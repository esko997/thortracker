package org.thortracker.editor

import org.thortracker.system.gb.gb
import org.thortracker.system.gb.HugeRules
import org.thortracker.system.gb.GbSoundView
import org.thortracker.system.gb.bank
import org.thortracker.system.gb.GbValidator
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.gb.WaveShapes
import org.thortracker.system.gb.setInstrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.system.gb.GbBank
import org.thortracker.model.Songs
import org.thortracker.model.Scl
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind

/** Drives views only through semantic controller actions, as a gamepad would. */
class ViewControllerTest {
    private val session = EditorSession(Songs.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()
    private var releases = 0
    private var transports = 0
    private val bends = ArrayList<Int>()

    init {
        session.onAudition = { ch, note, ins, detune -> auditions += Triple(ch, note, ins); bends += detune }
        session.onReleaseAudition = { releases++ }
        session.onTransport = { transports++ }
    }

    private fun view(kind: ViewKind, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = kind).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun cell(order: Int, channel: Int, row: Int): Cell {
        val s = session.song
        return s.patterns[s.orders[order]].tracks[channel][row]
    }

    @Test
    fun patternPanelSetsLengthAndKeepsTheCursorOnPlayedRows() {
        val v = view(ViewKind.PATTERN) { row = 40 }
        v.tap(Action.SCALE)
        v.tap(Action.DOWN)
        v.tap(Action.DOWN)
        repeat(4) { v.tap(Action.PREV_CHANNEL) }
        v.tap(Action.LEFT)
        assertEquals(31, session.song.patterns[0].length)
        assertEquals(30, v.state.row)
        v.tap(Action.CLEAR)
        assertEquals(64, session.song.patterns[0].length)
    }

    @Test
    fun patternPanelFitsPlayedTonalNotesToTheNearestScaleNoteAsOneUndo() {
        session.setPatternScale(0, Scale(0, ScaleKind.MINOR_PENTATONIC)) // C Eb F G Bb
        session.setPatternLength(0, 16)
        session.setCell(0, 0, 0, Cell(note = 61, instrument = 1))                 // C#: C is 1 away, Eb 2
        session.setCell(0, 1, 1, Cell(note = 62, instrument = 2, effect = 0xC, param = 0x08)) // D: Eb is closer
        session.setCell(0, 2, 2, Cell(note = 68, instrument = 0))                 // G#: G and Bb, G closer
        session.setCell(0, 3, 3, Cell(note = 61, instrument = 1))                 // Noise keeps its pitch
        session.setCell(0, 0, 20, Cell(note = 61, instrument = 1))                // hidden row is left alone
        session.setCell(0, 0, 4, Cell(note = 60, instrument = 1))                 // already in the scale
        val before = session.song
        val v = view(ViewKind.PATTERN)
        v.tap(Action.SCALE)
        repeat(3) { v.tap(Action.DOWN) }
        assertEquals(ViewController.SCALE_FIT_FIELD, v.state.scaleField)
        v.tap(Action.EDIT)
        assertTrue("Fit keeps the panel open", v.state.scalePanel)
        assertEquals(Cell(note = 60, instrument = 1), cell(0, 0, 0))
        assertEquals(Cell(note = 63, instrument = 2, effect = 0xC, param = 0x08), cell(0, 1, 1))
        assertEquals(67, cell(0, 2, 2).note)
        assertEquals(61, cell(0, 3, 3).note)
        assertEquals(61, cell(0, 0, 20).note)
        assertEquals(60, cell(0, 0, 4).note)
        session.undo()
        assertSame(before, session.song)
    }

    @Test
    fun fitDoesNothingForChromaticOrTunedPatterns() {
        session.setCell(0, 0, 0, Cell(note = 61, instrument = 1))
        assertEquals(0, session.fitToScale(0))
        session.setPatternScale(0, Scale(0, ScaleKind.MAJOR))
        session.setTuning(Scl.parse("19-TET\n19\n" + (1..19).joinToString("\n") { "%.4f".format(it * 1200.0 / 19) }, "19-TET"))
        assertEquals(0, session.fitToScale(0))
        assertEquals(61, cell(0, 0, 0).note)
    }

    @Test
    fun octaveChangeWhileListeningToASoundReSoundsItAnOctaveAway() {
        val v = view(ViewKind.INSTRUMENT) { bank = 0; instrument = 1 }
        v.press(Action.AUDITION)
        val first = auditions.last().second
        v.tap(Action.OCTAVE_UP)
        assertEquals(first + 12, auditions.last().second)
        v.tap(Action.OCTAVE_DOWN)
        v.tap(Action.OCTAVE_DOWN)
        assertEquals(first - 12, auditions.last().second)
        v.release(Action.AUDITION)
        val count = auditions.size
        v.tap(Action.OCTAVE_UP) // not listening: only the octave changes
        assertEquals(count, auditions.size)
    }

    @Test
    fun fitMarksShowTheOriginalNoteAcrossChainedFitsAndClearOnEdit() {
        session.setCell(0, 0, 0, Cell(note = 61, instrument = 1))   // C#
        session.setCell(0, 1, 2, Cell(note = 62, instrument = 1))   // D
        session.setPatternScale(0, Scale(0, ScaleKind.MINOR_PENTATONIC)) // C Eb F G Bb
        assertEquals(2, session.fitToScale(0))                       // C# -> C, D -> Eb
        assertEquals(Cell(note = 61, instrument = 1), session.fittedFrom(0, 0, 0))
        assertEquals(62, session.fittedFrom(0, 1, 2)?.note)
        assertNull(session.fittedFrom(0, 2, 0))
        // Changing the scale keeps the marks; a second Fit chains from the original notes (the drift is visible).
        session.setPatternScale(0, Scale(1, ScaleKind.MAJOR))        // C# major: C# D# F F# G# A# C
        session.fitToScale(0)                                        // Eb stays; C (24) is in C# major
        assertEquals(61, session.fittedFrom(0, 0, 0)?.note)
        assertEquals(62, session.fittedFrom(0, 1, 2)?.note)
        // Editing another cell keeps the marks; editing a marked note unmarks only that note.
        session.setCell(0, 3, 10, Cell(note = 66, instrument = 1))
        assertEquals(61, session.fittedFrom(0, 0, 0)?.note)
        session.setCell(0, 0, 0, Cell(note = 64, instrument = 1))
        assertNull(session.fittedFrom(0, 0, 0))
        assertEquals(62, session.fittedFrom(0, 1, 2)?.note)
        // Undoing that edit shows its mark again; undoing the first Fit (original notes) shows none.
        session.undo()
        assertEquals(61, session.fittedFrom(0, 0, 0)?.note)
        session.undo() // the cell on channel 3
        session.undo() // the scale change (the second Fit changed nothing)
        session.undo() // the first Fit
        assertEquals(61, cell(0, 0, 0).note)
        assertNull(session.fittedFrom(0, 0, 0))
        assertNull(session.fittedFrom(0, 1, 2))
    }

    @Test
    fun aNoteFittedBackToItsOriginalIsNoLongerMarked() {
        session.setCell(0, 0, 0, Cell(note = 62, instrument = 1))   // D
        session.setPatternScale(0, Scale(0, ScaleKind.MINOR_PENTATONIC))
        session.fitToScale(0)                                        // D -> Eb (27)
        assertEquals(62, session.fittedFrom(0, 0, 0)?.note)
        session.setPatternScale(0, Scale(2, ScaleKind.MINOR_PENTATONIC)) // D F G A C: Eb is nearest to D
        session.fitToScale(0)
        assertEquals(62, cell(0, 0, 0).note)
        assertNull(session.fittedFrom(0, 0, 0))
    }

    @Test
    fun chainedFitsMoveAFittedNoteAgainButRememberItsOriginal() {
        session.setCell(0, 0, 0, Cell(note = 62, instrument = 1))   // D
        session.setPatternScale(0, Scale(0, ScaleKind.MINOR_PENTATONIC))
        session.fitToScale(0)                                        // D -> Eb (27)
        session.setPatternScale(0, Scale(4, ScaleKind.MAJOR_PENTATONIC)) // E F# G# B C#: Eb is nearest to E
        assertEquals(1, session.fitToScale(0))
        assertEquals(64, cell(0, 0, 0).note)
        assertEquals(62, session.fittedFrom(0, 0, 0)?.note)          // the original, not the intermediate Eb
    }

    @Test
    fun insertingOrDeletingRowsClearsThePatternsFitMarks() {
        for (r in 4..7) session.setCell(0, 0, r, Cell(note = 61, instrument = 1))
        session.setPatternScale(0, Scale(0, ScaleKind.MAJOR))
        session.fitToScale(0)
        assertEquals(61, session.fittedFrom(0, 0, 4)?.note)
        session.insertRow(0, 0..0, 2)
        for (r in 0..63) assertNull("row $r", session.fittedFrom(0, 0, r))
    }

    @Test
    fun octaveChangeWhileListeningOnTheKeyboardReSoundsTheSameKey() {
        val v = view(ViewKind.KEYBOARD) { kbRow = 1; kbKey = 4; octave = 4 }
        v.press(Action.AUDITION)
        val first = auditions.last().second
        v.tap(Action.OCTAVE_UP)
        assertEquals(first + 12, auditions.last().second)
        v.release(Action.AUDITION)
    }

    @Test
    fun tapEditWritesLastNoteAdvancesAndIsOneUndo() {
        val v = view(ViewKind.PATTERN)
        v.tap(Action.EDIT)
        assertEquals(Cell(note = 48, instrument = 1), cell(0, 0, 0))
        assertEquals(1, v.state.row)
        assertEquals(Triple(0, 12, 1), auditions.single())
        assertEquals(1, releases)
        assertEquals(1, session.selection.row)

        session.undo()
        assertEquals(Cell(), cell(0, 0, 0))
        assertFalse(session.canUndo)
    }

    @Test
    fun holdEditAdjustsNoteFineAndCoarseAsOneStep() {
        val v = view(ViewKind.PATTERN)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.UP)
        assertEquals(48 + 3 + 12, cell(0, 0, 0).note)
        assertEquals(0, v.state.row) // adjusting does not advance
        session.undo()
        assertEquals(Cell(), cell(0, 0, 0))
        assertFalse(session.canUndo)
    }

