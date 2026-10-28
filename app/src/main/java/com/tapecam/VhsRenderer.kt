package com.tapecam

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.Preview
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.random.Random

/**
 * Owns a GL thread. Camera frames arrive on a SurfaceTexture, get run through the
 * tape shader and are drawn to the on-screen SurfaceView and — while recording —
 * to the MediaRecorder's input surface. Photos are rendered into an offscreen FBO.
 */
class VhsRenderer(private val osd: OsdOverlay) : SurfaceTexture.OnFrameAvailableListener {

    companion object {
        const val VIDEO_W = 960
        const val VIDEO_H = 720
        const val PHOTO_W = 1440
        const val PHOTO_H = 1080
        private const val TAPE_W = 640f   // virtual "tape" resolution for grain/scanlines
        private const val TAPE_H = 480f
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val TAG = "VhsRenderer"
    }

    @Volatile var intensity = 0.7f
    @Volatile var frontCamera = false

    private val thread = HandlerThread("VhsGL").also { it.start() }
    private val handler = Handler(thread.looper)
    private val glExecutor = Executor { handler.post(it) }

    // EGL
    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displaySurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displayW = 0
    private var displayH = 0
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // GL program
    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uCam = 0
    private var uOsd = 0
    private var uCamMatrix = 0
    private var uTime = 0
    private var uIntensity = 0
    private var uGlitch = 0
    private var uRes = 0
    private var osdTex = 0
    private var osdAllocated = false

    // Camera input
    private var camTexture: SurfaceTexture? = null
    private var camTexId = 0
    private var bufW = 1280
    private var bufH = 960
    /** Clockwise degrees to turn the camera buffer upright; set by the activity. */
    @Volatile var rotationDeg = 0
    private val stMatrix = FloatArray(16)
    private val adjust = FloatArray(16)
    private val camMatrix = FloatArray(16)

    // Effect state
    private val startMs = SystemClock.elapsedRealtime()
    private var lastFrameMs = 0L
    private var frameTime = 0f
    private var glitch = 0f

