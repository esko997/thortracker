package org.thortracker.system.vsu

import kotlin.math.roundToInt
import org.thortracker.backend.VsuPlayerImage
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * Virtual Boy wave editor: six page thumbnails (waves 1-5 and the modulation table), who uses the page (sounds and lanes),
 * a tool row and a 32-column drawing grid (waves 0..63 as bars from the floor, the table −128..127 as bars from the
 * centre line). The controller pen is a crosshair (sample, level); holding A draws at the pen height. Touch draws by
 * dragging, interpolated so fast strokes leave no gaps; each stroke or hold is one undo step.
 */
internal class VsuWavePainter(v: PaneView) : Painter(v) {
    private val thumbs = ArrayList<Pair<Rect, Int>>()
    private val tools = ArrayList<Pair<Rect, Int>>()
    private var grid = Rect()
    private var lastSample = -1
    private var lastLevel = -1
    private var drawing = false
    private var strokeGesture: GestureToken? = null

    /** The page being drawn, pinned at touch-down so nothing can redirect the stroke. */
    private var strokePage = 0

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val sound = s.vsu
        val mod = VsuWaveView.isModulation(st)
        val values = VsuWaveView.values(s, st)
        val locked = if (st.lockedSubject >= 0) "locked  " else ""
        val title = if (mod) "Modulation table" else "Wave ${st.wave + 1}" + (VsuWaves.name(sound, st.wave)?.let { " · $it" } ?: "")
        val top = header(c, area, title, locked + usersText())
        val col = Palette.channel(if (mod) VsuRules.SWEEP_LANE else VsuWaves.lanesPlaying(s, st.wave).firstOrNull() ?: 0)
        val pad = ink.unit * 0.7f

        // page thumbnails: the five waves, then the table
        thumbs.clear()
        val thumbH = ink.unit * 2.6f
        val tw = (area.width() - pad * 2) / VsuWaves.PAGES
        for (i in 0 until VsuWaves.PAGES) {
            val r = Rect(area.left + pad + i * tw + 2, top + ink.unit * 0.4f, area.left + pad + (i + 1) * tw - 2, top + ink.unit * 0.4f + thumbH)
            val sel = i == st.wave
            ink.round(c, r, ink.dp(3f), if (sel) Palette.CURSOR_FILL else Palette.LANE)
            val w = if (i == VsuWaves.MOD_PAGE) sound.modulation else sound.waves[i]
            val bw = r.width() / w.size
            val inner = r.height() - ink.unit * 0.9f
            for ((j, sample) in w.withIndex()) {
                if (i == VsuWaves.MOD_PAGE) {
                    val mid = r.bottom - 2 - inner / 2
                    val h = inner / 2 * sample / 128f
                    ink.rect(c, r.left + j * bw, minOf(mid, mid - h), r.left + (j + 1) * bw, maxOf(mid, mid - h) + 1, if (sel) col else Palette.FAINT)
                } else {
                    val h = inner * sample / VsuPlayerImage.SAMPLE_MAX
                    ink.rect(c, r.left + j * bw, r.bottom - 2 - h, r.left + (j + 1) * bw, r.bottom - 2, if (sel) col else Palette.FAINT)
                }
            }
            ink.mono(c, if (i == VsuWaves.MOD_PAGE) "M" else "${i + 1}", r.left + ink.dp(3f), r.top, ink.unit * 0.9f,
                if (sel) Palette.TEXT else Palette.DIM, size = ink.unit * 0.7f)
            if (sel) ink.outline(c, r, col, ink.dp(2f), ink.dp(3f))
            thumbs += r to i
        }