    @Test
    fun clearCyclesNoteEmptyCutEmpty() {
        val v = view(ViewKind.PATTERN)
        session.setCell(0, 0, 0, Cell(note = 56, instrument = 2, effect = 0xC, param = 0x08))
        v.tap(Action.CLEAR)
        assertEquals(Cell(Cell.EMPTY, 0, 0xC, 0x08), cell(0, 0, 0))
        v.tap(Action.CLEAR) // effect present: a cut would need the effect slot
        assertEquals(Cell.EMPTY, cell(0, 0, 0).note)
        assertNotNull(session.message)
        v.state.field = 2
        v.tap(Action.CLEAR)
        v.state.field = 0
        v.tap(Action.CLEAR)
        assertEquals(Cell(note = Cell.CUT), cell(0, 0, 0))
        assertEquals(null, GbSystem.cellProblem(session.song, 0, cell(0, 0, 0)))
        v.tap(Action.CLEAR)
        assertEquals(Cell(), cell(0, 0, 0))
    }

    @Test
    fun soundNumberNeedsANote() {
        val v = view(ViewKind.PATTERN) { field = 1 }
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(Cell(), cell(0, 0, 0))
        assertNotNull(session.message)
        assertFalse(session.canUndo)
    }

    @Test
    fun effectTypeSkipsUnsupportedCallRoutine() {
        val v = view(ViewKind.PATTERN) { field = 2 }
        v.hold(Action.EDIT, *Array(7) { Action.RIGHT }) // no effect, 0, 1, 2, 3, 4, 5, then 7
        assertEquals(0x7, cell(0, 0, 0).effect)
        v.state.field = 3
        v.hold(Action.EDIT, Action.UP, Action.RIGHT)
        assertEquals(0x11, cell(0, 0, 0).param)
    }

