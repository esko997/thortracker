package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.vsu.VsuDemoSong
import org.thortracker.system.vsu.VsuFreqMod
import org.thortracker.system.vsu.VsuInstrument
import org.thortracker.system.vsu.VsuRules
import org.thortracker.system.vsu.VsuSweep
import org.thortracker.system.vsu.VsuSystem
import org.thortracker.system.vsu.vsu

/**
 * The VSU image, field by field against docs/AUDIO_ENGINE.md "Virtual Boy VSU: Image layout": header, rows and pattern
 * tables, instrument records, waves, modulation and the row encoding (runs, OFF, two effect columns, sharing).
 */
class VsuLoweringTest {
    private fun u8(image: ByteArray, at: Int) = image[at].toInt() and 0xFF
    private fun u16(image: ByteArray, at: Int) = u8(image, at) or (u8(image, at + 1) shl 8)
    private fun bytes(image: ByteArray, at: Int, n: Int) = IntArray(n) { u8(image, at + it) }

    /**
     * Two orders: pattern 0 (4 rows) with A-4 on Wave 1 (instrument 1, C0A then 8F3 in two columns), an OFF at row 2,
     * and a W03 alone in Wave 4's second column; pattern 1 (64 rows) empty. Speed 5, loop to order 1. Slot 20 holds a
     * Wave 5 sweep sound whose software sweep has a start but no speed.
     */
    private fun tiny(): Song {
        val base = VsuSystem.blank()
        val p0 = Pattern.blank(6, Songs.ROWS, "A").let { p ->
            val tracks = p.tracks.mapIndexed { lane, t ->
                t.toMutableList().also {
                    when (lane) {
                        0 -> {
                            it[0] = Cell(57, 1, VsuRules.FX_LEVEL, 0x0A).withEffect(1, VsuRules.FX_STEREO, 0xF3)
                            it[2] = Cell(Cell.CUT)
                        }
                        3 -> it[0] = Cell().withEffect(1, VsuRules.FX_WAVE, 3)
                    }
                }
            }
            p.copy(tracks = tracks, length = 4)
        }
        val p1 = Pattern.blank(6, Songs.ROWS, "B")
        val sound = base.vsu.withInstrument(20, VsuInstrument("W5", wave = 2, level = 9, envelope = true, grow = true, interval = 5,
            repeat = true, autoLength = true, length = 17, noiseTap = 6,
            freqMod = VsuFreqMod(on = true, modulation = false, repeat = true, slowClock = true, interval = 3, up = true, shift = 5),
            sweep = VsuSweep(start = 5, end = -3, speed = 0)))
        return base.copy(ticksPerRow = 5, loopOrder = 1, patterns = listOf(p0, p1), orders = listOf(0, 1), sound = sound)
    }

