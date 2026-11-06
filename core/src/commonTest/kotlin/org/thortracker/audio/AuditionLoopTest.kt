package org.thortracker.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AuditionLoopTest {
    @Test fun stepsPlayOnTimeLoopSkipRestsAndNeverBurstAfterAStall() {
        val loop = AuditionLoop()
        val played = ArrayList<Pair<Long, Int>>()
        loop.start(channel = 1, steps = listOf(40, null, 43), instrument = 2, stepMillis = 100, now = 1000)
        fun at(now: Long) = loop.advance(now) { ch, note, ins -> assertEquals(1 to 2, ch to ins); played += now to note }
        assertEquals(1100L, at(1000))   // first step at once
        assertEquals(1100L, at(1050))   // not due yet: nothing plays
        assertEquals(1200L, at(1100))   // a rest: the previous note keeps sounding
        assertEquals(1300L, at(1200))
        assertEquals(1400L, at(1300))   // looped back to the first step
        assertEquals(2600L, at(2500))   // a 1.2 s stall plays one step (the rest) and resumes from now
        assertEquals(2700L, at(2600))
        assertEquals(listOf(1000L to 40, 1200L to 43, 1300L to 40, 2600L to 43), played)
        loop.stop()
        assertFalse(loop.active)
        assertNull(at(3000))
    }
}
