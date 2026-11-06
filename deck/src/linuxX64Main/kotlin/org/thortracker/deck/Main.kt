@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import cnames.structs.SDL_Renderer
import cnames.structs.SDL_Window
import decknative.*
import kotlinx.cinterop.*
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
import org.thortracker.ui.AppMenus
import org.thortracker.ui.DrawnPopup
import org.thortracker.ui.HostInput
import org.thortracker.ui.PaneView
import org.thortracker.ui.Popups
import platform.posix.*
import sdl3.*

/**
 * Thor Tracker on the Steam Deck: the shared core (editing, panes, transport, views, button routing, menus) in one
 * full-screen pane on SDL3, driven by the gamepad and heard through the C hUGEDriver engine.
 * Usage: thortracker [song.thor] [seconds=N] [script=...]
 */
fun main(args: Array<String>) {
    val songPath = args.firstOrNull { it.endsWith(".thor") }
    val quitAfterMs = args.firstOrNull { it.startsWith("seconds=") }?.substringAfter('=')?.toULong()?.times(1000uL)
    // Test hook: script=96+22,20,108 taps buttons (Android key codes; "a+b" holds a while tapping b), one step per 150 ms,
    // through the same routing as the gamepad. With seconds=N the last frame is saved as deck-screenshot.bmp.
    val script = args.firstOrNull { it.startsWith("script=") }?.substringAfter('=')?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_AUDIO or SDL_INIT_GAMEPAD)) { "SDL_Init: ${SDL_GetError()?.toKString()}" }
    val window = SDL_CreateWindow("Thor Tracker", 1280, 800, SDL_WINDOW_FULLSCREEN or SDL_WINDOW_HIGH_PIXEL_DENSITY)
        ?: error("window: ${SDL_GetError()?.toKString()}")
    val renderer = SDL_CreateRenderer(window, null) ?: error("renderer: ${SDL_GetError()?.toKString()}")
    SDL_SetRenderVSync(renderer, 1)
    println("SDL ${SDL_GetVersion()} ${SDL_GetCurrentVideoDriver()?.toKString()}/${SDL_GetCurrentAudioDriver()?.toKString()}")
    val appDir = executableDir()
    check(dd_init(renderer, "$appDir/fonts") == 0) { "fonts missing next to the executable" }

    val app = DeckApp(window, renderer, appDir, songPath?.let { Files.readText(it) }?.let { ProjectCodec.decode(it) })
    app.script = ArrayDeque(script)
    app.run(quitAfterMs)
    app.close()
    dd_close()
    SDL_DestroyRenderer(renderer)
    SDL_DestroyWindow(window)
    SDL_Quit()
}

private class DeckApp(private val window: CPointer<SDL_Window>, private val renderer: CPointer<SDL_Renderer>, appDir: String, opened: Song?) {
    val session = EditorSession(Songs.demo())
    /** One pane, shown full screen (the Deck's single-screen layout); view chords switch what it shows. */
    val panes = Workspace(listOf(ViewState(kind = ViewKind.PATTERN)))
    val audio = DeckAudio()
    val transport = Transport(session).also { it.audio = audio }
    private val output = outputSize(renderer)
    val pane = PaneView(session, panes.focused, DENSITY, SdlSurface(), clock = { SDL_GetTicks().toLong() }, invalidate = {})
    private val popup = DrawnPopup(pane.ink)
    val popups = Popups({ if (it == 0) popup else null }, { listOf(0) })
    val input = HostInput()
    var running = true
    val shell = DeckShell(session, pane, popups, input, appDir, quit = { running = false })
    val menus = AppMenus(session, panes, transport, popups, shell)
    private var repeatCode = 0
    private var repeatAt = 0uL
    private var repeats = 0
    var script = ArrayDeque<String>()
    private var scriptAt = 1000uL
    private var lastAudioError: String? = null
    private var textInput = false

    init {
        pane.keymap = shell.keymap
        input.keymap = shell.keymap
        popups.keys = shell.keymap
        pane.controllerFocused = true
        session.onSongChanged = { transport.songChanged(it); pane.refresh() }
        session.onStateChanged = { pane.refresh() }
        session.onAudition = { channel, note, instrument, detune -> audio.audition(channel, note, instrument, detune) }
        session.onTransport = { start -> if (start) transport.start(session.selection.order, session.selection.row) else transport.stop() }
        val start = opened ?: shell.recovered()
        if (start != null) { session.replaceSong(start); session.note(if (opened != null) "${start.title} opened" else "Recovered working project") }
        popups.onOpenChanged = { open -> if (open) pane.cancelInteraction() }
    }

