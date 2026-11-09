package org.thortracker.system.opn

import org.thortracker.backend.OpnPlayerImage
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
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesEnvelopeKind
import org.thortracker.system.nes.NesEnvelopes
import org.thortracker.system.nes.S5bEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.ym2612.YmCurated
import org.thortracker.system.ym2612.YmFmInstrument
import org.thortracker.system.ym2612.YmInstrumentView
import org.thortracker.system.ym2612.YmOperator
import org.thortracker.system.ym2612.YmPresets
import org.thortracker.system.ym2612.YmSweep
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter
import org.thortracker.system.ym2612.YmInstrumentView.Row as YmRow

/**
 * The OPN-family instrument editor (YM2203 and YM2608), controller-only: the one list of [OpnSound.SLOTS] sounds, each
 * empty or of one part — an FM voice, an SSG sound and, on the YM2608, a rhythm sound or an ADPCM-B sound. Head rows:
 * slot, name, the channel Listen plays on, the sound's type (its part), the page. Pages by part:
 * - FM: *Sound* (the Mega Drive editor's curated controls, [YmCurated], over the same OPN registers), *Expert* (every
 *   register; AM and AMS/FMS only on the YM2608, whose LFO they use), *Ch3* (FM 3's four operator pitch offsets for its
 *   special and CSM modes, and the song's FM 3 mode), *Drum* (the pitch sweep and drum presets).
 * - SSG: the *Volume*, *Arpeggio*, *Pitch*, *Mixer* and *Noise* envelopes (NES editor style, step by step), the
 *   *Hardware env* page (the SSG's shared envelope generator) and *Drum* (the sweep).
 * - Rhythm: level, pan and the rhythm ROM (status; Edit imports it).
 * - ADPCM: *Sample* (which song sample, root note, level, pan, loop; the sample's name, trimming, tools and adding
 *   samples, [OpnSampleMenu]) and *Drum* (the sweep).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep] (Expert and Ch3: the operator 0..3; envelopes: the step, size = append),
 * [ViewState.listenChannel], [ViewState.textPos] (the sound's or the sample's name). Holding Edit on a sound row sounds
 * the sound and every change restarts it.
 */
object OpnInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: NesEnvelopeKind? = null) {
        SOUND("Sound"), EXPERT("Expert"), CH3("Ch3"), FM_DRUM("Drum"),
        VOLUME("Volume", NesEnvelopeKind.VOLUME), ARPEGGIO("Arpeggio", NesEnvelopeKind.ARPEGGIO), PITCH("Pitch", NesEnvelopeKind.PITCH),
        MIXER("Mixer", NesEnvelopeKind.MIXER), NOISE("Noise", NesEnvelopeKind.NOISE), HARDWARE("Hw env"),
        RHYTHM("Rhythm"), SAMPLE("Sample"), DRUM("Drum"),
    }

    val FM_PAGES = listOf(Page.SOUND, Page.EXPERT, Page.CH3, Page.FM_DRUM)
    val SSG_PAGES = listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.MIXER, Page.NOISE, Page.HARDWARE, Page.DRUM)
    val RHYTHM_PAGES = listOf(Page.RHYTHM)
    val ADPCM_PAGES = listOf(Page.SAMPLE, Page.DRUM)

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
        ENV_SHAPE("Shape"), ENV_AUTO("Period"), ENV_OCTAVE("Octave"), ENV_PERIOD("Manual"),
        RHYTHM_LEVEL("Level"), PAN("Pan"), RHYTHM_ROM("Rhythm ROM"),
        SAMPLE("Sample"), ROOT("Root note"), ADPCM_LEVEL("Level"), LOOP_SAMPLE("Loop"), SAMPLE_NAME("Sample name"),
        TRIM_START("Trim start"), TRIM_END("Trim end"), SAMPLE_TOOLS("Tools"), SAMPLE_ADD("Add sample"),
    }

    val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.TYPE, Row.PAGE)

    /** The Expert grid: one register field per row, the four operators side by side; AM only on the YM2608. */
    val GRID_OPNA = listOf(Row.OP_DT, Row.OP_MUL, Row.OP_TL, Row.OP_KS, Row.OP_AR, Row.OP_AM, Row.OP_D1R, Row.OP_D2R, Row.OP_SL,
        Row.OP_RR, Row.OP_SSG)
    val GRID_OPN = GRID_OPNA - Row.OP_AM

    /** The Ch3 grid: each operator's pitch offset, semitones and 1/64 semitone. */
    val CH3_GRID = listOf(Row.CH3_SEMI, Row.CH3_FINE)

    /** Rows whose cursor moves between operators (←→, L1/R1). */
    private val GRIDS: Set<Row> = (GRID_OPNA + CH3_GRID).toSet()

    private val SWEEP = listOf(Row.SWEEP_START, Row.SWEEP_END, Row.SWEEP_SPEED)

    private val SOUND_OPNA = listOf(Row.PRESET, Row.ALGORITHM, Row.RATIO, Row.BRIGHTNESS, Row.LEVEL, Row.FEEDBACK, Row.ATTACK,
        Row.DECAY, Row.SUSTAIN, Row.SUSTAIN_RATE, Row.RELEASE, Row.TONE_DECAY, Row.KEY_SCALE, Row.VIBRATO, Row.TREMOLO, Row.SSG)
    private val SOUND_OPN = SOUND_OPNA - Row.VIBRATO - Row.TREMOLO

    private fun rowsFor(opna: Boolean): Map<Page, List<Row>> = mapOf(
        Page.SOUND to HEAD + if (opna) SOUND_OPNA else SOUND_OPN,
        Page.EXPERT to HEAD + listOf(Row.RAW_ALGORITHM, Row.RAW_FEEDBACK) + (if (opna) listOf(Row.AMS, Row.FMS) + GRID_OPNA else GRID_OPN),
        Page.CH3 to HEAD + listOf(Row.FM3_MODE) + CH3_GRID,
        Page.FM_DRUM to HEAD + listOf(Row.DRUM_PRESET) + SWEEP,
        Page.VOLUME to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.ENV_RELEASE, Row.STEPS),
        Page.ARPEGGIO to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.PITCH to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.MODE, Row.STEPS),
        Page.MIXER to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.NOISE to HEAD + listOf(Row.PRESET, Row.LENGTH, Row.LOOP, Row.STEPS),
        Page.HARDWARE to HEAD + listOf(Row.ENV_SHAPE, Row.ENV_AUTO, Row.ENV_OCTAVE, Row.ENV_PERIOD),
        Page.RHYTHM to HEAD + listOf(Row.PRESET, Row.RHYTHM_LEVEL, Row.PAN, Row.RHYTHM_ROM),
        Page.SAMPLE to HEAD + listOf(Row.SAMPLE, Row.ROOT, Row.ADPCM_LEVEL, Row.PAN, Row.LOOP_SAMPLE, Row.SAMPLE_NAME, Row.TRIM_START,
            Row.TRIM_END, Row.SAMPLE_TOOLS, Row.SAMPLE_ADD),
        Page.DRUM to HEAD + SWEEP,
    )

    private val ROWS_OPN = rowsFor(false)
    private val ROWS_OPNA = rowsFor(true)

    /** The FM rows [YmInstrumentView.step] edits (the same OPN registers as the Mega Drive's). */
    private val YM: Map<Row, YmRow> = (SOUND_OPNA - Row.PRESET + listOf(Row.RAW_ALGORITHM, Row.RAW_FEEDBACK, Row.AMS, Row.FMS) + GRID_OPNA)
        .associateWith { YmRow.valueOf(it.name) }

    /** Rows that are not about how the sound plays: holding Edit on them does not sound it. */
    private val QUIET = setOf(Row.STEPS, Row.RHYTHM_ROM, Row.SAMPLE_NAME, Row.SAMPLE_TOOLS, Row.SAMPLE_ADD)

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS: Set<Row> = Row.entries.toSet() - HEAD.toSet() - QUIET + Row.TYPE

    /** List controls: Edit+←→ chooses. */
    private val LISTS = setOf(Row.ALGORITHM, Row.RATIO, Row.KEY_SCALE, Row.SSG, Row.RAW_ALGORITHM, Row.FM3_MODE, Row.ENV_SHAPE, Row.SAMPLE)

    /** On/off controls: an Edit tap switches them. */
    private val TOGGLES = setOf(Row.OP_AM, Row.MODE, Row.ENV_AUTO, Row.LOOP_SAMPLE)

    /** Pan choices: both, left, right, off. */
    val PANS = listOf(true to true, true to false, false to true, false to false)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    /** PCM samples a trim step cuts: one 32-byte unit (Edit+←→), sixteen (Edit+↑↓). */
    const val TRIM_FINE = OpnEdits.UNIT_SAMPLES
    const val TRIM_COARSE = 16 * OpnEdits.UNIT_SAMPLES

    // ---- reading ------------------------------------------------------------------------------------------

    fun chip(song: Song): OpnChip = song.opn.chip

    fun sound(song: Song, state: ViewState): OpnInstrument? = song.opn.instrument(state.instrument)

    /** The part the slot's pages are: its sound's, or for an empty slot the Listen channel's. */
    fun part(song: Song, state: ViewState): OpnPart {
        val chip = chip(song)
        return sound(song, state)?.part ?: chip.part(state.listenChannel.coerceIn(0, chip.channelCount - 1))
    }

    fun pages(song: Song, state: ViewState): List<Page> = when (part(song, state)) {
        OpnPart.FM -> FM_PAGES
        OpnPart.SSG -> SSG_PAGES
        OpnPart.RHYTHM -> RHYTHM_PAGES
        OpnPart.ADPCM -> ADPCM_PAGES
    }

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> = (if (chip(song).opna) ROWS_OPNA else ROWS_OPN).getValue(page(song, state))

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The operator (0-based) the Expert or Ch3 grid's cursor is on. */
    fun operator(state: ViewState): Int = state.envStep.coerceIn(0, 3)

    /** The SSG envelope a page shows, or null. */
    fun envelopeKind(song: Song, state: ViewState): NesEnvelopeKind? = page(song, state).envelope

    /** The furthest step the cursor reaches on an envelope: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /** The envelope the page shows: the SSG sound's, or (empty slot) the one a new SSG sound starts with. */
    fun envelope(song: Song, state: ViewState): NesEnvelope? {
        val kind = envelopeKind(song, state) ?: return null
        val ssg = sound(song, state) as? OpnSsgSound ?: OpnEdits.defaultSsg(chip(song), state.instrument)
        return OpnEdits.envelope(ssg, kind)
    }

    /** The index of the song sample an ADPCM sound plays, or -1 when it plays none of the song's samples. */
    fun sampleIndex(song: Song, state: ViewState): Int =
        (sound(song, state) as? OpnAdpcmSound)?.sample?.takeIf { it in song.opn.samples.indices } ?: -1

    /**
     * The channel a held Listen plays [ins] on: one of its part's (the Listen channel when it is one, else the part's
     * first); FM voices on the Ch3 page on FM 3, where the operator offsets sound.
     */
    fun listenChannel(song: Song, state: ViewState, ins: OpnInstrument? = sound(song, state)): Int {
        val chip = chip(song)
        val ch = state.listenChannel.coerceIn(0, chip.channelCount - 1)
        val part = ins?.part ?: chip.part(ch)
        if (part == OpnPart.FM && page(song, state) == Page.CH3) return OpnPlayerImage.CH_FM3
        if (chip.part(ch) == part) return ch
        return OpnEdits.firstChannel(chip, part).takeIf { it >= 0 } ?: ch
    }

    /** The note Listen plays: the octave keys' C (ADPCM sounds: their root, moved by the octave keys), at least the channel's lowest. */
    fun listenNote(v: ViewController, ins: OpnInstrument?, channel: Int): Int {
        val chip = chip(v.session.song)
        val note = if (ins is OpnAdpcmSound) ins.root + v.defaultNote() - DEFAULT_OCTAVE_NOTE else v.defaultNote()
        return note.coerceIn(OpnRules.NOTES).coerceAtLeast(OpnRules.lowest(chip, channel))
    }

    /** The listening note of the default octave (4): an ADPCM sound plays at its root there. */
    private const val DEFAULT_OCTAVE_NOTE = 48

    /** "an FM voice", "an SSG sound", "a rhythm sound", "an ADPCM sound". */
    fun partText(part: OpnPart): String = when (part) {
        OpnPart.FM -> "an FM voice"
        OpnPart.SSG -> "an SSG sound"
        OpnPart.RHYTHM -> "a rhythm sound"
        OpnPart.ADPCM -> "an ADPCM sound"
    }

    /** "FM 1-6", "SSG 1-3", "Kick-Rim", "ADPCM": the channels a part plays on. */
    fun partChannels(chip: OpnChip, part: OpnPart): String = when (part) {
        OpnPart.FM -> "FM 1-${chip.fmChannels}"
        OpnPart.SSG -> "SSG 1-3"
        OpnPart.RHYTHM -> "the six rhythm channels"
        OpnPart.ADPCM -> "ADPCM"
    }

    // ---- view contract ------------------------------------------------------------------------------------

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, OpnSound.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(0, rows(song, st).size - 1)
        val page = page(song, st)
        when {
            page == Page.EXPERT || page == Page.CH3 -> st.envStep = st.envStep.coerceIn(0, 3)
            page.envelope != null -> st.envStep = st.envStep.coerceIn(0, envelope(song, st)?.let(::lastStep) ?: 0)
        }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, OpnSound.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry, in the bank of its part (the parts share the slot numbers). */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        val bank = part(session.song, v.state).ordinal
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

    /** After the slot's part may have changed: the page index stays within the part's pages, the cursor on its row. */
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
        val slot = (st.instrument + delta).coerceIn(1, OpnSound.SLOTS)
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
            row == Row.SAMPLE_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, OpnaSample.MAX_NAME - 1)
            row == Row.LISTEN -> { st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size); keepPage(v, row) }
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row in GRIDS -> st.envStep = (st.envStep + dx).coerceIn(0, 3)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val song = session.song
        val chip = chip(song)
        val slot = st.instrument
        val d = dx + dy
        val row = row(song, st)
        val changed = when (row) {
            Row.SLOT -> { moveSlot(v, dx - COARSE * dy); false }
            Row.NAME -> {
                val cur = sound(song, st) ?: OpnEdits.newSound(chip, part(song, st), slot)
                OpnEdits.set(session, slot, cur.named(NameEdit.edit(cur.name, st.textPos, dx + 26 * dy, NAME_MAX)), "Rename sound")
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(song.system.channels.size); keepPage(v, row); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.TYPE -> switchType(v, if (d < 0) -1 else 1)
            Row.PRESET -> applyPreset(v, d, drums = false)
            Row.DRUM_PRESET -> applyPreset(v, d, drums = true)
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.LENGTH, Row.LOOP, Row.ENV_RELEASE -> {
                val kind = envelopeKind(song, st) ?: return
                val name = kind.label.lowercase()
                OpnEdits.editEnvelope(session, slot, kind, "Set $name ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> NesEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy, OpnEdits.SSG_CHIP)
                        Row.LOOP -> NesEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy, OpnEdits.SSG_CHIP)
                        else -> NesEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
                    }
                }
            }
            Row.MODE -> d != 0 && OpnEdits.editSsg(session, slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.ENV_SHAPE -> OpnEdits.editSsg(session, slot, "Set SSG envelope shape") {
                val shapes = S5bShape.entries
                it.copy(envelope = it.envelope.copy(shape = shapes[(shapes.indexOf(it.envelope.shape) + d).coerceIn(0, shapes.size - 1)]))
            }
            Row.ENV_AUTO -> d != 0 && OpnEdits.editSsg(session, slot, "Set SSG envelope period mode") {
                it.copy(envelope = it.envelope.copy(autoPitch = !it.envelope.autoPitch))
            }
            Row.ENV_OCTAVE -> OpnEdits.editSsg(session, slot, "Set SSG envelope octave") {
                it.copy(envelope = it.envelope.copy(octave = (it.envelope.octave + dx + 4 * dy).coerceIn(S5bEnvelope.OCTAVES)))
            }
            Row.ENV_PERIOD -> OpnEdits.editSsg(session, slot, "Set SSG envelope period") {
                it.copy(envelope = it.envelope.copy(period = octaveOf(it.envelope.period + dx, dy).coerceIn(1, S5bEnvelope.PERIODS.last)))
            }
            Row.FM3_MODE -> d != 0 && session.apply("Set FM 3 mode") { s ->
                OpnEdits.withFm3Mode(s, OpnFm3Mode.entries[(s.opn.fm3Mode.ordinal + d).mod(OpnFm3Mode.entries.size)])
            }.also { if (it) fm3Note(v) }
            Row.CH3_SEMI, Row.CH3_FINE -> OpnEdits.editFm(session, slot, "Set FM 3 operator ${operator(st) + 1} offset") {
                stepCh3(it, operator(st), row, dx, dy)
            }.also { if (it && session.song.opn.fm3Mode == OpnFm3Mode.NORMAL) fm3Note(v) }
            Row.RHYTHM_LEVEL -> OpnEdits.editRhythm(session, slot, "Set rhythm level") { it.copy(level = (it.level + dx + 4 * dy).coerceIn(0, 31)) }
            Row.PAN -> stepPan(v, d)
            Row.RHYTHM_ROM, Row.SAMPLE_TOOLS, Row.SAMPLE_ADD -> false
            Row.SAMPLE -> stepSample(v, dx + 4 * dy)
            Row.ROOT -> OpnEdits.editAdpcm(session, slot, "Set root note") { it.copy(root = (it.root + dx + 12 * dy).coerceIn(OpnRules.NOTES)) }
            Row.ADPCM_LEVEL -> OpnEdits.editAdpcm(session, slot, "Set ADPCM level") { it.copy(level = (it.level + dx + 16 * dy).coerceIn(0, 255)) }
            Row.LOOP_SAMPLE -> d != 0 && OpnEdits.editAdpcm(session, slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.SAMPLE_NAME -> {
                val index = sampleIndex(song, st)
                if (index < 0) { noSample(v); false }
                else session.apply("Rename sample") { s ->
                    OpnEdits.renameSample(s, index, NameEdit.edit(s.opn.samples[index].name, st.textPos, dx + 26 * dy, OpnaSample.MAX_NAME))
                }
            }
            Row.TRIM_START, Row.TRIM_END -> trim(v, row == Row.TRIM_START, dx, dy)
            in SWEEP -> editSweep(v, "Set ${row.label.lowercase()}") { stepSweep(it, row, dx, dy) }
            else -> {
                val ym = YM[row] ?: return
                OpnEdits.editFm(session, slot, "Set ${row.label.lowercase()}") { it.withYm(YmInstrumentView.step(it.asYm(), ym, operator(st), dx, dy)) }
                    .also { if (it) fmNote(v, row) }
            }
        }
        if (changed) heard(v)
    }

    /** [s] with [row]'s value stepped: start/end by semitones (↑↓ octaves), speed by 1 (↑↓ 16). */
    fun stepSweep(s: YmSweep, row: Row, dx: Int, dy: Int): YmSweep = when (row) {
        Row.SWEEP_START -> (s.start + dx + 12 * dy).coerceIn(-OpnPlayerImage.SWEEP_LIMIT, OpnPlayerImage.SWEEP_LIMIT)
            .let { s.copy(start = it, speed = FmControls.sweepSpeed(s.speed, it, s.end)) }
        Row.SWEEP_END -> (s.end + dx + 12 * dy).coerceIn(-OpnPlayerImage.SWEEP_LIMIT, OpnPlayerImage.SWEEP_LIMIT)
            .let { s.copy(end = it, speed = FmControls.sweepSpeed(s.speed, s.start, it)) }
        else -> s.copy(speed = (s.speed + dx + 16 * dy).coerceIn(0, 255))
    }

    /** Edits the sweep of [v]'s sound, whichever part has one (FM, SSG, ADPCM). */
    private fun editSweep(v: ViewController, label: String, f: (YmSweep) -> YmSweep): Boolean {
        val session = v.session
        val slot = v.state.instrument
        return when (part(session.song, v.state)) {
            OpnPart.FM -> OpnEdits.editFm(session, slot, label) { it.copy(sweep = f(it.sweep)) }
            OpnPart.SSG -> OpnEdits.editSsg(session, slot, label) { it.copy(sweep = f(it.sweep)) }
            OpnPart.ADPCM -> OpnEdits.editAdpcm(session, slot, label) { it.copy(sweep = f(it.sweep)) }
            OpnPart.RHYTHM -> false
        }
    }

    /** FM 3 operator [op]'s pitch offset stepped: semitones (↑↓ octaves) or 1/64 semitones (↑↓ eighths), within ±4 octaves. */
    fun stepCh3(ins: OpnFmSound, op: Int, row: Row, dx: Int, dy: Int): OpnFmSound {
        val offsets = List(4) { ins.ch3.getOrElse(it) { 0 } }
        val step = if (row == Row.CH3_SEMI) 64 * (dx + 12 * dy) else dx + 8 * dy
        val next = (offsets[op] + step).coerceIn(-OpnPlayerImage.CH3_LIMIT, OpnPlayerImage.CH3_LIMIT)
        return ins.copy(ch3 = offsets.toMutableList().also { it[op] = next })
    }

    /** Halves or doubles a manual period (an octave of envelope rate per step), at least 1. */
    private fun octaveOf(period: Int, up: Int): Int = when {
        up > 0 -> maxOf(1, period) * 2
        up < 0 -> maxOf(1, period / 2)
        else -> period
    }

    /** Steps the pan of a rhythm or ADPCM sound through both, left, right and off. */
    private fun stepPan(v: ViewController, delta: Int): Boolean {
        if (delta == 0) return false
        fun next(l: Boolean, r: Boolean) = PANS[(PANS.indexOf(l to r).coerceAtLeast(0) + delta).mod(PANS.size)]
        val session = v.session
        val slot = v.state.instrument
        return when (part(session.song, v.state)) {
            OpnPart.RHYTHM -> OpnEdits.editRhythm(session, slot, "Set pan") { next(it.left, it.right).let { (l, r) -> it.copy(left = l, right = r) } }
            OpnPart.ADPCM -> OpnEdits.editAdpcm(session, slot, "Set pan") { next(it.left, it.right).let { (l, r) -> it.copy(left = l, right = r) } }
            else -> false
        }
    }

    /** The ADPCM sound plays the sample [delta] steps on in the song's list. */
    private fun stepSample(v: ViewController, delta: Int): Boolean {
        val session = v.session
        val st = v.state
        val samples = session.song.opn.samples
        if (samples.isEmpty()) { noSample(v); return false }
        val changed = OpnEdits.editAdpcm(session, st.instrument, "Choose sample") {
            it.copy(sample = ((if (it.sample in samples.indices) it.sample else -1) + delta).coerceIn(0, samples.size - 1))
        }
        val index = sampleIndex(session.song, st)
        if (index >= 0) samples[index].let { session.note("Sample ${index + 1} of ${samples.size}: ${it.name} · ${it.rate} Hz · ${OpnSampleMenu.kib(it.stored)}") }
        return changed
    }

    private fun noSample(v: ViewController) {
        val session = v.session
        if (session.song.opn.samples.isEmpty()) session.note("The song has no ADPCM samples yet: ${v.k(Action.EDIT)} on Add sample imports a .wav or generates one")
        else session.note("This sound plays none of the song's samples: choose one on the Sample row")
    }

    /** Edit+→ (↑: sixteen times as much) cuts one 32-byte unit of the sound's sample from its start or end. */
    private fun trim(v: ViewController, start: Boolean, dx: Int, dy: Int): Boolean {
        val session = v.session
        val index = sampleIndex(session.song, v.state)
        if (index < 0) { noSample(v); return false }
        val count = when {
            dy > 0 -> TRIM_COARSE
            dx > 0 -> TRIM_FINE
            else -> { session.note("Trimming only cuts (${v.k(Action.EDIT)}+→, ↑ for more); undo brings audio back"); return false }
        }
        val changed = session.apply(if (start) "Trim sample start" else "Trim sample end") { s ->
            if (start) OpnEdits.trimStart(s, index, count) else OpnEdits.trimEnd(s, index, count)
        }
        val s = session.song.opn.samples[index]
        if (changed) session.note("${s.name}: ${OpnSampleMenu.seconds(s.seconds)} · ${OpnSampleMenu.kib(s.stored)}")
        else session.note("${s.name} is as short as it gets: one 32-byte unit")
        return changed
    }

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
            changed = OpnEdits.editEnvelope(session, slot, kind, "Set $name") {
                NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep) + dy, OpnEdits.SSG_CHIP)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = NesEnvelopes.valueAt(kind, e, st.envStep)
            val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = OpnEdits.editEnvelope(session, slot, kind, "Draw $name") { NesEnvelopes.withValue(kind, it, next, value, OpnEdits.SSG_CHIP) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
        }
        return changed
    }

    /** The slot becomes the next ([delta] 1) or previous part's starting sound (keeping its name); undo brings the old one back. */
    private fun switchType(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val session = v.session
        val song = session.song
        val chip = chip(song)
        val slot = st.instrument
        val parts = chip.parts
        val cur = sound(song, st)
        val next = parts[(parts.indexOf(part(song, st)).coerceAtLeast(0) + delta).mod(parts.size)]
        val ok = session.apply("Make ${partText(next).substringAfter(' ')}") { OpnEdits.withPart(it, slot, next) }
        if (chip.part(st.listenChannel.coerceIn(0, chip.channelCount - 1)) != next) st.listenChannel = OpnEdits.firstChannel(chip, next)
        keepPage(v, Row.TYPE)
        session.note("Sound ${EditorSession.hex2(slot)} is now ${partText(next)} (${partChannels(chip, next)})" +
            if (cur != null) "; undo brings the old sound back" else "")
        return ok
    }

    /** The FM presets the slot offers: voices and drums, or [drums] only; on the YM2203 without LFO depths. */
    fun fmPresets(drums: Boolean): List<YmPresets.Preset> = OpnPresets.FM.filter {
        it.instrument is YmFmInstrument && (if (drums) it.category == YmPresets.Category.DRUM else it.category == YmPresets.Category.FM || it.category == YmPresets.Category.DRUM)
    }

    /** The FM preset [ins] is (its operator pitch offsets aside), or null. */
    fun fmPreset(chip: OpnChip, ins: OpnFmSound, drums: Boolean = false): YmPresets.Preset? =
        fmPresets(drums).firstOrNull { OpnPresets.fmFor(chip, it.instrument as YmFmInstrument).copy(ch3 = ins.ch3) == ins }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound; Listen follows the preset's part. */
    private fun applyPreset(v: ViewController, delta: Int, drums: Boolean): Boolean {
        val st = v.state
        val session = v.session
        val song = session.song
        val chip = chip(song)
        val slot = st.instrument
        val cur = sound(song, st)
        val ok: Boolean
        when (part(song, st)) {
            OpnPart.FM -> {
                val presets = fmPresets(drums)
                if (presets.isEmpty()) return false
                val fm = cur as? OpnFmSound
                val current = fm?.let { f -> presets.indexOfFirst { OpnPresets.fmFor(chip, it.instrument as YmFmInstrument).copy(ch3 = f.ch3) == f } } ?: -1
                val p = presets[FmControls.presetStep(current, delta, presets.size)]
                val ym = p.instrument as YmFmInstrument
                val next = OpnPresets.fmFor(chip, ym).let { if (fm != null) it.copy(ch3 = fm.ch3) else it }
                ok = OpnEdits.set(session, slot, next, "Preset ${p.name}")
                if (chip.part(st.listenChannel.coerceIn(0, chip.channelCount - 1)) != OpnPart.FM) st.listenChannel = 0
                session.note("${p.name}: ${YmCurated.summary(ym)}" + (p.note?.let { " · voiced at ${song.system.noteName(it)}" } ?: ""))
            }
            OpnPart.SSG -> {
                val presets = OpnPresets.SSG
                if (presets.isEmpty()) return false
                val p = presets[FmControls.presetStep(presets.indexOfFirst { it == cur }, delta, presets.size)]
                ok = OpnEdits.set(session, slot, p, "Preset ${p.name}")
                if (chip.part(st.listenChannel.coerceIn(0, chip.channelCount - 1)) != OpnPart.SSG) st.listenChannel = chip.ssgFirst
                session.note("${p.name}: SSG sound")
            }
            OpnPart.RHYTHM -> {
                val presets = OpnPresets.RHYTHM
                if (presets.isEmpty()) return false
                val p = presets[FmControls.presetStep(presets.indexOfFirst { it == cur }, delta, presets.size)]
                ok = OpnEdits.set(session, slot, p, "Preset ${p.name}")
                val drum = OpnChip.RHYTHM_NAMES.indexOf(p.name)
                if (drum >= 0 && chip.rhythmFirst >= 0) st.listenChannel = chip.rhythmFirst + drum
                else if (chip.part(st.listenChannel.coerceIn(0, chip.channelCount - 1)) != OpnPart.RHYTHM) st.listenChannel = chip.rhythmFirst.coerceAtLeast(0)
                session.note("${p.name}: rhythm level ${p.level} · ${panText(p.left, p.right)}")
            }
            OpnPart.ADPCM -> return false
        }
        return ok
    }

    /** "both", "left", "right", "off". */
    fun panText(left: Boolean, right: Boolean): String = when {
        left && right -> "both"
        left -> "left"
        right -> "right"
        else -> "off"
    }

    /** A status line where a list's choice says more than its row: algorithm routing, an LFO depth with the LFO off. */
    private fun fmNote(v: ViewController, row: Row) {
        val ins = sound(v.session.song, v.state) as? OpnFmSound ?: return
        when (row) {
            Row.ALGORITHM, Row.RAW_ALGORITHM -> YmCurated.ALGORITHMS[ins.algorithm.coerceIn(0, 7)].let { a ->
                v.session.note("Algorithm ${a.number}: ${a.name} ${a.diagram} · carriers ${a.carriers.joinToString("+")}")
            }
            Row.VIBRATO, Row.TREMOLO, Row.AMS, Row.FMS, Row.OP_AM -> if (v.session.song.opn.lfo and 8 == 0) {
                v.session.note("The song's LFO is off (Song view, or Lxx): vibrato and tremolo are silent until it is on")
            }
            else -> Unit
        }
    }

    private fun fm3Note(v: ViewController) {
        v.session.note(when (v.session.song.opn.fm3Mode) {
            OpnFm3Mode.NORMAL -> "FM 3 is in normal mode: the operator offsets sound only in special or CSM mode (this row, or Song view)"
            OpnFm3Mode.SPECIAL -> "FM 3 special mode: each operator plays the note plus its offset"
            OpnFm3Mode.CSM -> "FM 3 CSM mode: timer A (Song view, CSM rate, or Txx) keys FM 3's operators at their pitches"
        })
    }

    /** After an edit: a held Listen or Edit sounds the changed sound again. */
    private fun heard(v: ViewController) {
        if (v.auditionHeld || (v.editHeld && row(v.session.song, v.state) in SOUND_ROWS)) audition(v)
    }

    override fun editPress(v: ViewController) {
        if (row(v.session.song, v.state) in SOUND_ROWS && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val st = v.state
        val session = v.session
        val song = session.song
        val row = row(song, st)
        if (row in SOUND_ROWS && !v.auditionHeld) session.releaseAudition()
        if (!tap) return
        val slot = st.instrument
        val empty = sound(song, st) == null
        val changed = when {
            row == Row.SLOT -> {
                if (empty) {
                    val p = part(song, st)
                    OpnEdits.set(session, slot, OpnEdits.newSound(chip(song), p, slot), "New sound").also {
                        session.note("Sound ${EditorSession.hex2(slot)}: ${partText(p)} to start from (${partChannels(chip(song), p)})")
                    }
                } else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            row == Row.TYPE -> switchType(v, 1)
            row == Row.PRESET -> applyPreset(v, 1, drums = false)
            row == Row.DRUM_PRESET -> applyPreset(v, 1, drums = true)
            row in TOGGLES || row == Row.FM3_MODE || row == Row.PAN || row == Row.ENV_SHAPE -> { adjust(v, 1, 0); false }
            row == Row.RHYTHM_ROM -> { session.requestMenu { RhythmRomMenu.open(it) }; false }
            row == Row.SAMPLE_ADD -> { OpnSampleMenu.add(session, slot); false }
            row == Row.SAMPLE_TOOLS -> {
                val index = sampleIndex(song, st)
                if (index < 0) noSample(v) else OpnSampleMenu.tools(session, index)
                false
            }
            row == Row.STEPS -> {
                val kind = envelopeKind(song, st) ?: return
                val e = envelope(song, st) ?: return
                if (st.envStep == e.values.size) {
                    OpnEdits.editEnvelope(session, slot, kind, "Add ${kind.label.lowercase()} step") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep), OpnEdits.SSG_CHIP)
                    }.also { envelope(session.song, st)?.let { st.envStep = lastStep(it) } }
                } else false
            }
            empty && row in SOUND_ROWS -> OpnEdits.set(session, slot, OpnEdits.newSound(chip(song), part(song, st), slot), "New sound")
            else -> false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val song = session.song
        val chip = chip(song)
        val slot = st.instrument
        val row = row(song, st)
        val op = operator(st)
        val blank = OpnEdits.defaultFm(chip, slot).asYm()
        val reset = YmCurated.controls(blank)
        fun fm(label: String, f: (YmFmInstrument) -> YmFmInstrument) { OpnEdits.editFm(session, slot, label) { it.withYm(f(it.asYm())) } }
        when (row) {
            Row.SLOT -> OpnEdits.set(session, slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.PRESET, Row.DRUM_PRESET -> OpnEdits.set(session, slot, OpnEdits.newSound(chip, part(song, st), slot), "Reset sound")
            Row.NAME -> OpnEdits.rename(session, slot) { ViewController.deleteChar(it, st.textPos) }
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
            in GRID_OPNA -> fm("Reset ${row.label}") { it.withOperator(op, resetField(it.operators[op], row)) }
            Row.FM3_MODE -> session.apply("Set FM 3 mode") { OpnEdits.withFm3Mode(it, OpnFm3Mode.NORMAL) }
            Row.CH3_SEMI -> OpnEdits.editFm(session, slot, "Reset FM 3 operator ${op + 1} offset") {
                it.copy(ch3 = List(4) { i -> if (i == op) 0 else it.ch3.getOrElse(i) { 0 } })
            }
            Row.CH3_FINE -> OpnEdits.editFm(session, slot, "Reset FM 3 operator ${op + 1} fine offset") {
                it.copy(ch3 = List(4) { i -> it.ch3.getOrElse(i) { 0 }.let { o -> if (i == op) o / 64 * 64 else o } })
            }
            in SWEEP -> editSweep(v, "Sweep off") { YmSweep.NONE }
            Row.LENGTH -> envelopeKind(song, st)?.let { k ->
                OpnEdits.editEnvelope(session, slot, k, "Reset ${k.label.lowercase()} envelope") { OpnEdits.envelope(OpnEdits.defaultSsg(chip, slot), k) }
            }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> OpnEdits.editEnvelope(session, slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.ENV_RELEASE -> envelopeKind(song, st)?.let { k -> OpnEdits.editEnvelope(session, slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.MODE -> OpnEdits.editSsg(session, slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                OpnEdits.editEnvelope(session, slot, k, "Delete ${k.label.lowercase()} step") { NesEnvelopes.removed(k, it, st.envStep, OpnEdits.SSG_CHIP) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
            Row.ENV_SHAPE -> OpnEdits.editSsg(session, slot, "Set SSG envelope shape") { it.copy(envelope = it.envelope.copy(shape = S5bShape.OFF)) }
            Row.ENV_AUTO -> OpnEdits.editSsg(session, slot, "Set SSG envelope period mode") { it.copy(envelope = it.envelope.copy(autoPitch = HW_DEFAULT.autoPitch)) }
            Row.ENV_OCTAVE -> OpnEdits.editSsg(session, slot, "Set SSG envelope octave") { it.copy(envelope = it.envelope.copy(octave = HW_DEFAULT.octave)) }
            Row.ENV_PERIOD -> OpnEdits.editSsg(session, slot, "Set SSG envelope period") { it.copy(envelope = it.envelope.copy(period = HW_DEFAULT.period)) }
            Row.RHYTHM_LEVEL -> OpnEdits.editRhythm(session, slot, "Reset rhythm level") { it.copy(level = OpnRhythmSound().level) }
            Row.PAN -> when (part(song, st)) {
                OpnPart.RHYTHM -> OpnEdits.editRhythm(session, slot, "Set pan") { it.copy(left = true, right = true) }
                OpnPart.ADPCM -> OpnEdits.editAdpcm(session, slot, "Set pan") { it.copy(left = true, right = true) }
                else -> false
            }
            Row.SAMPLE -> sampleIndex(song, st).let { if (it >= 0) OpnSampleMenu.delete(session, it) else noSample(v) }
            Row.ROOT -> OpnEdits.editAdpcm(session, slot, "Reset root note") { it.copy(root = OpnAdpcmSound().root) }
            Row.ADPCM_LEVEL -> OpnEdits.editAdpcm(session, slot, "Reset ADPCM level") { it.copy(level = OpnAdpcmSound().level) }
            Row.LOOP_SAMPLE -> OpnEdits.editAdpcm(session, slot, "Set sample loop") { it.copy(loop = false) }
            Row.SAMPLE_NAME -> sampleIndex(song, st).let { index ->
                if (index >= 0) session.apply("Rename sample") { s -> OpnEdits.renameSample(s, index, ViewController.deleteChar(s.opn.samples[index].name, st.textPos)) }
            }
            else -> Unit // Listen, Type, Page, the ROM, trims, tools, add
        }
    }

    /** The SSG hardware envelope a new sound has (off, auto at octave 3, manual period 4000). */
    val HW_DEFAULT: S5bEnvelope = OpnSsgSound().envelope

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
        if (ins.part !in chip(song).parts) { v.session.note("The ${chip(song).label} has no ${ins.part.name.lowercase()} channel: change the sound's type"); return }
        val ch = listenChannel(song, st, ins)
        if (ch != st.listenChannel && page(song, st) != Page.CH3) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} is ${partText(ins.part)}: listening on ${song.system.channels[ch].name}")
        }
        v.startAudition(ch, listenNote(v, ins, ch), st.instrument)
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
                if (row in GRIDS) st.envStep = (st.envStep + d).coerceIn(0, 3) else showPage(v, st.envelope + d)
            }
            Action.COPY -> if (onEnvelope) OpnEdits.copyEnvelope(session, slot, kind!!) else OpnEdits.copySound(session, slot)
            Action.PASTE -> { OpnEdits.paste(session, slot, kind); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                OpnEdits.copyEnvelope(session, slot, kind!!)
                OpnEdits.editEnvelope(session, slot, kind, "Cut ${kind.label.lowercase()} envelope") { OpnEdits.envelope(OpnEdits.defaultSsg(chip(song), slot), kind) }
            } else {
                OpnEdits.copySound(session, slot)
                OpnEdits.set(session, slot, null, "Cut sound")
                keepPage(v, row)
            }
            Action.DUPLICATE -> {
                if (slot >= OpnSound.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                OpnEdits.set(session, slot + 1, src.named((src.name + "+").take(NAME_MAX)), "Duplicate sound")
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
        val clear = v.k(Action.CLEAR)
        val edit = v.k(Action.EDIT)
        when {
            row == Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(edit, if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(clear, "Empty the slot")
            }
            row == Row.NAME || row == Row.SAMPLE_NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(clear, "Delete letter") }
            row == Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            row == Row.PAGE -> h += Hint(v.lr(), "Page")
            row == Row.TYPE -> { h += Hint(edit, "Next type"); h += Hint(v.editLr(), "FM / SSG" + if (chip(song).opna) " / rhythm / ADPCM" else "") }
            row == Row.PRESET || row == Row.DRUM_PRESET -> {
                h += Hint(v.editLr(), if (row == Row.PRESET) "Start from a preset" else "Drum presets"); h += Hint(clear, "Default sound")
            }
            row in GRID_OPNA -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                if (row == Row.OP_AM) h += Hint(edit, "Switch AM") else h += Hint(v.editLr(), "${row.label} ±1 (${v.ud()} coarse)")
                h += Hint(clear, "Default")
            }
            row in CH3_GRID -> {
                h += Hint(v.lr(), "Operator"); h += Hint(v.channels(), "Operator")
                h += Hint(v.editLr(), if (row == Row.CH3_SEMI) "Semitone (${v.ud()} octave)" else "1/64 semitone (${v.ud()} 8/64)")
                h += Hint(clear, if (row == Row.CH3_SEMI) "No offset" else "No fine offset")
            }
            row == Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(clear, "Delete step")
            }
            row == Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(clear, "Empty envelope") }
            row == Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(clear, "No loop") }
            row == Row.ENV_RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(clear, "No release") }
            row == Row.MODE -> h += Hint(edit, "Relative / absolute")
            row == Row.ENV_AUTO -> { h += Hint(edit, "Auto / manual period"); h += Hint(clear, "Auto") }
            row == Row.ENV_OCTAVE -> { h += Hint(v.editLr(), "Octave ±1 (${v.ud()} ±4)"); h += Hint(clear, "${HW_DEFAULT.octave}") }
            row == Row.ENV_PERIOD -> { h += Hint(v.editLr(), "Period ±1 (${v.ud()} ×2 / ÷2)"); h += Hint(clear, "${HW_DEFAULT.period}") }
            row == Row.RHYTHM_LEVEL -> { h += Hint(v.editLr(), "Level ±1 (${v.ud()} ±4)"); h += Hint(clear, "31") }
            row == Row.PAN -> { h += Hint(v.editLr(), "Both / left / right / off"); h += Hint(clear, "Both") }
            row == Row.RHYTHM_ROM -> h += Hint(edit, if (Ym2608RhythmRom.installed) "Replace the rhythm ROM…" else "Import the rhythm ROM…")
            row == Row.SAMPLE -> { h += Hint(v.editLr(), "Song sample (${v.ud()} ±4)"); h += Hint(clear, "Delete sample…") }
            row == Row.ROOT -> { h += Hint(v.editLr(), "Semitone (${v.ud()} octave)"); h += Hint(clear, "C-4") }
            row == Row.ADPCM_LEVEL -> { h += Hint(v.editLr(), "Level ±1 (${v.ud()} ±16)"); h += Hint(clear, "255") }
            row == Row.LOOP_SAMPLE -> { h += Hint(edit, "Loop on / off"); h += Hint(clear, "Once") }
            row == Row.TRIM_START || row == Row.TRIM_END -> h += Hint(v.editLr(), "→ cut $TRIM_FINE samples (${v.ud()} ↑ $TRIM_COARSE)")
            row == Row.SAMPLE_TOOLS -> h += Hint(edit, "Normalize, gain, reverse, fade, resample, replace, delete…")
            row == Row.SAMPLE_ADD -> h += Hint(edit, "Import .wav or generate…")
            row == Row.FM3_MODE -> { h += Hint(v.editLr(), "Normal / special / CSM (the song's)"); h += Hint(clear, "Normal") }
            row in LISTS -> { h += Hint(v.editLr(), "Choose"); h += Hint(clear, "Default") }
            else -> { h += Hint(v.editLr(), "±1 (${v.ud()} coarse)"); h += Hint(clear, "Default") }
        }
        if (row !in HEAD && row !in GRIDS && row != Row.STEPS && row != Row.SAMPLE_NAME) h += Hint(v.lr(), "Sound slot")
        if (row !in GRIDS) h += Hint(v.channels(), "Page")
        val ch = listenChannel(song, st, ins)
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(ch)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(listenNote(v, ins, ch))}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in HEAD) "Copy/paste envelope" else "Copy/paste sound")
    }

    // ---- help ---------------------------------------------------------------------------------------------

    /** Help rows for this view on [chip] songs (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap, chip: OpnChip): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val octaves = keys.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP)
        val editLr = keys.held(Action.EDIT, lr)
        val editUd = keys.held(Action.EDIT, ud)
        val opna = chip.opna
        val parts = if (opna) "FM voices (FM 1-6), SSG sounds (SSG 1-3), rhythm sounds (the six drum channels) and ADPCM sounds (ADPCM)"
            else "FM voices (FM 1-3) and SSG sounds (SSG 1-3)"
        val sections = ArrayList<Pair<String, List<Pair<String, String>>>>()
        sections += "Instruments" to listOf(
            ud to "Sound slot, name, Listen channel, type, page, then the page's settings",
            lr to "On settings: the sound slot; on the Expert and Ch3 grids: the operator; on envelope steps: the step",
            channels to "Pages. FM voices: Sound, Expert, Ch3, Drum. SSG sounds: Volume, Arpeggio, Pitch, Mixer, Noise, Hw env, Drum" +
                if (opna) ". Rhythm sounds: Rhythm. ADPCM sounds: Sample, Drum" else "",
            "Sounds" to "One list of ${OpnSound.SLOTS} slots holds $parts; a note's sound must be of its channel's kind",
            "Listen on" to "The channel $listen plays the sound on: one of its kind (the Ch3 page always FM 3). Holding $edit on a setting " +
                "sounds the sound and every change restarts it. $octaves moves the listening note (ADPCM sounds: from their root)",
            "Type" to "$edit steps the slot through the kinds ($editLr either way), giving it that kind's starting sound; undo brings the old one back",
            editLr to "Change the value ($editUd in bigger steps); lists step through their choices",
            "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
            "$edit on Slot" to "An empty slot gets a new sound of the Listen channel's kind",
        )
        sections += "FM voices" to listOfNotNull(
            "Sound" to "The Mega Drive editor's curated controls over the same OPN operators: preset (the Mega Drive's voices and FM drums), " +
                "algorithm, ratios, brightness, level, feedback, attack, decay, sustain, sustain rate, release, tone decay, key scaling" +
                (if (opna) ", vibrato, tremolo" else "") + ", SSG-EG. A value marked ≈ was hand-edited on the Expert page",
            "Expert" to "Algorithm and feedback" + (if (opna) ", AMS and FMS" else "") + " as stored, then per operator DT, MUL, TL (level, 0 loudest), " +
                "KS, AR, " + (if (opna) "AM ($edit switches), " else "") + "D1R, D2R, SL, RR and SSG-EG; $lr or $channels picks the operator. Carriers are marked ♪car",
            if (opna) "LFO" to "Vibrato (FMS), tremolo (AMS) and AM need the song's LFO (Song view, LFO, or Lxx): the YM2608 has one LFO for all six FM channels" else null,
            if (!opna) "YM2203" to "The OPN has no LFO and no stereo: voices have no vibrato, tremolo or AM, and presets drop them" else null,
            "Ch3" to "FM 3's four operators can each play their own pitch: the offsets here (semitones and 1/64 semitones, ±4 octaves) are " +
                "added to the note on FM 3 when the song's FM 3 mode is special (each operator its own frequency) or CSM (timer A keys FM 3, " +
                "at the CSM rate). The FM 3 mode row sets the song's mode (also in the Song view); on other channels and in normal mode the offsets do nothing",
            "Drum" to "Pitch sweep: each note starts at the start offset (semitones from the note) and moves to the end at speed/16 semitones per tick. " +
                "Drum presets are FM drums (kick, snare, toms, hats, crash); the note picks the drum's pitch",
        )
        sections += "SSG sounds" to listOf(
            "Volume, Arpeggio, Pitch" to "Envelopes, one value per tick, edited as on the NES: $editUd sets a step, $editLr draws along, loop and " +
                "release points; volume 0-15, arpeggio in semitones, pitch in sixteenths of a semitone (relative: added up each tick)",
            "Mixer" to "Per tick: tone+noise, noise, tone or off (the channel holds its level). Empty: tone",
            "Noise" to "The noise period per tick, 0-31 (higher is lower). SSG 1-3 share one noise generator: the highest-numbered channel sounding " +
                "a noise envelope sets it each tick; Nxx sets it from the pattern",
            "Hw env" to "The SSG's one envelope generator: a shape (saw, triangle, or a one-shot attack/decay) replaces the volume envelope's level " +
                "(which then only gates it); each note restarts it. Auto: its period follows the note, shifted by the octave (4 = a saw in unison, " +
                "5 = a triangle in unison). Manual: a fixed period (one ramp = 128 µs × period); Hxx/Jxx override shape and period for a row's note",
            "Shared" to "The highest-numbered SSG channel sounding a hardware-envelope note sets the shape and period",
            "Drum" to "The pitch sweep, as on FM voices",
        )
        if (opna) {
            sections += "Rhythm and ADPCM" to listOf(
                "Rhythm" to "Kick, Snare, Cymbal, Hi-hat, Tom and Rim are six channels, each playing one fixed drum from the YM2608's rhythm ROM: a note " +
                    "triggers it. A rhythm sound sets its level (0-31, scaled by Cxx and the song's rhythm level) and pan (8xy overrides it)",
                "Rhythm ROM" to "The drums need the YM2608 rhythm ROM (${Ym2608RhythmRom.FILE_NAME}, 8192 bytes), which Thor Tracker does not include: import " +
                    "your own copy with \"${RhythmRomMenu.LABEL}\" (main menu, or $edit on the ROM row). It is checked, kept on this device and never " +
                    "written into projects or exports. FM, SSG and ADPCM play without it",
                "Sample" to "An ADPCM sound plays one of the song's samples ($editLr on Sample) at its recorded rate on the root note, transposed by the " +
                    "note's distance from it; level 0-255 (Cxx scales it), pan (8xy overrides it), loop (repeat while held). $clear on Sample deletes the sample (asked first)",
                "Editing a sample" to "Sample name; Trim start/end ($editLr → cuts 64 samples, $editUd ↑ 1024; undo restores); Tools ($edit): normalize, gain, " +
                    "reverse, fade in/out, resample to another rate, replace with a .wav, delete. Every sound playing the sample hears the change",
                "Add sample" to "$edit: import a .wav (converted on the device at the rate you choose; each rate shows its size against the free memory) " +
                    "or generate a starter sample. Samples are song data; together they share the chip's 256 KiB of ADPCM-B memory",
            )
        }
        return sections
    }

    override fun painter(pane: PaneView): Painter = OpnInstrumentPainter(pane)
}
