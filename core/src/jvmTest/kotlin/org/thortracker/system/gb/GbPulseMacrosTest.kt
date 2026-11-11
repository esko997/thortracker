package org.thortracker.system.gb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GbPulseMacrosTest {
    private val advanced = GbInstrument(
        name = "My patch", duty = 3, volume = 9, envelopeDirection = 1, envelopePace = 5,
        length = 17, lengthEnabled = true, sweepTime = 6, sweepDirection = 1, sweepShift = 3,
        wave = 11, waveVolume = 3, noiseWidth = 1,
    )

    private fun choose(control: GbPulseControl, index: Int, source: GbInstrument = advanced): GbInstrument =
        GbPulseMacros.apply(control, source, index)

    private fun shape(index: Int): GbInstrument = choose(GbPulseControl.SHAPE, index)

    private fun assertOnlyEnvelopeChanged(before: GbInstrument, after: GbInstrument) {
        assertEquals(before.copy(
            volume = after.volume, envelopeDirection = after.envelopeDirection, envelopePace = after.envelopePace,
            length = after.length, lengthEnabled = after.lengthEnabled,
        ), after)
    }

    @Test
    fun characterChangesDutyOnlyAndPreservesTheEquivalentSeventyFivePercentPatch() {
        assertEquals(1, GbPulseMacros.value(GbPulseControl.CHARACTER, advanced))
        assertSame(advanced, choose(GbPulseControl.CHARACTER, 1))
        assertEquals(advanced.copy(duty = 0), choose(GbPulseControl.CHARACTER, 0))
        assertEquals(advanced.copy(duty = 2), choose(GbPulseControl.CHARACTER, 2))
        val square = advanced.copy(duty = 2)
        assertEquals(square.copy(duty = 1), choose(GbPulseControl.CHARACTER, 1, square))
    }

    @Test
    fun shapeAndLengthCannotOverwriteNameDutySweepOrOtherBanksFields() {
        for (control in arrayOf(GbPulseControl.SHAPE, GbPulseControl.LENGTH)) {
            for (rank in 0 until GbPulseMacros.steps(control)) {
                val changed = choose(control, rank)
                assertOnlyEnvelopeChanged(advanced, changed)
                assertEquals(rank, GbPulseMacros.value(control, changed))
            }
        }
        for (shape in 0..2) {
            val initial = shape(shape)
            for (rank in 0..3) {
                val changed = choose(GbPulseControl.LENGTH, rank, initial)
                assertOnlyEnvelopeChanged(initial, changed)
                assertEquals(initial.volume, changed.volume)
                assertEquals(initial.envelopeDirection, changed.envelopeDirection)
                assertEquals(shape, GbPulseMacros.value(GbPulseControl.SHAPE, changed))
                assertEquals(rank, GbPulseMacros.value(GbPulseControl.LENGTH, changed))
            }
        }
    }

    @Test
    fun selectingCurrentValuesPreservesAllExactAdvancedBytes() {
        for (shape in 0..2) for (rank in 0..3) {
            val canonical = choose(GbPulseControl.LENGTH, rank, shape(shape))
            val original = canonical.copy(
                duty = 3,
                length = if (canonical.lengthEnabled) canonical.length else 57,
                envelopeDirection = if (canonical.envelopePace == 0) 1 else canonical.envelopeDirection,
                sweepTime = 0, sweepDirection = 0, sweepShift = 5,
            )
            for (control in GbPulseControl.entries) {
                val value = GbPulseMacros.value(control, original)!!
                assertSame(original, choose(control, value, original))
            }
        }
        val heldCustomGate = shape(1).copy(length = 43, envelopeDirection = 1)
        assertEquals(1, GbPulseMacros.value(GbPulseControl.SHAPE, heldCustomGate))
        assertNull(GbPulseMacros.value(GbPulseControl.LENGTH, heldCustomGate))
        assertSame(heldCustomGate, choose(GbPulseControl.SHAPE, 1, heldCustomGate))
    }

    @Test
    fun customPatchesAreNotInventedExactSettingsAndReadingNeverChangesThem() {
        val snapshot = advanced.copy()
        repeat(3) {
            for (control in GbPulseControl.entries) {
                GbPulseMacros.value(control, advanced)
                GbPulseMacros.describe(control, advanced)
                GbPulseMacros.help(control)
            }
        }
        assertEquals(snapshot, advanced)
        assertNull(GbPulseMacros.value(GbPulseControl.SHAPE, advanced))
        assertNull(GbPulseMacros.value(GbPulseControl.LENGTH, advanced))
        assertNull(GbPulseMacros.value(GbPulseControl.MOTION, advanced))
        val pluck = shape(0)
        for (custom in listOf(
            pluck.copy(volume = 0), pluck.copy(volume = 11),
            pluck.copy(lengthEnabled = true), pluck.copy(envelopeDirection = 1),
        )) {
            assertNull(GbPulseMacros.value(GbPulseControl.SHAPE, custom))
            assertNull(GbPulseMacros.value(GbPulseControl.LENGTH, custom))
        }
        // A real pluck with an unrepresented pace is still a pluck, but not one of the four exact Length choices.
        for (pace in intArrayOf(3, 5, 6)) {
            val customTime = pluck.copy(envelopePace = pace)
            assertEquals(0, GbPulseMacros.value(GbPulseControl.SHAPE, customTime))
            assertNull(GbPulseMacros.value(GbPulseControl.LENGTH, customTime))
            assertSame(customTime, choose(GbPulseControl.SHAPE, 0, customTime))
            assertEquals(2, choose(GbPulseControl.SHAPE, 2, customTime).envelopePace)
        }
        val deliberate = choose(GbPulseControl.LENGTH, 2)
        assertEquals(0, GbPulseMacros.value(GbPulseControl.SHAPE, deliberate))
        assertEquals(4, deliberate.envelopePace)
        assertFalse(deliberate.lengthEnabled)
        assertEquals(advanced.length, deliberate.length) // preserve the now-inactive gate byte
    }

    @Test
    fun pluckActuallyRingsLongerAndSwellTakesLongerWithoutPretendingToFadeBackOut() {
        for (shape in intArrayOf(0, 2)) {
            var previous = 0.0
            for ((rank, pace) in intArrayOf(1, 2, 4, 7).withIndex()) {
                val sound = choose(GbPulseControl.LENGTH, rank, shape(shape))
                assertNull(SoundTimeline.cutSeconds(sound))
                assertFalse(SoundTimeline.dacOff(sound))
                val end = SoundTimeline.envelopeEndSeconds(sound)!!
                assertEquals(12 * pace / 64.0, end, 0.0)
                assertTrue(end > previous)
                assertEquals(if (shape == 0) 1 else 14, SoundTimeline.level(sound, end - pace / 128.0))
                assertEquals(if (shape == 0) 0 else 15, SoundTimeline.level(sound, end))
                assertEquals(if (shape == 0) 0 else 15, SoundTimeline.level(sound, end + 1.0))
                previous = end
            }
        }
        val short = choose(GbPulseControl.LENGTH, 0, shape(0))
        val long = choose(GbPulseControl.LENGTH, 3, short)
        assertEquals(0, SoundTimeline.level(short, 0.5))
        assertTrue(SoundTimeline.level(long, 0.5) > 0)
    }

    @Test
    fun heldLengthGrowsToTheHardwareLimitThenOffersAnUncutTone() {
        var previous = 0.0
        for (rank in 0..3) {
            val held = choose(GbPulseControl.LENGTH, rank, shape(1))
            assertNull(SoundTimeline.envelopeEndSeconds(held))
            assertEquals(12, SoundTimeline.level(held, 2.0))
            if (rank < 3) {
                val duration = SoundTimeline.cutSeconds(held)!!
                assertEquals(doubleArrayOf(0.0625, 0.125, 0.25)[rank], duration, 0.0)
                assertTrue(duration > previous)
                previous = duration
            } else {
                assertNull(SoundTimeline.cutSeconds(held))
            }
        }
    }

    @Test
    fun motionChangesOnlySweepAndFollowsHardwarePitchDirection() {
        for (rank in 0..2) {
            val sound = choose(GbPulseControl.MOTION, rank)
            assertEquals(advanced.copy(sweepTime = sound.sweepTime,
                sweepDirection = sound.sweepDirection, sweepShift = sound.sweepShift), sound)
            assertEquals(rank, GbPulseMacros.value(GbPulseControl.MOTION, sound))
            if (rank > 0) {
                assertEquals(4 / 128.0, SoundTimeline.sweepClockSeconds(sound)!!, 0.0)
                val next = SoundTimeline.sweepClock(sound, 1046)
                assertTrue(next >= 0)
                val movement = SoundTimeline.semitones(1046, next)
                assertTrue(if (rank == 1) movement < 0 else movement > 0)
            }
        }
        assertTrue(SoundTimeline.sweepSilencesAtTrigger(choose(GbPulseControl.MOTION, 2), 2040))
        assertFalse(SoundTimeline.sweepSilencesAtTrigger(choose(GbPulseControl.MOTION, 1), 2040))
    }

    @Test
    fun stillRecognizesSafeInactiveBitsButNeverHidesOverflowProneSweepSettings() {
        for (still in listOf(
            advanced.copy(sweepTime = 0, sweepShift = 0),
            advanced.copy(sweepTime = 0, sweepDirection = 0),
            advanced.copy(sweepTime = 7, sweepShift = 0, sweepDirection = 0),
        )) {
            assertEquals(0, GbPulseMacros.value(GbPulseControl.MOTION, still))
            assertSame(still, choose(GbPulseControl.MOTION, 0, still))
        }
        for (unsafe in listOf(
            advanced.copy(sweepTime = 0, sweepDirection = 1, sweepShift = 1),
            advanced.copy(sweepTime = 2, sweepDirection = 1, sweepShift = 0),
        )) {
            assertNull(GbPulseMacros.value(GbPulseControl.MOTION, unsafe))
            val still = choose(GbPulseControl.MOTION, 0, unsafe)
            assertNull(SoundTimeline.sweepOffSeconds(still, HugeRules.NOTE_OFFSET + 60))
            assertEquals(unsafe.sweepDirection, still.sweepDirection)
        }
    }

    @Test
    fun shapeAndLengthCooperatePredictablyInEitherEditOrder() {
        val start = choose(GbPulseControl.LENGTH, 0, shape(0))
        for (shape in 0..2) for (rank in 0..3) {
            val shapeThenLength = choose(GbPulseControl.LENGTH, rank, choose(GbPulseControl.SHAPE, shape, start))
            val lengthThenShape = choose(GbPulseControl.SHAPE, shape, choose(GbPulseControl.LENGTH, rank, start))
            assertEquals(shape, GbPulseMacros.value(GbPulseControl.SHAPE, shapeThenLength))
            assertEquals(rank, GbPulseMacros.value(GbPulseControl.LENGTH, shapeThenLength))
            // Disabled gate bytes deliberately retain the last gate used, rather than normalizing hidden state.
            assertEquals(shapeThenLength.copy(length = lengthThenShape.length), lengthThenShape)
            if (shapeThenLength.lengthEnabled) assertEquals(shapeThenLength.length, lengthThenShape.length)
        }
        for (shape in 0..2) for (rank in 0..3) {
            val lengthFirst = choose(GbPulseControl.LENGTH, rank, advanced)
            val final = choose(GbPulseControl.SHAPE, shape, lengthFirst)
            assertEquals(shape, GbPulseMacros.value(GbPulseControl.SHAPE, final))
            assertEquals(rank, GbPulseMacros.value(GbPulseControl.LENGTH, final))
            assertOnlyEnvelopeChanged(advanced, final)
        }
        // Independent timbre and motion edits commute with the envelope controls byte-for-byte.
        for (control in arrayOf(GbPulseControl.SHAPE, GbPulseControl.LENGTH)) {
            for (rank in 0 until GbPulseMacros.steps(control)) {
                assertEquals(
                    choose(GbPulseControl.CHARACTER, 0, choose(control, rank)),
                    choose(control, rank, choose(GbPulseControl.CHARACTER, 0)),
                )
                assertEquals(
                    choose(GbPulseControl.MOTION, 1, choose(control, rank)),
                    choose(control, rank, choose(GbPulseControl.MOTION, 1)),
                )
            }
        }
    }

    @Test
    fun resetsUseOnlyTheirOwnFieldsAndMatchingResetsAreExactNoOps() {
        assertEquals(advanced.copy(duty = 2), GbPulseMacros.reset(GbPulseControl.CHARACTER, advanced))
        assertEquals(advanced.copy(sweepTime = 0, sweepShift = 0), GbPulseMacros.reset(GbPulseControl.MOTION, advanced))
        val longSwell = choose(GbPulseControl.LENGTH, 3, shape(2))
        val resetShape = GbPulseMacros.reset(GbPulseControl.SHAPE, longSwell)
        assertEquals(0, GbPulseMacros.value(GbPulseControl.SHAPE, resetShape))
        assertEquals(3, GbPulseMacros.value(GbPulseControl.LENGTH, resetShape))
        assertOnlyEnvelopeChanged(longSwell, resetShape)
        val resetLength = GbPulseMacros.reset(GbPulseControl.LENGTH, longSwell)
        assertEquals(2, GbPulseMacros.value(GbPulseControl.SHAPE, resetLength))
        assertEquals(1, GbPulseMacros.value(GbPulseControl.LENGTH, resetLength))
        assertOnlyEnvelopeChanged(longSwell, resetLength)
        for (control in GbPulseControl.entries) {
            val once = GbPulseMacros.reset(control, advanced)
            assertSame(once, GbPulseMacros.reset(control, once))
        }
    }

    @Test
    fun everyChoiceRoundTripsWithinLegalHardwareRanges() {
        for (shape in 0..2) for (rank in 0..3) for (character in 0..2) for (motion in 0..2) {
            val sound = choose(GbPulseControl.MOTION, motion,
                choose(GbPulseControl.CHARACTER, character, choose(GbPulseControl.LENGTH, rank, shape(shape))))
            assertEquals(character, GbPulseMacros.value(GbPulseControl.CHARACTER, sound))
            assertEquals(shape, GbPulseMacros.value(GbPulseControl.SHAPE, sound))
            assertEquals(rank, GbPulseMacros.value(GbPulseControl.LENGTH, sound))
            assertEquals(motion, GbPulseMacros.value(GbPulseControl.MOTION, sound))
            assertTrue(sound.duty in 0..3)
            assertTrue(sound.volume in 0..15)
            assertTrue(sound.envelopeDirection in 0..1)
            assertTrue(sound.envelopePace in 0..7)
            assertTrue(sound.length in 0..63)
            assertTrue(sound.sweepTime in 0..7)
            assertTrue(sound.sweepDirection in 0..1)
            assertTrue(sound.sweepShift in 0..7)
            assertEquals(advanced.name, sound.name)
            assertEquals(GbBank.PULSE, sound.kind)
        }
    }

    @Test
    fun nonpulsePatchesAreNeverConvertedAndInvalidChoicesAreRejected() {
        for (kind in arrayOf(GbBank.WAVE, GbBank.NOISE)) {
            val source = advanced.copy(kind = kind, length = if (kind == GbBank.WAVE) 231 else 17)
            for (control in GbPulseControl.entries) {
                assertNull(GbPulseMacros.value(control, source))
                assertSame(source, GbPulseMacros.reset(control, source))
                for (rank in 0 until GbPulseMacros.steps(control)) assertSame(source, choose(control, rank, source))
            }
        }
        for (control in GbPulseControl.entries) for (bad in intArrayOf(-1, GbPulseMacros.steps(control))) {
            var rejected = false
            try {
                choose(control, bad)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
        }
    }
}
