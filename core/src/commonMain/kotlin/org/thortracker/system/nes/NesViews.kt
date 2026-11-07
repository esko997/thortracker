package org.thortracker.system.nes

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
import org.thortracker.model.Songs
import org.thortracker.system.ChannelKind
import org.thortracker.system.SystemView
import org.thortracker.ui.PaneView
import org.thortracker.ui.Painter

/**
 * The NES instrument editor: the one shared bank, the selected sound's name, the channel Listen plays on, and its
 * four envelopes edited step by step with the controller. State: [ViewState.instrument], [ViewState.instField] (a
 * [Row] of [rows]), [ViewState.envelope] ([NesEnvelopeKind] ordinal), [ViewState.envStep] (0..size; size is the
 * append position) and [ViewState.listenChannel].
 */
object NesInstrumentView : SystemView() {
    enum class Row { SLOT, NAME, LISTEN, ENVELOPE, LENGTH, LOOP, RELEASE, MODE, STEPS }

    private val ROWS = NesEnvelopeKind.entries.associateWith { kind ->
        Row.entries.filter { (it != Row.RELEASE || kind.canRelease) && (it != Row.MODE || kind == NesEnvelopeKind.PITCH) }
    }

    /** Envelope rows jump by this much with the coarse ([Action.UP]/[Action.DOWN]) direction while editing. */
    private const val COARSE = 8

    fun kind(state: ViewState): NesEnvelopeKind = NesEnvelopeKind.entries[state.envelope.coerceIn(0, NesEnvelopeKind.entries.size - 1)]

    fun rows(state: ViewState): List<Row> = ROWS.getValue(kind(state))

    fun row(state: ViewState): Row = rows(state).let { it[state.instField.coerceIn(0, it.size - 1)] }

    fun sound(song: Song, state: ViewState): NesInstrument? = song.nesInstrument(state.instrument)

    fun envelope(song: Song, state: ViewState): NesEnvelope? = sound(song, state)?.let { kind(state).of(it) }

    /** The furthest step the cursor reaches: the append position after the last step, while there is room. */
    fun lastStep(e: NesEnvelope): Int = minOf(e.values.size, NesEnvelope.MAX_LENGTH - 1)

    /** Why [channel] ignores (or treats specially) envelope [kind], or null when it plays it as written. */
    fun channelNote(kind: NesEnvelopeKind, channel: ChannelKind): String? = when (kind) {
        NesEnvelopeKind.VOLUME -> if (channel == ChannelKind.TRIANGLE) "Triangle has no volume: 0 silences it, any other value plays full" else null
        NesEnvelopeKind.DUTY -> when (channel) {
            ChannelKind.TRIANGLE -> "Triangle ignores duty"
            ChannelKind.NOISE -> "On Noise, duty is the mode: 0 long noise, 1 short (metallic)"
            else -> null
        }
        NesEnvelopeKind.ARPEGGIO -> if (channel == ChannelKind.NOISE) "On Noise, arpeggio steps the noise rate" else null
        NesEnvelopeKind.PITCH -> if (channel == ChannelKind.NOISE) "On Noise, pitch moves the noise rate" else null
    }

    /** One line per envelope: what each channel does with it. */
    fun envelopeSummary(kind: NesEnvelopeKind): String = when (kind) {
        NesEnvelopeKind.VOLUME -> "Pulse and Noise: level 0-15 per frame. Triangle: no volume, 0 silences it"
        NesEnvelopeKind.ARPEGGIO -> "Semitones added to the note per frame. Noise: steps the noise rate"
        NesEnvelopeKind.PITCH -> "Period offset per frame (relative: added up each frame). Noise: moves the rate"
        NesEnvelopeKind.DUTY -> "Pulse: 12.5, 25, 50, 75%. Noise: 0 long, 1 short noise. Triangle: ignored"
    }

