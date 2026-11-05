package org.thortracker

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.thortracker.audio.HugeAudio
import org.thortracker.audio.TrackerAudio
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.SongValidator
import org.thortracker.storage.ProjectStore
import org.thortracker.storage.WorkspaceStore
import org.thortracker.ui.TrackerView

/** Two display hosts, one document. Musical edits and driver state never live here. */
class MainActivity : Activity(), DisplayManager.DisplayListener {
    private lateinit var session: EditorSession
    private lateinit var projectStore: ProjectStore
    private lateinit var workspace: WorkspaceStore
    private lateinit var states: MutableList<ViewState>
    private lateinit var displays: DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private val hosts = mutableMapOf<Int, Host>()
    private var secondary: Presentation? = null
    private var audio: TrackerAudio? = null
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
                    audio?.stop()
                    requestedPlaying = false
                    audio?.releaseAudition()
                    statusMessage = "Playback paused: another app requested audio"
                    refresh()
                }
            }, main).build()
    }
    private var focus = 0
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
    private var remappings = mutableMapOf<Int, Int>()
    private var captureKey: ((Int) -> Unit)? = null
    private var pickerSong: Song? = null
    private var dialog: AlertDialog? = null
    private var modalDisplay = 0
    private var awaitingTransportUntil = 0L
    private var requestedPlaying = false
    private val audioFeedback = Runnable { refresh() }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            val position = audio?.position()
            if (position != null) hosts.values.forEach { it.tracker.playback = position }
            if (android.os.SystemClock.uptimeMillis() >= awaitingTransportUntil && position?.playing != true) requestedPlaying = false
            if (position?.playing == true || android.os.SystemClock.uptimeMillis() < awaitingTransportUntil) {
                hosts.values.forEach { it.transport.text = if (requestedPlaying) "Stop" else "Play" }
                main.postDelayed(this, 32)
            } else hosts.values.forEach { it.transport.text = "Play" }
            audio?.error()?.let { error -> hosts.values.forEach { it.status.text = error } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        workspace = WorkspaceStore(this)
        states = workspace.load()
        focus = workspace.focused()
        remappings = workspace.bindings()
        currentUri = savedInstanceState?.getString("documentUri")?.let(Uri::parse)
        savedInstanceState?.getString("pendingPickerUri")?.let { uri ->
            pendingPickerResult = savedInstanceState.getInt("pendingPickerRequest") to Uri.parse(uri)
        }
        projectStore = ProjectStore(this)
        projectStore.onError = { showError(it) }
        session = EditorSession(Songs.demo())
        session.onSongChanged = { song ->
            audio?.update(song)
            if (documentReady) {
                projectStore.autosave(song)
                statusMessage = "Working copy"
            }
            refresh()
            scheduleAudioFeedback()
        }
        session.onStateChanged = { refresh() }
        session.onAudition = { channel, note, instrument ->
            if (requestAudioFocus()) audio?.audition(channel, note, instrument)
            scheduleAudioFeedback()
        }
        session.onReleaseAudition = { audio?.releaseAudition() }
        session.onTransport = { start -> if (start) startPlayback(true) else stopPlayback() }
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
                audio = HugeAudio(applicationContext).also { it.update(session.song) }
                initialized = true
                statusMessage = if (recovered != null) "Recovered working project" else "Demo loaded · File → New for a blank song"
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
            hosts.values.forEach { it.tracker.requestFocus() }
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
        updateSecondary()
        refresh()
    }

    override fun onPause() {
        active = false
        hosts.values.forEach { it.tracker.cancelInteraction() }
        detachSecondary()
        main.removeCallbacks(tick)
        audio?.stop()
        requestedPlaying = false
        audio?.releaseAudition()
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
        dialog?.dismiss()
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
            if (focus == 1) focus = 0
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
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (dialog?.isShowing == true) return super.dispatchKeyEvent(event)
                    return routeKey(event, 1) || super.dispatchKeyEvent(event)
                }
            }
            presentation.setOnDismissListener {
                if (secondary === presentation) {
                    detachSecondary()
                    if (focus == 1) focus = 0
                    refresh()
                }
            }
            secondary = presentation
            presentation.show()
            hosts[1]?.tracker?.requestFocus()
            refresh()
        } catch (e: WindowManager.InvalidDisplayException) {
            detachSecondary()
            if (focus == 1) focus = 0
            statusMessage = "Second display disconnected; working on one screen"
            refresh()
        }
    }

    private fun detachSecondary() {
        if (modalDisplay == 1) dialog?.dismiss()
        hosts.remove(1)?.tracker?.cancelInteraction()
        val old = secondary
        secondary = null
        old?.dismiss()
    }

    override fun onDisplayAdded(displayId: Int) = updateSecondary()
    override fun onDisplayRemoved(displayId: Int) = updateSecondary()
    override fun onDisplayChanged(displayId: Int) = updateSecondary()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dialog?.isShowing == true) return super.dispatchKeyEvent(event)
        return routeKey(event, 0) || super.dispatchKeyEvent(event)
    }

    private fun routeKey(original: KeyEvent, inputHost: Int): Boolean {
        if (!documentReady || !active) return true
        modalDisplay = inputHost
        captureKey?.let { callback ->
            if (original.action == KeyEvent.ACTION_DOWN && original.repeatCount == 0) {
                captureKey = null
                callback(original.keyCode)
            }
            return true
        }
        val code = remappings[original.keyCode] ?: original.keyCode
        val event = if (code == original.keyCode) original else KeyEvent(original.downTime, original.eventTime,
            original.action, code, original.repeatCount, original.metaState, original.deviceId,
            original.scanCode, original.flags, original.source)
        when (code) {
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_SPACE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleTransport()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) switchFocus()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    appMenu()
                }
                return true
            }
        }
        return hosts[focus]?.tracker?.handleController(event) ?: false
    }

    private fun toggleTransport() {
        if (requestedPlaying) stopPlayback() else startPlayback(true)
    }

    private fun startPlayback(fromSelection: Boolean) {
        val engine = audio ?: return showError("Audio is not ready yet")
        if (!requestAudioFocus()) return
        val selected = session.selection
        requestedPlaying = true
        engine.play(if (fromSelection) selected.order else 0, if (fromSelection) selected.row else 0)
        awaitingTransportUntil = android.os.SystemClock.uptimeMillis() + 1500
        main.removeCallbacks(tick)
        main.post(tick)
        refresh()
    }

    private fun stopPlayback() {
        requestedPlaying = false
        audio?.stop()
        awaitingTransportUntil = android.os.SystemClock.uptimeMillis() + 100
        main.removeCallbacks(tick)
        main.post(tick)
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
        if (!::states.isInitialized || !::session.isInitialized) return
        val selected = session.selection
        hosts.forEach { (index, host) ->
            val state = states[index]
            if (!state.pinned && index != focus && !host.tracker.ownsSelection) state.switchKind(selected.context)
            host.tracker.state = state
            host.tracker.controllerFocused = index == focus
            host.tracker.visibility = if (documentReady) View.VISIBLE else View.INVISIBLE
            host.tracker.refresh()
            host.heading.text = if (index == focus) "● ${session.song.title}" else session.song.title
            host.heading.setTextColor(if (index == focus) ACCENT else MUTED)
            host.viewButton.text = state.kind.name.lowercase().replaceFirstChar { it.uppercase() }
            host.pin.text = if (state.pinned) "Pinned" else "Context"
            host.transport.isEnabled = initialized
            host.transport.text = if (requestedPlaying) "Stop" else "Play"
            host.status.text = audio?.error() ?: statusMessage
        }
    }

    private fun scheduleAudioFeedback() {
        main.removeCallbacks(audioFeedback)
        main.postDelayed(audioFeedback, 100)
    }

    private fun switchFocus() {
        focus = if (hosts.containsKey(1)) 1 - focus else 0
        workspace.save(states, focus)
        refresh()
    }

    private fun swap() {
        hosts.values.forEach { it.tracker.cancelInteraction() }
        val first = states[0]
        states[0] = states[1]
        states[1] = first
        workspace.save(states, focus)
        refresh()
    }

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
        choose("Thor Tracker", listOf("Files", "Views / layout", "Switch controller focus", "Play from song start",
            "Copy selection", "Cut selection", "Paste", "Duplicate", "Undo", "Redo", "Entry step", "Controller mappings", "Help")) { option ->
            when (option) {
                0 -> fileMenu()
                1 -> viewOptions(focus)
                2 -> switchFocus()
                3 -> startPlayback(false)
                4 -> editAction(Action.COPY)
                5 -> editAction(Action.CUT)
                6 -> editAction(Action.PASTE)
                7 -> editAction(Action.DUPLICATE)
                8 -> session.undo()
                9 -> session.redo()
                10 -> choose("Advance after note entry", listOf("Stay", "1 row", "2 rows", "4 rows", "8 rows", "16 rows")) {
                    session.step = intArrayOf(0, 1, 2, 4, 8, 16)[it]; refresh()
                }
                11 -> mappingsMenu()
                12 -> { states[focus].switchKind(ViewKind.HELP); states[focus].pinned = true; refresh() }
            }
        }
    }

    private fun mappingsMenu() {
        val codes = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_MENU)
        val labels = listOf("Up", "Down", "Left", "Right", "Edit / A", "Clear / B", "Audition / X", "Mark / Y",
            "Previous channel / L1", "Next channel / R1", "Undo / L2", "Redo / R2", "Modifier / Select",
            "Transport / Start", "Display focus / R3", "Menu")
        choose("Remap action", labels + "Reset mappings") { index ->
            if (index == codes.size) {
                remappings.clear(); workspace.saveBindings(remappings); statusMessage = "Default controls restored"; refresh()
            } else {
                statusMessage = "Press a button for ${labels[index]} (Back cancels)"
                refresh()
                captureKey = { key ->
                    if (key != KeyEvent.KEYCODE_BACK && key != KeyEvent.KEYCODE_ESCAPE) {
                        remappings.entries.removeAll { it.value == codes[index] }
                        remappings[key] = codes[index]
                        workspace.saveBindings(remappings)
                        statusMessage = "${KeyEvent.keyCodeToString(key)} → ${labels[index]}"
                    } else statusMessage = "Remapping cancelled"
                    refresh()
                }
            }
        }
    }

    private fun fileMenu() {
        choose("Project", listOf("New blank song", "Open project", "Save", "Save as…", "Export .uge", "Rename song", "Load demo", "Recover previous project")) {
            when (it) {
                0 -> confirmReplace { replace(Songs.blank()) }
                1 -> launchPicker(OPEN)
                2 -> currentUri?.let { saveProject(it) } ?: launchPicker(SAVE)
                3 -> launchPicker(SAVE)
                4 -> launchPicker(EXPORT)
                5 -> rename()
                6 -> confirmReplace { replace(Songs.demo()) }
                7 -> confirmReplace { projectStore.previous { song -> replace(song) } }
            }
        }
    }

    private fun rename() {
        val field = EditText(dialogHost().root.context).apply { setSingleLine(); setText(session.song.title); selectAll() }
        showDialog(AlertDialog.Builder(dialogHost().root.context).setTitle("Song title").setView(field)
            .setPositiveButton("Rename") { _, _ -> session.setTitle(field.text.toString().take(255)); refresh() }
            .setNegativeButton("Cancel", null))
    }

    private fun confirmReplace(action: () -> Unit) {
        showDialog(AlertDialog.Builder(dialogHost().root.context).setTitle("Replace working song?")
            .setMessage("The current song will replace the single previous-project recovery backup. Save it to a project file if you want to keep it permanently.")
            .setPositiveButton("Continue") { _, _ -> action() }.setNegativeButton("Cancel", null))
    }

    private fun replace(song: Song, uri: Uri? = null) {
        documentGeneration++
        stopPlayback()
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

    private fun dialogHost(): Host = hosts[modalDisplay]?.takeIf {
        it.index == 0 || it.root.isAttachedToWindow
    } ?: hosts.getValue(0)

    private fun showDialog(builder: AlertDialog.Builder) {
        val host = dialogHost()
        modalDisplay = host.index
        hosts.values.forEach { it.tracker.cancelInteraction() }
        dialog?.dismiss()
        dialog = builder.create().also {
            // Secondary dialogs use the presentation's context and window type.
            if (host.index != 0) it.window?.setType(checkNotNull(secondary?.window).attributes.type)
            it.show()
        }
    }

    private fun choose(title: String, labels: List<String>, selected: (Int) -> Unit) {
        showDialog(AlertDialog.Builder(dialogHost().root.context).setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> selected(which) }
            .setNegativeButton("Cancel", null))
    }

    private fun showError(message: String) {
        if (isDestroyed) return
        statusMessage = message
        android.util.Log.e("ThorTracker", message)
        showDialog(AlertDialog.Builder(dialogHost().root.context).setTitle("Thor Tracker")
            .setMessage(message).setPositiveButton("OK", null))
        refresh()
    }

    private inner class Host(context: Context, val index: Int) {
        val root = object : LinearLayout(context) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (modalDisplay != index) dialog?.dismiss()
                    modalDisplay = index
                    tracker.requestFocus()
                }
                return super.dispatchTouchEvent(event)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BASE) }
        val heading = TextView(context).apply {
            textSize = 14f; setTextColor(ACCENT); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tracker = TrackerView(context, session, states[index]).apply {
            onTouchEnded = { this@MainActivity.refresh() }
        }
        val viewButton = button(context, "View", index) { chooseView(index) }
        val pin = button(context, "Pinned", index) { viewOptions(index) }
        val transport = button(context, "Play", index) { toggleTransport() }
        val status = TextView(context).apply {
            textSize = 11f; setTextColor(MUTED); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        init {
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val title = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(context, 8), 0, dp(context, 8), 0)
                addView(heading, LinearLayout.LayoutParams(-1, -2))
                addView(status, LinearLayout.LayoutParams(-1, -2))
            }
            top.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
            top.addView(viewButton)
            top.addView(pin)
            top.addView(button(context, "Focus", index) { focus = index; refresh() })
            val wideActions = listOf(
                button(context, "Swap", index) { swap() },
                button(context, "Undo", index) { session.undo() },
                button(context, "Redo", index) { session.redo() },
                button(context, "File", index) { fileMenu() },
            )
            wideActions.forEach(top::addView)
            top.addView(button(context, "Menu", index) { appMenu() })
            top.addView(transport)
            root.addView(top, LinearLayout.LayoutParams(-1, dp(context, 48)))
            root.addView(tracker, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val visibility = if (right - left >= dp(context, 720)) View.VISIBLE else View.GONE
                wideActions.forEach { if (it.visibility != visibility) it.visibility = visibility }
            }
        }
    }

    private fun button(context: Context, label: String, host: Int, action: () -> Unit): Button = Button(context).apply {
        text = label; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setPadding(dp(context, 8), 0, dp(context, 8), 0)
        setTextColor(TEXT); setBackgroundColor(PANEL)
        layoutParams = LinearLayout.LayoutParams(-2, dp(context, 40)).apply { setMargins(2, 2, 2, 2) }
        setOnClickListener { if (documentReady) { modalDisplay = host; action() } }
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9 _-]"), "_").take(80).ifBlank { "song" }
    companion object {
        private const val OPEN = 10
        private const val SAVE = 11
        private const val EXPORT = 12
        private val BASE = Color.rgb(24, 28, 34)
        private val PANEL = Color.rgb(40, 47, 56)
        private val TEXT = Color.rgb(231, 233, 235)
        private val MUTED = Color.rgb(158, 170, 184)
        private val ACCENT = Color.rgb(234, 169, 89)
    }
}
