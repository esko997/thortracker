package org.thortracker.system.opl3

import kotlin.math.abs
import kotlin.math.pow
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.opl3.Opl3InstrumentView.Page
import org.thortracker.system.opl3.Opl3InstrumentView.Row
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * OPL3 instrument editor. Left: the 64 slots of the bank with how often each is used. Right: name, the channel Listen
 * plays on, page tabs, then the page: the curated controls beside the drawn algorithm (operators, routing, feedback)
 * and envelope; the Expert register grid (one column per operator, carriers marked); the drum sweep with its pitch
 * curve; or the chord-shape table with the notes each shape sounds. On narrow displays the drawings go below the rows.
 * The bank, tabs, grid and drawings are the shared [FmInstrumentPainter]'s.
 */
internal class Opl3InstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private var listRect = Rect()
    private var listRowH = 0f
    private var listScroll = 0

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
        val left = drawBank(c, area, top, Opl3System.SLOTS, col, row == Row.SLOT) { i ->
            s.opl3.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + if (it.fourOp) " ⁴" else "" }
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
        val tabLabels = Page.entries.map { it.label }
        if (ins == null && page != Page.CHORDS) {
            for (r in rows) {
                if (r == Row.PAGE) { drawTabs(c, Rect(rx, y, rx + rw, y + ink.unit * 1.9f), col, tabLabels, st.envelope); y += ink.unit * 1.9f; continue }
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
                if (!gridHeaderDrawn) {
                    y = drawGridHeader(c, rx, vx, rw, y, rowH, col, { it < ins.operatorCount }, { ins.isCarrier(it) })
                    gridHeaderDrawn = true
                }
                drawGridRow(c, r.label, Opl3InstrumentView.rows(st).indexOf(r), rx, vx, rw, y, rowH, sel, Opl3InstrumentView.operator(st),
                    { it < ins.operatorCount }) { op -> gridText(ins.operators[op], r) }
                rowRects += Rect(rx, y, rx + rw, y + rowH) to rows.indexOf(r) + 1
                y += rowH
                continue
            }
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when (r) {
                Row.NAME -> drawName(c, ins?.name.orEmpty(), rx, vx, y, h, sel)
                Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val ch = Opl3InstrumentView.listenChannel(st, ins)
                    val text = s.system.channels.getOrNull(ch)?.name.orEmpty() + if (ch != st.listenChannel) " (4-op: FM 1-6 only)" else ""
                    ink.label(c, ink.fit(text, rightEdge - vx), vx, y, h, col, true)
                }
                Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope)
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
                    algorithm(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), ins, col, -1)
                    drawEnvelope(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), envelope(ins), col)
                } else {
                    val mid = box.top + box.height() * 0.5f
                    algorithm(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), ins, col, -1)
                    drawEnvelope(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), envelope(ins), col)
                }
            }
            Page.EXPERT -> if (ins != null) algorithm(c, box, ins, col, Opl3InstrumentView.operator(st))
            Page.DRUM -> if (ins != null) sweep(c, box, ins, col)
            Page.CHORDS -> drawChords(c, box, col, row == Row.SHAPES)
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

    /** One register field of operator [o] as the Expert grid shows it. */
    private fun gridText(o: Opl3Operator, r: Row): String = when (r) {
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

    // ---- drawings -------------------------------------------------------------------------------------

    /** Operator chains of each routing, top to bottom; each chain's last operator is a carrier into the output. */
    private fun chains(ins: Opl3Instrument): List<List<Int>> = if (!ins.fourOp) {
        if (ins.algorithm == 1) listOf(listOf(0), listOf(1)) else listOf(listOf(0, 1))
    } else when (ins.algorithm) {
        1 -> listOf(listOf(0, 1), listOf(2, 3))
        2 -> listOf(listOf(0), listOf(1, 2, 3))
        3 -> listOf(listOf(0), listOf(1, 2), listOf(3))
        else -> listOf(listOf(0, 1, 2, 3))
    }

    /** The routing with each played operator's ratio and waveform. [focus]: the Expert grid's operator. */
    private fun algorithm(c: Surface, r: Rect, ins: Opl3Instrument, col: Int, focus: Int) {
        val a = Opl3Curated.ALGORITHMS.firstOrNull { it.fourOp == ins.fourOp && it.algorithm == ins.algorithm }
        drawAlgorithm(c, r, "${a?.name ?: "Algorithm"} ${a?.diagram ?: ""} · fb ${ins.feedback}", FmRouting.chains(chains(ins)), ins.feedback, col, focus,
            { "×${Opl3Curated.multText(ins.operators[it].mult)}" }, { ins.operators[it].wave })
    }

    /** The carriers' envelope as the curated controls read it; the modulators' tone decay beneath. */
    private fun envelope(ins: Opl3Instrument): FmEnvelopeSketch {
        val k = Opl3Curated.controls(ins)
        val sustainDb = if (k.sustain == 0) 45f else (15 - k.sustain) * 3f
        return FmEnvelopeSketch(
            title = if (k.sustain == 0) "Envelope · percussive: fades while held" else "Envelope · held at −${sustainDb.toInt()} dB until OFF",
            attackMs = attackMs(k.attack),
            decayMs = decayMs(k.decay, (sustainDb / 3f).toInt()),
            sustainDb = sustainDb,
            holdMs = if (k.sustain == 0) 0f else 500f,
            releaseMs = decayMs(k.release, 16),
            toneDecayMs = if (k.toneDecay >= 0) decayMs(k.toneDecay, 16) else null,
        )
    }

    /** The pitch over the first half second: from the start offset toward the end at the sweep's speed, then held. */
    private fun sweep(c: Surface, r: Rect, ins: Opl3Instrument, col: Int) {
        val sw = ins.sweep
        val root = ctl.defaultNote()
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${Opl3System.noteName((root + sw.start).coerceIn(Opl3Rules.NOTES))} to ${Opl3System.noteName((root + sw.end).coerceIn(Opl3Rules.NOTES))} " +
                "in ${timeText(sweepMs(sw))} (note ${Opl3System.noteName(root)})"
        drawSweep(c, r, title, sw.start, sw.end, sw.speed, sw.active, 30 * Opl3SweepTicks.perFrame(song), col)
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


    // ---- touch ----------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean {
        if (downShared(x, y, Opl3System.SLOTS, { Opl3InstrumentView.showPage(ctl, it) }, { Opl3InstrumentView.publish(ctl) })) return true
        if (listRect.contains(x, y) && listRowH > 0f && Opl3InstrumentView.page(st) == Page.CHORDS) {
            st.instField = Opl3InstrumentView.rows(st).indexOf(Row.SHAPES)
            st.envStep = (listScroll + ((y - listRect.top) / listRowH).toInt()).coerceIn(0, Opl3InstrumentView.lastShape(song.opl3.chords))
            return true
        }
        return false
    }

    private companion object {
        val KSL_DB = listOf("off", "3dB/oct", "1.5dB/oct", "6dB/oct")

        /** Attack time to full level for rate [ar] (YMF262: 0 never, each step halves, 15 instant). */
        fun attackMs(ar: Int): Float = when (ar) { 0 -> Float.POSITIVE_INFINITY; 15 -> 0f; else -> 2826f / 2f.pow(ar - 1) }

        /** Time to fall [steps] × 3 dB at decay/release rate [rate] (0 never; rate 1 falls 96 dB in 39.3 s; each step halves). */
        fun decayMs(rate: Int, steps: Int): Float = if (rate == 0) Float.POSITIVE_INFINITY else 39280f / 2f.pow(rate - 1) * (steps * 3f / 96f)

        fun sweepMs(s: Opl3Sweep): Float = if (s.speed == 0) 0f else abs(s.end - s.start) * 16f / s.speed * 1000f / 60f
    }
}

/** Player ticks per 60 Hz frame of an OPL3 song (its multispeed), for drawing time-based curves. */
internal object Opl3SweepTicks {
    fun perFrame(song: Song): Int = song.opl3.multispeed.coerceIn(1, 8)
}
