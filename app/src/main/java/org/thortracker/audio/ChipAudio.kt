package org.thortracker.audio

import android.content.Context
import android.util.Log
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.thortracker.model.Song
import org.thortracker.system.SoundSystem
import org.thortracker.system.ay.AyVgm
import org.thortracker.system.ay.AyVgmRenderer
import org.thortracker.system.opl3.Opl3Vgm
import org.thortracker.system.opl3.Opl3VgmRenderer
import org.thortracker.system.sms.SmsExport
import org.thortracker.system.sms.SmsVgmRenderer
import org.thortracker.system.ym2612.YmExport
import org.thortracker.system.ym2612.YmExportRenderer

/**
 * Song preview through the native chip engine (`libthortracker_audio.so`, `chip_engine.h`), output
 * through AAudio. It runs the engine of the song's own system ([SoundSystem.engine]) and replaces
 * it when a song of another system arrives (that stops playback).
 *
 * - [update] lowers the song with the system's [org.thortracker.system.PlaybackEngine.lower] (the
 *   same data its export uses) and hands the image to the running machine, which applies it at the
 *   next driver tick; the transport never restarts. What an edit changes when is the driver's own
 *   behaviour (docs/AUDIO_ENGINE.md, "Live-edit semantics").
 * - [play] reconstructs state by silently running the driver from the song start up to the target
 *   row (the engine's [org.thortracker.system.PlaybackEngine.seekTicks] max); beyond that, or if
 *   jumps make the row unreachable, it starts from driver-init state at the order start and
 *   silently replays that order's earlier rows.
 * - [audition] plays the note through the driver on a second machine with the song's current
 *   instruments; during playback the song's channel is muted by the driver and unmuted on
 *   release, resuming at that channel's next note.
 * - Work runs on one control thread; the audio callback never allocates or waits.
 */
