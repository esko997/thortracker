package org.thortracker.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.model.Phrase
import org.thortracker.model.PhraseMotion
import org.thortracker.model.PhraseRhythm
import org.thortracker.model.PhraseSettings
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Songs

/**
 * The stored pane layout ([WorkspaceStore]): one JSON object per display, read leniently (a missing or odd value
 * falls back to its default, like `org.json`'s `opt` getters this format was first written with).
 *
 * The only note numbers stored are a Sketch phrase's, under `phrasePitches` in scientific pitch (0 = C0, A4 = 57 =
 * 440 Hz). Workspaces saved before notes became absolute have `phrase`, hUGE note numbers (0 = hUGETracker's C-3 = C2,
 * the Game Boy being the only system then), moved up by [LEGACY_NOTE_BASE]. Development builds briefly wrote
 * `phraseNotes` in each system's own octave naming; without knowing the song's system that is ambiguous, so it is
 * ignored and the phrase regenerates from its seed. Octaves are stored as the system's note-name octave and keyboard
 * keys from the octave's C, as always.
 */
object WorkspaceCodec {
    /** hUGE note 0 (C2 in scientific pitch): note 0 of the old `phrase` key. */
    const val LEGACY_NOTE_BASE = 24

    /** Most channels any system has (the OPL3's 9), for the stored per-channel effect column counts. */
    private const val MAX_CHANNELS = 16

    fun decode(text: String): MutableList<ViewState> {
        val defaults = mutableListOf(ViewState(kind = ViewKind.PATTERN), ViewState(kind = ViewKind.KEYBOARD))
        val array = try { Json.parseToJsonElement(text) as? JsonArray } catch (_: Exception) { null } ?: return defaults
        for (index in 0 until minOf(array.size, defaults.size)) {
            val item = array[index] as? JsonObject ?: return defaults
            fun int(key: String, default: Int) = number(item, key)?.toInt() ?: default
            fun bool(key: String, default: Boolean) = (item[key] as? JsonPrimitive)?.content?.lowercase()?.let {
                when (it) { "true" -> true; "false" -> false; else -> null }
            } ?: default
            fun string(key: String) = (item[key] as? JsonPrimitive)?.content ?: ""
            defaults[index] = ViewState(
                kind = ViewKind.entries.firstOrNull { it.name == string("kind") } ?: defaults[index].kind,
                pinned = bool("pinned", true),
                followSelection = bool("follow", true),
                lockedSubject = int("locked", -1),
                order = int("order", 0).coerceAtLeast(0),
                row = int("row", 0).coerceAtLeast(0),
                channel = int("channel", 0).coerceAtLeast(0),
                field = int("field", 0).coerceIn(0, ViewController.MAX_FIELDS - 1),
                instrument = int("instrument", 1).coerceAtLeast(1),
                wave = int("wave", 0).coerceAtLeast(0),
                scroll = int("scroll", 0).coerceAtLeast(0),
                octave = int("octave", 4).coerceIn(0, Songs.NOTE_LIMIT / 12),
                bank = int("bank", 0).coerceAtLeast(0),
                followPlayback = bool("followPlayback", true),
                entryMode = bool("entryMode", false),
                songItem = int("songItem", ViewState.SONG_HEADER_ITEMS).coerceAtLeast(0),
                songField = int("songField", 0).coerceAtLeast(0),
                instField = int("instField", 0).coerceAtLeast(0),
                waveRow = int("waveRow", 1).coerceAtLeast(0),
                waveCol = int("waveCol", 0).coerceAtLeast(0),
                waveLevel = int("waveLevel", 8).coerceAtLeast(0),
                kbRow = int("kbRow", 1).coerceAtLeast(0),
                kbField = int("kbField", 0).coerceAtLeast(0),
                kbKey = int("kbKey", 0).coerceAtLeast(0),
                helpPage = int("helpPage", 0).coerceAtLeast(0),
                textPos = int("textPos", 0).coerceAtLeast(0),
                sketchScale = ScaleKind.entries.firstOrNull { it.name == string("sketchScale") }
                    ?.let { Scale(int("sketchRoot", 0).coerceIn(0, 11), it) },
                sketchRow = int("sketchRow", 1).coerceIn(0, ViewController.SKETCH_ROWS - 1),
                sketchField = int("sketchField", 0).coerceIn(0, ViewController.SKETCH_FIELDS.lastIndex),
                sketchChord = int("sketchChord", 0).coerceAtLeast(0),
                sketchTone = int("sketchTone", 0).coerceAtLeast(0),
                sketchCircle = bool("sketchCircle", false),
                sketchCircleKey = Math.floorMod(int("sketchCircleKey", 0), 12),
                sketchCircleMinor = bool("sketchCircleMinor", false),
                sketchPhraseField = int("phraseField", 0).coerceIn(0, ViewController.PHRASE_FIELDS.lastIndex),
                sketchPhraseSettings = PhraseSettings(
                    length = int("phraseLength", 8).coerceIn(PhraseSettings.MIN_LENGTH, PhraseSettings.MAX_LENGTH),
                    rhythm = PhraseRhythm.entries.firstOrNull { it.name == string("phraseRhythm") } ?: PhraseRhythm.EVEN,
                    motion = PhraseMotion.entries.firstOrNull { it.name == string("phraseMotion") } ?: PhraseMotion.STEPS,
                    chordTones = bool("phraseChordTones", true),
                ),
                sketchPhrase = phrase(item["phrasePitches"] as? JsonArray, 0)
                    ?: phrase(item["phrase"] as? JsonArray, LEGACY_NOTE_BASE),
                sketchPhraseSeed = number(item, "phraseSeed")?.toLong() ?: 1L,
                sketchKeepRhythm = bool("keepRhythm", false),
                sketchKeepPitches = bool("keepPitches", false) && !bool("keepRhythm", false),
                envelope = int("envelope", 0).coerceAtLeast(0),
                envStep = int("envStep", 0).coerceAtLeast(0),
                listenChannel = int("listenChannel", 0).coerceAtLeast(0),
                dpcmKey = int("dpcmKey", ViewState.DEFAULT_DPCM_KEY).coerceAtLeast(0),
                dpcmSample = int("dpcmSample", 0).coerceAtLeast(0),
                programField = int("programField", 0).coerceAtLeast(0),
                rawTable = int("rawTable", 0).coerceAtLeast(0),
                effectColumns = (item["effectColumns"] as? JsonArray)?.take(MAX_CHANNELS)
                    ?.map { (it as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, Cell.MAX_EFFECT_COLUMNS) ?: 0 }.orEmpty(),
            )
        }
        return defaults
    }

