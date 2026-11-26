package org.thortracker.system.studio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.EditorSession
import org.thortracker.editor.withCell
import org.thortracker.model.*
import org.thortracker.system.midi.*
import kotlin.math.pow

class StudioSongTest {
    @Test fun mixedProjectKeepsBothLanesAcrossPortableLoadHistoryAndRouteChanges() {
        val base = StudioSound().system.blank().addStudioTrack()
        val sound = base.studio.withPatch(32, MonoPatch("Sub", tune = -12)).copy(tracks = listOf(
            base.studio.tracks[0], StudioTrack(MidiTrack(2, 9, "usb:fixture", program = 10), StudioTarget.USB_MIDI)))
        val song = base.copy(sound = sound, patterns = listOf(base.patterns.single().withCell(0, 0, Cell(45, 32))
            .withCell(1, 1, Cell(52, 1))))
        val session = EditorSession(ProjectCodec.decode(ProjectCodec.encode(song)))
        assertEquals(song, session.song)
        val image = ByteArray(StudioEngine.imageSize)
        assertTrue(assertThrows(IllegalArgumentException::class.java) { StudioEngine.lower(song, image) }.message!!.contains("Android"))
        StudioEngine.lowerInternal(song, image)
        assertEquals(1, image[21].toInt()); assertEquals(0, image[27].toInt())
        session.apply("Remove first") { it.removeStudioTrack(0) }
        assertEquals("usb:fixture", session.song.studio.tracks.single().midi.port)
        assertEquals(52, session.song.patterns.single().tracks.single()[1].note)
        session.undo(); assertEquals(song, session.song)
        session.redo(); session.apply("Add") { it.addStudioTrack() }
        assertEquals(listOf(2, 3), session.song.studio.tracks.map { it.midi.id })
        assertEquals("Sub", session.song.studio.patch(32)?.name)
    }

    // Decode the native event stream independently; compare the offsets, note order, tie and gain
    // that the renderer actually gets against the same musical events the USB adapter schedules.
    @Test fun loweredTimingFollowsMusicalRowsAndUsesTheTunedTable() {
        val track = MidiTrack(13, 7, gate = 80)
        val cell = Cell(49, 4, 7, 3).withEffect(1, 6, 63).withEffect(2, 11, 120).withEffect(3, 12, 1)
        val tuning = Tuning("Unequal", listOf(125.0, 1200.0), root = 48)
        val song = Song(patterns = listOf(Pattern.blank(1).withCell(0, 0, cell)), orders = listOf(0), tuning = tuning,
            sound = StudioSound(tracks = listOf(StudioTrack(track)), nextId = 14))
        val image = ByteArray(StudioEngine.imageSize); StudioEngine.lower(song, image)
        fun b(i: Int) = image[i].toInt() and 255
        fun u16(i: Int) = b(i) or (b(i+1) shl 8)
        val hz = 64 + 4*49
        assertEquals(440.0 * 2.0.pow((48 + 1.25 - 57) / 12), Float.fromBits(u16(hz) or (u16(hz+2) shl 16)).toDouble(), .001)
        val stream = StudioEngine.ROW_INDEX + (Songs.ROWS + 1) * 2
        val events = (0 until u16(12)).map { stream + 8*it }
        val expected = musicalRow(track, cell, 65536)
        assertEquals(4, b(events.first()+4)) // select patch before attack, not USB's channel number
        val strikes = events.filter { b(it+3) == 1 }
        val attacks = expected.filter { it.event is MusicalEvent.Attack }
        assertEquals(attacks.map { it.offset.toInt() }, strikes.map { u16(it) })
        for ((at,e) in strikes.zip(attacks)) {
            val a = e.event as MusicalEvent.Attack
            assertEquals(a.pitch, b(at+4)); assertEquals(a.strength, b(at+5)/255.0, .002)
            assertEquals(120, b(at+6)); assertEquals(a.tie, b(at+7) == 1)
        }
        assertEquals(listOf(0, 0, 0, 1), strikes.map { b(it+7) })
        assertEquals(expected.filter { it.event is MusicalEvent.Release }.map { it.offset.toInt() },
            events.filter { b(it+3) == 2 }.map { u16(it) })
        assertEquals(3, events.count { b(it+3) == 2 })
    }

    @Test fun unsupportedDestinationActionsArePreservedButNeverPlayedSilently() {
        val state = StudioSound(listOf(StudioTrack(MidiTrack(1, 0), StudioTarget.USB_MIDI)))
        val song = Song(sound = state, patterns = listOf(Pattern.blank(1).withCell(0, 0, Cell(48, effect = 12, param = 1))), orders = listOf(0))
        assertEquals(song, ProjectCodec.decode(ProjectCodec.encode(song)))
        assertTrue(song.system.errors(song).single().contains("require Mono"))
        val tuned = song.copy(tuning = Tuning("Fifths", listOf(702.0, 1200.0)))
        assertTrue(tuned.system.errors(tuned).single().contains("tuning"))
    }
}
