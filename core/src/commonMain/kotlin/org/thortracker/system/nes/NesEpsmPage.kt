package org.thortracker.system.nes

import kotlin.math.log2
import kotlin.math.roundToInt
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.ViewController
import org.thortracker.input.Keymap
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.fm.FmInstrumentPainter
import org.thortracker.system.fm.OpnTiming
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.NesInstrumentView.Row
import org.thortracker.system.ym2612.YmCurated
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmInstrumentView
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmPresets
import org.thortracker.system.ym2612.YmInstrumentView.Row as YmRow

/** The FM patch as a Mega Drive FM voice, so the YM2612 editor's curated controls ([YmCurated]) edit it (same OPN bits). */
internal fun EpsmPatch.asYm(): YmFmInstrument = YmFmInstrument(algorithm = algorithm, feedback = feedback, ams = ams, fms = pms, operators = operators)

/** This patch with the OPN fields of [ym] (its pan kept). */
internal fun EpsmPatch.withYm(ym: YmFmInstrument): EpsmPatch =
    copy(algorithm = ym.algorithm, feedback = ym.feedback, ams = ym.ams, pms = ym.fms, operators = ym.operators)

/**
 * The EPSM pages of [NesInstrumentView] (EPSM sounds only): the SSG hardware envelope ([NesInstrumentView.ENV_PAGE]),
 * the FM patch's curated controls ([NesInstrumentView.EPSM_FM_PAGE], the Mega Drive editor's [YmCurated] over the same
 * OPN registers) and every register ([NesInstrumentView.EPSM_EXPERT_PAGE]), and the rhythm page
 * ([NesInstrumentView.EPSM_RHYTHM_PAGE]): what the six fixed-pitch ROM drums take from a sound, and the rhythm ROM.
 * On the Expert grid [org.thortracker.editor.ViewState.programField] is the operator (0..3).
 */
object NesEpsmPage {
    /** The YMF288's envelope generator clock: 8 MHz / 144 (its FM sample rate) / 3. */
    val TIMING = OpnTiming(8_000_000f / 144f / 3f)

    /** The FM rows and the Mega Drive editor rows they are ([YmInstrumentView.step] edits both). */
    val OPN: Map<Row, YmRow> = Row.entries.filter { it.name.startsWith("OPN_") }.associateWith { YmRow.valueOf(it.name.removePrefix("OPN_")) }

    /** The Expert grid: one operator register field per row, the four operators side by side. */
    val GRID: List<Row> = OPN.filterValues { it in YmInstrumentView.GRID }.keys.toList()

    val SOUND_ROWS = listOf(Row.OPN_PRESET, Row.OPN_ALGORITHM, Row.OPN_RATIO, Row.OPN_BRIGHTNESS, Row.OPN_LEVEL, Row.OPN_FEEDBACK,
        Row.OPN_ATTACK, Row.OPN_DECAY, Row.OPN_SUSTAIN, Row.OPN_SUSTAIN_RATE, Row.OPN_RELEASE, Row.OPN_TONE_DECAY, Row.OPN_KEY_SCALE,
        Row.OPN_VIBRATO, Row.OPN_TREMOLO, Row.OPN_SSG, Row.EPSM_PAN)
    val EXPERT_ROWS = listOf(Row.OPN_RAW_ALGORITHM, Row.OPN_RAW_FEEDBACK, Row.EPSM_PAN, Row.OPN_AMS, Row.OPN_FMS) + GRID
    val ENV_ROWS = listOf(Row.ENV_SHAPE, Row.ENV_AUTO, Row.ENV_OCTAVE, Row.ENV_PERIOD)
    val RHYTHM_ROWS = listOf(Row.RHYTHM_LEVEL, Row.EPSM_PAN, Row.RHYTHM_ROM)

    /** Every row of the EPSM pages (all of them are about the whole sound, not one step envelope). */
    val ALL_ROWS: Set<Row> = (SOUND_ROWS + EXPERT_ROWS + ENV_ROWS + RHYTHM_ROWS).toSet()

    /** The Mega Drive FM presets, as starting points for the patch (the same OPN registers; the YMF288's clock is 4% faster). */
    val PRESETS: List<YmPresets.Preset> = YmPresets.ALL.filter { it.category == YmPresets.Category.FM && it.instrument is YmFmInstrument }

    /** Pan choices (register $B4 bits 7 and 6, also the rhythm drums' L/R): both, left, right, off. */
    private val PANS = listOf(true to true, true to false, false to true, false to false)

    /** The YMF288's LFO rates (register $22 bits 0-2) at 8 MHz. */
    private val LFO_HZ = listOf("3.98", "5.56", "6.02", "6.37", "6.88", "9.63", "48.1", "72.2")

