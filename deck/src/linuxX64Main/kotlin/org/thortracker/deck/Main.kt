@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import cnames.structs.SDL_Renderer
import decknative.*
import kotlinx.cinterop.*
import org.thortracker.audio.PlaybackPosition
import org.thortracker.audio.Transport
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.editor.patternAt
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.ui.PaneView
import platform.posix.*
import sdl3.*

/**
 * Thor Tracker on the Steam Deck: the shared core (editing, panes, transport, every view's drawing) in one full-screen
 * pane on SDL3, driven by the gamepad and heard through the C hUGEDriver engine.
 * Usage: thortracker [song.thor] [seconds=N] [script=...]
 */
fun main(args: Array<String>) {
    val songPath = args.firstOrNull { it.endsWith(".thor") }
    val quitAfterMs = args.firstOrNull { it.startsWith("seconds=") }?.substringAfter('=')?.toULong()?.times(1000uL)
    // Test hook: script=96+22,20,108 taps buttons (Android key codes; "a+b" holds a while tapping b), one step per 150 ms,
    // through the same routing as the gamepad. With seconds=N the last frame is saved as deck-screenshot.bmp.
    val script = args.firstOrNull { it.startsWith("script=") }?.substringAfter('=')?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val song = songPath?.let { readText(it) }?.let { ProjectCodec.decode(it) } ?: Songs.demo()

    check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_AUDIO or SDL_INIT_GAMEPAD)) { "SDL_Init: ${SDL_GetError()?.toKString()}" }
    val window = SDL_CreateWindow("Thor Tracker", 1280, 800, SDL_WINDOW_FULLSCREEN or SDL_WINDOW_HIGH_PIXEL_DENSITY)
        ?: error("window: ${SDL_GetError()?.toKString()}")
    val renderer = SDL_CreateRenderer(window, null) ?: error("renderer: ${SDL_GetError()?.toKString()}")
    SDL_SetRenderVSync(renderer, 1)
    println("SDL ${SDL_GetVersion()} ${SDL_GetCurrentVideoDriver()?.toKString()}/${SDL_GetCurrentAudioDriver()?.toKString()}")
    check(dd_init(renderer, "${executableDir()}/fonts") == 0) { "fonts missing next to the executable" }

    val app = DeckApp(song, renderer)
    app.script = ArrayDeque(script)
    app.run(quitAfterMs)
    app.audio.close()
    dd_close()
    SDL_DestroyRenderer(renderer)
    SDL_DestroyWindow(window)
    SDL_Quit()
}

private class DeckApp(song: Song, private val renderer: CPointer<SDL_Renderer>) {
    val session = EditorSession(song)
    /** One pane, shown full screen (the Deck's single-screen layout); view chords switch what it shows. */
    val panes = Workspace(listOf(ViewState(kind = ViewKind.PATTERN)))
    val audio = DeckAudio()
    val transport = Transport(session).also { it.audio = audio }
    val keymap = Keymap.DEFAULT
    private val output = outputSize(renderer)
    val pane = PaneView(session, panes.focused, DENSITY, SdlSurface(), clock = { SDL_GetTicks().toLong() }, invalidate = {})
    val held = HashSet<Int>()                        // Android key codes currently down
    val owners = HashMap<Int, Action>()              // button → action it pressed, for its release
    var repeatCode = 0
    var repeatAt = 0uL
    var running = true
    var script = ArrayDeque<String>()
    private var scriptAt = 1000uL
    private var lastAudioError: String? = null

    init {
        pane.keymap = keymap
        pane.controllerFocused = true
        session.onSongChanged = { transport.songChanged(it); pane.refresh() }
        session.onStateChanged = { pane.refresh() }
        session.onAudition = { channel, note, instrument, detune -> audio.audition(channel, note, instrument, detune) }
        session.onTransport = { start -> if (start) transport.start(session.selection.order, session.selection.row) else transport.stop() }
    }

