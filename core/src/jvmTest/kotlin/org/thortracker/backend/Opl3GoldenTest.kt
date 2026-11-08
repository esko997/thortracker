package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.system.opl3.Opl3System
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The Kotlin lowering against the reference image builder (`tools/audio/opl3/opl3_song.py` `build()`, through
 * `opl3_golden.py`): the spike's test song (whose image the native host test plays against the Python reference
 * player tick for tick) and a features song, rebuilt from Thor Tracker's model ([Opl3GoldenSongs]), must lower to the
 * same 65536 bytes.
 */
class Opl3GoldenTest {
    private val dir = File("src/jvmTest/resources/opl3-golden")

    private fun fixture(name: String): ByteArray = GZIPInputStream(File(dir, "$name.bin.gz").inputStream()).use { it.readBytes() }

    private fun assertSameImage(expected: ByteArray, actual: ByteArray) {
        assertEquals(Opl3PlayerImage.IMAGE_SIZE, expected.size)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals("first differing byte", null, first)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun spikeSongLowersToTheReferenceImage() {
        val song = Opl3GoldenSongs.spike()
        assertEquals(emptyList<String>(), Opl3System.errors(song))
        assertSameImage(fixture("spike"), Opl3Lowering.lower(song))
    }

    @Test
    fun featuresSongLowersToTheReferenceImage() {
        val song = Opl3GoldenSongs.features()
        assertEquals(emptyList<String>(), Opl3System.errors(song))
        val image = Opl3Lowering.lower(song)
        assertSameImage(fixture("features"), image)
        // Arranged: the parked order is gone, the loop and jumps renumbered; slot 64 sets the instrument count.
        assertEquals(3, image[Opl3PlayerImage.H_ORDERS].toInt())
        assertEquals(1, image[Opl3PlayerImage.H_LOOP].toInt())
        assertEquals(64, image[Opl3PlayerImage.H_INSTRUMENTS].toInt())
        assertEquals(0xC0, image[Opl3PlayerImage.H_DEPTH].toInt() and 0xFF)
    }

    @Test
    fun engineLowerWritesTheSameImage() {
        val song = Opl3GoldenSongs.spike()
        val out = ByteArray(Opl3System.engine.imageSize) { 0x55 }
        Opl3System.engine.lower(song, out)
        assertArrayEquals(Opl3Lowering.lower(song), out)
    }

    @Test
    fun demoLowersWithinTheImage() {
        val demo = Opl3System.demo()
        val image = Opl3Lowering.lower(demo)
        val size = (image[Opl3PlayerImage.H_DATA_SIZE].toInt() and 0xFF) or ((image[Opl3PlayerImage.H_DATA_SIZE + 1].toInt() and 0xFF) shl 8)
        assertEquals(Opl3Lowering.dataSize(demo), size)
        assertTrue(size in 1..Opl3PlayerImage.DATA_CAPACITY)
    }
}
