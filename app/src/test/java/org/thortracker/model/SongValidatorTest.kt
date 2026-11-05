package org.thortracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.HugeLimits

class SongValidatorTest {
    private val blank = Songs.blank()
    private val pulse1 = 0
    private val pulse2 = 1
    private val wave = 2
    private val noise = 3

    private fun errorsFor(channel: Int, cell: Cell, song: Song = blank): List<String> =
        SongValidator.errors(TestSongs.withCell(song, 0, channel, 10, cell))

    private fun assertRejected(channel: Int, cell: Cell, fragment: String, song: Song = blank) {
        val errors = errorsFor(channel, cell, song)
        assertEquals("expected one error for $cell on channel $channel: $errors", 1, errors.size)
        assertTrue("\"${errors[0]}\" should mention \"$fragment\"", errors[0].contains(fragment))
        assertTrue("location missing: ${errors[0]}", errors[0].contains("row 10"))
        // The editor hint uses the same rule.
        assertTrue(Songs.cellProblem(song, channel, cell)!!.contains(fragment))
    }

    private fun assertAccepted(channel: Int, cell: Cell, song: Song = blank) {
        assertEquals(emptyList<String>(), errorsFor(channel, cell, song))
        assertNull(Songs.cellProblem(song, channel, cell))
    }

    @Test
    fun bundledSongsAreExportable() {
        assertEquals(emptyList<String>(), SongValidator.errors(Songs.blank()))
        assertEquals(emptyList<String>(), SongValidator.errors(Songs.demo()))
        assertEquals(emptyList<String>(), SongValidator.warnings(Songs.demo()))
    }

    @Test
    fun cutsNeedTheirEffectSlotAndNoInstrument() {
        assertAccepted(pulse1, Cell(Cell.CUT))
        assertRejected(pulse1, Cell(Cell.CUT, 0, 0xC, 0x08), "E00")
        assertRejected(pulse1, Cell(Cell.CUT, 3), "cannot load instrument")
    }

    @Test
    fun instrumentWithoutNoteIsNotSilentlyIgnored() {
        assertRejected(wave, Cell(Cell.EMPTY, 2), "without a note")
        assertAccepted(wave, Cell(30, 0))
        assertAccepted(wave, Cell(Cell.EMPTY, 0, 0xC, 0x0A))
    }

    @Test
    fun effectRulesFollowHugeDriver() {
        assertAccepted(pulse1, Cell(24, 1, 0x0, 0x47))
        assertRejected(pulse1, Cell(24, 1, 0x6, 0x01), "6xx")
        assertRejected(pulse2, Cell(24, 1, 0x1, 0x00), "does nothing")
        assertRejected(noise, Cell(24, 1, 0x3, 0x10), "not supported on Noise")
        assertRejected(pulse1, Cell(24, 1, 0x4, 0x30), "depth 0")
        assertRejected(pulse1, Cell(24, 1, 0x7, 0x00), "never plays")
        assertAccepted(pulse1, Cell(24, 1, 0x7, 0x02))
        assertAccepted(pulse2, Cell(24, 1, 0x9, 0xC0))
        assertAccepted(wave, Cell(24, 1, 0x9, 0x0F))
        assertRejected(wave, Cell(24, 1, 0x9, 0x10), "only waves 00-0F")
        assertAccepted(noise, Cell(24, 1, 0x9, 0x08))
        assertRejected(noise, Cell(24, 1, 0x9, 0x04), "LFSR width")
        assertRejected(wave, Cell(24, 1, 0xA, 0x01), "not supported on the Wave")
        assertRejected(pulse1, Cell(24, 1, 0xA, 0x11), "one digit must be 0")
        assertAccepted(pulse1, Cell(24, 1, 0xA, 0x02))
        assertRejected(wave, Cell(24, 1, 0xC, 0x1A), "x must be 0")
        assertAccepted(noise, Cell(24, 1, 0xC, 0x1A))
        assertRejected(pulse1, Cell(Cell.EMPTY, 0, 0xD, 0x00), "D00 does nothing")
        assertRejected(pulse1, Cell(Cell.EMPTY, 0, 0xD, 0x41), "D01-40")
        assertAccepted(pulse1, Cell(Cell.EMPTY, 0, 0xD, 0x40))
        assertRejected(pulse1, Cell(Cell.EMPTY, 0, 0xF, 0x00), "F00")
        assertAccepted(pulse1, Cell(Cell.EMPTY, 0, 0xE, 0x00))
    }

