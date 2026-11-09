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
import org.thortracker.system.SoundSystems
import org.thortracker.system.ay.AyEnvelopeSettings
import org.thortracker.system.ay.AyInstrument
import org.thortracker.system.ay.AySweep
import org.thortracker.system.ay.AyVariant
import org.thortracker.system.ay.ay
import org.thortracker.system.nes.NesEnvelope

/** Project files hold AY songs under their computer's backend; the sound names the variant and must agree with it. */
class AyProjectCodecTest {
    @Test
    fun eachComputerIsItsOwnBackendAndItsSoundNamesTheVariant() {
        for (variant in AyVariant.entries) {
            val root = Json.parseToJsonElement(ProjectCodec.encode(variant.system.demo())).jsonObject
            assertEquals(variant.engineId, root["backend"]!!.jsonPrimitive.content)
            assertEquals("1", root["backendVersion"]!!.jsonPrimitive.content)
            val song = root["song"]!!.jsonObject
            assertEquals(variant.engineId, song["backend"]!!.jsonPrimitive.content)
            val sound = song["sound"]!!.jsonObject
            assertEquals(setOf("variant", "instruments"), sound.keys)
            assertEquals(variant.fileKey, sound["variant"]!!.jsonPrimitive.content)
            val instruments = sound["instruments"]!!.jsonArray
            assertEquals(64, instruments.size)
            val lead = instruments[0].jsonObject
            assertEquals(setOf("name", "volume", "arpeggio", "pitch", "pitchRelative", "mixer", "noise", "envelope", "sweep"), lead.keys)
            assertEquals(setOf("shape", "auto", "octave", "period", "sync"), lead["envelope"]!!.jsonObject.keys)
            assertEquals(setOf("start", "end", "speed"), lead["sweep"]!!.jsonObject.keys)
            assertEquals(kotlinx.serialization.json.JsonNull, instruments[63])
        }
    }

    @Test
    fun everyFieldSurvivesARoundTrip() {
        val system = AyVariant.ATARI_ST.system
        val ins = AyInstrument(
            name = "Everything",
            volume = NesEnvelope(listOf(15, 12, 9, 6, 3, 0), loop = 2, release = 4),
            arpeggio = NesEnvelope(listOf(-64, 0, 63), loop = 0),
            pitch = NesEnvelope(listOf(1, -1), loop = 1),
            pitchRelative = true,
            mixer = NesEnvelope(listOf(3, 2, 1, 0), loop = 3),
            noise = NesEnvelope(listOf(0, 31, 15), loop = 1),
            envelope = AyEnvelopeSettings(shape = 14, auto = false, octave = -3, period = 0xBEEF, sync = true),
            sweep = AySweep(96, -96, 255),
        )
        val song = system.blank().let { it.copy(sound = it.ay.withInstrument(64, ins)) }
        val back = ProjectCodec.decode(ProjectCodec.encode(song))
        assertEquals(song, back)
        assertEquals(ins, back.ay.instrument(64))
    }

    @Test
    fun aBackendThatDisagreesWithTheSoundIsRejected() {
        val root = Json.parseToJsonElement(ProjectCodec.encode(AyVariant.MSX.system.blank())).jsonObject
        val mixed = JsonObject(root + ("backend" to JsonPrimitive("ay-zx128")) +
            ("song" to JsonObject(root["song"]!!.jsonObject + ("backend" to JsonPrimitive("ay-zx128")))))
        val e = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(mixed.toString()) }
        assertTrue(e.message!!, e.message!!.contains("The song is for MSX (AY-3-8910), not ZX Spectrum 128 (AY-3-8912)"))
        // An unknown variant name is not a project; a known one that is not the backend's is a structural error.
        val msx = ProjectCodec.encode(AyVariant.MSX.system.blank())
        assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(msx.replace("\"variant\":\"msx\"", "\"variant\":\"oric\"")) }
        val cpc = assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(msx.replace("\"variant\":\"msx\"", "\"variant\":\"cpc\"")) }
        assertTrue(cpc.message!!, cpc.message!!.contains("The song is for Amstrad CPC (AY-3-8912), not MSX (AY-3-8910)"))
        // Older systems' files are unchanged by the new ones.
        for (id in listOf("gb-huge", "ym2612-fm")) {
            val demo = SoundSystems.of(id).demo()
            assertEquals(demo, ProjectCodec.decode(ProjectCodec.encode(demo)))
        }
    }
}
