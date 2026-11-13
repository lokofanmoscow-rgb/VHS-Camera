package com.vhscamera.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/** Paints pixel-font text in one colour, optionally with the dark edge of a viewfinder display. */
class PixelPainter(private val font: PixelFont) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val filters = HashMap<Int, PorterDuffColorFilter>()

    fun width(text: String, glyphHeight: Float) = font.width(text, glyphHeight)

    fun draw(canvas: Canvas, text: String, x: Float, y: Float, glyphHeight: Float, color: Int, edge: Boolean = false) {
        if (edge) {
            val o = glyphHeight * 0.07f
            paint.colorFilter = filter(0xC0000000.toInt())
            font.draw(canvas, text, x + o, y + o, glyphHeight, paint)
        }
        paint.colorFilter = filter(color)
        font.draw(canvas, text, x, y, glyphHeight, paint)
    }

    private fun filter(color: Int) = filters.getOrPut(color) { PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }
}

/** A rubber key of the camcorder body with an engraved label and, for toggles, a small lamp. */
class KeyButton(context: Context, private val painter: PixelPainter, private val label: String) : View(context) {
    /** Lamp in the corner: null = no lamp, false = dark, true = lit. */
    var lamp: Boolean? = null
        set(value) {
            field = value
            invalidate()
        }

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF0A0A0B.toInt() }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val d = resources.displayMetrics.density

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pressed = isPressed
        val inset = d * 1.5f
        val drop = if (pressed) d * 1f else 0f
        rect.set(inset, inset + drop, w - inset, h - inset + drop)
        val r = d * 6f
        val top = if (pressed) 0xFF8E8C84.toInt() else 0xFFD6D4CB.toInt()
        val bottom = if (pressed) 0xFF7E7C75.toInt() else 0xFFA3A199.toInt()
        body.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        body.alpha = if (isEnabled) 255 else 110
        canvas.drawRoundRect(rect, r, r, body)
        rim.strokeWidth = d * 1.5f
        canvas.drawRoundRect(rect, r, r, rim)
        if (!pressed) {
            shine.strokeWidth = d
            canvas.drawLine(rect.left + r, rect.top + d * 1.6f, rect.right - r, rect.top + d * 1.6f, shine)
        }

        val gh = minOf(h * 0.40f, (w * 0.80f) / (label.length * PixelFont.ADVANCE))
        val tw = painter.width(label, gh)
        painter.draw(canvas, label, (w - tw) / 2f, (h - gh) / 2f + drop, gh, if (isEnabled) 0xFF1E1E21.toInt() else 0xFF55554F.toInt())

        val l = lamp
        if (l != null) {
            dot.color = if (l) 0xFFFFB02E.toInt() else 0xFF4A3A22.toInt()
            canvas.drawCircle(w - d * 8f, d * 8f + drop, d * 2.4f, dot)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** The zoom rocker: press the upper half for telephoto, the lower half for wide. */
class ZoomRocker(context: Context, private val painter: PixelPainter) : View(context) {
    /** Called with +1 (T), -1 (W) or 0 (released). */
    var onZoom: ((Int) -> Unit)? = null

    private var dir = 0
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF0A0A0B.toInt() }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38000000 }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x70000000; style = Paint.Style.STROKE }
    private val rect = RectF()
    private val half = RectF()
    private val d = resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = d * 1.5f
        rect.set(inset, inset, w - inset, h - inset)
        val r = minOf(w, h) * 0.42f
        body.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFFB9B7AF.toInt(), 0xFFDAD8CF.toInt(), 0xFFA9A79F.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, body)
        if (dir != 0) {
            canvas.save()
            if (dir > 0) half.set(rect.left, rect.top, rect.right, h / 2f) else half.set(rect.left, h / 2f, rect.right, rect.bottom)
            canvas.clipRect(half)
            canvas.drawRoundRect(rect, r, r, shade)
            canvas.restore()
        }
        rim.strokeWidth = d * 1.5f
        canvas.drawRoundRect(rect, r, r, rim)
        groove.strokeWidth = d * 1.2f
        canvas.drawLine(rect.left + d * 6f, h / 2f, rect.right - d * 6f, h / 2f, groove)

        val gh = w * 0.34f
        val ink = 0xFF1E1E21.toInt()
        painter.draw(canvas, "T", (w - painter.width("T", gh)) / 2f, h * 0.25f - gh / 2f + (if (dir > 0) d else 0f), gh, ink)
        painter.draw(canvas, "W", (w - painter.width("W", gh)) / 2f, h * 0.75f - gh / 2f + (if (dir < 0) d else 0f), gh, ink)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val newDir = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> if (event.y < height / 2f) 1 else -1
            else -> 0
        }
        if (newDir != dir) {
            dir = newDir
            onZoom?.invoke(dir)
            invalidate()
        }
        return true
    }
}

/** The record key: a red dome in a dark bezel. */
class RecKey(context: Context) : View(context) {
    /** 0 = stand-by, 1 = recording, 2 = saving. */
    var mode = 0
        set(value) {
            field = value
            invalidate()
        }

    private val bezel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dome = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF050506.toInt() }

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        bezel.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0xFF55565B.toInt(), 0xFF1A1B1E.toInt(), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * 0.97f, bezel)
        rim.strokeWidth = r * 0.05f
        canvas.drawCircle(cx, cy, r * 0.97f, rim)
        val rr = r * (if (isPressed) 0.70f else 0.74f)
        val (inner, outer) = when {
            mode == 2 -> 0xFF8A8A8A.toInt() to 0xFF4C4C4C.toInt()
            mode == 1 -> 0xFFFF6A5E.toInt() to 0xFFD3160C.toInt()
            isPressed -> 0xFFC43A30.toInt() to 0xFF7E100A.toInt()
            else -> 0xFFE8483C.toInt() to 0xFF9E130B.toInt()
        }
        dome.shader = RadialGradient(cx - rr * 0.25f, cy - rr * 0.3f, rr * 1.25f, inner, outer, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rr, dome)
        canvas.drawCircle(cx, cy, rr, rim)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** Small lamp next to a printed label on the body (the tally light). */
