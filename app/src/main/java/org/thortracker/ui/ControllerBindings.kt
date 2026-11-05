package org.thortracker.ui

import android.view.KeyEvent
import org.thortracker.editor.Action

/**
 * Physical key → semantic [Action] map. [shiftKey] is a modifier: while it is held, [shifted] is
 * consulted before [base]. Keys absent from both maps are left to the host (START, R3, MENU, ...).
 */
class ControllerBindings(
    val base: Map<Int, Action>,
    val shifted: Map<Int, Action>,
    val shiftKey: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
) {
    companion object {
        fun default(): ControllerBindings = ControllerBindings(
            base = mapOf(
                KeyEvent.KEYCODE_DPAD_UP to Action.UP,
                KeyEvent.KEYCODE_DPAD_DOWN to Action.DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT to Action.LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT to Action.RIGHT,
                KeyEvent.KEYCODE_BUTTON_A to Action.EDIT,
                KeyEvent.KEYCODE_BUTTON_B to Action.CLEAR,
                KeyEvent.KEYCODE_BUTTON_X to Action.AUDITION,
                KeyEvent.KEYCODE_BUTTON_Y to Action.MARK,
                KeyEvent.KEYCODE_BUTTON_L1 to Action.PREV_CHANNEL,
                KeyEvent.KEYCODE_BUTTON_R1 to Action.NEXT_CHANNEL,
                KeyEvent.KEYCODE_BUTTON_L2 to Action.UNDO,
                KeyEvent.KEYCODE_BUTTON_R2 to Action.REDO,
            ),
            shifted = mapOf(
                KeyEvent.KEYCODE_BUTTON_A to Action.PASTE,
                KeyEvent.KEYCODE_BUTTON_X to Action.COPY,
                KeyEvent.KEYCODE_BUTTON_B to Action.CUT,
                KeyEvent.KEYCODE_BUTTON_Y to Action.DUPLICATE,
                KeyEvent.KEYCODE_BUTTON_L1 to Action.OCTAVE_DOWN,
                KeyEvent.KEYCODE_BUTTON_R1 to Action.OCTAVE_UP,
                KeyEvent.KEYCODE_DPAD_UP to Action.INSERT_ROW,
                KeyEvent.KEYCODE_DPAD_DOWN to Action.DELETE_ROW,
            ),
        )
    }
}
