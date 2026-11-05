package org.thortracker.editor

import org.thortracker.model.InstrumentKind

enum class ViewKind { PATTERN, SONG, INSTRUMENT, WAVE, KEYBOARD, HELP }

/**
 * Navigation state of one display. Each view kind keeps its own cursor fields so switching a display
 * between kinds (or swapping displays) preserves where the user was.
 *
 * lockedSubject: INSTRUMENT = bank * 16 + slot, WAVE = wave index, PATTERN/KEYBOARD = channel, -1 = none.
 */
data class ViewState(
    var kind: ViewKind = ViewKind.PATTERN,
    var pinned: Boolean = true,
    var followSelection: Boolean = true,
    var lockedSubject: Int = -1,
    var order: Int = 0,
    var row: Int = 0,
    var channel: Int = 0,
    var field: Int = 0,
    var instrument: Int = 1,
    var wave: Int = 0,
    var scroll: Int = 0,
    var octave: Int = 4,
    var bank: Int = 0,
    var followPlayback: Boolean = true,
    var entryMode: Boolean = false,
    var mark: Boolean = false,
    var markOrder: Int = 0,
    var markRow: Int = 0,
    var markChannel: Int = 0,
    var songItem: Int = SONG_HEADER_ITEMS,
    var songField: Int = 0,
    var instField: Int = 0,
    var waveRow: Int = 1,
    var waveCol: Int = 0,
    var waveLevel: Int = 8,
    var kbRow: Int = 1,
    var kbField: Int = 0,
    var kbKey: Int = 0,
    var helpPage: Int = 0,
    /** Character position while editing a name (song title/artist, sound name). */
    var textPos: Int = 0,
    /** Pattern view: the root/scale panel is open; [scaleField] 0 = root, 1 = scale. Not persisted. */
    var scalePanel: Boolean = false,
    var scaleField: Int = 0,
    /**
     * Pattern view: the cell (see [ViewController.cellKey]) whose stored 000 the user explicitly picked as
     * effect 0 (arpeggio) and is still on; -1 = none. Display only: 000 stays "no effect" in the song. Not persisted.
     */
    var chosenArpeggio: Int = -1,
    /** What "tap A" re-enters on this display; travels with the state when displays swap. Not persisted. */
    val entry: EntryMemory = EntryMemory(),
) {
    /** Subject identifiers belong to their view kind; never reinterpret one in another editor. */
    fun switchKind(next: ViewKind) {
        if (kind == next) return
        lockedSubject = -1
        scalePanel = false
        kind = next
    }

    /** Independent copy, used when swapping or persisting display states. */
    fun snapshot(): ViewState = copy(entry = entry.deepCopy())

    val bankKind: InstrumentKind get() = InstrumentKind.entries[bank.coerceIn(0, InstrumentKind.entries.size - 1)]

    companion object {
        /** Song view rows before the order list: title, artist, ticks per row, loop order. */
        const val SONG_HEADER_ITEMS = 4
        const val MIN_OCTAVE = 3
        const val MAX_OCTAVE = 8
        /** The 25-key keyboard starts at most at C-7, so only its top key (C-9) is beyond the note range. */
        const val KEYBOARD_MAX_OCTAVE = 7
    }
}

/** Last note and effect entered per channel, and the entry sound per bank. */
data class EntryMemory(
    val notes: HashMap<Int, Int> = HashMap(),
    val effects: HashMap<Int, Pair<Int, Int>> = HashMap(),
    val pens: HashMap<InstrumentKind, Int> = HashMap(),
) {
    fun deepCopy() = EntryMemory(HashMap(notes), HashMap(effects), HashMap(pens))
}
