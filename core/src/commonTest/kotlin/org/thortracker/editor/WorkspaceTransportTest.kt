package org.thortracker.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.thortracker.audio.PlaybackPosition
import org.thortracker.audio.TrackerAudio
import org.thortracker.audio.Transport
import org.thortracker.model.Song
import org.thortracker.model.Songs

class WorkspaceTransportTest {
    private fun workspace() = Workspace(listOf(ViewState(kind = ViewKind.PATTERN, pinned = false), ViewState(kind = ViewKind.KEYBOARD)))

    @Test fun aViewChordShowsTheViewAndTheSecondPressRestoresViewAndPinning() {
        val w = workspace()
        assertTrue(w.showView(ViewKind.SONG, ViewKind.entries.toSet()))
        assertEquals(ViewKind.SONG, w.focused.kind)
        assertTrue(w.focused.pinned)
        assertTrue(w.showView(ViewKind.SONG, ViewKind.entries.toSet()))
        assertEquals(ViewKind.PATTERN, w.focused.kind)
        assertFalse(w.focused.pinned, "the pane followed the selection before the chord")
        assertFalse(w.showView(ViewKind.PATTERN, ViewKind.entries.toSet()), "nothing remembered: the chord does nothing")
    }

    @Test fun swapCarriesEachPanesChordMemory() {
        val w = workspace()
        w.showView(ViewKind.INSTRUMENT, ViewKind.entries.toSet())                 // pane 0: Pattern → Instrument
        w.swap(0, 1)
        assertEquals(ViewKind.KEYBOARD, w.panes[0].kind)
        w.focus = 1
        assertTrue(w.showView(ViewKind.INSTRUMENT, ViewKind.entries.toSet()))      // pane 1 is the swapped Instrument: back to Pattern
        assertEquals(ViewKind.PATTERN, w.panes[1].kind)
        w.focus = 0
        assertFalse(w.showView(ViewKind.KEYBOARD, ViewKind.entries.toSet()))
    }

    @Test fun focusStaysInsideThePanes() {
        val w = workspace()
        w.focus = 5
        assertEquals(1, w.focus)
        w.focus = -1
        assertEquals(0, w.focus)
    }

    @Test fun thePlaceToPlayIsTheFocusedPatternPaneOrTheSelection() {
        val w = workspace()
        w.panes[0].order = 3; w.panes[0].row = 7
        val selection = Selection(order = 1, row = 2, channel = 0)
        assertEquals(3 to 7, w.activePlace(selection))
        w.focus = 1
        assertEquals(1 to 2, w.activePlace(selection))
    }

    private class FakeAudio : TrackerAudio {
        var song: Song? = null
        var played: Pair<Int, Int>? = null
        var playing = false
        var heardOrder = 0
        override fun update(song: Song) { this.song = song }
        override fun play(order: Int, row: Int) { played = order to row; playing = true; heardOrder = order }
        override fun stop() { playing = false }
        override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) {}
        override fun releaseAudition() {}
        override fun position() = PlaybackPosition(playing, heardOrder, 0)
        override fun error(): String? = null
        override fun close() {}
    }

    private fun transport(): Triple<EditorSession, Transport, FakeAudio> {
        val session = EditorSession(Songs.demo())        // orders 0-4 play patterns 0, 1, 2, 1, 2
        val audio = FakeAudio()
        val t = Transport(session)
        session.onSongChanged = { t.songChanged(it) }
        t.audio = audio
        return Triple(session, t, audio)
    }

    @Test fun playbackSkipsParkedOrdersAndPositionsMapBack() {
        val (session, t, audio) = transport()
        assertTrue(session.toggleParked(1))
        t.start(2, 5)
        assertEquals(1 to 5, audio.played, "document order 2 is the second played order")
        assertEquals(2, t.position()!!.order)
        t.toggle(0, 0)
        assertFalse(t.requested)
        assertFalse(audio.playing)
    }

    @Test fun theLoopPlaysOneOrderAndFollowsItsPatternThroughOrderEdits() {
        val (session, t, audio) = transport()
        t.toggleLoop(2)                                    // armed while stopped
        assertEquals(listOf(2), audio.song!!.orders)
        t.start(2, 0)
        assertEquals(0 to 0, audio.played)
        assertTrue(t.position()!!.looping)
        assertTrue(session.insertOrder(0, 0))              // order 2's pattern is now at order 3
        assertEquals(3, t.loopOrder)
        assertEquals(3, t.position()!!.order)
        session.undo()
        assertEquals(2, t.loopOrder)
        t.toggleLoop(2)                                    // off while playing: plays on through the song
        assertEquals(-1, t.loopOrder)
        assertEquals(session.song.orders, audio.song!!.orders)
        assertTrue(audio.playing)
    }

    @Test fun startingOnAParkedOrderLoopsItUntilStopped() {
        val (session, t, audio) = transport()
        session.toggleParked(3)
        t.start(3, 0)
        assertEquals(3, t.loopOrder)
        assertEquals(listOf(1), audio.song!!.orders)
        t.stop()
        assertEquals(-1, t.loopOrder)
        assertEquals(session.song.orders, audio.song!!.orders)
    }

    @Test fun turningOffTheLoopOfAParkedOrderStopsPlayback() {
        val (session, t, audio) = transport()
        session.toggleParked(3)
        t.toggleLoop(3)
        t.start(3, 0)
        t.toggleLoop(3)
        assertFalse(t.requested)
        assertFalse(audio.playing)
    }
}
