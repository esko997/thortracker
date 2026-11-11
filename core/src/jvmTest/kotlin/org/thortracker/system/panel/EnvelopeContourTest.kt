package org.thortracker.system.panel

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope

class EnvelopeContourTest {
    @Test fun customProgramsAndHeldTimeAreNeverNormalized() {
        val custom = NesEnvelope(listOf(15, 3, 12, 2), loop = 1, release = 3)
        assertNull(EnvelopeContour.read(custom))
        assertSame(custom, EnvelopeContour.duration(custom, 32))
        val empty = NesEnvelope()
        assertSame(empty, EnvelopeContour.choose(empty, EnvelopeContour.Shape.HELD))
        assertSame(empty, EnvelopeContour.duration(empty, 64))
    }

    @Test fun eachContourRetainsItsPeakAndHasValidLoopReleaseBounds() {
        for (shape in EnvelopeContour.Shape.entries) for (ticks in EnvelopeContour.lengths) for (peak in listOf(1, 7, 15)) {
            val envelope = EnvelopeContour.make(shape, ticks, peak)
            val read = EnvelopeContour.read(envelope)!!
            assertEquals(shape, read.shape)
            assertEquals(peak, read.peak)
            assertEquals(peak, envelope.values.maxOrNull())
            assertTrue(envelope.values.all { it in 0..peak })
            assertTrue(envelope.loop == -1 || envelope.loop in envelope.values.indices)
            assertTrue(envelope.release == -1 || envelope.release in (envelope.loop + 1)..envelope.values.lastIndex)
            assertSame(envelope, EnvelopeContour.choose(envelope, shape))
        }
    }

    @Test fun changingTimePreservesShapePeakAndReleaseBehavior() {
        val initial = EnvelopeContour.make(EnvelopeContour.Shape.SWELL, 8, 9)
        val longer = EnvelopeContour.duration(initial, 32)
        assertEquals(EnvelopeContour.Recognized(EnvelopeContour.Shape.SWELL, 32, 9), EnvelopeContour.read(longer))
        assertEquals(0, longer.values[longer.release])
        assertEquals(9, longer.values[longer.loop])
    }

    @Test fun explicitRecipeAdoptsCustomVolumeOnlyAtItsCurrentPeak() {
        val custom = NesEnvelope(listOf(3, 9, 2, 8), loop = 2)
        val pluck = EnvelopeContour.choose(custom, EnvelopeContour.Shape.PLUCK)
        assertEquals(EnvelopeContour.Recognized(EnvelopeContour.Shape.PLUCK, 16, 9), EnvelopeContour.read(pluck))
        assertEquals(-1, pluck.loop)
        assertEquals(-1, pluck.release)
    }

    @Test fun invalidProgramAndOutOfRangeLevelsRemainCustom() {
        assertNull(EnvelopeContour.read(NesEnvelope(listOf(16, 0))))
        assertNull(EnvelopeContour.read(NesEnvelope(emptyList(), loop = 0)))
        assertNull(EnvelopeContour.read(NesEnvelope(listOf(15, 7, 0), loop = 1)))
    }

    @Test fun paneSnapshotsDoNotShareModeOrPointerOwnership() {
        val original = ViewState()
        val panel = original.soundPanel("nes")
        panel.advanced = true
        var canceled = false
        panel.cancelTouch = { canceled = true }
        val copy = original.snapshot()
        copy.soundPanel("nes").advanced = false
        copy.soundPanel("nes").invalidate()
        assertTrue(original.soundPanel("nes").advanced)
        assertFalse(canceled)
        copy.soundPanel("ay").soundField = 4
        assertFalse(original.chipPanels.containsKey("ay"))
        original.soundPanel("nes").invalidate()
        assertTrue(canceled)
    }
}
