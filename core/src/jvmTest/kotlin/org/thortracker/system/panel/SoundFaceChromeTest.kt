package org.thortracker.system.panel

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Song
import org.thortracker.system.opl3.*
import org.thortracker.system.opm.*
import org.thortracker.system.opn.*
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

/** Exercises the shared primitive through real family bindings, native patches and document history. */
class SoundFaceChromeTest {
    private data class Fixture(val family: String, val song: Song, val count: Int, val dragControl: Int = 0)
    private fun fixtures(): List<Fixture> {
        val opl = Opl3System.blank()
        val opm = OpmSystem.blank()
        val opn = OpnSystem.blank()
        val opna = OpnaSystem.blank()
        return listOf(
            Fixture("opl3", opl.copy(sound = opl.opl3.withInstrument(60, defaultOpl3Instrument(60).copy(algorithm = 0, fourOp = false))), 6, 1),
            Fixture("opm", opm.copy(sound = opm.opm.withInstrument(60, defaultOpmInstrument(60).copy(algorithm = 0))), 6),
            Fixture("opn", opn.copy(sound = opn.opn.withInstrument(60, OpnEdits.newSound(OpnChip.YM2203, OpnPart.FM, 60))), 5),
            Fixture("opn", opna.copy(sound = opna.opn.withInstrument(60, OpnEdits.newSound(OpnChip.YM2608, OpnPart.FM, 60))), 5),
            Fixture("opn", opna.copy(sound = opna.opn.withInstrument(60, OpnEdits.newSound(OpnChip.YM2608, OpnPart.SSG, 60))), 5),
            Fixture("opn", opna.copy(sound = opna.opn.withInstrument(60, OpnRhythmSound("Rhythm"))), 2),
            Fixture("opn", opna.copy(sound = opna.opn.withInstrument(60, OpnAdpcmSound("Sample"))), 5),
        )
    }

    private class Canvas : Surface {
        val bankText = ArrayList<String>()
        var bankRight = 0f
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = Unit
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = Unit
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = Unit
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = Unit
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            if (x < bankRight && top >= 48f) bankText += "$s|$x|$top|$h|$color|$face|$bold|$size|$align"
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }

