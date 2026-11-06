@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.*
import kotlinx.cinterop.*
import org.thortracker.audio.HugeImageLayout
import org.thortracker.audio.PlaybackPosition
import org.thortracker.audio.TrackerAudio
import org.thortracker.backend.HugeLowering
import org.thortracker.model.Song

/**
 * [TrackerAudio] over the SDL3 engine glue (native/deck_audio.c), so the shared [org.thortracker.audio.Transport]
 * drives the Deck exactly as it drives Android's HugeAudio. Main thread only.
 */
class DeckAudio : TrackerAudio {
    private var error: String? = null

    init { check(da_open() == 0) { "SDL audio could not open" } }

    /** Lowers the song and hands its image to the engine (applied live while playing); a bad song keeps the last one. */
    override fun update(song: Song) {
        error = try {
            val image = ByteArray(HugeImageLayout.SIZE)
            HugeImageLayout.build(HugeLowering.lower(song), image)
            image.usePinned { da_set_image(it.addressOf(0).reinterpret(), image.size) }
            null
        } catch (e: IllegalArgumentException) {
            "Playing the last playable version, not your latest edits: ${e.message?.lineSequence()?.firstOrNull()}"
        }
    }

    override fun play(order: Int, row: Int) { da_play(order, row) }
    override fun stop() = da_stop()
    override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) { da_audition(channel, note, instrument, detune, AUDITION_TICKS) }
    override fun releaseAudition() {}
    override fun position() = PlaybackPosition(da_playing() != 0, da_order(), da_row())
    override fun error(): String? = error
    override fun close() = da_close()

    private companion object {
        /** About 0.4 s at the driver's 59.7 Hz tick: the Deck has no held-note release yet. */
        const val AUDITION_TICKS = 24
    }
}
