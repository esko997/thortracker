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
import org.thortracker.system.vsu.VsuPitch
import org.thortracker.system.vsu.VsuRules
import org.thortracker.system.vsu.VsuSystem
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

    @Test fun virtualBoyWaveLanesPlayTruePitch() {
        // FamiTracker-style names; A-4 = 57 is F 1693, 440.14 Hz.
        assertEquals("A-4", VsuSystem.noteName(57))
        assertEquals("D#2", VsuSystem.noteName(VsuRules.WAVE_NOTES.first))
        assertTrue(abs(cents(VsuPitch.hz(0, 64 * 57), 440.0)) < 1.0)
        // The 11-bit period rounds: within a few cents low down, at worst about -20 cents at B-7 (the specification's
        // -19.7; the 25-cent bound is a first guess, not measured); never a semitone off, on every wave lane alike.
        for (note in VsuRules.WAVE_NOTES) {
            val hz = VsuPitch.hz(0, 64 * note)
            assertTrue("vb-vsu note $note: $hz Hz", abs(cents(hz, twelveTet(note))) < 25.0)
            for (lane in 1..4) assertEquals(hz, VsuPitch.hz(lane, 64 * note), 0.0)
        }
    }
}
