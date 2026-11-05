package org.thortracker.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HostInputTest {
    @Test fun undoAndRedoChordsCannotActivateAnOpenPopup() {
        for ((trigger, command) in listOf(
            KeyEvent.KEYCODE_BUTTON_L2 to HostInput.Command.UNDO,
            KeyEvent.KEYCODE_BUTTON_R2 to HostInput.Command.REDO,
        )) {
            val input = HostInput()
            input.route(trigger, true, 0, true, false)
            assertEquals(HostInput.Route.Run(command), input.route(KeyEvent.KEYCODE_BUTTON_A, true, 0, true, false))
            input.route(trigger, false, 0, true, false)
            assertEquals(HostInput.Route.Deliver(HostInput.Target.NONE),
                input.route(KeyEvent.KEYCODE_BUTTON_A, true, 1, true, false))
            assertEquals(HostInput.Route.Deliver(HostInput.Target.NONE),
                input.route(KeyEvent.KEYCODE_BUTTON_A, false, 0, true, false))
        }
    }

    @Test fun screenSwapRemainsGlobalWhileAPopupIsOpen() {
        val input = HostInput()
        input.route(KeyEvent.KEYCODE_BUTTON_L2, true, 0, true, false)
        assertEquals(HostInput.Route.Run(HostInput.Command.SWAP),
            input.route(KeyEvent.KEYCODE_BUTTON_X, true, 0, true, false))
        assertEquals(HostInput.Route.Deliver(HostInput.Target.NONE),
            input.route(KeyEvent.KEYCODE_BUTTON_X, false, 0, false, false))
    }

    @Test fun ambiguousTriggerChordDoesNotChooseThePopupItem() {
        val input = HostInput()
        input.route(KeyEvent.KEYCODE_BUTTON_L2, true, 0, true, false)
        input.route(KeyEvent.KEYCODE_BUTTON_R2, true, 0, true, false)
        assertEquals(HostInput.Route.Deliver(HostInput.Target.NONE),
            input.route(KeyEvent.KEYCODE_BUTTON_A, true, 0, true, false))
    }
}
