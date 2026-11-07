package org.thortracker.system.nes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.NesInstrumentView.Row

/** The NES instrument editor driven only through controller actions, and its envelope edits. */
class NesInstrumentViewTest {
    private val session = EditorSession(NesSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        state.instField = NesInstrumentView.rows(state).indexOf(row)
    }

    private val sound get() = session.song.nes.instruments[0]

    @Test
    fun rowsFollowTheEnvelope() {
        val st = ViewState()
        assertTrue(Row.RELEASE in NesInstrumentView.rows(st))
        assertFalse(Row.MODE in NesInstrumentView.rows(st))
        st.envelope = NesEnvelopeKind.PITCH.ordinal
        assertFalse(Row.RELEASE in NesInstrumentView.rows(st))
        assertTrue(Row.MODE in NesInstrumentView.rows(st))
    }

    @Test
    fun stepsAreDrawnAndEditedWithTheController() {
        val v = view { instrument = 1 }
        v.goTo(Row.STEPS)
        // Volume starts as one step of 15: lower it, then carry it along three more steps.
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN)
        assertEquals(listOf(13), sound.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(13, 13, 13, 13), sound.volume.values)
        assertEquals(3, v.state.envStep)
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(listOf(13, 13, 13, 10), sound.volume.values)
        // One hold is one undo step.
        session.undo()
        assertEquals(listOf(13, 13, 13, 13), sound.volume.values)
        // Clear deletes the step under the cursor.
        v.tap(Action.LEFT)
        v.tap(Action.CLEAR)
        assertEquals(listOf(13, 13, 13), sound.volume.values)
        // Past the end, a tap appends the last value.
        v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        assertEquals(3, v.state.envStep)
        v.tap(Action.EDIT)
        assertEquals(listOf(13, 13, 13, 13), sound.volume.values)
    }

    @Test
    fun valuesStayInTheirEnvelopesRange() {
        val v = view { instrument = 2; envelope = NesEnvelopeKind.DUTY.ordinal }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(3), session.song.nes.instruments[1].duty.values)
        v.tap(Action.NEXT_CHANNEL) // to volume (wraps)
        assertEquals(NesEnvelopeKind.VOLUME, NesInstrumentView.kind(v.state))
        v.tap(Action.PREV_CHANNEL); v.tap(Action.PREV_CHANNEL) // pitch
        assertEquals(NesEnvelopeKind.PITCH, NesInstrumentView.kind(v.state))
        assertEquals(Row.STEPS, NesInstrumentView.row(v.state))
        v.hold(Action.EDIT, *Array(80) { Action.DOWN })
        assertEquals(listOf(-64), session.song.nes.instruments[1].pitch.values)
    }

    @Test
    fun lengthLoopAndReleaseStayLegal() {
        val v = view { instrument = 1 }
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.UP) // +8
        assertEquals(9, sound.volume.values.size)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT) // first legal release: step 0 (no loop yet)
        assertEquals(0, sound.volume.release)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(4, sound.volume.release)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, *Array(9) { Action.RIGHT })
        assertEquals("the loop stays before the release", 3, sound.volume.loop)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, *Array(6) { Action.LEFT })
        assertEquals(3, sound.volume.values.size)
        assertEquals(-1, sound.volume.release)
        assertEquals(2, sound.volume.loop)
        v.goTo(Row.LOOP)
        v.tap(Action.CLEAR)
        assertEquals(-1, sound.volume.loop)
    }

    @Test
    fun releaseOnlyOnVolume() {
        val e = NesEnvelope(listOf(1, 2, 3), loop = 0, release = 2)
        assertEquals(-1, NesEnvelopeKind.ARPEGGIO.fixed(e).release)
        assertEquals(e, NesEnvelopeKind.VOLUME.fixed(e))
        assertEquals(NesEnvelope(listOf(2, 3), 0, 1), NesEnvelopes.removed(NesEnvelopeKind.VOLUME, e, 0))
    }

    @Test
    fun listenPlaysTheChosenChannelAndSlot() {
        val v = view { instrument = 5 }
        v.goTo(Row.LISTEN)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        v.press(Action.AUDITION)
        assertEquals(2, auditions.last().first)
        assertEquals(5, auditions.last().third)
        v.release(Action.AUDITION)
    }

    @Test
    fun copyPasteEnvelopeBetweenKinds() {
        val v = view { instrument = 1; envelope = NesEnvelopeKind.ARPEGGIO.ordinal }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(20) { Action.UP }, Action.RIGHT)
        assertEquals(listOf(20, 20), sound.arpeggio.values)
        v.tap(Action.COPY)
        v.tap(Action.NEXT_CHANNEL) // pitch
        v.tap(Action.NEXT_CHANNEL) // duty
        v.tap(Action.PASTE)
        assertEquals("clamped into duty's range", listOf(3, 3), sound.duty.values)
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        v.tap(Action.RIGHT)
        v.tap(Action.PASTE)
        assertEquals(sound, session.song.nes.instruments[1])
    }

    @Test
    fun duplicateAndResetSlot() {
        val v = view { instrument = 1 }
        v.goTo(Row.NAME)
        v.hold(Action.EDIT, Action.RIGHT)
        val named = sound.name
        v.goTo(Row.SLOT)
        v.tap(Action.DUPLICATE)
        assertEquals(2, v.state.instrument)
        assertEquals("$named+", session.song.nes.instruments[1].name)
        v.tap(Action.CLEAR)
        assertEquals(defaultNesInstrument(2), session.song.nes.instruments[1])
    }

    @Test
    fun noWaveViewOnNes() {
        assertFalse(ViewKind.WAVE in NesSystem.views)
        assertEquals("NES (FamiStudio) has no Wave view", Workspace.missingView(ViewKind.WAVE, NesSystem))
        assertNull(Workspace.missingView(ViewKind.WAVE, GbSystem))
        // A pane showing Wave falls back to Pattern on an NES song.
        val v = ViewController(session, ViewState(kind = ViewKind.WAVE))
        v.press(Action.DOWN)
        assertEquals(ViewKind.PATTERN, v.state.kind)
    }
}
