package org.thortracker.editor

import org.thortracker.model.Cell
import org.thortracker.model.HugeRules
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Phrase
import org.thortracker.model.PhraseMotion
import org.thortracker.model.PhraseRhythm
import org.thortracker.model.PhraseSettings
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Songs
import org.thortracker.model.TonalGuide
import kotlin.random.Random
import org.thortracker.input.Keymap

/**
 * Semantic controller actions. Physical bindings live in the UI layer; views only see these.
 *
 * Press/release semantics: EDIT held + direction adjusts the field (one undo step per hold), a tap
 * inserts the previous value or activates. CLEAR tap clears, CLEAR held + direction jumps. AUDITION
 * held listens and never writes. MARK held + direction selects a block, a tap drops the selection.
 * UNDO/REDO have no bare button: the host dispatches its trigger+A chords to them. SCALE opens or closes
 * the Pattern view's root/scale panel.
 */
enum class Action {
    UP, DOWN, LEFT, RIGHT,
    EDIT, CLEAR, AUDITION, MARK,
    PREV_CHANNEL, NEXT_CHANNEL, OCTAVE_DOWN, OCTAVE_UP,
    COPY, CUT, PASTE, DUPLICATE, UNDO, REDO, INSERT_ROW, DELETE_ROW, SCALE;

    val isDirection: Boolean get() = this == UP || this == DOWN || this == LEFT || this == RIGHT
}

/** A contextual hint: the control and what it does right now. */
data class Hint(val keys: String, val text: String)

/**
 * Controller logic for one display: interprets semantic actions against [state] (view-local
 * navigation) and the shared [session] (document, selection, clipboard, undo).
 */
class ViewController(val session: EditorSession, var state: ViewState) {
    var editHeld = false
        private set
    var clearHeld = false
        private set
    var markHeld = false
        private set
    var auditionHeld = false
        private set
    private var editUsed = false
    private var editInserted = false
    private var clearUsed = false
    private var markUsed = false
    private var markWasActive = false
    private var auditioning = false
    private var keyHeld = false
    private var editGesture: GestureToken? = null
    /** Playback order this view last reacted to; -1 when stopped. */
    private var playOrderSeen = -1
    private var cachedSketchGuide: TonalGuide? = null
    /** Current controller bindings, so hints name the buttons the user actually pressed. */
    var keys: Keymap = Keymap.DEFAULT

    /** Last note/effect per channel and entry sound per bank; part of [state] so it swaps with it. */
    private val lastNote get() = state.entry.notes
    private val lastEffect get() = state.entry.effects
    private val penSound get() = state.entry.pens

    /** Held editing or Sketch listening keeps following from moving the subject under the user's hands. */
    val holding: Boolean get() = editHeld || clearHeld || markHeld ||
        (state.kind == ViewKind.SKETCH && (auditionHeld || auditioning))

    private val song get() = session.song

    // ---- entry points ----------------------------------------------------------------------------

