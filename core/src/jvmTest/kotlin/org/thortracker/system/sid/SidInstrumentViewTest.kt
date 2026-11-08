package org.thortracker.system.sid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.sid.SidInstrumentView.Page
import org.thortracker.system.sid.SidInstrumentView.Row

/** The SID instrument editor, Song settings, key on and the filter owner, driven through controller actions. */
class SidInstrumentViewTest {
    private val session = EditorSession(SidSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()
    private var releases = 0

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        session.onReleaseAudition = { releases++ }
    }

    private fun view(kind: ViewKind = ViewKind.INSTRUMENT, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = kind).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.page(p: Page, row: Row) {
        state.envelope = p.ordinal
        state.instField = SidInstrumentView.rows(state).indexOf(row)
    }

    private val sound get() = session.song.sid.instruments[0]

    @Test
    fun presetsMakeABlankSlotMusicalAndSoundWhileEditing() {
        val v = view { instrument = 1; listenChannel = 1 }
        v.page(Page.ENVELOPE, Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals("Saw lead", sound.name)
        assertEquals(0, SidPresets.indexOf(sound, 1))
        // Holding Edit sounds the sound on the Listen voice, and the change restarts it; release stops it.
        assertTrue(auditions.size >= 2)
        assertEquals(1, auditions.last().first)
        assertTrue(releases > 0)
        // The filter bass routes the Listen voice through the filter.
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals("Filter bass", sound.name)
        assertEquals(2, sound.filter.rows[0].right and 7)
        assertTrue(sound.filter.at > 0)
        // One hold, one undo step.
        session.undo()
        assertEquals("Saw lead", sound.name)
    }

    @Test
    fun adsrRowsEditTheEnvelope() {
        val v = view { instrument = 1 }
        v.page(Page.ENVELOPE, Row.ATTACK)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.UP)
        assertEquals(6, sound.attack)
        v.tap(Action.DOWN) // decay
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(8, sound.decay)
        v.tap(Action.CLEAR)
        assertEquals(SidInstrument.DEFAULT.decay, sound.decay)
        v.tap(Action.DOWN); v.tap(Action.DOWN) // release
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(0, sound.release)
    }

