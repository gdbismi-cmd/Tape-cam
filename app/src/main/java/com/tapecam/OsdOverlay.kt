package com.tapecam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import java.util.Calendar
import java.util.Locale

/**
 * Draws the camcorder on-screen display (STBY / REC, tape counter, SP, battery,
 * date and time stamp) into a bitmap that the renderer uploads as a texture.
 * Only redraws when the visible text actually changes.
 */
class OsdOverlay {

    companion object {
        const val W = 960
        const val H = 720
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

    private var lastKey = ""

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 46f
        color = Color.WHITE
        setShadowLayer(3f, 3f, 3f, Color.argb(210, 0, 0, 0))
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
        setShadowLayer(3f, 3f, 3f, Color.argb(210, 0, 0, 0))
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
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

        val key = "$rec|$blink|$dateEnabled|$dateStr|$timeStr|$counter|$photoMode"
        if (key == lastKey) return false
        lastKey = key

        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // Top left: status
        when {
            rec > 0 -> {
                if (blink) canvas.drawCircle(78f, 82f, 15f, red)
                canvas.drawText("REC", 104f, 98f, text)
                canvas.drawText(counter, 60f, 160f, text)
            }
            photoMode -> canvas.drawText("PHOTO", 60f, 98f, text)
            else -> canvas.drawText("STBY", 60f, 98f, text)
        }

        // Top right: tape speed + battery
        canvas.drawText("SP", 740f, 98f, text)
        canvas.drawRect(820f, 66f, 892f, 100f, stroke)
        canvas.drawRect(892f, 76f, 900f, 90f, fill)
        for (i in 0 until 3) {
            val x = 826f + i * 22f
            canvas.drawRect(x, 72f, x + 16f, 94f, fill)
        }

        // Bottom left: date stamp
        if (dateEnabled) {
            canvas.drawText(timeStr, 60f, 602f, text)
            canvas.drawText(dateStr, 60f, 662f, text)
        }
        return true
    }
}
