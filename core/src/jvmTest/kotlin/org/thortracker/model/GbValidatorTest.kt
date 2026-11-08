package org.thortracker.model

import org.thortracker.system.gb.GbInstrument
import org.thortracker.system.gb.gb
import org.thortracker.system.gb.HugeRules
import org.thortracker.system.gb.GbValidator
import org.thortracker.system.gb.GbSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.HugeLimits

class GbValidatorTest {
    private val blank = Songs.blank()
    private val pulse1 = 0
    private val pulse2 = 1
    private val wave = 2
    private val noise = 3

    private fun errorsFor(channel: Int, cell: Cell, song: Song = blank): List<String> =
        GbValidator.errors(TestSongs.withCell(song, 0, channel, 10, cell))

    private fun assertRejected(channel: Int, cell: Cell, fragment: String, song: Song = blank) {
        val errors = errorsFor(channel, cell, song)
        assertEquals("expected one error for $cell on channel $channel: $errors", 1, errors.size)
        assertTrue("\"${errors[0]}\" should mention \"$fragment\"", errors[0].contains(fragment))
        assertTrue("location missing: ${errors[0]}", errors[0].contains("row 10"))
        // The editor hint uses the same rule.
        assertTrue(GbSystem.cellProblem(song, channel, cell)!!.contains(fragment))
    }

    private fun assertAccepted(channel: Int, cell: Cell, song: Song = blank) {
        assertEquals(emptyList<String>(), errorsFor(channel, cell, song))
        assertNull(GbSystem.cellProblem(song, channel, cell))
    }

    @Test
    fun bundledSongsAreExportable() {
        assertEquals(emptyList<String>(), GbValidator.errors(Songs.blank()))
        assertEquals(emptyList<String>(), GbValidator.errors(Songs.demo()))
        assertEquals(emptyList<String>(), GbValidator.warnings(Songs.demo()))
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
        assertAccepted(wave, Cell(54, 0))
        assertAccepted(wave, Cell(Cell.EMPTY, 0, 0xC, 0x0A))
    }