    fun run(quitAfterMs: ULong?) = memScoped {
        val event = alloc<SDL_Event>()
        val start = SDL_GetTicks()
        var frames = 0L
        while (running) {
            while (SDL_PollEvent(event.ptr)) handle(event)
            val now = SDL_GetTicks()
            if (repeatCode != 0 && now >= repeatAt) { route(repeatCode, true, ++repeats); repeatAt = now + 60u }
            if (script.isNotEmpty() && now - start >= scriptAt) {
                val step = script.removeFirst()
                val codes = step.split('+').map { it.trim().toInt() }
                codes.forEach { button(it, true) }
                codes.reversed().forEach { button(it, false) }
                val st = pane.state
                println("script $step -> view=${st.kind} popup=${popups.top?.title} order=${st.order} row=${st.row} " +
                    "cell=${session.song.patterns[session.song.patternAt(st.order)].tracks[st.channel][st.row]} msg=${session.message}")
                scriptAt = now - start + 150u
            }
            audio.error().let { if (it != lastAudioError) { lastAudioError = it; if (it != null) session.note(it) } }
            shell.autosave(now)
            val typing = popup.fieldFocused
            if (typing != textInput) { textInput = typing; if (typing) SDL_StartTextInput(window) else SDL_StopTextInput(window) }
            pane.playback = transport.position() ?: PlaybackPosition()
            pane.heldModifiers = if (popups.isOpen) emptySet() else input.heldModifiers
            pane.draw(output.width, output.height)
            popup.draw(pane.surface, output.width.toFloat(), output.height.toFloat())
            if (quitAfterMs != null && now - start > quitAfterMs) {
                running = false
                println("frames=$frames fps=${frames * 1000 / maxOf(1uL, now - start).toLong()} position=${transport.position()} popup=${popups.top?.title}")
                SDL_RenderReadPixels(renderer, null)?.let { println("screenshot: ${SDL_SaveBMP(it, "deck-screenshot.bmp")}") }
            }
            SDL_RenderPresent(renderer)
            frames++
        }
        shell.autosave(SDL_GetTicks(), force = true)
    }

    fun close() = audio.close()

    private fun handle(e: SDL_Event) {
        when (e.type) {
            SDL_EVENT_QUIT -> running = false
            SDL_EVENT_GAMEPAD_ADDED -> SDL_OpenGamepad(e.gdevice.which)?.let { println("gamepad: ${SDL_GetGamepadName(it)?.toKString()}") }
            SDL_EVENT_GAMEPAD_BUTTON_DOWN, SDL_EVENT_GAMEPAD_BUTTON_UP ->
                Pad.code(e.gbutton.button.toInt())?.let { button(it, e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) }
            SDL_EVENT_GAMEPAD_AXIS_MOTION -> {
                val code = Pad.triggerCode(e.gaxis.axis.toInt()) ?: return
                val down = e.gaxis.value > 16000
                if (down != (code in input.heldModifiers)) button(code, down)
            }
            SDL_EVENT_TEXT_INPUT -> e.text.text?.toKString()?.let(popup::type)
            SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                val down = e.type == SDL_EVENT_KEY_DOWN
                if (popup.fieldFocused) {
                    // Typing goes to the field; Backspace edits, Enter submits, Escape leaves the field.
                    if (down) when (e.key.key) {
                        SDLK_BACKSPACE -> popup.erase()
                        SDLK_RETURN, SDLK_KP_ENTER -> { popup.stopTyping(); popups.handleKey(Keys.ENTER, true, 0) }
                        SDLK_ESCAPE -> popup.stopTyping()
                    }
                    return
                }
                if (!e.key.repeat) Pad.keyboardCode(e.key.key)?.let { button(it, down) }
            }
        }
    }

    private fun button(code: Int, down: Boolean) {
        route(code, down, 0)
        if (down && code in Pad.DPAD) { repeatCode = code; repeats = 0; repeatAt = SDL_GetTicks() + 320u }
        if (!down && code == repeatCode) repeatCode = 0
    }

    /** The same layers as Android's MainActivity.routeKey: binding capture, popup, host controls, then the pane. */
    private fun route(code: Int, down: Boolean, repeat: Int) {
        popups.top?.capture?.let { capture ->
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
            else -> {
                val shows = control.shows
                val action = control.action
                if (shows != null) { pane.cancelInteraction(); if (panes.showView(shows)) pane.refresh() }
                else if (action != null) shell.editAction(action)
            }
        }
    }
}

/** The renderer's output size in pixels (the Deck's panel: 1280 x 800). */
private class Output(val width: Int, val height: Int)

