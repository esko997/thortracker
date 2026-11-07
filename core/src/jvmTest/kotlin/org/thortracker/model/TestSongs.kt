package org.thortracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.thortracker.system.gb.GbInstrument
import org.thortracker.system.gb.gb

/** Small helpers for building test songs from [Songs.blank]. */
internal object TestSongs {
    fun withCell(song: Song, pattern: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.patterns[pattern]
        val track = p.tracks[channel].toMutableList().also { it[row] = cell }
        val tracks = p.tracks.toMutableList().also { it[channel] = track }
        val patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) }
        return song.copy(patterns = patterns)
    }

    fun withPulse(song: Song, slot: Int, instrument: GbInstrument): Song =
        song.copy(sound = song.gb.copy(pulse = song.gb.pulse.toMutableList().also { it[slot - 1] = instrument }))

    /** Blank song with [count] blank patterns, orders 0..count-1. */
    fun patterns(count: Int): Song {
        val blank = Songs.blank()
        return blank.copy(
            patterns = List(count) { Songs.blankPattern(blank, "P$it") },
            orders = List(count) { it },
        )
    }

    /**
     * A current project file ([ProjectCodec.encode] output) as Game Boy project format [version] (1-7) wrote it: the
     * banks and waves at the top of the song instead of in `sound`, notes and the tuning root as hUGE note numbers (0 = C-3).
     */
    fun legacy(text: String, version: Int): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val song = root.getValue("song").jsonObject
        val sound = song.getValue("sound").jsonObject
        fun older(note: JsonElement): JsonElement = note.jsonPrimitive.int.let { if (it >= 0) JsonPrimitive(it - 24) else note }
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in song) when (key) {
            "sound" -> {
                out["pulseInstruments"] = sound.getValue("pulse"); out["waveInstruments"] = sound.getValue("wave")
                out["noiseInstruments"] = sound.getValue("noise"); out["waves"] = sound.getValue("waves")
            }
            "patterns" -> out[key] = JsonArray(value.jsonArray.map { p ->
                val tracks = p.jsonObject.getValue("tracks").jsonArray.map { t -> JsonArray(t.jsonArray.map { c -> JsonArray(listOf(older(c.jsonArray[0])) + c.jsonArray.drop(1)) }) }
                JsonObject(p.jsonObject + ("tracks" to JsonArray(tracks)))
            })
            "tuning" -> out[key] = (value as? JsonObject)?.let { t -> JsonObject(t + ("root" to older(t.getValue("root")))) } ?: value
            else -> out[key] = value
        }
        return JsonObject(root + ("version" to JsonPrimitive(version)) + ("song" to JsonObject(out))).toString()
    }

    /**
     * A current project file as development builds wrote project format 8: notes and the tuning root counted from
     * the system's C-0 in its own naming, which for the Game Boy (hUGETracker names) is 12 above scientific pitch.
     */
    fun devVersion8(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val song = root.getValue("song").jsonObject
        fun older(note: JsonElement): JsonElement = note.jsonPrimitive.int.let { if (it >= 0) JsonPrimitive(it + 12) else note }
        val out = LinkedHashMap<String, JsonElement>()
        for ((key, value) in song) when (key) {
            "patterns" -> out[key] = JsonArray(value.jsonArray.map { p ->
                val tracks = p.jsonObject.getValue("tracks").jsonArray.map { t -> JsonArray(t.jsonArray.map { c -> JsonArray(listOf(older(c.jsonArray[0])) + c.jsonArray.drop(1)) }) }
                JsonObject(p.jsonObject + ("tracks" to JsonArray(tracks)))
            })
            "tuning" -> out[key] = (value as? JsonObject)?.let { t -> JsonObject(t + ("root" to older(t.getValue("root")))) } ?: value
            else -> out[key] = value
        }
        return JsonObject(root + ("version" to JsonPrimitive(8)) + ("song" to JsonObject(out))).toString()
    }
}