    fun press(a: Action): Boolean {
        normalise()
        if (state.scalePanel) scalePanelPress(a)?.let { return it }
        return when (a) {
            Action.SCALE -> { if (state.kind == ViewKind.PATTERN && !holding) openScalePanel(); state.kind == ViewKind.PATTERN }
            Action.UNDO -> { historyStep(true); true }
            Action.REDO -> { historyStep(false); true }
            Action.OCTAVE_DOWN, Action.OCTAVE_UP -> {
                state.octave = (state.octave + if (a == Action.OCTAVE_UP) 1 else -1).coerceIn(ViewState.MIN_OCTAVE, maxOctave())
                when (state.kind) {
                    ViewKind.SKETCH -> if (auditioning && state.sketchRow != SKETCH_PHRASE) sketchListen()
                    ViewKind.INSTRUMENT, ViewKind.WAVE -> if (auditioning) auditionPress()
                    ViewKind.KEYBOARD -> if (auditioning && keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey))
                    else -> Unit
                }
                session.note(if (state.kind == ViewKind.INSTRUMENT || state.kind == ViewKind.WAVE)
                    "Listening at ${Songs.noteName(defaultNote())}" else "Octave ${state.octave}")
                true
            }
            Action.EDIT -> { if (!editHeld) { editHeld = true; editUsed = false; editInserted = false; editPress() }; true }
            Action.CLEAR -> { if (!clearHeld) { clearHeld = true; clearUsed = false }; true }
            Action.MARK -> { if (!markHeld) { markHeld = true; markUsed = false; markWasActive = state.mark; markPress() }; true }
            Action.AUDITION -> { if (!auditionHeld) { auditionHeld = true; auditionPress() }; true }
            Action.UP -> direction(0, 1)
            Action.DOWN -> direction(0, -1)
            Action.LEFT -> direction(-1, 0)
            Action.RIGHT -> direction(1, 0)
            else -> command(a)
        }
    }

    fun release(a: Action): Boolean {
        return when (a) {
            Action.EDIT -> {
                if (!editHeld) return false
                editHeld = false
                editRelease(!editUsed)
                stopAudition()
                editGesture?.let { session.endGesture(it) }
                editGesture = null
                true
            }
            Action.CLEAR -> {
                if (!clearHeld) return false
                clearHeld = false
                if (!clearUsed) { normalise(); clearTap() }
                true
            }
            Action.MARK -> {
                if (!markHeld) return false
                markHeld = false
                if (!markUsed && markWasActive) { state.mark = false; session.note(null) }
                true
            }
            Action.AUDITION -> {
                if (!auditionHeld) return false
                auditionHeld = false
                stopAudition()
                true
            }
            else -> true
        }
    }

    /** Drops any held state, e.g. when the display loses controller focus. */
    fun cancelHolds() {
        // In Sketch a tap A can write (Insert) or change the key; a cancelled hold is never such a tap.
        if (editHeld && state.kind == ViewKind.SKETCH) editUsed = true
        if (editHeld) release(Action.EDIT)
        clearHeld = false; clearUsed = true
        markHeld = false
        if (auditionHeld) release(Action.AUDITION)
        if (keyHeld) touchKeyUp()
        if (state.kind == ViewKind.SKETCH) sketchRelease()
    }

    private fun historyStep(undo: Boolean) {
        // Undo/redo closes any open group; a still-held A then starts a fresh one.
        val regroup = editGesture != null
        editGesture?.let { session.endGesture(it) }
        editGesture = null
        if (undo) session.undo() else session.redo()
        if (regroup) editGesture = session.beginGesture(gestureLabel())
        normalise()
        if (state.kind == ViewKind.PATTERN) {
            val s = session.selection
            if (s.order in song.orders.indices) { state.order = s.order; state.row = s.row; state.channel = s.channel }
        }
    }

    // ---- shared helpers --------------------------------------------------------------------------

    fun normalise() {
        val s = song
        state.order = state.order.coerceIn(0, s.orders.size - 1)
        state.channel = state.channel.coerceIn(0, s.channelCount() - 1)
        state.row = state.row.coerceIn(0, s.rowCount(s.patternAt(state.order)) - 1)
        state.field = state.field.coerceIn(0, FIELDS - 1)
        state.instrument = state.instrument.coerceIn(1, EditorSession.BANK_SIZE)
        state.wave = state.wave.coerceIn(0, s.waves.size - 1)
        state.bank = state.bank.coerceIn(0, InstrumentKind.entries.size - 1)
        state.octave = state.octave.coerceIn(ViewState.MIN_OCTAVE, maxOctave())
        state.songItem = state.songItem.coerceIn(0, ViewState.SONG_HEADER_ITEMS + s.orders.size - 1)
        state.markOrder = state.markOrder.coerceIn(0, s.orders.size - 1)
        if (state.mark && state.markOrder != state.order) state.mark = false
        // Restored workspaces may carry any values; keep every view-local cursor addressable.
        state.songField = state.songField.coerceIn(0, ORDER_FIELDS.size - 1)
        state.instField = state.instField.coerceIn(0, 1 + SoundParams.forKind(state.bankKind).size)
        state.waveRow = state.waveRow.coerceIn(0, 1)
        state.waveCol = state.waveCol.coerceIn(0, if (state.waveRow == 0) WAVE_TOOLS.size - 1 else WaveShapes.SAMPLES - 1)
        state.waveLevel = state.waveLevel.coerceIn(0, WaveShapes.MAX)
        state.kbRow = state.kbRow.coerceIn(0, 1)
        state.kbField = state.kbField.coerceIn(0, STRIP_FIELDS.size - 1)
        state.kbKey = state.kbKey.coerceIn(0, KEYS - 1)
        state.helpPage = state.helpPage.coerceIn(0, HELP_PAGES.size - 1)
        state.textPos = state.textPos.coerceIn(0, EditorSession.MAX_NAME - 1)
        state.scroll = state.scroll.coerceAtLeast(0)
        state.markRow = state.markRow.coerceIn(0, s.rowCount(s.patternAt(state.order)) - 1)
        state.markChannel = state.markChannel.coerceIn(0, s.channelCount() - 1)
        state.scaleField = state.scaleField.coerceIn(0, SCALE_FIT_FIELD)
        if (state.kind != ViewKind.PATTERN) state.scalePanel = false
        if (state.kind == ViewKind.SKETCH) {
            if (kindOf(state.channel) == InstrumentKind.NOISE) state.channel = song.firstChannelOf(InstrumentKind.PULSE).coerceAtLeast(0)
            state.sketchRow = state.sketchRow.coerceIn(0, SKETCH_ROWS - 1)
            state.sketchField = state.sketchField.coerceIn(0, SKETCH_FIELDS.lastIndex)
            state.sketchChord = state.sketchChord.coerceIn(0, (sketchGuide.chords.size - 1).coerceAtLeast(0))
            state.sketchTone = state.sketchTone.coerceIn(0, sketchGuide.tones.lastIndex)
            state.sketchCircleKey = Math.floorMod(state.sketchCircleKey, 12)
            state.sketchPhraseField = state.sketchPhraseField.coerceIn(0, PHRASE_FIELDS.lastIndex)
            if (state.sketchKeepRhythm) state.sketchKeepPitches = false
        }
    }

    /** After explicit navigation in a locked view, the lock moves to the subject the user chose. */
    fun syncLock() {
        if (state.lockedSubject < 0) return
        state.lockedSubject = when (state.kind) {
            ViewKind.INSTRUMENT -> state.bank * 16 + state.instrument
            ViewKind.WAVE -> state.wave
            ViewKind.PATTERN, ViewKind.KEYBOARD, ViewKind.SKETCH -> state.channel
            else -> state.lockedSubject
        }
    }

    private fun gestureLabel(): String = when (state.kind) {
        ViewKind.PATTERN -> arrayOf("Edit note", "Edit sound", "Edit effect", "Edit effect value")[state.field]
        ViewKind.SONG -> "Edit song"
        ViewKind.INSTRUMENT -> "Edit sound"
        ViewKind.WAVE -> "Draw wave"
        ViewKind.KEYBOARD -> "Keyboard"
        ViewKind.SKETCH -> "Sketch"
        ViewKind.HELP -> "Help"
    }

    private fun kindOf(channel: Int): InstrumentKind = Songs.channelKind(song, channel)

    fun pen(kind: InstrumentKind): Int = penSound[kind] ?: 1

    private fun setPen(kind: InstrumentKind, slot: Int) {
        penSound[kind] = slot.coerceIn(1, EditorSession.BANK_SIZE)
    }

    private fun defaultNote(): Int = ((state.octave - ViewState.MIN_OCTAVE) * 12).coerceIn(0, Songs.NOTE_MAX)

    private fun startAudition(channel: Int, note: Int, instrument: Int, detune: Int = 0) {
        if (note !in 0..Songs.NOTE_MAX) return
        session.audition(channel, note, instrument, detune)
        auditioning = true
    }

    private fun stopAudition() {
        if (auditioning) { auditioning = false; session.releaseAudition() }
    }

    /** The keyboard's two-octave span is capped so only its top key can exceed the note range. */
    private fun maxOctave() = if (state.kind == ViewKind.KEYBOARD || state.kind == ViewKind.SKETCH) ViewState.KEYBOARD_MAX_OCTAVE else ViewState.MAX_OCTAVE

    private fun direction(dx: Int, dy: Int): Boolean {
        // A direction while B is held is always a jump/move, never followed by B's clear.
        if (clearHeld) clearUsed = true
        when (state.kind) {
            ViewKind.PATTERN -> patternDirection(dx, dy)
            ViewKind.SONG -> songDirection(dx, dy)
            ViewKind.INSTRUMENT -> soundDirection(dx, dy)
            ViewKind.WAVE -> waveDirection(dx, dy)
            ViewKind.KEYBOARD -> keyboardDirection(dx, dy)
            ViewKind.SKETCH -> sketchDirection(dx, dy)
            ViewKind.HELP -> helpDirection(dx, dy)
        }
        return true
    }

    private fun editPress() {
        if (state.kind == ViewKind.SKETCH) {
            sketchEditPress()
            return
        }
        editGesture = session.beginGesture(gestureLabel())
        when (state.kind) {
            ViewKind.PATTERN -> patternEditPress()
            ViewKind.WAVE -> if (state.waveRow == 1) paintSample()
            ViewKind.KEYBOARD -> if (state.kbRow == 1) keyDown(state.kbKey)
            else -> Unit
        }
    }

    private fun editRelease(tap: Boolean) {
        when (state.kind) {
            ViewKind.PATTERN -> if (tap && editInserted) advance(session.step)
            ViewKind.SONG -> if (tap) songActivate()
            ViewKind.INSTRUMENT -> if (tap) soundActivate()
            ViewKind.WAVE -> if (tap && state.waveRow == 0) applyTool(state.waveCol)
            ViewKind.KEYBOARD -> if (state.kbRow == 1) keyHeld = false else if (tap) stripActivate()
            ViewKind.HELP -> Unit
            ViewKind.SKETCH -> if (tap) sketchTap()
        }
    }

    private fun clearTap() {
        when (state.kind) {
            ViewKind.PATTERN -> patternClear()
            ViewKind.SONG -> songClear()
            ViewKind.INSTRUMENT -> soundClear()
            ViewKind.WAVE -> if (state.waveRow == 1) session.setWaveSample(state.wave, state.waveCol, 0)
            ViewKind.KEYBOARD -> if (state.entryMode) writeFromKeyboard(Cell.CUT)
            ViewKind.HELP -> Unit
            ViewKind.SKETCH -> sketchClear()
        }
    }

    private fun markPress() {
        when (state.kind) {
            ViewKind.PATTERN -> if (!state.mark) {
                state.mark = true
                state.markOrder = state.order
                state.markRow = state.row
                state.markChannel = state.channel
                session.note(null)
            }
            ViewKind.KEYBOARD -> {
                state.entryMode = !state.entryMode
                markWasActive = false
                session.note(entryModeNote())
            }
            ViewKind.SKETCH -> sketchUsePatternKey()
            else -> Unit
        }
    }

    private fun auditionPress() {
        when (state.kind) {
            ViewKind.PATTERN -> {
                val cell = song.cellAt(song.patternAt(state.order), state.channel, state.row)
                val note = if (cell.note >= 0) cell.note else entryNote(state.channel)
                val ins = if (cell.note >= 0 && cell.instrument > 0) cell.instrument else pen(kindOf(state.channel))
                startAudition(state.channel, note, ins)
            }
            ViewKind.SONG -> {
                val order = (state.songItem - ViewState.SONG_HEADER_ITEMS).coerceIn(0, song.orders.size - 1)
                session.select(session.selection.copy(order = order, row = 0, context = ViewKind.PATTERN))
                session.transport(true)
            }
            ViewKind.INSTRUMENT -> {
                val ch = song.firstChannelOf(state.bankKind)
                if (ch >= 0) startAudition(ch, defaultNote(), state.instrument)
            }
            ViewKind.WAVE -> {
                val slot = soundUsingWave(state.wave)
                val ch = song.firstChannelOf(InstrumentKind.WAVE)
                if (slot > 0 && ch >= 0) startAudition(ch, defaultNote(), slot)
                else session.note("No wave sound plays wave ${EditorSession.hex1(state.wave)}: choose Use in sound first")
            }
            ViewKind.KEYBOARD -> if (keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey))
            ViewKind.HELP -> Unit
            ViewKind.SKETCH -> sketchListen()
        }
    }

    private fun command(a: Action): Boolean = when (state.kind) {
        ViewKind.PATTERN -> patternCommand(a)
        ViewKind.SONG -> songCommand(a)
        ViewKind.INSTRUMENT -> soundCommand(a)
        ViewKind.WAVE -> waveCommand(a)
        ViewKind.KEYBOARD -> keyboardCommand(a)
        ViewKind.HELP -> helpCommand(a)
        ViewKind.SKETCH -> sketchCommand(a)
    }

    // ---- pattern ---------------------------------------------------------------------------------

    private val pattern get() = song.patternAt(state.order)
    private val cell get() = song.cellAt(pattern, state.channel, state.row)

    fun block(): Block =
        if (state.mark) Block.of(state.markChannel, state.markRow, state.channel, state.row)
        else Block(state.channel, state.channel, state.row, state.row)

    /** Publishes the pattern cursor as the shared selection. */
    fun publishPattern() {
        syncLock()
        if (state.chosenArpeggio != cursorKey()) state.chosenArpeggio = -1
        val kind = kindOf(state.channel)
        val c = cell
        val ins = if (c.instrument > 0) c.instrument else pen(kind)
        val wave = if (kind == InstrumentKind.WAVE) song.instrument(kind, ins)?.wave ?: session.selection.wave else session.selection.wave
        session.select(Selection(state.order, state.row, state.channel, kind, ins, wave, PATTERN_CONTEXT[state.field]))
    }

    private fun cursorKey() = cellKey(pattern, state.channel, state.row)

    /**
     * True when the cursor cell's stored 000 is shown as an explicitly chosen effect 0 (arpeggio) waiting for
     * its value, rather than as "no effect". Both are the same bytes; only the display and picker position differ.
     */
    fun chosenArpeggio(): Boolean {
        if (state.chosenArpeggio != cursorKey()) return false
        val c = cell
        return c.note != Cell.CUT && !c.hasEffect
    }

    /** The pattern scale note stepping follows on [channel]; Noise note numbers select noise pitches, so stay chromatic. */
    fun noteScale(channel: Int): Scale =
        if (kindOf(channel) == InstrumentKind.NOISE) Scale.CHROMATIC else song.patterns[pattern].scale

    /**
     * Note written into an empty row: the channel's last note, else the scale root (C when chromatic) at the octave.
     * With a scale, a remembered note outside it moves to the nearest scale note.
     */
    private fun entryNote(channel: Int): Int {
        val scale = noteScale(channel)
        if (scale.isChromatic) return lastNote[channel] ?: defaultNote()
        val note = lastNote[channel] ?: (defaultNote() + scale.root).coerceIn(0, Songs.NOTE_MAX)
        return scale.snap(note) ?: note
    }

    /** The song tuning when [channel] is tonal (Pulse/Wave); Noise and 12-note songs return null. */
    fun tuningFor(channel: Int): org.thortracker.model.TuningMap? =
        if (kindOf(channel) == InstrumentKind.NOISE) null else session.tuningMap

    /** Tuned pitch written into an empty row: the channel's last tuned pitch if still reachable, else 1/1 nearest the octave. */
    private fun entryPitch(channel: Int, map: org.thortracker.model.TuningMap): org.thortracker.model.TunedPitch? {
        state.entry.tuned[channel]?.let { (n, d) -> map.index(n, d).takeIf { it >= 0 }?.let { return map.pitches[it] } }
        val root = map.tuning.root
        val near = ((state.octave - ViewState.MIN_OCTAVE) * 12 + Math.floorMod(root, 12)).coerceIn(0, Songs.NOTE_MAX)
        return map.nearest(near, 0).takeIf { it >= 0 }?.let { map.pitches[it] }
    }

    /**
     * Writes tuned [pitch] into the cursor cell (with sound [ins]) and auditions it. Refuses, with a message, when
     * the pitch needs a bend and the row's effect slot holds another effect.
     */
    private fun writeTuned(c: Cell, pitch: org.thortracker.model.TunedPitch, ins: Int, map: org.thortracker.model.TuningMap): Boolean {
        val next = EditorSession.tuned(c.copy(instrument = ins), pitch) ?: run {
            session.note("This row's effect ${EditorSession.hex1(c.effect)}${EditorSession.hex2(c.param)} uses the slot tuned notes need for 40y; clear it first")
            return false
        }
        write(next)
        state.entry.tuned[state.channel] = pitch.note to pitch.detune
        lastNote[state.channel] = pitch.note
        startAudition(state.channel, pitch.note, ins, pitch.detune)
        session.note("${map.tuning.name}: ${map.label(pitch)}  ${Songs.noteName(pitch.note)}${if (pitch.detune > 0) " +${pitch.detune}" else ""}  " +
            "${"%+.1f".format(pitch.error)}¢")
        return true
    }

    /** Hold-Edit stepping in a tuned song: ←→ next/previous reachable degree, ↑↓ the same degree a period away. */
    private fun adjustTuned(c: Cell, dx: Int, dy: Int, map: org.thortracker.model.TuningMap) {
        val ch = state.channel
        val ins = if (c.note < 0 || c.instrument == 0) pen(kindOf(ch)) else c.instrument
        if (map.pitches.isEmpty()) { session.note("No degree of ${map.tuning.name} is reachable within ${map.tuning.tolerance}¢"); return }
        val target = if (c.note < 0) entryPitch(ch, map) else {
            val detune = HugeRules.detuneOf(c) ?: 0
            val here = map.index(c.note, detune).takeIf { it >= 0 } ?: map.nearest(c.note, detune)
            val cur = map.pitches[here]
            if (dy != 0) {
                val goal = cur.degree + dy * map.steps
                (if (dy > 0) map.pitches.firstOrNull { it.degree >= goal } else map.pitches.lastOrNull { it.degree <= goal })
                    ?.takeIf { if (dy > 0) it.degree > cur.degree else it.degree < cur.degree }
            } else if (map.index(c.note, detune) < 0) cur // first edit of an out-of-tuning note lands on the tuning
            else map.pitches.getOrNull(here + dx)
        } ?: run {
            session.note("No ${if (dx > 0 || dy > 0) "higher" else "lower"} reachable degree of ${map.tuning.name} within C-3..B-8")
            return
        }
        writeTuned(c, target, ins, map)
    }

    /** Moves the cursor by rows, crossing into neighbouring orders. */
    private fun moveRows(delta: Int) {
        var order = state.order
        var row = state.row + delta
        while (row < 0 && order > 0) { order--; row += song.rowCount(song.patternAt(order)) }
        while (row >= song.rowCount(song.patternAt(order)) && order < song.orders.size - 1) {
            row -= song.rowCount(song.patternAt(order)); order++
        }
        state.order = order
        state.row = row.coerceIn(0, song.rowCount(song.patternAt(order)) - 1)
        if (state.mark && !markHeld && state.markOrder != order) state.mark = false
    }

    private fun advance(rows: Int) {
        if (rows == 0) return
        moveRows(rows)
        publishPattern()
    }

    private fun patternDirection(dx: Int, dy: Int) {
        when {
            editHeld -> { editUsed = true; adjustCell(dx, dy) }
            clearHeld -> {
                clearUsed = true
                if (dy != 0) moveRows(-dy * 16)
                else {
                    state.order = (state.order + dx).coerceIn(0, song.orders.size - 1)
                    state.row = state.row.coerceAtMost(song.rowCount(pattern) - 1)
                    state.mark = false
                }
                publishPattern()
            }
            markHeld -> {
                markUsed = true
                if (dy != 0) state.row = (state.row - dy).coerceIn(0, song.rowCount(pattern) - 1)
                else state.channel = (state.channel + dx).coerceIn(0, song.channelCount() - 1)
                publishPattern()
            }
            else -> {
                if (dy != 0) moveRows(-dy)
                else {
                    val pos = (state.channel * FIELDS + state.field + dx).coerceIn(0, song.channelCount() * FIELDS - 1)
                    state.channel = pos / FIELDS
                    state.field = pos % FIELDS
                }
                publishPattern()
            }
        }
    }

    private fun patternEditPress() {
        val c = cell
        val ch = state.channel
        val kind = kindOf(ch)
        when (state.field) {
            0 -> if (c.note < 0) {
                val map = tuningFor(ch)
                if (map != null) {
                    val pitch = entryPitch(ch, map) ?: run { session.note("No degree of ${map.tuning.name} is reachable"); return }
                    if (writeTuned(c, pitch, pen(kind), map)) editInserted = true
                } else {
                    val note = entryNote(ch)
                    write(c.copy(note = note, instrument = pen(kind)))
                    editInserted = true
                    startAudition(ch, note, pen(kind))
                }
            } else {
                lastNote[ch] = c.note
                if (c.instrument > 0) { setPen(kind, c.instrument); state.instrument = c.instrument }
                val detune = if (tuningFor(ch) != null) HugeRules.detuneOf(c) ?: 0 else 0
                startAudition(ch, c.note, if (c.instrument > 0) c.instrument else pen(kind), detune)
            }
            1 -> when {
                c.note < 0 -> session.note("Sounds start with a note: enter a note on this row first")
                c.instrument == 0 -> { write(c.copy(instrument = pen(kind))); editInserted = true }
                else -> { setPen(kind, c.instrument); state.instrument = c.instrument }
            }
            else -> when {
                c.note == Cell.CUT -> session.note(OFF_TAKES_EFFECT_SLOT)
                !c.hasEffect && !chosenArpeggio() -> {
                    val (e, p) = lastEffect[ch] ?: return
                    if (e == HugeRules.FX_ARPEGGIO && p == 0) state.chosenArpeggio = cursorKey() // 000: show the chosen arpeggio, nothing to write
                    else { write(c.copy(effect = e, param = p)); editInserted = true }
                }
                else -> lastEffect[ch] = c.effect to c.param
            }
        }
        publishPattern()
    }

    private fun write(c: Cell) {
        session.setCell(pattern, state.channel, state.row, c)
        problemNote()
    }

    private fun problemNote() {
        val problem = Songs.cellProblem(song, state.channel, cell)
        if (problem != null) session.note(problem)
    }

    private fun adjustCell(dx: Int, dy: Int) {
        val c = cell
        val ch = state.channel
        val kind = kindOf(ch)
        when (state.field) {
            0 -> {
                tuningFor(ch)?.let { adjustTuned(c, dx, dy, it); publishPattern(); return }
                // Chromatic: semitones and octaves, clamped to C-3..B-8. With a pattern scale (Pulse/Wave), every
                // edit lands on a scale note: steps go to the next one, octaves are exact from a scale note and
                // otherwise take the first scale note past the octave target; no edit when none is in range.
                // Setting a scale never touches notes; only edited notes move onto it.
                val scale = noteScale(ch)
                val note = when {
                    c.note < 0 -> entryNote(ch)
                    scale.isChromatic -> (c.note + dx + 12 * dy).coerceIn(0, Songs.NOTE_MAX)
                    dy != 0 -> scale.octave(c.note, dy) ?: run {
                        session.note("No ${scale.label} note an octave ${if (dy > 0) "above" else "below"} ${Songs.noteName(c.note)} within C-3..B-8")
                        return
                    }
                    else -> scale.step(c.note, dx) ?: run {
                        session.note("No ${if (dx > 0) "higher" else "lower"} ${scale.label} note than ${Songs.noteName(c.note)} within C-3..B-8")
                        return
                    }
                }
                val ins = if (c.note < 0 || c.instrument == 0) pen(kind) else c.instrument
                write(c.copy(note = note, instrument = ins))
                lastNote[ch] = note
                startAudition(ch, note, ins)
            }
            1 -> {
                if (c.note < 0) { session.note("Sounds start with a note: enter a note on this row first"); return }
                val ins = ((if (c.instrument == 0) pen(kind) else c.instrument) + dx + 4 * dy).coerceIn(1, EditorSession.BANK_SIZE)
                write(c.copy(instrument = ins))
                setPen(kind, ins)
                state.instrument = ins
                startAudition(ch, c.note, ins)
            }
            2 -> {
                if (c.note == Cell.CUT) { session.note(OFF_TAKES_EFFECT_SLOT); return }
                // Picker positions: no effect, then the channel's usable codes. A stored 000 counts as "no effect"
                // unless effect 0 was picked here; picking 0 with value 00 writes nothing until a value is set.
                val positions = listOf(NO_EFFECT) + Songs.effectsFor(song, ch).map { it.code }
                val current = if (!c.hasEffect && !chosenArpeggio()) NO_EFFECT else c.effect
                val idx = positions.indexOf(current).coerceAtLeast(0)
                val next = positions[Math.floorMod(idx + (if (dx != 0) dx else dy), positions.size)]
                if (next == NO_EFFECT) {
                    write(c.copy(effect = 0, param = 0))
                    state.chosenArpeggio = -1
                    lastEffect.remove(ch)
                } else {
                    write(c.copy(effect = next))
                    lastEffect[ch] = next to c.param
                    state.chosenArpeggio = if (next == HugeRules.FX_ARPEGGIO && c.param == 0) cursorKey() else -1
                }
            }
            else -> {
                if (c.note == Cell.CUT) { session.note(OFF_TAKES_EFFECT_SLOT); return }
                val p = (c.param + dx + 16 * dy).coerceIn(0, 255)
                write(c.copy(param = p))
                lastEffect[ch] = c.effect to p
                // Back to 00 on effect 0: keep showing the arpeggio being edited instead of a blank cell.
                if (c.effect == HugeRules.FX_ARPEGGIO && p == 0) state.chosenArpeggio = cursorKey()
            }
        }
        publishPattern()
    }

    private fun patternClear() {
        if (state.mark) {
            session.clearBlock(pattern, block())
            state.mark = false
            return
        }
        val c = cell
        when (state.field) {
            0 -> when {
                c.note >= 0 -> write(c.copy(note = Cell.EMPTY, instrument = 0))
                c.note == Cell.CUT -> write(c.copy(note = Cell.EMPTY))
                c.hasEffect -> session.note("Clear this row's effect first: OFF uses the effect slot (as E00)")
                else -> { write(Cell(note = Cell.CUT)); session.note(OFF_EXPLAINED) }
            }
            1 -> write(c.copy(instrument = 0))
            else -> { write(c.copy(effect = 0, param = 0)); state.chosenArpeggio = -1 }
        }
    }

    private fun patternCommand(a: Action): Boolean {
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                state.channel = (state.channel + if (a == Action.NEXT_CHANNEL) 1 else -1).coerceIn(0, song.channelCount() - 1)
                syncLock()
                state.instrument = pen(kindOf(state.channel))
                publishPattern()
            }
            Action.COPY -> session.copyBlock(pattern, block())
            Action.CUT -> { session.cutBlock(pattern, block()); state.mark = false }
            Action.PASTE -> {
                val b = session.pasteCells(pattern, state.channel, state.row) ?: return true
                selectBlock(b)
            }
            Action.DUPLICATE -> {
                val b = session.duplicateBlock(pattern, block()) ?: return true
                if (state.mark) selectBlock(b) else { state.row = b.firstRow; publishPattern() }
            }
            Action.INSERT_ROW -> { val b = block(); session.insertRow(pattern, b.firstChannel..b.lastChannel, state.row) }
            Action.DELETE_ROW -> { val b = block(); session.deleteRow(pattern, b.firstChannel..b.lastChannel, state.row) }
            else -> return false
        }
        return true
    }

    /** Shows [b] as the marked block with the cursor on its top-left cell. */
    private fun selectBlock(b: Block) {
        if (b.width == 1 && b.height == 1) { state.mark = false; state.row = b.firstRow; publishPattern(); return }
        state.mark = true
        state.markOrder = state.order
        state.markChannel = b.lastChannel
        state.markRow = b.lastRow
        state.channel = b.firstChannel
        state.row = b.firstRow
        publishPattern()
    }

    // ---- pattern scale panel -------------------------------------------------------------------------

    /** Editor-only scale of the pattern at the cursor's order. */
    val patternScale: Scale get() = song.patterns[pattern].scale

    fun openScalePanel() {
        state.scalePanel = true
        session.note(null)
    }

    fun closeScalePanel() {
        state.scalePanel = false
    }

    /** Steps the root ([field] 0, by semitones, wrapping) or the scale ([field] 1, through [ScaleKind]) by [delta]. */
    fun stepScale(field: Int, delta: Int) {
        val s = patternScale
        state.scaleField = field
        setScale(if (field == 0) s.copy(root = Math.floorMod(s.root + delta, 12))
            else s.copy(kind = ScaleKind.entries[Math.floorMod(s.kind.ordinal + delta, ScaleKind.entries.size)]))
    }

    /** Resets the root ([field] 0) to C, the scale ([field] 1) to chromatic, or the length ([field] 2) to 64 rows. */
    fun resetScaleField(field: Int) {
        val s = patternScale
        state.scaleField = field
        when (field) {
            0 -> setScale(s.copy(root = 0))
            1 -> setScale(s.copy(kind = ScaleKind.CHROMATIC))
            else -> setLength(Songs.ROWS)
        }
    }

    /** Played rows of the pattern at the cursor's order. */
    val patternLength: Int get() = song.patterns[pattern].length

    /** Changes the pattern length by [delta] rows, within 1..64. */
    fun stepLength(delta: Int) {
        state.scaleField = 2
        setLength(patternLength + delta)
    }

    private fun setLength(length: Int) {
        if (!session.setPatternLength(pattern, length)) return
        val n = patternLength
        val uses = song.orders.count { it == pattern }
        session.note("Pattern ${EditorSession.hex2(pattern)}: $n rows" +
            (if (n < Songs.ROWS) " (rows ${EditorSession.hex2(n)}-3F hidden, kept)" else "") +
            (if (uses > 1) ", in all $uses orders that use it" else ""))
        normalise()
    }

    private fun setScale(s: Scale) {
        if (!session.setPatternScale(pattern, s)) return
        val root = Scale.ROOT_NAMES[s.root]
        session.note(
            if (s.isChromatic) "Pattern ${EditorSession.hex2(pattern)}: chromatic, every semitone (root $root waits for a scale)"
            else "Pattern ${EditorSession.hex2(pattern)}: ${s.label}. Note edits land on its notes; written notes stay until edited",
        )
    }

    /** Nudges the pattern's played Pulse/Wave notes onto the nearest scale notes, as one undo step. */
    fun fitPatternToScale() {
        state.scaleField = SCALE_FIT_FIELD
        val s = patternScale
        val label = "Pattern ${EditorSession.hex2(pattern)}"
        when {
            song.tuning != null -> session.note("The song tuning replaces the scale: use Menu → Tuning → Fit notes")
            s.isChromatic -> session.note("$label is chromatic: every note already fits. Choose a scale first")
            else -> {
                val moved = session.fitToScale(pattern)
                session.note(if (moved == 0) "$label: every note is already in ${s.label}"
                    else "$label: moved $moved note${if (moved == 1) "" else "s"} to the nearest ${s.label} note, marked ▴▾ until edited (${keys.label(Action.UNDO, state.kind)} undoes)")
            }
        }
    }

    /** Controller input while the scale panel is open; null lets the action through (closing the panel unless harmless). */
    private fun scalePanelPress(a: Action): Boolean? {
        when (a) {
            Action.UP -> state.scaleField = (state.scaleField - 1).coerceAtLeast(0)
            Action.DOWN -> state.scaleField = (state.scaleField + 1).coerceAtMost(SCALE_FIT_FIELD)
            Action.LEFT, Action.RIGHT -> {
                val d = if (a == Action.RIGHT) 1 else -1
                when (state.scaleField) {
                    2 -> stepLength(d)
                    SCALE_FIT_FIELD -> Unit
                    else -> stepScale(state.scaleField, d)
                }
            }
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                if (state.scaleField != 2) { closeScalePanel(); return null }
                stepLength(if (a == Action.NEXT_CHANNEL) 8 else -8)
            }
            Action.CLEAR -> if (state.scaleField != SCALE_FIT_FIELD) resetScaleField(state.scaleField)
            Action.EDIT -> if (state.scaleField == SCALE_FIT_FIELD) fitPatternToScale() else closeScalePanel()
            Action.SCALE -> closeScalePanel()
            Action.UNDO, Action.REDO, Action.AUDITION -> return null
            else -> { closeScalePanel(); return null }
        }
        return true
    }

    // ---- song / orders -----------------------------------------------------------------------------

    val songOrder: Int get() = state.songItem - ViewState.SONG_HEADER_ITEMS

    private fun publishOrder() {
        val o = songOrder
        if (o >= 0) {
            state.order = o
            session.select(session.selection.copy(order = o, row = 0, context = ViewKind.PATTERN))
        }
    }

    private fun songDirection(dx: Int, dy: Int) {
        val item = state.songItem
        when {
            editHeld -> { editUsed = true; songAdjust(dx, dy) }
            clearHeld -> {
                clearUsed = true
                state.songItem = (item - dy * 8 + dx * 8).coerceIn(0, ViewState.SONG_HEADER_ITEMS + song.orders.size - 1)
                publishOrder()
            }
            dy != 0 -> {
                state.songItem = (item - dy).coerceIn(0, ViewState.SONG_HEADER_ITEMS + song.orders.size - 1)
                state.textPos = 0
                if (state.songItem >= ViewState.SONG_HEADER_ITEMS) state.songField = state.songField.coerceIn(0, ORDER_FIELDS.size - 1)
                publishOrder()
            }
            item <= 1 -> state.textPos = (state.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            item >= ViewState.SONG_HEADER_ITEMS -> state.songField = (state.songField + dx).coerceIn(0, ORDER_FIELDS.size - 1)
        }
    }

    private fun songAdjust(dx: Int, dy: Int) {
        val s = song
        when (state.songItem) {
            0 -> session.setTitle(SoundParams.editName(s.title, state.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            1 -> session.setArtist(SoundParams.editName(s.artist, state.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            2 -> session.setTicksPerRow(s.ticksPerRow + dx + 4 * dy)
            3 -> session.setLoopOrder(s.loopOrder + dx + 8 * dy)
            else -> if (state.songField == 0) {
                session.setOrderPattern(songOrder, s.orders[songOrder] + dx + 8 * dy)
                publishOrder()
            }
        }
    }

    private fun songActivate() {
        val o = songOrder
        if (o < 0) return
        when (state.songField) {
            0 -> { publishOrder(); session.note("Order ${EditorSession.hex2(o)} selected") }
            1 -> if (session.insertOrder(o + 1, song.orders[o])) { state.songItem++; publishOrder() }
            2 -> if (session.newPatternAt(o + 1) >= 0) { state.songItem++; publishOrder() }
            3 -> if (session.clonePatternAt(o) >= 0) { state.songItem++; publishOrder() }
            4 -> { session.deleteOrder(o); normalise(); publishOrder() }
            5 -> if (session.moveOrder(o, o - 1)) { state.songItem--; publishOrder() }
            6 -> if (session.moveOrder(o, o + 1)) { state.songItem++; publishOrder() }
            7 -> session.setLoopOrder(o)
        }
    }

    private fun songClear() {
        val s = song
        when (state.songItem) {
            0 -> session.setTitle(deleteChar(s.title, state.textPos))
            1 -> session.setArtist(deleteChar(s.artist, state.textPos))
            2 -> session.setTicksPerRow(6)
            3 -> session.setLoopOrder(0)
            else -> { session.deleteOrder(songOrder); normalise(); publishOrder() }
        }
    }

    private fun deleteChar(text: String, pos: Int) = if (pos < text.length) text.removeRange(pos, pos + 1) else text

    private fun songCommand(a: Action): Boolean {
        val o = songOrder
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                state.songItem = (state.songItem + if (a == Action.NEXT_CHANNEL) 8 else -8).coerceIn(0, ViewState.SONG_HEADER_ITEMS + song.orders.size - 1)
                publishOrder()
            }
            Action.COPY -> if (o >= 0) session.copyPattern(song.orders[o]) else return false
            Action.PASTE -> if (o >= 0) session.pastePattern(song.orders[o]) else return false
            Action.DUPLICATE -> if (o >= 0 && session.clonePatternAt(o) >= 0) { state.songItem++; publishOrder() }
            Action.INSERT_ROW -> if (o >= 0 && session.insertOrder(o + 1, song.orders[o])) { state.songItem++; publishOrder() }
            Action.DELETE_ROW -> if (o >= 0) { session.deleteOrder(o); normalise(); publishOrder() }
            else -> return false
        }
        return true
    }

    // ---- instruments -------------------------------------------------------------------------------

    val soundParams: List<SoundParam> get() = SoundParams.forKind(state.bankKind)
    val sound: Instrument? get() = song.instrument(state.bankKind, state.instrument)

    fun publishSound() {
        val kind = state.bankKind
        syncLock()
        val wave = if (kind == InstrumentKind.WAVE) sound?.wave ?: session.selection.wave else session.selection.wave
        // Sound view suggests where the sound is heard: its waveform for wave sounds, else the keyboard.
        val context = if (kind == InstrumentKind.WAVE) ViewKind.WAVE else ViewKind.KEYBOARD
        session.select(session.selection.copy(bank = kind, instrument = state.instrument, wave = wave, context = context))
    }

    private fun soundDirection(dx: Int, dy: Int) {
        val fields = 2 + soundParams.size
        when {
            editHeld -> { editUsed = true; soundAdjust(dx, dy) }
            dy != 0 -> { state.instField = (state.instField - dy).coerceIn(0, fields - 1); state.textPos = 0 }
            state.instField == 1 -> state.textPos = (state.textPos + dx).coerceIn(0, EditorSession.MAX_NAME - 1)
            else -> { state.instrument = (state.instrument + dx).coerceIn(1, EditorSession.BANK_SIZE); publishSound() }
        }
    }

    private fun soundAdjust(dx: Int, dy: Int) {
        val kind = state.bankKind
        when (state.instField) {
            0 -> {
                state.instrument = (state.instrument + dx).coerceIn(1, EditorSession.BANK_SIZE)
                if (dy != 0) {
                    state.bank = Math.floorMod(state.bank - dy, InstrumentKind.entries.size)
                    state.instField = 0
                }
                publishSound()
            }
            1 -> session.editInstrument(kind, state.instrument, "Rename sound") {
                it.copy(name = SoundParams.editName(it.name, state.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            }
            else -> {
                val p = soundParams.getOrNull(state.instField - 2) ?: return
                session.editInstrument(kind, state.instrument, "Set ${p.label.lowercase()}") { p.adjust(it, dx + p.coarse * dy) }
                if (p.label == "Wave") publishSound()
            }
        }
    }

    private fun soundActivate() {
        when (state.instField) {
            0 -> { publishSound(); session.note("Sound ${EditorSession.hex2(state.instrument)} selected for entry") }
            1 -> Unit
            else -> {
                val p = soundParams.getOrNull(state.instField - 2) ?: return
                if (p.max - p.min == 1) {
                    session.editInstrument(state.bankKind, state.instrument, "Toggle ${p.label.lowercase()}") {
                        p.set(it, if (p.get(it) == p.min) p.max else p.min)
                    }
                }
            }
        }
    }

    private fun soundClear() {
        val kind = state.bankKind
        when (state.instField) {
            0 -> session.setInstrument(kind, state.instrument, defaultSound(kind, state.instrument))
            1 -> session.editInstrument(kind, state.instrument, "Rename sound") { it.copy(name = deleteChar(it.name, state.textPos)) }
            else -> {
                val p = soundParams.getOrNull(state.instField - 2) ?: return
                val def = p.get(defaultSound(kind, state.instrument))
                session.editInstrument(kind, state.instrument, "Reset ${p.label.lowercase()}") { p.set(it, def) }
            }
        }
    }

    private fun soundCommand(a: Action): Boolean {
        val kind = state.bankKind
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                state.bank = Math.floorMod(state.bank + if (a == Action.NEXT_CHANNEL) 1 else -1, InstrumentKind.entries.size)
                state.instField = state.instField.coerceAtMost(1 + soundParams.size)
                publishSound()
            }
            Action.COPY -> session.copyInstrument(kind, state.instrument)
            Action.PASTE -> session.pasteInstrument(kind, state.instrument)
            Action.CUT -> {
                session.copyInstrument(kind, state.instrument)
                session.setInstrument(kind, state.instrument, defaultSound(kind, state.instrument))
            }
            Action.DUPLICATE -> {
                if (state.instrument >= EditorSession.BANK_SIZE) { session.note("Sound ${EditorSession.hex2(state.instrument)} is the last slot"); return true }
                val src = sound ?: return true
                session.setInstrument(kind, state.instrument + 1, src.copy(name = (src.name + "+").take(EditorSession.MAX_NAME)))
                state.instrument++
                publishSound()
            }
            else -> return false
        }
        return true
    }

    private fun defaultSound(kind: InstrumentKind, slot: Int) = Instrument(name = "Sound ${EditorSession.hex2(slot)}", kind = kind)

    // ---- waves -------------------------------------------------------------------------------------

    /** Wave view suggests the sound view showing the wave sound that plays this wave. */
    fun publishWave() {
        syncLock()
        val slot = soundUsingWave(state.wave)
        val sel = session.selection
        session.select(
            if (slot > 0) sel.copy(wave = state.wave, bank = InstrumentKind.WAVE, instrument = slot, context = ViewKind.INSTRUMENT)
            else sel.copy(wave = state.wave, context = ViewKind.INSTRUMENT),
        )
    }

    /** Wave sound slot that plays [wave]: the selected wave sound if it does, else the first, else 0. */
    fun soundUsingWave(wave: Int): Int {
        val sel = session.selection
        if (sel.bank == InstrumentKind.WAVE && song.instrument(InstrumentKind.WAVE, sel.instrument)?.wave == wave) return sel.instrument
        val idx = song.waveInstruments.indexOfFirst { it.wave == wave }
        return if (idx < 0) 0 else idx + 1
    }

    /** Wave sound the "Use in sound" tool assigns to. */
    fun waveTargetSound(): Int {
        val sel = session.selection
        return if (sel.bank == InstrumentKind.WAVE) sel.instrument else pen(InstrumentKind.WAVE)
    }

    private fun paintSample() {
        session.setWaveSample(state.wave, state.waveCol, state.waveLevel)
    }

    private fun waveDirection(dx: Int, dy: Int) {
        if (state.waveRow == 0) {
            when {
                dy < 0 -> state.waveRow = 1
                dx != 0 -> state.waveCol = (state.waveCol + dx).coerceIn(0, WAVE_TOOLS.size - 1)
            }
            return
        }
        when {
            clearHeld -> { clearUsed = true; state.waveCol = (state.waveCol + 8 * dx).coerceIn(0, WaveShapes.SAMPLES - 1) }
            editHeld -> {
                editUsed = true
                state.waveCol = (state.waveCol + dx).coerceIn(0, WaveShapes.SAMPLES - 1)
                state.waveLevel = (state.waveLevel + dy).coerceIn(0, WaveShapes.MAX)
                paintSample()
            }
            dy > 0 && state.waveLevel == WaveShapes.MAX -> { state.waveRow = 0; state.waveCol = state.waveCol.coerceIn(0, WAVE_TOOLS.size - 1) }
            else -> {
                state.waveCol = (state.waveCol + dx).coerceIn(0, WaveShapes.SAMPLES - 1)
                state.waveLevel = (state.waveLevel + dy).coerceIn(0, WaveShapes.MAX)
            }
        }
    }

    fun applyTool(tool: Int) {
        val w = song.waves[state.wave]
        when (tool) {
            0 -> session.setWave(state.wave, WaveShapes.sine(), "Sine wave")
            1 -> session.setWave(state.wave, WaveShapes.triangle(), "Triangle wave")
            2 -> session.setWave(state.wave, WaveShapes.saw(), "Saw wave")
            3 -> session.setWave(state.wave, WaveShapes.square(), "Square wave")
            4 -> session.setWave(state.wave, WaveShapes.invert(w), "Invert wave")
            5 -> session.setWave(state.wave, WaveShapes.rotate(w, -1), "Shift wave")
            6 -> session.setWave(state.wave, WaveShapes.rotate(w, 1), "Shift wave")
            7 -> session.setWave(state.wave, WaveShapes.smooth(w), "Smooth wave")
            8 -> {
                val slot = waveTargetSound()
                session.editInstrument(InstrumentKind.WAVE, slot, "Assign wave") { it.copy(wave = state.wave) }
                session.note("Sound ${EditorSession.hex2(slot)} now plays wave ${EditorSession.hex1(state.wave)}")
            }
        }
    }

    private fun waveCommand(a: Action): Boolean {
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                state.wave = Math.floorMod(state.wave + if (a == Action.NEXT_CHANNEL) 1 else -1, song.waves.size)
                publishWave()
            }
            Action.COPY -> session.copyWave(state.wave)
            Action.PASTE -> session.pasteWave(state.wave)
            Action.CUT -> { session.copyWave(state.wave); session.setWave(state.wave, List(WaveShapes.SAMPLES) { 0 }, "Cut wave") }
            Action.DUPLICATE -> {
                if (state.wave >= song.waves.size - 1) { session.note("Wave ${EditorSession.hex1(state.wave)} is the last wave"); return true }
                session.setWave(state.wave + 1, song.waves[state.wave], "Duplicate wave")
                state.wave++
                publishWave()
            }
            else -> return false
        }
        return true
    }

    // ---- keyboard ----------------------------------------------------------------------------------

    /** Note of keyboard [key] at the current octave; may exceed the range, see [keyPlayable]. */
    fun keyNote(key: Int): Int = (state.octave - ViewState.MIN_OCTAVE) * 12 + key

    fun keyPlayable(key: Int): Boolean = keyNote(key) in 0..Songs.NOTE_MAX

    fun keyboardSound(): Int = state.instrument

    private fun keyboardDirection(dx: Int, dy: Int) {
        if (state.kbRow == 0) {
            when {
                editHeld -> { editUsed = true; stripAdjust(dx + dy) }
                dy < 0 -> state.kbRow = 1
                dx != 0 -> state.kbField = (state.kbField + dx).coerceIn(0, STRIP_FIELDS.size - 1)
            }
            return
        }
        when {
            clearHeld -> { clearUsed = true; moveWritePosition(-dy) }
            dy > 0 && !editHeld -> state.kbRow = 0
            dy < 0 && !editHeld -> if (state.entryMode) moveWritePosition(maxOf(1, session.step)) else Unit
            dx != 0 -> {
                var key = state.kbKey + dx
                if (key < 0 && state.octave > ViewState.MIN_OCTAVE) { state.octave--; key += 12 }
                if (key > KEYS - 1 && state.octave < ViewState.KEYBOARD_MAX_OCTAVE) { state.octave++; key -= 12 }
                state.kbKey = key.coerceIn(0, KEYS - 1)
                if (editHeld) { editUsed = true; if (keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey)) }
            }
        }
    }

    private fun stripAdjust(d: Int) {
        when (state.kbField) {
            0 -> state.entryMode = !state.entryMode
            1 -> setKeyboardChannel(state.channel + d)
            2 -> setKeyboardSound(state.instrument + d)
            3 -> state.octave = (state.octave + d).coerceIn(ViewState.MIN_OCTAVE, ViewState.KEYBOARD_MAX_OCTAVE)
            4 -> session.step += d
        }
    }

    private fun stripActivate() {
        if (state.kbField == 0) {
            state.entryMode = !state.entryMode
            session.note(entryModeNote())
        }
    }

    /**
     * Applies another view's published selection: navigation follows only when [ViewState.followSelection]
     * is set; the selected sound always becomes this view's entry sound for its bank.
     */
    fun follow(): Boolean {
        if (holding) return false // applied by the host once the held control is released
        val sel = session.selection
        setPen(sel.bank, sel.instrument)
        if (state.kind == ViewKind.PATTERN && kindOf(state.channel) == sel.bank) state.instrument = sel.instrument
        return Follow.apply(state, session)
    }

    /**
     * Moves a following pattern view to the playing order when playback enters a new order. The
     * move waits while A, B or Y is held; once made, the user may navigate away until the next order.
     * Returns true if the view moved.
     */
    fun followPlayback(order: Int, playing: Boolean): Boolean {
        if (!playing) { playOrderSeen = -1; return false }
        if (order == playOrderSeen) return false
        if (state.kind != ViewKind.PATTERN || !state.followPlayback) { playOrderSeen = order; return false }
        if (holding) return false
        playOrderSeen = order
        if (order !in song.orders.indices || state.order == order) return false
        state.order = order
        state.mark = false
        normalise()
        return true
    }

    fun setKeyboardSound(slot: Int) {
        state.instrument = slot.coerceIn(1, EditorSession.BANK_SIZE)
        setPen(kindOf(state.channel), state.instrument)
        publishKeyboard()
    }

    fun setKeyboardChannel(channel: Int) {
        state.channel = channel.coerceIn(0, song.channelCount() - 1)
        syncLock()
        state.instrument = pen(kindOf(state.channel))
        publishKeyboard()
    }

    private fun publishKeyboard() {
        val kind = kindOf(state.channel)
        session.select(session.selection.copy(channel = state.channel, bank = kind, instrument = state.instrument, context = ViewKind.PATTERN))
    }

    private fun moveWritePosition(rows: Int) {
        val sel = session.selection
        var order = sel.order
        var row = sel.row + rows
        while (row < 0 && order > 0) { order--; row += song.rowCount(song.patternAt(order)) }
        while (row >= song.rowCount(song.patternAt(order)) && order < song.orders.size - 1) { row -= song.rowCount(song.patternAt(order)); order++ }
        session.select(sel.copy(order = order, row = row, channel = state.channel, context = ViewKind.PATTERN))
    }

    /** Plays [key]; in write mode also writes it at the shared pattern cursor and advances by the step. */
    fun keyDown(key: Int) {
        state.kbKey = key.coerceIn(0, KEYS - 1)
        if (!keyPlayable(state.kbKey)) { session.note("Above ${Songs.noteName(Songs.NOTE_MAX)}, the highest note"); return }
        val note = keyNote(state.kbKey)
        keyHeld = true
        playKey(note)
        if (state.entryMode) writeFromKeyboard(note)
    }

    /** Touch glide while a key is held: plays the new key (if in range) without writing it. */
    fun glideTo(key: Int) {
        state.kbKey = key.coerceIn(0, KEYS - 1)
        if (keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey))
    }

    /** A key's pitch on the keyboard channel: in a tuned song, the nearest reachable degree (note, bend). */
    private fun keyPitch(note: Int): org.thortracker.model.TunedPitch? {
        val map = tuningFor(state.channel) ?: return null
        return map.nearest(note, 0).takeIf { it >= 0 }?.let { map.pitches[it] }
    }

    private fun playKey(note: Int) {
        val p = keyPitch(note)
        if (p != null) startAudition(state.channel, p.note, keyboardSound(), p.detune) else startAudition(state.channel, note, keyboardSound())
    }

    /** Touch key release. */
    fun touchKeyUp() {
        keyHeld = false
        stopAudition()
    }

    private fun writeFromKeyboard(note: Int) {
        val sel = session.selection
        val order = sel.order.coerceIn(0, song.orders.size - 1)
        val p = song.patternAt(order)
        val row = sel.row.coerceIn(0, song.rowCount(p) - 1)
        val old = song.cellAt(p, state.channel, row)
        val cell = if (note == Cell.CUT) {
            if (old.hasEffect) { session.note("That row has an effect; clear it first: OFF uses the effect slot (as E00)"); return }
            Cell(note = Cell.CUT)
        } else {
            val pitch = keyPitch(note)
            if (pitch == null) old.copy(note = note, instrument = keyboardSound())
            else EditorSession.tuned(old.copy(instrument = keyboardSound()), pitch)?.also {
                state.entry.tuned[state.channel] = pitch.note to pitch.detune
                session.note("${session.tuningMap?.tuning?.name}: wrote ${session.tuningMap?.label(pitch)} (${"%+.1f".format(pitch.error)}¢)")
            } ?: run {
                session.note("That row's effect uses the slot tuned notes need for 40y; clear it first")
                return
            }
        }
        session.setCell(p, state.channel, row, cell)
        if (note >= 0) lastNote[state.channel] = note
        moveWritePosition(session.step)
    }

    private fun keyboardCommand(a: Action): Boolean {
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> setKeyboardChannel(state.channel + if (a == Action.NEXT_CHANNEL) 1 else -1)
            else -> return false
        }
        return true
    }

    // ---- tonal playground -------------------------------------------------------------------------

    val sketchGuide: TonalGuide
        get() {
            val scale = state.sketchScale ?: patternScale
            val cached = cachedSketchGuide
            if (cached != null && cached.scale == scale) return cached
            if (state.kind == ViewKind.SKETCH) stopAudition()
            return TonalGuide(scale).also { cachedSketchGuide = it }
        }

    val sketchAuditioning: Boolean get() = state.kind == ViewKind.SKETCH && auditioning

    fun sketchNote(index: Int): Int =
        (state.octave - ViewState.MIN_OCTAVE) * 12 + sketchGuide.scale.root + sketchGuide.tones[index].interval

    /** Keep the entire ascending voicing in the hardware range, rather than clipping individual notes. */
    fun sketchChordNotes(): List<Int> {
        val guide = sketchGuide
        val chord = guide.chords.getOrNull(state.sketchChord) ?: return emptyList()
        val base = (state.octave - ViewState.MIN_OCTAVE) * 12 + guide.scale.root
        val shift = if (base + chord.intervals.last() > Songs.NOTE_MAX) 12 else 0
        return chord.intervals.map { base + it - shift }
    }

    fun sketchSetField(index: Int) {
        sketchRelease()
        state.sketchRow = SKETCH_SETTINGS
        state.sketchField = index.coerceIn(0, SKETCH_FIELDS.lastIndex)
    }

    fun sketchAdjustField(delta: Int) {
        if (delta == 0) return
        sketchRelease()
        val scale = sketchGuide.scale
        when (state.sketchField) {
            0 -> state.sketchScale = scale.copy(root = Math.floorMod(scale.root + delta, 12))
            1 -> state.sketchScale = scale.copy(kind = ScaleKind.entries[Math.floorMod(scale.kind.ordinal + delta, ScaleKind.entries.size)])
            2 -> sketchChannel(delta)
            3 -> state.instrument = (state.instrument + delta).coerceIn(1, EditorSession.BANK_SIZE)
            4 -> state.octave = (state.octave + delta).coerceIn(ViewState.MIN_OCTAVE, ViewState.KEYBOARD_MAX_OCTAVE)
            SKETCH_CHORDS_FIELD -> {
                state.sketchCircle = !state.sketchCircle
                if (state.sketchCircle) sketchCircleSync()
            }
        }
        normalise()
    }

    fun sketchUsePatternKey() {
        sketchRelease()
        state.sketchScale = null
        normalise()
        sketchCircleSync()
        session.note("Sketch follows the pattern key; no notes changed")
    }

    fun sketchSelectChord(index: Int) {
        state.sketchRow = SKETCH_CHORDS
        state.sketchChord = index.coerceIn(0, (sketchGuide.chords.size - 1).coerceAtLeast(0))
    }

    fun sketchSelectTone(index: Int) {
        state.sketchRow = SKETCH_NOTES
        state.sketchTone = index.coerceIn(0, sketchGuide.tones.lastIndex)
    }

    /**
     * Sounds the current Sketch item while held: the selected chord's arpeggio (or, in circle mode, the circle
     * key's tonic triad) on the settings and chords rows, the selected note, or the phrase looping at song tempo.
     */
    fun sketchListen() {
        normalise()
        when (state.sketchRow) {
            SKETCH_NOTES -> startAudition(state.channel, sketchNote(state.sketchTone), state.instrument)
            SKETCH_PHRASE -> {
                auditioning = true
                session.auditionSequence(state.channel, sketchPhrase.steps, state.instrument, sketchStepMillis())
            }
            else -> {
                val notes = if (state.sketchCircle) sketchCircleTriad() else sketchChordNotes()
                if (notes.isEmpty()) {
                    stopAudition()
                    session.note("Choose a scale in Sketch to explore its chords")
                    return
                }
                auditioning = true
                session.auditionSequence(state.channel, notes, state.instrument)
            }
        }
    }

    fun sketchRelease() = stopAudition()

    /** A held A listens, except on the settings row and on phrase controls other than the strip. */
    private fun sketchEditPress() {
        when (state.sketchRow) {
            SKETCH_SETTINGS -> Unit
            SKETCH_PHRASE -> if (state.sketchPhraseField == PHRASE_STRIP) sketchListen()
            else -> sketchListen()
        }
    }

    /** Tap A: use the circle key, or activate the phrase control under the cursor. */
    private fun sketchTap() {
        when (state.sketchRow) {
            SKETCH_CHORDS -> if (state.sketchCircle) sketchCircleSetKey()
            SKETCH_PHRASE -> sketchPhraseActivate()
        }
    }

    /** B: on the circle, switch between a major key and its relative minor; elsewhere it only stops a preview. */
    private fun sketchClear() {
        if (state.sketchRow == SKETCH_CHORDS && state.sketchCircle) {
            state.sketchCircleMinor = !state.sketchCircleMinor
            if (auditionHeld) sketchListen() else sketchRelease()
        } else sketchRelease()
    }

    private fun sketchDirection(dx: Int, dy: Int) {
        if (editHeld) editUsed = true
        val row = state.sketchRow
        if (row == SKETCH_SETTINGS && editHeld) {
            sketchAdjustField(dx + dy)
            return
        }
        if (row == SKETCH_PHRASE && editHeld && dy == 0 && state.sketchPhraseField in PHRASE_LENGTH..PHRASE_CHORD_TONES) {
            sketchAdjustPhrase(dx)
            return
        }
        if (dy != 0) {
            state.sketchRow = (row - dy).coerceIn(0, SKETCH_ROWS - 1)
            if (state.sketchRow == SKETCH_SETTINGS) sketchRelease()
        } else when (row) {
            SKETCH_SETTINGS -> state.sketchField = (state.sketchField + dx).coerceIn(0, SKETCH_FIELDS.lastIndex)
            SKETCH_CHORDS -> if (state.sketchCircle) sketchCircleSelect(state.sketchCircleKey + dx, state.sketchCircleMinor)
                else sketchSelectChord(state.sketchChord + dx)
            SKETCH_NOTES -> sketchSelectTone(state.sketchTone + dx)
            // Moving between phrase controls never restarts or stops a phrase that is already looping.
            else -> { state.sketchPhraseField = (state.sketchPhraseField + dx).coerceIn(0, PHRASE_FIELDS.lastIndex); return }
        }
        if (state.sketchRow == SKETCH_SETTINGS || (row == SKETCH_PHRASE && state.sketchRow == SKETCH_PHRASE)) return
        val listen = auditionHeld || (editHeld && (state.sketchRow != SKETCH_PHRASE || state.sketchPhraseField == PHRASE_STRIP))
        if (listen) sketchListen() else if (editHeld) sketchRelease()
    }

    private fun sketchChannel(delta: Int) {
        val channels = (0 until song.channelCount()).filter { kindOf(it) != InstrumentKind.NOISE }
        if (channels.isEmpty()) return
        val index = channels.indexOf(state.channel).coerceAtLeast(0)
        state.channel = channels[(index + delta).coerceIn(0, channels.lastIndex)]
        state.instrument = pen(kindOf(state.channel))
        syncLock()
    }

    private fun sketchCommand(a: Action): Boolean = when (a) {
        Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
            sketchRelease()
            sketchChannel(if (a == Action.NEXT_CHANNEL) 1 else -1)
            true
        }
        else -> false
    }

    // circle of fifths

    /** The key under the circle cursor: its major key, or the relative natural minor on the inner ring. */
    fun sketchCircleScale(position: Int = state.sketchCircleKey, minor: Boolean = state.sketchCircleMinor): Scale {
        val major = circleRoot(position)
        return if (minor) Scale((major + 9) % 12, ScaleKind.NATURAL_MINOR) else Scale(major, ScaleKind.MAJOR)
    }

    /**
     * Where [scale]'s tonic sits on the circle: (position, on the minor ring), from the quality of its third, so
     * D Dorian sits with D minor. Null for Chromatic and scales with neither a minor nor a major third.
     */
    fun sketchCirclePlace(scale: Scale = sketchGuide.scale): Pair<Int, Boolean>? {
        if (scale.isChromatic) return null
        val minor = scale.kind.hasInterval(3) && !scale.kind.hasInterval(4)
        if (!minor && !scale.kind.hasInterval(4)) return null
        return circlePosition(scale.root + if (minor) 3 else 0) to minor
    }

    /** Root-position tonic triad of the circle cursor's key at the Sketch octave, shifted down together to fit. */
    fun sketchCircleTriad(): List<Int> {
        val scale = sketchCircleScale()
        val base = (state.octave - ViewState.MIN_OCTAVE) * 12 + scale.root
        val shift = if (base + 7 > Songs.NOTE_MAX) 12 else 0
        return listOf(0, if (state.sketchCircleMinor) 3 else 4, 7).map { base + it - shift }
    }

    fun sketchCircleSelect(position: Int, minor: Boolean) {
        state.sketchRow = SKETCH_CHORDS
        state.sketchCircleKey = Math.floorMod(position, 12)
        state.sketchCircleMinor = minor
    }

    /** Makes the circle cursor's key (Major or Natural minor) Sketch's local key. The song is untouched. */
    fun sketchCircleSetKey() {
        val scale = sketchCircleScale()
        sketchRelease()
        state.sketchScale = scale
        normalise()
        session.note("Sketch key ${scale.label}; the pattern and its notes are unchanged")
    }

    /** Puts the circle cursor on the current key, when it has a place on the circle. */
    private fun sketchCircleSync() {
        val (position, minor) = sketchCirclePlace() ?: return
        state.sketchCircleKey = position
        state.sketchCircleMinor = minor
    }

    // phrases

    /** The current phrase, generated from the seed on first use. */
    val sketchPhrase: Phrase get() = state.sketchPhrase ?: sketchGenerate().also { state.sketchPhrase = it }

    /** One pattern row at the song tempo: ticks per row at the GB vblank rate. */
    fun sketchStepMillis(): Long = Math.round(song.ticksPerRow * 1000.0 / TICK_HZ)

    /** The chord whose tones generated phrases land on at strong rows: the selected triad while Chord tones is on. */
    fun sketchPhraseChord(): TonalGuide.Chord? =
        if (state.sketchPhraseSettings.chordTones) sketchGuide.chords.getOrNull(state.sketchChord) else null

    private fun sketchGenerate(): Phrase = Phrase.generate(
        sketchGuide, (state.octave - ViewState.MIN_OCTAVE) * 12 + sketchGuide.scale.root, sketchPhraseChord(),
        state.sketchPhraseSettings, Random(state.sketchPhraseSeed),
    )

    fun sketchSetPhraseField(index: Int) {
        if (state.sketchRow != SKETCH_PHRASE) sketchRelease()
        state.sketchRow = SKETCH_PHRASE
        state.sketchPhraseField = index.coerceIn(0, PHRASE_FIELDS.lastIndex)
    }

    /** Steps the phrase setting under the cursor by [delta] and regenerates the phrase from the current seed. */
    fun sketchAdjustPhrase(delta: Int) {
        if (delta == 0) return
        val s = state.sketchPhraseSettings
        state.sketchPhraseSettings = when (state.sketchPhraseField) {
            PHRASE_LENGTH -> s.copy(length = (s.length + delta).coerceIn(PhraseSettings.MIN_LENGTH, PhraseSettings.MAX_LENGTH))
            PHRASE_RHYTHM -> s.copy(rhythm = PhraseRhythm.entries[Math.floorMod(s.rhythm.ordinal + delta, PhraseRhythm.entries.size)])
            PHRASE_MOTION -> s.copy(motion = PhraseMotion.entries[Math.floorMod(s.motion.ordinal + delta, PhraseMotion.entries.size)])
            PHRASE_CHORD_TONES -> s.copy(chordTones = !s.chordTones)
            else -> return
        }
        if (state.sketchPhraseSettings == s) return
        sketchNewPhrase(sketchGenerate())
    }

    /** Activates the phrase control under the cursor (tap A or a touch). */
    fun sketchPhraseActivate() {
        when (state.sketchPhraseField) {
            PHRASE_RHYTHM, PHRASE_MOTION, PHRASE_CHORD_TONES -> sketchAdjustPhrase(1)
            PHRASE_ANOTHER -> {
                state.sketchPhraseSeed = nextSeed(state.sketchPhraseSeed)
                sketchNewPhrase(sketchGenerate())
            }
            PHRASE_VARY -> {
                state.sketchPhraseSeed = nextSeed(state.sketchPhraseSeed)
                val keepRhythm = state.sketchKeepRhythm
                val keepPitches = state.sketchKeepPitches && !keepRhythm
                sketchNewPhrase(Phrase.vary(sketchPhrase, sketchGuide, sketchPhraseChord(), state.sketchPhraseSettings,
                    Random(state.sketchPhraseSeed), keepRhythm, keepPitches))
                session.note(when {
                    keepRhythm -> "Variation: new pitches on the same rhythm"
                    keepPitches -> "Variation: the same notes on a new rhythm"
                    else -> "Variation: a few notes moved"
                })
            }
            PHRASE_KEEP_RHYTHM -> {
                state.sketchKeepRhythm = !state.sketchKeepRhythm
                if (state.sketchKeepRhythm) state.sketchKeepPitches = false
            }
            PHRASE_KEEP_PITCHES -> {
                state.sketchKeepPitches = !state.sketchKeepPitches
                if (state.sketchKeepPitches) state.sketchKeepRhythm = false
            }
            PHRASE_FROM_COPIED -> sketchPhraseFromCopied()
            PHRASE_INSERT -> sketchInsertPhrase()
        }
    }

    /** Replaces the phrase; a phrase that is looping restarts with the new one. */
    private fun sketchNewPhrase(phrase: Phrase) {
        state.sketchPhrase = phrase
        if (auditioning && state.sketchRow == SKETCH_PHRASE) sketchListen()
    }

    private fun sketchPhraseFromCopied() {
        val cells = (session.clipboard as? Clip.Cells)?.tracks?.firstOrNull()
        if (cells.isNullOrEmpty()) {
            session.note("Copy pattern cells first (${k(Action.COPY)}); their first channel becomes the phrase")
            return
        }
        val length = cells.size.coerceIn(PhraseSettings.MIN_LENGTH, PhraseSettings.MAX_LENGTH)
        val phrase = Phrase.fromCells(cells, length)
        if (phrase.onsets.isEmpty()) {
            session.note("The first copied channel has no notes in its first $length rows")
            return
        }
        state.sketchPhraseSettings = state.sketchPhraseSettings.copy(length = length)
        sketchNewPhrase(phrase)
        session.note("Phrase from copied cells: $length rows, ${phrase.onsets.size} notes" +
            if (cells.size > length) " (the first $length of ${cells.size} rows)" else "")
    }

    /**
     * Writes the phrase at the shared selection (its order's pattern and row) on Sketch's channel with Sketch's
     * sound as one undo step, then moves the selection to the row after it.
     */
    private fun sketchInsertPhrase() {
        sketchRelease()
        normalise()
        val sel = session.selection
        val pattern = song.patternAt(sel.order)
        val phrase = sketchPhrase
        val span = minOf(phrase.length, song.rowCount(pattern) - sel.row)
        val ch = state.channel
        val written = session.insertPhrase(pattern, ch, sel.row, phrase.steps, state.instrument)
        val skipped = span - written
        session.select(sel.copy(row = sel.row + span, channel = ch, context = ViewKind.PATTERN))
        session.note(buildString {
            append("Inserted phrase: ${Songs.channelNames(song)[ch]}, pattern ${EditorSession.hex2(pattern)} rows ")
            append("${EditorSession.hex2(sel.row)}-${EditorSession.hex2(sel.row + span - 1)}")
            if (span < phrase.length) append(", cut at the pattern end")
            if (skipped > 0) append("; $skipped note${if (skipped == 1) "" else "s"} skipped: the row's effect slot is needed for the tuning bend")
        })
    }

    private fun sketchHints(h: MutableList<Hint>) {
        val listen = "${k(Action.EDIT)} / ${k(Action.AUDITION)}"
        when (state.sketchRow) {
            SKETCH_SETTINGS -> {
                h += Hint(lr(), "Setting")
                h += Hint(keys.held(Action.EDIT, keys.dpad()),
                    if (state.sketchField == SKETCH_CHORDS_FIELD) "Triads / circle of fifths" else "Change ${SKETCH_FIELDS[state.sketchField].lowercase()}")
            }
            SKETCH_CHORDS -> if (state.sketchCircle) {
                h += Hint(lr(), "Move by fifths")
                h += Hint(k(Action.CLEAR), if (state.sketchCircleMinor) "Major key" else "Relative minor")
                h += Hint(listen, "Hold: tonic triad")
                h += Hint(k(Action.EDIT), "Tap: use this key in Sketch")
            } else {
                h += Hint(lr(), "Chord")
                h += Hint(listen, "Hold: arpeggio")
            }
            SKETCH_NOTES -> {
                h += Hint(lr(), "Note")
                h += Hint(listen, "Hold: listen")
            }
            else -> {
                h += Hint(lr(), "Phrase control")
                when (val f = state.sketchPhraseField) {
                    PHRASE_STRIP -> h += Hint(listen, "Hold: loop the phrase")
                    in PHRASE_LENGTH..PHRASE_CHORD_TONES -> {
                        h += Hint(editLr(), "Change ${PHRASE_FIELDS[f].lowercase()}")
                        h += Hint(k(Action.AUDITION), "Hold: loop the phrase")
                    }
                    else -> {
                        h += Hint(k(Action.EDIT), when (f) {
                            PHRASE_ANOTHER -> "New phrase with these settings"
                            PHRASE_VARY -> when {
                                state.sketchKeepRhythm -> "Vary: new pitches, same rhythm"
                                state.sketchKeepPitches -> "Vary: same notes, new rhythm"
                                else -> "Vary: move a few notes"
                            }
                            PHRASE_KEEP_RHYTHM -> if (state.sketchKeepRhythm) "Vary changes anything" else "Vary keeps the rhythm"
                            PHRASE_KEEP_PITCHES -> if (state.sketchKeepPitches) "Vary changes anything" else "Vary keeps the notes"
                            PHRASE_FROM_COPIED -> "Phrase from copied cells"
                            else -> "Write at the pattern cursor (one undo step)"
                        })
                        h += Hint(k(Action.AUDITION), "Hold: loop the phrase")
                    }
                }
            }
        }
        h += Hint(ud(), "Settings / chords / notes / phrase")
        h += Hint(k(Action.MARK), "Use pattern key")
        h += Hint(channels(), "Tonal channel")
    }

    // ---- help --------------------------------------------------------------------------------------

    private fun helpDirection(dx: Int, dy: Int) {
        if (dx != 0) { state.helpPage = Math.floorMod(state.helpPage + dx, HELP_PAGES.size); state.scroll = 0 }
        else state.scroll = (state.scroll - dy * 3).coerceAtLeast(0)
    }

    private fun helpCommand(a: Action): Boolean {
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                state.helpPage = Math.floorMod(state.helpPage + if (a == Action.NEXT_CHANNEL) 1 else -1, HELP_PAGES.size)
                state.scroll = 0
            }
            else -> return false
        }
        return true
    }

    // ---- hints -------------------------------------------------------------------------------------

    private fun entryModeNote(): String {
        val edit = keys.label(Action.EDIT)
        return if (state.entryMode) "Write mode: $edit writes notes at the pattern cursor" else "Listen mode: $edit only plays"
    }

    private fun k(a: Action): String = keys.label(a, state.kind)
    private fun lr(): String = keys.pair(Action.LEFT, Action.RIGHT, state.kind)
    private fun ud(): String = keys.pair(Action.UP, Action.DOWN, state.kind)
    private fun channels(): String = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL, state.kind)
    private fun copyPaste(): String = keys.pair(Action.COPY, Action.PASTE, state.kind)
    private fun octaves(): String = keys.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP, state.kind)
    private fun editLr(): String = keys.held(Action.EDIT, lr())
    private fun editUd(): String = keys.held(Action.EDIT, ud())

    /** Contextual hints for the current view, field and held buttons. Key names follow the current [keys]. */
    fun hints(): List<Hint> {
        normalise()
        val h = ArrayList<Hint>()
        when (state.kind) {
            ViewKind.PATTERN -> patternHints(h)
            ViewKind.SONG -> songHints(h)
            ViewKind.INSTRUMENT -> soundHints(h)
            ViewKind.WAVE -> waveHints(h)
            ViewKind.KEYBOARD -> keyboardHints(h)
            ViewKind.SKETCH -> sketchHints(h)
            ViewKind.HELP -> {
                h += Hint(ud(), "Scroll")
                h += Hint(lr(), "Page: ${HELP_PAGES[state.helpPage]}")
            }
        }
        return h
    }

    private fun patternHints(h: MutableList<Hint>) {
        val c = cell
        val ch = state.channel
        if (state.scalePanel) {
            h += Hint(ud(), "Root / scale / length / fit")
            when (state.scaleField) {
                0 -> { h += Hint(lr(), "Root note"); h += Hint(k(Action.CLEAR), "Root C") }
                1 -> { h += Hint(lr(), "Scale"); h += Hint(k(Action.CLEAR), "Chromatic") }
                2 -> { h += Hint(lr(), "Length ±1"); h += Hint(channels(), "Length ±8"); h += Hint(k(Action.CLEAR), "64 rows") }
                else -> h += Hint(k(Action.EDIT), "Fit notes to ${patternScale.label}")
            }
            if (state.scaleField != SCALE_FIT_FIELD) h += Hint(k(Action.EDIT), "Done")
            h += Hint(k(Action.SCALE), "Close")
            h += Hint(k(Action.AUDITION), "Listen")
            return
        }
        when {
            editHeld -> when (state.field) {
                0 -> {
                    val scale = noteScale(ch)
                    h += Hint(editLr(), when {
                        kindOf(ch) == InstrumentKind.NOISE -> "Noise pitch (no scale)"
                        tuningFor(ch) != null -> "${tuningFor(ch)!!.tuning.name} degree"
                        scale.isChromatic -> "Semitone"
                        else -> "${scale.label} note"
                    })
                    h += Hint(editUd(), if (tuningFor(ch) != null) "Period" else "Octave")
                }
                1 -> { h += Hint(editLr(), "Sound ±1"); h += Hint(editUd(), "Sound ±4") }
                2 -> { h += Hint(editLr(), "Effect type: · none, then 0-F") }
                else -> { h += Hint(editLr(), "Value ±1"); h += Hint(editUd(), "Value ±10h") }
            }
            clearHeld -> { h += Hint(keys.held(Action.CLEAR, ud()), "Jump 16 rows"); h += Hint(keys.held(Action.CLEAR, lr()), "Previous/next order") }
            markHeld -> { h += Hint(keys.held(Action.MARK, keys.dpad()), "Extend selection"); h += Hint("Release", "Keep selection") }
            else -> {
                val tap = when (state.field) {
                    0 -> if (c.note < 0) "Write note ${Songs.noteName(entryNote(ch))}" else "Hold to change note"
                    1 -> if (c.note < 0) "Needs a note" else if (c.instrument == 0) "Write sound ${EditorSession.hex2(pen(kindOf(ch)))}" else "Hold to change sound"
                    else -> when {
                        c.hasEffect -> "Hold to change effect"
                        chosenArpeggio() -> if (state.field == 2) "Hold to change effect" else "Hold to set arpeggio +x +y"
                        else -> lastEffect[ch]?.let { (e, p) ->
                            if (e == HugeRules.FX_ARPEGGIO && p == 0) "Pick arpeggio 0"
                            else "Write effect ${EditorSession.hex1(e)}${EditorSession.hex2(p)}"
                        } ?: "Hold to pick effect"
                    }
                }
                h += Hint(k(Action.EDIT), tap)
                h += Hint(k(Action.CLEAR), if (state.mark) "Clear selection" else if (state.field == 0 && c.note == Cell.EMPTY) "Place OFF" else "Clear")
                h += Hint(k(Action.AUDITION), "Listen")
                h += Hint(k(Action.MARK), if (state.mark) "Hold: extend, tap: unmark" else "Hold: select block")
                h += Hint(channels(), "Channel")
                h += Hint(copyPaste(), "Copy/paste")
                h += Hint(k(Action.SCALE), "Pattern: ${patternScale.label}, $patternLength rows")
            }
        }
    }

    private fun songHints(h: MutableList<Hint>) {
        val item = state.songItem
        when {
            item <= 1 -> { h += Hint(lr(), "Letter position"); h += Hint(editLr(), "Change letter"); h += Hint(editUd(), "Case/digits"); h += Hint(k(Action.CLEAR), "Delete letter") }
            item == 2 -> { h += Hint(editLr(), "Ticks per row ±1 (lower is faster)"); h += Hint(k(Action.CLEAR), "Reset to 6") }
            item == 3 -> { h += Hint(editLr(), "Loop target order"); h += Hint(k(Action.CLEAR), "Loop to start") }
            else -> {
                h += Hint(k(Action.EDIT), ORDER_FIELD_HINTS[state.songField])
                if (state.songField == 0) h += Hint(editLr(), "Pattern ±1")
                h += Hint(k(Action.CLEAR), "Delete order")
                h += Hint(k(Action.AUDITION), "Play from this order or stop")
                h += Hint(k(Action.DUPLICATE), "Clone pattern")
                h += Hint(copyPaste(), "Copy/paste pattern")
            }
        }
    }

    private fun soundHints(h: MutableList<Hint>) {
        when (state.instField) {
            0 -> { h += Hint(lr(), "Sound slot"); h += Hint(editUd(), "Bank"); h += Hint(channels(), "Bank"); h += Hint(k(Action.CLEAR), "Reset sound") }
            1 -> { h += Hint(lr(), "Letter position"); h += Hint(editLr(), "Change letter"); h += Hint(k(Action.CLEAR), "Delete letter") }
            else -> {
                val p = soundParams.getOrNull(state.instField - 2)
                if (p != null) h += Hint(editLr(), p.help)
                h += Hint(lr(), "Sound slot")
                h += Hint(k(Action.CLEAR), "Default value")
            }
        }
        h += Hint(k(Action.AUDITION), "Listen")
        h += Hint(octaves(), "Listen at ${Songs.noteName(defaultNote())}")
        h += Hint(copyPaste(), "Copy/paste sound")
    }

    private fun waveHints(h: MutableList<Hint>) {
        if (state.waveRow == 0) {
            h += Hint(lr(), "Tool")
            h += Hint(k(Action.EDIT), if (state.waveCol == 8) "Wave sound ${EditorSession.hex2(waveTargetSound())} plays this wave" else WAVE_TOOLS[state.waveCol])
            h += Hint(k(Action.DOWN), "Back to drawing")
        } else {
            h += Hint(keys.dpad(), "Move pen")
            h += Hint(k(Action.EDIT), "Hold: draw at pen height")
            h += Hint(k(Action.CLEAR), "Sample to 0")
            h += Hint("${k(Action.UP)} at top", "Tools")
        }
        h += Hint(channels(), "Wave")
        h += Hint(k(Action.AUDITION), "Listen")
        h += Hint(octaves(), "Listen at ${Songs.noteName(defaultNote())}")
    }

    private fun keyboardHints(h: MutableList<Hint>) {
        if (state.kbRow == 0) {
            h += Hint(lr(), "Setting")
            h += Hint(editLr(), "Change ${STRIP_FIELDS[state.kbField].lowercase()}")
            h += Hint(k(Action.DOWN), "Keys")
        } else {
            h += Hint(k(Action.EDIT), if (state.entryMode) "Write note and advance" else "Play note")
            h += Hint(k(Action.AUDITION), "Listen only")
            h += Hint(k(Action.MARK), if (state.entryMode) "Switch to listen" else "Switch to write")
            if (state.entryMode) { h += Hint(k(Action.CLEAR), "Write OFF"); h += Hint(k(Action.DOWN), "Skip step") }
            h += Hint(k(Action.UP), "Settings")
            h += Hint(channels(), "Channel")
            h += Hint(octaves(), "Octave ${state.octave}")
        }
    }

    companion object {
        const val FIELDS = 4
        /** Scale panel rows: 0 root, 1 scale, 2 length, 3 fit notes to the scale. */
        const val SCALE_FIT_FIELD = 3
        const val KEYS = 25
        /** Effect picker position before effect 0: the row has no effect (000). */
        private const val NO_EFFECT = -1
        private const val OFF_TAKES_EFFECT_SLOT = "An OFF row has no effect slot left: OFF is exported as E00"
        const val OFF_EXPLAINED = "OFF: instant silence (E00), no release tail; for a fade use a sound's falling envelope"

        /** Identifies one pattern cell for view-local markers: pattern < 256, channel < 4, row < 64. */
        fun cellKey(pattern: Int, channel: Int, row: Int): Int = (pattern shl 8) or (channel shl 6) or row
        val PATTERN_CONTEXT = arrayOf(ViewKind.KEYBOARD, ViewKind.INSTRUMENT, ViewKind.HELP, ViewKind.HELP)
        val ORDER_FIELDS = listOf("Pattern", "Repeat", "New", "Clone", "Delete", "Earlier", "Later", "Loop here")
        val ORDER_FIELD_HINTS = listOf(
            "Select this order for editing",
            "Repeat this pattern in a new order below",
            "Add a new empty pattern below",
            "Copy this pattern into a new one below",
            "Delete this order",
            "Move this order earlier",
            "Move this order later",
            "Loop the song back to this order",
        )
        val WAVE_TOOLS = listOf("Sine", "Triangle", "Saw", "Square", "Invert", "Shift left", "Shift right", "Smooth", "Use in sound")
        val STRIP_FIELDS = listOf("Mode", "Channel", "Sound", "Octave", "Step")
        const val SKETCH_ROWS = 4
        private const val SKETCH_SETTINGS = 0
        private const val SKETCH_CHORDS = 1
        private const val SKETCH_NOTES = 2
        private const val SKETCH_PHRASE = 3
        val SKETCH_FIELDS = listOf("Root", "Scale", "Channel", "Sound", "Octave", "Chords")
        /** The settings-strip field switching the chords row between triads and the circle of fifths. */
        const val SKETCH_CHORDS_FIELD = 5
        val PHRASE_FIELDS = listOf("Phrase", "Length", "Rhythm", "Motion", "Chord tones", "Another", "Vary",
            "Keep rhythm", "Keep pitches", "From copied", "Insert")
        const val PHRASE_STRIP = 0
        const val PHRASE_LENGTH = 1
        const val PHRASE_RHYTHM = 2
        const val PHRASE_MOTION = 3
        const val PHRASE_CHORD_TONES = 4
        const val PHRASE_ANOTHER = 5
        const val PHRASE_VARY = 6
        const val PHRASE_KEEP_RHYTHM = 7
        const val PHRASE_KEEP_PITCHES = 8
        const val PHRASE_FROM_COPIED = 9
        const val PHRASE_INSERT = 10
        /** hUGEDriver ticks once per GB vblank. */
        const val TICK_HZ = 59.73

        /** Major-key root (pitch class) at circle-of-fifths [position] (0 = C, 1 = G, ... 11 = F). */
        fun circleRoot(position: Int): Int = Math.floorMod(position * 7, 12)

        /** Circle-of-fifths position of major-key root [root]; 7 is its own inverse modulo 12. */
        fun circlePosition(root: Int): Int = Math.floorMod(root * 7, 12)

        private fun nextSeed(seed: Long): Long = seed * 6364136223846793005L + 1442695040888963407L
        val HELP_PAGES = listOf("Controls", "Effects", "Song check")
    }
}
