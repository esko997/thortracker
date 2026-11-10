@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import decknative.*
import kotlinx.cinterop.*
import org.thortracker.audio.AuditionLoop
import org.thortracker.audio.PlaybackPosition
import org.thortracker.audio.Transport
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.editor.patternAt
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.audio.WavExport
import org.thortracker.system.ay.AyVgm
import org.thortracker.system.opl3.Opl3Vgm
import org.thortracker.system.ws.WsVgm
import org.thortracker.system.vsu.VsuVgm
import org.thortracker.system.saa.SaaVgm
import org.thortracker.system.pokey.PokeyVgm
import org.thortracker.system.opn.OpnVgm
import org.thortracker.system.pce.PceVgm
import org.thortracker.system.scc.SccVgm
import org.thortracker.system.sms.SmsExport
import org.thortracker.system.opm.OpmVgm
import org.thortracker.system.ym2612.YmExport
import org.thortracker.ui.AppMenus
import org.thortracker.ui.DrawnPopup
import org.thortracker.ui.HostInput
import org.thortracker.ui.PaneView
import org.thortracker.ui.Popups
import org.thortracker.ui.RecentMessages
import platform.posix.*

/**
 * Thor Tracker's shared native Linux application: editing, panes, transport, views, button routing and menus
 * in one full-screen pane, with target-specific SDL display/input and the shared C chip engine ([DeckAudio]).
 * Usage: thortracker [song.thor] [seconds=N] [script=...] [size=WxH[@density]]
 */
fun main(args: Array<String>) {
    val songPath = args.firstOrNull { it.endsWith(".thor") }
    val quitAfterMs = args.firstOrNull { it.startsWith("seconds=") }?.substringAfter('=')?.toULong()?.times(1000uL)
    // Test hook: script=96+22,20,99~1500 taps buttons (Android key codes; "a+b" holds a while tapping b, "b~ms" holds b
    // for ms), one step per 150 ms,
    // through the same routing as the gamepad; "rx=V"/"ry=V" moves one right-stick axis (-1 left/up .. 1 right/down),
    // e.g. rx=-1,rx=0,rx=-1,rx=0 jumps to the first channel. With seconds=N the platform saves its last frame.
    val script = args.firstOrNull { it.startsWith("script=") }?.substringAfter('=')?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    // Test hook: size=WxH[@density] draws into a WxH window (no full screen) at that UI density, e.g. the Thor's displays
    // (1920x1080@2.31, 1240x1080@2.31) under SDL_VIDEO_DRIVER=offscreen, whose full-screen mode is 1024 x 768.
    val size = args.firstOrNull { it.startsWith("size=") }?.substringAfter('=')
    val (width, height) = size?.substringBefore('@')?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: listOf(Platform.WIDTH, Platform.HEIGHT)
    val density = size?.substringAfter('@', "")?.toFloatOrNull() ?: Platform.DENSITY

    val platform = Platform(width, height, size == null)
    val appDir = executableDir()
    check(dd_init(platform.renderer, "$appDir/fonts") == 0) { "fonts missing next to the executable" }

    Opl3Vgm.renderer = DeckOpl3Vgm
    WsVgm.renderer = DeckWsVgm
    VsuVgm.renderer = DeckVsuVgm
    SaaVgm.renderer = DeckSaaVgm
    PokeyVgm.renderer = DeckPokeyVgm
    OpnVgm.renderer = DeckOpnVgm
    SccVgm.renderer = DeckSccVgm
    PceVgm.renderer = DeckPceVgm
    OpmVgm.renderer = DeckOpmVgm
    YmExport.renderer = DeckYmExport
    AyVgm.renderer = DeckAyVgm
    SmsExport.renderer = DeckSmsVgm
    WavExport.renderer = DeckWav
    try {
        val app = DeckApp(platform, appDir, songPath?.let { Files.readText(it) }?.let { ProjectCodec.decode(it) }, density)
        try {
            app.script = ArrayDeque(script)
            app.run(quitAfterMs)
        } finally { app.close() }
    } finally {
        dd_close()
        platform.close()
    }
}

