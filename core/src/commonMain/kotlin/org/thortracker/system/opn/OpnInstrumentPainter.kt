package org.thortracker.system.opn

import kotlin.math.abs
import kotlin.math.roundToInt
import org.thortracker.backend.OpnPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.fm.OpnTiming
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesS5b
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.system.nes.NesInstrumentView
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.opn.OpnInstrumentView.Page
import org.thortracker.system.opn.OpnInstrumentView.Row
import org.thortracker.system.ym2612.YmCurated
import org.thortracker.system.ym2612.YmRules
import org.thortracker.system.ym2612.YmSweep
import org.thortracker.ui.Align
import org.thortracker.ui.IdentityCache
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * OPN-family instrument editor. Left: the 64 slots (FM voices, SSG sounds "ˢ", rhythm sounds "ᴿ", ADPCM sounds "ᴬ",
 * empty "·") with how often each is used. Right: name, Listen channel, type, page tabs, then the page — an FM voice's
 * curated controls beside the drawn algorithm and envelope, the Expert register grid, the Ch3 operator offsets, the drum
 * sweep; an SSG envelope's steps (the NES editor's plot) or the hardware envelope's shape; a rhythm sound's drums and the
 * ROM's state; an ADPCM sound's sample, its waveform and the song's samples in the 256 KiB memory. The bank, tabs, grid
 * and drawings are the shared [FmInstrumentPainter]'s.
 */
