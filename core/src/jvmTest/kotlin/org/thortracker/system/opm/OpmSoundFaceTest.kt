package org.thortracker.system.opm

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState

class OpmSoundFaceTest {
    private fun patch(algorithm: Int) = OpmInstrument(name = "Workstation", algorithm = algorithm, feedback = 3, ams = 2, pms = 5,
        operators = List(4) { i -> OpmOperator(mul = i + 2, tl = 30 + i * 9, dt1 = i, dt2 = i,
            ar = 17 + i, d1r = 3 + i, d2r = 4 + i, d1l = 5 + i, rr = 6 + i) },
        noiseFrequency = 21, sweep = OpmSweep(5, -2, 7))

    @Test fun everyAlgorithmPreservesCarrierTuningAndOtherOperatorFields() {
        for (algorithm in 0..7) for (control in 0..5) {
            val before = patch(algorithm)
            val after = OpmSoundMacros.set(before, control, OpmSoundMacros.maxima[control])
            val restored = when (control) {
                3 -> after.copy(feedback = before.feedback)
                4 -> after.copy(noise = before.noise, noiseFrequency = before.noiseFrequency)
                5 -> after.copy(sweep = before.sweep)
                else -> after.copy(operators = after.operators.mapIndexed { i, op ->
                    val old = before.operators[i]
                    if ((control == 2) != before.isCarrier(i)) { assertSame(old, op); op }
                    else when (control) {
                        0 -> op.copy(tl = old.tl)
                        1 -> op.copy(dt2 = old.dt2)
                        else -> op.copy(ar = old.ar, d1r = old.d1r, d2r = old.d2r, d1l = old.d1l, rr = old.rr)
                    }
                })
            }
            assertEquals(before, restored)
            OpmSoundMacros.value(after, control)?.let { assertSame(after, OpmSoundMacros.set(after, control, it)) }
        }
    }

    @Test fun customFieldsAreNotGuessedAndDisablingNoiseKeepsItsInactiveFrequency() {
        val before = patch(0)
        assertNull(OpmSoundMacros.value(before, 1))
        assertNull(OpmSoundMacros.value(before, 2))
        assertNull(OpmSoundMacros.value(before, 5))
        val noise = before.copy(noise = true)
        assertEquals(before, OpmSoundMacros.set(noise, 4, 0))
        assertSame(before, OpmSoundMacros.set(before, 4, 0))
        val brighter = OpmSoundMacros.set(before, 0, OpmSoundMacros.value(before, 0)!! + 4)
        for (i in 0..3) assertEquals(if (before.isCarrier(i)) before.operators[i].tl else before.operators[i].tl - 4, brighter.operators[i].tl)
        val additive = patch(7)
        for (control in 0..1) {
            assertFalse(OpmSoundMacros.enabled(additive, control))
            assertSame(additive, OpmSoundMacros.set(additive, control, 1))
        }
    }

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun soundEditsAndModeChangesPreserveSongLfoAndAuditionNoiseOnChannelEight() {
        val blank = OpmSystem.blank()
        val session = EditorSession(blank.copy(sound = blank.opm.copy(lfoRate = 123, lfoWave = 2, amd = 73, pmd = 65, csm = true, timerA = 321)))
        session.setOpmInstrument(1, patch(0), "Fixture")
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 6))
        val before = session.song
        var channel = -1
        session.onAudition = { ch, _, _, _ -> channel = ch }
        OpmSoundFace.selectMode(v, true); v.normalise(); OpmSoundFace.selectMode(v, false)
        assertSame(before, session.song)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertTrue(session.song.opmInstrument(1)!!.noise)
        assertEquals(OpmRules.NOISE_CHANNEL, channel)
        assertEquals(before, session.song.copy(sound = session.song.opm.withInstrument(1, before.opmInstrument(1))))
        session.undo(); assertSame(before, session.song)
        v.state.instField = 2
        v.press(Action.EDIT)
        OpmSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, session.song)
    }
}
