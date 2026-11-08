package org.thortracker.system.opl3

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.channelCount
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.opl3.Opl3InstrumentView.Page
import org.thortracker.system.opl3.Opl3InstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * OPL3 instrument editor. Left: the 64 slots of the bank with how often each is used. Right: name, the channel Listen
 * plays on, page tabs, then the page: the curated controls beside the drawn algorithm (operators, routing, feedback)
 * and envelope; the Expert register grid (one column per operator, carriers marked); the drum sweep with its pitch
 * curve; or the chord-shape table with the notes each shape sounds. On narrow displays the drawings go below the rows.
 */
internal class Opl3InstrumentPainter(v: PaneView) : Painter(v) {
    private val tabs = ArrayList<Pair<Rect, Int>>()
    private val rowRects = ArrayList<Pair<Rect, Int>>()
    private val gridCells = ArrayList<Triple<Rect, Int, Int>>()
    private var slotTop = 0f
    private var slotH = 0f
    private var slotRect = Rect()
    private var slotScroll = 0
    private var listRect = Rect()
    private var listRowH = 0f
    private var listScroll = 0
    /** Right end of the settings column, for fitting values. */
    private var rightEdge = 0f

    private var usageSong: Song? = null
    private val usage = HashMap<Int, Int>()

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = Opl3InstrumentView.sound(s, st)
        val page = Opl3InstrumentView.page(st)
        val col = Palette.channel(Opl3InstrumentView.listenChannel(st, ins))
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = if (ins == null) "empty slot" else Opl3Curated.ALGORITHMS[Opl3Curated.controls(ins).algorithm].name
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "OPL3 · $kind$locked  ${usageText(st.instrument)}")
        val row = Opl3InstrumentView.row(st)
        rowRects.clear()
        gridCells.clear()
        listRect = Rect()

        // left column: the bank, scrolled to keep the selected slot in view
        val leftW = minOf(area.width() * 0.26f, ink.unit * 12f)
        val left = Rect(area.left, top, area.left + leftW, area.bottom)
        ink.rect(c, left, Palette.LANE)
        slotTop = top + ink.unit * 0.2f
        slotH = ink.unit * 1.4f
        val visible = maxOf(1, ((area.bottom - slotTop) / slotH).toInt())
        slotScroll = slotScroll.coerceIn(maxOf(0, st.instrument - visible), minOf(st.instrument - 1, Opl3System.SLOTS - visible).coerceAtLeast(0))
        slotRect = Rect(left.left, slotTop, left.right, slotTop + slotH * visible)
        for (k in 0 until minOf(visible, Opl3System.SLOTS - slotScroll)) {
            val i = slotScroll + k + 1
            val y = slotTop + k * slotH
            val sel = i == st.instrument
            if (sel) {
                ink.rect(c, left.left, y, left.right, y + slotH, Palette.CURSOR_FILL)
                ink.rect(c, left.left, y, left.left + ink.dp(3f), y + slotH, col)
            }
            val sz = minOf(ink.unit, slotH * 0.72f)
            val slotIns = s.opl3.instrument(i)
            ink.mono(c, hex2(i), left.left + ink.unit * 0.6f, y, slotH, if (sel) col else Palette.FAINT, sel, sz)
            val used = usage(i)
            val name = slotIns?.let { it.name.ifEmpty { "(unnamed)" } + if (it.fourOp) " ⁴" else "" } ?: "·"
            val nameColor = if (sel) Palette.TEXT else if (slotIns == null) Palette.FAINT else if (used > 0) Palette.DIM else Palette.FAINT
            ink.label(c, ink.fit(name, leftW - ink.unit * 4.4f, sz), left.left + ink.unit * 2.4f, y, slotH, nameColor, size = sz)
            if (used > 0) ink.mono(c, used.toString(), left.right - ink.unit * 0.5f, y, slotH, Palette.FAINT, size = sz * 0.8f, align = Align.RIGHT)
        }
        if (row == Row.SLOT) {
            val y = slotTop + (st.instrument - 1 - slotScroll) * slotH
            ink.outline(c, Rect(left.left + 2, y + 1, left.right - 2, y + slotH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // right: settings rows, with the drawings beside them on wide displays or below them on narrow ones
        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val side = fullW >= ink.unit * 50f && page != Page.CHORDS
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = Opl3InstrumentView.rows(st).filter { it != Row.SLOT && it != Row.SHAPES }
        val gridRows = rows.count { it in Opl3InstrumentView.GRID }
        val plain = rows.size - gridRows
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side || page == Page.CHORDS) 0f else ink.unit * 9f
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (plain + gridRows + (if (gridRows > 0) 1 else 0) + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        if (ins == null && page != Page.CHORDS) {
            for (r in rows) {
                if (r == Row.PAGE) { drawTabs(c, Rect(rx, y, rx + rw, y + ink.unit * 1.9f), col); y += ink.unit * 1.9f; continue }
                if (r == Row.NAME) {
                    val sel = r == row
                    if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + rowH, Palette.CURSOR_FILL)
                    setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, rowH, sel, true)
                    y += rowH
                }
            }
            return
        }
        var gridHeaderDrawn = false
        for (r in rows) {
            val sel = r == row
            if (r in Opl3InstrumentView.GRID && ins != null) {
                if (!gridHeaderDrawn) { y = drawGridHeader(c, ins, rx, vx, rw, y, rowH, col); gridHeaderDrawn = true }
                drawGridRow(c, ins, r, rx, vx, rw, y, rowH, sel)
                rowRects += Rect(rx, y, rx + rw, y + rowH) to rows.indexOf(r) + 1
                y += rowH
                continue
            }
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when (r) {
                Row.NAME -> drawName(c, ins, rx, vx, y, h, sel)
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val ch = Opl3InstrumentView.listenChannel(st, ins)
                    val text = s.system.channels.getOrNull(ch)?.name.orEmpty() + if (ch != st.listenChannel) " (4-op: FM 1-6 only)" else ""
                    ink.label(c, ink.fit(text, rightEdge - vx), vx, y, h, col, true)
                }
                Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col)
                else -> if (ins != null) setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to Opl3InstrumentView.rows(st).indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f) return
        when (page) {
            Page.SOUND -> if (ins != null) {
                // Side by side when the box is wide, stacked when it is tall.
                if (box.width() > box.height() * 1.6f) {
                    val mid = box.left + box.width() * 0.5f
                    drawAlgorithm(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), ins, col, -1)
                    drawEnvelope(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), ins, col)
                } else {
                    val mid = box.top + box.height() * 0.5f
                    drawAlgorithm(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), ins, col, -1)
                    drawEnvelope(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), ins, col)
                }
            }
            Page.EXPERT -> if (ins != null) drawAlgorithm(c, box, ins, col, Opl3InstrumentView.operator(st))
            Page.DRUM -> if (ins != null) drawSweep(c, box, ins, col)
            Page.CHORDS -> drawChords(c, box, col, row == Row.SHAPES)
        }
    }

    private fun drawName(c: Surface, ins: Opl3Instrument?, rx: Float, vx: Float, y: Float, h: Float, sel: Boolean) {
        val name = ins?.name.orEmpty()
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

    /** A curated or stored setting's text: the value, then what it means. */
    private fun value(ins: Opl3Instrument, r: Row): String {
        val k = Opl3Curated.controls(ins)
        fun approx(exact: Boolean) = if (exact) "" else "≈ "
        return when (r) {
            Row.PRESET, Row.DRUM_PRESET -> Opl3Presets.ALL.firstOrNull { it.instrument == ins }?.let { p ->
                p.name + (p.note?.let { "   drum, voiced at ${Opl3System.noteName(it)}" } ?: "")
            } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"
            Row.ALGORITHM -> Opl3Curated.ALGORITHMS[k.algorithm].let { "${it.name}   ${it.diagram}" }
            Row.RATIO -> approx(k.ratioExact) + Opl3Curated.ratios(ins.fourOp)[k.ratio].name + if (k.ratioExact) "" else "   (now " +
                ins.operators.take(ins.operatorCount).joinToString(":") { Opl3Curated.multText(it.mult) } + ")"
            Row.WAVES -> approx(k.wavesExact) + Opl3Curated.waves(ins.fourOp)[k.waves].name
            Row.BRIGHTNESS -> if (k.brightness < 0) "—   additive: no modulators" else "${k.brightness}   ${bar(k.brightness, 63)}"
            Row.LEVEL -> "${k.level}   ${bar(k.level, 63)}"
            Row.FEEDBACK -> "${k.feedback}   ${if (k.feedback == 0) "pure" else bar(k.feedback, 7)}"
            Row.ATTACK -> approx(k.envelopeExact) + "${k.attack}   ${timeText(attackMs(k.attack))} to full"
            Row.DECAY -> approx(k.envelopeExact) + "${k.decay}   ${timeText(decayMs(k.decay, 15 - k.sustain.coerceAtLeast(0)))} to sustain"
            Row.SUSTAIN -> approx(k.envelopeExact) + if (k.sustain == 0) "0   percussive: fades while held" else "${k.sustain}   held at −${(15 - k.sustain) * 3} dB"
            Row.RELEASE -> approx(k.envelopeExact) + "${k.release}   ${timeText(decayMs(k.release, 16))} after OFF"
            Row.TONE_DECAY -> if (k.toneDecay < 0) "—   additive: no modulators" else approx(k.envelopeExact) + "${k.toneDecay}   brightness fades in ${timeText(decayMs(k.toneDecay, 16))}"
            Row.KEY_SCALE -> approx(k.keyScaleExact) + Opl3Curated.KEY_SCALES[k.keyScale]
            Row.LFO -> approx(k.lfoExact) + Opl3Curated.LFOS[k.lfo]
            Row.MODE -> if (ins.fourOp) "4-op   (FM 1-6 only, no chords)" else "2-op   (any channel, chords)"
            Row.CONNECTION -> "${ins.algorithm}   ${Opl3Curated.ALGORITHMS.firstOrNull { it.fourOp == ins.fourOp && it.algorithm == ins.algorithm }?.diagram ?: ""}"
            Row.RAW_FEEDBACK -> "${ins.feedback}   operator 1"
            Row.SWEEP_START -> if (!ins.sweep.active) "${Opl3Rules.signed(ins.sweep.start)}   (sweep off: set a speed)" else "${Opl3Rules.signed(ins.sweep.start)} semitones from the note"
            Row.SWEEP_END -> "${Opl3Rules.signed(ins.sweep.end)} semitones"
            Row.SWEEP_SPEED -> if (!ins.sweep.active) "0   off" else "${ins.sweep.speed}   ${NumberText.fixed(ins.sweep.speed / 16.0, 2)} semitones per tick · ${timeText(sweepMs(ins.sweep))}"
            else -> ""
        }
    }

    private fun dim(ins: Opl3Instrument, r: Row): Boolean = when (r) {
        Row.BRIGHTNESS, Row.TONE_DECAY -> Opl3Curated.controls(ins).brightness < 0
        Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> !ins.sweep.active
        else -> false
    }

    private fun bar(value: Int, max: Int): String {
        val n = (value * 10 + max / 2) / max
        return "▮".repeat(n) + "▯".repeat(10 - n)
    }

    private fun setting(c: Surface, label: String, value: String, x: Float, vx: Float, y: Float, h: Float, sel: Boolean, off: Boolean) {
        ink.label(c, ink.fit(label, vx - x - ink.unit * 0.3f), x, y, h, if (sel) Palette.TEXT else Palette.DIM)
        val max = rightEdge - vx
        ink.label(c, ink.fit(value, max, ink.unit, sel), vx, y, h, if (sel) (if (off) Palette.DIM else Palette.TEXT) else (if (off) Palette.FAINT else Palette.DIM), sel)
    }

    private fun cursorColor() = when {
        ctl.editHeld -> Palette.WRITE
        ctl.auditionHeld -> Palette.LISTEN
        v.controllerFocused -> Palette.TEXT
        else -> Palette.DIM
    }

    private fun drawTabs(c: Surface, r: Rect, col: Int) {
        tabs.clear()
        val pages = Page.entries
        val w = r.width() / pages.size
        for ((i, p) in pages.withIndex()) {
            val t = Rect(r.left + i * w, r.top, r.left + (i + 1) * w, r.bottom)
            val on = i == st.envelope
            if (on) ink.rect(c, t, Palette.mix(Palette.BG, col, 0.18f))
            ink.rect(c, t.left, t.bottom - ink.dp(if (on) 3f else 1f), t.right, t.bottom, if (on) col else Palette.RULE)
            ink.label(c, ink.fit(p.label, w - ink.unit * 0.4f, ink.unit * 0.9f), t.centerX(), t.top, t.height(),
                if (on) col else Palette.DIM, on, ink.unit * 0.9f, Align.CENTER)
            tabs += t to i
        }
    }

    // ---- Expert grid ----------------------------------------------------------------------------------

    private fun opX(vx: Float, rw: Float, rx: Float, op: Int): Float {
        val colW = (rx + rw - vx) / 4f
        return vx + op * colW
    }

    /** "Op 1 mod · Op 2 ♪ car …" over the grid; operators a 2-op sound does not play are faint. */
    private fun drawGridHeader(c: Surface, ins: Opl3Instrument, rx: Float, vx: Float, rw: Float, y: Float, h: Float, col: Int): Float {
        val colW = (rx + rw - vx) / 4f
        val size = ink.unit * 0.8f
        ink.label(c, "Register", rx, y, h, Palette.FAINT, size = size)
        for (op in 0 until 4) {
            val x = opX(vx, rw, rx, op)
            val played = op < ins.operatorCount
            val carrier = played && ins.isCarrier(op)
            val text = "Op ${op + 1} " + if (!played) "off" else if (carrier) "♪car" else "mod"
            ink.label(c, ink.fit(text, colW - ink.unit * 0.2f, size, true), x, y, h, if (!played) Palette.FAINT else if (carrier) col else Palette.DIM, true, size)
        }
        return y + h
    }

    private fun drawGridRow(c: Surface, ins: Opl3Instrument, r: Row, rx: Float, vx: Float, rw: Float, y: Float, h: Float, sel: Boolean) {
        val colW = (rx + rw - vx) / 4f
        if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.alpha(Palette.CURSOR_FILL, 160))
        ink.mono(c, r.label, rx, y, h, if (sel) Palette.TEXT else Palette.DIM, sel, ink.unit * 0.9f)
        val current = Opl3InstrumentView.operator(st)
        for (op in 0 until 4) {
            val o = ins.operators[op]
            val played = op < ins.operatorCount
            val text = when (r) {
                Row.OP_MULT -> "${NumberText.hex(o.mult)} ×${Opl3Curated.multText(o.mult)}"
                Row.OP_TL -> "${hex2(o.tl)} ${NumberText.fixed(o.tl * 0.75, 1)}dB"
                Row.OP_AR -> NumberText.hex(o.ar)
                Row.OP_DR -> NumberText.hex(o.dr)
                Row.OP_SL -> "${NumberText.hex(o.sl)} −${o.sl * 3}dB"
                Row.OP_RR -> NumberText.hex(o.rr)
                Row.OP_WAVE -> "${o.wave} ${Opl3Curated.WAVE_NAMES[o.wave]}"
                Row.OP_KSL -> "${o.ksl} ${KSL_DB[o.ksl]}"
                Row.OP_AM -> if (o.am) "■ on" else "·"
                Row.OP_VIB -> if (o.vib) "■ on" else "·"
                Row.OP_EGT -> if (o.sustain) "■ hold" else "· perc"
                Row.OP_KSR -> if (o.ksr) "■ on" else "·"
                else -> ""
            }
            val x = opX(vx, rw, rx, op)
            ink.mono(c, ink.fit(text, colW - ink.unit * 0.3f, ink.unit * 0.85f), x, y, h, if (!played) Palette.FAINT else if (sel && op == current) Palette.TEXT else Palette.DIM, size = ink.unit * 0.85f)
            val cell = Rect(x - ink.unit * 0.2f, y + 1, x + colW - ink.unit * 0.2f, y + h - 1)
            gridCells += Triple(cell, Opl3InstrumentView.rows(st).indexOf(r), op)
            if (sel && op == current) ink.outline(c, cell, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
    }

    // ---- algorithm diagram ----------------------------------------------------------------------------

    /** Operator chains of each routing, top to bottom; each chain's last operator is a carrier into the output. */
    private fun chains(ins: Opl3Instrument): List<List<Int>> = if (!ins.fourOp) {
        if (ins.algorithm == 1) listOf(listOf(0), listOf(1)) else listOf(listOf(0, 1))
    } else when (ins.algorithm) {
        1 -> listOf(listOf(0, 1), listOf(2, 3))
        2 -> listOf(listOf(0), listOf(1, 2, 3))
        3 -> listOf(listOf(0), listOf(1, 2), listOf(3))
        else -> listOf(listOf(0, 1, 2, 3))
    }

    /**
     * The routing: one box per played operator (number, ratio, waveform), arrows from modulators into what they
     * modulate, carriers into the output bus on the right, operator 1's feedback loop. [focus]: the Expert grid's operator.
     */
    private fun drawAlgorithm(c: Surface, r: Rect, ins: Opl3Instrument, col: Int, focus: Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.4f
        val a = Opl3Curated.ALGORITHMS.firstOrNull { it.fourOp == ins.fourOp && it.algorithm == ins.algorithm }
        ink.label(c, ink.fit("${a?.name ?: "Algorithm"} ${a?.diagram ?: ""} · fb ${ins.feedback}", r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.6f, r.top + titleH + ink.unit * 0.3f, r.right - ink.unit * 0.6f, r.bottom - ink.unit * 0.4f)
        val chains = chains(ins)
        val cols = chains.maxOf { it.size }
        val outW = ink.unit * 2.2f
        val cellW = (g.width() - outW) / cols
        val cellH = minOf(g.height() / chains.size, ink.unit * 4f)
        val boxW = minOf(cellW * 0.72f, ink.unit * 5.5f)
        val boxH = minOf(cellH * 0.72f, ink.unit * 2.6f)
        if (boxW < ink.unit * 1.5f || boxH < ink.unit) return
        val busX = g.right - outW * 0.45f
        val top = g.top + (g.height() - cellH * chains.size) / 2f
        val busTop = top + cellH * 0.5f
        val busBottom = top + cellH * (chains.size - 0.5f)
        ink.line(c, busX, busTop, busX, busBottom, col, ink.dp(2f))
        ink.label(c, "out", busX + ink.dp(4f), (busTop + busBottom) / 2f - ink.unit * 0.5f, ink.unit, col, true, ink.unit * 0.75f)
        val small = ink.unit * 0.7f
        for ((ci, chain) in chains.withIndex()) {
            val cy = top + cellH * (ci + 0.5f)
            // Chains align right, so every carrier sits next to the output bus.
            val start = cols - chain.size
            var prevRight = Float.NaN
            for ((k, op) in chain.withIndex()) {
                val cx = g.left + cellW * (start + k + 0.5f)
                val box = Rect(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2)
                val carrier = ins.isCarrier(op)
                val o = ins.operators[op]
                if (!prevRight.isNaN()) arrow(c, prevRight, cy, box.left, cy, Palette.DIM)
                ink.round(c, box, ink.dp(4f), Palette.mix(Palette.BG, col, if (carrier) 0.32f else 0.12f))
                ink.outline(c, box, if (op == focus) Palette.TEXT else if (carrier) col else Palette.DIM, if (op == focus) ink.dp(2.5f) else ink.dp(1.2f), ink.dp(4f))
                ink.label(c, "${op + 1}", box.left + ink.unit * 0.3f, box.top, boxH * 0.55f, if (carrier) Palette.TEXT else Palette.DIM, true, ink.unit * 0.9f)
                ink.label(c, ink.fit("×${Opl3Curated.multText(o.mult)}", boxW * 0.55f, small), box.right - ink.unit * 0.25f, box.top, boxH * 0.55f, Palette.DIM, size = small, align = Align.RIGHT)
                drawWave(c, Rect(box.left + ink.unit * 0.3f, box.top + boxH * 0.55f, box.right - ink.unit * 0.3f, box.bottom - boxH * 0.1f), o.wave, if (carrier) col else Palette.DIM)
                if (op == 0 && ins.feedback > 0) {
                    // Feedback: a loop over operator 1.
                    val ly = box.top - ink.unit * 0.35f
                    ink.line(c, box.left + boxW * 0.25f, box.top, box.left + boxW * 0.25f, ly, Palette.PLAYHEAD, ink.dp(1.5f))
                    ink.line(c, box.left + boxW * 0.25f, ly, box.right - boxW * 0.25f, ly, Palette.PLAYHEAD, ink.dp(1.5f))
                    ink.line(c, box.right - boxW * 0.25f, ly, box.right - boxW * 0.25f, box.top, Palette.PLAYHEAD, ink.dp(1.5f))
                    ink.label(c, "fb${ins.feedback}", box.right - boxW * 0.2f, ly - ink.unit * 0.55f, ink.unit * 0.8f, Palette.PLAYHEAD, size = small)
                }
                prevRight = box.right
                if (k == chain.size - 1) arrow(c, box.right, cy, busX, cy, col)
            }
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

    /** One cycle of OPL3 waveform [wave] (the chip's 8 shapes). */
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

    private val envXs = FloatArray(64)
    private val envYs = FloatArray(64)

    /**
     * The carriers' envelope in decibels (the chip's decay and release are straight lines in dB): attack, decay to the
     * sustain level, held until OFF (percussive sounds keep falling at the release rate), then the release; the
     * modulators' tone decay dimmer beneath. Segment widths ∝ √time, so fast and slow settings both read.
     */
    private fun drawEnvelope(c: Surface, r: Rect, ins: Opl3Instrument, col: Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val k = Opl3Curated.controls(ins)
        val titleH = ink.unit * 1.4f
        val sustainDb = if (k.sustain == 0) 45f else (15 - k.sustain) * 3f
        ink.label(c, ink.fit(if (k.sustain == 0) "Envelope · percussive: fades while held" else "Envelope · held at −${sustainDb.toInt()} dB until OFF", r.width() - ink.unit),
            r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 0.8f, r.top + titleH, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 1.3f)
        if (g.height() <= ink.unit) return
        val floorDb = 48f
        fun yOf(db: Float) = g.top + g.height() * (db.coerceIn(0f, floorDb) / floorDb)
        val aMs = attackMs(k.attack).coerceAtMost(4000f)
        val dMs = decayMs(k.decay, (sustainDb / 3f).toInt()).coerceAtMost(8000f)
        val rMs = decayMs(k.release, 16).coerceAtMost(8000f)
        val holdMs = if (k.sustain == 0) 0f else 500f
        val wa = sqrt(maxOf(aMs, 4f)); val wd = sqrt(maxOf(dMs, 4f)); val wh = if (holdMs > 0f) sqrt(holdMs) else 0f; val wr = sqrt(maxOf(rMs, 4f))
        val total = wa + wd + wh + wr
        val xA = g.left + g.width() * wa / total
        val xD = xA + g.width() * wd / total
        val xH = xD + g.width() * wh / total
        for (x in listOf(xA, xD, xH)) ink.line(c, x, g.top, x, g.bottom, Palette.RULE, ink.dp(1f))
        ink.line(c, g.left, yOf(sustainDb), g.right, yOf(sustainDb), Palette.RULE, ink.dp(1f))
        // tone decay (modulators): from full brightness falling at their decay rate during the note
        if (k.toneDecay >= 0) {
            var n = 0
            val tMs = decayMs(k.toneDecay, 16).coerceAtMost(16000f)
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
        add(xD, sustainDb)
        if (holdMs > 0f) add(xH, sustainDb)
        add(g.right, floorDb)
        c.polyline(envXs, envYs, n, col, ink.dp(2.5f))
        val ls = ink.unit * 0.7f
        val ly = g.bottom + ink.dp(2f)
        val lh = ink.unit * 1.1f
        ink.label(c, "A ${timeText(aMs)}", (g.left + xA) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "D ${timeText(dMs)}", (xA + xD) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        if (holdMs > 0f) ink.label(c, "held", (xD + xH) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        ink.label(c, "R ${timeText(rMs)}", (xH + g.right) / 2, ly, lh, Palette.DIM, size = ls, align = Align.CENTER)
        if (holdMs > 0f) ink.label(c, "OFF", xH + ink.dp(3f), g.top, ink.unit, Palette.WRITE, size = ls)
    }

    /** Milliseconds since the note at [x] on the envelope's time axis. */
    private fun msAt(x: Float, xA: Float, xD: Float, xH: Float, right: Float, dMs: Float, holdMs: Float, rMs: Float): Float = when {
        x <= xD -> dMs * (x - xA) / maxOf(1f, xD - xA)
        x <= xH -> dMs + holdMs * (x - xD) / maxOf(1f, xH - xD)
        else -> dMs + holdMs + rMs * (x - xH) / maxOf(1f, right - xH)
    }

    // ---- drum sweep -----------------------------------------------------------------------------------

    /** The pitch over the first half second: from the start offset toward the end at the sweep's speed, then held. */
    private fun drawSweep(c: Surface, r: Rect, ins: Opl3Instrument, col: Int) {
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val sw = ins.sweep
        val titleH = ink.unit * 1.4f
        val root = ctl.defaultNote()
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${Opl3System.noteName((root + sw.start).coerceIn(Opl3Rules.NOTES))} to ${Opl3System.noteName((root + sw.end).coerceIn(Opl3Rules.NOTES))} " +
                "in ${timeText(sweepMs(sw))} (note ${Opl3System.noteName(root)})"
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        val g = Rect(r.left + ink.unit * 2.6f, r.top + titleH + ink.unit * 0.3f, r.right - ink.unit * 0.8f, r.bottom - ink.unit * 1.3f)
        if (g.height() <= ink.unit) return
        val lo = minOf(0, sw.start, sw.end) - 2
        val hi = maxOf(0, sw.start, sw.end) + 2
        fun yOf(semi: Float) = g.bottom - g.height() * (semi - lo) / (hi - lo)
        ink.line(c, g.left, yOf(0f), g.right, yOf(0f), Palette.RULE, ink.dp(1f))
        ink.label(c, "note", g.left - ink.dp(3f), yOf(0f) - ink.unit * 0.5f, ink.unit, Palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        val ticks = 30 * Opl3SweepTicks.perFrame(song)
        var n = 0
        for (i in 0 until envXs.size) {
            val t = i.toFloat() / (envXs.size - 1) * ticks
            val semi = if (!sw.active) 0f else {
                val moved = sw.speed / 16f * t
                val dist = (sw.end - sw.start).toFloat()
                sw.start + if (abs(dist) <= moved) dist else moved * (if (dist > 0) 1 else -1)
            }
            envXs[n] = g.left + g.width() * i / (envXs.size - 1)
            envYs[n] = yOf(semi)
            n++
        }
        c.polyline(envXs, envYs, n, col, ink.dp(2.5f))
        val ls = ink.unit * 0.7f
        ink.label(c, Opl3Rules.signed(hi - 2), g.left - ink.dp(3f), g.top - ink.unit * 0.3f, ink.unit, Palette.DIM, size = ls, align = Align.RIGHT)
        ink.label(c, Opl3Rules.signed(lo + 2), g.left - ink.dp(3f), g.bottom - ink.unit * 0.7f, ink.unit, Palette.DIM, size = ls, align = Align.RIGHT)
        ink.label(c, "0.5 s", g.right, g.bottom + ink.dp(2f), ink.unit * 1.1f, Palette.FAINT, size = ls, align = Align.RIGHT)
    }

    // ---- chord shapes ---------------------------------------------------------------------------------

    /** The song's chord shapes (`Kxx`): number, name, up to four intervals (the cursor on one), and the notes from the listening C. */
    private fun drawChords(c: Surface, box: Rect, col: Int, focused: Boolean) {
        ink.round(c, box, ink.dp(4f), Palette.LANE)
        val chords = song.opl3.chords
        val small = ink.unit * 0.85f
        val titleH = ink.unit * 1.4f
        val root = ctl.defaultNote()
        ink.label(c, ink.fit("Chord shapes for Kxx (${chords.size} of ${Opl3System.MAX_CHORDS}) · notes from ${Opl3System.noteName(root)} · Hxy plays +x +y without a shape",
            box.width() - ink.unit), box.left + ink.unit * 0.5f, box.top, titleH, Palette.FAINT, size = ink.unit * 0.8f)
        listRowH = ink.unit * 1.35f
        listRect = Rect(box.left, box.top + titleH, box.right, box.bottom - ink.unit * 0.2f)
        val fit = maxOf(1, (listRect.height() / listRowH).toInt())
        val step = st.envStep
        val lines = chords.size + 1
        listScroll = listScroll.coerceIn(maxOf(0, step - fit + 1), step).coerceAtMost(maxOf(0, lines - fit))
        val cw = ink.monoWidth("0", small)
        val numX = box.left + ink.unit * 0.6f
        val nameX = numX + cw * 4f
        val ivX = nameX + ink.unit * 5f
        val ivW = cw * 4.5f
        val notesX = ivX + ivW * 4 + ink.unit * 0.5f
        for (k in 0 until minOf(fit, lines - listScroll)) {
            val i = listScroll + k
            val y = listRect.top + k * listRowH
            val on = i == step
            if (on) ink.rect(c, box.left, y, box.right, y + listRowH, if (focused) Palette.CURSOR_FILL else Palette.alpha(Palette.CURSOR_FILL, 120))
            if (i == chords.size) {
                ink.label(c, if (chords.size >= Opl3System.MAX_CHORDS) "table full" else "+ add a shape", nameX, y, listRowH, Palette.FAINT, size = small)
                if (on && focused) ink.outline(c, Rect(nameX - ink.unit * 0.2f, y + 1, box.right - ink.unit * 0.3f, y + listRowH - 1), cursorColor(), ink.dp(2f), ink.dp(2f))
                continue
            }
            val shape = chords[i]
            ink.mono(c, "K${hex2(i + 1)}", numX, y, listRowH, if (on) col else Palette.DIM, on, small)
            ink.label(c, ink.fit(shape.name.ifEmpty { "—" }, ivX - nameX - ink.unit * 0.3f, small), nameX, y, listRowH, if (on) Palette.TEXT else Palette.DIM, size = small)
            for (f in 0 until 4) {
                val x = ivX + f * ivW
                val iv = shape.intervals.getOrNull(f)
                ink.mono(c, iv?.let { Opl3Rules.signed(it) } ?: "·", x, y, listRowH, if (iv == null) Palette.FAINT else if (on) Palette.TEXT else Palette.DIM, size = small)
                if (on && focused && f == st.programField) ink.outline(c, Rect(x - cw * 0.4f, y + 1, x + ivW - cw * 0.6f, y + listRowH - 1), cursorColor(), ink.dp(2f), ink.dp(2f))
            }
            val notes = Opl3InstrumentView.shapeNotes(shape, root)
            if (box.right - notesX > ink.unit * 4f) {
                ink.label(c, ink.fit(notes, box.right - notesX - ink.unit * 0.4f, ink.unit * 0.8f), notesX, y, listRowH, Palette.FAINT, size = ink.unit * 0.8f)
            }
            shape.problem()?.let { ink.rect(c, box.right - ink.dp(5f), y + ink.dp(3f), box.right - ink.dp(2f), y + listRowH - ink.dp(3f), Palette.ERROR) }
        }
    }

    // ---- usage ----------------------------------------------------------------------------------------

    private fun usage(slot: Int): Int {
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

    private fun usageText(slot: Int): String {
        val n = usage(slot)
        return if (n == 0) "not used in the arrangement yet" else "used on $n row${if (n == 1) "" else "s"}"
    }

    // ---- touch ----------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        tabs.firstOrNull { it.first.contains(x, y) }?.let { (_, i) -> Opl3InstrumentView.showPage(ctl, i); return true }
        if (slotRect.contains(x, y)) {
            st.instrument = (((y - slotTop) / slotH).toInt() + slotScroll + 1).coerceIn(1, Opl3System.SLOTS)
            st.instField = 0
            Opl3InstrumentView.publish(ctl)
            return true
        }
        gridCells.firstOrNull { it.first.contains(x, y) }?.let { (_, rowIndex, op) ->
            st.instField = rowIndex
            st.envStep = op
            return true
        }
        rowRects.firstOrNull { it.first.contains(x, y) }?.let { (_, rowIndex) -> st.instField = rowIndex; return true }
        if (listRect.contains(x, y) && listRowH > 0f && Opl3InstrumentView.page(st) == Page.CHORDS) {
            st.instField = Opl3InstrumentView.rows(st).indexOf(Row.SHAPES)
            st.envStep = (listScroll + ((y - listRect.top) / listRowH).toInt()).coerceIn(0, Opl3InstrumentView.lastShape(song.opl3.chords))
            return true
        }
        return false
    }

    private companion object {
        const val WAVE_POINTS = 33
        val KSL_DB = listOf("off", "3dB/oct", "1.5dB/oct", "6dB/oct")

        /** Attack time to full level for rate [ar] (YMF262: 0 never, each step halves, 15 instant). */
        fun attackMs(ar: Int): Float = when (ar) { 0 -> Float.POSITIVE_INFINITY; 15 -> 0f; else -> 2826f / 2f.pow(ar - 1) }

        /** Time to fall [steps] × 3 dB at decay/release rate [rate] (0 never; rate 1 falls 96 dB in 39.3 s; each step halves). */
        fun decayMs(rate: Int, steps: Int): Float = if (rate == 0) Float.POSITIVE_INFINITY else 39280f / 2f.pow(rate - 1) * (steps * 3f / 96f)

        fun sweepMs(s: Opl3Sweep): Float = if (s.speed == 0) 0f else abs(s.end - s.start) * 16f / s.speed * 1000f / 60f

        fun timeText(ms: Float): String = when {
            ms.isInfinite() -> "never"
            ms >= 1000f -> "${NumberText.fixed(ms / 1000.0, 1)} s"
            ms >= 10f -> "${ms.toInt()} ms"
            else -> "${NumberText.fixed(ms.toDouble(), 1)} ms"
        }

        /** OPL3 waveform [wave] at phase [t] (0..1), −1..1. */
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

/** Player ticks per 60 Hz frame of an OPL3 song (its multispeed), for drawing time-based curves. */
internal object Opl3SweepTicks {
    fun perFrame(song: Song): Int = song.opl3.multispeed.coerceIn(1, 8)
}
