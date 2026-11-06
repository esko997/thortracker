package org.thortracker

import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import org.thortracker.audio.HugeAudio
import org.thortracker.audio.TrackerAudio
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
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.SongValidator
import org.thortracker.model.Scl
import org.thortracker.model.Tuning
import org.thortracker.model.TuningMap
import org.thortracker.storage.ProjectStore
import org.thortracker.storage.WorkspaceStore
import org.thortracker.ui.HostInput
import org.thortracker.ui.Palette
import org.thortracker.ui.PopupItem
import org.thortracker.ui.PopupLayer
import org.thortracker.ui.PopupSpec
import org.thortracker.ui.PopupText
import org.thortracker.ui.Popups
import org.thortracker.ui.ShellToolbar
import org.thortracker.ui.ToolbarHandle
import org.thortracker.ui.TrackerView
import org.thortracker.ui.hideFromAccessibility

/** Two display hosts, one document. Musical edits and driver state never live here. */
class MainActivity : Activity(), DisplayManager.DisplayListener {
    private lateinit var session: EditorSession
    private lateinit var projectStore: ProjectStore
    private lateinit var workspace: WorkspaceStore
    /** Panes (one per display here) and the controller's focus; [states] and [focus] are its views. */
    private lateinit var panes: Workspace
    private val states: MutableList<ViewState> get() = panes.panes
    private val focus: Int get() = panes.focus
    private lateinit var displays: DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private val hosts = mutableMapOf<Int, Host>()
    private val popups = Popups({ hosts[it]?.popup }, { hosts.keys })
    private val input = HostInput()
    private var secondary: Presentation? = null
    private var battery: TrackerView.Battery? = null
    /**
     * Sticky ACTION_BATTERY_CHANGED: delivered at once on registration, then on level, plug, voltage and
     * temperature changes. Only the readout is touched (no view refresh), so frequent broadcasts never disturb
     * selection following or a gesture; [refresh] copies it to a display attached later.
     */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            // Some devices report NOT_CHARGING while plugged in at full; plugged in counts as charging.
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            battery = if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) || level < 0 || scale <= 0) null
                else TrackerView.Battery(level * 100 / scale, plugged || status == BatteryManager.BATTERY_STATUS_CHARGING)
            hosts.values.forEach { it.tracker.battery = battery }
        }
    }
    private var audio: TrackerAudio? = null
    private var auditionSequence: Runnable? = null
    private var hasAudioFocus = false
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val audioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) {
                    hasAudioFocus = false
                    transport.halt()
                    hosts.values.forEach { it.tracker.cancelInteraction() }
                    releaseAudition()
                    statusMessage = "Playback paused: another app requested audio"
                    refresh()
                }
            }, main).build()
    }
    private var active = false
    private var initialized = false
    private var documentReady = false
    private var recoveredWorkingSong = false
    private var recoveryWarning: String? = null
    private var pendingPickerResult: Pair<Int, Uri>? = null
    private var currentUri: Uri? = null
    private var documentGeneration = 0L
    private var pickerGeneration: Long? = null
    private var statusMessage = "Loading project…"
    private var flashedStatus: String? = null
    private var keymap = Keymap.DEFAULT
    private var pickerSong: Song? = null
    /** Host where the next popup opens: the touched display, or the controller-focused one. */
    private var modalDisplay = 0
    private var awaitingTransportUntil = 0L
    private lateinit var transport: Transport
    private val audioFeedback = Runnable { refresh() }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val position = transport.position()
            if (position != null) hosts.values.forEach { it.tracker.playback = position }
            if (android.os.SystemClock.uptimeMillis() >= awaitingTransportUntil && position?.playing != true) transport.requested = false
            if (position?.playing == true || android.os.SystemClock.uptimeMillis() < awaitingTransportUntil) {
                hosts.values.forEach { it.transport.text = if (transport.requested) "Stop" else "Play" }
                main.postDelayed(this, 32)
            } else hosts.values.forEach { it.transport.text = "Play" }
            if (audio?.error() != null) updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        workspace = WorkspaceStore(this)
        panes = Workspace(workspace.load(), workspace.focused())
        keymap = workspace.keymap()
        input.keymap = keymap
        popups.keys = keymap
        currentUri = savedInstanceState?.getString("documentUri")?.let(Uri::parse)
        savedInstanceState?.getString("pendingPickerUri")?.let { uri ->
            pendingPickerResult = savedInstanceState.getInt("pendingPickerRequest") to Uri.parse(uri)
        }
        projectStore = ProjectStore(this)
        projectStore.onError = { showError(it) }
        session = EditorSession(Songs.demo())
        transport = Transport(session, object : Transport.Host {
            override fun mayPlay() = requestAudioFocus()
            override fun audioMissing() = showError("Audio is not ready yet")
            override fun transportChanged(starting: Boolean) {
                awaitingTransportUntil = android.os.SystemClock.uptimeMillis() + if (starting) 1500 else 100
                main.removeCallbacks(tick)
                main.post(tick)
                refresh()
            }
        })
        session.onSongChanged = { song ->
            transport.songChanged(song)
            if (documentReady) {
                projectStore.autosave(song)
                statusMessage = WORKING
            }
            refresh()
            scheduleAudioFeedback()
        }
        session.onStateChanged = { refresh() }
        session.onAudition = { channel, note, instrument, detune ->
            cancelAuditionSequence()
            if (requestAudioFocus()) audio?.audition(channel, note, instrument, detune)
            scheduleAudioFeedback()
        }
        session.onAuditionSequence = { channel, steps, instrument, stepMillis ->
            cancelAuditionSequence()
            if (requestAudioFocus()) {
                val sequence = object : Runnable {
                    private var index = 0
                    private var due = android.os.SystemClock.uptimeMillis()
                    override fun run() {
                        if (!active || auditionSequence !== this) return
                        // A null step starts nothing: the previous note keeps sounding.
                        steps[index]?.let { audio?.audition(channel, it, instrument) }
                        index = (index + 1) % steps.size
                        // Steps keep their spacing from the start time instead of accumulating handler delays;
                        // after a stall the loop resumes from now rather than catching up in a burst.
                        val now = android.os.SystemClock.uptimeMillis()
                        due = if (due + stepMillis < now) now + stepMillis else due + stepMillis
                        main.postAtTime(this, due)
                    }
                }
                auditionSequence = sequence
                sequence.run()
                scheduleAudioFeedback()
            }
        }
        session.onReleaseAudition = ::releaseAudition
        // The Song view's "play this order" publishes the order it means; play exactly that selection.
        session.onTransport = { start ->
            if (start) session.selection.let { transport.start(it.order, it.row) } else transport.stop()
        }
        popups.onOpenChanged = { open ->
            if (open) {
                hosts.values.forEach { it.tracker.cancelInteraction() }
            } else {
                hosts.values.forEach { it.tracker.requestFocus() }
                hideSystemBars()
            }
            refresh()
        }
        displays = getSystemService(DisplayManager::class.java)
        displays.registerDisplayListener(this, main)
        buildPrimary()
        projectStore.restore { recovered, warning ->
            if (isDestroyed) return@restore
            if (recovered != null) session.replaceSong(recovered)
            if (recovered == null) currentUri = null
            recoveredWorkingSong = recovered != null
            recoveryWarning = warning
            documentReady = true
            try {
                audio = HugeAudio(applicationContext).also { transport.audio = it }
                initialized = true
                statusMessage = if (recovered != null) "Recovered working project"
                else "Demo loaded. For a blank song choose Menu, Files, New blank song"
            } catch (e: Exception) {
                statusMessage = "Audio unavailable"
                showError("Audio initialization failed: ${e.message}")
            } catch (e: UnsatisfiedLinkError) {
                statusMessage = "Native audio unavailable"
                showError("Native audio could not load: ${e.message}")
            }
            warning?.let { showError(it) }
            pendingPickerResult?.let { (request, uri) ->
                pendingPickerResult = null
                if (recovered != null || request == OPEN) finishPicker(request, uri)
                else rejectUnrecoveredWrite()
            }
            refresh()
            if (!popups.isOpen) hosts.values.forEach { it.tracker.requestFocus() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentUri?.let { outState.putString("documentUri", it.toString()) }
        pendingPickerResult?.let { (request, uri) ->
            outState.putInt("pendingPickerRequest", request)
            outState.putString("pendingPickerUri", uri.toString())
        }
        super.onSaveInstanceState(outState)
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onResume() {
        super.onResume()
        active = true
        hideSystemBars()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        updateSecondary()
        refresh()
    }

    override fun onPause() {
        active = false
        // Key-ups for anything held now may never arrive.
        input.reset()
        unregisterReceiver(batteryReceiver)
        hosts.values.forEach { it.tracker.heldModifiers = emptySet() }
        if (popups.dropCaptures()) statusMessage = "Binding unchanged"
        hosts.values.forEach { it.tracker.cancelInteraction(); it.toolbar.hide() }
        detachSecondary()
        main.removeCallbacks(tick)
        transport.halt()
        releaseAudition()
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(audioFocusRequest)
        hasAudioFocus = false
        if (documentReady) projectStore.autosave(session.song, immediate = true)
        workspace.save(states, focus)
        super.onPause()
    }

    override fun onStop() {
        detachSecondary()
        super.onStop()
    }

    override fun onDestroy() {
        displays.unregisterDisplayListener(this)
        main.removeCallbacksAndMessages(null)
        detachSecondary()
        audio?.close()
        projectStore.close()
        popups.onOpenChanged = null
        popups.closeAll()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        refresh()
    }

    private fun buildPrimary() {
        val host = Host(this, 0)
        hosts[0] = host
        setContentView(host.root)
    }

    private fun updateSecondary() {
        if (!active) return
        val candidate = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != display?.displayId }
        if (candidate == null) {
            detachSecondary()
            if (focus == 1) panes.focus = 0
            refresh()
            return
        }
        if (secondary?.display?.displayId == candidate.displayId && secondary?.isShowing == true) return
        detachSecondary()
        try {
            val presentation = object : Presentation(this, candidate) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    val host = Host(context, 1)
                    hosts[1] = host
                    setContentView(host.root)
                }
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    routeKey(event) || super.dispatchKeyEvent(event)
            }
            presentation.setOnDismissListener {
                if (secondary === presentation) {
                    detachSecondary()
                    if (focus == 1) panes.focus = 0
                    refresh()
                }
            }
            secondary = presentation
            presentation.show()
            if (!popups.isOpen) hosts[1]?.tracker?.requestFocus()
            refresh()
        } catch (e: WindowManager.InvalidDisplayException) {
            detachSecondary()
            if (focus == 1) panes.focus = 0
            statusMessage = "Second display disconnected; working on one screen"
            refresh()
        }
    }

    private fun detachSecondary() {
        hosts.remove(1)?.let { it.tracker.cancelInteraction(); it.toolbar.hide() }
        // Popups shown there continue on the primary display with their callbacks intact, except a
        // pending button capture: the user can no longer see that prompt, so it must not take a key.
        if (popups.isOpen && popups.display == 1 && popups.dropCaptures()) statusMessage = "Remapping cancelled"
        popups.rehome()
        if (modalDisplay == 1) modalDisplay = 0
        val old = secondary
        secondary = null
        old?.dismiss()
    }

    override fun onDisplayAdded(displayId: Int) = updateSecondary()
    override fun onDisplayRemoved(displayId: Int) = updateSecondary()
    override fun onDisplayChanged(displayId: Int) = updateSecondary()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = routeKey(event) || super.dispatchKeyEvent(event)

    /**
     * Every key from either window. Layers in order: binding capture, open popup, host chords and global
     * keys, the controller-focused toolbar, then the controller-focused editor. Returns false only for
     * keys the window itself should handle (system keys, typing into a popup's text field).
     */
    private fun routeKey(original: KeyEvent): Boolean {
        if (!active) return true
        val action = original.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return !popups.typing
        val down = action == KeyEvent.ACTION_DOWN
        val repeat = original.repeatCount
        val code = original.keyCode
        popups.top?.capture?.let { capture ->
            if (repeat == 0) capture(code, down)
            return true
        }
        if (!documentReady && !popups.isOpen) return true
        val route = input.route(code, down, repeat, popups.isOpen, toolbarFocused(), states[focus].kind)
        hosts.forEach { (index, host) -> host.tracker.heldModifiers = if (index == focus) input.heldModifiers else emptySet() }
        return when (route) {
            is HostInput.Route.Run -> { run(route.control); true }
            HostInput.Route.Menu -> { if (documentReady) { modalDisplay = focus; appMenu() }; true }
            HostInput.Route.Pass -> false
            is HostInput.Route.Editor ->
                if (!documentReady) true else hosts[focus]?.tracker?.controllerAction(route.action, down, repeat) ?: true
            is HostInput.Route.Deliver -> when (route.target) {
                HostInput.Target.NONE -> true
                HostInput.Target.POPUP -> popups.handleKey(route.code, down, repeat)
                HostInput.Target.TOOLBAR -> hosts[focus]?.toolbar?.handleKey(route.code, down, repeat) ?: true
            }
        }
    }

    private fun run(control: Control) {
        if (!documentReady) return
        when (control) {
            Control.SWAP -> swap()
            Control.TOOLBAR -> toggleToolbar()
            Control.PLAY_PATTERN -> toggleTransport(fromCursor = false)
            Control.PLAY_CURSOR -> toggleTransport(fromCursor = true)
            Control.LOOP_PATTERN -> toggleLoop()
            Control.FOCUS -> switchFocus()
            else -> control.shows?.let(::showView) ?: control.action?.let(::editAction)
        }
    }

    /** A view chord on the controller-focused display ([Workspace.showView]). */
    private fun showView(kind: ViewKind) {
        hosts.values.forEach { it.tracker.cancelInteraction() }
        if (!panes.showView(kind)) return
        refresh(); workspace.save(states, focus)
    }

    private fun toolbarFocused(): Boolean = hosts[focus]?.toolbar?.controllerFocused == true

    /** Select+Start: the focused display's toolbar takes the controller cursor, or hands it back. */
    private fun toggleToolbar() {
        val toolbar = hosts[focus]?.toolbar ?: return
        if (toolbar.controllerFocused) toolbar.hide()
        else {
            hosts.values.forEach { it.tracker.cancelInteraction() }
            toolbar.focusByController()
        }
        refresh()
    }

    private fun setFocus(index: Int) {
        val next = if (hosts.containsKey(index)) index else 0
        if (next != focus) hosts[focus]?.toolbar?.takeIf { it.controllerFocused }?.hide()
        panes.focus = next
        workspace.save(states, focus)
        refresh()
    }

    /** Stopping is unconditional; starting plays the focused editor's order from row 0 or its cursor. */
    private fun toggleTransport(fromCursor: Boolean) {
        val (order, row) = panes.activePlace(session.selection)
        transport.toggle(order, if (fromCursor) row else 0)
    }

    private fun toggleLoop() {
        transport.toggleLoop(panes.activePlace(session.selection).first)
        transport.position()?.let { p -> hosts.values.forEach { it.tracker.playback = p } }
        refresh()
    }

    private fun requestAudioFocus(): Boolean {
        if (!hasAudioFocus) hasAudioFocus =
            audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasAudioFocus) {
            statusMessage = "Audio is in use by another app"
            refresh()
        }
        return hasAudioFocus
    }

    private fun refresh() {
        if (!::panes.isInitialized || !::session.isInitialized) return
        val selected = session.selection
        hosts.forEach { (index, host) ->
            val state = states[index]
            if (!state.pinned && index != focus && !host.tracker.ownsSelection) state.switchKind(selected.context)
            host.tracker.state = state
            host.tracker.controllerFocused = index == focus
            host.tracker.heldModifiers = if (index == focus) input.heldModifiers else emptySet()
            host.tracker.battery = battery
            host.tracker.visibility = if (documentReady) View.VISIBLE else View.INVISIBLE
            host.tracker.refresh()
            host.toolbar.heading.text = if (index == focus) "● ${session.song.title}" else session.song.title
            host.toolbar.heading.setTextColor(if (index == focus) Palette.PLAYHEAD else Palette.DIM)
            host.viewButton.text = state.kind.name.lowercase().replaceFirstChar { it.uppercase() }
            host.pin.text = if (state.pinned) "Pinned" else "Context"
            host.focusButton.visibility = if (hosts.size > 1) View.VISIBLE else View.GONE
            host.transport.isEnabled = initialized
            host.transport.text = if (transport.requested) "Stop" else "Play"
            host.toolbar.repaint()
            val popupHere = popups.isOpen && popups.display == index
            host.handle.visibility = if (documentReady && !popupHere && !host.toolbar.revealed) View.VISIBLE else View.GONE
            host.setModal(popupHere)
        }
        updateStatus()
    }

    /**
     * Status lives in the (usually hidden) toolbar; a new message also flashes a chip on each display. An audio
     * error (e.g. the preview could not take the latest edits) stays on screen until it clears, because what you
     * hear no longer matches the song.
     */
    private fun updateStatus() {
        val audioError = audio?.error()
        val message = audioError ?: statusMessage
        hosts.values.forEach { if (it.toolbar.status.text.toString() != message) it.toolbar.status.text = message }
        if (!documentReady) { hosts.values.forEach { it.flash(message, sticky = true) }; return }
        if (message == WORKING) { flashedStatus = null; return }
        if (message == flashedStatus) return
        flashedStatus = message
        val sticky = audioError != null
        hosts.values.forEach { if (!it.toolbar.revealed) it.flash(message, sticky = sticky) else it.flash(null, false) }
    }

    private fun cancelAuditionSequence() {
        auditionSequence?.let { main.removeCallbacks(it) }
        auditionSequence = null
    }

    private fun releaseAudition() {
        cancelAuditionSequence()
        audio?.releaseAudition()
    }

    private fun scheduleAudioFeedback() {
        main.removeCallbacks(audioFeedback)
        main.postDelayed(audioFeedback, 100)
    }

    private fun switchFocus() = setFocus(if (hosts.containsKey(1)) 1 - focus else 0)

    private fun swap() {
        hosts.values.forEach { it.tracker.cancelInteraction() }
        panes.swap(0, 1)
        statusMessage = if (hosts.containsKey(1)) "Displays swapped" else "Showing the other saved view"
        workspace.save(states, focus)
        refresh()
    }

    /** Menu route: the originating display shows the Song view with its tempo row under the cursor. */
    private fun showSongDetails() {
        val index = if (hosts.containsKey(modalDisplay)) modalDisplay else focus
        val host = hosts[index] ?: return
        hosts.values.forEach { it.toolbar.takeIf { t -> t.controllerFocused }?.hide() }
        host.tracker.cancelInteraction()
        panes.focus = index
        val state = states[index]
        state.switchKind(ViewKind.SONG)
        state.pinned = true
        // Let the view apply its kind change (and any selection following) before choosing the row.
        host.tracker.state = state
        host.tracker.refresh()
        state.songItem = SONG_TEMPO_ITEM
        state.textPos = 0
        statusMessage = "Song details: tempo (ticks per row) selected"
        workspace.save(states, focus)
        refresh()
    }

    // ---- menus ----------------------------------------------------------------------------------

    private fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int = 0) {
        popups.show(PopupSpec(title, items = entries.map { (label, action) -> PopupItem(label, action = action) } +
            PopupItem.dismiss(), selected = selected), modalDisplay)
    }

    private fun choose(title: String, labels: List<String>, selected: (Int) -> Unit) =
        menu(title, labels.mapIndexed { index, label -> label to { selected(index) } })

    private fun chooseView(index: Int) {
        choose("View on display ${index + 1}", ViewKind.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { selected ->
            states[index].switchKind(ViewKind.entries[selected])
            states[index].pinned = true
            refresh(); workspace.save(states, focus)
        }
    }

    private fun viewOptions(index: Int) {
        val state = states[index]
        choose("View behavior", listOf(
            if (state.pinned) "Allow contextual view changes" else "Pin this view",
            if (state.followSelection) "Stop following selection" else "Follow selected item",
            if (state.lockedSubject >= 0) "Unlock subject" else "Lock to this item",
            "Swap complete views", "Pattern + keyboard layout", "Pattern + instrument layout",
            if (state.followPlayback) "Stop following playhead" else "Follow playhead",
        )) { selected ->
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
                3 -> swap()
                4, 5 -> {
                    states[0] = ViewState(kind = ViewKind.PATTERN)
                    states[1] = ViewState(kind = if (selected == 4) ViewKind.KEYBOARD else ViewKind.INSTRUMENT)
                }
                6 -> state.followPlayback = !state.followPlayback
            }
            refresh(); workspace.save(states, focus)
        }
    }

    private fun editAction(action: Action) {
        hosts[focus]?.tracker?.let { it.press(action); it.release(action) }
        refresh()
    }

    private fun appMenu() {
        menu("Thor Tracker", buildList<Pair<String, () -> Unit>> {
            add("Files" to ::fileMenu)
            add("Song details / tempo" to ::showSongDetails)
            add("Views / layout" to { viewOptions(focus) })
            if (hosts.containsKey(1)) add("Switch controller focus" to ::switchFocus)
            add("Play from song start" to { transport.start(0, 0) })
            add("Copy selection" to { editAction(Action.COPY) })
            add("Cut selection" to { editAction(Action.CUT) })
            add("Paste" to { editAction(Action.PASTE) })
            add("Duplicate" to { editAction(Action.DUPLICATE) })
            add("Undo" to { editAction(Action.UNDO) })
            add("Redo" to { editAction(Action.REDO) })
            add("Entry step" to {
                choose("Advance after note entry", listOf("Stay", "1 row", "2 rows", "4 rows", "8 rows", "16 rows")) {
                    session.step = intArrayOf(0, 1, 2, 4, 8, 16)[it]; refresh()
                }
            })
            add("Tuning" to ::tuningMenu)
            add("Controls" to ::showControls)
            add("Help" to { states[focus].switchKind(ViewKind.HELP); states[focus].pinned = true; refresh() })
        })
    }

    // ---- controls -------------------------------------------------------------------------------

    private fun applyKeymap(next: Keymap, message: String?) {
        keymap = next
        input.keymap = next
        input.reset()
        popups.keys = next
        hosts.values.forEach { it.tracker.keymap = next; it.toolbar.setKeys(next) }
        workspace.saveKeymap(next)
        message?.let { statusMessage = it }
        refresh()
    }

    /** One row per control, grouped by area: its job, then its current binding; choosing a row rebinds it. */
    private fun controlsSpec(): PopupSpec {
        val items = ArrayList<PopupItem>()
        var group: ControlGroup? = null
        for (c in Control.entries) {
            if (c.group != group) { group = c.group; items += PopupItem.heading(c.group.label) }
            val bound = keymap.binding(c) != null
            items += PopupItem(c.label, if (bound) PopupItem.Tone.PLAIN else PopupItem.Tone.DANGER, value = keymap.label(c)) { captureBinding(c) }
        }
        items += PopupItem.heading("Keymap file")
        items += PopupItem("Export keymap…") { launchKeymapPicker(KEYMAP_EXPORT) }
        items += PopupItem("Import keymap…") { launchKeymapPicker(KEYMAP_IMPORT) }
        items += PopupItem("Reset all to defaults", PopupItem.Tone.DANGER) {
            popups.show(PopupSpec("Reset all controls?", message = "Every binding returns to its default.",
                items = listOf(PopupItem("Reset", PopupItem.Tone.DANGER) {
                    popups.back()
                    applyKeymap(Keymap.DEFAULT, "Default controls restored")
                    popups.replaceTop(controlsSpec())
                }, PopupItem.dismiss()), selected = 1), modalDisplay)
        }
        items += PopupItem.dismiss()
        return PopupSpec("Controls", message = "Choose a row, then press its new button — or hold Select, L2, R2 or L3 " +
            "and press a button for a chord. Menu and Back are fixed, so this screen is always reachable.",
            items = items, selected = 1)
    }

    private fun showControls() = popups.show(controlsSpec(), modalDisplay)

    /** Waits for one button or one modifier+button chord for [control]; Back or Escape leaves it unchanged. */
    private fun captureBinding(control: Control) {
        val held = LinkedHashSet<Int>()
        var finished = false
        val default = Keymap.DEFAULT.binding(control)
        fun finish(binding: Binding?) {
            finished = true
            popups.back()
            input.reset()
            if (binding == null) { statusMessage = "Binding unchanged"; refresh() } else finishCapture(control, binding)
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
                    code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE -> if (down) finish(null)
                    code in Keymap.MODIFIERS -> if (down) held += code else held -= code
                    !down -> Unit
                    held.size > 1 -> { finish(null); showError("Hold one of Select, L2, R2 or L3 for a chord, not several") }
                    else -> finish(Binding(code, held.firstOrNull()))
                }
            }), modalDisplay)
    }

    /** Applies a captured [binding], asking before taking it from another control. */
    private fun finishCapture(control: Control, binding: Binding) {
        keymap.problem(control, binding)?.let { return showError(it) }
        if (keymap.binding(control) == binding) { statusMessage = "${control.short}: unchanged"; refresh(); return }
        val owners = keymap.conflicts(control, binding)
        if (owners.isEmpty()) return assign(control, binding, emptyList())
        val chord = Keymap.label(binding)
        owners.firstOrNull { it.essential }?.let { owner ->
            return showError("$chord is ${owner.label}, which always needs a button. Rebind ${owner.short} first, or choose another button.")
        }
        val names = owners.joinToString(" and ") { it.short }
        popups.show(PopupSpec("$chord is $names", message = "Use $chord for ${control.label}? ${owners.joinToString(" and ") { it.label }} will have no binding until you set one.",
            items = listOf(PopupItem("Replace", PopupItem.Tone.PRIMARY) { popups.back(); assign(control, binding, owners) }, PopupItem.dismiss()),
            selected = 1), modalDisplay)
    }

    private fun assign(control: Control, binding: Binding, displaced: List<Control>) {
        var next = keymap.with(control, binding)
        for (d in displaced) next = next.with(d, null)
        applyKeymap(next, "${control.short}: ${Keymap.label(binding)}" +
            (if (displaced.isEmpty()) "" else " (${displaced.joinToString(" and ") { it.short }} now unbound)"))
        if (popups.top?.title == "Controls") popups.replaceTop(controlsSpec())
    }

    private fun launchKeymapPicker(request: Int) {
        val intent = Intent(if (request == KEYMAP_IMPORT) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (request == KEYMAP_IMPORT) "*/*" else "application/json"
            if (request == KEYMAP_EXPORT) putExtra(Intent.EXTRA_TITLE, "thor-tracker-keymap.json")
        }
        try { startActivityForResult(intent, request) }
        catch (e: Exception) { showError("No document provider could open: ${e.message}") }
    }

    // ---- tuning ---------------------------------------------------------------------------------

    /** Song tuning: load a Scala file, choose where 1/1 sounds and the accuracy limit, fit notes, or remove it. */
    private fun tuningMenu() {
        val t = session.song.tuning
        val map = session.tuningMap
        menu(if (t == null) "Tuning: 12-note (none)" else "Tuning: ${t.name}", buildList<Pair<String, () -> Unit>> {
            add("Built-in tunings…" to ::builtInTunings)
            add("Load .scl file…" to { launchTuningPicker() })
            if (t != null && map != null) {
                add("1/1 sounds on ${Songs.noteName(t.root)}…" to {
                    menu("1/1 sounds on", (0..Songs.NOTE_MAX).map { n -> Songs.noteName(n) to { applyTuning(t.copy(root = n)) } }, selected = t.root)
                })
                add("Accuracy limit: ${t.tolerance}¢…" to {
                    choose("Accuracy limit", TOLERANCES.map { "$it cents" + if (it == 5) " (default)" else "" }) { applyTuning(t.copy(tolerance = TOLERANCES[it])) }
                })
                add("Fit notes in this pattern" to { fitTuning(listOf(states[focus].let { session.song.orders[it.order.coerceIn(0, session.song.orders.size - 1)] })) })
                add("Fit notes in whole song" to { fitTuning(session.song.patterns.indices.toList()) })
                add("Remove tuning (back to 12-note)" to { session.setTuning(null); statusMessage = "Tuning removed; written notes unchanged"; refresh() })
            }
        })
    }

    /** Scala files bundled in assets/tunings, listed by their own descriptions; keeps the current 1/1 and accuracy limit. */
    private fun builtInTunings() {
        val previous = session.song.tuning
        val tunings = (assets.list(TUNING_ASSETS) ?: emptyArray()).filter { it.endsWith(".scl") }.sorted().mapNotNull { file ->
            runCatching {
                Scl.parse(assets.open("$TUNING_ASSETS/$file").use { it.readBytes() }.toString(Charsets.UTF_8), file.removeSuffix(".scl"))
            }.getOrNull()
        }
        menu("Built-in tunings", tunings.map { t ->
            t.name to { applyTuning(if (previous == null) t else t.copy(root = previous.root, tolerance = previous.tolerance)) }
        })
    }

    private fun applyTuning(tuning: Tuning) {
        session.setTuning(tuning)
        statusMessage = tuningSummary(TuningMap(tuning))
        refresh()
    }

    /** "24-TET: 118 of 144 degrees reachable within 5¢ (C-3..B-8)". */
    private fun tuningSummary(map: TuningMap) =
        "${map.tuning.name}: ${map.pitches.size} of ${map.all.size} degrees reachable within ${map.tuning.tolerance}¢"

    private fun fitTuning(patterns: List<Int>) {
        val r = session.fitToTuning(patterns)
        statusMessage = "Fitted ${r.moved} note${if (r.moved == 1) "" else "s"}" +
            (if (r.blocked > 0) "; ${r.blocked} left: their row's effect slot is needed for 40y" else "")
        refresh()
    }

    private fun launchTuningPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        try { startActivityForResult(intent, TUNING_IMPORT) }
        catch (e: Exception) { showError("No document provider could open: ${e.message}") }
    }

    /** Reads and parses a Scala file off the UI thread; keeps the current 1/1 note and accuracy limit. */
    private fun importTuning(uri: Uri) {
        val previous = session.song.tuning
        Thread({
            val result = runCatching {
                val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }?.substringBeforeLast('.') ?: "Tuning"
                val bytes = checkNotNull(contentResolver.openInputStream(uri)) { "the file could not be opened" }.use { stream ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        check(out.size() <= MAX_KEYMAP_BYTES) { "the file is too large to be a Scala scale" }
                    }
                    out.toByteArray()
                }
                Scl.parse(bytes.toString(Charsets.UTF_8), name).let { parsed ->
                    if (previous == null) parsed else parsed.copy(root = previous.root, tolerance = previous.tolerance)
                }
            }
            main.post {
                if (isDestroyed) return@post
                result.fold({ applyTuning(it) }, { e -> showError("Tuning not loaded: ${e.message}") })
            }
        }, "tuning-file").start()
    }

    /** Writes or reads a keymap file off the UI thread; a provider that stalls cannot freeze input. */
    private fun keymapFile(request: Int, uri: Uri) {
        val snapshot = keymap
        Thread({
            val result = runCatching {
                if (request == KEYMAP_EXPORT) {
                    checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "the destination could not be opened" }
                        .use { it.write(snapshot.toJson().toByteArray()) }
                    null
                } else {
                    val bytes = checkNotNull(contentResolver.openInputStream(uri)) { "the file could not be opened" }.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = stream.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            check(out.size() <= MAX_KEYMAP_BYTES) { "the file is too large to be a keymap" }
                        }
                        out.toByteArray()
                    }
                    Keymap.fromJson(bytes.toString(Charsets.UTF_8))
                }
            }
            main.post {
                if (isDestroyed) return@post
                result.fold(
                    { imported -> if (imported == null) { statusMessage = "Keymap exported"; refresh() } else applyKeymap(imported, "Keymap imported") },
                    { e -> showError("Keymap ${if (request == KEYMAP_EXPORT) "export" else "import"} failed: ${e.message}") },
                )
            }
        }, "keymap-file").start()
    }

    private fun fileMenu() {
        menu("Project", listOf<Pair<String, () -> Unit>>(
            "New blank song" to { confirmReplace { replace(Songs.blank()) } },
            "Open project" to { launchPicker(OPEN) },
            "Save" to { currentUri?.let { saveProject(it) } ?: launchPicker(SAVE) },
            "Save as…" to { launchPicker(SAVE) },
            "Export .uge" to { launchPicker(EXPORT) },
            "Rename song" to ::rename,
            "Load demo" to { confirmReplace { replace(Songs.demo()) } },
            "Recover previous project" to { confirmReplace { projectStore.previous { song -> replace(song) } } },
        ))
    }

    private fun rename() {
        val title = PopupText(session.song.title, 255)
        popups.show(PopupSpec("Song title", text = title, items = listOf(
            PopupItem("Rename", PopupItem.Tone.PRIMARY) { session.setTitle(title.value.take(255)); refresh() },
            PopupItem.dismiss(),
        )), modalDisplay)
    }

    private fun confirmReplace(action: () -> Unit) {
        popups.show(PopupSpec("Replace working song?",
            message = "The current song will replace the single previous-project recovery backup. Save it to a project file if you want to keep it permanently.",
            items = listOf(PopupItem("Continue", PopupItem.Tone.DANGER, action = action), PopupItem.dismiss()),
            selected = 1), modalDisplay)
    }

    private fun replace(song: Song, uri: Uri? = null) {
        documentGeneration++
        transport.stop()
        projectStore.archive(session.song)
        session.replaceSong(song)
        currentUri = uri
        projectStore.autosave(song, immediate = true)
        statusMessage = "${song.title} loaded"
        refresh()
    }

    private fun saveProject(uri: Uri, snapshot: Song = session.song, generation: Long = documentGeneration) {
        projectStore.save(uri, snapshot) {
            if (generation != documentGeneration) return@save
            currentUri = uri
            statusMessage = if (session.song === snapshot) "Project saved" else "Saved earlier version · unsaved edits"
            refresh()
        }
    }

    private fun launchPicker(request: Int) {
        if (request == EXPORT) {
            val errors = SongValidator.errors(session.song)
            if (errors.isNotEmpty()) return showError(errors.joinToString("\n"))
        }
        hosts.values.forEach { it.tracker.cancelInteraction() }
        pickerSong = session.song
        pickerGeneration = documentGeneration
        val intent = Intent(if (request == OPEN) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (request == OPEN) "*/*" else "application/octet-stream"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            if (request != OPEN) putExtra(Intent.EXTRA_TITLE, safeName(session.song.title) + if (request == EXPORT) ".uge" else ".thor")
        }
        try { startActivityForResult(intent, request) }
        catch (e: Exception) { showError("No document provider could open: ${e.message}") }
    }

    @Deprecated("Android Activity result API used to keep a small native host")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == KEYMAP_EXPORT || requestCode == KEYMAP_IMPORT) {
            if (resultCode == RESULT_OK) data?.data?.let { keymapFile(requestCode, it) }
            return
        }
        if (requestCode == TUNING_IMPORT) {
            if (resultCode == RESULT_OK) data?.data?.let(::importTuning)
            return
        }
        if (resultCode != RESULT_OK) { pickerSong = null; pickerGeneration = null; return }
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        } catch (_: SecurityException) { /* Some providers grant only the current operation. */ }
        if (!documentReady) {
            pendingPickerResult = requestCode to uri
            return
        }
        if (requestCode != OPEN && pickerSong == null && !recoveredWorkingSong) {
            rejectUnrecoveredWrite()
            return
        }
        finishPicker(requestCode, uri)
    }

    private fun rejectUnrecoveredWrite() {
        showError(listOfNotNull(recoveryWarning,
            "The working project could not be recovered. Nothing was written to the selected destination.").joinToString("\n"))
    }

    private fun finishPicker(requestCode: Int, uri: Uri) {
        val snapshot = pickerSong ?: session.song
        val generation = pickerGeneration ?: documentGeneration
        pickerSong = null
        pickerGeneration = null
        when (requestCode) {
            OPEN -> projectStore.open(uri) { song ->
                if (generation == documentGeneration) replace(song, uri)
            }
            SAVE -> saveProject(uri, snapshot, generation)
            EXPORT -> projectStore.export(uri, snapshot) {
                if (generation == documentGeneration) { statusMessage = "UGE exported"; refresh() }
            }
        }
    }

    private fun showError(message: String) {
        if (isDestroyed) return
        statusMessage = message
        android.util.Log.e("ThorTracker", message)
        popups.show(PopupSpec("Thor Tracker", message = message,
            items = listOf(PopupItem("OK", PopupItem.Tone.PRIMARY))), modalDisplay)
        refresh()
    }

    /** One display's UI: the tracker with overlays for the pull tab, status chip, toolbar and popups. */
    private inner class Host(context: Context, val index: Int) {
        private val density = context.resources.displayMetrics.density
        private var modal = false
        val tracker = TrackerView(context, session, states[index]).apply {
            onTouchEnded = { this@MainActivity.refresh() }
        }
        val popup = PopupLayer(context)
        val toolbar = ShellToolbar(context) { popups.isOpen }.apply { onLeave = { this@MainActivity.refresh() } }
        val handle = ToolbarHandle(context) {
            if (documentReady && !popups.isOpen) { modalDisplay = index; toolbar.revealByTouch(); refresh() }
        }
        private val chip = TextView(context).apply {
            typeface = PopupLayer.REGULAR; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Palette.TEXT)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(560)
            background = GradientDrawable().apply {
                setColor(Palette.BAR); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Palette.RULE)
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val chipHide = Runnable {
            chip.animate().alpha(0f).setDuration(220).withEndAction { chip.visibility = View.GONE }.start()
        }
        val root = object : FrameLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Popups belong to their display; touching the other display leaves them.
                    if (popups.isOpen && popups.display != index) popups.closeAll()
                    if (!popups.isOpen) {
                        modalDisplay = index
                        if (toolbar.revealed && event.y > toolbar.bottom) toolbar.hide()
                        tracker.requestFocus()
                    }
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply { setBackgroundColor(Palette.BG) }

        private fun act(action: () -> Unit) = { if (documentReady) { modalDisplay = index; action() } }

        val viewButton: Button = toolbar.add("View", action = act { chooseView(index) })
        val pin: Button = toolbar.add("Pinned", action = act { viewOptions(index) })
        val focusButton: Button = toolbar.add("Focus", action = act { setFocus(index) })
        val transport: Button

        init {
            toolbar.add("Swap", wideOnly = true, action = act { swap() })
            toolbar.add("Undo", wideOnly = true, action = act { editAction(Action.UNDO) })
            toolbar.add("Redo", wideOnly = true, action = act { editAction(Action.REDO) })
            toolbar.add("File", wideOnly = true, action = act { fileMenu() })
            toolbar.add("Menu", action = act { appMenu() })
            transport = toolbar.add("Play", action = act { toggleTransport(fromCursor = false) })
            tracker.keymap = keymap
            toolbar.setKeys(keymap)
            root.addView(tracker, FrameLayout.LayoutParams(-1, -1))
            root.addView(handle, FrameLayout.LayoutParams(dp(72), dp(18), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            root.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(34)
            })
            root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            root.addView(popup, FrameLayout.LayoutParams(-1, -1))
        }

        /** Shows [message] on the status chip ([sticky] while loading); null clears it. */
        fun flash(message: String?, sticky: Boolean) {
            chip.removeCallbacks(chipHide)
            chip.animate().cancel()
            if (message == null) { chip.visibility = View.GONE; return }
            chip.text = message
            chip.alpha = 1f
            chip.visibility = View.VISIBLE
            if (!sticky) chip.postDelayed(chipHide, 2800)
        }

        /** While this display shows a popup, everything under it is hidden from accessibility. */
        fun setModal(value: Boolean) {
            if (value == modal) return
            modal = value
            root.hideFromAccessibility(value, popup)
        }

        private fun dp(value: Int) = (value * density).toInt()
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(80).ifBlank { "song" }

    companion object {
        private const val OPEN = 10
        private const val SAVE = 11
        private const val EXPORT = 12
        private const val KEYMAP_EXPORT = 13
        private const val KEYMAP_IMPORT = 14
        private const val TUNING_IMPORT = 15
        private const val TUNING_ASSETS = "tunings"
        private val TOLERANCES = intArrayOf(5, 10, 20)
        private const val MAX_KEYMAP_BYTES = 64 * 1024
        private const val WORKING = "Working copy"
        /** Song view header rows: 0 title, 1 artist, 2 ticks per row (tempo), 3 loop order. */
        private const val SONG_TEMPO_ITEM = 2
    }
}
