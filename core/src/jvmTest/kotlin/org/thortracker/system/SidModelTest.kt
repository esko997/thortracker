package org.thortracker.system

import org.thortracker.backend.GtSong
import org.thortracker.backend.SidLowering
import org.thortracker.backend.SidPlayerImage
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Tuning
import org.thortracker.model.TuningMap
import org.thortracker.system.sid.SidInstrument
import org.thortracker.system.sid.SidProgram
import org.thortracker.system.sid.SidRow
import org.thortracker.system.sid.SidRules
import org.thortracker.system.sid.SidSound
import org.thortracker.system.sid.SidSpeeds
import org.thortracker.system.sid.SidSystem
import org.thortracker.system.sid.SidTables
import org.thortracker.system.sid.SidTuning
import org.thortracker.system.sid.SidVibrato
import org.thortracker.system.sid.sid
import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** SID model rules: program placement, speed rows, tuning tables, validation messages, pattern loops. */
class SidModelTest {
    private fun waves(vararg rows: Int) = SidProgram(rows.map { SidRow.wave(it) })

    private fun sound(vararg programs: SidProgram): SidSound {
        val bank = SidSound.blank().instruments.map { it.copy(wave = SidProgram()) }.toMutableList()
        programs.forEachIndexed { i, p -> bank[i] = bank[i].copy(wave = p) }
        return SidTables.place(null, SidSound(bank))
    }

    private fun at(s: SidSound, slot: Int) = s.instruments[slot - 1].wave.at

    @Test
    fun programsArePackedFromRowOneWithTheirStopRows() {
        val s = sound(waves(0x21), waves(0x41, 0x11), SidProgram(listOf(SidRow.wave(0x81), SidRow.jump(1))))
        assertEquals(listOf(1, 3, 6), (1..3).map { at(s, it) }) // 1+stop, 2+stop, 2 with its own jump
        assertEquals(7, SidTables.placement(s, SidTables.WAVE).used)
    }

    @Test
    fun anEditedProgramMovesAndTheOthersKeepTheirRows() {
        val before = sound(waves(0x21), waves(0x41), waves(0x11))
        val grown = before.instruments.toMutableList().also { it[0] = it[0].copy(wave = it[0].wave.copy(rows = listOf(SidRow.wave(0x21), SidRow.wave(0x41)))) }
        val after = SidTables.place(before, before.copy(instruments = grown))
        assertEquals(at(before, 2), at(after, 2))
        assertEquals(at(before, 3), at(after, 3))
        assertEquals(7, at(after, 1)) // the first gap that holds 3 rows: after instrument 3
        // Shrinking back keeps the new place; the freed rows stay free.
        val shrunk = after.instruments.toMutableList().also { it[0] = it[0].copy(wave = it[0].wave.copy(rows = listOf(SidRow.wave(0x21)))) }
        assertEquals(7, at(SidTables.place(after, after.copy(instruments = shrunk)), 1))
    }

    @Test
    fun identicalProgramsShareRowsUntilOneChanges() {
        val before = sound(waves(0x21), waves(0x21), waves(0x41))
        assertEquals(at(before, 1), at(before, 2))
        val edited = before.instruments.toMutableList().also { it[1] = it[1].copy(wave = it[1].wave.copy(rows = listOf(SidRow.wave(0x81)))) }
        val after = SidTables.place(before, before.copy(instruments = edited))
        assertEquals(at(before, 1), at(after, 1))
        assertEquals(at(before, 3), at(after, 3))
        assertNotEquals(at(after, 1), at(after, 2))
    }

    @Test
    fun loweredTablesHaveAbsoluteJumpsAndCommandRows() {
        val demo = SidSystem.demo()
        val gt = GtSong.of(demo)
        val lead = demo.sid.instruments[2]
        val pulseAt = lead.pulse.at
        // Lead PWM: width, up, down, jump to its row 2 → table row at + 1.
        assertEquals(0xFF, gt.left[SidTables.PULSE][pulseAt + 2])
        assertEquals(pulseAt + 1, gt.right[SidTables.PULSE][pulseAt + 2])
        // Single-row wave programs end with GoatTracker's stop row.
        val bassAt = demo.sid.instruments[1].wave.at
        assertEquals(0xFF to 0, gt.left[SidTables.WAVE][bassAt] to gt.right[SidTables.WAVE][bassAt])
        // Instrument columns point at the placed rows; vibrato at its speed row with its delay.
        assertEquals(lead.wave.at, gt.instruments[2].wave)
        assertEquals(SidSpeeds.of(demo).indexOf(SidSpeeds.vibratoValue(lead)) + 1, gt.instruments[2].vibParam)
        assertEquals(18, gt.instruments[2].vibDelay)
    }

