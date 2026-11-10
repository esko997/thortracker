package org.thortracker.input

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
class PreviewInputTest {
    private class Bench {
        val session = EditorSession(Songs.demo()).apply { step = 0 }
        val view = ViewController(session, ViewState(kind = ViewKind.PATTERN))
        val router = HostInput()
        val owners = PreviewKeyOwners()
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
            if (owners.change(source, code, down, repeat) == PreviewKeyOwners.Edge.NONE) return
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
        val touch = PreviewPointers { code, down -> key(-1001, code, down) }
        fun down(id: Int, code: Int) = touch.down(id, code, code in Keymap.MODIFIERS)
        fun up(id: Int) = touch.up(id) { it in Keymap.MODIFIERS }
        fun tap(id: Int, code: Int) { down(id, code); up(id) }
        fun cancel() { touch.cancel(); owners.cancel(); router.reset(); view.cancelHolds() }
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
        val pointers = PreviewPointers { code, down -> events += code to down }
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
        lateinit var pointers: PreviewPointers
        val events = ArrayList<Pair<Int, Boolean>>()
        pointers = PreviewPointers { code, down -> events += code to down; pointers.cancel() }
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
}
