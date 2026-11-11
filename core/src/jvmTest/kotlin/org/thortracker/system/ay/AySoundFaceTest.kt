package org.thortracker.system.ay

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect

class AySoundFaceTest {
    private fun patch() = AyInstrument(name = "Keep me", volume = NesEnvelope(listOf(12, 8, 9, 0)),
        arpeggio = NesEnvelope(listOf(0, 7), loop = 0), pitch = NesEnvelope(listOf(2, -1), loop = 0),
        pitchRelative = true, mixer = NesEnvelope(listOf(1, 3), loop = 0), noise = NesEnvelope(listOf(4, 12), loop = 0),
        envelope = AyEnvelopeSettings(shape = 0, auto = false, octave = -2, period = 12345, sync = true), sweep = AySweep(12, -8, 3))
    private fun session(variant: AyVariant = AyVariant.ZX128) = EditorSession(variant.system.blank()).also {
        it.setAyInstrument(1, patch(), "Fixture")
    }
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun view(s: EditorSession) = ViewController(s, ViewState(kind = ViewKind.INSTRUMENT, instrument = 1))

    @Test fun recipesOwnOnlyTheirDeclaredFieldsAndKeepCustomPrograms() {
        val before = patch()
        for (c in listOf(AySoundControl.SOURCE, AySoundControl.CONTOUR, AySoundControl.COLOUR)) assertNull(AySoundMacros.value(c, before))
        for (c in AySoundControl.entries) {
            val source = if (c == AySoundControl.REGISTER) before.copy(envelope = before.envelope.copy(shape = 10)) else before
            for (n in 0..c.maximum) {
                val after = AySoundMacros.apply(c, source, n)
                assertNull(after.problem())
                assertEquals(n, AySoundMacros.value(c, after))
                assertSame(after, AySoundMacros.apply(c, after, n))
                assertEquals(source, when (c) {
                    AySoundControl.SOURCE -> after.copy(mixer = source.mixer)
                    AySoundControl.CONTOUR -> after.copy(volume = source.volume)
                    AySoundControl.COLOUR -> after.copy(noise = source.noise)
                    AySoundControl.BUZZ -> after.copy(envelope = after.envelope.copy(shape = source.envelope.shape))
                    AySoundControl.REGISTER -> after.copy(envelope = after.envelope.copy(auto = source.envelope.auto, octave = source.envelope.octave))
                })
            }
        }
        val hardware = before.copy(envelope = before.envelope.copy(shape = 14))
        assertFalse(AySoundMacros.enabled(AySoundControl.CONTOUR, hardware))
        assertSame(hardware, AySoundMacros.apply(AySoundControl.CONTOUR, hardware, 1))
        assertNull(AySoundMacros.value(AySoundControl.REGISTER, hardware))
        val auto = AySoundMacros.apply(AySoundControl.REGISTER, hardware, 15)
        assertEquals(12345, auto.envelope.period)
        assertEquals(7, auto.envelope.octave)
        assertTrue(auto.envelope.auto)
        assertEquals(-8, AySoundMacros.apply(AySoundControl.REGISTER, auto, -100).envelope.octave)
        assertEquals(7, AySoundMacros.apply(AySoundControl.REGISTER, auto, 100).envelope.octave)
        val pluck = before.copy(volume = EnvelopeContour.make(EnvelopeContour.Shape.PLUCK, 8, 11))
        assertSame(pluck, AySoundMacros.apply(AySoundControl.CONTOUR, pluck, 1))
        assertEquals(11, AySoundMacros.apply(AySoundControl.CONTOUR, pluck, 2).volume.values.maxOrNull())
    }

    @Test fun everyComputerSwitchesFacesWithoutChangingAnyInstrument() {
        for (variant in AyVariant.entries) {
            val s = session(variant)
            val v = view(s)
            val before = s.song
            v.normalise()
            assertEquals(2, v.state.instField)
            repeat(3) { AySoundFace.selectMode(v, true); v.normalise(); AySoundFace.selectMode(v, false); v.normalise() }
            assertSame(before, s.song)
            v.tap(Action.RIGHT)
            assertEquals(3, v.state.instField)
            v.tap(Action.DOWN)
            assertEquals(2, v.state.instrument)
            assertEquals(3, v.state.instField)
            v.tap(Action.UP)
            assertEquals(1, v.state.instrument)
            v.state.instField = 1
            v.press(Action.EDIT)
            repeat(60) { v.tap(Action.DOWN) }
            assertEquals(AyInstrumentView.NAME_MAX - 1, v.state.textPos)
            repeat(60) { v.tap(Action.UP) }
            assertEquals(0, v.state.textPos)
            v.release(Action.EDIT)
            assertSame(before, s.song)
        }
    }

