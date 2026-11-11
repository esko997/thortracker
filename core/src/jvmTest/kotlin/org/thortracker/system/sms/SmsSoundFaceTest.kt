package org.thortracker.system.sms

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.ay.SoundFaceCanvas
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect

class SmsSoundFaceTest {
    private fun psg() = SmsPsgInstrument(name = "Keep PSG", volume = NesEnvelope(listOf(12, 9, 10, 0)),
        arpeggio = NesEnvelope(listOf(0, 3, 8), loop = 1), noise = NesEnvelope(listOf(0, 1), loop = 0))
    private fun fm() = SmsFmInstrument(name = "Keep FM", patch = 0, custom = OpllPatch(
        modulator = OpllOperator(am = true, vibrato = true, ksr = true, mult = 4, ksl = 2, ar = 12, dr = 5, sl = 3, rr = 9, rectified = true),
        carrier = OpllOperator(am = true, vibrato = true, mult = 3, ksl = 1, ar = 13, dr = 3, sl = 4, rr = 8), level = 21, feedback = 3),
        volume = NesEnvelope(listOf(12, 9, 6), loop = 1), arpeggio = NesEnvelope(listOf(0, 7), loop = 0))
    private fun kit() = SmsDrumKit(name = "Keep kit", fixed = true, kick = 30, snareHat = 42, tomCymbal = 50)
    private fun session(system: SmsSystem = SmsSystem.FM_DRUMS, ins: SmsInstrument = fm()) = EditorSession(system.blank()).also {
        it.setSmsInstrument(1, ins, "Fixture")
    }
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun view(s: EditorSession) = ViewController(s, ViewState(kind = ViewKind.INSTRUMENT, instrument = 1))

    @Test fun psgRecipesOwnOnlyVolumeArpeggioOrNoiseAndNeverDiscardPitch() {
        val before = psg()
        for (c in SmsSoundMacros.PSG) {
            assertNull(SmsSoundMacros.value(c, before))
            for (n in 0..c.maximum) {
                val after = SmsSoundMacros.apply(c, before, n) as SmsPsgInstrument
                assertNull(after.problem())
                assertEquals(n, SmsSoundMacros.value(c, after))
                assertSame(after, SmsSoundMacros.apply(c, after, n))
                assertEquals(before, when (c) {
                    SmsSoundControl.TOUCH -> after.copy(volume = before.volume)
                    SmsSoundControl.CHORD -> after.copy(arpeggio = before.arpeggio)
                    else -> after.copy(noise = before.noise)
                })
            }
        }
        val pitched = before.copy(noise = NesEnvelope(), pitch = NesEnvelope(listOf(3, -2)))
        assertFalse(SmsSoundMacros.enabled(SmsSoundControl.GRAIN, pitched))
        assertSame(pitched, SmsSoundMacros.apply(SmsSoundControl.GRAIN, pitched, 4))
        val quiet = before.copy(volume = EnvelopeContour.make(EnvelopeContour.Shape.PLUCK, 8, 9))
        assertSame(quiet, SmsSoundMacros.apply(SmsSoundControl.TOUCH, quiet, 1))
        assertEquals(9, (SmsSoundMacros.apply(SmsSoundControl.TOUCH, quiet, 2) as SmsPsgInstrument).volume.values.maxOrNull())
    }

