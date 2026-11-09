package org.thortracker.system.pce

import kotlin.math.roundToInt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * PC Engine wave editor: the song's waves as a scrolled strip of thumbnails, a tool row, and a 32 × 32 drawing grid.
 * The controller pen is a crosshair (step, level); holding A draws at the pen height. Touch draws by dragging, with
 * interpolation so fast strokes leave no gaps. Each stroke or hold is one undo step.
 */
internal class PceWavePainter(v: PaneView) : Painter(v) {
    private val thumbs = ArrayList<Pair<Rect, Int>>()
    private val tools = ArrayList<Pair<Rect, Int>>()
    private var grid = Rect()
    private var thumbScroll = 0
    private var lastSample = -1
    private var lastLevel = -1
    private var drawing = false

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val waves = s.pce.waves
        val wave = waves.getOrNull(st.wave)
        val locked = if (st.lockedSubject >= 0) "locked  " else ""
        val users = PceWaveView.users(s, st.wave)
        val detail = when {
            wave == null -> "The song has no waves yet"
            users.isEmpty() -> "No sound plays this wave"
            else -> "Played by sound ${users.joinToString(", ") { hex2(it) }}"
        }
        val top = header(c, area, if (wave == null) "Waves" else "Wave ${hex2(st.wave)}", locked + detail + if (wave == null) "" else " · ${waves.size} waves")
        val col = Palette.channel(session.selection.channel.coerceIn(0, s.system.channels.size - 1))
        val pad = ink.unit * 0.7f

        // thumbnails, scrolled to keep the shown wave in view
        thumbs.clear()
        val thumbH = ink.unit * 2.6f
        val tw = ink.unit * 2.8f
        val strip = area.width() - pad * 2
        val fit = maxOf(1, (strip / tw).toInt())
        thumbScroll = thumbScroll.coerceIn(maxOf(0, st.wave - fit + 1), st.wave.coerceAtLeast(0))
        thumbScroll = thumbScroll.coerceIn(0, maxOf(0, waves.size - fit))
        for (i in thumbScroll until minOf(waves.size, thumbScroll + fit)) {
            val w = waves[i]
            val x = area.left + pad + (i - thumbScroll) * tw
            val r = Rect(x + 2, top + ink.unit * 0.4f, x + tw - 2, top + ink.unit * 0.4f + thumbH)
            val sel = i == st.wave
            ink.round(c, r, ink.dp(3f), if (sel) Palette.CURSOR_FILL else Palette.LANE)
            val bw = r.width() / w.size
            for ((j, sample) in w.withIndex()) {
                val h = (r.height() - ink.unit * 0.9f) * sample / PceSound.WAVE_MAX.toFloat()
                ink.rect(c, r.left + j * bw, r.bottom - 2 - h, r.left + (j + 1) * bw, r.bottom - 2, if (sel) col else Palette.FAINT)
            }
            ink.mono(c, hex2(i), r.left + ink.dp(3f), r.top, ink.unit * 0.9f, if (sel) Palette.TEXT else Palette.DIM, size = ink.unit * 0.7f)
            if (sel) ink.outline(c, r, col, ink.dp(2f), ink.dp(3f))
            thumbs += r to i
        }
        if (thumbScroll > 0) ink.label(c, "‹", area.left + ink.dp(2f), top + ink.unit * 0.4f, thumbH, Palette.DIM)
        if (thumbScroll + fit < waves.size) ink.label(c, "›", area.right - ink.dp(2f), top + ink.unit * 0.4f, thumbH, Palette.DIM, align = Align.RIGHT)

