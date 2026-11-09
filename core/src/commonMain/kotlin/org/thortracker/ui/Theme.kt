package org.thortracker.ui

/**
 * Visual tokens. A dark slate bench (matching the shell base) with one hue per channel lane, an amber
 * playhead, rose for writing and cyan for listening. Grid text is monospaced; labels condensed sans.
 */
object Palette {
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
        0xFFD8B48C.toInt(), // ninth lane (OPL3 channel 9): sand
        0xFFC4CCD4.toInt(), // tenth lane (Mega Drive Noise): silver
        0xFF9FD89A.toInt(), // lanes 11-15 (Master System FM unit: FM, rhythm drums): sage
        0xFFF0B07A.toInt(), // apricot
        0xFFA6A0E8.toInt(), // lavender
        0xFF7AD8D8.toInt(), // aqua
        0xFFE8A0B8.toInt(), // rose
    )

    fun channel(i: Int): Int = CHANNELS[i.mod(CHANNELS.size)]

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).toInt() shl s
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}

/**
 * Size metrics and drawing helpers shared by every view, over a platform [Surface]. [unit] is the base text size in
 * pixels, derived from the drawing area and the display density. Text is measured with [measure], the frontend's
 * surface (measuring needs no drawing target).
 */
class Ink(val density: Float, private val measure: Surface) {
    /** Base text size in px; set per layout. */
    var unit = 14f * density
        private set

    fun layout(width: Int, height: Int) {
        // A fixed 12dp text unit: extra height becomes more rows, not larger text. Only displays too
        // narrow for the four-lane pattern grid (about 31 units) or too short for ~16 rows shrink it.
        val fit = minOf(width / 31f, height / 24f)
        unit = maxOf(9f * density, minOf(12f * density, fit))
    }

    fun dp(v: Float) = v * density

    fun rect(c: Surface, r: Rect, color: Int) = c.fillRect(r.left, r.top, r.right, r.bottom, color)

    fun rect(c: Surface, l: Float, t: Float, r: Float, b: Float, color: Int) = c.fillRect(l, t, r, b, color)

    fun round(c: Surface, r: Rect, radius: Float, color: Int) = c.fillRound(r.left, r.top, r.right, r.bottom, radius, color)

    fun outline(c: Surface, r: Rect, color: Int, width: Float, radius: Float = 0f) =
        c.strokeRect(r.left, r.top, r.right, r.bottom, color, width, radius)

    fun line(c: Surface, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = c.line(x0, y0, x1, y1, color, width)

    /** Draws monospaced text vertically centred in the row [top, top+h]. */
    fun mono(c: Surface, s: String, x: Float, top: Float, h: Float, color: Int, bold: Boolean = false, size: Float = unit, align: Align = Align.LEFT) =
        c.text(s, x, top, h, color, Face.MONO, bold, size, align)

    fun label(c: Surface, s: String, x: Float, top: Float, h: Float, color: Int, bold: Boolean = false, size: Float = unit, align: Align = Align.LEFT) =
        c.text(s, x, top, h, color, Face.SANS, bold, size, align)

    fun monoWidth(s: String, size: Float = unit, bold: Boolean = false): Float = measure.textWidth(s, Face.MONO, bold, size)

    fun labelWidth(s: String, size: Float = unit, bold: Boolean = false): Float = measure.textWidth(s, Face.SANS, bold, size)

    /** Truncates [s] with an ellipsis to fit [max] px in the label face. */
    fun fit(s: String, max: Float, size: Float = unit, bold: Boolean = false): String {
        if (labelWidth(s, size, bold) <= max) return s
        var end = s.length
        while (end > 0 && labelWidth(s.substring(0, end) + "…", size, bold) > max) end--
        return s.substring(0, end) + "…"
    }

    private val badge = Rect()

    /** A key badge followed by its meaning; returns the x after it. */
    fun hint(c: Surface, keys: String, meaning: String, x: Float, top: Float, h: Float, size: Float): Float {
        val padX = size * 0.4f
        val kw = monoWidth(keys, size * 0.9f, true) + padX * 2
        badge.set(x, top + h * 0.16f, x + kw, top + h * 0.84f)
        round(c, badge, size * 0.3f, Palette.RULE)
        mono(c, keys, x + padX, top, h, Palette.TEXT, true, size * 0.9f)
        val tx = x + kw + size * 0.35f
        label(c, meaning, tx, top, h, Palette.DIM, size = size)
        return tx + labelWidth(meaning, size) + size * 1.1f
    }

    fun hintWidth(keys: String, meaning: String, size: Float): Float =
        monoWidth(keys, size * 0.9f, true) + size * 0.8f + size * 0.35f + labelWidth(meaning, size) + size * 1.1f
}
