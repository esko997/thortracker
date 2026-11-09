package org.thortracker.ui

import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Follow
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.ControlGroup
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.Tuning
import org.thortracker.model.TuningMap
import org.thortracker.system.ExportFormat
import org.thortracker.system.SoundSystem
import org.thortracker.system.SoundSystems
import org.thortracker.system.nes.EpsmRhythmRom
import org.thortracker.system.nes.EpsmRomMenu
import org.thortracker.system.nes.NesExpansion
import org.thortracker.system.nes.NesSound

/**
 * Everything a frontend's menus need from its platform: files, displays, persistence and status. The shared
 * [AppMenus] decides what the menus contain and what their choices do.
 */
interface MenuHost {
    /** The display popups open on (the touched one, or the controller-focused one). */
    val modalDisplay: Int
    /** How many displays show panes (2 on the Thor; 1 elsewhere). */
    val displayCount: Int
    val keymap: Keymap

    /** Re-reads panes and session for every display (and saves the pane layout). */
    fun refresh()
    fun status(message: String)
    fun error(message: String)
    fun applyKeymap(next: Keymap, message: String?)
    /** Forgets held buttons (after a capture, whose key-ups never reach the editor). */
    fun resetInput()
    /** Runs an editor command in the focused pane (menu shortcuts for Copy, Undo...). */
    fun editAction(action: Action)
    fun switchFocus()
    fun swap()
    /** Shows the Song view with its tempo row selected on the display the menu came from. */
    fun showSongDetails()

    /** Replaces the working document (the old one becomes the previous-project backup). */
    fun replace(song: Song)
    fun openProject()
    /** Saves to the current project file, or asks where when there is none. */
    fun saveProject()
    fun saveProjectAs()
    /** Asks where and exports [song] as [format]; [song] validated without errors. */
    fun export(song: Song, format: ExportFormat)
    /**
     * Renders [song] as a WAV file with [options] through the native engine ([org.thortracker.audio.WavExport]: off the
     * UI's work, spooled to a private file, with a progress popup that can cancel it) and saves it where the user
     * chooses: Android renders first and then asks for the destination, the Deck asks first. [song] validated without
     * errors; never called while [showWavExport] reports a running export.
     */
    fun exportWav(song: Song, options: WavOptions)
    /** Shows the running WAV export's popup again (Hide closed it); false when no export runs. */
    fun showWavExport(): Boolean
    fun recoverPrevious()
    fun exportKeymap()
    fun importKeymap()
    /** The Scala tunings bundled with the app. */
    fun builtInTunings(): List<Tuning>
    /** Lets the user choose a .scl file; [loaded] receives it parsed (errors go through [error]). */
    fun loadTuningFile(loaded: (Tuning) -> Unit)
    /**
     * Lets the user choose a file ending in one of [extensions] (lower case, with the dot); [loaded] receives its
     * name without the extension, the extension and its bytes. Unreadable files, files over [maxBytes] and other
     * extensions go through [error] instead.
     */
    fun loadFile(title: String, extensions: List<String>, maxBytes: Int, loaded: (name: String, extension: String, bytes: ByteArray) -> Unit)
    /**
     * Keeps [bytes], a checked YMF288 rhythm ROM ([org.thortracker.system.nes.EpsmRhythmRom.problem] found nothing), as
     * this device's firmware (`firmware/ym2608_adpcm_rom.bin` in the app's data, never in a project or export), installs
     * it in the native engine and marks it installed ([org.thortracker.system.nes.EpsmRhythmRom.markInstalled]).
     * Returns why it could not, or null.
     */
    fun installEpsmRhythmRom(bytes: ByteArray): String?
    /** Platform rows at the end of the main menu (e.g. Quit on the Deck). */
    fun extraMenuItems(): List<Pair<String, () -> Unit>> = emptyList()

    /** Opens [url] in the platform's browser; false when it cannot (the menus then show the address instead). */
    fun openUrl(url: String): Boolean = false

    /** The platform's diagnostics lines ([About.diagnostics]): device, OS, displays, audio statistics. */
    fun diagnostics(): List<Pair<String, String>>

    /** Gives the diagnostics [text] to the user: Android copies it to the clipboard, the Deck writes a file. */
    fun shareDiagnostics(text: String)
}

