package org.thortracker.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap

class HostInputTest {
    private val none = HostInput.Route.Deliver(HostInput.Target.NONE)

    private fun HostInput.down(key: Int, popup: Boolean = false, toolbar: Boolean = false) = route(key, true, 0, popup, toolbar)
    private fun HostInput.up(key: Int, popup: Boolean = false, toolbar: Boolean = false) = route(key, false, 0, popup, toolbar)

    @Test fun undoAndRedoChordsCannotActivateAnOpenPopup() {
        for ((trigger, control) in listOf(KeyEvent.KEYCODE_BUTTON_L2 to Control.UNDO, KeyEvent.KEYCODE_BUTTON_R2 to Control.REDO)) {
            val input = HostInput()
            input.down(trigger, popup = true)
            assertEquals(HostInput.Route.Run(control), input.down(KeyEvent.KEYCODE_BUTTON_A, popup = true))
            input.up(trigger, popup = true)
            assertEquals(none, input.route(KeyEvent.KEYCODE_BUTTON_A, true, 1, true, false))
            assertEquals(none, input.up(KeyEvent.KEYCODE_BUTTON_A, popup = true))
        }
    }

    @Test fun ambiguousChordDoesNothingEvenOverAPopup() {
        val input = HostInput()
        input.down(KeyEvent.KEYCODE_BUTTON_L2, popup = true)
        input.down(KeyEvent.KEYCODE_BUTTON_R2, popup = true)
        assertEquals(none, input.down(KeyEvent.KEYCODE_BUTTON_A, popup = true))
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2), input.heldModifiers)
    }

    @Test fun chordWinsOverTheBareBindingAndKeepsItsMeaningUntilRelease() {
        val input = HostInput()
        assertEquals(HostInput.Route.Run(Control.PLAY_PATTERN), input.down(KeyEvent.KEYCODE_BUTTON_START))
        input.up(KeyEvent.KEYCODE_BUTTON_START)
        input.down(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(HostInput.Route.Run(Control.PLAY_CURSOR), input.down(KeyEvent.KEYCODE_BUTTON_START))
        input.up(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(none, input.route(KeyEvent.KEYCODE_BUTTON_START, true, 1, false, false))
        assertEquals(none, input.up(KeyEvent.KEYCODE_BUTTON_START))
        // A modifier without a chord for the key falls back to the key's own binding.
        input.down(KeyEvent.KEYCODE_BUTTON_THUMBL)
        assertEquals(HostInput.Route.Run(Control.PLAY_PATTERN), input.down(KeyEvent.KEYCODE_BUTTON_START))
        input.up(KeyEvent.KEYCODE_BUTTON_START)
        input.up(KeyEvent.KEYCODE_BUTTON_THUMBL)
        input.down(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(HostInput.Route.Editor(Action.LEFT), input.route(KeyEvent.KEYCODE_DPAD_LEFT, true, 1, false, false))
    }

    @Test fun editorChordHoldsItsActionThroughRepeatAndRelease() {
        val input = HostInput()
        assertEquals(HostInput.Route.Editor(Action.RIGHT), input.down(KeyEvent.KEYCODE_DPAD_RIGHT))
        input.up(KeyEvent.KEYCODE_DPAD_RIGHT)
        input.down(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.SCALE), input.down(KeyEvent.KEYCODE_DPAD_RIGHT))
        input.up(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.SCALE), input.up(KeyEvent.KEYCODE_DPAD_RIGHT))
        // The toolbar never receives a chord as if it were its plain button.
        input.down(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(HostInput.Route.Deliver(HostInput.Target.TOOLBAR, KeyEvent.KEYCODE_UNKNOWN),
            input.down(KeyEvent.KEYCODE_DPAD_RIGHT, toolbar = true))
    }

    @Test fun reboundKeysDriveTheEditorAndMenusFollowThem() {
        // Y confirms and adjusts; A only selects blocks; undo moves to Select+B and L2+A becomes free.
        val keymap = Keymap.DEFAULT
            .with(Control.EDIT, Binding(KeyEvent.KEYCODE_BUTTON_Y))
            .with(Control.MARK, Binding(KeyEvent.KEYCODE_BUTTON_A))
            .with(Control.CUT, null)
            .with(Control.UNDO, Binding(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_SELECT))
        val input = HostInput(keymap)
        assertEquals(HostInput.Route.Editor(Action.EDIT), input.down(KeyEvent.KEYCODE_BUTTON_Y))
        input.up(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(HostInput.Route.Deliver(HostInput.Target.POPUP, KeyEvent.KEYCODE_BUTTON_A), input.down(KeyEvent.KEYCODE_BUTTON_Y, popup = true))
        assertEquals(HostInput.Route.Deliver(HostInput.Target.POPUP, KeyEvent.KEYCODE_UNKNOWN), input.down(KeyEvent.KEYCODE_BUTTON_A, popup = true))
        input.down(KeyEvent.KEYCODE_BUTTON_SELECT)
        assertEquals(HostInput.Route.Run(Control.UNDO), input.down(KeyEvent.KEYCODE_BUTTON_B))
        input.up(KeyEvent.KEYCODE_BUTTON_SELECT)
        input.down(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.MARK), input.down(KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun menuAndBackAreFixedAndSystemKeysPassThrough() {
        val input = HostInput()
        assertEquals(HostInput.Route.Menu, input.down(KeyEvent.KEYCODE_MENU))
        assertEquals(HostInput.Route.Deliver(HostInput.Target.TOOLBAR, KeyEvent.KEYCODE_BACK), input.down(KeyEvent.KEYCODE_BACK, toolbar = true))
        assertEquals(HostInput.Route.Pass, input.down(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(none, input.down(KeyEvent.KEYCODE_BUTTON_Z)) // unbound controller key: consumed, no system fallback
    }

    @Test fun heldModifiersFollowPressesReleasesAndReset() {
        val input = HostInput()
        input.down(KeyEvent.KEYCODE_BUTTON_L2)
        input.down(KeyEvent.KEYCODE_BUTTON_SELECT, popup = true)
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_SELECT), input.heldModifiers)
        // A release under a popup still clears the modifier, so its hints cannot stick.
        input.up(KeyEvent.KEYCODE_BUTTON_L2, popup = true)
        assertEquals(setOf(KeyEvent.KEYCODE_BUTTON_SELECT), input.heldModifiers)
        input.reset()
        assertEquals(emptySet<Int>(), input.heldModifiers)
    }
}
