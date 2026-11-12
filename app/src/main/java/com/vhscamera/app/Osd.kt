package com.vhscamera.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import java.util.Calendar

/**
 * Camcorder on-screen display: a 5x7 dot-matrix font (smoothed with two rounds of
 * Scale2x so diagonals look like a character generator's) drawn into a 960x720 bitmap
 * that the tape shader burns into the picture before the VHS filter.
 */
class Osd(fontText: String) {
    private val glyphs = HashMap<Char, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR; style = Paint.Style.FILL }
    private val dst = RectF()
    private val path = Path()

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    init {
        val lines = fontText.split('\n').map { it.trimEnd('\r') }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val isGlyph = l.length == 1 && i + 7 < lines.size && (1..7).all { ROW.matches(lines[i + it]) }
            if (isGlyph) {
                glyphs[l[0]] = makeGlyph(lines.subList(i + 1, i + 8))
                i += 8
            } else {
                i++
            }
        }
    }

    private fun makeGlyph(rows: List<String>): Bitmap {
        var w = 5
        var h = 7
        var b = BooleanArray(w * h) { rows[it / w][it % w] == '#' }
        repeat(2) {
            b = scale2x(b, w, h)
            w *= 2
            h *= 2
        }
        val px = IntArray(w * h) { if (b[it]) TEXT_COLOR else Color.TRANSPARENT }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun scale2x(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val d = BooleanArray(w * h * 4)
        fun at(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) false else src[y * w + x]
        for (y in 0 until h) for (x in 0 until w) {
            val p = at(x, y)
            val a = at(x, y - 1)
            val bb = at(x + 1, y)
            val c = at(x - 1, y)
            val dd = at(x, y + 1)
            val o = (2 * y) * (2 * w) + 2 * x
            d[o] = if (c == a && c != dd && a != bb) a else p
            d[o + 1] = if (a == bb && a != c && bb != dd) bb else p
            d[o + 2 * w] = if (dd == c && dd != bb && c != a) c else p
            d[o + 2 * w + 1] = if (bb == dd && bb != a && dd != c) dd else p
        }
        return d
    }

    private fun text(s: String, y: Float) {
        for (i in s.indices) {
            val g = glyphs[s[i]] ?: continue
            val x = X0 + i * ADVANCE
            // Drawn four times, slightly offset, for the heavy strokes of a character generator.
            for (k in 0 until 4) {
                val ox = if (k % 2 == 1) BOLD_X else 0f
                val oy = if (k >= 2) BOLD_Y else 0f
                dst.set(x + ox, y + oy, x + ox + GLYPH_W, y + oy + GLYPH_H)
                canvas.drawBitmap(g, null, dst, paint)
            }
        }
    }

    /** Redraws the display. [showPlay] adds the "PLAY▶" indicator, [stamp] the date and time. */
    fun draw(showPlay: Boolean, stamp: Boolean, timeMillis: Long) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (showPlay) {
            text("PLAY", PLAY_Y)
            val tx = X0 + 4 * ADVANCE + 6.0f
            val ty = PLAY_Y + GLYPH_H / 2f
            path.rewind()
            path.moveTo(tx, ty - 13.5f)
            path.lineTo(tx + 22.5f, ty)
            path.lineTo(tx, ty + 13.5f)
            path.close()
            canvas.drawPath(path, fill)
        }
        if (stamp) {
            text(timeLine(timeMillis), LINE1_Y)
            text(dateLine(timeMillis), LINE2_Y)
        }
    }

    companion object {
        const val WIDTH = 960
        const val HEIGHT = 720
        private const val GLYPH_W = 23.0f
        private const val GLYPH_H = 39.0f
        private const val BOLD_X = 1.4f
        private const val BOLD_Y = 1.2f
        private const val ADVANCE = 30.2f
        private const val X0 = 100.3f
        private const val PLAY_Y = 108.5f
        private const val LINE1_Y = 528.5f
        private const val LINE2_Y = 582.0f
        private val TEXT_COLOR = Color.rgb(222, 222, 215)
        private val ROW = Regex("[.#]{5}")
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
