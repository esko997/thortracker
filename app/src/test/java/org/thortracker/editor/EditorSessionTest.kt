package org.thortracker.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Instrument
import org.thortracker.model.InstrumentKind
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.HugeRules
import org.thortracker.model.SongValidator

class EditorSessionTest {
    private fun session(song: Song = Songs.blank()) = EditorSession(song)

    @Test
    fun singleEditUndoesAndRedoesToIdenticalDocuments() {
        val s = session()
        val original = s.song
        var notified = 0
        s.onSongChanged = { notified++ }
        assertTrue(s.setCell(0, 1, 5, Cell(note = 24, instrument = 2)))
        val edited = s.song
        assertEquals(Cell(24, 2, 0, 0), edited.patterns[0].tracks[1][5])
        assertSame(original.patterns[0].tracks[0], edited.patterns[0].tracks[0])

        s.undo()
        assertSame(original, s.song)
        assertTrue(s.canRedo)
        s.redo()
        assertSame(edited, s.song)
        assertEquals(3, notified)
    }

    @Test
    fun unchangedCommandsLeaveNoHistory() {
        val s = session()
        assertFalse(s.setCell(0, 0, 0, Cell()))
        assertFalse(s.canUndo)
    }

    @Test
    fun gestureIsOneUndoStep() {
        val s = session()
        val original = s.song
        val g = s.beginGesture("Draw")
        for (i in 0 until 32) s.setWaveSample(0, i, i / 2)
        assertTrue(s.canUndo)
        s.endGesture(g)
        assertEquals(List(32) { it / 2 }, s.song.waves[0])
        s.undo()
        assertSame(original, s.song)
        assertFalse(s.canUndo)
    }

    @Test
    fun undoInsideGestureCommitsItFirst() {
        val s = session()
        val original = s.song
        val g = s.beginGesture("Edit")
        s.setCell(0, 0, 0, Cell(note = 10, instrument = 1))
        s.undo()
        assertSame(original, s.song)
        assertFalse(s.inGesture)
        s.endGesture(g) // a stray end after the forced commit is harmless
        assertFalse(s.canUndo)
        assertTrue(s.canRedo)
    }

    @Test
    fun overlappingGesturesCommitOnlyWhenEveryOwnerHasEnded() {
        val s = session()
        val original = s.song
        val held = s.beginGesture("Hold A") // display 1
        s.setCell(0, 0, 0, Cell(note = 10, instrument = 1))
        val touch = s.beginGesture("Touch") // display 2 starts and ends its own group
        s.endGesture(touch)
        assertTrue(s.inGesture)
        s.setCell(0, 0, 1, Cell(note = 11, instrument = 1))
        s.endGesture(touch) // ending again, or a token it never had, cannot close the other group
        assertTrue(s.inGesture)
        s.endGesture(held)
        assertFalse(s.inGesture)
        s.undo()
        assertSame(original, s.song)
        assertFalse(s.canUndo)
    }

    @Test
    fun newEditDropsRedo() {
        val s = session()
        s.setCell(0, 0, 0, Cell(note = 1, instrument = 1))
        s.undo()
        s.setCell(0, 0, 1, Cell(note = 2, instrument = 1))
        assertFalse(s.canRedo)
    }

    @Test
    fun cellsAreNormalisedToRepresentableValues() {
        val s = session()
        s.setCell(0, 0, 0, Cell(note = Cell.EMPTY, instrument = 4, effect = 0xC, param = 0x0F))
        assertEquals(Cell(Cell.EMPTY, 0, 0xC, 0x0F), s.song.patterns[0].tracks[0][0])
        s.setCell(0, 0, 1, Cell(note = 500, instrument = 99, effect = 20, param = 999))
        assertEquals(Cell(Songs.NOTE_MAX, 15, 15, 255), s.song.patterns[0].tracks[0][1])
    }

    @Test
    fun copyPasteClipsAtPatternBounds() {
        val s = session()
        for (r in 0 until 4) for (c in 0 until 2) s.setCell(0, c, r, Cell(note = r + c * 12, instrument = 1))
        s.copyBlock(0, Block(0, 1, 0, 3))
        val clip = s.clipboard as Clip.Cells
        assertEquals(2, clip.width)
        assertEquals(4, clip.height)

        val pasted = s.pasteCells(0, 3, 62)
        assertEquals(Block(3, 3, 62, 63), pasted)
        assertEquals(Cell(0, 1, 0, 0), s.song.patterns[0].tracks[3][62])
        assertEquals(Cell(1, 1, 0, 0), s.song.patterns[0].tracks[3][63])
    }

