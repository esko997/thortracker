package org.thortracker.input

import android.view.InputDevice
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Songs
import org.thortracker.ui.HostInput

/** Real router/editor transitions; no Android event mocks and no substitute editing implementation. */
class TouchInputTest {
    private class Bench {
        val session = EditorSession(Songs.demo()).apply { step = 0 }
        val view = ViewController(session, ViewState(kind = ViewKind.PATTERN))
        val router = HostInput()
        val owners = TouchKeyOwners()
        val controls = ArrayList<Control>()
        var auditions = 0
        var releases = 0
        var modal = false
        var capture: BindingCapture? = null
        var captured: Binding? = null
        init {
            session.onAudition = { _, _, _, _ -> auditions++ }
            session.onReleaseAudition = { releases++ }
        }
        fun key(source: Int, code: Int, down: Boolean, repeat: Int = 0) {
            if (owners.change(source, code, down, repeat) == TouchKeyOwners.Edge.NONE) return
            capture?.let {
                router.trackModifier(code, down)
                if (repeat == 0) it.key(code, down)?.let { binding ->
                    captured = binding
                    capture = null
                    cancel()
                }
                return
            }
            when (val route = router.route(code, down, repeat, modal, false, view.state.kind)) {
                is HostInput.Route.Editor -> if (down) view.press(route.action) else view.release(route.action)
                is HostInput.Route.Run -> { controls += route.control; route.control.action?.let { view.press(it); view.release(it) } }
                else -> Unit
            }
        }
        val touch = TouchPointers { code, down -> key(-1001, code, down) }
        fun down(id: Int, code: Int) = touch.down(id, code, code in Keymap.MODIFIERS)
        fun up(id: Int) = touch.up(id) { it in Keymap.MODIFIERS }
        fun tap(id: Int, code: Int) { down(id, code); up(id) }
        fun cancel() { touch.cancel(); owners.cancel(); router.reset(); view.cancelHolds() }
        /** As MainActivity.forgetVirtualInput when a controller attaches: only the dock's presses go. */
        fun hideDock() { touch.cancel(); owners.forget(-1001, router) { view.cancelHold(it) } }
        fun startCapture() { cancel(); modal = true; capture = BindingCapture() }
        fun cancelCapture() { capture = null; cancel() }
        fun closeModal() { cancelCapture(); modal = false }
        fun note() = session.song.patterns[session.song.orders[0]].tracks[0][0].note
    }

    @Test fun twoPointersAdjustOneNoteAndOneUndoRestoresWholeGesture() {
        val b = Bench(); val before = b.session.song; val note = b.note()
        b.down(17, Keys.BUTTON_A)
        b.tap(42, Keys.DPAD_RIGHT); b.tap(91, Keys.DPAD_RIGHT)
        b.up(17)
        assertEquals(note + 2, b.note())
        b.tap(2, Keys.BUTTON_L2)
        assertTrue(b.touch.latched(Keys.BUTTON_L2))
        b.tap(7, Keys.BUTTON_A)
        assertEquals(before, b.session.song)
        b.tap(2, Keys.BUTTON_L2)
        assertFalse(b.touch.latched(Keys.BUTTON_L2))
    }

    @Test fun pointerIndexReorderingAndTwoOwnersOfSameButtonDoNotReleaseEarly() {
        val events = ArrayList<Pair<Int, Boolean>>()
        val pointers = TouchPointers { code, down -> events += code to down }
        pointers.down(80, Keys.BUTTON_X, false); pointers.down(3, Keys.BUTTON_X, false)
        pointers.down(99, Keys.DPAD_UP, false)
        pointers.up(3) { false }; pointers.up(99) { false }; pointers.up(80) { false }
        assertEquals(listOf(Keys.BUTTON_X to true, Keys.DPAD_UP to true, Keys.DPAD_UP to false, Keys.BUTTON_X to false), events)
    }

