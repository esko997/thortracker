package org.thortracker.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.HugeRules
import org.thortracker.model.Scl
import org.thortracker.model.Songs
import org.thortracker.model.TuningMap

/** Tuned songs: note entry stays on reachable degrees, bends travel with the note, Fit is explicit and undoable. */
class TuningEditorTest {
    private val quarterTones = Scl.parse("! 24-TET\n24 equal\n24\n" + (1..24).joinToString("\n") { "%.1f".format(it * 50.0) }, "24-TET")
    private val session = EditorSession(Songs.blank()).also { it.setTuning(quarterTones.copy(root = 24)) }
    private val bends = ArrayList<Pair<Int, Int>>()

    init { session.onAudition = { _, note, _, detune -> bends += note to detune } }

    private fun view(octave: Int) = ViewController(session, ViewState(kind = ViewKind.PATTERN, octave = octave))
    private fun ViewController.hold(vararg moves: Action) {
        press(Action.EDIT); for (m in moves) { press(m); release(m) }; release(Action.EDIT)
    }
    private fun cell(row: Int = 0, ch: Int = 0) = session.song.patterns[0].tracks[ch][row]

    @Test fun stepsVisitQuarterTonesWhereReachableAndWriteTheirBend() {
        val map = TuningMap(session.song.tuning!!)
        val v = view(5)
        v.press(Action.EDIT); v.release(Action.EDIT) // writes 1/1 = C-5, plain
        assertEquals(Cell(24, 1), cell())
        v.state.row = 0
        v.hold(Action.RIGHT)
        val quarter = map.pitches[map.index(24, 0) + 1]
        assertTrue("a quarter tone above C-5 needs a bend", quarter.detune > 0)
        assertEquals(Cell(quarter.note, 1, HugeRules.FX_VIBRATO, quarter.detune), cell())
        assertEquals(quarter.note to quarter.detune, bends.last())
        v.hold(Action.RIGHT) // C#-5 needs no bend: the tuning's 40y is removed
        assertEquals(Cell(25, 1), cell())
        v.hold(Action.UP) // one period up: C#-6
        assertEquals(37, cell().note)
        // Octave 3 bends cannot reach a quarter tone: stepping goes straight to the next plain semitone.
        session.setCell(0, 0, 1, Cell(0, 1))
        v.state.row = 1
        v.hold(Action.RIGHT)
        assertEquals(Cell(1, 1), cell(1))
    }

    @Test fun aBendNeverOverwritesAnotherEffect() {
        session.setCell(0, 0, 0, Cell(24, 1, HugeRules.FX_SET_VOLUME, 0x08))
        val v = view(5)
        v.hold(Action.RIGHT)
        assertEquals(Cell(24, 1, HugeRules.FX_SET_VOLUME, 0x08), cell())
        assertTrue(session.message!!.contains("40y"))
    }

    @Test fun fitMovesStrayNotesInOneUndoStepAndReportsBlockedOnes() {
        val nineteen = Scl.parse("19-TET\n19\n" + (1..19).joinToString("\n") { "%.4f".format(it * 1200.0 / 19) }, "19-TET")
        session.setTuning(nineteen.copy(root = 24))
        val map = session.tuningMap!!
        session.setCell(0, 0, 0, Cell(28, 1))                                   // E-5: not a 19-TET degree
        session.setCell(0, 1, 0, Cell(28, 2, HugeRules.FX_SET_VOLUME, 0x0F))    // same, but its effect slot is taken
        session.setCell(0, 3, 0, Cell(28, 1))                                   // Noise: never tuned
        val before = session.song
        val result = session.fitToTuning(listOf(0))
        val fitted = cell(0, 0)
        assertTrue(map.index(fitted.note, HugeRules.detuneOf(fitted)!!) >= 0)
        assertEquals(1, result.moved)
        assertEquals(1, result.blocked)
        assertEquals(Cell(28, 2, HugeRules.FX_SET_VOLUME, 0x0F), cell(0, 1))
        assertEquals(Cell(28, 1), cell(0, 3))
        session.undo()
        assertEquals(before, session.song)
        assertFalse(session.song.tuning == null)
    }

    @Test fun tuningFitMarksCellsEvenWhenOnlyTheBendChanges() {
        val map = session.tuningMap!!
        val quarter = map.pitches[map.index(24, 0) + 1]               // a bent quarter tone above C-5
        val off = Cell(quarter.note, 1, HugeRules.FX_VIBRATO, (quarter.detune + 4).coerceAtMost(15))
        session.setCell(0, 0, 0, off)                                 // right note, wrong bend
        session.setCell(0, 1, 0, Cell(28, 1, HugeRules.FX_VIBRATO, 0x07)) // E-5 bent by 7: not a degree within 5¢
        assertEquals(2, session.fitToTuning(listOf(0)).moved)
        assertEquals(off, session.fittedFrom(0, 0, 0))                 // marked although only the bend moved
        assertEquals(Cell(28, 1, HugeRules.FX_VIBRATO, 0x07), session.fittedFrom(0, 1, 0))
        // Editing the fitted cell's effect is an edit: the mark goes.
        session.setCell(0, 0, 0, cell(0, 0).copy(effect = HugeRules.FX_SET_VOLUME, param = 0x08))
        assertEquals(null, session.fittedFrom(0, 0, 0))
        assertNotNull(session.fittedFrom(0, 1, 0))
    }

    @Test fun twelveEqualTuningKeepsPlainNotesPlain() {
        session.setTuning(Scl.parse("12\n12\n" + (1..12).joinToString("\n") { "${it * 100}.0" }, "12-TET"))
        session.setCell(0, 0, 0, Cell(40, 1, HugeRules.FX_ARPEGGIO, 0x37))
        assertEquals(0, session.fitToTuning(listOf(0)).moved)
        val v = view(5)
        v.hold(Action.RIGHT)
        assertEquals(Cell(41, 1, HugeRules.FX_ARPEGGIO, 0x37), cell())
    }
}
