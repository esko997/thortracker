package org.thortracker.system.ym2612

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.thortracker.backend.YmPcmLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.GestureToken
import org.thortracker.model.NumberText
import org.thortracker.system.fm.FmEnvelopeSketch
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.FmRouting
import org.thortracker.system.fm.OpnTiming
import org.thortracker.system.nes.EnvelopePlot
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.system.nes.NesInstrumentView
import org.thortracker.system.ym2612.YmInstrumentView.Page
import org.thortracker.system.ym2612.YmInstrumentView.Row
import org.thortracker.ui.Align
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/**
 * Mega Drive instrument editor. Left: the 64 slots (FM voices, PSG sounds marked "ᴾ", PCM kits "ᴷ", empty "·") with
 * how often each is used. Right: name, Listen channel, type, page tabs, then the page — for an FM voice the curated
 * controls beside the drawn algorithm (the eight OPN2 routings) and envelope (with SSG-EG shapes), the Expert register
 * grid, the Ch3 operator offsets, or the drum sweep; for a PSG sound an envelope's steps (the NES editor's plot) or the
 * noise settings with the sweep; for a PCM kit its level and key rows above the mapped keys and the song's samples
 * (the NES PCM page's box). The bank, tabs, grid and drawings are the shared [FmInstrumentPainter]'s.
 */
internal class YmInstrumentPainter(v: PaneView) : FmInstrumentPainter(v) {
    private val plot = EnvelopePlot(ink)

    override fun draw(c: Surface, area: Rect) {
        val s = song
        val ins = YmInstrumentView.sound(s, st)
        val page = YmInstrumentView.page(s, st)
        val listen = YmInstrumentView.listenChannel(st, ins, page)
        val col = ink.palette.channel(listen)
        val locked = if (st.lockedSubject >= 0) "  locked" else ""
        val kind = when (ins) {
            is YmFmInstrument -> "FM · " + YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].name
            is YmPsgInstrument -> "PSG sound"
            is YmPcmInstrument -> "PCM kit"
            null -> "empty slot"
        }
        val top = header(c, area, "Sound ${hex2(st.instrument)}", "Mega Drive · $kind$locked  ${usageText(st.instrument)}")
        val row = YmInstrumentView.row(s, st)
        val allRows = YmInstrumentView.rows(s, st)
        rowRects.clear()
        gridCells.clear()
        plot.hide()

        val left = drawBank(c, area, top, YmSystem.SLOTS, col, row == Row.SLOT) { i ->
            s.ym.instrument(i)?.let {
                it.name.ifEmpty { "(unnamed)" } + when (it) { is YmPsgInstrument -> " ᴾ"; is YmPcmInstrument -> " ᴷ"; else -> "" }
            }
        }

