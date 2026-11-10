package org.thortracker.system.pce

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
 * The PC Engine instrument editor, controller-only: the one bank of [PceSystem.SLOTS] sounds, each empty or a tone,
 * noise or sample sound. Head rows: slot, name, the channel Listen plays on (noise sounds: PSG 5-6 only), the kind, a
 * preset, on tone sounds the wave (Edit shows it in a following Wave view), then the page. Pages: tone sounds have the
 * volume, arpeggio, pitch and wave-sequence envelopes; noise sounds volume and arpeggio; sample sounds the *Sample*
 * page (the sample they play, root note, loop, the song's samples with Add/rename/delete, [PceSampleMenu]) and volume,
 * arpeggio and pitch. Envelopes are edited step by step as on the NES.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep] (the envelope step; size = append), [ViewState.listenChannel], [ViewState.dpcmSample]
 * (the sample list cursor), [ViewState.textPos] (names).
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object PceInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: PceEnvelopeKind?) {
        SAMPLE("Sample", null),
        VOLUME("Volume", PceEnvelopeKind.VOLUME),
        ARPEGGIO("Arpeggio", PceEnvelopeKind.ARPEGGIO),
        PITCH("Pitch", PceEnvelopeKind.PITCH),
        WAVES("Wave seq", PceEnvelopeKind.WAVE),
    }

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), KIND("Kind"), PRESET("Preset"), WAVE("Wave"), PAGE("Page"),
        LENGTH("Length"), LOOP("Loop"), RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
        SAMPLE("Sample"), ROOT("Root note"), SAMPLE_LOOP("Loop"), LOOP_START("Loop start"),
        SAMPLES("Samples"), SAMPLE_NAME("Sample name"), ADD("Add"),
    }

    private val PAGES = mapOf(
        PceKind.TONE to listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.WAVES),
        PceKind.NOISE to listOf(Page.VOLUME, Page.ARPEGGIO),
        PceKind.SAMPLE to listOf(Page.SAMPLE, Page.VOLUME, Page.ARPEGGIO, Page.PITCH),
    )

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.KIND, Row.PRESET)

    /** The Sample page: the sound's sample settings, then the song's samples (drawn in a box beside them). */
    val SAMPLE_ROWS = listOf(Row.SAMPLE, Row.ROOT, Row.SAMPLE_LOOP, Row.LOOP_START, Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = setOf(Row.KIND, Row.PRESET, Row.WAVE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE,
        Row.SAMPLE, Row.ROOT, Row.SAMPLE_LOOP, Row.LOOP_START)

    /** Rows where ←→ moves within the row (or has its own hint) rather than to another slot. */
    private val LR_ROWS = setOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE, Row.STEPS, Row.SAMPLES, Row.SAMPLE_NAME)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun sound(song: Song, state: ViewState): PceInstrument? = song.pceInstrument(state.instrument)

    /** The shown sound's kind; an empty slot shows a tone sound's pages (Edit makes one). */
    fun kind(song: Song, state: ViewState): PceKind = sound(song, state)?.kind ?: PceKind.TONE

    fun pages(song: Song, state: ViewState): List<Page> = PAGES.getValue(kind(song, state))

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> {
        val head = if (kind(song, state) == PceKind.TONE) HEAD + Row.WAVE + Row.PAGE else HEAD + Row.PAGE
        val page = page(song, state)
        val kind = page.envelope ?: return head + SAMPLE_ROWS
        return head + if (kind == PceEnvelopeKind.PITCH) listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
            else listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS)
    }

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The envelope the shown page edits, or null (the Sample page). */
    fun envelopeKind(song: Song, state: ViewState): PceEnvelopeKind? = page(song, state).envelope

    /** The shown envelope (an empty slot's: the new sound's). */
    fun envelope(song: Song, state: ViewState): PceEnvelope? =
        envelopeKind(song, state)?.of(sound(song, state) ?: defaultPceInstrument(state.instrument))

    /** The value scale of envelope [kind] in [song]. */
    fun range(song: Song, kind: PceEnvelopeKind): IntRange = kind.range(song.pce.waves.size)

    /** What a new step of [kind] starts from on an empty envelope: the wave sequence starts on the sound's own wave. */
    fun fallback(ins: PceInstrument?, kind: PceEnvelopeKind): Int = if (kind == PceEnvelopeKind.WAVE) ins?.wave ?: 0 else kind.fallback

    /** The channel a held Listen plays [ins] on: a noise sound on PSG 5 unless Listen is PSG 5 or 6, else Listen's. */
    fun listenChannel(state: ViewState, ins: PceInstrument?): Int =
        if (ins?.kind == PceKind.NOISE && state.listenChannel !in PceRules.NOISE_CHANNELS) PceRules.NOISE_CHANNELS.first else state.listenChannel

    /** A step value as the editor writes it: offsets signed, waves in hex. */
    fun valueText(kind: PceEnvelopeKind, value: Int): String = when (kind) {
        PceEnvelopeKind.VOLUME -> value.toString()
        PceEnvelopeKind.WAVE -> EditorSession.hex2(value)
        else -> if (value > 0) "+$value" else value.toString()
    }

    /** One line per envelope: what the player does with it. */
    fun envelopeSummary(kind: PceEnvelopeKind, sound: PceKind): String = when (kind) {
        PceEnvelopeKind.VOLUME -> "Channel volume 0-31 per tick (1.5 dB steps; empty = 31). A release point makes OFF a release"
        PceEnvelopeKind.ARPEGGIO -> if (sound == PceKind.NOISE) "Semitones added to the note per tick: steps the noise rate (note mod 32)"
            else "Semitones added to the note per tick"
        PceEnvelopeKind.PITCH -> "Sixty-fourths of a semitone per tick (relative: added up each tick)"
        PceEnvelopeKind.WAVE -> "The wave played each tick (the song's waves; empty = the sound's Wave). Wxx is ignored while it runs"
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, PceSystem.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(0, rows(song, st).size - 1)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, song.pce.samples.size - 1))
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, PceEnvelopes.lastStep(it)) }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, PceSystem.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Selects the slot for entry; a tone sound also points a following Wave view at its wave. */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        val ins = sound(session.song, v.state)
        val wave = if (ins?.kind == PceKind.TONE) ins.wave else session.selection.wave
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument, wave = wave, context = ViewKind.KEYBOARD))
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

    /** Moves to another slot; Listen moves to the channel that slot plays on most, the sample list to its sample. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, PceSystem.SLOTS)
        if (slot == st.instrument) return
        val song = v.session.song
        val row = row(song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(song, slot)?.let { st.listenChannel = it }
        sound(song, st)?.takeIf { it.kind == PceKind.SAMPLE }?.let { st.dpcmSample = it.sample }
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
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, PceEnvelopes.lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.SAMPLE_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, PceSample.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, PceEnvelopes.lastStep(e))
            row == Row.SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, song.pce.samples.size - 1))
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
            Row.NAME -> session.editPceInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX))
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.KIND -> stepKind(v, d)
            Row.PRESET -> stepPreset(v, d)
            Row.WAVE -> stepWave(v, dx + COARSE * dy)
            Row.LENGTH, Row.LOOP, Row.RELEASE -> {
                val kind = envelopeKind(session.song, st) ?: return
                val range = range(session.song, kind)
                val fallback = fallback(sound(session.song, st), kind)
                session.editPceEnvelope(slot, kind, "Set ${kind.label.lowercase()} ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> PceEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy, range, fallback)
                        Row.LOOP -> PceEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy, range)
                        else -> PceEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy, range)
                    }
                }
            }
            Row.MODE -> session.editPceInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.SAMPLE -> stepSample(v, dx + 4 * dy)
            Row.ROOT -> session.editPceInstrument(slot, "Set root note") { it.copy(root = (it.root + dx + 12 * dy).coerceIn(PceRules.NOTES)) }
            Row.SAMPLE_LOOP -> session.editPceInstrument(slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.LOOP_START -> {
                val length = session.song.pce.samples.getOrNull(sound(session.song, st)?.sample ?: -1)?.data?.size ?: 1
                session.editPceInstrument(slot, "Set loop start") {
                    it.copy(loopStart = (it.loopStart + dx + loopCoarse(length) * dy).coerceIn(0, length - 1))
                }
            }
            Row.SAMPLES -> { st.dpcmSample = (st.dpcmSample + d).coerceIn(0, maxOf(0, session.song.pce.samples.size - 1)); false }
            Row.SAMPLE_NAME -> session.editPceSample(st.dpcmSample, "Rename sample") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, PceSample.MAX_NAME))
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
        val fallback = fallback(sound(session.song, st), kind)
        val e = envelope(session.song, st) ?: return false
        var changed = false
        if (dy != 0) {
            changed = session.editPceEnvelope(slot, kind, "Set $name") {
                PceEnvelopes.withValue(kind, it, st.envStep, PceEnvelopes.valueAt(it, st.envStep, fallback) + dy, range)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = PceEnvelopes.valueAt(e, st.envStep, fallback)
            val next = (st.envStep + dx).coerceIn(0, PceEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editPceEnvelope(slot, kind, "Draw $name") { PceEnvelopes.withValue(kind, it, next, value, range) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(PceEnvelopes.lastStep(it)) }
        }
        return changed
    }

    /** Tone → noise → sample (wrapping): the slot's sound becomes that kind; a noise sound listens on PSG 5-6. */
    private fun stepKind(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val kinds = PceKind.entries
        val cur = sound(session.song, st)
        // An empty slot shows a tone sound's pages: stepping starts from tone.
        val next = kinds[(kinds.indexOf(cur?.kind ?: PceKind.TONE) + delta).mod(kinds.size)]
        val ok = session.setPceKind(slot, next)
        if (!ok) return false
        if (next == PceKind.NOISE && st.listenChannel !in PceRules.NOISE_CHANNELS) st.listenChannel = PceRules.NOISE_CHANNELS.first
        keepPage(v, Row.KIND)
        session.note("Sound ${EditorSession.hex2(slot)} is now " + when (next) {
            PceKind.TONE -> "a tone sound: it plays its wave on any channel"
            PceKind.NOISE -> "a noise sound: it plays on PSG 5 and PSG 6 only, the note picks the noise rate"
            PceKind.SAMPLE -> if (session.song.pce.samples.isEmpty()) "a sample sound: the song has no samples yet, Add on the Sample page"
                else "a sample sound: it streams its sample through the D/A on any channel"
        } + if (cur != null) "; its other settings are kept" else "")
        return true
    }

    /** The preset the sound is (by name and settings, its wave compared by values), or -1. */
    fun presetIndex(song: Song, ins: PceInstrument?): Int {
        if (ins == null) return -1
        return PcePresets.ALL.indexOfFirst { p ->
            (ins.name == p.instrument.name || ins.name == p.name.take(NAME_MAX)) && p.instrument.copy(name = ins.name, wave = ins.wave) == ins &&
                (p.wave == null || song.pce.waves.getOrNull(ins.wave) == p.wave)
        }
    }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound. */
    private fun stepPreset(v: ViewController, delta: Int): Boolean {
        val presets = PcePresets.ALL
        if (presets.isEmpty() || delta == 0) return false
        val i = FmControls.presetStep(presetIndex(v.session.song, sound(v.session.song, v.state)), delta, presets.size)
        return usePreset(v, presets[i])
    }

    /** Replaces the sound by [preset]; noise presets listen on PSG 5-6. */
    private fun usePreset(v: ViewController, preset: PcePresets.Preset): Boolean {
        val st = v.state
        val ok = v.session.applyPcePreset(st.instrument, preset)
        if (!ok) return false
        if (preset.instrument.kind == PceKind.NOISE && st.listenChannel !in PceRules.NOISE_CHANNELS) st.listenChannel = PceRules.NOISE_CHANNELS.first
        keepPage(v, Row.PRESET)
        val ins = sound(v.session.song, st)
        v.session.note("${preset.name}: " + when (preset.instrument.kind) {
            PceKind.TONE -> "tone on wave ${EditorSession.hex2(ins?.wave ?: 0)}" +
                if (preset.wave != null && v.session.song.pce.waves.getOrNull(ins?.wave ?: -1) != preset.wave) " (the song has no room for its own wave)" else ""
            PceKind.NOISE -> "noise, PSG 5-6"
            PceKind.SAMPLE -> "sample sound"
        })
        return ok
    }

    /** The presets popup: every preset by category, the cursor on the sound's own. */
    private fun openPresets(v: ViewController) {
        val presets = PcePresets.ALL
        val current = presetIndex(v.session.song, sound(v.session.song, v.state))
        v.session.requestMenu { menu ->
            menu.menu("Preset for sound ${EditorSession.hex2(v.state.instrument)}", presets.map { p ->
                "${p.name} · ${if (p.category == PcePresets.Category.NOISE) "noise (PSG 5-6)" else "tone"}" to { usePreset(v, p); Unit }
            }, selected = maxOf(0, current))
        }
    }

    /** Steps a tone sound's wave through the song's waves. */
    private fun stepWave(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val waves = v.session.song.pce.waves.size
        if (waves == 0) { v.session.note("The song has no waves: the Wave view's New tool makes one"); return false }
        val ok = v.session.editPceInstrument(st.instrument, "Set wave") { it.copy(wave = (it.wave + delta).coerceIn(0, waves - 1)) }
        if (ok) publish(v)
        return ok
    }

    /** Steps a sample sound's sample through the song's samples; the list follows. */
    private fun stepSample(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val session = v.session
        val samples = session.song.pce.samples
        if (samples.isEmpty()) { session.note("The song has no samples yet: add some on the Add row"); return false }
        val ok = session.editPceInstrument(st.instrument, "Set sample") { ins ->
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
                if (ins == null) session.setPceInstrument(slot, defaultPceInstrument(slot), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: a new tone sound to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            Row.KIND -> if (ins == null) session.setPceInstrument(slot, defaultPceInstrument(slot), "New sound") else stepKind(v, 1)
            Row.PRESET -> { openPresets(v); false }
            Row.WAVE -> {
                if (ins == null) session.setPceInstrument(slot, defaultPceInstrument(slot), "New sound")
                else {
                    publish(v)
                    session.select(session.selection.copy(context = ViewKind.WAVE))
                    session.note("Wave ${EditorSession.hex2(ins.wave)}: a following Wave view shows it")
                    false
                }
            }
            Row.MODE -> session.editPceInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.SAMPLE_LOOP -> session.editPceInstrument(slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.STEPS -> {
                val kind = envelopeKind(session.song, st) ?: return
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    val fallback = fallback(ins, kind)
                    session.editPceEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        PceEnvelopes.withValue(kind, it, st.envStep, PceEnvelopes.valueAt(it, st.envStep, fallback), range(session.song, kind))
                    }.also { envelope(session.song, st)?.let { st.envStep = PceEnvelopes.lastStep(it) } }
                } else false
            }
            Row.SAMPLES -> useListedSample(v)
            Row.ADD -> { PceSampleMenu.open(session, slot); false }
            else -> if (ins == null && row in SOUND_ROWS) session.setPceInstrument(slot, defaultPceInstrument(slot), "New sound") else false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    /** The sound plays the sample selected in the list. */
    private fun useListedSample(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val sample = session.song.pce.samples.getOrNull(st.dpcmSample) ?: run { session.note("The song has no samples yet: add some on the Add row"); return false }
        if (sound(session.song, st)?.sample == st.dpcmSample) { session.note("Sound ${EditorSession.hex2(st.instrument)} already plays ${sample.name}"); return false }
        val ok = session.editPceInstrument(st.instrument, "Set sample") { it.copy(sample = st.dpcmSample, loopStart = it.loopStart.coerceIn(0, sample.data.size - 1)) }
        session.note("Sound ${EditorSession.hex2(st.instrument)} plays ${sample.name}")
        return ok
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val ins = sound(song, st)
        val blank = defaultPceInstrument(slot, ins?.kind ?: PceKind.TONE)
        when (row(song, st)) {
            Row.SLOT -> session.setPceInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.NAME -> if (ins != null) session.setPceInstrument(slot, ins.copy(name = ViewController.deleteChar(ins.name, st.textPos)), "Rename sound")
            Row.KIND -> if (ins != null && ins.kind != PceKind.TONE) { session.setPceKind(slot, PceKind.TONE); keepPage(v, Row.KIND) }
            Row.PRESET -> { session.setPceInstrument(slot, blank, "Reset sound"); keepPage(v, Row.PRESET) }
            Row.WAVE -> session.editPceInstrument(slot, "Set wave") { it.copy(wave = 0) }
            Row.LENGTH -> envelopeKind(song, st)?.let { k -> session.editPceEnvelope(slot, k, "Reset ${k.label.lowercase()} envelope") { k.of(blank) } }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> session.editPceEnvelope(slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.RELEASE -> envelopeKind(song, st)?.let { k -> session.editPceEnvelope(slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.MODE -> session.editPceInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                session.editPceEnvelope(slot, k, "Delete ${k.label.lowercase()} step") { PceEnvelopes.removed(k, it, st.envStep, range(song, k)) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(PceEnvelopes.lastStep(it)) }
            }
            Row.SAMPLE -> session.editPceInstrument(slot, "Set sample") { it.copy(sample = 0, loopStart = 0) }
            Row.ROOT -> session.editPceInstrument(slot, "Set root note") { it.copy(root = PceInstrument.ROOT) }
            Row.SAMPLE_LOOP -> session.editPceInstrument(slot, "Set sample loop") { it.copy(loop = false) }
            Row.LOOP_START -> session.editPceInstrument(slot, "Set loop start") { it.copy(loopStart = 0) }
            Row.SAMPLES -> {
                val name = song.pce.samples.getOrNull(st.dpcmSample)?.name ?: return
                val users = session.deletePceSample(st.dpcmSample)
                normalise(v)
                session.note("Deleted sample $name" + if (users > 0) "; the $users sound${if (users == 1) "" else "s"} that played it now play the sample before it" else "")
            }
            Row.SAMPLE_NAME -> session.editPceSample(st.dpcmSample, "Rename sample") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.LISTEN, Row.PAGE, Row.ADD -> Unit
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        if (ins.kind == PceKind.SAMPLE && ins.sample !in song.pce.samples.indices) {
            v.session.note("Sound ${EditorSession.hex2(st.instrument)} has no sample to play: add one on the Sample page"); return
        }
        val ch = listenChannel(st, ins)
        if (ch != st.listenChannel) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} is a noise sound: listening on ${song.system.channels[ch].name} (noise plays on PSG 5-6 only)")
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
        val onEnvelope = kind != null && row !in HEAD && row != Row.WAVE && row != Row.PAGE
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyPceEnvelope(slot, kind!!) else session.copyPceInstrument(slot)
            Action.PASTE -> { session.pastePce(slot, kind); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                session.copyPceEnvelope(slot, kind!!)
                session.editPceEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(defaultPceInstrument(slot)) }
            } else {
                session.copyPceInstrument(slot)
                session.setPceInstrument(slot, null, "Cut sound")
                keepPage(v, row)
            }
            Action.DUPLICATE -> {
                if (slot >= PceSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                session.setPceInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
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
            Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.KIND -> { h += Hint(v.editLr(), "Tone / noise / sample"); h += Hint(v.k(Action.CLEAR), "Tone") }
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.EDIT), "Presets list"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            Row.WAVE -> { h += Hint(v.editLr(), "Wave"); h += Hint(v.k(Action.EDIT), "Show in the Wave view"); h += Hint(v.k(Action.CLEAR), "Wave 00") }
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
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${PceSystem.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in HEAD && row != Row.WAVE && row != Row.PAGE) "Copy/paste envelope" else "Copy/paste sound")
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
        val editUd = keys.held(Action.EDIT, ud)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, kind, preset, wave (tone sounds), page, then the page's settings",
                lr to "On settings: the sound slot; on envelope steps: the step; on the sample list: the sample",
                channels to "Pages. Tone: Volume, Arpeggio, Pitch, Wave seq. Noise: Volume, Arpeggio. Sample: Sample, Volume, Arpeggio, Pitch",
                "Listen on" to "The channel $listen plays the sound on (PSG 1-6); a noise sound plays on PSG 5 or 6, the channels with a noise " +
                    "generator. Holding $edit on a setting sounds the sound and every change restarts it",
                "Kind" to "Tone: the channel's 32-step wave at the note's pitch. Noise: the noise generator (PSG 5-6), the note picks its rate " +
                    "(note mod 32). Sample: the channel's D/A streams a sample from the CPU timer (any channel). Switching keeps the other " +
                    "kinds' settings",
                "Preset" to "$editLr steps through the starting sounds (tones on built-in waves and noise drums); $edit opens the list",
                "Wave" to "The wave a tone sound plays ($editLr); $edit shows it in a following Wave view, where it is drawn",
                editLr to "Change the value ($editUd in bigger steps)",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new tone sound to start from",
            ),
            "Envelopes" to listOf(
                editUd to "On a step: raise / lower its value; past the last step it adds one",
                editLr to "On a step: carry its value to the next step (draws flat runs, adds steps at the end)",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
                "Volume" to envelopeSummary(PceEnvelopeKind.VOLUME, PceKind.TONE),
                "Arpeggio" to "Semitones added to the note per tick; on noise sounds it steps the noise rate",
                "Pitch" to envelopeSummary(PceEnvelopeKind.PITCH, PceKind.TONE) + "; noise sounds ignore it",
                "Wave seq" to envelopeSummary(PceEnvelopeKind.WAVE, PceKind.TONE),
                "Loop" to "The steps from the loop point to the end (or to the release point) repeat while the note holds; without one the last value holds",
                "Release" to "With a release point on the volume envelope OFF releases the note: every envelope with a release point " +
                    "jumps to it and plays out. Without one OFF stops the note",
            ),
            "Samples" to listOf(
                "Sample" to "The song's sample this sound plays ($editLr), one 5-bit frame per timer interrupt at the root note",
                "Root note" to "The note that plays the sample at its own rate (C-4 unless changed); other notes play it faster or slower",
                "Loop" to "Repeat from the loop start frame until the next note or OFF; off, the sample plays once",
                "Samples" to "$lr: choose; $edit: this sound plays it; $clear deletes it (sounds that played it take the sample before it)",
                "Add" to "$edit: the starter kit (kick, snare, hat, tom; made in code, free to use; one sound each in the next empty slots) " +
                    "or a .wav file, converted on the device at the song's DDA rate (Song view or Txx: 6991 Hz ÷ (timer + 1))",
                "Space" to "Samples share the player's data with the patterns and envelopes; Help → Check shows what is used",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = PceInstrumentPainter(pane)
}
