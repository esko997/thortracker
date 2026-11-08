package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.ws.WsSystem
import org.thortracker.system.ws.WscSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/ws/ws_song.py`, through `ws_golden.py`): the
 * two songs of [WsGoldenSongs] (an original-model song with every lane kind, sweep, taps, CPU-fed samples, shared
 * patterns, envelopes and sample bytes, unlowered fields of other kinds and feeds, four effect columns and the shared
 * header settings; a Color song with Hyper Voice, Sound DMA on both sample lanes, a stereo sample, backwards and loop
 * flags, signed copies shared between identical samples, a CPU-fed sample without one, and slot 64) must lower to the
 * same 131072 bytes.
 */
class WsGoldenTest {
    private val dir = File("src/jvmTest/resources/ws-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(WsPlayerImage.IMAGE_SIZE, expected.size)
        assertEquals(WsPlayerImage.IMAGE_SIZE, actual.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)

    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)

    private fun record(slot: Int) = WsPlayerImage.OFF_INSTRUMENTS + WsPlayerImage.INSTRUMENT_SIZE * (slot - 1)

    private fun envelope(slot: Int, k: Int) = record(slot) + WsPlayerImage.I_ENVELOPES + WsPlayerImage.ENVELOPE_BYTES * k

    private fun sampleEntry(s: Int) = WsPlayerImage.OFF_SAMPLES + WsPlayerImage.SAMPLE_ENTRY_SIZE * s

    @Test
    fun monoSongLowersToTheReferenceImage() {
        val song = WsGoldenSongs.mono()
        assertEquals(emptyList<String>(), WsSystem.errors(song))
        val image = WsLowering.lower(song)
        assertSameImage(fixture("mono"), image)
        assertEquals(WsPlayerImage.MAGIC, image.copyOfRange(0, 4).decodeToString())
        assertEquals(WsPlayerImage.MODEL_MONO, u8(image, WsPlayerImage.H_MODEL))
        assertEquals(2, u16(image, WsPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, WsPlayerImage.H_LOOP))
        assertEquals(6, u8(image, WsPlayerImage.H_INSTRUMENTS))
        assertEquals(3, u8(image, WsPlayerImage.H_WAVES))
        assertEquals(3, u8(image, WsPlayerImage.H_SAMPLES))
        assertEquals(3, u8(image, WsPlayerImage.H_TIMER))
        assertEquals(2, u8(image, WsPlayerImage.H_SHIFT))
        assertEquals(WsPlayerImage.OUTPUT_SPEAKER, u8(image, WsPlayerImage.H_OUTPUT))
        // The original model's lane 4 pattern entries are 0; its samples have no signed copies.
        for (o in 0 until 2) assertEquals(0, u16(image, WsPlayerImage.OFF_ORDERS + 2 * (5 * o + 4)))
        for (s in 0 until 3) assertEquals(0, u8(image, sampleEntry(s) + WsPlayerImage.S_FLAGS))
        // The tone's sweep is written (s8), the noise's LFSR reset and relative pitch; the sweep of the noise is not.
        assertEquals(0xFB, u8(image, record(1) + WsPlayerImage.I_SWEEP))
        assertEquals(3, u8(image, record(1) + WsPlayerImage.I_SWEEP_TIME))
        assertEquals(WsPlayerImage.FLAG_DEFINED or WsPlayerImage.KIND_NOISE or WsPlayerImage.FLAG_RELATIVE or WsPlayerImage.FLAG_LFSR_RESET,
            u8(image, record(2) + WsPlayerImage.I_FLAGS))
        assertEquals(5, u8(image, record(2) + WsPlayerImage.I_SOURCE))
        assertEquals(0, u8(image, record(2) + WsPlayerImage.I_SWEEP))
        // The noise's tap sequence is its fourth envelope; the one-shot's loop start is 0; slots 1 and 5 share a volume.
        assertEquals(3, u8(image, envelope(2, WsPlayerImage.ENV_WAVE) + 2))
        assertEquals(0, u16(image, record(5) + WsPlayerImage.I_LOOP_START))
        assertEquals(u16(image, envelope(1, WsPlayerImage.ENV_VOLUME)), u16(image, envelope(5, WsPlayerImage.ENV_VOLUME)))
        assertTrue(u8(image, record(4) + WsPlayerImage.I_FLAGS) == 0)
        // Identical samples share their bytes.
        assertEquals(u32(image, sampleEntry(0)), u32(image, sampleEntry(1)))
        assertEquals(WsLowering.dataSize(song), u32(image, WsPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun colorSongLowersToTheReferenceImage() {
        val song = WsGoldenSongs.color()
        assertEquals(emptyList<String>(), WscSystem.errors(song))
        val image = WsLowering.lower(song)
        assertSameImage(fixture("color"), image)
        assertEquals(WsPlayerImage.MODEL_COLOR, u8(image, WsPlayerImage.H_MODEL))
        assertEquals(64, u8(image, WsPlayerImage.H_INSTRUMENTS))
        assertEquals(4, u8(image, WsPlayerImage.H_SAMPLES))
        assertEquals(WsPlayerImage.OUTPUT_HEADPHONES, u8(image, WsPlayerImage.H_OUTPUT))
        // DMA records: the rate in the root byte, backwards and DMA flags, no arpeggio or pitch and no relative flag.
        assertEquals(WsPlayerImage.FLAG_DEFINED or WsPlayerImage.KIND_SAMPLE or WsPlayerImage.FLAG_DMA or WsPlayerImage.FLAG_REVERSE,
            u8(image, record(2) + WsPlayerImage.I_FLAGS))
        assertEquals(3, u8(image, record(2) + WsPlayerImage.I_ROOT))
        assertEquals(0, u8(image, envelope(2, WsPlayerImage.ENV_ARPEGGIO) + 2))
        assertEquals(WsPlayerImage.ENV_NONE, u8(image, envelope(2, WsPlayerImage.ENV_PITCH) + 3))
        assertEquals(1, u8(image, record(3) + WsPlayerImage.I_LOOP))
        // Signed copies: samples 0-2 (DMA instruments play them), shared between the identical 0 and 2; none for 3.
        val stereoSigned = WsPlayerImage.SAMPLE_STEREO or WsPlayerImage.SAMPLE_SIGNED
        assertEquals(listOf(WsPlayerImage.SAMPLE_SIGNED, stereoSigned, WsPlayerImage.SAMPLE_SIGNED, 0),
            (0 until 4).map { u8(image, sampleEntry(it) + WsPlayerImage.S_FLAGS) })
        assertEquals(8, u32(image, sampleEntry(1) + WsPlayerImage.S_FRAMES))
        assertEquals(u32(image, sampleEntry(0) + WsPlayerImage.S_SIGNED_OFFSET), u32(image, sampleEntry(2) + WsPlayerImage.S_SIGNED_OFFSET))
        assertEquals(0, u32(image, sampleEntry(3) + WsPlayerImage.S_SIGNED_OFFSET))
        val unsigned = u32(image, sampleEntry(0))
        val signed = u32(image, sampleEntry(0) + WsPlayerImage.S_SIGNED_OFFSET)
        assertTrue(signed > u32(image, sampleEntry(3)))
        for (i in 0 until 20) {
            assertEquals(u8(image, WsPlayerImage.OFF_DATA + unsigned + i) xor 0x80, u8(image, WsPlayerImage.OFF_DATA + signed + i))
        }
        assertEquals(WsLowering.dataSize(song), u32(image, WsPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        val song = WsGoldenSongs.color()
        val out = ByteArray(WscSystem.engine.imageSize) { 0x55 }
        WscSystem.engine.lower(song, out)
        assertArrayEquals(WsLowering.lower(song), out)
    }

    @Test
    fun demosLowerWithinTheImage() {
        for (system in listOf(WsSystem, WscSystem)) {
            val demo = system.demo()
            val image = WsLowering.lower(demo)
            val size = u32(image, WsPlayerImage.H_DATA_SIZE)
            assertEquals(WsLowering.dataSize(demo), size)
            assertTrue(size in 1..WsPlayerImage.DATA_CAPACITY)
        }
    }
}