    @Test
    fun effectRulesFollowHugeDriver() {
        assertAccepted(pulse1, Cell(48, 1, 0x0, 0x47))
        assertRejected(pulse1, Cell(48, 1, 0x6, 0x01), "6xx")
        assertRejected(pulse2, Cell(48, 1, 0x1, 0x00), "does nothing")
        assertRejected(noise, Cell(48, 1, 0x3, 0x10), "not supported on Noise")
        assertRejected(pulse1, Cell(48, 1, 0x4, 0x30), "depth 0")
        assertRejected(pulse1, Cell(48, 1, 0x7, 0x00), "never plays")
        assertAccepted(pulse1, Cell(48, 1, 0x7, 0x02))
        assertAccepted(pulse2, Cell(48, 1, 0x9, 0xC0))
        assertAccepted(wave, Cell(48, 1, 0x9, 0x0F))
        assertRejected(wave, Cell(48, 1, 0x9, 0x10), "only waves 00-0F")
        assertAccepted(noise, Cell(48, 1, 0x9, 0x08))
        assertRejected(noise, Cell(48, 1, 0x9, 0x04), "LFSR width")
        assertRejected(wave, Cell(48, 1, 0xA, 0x01), "not supported on the Wave")
        assertRejected(pulse1, Cell(48, 1, 0xA, 0x11), "one digit must be 0")
        assertAccepted(pulse1, Cell(48, 1, 0xA, 0x02))
        assertRejected(wave, Cell(48, 1, 0xC, 0x1A), "x must be 0")
        assertAccepted(noise, Cell(48, 1, 0xC, 0x1A))
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
            assertRejected(noise, Cell(48, 1, effect, param), "not supported on Noise")
            assertAccepted(pulse1, Cell(48, 1, effect, param))
            assertTrue(GbSystem.describeEffect(blank, noise, effect, param).endsWith("not supported on Noise"))
        }
        assertAccepted(noise, Cell(48, 1, 0x0, 0x00))
        assertAccepted(noise, Cell(48, 1, 0xE, 0x02))
    }

    @Test
    fun positionJumpsAreOneBasedAndBounded() {
        val song = TestSongs.patterns(3)
        assertAccepted(pulse1, Cell(Cell.EMPTY, 0, 0xB, 0x00), song)
        assertAccepted(pulse1, Cell(Cell.EMPTY, 0, 0xB, 0x03), song)
        assertRejected(pulse1, Cell(Cell.EMPTY, 0, 0xB, 0x04), "the song has 3 orders", song)
        assertEquals("Jump to order 0", GbSystem.describeEffect(song, 0, 0xB, 0x01))
        assertEquals("Jump to the next order", GbSystem.describeEffect(song, 0, 0xB, 0x00))
        assertEquals("Break to row 0 of the next order", GbSystem.describeEffect(song, 0, 0xD, 0x01))
    }

    @Test
    fun sweepOnlyOnPulseOne() {
        val song = TestSongs.withPulse(blank, 4, GbInstrument(name = "Zap", sweepTime = 3, sweepShift = 2))
        assertAccepted(pulse1, Cell(54, 4), song)
        assertRejected(pulse2, Cell(54, 4), "frequency sweep", song)
        // Sweep time without a shift does not change pitch, so it is harmless on Pulse 2.
        val inert = TestSongs.withPulse(blank, 4, GbInstrument(name = "Inert", sweepTime = 3, sweepShift = 0))
        assertAccepted(pulse2, Cell(54, 4), inert)
    }

    @Test
    fun loopWithoutFreeEffectSlotIsAConflict() {
        var song = TestSongs.patterns(2).copy(loopOrder = 1)
        assertEquals(emptyList<String>(), GbValidator.errors(song))
        for (ch in 0 until 4) song = TestSongs.withCell(song, 1, ch, 63, Cell(Cell.EMPTY, 0, 0xC, 0x05))
        val errors = GbValidator.errors(song)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("free effect slot on row 63"))
        assertTrue(errors[0].contains("B02"))
        // An explicit jump on that row resolves it.
        song = TestSongs.withCell(song, 1, 2, 63, Cell(Cell.EMPTY, 0, 0xB, 0x02))
        assertEquals(emptyList<String>(), GbValidator.errors(song))
        // A cut needs its slot for E00, so it is not free either.
        var cuts = TestSongs.patterns(2).copy(loopOrder = 1)
        for (ch in 0 until 4) cuts = TestSongs.withCell(cuts, 1, ch, 63, Cell(Cell.CUT))
        assertEquals(1, GbValidator.errors(cuts).size)
    }

    @Test
    fun targetLimits() {
        val tooManyOrders = blank.copy(orders = List(HugeLimits.MAX_ORDERS + 1) { 0 })
        assertTrue(GbValidator.errors(tooManyOrders).single().contains("at most ${HugeLimits.MAX_ORDERS}"))
        assertEquals(emptyList<String>(), GbValidator.errors(blank.copy(orders = List(HugeLimits.MAX_ORDERS) { 0 })))

        val longTitle = blank.copy(title = "é".repeat(128)) // 256 UTF-8 bytes
        assertTrue(GbValidator.errors(longTitle).single().contains("Title is 256 bytes"))
        assertEquals(emptyList<String>(), GbValidator.errors(blank.copy(title = "x".repeat(255))))
    }

    @Test
    fun distinctTrackLimitMatchesPreviewCapacity() {
        // 69 patterns x 4 channels of pairwise-different tracks = 276 > 272.
        var song = TestSongs.patterns(69)
        for (p in 0 until 69) for (ch in 0 until 4) {
            song = TestSongs.withCell(song, p, ch, p % 64, Cell(36 + ch + 4 * (p / 64), 1))
        }
        val errors = GbValidator.errors(song)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("276 distinct channel patterns"))
        // Repeating patterns in the order list does not use more slots.
        val reused = song.copy(orders = List(127) { it % 60 })
        assertEquals(emptyList<String>(), GbValidator.errors(reused))
    }

    @Test
    fun warningsForTimingThatNeverHappens() {
        val delayed = TestSongs.withCell(blank, 0, 0, 3, Cell(48, 1, 0x7, 0x06))
        assertTrue(GbValidator.warnings(delayed).single().contains("note is skipped"))
        val cut = TestSongs.withCell(blank, 0, 1, 3, Cell(48, 1, 0xE, 0x09))
        assertTrue(GbValidator.warnings(cut).single().contains("nothing is cut"))
        assertEquals(emptyList<String>(), GbValidator.errors(cut))
    }

    @Test
    fun noiseNotesOutsideTheAudibleRangeWarn() {
        // get_note_poly: driver notes 0-3 and 64-67 (cells hold them + 24) give clock shift 14 (LFSR never clocked, Pan Docs NR43);
        // 68-71 wrap to the same NR43 as E-3.
        for (note in listOf(0, 3, 64, 67).map { it + 24 }) {
            val song = TestSongs.withCell(blank, 0, noise, 0, Cell(note, 1))
            assertTrue(GbValidator.warnings(song).single().contains("plays no noise"))
        }
        val wrapped = TestSongs.withCell(blank, 0, noise, 0, Cell(94, 1))
        val warning = GbValidator.warnings(wrapped).single()
        assertTrue(warning.contains("wraps around") && warning.contains("E-3"))
        for (note in listOf(4, 12, 63).map { it + 24 }) {
            assertEquals(emptyList<String>(), GbValidator.warnings(TestSongs.withCell(blank, 0, noise, 0, Cell(note, 1))))
        }
        // The same note numbers are ordinary pitches on the tonal channels.
        assertEquals(emptyList<String>(), GbValidator.warnings(TestSongs.withCell(blank, 0, pulse1, 0, Cell(94, 1))))
    }

    @Test
    fun legatoNoteAfterCutOrBeforeAnyInstrumentWarns() {
        fun song(vararg cells: Pair<Int, Cell>, channel: Int = pulse1): Song =
            cells.fold(blank) { s, (row, cell) -> TestSongs.withCell(s, 0, channel, row, cell) }

        val afterCut = GbValidator.warnings(song(0 to Cell(48, 1), 4 to Cell(Cell.CUT), 8 to Cell(50, 0)))
        assertTrue(afterCut.single().contains("row 8"))
        assertTrue(afterCut.single().contains("row 4"))

        val afterEffectCut = GbValidator.warnings(song(0 to Cell(48, 1, 0xE, 0x02), 8 to Cell(50, 0)))
        assertTrue(afterEffectCut.single().contains("E02 cut"))

        val atStart = GbValidator.warnings(song(5 to Cell(48, 0), channel = pulse2))
        assertTrue(atStart.single().contains("start of the song"))

        // Legato after a sounding note, a new instrument, or a volume restart is fine.
        assertEquals(emptyList<String>(), GbValidator.warnings(song(0 to Cell(48, 1), 8 to Cell(50, 0))))
        assertEquals(emptyList<String>(), GbValidator.warnings(song(0 to Cell(48, 1), 4 to Cell(Cell.CUT), 8 to Cell(50, 2))))
        assertEquals(emptyList<String>(), GbValidator.warnings(
            song(0 to Cell(48, 1), 4 to Cell(Cell.CUT), 6 to Cell(Cell.EMPTY, 0, 0xC, 0x0A), 8 to Cell(50, 0))))
        // A Cxy after a legato note cannot restart (no trigger bit), so the next legato note is still silent.
        val stillSilent = GbValidator.warnings(song(0 to Cell(48, 1), 2 to Cell(Cell.CUT), 4 to Cell(49, 0),
            6 to Cell(Cell.EMPTY, 0, 0xC, 0x0A), 8 to Cell(50, 0)))
        assertEquals(2, stillSilent.size)
        // The order list carries state across patterns: a cut at the end of one silences the next.
        var twoOrders = TestSongs.patterns(2)
        twoOrders = TestSongs.withCell(twoOrders, 0, wave, 0, Cell(48, 1))
        twoOrders = TestSongs.withCell(twoOrders, 0, wave, 63, Cell(Cell.CUT))
        twoOrders = TestSongs.withCell(twoOrders, 1, wave, 0, Cell(48, 0))
        assertTrue(GbValidator.warnings(twoOrders).single().contains("P1"))
    }

    @Test
    fun bankEstimateWarnsAboveOneRomBank() {
        // uge2source GBDK size: 22 (descriptor + order_cnt) + 192/track + 8/order + 270 instruments + 256 waves.
        fun distinctSong(patterns: Int): Song {
            var song = TestSongs.patterns(patterns)
            for (p in 0 until patterns) for (ch in 0 until 4) song = TestSongs.withCell(song, p, ch, p, Cell(46 + ch, 1))
            return song
        }
        assertEquals(emptyList<String>(), GbValidator.warnings(distinctSong(20))) // 16068 bytes
        assertTrue(GbValidator.warnings(distinctSong(21)).single().contains("16844 bytes"))
    }

    @Test
    fun structuralErrorsAreReportedFirstAndCapped() {
        var junk = blank
        for (row in 0 until 64) for (ch in 0 until 4) junk = TestSongs.withCell(junk, 0, ch, row, Cell(123))
        val errors = GbValidator.structuralErrors(junk)
        assertEquals(GbValidator.MAX_REPORTED + 1, errors.size)
        assertTrue(errors.last().startsWith("...and "))
    }

    @Test fun aStaleJumpOnlyBlocksPlaybackWhenAnOrderPlaysItsPattern() {
        // Pattern 1 is not in the order list and jumps to an order that no longer exists.
        var song = TestSongs.patterns(2).copy(orders = listOf(0))
        song = TestSongs.withCell(song, 1, 1, 15, Cell(Cell.EMPTY, 0, HugeRules.FX_POSITION_JUMP, 0x0B))
        assertEquals(emptyList<String>(), GbValidator.errors(song))
        assertTrue(GbValidator.warnings(song).any { it.contains("B0B") && it.contains("no longer has") })
        // Once an order plays it, it blocks playback and export.
        val played = song.copy(orders = listOf(0, 1))
        assertTrue(GbValidator.errors(played).single().contains("B0B would jump to order 10"))
    }

    @Test fun aPatternLoopPlaysOnlyThatOrderAndKeepsItsJumpsInside() {
        // Orders 0,1,2 play patterns 0,1,2; pattern 1 jumps to order 2 and the song loops to order 1.
        var song = TestSongs.patterns(3).copy(orders = listOf(0, 1, 2), loopOrder = 1)
        song = TestSongs.withCell(song, 1, 1, 15, Cell(Cell.EMPTY, 0, HugeRules.FX_POSITION_JUMP, 0x03))
        song = TestSongs.withCell(song, 1, 2, 20, Cell(Cell.EMPTY, 0, HugeRules.FX_POSITION_JUMP, 0x00))
        val loop = HugeRules.patternLoop(song, 1)
        assertEquals(listOf(1), loop.orders)
        assertEquals(0, loop.loopOrder)
        assertEquals(0x01, loop.patterns[1].tracks[1][15].param)   // B03 now replays the loop
        assertEquals(0x00, loop.patterns[1].tracks[2][20].param)   // B00 already means "next order" = the loop
        assertEquals(song.patterns[0], loop.patterns[0])
        assertEquals(emptyList<String>(), GbValidator.errors(loop))
        org.thortracker.backend.HugeLowering.lower(loop)              // playable by the driver
        assertEquals(song.patterns[1].tracks[1][15].param, 0x03)    // the document is untouched
    }

    @Test fun theNearestOrderPlayingAPatternPrefersTheCloserThenEarlierOne() {
        val orders = listOf(0, 1, 2, 1, 2)
        assertEquals(2, Songs.nearestOrderPlaying(orders, 2, 2))       // still where it was
        assertEquals(3, Songs.nearestOrderPlaying(listOf(9) + orders, 2, 2)) // shifted by an insert before it
        assertEquals(1, Songs.nearestOrderPlaying(orders, 1, 2))       // tie between 1 and 3: earlier wins
        assertEquals(-1, Songs.nearestOrderPlaying(orders, 7, 2))      // no order plays it
    }

    @Test
    fun shortPatternsNeedAnEndingSlotAndBreaksIntoPlayedRows() {
        var song = TestSongs.patterns(2).let { s -> s.copy(patterns = s.patterns.mapIndexed { i, p -> if (i == 1) p.copy(length = 16) else p }) }
        assertEquals(emptyList<String>(), GbValidator.errors(song))
        // A problem on a hidden row is exported blank, so it does not block export.
        song = TestSongs.withCell(song, 1, 0, 40, Cell(Cell.EMPTY, 0, 0xD, 0x00))
        assertEquals(emptyList<String>(), GbValidator.errors(song))
        song = TestSongs.withCell(song, 0, 0, 10, Cell(Cell.EMPTY, 0, 0xD, 0x15)) // row 20 of a 16-row pattern
        assertTrue(GbValidator.errors(song).single().contains("D15 breaks to row 20 of order 1"))
        song = TestSongs.withCell(song, 0, 0, 10, Cell(Cell.EMPTY, 0, 0xD, 0x10)) // row 15: its last row
        assertEquals(emptyList<String>(), GbValidator.errors(song))
        for (ch in 0 until 4) song = TestSongs.withCell(song, 1, ch, 15, Cell(Cell.EMPTY, 0, 0xC, 0x05))
        assertTrue(GbValidator.errors(song).single().contains("row 15 ends it with D01"))
    }

    @Test
    fun tuningMustBeStructurallyValid() {
        val ok = Tuning("24", List(24) { 50.0 * (it + 1) })
        assertEquals(emptyList<String>(), GbValidator.structuralErrors(blank.copy(tuning = ok)))
        val cases = listOf(
            ok.copy(root = 96) to "root 96",
            ok.copy(root = 23) to "root 23",
            ok.copy(root = -1) to "root -1",
            ok.copy(tolerance = 0) to "tolerance 0",
            ok.copy(cents = emptyList()) to "no degrees",
            ok.copy(cents = listOf(100.0, 100.0)) to "must ascend",
            ok.copy(cents = listOf(0.0, 1200.0)) to "not above 1/1",
            ok.copy(cents = listOf(Double.POSITIVE_INFINITY)) to "not a number",
            ok.copy(cents = List(257) { it + 1.0 }) to "at most 256",
            ok.copy(name = "n".repeat(256)) to "name",
        )
        for ((tuning, fragment) in cases) {
            val errors = GbValidator.structuralErrors(blank.copy(tuning = tuning))
            assertEquals("$tuning: $errors", 1, errors.size)
            assertTrue("\"${errors[0]}\" should mention \"$fragment\"", errors[0].contains(fragment))
        }
    }

    @Test
    fun tunedSongsWarnAboutNotesOutsideTheTuning() {
        val just = Scl.parse("Just\n12\n16/15\n9/8\n6/5\n5/4\n4/3\n45/32\n3/2\n8/5\n5/3\n9/5\n15/8\n2/1\n", "f")
        val map = TuningMap(just, GbSystem)
        // 5/4 is 13.7 cents below the table's E-5 and too far above D#-5 for 40y, so E-5 is not in the tuning at 5 cents.
        assertEquals(-1, map.index(28, 0))
        val tuned = map.pitches.first { it.detune > 0 }
        val song = blank.copy(tuning = just)
        fun warningsFor(channel: Int, cell: Cell, s: Song = song) = GbValidator.warnings(TestSongs.withCell(s, 0, channel, 10, cell))

        assertEquals(emptyList<String>(), warningsFor(pulse1, Cell(tuned.note, 1, 0x4, tuned.detune)))
        assertEquals(emptyList<String>(), warningsFor(wave, Cell(48, 1)))
        // A plain note that is itself a tuned pitch keeps its effect slot free for other effects.
        assertEquals(emptyList<String>(), warningsFor(pulse2, Cell(48, 1, 0x0, 0x47)))

        val outside = warningsFor(pulse1, Cell(52, 1)).single()
        assertTrue(outside, outside.contains("row 10") && outside.contains("E-5 is outside the song tuning \"Just\""))
        val bent = if (tuned.detune < 15) tuned.detune + 1 else tuned.detune - 1
        assertEquals(-1, map.index(tuned.note, bent))
        val wrongBend = warningsFor(wave, Cell(tuned.note, 1, 0x4, bent)).single()
        assertTrue(wrongBend, wrongBend.contains("Wave row 10") && wrongBend.contains("+$bent is outside"))
        val occupied = warningsFor(pulse2, Cell(52, 1, 0xC, 0x08)).single()
        assertTrue(occupied, occupied.contains("Pulse 2 row 10") && occupied.contains("holds C08"))
        val vibrato = warningsFor(pulse1, Cell(52, 1, 0x4, 0x13)).single()
        assertTrue(vibrato, vibrato.contains("holds 413"))

        // Noise is never tuned, hidden rows are not played and untuned songs never warn.
        assertEquals(emptyList<String>(), warningsFor(noise, Cell(52, 1)))
        val short = song.copy(patterns = listOf(song.patterns[0].copy(length = 8)))
        assertEquals(emptyList<String>(), warningsFor(pulse1, Cell(52, 1), short))
        assertEquals(emptyList<String>(), warningsFor(pulse1, Cell(52, 1, 0x4, 0x03), blank))
        // Every note of a 12-equal tuning is plain, so the demo song needs no changes.
        assertEquals(emptyList<String>(), GbValidator.warnings(Songs.demo().copy(tuning = Tuning("12", List(12) { 100.0 * (it + 1) }))))
    }
}
