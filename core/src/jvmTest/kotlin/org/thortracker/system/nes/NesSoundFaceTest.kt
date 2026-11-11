package org.thortracker.system.nes

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.NameEdit
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class NesSoundFaceTest {
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun ViewController.edit(vararg moves: Action) {
        press(Action.EDIT)
        for (a in moves) tap(a)
        release(Action.EDIT)
    }
    private fun view(session: EditorSession) = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 1)).also { it.normalise() }
    private fun sound(v: ViewController) = v.session.song.nesInstrument(v.state.instrument)!!

    @Test fun everyNativeChannelCanBeChosenAndBothFacesPreserveItsPatch() {
        for (expansion in NesExpansion.entries) {
            val session = EditorSession(expansion.system.blank())
            val v = view(session)
            for (channel in session.song.system.channels.indices) {
                NesSoundFace.set(v, NesSoundControl.VOICE, channel)
                v.normalise()
                assertEquals("$expansion/$channel", channel, v.state.listenChannel)
                assertEquals(NesRules.chip(expansion, channel), sound(v).chip)
                val before = session.song
                val expected = NesVoice.of(expansion, channel, sound(v).chip)
                assertEquals(expected, NesSoundFace.voice(before, v.state))
                repeat(2) {
                    NesSoundFace.selectMode(v, true); v.normalise()
                    NesSoundFace.selectMode(v, false); v.normalise()
                }
                assertSame(before, session.song)
                for (c in NesSoundFace.controls(before, v.state)) {
                    NesSoundFace.value(c, before, v.state, sound(v))?.let { NesSoundFace.set(v, c, it) }
                }
                assertSame(before, session.song)
            }
        }
    }

    @Test fun bareAxesNavigateWithoutEditingAndModeIsInTheHorizontalRing() {
        val session = EditorSession(NesSystem.blank())
        val v = view(session)
        val before = session.song
        assertEquals(2, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument); assertEquals(2, v.state.instField)
        v.tap(Action.UP)
        assertEquals(1, v.state.instrument)
        for (field in listOf(3, 4, 5, -1, 1, 2)) {
            v.tap(Action.RIGHT); assertEquals(field, v.state.instField)
        }
        v.tap(Action.LEFT); v.tap(Action.LEFT)
        assertEquals(-1, v.state.instField)
        v.edit(Action.RIGHT)
        assertTrue(NesSoundFace.panel(v.state).advanced)
        v.tap(Action.DOWN)
        assertEquals(0, v.state.instField)
        v.tap(Action.RIGHT)
        assertEquals(2, v.state.instrument) // Advanced retains horizontal slot navigation.
        v.tap(Action.UP)
        assertEquals(-1, v.state.instField)
        v.tap(Action.EDIT)
        assertTrue(NesSoundFace.friendly(v.state))
        assertSame(before, session.song)
    }

    @Test fun heldEditsAuditionAndGroupUndoWhileUnusedSlotsAreImmediatelyPlayable() {
        val session = EditorSession(NesSystem.blank())
        val v = view(session)
        NesSoundFace.moveSlot(v, 31)
        v.state.instField = 3 // Width, not Listen on.
        val before = session.song
        val heard = ArrayList<NesInstrument>()
        session.onAudition = { _, _, slot, _ -> heard += session.song.nesInstrument(slot)!! }
        v.edit(Action.RIGHT, Action.RIGHT)
        assertEquals(2, sound(v).duty.values.single())
        assertEquals(3, heard.size)
        assertSame(before.nesInstrument(32), heard.first())
        for (slot in 1..NesSystem.SLOTS) if (slot != 32) assertSame(before.nesInstrument(slot), session.song.nesInstrument(slot))
        session.undo()
        assertSame(before, session.song)
    }

    @Test fun heldEditCannotCrossModeSlotOrControlChanges() {
        val session = EditorSession(NesSystem.blank())
        val v = view(session)
        v.state.instField = 3
        val before = session.song
        v.press(Action.EDIT)
        NesSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR)
        v.release(Action.EDIT)
        assertSame(before, session.song)
        NesSoundFace.selectMode(v, false)
        v.state.instField = 3
        v.press(Action.EDIT)
        NesSoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertSame(before, session.song)
        v.press(Action.EDIT)
        v.tap(Action.NEXT_CHANNEL)
        v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertSame(before, session.song)
        v.state.instField = 3
        v.edit(Action.RIGHT)
        assertEquals(listOf(1), sound(v).duty.values)
    }

    @Test fun nameCursorClampsAndOnlyChangesTheName() {
        val session = EditorSession(NesSystem.blank())
        val v = view(session)
        v.state.instField = 1
        val before = sound(v)
        v.press(Action.EDIT)
        repeat(EditorSession.MAX_NAME + 4) { v.tap(Action.DOWN) }
        assertEquals(EditorSession.MAX_NAME - 1, v.state.textPos)
        repeat(EditorSession.MAX_NAME + 4) { v.tap(Action.UP) }
        assertEquals(0, v.state.textPos)
        v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertEquals(before.copy(name = NameEdit.edit(before.name, 0, 1, EditorSession.MAX_NAME)), sound(v))
    }

    @Test fun staleSamplePageDoesNotHijackTonalSoundAudition() {
        val session = EditorSession(NesMmc5System.blank())
        val v = view(session)
        val heard = ArrayList<Int>()
        session.onAudition = { ch, _, _, _ -> heard += ch }
        v.state.envelope = NesInstrumentView.DPCM_PAGE
        v.state.listenChannel = 2
        v.tap(Action.AUDITION)
        assertEquals(listOf(2), heard)
        NesSoundFace.set(v, NesSoundControl.VOICE, NesRules.MMC5_PULSE1)
        v.tap(Action.AUDITION)
        assertEquals(listOf(2, NesRules.MMC5_PULSE1), heard)
    }

    private class Canvas : Surface {
        var ops: MutableList<String>? = null
        var columnRight = 0f
        var columnTop = 0f
        private fun keep(x: Float, y: Float, op: () -> String) { if (x < columnRight - 0.01f && y >= columnTop - 0.01f) ops?.add(op()) }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = keep(l, t) { "fill $l $t $r $b $color" }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = keep(l, t) { "round $l $t $r $b $radius $color" }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = keep(l, t) { "stroke $l $t $r $b $color $width $radius" }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = keep(minOf(x0, x1), minOf(y0, y1)) { "line $x0 $y0 $x1 $y1 $color $width" }
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = keep(cx - radius, cy - radius) { "circle $cx $cy $radius $color" }
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = keep(cx - radius, cy - radius) { "ring $cx $cy $radius $color $width" }
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
            if (count > 0) keep(xs.take(count).min(), ys.take(count).min()) { "poly ${xs.take(count)} ${ys.take(count)} $color $width" }
        }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) = keep(x, top) { "text $s $x $top $h $color $face $bold $size $align" }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }

    private class Panel(val session: EditorSession, val width: Int = 960, val height: Int = 720, val density: Float = 1f,
                        zoom: PaneZoom = PaneZoom.NORMAL) {
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 2, zoom = zoom)
        val pane = PaneView(session, state, density, surface, { 0L }, {})
        val painter = NesSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise()
            painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
    }

    @Test fun compactTouchPagesEveryVoiceAtPhysicalSizeWithoutEditing() {
        for (expansion in NesExpansion.entries) {
            val session = EditorSession(expansion.system.blank())
            val panel = Panel(session, 360, 270)
            for (channel in session.song.system.channels.indices) {
                NesSoundFace.set(panel.pane.controller, NesSoundControl.VOICE, channel)
                panel.state.instField = 2
                panel.draw()
                val before = session.song
                val count = NesSoundFace.controls(before, panel.state).size
                repeat(count) { i ->
                    val r = panel.painter.controlRect(i)!!
                    assertTrue("$expansion/$channel/$i width", r.width() >= 48f)
                    assertTrue("$expansion/$channel/$i height", r.height() >= 48f)
                    assertTrue(r.bottom <= panel.area.bottom && r.right <= panel.area.right)
                    panel.tap(panel.painter.navigationRect(true)); panel.draw()
                }
                assertEquals(2, panel.state.instField)
                assertSame(before, session.song)
            }
        }
    }

    @Test fun bothFacesDrawIdenticalBankAndHeaderAndUseTheSameSlotTargets() {
        for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) for (size in listOf(360 to 270, 960 to 720)) {
            val session = EditorSession(NesSystem.blank())
            val panel = Panel(session, size.first, size.second, zoom = zoom)
            for (slot in listOf(1, 40)) {
                panel.state.instrument = slot
                fun column(advanced: Boolean): List<String> {
                    NesSoundFace.selectMode(panel.pane.controller, advanced)
                    panel.state.instField = 3
                    panel.draw()
                    panel.surface.columnRight = panel.painter.slotBounds(slot)!!.right
                    panel.surface.columnTop = panel.painter.modeRect(false).bottom
                    val ops = ArrayList<String>()
                    panel.surface.ops = ops; panel.draw(); panel.surface.ops = null
                    return ops
                }
                val sound = column(false)
                val advanced = column(true)
                assertTrue(sound.any { it.startsWith("text Sound ${EditorSession.hex2(slot)} ") })
                assertEquals(advanced, sound)
                NesSoundFace.selectMode(panel.pane.controller, false)
                panel.draw()
                panel.tap(panel.painter.slotNavigationRect(true)); panel.draw()
                assertEquals(slot + 1, panel.state.instrument)
                assertEquals(3, panel.state.instField)
            }
        }
    }

    @Test fun pointerEditsArePinnedGroupedAndCancelledByUndoModeOrSlot() {
        val session = EditorSession(NesSystem.blank())
        val panel = Panel(session)
        val before = session.song
        val r = panel.painter.controlRect(1)!!
        panel.painter.down(panel.painter.valueX(1, 0f), r.centerY())
        panel.draw()
        panel.painter.move(panel.painter.valueX(1, 1f), r.centerY())
        panel.draw()
        panel.painter.move(panel.painter.valueX(1, 0.5f), r.centerY())
        panel.painter.up(0f, 0f)
        assertEquals(listOf(2), session.song.nesInstrument(1)!!.duty.values)
        session.undo()
        assertSame(before, session.song)
        for (stop in 0..2) {
            panel.draw()
            panel.painter.down(panel.painter.valueX(1, 1f), panel.painter.controlRect(1)!!.centerY())
            when (stop) {
                0 -> session.undo()
                1 -> NesSoundFace.selectMode(panel.pane.controller, true)
                else -> NesSoundFace.moveSlot(panel.pane.controller, 1)
            }
            val stopped = session.song
            panel.painter.move(9999f, 0f); panel.painter.up(0f, 0f)
            assertSame(stopped, session.song)
            if (stop != 0) session.undo()
            assertSame(before, session.song)
            NesSoundFace.selectMode(panel.pane.controller, false)
        }
    }

    @Test fun acceptedTouchReauditionsWhileHeldDirectionsRemainGuarded() {
        val session = EditorSession(NesSystem.blank())
        val panel = Panel(session)
        val controller = panel.pane.controller
        panel.state.instField = 3
        val before = session.song
        var heard = before
        session.onAudition = { _, _, _, _ -> heard = session.song }
        controller.press(Action.EDIT)
        val r = panel.painter.controlRect(1)!!
        panel.painter.down(panel.painter.valueX(1, 0f), r.centerY())
        panel.draw()
        panel.painter.move(panel.painter.valueX(1, 1f), r.centerY())
        panel.painter.up(0f, 0f)
        val shaped = session.song
        assertEquals(listOf(3), shaped.nesInstrument(1)!!.duty.values)
        assertSame(shaped, heard)
        controller.tap(Action.LEFT)
        assertSame(shaped, session.song)
        controller.release(Action.EDIT)
        session.undo()
        assertSame(before, session.song)
    }

    @Test fun touchAndControllerProduceTheSameStoredWidth() {
        val touched = EditorSession(NesSystem.blank())
        val controlled = EditorSession(NesSystem.blank())
        val panel = Panel(touched)
        val r = panel.painter.controlRect(1)!!
        panel.painter.down(panel.painter.valueX(1, 0.5f), r.centerY()); panel.painter.up(0f, 0f)
        val v = view(controlled)
        v.state.instField = 3
        v.edit(Action.RIGHT, Action.RIGHT)
        assertEquals(controlled.song, touched.song)
    }
}