    /** The six rhythm drums, in channel order (YM2608 BD, SD, TOP, HH, TOM, RIM). */
    val DRUM_REGISTERS = listOf("BD", "SD", "TOP", "HH", "TOM", "RIM")

    fun operator(state: org.thortracker.editor.ViewState): Int = state.programField.coerceIn(0, 3)

    fun label(row: Row): String = when (row) {
        Row.OPN_FMS -> "PMS"
        Row.EPSM_PAN -> "Pan"
        Row.RHYTHM_LEVEL -> "Level"
        Row.RHYTHM_ROM -> "Rhythm ROM"
        Row.ENV_SHAPE -> "Shape"
        Row.ENV_AUTO -> "Period"
        Row.ENV_OCTAVE -> "Octave"
        Row.ENV_PERIOD -> "Manual"
        else -> OPN[row]?.label ?: row.name
    }

    // ---- reading ------------------------------------------------------------------------------------------

    /** The song's one chip LFO ($22: bit 3 on, bits 0-2 rate), as the FM rows mention it. */
    fun lfoText(lfo: Int): String = if (lfo and 8 == 0) "off" else "rate ${lfo and 7} (${LFO_HZ[lfo and 7]} Hz)"

    fun panText(p: EpsmPatch): String = when {
        p.left && p.right -> "both   centre"
        p.left -> "left only"
        p.right -> "right only"
        else -> "off   silent: neither output"
    }

    /** The first volume step a rhythm drum plays at (0-15; empty = 15). */
    fun rhythmLevel(ins: NesInstrument): Int = ins.volume.values.firstOrNull() ?: 15

    fun romText(): String = if (Ym2608RhythmRom.installed) "installed (SHA-256 verified)   ${RhythmRomMenu.LABEL} replaces it" else "missing: ${Ym2608RhythmRom.MISSING}"

    /** The preset the patch is (pan aside), or null. */
    fun preset(p: EpsmPatch): YmPresets.Preset? = p.asYm().let { ym -> PRESETS.firstOrNull { (it.instrument as YmFmInstrument).copy(name = "") == ym } }

    /** A row of the EPSM pages as the editor shows it, for sound [ins] of [song]. */
    fun value(song: Song, ins: NesInstrument, row: Row): String {
        val p = ins.epsm
        val e = ins.epsmEnvelope
        return when (row) {
            Row.EPSM_PAN -> panText(p)
            Row.RHYTHM_LEVEL -> rhythmLevel(ins).let { "$it   written as drum level ${it * 2} of 31 (Cxx scales it); the volume envelope's first step" }
            Row.RHYTHM_ROM -> romText()
            Row.ENV_SHAPE -> if (e.shape == S5bShape.OFF) "Off: the volume envelope sets the level" else
                "${e.shape.label} ${e.shape.picture}: ${if (e.shape.repeats) "repeats while the note holds" else "once per note"}; each attack restarts it"
            Row.ENV_AUTO -> if (e.autoPitch) "Auto: the period follows the note" else "Manual: a fixed period (Ixx/Jyy change it)"
            Row.ENV_OCTAVE -> if (e.autoPitch) "${e.octave}: ${EpsmSsg.octaveText(e.shape, e.octave)}" else "${e.octave} (auto only)"
            Row.ENV_PERIOD -> if (e.autoPitch) "${e.period} (manual only)" else "${e.period}: ${EpsmSsg.periodText(e.shape, e.period)}"
            in OPN -> opnValue(song, p, row)
            else -> ""
        }
    }

    /** Rows shown dimmed: settings that do nothing as they stand. */
    fun dim(song: Song, ins: NesInstrument, row: Row): Boolean {
        val k = YmCurated.controls(ins.epsm.asYm())
        val e = ins.epsmEnvelope
        return when (row) {
            Row.OPN_BRIGHTNESS, Row.OPN_TONE_DECAY -> k.brightness < 0
            Row.OPN_VIBRATO, Row.OPN_TREMOLO, Row.OPN_AMS, Row.OPN_FMS -> song.nes.epsmLfo and 8 == 0
            Row.EPSM_PAN -> !ins.epsm.left || !ins.epsm.right
            Row.RHYTHM_ROM -> !Ym2608RhythmRom.installed
            Row.ENV_SHAPE -> e.shape == S5bShape.OFF
            Row.ENV_OCTAVE -> !e.autoPitch
            Row.ENV_PERIOD -> e.autoPitch
            else -> false
        }
    }

