@file:OptIn(ExperimentalForeignApi::class)
package org.thortracker.deck

import kotlinx.cinterop.*
import org.thortracker.input.Keys
import sdl2.*

/** Vendor SDL2 owns display, controllers and audio; no SDL3 API emulation or tracker logic lives here. */
class Platform(width: Int, height: Int, fullscreen: Boolean) {
    init {
        check(SDL_Init(SDL_INIT_VIDEO or SDL_INIT_AUDIO or SDL_INIT_GAMECONTROLLER) == 0) {
            "SDL_Init: ${SDL_GetError()?.toKString()}"
        }
    }
    private val window = SDL_CreateWindow("Thor Tracker", SDL_WINDOWPOS_UNDEFINED.toInt(), SDL_WINDOWPOS_UNDEFINED.toInt(),
        width, height, if (fullscreen) SDL_WINDOW_FULLSCREEN_DESKTOP else 0u)
        ?: error("window: ${SDL_GetError()?.toKString()}")
    val renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_ACCELERATED or SDL_RENDERER_PRESENTVSYNC)
        ?: error("accelerated renderer: ${SDL_GetError()?.toKString()}")
    val output = memScoped {
        val w = alloc<IntVar>(); val h = alloc<IntVar>()
        check(SDL_GetRendererOutputSize(renderer, w.ptr, h.ptr) == 0) { SDL_GetError()?.toKString() ?: "output size failed" }
        Output(w.value, h.value)
    }
    private val controllers = mutableMapOf<Int, CPointer<cnames.structs._SDL_GameController>>()
    private val triggerDown = mutableSetOf<Int>()
    private val event = nativeHeap.alloc<SDL_Event>()
    val audioDriver get() = SDL_GetCurrentAudioDriver()?.toKString() ?: "none"
    val videoDriver get() = SDL_GetCurrentVideoDriver()?.toKString() ?: "none"
    init {
        SDL_ShowCursor(SDL_DISABLE)
        memScoped {
            val version = alloc<SDL_version>()
            SDL_GetVersion(version.ptr)
            println("SDL ${version.major}.${version.minor}.${version.patch} $videoDriver/$audioDriver output=${output.width}x${output.height}")
        }
        for (index in 0 until SDL_NumJoysticks()) openController(index)
    }
    private fun openController(index: Int) {
        if (SDL_IsGameController(index) != SDL_TRUE) return
        val instance = SDL_JoystickGetDeviceInstanceID(index)
        if (controllers.containsKey(instance)) return
        SDL_GameControllerOpen(index)?.let {
            controllers[instance] = it
            println("gamepad: ${SDL_GameControllerName(it)?.toKString()}")
        }
    }
    fun ticks(): ULong = SDL_GetTicks64()
    fun textInput(enabled: Boolean) { if (enabled) SDL_StartTextInput() else SDL_StopTextInput() }
    fun present() { SDL_RenderPresent(renderer) }
    fun screenshot(): Boolean {
        val image = SDL_CreateRGBSurfaceWithFormat(0u, output.width, output.height, 32, SDL_PIXELFORMAT_ARGB8888)
            ?: return false
        return try {
            if (SDL_RenderReadPixels(renderer, null, SDL_PIXELFORMAT_ARGB8888, image.pointed.pixels, image.pointed.pitch) != 0) false
            else SDL_SaveBMP_RW(image, SDL_RWFromFile("brick-screenshot.bmp", "wb"), 1) == 0
        } finally { SDL_FreeSurface(image) }
    }
    fun close() {
        controllers.values.forEach { SDL_GameControllerClose(it) }; controllers.clear()
        nativeHeap.free(event.ptr)
        SDL_DestroyRenderer(renderer); SDL_DestroyWindow(window); SDL_Quit()
    }
    fun poll(app: DeckApp) {
        while (SDL_PollEvent(event.ptr) != 0) {
            val e = event
            when (e.type) {
                SDL_QUIT -> app.running = false
                SDL_CONTROLLERDEVICEADDED -> openController(e.cdevice.which)
                SDL_CONTROLLERDEVICEREMOVED -> {
                    controllers.remove(e.cdevice.which)?.let { SDL_GameControllerClose(it) }
                    triggerDown.clear(); app.focusChanged()
                }
                SDL_CONTROLLERDEVICEREMAPPED -> { triggerDown.clear(); app.focusChanged() }
                SDL_WINDOWEVENT -> if (e.window.event.toInt() == SDL_WINDOWEVENT_FOCUS_LOST.toInt() ||
                    e.window.event.toInt() == SDL_WINDOWEVENT_FOCUS_GAINED.toInt()) {
                    triggerDown.clear(); app.focusChanged()
                }
                SDL_CONTROLLERBUTTONDOWN, SDL_CONTROLLERBUTTONUP ->
                    Pad.code(e.cbutton.button.toInt())?.let { app.button(it, e.type == SDL_CONTROLLERBUTTONDOWN) }
                SDL_CONTROLLERAXISMOTION -> {
                    val axis = e.caxis.axis.toInt()
                    if (axis == SDL_CONTROLLER_AXIS_RIGHTX.toInt() || axis == SDL_CONTROLLER_AXIS_RIGHTY.toInt()) {
                        app.rightStick(e.caxis.which, axis == SDL_CONTROLLER_AXIS_RIGHTX.toInt(), e.caxis.value / 32767f, ticks().toLong())
                    } else Pad.triggerCode(axis)?.let { code ->
                        // The Brick's digital L2/R2 arrive as SDL controller trigger axes. Preserve both held edges.
                        val key = code
                        val down = e.caxis.value > 16000
                        if (down != (key in triggerDown)) {
                            if (down) triggerDown.add(key) else triggerDown.remove(key)
                            app.button(code, down)
                        }
                    }
                }
                SDL_TEXTINPUT -> app.type(e.text.text.toKString())
                SDL_KEYDOWN, SDL_KEYUP -> {
                    val down = e.type == SDL_KEYDOWN
                    if (app.fieldFocused) {
                        if (down) when (e.key.keysym.sym.toUInt()) {
                            SDLK_BACKSPACE -> app.editText(67)
                            SDLK_RETURN, SDLK_KP_ENTER -> app.editText(Keys.ENTER)
                            SDLK_ESCAPE -> app.editText(Keys.ESCAPE)
                        }
                    } else if (e.key.repeat.toInt() == 0) Pad.keyboardCode(e.key.keysym.sym)?.let { app.button(it, down) }
                }
            }
        }
    }
    companion object { const val NAME = "Brick Hammer / NextUI"; const val WIDTH = 1024; const val HEIGHT = 768; const val DENSITY = 2f }
}