    @Test fun opllControlsPreserveStoredCustomVoiceAndEveryUnownedRegister() {
        val before = fm()
        assertNull(SmsSoundMacros.value(SmsSoundControl.HARMONICS, before))
        assertNull(SmsSoundMacros.value(SmsSoundControl.CONTOUR, before))
        for (c in SmsSoundMacros.FM) for (n in 0..c.maximum) {
            val after = SmsSoundMacros.apply(c, before, n) as SmsFmInstrument
            assertNull(after.problem())
            assertEquals(n, SmsSoundMacros.value(c, after))
            assertSame(after, SmsSoundMacros.apply(c, after, n))
            val a = after.custom
            val b = before.custom
            assertEquals(before, when (c) {
                SmsSoundControl.VOICE -> after.copy(patch = before.patch)
                SmsSoundControl.BRIGHTNESS -> after.copy(custom = a.copy(level = b.level))
                SmsSoundControl.HARMONICS -> after.copy(custom = a.copy(modulator = a.modulator.copy(mult = b.modulator.mult)))
                SmsSoundControl.EDGE -> after.copy(custom = a.copy(feedback = b.feedback))
                else -> after.copy(custom = a.copy(carrier = a.carrier.copy(ar = b.carrier.ar, dr = b.carrier.dr, sl = b.carrier.sl, rr = b.carrier.rr, sustained = b.carrier.sustained)))
            })
        }
        for (voice in 1..15) {
            val builtin = SmsSoundMacros.apply(SmsSoundControl.VOICE, before, voice) as SmsFmInstrument
            for (c in SmsSoundMacros.FM.drop(1)) {
                assertFalse(SmsSoundMacros.enabled(c, builtin))
                assertSame(builtin, SmsSoundMacros.apply(c, builtin, 0))
            }
            assertEquals(before, SmsSoundMacros.apply(SmsSoundControl.VOICE, builtin, 0))
        }
        assertEquals(63, (SmsSoundMacros.apply(SmsSoundControl.BRIGHTNESS, before, -100) as SmsFmInstrument).custom.level)
        assertEquals(0, (SmsSoundMacros.apply(SmsSoundControl.BRIGHTNESS, before, 100) as SmsFmInstrument).custom.level)
    }

    @Test fun rhythmOwnsOnlyModeOrOneSharedPitchAndRetainsInactivePitches() {
        val before = kit()
        for (c in SmsSoundMacros.KIT) for (n in 0..c.maximum) {
            val after = SmsSoundMacros.apply(c, before, n) as SmsDrumKit
            assertNull(after.problem())
            assertEquals(n, SmsSoundMacros.value(c, after))
            assertSame(after, SmsSoundMacros.apply(c, after, n))
            assertEquals(before, when (c) {
                SmsSoundControl.TRACKING -> after.copy(fixed = before.fixed)
                SmsSoundControl.KICK -> after.copy(kick = before.kick)
                SmsSoundControl.SNARE -> after.copy(snareHat = before.snareHat)
                else -> after.copy(tomCymbal = before.tomCymbal)
            })
        }
        val notes = SmsSoundMacros.apply(SmsSoundControl.TRACKING, before, 0) as SmsDrumKit
        for (c in SmsSoundMacros.KIT.drop(1)) assertSame(notes, SmsSoundMacros.apply(c, notes, 0))
        assertEquals(before, SmsSoundMacros.apply(SmsSoundControl.TRACKING, notes, 1))
    }

    @Test fun everyLayoutAndKindPreservesPatchOnOpenAndModeSwitches() {
        for (system in listOf(SmsSystem.PSG, SmsSystem.GG, SmsSystem.FM, SmsSystem.FM_DRUMS)) {
            val instruments = listOf(psg(), fm(), kit()).filter { it.kind in system.blank().sms.layout.kinds }
            for (ins in instruments) {
                val s = session(system, ins)
                val v = view(s)
                val before = s.song
                v.normalise(); assertEquals(2, v.state.instField)
                repeat(3) { SmsSoundFace.selectMode(v, true); v.normalise(); SmsSoundFace.selectMode(v, false); v.normalise() }
                v.tap(Action.RIGHT); assertEquals(3, v.state.instField)
                v.tap(Action.DOWN); assertEquals(2, v.state.instrument)
                v.tap(Action.UP); assertEquals(1, v.state.instrument)
                v.state.instField = 1
                v.press(Action.EDIT)
                repeat(80) { v.tap(Action.DOWN) }
                assertEquals(SmsInstrumentView.NAME_MAX - 1, v.state.textPos)
                repeat(80) { v.tap(Action.UP) }
                assertEquals(0, v.state.textPos)
                v.release(Action.EDIT)
                assertSame(before, s.song)
            }
        }
    }

