@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import kotlinx.cinterop.*
import org.thortracker.ui.PopupItem
import org.thortracker.ui.PopupSpec
import org.thortracker.ui.Popups
import platform.posix.*

/** Small POSIX file helpers for the Deck frontend (UTF-8 text and bytes, directories). */
object Files {
    val home: String = getenv("HOME")?.toKString() ?: "/tmp"

    fun readBytes(path: String, max: Long = 64L * 1024 * 1024): ByteArray? {
        val f = fopen(path, "rb") ?: return null
        try {
            fseek(f, 0, SEEK_END)
            val n = ftell(f)
            if (n < 0 || n > max) return null
            fseek(f, 0, SEEK_SET)
            val out = ByteArray(n.toInt())
            if (n > 0) out.usePinned { if (fread(it.addressOf(0), 1u, n.convert(), f).toLong() != n) return null }
            return out
        } finally {
            fclose(f)
        }
    }

    fun readText(path: String): String? = readBytes(path)?.decodeToString()

    /** Writes via a temporary file and rename, so a crash never leaves a half-written file at [path]. */
    fun write(path: String, bytes: ByteArray): Boolean {
        val tmp = "$path.tmp"
        val f = fopen(tmp, "wb") ?: return false
        val ok = try {
            bytes.isEmpty() || bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.convert(), f).toInt() == bytes.size }
        } finally {
            fclose(f)
        }
        return ok && rename(tmp, path) == 0
    }

    fun exists(path: String): Boolean = access(path, F_OK) == 0

    fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
    }

    /** Creates [path] and its parents. */
    fun mkdirs(path: String) {
        var at = ""
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            at += "/$part"
            if (!exists(at)) mkdir(at, 0x1EDu) // 0755
        }
    }

    /** Names in [dir], without "." and ".."; null when it cannot be read. */
    fun list(dir: String): List<String>? {
        val d = opendir(dir) ?: return null
        val out = ArrayList<String>()
        try {
            while (true) {
                val e = readdir(d) ?: break
                val name = e.pointed.d_name.toKString()
                if (name != "." && name != "..") out += name
            }
        } finally {
            closedir(d)
        }
        return out
    }

    fun parent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    /** [path] with the home directory shown as "~". */
    fun pretty(path: String): String = if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home) else path

    /** A file name from a song title: letters, digits, spaces, '_' and '-' only. */
    fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(80).ifBlank { "Untitled" }
}

/**
 * A controller-driven file browser in the shared popups (no system dialog, so it works in Gaming Mode). Lists
 * folders first, then files with one of [extensions]; choosing a file calls [picked]. In folder mode
 * ([saveName] set), the first row saves [saveName] in the folder shown, asking before replacing a file.
 */
class FileBrowser(
    private val popups: Popups,
    private val title: String,
    private val extensions: List<String>,
    private val saveName: String? = null,
    private val picked: (String) -> Unit,
) {
    fun show(start: String) = popups.show(spec(start), 0)

    private fun spec(dir: String): PopupSpec {
        val names = Files.list(dir).orEmpty().filter { !it.startsWith(".") }.sortedBy { it.lowercase() }
        val folders = names.filter { Files.isDirectory("$dir/$it") }
        val files = names.filter { name -> extensions.any { name.endsWith(it, ignoreCase = true) } && !Files.isDirectory("$dir/$name") }
        val items = ArrayList<PopupItem>()
        fun saveTo(name: String) {
            val path = "$dir/$name"
            if (Files.exists(path)) {
                popups.show(PopupSpec("Replace $name?", message = "A file with this name is already in ${Files.pretty(dir)}.",
                    items = listOf(PopupItem("Replace", PopupItem.Tone.DANGER) { picked(path) }, PopupItem.dismiss()), selected = 1), 0)
            } else picked(path)
        }
        if (saveName != null) items += PopupItem("Save here as $saveName", PopupItem.Tone.PRIMARY) { saveTo(saveName) }
        if (dir != "/") items += PopupItem("‹ Up to ${Files.pretty(Files.parent(dir))}") { popups.replaceTop(spec(Files.parent(dir))) }
        if (folders.isNotEmpty()) items += PopupItem.heading("Folders")
        for (f in folders) items += PopupItem("$f/") { popups.replaceTop(spec("$dir/$f")) }
        if (saveName == null) {
            items += PopupItem.heading(if (files.isEmpty()) "No ${extensions.joinToString(" or ")} files here" else "Files")
            for (f in files) items += PopupItem(f) { picked("$dir/$f") }
        } else if (files.isNotEmpty()) {
            items += PopupItem.heading("Already here (choose one to replace it)")
            for (f in files) items += PopupItem(f) { saveTo(f) }
        }
        items += PopupItem.dismiss()
        return PopupSpec("$title · ${Files.pretty(dir)}", items = items, selected = 0)
    }
}
