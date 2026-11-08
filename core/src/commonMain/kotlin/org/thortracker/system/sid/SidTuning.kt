package org.thortracker.system.sid

import org.thortracker.backend.GtSong
import org.thortracker.model.Song
import org.thortracker.model.Tuning
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Tunings on the SID: the player has no per-note fine pitch, so a song's [Tuning] retunes its 96-entry frequency table
 * (as GoatTracker's base-pitch and Scala tuning options do). Note n plays the tuning degree nearest to it on the 12-note
 * grid ([Tuning.nearestDegree]; 1/1 on the tuning's root), exactly: GoatTracker's register value for n scaled by the
 * degree's deviation from n. Without a tuning (or with 12-equal) the table is GoatTracker's own.
 */
object SidTuning {
    /** The frequency register value (16 bits) of notes 0..95 for [song]. */
    fun frequencies(song: Song): IntArray = frequencies(song.tuning)

    fun frequencies(tuning: Tuning?): IntArray = IntArray(96) { n ->
        val plain = GtSong.DEFAULT_FREQ_LO[n] or (GtSong.DEFAULT_FREQ_HI[n] shl 8)
        if (tuning == null) plain
        else {
            val offset = 100.0 * (n - tuning.root)
            val deviation = tuning.degreeCents(tuning.nearestDegree(offset)) - offset
            (plain * 2.0.pow(deviation / 1200.0)).roundToInt().coerceIn(1, 0xFFFF)
        }
    }
}