    @Test
    fun speedRowsComeFromVibratosProgramCommandsAndEffectsOnce() {
        val bank = SidSound.blank().instruments.toMutableList()
        bank[0] = bank[0].copy(vibrato = SidVibrato(delay = 2, speed = 3, depth = 4, noteIndependent = true))
        bank[1] = bank[1].copy(wave = SidProgram(listOf(SidRow.wave(0x21), SidRow.command(SidRules.FX_VIBRATO, 0x25))))
        val cells = List(64) { r ->
            when (r) {
                0 -> Cell(48, 1, SidRules.FX_PORTA_UP, 0x10)
                1 -> Cell(effect = SidRules.FX_PORTA_DOWN, param = 0x10)
                2 -> Cell(effect = SidRules.FX_FUNKTEMPO, param = 0x64)
                3 -> Cell(effect = SidRules.FX_VIBRATO, param = 0x25)
                4 -> Cell(effect = SidRules.FX_TONE_PORTA, param = 0x00)
                else -> Cell.BLANK
            }
        }
        val song = SidSystem.blank().let { it.copy(patterns = listOf(Pattern("P", listOf(cells, List(64) { Cell.BLANK }, List(64) { Cell.BLANK }))),
            sound = SidTables.place(it.sid, it.sid.copy(instruments = bank))) }
        assertEquals(listOf(0x8304, 0x0250, 0x0040, 0x0604), SidSpeeds.of(song))
    }

    @Test
    fun withoutATuningTheFrequencyTableIsGoatTrackers() {
        val plain = SidTuning.frequencies(null)
        assertContentEquals(IntArray(96) { GtSong.DEFAULT_FREQ_LO[it] or (GtSong.DEFAULT_FREQ_HI[it] shl 8) }, plain)
        assertEquals(0x1D46, plain[57])
        val edo12 = Tuning("12-EDO", (1..12).map { it * 100.0 })
        assertContentEquals(plain, SidTuning.frequencies(edo12))
    }

    @Test
    fun aTuningRetunesTheTableAndEveryDegreeIsExact() {
        val ratios = listOf(16.0 / 15, 9.0 / 8, 6.0 / 5, 5.0 / 4, 4.0 / 3, 45.0 / 32, 3.0 / 2, 8.0 / 5, 5.0 / 3, 9.0 / 5, 15.0 / 8, 2.0)
        val just = Tuning("Just", ratios.map { 1200.0 * log2(it) }, root = 48)
        val table = SidTuning.frequencies(just)
        val plain = SidTuning.frequencies(null)
        assertEquals(plain[48], table[48]) // 1/1 keeps its pitch
        for ((i, r) in ratios.withIndex()) {
            val got = table[49 + i].toDouble() / table[48]
            assertTrue(abs(1200.0 * log2(got / r)) < 0.5, "degree ${i + 1}: ${1200.0 * log2(got / r)} cents off")
        }
        val map = TuningMap(just, SidSystem)
        assertTrue(map.pitches.isNotEmpty())
        assertTrue(map.pitches.all { abs(it.error) < 1e-9 && it.detune == 0 })
        assertEquals(SidSystem.noteRange.count(), map.pitches.size) // every note plays its own degree
    }

