package org.thortracker.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.thortracker.audio.i32le
import org.thortracker.system.studio.MonoPatch
import org.thortracker.system.studio.SamplerPatch
import org.thortracker.system.studio.StudioAssets
import org.thortracker.system.studio.StudioPcm
import org.thortracker.system.studio.StudioSlice
import org.thortracker.system.studio.StudioSound
import org.thortracker.system.studio.studio
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Cross-platform wire and partial-read coverage, run on JVM (Android core) and Kotlin/Native. */
class StudioProjectDocumentTest {
    private val first = StudioPcm.of(2, 44_100, floatArrayOf(-1f, -0f, 1f / 8388608, .75f, .5f, -.25f))
    private val second = StudioPcm.of(1, 96_000, floatArrayOf(-.5f, .25f))

    @Test fun embeddedStereoIsBitExactAndSharedBetweenSamplerPatches() {
        val song = sampled()
        val bytes = ProjectCodec.encodeBytes(song)
        assertContentEquals("THORBND\n".encodeToByteArray(), bytes.copyOfRange(0, 8))
        assertEquals(1, bytes.i32le(8))
        assertEquals(2, bytes.i32le(16))
        val data = 20 + bytes.i32le(12)
        // Wire fields are fixed little endian, including PCM that is not representable as signed 16-bit.
        assertEquals(1, bytes.i32le(data))
        assertEquals(2, bytes.i32le(data + 4))
        assertEquals(44_100, bytes.i32le(data + 8))
        assertEquals(6, bytes.i32le(data + 12))
        assertContentEquals(byteArrayOf(0, 0, 0x80.toByte(), 0xbf.toByte(), 0, 0, 0, 0x80.toByte()),
            bytes.copyOfRange(data + 16, data + 24))
        // This independent, deliberately slow CRC checks an actual complete wire document.
        var crc = -1
        for (i in 0 until bytes.size - 4) {
            crc = crc xor (bytes[i].toInt() and 255)
            repeat(8) { crc = (crc ushr 1) xor (if (crc and 1 == 1) 0xedb8_8320.toInt() else 0) }
        }
        assertEquals(crc.inv(), bytes.i32le(bytes.size - 4))

        var at = 0
        val reopened = ProjectCodec.readDocument { destination, offset, length ->
            if (at == bytes.size) -1 else {
                val used = minOf(3, length, bytes.size - at)
                bytes.copyInto(destination, offset, at, at + used)
                at += used
                used
            }
        }
        assertEquals(song, reopened)
        val firstId = assertNotNull((reopened.studio.patch(1) as SamplerPatch).assetId)
        val repeated = assertNotNull((reopened.studio.patch(7) as SamplerPatch).assetId)
        val pcm = assertNotNull(reopened.studio.assets[firstId])
        assertSame(pcm, reopened.studio.assets[repeated])
        assertEquals(44_100, pcm.sampleRate)
        assertEquals(3, pcm.frames)
        assertEquals((-0f).toRawBits(), pcm.sample(1).toRawBits())
        assertEquals((1f / 8388608).toRawBits(), pcm.sample(2).toRawBits())
        assertContentEquals(bytes, ProjectCodec.encodeBytes(reopened))
        assertFailsWith<ProjectFormatException> { ProjectCodec.encode(song) }
    }

    @Test fun rejectsTruncatedCorruptUnsupportedAndOversizedRecordsBeforeUse() {
        val bytes = ProjectCodec.encodeBytes(sampled())
        val data = 20 + bytes.i32le(12)
        fun reject(content: ByteArray, problem: String) {
            val error = assertFailsWith<ProjectFormatException> { ProjectCodec.decodeBytes(content) }
            assertTrue(error.message.orEmpty().contains(problem, ignoreCase = true), error.message)
        }
        reject(bytes.copyOf(bytes.size - 2), "truncated")
        reject(bytes + byteArrayOf(1), "extra data")
        reject(bytes.copyOf().apply { this[8] = 2 }, "bundle version 2")
        reject(bytes.copyOf().apply { this[data + 16] = 1 }, "checksum")
        reject(bytes.copyOf().apply { this[data + 15] = 0x7f }, "16 MiB")
        val secondRecord = data + 16 + first.sampleCount * Float.SIZE_BYTES
        reject(bytes.copyOf().apply { this[secondRecord] = 1 }, "duplicate studio asset id")

        val source = sampled()
        val manifest = bytes.decodeToString(20, data).replace("\"assetId\":1", "\"assetId\":32")
        val detached = ProjectDocument(manifest.encodeToByteArray(), source.studio.assets)
        val invalid = ByteArray(detached.size)
        var at = 0
        detached.writeTo { chunk, count -> chunk.copyInto(invalid, at, endIndex = count); at += count }
        reject(invalid, "missing asset 32")
    }

    @Test fun legacyMonoMigrationAndChipJsonRemainReadable() {
        val chip = Songs.blank().copy(title = "Chip JSON")
        val chipBytes = ProjectCodec.encodeBytes(chip)
        assertEquals('{'.code.toByte(), chipBytes[0])
        assertEquals(chip, ProjectCodec.decodeBytes(chipBytes, androidMidi = true))
        assertContentEquals(ProjectCodec.encode(chip).encodeToByteArray(), chipBytes)

        val mono = StudioSound().system.blank()
        val current = Json.parseToJsonElement(ProjectCodec.encode(mono)).jsonObject
        val saved = current.getValue("song").jsonObject
        val oldPatches = List(StudioSound.SLOTS) { index ->
            if (index == 0) JsonObject(mapOf("name" to JsonPrimitive("1999 mono"), "cutoff" to JsonPrimitive(17)))
            else JsonObject(emptyMap())
        }
        val v1Sound = JsonObject(mapOf("patches" to JsonArray(oldPatches)))
        val v1 = JsonObject(current + mapOf("backendVersion" to JsonPrimitive(1), "song" to JsonObject(saved + ("sound" to v1Sound))))
        val migrated = ProjectCodec.decodeBytes(v1.toString().encodeToByteArray())
        assertEquals(MonoPatch(name = "1999 mono", cutoff = 17), migrated.studio.patch(1))
        assertTrue(migrated.studio.assets.ids.isEmpty())
        assertEquals(migrated, ProjectCodec.decodeBytes(ProjectCodec.encodeBytes(migrated)))
    }

    private fun sampled(): Song {
        val a = StudioAssets.EMPTY.add(first)
        val b = a.assets.add(second)
        val sound = StudioSound(assets = b.assets)
            .withPatch(1, SamplerPatch("L-R / 24-bit", a.id, slices = listOf(StudioSlice(12, 1, 3)), defaultSlice = 12))
            .withPatch(2, SamplerPatch("96 kHz", b.id))
            .withPatch(7, SamplerPatch("Same source", a.id))
        return sound.system.blank().copy(sound = sound, title = "Portable − Break")
    }
}
