package org.thortracker.input

/**
 * Button codes used by [Keymap] and keymap files. The values are Android's `KeyEvent.KEYCODE_*` numbers, so the
 * Android app passes its key codes through unchanged; other frontends map their buttons to these codes.
 */
object Keys {
    const val UNKNOWN = 0
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val A = 29
    const val Z = 54
    const val SPACE = 62
    const val ENTER = 66
    const val MENU = 82
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_C = 98
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_Z = 101
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_THUMBL = 106
    const val BUTTON_THUMBR = 107
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110
    const val ESCAPE = 111
    const val NUMPAD_ENTER = 160
    const val BUTTON_1 = 188
    const val BUTTON_16 = 203

    /** Android's KeyEvent.isGamepadButton: A..Mode and the generic buttons 1..16. */
    fun isGamepadButton(code: Int): Boolean = code in BUTTON_A..BUTTON_MODE || code in BUTTON_1..BUTTON_16
}