    @Test
    fun cutClearsAndDuplicateRepeatsBelow() {
        val s = session()
        s.setCell(0, 2, 0, Cell(note = 30, instrument = 1))
        s.setCell(0, 2, 1, Cell(note = 31, instrument = 1))
        val dup = s.duplicateBlock(0, Block(2, 2, 0, 1))
        assertEquals(Block(2, 2, 2, 3), dup)
        assertEquals(30, s.song.patterns[0].tracks[2][2].note)
        assertEquals(31, s.song.patterns[0].tracks[2][3].note)
        assertNull(s.clipboard)

        s.cutBlock(0, Block(2, 2, 0, 3))
        assertTrue((0..3).all { s.song.patterns[0].tracks[2][it] == Cell() })
        assertEquals(4, (s.clipboard as Clip.Cells).height)
        assertNull(s.duplicateBlock(0, Block(0, 0, 63, 63)))
    }

    @Test
    fun insertAndDeleteRowShiftOnlyChosenChannels() {
        val s = session()
        s.setCell(0, 0, 0, Cell(note = 5, instrument = 1))
        s.setCell(0, 1, 0, Cell(note = 6, instrument = 1))
        s.insertRow(0, 0..0, 0)
        assertEquals(5, s.song.patterns[0].tracks[0][1].note)
        assertEquals(Cell(), s.song.patterns[0].tracks[0][0])
        assertEquals(6, s.song.patterns[0].tracks[1][0].note)
        assertEquals(64, s.song.patterns[0].tracks[0].size)
        s.deleteRow(0, 0..0, 0)
        assertEquals(5, s.song.patterns[0].tracks[0][0].note)
        assertEquals(64, s.song.patterns[0].tracks[0].size)
    }

    @Test
    fun orderEditingKeepsLoopTargetOnItsOrder() {
        val s = session()
        val p1 = s.newPatternAt(1)
        assertEquals(1, p1)
        assertEquals(listOf(0, 1), s.song.orders)
        s.setLoopOrder(1)
        s.insertOrder(1, 0)
        assertEquals(listOf(0, 0, 1), s.song.orders)
        assertEquals(2, s.song.loopOrder)
        s.deleteOrder(0)
        assertEquals(listOf(0, 1), s.song.orders)
        assertEquals(1, s.song.loopOrder)
        s.moveOrder(1, 0)
        assertEquals(listOf(1, 0), s.song.orders)
    }

    @Test
    fun movingAnOrderKeepsTheLoopOnTheSameOrder() {
        val s = session()
        s.newPatternAt(1)
        s.newPatternAt(2)
        assertEquals(listOf(0, 1, 2), s.song.orders)
        s.setLoopOrder(1)
        s.moveOrder(1, 0) // the loop target itself moves
        assertEquals(listOf(1, 0, 2), s.song.orders)
        assertEquals(0, s.song.loopOrder)
        s.setLoopOrder(1) // loop on pattern 0
        s.moveOrder(2, 0) // an order jumps over the loop target
        assertEquals(listOf(2, 1, 0), s.song.orders)
        assertEquals(0, s.song.orders[s.song.loopOrder])
        s.moveOrder(0, 2) // and back past it the other way
        assertEquals(listOf(1, 0, 2), s.song.orders)
        assertEquals(0, s.song.orders[s.song.loopOrder])
    }

    @Test
    fun storedJumpsFollowTheirOrderThroughInsertDeleteMoveAndClone() {
        val s = session()
        repeat(4) { s.newPatternAt(s.song.orders.size) }          // orders 0..4 play patterns 0..4
        val jump = HugeRules.FX_POSITION_JUMP
        s.setCell(4, 1, 15, Cell(Cell.EMPTY, 0, jump, 0x05))       // B05: jump to order 4 (pattern 4)
        s.setCell(4, 2, 15, Cell(Cell.EMPTY, 0, jump, 0x00))       // B00: next order, relative
        fun b() = s.song.patterns[4].tracks[1][15].param
        fun target() = s.song.orders[b() - 1]
        s.insertOrder(1, 0)                                        // shifts order 4 to 5
        assertEquals(6, b()); assertEquals(4, target())
        s.deleteOrder(2)                                           // an order before it goes
        assertEquals(5, b()); assertEquals(4, target())
        s.moveOrder(4, 0)                                          // its own order moves to the front
        assertEquals(1, b()); assertEquals(4, target())
        assertEquals(0x00, s.song.patterns[4].tracks[2][15].param) // B00 is relative and untouched
        val clone = s.clonePatternAt(0)                            // the clone jumps to the same order
        assertEquals(s.song.patterns[4].tracks[1][15], s.song.patterns[clone].tracks[1][15])
        // Deleting the jump's own target lands it on the order that takes its place.
        s.deleteOrder(0)
        assertEquals(1, b())
        assertTrue(SongValidator.errors(s.song).isEmpty())
    }

