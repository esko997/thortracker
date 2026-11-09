package org.thortracker.system.scc

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
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
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The SCC / SCC+ instrument editor, controller-only: the one bank of [SccSound.SLOTS] sounds (empty slots are made by
 * Edit on Slot or by choosing a preset), the selected sound's name, its preset, the channel Listen plays on, the
 * restart switch, and four envelopes edited step by step like the NES's (volume 0-15 with loop and release, arpeggio
 * and pitch -64..63 with the pitch mode, and the wave numbers 1-64 the note plays per tick, with loop).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]), [ViewState.envelope] (the page: an
 * [SccEnvelopeKind] ordinal), [ViewState.envStep] (0..size; size is the append position) and
 * [ViewState.listenChannel].
 */
object SccInstrumentView : SystemView() {
    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), PRESET("Preset"), LISTEN("Listen on"), RESTART("Restart"), PAGE("Page"),
        LENGTH("Length"), LOOP("Loop"), RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.PRESET, Row.LISTEN, Row.RESTART, Row.PAGE)
    private val ROWS = SccEnvelopeKind.entries.associateWith { kind ->
        HEAD + listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
            .filter { (it != Row.RELEASE || kind.canRelease) && (it != Row.MODE || kind == SccEnvelopeKind.PITCH) }
    }

    /** Rows about the shown envelope: copy, cut and paste work on the envelope there, elsewhere on the whole sound. */
    private val ENVELOPE_ROWS = setOf(Row.PAGE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)

    /** Slots, lengths, loop and release points move by this much with the coarse direction while editing. */
    const val COARSE = 8

    fun kind(state: ViewState): SccEnvelopeKind = SccEnvelopeKind.entries[state.envelope.coerceIn(0, SccEnvelopeKind.entries.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(kind(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The shown sound, or null for an empty slot. */
    fun sound(song: Song, state: ViewState): SccInstrument? = song.sccInstrument(state.instrument)

    /** What the editor shows and the first edit starts from: the sound, or for an empty slot [defaultSccInstrument]. */
    fun shown(song: Song, state: ViewState): SccInstrument = sound(song, state) ?: defaultSccInstrument(state.instrument)

    /** The shown envelope (of [shown]). */
    fun envelope(song: Song, state: ViewState): NesEnvelope = kind(state).of(shown(song, state))

    /** The furthest step the cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    fun variant(song: Song): SccVariant = song.scc.variant

    /** True when [channel] (0-based) shares its wave RAM with another channel: SCC 4 and 5 on the SCC (K051649). */
    fun sharesWave(song: Song, channel: Int): Boolean = variant(song).sharedWave && channel in SccSound.SHARED_CHANNELS

    /** What Listen's channel does with the sound beyond playing it, or null: the SCC's shared wave RAM. */
    fun channelNote(song: Song, channel: Int): String? =
        if (sharesWave(song, channel)) "SCC 4 and SCC 5 share one wave RAM: while both sound, both play the wave claimed last" else null

    /** One line per envelope: what the player does with it. */
    fun envelopeSummary(kind: SccEnvelopeKind): String = when (kind) {
        SccEnvelopeKind.VOLUME -> "Level 0-15 per tick, scaled by the channel volume (Cxx); empty = 15 throughout"
        SccEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick (-64..+63); empty = none"
        SccEnvelopeKind.PITCH -> "Sixteenths of a semitone per tick (±4 semitones); relative: added up every tick, for sweeps"
        SccEnvelopeKind.WAVE -> "The wave (1-64) the note plays per tick: one step = a fixed wave, several = a timbre that changes"
    }

    /** A step value as the editor writes it: offsets signed, waves by number. */
    fun valueText(kind: SccEnvelopeKind, value: Int): String = when (kind) {
        SccEnvelopeKind.VOLUME -> value.toString()
        SccEnvelopeKind.ARPEGGIO, SccEnvelopeKind.PITCH -> if (value > 0) "+$value" else value.toString()
        SccEnvelopeKind.WAVE -> hex2(value)
    }

    /** Index in [SccPresets.ALL] of the preset [ins] is (compared without the name, so a renamed preset still reads as it), or -1. */
    fun presetIndex(ins: SccInstrument?): Int =
        if (ins == null) -1 else SccPresets.ALL.indexOfFirst { it.instrument.copy(name = "") == ins.copy(name = "") }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, SccSound.SLOTS)
        st.envelope = st.envelope.coerceIn(0, SccEnvelopeKind.entries.size - 1)
        st.instField = st.instField.coerceIn(0, rows(st).size - 1)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envStep = st.envStep.coerceIn(0, lastStep(envelope(song, st)))
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, SccSound.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /**
     * Publishes the shown sound for entry, and its wave for a following Wave view: on the Wave page the wave under the
     * cursor, else the sound's first wave.
     */
    fun publish(v: ViewController) {
        v.syncLock()
        val st = v.state
        val session = v.session
        val waves = SccEnvelopeKind.WAVE.of(shown(session.song, st)).values
        val wave = (if (kind(st) == SccEnvelopeKind.WAVE) waves.getOrNull(st.envStep) ?: waves.lastOrNull() else waves.firstOrNull())
            ?.let { it - 1 } ?: session.selection.wave
        session.select(session.selection.copy(bank = 0, instrument = st.instrument, wave = wave, context = ViewKind.WAVE))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(SccEnvelopeKind.entries.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.LOOP)
        st.envStep = st.envStep.coerceIn(0, lastStep(envelope(v.session.song, st)))
    }

    /** Moves to another slot; Listen moves to the channel that slot plays on most. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, SccSound.SLOTS)
        if (slot == st.instrument) return
        st.instrument = slot
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        st.envStep = st.envStep.coerceIn(0, lastStep(envelope(v.session.song, st)))
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val song = v.session.song
        val rows = rows(st)
        val row = row(st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> { v.useClear(); stepTo(v, st.envStep + COARSE * dx) }
            dy != 0 -> { st.instField = (st.instField - dy).coerceIn(0, rows.size - 1); st.textPos = 0 }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(song.system.channels.size)
            row == Row.PAGE -> showPage(v, st.envelope + dx)
            row == Row.STEPS -> stepTo(v, st.envStep + dx)
            else -> moveSlot(v, dx)
        }
        if (row == Row.LISTEN && dx != 0 && !v.editHeld) channelNote(song, st.listenChannel)?.let { v.session.note(it) }
    }

    /** Moves the step cursor; on the Wave page a following Wave view shows the wave under it. */
    private fun stepTo(v: ViewController, step: Int) {
        val st = v.state
        st.envStep = step.coerceIn(0, lastStep(envelope(v.session.song, st)))
        if (kind(st) == SccEnvelopeKind.WAVE) publish(v)
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        when (row(st)) {
            Row.SLOT -> moveSlot(v, dx - COARSE * dy)
            Row.NAME -> session.editSccInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            }
            Row.PRESET -> applyPreset(v, dx - dy)
            Row.LISTEN -> {
                st.listenChannel = (st.listenChannel + dx + dy).mod(session.song.system.channels.size)
                channelNote(session.song, st.listenChannel)?.let { session.note(it) }
            }
            Row.RESTART -> if (dx != 0 || dy != 0) toggleRestart(v)
            Row.PAGE -> showPage(v, st.envelope + dx + dy)
            Row.LENGTH -> session.editSccEnvelope(slot, kind, "Set $name length") {
                SccEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy)
            }
            Row.LOOP -> session.editSccEnvelope(slot, kind, "Set $name loop") { SccEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy) }
            Row.RELEASE -> session.editSccEnvelope(slot, kind, "Set $name release") {
                SccEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
            }
            Row.MODE -> if (dx != 0 || dy != 0) toggleMode(v)
            Row.STEPS -> {
                val e = envelope(session.song, st)
                if (dy != 0) {
                    // Up raises the value; at the append position the new step starts from the last value.
                    session.editSccEnvelope(slot, kind, "Set $name") {
                        SccEnvelopes.withValue(kind, it, st.envStep, SccEnvelopes.valueAt(kind, it, st.envStep) + dy)
                    }
                }
                if (dx != 0) {
                    // Drawing sideways carries the value under the cursor to the next step, appending past the end.
                    val value = SccEnvelopes.valueAt(kind, e, st.envStep)
                    val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
                    if (next != st.envStep) {
                        st.envStep = next
                        session.editSccEnvelope(slot, kind, "Draw $name") { SccEnvelopes.withValue(kind, it, next, value) }
                    }
                }
                st.envStep = st.envStep.coerceAtMost(lastStep(envelope(session.song, st)))
                if (kind == SccEnvelopeKind.WAVE) {
                    publish(v)
                    waveNote(v)
                }
            }
        }
    }

    /** "Step 3: wave 05 Saw · SCC 4/5 share one wave RAM" after a wave step edit. */
    private fun waveNote(v: ViewController) {
        val st = v.state
        val song = v.session.song
        val number = envelope(song, st).values.getOrNull(st.envStep) ?: return
        val wave = song.scc.wave(number)
        v.session.note("Step ${st.envStep}: wave ${hex2(number)}" + (wave?.name?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: "") +
            if (sharesWave(song, st.listenChannel)) " · on ${song.system.channels[st.listenChannel].name} it shares the wave RAM with the other of SCC 4/5" else "")
    }

    private fun toggleRestart(v: ViewController) {
        v.session.editSccInstrument(v.state.instrument, "Set restart") { it.copy(restart = !it.restart) }
    }

    private fun toggleMode(v: ViewController) {
        v.session.editSccInstrument(v.state.instrument, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
    }

    /** The preset [delta] steps from the one the sound is (or the first / last), replacing the sound; Listen hears it. */
    private fun applyPreset(v: ViewController, delta: Int): Boolean {
        if (delta == 0) return false
        val st = v.state
        val presets = SccPresets.ALL
        val current = presetIndex(sound(v.session.song, st))
        val preset = presets[FmControls.presetStep(current, delta, presets.size)]
        val ok = v.session.setSccInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        val waves = preset.instrument.waves.sorted().joinToString(" ") { hex2(it) }
        v.session.note("${preset.name}: plays wave${if (preset.instrument.waves.size == 1) "" else "s"} $waves of this song")
        return ok
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (!tap) return
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        when (row(st)) {
            Row.SLOT -> if (empty) {
                session.setSccInstrument(slot, defaultSccInstrument(slot), "New sound")
                session.note("Sound ${hex2(slot)}: a new sound to start from (Preset picks another)")
                publish(v)
            } else {
                publish(v)
                session.note("Sound ${hex2(slot)} selected for entry")
            }
            Row.PRESET -> applyPreset(v, 1)
            Row.RESTART -> toggleRestart(v)
            Row.MODE -> toggleMode(v)
            Row.STEPS -> {
                val e = envelope(session.song, st)
                if (st.envStep == e.values.size) {
                    session.editSccEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        SccEnvelopes.withValue(kind, it, st.envStep, SccEnvelopes.valueAt(kind, it, st.envStep))
                    }
                    st.envStep = lastStep(envelope(session.song, st))
                } else if (empty) {
                    session.setSccInstrument(slot, defaultSccInstrument(slot), "New sound")
                }
            }
            Row.NAME, Row.LENGTH, Row.LOOP, Row.RELEASE -> if (empty) session.setSccInstrument(slot, defaultSccInstrument(slot), "New sound")
            Row.LISTEN, Row.PAGE -> Unit
        }
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        when (row(st)) {
            Row.SLOT -> if (session.setSccInstrument(slot, null, "Empty sound")) {
                session.note("Sound ${hex2(slot)} is empty: notes naming it are refused")
            }
            Row.NAME -> session.editSccInstrument(slot, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.PRESET -> session.setSccInstrument(slot, defaultSccInstrument(slot), "Reset sound")
            Row.LISTEN, Row.PAGE -> Unit
            Row.RESTART -> session.editSccInstrument(slot, "Set restart") { it.copy(restart = false) }
            Row.LENGTH -> session.editSccEnvelope(slot, kind, "Reset $name envelope") { kind.of(SccInstrument()) }
            Row.LOOP -> session.editSccEnvelope(slot, kind, "Clear $name loop") { it.copy(loop = -1) }
            Row.RELEASE -> session.editSccEnvelope(slot, kind, "Clear $name release") { it.copy(release = -1) }
            Row.MODE -> session.editSccInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> {
                val e = envelope(session.song, st)
                if (st.envStep in e.values.indices && e.values.size <= kind.minLength) {
                    session.note("A sound plays at least one wave: change this step's wave instead (${v.editUd()})")
                    return
                }
                session.editSccEnvelope(slot, kind, "Delete $name step") { SccEnvelopes.removed(kind, it, st.envStep) }
                st.envStep = st.envStep.coerceAtMost(lastStep(envelope(session.song, st)))
            }
        }
    }

    override fun audition(v: ViewController) {
        val st = v.state
        if (sound(v.session.song, st) == null) {
            v.session.note("Sound ${hex2(st.instrument)} is empty: ${v.k(Action.EDIT)} on Slot or a preset makes one")
            return
        }
        v.startAudition(st.listenChannel, v.defaultNote(), st.instrument)
    }

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val onEnvelope = row(st) in ENVELOPE_ROWS
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copySccEnvelope(slot, kind) else session.copySccInstrument(slot)
            Action.PASTE -> session.pasteSccSound(slot, kind)
            Action.CUT -> if (onEnvelope) {
                if (sound(session.song, st) != null) {
                    session.copySccEnvelope(slot, kind)
                    session.editSccEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(SccInstrument()) }
                }
            } else {
                session.copySccInstrument(slot)
                session.setSccInstrument(slot, null, "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= SccSound.SLOTS) { session.note("Sound ${hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: run { session.note("Sound ${hex2(slot)} is empty"); return true }
                session.setSccInstrument(slot + 1, src.copy(name = (src.name + "+").take(EditorSession.MAX_NAME)), "Duplicate sound")
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
        val row = row(st)
        val empty = sound(song, st) == null
        val name = kind(st).label.lowercase()
        when (row) {
            Row.SLOT -> {
                h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots")
                h += Hint(v.k(Action.EDIT), if (empty) "Make a sound here" else "Use for entry")
                h += Hint(v.k(Action.CLEAR), "Empty the slot")
            }
            Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            Row.PRESET -> { h += Hint(v.editLr(), "Start from a preset"); h += Hint(v.k(Action.CLEAR), "Default sound") }
            Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            Row.RESTART -> { h += Hint(v.k(Action.EDIT), "Restart the wave at each note on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Reset $name envelope") }
            Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            Row.RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            Row.MODE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            Row.STEPS -> {
                h += Hint(v.lr(), "Step")
                h += Hint(v.editUd(), if (kind(st) == SccEnvelopeKind.WAVE) "Wave number" else "Value")
                h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
        }
        if (row == Row.PRESET || row == Row.RESTART || row == Row.LENGTH || row == Row.LOOP || row == Row.RELEASE || row == Row.MODE) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (row in ENVELOPE_ROWS) "Copy/paste envelope" else "Copy/paste sound")
    }

    /**
     * Help rows for the SCC's Instrument and Wave views (heading, then key → meaning), named with the current [keys];
     * the same for the SCC and the SCC+, so the wave RAM difference is spelled out.
     */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val up = keys.label(Action.UP)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, preset, Listen channel, restart, page, length, loop, release (volume), mode (pitch), then the steps",
                lr to "On settings: the sound slot; on the steps: move along them",
                channels to "Pages: volume, arpeggio, pitch and wave envelopes",
                "$edit on Slot" to "An empty slot gets a new sound (the first preset) to start from; $clear empties the slot (notes naming it are refused)",
                "Preset" to "${keys.held(Action.EDIT, lr)} replaces the sound with a starting sound; presets play the starter waves 01 onwards of the song, " +
                    "so edited starter waves change them too",
                "Listen on" to "The channel $listen plays the sound on ($lr there); choosing a slot moves it to the channel that sound plays on most",
                "Restart" to "On: every note starts its wave at sample 0 (the chip's test-register bit 5 around the note's period write), so attacks " +
                    "sound the same; off: the wave runs on from wherever the channel's counter is",
                keys.held(Action.EDIT, ud) to "On a step: raise / lower its value; past the last step it adds one",
                keys.held(Action.EDIT, lr) to "On a step: carry its value to the next step (draws flat runs, adds steps at the end); on settings: change them",
                "$edit tap" to "Past the last step: add a step; on Restart and Mode: switch them",
                "$clear tap" to "Delete the step (the wave envelope keeps at least one); on settings: reset that setting",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
            ),
            "Envelopes" to SccEnvelopeKind.entries.map { it.label to envelopeSummary(it) } + listOf(
                "Loop" to "The steps from the loop point to the end (or to the release point) repeat while the note holds; without one the last value holds",
                "Release" to "Volume only: until OFF the note plays up to the step before the release point (looping if there is a loop, else holding " +
                    "that value), then the steps from it play out. Without one, OFF stops the note at once",
                "Mode" to "Pitch only: absolute, each value is the offset from the note; relative, each value is added to the pitch every tick (sweeps)",
                "Wave steps" to "Each step names one of the song's 64 waves; the Wave view draws them. Wxx in a pattern plays a wave instead of the envelope",
            ),
            "Waves" to listOf(
                channels to "The wave: 01-40 (64 waves of 32 signed samples, -128..127)",
                keys.dpad() to "Move the pen: $lr one sample, $ud the level by 8; $up at the top reaches the tools, above them the name",
                "$clear hold+$lr" to "Move the pen 8 samples; $clear hold+$ud: the level by 1",
                "$edit hold" to "Draw at the pen height; ${keys.held(Action.EDIT, lr)} draws along, ${keys.held(Action.EDIT, ud)} fine-tunes the level by 1",
                "$clear tap" to "On the grid: the sample to 0",
                "Tools" to "Sine, triangle, saw, square, pulse (${keys.held(Action.EDIT, lr)}: its width), noise (${keys.held(Action.EDIT, lr)}: another), " +
                    "invert, shift left / right, smooth, normalise (loudest without clipping), and Use in sound: the selected sound plays this wave",
                listen to "Plays the wave through a sound that uses it (the selected sound first)",
            ),
            "SCC and SCC+ wave RAM" to listOf(
                "SCC (K051649)" to "SCC 1-3 have a wave RAM each, but SCC 4 and SCC 5 share one: whatever it holds, both play. The player gives it " +
                    "to whichever of the two last started a note (or changed wave); while both sound with different waves, both play that one. " +
                    "The song check warns where they overlap, and the Pattern lane headers show which wave the shared RAM holds",
                "SCC+ (K052539)" to "Every channel has its own wave RAM: SCC 4 and SCC 5 play their own waves",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = SccInstrumentPainter(pane)
}
