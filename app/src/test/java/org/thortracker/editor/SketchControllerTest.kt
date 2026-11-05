package org.thortracker.editor

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.model.Cell
import org.thortracker.model.Scale
import org.thortracker.model.ScaleKind
import org.thortracker.model.Songs

class SketchControllerTest {
    private fun controller(scale: Scale = Scale(0, ScaleKind.MAJOR)): ViewController {
        val song = Songs.blank().let { it.copy(patterns = listOf(it.patterns[0].copy(scale = scale))) }
        return ViewController(EditorSession(song), ViewState(kind = ViewKind.SKETCH))
    }

    private fun ViewController.tap(action: Action) { press(action); release(action) }

    @Test fun exploringAndListeningPreserveTheDocumentAndExistingRedo() {
        val ctl = controller()
        val session = ctl.session
        session.setCell(0, 0, 0, Cell(note = 12, instrument = 1))
        val edited = session.song
        session.undo()
        val before = session.song
        val selection = session.selection
        val notes = mutableListOf<Int>()
        val chords = mutableListOf<List<Int>>()
        var releases = 0
        session.onAudition = { _, note, _ -> notes += note }
        session.onAuditionSequence = { _, phrase, _ -> chords += phrase }
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
        assertEquals(listOf(listOf(12, 16, 19)), chords)
        assertEquals(listOf(12, 14), notes)
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
                assertTrue(ctl.sketchNote(index) in 0..Songs.NOTE_MAX)
                assertTrue(scale.contains(ctl.sketchNote(index)))
            }
            ctl.sketchGuide.chords.forEachIndexed { index, chord ->
                ctl.sketchSelectChord(index)
                val voiced = ctl.sketchChordNotes()
                assertTrue(voiced.all { it in 0..Songs.NOTE_MAX && scale.contains(it) })
                assertEquals(chord.intervals.map { it - chord.intervals[0] }, voiced.map { it - voiced[0] })
                assertEquals((root + chord.intervals[0]) % 12, voiced[0] % 12)
            }
        }
    }

    @Test fun chromaticCannotStartAnArpeggioButNotesStillPlayAndCancel() {
        val ctl = controller(Scale.CHROMATIC)
        val notes = mutableListOf<Int>()
        ctl.session.onAudition = { _, note, _ -> notes += note }
        ctl.session.onAuditionSequence = { _, _, _ -> fail("Chromatic has no diatonic chord") }
        ctl.tap(Action.EDIT)
        assertFalse(ctl.sketchAuditioning)
        ctl.sketchSelectTone(11)
        ctl.press(Action.AUDITION)
        assertEquals(listOf(23), notes)
        assertTrue(ctl.holding)
        ctl.cancelHolds()
        assertFalse(ctl.holding)
        assertFalse(ctl.sketchAuditioning)
        assertSame(Scale.CHROMATIC, ctl.sketchGuide.scale)
    }

    @Test fun followingAndChannelControlsNeverRouteTonalPreviewsToNoise() {
        val ctl = controller()
        ctl.session.select(Selection(channel = 3, bank = org.thortracker.model.InstrumentKind.NOISE, instrument = 4))
        ctl.follow(); ctl.normalise()
        assertEquals(0, ctl.state.channel)
        assertEquals(1, ctl.state.instrument)
        repeat(5) { ctl.tap(Action.NEXT_CHANNEL) }
        assertEquals(2, ctl.state.channel)
        val notes = mutableListOf<Triple<Int, Int, Int>>()
        ctl.session.onAudition = { channel, note, sound -> notes += Triple(channel, note, sound) }
        ctl.sketchSelectTone(0)
        ctl.press(Action.EDIT)
        ctl.session.select(Selection(channel = 1, instrument = 7))
        assertFalse(ctl.follow())
        assertEquals(2, ctl.state.channel)
        ctl.release(Action.EDIT)
        ctl.follow()
        assertEquals(1, ctl.state.channel)
        assertEquals(7, ctl.state.instrument)
        assertEquals(listOf(Triple(2, 12, 1)), notes)
    }
}