        // tool row
        tools.clear()
        val toolTop = top + thumbH + ink.unit * 0.9f
        val toolH = ink.unit * 1.7f
        var x = area.left + pad
        val size = ink.unit * 0.85f
        for ((i, tool) in PceWaveView.TOOLS.withIndex()) {
            val text = when (tool) {
                PceWaveView.Tool.SHAPE -> wave?.let { w -> PceWaveShapes.presetOf(w).takeIf { it >= 0 }?.let { "Shape: ${PcePresets.WAVES[it].first}" } } ?: "Shape"
                PceWaveView.Tool.USE -> "Use in sound ${hex2(PceWaveView.targetSound(ctl))}"
                else -> tool.label
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
        val hexH = ink.unit * 1.3f
        val gTop = toolTop + toolH + ink.unit * 0.8f
        val gBottom = area.bottom - hexH - ink.unit * 0.4f
        val labelW = ink.unit * 1.6f
        grid = Rect(area.left + pad + labelW, gTop, area.right - pad, gBottom)
        if (wave == null) {
            ink.label(c, ink.fit("New wave adds one: tone sounds play the song's waves", area.width() - pad * 2), area.left + pad, gTop, ink.unit * 1.5f, Palette.FAINT)
            grid = Rect()
            return
        }
        if (grid.height() < ink.unit * 3) { grid = Rect(); return }
        val levels = PceSound.WAVE_MAX + 1
        val cw = grid.width() / PceSound.WAVE_SIZE
        val ch = grid.height() / levels
        ink.rect(c, grid, Palette.LANE)
        for (lv in 0..PceSound.WAVE_MAX) {
            val y = grid.bottom - (lv + 1) * ch
            if (lv % 8 == 0) ink.line(c, grid.left, y + ch, grid.right, y + ch, Palette.RULE, 1f)
            if (lv % 8 == 7 || lv == 0) ink.mono(c, hex2(lv), area.left + pad, y, ch, Palette.FAINT, size = minOf(ink.unit * 0.75f, ch * 1.8f))
        }
        for (i in 0..PceSound.WAVE_SIZE) {
            val gx = grid.left + i * cw
            ink.line(c, gx, grid.top, gx, grid.bottom, if (i % 8 == 0) Palette.RULE else Palette.alpha(Palette.RULE, 90), 1f)
        }
        val mid = grid.bottom - grid.height() * 16f / levels
        ink.line(c, grid.left, mid, grid.right, mid, Palette.alpha(Palette.DIM, 70), ink.dp(1f))
        val valueSize = minOf(ink.unit * 0.8f, cw * 0.6f)
        for ((i, sample) in wave.withIndex()) {
            val bx = grid.left + i * cw
            val topY = grid.bottom - (sample + 1) * ch
            ink.rect(c, bx + 1, topY + ch, bx + cw - 1, grid.bottom, Palette.alpha(col, 70))
            ink.rect(c, bx + 1, topY, bx + cw - 1, topY + ch, col)
            ink.mono(c, hex2(sample), bx + cw / 2, grid.bottom + ink.unit * 0.2f, hexH,
                if (st.waveRow == 1 && i == st.waveCol) Palette.TEXT else Palette.FAINT, size = valueSize, align = Align.CENTER)
        }
        // pen crosshair
        if (st.waveRow == 1) {
            val px = grid.left + st.waveCol * cw
            val py = grid.bottom - (st.waveLevel + 1) * ch
            val pc = cursorColor()
            ink.line(c, grid.left, py + ch / 2, grid.right, py + ch / 2, Palette.alpha(pc, 70), ink.dp(1f))
            ink.line(c, px + cw / 2, grid.top, px + cw / 2, grid.bottom, Palette.alpha(pc, 70), ink.dp(1f))
            ink.outline(c, Rect(px, py, px + cw, py + ch), pc, ink.dp(2f))
        }
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
            PceWaveView.publish(ctl)
            return true
        }
        tools.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            st.waveRow = 0
            st.waveCol = i
            PceWaveView.applyTool(ctl, PceWaveView.TOOLS[i])
            return true
        }
        if (grid.contains(x, y)) {
            drawing = true
            strokeWave = st.wave
            strokeGesture = session.beginGesture("Draw wave")
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

    private var strokeGesture: GestureToken? = null

    /** The wave being drawn, pinned at touch-down so nothing can redirect the stroke. */
    private var strokeWave = 0

    private fun finish() {
        if (!drawing) return
        drawing = false
        strokeGesture?.let { session.endGesture(it) }
        strokeGesture = null
    }

    private fun paint(x: Float, y: Float) {
        val current = song.pce.waves.getOrNull(strokeWave) ?: return
        val cw = grid.width() / PceSound.WAVE_SIZE
        val ch = grid.height() / (PceSound.WAVE_MAX + 1)
        val sample = ((x - grid.left) / cw).toInt().coerceIn(0, PceSound.WAVE_SIZE - 1)
        val level = ((grid.bottom - y) / ch).toInt().coerceIn(0, PceSound.WAVE_MAX)
        val w = current.toMutableList()
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
        if (st.wave == strokeWave) {
            st.waveRow = 1
            st.waveCol = sample
            st.waveLevel = level
        }
        session.setPceWave(strokeWave, w, "Draw wave")
    }
}
