package org.thortracker.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** A local catalogue, not song data. Entry identities and Scala source are retained verbatim. */
@Serializable
data class TuningPack(
    val version: Int = 1,
    val id: String,
    val name: String,
    val attribution: String = "",
    val entries: List<TuningPackEntry>,
)

@Serializable
data class TuningPackEntry(
    val id: String,
    val name: String,
    val category: String,
    val scl: String,
    val attribution: String = "",
)

data class InstalledTuningPack(val pack: TuningPack, val bundled: Boolean)

/** Structural validation only: an unsupported or malformed scale remains a discoverable catalogue entry. */
object TuningPackCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 10000
    const val MAX_SCL_BYTES = 64 * 1024
    const val MAX_ATTRIBUTION_BYTES = 16 * 1024
    private val packId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val json = Json { encodeDefaults = true }

    fun decode(text: String): TuningPack {
        require(utf8Size(text) <= MAX_BYTES) { "Tuning pack exceeds $MAX_BYTES UTF-8 bytes" }
        val pack = try {
            val root = json.parseToJsonElement(text)
            require(root is JsonObject) { "Invalid tuning pack JSON: expected an object" }
            root["version"]?.let { version ->
                require(version is JsonPrimitive && !version.isString && version.intOrNull == 1) {
                    "Unsupported tuning pack version; expected integer 1"
                }
            }
            json.decodeFromJsonElement(TuningPack.serializer(), root)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid tuning pack JSON: ${e.message}", e)
        }
        validate(pack)
        return pack
    }

    fun encode(pack: TuningPack): String {
        validate(pack)
        return json.encodeToString(TuningPack.serializer(), pack).also {
            require(utf8Size(it) <= MAX_BYTES) { "Tuning pack exceeds $MAX_BYTES UTF-8 bytes" }
        }
    }

    private fun validate(pack: TuningPack) {
        require(pack.version == 1) { "Unsupported tuning pack version ${pack.version}; supported version is 1" }
        require(packId.matches(pack.id)) { "Invalid tuning pack id; use 1–64 ASCII letters, digits, dots, underscores or hyphens, beginning with a letter or digit" }
        require(pack.entries.size <= MAX_ENTRIES) { "Tuning pack has ${pack.entries.size} entries; at most $MAX_ENTRIES are allowed" }
        require(utf8Size(pack.attribution) <= MAX_ATTRIBUTION_BYTES) { "Tuning pack attribution exceeds $MAX_ATTRIBUTION_BYTES UTF-8 bytes" }
        val ids = HashSet<String>()
        for (entry in pack.entries) {
            require(entry.id.isNotEmpty()) { "Tuning pack entry id is empty" }
            require(ids.add(entry.id)) { "Duplicate tuning pack entry id: ${display(entry.id)}" }
            require(utf8Size(entry.scl) <= MAX_SCL_BYTES) { "Scala source for ${display(entry.id)} exceeds $MAX_SCL_BYTES UTF-8 bytes" }
            require(utf8Size(entry.attribution) <= MAX_ATTRIBUTION_BYTES) { "Attribution for ${display(entry.id)} exceeds $MAX_ATTRIBUTION_BYTES UTF-8 bytes" }
        }
    }

    /** Controls are escaped only at the UI boundary, never in ids, source, search or stored attribution. */
    fun display(text: String, multiline: Boolean = false): String = buildString {
        for (c in text) when {
            c == '\n' && multiline -> append(c)
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 32 || c.code in 127..159 || c.code in 0x2028..0x202e || c.code in 0x2066..0x2069 -> {
                append("\\u")
                append(c.code.toString(16).padStart(4, '0'))
            }
            else -> append(c)
        }
    }

    /** Avoid allocating a second full pack just to enforce its UTF-8 byte boundary. */
    private fun utf8Size(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i++].code
            bytes += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xd800..0xdbff && i < text.length && text[i].code in 0xdc00..0xdfff -> { i++; 4 }
                c in 0xd800..0xdfff -> throw IllegalArgumentException("Tuning pack contains an unpaired Unicode surrogate")
                else -> 3
            }
        }
        return bytes
    }
}
