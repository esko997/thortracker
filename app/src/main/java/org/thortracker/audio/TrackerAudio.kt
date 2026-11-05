package org.thortracker.audio

import org.thortracker.model.Song

/** Row the listener hears now (output latency compensated while playing). */
data class PlaybackPosition(val playing: Boolean = false, val order: Int = 0, val row: Int = 0)

/**
 * Backend playback boundary. Every method returns immediately and never throws for song or
 * device problems; work runs on the implementation's own thread. Failures are reported by
 * [error] so the host can show them while editing continues.
 */
interface TrackerAudio : java.io.Closeable {
    /** New document state; upcoming events use it without restarting the transport. */
    fun update(song: Song)

    /** Starts playback so the next row heard is ([order], [row]) with reconstructed state. */
    fun play(order: Int = 0, row: Int = 0)

    fun stop()

    /** Sounds [note] (0..71) with typed [instrument] (1..15) on [channel] until [releaseAudition]. */
    fun audition(channel: Int, note: Int, instrument: Int)

    fun releaseAudition()

    fun position(): PlaybackPosition

    /** Actionable description of the latest failure (invalid song, device, command), or null. */
    fun error(): String?
}
