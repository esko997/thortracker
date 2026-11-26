package org.thortracker.system.midi

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.EditorSession
import org.thortracker.model.*

class MidiSongTest {
    @Test fun topologyHistoryAndUnsupportedProject() {
        val blank = MidiSound().system.blank()
        val session = EditorSession(blank.copy(patterns = blank.patterns + blank.patterns))
        session.apply("Add") { it.addMidiTrack() }
        val two = session.song
        assertEquals(listOf(0, 1), (two.sound as MidiSound).tracks.map { it.channel })
        assertTrue(two.patterns.all { it.tracks.size == 2 })
        session.apply("Remove") { it.removeMidiTrack(0) }
        assertEquals(2, (session.song.sound as MidiSound).tracks.single().id)
        session.undo()
        assertEquals(two, session.song)
        session.redo()
        val text = ProjectCodec.encode(session.song)
        assertEquals(session.song, ProjectCodec.decode(text, androidMidi = true))
        assertTrue(assertThrows(ProjectFormatException::class.java) { ProjectCodec.decode(text) }.message!!.contains("Android"))
    }

    @Test fun timedColumnsAndGate() {
        val track = MidiTrack(4, 0, gate = 100)
        val cell = Cell(note = 0, effect = 5, param = 2).withEffect(1, 7, 2)
            .withEffect(2, 6, 128).withEffect(3, 0, 0x47)
        val trace = musicalRow(track, cell, 6000)
        val attacks = trace.filter { it.event is MusicalEvent.Attack }
        assertEquals(listOf(3000L, 4000L, 5000L), attacks.map { it.offset })
        assertEquals(listOf(0, 4, 7), attacks.map { (it.event as MusicalEvent.Attack).pitch })
        assertTrue(attacks.zipWithNext().all { (a, b) -> (a.event as MusicalEvent.Attack).strength > (b.event as MusicalEvent.Attack).strength })
        assertEquals(3, trace.count { it.event is MusicalEvent.Release })
        assertTrue(trace.all { it.offset in 0L until 6000L && it.trackId == 4 })
        val automation = musicalRow(track, Cell(effect = 1, param = 100).withEffect(1, 2, 50)
            .withEffect(2, 4, 127).withEffect(3, 9, 64), 6000)
        assertEquals(MusicalEvent.Pitch(-0.5), automation.last { it.event is MusicalEvent.Pitch }.event)
        assertEquals(MusicalEvent.Expression(0.0), automation.last { it.event is MusicalEvent.Expression }.event)
        assertTrue(automation.any { it.event == MusicalEvent.Parameter(0, 64 / 127.0) })
    }
}
