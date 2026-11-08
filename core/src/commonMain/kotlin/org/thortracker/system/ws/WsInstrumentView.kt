package org.thortracker.system.ws

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
 * The WonderSwan instrument editor, controller-only: the one bank of [WsRules.SLOTS] sounds, each empty or a tone,
 * noise or sample sound. Head rows: slot, name, the lane Listen plays on (a sound plays only where it fits: noise on
 * CH4, mono samples on CH2, samples on the Color's Hyper Voice), the kind, on sample sounds the feed (CPU or, on the
 * Color, Sound DMA), a preset; on tone sounds the wave (Edit shows it in a following Wave view), the CH3 sweep and its
 * time; on noise sounds the tap mode and LFSR reset; then the page. Pages: tone sounds have the volume, arpeggio, pitch
 * and wave-sequence envelopes; noise sounds volume, arpeggio, pitch and tap sequence; CPU-fed sample sounds the
 * *Sample* page (the sample, root note, loop and loop start, the song's samples with Add/rename/delete,
 * [WsSampleMenu]), volume, arpeggio and pitch; DMA sample sounds the Sample page (sample, rate, loop, backwards) and
 * volume. Envelopes are edited step by step as on the NES.
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page index among
 * [pages]), [ViewState.envStep] (the envelope step; size = append), [ViewState.listenChannel], [ViewState.dpcmSample]
 * (the sample list cursor), [ViewState.textPos] (names).
 * Holding Edit on a sound row sounds the instrument and every change restarts it, so edits are heard as made.
 */
