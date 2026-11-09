package org.thortracker.storage

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.AtomicFile
import org.thortracker.system.ExportFormat
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** App-private recovery never waits for potentially stalled document-provider I/O. */
class ProjectStore(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    @Volatile private var closed = false
    @Volatile private var recoveryWritable = true
    private val main = Handler(Looper.getMainLooper())
    private val recovery = AtomicFile(File(context.filesDir, "recovery.thor"))
    private val previous = AtomicFile(File(context.filesDir, "previous.thor"))
    private var scheduled: ScheduledFuture<*>? = null
    var onError: ((String) -> Unit)? = null

    fun restore(callback: (Song?, String?) -> Unit) {
        recoveryExecutor.execute {
            var song: Song? = null
            var message: String? = null
            try {
                if (recovery.baseFile.exists() || File(recovery.baseFile.path + ".bak").exists()) {
                    song = ProjectCodec.decode(recovery.openRead().bufferedReader().use { it.readText() })
                }
            } catch (e: Exception) {
                val suffix = "unreadable-${System.currentTimeMillis()}.thor"
                try {
                    for (extension in listOf("", ".bak", ".new")) {
                        val source = File(recovery.baseFile.path + extension)
                        if (source.exists()) {
                            val preserved = File(context.filesDir, "$suffix$extension")
                            check(source.renameTo(preserved)) { "Could not preserve ${source.name}" }
                        }
                    }
                    message = "Could not load recovery: ${e.message}. Preserved as $suffix."
                } catch (preserveError: Exception) {
                    recoveryWritable = false
                    message = "Recovery is unreadable and could not be moved: ${preserveError.message}. Automatic recovery writes are disabled to protect it; use Save as."
                }
            }
            deliver { callback(song, message) }
        }
    }

    @Synchronized
    fun autosave(song: Song, immediate: Boolean = false) {
        if (closed || !recoveryWritable) return
        scheduled?.cancel(false)
        scheduled = recoveryExecutor.schedule({
            try { writeAtomic(recovery, ProjectCodec.encode(song)) }
            catch (e: Exception) { fail("Recovery save failed", e) }
        }, if (immediate) 0 else 250, TimeUnit.MILLISECONDS)
    }

    /** Preserve the outgoing document before explicitly replacing it. */
    fun archive(song: Song) {
        recoveryExecutor.execute {
            try { writeAtomic(previous, ProjectCodec.encode(song)) }
            catch (e: Exception) { fail("Previous-project backup failed", e) }
        }
    }

    fun open(uri: Uri, callback: (Song) -> Unit) {
        providerExecutor.execute {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                    // Bound hostile or accidental non-project selections before materializing JSON.
                    val bytes = stream.readBytesBounded(16 * 1024 * 1024)
                    bytes.toString(Charsets.UTF_8)
                } ?: error("The selected provider did not open the document")
                val song = ProjectCodec.decode(text)
                deliver { callback(song) }
            } catch (e: Exception) { fail("Open failed; current song unchanged", e) }
        }
    }

    fun save(uri: Uri, song: Song, callback: () -> Unit) {
        providerExecutor.execute {
            try {
                val text = ProjectCodec.encode(song)
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: error("The selected provider did not open the document for writing")
                deliver(callback)
            } catch (e: Exception) { fail("Save failed; app-private recovery retained", e) }
        }
    }

    /** [uri] is a newly created export document, never the working project; remove it if generation or writing fails. */
    fun export(uri: Uri, song: Song, format: ExportFormat, callback: () -> Unit) {
        providerExecutor.execute {
            try {
                val bytes = format.export(song)
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                } ?: error("The selected provider did not open the export destination")
                deliver(callback)
            } catch (e: Exception) {
                val removed = runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                val cleanup = if (removed) "" else "; the incomplete export destination could not be removed"
                fail("${format.extension.uppercase()} export failed$cleanup", e)
            }
        }
    }

    fun previous(callback: (Song) -> Unit) {
        recoveryExecutor.execute {
            try {
                val song = ProjectCodec.decode(previous.openRead().bufferedReader().use { it.readText() })
                deliver { callback(song) }
            } catch (e: Exception) { fail("No readable previous-project backup", e) }
        }
    }

    private fun writeAtomic(file: AtomicFile, text: String) {
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { "Project exceeds the 16 MiB input limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun fail(message: String, exception: Exception) {
        deliver { onError?.invoke("$message: ${exception.message ?: exception.javaClass.simpleName}") }
    }

    private fun deliver(callback: () -> Unit) {
        main.post { if (!closed) callback() }
    }

    override fun close() {
        closed = true
        onError = null
        main.removeCallbacksAndMessages(null)
        // Queued recovery writes finish in order before the next Activity's restore.
    }

    companion object {
        private val recoveryExecutor = Executors.newSingleThreadScheduledExecutor()
        private val providerExecutor = Executors.newSingleThreadExecutor()
    }
}
