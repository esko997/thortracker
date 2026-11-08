package org.thortracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.NesGoldenSongs
import org.thortracker.system.SoundSystems
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.NesInstrument
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.nes

/** Project files hold NES songs as backend "nes-famistudio"; Game Boy files are unchanged. */
class NesProjectCodecTest {
    @Test
    fun nesSongsRoundTripExactly() {
        val songs = listOf(NesSystem.blank(), NesSystem.demo()) + NesGoldenSongs.ALL.values.map { it() }
        for (song in songs) assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
    }

    @Test
    fun envelopeNamesTheNesBackendAndNestsTheBank() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(NesSystem.demo())).jsonObject
        assertEquals("nes-famistudio", root["backend"]!!.jsonPrimitive.content)
        assertEquals("2", root["backendVersion"]!!.jsonPrimitive.content)
        val song = root["song"]!!.jsonObject
        assertEquals("nes-famistudio", song["backend"]!!.jsonPrimitive.content)
        val instruments = song["sound"]!!.jsonObject["instruments"]!!.jsonArray
        assertEquals(NesSystem.SLOTS, instruments.size)
        val lead = instruments[0].jsonObject
        assertEquals(setOf("name", "volume", "arpeggio", "pitch", "pitchRelative", "duty"), lead.keys)
        assertEquals(setOf("values", "loop", "release"), lead["volume"]!!.jsonObject.keys)
    }

    @Test
    fun envelopeDetailsSurvive() {
        val song = NesSystem.blank().let { s ->
            val bank = s.nes.instruments.toMutableList()
            bank[5] = NesInstrument("Odd", volume = NesEnvelope(listOf(3, 2, 1), loop = 1, release = 2),
                pitch = NesEnvelope(listOf(-64, 63), loop = 0), pitchRelative = true, arpeggio = NesEnvelope(listOf(-12)))
            s.copy(sound = s.nes.copy(instruments = bank))
        }
        assertEquals(song.nes.instruments[5], ProjectCodec.decode(ProjectCodec.encode(song)).nes.instruments[5])
    }

    @Test
    fun gameBoySongsStillWriteTheGameBoyBackend() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(Songs.demo())).jsonObject
        assertEquals("gb-huge", root["backend"]!!.jsonPrimitive.content)
        assertEquals(SoundSystems.DEFAULT.id, "gb-huge")
    }

    @Test
    fun structurallyInvalidNesFilesAreRejected() {
        val text = ProjectCodec.encode(NesSystem.blank())
        val root = Json.parseToJsonElement(text).jsonObject
        val song = root["song"]!!.jsonObject
        val sound = song["sound"]!!.jsonObject
        // Drop one instrument: NES banks have exactly 64.
        val shorter = JsonObject(root + ("song" to JsonObject(song + ("sound" to JsonObject(sound + ("instruments" to
            kotlinx.serialization.json.JsonArray(sound["instruments"]!!.jsonArray.drop(1))))))))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(shorter.toString()) }
        assertTrue(e.message!!, e.message!!.contains("exactly 64"))
        // A cell effect the NES does not have (B).
        val bad = text.replaceFirst("[-1,0,0,0]", "[-1,0,11,1]")
        val e2 = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(bad) }
        assertTrue(e2.message!!, e2.message!!.contains("does not exist on the NES"))
    }

    @Test
    fun oldVersionsCannotHoldNesSongs() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(NesSystem.blank())).jsonObject
        val old = JsonObject(root + ("version" to JsonPrimitive(7)))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(old.toString()) }
        assertTrue(e.message!!, e.message!!.contains("version 8"))
    }
}
