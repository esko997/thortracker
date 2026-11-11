package org.thortracker.ui

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.system.gb.GbSystem

class PatternHeaderPainterTest {
    private data class Text(val value: String, val y: Float)
    private class RecordingSurface : Surface {
        val texts = mutableListOf<Text>()
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) { texts += Text(s, top) }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {}
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {}
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {}
    }

    private class Grid(val session: EditorSession) {
        val state = ViewState()
        private val surface = RecordingSurface()
        val view = PaneView(session, state, 1f, surface, { 0L }, {})
        private val painter = PatternPainter(view)
        fun headerSlots(): List<String> {
            surface.texts.clear()
            painter.draw(surface, Rect(0f, 0f, 1200f, 500f))
            val y = surface.texts.first { it.value == GbSystem.channels[0].name }.y
            return surface.texts.filter { it.y == y && it.value.matches(Regex("[0-9A-F]{2} .*")) }
                .map { it.value.substringBefore(' ') }
        }
    }

    @Test fun headersDescribeEachTrackNotTheSharedEntryPenAndFollowEditUndo() {
        val session = EditorSession(GbSystem.blank())
        session.setCell(0, 0, 0, Cell(48, 2))
        session.setCell(0, 0, 1, Cell(50, 2))
        session.setCell(0, 0, 2, Cell(52, 1))
        session.setCell(0, 1, 0, Cell(48, 3))
        session.setCell(0, 1, 1, Cell(50, 3))
        session.setCell(0, 1, 2, Cell(52, 1))
        val grid = Grid(session)
        assertEquals(listOf("02", "03"), grid.headerSlots())
        val original = session.song
        grid.view.controller.setPen(0, 8)
        grid.state.instrument = 9
        assertEquals(listOf("02", "03"), grid.headerSlots())
        assertSame(original, session.song)
        session.setCell(0, 0, 0, Cell(48, 1))
        assertEquals(listOf("01", "03"), grid.headerSlots())
        session.undo()
        assertEquals(listOf("02", "03"), grid.headerSlots())
    }

    @Test fun tiesEmptyAssignmentsAndHiddenRowsStayPatternLocal() {
        val blank = GbSystem.blank()
        val session = EditorSession(blank.copy(patterns = listOf(blank.patterns[0], Pattern.blank(GbSystem.channels.size)), orders = listOf(0, 1)))
        session.setCell(0, 0, 0, Cell(48, 2))
        session.setCell(0, 0, 1, Cell(50, 1))
        for (row in 2..8) session.setCell(0, 0, row, Cell(52, 2))
        session.setCell(0, 1, 0, Cell(48, 3))
        session.setCell(0, 2, 0, Cell(48))
        session.setCell(0, 2, 1, Cell(Cell.CUT))
        session.setPatternLength(0, 2)
        val grid = Grid(session)
        assertEquals(listOf("01", "03"), grid.headerSlots())
        session.setPatternLength(0, 9)
        assertEquals(listOf("02", "03"), grid.headerSlots())
        grid.state.order = 1
        assertEquals(emptyList<String>(), grid.headerSlots())
        grid.state.order = 0
        assertEquals(listOf("02", "03"), grid.headerSlots())
    }
}
