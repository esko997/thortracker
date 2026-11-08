package org.thortracker.system.fm

import org.thortracker.model.Song

/** Controller pieces the FM instrument editors (OPL3, YM2612) share. */
object FmControls {
    /** Coarse steps (↑↓ while editing, Clear+direction on lists). */
    const val COARSE = 8

    /** Longest sound name. */
    const val NAME_MAX = 16

    /** The channel whose arranged rows use sound [slot] most, or null when none does. */
    fun mostUsedChannel(song: Song, slot: Int): Int? {
        val counts = IntArray(song.system.channels.size)
        for (pat in song.orders.toSet()) {
            val tracks = song.patterns[pat].tracks
            for (ch in counts.indices) for (cell in tracks[ch]) if (cell.instrument == slot) counts[ch]++
        }
        val best = counts.indices.maxBy { counts[it] }
        return if (counts[best] > 0) best else null
    }

    /**
     * A drum sweep's speed after its start or end changed: a sweep without speed gets a moderate one (16 = one
     * semitone per tick) so the new range is heard.
     */
    fun sweepSpeed(speed: Int, start: Int, end: Int): Int = if (speed == 0 && start != end) 16 else speed

    /**
     * The preset [delta] steps from [current] (its index, -1 when the sound is none of them) among [count]: the first
     * (or last, stepping back) when it is none.
     */
    fun presetStep(current: Int, delta: Int, count: Int): Int =
        if (current < 0) (if (delta < 0) count - 1 else 0) else (current + delta).mod(count)
}
