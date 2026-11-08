package org.thortracker.system.ym2612

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
 * The Mega Drive instrument editor, controller-only: the one bank of [YmSystem.SLOTS] sounds, each empty, an FM voice
 * (FM 1-6) or a PSG sound (PSG 1-3, Noise). Head rows: slot, name, the channel Listen plays on, the sound's type, the
 * page. An FM voice has the OPL3 editor's pages (shared FM pieces): *Sound*, the curated controls of [YmCurated] beside
 * the drawn algorithm and envelope (SSG-EG drawn on it); *Expert*, algorithm, feedback, AMS/FMS and every OPN2 operator
 * field as a grid; *Drum*, the pitch sweep. A PSG sound has the NES editor's envelopes (volume, arpeggio, pitch, edited
 * step by step) and a *Noise·Drum* page (noise type and rate for the Noise channel, the sweep).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep] (Expert: the operator 0..3; envelopes: the step, size = append), [ViewState.listenChannel].
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object YmInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: NesEnvelopeKind? = null) {
        SOUND("Sound"), EXPERT("Expert"), DRUM("Drum"),
        VOLUME("Volume", NesEnvelopeKind.VOLUME), ARPEGGIO("Arpeggio", NesEnvelopeKind.ARPEGGIO), PITCH("Pitch", NesEnvelopeKind.PITCH),
        NOISE("Noise·Drum"),
    }

    val FM_PAGES = listOf(Page.SOUND, Page.EXPERT, Page.DRUM)
    val PSG_PAGES = listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.NOISE)

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), TYPE("Type"), PAGE("Page"),
        PRESET("Preset"), ALGORITHM("Algorithm"), RATIO("Ratios"), BRIGHTNESS("Brightness"), LEVEL("Level"), FEEDBACK("Feedback"),
        ATTACK("Attack"), DECAY("Decay"), SUSTAIN("Sustain"), SUSTAIN_RATE("Sustain rate"), RELEASE("Release"),
        TONE_DECAY("Tone decay"), KEY_SCALE("Key scaling"), VIBRATO("Vibrato"), TREMOLO("Tremolo"), SSG("SSG-EG"),
        RAW_ALGORITHM("Algorithm"), RAW_FEEDBACK("Feedback"), AMS("AMS"), FMS("FMS"),
        OP_DT("DT"), OP_MUL("MUL"), OP_TL("TL"), OP_KS("KS"), OP_AR("AR"), OP_AM("AM"), OP_D1R("D1R"), OP_D2R("D2R"),
        OP_SL("SL"), OP_RR("RR"), OP_SSG("SSG"),
        DRUM_PRESET("Drum preset"), SWEEP_START("Sweep start"), SWEEP_END("Sweep end"), SWEEP_SPEED("Sweep speed"),
        LENGTH("Length"), LOOP("Loop"), ENV_RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
        NOISE_TYPE("Noise"), NOISE_RATE("Noise rate"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.TYPE, Row.PAGE)

    /** The Expert grid rows: one register field each, all operators side by side. */
    val GRID = listOf(Row.OP_DT, Row.OP_MUL, Row.OP_TL, Row.OP_KS, Row.OP_AR, Row.OP_AM, Row.OP_D1R, Row.OP_D2R, Row.OP_SL,
        Row.OP_RR, Row.OP_SSG)

    private val SWEEP = listOf(Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED)

    private val ROWS = mapOf(
        Page.SOUND to HEAD + listOf(Row.PRESET, Row.ALGORITHM, Row.RATIO, Row.BRIGHTNESS, Row.LEVEL, Row.FEEDBACK, Row.ATTACK,
            Row.DECAY, Row.SUSTAIN, Row.SUSTAIN_RATE, Row.RELEASE, Row.TONE_DECAY, Row.KEY_SCALE, Row.VIBRATO, Row.TREMOLO, Row.SSG),
        Page.EXPERT to HEAD + listOf(Row.RAW_ALGORITHM, Row.RAW_FEEDBACK, Row.AMS, Row.FMS) + GRID,
        Page.DRUM to HEAD + listOf(Row.DRUM_PRESET) + SWEEP,
        Page.VOLUME to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.STEPS),
        Page.ARPEGGIO to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.PITCH to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.MODE, Row.STEPS),
        Page.NOISE to HEAD + listOf(Row.PRESET, Row.NOISE_TYPE, Row.NOISE_RATE) + SWEEP,
    )

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = ROWS.values.flatMap { it.drop(HEAD.size) }.toSet() - Row.STEPS + Row.TYPE

    /** On/off register bits of the Expert grid: a tap switches them. */
    private val TOGGLES = setOf(Row.OP_AM)

    /** List controls: Edit+←→ chooses, wrapping. */
    private val LISTS = setOf(Row.ALGORITHM, Row.RATIO, Row.KEY_SCALE, Row.SSG, Row.RAW_ALGORITHM, Row.NOISE_TYPE, Row.NOISE_RATE)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun sound(song: Song, state: ViewState): YmInstrument? = song.ymInstrument(state.instrument)

    /** True when the slot's pages are FM's: an FM voice, or an empty slot while Listen is on an FM channel. */
    fun fmPages(song: Song, state: ViewState): Boolean = when (sound(song, state)) {
        is YmFmInstrument -> true
        is YmPsgInstrument -> false
        null -> YmRules.isFm(state.listenChannel)
    }

    fun pages(song: Song, state: ViewState): List<Page> = if (fmPages(song, state)) FM_PAGES else PSG_PAGES

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> = ROWS.getValue(page(song, state))

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The operator (0-based) the Expert grid's cursor is on. */
    fun operator(state: ViewState): Int = state.envStep.coerceIn(0, 3)

    /** The PSG envelope a page shows, or null. */
    fun envelopeKind(song: Song, state: ViewState): NesEnvelopeKind? = page(song, state).envelope

    /** The channel a held Listen plays [ins] on: FM voices on an FM channel (FM 1 unless Listen is one), PSG sounds on PSG/Noise. */
    fun listenChannel(state: ViewState, ins: YmInstrument?): Int = when {
        ins is YmFmInstrument && !YmRules.isFm(state.listenChannel) -> 0
        ins is YmPsgInstrument && YmRules.isFm(state.listenChannel) -> YmRules.PSG_FIRST
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
        st.instField = st.instField.coerceIn(0, rows(song, st).size - 1)
        when {
            page(song, st) == Page.EXPERT -> st.envStep = st.envStep.coerceIn(0, 3)
            envelopeKind(song, st) != null -> st.envStep = st.envStep.coerceIn(0, envelope(song, st)?.let(::lastStep) ?: 0)
        }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, YmSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry, in the bank of its kind (FM voices or PSG sounds share the slot numbers). */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        val bank = if (sound(session.song, v.state) is YmPsgInstrument) YmSystem.PSG_BANK else YmSystem.FM_BANK
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
        val row = row(v.session.song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        keepPage(v, row)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val song = v.session.song
        val rows = rows(song, st)
        val row = row(song, st)
        val e = envelope(song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> { st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size); keepPage(v, row) }
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row in GRID -> st.envStep = (st.envStep + dx).coerceIn(0, 3)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
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

    /** FM ⇄ PSG: the slot gets the other kind's starting sound (keeping its name); undo brings the old one back. */
    private fun switchType(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val cur = sound(session.song, st)
        val toFm = !fmPages(session.song, st)
        val next: YmInstrument = if (toFm) defaultYmFm(slot) else defaultYmPsg(slot)
        val named = cur?.let { next.named(it.name) } ?: next
        val ok = session.setYmInstrument(slot, named, if (toFm) "Make FM voice" else "Make PSG sound")
        if (toFm && !YmRules.isFm(st.listenChannel)) st.listenChannel = 0
        if (!toFm && YmRules.isFm(st.listenChannel)) st.listenChannel = YmRules.PSG_FIRST
        keepPage(v, Row.TYPE)
        session.note("Sound ${EditorSession.hex2(slot)} is now ${if (toFm) "an FM voice (FM 1-6)" else "a PSG sound (PSG 1-3, Noise)"}" +
            if (cur != null) "; undo brings the old sound back" else "")
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

    /** The presets the slot's kind offers: FM voices and drums, or PSG tones and noise sounds; [drums] only drums. */
    fun presets(song: Song, state: ViewState, drums: Boolean): List<YmPresets.Preset> = YmPresets.ALL.filter {
        if (fmPages(song, state)) (if (drums) it.category == YmPresets.Category.DRUM else it.category == YmPresets.Category.FM || it.category == YmPresets.Category.DRUM)
        else it.category == YmPresets.Category.PSG || it.category == YmPresets.Category.NOISE
    }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound; noise presets listen on Noise. */
    private fun applyPreset(v: ViewController, delta: Int, drums: Boolean): Boolean {
        val st = v.state
        val presets = presets(v.session.song, st, drums)
        if (presets.isEmpty()) return false
        val ins = sound(v.session.song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setYmInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        when (preset.category) {
            YmPresets.Category.NOISE -> st.listenChannel = YmRules.NOISE
            YmPresets.Category.PSG -> if (st.listenChannel == YmRules.NOISE || YmRules.isFm(st.listenChannel)) st.listenChannel = YmRules.PSG_FIRST
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
        if (row(v.session.song, v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
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
                    session.note("Sound ${EditorSession.hex2(slot)}: a new ${if (YmRules.isFm(st.listenChannel)) "FM voice" else "PSG sound"} to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.TYPE -> switchType(v)
            row == Row.PRESET -> applyPreset(v, 1, drums = false)
            row == Row.DRUM_PRESET -> applyPreset(v, 1, drums = true)
            row in TOGGLES -> session.editYmFm(slot, "Set ${row.label}") { step(it, row, operator(st), 1, 0) }
            row == Row.MODE || row == Row.NOISE_TYPE -> { adjust(v, 1, 0); false }
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
            Row.PRESET, Row.DRUM_PRESET -> session.setYmInstrument(slot, if (fm) blank else defaultYmPsg(slot), "Reset sound")
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
        val ins = sound(v.session.song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val ch = listenChannel(st, ins)
        if (ch != st.listenChannel) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} is ${if (ins is YmFmInstrument) "an FM voice" else "a PSG sound"}: listening on ${v.session.song.system.channels[ch].name}")
        }
        v.startAudition(ch, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
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
                if (row in GRID) st.envStep = (st.envStep + d).coerceIn(0, 3) else showPage(v, st.envelope + d)
            }
            Action.COPY -> if (onEnvelope) session.copyYmEnvelope(slot, kind!!) else session.copyYmInstrument(slot)
            Action.PASTE -> { session.pasteYm(slot, kind); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                session.copyYmEnvelope(slot, kind!!)
                session.editYmEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { defaultYmPsg(slot).envelope(kind) }
            } else {
                session.copyYmInstrument(slot)
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
            row == Row.TYPE -> h += Hint(v.k(Action.EDIT), if (fmPages(song, st)) "Make it a PSG sound" else "Make it an FM voice")
            row == Row.PRESET || row == Row.DRUM_PRESET -> {
                h += Hint(v.editLr(), if (row == Row.PRESET) "Start from a preset" else "Drum presets"); h += Hint(v.k(Action.CLEAR), "Default sound")
            }
            row in GRID -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                if (row in TOGGLES) h += Hint(v.k(Action.EDIT), "Switch ${row.label}") else h += Hint(v.editLr(), "${row.label} ±1 (${v.ud()} coarse)")
                h += Hint(v.k(Action.CLEAR), "Default")
            }
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
        if (row !in HEAD && row !in GRID && row != Row.STEPS) h += Hint(v.lr(), "Sound slot")
        if (row !in GRID) h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${YmSystem.noteName(v.defaultNote())}")
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
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, type, page, then the page's settings",
                lr to "On settings: the sound slot; on the Expert grid: the operator; on envelope steps: the step",
                channels to "Pages. FM voices: Sound (curated), Expert (every register), Drum (pitch sweep). PSG sounds: Volume, Arpeggio, Pitch, Noise·Drum",
                "Listen on" to "The channel $listen plays the sound on: FM voices on FM 1-6, PSG sounds on PSG 1-3 or Noise. Holding $edit on a setting sounds the sound and every change restarts it",
                "Type" to "FM voice (FM 1-6) or PSG sound (PSG 1-3, Noise); $edit switches, giving the slot the other kind's starting sound",
                editLr to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new sound to start from: an FM voice when Listen is on FM 1-6, else a PSG sound",
            ),
            "FM Sound page" to listOf(
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
        )
    }

    override fun painter(pane: PaneView): Painter = YmInstrumentPainter(pane)
}
