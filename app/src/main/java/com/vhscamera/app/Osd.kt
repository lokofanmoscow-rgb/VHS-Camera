package com.vhscamera.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import java.util.Calendar

/**
 * The recorded on-screen display: "PLAY" and the date stamp, drawn into a 960x720 bitmap
 * that the tape shader burns into the picture before the VHS filter.
 */
class Osd(private val font: PixelFont) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(TEXT_COLOR, PorterDuff.Mode.SRC_IN)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR; style = Paint.Style.FILL }
    private val path = Path()

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    /**
     * Redraws the display. [showPlay] adds the "PLAY▶" indicator, [stamp] the date and time;
     * [edged] gives the characters the black edging of a camcorder's character generator.
     */
    fun draw(showPlay: Boolean, stamp: Boolean, timeMillis: Long, edged: Boolean = false) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (showPlay) {
            font.draw(canvas, "PLAY", X0, PLAY_Y, GLYPH_H, paint)
            val tx = X0 + 4 * PixelFont.ADVANCE * GLYPH_H + 6.0f
            val ty = PLAY_Y + GLYPH_H / 2f
            path.rewind()
            path.moveTo(tx, ty - 13.5f)
            path.lineTo(tx + 22.5f, ty)
            path.lineTo(tx, ty + 13.5f)
            path.close()
            canvas.drawPath(path, fill)
        }
        if (stamp) {
            if (edged) {
                for (dx in EDGE_OFFSETS) for (dy in EDGE_OFFSETS) {
                    if (dx == 0f && dy == 0f) continue
                    font.draw(canvas, timeLine(timeMillis), X0 + dx, LINE1_Y + dy, GLYPH_H, edgePaint)
                    font.draw(canvas, dateLine(timeMillis), X0 + dx, LINE2_Y + dy, GLYPH_H, edgePaint)
                }
            }
            font.draw(canvas, timeLine(timeMillis), X0, LINE1_Y, GLYPH_H, paint)
            font.draw(canvas, dateLine(timeMillis), X0, LINE2_Y, GLYPH_H, paint)
        }
    }

    companion object {
        const val WIDTH = 960
        const val HEIGHT = 720
        const val GLYPH_H = 39.5f
        const val X0 = 100.8f
        const val PLAY_Y = 108.8f
        private const val LINE1_Y = 528.8f
        private const val LINE2_Y = 582.3f
        private val TEXT_COLOR = Color.rgb(210, 210, 203)
        private val EDGE_OFFSETS = floatArrayOf(-2.5f, 0f, 2.5f)
        private val MONTHS = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

        /** "PM  3:02" — AM/PM first, hour right-aligned without a leading zero. */
        fun timeLine(timeMillis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = timeMillis }
            val h24 = c.get(Calendar.HOUR_OF_DAY)
            val h12 = if (h24 % 12 == 0) 12 else h24 % 12
            val ampm = if (h24 < 12) "AM" else "PM"
            val hour = if (h12 < 10) " $h12" else "$h12"
            val m = c.get(Calendar.MINUTE)
            val min = if (m < 10) "0$m" else "$m"
            return "$ampm $hour:$min"
        }

        /** "MAR.09 2025" */
        fun dateLine(timeMillis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = timeMillis }
            val d = c.get(Calendar.DAY_OF_MONTH)
            val day = if (d < 10) "0$d" else "$d"
            return MONTHS[c.get(Calendar.MONTH)] + "." + day + " " + c.get(Calendar.YEAR)
        }
    }
}
