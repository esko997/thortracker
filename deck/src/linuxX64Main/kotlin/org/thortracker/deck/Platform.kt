@file:OptIn(ExperimentalForeignApi::class)
package org.thortracker.deck

import kotlinx.cinterop.*
import org.thortracker.input.Keys
import sdl3.*

/** SDL3 owns only window, input and presentation; the tracker host is shared with Brick. */
class Platform(width: Int, height: Int, fullscreen: Boolean) {
    init { check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_AUDIO or SDL_INIT_GAMEPAD)) { SDL_GetError()?.toKString() ?: "SDL init failed" } }
    private val window = SDL_CreateWindow("Thor Tracker", width, height,
        (if (fullscreen) SDL_WINDOW_FULLSCREEN else 0uL) or SDL_WINDOW_HIGH_PIXEL_DENSITY)
        ?: error("window: ${SDL_GetError()?.toKString()}")
    val renderer = SDL_CreateRenderer(window, null) ?: error("renderer: ${SDL_GetError()?.toKString()}")
    val output = memScoped {
        val w = alloc<IntVar>(); val h = alloc<IntVar>()
        SDL_GetRenderOutputSize(renderer, w.ptr, h.ptr)
        Output(w.value, h.value)
    }
    private val gamepads = mutableMapOf<Int, CPointer<cnames.structs.SDL_Gamepad>>()
    private val event = nativeHeap.alloc<SDL_Event>()
    val audioDriver get() = SDL_GetCurrentAudioDriver()?.toKString() ?: "none"
    val videoDriver get() = SDL_GetCurrentVideoDriver()?.toKString() ?: "none"
    init {
        SDL_SetRenderVSync(renderer, 1)
        println("SDL ${SDL_GetVersion()} $videoDriver/$audioDriver")
    }
    fun ticks(): ULong = SDL_GetTicks()
    fun textInput(enabled: Boolean) { if (enabled) SDL_StartTextInput(window) else SDL_StopTextInput(window) }
    fun present() { SDL_RenderPresent(renderer) }
    fun screenshot(): Boolean {
        val image = SDL_RenderReadPixels(renderer, null) ?: return false
        return try { SDL_SaveBMP(image, "deck-screenshot.bmp") } finally { SDL_DestroySurface(image) }
    }
    fun close() {
        gamepads.values.forEach { SDL_CloseGamepad(it) }; gamepads.clear()
        nativeHeap.free(event.ptr)
        SDL_DestroyRenderer(renderer); SDL_DestroyWindow(window); SDL_Quit()
    }
    fun poll(app: DeckApp) {
        while (SDL_PollEvent(event.ptr)) {
            val e = event
            when (e.type) {
                SDL_EVENT_QUIT -> app.running = false
                SDL_EVENT_GAMEPAD_ADDED -> {
                    SDL_OpenGamepad(e.gdevice.which)?.let { gamepads[e.gdevice.which.toInt()] = it; println("gamepad: ${SDL_GetGamepadName(it)?.toKString()}") }
                    app.input.resetStick()
                }
                SDL_EVENT_GAMEPAD_REMOVED -> { gamepads.remove(e.gdevice.which.toInt())?.let { SDL_CloseGamepad(it) }; app.focusChanged() }
                SDL_EVENT_GAMEPAD_REMAPPED -> app.focusChanged()
                SDL_EVENT_WINDOW_FOCUS_LOST, SDL_EVENT_WINDOW_FOCUS_GAINED -> app.focusChanged()
                SDL_EVENT_GAMEPAD_BUTTON_DOWN, SDL_EVENT_GAMEPAD_BUTTON_UP ->
                    Pad.code(e.gbutton.button.toInt())?.let { app.button(it, e.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) }
                SDL_EVENT_GAMEPAD_AXIS_MOTION -> {
                    val axis = e.gaxis.axis.toInt()
                    if (axis == SDL_GAMEPAD_AXIS_RIGHTX.toInt() || axis == SDL_GAMEPAD_AXIS_RIGHTY.toInt()) {
                        app.rightStick(e.gaxis.which.toInt(), axis == SDL_GAMEPAD_AXIS_RIGHTX.toInt(), e.gaxis.value / 32767f,
                            (e.gaxis.timestamp / 1_000_000uL).toLong())
                    } else Pad.triggerCode(axis)?.let { code ->
                        val down = e.gaxis.value > 16000
                        if (down != (code in app.input.heldModifiers)) app.button(code, down)
                    }
                }
                SDL_EVENT_TEXT_INPUT -> e.text.text?.toKString()?.let(app::type)
                SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP -> {
                    val down = e.type == SDL_EVENT_KEY_DOWN
                    if (app.fieldFocused) {
                        if (down) when (e.key.key) {
                            SDLK_BACKSPACE -> app.editText(67)
                            SDLK_RETURN, SDLK_KP_ENTER -> app.editText(Keys.ENTER)
                            SDLK_ESCAPE -> app.editText(Keys.ESCAPE)
                        }
                    } else if (!e.key.repeat) Pad.keyboardCode(e.key.key)?.let { app.button(it, down) }
                }
            }
        }
    }
    companion object { const val NAME = "Steam Deck / desktop Linux"; const val WIDTH = 1280; const val HEIGHT = 800; const val DENSITY = 1.5f }
}

private object Pad {
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

