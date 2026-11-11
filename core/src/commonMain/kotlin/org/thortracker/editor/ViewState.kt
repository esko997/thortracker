package org.thortracker.editor

import org.thortracker.model.Phrase
import org.thortracker.model.PhraseSettings
import org.thortracker.model.Scale
import org.thortracker.system.panel.MusicalPanelState

enum class ViewKind { PATTERN, SONG, INSTRUMENT, WAVE, KEYBOARD, SKETCH, HELP }

/** Device-local pane magnification. Unknown stored values use the unchanged 100% layout. */
enum class PaneZoom(val percent: Int) {
    SMALL(75), NORMAL(100), LARGE(125), LARGEST(150);

    val factor: Float = percent / 100f
    fun step(direction: Int): PaneZoom = entries[(ordinal + direction.coerceIn(-1, 1)).coerceIn(0, entries.lastIndex)]

    companion object {
        fun fromStored(value: String?): PaneZoom {
            val percent = value?.trim()?.toIntOrNull()
            return entries.firstOrNull { it.percent == percent } ?: NORMAL
        }
    }
}

/**
 * Navigation state of one display. Each view kind keeps its own cursor fields so switching a display
 * between kinds (or swapping displays) preserves where the user was.
 *
 * lockedSubject: PATTERN/KEYBOARD/SKETCH = channel, a system's own views their subject ([org.thortracker.system.SystemView.subject]), -1 = none.
 */
data class ViewState(
    var kind: ViewKind = ViewKind.PATTERN,
    /** Travels with this complete view on swaps; never part of a song or undo history. */
    var zoom: PaneZoom = PaneZoom.NORMAL,
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
    /** Independent, non-document presentation and pointer ownership for the three musical design pilots. */
    var gbPanel: MusicalPanelState = MusicalPanelState(),
    var sidPanel: MusicalPanelState = MusicalPanelState(),
    var ymPanel: MusicalPanelState = MusicalPanelState(),
    /** Game Boy pulse pilot: local presentation only, never song data or undo history. */
    var gbAdvanced: Boolean = false,
    var gbAdvancedField: Int = 2,
    var gbSoundField: Int = 2,
    /** Invalidates pointer ownership even if navigation returns to the original slot/mode. */
    var gbSoundRevision: Int = 0,
    /** A physical Edit held across a mode change must release before it can edit the new mode. */
    var gbModeEditBlocked: Boolean = false,
    /** Envelope instrument editors (NES): the envelope shown, the step under the cursor and the channel Listen plays on. */
    var envelope: Int = 0,
    var envStep: Int = 0,
    var listenChannel: Int = 0,
    /** Sample instrument pages (NES DPCM): the key (absolute note) whose mapping is edited and the sample list cursor. */
    var dpcmKey: Int = DEFAULT_DPCM_KEY,
    var dpcmSample: Int = 0,
    /** Program editors (SID): the column of the program row under the cursor, and the table the raw table page shows. */
    var programField: Int = 0,
    var rawTable: Int = 0,
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
     * Pattern view: effect columns shown per channel (index = channel) on systems that declare more than one
     * ([org.thortracker.system.SoundSystem.effectColumns]); 0 or missing = automatic (the columns the song uses, at
     * least one). Per pane, so a narrow display can show fewer. See [ViewController.shownColumns].
     */
    var effectColumns: List<Int> = emptyList(),
    /** Pattern-only, per-pane note-width lanes. Missing entries are expanded; never part of the song. */
    var collapsedChannels: Set<Int> = emptySet(),
    /**
     * Pattern view: the cell (see [ViewController.cellKey]) whose stored 000 the user explicitly picked as
     * effect 0 (arpeggio) and is still on; -1 = none. Display only: 000 stays "no effect" in the song. Not persisted.
     */
    var chosenArpeggio: Int = -1,
    /** What "tap A" re-enters on this display; travels with the state when displays swap. Not persisted. */
    val entry: EntryMemory = EntryMemory(),
) {
    /** Active GB pointer owner only. Not copied with pane snapshots; navigation closes its Undo group immediately. */
    internal var cancelGbSoundTouch: (() -> Unit)? = null

    /** Subject identifiers belong to their view kind; never reinterpret one in another editor. */
    fun switchKind(next: ViewKind) {
        if (kind == next) return
        lockedSubject = -1
        scalePanel = false
        kind = next
    }

    /** Independent copy, used when swapping or persisting display states. */
    fun snapshot(): ViewState = copy(entry = entry.deepCopy(), gbPanel = gbPanel.copy(), sidPanel = sidPanel.copy(), ymPanel = ymPanel.copy())

    companion object {
        /** Song view rows before the order list: title, artist, ticks per row, loop order, scale for new patterns. */
        const val SONG_HEADER_ITEMS = 5

        /** Song view rows before [song]'s order list: the shared ones, then its system's [org.thortracker.system.SoundSystem.songSettings]. */
        fun songHeaderItems(song: org.thortracker.model.Song): Int = SONG_HEADER_ITEMS + song.system.songSettings.size
        /** Song view row of the scale new patterns start with. */
        const val SONG_NEW_SCALE_ITEM = 4
        /** C-3 (absolute 36): the first key a sample page edits, where starter kits begin. */
        const val DEFAULT_DPCM_KEY = 36
    }
}

/** Last note and effect entered per channel, last tuned pitch (note, bend) per channel, and the entry sound per bank index. */
data class EntryMemory(
    val notes: HashMap<Int, Int> = HashMap(),
    val effects: HashMap<Int, Pair<Int, Int>> = HashMap(),
    val pens: HashMap<Int, Int> = HashMap(),
    val tuned: HashMap<Int, Pair<Int, Int>> = HashMap(),
    internal var documentGeneration: Long = 0,
) {
    internal fun bindDocument(generation: Long) {
        if (documentGeneration == generation) return
        notes.clear()
        effects.clear()
        pens.clear()
        tuned.clear()
        documentGeneration = generation
    }

    fun deepCopy() = EntryMemory(HashMap(notes), HashMap(effects), HashMap(pens), HashMap(tuned), documentGeneration)
}