        // tool row
        tools.clear()
        val toolTop = top + thumbH + ink.unit * 0.9f
        val toolH = ink.unit * 1.7f
        var x = area.left + pad
        val size = ink.unit * 0.85f
        for ((i, tool) in VsuWaveView.TOOLS.withIndex()) {
            val text = when (tool) {
                VsuWaveView.Tool.USE -> "Use in sound ${hex2(VsuWaveView.targetSound(ctl))}"
                VsuWaveView.Tool.PRESET -> "Preset: " + ((if (mod) VsuWaves.modulationName(sound) else VsuWaves.name(sound, st.wave)) ?: "drawn")
                else -> if (mod) tool.modLabel else tool.label
            }
            val w = ink.labelWidth(text, size) + ink.unit
            if (x + w > area.right - pad) break
            val r = Rect(x, toolTop, x + w, toolTop + toolH)
            val sel = st.waveRow == 0 && st.waveCol == i
            ink.round(c, r, ink.dp(4f), if (sel) Palette.CURSOR_FILL else Palette.RULE)
            if (sel) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
            ink.label(c, text, r.centerX(), r.top, r.height(), if (sel) Palette.TEXT else Palette.DIM, sel, size, Align.CENTER)
            tools += r to i
            x += w + ink.unit * 0.4f
        }

