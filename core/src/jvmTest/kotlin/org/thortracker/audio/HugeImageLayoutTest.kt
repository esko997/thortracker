package org.thortracker.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.HugeData
import org.thortracker.backend.HugeLimits
import org.thortracker.backend.HugeLowering
import org.thortracker.model.Songs

class HugeImageLayoutTest {
    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun inSongRegion(addr: Int, length: Int) =
        (addr >= 0x0900 && addr + length <= 0xC000) || (addr >= 0xC400 && addr + length <= 0xE000)

    @Test
    fun capacityMatchesBackendLimit() {
        assertEquals(HugeLimits.PREVIEW_TRACK_SLOTS, HugeImageLayout.TRACK_SLOTS)
    }

    @Test
    fun demoSongIsPlacedExactlyAsLowered() {
        val data = HugeLowering.lower(Songs.demo())
        val image = ByteArray(HugeImageLayout.SIZE)
        HugeImageLayout.build(data, image)
        val d = HugeImageLayout.DESCRIPTOR
        assertEquals(data.ticksPerRow, u8(image, d))
        assertEquals(data.orderCountByte, u8(image, u16(image, d + 1)))
        for (ch in 0 until 4) {
            val table = u16(image, d + 3 + 2 * ch)
            for (pos in data.orderPatterns.indices) {
                val track = u16(image, table + 2 * pos)
                assertTrue("track pointer ${track.toString(16)} outside song data", inSongRegion(track, 192))
                assertArrayEquals(data.tracks[data.orders[ch][pos]], image.copyOfRange(track, track + 192))
            }
        }
        val tables = listOf(data.duty to u16(image, d + 11), data.wave to u16(image, d + 13), data.noise to u16(image, d + 15))
        for ((rows, base) in tables) {
            rows.forEachIndexed { i, bytes -> assertArrayEquals(bytes, image.copyOfRange(base + 6 * i, base + 6 * i + 6)) }
        }
        assertEquals(0, u16(image, d + 17)) // no routines
        val waves = u16(image, d + 19)
        data.waves.forEachIndexed { i, bytes -> assertArrayEquals(bytes, image.copyOfRange(waves + 16 * i, waves + 16 * i + 16)) }
    }

    @Test
    fun identicalTracksShareOneSlotAndRebuildClearsStaleBytes() {
        val data = HugeLowering.lower(Songs.demo())
        val image = ByteArray(HugeImageLayout.SIZE) { 0x55 }
        HugeImageLayout.build(data, image)
        val d = HugeImageLayout.DESCRIPTOR
        val pointers = (0 until 4).flatMap { ch ->
            val table = u16(image, d + 3 + 2 * ch)
            data.orderPatterns.indices.map { pos -> u16(image, table + 2 * pos) to data.tracks[data.orders[ch][pos]] }
        }
        val byContent = pointers.groupBy { it.second.toList() }
        for ((_, uses) in byContent) assertEquals(1, uses.map { it.first }.toSet().size)
        assertEquals(0, u8(image, 0xBFFF)) // previous garbage cleared
        val again = ByteArray(HugeImageLayout.SIZE)
        HugeImageLayout.build(data, again)
        assertArrayEquals(again, image)
    }

    @Test
    fun songBeyondPreviewCapacityIsRejected() {
        val orders = 69 // 69 * 4 = 276 distinct tracks > 272
        val tracks = List(orders * 4) { i -> ByteArray(192) { if (it == 0) (i % 72).toByte() else (i / 72 + 1).toByte() } }
        val instrument = { ByteArray(6) }
        val data = HugeData(
            ticksPerRow = 6, loopOrder = 0, orderPatterns = IntArray(orders) { it },
            orders = List(4) { ch -> IntArray(orders) { pos -> pos * 4 + ch } }, tracks = tracks,
            duty = List(15) { instrument() }, wave = List(15) { instrument() }, noise = List(15) { instrument() },
            waves = List(16) { ByteArray(16) },
        )
        val error = assertThrows(IllegalArgumentException::class.java) { HugeImageLayout.build(data, ByteArray(HugeImageLayout.SIZE)) }
        assertTrue(error.message!!.contains("272"))
    }

    @Test
    fun tempoAndOrderEditsChangeOnlyTheirBytes() {
        val song = Songs.demo()
        val a = ByteArray(HugeImageLayout.SIZE)
        val b = ByteArray(HugeImageLayout.SIZE)
        HugeImageLayout.build(HugeLowering.lower(song), a)
        HugeImageLayout.build(HugeLowering.lower(song.copy(ticksPerRow = song.ticksPerRow + 1)), b)
        val diff = a.indices.filter { a[it] != b[it] }
        assertEquals(listOf(HugeImageLayout.DESCRIPTOR), diff)
        assertNotEquals(a[HugeImageLayout.DESCRIPTOR], b[HugeImageLayout.DESCRIPTOR])
    }
}