    private fun opnValue(song: Song, p: EpsmPatch, row: Row): String {
        val ins = p.asYm()
        val k = YmCurated.controls(ins)
        val lfoOff = song.nes.epsmLfo and 8 == 0
        fun approx(exact: Boolean) = if (exact) "" else "≈ "
        val slDb = OpnTiming.slDb(15 - k.sustain)
        return when (OPN.getValue(row)) {
            YmRow.PRESET -> preset(p)?.name ?: "custom   (none of the presets)"
            YmRow.ALGORITHM -> YmCurated.ALGORITHMS[k.algorithm].let { "${it.number} ${it.name}   ${it.diagram}" }
            YmRow.RATIO -> approx(k.ratioExact) + YmCurated.RATIOS[k.ratio].name + if (k.ratioExact) "" else "   (now " +
                ins.operators.joinToString(":") { YmCurated.multText(it.mul) + if (YmCurated.detuneSteps(it.dt) != 0) "(${YmCurated.detuneText(it.dt)})" else "" } + ")"
            YmRow.BRIGHTNESS -> if (k.brightness < 0) "—   additive: no modulators" else "${k.brightness}   ${FmInstrumentPainter.bar(k.brightness, 127)}"
            YmRow.LEVEL -> "${k.level}   ${FmInstrumentPainter.bar(k.level, 127)}"
            YmRow.FEEDBACK -> "${k.feedback}   ${if (k.feedback == 0) "pure" else FmInstrumentPainter.bar(k.feedback, 7)}"
            YmRow.ATTACK -> approx(k.envelopeExact) + "${k.attack}   ${FmInstrumentPainter.timeText(TIMING.attackMs(k.attack))} to full"
            YmRow.DECAY -> approx(k.envelopeExact) + "${k.decay}   ${FmInstrumentPainter.timeText(TIMING.fallMs(2 * k.decay, slDb))} to sustain"
            YmRow.SUSTAIN -> approx(k.envelopeExact) + when (k.sustain) {
                15 -> "15   held at full level"
                0 -> "0   percussive: decays to silence"
                else -> "${k.sustain}   at −${slDb.toInt()} dB"
            }
            YmRow.SUSTAIN_RATE -> approx(k.envelopeExact) + if (k.sustainRate == 0) "0   held while the key is down"
                else "${k.sustainRate}   keeps fading: −96 dB in ${FmInstrumentPainter.timeText(TIMING.fallMs(2 * k.sustainRate, 96f))}"
            YmRow.RELEASE -> approx(k.envelopeExact) + "${k.release}   ${FmInstrumentPainter.timeText(TIMING.fallMs(4 * k.release + 2, 96f))} after OFF"
            YmRow.TONE_DECAY -> if (k.toneDecay < 0) "—   additive: no modulators"
                else approx(k.envelopeExact) + "${k.toneDecay}   brightness fades in ${FmInstrumentPainter.timeText(TIMING.fallMs(2 * k.toneDecay, 96f))}"
            YmRow.KEY_SCALE -> approx(k.keyScaleExact) + YmCurated.KEY_SCALES[k.keyScale.coerceIn(0, 3)]
            YmRow.VIBRATO -> YmCurated.FMS_TEXT[k.vibrato] + if (k.vibrato > 0 && lfoOff) "   (song LFO off)" else ""
            YmRow.TREMOLO -> approx(k.tremoloExact) + YmCurated.AMS_TEXT[k.tremolo] + if (k.tremolo > 0 && lfoOff) "   (song LFO off)" else ""
            YmRow.SSG -> approx(k.ssgExact) + YmCurated.SSG_PRESETS[k.ssg].let { s ->
                s.name + listOfNotNull(
                    if (s.modulators != 0) "mod ${ssgShape(s.modulators)}" else null,
                    if (s.carriers != 0) "car ${ssgShape(s.carriers)}" else null,
                ).joinToString(" · ").let { if (it.isEmpty()) "" else "   $it" }
            }
            YmRow.RAW_ALGORITHM -> "${p.algorithm}   ${YmCurated.ALGORITHMS[p.algorithm.coerceIn(0, 7)].diagram}"
            YmRow.RAW_FEEDBACK -> "${p.feedback}   operator 1"
            YmRow.AMS -> if (p.ams == 0) "0   off" else "${p.ams}   ${YmCurated.AMS_TEXT[p.ams.coerceIn(0, 3)]} tremolo on AM operators"
            YmRow.FMS -> if (p.pms == 0) "0   off" else "${p.pms}   ${YmCurated.FMS_TEXT[p.pms.coerceIn(0, 7)]} vibrato"
            else -> ""
        }
    }

