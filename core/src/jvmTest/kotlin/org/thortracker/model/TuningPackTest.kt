package org.thortracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TuningPackTest {
    private val entry = TuningPackEntry("original/path.scl", "Name", "Category", "Name\n2\n3/2\n3/1\n", "Entry author")
    private val pack = TuningPack(id = "test-pack", name = "Collection", attribution = "Pack author", entries = listOf(entry))

    @Test fun sourceAndUniqueIdentitiesRoundTripEvenWhenMusicIsIdenticalOrInvalid() {
        val source = "\uFEFF! comment\r\n\r\n2\r\n3/2fifth\r\n3/1 period\r\n"
        val originals = listOf(
            entry.copy(id = "dir/\u0001first.scl", name = "", category = "\tcategory", scl = source),
            entry.copy(id = "elsewhere/second.scl", scl = source),
            entry.copy(id = "invalid.scl", scl = "not a scale\n0\n"),
        )
        val original = pack.copy(entries = originals)
        assertEquals(original, TuningPackCodec.decode(TuningPackCodec.encode(original)))
        assertThrows(IllegalArgumentException::class.java) { Scl.parse(originals.last().scl, "") }
    }

    @Test fun displayEscapesControlsWithoutChangingStoredFields() {
        val original = pack.copy(name = "A\nB\t\u0000\u202e", entries = listOf(entry.copy(id = "x\r\ny")))
        val decoded = TuningPackCodec.decode(TuningPackCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals("A\\nB\\t\\u0000\\u202e", TuningPackCodec.display(decoded.name))
        assertEquals("x\\r\\ny", TuningPackCodec.display(decoded.entries.single().id))
        assertEquals("A\nB\\t\\u0000\\u202e", TuningPackCodec.display(decoded.name, multiline = true))
    }

    @Test fun duplicateIdsAreRejectedButNoPathIsRewrittenOrExtracted() {
        assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.encode(pack.copy(entries = listOf(entry, entry))) }
        val names = listOf("../original.scl", "/absolute.scl", "folder\\name.scl", "A.scl", "a.scl")
        val original = pack.copy(entries = names.map { entry.copy(id = it) })
        assertEquals(names, TuningPackCodec.decode(TuningPackCodec.encode(original)).entries.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.encode(pack.copy(entries = listOf(entry.copy(id = "")))) }
    }

    @Test fun onlyPackIdsCanBecomeStorageNames() {
        for (id in listOf("", "..", "../x", "a/b", "a\\b", ".hidden", "x\n", "é", "a".repeat(65))) {
            assertThrows(id, IllegalArgumentException::class.java) { TuningPackCodec.encode(pack.copy(id = id)) }
        }
        for (id in listOf("A", "a.b_c-9", "a".repeat(64))) {
            assertEquals(id, TuningPackCodec.decode(TuningPackCodec.encode(pack.copy(id = id))).id)
        }
    }

    @Test fun versionsAndJsonStructureAreStrict() {
        val json = Json.parseToJsonElement(TuningPackCodec.encode(pack)) as JsonObject
        for (bad in listOf(
            JsonObject(json + ("version" to JsonPrimitive(2))),
            JsonObject(json + ("version" to JsonPrimitive("1"))),
            JsonObject(json + ("extra" to JsonPrimitive(true))),
            JsonObject(json - "entries"),
        )) assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.decode(bad.toString()) }
        assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.decode("[]") }
    }

    @Test fun boundariesUseUtf8BytesAndNeverDropEntries() {
        val scl = "é".repeat(TuningPackCodec.MAX_SCL_BYTES / 2)
        val credit = "é".repeat(TuningPackCodec.MAX_ATTRIBUTION_BYTES / 2)
        val boundary = pack.copy(attribution = credit, entries = listOf(entry.copy(scl = scl, attribution = credit)))
        assertEquals(boundary, TuningPackCodec.decode(TuningPackCodec.encode(boundary)))
        for (bad in listOf(
            boundary.copy(attribution = credit + "a"),
            pack.copy(entries = listOf(entry.copy(scl = scl + "a"))),
            pack.copy(entries = listOf(entry.copy(attribution = credit + "a"))),
            pack.copy(entries = List(TuningPackCodec.MAX_ENTRIES + 1) { entry.copy(id = "$it") }),
        )) assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.encode(bad) }
        val maximumCount = pack.copy(entries = List(TuningPackCodec.MAX_ENTRIES) { entry.copy(id = "$it", scl = "") })
        assertEquals(maximumCount.entries, TuningPackCodec.decode(TuningPackCodec.encode(maximumCount)).entries)
        val tooLong = " ".repeat(TuningPackCodec.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.decode(tooLong) }
        assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.encode(pack.copy(name = tooLong)) }
        // Input byte bounds also apply before JSON parsing; many non-ASCII characters can exceed them sooner.
        assertThrows(IllegalArgumentException::class.java) { TuningPackCodec.decode("é".repeat(TuningPackCodec.MAX_BYTES / 2 + 1)) }
    }
}