    @Test fun bothTriggerLatchesResolveThirdLayerWithoutUndoOrEdit() {
        for (order in listOf(listOf(Keys.BUTTON_L2, Keys.BUTTON_R2), listOf(Keys.BUTTON_R2, Keys.BUTTON_L2))) {
            val b = Bench(); val before = b.session.song
            b.tap(8, order[0]); b.tap(16, order[1]); b.down(77, Keys.BUTTON_A)
            b.tap(8, order[0]); b.up(77)
            assertEquals(listOf(Control.OTHER_SONG), b.controls)
            assertEquals(before, b.session.song)
            b.cancel()
            assertTrue(b.router.heldModifiers.isEmpty())
            assertFalse(b.touch.latched(Keys.BUTTON_R2))
        }
    }

    @Test fun physicalReleaseCannotEndVirtualEditOrAudition() {
        val b = Bench(); val before = b.note()
        b.key(4, Keys.BUTTON_A, true); b.down(45, Keys.BUTTON_A)
        b.key(4, Keys.BUTTON_A, false)
        b.tap(7, Keys.DPAD_RIGHT); b.up(45)
        assertEquals(before + 1, b.note())
        b.key(4, Keys.BUTTON_X, true); b.down(45, Keys.BUTTON_X)
        val heldReleases = b.releases
        b.key(4, Keys.BUTTON_X, false)
        assertEquals(heldReleases, b.releases)
        b.tap(7, Keys.DPAD_RIGHT)
        assertTrue(b.auditions > 0)
        b.up(45)
        assertTrue(b.releases > heldReleases)
    }

    @Test fun cancelClearsAllLayersAndSuppressesLateUpsAndRepeatsWithoutWriting() {
        val b = Bench()
        b.down(9, Keys.BUTTON_A); b.tap(31, Keys.DPAD_RIGHT)
        val adjusted = b.session.song
        b.tap(66, Keys.BUTTON_L2); b.down(82, Keys.BUTTON_X)
        b.cancel()
        b.up(9); b.up(82)
        b.key(-1001, Keys.BUTTON_A, false)
        b.key(5, Keys.BUTTON_A, true, repeat = 7)
        assertEquals(adjusted, b.session.song)
        assertTrue(b.router.heldModifiers.isEmpty())
        b.tap(31, Keys.DPAD_RIGHT)
        assertEquals("Fresh arrows navigate, not edit", adjusted, b.session.song)
    }

    @Test fun cancellationDuringDownCallbackDoesNotResurrectPointer() {
        lateinit var pointers: TouchPointers
        val events = ArrayList<Pair<Int, Boolean>>()
        pointers = TouchPointers { code, down -> events += code to down; pointers.cancel() }
        pointers.down(50, Keys.BUTTON_A, false)
        pointers.up(50) { false }
        assertFalse(pointers.pressed(Keys.BUTTON_A))
        assertEquals(listOf(Keys.BUTTON_A to true), events)
    }

    @Test fun touchChordCaptureClearsLatchedAndMergedOwnersBeforeReturningToModal() {
        val b = Bench(); val before = b.session.song
        b.startCapture()
        b.tap(10, Keys.BUTTON_L2)
        b.key(4, Keys.BUTTON_L2, true)
        b.tap(20, Keys.BUTTON_R2)
        b.down(30, Keys.BUTTON_A)
        assertEquals(Binding(Keys.BUTTON_A, setOf(Keys.BUTTON_L2, Keys.BUTTON_R2)), b.captured)
        assertNull(b.capture)
        assertFalse(b.touch.pressed(Keys.BUTTON_A))
        assertFalse(b.touch.latched(Keys.BUTTON_L2))
        assertFalse(b.touch.latched(Keys.BUTTON_R2))
        assertTrue(b.router.heldModifiers.isEmpty())
        b.up(30)
        b.key(4, Keys.BUTTON_L2, false)
        b.tap(40, Keys.DPAD_RIGHT)
        assertEquals("Neither capture nor its parent modal edits the song", before, b.session.song)
        b.closeModal()
        b.down(30, Keys.BUTTON_A); b.tap(40, Keys.DPAD_RIGHT); b.up(30)
        assertNotEquals("Fresh input still edits after leaving the modal", before, b.session.song)
    }