    @Test fun editsAuditionGroupUndoAndBlockHeldModeOrSlotCrossing() {
        val s = session()
        val v = view(s)
        val heard = mutableListOf<Int>()
        s.onAudition = { ch, _, _, _ -> heard += ch }
        val before = s.song
        v.normalise()
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertTrue(heard.size >= 3)
        assertNotEquals(before, s.song)
        s.undo(); assertSame(before, s.song)
        v.press(Action.EDIT)
        AySoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(before, s.song)
        AySoundFace.selectMode(v, false)
        v.press(Action.EDIT)
        AySoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
        v.state.instrument = 60
        v.state.instField = AySoundControl.REGISTER.ordinal + 2
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertNull(s.song.ayInstrument(60)) // disabled tuning must not create a default sound
        v.state.instField = 2
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertNotNull(s.song.ayInstrument(60))
        assertNull(s.song.ayInstrument(60)!!.problem())
    }

    private class Panel(val s: EditorSession, val width: Int = 360, val height: Int = 270) {
        val canvas = SoundFaceCanvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1)
        val pane = PaneView(s, state, 1f, canvas, { 0L }, {})
        val painter = AySoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise(); painter.draw(canvas, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(0f, 0f) }
    }

    @Test fun compactTouchPagesEveryControlAndReusesTheExactAdvancedSidebar() {
        val s = session()
        val before = s.song
        for (size in listOf(360 to 270, 960 to 720)) {
            val p = Panel(s, size.first, size.second)
            for (i in AySoundControl.entries.indices) {
                p.state.instField = i + 2; p.draw()
                val r = p.painter.controlRect(i)!!
                assertTrue("control $i is touch sized", r.width() >= 48f && r.height() >= 48f)
            }
            if (p.painter.compact) {
                p.state.instField = 2; p.draw()
                repeat(5) { i ->
                    assertNotNull(p.painter.controlRect(i))
                    p.tap(p.painter.navigationRect(true)!!); p.draw()
                    assertEquals((i + 1) % 5 + 2, p.state.instField)
                }
            }
            p.tap(p.painter.slotNavigationRect(true)); p.draw()
            assertEquals(2, p.state.instrument)
            p.tap(p.painter.slotNavigationRect(false)); p.draw()
            val slot = p.painter.slotRect(1)!!
            p.canvas.columnRight = slot.right; p.canvas.columnTop = slot.top
            p.canvas.ops.clear(); p.draw(); val sound = p.canvas.ops.toList()
            p.tap(p.painter.modeRect(true)); p.canvas.ops.clear(); p.draw()
            assertEquals(sound, p.canvas.ops)
            p.tap(p.painter.modeRect(false)); p.draw()
            p.tap(p.painter.listenRect())
            assertEquals(1, p.state.listenChannel)
            assertSame(before, s.song)
        }
    }

    @Test fun dragsPinOriginalPatchAndStopOnUndoSlotAndModeChanges() {
        val s = session()
        val p = Panel(s)
        val before = s.song
        fun start() { p.state.instField = 2; p.draw(); val r = p.painter.controlRect(0)!!; p.painter.down(r.left + 1f, r.centerY()) }
        start(); p.painter.move(999f, 0f); p.painter.up(0f, 0f)
        assertEquals(0, s.song.ayInstrument(1)!!.mixer.values.single())
        s.undo(); assertSame(before, s.song)
        start(); s.undo(); p.painter.move(999f, 0f); p.painter.up(0f, 0f)
        assertSame(before, s.song)
        start(); AySoundFace.selectMode(p.pane.controller, true)
        val switched = s.song
        p.painter.move(999f, 0f); assertSame(switched, s.song)
        s.undo(); assertSame(before, s.song)
        AySoundFace.selectMode(p.pane.controller, false)
        start(); AySoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song
        p.painter.move(999f, 0f); assertSame(moved, s.song)
        s.undo(); assertSame(before, s.song)
    }
}