        val pad = ink.unit * 0.9f
        val rx = left.right + pad
        val fullW = area.right - pad - rx
        val envelopePage = page.envelope != null
        val pcmPage = page == Page.PCM
        val side = fullW >= ink.unit * 50f && !envelopePage && !pcmPage
        val rw = if (side) minOf(fullW * 0.5f, ink.unit * 30f) else fullW
        rightEdge = rx + rw
        val rows = allRows.filter { it != Row.SLOT && it != Row.STEPS && it !in YmPcmPage.BOX_ROWS }
        val gridRows = rows.count { it in YmInstrumentView.GRID || it in YmInstrumentView.CH3_GRID }
        val plain = rows.size - gridRows
        // Rows shrink (down to 1.15 units) before the drawings below them lose their room.
        val reserve = if (side) 0f else ink.unit * (if (envelopePage) 8f else if (pcmPage) 11f else 9f)
        val fit = (area.bottom - top - ink.unit * 0.7f - reserve - ink.unit * 0.4f) / (plain + gridRows + (if (gridRows > 0) 1 else 0) + 0.4f)
        val rowH = fit.coerceIn(ink.unit * 1.15f, ink.unit * 1.5f)
        val vx = rx + ink.unit * 6.3f
        var y = top + ink.unit * 0.3f
        val tabLabels = YmInstrumentView.pages(s, st).map { p -> tabLabel(ins, p) }
        var gridHeaderDrawn = false
        for (r in rows) {
            val sel = r == row
            if ((r in YmInstrumentView.GRID || r in YmInstrumentView.CH3_GRID) && ins is YmFmInstrument) {
                if (!gridHeaderDrawn) { y = drawGridHeader(c, rx, vx, rw, y, rowH, col, { true }, { ins.isCarrier(it) }); gridHeaderDrawn = true }
                drawGridRow(c, r.label, allRows.indexOf(r), rx, vx, rw, y, rowH, sel, YmInstrumentView.operator(st), { true }) { op ->
                    if (r in YmInstrumentView.CH3_GRID) ch3Text(ins, op, r) else gridText(ins.operators[op], r)
                }
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
                    listenNote(ins, listen)?.let { note ->
                        val nx = vx + ink.labelWidth(name, ink.unit, true) + ink.unit
                        ink.label(c, ink.fit(note, rightEdge - nx, ink.unit * 0.85f), nx, y, h, ink.palette.PLAYHEAD, size = ink.unit * 0.85f)
                    }
                }
                r == Row.PAGE -> drawTabs(c, Rect(rx, y, rx + rw, y + h), col, tabLabels, st.envelope.coerceIn(0, tabLabels.size - 1))
                r == Row.TYPE -> setting(c, "Type", when (ins) {
                    is YmFmInstrument -> "FM voice   plays on FM 1-6"
                    is YmPsgInstrument -> "PSG sound   plays on PSG 1-3 and Noise"
                    is YmPcmInstrument -> "PCM kit   plays on PCM 1-3"
                    null -> "empty   ${ctl.k(Action.EDIT)} makes " + when (YmInstrumentView.kind(s, st)) {
                        YmInstrumentView.Kind.FM -> "an FM voice"
                        YmInstrumentView.Kind.PSG -> "a PSG sound"
                        YmInstrumentView.Kind.PCM -> "a PCM kit"
                    }
                }, rx, vx, y, h, sel, ins == null)
                r == Row.FM3_MODE -> setting(c, r.label, fm3Text(), rx, vx, y, h, sel, s.ym.fm3Mode == YmFm3Mode.NORMAL)
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
            val psg = ins as? YmPsgInstrument ?: defaultYmPsg(st.instrument)
            val small = ink.unit * 0.8f
            ink.label(c, ink.fit(envelopeSummary(k), rw, small), rx, y, rowH * 0.85f, ink.palette.FAINT, size = small)
            y += rowH * 0.85f
            val plotTop = y + ink.unit * 0.3f
            if (area.bottom - plotTop > ink.unit * 4f) {
                val env = psg.envelope(k)
                val text = { value: Int -> NesInstrumentView.valueText(k, value, null) }
                val title = EnvelopePlot.title(k.label, env.values, st.envStep, text, ctl.keys.label(Action.EDIT), ctl.keys.label(Action.UP))
                plot.draw(c, Rect(rx, plotTop, rx + rw, area.bottom - ink.unit * 0.5f), title, k, env, st.envStep, text,
                    ins == null, col, row == Row.STEPS, cursorColor(), ctl.editHeld)
            }
            return
        }
        if (pcmPage) {
            val box = Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
            if (box.height() > ink.unit * 6f) drawPcm(c, box, ins as? YmPcmInstrument ?: defaultYmPcm(st.instrument), row, col)
            return
        }
        val box = if (side) Rect(rightEdge + pad, top + ink.unit * 0.3f, area.right - pad, area.bottom - ink.unit * 0.4f)
            else Rect(rx, y + ink.unit * 0.4f, rx + fullW, area.bottom - ink.unit * 0.4f)
        if (box.height() < ink.unit * 4f || ins == null) return
        when (page) {
            Page.SOUND -> if (ins is YmFmInstrument) {
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
            Page.EXPERT -> if (ins is YmFmInstrument) algorithm(c, box, ins, col, YmInstrumentView.operator(st))
            Page.CH3 -> if (ins is YmFmInstrument) drawCh3(c, box, ins, col)
            else -> sweep(c, box, ins.sweep, col)
        }
    }

    /** A page tab: PSG envelopes carry their length, as on the NES; Ch3 is ticked when an operator has an offset. */
    private fun tabLabel(ins: YmInstrument?, p: Page): String {
        if (p == Page.CH3) return if ((ins as? YmFmInstrument)?.hasCh3 == true) "${p.label} ✓" else p.label
        if (p == Page.PCM) return (ins as? YmPcmInstrument)?.pcm?.size?.takeIf { it > 0 }?.let { "${p.label} ($it)" } ?: p.label
        val k = p.envelope ?: return p.label
        val n = (ins as? YmPsgInstrument)?.envelope(k)?.values?.size ?: 0
        return if (n == 0) p.label else "${p.label} ($n)"
    }

