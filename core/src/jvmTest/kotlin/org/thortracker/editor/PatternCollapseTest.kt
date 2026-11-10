package org.thortracker.editor

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.ProjectCodec
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.opl3.Opl3Lowering
import org.thortracker.system.opl3.Opl3Rules
import org.thortracker.system.opl3.Opl3System

class PatternCollapseTest {
    private fun ViewController.tap(action: Action) { press(action); release(action) }

    @Test fun togglePreservesDocumentHistoryProjectAndExportBytes() {
        for (song in listOf(GbSystem.demo(), Opl3System.demo())) {
            val session = EditorSession(song)
            val controller = ViewController(session, ViewState(field = 3))
            val project = ProjectCodec.encode(song)
            val bytes = if (song.system == GbSystem) GbSystem.exports.single().export(song) else Opl3Lowering.lower(song)
            controller.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
            assertTrue(controller.channelCollapsed(0))
            assertEquals(0, controller.state.field)
            assertSame(song, session.song)
            assertFalse(session.canUndo)
            assertFalse(session.canRedo)
            assertEquals(project, ProjectCodec.encode(session.song))
            assertArrayEquals(bytes, if (song.system == GbSystem) GbSystem.exports.single().export(session.song) else Opl3Lowering.lower(session.song))
            controller.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
            assertFalse(controller.channelCollapsed(0))
            assertSame(song, session.song)
            assertEquals(project, ProjectCodec.encode(session.song))
        }
    }

    @Test fun navigationVisitsOnlyVisibleFieldsAndRestoresEffectColumnChoices() {
        val session = EditorSession(Opl3System.blank())
        val state = ViewState(effectColumns = listOf(4, 2, 3), field = 9)
        val c = ViewController(session, state)
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        assertEquals(1, c.fieldCount(0))
        assertEquals(4, c.shownColumns(0))
        c.tap(Action.RIGHT)
        assertEquals(1, state.channel)
        assertEquals(0, state.field)
        c.tap(Action.LEFT)
        assertEquals(0, state.channel)
        assertEquals(0, state.field)
        c.tap(Action.NEXT_CHANNEL)
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        c.tap(Action.RIGHT)
        assertEquals(2, state.channel)
        c.tap(Action.LEFT)
        assertEquals(1, state.channel)
        assertEquals(0, state.field)
        c.tap(Action.PREV_CHANNEL)
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        assertEquals(10, c.fieldCount(0))
        repeat(9) { c.tap(Action.RIGHT) }
        assertEquals(9, state.field)
        c.tap(Action.NEXT_CHANNEL)
        assertEquals(0, state.field)
        c.tap(Action.NEXT_CHANNEL)
        state.field = 7
        c.press(Action.MARK)
        c.tap(Action.LEFT)
        assertEquals(0, state.field)
        c.release(Action.MARK)
    }

    @Test fun noteEditsAndBlockCopyKeepHiddenSoundsAndEveryEffectColumn() {
        val session = EditorSession(Opl3System.blank())
        session.step = 0
        val original = Cell(48, 2).withEffect(0, Opl3Rules.FX_VOLUME, 0x30).withEffect(3, Opl3Rules.FX_CHORD, 0x37)
        session.setCell(0, 0, 0, original)
        val c = ViewController(session, ViewState())
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        c.press(Action.EDIT)
        c.tap(Action.RIGHT)
        c.release(Action.EDIT)
        val edited = session.song.patterns[0].tracks[0][0]
        assertEquals(original.copy(note = 49), edited)
        c.tap(Action.COPY)
        c.tap(Action.NEXT_CHANNEL)
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        c.tap(Action.PASTE)
        assertEquals(edited, session.song.patterns[0].tracks[1][0])
        assertTrue(c.state.mark)
        c.tap(Action.MARK) // clear the pasted selection; a new note still uses normal entry memory
        c.tap(Action.DOWN)
        c.tap(Action.EDIT)
        assertTrue(session.song.patterns[0].tracks[1][1].note >= 0)
        assertTrue(session.song.patterns[0].tracks[1][1].instrument > 0)
    }

    @Test fun collapseIsPaneLocalPatternOnlyAndDoesNotInterruptHeldEdits() {
        val session = EditorSession(Opl3System.blank())
        val c = ViewController(session, ViewState(field = 3))
        c.press(Action.EDIT)
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        assertFalse(c.channelCollapsed(0))
        assertEquals(3, c.state.field)
        c.release(Action.EDIT)
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        val snapshot = c.state.snapshot()
        c.tap(Action.TOGGLE_CHANNEL_COLLAPSE)
        assertEquals(setOf(0), snapshot.collapsedChannels)
        val workspace = Workspace(listOf(c.state, snapshot))
        workspace.swap()
        assertEquals(setOf(0), workspace.focused.collapsedChannels)
        for (kind in Opl3System.views.filter { it != ViewKind.PATTERN }) {
            val state = ViewState(kind = kind, collapsedChannels = setOf(0))
            val other = ViewController(session, state)
            assertFalse(other.press(Action.TOGGLE_CHANNEL_COLLAPSE))
            assertEquals(setOf(0), state.collapsedChannels)
            assertEquals(4, other.fieldCount(0))
        }
    }
}