    @Test fun controllerGroupsEditsAndBlocksHeldModeAndSlotCrossing() {
        val s = session()
        val v = view(s)
        v.state.instField = 3
        val before = s.song
        val heard = mutableListOf<Int>()
        s.onAudition = { ch, _, _, _ -> heard += ch }
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(fm().custom.level - 2, (s.song.smsInstrument(1) as SmsFmInstrument).custom.level)
        assertTrue(heard.size >= 3)
        assertTrue(heard.all { it == SmsLayout.FIRST_FM })
        s.undo(); assertSame(before, s.song)
        v.press(Action.EDIT); SmsSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(before, s.song)
        SmsSoundFace.selectMode(v, false)
        v.press(Action.EDIT); SmsSoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
    }

    @Test fun emptySlotsSelectEveryNativeKindThroughListenWithoutCreatingUntilEdited() {
        val s = session()
        val v = view(s)
        v.state.instrument = 60; v.state.listenChannel = 0
        for (target in listOf(0, SmsLayout.FIRST_FM, SmsLayout.FIRST_FM + s.song.sms.layout.fmChannels)) {
            s.setSmsInstrument(60, null, "Empty fixture")
            v.state.listenChannel = 0
            repeat(target) { v.tap(Action.NEXT_CHANNEL) }
            assertNull(s.song.smsInstrument(60))
            assertEquals(target, v.state.listenChannel)
            val kind = s.song.sms.layout.instrumentKind(target)
            v.state.instField = 2
            v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
            assertEquals(kind, s.song.smsInstrument(60)?.kind)
            assertNull(s.song.smsInstrument(60)!!.problem())
        }
    }

