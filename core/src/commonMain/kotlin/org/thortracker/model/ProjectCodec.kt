package org.thortracker.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.thortracker.system.SoundSystems
import org.thortracker.system.SystemSound

/** A project file that is malformed, from a newer/unknown format, or structurally invalid. */
class ProjectFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Thor Tracker's own project format: UTF-8 JSON
 * `{"format":"thor-tracker-project","version":9,"backend":"gb-huge","backendVersion":1,"song":{...}}`.
 * Cells are `[note, instrument, effect, param]` arrays, followed by `effect, param` per further effect column on
 * systems with several ([Cell.more], the OPL3). Decoding is strict: unknown keys, wrong types,
 * unknown versions/backends and structurally invalid songs are rejected with [ProjectFormatException];
 * export-level problems ([org.thortracker.system.SoundSystem.errors]) are allowed so work in progress round-trips.
 * `backend` names the song's sound system ([SoundSystems]); the song repeats it, and its chip data is the song's
 * `sound` object, in that system's own shape ([org.thortracker.system.SoundSystem.soundSerializer]).
 *
 * Versions: 1 = initial format. 2 adds each pattern's editor-only `scale` (`{"root":0..11,"kind":"major"}`);
 * version 1 files have no scales and read as chromatic. 3 adds each pattern's played `length` (1-64);
 * older files read as 64 rows. 4 adds scale kinds beyond the original five (modes, harmonic/melodic minor,
 * blues, whole tone and others). 5 adds the song's optional microtuning `tuning`
 * (`{"name":...,"cents":[...],"root":note,"tolerance":5|10|20}` or null); older files have none. 6 adds the
 * song's editor-only `newPatternScale` (the scale new patterns start with); older files read as chromatic.
 * 7 adds `parkedOrders` (ascending order indices skipped by playback and export); older files park none.
 * 8 (development builds only) nested the chip data in `sound` and counted notes from C-0 in each system's own
 * naming. 9 makes every note (and the tuning root) scientific pitch: 0 = C0 (16.35 Hz), 57 = A4 = 440 Hz, so the same
 * number is the same pitch on every system. Older files are upgraded by their system
 * ([org.thortracker.system.SoundSystem.upgradeProject]; versions 1-7 are Game Boy songs). Every save writes the
 * current version.
 *
 * `backendVersion` is the version of the system's own song data ([org.thortracker.system.SoundSystem.backendVersion]),
 * upgraded by [org.thortracker.system.SoundSystem.upgradeBackend]: Game Boy 1; NES 2 adds the DPCM channel (a fifth
 * track; NES 1 songs gain an empty one), DPCM samples and instruments' DPCM mappings; SID 1; OPL3 (`opl3-fm`) 1; Mega Drive (`ym2612-fm`) 1;
 * SAM Coupé (`sam-saa1099`) 1; Creative Music System (`cms-saa1099`) 1.
 */
