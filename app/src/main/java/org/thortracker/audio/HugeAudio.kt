package org.thortracker.audio

import android.content.Context
import android.util.Log
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.thortracker.backend.HugeLowering
import org.thortracker.model.Song

/**
 * Game Boy preview: the pinned hUGEDriver 6.1.3 player running in an embedded binjgb Game Boy
 * Color (native, `libthortracker_audio.so`), output through AAudio.
 *
 * - [update] lowers the song with [HugeLowering] (the same data `.uge` export uses), places it with
 *   [HugeImageLayout] and hands the 64 KiB image to the running machine, which applies it at the
 *   next driver tick: pattern cells take effect from the next row processed, order changes at the
 *   next order (the current order's patterns are re-resolved immediately), instrument edits at the
 *   next note that loads the instrument (a sustained note keeps the registers it was triggered
 *   with, as on hardware), wave edits at the next CH3 note with an instrument, base tempo edits
 *   immediately. The transport never restarts.
 * - [play] reconstructs state by silently running the driver from the song start up to the target
 *   row ([SEEK_TICK_BUDGET] ticks max); beyond that, or if jumps make the row unreachable, it
 *   starts from driver-init state at the order start and silently replays that order's earlier rows.
 * - [audition] plays the note through the driver on a second machine with the song's current
 *   instruments; during playback the song's channel is muted with hUGE_mute_channel (the driver
 *   cuts it) and unmuted on release, resuming at that channel's next note.
 * - Work runs on one control thread; the audio callback never allocates or waits.
 */
class HugeAudio(@Suppress("UNUSED_PARAMETER") context: Context) : TrackerAudio {
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
    @Volatile private var commandError: String? = null
    @Volatile private var deviceError: String? = null

    // Control-thread state.
    private val image = ByteArray(HugeImageLayout.SIZE)
    private var imageVersion = 0
    private var hasImage = false
    private var idleCheck: ScheduledFuture<*>? = null

    init {
        post {
            val created = nativeCreate()
            if (created == 0L) {
                deviceError = "Audio engine could not allocate the Game Boy player"
            } else {
                synchronized(handleLock) { handle = created }
            }
        }
    }

    override fun update(song: Song) {
        if (closed) return
        if (pendingSong.getAndSet(song) == null) post { applyPendingSong() }
    }

    override fun play(order: Int, row: Int) = post {
        applyPendingSong()
        val h = readyHandle() ?: return@post
        val result = nativePlay(h, order, row, SEEK_TICK_BUDGET)
        commandError = if (result < 0) engineFailure(result, "play") else null
        scheduleIdleCheck()
    }

    override fun stop() = post {
        val h = handle
        if (h != 0L) nativeStop(h)
        scheduleIdleCheck()
    }

    override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) = post {
        if (instrument !in 1..15) {
            commandError = "Choose an instrument (01-0F) to audition"
            return@post
        }
        if (note !in 0..71 || channel !in 0..3) {
            commandError = "Cannot audition note $note on channel $channel"
            return@post
        }
        if (detune !in 0..15) {
            commandError = "Cannot audition detune $detune (00-0F)"
            return@post
        }
        applyPendingSong()
        val h = readyHandle() ?: return@post
        val result = nativeAudition(h, channel, note, instrument, detune)
        commandError = if (result < 0) engineFailure(result, "audition") else null
        if (result >= 0) {
            // A held note keeps the stream: a pending idle check must not stop it before the
            // callback has picked the audition up.
            idleCheck?.cancel(false)
            idleCheck = null
        }
    }

    override fun releaseAudition() = post {
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
        val h = handle
        if (h == 0L) {
            pendingSong.compareAndSet(null, song)
            return
        }
        try {
            HugeImageLayout.build(HugeLowering.lower(song), image)
        } catch (e: IllegalArgumentException) {
            songError = "Playing the last playable version, not your latest edits: ${e.message ?: "invalid song"}"
            return
        }
        imageVersion++
        val result = nativeSetImage(h, image, imageVersion)
        if (result == 0) {
            hasImage = true
            songError = null
        } else {
            songError = "Playing the last playable version, not your latest edits: the player rejected the song data (layout error $result)"
        }
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
        HM_ERR_IMAGE -> "Cannot $what: no valid song data loaded"
        HM_ERR_FAULT -> "Cannot $what: the driver faulted on this song data"
        else -> "Cannot $what (error $code)"
    }

    companion object {
        private const val TAG = "HugeAudio"
        /** Seek reconstruction budget in driver ticks (59.73 per second): ~3.3 minutes of song. */
        const val SEEK_TICK_BUDGET = 12000
        private const val IDLE_SUSPEND_SECONDS = 10L
        private const val CLOSE_TIMEOUT_SECONDS = 2L
        private const val HM_ERR_IMAGE = -1
        private const val HM_ERR_FAULT = -2
        private val DIAGNOSTIC_NAMES = listOf(
            "sampleRate", "framesPerBurst", "bufferFrames", "xruns", "maxCallbackNs", "lastSeekNs",
            "lastAuditionPrepNs", "driverFaults", "lastSeekMode", "exclusive", "lowLatency", "lastLiveApplyNs",
            "outputLatencyFrames", "rawTimestampLatencyFrames",
        )

        init {
            System.loadLibrary("thortracker_audio")
        }

        @JvmStatic private external fun nativeCreate(): Long
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
    }
}