    private class Panel(val s: EditorSession, val width: Int = 360, val height: Int = 270) {
        val canvas = SoundFaceCanvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1)
        val pane = PaneView(s, state, 1f, canvas, { 0L }, {})
        val painter = SmsSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise(); painter.draw(canvas, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(0f, 0f) }
    }

    @Test fun wideShortPanesKeepTheWholeCartridgeRackVisible() {
        for (ins in listOf(psg(), fm(), kit())) {
            val s = session(ins = ins)
            val before = s.song
            val p = Panel(s, 720, 384)
            assertFalse("Wide ${ins.kind} must not become a single empty page", p.painter.compact)
            val list = SmsSoundMacros.controls(ins.kind)
            for (i in list.indices) {
                val r = p.painter.controlRect(i)!!
                assertTrue(r.width() >= 48f && r.height() >= 48f)
                for (j in 0 until i) {
                    val other = p.painter.controlRect(j)!!
                    assertTrue(r.left >= other.right || r.right <= other.left ||
                        r.top >= other.bottom || r.bottom <= other.top)
                }
            }
            assertSame(before, s.song)
        }
    }

    @Test fun compactTouchPagesAllKindsAndSharesTheExactAdvancedSidebar() {
        for (ins in listOf(psg(), fm(), kit())) for (size in listOf(360 to 270, 960 to 720)) {
            val s = session(ins = ins)
            val before = s.song
            val p = Panel(s, size.first, size.second)
            val count = SmsSoundMacros.controls(ins.kind).size
            for (i in 0 until count) {
                p.state.instField = i + 2; p.draw()
                val r = p.painter.controlRect(i)!!
                assertTrue("${ins.kind} control $i is touch sized", r.width() >= 48f && r.height() >= 48f)
            }
            if (p.painter.compact) {
                p.state.instField = 2; p.draw()
                repeat(count) { i ->
                    assertNotNull(p.painter.controlRect(i))
                    p.tap(p.painter.navigationRect(true)!!); p.draw()
                    assertEquals((i + 1) % count + 2, p.state.instField)
                }
            }
            p.tap(p.painter.slotNavigationRect(true)); p.draw(); assertEquals(2, p.state.instrument)
            p.tap(p.painter.slotNavigationRect(false)); p.draw()
            val slot = p.painter.slotRect(1)!!
            p.canvas.columnRight = slot.right; p.canvas.columnTop = slot.top
            p.canvas.ops.clear(); p.draw(); val sound = p.canvas.ops.toList()
            p.tap(p.painter.modeRect(true)); p.canvas.ops.clear(); p.draw()
            assertEquals(sound, p.canvas.ops)
            p.tap(p.painter.modeRect(false)); p.draw()
            assertSame(before, s.song)
        }
    }

    @Test fun dragIsOneUndoAndStaleMovesCannotEditAfterUndoModeOrSlotChanges() {
        val s = session()
        val p = Panel(s)
        val before = s.song
        fun start() { p.state.instField = 3; p.draw(); val r = p.painter.controlRect(1)!!; p.painter.down(r.left + 1f, r.centerY()) }
        start(); p.painter.move(999f, 0f); p.painter.up(0f, 0f)
        assertEquals(0, (s.song.smsInstrument(1) as SmsFmInstrument).custom.level)
        s.undo(); assertSame(before, s.song)
        start(); s.undo(); p.painter.move(999f, 0f); p.painter.up(0f, 0f)
        assertSame(before, s.song)
        start(); SmsSoundFace.selectMode(p.pane.controller, true)
        val switched = s.song
        p.painter.move(999f, 0f); assertSame(switched, s.song)
        s.undo(); assertSame(before, s.song)
        SmsSoundFace.selectMode(p.pane.controller, false)
        start(); SmsSoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song
        p.painter.move(999f, 0f); assertSame(moved, s.song)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun builtinsPitchMacrosAndNoteTrackingAreReadOnlyByTouch() {
        for ((ins, index) in listOf(
            fm().copy(patch = 3) to 1,
            psg().copy(noise = NesEnvelope(), pitch = NesEnvelope(listOf(2, -1))) to 2,
            kit().copy(fixed = false) to 1,
        )) {
            val s = session(ins = ins)
            val p = Panel(s)
            p.state.instField = index + 2; p.draw()
            val before = s.song
            p.tap(p.painter.controlRect(index)!!)
            p.pane.controller.press(Action.EDIT)
            p.pane.controller.tap(Action.RIGHT)
            p.pane.controller.release(Action.EDIT)
            assertSame(before, s.song)
        }
    }

    @Test fun heldBrightnessEditCannotCrossPasteCutOrDuplicateIntoAnotherPatch() {
        for (action in listOf(Action.PASTE, Action.CUT, Action.DUPLICATE)) {
            val s = session()
            s.setSmsInstrument(2, psg(), "Replacement")
            s.copySmsInstrument(2)
            val v = view(s)
            v.state.instField = 3 // FM Brightness becomes PSG Chord after paste.
            v.press(Action.EDIT)
            v.tap(action)
            val replaced = s.song
            if (action == Action.PASTE) assertEquals(psg(), s.song.smsInstrument(1))
            if (action == Action.CUT) assertNull(s.song.smsInstrument(1))
            if (action == Action.DUPLICATE) assertEquals(2, v.state.instrument)
            v.tap(Action.RIGHT); v.tap(Action.CLEAR)
            assertSame(replaced, s.song)
            v.release(Action.EDIT)
            v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
            assertNotSame(replaced, s.song)
        }
    }

    @Test fun locksAndSelectionFollowBlockHeldEditsAcrossKindsButAllowFreshEdits() {
        for (follow in listOf(false, true)) for (held in listOf(false, true)) {
            val s = session()
            s.setSmsInstrument(2, psg(), "Second target")
            val v = view(s)
            v.state.instField = 3
            if (held) v.press(Action.EDIT)
            if (follow) SmsInstrumentView.follow(v.state, s.selection.copy(instrument = 2, channel = 0), s.song)
            else SmsInstrumentView.applyLock(v.state, 2)
            assertEquals(2, v.state.instrument)
            val transitioned = s.song
            if (held) {
                v.tap(Action.RIGHT); v.tap(Action.CLEAR)
                assertSame(transitioned, s.song)
                v.release(Action.EDIT)
            } else {
                v.state.instField = 1
                v.tap(Action.CLEAR)
                assertNotSame(transitioned, s.song)
                v.state.instField = 3
            }
            val beforeEdit = s.song
            v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
            assertNotSame(beforeEdit, s.song)
        }
    }
}