    private class Panel(val fixture: Fixture, val width: Int = 960, val height: Int = 720) {
        val session = EditorSession(fixture.song)
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 60, instField = fixture.dragControl + 2)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter: SoundFaceChrome<*> = when (fixture.family) {
            "opl3" -> Opl3SoundPainter(pane)
            "opm" -> OpmSoundPainter(pane)
            else -> OpnSoundPainter(pane)
        }
        private val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            surface.bankText.clear()
            surface.bankRight = minOf(width * 0.26f, pane.ink.unit * 12f)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise()
            painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
        fun key(a: Action) { pane.controller.press(a); pane.controller.release(a) }
    }

    @Test fun openingAndModeSwitchingAreReadOnlyAndUseIdenticalBankTypographyAndPositions() {
        for (fixture in fixtures()) {
            val p = Panel(fixture)
            val before = p.session.song
            val soundBank = p.surface.bankText.toList()
            assertTrue(soundBank.isNotEmpty())
            p.tap(p.painter.modeRect(true)); p.draw()
            assertTrue(p.state.soundPanel(fixture.family).advanced)
            assertEquals(soundBank, p.surface.bankText)
            p.tap(p.painter.modeRect(false)); p.draw()
            assertFalse(p.state.soundPanel(fixture.family).advanced)
            assertSame(before, p.session.song)
        }
    }

    @Test fun compactFacesPageEveryControlAtPhysicalTouchSizeWithoutEditing() {
        for (fixture in fixtures()) {
            val p = Panel(fixture, 360, 270)
            val before = p.session.song
            assertTrue(p.painter.compact)
            p.state.instField = 2
            for (i in 0 until fixture.count) {
                p.draw()
                val r = p.painter.controlRect(i)
                assertTrue("${fixture.family} control $i width", r.width() >= 48f)
                assertTrue("${fixture.family} control $i height", r.height() >= 48f)
                assertTrue(r.left >= 0f && r.right <= 360f && r.top >= 0f && r.bottom <= 270f)
                val next = p.painter.navigationRect(true)
                assertTrue(next.width() >= 48f && next.height() >= 48f)
                p.tap(next)
            }
            assertEquals(2, p.state.instField)
            assertSame(before, p.session.song)
            p.tap(p.painter.nameTarget()); assertEquals(1, p.state.instField)
            p.tap(p.painter.slotNavigationRect(true)); assertEquals(61, p.state.instrument)
            assertSame(before, p.session.song)
        }
    }

    @Test fun pinnedDragsSurviveRepaintingAreOneUndoAndCannotResumeAfterUndo() {
        for (fixture in fixtures()) {
            val p = Panel(fixture)
            val before = p.session.song
            val i = fixture.dragControl
            val r = p.painter.controlRect(i)
            assertTrue(p.painter.down(p.painter.valueX(i, 0.2f), r.centerY()))
            p.draw()
            p.painter.move(p.painter.valueX(i, 0f), r.centerY())
            p.painter.up(0f, 0f)
            assertNotEquals(before, p.session.song)
            p.session.undo(); assertSame(before, p.session.song)
            p.draw()
            p.painter.down(p.painter.valueX(i, 0.2f), p.painter.controlRect(i).centerY())
            p.session.undo()
            val undone = p.session.song
            p.painter.move(p.painter.valueX(i, 0.9f), 0f)
            p.painter.up(0f, 0f)
            assertSame(undone, p.session.song)
        }
    }

    @Test fun slotAndModeChangesInvalidateTouchesAndBlockAHeldAcrossTheChange() {
        for (fixture in fixtures()) {
            val p = Panel(fixture)
            val before = p.session.song
            p.pane.controller.press(Action.EDIT)
            p.tap(p.painter.slotNavigationRect(true))
            p.key(Action.RIGHT)
            p.pane.controller.release(Action.EDIT)
            assertSame(before, p.session.song)
            p.tap(p.painter.slotNavigationRect(false)); p.draw()
            p.pane.controller.press(Action.EDIT)
            p.tap(p.painter.modeRect(true)); p.draw()
            p.key(Action.RIGHT)
            p.pane.controller.release(Action.EDIT)
            assertSame(before, p.session.song)
            p.tap(p.painter.modeRect(false)); p.draw()
            val i = fixture.dragControl
            p.painter.down(p.painter.valueX(i, 0.2f), p.painter.controlRect(i).centerY())
            p.tap(p.painter.modeRect(true))
            val afterMode = p.session.song
            p.painter.move(999f, 0f); p.painter.up(999f, 0f)
            assertSame(afterMode, p.session.song)
        }
    }

    @Test fun controllerAxesNameBoundsAndModeRingAreConsistentForEveryFamily() {
        for (fixture in fixtures()) {
            val p = Panel(fixture)
            val before = p.session.song
            p.state.instField = 2
            p.key(Action.LEFT); assertEquals(1, p.state.instField)
            p.pane.controller.press(Action.EDIT)
            repeat(40) { p.key(Action.DOWN) }
            assertEquals(15, p.state.textPos)
            repeat(40) { p.key(Action.UP) }
            assertEquals(0, p.state.textPos)
            p.pane.controller.release(Action.EDIT)
            p.key(Action.LEFT); assertEquals(-1, p.state.instField)
            p.key(Action.EDIT); assertTrue(p.state.soundPanel(fixture.family).advanced)
            p.key(Action.EDIT); assertFalse(p.state.soundPanel(fixture.family).advanced)
            p.key(Action.RIGHT); assertEquals(1, p.state.instField)
            p.key(Action.RIGHT); assertEquals(2, p.state.instField)
            p.key(Action.DOWN); assertEquals(61, p.state.instrument)
            assertEquals(2, p.state.instField)
            p.key(Action.UP); assertEquals(60, p.state.instrument)
            assertSame(before, p.session.song)
        }
    }
}