    /** Why the Listen channel plays the sound differently: a fixed-rate noise ignores pitch; PSG sounds on Noise play noise. */
    private fun listenNote(ins: YmInstrument?, ch: Int): String? = when {
        ins is YmPsgInstrument && ch == YmRules.NOISE ->
            if (ins.noise and 3 == YmPsgInstrument.PITCHED) "pitched noise: PSG 3 is silent while it sounds" else "fixed-rate noise: notes do not change it"
        ins is YmPsgInstrument -> "tone (the noise settings apply on Noise)"
        ins is YmFmInstrument && YmInstrumentView.page(song, st) == Page.CH3 ->
            if (song.ym.fm3Mode == YmFm3Mode.NORMAL) "offsets silent: FM 3 is in normal mode" else "operators at their offsets"
        ins is YmPcmInstrument -> "key ${YmSystem.noteName(st.dpcmKey)}; FM 6 is silent while PCM plays"
        else -> null
    }

    private fun envelopeSummary(k: NesEnvelopeKind): String = when (k) {
        NesEnvelopeKind.VOLUME -> "Level 0-15 per tick (2 dB steps; empty = 15). After OFF the release step on plays out"
        NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick. Noise: only in pitched mode"
        else -> "Sixteenths of a semitone per tick (relative: added up each tick)"
    }

    // ---- values ---------------------------------------------------------------------------------------

    private fun value(ins: YmInstrument, r: Row): String = when (ins) {
        is YmFmInstrument -> fmValue(ins, r)
        is YmPsgInstrument -> psgValue(ins, r)
        is YmPcmInstrument -> pcmValue(ins, r)
    }

    /** The PCM page's setting rows for the shown key ([org.thortracker.editor.ViewState.dpcmKey]). */
    private fun pcmValue(kit: YmPcmInstrument, r: Row): String {
        val key = st.dpcmKey
        val m = kit.pcm[key]
        val sample = m?.let { song.ym.pcmSamples.getOrNull(it.sample) }
        return when (r) {
            Row.PCM_LEVEL -> if (kit.level == 0) "0   silent" else "${kit.level}   ${bar(kit.level, YmPcm.LEVEL_MAX)}  −${2 * (YmPcm.LEVEL_MAX - kit.level)} dB"
            Row.PCM_KEY -> YmSystem.noteName(key) + when {
                kit.pcm.isEmpty() -> "   no keys mapped yet"
                m == null -> "   not mapped (${kit.pcm.size} key${if (kit.pcm.size == 1) "" else "s"} are)"
                else -> "   key ${kit.pcm.keys.count { it <= key }} of ${kit.pcm.size} mapped"
            }
            Row.PCM_SAMPLE -> when {
                m == null -> "none"
                sample == null -> "missing PCM sample ${m.sample + 1}"
                else -> "${sample.name}  (${sample.data.size} bytes, ${YmPcm.seconds(sample)})"
            }
            Row.PCM_PITCH -> when {
                m == null -> "—"
                sample == null -> "${m.pitch} st"
                else -> "${if (m.pitch > 0) "+" else ""}${m.pitch} st  plays ${YmPcm.seconds(sample, m.pitch)} " +
                    "(${YmPcm.rateText(YmPcm.playRate(sample.rate, m.pitch))} → ${YmPcm.rateText(YmPcm.fullRate(song.ym.pal))})"
            }
            Row.PCM_SPREAD -> if (m == null) "—" else "map this sample an octave either way, a semitone per key"
            else -> ""
        }
    }

    /** The preset [ins] is (FM 3 offsets aside: no preset sets them), or "custom". */
    private fun presetText(ins: YmInstrument): String = YmPresets.ALL.firstOrNull {
        it.instrument == (if (ins is YmFmInstrument) ins.copy(ch3 = listOf(0, 0, 0, 0)) else ins)
    }?.let { p ->
        p.name + (p.note?.let { "   drum, voiced at ${YmSystem.noteName(it)}" } ?: "")
    } ?: "custom   ${ctl.keys.held(Action.EDIT, ctl.lr())} picks a starting sound"

