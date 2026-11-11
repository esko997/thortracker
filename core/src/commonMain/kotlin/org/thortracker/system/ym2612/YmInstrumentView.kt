package org.thortracker.system.ym2612

import org.thortracker.backend.YmPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.fm.FmControls
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The Mega Drive instrument editor: one bank of [YmSystem.SLOTS] sounds, each empty, an FM voice, a PSG sound or a PCM
 * kit. FM opens on the musical [YmSoundFace]; Sound/Advanced selects a pane-local face without changing the patch.
 * Advanced head rows: slot, name, the channel Listen plays on, type, page. An FM voice has the shared FM pages:
 * Curated controls of [YmCurated] beside the algorithm and envelope; Expert, every OPN2 operator register; Ch3,
 * FM 3's mode and four operator pitch offsets (heard on
 * FM 3 in special mode); *Drum*, the pitch sweep. A PSG sound has the NES editor's envelopes (volume, arpeggio, pitch,
 * edited step by step) and a *Noise·Drum* page (noise type and rate for the Noise channel, the sweep). A PCM kit has
 * one *PCM* page ([YmPcmPage]): its level, keys mapped to samples and the song's samples.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep] (Expert/Ch3: the operator 0..3; envelopes: the step, size = append),
 * [ViewState.listenChannel], [ViewState.dpcmKey]/[ViewState.dpcmSample] (PCM page).
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object YmInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: NesEnvelopeKind? = null) {
        SOUND("Curated"), EXPERT("Expert"), CH3("Ch3"), DRUM("Drum"),
        VOLUME("Volume", NesEnvelopeKind.VOLUME), ARPEGGIO("Arpeggio", NesEnvelopeKind.ARPEGGIO), PITCH("Pitch", NesEnvelopeKind.PITCH),
        NOISE("Noise·Drum"), PCM("PCM"),
    }

    /** What a slot holds, or (empty) what Listen's channel plays. */
    enum class Kind { FM, PSG, PCM }

    val FM_PAGES = listOf(Page.SOUND, Page.EXPERT, Page.CH3, Page.DRUM)
    val PSG_PAGES = listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.NOISE)
    val PCM_PAGES = listOf(Page.PCM)

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), TYPE("Type"), PAGE("Page"),
        PRESET("Preset"), ALGORITHM("Algorithm"), RATIO("Ratios"), BRIGHTNESS("Brightness"), LEVEL("Level"), FEEDBACK("Feedback"),
        ATTACK("Attack"), DECAY("Decay"), SUSTAIN("Sustain"), SUSTAIN_RATE("Sustain rate"), RELEASE("Release"),
        TONE_DECAY("Tone decay"), KEY_SCALE("Key scaling"), VIBRATO("Vibrato"), TREMOLO("Tremolo"), SSG("SSG-EG"),
        RAW_ALGORITHM("Algorithm"), RAW_FEEDBACK("Feedback"), AMS("AMS"), FMS("FMS"),
        OP_DT("DT"), OP_MUL("MUL"), OP_TL("TL"), OP_KS("KS"), OP_AR("AR"), OP_AM("AM"), OP_D1R("D1R"), OP_D2R("D2R"),
        OP_SL("SL"), OP_RR("RR"), OP_SSG("SSG"),
        FM3_MODE("FM 3 mode"), CH3_SEMI("Semitones"), CH3_FINE("Fine /64"),
        DRUM_PRESET("Drum preset"), SWEEP_START("Sweep start"), SWEEP_END("Sweep end"), SWEEP_SPEED("Sweep speed"),
        LENGTH("Length"), LOOP("Loop"), ENV_RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
        NOISE_TYPE("Noise"), NOISE_RATE("Noise rate"),
        PCM_LEVEL("Level"), PCM_KEY("Key"), PCM_SAMPLE("Sample"), PCM_PITCH("Pitch"), PCM_SPREAD("Spread"),
        PCM_SAMPLES("Samples"), PCM_NAME("Name"), PCM_ADD("Add"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.TYPE, Row.PAGE)

    /** The Expert grid rows: one register field each, all operators side by side. */
    val GRID = listOf(Row.OP_DT, Row.OP_MUL, Row.OP_TL, Row.OP_KS, Row.OP_AR, Row.OP_AM, Row.OP_D1R, Row.OP_D2R, Row.OP_SL,
        Row.OP_RR, Row.OP_SSG)

    /** The Ch3 grid: each operator's FM 3 pitch offset, semitones and 1/64 semitone. */
    val CH3_GRID = listOf(Row.CH3_SEMI, Row.CH3_FINE)

    /** Rows whose cursor moves between operators (←→, L1/R1). */
    private val OPERATOR_ROWS: Set<Row> = (GRID + CH3_GRID).toSet()

    private val SWEEP = listOf(Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED)

    private val ROWS = mapOf(
        Page.SOUND to HEAD + listOf(Row.PRESET, Row.ALGORITHM, Row.RATIO, Row.BRIGHTNESS, Row.LEVEL, Row.FEEDBACK, Row.ATTACK,
            Row.DECAY, Row.SUSTAIN, Row.SUSTAIN_RATE, Row.RELEASE, Row.TONE_DECAY, Row.KEY_SCALE, Row.VIBRATO, Row.TREMOLO, Row.SSG),
        Page.EXPERT to HEAD + listOf(Row.RAW_ALGORITHM, Row.RAW_FEEDBACK, Row.AMS, Row.FMS) + GRID,
        Page.CH3 to HEAD + listOf(Row.FM3_MODE) + CH3_GRID,
        Page.DRUM to HEAD + listOf(Row.DRUM_PRESET) + SWEEP,
        Page.VOLUME to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.STEPS),
        Page.ARPEGGIO to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.PITCH to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.MODE, Row.STEPS),
        Page.NOISE to HEAD + listOf(Row.PRESET, Row.NOISE_TYPE, Row.NOISE_RATE) + SWEEP,
        Page.PCM to HEAD + listOf(Row.PCM_LEVEL, Row.PCM_KEY, Row.PCM_SAMPLE, Row.PCM_PITCH, Row.PCM_SPREAD, Row.PCM_SAMPLES,
            Row.PCM_NAME, Row.PCM_ADD),
    )

    /** PCM page rows that are about the song's samples, not how the kit sounds. */
    private val SAMPLE_LIST_ROWS = setOf(Row.PCM_SAMPLES, Row.PCM_NAME, Row.PCM_ADD)

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = ROWS.values.flatMap { it.drop(HEAD.size) }.toSet() - Row.STEPS - SAMPLE_LIST_ROWS + Row.TYPE

    /** On/off register bits of the Expert grid: a tap switches them. */
    private val TOGGLES = setOf(Row.OP_AM)

    /** List controls: Edit+←→ chooses, wrapping. */
    private val LISTS = setOf(Row.ALGORITHM, Row.RATIO, Row.KEY_SCALE, Row.SSG, Row.RAW_ALGORITHM, Row.NOISE_TYPE, Row.NOISE_RATE, Row.FM3_MODE)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun sound(song: Song, state: ViewState): YmInstrument? = song.ymInstrument(state.instrument)

    /** The slot's kind: what it holds, or for an empty slot the kind Listen's channel plays. */
    fun kind(song: Song, state: ViewState): Kind = when (sound(song, state)) {
        is YmFmInstrument -> Kind.FM
        is YmPsgInstrument -> Kind.PSG
        is YmPcmInstrument -> Kind.PCM
        null -> kindOf(state.listenChannel)
    }

    /** The kind [channel] plays. */
    fun kindOf(channel: Int): Kind = when {
        YmRules.isFm(channel) -> Kind.FM
        YmRules.isPcm(channel) -> Kind.PCM
        else -> Kind.PSG
    }

    /** True when the slot's pages are FM's: an FM voice, or an empty slot while Listen is on an FM channel. */
    fun fmPages(song: Song, state: ViewState): Boolean = kind(song, state) == Kind.FM

    fun pages(song: Song, state: ViewState): List<Page> = when (kind(song, state)) {
        Kind.FM -> FM_PAGES
        Kind.PSG -> PSG_PAGES
        Kind.PCM -> PCM_PAGES
    }

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> = ROWS.getValue(page(song, state))

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The operator (0-based) the Expert or Ch3 grid's cursor is on. */
    fun operator(state: ViewState): Int = state.envStep.coerceIn(0, 3)

    /** The PSG envelope a page shows, or null. */
    fun envelopeKind(song: Song, state: ViewState): NesEnvelopeKind? = page(song, state).envelope

    /**
     * The channel a held Listen plays [ins] on: FM voices on an FM channel (FM 1 unless Listen is one; FM 3 on the Ch3
     * page, where the offsets sound), PSG sounds on PSG/Noise, PCM kits on PCM 1-3.
     */
    fun listenChannel(state: ViewState, ins: YmInstrument?, page: Page? = null): Int = when {
        ins is YmFmInstrument && page == Page.CH3 -> YmRules.FM3
        ins is YmFmInstrument && !YmRules.isFm(state.listenChannel) -> 0
        ins is YmPsgInstrument && !YmRules.isPsg(state.listenChannel) -> YmRules.PSG_FIRST
        ins is YmPcmInstrument && !YmRules.isPcm(state.listenChannel) -> YmRules.PCM_FIRST
        else -> state.listenChannel
    }

    /** The furthest step the cursor reaches on an envelope: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    private fun envelope(song: Song, state: ViewState): NesEnvelope? {
        val kind = envelopeKind(song, state) ?: return null
        return (sound(song, state) as? YmPsgInstrument)?.envelope(kind) ?: defaultYmPsg(state.instrument).envelope(kind)
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, YmSystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(if (fmPages(song, st)) -1 else 0,
            if (YmSoundFace.friendly(song, st)) YmSoundControl.entries.size + 1 else rows(song, st).size - 1)
        when {
            page(song, st) == Page.EXPERT || page(song, st) == Page.CH3 -> st.envStep = st.envStep.coerceIn(0, 3)
            envelopeKind(song, st) != null -> st.envStep = st.envStep.coerceIn(0, envelope(song, st)?.let(::lastStep) ?: 0)
            page(song, st) == Page.PCM -> YmPcmPage.normalise(v)
        }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        if (state.instrument != subject) state.ymPanel.invalidate()
        state.instrument = subject.coerceIn(1, YmSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.instrument != selection.instrument) state.ymPanel.invalidate()
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry, in the bank of its kind (FM voices, PSG sounds and PCM kits share the slot numbers). */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        val bank = when (sound(session.song, v.state)) {
            is YmPsgInstrument -> YmSystem.PSG_BANK
            is YmPcmInstrument -> YmSystem.PCM_BANK
            else -> YmSystem.FM_BANK
        }
        session.select(session.selection.copy(bank = bank, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        st.envelope = index.mod(pages(song, st).size)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
    }

    /** After the slot's kind may have changed: the page index stays within the kind's pages, the cursor on its row. */
    private fun keepPage(v: ViewController, row: Row) {
        val st = v.state
        val song = v.session.song
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: st.instField.coerceIn(0, rows.size - 1)
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, YmSystem.SLOTS)
        if (slot == st.instrument) return
        st.ymPanel.invalidate()
        val row = row(v.session.song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        keepPage(v, row)
        if (page(v.session.song, st) == Page.PCM) YmPcmPage.showFirstMapping(v)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (YmSoundFace.direction(v, dx, dy)) return
        val st = v.state
        val song = v.session.song
        val rows = rows(song, st)
        val row = row(song, st)
        val e = envelope(song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(if (fmPages(song, st)) -1 else 0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> { st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size); keepPage(v, row) }
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row in OPERATOR_ROWS -> st.envStep = (st.envStep + dx).coerceIn(0, 3)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            row == Row.PCM_KEY || row == Row.PCM_SAMPLES || row == Row.PCM_NAME -> YmPcmPage.direction(v, row, dx)
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        val row = row(session.song, st)
        val changed = when (row) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> {
                val cur = sound(session.song, st) ?: defaultYmInstrument(slot, st.listenChannel)
                session.setYmInstrument(slot, cur.named(NameEdit.edit(cur.name, st.textPos, dx + 26 * dy, NAME_MAX)), "Rename sound")
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); keepPage(v, row); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.TYPE -> switchType(v)
            Row.PRESET -> applyPreset(v, d, drums = false)
            Row.DRUM_PRESET -> applyPreset(v, d, drums = true)
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.LENGTH, Row.LOOP, Row.ENV_RELEASE -> {
                val kind = envelopeKind(session.song, st) ?: return
                val name = kind.label.lowercase()
                session.editYmEnvelope(slot, kind, "Set $name ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> NesEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy)
                        Row.LOOP -> NesEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy)
                        else -> NesEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
                    }
                }
            }
            Row.MODE -> session.editYmPsg(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.NOISE_TYPE -> session.editYmPsg(slot, "Set noise type") { it.copy(noise = it.noise xor YmPsgInstrument.WHITE) }
            Row.NOISE_RATE -> session.editYmPsg(slot, "Set noise rate") { it.copy(noise = (it.noise and YmPsgInstrument.WHITE) or ((it.noise and 3) + d).mod(4)) }
            in SWEEP -> if (fmPages(session.song, st)) session.editYmFm(slot, "Set ${row.label.lowercase()}") { it.copy(sweep = stepSweep(it.sweep, row, dx, dy)) }
                else session.editYmPsg(slot, "Set ${row.label.lowercase()}") { it.copy(sweep = stepSweep(it.sweep, row, dx, dy)) }
            Row.FM3_MODE -> d != 0 && stepFm3Mode(v, d)
            Row.CH3_SEMI, Row.CH3_FINE -> session.editYmFm(slot, "Set FM 3 operator ${operator(st) + 1} offset") {
                stepCh3(it, operator(st), row, dx, dy)
            }.also { if (it && session.song.ym.fm3Mode == YmFm3Mode.NORMAL) fm3Note(v) }
            Row.PCM_LEVEL, Row.PCM_KEY, Row.PCM_SAMPLE, Row.PCM_PITCH, Row.PCM_SPREAD, Row.PCM_SAMPLES, Row.PCM_NAME, Row.PCM_ADD ->
                YmPcmPage.adjust(v, row, dx, dy)
            else -> session.editYmFm(slot, "Set ${row.label.lowercase()}") { step(it, row, operator(st), dx, dy) }
        }
        if (changed) {
            curatedNote(v)
            heard(v)
        }
    }

    /** [s] with [row]'s value stepped: start/end by semitones (↑↓ octaves), speed by 1 (↑↓ 16). */
    fun stepSweep(s: YmSweep, row: Row, dx: Int, dy: Int): YmSweep = when (row) {
        Row.SWEEP_START -> (s.start + dx + 12 * dy).coerceIn(-96, 96).let { s.copy(start = it, speed = FmControls.sweepSpeed(s.speed, it, s.end)) }
        Row.SWEEP_END -> (s.end + dx + 12 * dy).coerceIn(-96, 96).let { s.copy(end = it, speed = FmControls.sweepSpeed(s.speed, s.start, it)) }
        else -> s.copy(speed = (s.speed + dx + 16 * dy).coerceIn(0, 255))
    }

    /** Operator [op]'s FM 3 offset stepped: semitones (↑↓ octaves) or 1/64 semitones (↑↓ eighths), within ±[YmPlayerImage.CH3_LIMIT]. */
    fun stepCh3(ins: YmFmInstrument, op: Int, row: Row, dx: Int, dy: Int): YmFmInstrument {
        val offsets = List(4) { ins.ch3.getOrElse(it) { 0 } }
        val step = if (row == Row.CH3_SEMI) 64 * (dx + 12 * dy) else dx + 8 * dy
        val next = (offsets[op] + step).coerceIn(-YmPlayerImage.CH3_LIMIT, YmPlayerImage.CH3_LIMIT)
        return ins.copy(ch3 = offsets.toMutableList().also { it[op] = next })
    }

    /** Switches the song's FM 3 mode (normal ⇄ special); true when it changed. */
    private fun stepFm3Mode(v: ViewController, d: Int): Boolean {
        if (d % 2 == 0) return false
        val changed = v.session.apply("Set FM 3 mode") { s ->
            s.copy(sound = s.ym.copy(fm3Mode = if (s.ym.fm3Mode == YmFm3Mode.NORMAL) YmFm3Mode.SPECIAL else YmFm3Mode.NORMAL))
        }
        if (changed) fm3Note(v)
        return changed
    }

    private fun fm3Note(v: ViewController) {
        v.session.note(when (v.session.song.ym.fm3Mode) {
            YmFm3Mode.NORMAL -> "FM 3 is in normal mode: the operator offsets sound only in special mode (FM 3 mode row, or Song view)"
            YmFm3Mode.SPECIAL -> "FM 3 special mode: each operator of FM 3's voice plays the note plus its offset"
        })
    }

    /** [ins] with [row]'s value stepped by [dx] (fine) and [dy] (coarse); list controls wrap. */
    fun step(ins: YmFmInstrument, row: Row, op: Int, dx: Int, dy: Int): YmFmInstrument {
        val c = YmCurated.controls(ins)
        val d = dx + dy
        fun wrap(i: Int, size: Int) = (i + d).mod(size)
        return when (row) {
            Row.ALGORITHM -> YmCurated.withAlgorithm(ins, wrap(c.algorithm, YmCurated.ALGORITHMS.size))
            Row.RATIO -> YmCurated.withRatio(ins, wrap(c.ratio, YmCurated.RATIOS.size))
            Row.BRIGHTNESS -> if (c.brightness < 0) ins else YmCurated.withBrightness(ins, (c.brightness + dx + COARSE * dy).coerceIn(0, 127))
            Row.LEVEL -> YmCurated.withLevel(ins, (c.level + dx + COARSE * dy).coerceIn(0, 127))
            Row.FEEDBACK -> YmCurated.withFeedback(ins, (c.feedback + d).coerceIn(0, 7))
            Row.ATTACK -> YmCurated.withAttack(ins, (c.attack + dx + 4 * dy).coerceIn(0, 31))
            Row.DECAY -> YmCurated.withDecay(ins, (c.decay + dx + 4 * dy).coerceIn(0, 31))
            Row.SUSTAIN -> YmCurated.withSustain(ins, (c.sustain + dx + 4 * dy).coerceIn(0, 15))
            Row.SUSTAIN_RATE -> YmCurated.withSustainRate(ins, (c.sustainRate + dx + 4 * dy).coerceIn(0, 31))
            Row.RELEASE -> YmCurated.withRelease(ins, (c.release + dx + 4 * dy).coerceIn(0, 15))
            Row.TONE_DECAY -> if (c.toneDecay < 0) ins else YmCurated.withToneDecay(ins, (c.toneDecay + dx + 4 * dy).coerceIn(0, 31))
            Row.KEY_SCALE -> YmCurated.withKeyScale(ins, wrap(c.keyScale, YmCurated.KEY_SCALES.size))
            Row.VIBRATO -> YmCurated.withVibrato(ins, (c.vibrato + d).coerceIn(0, 7))
            Row.TREMOLO -> YmCurated.withTremolo(ins, (c.tremolo + d).coerceIn(0, 3))
            Row.SSG -> YmCurated.withSsg(ins, wrap(c.ssg, YmCurated.SSG_PRESETS.size))
            Row.RAW_ALGORITHM -> ins.copy(algorithm = wrap(ins.algorithm, 8))
            Row.RAW_FEEDBACK -> ins.copy(feedback = (ins.feedback + d).coerceIn(0, 7))
            Row.AMS -> ins.copy(ams = (ins.ams + d).coerceIn(0, 3))
            Row.FMS -> ins.copy(fms = (ins.fms + d).coerceIn(0, 7))
            in GRID -> ins.withOperator(op, stepOperator(ins.operators[op], row, dx, dy))
            else -> ins
        }
    }

    /** One register field of operator [o] stepped: values clamp to the field's range, AM switches, SSG-EG steps off, 8..15. */
    fun stepOperator(o: YmOperator, row: Row, dx: Int, dy: Int): YmOperator {
        val d = dx + dy
        return when (row) {
            Row.OP_DT -> o.copy(dt = (o.dt + d).mod(8))
            Row.OP_MUL -> o.copy(mul = (o.mul + d).coerceIn(0, 15))
            Row.OP_TL -> o.copy(tl = (o.tl + dx + COARSE * dy).coerceIn(0, 127))
            Row.OP_KS -> o.copy(ks = (o.ks + d).coerceIn(0, 3))
            Row.OP_AR -> o.copy(ar = (o.ar + dx + 4 * dy).coerceIn(0, 31))
            Row.OP_AM -> o.copy(am = !o.am)
            Row.OP_D1R -> o.copy(d1r = (o.d1r + dx + 4 * dy).coerceIn(0, 31))
            Row.OP_D2R -> o.copy(d2r = (o.d2r + dx + 4 * dy).coerceIn(0, 31))
            Row.OP_SL -> o.copy(sl = (o.sl + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_RR -> o.copy(rr = (o.rr + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_SSG -> o.copy(ssgEg = SSG_STEPS[(SSG_STEPS.indexOf(o.ssgEg).coerceAtLeast(0) + d).mod(SSG_STEPS.size)])
            else -> o
        }
    }

    /** SSG-EG register values in stepping order: off, then the eight shapes. */
    private val SSG_STEPS = listOf(0) + (8..15)

    /** Envelope steps: Edit+↑↓ changes the step's value (adding one at the end), Edit+←→ carries it along. */
    private fun adjustSteps(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val kind = envelopeKind(session.song, st) ?: return false
        val slot = st.instrument
        val name = kind.label.lowercase()
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editYmEnvelope(slot, kind, "Set $name") {
                NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep) + dy)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = NesEnvelopes.valueAt(kind, e, st.envStep)
            val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editYmEnvelope(slot, kind, "Draw $name") { NesEnvelopes.withValue(kind, it, next, value) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        }
        return changed
    }

    /** FM → PSG → PCM → FM: the slot gets the next kind's starting sound (keeping its name); undo brings the old one back. */
    private fun switchType(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val cur = sound(session.song, st)
        val to = when (kind(session.song, st)) {
            Kind.FM -> Kind.PSG
            Kind.PSG -> Kind.PCM
            Kind.PCM -> Kind.FM
        }
        val next: YmInstrument = when (to) {
            Kind.FM -> defaultYmFm(slot)
            Kind.PSG -> defaultYmPsg(slot)
            Kind.PCM -> defaultYmPcm(slot)
        }
        val named = cur?.let { next.named(it.name) } ?: next
        val label = when (to) { Kind.FM -> "Make FM voice"; Kind.PSG -> "Make PSG sound"; Kind.PCM -> "Make PCM kit" }
        val ok = session.setYmInstrument(slot, named, label)
        if (kindOf(st.listenChannel) != to) st.listenChannel = when (to) { Kind.FM -> 0; Kind.PSG -> YmRules.PSG_FIRST; Kind.PCM -> YmRules.PCM_FIRST }
        keepPage(v, Row.TYPE)
        if (to == Kind.PCM) YmPcmPage.normalise(v)
        val what = when (to) {
            Kind.FM -> "an FM voice (FM 1-6)"
            Kind.PSG -> "a PSG sound (PSG 1-3, Noise)"
            Kind.PCM -> "a PCM kit (PCM 1-3)"
        }
        session.note("Sound ${EditorSession.hex2(slot)} is now $what" + if (cur != null) "; undo brings the old sound back" else "")
        return ok
    }

    /** A status line for list controls whose names say more than the row can (algorithm routing). */
    private fun curatedNote(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st) as? YmFmInstrument ?: return
        when (row(v.session.song, st)) {
            Row.ALGORITHM, Row.RAW_ALGORITHM -> {
                val a = YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)]
                v.session.note("Algorithm ${a.number}: ${a.name} ${a.diagram} · carriers ${a.carriers.joinToString("+")}")
            }
            Row.VIBRATO, Row.TREMOLO, Row.AMS, Row.FMS -> if (v.session.song.ym.lfo == 0) v.session.note("The song's LFO is off (Song view): vibrato and tremolo are silent until it, or Lxx, turns it on")
            else -> Unit
        }
    }

    /** The presets the slot's kind offers: FM voices and drums, or PSG tones and noise sounds; [drums] only drums. PCM kits have none. */
    fun presets(song: Song, state: ViewState, drums: Boolean): List<YmPresets.Preset> = YmPresets.ALL.filter {
        when (kind(song, state)) {
            Kind.FM -> if (drums) it.category == YmPresets.Category.DRUM else it.category == YmPresets.Category.FM || it.category == YmPresets.Category.DRUM
            Kind.PSG -> it.category == YmPresets.Category.PSG || it.category == YmPresets.Category.NOISE
            Kind.PCM -> false
        }
    }

    /**
     * Presets [delta] steps from the one the sound is (or the first), replacing the sound; noise presets listen on
     * Noise. An FM voice keeps its FM 3 operator offsets (the Ch3 page's), which no preset sets.
     */
    private fun applyPreset(v: ViewController, delta: Int, drums: Boolean): Boolean {
        val st = v.state
        val presets = presets(v.session.song, st, drums)
        if (presets.isEmpty()) return false
        val ins = sound(v.session.song, st)
        val ch3 = (ins as? YmFmInstrument)?.ch3
        fun kept(p: YmPresets.Preset): YmInstrument = p.instrument.let { if (it is YmFmInstrument && ch3 != null) it.copy(ch3 = ch3) else it }
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { kept(it) == ins }, delta, presets.size)]
        val ok = v.session.setYmInstrument(st.instrument, kept(preset), "Preset ${preset.name}")
        when (preset.category) {
            YmPresets.Category.NOISE -> st.listenChannel = YmRules.NOISE
            YmPresets.Category.PSG -> if (!YmRules.isPsg(st.listenChannel) || st.listenChannel == YmRules.NOISE) st.listenChannel = YmRules.PSG_FIRST
            else -> if (!YmRules.isFm(st.listenChannel)) st.listenChannel = 0
        }
        v.session.note("${preset.name}: ${YmCurated.summary(preset.instrument)}" + (preset.note?.let { " · voiced at ${YmSystem.noteName(it)}" } ?: ""))
        return ok
    }

    /** After an edit: a held Listen or Edit sounds the changed instrument again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.session.song, v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        v.state.ymPanel.editBlocked = false
        if (v.state.instField < 0) return
        if (YmSoundFace.friendly(v.session.song, v.state)) {
            if (v.state.instField >= 2 && sound(v.session.song, v.state) != null) audition(v)
            return
        }
        if (row(v.session.song, v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (v.state.ymPanel.editBlocked) { v.state.ymPanel.editBlocked = false; return }
        if (v.state.instField < 0) {
            if (tap) YmSoundFace.selectMode(v, !v.state.ymPanel.advanced, true)
            return
        }
        if (YmSoundFace.friendly(v.session.song, v.state) && v.state.instField >= 1) return
        val st = v.state
        val session = v.session
        val row = row(session.song, st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        val changed = when {
            row == Row.SLOT -> {
                if (empty) session.setYmInstrument(slot, defaultYmInstrument(slot, st.listenChannel), "New sound").also {
                    val what = when (kindOf(st.listenChannel)) { Kind.FM -> "FM voice"; Kind.PSG -> "PSG sound"; Kind.PCM -> "PCM kit" }
                    session.note("Sound ${EditorSession.hex2(slot)}: a new $what to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.TYPE -> switchType(v)
            row == Row.PRESET -> applyPreset(v, 1, drums = false)
            row == Row.DRUM_PRESET -> applyPreset(v, 1, drums = true)
            row in TOGGLES -> session.editYmFm(slot, "Set ${row.label}") { step(it, row, operator(st), 1, 0) }
            row == Row.MODE || row == Row.NOISE_TYPE -> { adjust(v, 1, 0); false }
            row == Row.FM3_MODE -> stepFm3Mode(v, 1)
            row == Row.PCM_SAMPLE || row == Row.PCM_SAMPLES || row == Row.PCM_SPREAD || row == Row.PCM_ADD -> YmPcmPage.editTap(v, row)
            row == Row.STEPS -> {
                val kind = envelopeKind(session.song, st) ?: return
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editYmEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep))
                    }.also { envelope(session.song, st)?.let { st.envStep = lastStep(it) } }
                } else false
            }
            empty && row in SOUND_ROWS -> session.setYmInstrument(slot, defaultYmInstrument(slot, st.listenChannel), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        if (v.state.instField < 0 || v.state.ymPanel.editBlocked) return
        // A musical control has no implicit reset/adoption of a custom patch. Slot/name retain their normal actions.
        if (YmSoundFace.friendly(v.session.song, v.state) && v.state.instField >= 2) return
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(song, st)
        val fm = fmPages(song, st)
        val blank = defaultYmFm(slot)
        val reset = YmCurated.controls(blank)
        fun fm(label: String, f: (YmFmInstrument) -> YmFmInstrument) { session.editYmFm(slot, label, f) }
        when (row) {
            Row.SLOT -> session.setYmInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.PRESET, Row.DRUM_PRESET -> session.setYmInstrument(slot, if (fm) blank.copy(ch3 = (sound(song, st) as? YmFmInstrument)?.ch3 ?: blank.ch3) else defaultYmPsg(slot), "Reset sound")
            Row.NAME -> session.renameYm(slot) { ViewController.deleteChar(it, st.textPos) }
            Row.ALGORITHM -> fm("Reset algorithm") { YmCurated.withAlgorithm(it, reset.algorithm) }
            Row.RATIO -> fm("Reset ratios") { YmCurated.withRatio(it, 0) }
            Row.BRIGHTNESS -> fm("Reset brightness") { YmCurated.withBrightness(it, maxOf(0, reset.brightness)) }
            Row.LEVEL -> fm("Reset level") { YmCurated.withLevel(it, reset.level) }
            Row.FEEDBACK, Row.RAW_FEEDBACK -> fm("Feedback off") { it.copy(feedback = 0) }
            Row.ATTACK -> fm("Reset attack") { YmCurated.withAttack(it, reset.attack) }
            Row.DECAY -> fm("Reset decay") { YmCurated.withDecay(it, reset.decay) }
            Row.SUSTAIN -> fm("Reset sustain") { YmCurated.withSustain(it, reset.sustain) }
            Row.SUSTAIN_RATE -> fm("Sustain rate off") { YmCurated.withSustainRate(it, 0) }
            Row.RELEASE -> fm("Reset release") { YmCurated.withRelease(it, reset.release) }
            Row.TONE_DECAY -> fm("Reset tone decay") { YmCurated.withToneDecay(it, maxOf(0, reset.toneDecay)) }
            Row.KEY_SCALE -> fm("Key scaling off") { YmCurated.withKeyScale(it, 0) }
            Row.VIBRATO, Row.FMS -> fm("Vibrato off") { YmCurated.withVibrato(it, 0) }
            Row.TREMOLO -> fm("Tremolo off") { YmCurated.withTremolo(it, 0) }
            Row.AMS -> fm("AMS off") { it.copy(ams = 0) }
            Row.SSG -> fm("SSG-EG off") { YmCurated.withSsg(it, 0) }
            Row.RAW_ALGORITHM -> fm("Algorithm 0") { it.copy(algorithm = 0) }
            in GRID -> fm("Reset ${row.label}") { it.withOperator(operator(st), resetField(it.operators[operator(st)], row)) }
            Row.FM3_MODE -> session.apply("Set FM 3 mode") { s -> s.copy(sound = s.ym.copy(fm3Mode = YmFm3Mode.NORMAL)) }
            Row.CH3_SEMI, Row.CH3_FINE -> fm("Reset FM 3 operator ${operator(st) + 1} offset") { ins ->
                ins.copy(ch3 = List(4) { k -> if (k == operator(st)) 0 else ins.ch3.getOrElse(k) { 0 } })
            }
            in SWEEP -> if (fm) fm("Sweep off") { it.copy(sweep = YmSweep.NONE) } else session.editYmPsg(slot, "Sweep off") { it.copy(sweep = YmSweep.NONE) }
            Row.LENGTH -> envelopeKind(song, st)?.let { k -> session.editYmEnvelope(slot, k, "Reset ${k.label.lowercase()} envelope") { defaultYmPsg(slot).envelope(k) } }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> session.editYmEnvelope(slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.ENV_RELEASE -> envelopeKind(song, st)?.let { k -> session.editYmEnvelope(slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.MODE -> session.editYmPsg(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                session.editYmEnvelope(slot, k, "Delete ${k.label.lowercase()} step") { NesEnvelopes.removed(k, it, st.envStep) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
            Row.NOISE_TYPE, Row.NOISE_RATE -> session.editYmPsg(slot, "Reset noise") { it.copy(noise = defaultYmPsg(slot).noise) }
            in YmPcmPage.KEY_ROWS, in YmPcmPage.BOX_ROWS, Row.PCM_LEVEL -> YmPcmPage.clear(v, row)
            else -> Unit // Listen, Type, Page
        }
    }

    private fun resetField(o: YmOperator, row: Row): YmOperator {
        val d = YmOperator()
        return when (row) {
            Row.OP_DT -> o.copy(dt = d.dt)
            Row.OP_MUL -> o.copy(mul = d.mul)
            Row.OP_TL -> o.copy(tl = d.tl)
            Row.OP_KS -> o.copy(ks = d.ks)
            Row.OP_AR -> o.copy(ar = d.ar)
            Row.OP_AM -> o.copy(am = d.am)
            Row.OP_D1R -> o.copy(d1r = d.d1r)
            Row.OP_D2R -> o.copy(d2r = d.d2r)
            Row.OP_SL -> o.copy(sl = d.sl)
            Row.OP_RR -> o.copy(rr = d.rr)
            Row.OP_SSG -> o.copy(ssgEg = d.ssgEg)
            else -> o
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val listenPage = if (YmSoundFace.friendly(song, st)) null else page(song, st)
        val ch = listenChannel(st, ins, listenPage)
        if (ch != st.listenChannel && listenPage != Page.CH3) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} is ${YmSystem.kindName(ins)}: listening on ${song.system.channels[ch].name}")
        }
        if (ins is YmPcmInstrument) YmPcmPage.audition(v, ch) else v.startAudition(ch, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        if (v.state.ymPanel.editBlocked && v.editHeld) { v.useEdit(); return true }
        if (YmSoundFace.friendly(v.session.song, v.state) && (a == Action.PREV_CHANNEL || a == Action.NEXT_CHANNEL)) {
            val d = if (a == Action.NEXT_CHANNEL) 1 else -1
            v.state.instField = 2 + ((v.state.instField - 2).coerceAtLeast(0) + d).mod(YmSoundControl.entries.size)
            return true
        }
        if (a == Action.PASTE || a == Action.CUT || a == Action.DUPLICATE) v.state.ymPanel.invalidate()
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(song, st)
        val kind = envelopeKind(song, st)
        val onEnvelope = kind != null && row !in HEAD
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                val d = if (a == Action.NEXT_CHANNEL) 1 else -1
                if (row in OPERATOR_ROWS) st.envStep = (st.envStep + d).coerceIn(0, 3) else showPage(v, st.envelope + d)
            }
            Action.COPY -> if (onEnvelope) session.copyYmEnvelope(slot, kind!!) else session.copyYmInstrument(slot)
            Action.PASTE -> { session.pasteYm(slot, kind); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                session.copyYmEnvelope(slot, kind!!)
                session.editYmEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { defaultYmPsg(slot).envelope(kind) }
            } else if (session.copyYmInstrument(slot)) {
                session.setYmInstrument(slot, null, "Cut sound")
                keepPage(v, row)
            }
            Action.DUPLICATE -> {
                if (slot >= YmSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                session.setYmInstrument(slot + 1, src.named((src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (YmSoundFace.friendly(v.session.song, v.state) || v.state.instField == -1) { YmSoundFace.hints(v, h); return }
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        val ins = sound(song, st)
        when {
            row == Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            row == Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            row == Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            row == Row.PAGE -> h += Hint(v.lr(), "Page")
            row == Row.TYPE -> h += Hint(v.k(Action.EDIT), when (kind(song, st)) {
                Kind.FM -> "Make it a PSG sound"
                Kind.PSG -> "Make it a PCM kit"
                Kind.PCM -> "Make it an FM voice"
            })
            row == Row.PRESET || row == Row.DRUM_PRESET -> {
                h += Hint(v.editLr(), if (row == Row.PRESET) "Start from a preset" else "Drum presets"); h += Hint(v.k(Action.CLEAR), "Default sound")
            }
            row in GRID -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                if (row in TOGGLES) h += Hint(v.k(Action.EDIT), "Switch ${row.label}") else h += Hint(v.editLr(), "${row.label} ±1 (${v.ud()} coarse)")
                h += Hint(v.k(Action.CLEAR), "Default")
            }
            row in CH3_GRID -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                h += Hint(v.editLr(), if (row == Row.CH3_SEMI) "±1 semitone (${v.ud()} octave)" else "±1/64 (${v.ud()} ±8/64)")
                h += Hint(v.k(Action.CLEAR), "Offset 0")
            }
            row == Row.FM3_MODE -> { h += Hint(v.k(Action.EDIT), "Normal / special"); h += Hint(v.k(Action.CLEAR), "Normal") }
            page(song, st) == Page.PCM && row !in HEAD -> YmPcmPage.hints(v, row, h)
            row == Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
            row == Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty envelope") }
            row == Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            row == Row.ENV_RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            row == Row.MODE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            row == Row.NOISE_TYPE -> h += Hint(v.k(Action.EDIT), "White / periodic")
            row in LISTS || row == Row.NOISE_RATE -> { h += Hint(v.editLr(), "Choose"); h += Hint(v.k(Action.CLEAR), "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
        }
        if (row !in HEAD && row !in OPERATOR_ROWS && row != Row.STEPS && page(song, st) != Page.PCM) h += Hint(v.lr(), "Sound slot")
        if (row !in OPERATOR_ROWS) h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(st, ins, page(song, st)))?.name ?: ""}")
        if (ins is YmPcmInstrument) h += Hint(v.k(Action.AUDITION), "Listen: key ${YmSystem.noteName(st.dpcmKey)}")
        else h += Hint(v.octaves(), "Listen at ${YmSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in HEAD) "Copy/paste envelope" else "Copy/paste sound")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val editLr = keys.held(Action.EDIT, lr)
        return listOf(
            "FM Sound face" to listOf(
                "Sound / Advanced" to "Move up past Slot, then $lr selects the face. Both edit the same exact patch; opening either changes nothing. PSG and PCM keep their own editors",
                "Brightness" to "The large dial changes modulator strength, not carrier loudness. Its internal balance stays until a register reaches its limit",
                "Structure" to "One deliberate routing and voice-balance choice, covering all eight algorithms. Ratios, envelopes, detune, sweep and FM 3 offsets stay",
                "Harmonics" to "Round, Hollow, Reedy, Metal and Glass set only modulator multipliers to 1, 2, 3, 5 and 7. Carrier pitch and custom tuning stay",
                "Edge" to "Operator 1 feedback: from pure to a biting or noisy tone. It also works on Four sines",
                "Contour" to "Pluck, Held, Bloom or Short changes only carrier attack, decay, sustain level/rate and release. Other envelopes and rate scaling stay. SSG carrier envelopes require Advanced",
                "Custom" to "No exact musical choice matches those fields. They remain untouched until you choose a value; Clear never normalizes a musical control",
                "Four sines" to "There are no modulators, so Brightness and Harmonics are unavailable",
                "Explore" to "Hold $edit with directions, or drag a dial horizontally. One continuous edit is one Undo. $channels selects a control; unheld $lr selects a slot",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, type, page, then the page's settings",
                lr to "On settings: the sound slot; on the Expert and Ch3 grids: the operator; on envelope steps: the step; on PCM keys: the key",
                channels to "Advanced pages. FM voices: Curated, Expert (every register), Ch3 (FM 3 operator pitches), Drum (pitch sweep). " +
                    "PSG sounds: Volume, Arpeggio, Pitch, Noise·Drum. PCM kits: PCM",
                "Listen on" to "The channel $listen plays the sound on: FM voices on FM 1-6 (FM 3 on the Ch3 page), PSG sounds on PSG 1-3 or Noise, " +
                    "PCM kits on PCM 1-3 (the shown key). Holding $edit on a setting sounds the sound and every change restarts it",
                "Type" to "FM voice (FM 1-6), PSG sound (PSG 1-3, Noise) or PCM kit (PCM 1-3); $edit steps to the next kind, giving the slot its starting sound",
                editLr to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new sound to start from, of the kind Listen's channel plays",
            ),
            "FM Curated page (Advanced)" to listOf(
                "Preset" to "Starting sounds: slap and finger bass, brass, E-piano, bell, strings, lead, organ, pluck, pad and FM drums (kick, snare, tom, hi-hat, crash)",
                "Algorithm" to "How the four operators connect (0 chain … 7 additive). Modulators (→) colour the carriers you hear; carriers sum to the output",
                "Ratios" to "Frequency multipliers of operators 1-4 (½ to 15) with detune: whole-number sets are harmonic, chorus/shimmer detune pairs",
                "Brightness" to "How strongly the modulators act (their level): 0 dull, 127 brightest. Level is the carriers' loudness",
                "Feedback" to "Operator 1 modulating itself, 0-7: from pure to noisy",
                "Envelope" to "Attack, decay to the sustain level, sustain (15 = full, 0 = fades to silence), sustain rate (keeps fading while held), " +
                    "release after OFF; tone decay is how fast the brightness fades. 31 (15 for release) is fastest",
                "Key scaling" to "Faster envelopes on higher notes, like real instruments",
                "Vibrato/Tremolo" to "The instrument's LFO depths (FMS, AMS on the carriers); the song's LFO rate (Song view, or Lxx) must be on to hear them",
                "SSG-EG" to "The YM2612's repeating envelope shapes on modulators (the tone cycles) or carriers (the volume cycles); drawn on the envelope",
                "≈" to "A value marked ≈ was hand-edited on the Expert page: the nearest curated choice is shown, and stepping it replaces those fields",
            ),
            "Expert, Drum, PSG" to listOf(
                "Expert" to "Algorithm, feedback, AMS and FMS as stored, then per operator DT (detune), MUL, TL (level, 0 loudest), KS, AR, AM ($edit switches), " +
                    "D1R, D2R, SL, RR and SSG-EG. Carriers are marked ♪",
                "Drum" to "Pitch sweep: each note starts at the start offset (semitones from the note) and moves to the end at speed/16 semitones per tick. " +
                    "The note picks the drum's pitch",
                "Volume, Arpeggio, Pitch" to "PSG envelopes, one value per tick, edited as on the NES: ${keys.held(Action.EDIT, ud)} sets a step, $editLr draws along, " +
                    "loop and release points; volume 0-15, arpeggio in semitones, pitch in sixteenths of a semitone",
                "Noise·Drum" to "On the Noise channel: white or periodic noise at a fixed rate (/512, /1024, /2048) or pitched by the note " +
                    "(PSG 3's period: PSG 3 is silent while it sounds); and the PSG sound's pitch sweep",
            ),
            "Ch3 page" to listOf(
                "FM 3 mode" to "The song's FM 3 mode (also in the Song view). Normal: FM 3 plays like FM 1-6. Special: each of FM 3's four " +
                    "operators sounds the note plus its own offset, so one voice can play a chord or a detuned stack",
                "Semitones, Fine" to "Operator offsets in semitones and 1/64 semitones (±4 octaves), operators numbered as in the algorithm drawing. " +
                    "Slides, vibrato, arpeggios and tunings move the note; the offsets follow it. Other FM channels ignore them",
            ),
            "PCM page" to YmPcmPage.helpRows(keys),
        )
    }

    override fun painter(pane: PaneView): Painter = YmSoundPainter(pane)
}
