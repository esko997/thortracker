package org.thortracker.system.opm

import kotlin.math.abs
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.fm.OpnTiming
import org.thortracker.system.opm.OpmInstrumentView.Page
import org.thortracker.system.opm.OpmInstrumentView.Row
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * OPM instrument editor. Left: the 64 slots (sounds with noise marked "ᴺ", empty "·") with how often each is used.
 * Right: name, Listen channel, page tabs, then the page — the curated controls beside the drawn algorithm (the eight
 * YM2151 routings; operator 4 marked as noise when the sound uses the noise generator) and envelope, the Expert
 * register grid, or the noise settings with the drum sweep. The bank, tabs, grid and drawings are the shared
 * [FmInstrumentPainter]'s.
 */
internal class OpmInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = OpmInstrumentView.sound(s, st)
        val page = OpmInstrumentView.page(st)
        val listen = OpmInstrumentView.listenChannel(st, ins)
        val col = ink.palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when {
            ins == null -> "empty slot"
            ins.noise -> "FM + noise (FM 8) · " + OpmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name
            else -> "FM · " + OpmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "OPM · $kind$locked  ${usageText(st.instrument)}")
        val row = OpmInstrumentView.row(st)
        val allRows = OpmInstrumentView.rows(st)
        rowRects.clear()
        gridCells.clear()

        val left = drawBank(c, area, top, OpmSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.opm.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + if (it.noise) " ᴺ" else "" }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val side = fullW >= ink.unit * 50f
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT }
        val gridRows = rows.count { it in OpmInstrumentView.GRID }
        val plain = rows.size - gridRows
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side) 0f else ink.unit * 9f
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (plain + gridRows + (if (gridRows > 0) 1 else 0) + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = OpmInstrumentView.PAGES.map { it.label }
        var gridHeaderDrawn = false
        for (r in rows) {
            val sel = r == row
            if (r in OpmInstrumentView.GRID && ins != null) {
                if (!gridHeaderDrawn) { y = drawGridHeader(c, rx, vx, rw, y, rowH, col, { true }, { ins.isCarrier(it) }); gridHeaderDrawn = true }
                drawGridRow(c, r.label, allRows.indexOf(r), rx, vx, rw, y, rowH, sel, OpmInstrumentView.operator(st), { true }) { op -> gridText(ins, op, r) }
                rowRects += Rect(rx, y, rx + rw, y + rowH) to allRows.indexOf(r)
                y += rowH
                continue
            }
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, ink.palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) ink.palette.TEXT else ink.palette.DIM)
                    val name = s.system.channels.getOrNull(listen)?.name.orEmpty()
                    ink.label(c, name, vx, y, h, col, true)
                    listenNote(ins)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, ink.palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        when (page) {
            Page.SOUND -> if (box.width() > box.height() * 1.6f) {
                val mid = box.left + box.width() * 0.5f
                algorithm(c, Rect(box.left, box.top, mid - ink.unit * 0.25f, box.bottom), ins, col, -1)
                drawEnvelope(c, Rect(mid + ink.unit * 0.25f, box.top, box.right, box.bottom), envelope(ins), col)
            } else {
                val mid = box.top + box.height() * 0.5f
                algorithm(c, Rect(box.left, box.top, box.right, mid - ink.unit * 0.25f), ins, col, -1)
                drawEnvelope(c, Rect(box.left, mid + ink.unit * 0.25f, box.right, box.bottom), envelope(ins), col)
            }
            Page.EXPERT -> algorithm(c, box, ins, col, OpmInstrumentView.operator(st))
            Page.DRUM -> sweep(c, box, ins.sweep, col)
        }
    }

    /** Why Listen plays the sound where it does: the noise generator exists on FM 8 only. */
    private fun listenNote(ins: OpmInstrument?): String? =
        if (ins?.noise == true) "noise: plays on FM 8 only (the chip's one noise generator)" else null

    // ---- values ---------------------------------------------------------------------------------------

    private fun presetText(ins: OpmInstrument): String = OpmPresets.ALL.firstOrNull { it.instrument == ins }?.let { p ->
        p.name + when {
            p.category == OpmPresets.Category.NOISE -> "   noise, FM 8 only" + (p.note?.let { ", voiced at ${OpmSystem.noteName(it)}" } ?: "")
            p.note != null -> "   drum, voiced at ${OpmSystem.noteName(p.note)}"
            else -> ""
        }
    } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"

    private fun value(ins: OpmInstrument, r: Row): String {
        val k = OpmCurated.controls(ins)
        val opm = song.opm
        fun approx(exact: Boolean) = if (exact) "" else "≈ "
        val slDb = OpnTiming.slDb(15 - k.sustain)
        return when (r) {
            Row.PRESET, Row.DRUM_PRESET -> presetText(ins)
            Row.ALGORITHM -> OpmCurated.ALGORITHMS[k.algorithm].let { "${it.number} ${it.name}   ${it.diagram}" }
            Row.RATIO -> approx(k.ratioExact) + OpmCurated.RATIOS[k.ratio].name + if (k.ratioExact) "" else "   (now " +
                ins.operators.joinToString(":") { OpmCurated.ratioText(it).drop(1) } + ")"
            Row.BRIGHTNESS -> if (k.brightness < 0) "—   additive: no modulators" else "${k.brightness}   ${bar(k.brightness, 127)}"
            Row.LEVEL -> "${k.level}   ${bar(k.level, 127)}"
            Row.FEEDBACK -> "${k.feedback}   ${if (k.feedback == 0) "pure" else bar(k.feedback, 7)}"
            Row.ATTACK -> approx(k.envelopeExact) + "${k.attack}   ${timeText(TIMING.attackMs(k.attack))} to full"
            Row.DECAY -> approx(k.envelopeExact) + "${k.decay}   ${timeText(TIMING.fallMs(2 * k.decay, slDb))} to sustain"
            Row.SUSTAIN -> approx(k.envelopeExact) + when (k.sustain) {
                15 -> "15   held at full level"
                0 -> "0   percussive: decays to silence"
                else -> "${k.sustain}   at −${slDb.toInt()} dB"
            }
            Row.SUSTAIN_RATE -> approx(k.envelopeExact) + if (k.sustainRate == 0) "0   held while the key is down" else "${k.sustainRate}   keeps fading: −96 dB in ${timeText(TIMING.fallMs(2 * k.sustainRate, 96f))}"
            Row.RELEASE -> approx(k.envelopeExact) + "${k.release}   ${timeText(TIMING.fallMs(4 * k.release + 2, 96f))} after OFF"
            Row.TONE_DECAY -> if (k.toneDecay < 0) "—   additive: no modulators" else approx(k.envelopeExact) + "${k.toneDecay}   brightness fades in ${timeText(TIMING.fallMs(2 * k.toneDecay, 96f))}"
            Row.KEY_SCALE -> approx(k.keyScaleExact) + OpmCurated.KEY_SCALES[k.keyScale.coerceIn(0, 3)]
            Row.VIBRATO -> if (k.vibrato == 0) "Off" else "${OpmCurated.PMS_TEXT[k.vibrato]} at PMD 127" +
                if (opm.pmd == 0) "   (song PMD 0: silent)" else "   (song PMD ${opm.pmd})"
            Row.TREMOLO -> approx(k.tremoloExact) + if (k.tremolo == 0) "Off" else "${OpmCurated.AMS_TEXT[k.tremolo]} at AMD 127" +
                if (opm.amd == 0) "   (song AMD 0: silent)" else "   (song AMD ${opm.amd})"
            Row.RAW_ALGORITHM -> "${ins.algorithm}   ${OpmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].diagram}"
            Row.RAW_FEEDBACK -> "${ins.feedback}   operator 1"
            Row.PMS -> if (ins.pms == 0) "0   off" else "${ins.pms}   ${OpmCurated.PMS_TEXT[ins.pms.coerceIn(0, 7)]} vibrato at PMD 127"
            Row.AMS -> if (ins.ams == 0) "0   off" else "${ins.ams}   ${OpmCurated.AMS_TEXT[ins.ams.coerceIn(0, 3)]} tremolo at AMD 127 on AM operators"
            Row.NOISE -> if (ins.noise) "On   operator 4 plays noise · FM 8 only" else "Off   operator 4 is a sine"
            Row.NOISE_FREQ -> if (!ins.noise) "—   noise off" else "${ins.noiseFrequency}   ${OpmCurated.noiseText(ins.noiseFrequency)}"
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun sweepValue(sw: OpmSweep, r: Row): String = when (r) {
        Row.SWEEP_START -> if (!sw.active) "${signed(sw.start)}   (sweep off: set a speed)" else "${signed(sw.start)} semitones from the note"
        Row.SWEEP_END -> "${signed(sw.end)} semitones"
        else -> if (!sw.active) "0   off" else "${sw.speed}   ${NumberText.fixed(sw.speed / 16.0, 2)} semitones per tick · ${timeText(sweepMs(sw))}"
    }

    private fun dim(ins: OpmInstrument, r: Row): Boolean = when (r) {
        Row.BRIGHTNESS, Row.TONE_DECAY -> OpmCurated.controls(ins).brightness < 0
        Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> !ins.sweep.active
        Row.VIBRATO, Row.PMS -> song.opm.pmd == 0
        Row.TREMOLO, Row.AMS -> song.opm.amd == 0
        Row.NOISE_FREQ -> !ins.noise
        else -> false
    }

    /** One register field of operator [op] as the Expert grid shows it. */
    private fun gridText(ins: OpmInstrument, op: Int, r: Row): String {
        val o = ins.operators[op]
        return when (r) {
            Row.OP_DT1 -> "${o.dt1} ${OpmCurated.detuneText(o.dt1)}"
            Row.OP_MUL -> if (op == 3 && ins.noise) "${NumberText.hex(o.mul)} noise" else "${NumberText.hex(o.mul)} ×${OpmCurated.multText(o.mul)}"
            Row.OP_TL -> "${hex2(o.tl)} ${NumberText.fixed(o.tl * 0.75, 1)}dB"
            Row.OP_KS -> o.ks.toString()
            Row.OP_AR -> hex2(o.ar)
            Row.OP_AM -> if (o.am) "■ on" else "·"
            Row.OP_D1R -> hex2(o.d1r)
            Row.OP_DT2 -> "${o.dt2} ${OpmCurated.DT2_TEXT[o.dt2.coerceIn(0, 3)]}"
            Row.OP_D2R -> hex2(o.d2r)
            Row.OP_D1L -> "${NumberText.hex(o.d1l)} −${OpnTiming.slDb(o.d1l).toInt()}dB"
            Row.OP_RR -> NumberText.hex(o.rr)
            else -> ""
        }
    }

    // ---- drawings -------------------------------------------------------------------------------------

    /** The algorithm's routing: operators placed by depth (modulators left of what they modulate), carriers on the right. */
    private fun routing(ins: OpmInstrument): FmRouting {
        val alg = ins.algorithm.coerceIn(0, 7)
        return FmRouting.opn(alg, OpmCurated.ALGORITHMS[alg].edges) { ins.isCarrier(it) }
    }

    private fun algorithm(c: Surface, r: Rect, ins: OpmInstrument, col: Int, focus: Int) {
        val a = OpmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)]
        val noise = if (ins.noise) " · op 4 noise ${OpmCurated.noiseText(ins.noiseFrequency)}" else ""
        drawAlgorithm(c, r, "Algorithm ${a.number} ${a.name} ${a.diagram} · fb ${ins.feedback}$noise", routing(ins), ins.feedback, col, focus,
            { op -> if (op == 3 && ins.noise) "noise" else OpmCurated.ratioText(ins.operators[op]) }, { 0 })
    }

    /** The first carrier's envelope in dB: attack, decay to D1L, held while falling at D2R, release. */
    private fun envelope(ins: OpmInstrument): FmEnvelopeSketch {
        val k = OpmCurated.controls(ins)
        val car = ins.operators[(0..3).first { ins.isCarrier(it) }]
        val sustainDb = OpnTiming.slDb(car.d1l)
        val holdFall = if (car.d2r == 0) 0f else 96f * HOLD_MS / TIMING.fallMs(2 * car.d2r, 96f)
        val title = when {
            car.d2r > 0 -> "Envelope · decays to −${sustainDb.toInt()} dB, keeps fading while held"
            car.d1l == 0 -> "Envelope · held at full level until OFF"
            else -> "Envelope · held at −${sustainDb.toInt()} dB until OFF"
        }
        return FmEnvelopeSketch(
            title = title,
            attackMs = TIMING.attackMs(car.ar),
            decayMs = TIMING.fallMs(2 * car.d1r, sustainDb),
            sustainDb = sustainDb,
            holdMs = HOLD_MS,
            releaseMs = TIMING.fallMs(4 * car.rr + 2, 96f),
            toneDecayMs = if (k.toneDecay >= 0) TIMING.fallMs(2 * k.toneDecay, 96f) else null,
            floorDb = 96f,
            heldEndDb = (sustainDb + holdFall).coerceAtMost(96f),
        )
    }

    private fun sweep(c: Surface, r: Rect, sw: OpmSweep, col: Int) {
        val root = ctl.defaultNote()
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${OpmSystem.noteName((root + sw.start).coerceIn(OpmRules.NOTES))} to ${OpmSystem.noteName((root + sw.end).coerceIn(OpmRules.NOTES))} " +
                "in ${timeText(sweepMs(sw))} (note ${OpmSystem.noteName(root)})"
        drawSweep(c, r, title, sw.start, sw.end, sw.speed, sw.active, (tickHz() / 2).toInt(), col)
    }

    private fun tickHz(): Double = OpmSystem.ticksPerSecond(song)

    private fun sweepMs(s: OpmSweep): Float = if (s.speed == 0) 0f else (abs(s.end - s.start) * 16f / s.speed * 1000f / tickHz()).toFloat()

    // ---- touch ----------------------------------------------------------------------------------------

    override fun down(x: Float, y: Float): Boolean =
        downShared(x, y, OpmSystem.SLOTS, { OpmInstrumentView.showPage(ctl, it) }, { OpmInstrumentView.publish(ctl) })

    private companion object {
        /** How long the envelope sketch holds the key. */
        const val HOLD_MS = 500f

        /** The envelope generator's clock: the YM2151's 4 MHz / 64 = 62500 Hz sample rate, stepped every third sample. */
        const val EG_HZ = 62500f / 3f

        val TIMING = OpnTiming(EG_HZ)
    }
}