    // Photo
    private var pendingPhoto: ((Bitmap) -> Unit)? = null
    private var photoFbo = 0
    private var photoTex = 0

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
            ))
            position(0)
        }

    init {
        handler.post { initGl() }
    }

    // ---------------------------------------------------------------- public API

    /** Hand this to CameraX `Preview.setSurfaceProvider`. */
    val surfaceProvider = Preview.SurfaceProvider { request ->
        handler.post {
            makeCurrent(pbuffer)
            val texId = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            val st = SurfaceTexture(texId)
            val res = request.resolution
            st.setDefaultBufferSize(res.width, res.height)
            val surface = Surface(st)

            st.setOnFrameAvailableListener(this, handler)

            camTexture = st
            camTexId = texId
            bufW = res.width
            bufH = res.height

            request.provideSurface(surface, glExecutor) {
                surface.release()
                st.release()
                makeCurrent(pbuffer)
                GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                if (camTexture === st) camTexture = null
            }
        }
    }

    fun setDisplaySurface(surface: Surface?, width: Int, height: Int) = runSync {
        if (displaySurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(eglDisplay, displaySurface)
            displaySurface = EGL14.EGL_NO_SURFACE
        }
        if (surface != null && surface.isValid) {
            displaySurface = createWindowSurface(surface)
            displayW = width
            displayH = height
        }
    }

    fun setEncoderSurface(surface: Surface?) = runSync {
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(eglDisplay, encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
        if (surface != null) encoderSurface = createWindowSurface(surface)
    }

    fun triggerGlitch() = handler.post { glitch = 1f }

    /** Callback runs on the GL thread with a freshly rendered, upright bitmap. */
    fun requestPhoto(callback: (Bitmap) -> Unit) = handler.post { pendingPhoto = callback }

    fun release() {
        handler.post {
            camTexture?.release()
            camTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                for (s in listOf(displaySurface, encoderSurface, pbuffer)) {
                    if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, s)
                }
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            thread.quitSafely()
        }
    }

    // ---------------------------------------------------------------- frame loop

    override fun onFrameAvailable(st: SurfaceTexture) {
        if (st !== camTexture || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        makeCurrent(pbuffer)
        try {
            st.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage failed", e)
            return
        }
        st.getTransformMatrix(stMatrix)
        updateCamMatrix()
        renderAll()
    }

    private fun renderAll() {
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.033f else ((now - lastFrameMs) / 1000f).coerceAtMost(0.2f)
        lastFrameMs = now
        frameTime = ((now - startMs) / 1000f) % 1000f

        glitch = max(0f, glitch - dt * 1.5f)
        if (Random.nextFloat() < 0.003f * intensity) glitch = max(glitch, 0.35f)

        if (osd.update(now)) uploadOsd()

        if (displaySurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(displaySurface)
            draw(displayW, displayH)
            EGL14.eglSwapBuffers(eglDisplay, displaySurface)
        }
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            makeCurrent(encoderSurface)
            draw(VIDEO_W, VIDEO_H)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderSurface, System.nanoTime())
            EGL14.eglSwapBuffers(eglDisplay, encoderSurface)
        }
        pendingPhoto?.let { cb ->
            pendingPhoto = null
            cb(renderPhoto())
        }
    }

    private fun draw(w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camTexId)
        GLES20.glUniform1i(uCam, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        GLES20.glUniform1i(uOsd, 1)

        GLES20.glUniformMatrix4fv(uCamMatrix, 1, false, camMatrix, 0)
        GLES20.glUniform1f(uTime, frameTime)
        GLES20.glUniform1f(uIntensity, intensity)
        GLES20.glUniform1f(uGlitch, glitch)
        GLES20.glUniform2f(uRes, TAPE_W, TAPE_H)

        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Maps output UV (upright, 4:3) to camera-buffer UV: centre-crop to 4:3,
     * mirror for the selfie camera, rotate to upright, then apply the
     * SurfaceTexture's own transform.
     */
    private fun updateCamMatrix() {
        val rot = rotationDeg
        val swapped = rot % 180 != 0
        val bw = (if (swapped) bufH else bufW).toFloat()
        val bh = (if (swapped) bufW else bufH).toFloat()
        val aspect = bw / bh
        val target = 4f / 3f
        var sx = 1f
        var sy = 1f
        if (aspect > target) sx = target / aspect else sy = aspect / target
        if (frontCamera) sx = -sx

        Matrix.setIdentityM(adjust, 0)
        Matrix.translateM(adjust, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(adjust, 0, rot.toFloat(), 0f, 0f, 1f)
        Matrix.scaleM(adjust, 0, sx, sy, 1f)
        Matrix.translateM(adjust, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(camMatrix, 0, stMatrix, 0, adjust, 0)
    }

    private fun uploadOsd() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTex)
        if (!osdAllocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, osd.bitmap, 0)
            osdAllocated = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, osd.bitmap)
        }
    }

    private fun renderPhoto(): Bitmap {
        makeCurrent(pbuffer)
        if (photoFbo == 0) {
            photoTex = genTexture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, PHOTO_W, PHOTO_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val ids = IntArray(1)
            GLES20.glGenFramebuffers(1, ids, 0)
            photoFbo = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, photoFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, photoTex, 0)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, photoFbo)
        draw(PHOTO_W, PHOTO_H)
        val buf = ByteBuffer.allocateDirect(PHOTO_W * PHOTO_H * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, PHOTO_W, PHOTO_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        val raw = Bitmap.createBitmap(PHOTO_W, PHOTO_H, Bitmap.Config.ARGB_8888)
        buf.rewind()
        raw.copyPixelsFromBuffer(buf)
        val flip = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val upright = Bitmap.createBitmap(raw, 0, 0, PHOTO_W, PHOTO_H, flip, false)
        raw.recycle()
        return upright
    }

    // ---------------------------------------------------------------- EGL / GL setup

    private fun initGl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        eglConfig = chooseConfig(withPbuffer = true) ?: chooseConfig(withPbuffer = false)
                ?: error("No suitable EGL config")
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)

        pbuffer = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            ?: EGL14.EGL_NO_SURFACE   // falls back to a surfaceless context
        makeCurrent(pbuffer)

        program = buildProgram(VhsShaders.VERTEX, VhsShaders.FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uCam = GLES20.glGetUniformLocation(program, "uCam")
        uOsd = GLES20.glGetUniformLocation(program, "uOsd")
        uCamMatrix = GLES20.glGetUniformLocation(program, "uCamMatrix")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity")
        uGlitch = GLES20.glGetUniformLocation(program, "uGlitch")
        uRes = GLES20.glGetUniformLocation(program, "uRes")

        osdTex = genTexture(GLES20.GL_TEXTURE_2D)
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(camMatrix, 0)
    }

    private fun chooseConfig(withPbuffer: Boolean): EGLConfig? {
        val surfaceType = if (withPbuffer) EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, surfaceType,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)
        return if (num[0] > 0) configs[0] else null
    }

    private fun createWindowSurface(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)

    private fun makeCurrent(s: EGLSurface) {
        EGL14.eglMakeCurrent(eglDisplay, s, s, eglContext)
    }

    private fun genTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}")
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
        return p
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) { block(); return }
        val latch = CountDownLatch(1)
        val posted = handler.post { try { block() } finally { latch.countDown() } }
        if (posted) latch.await()
    }
}