    /** A step value as the editor writes it: duty as a percentage (or noise mode), offsets signed. */
    fun valueText(kind: NesEnvelopeKind, value: Int, channel: ChannelKind?): String = when (kind) {
        NesEnvelopeKind.VOLUME -> value.toString()
        NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH -> if (value > 0) "+$value" else value.toString()
        NesEnvelopeKind.DUTY -> if (channel == ChannelKind.NOISE) (if (value and 1 == 0) "long" else "short")
            else when (value) { 0 -> "12.5%"; 1 -> "25%"; 2 -> "50%"; else -> "75%" }
    }

    override val gestureLabel = "Edit sound"

    override fun normalise(v: ViewController) {
        val st = v.state
        st.instrument = st.instrument.coerceIn(1, NesSystem.SLOTS)
        st.envelope = st.envelope.coerceIn(0, NesEnvelopeKind.entries.size - 1)
        st.instField = st.instField.coerceIn(0, rows(st).size - 1)
        st.listenChannel = st.listenChannel.coerceIn(0, v.session.song.system.channels.size - 1)
        val e = envelope(v.session.song, st)
        st.envStep = st.envStep.coerceIn(0, if (e == null) 0 else lastStep(e))
    }

    override fun subject(state: ViewState): Int = state.instrument

    override fun applyLock(state: ViewState, subject: Int) {
        state.instrument = subject.coerceIn(1, NesSystem.SLOTS)
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

    /** Shows envelope [index] (wrapping), keeping the cursor on the same kind of row where the new envelope has it. */
    fun showEnvelope(v: ViewController, index: Int) {
        val st = v.state
        val row = row(st)
        st.envelope = index.mod(NesEnvelopeKind.entries.size)
        val rows = rows(st)
        st.instField = rows.indexOf(row).takeIf { it >= 0 } ?: rows.indexOf(Row.LOOP)
        envelope(v.session.song, st)?.let { st.envStep = st.envStep.coerceIn(0, lastStep(it)) }
    }

    private fun moveSlot(v: ViewController, delta: Int) {
        v.state.instrument = (v.state.instrument + delta).coerceIn(1, NesSystem.SLOTS)
        publish(v)
    }

    override fun direction(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val rows = rows(st)
        val row = row(st)
        val e = envelope(v.session.song, st)
        when {
            v.editHeld -> { v.useEdit(); adjust(v, dx, dy) }
            row == Row.STEPS && v.clearHeld -> if (e != null) st.envStep = (st.envStep + COARSE * dx).coerceIn(0, lastStep(e))
            dy != 0 -> { st.instField = (st.instField - dy).coerceIn(0, rows.size - 1); st.textPos = 0 }
            row == Row.NAME -> st.textPos = (st.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            row == Row.LISTEN -> st.listenChannel = (st.listenChannel + dx).mod(v.session.song.system.channels.size)
            row == Row.ENVELOPE -> showEnvelope(v, st.envelope + dx)
            row == Row.STEPS -> if (e != null) st.envStep = (st.envStep + dx).coerceIn(0, lastStep(e))
            else -> moveSlot(v, dx)
        }
    }

    private fun adjust(v: ViewController, dx: Int, dy: Int) {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        when (row(st)) {
            Row.SLOT -> moveSlot(v, dx - COARSE * dy)
            Row.NAME -> session.editNesInstrument(slot, "Rename sound") {
                it.copy(name = NameEdit.edit(it.name, st.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            }
            Row.LISTEN -> st.listenChannel = (st.listenChannel + dx + dy).mod(session.song.system.channels.size)
            Row.ENVELOPE -> showEnvelope(v, st.envelope + dx + dy)
            Row.LENGTH -> session.editNesEnvelope(slot, kind, "Set $name length") {
                NesEnvelopes.withLength(kind, it, it.values.size + dx + COARSE * dy)
            }
            Row.LOOP -> session.editNesEnvelope(slot, kind, "Set $name loop") {
                NesEnvelopes.withLoop(kind, it, it.loop + dx + COARSE * dy)
            }
            Row.RELEASE -> session.editNesEnvelope(slot, kind, "Set $name release") {
                NesEnvelopes.withRelease(kind, it, it.release + dx + COARSE * dy)
            }
            Row.MODE -> session.editNesInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.STEPS -> {
                val e = envelope(session.song, st) ?: return
                if (dy != 0) {
                    // Up raises the value; at the append position the new step starts from the last value.
                    session.editNesEnvelope(slot, kind, "Set $name") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep) + dy)
                    }
                }
                if (dx != 0) {
                    // Drawing sideways carries the value under the cursor to the next step, appending past the end.
                    val value = NesEnvelopes.valueAt(kind, e, st.envStep)
                    val next = (st.envStep + dx).coerceIn(0, NesEnvelope.MAX_LENGTH - 1)
                    if (next == st.envStep) return
                    st.envStep = next
                    session.editNesEnvelope(slot, kind, "Draw $name") { NesEnvelopes.withValue(kind, it, next, value) }
                    envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
                }
            }
        }
    }

    override fun editRelease(v: ViewController, tap: Boolean) {
        if (!tap) return
        val st = v.state
        val session = v.session
        val kind = kind(st)
        when (row(st)) {
            Row.SLOT -> { publish(v); session.note("Sound ${EditorSession.hex2(st.instrument)} selected for entry") }
            Row.MODE -> session.editNesInstrument(st.instrument, "Set pitch mode") { it.copy(pitchRelative = !it.pitchRelative) }
            Row.STEPS -> {
                val e = envelope(session.song, st) ?: return
                if (st.envStep == e.values.size) {
                    session.editNesEnvelope(st.instrument, kind, "Add ${kind.label.lowercase()} step") {
                        NesEnvelopes.withValue(kind, it, st.envStep, NesEnvelopes.valueAt(kind, it, st.envStep))
                    }
                    envelope(session.song, st)?.let { st.envStep = lastStep(it) }
                }
            }
            else -> Unit
        }
    }

    override fun clear(v: ViewController) {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val name = kind.label.lowercase()
        val blank = kind.of(defaultNesInstrument(slot))
        when (row(st)) {
            Row.SLOT -> session.setNesInstrument(slot, defaultNesInstrument(slot), "Reset sound")
            Row.NAME -> session.editNesInstrument(slot, "Rename sound") { it.copy(name = ViewController.deleteChar(it.name, st.textPos)) }
            Row.LISTEN, Row.ENVELOPE -> Unit
            Row.LENGTH -> session.editNesEnvelope(slot, kind, "Reset $name envelope") { blank }
            Row.LOOP -> session.editNesEnvelope(slot, kind, "Clear $name loop") { it.copy(loop = -1) }
            Row.RELEASE -> session.editNesEnvelope(slot, kind, "Clear $name release") { it.copy(release = -1) }
            Row.MODE -> session.editNesInstrument(slot, "Set pitch mode") { it.copy(pitchRelative = false) }
            Row.STEPS -> {
                session.editNesEnvelope(slot, kind, "Delete $name step") { NesEnvelopes.removed(kind, it, st.envStep) }
                envelope(session.song, st)?.let { st.envStep = st.envStep.coerceAtMost(lastStep(it)) }
            }
        }
    }

    override fun audition(v: ViewController) {
        v.startAudition(v.state.listenChannel, v.defaultNote(), v.state.instrument)
    }

    /** Rows about the whole sound copy, cut and paste the sound; envelope rows the shown envelope. */
    private fun envelopeRow(row: Row) = row != Row.SLOT && row != Row.NAME && row != Row.LISTEN

    override fun command(v: ViewController, a: Action): Boolean {
        val st = v.state
        val session = v.session
        val kind = kind(st)
        val slot = st.instrument
        val onEnvelope = envelopeRow(row(st))
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> showEnvelope(v, st.envelope + if (a == Action.NEXT_CHANNEL) 1 else -1)
            Action.COPY -> if (onEnvelope) session.copyNesEnvelope(slot, kind) else session.copyNesInstrument(slot)
            Action.PASTE -> session.pasteNes(slot, kind)
            Action.CUT -> if (onEnvelope) {
                session.copyNesEnvelope(slot, kind)
                session.editNesEnvelope(slot, kind, "Cut ${kind.label.lowercase()} envelope") { kind.of(defaultNesInstrument(slot)) }
            } else {
                session.copyNesInstrument(slot)
                session.setNesInstrument(slot, defaultNesInstrument(slot), "Cut sound")
            }
            Action.DUPLICATE -> {
                if (slot >= NesSystem.SLOTS) { session.note("Sound ${EditorSession.hex2(slot)} is the last slot"); return true }
                val src = sound(session.song, st) ?: return true
                session.setNesInstrument(slot + 1, src.copy(name = (src.name + "+").take(EditorSession.MAX_NAME)), "Duplicate sound")
                st.instrument++
                publish(v)
            }
            else -> return false
        }
        return true
    }