/** The app's menus: main menu, views, controls rebinding, tuning and files, over a platform [MenuHost]. */
class AppMenus(
    private val session: EditorSession,
    private val panes: Workspace,
    private val transport: Transport,
    private val popups: Popups,
    private val host: MenuHost,
) {
    private val keymap: Keymap get() = host.keymap
    private val states get() = panes.panes
    private val focus get() = panes.focus

    fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int = 0) {
        popups.show(PopupSpec(title, items = entries.map { (label, action) -> PopupItem(label, action = action) } +
            PopupItem.dismiss(), selected = selected), host.modalDisplay)
    }

    fun choose(title: String, labels: List<String>, selected: (Int) -> Unit) =
        menu(title, labels.mapIndexed { index, label -> label to { selected(index) } })

    /** The menus a view opens through [EditorSession.requestMenu]; hosts route `session.onMenuRequest` to [open]. */
    private val context = object : MenuContext {
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) = this@AppMenus.menu(title, entries, selected)
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) =
            host.loadFile(title, extensions, maxBytes, picked)
        override fun status(message: String) = host.status(message)
        override fun error(message: String) = host.error(message)
        override fun installEpsmRhythmRom(bytes: ByteArray): String? {
            val problem = host.installEpsmRhythmRom(bytes)
            if (problem == null) transport.songChanged(session.song)
            return problem
        }
        override fun about() = this@AppMenus.about()
    }

    /** Runs a view's menu request ([EditorSession.onMenuRequest]). */
    fun open(build: (MenuContext) -> Unit) = build(context)

    fun chooseView(index: Int) {
        val kinds = ViewKind.entries.filter { it in session.song.system.views }
        choose("View on display ${index + 1}", kinds.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { selected ->
            states[index].switchKind(kinds[selected])
            states[index].pinned = true
            host.refresh()
        }
    }

    fun viewOptions(index: Int) {
        val state = states[index]
        choose("View behavior", buildList {
            add(if (state.pinned) "Allow contextual view changes" else "Pin this view")
            add(if (state.followSelection) "Stop following selection" else "Follow selected item")
            add(if (state.lockedSubject >= 0) "Unlock subject" else "Lock to this item")
            if (states.size > 1) addAll(listOf("Swap complete views", "Pattern + keyboard layout", "Pattern + instrument layout"))
            add(if (state.followPlayback) "Stop following playhead" else "Follow playhead")
        }) { chosen ->
            // Single-pane frontends have no layout rows: their last row is "follow playhead".
            val selected = if (states.size > 1 || chosen < 3) chosen else chosen + 3
            when (selected) {
                0 -> state.pinned = !state.pinned
                1 -> { state.followSelection = !state.followSelection; if (state.followSelection) state.lockedSubject = -1 }
                2 -> {
                    if (state.lockedSubject >= 0) state.lockedSubject = -1
                    else {
                        state.lockedSubject = Follow.subject(state, session.song)
                        state.followSelection = false
                    }
                }
                3 -> host.swap()
                4, 5 -> {
                    states[0] = ViewState(kind = ViewKind.PATTERN)
                    states[1] = ViewState(kind = if (selected == 4) ViewKind.KEYBOARD else ViewKind.INSTRUMENT)
                }
                6 -> state.followPlayback = !state.followPlayback
            }
            host.refresh()
        }
    }

    fun appMenu() {
        menu("Thor Tracker", buildList {
            add("Files" to ::fileMenu)
            add("Song details / tempo" to host::showSongDetails)
            // The EPSM's drum ROM is device firmware the user supplies; offered (with its state) while an EPSM song is open.
            if ((session.song.sound as? NesSound)?.expansion == NesExpansion.EPSM) {
                add(EpsmRomMenu.LABEL + (if (EpsmRhythmRom.installed) "  installed" else "  missing") to { open(EpsmRomMenu::open) })
            }
            add("Views / layout" to { viewOptions(focus) })
            if (host.displayCount > 1) add("Switch controller focus" to host::switchFocus)
            add("Play from song start" to { transport.start(0, 0) })
            add("Copy selection" to { host.editAction(Action.COPY) })
            add("Cut selection" to { host.editAction(Action.CUT) })
            add("Paste" to { host.editAction(Action.PASTE) })
            add("Duplicate" to { host.editAction(Action.DUPLICATE) })
            add("Undo" to { host.editAction(Action.UNDO) })
            add("Redo" to { host.editAction(Action.REDO) })
            add("Entry step" to {
                choose("Advance after note entry", listOf("Stay", "1 row", "2 rows", "4 rows", "8 rows", "16 rows")) {
                    session.step = intArrayOf(0, 1, 2, 4, 8, 16)[it]; host.refresh()
                }
            })
            add("Tuning" to ::tuningMenu)
            add("Controls" to ::showControls)
            add("Help" to { states[focus].switchKind(ViewKind.HELP); states[focus].pinned = true; host.refresh() })
            addAll(host.extraMenuItems())
        })
    }

    // ---- controls -------------------------------------------------------------------------------

    /** One row per control, grouped by area: its job, then its current binding; choosing a row rebinds it. */
    fun controlsSpec(): PopupSpec {
        val items = ArrayList<PopupItem>()
        var group: ControlGroup? = null
        for (c in Control.entries) {
            if (c.group != group) { group = c.group; items += PopupItem.heading(c.group.label) }
            val bound = keymap.binding(c) != null
            items += PopupItem(c.label, if (bound) PopupItem.Tone.PLAIN else PopupItem.Tone.DANGER, value = keymap.label(c)) { captureBinding(c) }
        }
        items += PopupItem.heading("Keymap file")
        items += PopupItem("Export keymap…") { host.exportKeymap() }
        items += PopupItem("Import keymap…") { host.importKeymap() }
        items += PopupItem("Reset all to defaults", PopupItem.Tone.DANGER) {
            popups.show(PopupSpec("Reset all controls?", message = "Every binding returns to its default.",
                items = listOf(PopupItem("Reset", PopupItem.Tone.DANGER) {
                    popups.back()
                    host.applyKeymap(Keymap.DEFAULT, "Default controls restored")
                    popups.replaceTop(controlsSpec())
                }, PopupItem.dismiss()), selected = 1), host.modalDisplay)
        }
        items += PopupItem.dismiss()
        return PopupSpec("Controls", message = "Choose a row, then press its new button — or hold Select, L2, R2 or L3 " +
            "and press a button for a chord. Menu and Back are fixed, so this screen is always reachable.",
            items = items, selected = 1)
    }

    fun showControls() = popups.show(controlsSpec(), host.modalDisplay)

    /** Waits for one button or one modifier+button chord for [control]; Back, Escape or Menu leaves it unchanged. */
    fun captureBinding(control: Control) {
        val held = LinkedHashSet<Int>()
        var finished = false
        val default = Keymap.DEFAULT.binding(control)
        fun finish(binding: Binding?) {
            finished = true
            popups.back()
            host.resetInput()
            if (binding == null) { host.status("Binding unchanged") } else finishCapture(control, binding)
        }
        popups.show(PopupSpec(control.label,
            message = "Press the new button now. Current: ${keymap.label(control)}." +
                (if (control.hold) " It is held with the D-pad, so it takes a single button."
                else " For a chord, hold Select, L2, R2 or L3, then press the button.") + " Back or Menu cancels.",
            items = listOfNotNull(
                default?.let { PopupItem("Use default (${Keymap.label(it)})") { finish(it) } },
                PopupItem.dismiss(),
            ),
            capture = { code, down ->
                when {
                    finished -> Unit
                    // Back and Menu can never be bound, so either cancels (the Deck has no Back button).
                    code == Keys.BACK || code == Keys.ESCAPE || code == Keys.MENU || code == Keys.BUTTON_MODE -> if (down) finish(null)
                    code in Keymap.MODIFIERS -> if (down) held += code else held -= code
                    !down -> Unit
                    held.size > 1 -> { finish(null); host.error("Hold one of Select, L2, R2 or L3 for a chord, not several") }
                    else -> finish(Binding(code, held.firstOrNull()))
                }
            }), host.modalDisplay)
    }

    /** Applies a captured [binding], asking before taking it from another control. */
    private fun finishCapture(control: Control, binding: Binding) {
        keymap.problem(control, binding)?.let { return host.error(it) }
        if (keymap.binding(control) == binding) { host.status("${control.short}: unchanged"); return }
        val owners = keymap.conflicts(control, binding)
        if (owners.isEmpty()) return assign(control, binding, emptyList())
        val chord = Keymap.label(binding)
        owners.firstOrNull { it.essential }?.let { owner ->
            return host.error("$chord is ${owner.label}, which always needs a button. Rebind ${owner.short} first, or choose another button.")
        }
        val names = owners.joinToString(" and ") { it.short }
        popups.show(PopupSpec("$chord is $names", message = "Use $chord for ${control.label}? ${owners.joinToString(" and ") { it.label }} will have no binding until you set one.",
            items = listOf(PopupItem("Replace", PopupItem.Tone.PRIMARY) { popups.back(); assign(control, binding, owners) }, PopupItem.dismiss()),
            selected = 1), host.modalDisplay)
    }

    private fun assign(control: Control, binding: Binding, displaced: List<Control>) {
        var next = keymap.with(control, binding)
        for (d in displaced) next = next.with(d, null)
        host.applyKeymap(next, "${control.short}: ${Keymap.label(binding)}" +
            (if (displaced.isEmpty()) "" else " (${displaced.joinToString(" and ") { it.short }} now unbound)"))
        if (popups.top?.title == "Controls") popups.replaceTop(controlsSpec())
    }

    // ---- tuning ---------------------------------------------------------------------------------

    /** Song tuning: load a Scala file, choose where 1/1 sounds and the accuracy limit, fit notes, or remove it. */
    fun tuningMenu() {
        val t = session.song.tuning
        val map = session.tuningMap
        menu(if (t == null) "Tuning: 12-note (none)" else "Tuning: ${t.name}", buildList {
            add("Built-in tunings…" to ::builtInTunings)
            add("Load .scl file…" to {
                val previous = session.song.tuning
                host.loadTuningFile { parsed -> applyTuning(if (previous == null) parsed else parsed.copy(root = previous.root, tolerance = previous.tolerance)) }
            })
            if (t != null && map != null) {
                add("1/1 sounds on ${session.song.system.noteName(t.root)}…" to {
                    val range = session.song.system.noteRange
                    menu("1/1 sounds on", range.map { n -> session.song.system.noteName(n) to { applyTuning(t.copy(root = n)) } }, selected = t.root - range.first)
                })
                add("Accuracy limit: ${t.tolerance}¢…" to {
                    choose("Accuracy limit", TOLERANCES.map { "$it cents" + if (it == 5) " (default)" else "" }) { applyTuning(t.copy(tolerance = TOLERANCES[it])) }
                })
                add("Fit notes in this pattern" to { fitTuning(listOf(states[focus].let { session.song.orders[it.order.coerceIn(0, session.song.orders.size - 1)] })) })
                add("Fit notes in whole song" to { fitTuning(session.song.patterns.indices.toList()) })
                add("Remove tuning (back to 12-note)" to { session.setTuning(null); host.status("Tuning removed; written notes unchanged") })
            }
        })
    }

    /** The bundled Scala files, listed by their own descriptions; keeps the current 1/1 and accuracy limit. */
    private fun builtInTunings() {
        val previous = session.song.tuning
        menu("Built-in tunings", host.builtInTunings().map { t ->
            t.name to { applyTuning(if (previous == null) t else t.copy(root = previous.root, tolerance = previous.tolerance)) }
        })
    }

    fun applyTuning(tuning: Tuning) {
        session.setTuning(tuning)
        host.status(tuningSummary(TuningMap(tuning, session.song.system)))
    }

    /** "24-TET: 118 of 144 degrees reachable within 5¢ (C-3..B-8)". */
    private fun tuningSummary(map: TuningMap) =
        "${map.tuning.name}: ${map.pitches.size} of ${map.all.size} degrees reachable within ${map.tuning.tolerance}¢"

    private fun fitTuning(patterns: List<Int>) {
        val r = session.fitToTuning(patterns)
        host.status("Fitted ${r.moved} note${if (r.moved == 1) "" else "s"}" +
            (if (r.blocked > 0) "; ${r.blocked} left: their row's effect slot is needed for ${session.song.system.fine.name}" else ""))
    }

    // ---- files ----------------------------------------------------------------------------------

    fun fileMenu() {
        val system = session.song.system
        menu("Project", listOf(
            "New blank song" to { chooseSystem("New song for") { confirmReplace { host.replace(it.blank()) } } },
            "Open project" to host::openProject,
            "Save" to host::saveProject,
            "Save as…" to host::saveProjectAs,
        ) + system.exports.map { format -> "Export .${format.extension}" to { export(format) } } + listOf(
            "Export .wav…" to ::exportWav,
            "Rename song" to ::rename,
            "Load demo" to { chooseSystem("Demo for") { confirmReplace { host.replace(it.demo()) } } },
            "Recover previous project" to { confirmReplace { host.recoverPrevious() } },
        ))
    }

    /**
     * Asks which sound chip a new song targets (the cursor starts on the current song's), skipped while the build knows
     * one. A family of variants (the NES with and without expansion chips) is one entry that opens its variants; within
     * it, variants of one group (the NES + N163's channel counts) are one entry that opens the group.
     */
    private fun chooseSystem(title: String, chosen: (SoundSystem) -> Unit) {
        val systems = SoundSystems.ALL
        val current = session.song.system
        if (systems.size == 1) return chosen(current)
        val (entries, selected) = grouped(systems, current, { it.family }, chosen) { family, variants ->
            val (inner, innerSelected) = grouped(variants, current, { it.familyGroup }, chosen) { group, members ->
                menu("$title $group", members.map { it.name to { chosen(it) } }, selected = members.indexOf(current).coerceAtLeast(0))
            }
            menu("$title $family", inner, innerSelected)
        }
        menu(title, entries, selected)
    }

    /**
     * Menu entries for [systems]: each system on its own ([chosen] picks it), except systems [key] puts in one group,
     * listed once as "group…", which [open]s with the group's members; and the index of the entry holding [current].
     */
    private fun grouped(
        systems: List<SoundSystem>,
        current: SoundSystem,
        key: (SoundSystem) -> String?,
        chosen: (SoundSystem) -> Unit,
        open: (String, List<SoundSystem>) -> Unit,
    ): Pair<List<Pair<String, () -> Unit>>, Int> {
        val entries = ArrayList<Pair<String, () -> Unit>>()
        var selected = 0
        val seen = HashSet<String>()
        for (system in systems) {
            val k = key(system)
            if (k == null) {
                if (system == current) selected = entries.size
                entries += system.name to { chosen(system) }
            } else if (seen.add(k)) {
                val members = systems.filter { key(it) == k }
                if (current in members) selected = entries.size
                entries += "$k…" to { open(k, members) }
            }
        }
        return entries to selected
    }

    private fun export(format: ExportFormat) {
        val song = session.song
        val errors = song.system.errors(song)
        if (errors.isNotEmpty()) return host.error(errors.joinToString("\n"))
        host.export(song, format)
    }

    /** Files → Export .wav: the running export's popup, or how long, then the host renders and saves. */
    private fun exportWav() {
        if (host.showWavExport()) return
        val song = session.song
        val errors = song.system.errors(song)
        if (errors.isNotEmpty()) return host.error(errors.joinToString("\n"))
        choose("Export .wav: length", WavOptions.CHOICES.map { it.label }) { host.exportWav(song, WavOptions.CHOICES[it]) }
    }

    /** Help → About's actions: report a bug (the issue form) and the diagnostics text. */
    fun about() {
        popups.show(PopupSpec("Thor Tracker ${About.version}",
            message = "Bug reports go to GitHub's issue form. Copy diagnostics makes a plain-text report (version, device, " +
                "displays, audio statistics, recent messages) for you to paste into it; nothing is sent anywhere.",
            items = listOf(
                PopupItem("Report a bug…", PopupItem.Tone.PRIMARY) { reportBug() },
                PopupItem("Copy diagnostics") { host.shareDiagnostics(About.diagnostics(host.diagnostics(), session.song, keymap)) },
                PopupItem.dismiss(),
            )), host.modalDisplay)
    }

    private fun reportBug() {
        if (host.openUrl(About.ISSUES_URL)) return
        popups.show(PopupSpec("Report a bug", message = "Open ${About.ISSUES_URL} in a browser and choose Bug report. " +
            "Copy diagnostics first and paste the text into the form.",
            items = listOf(PopupItem("OK", PopupItem.Tone.PRIMARY))), host.modalDisplay)
    }

    private fun rename() {
        val title = PopupText(session.song.title, 255)
        popups.show(PopupSpec("Song title", text = title, items = listOf(
            PopupItem("Rename", PopupItem.Tone.PRIMARY) { session.setTitle(title.value.take(255)); host.refresh() },
            PopupItem.dismiss(),
        )), host.modalDisplay)
    }

    /** Asks before [action] replaces the working song. */
    fun confirmReplace(action: () -> Unit) {
        popups.show(PopupSpec("Replace working song?",
            message = "The current song will replace the single previous-project recovery backup. Save it to a project file if you want to keep it permanently.",
            items = listOf(PopupItem("Continue", PopupItem.Tone.DANGER, action = action), PopupItem.dismiss()),
            selected = 1), host.modalDisplay)
    }

    companion object {
        val TOLERANCES = listOf(5, 10, 20)
    }
}
