package org.thortracker.deck

import org.thortracker.system.ExportFormat
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.input.Keymap
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Scl
import org.thortracker.model.Song
import org.thortracker.model.Tuning
import org.thortracker.ui.HostInput
import org.thortracker.ui.MenuHost
import org.thortracker.ui.PaneView
import org.thortracker.ui.PopupItem
import org.thortracker.ui.PopupSpec
import org.thortracker.ui.Popups

/**
 * The Deck's platform side of the shared menus: projects and exports in ~/ThorTracker via the in-app [FileBrowser],
 * working-song recovery and keymap in ~/.local/share/thortracker, bundled tunings beside the executable.
 */
class DeckShell(
    private val session: EditorSession,
    private val pane: PaneView,
    private val popups: Popups,
    private val input: HostInput,
    private val appDir: String,
    private val quit: () -> Unit,
) : MenuHost {
    private val documents = "${Files.home}/ThorTracker"
    private val data = "${Files.home}/.local/share/thortracker"
    private val working = "$data/working.thor"
    private val previous = "$data/previous.thor"
    private val keymapFile = "$data/keymap.json"

    /** The project file the working song was opened from or last saved to. */
    private var currentFile: String? = null
    private var dirtySince = 0uL
    private var savedSong: Song? = null

    override val modalDisplay: Int get() = 0
    override val displayCount: Int get() = 1
    override var keymap: Keymap = Keymap.DEFAULT
        private set

    init {
        Files.mkdirs(documents)
        Files.mkdirs(data)
        Files.readText(keymapFile)?.let { text -> runCatching { Keymap.fromJson(text) }.getOrNull() }?.let { applyKeymap(it, null) }
    }

    /** The song to start with: the recovered working song, or null for the demo. */
    fun recovered(): Song? = Files.readText(working)?.let { runCatching { ProjectCodec.decode(it) }.getOrNull() }

    /** Call every frame with the clock: writes the working song a second after it last changed. */
    fun autosave(now: ULong, force: Boolean = false) {
        val song = session.song
        if (song === savedSong) return
        if (dirtySince == 0uL) dirtySince = now
        if (!force && now - dirtySince < 1000uL) return
        dirtySince = 0uL
        if (Files.write(working, ProjectCodec.encode(song).encodeToByteArray())) savedSong = song
    }

    override fun refresh() = pane.refresh()
    override fun status(message: String) { session.note(message); pane.refresh() }

    override fun error(message: String) {
        popups.show(PopupSpec("Thor Tracker", message = message, items = listOf(PopupItem("OK", PopupItem.Tone.PRIMARY) {})), 0)
    }

    override fun applyKeymap(next: Keymap, message: String?) {
        keymap = next
        input.keymap = next
        input.reset()
        popups.keys = next
        pane.keymap = next
        Files.write(keymapFile, next.toJson().encodeToByteArray())
        message?.let(::status)
    }

    override fun resetInput() = input.reset()
    override fun editAction(action: Action) { pane.press(action); pane.release(action) }
    override fun switchFocus() {}
    override fun swap() {}

    override fun showSongDetails() {
        pane.cancelInteraction()
        val state = pane.state
        state.switchKind(ViewKind.SONG)
        state.pinned = true
        pane.refresh()
        state.songItem = SONG_TEMPO_ITEM
        state.textPos = 0
        status("Song details: tempo (ticks per row) selected")
    }

    override fun replace(song: Song) {
        Files.write(previous, ProjectCodec.encode(session.song).encodeToByteArray())
        session.replaceSong(song)
        currentFile = null
        autosave(0uL, force = true)
        status("${song.title} loaded")
    }

    override fun openProject() = FileBrowser(popups, "Open project", listOf(".thor")) { path ->
        val song = Files.readText(path)?.let { runCatching { ProjectCodec.decode(it) } }
        song?.fold({ replace(it); currentFile = path }, { error("Not opened: ${it.message}") }) ?: error("Not opened: $path could not be read")
    }.show(documents)

    override fun saveProject() { currentFile?.let(::writeProject) ?: saveProjectAs() }

    override fun saveProjectAs() =
        FileBrowser(popups, "Save project", listOf(".thor"), saveName = Files.safeName(session.song.title) + ".thor", picked = ::writeProject).show(documents)

    private fun writeProject(path: String) {
        if (Files.write(path, ProjectCodec.encode(session.song).encodeToByteArray())) {
            currentFile = path
            status("Project saved to ${Files.pretty(path)}")
        } else error("Could not write ${Files.pretty(path)}")
    }

    override fun export(song: Song, format: ExportFormat) =
        FileBrowser(popups, "Export .${format.extension}", listOf(".${format.extension}"), saveName = Files.safeName(song.title) + ".${format.extension}") { path ->
            if (Files.write(path, format.export(song))) status("${format.extension.uppercase()} exported to ${Files.pretty(path)}") else error("Could not write ${Files.pretty(path)}")
        }.show(documents)

    override fun recoverPrevious() {
        val song = Files.readText(previous)?.let { runCatching { ProjectCodec.decode(it) }.getOrNull() }
            ?: return error("There is no previous project to recover")
        replace(song)
    }

    override fun exportKeymap() =
        FileBrowser(popups, "Export keymap", listOf(".json"), saveName = "thor-tracker-keymap.json") { path ->
            if (Files.write(path, keymap.toJson().encodeToByteArray())) status("Keymap exported") else error("Could not write ${Files.pretty(path)}")
        }.show(documents)

    override fun importKeymap() = FileBrowser(popups, "Import keymap", listOf(".json")) { path ->
        val text = Files.readText(path) ?: return@FileBrowser error("Could not read ${Files.pretty(path)}")
        runCatching { Keymap.fromJson(text) }.fold({ applyKeymap(it, "Keymap imported") }, { error("Keymap import failed: ${it.message}") })
    }.show(documents)

    override fun builtInTunings(): List<Tuning> {
        val dir = "$appDir/tunings"
        return Files.list(dir).orEmpty().filter { it.endsWith(".scl") }.sorted().mapNotNull { file ->
            Files.readText("$dir/$file")?.let { runCatching { Scl.parse(it, file.removeSuffix(".scl")) }.getOrNull() }
        }
    }

    override fun loadTuningFile(loaded: (Tuning) -> Unit) = FileBrowser(popups, "Load .scl", listOf(".scl")) { path ->
        val bytes = Files.readBytes(path, max = 64L * 1024) ?: return@FileBrowser error("Tuning not loaded: the file could not be read or is too large")
        runCatching { Scl.parse(bytes.decodeToString(), path.substringAfterLast('/').substringBeforeLast('.')) }
            .fold(loaded) { error("Tuning not loaded: ${it.message}") }
    }.show(documents)

    override fun loadFile(title: String, extensions: List<String>, maxBytes: Int, loaded: (String, String, ByteArray) -> Unit) =
        FileBrowser(popups, title, extensions) { path ->
            val file = path.substringAfterLast('/')
            val extension = extensions.firstOrNull { file.endsWith(it, ignoreCase = true) } ?: return@FileBrowser error("Choose a ${extensions.joinToString(" or ")} file")
            val bytes = Files.readBytes(path, max = maxBytes.toLong()) ?: return@FileBrowser error("$file could not be read or is larger than ${maxBytes / 1024} KiB")
            loaded(file.dropLast(extension.length), extension, bytes)
        }.show(documents)

    override fun extraMenuItems(): List<Pair<String, () -> Unit>> = listOf("Quit" to quit)

    private companion object {
        /** Song view header row of ticks per row (as on Android). */
        const val SONG_TEMPO_ITEM = 2
    }
}
