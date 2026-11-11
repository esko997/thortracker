package org.thortracker.system.saa

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.SaaLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class SaaSoundFaceTest {
    private fun patch() = SaaInstrument(name = "Keep me",
        volume = NesEnvelope(listOf(15, 9, 6, 0), loop = 1, release = 3),
        mixer = NesEnvelope(listOf(1, 3, 2), loop = 1),
        arpeggio = NesEnvelope(listOf(0, 7, 12), loop = 0),
        pitch = NesEnvelope(listOf(-2, 4), loop = 0), pitchRelative = true,
        panLeft = 9, panRight = 11, noise = 2,
        envelope = SaaHardwareEnvelope(shape = SaaHardwareEnvelope.REPETITIVE_ATTACK,
            threeBit = true, external = false, rate = 9, retrigger = false))
    private fun session(variant: SaaVariant = SaaVariant.SAM, ins: SaaInstrument = patch()): EditorSession {
        val song = variant.system.blank()
        return EditorSession(song.copy(sound = song.saa.withInstrument(1, ins)))
    }
    private fun view(s: EditorSession, field: Int = 2, lane: Int = 2) = ViewController(s,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = field, listenChannel = lane))
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun ViewController.edit(vararg moves: Action) { press(Action.EDIT); for (a in moves) tap(a); release(Action.EDIT) }

    @Test fun bothVariantsOpenAndSwitchWithoutChangingNativeBytesOrInactiveFields() {
        for (variant in SaaVariant.entries) {
            val s = session(variant)
            val v = view(s, 0)
            val before = s.song
            val bytes = SaaLowering.lower(before)
            repeat(3) { v.normalise(); SaaSoundFace.selectMode(v, true); v.normalise(); SaaSoundFace.selectMode(v, false) }
            assertEquals(2, v.state.instField)
            assertSame(before, s.song)
            assertArrayEquals(bytes, SaaLowering.lower(s.song))
        }
    }

    @Test fun everyRecipeHasMinimalFieldOwnershipAndIdentityNoops() {
        val before = patch()
        for (c in SaaSoundControl.entries) {
            if (c == SaaSoundControl.LANE) continue
            for (n in 0..c.maximum) {
                val after = SaaSoundMacros.set(c, before, n, 2)
                assertEquals(n, SaaSoundMacros.value(c, after, 2))
                assertSame(after, SaaSoundMacros.set(c, after, n, 2))
                val restored = when (c) {
                    SaaSoundControl.SOURCE -> after.copy(mixer = before.mixer)
                    SaaSoundControl.CONTOUR -> after.copy(volume = before.volume)
                    SaaSoundControl.POSITION -> after.copy(panLeft = before.panLeft, panRight = before.panRight)
                    SaaSoundControl.GRAIN -> after.copy(noise = before.noise)
                    SaaSoundControl.MOTION -> after.copy(envelope = before.envelope)
                    SaaSoundControl.LANE -> after
                }
                assertEquals(before, restored)
            }
        }
    }

    @Test fun complexProgramsAndInternalHardwareClocksStayCustom() {
        val before = patch()
        for (c in listOf(SaaSoundControl.SOURCE, SaaSoundControl.CONTOUR, SaaSoundControl.POSITION, SaaSoundControl.MOTION))
            assertNull(SaaSoundMacros.value(c, before, 2))
        // Even a constant multi-step program is kept as a program rather than silently folded.
        val constant = before.copy(mixer = NesEnvelope(listOf(2, 2), loop = 0))
        assertNull(SaaSoundMacros.value(SaaSoundControl.SOURCE, constant, 2))
        val held = before.copy(volume = NesEnvelope(), mixer = NesEnvelope())
        assertEquals(0, SaaSoundMacros.value(SaaSoundControl.CONTOUR, held, 2))
        assertEquals(1, SaaSoundMacros.value(SaaSoundControl.SOURCE, held, 2))
        assertSame(held, SaaSoundMacros.set(SaaSoundControl.CONTOUR, held, 0, 2))
        assertSame(held, SaaSoundMacros.set(SaaSoundControl.SOURCE, held, 1, 2))
        assertEquals(patch(), before)
    }

    @Test fun grainIsOnlyActiveForNoiseAndMotionOnlyOnEachChipsThirdAndSixthLane() {
        val tone = patch().copy(mixer = NesEnvelope())
        assertFalse(SaaSoundMacros.enabled(SaaSoundControl.GRAIN, tone, 2))
        assertSame(tone, SaaSoundMacros.set(SaaSoundControl.GRAIN, tone, 3, 2))
        for (variant in SaaVariant.entries) for (lane in 0 until variant.channels) {
            val before = patch()
            val capable = lane % 3 == 2
            assertEquals(capable, SaaSoundMacros.enabled(SaaSoundControl.MOTION, before, lane))
            for (n in 1..4) {
                val after = SaaSoundMacros.set(SaaSoundControl.MOTION, before, n, lane)
                if (!capable) assertSame(before, after) else {
                    val envelope = after.envelope!!
                    assertTrue(envelope.external)
                    assertTrue(envelope.retrigger)
                    assertNull(envelope.problem())
                    assertFalse(envelope.threeBit)
                    assertEquals(n == 4, envelope.invertRight)
                    assertEquals(before, after.copy(envelope = before.envelope))
                }
            }
        }
    }

    @Test fun choicesClampAndStereoStageUsesRealAmplitudes() {
        val before = patch()
        assertEquals(before.copy(panLeft = 15, panRight = 0), SaaSoundMacros.set(SaaSoundControl.POSITION, before, -1, 2))
        assertEquals(before.copy(panLeft = 0, panRight = 15), SaaSoundMacros.set(SaaSoundControl.POSITION, before, 99, 2))
        assertEquals(before.copy(panLeft = 15, panRight = 15), SaaSoundMacros.set(SaaSoundControl.POSITION, before, 2, 2))
        assertEquals(before.copy(noise = 3), SaaSoundMacros.set(SaaSoundControl.GRAIN, before, 99, 2))
        assertSame(before, SaaSoundMacros.set(SaaSoundControl.LANE, before, 11, 2))
    }

    @Test fun bareAxesAndBoundedNameEditingKeepSlotAndPatchOwnership() {
        for (variant in SaaVariant.entries) {
            val s = session(variant)
            val v = view(s, 0)
            v.normalise()
            val before = s.song
            v.tap(Action.DOWN); assertEquals(2, v.state.instrument); assertEquals(2, v.state.instField)
            v.tap(Action.UP); assertEquals(1, v.state.instrument)
            for (field in listOf(3, 4, 5, 6, 7, -1, 1, 2)) { v.tap(Action.RIGHT); assertEquals(field, v.state.instField) }
            v.tap(Action.LEFT); assertEquals(1, v.state.instField)
            v.edit(*Array(40) { Action.DOWN }); assertEquals(EditorSession.MAX_NAME - 1, v.state.textPos)
            v.edit(*Array(40) { Action.UP }); assertEquals(0, v.state.textPos)
            assertSame(before, s.song)
            v.edit(Action.RIGHT)
            assertEquals(NameEdit.edit("Keep me", 0, 1, EditorSession.MAX_NAME), s.song.saa.instrument(1)!!.name)
            s.undo(); assertSame(before, s.song)
            v.tap(Action.LEFT); assertEquals(-1, v.state.instField)
            v.edit(Action.RIGHT); assertTrue(SaaSoundFace.panel(v).advanced)
            v.tap(Action.EDIT); assertFalse(SaaSoundFace.panel(v).advanced)
            assertSame(before, s.song)
        }
    }

    @Test fun emptySlotsAreCreatedOnlyByDeliberateEditsAndOneHoldIsOneUndo() {
        for (variant in SaaVariant.entries) {
            val s = session(variant)
            val v = view(s)
            v.state.instrument = SaaSound.SLOTS
            val before = s.song
            v.normalise(); v.tap(Action.EDIT)
            assertSame(before, s.song)
            v.edit(Action.RIGHT, Action.RIGHT)
            assertEquals(NesEnvelope(listOf(3)), s.song.saa.instrument(SaaSound.SLOTS)!!.mixer)
            s.undo(); assertSame(before, s.song)
        }
    }

    @Test fun listenLaneIncludesEveryCmsAndSamChannelWithoutInstrumentEdits() {
        for (variant in SaaVariant.entries) {
            val s = session(variant)
            val v = view(s)
            val before = s.song
            val heard = ArrayList<Int>()
            s.onAudition = { lane, _, _, _ -> heard += lane }
            for (lane in 0 until variant.channels) {
                SaaSoundFace.set(v, SaaSoundControl.LANE, lane)
                v.tap(Action.AUDITION)
            }
            assertEquals((0 until variant.channels).toList(), heard)
            assertSame(before, s.song)
            SaaSoundFace.set(v, SaaSoundControl.LANE, 999)
            assertEquals(variant.channels - 1, v.state.listenChannel)
            v.tap(Action.NEXT_CHANNEL); assertEquals(0, v.state.listenChannel)
            v.tap(Action.PREV_CHANNEL); assertEquals(variant.channels - 1, v.state.listenChannel)
        }
    }

    @Test fun modeSlotAndLaneChangesRequireAReleaseBeforeFurtherEdits() {
        val s = session()
        val v = view(s)
        val before = s.song
        v.press(Action.EDIT); SaaSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(before, s.song)
        SaaSoundFace.selectMode(v, false)
        v.press(Action.EDIT); SaaSoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
        SaaSoundFace.moveSlot(v, -1)
        v.state.instField = 6
        v.press(Action.EDIT); v.tap(Action.PREV_CHANNEL)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
        assertEquals(1, v.state.listenChannel)
        // Even a fresh edit cannot overwrite an inaudible hardware envelope on lane 2.
        v.edit(Action.RIGHT)
        assertSame(before, s.song)
    }

    private class Canvas : Surface {
        val bank = ArrayList<String>()
        private fun save(x: Float, y: Float, value: String) { if (x < 100f && y >= 85f) bank += value }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) = save(x, top, "$s/$x/$top/$h/$color/$face/$bold/$size/$align")
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = save(l, t, "$l/$t/$r/$b/$color")
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = save(l, t, "$l/$t/$r/$b/$radius/$color")
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = save(l, t, "$l/$t/$r/$b/$color/$width/$radius")
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = Unit
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
    }
    private class Panel(val session: EditorSession, val width: Int = 360, val height: Int = 270) {
        val canvas = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 2, listenChannel = 2)
        val pane = PaneView(session, state, 1f, canvas, { 0L }, {})
        val painter = SaaSoundPainter(pane)
        fun draw() { pane.ink.layout(width, height, state.zoom); pane.controller.normalise(); painter.draw(canvas, Rect(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)) }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
        init { draw() }
    }

    @Test fun compactTouchPagesEveryControlAndSharesTheExactAdvancedBank() {
        for (variant in SaaVariant.entries) {
            val s = session(variant)
            val p = Panel(s)
            val before = s.song
            assertTrue(p.painter.compact)
            repeat(SaaSoundControl.entries.size) { i ->
                p.draw()
                val r = p.painter.controlRect(i)
                assertTrue(r.width() >= 48f); assertTrue(r.height() >= 48f)
                p.tap(p.painter.navigationRect(true))
            }
            assertEquals(2, p.state.instField)
            p.tap(p.painter.nameTarget()); assertEquals(1, p.state.instField)
            p.canvas.bank.clear(); p.draw(); val bank = p.canvas.bank.toList()
            assertTrue(bank.isNotEmpty())
            p.tap(p.painter.modeRect(true)); p.canvas.bank.clear(); p.draw()
            assertEquals(bank, p.canvas.bank)
            p.tap(p.painter.modeRect(false)); p.draw()
            p.tap(p.painter.slotNavigationRect(true)); assertEquals(2, p.state.instrument)
            assertSame(before, s.song)
        }
    }

    @Test fun touchDragIsOneUndoAndModeSlotLaneOrUndoTerminatesIt() {
        val s = session()
        val p = Panel(s, 960, 720)
        val before = s.song
        fun start() { p.draw(); p.painter.down(p.painter.valueX(2, 0f), p.painter.controlRect(2).centerY()) }
        start(); p.draw(); p.painter.move(p.painter.valueX(2, 1f), 0f); p.painter.up(0f, 0f)
        assertEquals(patch().copy(panLeft = 0, panRight = 15), s.song.saa.instrument(1))
        s.undo(); assertSame(before, s.song)
        start(); s.undo(); p.painter.move(p.painter.valueX(2, 1f), 0f); p.painter.up(0f, 0f)
        assertSame(before, s.song)
        start(); SaaSoundFace.selectMode(p.pane.controller, true)
        val switched = s.song
        p.painter.move(p.painter.valueX(2, 1f), 0f); assertSame(switched, s.song)
        s.undo(); assertSame(before, s.song)
        SaaSoundFace.selectMode(p.pane.controller, false)
        start(); SaaSoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song
        p.painter.move(p.painter.valueX(2, 1f), 0f); assertSame(moved, s.song)
        s.undo(); assertSame(before, s.song)
        SaaSoundFace.moveSlot(p.pane.controller, -1)
        start(); SaaSoundFace.set(p.pane.controller, SaaSoundControl.LANE, 0)
        val lane = s.song
        p.painter.move(p.painter.valueX(2, 1f), 0f); assertSame(lane, s.song)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun unavailableMotionCannotBeTouchedAndTouchMatchesControllerNativeImage() {
        val touched = session()
        val p = Panel(touched)
        p.state.listenChannel = 0; p.state.instField = 6; p.draw()
        val before = touched.song
        p.tap(p.painter.controlRect(4))
        assertSame(before, touched.song)
        p.state.instField = 4; p.draw()
        p.painter.down(p.painter.valueX(2, 0.5f), p.painter.controlRect(2).centerY()); p.painter.up(0f, 0f)
        val controlled = session()
        val v = view(controlled, 4)
        v.edit(Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertArrayEquals(SaaLowering.lower(controlled.song), SaaLowering.lower(touched.song))
    }
    @Test fun externalSlotLockCannotRedirectHeldEdits() {
        val s = session()
        val v = view(s)
        val before = s.song
        v.press(Action.EDIT)
        SaaInstrumentView.applyLock(v.state, 2)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
    }
}