class Lamp(context: Context) : View(context) {
    var lit = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f
        paint.color = if (lit) 0xFFFF3B2E.toInt() else 0xFF4A1512.toInt()
        canvas.drawCircle(width / 2f, height / 2f, r * 0.8f, paint)
    }
}

/** Label printed on the camcorder body. */
class BodyLabel(context: Context, private val painter: PixelPainter, private val text: String) : View(context) {
    override fun onDraw(canvas: Canvas) {
        val gh = height.toFloat()
        painter.draw(canvas, text, (width - painter.width(text, gh)) / 2f, 0f, gh, 0xFFB8B6AE.toInt())
    }
}

/**
 * What the electronic viewfinder shows on top of the picture and what never reaches the
 * tape: STBY / REC with a blinking dot, the tape counter and the zoom bar.
 */
class Viewfinder(context: Context, private val painter: PixelPainter) : View(context) {
    /** 0 = stand-by, 1 = recording, 2 = saving. */
    var mode = 0
    var recordedMillis = 0L
    var zoom = 1f
    var maxZoom = 6f
    var zoomBarUntil = 0L

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val k = h / Osd.HEIGHT                      // same geometry as the recorded stamp
        val gh = Osd.GLYPH_H * k
        val right = w - Osd.X0 * k
        val y1 = Osd.PLAY_Y * k
        val now = android.os.SystemClock.elapsedRealtime()

        val status = when (mode) {
            1 -> "REC"
            2 -> "SAVE"
            else -> "STBY"
        }
        val sw = painter.width(status, gh)
        painter.draw(canvas, status, right - sw, y1, gh, WHITE, true)
        if (mode == 1 && now / 500 % 2 == 0L) {
            painter.draw(canvas, "*", right - sw - PixelFont.ADVANCE * gh * 1.15f, y1, gh, RED, true)
        }

        val s = recordedMillis / 1000
        val counter = "${s / 3600}:${two(s / 60 % 60)}:${two(s % 60)}"
        painter.draw(canvas, counter, right - painter.width(counter, gh), y1 + 53.5f * k, gh, WHITE, true)

        if (now < zoomBarUntil) {
            val y = 582.3f * k
            val barW = 150f * k
            val tw = painter.width("T", gh)
            painter.draw(canvas, "T", right - tw, y, gh, WHITE, true)
            val bx1 = right - tw - 12f * k
            val bx0 = bx1 - barW
            painter.draw(canvas, "W", bx0 - 12f * k - painter.width("W", gh), y, gh, WHITE, true)
            rect.set(bx0, y + gh * 0.28f, bx1, y + gh * 0.72f)
            line.strokeWidth = 3f * k
            line.color = 0xC0000000.toInt()
            canvas.save()
            canvas.translate(2f * k, 2f * k)
            canvas.drawRect(rect, line)
            canvas.restore()
            line.color = WHITE
            canvas.drawRect(rect, line)
            val f = ((zoom - 1f) / (maxZoom - 1f)).coerceIn(0f, 1f)
            val knob = 10f * k
            val kx = rect.left + (rect.width() - knob) * f
            fill.color = WHITE
            canvas.drawRect(kx, rect.top, kx + knob, rect.bottom, fill)
        }
    }

    private fun two(v: Long) = if (v < 10) "0$v" else "$v"

    companion object {
        private const val WHITE = 0xFFF2F0E8.toInt()
        private const val RED = 0xFFFF3B2E.toInt()
    }
}

/** The on-screen menu of the camcorder: white characters on blue, one tap per line. */
class MenuPanel(context: Context, private val painter: PixelPainter) : View(context) {
    class Item(val label: String, val value: () -> String, val enabled: () -> Boolean, val action: () -> Unit)

    var items: List<Item> = emptyList()
    private val bg = Paint().apply { color = 0xF01A2CC8.toInt() }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFF2F0E8.toInt() }
    private var pressedRow = -1
    private val hl = Paint().apply { color = 0x40FFFFFF }

    private fun rowHeight() = height / (items.size + 2f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        val rh = rowHeight()
        frame.strokeWidth = rh * 0.06f
        val m = rh * 0.22f
        canvas.drawRect(m, m, w - m, h - m, frame)
        val gh = rh * 0.50f
        val title = "MENU"
        painter.draw(canvas, title, (w - painter.width(title, gh)) / 2f, rh * 0.5f + (rh - gh) / 2f - rh * 0.15f, gh, WHITE)
        val left = rh * 0.9f
        for (i in items.indices) {
            val item = items[i]
            val top = rh * (i + 1.5f)
            if (i == pressedRow) canvas.drawRect(m, top, w - m, top + rh, hl)
            val color = if (item.enabled()) WHITE else 0xFF8C93D8.toInt()
            val y = top + (rh - gh) / 2f
            painter.draw(canvas, item.label, left, y, gh, color)
            val v = item.value()
            painter.draw(canvas, v, w - left - painter.width(v, gh), y, gh, color)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val row = Math.floor(((event.y / rowHeight()) - 1.5f).toDouble()).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val r = if (row in items.indices) row else -1
                if (r != pressedRow) {
                    pressedRow = r
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                pressedRow = -1
                if (row in items.indices && items[row].enabled()) items[row].action()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow = -1
                invalidate()
            }
        }
        return true
    }

    companion object {
        private const val WHITE = 0xFFF2F0E8.toInt()
    }
}