internal class OpnInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plot = EnvelopePlot(ink)

    /** Waveform outlines (min, max per bin) of drawn samples, by identity (samples are immutable). */
    private val outlines = IdentityCache<OpnaSample, ShortArray>(16)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val chip = s.opn.chip
        val ins = OpnInstrumentView.sound(s, st)
        val page = OpnInstrumentView.page(s, st)
        val listen = OpnInstrumentView.listenChannel(s, st, ins)
        val col = Palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when (ins) {
            is OpnFmSound -> "FM · " + YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name
            is OpnSsgSound -> "SSG sound"
            is OpnRhythmSound -> "rhythm sound"
            is OpnAdpcmSound -> "ADPCM sound"
            null -> "empty slot"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "${chip.label} · $kind$locked  ${usageText(st.instrument)}")
        val row = OpnInstrumentView.row(s, st)
        val allRows = OpnInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()

        val left = drawBank(c, area, top, OpnSound.SLOTS, col, row == Row.SLOT) { i ->
            s.opn.instrument(i)?.let { it.name.ifEmpty { "(unnamed)" } + mark(it) }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val envelopePage = page.envelope != null
        val side = fullW >= ink.unit * 50f && !envelopePage
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS }
        val gridRows = rows.count { isGrid(it) }
        val plain = rows.size - gridRows
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side) 0f else ink.unit * (if (envelopePage) 8f else 9f)
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (plain + gridRows + (if (gridRows > 0) 1 else 0) + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = OpnInstrumentView.pages(s, st).map { p -> tabLabel(ins, p) }
        var gridHeaderDrawn = false
        for (r in rows) {
            val sel = r == row
            if (isGrid(r) && ins is OpnFmSound) {
                if (!gridHeaderDrawn) { y = drawGridHeader(c, rx, vx, rw, y, rowH, col, { true }, { ins.isCarrier(it) }); gridHeaderDrawn = true }
                drawGridRow(c, r.label, allRows.indexOf(r), rx, vx, rw, y, rowH, sel, OpnInstrumentView.operator(st), { true }) { op -> gridText(ins, op, r) }
                rowRects += Rect(rx, y, rx + rw, y + rowH) to allRows.indexOf(r)
                y += rowH
                continue
            }
            val h = if (r == Row.PAGE) ink.unit * 1.9f else rowH
            if (sel) ink.rect(c, rx - ink.unit * 0.4f, y, rx + rw + ink.unit * 0.4f, y + h, Palette.CURSOR_FILL)
            when {
                r == Row.NAME -> if (ins != null) drawName(c, ins.name, rx, vx, y, h, sel)
                    else setting(c, "Empty slot", "${ctl.k(Action.EDIT)} on Slot or any setting makes a sound here", rx, vx, y, h, sel, true)
                r == Row.LISTEN -> {
                    ink.label(c, "Listen on", rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
                    val name = s.system.channels.getOrNull(listen)?.name.orEmpty()
                    ink.label(c, name, vx, y, h, col, true)
                    listenNote(ins, page)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, Palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                r == Row.TYPE -> setting(c, "Type", typeText(chip, ins), rx, vx, y, h, sel, ins == null)
                r == Row.SAMPLE_NAME && ins is OpnAdpcmSound -> drawSampleName(c, rx, vx, y, h, sel)
                r == Row.FM3_MODE -> setting(c, r.label, fm3Text(), rx, vx, y, h, sel, s.opn.fm3Mode == OpnFm3Mode.NORMAL)
                ins == null -> setting(c, r.label, "—", rx, vx, y, h, sel, true)
                else -> setting(c, r.label, value(ins, r), rx, vx, y, h, sel, dim(ins, r))
            }
            if (r != Row.PAGE) rowRects += Rect(rx, y, rx + rw, y + h) to allRows.indexOf(r)
            if (sel && r != Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.4f, y + 1, rx + rw + ink.unit * 0.4f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            if (sel && r == Row.PAGE) ink.outline(c, Rect(rx - ink.unit * 0.2f, y + 1, rx + rw + ink.unit * 0.2f, y + h - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
            y += h
        }

        if (envelopePage) {
            val k = page.envelope!!
            val ssg = ins as? OpnSsgSound ?: OpnEdits.defaultSsg(chip, st.instrument)
            val small = ink.unit * 0.8f
            ink.label(c, ink.fit(envelopeSummary(k), rw, small), rx, y, rowH * 0.85f, Palette.FAINT, size = small)
            y += rowH * 0.85f
            val plotTop = y + ink.unit * 0.3f
            if (area.bottom - plotTop > ink.unit * 4f) {
                val env = OpnEdits.envelope(ssg, k)
                val text = { value: Int -> NesInstrumentView.valueText(k, value, null, OpnEdits.SSG_CHIP) }
                val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
                plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, k, env, st.envStep, text,
                    ins == null, col, row == Row.STEPS, cursorColor(), ctl.editHeld, k.range(OpnEdits.SSG_CHIP))
            }
            return
        }
        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        when (page) {
            Page.SOUND -> if (ins is OpnFmSound) {
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
            Page.EXPERT -> if (ins is OpnFmSound) algorithm(c, box, ins, col, OpnInstrumentView.operator(st))
            Page.CH3 -> if (ins is OpnFmSound) drawCh3(c, box, ins, col)
            Page.HARDWARE -> if (ins is OpnSsgSound) drawShape(c, box, ins.envelope, col)
            Page.RHYTHM -> if (ins is OpnRhythmSound) drawRhythm(c, box, ins, col)
            Page.SAMPLE -> if (ins is OpnAdpcmSound) drawSample(c, box, ins, col)
            else -> sweepOf(ins)?.let { sweep(c, box, it, col) }
        }
    }

    private fun isGrid(r: Row) = r in OpnInstrumentView.GRID_OPNA || r in OpnInstrumentView.CH3_GRID

    private fun mark(ins: OpnInstrument): String = when (ins) {
        is OpnFmSound -> ""
        is OpnSsgSound -> " ˢ"
        is OpnRhythmSound -> " ᴿ"
        is OpnAdpcmSound -> " ᴬ"
    }

    private fun sweepOf(ins: OpnInstrument): YmSweep? = when (ins) {
        is OpnFmSound -> ins.sweep
        is OpnSsgSound -> ins.sweep
        is OpnAdpcmSound -> ins.sweep
        is OpnRhythmSound -> null
    }

    private fun typeText(chip: OpnChip, ins: OpnInstrument?): String = when (ins) {
        null -> "empty   ${ctl.k(Action.EDIT)} makes ${OpnInstrumentView.partText(OpnInstrumentView.part(song, st))}"
        else -> {
            val part = ins.part
            val name = when (part) {
                OpnPart.FM -> "FM voice"
                OpnPart.SSG -> "SSG sound"
                OpnPart.RHYTHM -> "Rhythm sound"
                OpnPart.ADPCM -> "ADPCM sound"
            }
            if (part in chip.parts) "$name   plays on ${OpnInstrumentView.partChannels(chip, part)}" else "$name   the ${chip.label} has none: change it"
        }
    }

    /** A page tab: SSG envelopes carry their length; the hardware envelope and Ch3 a mark when in use. */
    private fun tabLabel(ins: OpnInstrument?, p: Page): String {
        val k = p.envelope
        if (k != null) {
            val n = (ins as? OpnSsgSound)?.let { OpnEdits.envelope(it, k).values.size } ?: 0
            return if (n == 0) p.label else "${p.label} ($n)"
        }
        return when {
            p == Page.HARDWARE && ins is OpnSsgSound && ins.envelope.shape != S5bShape.OFF -> "${p.label} ✓"
            p == Page.CH3 && ins is OpnFmSound && ins.ch3.any { it != 0 } -> "${p.label} ✓"
            else -> p.label
        }
    }

    /** What the Listen channel does with the sound, where it is not plain. */
    private fun listenNote(ins: OpnInstrument?, page: Page): String? = when {
        page == Page.CH3 -> if (song.opn.fm3Mode == OpnFm3Mode.NORMAL) "offsets silent: FM 3 is in normal mode" else "operators at their offsets"
        ins is OpnRhythmSound -> "fixed-pitch ROM drum: a note only triggers it"
        ins is OpnAdpcmSound -> "plays at its root ${song.system.noteName(ins.root)} on octave 4"
        ins is OpnSsgSound && ins.envelope.shape != S5bShape.OFF -> "hardware envelope: shared by SSG 1-3"
        else -> null
    }

    private fun envelopeSummary(k: NesEnvelopeKind): String = when (k) {
        NesEnvelopeKind.VOLUME -> "Level 0-15 per tick (empty = 15). After OFF the release step on plays out"
        NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick"
        NesEnvelopeKind.PITCH -> "Sixteenths of a semitone per tick (relative: added up each tick)"
        NesEnvelopeKind.MIXER -> "Per tick: tone+noise, noise, tone, or off (a constant level). Empty: tone"
        else -> "Noise period 0-31 per tick (higher is lower; empty: untouched). One generator for SSG 1-3"
    }

    private fun fm3Text(): String = "${song.opn.fm3Mode.label}   the song's (Song view too)"

    // ---- values ---------------------------------------------------------------------------------------

    private fun value(ins: OpnInstrument, r: Row): String = when (ins) {
        is OpnFmSound -> fmValue(ins, r)
        is OpnSsgSound -> ssgValue(ins, r)
        is OpnRhythmSound -> rhythmValue(ins, r)
        is OpnAdpcmSound -> adpcmValue(ins, r)
    }

    private fun custom(): String = "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"

    private fun fmValue(ins: OpnFmSound, r: Row): String {
        val ym = ins.asYm()
        val k = YmCurated.controls(ym)
        val lfoOff = song.opn.lfo and 8 == 0
        fun approx(exact: Boolean) = if (exact) "" else "≈ "
        val slDb = OpnTiming.slDb(15 - k.sustain)
        return when (r) {
            Row.PRESET -> OpnInstrumentView.fmPreset(song.opn.chip, ins)?.let { p ->
                p.name + (p.note?.let { "   drum, voiced at ${song.system.noteName(it)}" } ?: "")
            } ?: custom()
            Row.DRUM_PRESET -> OpnInstrumentView.fmPreset(song.opn.chip, ins, drums = true)?.let { p ->
                p.name + (p.note?.let { "   voiced at ${song.system.noteName(it)}" } ?: "")
            } ?: "none   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks an FM drum"
            Row.ALGORITHM -> YmCurated.ALGORITHMS[k.algorithm].let { "${it.number} ${it.name}   ${it.diagram}" }
            Row.RATIO -> approx(k.ratioExact) + YmCurated.RATIOS[k.ratio].name + if (k.ratioExact) "" else "   (now " +
                ins.operators.joinToString(":") { YmCurated.multText(it.mul) + if (YmCurated.detuneSteps(it.dt) != 0) "(${YmCurated.detuneText(it.dt)})" else "" } + ")"
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
            Row.SUSTAIN_RATE -> approx(k.envelopeExact) + if (k.sustainRate == 0) "0   held while the key is down"
                else "${k.sustainRate}   keeps fading: −96 dB in ${timeText(TIMING.fallMs(2 * k.sustainRate, 96f))}"
            Row.RELEASE -> approx(k.envelopeExact) + "${k.release}   ${timeText(TIMING.fallMs(4 * k.release + 2, 96f))} after OFF"
            Row.TONE_DECAY -> if (k.toneDecay < 0) "—   additive: no modulators"
                else approx(k.envelopeExact) + "${k.toneDecay}   brightness fades in ${timeText(TIMING.fallMs(2 * k.toneDecay, 96f))}"
            Row.KEY_SCALE -> approx(k.keyScaleExact) + YmCurated.KEY_SCALES[k.keyScale.coerceIn(0, 3)]
            Row.VIBRATO -> YmCurated.FMS_TEXT[k.vibrato] + if (k.vibrato > 0 && lfoOff) "   (song LFO off)" else ""
            Row.TREMOLO -> approx(k.tremoloExact) + YmCurated.AMS_TEXT[k.tremolo] + if (k.tremolo > 0 && lfoOff) "   (song LFO off)" else ""
            Row.SSG -> approx(k.ssgExact) + YmCurated.SSG_PRESETS[k.ssg].let { p ->
                p.name + listOfNotNull(
                    if (p.modulators != 0) "mod ${ssgShape(p.modulators)}" else null,
                    if (p.carriers != 0) "car ${ssgShape(p.carriers)}" else null,
                ).joinToString(" · ").let { if (it.isEmpty()) "" else "   $it" }
            }
            Row.RAW_ALGORITHM -> "${ins.algorithm}   ${YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].diagram}"
            Row.RAW_FEEDBACK -> "${ins.feedback}   operator 1"
            Row.AMS -> if (ins.ams == 0) "0   off" else "${ins.ams}   ${YmCurated.AMS_TEXT[ins.ams.coerceIn(0, 3)]} tremolo on AM operators"
            Row.FMS -> if (ins.fms == 0) "0   off" else "${ins.fms}   ${YmCurated.FMS_TEXT[ins.fms.coerceIn(0, 7)]} vibrato"
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun ssgValue(ins: OpnSsgSound, r: Row): String {
        val kind = OpnInstrumentView.envelopeKind(song, st)
        val env = kind?.let { OpnEdits.envelope(ins, it) } ?: NesEnvelope()
        val e = ins.envelope
        return when (r) {
            Row.PRESET -> OpnPresets.SSG.firstOrNull { it == ins }?.name ?: custom()
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == NesEnvelopeKind.VOLUME -> "empty: full volume"
                kind == NesEnvelopeKind.MIXER -> "empty: tone"
                kind == NesEnvelopeKind.NOISE -> "empty: the noise period is left alone"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.ENV_RELEASE -> when {
                env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                else -> "none: OFF stops the note"
            }
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.ENV_SHAPE -> if (e.shape == S5bShape.OFF) "Off: the volume envelope sets the level" else
                "${e.shape.label} ${e.shape.picture}: ${if (e.shape.repeats) "repeats while the note holds" else "once per note"}; each note restarts it"
            Row.ENV_AUTO -> if (e.autoPitch) "Auto: the period follows the note" else "Manual: a fixed period (Jxx changes it for a row)"
            Row.ENV_OCTAVE -> if (e.autoPitch) "${e.octave}: ${NesS5b.octaveText(e.shape, e.octave)}" else "${e.octave} (auto only)"
            Row.ENV_PERIOD -> if (e.autoPitch) "${e.period} (manual only)" else "${e.period}: ${periodText(e.shape, e.period)}"
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun rhythmValue(ins: OpnRhythmSound, r: Row): String = when (r) {
        Row.PRESET -> OpnPresets.RHYTHM.firstOrNull { it == ins }?.name ?: custom()
        Row.RHYTHM_LEVEL -> "${ins.level}   ${bar(ins.level, 31)} ${if (ins.level == 31) "full" else "−${NumberText.fixed((31 - ins.level) * 1.5, 1)} dB"}; Cxx scales it"
        Row.PAN -> panValue(ins.left, ins.right)
        Row.RHYTHM_ROM -> if (Ym2608RhythmRom.installed) "installed (SHA-256 verified)   ${RhythmRomMenu.LABEL} replaces it"
            else "missing: ${ctl.k(Action.EDIT)} imports your copy (${Ym2608RhythmRom.FILE_NAME}); the drums are silent without it"
        else -> ""
    }

    private fun adpcmValue(ins: OpnAdpcmSound, r: Row): String {
        val samples = song.opn.samples
        val sample = samples.getOrNull(ins.sample)
        return when (r) {
            Row.SAMPLE -> when {
                sample != null -> {
                    val others = OpnEdits.soundsUsing(song, ins.sample).filter { it != st.instrument }
                    "${ins.sample + 1}/${samples.size} ${sample.name} · ${sample.rate} Hz · ${OpnSampleMenu.seconds(sample.seconds)}" +
                        if (others.isEmpty()) "" else " · also sound ${others.joinToString(" ") { hex2(it) }}"
                }
                samples.isEmpty() -> "none: Add sample imports or generates one"
                else -> "none (sample ${ins.sample + 1} is missing): ${ctl.keys.held(Action.EDIT, ctl.lr())} picks one of ${samples.size}"
            }
            Row.ROOT -> song.system.noteName(ins.root) + (sample?.let { "   plays at ${it.rate} Hz on this note" } ?: "")
            Row.ADPCM_LEVEL -> "${ins.level}   ${bar(ins.level, 255)}; Cxx scales it"
            Row.PAN -> panValue(ins.left, ins.right)
            Row.LOOP_SAMPLE -> if (ins.loop) "on: repeats from its start while the note holds" else "off: plays once"
            Row.TRIM_START, Row.TRIM_END -> sample?.let {
                "${OpnSampleMenu.seconds(it.seconds)} · ${OpnSampleMenu.kib(it.stored)}   ${ctl.keys.held(Action.EDIT, ctl.lr())} → cuts " +
                    "${(OpnInstrumentView.TRIM_FINE * 1000.0 / it.rate).roundToInt()} ms"
            } ?: "—"
            Row.SAMPLE_TOOLS -> if (sample == null) "—" else "normalize · gain · reverse · fade · resample · replace · delete…"
            Row.SAMPLE_ADD -> "import .wav or generate…   ${OpnSampleMenu.memoryText(song)}"
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun panValue(left: Boolean, right: Boolean): String = when {
        left && right -> "both   centre"
        left -> "left only"
        right -> "right only"
        else -> "off   silent: neither output"
    }

    private fun sweepValue(sw: YmSweep, r: Row): String = when (r) {
        Row.SWEEP_START -> if (!sw.active) "${YmRules.signed(sw.start)}   (sweep off: set a speed)" else "${YmRules.signed(sw.start)} semitones from the note"
        Row.SWEEP_END -> "${YmRules.signed(sw.end)} semitones"
        else -> if (!sw.active) "0   off" else "${sw.speed}   ${NumberText.fixed(sw.speed / 16.0, 2)} semitones per tick · ${timeText(sweepMs(sw))}"
    }

    private fun dim(ins: OpnInstrument, r: Row): Boolean = when (r) {
        Row.BRIGHTNESS, Row.TONE_DECAY -> ins is OpnFmSound && YmCurated.controls(ins.asYm()).brightness < 0
        Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepOf(ins)?.active != true
        Row.VIBRATO, Row.TREMOLO, Row.AMS, Row.FMS -> song.opn.lfo and 8 == 0
        Row.ENV_SHAPE -> ins is OpnSsgSound && ins.envelope.shape == S5bShape.OFF
        Row.ENV_OCTAVE -> ins is OpnSsgSound && !ins.envelope.autoPitch
        Row.ENV_PERIOD -> ins is OpnSsgSound && ins.envelope.autoPitch
        Row.RHYTHM_ROM -> !Ym2608RhythmRom.installed
        Row.SAMPLE, Row.ROOT, Row.TRIM_START, Row.TRIM_END, Row.SAMPLE_TOOLS -> OpnInstrumentView.sampleIndex(song, st) < 0
        else -> false
    }

    /** One register field (Expert) or pitch offset (Ch3) of operator [op] as the grid shows it. */
    private fun gridText(ins: OpnFmSound, op: Int, r: Row): String {
        if (r == Row.CH3_SEMI || r == Row.CH3_FINE) {
            val offset = ins.ch3.getOrElse(op) { 0 }
            return if (r == Row.CH3_SEMI) "${YmRules.signed(offset / 64)} st" else "${YmRules.signed(offset % 64)}/64"
        }
        val o = ins.operators[op]
        return when (r) {
            Row.OP_DT -> "${o.dt} ${YmCurated.detuneText(o.dt)}"
            Row.OP_MUL -> "${NumberText.hex(o.mul)} ×${YmCurated.multText(o.mul)}"
            Row.OP_TL -> "${hex2(o.tl)} ${NumberText.fixed(o.tl * 0.75, 1)}dB"
            Row.OP_KS -> o.ks.toString()
            Row.OP_AR -> hex2(o.ar)
            Row.OP_AM -> if (o.am) "■ on" else "·"
            Row.OP_D1R -> hex2(o.d1r)
            Row.OP_D2R -> hex2(o.d2r)
            Row.OP_SL -> "${NumberText.hex(o.sl)} −${OpnTiming.slDb(o.sl).toInt()}dB"
            Row.OP_RR -> NumberText.hex(o.rr)
            Row.OP_SSG -> if (o.ssgEg < 8) "·" else "${NumberText.hex(o.ssgEg)} ${ssgShape(o.ssgEg)}"
            else -> ""
        }
    }

    /** What a manual [period] does with [shape] at the SSG's clock: a repeating shape's rate, a one-shot's ramp time. */
    private fun periodText(shape: S5bShape, period: Int): String {
        val ramp = 256.0 * maxOf(1, period) / OpnPlayerImage.SSG_CLOCK
        return when {
            shape == S5bShape.OFF -> "${NesS5b.duration(ramp)} per ramp (no shape chosen)"
            shape.repeats -> "${NesS5b.frequency(1.0 / (ramp * if (shape.triangle) 2 else 1))} (${NesS5b.duration(ramp)} per ramp)"
            else -> "${NesS5b.duration(ramp)} ramp"
        }
    }

    // ---- drawings -------------------------------------------------------------------------------------

    private fun routing(ins: OpnFmSound): FmRouting {
        val alg = ins.algorithm.coerceIn(0, 7)
        return FmRouting.opn(alg, YmCurated.ALGORITHMS[alg].edges) { ins.isCarrier(it) }
    }

    private fun algorithm(c: Surface, r: Rect, ins: OpnFmSound, col: Int, focus: Int) {
        val a = YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)]
        drawAlgorithm(c, r, "Algorithm ${a.number} ${a.name} ${a.diagram} · fb ${ins.feedback}", routing(ins), ins.feedback, col, focus,
            { op -> ins.operators[op].let { "×${YmCurated.multText(it.mul)}" + if (YmCurated.detuneSteps(it.dt) != 0) "(${YmCurated.detuneText(it.dt)})" else "" } }, { 0 })
    }

    /** The first carrier's envelope in dB: attack, decay to SL, held while falling at D2R, release; SSG-EG when the carriers use it. */
    private fun envelope(ins: OpnFmSound): FmEnvelopeSketch {
        val k = YmCurated.controls(ins.asYm())
        val car = ins.operators[(0..3).first { ins.isCarrier(it) }]
        val sustainDb = OpnTiming.slDb(car.sl)
        val holdFall = if (car.d2r == 0) 0f else 96f * HOLD_MS / TIMING.fallMs(2 * car.d2r, 96f)
        val ssg = if (car.ssgEg >= 8) car.ssgEg else -1
        val title = when {
            ssg >= 0 -> "Envelope · SSG-EG ${ssgShape(ssg)} ${YmCurated.SSG_EG_NAMES[ssg]?.substringAfter(' ') ?: ""} until OFF"
            car.d2r > 0 -> "Envelope · decays to −${sustainDb.toInt()} dB, keeps fading while held"
            car.sl == 0 -> "Envelope · held at full level until OFF"
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
            ssgEg = ssg,
        )
    }

    private fun sweep(c: Surface, r: Rect, sw: YmSweep, col: Int) {
        val root = ctl.defaultNote()
        val notes = OpnRules.NOTES
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${song.system.noteName((root + sw.start).coerceIn(notes))} to ${song.system.noteName((root + sw.end).coerceIn(notes))} " +
                "in ${timeText(sweepMs(sw))} (note ${song.system.noteName(root)})"
        drawSweep(c, r, title, sw.start, sw.end, sw.speed, sw.active, (tickHz() / 2).toInt(), col)
    }

    /** FM 3's four operators: each one's offset as an interval and the note it sounds against the listening note; the mode. */
    private fun drawCh3(c: Surface, r: Rect, ins: OpnFmSound, col: Int) {
        val small = ink.unit * 0.8f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val x = r.left + ink.unit * 0.5f
        val w = r.width() - ink.unit
        val lineH = ink.unit * 1.2f
        var y = r.top
        val mode = song.opn.fm3Mode
        ink.label(c, ink.fit(when (mode) {
            OpnFm3Mode.NORMAL -> "FM 3 normal mode: these offsets are silent (set special or CSM mode)"
            OpnFm3Mode.SPECIAL -> "FM 3 special mode: each operator sounds the note plus its offset"
            OpnFm3Mode.CSM -> "FM 3 CSM mode: timer A at ${NumberText.fixed(OpnRules.timerAHz(song.opn.timerA), 1)} Hz keys these operators"
        }, w, small), x, y, ink.unit * 1.4f, if (mode == OpnFm3Mode.NORMAL) Palette.ERROR else Palette.DIM, size = small)
        y += ink.unit * 1.4f
        val root = ctl.defaultNote()
        for (op in 0 until 4) {
            if (y + lineH > r.bottom) break
            val offset = ins.ch3.getOrElse(op) { 0 }
            val units = root * 64 + offset
            val note = (units / 64.0).roundToInt().coerceIn(OpnRules.NOTES)
            val cents = ((units - note * 64) * 100.0 / 64).roundToInt()
            val carrier = ins.isCarrier(op)
            val on = op == OpnInstrumentView.operator(st)
            val text = "Op ${op + 1} ${if (carrier) "♪car" else "mod"}   ${NumberText.fixed(offset / 64.0, 2, plus = true)} st   " +
                song.system.noteName(note) + if (cents != 0) " ${if (cents > 0) "+" else ""}${cents}¢" else ""
            ink.label(c, ink.fit(text, w, small), x, y, lineH, if (on) col else if (carrier) Palette.TEXT else Palette.DIM, on, small)
            y += lineH
        }
        val notes = listOf("Offsets play on FM 3 only; other channels and normal mode ignore them.",
            "Against the listening note ${song.system.noteName(root)}; ${ctl.keys.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP)} moves it.")
        var ny = r.bottom - lineH * notes.size - ink.unit * 0.2f
        if (ny < y) return
        for (n in notes) {
            ink.label(c, ink.fit(n, w, small), x, ny, lineH, Palette.FAINT, size = small * 0.95f)
            ny += lineH
        }
    }

    /** The SSG hardware envelope's level over five 32-step ramps from a restart, and the sharing rule under it. */
    private fun drawShape(c: Surface, r: Rect, e: S5bEnvelope, col: Int) {
        val small = ink.unit * 0.8f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val titleH = ink.unit * 1.3f
        val title = when {
            e.shape == S5bShape.OFF -> "No hardware envelope: the volume envelope sets the level"
            e.autoPitch -> "${e.shape.label}: ${NesS5b.octaveText(e.shape, e.octave)}"
            else -> "${e.shape.label}: ${periodText(e.shape, e.period)}"
        }
        ink.label(c, ink.fit(title, r.width() - ink.unit), r.left + ink.unit * 0.5f, r.top, titleH, Palette.DIM, size = small)
        val notes = listOf(
            "One generator for SSG 1-3: the highest-numbered SSG channel sounding such a note sets its shape and period.",
            "Each note restarts it. Its level replaces the volume envelope's, which then only gates it; Hxx/Jxx override a row's note.",
        )
        val lineH = ink.unit * 1.1f
        val plotRect = Rect(r.left + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, r.bottom - lineH * notes.size - ink.unit * 0.3f)
        if (plotRect.height() > ink.unit * 1.5f && plotRect.width() > ink.unit * 4f) {
            ink.rect(c, plotRect, Palette.BG)
            val levels = shapeLevels(e.shape)
            val dx = plotRect.width() / levels.size
            val color = if (e.shape == S5bShape.OFF) Palette.FAINT else col
            var px = plotRect.left
            var py = plotRect.bottom - plotRect.height() * levels[0] / 31f
            for ((i, level) in levels.withIndex()) {
                val x = plotRect.left + dx * (i + 1)
                val ly = plotRect.bottom - plotRect.height() * level / 31f
                ink.line(c, px, py, px, ly, color, ink.dp(2f))
                ink.line(c, px, ly, x, ly, color, ink.dp(2f))
                px = x
                py = ly
            }
        }
        var ny = r.bottom - lineH * notes.size - ink.unit * 0.2f
        for (n in notes) {
            ink.label(c, ink.fit(n, r.width() - ink.unit), r.left + ink.unit * 0.5f, ny, lineH, Palette.FAINT, size = small * 0.95f)
            ny += lineH
        }
    }

    /** The SSG envelope's levels (0..31) step by step from a restart: 160 steps (five ramps); off: full level. */
    private fun shapeLevels(shape: S5bShape): IntArray {
        if (shape == S5bShape.OFF) return IntArray(160) { 31 }
        val reg = shape.register
        val cont = reg and 8 != 0
        val attack = reg and 4 != 0
        val alternate = reg and 2 != 0
        val hold = reg and 1 != 0
        var up = attack
        var level = if (attack) 0 else 31
        var held = false
        return IntArray(160) { i ->
            if (i > 0 && !held) {
                val next = level + if (up) 1 else -1
                if (next in 0..31) level = next
                else when {
                    !cont -> { level = 0; held = true }
                    hold -> { level = if (attack != alternate) 31 else 0; held = true }
                    alternate -> up = !up
                    else -> level = if (up) 0 else 31
                }
            }
            level
        }
    }

    /** The six drum channels with the level and pan this sound gives each, the ROM's state and the song's rhythm level. */
    private fun drawRhythm(c: Surface, r: Rect, ins: OpnRhythmSound, col: Int) {
        val small = ink.unit * 0.8f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val lineH = ink.unit * 1.15f
        var y = r.top
        val x = r.left + ink.unit * 0.5f
        val w = r.width() - ink.unit
        val chip = song.opn.chip
        ink.label(c, ink.fit("Six fixed-pitch drums from the YM2608's rhythm ROM · song rhythm level ${song.opn.rhythmLevel}/63", w, small), x, y,
            ink.unit * 1.3f, Palette.DIM, size = small)
        y += ink.unit * 1.3f
        val pan = OpnInstrumentView.panText(ins.left, ins.right)
        for (i in OpnChip.RHYTHM_NAMES.indices) {
            if (y + lineH > r.bottom - lineH * 2) break
            val ch = chip.rhythmFirst + i
            val on = ch == st.listenChannel
            ink.label(c, OpnChip.RHYTHM_NAMES[i], x, y, lineH, if (on) Palette.channel(ch) else Palette.TEXT, on, small)
            ink.label(c, ink.fit("level ${ins.level}/31 · pan $pan", w * 0.6f, small), x + w * 0.4f, y, lineH, Palette.DIM, size = small)
            y += lineH
        }
        val notes = listOf(
            (if (Ym2608RhythmRom.installed) "ROM installed (SHA-256 verified)" else "ROM missing: rhythm notes are refused until it is imported") to
                (if (Ym2608RhythmRom.installed) col else Palette.ERROR),
            "A note triggers its channel's drum; its pitch does not change the drum." to Palette.FAINT,
        )
        var ny = r.bottom - lineH * notes.size - ink.unit * 0.2f
        for ((n, color) in notes) {
            ink.label(c, ink.fit(n, w), x, ny, lineH, color, size = small * 0.95f)
            ny += lineH
        }
    }

    /**
     * The ADPCM-B memory (every sample's share of 256 KiB), the sound's sample as a waveform, and the song's samples
     * (the sound's marked).
     */
    private fun drawSample(c: Surface, r: Rect, ins: OpnAdpcmSound, col: Int) {
        val s = song
        val samples = s.opn.samples
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        ink.round(c, r, ink.dp(4f), Palette.LANE)
        val used = s.opn.adpcmUsed
        val over = used > OpnPlayerImage.ADPCM_MEMORY
        ink.label(c, ink.fit("ADPCM-B memory · ${OpnSampleMenu.memoryText(s)}", r.width() * 0.62f, small), r.left + ink.unit * 0.5f, r.top, titleH,
            if (over) Palette.ERROR else Palette.DIM, size = small)
        val barL = r.left + r.width() * 0.64f
        val barR = r.right - ink.unit * 0.5f
        val barY = r.top + titleH * 0.42f
        ink.rect(c, barL, barY, barR, barY + ink.dp(5f), Palette.RULE)
        ink.rect(c, barL, barY, barL + (barR - barL) * minOf(1f, used.toFloat() / OpnPlayerImage.ADPCM_MEMORY), barY + ink.dp(5f), if (over) Palette.ERROR else col)

        val sample = samples.getOrNull(ins.sample)
        val waveH = minOf(ink.unit * 4f, (r.height() - titleH) * 0.45f)
        val waveRect = Rect(r.left + ink.unit * 0.5f, r.top + titleH, r.right - ink.unit * 0.5f, r.top + titleH + waveH)
        ink.rect(c, waveRect, Palette.BG)
        if (sample != null && waveRect.width() > 4f && waveRect.height() > 4f) {
            val bins = outlines.getOrPut(sample) { outline(sample) }
            val n = bins.size / 2
            val columns = maxOf(1, minOf(n, waveRect.width().toInt() / 2))
            val cw = waveRect.width() / columns
            val mid = waveRect.centerY()
            val half = waveRect.height() / 2
            ink.line(c, waveRect.left, mid, waveRect.right, mid, Palette.RULE, ink.dp(1f))
            for (k in 0 until columns) {
                val a = k * n / columns
                val b = maxOf(a + 1, (k + 1) * n / columns)
                var lo = 0
                var hi = 0
                for (i in a until b) { lo = minOf(lo, bins[2 * i].toInt()); hi = maxOf(hi, bins[2 * i + 1].toInt()) }
                val y0 = mid - half * hi / 32768f
                val y1 = mid - half * lo / 32768f
                ink.rect(c, waveRect.left + k * cw, y0, waveRect.left + k * cw + maxOf(1f, cw - 0.5f), maxOf(y1, y0 + ink.dp(1f)), Palette.alpha(col, 200))
            }
            ink.label(c, "${sample.rate} Hz · ${OpnSampleMenu.seconds(sample.seconds)}${if (ins.loop) " · loops" else ""}", waveRect.right - ink.dp(3f), waveRect.top,
                ink.unit, Palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        } else {
            ink.label(c, ink.fit(if (samples.isEmpty()) "No samples yet: ${ctl.k(Action.EDIT)} on Add sample" else "No sample chosen", waveRect.width() - ink.unit, small),
                waveRect.left + ink.unit * 0.3f, waveRect.top, waveRect.height(), Palette.FAINT, size = small)
        }

        val rowH = ink.unit * 1.15f
        val listTop = waveRect.bottom + ink.unit * 0.3f
        val visible = maxOf(0, ((r.bottom - ink.unit * 0.2f - listTop) / rowH).toInt())
        if (visible == 0 || samples.isEmpty()) return
        val first = (ins.sample - visible / 2).coerceIn(0, maxOf(0, samples.size - visible))
        for (i in first until minOf(samples.size, first + visible)) {
            val smp = samples[i]
            val y = listTop + (i - first) * rowH
            val on = i == ins.sample
            if (on) ink.rect(c, r.left, y, r.right, y + rowH, Palette.alpha(Palette.CURSOR_FILL, 160))
            ink.mono(c, (i + 1).toString().padStart(2, '0'), r.left + ink.unit * 0.5f, y, rowH, if (on) col else Palette.FAINT, on, small)
            val size = "${smp.rate} Hz · ${OpnSampleMenu.kib(smp.stored)}"
            ink.label(c, ink.fit(smp.name.ifEmpty { "(unnamed)" }, r.width() - ink.unit * 3f - ink.labelWidth(size, small)), r.left + ink.unit * 2.2f, y, rowH,
                if (on) Palette.TEXT else Palette.DIM, size = small)
            ink.label(c, size, r.right - ink.unit * 0.5f, y, rowH, Palette.FAINT, size = small, align = Align.RIGHT)
        }
    }

    /** [sample]'s decoded PCM as [OUTLINE_BINS] (min, max) pairs. */
    private fun outline(sample: OpnaSample): ShortArray {
        val pcm = OpnAdpcm.decode(sample.data)
        val bins = minOf(OUTLINE_BINS, maxOf(1, pcm.size))
        val out = ShortArray(2 * bins)
        for (b in 0 until bins) {
            val a = (b.toLong() * pcm.size / bins).toInt()
            val e = maxOf(a + 1, ((b + 1).toLong() * pcm.size / bins).toInt()).coerceAtMost(pcm.size)
            var lo = 0
            var hi = 0
            for (i in a until e) { val v = pcm[i].toInt(); if (v < lo) lo = v; if (v > hi) hi = v }
            out[2 * b] = lo.toShort()
            out[2 * b + 1] = hi.toShort()
        }
        return out
    }

    /** The sound's sample name, with the letter cursor on [org.thortracker.editor.ViewState.textPos]. */
    private fun drawSampleName(c: Surface, rx: Float, vx: Float, y: Float, h: Float, sel: Boolean) {
        val index = OpnInstrumentView.sampleIndex(song, st)
        ink.label(c, Row.SAMPLE_NAME.label, rx, y, h, if (sel) Palette.TEXT else Palette.DIM)
        if (index < 0) { ink.label(c, "—", vx, y, h, Palette.FAINT); return }
        val name = song.opn.samples[index].name
        ink.label(c, name.ifEmpty { "(unnamed)" }, vx, y, h, if (name.isEmpty()) Palette.FAINT else Palette.TEXT, true)
        if (sel) {
            val before = name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, ink.unit, true)
            val ch = name.getOrNull(st.textPos)?.toString() ?: " "
            val w = maxOf(ink.labelWidth(ch, ink.unit, true), ink.unit * 0.5f)
            ink.outline(c, Rect(cx - 1, y + 2, cx + w + 1, y + h - 2), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
    }

    private fun tickHz(): Double = OpnPlayerImage.tickHz(song.opn.timerB)

    private fun ticks(n: Int) = "${(n * 1000.0 / tickHz()).toInt()} ms"

    private fun sweepMs(s: YmSweep): Float = if (s.speed == 0) 0f else (abs(s.end - s.start) * 16f / s.speed * 1000f / tickHz()).toFloat()

    // ---- touch ----------------------------------------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (downShared(x, y, OpnSound.SLOTS, { OpnInstrumentView.showPage(ctl, it) }, { OpnInstrumentView.publish(ctl) })) return true
        val kind = OpnInstrumentView.envelopeKind(song, st)
        if (kind != null && plot.contains(x, y) && song.opn.instrument(st.instrument) is OpnSsgSound) {
            st.instField = OpnInstrumentView.rows(song, st).indexOf(Row.STEPS)
            drawSlot = st.instrument
            drawKind = kind
            drawing = session.beginGesture("Draw ${kind.label.lowercase()}")
            drawAt(x, y)
            return true
        }
        return false
    }

    /** Sets the step under [x] to the value at [y]; past the end it appends one step at a time. */
    private fun drawAt(x: Float, y: Float) {
        val ssg = song.opn.instrument(drawSlot) as? OpnSsgSound ?: return
        val e = OpnEdits.envelope(ssg, drawKind)
        val i = plot.stepAt(x, OpnInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        OpnEdits.editEnvelope(session, drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") {
            NesEnvelopes.withValue(drawKind, it, i, value, OpnEdits.SSG_CHIP)
        }
    }

    override fun move(x: Float, y: Float) {
        val g = drawing ?: return
        if (!session.isGestureOpen(g)) { drawing = null; return }
        drawAt(x, y)
    }

    override fun up(x: Float, y: Float) = finish()
    override fun cancel() = finish()

    private fun finish() {
        drawing?.let { session.endGesture(it) }
        drawing = null
    }

    private companion object {
        /** How long the envelope sketch holds the key. */
        const val HOLD_MS = 500f

        /** The envelope generator's clock: the chips' native sample rate (55466.67 Hz on both) / 3. */
        val TIMING = OpnTiming((OpnPlayerImage.NATIVE_RATE / 3.0).toFloat())

        /** Waveform resolution of a drawn sample. */
        const val OUTLINE_BINS = 512

        /** An SSG-EG value's outline ("\\\\", "/‾‾‾"…). */
        fun ssgShape(v: Int): String = YmCurated.SSG_EG_NAMES[v]?.substringBefore(' ') ?: "?"
    }
}
