@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

package org.thortracker.deck

import kotlinx.cinterop.*
import kotlin.concurrent.Volatile
import kotlin.native.concurrent.Future
import kotlin.native.concurrent.FutureState
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.time.TimeSource
import org.thortracker.system.studio.StudioBouncePlan
import org.thortracker.system.studio.StudioPcm
import org.thortracker.system.studio.render
import org.thortracker.system.studio.decode
import platform.posix.close
import platform.posix.mkstemp
import platform.posix.remove

/** Uses the existing file worker; the frame loop only delivers progress and completion. */
class DeckBounce(private val directory: String) : AutoCloseable {
    private class State {
        @Volatile var cancelled = false
        @Volatile var frames = 0L
    }
    private class Run(
        val state: State,
        val future: Future<Result<StudioPcm?>>,
        val progress: (Long) -> Unit,
        val complete: (Result<StudioPcm?>) -> Unit,
    ) {
        var shown = -1L
        var shownAt = TimeSource.Monotonic.markNow()
    }
    private var run: Run? = null
    private var closed = false
    val busy: Boolean get() = run != null

    init {
        Files.list(directory)?.filter { name ->
            name.startsWith(PREFIX) && name.removePrefix(PREFIX).let { suffix ->
                suffix.length == 6 && suffix.all { it.isLetterOrDigit() }
            }
        }?.forEach { remove("$directory/$it") }
    }

    fun start(worker: Worker, plan: StudioBouncePlan, progress: (Long) -> Unit,
              complete: (Result<StudioPcm?>) -> Unit): (() -> Unit)? {
        if (closed || busy) return null
        val state = State()
        val directory = directory
        val future = worker.execute(TransferMode.SAFE, { Triple(plan, directory, state) }) { (snapshot, dir, control) ->
            var path: String? = null
            try {
                runCatching {
                    if (control.cancelled) return@runCatching null
                    val spool = memScoped {
                        val name = "$dir/${PREFIX}XXXXXX".cstr.getPointer(this)
                        val fd = mkstemp(name)
                        check(fd >= 0) { "Could not create the bounce spool" }
                        close(fd)
                        name.toKString()
                    }
                    path = spool
                    val finished = snapshot.render(DeckWav, spool, { control.cancelled }) { control.frames = it }
                    if (!finished || control.cancelled) return@runCatching null
                    val bytes = Files.readBytes(spool, snapshot.spoolBytes.toLong())
                        ?: error("Could not read the complete bounce WAV")
                    if (control.cancelled) null else snapshot.decode(bytes).takeUnless { control.cancelled }
                }
            } finally {
                path?.let { remove(it) }
            }
        }
        run = Run(state, future, progress, complete)
        return { state.cancelled = true }
    }

    fun pump() {
        val current = run ?: return
        val frames = current.state.frames
        if (!current.state.cancelled && frames != current.shown && current.shownAt.elapsedNow().inWholeMilliseconds >= 150) {
            current.shown = frames
            current.shownAt = TimeSource.Monotonic.markNow()
            current.progress(frames)
        }
        if (current.future.state != FutureState.COMPUTED) return
        val result = current.future.result
        run = null
        if (!closed) current.complete(if (current.state.cancelled) Result.success(null) else result)
    }

    override fun close() {
        closed = true
        run?.let {
            it.state.cancelled = true
            it.future.result // drain ownership before the shared worker terminates
        }
        run = null
    }

    private companion object { const val PREFIX = "studio-bounce-" }
}
