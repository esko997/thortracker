package org.thortracker.system.tia

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.TiaLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.panel.EnvelopeContour
import org.thortracker.system.pokey.AtariPanelSurface
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect

class TiaSoundFaceTest {
    private fun patch() = TiaInstrument(name = "Keep me", waveform = 13, relativePitch = true,
        volume = TiaEnvelope(listOf(12, 5, 9), loop = 1),
        arpeggio = TiaEnvelope(listOf(2, 7), loop = 0), pitch = TiaEnvelope(listOf(31, -7)))
    private fun session(region: TiaRegion = TiaRegion.NTSC, ins: TiaInstrument = patch()) =
        EditorSession(region.system.blank()).also { it.setTiaInstrument(1, ins, "Fixture") }
    private fun view(s: EditorSession, slot: Int = 1) = ViewController(s, ViewState(kind = ViewKind.INSTRUMENT, instrument = slot))
    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun musicalControlsOwnOnlyNativeFieldsForTonalAndRawSounds() {
        for (raw in listOf(false, true)) for (n in 0..3) for (choice in 0..TiaSoundMacros.maxima[n]) {
            val old = patch().copy(raw = raw)
            val next = TiaSoundMacros.apply(old, n, choice)
            assertEquals(choice, TiaSoundMacros.value(next, n))
            assertSame(next, TiaSoundMacros.apply(next, n, choice))
            assertNull(next.problem())
            val restored = when (n) {
                0 -> next.copy(waveform = old.waveform)
                1 -> next.copy(raw = old.raw)
                2 -> next.copy(volume = old.volume)
                else -> next.copy(arpeggio = old.arpeggio)
            }
            assertEquals(old, restored)
        }
    }

    @Test fun allSixteenWaveformsHaveExactReadbackAndAliasesAreNotNormalized() {
        for (wave in 0..15) {
            val old = patch().copy(waveform = wave)
            val choice = TiaSoundMacros.value(old, 0)
            assertNotNull("AUDC $wave", choice)
            assertSame(old, TiaSoundMacros.apply(old, 0, choice!!))
        }
        val programmed = patch().copy(waveforms = TiaEnvelope(listOf(4, 8), loop = 0))
        assertFalse(TiaSoundMacros.enabled(programmed, 0))
        assertNull(TiaSoundMacros.value(programmed, 0))
        assertEquals("Custom", TiaSoundMacros.text(programmed, 0))
        assertSame(programmed, TiaSoundMacros.apply(programmed, 0, 0))
        assertNull(TiaSoundMacros.value(programmed, 2))
        assertNull(TiaSoundMacros.value(programmed, 3))
        for (shape in EnvelopeContour.Shape.entries) {
            val next = TiaSoundMacros.apply(programmed, 2, shape.ordinal)
            assertEquals(shape.ordinal, TiaSoundMacros.value(next, 2))
            assertEquals(12, next.volume.values.maxOrNull())
            assertEquals(programmed, next.copy(volume = programmed.volume))
        }
        val held = patch().copy(volume = TiaEnvelope(listOf(7)))
        assertEquals(0, TiaSoundMacros.value(held, 2))
        assertSame(held, TiaSoundMacros.apply(held, 2, 0))
    }

    @Test fun dividerReadoutUsesRealFirstStepsOnBothStandardsAndNeverInventsDCPitch() {
        for (region in TiaRegion.entries) for (wave in 0..15) for (note in listOf(0, 31, 48, 63, 119)) {
            val old = patch().copy(waveform = wave)
            val expected = TiaRules.audf(region, wave, (note + 2) * TiaRules.UNITS + 31)
            assertEquals(expected, TiaSoundMacros.divider(old, region, note))
            val raw = old.copy(raw = true)
            assertEquals(if (TiaRules.period(wave) == null) null else TiaRules.rawAudf(note, 2), TiaSoundMacros.divider(raw, region, note))
            assertEquals(TiaSoundMacros.divider(raw, region, note), TiaSoundMacros.divider(raw, region, note + 32))
        }
        val programmed = patch().copy(waveforms = TiaEnvelope(listOf(8, 4)))
        assertEquals(TiaRules.audf(TiaRegion.NTSC, 8, 50 * TiaRules.UNITS + 31), TiaSoundMacros.divider(programmed, TiaRegion.NTSC, 48))
    }

