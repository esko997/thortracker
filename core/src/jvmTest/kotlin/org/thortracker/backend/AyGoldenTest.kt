package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.ay.AyCpcSystem
import org.thortracker.system.ay.AyRules
import org.thortracker.system.ay.AyVariant
import org.thortracker.system.ay.ay
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/ay/ay_song.py` `build()`, through
 * `ay_golden.py`): the native host test's song for each variant (whose image the host test plays against the Python
 * reference player tick for tick) and a features song (extremes, shared envelopes, effect columns, a parked order),
 * rebuilt from Thor Tracker's model ([AyGoldenSongs]), must lower to the same 65536 bytes.
 */
class AyGoldenTest {
    private val dir = File("src/jvmTest/resources/ay-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun lower(song: org.thortracker.model.Song): ByteArray =
        ByteArray(AyPlayerImage.IMAGE_SIZE).also { AyLowering.lower(song, it) }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(AyPlayerImage.IMAGE_SIZE, expected.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    private fun field(image: ByteArray, slot: Int, at: Int) = AyPlayerImage.OFF_INSTRUMENTS + AyPlayerImage.INSTRUMENT_SIZE * (slot - 1) + at

    @Test
    fun testSongLowersToTheReferenceImageForEveryVariant() {
        for (variant in AyVariant.values()) {
            assertEquals("lowest note of $variant", AyGoldenSongs.LOWEST_NOTE[variant.index], AyRules.lowestNote(variant))
            val song = AyGoldenSongs.test(variant)
            assertEquals(variant, song.ay.variant)
            assertEquals(emptyList<String>(), variant.system.errors(song))
            val image = lower(song)
            assertSameImage(fixture("test-${variant.fileKey}"), image)
            assertEquals(variant.index, image[AyPlayerImage.H_VARIANT].toInt())
            assertEquals(AyLowering.dataSize(song), u16(image, AyPlayerImage.H_DATA_SIZE))
        }
    }

    @Test
    fun cpcSongLowersToTheReferenceImage() {
        // The fourth variant: its own golden image (variant byte 3, lowest note C-0), the CPC system and its stereo socket.
        val song = AyGoldenSongs.test(AyVariant.CPC)
        assertEquals(AyCpcSystem, AyVariant.CPC.system)
        assertEquals(AyPlayerImage.VARIANT_CPC, AyVariant.CPC.index)
        assertTrue(AyVariant.CPC.stereo && AyVariant.values().filter { it.stereo } == listOf(AyVariant.CPC))
        assertEquals(0, AyRules.lowestNote(AyVariant.CPC))
        val image = lower(song)
        assertSameImage(fixture("test-cpc"), image)
        assertEquals(AyPlayerImage.VARIANT_CPC, image[AyPlayerImage.H_VARIANT].toInt())
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = AyGoldenSongs.features()
        assertEquals(emptyList<String>(), AyVariant.MSX.system.errors(song))
        val image = lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, u16(image, AyPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, AyPlayerImage.H_LOOP))
        assertEquals(64, image[AyPlayerImage.H_INSTRUMENTS].toInt())
        assertEquals(AyPlayerImage.VARIANT_MSX, image[AyPlayerImage.H_VARIANT].toInt())
        // Slots 1 and 3 share one volume envelope; slot 4 has none.
        assertEquals(u16(image, field(image, 1, AyPlayerImage.I_ENV_VOLUME)), u16(image, field(image, 3, AyPlayerImage.I_ENV_VOLUME)))
        assertEquals(AyPlayerImage.NO_ENVELOPE, u16(image, field(image, 4, AyPlayerImage.I_ENV_VOLUME)))
        // Only the active mode's settings are written: a manual sound keeps no octave or sync, an auto sound no period.
        assertEquals(AyPlayerImage.FLAG_DEFINED, image[field(image, 5, AyPlayerImage.I_FLAGS)].toInt() and 0xFF)
        assertEquals(0, image[field(image, 5, AyPlayerImage.I_OCTAVE)].toInt())
        assertEquals(0x1234, u16(image, field(image, 5, AyPlayerImage.I_PERIOD)))
        assertEquals(AyPlayerImage.FLAG_DEFINED or AyPlayerImage.FLAG_AUTO_PERIOD or AyPlayerImage.FLAG_SYNC,
            image[field(image, 6, AyPlayerImage.I_FLAGS)].toInt() and 0xFF)
        assertEquals(0, u16(image, field(image, 6, AyPlayerImage.I_PERIOD)))
        assertEquals(AyLowering.dataSize(song), u16(image, AyPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        for (variant in AyVariant.values()) {
            val song = AyGoldenSongs.test(variant)
            val out = ByteArray(variant.system.engine.imageSize) { 0x55 }
            variant.system.engine.lower(song, out)
            assertArrayEquals(lower(song), out)
        }
    }

    @Test
    fun demosLowerWithinTheImage() {
        for (variant in AyVariant.values()) {
            val demo = variant.system.demo()
            val image = lower(demo)
            val size = u16(image, AyPlayerImage.H_DATA_SIZE)
            assertEquals(AyLowering.dataSize(demo), size)
            assertTrue(size in 1..AyPlayerImage.DATA_CAPACITY)
            assertEquals(variant.index, image[AyPlayerImage.H_VARIANT].toInt())
        }
    }
}
