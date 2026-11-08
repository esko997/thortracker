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
import org.thortracker.system.nes.NesVrc6System
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.nes.DpcmMapping
import org.thortracker.system.nes.DpcmSample
import org.thortracker.system.nes.DpcmStarterKit
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.nes
import kotlinx.serialization.json.JsonArray

/** Project files hold NES songs as backend "nes-famistudio"; Game Boy files are unchanged. */
class NesProjectCodecTest {
    @Test
    fun nesSongsRoundTripExactly() {
        val songs = listOf(NesSystem.blank(), NesSystem.demo(), NesVrc6System.blank(), NesVrc6System.demo()) +
            NesGoldenSongs.ALL.values.map { it() }
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
        assertEquals(setOf("name", "volume", "arpeggio", "pitch", "pitchRelative", "duty", "dpcm"), lead.keys)
        assertEquals(setOf("values", "loop", "release"), lead["volume"]!!.jsonObject.keys)
    }

    /** The VRC6 variant is its own backend; its sound names the expansion and its VRC6 sounds their chip. */
    @Test
    fun vrc6SongsNameTheirVariant() {
        val demo = NesVrc6System.demo()
        assertEquals(NesVrc6System, demo.system)
        val root = Json.parseToJsonElement(ProjectCodec.encode(demo)).jsonObject
        assertEquals("nes-famistudio-vrc6", root["backend"]!!.jsonPrimitive.content)
        assertEquals("2", root["backendVersion"]!!.jsonPrimitive.content)
        val sound = root["song"]!!.jsonObject["sound"]!!.jsonObject
        assertEquals("vrc6", sound["expansion"]!!.jsonPrimitive.content)
        val instruments = sound["instruments"]!!.jsonArray.map { it.jsonObject }
        assertTrue(instruments.any { it["chip"]?.jsonPrimitive?.content == "vrc6" && it["sawVolume"]?.jsonPrimitive?.content == "full" })
        // 2A03 songs are stored exactly as before: no expansion, chip or saw volume keys.
        val plain = Json.parseToJsonElement(ProjectCodec.encode(NesSystem.demo())).jsonObject["song"]!!.jsonObject["sound"]!!.jsonObject
        assertTrue(plain.keys.toString(), "expansion" !in plain.keys)
        assertTrue(plain["instruments"]!!.jsonArray.none { "chip" in it.jsonObject.keys || "sawVolume" in it.jsonObject.keys })
        // A file whose backend and sound disagree is rejected.
        val mixed = JsonObject(root + ("backend" to JsonPrimitive("nes-famistudio")) +
            ("song" to JsonObject(root["song"]!!.jsonObject + ("backend" to JsonPrimitive("nes-famistudio")))))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(mixed.toString()) }
        assertTrue(e.message!!, e.message!!.contains("NES + VRC6"))
    }

    /**
     * Each N163 channel count is its own backend; its sound names the expansion with the count, its N163 sounds their chip
     * and waves, and a file whose count disagrees with its backend is rejected (the channels and RAM differ).
     */
    @Test
    fun n163SongsNameTheirChannelCount() {
        for (system in org.thortracker.system.nes.NesN163System.ALL) {
            for (song in listOf(system.blank(), system.demo())) assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        }
        val demo = org.thortracker.system.nes.NesN163System.of(3).demo()
        val root = Json.parseToJsonElement(ProjectCodec.encode(demo)).jsonObject
        assertEquals("nes-famistudio-n163-3", root["backend"]!!.jsonPrimitive.content)
        val sound = root["song"]!!.jsonObject["sound"]!!.jsonObject
        assertEquals("n163-3", sound["expansion"]!!.jsonPrimitive.content)
        val instruments = sound["instruments"]!!.jsonArray.map { it.jsonObject }
        assertTrue(instruments.any { it["chip"]?.jsonPrimitive?.content == "n163" && "wave" in it.keys })
        assertTrue(instruments.none { it["chip"] == null && "wave" in it.keys })
        val other = JsonObject(root + ("backend" to JsonPrimitive("nes-famistudio-n163-4")) +
            ("song" to JsonObject(root["song"]!!.jsonObject + ("backend" to JsonPrimitive("nes-famistudio-n163-4")))))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(other.toString()) }
        assertTrue(e.message!!, e.message!!.contains("NES + N163, 3 channels"))
    }

    /**
     * The FDS variant is its own backend; its sound names the expansion, its FDS sounds their chip and their waves and
     * modulation (left out at FamiStudio's defaults, so 2A03 sounds are stored as before); every field survives.
     */
    @Test
    fun fdsSongsNameTheirVariantAndKeepEveryField() {
        val system = org.thortracker.system.nes.NesFdsSystem
        for (song in listOf(system.blank(), system.demo())) assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        val odd = system.blank().let { s ->
            val bank = s.nes.instruments.toMutableList()
            bank[2] = NesInstrument("Odd", chip = org.thortracker.system.nes.NesChip.FDS, fds = org.thortracker.system.nes.FdsSound(
                waves = listOf(List(64) { it }, List(64) { 63 - it }), repeat = listOf(3, 15), loop = 0, release = 1,
                masterVolume = org.thortracker.system.nes.FdsMasterVolume.TWO_FIFTHS, modulation = List(32) { it * 4 - 64 },
                modSpeed = 4095 and 0xF7F, modDepth = 63, modDelay = 255, autoMod = true, autoModNumer = 32, autoModDenom = 31))
            s.copy(sound = s.nes.copy(instruments = bank))
        }
        assertEquals(odd, ProjectCodec.decode(ProjectCodec.encode(odd)))
        val root = Json.parseToJsonElement(ProjectCodec.encode(system.demo())).jsonObject
        assertEquals("nes-famistudio-fds", root["backend"]!!.jsonPrimitive.content)
        val sound = root["song"]!!.jsonObject["sound"]!!.jsonObject
        assertEquals("fds", sound["expansion"]!!.jsonPrimitive.content)
        val instruments = sound["instruments"]!!.jsonArray.map { it.jsonObject }
        assertTrue(instruments.any { it["chip"]?.jsonPrimitive?.content == "fds" && "fds" in it.keys })
        assertTrue(instruments.none { it["chip"] == null && "fds" in it.keys })
        val mixed = JsonObject(root + ("backend" to JsonPrimitive("nes-famistudio")) +
            ("song" to JsonObject(root["song"]!!.jsonObject + ("backend" to JsonPrimitive("nes-famistudio")))))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(mixed.toString()) }
        assertTrue(e.message!!, e.message!!.contains("NES + FDS"))
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
    fun dpcmSamplesAndMappingsSurvive() {
        val song = NesSystem.blank().let { s ->
            val sound = DpcmStarterKit.addTo(s.nes, 3)
            val bank = sound.instruments.toMutableList()
            bank[4] = NesInstrument("Loop", dpcm = mapOf(60 to DpcmMapping(4, pitch = 9, loop = true, dmcInitial = 7)))
            s.copy(sound = sound.copy(instruments = bank, samples = sound.samples + DpcmSample("odd", listOf(1, 2, 255), dmcInitial = 63)))
        }
        assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
    }

    /** NES data version 1 (four channels, no samples): every pattern gains an empty DPCM track. */
    @Test
    fun nesDataVersion1GainsAnEmptyDpcmTrack() {
        val song = NesSystem.demo().let { s ->
            s.copy(patterns = s.patterns.map { p -> p.copy(tracks = p.tracks.mapIndexed { ch, t -> if (ch == NesRules.DPCM) List(t.size) { Cell.BLANK } else t }) },
                sound = s.nes.copy(instruments = s.nes.instruments.map { it.copy(dpcm = emptyMap()) }, samples = emptyList()))
        }
        val root = Json.parseToJsonElement(ProjectCodec.encode(song)).jsonObject
        val file = root["song"]!!.jsonObject
        val sound = file["sound"]!!.jsonObject
        val v1 = JsonObject(root + ("backendVersion" to JsonPrimitive(1)) + ("song" to JsonObject(file +
            ("patterns" to JsonArray(file["patterns"]!!.jsonArray.map { p ->
                JsonObject(p.jsonObject + ("tracks" to JsonArray(p.jsonObject["tracks"]!!.jsonArray.take(4))))
            })) +
            ("sound" to JsonObject(mapOf("instruments" to JsonArray(sound["instruments"]!!.jsonArray.map { JsonObject(it.jsonObject - "dpcm") })))))))
        assertEquals(song, ProjectCodec.decode(v1.toString()))
        val newer = JsonObject(root + ("backendVersion" to JsonPrimitive(3)))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(newer.toString()) }
        assertTrue(e.message!!, e.message!!.contains("reads 1-2"))
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
