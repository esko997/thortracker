package org.thortracker.system.fm

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.channelCount
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * One operator box of an algorithm diagram: operator [op] (0-based) at grid [column] and [row] (fractional rows
 * centre a box between others), [carrier] when it feeds the output.
 */
data class FmNode(val op: Int, val column: Int, val row: Float, val carrier: Boolean)

/**
 * How an FM algorithm connects its operators, for drawing: [nodes] in drawing order on a [columns] × [rows] grid, and
 * the modulation [edges] (modulator op → modulated op). Carriers sit in the last column, next to the output bus.
 */
class FmRouting(val columns: Int, val rows: Int, val nodes: List<FmNode>, val edges: List<Pair<Int, Int>>) {
    companion object {
        /**
         * Routing of operator [chains], top to bottom: each chain modulates along itself and its last operator is a
         * carrier; chains align right (the OPL3's connections).
         */
        fun chains(chains: List<List<Int>>): FmRouting {
            val cols = chains.maxOf { it.size }
            val nodes = ArrayList<FmNode>()
            val edges = ArrayList<Pair<Int, Int>>()
            for ((ci, chain) in chains.withIndex()) {
                val start = cols - chain.size
                for ((k, op) in chain.withIndex()) {
                    nodes += FmNode(op, start + k, ci.toFloat(), k == chain.size - 1)
                    if (k > 0) edges += chain[k - 1] to op
                }
            }
            return FmRouting(cols, chains.size, nodes, edges)
        }
    }
}

/**
 * The envelope sketch of an FM sound in decibels (FM chips decay in straight lines in dB): attack ([attackMs] to full),
 * decay ([decayMs]) to [sustainDb], held for [holdMs] (0 = percussive: no hold) while falling to [heldEndDb] (a
 * YM2612's sustain rate), then the release ([releaseMs]). [toneDecayMs]: the modulators' brightness fading (dimmer
 * line), null for none. [ssgEg]: the YM2612's SSG-EG mode 8..15 drawn from the attack's end until OFF, or -1.
 */
class FmEnvelopeSketch(
    val title: String,
    val attackMs: Float,
    val decayMs: Float,
    val sustainDb: Float,
    val holdMs: Float,
    val releaseMs: Float,
    val toneDecayMs: Float?,
    val floorDb: Float = 48f,
    val heldEndDb: Float = sustainDb,
    val ssgEg: Int = -1,
)

/**
 * Shared drawing of the FM instrument editors (OPL3, YM2612): the bank column, page tabs, setting rows with a name
 * cursor, the Expert register grid (one column per operator), the algorithm diagram, the envelope sketch and the drum
 * sweep's pitch curve, plus the touch targets they register. Each system's painter lays out its own pages with these.
 */
internal abstract class FmInstrumentPainter(v: PaneView) : Painter(v) {
    protected val tabs = ArrayList<Pair<Rect, Int>>()
    protected val rowRects = ArrayList<Pair<Rect, Int>>()
    protected val gridCells = ArrayList<Triple<Rect, Int, Int>>()
    protected var slotTop = 0f
    protected var slotH = 0f
    protected var slotRect = Rect()
    protected var slotScroll = 0

    /** Right end of the settings column, for fitting values. */
    protected var rightEdge = 0f

    private var usageSong: Song? = null
    private val usage = HashMap<Int, Int>()