    @Test
    fun validationNamesWhatDoesNotFit() {
        val blank = SidSystem.blank()
        fun withCell(c: Cell, ticks: Int = 6, sound: SidSound = blank.sid): Song {
            val p = blank.patterns[0]
            val tracks = p.tracks.mapIndexed { ch, t -> if (ch == 0) t.toMutableList().also { it[0] = c } else t }
            return blank.copy(patterns = listOf(p.copy(tracks = tracks)), ticksPerRow = ticks, sound = sound)
        }
        fun errors(s: Song) = SidSystem.errors(s).joinToString("\n")
        assertTrue(errors(withCell(Cell(48, 1, SidRules.FX_TEMPO, 0x86))).contains("channel tempos"))
        assertTrue(errors(withCell(Cell(48, 1, SidRules.FX_VOLUME, 0x10))).contains("D00-D0F"))
        assertTrue(errors(withCell(Cell(48, 1, SidRules.FX_FUNKTEMPO, 0x61))).contains("funktempo digits"))
        assertTrue(errors(withCell(Cell(48, 1, SidRules.FX_PULSE_PROGRAM, 0x05))).contains("has no pulse program"))
        assertTrue(errors(withCell(Cell(48, 1, SidRules.FX_TONE_PORTA, 0x10).copy(note = Cell.EMPTY))).contains("needs a note"))
        assertTrue(SidSystem.structuralErrors(withCell(Cell(93, 1))).joinToString().contains("C-0..G#7"))
        assertTrue(errors(withCell(Cell(48, 1), ticks = 2)).contains("outside GoatTracker's 3-127"))
        assertTrue(errors(withCell(Cell(48, 1), ticks = 3, sound = blank.sid.copy(instruments = blank.sid.instruments.toMutableList().also {
            it[0] = it[0].copy(gateTimer = 3)
        }))).contains("gate timer 3 is not below the shortest row (3 ticks)"))
        // 63 distinct 5-row wave programs need 63 × 6 rows.
        val full = SidSound(List(63) { i -> SidInstrument(wave = SidProgram(List(5) { r -> SidRow.wave(0x21, (i + r) % 90) })) })
        assertTrue(errors(withCell(Cell(48, 1), sound = full)).contains("Wave programs need 378 table rows; GoatTracker's wave table has 255"))
        // A program command in a wave program that GoatTracker forbids there.
        val badWave = blank.sid.instruments.toMutableList().also { it[0] = it[0].copy(wave = SidProgram(listOf(SidRow.wave(0x21), SidRow.command(8, 1)))) }
        assertTrue(errors(withCell(Cell(48, 1), sound = blank.sid.copy(instruments = badWave))).contains("cannot run from a wave program"))
    }

    @Test
    fun songDataBeyondThePlayersRegionIsReported() {
        // 60 patterns whose three voices all differ, every row a note with a changing command: 180 voice patterns of
        // ~200 bytes exceed the 43776 bytes after $2500.
        val blank = SidSystem.blank()
        val patterns = List(60) { p ->
            Pattern("P$p", List(3) { c -> List(64) { r -> Cell(12 + (p + c + r) % 80, 1 + r % 2, SidRules.FX_CUTOFF, (p * 7 + c * 3 + r) and 0xFF) } })
        }
        val song = blank.copy(patterns = patterns, orders = patterns.indices.toList())
        val errors = SidSystem.errors(song)
        assertTrue(errors.any { it.startsWith("Song data does not fit") && it.contains("\$D000") }, errors.joinToString("\n"))
    }

    @Test
    fun theDensestPatternStillPacks() {
        // Instrument, command, parameter and note on all 64 rows: exactly 256 packed bytes, GoatTracker's maximum.
        val rows = List(64) { r -> org.thortracker.backend.GtRow(0x60 + r, 1 + r % 2, SidRules.FX_CUTOFF, r) }
        val map = IntArray(64) { it }
        assertEquals(256, SidLowering.packPattern(rows, map).size)
    }

    @Test
    fun patternLoopsCarryTheInstrumentTheOrderIsEnteredWith() {
        val blank = SidSystem.blank()
        fun pattern(cell: Cell) = Pattern("P", List(3) { c -> List(64) { r -> if (c == 0 && r == 4) cell else Cell.BLANK } })
        val song = blank.copy(patterns = listOf(pattern(Cell(48, 3)), pattern(Cell(50))), orders = listOf(0, 1))
        val loop = SidSystem.patternLoop(song, 1)
        assertEquals(listOf(1), loop.orders)
        assertEquals(Cell(50, 3), loop.patterns[1].tracks[0][4])
        assertEquals(song.patterns[0], loop.patterns[0])
        // The whole song writes it into the second order's GoatTracker pattern as well.
        val gt = GtSong.of(song)
        assertEquals(3, gt.patterns[gt.orderlists[0][1]][4].instrument)
        SidSystem.engine.lower(loop, ByteArray(SidPlayerImage.IMAGE_SIZE))
    }

    @Test
    fun demoAndBlankAreClean() {
        for (song in listOf(SidSystem.blank(), SidSystem.demo())) {
            assertEquals(emptyList(), SidSystem.errors(song))
        }
        assertEquals(emptyList(), SidSystem.warnings(SidSystem.demo()))
    }
}
