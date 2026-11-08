package org.thortracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.SidGoldenSongs
import org.thortracker.system.sid.SidChip
import org.thortracker.system.sid.SidRules
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sid.sid

/** Project files hold SID songs as backend "c64-sid", data version 1; Game Boy and NES files are unchanged. */
class SidProjectCodecTest {
    @Test
    fun sidSongsRoundTripExactly() {
        val songs = listOf(SidSystem.blank(), SidSystem.demo()) + SidGoldenSongs.ALL.values.map { it() }
        for (song in songs) assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
    }

    @Test
    fun envelopeNamesTheSidBackendAndNestsTheChipData() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(SidSystem.demo())).jsonObject
        assertEquals("c64-sid", root["backend"]!!.jsonPrimitive.content)
        assertEquals("1", root["backendVersion"]!!.jsonPrimitive.content)
        val sound = root["song"]!!.jsonObject["sound"]!!.jsonObject
        assertEquals(SidSystem.SLOTS, sound["instruments"]!!.jsonArray.size)
        assertEquals("8580", sound["chip"]!!.jsonPrimitive.content)
        val bass = sound["instruments"]!!.jsonArray[0].jsonObject
        assertEquals(setOf("name", "attack", "decay", "sustain", "release", "restart", "gateTimer", "firstWave", "vibrato", "wave", "pulse", "filter"), bass.keys)
        assertEquals("hard", bass["restart"]!!.jsonPrimitive.content)
        // Program rows are compact [left, right] pairs with their placed row.
        val filter = bass["filter"]!!.jsonObject
        assertEquals("[144,193]", filter["rows"]!!.jsonArray[0].toString())
        assertTrue(filter["at"]!!.jsonPrimitive.content.toInt() >= 1)
    }

    @Test
    fun chipSettingsAndKeyOnSurvive() {
        val song = SidSystem.blank().let { s ->
            val p = s.patterns[0]
            val tracks = p.tracks.mapIndexed { c, t -> if (c == 1) t.toMutableList().also { it[3] = Cell(note = SidRules.KEY_ON) } else t }
            s.copy(patterns = listOf(p.copy(tracks = tracks)), ticksPerRow = 9,
                sound = s.sid.copy(chip = SidChip.MOS6581, multispeed = 4, hardRestartAttackDecay = 0x0A, hardRestartSustainRelease = 0x01))
        }
        val back = ProjectCodec.decode(ProjectCodec.encode(song))
        assertEquals(song, back)
        assertEquals(SidRules.KEY_ON, back.patterns[0].tracks[1][3].note)
    }

    @Test
    fun structurallyInvalidSidFilesAreRejected() {
        val text = ProjectCodec.encode(SidSystem.blank())
        val root = Json.parseToJsonElement(text).jsonObject
        val song = root["song"]!!.jsonObject
        val sound = song["sound"]!!.jsonObject
        val shorter = JsonObject(root + ("song" to JsonObject(song + ("sound" to JsonObject(sound + ("instruments" to
            JsonArray(sound["instruments"]!!.jsonArray.drop(1))))))))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(shorter.toString()) }
        assertTrue(e.message!!, e.message!!.contains("exactly 63"))
        val bad = text.replaceFirst("[-1,0,0,0]", "[-1,0,16,1]")
        val e2 = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(bad) }
        assertTrue(e2.message!!, e2.message!!.contains("does not exist on the SID"))
        val high = text.replaceFirst("[-1,0,0,0]", "[93,1,0,0]")
        val e3 = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(high) }
        assertTrue(e3.message!!, e3.message!!.contains("C-0..G#7"))
    }

    @Test
    fun oldVersionsCannotHoldSidSongs() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(SidSystem.blank())).jsonObject
        val old = JsonObject(root + ("version" to JsonPrimitive(8)))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(old.toString()) }
        assertTrue(e.message!!, e.message!!.contains("version 9"))
    }
}
