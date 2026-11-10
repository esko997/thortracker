package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.saa.CmsSystem
import org.thortracker.system.saa.SamCoupeSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/saa/saa_song.py` `build()`, through
 * `saa_golden.py`): the host test's SAM Coupé song and twelve-lane CMS song (whose images the native host test plays
 * against the Python reference player tick for tick) and a features song (B-0 and A#8, slot 64, 256-value envelopes
 * with loops and a release, a looping mixer envelope, pans 0 and 15, every hardware envelope bit, four effect columns,
 * blank columns, E/M/N/Y/8 parameters, multispeed, a parked order), rebuilt from Thor Tracker's model
 * ([SaaGoldenSongs]), must lower to the same 65536 bytes.
 */
class SaaGoldenTest {
    private val dir = File("src/jvmTest/resources/saa-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(SaaPlayerImage.IMAGE_SIZE, expected.size)
        assertEquals(SaaPlayerImage.IMAGE_SIZE, actual.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF

    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)

    private fun record(slot: Int) = SaaPlayerImage.OFF_INSTRUMENTS + SaaPlayerImage.INSTRUMENT_SIZE * (slot - 1)

    private fun envelopeAt(image: ByteArray, slot: Int, field: Int): Int = u16(image, record(slot) + field)

    @Test
    fun samSongLowersToTheReferenceImage() {
        val song = SaaGoldenSongs.sam()
        assertEquals(emptyList<String>(), SamCoupeSystem.errors(song))
        val image = SaaLowering.lower(song)
        assertSameImage(fixture("sam"), image)
        assertEquals(SaaPlayerImage.MAGIC, image.copyOfRange(0, 4).decodeToString())
        assertEquals(SaaPlayerImage.VARIANT_SAM, u8(image, SaaPlayerImage.H_VARIANT))
        assertEquals(SaaPlayerImage.CHANNELS_SAM, u8(image, SaaPlayerImage.H_CHANNELS))
        assertEquals(6, u8(image, SaaPlayerImage.H_SPEED))
        assertEquals(4, u16(image, SaaPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, SaaPlayerImage.H_LOOP))
        assertEquals(13, u8(image, SaaPlayerImage.H_INSTRUMENTS))
        assertEquals(SaaLowering.dataSize(song), u16(image, SaaPlayerImage.H_DATA_SIZE))
        assertEquals(listOf(16, 32, 16, 24), (0 until 4).map { u8(image, SaaPlayerImage.OFF_ROWS + it) })
        // Slot 1: no envelopes, full pan. Slot 3: an internal repetitive decay that retriggers. Slot 12: an external
        // single decay at rate 2 without retrigger.
        for (field in listOf(SaaPlayerImage.I_VOLUME, SaaPlayerImage.I_ARPEGGIO, SaaPlayerImage.I_PITCH, SaaPlayerImage.I_MIXER)) {
            assertEquals(SaaPlayerImage.ENV_NONE, envelopeAt(image, 1, field))
        }
        assertEquals(0xFF, u8(image, record(1) + SaaPlayerImage.I_PAN))
        assertEquals(SaaPlayerImage.FLAG_DEFINED or SaaPlayerImage.FLAG_ENV_RETRIGGER, u8(image, record(3) + SaaPlayerImage.I_FLAGS))
        assertEquals(0x86, u8(image, record(3) + SaaPlayerImage.I_ENVELOPE))
        assertEquals(0, u8(image, record(3) + SaaPlayerImage.I_RATE))
        assertEquals(SaaPlayerImage.FLAG_DEFINED, u8(image, record(12) + SaaPlayerImage.I_FLAGS))
        assertEquals(0xA4, u8(image, record(12) + SaaPlayerImage.I_ENVELOPE))
        assertEquals(2, u8(image, record(12) + SaaPlayerImage.I_RATE))
        // Slot 2's volume envelope: 14 values, no loop, release at 9; pan left 15, right 10.
        val pluck = SaaPlayerImage.OFF_DATA + envelopeAt(image, 2, SaaPlayerImage.I_VOLUME)
        assertEquals(14, u16(image, pluck))
        assertEquals(SaaPlayerImage.ENV_NONE, u16(image, pluck + 2))
        assertEquals(9, u16(image, pluck + 4))
        assertEquals(0xAF, u8(image, record(2) + SaaPlayerImage.I_PAN))
        for (slot in 14..SaaPlayerImage.MAX_INSTRUMENTS) assertEquals(0, u8(image, record(slot) + SaaPlayerImage.I_FLAGS))
    }

    @Test
    fun cmsSongLowersToTheReferenceImage() {
        val song = SaaGoldenSongs.cms()
        assertEquals(emptyList<String>(), CmsSystem.errors(song))
        val image = SaaLowering.lower(song)
        assertSameImage(fixture("cms"), image)
        assertEquals(SaaPlayerImage.VARIANT_CMS, u8(image, SaaPlayerImage.H_VARIANT))
        assertEquals(SaaPlayerImage.CHANNELS_CMS, u8(image, SaaPlayerImage.H_CHANNELS))
        // Chip B's lanes share the tracks chip A already placed: the data grows by its new tracks only.
        val sam = SaaLowering.lower(SaaGoldenSongs.sam())
        assertTrue(u16(image, SaaPlayerImage.H_DATA_SIZE) >= u16(sam, SaaPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = SaaGoldenSongs.features()
        assertEquals(emptyList<String>(), SamCoupeSystem.errors(song))
        val image = SaaLowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, u16(image, SaaPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, SaaPlayerImage.H_LOOP))
        assertEquals(64, u8(image, SaaPlayerImage.H_INSTRUMENTS))
        assertEquals(3, u8(image, SaaPlayerImage.H_MULTISPEED))
        assertEquals(5, u8(image, SaaPlayerImage.H_SPEED))
        assertEquals(listOf(64, 20, 1), (0 until 3).map { u8(image, SaaPlayerImage.OFF_ROWS + it) })
        for (slot in 4..63) assertEquals(0, u8(image, record(slot) + SaaPlayerImage.I_FLAGS))
        // Every hardware envelope bit and the longest external rate; pans 0 and 15.
        assertEquals(0xBF, u8(image, record(3) + SaaPlayerImage.I_ENVELOPE))
        assertEquals(255, u8(image, record(3) + SaaPlayerImage.I_RATE))
        assertEquals(0xF0, u8(image, record(1) + SaaPlayerImage.I_PAN))
        assertEquals(0x0F, u8(image, record(2) + SaaPlayerImage.I_PAN))
        val volume = SaaPlayerImage.OFF_DATA + envelopeAt(image, 1, SaaPlayerImage.I_VOLUME)
        assertEquals(256, u16(image, volume))
        assertEquals(16, u16(image, volume + 2))
        assertEquals(200, u16(image, volume + 4))
        val arpeggio = SaaPlayerImage.OFF_DATA + envelopeAt(image, 64, SaaPlayerImage.I_ARPEGGIO)
        assertEquals(63, image[arpeggio + SaaPlayerImage.ENV_HEADER].toInt())
        assertEquals(-64, image[arpeggio + SaaPlayerImage.ENV_HEADER + 1].toInt())
        assertEquals(SaaLowering.dataSize(song), u16(image, SaaPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        for ((system, song) in listOf(SamCoupeSystem to SaaGoldenSongs.sam(), CmsSystem to SaaGoldenSongs.cms())) {
            assertEquals(SaaPlayerImage.IMAGE_SIZE, system.engine.imageSize)
            val out = ByteArray(system.engine.imageSize) { 0x55 }
            system.engine.lower(song, out)
            assertArrayEquals(SaaLowering.lower(song), out)
        }
    }

    @Test
    fun demosLowerWithinTheImage() {
        for ((system, variant) in listOf(SamCoupeSystem to SaaPlayerImage.VARIANT_SAM, CmsSystem to SaaPlayerImage.VARIANT_CMS)) {
            val demo = system.demo()
            assertEquals(emptyList<String>(), system.errors(demo))
            val image = SaaLowering.lower(demo)
            assertEquals(variant, u8(image, SaaPlayerImage.H_VARIANT))
            val size = u16(image, SaaPlayerImage.H_DATA_SIZE)
            assertEquals(SaaLowering.dataSize(demo), size)
            assertTrue(size in 1..SaaPlayerImage.DATA_CAPACITY)
        }
    }
}