    @Test
    fun deletingOrdersNeverLeavesAPlayedJumpPastTheEnd() {
        val s = session()
        repeat(10) { s.newPatternAt(s.song.orders.size) }         // 11 orders
        s.setCell(10, 1, 15, Cell(Cell.EMPTY, 0, HugeRules.FX_POSITION_JUMP, 0x0B)) // B0B: the last order
        repeat(5) { s.deleteOrder(1) }
        assertEquals(6, s.song.orders.size)
        assertTrue(SongValidator.errors(s.song).isEmpty())
        assertEquals(10, s.song.orders[s.song.patterns[10].tracks[1][15].param - 1])
    }

    @Test
    fun parkingFollowsItsOrderThroughInsertDeleteMoveAndUndo() {
        val s = session()
        repeat(4) { s.newPatternAt(s.song.orders.size) }          // orders 0..4 play patterns 0..4
        assertTrue(s.toggleParked(3))
        fun parkedPatterns() = s.song.parkedOrders.map { s.song.orders[it] }
        assertEquals(listOf(3), parkedPatterns())
        s.insertOrder(0, 0)                                        // before it: the parking moves with its order
        assertEquals(listOf(4), s.song.parkedOrders); assertEquals(listOf(3), parkedPatterns())
        s.moveOrder(4, 1)                                          // the parked order itself moves
        assertEquals(listOf(1), s.song.parkedOrders); assertEquals(listOf(3), parkedPatterns())
        s.insertOrder(2, 1, parked = true)                         // a Repeat next to a parked order parks
        assertEquals(listOf(3, 1), parkedPatterns())
        s.deleteOrder(1)                                           // deleting a parked order drops its parking
        assertEquals(listOf(1), parkedPatterns())
        s.undo()
        assertEquals(listOf(3, 1), parkedPatterns())
        assertTrue(s.toggleParked(1))                              // unpark
        assertEquals(listOf(1), parkedPatterns())
        assertTrue(SongValidator.errors(s.song).isEmpty())
    }

    @Test
    fun theLastPlayingOrderCanBeNeitherParkedNorDeleted() {
        val s = session()
        s.newPatternAt(1)                                          // orders 0, 1
        assertTrue(s.toggleParked(0))
        val before = s.song
        assertFalse(s.toggleParked(1))
        assertFalse(s.deleteOrder(1))
        assertEquals(before, s.song)
        assertTrue(s.deleteOrder(0))                               // the parked one can go
        assertEquals(emptyList<Int>(), s.song.parkedOrders)
    }

    @Test
    fun aCloneOfAParkedOrderIsParked() {
        val s = session()
        s.newPatternAt(1)
        s.toggleParked(1)
        val clone = s.clonePatternAt(1)
        assertEquals(listOf(1, 2), s.song.parkedOrders)
        assertEquals(clone, s.song.orders[2])
    }

    @Test
    fun cloneCopiesCellsIntoAnIndependentPattern() {
        val s = session()
        s.setCell(0, 0, 0, Cell(note = 12, instrument = 1))
        val clone = s.clonePatternAt(0)
        assertEquals(1, clone)
        assertEquals(listOf(0, 1), s.song.orders)
        assertEquals(s.song.patterns[0].tracks, s.song.patterns[1].tracks)
        s.setCell(1, 0, 0, Cell(note = 13, instrument = 1))
        assertEquals(12, s.song.patterns[0].tracks[0][0].note)
    }

    @Test
    fun orderLimitsAreEnforcedWithAMessage() {
        val s = session()
        assertFalse(s.deleteOrder(0))
        assertNotNull(s.message)
        val full = s.song.copy(orders = List(EditorSession.MAX_ORDERS) { 0 })
        s.replaceSong(full)
        assertFalse(s.insertOrder(0, 0))
        assertEquals(-1, s.newPatternAt(0))
        assertEquals(EditorSession.MAX_ORDERS, s.song.orders.size)
    }

