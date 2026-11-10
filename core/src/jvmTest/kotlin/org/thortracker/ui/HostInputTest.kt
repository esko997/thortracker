package org.thortracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // No L2+R2+A chord: L2+A and R2+A belong to the layers below, so the press does nothing.
        val input = HostInput(Keymap.DEFAULT.with(Control.OTHER_SONG, null))
        input.down(Keys.BUTTON_L2, popup = true)
        input.down(Keys.BUTTON_R2, popup = true)
        assertEquals(none, input.down(Keys.BUTTON_A, popup = true))
        assertEquals(setOf(Keys.BUTTON_L2, Keys.BUTTON_R2), input.heldModifiers)
    }

    private val both = setOf(Keys.BUTTON_L2, Keys.BUTTON_R2)

    /** The defaults with the L2+R2+A chord stated, so the tests show what they rely on. */
    private fun layered() = Keymap.DEFAULT.with(Control.OTHER_SONG, Binding(Keys.BUTTON_A, both))

    @Test fun bothTriggersFormTheirOwnLayerWhicheverWentDownFirst() {
        for (order in listOf(listOf(Keys.BUTTON_L2, Keys.BUTTON_R2), listOf(Keys.BUTTON_R2, Keys.BUTTON_L2))) {
            val input = HostInput(layered())
            order.forEach { assertEquals(none, input.down(it)) }
            assertEquals(order.toString(), HostInput.Route.Run(Control.OTHER_SONG), input.down(Keys.BUTTON_A))
            assertEquals(none, input.up(Keys.BUTTON_A))
            order.forEach { assertEquals(none, input.up(it)) }
            assertEquals(HostInput.Route.Editor(Action.EDIT), input.down(Keys.BUTTON_A))
        }
    }

    @Test fun whileBothTriggersAreHeldNoSingleTriggerChordOrBareKeyActs() {
        val input = HostInput(Keymap.DEFAULT.with(Control.OTHER_SONG, null))
        input.down(Keys.BUTTON_L2)
        input.down(Keys.BUTTON_R2)
        // L2+A (Undo), R2+A (Song) and A (Edit) all stay quiet, through repeat and release.
        assertEquals(none, input.down(Keys.BUTTON_A))
        assertEquals(none, input.route(Keys.BUTTON_A, true, 1, false, false, ViewKind.PATTERN))
        assertEquals(none, input.up(Keys.BUTTON_A))
        // Only L2 has a Start chord; it is still not run.
        assertEquals(none, input.down(Keys.BUTTON_START))
        assertEquals(none, input.up(Keys.BUTTON_START))
        // L2+← collapses in Pattern and changes octave in sound views; both wait for one trigger to go.
        assertEquals(none, input.down(Keys.DPAD_LEFT))
        assertEquals(none, input.up(Keys.DPAD_LEFT))
        assertEquals(none, input.down(Keys.DPAD_LEFT, view = ViewKind.INSTRUMENT))
        assertEquals(none, input.up(Keys.DPAD_LEFT, view = ViewKind.INSTRUMENT))
        // Nor does A reach a focused toolbar as its plain key.
        assertEquals(none, input.down(Keys.BUTTON_A, toolbar = true))
    }

    @Test fun rollingReleasesKeepEachPressItsFirstMeaning() {
        val input = HostInput(layered())
        input.down(Keys.BUTTON_L2)
        input.down(Keys.BUTTON_R2)
        assertEquals(HostInput.Route.Run(Control.OTHER_SONG), input.down(Keys.BUTTON_A))
        input.up(Keys.BUTTON_L2)   // one trigger first: A's repeat and release must not turn into R2+A
        assertEquals(none, input.route(Keys.BUTTON_A, true, 1, false, false, ViewKind.PATTERN))
        assertEquals(none, input.up(Keys.BUTTON_A))
        // A new press with only R2 left is that layer's chord.
        assertEquals(HostInput.Route.Run(Control.SHOW_SONG), input.down(Keys.BUTTON_A))
        input.up(Keys.BUTTON_R2)
        assertEquals(none, input.up(Keys.BUTTON_A))
        assertEquals(emptySet<Int>(), input.heldModifiers)
        // A held Listen stays the editor's through a third-layer chord, so its repeat and release still reach the editor.
        assertEquals(HostInput.Route.Editor(Action.AUDITION), input.down(Keys.BUTTON_X))
        input.down(Keys.BUTTON_R2)
        input.down(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Run(Control.OTHER_SONG), input.down(Keys.BUTTON_A))
        input.up(Keys.BUTTON_A)
        assertEquals(HostInput.Route.Editor(Action.AUDITION), input.route(Keys.BUTTON_X, true, 1, false, false, ViewKind.PATTERN))
        input.up(Keys.BUTTON_R2)
        input.up(Keys.BUTTON_L2)
        assertEquals(HostInput.Route.Editor(Action.AUDITION), input.up(Keys.BUTTON_X))
    }

    @Test fun triggersAloneNeverAct() {
        val input = HostInput(layered())
        for (code in listOf(Keys.BUTTON_L2, Keys.BUTTON_R2)) assertEquals(none, input.down(code))
        assertEquals(none, input.route(Keys.BUTTON_L2, true, 3, false, false, ViewKind.PATTERN))  // a held trigger's repeat
        for (code in listOf(Keys.BUTTON_L2, Keys.BUTTON_R2)) assertEquals(none, input.up(code))
        assertEquals(none, input.down(Keys.BUTTON_R2, popup = true))
        assertEquals(none, input.up(Keys.BUTTON_R2, popup = true))
        assertEquals(emptySet<Int>(), input.heldModifiers)
        assertEquals(HostInput.Route.Editor(Action.EDIT), input.down(Keys.BUTTON_A))
    }

    @Test fun cancelledPressesAreForgottenWithoutActing() {
        val input = HostInput(layered())
        input.down(Keys.BUTTON_L2)
        input.down(Keys.BUTTON_R2)
        assertFalse(input.cancel(Keys.BUTTON_L2))   // e.g. the notification shade took focus
        assertEquals(setOf(Keys.BUTTON_R2), input.heldModifiers)
        assertEquals(HostInput.Route.Run(Control.SHOW_SONG), input.down(Keys.BUTTON_A))
        assertFalse(input.cancel(Keys.BUTTON_A))    // a host control: nothing for the editor to drop
        assertFalse(input.cancel(Keys.BUTTON_R2))
        assertEquals(emptySet<Int>(), input.heldModifiers)
        assertEquals(HostInput.Route.Editor(Action.EDIT), input.down(Keys.BUTTON_A))
        assertTrue(input.cancel(Keys.BUTTON_A))     // the editor's hold must be cancelled, not released as a tap
    }

    @Test fun aCaptureTakesTheKeysButTriggersStayTrueToTheHardware() {
        val input = HostInput(layered())
        assertTrue(input.trackModifier(Keys.BUTTON_R2, true))
        assertTrue(input.trackModifier(Keys.BUTTON_L2, true))
        assertFalse(input.trackModifier(Keys.BUTTON_A, true))
        assertEquals(both, input.heldModifiers)
        input.trackModifier(Keys.BUTTON_R2, false)
        assertEquals(setOf(Keys.BUTTON_L2), input.heldModifiers)
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
            .with(Control.UNDO, Binding(Keys.BUTTON_B, setOf(Keys.BUTTON_SELECT)))
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

    /** Samples from one right-stick axis; returns the jumps reported. */
    private fun HostInput.stick(vararg samples: Pair<Float, Int>, device: Int = 1, pane: Int = 0, view: ViewKind = ViewKind.PATTERN,
                                pattern: Boolean = true, popup: Boolean = false, toolbar: Boolean = false, horizontal: Boolean = false): List<Int> =
        samples.map { (value, t) -> rightStick(device, horizontal, value, t.toLong(), pane, view, pattern, popup, toolbar) }.filter { it != 0 }

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

    @Test fun horizontalDoubleFlicksRespectPatternFocusModalsAndControllerIdentity() {
        val input = HostInput()
        assertEquals(listOf(-1), input.stick(-1f to 0, 0f to 80, -1f to 160, 0f to 220, horizontal = true))
        assertEquals(listOf(1), input.stick(1f to 1000, 0f to 1080, 1f to 1160, 0f to 1220, horizontal = true))
        for (kind in ViewKind.entries.filter { it != ViewKind.PATTERN }) {
            assertEquals(emptyList<Int>(), input.stick(-1f to 2000, 0f to 2080, -1f to 2160, 0f to 2220, view = kind, horizontal = true))
        }
        assertEquals(emptyList<Int>(), input.stick(-1f to 3000, 0f to 3080, -1f to 3160, 0f to 3220, pattern = false, horizontal = true))
        assertEquals(emptyList<Int>(), input.stick(-1f to 4000, 0f to 4080, -1f to 4160, 0f to 4220, popup = true, horizontal = true))
        assertEquals(emptyList<Int>(), input.stick(-1f to 5000, 0f to 5080, -1f to 5160, 0f to 5220, toolbar = true, horizontal = true))
        input.stick(-1f to 6000, 0f to 6080, horizontal = true)
        assertEquals(emptyList<Int>(), input.stick(-1f to 6160, horizontal = true, device = 2))
        assertEquals(emptyList<Int>(), input.stick(-1f to 6160, horizontal = true, pane = 1))
        input.stick(0f to 6200, -1f to 7000, 0f to 7080, horizontal = true, pane = 1)
        input.down(Keys.BUTTON_START)
        input.up(Keys.BUTTON_START)
        assertEquals(emptyList<Int>(), input.stick(-1f to 7160, horizontal = true, pane = 1))
    }

    @Test fun rightStickAxesNeverPairWithEachOtherAndDiagonalsCanCompleteBoth() {
        val input = HostInput()
        input.stick(-1f to 0, 0f to 80, horizontal = true)
        assertEquals(emptyList<Int>(), input.stick(-1f to 100, 0f to 140))
        assertEquals(listOf(-1), input.stick(-1f to 160, 0f to 220, horizontal = true))
        assertEquals(listOf(-1), input.stick(-1f to 180, 0f to 240))
        // Like Android's batched diagonal samples: feed both axes at each timestamp.
        for ((value, time) in listOf(1f to 1000, 0f to 1080, 1f to 1160)) {
            val expected = if (time == 1160) listOf(1) else emptyList()
            assertEquals(expected, input.stick(value to time, horizontal = true))
            assertEquals(expected, input.stick(value to time))
        }
        input.resetStick()
        // A held diagonal cannot become a fresh first flick on either axis across a reset.
        for (horizontal in listOf(false, true))
            assertEquals(emptyList<Int>(), input.stick(1f to 1200, 0f to 1280, 1f to 1360, horizontal = horizontal))
    }

    /** A key that is not a controller button (Android's KEYCODE_VOLUME_UP). */
    private val VOLUME_UP = 24
}
