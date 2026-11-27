package org.thortracker.audio

import android.os.Handler
import android.os.SystemClock
import org.thortracker.system.studio.StudioBouncePlan
import org.thortracker.system.studio.StudioPcm
import org.thortracker.system.studio.render
import org.thortracker.system.studio.decode
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One private offline render; only its worker touches the native job and spool. */
class StudioBounceWorker(private val cache: File, private val main: Handler) : AutoCloseable {
    private class Ticket { @Volatile var cancelled = false }
    private var ticket: Ticket? = null // main thread
    @Volatile private var closed = false
    val busy: Boolean get() = ticket != null

    init {
        // A replacement Activity must not remove an older Activity's still-unwinding spool.
        if (cleaned.compareAndSet(false, true)) {
            cache.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(".part") }
                ?.forEach { it.delete() }
        }
    }

    fun start(
        plan: StudioBouncePlan,
        progress: (Long) -> Unit,
        complete: (Result<StudioPcm?>) -> Unit,
    ): (() -> Unit)? {
        if (closed || busy) return null
        val run = Ticket()
        ticket = run
        Thread({
            var spool: File? = null
            val result = runCatching {
                if (run.cancelled) return@runCatching null
                val file = File.createTempFile(PREFIX, ".part", cache)
                spool = file
                var shown = 0L
                val renderer = WavExport.renderer ?: error("The offline audio renderer is unavailable")
                val finished = plan.render(renderer, file.path, { run.cancelled }) { frames ->
                    val now = SystemClock.uptimeMillis()
                    if (frames == plan.frames || now - shown >= 150) {
                        shown = now
                        main.post {
                            if (!closed && ticket === run && !run.cancelled) progress(frames)
                        }
                    }
                }
                if (!finished || run.cancelled) return@runCatching null
                check(file.length() == plan.spoolBytes.toLong()) { "The rendered WAV has an unexpected size" }
                val bytes = ByteArray(plan.spoolBytes)
                DataInputStream(file.inputStream()).use { input ->
                    input.readFully(bytes)
                    check(input.read() == -1) { "The rendered WAV exceeds its planned size" }
                }
                if (run.cancelled) null else plan.decode(bytes).takeUnless { run.cancelled }
            }
            spool?.delete()
            main.post {
                if (ticket === run) ticket = null
                if (!closed) complete(if (run.cancelled) Result.success(null) else result)
            }
        }, "studio-bounce").start()
        return { run.cancelled = true }
    }

    override fun close() {
        closed = true
        ticket?.cancelled = true
        ticket = null
        // Do not block the UI or close a native pointer owned by the worker.
        // The worker's finally/cleanup path finishes independently of the audio device.
    }

    private companion object {
        const val PREFIX = "studio-bounce-"
        val cleaned = AtomicBoolean(false)
    }
}