    /** hUGEDriver 6.1.3's CH4 pitch-effect paths read a period byte as a note ID (fx_arpeggio, fx_vibrato, fx_porta_*). */
    @Test
    fun pitchEffectsAreRejectedOnNoise() {
        for ((effect, param) in listOf(0x0 to 0x37, 0x1 to 0x04, 0x2 to 0x04, 0x3 to 0x10, 0x4 to 0x33)) {
            assertRejected(noise, Cell(24, 1, effect, param), "not supported on Noise")
            assertAccepted(pulse1, Cell(24, 1, effect, param))
            assertTrue(Songs.describeEffect(blank, noise, effect, param).endsWith("not supported on Noise"))
        }
        assertAccepted(noise, Cell(24, 1, 0x0, 0x00))
        assertAccepted(noise, Cell(24, 1, 0xE, 0x02))
    }

    @Test
    fun positionJumpsAreOneBasedAndBounded() {
        val song = TestSongs.patterns(3)
        assertAccepted(pulse1, Cell(Cell.EMPTY, 0, 0xB, 0x00), song)
        assertAccepted(pulse1, Cell(Cell.EMPTY, 0, 0xB, 0x03), song)
        assertRejected(pulse1, Cell(Cell.EMPTY, 0, 0xB, 0x04), "the song has 3 orders", song)
        assertEquals("Jump to order 0", Songs.describeEffect(song, 0, 0xB, 0x01))
        assertEquals("Jump to the next order", Songs.describeEffect(song, 0, 0xB, 0x00))
        assertEquals("Break to row 0 of the next order", Songs.describeEffect(song, 0, 0xD, 0x01))
    }

    @Test
    fun sweepOnlyOnPulseOne() {
        val song = TestSongs.withPulse(blank, 4, Instrument(name = "Zap", sweepTime = 3, sweepShift = 2))
        assertAccepted(pulse1, Cell(30, 4), song)
        assertRejected(pulse2, Cell(30, 4), "frequency sweep", song)
        // Sweep time without a shift does not change pitch, so it is harmless on Pulse 2.
        val inert = TestSongs.withPulse(blank, 4, Instrument(name = "Inert", sweepTime = 3, sweepShift = 0))
        assertAccepted(pulse2, Cell(30, 4), inert)
    }