object WsInstrumentView : SystemView() {
    enum class Page(val label: String, val envelope: WsEnvelopeKind?) {
        SAMPLE("Sample", null),
        VOLUME("Volume", WsEnvelopeKind.VOLUME),
        ARPEGGIO("Arpeggio", WsEnvelopeKind.ARPEGGIO),
        PITCH("Pitch", WsEnvelopeKind.PITCH),
        WAVES("Wave seq", WsEnvelopeKind.WAVE),
        TAPS("Tap seq", WsEnvelopeKind.TAP),
    }

    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), LISTEN("Listen on"), KIND("Kind"), FEED("Feed"), PRESET("Preset"),
        WAVE("Wave"), SWEEP("Sweep"), SWEEP_TIME("Sweep time"), TAP("Tap mode"), LFSR_RESET("LFSR reset"), PAGE("Page"),
        LENGTH("Length"), LOOP("Loop"), RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
        SAMPLE("Sample"), ROOT("Root note"), RATE("DMA rate"), SAMPLE_LOOP("Loop"), LOOP_START("Loop start"), BACKWARDS("Backwards"),
        SAMPLES("Samples"), SAMPLE_NAME("Sample name"), ADD("Add"),
    }

    /** Rows whose edits change how the sound plays: holding Edit on them sounds it. */
    private val SOUND_ROWS = setOf(Row.KIND, Row.FEED, Row.PRESET, Row.WAVE, Row.SWEEP, Row.SWEEP_TIME, Row.TAP, Row.LFSR_RESET,
        Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.SAMPLE, Row.ROOT, Row.RATE, Row.SAMPLE_LOOP, Row.LOOP_START, Row.BACKWARDS)

    /** Rows where ←→ moves within the row (or has its own hint) rather than to another slot. */
    private val LR_ROWS = setOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.PAGE, Row.STEPS, Row.SAMPLES, Row.SAMPLE_NAME)

    /** Rows drawn as head settings (before the page). */
    val HEAD_ROWS = setOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.KIND, Row.FEED, Row.PRESET, Row.WAVE, Row.SWEEP, Row.SWEEP_TIME,
        Row.TAP, Row.LFSR_RESET, Row.PAGE)

    private const val COARSE = FmControls.COARSE
    const val NAME_MAX = FmControls.NAME_MAX

    fun sound(song: Song, state: ViewState): WsInstrument? = song.wsInstrument(state.instrument)

    /** The shown sound's kind; an empty slot shows a tone sound's pages (Edit makes one). */
    fun kind(song: Song, state: ViewState): WsKind = sound(song, state)?.kind ?: WsKind.TONE

    fun pages(song: Song, state: ViewState): List<Page> {
        val ins = sound(song, state)
        return when (kind(song, state)) {
            WsKind.TONE -> listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.WAVES)
            WsKind.NOISE -> listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.TAPS)
            WsKind.SAMPLE -> if (ins?.isDma == true) listOf(Page.SAMPLE, Page.VOLUME) else listOf(Page.SAMPLE, Page.VOLUME, Page.ARPEGGIO, Page.PITCH)
        }
    }

    fun page(song: Song, state: ViewState): Page = pages(song, state).let { it[state.envelope.coerceIn(0, it.size - 1)] }

    fun rows(song: Song, state: ViewState): List<Row> {
        val ins = sound(song, state)
        val head = buildList {
            add(Row.SLOT); add(Row.NAME); add(Row.LISTEN); add(Row.KIND)
            if (ins?.kind == WsKind.SAMPLE && song.ws.color) add(Row.FEED)
            add(Row.PRESET)
            when (kind(song, state)) {
                WsKind.TONE -> { add(Row.WAVE); add(Row.SWEEP); add(Row.SWEEP_TIME) }
                WsKind.NOISE -> { add(Row.TAP); add(Row.LFSR_RESET) }
                WsKind.SAMPLE -> Unit
            }
            add(Row.PAGE)
        }
        val kind = page(song, state).envelope
            ?: return head + if (ins?.isDma == true) listOf(Row.SAMPLE, Row.RATE, Row.SAMPLE_LOOP, Row.BACKWARDS, Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)
                else listOf(Row.SAMPLE, Row.ROOT, Row.SAMPLE_LOOP, Row.LOOP_START, Row.SAMPLES, Row.SAMPLE_NAME, Row.ADD)
        return head + if (kind == WsEnvelopeKind.PITCH) listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
            else listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS)
    }

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The envelope the shown page edits, or null (the Sample page). */
    fun envelopeKind(song: Song, state: ViewState): WsEnvelopeKind? = page(song, state).envelope

    /** The shown envelope (an empty slot's: the new sound's). */
    fun envelope(song: Song, state: ViewState): WsEnvelope? =
        envelopeKind(song, state)?.of(sound(song, state) ?: defaultWsInstrument(state.instrument))

    /** The value scale of envelope [kind] in [song]. */
    fun range(song: Song, kind: WsEnvelopeKind): IntRange = kind.range(song.ws.waves.size)

    /** What a new step of [kind] starts from on an empty envelope: the wave/tap sequence starts on the sound's own. */
    fun fallback(ins: WsInstrument?, kind: WsEnvelopeKind): Int = when (kind) {
        WsEnvelopeKind.WAVE -> ins?.wave ?: 0
        WsEnvelopeKind.TAP -> ins?.tap ?: 0
        else -> kind.fallback
    }

    /**
     * The lane a held Listen plays [ins] on: the Listen lane when the sound fits it, else the first lane it fits (tones:
     * CH1; noise: CH4; samples: CH2 when mono, else Hyper Voice).
     */
    fun listenChannel(song: Song, state: ViewState, ins: WsInstrument?): Int {
        val sound = song.ws
        if (ins == null || WsValidator.fits(sound, ins, state.listenChannel)) return state.listenChannel
        return (0 until WsRules.laneCount(sound.color)).firstOrNull { WsValidator.fits(sound, ins, it) } ?: state.listenChannel
    }

    /** A step value as the editor writes it: offsets signed, waves in hex. */
    fun valueText(kind: WsEnvelopeKind, value: Int): String = when (kind) {
        WsEnvelopeKind.VOLUME, WsEnvelopeKind.TAP -> value.toString()
        WsEnvelopeKind.WAVE -> EditorSession.hex2(value)
        else -> if (value > 0) "+$value" else value.toString()
    }

    /** One line per envelope: what the player does with it. */
    fun envelopeSummary(kind: WsEnvelopeKind, sound: WsKind): String = when (kind) {
        WsEnvelopeKind.VOLUME -> if (sound == WsKind.SAMPLE) "Lane volume 0-15 per tick: CH2 voice off/50/100 %, Hyper Voice 4 levels. A release point makes OFF a release"
            else "Lane volume 0-15 per tick (4-bit per side; empty = 15). A release point makes OFF a release"
        WsEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick"
        WsEnvelopeKind.PITCH -> if (sound == WsKind.NOISE) "Sixty-fourths of a semitone per tick: moves the LFSR's clock (relative: added up)"
            else "Sixty-fourths of a semitone per tick (relative: added up each tick)"
        WsEnvelopeKind.WAVE -> "The wave played each tick (the song's waves; empty = the sound's Wave). Wxx is ignored while it runs"
        WsEnvelopeKind.TAP -> "The LFSR tap mode each tick (0-7; empty = the sound's Tap mode). Nxx is ignored while it runs"
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, WsRules.SLOTS)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envelope = st.envelope.coerceIn(0, pages(song, st).size - 1)
        st.instField = st.instField.coerceIn(0, rows(song, st).size - 1)
        st.dpcmSample = st.dpcmSample.coerceIn(0, maxOf(0, song.ws.samples.size - 1))
        envelope(song, st)?.let { st.envStep = st.envStep.coerceIn(0, WsEnvelopes.lastStep(it)) }
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, WsRules.SLOTS)
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
        val wave = if (ins?.kind == WsKind.TONE) ins.wave else session.selection.wave
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

    /** After the slot's kind or feed may have changed: the page index stays within its pages, the cursor on [row]. */
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
        val slot = (st.instrument + delta).coerceIn(1, WsRules.SLOTS)
        if (slot == st.instrument) return
        val song = v.session.song
        val row = row(song, st)
        st.instrument = slot
        FmControls.mostUsedChannel(song, slot)?.let { st.listenChannel = it }
        sound(song, st)?.takeIf { it.kind == WsKind.SAMPLE }?.let { st.dpcmSample = it.sample }
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
            row == Row.STEPS && v.clearHeld -> { v.useClear(); if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, WsEnvelopes.lastStep(e)) }
            dy != 0 -> {
                st.instField = (st.instField - dy).coerceIn(0, rows.size - 1)
                st.textPos = 0
            }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, NAME_MAX - 1)
            row == Row.SAMPLE_NAME -> st.textPos = (st.textPos + dx).coerceIn(0, WsSample.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, WsEnvelopes.lastStep(e))
            row == Row.SAMPLES -> st.dpcmSample = (st.dpcmSample + dx).coerceIn(0, maxOf(0, song.ws.samples.size - 1))
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
            Row.NAME -> session.editWsInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, NAME_MAX))
            }
            Row.LISTEN -> { st.listenChannel = (st.listenChannel + d).mod(session.song.system.channels.size); false }
            Row.PAGE -> { showPage(v, st.envelope + d); false }
            Row.KIND -> stepKind(v, d)
            Row.FEED -> if (d == 0) false else toggleFeed(v)
            Row.PRESET -> stepPreset(v, d)
            Row.WAVE -> stepWave(v, dx + COARSE * dy)
            Row.SWEEP -> session.editWsInstrument(slot, "Set sweep") { it.copy(sweep = (it.sweep + dx + 16 * dy).coerceIn(-128, 127)) }
            Row.SWEEP_TIME -> session.editWsInstrument(slot, "Set sweep time") {
                it.copy(sweepTime = (it.sweepTime + dx + COARSE * dy).coerceIn(0, org.thortracker.backend.WsPlayerImage.MAX_SWEEP_TIME))
            }
            Row.TAP -> session.editWsInstrument(slot, "Set tap mode") { it.copy(tap = (it.tap + d).coerceIn(WsEnvelope.TAPS)) }
            Row.LFSR_RESET -> if (d == 0) false else session.editWsInstrument(slot, "Set LFSR reset") { it.copy(lfsrReset = !it.lfsrReset) }
            Row.LENGTH, Row.LOOP, Row.RELEASE -> {
                val kind = envelopeKind(session.song, st) ?: return
                val range = range(session.song, kind)
                val fallback = fallback(sound(session.song, st), kind)
                session.editWsEnvelope(slot, kind, "Set ${kind.label.lowercase()} ${row.label.lowercase()}") {
                    when (row) {
                        Row.LENGTH -> WsEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy, range, fallback)
                        Row.LOOP -> WsEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy, range)
                        else -> WsEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy, range)
                    }
                }
            }
            Row.MODE -> session.editWsInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.STEPS -> adjustSteps(v, dx, dy)
            Row.SAMPLE -> stepSample(v, dx + 4 * dy)
            Row.ROOT -> session.editWsInstrument(slot, "Set root note") { it.copy(root = (it.root + dx + 12 * dy).coerceIn(WsRules.NOTES)) }
            Row.RATE -> session.editWsInstrument(slot, "Set DMA rate") {
                it.copy(rate = (it.rate + d).coerceIn(0, org.thortracker.backend.WsPlayerImage.MAX_DMA_RATE))
            }
            Row.SAMPLE_LOOP -> session.editWsInstrument(slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.LOOP_START -> {
                val length = session.song.ws.samples.getOrNull(sound(session.song, st)?.sample ?: -1)?.frames ?: 1
                session.editWsInstrument(slot, "Set loop start") {
                    it.copy(loopStart = (it.loopStart + dx + loopCoarse(length) * dy).coerceIn(0, length - 1))
                }
            }
            Row.BACKWARDS -> if (d == 0) false else session.editWsInstrument(slot, "Set backwards") { it.copy(backwards = !it.backwards) }
            Row.SAMPLES -> { st.dpcmSample = (st.dpcmSample + d).coerceIn(0, maxOf(0, session.song.ws.samples.size - 1)); false }
            Row.SAMPLE_NAME -> session.editWsSample(st.dpcmSample, "Rename sample") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, WsSample.MAX_NAME))
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
            changed = session.editWsEnvelope(slot, kind, "Set $name") {
                WsEnvelopes.withValue(kind, it, st.envStep, WsEnvelopes.valueAt(it, st.envStep, fallback) + dy, range)
            }
        }
        if (dx != 0) {
            // Drawing sideways carries the value under the cursor to the next step, appending past the end.
            val value = WsEnvelopes.valueAt(e, st.envStep, fallback)
            val next = (st.envStep + dx).coerceIn(0, WsEnvelope.MAX_LENGTH - 1)
            if (next == st.envStep) return changed
            st.envStep = next
            changed = session.editWsEnvelope(slot, kind, "Draw $name") { WsEnvelopes.withValue(kind, it, next, value, range) } || changed
            envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(WsEnvelopes.lastStep(it)) }
        }
        return changed
    }

    /** Tone → noise → sample (wrapping): the slot's sound becomes that kind; Listen moves to a lane where it plays. */
    private fun stepKind(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val session = v.session
        val slot = st.instrument
        val kinds = WsKind.entries
        val cur = sound(session.song, st)
        // An empty slot shows a tone sound's pages: stepping starts from tone.
        val next = kinds[(kinds.indexOf(cur?.kind ?: WsKind.TONE) + delta).mod(kinds.size)]
        val ok = session.setWsKind(slot, next)
        if (!ok) return false
        st.listenChannel = listenChannel(session.song, st, sound(session.song, st))
        keepPage(v, Row.KIND)
        session.note("Sound ${EditorSession.hex2(slot)} is now " + when (next) {
            WsKind.TONE -> "a tone sound: it plays its wave on CH1-CH4 (CH3 adds its sweep)"
            WsKind.NOISE -> "a noise sound: it plays channel 4's LFSR, on CH4 only"
            WsKind.SAMPLE -> if (session.song.ws.samples.isEmpty()) "a sample sound: the song has no samples yet, Add on the Sample page"
                else "a sample sound: CH2's voice plays it" + if (session.song.ws.color) ", and Hyper Voice" else ""
        } + if (cur != null) "; its other settings are kept" else "")
        return true
    }

    /** CPU ↔ Sound DMA (the WonderSwan Color): the pages follow, root and rate are both kept. */
    private fun toggleFeed(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val ins = sound(session.song, st) ?: return false
        val next = if (ins.isDma) WsFeed.CPU else WsFeed.DMA
        val ok = session.setWsFeed(st.instrument, next)
        if (!ok) {
            if (next == WsFeed.DMA && !session.song.ws.color) session.note("Sound DMA exists only on the WonderSwan Color")
            return false
        }
        keepPage(v, Row.FEED)
        session.note(if (next == WsFeed.DMA) "Sound DMA: the sample streams at a fixed rate, the note only triggers it"
            else "CPU-fed: one frame per voice-timer interrupt on the root note, other notes faster or slower")
        return true
    }

    /** The preset the sound is (by name and settings, its wave and sample compared by values), or -1. */
    fun presetIndex(song: Song, ins: WsInstrument?): Int {
        if (ins == null) return -1
        return WsPresets.presets(song.ws.color).indexOfFirst { p ->
            (ins.name == p.instrument.name || ins.name == p.name.take(NAME_MAX)) &&
                p.instrument.copy(name = ins.name, wave = ins.wave, sample = ins.sample) == ins &&
                (p.wave == null || song.ws.waves.getOrNull(ins.wave) == p.wave) &&
                (p.sample == null || song.ws.samples.getOrNull(ins.sample)?.data == p.sample.data)
        }
    }

    /** Presets [delta] steps from the one the sound is (or the first), replacing the sound. */
    private fun stepPreset(v: ViewController, delta: Int): Boolean {
        val presets = WsPresets.presets(v.session.song.ws.color)
        if (presets.isEmpty() || delta == 0) return false
        val i = FmControls.presetStep(presetIndex(v.session.song, sound(v.session.song, v.state)), delta, presets.size)
        return usePreset(v, presets[i])
    }

    /** Replaces the sound by [preset]; Listen moves to a lane where it plays. */
    private fun usePreset(v: ViewController, preset: WsPresets.Preset): Boolean {
        val st = v.state
        val ok = v.session.applyWsPreset(st.instrument, preset)
        val ins = sound(v.session.song, st)
        st.listenChannel = listenChannel(v.session.song, st, ins)
        keepPage(v, Row.PRESET)
        if (!ok && preset.sample != null && v.session.song.ws.samples.size >= WsSound.MAX_SAMPLES) {
            v.session.note("${preset.name}: the song has ${WsSound.MAX_SAMPLES} samples, no room for its sample")
            return false
        }
        v.session.note("${preset.name}: " + when (preset.category) {
            WsPresets.Category.TONE -> "tone on wave ${EditorSession.hex2(ins?.wave ?: 0)}" +
                (if (preset.wave != null && v.session.song.ws.waves.getOrNull(ins?.wave ?: -1) != preset.wave) " (the song has no room for its own wave)" else "") +
                if (preset.instrument.sweep != 0) "; its sweep sounds on CH3" else ""
            WsPresets.Category.NOISE -> "noise, CH4"
            WsPresets.Category.SAMPLE -> "sample ${EditorSession.hex2(ins?.sample ?: 0)}" + if (preset.instrument.isDma) " by Sound DMA" else ", CPU-fed"
        })
        return ok
    }

    /** The presets popup: every preset of the model by category, the cursor on the sound's own. */
    private fun openPresets(v: ViewController) {
        val presets = WsPresets.presets(v.session.song.ws.color)
        val current = presetIndex(v.session.song, sound(v.session.song, v.state))
        v.session.requestMenu { menu ->
            menu.menu("Preset for sound ${EditorSession.hex2(v.state.instrument)}", presets.map { p ->
                "${p.name} · ${when (p.category) {
                    WsPresets.Category.TONE -> "tone"
                    WsPresets.Category.NOISE -> "noise (CH4)"
                    WsPresets.Category.SAMPLE -> if (p.instrument.isDma) "sample by DMA" else "sample, CPU-fed"
                }}" to { usePreset(v, p); Unit }
            }, selected = maxOf(0, current))
        }
    }

    /** Steps a tone sound's wave through the song's waves. */
    private fun stepWave(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val waves = v.session.song.ws.waves.size
        if (waves == 0) { v.session.note("The song has no waves: the Wave view's New tool makes one"); return false }
        val ok = v.session.editWsInstrument(st.instrument, "Set wave") { it.copy(wave = (it.wave + delta).coerceIn(0, waves - 1)) }
        if (ok) publish(v)
        return ok
    }

    /** Steps a sample sound's sample through the song's samples; the list follows. */
    private fun stepSample(v: ViewController, delta: Int): Boolean {
        val st = v.state
        val session = v.session
        val samples = session.song.ws.samples
        if (samples.isEmpty()) { session.note("The song has no samples yet: add some on the Add row"); return false }
        val ok = session.editWsInstrument(st.instrument, "Set sample") { ins ->
            val next = (ins.sample + delta).coerceIn(0, samples.size - 1)
            ins.copy(sample = next, loopStart = ins.loopStart.coerceIn(0, samples[next].frames - 1))
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
                if (ins == null) session.setWsInstrument(slot, defaultWsInstrument(slot), "New sound").also {
                    session.note("Sound ${EditorSession.hex2(slot)}: a new tone sound to start from")
                }
                else { publish(v); session.note("Sound ${EditorSession.hex2(slot)} selected for entry"); false }
            }
            Row.KIND -> if (ins == null) session.setWsInstrument(slot, defaultWsInstrument(slot), "New sound") else stepKind(v, 1)
            Row.FEED -> toggleFeed(v)
            Row.PRESET -> { openPresets(v); false }
            Row.WAVE -> {
                if (ins == null) session.setWsInstrument(slot, defaultWsInstrument(slot), "New sound")
                else {
                    publish(v)
                    session.select(session.selection.copy(context = ViewKind.WAVE))
                    session.note("Wave ${EditorSession.hex2(ins.wave)}: a following Wave view shows it")
                    false
                }
            }
            Row.LFSR_RESET -> session.editWsInstrument(slot, "Set LFSR reset") { it.copy(lfsrReset = !it.lfsrReset) }
            Row.MODE -> session.editWsInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.SAMPLE_LOOP -> session.editWsInstrument(slot, "Set sample loop") { it.copy(loop = !it.loop) }
            Row.BACKWARDS -> session.editWsInstrument(slot, "Set backwards") { it.copy(backwards = !it.backwards) }
            Row.STEPS -> {
                val kind = envelopeKind(session.song, st) ?: return
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    val fallback = fallback(ins, kind)
                    session.editWsEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        WsEnvelopes.withValue(kind, it, st.envStep, WsEnvelopes.valueAt(it, st.envStep, fallback), range(session.song, kind))
                    }.also { envelope(session.song, st)?.let { st.envStep = WsEnvelopes.lastStep(it) } }
                } else false
            }
            Row.SAMPLES -> useListedSample(v)
            Row.ADD -> { WsSampleMenu.open(session, slot); false }
            else -> if (ins == null && row in SOUND_ROWS) session.setWsInstrument(slot, defaultWsInstrument(slot), "New sound") else false
        }
        if (changed && v.auditionHeld) audition(v)
    }

    /** The sound plays the sample selected in the list. */
    private fun useListedSample(v: ViewController): Boolean {
        val st = v.state
        val session = v.session
        val sample = session.song.ws.samples.getOrNull(st.dpcmSample) ?: run { session.note("The song has no samples yet: add some on the Add row"); return false }
        if (sound(session.song, st)?.sample == st.dpcmSample) { session.note("Sound ${EditorSession.hex2(st.instrument)} already plays ${sample.name}"); return false }
        val ok = session.editWsInstrument(st.instrument, "Set sample") { it.copy(sample = st.dpcmSample, loopStart = it.loopStart.coerceIn(0, sample.frames - 1)) }
        session.note("Sound ${EditorSession.hex2(st.instrument)} plays ${sample.name}")
        return ok
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val song = session.song
        val slot = st.instrument
        val ins = sound(song, st)
        val blank = defaultWsInstrument(slot, ins?.kind ?: WsKind.TONE).let { if (ins?.isDma == true) it.copy(feed = WsFeed.DMA) else it }
        when (row(song, st)) {
            Row.SLOT -> session.setWsInstrument(slot, null, "Empty sound").also { session.note("Sound ${EditorSession.hex2(slot)} is empty: notes naming it are refused") }
            Row.NAME -> if (ins != null) session.setWsInstrument(slot, ins.copy(name = ViewController.deleteChar(ins.name, st.textPos)), "Rename sound")
            Row.KIND -> if (ins != null && ins.kind != WsKind.TONE) { session.setWsKind(slot, WsKind.TONE); keepPage(v, Row.KIND) }
            Row.FEED -> if (ins?.isDma == true) { session.setWsFeed(slot, WsFeed.CPU); keepPage(v, Row.FEED) }
            Row.PRESET -> { session.setWsInstrument(slot, blank, "Reset sound"); keepPage(v, Row.PRESET) }
            Row.WAVE -> session.editWsInstrument(slot, "Set wave") { it.copy(wave = 0) }
            Row.SWEEP -> session.editWsInstrument(slot, "Set sweep") { it.copy(sweep = 0) }
            Row.SWEEP_TIME -> session.editWsInstrument(slot, "Set sweep time") { it.copy(sweepTime = 0) }
            Row.TAP -> session.editWsInstrument(slot, "Set tap mode") { it.copy(tap = 0) }
            Row.LFSR_RESET -> session.editWsInstrument(slot, "Set LFSR reset") { it.copy(lfsrReset = false) }
            Row.LENGTH -> envelopeKind(song, st)?.let { k -> session.editWsEnvelope(slot, k, "Reset ${k.label.lowercase()} envelope") { k.of(blank) } }
            Row.LOOP -> envelopeKind(song, st)?.let { k -> session.editWsEnvelope(slot, k, "Clear ${k.label.lowercase()} loop") { it.copy(loop = -1) } }
            Row.RELEASE -> envelopeKind(song, st)?.let { k -> session.editWsEnvelope(slot, k, "Clear ${k.label.lowercase()} release") { it.copy(release = -1) } }
            Row.MODE -> session.editWsInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> envelopeKind(song, st)?.let { k ->
                session.editWsEnvelope(slot, k, "Delete ${k.label.lowercase()} step") { WsEnvelopes.removed(k, it, st.envStep, range(song, k)) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(WsEnvelopes.lastStep(it)) }
            }
            Row.SAMPLE -> session.editWsInstrument(slot, "Set sample") { it.copy(sample = 0, loopStart = 0) }
            Row.ROOT -> session.editWsInstrument(slot, "Set root note") { it.copy(root = WsInstrument.ROOT) }
            Row.RATE -> session.editWsInstrument(slot, "Set DMA rate") { it.copy(rate = WsInstrument.DEFAULT_RATE) }
            Row.SAMPLE_LOOP -> session.editWsInstrument(slot, "Set sample loop") { it.copy(loop = false) }
            Row.LOOP_START -> session.editWsInstrument(slot, "Set loop start") { it.copy(loopStart = 0) }
            Row.BACKWARDS -> session.editWsInstrument(slot, "Set backwards") { it.copy(backwards = false) }
            Row.SAMPLES -> {
                val name = song.ws.samples.getOrNull(st.dpcmSample)?.name ?: return
                val users = session.deleteWsSample(st.dpcmSample)
                normalise(v)
                session.note("Deleted sample $name" + if (users > 0) "; the $users sound${if (users == 1) "" else "s"} that played it now play the sample before it" else "")
            }
            Row.SAMPLE_NAME -> session.editWsSample(st.dpcmSample, "Rename sample") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.LISTEN, Row.PAGE, Row.ADD -> Unit
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val ins = sound(song, st)
        if (ins == null) { v.session.note("Sound ${EditorSession.hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot makes one"); return }
        if (ins.kind == WsKind.SAMPLE && ins.sample !in song.ws.samples.indices) {
            v.session.note("Sound ${EditorSession.hex2(st.instrument)} has no sample to play: add one on the Sample page"); return
        }
        val ch = listenChannel(song, st, ins)
        WsValidator.laneProblem(song.ws, ins, ch)?.let { v.session.note("${ins.name.ifEmpty { "This sound" }} $it"); return }
        if (ch != st.listenChannel) {
            v.session.note("${ins.name.ifEmpty { "This sound" }} cannot play on ${song.system.channels[st.listenChannel].name}: listening on ${song.system.channels[ch].name}")
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
        val onEnvelope = kind != null && row !in HEAD_ROWS
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyWsEnvelope(slot, kind!!) else session.copyWsInstrument(slot)
            Action.PASTE -> { session.pasteWs(slot, kind); keepPage(v, row) }
            Action.CUT -> if (onEnvelope) {
                session.copyWsEnvelope(slot, kind!!)
                session.editWsEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(defaultWsInstrument(slot)) }
            } else {
                session.copyWsInstrument(slot)
                session.setWsInstrument(slot, null, "Cut sound")
                keepPage(v, row)
            }
            Action.DUPLICATE -> {
                if (slot >= WsRules.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(song, st) ?: return true
                session.setWsInstrument(slot + 1, src.copy(name = (src.name + "+").take(NAME_MAX)), "Duplicate sound")
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
            Row.KIND -> { h += Hint(v.editLr(), "Tone / noise / sample"); h += Hint(v.k(Action.CLEAR), "Tone") }
            Row.FEED -> { h += Hint(v.k(Action.EDIT), "CPU / Sound DMA"); h += Hint(v.k(Action.CLEAR), "CPU") }
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.EDIT), "Presets list"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            Row.WAVE -> { h += Hint(v.editLr(), "Wave"); h += Hint(v.k(Action.EDIT), "Show in the Wave view"); h += Hint(v.k(Action.CLEAR), "Wave 00") }
            Row.SWEEP -> { h += Hint(v.editLr(), "Sweep ±1 (${v.ud()} ±16)"); h += Hint(v.k(Action.CLEAR), "No sweep") }
            Row.SWEEP_TIME -> { h += Hint(v.editLr(), "Sweep time"); h += Hint(v.k(Action.CLEAR), "0") }
            Row.TAP -> { h += Hint(v.editLr(), "Tap mode 0-7"); h += Hint(v.k(Action.CLEAR), "Mode 0") }
            Row.LFSR_RESET -> { h += Hint(v.k(Action.EDIT), "Reset on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
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
            Row.RATE -> { h += Hint(v.editLr(), "4000 / 6000 / 12000 / 24000 Hz"); h += Hint(v.k(Action.CLEAR), "6000 Hz") }
            Row.SAMPLE_LOOP -> { h += Hint(v.k(Action.EDIT), "Loop on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.LOOP_START -> { h += Hint(v.editLr(), "Frame ±1 (${v.ud()} coarse)"); h += Hint(v.k(Action.CLEAR), "Frame 0") }
            Row.BACKWARDS -> { h += Hint(v.k(Action.EDIT), "Backwards on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.SAMPLES -> { h += Hint(v.lr(), "Sample"); h += Hint(v.k(Action.EDIT), "Play it in this sound"); h += Hint(v.k(Action.CLEAR), "Delete sample") }
            Row.ADD -> h += Hint(v.k(Action.EDIT), "Starter kit or .wav file")
        }
        if (row !in LR_ROWS) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(listenChannel(song, st, ins))?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeKind(song, st) != null && row !in HEAD_ROWS) "Copy/paste envelope" else "Copy/paste sound")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys], for the [color] model. */
    fun helpSections(keys: Keymap, color: Boolean): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        val editLr = keys.held(Action.EDIT, lr)
        val editUd = keys.held(Action.EDIT, ud)
        return listOf(
            "Instruments" to listOfNotNull(
                ud to "Sound slot, name, Listen lane, kind, ${if (color) "feed (samples), " else ""}preset, the kind's settings, page, then the page's settings",
                lr to "On settings: the sound slot; on envelope steps: the step; on the sample list: the sample",
                channels to "Pages. Tone: Volume, Arpeggio, Pitch, Wave seq. Noise: Volume, Arpeggio, Pitch, Tap seq. Sample: Sample, Volume, " +
                    "Arpeggio, Pitch${if (color) " (Sound DMA: Sample, Volume)" else ""}",
                "Listen on" to "The lane $listen plays the sound on; a sound plays only where it fits (noise on CH4, mono samples on CH2" +
                    (if (color) ", any sample on Hyper Voice" else "") + "), else on the first lane it fits. Holding $edit on a setting sounds " +
                    "the sound and every change restarts it",
                "Kind" to "Tone: a 32-step, 4-bit wave at the note's pitch (CH1-CH4). Noise: channel 4's LFSR (CH4). Sample: 8-bit " +
                    "frames on CH2's voice output${if (color) " or Hyper Voice" else ""}. Switching keeps the other kinds' settings",
                if (color) "Feed" to "CPU: the player writes one frame per voice-timer interrupt, pitched from the root note. Sound DMA: the " +
                    "Color's DMA streams the sample at a fixed rate, the note only triggers it; one DMA sample sounds at a time" else null,
                "Preset" to "$editLr steps through the starting sounds (tones, CH3 sweeps, noise, starter-kit samples); $edit opens the list",
                "Wave" to "The wave a tone sound plays ($editLr); $edit shows it in a following Wave view, where it is drawn",
                "Sweep" to "CH3 only: added to the 11-bit frequency register every (sweep time + 1) / 375 s, wrapping; 0 = no sweep. " +
                    "Negative values fall, positive rise",
                "Tap mode" to "Channel 4's LFSR taps: mode 0 is white noise (32767 steps), 1-7 shorter sequences (1953 to 28 steps) that buzz " +
                    "at a pitch. LFSR reset restarts the sequence at each note, so every hit sounds the same",
                editLr to "Change the value ($editUd in bigger steps)",
                "$clear tap" to "The setting's default; on Slot: empty the slot (notes naming it are refused)",
                "$edit on Slot" to "An empty slot gets a new tone sound to start from",
            ),
            "Envelopes" to listOf(
                editUd to "On a step: raise / lower its value; past the last step it adds one",
                editLr to "On a step: carry its value to the next step (draws flat runs, adds steps at the end)",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
                "Volume" to "Lane volume 0-15 per tick, times Cxx: 4 bits per side on tones and noise; CH2's voice has off, 50 % (1-11) and " +
                    "100 % (12-15) per side" + if (color) "; Hyper Voice 12.5 % (1-2), 25 % (3-5), 50 % (6-11), 100 % (12-15)" else "",
                "Arpeggio" to envelopeSummary(WsEnvelopeKind.ARPEGGIO, WsKind.TONE),
                "Pitch" to envelopeSummary(WsEnvelopeKind.PITCH, WsKind.TONE),
                "Wave seq" to envelopeSummary(WsEnvelopeKind.WAVE, WsKind.TONE),
                "Tap seq" to envelopeSummary(WsEnvelopeKind.TAP, WsKind.NOISE),
                "Loop" to "The steps from the loop point to the end (or to the release point) repeat while the note holds; without one the last value holds",
                "Release" to "With a release point on the volume envelope OFF releases the note: every envelope with a release point " +
                    "jumps to it and plays out. Without one OFF stops the note",
            ),
            "Samples" to listOfNotNull(
                "Sample" to "The song's sample this sound plays ($editLr): unsigned 8-bit frames" + if (color) ", stereo ones on Hyper Voice only" else "",
                "Root note" to "CPU-fed: the note that plays one frame per voice-timer interrupt (12000 / T Hz, Song view or Txx); other notes " +
                    "play it faster or slower",
                if (color) "DMA rate" to "Sound DMA: 4000, 6000, 12000 or 24000 bytes per second whatever the note; Backwards plays it from the end" else null,
                "Loop" to "Repeat (CPU-fed: from the loop start frame; DMA: the whole sample) until the next note or OFF; off, it plays once",
                "Samples" to "$lr: choose; $edit: this sound plays it; $clear deletes it (sounds that played it take the sample before it)",
                "Add" to "$edit: the starter kit (kick, snare, hat, tom; made in code, free to use; one sound each in the next empty slots) " +
                    "or a .wav file, converted on the device at the rate the sound plays it" + if (color) " (stereo kept)" else " (mixed to mono)",
                "Space" to "Samples share the player's data with the patterns and envelopes" +
                    (if (color) "; a DMA-fed sample also stores a signed copy for Hyper Voice" else "") + "; Help → Check shows what is used",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = WsInstrumentPainter(pane)
}
