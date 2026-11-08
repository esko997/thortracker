package org.thortracker.editor

import org.thortracker.model.Cell
import org.thortracker.model.Phrase
import org.thortracker.model.PhraseMotion
import org.thortracker.model.PhraseRhythm
import org.thortracker.model.PhraseSettings
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Songs
import org.thortracker.model.TonalGuide
import org.thortracker.model.NumberText
import org.thortracker.model.TunedPitch
import org.thortracker.model.TuningMap
import org.thortracker.system.EffectSpec
import org.thortracker.system.SoundSystem
import org.thortracker.system.SystemView
import kotlin.random.Random
import kotlin.math.roundToLong
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

    internal val song get() = session.song
    private val system: SoundSystem get() = song.system

    /** The system's own editor for the showing view (Instrument, Wave), or null for a shared view. */
    private fun systemView(): SystemView? = system.view(state.kind)

    // ---- entry points ----------------------------------------------------------------------------

    fun press(a: Action): Boolean {
        normalise()
        if (state.scalePanel) scalePanelPress(a)?.let { return it }
        return when (a) {
            Action.SCALE -> { if (state.kind == ViewKind.PATTERN && !holding) openScalePanel(); state.kind == ViewKind.PATTERN }
            Action.UNDO -> { historyStep(true); true }
            Action.REDO -> { historyStep(false); true }
            Action.OCTAVE_DOWN, Action.OCTAVE_UP -> {
                state.octave = (state.octave + if (a == Action.OCTAVE_UP) 1 else -1).coerceIn(minOctave(), maxOctave())
                val own = systemView()
                when (state.kind) {
                    ViewKind.SKETCH -> if (auditioning && state.sketchRow != SKETCH_PHRASE) sketchListen()
                    ViewKind.KEYBOARD -> if (auditioning && keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey))
                    else -> if (own != null && auditioning) own.audition(this)
                }
                session.note(if (own != null) "Listening at ${system.noteName(defaultNote())}" else "Octave ${state.octave}")
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
        val system = s.system
        // A song of another system may lack the showing view (no Wave view without wave tables).
        if (state.kind !in system.views) state.switchKind(ViewKind.PATTERN)
        state.order = state.order.coerceIn(0, s.orders.size - 1)
        state.channel = state.channel.coerceIn(0, s.channelCount() - 1)
        state.row = state.row.coerceIn(0, s.rowCount(s.patternAt(state.order)) - 1)
        state.field = state.field.coerceIn(0, FIELDS - 1)
        state.instrument = state.instrument.coerceIn(1, maxSlots())
        state.octave = state.octave.coerceIn(minOctave(), maxOctave())
        state.songItem = state.songItem.coerceIn(0, ViewState.SONG_HEADER_ITEMS + s.orders.size - 1)
        state.markOrder = state.markOrder.coerceIn(0, s.orders.size - 1)
        if (state.mark && state.markOrder != state.order) state.mark = false
        // Restored workspaces may carry any values; keep every view-local cursor addressable.
        state.songField = state.songField.coerceIn(0, ORDER_FIELDS.size - 1)
        for (kind in system.views) system.view(kind)?.normalise(this)
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
            if (!tonal(state.channel)) state.channel = system.channels.indexOfFirst { it.tonal }.coerceAtLeast(0)
            state.sketchRow = state.sketchRow.coerceIn(0, SKETCH_ROWS - 1)
            state.sketchField = state.sketchField.coerceIn(0, SKETCH_FIELDS.lastIndex)
            state.sketchChord = state.sketchChord.coerceIn(0, (sketchGuide.chords.size - 1).coerceAtLeast(0))
            state.sketchTone = state.sketchTone.coerceIn(0, sketchGuide.tones.lastIndex)
            state.sketchCircleKey = state.sketchCircleKey.mod(12)
            state.sketchPhraseField = state.sketchPhraseField.coerceIn(0, PHRASE_FIELDS.lastIndex)
            if (state.sketchKeepRhythm) state.sketchKeepPitches = false
        }
    }

    /** After explicit navigation in a locked view, the lock moves to the subject the user chose. */
    fun syncLock() {
        if (state.lockedSubject < 0) return
        state.lockedSubject = systemView()?.subject(state) ?: when (state.kind) {
            ViewKind.PATTERN, ViewKind.KEYBOARD, ViewKind.SKETCH -> state.channel
            else -> state.lockedSubject
        }
    }

    private fun gestureLabel(): String = systemView()?.gestureLabel ?: when (state.kind) {
        ViewKind.PATTERN -> arrayOf("Edit note", "Edit sound", "Edit effect", "Edit effect value")[state.field]
        ViewKind.SONG -> "Edit song"
        ViewKind.KEYBOARD -> "Keyboard"
        ViewKind.SKETCH -> "Sketch"
        ViewKind.HELP -> "Help"
        else -> ""
    }

    /** The instrument bank [channel] plays from. */
    private fun bankOf(channel: Int): Int = system.channels[channel].bank

    /** True when [channel]'s notes are pitches (scales, tunings and Sketch apply); false for noise. */
    private fun tonal(channel: Int): Boolean = system.channels[channel].tonal

    private fun slots(bank: Int): Int = system.banks[bank].slots

    private fun maxSlots(): Int = system.banks.maxOf { it.slots }

    /** The entry sound of [bank]: the slot new notes on its channels get (kept within the bank after a system change). */
    fun pen(bank: Int): Int = (penSound[bank] ?: 1).coerceIn(1, slots(bank))

    internal fun setPen(bank: Int, slot: Int) {
        penSound[bank] = slot.coerceIn(1, slots(bank))
    }

    /** Lowest and highest note of the song's system, for messages ("C-3..B-8"). */
    private fun rangeText(): String = "${system.noteName(system.noteRange.first)}..${system.noteName(system.noteRange.last)}"

    /** The listening note of the sound views: C at the current octave. */
    internal fun defaultNote(): Int = (octaveNote(state.octave)).coerceIn(system.noteRange)

    internal fun startAudition(channel: Int, note: Int, instrument: Int, detune: Int = 0) {
        if (note !in system.noteRange) return
        session.audition(channel, note, instrument, detune)
        auditioning = true
    }

    private fun stopAudition() {
        if (auditioning) { auditioning = false; session.releaseAudition() }
    }

    /** A held Edit moved something: its release is no tap. For system views. */
    internal fun useEdit() { editUsed = true }

    /** A held Clear moved something: its release clears nothing. For system views. */
    internal fun useClear() { clearUsed = true }

    /** Octave of the lowest note, in the system's note names. */
    private fun minOctave() = system.noteRange.first / 12 + system.nameOctaveOffset

    /** The C of octave [octave], counted in the system's note names (Game Boy "Octave 4" starts at its C-4). */
    private fun octaveNote(octave: Int): Int = (octave - system.nameOctaveOffset) * 12

    /** The keyboard's two-octave span is capped so only its top key can exceed the note range. */
    private fun maxOctave() = if (state.kind == ViewKind.KEYBOARD || state.kind == ViewKind.SKETCH) keyboardOctaves().last else system.noteRange.last / 12 + system.nameOctaveOffset

    /** Octaves the 25-key keyboard (and Sketch) can start at: its top key is the only one past the highest note. */
    fun keyboardOctaves(): IntRange = minOctave()..(system.noteRange.last + 1) / 12 - 2 + system.nameOctaveOffset

    private fun direction(dx: Int, dy: Int): Boolean {
        // A direction while B is held is always a jump/move, never followed by B's clear.
        if (clearHeld) clearUsed = true
        systemView()?.let { it.direction(this, dx, dy); return true }
        when (state.kind) {
            ViewKind.PATTERN -> patternDirection(dx, dy)
            ViewKind.SONG -> songDirection(dx, dy)
            ViewKind.KEYBOARD -> keyboardDirection(dx, dy)
            ViewKind.SKETCH -> sketchDirection(dx, dy)
            ViewKind.HELP -> helpDirection(dx, dy)
            else -> Unit
        }
        return true
    }

    private fun editPress() {
        if (state.kind == ViewKind.SKETCH) {
            sketchEditPress()
            return
        }
        editGesture = session.beginGesture(gestureLabel())
        val own = systemView()
        when {
            own != null -> own.editPress(this)
            state.kind == ViewKind.PATTERN -> patternEditPress()
            state.kind == ViewKind.KEYBOARD -> if (state.kbRow == 1) keyDown(state.kbKey)
        }
    }

    private fun editRelease(tap: Boolean) {
        systemView()?.let { it.editRelease(this, tap); return }
        when (state.kind) {
            ViewKind.PATTERN -> if (tap && editInserted) advance(session.step)
            ViewKind.SONG -> if (tap) songActivate()
            ViewKind.KEYBOARD -> if (state.kbRow == 1) keyHeld = false else if (tap) stripActivate()
            ViewKind.SKETCH -> if (tap) sketchTap()
            else -> Unit
        }
    }

    private fun clearTap() {
        systemView()?.let { it.clear(this); return }
        when (state.kind) {
            ViewKind.PATTERN -> patternClear()
            ViewKind.SONG -> songClear()
            ViewKind.KEYBOARD -> if (state.entryMode) writeFromKeyboard(Cell.CUT)
            ViewKind.SKETCH -> sketchClear()
            else -> Unit
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
        systemView()?.let { it.audition(this); return }
        when (state.kind) {
            ViewKind.PATTERN -> {
                val cell = song.cellAt(song.patternAt(state.order), state.channel, state.row)
                val note = if (cell.note >= 0) cell.note else entryNote(state.channel)
                val ins = if (cell.note >= 0 && cell.instrument > 0) cell.instrument else pen(bankOf(state.channel))
                startAudition(state.channel, note, ins)
            }
            ViewKind.SONG -> {
                val order = (state.songItem - ViewState.SONG_HEADER_ITEMS).coerceIn(0, song.orders.size - 1)
                session.select(session.selection.copy(order = order, row = 0, context = ViewKind.PATTERN))
                session.transport(true)
            }
            ViewKind.KEYBOARD -> if (keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey))
            ViewKind.SKETCH -> sketchListen()
            else -> Unit
        }
    }

    private fun command(a: Action): Boolean = systemView()?.command(this, a) ?: when (state.kind) {
        ViewKind.PATTERN -> patternCommand(a)
        ViewKind.SONG -> songCommand(a)
        ViewKind.KEYBOARD -> keyboardCommand(a)
        ViewKind.HELP -> helpCommand(a)
        ViewKind.SKETCH -> sketchCommand(a)
        else -> false
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
        val bank = bankOf(state.channel)
        val c = cell
        val ins = if (c.instrument > 0) c.instrument else pen(bank)
        val sel = Selection(state.order, state.row, state.channel, bank, ins, session.selection.wave, PATTERN_CONTEXT[state.field])
        session.select(system.selectSound(sel, song))
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

    /** The pattern scale note stepping follows on [channel]; noise note numbers select noise rates, so stay chromatic. */
    fun noteScale(channel: Int): Scale =
        if (!tonal(channel)) Scale.CHROMATIC else song.patterns[pattern].scale

    /**
     * Note written into an empty row: the channel's last note, else the scale root (C when chromatic) at the octave.
     * With a scale, a remembered note outside it moves to the nearest scale note.
     */
    private fun entryNote(channel: Int): Int {
        val scale = noteScale(channel)
        // A sample channel starts on the pen sound's first mapped note until a note was entered there.
        if (lastNote[channel] == null) system.mappedNotes(song, channel, pen(bankOf(channel)))?.firstOrNull()?.let { return it }
        if (scale.isChromatic) return lastNote[channel] ?: defaultNote()
        val note = lastNote[channel] ?: (defaultNote() + scale.root).coerceIn(system.noteRange)
        return scale.snap(note, system.noteRange) ?: note
    }

    /** On a sample channel: the mapped note after (dx > 0) or before [from], or null at either end. */
    private fun mappedStep(mapped: List<Int>, from: Int, dx: Int): Int? =
        if (dx > 0) mapped.firstOrNull { it > from } else mapped.lastOrNull { it < from }

    /** The song tuning when [channel] is tonal; noise channels and 12-note songs return null. */
    fun tuningFor(channel: Int): TuningMap? =
        if (!tonal(channel)) null else session.tuningMap

    /** Tuned pitch written into an empty row: the channel's last tuned pitch if still reachable, else 1/1 nearest the octave. */
    private fun entryPitch(channel: Int, map: TuningMap): TunedPitch? {
        state.entry.tuned[channel]?.let { (n, d) -> map.index(n, d).takeIf { it >= 0 }?.let { return map.pitches[it] } }
        val root = map.tuning.root
        val near = (octaveNote(state.octave) + root.mod(12)).coerceIn(system.noteRange)
        return map.nearest(near, 0).takeIf { it >= 0 }?.let { map.pitches[it] }
    }

    /**
     * Writes tuned [pitch] into the cursor cell (with sound [ins]) and auditions it. Refuses, with a message, when
     * the pitch needs a bend and the row's effect slot holds another effect.
     */
    private fun writeTuned(c: Cell, pitch: TunedPitch, ins: Int, map: TuningMap): Boolean {
        val next = system.fine.write(c.copy(instrument = ins), pitch.note, pitch.detune) ?: run {
            session.note("This row's effect ${EffectSpec.charOf(c.effect)}${EditorSession.hex2(c.param)} uses the slot tuned notes need for ${system.fine.name}; clear it first")
            return false
        }
        write(next)
        state.entry.tuned[state.channel] = pitch.note to pitch.detune
        lastNote[state.channel] = pitch.note
        startAudition(state.channel, pitch.note, ins, pitch.detune)
        session.note("${map.tuning.name}: ${map.label(pitch)}  ${system.noteName(pitch.note)}${if (pitch.detune > 0) " +${pitch.detune}" else ""}  " +
            "${NumberText.fixed(pitch.error, 1, plus = true)}¢")
        return true
    }

    /** Hold-Edit stepping in a tuned song: ←→ next/previous reachable degree, ↑↓ the same degree a period away. */
    private fun adjustTuned(c: Cell, dx: Int, dy: Int, map: TuningMap) {
        val ch = state.channel
        val ins = if (c.note < 0 || c.instrument == 0) pen(bankOf(ch)) else c.instrument
        if (map.pitches.isEmpty()) { session.note("No degree of ${map.tuning.name} is reachable within ${map.tuning.tolerance}¢"); return }
        val target = if (c.note < 0) entryPitch(ch, map) else {
            val detune = system.fine.of(c) ?: 0
            val here = map.index(c.note, detune).takeIf { it >= 0 } ?: map.nearest(c.note, detune)
            val cur = map.pitches[here]
            if (dy != 0) {
                val goal = cur.degree + dy * map.steps
                (if (dy > 0) map.pitches.firstOrNull { it.degree >= goal } else map.pitches.lastOrNull { it.degree <= goal })
                    ?.takeIf { if (dy > 0) it.degree > cur.degree else it.degree < cur.degree }
            } else if (map.index(c.note, detune) < 0) cur // first edit of an out-of-tuning note lands on the tuning
            else map.pitches.getOrNull(here + dx)
        } ?: run {
            session.note("No ${if (dx > 0 || dy > 0) "higher" else "lower"} reachable degree of ${map.tuning.name} within ${rangeText()}")
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
        val bank = bankOf(ch)
        when (state.field) {
            0 -> if (c.note < 0) {
                val map = tuningFor(ch)
                if (map != null) {
                    val pitch = entryPitch(ch, map) ?: run { session.note("No degree of ${map.tuning.name} is reachable"); return }
                    if (writeTuned(c, pitch, pen(bank), map)) editInserted = true
                } else {
                    val note = entryNote(ch)
                    write(c.copy(note = note, instrument = pen(bank)))
                    editInserted = true
                    startAudition(ch, note, pen(bank))
                    if (system.cellProblem(song, ch, cell) == null) sampleNote(ch, note, pen(bank))
                }
            } else {
                lastNote[ch] = c.note
                if (c.instrument > 0) { setPen(bank, c.instrument); state.instrument = c.instrument }
                val detune = if (tuningFor(ch) != null) system.fine.of(c) ?: 0 else 0
                startAudition(ch, c.note, if (c.instrument > 0) c.instrument else pen(bank), detune)
                sampleNote(ch, c.note, if (c.instrument > 0) c.instrument else pen(bank))
            }
            1 -> when {
                c.note < 0 -> session.note("Sounds start with a note: enter a note on this row first")
                c.instrument == 0 -> { write(c.copy(instrument = pen(bank))); editInserted = true }
                else -> { setPen(bank, c.instrument); state.instrument = c.instrument }
            }
            else -> when {
                offTakesSlot(c) -> session.note(offSlotTaken())
                !c.hasEffect && !chosenArpeggio() -> {
                    val (e, p) = lastEffectOf(ch) ?: return
                    if (e == ZERO_EFFECT && p == 0) state.chosenArpeggio = cursorKey() // 000: show the chosen arpeggio, nothing to write
                    else { write(c.copy(effect = e, param = p)); editInserted = true }
                }
                else -> lastEffect[ch] = c.effect to c.param
            }
        }
        publishPattern()
    }

    /** [c] is an OFF that is written as an effect, so its row has no effect slot to edit. */
    private fun offTakesSlot(c: Cell): Boolean = c.note == Cell.CUT && system.offEffect != null

    private fun offSlotTaken() = "An OFF row has no effect slot left: OFF is exported as ${system.offEffect}"

    /** The effect a tap re-enters on [ch], unless it was remembered from a song of another system that lacks it. */
    private fun lastEffectOf(ch: Int): Pair<Int, Int>? = lastEffect[ch]?.takeIf { (e, _) -> system.effects.any { it.code == e } }

    private fun write(c: Cell) {
        session.setCell(pattern, state.channel, state.row, c)
        problemNote()
    }

    /** "C-3: Kick · rate F": what a note on a sample channel plays, for narrow screens without the inspector. */
    private fun sampleNote(channel: Int, note: Int, instrument: Int) {
        val sample = system.noteSample(song, channel, note, instrument) ?: return
        session.note("${system.noteName(note)}: ${sample.name} · ${sample.detail}")
    }

    private fun problemNote() {
        val problem = system.cellProblem(song, state.channel, cell)
        if (problem != null) session.note(problem)
    }

    private fun adjustCell(dx: Int, dy: Int) {
        val c = cell
        val ch = state.channel
        val bank = bankOf(ch)
        when (state.field) {
            0 -> {
                tuningFor(ch)?.let { adjustTuned(c, dx, dy, it); publishPattern(); return }
                // Chromatic: semitones and octaves, clamped to the note range. With a pattern scale (tonal channels), every
                // edit lands on a scale note: steps go to the next one, octaves are exact from a scale note and
                // otherwise take the first scale note past the octave target; no edit when none is in range.
                // Setting a scale never touches notes; only edited notes move onto it.
                val scale = noteScale(ch)
                val ins = if (c.note < 0 || c.instrument == 0) pen(bank) else c.instrument
                // A sample channel's ←→ visit only the notes its sound maps; ↑↓ still move an octave.
                val mapped = system.mappedNotes(song, ch, ins)?.takeIf { it.isNotEmpty() && dx != 0 }
                val note = when {
                    c.note < 0 -> entryNote(ch)
                    mapped != null -> mappedStep(mapped, c.note, dx) ?: run {
                        session.note("No ${if (dx > 0) "higher" else "lower"} note with a sample in sound ${EditorSession.hex2(ins)}")
                        return
                    }
                    scale.isChromatic -> (c.note + dx + 12 * dy).coerceIn(system.noteRange)
                    dy != 0 -> scale.octave(c.note, dy, system.noteRange) ?: run {
                        session.note("No ${scale.label} note an octave ${if (dy > 0) "above" else "below"} ${system.noteName(c.note)} within ${rangeText()}")
                        return
                    }
                    else -> scale.step(c.note, dx, system.noteRange) ?: run {
                        session.note("No ${if (dx > 0) "higher" else "lower"} ${scale.label} note than ${system.noteName(c.note)} within ${rangeText()}")
                        return
                    }
                }
                write(c.copy(note = note, instrument = ins))
                lastNote[ch] = note
                startAudition(ch, note, ins)
                if (system.cellProblem(song, ch, cell) == null) sampleNote(ch, note, ins)
            }
            1 -> {
                if (c.note < 0) { session.note("Sounds start with a note: enter a note on this row first"); return }
                val ins = ((if (c.instrument == 0) pen(bank) else c.instrument) + dx + 4 * dy).coerceIn(1, slots(bank))
                write(c.copy(instrument = ins))
                setPen(bank, ins)
                state.instrument = ins
                startAudition(ch, c.note, ins)
            }
            2 -> {
                if (offTakesSlot(c)) { session.note(offSlotTaken()); return }
                // Picker positions: no effect, then the channel's usable codes. A stored 000 counts as "no effect"
                // unless effect 0 was picked here; picking 0 with value 00 writes nothing until a value is set.
                val positions = listOf(NO_EFFECT) + system.effectsFor(ch).map { it.code }
                val current = if (!c.hasEffect && !chosenArpeggio()) NO_EFFECT else c.effect
                val idx = positions.indexOf(current).coerceAtLeast(0)
                val next = positions[(idx + (if (dx != 0) dx else dy)).mod(positions.size)]
                if (next == NO_EFFECT) {
                    write(c.copy(effect = 0, param = 0))
                    state.chosenArpeggio = -1
                    lastEffect.remove(ch)
                } else {
                    write(c.copy(effect = next))
                    lastEffect[ch] = next to c.param
                    state.chosenArpeggio = if (next == ZERO_EFFECT && c.param == 0) cursorKey() else -1
                }
            }
            else -> {
                if (offTakesSlot(c)) { session.note(offSlotTaken()); return }
                val p = (c.param + dx + 16 * dy).coerceIn(0, 255)
                write(c.copy(param = p))
                lastEffect[ch] = c.effect to p
                // Back to 00 on effect 0: keep showing the arpeggio being edited instead of a blank cell.
                if (c.effect == ZERO_EFFECT && p == 0) state.chosenArpeggio = cursorKey()
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
                c.hasEffect && system.offEffect != null -> session.note("Clear this row's effect first: OFF uses the effect slot (as ${system.offEffect})")
                else -> { write(Cell(note = Cell.CUT)); session.note(system.help.offEntered) }
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
                state.instrument = pen(bankOf(state.channel))
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
        setScale(if (field == 0) s.copy(root = (s.root + delta).mod(12))
            else s.copy(kind = ScaleKind.entries[(s.kind.ordinal + delta).mod(ScaleKind.entries.size)]))
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

    /** Nudges the pattern's played notes on tonal channels onto the nearest scale notes, as one undo step. */
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
            0 -> session.setTitle(NameEdit.edit(s.title, state.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            1 -> session.setArtist(NameEdit.edit(s.artist, state.textPos, dx + 26 * dy, EditorSession.MAX_NAME))
            2 -> session.setTicksPerRow(s.ticksPerRow + dx + 4 * dy)
            3 -> session.setLoopOrder(s.loopOrder + dx + 8 * dy)
            ViewState.SONG_NEW_SCALE_ITEM -> {
                // A+←→ steps the scale kind, A+↑↓ the root, like the pattern panel.
                val ns = s.newPatternScale
                val next = ns.copy(
                    root = (ns.root + dy).mod(12),
                    kind = ScaleKind.entries[(ns.kind.ordinal + dx).mod(ScaleKind.entries.size)],
                )
                if (session.setNewPatternScale(next)) session.note("New patterns start in ${next.label}")
            }
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
            1 -> if (session.insertOrder(o + 1, song.orders[o], parked = o in song.parkedOrders)) { state.songItem++; publishOrder() }
            2 -> if (session.newPatternAt(o + 1, parked = o in song.parkedOrders) >= 0) { state.songItem++; publishOrder() }
            3 -> if (session.clonePatternAt(o) >= 0) { state.songItem++; publishOrder() }
            4 -> { session.deleteOrder(o); normalise(); publishOrder() }
            5 -> if (session.moveOrder(o, o - 1)) { state.songItem--; publishOrder() }
            6 -> if (session.moveOrder(o, o + 1)) { state.songItem++; publishOrder() }
            7 -> session.setLoopOrder(o)
            8 -> if (session.toggleParked(o)) {
                session.note(if (o in song.parkedOrders) "Order ${EditorSession.hex2(o)} parked: kept here, skipped by song playback and export"
                else "Order ${EditorSession.hex2(o)} plays again")
            }
        }
    }

    private fun songClear() {
        val s = song
        when (state.songItem) {
            0 -> session.setTitle(deleteChar(s.title, state.textPos))
            1 -> session.setArtist(deleteChar(s.artist, state.textPos))
            2 -> session.setTicksPerRow(6)
            3 -> session.setLoopOrder(0)
            ViewState.SONG_NEW_SCALE_ITEM -> if (session.setNewPatternScale(Scale.CHROMATIC)) session.note("New patterns start chromatic")
            else -> { session.deleteOrder(songOrder); normalise(); publishOrder() }
        }
    }

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
            Action.INSERT_ROW -> if (o >= 0 && session.insertOrder(o + 1, song.orders[o], parked = o in song.parkedOrders)) { state.songItem++; publishOrder() }
            Action.DELETE_ROW -> if (o >= 0) { session.deleteOrder(o); normalise(); publishOrder() }
            else -> return false
        }
        return true
    }

    // ---- keyboard ----------------------------------------------------------------------------------

    /** Note of keyboard [key] at the current octave; may exceed the range, see [keyPlayable]. */
    fun keyNote(key: Int): Int = octaveNote(state.octave) + key

    fun keyPlayable(key: Int): Boolean = keyNote(key) in system.noteRange

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
                if (key < 0 && state.octave > minOctave()) { state.octave--; key += 12 }
                if (key > KEYS - 1 && state.octave < keyboardOctaves().last) { state.octave++; key -= 12 }
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
            3 -> state.octave = (state.octave + d).coerceIn(keyboardOctaves())
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
        if (state.kind == ViewKind.PATTERN && bankOf(state.channel) == sel.bank) state.instrument = sel.instrument
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
        state.instrument = slot.coerceIn(1, slots(bankOf(state.channel)))
        setPen(bankOf(state.channel), state.instrument)
        publishKeyboard()
    }

    fun setKeyboardChannel(channel: Int) {
        state.channel = channel.coerceIn(0, song.channelCount() - 1)
        syncLock()
        state.instrument = pen(bankOf(state.channel))
        publishKeyboard()
    }

    private fun publishKeyboard() {
        session.select(session.selection.copy(channel = state.channel, bank = bankOf(state.channel), instrument = state.instrument, context = ViewKind.PATTERN))
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
        if (!keyPlayable(state.kbKey)) { session.note("Above ${system.noteName(system.noteRange.last)}, the highest note"); return }
        val note = keyNote(state.kbKey)
        keyHeld = true
        playKey(note)
        sampleNote(state.channel, note, keyboardSound())
        if (state.entryMode) writeFromKeyboard(note)
    }

    /** Touch glide while a key is held: plays the new key (if in range) without writing it. */
    fun glideTo(key: Int) {
        state.kbKey = key.coerceIn(0, KEYS - 1)
        if (keyPlayable(state.kbKey)) playKey(keyNote(state.kbKey))
    }

    /** A key's pitch on the keyboard channel: in a tuned song, the nearest reachable degree (note, bend). */
    private fun keyPitch(note: Int): TunedPitch? {
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
            val off = system.offEffect
            if (off != null && old.hasEffect) { session.note("That row has an effect; clear it first: OFF uses the effect slot (as $off)"); return }
            Cell(note = Cell.CUT)
        } else {
            val pitch = keyPitch(note)
            if (pitch == null) old.copy(note = note, instrument = keyboardSound())
            else system.fine.write(old.copy(instrument = keyboardSound()), pitch.note, pitch.detune)?.also {
                state.entry.tuned[state.channel] = pitch.note to pitch.detune
                session.note("${session.tuningMap?.tuning?.name}: wrote ${session.tuningMap?.label(pitch)} (${NumberText.fixed(pitch.error, 1, plus = true)}¢)")
            } ?: run {
                session.note("That row's effect uses the slot tuned notes need for ${system.fine.name}; clear it first")
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
        octaveNote(state.octave) + sketchGuide.scale.root + sketchGuide.tones[index].interval

    /** Keep the entire ascending voicing in the hardware range, rather than clipping individual notes. */
    fun sketchChordNotes(): List<Int> {
        val guide = sketchGuide
        val chord = guide.chords.getOrNull(state.sketchChord) ?: return emptyList()
        val base = octaveNote(state.octave) + guide.scale.root
        val shift = if (base + chord.intervals.last() > system.noteRange.last) 12 else 0
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
            0 -> state.sketchScale = scale.copy(root = (scale.root + delta).mod(12))
            1 -> state.sketchScale = scale.copy(kind = ScaleKind.entries[(scale.kind.ordinal + delta).mod(ScaleKind.entries.size)])
            2 -> sketchChannel(delta)
            3 -> state.instrument = (state.instrument + delta).coerceIn(1, slots(bankOf(state.channel)))
            4 -> state.octave = (state.octave + delta).coerceIn(keyboardOctaves())
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
        val channels = (0 until song.channelCount()).filter { tonal(it) }
        if (channels.isEmpty()) return
        val index = channels.indexOf(state.channel).coerceAtLeast(0)
        state.channel = channels[(index + delta).coerceIn(0, channels.lastIndex)]
        state.instrument = pen(bankOf(state.channel))
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
        val base = octaveNote(state.octave) + scale.root
        val shift = if (base + 7 > system.noteRange.last) 12 else 0
        return listOf(0, if (state.sketchCircleMinor) 3 else 4, 7).map { base + it - shift }
    }

    fun sketchCircleSelect(position: Int, minor: Boolean) {
        state.sketchRow = SKETCH_CHORDS
        state.sketchCircleKey = position.mod(12)
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

    /** One pattern row at the song tempo: ticks per row at the system's tick rate. */
    fun sketchStepMillis(): Long = (song.ticksPerRow * 1000.0 / system.tickHz).roundToLong()

    /** The chord whose tones generated phrases land on at strong rows: the selected triad while Chord tones is on. */
    fun sketchPhraseChord(): TonalGuide.Chord? =
        if (state.sketchPhraseSettings.chordTones) sketchGuide.chords.getOrNull(state.sketchChord) else null

    private fun sketchGenerate(): Phrase = Phrase.generate(
        sketchGuide, octaveNote(state.octave) + sketchGuide.scale.root, sketchPhraseChord(),
        state.sketchPhraseSettings, Random(state.sketchPhraseSeed), system.noteRange,
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
            PHRASE_RHYTHM -> s.copy(rhythm = PhraseRhythm.entries[(s.rhythm.ordinal + delta).mod(PhraseRhythm.entries.size)])
            PHRASE_MOTION -> s.copy(motion = PhraseMotion.entries[(s.motion.ordinal + delta).mod(PhraseMotion.entries.size)])
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
                    Random(state.sketchPhraseSeed), keepRhythm, keepPitches, system.noteRange))
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
        val phrase = Phrase.fromCells(cells, length, system.noteRange)
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
            append("Inserted phrase: ${system.channels[ch].name}, pattern ${EditorSession.hex2(pattern)} rows ")
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
        if (dx != 0) { state.helpPage = (state.helpPage + dx).mod(HELP_PAGES.size); state.scroll = 0 }
        else state.scroll = (state.scroll - dy * 3).coerceAtLeast(0)
    }

    private fun helpCommand(a: Action): Boolean {
        when (a) {
            Action.PREV_CHANNEL, Action.NEXT_CHANNEL -> {
                state.helpPage = (state.helpPage + if (a == Action.NEXT_CHANNEL) 1 else -1).mod(HELP_PAGES.size)
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

    internal fun k(a: Action): String = keys.label(a, state.kind)
    internal fun lr(): String = keys.pair(Action.LEFT, Action.RIGHT, state.kind)
    internal fun ud(): String = keys.pair(Action.UP, Action.DOWN, state.kind)
    internal fun channels(): String = keys.pair(Action.PREV_CHANNEL, Action.NEXT_CHANNEL, state.kind)
    internal fun copyPaste(): String = keys.pair(Action.COPY, Action.PASTE, state.kind)
    internal fun octaves(): String = keys.pair(Action.OCTAVE_DOWN, Action.OCTAVE_UP, state.kind)
    internal fun editLr(): String = keys.held(Action.EDIT, lr())
    internal fun editUd(): String = keys.held(Action.EDIT, ud())

    /** Contextual hints for the current view, field and held buttons. Key names follow the current [keys]. */
    fun hints(): List<Hint> {
        normalise()
        val h = ArrayList<Hint>()
        systemView()?.let { it.hints(this, h); return h }
        when (state.kind) {
            ViewKind.PATTERN -> patternHints(h)
            ViewKind.SONG -> songHints(h)
            ViewKind.KEYBOARD -> keyboardHints(h)
            ViewKind.SKETCH -> sketchHints(h)
            ViewKind.HELP -> {
                h += Hint(ud(), "Scroll")
                h += Hint(lr(), "Page: ${HELP_PAGES[state.helpPage]}")
            }
            else -> Unit
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
                        system.mappedNotes(song, ch, if (c.instrument > 0) c.instrument else pen(bankOf(ch))) != null -> "Mapped sample"
                        !tonal(ch) -> "${system.channels[ch].name} pitch (no scale)"
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
                    0 -> if (c.note < 0) "Write note ${system.noteName(entryNote(ch))}" else "Hold to change note"
                    1 -> if (c.note < 0) "Needs a note" else if (c.instrument == 0) "Write sound ${EditorSession.hex2(pen(bankOf(ch)))}" else "Hold to change sound"
                    else -> when {
                        c.hasEffect -> "Hold to change effect"
                        chosenArpeggio() -> if (state.field == 2) "Hold to change effect" else "Hold to set arpeggio +x +y"
                        else -> lastEffectOf(ch)?.let { (e, p) ->
                            if (e == ZERO_EFFECT && p == 0) "Pick ${system.effects.first { it.code == ZERO_EFFECT }.name.lowercase()} 0"
                            else "Write effect ${EffectSpec.charOf(e)}${EditorSession.hex2(p)}"
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
            item == ViewState.SONG_NEW_SCALE_ITEM -> {
                h += Hint(editLr(), "Scale"); h += Hint(editUd(), "Root"); h += Hint(k(Action.CLEAR), "Chromatic")
            }
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
        /** Effect number 0: with value 00 it is stored exactly like "no effect" (000), so choosing it is shown, not written. */
        private const val ZERO_EFFECT = 0

        /** [text] without the character at [pos]. */
        internal fun deleteChar(text: String, pos: Int) = if (pos < text.length) text.removeRange(pos, pos + 1) else text

        /** Identifies one pattern cell for view-local markers: pattern < 256, channel < 4, row < 64. */
        fun cellKey(pattern: Int, channel: Int, row: Int): Int = (pattern shl 8) or (channel shl 6) or row
        val PATTERN_CONTEXT = arrayOf(ViewKind.KEYBOARD, ViewKind.INSTRUMENT, ViewKind.HELP, ViewKind.HELP)
        val ORDER_FIELDS = listOf("Pattern", "Repeat", "New", "Clone", "Delete", "Earlier", "Later", "Loop here", "Park")
        /** Song view order command that parks/unparks the order. */
        const val PARK_FIELD = 8
        val ORDER_FIELD_HINTS = listOf(
            "Select this order for editing",
            "Repeat this pattern in a new order below",
            "Add a new empty pattern below",
            "Copy this pattern into a new one below",
            "Delete this order",
            "Move this order earlier",
            "Move this order later",
            "Loop the song back to this order",
            "Park / unpark: keep this order here but skip it in song playback and export",
        )
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

        /** Major-key root (pitch class) at circle-of-fifths [position] (0 = C, 1 = G, ... 11 = F). */
        fun circleRoot(position: Int): Int = (position * 7).mod(12)

        /** Circle-of-fifths position of major-key root [root]; 7 is its own inverse modulo 12. */
        fun circlePosition(root: Int): Int = (root * 7).mod(12)

        private fun nextSeed(seed: Long): Long = seed * 6364136223846793005L + 1442695040888963407L
        val HELP_PAGES = listOf("Controls", "Effects", "Song check")
    }
}