    fun run(quitAfterMs: ULong?) = memScoped {
        val event = alloc<SDL_Event>()
        val start = SDL_GetTicks()
        var frames = 0L
        var lastRaw = -1 to -1
        while (running) {
            while (SDL_PollEvent(event.ptr)) handle(event)
            val now = SDL_GetTicks()
            if (repeatCode != 0 && now >= repeatAt) { route(repeatCode, true, repeat = 1); repeatAt = now + 60u }
            if (script.isNotEmpty() && now - start >= scriptAt) {
                val step = script.removeFirst()
                val codes = step.split('+').map { it.trim().toInt() }
                codes.forEach { button(it, true) }
                codes.reversed().forEach { button(it, false) }
                val st = pane.state
                println("script $step -> view=${st.kind} order=${st.order} row=${st.row} ch=${st.channel} field=${st.field} " +
                    "cell=${session.song.patterns[session.song.patternAt(st.order)].tracks[st.channel][st.row]} " +
                    "playing=${transport.position()} msg=${session.message}")
                scriptAt = now - start + 150u
            }
            if (script.isEmpty() && quitAfterMs != null) {
                // Test log: the engine's raw position (not mapped by Transport) whenever its order or row goes backwards.
                val raw = audio.position()
                val now2 = raw.order to raw.row
                if (raw.playing && lastRaw.first >= 0 && (now2.first < lastRaw.first || (now2.first == lastRaw.first && now2.second < lastRaw.second))) {
                    println("raw wrap at ${now - start}ms: engine order ${lastRaw.first} row ${lastRaw.second} -> order ${now2.first} row ${now2.second}; engine song orders=${transport.heard(session.song).orders}")
                }
                if (raw.playing) lastRaw = now2
            }
            audio.error().let { if (it != lastAudioError) { lastAudioError = it; if (it != null) session.note(it) } }
            pane.playback = transport.position() ?: PlaybackPosition()
            pane.heldModifiers = held.filterTo(HashSet()) { it in Keymap.MODIFIERS }
            pane.draw(output.width, output.height)
            if (quitAfterMs != null && now - start > quitAfterMs) {
                running = false
                println("frames=$frames fps=${frames * 1000 / maxOf(1uL, now - start).toLong()} position=${transport.position()}")
                SDL_RenderReadPixels(renderer, null)?.let { println("screenshot: ${SDL_SaveBMP(it, "deck-screenshot.bmp")}") }
            }
            SDL_RenderPresent(renderer)
            frames++
        }
    }

