@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import kotlinx.cinterop.*
import org.thortracker.audio.WavExport
import org.thortracker.audio.WavOptions
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
import org.thortracker.ui.RecentMessages
import platform.posix.remove
import platform.posix.uname
import platform.posix.utsname
import kotlin.time.TimeSource
import decknative.ym2608_rhythm_rom_install
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom

/**
 * The Deck's platform side of the shared menus: projects and exports in ~/ThorTracker via the in-app [FileBrowser],
 * working-song recovery, keymap and imported firmware (the YM2608 rhythm ROM, firmware/) in ~/.local/share/thortracker,
 * bundled tunings beside the executable. WAV exports render in slices of the frame loop ([pumpExport]) behind a
 * progress popup.
 */
class DeckShell(
    private val session: EditorSession,
    private val pane: PaneView,
    private val popups: Popups,
    private val input: HostInput,
    private val appDir: String,
    private val quit: () -> Unit,
    private val audio: DeckAudio,
    /** The output for the diagnostics: size, density, video driver. */
    private val display: () -> String,
) : MenuHost {
    private val documents = "${Files.home}/ThorTracker"
    private val data = "${Files.home}/.local/share/thortracker"
    private val working = "$data/working.thor"
    private val previous = "$data/previous.thor"
    private val keymapFile = "$data/keymap.json"
    /** Where a WAV export renders before it moves to its destination (removed when the export ends, and at startup). */
    private val wavSpool = "$data/wav-export.wav.part"
    /** Device firmware the user imports (the YM2608 rhythm ROM of the NES EPSM and OPNA drums): kept here, never in projects or exports. */
    private val firmware = "$data/firmware"
    private val rhythmRom = "$firmware/${Ym2608RhythmRom.FILE_NAME}"

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
        remove(wavSpool)   // a render the last run did not live to finish
        Files.readText(keymapFile)?.let { text -> runCatching { Keymap.fromJson(text) }.getOrNull() }?.let { applyKeymap(it, null) }
        loadRhythmRom()
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
    override fun status(message: String) { RecentMessages.record("status", message); session.note(message); pane.refresh() }

    override fun error(message: String) {
        RecentMessages.record("error", message)
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

    override fun exportWav(song: Song, options: WavOptions) =
        FileBrowser(popups, "Export .wav", listOf(".wav"), saveName = Files.safeName(song.title) + ".wav") { path ->
            startWav(path, song, options)
        }.show(documents)

    override fun showWavExport(): Boolean {
        val run = wav ?: return false
        popups.closeAll()
        run.popup = null
        showProgress(run)
        return true
    }

    /** The WAV export in progress: its destination, progress popup (null until shown) and cancel request. */
    private class WavRun(val export: WavExport, val path: String) {
        var popup: PopupSpec? = null
        var cancelled = false
        var shownAt = TimeSource.Monotonic.markNow()
    }

    private var wav: WavRun? = null

    private fun startWav(path: String, song: Song, options: WavOptions) {
        if (wav != null) return error("A WAV export is already running")
        val export = try {
            WavExport(song, options, wavSpool)
        } catch (e: IllegalArgumentException) {
            return error("WAV export failed: ${e.message}")
        } catch (e: IllegalStateException) {
            return error("WAV export failed: ${e.message}")
        }
        val run = WavRun(export, path)
        wav = run
        popups.closeAll()   // the file browser: the progress popup stands alone, so it leaves nothing behind
        showProgress(run)
    }

    /** Shows or refreshes the progress popup, unless Back hid it (or another popup covers it). */
    private fun showProgress(run: WavRun) {
        val spec = run.export.popup { run.cancelled = true }
        when {
            run.popup == null -> popups.show(spec, 0)
            popups.top === run.popup -> popups.replaceTop(spec)
            else -> return
        }
        run.popup = spec
        run.shownAt = TimeSource.Monotonic.markNow()
    }

    /**
     * Call every frame: renders the running WAV export for about [budgetMs] milliseconds (the frame loop stays
     * responsive), refreshes its popup a few times a second and writes the file when it is complete.
     */
    fun pumpExport(budgetMs: Long = 8) {
        val run = wav ?: return
        val start = TimeSource.Monotonic.markNow()
        try {
            while (!run.cancelled && !run.export.done && start.elapsedNow().inWholeMilliseconds < budgetMs) run.export.step()
        } catch (e: IllegalStateException) {
            endWav(run)
            return error("WAV export failed: ${e.message}")
        }
        if (run.cancelled) {
            endWav(run)
            return status("WAV export cancelled")
        }
        if (!run.export.done) {
            if (run.shownAt.elapsedNow().inWholeMilliseconds >= 100) showProgress(run)
            return
        }
        run.export.close()   // complete: the spool stays for the move
        val ok = Files.move(wavSpool, run.path)
        endWav(run)
        val message = "WAV exported to ${Files.pretty(run.path)} (${run.export.summary()})"
        when {
            !ok -> error("Could not write ${Files.pretty(run.path)}")
            run.export.capped -> error(message)   // not the whole song: say so where it cannot be missed
            else -> status(message)
        }
    }

    /** Ends [run]: frees the job (an incomplete spool goes with it) and closes its popup if it is on top. */
    private fun endWav(run: WavRun) {
        run.export.close()
        wav = null
        if (run.popup != null && popups.top === run.popup) popups.back()
    }

    override fun diagnostics(): List<Pair<String, String>> = listOf(
        "Platform" to "Steam Deck / desktop Linux: ${osName()}",
        "Device" to (Files.readText("/sys/devices/virtual/dmi/id/product_name")?.trim()?.ifEmpty { null } ?: "unknown"),
        "Display" to display(),
    ) + audio.diagnostics()

    override fun shareDiagnostics(text: String) {
        val path = "$documents/diagnostics.txt"
        if (Files.write(path, text.encodeToByteArray())) status("Diagnostics written to ${Files.pretty(path)}") else error("Could not write ${Files.pretty(path)}")
    }

    /** "SteamOS 3.6 (Linux 6.5.0-valve22)": os-release's name and the kernel. */
    private fun osName(): String {
        val pretty = Files.readText("/etc/os-release")?.lineSequence()?.firstOrNull { it.startsWith("PRETTY_NAME=") }
            ?.substringAfter('=')?.trim('"')
        val kernel = memScoped {
            val u = alloc<utsname>()
            if (uname(u.ptr) == 0) "${u.sysname.toKString()} ${u.release.toKString()} ${u.machine.toKString()}" else "Linux"
        }
        return if (pretty != null) "$pretty ($kernel)" else kernel
    }

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

    /** [MenuHost.installRhythmRom]: saves the checked [bytes] (temporary file and rename), then installs them. */
    override fun installRhythmRom(bytes: ByteArray): String? {
        Files.mkdirs(firmware)
        if (!Files.write(rhythmRom, bytes)) return "it could not be saved in ${Files.pretty(firmware)}"
        return installRhythmRomNative(bytes)
    }

    /** Hands checked ROM [bytes] to the native engine and marks the ROM installed; returns why not, or null. */
    private fun installRhythmRomNative(bytes: ByteArray): String? {
        val result = bytes.usePinned { ym2608_rhythm_rom_install(it.addressOf(0).reinterpret<UByteVar>(), bytes.size.convert()) }
        if (result != 0) return "the audio engine refused it (error $result)"
        Ym2608RhythmRom.markInstalled()
        return null
    }

    /** At start: installs a ROM imported earlier after checking it again; a changed or damaged file is reported, not used. */
    private fun loadRhythmRom() {
        if (!Files.exists(rhythmRom)) return
        val bytes = Files.readBytes(rhythmRom, max = RhythmRomMenu.MAX_FILE.toLong())
        val problem = if (bytes == null) "it could not be read" else Ym2608RhythmRom.problem(bytes) ?: installRhythmRomNative(bytes)
        if (problem != null) error("The saved YM2608 rhythm ROM (${Files.pretty(rhythmRom)}) was not installed: $problem. " +
            "Import it again (${RhythmRomMenu.LABEL} in the menu)")
    }

    override fun extraMenuItems(): List<Pair<String, () -> Unit>> = listOf("Quit" to quit)

    private companion object {
        /** Song view header row of ticks per row (as on Android). */
        const val SONG_TEMPO_ITEM = 2
    }
}
