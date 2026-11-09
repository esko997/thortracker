package org.thortracker.system.scc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.scc.SccInstrumentView.Row

/** The SCC Instrument and Wave views driven only through controller actions, and their edits. */
class SccEditorTest {
    private val session = EditorSession(SccSystem.blank())
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

    private fun ViewController.goTo(row: Row) {
        state.instField = SccInstrumentView.rows(state).indexOf(row)
        assertTrue("$row is on this page", state.instField >= 0)
    }

    private fun sound(slot: Int) = session.song.sccInstrument(slot)

    private fun wave(number: Int) = session.song.scc.wave(number)!!

    /** The first empty slot of the blank song (presets fill the first slots, the rest are empty). */
    private val emptySlot = session.song.scc.instruments.indexOfFirst { it == null } + 1

    @Test
    fun slotsAreNavigatedAndEmptySlotsStartFromAPreset() {
        assertTrue(emptySlot > 1)
        val v = view(ViewKind.INSTRUMENT) { instrument = emptySlot }
        assertNull(sound(emptySlot))
        // Preset: Edit+→ puts the first preset into the empty slot, again the next one.
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(SccPresets.ALL[0].instrument, sound(emptySlot))
        assertEquals("Preset ${SccPresets.ALL[0].name}", session.undoLabel)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(SccPresets.ALL[1].instrument, sound(emptySlot))
        assertEquals(1, SccInstrumentView.presetIndex(sound(emptySlot)))
        // Clear on Preset: the default sound, named after the slot.
        v.tap(Action.CLEAR)
        assertEquals(defaultSccInstrument(emptySlot), sound(emptySlot))
        assertEquals("Sound ${EditorSession.hex2(emptySlot)}", sound(emptySlot)!!.name)
        // Slot row: ←→ one slot, Edit+↓ eight; Edit on an empty slot makes a sound, Clear empties it.
        v.goTo(Row.SLOT)
        v.tap(Action.RIGHT)
        assertEquals(emptySlot + 1, v.state.instrument)
        v.hold(Action.EDIT, Action.DOWN)
        val far = emptySlot + 1 + SccInstrumentView.COARSE
        assertEquals(far, v.state.instrument)
        assertEquals(far, session.selection.instrument)
        assertNull(sound(far))
        v.tap(Action.EDIT)
        assertEquals("Sound ${EditorSession.hex2(far)}", sound(far)!!.name)
        assertEquals("New sound", session.undoLabel)
        v.tap(Action.CLEAR)
        assertNull(sound(far))
        assertEquals("Empty sound", session.undoLabel)
        // Any edit of an empty slot starts from the default sound.
        v.goTo(Row.RESTART)
        v.tap(Action.EDIT)
        assertEquals(!defaultSccInstrument(far).restart, sound(far)!!.restart)
        assertEquals("Set restart", session.undoLabel)
    }