    @Test
    fun effectTypeOffersOnlyEffectsTheChannelSupports() {
        val v = view(ViewKind.PATTERN) { channel = 3; field = 2 }
        v.hold(Action.EDIT, Action.RIGHT)
        val first = cell(0, 3, 0).effect
        assertEquals(GbSystem.effectsFor(3).map { it.code }.first { it != 0 }, first)
        repeat(16) { v.hold(Action.EDIT, Action.RIGHT) }
        assertTrue(GbSystem.effectsFor(3).any { it.code == cell(0, 3, 0).effect })
    }

    @Test
    fun markCopyPasteAndDuplicateBlocks() {
        for (r in 0..3) {
            session.setCell(0, 0, r, Cell(note = r, instrument = 1))
            session.setCell(0, 1, r, Cell(note = 60 + r, instrument = 2))
        }
        val v = view(ViewKind.PATTERN)
        v.hold(Action.MARK, Action.DOWN, Action.DOWN, Action.DOWN, Action.RIGHT)
        assertTrue(v.state.mark)
        assertEquals(Block(0, 1, 0, 3), v.block())
        v.tap(Action.COPY)

        v.tap(Action.MARK) // tap drops the mark
        assertFalse(v.state.mark)
        v.state.row = 8
        v.state.channel = 0
        v.tap(Action.PASTE)
        for (r in 0..3) {
            assertEquals(cell(0, 0, r), cell(0, 0, 8 + r))
            assertEquals(cell(0, 1, r), cell(0, 1, 8 + r))
        }
        assertEquals(Block(0, 1, 8, 11), v.block())

        v.tap(Action.DUPLICATE)
        assertEquals(cell(0, 1, 9), cell(0, 1, 13))
        assertEquals(Block(0, 1, 12, 15), v.block())
    }

    @Test
    fun auditionListensWithoutWriting() {
        val v = view(ViewKind.PATTERN)
        val before = session.song
        v.press(Action.AUDITION)
        assertEquals(Triple(0, 12, 1), auditions.single())
        v.release(Action.AUDITION)
        assertEquals(1, releases)
        assertSame(before, session.song)
    }

    @Test
    fun keyboardWriteModeWritesAtSharedCursorAndListenModeDoesNot() {
        val kb = view(ViewKind.KEYBOARD) { entryMode = true; kbRow = 1; kbKey = 4 }
        kb.tap(Action.EDIT)
        assertEquals(Cell(note = 52, instrument = 1), cell(0, 0, 0))
        assertEquals(1, session.selection.row)

        kb.tap(Action.CLEAR) // write mode: cut and advance
        assertEquals(Cell.CUT, cell(0, 0, 1).note)
        assertEquals(2, session.selection.row)

        kb.tap(Action.MARK) // switch to listen
        assertFalse(kb.state.entryMode)
        val before = session.song
        kb.tap(Action.EDIT)
        assertSame(before, session.song)
        assertEquals(16, auditions.last().second)
    }

    @Test
    fun keyboardFollowsPatternCursorChannel() {
        val pattern = view(ViewKind.PATTERN)
        val kb = view(ViewKind.KEYBOARD) { entryMode = true; kbRow = 1 }
        pattern.tap(Action.NEXT_CHANNEL)
        pattern.tap(Action.NEXT_CHANNEL)
        kb.follow()
        assertEquals(2, kb.state.channel)
        kb.tap(Action.EDIT)
        assertEquals(48, cell(0, 2, 0).note)
    }