    /** One register field of operator [o] as the Expert grid shows it. */
    fun gridText(o: YmOperator, row: Row): String = when (OPN[row]) {
        YmRow.OP_DT -> "${o.dt} ${YmCurated.detuneText(o.dt)}"
        YmRow.OP_MUL -> "${NumberText.hex(o.mul)} ×${YmCurated.multText(o.mul)}"
        YmRow.OP_TL -> "${EditorSession.hex2(o.tl)} ${NumberText.fixed(o.tl * 0.75, 1)}dB"
        YmRow.OP_KS -> o.ks.toString()
        YmRow.OP_AR -> EditorSession.hex2(o.ar)
        YmRow.OP_AM -> if (o.am) "■ on" else "·"
        YmRow.OP_D1R -> EditorSession.hex2(o.d1r)
        YmRow.OP_D2R -> EditorSession.hex2(o.d2r)
        YmRow.OP_SL -> "${NumberText.hex(o.sl)} −${OpnTiming.slDb(o.sl).toInt()}dB"
        YmRow.OP_RR -> NumberText.hex(o.rr)
        YmRow.OP_SSG -> if (o.ssgEg < 8) "·" else "${NumberText.hex(o.ssgEg)} ${ssgShape(o.ssgEg)}"
        else -> ""
    }

    /** An SSG-EG value's outline ("\\\\", "/‾‾‾"…). */
    fun ssgShape(v: Int): String = YmCurated.SSG_EG_NAMES[v]?.substringBefore(' ') ?: "?"

    // ---- editing ------------------------------------------------------------------------------------------

