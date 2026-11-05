package org.thortracker.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A project file that is malformed, from a newer/unknown format, or structurally invalid. */
class ProjectFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Thor Tracker's own project format: UTF-8 JSON
 * `{"format":"thor-tracker-project","version":5,"backend":"gb-huge","backendVersion":1,"song":{...}}`.
 * Cells are `[note, instrument, effect, param]` arrays. Decoding is strict: unknown keys, wrong types,
 * unknown versions/backends and structurally invalid songs are rejected with [ProjectFormatException];
 * export-level problems ([SongValidator.errors]) are allowed so work in progress round-trips.
 *
 * Versions: 1 = initial format. 2 adds each pattern's editor-only `scale` (`{"root":0..11,"kind":"major"}`);
 * version 1 files have no scales and read as chromatic. 3 adds each pattern's played `length` (1-64);
 * older files read as 64 rows. 4 adds scale kinds beyond the original five (modes, harmonic/melodic minor,
 * blues, whole tone and others). 5 adds the song's optional microtuning `tuning`
 * (`{"name":...,"cents":[...],"root":0..71,"tolerance":5|10|20}` or null); older files have none. Every save
 * writes the current version. Backend data is unchanged, so `backendVersion` stays 1.
 */
object ProjectCodec {
    const val FORMAT = "thor-tracker-project"
    const val VERSION = 5
    const val BACKEND_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
        coerceInputValues = false
    }

    @Serializable
    private data class Envelope(
        @SerialName("format") val format: String,
        @SerialName("version") val version: Int,
        @SerialName("backend") val backend: String,
        @SerialName("backendVersion") val backendVersion: Int,
        @SerialName("song") val song: Song,
    )

    /** @throws ProjectFormatException if [song] is structurally invalid (it could not be read back). */
    fun encode(song: Song): String {
        val problems = SongValidator.structuralErrors(song)
        if (problems.isNotEmpty()) throw ProjectFormatException("Song cannot be saved: ${problems.joinToString("; ")}")
        return json.encodeToString(Envelope.serializer(), Envelope(FORMAT, VERSION, song.backend, BACKEND_VERSION, song))
    }

    /** @throws ProjectFormatException for anything that is not a readable, structurally valid project. */
    fun decode(text: String): Song {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ProjectFormatException("Not a Thor Tracker project: the file is not valid JSON", e)
        }
        if (root !is JsonObject) throw ProjectFormatException("Not a Thor Tracker project: expected a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) throw ProjectFormatException("Not a Thor Tracker project (format is ${format ?: "missing"})")
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw ProjectFormatException("Project version is missing or not a number")
        if (version > VERSION) throw ProjectFormatException("Project version $version was written by a newer Thor Tracker; this version reads up to $VERSION")
        if (version < 1) throw ProjectFormatException("Project version $version is invalid")
        val backend = (root["backend"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (backend != Songs.BACKEND) throw ProjectFormatException("Project backend \"${backend ?: "missing"}\" is not supported; this version reads \"${Songs.BACKEND}\"")
        val backendVersion = (root["backendVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw ProjectFormatException("Backend data version is missing or not a number")
        if (backendVersion !in 1..BACKEND_VERSION) {
            throw ProjectFormatException("GB/hUGE data version $backendVersion is not supported; this version reads 1-$BACKEND_VERSION")
        }
        if (version < SCALE_VERSION && hasPatternKey(root, "scale")) {
            throw ProjectFormatException("Project version $version cannot contain pattern scales (added in version $SCALE_VERSION)")
        }
        if (version < LENGTH_VERSION && hasPatternKey(root, "length")) {
            throw ProjectFormatException("Project version $version cannot contain pattern lengths (added in version $LENGTH_VERSION)")
        }
        if (version < EXTENDED_SCALES_VERSION && usesExtendedScale(root)) {
            throw ProjectFormatException("Project version $version cannot contain the newer scales (added in version $EXTENDED_SCALES_VERSION)")
        }
        if (version < TUNING_VERSION && hasTuning(root)) {
            throw ProjectFormatException("Project version $version cannot contain a song tuning (added in version $TUNING_VERSION)")
        }
        val envelope = try {
            json.decodeFromJsonElement(Envelope.serializer(), root)
        } catch (e: SerializationException) {
            throw ProjectFormatException("Project data is invalid: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("Project data is invalid: ${e.message}", e)
        }
        val song = envelope.song
        if (song.backend != backend) throw ProjectFormatException("Song backend \"${song.backend}\" does not match the project backend \"$backend\"")
        val problems = SongValidator.structuralErrors(song)
        if (problems.isNotEmpty()) throw ProjectFormatException("Project data is invalid: ${problems.joinToString("; ")}")
        return song
    }

    /** First format version with per-pattern scales. */
    private const val SCALE_VERSION = 2

    /** First format version with per-pattern lengths. */
    private const val LENGTH_VERSION = 3

    /** First format version with scale kinds outside [ScaleKind.V2]. */
    private const val EXTENDED_SCALES_VERSION = 4

    /** First format version with a song tuning. */
    private const val TUNING_VERSION = 5

    private val V2_SCALE_NAMES = ScaleKind.V2.map { ScaleKind.serializer().descriptor.getElementName(it.ordinal) }.toSet()

    private fun usesExtendedScale(root: JsonObject): Boolean =
        ((root["song"] as? JsonObject)?.get("patterns") as? JsonArray)?.any { p ->
            val kind = (((p as? JsonObject)?.get("scale") as? JsonObject)?.get("kind") as? JsonPrimitive)?.content
            kind != null && kind !in V2_SCALE_NAMES
        } == true

    private fun hasPatternKey(root: JsonObject, key: String): Boolean =
        ((root["song"] as? JsonObject)?.get("patterns") as? JsonArray)?.any { (it as? JsonObject)?.containsKey(key) == true } == true

    private fun hasTuning(root: JsonObject): Boolean =
        (root["song"] as? JsonObject)?.get("tuning").let { it != null && it !is JsonNull }
}