        // grid
        val valueH = ink.unit * 1.3f
        val gTop = toolTop + toolH + ink.unit * 0.8f
        val gBottom = area.bottom - valueH - ink.unit * 0.4f
        val labelW = ink.unit * 2.2f
        grid = Rect(area.left + pad + labelW, gTop, area.right - pad, gBottom)
        if (grid.height() < ink.unit * 3) return
        val range = VsuWaveView.levels(st)
        val levels = range.last - range.first + 1
        val cw = grid.width() / VsuPlayerImage.WAVE_SAMPLES
        ink.rect(c, grid, Palette.LANE)
        fun yOf(level: Int) = grid.bottom - grid.height() * (level - range.first) / levels.toFloat()
        val marks = if (mod) listOf(-128, -64, 0, 64, 127) else listOf(0, 16, 32, 48, 63)
        for (m in marks) {
            val y = yOf(m)
            ink.line(c, grid.left, y, grid.right, y, Palette.RULE, 1f)
            ink.mono(c, m.toString(), area.left + pad, y - ink.unit * 0.45f, ink.unit * 0.9f, Palette.FAINT, size = ink.unit * 0.7f)
        }
        for (i in 0..VsuPlayerImage.WAVE_SAMPLES) {
            val gx = grid.left + i * cw
            ink.line(c, gx, grid.top, gx, grid.bottom, if (i % 8 == 0) Palette.RULE else Palette.alpha(Palette.RULE, 90), 1f)
        }
        val base = if (mod) yOf(0) else grid.bottom
        val ch = grid.height() / levels
        for ((i, value) in values.withIndex()) {
            val bx = grid.left + i * cw
            val vy = yOf(value) - ch
            ink.rect(c, bx + 1, minOf(base, vy + ch), bx + cw - 1, maxOf(base, vy + ch), Palette.alpha(col, 70))
            ink.rect(c, bx + 1, vy, bx + cw - 1, vy + maxOf(ch, ink.dp(2f)), col)
            if (cw > ink.unit * 0.9f) {
                ink.mono(c, value.toString(), bx + cw / 2, grid.bottom + ink.unit * 0.2f, valueH,
                    if (st.waveRow == 1 && i == st.waveCol) Palette.TEXT else Palette.FAINT, size = minOf(ink.unit * 0.7f, cw * 0.45f), align = Align.CENTER)
            }
        }
        // pen crosshair
        if (st.waveRow == 1) {
            val px = grid.left + st.waveCol * cw
            val py = yOf(st.waveLevel) - ch
            val pc = cursorColor()
            ink.line(c, grid.left, py + ch / 2, grid.right, py + ch / 2, Palette.alpha(pc, 70), ink.dp(1f))
            ink.line(c, px + cw / 2, grid.top, px + cw / 2, grid.bottom, Palette.alpha(pc, 70), ink.dp(1f))
            ink.outline(c, Rect(px, py - ink.dp(2f), px + cw, py + ch + ink.dp(2f)), pc, ink.dp(2f))
            if (cw <= ink.unit * 0.9f) {
                ink.mono(c, "${st.waveCol}: ${values[st.waveCol]} → ${st.waveLevel}", grid.right, grid.bottom + ink.unit * 0.2f, valueH,
                    Palette.TEXT, size = ink.unit * 0.75f, align = Align.RIGHT)
            }
        }
    }

    /** "Played by sounds 01 Square lead, 03 · on Wave 1, Wave 3" / "Read by Wave 5 sounds 08". */
    private fun usersText(): String {
        val s = song
        if (VsuWaveView.isModulation(st)) {
            val users = VsuWaves.soundsModulating(s.vsu)
            return if (users.isEmpty()) "No sound modulates Wave 5 with it" else "Read by Wave 5 modulation of sound ${users.joinToString(", ") { hex2(it) }}"
        }
        val users = VsuWaves.soundsUsing(s.vsu, st.wave)
        val lanes = VsuWaves.lanesPlaying(s, st.wave)
        val who = if (users.isEmpty()) "No sound plays this wave" else "Sounds ${users.joinToString(", ") { hex2(it) }}"
        return who + if (lanes.isEmpty()) "" else " · heard on ${lanes.joinToString(", ") { VsuRules.CHANNEL_NAMES[it] }}"
    }

    private fun cursorColor() = when {
        ctl.editHeld || drawing -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    // ---- touch ---------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        thumbs.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            st.wave = i
            st.waveLevel = st.waveLevel.coerceIn(VsuWaveView.levels(st))
            VsuWaveView.publish(ctl)
            return true
        }
        tools.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            st.waveRow = 0
            st.waveCol = i
            VsuWaveView.applyTool(ctl, VsuWaveView.TOOLS[i])
            return true
        }
        if (grid.contains(x, y)) {
            drawing = true
            strokePage = st.wave
            strokeGesture = session.beginGesture(if (strokePage == VsuWaves.MOD_PAGE) "Draw modulation" else "Draw wave")
            lastSample = -1
            paint(x, y)
            return true
        }
        return false
    }

    override fun move(x: Float, y: Float) {
        if (!drawing) return
        // Undo or redo closed the stroke's group: end the stroke instead of writing outside any group.
        val g = strokeGesture
        if (g == null || !session.isGestureOpen(g)) { drawing = false; strokeGesture = null; return }
        paint(x, y)
    }

    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        if (!drawing) return
        drawing = false
        strokeGesture?.let { session.endGesture(it) }
        strokeGesture = null
    }

    private fun paint(x: Float, y: Float) {
        val mod = strokePage == VsuWaves.MOD_PAGE
        val range = if (mod) -128..127 else 0..VsuPlayerImage.SAMPLE_MAX
        val levels = range.last - range.first + 1
        val cw = grid.width() / VsuPlayerImage.WAVE_SAMPLES
        val sample = ((x - grid.left) / cw).toInt().coerceIn(0, VsuPlayerImage.WAVE_SAMPLES - 1)
        val level = (range.first + ((grid.bottom - y) / grid.height() * levels).toInt()).coerceIn(range)
        val w = (if (mod) song.vsu.modulation else song.vsu.waves[strokePage]).toMutableList()
        if (lastSample < 0 || lastSample == sample) {
            w[sample] = level
        } else {
            val step = if (sample > lastSample) 1 else -1
            var i = lastSample
            while (true) {
                val t = (i - lastSample).toFloat() / (sample - lastSample)
                w[i] = (lastLevel + (level - lastLevel) * t).roundToInt()
                if (i == sample) break
                i += step
            }
        }
        lastSample = sample
        lastLevel = level
        if (st.wave == strokePage) {
            st.waveRow = 1
            st.waveCol = sample
            st.waveLevel = level
        }
        if (mod) session.setVsuModulation(w, "Draw modulation") else session.setVsuWave(strokePage, w, "Draw wave")
    }
}
