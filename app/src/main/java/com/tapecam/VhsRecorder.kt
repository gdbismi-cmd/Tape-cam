package com.tapecam

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * MediaRecorder fed from a Surface: the renderer draws the processed tape image
 * into [start]'s returned surface, MediaRecorder encodes it with mic audio.
 */
class VhsRecorder(private val context: Context, private val withAudio: Boolean) {

    private var recorder: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    fun start(): Surface {
        val u = MediaSaver.createVideo(context, MediaSaver.newName("TAPE_"))
            ?: error("Could not create video file")
        uri = u
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
                 else @Suppress("DEPRECATION") MediaRecorder()
        try {
            val fd = context.contentResolver.openFileDescriptor(u, "w")
                ?: error("Could not open video file")
            pfd = fd

            if (withAudio) mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setOutputFile(fd.fileDescriptor)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mr.setVideoSize(VhsRenderer.VIDEO_W, VhsRenderer.VIDEO_H)
            mr.setVideoFrameRate(30)
            mr.setVideoEncodingBitRate(3_500_000)
            if (withAudio) {
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(32_000)
                mr.setAudioEncodingBitRate(96_000)
                mr.setAudioChannels(1)
            }
            mr.prepare()
            val surface = mr.surface
            mr.start()
            recorder = mr
            return surface
        } catch (e: Exception) {
            mr.release()
            pfd?.close()
            context.contentResolver.delete(u, null, null)
            throw e
        }
    }

    /** Returns the saved video's Uri, or null if the clip was too short / failed. */
    fun stop(): Uri? {
        val mr = recorder ?: return null
        recorder = null
        val ok = try { mr.stop(); true } catch (e: RuntimeException) { false }
        mr.release()
        pfd?.close()
        val u = uri ?: return null
        return if (ok) {
            MediaSaver.finish(context, u); u
        } else {
            context.contentResolver.delete(u, null, null); null
        }
    }
}