    @Test fun openingDrawingAndModeSwitchesPreserveNativeImageForEveryRegionAndMode() {
        for (region in TiaRegion.entries) for (raw in listOf(false, true)) {
            val s = session(region, patch().copy(raw = raw, waveforms = TiaEnvelope(listOf(8, 4))))
            val p = Panel(s, 960, 720)
            val old = s.song; val image = TiaLowering.lower(old)
            p.draw()
            repeat(3) {
                TiaSoundFace.selectMode(p.pane.controller, true); p.draw()
                TiaSoundFace.selectMode(p.pane.controller, false); p.draw()
            }
            assertSame(old, s.song)
            assertArrayEquals(image, TiaLowering.lower(s.song))
        }
    }

    @Test fun controllerNavigatesModesNamesAndSlotsWithoutEditingUntilDeliberateAdjustment() {
        val s = session(); val v = view(s, TiaSystem.SLOTS)
        val old = s.song
        v.normalise(); assertEquals(2, v.state.instField)
        v.tap(Action.LEFT); assertEquals(1, v.state.instField)
        v.tap(Action.LEFT); assertEquals(-1, v.state.instField)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        assertSame(old, s.song)
        val heard = ArrayList<Int>()
        s.onAudition = { _, _, slot, _ -> heard += slot }
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(6, s.song.tiaInstrument(TiaSystem.SLOTS)!!.waveform)
        assertTrue(heard.isNotEmpty())
        s.undo(); assertSame(old, s.song)
        v.tap(Action.UP); assertEquals(TiaSystem.SLOTS - 1, v.state.instrument)
        assertEquals(2, v.state.instField)
        v.state.instField = 1
        v.press(Action.EDIT); repeat(80) { v.tap(Action.DOWN) }; v.release(Action.EDIT)
        assertEquals(TiaInstrumentView.NAME_MAX - 1, v.state.textPos)
    }

    @Test fun heldCharacterAndTrackingChangesRestartTheAcceptedPatch() {
        for (control in 0..1) {
            val s = session(); val v = view(s)
            v.state.instField = control + 2
            v.state.listenChannel = 1
            val heard = ArrayList<Pair<Int, TiaInstrument?>>()
            s.onAudition = { lane, _, slot, _ -> heard += lane to s.song.tiaInstrument(slot) }
            val old = s.song
            v.press(Action.EDIT)
            assertEquals(1 to old.tiaInstrument(1), heard.single())
            v.tap(Action.RIGHT)
            val changed = s.song.tiaInstrument(1)!!
            assertEquals(if (control == 0) patch().copy(waveform = 4) else patch().copy(raw = true), changed)
            assertEquals(2, heard.size)
            assertEquals(1 to changed, heard.last())
            v.release(Action.EDIT)
            s.undo()
            assertSame(old, s.song)
        }
    }

    @Test fun heldEditIsBlockedAcrossModesSlotsAndFollowUntilRelease() {
        val s = session(); val v = view(s)
        val old = s.song
        v.press(Action.EDIT); TiaSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(old, s.song)
        TiaSoundFace.selectMode(v, false)
        v.press(Action.EDIT); TiaSoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(old, s.song)
        v.press(Action.EDIT)
        TiaInstrumentView.follow(v.state, s.selection.copy(instrument = 3), s.song)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(old, s.song)
        v.state.instrument = 1
        v.press(Action.EDIT); v.tap(Action.NEXT_CHANNEL)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(old, s.song)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertTrue(s.song.tiaInstrument(1)!!.raw)
        s.undo(); assertSame(old, s.song)
    }

    @Test fun touchingAnEmptiedPreviouslyPopulatedSlotCreatesItsOwnDefault() {
        val s = session()
        val slot = TiaSystem.SLOTS
        s.setTiaInstrument(slot, patch(), "Fixture")
        val p = Panel(s, 960, 720)
        p.state.instrument = slot; p.state.instField = 2; p.draw()
        s.setTiaInstrument(slot, null, "Empty")
        p.draw()
        p.painter.down(p.face.valueX(0, 0f), p.face.controlRect(0).centerY())
        p.painter.up(0f, 0f)
        assertEquals(defaultTiaInstrument(slot), s.song.tiaInstrument(slot))
    }