    fun encode(states: List<ViewState>): String = buildJsonArray {
        for (s in states) add(buildJsonObject {
            put("kind", s.kind.name); put("pinned", s.pinned); put("follow", s.followSelection)
            put("locked", s.lockedSubject); put("order", s.order); put("row", s.row)
            put("channel", s.channel); put("field", s.field); put("instrument", s.instrument)
            put("wave", s.wave); put("scroll", s.scroll); put("octave", s.octave)
            put("bank", s.bank); put("followPlayback", s.followPlayback); put("entryMode", s.entryMode)
            put("songItem", s.songItem); put("songField", s.songField); put("instField", s.instField)
            put("waveRow", s.waveRow); put("waveCol", s.waveCol); put("waveLevel", s.waveLevel)
            put("kbRow", s.kbRow); put("kbField", s.kbField); put("kbKey", s.kbKey)
            put("helpPage", s.helpPage); put("textPos", s.textPos)
            s.sketchScale?.let { put("sketchRoot", it.root); put("sketchScale", it.kind.name) }
            put("sketchRow", s.sketchRow); put("sketchField", s.sketchField)
            put("sketchChord", s.sketchChord); put("sketchTone", s.sketchTone)
            put("sketchCircle", s.sketchCircle); put("sketchCircleKey", s.sketchCircleKey); put("sketchCircleMinor", s.sketchCircleMinor)
            put("phraseField", s.sketchPhraseField); put("phraseLength", s.sketchPhraseSettings.length)
            put("phraseRhythm", s.sketchPhraseSettings.rhythm.name); put("phraseMotion", s.sketchPhraseSettings.motion.name)
            put("phraseChordTones", s.sketchPhraseSettings.chordTones); put("phraseSeed", s.sketchPhraseSeed)
            put("keepRhythm", s.sketchKeepRhythm); put("keepPitches", s.sketchKeepPitches)
            put("envelope", s.envelope); put("envStep", s.envStep); put("listenChannel", s.listenChannel)
            put("dpcmKey", s.dpcmKey); put("dpcmSample", s.dpcmSample)
            put("programField", s.programField); put("rawTable", s.rawTable)
            if (s.effectColumns.isNotEmpty()) put("effectColumns", buildJsonArray { s.effectColumns.forEach { add(JsonPrimitive(it)) } })
            s.sketchPhrase?.let { p -> put("phrasePitches", buildJsonArray { p.steps.forEach { add(JsonPrimitive(it ?: -1)) } }) }
        })
    }.toString()

    /** A number stored as a number or numeric text (`opt` getters accept both). */
    private fun number(item: JsonObject, key: String): Long? {
        val p = item[key] as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
    }

    /**
     * A stored phrase (-1 = no onset) whose notes count from [base] semitones above C-0, as absolute notes; null to
     * regenerate when it is missing or not a usable phrase.
     */
    private fun phrase(array: JsonArray?, base: Int): Phrase? {
        if (array == null || array.size !in 1..PhraseSettings.MAX_LENGTH) return null
        val steps = array.map { (it as? JsonPrimitive)?.let { p -> p.longOrNull?.toInt() ?: p.content.toIntOrNull() } ?: -1 }
        val notes = steps.map { if (it >= 0) it + base else it }
        if (notes.any { it !in -1..Songs.NOTE_LIMIT } || notes.all { it < 0 }) return null
        return Phrase(notes.map { it.takeIf { n -> n >= 0 } })
    }
}