    @Test
    fun instrumentsKeepTheirBankKindAndPasteOnlyIntoTheSameKind() {
        val s = session()
        s.setInstrument(InstrumentKind.NOISE, 3, Instrument(name = "Hat", kind = InstrumentKind.PULSE, noiseWidth = 1))
        assertEquals(InstrumentKind.NOISE, s.song.noiseInstruments[2].kind)
        s.copyInstrument(InstrumentKind.NOISE, 3)
        assertFalse(s.pasteInstrument(InstrumentKind.PULSE, 1))
        assertTrue(s.pasteInstrument(InstrumentKind.NOISE, 4))
        assertEquals("Hat", s.song.noiseInstruments[3].name)
    }

    @Test
    fun waveSamplesAreClamped() {
        val s = session()
        s.setWaveSample(3, 0, 99)
        assertEquals(15, s.song.waves[3][0])
        assertFalse(s.setWave(3, listOf(1, 2, 3)))
        for (shape in listOf(WaveShapes.sine(), WaveShapes.triangle(), WaveShapes.saw(), WaveShapes.square())) {
            assertEquals(32, shape.size)
            assertTrue(shape.all { it in 0..15 })
        }
        assertEquals(WaveShapes.square(), WaveShapes.rotate(WaveShapes.rotate(WaveShapes.square(), 5), -5))
    }

    @Test
    fun replaceSongResetsHistoryAndClampsSelection() {
        val s = session()
        s.newPatternAt(1)
        s.select(Selection(order = 1, row = 40, channel = 3))
        s.replaceSong(Songs.blank())
        assertFalse(s.canUndo)
        assertEquals(0, s.selection.order)
        assertEquals(40, s.selection.row)
    }

    @Test
    fun undoRestoresWhereTheEditHappened() {
        val s = session()
        s.select(Selection(order = 0, row = 10, channel = 2))
        s.setCell(0, 2, 10, Cell(note = 3, instrument = 1))
        s.select(Selection(order = 0, row = 0, channel = 0))
        s.undo()
        assertEquals(10, s.selection.row)
        assertEquals(2, s.selection.channel)
    }

    @Test
    fun songSettingsAreClamped() {
        val s = session()
        s.setTicksPerRow(0)
        assertEquals(1, s.song.ticksPerRow)
        s.setLoopOrder(9)
        assertEquals(0, s.song.loopOrder)
        s.setTitle("A".repeat(400))
        assertEquals(EditorSession.MAX_TEXT, s.song.title.length)
    }

    @Test
    fun shorteningHidesRowsAndRowEditsStayInsideTheLength() {
        val s = session()
        s.setCell(0, 0, 3, Cell(note = 3, instrument = 1))
        s.setCell(0, 0, 40, Cell(note = 40, instrument = 1))
        assertTrue(s.setPatternLength(0, 8))
        assertEquals(8, s.song.rowCount(0))
        s.insertRow(0, 0..0, 0)
        assertEquals(3, s.song.patterns[0].tracks[0][4].note)
        assertEquals(40, s.song.patterns[0].tracks[0][40].note)
        s.deleteRow(0, 0..0, 0)
        s.deleteRow(0, 0..0, 0)
        assertEquals(3, s.song.patterns[0].tracks[0][2].note)
        assertEquals(Cell(), s.song.patterns[0].tracks[0][7])
        assertEquals(40, s.song.patterns[0].tracks[0][40].note)
        assertTrue(s.setPatternLength(0, 64))
        assertEquals(40, s.song.patterns[0].tracks[0][40].note)
        assertFalse(s.setPatternLength(0, 65)) // clamped to 64: unchanged
    }

    @Test
    fun wholePatternCopyPasteCarriesItsLengthInOneUndoStep() {
        val s = session()
        val p1 = s.newPatternAt(1)
        s.setCell(0, 1, 2, Cell(note = 5, instrument = 1))
        s.setPatternLength(0, 12)
        s.copyPattern(0)
        s.pastePattern(p1)
        assertEquals(12, s.song.patterns[p1].length)
        assertEquals(5, s.song.patterns[p1].tracks[1][2].note)
        s.undo()
        assertEquals(64, s.song.patterns[p1].length)
        assertEquals(Cell(), s.song.patterns[p1].tracks[1][2])
    }
}
