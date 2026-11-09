package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.scc.SccPlusSystem
import org.thortracker.system.scc.SccSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/scc/scc_song.py` `build()`, through
 * `scc_golden.py`): the host test's song (whose image the native host test plays against the Python reference player
 * tick for tick) on the SCC and the SCC+, and a features song (A-0 and B-7, slot 64, 256-value envelopes with loops and
 * a release, four effect columns, blank columns, wave 64, multispeed, a parked order), rebuilt from Thor Tracker's model
 * ([SccGoldenSongs]), must lower to the same 65536 bytes.
 */
class SccGoldenTest {
    private val dir = File("src/jvmTest/resources/scc-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(SccPlayerImage.IMAGE_SIZE, expected.size)
        assertEquals(SccPlayerImage.IMAGE_SIZE, actual.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)

    private fun record(slot: Int) = SccPlayerImage.OFF_INSTRUMENTS + SccPlayerImage.INSTRUMENT_SIZE * (slot - 1)

    private fun envelopeAt(image: ByteArray, slot: Int, field: Int): Int = u16(image, record(slot) + field)

    private fun wave(image: ByteArray, number: Int): List<Int> =
        (0 until SccPlayerImage.WAVE_BYTES).map { image[SccPlayerImage.OFF_WAVES + SccPlayerImage.WAVE_BYTES * (number - 1) + it].toInt() }

    @Test
    fun spikeSongLowersToTheReferenceImage() {
        val song = SccGoldenSongs.spike()
        assertEquals(emptyList<String>(), SccSystem.errors(song))
        val image = SccLowering.lower(song)
        assertSameImage(fixture("spike"), image)
        assertEquals(SccPlayerImage.MAGIC, image.copyOfRange(0, 4).decodeToString())
        assertEquals(SccPlayerImage.LAYOUT, u8(image, SccPlayerImage.H_LAYOUT))
        assertEquals(SccPlayerImage.VARIANT_SCC, u8(image, SccPlayerImage.H_VARIANT))
        assertEquals(1, u8(image, SccPlayerImage.H_MULTISPEED))
        assertEquals(6, u8(image, SccPlayerImage.H_SPEED))
        assertEquals(4, u16(image, SccPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, SccPlayerImage.H_LOOP))
        assertEquals(10, u8(image, SccPlayerImage.H_INSTRUMENTS))
        assertEquals(SccPlayerImage.MAX_WAVES, u8(image, SccPlayerImage.H_WAVES))
        assertEquals(SccLowering.dataSize(song), u16(image, SccPlayerImage.H_DATA_SIZE))
        assertEquals(listOf(16, 32, 16, 24), (0 until 4).map { u8(image, SccPlayerImage.OFF_ROWS + it) })
        // Slot 1 is the plain sine: no volume, arpeggio or pitch envelope; slot 3 restarts its wave; slot 6 is the kick.
        assertEquals(SccPlayerImage.FLAG_DEFINED, u8(image, record(1) + SccPlayerImage.I_FLAGS))
        for (field in listOf(SccPlayerImage.I_VOLUME, SccPlayerImage.I_ARPEGGIO, SccPlayerImage.I_PITCH)) {
            assertEquals(SccPlayerImage.ENV_NONE, envelopeAt(image, 1, field))
        }
        assertEquals(SccPlayerImage.FLAG_DEFINED or SccPlayerImage.FLAG_RESTART, u8(image, record(3) + SccPlayerImage.I_FLAGS))
        assertEquals(
            SccPlayerImage.FLAG_DEFINED or SccPlayerImage.FLAG_RESTART or SccPlayerImage.FLAG_PITCH_RELATIVE,
            u8(image, record(6) + SccPlayerImage.I_FLAGS),
        )
        // Slot 2's volume envelope: 14 values, no loop, release at 9, ending at 0.
        val pluck = SccPlayerImage.OFF_DATA + envelopeAt(image, 2, SccPlayerImage.I_VOLUME)
        assertEquals(14, u16(image, pluck + SccPlayerImage.ENV_LENGTH))
        assertEquals(SccPlayerImage.ENV_NONE, u16(image, pluck + SccPlayerImage.ENV_LOOP))
        assertEquals(9, u16(image, pluck + SccPlayerImage.ENV_RELEASE))
        assertEquals(0, u8(image, pluck + SccPlayerImage.ENV_HEADER + 13))
        // Wave 1 is the sine: +127 at a quarter, -127 at three quarters; waves 9..64 are silent.
        val sine = wave(image, 1)
        assertEquals(0, sine[0])
        assertEquals(127, sine[8])
        assertEquals(-127, sine[24])
        for (k in 9..SccPlayerImage.MAX_WAVES) assertEquals(List(SccPlayerImage.WAVE_BYTES) { 0 }, wave(image, k))
        for (slot in 11..SccPlayerImage.MAX_INSTRUMENTS) assertEquals(0, u8(image, record(slot) + SccPlayerImage.I_FLAGS))
    }

    @Test
    fun plusSongLowersToTheReferenceImage() {
        val song = SccGoldenSongs.plus()
        assertEquals(emptyList<String>(), SccPlusSystem.errors(song))
        val image = SccLowering.lower(song)
        assertSameImage(fixture("plus"), image)
        assertEquals(SccPlayerImage.VARIANT_PLUS, u8(image, SccPlayerImage.H_VARIANT))
        // Only the variant byte differs from the same song on the SCC.
        val scc = SccLowering.lower(SccGoldenSongs.spike())
        assertEquals(listOf(SccPlayerImage.H_VARIANT), image.indices.filter { image[it] != scc[it] })
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = SccGoldenSongs.features()
        assertEquals(emptyList<String>(), SccSystem.errors(song))
        val image = SccLowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, u16(image, SccPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, SccPlayerImage.H_LOOP))
        assertEquals(64, u8(image, SccPlayerImage.H_INSTRUMENTS))
        assertEquals(3, u8(image, SccPlayerImage.H_MULTISPEED))
        assertEquals(5, u8(image, SccPlayerImage.H_SPEED))
        assertEquals(listOf(64, 20, 1), (0 until 3).map { u8(image, SccPlayerImage.OFF_ROWS + it) })
        for (slot in 4..63) assertEquals(0, u8(image, record(slot) + SccPlayerImage.I_FLAGS))
        // Slot 1's 256-value envelopes keep their loop and release points.
        val volume = SccPlayerImage.OFF_DATA + envelopeAt(image, 1, SccPlayerImage.I_VOLUME)
        assertEquals(256, u16(image, volume + SccPlayerImage.ENV_LENGTH))
        assertEquals(16, u16(image, volume + SccPlayerImage.ENV_LOOP))
        assertEquals(200, u16(image, volume + SccPlayerImage.ENV_RELEASE))
        val waveEnv = SccPlayerImage.OFF_DATA + envelopeAt(image, 1, SccPlayerImage.I_WAVE)
        assertEquals(255, u16(image, waveEnv + SccPlayerImage.ENV_LOOP))
        // Slot 64's arpeggio is stored as two's complement; wave 64 alternates the extremes.
        val arpeggio = SccPlayerImage.OFF_DATA + envelopeAt(image, 64, SccPlayerImage.I_ARPEGGIO)
        assertEquals(63, image[arpeggio + SccPlayerImage.ENV_HEADER].toInt())
        assertEquals(-64, image[arpeggio + SccPlayerImage.ENV_HEADER + 1].toInt())
        assertEquals(List(SccPlayerImage.WAVE_BYTES) { if (it % 2 == 1) 127 else -128 }, wave(image, 64))
        assertEquals(SccLowering.dataSize(song), u16(image, SccPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        for ((system, song) in listOf(SccSystem to SccGoldenSongs.spike(), SccPlusSystem to SccGoldenSongs.plus())) {
            assertEquals(SccPlayerImage.IMAGE_SIZE, system.engine.imageSize)
            val out = ByteArray(system.engine.imageSize) { 0x55 }
            system.engine.lower(song, out)
            assertArrayEquals(SccLowering.lower(song), out)
        }
    }

    @Test
    fun demosLowerWithinTheImage() {
        for ((system, variant) in listOf(SccSystem to SccPlayerImage.VARIANT_SCC, SccPlusSystem to SccPlayerImage.VARIANT_PLUS)) {
            val demo = system.demo()
            assertEquals(emptyList<String>(), system.errors(demo))
            val image = SccLowering.lower(demo)
            assertEquals(variant, u8(image, SccPlayerImage.H_VARIANT))
            val size = u16(image, SccPlayerImage.H_DATA_SIZE)
            assertEquals(SccLowering.dataSize(demo), size)
            assertTrue(size in 1..SccPlayerImage.DATA_CAPACITY)
        }
    }
}
