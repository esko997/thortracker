package org.thortracker.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.model.Cell
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.gb.HugeRules
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/** Absolute notes are scientific pitch on every system: the same number sounds the same; names follow each tracker. */
class TruePitchTest {
    private fun twelveTet(note: Int) = 440.0 * 2.0.pow((note - 57) / 12.0)
    private fun cents(a: Double, b: Double) = 1200.0 * log2(a / b)

    /** Game Boy pulse: 131072 / (2048 - period). */
    private fun gbHz(note: Int) = HugeRules.pitchHz(note, 0)

    /** NES (NTSC) pulse: CPU 1789773 Hz / (16 * (period + 1)). */
    private fun nesHz(note: Int) =
        1789773.0 / (16.0 * (FamiStudioLowering.NOTE_TABLE[NesRules.engineNote(NesExpansion.NONE, 0, note)] + 1))

    /** EPSM SSG square (YMF288 at 8 MHz): 8 MHz / 64 / tone period, the period from FamiStudio's SSG table. */
    private fun ssgHz(note: Int) =
        8e6 / 64.0 / FamiStudioLowering.NOTE_TABLE_EPSM_SSG[NesRules.engineNote(NesExpansion.EPSM, NesRules.EPSM_SSG1, note)]

    /**
     * EPSM FM channel: FamiStudio's engine halves the table value below `$200` to find the block B, then writes F-number
     * F = value × 4 (11 bits); the OPN sounds F × (8 MHz / 144) / 2^(21 − B).
     */
    private fun fmHz(note: Int): Double {
        var v = FamiStudioLowering.NOTE_TABLE_EPSM_FM[NesRules.engineNote(NesExpansion.EPSM, NesRules.EPSM_FM1, note)]
        var block = 0
        while (v >= 0x200) { v = v shr 1; block++ }
        return v * 4 * (8e6 / 144.0) / 2.0.pow(21 - block)
    }

    @Test fun a4IsFiftySevenAnd440HzOnBothSystems() {
        assertTrue(57 in GbSystem.noteRange && 57 in NesSystem.noteRange)
        assertTrue(abs(cents(gbHz(57), 440.0)) < 10.0)
        assertTrue(abs(cents(nesHz(57), 440.0)) < 10.0)
    }

    @Test fun theSameNoteSoundsTheSameOnGameBoyAndNes() {
        val shared = maxOf(GbSystem.noteRange.first, NesSystem.noteRange.first, NesRules.LOWEST_TONAL)..minOf(GbSystem.noteRange.last, NesSystem.noteRange.last)
        assertTrue(shared.count() >= 60)
        for (note in shared) {
            // Both 11-bit tables round to whole periods: within a few cents below C5, coarser above (the Game Boy's top
            // octave is off by up to ~45 cents); never an octave apart.
            val tolerance = when { note < 72 -> 8.0; note < 84 -> 30.0; else -> 50.0 }
            assertTrue("note $note: GB ${gbHz(note)} Hz vs NES ${nesHz(note)} Hz", abs(cents(gbHz(note), nesHz(note))) < tolerance)
            assertTrue("note $note: GB vs 12-TET", abs(cents(gbHz(note), twelveTet(note))) < tolerance)
        }
    }

    @Test fun epsmSsgAndFmSoundTheirTrueNote() {
        // FamiStudio's EPSM tables sound its note n an octave below its name: true pitch takes n 12 higher.
        assertEquals(58, NesRules.engineNote(NesExpansion.EPSM, NesRules.EPSM_SSG1, 57))
        assertEquals(58, NesRules.engineNote(NesExpansion.EPSM, NesRules.EPSM_FM1 + 5, 57))
        assertEquals(46, NesRules.engineNote(NesExpansion.EPSM, NesRules.EPSM_RHYTHM1, 57)) // drums: pitch ignored, no shift
        assertEquals(46, NesRules.engineNote(NesExpansion.EPSM, 0, 57)) // the 2A03 stays as it was
        assertEquals(12, NesRules.engineNoteShift(NesExpansion.EPSM, NesRules.EPSM_FM1))
        assertEquals(0, NesRules.engineNoteShift(NesExpansion.VRC6, NesRules.VRC6_SAW))
        assertTrue(abs(cents(ssgHz(57), 440.0)) < 1.0) // 440.1 Hz
        assertTrue(abs(cents(fmHz(57), 440.0)) < 5.0) // 439.1 Hz
        // C-1..B-7 on both (B-8 would need FamiStudio's note 108).
        for (ch in listOf(NesRules.EPSM_SSG1, NesRules.EPSM_FM1)) {
            assertEquals(12, NesRules.lowestNote(NesExpansion.EPSM, ch))
            assertEquals(95, NesRules.highestNote(NesExpansion.EPSM, ch))
        }
        assertEquals(107, NesRules.highestNote(NesExpansion.EPSM, NesRules.EPSM_RHYTHM1))
        for (note in 12..95) {
            // SSG periods round to whole steps (coarse at the top: 25 cents at B-7); FM frequency numbers to 9 bits.
            assertTrue("SSG note $note: ${ssgHz(note)} Hz", abs(cents(ssgHz(note), twelveTet(note))) < 30.0)
            assertTrue("FM note $note: ${fmHz(note)} Hz", abs(cents(fmHz(note), twelveTet(note))) < 10.0)
        }
    }

    @Test fun namesFollowEachTracker() {
        // hUGETracker: its C-3 is C2 (65.4 Hz), A-5 is A4.
        assertEquals("C-3", GbSystem.noteName(24))
        assertEquals("A-5", GbSystem.noteName(57))
        assertEquals("B-8", GbSystem.noteName(GbSystem.noteRange.last))
        // FamiTracker / FamiStudio: scientific, A-4 = 440 Hz.
        assertEquals("A-4", NesSystem.noteName(57))
        assertEquals("C-1", NesSystem.noteName(NesSystem.noteRange.first))
        assertEquals("B-8", NesSystem.noteName(NesSystem.noteRange.last))
        for (s in listOf<SoundSystem>(GbSystem, NesSystem)) {
            assertEquals("---", s.noteName(Cell.EMPTY))
            assertEquals("OFF", s.noteName(Cell.CUT))
        }
    }
}