class ChipAudio(@Suppress("UNUSED_PARAMETER") context: Context) : TrackerAudio {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "thor-audio-control").apply { isDaemon = true }
    }.apply {
        // close() must not wait for a pending idle check.
        executeExistingDelayedTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }
    private val handleLock = Any()
    @Volatile private var handle = 0L
    @Volatile private var closed = false
    private val pendingSong = AtomicReference<Song?>(null)
    @Volatile private var songError: String? = null

    /** The song the engine's current image was lowered from (audition note numbers depend on it, e.g. NES DPCM). */
    private var imageSong: Song? = null
    @Volatile private var commandError: String? = null
    @Volatile private var deviceError: String? = null

    // Control-thread state.
    private var system: SoundSystem? = null   // whose engine `handle` runs
    private var image = ByteArray(0)
    private var imageVersion = 0
    private var hasImage = false
    private var idleCheck: ScheduledFuture<*>? = null

    override fun update(song: Song) {
        if (closed) return
        if (pendingSong.getAndSet(song) == null) post { applyPendingSong() }
    }

    override fun play(order: Int, row: Int) = post {
        applyPendingSong()
        val h = readyHandle() ?: return@post
        val engine = system?.engine ?: return@post   // set whenever readyHandle has a handle
        val result = nativePlay(h, order, row, engine.seekTicks)
        commandError = if (result < 0) engineFailure(result, "play") else null
        scheduleIdleCheck()
    }

    override fun stop() = post {
        commandError = null   // a failed command is over once its sound is
        val h = handle
        if (h != 0L) nativeStop(h)
        scheduleIdleCheck()
    }

    override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) = post {
        applyPendingSong()
        val engine = system?.engine
        if (engine == null) {
            commandError = songError ?: "No playable song loaded"
            return@post
        }
        val song = imageSong
        if (song == null) {
            commandError = songError ?: "No playable song loaded"
            return@post
        }
        engine.auditionProblem(song, channel, note, instrument, detune)?.let {
            commandError = it
            return@post
        }
        val h = readyHandle() ?: return@post
        val result = nativeAudition(h, channel, engine.note(song, channel, note, instrument), instrument, engine.fine(channel, note, detune))
        commandError = if (result < 0) engineFailure(result, "audition") else null
        if (result >= 0) {
            // A held note keeps the stream: a pending idle check must not stop it before the
            // callback has picked the audition up.
            idleCheck?.cancel(false)
            idleCheck = null
        }
    }

    override fun releaseAudition() = post {
        commandError = null
        val h = handle
        if (h != 0L) nativeReleaseAudition(h)
        scheduleIdleCheck()
    }

    override fun position(): PlaybackPosition = synchronized(handleLock) {
        val h = handle
        if (h == 0L) return PlaybackPosition()
        val packed = nativePosition(h)
        if (packed < 0) return PlaybackPosition()
        PlaybackPosition(
            playing = (packed shr 40) and 1L == 1L,
            order = ((packed shr 16) and 0xFF).toInt(),
            row = (packed and 0xFF).toInt(),
        )
    }

    override fun error(): String? = deviceError ?: commandError ?: songError

    /**
     * Measurements for latency tuning: sample rate, frames per burst, buffer size (frames), xruns,
     * worst callback (ns), last seek / audition preparation (ns), driver faults, last seek mode
     * (0 from song start, 1 from order start), exclusive stream, low-latency stream, last live
     * image apply inside the callback (ns), output latency used for the playhead and the raw
     * timestamp measurement (frames at 48 kHz; negative measurements fall back to the buffer size).
     */
    fun diagnostics(): Map<String, Long> = synchronized(handleLock) {
        val h = handle
        if (h == 0L) return emptyMap()
        val values = LongArray(DIAGNOSTIC_NAMES.size)
        nativeStats(h, values)
        DIAGNOSTIC_NAMES.zip(values.toList()).toMap()
    }

    override fun close() {
        if (closed) return
        closed = true
        pendingSong.set(null)
        try {
            executor.execute {
                try {
                    idleCheck?.cancel(false)
                    synchronized(handleLock) {
                        val h = handle
                        handle = 0L
                        if (h != 0L) nativeDestroy(h)
                    }
                } finally {
                    // Shut down from inside the destroy task: Android's ScheduledThreadPoolExecutor
                    // (b/202927404) cancels every queued task on shutdown() when delayed tasks are not
                    // kept, so calling it before this task ran would drop the destroy.
                    executor.shutdown()
                }
            }
        } catch (_: RejectedExecutionException) {
            executor.shutdown()
        }
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "audio control thread did not finish within $CLOSE_TIMEOUT_SECONDS s")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun post(block: () -> Unit) {
        if (closed) return
        try {
            executor.execute {
                try {
                    block()
                } catch (t: RuntimeException) {
                    // Never let a preview problem take down the host; report it instead.
                    Log.e(TAG, "audio command failed", t)
                    commandError = "Audio error: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun applyPendingSong() {
        val song = pendingSong.getAndSet(null) ?: return
        val h = engineFor(song.system)
        if (h == 0L) {
            pendingSong.compareAndSet(null, song)
            return
        }
        try {
            song.system.engine.lower(song, image)
        } catch (e: IllegalArgumentException) {
            songError = "Playing the last playable version, not your latest edits: ${e.message ?: "invalid song"}"
            return
        }
        imageVersion++
        val result = nativeSetImage(h, image, imageVersion)
        if (result == 0) {
            hasImage = true
            imageSong = song
            songError = null
        } else {
            songError = "Playing the last playable version, not your latest edits: the player rejected the song data (layout error $result)"
        }
    }

    /**
     * The handle of [next]'s engine: the running one, or a new one replacing another system's (its
     * transport, stream and image go with it). 0 when it cannot be created ([deviceError] says why).
     */
    private fun engineFor(next: SoundSystem): Long {
        if (next === system) return handle
        synchronized(handleLock) {
            val old = handle
            handle = 0L
            if (old != 0L) nativeDestroy(old)
        }
        system = null
        hasImage = false
        imageSong = null
        val created = nativeCreate(next.engine.id)
        if (created == 0L) {
            deviceError = "Audio engine could not start the ${next.name} player"
            return 0L
        }
        synchronized(handleLock) { handle = created }
        system = next
        if (image.size != next.engine.imageSize) image = ByteArray(next.engine.imageSize)
        deviceError = null
        return created
    }

    private fun readyHandle(): Long? {
        val h = handle
        if (h == 0L) return null
        if (!hasImage) {
            commandError = songError ?: "No playable song loaded"
            return null
        }
        val stream = nativeEnsureStream(h)
        deviceError = if (stream == 0) null else "Audio output unavailable (AAudio error $stream)"
        return if (stream == 0) h else null
    }

    private fun scheduleIdleCheck() {
        idleCheck?.cancel(false)
        idleCheck = executor.schedule({
            val h = handle
            if (h != 0L) nativeSuspendIfIdle(h)
        }, IDLE_SUSPEND_SECONDS, TimeUnit.SECONDS)
    }

    private fun engineFailure(code: Int, what: String) = when (code) {
        CHIP_ERR_IMAGE -> "Cannot $what: no valid song data loaded"
        CHIP_ERR_FAULT -> "Cannot $what: the driver faulted on this song data"
        else -> "Cannot $what (error $code)"
    }

    companion object {
        private const val TAG = "ChipAudio"
        private const val IDLE_SUSPEND_SECONDS = 10L
        private const val CLOSE_TIMEOUT_SECONDS = 2L
        private const val CHIP_ERR_IMAGE = -1
        private const val CHIP_ERR_FAULT = -2
        private val DIAGNOSTIC_NAMES = listOf(
            "sampleRate", "framesPerBurst", "bufferFrames", "xruns", "maxCallbackNs", "lastSeekNs",
            "lastAuditionPrepNs", "driverFaults", "lastSeekMode", "exclusive", "lowLatency", "lastLiveApplyNs",
            "outputLatencyFrames", "rawTimestampLatencyFrames",
        )

        init {
            System.loadLibrary("thortracker_audio")
            // Native VGM/XGM/XGC exports and WAV rendering (core has no native code).
            Opl3Vgm.renderer = Opl3VgmRenderer { image, maxTicks, tags ->
                nativeOpl3Vgm(image, maxTicks, tags.title.encodeToByteArray(), tags.author.encodeToByteArray(), tags.date.encodeToByteArray())
            }
            YmExport.renderer = YmExportRenderer { image, format, maxTicks, tags ->
                nativeYmExport(image, format, maxTicks, tags.title.encodeToByteArray(), tags.author.encodeToByteArray(), tags.date.encodeToByteArray())
            }
            AyVgm.renderer = AyVgmRenderer { variant, image, maxTicks, tags ->
                nativeAyVgm(variant, image, maxTicks, tags.title.encodeToByteArray(), tags.author.encodeToByteArray(), tags.date.encodeToByteArray())
            }
            SmsExport.renderer = SmsVgmRenderer { image, maxTicks, tags ->
                nativeSmsVgm(image, maxTicks, tags.title.encodeToByteArray(), tags.author.encodeToByteArray(), tags.date.encodeToByteArray())
            }
            WavExport.renderer = WavRenderer { engineId, image, options, spool ->
                NativeWavJob(nativeWavOpen(engineId, image, options.loops, options.fadeSeconds * 1000, options.maxSeconds, spool))
            }
        }

        /** A native WAV render (`chip_wav.h`) through JNI; one thread at a time, as [WavJob] requires. */
        private class NativeWavJob(private var handle: Long) : WavJob {
            private val values = LongArray(6)

            override fun run(ticks: Int): Boolean = when (val r = nativeWavRun(handle, ticks)) {
                0 -> true
                1 -> false
                CHIP_ERR_FAULT -> throw IllegalStateException("the driver faulted on this song data")
                else -> throw IllegalStateException("the file could not be written (storage full?)")
            }

            override fun status(): WavStatus {
                nativeWavStatus(handle, values)
                return WavStatus(values[0], values[1], values[2].toInt(), values[3] != 0L, values[4], values[5] != 0L)
            }

            override fun close() {
                if (handle != 0L) nativeWavClose(handle)
                handle = 0L
            }
        }

        @JvmStatic private external fun nativeCreate(systemId: String): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSetImage(handle: Long, image: ByteArray, version: Int): Int
        @JvmStatic private external fun nativeEnsureStream(handle: Long): Int
        @JvmStatic private external fun nativePlay(handle: Long, order: Int, row: Int, maxTicks: Int): Int
        @JvmStatic private external fun nativeStop(handle: Long)
        @JvmStatic private external fun nativeAudition(handle: Long, channel: Int, note: Int, instrument: Int, detune: Int): Int
        @JvmStatic private external fun nativeReleaseAudition(handle: Long)
        @JvmStatic private external fun nativeSuspendIfIdle(handle: Long): Boolean
        @JvmStatic private external fun nativePosition(handle: Long): Long
        @JvmStatic private external fun nativeStats(handle: Long, out: LongArray)
        /** UTF-8 tag strings; throws IllegalArgumentException(loader problem) for a rejected image. */
        @JvmStatic private external fun nativeOpl3Vgm(image: ByteArray, maxTicks: Int, title: ByteArray, author: ByteArray, date: ByteArray): ByteArray
        /** Format 0 VGM, 1 XGM, 2 XGC (`ym_export.h`); UTF-8 tags; throws IllegalArgumentException(loader problem). */
        @JvmStatic private external fun nativeYmExport(image: ByteArray, format: Int, maxTicks: Int, title: ByteArray, author: ByteArray, date: ByteArray): ByteArray
        /** `ay_export.h`: [variant] 0 ZX Spectrum 128, 1 MSX, 2 Atari ST, 3 Amstrad CPC; UTF-8 tags; throws IllegalArgumentException(loader problem). */
        @JvmStatic private external fun nativeAyVgm(variant: Int, image: ByteArray, maxTicks: Int, title: ByteArray, author: ByteArray, date: ByteArray): ByteArray
        /** Master System VGM 1.51 (`sms_export.h`); UTF-8 tags; throws IllegalArgumentException(loader problem). */
        @JvmStatic private external fun nativeSmsVgm(image: ByteArray, maxTicks: Int, title: ByteArray, author: ByteArray, date: ByteArray): ByteArray
        /** `chip_wav.h`: a job rendering into [spool]; throws IllegalArgumentException(problem) when it cannot start. */
        @JvmStatic private external fun nativeWavOpen(systemId: String, image: ByteArray, loops: Int, fadeMs: Int, maxSeconds: Int, spool: String): Long
        @JvmStatic private external fun nativeWavRun(handle: Long, ticks: Int): Int
        @JvmStatic private external fun nativeWavStatus(handle: Long, out: LongArray)
        @JvmStatic private external fun nativeWavClose(handle: Long)

        /**
         * Installs the YMF288 rhythm ROM (`nes_epsm_install_rhythm_rom`): process-wide firmware every EPSM engine plays
         * its six rhythm drums from. 0 = installed (identical bytes again: no change), -1 = not 8192 bytes. Callers
         * validate the bytes first ([org.thortracker.system.nes.EpsmRhythmRom.problem]); never called concurrently.
         */
        @JvmStatic external fun nativeInstallRhythmRom(rom: ByteArray): Int
    }
}
