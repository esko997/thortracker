package org.thortracker.system.opm

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.NumberText
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.fm.FmControls
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The OPM instrument editor, controller-only: the one bank of [OpmSystem.SLOTS] sounds, each empty or a 4-operator
 * YM2151 voice. Head rows: slot, name, the channel Listen plays on, the page. The Mega Drive editor's FM pages (shared
 * FM pieces): *Sound*, the curated controls of [OpmCurated] beside the drawn algorithm and envelope; *Expert*,
 * algorithm, feedback, PMS/AMS and every OPM operator field (DT2 included) as a grid; *Drum*, the noise generator
 * (operator 4 plays it; FM 8 only) and the pitch sweep.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [PAGES]), [ViewState.envStep] (Expert: the operator 0..3), [ViewState.listenChannel].
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object OpmInstrumentView : SystemView() {
    enum class Page(val label: String) { SOUND("Sound"), EXPERT("Expert"), DRUM("Drum") }

    val PAGES = Page.entries.toList()

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), PAGE("Page"),
        PRESET("Preset"), ALGORITHM("Algorithm"), RATIO("Ratios"), BRIGHTNESS("Brightness"), LEVEL("Level"), FEEDBACK("Feedback"),
        ATTACK("Attack"), DECAY("Decay"), SUSTAIN("Sustain"), SUSTAIN_RATE("Sustain rate"), RELEASE("Release"),
        TONE_DECAY("Tone decay"), KEY_SCALE("Key scaling"), VIBRATO("Vibrato"), TREMOLO("Tremolo"),
        RAW_ALGORITHM("Algorithm"), RAW_FEEDBACK("Feedback"), PMS("PMS"), AMS("AMS"),
        OP_DT1("DT1"), OP_MUL("MUL"), OP_TL("TL"), OP_KS("KS"), OP_AR("AR"), OP_AM("AM"), OP_D1R("D1R"), OP_DT2("DT2"),
        OP_D2R("D2R"), OP_D1L("D1L"), OP_RR("RR"),
        DRUM_PRESET("Drum preset"), NOISE("Noise"), NOISE_FREQ("Noise freq"),
        SWEEP_START("Sweep start"), SWEEP_END("Sweep end"), SWEEP_SPEED("Sweep speed"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE)

    /** The Expert grid rows: one register field each, all operators side by side, in register order ($40 … $E0). */
    val GRID = listOf(Row.OP_DT1, Row.OP_MUL, Row.OP_TL, Row.OP_KS, Row.OP_AR, Row.OP_AM, Row.OP_D1R, Row.OP_DT2, Row.OP_D2R,
        Row.OP_D1L, Row.OP_RR)

    private val SWEEP = listOf(Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED)

    private val ROWS = mapOf(
        Page.SOUND to HEAD + listOf(Row.PRESET, Row.ALGORITHM, Row.RATIO, Row.BRIGHTNESS, Row.LEVEL, Row.FEEDBACK, Row.ATTACK,
            Row.DECAY, Row.SUSTAIN, Row.SUSTAIN_RATE, Row.RELEASE, Row.TONE_DECAY, Row.KEY_SCALE, Row.VIBRATO, Row.TREMOLO),
        Page.EXPERT to HEAD + listOf(Row.RAW_ALGORITHM, Row.RAW_FEEDBACK, Row.PMS, Row.AMS) + GRID,
        Page.DRUM to HEAD + listOf(Row.DRUM_PRESET, Row.NOISE, Row.NOISE_FREQ) + SWEEP,
    )

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = ROWS.values.flatMap { it.drop(HEAD.size) }.toSet()

    /** On/off settings: a tap switches them. */
    private val TOGGLES = setOf(Row.OP_AM, Row.NOISE)

    /** List controls: Edit+←→ chooses, wrapping. */
    private val LISTS = setOf(Row.ALGORITHM, Row.RATIO, Row.KEY_SCALE, Row.RAW_ALGORITHM)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun sound(song: Song, state: ViewState): OpmInstrument? = song.opmInstrument(state.instrument)

    fun page(state: ViewState): Page = PAGES[state.envelope.coerceIn(0, PAGES.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(page(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The operator (0-based) the Expert grid's cursor is on. */
    fun operator(state: ViewState): Int = state.envStep.coerceIn(0, 3)

    /** The channel a held Listen plays [ins] on: a noise sound always on FM 8 (the noise generator's channel), else Listen's. */
    fun listenChannel(state: ViewState, ins: OpmInstrument?): Int =
        if (ins?.noise == true) OpmRules.NOISE_CHANNEL else state.listenChannel

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, OpmSystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, v.session.song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, PAGES.size - 1)
        OpmSoundFace.normalise(v, rows(st).size - 1)
        if (page(st) == Page.EXPERT) st.envStep = st.envStep.coerceIn(0, 3)
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, OpmSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry. */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, context = ViewKind.KEYBOARD))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(PAGES.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, OpmSystem.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (OpmSoundFace.direction(v, dx, dy)) return
        val st = v.state
        val song = v.session.song
        val rows = rows(st)
        val row = row(st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row in GRID -> st.envStep = (st.envStep + dx).coerceIn(0, 3)
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val d = dx + dy
        val row = row(st)
        val changed = when (row) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> {
                val cur = sound(session.song, st) ?: defaultOpmInstrument(slot)
                session.setOpmInstrument(slot, cur.copy(name = NameEdit.edit(cur.name, st.textPos, dx + 26 * dy, NAME_MAX)), "Rename sound")
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.PRESET -> applyPreset(v, d, drums = false)
            Row.DRUM_PRESET -> applyPreset(v, d, drums = true)
            Row.NOISE -> switchNoise(v)
            in SWEEP -> session.editOpmInstrument(slot, "Set ${row.label.lowercase()}") { it.copy(sweep = stepSweep(it.sweep, row, dx, dy)) }
            else -> session.editOpmInstrument(slot, "Set ${row.label.lowercase()}") { step(it, row, operator(st), dx, dy) }
        }
        if (changed) {
            curatedNote(v)
            heard(v)
        }
    }

    /** [s] with [row]'s value stepped: start/end by semitones (↑↓ octaves), speed by 1 (↑↓ 16). */
    fun stepSweep(s: OpmSweep, row: Row, dx: Int, dy: Int): OpmSweep = when (row) {
        Row.SWEEP_START -> (s.start + dx + 12 * dy).coerceIn(-96, 96).let { s.copy(start = it, speed = FmControls.sweepSpeed(s.speed, it, s.end)) }
        Row.SWEEP_END -> (s.end + dx + 12 * dy).coerceIn(-96, 96).let { s.copy(end = it, speed = FmControls.sweepSpeed(s.speed, s.start, it)) }
        else -> s.copy(speed = (s.speed + dx + 16 * dy).coerceIn(0, 255))
    }

    /** [ins] with [row]'s value stepped by [dx] (fine) and [dy] (coarse); list controls wrap. */
    fun step(ins: OpmInstrument, row: Row, op: Int, dx: Int, dy: Int): OpmInstrument {
        val c = OpmCurated.controls(ins)
        val d = dx + dy
        fun wrap(i: Int, size: Int) = (i + d).mod(size)
        return when (row) {
            Row.ALGORITHM -> OpmCurated.withAlgorithm(ins, wrap(c.algorithm, OpmCurated.ALGORITHMS.size))
            Row.RATIO -> OpmCurated.withRatio(ins, wrap(c.ratio, OpmCurated.RATIOS.size))
            Row.BRIGHTNESS -> if (c.brightness < 0) ins else OpmCurated.withBrightness(ins, (c.brightness + dx + COARSE * dy).coerceIn(0, 127))
            Row.LEVEL -> OpmCurated.withLevel(ins, (c.level + dx + COARSE * dy).coerceIn(0, 127))
            Row.FEEDBACK -> OpmCurated.withFeedback(ins, (c.feedback + d).coerceIn(0, 7))
            Row.ATTACK -> OpmCurated.withAttack(ins, (c.attack + dx + 4 * dy).coerceIn(0, 31))
            Row.DECAY -> OpmCurated.withDecay(ins, (c.decay + dx + 4 * dy).coerceIn(0, 31))
            Row.SUSTAIN -> OpmCurated.withSustain(ins, (c.sustain + dx + 4 * dy).coerceIn(0, 15))
            Row.SUSTAIN_RATE -> OpmCurated.withSustainRate(ins, (c.sustainRate + dx + 4 * dy).coerceIn(0, 31))
            Row.RELEASE -> OpmCurated.withRelease(ins, (c.release + dx + 4 * dy).coerceIn(0, 15))
            Row.TONE_DECAY -> if (c.toneDecay < 0) ins else OpmCurated.withToneDecay(ins, (c.toneDecay + dx + 4 * dy).coerceIn(0, 31))
            Row.KEY_SCALE -> OpmCurated.withKeyScale(ins, wrap(c.keyScale, OpmCurated.KEY_SCALES.size))
            Row.VIBRATO -> OpmCurated.withVibrato(ins, (c.vibrato + d).coerceIn(0, 7))
            Row.TREMOLO -> OpmCurated.withTremolo(ins, (c.tremolo + d).coerceIn(0, 3))
            Row.RAW_ALGORITHM -> ins.copy(algorithm = wrap(ins.algorithm, 8))
            Row.RAW_FEEDBACK -> ins.copy(feedback = (ins.feedback + d).coerceIn(0, 7))
            Row.PMS -> ins.copy(pms = (ins.pms + d).coerceIn(0, 7))
            Row.AMS -> ins.copy(ams = (ins.ams + d).coerceIn(0, 3))
            Row.NOISE -> OpmCurated.withNoise(ins, !ins.noise)
            Row.NOISE_FREQ -> OpmCurated.withNoiseFrequency(ins, (ins.noiseFrequency + dx + 4 * dy).coerceIn(0, 31))
            in GRID -> ins.withOperator(op, stepOperator(ins.operators[op], row, dx, dy))
            else -> ins
        }
    }

    /** One register field of operator [o] stepped: values clamp to the field's range, DT1 wraps 0..7, AM switches. */
    fun stepOperator(o: OpmOperator, row: Row, dx: Int, dy: Int): OpmOperator {
        val d = dx + dy
        return when (row) {
            Row.OP_DT1 -> o.copy(dt1 = (o.dt1 + d).mod(8))
            Row.OP_MUL -> o.copy(mul = (o.mul + d).coerceIn(0, 15))
            Row.OP_TL -> o.copy(tl = (o.tl + dx + COARSE * dy).coerceIn(0, 127))
            Row.OP_KS -> o.copy(ks = (o.ks + d).coerceIn(0, 3))
            Row.OP_AR -> o.copy(ar = (o.ar + dx + 4 * dy).coerceIn(0, 31))
            Row.OP_AM -> o.copy(am = !o.am)
            Row.OP_D1R -> o.copy(d1r = (o.d1r + dx + 4 * dy).coerceIn(0, 31))
            Row.OP_DT2 -> o.copy(dt2 = (o.dt2 + d).coerceIn(0, 3))
            Row.OP_D2R -> o.copy(d2r = (o.d2r + dx + 4 * dy).coerceIn(0, 31))
            Row.OP_D1L -> o.copy(d1l = (o.d1l + dx + 4 * dy).coerceIn(0, 15))
            Row.OP_RR -> o.copy(rr = (o.rr + dx + 4 * dy).coerceIn(0, 15))
            else -> o
        }
    }

    /** Noise on ⇄ off; a sound with noise listens on FM 8, the only channel whose operator 4 the noise generator feeds. */
    private fun switchNoise(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val ok = session.editOpmInstrument(slot, "Set noise") { OpmCurated.withNoise(it, !it.noise) }
        val ins = sound(session.song, st) ?: return ok
        if (ins.noise) {
            st.listenChannel = OpmRules.NOISE_CHANNEL
            session.note("Noise on: operator 4 plays the noise generator at ${OpmCurated.noiseText(ins.noiseFrequency)}; " +
                "the sound plays on ${channelName(session.song, OpmRules.NOISE_CHANNEL)} only")
        } else {
            session.note("Noise off: operator 4 is a sine again; the sound plays on every channel")
        }
        return ok
    }

    private fun channelName(song: Song, ch: Int): String = song.system.channels.getOrNull(ch)?.name ?: "FM ${ch + 1}"

    /** A status line for controls whose names say more than the row can (algorithm routing, the song's LFO depths). */
    private fun curatedNote(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st) ?: return
        val opm = song.opm
        when (row(st)) {
            Row.ALGORITHM, Row.RAW_ALGORITHM -> {
                val a = OpmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)]
                v.session.note("Algorithm ${a.number}: ${a.name} ${a.diagram} · carriers ${a.carriers.joinToString("+")}")
            }
            Row.VIBRATO, Row.PMS -> when {
                ins.pms == 0 -> Unit
                opm.pmd == 0 -> v.session.note("The song's LFO pitch depth is 0 (Song view or Vxx): vibrato is silent until it is raised")
                OpmRules.lfoHz(opm.lfoRate) < 0.5 -> v.session.note(lfoSlow(opm.lfoRate))
                else -> Unit
            }
            Row.TREMOLO, Row.AMS -> when {
                ins.ams == 0 -> Unit
                opm.amd == 0 -> v.session.note("The song's LFO amplitude depth is 0 (Song view or Txx): tremolo is silent until it is raised")
                OpmRules.lfoHz(opm.lfoRate) < 0.5 -> v.session.note(lfoSlow(opm.lfoRate))
                else -> Unit
            }
            else -> Unit
        }
    }

    private fun lfoSlow(rate: Int): String =
        "The song's LFO rate $rate is ${NumberText.fixed(OpmRules.lfoHz(rate), 3)} Hz (Song view or Lxx): too slow to hear as vibrato or tremolo"

    /** The presets the Preset rows offer: every preset, or [drums] the drums and noise sounds. */
    fun presets(drums: Boolean): List<OpmPresets.Preset> =
        if (drums) OpmPresets.ALL.filter { it.category != OpmPresets.Category.FM } else OpmPresets.ALL

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound; noise presets listen on FM 8. */
    private fun applyPreset(v: ViewController, delta: Int, drums: Boolean): Boolean {
        val st = v.state
        val presets = presets(drums)
        val ins = sound(v.session.song, st)
        val preset = presets[FmControls.presetStep(presets.indexOfFirst { it.instrument == ins }, delta, presets.size)]
        val ok = v.session.setOpmInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        if (preset.instrument.noise) st.listenChannel = OpmRules.NOISE_CHANNEL
        v.session.note("${preset.name}: ${OpmCurated.summary(preset.instrument)}" + (preset.note?.let { " · voiced at ${OpmSystem.noteName(it)}" } ?: ""))
        return ok
    }

    /** After an edit: a held Listen or Edit sounds the changed instrument again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        if (OpmSoundFace.editPress(v)) return
        if (row(v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (OpmSoundFace.editRelease(v, tap)) return
        val st = v.state
        val session = v.session
        val row = row(st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        val changed = when {
            row == Row.SLOT -> {
                if (empty) session.setOpmInstrument(slot, defaultOpmInstrument(slot), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: a new sound to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.PRESET -> applyPreset(v, 1, drums = false)
            row == Row.DRUM_PRESET -> applyPreset(v, 1, drums = true)
            row == Row.NOISE -> switchNoise(v)
            row in TOGGLES -> session.editOpmInstrument(slot, "Set ${row.label}") { step(it, row, operator(st), 1, 0) }
            empty && row in SOUND_ROWS -> session.setOpmInstrument(slot, defaultOpmInstrument(slot), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        if (OpmSoundFace.clear(v)) return
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val row = row(st)
        val blank = defaultOpmInstrument(slot)
        val reset = OpmCurated.controls(blank)
        fun edit(label: String, f: (OpmInstrument) -> OpmInstrument) { session.editOpmInstrument(slot, label, f) }
        when (row) {
            Row.SLOT -> session.setOpmInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.PRESET, Row.DRUM_PRESET -> session.setOpmInstrument(slot, blank, "Reset sound")
            Row.NAME -> sound(session.song, st)?.let { session.setOpmInstrument(slot, it.copy(name = ViewController.deleteChar(it.name, st.textPos)), "Rename sound") }
            Row.ALGORITHM -> edit("Reset algorithm") { OpmCurated.withAlgorithm(it, reset.algorithm) }
            Row.RATIO -> edit("Reset ratios") { OpmCurated.withRatio(it, 0) }
            Row.BRIGHTNESS -> edit("Reset brightness") { OpmCurated.withBrightness(it, maxOf(0, reset.brightness)) }
            Row.LEVEL -> edit("Reset level") { OpmCurated.withLevel(it, reset.level) }
            Row.FEEDBACK, Row.RAW_FEEDBACK -> edit("Feedback off") { it.copy(feedback = 0) }
            Row.ATTACK -> edit("Reset attack") { OpmCurated.withAttack(it, reset.attack) }
            Row.DECAY -> edit("Reset decay") { OpmCurated.withDecay(it, reset.decay) }
            Row.SUSTAIN -> edit("Reset sustain") { OpmCurated.withSustain(it, reset.sustain) }
            Row.SUSTAIN_RATE -> edit("Sustain rate off") { OpmCurated.withSustainRate(it, 0) }
            Row.RELEASE -> edit("Reset release") { OpmCurated.withRelease(it, reset.release) }
            Row.TONE_DECAY -> edit("Reset tone decay") { OpmCurated.withToneDecay(it, maxOf(0, reset.toneDecay)) }
            Row.KEY_SCALE -> edit("Key scaling off") { OpmCurated.withKeyScale(it, 0) }
            Row.VIBRATO, Row.PMS -> edit("Vibrato off") { OpmCurated.withVibrato(it, 0) }
            Row.TREMOLO -> edit("Tremolo off") { OpmCurated.withTremolo(it, 0) }
            Row.AMS -> edit("AMS off") { it.copy(ams = 0) }
            Row.RAW_ALGORITHM -> edit("Algorithm 0") { it.copy(algorithm = 0) }
            Row.NOISE -> edit("Noise off") { OpmCurated.withNoise(it, false) }
            Row.NOISE_FREQ -> edit("Reset noise frequency") { OpmCurated.withNoiseFrequency(it, OpmCurated.DEFAULT_NOISE_FREQUENCY) }
            in GRID -> edit("Reset ${row.label}") { it.withOperator(operator(st), resetField(it.operators[operator(st)], row)) }
            in SWEEP -> edit("Sweep off") { it.copy(sweep = OpmSweep.NONE) }
            else -> Unit // Listen, Page
        }
    }

    private fun resetField(o: OpmOperator, row: Row): OpmOperator {
        val d = OpmOperator()
        return when (row) {
            Row.OP_DT1 -> o.copy(dt1 = d.dt1)
            Row.OP_MUL -> o.copy(mul = d.mul)
            Row.OP_TL -> o.copy(tl = d.tl)
            Row.OP_KS -> o.copy(ks = d.ks)
            Row.OP_AR -> o.copy(ar = d.ar)
            Row.OP_AM -> o.copy(am = d.am)
            Row.OP_D1R -> o.copy(d1r = d.d1r)
            Row.OP_DT2 -> o.copy(dt2 = d.dt2)
            Row.OP_D2R -> o.copy(d2r = d.d2r)
            Row.OP_D1L -> o.copy(d1l = d.d1l)
            Row.OP_RR -> o.copy(rr = d.rr)
            else -> o
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val ins = sound(v.session.song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        val ch = listenChannel(st, ins)
        if (ch != st.listenChannel) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} uses the noise generator: listening on ${channelName(v.session.song, ch)}, the only channel it plays on")
        }
        v.startAudition(ch, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        if (OpmSoundFace.command(v, a)) return true
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val row = row(st)
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                val d = if (a == Action.NEXT_CHANNEL) 1 else -1
                if (row in GRID) st.envStep = (st.envStep + d).coerceIn(0, 3) else showPage(v, st.envelope + d)
            }
            Action.COPY -> session.copyOpmInstrument(slot)
            Action.PASTE -> session.pasteOpm(slot)
            Action.CUT -> {
                session.copyOpmInstrument(slot)
                session.setOpmInstrument(slot, null, "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= OpmSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                session.setOpmInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (OpmSoundFace.hints(v, h)) return
        val st = v.state
        val song = v.session.song
        val row = row(st)
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
                h += Hint(v.editLr(), if (row == Row.PRESET) "Start from a preset" else "Drum and noise presets"); h += Hint(v.k(Action.CLEAR), "Default sound")
            }
            row == Row.NOISE -> { h += Hint(v.k(Action.EDIT), if (ins?.noise == true) "Noise off" else "Noise on (FM 8 only)"); h += Hint(v.k(Action.CLEAR), "Off") }
            row in GRID -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                if (row in TOGGLES) h += Hint(v.k(Action.EDIT), "Switch ${row.label}") else h += Hint(v.editLr(), "${row.label} ±1 (${v.ud()} coarse)")
                h += Hint(v.k(Action.CLEAR), "Default")
            }
            row in LISTS -> { h += Hint(v.editLr(), "Choose"); h += Hint(v.k(Action.CLEAR), "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Default") }
        }
        if (row !in HEAD && row !in GRID) h += Hint(v.lr(), "Sound slot")
        if (row !in GRID) h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${OpmSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), "Copy/paste sound")
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
                "Workstation" to "Colour and Alloy shape modulators, Contour shapes outputs, Bite adds feedback, Noise uses FM 8, Fall adds pitch drop",
                "Touch" to "Drag a control; compact panes use arrows to page. Advanced retains routing, ratios and the shared LFO",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, page, then the page's settings",
                lr to "On settings: the sound slot; on the Expert grid: the operator",
                channels to "Pages: Sound (curated), Expert (every register), Drum (noise and pitch sweep)",
                "Listen on" to "The channel $listen plays the sound on (FM 1-8); a sound with noise always plays on FM 8. " +
                    "Holding $edit on a setting sounds the sound and every change restarts it",
                editLr to "Change the value (${keys.held(Action.EDIT, ud)} in bigger steps); lists step through their choices",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new sound to start from (the Lead preset)",
            ),
            "Sound page" to listOf(
                "Preset" to "Starting sounds: lead, brass, E-piano, slap and finger bass, strings, a DT2 bell, organ, pluck, LFO pad, synth sweep, " +
                    "FM drums (kick, tom, snare body) and noise sounds for FM 8 (hi-hat, open hat, snare noise, crash)",
                "Algorithm" to "How the four operators connect (0 chain … 7 additive). Modulators (→) colour the carriers you hear; carriers sum to the output",
                "Ratios" to "Frequency multipliers of operators 1-4 (½ to 15) with detune: whole-number sets are harmonic, chorus/shimmer detune pairs; " +
                    "bell, chime, metal, gong, glass and tritone use DT2, the YM2151's coarse detune (×1.41, ×1.57, ×1.73), for inharmonic partials",
                "Brightness" to "How strongly the modulators act (their level): 0 dull, 127 brightest. Level is the carriers' loudness",
                "Feedback" to "Operator 1 modulating itself, 0-7: from pure to noisy",
                "Envelope" to "Attack, decay to the sustain level, sustain (15 = full, 0 = fades to silence), sustain rate (keeps fading while held), " +
                    "release after OFF; tone decay is how fast the brightness fades. 31 (15 for release) is fastest",
                "Key scaling" to "Faster envelopes on higher notes, like real instruments",
                "Vibrato/Tremolo" to "The sound's LFO sensitivities PMS and AMS (AM on the carriers). The depths shown are the chip manual's at the song's " +
                    "full LFO depth (PMD/AMD 127); the song's one shared LFO (Song view, or Lxx rate, Vxx pitch depth, Txx amplitude depth, Wxx waveform) " +
                    "scales them, and at depth 0 they are silent",
                "≈" to "A value marked ≈ was hand-edited on the Expert page: the nearest curated choice is shown, and stepping it replaces those fields",
            ),
            "Expert and Drum pages" to listOf(
                "Expert" to "Algorithm, feedback, PMS and AMS as stored, then per operator DT1 (fine detune), MUL, TL (level, 0 loudest), KS, AR, " +
                    "AM ($edit switches), D1R, DT2 (coarse detune), D2R, D1L (sustain level) and RR. Carriers are marked ♪",
                "Noise" to "Operator 4 plays the YM2151's one noise generator instead of its sine, at the noise frequency (0-31, 3.9 kHz to 125 kHz); " +
                    "the chip wires it to FM 8 only, so a sound with noise plays on FM 8 and Listen moves there. Nxx changes the frequency while playing",
                "Sweep" to "Pitch sweep: each note starts at the start offset (semitones from the note) and moves to the end at speed/16 semitones per tick. " +
                    "The note picks the drum's pitch; the noise's own frequency does not follow it",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = OpmSoundPainter(pane)
}
