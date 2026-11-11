package org.thortracker.system.saa

import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.EditorSession.Companion.hex2
import org.thortracker.editor.Hint
import org.thortracker.editor.NameEdit
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Song
import org.thortracker.system.SystemView
import org.thortracker.system.fm.FmControls
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The SAM Coupé / CMS instrument editor, controller-only: the one bank of [SaaSound.SLOTS] sounds (empty slots are made
 * by Edit on Slot or by choosing a preset), the selected sound's name, its preset, the lane Listen plays on, its pan
 * (left and right levels), noise clock and hardware envelope (on/off, then shape, resolution, inverted right, clock,
 * external rate and retrigger), and four envelopes edited step by step like the NES's (volume 0-15 with loop and
 * release, arpeggio and pitch -64..63 with the pitch mode, and the mixer 0-3 per tick, with loop).
 * State: [ViewState.instrument], [ViewState.instField] (a [Row] of [rows]: the hardware envelope's own rows only while
 * the sound has one), [ViewState.envelope] (the page: an [SaaEnvelopeKind] ordinal), [ViewState.envStep] (0..size;
 * size is the append position) and [ViewState.listenChannel].
 */
object SaaInstrumentView : SystemView() {
    enum class Row(val label: String) {
        SLOT("Slot"), NAME("Name"), PRESET("Preset"), LISTEN("Listen on"), PAN_LEFT("Pan L"), PAN_RIGHT("Pan R"),
        NOISE("Noise clock"), HARDWARE("Hardware envelope"), SHAPE("Shape"), RESOLUTION("Resolution"),
        INVERT("Inverted right"), CLOCK("Clock"), RATE("Rate"), RETRIGGER("Retrigger"), PAGE("Page"),
        LENGTH("Length"), LOOP("Loop"), RELEASE("Release"), MODE("Mode"), STEPS("Steps"),
    }

    private val HEAD = listOf(Row.SLOT, Row.NAME, Row.PRESET, Row.LISTEN, Row.PAN_LEFT, Row.PAN_RIGHT, Row.NOISE, Row.HARDWARE)

    /** The hardware envelope's rows, shown while the sound has one. */
    val ENVELOPE_SETTINGS = listOf(Row.SHAPE, Row.RESOLUTION, Row.INVERT, Row.CLOCK, Row.RATE, Row.RETRIGGER)

    private val PAGES = SaaEnvelopeKind.entries.associateWith { kind ->
        listOf(Row.PAGE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)
            .filter { (it != Row.RELEASE || kind.canRelease) && (it != Row.MODE || kind == SaaEnvelopeKind.PITCH) }
    }

    /** Rows about the shown envelope: copy, cut and paste work on the envelope there, elsewhere on the whole sound. */
    private val ENVELOPE_ROWS = setOf(Row.PAGE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.MODE, Row.STEPS)

    /** Slots, lengths, loop and release points, pan levels and rates move by this much with the coarse direction while editing. */
    const val COARSE = 8

    fun kind(state: ViewState): SaaEnvelopeKind = SaaEnvelopeKind.entries[state.envelope.coerceIn(0, SaaEnvelopeKind.entries.size - 1)]

    /** The rows of the shown sound and page. */
    fun rows(song: Song, state: ViewState): List<Row> {
        val settings = if (shown(song, state).envelope != null) HEAD + ENVELOPE_SETTINGS else HEAD
        return settings + PAGES.getValue(kind(state))
    }