    private fun fmValue(ins: YmFmInstrument, r: Row): String {
        val k = YmCurated.controls(ins)
        val lfoOff = song.ym.lfo == 0
        fun approx(exact: Boolean) = if (exact) "" else "≈ "
        val slDb = OpnTiming.slDb(15 - k.sustain)
        return when (r) {
            Row.PRESET, Row.DRUM_PRESET -> presetText(ins)
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
            Row.SUSTAIN_RATE -> approx(k.envelopeExact) + if (k.sustainRate == 0) "0   held while the key is down" else "${k.sustainRate}   keeps fading: −96 dB in ${timeText(TIMING.fallMs(2 * k.sustainRate, 96f))}"
            Row.RELEASE -> approx(k.envelopeExact) + "${k.release}   ${timeText(TIMING.fallMs(4 * k.release + 2, 96f))} after OFF"
            Row.TONE_DECAY -> if (k.toneDecay < 0) "—   additive: no modulators" else approx(k.envelopeExact) + "${k.toneDecay}   brightness fades in ${timeText(TIMING.fallMs(2 * k.toneDecay, 96f))}"
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

    private fun psgValue(ins: YmPsgInstrument, r: Row): String {
        val kind = YmInstrumentView.envelopeKind(song, st)
        val env = kind?.let { ins.envelope(it) } ?: NesEnvelope()
        return when (r) {
            Row.PRESET -> presetText(ins)
            Row.LENGTH -> when {
                env.values.isNotEmpty() -> "${env.values.size} step${if (env.values.size == 1) "" else "s"} (${ticks(env.values.size)})"
                kind == NesEnvelopeKind.VOLUME -> "empty: full volume"
                else -> "empty: not used"
            }
            Row.LOOP -> if (env.loop < 0) "off: the last value holds" else "from step ${env.loop}"
            Row.ENV_RELEASE -> when {
                env.release >= 0 -> "at step ${env.release}: after OFF the steps from here play out"
                env.loop < 0 -> "none: OFF stops the note (a release needs a loop)"
                else -> "none: OFF stops the note"
            }
            Row.MODE -> if (ins.pitchRelative) "relative: offsets add up every tick" else "absolute: offset from the note"
            Row.NOISE_TYPE -> if (ins.noise and YmPsgInstrument.WHITE != 0) "white   hiss (snares, hats)" else "periodic   buzz (tonal)"
            Row.NOISE_RATE -> when (ins.noise and 3) {
                YmPsgInstrument.PITCHED -> "pitched   the note sets it (takes PSG 3's period)"
                else -> "${YmRules.NOISE_RATES[ins.noise and 3]}   fixed: ${NOISE_HZ[ins.noise and 3]} (notes do not change it)"
            }
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> sweepValue(ins.sweep, r)
            else -> ""
        }
    }

    private fun sweepValue(sw: YmSweep, r: Row): String = when (r) {
        Row.SWEEP_START -> if (!sw.active) "${YmRules.signed(sw.start)}   (sweep off: set a speed)" else "${YmRules.signed(sw.start)} semitones from the note"
        Row.SWEEP_END -> "${YmRules.signed(sw.end)} semitones"
        else -> if (!sw.active) "0   off" else "${sw.speed}   ${NumberText.fixed(sw.speed / 16.0, 2)} semitones per tick · ${timeText(sweepMs(sw))}"
    }

    private fun dim(ins: YmInstrument, r: Row): Boolean = when (r) {
        Row.BRIGHTNESS, Row.TONE_DECAY -> ins is YmFmInstrument && YmCurated.controls(ins).brightness < 0
        Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> !ins.sweep.active
        Row.VIBRATO, Row.TREMOLO -> song.ym.lfo == 0
        Row.NOISE_TYPE, Row.NOISE_RATE -> YmInstrumentView.listenChannel(st, ins) != YmRules.NOISE
        Row.PCM_KEY, Row.PCM_SAMPLE, Row.PCM_PITCH, Row.PCM_SPREAD -> (ins as? YmPcmInstrument)?.pcm?.get(st.dpcmKey) == null
        Row.PCM_LEVEL -> (ins as? YmPcmInstrument)?.level == 0
        else -> false
    }

    /** The song's FM 3 mode as the Ch3 page's first row shows it. */
    private fun fm3Text(): String = when (song.ym.fm3Mode) {
        YmFm3Mode.NORMAL -> "normal   offsets silent; FM 3 plays like FM 1-6"
        YmFm3Mode.SPECIAL -> "special   FM 3's operators sound at their offsets"
    }

    /** Operator [op]'s FM 3 offset as the Ch3 grid shows it: whole semitones, then sixty-fourths. */
    private fun ch3Text(ins: YmFmInstrument, op: Int, r: Row): String {
        val offset = ins.ch3.getOrElse(op) { 0 }
        val semis = if (offset >= 0) offset / 64 else -((-offset) / 64)
        val fine = offset - semis * 64
        return if (r == Row.CH3_SEMI) "${signed(semis)} st" else "${signed(fine)}/64"
    }

    /** One register field of operator [o] as the Expert grid shows it. */
    private fun gridText(o: YmOperator, r: Row): String = when (r) {
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

    // ---- drawings -------------------------------------------------------------------------------------

    /** The algorithm's routing: operators placed by depth (modulators left of what they modulate), carriers on the right. */
    private fun routing(ins: YmFmInstrument): FmRouting {
        val alg = ins.algorithm.coerceIn(0, 7)
        return FmRouting.opn(alg, YmCurated.ALGORITHMS[alg].edges) { ins.isCarrier(it) }
    }

    private fun algorithm(c: Surface, r: Rect, ins: YmFmInstrument, col: Int, focus: Int) {
        val a = YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)]
        drawAlgorithm(c, r, "Algorithm ${a.number} ${a.name} ${a.diagram} · fb ${ins.feedback}", routing(ins), ins.feedback, col, focus,
            { op -> ins.operators[op].let { "×${YmCurated.multText(it.mul)}" + if (YmCurated.detuneSteps(it.dt) != 0) "(${YmCurated.detuneText(it.dt)})" else "" } }, { 0 })
    }

    /** The first carrier's envelope in dB: attack, decay to SL, held while falling at D2R, release; SSG-EG when the carriers use it. */
    private fun envelope(ins: YmFmInstrument): FmEnvelopeSketch {
        val k = YmCurated.controls(ins)
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
        val title = if (!sw.active) "No pitch sweep: notes play at their pitch (set a speed for a drum drop)"
            else "Pitch from ${YmSystem.noteName((root + sw.start).coerceIn(YmRules.NOTES))} to ${YmSystem.noteName((root + sw.end).coerceIn(YmRules.NOTES))} " +
                "in ${timeText(sweepMs(sw))} (note ${YmSystem.noteName(root)})"
        drawSweep(c, r, title, sw.start, sw.end, sw.speed, sw.active, (tickHz() / 2).toInt(), col)
    }

    /** FM 3's four operators: each one's offset as an interval and the note it sounds against the listening note; the mode. */
    private fun drawCh3(c: Surface, r: Rect, ins: YmFmInstrument, col: Int) {
        val small = ink.unit * 0.8f
        ink.round(c, r, ink.dp(4f), ink.palette.LANE)
        val x = r.left + ink.unit * 0.5f
        val w = r.width() - ink.unit
        val lineH = ink.unit * 1.2f
        var y = r.top
        val mode = song.ym.fm3Mode
        ink.label(c, ink.fit(when (mode) {
            YmFm3Mode.NORMAL -> "FM 3 normal mode: these offsets are silent (set the FM 3 mode to special)"
            YmFm3Mode.SPECIAL -> "FM 3 special mode: each operator sounds the note plus its offset"
        }, w, small), x, y, ink.unit * 1.4f, if (mode == YmFm3Mode.NORMAL) ink.palette.ERROR else ink.palette.DIM, size = small)
        y += ink.unit * 1.4f
        val root = ctl.defaultNote()
        for (op in 0 until 4) {
            if (y + lineH > r.bottom) break
            val offset = ins.ch3.getOrElse(op) { 0 }
            val units = root * 64 + offset
            val note = (units / 64.0).roundToInt().coerceIn(YmRules.NOTES)
            val cents = ((units - note * 64) * 100.0 / 64).roundToInt()
            val carrier = ins.isCarrier(op)
            val on = op == YmInstrumentView.operator(st)
            val text = "Op ${op + 1} ${if (carrier) "♪car" else "mod"}   ${NumberText.fixed(offset / 64.0, 2, plus = true)} st   " +
                YmSystem.noteName(note) + if (cents != 0) " ${if (cents > 0) "+" else ""}${cents}¢" else ""
            ink.label(c, ink.fit(text, w, small), x, y, lineH, if (on) col else if (carrier) ink.palette.TEXT else ink.palette.DIM, on, small)
            y += lineH
        }
        val notes = listOf("Offsets play on FM 3 only; other channels and normal mode ignore them.",
            "Against the listening note ${YmSystem.noteName(root)}; ${ctl.keys.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP)} moves it.")
        var ny = r.bottom - lineH * notes.size - ink.unit * 0.2f
        if (ny < y) return
        for (n in notes) {
            ink.label(c, ink.fit(n, w, small), x, ny, lineH, ink.palette.FAINT, size = small * 0.95f)
            ny += lineH
        }
    }

    private var keyRect = Rect()
    private var keyRowH = 0f
    private var keyScroll = 0
    private var keyList: List<Int> = emptyList()
    private var sampleRect = Rect()
    private var sampleRowH = 0f
    private var sampleScroll = 0
    private var addRect = Rect()

    /**
     * The PCM page's box (as the NES PCM page's): left, every key the kit maps (key, sample, pitch); right, the song's
     * PCM samples with their sizes and the memory they share, the compiled bank's use, the selected sample's waveform
     * (its signed values around the centre line), its name, and the Add row.
     */
    private fun drawPcm(c: Surface, r: Rect, kit: YmPcmInstrument, row: Row, col: Int) {
        val s = song
        val samples = s.ym.pcmSamples
        val gap = ink.unit * 0.6f
        val small = ink.unit * 0.8f
        val titleH = ink.unit * 1.4f
        val left = Rect(r.left, r.top, r.left + (r.width() - gap) * 0.42f, r.bottom)
        val right = Rect(left.right + gap, r.top, r.right, r.bottom)

        // mapped keys
        ink.round(c, left, ink.dp(4f), ink.palette.LANE)
        ink.label(c, ink.fit("Keys of kit ${hex2(st.instrument)}", left.width() - ink.unit), left.left + ink.unit * 0.5f, left.top, titleH, ink.palette.DIM, size = small)
        keyList = kit.pcm.keys.sorted()
        keyRowH = ink.unit * 1.25f
        keyRect = Rect(left.left, left.top + titleH, left.right, left.bottom - ink.unit * 0.3f)
        val keyRows = maxOf(1, (keyRect.height() / keyRowH).toInt())
        val shown = keyList.indexOf(st.dpcmKey)
        if (shown >= 0) keyScroll = keyScroll.coerceIn(maxOf(0, shown - keyRows + 1), shown)
        keyScroll = keyScroll.coerceIn(0, maxOf(0, keyList.size - keyRows))
        if (keyList.isEmpty()) {
            ink.label(c, ink.fit("No keys yet: Add, then map", left.width() - ink.unit), left.left + ink.unit * 0.5f, keyRect.top, keyRowH, ink.palette.FAINT, size = small)
        }
        val keyFocus = row in YmPcmPage.KEY_ROWS
        for (i in keyScroll until minOf(keyList.size, keyScroll + keyRows)) {
            val key = keyList[i]
            val m = kit.pcm.getValue(key)
            val y = keyRect.top + (i - keyScroll) * keyRowH
            val on = key == st.dpcmKey
            if (on) ink.rect(c, left.left, y, left.right, y + keyRowH, if (keyFocus) ink.palette.CURSOR_FILL else Palette.alpha(ink.palette.CURSOR_FILL, 140))
            if (on) ink.rect(c, left.left, y, left.left + ink.dp(3f), y + keyRowH, col)
            ink.mono(c, YmSystem.noteName(key), left.left + ink.unit * 0.5f, y, keyRowH, if (on) col else ink.palette.DIM, on, small)
            val tail = if (m.pitch > 0) "+${m.pitch}" else m.pitch.toString()
            val tailW = ink.labelWidth(tail, small) + ink.unit * 0.5f
            val name = samples.getOrNull(m.sample)?.name ?: "missing"
            ink.label(c, ink.fit(name, left.width() - ink.unit * 3.6f - tailW, small), left.left + ink.unit * 3.2f, y, keyRowH,
                if (m.sample in samples.indices) (if (on) ink.palette.TEXT else ink.palette.DIM) else ink.palette.ERROR, size = small)
            ink.label(c, tail, left.right - ink.unit * 0.4f, y, keyRowH, ink.palette.FAINT, size = small, align = Align.RIGHT)
        }

        // samples and the compiled bank
        ink.round(c, right, ink.dp(4f), ink.palette.LANE)
        val used = YmPcm.stored(s.ym)
        val problem = YmPcmLowering.problem(s.ym)
        val over = used > YmPcm.MEMORY || problem != null
        val bank = if (problem == null) YmPcmLowering.compile(s.ym) else null
        val title = "PCM · ${YmPcm.kb(used)} of ${YmPcm.kb(YmPcm.MEMORY)}" + (bank?.let { " · bank ${YmPcmLowering.usage(it)}" } ?: " · bank over its limits")
        ink.label(c, ink.fit(title, right.width() * 0.62f), right.left + ink.unit * 0.5f, right.top, titleH, if (over) ink.palette.ERROR else ink.palette.DIM, size = small)
        val barL = right.left + right.width() * 0.64f
        val barR = right.right - ink.unit * 0.5f
        val barY = right.top + titleH * 0.42f
        ink.rect(c, barL, barY, barR, barY + ink.dp(5f), ink.palette.RULE)
        ink.rect(c, barL, barY, barL + (barR - barL) * minOf(1f, used.toFloat() / YmPcm.MEMORY), barY + ink.dp(5f), if (over) ink.palette.ERROR else col)

        val lineH = ink.unit * 1.35f
        addRect = Rect(right.left, right.bottom - lineH - ink.unit * 0.2f, right.right, right.bottom - ink.unit * 0.2f)
        val nameY = addRect.top - lineH
        val waveH = minOf(ink.unit * 3.2f, (nameY - right.top - titleH) * 0.45f)
        val waveRect = Rect(right.left + ink.unit * 0.5f, nameY - waveH - ink.unit * 0.2f, right.right - ink.unit * 0.5f, nameY - ink.unit * 0.2f)
        sampleRowH = ink.unit * 1.2f
        sampleRect = Rect(right.left, right.top + titleH, right.right, waveRect.top - ink.unit * 0.2f)
        val sampleRows = maxOf(1, (sampleRect.height() / sampleRowH).toInt())
        sampleScroll = sampleScroll.coerceIn(maxOf(0, st.dpcmSample - sampleRows + 1), st.dpcmSample.coerceAtLeast(0))
        sampleScroll = sampleScroll.coerceIn(0, maxOf(0, samples.size - sampleRows))
        val keyed = kit.pcm[st.dpcmKey]?.sample ?: -1
        if (samples.isEmpty()) ink.label(c, ink.fit("None yet: use Add below", right.width() - ink.unit), right.left + ink.unit * 0.5f, sampleRect.top, sampleRowH, ink.palette.FAINT, size = small)
        for (i in sampleScroll until minOf(samples.size, sampleScroll + sampleRows)) {
            val sample = samples[i]
            val y = sampleRect.top + (i - sampleScroll) * sampleRowH
            val on = i == st.dpcmSample
            if (on) ink.rect(c, right.left, y, right.right, y + sampleRowH, ink.palette.CURSOR_FILL)
            ink.mono(c, (i + 1).toString().padStart(2, '0'), right.left + ink.unit * 0.5f, y, sampleRowH, if (i == keyed) col else ink.palette.FAINT, i == keyed, small)
            val size = "${YmPcm.seconds(sample)} · ${YmPcm.rateText(sample.rate.toDouble())}"
            ink.label(c, ink.fit(sample.name.ifEmpty { "(unnamed)" }, right.width() - ink.unit * 2.6f - ink.labelWidth(size, small) - ink.unit), right.left + ink.unit * 2.2f, y, sampleRowH,
                if (on) ink.palette.TEXT else ink.palette.DIM, size = small)
            ink.label(c, size, right.right - ink.unit * 0.5f, y, sampleRowH, ink.palette.FAINT, size = small, align = Align.RIGHT)
            if (on && row == Row.PCM_SAMPLES) ink.outline(c, Rect(right.left + 2, y + 1, right.right - 2, y + sampleRowH - 1), cursorColor(), ink.dp(2f), ink.dp(3f))
        }

        // the selected sample: waveform around silence, name
        val sample = samples.getOrNull(st.dpcmSample)
        ink.rect(c, waveRect, ink.palette.BG)
        if (sample != null && sample.data.size > 0 && waveRect.width() > 4f && waveRect.height() > 4f) {
            val data = sample.data
            val columns = maxOf(1, waveRect.width().toInt() / 2)
            val per = data.size.toFloat() / columns
            val cw = waveRect.width() / columns
            val mid = waveRect.top + waveRect.height() / 2
            ink.line(c, waveRect.left, mid, waveRect.right, mid, ink.palette.RULE, ink.dp(1f))
            for (k in 0 until columns) {
                val a = (k * per).toInt().coerceIn(0, data.size - 1)
                val b = ((k + 1) * per).toInt().coerceIn(a + 1, data.size)
                // At most 64 reads per column: long samples are drawn from a stride of their bytes.
                val stride = maxOf(1, (b - a) / 64)
                var lo = 127
                var hi = -128
                var i = a
                while (i < b) { val v = data.signed(i); if (v < lo) lo = v; if (v > hi) hi = v; i += stride }
                val y0 = mid - waveRect.height() * hi / 256f
                val y1 = mid - waveRect.height() * lo / 256f
                ink.rect(c, waveRect.left + k * cw, y0, waveRect.left + k * cw + maxOf(1f, cw - 0.5f), maxOf(y1, y0 + ink.dp(1f)), Palette.alpha(col, 200))
            }
            ink.label(c, "${data.size} B", waveRect.right - ink.dp(3f), waveRect.top, ink.unit, ink.palette.FAINT, size = ink.unit * 0.65f, align = Align.RIGHT)
        }
        val vx = right.left + ink.unit * 4.4f
        fun boxRow(y: Float, which: Row, label: String, value: String, off: Boolean) {
            val sel = row == which
            val rr = Rect(right.left + ink.unit * 0.2f, y, right.right - ink.unit * 0.2f, y + lineH)
            if (sel) ink.rect(c, rr, ink.palette.CURSOR_FILL)
            ink.label(c, label, right.left + ink.unit * 0.5f, y, lineH, if (sel) ink.palette.TEXT else ink.palette.DIM, size = small)
            ink.label(c, ink.fit(value, right.right - vx - ink.unit * 0.5f, small, bold = sel), vx, y, lineH,
                if (off) ink.palette.FAINT else if (sel) ink.palette.TEXT else ink.palette.DIM, sel, small)
            if (sel) ink.outline(c, rr, cursorColor(), ink.dp(2f), ink.dp(3f))
        }
        boxRow(nameY, Row.PCM_NAME, "Name", sample?.name?.ifEmpty { "(unnamed)" } ?: "—", sample == null)
        if (row == Row.PCM_NAME && sample != null) {
            val before = sample.name.take(st.textPos).padEnd(st.textPos, ' ')
            val cx = vx + ink.labelWidth(before, small, true)
            val w = maxOf(ink.labelWidth(sample.name.getOrNull(st.textPos)?.toString() ?: " ", small, true), small * 0.5f)
            ink.outline(c, Rect(cx - 1, nameY + 3, cx + w + 1, nameY + lineH - 3), cursorColor(), ink.dp(2f), ink.dp(2f))
        }
        boxRow(addRect.top, Row.PCM_ADD, "Add", "import a .wav file…", false)
    }

    private fun tickHz(): Double = YmSystem.ticksPerSecond(song)

    private fun ticks(n: Int) = "${(n * 1000.0 / tickHz()).toInt()} ms"

    private fun sweepMs(s: YmSweep): Float = if (s.speed == 0) 0f else (abs(s.end - s.start) * 16f / s.speed * 1000f / tickHz()).toFloat()

    // ---- touch ----------------------------------------------------------------------------------------

    private var drawing: GestureToken? = null
    private var drawSlot = 0
    private var drawKind = NesEnvelopeKind.VOLUME

    override fun down(x: Float, y: Float): Boolean {
        if (downShared(x, y, YmSystem.SLOTS, { YmInstrumentView.showPage(ctl, it) }, { YmInstrumentView.publish(ctl) })) return true
        if (YmInstrumentView.page(song, st) == Page.PCM) pcmDown(x, y)?.let { return it }
        val kind = YmInstrumentView.envelopeKind(song, st)
        if (kind != null && plot.contains(x, y) && song.ymInstrument(st.instrument) is YmPsgInstrument) {
            st.instField = YmInstrumentView.rows(song, st).indexOf(Row.STEPS)
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
        val e = (song.ymInstrument(drawSlot) as? YmPsgInstrument)?.envelope(drawKind) ?: return
        val i = plot.stepAt(x, YmInstrumentView.lastStep(e))
        val value = plot.valueAt(y)
        st.envStep = i
        session.editYmEnvelope(drawSlot, drawKind, "Draw ${drawKind.label.lowercase()}") { NesEnvelopes.withValue(drawKind, it, i, value) }
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

    /** PCM page taps: a mapped key shows it, a sample selects it, Add opens the import. Null: not on them. */
    private fun pcmDown(x: Float, y: Float): Boolean? {
        val rows = YmInstrumentView.rows(song, st)
        when {
            keyRect.contains(x, y) && keyRowH > 0f -> {
                val key = keyList.getOrNull(keyScroll + ((y - keyRect.top) / keyRowH).toInt()) ?: return true
                st.dpcmKey = key
                st.instField = rows.indexOf(Row.PCM_KEY)
                song.ymPcmMapping(st.instrument, key)?.let { st.dpcmSample = it.sample }
            }
            sampleRect.contains(x, y) && sampleRowH > 0f -> {
                val i = sampleScroll + ((y - sampleRect.top) / sampleRowH).toInt()
                if (i !in song.ym.pcmSamples.indices) return true
                st.dpcmSample = i
                st.instField = rows.indexOf(Row.PCM_SAMPLES)
            }
            addRect.contains(x, y) -> {
                st.instField = rows.indexOf(Row.PCM_ADD)
                YmPcmSampleMenu.open(session, st.instrument, st.dpcmKey)
            }
            else -> return null
        }
        return true
    }

    private companion object {
        /** How long the envelope sketch holds the key. */
        const val HOLD_MS = 500f

        /** The envelope generator's clock: the chip's sample rate (NTSC 53267 Hz) / 3. */
        const val EG_HZ = 53267f / 3f

        val TIMING = OpnTiming(EG_HZ)

        /** The fixed noise rates at the NTSC PSG clock (3579545 / 32 / divider). */
        val NOISE_HZ = listOf("6991 Hz", "3496 Hz", "1748 Hz")

        /** An SSG-EG value's outline ("\\\\", "/‾‾‾"…). */
        fun ssgShape(v: Int): String = YmCurated.SSG_EG_NAMES[v]?.substringBefore(' ') ?: "?"
    }
}