    @Test
    fun instrumentParameterHoldIsOneUndo() {
        val v = view(ViewKind.INSTRUMENT) { bank = 0; instrument = 1; instField = 3 }
        assertEquals("Volume", GbSoundView.params(v.state)[1].label)
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals(13, session.song.gb.pulse[0].volume)
        session.undo()
        assertEquals(15, session.song.gb.pulse[0].volume)

        v.state.instField = 2 + 4 // length cut on/off
        v.tap(Action.EDIT)
        assertTrue(session.song.gb.pulse[0].lengthEnabled)
    }

    @Test
    fun everyInstrumentParameterStaysInRange() {
        for (kind in GbBank.entries) {
            val v = view(ViewKind.INSTRUMENT) { bank = kind.ordinal; instrument = 2 }
            for ((i, p) in GbSoundView.params(v.state).withIndex()) {
                v.state.instField = i + 2
                v.hold(Action.EDIT, *Array(40) { Action.UP })
                assertEquals(p.max, p.get(session.song.bank(kind)[1]))
                v.hold(Action.EDIT, *Array(40) { Action.DOWN })
                assertEquals(p.min, p.get(session.song.bank(kind)[1]))
            }
        }
        assertTrue(GbValidator.structuralErrors(session.song).isEmpty())
    }

    @Test
    fun soundNameEditsWithTheController() {
        val v = view(ViewKind.INSTRUMENT) { instField = 1 }
        session.setInstrument(GbBank.PULSE, 1, session.song.gb.pulse[0].copy(name = "Lead"))
        v.tap(Action.RIGHT)
        v.tap(Action.RIGHT)
        v.tap(Action.RIGHT)
        v.tap(Action.RIGHT) // past the end: appends
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals("LeadB", session.song.gb.pulse[0].name)
        v.tap(Action.CLEAR)
        assertEquals("Lead", session.song.gb.pulse[0].name)
    }

    @Test
    fun waveDrawingAndToolsAreUndoable() {
        val original = session.song.gb.waves[0]
        val v = view(ViewKind.WAVE) { wave = 0; waveRow = 1; waveCol = 0; waveLevel = 8 }
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(8, session.song.gb.waves[0][0])
        assertEquals(9, session.song.gb.waves[0][1])
        session.undo()
        assertEquals(original, session.song.gb.waves[0])

        v.tap(Action.LEFT) // back to sample 0, pen still at level 9
        repeat(6) { v.tap(Action.UP) } // level 9 -> 15
        assertEquals(1, v.state.waveRow)
        v.tap(Action.UP) // past the top: into the tool row
        assertEquals(0, v.state.waveRow)
        v.tap(Action.EDIT) // Sine
        assertEquals(WaveShapes.sine(), session.song.gb.waves[0])
    }

    @Test
    fun useInSoundAssignsTheWaveAndAuditionFindsIt() {
        session.select(Selection(bank = GbBank.WAVE.ordinal, instrument = 3))
        val v = view(ViewKind.WAVE) { wave = 12; waveRow = 0; waveCol = 8 }
        v.tap(Action.EDIT)
        assertEquals(12, session.song.gb.wave[2].wave)
        v.state.waveRow = 1
        v.press(Action.AUDITION)
        assertEquals(Triple(2, 12, 3), auditions.last())
        v.release(Action.AUDITION)
    }

    @Test
    fun songNewPatternScaleGuidesNewPatternsOnly() {
        val v = view(ViewKind.SONG) { songItem = ViewState.SONG_NEW_SCALE_ITEM }
        v.hold(Action.EDIT, Action.RIGHT, Action.UP, Action.UP)          // Chromatic → Major, root C → D
        assertEquals(Scale(2, ScaleKind.MAJOR), session.song.newPatternScale)
        val fresh = session.newPatternAt(1)
        assertEquals(Scale(2, ScaleKind.MAJOR), session.song.patterns[fresh].scale)
        // Existing patterns and clones keep their own guide.
        assertEquals(Scale.CHROMATIC, session.song.patterns[0].scale)
        val clone = session.clonePatternAt(0)
        assertEquals(Scale.CHROMATIC, session.song.patterns[clone].scale)
        v.tap(Action.CLEAR)
        assertEquals(Scale.CHROMATIC, session.song.newPatternScale)
        session.undo()
        assertEquals(Scale(2, ScaleKind.MAJOR), session.song.newPatternScale)
    }

    @Test
    fun songViewArrangesOrders() {
        val v = view(ViewKind.SONG) { songItem = ViewState.SONG_HEADER_ITEMS; songField = 1 }
        v.tap(Action.EDIT) // repeat
        assertEquals(listOf(0, 0), session.song.orders)
        assertEquals(1, v.songOrder)
        v.state.songField = 2
        v.tap(Action.EDIT) // new pattern
        assertEquals(listOf(0, 0, 1), session.song.orders)
        v.state.songField = 3
        v.tap(Action.EDIT) // clone
        assertEquals(listOf(0, 0, 1, 2), session.song.orders)
        assertEquals(3, session.song.patterns.size)
        v.state.songField = 7
        v.tap(Action.EDIT) // loop here
        assertEquals(3, session.song.loopOrder)
        v.state.songField = 5
        v.tap(Action.EDIT) // earlier
        assertEquals(listOf(0, 0, 2, 1), session.song.orders)
        v.state.songField = 0
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(listOf(0, 0, 1, 1), session.song.orders)
        v.tap(Action.CLEAR) // delete order
        assertEquals(3, session.song.orders.size)

        v.state.songItem = 2
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(6 + 1 + 4, session.song.ticksPerRow)
    }