    @Test
    fun envelopeStepsLoopAndReleaseAreEditedStepByStep() {
        val slot = emptySlot
        session.setSccInstrument(slot, SccInstrument(name = "T"), "test")
        val v = view(ViewKind.INSTRUMENT) { instrument = slot }
        v.goTo(Row.STEPS)
        // Volume starts as one step of 15: lower it, then carry it along three more steps.
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals("Set volume", session.undoLabel)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(listOf(13), sound(slot)!!.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(13, 13, 13, 13), sound(slot)!!.volume.values)
        assertEquals(3, v.state.envStep)
        v.hold(Action.EDIT, *Array(20) { Action.DOWN })
        assertEquals(listOf(13, 13, 13, 0), sound(slot)!!.volume.values)
        // One hold is one undo step.
        session.undo()
        assertEquals(listOf(13, 13, 13, 13), sound(slot)!!.volume.values)
        // Clear deletes the step under the cursor; past the end a tap appends the last value.
        v.tap(Action.LEFT)
        v.tap(Action.CLEAR)
        assertEquals(listOf(13, 13, 13), sound(slot)!!.volume.values)
        repeat(3) { v.tap(Action.RIGHT) }
        assertEquals(3, v.state.envStep)
        v.tap(Action.EDIT)
        assertEquals(listOf(13, 13, 13, 13), sound(slot)!!.volume.values)
        assertEquals("Add volume step", session.undoLabel)

        // Loop from step 0, then a release: the loop stays before it.
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, sound(slot)!!.volume.loop)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesEnvelope(listOf(13, 13, 13, 13), 0, 1), sound(slot)!!.volume)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, sound(slot)!!.volume.loop)
        // The SCC's release needs no loop: the note holds the step before it until OFF.
        v.tap(Action.CLEAR)
        assertEquals(NesEnvelope(listOf(13, 13, 13, 13), -1, 1), sound(slot)!!.volume)
        assertEquals("Clear volume loop", session.undoLabel)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, sound(slot)!!.volume.release)
        v.tap(Action.CLEAR)
        assertEquals(-1, sound(slot)!!.volume.release)

        // Arpeggio has no release row; pitch has the mode row and stops at -64.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(SccEnvelopeKind.ARPEGGIO, SccInstrumentView.kind(v.state))
        assertFalse(Row.RELEASE in SccInstrumentView.rows(v.state))
        assertFalse(Row.MODE in SccInstrumentView.rows(v.state))
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(SccEnvelopeKind.PITCH, SccInstrumentView.kind(v.state))
        v.goTo(Row.MODE)
        v.tap(Action.EDIT)
        assertTrue(sound(slot)!!.pitchRelative)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(80) { Action.DOWN })
        assertEquals(listOf(-64), sound(slot)!!.pitch.values)
        assertEquals(-1, sound(slot)!!.pitch.release)
        // Length on an empty envelope grows from the default value; Clear on Length empties it again.
        v.tap(Action.PREV_CHANNEL)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(0, 0), sound(slot)!!.arpeggio.values)
        v.tap(Action.CLEAR)
        assertEquals(NesEnvelope(), sound(slot)!!.arpeggio)
    }

    @Test
    fun wavePageValuesAreWaveNumbersOneToSixtyFour() {
        val slot = emptySlot
        session.setSccInstrument(slot, SccInstrument(name = "W"), "test")
        val v = view(ViewKind.INSTRUMENT) { instrument = slot; envelope = SccEnvelopeKind.WAVE.ordinal }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(100) { Action.UP })
        assertEquals(listOf(SccSound.WAVES), sound(slot)!!.wave.values)
        assertEquals(SccSound.WAVES - 1, session.selection.wave) // a following Wave view shows the step's wave
        v.hold(Action.EDIT, *Array(200) { Action.DOWN })
        assertEquals(listOf(1), sound(slot)!!.wave.values)
        assertEquals(0, session.selection.wave)
        // The wave envelope keeps at least one step: neither Clear nor Length removes it.
        v.tap(Action.CLEAR)
        assertEquals(listOf(1), sound(slot)!!.wave.values)
        assertTrue(session.message!!.contains("at least one wave"))
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals(listOf(1), sound(slot)!!.wave.values)
        v.tap(Action.CLEAR)
        assertEquals(listOf(1), sound(slot)!!.wave.values)
        // Drawn along: wave 3 for three steps, looping on the last; the selection follows the cursor step.
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(3, 3, 3), sound(slot)!!.wave.values)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(2, sound(slot)!!.wave.loop)
        assertEquals(-1, sound(slot)!!.wave.release)
        assertFalse(Row.RELEASE in SccInstrumentView.rows(v.state))
        // A pasted volume envelope is clamped to wave numbers.
        assertTrue(SccEnvelopeKind.WAVE.fixed(NesEnvelope(listOf(0, 15, 99), 0, 2)).let { it.values == listOf(1, 15, 64) && it.release == -1 })
        assertEquals(listOf(1), SccEnvelopeKind.WAVE.fixed(NesEnvelope()).values)
    }

    @Test
    fun wavesAreDrawnWithThePenAndShapedByTools() {
        val number = 31
        val v = view(ViewKind.WAVE) { wave = number - 1; waveRow = SccWaveView.GRID_ROW; waveCol = 0; waveLevel = 0 }
        // ↑ moves the pen coarsely, Clear+↑ by one level.
        v.tap(Action.UP)
        assertEquals(SccWaveView.COARSE, v.state.waveLevel)
        v.hold(Action.CLEAR, Action.UP)
        assertEquals(SccWaveView.COARSE + 1, v.state.waveLevel)
        assertEquals(List(SccWave.SAMPLES) { 0 }, wave(number).samples) // a used Clear clears nothing
        // Holding Edit draws at the pen: press paints, → carries on, ↑ fine-tunes.
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(9, wave(number).samples[0])
        assertEquals(10, wave(number).samples[1])
        assertEquals("Draw wave", session.undoLabel)
        v.hold(Action.CLEAR, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(7, v.state.waveLevel)
        v.tap(Action.CLEAR)
        assertEquals(0, wave(number).samples[1])
        // Levels stop at -128..127, and ↑ at the top reaches the tools.
        v.state.waveLevel = SccWave.LEVELS.first
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(-128, wave(number).samples[1])
        v.state.waveLevel = SccWave.LEVELS.last - 3
        v.tap(Action.UP)
        assertEquals(SccWave.LEVELS.last, v.state.waveLevel)
        v.tap(Action.UP)
        assertEquals(SccWaveView.TOOLS_ROW, v.state.waveRow)

        fun useTool(t: SccWaveView.Tool) { v.state.waveCol = t.ordinal; v.tap(Action.EDIT) }
        useTool(SccWaveView.Tool.SINE)
        assertEquals(SccWaveShapes.sine(), wave(number).samples)
        assertEquals("Sine wave", session.undoLabel)
        useTool(SccWaveView.Tool.INVERT)
        assertEquals(SccWaveShapes.invert(SccWaveShapes.sine()), wave(number).samples)
        useTool(SccWaveView.Tool.SHIFT_RIGHT)
        assertEquals(SccWaveShapes.rotate(SccWaveShapes.invert(SccWaveShapes.sine()), 1), wave(number).samples)
        useTool(SccWaveView.Tool.PULSE)
        assertEquals(SccWaveShapes.pulse(SccWaveView.DEFAULT_PULSE), wave(number).samples)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(SccWaveShapes.pulse(SccWaveView.DEFAULT_PULSE + 1), wave(number).samples)
        useTool(SccWaveView.Tool.NOISE)
        val noise = wave(number).samples
        assertTrue(noise.all { it in SccWave.LEVELS } && noise.size == SccWave.SAMPLES)
        useTool(SccWaveView.Tool.SAW)
        assertEquals(SccWaveShapes.saw(), wave(number).samples)
        // Edit+← on a tool without a setting changes nothing and its release applies no tool.
        v.state.waveCol = SccWaveView.Tool.SQUARE.ordinal
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(SccWaveShapes.saw(), wave(number).samples)

        // Use in sound: the selected sound plays this wave, and Listen sounds it.
        useTool(SccWaveView.Tool.USE)
        assertEquals(listOf(number), sound(session.selection.instrument)!!.wave.values)
        assertEquals(session.selection.instrument, SccWaveView.soundUsingWave(v, number))
        v.tap(Action.AUDITION)
        assertEquals(session.selection.instrument, auditions.last().third)

        // Name above the tools.
        v.tap(Action.UP)
        assertEquals(SccWaveView.NAME_ROW, v.state.waveRow)
        v.hold(Action.EDIT, Action.UP)
        assertTrue(wave(number).name.isNotEmpty())
        assertEquals("Rename wave", session.undoLabel)
        v.tap(Action.DOWN)
        assertEquals(SccWaveView.TOOLS_ROW, v.state.waveRow)

        // Copy, then paste into the next wave; R1 publishes it.
        v.tap(Action.COPY)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(number, v.state.wave)
        assertEquals(number, session.selection.wave)
        v.tap(Action.PASTE)
        assertEquals(wave(number), wave(number + 1))
        assertEquals("Paste wave", session.undoLabel)
        v.tap(Action.CUT)
        assertEquals(SccWave(), wave(number + 1))
    }

    @Test
    fun soundsAndEnvelopesAreCopiedPastedCutAndDuplicated() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 1 }
        val first = sound(1)!!
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        assertEquals(SccClip.Sound(first), session.clipboard)
        v.state.instrument = emptySlot
        v.tap(Action.PASTE)
        assertEquals(first, sound(emptySlot))
        assertEquals("Paste sound", session.undoLabel)
        // An envelope copied from the volume page pastes into arpeggio, without its release.
        session.setSccInstrument(1, first.copy(volume = NesEnvelope(listOf(15, 9, 4), 0, 1)), "test")
        v.state.instrument = 1
        v.goTo(Row.STEPS)
        v.tap(Action.COPY)
        assertTrue(session.clipboard is SccClip.Envelope)
        v.state.instrument = emptySlot
        v.tap(Action.NEXT_CHANNEL)
        v.goTo(Row.STEPS)
        v.tap(Action.PASTE)
        assertEquals(NesEnvelope(listOf(15, 9, 4), 0, -1), sound(emptySlot)!!.arpeggio)
        // Cut on an envelope row empties that envelope; on the slot the sound.
        v.tap(Action.CUT)
        assertEquals(NesEnvelope(), sound(emptySlot)!!.arpeggio)
        v.goTo(Row.SLOT)
        v.tap(Action.CUT)
        assertNull(sound(emptySlot))
        assertEquals("Cut sound", session.undoLabel)
        // Duplicate copies the sound into the next slot and follows it.
        v.state.instrument = 1
        v.tap(Action.DUPLICATE)
        assertEquals(2, v.state.instrument)
        assertEquals(sound(1)!!.copy(name = sound(1)!!.name + "+"), sound(2))
        // A wave clip is no sound.
        session.hold(SccClip.Wave(SccWave("x")))
        assertFalse(session.pasteSccSound(3, SccEnvelopeKind.VOLUME))
    }

    @Test
    fun theSccsSharedWaveIsExplainedAndTheSccPlusHasNone() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 1 }
        v.goTo(Row.LISTEN)
        repeat(3) { v.tap(Action.RIGHT) }
        assertEquals(3, v.state.listenChannel)
        assertTrue(session.message!!.contains("share one wave RAM"))
        v.tap(Action.AUDITION)
        assertEquals(Triple(3, v.defaultNote(), 1), auditions.last())
        // Waves SCC 4 or SCC 5 play carry the note in the Wave view.
        session.setCell(0, 3, 0, Cell(note = 48, instrument = 1))
        val shared = SccWaveView.sharedWaves(session.song)
        assertEquals(sound(1)!!.waves, shared)
        assertNotNull(SccWaveView.sharedNote(session.song, shared.first()))
        session.setCell(0, 4, 4, Cell(note = 48).withEffect(1, SccRules.FX_WAVE, 0x2A))
        assertTrue(0x2A in SccWaveView.sharedWaves(session.song))

        val plus = EditorSession(SccPlusSystem.blank())
        val w = ViewController(plus, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1 })
        w.state.instField = SccInstrumentView.rows(w.state).indexOf(Row.LISTEN)
        repeat(3) { w.tap(Action.RIGHT) }
        assertNull(SccInstrumentView.channelNote(plus.song, w.state.listenChannel))
        plus.setCell(0, 3, 0, Cell(note = 48, instrument = 1))
        assertTrue(SccWaveView.sharedWaves(plus.song).isEmpty())

        // Help explains both chips' wave RAM.
        val help = SccInstrumentView.helpSections(Keymap.DEFAULT)
        val text = help.joinToString("\n") { (head, rows) -> head + "\n" + rows.joinToString("\n") { "${it.first} ${it.second}" } }
        assertTrue(help.any { it.first == "Waves" })
        assertTrue(text.contains("SCC 4 and SCC 5 share one"))
        assertTrue(text.contains("claimed last") || text.contains("last started"))
        assertTrue(text.contains("Every channel has its own wave RAM"))
    }

    @Test
    fun emptySlotsAreNotAuditioned() {
        val v = view(ViewKind.INSTRUMENT) { instrument = emptySlot }
        v.tap(Action.AUDITION)
        assertTrue(auditions.isEmpty())
        assertTrue(session.message!!.contains("empty"))
    }
}
