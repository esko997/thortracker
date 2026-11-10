package org.thortracker.ui

/**
 * Immutable semantic colours, shared by panes and platform chrome. Slate preserves the original bench exactly;
 * Paper uses ink on neutral paper, and Contrast separates bright text and signals from a near-black grid.
 * A workspace owns its selection: palettes never change song data or another workspace's appearance.
 */
class Palette private constructor(
    val id: String,
    val name: String,
    val BG: Int,
    val LANE: Int,
    val BEAT: Int,
    val BAR: Int,
    val RULE: Int,
    val TEXT: Int,
    val DIM: Int,
    val FAINT: Int,
    val PLAYHEAD: Int,
    val WRITE: Int,
    val LISTEN: Int,
    val CURSOR_FILL: Int,
    val ERROR: Int,
    val FOCUS: Int,
    /** Historically amber in menus/keyboards; separate from playback in the other palettes. */
    val FOCUS_ACCENT: Int = FOCUS,
    private val channels: IntArray,
    // Piano keys retain their physical black/white convention in every theme.
    val KEY_WHITE: Int = 0xFFD6DCE2.toInt(),
    val KEY_WHITE_TEXT: Int = 0xFF3A4450.toInt(),
    val KEY_BLACK: Int = 0xFF262C35.toInt(),
    val KEY_BLACK_TEXT: Int = 0xFFDCE3EA.toInt(),
) {
    fun channel(i: Int): Int = channels[i.mod(channels.size)]

    companion object {
        val SLATE = Palette(
            "slate", "Slate",
            BG = 0xFF181C22.toInt(), LANE = 0xFF1C2129.toInt(), BEAT = 0xFF212833.toInt(),
            BAR = 0xFF28313D.toInt(), RULE = 0xFF2E3946.toInt(), TEXT = 0xFFDCE3EA.toInt(),
            DIM = 0xFF7D8A99.toInt(), FAINT = 0xFF46525F.toInt(), PLAYHEAD = 0xFFEAA959.toInt(),
            WRITE = 0xFFF0607A.toInt(), LISTEN = 0xFF5FD0F0.toInt(), CURSOR_FILL = 0xFF34404F.toInt(),
            ERROR = 0xFFFF7A6B.toInt(), FOCUS = 0xFFDCE3EA.toInt(), FOCUS_ACCENT = 0xFFEAA959.toInt(),
            channels = intArrayOf(
                0xFF7FD3C4.toInt(), 0xFF8FB8F0.toInt(), 0xFFC9A0F0.toInt(), 0xFFF09A8F.toInt(),
                0xFFB8D46A.toInt(), 0xFFE8C46A.toInt(), 0xFF6AC4E8.toInt(), 0xFFE87AC4.toInt(),
                0xFFD8B48C.toInt(), 0xFFC4CCD4.toInt(), 0xFF9FD89A.toInt(), 0xFFF0B07A.toInt(),
                0xFFA6A0E8.toInt(), 0xFF7AD8D8.toInt(), 0xFFE8A0B8.toInt(),
            ),
        )
        val PAPER = Palette(
            "paper", "Paper",
            BG = 0xFFF5F6F7.toInt(), LANE = 0xFFEDF0F2.toInt(), BEAT = 0xFFE3E8ED.toInt(),
            BAR = 0xFFD6DFE7.toInt(), RULE = 0xFFADB9C5.toInt(), TEXT = 0xFF172531.toInt(),
            DIM = 0xFF435567.toInt(), FAINT = 0xFF526171.toInt(), PLAYHEAD = 0xFF853E00.toInt(),
            WRITE = 0xFF9D155C.toInt(), LISTEN = 0xFF006475.toInt(), CURSOR_FILL = 0xFFC9DAED.toInt(),
            ERROR = 0xFFAB271B.toInt(), FOCUS = 0xFF164F91.toInt(),
            channels = intArrayOf(
                0xFF00675D.toInt(), 0xFF22579A.toInt(), 0xFF714498.toInt(), 0xFF973E32.toInt(),
                0xFF4B650A.toInt(), 0xFF775300.toInt(), 0xFF006279.toInt(), 0xFF94356F.toInt(),
                0xFF78502F.toInt(), 0xFF435567.toInt(), 0xFF316738.toInt(), 0xFF8B460F.toInt(),
                0xFF514F9E.toInt(), 0xFF00686B.toInt(), 0xFF8E3D59.toInt(),
            ),
        )
        val CONTRAST = Palette(
            "contrast", "Contrast",
            BG = 0xFF080A0D.toInt(), LANE = 0xFF11161C.toInt(), BEAT = 0xFF1C2630.toInt(),
            BAR = 0xFF283744.toInt(), RULE = 0xFF637487.toInt(), TEXT = 0xFFF7FAFF.toInt(),
            DIM = 0xFFBDCCDB.toInt(), FAINT = 0xFFA8B9CB.toInt(), PLAYHEAD = 0xFFFFCB66.toInt(),
            WRITE = 0xFFFF8CBD.toInt(), LISTEN = 0xFF6CE5FF.toInt(), CURSOR_FILL = 0xFF34485E.toInt(),
            ERROR = 0xFFFF9D8C.toInt(), FOCUS = 0xFF9BC7FF.toInt(),
            channels = intArrayOf(
                0xFF8DF0DA.toInt(), 0xFFADD0FF.toInt(), 0xFFDDB9FF.toInt(), 0xFFFFB2A6.toInt(),
                0xFFD2EC8B.toInt(), 0xFFFFDD8B.toInt(), 0xFF8DDDFF.toInt(), 0xFFFFA8E2.toInt(),
                0xFFEFCDA7.toInt(), 0xFFE0E7EF.toInt(), 0xFFB6EDB1.toInt(), 0xFFFFCB9A.toInt(),
                0xFFC8C2FF.toInt(), 0xFFA0F0EF.toInt(), 0xFFFFBDD3.toInt(),
            ),
        )
        val all: List<Palette> = listOf(SLATE, PAPER, CONTRAST)

        /** Stable workspace preference IDs, independent of display names; missing/unknown values use Slate. */
        fun fromId(id: String?): Palette = when (id) {
            PAPER.id -> PAPER
            CONTRAST.id -> CONTRAST
            else -> SLATE
        }

        fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - u) + ((b shr s) and 0xFF) * u).toInt() shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/**
 * Size metrics and drawing helpers shared by every view, over a platform [Surface]. [unit] is the base text size in
 * pixels, derived from the drawing area and the display density. Text is measured with [measure], the frontend's
 * surface (measuring needs no drawing target).
 */
class Ink(val density: Float, private val measure: Surface) {
    /** Scoped to this drawing context; hosts apply their workspace choice before repainting. */
    var palette: Palette = Palette.SLATE
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
        round(c, badge, size * 0.3f, palette.RULE)
        mono(c, keys, x + padX, top, h, palette.TEXT, true, size * 0.9f)
        val tx = x + kw + size * 0.35f
        label(c, meaning, tx, top, h, palette.DIM, size = size)
        return tx + labelWidth(meaning, size) + size * 1.1f
    }

    fun hintWidth(keys: String, meaning: String, size: Float): Float =
        monoWidth(keys, size * 0.9f, true) + size * 0.8f + size * 0.35f + labelWidth(meaning, size) + size * 1.1f
}