private fun outputSize(renderer: CPointer<SDL_Renderer>): Output = memScoped {
    val w = alloc<IntVar>()
    val h = alloc<IntVar>()
    SDL_GetRenderOutputSize(renderer, w.ptr, h.ptr)
    Output(w.value, h.value)
}

/** UI scale like Android's density: 12 x 1.5 = 18 px text on the Deck's 1280 x 800 seven-inch panel. */
private const val DENSITY = 1.5f

/** SDL gamepad buttons / keyboard keys → the Android key codes the shared Keymap and HostInput use. */
private object Pad {
    val DPAD = setOf(Keys.DPAD_UP, Keys.DPAD_DOWN, Keys.DPAD_LEFT, Keys.DPAD_RIGHT)
    fun code(button: Int): Int? = when (button) {
        SDL_GAMEPAD_BUTTON_SOUTH.toInt() -> Keys.BUTTON_A
        SDL_GAMEPAD_BUTTON_EAST.toInt() -> Keys.BUTTON_B
        SDL_GAMEPAD_BUTTON_WEST.toInt() -> Keys.BUTTON_X
        SDL_GAMEPAD_BUTTON_NORTH.toInt() -> Keys.BUTTON_Y
        SDL_GAMEPAD_BUTTON_LEFT_SHOULDER.toInt() -> Keys.BUTTON_L1
        SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER.toInt() -> Keys.BUTTON_R1
        SDL_GAMEPAD_BUTTON_LEFT_STICK.toInt() -> Keys.BUTTON_THUMBL
        SDL_GAMEPAD_BUTTON_RIGHT_STICK.toInt() -> Keys.BUTTON_THUMBR
        SDL_GAMEPAD_BUTTON_START.toInt() -> Keys.BUTTON_START
        SDL_GAMEPAD_BUTTON_BACK.toInt() -> Keys.BUTTON_SELECT        // Select / View
        // The back grips (L4, R4, L5, R5) open the menu, like the Thor's Menu button.
        SDL_GAMEPAD_BUTTON_LEFT_PADDLE1.toInt(), SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1.toInt(),
        SDL_GAMEPAD_BUTTON_LEFT_PADDLE2.toInt(), SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2.toInt() -> Keys.BUTTON_MODE
        SDL_GAMEPAD_BUTTON_DPAD_UP.toInt() -> Keys.DPAD_UP
        SDL_GAMEPAD_BUTTON_DPAD_DOWN.toInt() -> Keys.DPAD_DOWN
        SDL_GAMEPAD_BUTTON_DPAD_LEFT.toInt() -> Keys.DPAD_LEFT
        SDL_GAMEPAD_BUTTON_DPAD_RIGHT.toInt() -> Keys.DPAD_RIGHT
        else -> null
    }
    fun triggerCode(axis: Int): Int? = when (axis) {
        SDL_GAMEPAD_AXIS_LEFT_TRIGGER.toInt() -> Keys.BUTTON_L2
        SDL_GAMEPAD_AXIS_RIGHT_TRIGGER.toInt() -> Keys.BUTTON_R2
        else -> null
    }
    fun keyboardCode(key: UInt): Int? = when (key) {
        SDLK_UP -> Keys.DPAD_UP; SDLK_DOWN -> Keys.DPAD_DOWN; SDLK_LEFT -> Keys.DPAD_LEFT; SDLK_RIGHT -> Keys.DPAD_RIGHT
        SDLK_Z -> Keys.BUTTON_A; SDLK_X -> Keys.BUTTON_B; SDLK_A -> Keys.BUTTON_X; SDLK_S -> Keys.BUTTON_Y
        SDLK_Q -> Keys.BUTTON_L1; SDLK_W -> Keys.BUTTON_R1; SDLK_1 -> Keys.BUTTON_L2; SDLK_2 -> Keys.BUTTON_R2
        SDLK_SPACE -> Keys.BUTTON_START; SDLK_TAB -> Keys.BUTTON_SELECT
        SDLK_RETURN, SDLK_KP_ENTER -> Keys.ENTER; SDLK_ESCAPE -> Keys.ESCAPE; SDLK_F1 -> Keys.MENU
        else -> null
    }
}

/** The directory holding this executable (fonts and tunings ship beside it). */
private fun executableDir(): String = memScoped {
    val buf = allocArray<ByteVar>(4096)
    val n = readlink("/proc/self/exe", buf, 4095u)
    if (n <= 0) return "."
    buf[n.toInt()] = 0
    buf.toKString().substringBeforeLast('/')
}