    fun row(song: Song, state: ViewState): Row = rows(song, state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    /** The shown sound, or null for an empty slot. */
    fun sound(song: Song, state: ViewState): SaaInstrument? = song.saaInstrument(state.instrument)

    /** What the editor shows and the first edit starts from: the sound, or for an empty slot [defaultSaaInstrument]. */
    fun shown(song: Song, state: ViewState): SaaInstrument = sound(song, state) ?: defaultSaaInstrument(state.instrument)

    /** The shown envelope (of [shown]). */
    fun envelope(song: Song, state: ViewState): NesEnvelope = kind(state).of(shown(song, state))

    /** The furthest step the cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /**
     * What the Listen lane does differently with [ins], or null: a hardware envelope (or a mixer-off sound) on a lane
     * without one, an internal clock on lane 3 or 6 (Listen alone leaves lane 2's tone generator at the chip's
     * lowest tone), a noise clock 3 (twice lane 1's or 4's tone).
     */
    fun channelNote(song: Song, channel: Int, ins: SaaInstrument): String? {
        val variant = song.saa.variant
        if (channel !in 0 until variant.channels) return null
        val names = variant.laneNames
        val e = ins.envelope
        return when {
            e != null && !SaaSound.hasEnvelope(channel) ->
                "${names[channel]} has no hardware envelope (only lanes 3 and 6 do): the sound plays without it here"
            e != null && !e.external ->
                "The envelope steps with ${names[SaaSound.envelopeClockLane(channel)]}'s tone: Listen alone leaves it at the chip's lowest tone"
            ins.usesNoise && ins.noise == 3 && SaaSound.noiseClockLane(channel) != channel ->
                "Noise clock 3 follows ${names[SaaSound.noiseClockLane(channel)]}'s tone, not this lane's"
            else -> null
        }
    }

    /** Why the noise clock row does nothing for [ins], or null: its mixer never plays noise. */
    fun noiseNote(ins: SaaInstrument): String? =
        if (ins.usesNoise) null else "unused: the mixer envelope never plays noise"

    /** One line per envelope: what the player does with it. */
    fun envelopeSummary(kind: SaaEnvelopeKind): String = when (kind) {
        SaaEnvelopeKind.VOLUME -> "Level 0-15 per tick, scaled by the channel volume (Cxx) and the pan; empty = 15 throughout"
        SaaEnvelopeKind.ARPEGGIO -> "Semitones added to the note per tick (-64..+63); empty = none"
        SaaEnvelopeKind.PITCH -> "Sixteenths of a semitone per tick (±4 semitones); relative: added up every tick, for sweeps"
        SaaEnvelopeKind.MIXER -> "Per tick: 0 off, 1 tone, 2 noise, 3 tone + noise; empty = tone throughout (Mxx overrides it)"
    }

    /** A step value as the editor writes it: offsets signed, mixer values by name. */
    fun valueText(kind: SaaEnvelopeKind, value: Int): String = when (kind) {
        SaaEnvelopeKind.VOLUME -> value.toString()
        SaaEnvelopeKind.ARPEGGIO, SaaEnvelopeKind.PITCH -> if (value > 0) "+$value" else value.toString()
        SaaEnvelopeKind.MIXER -> when (value) { 0 -> "off"; 1 -> "T"; 2 -> "N"; else -> "T+N" }
    }

    /** Index in [SaaPresets.ALL] of the preset [ins] is (compared without the name, so a renamed preset still reads as it), or -1. */
    fun presetIndex(ins: SaaInstrument?): Int =
        if (ins == null) -1 else SaaPresets.ALL.indexOfFirst { it.instrument.copy(name = "") == ins.copy(name = "") }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        val song = v.session.song
        st.instrument = st.instrument.coerceIn(1, SaaSound.SLOTS)
        st.envelope = st.envelope.coerceIn(0, SaaEnvelopeKind.entries.size - 1)
        st.instField = st.instField.coerceIn(-1, if (SaaSoundFace.friendly(v)) SaaSoundControl.entries.size + 1 else rows(song, st).size - 1)
        SaaSoundFace.settle(v)
        st.listenChannel = st.listenChannel.coerceIn(0, song.system.channels.size - 1)
        st.envStep = st.envStep.coerceIn(0, lastStep(envelope(song, st)))
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        if (state.instrument != subject) state.soundPanel("saa").apply { invalidate(); editBlocked = true }
        state.instrument = subject.coerceIn(1, SaaSound.SLOTS)
    }

    override fun follow(state: ViewState, selection: Selection, song: Song) {
        if (state.instrument != selection.instrument || state.listenChannel != selection.channel)
            state.soundPanel("saa").apply { invalidate(); editBlocked = true }
        state.instrument = selection.instrument
        state.listenChannel = selection.channel
    }

    /** Publishes the shown sound for entry. */
    fun publish(v: ViewController) {
        v.syncLock()
        val session = v.session
        session.select(session.selection.copy(bank = 0, instrument = v.state.instrument))
    }

    /** Shows page [index] (wrapping), keeping the cursor on the same kind of row where the new page has it. */
    fun showPage(v: ViewController, index: Int) {
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
        st.envelope = index.mod(SaaEnvelopeKind.entries.size)
        val rows = rows(song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.LOOP)
        st.envStep = st.envStep.coerceIn(0, lastStep(envelope(song, st)))
    }

