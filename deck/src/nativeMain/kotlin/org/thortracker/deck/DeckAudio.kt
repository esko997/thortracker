@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.*
import kotlinx.cinterop.*
import org.thortracker.audio.PlaybackPosition
import org.thortracker.audio.TrackerAudio
import org.thortracker.model.Song
import org.thortracker.system.PlaybackEngine
import org.thortracker.system.SoundSystem
import org.thortracker.system.studio.StudioModularAdmission
import kotlin.math.roundToInt

/**
 * [TrackerAudio] over the SDL3 engine glue (native/deck_audio.c), so the shared [org.thortracker.audio.Transport]
 * drives the Deck exactly as it drives Android's ChipAudio: during playback an audition mutes its channel in the
 * song until it is released. It runs the engine of the song's own system ([SoundSystem.engine]) and reopens the
 * glue with another engine when a song of another system arrives. Main thread only.
 */
class DeckAudio(private val audioDriver: () -> String) : TrackerAudio {
    private var system: SoundSystem? = null   // whose engine the glue has open
    private var image = ByteArray(0)
    private var hasImage = false
    private val modularLimits = NativeModularProfile.current
    private var auditionSlot: Int? = null
    private var refusedSong: Song? = null

    /** The song the engine's current image was lowered from (audition note numbers depend on it, e.g. NES DPCM). */
    private var imageSong: Song? = null
    private var songError: String? = null
    private var commandError: String? = null
    private var deviceError: String? = null

    /** Auditions started since launch (test diagnostics). */
    var auditions = 0
        private set

    /** Lowers the song and hands its image to the engine (applied live while playing); a bad song keeps the last one. */
    override fun update(song: Song) {
        refusedSong = null
        if (refuseModular(song, auditionSlot)) return
        val engine = open(song.system) ?: return
        songError = try {
            engine.lower(song, image)
            val studio=song.sound as? org.thortracker.system.studio.StudioSound
            val old=imageSong?.sound as? org.thortracker.system.studio.StudioSound
            val wire=if(studio!=null)
                org.thortracker.system.studio.StudioEngine.withResources(image,studio,includePcm=studio.assets !== old?.assets) else image
            val result = wire.usePinned { da_set_image(it.addressOf(0).reinterpret(), wire.size) }
            require(result==0 || studio==null) { "Studio player could not prepare the sample bank or effect graph ($result)" }
            if (result == 0) { hasImage = true; imageSong = song; null }
            else "Playing the last playable version, not your latest edits: the player rejected the song data (error $result)"
        } catch (e: IllegalArgumentException) {
            if (song.sound is org.thortracker.system.studio.StudioSound) {
                da_stop(); da_release_audition(); hasImage = false; imageSong = null
                commandError = null
                songError = e.message
                return
            }
            "Playing the last playable version, not your latest edits: ${e.message?.lineSequence()?.firstOrNull()}"
        }
    }

    override fun play(order: Int, row: Int) {
        refusedSong?.let { update(it) }
        imageSong?.let { if (refuseModular(it, auditionSlot)) return }
        val engine = ready() ?: return
        commandError = failure(da_play(order, row, engine.seekTicks), "play")
    }

    override fun stop() { commandError = null; auditionSlot = null; if (system != null) da_stop() }
    override fun silence() { commandError = null; auditionSlot = null; if (system != null) da_panic() }

    override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) {
        auditions++
        refusedSong?.let { update(it) }
        val engine = ready() ?: return
        val song = imageSong ?: run { commandError = songError ?: "No playable song loaded"; return }
        engine.auditionProblem(song, channel, note, instrument, detune)?.let { commandError = it; return }
        StudioModularAdmission.problem(song, modularLimits, instrument)?.let {
            da_release_audition()
            auditionSlot = null
            commandError = it
            return
        }
        // The cap counts driver ticks, which multispeed songs run several times per frame.
        val capTicks = (AUDITION_CAP_SECONDS * song.system.ticksPerSecond(song)).roundToInt()
        commandError = failure(da_audition(channel, engine.note(song, channel, note, instrument), instrument, engine.fine(channel, note, detune), capTicks), "audition")
        if (commandError == null) auditionSlot = if (song.sound is org.thortracker.system.studio.StudioSound) instrument else null
    }

    override fun releaseAudition() { commandError = null; auditionSlot = null; if (system != null) da_release_audition() }
    override fun position() = PlaybackPosition(da_playing() != 0, da_order(), da_row())
    override fun error(): String? = deviceError ?: commandError ?: songError
    override fun close() = da_close()

    /**
     * Diagnostics lines: SDL's audio driver, the output (rate, device buffer), callbacks, late callbacks while sounding
     * (likely underruns), the slowest callback and driver faults since launch, and the system whose engine is open.
     */
    fun diagnostics(): List<Pair<String, String>> {
        val v = LongArray(DA_STATS)
        v.usePinned { da_stats(it.addressOf(0), v.size) }
        val driver = audioDriver()
        return listOf(
            "Audio" to "SDL $driver, ${v[0]} Hz float stereo, device buffer ${v[1]} frames, engine ${system?.engine?.id ?: "not open"}",
            "Audio stats" to "callbacks ${v[2]}, late callbacks ${v[3]}, worst callback ${v[4] / 1000} µs, driver faults ${v[5]}",
            "Modular" to "${modularLimits.name}: ${modularLimits.workUnits} work units, ${modularLimits.reservationBytes / (1024 * 1024)} MiB reservation",
        )
    }

    private fun refuseModular(song: Song, slot: Int?): Boolean {
        val problem = StudioModularAdmission.problem(song, modularLimits, slot) ?: return false
        if (system != null) da_panic()
        auditionSlot = null
        hasImage = false
        imageSong = null
        refusedSong = song
        commandError = null
        songError = problem
        return true
    }

    /** [next]'s engine, opening it in place of another system's (whose playback ends); null if it cannot open. */
    private fun open(next: SoundSystem): PlaybackEngine? {
        if (next === system || (next.engine === org.thortracker.system.studio.StudioEngine && system?.engine === next.engine)) {
            system = next
            return next.engine
        }
        da_close()
        system = null
        hasImage = false
        imageSong = null
        auditionSlot = null
        val result = da_open(next.engine.id)
        if (result != 0) {
            da_close()
            deviceError = if (result == NO_ENGINE) "This build has no player for ${next.name}" else "SDL audio could not open (error $result)"
            return null
        }
        deviceError = null
        system = next
        if (image.size != next.engine.imageSize) image = ByteArray(next.engine.imageSize)
        return next.engine
    }

    /** The open engine once it has a playable image, else null with the reason in [commandError]. */
    private fun ready(): PlaybackEngine? {
        val engine = system?.engine
        if (engine == null || !hasImage) { commandError = songError ?: "No playable song loaded"; return null }
        return engine
    }

    private fun failure(code: Int, what: String): String? = when {
        code >= 0 -> null
        code == CHIP_ERR_FAULT -> "Cannot $what: the driver faulted on this song data"
        else -> "Cannot $what: no valid song data loaded"
    }

    private companion object {
        /** A held note sounds until released (as on Android); this caps it at about a minute should a release be lost. */
        const val AUDITION_CAP_SECONDS = 60
        const val NO_ENGINE = -2   // da_open: this build has no engine for the system
        const val CHIP_ERR_FAULT = -2
    }
}
