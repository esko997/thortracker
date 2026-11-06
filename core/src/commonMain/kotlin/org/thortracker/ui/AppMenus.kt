package org.thortracker.ui

import org.thortracker.audio.Transport
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.ControlGroup
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.Song
import org.thortracker.model.SongValidator
import org.thortracker.model.Songs
import org.thortracker.model.Tuning
import org.thortracker.model.TuningMap

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
    /** Asks where and exports [song], which validated without errors. */
    fun exportUge(song: Song)
    fun recoverPrevious()
    fun exportKeymap()
    fun importKeymap()
    /** The Scala tunings bundled with the app. */
    fun builtInTunings(): List<Tuning>
    /** Lets the user choose a .scl file; [loaded] receives it parsed (errors go through [error]). */
    fun loadTuningFile(loaded: (Tuning) -> Unit)
    /** Platform rows at the end of the main menu (e.g. Quit on the Deck). */
    fun extraMenuItems(): List<Pair<String, () -> Unit>> = emptyList()
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

    fun chooseView(index: Int) {
        choose("View on display ${index + 1}", ViewKind.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { selected ->
            states[index].switchKind(ViewKind.entries[selected])
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
                        state.lockedSubject = when (state.kind) {
                            ViewKind.INSTRUMENT -> state.bank * 16 + state.instrument
                            ViewKind.WAVE -> state.wave
                            else -> state.channel
                        }
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

    /** Waits for one button or one modifier+button chord for [control]; Back or Escape leaves it unchanged. */
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
                else " For a chord, hold Select, L2, R2 or L3, then press the button.") + " Back cancels.",
            items = listOfNotNull(
                default?.let { PopupItem("Use default (${Keymap.label(it)})") { finish(it) } },
                PopupItem.dismiss(),
            ),
            capture = { code, down ->
                when {
                    finished -> Unit
                    code == Keys.BACK || code == Keys.ESCAPE -> if (down) finish(null)
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
                add("1/1 sounds on ${Songs.noteName(t.root)}…" to {
                    menu("1/1 sounds on", (0..Songs.NOTE_MAX).map { n -> Songs.noteName(n) to { applyTuning(t.copy(root = n)) } }, selected = t.root)
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
        host.status(tuningSummary(TuningMap(tuning)))
    }

    /** "24-TET: 118 of 144 degrees reachable within 5¢ (C-3..B-8)". */
    private fun tuningSummary(map: TuningMap) =
        "${map.tuning.name}: ${map.pitches.size} of ${map.all.size} degrees reachable within ${map.tuning.tolerance}¢"

    private fun fitTuning(patterns: List<Int>) {
        val r = session.fitToTuning(patterns)
        host.status("Fitted ${r.moved} note${if (r.moved == 1) "" else "s"}" +
            (if (r.blocked > 0) "; ${r.blocked} left: their row's effect slot is needed for 40y" else ""))
    }

    // ---- files ----------------------------------------------------------------------------------

    fun fileMenu() {
        menu("Project", listOf(
            "New blank song" to { confirmReplace { host.replace(Songs.blank()) } },
            "Open project" to host::openProject,
            "Save" to host::saveProject,
            "Save as…" to host::saveProjectAs,
            "Export .uge" to ::exportUge,
            "Rename song" to ::rename,
            "Load demo" to { confirmReplace { host.replace(Songs.demo()) } },
            "Recover previous project" to { confirmReplace { host.recoverPrevious() } },
        ))
    }

    private fun exportUge() {
        val song = session.song
        val errors = SongValidator.errors(song)
        if (errors.isNotEmpty()) return host.error(errors.joinToString("\n"))
        host.exportUge(song)
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
