package com.tapecam

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val osd = OsdOverlay()
    private lateinit var renderer: VhsRenderer
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var useFront = false
    private var photoMode = false
    private var recorder: VhsRecorder? = null

    private lateinit var previewView: SurfaceView
    private lateinit var shutter: View
    private lateinit var flash: View
    private lateinit var btnMode: TextView
    private lateinit var btnFlip: TextView
    private lateinit var btnDate: TextView
    private lateinit var btnTrack: TextView
    private lateinit var fxSeek: SeekBar

    private val prefs by lazy { getSharedPreferences("tapecam", Context.MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (has(Manifest.permission.CAMERA)) startCamera()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        renderer = VhsRenderer(osd)

        previewView = findViewById(R.id.preview)
        shutter = findViewById(R.id.btnShutter)
        flash = findViewById(R.id.flash)
        btnMode = findViewById(R.id.btnMode)
        btnFlip = findViewById(R.id.btnFlip)
        btnDate = findViewById(R.id.btnDate)
        btnTrack = findViewById(R.id.btnTrack)
        fxSeek = findViewById(R.id.fxSeek)

        previewView.holder.addCallback(this)

        // Restore settings
        osd.dateEnabled = prefs.getBoolean("date_on", true)
        osd.dateOffsetMs = prefs.getLong("date_offset", 0L)
        val fx = prefs.getInt("fx", 70)
        fxSeek.progress = fx
        renderer.intensity = fx / 100f

        fxSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                renderer.intensity = p / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                prefs.edit().putInt("fx", s.progress).apply()
            }
        })

        shutter.setOnClickListener { if (photoMode) takePhoto() else toggleRecording() }
        btnMode.setOnClickListener { toggleMode() }
        btnFlip.setOnClickListener { flipCamera() }
        btnTrack.setOnClickListener { renderer.triggerGlitch() }
        btnDate.setOnClickListener {
            osd.dateEnabled = !osd.dateEnabled
            prefs.edit().putBoolean("date_on", osd.dateEnabled).apply()
            updateUi()
        }
        btnDate.setOnLongClickListener { pickDate(); true }

        // Manual fallback: turn the picture 90° at a time (remembered per camera).
        // Long-press resets to automatic.
        val btnRotate = findViewById<TextView>(R.id.btnRotate)
        btnRotate.setOnClickListener {
            val key = rotationKey()
            prefs.edit().putInt(key, (prefs.getInt(key, 0) + 90) % 360).apply()
            syncRotation()
        }
        btnRotate.setOnLongClickListener {
            prefs.edit().remove(rotationKey()).apply()
            syncRotation()
            toast("Rotation reset to automatic")
            true
        }

        updateUi()
        requestPermissions()
    }

    // ---------------------------------------------------------------- camera

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 29) perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
        val missing = perms.filterNot { has(it) }
        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        renderer.frontCamera = useFront

        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .setTargetRotation(displayRotation())
            .build()
        preview.setSurfaceProvider(renderer.surfaceProvider)
        this.preview = preview

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview)
            syncRotation()
        } catch (e: Exception) {
            Toast.makeText(this, "Camera unavailable: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun flipCamera() {
        if (recorder != null) return
        val provider = cameraProvider ?: return
        val next = if (useFront) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        if (!provider.hasCamera(next)) return
        useFront = !useFront
        bindCamera()
    }

    // ---------------------------------------------------------------- capture

    private fun toggleMode() {
        if (recorder != null) return
        photoMode = !photoMode
        osd.photoMode = photoMode
        updateUi()
    }

    private fun takePhoto() {
        flash.alpha = 0.85f
        flash.animate().alpha(0f).setDuration(250).start()
        renderer.requestPhoto { bmp ->
            io.execute {
                val uri = MediaSaver.saveJpeg(applicationContext, bmp)
                bmp.recycle()
                runOnUiThread {
                    toast(if (uri != null) "Saved to Pictures/TapeCam" else "Couldn't save photo")
                }
            }
        }
    }

    private fun toggleRecording() {
        if (recorder == null) startRecording() else stopRecording()
    }

    private fun startRecording() {
        val rec = VhsRecorder(applicationContext, has(Manifest.permission.RECORD_AUDIO))
        val surface = try {
            rec.start()
        } catch (e: Exception) {
            toast("Couldn't start recording: ${e.message}")
            return
        }
        renderer.setEncoderSurface(surface)
        recorder = rec
        osd.recordingStartMs = SystemClock.elapsedRealtime()
        updateUi()
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        renderer.setEncoderSurface(null)
        osd.recordingStartMs = 0L
        updateUi()
        io.execute {
            val uri = rec.stop()
            runOnUiThread {
                toast(if (uri != null) "Saved to Movies/TapeCam" else "Clip too short — not saved")
            }
        }
    }

    // ---------------------------------------------------------------- date stamp

    private fun pickDate() {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() + osd.dateOffsetMs
        }
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, hh, mm ->
                val target = Calendar.getInstance().apply { set(y, m, d, hh, mm, 0) }
                osd.dateOffsetMs = target.timeInMillis - System.currentTimeMillis()
                osd.dateEnabled = true
                prefs.edit()
                    .putLong("date_offset", osd.dateOffsetMs)
                    .putBoolean("date_on", true)
                    .apply()
                updateUi()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            setButton(DatePickerDialog.BUTTON_NEUTRAL, "Today") { _, _ ->
                osd.dateOffsetMs = 0L
                prefs.edit().putLong("date_offset", 0L).apply()
            }
        }.show()
    }

    // ---------------------------------------------------------------- UI

    private fun updateUi() {
        val recording = recorder != null
        shutter.setBackgroundResource(
            when {
                photoMode -> R.drawable.shutter_photo
                recording -> R.drawable.shutter_stop
                else -> R.drawable.shutter_rec
            }
        )
        btnMode.text = if (photoMode) "PHOTO" else "VIDEO"
        btnDate.text = if (osd.dateEnabled) "DATE ●" else "DATE ○"
        val idleAlpha = if (recording) 0.35f else 1f
        btnMode.alpha = idleAlpha
        btnFlip.alpha = idleAlpha
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- lifecycle

    override fun onPause() {
        super.onPause()
        if (recorder != null) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.release()
        io.shutdown()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.setDisplaySurface(holder.surface, width, height)
        syncRotation()
    }

    // The activity is locked to landscape but starts in portrait for a moment, and
    // with configChanges it is never recreated — so the rotation read at bind time
    // can be stale. Re-read it whenever the screen settles and tell CameraX.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncRotation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) syncRotation()
    }

    /**
     * Works out how far to turn the camera image from the sensor's mounting angle
     * and the current screen rotation, plus any manual correction from ROTATE.
     */
    private fun syncRotation() {
        val r = displayRotation()
        preview?.let { if (it.targetRotation != r) it.targetRotation = r }
        val cam = camera ?: return
        val auto = cam.cameraInfo.getSensorRotationDegrees(r)
        val manual = prefs.getInt(rotationKey(), 0)
        renderer.rotationDeg = (auto + manual) % 360
    }

    private fun rotationKey() = if (useFront) "rot_front" else "rot_back"

    private fun displayRotation(): Int =
        (previewView.display ?: ContextCompat.getDisplayOrDefault(this)).rotation

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderer.setDisplaySurface(null, 0, 0)
    }
}
