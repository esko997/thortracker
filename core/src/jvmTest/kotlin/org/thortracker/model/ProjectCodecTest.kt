package org.thortracker.model

import org.thortracker.system.gb.GbInstrument
import org.thortracker.system.gb.gb
import org.thortracker.system.gb.GbValidator
import org.thortracker.system.gb.GbSystem
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
        assertEquals(GbSystem.id, root["backend"]!!.jsonPrimitive.content)
        assertEquals("1", root["backendVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun cellsAreCompactArrays() {
        val text = ProjectCodec.encode(TestSongs.withCell(Songs.blank(), 0, 1, 0, Cell(60, 3, 0xC, 0x08)))
        assertTrue(text.contains("[60,3,12,8]"))
        assertTrue(text.contains("[-1,0,0,0]"))
    }

    @Test
    fun workInProgressWithExportErrorsStillRoundTrips() {
        // A cut that also carries an effect cannot be exported, but must survive autosave.
        val song = TestSongs.withCell(Songs.blank(), 0, 0, 5, Cell(Cell.CUT, 0, 0xF, 3))
        assertTrue(GbValidator.errors(song).isNotEmpty())
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
        val badNote = TestSongs.withCell(blank, 0, 0, 0, Cell(108, 1))
        val badSample = blank.copy(sound = blank.gb.copy(waves = blank.gb.waves.toMutableList().also { it[0] = List(32) { 16 } }))
        val wrongKind = blank.copy(sound = blank.gb.copy(noise = blank.gb.noise.toMutableList().also { it[0] = GbInstrument() }))
        val badLoop = blank.copy(loopOrder = 1)
        for (song in listOf(threeChannels, shortTrack, badNote, badSample, wrongKind, badLoop)) {
            assertTrue(GbValidator.structuralErrors(song).isNotEmpty())
            assertThrows(ProjectFormatException::class.java) { ProjectCodec.encode(song) }
        }
        // The same data arriving from a file is rejected on decode.
        val text = ProjectCodec.encode(blank).replaceFirst("[-1,0,0,0]", "[108,1,0,0]")
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }
    }

    @Test
    fun versionSevenProjectsUpgradeToTheSameSongAndExportIdenticalUge() {
        val v7 = TestSongs.legacy(ProjectCodec.encode(Songs.demo()), 7)
        assertTrue(v7.contains("\"version\":7,") && v7.contains("\"pulseInstruments\"") && !v7.contains("\"sound\""))
        assertTrue(v7.contains("[33,1,0,55]")) // the intro's first chord, A-5, in hUGE numbering
        val song = ProjectCodec.decode(v7)
        assertEquals(Songs.demo(), song)
        assertEquals(57, song.patterns[0].tracks[0][0].note) // hUGETracker's A-5 is A4 = 440 Hz
        val examples = listOf(java.io.File("../examples/thor-groove.uge"), java.io.File("examples/thor-groove.uge")).first { it.isFile }
        assertTrue(examples.readBytes().contentEquals(org.thortracker.backend.UgeExporter.export(song)))
        // Saving writes version 9: the chip data nested in "sound", notes in scientific pitch.
        val saved = ProjectCodec.encode(song)
        assertTrue(saved.contains("\"version\":9,") && saved.contains("\"sound\":{\"pulse\":") && saved.contains("[57,1,0,55]"))
    }

    @Test
    fun versionEightGameBoyProjectsMoveDownAnOctaveAndExportIdenticalUge() {
        val tuned = Songs.demo().copy(tuning = Tuning("12", List(12) { 100.0 * (it + 1) }, root = 48))
        val v8 = TestSongs.devVersion8(ProjectCodec.encode(tuned))
        assertTrue(v8.contains("\"version\":8,") && v8.contains("[69,1,0,55]") && v8.contains("\"root\":60"))
        val song = ProjectCodec.decode(v8)
        assertEquals(tuned, song)
        val examples = listOf(java.io.File("../examples/thor-groove.uge"), java.io.File("examples/thor-groove.uge")).first { it.isFile }
        assertTrue(examples.readBytes().contentEquals(org.thortracker.backend.UgeExporter.export(song)))
    }

    @Test
    fun versionEightNesProjectsAreUnchanged() {
        val demo = org.thortracker.system.nes.NesSystem.demo()
        val text = ProjectCodec.encode(demo)
        val v8 = Json.parseToJsonElement(text).jsonObject.let { JsonObject(it + ("version" to JsonPrimitive(8))) }.toString()
        assertEquals(demo, ProjectCodec.decode(v8))
    }

    @Test
    fun versionOneProjectsLoadWithChromaticPatterns() {
        val old = javaClass.getResource("/uge2source/thor-groove.thor")!!.readText()
        assertTrue(old.contains("\"version\":1,"))
        val song = ProjectCodec.decode(old)
        assertTrue(song.patterns.isNotEmpty() && song.patterns.all { it.scale == Scale.CHROMATIC })
        assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
    }

    @Test
    fun patternScalesRoundTripAndNeedTheirFormatVersion() {
        val blank = Songs.blank()
        val song = blank.copy(patterns = listOf(blank.patterns[0].copy(scale = Scale(9, ScaleKind.NATURAL_MINOR))))
        val text = ProjectCodec.encode(song)
        assertEquals(song, ProjectCodec.decode(text))
        val root = Json.parseToJsonElement(text).jsonObject
        val claimingVersionOne = JsonObject(root.toMutableMap().also { it["version"] = JsonPrimitive(1) }).toString()
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(claimingVersionOne) }
        val badRoot = blank.copy(patterns = listOf(blank.patterns[0].copy(scale = Scale(12, ScaleKind.MAJOR))))
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.encode(badRoot) }
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text.replaceFirst("\"root\":9", "\"root\":12")) }
    }

    @Test
    fun patternLengthsRoundTripAndNeedTheirFormatVersion() {
        val blank = Songs.blank()
        val song = blank.copy(patterns = listOf(blank.patterns[0].copy(length = 20)))
        val text = ProjectCodec.encode(song)
        assertEquals(song, ProjectCodec.decode(text))
        val root = Json.parseToJsonElement(text).jsonObject
        val claimingVersionTwo = JsonObject(root.toMutableMap().also { it["version"] = JsonPrimitive(2) }).toString()
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(claimingVersionTwo) }
        for (bad in listOf(0, 65)) {
            assertThrows(ProjectFormatException::class.java) {
                ProjectCodec.encode(blank.copy(patterns = listOf(blank.patterns[0].copy(length = bad))))
            }
        }
    }

    @Test
    fun newerScalesRoundTripAndNeedFormatFour() {
        val blank = Songs.blank()
        val song = blank.copy(patterns = listOf(blank.patterns[0].copy(scale = Scale(4, ScaleKind.PHRYGIAN_DOMINANT))))
        val text = ProjectCodec.encode(song)
        assertTrue(text.contains("\"kind\":\"phrygian-dominant\""))
        assertEquals(song, ProjectCodec.decode(text))
        val root = Json.parseToJsonElement(text).jsonObject
        val claimingThree = JsonObject(root.toMutableMap().also { it["version"] = JsonPrimitive(3) }).toString()
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(claimingThree) }
        // A version 3 file with an original scale still opens.
        val major = ProjectCodec.encode(blank.copy(patterns = listOf(blank.patterns[0].copy(scale = Scale(0, ScaleKind.MAJOR)))))
        val majorV3 = TestSongs.legacy(major, 3)
        assertEquals(ScaleKind.MAJOR, ProjectCodec.decode(majorV3).patterns[0].scale.kind)
    }

    @Test
    fun songTuningRoundTripsAndNeedsFormatFive() {
        val blank = Songs.blank()
        val just = Scl.parse("Just\n3\n5/4\n3/2\n2/1\n", "f")
        val song = blank.copy(tuning = just.copy(root = 36, tolerance = 10))
        val text = ProjectCodec.encode(song)
        assertTrue(text.contains("\"tuning\":{\"name\":\"Just\""))
        assertEquals(song, ProjectCodec.decode(text))
        val root = Json.parseToJsonElement(text).jsonObject
        val claimingFour = JsonObject(root.toMutableMap().also { it["version"] = JsonPrimitive(4) }).toString()
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(claimingFour) }
        // An untuned song written as version 4 (no tuning key) still opens.
        val plain = Json.parseToJsonElement(ProjectCodec.encode(blank)).jsonObject
        val plainSong = (plain["song"] as JsonObject).toMutableMap().also { it.remove("tuning") }
        val v4 = TestSongs.legacy(JsonObject(plain.toMutableMap().also { it["song"] = JsonObject(plainSong) }).toString(), 4)
        assertEquals(blank, ProjectCodec.decode(v4))
        // Structurally invalid tunings are neither saved nor loaded.
        for (bad in listOf(just.copy(root = 96), just.copy(root = 23), just.copy(tolerance = 15), just.copy(cents = listOf(700.0, 500.0)))) {
            assertThrows(ProjectFormatException::class.java) { ProjectCodec.encode(blank.copy(tuning = bad)) }
        }
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text.replaceFirst("\"tolerance\":10", "\"tolerance\":7")) }
    }

    @Test
    fun newPatternScaleRoundTripsAndNeedsFormatSix() {
        val blank = Songs.blank()
        val song = blank.copy(newPatternScale = Scale(9, ScaleKind.DORIAN))
        val text = ProjectCodec.encode(song)
        assertEquals(song, ProjectCodec.decode(text))
        val root = Json.parseToJsonElement(text).jsonObject
        val claimingFive = JsonObject(root.toMutableMap().also { it["version"] = JsonPrimitive(5) }).toString()
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(claimingFive) }
        // A version 5 file has no key and reads as chromatic.
        val plain = Json.parseToJsonElement(ProjectCodec.encode(blank)).jsonObject
        val plainSong = (plain["song"] as JsonObject).toMutableMap().also { it.remove("newPatternScale") }
        val v5 = TestSongs.legacy(JsonObject(plain.toMutableMap().also { it["song"] = JsonObject(plainSong) }).toString(), 5)
        assertEquals(Scale.CHROMATIC, ProjectCodec.decode(v5).newPatternScale)
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.encode(blank.copy(newPatternScale = Scale(12, ScaleKind.MAJOR))) }
    }
}
