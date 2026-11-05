package org.thortracker.ui

import android.view.KeyEvent
import org.thortracker.editor.Action

/**
 * Physical key → semantic [Action] map. [shiftKey] is a modifier: while it is held, [shifted] is
 * consulted before [base]. Keys absent from both maps are left to the host: Start, L3, R3, Menu and the
 * L2/R2 triggers, which do nothing alone and only act in the host's chords (L2+A undo, R2+A redo,
 * L2+X swap displays, Select+Start toolbar, L3+Start play from the cursor).
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
                KeyEvent.KEYCODE_DPAD_RIGHT to Action.SCALE,
            ),
        )
    }
}
