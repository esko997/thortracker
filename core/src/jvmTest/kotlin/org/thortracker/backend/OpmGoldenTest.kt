package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.opm.OpmSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/opm/opm_song.py` `build()`, through
 * `opm_golden.py`): the host test's song (whose image the native host test plays against the Python reference player
 * tick for tick) and a features song (lowest and highest notes, slot 64, noise on FM 8, sweeps, four effect columns,
 * blank columns, multispeed, the shared LFO and CSM header, a parked order), rebuilt from Thor Tracker's model
 * ([OpmGoldenSongs]), must lower to the same 65536 bytes.
 */
class OpmGoldenTest {
    private val dir = File("src/jvmTest/resources/opm-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(OpmPlayerImage.IMAGE_SIZE, expected.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)

    private fun instrumentByte(image: ByteArray, slot: Int, field: Int) =
        u8(image, OpmPlayerImage.OFF_INSTRUMENTS + OpmPlayerImage.INSTRUMENT_SIZE * (slot - 1) + field)

    @Test
    fun spikeSongLowersToTheReferenceImage() {
        val song = OpmGoldenSongs.spike()
        assertEquals(emptyList<String>(), OpmSystem.errors(song))
        val image = OpmLowering.lower(song)
        assertSameImage(fixture("spike"), image)
        assertEquals(OpmPlayerImage.MAGIC, image.copyOfRange(0, 4).decodeToString())
        assertEquals(OpmPlayerImage.LAYOUT, u8(image, OpmPlayerImage.H_LAYOUT))
        assertEquals(4, u16(image, OpmPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, OpmPlayerImage.H_LOOP))
        assertEquals(12, u8(image, OpmPlayerImage.H_INSTRUMENTS))
        assertEquals(6, u8(image, OpmPlayerImage.H_SPEED))
        assertEquals(1, u8(image, OpmPlayerImage.H_MULTISPEED))
        assertEquals(0x9C, u8(image, OpmPlayerImage.H_LFO_RATE))
        assertEquals(2, u8(image, OpmPlayerImage.H_LFO_WAVE))
        assertEquals(0x18, u8(image, OpmPlayerImage.H_AMD))
        assertEquals(0x24, u8(image, OpmPlayerImage.H_PMD))
        assertEquals(0, u8(image, OpmPlayerImage.H_CSM))
        assertEquals(0x1F1, u16(image, OpmPlayerImage.H_TIMER_A))
        // Slot 2 is the noise hat: the noise flag and its frequency.
        assertEquals(OpmPlayerImage.FLAG_DEFINED or OpmPlayerImage.FLAG_NOISE, instrumentByte(image, 2, OpmPlayerImage.I_FLAGS))
        assertEquals(24, instrumentByte(image, 2, OpmPlayerImage.I_NOISE))
        assertEquals(OpmLowering.dataSize(song), u16(image, OpmPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = OpmGoldenSongs.features()
        assertEquals(emptyList<String>(), OpmSystem.errors(song))
        val image = OpmLowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, u16(image, OpmPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, OpmPlayerImage.H_LOOP))
        assertEquals(64, u8(image, OpmPlayerImage.H_INSTRUMENTS))
        assertEquals(2, u8(image, OpmPlayerImage.H_MULTISPEED))
        assertEquals(0xB7, u8(image, OpmPlayerImage.H_LFO_RATE))
        assertEquals(2, u8(image, OpmPlayerImage.H_LFO_WAVE))
        assertEquals(0x33, u8(image, OpmPlayerImage.H_AMD))
        assertEquals(0x45, u8(image, OpmPlayerImage.H_PMD))
        assertEquals(1, u8(image, OpmPlayerImage.H_CSM))
        assertEquals(1023, u16(image, OpmPlayerImage.H_TIMER_A))
        // Empty slots 5..63 are all-zero records; the negative sweep start is a signed byte.
        for (slot in 5..63) assertEquals(0, instrumentByte(image, slot, OpmPlayerImage.I_FLAGS))
        assertEquals(-12, image[OpmPlayerImage.OFF_INSTRUMENTS + OpmPlayerImage.INSTRUMENT_SIZE * 2 + OpmPlayerImage.I_SWEEP_START].toInt())
        assertEquals(31, instrumentByte(image, 2, OpmPlayerImage.I_NOISE))
        assertEquals(OpmLowering.dataSize(song), u16(image, OpmPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        val song = OpmGoldenSongs.spike()
        val out = ByteArray(OpmSystem.engine.imageSize) { 0x55 }
        OpmSystem.engine.lower(song, out)
        assertArrayEquals(OpmLowering.lower(song), out)
    }

    @Test
    fun demoLowersWithinTheImage() {
        val demo = OpmSystem.demo()
        val image = OpmLowering.lower(demo)
        val size = u16(image, OpmPlayerImage.H_DATA_SIZE)
        assertEquals(OpmLowering.dataSize(demo), size)
        assertTrue(size in 1..OpmPlayerImage.DATA_CAPACITY)
    }
}