    /** Edit+direction on an EPSM row; false when [row] is not one. */
    fun adjust(v: ViewController, row: Row, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        when (row) {
            Row.OPN_PRESET -> applyPreset(v, d)
            Row.EPSM_PAN -> session.editNesEpsm(slot, "Set pan") { stepPan(it, d) }
            in OPN -> {
                val ym = OPN.getValue(row)
                session.editNesEpsm(slot, "Set ${label(row).lowercase()}") { it.withYm(YmInstrumentView.step(it.asYm(), ym, operator(st), dx, dy)) }
                fmNote(v, row)
            }
            Row.RHYTHM_LEVEL -> setRhythmLevel(v, rhythmLevel(session.song.nesInstrument(slot) ?: return true) + dx + 4 * dy)
            Row.RHYTHM_ROM -> Unit
            Row.ENV_SHAPE -> stepShape(v, dx - dy)
            Row.ENV_AUTO -> if (d != 0) session.editNesEpsmEnvelope(slot, "Set SSG envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
            Row.ENV_OCTAVE -> session.editNesEpsmEnvelope(slot, "Set SSG envelope octave") { it.copy(octave = it.octave + dx + 4 * dy) }
            Row.ENV_PERIOD -> session.editNesEpsmEnvelope(slot, "Set SSG envelope period") { it.copy(period = octaveOf(it.period + dx, dy)) }
            else -> return false
        }
        return true
    }

    /** An Edit tap on an EPSM row; false when [row] is not one. */
    fun editTap(v: ViewController, row: Row): Boolean {
        val session = v.session
        val slot = v.state.instrument
        when (row) {
            Row.OPN_PRESET -> applyPreset(v, 1)
            Row.OPN_OP_AM -> session.editNesEpsm(slot, "Set AM") { it.withYm(YmInstrumentView.step(it.asYm(), YmRow.OP_AM, operator(v.state), 1, 0)) }
            Row.EPSM_PAN -> session.editNesEpsm(slot, "Set pan") { stepPan(it, 1) }
            Row.RHYTHM_ROM -> session.requestMenu { RhythmRomMenu.open(it) }
            Row.ENV_SHAPE -> stepShape(v, 1)
            Row.ENV_AUTO -> session.editNesEpsmEnvelope(slot, "Set SSG envelope period mode") { it.copy(autoPitch = !it.autoPitch) }
            in ALL_ROWS -> Unit
            else -> return false
        }
        return true
    }

    /** Clear on an EPSM row: the setting's default (FamiStudio's Default patch, the EPSM envelope defaults); false when not one. */
    fun clear(v: ViewController, row: Row): Boolean {
        val session = v.session
        val slot = v.state.instrument
        val op = operator(v.state)
        val blank = EpsmPatch.DEFAULT.asYm()
        val reset = YmCurated.controls(blank)
        val defaults = S5bEnvelope.EPSM_DEFAULT
        fun fm(label: String, f: (YmFmInstrument) -> YmFmInstrument) { session.editNesEpsm(slot, label) { it.withYm(f(it.asYm())) } }
        when (OPN[row]) {
            YmRow.PRESET -> session.editNesEpsm(slot, "Reset FM patch") { EpsmPatch.DEFAULT.copy(left = it.left, right = it.right) }
            YmRow.ALGORITHM -> fm("Reset algorithm") { YmCurated.withAlgorithm(it, reset.algorithm) }
            YmRow.RATIO -> fm("Reset ratios") { YmCurated.withRatio(it, reset.ratio) }
            YmRow.BRIGHTNESS -> fm("Reset brightness") { YmCurated.withBrightness(it, maxOf(0, reset.brightness)) }
            YmRow.LEVEL -> fm("Reset level") { YmCurated.withLevel(it, reset.level) }
            YmRow.FEEDBACK, YmRow.RAW_FEEDBACK -> fm("Feedback off") { it.copy(feedback = 0) }
            YmRow.ATTACK -> fm("Reset attack") { YmCurated.withAttack(it, reset.attack) }
            YmRow.DECAY -> fm("Reset decay") { YmCurated.withDecay(it, reset.decay) }
            YmRow.SUSTAIN -> fm("Reset sustain") { YmCurated.withSustain(it, reset.sustain) }
            YmRow.SUSTAIN_RATE -> fm("Sustain rate off") { YmCurated.withSustainRate(it, 0) }
            YmRow.RELEASE -> fm("Reset release") { YmCurated.withRelease(it, reset.release) }
            YmRow.TONE_DECAY -> fm("Reset tone decay") { YmCurated.withToneDecay(it, maxOf(0, reset.toneDecay)) }
            YmRow.KEY_SCALE -> fm("Key scaling off") { YmCurated.withKeyScale(it, 0) }
            YmRow.VIBRATO, YmRow.FMS -> fm("Vibrato off") { YmCurated.withVibrato(it, 0) }
            YmRow.TREMOLO -> fm("Tremolo off") { YmCurated.withTremolo(it, 0) }
            YmRow.AMS -> fm("AMS off") { it.copy(ams = 0) }
            YmRow.SSG -> fm("SSG-EG off") { YmCurated.withSsg(it, 0) }
            YmRow.RAW_ALGORITHM -> fm("Reset algorithm") { it.copy(algorithm = blank.algorithm) }
            else -> if (row in GRID) {
                fm("Reset ${label(row)}") { it.withOperator(op, resetField(it.operators[op], blank.operators[op], OPN.getValue(row))) }
            } else when (row) {
                Row.EPSM_PAN -> session.editNesEpsm(slot, "Set pan") { it.copy(left = true, right = true) }
                Row.RHYTHM_LEVEL -> session.editNesEnvelope(slot, NesEnvelopeKind.VOLUME, "Reset rhythm level") {
                    if (it.values.size <= 1) NesEnvelope() else NesEnvelopes.withValue(NesEnvelopeKind.VOLUME, it, 0, 15, NesChip.EPSM)
                }
                Row.RHYTHM_ROM -> Unit
                Row.ENV_SHAPE -> session.editNesEpsmEnvelope(slot, "Set SSG envelope shape") { it.copy(shape = defaults.shape) }
                Row.ENV_AUTO -> session.editNesEpsmEnvelope(slot, "Set SSG envelope period mode") { it.copy(autoPitch = defaults.autoPitch) }
                Row.ENV_OCTAVE -> session.editNesEpsmEnvelope(slot, "Set SSG envelope octave") { it.copy(octave = defaults.octave) }
                Row.ENV_PERIOD -> session.editNesEpsmEnvelope(slot, "Set SSG envelope period") { it.copy(period = defaults.period) }
                else -> return false
            }
        }
        return true
    }

    private fun resetField(o: YmOperator, d: YmOperator, row: YmRow): YmOperator = when (row) {
        YmRow.OP_DT -> o.copy(dt = d.dt)
        YmRow.OP_MUL -> o.copy(mul = d.mul)
        YmRow.OP_TL -> o.copy(tl = d.tl)
        YmRow.OP_KS -> o.copy(ks = d.ks)
        YmRow.OP_AR -> o.copy(ar = d.ar)
        YmRow.OP_AM -> o.copy(am = d.am)
        YmRow.OP_D1R -> o.copy(d1r = d.d1r)
        YmRow.OP_D2R -> o.copy(d2r = d.d2r)
        YmRow.OP_SL -> o.copy(sl = d.sl)
        YmRow.OP_RR -> o.copy(rr = d.rr)
        YmRow.OP_SSG -> o.copy(ssgEg = d.ssgEg)
        else -> o
    }

    private fun stepPan(p: EpsmPatch, delta: Int): EpsmPatch {
        val i = PANS.indexOf(p.left to p.right).coerceAtLeast(0)
        val (l, r) = PANS[(i + delta).mod(PANS.size)]
        return p.copy(left = l, right = r)
    }

    /** Sets the rhythm level: the volume envelope's first step (an empty envelope gets one step). */
    private fun setRhythmLevel(v: ViewController, level: Int) {
        v.session.editNesEnvelope(v.state.instrument, NesEnvelopeKind.VOLUME, "Set rhythm level") {
            NesEnvelopes.withValue(NesEnvelopeKind.VOLUME, it, 0, level, NesChip.EPSM)
        }
    }

    /** Presets [delta] steps from the one the patch is (or the first): the patch's OPN fields, its pan kept. */
    private fun applyPreset(v: ViewController, delta: Int) {
        val ins = v.session.song.nesInstrument(v.state.instrument) ?: return
        val current = preset(ins.epsm)?.let { PRESETS.indexOf(it) } ?: -1
        val next = PRESETS[org.thortracker.system.fm.FmControls.presetStep(current, delta, PRESETS.size)]
        val ym = next.instrument as YmFmInstrument
        v.session.editNesEpsm(v.state.instrument, "Preset ${next.name}") { it.withYm(ym) }
        v.session.note("${next.name}: ${YmCurated.summary(ym)} (a Mega Drive preset: the YMF288 has the same FM operators)")
    }

    /** A status line where a list's choice says more than its row: algorithm routing, an LFO depth with the song's LFO off. */
    private fun fmNote(v: ViewController, row: Row) {
        val ins = v.session.song.nesInstrument(v.state.instrument) ?: return
        when (OPN[row]) {
            YmRow.ALGORITHM, YmRow.RAW_ALGORITHM -> YmCurated.ALGORITHMS[ins.epsm.algorithm.coerceIn(0, 7)].let { a ->
                v.session.note("Algorithm ${a.number}: ${a.name} ${a.diagram} · carriers ${a.carriers.joinToString("+")}")
            }
            YmRow.VIBRATO, YmRow.TREMOLO, YmRow.AMS, YmRow.FMS -> if (v.session.song.nes.epsmLfo and 8 == 0) {
                v.session.note("The song's LFO is off (Song view, LFO): vibrato and tremolo are silent until it is on")
            }
            else -> Unit
        }
    }

    /** Steps the SSG hardware envelope shape: off, then `$08-$0F`. */
    private fun stepShape(v: ViewController, delta: Int) {
        val shapes = S5bShape.entries
        v.session.editNesEpsmEnvelope(v.state.instrument, "Set SSG envelope shape") {
            it.copy(shape = shapes[(shapes.indexOf(it.shape) + delta).coerceIn(0, shapes.size - 1)])
        }
    }

    /** Halves or doubles a manual period (an octave of envelope rate per step), at least 1. */
    private fun octaveOf(period: Int, up: Int): Int = when {
        up > 0 -> maxOf(1, period) * 2
        up < 0 -> maxOf(1, period / 2)
        else -> period
    }

    /** Hints for an EPSM row; false when [row] is not one. */
    fun hints(v: ViewController, row: Row, h: MutableList<Hint>): Boolean {
        when {
            row == Row.OPN_PRESET -> { h += Hint(v.editLr(), "Start from a Mega Drive FM preset"); h += Hint(v.k(Action.CLEAR), "Default patch") }
            row in GRID -> {
                h += Hint(v.lr(), "Operator")
                if (row == Row.OPN_OP_AM) h += Hint(v.k(Action.EDIT), "Switch AM") else h += Hint(v.editLr(), "${label(row)} ±1 (${v.ud()} coarse)")
                h += Hint(v.k(Action.CLEAR), "Default")
            }
            row == Row.OPN_ALGORITHM || row == Row.OPN_RATIO || row == Row.OPN_KEY_SCALE || row == Row.OPN_SSG || row == Row.OPN_RAW_ALGORITHM ->
                { h += Hint(v.editLr(), "Choose"); h += Hint(v.k(Action.CLEAR), "Default") }
            row in OPN -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
            row == Row.EPSM_PAN -> { h += Hint(v.editLr(), "Both / left / right / off"); h += Hint(v.k(Action.CLEAR), "Both") }
            row == Row.RHYTHM_LEVEL -> { h += Hint(v.editLr(), "Level ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "15") }
            row == Row.RHYTHM_ROM -> h += Hint(v.k(Action.EDIT), if (Ym2608RhythmRom.installed) "Replace the rhythm ROM…" else "Import the rhythm ROM…")
            row == Row.ENV_SHAPE -> { h += Hint(v.editLr(), "Shape"); h += Hint(v.k(Action.CLEAR), "Off") }
            row == Row.ENV_AUTO -> { h += Hint(v.k(Action.EDIT), "Auto / manual period"); h += Hint(v.k(Action.CLEAR), "Auto") }
            row == Row.ENV_OCTAVE -> { h += Hint(v.editLr(), "Octave ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "${S5bEnvelope.EPSM_DEFAULT.octave}") }
            row == Row.ENV_PERIOD -> { h += Hint(v.editLr(), "Period ±1 (${v.ud()} ×2 / ÷2)"); h += Hint(v.k(Action.CLEAR), "${S5bEnvelope.EPSM_DEFAULT.period}") }
            else -> return false
        }
        if (row !in GRID) h += Hint(v.lr(), "Sound slot")
        return true
    }

    // ---- help ---------------------------------------------------------------------------------------------

    /** Help rows for EPSM sounds. */
    fun helpRows(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val editLr = keys.held(Action.EDIT, lr)
        return listOf(
            "EPSM sounds" to listOf(
                "Chip" to "On songs with the EPSM (a Yamaha YMF288, OPN3-L): whether the sound plays on the 2A03's channels or on the " +
                    "EPSM's ($editLr there). One EPSM sound plays on all of them: SSG 1-3, FM 1-6 and the six rhythm drums",
                "Pages" to "Volume, Arpeggio, Pitch (every EPSM channel but the drums), Mixer and Noise (SSG), SSG env (the hardware " +
                    "envelope), FM (curated), FM Expert (every register) and Rhythm. No duty, no DPCM page",
                "Pitch" to "SSG and FM notes sound at their true pitch, C-1 to B-7 (FamiStudio shows them an octave higher)",
                "Listen on" to "Showing an FM, SSG or rhythm page moves Listen to a channel of that kind",
            ) + EPSM_PAGES.map { it.label to NesInstrumentView.envelopeSummary(it, NesChip.EPSM) },
            "EPSM SSG" to listOf(
                "Mixer" to "Per frame: tone+noise, noise, tone, or off (the channel holds a constant level). Empty: tone",
                "Noise" to "The noise period per frame, 0-31 (higher is lower). The three SSG channels share one noise generator",
                "SSG env page" to "The chip's one envelope generator: a shape (saw, triangle, or a one-shot attack/decay) that replaces " +
                    "the volume envelope and Cxx; each note with attack restarts it. Auto: its period follows the note, shifted by the " +
                    "octave (4 = a saw in unison, 5 = a triangle in unison, 3 an octave below: buzz basses). Manual: a fixed period " +
                    "(one ramp = 128 µs × period), changed by Ixx/Jyy in the pattern",
                "Shared" to "SSG 1-3 share the envelope generator and the noise generator: each frame FamiStudio's engine takes the " +
                    "shape and period from the highest-numbered SSG channel sounding an envelope sound, and the noise rate from the " +
                    "highest-numbered one with noise on",
                "${keys.held(Action.EDIT, lr)} on the page" to "Shape, auto/manual, octave ±1 (${keys.held(Action.EDIT, ud)} ±4), " +
                    "period ±1 (${keys.held(Action.EDIT, ud)}: × 2 / ÷ 2)",
            ),
            "EPSM FM" to listOf(
                "FM page" to "The Mega Drive editor's curated controls over the same OPN registers: preset (the Mega Drive's FM presets), " +
                    "algorithm, ratios, brightness, level, feedback, attack, decay, sustain, sustain rate, release, tone decay, key " +
                    "scaling, vibrato, tremolo, SSG-EG, pan. A value marked ≈ was hand-edited on the Expert page",
                "FM Expert" to "Algorithm, feedback, pan, AMS and PMS as stored, then per operator DT, MUL, TL (level, 0 loudest), KS, " +
                    "AR, AM ($edit switches), D1R, D2R, SL, RR and SSG-EG; $lr picks the operator. Carriers are marked ♪",
                "Pan" to "The patch's left/right outputs (register \$B4): both, left, right or off (silent). The rhythm drums use the same bits",
                "LFO" to "Vibrato (PMS) and tremolo (AMS) need the song's LFO (Song view, LFO): the YMF288 has one LFO for all six FM " +
                    "channels, so it is a song setting, written into every EPSM sound when exported",
                "Volume" to "On FM the volume envelope and Cxx lower the carriers' level",
            ),
            "EPSM rhythm" to listOf(
                "Drums" to "Kick, Snare, Cymbal, Hi-hat, Tom and Rim are six channels, each playing one fixed drum from the YMF288's " +
                    "rhythm ROM: a note triggers it, its pitch does not change the drum",
                "Level" to "The volume envelope's first step, 0-15 (empty = 15), written as the drum's level ×2; Cxx scales it. " +
                    "Arpeggio, pitch, mixer, noise and the FM patch do not apply",
                "Pan" to "The sound's pan (the same L/R bits as its FM pan)",
                "Rhythm ROM" to "The drums need the YM2608 rhythm ROM (ym2608_adpcm_rom.bin, 8192 bytes), which Thor Tracker does not " +
                    "include: import your own copy with \"${RhythmRomMenu.LABEL}\" (main menu, or $edit on the Rhythm page's ROM row). It is " +
                    "checked (size and SHA-256), kept on this device and never written into projects or exports. Without it, songs " +
                    "whose drums play are refused with a message; FM and SSG play without it",
            ),
        )
    }

    private val EPSM_PAGES = listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.MIXER, NesEnvelopeKind.NOISE)
}

/**
 * The EPSM's SSG (YM2149-compatible; 8 MHz / 4 = 2 MHz SSG clock: a tone period P sounds 2 MHz / 16P, the envelope
 * steps 32 times per ramp at 8 SSG clocks × its period, so one ramp lasts 128 µs × period).
 */
object EpsmSsg {
    const val CLOCK_HZ = 2_000_000.0

    /** Seconds one ramp (32 steps) lasts at [period] (period 0 acts as 1). */
    fun rampSeconds(period: Int): Double = 256.0 * maxOf(1, period) / CLOCK_HZ

    /** Repeating shapes: cycles per second at [period] (a triangle takes two ramps). */
    fun cycleHz(shape: S5bShape, period: Int): Double = 1.0 / (rampSeconds(period) * if (shape.triangle) 2 else 1)

    /** "286 ms", "4.6 s", "71 µs": a duration. */
    fun duration(seconds: Double): String = when {
        seconds >= 1.0 -> "${NumberText.fixed(seconds, 1)} s"
        seconds >= 0.001 -> "${(seconds * 1000).roundToInt()} ms"
        else -> "${(seconds * 1e6).roundToInt()} µs"
    }

    /** "3.5 Hz", or for audible rates "437 Hz ≈ A-4". */
    fun frequency(hz: Double): String {
        val text = if (hz >= 100) "${hz.roundToInt()} Hz" else "${NumberText.fixed(hz, 1)} Hz"
        if (hz < 20.0) return text
        val note = (12 * log2(hz / 440.0) + 57).roundToInt()
        return if (note in 0..127) "$text ≈ ${NesSystem.noteName(note)}" else text
    }

    /** What a manual [period] does with [shape]: a repeating shape's rate, a one-shot shape's ramp time. */
    fun periodText(shape: S5bShape, period: Int): String = when {
        shape == S5bShape.OFF -> "${duration(rampSeconds(period))} per ramp (no shape chosen)"
        shape.repeats -> "${frequency(cycleHz(shape, period))} (${duration(rampSeconds(period))} per ramp)"
        else -> "${duration(rampSeconds(period))} ramp"
    }

    /**
     * What auto-period [octave] makes of [shape] against the note: the envelope period is the note's tone period shifted
     * right by [octave] (left when negative); a saw at period E repeats at 2 MHz / 256E, a tone at period P sounds
     * 2 MHz / 16P, so octave 4 puts a saw in unison with the note and octave 5 a triangle.
     */
    fun octaveText(shape: S5bShape, octave: Int): String {
        if (shape == S5bShape.OFF) return "period = note period ${shift(octave)} (no shape chosen)"
        if (!shape.repeats) {
            return "ramp = ${if (octave >= 0) "1/${1 shl octave.coerceAtMost(8)}" else "${1 shl (-octave).coerceAtMost(8)}×"} of 16 note cycles"
        }
        val rel = octave - if (shape.triangle) 5 else 4
        val pitch = when {
            rel == 0 -> "in unison with the note"
            rel > 0 -> "$rel octave${if (rel == 1) "" else "s"} above the note"
            else -> "${-rel} octave${if (rel == -1) "" else "s"} below the note"
        }
        return "a ${if (shape.triangle) "triangle" else "saw"} $pitch"
    }

    private fun shift(octave: Int) = when {
        octave > 0 -> "÷ ${1 shl octave}"
        octave < 0 -> "× ${1 shl -octave}"
        else -> "itself"
    }

    /** A mixer value as the editor writes it. */
    fun mixerText(value: Int): String = when (value) {
        0 -> "tone+noise"
        1 -> "noise"
        2 -> "tone"
        3 -> "off"
        else -> "?"
    }
}
