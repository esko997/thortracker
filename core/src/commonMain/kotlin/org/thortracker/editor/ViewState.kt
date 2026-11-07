package org.thortracker.editor

import org.thortracker.model.Phrase
import org.thortracker.model.PhraseSettings
import org.thortracker.model.Scale

enum class ViewKind { PATTERN, SONG, INSTRUMENT, WAVE, KEYBOARD, SKETCH, HELP }

/**
 * Navigation state of one display. Each view kind keeps its own cursor fields so switching a display
 * between kinds (or swapping displays) preserves where the user was.
 *
 * lockedSubject: PATTERN/KEYBOARD/SKETCH = channel, a system's own views their subject ([org.thortracker.system.SystemView.subject]), -1 = none.
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
    /** Envelope instrument editors (NES): the envelope shown, the step under the cursor and the channel Listen plays on. Not persisted. */
    var envelope: Int = 0,
    var envStep: Int = 0,
    var listenChannel: Int = 0,
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
    /** Null follows the pattern key; a local exploration never changes the musical document. */
    var sketchScale: Scale? = null,
    /** 0 settings, 1 chords (or circle of fifths), 2 notes, 3 phrase. */
    var sketchRow: Int = 1,
    var sketchField: Int = 0,
    var sketchChord: Int = 0,
    var sketchTone: Int = 0,
    /** The chords row shows the circle of fifths instead of the key's triads. */
    var sketchCircle: Boolean = false,
    /** Circle cursor: position by fifths from C (major root = 7 * position mod 12), on the outer or the relative-minor ring. */
    var sketchCircleKey: Int = 0,
    var sketchCircleMinor: Boolean = false,
    /** Phrase row control, see [ViewController.PHRASE_FIELDS]. */
    var sketchPhraseField: Int = 0,
    var sketchPhraseSettings: PhraseSettings = PhraseSettings(),
    /** The current phrase; null until first generated from [sketchPhraseSeed]. */
    var sketchPhrase: Phrase? = null,
    var sketchPhraseSeed: Long = 1L,
    /** Vary keeps the onsets ([sketchKeepRhythm]) or the pitch sequence ([sketchKeepPitches]); at most one is on. */
    var sketchKeepRhythm: Boolean = false,
    var sketchKeepPitches: Boolean = false,
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

    companion object {
        /** Song view rows before the order list: title, artist, ticks per row, loop order, scale for new patterns. */
        const val SONG_HEADER_ITEMS = 5
        /** Song view row of the scale new patterns start with. */
        const val SONG_NEW_SCALE_ITEM = 4
    }
}

/** Last note and effect entered per channel, last tuned pitch (note, bend) per channel, and the entry sound per bank index. */
data class EntryMemory(
    val notes: HashMap<Int, Int> = HashMap(),
    val effects: HashMap<Int, Pair<Int, Int>> = HashMap(),
    val pens: HashMap<Int, Int> = HashMap(),
    val tuned: HashMap<Int, Pair<Int, Int>> = HashMap(),
) {
    fun deepCopy() = EntryMemory(HashMap(notes), HashMap(effects), HashMap(pens), HashMap(tuned))
}
