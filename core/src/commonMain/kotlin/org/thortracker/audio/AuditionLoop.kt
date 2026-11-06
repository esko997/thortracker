package org.thortracker.audio

/**
 * Timing for a looping audition ([org.thortracker.editor.EditorSession.auditionSequence]: Sketch's held phrase or
 * chord arpeggio). A frontend calls [advance] when the next step is due, or simply every frame; each step that falls
 * due is played through `play`. A null step starts nothing, so the previous note keeps sounding.
 */
class AuditionLoop {
    private var channel = 0
    private var steps: List<Int?> = emptyList()
    private var instrument = 0
    private var stepMillis = 0L
    private var index = 0
    private var due = 0L

    val active: Boolean get() = steps.isNotEmpty()

    /** Starts looping [steps] from the first, which plays at the next [advance]. */
    fun start(channel: Int, steps: List<Int?>, instrument: Int, stepMillis: Long, now: Long) {
        this.channel = channel
        this.steps = steps
        this.instrument = instrument
        this.stepMillis = maxOf(1L, stepMillis)
        index = 0
        due = now
    }

    fun stop() { steps = emptyList() }

    /**
     * Plays the step due at [now], if any, and returns when the next one is due (null when stopped). Steps keep their
     * spacing from the start instead of accumulating scheduling delays; after a stall the loop resumes from now
     * rather than catching up in a burst.
     */
    fun advance(now: Long, play: (channel: Int, note: Int, instrument: Int) -> Unit): Long? {
        if (steps.isEmpty()) return null
        if (now < due) return due
        steps[index]?.let { play(channel, it, instrument) }
        index = (index + 1) % steps.size
        due = if (due + stepMillis < now) now + stepMillis else due + stepMillis
        return due
    }
}
