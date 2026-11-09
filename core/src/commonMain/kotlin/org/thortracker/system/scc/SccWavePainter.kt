package org.thortracker.system.scc

import kotlin.math.roundToInt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.ui.Align
import org.thortracker.ui.Ink
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * A wave's 32 signed samples as bars from the middle line of [r] (the SCC's level 0), for thumbnails in the Wave and
 * Instrument views.
 */
internal fun drawSccWaveThumb(ink: Ink, c: Surface, r: Rect, samples: List<Int>, color: Int) {
    if (r.width() <= 2f || r.height() <= 2f || samples.isEmpty()) return
    val mid = r.top + r.height() / 2
    val half = r.height() / 2 - 1f
    val bw = r.width() / samples.size
    for ((j, s) in samples.withIndex()) {
        val y = mid - half * s / 128f
        val top = minOf(mid, y)
        val bottom = maxOf(mid, y)
        ink.rect(c, r.left + j * bw, top, r.left + (j + 1) * bw, maxOf(bottom, top + 1f), color)
    }
}

/**
 * The SCC Wave view: the wave's name, a scrolling strip of the 64 waves as thumbnails, the tools (wrapped over as many
 * lines as they need), and a 32-sample drawing grid over the levels -128..127. The controller pen is a crosshair
 * (sample, level); holding Edit draws at the pen height. Touch draws by dragging, interpolating so fast strokes leave
 * no gaps; each stroke or hold is one undo step. On the SCC a wave SCC 4 or SCC 5 plays carries the shared-RAM note.
 */
internal class SccWavePainter(v: PaneView) : Painter(v) {
    private val thumbs = ArrayList<Pair<Rect, Int>>()
    private val tools = ArrayList<Pair<Rect, SccWaveView.Tool>>()
    private var nameRect = Rect()
    private var grid = Rect()
    private var thumbScroll = 0
    private var lastSample = -1
    private var lastLevel = 0
    private var drawing = false
    private var strokeGesture: GestureToken? = null

    /** The wave being drawn, pinned at touch-down so nothing can redirect the stroke. */
    private var strokeWave = 1

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val number = SccWaveView.number(st)
        val wave = SccWaveView.wave(s, st)
        val users = SccWaveView.users(s, number)
        val locked = if (st.lockedSubject >= 0) "locked  " else ""
        val chip = s.scc.variant
        val top = header(c, area, "Wave ${hex2(number)}" + if (wave.name.isEmpty()) "" else " ${wave.name}",
            "${chip.label} · $locked" + if (users.isEmpty()) "no sound plays this wave" else "played by sound ${users.joinToString(", ") { hex2(it) }}")
        val col = Palette.channel(st.listenChannel.coerceIn(0, s.system.channels.size - 1))
        val pad = ink.unit * 0.7f
        val small = ink.unit * 0.8f
        var y = top + ink.unit * 0.3f