class DeckApp(private val platform: Platform, appDir: String, opened: Song?, density: Float) {
    val session = EditorSession(Songs.demo())
    /** One pane, shown full screen (the Deck's single-screen layout); view chords switch what it shows. */
    val panes = Workspace(listOf(ViewState(kind = ViewKind.PATTERN)))
    val audio = DeckAudio { platform.audioDriver }
    val transport = Transport(session).also { it.audio = audio }
    private val output = platform.output
    val pane = PaneView(session, panes.focused, density, SdlSurface(), clock = { platform.ticks().toLong() }, invalidate = {})
    private val popupInk = org.thortracker.ui.Ink(density, pane.surface)
    private val popup = DrawnPopup(popupInk)
    val popups = Popups({ if (it == 0) popup else null }, { listOf(0) })
    val input = HostInput()
    var running = true
    val shell = DeckShell(session, pane, popups, input, appDir, panes, quit = { running = false }, audio = audio, display = {
        "${output.width}x${output.height} px at density $density, SDL video ${platform.videoDriver}"
    })
    val menus = AppMenus(session, panes, transport, popups, shell)
    private var repeatCode = 0
    private var repeatAt = 0uL
    private var repeats = 0
    var script = ArrayDeque<String>()
    private var scriptAt = 1000uL
    private var heldScript: Pair<List<Int>, ULong>? = null
    private var lastAudioError: String? = null
    private var textInput = false
    private val auditionLoop = AuditionLoop()

    init {
        pane.keymap = shell.keymap
        input.keymap = shell.keymap
        popups.keys = shell.keymap
        pane.controllerFocused = true
        session.onMenuRequest = { build -> menus.open(build) }
        session.onSongChanged = { transport.songChanged(it); pane.refresh() }
        session.onStateChanged = { pane.refresh() }
        session.onAudition = { channel, note, instrument, detune -> auditionLoop.stop(); audio.audition(channel, note, instrument, detune) }
        session.onAuditionSequence = { channel, steps, instrument, stepMillis -> auditionLoop.start(channel, steps, instrument, stepMillis, platform.ticks().toLong()) }
        session.onReleaseAudition = { auditionLoop.stop(); audio.releaseAudition() }
        session.onTransport = { start -> if (start) transport.start(session.selection.order, session.selection.row) else transport.stop() }
        val start = opened ?: shell.recovered()
        if (start != null) { session.replaceSong(start); session.note(if (opened != null) "${start.title} opened" else "Recovered working project") }
        popups.onOpenChanged = { open -> input.resetStick(); if (open) pane.cancelInteraction() }
    }

    fun run(quitAfterMs: ULong?) {
        val start = platform.ticks()
        var frames = 0L
        while (running) {
            platform.poll(this)
            val now = platform.ticks()
            if (repeatCode != 0 && now >= repeatAt) { route(repeatCode, true, ++repeats); repeatAt = now + 60u }
            if (script.isNotEmpty() && now - start >= scriptAt) {
                val step = script.removeFirst()
                // "rx=V"/"ry=V" moves one right-stick axis; "a+b" holds a while tapping b; "b~ms" holds b
                // for ms (the next step waits until it is released).
                val stick = if (step.startsWith("rx=") || step.startsWith("ry=")) step.substringAfter('=').toFloat() else null
                val held = if (stick != null) null else step.substringAfter('~', "").toULongOrNull()
                if (stick != null) rightStick(SCRIPT_STICK, step.startsWith("rx="), stick, now.toLong())
                else {
                    val codes = step.substringBefore('~').split('+').map { it.trim().toInt() }
                    codes.forEach { button(it, true) }
                    if (held != null) { heldScript = codes to now + held } else codes.reversed().forEach { button(it, false) }
                }
                val st = pane.state
                println("script $step -> view=${st.kind} popup=${popups.top?.title} order=${st.order} row=${st.row} channel=${st.channel} field=${st.field} " +
                    "cell=${session.song.patterns[session.song.patternAt(st.order)].tracks[st.channel][st.row]} msg=${session.message}")
                scriptAt = now - start + 150u + (held ?: 0u)
            }
            heldScript?.let { (codes, until) ->
                if (now >= until) { codes.reversed().forEach { button(it, false) }; heldScript = null; println("released ${codes.joinToString("+")}: auditions=${audio.auditions} peak=${da_peak()}") }
            }
            auditionLoop.advance(now.toLong()) { ch, note, ins -> audio.audition(ch, note, ins) }
            audio.error().let { if (it != lastAudioError) { lastAudioError = it; if (it != null) { RecentMessages.record("audio", it); session.note(it) } } }
            shell.pumpExport()
            shell.pumpTuningPacks()
            shell.autosave(now)
            val typing = popup.fieldFocused
            if (typing != textInput) { textInput = typing; platform.textInput(typing) }
            pane.playback = transport.position() ?: PlaybackPosition()
            pane.heldModifiers = if (popups.isOpen) emptySet() else input.heldModifiers
            pane.draw(output.width, output.height)
            popupInk.palette = pane.palette
            popup.draw(pane.surface, output.width.toFloat(), output.height.toFloat())
            if (quitAfterMs != null && now - start > quitAfterMs) {
                running = false
                println("audio: auditions=${audio.auditions} peak=${da_peak()}")
                println("frames=$frames fps=${frames * 1000 / maxOf(1uL, now - start).toLong()} position=${transport.position()} popup=${popups.top?.title}")
                println("screenshot: ${platform.screenshot()}")
            }
            platform.present()
            frames++
        }
        shell.autosave(platform.ticks(), force = true)
    }