    override fun hints(v: ViewController, h: MutableList<Hint>) {
        val st = v.state
        val kind = kind(st)
        val name = kind.label.lowercase()
        val row = row(st)
        when (row) {
            Row.SLOT -> { h += Hint(v.lr(), "Sound slot"); h += Hint(v.editUd(), "$COARSE slots"); h += Hint(v.k(Action.CLEAR), "Reset sound") }
            Row.NAME -> { h += Hint(v.lr(), "Letter position"); h += Hint(v.editLr(), "Change letter"); h += Hint(v.k(Action.CLEAR), "Delete letter") }
            Row.LISTEN -> h += Hint(v.lr(), "Channel Listen plays on")
            Row.ENVELOPE -> h += Hint(v.lr(), "Envelope")
            Row.LENGTH -> { h += Hint(v.editLr(), "Steps ±1 (${v.ud()} ±$COARSE)"); h += Hint(v.k(Action.CLEAR), "Empty $name envelope") }
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
        if (row == Row.LENGTH || row == Row.LOOP || row == Row.RELEASE || row == Row.MODE) h += Hint(v.lr(), "Sound slot")
        h += Hint(v.channels(), "Envelope")
        h += Hint(v.k(Action.AUDITION), "Listen on ${v.session.song.system.channels.getOrNull(st.listenChannel)?.name ?: ""}")
        h += Hint(v.octaves(), "Listen at ${Songs.noteName(v.defaultNote())}")
        h += Hint(v.copyPaste(), if (envelopeRow(row)) "Copy/paste envelope" else "Copy/paste sound")
    }

    /** Help rows for this view (heading, then key → meaning), named with the current [keys]. */
    fun helpSections(keys: Keymap): List<Pair<String, List<Pair<String, String>>>> {
        val edit = keys.label(Action.EDIT)
        val clear = keys.label(Action.CLEAR)
        val lr = keys.pair(Action.LEFT, Action.RIGHT)
        val ud = keys.pair(Action.UP, Action.DOWN)
        val channels = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL)
        return listOf(
            "Instruments" to listOf(
                ud to "Sound slot, name, Listen channel, envelope, length, loop, release (volume), mode (pitch), then the steps",
                lr to "On settings: the sound slot; on the steps: move along them",
                channels to "Volume, arpeggio, pitch or duty envelope",
                keys.held(Action.EDIT, ud) to "On a step: raise / lower its value; past the last step it adds one",
                keys.held(Action.EDIT, lr) to "On a step: carry its value to the next step (draws flat runs, adds steps at the end); on settings: change them",
                "$edit tap" to "Past the last step: add a step; on Mode: relative / absolute pitch",
                "$clear tap" to "Delete the step; on settings: reset that setting",
                "$clear hold+$lr" to "Move along the steps by $COARSE",
            ),
            "Envelopes" to NesEnvelopeKind.entries.map { it.label to envelopeSummary(it) } + listOf(
                "Loop" to "The steps from the loop point to the end repeat while the note holds; without one the last value holds",
                "Release" to "Volume only, and only with a loop: the loop runs before the release point until OFF, then the steps from it play out. " +
                    "Without one, OFF stops the note. Setting a release on an envelope without a loop loops it from step 0",
            ),
        )
    }

    override fun painter(pane: PaneView): Painter = NesInstrumentPainter(pane)
}
