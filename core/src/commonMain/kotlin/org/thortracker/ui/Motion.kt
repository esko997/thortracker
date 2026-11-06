package org.thortracker.ui

/**
 * Short, event-driven transitions for one view. A transition is only a start stamp: painters ask for its
 * progress while drawing, and only an unfinished one schedules the next frame, so a settled view never
 * redraws by itself. [scale] is the platform's animation duration scale; 0 (animations off) shows every end
 * state at once. [clock] reads milliseconds; [requestFrame] asks the platform to draw again soon.
 */
class Motion(private val clock: () -> Long, private val requestFrame: () -> Unit) {
    var scale = 1f
    private var now = 0L
    private var frameRequested = false

    /** Starts a transition now and returns its stamp, or [OFF] when animations are disabled. */
    fun start(): Long {
        if (scale <= 0f) return OFF
        requestFrame()
        return clock()
    }

    /** Call at the top of every draw so all transitions in a frame share one clock reading. */
    fun beginFrame() {
        now = clock()
        frameRequested = false
    }

    /** Linear progress 0..1 of a transition started at [start] lasting [ms]; requests a frame while below 1. */
    fun progress(start: Long, ms: Int): Float {
        if (start == OFF || scale <= 0f) return 1f
        val t = (now - start) / (ms * scale)
        if (t >= 1f) return 1f
        if (!frameRequested) {
            frameRequested = true
            requestFrame()
        }
        return maxOf(0f, t)
    }

    /** Remaining strength 1..0 of a fading accent (ease-out), for the same arguments as [progress]. */
    fun fade(start: Long, ms: Int): Float {
        val u = 1f - progress(start, ms)
        return u * u
    }

    companion object {
        /** A stamp that is always finished. */
        const val OFF = Long.MIN_VALUE
    }
}
