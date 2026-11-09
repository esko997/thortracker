package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.pce.PceSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/pce/pce_song.py` `build()`, through
 * `pce_golden.py`): the host test's song (whose image the native host test plays against the Python reference player
 * tick for tick and interrupt for interrupt) and a features song (lowest and highest notes, slot 64, all 64 waves, a
 * 65535-frame sample pushing later samples past offset 65535, deduplicated samples and envelopes, unlowered model
 * fields of noise and one-shot sounds, four effect columns, blank columns, multispeed, the shared header at its
 * extremes, a parked order), rebuilt from Thor Tracker's model ([PceGoldenSongs]), must lower to the same 131072 bytes.
 */
class PceGoldenTest {
    private val dir = File("src/jvmTest/resources/pce-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(PcePlayerImage.IMAGE_SIZE, expected.size)
        assertEquals(PcePlayerImage.IMAGE_SIZE, actual.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)

    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)

    private fun record(slot: Int) = PcePlayerImage.OFF_INSTRUMENTS + PcePlayerImage.INSTRUMENT_SIZE * (slot - 1)

    private fun envelope(slot: Int, k: Int) = record(slot) + PcePlayerImage.I_ENVELOPES + PcePlayerImage.ENVELOPE_BYTES * k

    private fun sampleEntry(s: Int) = PcePlayerImage.OFF_SAMPLES + PcePlayerImage.SAMPLE_ENTRY_SIZE * s

    @Test
    fun spikeSongLowersToTheReferenceImage() {
        val song = PceGoldenSongs.spike()
        assertEquals(emptyList<String>(), PceSystem.errors(song))
        val image = PceLowering.lower(song)
        assertSameImage(fixture("spike"), image)
        assertEquals(PcePlayerImage.MAGIC, image.copyOfRange(0, 4).decodeToString())
        assertEquals(PcePlayerImage.LAYOUT, u8(image, PcePlayerImage.H_LAYOUT))
        assertEquals(4, u16(image, PcePlayerImage.H_ORDERS))
        assertEquals(1, u16(image, PcePlayerImage.H_LOOP))
        assertEquals(9, u8(image, PcePlayerImage.H_INSTRUMENTS))
        assertEquals(6, u8(image, PcePlayerImage.H_SPEED))
        assertEquals(1, u8(image, PcePlayerImage.H_MULTISPEED))
        assertEquals(5, u8(image, PcePlayerImage.H_WAVES))
        assertEquals(2, u8(image, PcePlayerImage.H_SAMPLES))
        assertEquals(0xEE, u8(image, PcePlayerImage.H_MASTER))
        assertEquals(0x20, u8(image, PcePlayerImage.H_LFO_FREQ))
        assertEquals(0, u8(image, PcePlayerImage.H_LFO_CTRL))
        assertEquals(7, u8(image, PcePlayerImage.H_TIMER))
        // Slot 5's pitch envelope is relative; slot 9 loops its sample from frame 64.
        assertEquals(PcePlayerImage.FLAG_DEFINED or PcePlayerImage.FLAG_RELATIVE or PcePlayerImage.KIND_TONE,
            u8(image, record(5) + PcePlayerImage.I_FLAGS))
        assertEquals(PcePlayerImage.FLAG_DEFINED or PcePlayerImage.KIND_SAMPLE, u8(image, record(9) + PcePlayerImage.I_FLAGS))
        assertEquals(64, u16(image, record(9) + PcePlayerImage.I_LOOP_START))
        assertEquals(PceLowering.dataSize(song), u32(image, PcePlayerImage.H_DATA_SIZE))
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = PceGoldenSongs.features()
        assertEquals(emptyList<String>(), PceSystem.errors(song))
        val image = PceLowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, u16(image, PcePlayerImage.H_ORDERS))
        assertEquals(1, u16(image, PcePlayerImage.H_LOOP))
        assertEquals(64, u8(image, PcePlayerImage.H_INSTRUMENTS))
        assertEquals(2, u8(image, PcePlayerImage.H_MULTISPEED))
        assertEquals(64, u8(image, PcePlayerImage.H_WAVES))
        assertEquals(4, u8(image, PcePlayerImage.H_SAMPLES))
        assertEquals(0x5A, u8(image, PcePlayerImage.H_MASTER))
        assertEquals(0xFF, u8(image, PcePlayerImage.H_LFO_FREQ))
        assertEquals(0x83, u8(image, PcePlayerImage.H_LFO_CTRL))
        assertEquals(0x7F, u8(image, PcePlayerImage.H_TIMER))
        // Empty slots 7..63 are all-zero records.
        for (slot in 7..63) assertEquals(0, u8(image, record(slot) + PcePlayerImage.I_FLAGS))
        // The noise sound's model wave and wave envelope are not lowered; the one-shot's loop start is 0.
        assertEquals(0, u8(image, record(2) + PcePlayerImage.I_WAVE))
        assertEquals(0, u8(image, envelope(2, PcePlayerImage.ENV_WAVE) + 2))
        assertEquals(PcePlayerImage.ENV_NONE, u8(image, envelope(2, PcePlayerImage.ENV_WAVE) + 3))
        assertEquals(0, u16(image, record(4) + PcePlayerImage.I_LOOP_START))
        assertEquals(65534, u16(image, record(3) + PcePlayerImage.I_LOOP_START))
        // Slots 1 and 5 share one volume envelope in the data, with their own loop and release.
        assertEquals(u16(image, envelope(1, PcePlayerImage.ENV_VOLUME)), u16(image, envelope(5, PcePlayerImage.ENV_VOLUME)))
        assertEquals(1, u8(image, envelope(1, PcePlayerImage.ENV_VOLUME) + 3))
        assertEquals(0, u8(image, envelope(5, PcePlayerImage.ENV_VOLUME) + 3))
        assertEquals(255, u8(image, envelope(1, PcePlayerImage.ENV_PITCH) + 2))
        // The 65535-frame sample pushes the next ones past offset 65535; identical samples are stored once.
        assertEquals(65535, u32(image, sampleEntry(0) + 4))
        assertTrue(u32(image, sampleEntry(1)) > 0xFFFF)
        assertEquals(u32(image, sampleEntry(1)), u32(image, sampleEntry(2)))
        assertEquals(1, u32(image, sampleEntry(3) + 4))
        assertEquals(PceLowering.dataSize(song), u32(image, PcePlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        val song = PceGoldenSongs.spike()
        val out = ByteArray(PceSystem.engine.imageSize) { 0x55 }
        PceSystem.engine.lower(song, out)
        assertArrayEquals(PceLowering.lower(song), out)
    }

    @Test
    fun demoLowersWithinTheImage() {
        val demo = PceSystem.demo()
        val image = PceLowering.lower(demo)
        val size = u32(image, PcePlayerImage.H_DATA_SIZE)
        assertEquals(PceLowering.dataSize(demo), size)
        assertTrue(size in 1..PcePlayerImage.DATA_CAPACITY)
    }
}
