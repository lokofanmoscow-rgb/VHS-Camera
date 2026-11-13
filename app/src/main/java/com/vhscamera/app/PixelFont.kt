package com.vhscamera.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * The camcorder's character generator: a 5x7 dot-matrix font, smoothed with two rounds
 * of Scale2x so that diagonals come out as strokes rather than staircases. Glyphs are
 * white on transparent; tint them with a colour filter on the paint.
 */
class PixelFont(fontText: String) {
    private val glyphs = HashMap<Char, Bitmap>()
    private val dst = RectF()

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
        val px = IntArray(w * h) { if (b[it]) Color.WHITE else Color.TRANSPARENT }
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

    /** Width of [text] when its glyphs are [glyphHeight] tall. */
    fun width(text: String, glyphHeight: Float): Float =
        if (text.isEmpty()) 0f else (text.length - 1) * ADVANCE * glyphHeight + GLYPH_W * glyphHeight

    /** Draws [text] with the top-left corner of its first glyph at ([x], [y]). */
    fun draw(canvas: Canvas, text: String, x: Float, y: Float, glyphHeight: Float, paint: Paint) {
        val gw = GLYPH_W * glyphHeight
        val adv = ADVANCE * glyphHeight
        for (i in text.indices) {
            val g = glyphs[text[i]] ?: continue
            val gx = x + i * adv
            dst.set(gx, y, gx + gw, y + glyphHeight)
            canvas.drawBitmap(g, null, dst, paint)
        }
    }

    companion object {
        /** Glyph width and character advance as fractions of the glyph height (as on the reference tapes). */
        const val GLYPH_W = 23.5f / 39.5f
        const val ADVANCE = 30.2f / 39.5f
        private val ROW = Regex("[.#]{5}")
    }
}
