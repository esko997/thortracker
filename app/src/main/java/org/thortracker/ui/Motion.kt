package org.thortracker.ui

import android.provider.Settings
import android.view.View
import android.view.animation.AnimationUtils

/**
 * Short, event-driven transitions for one view. A transition is only a start stamp: painters ask for its
 * progress while drawing, and only an unfinished one schedules the next frame, so a settled view never
 * redraws by itself. Durations follow the system animator duration scale; scale 0 (Remove animations)
 * shows every end state at once.
 */
internal class Motion(private val view: View) {
    private var scale = 1f
    private var now = 0L
    private var frameRequested = false

    /** Re-reads the system animator duration scale; call when the view attaches or regains the window. */
    fun refreshScale() {
        scale = Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Starts a transition now and returns its stamp, or [OFF] when animations are disabled. */
    fun start(): Long {
        if (scale <= 0f) return OFF
        view.postInvalidateOnAnimation()
        return AnimationUtils.currentAnimationTimeMillis()
    }

    /** Call at the top of every draw so all transitions in a frame share one clock reading. */
    fun beginFrame() {
        now = AnimationUtils.currentAnimationTimeMillis()
        frameRequested = false
    }

    /** Linear progress 0..1 of a transition started at [start] lasting [ms]; requests a frame while below 1. */
    fun progress(start: Long, ms: Int): Float {
        if (start == OFF || scale <= 0f) return 1f
        val t = (now - start) / (ms * scale)
        if (t >= 1f) return 1f
        if (!frameRequested) {
            frameRequested = true
            view.postInvalidateOnAnimation()
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
