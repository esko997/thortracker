package org.thortracker.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.thortracker.editor.EditorSession.Companion.hex1
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.editor.ViewController
import org.thortracker.editor.WaveShapes
import org.thortracker.editor.firstChannelOf
import org.thortracker.model.InstrumentKind

/**
 * Wave editor: the 16 wave tables as thumbnails, a tool row, and a 32 x 16 drawing grid. The controller
 * pen is a crosshair (sample, level); holding A draws at the pen height. Touch draws by dragging, with
 * interpolation so fast strokes leave no gaps. Each stroke or hold is one undo step.
 */
internal class WavePainter(v: TrackerView) : Painter(v) {
    private val thumbs = ArrayList<Pair<RectF, Int>>()
    private val tools = ArrayList<Pair<RectF, Int>>()
    private var grid = RectF()
    private var lastSample = -1
    private var lastLevel = -1
    private var drawing = false

    override fun draw(c: Canvas, area: RectF) {
        val s = song
        val wave = s.waves[st.wave]
        val users = s.waveInstruments.withIndex().filter { it.value.wave == st.wave }.map { hex2(it.index + 1) }
        val locked = if (st.lockedSubject >= 0) "locked  " else ""
        val top = header(c, area, "Wave ${hex1(st.wave)}", locked + if (users.isEmpty()) "No wave sound plays this wave" else "Played by sound ${users.joinToString(", ")}")
        val col = Palette.channel(maxOf(0, s.firstChannelOf(InstrumentKind.WAVE)))
        val pad = ink.unit * 0.7f

        // thumbnails
        thumbs.clear()
        val thumbH = ink.unit * 2.6f
        val tw = (area.width() - pad * 2) / s.waves.size
        for ((i, w) in s.waves.withIndex()) {
            val r = RectF(area.left + pad + i * tw + 2, top + ink.unit * 0.4f, area.left + pad + (i + 1) * tw - 2, top + ink.unit * 0.4f + thumbH)
            val sel = i == st.wave
            ink.round(c, r, ink.dp(3f), if (sel) Palette.CURSOR_FILL else Palette.LANE)
            val bw = r.width() / w.size
            for ((j, sample) in w.withIndex()) {
                val h = (r.height() - ink.unit * 0.9f) * sample / 15f
                ink.rect(c, r.left + j * bw, r.bottom - 2 - h, r.left + (j + 1) * bw, r.bottom - 2, if (sel) col else Palette.FAINT)
            }
            ink.mono(c, hex1(i), r.left + ink.dp(3f), r.top, ink.unit * 0.9f, if (sel) Palette.TEXT else Palette.DIM, size = ink.unit * 0.7f)
            if (sel) ink.outline(c, r, col, ink.dp(2f), ink.dp(3f))
            thumbs += r to i
        }

        // tool row
        tools.clear()
        val toolTop = top + thumbH + ink.unit * 0.9f
        val toolH = ink.unit * 1.7f
        var x = area.left + pad
        val size = ink.unit * 0.85f
        for ((i, name) in ViewController.WAVE_TOOLS.withIndex()) {
            val text = if (i == 8) "Use in sound ${hex2(ctl.waveTargetSound())}" else name
            val w = ink.labelWidth(text, size) + ink.unit
            if (x + w > area.right - pad) break
            val r = RectF(x, toolTop, x + w, toolTop + toolH)
            val sel = st.waveRow == 0 && st.waveCol == i
            ink.round(c, r, ink.dp(4f), if (sel) Palette.CURSOR_FILL else Palette.RULE)
            if (sel) ink.outline(c, r, cursorColor(), ink.dp(2f), ink.dp(4f))
            ink.label(c, text, r.centerX(), r.top, r.height(), if (sel) Palette.TEXT else Palette.DIM, sel, size, Paint.Align.CENTER)
            tools += r to i
            x += w + ink.unit * 0.4f
        }

        // grid
        val hexH = ink.unit * 1.3f
        val gTop = toolTop + toolH + ink.unit * 0.8f
        val gBottom = area.bottom - hexH - ink.unit * 0.4f
        val labelW = ink.unit * 1.6f
        grid = RectF(area.left + pad + labelW, gTop, area.right - pad, gBottom)
        if (grid.height() < ink.unit * 3) return
        val cw = grid.width() / WaveShapes.SAMPLES
        val ch = grid.height() / (WaveShapes.MAX + 1)
        ink.rect(c, grid, Palette.LANE)
        for (lv in 0..WaveShapes.MAX) {
            val y = grid.bottom - (lv + 1) * ch
            if (lv % 4 == 0) ink.line(c, grid.left, y + ch, grid.right, y + ch, Palette.RULE, 1f)
            if (lv % 4 == 3 || lv == 0) ink.mono(c, hex1(lv), area.left + pad, y, ch, Palette.FAINT, size = minOf(ink.unit * 0.75f, ch * 0.9f))
        }
        for (i in 0..WaveShapes.SAMPLES) {
            val gx = grid.left + i * cw
            ink.line(c, gx, grid.top, gx, grid.bottom, if (i % 8 == 0) Palette.RULE else Palette.alpha(Palette.RULE, 90), 1f)
        }
        val mid = grid.bottom - grid.height() * 7.5f / 15f
        ink.line(c, grid.left, mid, grid.right, mid, Palette.alpha(Palette.DIM, 70), ink.dp(1f))
        for ((i, sample) in wave.withIndex()) {
            val bx = grid.left + i * cw
            val topY = grid.bottom - (sample + 1) * ch
            ink.rect(c, bx + 1, topY + ch, bx + cw - 1, grid.bottom, Palette.alpha(col, 70))
            ink.rect(c, bx + 1, topY, bx + cw - 1, topY + ch, col)
            ink.mono(c, hex1(sample), bx + cw / 2, grid.bottom + ink.unit * 0.2f, hexH, if (st.waveRow == 1 && i == st.waveCol) Palette.TEXT else Palette.FAINT, size = minOf(ink.unit * 0.8f, cw * 1.1f), align = Paint.Align.CENTER)
        }
        // pen crosshair
        if (st.waveRow == 1) {
            val px = grid.left + st.waveCol * cw
            val py = grid.bottom - (st.waveLevel + 1) * ch
            val pc = cursorColor()
            ink.line(c, grid.left, py + ch / 2, grid.right, py + ch / 2, Palette.alpha(pc, 70), ink.dp(1f))
            ink.line(c, px + cw / 2, grid.top, px + cw / 2, grid.bottom, Palette.alpha(pc, 70), ink.dp(1f))
            ink.outline(c, RectF(px, py, px + cw, py + ch), pc, ink.dp(2f))
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
            ctl.publishWave()
            return true
        }
        tools.firstOrNull { it.first.contains(x, y) }?.let { (_, i) ->
            st.waveRow = 0
            st.waveCol = i
            ctl.applyTool(i)
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
        val cw = grid.width() / WaveShapes.SAMPLES
        val ch = grid.height() / (WaveShapes.MAX + 1)
        val sample = ((x - grid.left) / cw).toInt().coerceIn(0, WaveShapes.SAMPLES - 1)
        val level = ((grid.bottom - y) / ch).toInt().coerceIn(0, WaveShapes.MAX)
        val w = song.waves[strokeWave].toMutableList()
        if (lastSample < 0 || lastSample == sample) {
            w[sample] = level
        } else {
            val step = if (sample > lastSample) 1 else -1
            var i = lastSample
            while (true) {
                val t = (i - lastSample).toFloat() / (sample - lastSample)
                w[i] = Math.round(lastLevel + (level - lastLevel) * t)
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
        session.setWave(strokeWave, w, "Draw wave")
    }
}