    @Test
    fun waveProgramIsEditedRowByRowWithStableJumps() {
        val v = view { instrument = 1 }
        v.page(Page.WAVE, Row.STEPS)
        assertEquals(1, sound.wave.rows.size)
        // Column 1 is the shape: saw → saw+tri? No, one step up the shape list (Tri+Saw).
        v.tap(Action.RIGHT)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0x31, sound.wave.rows[0].left)
        // Gate bit column: Edit tap switches it.
        v.tap(Action.RIGHT)
        v.tap(Action.EDIT)
        assertEquals(0x30, sound.wave.rows[0].left)
        v.tap(Action.EDIT)
        // Append two rows past the end, then make the last a jump to row 1.
        v.tap(Action.DOWN)
        v.tap(Action.EDIT)
        v.tap(Action.DOWN)
        v.tap(Action.EDIT)
        assertEquals(3, sound.wave.rows.size)
        assertEquals(2, v.state.envStep)
        v.state.programField = 0
        v.hold(Action.EDIT, Action.LEFT) // kind: Wave → Jump (wraps backwards)
        assertTrue(sound.wave.rows[2].isJump)
        assertEquals(1, sound.wave.rows[2].right)
        // Note column of row 2: +12 semitones.
        v.tap(Action.UP)
        v.state.programField = SidPrograms.fields(SidTables.WAVE, sound.wave.rows[1], 3).indexOfFirst { it.label == "Note" }
        v.hold(Action.EDIT, Action.UP)
        assertEquals(12, SidTables.relativeOf(sound.wave.rows[1].right))
        // Inserting before row 1 keeps the jump on the same row.
        v.tap(Action.UP)
        v.tap(Action.INSERT_ROW)
        assertEquals(4, sound.wave.rows.size)
        assertEquals(2, sound.wave.rows[3].right)
        // Deleting it again restores the target.
        v.tap(Action.CLEAR)
        assertEquals(1, sound.wave.rows[2].right)
        assertNull((0 until 3).firstNotNullOfOrNull { SidTables.rowProblem(SidTables.WAVE, sound.wave.rows, it) })
    }

    @Test
    fun editingOneProgramKeepsTheOthersOnTheirRows() {
        val v = view { instrument = 2 }
        v.page(Page.ENVELOPE, Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT) // PWM pad on slot 2
        val pulseAt = session.song.sid.instruments[1].pulse.at
        val otherWaveAt = session.song.sid.instruments[2].wave.at
        v.state.instrument = 1
        v.page(Page.WAVE, Row.LENGTH)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(4, sound.wave.rows.size)
        assertEquals(pulseAt, session.song.sid.instruments[1].pulse.at)
        assertEquals(otherWaveAt, session.song.sid.instruments[2].wave.at)
    }

    @Test
    fun tablesPageOpensTheOwningProgram() {
        val v = view { instrument = 3 }
        v.page(Page.ENVELOPE, Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT) // PWM pad: a 4-row pulse program
        val at = session.song.sid.instruments[2].pulse.at
        v.state.instrument = 1
        v.page(Page.TABLES, Row.RAW)
        v.state.rawTable = SidTables.PULSE
        v.state.envStep = at // table row at + 1: the program's second row
        v.tap(Action.EDIT)
        assertEquals(3, v.state.instrument)
        assertEquals(Page.PULSE, SidInstrumentView.page(v.state))
        assertEquals(Row.STEPS, SidInstrumentView.row(v.state))
        assertEquals(1, v.state.envStep)
    }

    @Test
    fun songViewListsTheChipSettingsOnlyOnSid() {
        assertEquals(ViewState.SONG_HEADER_ITEMS, ViewState.songHeaderItems(GbSystem.blank()))
        assertEquals(ViewState.SONG_HEADER_ITEMS + 4, ViewState.songHeaderItems(session.song))
        val v = view(ViewKind.SONG) { songItem = ViewState.SONG_HEADER_ITEMS }
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(SidChip.MOS6581, session.song.sid.chip)
        v.tap(Action.CLEAR)
        assertEquals(SidChip.MOS8580, session.song.sid.chip)
        v.tap(Action.DOWN)
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(6, session.song.sid.multispeed)
        assertEquals(SidSystem.tickHz * 6, SidSystem.ticksPerSecond(session.song), 1e-9)
        // The orders start after the settings: the first order is still order 0.
        v.state.songItem = ViewState.songHeaderItems(session.song)
        assertEquals(0, v.songOrder)
    }

    @Test
    fun clearCyclesOffKeyOnAndEmpty() {
        val v = view(ViewKind.PATTERN) { channel = 0; row = 0; field = 0 }
        v.tap(Action.CLEAR)
        assertEquals(Cell.CUT, session.song.patterns[0].tracks[0][0].note)
        v.tap(Action.CLEAR)
        assertEquals(SidRules.KEY_ON, session.song.patterns[0].tracks[0][0].note)
        v.tap(Action.CLEAR)
        assertEquals(Cell.EMPTY, session.song.patterns[0].tracks[0][0].note)
    }

    @Test
    fun theFilterBelongsToTheLastVoiceThatStartedAFilterProgram() {
        val v = view { instrument = 4; listenChannel = 2 }
        v.page(Page.ENVELOPE, Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT) // Filter bass in slot 4
        session.setCell(0, 2, 8, Cell(note = 36, instrument = 4))
        session.setCell(0, 0, 16, Cell(note = 48, instrument = 1, effect = SidRules.FX_FILTER_PROGRAM, param = 4))
        session.setCell(0, 1, 24, Cell(effect = SidRules.FX_FILTER_CONTROL, param = 0))
        val song = session.song
        assertNull(SidFilterOwner.at(song, 0, 7))
        assertEquals(2, SidFilterOwner.at(song, 0, 8)?.voice)
        assertEquals(listOf(null, null, "FILTER"), SidSystem.sharedState(song, 0, 10).tags)
        assertEquals(0, SidFilterOwner.at(song, 0, 20)?.voice)
        assertTrue(SidFilterOwner.at(song, 0, 30)?.off == true)
        assertNotEquals(SidSystem.sharedState(song, 0, 8).lines, SidSystem.sharedState(song, 0, 30).lines)
        assertNull(GbSystem.sharedState(GbSystem.blank(), 0, 0))
    }

    @Test
    fun pulseTraceSweepsAndLoops() {
        val rows = listOf(SidRow.pulseWidth(0x400), SidRow.modulate(4, 0x10), SidRow.modulate(4, -0x10), SidRow.jump(2))
        val t = SidPrograms.trace(SidTables.PULSE, rows, 12, 0x800)
        assertEquals(listOf(0x400, 0x410, 0x420, 0x430, 0x440, 0x430, 0x420, 0x410, 0x400, 0x410), t.take(10).toList())
        assertFalse(SidPrograms.trace(SidTables.FILTER, listOf(SidRow.cutoff(0x20)), 3, 0x80).any { it != 0x20 })
    }
}