    /** Moves to another slot; Listen moves to the lane that slot plays on most. The cursor stays on its kind of row. */
    internal fun moveSlot(v: ViewController, delta: Int) {
        val st = v.state
        val slot = (st.instrument + delta).coerceIn(1, SaaSound.SLOTS)
        if (slot == st.instrument) return
        st.soundPanel("saa").invalidate()
        val row = row(v.session.song, st)
        st.instrument = slot
        val rows = rows(v.session.song, st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.HARDWARE)
        FmControls.mostUsedChannel(v.session.song, slot)?.let { st.listenChannel = it }
        st.envStep = st.envStep.coerceIn(0, lastStep(envelope(v.session.song, st)))
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        if (SaaSoundFace.direction(v, dx, dy)) return
        val st = v.state
        val song = v.session.song
        val rows = rows(song, st)
        val row = row(song, st)
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
        if (row == Row.LISTEN && dx != 0 && !v.editHeld) channelNote(song, st.listenChannel, shown(song, st))?.let { v.session.note(it) }
    }

    private fun stepTo(v: ViewController, step: Int) {
        val st = v.state
        st.envStep = step.coerceIn(0, lastStep(envelope(v.session.song, st)))
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        val any = dx != 0 || dy != 0
        when (row(session.song, st)) {
            Row.SLOT -> moveSlot(v, dx - COARSE * dy)
            Row.NAME -> session.editSaaInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            }
            Row.PRESET -> applyPreset(v, dx - dy)
            Row.LISTEN -> {
                st.listenChannel = (st.listenChannel + dx + dy).mod(session.song.system.channels.size)
                channelNote(session.song, st.listenChannel, shown(session.song, st))?.let { session.note(it) }
            }
            Row.PAN_LEFT -> session.editSaaInstrument(slot, "Set pan left") {
                it.copy(panLeft = (it.panLeft + dx + 4 * dy).coerceIn(SaaInstrument.PAN))
            }
            Row.PAN_RIGHT -> session.editSaaInstrument(slot, "Set pan right") {
                it.copy(panRight = (it.panRight + dx + 4 * dy).coerceIn(SaaInstrument.PAN))
            }
            Row.NOISE -> if (any) session.editSaaInstrument(slot, "Set noise clock") { it.copy(noise = (it.noise + dx + dy).mod(4)) }
            Row.HARDWARE -> if (any) toggleHardware(v)
            Row.SHAPE -> if (any) session.editSaaHardwareEnvelope(slot, "Set envelope shape") { SaaHardwareEnvelopes.withShape(it, dx + dy) }
            Row.RESOLUTION -> if (any) session.editSaaHardwareEnvelope(slot, "Set envelope resolution") { it.copy(threeBit = !it.threeBit) }
            Row.INVERT -> if (any) session.editSaaHardwareEnvelope(slot, "Set inverted right") { it.copy(invertRight = !it.invertRight) }
            Row.CLOCK -> if (any) session.editSaaHardwareEnvelope(slot, "Set envelope clock") { it.copy(external = !it.external) }
            Row.RATE -> session.editSaaHardwareEnvelope(slot, "Set envelope rate") { SaaHardwareEnvelopes.withRate(it, dx + COARSE * dy) }
            Row.RETRIGGER -> if (any) session.editSaaHardwareEnvelope(slot, "Set retrigger") { it.copy(retrigger = !it.retrigger) }
            Row.PAGE -> showPage(v, st.envelope + dx + dy)
            Row.LENGTH -> session.editSaaEnvelope(slot, kind, "Set $name length") {
                SaaEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy)
            }
            Row.LOOP -> session.editSaaEnvelope(slot, kind, "Set $name loop") { SaaEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy) }
            Row.RELEASE -> session.editSaaEnvelope(slot, kind, "Set $name release") {
                SaaEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
            }
            Row.MODE -> if (any) toggleMode(v)
            Row.STEPS -> {
                val e = envelope(session.song, st)
                if (dy != 0) {
                    // Up raises the value; at the append position the new step starts from the last value.
                    session.editSaaEnvelope(slot, kind, "Set $name") {
                        SaaEnvelopes.withValue(kind, it, st.envStep, SaaEnvelopes.valueAt(kind, it, st.envStep) + dy)
                    }
                }
                if (dx != 0) {
                    // Drawing sideways carries the value under the cursor to the next step, appending past the end.
                    val value = SaaEnvelopes.valueAt(kind, e, st.envStep)
                    val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
                    if (next != st.envStep) {
                        st.envStep = next
                        session.editSaaEnvelope(slot, kind, "Draw $name") { SaaEnvelopes.withValue(kind, it, next, value) }
                    }
                }
                st.envStep = st.envStep.coerceAtMost(lastStep(envelope(session.song, st)))
            }
        }
    }

    /** Switches the hardware envelope on (a repetitive decay on the internal clock) or off. */
    private fun toggleHardware(v: ViewController) {
        val st = v.state
        val session = v.session
        val on = shown(session.song, st).envelope != null
        if (on) session.editSaaInstrument(st.instrument, "Hardware envelope off") { it.copy(envelope = null) }
        else session.editSaaInstrument(st.instrument, "Hardware envelope on") { it.copy(envelope = SaaHardwareEnvelope()) }
        if (!on) channelNote(session.song, st.listenChannel, shown(session.song, st))?.let { session.note(it) }
        st.instField = st.instField.coerceIn(0, rows(session.song, st).size - 1)
    }

    private fun toggleMode(v: ViewController) {
        v.session.editSaaInstrument(v.state.instrument, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
    }

    /** The preset [delta] steps from the one the sound is (or the first / last), replacing the sound. */
    private fun applyPreset(v: ViewController, delta: Int): Boolean {
        if (delta == 0) return false
        val st = v.state
        val presets = SaaPresets.ALL
        val current = presetIndex(sound(v.session.song, st))
        val preset = presets[FmControls.presetStep(current, delta, presets.size)]
        val ok = v.session.setSaaInstrument(st.instrument, preset.instrument, "Preset ${preset.name}")
        st.instField = st.instField.coerceIn(0, rows(v.session.song, st).size - 1)
        v.session.note(channelNote(v.session.song, st.listenChannel, preset.instrument)?.let { "${preset.name}: $it" } ?: preset.name)
        return ok
    }

    override fun editPress(v: ViewController) {
        SaaSoundFace.panel(v).editBlocked = false
        if (SaaSoundFace.friendly(v) && v.state.instField >= 2 && sound(v.session.song, v.state) != null) audition(v)
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        val panel = SaaSoundFace.panel(v)
        if (panel.editBlocked) { panel.editBlocked = false; if (!v.auditionHeld) v.session.releaseAudition(); return }
        if (v.state.instField < 0) { if (tap) SaaSoundFace.selectMode(v, !panel.advanced, true); return }
        if (SaaSoundFace.friendly(v)) { if (!v.auditionHeld) v.session.releaseAudition(); return }
        if (!tap) return
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val empty = sound(session.song, st) == null
        when (row(session.song, st)) {
            Row.SLOT -> if (empty) {
                session.setSaaInstrument(slot, defaultSaaInstrument(slot), "New sound")
                session.note("Sound ${hex2(slot)}: a new sound to start from (Preset picks another)")
                publish(v)
            } else {
                publish(v)
                session.note("Sound ${hex2(slot)} selected for entry")
            }
            Row.PRESET -> applyPreset(v, 1)
            Row.NOISE -> session.editSaaInstrument(slot, "Set noise clock") { it.copy(noise = (it.noise + 1).mod(4)) }
            Row.HARDWARE -> toggleHardware(v)
            Row.SHAPE -> session.editSaaHardwareEnvelope(slot, "Set envelope shape") { SaaHardwareEnvelopes.withShape(it, 1) }
            Row.RESOLUTION -> session.editSaaHardwareEnvelope(slot, "Set envelope resolution") { it.copy(threeBit = !it.threeBit) }
            Row.INVERT -> session.editSaaHardwareEnvelope(slot, "Set inverted right") { it.copy(invertRight = !it.invertRight) }
            Row.CLOCK -> session.editSaaHardwareEnvelope(slot, "Set envelope clock") { it.copy(external = !it.external) }
            Row.RETRIGGER -> session.editSaaHardwareEnvelope(slot, "Set retrigger") { it.copy(retrigger = !it.retrigger) }
            Row.MODE -> toggleMode(v)
            Row.STEPS -> {
                val e = envelope(session.song, st)
                if (st.envStep == e.values.size) {
                    session.editSaaEnvelope(slot, kind, "Add ${kind.label.lowercase()} step") {
                        SaaEnvelopes.withValue(kind, it, st.envStep, SaaEnvelopes.valueAt(kind, it, st.envStep))
                    }
                    st.envStep = lastStep(envelope(session.song, st))
                } else if (empty) {
                    session.setSaaInstrument(slot, defaultSaaInstrument(slot), "New sound")
                }
            }
            Row.NAME, Row.PAN_LEFT, Row.PAN_RIGHT, Row.RATE, Row.LENGTH, Row.LOOP, Row.RELEASE ->
                if (empty) session.setSaaInstrument(slot, defaultSaaInstrument(slot), "New sound")
            Row.LISTEN, Row.PAGE -> Unit
        }
    }

    override fun clear(v: ViewController) {
        if (v.state.instField < 0 || SaaSoundFace.panel(v).editBlocked && v.editHeld) return
        if (SaaSoundFace.friendly(v) && v.state.instField != 1) return
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        val defaults = SaaHardwareEnvelope()
        when (row(session.song, st)) {
            Row.SLOT -> if (session.setSaaInstrument(slot, null, "Empty sound")) {
                session.note("Sound ${hex2(slot)} is empty: notes naming it are refused")
            }
            Row.NAME -> session.editSaaInstrument(slot, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.PRESET -> session.setSaaInstrument(slot, defaultSaaInstrument(slot), "Reset sound")
            Row.LISTEN, Row.PAGE -> Unit
            Row.PAN_LEFT -> session.editSaaInstrument(slot, "Set pan left") { it.copy(panLeft = SaaInstrument.PAN.last) }
            Row.PAN_RIGHT -> session.editSaaInstrument(slot, "Set pan right") { it.copy(panRight = SaaInstrument.PAN.last) }
            Row.NOISE -> session.editSaaInstrument(slot, "Set noise clock") { it.copy(noise = 0) }
            Row.HARDWARE -> {
                session.editSaaInstrument(slot, "Hardware envelope off") { it.copy(envelope = null) }
                st.instField = st.instField.coerceIn(0, rows(session.song, st).size - 1)
            }
            Row.SHAPE -> session.editSaaHardwareEnvelope(slot, "Set envelope shape") { it.copy(shape = defaults.shape) }
            Row.RESOLUTION -> session.editSaaHardwareEnvelope(slot, "Set envelope resolution") { it.copy(threeBit = defaults.threeBit) }
            Row.INVERT -> session.editSaaHardwareEnvelope(slot, "Set inverted right") { it.copy(invertRight = defaults.invertRight) }
            Row.CLOCK -> session.editSaaHardwareEnvelope(slot, "Set envelope clock") { it.copy(external = defaults.external) }
            Row.RATE -> session.editSaaHardwareEnvelope(slot, "Set envelope rate") { it.copy(rate = defaults.rate) }
            Row.RETRIGGER -> session.editSaaHardwareEnvelope(slot, "Set retrigger") { it.copy(retrigger = defaults.retrigger) }
            Row.LENGTH -> session.editSaaEnvelope(slot, kind, "Reset $name envelope") { kind.of(SaaInstrument()) }
            Row.LOOP -> session.editSaaEnvelope(slot, kind, "Clear $name loop") { it.copy(loop = -1) }
            Row.RELEASE -> session.editSaaEnvelope(slot, kind, "Clear $name release") { it.copy(release = -1) }
            Row.MODE -> session.editSaaInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> {
                session.editSaaEnvelope(slot, kind, "Delete $name step") { SaaEnvelopes.removed(kind, it, st.envStep) }
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
        if (SaaSoundFace.command(v, a)) return true
        if (a == Action.PASTE || a == Action.CUT || a == Action.DUPLICATE) SaaSoundFace.panel(v).invalidate()
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val onEnvelope = row(session.song, st) in ENVELOPE_ROWS
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showPage(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copySaaEnvelope(slot, kind) else session.copySaaInstrument(slot)
            Action.PASTE -> session.pasteSaaSound(slot, kind)
            Action.CUT -> if (onEnvelope) {
                if (sound(session.song, st) != null) {
                    session.copySaaEnvelope(slot, kind)
                    session.editSaaEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(SaaInstrument()) }
                }
            } else {
                session.copySaaInstrument(slot)
                session.setSaaInstrument(slot, null, "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= SaaSound.SLOTS) { session.note("Sound ${hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: run { session.note("Sound ${hex2(slot)} is empty"); return true }
                session.setSaaInstrument(slot + 1, src.copy(name = (src.name + "+").take(EditorSession.MAX_NAME)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        st.instField = st.instField.coerceIn(0, rows(session.song, st).size - 1)
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        if (SaaSoundFace.friendly(v) || v.state.instField == -1) { SaaSoundFace.hints(v, h); return }
        val st = v.state
        val song = v.session.song
        val row = row(song, st)
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
            Row.LISTEN -> h += Hint(v.lr(), "Lane Listen plays on")
            Row.PAN_LEFT, Row.PAN_RIGHT -> { h += Hint(v.editLr(), "Level ±1 (${v.ud()} ±4)"); h += Hint(v.k(Action.CLEAR), "Full (15)") }
            Row.NOISE -> { h += Hint(v.editLr(), "Noise clock"); h += Hint(v.k(Action.CLEAR), "Fastest (0)") }
            Row.HARDWARE -> { h += Hint(v.k(Action.EDIT), "Envelope on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.SHAPE -> { h += Hint(v.editLr(), "Shape"); h += Hint(v.k(Action.CLEAR), "Repetitive decay") }
            Row.RESOLUTION -> { h += Hint(v.k(Action.EDIT), "4-bit / 3-bit"); h += Hint(v.k(Action.CLEAR), "4-bit") }
            Row.INVERT -> { h += Hint(v.k(Action.EDIT), "Right inverted on / off"); h += Hint(v.k(Action.CLEAR), "Off") }
            Row.CLOCK -> { h += Hint(v.k(Action.EDIT), "Internal / external clock"); h += Hint(v.k(Action.CLEAR), "Internal") }
            Row.RATE -> { h += Hint(v.editLr(), "Ticks per step ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "1") }
            Row.RETRIGGER -> { h += Hint(v.k(Action.EDIT), "Restart at each note on / off"); h += Hint(v.k(Action.CLEAR), "On") }
            Row.PAGE -> h += Hint(v.lr(), "Page")
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Reset $name envelope") }
            Row.LOOP -> { h += Hint(v.editLr(), "Loop start"); h += Hint(v.k(Action.CLEAR), "No loop") }
            Row.RELEASE -> { h += Hint(v.editLr(), "Release step"); h += Hint(v.k(Action.CLEAR), "No release") }
            Row.MODE -> h += Hint(v.k(Action.EDIT), "Relative / absolute")
            Row.STEPS -> {
                h += Hint(v.lr(), "Step")
                h += Hint(v.editUd(), "Value")
                h += Hint(v.editLr(), "Draw value along")
                h += Hint(v.k(Action.CLEAR), "Delete step")
            }
        }
        if (row != Row.SLOT && row != Row.NAME && row != Row.LISTEN && row != Row.PAGE && row != Row.STEPS) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Page")
        h += Hint(v.k(Action.AUDITION), "Listen on ${song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${song.system.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (row in ENVELOPE_ROWS) "Copy/paste envelope" else "Copy/paste sound")
    }

    /** Help rows for the SAA1099's Instrument view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val listen = keys.label(Action.AUDITION)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Sound face" to listOf(
                ud to "Choose an instrument slot; $lr selects mode, name or a musical control",
                keys.held(Action.EDIT, lr) to "Shape the selected control; on Name edit a letter, with $ud move the caret",
                channels to "Choose the Listen lane (not an instrument edit)",
                "Stereo stage" to "Source replaces only the mixer program; Contour only the volume program; Position only the two amplitudes. Custom stays exact until a recipe is chosen.",
                "Grain and Motion" to "Grain claims the triple's shared noise clock: latest noise note wins. Motion explicitly chooses an external-clock envelope on lanes 3/6 of each chip; other lanes keep it unchanged. Internal clocks and custom hardware shapes remain in Advanced.",
                "Advanced" to "The original precise editor, presets and every envelope remain available on the same exact patch.",
            ),
            "Instruments" to listOf(
                ud to "Sound slot, name, preset, Listen lane, pan, noise clock, hardware envelope (and its settings while on), " +
                    "page, length, loop, release (volume), mode (pitch), then the steps",
                lr to "On settings: the sound slot; on the steps: move along them",
                channels to "Pages: volume, arpeggio, pitch and mixer envelopes",
                "$edit on Slot" to "An empty slot gets a new sound (the first preset) to start from; $clear empties the slot (notes naming it are refused)",
                "Preset" to "${keys.held(Action.EDIT, lr)} replaces the sound with a starting sound: leads, basses, a stereo pad, drums, " +
                    "a noise sweep and three hardware-envelope sounds for lanes 3 and 6",
                "Listen on" to "The lane $listen plays the sound on ($lr there); choosing a slot moves it to the lane that sound plays on most",
                "Pan L, Pan R" to "The lane's left and right levels (0-15; the chip's amplitude controllers); 8xy replaces them for one note",
                "Noise clock" to "The clock a note playing noise claims for its lane triple's noise generator: 0 fastest (31.3 kHz on the " +
                    "SAM Coupé), 1 half, 2 a quarter, 3 twice lane 1's or 4's tone. Unused while the mixer envelope plays no noise",
                "Hardware envelope" to "$edit switches the chip's envelope on or off. Only lanes 3 and 6 of a chip have one; elsewhere the " +
                    "sound plays without it",
                "Shape" to "Zero, maximum, single or repetitive decay, triangle and attack; drawn beside it (right side dashed when inverted)",
                "Resolution, Inverted right" to "16 or 8 levels; the right output plays the left's inverse (an automatic pan)",
                "Clock, Rate" to "Internal: two steps per period of lane 2's (5's) tone, so that lane's note sets the envelope's speed (a " +
                    "repetitive decay sounds 3 octaves below it); external: one step every Rate ticks of the player",
                "Retrigger" to "On: every note restarts the envelope; off: it runs on across notes",
                keys.held(Action.EDIT, ud) to "On a step: raise / lower its value; past the last step it adds one",
                keys.held(Action.EDIT, lr) to "On a step: carry its value to the next step (draws flat runs, adds steps at the end); on settings: change them",
                "$edit tap" to "Past the last step: add a step; on switches: flip them",
                "$clear tap" to "Delete the step; on settings: reset that setting",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
            ),
            "Envelopes" to SaaEnvelopeKind.entries.map { it.label to envelopeSummary(it) } + listOf(
                "Loop" to "The steps from the loop point to the end (or to the release point) repeat while the note holds; without one the last value holds",
                "Release" to "Volume only: until OFF the note plays up to the step before the release point (looping if there is a loop, else holding " +
                    "that value), then the steps from it play out. Without one, OFF stops the note at once",
                "Mode" to "Pitch only: absolute, each value is the offset from the note; relative, each value is added to the pitch every tick (sweeps)",
                "Mixer" to "Tone + noise plays the tone gated by the noise. With the mixer off on lane 3 or 6, a hardware envelope " +
                    "becomes the waveform itself (the buzz bass)",
            ),
            "SAA1099 shared noise and envelopes" to listOf(
                "Noise generators" to "Each chip has two: one for lanes 1-3, one for lanes 4-6. A note that starts noise (or changes its " +
                    "clock with Nxx or its mixer) sets the generator's clock for all three lanes; the latest claim wins. Give lanes of one " +
                    "triple the same clock when they sound noise together; the song check warns where they differ",
                "Clock 3" to "Noise at twice lane 1's (4's) tone frequency, whatever that lane plays (its tone generator runs on after a note ends)",
                "Hardware envelopes" to "Lanes 3 and 6 of each chip only. Their internal clock is lane 2's (5's) tone generator: play notes " +
                    "there to set the envelope's speed (at volume 0 if you do not want to hear them)",
                "Pattern lane headers" to "N0:31k marks the lane that last set a noise generator and its clock (T1 / T4 = clock 3); " +
                    "E←2 / E←5 an envelope clocked by lane 2 / 5, E ext the external clock; SYNC / OFF a chip held by Y02 / Y03",
                "Yxx" to "Per chip: Y01 restarts every tone and noise generator in phase (lanes playing one note then sound as one), " +
                    "Y02 holds them silent, Y03 turns the sound off, Y00 back to normal",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = SaaSoundPainter(pane)
}