    private fun handle(e: SDL_Event) {
        when (e.type) {
            SDL_EVENT_QUIT -> running = false
            SDL_EVENT_GAMEPAD_ADDED -> SDL_OpenGamepad(e.gdevice.which)?.let { println("gamepad: ${SDL_GetGamepadName(it)?.toKString()}") }
            SDL_EVENT_GAMEPAD_BUTTON_DOWN, SDL_EVENT_GAMEPAD_BUTTON_UP ->
                Pad.code(e.gbutton.button.toInt())?.let { button(it, e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) }
            SDL_EVENT_GAMEPAD_AXIS_MOTION -> {
                val code = Pad.triggerCode(e.gaxis.axis.toInt()) ?: return
                val down = e.gaxis.value > 16000
                if (down != (code in held)) button(code, down)
            }
            SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                if (e.key.key == SDLK_ESCAPE) running = false
                if (!e.key.repeat) Pad.keyboardCode(e.key.key)?.let { button(it, e.type == SDL_EVENT_KEY_DOWN) }
            }
        }
    }

    private fun button(code: Int, down: Boolean) {
        if (down) held += code else held -= code
        if (code in Keymap.MODIFIERS) return
        route(code, down, repeat = 0)
        if (down && code in Pad.DPAD) { repeatCode = code; repeatAt = SDL_GetTicks() + 320u }
        if (!down && code == repeatCode) repeatCode = 0
    }

    /** Same resolution as Android's HostInput: a held modifier's chord first, otherwise the button's own binding. */
    private fun route(code: Int, down: Boolean, repeat: Int) {
        if (!down) { owners.remove(code)?.let { pane.controllerAction(it, false, 0) }; return }
        val kind = pane.state.kind
        val modifier = held.firstOrNull { it in Keymap.MODIFIERS }
        val control = (modifier?.let { keymap.control(Binding(code, it), kind) }) ?: keymap.control(Binding(code), kind) ?: return
        if (repeat > 0 && control.action?.isDirection != true) return
        when (control) {
            Control.PLAY_PATTERN, Control.PLAY_CURSOR -> {
                val (order, row) = panes.activePlace(session.selection)
                transport.toggle(order, if (control == Control.PLAY_CURSOR) row else 0)
            }
            Control.LOOP_PATTERN -> transport.toggleLoop(panes.activePlace(session.selection).first)
            else -> when {
                control.shows != null -> { pane.cancelInteraction(); if (panes.showView(control.shows!!)) pane.refresh() }
                control.action != null -> {
                    val action = control.action!!
                    owners[code] = action
                    pane.controllerAction(action, true, repeat)
                }
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

/** SDL gamepad buttons / keyboard keys → the Android key codes the shared Keymap is written in. */
private object Pad {
    const val UP = 19; const val DOWN = 20; const val LEFT = 21; const val RIGHT = 22
    val DPAD = setOf(UP, DOWN, LEFT, RIGHT)
    fun code(button: Int): Int? = when (button) {
        SDL_GAMEPAD_BUTTON_SOUTH.toInt() -> 96        // A
        SDL_GAMEPAD_BUTTON_EAST.toInt() -> 97         // B
        SDL_GAMEPAD_BUTTON_WEST.toInt() -> 99         // X
        SDL_GAMEPAD_BUTTON_NORTH.toInt() -> 100       // Y
        SDL_GAMEPAD_BUTTON_LEFT_SHOULDER.toInt() -> 102
        SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER.toInt() -> 103
        SDL_GAMEPAD_BUTTON_LEFT_STICK.toInt() -> 106
        SDL_GAMEPAD_BUTTON_RIGHT_STICK.toInt() -> 107
        SDL_GAMEPAD_BUTTON_START.toInt() -> 108
        SDL_GAMEPAD_BUTTON_BACK.toInt() -> 109        // Select / View
        SDL_GAMEPAD_BUTTON_DPAD_UP.toInt() -> UP
        SDL_GAMEPAD_BUTTON_DPAD_DOWN.toInt() -> DOWN
        SDL_GAMEPAD_BUTTON_DPAD_LEFT.toInt() -> LEFT
        SDL_GAMEPAD_BUTTON_DPAD_RIGHT.toInt() -> RIGHT
        else -> null
    }
    fun triggerCode(axis: Int): Int? = when (axis) {
        SDL_GAMEPAD_AXIS_LEFT_TRIGGER.toInt() -> 104
        SDL_GAMEPAD_AXIS_RIGHT_TRIGGER.toInt() -> 105
        else -> null
    }
    fun keyboardCode(key: UInt): Int? = when (key) {
        SDLK_UP -> UP; SDLK_DOWN -> DOWN; SDLK_LEFT -> LEFT; SDLK_RIGHT -> RIGHT
        SDLK_Z -> 96; SDLK_X -> 97; SDLK_A -> 99; SDLK_S -> 100; SDLK_Q -> 102; SDLK_W -> 103
        SDLK_1 -> 104; SDLK_2 -> 105; SDLK_RETURN -> 108; SDLK_TAB -> 109
        else -> null
    }
}

/** The directory holding this executable (fonts ship beside it). */
private fun executableDir(): String = memScoped {
    val buf = allocArray<ByteVar>(4096)
    val n = readlink("/proc/self/exe", buf, 4095u)
    if (n <= 0) return "."
    buf[n.toInt()] = 0
    buf.toKString().substringBeforeLast('/')
}

private fun readText(path: String): String? {
    val f = fopen(path, "rb") ?: return null
    try {
        fseek(f, 0, SEEK_END)
        val n = ftell(f).toInt()
        fseek(f, 0, SEEK_SET)
        val bytes = ByteArray(n)
        bytes.usePinned { fread(it.addressOf(0), 1u, n.convert(), f) }
        return bytes.decodeToString()
    } finally {
        fclose(f)
    }
}
