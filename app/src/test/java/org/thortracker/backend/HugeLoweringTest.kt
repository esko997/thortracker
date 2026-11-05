package org.thortracker.backend

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Song
import org.thortracker.model.Songs

class HugeLoweringTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun row(track: ByteArray, row: Int) = track.copyOfRange(row * 3, row * 3 + 3)

    private fun withCell(song: Song, pattern: Int, channel: Int, row: Int, cell: Cell): Song {
        val p = song.patterns[pattern]
        val tracks = p.tracks.toMutableList().also { t -> t[channel] = t[channel].toMutableList().also { it[row] = cell } }
        return song.copy(patterns = song.patterns.toMutableList().also { it[pattern] = p.copy(tracks = tracks) })
    }

    private fun patterns(count: Int): Song {
        val blank = Songs.blank()
        return blank.copy(patterns = List(count) { Songs.blankPattern(blank) }, orders = List(count) { it })
    }

    @Test
    fun cellsUseTheDnMacroLayout() {
        val cells = List(Songs.ROWS) { Cell.BLANK }.toMutableList().apply {
            this[0] = Cell(24, 1, 0x0, 0x37) // DN(C_5,1,0x037)
            this[1] = Cell(Cell.CUT) // DN(___,0,0xE00)
            this[2] = Cell(71, 15, 0xF, 0xFF)
        }
        val track = HugeLowering.encodeTrack(cells)
        assertEquals(Songs.ROWS * 3, track.size)
        assertArrayEquals(bytes(24, 0x10, 0x37), row(track, 0))
        assertArrayEquals(bytes(90, 0x0E, 0x00), row(track, 1))
        assertArrayEquals(bytes(71, 0xFF, 0xFF), row(track, 2))
        assertArrayEquals(bytes(90, 0x00, 0x00), row(track, 3))
    }

    /** Expected bytes were produced by hUGETracker 1.0.11 uge2source from equivalent .uge instruments. */
    @Test
    fun instrumentBytesMatchUge2source() {
        val duty = Instrument(duty = 0, volume = 15, envelopePace = 1, length = 10)
        assertArrayEquals(bytes(8, 10, 241, 0, 0, 128), HugeLowering.dutyBytes(duty))
        val wave = Instrument(kind = InstrumentKind.WAVE, length = 200, waveVolume = 2, wave = 0)
        assertArrayEquals(bytes(200, 64, 0, 0, 0, 128), HugeLowering.waveBytes(wave))
        val noiseShort = Instrument(kind = InstrumentKind.NOISE, volume = 13, envelopePace = 2, length = 5, lengthEnabled = true, noiseWidth = 1)
        assertArrayEquals(bytes(210, 0, 0, 197, 0, 0), HugeLowering.noiseBytes(noiseShort))
        val noiseLong = noiseShort.copy(lengthEnabled = false, noiseWidth = 0)
        assertArrayEquals(bytes(210, 0, 0, 5, 0, 0), HugeLowering.noiseBytes(noiseLong))
    }

    @Test
    fun registerFieldDirections() {
        val upSweep = Instrument(sweepTime = 2, sweepDirection = 1, sweepShift = 3)
        assertEquals(0x23, HugeLowering.nr10(upSweep))
        assertEquals(0x2B, HugeLowering.nr10(upSweep.copy(sweepDirection = 0)))
        assertEquals(0x9D, HugeLowering.envelope(Instrument(volume = 9, envelopeDirection = 1, envelopePace = 5)))
        val lengthPulse = HugeLowering.dutyBytes(Instrument(duty = 3, length = 63, lengthEnabled = true))
        assertEquals(0xFF, lengthPulse[1].toInt() and 0xFF)
        assertEquals(0xC0, lengthPulse[5].toInt() and 0xFF)
    }

    @Test
    fun wavesPackHighNibbleFirst() {
        val samples = List(32) { it % 16 }
        assertArrayEquals(bytes(0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF),
            HugeLowering.packWave(samples))
    }

    @Test
    fun trackIndicesAreStableAndOrdersAligned() {
        val song = patterns(3).copy(orders = listOf(2, 0, 2, 1))
        val data = HugeLowering.lower(song)
        assertEquals(12, data.tracks.size)
        assertEquals(4, data.orders.size)
        for (ch in 0 until 4) {
            assertArrayEquals(intArrayOf(8 + ch, ch, 8 + ch, 4 + ch), data.orders[ch])
            assertEquals(data.track(1, ch), 4 + ch)
        }
        assertArrayEquals(intArrayOf(2, 0, 2, 1), data.orderPatterns)
        assertEquals(8, data.orderCountByte)
        assertEquals(15, data.duty.size)
        assertEquals(16, data.waves.size)
    }

    @Test
    fun loopOrderInjectsJumpOnlyIntoTheFinalOrdersCopy() {
        val song = patterns(2).copy(orders = listOf(0, 1, 1), loopOrder = 1)
        val data = HugeLowering.lower(song)
        assertEquals(9, data.tracks.size) // 8 stable + 1 loop variant
        assertEquals(8, data.orders[0][2])
        assertEquals(4, data.orders[0][1]) // earlier use of pattern 1 keeps the original track
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(data.tracks[8], 63))
        assertArrayEquals(bytes(90, 0x00, 0x00), row(data.tracks[4], 63))
        for (ch in 1 until 4) assertEquals(4 + ch, data.orders[ch][2])
    }

    @Test
    fun loopUsesFirstFreeSlotAndRewritesNextOrderJumps() {
        var song = patterns(2).copy(loopOrder = 1)
        song = withCell(song, 1, 0, 63, Cell(30, 1, 0xC, 0x08)) // occupied
        song = withCell(song, 1, 1, 63, Cell(Cell.CUT)) // cut needs E00
        song = withCell(song, 1, 3, 20, Cell(Cell.EMPTY, 0, 0xB, 0x00)) // B00 = "next order" = loop
        song = withCell(song, 1, 2, 40, Cell(Cell.EMPTY, 0, 0xD, 0x05)) // break: needs the loop target too
        val data = HugeLowering.lower(song)
        val wave = data.tracks[data.orders[2][1]]
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(wave, 63))
        val noise = data.tracks[data.orders[3][1]]
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(noise, 20))
        // Row 40: D05 stays on the wave channel; the order target goes into the first free slot (Pulse 1).
        assertArrayEquals(bytes(90, 0x0D, 0x05), row(wave, 40))
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(data.tracks[data.orders[0][1]], 40))
        assertArrayEquals(bytes(30, 0x1C, 0x08), row(data.tracks[data.orders[0][1]], 63))
        assertArrayEquals(bytes(90, 0x0E, 0x00), row(data.tracks[data.orders[1][1]], 63))
    }

    @Test
    fun loopToStartNeedsNoInjection() {
        val data = HugeLowering.lower(patterns(2))
        assertEquals(8, data.tracks.size)
        assertTrue(data.tracks.all { t -> (0 until 64).all { r -> t[r * 3 + 1].toInt() == 0 } })
    }

    /** B00 and Dxx on one final-order row: the driver takes the order from B and the row from D. */
    @Test
    fun nextOrderJumpAndBreakOnTheSameFinalRow() {
        var song = patterns(2).copy(loopOrder = 1)
        song = withCell(song, 1, 0, 30, Cell(Cell.EMPTY, 0, 0xB, 0x00))
        song = withCell(song, 1, 1, 30, Cell(Cell.EMPTY, 0, 0xD, 0x05))
        val data = HugeLowering.lower(song)
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(data.tracks[data.orders[0][1]], 30))
        assertArrayEquals(bytes(90, 0x0D, 0x05), row(data.tracks[data.orders[1][1]], 30))
        // The rewritten B already carries the loop target; no second jump is injected on that row.
        assertArrayEquals(bytes(90, 0x00, 0x00), row(data.tracks[data.orders[2][1]], 30))
        assertEquals(6, data.orders[2][1]) // Wave keeps the stable track: nothing changed there
    }

    /** A jump from an earlier order into the final order lands on the loop-variant copy, target unchanged. */
    @Test
    fun jumpIntoTheFinalOrderFromEarlierOrder() {
        var song = patterns(3).copy(loopOrder = 1)
        song = withCell(song, 0, 0, 10, Cell(Cell.EMPTY, 0, 0xB, 0x03))
        val data = HugeLowering.lower(song)
        assertEquals(0, data.orders[0][0])
        assertArrayEquals(bytes(90, 0x0B, 0x03), row(data.tracks[0], 10))
        assertEquals(12, data.orders[0][2])
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(data.tracks[12], 63))
        assertArrayEquals(bytes(90, 0x00, 0x00), row(data.tracks[8], 63)) // stable track of pattern 2 untouched
    }

    @Test
    fun rejectsSongsWithErrorsListingAllOfThem() {
        var song = Songs.blank()
        song = withCell(song, 0, 0, 1, Cell(24, 1, 0x6, 0x00))
        song = withCell(song, 0, 2, 2, Cell(24, 1, 0xA, 0x01))
        val error = assertThrows(IllegalArgumentException::class.java) { HugeLowering.lower(song) }
        assertEquals(2, error.message!!.lines().size)
    }

    @Test
    fun demoLowers() {
        val data = HugeLowering.lower(Songs.demo())
        assertEquals(13, data.tracks.size) // 3 patterns x 4 + Pulse 2 loop variant of theme B
        assertEquals(12, data.orders[1][4])
        assertArrayEquals(bytes(90, 0x0B, 0x02), row(data.tracks[12], 63))
        assertTrue(data.orders.all { it.size == 5 })
    }
}
