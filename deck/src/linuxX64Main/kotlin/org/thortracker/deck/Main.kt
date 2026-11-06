@file:OptIn(ExperimentalForeignApi::class)

package org.thortracker.deck

import cnames.structs.SDL_Renderer
import decknative.*
import kotlinx.cinterop.*
import org.thortracker.audio.PlaybackPosition
import org.thortracker.audio.Transport
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.editor.channelCount
import org.thortracker.editor.patternAt
import org.thortracker.editor.rowCount
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.HugeRules
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs
import platform.posix.*
import sdl3.*

/**
 * Steam Deck spike: the shared core's EditorSession + ViewController driven by an SDL3 gamepad, drawn with SDL's
 * debug font, heard through the C hUGEDriver engine. Pattern view only. Usage: thortracker [song.thor] [seconds=N]
 */
fun main(args: Array<String>) {
    val songPath = args.firstOrNull { it.endsWith(".thor") }
    val quitAfterMs = args.firstOrNull { it.startsWith("seconds=") }?.substringAfter('=')?.toULong()?.times(1000uL)
    // Test hook: script=96+22,20,108 taps buttons (Android key codes; "a+b" holds a while tapping b), one step per 150 ms,
    // through the same routing as the gamepad.
    val script = args.firstOrNull { it.startsWith("script=") }?.substringAfter('=')?.split(',')?.filter { it.isNotBlank() }.orEmpty()
    val song = songPath?.let { readText(it) }?.let { ProjectCodec.decode(it) } ?: Songs.demo()

    check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_AUDIO or SDL_INIT_GAMEPAD)) { "SDL_Init: ${SDL_GetError()?.toKString()}" }
    val window = SDL_CreateWindow("Thor Tracker", 1280, 800, SDL_WINDOW_FULLSCREEN or SDL_WINDOW_HIGH_PIXEL_DENSITY)
        ?: error("window: ${SDL_GetError()?.toKString()}")
    val renderer = SDL_CreateRenderer(window, null) ?: error("renderer: ${SDL_GetError()?.toKString()}")
    SDL_SetRenderVSync(renderer, 1)
    println("SDL ${SDL_GetVersion()} ${SDL_GetCurrentVideoDriver()?.toKString()}/${SDL_GetCurrentAudioDriver()?.toKString()}")

    val app = DeckApp(song)
    app.script = ArrayDeque(script)
    app.run(renderer, quitAfterMs)
    app.audio.close()
    SDL_DestroyRenderer(renderer)
    SDL_DestroyWindow(window)
    SDL_Quit()
}

private class DeckApp(song: Song) {
    val session = EditorSession(song)
    /** One pane, shown full screen (the Deck's single-screen layout). */
    val panes = Workspace(listOf(ViewState(kind = ViewKind.PATTERN)))
    val state: ViewState get() = panes.focused
    val controller = ViewController(session, panes.focused)
    val audio = DeckAudio()
    val transport = Transport(session).also { it.audio = audio }
    val keymap = Keymap.DEFAULT
    val held = HashSet<Int>()                        // Android key codes currently down
    val owners = HashMap<Int, org.thortracker.editor.Action>()  // button → action it pressed, for its release
    var repeatCode = 0
    var repeatAt = 0uL
    var running = true
    var script = ArrayDeque<String>()
    private var scriptAt = 1000uL

    init {
        controller.keys = keymap
        session.onSongChanged = { transport.songChanged(it) }
        session.onAudition = { channel, note, instrument, detune -> audio.audition(channel, note, instrument, detune) }
        session.onTransport = { start -> if (start) transport.start(session.selection.order, session.selection.row) else transport.stop() }
    }

