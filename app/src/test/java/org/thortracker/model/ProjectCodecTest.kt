package org.thortracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectCodecTest {
    @Test
    fun demoAndBlankRoundTripExactly() {
        for (song in listOf(Songs.demo(), Songs.blank())) {
            assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        }
    }

    @Test
    fun envelopeIdentifiesFormatVersionAndBackend() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(Songs.blank())).jsonObject
        assertEquals(ProjectCodec.FORMAT, root["format"]!!.jsonPrimitive.content)
        assertEquals(ProjectCodec.VERSION.toString(), root["version"]!!.jsonPrimitive.content)
        assertEquals(Songs.BACKEND, root["backend"]!!.jsonPrimitive.content)
        assertEquals(ProjectCodec.BACKEND_VERSION.toString(), root["backendVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun cellsAreCompactArrays() {
        val text = ProjectCodec.encode(TestSongs.withCell(Songs.blank(), 0, 1, 0, Cell(24, 3, 0xC, 0x08)))
        assertTrue(text.contains("[24,3,12,8]"))
        assertTrue(text.contains("[-1,0,0,0]"))
    }

    @Test
    fun workInProgressWithExportErrorsStillRoundTrips() {
        // A cut that also carries an effect cannot be exported, but must survive autosave.
        val song = TestSongs.withCell(Songs.blank(), 0, 0, 5, Cell(Cell.CUT, 0, 0xF, 3))
        assertTrue(SongValidator.errors(song).isNotEmpty())
        assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
    }

    @Test
    fun rejectsMalformedAndForeignDocuments() {
        val valid = ProjectCodec.encode(Songs.blank())
        val root = Json.parseToJsonElement(valid).jsonObject
        fun with(key: String, value: kotlinx.serialization.json.JsonElement) =
            JsonObject(root.toMutableMap().also { it[key] = value }).toString()

        val bad = listOf(
            "",
            "not json",
            "[]",
            "{\"format\":\"something-else\"}",
            with("format", JsonPrimitive("sortie-project")),
            with("version", JsonPrimitive(ProjectCodec.VERSION + 1)),
            with("version", JsonPrimitive("1")),
            with("backend", JsonPrimitive("gba-m4a")),
            with("backendVersion", JsonPrimitive(99)),
            with("extra", JsonPrimitive(true)),
            valid.replaceFirst("[-1,0,0,0]", "[-1,0,0]"),
            valid.replaceFirst("\"ticksPerRow\":6", "\"ticksPerRow\":6.5"),
            valid.replaceFirst("\"orders\":[0]", "\"orders\":[3]"),
            valid.replaceFirst("\"orders\":[0]", "\"orders\":[]"),
            valid.substring(0, valid.length / 2),
        )
        for (text in bad) {
            assertThrows("should reject: ${text.take(80)}", ProjectFormatException::class.java) { ProjectCodec.decode(text) }
        }
    }

    @Test
    fun rejectsStructurallyInvalidSongsOnDecodeAndEncode() {
        val blank = Songs.blank()
        val threeChannels = blank.copy(patterns = listOf(Pattern.blank(3)))
        val shortTrack = blank.copy(patterns = listOf(Pattern("x", List(4) { List(32) { Cell.BLANK } })))
        val badNote = TestSongs.withCell(blank, 0, 0, 0, Cell(72, 1))
        val badSample = blank.copy(waves = blank.waves.toMutableList().also { it[0] = List(32) { 16 } })
        val wrongKind = blank.copy(noiseInstruments = blank.noiseInstruments.toMutableList().also { it[0] = Instrument() })
        val badLoop = blank.copy(loopOrder = 1)
        for (song in listOf(threeChannels, shortTrack, badNote, badSample, wrongKind, badLoop)) {
            assertTrue(SongValidator.structuralErrors(song).isNotEmpty())
            assertThrows(ProjectFormatException::class.java) { ProjectCodec.encode(song) }
        }
        // The same data arriving from a file is rejected on decode.
        val text = ProjectCodec.encode(blank).replaceFirst("[-1,0,0,0]", "[72,1,0,0]")
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }
    }
}
