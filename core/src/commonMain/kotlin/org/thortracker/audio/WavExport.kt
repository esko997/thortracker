package org.thortracker.audio

import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.ui.PopupItem
import org.thortracker.ui.PopupSpec

/**
 * How long a WAV export runs (`chip_wav.h`): the song once through to its loop point, [loops] more passes of the loop,
 * then a [fadeSeconds] fade-out of the song playing on (0: end at the loop boundary without a fade).
 * The song part stops at [maxSeconds] if it gets that far; the export then says so ([WavExport.capped]).
 */
data class WavOptions(val loops: Int = 0, val fadeSeconds: Int = DEFAULT_FADE_SECONDS, val maxSeconds: Int = MAX_SECONDS) {
    /** The menu row: "Once through, then fade out". */
    val label: String
        get() = when {
            fadeSeconds == 0 && loops == 0 -> "Once through, no fade"
            fadeSeconds == 0 -> "Once + $loops loop${if (loops == 1) "" else "s"}, no fade"
            loops == 0 -> "Once through, then fade out"
            else -> "Once + $loops loop${if (loops == 1) "" else "s"}, then fade out"
        }

    companion object {
        const val DEFAULT_FADE_SECONDS = 5
        const val MAX_SECONDS = 600

        /** What Files → Export .wav offers, the default first. */
        val CHOICES = listOf(WavOptions(), WavOptions(loops = 1), WavOptions(loops = 2), WavOptions(loops = 3), WavOptions(fadeSeconds = 0))
    }
}

/**
 * A native render's progress (`chip_wav_status`): frames so far, the expected total once known (else 0), the order
 * playing, whether the loop point is known, the file size once complete (else 0), and whether the song part stopped
 * at the length limit.
 */
data class WavStatus(val frames: Long, val target: Long, val order: Int, val looped: Boolean, val size: Long, val capped: Boolean)

/** One offline render in the native engine (`chip_wav.h`), spooled to a file; used by one thread at a time. */
interface WavJob : AutoCloseable {
    /**
     * Runs at most [ticks] driver ticks; true once the file is complete (its header written, the spool closed).
     * @throws IllegalStateException when the driver faulted or the spool file could not be written.
     */
    fun run(ticks: Int): Boolean

    fun status(): WavStatus

    /** Frees the job. The native side removes the spool file unless it is complete: then it is the host's. */
    override fun close()
}

/**
 * The native entry seam. Core has no native code, so each host registers the renderer at startup: Android through JNI
 * (`ChipAudio.nativeWav*`), the Deck through cinterop (`DeckWav`). The native host test
 * (app/src/test/cpp/wav_render_test.c) covers the engine side.
 */
fun interface WavRenderer {
    /**
     * Starts rendering [image], lowered for engine [engineId] ([org.thortracker.system.PlaybackEngine.id]), into the
     * file at [spool] (a host-private path; created or replaced).
     * @throws IllegalArgumentException with the engine's reason when it refuses the image or cannot create the file.
     */
    fun open(engineId: String, image: ByteArray, options: WavOptions, spool: String): WavJob
}

/**
 * A song's WAV export: lowered with its system's playback lowering (what the preview plays, so the file sounds the
 * same), rendered in steps by the host off its UI work (Android: a worker thread; the Deck: slices of the frame
 * loop) into the [spool] file, with progress for a popup. 48 kHz 16-bit stereo PCM. Memory stays bounded: the samples
 * go to the file as they are made. Once [done], the complete file at [spool] belongs to the host, which moves or
 * copies it to the user's destination and deletes it; closing earlier removes it.
 * @throws IllegalStateException when this build has no native renderer.
 * @throws IllegalArgumentException when the song cannot be lowered or the engine refuses it.
 */
class WavExport(song: Song, val options: WavOptions, val spool: String, renderer: WavRenderer? = WavExport.renderer) : AutoCloseable {
    val title: String = song.title
    private val orders = Songs.playedOrders(song).size.coerceAtLeast(1)
    private val job: WavJob
    private var shown = 0f
    private var last = WavStatus(0, 0, 0, false, 0, false)

    init {
        val r = renderer ?: throw IllegalStateException("WAV export needs the native players, which this build does not include")
        val engine = song.system.engine
        val image = ByteArray(engine.imageSize)
        engine.lower(song, image)
        job = try {
            r.open(engine.id, image, options, spool)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(when (e.message) {   // chip_wav_open's problems
                "image" -> "the player refused the song data"
                "spool" -> "the temporary file could not be created"
                else -> e.message
            }, e)
        }
    }

    /** True once the file is complete. */
    var done = false
        private set

    /** Renders up to [ticks] more driver ticks; true once the file is complete. */
    fun step(ticks: Int = STEP_TICKS): Boolean {
        if (!done) {
            done = job.run(ticks)
            last = job.status()
        }
        return done
    }

    /**
     * 0..1, never going back. Exact once the loop point is known; before that, the played-order position through the
     * first pass, scaled by the passes to come.
     */
    fun progress(): Float {
        val s = last
        val p = when {
            done -> 1f
            s.target > 0 -> (s.frames.toDouble() / s.target).toFloat()
            else -> s.order.toFloat() / orders / (1 + options.loops)
        }
        shown = maxOf(shown, p.coerceIn(0f, if (done) 1f else 0.99f))
        return shown
    }

    /** Seconds of audio rendered so far, and the expected total once known (else null). */
    val seconds: Double get() = last.frames.toDouble() / RATE
    val totalSeconds: Double? get() = last.target.takeIf { it > 0 }?.let { it.toDouble() / RATE }

    /** The complete file's size in bytes (0 before [done]). */
    val size: Long get() = last.size

    /** The song part stopped at [WavOptions.maxSeconds]: the file is not the whole song, or not all the loop passes. */
    val capped: Boolean get() = last.capped

    /** What the finished file holds, for the hosts' message: "1:35", plus why it stopped early when [capped]. */
    fun summary(): String {
        val length = clock(seconds)
        if (!capped) return length
        val limit = clock(options.maxSeconds.toDouble())
        return if (last.looped) "$length; it stopped at the $limit limit before all the loop passes"
        else "$length; no loop point was reached within $limit, so the export stopped at that limit"
    }

    override fun close() = job.close()

    /**
     * The progress popup the hosts show while rendering: [cancel] stops the export; Hide (where the cursor starts, and
     * Back) only closes the popup, and the export carries on.
     */
    fun popup(cancel: () -> Unit): PopupSpec {
        val percent = (progress() * 100).toInt()
        val total = totalSeconds?.let { " of ${clock(it)}" } ?: ""
        return PopupSpec("Exporting .wav", message = "$title: $percent% · ${clock(seconds)}$total rendered. " +
            "Hide keeps it running; Files → Export .wav shows it again.",
            items = listOf(PopupItem("Cancel export", PopupItem.Tone.DANGER, action = cancel), PopupItem("Hide (keep exporting)")),
            selected = 1)
    }

    companion object {
        /** Set by the host before the first export; null where no native players are linked. */
        var renderer: WavRenderer? = null

        const val RATE = 48000

        /** Driver ticks per [step]: a few milliseconds of work on every system. */
        const val STEP_TICKS = 32

        /** "1:05". */
        fun clock(seconds: Double): String {
            val s = seconds.toLong()
            return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        }
    }
}
