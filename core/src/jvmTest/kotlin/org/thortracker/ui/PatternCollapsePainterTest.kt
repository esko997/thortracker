package org.thortracker.ui

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.system.opl3.Opl3System

class PatternCollapsePainterTest {
    private data class Text(val value: String, val x: Float, val y: Float, val height: Float)
    private class RecordingSurface : Surface {
        val commands = mutableListOf<List<Any>>()
        val texts = mutableListOf<Text>()
        private fun record(vararg values: Any) { commands += values.toList() }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            texts += Text(s, x, top, h)
            record("text", s, x, top, h, color, face, bold, size, align)
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = record("rect", l, t, r, b, color)
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = record("round", l, t, r, b, radius, color)
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = record("stroke", l, t, r, b, color, width, radius)
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = record("line", x0, y0, x1, y1, color, width)
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = record("circle", cx, cy, radius, color)
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = record("circleStroke", cx, cy, radius, color, width)
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = record("polyline", xs.take(count), ys.take(count), color, width)
        fun clear() { commands.clear(); texts.clear() }
    }

    @Test fun narrowDenseGridReclaimsWidthAndTouchNeverSelectsHiddenFields() {
        for (width in listOf(400f, 600f)) {
            val session = EditorSession(Opl3System.blank())
            for (ch in Opl3System.channels.indices) session.setCell(0, ch, 0, Cell(48, 1))
            val state = ViewState(effectColumns = List(Opl3System.channels.size) { 4 })
            val surface = RecordingSurface()
            val view = PaneView(session, state, 1f, surface, { 0L }, {})
            view.controllerFocused = true
            val painter = PatternPainter(view)
            val area = Rect(0f, 0f, width, 420f)
            fun draw() { surface.clear(); painter.draw(surface, area) }
            fun notes() = surface.texts.filter { it.value == Opl3System.noteName(48) }.distinctBy { it.x }
            draw()
            val expandedCount = notes().size
            val expanded = surface.commands.toList()
            view.controllerAction(Action.TOGGLE_CHANNEL_COLLAPSE, true, 0)
            view.controllerAction(Action.TOGGLE_CHANNEL_COLLAPSE, true, 1)
            view.controllerAction(Action.TOGGLE_CHANNEL_COLLAPSE, false, 0)
            assertTrue(view.controller.channelCollapsed(0)) // key repeat must not toggle it back
            draw()
            assertTrue("width=$width did not reclaim a lane", notes().size > expandedCount)
            assertTrue(surface.texts.any { it.value == "01+" })
            val note = notes().minBy { it.x }
            // The right edge of the collapsed lane is still the note field, including its padding.
            val x = note.x + view.ink.monoWidth("0") * 3.3f
            assertTrue(painter.down(x, note.y + note.height / 2))
            painter.up(x, note.y + note.height / 2)
            assertEquals(0, state.channel)
            assertEquals(0, state.field)
            view.controller.press(Action.TOGGLE_CHANNEL_COLLAPSE)
            draw()
            assertEquals("expanded drawing changed at width=$width", expanded, surface.commands)
            // Every channel can stay collapsed at note width; no spare-space stretching.
            state.collapsedChannels = Opl3System.channels.indices.toSet()
            draw()
            val compact = notes().sortedBy { it.x }
            assertEquals(Opl3System.channels.size, compact.size)
            assertEquals(view.ink.monoWidth("0") * 4.2f, compact[1].x - compact[0].x, 0.001f)
            assertEquals(0, state.field)
        }
    }
}