    /**
     * The left column: [slots] sounds, scrolled to keep [ViewState.instrument] in view, each with its [name] (null:
     * empty) and how often the arrangement uses it; [focused] outlines the selected one. Returns the column.
     */
    protected fun drawBank(c: Surface, area: Rect, top: Float, slots: Int, col: Int, focused: Boolean, name: (Int) -> String?): Rect {
        val leftW = minOf(area.width() * 0.26f, ink.unit * 12f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, Palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.4f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, slots - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        for (k in 0 until minOf(visible, slots - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, Palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            val slotName = name(i)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else Palette.FAINT, sel, sz)
            val used = usage(i)
            val nameColor = if (sel) Palette.TEXT else if (slotName == null) Palette.FAINT else if (used > 0) Palette.DIM else Palette.FAINT
            ink.label(c, ink.fit(slotName ?: "·", leftW - ink.unit * 4.4f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, Palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (focused) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        return left
    }

    protected fun drawName(c: Surface, name: String, rx: Float, vx: Float, y: Float, h: Float, sel: Boolean) {
        ink.label(c, "Name", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
        ink.label(c, name.ifEmpty { "(unnamed)" }, vx, y, h, if (name.isEmpty()) Palette.FAINT else Palette.TEXT, true)
        if (sel) {
            val before = name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, ink.unit, true)
            val ch = name.getOrNull(st.textPos)?.toString() ?: " "
            val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
            ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + h - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
    }

    protected fun setting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, ink.fit(label, vx - x - ink.unit * 0.3f), x, y, h, if (sel) Palette.TEXT else Palette.DIM)
        val max = rightEdge - vx
        ink.label(c, ink.fit(value, max, ink.unit, sel), vx, y, h, if (sel) (if (off) Palette.DIM else Palette.TEXT) else (if (off) Palette.FAINT else Palette.DIM), sel)
    }

    protected fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    /** Page tabs [labels] across [r], [current] highlighted; touching one shows that page ([tabs]). */
    protected fun drawTabs(c: Surface, r: Rect, col: Int, labels: List<String>, current: Int) {
        tabs.clear()
        val w = r.width() / labels.size
        for ((i, p) in labels.withIndex()) {
            val t = Rect(r.left + i * w, r.top, r.left + (i + 1) * w, r.bottom)
            val on = i == current
            if (on) ink.rect(c, t, Palette.mix(Palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else Palette.RULE)
            ink.label(c, ink.fit(p, w - ink.unit * 0.4f, ink.unit * 0.9f), t.centerX(), t.top, t.height(),
                if (on) col else Palette.DIM, on, ink.unit * 0.9f, Align.CENTER)
            tabs += t to i
        }
    }

    // ---- Expert grid ----------------------------------------------------------------------------------

    private fun opX(vx: Float, rw: Float, rx: Float, op: Int): Float {
        val colW = (rx + rw - vx) / 4f
        return vx + op * colW
    }

    /** "Op 1 mod · Op 2 ♪car …" over the grid; operators the sound does not play ([played] false) are faint. */
    protected fun drawGridHeader(c: Surface, rx: Float, vx: Float, rw: Float, y: Float, h: Float, col: Int,
                                 played: (Int) -> Boolean, carrier: (Int) -> Boolean): Float {
        val colW = (rx + rw - vx) / 4f
        val size = ink.unit * 0.8f
        ink.label(c, "Register", rx, y, h, Palette.FAINT, size = size)
        for (op in 0 until 4) {
            val x = opX(vx, rw, rx, op)
            val on = played(op)
            val car = on && carrier(op)
            val text = "Op ${op + 1} " + if (!on) "off" else if (car) "♪car" else "mod"
            ink.label(c, ink.fit(text, colW - ink.unit * 0.2f, size, true), x, y, h, if (!on) Palette.FAINT else if (car) col else Palette.DIM, true, size)
        }
        return y + h
    }

    /**
     * One register row of the grid: [label], then [text] of each operator; [rowIndex] is the row's index in the view's
     * rows (for touch), [current] the operator under the cursor.
     */
    protected fun drawGridRow(c: Surface, label: String, rowIndex: Int, rx: Float, vx: Float, rw: Float, y: Float, h: Float, sel: Boolean,
                              current: Int, played: (Int) -> Boolean, text: (Int) -> String) {
        val colW = (rx + rw - vx) / 4f
        if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.alpha(Palette.CURSOR_FILL, 160))
        ink.mono(c, label, rx, y, h, if (sel) Palette.TEXT else Palette.DIM, sel, ink.unit * 0.9f)
        for (op in 0 until 4) {
            val x = opX(vx, rw, rx, op)
            ink.mono(c, ink.fit(text(op), colW - ink.unit * 0.3f, ink.unit * 0.85f), x, y, h,
                if (!played(op)) Palette.FAINT else if (sel && op == current) Palette.TEXT else Palette.DIM, size = ink.unit * 0.85f)
            val cell = Rect(x - ink.unit * 0.2f, y + 1, x + colW - ink.unit * 0.2f, y + h - 1)
            gridCells += Triple(cell, rowIndex, op)
            if (sel && op == current) ink.outline(c, cell, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
    }

    // ---- algorithm diagram ----------------------------------------------------------------------------

    /**
     * The routing: one box per operator (number, [ratio], waveform [wave]), arrows from modulators into what they
     * modulate (elbows between rows), carriers into the output bus on the right, operator 1's [feedback] loop.
     * [focus]: the Expert grid's operator.
     */
    protected fun drawAlgorithm(c: Surface, r: Rect, title: String, routing: FmRouting, feedback: Int, col: Int, focus: Int,
                                ratio: (Int) -> String, wave: (Int) -> Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.4f
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.6f, r.top + titleH + ink.unit * 0.3f, r.right - ink.unit * 0.6f, r.bottom - ink.unit * 0.4f)
        val cols = routing.columns
        val outW = ink.unit * 2.2f
        val cellW = (g.width() - outW) / cols
        val cellH = minOf(g.height() / routing.rows, ink.unit * 4f)
        val boxW = minOf(cellW * 0.72f, ink.unit * 5.5f)
        val boxH = minOf(cellH * 0.72f, ink.unit * 2.6f)
        if (boxW < ink.unit * 1.5f || boxH < ink.unit) return
        val busX = g.right - outW * 0.45f
        val top = g.top + (g.height() - cellH * routing.rows) / 2f
        val carriers = routing.nodes.filter { it.carrier }
        val busTop = top + cellH * ((carriers.minOfOrNull { it.row } ?: 0f) + 0.5f)
        val busBottom = top + cellH * ((carriers.maxOfOrNull { it.row } ?: 0f) + 0.5f)
        ink.line(c, busX, busTop, busX, busBottom, col, ink.dp(2f))
        ink.label(c, "out", busX + ink.dp(4f), (busTop + busBottom) / 2f - ink.unit * 0.5f, ink.unit, col, true, ink.unit * 0.75f)
        val small = ink.unit * 0.7f
        fun boxOf(n: FmNode): Rect {
            val cx = g.left + cellW * (n.column + 0.5f)
            val cy = top + cellH * (n.row + 0.5f)
            return Rect(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2)
        }
        for (n in routing.nodes) {
            val box = boxOf(n)
            val cy = box.centerY()
            for ((from, to) in routing.edges) {
                if (to != n.op) continue
                val src = boxOf(routing.nodes.first { it.op == from })
                val sy = src.centerY()
                if (sy == cy) arrow(c, src.right, cy, box.left, cy, Palette.DIM)
                else {
                    // Between rows: out of the modulator, down or up halfway across, then into the operator.
                    val mx = (src.right + box.left) / 2f
                    ink.line(c, src.right, sy, mx, sy, Palette.DIM, ink.dp(1.5f))
                    ink.line(c, mx, sy, mx, cy, Palette.DIM, ink.dp(1.5f))
                    arrow(c, mx, cy, box.left, cy, Palette.DIM)
                }
            }
            val op = n.op
            val carrier = n.carrier
            ink.round(c, box, ink.dp(4f), Palette.mix(Palette.BG, col, if (carrier) 0.32f else 0.12f))
            ink.outline(c, box, if (op == focus) Palette.TEXT else if (carrier) col else Palette.DIM, if (op == focus) ink.dp(2.5f) else ink.dp(1.2f), ink.dp(4f))
            ink.label(c, "${op + 1}", box.left + ink.unit * 0.3f, box.top, boxH * 0.55f, if (carrier) Palette.TEXT else Palette.DIM, true, ink.unit * 0.9f)
            ink.label(c, ink.fit(ratio(op), boxW * 0.55f, small), box.right - ink.unit * 0.25f, box.top, boxH * 0.55f, Palette.DIM, size = small, align = Align.RIGHT)
            drawWave(c, Rect(box.left + ink.unit * 0.3f, box.top + boxH * 0.55f, box.right - ink.unit * 0.3f, box.bottom - boxH * 0.1f), wave(op), if (carrier) col else Palette.DIM)
            if (op == 0 && feedback > 0) {
                // Feedback: a loop over operator 1.
                val ly = box.top - ink.unit * 0.35f
                ink.line(c, box.left + boxW * 0.25f, box.top, box.left + boxW * 0.25f, ly, Palette.PLAYHEAD, ink.dp(1.5f))
                ink.line(c, box.left + boxW * 0.25f, ly, box.right - boxW * 0.25f, ly, Palette.PLAYHEAD, ink.dp(1.5f))
                ink.line(c, box.right - boxW * 0.25f, ly, box.right - boxW * 0.25f, box.top, Palette.PLAYHEAD, ink.dp(1.5f))
                ink.label(c, "fb$feedback", box.right - boxW * 0.2f, ly - ink.unit * 0.55f, ink.unit * 0.8f, Palette.PLAYHEAD, size = small)
            }
            if (carrier) arrow(c, box.right, cy, busX, cy, col)
        }
    }

    private fun arrow(c: Surface, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        ink.line(c, x0, y0, x1, y1, color, ink.dp(1.5f))
        val s = ink.dp(4f)
        ink.line(c, x1 - s, y1 - s, x1, y1, color, ink.dp(1.5f))
        ink.line(c, x1 - s, y1 + s, x1, y1, color, ink.dp(1.5f))
    }

    private val waveXs = FloatArray(WAVE_POINTS)
    private val waveYs = FloatArray(WAVE_POINTS)

    /** One cycle of waveform [wave] ([waveSample]). */
    private fun drawWave(c: Surface, r: Rect, wave: Int, color: Int) {
        if (r.width() < 4f || r.height() < 3f) return
        val mid = r.centerY()
        val amp = r.height() / 2f
        for (i in 0 until WAVE_POINTS) {
            val t = i.toFloat() / (WAVE_POINTS - 1)
            waveXs[i] = r.left + r.width() * t
            waveYs[i] = mid - amp * waveSample(wave, t)
        }
        c.polyline(waveXs, waveYs, WAVE_POINTS, color, ink.dp(1.2f))
    }

    // ---- envelope -------------------------------------------------------------------------------------

    protected val envXs = FloatArray(64)
    protected val envYs = FloatArray(64)

    /**
     * The carriers' envelope [e] in decibels: attack, decay to the sustain level, held until OFF (falling to
     * [FmEnvelopeSketch.heldEndDb]; percussive sounds have no hold), then the release; the modulators' tone decay
     * dimmer beneath; an SSG-EG's repeating shape from the attack's end to OFF. Segment widths ∝ √time, so fast and
     * slow settings both read.
     */
    protected fun drawEnvelope(c: Surface, r: Rect, e: FmEnvelopeSketch, col: Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.4f
        val sustainDb = e.sustainDb
        ink.label(c, ink.fit(e.title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.8f, r.top + titleH, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 1.3f)
        if (g.height() <= ink.unit) return
        val floorDb = e.floorDb
        fun yOf(db: Float) = g.top + g.height() * (db.coerceIn(0f, floorDb) / floorDb)
        val aMs = e.attackMs.coerceAtMost(4000f)
        val dMs = e.decayMs.coerceAtMost(8000f)
        val rMs = e.releaseMs.coerceAtMost(8000f)
        val holdMs = e.holdMs
        val wa = sqrt(maxOf(aMs, 4f)); val wd = sqrt(maxOf(dMs, 4f)); val wh = if (holdMs > 0f) sqrt(holdMs) else 0f; val wr = sqrt(maxOf(rMs, 4f))
        val total = wa + wd + wh + wr
        val xA = g.left + g.width() * wa / total
        val xD = xA + g.width() * wd / total
        val xH = xD + g.width() * wh / total
        for (x in listOf(xA, xD, xH)) ink.line(c, x, g.top, x, g.bottom, Palette.RULE, ink.dp(1f))
        ink.line(c, g.left, yOf(sustainDb), g.right, yOf(sustainDb), Palette.RULE, ink.dp(1f))
        // tone decay (modulators): from full brightness falling at their decay rate during the note
        if (e.toneDecayMs != null) {
            var n = 0
            val tMs = e.toneDecayMs.coerceAtMost(16000f)
            for (i in 0..16) {
                val t = i / 16f
                val x = xA + (g.right - xA) * t
                val ms = msAt(x, xA, xD, xH, g.right, dMs, holdMs, rMs)
                envXs[n] = x; envYs[n] = yOf(if (tMs <= 0f) 0f else 96f * ms / tMs); n++
            }
            c.polyline(envXs, envYs, n, Palette.alpha(col, 90), ink.dp(1.5f))
        }
        var n = 0
        fun add(x: Float, db: Float) { if (n < envXs.size) { envXs[n] = x; envYs[n] = yOf(db); n++ } }
        add(g.left, floorDb)
        for (i in 1..8) { val t = i / 8f; add(g.left + (xA - g.left) * t, floorDb * (1f - (1f - exp(-3f * t)) / (1f - exp(-3f)))) }
        val offDb = if (e.ssgEg in 8..15) {
            ssgShape(e.ssgEg, xA, (if (holdMs > 0f) xH else xD), minOf(floorDb, SSG_RANGE_DB), ::add)
        } else {
            add(xD, sustainDb)
            if (holdMs > 0f) add(xH, e.heldEndDb)
            e.heldEndDb
        }
        if (offDb < floorDb) add(g.right, floorDb)
        c.polyline(envXs, envYs, n, col, ink.dp(2.5f))
        val ls = ink.unit * 0.7f
        val ly = g.bottom + ink.dp(2f)
        val lh = ink.unit * 1.1f
        ink.label(c, "A ${timeText(aMs)}", (g.left + xA) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, if (e.ssgEg in 8..15) "SSG-EG" else "D ${timeText(dMs)}", (xA + xD) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        if (holdMs > 0f) ink.label(c, "held", (xD + xH) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "R ${timeText(rMs)}", (xH + g.right) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        if (holdMs > 0f) ink.label(c, "OFF", xH + ink.dp(3f), g.top, ink.unit, Palette.WRITE, size = ls)
    }

    /**
     * SSG-EG [mode] (8..15) from [x0] to [x1] as three cycles between 0 dB and [range] dB: bit 2 starts rising
     * (inverted), bit 1 alternates direction, bit 0 holds after the first cycle (at the far end, or flipped back when
     * it also alternates). Returns the level at [x1].
     */
    private fun ssgShape(mode: Int, x0: Float, x1: Float, range: Float, add: (Float, Float) -> Unit): Float {
        val attack = mode and 4 != 0
        val alternate = mode and 2 != 0
        val hold = mode and 1 != 0
        val cycles = 3
        val w = (x1 - x0) / cycles
        var high = !attack // true: starts at 0 dB (loud) and falls
        var db = 0f
        for (k in 0 until cycles) {
            val xs = x0 + w * k
            val from = if (high) 0f else range
            val to = if (high) range else 0f
            if (k > 0 && hold) { add(x1, db); return db }
            add(xs, from)
            add(xs + w, to)
            db = to
            if (hold) {
                // One pass, then the level holds: at the end, or inverted when the mode also alternates.
                db = if (alternate) from else to
                add(xs + w, db)
            } else if (alternate) high = !high
        }
        return db
    }

    /** Milliseconds since the note at [x] on the envelope's time axis. */
    private fun msAt(x: Float, xA: Float, xD: Float, xH: Float, right: Float, dMs: Float, holdMs: Float, rMs: Float): Float = when {
        x <= xD -> dMs * (x - xA) / maxOf(1f, xD - xA)
        x <= xH -> dMs + holdMs * (x - xD) / maxOf(1f, xH - xD)
        else -> dMs + holdMs + rMs * (x - xH) / maxOf(1f, right - xH)
    }

    // ---- drum sweep -----------------------------------------------------------------------------------

    /**
     * The pitch over the first half second ([ticks] player ticks): from [start] semitones toward [end] at [speed]/16
     * semitones per tick, then held; flat at the note when [active] is false. [title] says it in notes.
     */
    protected fun drawSweep(c: Surface, r: Rect, title: String, start: Int, end: Int, speed: Int, active: Boolean, ticks: Int, col: Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.4f
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 2.6f, r.top + titleH + ink.unit * 0.3f, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 1.3f)
        if (g.height() <= ink.unit) return
        val lo = minOf(0, start, end) - 2
        val hi = maxOf(0, start, end) + 2
        fun yOf(semi: Float) = g.bottom - g.height() * (semi - lo) / (hi - lo)
        ink.line(c, g.left, yOf(0f), g.right, yOf(0f), Palette.RULE, ink.dp(1f))
        ink.label(c, "note", g.left - ink.dp(3f), yOf(0f) - ink.unit * 0.5f, ink.unit, Palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        var n = 0
        for (i in 0 until envXs.size) {
            val t = i.toFloat() / (envXs.size - 1) * ticks
            val semi = if (!active) 0f else {
                val moved = speed / 16f * t
                val dist = (end - start).toFloat()
                start + if (abs(dist) <= moved) dist else moved * (if (dist > 0) 1 else -1)
            }
            envXs[n] = g.left + g.width() * i / (envXs.size - 1)
            envYs[n] = yOf(semi)
            n++
        }
        c.polyline(envXs, envYs, n, col, ink.dp(2.5f))
        val ls = ink.unit * 0.7f
        ink.label(c, signed(hi - 2), g.left - ink.dp(3f), g.top - ink.unit * 0.3f, ink.unit, Palette.DIM, size = ls, align = Align.RIGHT)
        ink.label(c, signed(lo + 2), g.left - ink.dp(3f), g.bottom - ink.unit * 0.7f, ink.unit, Palette.DIM, size = ls, align = Align.RIGHT)
        ink.label(c, "0.5 s", g.right, g.bottom + ink.dp(2f), ink.unit * 1.1f, Palette.FAINT, size = ls, align = Align.RIGHT)
    }

    // ---- usage ----------------------------------------------------------------------------------------

    protected fun usage(slot: Int): Int {
        val s = song
        if (s !== usageSong) {
            usage.clear()
            val counted = HashSet<Int>()
            for (pat in s.orders) {
                if (!counted.add(pat)) continue
                for (ch in 0 until s.channelCount()) for (cell in s.patterns[pat].tracks[ch]) {
                    if (cell.instrument > 0) usage[cell.instrument] = (usage[cell.instrument] ?: 0) + 1
                }
            }
            usageSong = s
        }
        return usage[slot] ?: 0
    }

    protected fun usageText(slot: Int): String {
        val n = usage(slot)
        return if (n == 0) "not used in the arrangement yet" else "used on $n row${if (n == 1) "" else "s"}"
    }

    // ---- touch ----------------------------------------------------------------------------------------

    /**
     * Touches on the shared parts: a tab shows its page ([showPage]), a slot selects it ([selectSlot] after the
     * cursor moves to the slot row), a grid cell moves to its row and operator, a setting row to that row.
     */
    protected fun downShared(x: Float, y: Float, slots: Int, showPage: (Int) -> Unit, selectSlot: () -> Unit): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, i) -> showPage(i); return true }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, slots)
            st.instField = 0
            selectSlot()
            return true
        }
        gridCells.firstOrNull { it.first.contains(x, y) }?.let { (_, rowIndex, op) ->
            st.instField = rowIndex
            st.envStep = op
            return true
        }
        rowRects.firstOrNull { it.first.contains(x, y) }?.let { (_, rowIndex) -> st.instField = rowIndex; return true }
        return false
    }

    companion object {
        const val WAVE_POINTS = 33

        /** How far an SSG-EG cycle swings: the chip repeats or inverts at attenuation 0x200 of 0x3FF. */
        const val SSG_RANGE_DB = 48f

        /** "+3", "0", "-5". */
        fun signed(v: Int) = if (v > 0) "+$v" else "$v"

        fun timeText(ms: Float): String = when {
            ms.isInfinite() -> "never"
            ms >= 1000f -> "${NumberText.fixed(ms / 1000.0, 1)} s"
            ms >= 10f -> "${ms.toInt()} ms"
            else -> "${NumberText.fixed(ms.toDouble(), 1)} ms"
        }

        /** A ten-step bar of [value] out of [max]. */
        fun bar(value: Int, max: Int): String {
            val n = (value * 10 + max / 2) / max
            return "▮".repeat(n) + "▯".repeat(10 - n)
        }

        /** OPL3 waveform [wave] at phase [t] (0..1), −1..1; 0 is the sine every FM chip has. */
        fun waveSample(wave: Int, t: Float): Float {
            val s = kotlin.math.sin(2.0 * kotlin.math.PI * t).toFloat()
            val s2 = kotlin.math.sin(4.0 * kotlin.math.PI * t).toFloat()
            return when (wave) {
                1 -> maxOf(0f, s)
                2 -> abs(s)
                3 -> if ((t * 4f).toInt() % 2 == 0) abs(s) else 0f
                4 -> if (t < 0.5f) s2 else 0f
                5 -> if (t < 0.5f) abs(s2) else 0f
                6 -> if (t < 0.5f) 1f else -1f
                7 -> if (t < 0.5f) 1f - 2f * t else -(1f - 2f * (t - 0.5f))
                else -> s
            }
        }
    }
}
