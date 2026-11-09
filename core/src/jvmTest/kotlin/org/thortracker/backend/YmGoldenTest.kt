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
 * tick for tick and streams through the XGM2 driver), a features song (effect columns, PSG envelopes, noise modes,
 * PAL) and the XGM2 song (FM 3 operator offsets, PCM kits, the compiled bank), rebuilt from Thor Tracker's model
 * ([YmGoldenSongs]), must lower to the same [YmPlayerImage.IMAGE_SIZE] bytes.
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

    @Test
    fun xgm2SongLowersToTheReferenceImage() {
        val song = YmGoldenSongs.xgm2()
        assertEquals(emptyList<String>(), YmSystem.errors(song))
        val image = YmLowering.lower(song)
        assertSameImage(fixture("xgm2"), image)
        assertEquals(YmPlayerImage.FM3_SPECIAL, image[YmPlayerImage.H_FM3].toInt())
        // Seven variants: the copy of "ramp" and the repeated (sample, pitch, level) share id 1.
        assertEquals(7, image[YmPlayerImage.H_PCM_VARIANTS].toInt())
        val key = { slot: Int, note: Int -> image[YmPlayerImage.OFF_PCM_KEYS + YmPlayerImage.PCM_KEYS * (slot - 1) + note].toInt() }
        assertEquals(listOf(1, 1, 2, 3, 4, 1), listOf(36, 37, 38, 40, 41, 42).map { key(2, it) })
        assertEquals(listOf(5, 6), listOf(36, 48).map { key(3, it) })
        assertEquals(7, key(5, 50))
        assertEquals(0, key(2, 39))
        // Contiguous variants from page 0; the bank's size is their sum; the silent kit compiles to zeros.
        var page = 0
        for (id in 1..7) {
            val entry = YmPlayerImage.OFF_PCM_VARIANTS + YmPlayerImage.PCM_VARIANT_SIZE * (id - 1)
            assertEquals(page, u16(image, entry))
            assertTrue(u16(image, entry + 2) >= 1)
            assertEquals(0, image[entry + 4].toInt())
            page += u16(image, entry + 2)
        }
        assertEquals(page, u16(image, YmPlayerImage.H_PCM_BANK_SIZE))
        val silent = YmPlayerImage.OFF_PCM_VARIANTS + YmPlayerImage.PCM_VARIANT_SIZE * 6
        val start = YmPlayerImage.OFF_PCM_BANK + u16(image, silent) * YmPlayerImage.PCM_ALIGN
        assertTrue((start until start + u16(image, silent + 2) * YmPlayerImage.PCM_ALIGN).all { image[it].toInt() == 0 })
        // FM 3 offsets per slot, little-endian s16; PCM and empty slots have none.
        val ch3 = { slot: Int, op: Int -> u16(image, YmPlayerImage.OFF_CH3 + 8 * (slot - 1) + 2 * op).toShort().toInt() }
        assertEquals(listOf(0, 448, -64, 3072), (0..3).map { ch3(1, it) })
        assertEquals(listOf(-3072, 0, 1, -1), (0..3).map { ch3(4, it) })
        assertEquals(listOf(0, 0, 0, 0), (0..3).map { ch3(2, it) })
        // The PCM tracks live in their own region: the tonal data size counts only FM, PSG and Noise.
        assertEquals(YmLowering.dataSize(song), u16(image, YmPlayerImage.H_DATA_SIZE))
        assertEquals(YmLowering.pcmDataSize(song), u16(image, YmPlayerImage.H_PCM_DATA_SIZE) or (u16(image, YmPlayerImage.H_PCM_DATA_SIZE + 2) shl 16))
    }
}