    private class Panel(val session: EditorSession, val width: Int, val height: Int) {
        val surface = AtariPanelSurface()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = TiaInstrumentPainter(pane)
        val face get() = painter.soundFace
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            pane.controller.normalise()
            painter.draw(surface, Rect(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f))
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(0f, 0f) }
    }

    @Test fun pointerEditsUseOriginalPatchAndStopOnUndoModeSlotAndPatchReplacement() {
        val s = session(); val p = Panel(s, 960, 720)
        fun start() { p.state.instField = 2; p.draw(); p.painter.down(p.face.valueX(0, 1f), p.face.controlRect(0).centerY()) }
        val old = s.song
        start(); p.draw(); p.painter.move(p.face.valueX(0, 0.5f), 0f); p.painter.up(0f, 0f)
        assertEquals(7, s.song.tiaInstrument(1)!!.waveform)
        s.undo(); assertSame(old, s.song)
        start(); s.undo(); p.painter.move(999f, 0f); assertSame(old, s.song)
        start(); TiaSoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song; p.painter.move(999f, 0f); assertSame(moved, s.song)
        s.undo(); assertSame(old, s.song)
        p.state.instrument = 1; start(); TiaSoundFace.selectMode(p.pane.controller, true)
        val switched = s.song; p.painter.move(999f, 0f); assertSame(switched, s.song)
        s.undo(); assertSame(old, s.song)
        TiaSoundFace.selectMode(p.pane.controller, false); start()
        s.setTiaInstrument(1, patch().copy(raw = true), "Replacement")
        val replaced = s.song; p.painter.move(999f, 0f); assertSame(replaced, s.song)
    }

    @Test fun handheldLandscapeExposesAllFourPaddlesWithoutChangingThePatch() {
        for ((width, height) in listOf(640 to 384, 552 to 408)) {
            val s = session()
            val original = s.song
            val p = Panel(s, width, height)
            p.draw()
            val rects = (0..3).map(p.face::controlRect)
            for ((i, r) in rects.withIndex()) {
                assertTrue("$width/$height control $i", r.width() >= 48f && r.height() >= 48f)
                assertTrue(r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height)
                for (other in rects.take(i))
                    assertTrue(r.right <= other.left || r.left >= other.right || r.bottom <= other.top || r.top >= other.bottom)
            }
            assertSame(original, s.song)
        }
    }

    @Test fun compactTouchPagesEveryControlAndUnavailableCharacterIsReadOnly() {
        val s = session(); val old = s.song; val p = Panel(s, 360, 270)
        for (n in 0..3) {
            p.draw(); assertEquals(n + 2, p.state.instField)
            val r = p.face.controlRect(n)
            assertTrue("control $n height ${r.height()}", r.height() >= 48f)
            assertTrue(r.width() >= 48f)
            val next = p.face.navigationRect(true)
            assertTrue(next.width() >= 48f && next.height() >= 48f)
            p.tap(next)
        }
        p.draw(); p.tap(p.face.slotNavigationRect(true)); assertEquals(2, p.state.instrument)
        p.draw(); p.tap(p.face.modeRect(true)); assertTrue(TiaSoundFace.panel(p.state).advanced)
        p.draw(); p.tap(p.face.modeRect(false)); assertFalse(TiaSoundFace.panel(p.state).advanced)
        assertSame(old, s.song)
        p.state.instrument = 1; p.state.instField = 2
        s.setTiaInstrument(1, patch().copy(waveforms = TiaEnvelope(listOf(8, 4))), "Custom program")
        val custom = s.song
        p.draw(); p.tap(p.face.controlRect(0))
        p.pane.controller.press(Action.EDIT); p.pane.controller.tap(Action.RIGHT); p.pane.controller.release(Action.EDIT)
        assertSame(custom, s.song)
    }
}
