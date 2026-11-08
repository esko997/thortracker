package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.tia.TiaNtscSystem
import org.thortracker.system.tia.TiaPalSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/tia/tia_song.py` `build()`, through
 * `tia_golden.py`): the host test's song (whose image the native host test plays against the Python reference player
 * tick for tick) and the PAL features song (slot 64 behind empty slots, a 255-step relative pitch envelope, extreme
 * envelope values, a waveform envelope through every AUDC, shared envelope bytes with their own loop and release,
 * byte-identical sounds in two slots, a raw sound, two effect columns and a blank first column, a parked order),
 * rebuilt from Thor Tracker's model ([TiaGoldenSongs]), must lower to the same 65536 bytes.
 */
class TiaGoldenTest {
    private val dir = File("src/jvmTest/resources/tia-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(TiaPlayerImage.IMAGE_SIZE, expected.size)
        assertEquals(TiaPlayerImage.IMAGE_SIZE, actual.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)

    private fun u32(image: ByteArray, at: Int) = u16(image, at) or (u16(image, at + 2) shl 16)

    private fun record(image: ByteArray, slot: Int) =
        image.copyOfRange(TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * (slot - 1),
            TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * slot)

    private fun envelope(slot: Int, k: Int) =
        TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * (slot - 1) + TiaPlayerImage.I_ENVELOPES + TiaPlayerImage.ENVELOPE_BYTES * k

    @Test
    fun spikeSongLowersToTheReferenceImage() {
        val song = TiaGoldenSongs.spike()
        assertEquals(emptyList<String>(), TiaNtscSystem.errors(song))
        val image = TiaLowering.lower(song)
        assertSameImage(fixture("spike"), image)
        assertEquals(TiaPlayerImage.MAGIC, image.copyOfRange(0, 4).decodeToString())
        assertEquals(TiaPlayerImage.LAYOUT, u8(image, TiaPlayerImage.H_LAYOUT))
        assertEquals(TiaPlayerImage.REGION_NTSC, u8(image, TiaPlayerImage.H_REGION))
        assertEquals(6, u8(image, TiaPlayerImage.H_SPEED))
        assertEquals(18, u8(image, TiaPlayerImage.H_INSTRUMENTS))
        assertEquals(4, u16(image, TiaPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, TiaPlayerImage.H_LOOP))
        assertEquals(TiaLowering.dataSize(song), u32(image, TiaPlayerImage.H_DATA_SIZE))
        // Slot 18 is byte-identical to slot 1 and still its own record.
        assertArrayEquals(record(image, 1), record(image, 18))
        assertEquals(TiaPlayerImage.FLAG_DEFINED, u8(image, TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * 17))
        // Order 2 reuses order 0's TIA 2 pattern: stored once.
        assertEquals(u16(image, TiaPlayerImage.OFF_ORDERS + 2 * 1), u16(image, TiaPlayerImage.OFF_ORDERS + 2 * 5))
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = TiaGoldenSongs.features()
        assertEquals(emptyList<String>(), TiaPalSystem.errors(song))
        val image = TiaLowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop renumbered; slot 64 sets the instrument count; PAL.
        assertEquals(TiaPlayerImage.REGION_PAL, u8(image, TiaPlayerImage.H_REGION))
        assertEquals(3, u16(image, TiaPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, TiaPlayerImage.H_LOOP))
        assertEquals(64, u8(image, TiaPlayerImage.H_INSTRUMENTS))
        for (slot in 7..63) assertEquals(0, u8(image, TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * (slot - 1)))
        // Slots 2 and 3: two records with the same bytes (envelopes shared); slots 1 and 5 share volume data.
        assertArrayEquals(record(image, 2), record(image, 3))
        assertEquals(u16(image, envelope(1, TiaPlayerImage.ENV_VOLUME)), u16(image, envelope(5, TiaPlayerImage.ENV_VOLUME)))
        assertNotEquals(u8(image, envelope(1, TiaPlayerImage.ENV_VOLUME) + 3), u8(image, envelope(5, TiaPlayerImage.ENV_VOLUME) + 3))
        assertEquals(255, u8(image, envelope(1, TiaPlayerImage.ENV_PITCH) + 2))
        assertEquals(16, u8(image, envelope(1, TiaPlayerImage.ENV_WAVEFORM) + 2))
        assertEquals(TiaPlayerImage.FLAG_DEFINED or TiaPlayerImage.FLAG_RAW, u8(image, TiaPlayerImage.OFF_INSTRUMENTS + TiaPlayerImage.INSTRUMENT_SIZE * 5))
        assertEquals(TiaLowering.dataSize(song), u32(image, TiaPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        val song = TiaGoldenSongs.spike()
        val out = ByteArray(TiaNtscSystem.engine.imageSize) { 0x55 }
        TiaNtscSystem.engine.lower(song, out)
        assertArrayEquals(TiaLowering.lower(song), out)
    }

    @Test
    fun demosLowerWithinTheImage() {
        for (demo in listOf(TiaNtscSystem.demo(), TiaPalSystem.demo())) {
            val image = TiaLowering.lower(demo)
            val size = u32(image, TiaPlayerImage.H_DATA_SIZE)
            assertEquals(TiaLowering.dataSize(demo), size)
            assertTrue(size in 1..TiaPlayerImage.DATA_CAPACITY)
            assertEquals(demo.system, if (u8(image, TiaPlayerImage.H_REGION) == TiaPlayerImage.REGION_NTSC) TiaNtscSystem else TiaPalSystem)
        }
    }
}
