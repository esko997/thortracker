package org.thortracker.input

import org.junit.Assert.assertEquals
import org.junit.Test

class StickFlicksTest {
    /** Feeds (position, millis) samples; returns every non-zero result in order. */
    private fun StickFlicks.feed(vararg samples: Pair<Float, Number>): List<Int> =
        samples.map { (p, t) -> sample(p, t.toLong()) }.filter { it != 0 }

    @Test fun twoQuickFlicksWithNeutralBetweenCompleteOnTheSecondPush() {
        assertEquals(listOf(-1), StickFlicks().feed(-1f to 0, 0f to 100, -1f to 250))
        assertEquals(listOf(1), StickFlicks().feed(0.2f to 0, 0.9f to 10, 0.1f to 90, 0.8f to 200, 0f to 260))
    }

    @Test fun oneFlickOrAHeldPushNeverCompletes() {
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 0, 0f to 100))
        // A held push reports many samples but only one transition.
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 0, -0.9f to 100, -1f to 200, -0.95f to 900))
        // Held longer than a flick, then flicked: the hold was not the first flick.
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 0, 0f to 500, -1f to 600, 0f to 650))
    }

    @Test fun theStickMustReachNeutralBetweenFlicks() {
        // Easing back into the hysteresis band and pushing again is still one push.
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 0, -0.45f to 80, -1f to 160, 0f to 240))
        // Crossing the centre into the band on the other side does count as passing neutral.
        assertEquals(listOf(-1), StickFlicks().feed(-1f to 0, 0.45f to 80, -1f to 160))
    }

    @Test fun aSlowSecondFlickOrAPushTheOtherWayForgetsTheFirst() {
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 0, 0f to 100, -1f to 100 + StickFlicks.GAP_MILLIS + 1))
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 0, 0f to 80, 1f to 160, 0f to 220, -1f to 300))
    }

    @Test fun aDoubleFlickIsNotTheFirstFlickOfAnother() {
        // Flicks 1+2 complete one double; flick 3 is a fresh first flick and 4 completes the next.
        assertEquals(listOf(1, 1), StickFlicks().feed(1f to 0, 0f to 50, 1f to 100, 0f to 150, 1f to 200, 0f to 250, 1f to 300))
    }

    @Test fun resetForgetsTheFirstFlickAndAPushUnderWayAtReset() {
        val f = StickFlicks()
        assertEquals(emptyList<Int>(), f.feed(-1f to 0, 0f to 80))
        f.reset()
        assertEquals("the flick before the reset cannot pair", emptyList<Int>(), f.feed(-1f to 160, 0f to 220))
        assertEquals(listOf(-1), f.feed(-1f to 300))

        val held = StickFlicks()
        held.feed(-1f to 0)
        held.reset()
        assertEquals("a push held through the reset is not a flick", emptyList<Int>(), held.feed(0f to 50, -1f to 100))
    }

    @Test fun aClockRunningBackwardsForgetsTheFirstFlick() {
        assertEquals(emptyList<Int>(), StickFlicks().feed(-1f to 1000, 0f to 1050, -1f to 900))
    }
}
