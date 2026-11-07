package org.thortracker.editor

import org.thortracker.system.gb.GbSystem
import org.thortracker.system.gb.GbBank
import org.thortracker.system.gb.gb
import org.thortracker.system.gb.HugeRules
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Phrase
import org.thortracker.model.PhraseSettings
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Scl
import org.thortracker.model.Songs

class SketchControllerTest {
    private fun controller(scale: Scale = Scale(0, ScaleKind.MAJOR), ticksPerRow: Int = 6): ViewController {
        val song = Songs.blank().let { it.copy(ticksPerRow = ticksPerRow, patterns = listOf(it.patterns[0].copy(scale = scale))) }
        return ViewController(EditorSession(song), ViewState(kind = ViewKind.SKETCH))
    }

    private fun ViewController.tap(action: Action) { press(action); release(action) }

    private data class Sequence(val channel: Int, val steps: List<Int?>, val instrument: Int, val stepMillis: Long)

    private fun EditorSession.recordSequences(): MutableList<Sequence> {
        val out = mutableListOf<Sequence>()
        onAuditionSequence = { channel, steps, instrument, ms -> out += Sequence(channel, steps, instrument, ms) }
        return out
    }

    @Test fun exploringAndListeningPreserveTheDocumentAndExistingRedo() {
        val ctl = controller()
        val session = ctl.session
        session.setCell(0, 0, 0, Cell(note = 48, instrument = 1))
        val edited = session.song
        session.undo()
        val before = session.song
        val selection = session.selection
        val notes = mutableListOf<Int>()
        var releases = 0
        session.onAudition = { _, note, _, _ -> notes += note }
        val chords = session.recordSequences()
        session.onReleaseAudition = { releases++ }
        ctl.tap(Action.EDIT)
        ctl.tap(Action.DOWN)
        ctl.press(Action.AUDITION)
        ctl.tap(Action.RIGHT)
        ctl.release(Action.AUDITION)
        ctl.sketchSetField(0)
        ctl.press(Action.EDIT)
        ctl.tap(Action.RIGHT)
        ctl.release(Action.EDIT)
        ctl.tap(Action.CLEAR)
        ctl.tap(Action.PASTE)
        ctl.tap(Action.CUT)
        ctl.tap(Action.INSERT_ROW)
        assertEquals(listOf(Sequence(0, listOf(48, 52, 55), 1, 180)), chords)
        assertEquals(listOf(48, 50), notes)
        assertEquals(2, releases)
        assertSame(before, session.song)
        assertEquals(selection, session.selection)
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)
        session.redo()
        assertEquals(edited, session.song)
    }

    @Test fun localKeyIsIndependentAndCanResumeFollowingThePattern() {
        val ctl = controller(Scale(2, ScaleKind.MAJOR))
        val other = ViewController(ctl.session, ViewState(kind = ViewKind.SKETCH))
        ctl.normalise(); other.normalise()
        ctl.sketchSetField(0)
        ctl.sketchAdjustField(3)
        assertEquals(Scale(5, ScaleKind.MAJOR), ctl.sketchGuide.scale)
        assertEquals(Scale(2, ScaleKind.MAJOR), other.sketchGuide.scale)
        assertEquals(Scale(2, ScaleKind.MAJOR), ctl.session.song.patterns[0].scale)
        ctl.tap(Action.MARK)
        assertNull(ctl.state.sketchScale)
        assertEquals(other.sketchGuide.scale, ctl.sketchGuide.scale)
        assertFalse(ctl.session.canUndo)
    }

    @Test fun everyVoicingFitsHardwareWithoutClippingOrForeignTones() {
        for (kind in ScaleKind.entries) for (root in 0..11) for (octave in listOf(3, 7)) {
            val scale = Scale(root, kind)
            val ctl = controller(scale)
            ctl.state.octave = octave
            ctl.normalise()
            ctl.sketchGuide.tones.indices.forEach { index ->
                assertTrue(ctl.sketchNote(index) in GbSystem.noteRange)
                assertTrue(scale.contains(ctl.sketchNote(index)))
            }
            ctl.sketchGuide.chords.forEachIndexed { index, chord ->
                ctl.sketchSelectChord(index)
                val voiced = ctl.sketchChordNotes()
                assertTrue(voiced.all { it in GbSystem.noteRange && scale.contains(it) })
                assertEquals(chord.intervals.map { it - chord.intervals[0] }, voiced.map { it - voiced[0] })
                assertEquals((root + chord.intervals[0]) % 12, voiced[0] % 12)
            }
        }
    }

    @Test fun chromaticCannotStartAnArpeggioButNotesStillPlayAndCancel() {
        val ctl = controller(Scale.CHROMATIC)
        val notes = mutableListOf<Int>()
        ctl.session.onAudition = { _, note, _, _ -> notes += note }
        ctl.session.onAuditionSequence = { _, _, _, _ -> fail("Chromatic has no diatonic chord") }
        ctl.tap(Action.EDIT)
        assertFalse(ctl.sketchAuditioning)
        ctl.sketchSelectTone(11)
        ctl.press(Action.AUDITION)
        assertEquals(listOf(59), notes)
        assertTrue(ctl.holding)
        ctl.cancelHolds()
        assertFalse(ctl.holding)
        assertFalse(ctl.sketchAuditioning)
        assertSame(Scale.CHROMATIC, ctl.sketchGuide.scale)
    }

    @Test fun followingAndChannelControlsNeverRouteTonalPreviewsToNoise() {
        val ctl = controller()
        ctl.session.select(Selection(channel = 3, bank = GbBank.NOISE.ordinal, instrument = 4))
        ctl.follow(); ctl.normalise()
        assertEquals(0, ctl.state.channel)
        assertEquals(1, ctl.state.instrument)
        repeat(5) { ctl.tap(Action.NEXT_CHANNEL) }
        assertEquals(2, ctl.state.channel)
        val notes = mutableListOf<Triple<Int, Int, Int>>()
        ctl.session.onAudition = { channel, note, sound, _ -> notes += Triple(channel, note, sound) }
        ctl.sketchSelectTone(0)
        ctl.press(Action.EDIT)
        ctl.session.select(Selection(channel = 1, instrument = 7))
        assertFalse(ctl.follow())
        assertEquals(2, ctl.state.channel)
        ctl.release(Action.EDIT)
        ctl.follow()
        assertEquals(1, ctl.state.channel)
        assertEquals(7, ctl.state.instrument)
        assertEquals(listOf(Triple(2, 48, 1)), notes)
    }

    // ---- phrases ---------------------------------------------------------------------------------

    @Test fun downReachesThePhraseRowAndRightWalksItsControls() {
        val ctl = controller()
        ctl.normalise()
        assertEquals(1, ctl.state.sketchRow)
        ctl.tap(Action.DOWN)
        ctl.tap(Action.DOWN)
        assertEquals(3, ctl.state.sketchRow)
        ctl.tap(Action.DOWN)
        assertEquals(3, ctl.state.sketchRow)
        assertTrue(ctl.hints().any { it.text == "Phrase control" })
        repeat(ViewController.PHRASE_INSERT) { ctl.tap(Action.RIGHT) }
        assertEquals(ViewController.PHRASE_INSERT, ctl.state.sketchPhraseField)
        ctl.tap(Action.RIGHT)
        assertEquals(ViewController.PHRASE_INSERT, ctl.state.sketchPhraseField)
        ctl.tap(Action.UP)
        assertEquals(2, ctl.state.sketchRow)
        // The phrase is generated on first use from the stored seed, with the stored settings.
        assertEquals(PhraseSettings().length, ctl.sketchPhrase.length)
        assertNotNull(ctl.sketchPhrase.steps[0])
    }

    @Test fun phraseSettingsRegenerateDeterministicallyAndAnotherDrawsANewPhrase() {
        val ctl = controller()
        ctl.sketchSetPhraseField(ViewController.PHRASE_LENGTH)
        ctl.press(Action.EDIT)
        repeat(4) { ctl.tap(Action.RIGHT) }
        ctl.release(Action.EDIT)
        assertEquals(12, ctl.state.sketchPhraseSettings.length)
        assertEquals(12, ctl.sketchPhrase.length)
        val first = ctl.sketchPhrase
        val twin = controller()
        twin.state.sketchPhraseSettings = ctl.state.sketchPhraseSettings
        assertEquals(first, twin.sketchPhrase)
        ctl.sketchSetPhraseField(ViewController.PHRASE_ANOTHER)
        ctl.tap(Action.EDIT)
        assertNotEquals(1L, ctl.state.sketchPhraseSeed)
        assertEquals(12, ctl.sketchPhrase.length)
        // The keep toggles exclude each other, and Vary with Keep rhythm keeps the onsets.
        ctl.sketchSetPhraseField(ViewController.PHRASE_KEEP_PITCHES)
        ctl.tap(Action.EDIT)
        ctl.sketchSetPhraseField(ViewController.PHRASE_KEEP_RHYTHM)
        ctl.tap(Action.EDIT)
        assertTrue(ctl.state.sketchKeepRhythm)
        assertFalse(ctl.state.sketchKeepPitches)
        val before = ctl.sketchPhrase
        ctl.sketchSetPhraseField(ViewController.PHRASE_VARY)
        ctl.tap(Action.EDIT)
        assertEquals(before.onsets, ctl.sketchPhrase.onsets)
        assertFalse(ctl.session.canUndo)
    }

    @Test fun listeningLoopsThePhraseAtSongTempoAndNeverEditsTheSong() {
        val ctl = controller(ticksPerRow = 12)
        val session = ctl.session
        val before = session.song
        val phrase = Phrase(listOf(60, null, 64, null, 67, null, null, 72))
        ctl.state.sketchPhrase = phrase
        ctl.state.channel = 1
        ctl.state.instrument = 3
        val sequences = session.recordSequences()
        var releases = 0
        session.onReleaseAudition = { releases++ }
        ctl.sketchSetPhraseField(ViewController.PHRASE_RHYTHM)
        ctl.press(Action.AUDITION)
        // One pattern row per step: 12 ticks at 59.73 Hz.
        assertEquals(listOf(Sequence(1, phrase.steps, 3, Math.round(12 * 1000.0 / 59.73))), sequences)
        assertEquals(201L, sequences[0].stepMillis)
        assertTrue(ctl.sketchAuditioning)
        // Moving between controls keeps the loop running without restarting it.
        ctl.tap(Action.RIGHT)
        assertEquals(1, sequences.size)
        ctl.release(Action.AUDITION)
        assertEquals(1, releases)
        // Holding A listens on the strip itself, not on action controls.
        ctl.sketchSetPhraseField(ViewController.PHRASE_INSERT)
        ctl.press(Action.EDIT)
        assertEquals(1, sequences.size)
        // Moving while A is held makes it a hold, not a tap, so Insert is not activated on release.
        ctl.tap(Action.RIGHT)
        ctl.release(Action.EDIT)
        assertSame(before, session.song)
        ctl.sketchSetPhraseField(ViewController.PHRASE_STRIP)
        ctl.tap(Action.EDIT)
        assertEquals(2, sequences.size)
        assertFalse(ctl.sketchAuditioning)
        assertSame(before, session.song)
        assertFalse(session.canUndo)
    }

    @Test fun insertWritesAtTheSelectionAsOneUndoStepAndAdvancesIt() {
        val ctl = controller()
        val session = ctl.session
        session.setCell(0, 0, 5, Cell(note = 76, instrument = 2, effect = HugeRules.FX_SET_VOLUME, param = 0x08))
        session.setCell(0, 0, 6, Cell(note = 77, instrument = 2, effect = HugeRules.FX_PAN, param = 0x11))
        val before = session.song
        session.select(Selection(order = 0, row = 4, channel = 0))
        ctl.state.channel = 0
        ctl.state.instrument = 3
        ctl.state.sketchPhrase = Phrase(listOf(60, null, 62, null))
        ctl.sketchSetPhraseField(ViewController.PHRASE_INSERT)
        // A hold cancelled by a menu or focus change is not a tap: nothing is written.
        ctl.press(Action.EDIT)
        ctl.cancelHolds()
        assertSame(before, session.song)
        ctl.tap(Action.EDIT)
        val track = session.song.patterns[0].tracks[0]
        assertEquals(Cell(60, 3), track[4])
        assertEquals(Cell(effect = HugeRules.FX_SET_VOLUME, param = 0x08), track[5])
        assertEquals(Cell(62, 3, HugeRules.FX_PAN, 0x11), track[6])
        assertEquals(Cell(), track[7])
        assertEquals(8, session.selection.row)
        assertEquals(0, session.selection.channel)
        assertEquals("Insert phrase", session.undoLabel)
        assertTrue(session.message!!.startsWith("Inserted phrase"))
        session.undo()
        assertEquals(before, session.song)
    }

    @Test fun insertPhraseClipsToThePatternLengthAndKeepsOtherChannels() {
        val session = EditorSession(Songs.blank())
        session.setPatternLength(0, 10)
        session.setCell(0, 1, 9, Cell(86, 1))
        val steps = listOf(66, null, 68, 69)
        assertEquals(2, session.insertPhrase(0, 0, 8, steps, 1))
        val p = session.song.patterns[0]
        assertEquals(Cell(66, 1), p.tracks[0][8])
        assertEquals(Cell(), p.tracks[0][9])
        assertEquals(Cell(), p.tracks[0][10]) // hidden rows untouched
        assertEquals(Cell(86, 1), p.tracks[1][9])
        assertEquals("Insert phrase", session.undoLabel)
        // Through Sketch, the clipped insert leaves the selection on the last played row.
        val ctl = ViewController(session, ViewState(kind = ViewKind.SKETCH))
        session.select(Selection(order = 0, row = 8))
        ctl.state.sketchPhrase = Phrase(steps)
        ctl.sketchSetPhraseField(ViewController.PHRASE_INSERT)
        ctl.tap(Action.EDIT)
        assertEquals(9, session.selection.row)
        assertTrue(session.message!!.contains("cut at the pattern end"))
    }

    @Test fun insertInATunedSongSnapsNotesAndSkipsThoseWhoseBendHasNoSlot() {
        val nineteen = Scl.parse("19-TET\n19\n" + (1..19).joinToString("\n") { "%.4f".format(it * 1200.0 / 19) }, "19-TET")
        val session = EditorSession(Songs.blank())
        session.setTuning(nineteen.copy(root = 60))
        val map = session.tuningMap!!
        val bent = (60..95).first { map.pitches[map.nearest(it, 0)].detune > 0 }
        val bentPitch = map.pitches[map.nearest(bent, 0)]
        val plainPitch = map.pitches[map.nearest(60, 0)]
        assertEquals(0, plainPitch.detune)
        session.setCell(0, 0, 1, Cell(effect = HugeRules.FX_PAN, param = 0x11))
        session.setCell(0, 0, 3, Cell(note = 46, instrument = 2, effect = HugeRules.FX_SET_VOLUME, param = 0x0F))
        val blocked = session.song.patterns[0].tracks[0][3]
        val written = session.insertPhrase(0, 0, 0, listOf(bent, null, 60, bent), 1)
        assertEquals(3, written) // four rows, one note skipped
        val track = session.song.patterns[0].tracks[0]
        assertEquals(Cell(bentPitch.note, 1, HugeRules.FX_VIBRATO, bentPitch.detune), track[0])
        assertEquals(Cell(effect = HugeRules.FX_PAN, param = 0x11), track[1])
        assertEquals(Cell(plainPitch.note, 1), track[2])
        assertEquals(blocked, track[3])
        assertEquals("Insert phrase", session.undoLabel)
    }

    @Test fun fromCopiedTakesTheFirstCopiedChannel() {
        val ctl = controller()
        val session = ctl.session
        session.setCell(0, 1, 0, Cell(66, 1))
        session.setCell(0, 1, 3, Cell(69, 1))
        session.setCell(0, 2, 1, Cell(76, 1))
        session.copyBlock(0, Block(1, 2, 0, 5))
        val before = session.song
        ctl.sketchSetPhraseField(ViewController.PHRASE_FROM_COPIED)
        ctl.tap(Action.EDIT)
        assertEquals(listOf(66, null, null, 69, null, null), ctl.sketchPhrase.steps)
        assertEquals(6, ctl.state.sketchPhraseSettings.length)
        assertSame(before, session.song)
    }

    // ---- circle of fifths ------------------------------------------------------------------------

    @Test fun circleSelectionSetsTheLocalKeyWithoutTouchingTheSong() {
        val ctl = controller()
        val session = ctl.session
        val before = session.song
        val sequences = session.recordSequences()
        ctl.sketchSetField(ViewController.SKETCH_CHORDS_FIELD)
        ctl.press(Action.EDIT)
        ctl.tap(Action.RIGHT)
        ctl.release(Action.EDIT)
        assertTrue(ctl.state.sketchCircle)
        assertEquals(0 to false, ctl.state.sketchCircleKey to ctl.state.sketchCircleMinor) // on C major, the pattern key
        ctl.tap(Action.DOWN)
        ctl.tap(Action.RIGHT) // G major
        ctl.press(Action.AUDITION)
        assertEquals(listOf(55, 59, 62), sequences.last().steps)
        assertEquals(180L, sequences.last().stepMillis)
        ctl.release(Action.AUDITION)
        ctl.tap(Action.CLEAR) // its relative minor
        assertEquals(Scale(4, ScaleKind.NATURAL_MINOR), ctl.sketchCircleScale())
        ctl.press(Action.AUDITION)
        assertEquals(listOf(52, 55, 59), sequences.last().steps)
        ctl.release(Action.AUDITION)
        ctl.tap(Action.EDIT)
        assertEquals(Scale(4, ScaleKind.NATURAL_MINOR), ctl.state.sketchScale)
        assertEquals(Scale(4, ScaleKind.NATURAL_MINOR), ctl.sketchGuide.scale)
        assertEquals(Scale(0, ScaleKind.MAJOR), session.song.patterns[0].scale)
        assertSame(before, session.song)
        assertFalse(session.canUndo)
        // Y returns to the pattern key and the circle cursor follows it.
        ctl.tap(Action.MARK)
        assertNull(ctl.state.sketchScale)
        assertEquals(0 to false, ctl.state.sketchCircleKey to ctl.state.sketchCircleMinor)
    }

    @Test fun keysSitOnTheCircleByTheirThird() {
        val ctl = controller()
        assertEquals(1 to false, ctl.sketchCirclePlace(Scale(7, ScaleKind.MAJOR)))
        assertEquals(0 to true, ctl.sketchCirclePlace(Scale(9, ScaleKind.NATURAL_MINOR)))
        assertEquals(11 to true, ctl.sketchCirclePlace(Scale(2, ScaleKind.DORIAN)))
        assertEquals(6 to false, ctl.sketchCirclePlace(Scale(6, ScaleKind.LYDIAN)))
        assertNull(ctl.sketchCirclePlace(Scale.CHROMATIC))
        assertNull(ctl.sketchCirclePlace(Scale(0, ScaleKind.MIYAKO_BUSHI)))
        for (position in 0 until 12) {
            assertEquals(position, ViewController.circlePosition(ViewController.circleRoot(position)))
            assertEquals(position to false, ctl.sketchCirclePlace(ctl.sketchCircleScale(position, false)))
            assertEquals(position to true, ctl.sketchCirclePlace(ctl.sketchCircleScale(position, true)))
        }
    }
}
