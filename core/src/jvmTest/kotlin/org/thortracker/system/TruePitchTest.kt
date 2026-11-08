package org.thortracker.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.FamiStudioLowering
import org.thortracker.model.Cell
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.gb.HugeRules
import org.thortracker.system.nes.NesRules
import org.thortracker.system.nes.NesSystem
import org.thortracker.system.ay.AyRules
import org.thortracker.system.ay.AyVariant
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
    private fun nesHz(note: Int) = 1789773.0 / (16.0 * (FamiStudioLowering.NOTE_TABLE[note - NesRules.NOTE_OFFSET] + 1))

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

    /** AY tone: chip clock / (16 · period), the period from the variant's table for the note's pitch. */
    private fun ayHz(variant: AyVariant, note: Int) = AyRules.toneHz(variant, AyRules.tonePeriod(variant, note * 64))

    @Test fun ayNotesAreTruePitchOnEveryComputer() {
        // A-4 = 57 on each machine within its 12-bit period: ZX 252 (439.84 Hz), MSX 254 (440.40 Hz), ST 285 (439.76 Hz),
        // CPC 142 (440.14 Hz).
        assertEquals(252, AyRules.tonePeriod(AyVariant.ZX128, 57 * 64))
        assertEquals(254, AyRules.tonePeriod(AyVariant.MSX, 57 * 64))
        assertEquals(285, AyRules.tonePeriod(AyVariant.ATARI_ST, 57 * 64))
        assertEquals(142, AyRules.tonePeriod(AyVariant.CPC, 57 * 64))
        // A whole period is at most half a step from the exact one: its error in cents (plus the table's rounding).
        fun bound(period: Int) = 1200.0 * log2((period + 0.5) / period) + 1.0
        for (variant in AyVariant.entries) {
            val system = variant.system
            assertEquals("A-4", system.noteName(57))
            assertEquals("B-7", system.noteName(system.noteRange.last))
            assertTrue(abs(cents(ayHz(variant, 57), 440.0)) < 3.0)
            for (note in system.noteRange) {
                // Whole periods: within a few cents low down, coarser in the top octaves (the CPC's B-7 is period 16).
                val tolerance = bound(AyRules.tonePeriod(variant, note * 64))
                assertTrue("${variant.label} note $note", abs(cents(ayHz(variant, note), twelveTet(note))) < tolerance)
                if (note in NesSystem.noteRange && note >= NesRules.LOWEST_TONAL && note < 84) {
                    // Both chips round to whole periods: their errors add up (never a semitone apart).
                    val nes = FamiStudioLowering.NOTE_TABLE[note - NesRules.NOTE_OFFSET] + 1
                    val pair = tolerance + bound(nes) + 2.0
                    assertTrue("${variant.label} vs NES note $note", abs(cents(ayHz(variant, note), nesHz(note))) < pair)
                }
            }
        }
        // The lowest note whose period fits 12 bits: A-0 on the ZX Spectrum 128 and MSX, B-0 on the Atari ST's faster
        // clock, C-0 on the CPC's 1 MHz.
        assertEquals("A-0", AyVariant.ZX128.system.noteName(AyVariant.ZX128.system.noteRange.first))
        assertEquals("A-0", AyVariant.MSX.system.noteName(AyVariant.MSX.system.noteRange.first))
        assertEquals("B-0", AyVariant.ATARI_ST.system.noteName(AyVariant.ATARI_ST.system.noteRange.first))
        assertEquals("C-0", AyVariant.CPC.system.noteName(AyVariant.CPC.system.noteRange.first))
    }
}