    @Test
    fun loopWithoutFreeEffectSlotIsAConflict() {
        var song = TestSongs.patterns(2).copy(loopOrder = 1)
        assertEquals(emptyList<String>(), SongValidator.errors(song))
        for (ch in 0 until 4) song = TestSongs.withCell(song, 1, ch, 63, Cell(Cell.EMPTY, 0, 0xC, 0x05))
        val errors = SongValidator.errors(song)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("free effect slot on row 63"))
        assertTrue(errors[0].contains("B02"))
        // An explicit jump on that row resolves it.
        song = TestSongs.withCell(song, 1, 2, 63, Cell(Cell.EMPTY, 0, 0xB, 0x02))
        assertEquals(emptyList<String>(), SongValidator.errors(song))
        // A cut needs its slot for E00, so it is not free either.
        var cuts = TestSongs.patterns(2).copy(loopOrder = 1)
        for (ch in 0 until 4) cuts = TestSongs.withCell(cuts, 1, ch, 63, Cell(Cell.CUT))
        assertEquals(1, SongValidator.errors(cuts).size)
    }

    @Test
    fun targetLimits() {
        val tooManyOrders = blank.copy(orders = List(HugeLimits.MAX_ORDERS + 1) { 0 })
        assertTrue(SongValidator.errors(tooManyOrders).single().contains("at most ${HugeLimits.MAX_ORDERS}"))
        assertEquals(emptyList<String>(), SongValidator.errors(blank.copy(orders = List(HugeLimits.MAX_ORDERS) { 0 })))

        val longTitle = blank.copy(title = "é".repeat(128)) // 256 UTF-8 bytes
        assertTrue(SongValidator.errors(longTitle).single().contains("Title is 256 bytes"))
        assertEquals(emptyList<String>(), SongValidator.errors(blank.copy(title = "x".repeat(255))))
    }

    @Test
    fun distinctTrackLimitMatchesPreviewCapacity() {
        // 69 patterns x 4 channels of pairwise-different tracks = 276 > 272.
        var song = TestSongs.patterns(69)
        for (p in 0 until 69) for (ch in 0 until 4) {
            song = TestSongs.withCell(song, p, ch, p % 64, Cell(ch + 4 * (p / 64), 1))
        }
        val errors = SongValidator.errors(song)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("276 distinct channel patterns"))
        // Repeating patterns in the order list does not use more slots.
        val reused = song.copy(orders = List(127) { it % 60 })
        assertEquals(emptyList<String>(), SongValidator.errors(reused))
    }

    @Test
    fun warningsForTimingThatNeverHappens() {
        val delayed = TestSongs.withCell(blank, 0, 0, 3, Cell(24, 1, 0x7, 0x06))
        assertTrue(SongValidator.warnings(delayed).single().contains("note is skipped"))
        val cut = TestSongs.withCell(blank, 0, 1, 3, Cell(24, 1, 0xE, 0x09))
        assertTrue(SongValidator.warnings(cut).single().contains("nothing is cut"))
        assertEquals(emptyList<String>(), SongValidator.errors(cut))
    }

    @Test
    fun noiseNotesOutsideTheAudibleRangeWarn() {
        // get_note_poly: notes 0-3 and 64-67 give clock shift 14 (LFSR never clocked, Pan Docs NR43);
        // 68-71 wrap to the same NR43 as E-3.
        for (note in listOf(0, 3, 64, 67)) {
            val song = TestSongs.withCell(blank, 0, noise, 0, Cell(note, 1))
            assertTrue(SongValidator.warnings(song).single().contains("plays no noise"))
        }
        val wrapped = TestSongs.withCell(blank, 0, noise, 0, Cell(70, 1))
        val warning = SongValidator.warnings(wrapped).single()
        assertTrue(warning.contains("wraps around") && warning.contains("E-3"))
        for (note in listOf(4, 24, 63)) {
            assertEquals(emptyList<String>(), SongValidator.warnings(TestSongs.withCell(blank, 0, noise, 0, Cell(note, 1))))
        }
        // The same note numbers are ordinary pitches on the tonal channels.
        assertEquals(emptyList<String>(), SongValidator.warnings(TestSongs.withCell(blank, 0, pulse1, 0, Cell(70, 1))))
    }

    @Test
    fun legatoNoteAfterCutOrBeforeAnyInstrumentWarns() {
        fun song(vararg cells: Pair<Int, Cell>, channel: Int = pulse1): Song =
            cells.fold(blank) { s, (row, cell) -> TestSongs.withCell(s, 0, channel, row, cell) }

        val afterCut = SongValidator.warnings(song(0 to Cell(24, 1), 4 to Cell(Cell.CUT), 8 to Cell(26, 0)))
        assertTrue(afterCut.single().contains("row 8"))
        assertTrue(afterCut.single().contains("row 4"))

        val afterEffectCut = SongValidator.warnings(song(0 to Cell(24, 1, 0xE, 0x02), 8 to Cell(26, 0)))
        assertTrue(afterEffectCut.single().contains("E02 cut"))

        val atStart = SongValidator.warnings(song(5 to Cell(24, 0), channel = pulse2))
        assertTrue(atStart.single().contains("start of the song"))

        // Legato after a sounding note, a new instrument, or a volume restart is fine.
        assertEquals(emptyList<String>(), SongValidator.warnings(song(0 to Cell(24, 1), 8 to Cell(26, 0))))
        assertEquals(emptyList<String>(), SongValidator.warnings(song(0 to Cell(24, 1), 4 to Cell(Cell.CUT), 8 to Cell(26, 2))))
        assertEquals(emptyList<String>(), SongValidator.warnings(
            song(0 to Cell(24, 1), 4 to Cell(Cell.CUT), 6 to Cell(Cell.EMPTY, 0, 0xC, 0x0A), 8 to Cell(26, 0))))
        // A Cxy after a legato note cannot restart (no trigger bit), so the next legato note is still silent.
        val stillSilent = SongValidator.warnings(song(0 to Cell(24, 1), 2 to Cell(Cell.CUT), 4 to Cell(25, 0),
            6 to Cell(Cell.EMPTY, 0, 0xC, 0x0A), 8 to Cell(26, 0)))
        assertEquals(2, stillSilent.size)
        // The order list carries state across patterns: a cut at the end of one silences the next.
        var twoOrders = TestSongs.patterns(2)
        twoOrders = TestSongs.withCell(twoOrders, 0, wave, 0, Cell(24, 1))
        twoOrders = TestSongs.withCell(twoOrders, 0, wave, 63, Cell(Cell.CUT))
        twoOrders = TestSongs.withCell(twoOrders, 1, wave, 0, Cell(24, 0))
        assertTrue(SongValidator.warnings(twoOrders).single().contains("P1"))
    }

    @Test
    fun bankEstimateWarnsAboveOneRomBank() {
        // uge2source GBDK size: 22 (descriptor + order_cnt) + 192/track + 8/order + 270 instruments + 256 waves.
        fun distinctSong(patterns: Int): Song {
            var song = TestSongs.patterns(patterns)
            for (p in 0 until patterns) for (ch in 0 until 4) song = TestSongs.withCell(song, p, ch, p, Cell(10 + ch, 1))
            return song
        }
        assertEquals(emptyList<String>(), SongValidator.warnings(distinctSong(20))) // 16068 bytes
        assertTrue(SongValidator.warnings(distinctSong(21)).single().contains("16844 bytes"))
    }

    @Test
    fun structuralErrorsAreReportedFirstAndCapped() {
        val wrongBackend = blank.copy(backend = "gba-m4a")
        assertTrue(SongValidator.errors(wrongBackend).single().contains("not supported"))
        var junk = blank
        for (row in 0 until 64) for (ch in 0 until 4) junk = TestSongs.withCell(junk, 0, ch, row, Cell(99))
        val errors = SongValidator.structuralErrors(junk)
        assertEquals(SongValidator.MAX_REPORTED + 1, errors.size)
        assertTrue(errors.last().startsWith("...and "))
    }

    @Test
    fun shortPatternsNeedAnEndingSlotAndBreaksIntoPlayedRows() {
        var song = TestSongs.patterns(2).let { s -> s.copy(patterns = s.patterns.mapIndexed { i, p -> if (i == 1) p.copy(length = 16) else p }) }
        assertEquals(emptyList<String>(), SongValidator.errors(song))
        // A problem on a hidden row is exported blank, so it does not block export.
        song = TestSongs.withCell(song, 1, 0, 40, Cell(Cell.EMPTY, 0, 0xD, 0x00))
        assertEquals(emptyList<String>(), SongValidator.errors(song))
        song = TestSongs.withCell(song, 0, 0, 10, Cell(Cell.EMPTY, 0, 0xD, 0x15)) // row 20 of a 16-row pattern
        assertTrue(SongValidator.errors(song).single().contains("D15 breaks to row 20 of order 1"))
        song = TestSongs.withCell(song, 0, 0, 10, Cell(Cell.EMPTY, 0, 0xD, 0x10)) // row 15: its last row
        assertEquals(emptyList<String>(), SongValidator.errors(song))
        for (ch in 0 until 4) song = TestSongs.withCell(song, 1, ch, 15, Cell(Cell.EMPTY, 0, 0xC, 0x05))
        assertTrue(SongValidator.errors(song).single().contains("row 15 ends it with D01"))
    }
}
