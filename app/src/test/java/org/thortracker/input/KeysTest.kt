package org.thortracker.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The core's button codes are Android's, so the app passes KeyEvent codes to the keymap unchanged. */
class KeysTest {
    @Test
    fun keysAreAndroidKeyCodes() {
        val pairs = listOf(
            Keys.UNKNOWN to KeyEvent.KEYCODE_UNKNOWN, Keys.BACK to KeyEvent.KEYCODE_BACK,
            Keys.DPAD_UP to KeyEvent.KEYCODE_DPAD_UP, Keys.DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            Keys.DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT, Keys.DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            Keys.DPAD_CENTER to KeyEvent.KEYCODE_DPAD_CENTER, Keys.A to KeyEvent.KEYCODE_A, Keys.Z to KeyEvent.KEYCODE_Z,
            Keys.SPACE to KeyEvent.KEYCODE_SPACE, Keys.ENTER to KeyEvent.KEYCODE_ENTER, Keys.MENU to KeyEvent.KEYCODE_MENU,
            Keys.BUTTON_A to KeyEvent.KEYCODE_BUTTON_A, Keys.BUTTON_B to KeyEvent.KEYCODE_BUTTON_B,
            Keys.BUTTON_C to KeyEvent.KEYCODE_BUTTON_C, Keys.BUTTON_X to KeyEvent.KEYCODE_BUTTON_X,
            Keys.BUTTON_Y to KeyEvent.KEYCODE_BUTTON_Y, Keys.BUTTON_Z to KeyEvent.KEYCODE_BUTTON_Z,
            Keys.BUTTON_L1 to KeyEvent.KEYCODE_BUTTON_L1, Keys.BUTTON_R1 to KeyEvent.KEYCODE_BUTTON_R1,
            Keys.BUTTON_L2 to KeyEvent.KEYCODE_BUTTON_L2, Keys.BUTTON_R2 to KeyEvent.KEYCODE_BUTTON_R2,
            Keys.BUTTON_THUMBL to KeyEvent.KEYCODE_BUTTON_THUMBL, Keys.BUTTON_THUMBR to KeyEvent.KEYCODE_BUTTON_THUMBR,
            Keys.BUTTON_START to KeyEvent.KEYCODE_BUTTON_START, Keys.BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
            Keys.BUTTON_MODE to KeyEvent.KEYCODE_BUTTON_MODE, Keys.ESCAPE to KeyEvent.KEYCODE_ESCAPE,
            Keys.BUTTON_1 to KeyEvent.KEYCODE_BUTTON_1, Keys.BUTTON_16 to KeyEvent.KEYCODE_BUTTON_16,
        )
        for ((core, android) in pairs) assertEquals(android, core)
    }
}
