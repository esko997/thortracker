package org.thortracker.audio

import org.thortracker.model.Song

/**
 * Row the listener hears now (output latency compensated while playing). [looping] marks the editor's pattern loop:
 * [order] is then the looped order, which is the only one heard.
 */
data class PlaybackPosition(val playing: Boolean = false, val order: Int = 0, val row: Int = 0, val looping: Boolean = false)

/**
 * Backend playback boundary. Every method returns immediately and never throws for song or
 * device problems; work runs on the implementation's own thread. Failures are reported by
 * [error] so the host can show them while editing continues.
 */
interface TrackerAudio : AutoCloseable {
    /** New document state; upcoming events use it without restarting the transport. */
    fun update(song: Song)

    /** Starts playback so the next row heard is ([order], [row]) with reconstructed state. */
    fun play(order: Int = 0, row: Int = 0)

    fun stop()

    /**
     * Sounds [note] with [instrument] (a slot of the channel's bank) on [channel] until [releaseAudition].
     * [note] is the engine's own number ([org.thortracker.system.SoundSystem.engineNote]; Game Boy: hUGEDriver's
     * 0..71 = C-3..B-8). [detune] > 0 plays the note with the system's fine pitch (Game Boy: effect 40y, the table
     * period for the first driver tick, then raised by [detune] period units). Noise channels ignore it.
     */
    fun audition(channel: Int, note: Int, instrument: Int, detune: Int = 0)

    fun releaseAudition()

    fun position(): PlaybackPosition

    /** Actionable description of the latest failure (invalid song, device, command), or null. */
    fun error(): String?
}
