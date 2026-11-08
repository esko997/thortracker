package org.thortracker.system.opl3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.normaliseCell
import org.thortracker.model.Cell
import org.thortracker.model.Fx
import org.thortracker.model.Songs
import org.thortracker.system.opl3.Opl3InstrumentView.Page
import org.thortracker.system.opl3.Opl3InstrumentView.Row

/** The OPL3 editor driven through semantic controller actions: effect columns, chord text and the instrument pages. */
class Opl3EditorTest {
    private val session = EditorSession(Opl3System.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(kind: ViewKind, setup: ViewState.() -> Unit = {}) = ViewController(session, ViewState(kind = kind).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun cell(channel: Int, row: Int): Cell = session.song.patterns[0].tracks[channel][row]

    @Test
    fun oneColumnShowsUntilThePanelOrTheSongAsksForMore() {
        val v = view(ViewKind.PATTERN)
        assertEquals(1, v.shownColumns(0))
        assertEquals(4, v.fieldCount(0))
        // Pattern panel → Columns (row 4): → shows a second column on this channel only.
        v.tap(Action.SCALE)
        repeat(4) { v.tap(Action.DOWN) }
        v.tap(Action.RIGHT)
        v.tap(Action.SCALE)
        assertEquals(2, v.shownColumns(0))
        assertEquals(1, v.shownColumns(1))
        // A song whose channel 3 uses its third column shows three there automatically.
        session.setCell(0, 2, 5, Cell().withEffect(2, Opl3Rules.FX_VOLUME, 0x20))
        assertEquals(3, v.shownColumns(2))
    }

    @Test
    fun cursorWalksEveryShownColumnAndEditsEachSeparately() {
        session.step = 0
        val v = view(ViewKind.PATTERN) { effectColumns = listOf(2) }
        v.tap(Action.EDIT) // note
        repeat(4) { v.tap(Action.RIGHT) } // sound, effect 1, value 1, effect 2
        assertEquals(4, v.state.field)
        assertEquals(1, v.cursorColumn())
        v.hold(Action.EDIT, *Array(Opl3Rules.EFFECTS.indexOfFirst { it.code == Opl3Rules.FX_CHORD } + 1) { Action.RIGHT })
        assertEquals(Opl3Rules.FX_CHORD, cell(0, 0).effectCode(1))
        v.tap(Action.RIGHT) // value of column 2
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.UP, Action.UP, *Array(7) { Action.RIGHT })
        assertEquals(0x47, cell(0, 0).effectParam(1))
        assertEquals(0, cell(0, 0).effect)
        assertTrue(session.message!!.contains("E-4") && session.message!!.contains("G-4"))
        // Column 1 gets its own effect; clearing column 2 leaves it.
        repeat(3) { v.tap(Action.LEFT) }
        v.hold(Action.EDIT, *Array(Opl3Rules.EFFECTS.indexOfFirst { it.code == Opl3Rules.FX_VOLUME } + 1) { Action.RIGHT })
        assertEquals(Opl3Rules.FX_VOLUME, cell(0, 0).effect)
        repeat(2) { v.tap(Action.RIGHT) }
        v.tap(Action.CLEAR)
        assertEquals(Opl3Rules.FX_VOLUME, cell(0, 0).effect)
        assertTrue(cell(0, 0).more.isEmpty())
        // Past the last field the cursor goes to the next channel's note.
        v.tap(Action.RIGHT)
        v.tap(Action.RIGHT)
        assertEquals(1, v.state.channel)
        assertEquals(0, v.state.field)
    }

    @Test
    fun copyPasteAndPlacingOffKeepEveryColumn() {
        val two = Cell(note = 48, instrument = 1).withEffect(0, Opl3Rules.FX_VOLUME, 0x30).withEffect(3, Opl3Rules.FX_CHORD, 0x37)
        session.setCell(0, 0, 0, two)
        assertEquals(two, cell(0, 0))
        val v = view(ViewKind.PATTERN)
        v.tap(Action.COPY)
        v.tap(Action.NEXT_CHANNEL)
        v.tap(Action.PASTE)
        assertEquals(two, cell(1, 0))
        // OFF on a row with effects keeps them on a several-column system.
        session.setCell(0, 2, 1, Cell().withEffect(1, Opl3Rules.FX_PAN, 0x10))
        val w = view(ViewKind.PATTERN) { channel = 2; row = 1 }
        w.tap(Action.CLEAR)
        assertEquals(Cell.CUT, cell(2, 1).note)
        assertEquals(Opl3Rules.FX_PAN, cell(2, 1).effectCode(1))
    }

    @Test
    fun normalisingKeepsTheColumnsTheChannelHas() {
        val c = Cell(note = 40, more = listOf(Fx(0, 0), Fx(Opl3Rules.FX_VOLUME, 0x10)))
        assertEquals(c, normaliseCell(c, Opl3System, 0))
        // A one-column system drops what it cannot store.
        assertTrue(normaliseCell(c, org.thortracker.system.gb.GbSystem, 0).more.isEmpty())
    }

    @Test
    fun chordTextNamesTheNotesAndFourOpSilence() {
        session.setCell(0, 0, 0, Cell(note = 48, instrument = 2).withEffect(1, Opl3Rules.FX_CHORD, 0x47))
        val text = Opl3EffectText.detail(session.song, 0, 0, 0, 1)!!
        assertTrue(text, text.contains("C-4 + E-4 G-4"))
        val e = session.song.opl3.instruments.indexOfFirst { it?.fourOp == true } + 1
        session.setCell(0, 0, 1, Cell(note = 48, instrument = e).withEffect(0, Opl3Rules.FX_SHAPE, 1))
        assertTrue(Opl3EffectText.detail(session.song, 0, 0, 1, 0)!!.contains("4-op"))
        session.setCell(0, 0, 2, Cell().withEffect(0, Opl3Rules.FX_CHORD, 0x47))
        assertTrue(Opl3EffectText.detail(session.song, 0, 0, 2, 0)!!.contains("ignored"))
    }

    @Test
    fun curatedExpertDrumAndChordPagesEditTheSound() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 5 }
        val before = session.song.opl3Instrument(5)!!
        fun goTo(row: Row) { v.state.instField = Opl3InstrumentView.rows(v.state).indexOf(row) }
        // Preset
        goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertNotEquals(before, session.song.opl3Instrument(5))
        assertTrue(auditions.isNotEmpty())
        // Curated: algorithm to 4-op chain, ratio, brightness
        goTo(Row.ALGORITHM)
        val alg = Opl3Curated.controls(session.song.opl3Instrument(5)!!).algorithm
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((alg + 1) % Opl3Curated.ALGORITHMS.size, Opl3Curated.controls(session.song.opl3Instrument(5)!!).algorithm)
        goTo(Row.LEVEL)
        val level = Opl3Curated.controls(session.song.opl3Instrument(5)!!).level
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals((level - 2).coerceAtLeast(0), Opl3Curated.controls(session.song.opl3Instrument(5)!!).level)
        // Expert: operator 2's multiplier via R1 then Edit+→
        v.state.envelope = Page.EXPERT.ordinal
        goTo(Row.OP_MULT)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(1, v.state.envStep)
        val mult = session.song.opl3Instrument(5)!!.operators[1].mult
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((mult + 1).coerceAtMost(15), session.song.opl3Instrument(5)!!.operators[1].mult)
        goTo(Row.OP_VIB)
        val vib = session.song.opl3Instrument(5)!!.operators[1].vib
        v.tap(Action.EDIT)
        assertEquals(!vib, session.song.opl3Instrument(5)!!.operators[1].vib)
        // Drum: a sweep start gives the sweep a speed
        v.state.envelope = Page.DRUM.ordinal
        session.editOpl3Instrument(5, "test") { it.copy(sweep = Opl3Sweep.NONE) }
        goTo(Row.SWEEP_START)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(Opl3Sweep(12, 0, 16), session.song.opl3Instrument(5)!!.sweep)
        // Chords: add a shape at the end and an interval to it
        v.state.envelope = Page.CHORDS.ordinal
        val shapes = session.song.opl3.chords.size
        goTo(Row.SHAPES)
        v.state.envStep = shapes
        v.tap(Action.EDIT)
        assertEquals(shapes + 1, session.song.opl3.chords.size)
        v.state.programField = 2
        v.tap(Action.EDIT)
        assertEquals(listOf(4, 7, 10), session.song.opl3.chords.last().intervals)
        v.state.programField = 0
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(5, session.song.opl3.chords.last().intervals[0])
    }

    @Test
    fun emptySlotsAreMadeOnFirstEditAndClearedFromSlot() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 40 }
        assertNull(session.song.opl3Instrument(40))
        v.tap(Action.EDIT) // on Slot
        assertEquals("Sound 28", session.song.opl3Instrument(40)!!.name)
        v.tap(Action.CLEAR)
        assertNull(session.song.opl3Instrument(40))
        // Four-op sounds listen on FM 1 when Listen is a 2-op-only channel.
        val e = session.song.opl3.instruments.indexOfFirst { it?.fourOp == true } + 1
        val w = view(ViewKind.INSTRUMENT) { instrument = e; listenChannel = 8 }
        w.tap(Action.AUDITION)
        assertEquals(0, auditions.last().first)
    }

    @Test
    fun oneColumnSystemsKeepFourFields() {
        val gb = EditorSession(Songs.blank())
        val v = ViewController(gb, ViewState(kind = ViewKind.PATTERN))
        assertTrue(!v.multiColumn())
        assertEquals(4, v.fieldCount(0))
        repeat(5) { v.tap(Action.RIGHT) }
        assertEquals(1, v.state.channel)
        assertEquals(1, v.state.field)
    }
}