    @Test fun cancelledCaptureDoesNotCarryLayersOrLateReleasesIntoNextCapture() {
        val b = Bench(); val before = b.session.song
        b.startCapture()
        b.tap(10, Keys.BUTTON_L2)
        b.cancelCapture()
        assertNull(b.captured)
        assertFalse(b.touch.latched(Keys.BUTTON_L2))
        assertTrue(b.router.heldModifiers.isEmpty())
        b.startCapture()
        b.key(-1001, Keys.BUTTON_L2, false)
        b.tap(20, Keys.BUTTON_X)
        assertEquals(Binding(Keys.BUTTON_X), b.captured)
        assertEquals(before, b.session.song)
    }

    @Test fun hidingDockForgetsVirtualLayersButPhysicalEditGestureContinues() {
        val b = Bench(); val before = b.note()
        b.key(4, Keys.BUTTON_A, true); b.down(45, Keys.BUTTON_A)
        b.down(9, Keys.DPAD_RIGHT)
        b.tap(8, Keys.BUTTON_L2)
        assertEquals(before + 1, b.note())
        b.hideDock()
        assertTrue(b.router.heldModifiers.isEmpty())
        assertFalse(b.touch.latched(Keys.BUTTON_L2))
        assertFalse(b.touch.pressed(Keys.BUTTON_A))
        b.up(45); b.up(9); b.key(-1001, Keys.DPAD_RIGHT, false)
        b.key(4, Keys.DPAD_RIGHT, true); b.key(4, Keys.DPAD_RIGHT, false)
        assertEquals("Physical A still owns the edit after the dock's A went", before + 2, b.note())
        b.key(4, Keys.BUTTON_A, false)
        b.key(4, Keys.DPAD_RIGHT, true); b.key(4, Keys.DPAD_RIGHT, false)
        assertEquals("Physical release ended the edit; arrows navigate", before + 2, b.note())
    }

    @Test fun hidingDockMidVirtualEditCancelsWithoutWritingAndKeepsPhysicalLayer() {
        val b = Bench()
        b.down(17, Keys.BUTTON_A); b.tap(31, Keys.DPAD_RIGHT)
        val adjusted = b.session.song
        b.down(40, Keys.BUTTON_X)
        b.key(4, Keys.BUTTON_L2, true)
        val releases = b.releases
        b.hideDock()
        assertTrue("Forgotten held audition is released", b.releases > releases)
        assertEquals(setOf(Keys.BUTTON_L2), b.router.heldModifiers)
        b.up(17); b.up(40); b.key(-1001, Keys.BUTTON_A, false)
        b.key(4, Keys.BUTTON_L2, false)
        b.key(4, Keys.DPAD_RIGHT, true); b.key(4, Keys.DPAD_RIGHT, false)
        assertEquals("No tap, late release or orphaned hold edits", adjusted, b.session.song)
        assertTrue(b.router.heldModifiers.isEmpty())
    }

    @Test fun hidingDockEndsVirtualEditButKeepsPhysicalListenSounding() {
        val b = Bench()
        b.key(4, Keys.BUTTON_X, true)
        b.down(17, Keys.BUTTON_A); b.tap(31, Keys.DPAD_RIGHT)
        val adjusted = b.session.song
        val releases = b.releases
        b.hideDock()
        assertEquals("Physical Listen keeps sounding", releases, b.releases)
        b.up(17)
        b.key(4, Keys.DPAD_RIGHT, true); b.key(4, Keys.DPAD_RIGHT, false)
        assertEquals("The dock's Edit ended without a tap; arrows no longer edit", adjusted, b.session.song)
        b.key(4, Keys.BUTTON_X, false)
        assertTrue("Physical release stops the audition", b.releases > releases)
    }

