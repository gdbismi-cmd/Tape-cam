package com.tapecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.SystemClock
import java.util.Calendar
import java.util.Locale

/**
 * Draws the camcorder on-screen display (STBY / REC, tape counter, SP, battery,
 * zoom bar, date and time stamp) into a portrait 3:4 bitmap that the renderer
 * uploads as a texture. Only redraws when what's visible actually changes.
 */
class OsdOverlay {

    companion object {
        const val W = 720
        const val H = 960
        private const val ZOOM_SHOW_MS = 2000L
        private const val ZOOM_SEGMENTS = 16
        private val MONTHS = arrayOf("JAN.", "FEB.", "MAR.", "APR.", "MAY ", "JUN.",
            "JUL.", "AUG.", "SEP.", "OCT.", "NOV.", "DEC.")
    }

    val bitmap: Bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    /** elapsedRealtime when recording started, or 0 when idle. */
    @Volatile var recordingStartMs = 0L
    @Volatile var dateEnabled = true
    /** Offset added to the real clock so the stamp can read any date you like. */
    @Volatile var dateOffsetMs = 0L
    @Volatile var photoMode = false

    /** 0 = widest, 1 = full tele. */
    @Volatile var zoomFraction = 0f
        private set
    @Volatile private var zoomShownUntil = 0L

    /** Call whenever zoom changes; the W–T bar appears for a couple of seconds. */
    fun showZoom(fraction: Float) {
        zoomFraction = fraction.coerceIn(0f, 1f)
        zoomShownUntil = SystemClock.elapsedRealtime() + ZOOM_SHOW_MS
    }

    private var lastKey = ""

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 44f
        color = Color.WHITE
        setShadowLayer(3f, 3f, 3f, Color.argb(210, 0, 0, 0))
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        setShadowLayer(3f, 3f, 3f, Color.argb(210, 0, 0, 0))
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(2f, 2f, 2f, Color.argb(180, 0, 0, 0))
    }
    private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 40, 40)
        setShadowLayer(3f, 3f, 3f, Color.argb(210, 0, 0, 0))
    }

    /** Returns true when the bitmap changed and needs re-uploading. */
    fun update(nowElapsedMs: Long): Boolean {
        val rec = recordingStartMs
        val blink = (nowElapsedMs / 500) % 2 == 0L

        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() + dateOffsetMs
        }
        val dateStr = String.format(Locale.US, "%s %2d %d",
            MONTHS[cal.get(Calendar.MONTH)], cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.YEAR))
        val hour = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        val ampm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        val timeStr = String.format(Locale.US, "%s %2d:%02d", ampm, hour, cal.get(Calendar.MINUTE))

        val counter = if (rec > 0) {
            val s = (nowElapsedMs - rec) / 1000
            String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        } else ""

        val zoomVisible = nowElapsedMs < zoomShownUntil
        val zoomLit = if (zoomVisible) Math.round(zoomFraction * ZOOM_SEGMENTS) else -1

        val key = "$rec|$blink|$dateEnabled|$dateStr|$timeStr|$counter|$photoMode|$zoomLit"
        if (key == lastKey) return false
        lastKey = key

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Top left: status
        when {
            rec > 0 -> {
                if (blink) canvas.drawCircle(66f, 82f, 15f, red)
                canvas.drawText("REC", 92f, 98f, text)
                canvas.drawText(counter, 48f, 156f, text)
            }
            photoMode -> canvas.drawText("PHOTO", 48f, 98f, text)
            else -> canvas.drawText("STBY", 48f, 98f, text)
        }

        // Top right: tape speed + battery
        canvas.drawText("SP", 512f, 98f, text)
        canvas.drawRect(590f, 66f, 662f, 100f, stroke)
        canvas.drawRect(662f, 76f, 670f, 90f, fill)
        for (i in 0 until 3) {
            val x = 596f + i * 22f
            canvas.drawRect(x, 72f, x + 16f, 94f, fill)
        }

        // Zoom bar: W ▮▮▮▮▯▯▯▯ T
        if (zoomVisible) {
            val y = 228f
            canvas.drawText("W", 92f, y + 14f, text)
            canvas.drawText("T", 602f, y + 14f, text)
            val left = 136f
            val right = 588f
            val seg = (right - left) / ZOOM_SEGMENTS
            for (i in 0 until ZOOM_SEGMENTS) {
                val x = left + i * seg + 3f
                if (i < zoomLit) canvas.drawRect(x, y - 14f, x + seg - 6f, y + 14f, fill)
                else canvas.drawRect(x, y - 2f, x + seg - 6f, y + 2f, fill)
            }
        }

        // Bottom left: date stamp
        if (dateEnabled) {
            canvas.drawText(timeStr, 48f, H - 132f, text)
            canvas.drawText(dateStr, 48f, H - 72f, text)
        }
        return true
    }
}