        // name, and the shared-RAM note on the SCC
        val lineH = ink.unit * 1.5f
        nameRect = Rect(area.left + pad, y, area.right - pad, y + lineH)
        val onName = st.waveRow == SccWaveView.NAME_ROW
        if (onName) ink.rect(c, nameRect, Palette.CURSOR_FILL)
        val vx = nameRect.left + ink.unit * 4f
        ink.label(c, "Name", nameRect.left + ink.unit * 0.3f, y, lineH, if (onName) Palette.TEXT else Palette.DIM)
        ink.label(c, wave.name.ifEmpty { "(unnamed)" }, vx, y, lineH, if (wave.name.isEmpty()) Palette.FAINT else Palette.TEXT, true)
        if (onName) {
            val before = wave.name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, ink.unit, true)
            val w = maxOf(ink.labelWidth(wave.name.getOrNull(st.textPos)?.toString() ?: " ", ink.unit, true), ink.unit * 0.5f)
            ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + lineH - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
            ink.outline(c, nameRect, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        y += lineH
        SccWaveView.sharedNote(s, number)?.let { note ->
            ink.label(c, ink.fit(note, area.width() - pad * 2, small), area.left + pad, y, ink.unit * 1.1f, Palette.PLAYHEAD, size = small)
            y += ink.unit * 1.1f
        }

        // thumbnails: as many as fit at a readable width, scrolled to keep the selected wave in view
        thumbs.clear()
        val thumbH = ink.unit * 2.4f
        val stripW = area.width() - pad * 2
        val fit = maxOf(1, minOf(SccSound.WAVES, (stripW / (ink.unit * 2.4f)).toInt()))
        val tw = stripW / fit
        thumbScroll = thumbScroll.coerceIn(maxOf(0, st.wave - fit + 1), st.wave).coerceAtMost(SccSound.WAVES - fit)
        val thumbTop = y + ink.unit * 0.3f
        for (k in 0 until fit) {
            val i = thumbScroll + k
            val w = s.scc.waves.getOrNull(i) ?: continue
            val r = Rect(area.left + pad + k * tw + 2, thumbTop, area.left + pad + (k + 1) * tw - 2, thumbTop + thumbH)
            val sel = i == st.wave
            ink.round(c, r, ink.dp(3f), if (sel) Palette.CURSOR_FILL else Palette.LANE)
            drawSccWaveThumb(ink, c, Rect(r.left + 2, r.top + ink.unit * 0.8f, r.right - 2, r.bottom - 2), w.samples, if (sel) col else Palette.FAINT)
            ink.mono(c, hex2(i + 1), r.left + ink.dp(3f), r.top, ink.unit * 0.8f, if (sel) Palette.TEXT else Palette.DIM, size = ink.unit * 0.65f)
            if (sel) ink.outline(c, r, col, ink.dp(2f), ink.dp(3f))
            thumbs += r to i
        }
        y = thumbTop + thumbH + ink.unit * 0.6f

        // tools, wrapped
        tools.clear()
        val toolH = ink.unit * 1.6f
        var x = area.left + pad
        val size = ink.unit * 0.85f
        for (t in SccWaveView.Tool.entries) {
            val text = when (t) {
                SccWaveView.Tool.USE -> "Use in sound ${hex2(SccWaveView.targetSound(ctl))}"
                SccWaveView.Tool.PULSE -> SccWaveView.pulseWidth(wave.samples)?.let { "Pulse $it/32" } ?: t.label
                else -> t.label
            }
            val w = ink.labelWidth(text, size) + ink.unit
            if (x + w > area.right - pad && x > area.left + pad) {
                x = area.left + pad
                y += toolH + ink.unit * 0.3f
            }
            val r = Rect(x, y, x + w, y + toolH)
            val sel = st.waveRow == SccWaveView.TOOLS_ROW && st.waveCol == t.ordinal
            ink.round(c, r, ink.dp(4f), if (sel) Palette.CURSOR_FILL else Palette.RULE)
            if (sel) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
            ink.label(c, text, r.centerX(), r.top, r.height(), if (sel) Palette.TEXT else Palette.DIM, sel, size, Align.CENTER)
            tools += r to t
            x += w + ink.unit * 0.4f
        }
        y += toolH + ink.unit * 0.6f

        // grid: bars from level 0, rules every 64 levels, the pen as a crosshair
        val statusH = ink.unit * 1.2f
        val labelW = ink.unit * 2.4f
        grid = Rect(area.left + pad + labelW, y, area.right - pad, area.bottom - statusH - ink.unit * 0.3f)
        if (grid.height() < ink.unit * 3) return
        ink.rect(c, grid, Palette.LANE)
        val cw = grid.width() / SccWave.SAMPLES
        fun yOf(level: Int) = grid.bottom - grid.height() * (level - SccWaveView.LOW + 0.5f) / 256f
        val mid = yOf(0)
        for (level in listOf(-128, -64, 0, 64, 127)) {
            val ly = yOf(level)
            ink.line(c, grid.left, ly, grid.right, ly, if (level == 0) Palette.alpha(Palette.DIM, 90) else Palette.RULE, ink.dp(1f))
            ink.mono(c, if (level > 0) "+$level" else level.toString(), grid.left - ink.unit * 0.3f, ly - ink.unit * 0.5f, ink.unit,
                Palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        }
        for (i in 0..SccWave.SAMPLES) {
            val gx = grid.left + i * cw
            ink.line(c, gx, grid.top, gx, grid.bottom, if (i % 8 == 0) Palette.RULE else Palette.alpha(Palette.RULE, 90), 1f)
        }
        val onGrid = st.waveRow == SccWaveView.GRID_ROW
        for ((i, sample) in wave.samples.withIndex()) {
            val bx = grid.left + i * cw
            val sy = yOf(sample)
            ink.rect(c, bx + 1, minOf(mid, sy), bx + cw - 1, maxOf(mid, sy), Palette.alpha(col, 80))
            ink.rect(c, bx + 1, sy - ink.dp(1.5f), bx + cw - 1, sy + ink.dp(1.5f), if (onGrid && i == st.waveCol) Palette.TEXT else col)
        }
        if (onGrid) {
            val px = grid.left + st.waveCol * cw
            val py = yOf(st.waveLevel)
            val pc = cursorColor()
            ink.line(c, grid.left, py, grid.right, py, Palette.alpha(pc, 70), ink.dp(1f))
            ink.line(c, px + cw / 2, grid.top, px + cw / 2, grid.bottom, Palette.alpha(pc, 70), ink.dp(1f))
            ink.outline(c, Rect(px, py - ink.dp(4f), px + cw, py + ink.dp(4f)), pc, ink.dp(2f))
        }

        // status: the sample under the pen and the pen's level (signed and as the chip's byte)
        val sample = wave.samples.getOrNull(st.waveCol) ?: 0
        val status = if (onGrid) "Sample ${st.waveCol}: ${signed(sample)} (${hex2(sample and 0xFF)})   pen ${signed(st.waveLevel)} (${hex2(st.waveLevel and 0xFF)})"
            else "32 samples, -128..127 · ${if (chip.sharedWave) "SCC: SCC 4 and SCC 5 share one wave RAM" else "SCC+: every channel has its own wave RAM"}"
        ink.label(c, ink.fit(status, area.width() - pad * 2, small), area.left + pad, grid.bottom + ink.unit * 0.2f, statusH, Palette.FAINT, size = small)
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else v.toString()

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
            SccWaveView.publish(ctl)
            return true
        }
        tools.firstOrNull { it.first.contains(x, y) }?.let { (_, t) ->
            st.waveRow = SccWaveView.TOOLS_ROW
            st.waveCol = t.ordinal
            SccWaveView.applyTool(ctl, t)
            return true
        }
        if (nameRect.contains(x, y)) {
            st.waveRow = SccWaveView.NAME_ROW
            return true
        }
        if (grid.contains(x, y) && grid.height() > 0f) {
            drawing = true
            strokeWave = SccWaveView.number(st)
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

    private fun finish() {
        if (!drawing) return
        drawing = false
        strokeGesture?.let { session.endGesture(it) }
        strokeGesture = null
    }

    private fun paint(x: Float, y: Float) {
        val cw = grid.width() / SccWave.SAMPLES
        val sample = ((x - grid.left) / cw).toInt().coerceIn(0, SccWave.SAMPLES - 1)
        val level = (SccWaveView.LOW + (grid.bottom - y) / grid.height() * 256f - 0.5f).roundToInt().coerceIn(SccWaveView.LOW, SccWaveView.HIGH)
        val w = (song.scc.wave(strokeWave) ?: return).samples.toMutableList()
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
        if (SccWaveView.number(st) == strokeWave) {
            st.waveRow = SccWaveView.GRID_ROW
            st.waveCol = sample
            st.waveLevel = level
        }
        session.setSccWaveSamples(strokeWave, w, "Draw wave")
    }
}