    @Test fun hidingDockEndsVirtualListenButKeepsPhysicalEditAndItsAudition() {
        val b = Bench(); val before = b.note()
        b.key(4, Keys.BUTTON_A, true)
        b.down(40, Keys.BUTTON_X)
        val releases = b.releases
        b.hideDock()
        assertEquals("Physical Edit keeps its audition", releases, b.releases)
        b.up(40)
        b.key(4, Keys.DPAD_RIGHT, true); b.key(4, Keys.DPAD_RIGHT, false)
        assertEquals("Physical Edit still adjusts", before + 1, b.note())
        b.key(4, Keys.BUTTON_A, false)
        assertTrue("Physical release stops the audition", b.releases > releases)
        b.key(4, Keys.DPAD_RIGHT, true); b.key(4, Keys.DPAD_RIGHT, false)
        assertEquals(before + 1, b.note())
    }

    @Test fun onlyCurrentOwnersRepeatAndFinalOwnerReleasesTheKey() {
        val owners = TouchKeyOwners()
        assertEquals(TouchKeyOwners.Edge.DOWN, owners.change(-1001, Keys.DPAD_RIGHT, true))
        assertEquals(TouchKeyOwners.Edge.NONE, owners.change(4, Keys.DPAD_RIGHT, true, repeat = 1))
        assertEquals(TouchKeyOwners.Edge.NONE, owners.change(4, Keys.DPAD_RIGHT, true))
        assertEquals(TouchKeyOwners.Edge.REPEAT, owners.change(4, Keys.DPAD_RIGHT, true, repeat = 1))
        assertEquals(TouchKeyOwners.Edge.NONE, owners.change(-1001, Keys.DPAD_RIGHT, false))
        assertFalse(owners.holds(-1001))
        assertTrue(owners.holds(4))
        assertEquals(TouchKeyOwners.Edge.NONE, owners.change(-1001, Keys.DPAD_RIGHT, true, repeat = 2))
        assertEquals(TouchKeyOwners.Edge.UP, owners.change(4, Keys.DPAD_RIGHT, false))
        assertFalse(owners.holds(4))
        owners.change(4, Keys.BUTTON_A, true)
        owners.cancel()
        assertEquals(TouchKeyOwners.Edge.NONE, owners.change(4, Keys.BUTTON_A, false))
        assertEquals(TouchKeyOwners.Edge.NONE, owners.change(4, Keys.BUTTON_A, true, repeat = 1))
        assertEquals(TouchKeyOwners.Edge.DOWN, owners.change(4, Keys.BUTTON_A, true))
    }

    @Test fun dockHidesOnlyForEnabledPhysicalGamepadWithFaceButtonsAndDirections() {
        val faceButtons: (IntArray) -> BooleanArray = { keys -> BooleanArray(keys.size) { keys[it] == Keys.BUTTON_A || keys[it] == Keys.BUTTON_B } }
        val numberedOnly: (IntArray) -> BooleanArray = { keys -> BooleanArray(keys.size) { keys[it] in Keys.BUTTON_1..Keys.BUTTON_16 } }
        val handheld = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val hatPad = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val keyboard = InputDevice.SOURCE_KEYBOARD or InputDevice.SOURCE_DPAD
        assertTrue("Built-in handheld pad", TouchControllers.usable(false, true, handheld, faceButtons))
        assertTrue("Stick/hat directions", TouchControllers.usable(false, true, hatPad, faceButtons))
        assertFalse("Disabled", TouchControllers.usable(false, false, handheld, faceButtons))
        assertFalse("Virtual", TouchControllers.usable(true, true, handheld, faceButtons))
        assertFalse("Keyboard", TouchControllers.usable(false, true, keyboard, faceButtons))
        assertFalse("D-pad remote", TouchControllers.usable(false, true, InputDevice.SOURCE_DPAD, faceButtons))
        assertFalse("No directions", TouchControllers.usable(false, true, InputDevice.SOURCE_GAMEPAD, faceButtons))
        assertFalse("No A/B", TouchControllers.usable(false, true, handheld, numberedOnly))
    }
}