    fun run(renderer: CPointer<SDL_Renderer>, quitAfterMs: ULong?) = memScoped {
        val event = alloc<SDL_Event>()
        val start = SDL_GetTicks()
        var frames = 0L
        var lastRaw = -1 to -1
        while (running) {
            while (SDL_PollEvent(event.ptr)) handle(event)
            val now = SDL_GetTicks()
            if (repeatCode != 0 && now >= repeatAt) { route(repeatCode, true); repeatAt = now + 60u }
            if (script.isNotEmpty() && now - start >= scriptAt) {
                val step = script.removeFirst()
                val codes = step.split('+').map { it.trim().toInt() }
                codes.forEach { button(it, true) }
                codes.reversed().forEach { button(it, false) }
                println("script $step -> order=${state.order} row=${state.row} ch=${state.channel} field=${state.field} " +
                    "cell=${session.song.patterns[session.song.patternAt(state.order)].tracks[state.channel][state.row]} " +
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
                if (raw.playing && now2 != lastRaw && now2.second % 16 == 0) println("raw ${now - start}ms: engine order ${now2.first} row ${now2.second} mapped=${transport.position()}")
                if (raw.playing) lastRaw = now2
            }
            Painter(renderer, this@DeckApp).draw()
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
        route(code, down)
        if (down && code in Pad.DPAD) { repeatCode = code; repeatAt = SDL_GetTicks() + 320u }
        if (!down && code == repeatCode) repeatCode = 0
    }

    /** Same resolution as Android's HostInput: a held modifier's chord first, otherwise the button's own binding. */
    private fun route(code: Int, down: Boolean) {
        if (!down) { owners.remove(code)?.let { controller.release(it) }; return }
        val modifier = held.firstOrNull { it in Keymap.MODIFIERS }
        val control = (modifier?.let { keymap.control(Binding(code, it), state.kind) }) ?: keymap.control(Binding(code), state.kind)
            ?: return
        when (control) {
            Control.PLAY_PATTERN, Control.PLAY_CURSOR -> {
                val (order, row) = panes.activePlace(session.selection)
                transport.toggle(order, if (control == Control.PLAY_CURSOR) row else 0)
            }
            Control.LOOP_PATTERN -> transport.toggleLoop(panes.activePlace(session.selection).first)
            else -> control.action?.let { action ->
                if (owners[code] == null || action.isDirection) { owners[code] = action; controller.press(action) }
            }
        }
    }

    fun playing(): PlaybackPosition? = transport.position()?.takeIf { it.playing }
}

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

/** Pattern view drawn with SDL's 8x8 debug font at 2x: 80 x 40 character cells on 1280 x 800. */
private class Painter(val r: CPointer<SDL_Renderer>, val app: DeckApp) {
    /** Character cell at render scale 2: the 8x8 debug font, 8 wide and 10 tall (2 px gap), so 80 x 40 cells. */
    private val lineH = 10f

    fun draw() = memScoped {
        val s = app.session.song
        val st = app.state
        app.controller.normalise()
        color(14, 17, 23); SDL_RenderClear(r)
        SDL_SetRenderScale(r, 2f, 2f)
        val pattern = s.patternAt(st.order)
        val p = s.patterns[pattern]
        val heard = app.playing()
        val playOrder = heard?.order ?: -1
        text(0, 0, "THOR TRACKER", 240, 190, 90)
        text(14, 0, "Order ${hex2(st.order)}/${hex2(s.orders.size - 1)}  Pattern ${hex2(pattern)} ${p.name}" +
            (if (st.order in s.parkedOrders) "  PARKED" else "") +
            (if (playOrder >= 0) "  > ${if (heard!!.looping) "looping" else "playing"} order ${hex2(playOrder)}" else ""), 200, 205, 215)
        val names = Songs.channelNames(s)
        val x0 = 4
        val colW = 12
        for (ch in 0 until s.channelCount()) text(x0 + ch * colW, 1, names[ch].take(10), 120, 170, 230)

        val rows = s.rowCount(pattern)
        val top = 2
        val visible = 33
        val first = (st.row - visible / 2).coerceIn(0, maxOf(0, rows - visible))
        for (i in 0 until minOf(visible, rows - first)) {
            val row = first + i
            val y = top + i
            if (playOrder == st.order && row == heard?.row) fill(0, y, 80, 1, 70, 55, 20)
            if (row == st.row) fill(0, y, 80, 1, 36, 44, 60)
            text(0, y, hex2(row), if (row % 4 == 0) 200 else 110, if (row % 4 == 0) 205 else 115, if (row % 4 == 0) 215 else 125)
            for (ch in 0 until s.channelCount()) {
                val c = p.tracks[ch][row]
                val x = x0 + ch * colW
                if (row == st.row && ch == st.channel) {
                    val (fx, fw) = when (st.field) { 0 -> 0 to 3; 1 -> 4 to 2; 2 -> 7 to 1; else -> 8 to 2 }
                    val edit = app.controller.editHeld
                    fill(x + fx, y, fw, 1, if (edit) 150 else 90, if (edit) 70 else 110, if (edit) 40 else 170)
                }
                text(x, y, cellText(c), 225, 228, 235)
            }
        }
        // Hints from the shared controller, then the session's status line.
        val hints = app.controller.hints().joinToString("   ") { "${it.keys} ${it.text}" }
        text(0, 36, hints.take(80), 150, 160, 180)
        if (hints.length > 80) text(0, 37, hints.drop(80).take(80), 150, 160, 180)
        (app.audio.error() ?: app.session.message)?.let { text(0, 39, it.take(80), 240, 190, 90) }
        SDL_SetRenderScale(r, 1f, 1f)
    }

    private fun cellText(c: Cell): String {
        val note = if (c.note == Cell.EMPTY) "---" else Songs.noteName(c.note)
        val inst = if (c.instrument == 0) ".." else hex2(c.instrument)
        val fx = if (!c.hasEffect) "..." else c.effect.toString(16).uppercase() + hex2(c.param)
        return "$note $inst $fx"
    }

    private fun MemScope.fill(x: Int, y: Int, w: Int, h: Int, red: Int, green: Int, blue: Int) {
        color(red, green, blue)
        val rect = alloc<SDL_FRect>()
        rect.x = x * 8f; rect.y = y * lineH; rect.w = w * 8f; rect.h = h * lineH
        SDL_RenderFillRect(r, rect.ptr)
    }

    private fun text(x: Int, y: Int, s: String, red: Int, green: Int, blue: Int) {
        color(red, green, blue)
        SDL_RenderDebugText(r, x * 8f, y * lineH + 1f, ascii(s))
    }

    /** The debug font is ASCII only; the shared hints use a few symbols. */
    private fun ascii(s: String): String = buildString {
        for (c in s) append(when (c) { '→' -> "->"; '←' -> "<-"; '↑' -> "^"; '↓' -> "v"; '·' -> "."; '↻' -> "@"; '±' -> "+-"; '…' -> "..."; '—', '–' -> "-"; else -> if (c.code < 128) c.toString() else "?" })
    }

    private fun color(red: Int, green: Int, blue: Int) = SDL_SetRenderDrawColor(r, red.toUByte(), green.toUByte(), blue.toUByte(), 255u)

    private fun hex2(v: Int) = EditorSession.hex2(v)
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