    fun close() { shell.autosave(platform.ticks(), force = true); shell.close(); audio.close(); (pane.surface as SdlSurface).close() }

    fun focusChanged() { repeatCode = 0; input.reset(); pane.cancelInteraction() }
    fun type(text: String) = popup.type(text)
    val fieldFocused: Boolean get() = popup.fieldFocused
    fun editText(code: Int) {
        when (code) {
            67 -> popup.erase() // Android DEL, used only by physical keyboard text entry.
            Keys.ENTER -> { popup.stopTyping(); popups.handleKey(Keys.ENTER, true, 0) }
            Keys.ESCAPE -> popup.stopTyping()
        }
    }

    fun button(code: Int, down: Boolean) {
        route(code, down, 0)
        if (down && code in DPAD) { repeatCode = code; repeats = 0; repeatAt = platform.ticks() + 320u }
        if (!down && code == repeatCode) repeatCode = 0
    }

    /** Independent right-stick axes: double left/right jumps channels, double up/down jumps rows. */
    fun rightStick(device: Int, horizontal: Boolean, value: Float, millis: Long) {
        val jump = input.rightStick(device, horizontal, value.coerceIn(-1f, 1f), millis, 0, pane.state.kind, pane.patternFocused, popups.isOpen, false)
        if (jump != 0) {
            if (horizontal) pane.jumpPatternChannelEdge(jump > 0)
            else pane.jumpPatternEdge(jump > 0)
        }
    }

    /** The same layers as Android's MainActivity.routeKey: binding capture, popup, host controls, then the pane. */
    private fun route(code: Int, down: Boolean, repeat: Int) {
        popups.top?.capture?.let { capture ->
            // The capture takes the key, but the trigger edges above read [HostInput.heldModifiers]: keep them true, so
            // a trigger let go during the capture is released there too and never joins the chord.
            input.trackModifier(code, down)
            if (repeat == 0) capture(code, down)
            return
        }
        when (val r = input.route(code, down, repeat, popups.isOpen, false, pane.state.kind)) {
            is HostInput.Route.Run -> run(r.control)
            HostInput.Route.Menu -> menus.appMenu()
            HostInput.Route.Pass -> {}
            is HostInput.Route.Editor -> pane.controllerAction(r.action, down, repeat)
            is HostInput.Route.Deliver -> if (r.target == HostInput.Target.POPUP) popups.handleKey(r.code, down, repeat)
        }
    }

    private fun run(control: Control) {
        when (control) {
            Control.PLAY_PATTERN, Control.PLAY_CURSOR -> {
                val (order, row) = panes.activePlace(session.selection)
                transport.toggle(order, if (control == Control.PLAY_CURSOR) row else 0)
            }
            Control.LOOP_PATTERN -> transport.toggleLoop(panes.activePlace(session.selection).first)
            // No toolbar on the Deck: its button opens the menu.
            Control.TOOLBAR -> menus.appMenu()
            Control.SWAP, Control.FOCUS -> {}
            Control.ZOOM_IN -> menus.zoom(panes.focus, 1)
            Control.ZOOM_OUT -> menus.zoom(panes.focus, -1)
            Control.ZOOM_RESET -> menus.zoom(panes.focus, 0)
            else -> {
                // One pane: other-display view chords show their view on it, like the R2 ones.
                val shows = control.shows
                val action = control.action
                if (shows != null) {
                    pane.cancelInteraction()
                    if (panes.showView(shows, session.song.system.views)) pane.refresh()
                    else Workspace.missingView(shows, session.song.system)?.let(session::note)
                }
                else if (action != null) shell.editAction(action)
            }
        }
    }
}

class Output(val width: Int, val height: Int)

/** Device id the script hook's "ry=" steps use; SDL joystick ids are never 0 or negative. */
private const val SCRIPT_STICK = -1

private val DPAD = setOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT)

/** The directory holding this executable (fonts and tunings ship beside it). */
private fun executableDir(): String = memScoped {
    val buf = allocArray<ByteVar>(4096)
    val n = readlink("/proc/self/exe", buf, 4095u)
    if (n <= 0) return "."
    buf[n.toInt()] = 0
    buf.toKString().substringBeforeLast('/')
}
