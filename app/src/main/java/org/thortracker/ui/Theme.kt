package org.thortracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Visual tokens. A dark slate bench (matching the shell base) with one hue per channel lane, an amber
 * playhead, rose for writing and cyan for listening. Grid text is monospaced; labels condensed sans.
 */
internal object Palette {
    const val BG = 0xFF181C22.toInt()
    const val LANE = 0xFF1C2129.toInt()
    const val BEAT = 0xFF212833.toInt()
    const val BAR = 0xFF28313D.toInt()
    const val RULE = 0xFF2E3946.toInt()
    const val TEXT = 0xFFDCE3EA.toInt()
    const val DIM = 0xFF7D8A99.toInt()
    const val FAINT = 0xFF46525F.toInt()
    const val PLAYHEAD = 0xFFEAA959.toInt()
    const val WRITE = 0xFFF0607A.toInt()
    const val LISTEN = 0xFF5FD0F0.toInt()
    const val CURSOR_FILL = 0xFF34404F.toInt()
    const val ERROR = 0xFFFF7A6B.toInt()

    private val CHANNELS = intArrayOf(
        0xFF7FD3C4.toInt(), // pulse 1: mint
        0xFF8FB8F0.toInt(), // pulse 2: periwinkle
        0xFFC9A0F0.toInt(), // wave: lilac
        0xFFF09A8F.toInt(), // noise: salmon
        0xFFB8D46A.toInt(),
        0xFFE8C46A.toInt(),
        0xFF6AC4E8.toInt(),
        0xFFE87AC4.toInt(),
    )

    fun channel(i: Int): Int = CHANNELS[Math.floorMod(i, CHANNELS.size)]

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}

/** Reusable paints and size metrics derived from the view's size and density. */
internal class Ink(density: Float) {
    val density = density
    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    val sans: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val sansBold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans }

    /** Base text size in px; set per layout. */
    var unit = 14f * density
        private set

    fun layout(width: Int, height: Int) {
        // A fixed 12dp text unit: extra height becomes more rows, not larger text. Only displays too
        // narrow for the four-lane pattern grid (about 31 units) or too short for ~16 rows shrink it.
        val fit = minOf(width / 31f, height / 24f)
        unit = maxOf(9f * density, minOf(12f * density, fit))
        text.textSize = unit
        label.textSize = unit
    }

    fun dp(v: Float) = v * density

    fun rect(c: Canvas, r: RectF, color: Int) {
        fill.color = color
        c.drawRect(r, fill)
    }

    fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        c.drawRect(l, t, r, b, fill)
    }

    fun round(c: Canvas, r: RectF, radius: Float, color: Int) {
        fill.color = color
        c.drawRoundRect(r, radius, radius, fill)
    }

    fun outline(c: Canvas, r: RectF, color: Int, width: Float, radius: Float = 0f) {
        stroke.color = color
        stroke.strokeWidth = width
        if (radius > 0f) c.drawRoundRect(r, radius, radius, stroke) else c.drawRect(r, stroke)
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    /** Draws monospaced text with its baseline vertically centred in the row [top, top+h]. */
    fun mono(c: Canvas, s: String, x: Float, top: Float, h: Float, color: Int, bold: Boolean = false, size: Float = unit, align: Paint.Align = Paint.Align.LEFT) {
        text.typeface = if (bold) monoBold else mono
        text.textSize = size
        text.color = color
        text.textAlign = align
        c.drawText(s, x, baseline(text, top, h), text)
    }

    fun label(c: Canvas, s: String, x: Float, top: Float, h: Float, color: Int, bold: Boolean = false, size: Float = unit, align: Paint.Align = Paint.Align.LEFT) {
        label.typeface = if (bold) sansBold else sans
        label.textSize = size
        label.color = color
        label.textAlign = align
        c.drawText(s, x, baseline(label, top, h), label)
    }

    fun monoWidth(s: String, size: Float = unit, bold: Boolean = false): Float {
        text.typeface = if (bold) monoBold else mono
        text.textSize = size
        return text.measureText(s)
    }

    fun labelWidth(s: String, size: Float = unit, bold: Boolean = false): Float {
        label.typeface = if (bold) sansBold else sans
        label.textSize = size
        return label.measureText(s)
    }

    /** Truncates [s] with an ellipsis to fit [max] px in the label face. */
    fun fit(s: String, max: Float, size: Float = unit, bold: Boolean = false): String {
        if (labelWidth(s, size, bold) <= max) return s
        var end = s.length
        while (end > 0 && labelWidth(s.substring(0, end) + "…", size, bold) > max) end--
        return s.substring(0, end) + "…"
    }

    /** Reused for every text draw; [Paint.getFontMetrics] with an argument does not allocate. */
    private val metrics = Paint.FontMetrics()

    private fun baseline(p: Paint, top: Float, h: Float): Float {
        p.getFontMetrics(metrics)
        return top + h / 2f - (metrics.ascent + metrics.descent) / 2f
    }

    /** A key badge followed by its meaning; returns the x after it. */
    fun hint(c: Canvas, keys: String, meaning: String, x: Float, top: Float, h: Float, size: Float): Float {
        val padX = size * 0.4f
        val kw = monoWidth(keys, size * 0.9f, true) + padX * 2
        val r = RectF(x, top + h * 0.16f, x + kw, top + h * 0.84f)
        round(c, r, size * 0.3f, Palette.RULE)
        mono(c, keys, x + padX, top, h, Palette.TEXT, true, size * 0.9f)
        val tx = x + kw + size * 0.35f
        label(c, meaning, tx, top, h, Palette.DIM, size = size)
        return tx + labelWidth(meaning, size) + size * 1.1f
    }

    fun hintWidth(keys: String, meaning: String, size: Float): Float =
        monoWidth(keys, size * 0.9f, true) + size * 0.8f + size * 0.35f + labelWidth(meaning, size) + size * 1.1f
}
