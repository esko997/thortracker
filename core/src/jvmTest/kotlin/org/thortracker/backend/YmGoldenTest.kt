package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.ym2612.YmSystem
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/ym/ym_song.py` `build()`, through
 * `ym_golden.py`): the native host test's song (whose image the host test plays against the Python reference player
 * tick for tick and streams through the XGM driver) and a features song (effect columns, PSG envelopes, noise modes,
 * PAL), rebuilt from Thor Tracker's model ([YmGoldenSongs]), must lower to the same 65536 bytes.
 */
class YmGoldenTest {
    private val dir = File("src/jvmTest/resources/ym-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(YmPlayerImage.IMAGE_SIZE, expected.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    private fun u16(image: ByteArray, at: Int) = (image[at].toInt() and 0xFF) or ((image[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun spikeSongLowersToTheReferenceImage() {
        val song = YmGoldenSongs.spike()
        assertEquals(emptyList<String>(), YmSystem.errors(song))
        assertSameImage(fixture("spike"), YmLowering.lower(song))
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = YmGoldenSongs.features()
        assertEquals(emptyList<String>(), YmSystem.errors(song))
        val image = YmLowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, u16(image, YmPlayerImage.H_ORDERS))
        assertEquals(1, u16(image, YmPlayerImage.H_LOOP))
        assertEquals(64, image[YmPlayerImage.H_INSTRUMENTS].toInt())
        assertEquals(YmPlayerImage.PAL, image[YmPlayerImage.H_REGION].toInt())
        assertEquals(0x0F, image[YmPlayerImage.H_LFO].toInt())
        // Slots 3 and 5 share one volume envelope; slot 4 has none.
        val at = { slot: Int, field: Int -> u16(image, YmPlayerImage.OFF_INSTRUMENTS + YmPlayerImage.INSTRUMENT_SIZE * (slot - 1) + field) }
        assertEquals(at(3, YmPlayerImage.I_ENV_VOLUME), at(5, YmPlayerImage.I_ENV_VOLUME))
        assertEquals(YmPlayerImage.NO_ENVELOPE, at(4, YmPlayerImage.I_ENV_VOLUME))
        assertEquals(YmLowering.dataSize(song), u16(image, YmPlayerImage.H_DATA_SIZE))
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        val song = YmGoldenSongs.spike()
        val out = ByteArray(YmSystem.engine.imageSize) { 0x55 }
        YmSystem.engine.lower(song, out)
        assertArrayEquals(YmLowering.lower(song), out)
    }

    @Test
    fun demoLowersWithinTheImage() {
        val demo = YmSystem.demo()
        val image = YmLowering.lower(demo)
        val size = u16(image, YmPlayerImage.H_DATA_SIZE)
        assertEquals(YmLowering.dataSize(demo), size)
        assertTrue(size in 1..YmPlayerImage.DATA_CAPACITY)
    }
}
