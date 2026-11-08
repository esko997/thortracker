package org.thortracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.ViewKind
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.input.Keys

class HostInputTest {
    private val none = HostInput.Route.Deliver(HostInput.Target.NONE)

    private fun HostInput.down(key: Int, popup: Boolean = false, toolbar: Boolean = false, view: ViewKind = ViewKind.PATTERN) =
        route(key, true, 0, popup, toolbar, view)
    private fun HostInput.up(key: Int, popup: Boolean = false, toolbar: Boolean = false, view: ViewKind = ViewKind.PATTERN) =
        route(key, false, 0, popup, toolbar, view)

    @Test fun l2RightOpensThePatternPanelInPatternButRaisesTheListeningOctaveInSoundViews() {
        val input = HostInput()
        input.down(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.SCALE), input.down(Keys.DPAD_RIGHT))
        input.up(Keys.DPAD_RIGHT)
        assertEquals(HostInput.Route.Editor(Action.OCTAVE_UP), input.down(Keys.DPAD_RIGHT, view = ViewKind.INSTRUMENT))
        input.up(Keys.DPAD_RIGHT, view = ViewKind.INSTRUMENT)
        assertEquals(HostInput.Route.Editor(Action.OCTAVE_DOWN), input.down(Keys.DPAD_LEFT, view = ViewKind.WAVE))
        input.up(Keys.DPAD_LEFT, view = ViewKind.WAVE)
        // Where the chord means nothing, the key keeps its own binding, as for any unbound chord.
        assertEquals(HostInput.Route.Editor(Action.RIGHT), input.down(Keys.DPAD_RIGHT, view = ViewKind.SONG))
    }

    @Test fun undoAndRedoChordsCannotActivateAnOpenPopup() {
        for ((button, control) in listOf(Keys.BUTTON_A to Control.UNDO, Keys.BUTTON_B to Control.REDO)) {
            val input = HostInput()
            input.down(Keys.BUTTON_L2, popup = true)
            assertEquals(HostInput.Route.Run(control), input.down(button, popup = true))
            input.up(Keys.BUTTON_L2, popup = true)
            assertEquals(none, input.route(button, true, 1, true, false, ViewKind.PATTERN))
            assertEquals(none, input.up(button, popup = true))
        }
    }

    @Test fun r2ChordsShowViewsAndNeverPressAPopupItem() {
        val input = HostInput()
        input.down(Keys.BUTTON_R2)
        for ((button, control) in listOf(
            Keys.BUTTON_A to Control.SHOW_SONG, Keys.BUTTON_B to Control.SHOW_INSTRUMENT,
            Keys.BUTTON_X to Control.SHOW_SKETCH, Keys.BUTTON_Y to Control.SHOW_KEYBOARD,
        )) {
            assertEquals(HostInput.Route.Run(control), input.down(button, view = ViewKind.WAVE))
            assertEquals(none, input.up(button, view = ViewKind.WAVE))
        }
        // Over an open popup a view chord does nothing: R2+A must not choose the highlighted item.
        assertEquals(none, input.down(Keys.BUTTON_A, popup = true))
        assertEquals(none, input.up(Keys.BUTTON_A, popup = true))
    }

    @Test fun ambiguousChordDoesNothingEvenOverAPopup() {
        val input = HostInput()
        input.down(Keys.BUTTON_L2, popup = true)
        input.down(Keys.BUTTON_R2, popup = true)
        assertEquals(none, input.down(Keys.BUTTON_A, popup = true))
        assertEquals(setOf(Keys.BUTTON_L2, Keys.BUTTON_R2), input.heldModifiers)
    }

    @Test fun chordWinsOverTheBareBindingAndKeepsItsMeaningUntilRelease() {
        val input = HostInput()
        assertEquals(HostInput.Route.Run(Control.PLAY_PATTERN), input.down(Keys.BUTTON_START))
        input.up(Keys.BUTTON_START)
        input.down(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Run(Control.PLAY_CURSOR), input.down(Keys.BUTTON_START))
        input.up(Keys.BUTTON_L2)
        assertEquals(none, input.route(Keys.BUTTON_START, true, 1, false, false, ViewKind.PATTERN))
        assertEquals(none, input.up(Keys.BUTTON_START))
        // A modifier without a chord for the key falls back to the key's own binding.
        input.down(Keys.BUTTON_THUMBL)
        assertEquals(HostInput.Route.Run(Control.PLAY_PATTERN), input.down(Keys.BUTTON_START))
        input.up(Keys.BUTTON_START)
        input.up(Keys.BUTTON_THUMBL)
        input.down(Keys.DPAD_LEFT)
        assertEquals(HostInput.Route.Editor(Action.LEFT), input.route(Keys.DPAD_LEFT, true, 1, false, false, ViewKind.PATTERN))
    }

    @Test fun editorChordHoldsItsActionThroughRepeatAndRelease() {
        val input = HostInput()
        assertEquals(HostInput.Route.Editor(Action.RIGHT), input.down(Keys.DPAD_RIGHT))
        input.up(Keys.DPAD_RIGHT)
        input.down(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.SCALE), input.down(Keys.DPAD_RIGHT))
        input.up(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.SCALE), input.up(Keys.DPAD_RIGHT))
        // The toolbar never receives a chord as if it were its plain button.
        input.down(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Deliver(HostInput.Target.TOOLBAR, Keys.UNKNOWN),
            input.down(Keys.DPAD_RIGHT, toolbar = true))
    }

    @Test fun reboundKeysDriveTheEditorAndMenusFollowThem() {
        // Y confirms and adjusts; A only selects blocks; undo moves to Select+B and L2+A becomes free.
        val keymap = Keymap.DEFAULT
            .with(Control.EDIT, Binding(Keys.BUTTON_Y))
            .with(Control.MARK, Binding(Keys.BUTTON_A))
            .with(Control.CUT, null)
            .with(Control.UNDO, Binding(Keys.BUTTON_B, Keys.BUTTON_SELECT))
        val input = HostInput(keymap)
        assertEquals(HostInput.Route.Editor(Action.EDIT), input.down(Keys.BUTTON_Y))
        input.up(Keys.BUTTON_Y)
        assertEquals(HostInput.Route.Deliver(HostInput.Target.POPUP, Keys.BUTTON_A), input.down(Keys.BUTTON_Y, popup = true))
        assertEquals(HostInput.Route.Deliver(HostInput.Target.POPUP, Keys.UNKNOWN), input.down(Keys.BUTTON_A, popup = true))
        input.down(Keys.BUTTON_SELECT)
        assertEquals(HostInput.Route.Run(Control.UNDO), input.down(Keys.BUTTON_B))
        input.up(Keys.BUTTON_SELECT)
        input.down(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.MARK), input.down(Keys.BUTTON_A))
    }

    @Test fun menuAndBackAreFixedAndSystemKeysPassThrough() {
        val input = HostInput()
        assertEquals(HostInput.Route.Menu, input.down(Keys.MENU))
        assertEquals(HostInput.Route.Deliver(HostInput.Target.TOOLBAR, Keys.BACK), input.down(Keys.BACK, toolbar = true))
        assertEquals(HostInput.Route.Pass, input.down(VOLUME_UP))
        assertEquals(none, input.down(Keys.BUTTON_Z)) // unbound controller key: consumed, no system fallback
    }

    @Test fun heldModifiersFollowPressesReleasesAndReset() {
        val input = HostInput()
        input.down(Keys.BUTTON_L2)
        input.down(Keys.BUTTON_SELECT, popup = true)
        assertEquals(setOf(Keys.BUTTON_L2, Keys.BUTTON_SELECT), input.heldModifiers)
        // A release under a popup still clears the modifier, so its hints cannot stick.
        input.up(Keys.BUTTON_L2, popup = true)
        assertEquals(setOf(Keys.BUTTON_SELECT), input.heldModifiers)
        input.reset()
        assertEquals(emptySet<Int>(), input.heldModifiers)
    }

    /** Right-stick samples from [device] at the given (y, millis); returns the jumps reported. */
    private fun HostInput.stick(vararg samples: Pair<Float, Int>, device: Int = 1, pane: Int = 0, view: ViewKind = ViewKind.PATTERN,
                                pattern: Boolean = true, popup: Boolean = false, toolbar: Boolean = false): List<Int> =
        samples.map { (y, t) -> rightStick(device, y, t.toLong(), pane, view, pattern, popup, toolbar) }.filter { it != 0 }

    @Test fun rightStickDoubleFlicksJumpOnlyInTheFocusedPatternGrid() {
        val input = HostInput()
        assertEquals(listOf(-1), input.stick(-1f to 0, 0f to 80, -1f to 160, 0f to 220))
        assertEquals(listOf(1), input.stick(1f to 1000, 0f to 1080, 1f to 1160, 0f to 1220))
        // Song view, an open pattern panel (not patternFocused), a popup or the toolbar: nothing.
        assertEquals(emptyList<Int>(), input.stick(-1f to 2000, 0f to 2080, -1f to 2160, 0f to 2220, view = ViewKind.SONG))
        assertEquals(emptyList<Int>(), input.stick(-1f to 3000, 0f to 3080, -1f to 3160, 0f to 3220, pattern = false))
        assertEquals(emptyList<Int>(), input.stick(-1f to 4000, 0f to 4080, -1f to 4160, 0f to 4220, popup = true))
        assertEquals(emptyList<Int>(), input.stick(-1f to 5000, 0f to 5080, -1f to 5160, 0f to 5220, toolbar = true))
    }

    @Test fun aFlickBeforeAPopupFocusChangeOrButtonCannotFinishAJumpAfterIt() {
        val input = HostInput()
        input.stick(-1f to 0, 0f to 80)
        input.stick(0f to 100, popup = true)
        assertEquals("a popup came and went", emptyList<Int>(), input.stick(-1f to 160, 0f to 220))

        input.stick(-1f to 1000, 0f to 1080)
        assertEquals("focus moved to the other display", emptyList<Int>(), input.stick(-1f to 1160, pane = 1))

        input.stick(1f to 2000, 0f to 2080, pane = 1)
        input.down(Keys.BUTTON_START)
        input.up(Keys.BUTTON_START)
        assertEquals("a button press in between", emptyList<Int>(), input.stick(1f to 2160, pane = 1))

        // A D-pad move between the flicks keeps the gesture.
        input.stick(0f to 3000, 1f to 3010, 0f to 3080, pane = 1)
        input.down(Keys.DPAD_LEFT)
        input.up(Keys.DPAD_LEFT)
        assertEquals(listOf(1), input.stick(1f to 3160, pane = 1))

        // Held up through a popup: closing it does not turn the held push into a flick.
        input.stick(0f to 4000, -1f to 4010, pane = 1, popup = true)
        input.resetStick()
        assertEquals(emptyList<Int>(), input.stick(0f to 4100, -1f to 4180, pane = 1))
    }

    @Test fun flicksFromTwoControllersNeverPair() {
        val input = HostInput()
        input.stick(-1f to 0, 0f to 80, device = 1)
        assertEquals(emptyList<Int>(), input.stick(-1f to 160, 0f to 220, device = 2))
        input.forgetStick(1)
        assertEquals(emptyList<Int>(), input.stick(-1f to 240, device = 1))
    }

    /** A key that is not a controller button (Android's KEYCODE_VOLUME_UP). */
    private val VOLUME_UP = 24
}