    @Test
    fun songViewXPlaysFromTheOrder() {
        session.newPatternAt(1)
        val v = view(ViewKind.SONG) { songItem = ViewState.SONG_HEADER_ITEMS + 1 }
        v.tap(Action.AUDITION)
        assertEquals(1, transports)
        assertEquals(1, session.selection.order)
        assertEquals(0, session.selection.row)
    }

    @Test
    fun followingViewsTrackSubjectsUnlessLocked() {
        val pattern = view(ViewKind.PATTERN)
        repeat(3) { pattern.tap(Action.NEXT_CHANNEL) }
        pattern.state.field = 1
        pattern.publishPattern()
        assertEquals(GbBank.NOISE.ordinal, session.selection.bank)
        assertEquals(ViewKind.INSTRUMENT, session.selection.context)

        val follower = view(ViewKind.INSTRUMENT)
        assertTrue(follower.follow())
        assertEquals(GbBank.NOISE.ordinal, follower.state.bank)

        val locked = view(ViewKind.INSTRUMENT) { lockedSubject = 0 * 16 + 5 }
        locked.follow()
        assertEquals(0, locked.state.bank)
        assertEquals(5, locked.state.instrument)

        val independent = view(ViewKind.PATTERN) { followSelection = false; row = 30 }
        assertFalse(independent.follow())
        assertEquals(30, independent.state.row)
    }

    @Test
    fun changingEditorDoesNotInterpretANoiseInstrumentLockAsAWaveIndex() {
        val v = view(ViewKind.INSTRUMENT) { bank = 2; instrument = 1; lockedSubject = 33 }
        v.state.switchKind(ViewKind.WAVE)
        session.select(Selection(bank = GbBank.WAVE.ordinal, instrument = 1, wave = 3, context = ViewKind.WAVE))
        v.follow()
        assertEquals(3, v.state.wave)

        v.state.lockedSubject = 3
        v.state.switchKind(ViewKind.WAVE)
        session.select(Selection(bank = GbBank.WAVE.ordinal, instrument = 1, wave = 4, context = ViewKind.WAVE))
        v.follow()
        assertEquals("Reselecting the same editor retains its subject lock", 3, v.state.wave)
    }

    @Test
    fun explicitNavigationMovesTheLock() {
        val v = view(ViewKind.INSTRUMENT) { lockedSubject = 16 + 2; bank = 1; instrument = 2 }
        v.tap(Action.RIGHT)
        assertEquals(16 + 3, v.state.lockedSubject)
    }