private object Pad {
    fun code(button: Int): Int? = when (button) {
        // NextUI/stock swaps the Xbox mapping: honor the letters printed on the Brick.
        SDL_CONTROLLER_BUTTON_A.toInt() -> Keys.BUTTON_B
        SDL_CONTROLLER_BUTTON_B.toInt() -> Keys.BUTTON_A
        SDL_CONTROLLER_BUTTON_X.toInt() -> Keys.BUTTON_Y
        SDL_CONTROLLER_BUTTON_Y.toInt() -> Keys.BUTTON_X
        SDL_CONTROLLER_BUTTON_LEFTSHOULDER.toInt() -> Keys.BUTTON_L1
        SDL_CONTROLLER_BUTTON_RIGHTSHOULDER.toInt() -> Keys.BUTTON_R1
        SDL_CONTROLLER_BUTTON_LEFTSTICK.toInt() -> Keys.BUTTON_THUMBL
        SDL_CONTROLLER_BUTTON_RIGHTSTICK.toInt() -> Keys.BUTTON_THUMBR
        SDL_CONTROLLER_BUTTON_START.toInt() -> Keys.BUTTON_START
        SDL_CONTROLLER_BUTTON_BACK.toInt() -> Keys.BUTTON_SELECT
        SDL_CONTROLLER_BUTTON_GUIDE.toInt(), SDL_CONTROLLER_BUTTON_MISC1.toInt() -> Keys.BUTTON_MODE
        SDL_CONTROLLER_BUTTON_DPAD_UP.toInt() -> Keys.DPAD_UP
        SDL_CONTROLLER_BUTTON_DPAD_DOWN.toInt() -> Keys.DPAD_DOWN
        SDL_CONTROLLER_BUTTON_DPAD_LEFT.toInt() -> Keys.DPAD_LEFT
        SDL_CONTROLLER_BUTTON_DPAD_RIGHT.toInt() -> Keys.DPAD_RIGHT
        else -> null
    }
    fun triggerCode(axis: Int): Int? = when (axis) {
        SDL_CONTROLLER_AXIS_TRIGGERLEFT.toInt() -> Keys.BUTTON_L2
        SDL_CONTROLLER_AXIS_TRIGGERRIGHT.toInt() -> Keys.BUTTON_R2
        else -> null
    }
    // SDL_Keycode is signed, while cinterop imports SDL2's nonnegative key constants as UInt.
    fun keyboardCode(key: Int): Int? = when (key.toUInt()) {
        SDLK_UP -> Keys.DPAD_UP; SDLK_DOWN -> Keys.DPAD_DOWN; SDLK_LEFT -> Keys.DPAD_LEFT; SDLK_RIGHT -> Keys.DPAD_RIGHT
        SDLK_z -> Keys.BUTTON_A; SDLK_x -> Keys.BUTTON_B; SDLK_a -> Keys.BUTTON_X; SDLK_s -> Keys.BUTTON_Y
        SDLK_q -> Keys.BUTTON_L1; SDLK_w -> Keys.BUTTON_R1; SDLK_1 -> Keys.BUTTON_L2; SDLK_2 -> Keys.BUTTON_R2
        SDLK_SPACE -> Keys.BUTTON_START; SDLK_TAB -> Keys.BUTTON_SELECT
        SDLK_RETURN, SDLK_KP_ENTER -> Keys.ENTER; SDLK_ESCAPE -> Keys.ESCAPE; SDLK_F1 -> Keys.MENU
        else -> null
    }
}
