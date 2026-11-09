package org.thortracker.system.cbm

import org.thortracker.backend.CbmImage
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
 * The VIC-20 / C16-Plus/4 instrument editor, controller-only: the one bank of [CbmSystem.SLOTS] sounds. Head rows:
 * slot, name, the lane Listen plays on, on the TED the kind (tone or sample) and, for tones, the wave (square or Voice
 * 2's noise), a preset, then the page. Pages: tone sounds have the master volume (the chip's one volume: a sound with
 * this envelope shapes every voice while it sounds), arpeggio, pitch and wave envelopes; TED sample sounds the *Sample*
 * page (the sample, root note, loop, the song's samples with Add/rename/delete, [CbmSampleMenu]), arpeggio and pitch.
 * Envelopes are edited step by step as on the NES.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep], [ViewState.listenChannel], [ViewState.dpcmSample] (the sample list cursor),
 * [ViewState.textPos] (names). Holding Edit on a sound row sounds the instrument and every change restarts it.
 */
object CbmInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: CbmEnvelopeKind?) {
        SAMPLE("Sample", null),
        VOLUME("Master vol", CbmEnvelopeKind.VOLUME),
        ARPEGGIO("Arpeggio", CbmEnvelopeKind.ARPEGGIO),
        PITCH("Pitch", CbmEnvelopeKind.PITCH),
        WAVE("Wave", CbmEnvelopeKind.WAVE),
    }

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), KIND("Kind"), WAVE("Wave"), PRESET("Preset"), PAGE("Page"),
        LENGTH("Length"), LOOP("Loop"), RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
        SAMPLE("Sample"), ROOT("Root note"), SAMPLE_LOOP("Loop"), LOOP_START("Loop start"),
        SAMPLES("Samples"), SAMPLE_NAME("Sample name"), ADD("Add"),
    }

    private val TONE_PAGES = listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.WAVE)
    private val SAMPLE_PAGES = listOf(Page.SAMPLE, Page.ARPEGGIO, Page.PITCH)

    /** Rows before the page: the VIC has one kind and one wave, the TED tone/sample and square/noise. */
    private val VIC_HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PRESET)
    private val TED_HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.KIND, Row.PRESET)

    /** The Sample page: the sound's sample settings, then the song's samples (drawn in a box beside them). */
    val SAMPLE_ROWS = listOf(Row.SAMPLE, Row.ROOT, Row.SAMPLE_LOOP, Row.LOOP_START, Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = setOf(Row.KIND, Row.WAVE, Row.PRESET, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE,
        Row.SAMPLE, Row.ROOT, Row.SAMPLE_LOOP, Row.LOOP_START)

    /** Rows where ←→ moves within the row rather than to another slot. */
    private val LR_ROWS = setOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE, Row.STEPS, Row.SAMPLES, Row.SAMPLE_NAME)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun chip(song: Song): CbmChip = song.cbm.chip

    fun sound(song: Song, state: ViewState): CbmInstrument? = song.cbmInstrument(state.instrument)

    /** The shown sound's kind; an empty slot shows a tone sound's pages (Edit makes one). */
    fun kind(song: Song, state: ViewState): CbmKind = sound(song, state)?.kind ?: CbmKind.TONE

    fun pages(song: Song, state: ViewState): List<Page> = if (kind(song, state) == CbmKind.SAMPLE) SAMPLE_PAGES else TONE_PAGES

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun head(song: Song, state: ViewState): List<Row> = when {
        chip(song) == CbmChip.VIC -> VIC_HEAD
        kind(song, state) == CbmKind.TONE -> TED_HEAD + Row.WAVE
        else -> TED_HEAD
    }

    fun rows(song: Song, state: ViewState): List<Row> {
        val head = head(song, state) + Row.PAGE
        val kind = page(song, state).envelope ?: return head + SAMPLE_ROWS
        return head + if (kind == CbmEnvelopeKind.PITCH) listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
            else listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS)
    }

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The envelope the shown page edits, or null (the Sample page). */
    fun envelopeKind(song: Song, state: ViewState): CbmEnvelopeKind? = page(song, state).envelope

    /** The shown envelope (an empty slot's: the new sound's). */
    fun envelope(song: Song, state: ViewState): CbmEnvelope? =
        envelopeKind(song, state)?.of(sound(song, state) ?: defaultCbmInstrument(state.instrument))

    /** The value scale of envelope [kind] in [song]. */
    fun range(song: Song, kind: CbmEnvelopeKind): IntRange = kind.range(chip(song))

    /**
     * The lane a held Listen plays [ins] on: on the TED a sample sound on the DAC lane, a sound that can play noise on
     * Voice 2, a square sound on Listen's voice (Voice 1 when Listen is the DAC lane); on the VIC Listen's lane.
     */
    fun listenChannel(song: Song, state: ViewState, ins: CbmInstrument?): Int {
        if (chip(song) == CbmChip.VIC || ins == null) return state.listenChannel
        return when {
            ins.kind == CbmKind.SAMPLE -> CbmImage.TED_DAC_CHANNEL
            ins.playsNoise -> CbmImage.TED_NOISE_CHANNEL
            state.listenChannel == CbmImage.TED_DAC_CHANNEL -> 0
            else -> state.listenChannel
        }
    }

    /** A step value as the editor writes it: offsets signed, waves by name. */
    fun valueText(kind: CbmEnvelopeKind, value: Int): String = when (kind) {
        CbmEnvelopeKind.VOLUME -> value.toString()
        CbmEnvelopeKind.WAVE -> when (value) { 0 -> "off"; 1 -> "sq"; 2 -> "nz"; else -> value.toString() }
        else -> if (value > 0) "+$value" else value.toString()
    }

    /** The wave byte in words. */
    fun waveText(chip: CbmChip, wave: Int): String = when {
        chip == CbmChip.VIC -> "square (noise on the Noise lane)"
        wave == CbmImage.WAVE_NOISE -> "noise (Voice 2 only)"
        else -> "square"
    }

    /** One line per envelope: what the player does with it. */
    fun envelopeSummary(kind: CbmEnvelopeKind, chip: CbmChip): String = when (kind) {
        CbmEnvelopeKind.VOLUME -> "The chip's ONE master volume 0-${chip.maxVolume} per tick: while this note sounds it shapes every " +
            "voice (empty = the song's level). A release point makes OFF a release"
        CbmEnvelopeKind.ARPEGGIO -> if (chip == CbmChip.VIC) "Semitones added to the note per tick (on the Noise lane: rate steps)"
            else "Semitones added to the note per tick"
        CbmEnvelopeKind.PITCH -> "Sixty-fourths of a semitone per tick (relative: added up each tick)"
        CbmEnvelopeKind.WAVE -> if (chip == CbmChip.VIC) "Per tick 0 gates the voice off, 1 on: short notes on one voice. Empty = always on"
            else "Per tick 0 off, 1 square, 2 noise (Voice 2). Empty = the sound's Wave; Wxx is ignored while it runs"
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, CbmSystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(0, rows(song, st).size - 1)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, song.cbm.samples.size - 1))
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, CbmEnvelopes.lastStep(it)) }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, CbmSystem.SLOTS)
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
        val song = v.session.song
        val row = row(song, st)
        st.envelope = index.mod(pages(song, st).size)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.PAGE)
        st.envStep = 0
        if (page(song, st) == Page.SAMPLE) sound(song, st)?.let { st.dpcmSample = it.sample }
    }

    /** After the slot's kind may have changed: the page index stays within the kind's pages, the cursor on [row]. */
    private fun keepPage(v: ViewController, row: Row) {
        val st = v.state
        val song = v.session.song
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: st.instField.coerceIn(0, rows.size - 1)
    }

    /** Moves to another slot; Listen moves to the lane that slot plays on most, the sample list to its sample. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, CbmSystem.SLOTS)
        if (slot == st.instrument) return
        val song = v.session.song
        val row = row(song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(song, slot)?.let { st.listenChannel = it }
        sound(song, st)?.takeIf { it.kind == CbmKind.SAMPLE }?.let { st.dpcmSample = it.sample }
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
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, CbmEnvelopes.lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.SAMPLE_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, CbmSample.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, CbmEnvelopes.lastStep(e))
            row == Row.SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, song.cbm.samples.size - 1))
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
            Row.NAME -> session.editCbmInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX))
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.KIND -> if (d == 0) false else toggleKind(v)
            Row.WAVE -> if (d == 0) false else toggleWave(v)
            Row.PRESET -> stepPreset(v, d)
            Row.LENGTH, Row.LOOP, Row.RELEASE -> {
                val kind = envelopeKind(session.song, st) ?: return
                val range = range(session.song, kind)
                val fallback = kind.fallback(chip(session.song), sound(session.song, st))
                session.editCbmEnvelope(slot, kind, "Set ${kind.label.lowercase()} ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> CbmEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy, range, fallback)
                        Row.LOOP -> CbmEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy, range)
                        else -> CbmEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy, range)
                    }
                }
            }
            Row.MODE -> session.editCbmInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.SAMPLE -> stepSample(v, dx + 4 * dy)
            Row.ROOT -> session.editCbmInstrument(slot, "Set root note") {
                it.copy(root = (it.root + dx + 12 * dy).coerceIn(CbmImage.ROOT_MIN, CbmImage.ROOT_MAX))
            }
            Row.SAMPLE_LOOP -> session.editCbmInstrument(slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.LOOP_START -> {
                val length = session.song.cbm.samples.getOrNull(sound(session.song, st)?.sample ?: -1)?.data?.size ?: 1
                session.editCbmInstrument(slot, "Set loop start") {
                    it.copy(loopStart = (it.loopStart + dx + loopCoarse(length) * dy).coerceIn(0, length - 1))
                }
            }
            Row.SAMPLES -> { st.dpcmSample = (st.dpcmSample + d).coerceIn(0, maxOf(0, session.song.cbm.samples.size - 1)); false }
            Row.SAMPLE_NAME -> session.editCbmSample(st.dpcmSample, "Rename sample") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, CbmSample.MAX_NAME))
            }
            Row.ADD -> false
        }
        if (changed) heard(v)
    }

    /** Loop start's coarse step for a sample of [length] frames: a sixteenth of it, at least 16 frames. */
    fun loopCoarse(length: Int): Int = maxOf(16, length / 16)

    /** Envelope steps: Edit+↑↓ changes the step's value (adding one at the end), Edit+←→ carries it along. */
    private fun adjustSteps(v: ViewController, dx: Int, dy: Int): Boolean {
        val st = v.state
        val session = v.session
        val kind = envelopeKind(session.song, st) ?: return false
        val slot = st.instrument
        val name = kind.label.lowercase()
        val range = range(session.song, kind)
        val fallback = kind.fallback(chip(session.song), sound(session.song, st))
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editCbmEnvelope(slot, kind, "Set $name") {
                CbmEnvelopes.withValue(kind, it, st.envStep, CbmEnvelopes.valueAt(it, st.envStep, fallback) + dy, range)
            }
        }
        if (dx != 0) {
            val value = CbmEnvelopes.valueAt(e, st.envStep, fallback)
            val next = (st.envStep + dx).coerceIn(0, CbmEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editCbmEnvelope(slot, kind, "Draw $name") { CbmEnvelopes.withValue(kind, it, next, value, range) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(CbmEnvelopes.lastStep(it)) }
        }
        return changed
    }

    /** Tone ↔ sample (TED only): the slot's sound becomes that kind; a sample sound listens on the DAC lane. */
    private fun toggleKind(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        if (chip(session.song) == CbmChip.VIC) { session.note("The VIC-20 plays tones only: it has no D/A for samples"); return false }
        val slot = st.instrument
        val cur = sound(session.song, st)
        val next = if ((cur?.kind ?: CbmKind.TONE) == CbmKind.TONE) CbmKind.SAMPLE else CbmKind.TONE
        if (!session.setCbmKind(slot, next)) return false
        keepPage(v, Row.KIND)
        session.note("Sound ${EditorSession.hex2(slot)} is now " + when (next) {
            CbmKind.TONE -> "a tone sound: a square (or Voice 2's noise) on the voices"
            CbmKind.SAMPLE -> if (session.song.cbm.samples.isEmpty()) "a sample sound: the song has no samples yet, Add on the Sample page"
                else "a sample sound: it plays through the D/A on the DAC lane"
        } + if (cur != null) "; its other settings are kept" else "")
        return true
    }

    /** Square ↔ noise (TED tone sounds): a noise sound plays on Voice 2 only. */
    private fun toggleWave(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val ok = session.editCbmInstrument(st.instrument, "Set wave") {
            it.copy(wave = if (it.wave == CbmImage.WAVE_NOISE) CbmImage.WAVE_SQUARE else CbmImage.WAVE_NOISE)
        }
        val ins = sound(session.song, st)
        if (ok && ins?.wave == CbmImage.WAVE_NOISE) session.note("${ins.name}: noise, which the TED plays on Voice 2 only")
        return ok
    }

    /** The preset the sound is (by name and settings), or -1. */
    fun presetIndex(song: Song, ins: CbmInstrument?): Int {
        if (ins == null) return -1
        return CbmPresets.forChip(chip(song)).indexOfFirst { p ->
            (ins.name == p.instrument.name || ins.name == p.name.take(NAME_MAX)) && p.instrument.copy(name = ins.name) == ins
        }
    }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound. */
    private fun stepPreset(v: ViewController, delta: Int): Boolean {
        val presets = CbmPresets.forChip(chip(v.session.song))
        if (presets.isEmpty() || delta == 0) return false
        val i = FmControls.presetStep(presetIndex(v.session.song, sound(v.session.song, v.state)), delta, presets.size)
        return usePreset(v, presets[i])
    }

    /** Replaces the sound by [preset]; the note says what it does to the master volume or where it plays. */
    private fun usePreset(v: ViewController, preset: CbmPresets.Preset): Boolean {
        val st = v.state
        val ok = v.session.applyCbmPreset(st.instrument, preset)
        keepPage(v, Row.PRESET)
        v.session.note("${preset.name}: " + when {
            preset.master -> "its volume envelope takes the master volume while it sounds"
            preset.instrument.playsNoise -> "noise, Voice 2 only"
            else -> "a voice sound"
        })
        return ok
    }

    /** The presets popup: every preset of the song's chip, the cursor on the sound's own. */
    private fun openPresets(v: ViewController) {
        val presets = CbmPresets.forChip(chip(v.session.song))
        val current = presetIndex(v.session.song, sound(v.session.song, v.state))
        v.session.requestMenu { menu ->
            menu.menu("Preset for sound ${EditorSession.hex2(v.state.instrument)}", presets.map { p ->
                val tag = when {
                    p.master -> "master volume"
                    p.instrument.playsNoise -> "noise (Voice 2)"
                    else -> "voice"
                }
                "${p.name} · $tag" to { usePreset(v, p); Unit }
            }, selected = maxOf(0, current))
        }
    }

    /** Steps a sample sound's sample through the song's samples; the list follows. */
    private fun stepSample(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val session = v.session
        val samples = session.song.cbm.samples
        if (samples.isEmpty()) { session.note("The song has no samples yet: add some on the Add row"); return false }
        val ok = session.editCbmInstrument(st.instrument, "Set sample") { ins ->
            val next = (ins.sample + delta).coerceIn(0, samples.size - 1)
            ins.copy(sample = next, loopStart = ins.loopStart.coerceIn(0, samples[next].data.size - 1))
        }
        sound(session.song, st)?.let { st.dpcmSample = it.sample }
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
        val ins = sound(session.song, st)
        val changed = when (row) {
            Row.SLOT -> {
                if (ins == null) session.setCbmInstrument(slot, defaultCbmInstrument(slot), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: a new square sound to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            Row.KIND -> if (ins == null) session.setCbmInstrument(slot, defaultCbmInstrument(slot), "New sound") else toggleKind(v)
            Row.WAVE -> if (ins == null) session.setCbmInstrument(slot, defaultCbmInstrument(slot), "New sound") else toggleWave(v)
            Row.PRESET -> { openPresets(v); false }
            Row.MODE -> session.editCbmInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.SAMPLE_LOOP -> session.editCbmInstrument(slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.STEPS -> {
                val kind = envelopeKind(session.song, st) ?: return
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    val fallback = kind.fallback(chip(session.song), ins)
                    session.editCbmEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        CbmEnvelopes.withValue(kind, it, st.envStep, CbmEnvelopes.valueAt(it, st.envStep, fallback), range(session.song, kind))
                    }.also { envelope(session.song, st)?.let { st.envStep = CbmEnvelopes.lastStep(it) } }
                } else false
            }
            Row.SAMPLES -> useListedSample(v)
            Row.ADD -> { CbmSampleMenu.open(session, slot); false }
            else -> if (ins == null && row in SOUND_ROWS) session.setCbmInstrument(slot, defaultCbmInstrument(slot), "New sound") else false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    /** The sound plays the sample selected in the list. */
    private fun useListedSample(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val sample = session.song.cbm.samples.getOrNull(st.dpcmSample) ?: run { session.note("The song has no samples yet: add some on the Add row"); return false }
        if (sound(session.song, st)?.sample == st.dpcmSample) { session.note("Sound ${EditorSession.hex2(st.instrument)} already plays ${sample.name}"); return false }
        val ok = session.editCbmInstrument(st.instrument, "Set sample") { it.copy(sample = st.dpcmSample, loopStart = it.loopStart.coerceIn(0, sample.data.size - 1)) }
        session.note("Sound ${EditorSession.hex2(st.instrument)} plays ${sample.name}")
        return ok
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val ins = sound(song, st)
        val blank = defaultCbmInstrument(slot, ins?.kind ?: CbmKind.TONE)
        when (row(song, st)) {
            Row.SLOT -> session.setCbmInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.NAME -> if (ins != null) session.setCbmInstrument(slot, ins.copy(name = ViewController.deleteChar(ins.name, st.textPos)), "Rename sound")
            Row.KIND -> if (ins != null && ins.kind != CbmKind.TONE) { session.setCbmKind(slot, CbmKind.TONE); keepPage(v, Row.KIND) }
            Row.WAVE -> session.editCbmInstrument(slot, "Set wave") { it.copy(wave = CbmImage.WAVE_SQUARE) }
            Row.PRESET -> { session.setCbmInstrument(slot, blank, "Reset sound"); keepPage(v, Row.PRESET) }
            Row.LENGTH -> envelopeKind(song, st)?.let { k -> session.editCbmEnvelope(slot, k, "Reset ${k.label.lowercase()} envelope") { k.of(blank) } }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> session.editCbmEnvelope(slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.RELEASE -> envelopeKind(song, st)?.let { k -> session.editCbmEnvelope(slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.MODE -> session.editCbmInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                session.editCbmEnvelope(slot, k, "Delete ${k.label.lowercase()} step") { CbmEnvelopes.removed(k, it, st.envStep, range(song, k)) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(CbmEnvelopes.lastStep(it)) }
            }
            Row.SAMPLE -> session.editCbmInstrument(slot, "Set sample") { it.copy(sample = 0, loopStart = 0) }
            Row.ROOT -> session.editCbmInstrument(slot, "Set root note") { it.copy(root = CbmInstrument.ROOT) }
            Row.SAMPLE_LOOP -> session.editCbmInstrument(slot, "Set sample loop") { it.copy(loop = false) }
            Row.LOOP_START -> session.editCbmInstrument(slot, "Set loop start") { it.copy(loopStart = 0) }
            Row.SAMPLES -> {
                val name = song.cbm.samples.getOrNull(st.dpcmSample)?.name ?: return
                val users = session.deleteCbmSample(st.dpcmSample)
                normalise(v)
                session.note("Deleted sample $name" + if (users > 0) "; the $users sound${if (users == 1) "" else "s"} that played it now play the sample before it" else "")
            }
            Row.SAMPLE_NAME -> session.editCbmSample(st.dpcmSample, "Rename sample") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.LISTEN, Row.PAGE, Row.ADD -> Unit
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        if (ins.kind == CbmKind.SAMPLE && ins.sample !in song.cbm.samples.indices) {
            v.session.note("Sound ${EditorSession.hex2(st.instrument)} has no sample to play: add one on the Sample page"); return
        }
        val ch = listenChannel(song, st, ins)
        if (ch != st.listenChannel) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} plays on ${song.system.channels[ch].name}: " +
                if (ins.kind == CbmKind.SAMPLE) "samples play on the DAC lane" else if (ins.playsNoise) "the TED has noise on Voice 2 only" else "the DAC lane plays samples")
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
        val onEnvelope = kind != null && row !in head(song, st) && row != Row.PAGE
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyCbmEnvelope(slot, kind!!) else session.copyCbmInstrument(slot)
            Action.PASTE -> { session.pasteCbm(slot, kind); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                session.copyCbmEnvelope(slot, kind!!)
                session.editCbmEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(defaultCbmInstrument(slot)) }
            } else {
                session.copyCbmInstrument(slot)
                session.setCbmInstrument(slot, null, "Cut sound")
                keepPage(v, row)
            }
            Action.DUPLICATE -> {
                if (slot >= CbmSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                session.setCbmInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
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
        when (row) {
            Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (ins == null) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            Row.NAME, Row.SAMPLE_NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            Row.LISTEN -> h += Hint(v.lr(), "Lane Listen plays on")
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.KIND -> { h += Hint(v.k(Action.EDIT), "Tone / sample"); h += Hint(v.k(Action.CLEAR), "Tone") }
            Row.WAVE -> { h += Hint(v.k(Action.EDIT), "Square / noise"); h += Hint(v.k(Action.CLEAR), "Square") }
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.EDIT), "Presets list"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty envelope") }
            Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            Row.RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            Row.MODE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            Row.STEPS -> {
                h += Hint(v.lr(), "Step"); h += Hint(v.editUd(), "Value"); h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
            Row.SAMPLE -> { h += Hint(v.editLr(), "Sample (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "First sample") }
            Row.ROOT -> { h += Hint(v.editLr(), "Root note (${v.ud()} octave)"); h += Hint(v.k(Action.CLEAR), "C-4") }
            Row.SAMPLE_LOOP -> { h += Hint(v.k(Action.EDIT), "Loop on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.LOOP_START -> { h += Hint(v.editLr(), "Frame ±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Frame 0") }
            Row.SAMPLES -> { h += Hint(v.lr(), "Sample"); h += Hint(v.k(Action.EDIT), "Play it in this sound"); h += Hint(v.k(Action.CLEAR), "Delete sample") }
            Row.ADD -> h += Hint(v.k(Action.EDIT), "Starter kit or .wav file")
        }
        if (row !in LR_ROWS) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(song, st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in head(song, st) && row != Row.PAGE) "Copy/paste envelope" else "Copy/paste sound")
    }

    /** Help rows for this view on [chip] (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap, chip: CbmChip): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val editLr = keys.held(Action.EDIT, lr)
        val editUd = keys.held(Action.EDIT, ud)
        val vic = chip == CbmChip.VIC
        val sounds = buildList {
            add(ud to (if (vic) "Sound slot, name, Listen lane, preset, page, then the page's settings"
                else "Sound slot, name, Listen lane, kind, preset, wave (tone sounds), page, then the page's settings"))
            add(lr to "On settings: the sound slot; on envelope steps: the step; on the sample list: the sample")
            add(channels to (if (vic) "Pages: Master vol, Arpeggio, Pitch, Wave"
                else "Pages. Tone: Master vol, Arpeggio, Pitch, Wave. Sample: Sample, Arpeggio, Pitch"))
            add("Listen on" to (if (vic) "The lane $listen plays the sound on: Bass, Alto and Soprano play it as a square in their own " +
                "octaves, Noise as noise (the note picks the rate)"
                else "The lane $listen plays the sound on: sample sounds play on the DAC lane, sounds that can play noise on Voice 2") +
                ". Holding $edit on a setting sounds the sound and every change restarts it")
            if (!vic) {
                add("Kind" to "Tone: a square (or noise) on Voice 1 or Voice 2. Sample: a recording through the TED's D/A on the DAC lane, " +
                    "which takes both voices while it sounds. Switching keeps the other kind's settings")
                add("Wave" to "Square, or noise: the TED's noise is Voice 2's, so a noise sound plays on Voice 2 only")
            }
            add("Preset" to "$editLr steps through the starting sounds; $edit opens the list")
            add(editLr to "Change the value ($editUd in bigger steps)")
            add("$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)")
        }
        val envelopes = listOf(
            editUd to "On a step: raise / lower its value; past the last step it adds one",
            editLr to "On a step: carry its value to the next step (draws flat runs, adds steps at the end)",
            "$clear hold+$lr" to "Move along the steps by $COARSE",
            "Master vol" to envelopeSummary(CbmEnvelopeKind.VOLUME, chip),
            "Arpeggio" to envelopeSummary(CbmEnvelopeKind.ARPEGGIO, chip),
            "Pitch" to envelopeSummary(CbmEnvelopeKind.PITCH, chip) + if (vic) "; the Noise lane ignores it" else "",
            "Wave" to envelopeSummary(CbmEnvelopeKind.WAVE, chip),
            "Loop" to "The steps from the loop point to the end (or to the release point) repeat while the note holds; without one the last value holds",
            "Release" to "With a release point on the master volume or wave envelope OFF releases the note: every envelope with a " +
                "release point jumps to it and plays out. Without one OFF stops the note",
        )
        val samples = listOf(
            "Sample" to "The song's sample this sound plays ($editLr), one frame (0-8) per timer interrupt at the root note",
            "Root note" to "The note that plays the sample at its own rate (C-4 unless changed); other notes play it faster or slower",
            "Loop" to "Repeat from the loop start frame until the next note or OFF; off, the sample plays once",
            "Samples" to "$lr: choose; $edit: this sound plays it; $clear deletes it (sounds that played it take the sample before it)",
            "Add" to "$edit: the starter kit (kick, snare, hat, tom; made in code, free to use; one sound each in the next empty slots) " +
                "or a .wav file, converted on the device at the song's DAC rate (Song view or Txx)",
            "Space" to "Samples share the player's data with the patterns and envelopes; Help → Check shows what is used",
        )
        return listOf("Instruments" to sounds, "Envelopes" to envelopes) + if (vic) emptyList() else listOf("Samples" to samples)
    }

    override fun painter(pane: PaneView): Painter = CbmInstrumentPainter(pane)
}