    @Test
    fun headerAndTables() {
        val image = VsuLowering.lower(tiny())
        assertEquals(VsuPlayerImage.IMAGE_SIZE, image.size)
        assertEquals("TTVB", String(image.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(1, u8(image, 4))
        assertEquals(5, u8(image, 5))
        assertEquals(2, u16(image, 6))
        assertEquals(1, u16(image, 8))
        assertEquals(20, u8(image, 10))
        assertEquals(0, u8(image, 11))
        assertEquals(17, u16(image, 12))
        assertTrue(bytes(image, 14, 50).all { it == 0 })
        // Rows per order, then zeros.
        assertEquals(4, u8(image, 64))
        assertEquals(64, u8(image, 65))
        assertTrue(bytes(image, 66, 254).all { it == 0 })
        // Pattern offsets: first use, orders then lanes; identical encodings shared.
        fun at(o: Int, c: Int) = u16(image, 320 + 2 * (6 * o + c))
        assertEquals(listOf(0, 11, 11, 12, 11, 11), (0 until 6).map { at(0, it) })
        assertEquals(List(6) { 16 }, (0 until 6).map { at(1, it) })
        assertTrue(bytes(image, 320 + 2 * 12, 3392 - (320 + 2 * 12)).all { it == 0 })
    }

    @Test
    fun rowEncoding() {
        val image = VsuLowering.lower(tiny())
        val data = VsuPlayerImage.OFF_DATA
        // Wave 1: mask note|instrument|2 effects, A-4, slot 1, C 0A, 8 F3; a run of 1; OFF; a run of 1.
        assertArrayEquals(intArrayOf(0x0B, 57, 1, 12, 0x0A, 8, 0xF3, 0x80, 0x01, 0xFE, 0x80), bytes(image, data, 11))
        // Empty 4-row lanes: one run of 4.
        assertArrayEquals(intArrayOf(0x83), bytes(image, data + 11, 1))
        // Wave 4: one effect (the blank first column is omitted), W 03, then a run of 3.
        assertArrayEquals(intArrayOf(0x04, 32, 3, 0x82), bytes(image, data + 12, 4))
        // The 64-row empty pattern: one run of 64.
        assertArrayEquals(intArrayOf(0xBF), bytes(image, data + 16, 1))
        assertTrue(bytes(image, data + 17, 64).all { it == 0 })
        assertEquals(17, VsuLowering.dataSize(tiny()))
    }

    @Test
    fun instrumentRecords() {
        val image = VsuLowering.lower(tiny())
        fun record(slot: Int) = bytes(image, 3392 + 16 * (slot - 1), 16)
        // Slot 1, Square lead: defined + envelope; level 15, interval 7, wave 4 (index 3).
        assertArrayEquals(intArrayOf(0x81, 15, 7, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), record(1))
        // Slot 8, Vibrato lead: frequency modification on | modulation | repeat; S5SWP interval 6.
        val vibrato = record(8)
        assertEquals(0x07, vibrato[6])
        assertEquals(0x60, vibrato[7])
        // Slot 12, Tom: the software sweep 7 → -5 at 24.
        assertArrayEquals(intArrayOf(7, 0xFB, 24), record(12).copyOfRange(8, 11))
        // Slot 20: every flag, every field; the sweep start/end are written 0 without a speed.
        assertArrayEquals(intArrayOf(0x80 or 0x01 or 0x02 or 0x04 or 0x08, 9, 5, 17, 2, 6, 0x01 or 0x04, 0x80 or 0x30 or 0x08 or 5, 0, 0, 0, 0, 0, 0, 0, 0),
            record(20))
        // Empty slots below the count stay zero, as does everything above it.
        assertTrue(record(17).all { it == 0 })
        assertTrue(bytes(image, 3392 + 16 * 20, 16 * 44).all { it == 0 })
    }

    @Test
    fun wavesAndModulationAreCopied() {
        val song = tiny()
        val image = VsuLowering.lower(song)
        for (w in 0 until 5) assertArrayEquals(song.vsu.waves[w].toIntArray(), bytes(image, 4416 + 32 * w, 32))
        val mod = song.vsu.modulation.toMutableList().also { it[0] = -128; it[31] = 127 }
        val image2 = VsuLowering.lower(song.copy(sound = song.vsu.copy(modulation = mod)))
        assertEquals(0x80, u8(image2, 4576))
        assertEquals(0x7F, u8(image2, 4576 + 31))
        assertArrayEquals(song.vsu.modulation.map { it and 0xFF }.toIntArray(), bytes(image, 4576, 32))
    }

    @Test
    fun parkedOrdersAreLeftOutAndTheShortestSongIsOneRow() {
        val song = tiny().copy(orders = listOf(0, 1, 0), loopOrder = 2, parkedOrders = listOf(1))
        val image = VsuLowering.lower(song)
        assertEquals(2, u16(image, 6))
        assertEquals(1, u16(image, 8))
        assertEquals(4, u8(image, 64))
        assertEquals(4, u8(image, 65))
        val shortest = VsuLowering.lower(VsuDemoSong.shortest())
        assertEquals(1, u16(shortest, 6))
        assertEquals(1, u8(shortest, 64))
        // A-4 with slot 1 on Wave 1; the other lanes one empty row each (shared).
        assertArrayEquals(intArrayOf(0x03, 57, 1, 0x80), bytes(shortest, VsuPlayerImage.OFF_DATA, 4))
        assertEquals(4, u16(shortest, 12))
        // The engine's lowering is the same bytes.
        val out = ByteArray(VsuSystem.engine.imageSize)
        VsuSystem.engine.lower(VsuDemoSong.shortest(), out)
        assertArrayEquals(shortest, out)
    }
}