    @Test
    fun undoWhileHoldingEditKeepsHistoryCoherent() {
        val v = view(ViewKind.PATTERN)
        v.press(Action.EDIT)
        v.press(Action.RIGHT)
        v.press(Action.UNDO)
        assertEquals(Cell(), cell(0, 0, 0))
        v.release(Action.EDIT)
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)
        session.redo()
        assertEquals(49, cell(0, 0, 0).note)
    }

    @Test
    fun cancelHoldsEndsTheGestureAndAudition() {
        val v = view(ViewKind.PATTERN)
        v.press(Action.EDIT)
        v.press(Action.RIGHT)
        v.cancelHolds()
        assertFalse(v.editHeld)
        assertFalse(session.inGesture)
        assertEquals(1, releases)
        v.press(Action.RIGHT) // a stray direction after a lost key-up only moves the cursor
        assertEquals(49, cell(0, 0, 0).note)
        assertEquals(1, v.state.field)
    }

    @Test
    fun hintsDescribeTheCurrentAction() {
        val v = view(ViewKind.PATTERN)
        assertTrue(v.hints().any { it.keys == "A" && it.text.startsWith("Write note C-4") })
        v.press(Action.EDIT)
        assertTrue(v.hints().any { it.text == "Semitone" })
        v.release(Action.EDIT)
        for (kind in ViewKind.entries) assertTrue(view(kind).hints().isNotEmpty())
    }

    // ---- regressions found in editor review (each failed before its fix) ----

    @Test
    fun holdingClearWhileMovingNeverClears() {
        val before = session.song
        view(ViewKind.INSTRUMENT) { instField = 0; instrument = 1 }.hold(Action.CLEAR, Action.RIGHT)
        view(ViewKind.INSTRUMENT) { instField = 2; instrument = 1 }.hold(Action.CLEAR, Action.DOWN)
        view(ViewKind.WAVE) { wave = 0; waveRow = 0; waveCol = 5 }.hold(Action.CLEAR, Action.DOWN)
        view(ViewKind.KEYBOARD) { entryMode = true; kbRow = 0; kbField = 1 }.hold(Action.CLEAR, Action.RIGHT)
        assertSame(before, session.song)
        assertEquals(0, session.selection.row)
    }

    @Test
    fun keyboardKeysAboveTheTopNoteDoNothing() {
        val kb = view(ViewKind.KEYBOARD) { entryMode = true; kbRow = 1; octave = 7; kbKey = 24 }
        val before = session.song
        kb.tap(Action.EDIT)
        kb.tap(Action.AUDITION)
        assertSame(before, session.song)
        assertTrue(auditions.isEmpty())
        kb.tap(Action.OCTAVE_UP)
        assertEquals(7, kb.state.octave)
        kb.state.kbKey = 23
        kb.tap(Action.EDIT)
        assertEquals(GbSystem.noteRange.last, cell(0, 0, 0).note)
    }

    @Test
    fun heldControlsDeferFollowingAnotherDisplay() {
        session.setCell(0, 0, 5, Cell(note = 66, instrument = 1))
        val v = view(ViewKind.PATTERN) { row = 5 }
        v.press(Action.EDIT) // picks up the note, no insertion
        session.select(Selection(row = 20))
        assertFalse(v.follow())
        assertEquals(5, v.state.row)
        v.release(Action.EDIT)
        assertTrue(v.follow())
        assertEquals(20, v.state.row)
    }

    @Test
    fun contextSuggestsAComplementaryView() {
        view(ViewKind.KEYBOARD) { entryMode = true; kbRow = 1 }.tap(Action.EDIT)
        assertEquals(ViewKind.PATTERN, session.selection.context)
        view(ViewKind.KEYBOARD) { kbRow = 0; kbField = 1 }.hold(Action.EDIT, Action.RIGHT)
        assertEquals(ViewKind.PATTERN, session.selection.context)
        view(ViewKind.INSTRUMENT) { bank = GbBank.PULSE.ordinal }.tap(Action.RIGHT)
        assertEquals(ViewKind.KEYBOARD, session.selection.context)
        view(ViewKind.INSTRUMENT) { bank = GbBank.WAVE.ordinal }.tap(Action.RIGHT)
        assertEquals(ViewKind.WAVE, session.selection.context)
        view(ViewKind.WAVE).tap(Action.NEXT_CHANNEL)
        assertEquals(ViewKind.INSTRUMENT, session.selection.context)
    }

    @Test
    fun entryMemoryTravelsWithTheViewStateOnSwap() {
        val top = view(ViewKind.PATTERN)
        top.hold(Action.EDIT, Action.RIGHT, Action.RIGHT) // C-4 -> D-4
        top.tap(Action.DOWN)
        val bottom = view(ViewKind.KEYBOARD)
        val moved = top.state
        top.state = bottom.state
        bottom.state = moved // the host swaps whole states between displays
        bottom.tap(Action.EDIT)
        assertEquals(50, cell(0, 0, 1).note)
    }

    @Test
    fun playbackFollowWaitsForHeldControlsAndThenLetsTheUserNavigate() {
        session.newPatternAt(1)
        session.setCell(0, 0, 0, Cell(note = 66, instrument = 1))
        val v = view(ViewKind.PATTERN)
        v.press(Action.EDIT)
        assertFalse(v.followPlayback(order = 1, playing = true))
        assertEquals(0, v.state.order)
        v.release(Action.EDIT)
        assertTrue(v.followPlayback(order = 1, playing = true))
        assertEquals(1, v.state.order)
        v.state.order = 0 // the user navigates away while order 1 keeps playing
        assertFalse(v.followPlayback(order = 1, playing = true))
        assertEquals(0, v.state.order)

        val still = view(ViewKind.PATTERN) { followPlayback = false }
        assertFalse(still.followPlayback(order = 1, playing = true))
        assertEquals(0, still.state.order)
    }

    @Test
    fun anotherDisplayCannotEndAHeldAdjustment() {
        val pattern = view(ViewKind.PATTERN)
        val keyboard = view(ViewKind.KEYBOARD) { kbRow = 1 }
        pattern.press(Action.EDIT)
        pattern.tap(Action.RIGHT)
        keyboard.tap(Action.EDIT) // Listen-mode key on the other display
        keyboard.state.entryMode = true
        keyboard.tap(Action.EDIT) // and a written note: both join the held group
        assertTrue(session.inGesture)
        pattern.tap(Action.RIGHT)
        pattern.release(Action.EDIT)
        assertFalse(session.inGesture)
        session.undo()
        assertEquals(Songs.blank(), session.song)
        assertFalse(session.canUndo)
    }

    @Test
    fun snapshotCopiesEntryMemoryIndependently() {
        val v = view(ViewKind.PATTERN)
        v.hold(Action.EDIT, Action.RIGHT)
        val copy = v.state.snapshot()
        v.state.entry.notes[0] = 40
        assertEquals(49, copy.entry.notes[0])
    }

    /** A complete short song composed with controller actions only, across every view. */
    @Test
    fun physicalControlsComposeACompleteExportableSong() {
        session.step = 4
        val pattern = view(ViewKind.PATTERN)

        // Melody on Pulse 1: a note every 4 rows, each held-A adjustment starting from the previous note.
        pattern.tap(Action.EDIT)
        for (semitones in listOf(4, 7, 12)) {
            pattern.hold(Action.EDIT, *Array(semitones) { Action.RIGHT })
            pattern.tap(Action.DOWN); pattern.tap(Action.DOWN); pattern.tap(Action.DOWN); pattern.tap(Action.DOWN)
        }
        // Set volume effect C0F on row 0 (after "no effect", effect type C is the 12th supported code).
        pattern.state.row = 0
        pattern.state.field = 2
        pattern.hold(Action.EDIT, *Array(12) { Action.RIGHT })
        pattern.state.field = 3
        pattern.hold(Action.EDIT, *Array(15) { Action.RIGHT })
        assertEquals(0xC, cell(0, 0, 0).effect)
        assertEquals(0x0F, cell(0, 0, 0).param)

        // Bass on Pulse 2 an octave lower with sound 03.
        pattern.state.field = 0
        pattern.tap(Action.NEXT_CHANNEL)
        pattern.tap(Action.OCTAVE_DOWN)
        pattern.tap(Action.EDIT)
        pattern.state.row = 0
        pattern.state.field = 1
        pattern.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(Cell(note = 36, instrument = 3), cell(0, 1, 0))

        // Wave and noise: a note each, and a cut on the noise channel.
        pattern.state.field = 0
        pattern.tap(Action.NEXT_CHANNEL)
        pattern.tap(Action.EDIT)
        pattern.tap(Action.NEXT_CHANNEL)
        pattern.state.row = 0
        pattern.tap(Action.EDIT)
        pattern.tap(Action.CLEAR)
        assertEquals(Cell.CUT, cell(0, 3, 4).note)

        // Copy the melody bar down with the block selection.
        pattern.state.channel = 0
        pattern.state.row = 0
        pattern.hold(Action.MARK, *Array(15) { Action.DOWN })
        pattern.tap(Action.COPY)
        pattern.tap(Action.MARK)
        pattern.state.row = 16
        pattern.tap(Action.PASTE)
        assertEquals(cell(0, 0, 4), cell(0, 0, 20))

        // Shape the lead sound and draw a wave the wave sound uses.
        val sounds = view(ViewKind.INSTRUMENT) { bank = 0; instrument = 1; instField = 2 }
        sounds.hold(Action.EDIT, Action.RIGHT) // duty 25%
        assertEquals(1, session.song.gb.pulse[0].duty)
        val waves = view(ViewKind.WAVE) { wave = 0; waveRow = 0; waveCol = 1 }
        waves.tap(Action.EDIT) // triangle
        assertEquals(WaveShapes.triangle(), session.song.gb.waves[0])

        // Arrange: repeat the pattern, add a clone, speed up, loop to order 1.
        val song = view(ViewKind.SONG) { songItem = ViewState.SONG_HEADER_ITEMS; songField = 1 }
        song.tap(Action.EDIT)
        song.state.songField = 3
        song.tap(Action.EDIT)
        song.state.songField = 7
        song.state.songItem = ViewState.SONG_HEADER_ITEMS + 1
        song.tap(Action.EDIT)
        song.state.songItem = 2
        song.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        song.state.songItem = 0
        song.hold(Action.EDIT, Action.RIGHT) // "Untitled" -> "Vntitled"

        val s = session.song
        assertEquals(listOf(0, 0, 1), s.orders)
        assertEquals(1, s.loopOrder)
        assertEquals(4, s.ticksPerRow)
        assertEquals("Vntitled", s.title)
        for (ch in 0 until 4) assertTrue(s.patterns[0].tracks[ch].any { it.note >= 0 })
        assertEquals(listOf<String>(), GbValidator.errors(s))
        assertNotEquals(Songs.blank(), s)
    }

    @Test
    fun effectZeroIsTheFirstPickAndTakesAValue() {
        val v = view(ViewKind.PATTERN) { field = 2 }
        v.hold(Action.EDIT, Action.RIGHT)
        // Effect 0 comes right after "no effect": shown as chosen, but nothing audible is written yet.
        assertTrue(v.chosenArpeggio())
        assertEquals(Cell(), cell(0, 0, 0))
        assertFalse(session.canUndo)
        v.tap(Action.RIGHT) // value column of the same cell
        assertEquals(3, v.state.field)
        assertTrue(v.chosenArpeggio())
        v.hold(Action.EDIT, Action.UP, Action.RIGHT, Action.RIGHT)
        assertEquals(Cell(effect = 0, param = 0x12), cell(0, 0, 0))
        v.tap(Action.DOWN)
        assertFalse(v.chosenArpeggio())
    }

    @Test
    fun effectOneWithValueZeroStepsBackToEffectZeroNotToNothing() {
        session.setCell(0, 0, 0, Cell(note = 60, instrument = 1, effect = 1, param = 0))
        val v = view(ViewKind.PATTERN) { field = 2 }
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(Cell(note = 60, instrument = 1), cell(0, 0, 0)) // 000: the same bytes as no effect
        assertTrue(v.chosenArpeggio())
        v.hold(Action.EDIT, Action.LEFT) // one further: no effect
        assertFalse(v.chosenArpeggio())
        assertEquals(Cell(note = 60, instrument = 1), cell(0, 0, 0))
        v.hold(Action.EDIT, Action.RIGHT) // forward again lands on 0, not 1
        assertTrue(v.chosenArpeggio())
        v.state.field = 3
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(Cell(note = 60, instrument = 1, effect = 0, param = 0x01), cell(0, 0, 0))
    }

    @Test
    fun patternScaleGuidesNoteSteppingWithoutChangingWrittenNotes() {
        session.setCell(0, 0, 0, Cell(note = 49, instrument = 1)) // C#4, outside C major
        session.setCell(0, 0, 1, Cell(note = 52, instrument = 1)) // E-4
        session.setCell(0, 3, 0, Cell(note = 52, instrument = 1)) // a noise pitch
        val tracks = session.song.patterns[0].tracks
        assertTrue(session.setPatternScale(0, Scale(0, ScaleKind.MAJOR)))
        assertSame(tracks, session.song.patterns[0].tracks)

        val v = view(ViewKind.PATTERN)
        v.hold(Action.EDIT, Action.UP) // C#5 is outside the scale: the next scale note above it, D-5
        assertEquals(62, cell(0, 0, 0).note)
        v.hold(Action.EDIT, Action.RIGHT) // D-5 to E-5
        assertEquals(64, cell(0, 0, 0).note)
        v.state.row = 1
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT) // E, F, G
        assertEquals(55, cell(0, 0, 1).note)
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT, Action.LEFT) // F, E, D
        assertEquals(50, cell(0, 0, 1).note)
        v.hold(Action.EDIT, Action.DOWN) // from a scale note the octave is exact
        assertEquals(38, cell(0, 0, 1).note)
        v.state.channel = 3
        v.state.row = 0
        v.hold(Action.EDIT, Action.RIGHT) // Noise note numbers stay chromatic
        assertEquals(53, cell(0, 3, 0).note)
    }

    @Test
    fun scaleEditsStayInsideTheNoteRange() {
        session.setPatternScale(0, Scale(9, ScaleKind.MINOR_PENTATONIC)) // A C D E G
        val v = view(ViewKind.PATTERN)
        fun edit(note: Int, vararg moves: Action): Int {
            session.setCell(0, 0, 0, Cell(note = note, instrument = 1))
            v.hold(Action.EDIT, *moves)
            return cell(0, 0, 0).note
        }
        assertEquals(105, edit(103, Action.RIGHT, Action.RIGHT, Action.UP)) // G-8 to A-8, the top scale note; no octave above
        assertEquals(36, edit(36, Action.LEFT, Action.DOWN)) // C-3: nothing lower
        assertEquals(94, edit(94, Action.UP)) // A#7 (off scale): A#8 and B-8 are not scale notes, so no edit
        assertEquals(36, edit(49, Action.DOWN)) // C#4 (off scale): C#3, then down to C-3
        session.setPatternScale(0, Scale(2, ScaleKind.MAJOR)) // C is not in D major
        assertEquals(48, edit(48, Action.DOWN)) // C-4 (off scale): no D major note at or below C-3, so no edit
    }

    @Test
    fun chromaticEditsStillClampToTheNoteRange() {
        val v = view(ViewKind.PATTERN)
        fun edit(note: Int, vararg moves: Action): Int {
            session.setCell(0, 0, 0, Cell(note = note, instrument = 1))
            v.hold(Action.EDIT, *moves)
            return cell(0, 0, 0).note
        }
        assertEquals(GbSystem.noteRange.last, edit(101, Action.UP))
        assertEquals(36, edit(41, Action.DOWN))
        assertEquals(GbSystem.noteRange.last, edit(GbSystem.noteRange.last, Action.RIGHT))
        assertEquals(36, edit(36, Action.LEFT))
    }

    @Test
    fun scalePanelEditsUndoableMetadataThatCloneAndPatternCopyCarry() {
        session.setCell(0, 0, 0, Cell(note = 49, instrument = 1))
        val tracks = session.song.patterns[0].tracks
        val v = view(ViewKind.PATTERN)
        v.tap(Action.SCALE)
        assertTrue(v.state.scalePanel)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT) // root D
        v.tap(Action.DOWN)
        v.tap(Action.RIGHT) // Major
        v.tap(Action.EDIT) // Done
        assertFalse(v.state.scalePanel)
        assertEquals(Scale(2, ScaleKind.MAJOR), session.song.patterns[0].scale)
        assertSame(tracks, session.song.patterns[0].tracks)
        session.undo()
        assertEquals(Scale(2, ScaleKind.CHROMATIC), session.song.patterns[0].scale)
        session.redo()

        assertEquals(1, session.clonePatternAt(0))
        assertEquals(Scale(2, ScaleKind.MAJOR), session.song.patterns[1].scale)
        val song = view(ViewKind.SONG) { songItem = ViewState.SONG_HEADER_ITEMS }
        song.tap(Action.COPY)
        assertEquals(2, session.newPatternAt(2))
        song.state.songItem = ViewState.SONG_HEADER_ITEMS + 2
        song.tap(Action.PASTE)
        assertEquals(Scale(2, ScaleKind.MAJOR), session.song.patterns[2].scale)
        assertEquals(tracks, session.song.patterns[2].tracks)
    }
}
