package org.thortracker.system.opl3

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
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The OPL3 instrument editor, controller-only: the one bank of [Opl3System.SLOTS] sounds, the selected sound's name, the
 * channel Listen plays on, and four pages. *Sound*: curated controls ([Opl3Curated]), each one Edit+←→ value (preset,
 * algorithm with 2-op/4-op, ratio set, waveforms, brightness, level, feedback, attack, decay, sustain, release, tone
 * decay, key scaling, vibrato/tremolo) beside the drawn algorithm and envelope. *Expert*: the mode, connection and
 * feedback as stored, then every operator register field as a grid (←→ or L1/R1 move between operators). *Drum*: the
 * pitch sweep (start, end, speed) and drum presets. *Chords*: the song's chord-shape table (`Kxx`).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the [Page]),
 * [ViewState.envStep] (Expert: the operator 0..3; Chords: the shape, size = append), [ViewState.programField] (Chords:
 * the interval column 0..3) and [ViewState.listenChannel].
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object Opl3InstrumentView : SystemView() {
    enum class Page(val label: String) { SOUND("Sound"), EXPERT("Expert"), DRUM("Drum"), CHORDS("Chords") }

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), PAGE("Page"),
        PRESET("Preset"), ALGORITHM("Algorithm"), RATIO("Ratios"), WAVES("Waveforms"), BRIGHTNESS("Brightness"),
        LEVEL("Level"), FEEDBACK("Feedback"), ATTACK("Attack"), DECAY("Decay"), SUSTAIN("Sustain"), RELEASE("Release"),
        TONE_DECAY("Tone decay"), KEY_SCALE("Key scaling"), LFO("Vib/Trem"),
        MODE("Operators"), CONNECTION("Connection"), RAW_FEEDBACK("Feedback"),
        OP_MULT("MULT"), OP_TL("TL"), OP_AR("AR"), OP_DR("DR"), OP_SL("SL"), OP_RR("RR"), OP_WAVE("WAVE"), OP_KSL("KSL"),
        OP_AM("AM"), OP_VIB("VIB"), OP_EGT("EGT"), OP_KSR("KSR"),
        DRUM_PRESET("Drum preset"), SWEEP_START("Sweep start"), SWEEP_END("Sweep end"), SWEEP_SPEED("Sweep speed"),
        SHAPES("Shapes"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE)

    /** The Expert grid rows: one register field each, all operators side by side. */
    val GRID = listOf(Row.OP_MULT, Row.OP_TL, Row.OP_AR, Row.OP_DR, Row.OP_SL, Row.OP_RR, Row.OP_WAVE, Row.OP_KSL,
        Row.OP_AM, Row.OP_VIB, Row.OP_EGT, Row.OP_KSR)

    private val ROWS = mapOf(
        Page.SOUND to HEAD + listOf(Row.PRESET, Row.ALGORITHM, Row.RATIO, Row.WAVES, Row.BRIGHTNESS, Row.LEVEL, Row.FEEDBACK,
            Row.ATTACK, Row.DECAY, Row.SUSTAIN, Row.RELEASE, Row.TONE_DECAY, Row.KEY_SCALE, Row.LFO),
        Page.EXPERT to HEAD + listOf(Row.MODE, Row.CONNECTION, Row.RAW_FEEDBACK) + GRID,
        Page.DRUM to HEAD + listOf(Row.DRUM_PRESET, Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED),
        Page.CHORDS to listOf(Row.SLOT, Row.PAGE, Row.SHAPES),
    )

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = ROWS.getValue(Page.SOUND).drop(HEAD.size).toSet() + ROWS.getValue(Page.EXPERT).drop(HEAD.size) +
        ROWS.getValue(Page.DRUM).drop(HEAD.size)

    /** On/off register bits of the Expert grid: a tap switches them. */
    private val TOGGLES = setOf(Row.OP_AM, Row.OP_VIB, Row.OP_EGT, Row.OP_KSR)

    private const val COARSE = FmControls.COARSE

    const val NAME_MAX = FmControls.NAME_MAX

    fun page(state: ViewState): Page = Page.entries[state.envelope.coerceIn(0, Page.entries.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(page(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): Opl3Instrument? = song.opl3Instrument(state.instrument)

    /** The operator (0-based) the Expert grid's cursor is on. */
    fun operator(state: ViewState): Int = state.envStep.coerceIn(0, 3)

    /** The channel a held Listen plays [ins] on: 4-op sounds only play on channels 1-6. */
    fun listenChannel(state: ViewState, ins: Opl3Instrument?): Int =
        if (ins?.fourOp == true && state.listenChannel >= Opl3Rules.FOUR_OP_CHANNELS) 0 else state.listenChannel

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, Opl3System.SLOTS)
        st.envelope = st.envelope.coerceIn(0, Page.entries.size - 1)
        Opl3SoundFace.normalise(v, rows(st).size - 1)
        st.listenChannel = st.listenChannel.coerceIn(0, v.session.song.system.channels.size - 1)
        when (page(st)) {
            Page.EXPERT -> st.envStep = st.envStep.coerceIn(0, (sound(v.session.song, st)?.operatorCount ?: 2) - 1)
            Page.CHORDS -> {
                val chords = v.session.song.opl3.chords
                st.envStep = st.envStep.coerceIn(0, lastShape(chords))
                st.programField = st.programField.coerceIn(0, 3)
            }
            else -> Unit
        }
    }

    /** The furthest shape row the cursor reaches: the append position while there is room. */
    fun lastShape(chords: List<Opl3Chord>): Int = minOf(chords.size, Opl3System.MAX_CHORDS - 1)

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, Opl3System.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(Page.entries.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
        st.programField = 0
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, Opl3System.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        if (page(st) == Page.EXPERT) st.envStep = st.envStep.coerceIn(0, (sound(v.session.song, st)?.operatorCount ?: 2) - 1)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (Opl3SoundFace.direction(v, dx, dy)) return
        val st = v.state
        val rows = rows(st)
        val row = row(st)
        val song = v.session.song
        val chords = song.opl3.chords
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.SHAPES && v.clearHeld -> { v.useClear(); st.envStep = (st.envStep - COARSE * dy + COARSE * dx).coerceIn(0, lastShape(chords)) }
            row == Row.SHAPES && dy != 0 -> {
                if (dy > 0 && st.envStep == 0) { st.instField = rows.indexOf(Row.SHAPES) - 1; return }
                st.envStep = (st.envStep - dy).coerceIn(0, lastShape(chords))
            }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
                if (row(st) == Row.SHAPES) st.envStep = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row in GRID -> moveOperator(v, dx)
            row == Row.SHAPES -> st.programField = (st.programField + dx).coerceIn(0, 3)
            else -> moveSlot(v, dx)
        }
    }

    private fun moveOperator(v: ViewController, delta: Int) {
        val st = v.state
        val count = sound(v.session.song, st)?.operatorCount ?: 2
        st.envStep = (st.envStep + delta).coerceIn(0, count - 1)
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        val changed = when (val row = row(st)) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> session.editOpl3Instrument(slot, "Rename sound") { it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX)) }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.PRESET -> applyPreset(v, d, null)
            Row.DRUM_PRESET -> applyPreset(v, d, Opl3Presets.Category.DRUM)
            Row.SHAPES -> adjustShape(v, dx, dy)
            else -> session.editOpl3Instrument(slot, "Set ${row.label.lowercase()}") { step(it, row, operator(st), dx, dy) }
        }
        if (changed) {
            curatedNote(v)
            heard(v)
        }
    }

    /** [ins] with [row]'s value stepped by [dx] (fine) and [dy] (coarse); list controls wrap. */
    fun step(ins: Opl3Instrument, row: Row, op: Int, dx: Int, dy: Int): Opl3Instrument {
        val c = Opl3Curated.controls(ins)
        val d = dx + dy
        fun wrap(i: Int, size: Int) = (i + d).mod(size)
        return when (row) {
            Row.ALGORITHM -> Opl3Curated.withAlgorithm(ins, wrap(c.algorithm, Opl3Curated.ALGORITHMS.size))
            Row.RATIO -> Opl3Curated.withRatio(ins, wrap(c.ratio, Opl3Curated.ratios(ins.fourOp).size))
            Row.WAVES -> Opl3Curated.withWaves(ins, wrap(c.waves, Opl3Curated.waves(ins.fourOp).size))
            Row.BRIGHTNESS -> if (c.brightness < 0) ins else Opl3Curated.withBrightness(ins, (c.brightness + dx + COARSE * dy).coerceIn(0, 63))
            Row.LEVEL -> Opl3Curated.withLevel(ins, (c.level + dx + COARSE * dy).coerceIn(0, 63))
            Row.FEEDBACK -> Opl3Curated.withFeedback(ins, (c.feedback + d).coerceIn(0, 7))
            Row.ATTACK -> Opl3Curated.withAttack(ins, (c.attack + dx + 4 * dy).coerceIn(0, 15))
            Row.DECAY -> Opl3Curated.withDecay(ins, (c.decay + dx + 4 * dy).coerceIn(0, 15))
            Row.SUSTAIN -> Opl3Curated.withSustain(ins, (c.sustain + dx + 4 * dy).coerceIn(0, 15))
            Row.RELEASE -> Opl3Curated.withRelease(ins, (c.release + dx + 4 * dy).coerceIn(0, 15))
            Row.TONE_DECAY -> if (c.toneDecay < 0) ins else Opl3Curated.withToneDecay(ins, (c.toneDecay + dx + 4 * dy).coerceIn(0, 15))
            Row.KEY_SCALE -> Opl3Curated.withKeyScale(ins, wrap(c.keyScale, Opl3Curated.KEY_SCALES.size))
            Row.LFO -> Opl3Curated.withLfo(ins, wrap(c.lfo, Opl3Curated.LFOS.size))
            Row.MODE -> ins.copy(fourOp = !ins.fourOp, algorithm = 0)
            Row.CONNECTION -> ins.copy(algorithm = wrap(ins.algorithm, if (ins.fourOp) 4 else 2))
            Row.RAW_FEEDBACK -> ins.copy(feedback = (ins.feedback + d).coerceIn(0, 7))
            Row.SWEEP_START -> ins.copy(sweep = sweepWith(ins.sweep, start = (ins.sweep.start + dx + 12 * dy).coerceIn(-96, 96)))
            Row.SWEEP_END -> ins.copy(sweep = sweepWith(ins.sweep, end = (ins.sweep.end + dx + 12 * dy).coerceIn(-96, 96)))
            Row.SWEEP_SPEED -> ins.copy(sweep = ins.sweep.copy(speed = (ins.sweep.speed + dx + 16 * dy).coerceIn(0, 255)))
            in GRID -> if (op >= ins.operatorCount) ins else ins.withOperator(op, stepOperator(ins.operators[op], row, dx, dy))
            else -> ins
        }
    }

    /** A sweep with a new start or end; setting either on a sweep without speed gives it a moderate one so it is heard. */
    private fun sweepWith(s: Opl3Sweep, start: Int = s.start, end: Int = s.end): Opl3Sweep =
        s.copy(start = start, end = end, speed = FmControls.sweepSpeed(s.speed, start, end))

    /** One register field of operator [o] stepped: values clamp to the field's range, bits switch. */
    fun stepOperator(o: Opl3Operator, row: Row, dx: Int, dy: Int): Opl3Operator {
        val d = dx + dy
        return when (row) {
            Row.OP_MULT -> o.copy(mult = (o.mult + d).coerceIn(0, 15))
            Row.OP_TL -> o.copy(tl = (o.tl + dx + COARSE * dy).coerceIn(0, 63))
            Row.OP_AR -> o.copy(ar = (o.ar + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_DR -> o.copy(dr = (o.dr + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_SL -> o.copy(sl = (o.sl + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_RR -> o.copy(rr = (o.rr + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_WAVE -> o.copy(wave = (o.wave + d).mod(8))
            Row.OP_KSL -> o.copy(ksl = (o.ksl + d).mod(4))
            Row.OP_AM -> o.copy(am = !o.am)
            Row.OP_VIB -> o.copy(vib = !o.vib)
            Row.OP_EGT -> o.copy(sustain = !o.sustain)
            Row.OP_KSR -> o.copy(ksr = !o.ksr)
            else -> o
        }
    }

    /** A status line for list controls whose names say more than the row can (algorithm routing, 4-op limits). */
    private fun curatedNote(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st) ?: return
        when (row(st)) {
            Row.ALGORITHM, Row.MODE, Row.CONNECTION -> {
                val a = Opl3Curated.ALGORITHMS.firstOrNull { it.fourOp == ins.fourOp && it.algorithm == ins.algorithm }
                v.session.note("${a?.name ?: "Algorithm"} ${a?.diagram ?: ""}" +
                    if (ins.fourOp) " · 4-op: plays on FM 1-6 only, no chords" else " · 2-op: any channel, chords on spare voices")
            }
            else -> Unit
        }
    }

    /** Presets [delta] steps from the one the sound is (or the first) of [category] (null: all), replacing the sound. */
    private fun applyPreset(v: ViewController, delta: Int, category: Opl3Presets.Category?): Boolean {
        val st = v.state
        val presets = Opl3Presets.ALL.filter { category == null || it.category == category }
        if (presets.isEmpty()) return false
        val ins = sound(v.session.song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setOpl3Instrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        v.session.note("${preset.name}: ${Opl3Curated.summary(preset.instrument)}" +
            (preset.note?.let { " · voiced at ${Opl3System.noteName(it)}" } ?: ""))
        return ok
    }

    /** Chords page: Edit+←→ steps the interval under the cursor (±1, ↑↓ ±12), skipping 0; the append row adds a shape. */
    private fun adjustShape(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val chords = v.session.song.opl3.chords
        val shape = chords.getOrNull(st.envStep) ?: return addShape(v)
        val col = st.programField
        if (col >= shape.intervals.size) return addInterval(v)
        return v.session.editOpl3Chords("Edit chord shape") { list ->
            list.mapIndexed { i, ch ->
                if (i != st.envStep) ch else {
                    var n = ch.intervals[col] + dx + 12 * dy
                    if (n == 0) n += if (dx + dy > 0) 1 else -1
                    named(ch.intervals.toMutableList().also { it[col] = n.coerceIn(-48, 48) })
                }
            }
        }.also { if (it) shapeNote(v) }
    }

    private fun addShape(v: ViewController): Boolean {
        val st = v.state
        val chords = v.session.song.opl3.chords
        if (chords.size >= Opl3System.MAX_CHORDS) { v.session.note("The chord table holds at most ${Opl3System.MAX_CHORDS} shapes"); return false }
        val ok = v.session.editOpl3Chords("Add chord shape") { it + named(listOf(4, 7)) }
        st.envStep = chords.size
        st.programField = 0
        shapeNote(v)
        return ok
    }

    private fun addInterval(v: ViewController): Boolean {
        val st = v.state
        val shape = v.session.song.opl3.chords.getOrNull(st.envStep) ?: return false
        if (shape.intervals.size >= 4) return false
        val next = ((shape.intervals.lastOrNull() ?: 0) + 3).coerceAtMost(48)
        val ok = v.session.editOpl3Chords("Add chord interval") { list ->
            list.mapIndexed { i, ch -> if (i == st.envStep) named(ch.intervals + next) else ch }
        }
        st.programField = shape.intervals.size
        shapeNote(v)
        return ok
    }

    /** A shape of [intervals], named as the default table names them (a hand-made shape has no name). */
    fun named(intervals: List<Int>): Opl3Chord = Opl3Chord(Opl3Chords.DEFAULT.firstOrNull { it.intervals == intervals }?.name ?: "", intervals)

    /** "K03 7: C-4 + E-4 G-4 A#4", from the listening octave's C. */
    private fun shapeNote(v: ViewController) {
        val st = v.state
        val shape = v.session.song.opl3.chords.getOrNull(st.envStep) ?: return
        v.session.note("K${EditorSession.hex2(st.envStep + 1)} ${shape.name.ifEmpty { "shape" }}: ${shapeNotes(shape, v.defaultNote())}")
    }

    /** A shape's notes from [root]: "C-4 + E-4 G-4". */
    fun shapeNotes(shape: Opl3Chord, root: Int): String =
        "${Opl3System.noteName(root)} + " + shape.intervals.joinToString(" ") { (root + it).let { n -> if (n in Opl3Rules.NOTES) Opl3System.noteName(n) else "--" } }

    /** After an edit: a held Listen or Edit sounds the changed instrument again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        if (Opl3SoundFace.editPress(v)) return
        if (row(v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (Opl3SoundFace.editRelease(v, tap)) return
        val st = v.state
        val session = v.session
        val row = row(st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        val changed = when {
            row == Row.SLOT -> {
                if (empty) session.setOpl3Instrument(slot, defaultOpl3Instrument(slot), "New sound").also { session.note("Sound ${EditorSession.hex2(slot)}: a new sound to start from") }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.PRESET -> applyPreset(v, 1, null)
            row == Row.DRUM_PRESET -> applyPreset(v, 1, Opl3Presets.Category.DRUM)
            row in TOGGLES || row == Row.MODE -> session.editOpl3Instrument(slot, "Set ${row.label}") { step(it, row, operator(st), 1, 0) }
            row == Row.SHAPES -> {
                val chords = session.song.opl3.chords
                when {
                    st.envStep >= chords.size -> addShape(v)
                    st.programField >= chords[st.envStep].intervals.size -> addInterval(v)
                    else -> { shapeNote(v); false }
                }
            }
            empty && row in SOUND_ROWS -> session.setOpl3Instrument(slot, defaultOpl3Instrument(slot), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        if (Opl3SoundFace.clear(v)) return
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val row = row(st)
        val blank = defaultOpl3Instrument(slot)
        val reset = Opl3Curated.controls(blank)
        when (row) {
            Row.SLOT -> session.setOpl3Instrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.PRESET, Row.DRUM_PRESET -> session.setOpl3Instrument(slot, blank, "Reset sound")
            Row.NAME -> session.editOpl3Instrument(slot, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.ALGORITHM -> session.editOpl3Instrument(slot, "Reset algorithm") { Opl3Curated.withAlgorithm(it, reset.algorithm) }
            Row.RATIO -> session.editOpl3Instrument(slot, "Reset ratios") { Opl3Curated.withRatio(it, 0) }
            Row.WAVES -> session.editOpl3Instrument(slot, "Reset waveforms") { Opl3Curated.withWaves(it, 0) }
            Row.BRIGHTNESS -> session.editOpl3Instrument(slot, "Reset brightness") { Opl3Curated.withBrightness(it, maxOf(0, reset.brightness)) }
            Row.LEVEL -> session.editOpl3Instrument(slot, "Reset level") { Opl3Curated.withLevel(it, reset.level) }
            Row.FEEDBACK, Row.RAW_FEEDBACK -> session.editOpl3Instrument(slot, "Feedback off") { it.copy(feedback = 0) }
            Row.ATTACK -> session.editOpl3Instrument(slot, "Reset attack") { Opl3Curated.withAttack(it, reset.attack) }
            Row.DECAY -> session.editOpl3Instrument(slot, "Reset decay") { Opl3Curated.withDecay(it, reset.decay) }
            Row.SUSTAIN -> session.editOpl3Instrument(slot, "Reset sustain") { Opl3Curated.withSustain(it, reset.sustain) }
            Row.RELEASE -> session.editOpl3Instrument(slot, "Reset release") { Opl3Curated.withRelease(it, reset.release) }
            Row.TONE_DECAY -> session.editOpl3Instrument(slot, "Reset tone decay") { Opl3Curated.withToneDecay(it, maxOf(0, reset.toneDecay)) }
            Row.KEY_SCALE -> session.editOpl3Instrument(slot, "Key scaling off") { Opl3Curated.withKeyScale(it, 0) }
            Row.LFO -> session.editOpl3Instrument(slot, "Vibrato and tremolo off") { Opl3Curated.withLfo(it, 0) }
            Row.MODE, Row.CONNECTION -> session.editOpl3Instrument(slot, "Reset connection") { it.copy(algorithm = 0) }
            in GRID -> session.editOpl3Instrument(slot, "Reset ${row.label}") { ins ->
                val op = operator(st)
                if (op >= ins.operatorCount) ins else ins.withOperator(op, resetField(ins.operators[op], row))
            }
            Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED -> session.editOpl3Instrument(slot, "Sweep off") { it.copy(sweep = Opl3Sweep.NONE) }
            Row.SHAPES -> deleteInterval(v)
            else -> Unit // Listen, Page
        }
    }

    private fun resetField(o: Opl3Operator, row: Row): Opl3Operator {
        val d = Opl3Operator()
        return when (row) {
            Row.OP_MULT -> o.copy(mult = d.mult)
            Row.OP_TL -> o.copy(tl = d.tl)
            Row.OP_AR -> o.copy(ar = d.ar)
            Row.OP_DR -> o.copy(dr = d.dr)
            Row.OP_SL -> o.copy(sl = d.sl)
            Row.OP_RR -> o.copy(rr = d.rr)
            Row.OP_WAVE -> o.copy(wave = d.wave)
            Row.OP_KSL -> o.copy(ksl = d.ksl)
            Row.OP_AM -> o.copy(am = d.am)
            Row.OP_VIB -> o.copy(vib = d.vib)
            Row.OP_EGT -> o.copy(sustain = d.sustain)
            Row.OP_KSR -> o.copy(ksr = d.ksr)
            else -> o
        }
    }

    /** Clear on the Chords page: the interval under the cursor; a shape's last interval deletes the shape. */
    private fun deleteInterval(v: ViewController) {
        val st = v.state
        val chords = v.session.song.opl3.chords
        val shape = chords.getOrNull(st.envStep) ?: return
        val col = st.programField
        if (shape.intervals.size <= 1) {
            v.session.editOpl3Chords("Delete chord shape") { it.filterIndexed { i, _ -> i != st.envStep } }
            v.session.note("Deleted shape K${EditorSession.hex2(st.envStep + 1)}: Kxx effects naming later shapes now name the shape before")
            st.envStep = st.envStep.coerceAtMost(lastShape(v.session.song.opl3.chords))
            return
        }
        if (col >= shape.intervals.size) return
        v.session.editOpl3Chords("Delete chord interval") { list ->
            list.mapIndexed { i, ch -> if (i == st.envStep) named(ch.intervals.filterIndexed { k, _ -> k != col }) else ch }
        }
        st.programField = st.programField.coerceAtMost(shape.intervals.size - 2)
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val ch = listenChannel(st, ins)
        if (ch != st.listenChannel) v.session.note("${ins.name.ifEmpty { "This sound" }} is 4-op: listening on ${v.session.song.system.channels[ch].name} (4-op plays on FM 1-6)")
        v.startAudition(ch, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        if (Opl3SoundFace.command(v, a)) return true
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val row = row(st)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                val d = if (a == Action.NEXT_CHANNEL) 1 else -1
                if (row in GRID) moveOperator(v, d) else showPage(v, st.envelope + d)
            }
            Action.COPY -> if (Opl3SoundFace.panel(v).advanced && page(st) == Page.CHORDS) session.copyOpl3Chords() else session.copyOpl3Instrument(slot)
            Action.PASTE -> session.pasteOpl3(slot)
            Action.CUT -> if (!Opl3SoundFace.panel(v).advanced || page(st) != Page.CHORDS) {
                session.copyOpl3Instrument(slot)
                session.setOpl3Instrument(slot, null, "Cut sound")
            } else session.copyOpl3Chords()
            Action.DUPLICATE -> if (Opl3SoundFace.panel(v).advanced && row == Row.SHAPES) {
                val shape = session.song.opl3.chords.getOrNull(st.envStep) ?: return true
                if (session.song.opl3.chords.size >= Opl3System.MAX_CHORDS) { session.note("The chord table holds at most ${Opl3System.MAX_CHORDS} shapes"); return true }
                if (session.editOpl3Chords("Duplicate chord shape") { it.toMutableList().also { l -> l.add(st.envStep + 1, shape) } }) st.envStep++
            } else {
                if (slot >= Opl3System.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setOpl3Instrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (Opl3SoundFace.hints(v, h)) return
        val st = v.state
        val row = row(st)
        val song = v.session.song
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
            row == Row.PRESET || row == Row.DRUM_PRESET -> {
                h += Hint(v.editLr(), if (row == Row.PRESET) "Start from a preset" else "Drum presets"); h += Hint(v.k(Action.CLEAR), "Default sound")
            }
            row in GRID -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                if (row in TOGGLES) h += Hint(v.k(Action.EDIT), "Switch ${row.label}") else h += Hint(v.editLr(), "${row.label} ±1 (${v.ud()} coarse)")
                h += Hint(v.k(Action.CLEAR), "Default")
            }
            row == Row.SHAPES -> {
                val chords = song.opl3.chords
                if (st.envStep >= chords.size) h += Hint(v.k(Action.EDIT), "Add a shape")
                else {
                    h += Hint(v.lr(), "Interval")
                    h += Hint(v.editLr(), "Semitones ±1 (${v.ud()} octave)")
                    h += Hint(v.k(Action.CLEAR), "Delete interval")
                    h += Hint(v.k(Action.DUPLICATE), "Duplicate shape")
                }
            }
            row == Row.MODE -> { h += Hint(v.k(Action.EDIT), "2-op / 4-op"); h += Hint(v.k(Action.CLEAR), "Connection 0") }
            row == Row.ALGORITHM || row == Row.RATIO || row == Row.WAVES || row == Row.KEY_SCALE || row == Row.LFO || row == Row.CONNECTION ->
                { h += Hint(v.editLr(), "Choose"); h += Hint(v.k(Action.CLEAR), "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
        }
        if (row != Row.SLOT && row != Row.NAME && row != Row.LISTEN && row != Row.PAGE && row !in GRID && row != Row.SHAPES) h += Hint(v.lr(), "Sound slot")
        if (row !in GRID) h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${Opl3System.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (page(st) == Page.CHORDS) "Copy/paste chord shapes" else "Copy/paste sound")
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
            "Sound face" to listOf(
                ud to "Choose instrument slot; $lr chooses mode, name or a musical control",
                editLr to "Shape the selected sound; $edit on mode switches Sound / Advanced without changing the patch",
                "Organ" to "Voicing selects 2/4-op routing; Drawbar shapes modulation; Reed shapes output waves; Contour, Release and Drop shape articulation",
                "Touch" to "Drag a control; compact panes use arrows to page. Advanced retains presets, exact registers and chords",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, page, then the page's settings",
                lr to "On settings: the sound slot; on the Expert grid: the operator; on a chord shape: its intervals",
                channels to "Pages: Sound (curated), Expert (every register), Drum (pitch sweep), Chords (Kxx shapes); on the Expert grid: the operator",
                "Listen on" to "The channel $listen plays the sound on (4-op sounds on FM 1-6). Holding $edit on a setting sounds the sound and every change restarts it",
                editLr to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new sound (the Lead preset) to start from",
            ),
            "Sound page" to listOf(
                "Preset" to "Starting sounds: E-piano, brass, bell, organs, bass, pad, lead, pluck, marimba, clav, flute and pitched drums (kick, snare, tom, hats)",
                "Algorithm" to "How the operators connect: 2-op FM (1→2) or additive (1+2); 4-op chain, two pairs, 1 + chain, 1 + pair + 4. " +
                    "Modulators (→) colour the carriers you hear; 4-op sounds play on FM 1-6 and take no chords",
                "Ratios" to "Frequency multipliers of the operators, operator 1 first (½ to 15): whole-number pairs are harmonic, others bell-like",
                "Waveforms" to "Operator waveforms (sine, half, abs, pulse, alt, camel, square, saw), curated combinations",
                "Brightness" to "How strongly the modulators act (their level): 0 dull, 63 brightest. Level is the carriers' loudness",
                "Feedback" to "Operator 1 modulating itself, 0-7: from pure to noisy",
                "Envelope" to "Attack, decay (to the sustain level), sustain (0 = percussive: fades while held), release after OFF; " +
                    "tone decay is how fast the brightness fades. 15 is fastest",
                "Key scaling" to "Higher notes duller (Soft) or also quieter with faster envelopes (Strong)",
                "Vib/Trem" to "The chip's vibrato (6.1 Hz) and tremolo (3.7 Hz); their depths are song settings (Song view)",
                "≈" to "A value marked ≈ was hand-edited on the Expert page: the nearest curated choice is shown, and stepping it replaces those fields",
            ),
            "Expert, Drum, Chords" to listOf(
                "Expert" to "Operators (2-op/4-op), connection and feedback as stored, then per operator MULT, TL (level, 0 loudest), AR, DR, SL, RR, " +
                    "WAVE, KSL, and the AM, VIB, EGT (sustain) and KSR bits ($edit switches them). Carriers are marked ♪",
                "Drum" to "Pitch sweep: each note starts at the start offset (semitones from the note) and moves to the end at speed/16 semitones per tick. " +
                    "The note picks the drum's pitch",
                "Chords" to "The song's chord shapes for Kxx: up to 4 intervals each (negative for inversions). $edit adds a shape or interval, " +
                    "$editLr changes one, $clear deletes it; Hxy plays +x and +y without a shape",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = Opl3SoundPainter(pane)
}
