package org.thortracker.input

/**
 * Recognises a quick double flick of one analog stick axis: push past [ENGAGE], come back inside [NEUTRAL] within
 * [FLICK_MILLIS], then push the same way again within [GAP_MILLIS] of returning. The second push completes it at
 * once. Positions between the two thresholds keep the current state (hysteresis), so a stick resting near the
 * edge of its dead zone cannot chatter; one that crosses the centre has passed neutral.
 *
 * Only transitions count, so a held push never repeats. A push longer than a flick, a push the other way, a
 * clock running backwards and [reset] forget the first flick. A push already under way at [reset] (or that
 * completed a double flick) cannot start a flick; the stick must come back to neutral first. Times are
 * monotonic milliseconds; positions run -1..1.
 */
class StickFlicks {
    /** Where the stick is now: -1, 0 (neutral) or 1. */
    private var direction = 0
    private var pushedAt = 0L
    /** The current push may not be a flick: it began before a [reset] or completed a double flick. */
    private var stale = false
    /** Direction of a completed first flick waiting for its second, 0 when none. */
    private var flicked = 0
    private var flickedAt = 0L
    private var last = Long.MIN_VALUE

    /** Feeds the stick's [position] at [millis]; returns the direction of a completed double flick, otherwise 0. */
    fun sample(position: Float, millis: Long): Int {
        if (millis < last) reset()
        last = millis
        val now = when {
            position >= ENGAGE -> 1
            position <= -ENGAGE -> -1
            position > -NEUTRAL && position < NEUTRAL -> 0
            // Between the thresholds: still pushed if on the pushed side, neutral if it crossed the centre.
            (position > 0f) == (direction > 0) && direction != 0 -> direction
            else -> 0
        }
        if (now == direction) return 0
        val ended = direction
        direction = now
        if (ended != 0) {
            flicked = if (!stale && millis - pushedAt <= FLICK_MILLIS) ended else 0
            flickedAt = millis
        }
        if (now == 0) return 0
        pushedAt = millis
        val double = flicked == now && millis - flickedAt <= GAP_MILLIS
        flicked = 0
        stale = double
        return if (double) now else 0
    }

    /** Forgets a pending first flick; a push under way must return to neutral before it counts. */
    fun reset() {
        flicked = 0
        stale = true
    }

    companion object {
        /** Past this (of full travel) the stick is pushed. */
        const val ENGAGE = 0.6f
        /** Inside this it is back at neutral. */
        const val NEUTRAL = 0.3f
        /** Longest push that still counts as a flick. */
        const val FLICK_MILLIS = 300L
        /** Longest wait at neutral between the two flicks. */
        const val GAP_MILLIS = 300L
    }
}
