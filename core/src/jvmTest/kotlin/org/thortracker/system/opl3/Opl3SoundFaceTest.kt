package org.thortracker.system.opl3

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState

class Opl3SoundFaceTest {
    private fun patch(four: Boolean, algorithm: Int) = Opl3Instrument(name = "Registration", fourOp = four, algorithm = algorithm,
        feedback = 5, operators = List(4) { i -> Opl3Operator(mult = i + 2, tl = 20 + i * 7, ar = 9 + i,
            dr = 3 + i, sl = 2 + i, rr = 4 + i, wave = i, ksl = i, am = true, vib = true, ksr = true) },
        sweep = Opl3Sweep(7, -3, 19))

    @Test fun allRoutingsUseCarrierRolesAndPreserveDormantOperatorsAndRatios() {
        for (four in listOf(false, true)) for (algorithm in 0 until if (four) 4 else 2) {
            val before = patch(four, algorithm)
            for (control in 0..5) {
                val after = Opl3SoundMacros.set(before, control, Opl3SoundMacros.maxima[control])
                val restored = when (control) {
                    0 -> after.copy(fourOp = before.fourOp, algorithm = before.algorithm)
                    5 -> after.copy(sweep = before.sweep)
                    else -> after.copy(operators = after.operators.mapIndexed { i, op ->
                        val old = before.operators[i]
                        if (i >= before.operatorCount || (control == 1) == before.isCarrier(i)) {
                            assertSame(old, op); op
                        } else when (control) {
                            1 -> op.copy(tl = old.tl)
                            2 -> op.copy(wave = old.wave)
                            3 -> op.copy(ar = old.ar, dr = old.dr, sl = old.sl, sustain = old.sustain)
                            else -> op.copy(rr = old.rr)
                        }
                    })
                }
                assertEquals(before, restored)
                Opl3SoundMacros.value(after, control)?.let { assertSame(after, Opl3SoundMacros.set(after, control, it)) }
            }
        }
    }

    @Test fun drawbarMovesRelativeModulatorLevelsAndAdditiveLeavesItUnavailable() {
        val before = patch(true, 0)
        val level = Opl3SoundMacros.value(before, 1)!!
        val after = Opl3SoundMacros.set(before, 1, level + 3)
        for (i in 0..3) assertEquals(if (before.isCarrier(i)) before.operators[i].tl else before.operators[i].tl - 3, after.operators[i].tl)
        val additive = patch(false, 1)
        assertFalse(Opl3SoundMacros.enabled(additive, 1))
        assertSame(additive, Opl3SoundMacros.set(additive, 1, 63))
        assertNull(Opl3SoundMacros.value(before, 3))
        assertNull(Opl3SoundMacros.value(before, 5))
        for (control in 0..5) {
            assertEquals(Opl3SoundMacros.set(before, control, 0), Opl3SoundMacros.set(before, control, -20))
            assertEquals(Opl3SoundMacros.set(before, control, Opl3SoundMacros.maxima[control]), Opl3SoundMacros.set(before, control, 500))
        }
    }

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun freshOrganNavigationEmptyCreationAndHoldUndoUseTheNativePatch() {
        val session = EditorSession(Opl3System.blank())
        session.setOpl3Instrument(60, null, "Empty")
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 60))
        val before = session.song
        v.normalise()
        assertEquals(2, v.state.instField)
        repeat(2) { Opl3SoundFace.selectMode(v, true); v.normalise(); Opl3SoundFace.selectMode(v, false) }
        assertSame(before, session.song)
        v.tap(Action.RIGHT)
        assertEquals(3, v.state.instField)
        v.tap(Action.UP); assertEquals(59, v.state.instrument)
        v.tap(Action.DOWN); assertEquals(60, v.state.instrument)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertNotNull(session.song.opl3Instrument(60))
        session.undo(); assertSame(before, session.song)
    }

    @Test fun soundClipboardDoesNotAccidentallyCopyTheParkedChordPage() {
        val session = EditorSession(Opl3System.blank())
        val source = patch(true, 2)
        session.setOpl3Instrument(50, source, "Fixture")
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 50, envelope = Opl3InstrumentView.Page.CHORDS.ordinal))
        v.tap(Action.COPY); v.tap(Action.DOWN); v.tap(Action.PASTE)
        assertEquals(source, session.song.opl3Instrument(51))
    }
}