object ProjectCodec {
    const val FORMAT = "thor-tracker-project"
    const val VERSION = 9

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
        @SerialName("song") val song: SongFile,
    )

    /** A [Song] as stored: shared fields, the system id and the system's own `sound` object. */
    @Serializable
    private data class SongFile(
        @SerialName("title") val title: String = "Untitled",
        @SerialName("artist") val artist: String = "",
        @SerialName("comment") val comment: String = "",
        /** Absent in hand-written files: the project's backend. */
        @SerialName("backend") val backend: String? = null,
        @SerialName("ticksPerRow") val ticksPerRow: Int = 6,
        @SerialName("loopOrder") val loopOrder: Int = 0,
        @SerialName("patterns") val patterns: List<Pattern>,
        @SerialName("orders") val orders: List<Int>,
        @SerialName("sound") val sound: JsonElement,
        @SerialName("tuning") val tuning: Tuning? = null,
        @SerialName("newPatternScale") val newPatternScale: Scale = Scale.CHROMATIC,
        @SerialName("parkedOrders") val parkedOrders: List<Int> = emptyList(),
    )

    /** @throws ProjectFormatException if [song] is structurally invalid (it could not be read back). */
    fun encode(song: Song): String {
        val system = song.system
        val problems = system.structuralErrors(song)
        if (problems.isNotEmpty()) throw ProjectFormatException("Song cannot be saved: ${problems.joinToString("; ")}")
        @Suppress("UNCHECKED_CAST") // a song's sound is always its system's
        val sound = json.encodeToJsonElement(system.soundSerializer as KSerializer<SystemSound>, song.sound)
        val file = SongFile(song.title, song.artist, song.comment, system.id, song.ticksPerRow, song.loopOrder, song.patterns,
            song.orders, sound, song.tuning, song.newPatternScale, song.parkedOrders)
        return json.encodeToString(Envelope.serializer(), Envelope(FORMAT, VERSION, system.id, system.backendVersion, file))
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
        val system = backend?.let(SoundSystems::find) ?: throw ProjectFormatException(
            "Project backend \"${backend ?: "missing"}\" is not supported; this version reads ${SoundSystems.ALL.joinToString { "\"${it.id}\"" }}"
        )
        val backendVersion = (root["backendVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw ProjectFormatException("Backend data version is missing or not a number")
        if (backendVersion !in 1..system.backendVersion) {
            throw ProjectFormatException("${system.name} data version $backendVersion is not supported; this version reads 1-${system.backendVersion}")
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
        if (version < NEW_PATTERN_SCALE_VERSION && hasNewPatternScale(root)) {
            throw ProjectFormatException("Project version $version cannot contain a new-pattern scale (added in version $NEW_PATTERN_SCALE_VERSION)")
        }
        val parked = (root["song"] as? JsonObject)?.get("parkedOrders")
        if (version < PARKED_VERSION && parked is JsonArray && parked.isNotEmpty()) {
            throw ProjectFormatException("Project version $version cannot contain parked orders (added in version $PARKED_VERSION)")
        }
        val upgraded = (root["song"] as? JsonObject)?.takeIf { version < PITCH_VERSION || backendVersion < system.backendVersion }?.let { song ->
            try {
                val current = if (version < PITCH_VERSION) system.upgradeProject(song, version) else song
                val data = if (backendVersion < system.backendVersion) system.upgradeBackend(current, backendVersion) else current
                JsonObject(root + ("song" to data))
            } catch (e: IllegalArgumentException) {
                throw ProjectFormatException("Project data is invalid: ${e.message}", e)
            }
        } ?: root
        val song = try {
            val file = json.decodeFromJsonElement(Envelope.serializer(), upgraded).song
            if (file.backend != null && file.backend != backend) {
                throw ProjectFormatException("Song backend \"${file.backend}\" does not match the project backend \"$backend\"")
            }
            Song(file.title, file.artist, file.comment, file.ticksPerRow, file.loopOrder, file.patterns, file.orders,
                json.decodeFromJsonElement(system.soundSerializer, file.sound), file.tuning, file.newPatternScale, file.parkedOrders)
        } catch (e: SerializationException) {
            throw ProjectFormatException("Project data is invalid: ${e.message}", e)
        } catch (e: ProjectFormatException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw ProjectFormatException("Project data is invalid: ${e.message}", e)
        }
        val problems = system.structuralErrors(song)
        if (problems.isNotEmpty()) throw ProjectFormatException("Project data is invalid: ${problems.joinToString("; ")}")
        return song
    }

    /** First format version whose notes are scientific pitch (0 = C0, A4 = 57 = 440 Hz) for every system. */
    private const val PITCH_VERSION = 9

    /** First format version with per-pattern scales. */
    private const val SCALE_VERSION = 2

    /** First format version with per-pattern lengths. */
    private const val LENGTH_VERSION = 3

    /** First format version with scale kinds outside [ScaleKind.V2]. */
    private const val EXTENDED_SCALES_VERSION = 4

    /** First format version with a song tuning. */
    private const val TUNING_VERSION = 5

    /** First format version with the song's scale for new patterns. */
    private const val NEW_PATTERN_SCALE_VERSION = 6

    /** First format version with parked orders. */
    private const val PARKED_VERSION = 7

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

    /** True for a guiding (non-chromatic) new-pattern scale; a chromatic one is what older versions mean anyway. */
    private fun hasNewPatternScale(root: JsonObject): Boolean {
        val kind = ((root["song"] as? JsonObject)?.get("newPatternScale") as? JsonObject)?.get("kind") as? JsonPrimitive
        return kind != null && kind.content != "chromatic"
    }
}
