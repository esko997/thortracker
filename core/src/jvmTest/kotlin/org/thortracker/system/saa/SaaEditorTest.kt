package org.thortracker.system.saa

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
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.saa.SaaInstrumentView.Row

/** The SAA1099 Instrument view driven only through controller actions, and its edits. */
class SaaEditorTest {
    private val session = EditorSession(SamCoupeSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(setup: ViewState.() -> Unit = {}) = ViewController(session,
        ViewState(kind = ViewKind.INSTRUMENT).apply { soundPanel("saa").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        state.instField = SaaInstrumentView.rows(session.song, state).indexOf(row)
        assertTrue("$row is shown", state.instField >= 0)
    }

    private fun sound(slot: Int) = session.song.saaInstrument(slot)

    /** The first empty slot of the blank song (presets fill 01-12, the rest are empty). */
    private val emptySlot = session.song.saa.instruments.indexOfFirst { it == null } + 1

    private fun slot(name: String) = SaaPresets.ALL.indexOfFirst { it.name == name } + 1

    @Test
    fun slotsAreNavigatedAndEmptySlotsStartFromAPreset() {
        assertEquals(13, emptySlot)
        val v = view { instrument = emptySlot }
        assertNull(sound(emptySlot))
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(SaaPresets.ALL[0].instrument, sound(emptySlot))
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(SaaPresets.ALL[1].instrument, sound(emptySlot))
        assertEquals(1, SaaInstrumentView.presetIndex(sound(emptySlot)))
        // Stepping back past the first wraps to the last preset (a hardware-envelope sound adds its rows).
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals(SaaPresets.ALL.last().instrument, sound(emptySlot))
        assertTrue(Row.SHAPE in SaaInstrumentView.rows(session.song, v.state))
        // Clear on Preset: the default sound, named after the slot.
        v.goTo(Row.PRESET)
        v.tap(Action.CLEAR)
        assertEquals(defaultSaaInstrument(emptySlot), sound(emptySlot))
        // Slot row: ←→ one slot, Edit+↓ eight; Edit on an empty slot makes a sound, Clear empties it.
        v.goTo(Row.SLOT)
        v.tap(Action.RIGHT)
        assertEquals(emptySlot + 1, v.state.instrument)
        v.hold(Action.EDIT, Action.DOWN)
        val far = emptySlot + 1 + SaaInstrumentView.COARSE
        assertEquals(far, v.state.instrument)
        assertEquals(far, session.selection.instrument)
        assertNull(sound(far))
        v.tap(Action.EDIT)
        assertEquals(defaultSaaInstrument(far), sound(far))
        v.tap(Action.CLEAR)
        assertNull(sound(far))
        // Any edit of an empty slot starts from the default sound.
        v.goTo(Row.PAN_RIGHT)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(defaultSaaInstrument(far).copy(panRight = 11), sound(far))
    }

    @Test
    fun panAndNoiseClockStayInTheChipsRanges() {
        val slot = emptySlot
        session.setSaaInstrument(slot, SaaInstrument(name = "T"), "test")
        val v = view { instrument = slot }
        v.goTo(Row.PAN_LEFT)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(15, sound(slot)!!.panLeft)
        v.hold(Action.EDIT, *Array(5) { Action.DOWN })
        assertEquals(0, sound(slot)!!.panLeft)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(2, sound(slot)!!.panLeft)
        v.tap(Action.CLEAR)
        assertEquals(15, sound(slot)!!.panLeft)
        v.goTo(Row.NOISE)
        val clocks = ArrayList<Int>()
        repeat(5) { v.hold(Action.EDIT, Action.RIGHT); clocks += sound(slot)!!.noise }
        assertEquals(listOf(1, 2, 3, 0, 1), clocks)
        v.tap(Action.CLEAR)
        assertEquals(0, sound(slot)!!.noise)
        // The noise clock does nothing while the mixer plays no noise.
        assertNotNull(SaaInstrumentView.noiseNote(sound(slot)!!))
        assertNull(SaaInstrumentView.noiseNote(SaaPresets.named("Hat")!!.instrument))
    }

    @Test
    fun theHardwareEnvelopeIsSwitchedAndEditedRowByRow() {
        val slot = emptySlot
        session.setSaaInstrument(slot, SaaInstrument(name = "E"), "test")
        val v = view { instrument = slot; listenChannel = 2 }
        assertFalse(Row.SHAPE in SaaInstrumentView.rows(session.song, v.state))
        v.goTo(Row.HARDWARE)
        v.tap(Action.EDIT)
        assertEquals(SaaHardwareEnvelope(), sound(slot)!!.envelope)
        assertEquals(SaaInstrumentView.ENVELOPE_SETTINGS, SaaInstrumentView.rows(session.song, v.state).filter { it in SaaInstrumentView.ENVELOPE_SETTINGS })
        // Shape wraps through the eight shapes.
        v.goTo(Row.SHAPE)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT })
        assertEquals((SaaHardwareEnvelope().shape + 5) % 8, sound(slot)!!.envelope!!.shape)
        v.tap(Action.CLEAR)
        assertEquals(SaaHardwareEnvelope().shape, sound(slot)!!.envelope!!.shape)
        // Switches.
        for (row in listOf(Row.RESOLUTION, Row.INVERT, Row.CLOCK, Row.RETRIGGER)) {
            val before = sound(slot)!!.envelope!!
            v.goTo(row)
            v.tap(Action.EDIT)
            assertTrue("$row switched", sound(slot)!!.envelope != before)
        }
        val e = sound(slot)!!.envelope!!
        assertTrue(e.threeBit && e.invertRight && e.external && !e.retrigger)
        assertEquals(0xB7, e.control)
        // Rate: 1..255.
        v.goTo(Row.RATE)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(1, sound(slot)!!.envelope!!.rate)
        v.hold(Action.EDIT, *Array(40) { Action.UP })
        assertEquals(255, sound(slot)!!.envelope!!.rate)
        v.tap(Action.CLEAR)
        assertEquals(1, sound(slot)!!.envelope!!.rate)
        // Clear on the switch removes the envelope and its rows; the cursor stays on the switch.
        v.goTo(Row.HARDWARE)
        v.tap(Action.CLEAR)
        assertNull(sound(slot)!!.envelope)
        assertEquals(Row.HARDWARE, SaaInstrumentView.row(session.song, v.state))
        assertFalse(Row.RATE in SaaInstrumentView.rows(session.song, v.state))
        // One undo restores it.
        session.undo()
        assertNotNull(sound(slot)!!.envelope)
    }

    @Test
    fun envelopeStepsLoopAndReleaseAreEditedStepByStep() {
        val slot = emptySlot
        session.setSaaInstrument(slot, SaaInstrument(name = "T"), "test")
        val v = view { instrument = slot }
        v.goTo(Row.STEPS)
        // Volume starts as one step of 15: lower it, then carry it along three more steps.
        v.hold(Action.EDIT, Action.DOWN)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(listOf(13), sound(slot)!!.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(13, 13, 13, 13), sound(slot)!!.volume.values)
        assertEquals(3, v.state.envStep)
        v.hold(Action.EDIT, *Array(20) { Action.DOWN })
        assertEquals(listOf(13, 13, 13, 0), sound(slot)!!.volume.values)
        session.undo()
        assertEquals(listOf(13, 13, 13, 13), sound(slot)!!.volume.values)
        v.tap(Action.LEFT)
        v.tap(Action.CLEAR)
        assertEquals(listOf(13, 13, 13), sound(slot)!!.volume.values)
        repeat(3) { v.tap(Action.RIGHT) }
        v.tap(Action.EDIT)
        assertEquals(listOf(13, 13, 13, 13), sound(slot)!!.volume.values)
        // Loop from step 0, then a release after it; clearing the loop keeps the release.
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, Action.RIGHT)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesEnvelope(listOf(13, 13, 13, 13), 0, 1), sound(slot)!!.volume)
        v.goTo(Row.LOOP)
        v.tap(Action.CLEAR)
        assertEquals(NesEnvelope(listOf(13, 13, 13, 13), -1, 1), sound(slot)!!.volume)
        // Arpeggio has neither release nor mode; pitch has the mode and stops at -64.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(SaaEnvelopeKind.ARPEGGIO, SaaInstrumentView.kind(v.state))
        assertFalse(Row.RELEASE in SaaInstrumentView.rows(session.song, v.state))
        assertFalse(Row.MODE in SaaInstrumentView.rows(session.song, v.state))
        v.tap(Action.NEXT_CHANNEL)
        v.goTo(Row.MODE)
        v.tap(Action.EDIT)
        assertTrue(sound(slot)!!.pitchRelative)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(80) { Action.DOWN })
        assertEquals(listOf(-64), sound(slot)!!.pitch.values)
        // The mixer page: values 0..3, empty by default (tone).
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(SaaEnvelopeKind.MIXER, SaaInstrumentView.kind(v.state))
        assertEquals(NesEnvelope(), sound(slot)!!.mixer)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(3), sound(slot)!!.mixer.values)
        v.hold(Action.EDIT, Action.DOWN, Action.RIGHT)
        assertEquals(listOf(2, 2), sound(slot)!!.mixer.values)
        v.hold(Action.EDIT, *Array(10) { Action.DOWN })
        assertEquals(listOf(2, 0), sound(slot)!!.mixer.values)
        assertEquals(listOf(3, 0), SaaEnvelopeKind.MIXER.fixed(NesEnvelope(listOf(9, -2), 0, 1)).values)
        assertEquals(-1, SaaEnvelopeKind.MIXER.fixed(NesEnvelope(listOf(9, -2), 0, 1)).release)
        // Length on the mixer: Clear empties it again (tone throughout).
        v.goTo(Row.LENGTH)
        v.tap(Action.CLEAR)
        assertEquals(NesEnvelope(), sound(slot)!!.mixer)
        // L1 from volume wraps to the mixer page.
        v.tap(Action.NEXT_CHANNEL)
        v.tap(Action.PREV_CHANNEL)
        assertEquals(SaaEnvelopeKind.MIXER, SaaInstrumentView.kind(v.state))
    }

    @Test
    fun soundsAndEnvelopesAreCopiedPastedCutAndDuplicated() {
        val v = view { instrument = 1 }
        val first = sound(1)!!
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        assertEquals(SaaClip.Sound(first), session.clipboard)
        v.state.instrument = emptySlot
        v.tap(Action.PASTE)
        assertEquals(first, sound(emptySlot))
        // An envelope copied from the volume page pastes into the mixer, clamped and without its release.
        session.setSaaInstrument(1, first.copy(volume = NesEnvelope(listOf(15, 2, 1), 0, 1)), "test")
        v.state.instrument = 1
        v.goTo(Row.STEPS)
        v.tap(Action.COPY)
        assertTrue(session.clipboard is SaaClip.Envelope)
        v.state.instrument = emptySlot
        v.tap(Action.PREV_CHANNEL)
        assertEquals(SaaEnvelopeKind.MIXER, SaaInstrumentView.kind(v.state))
        v.goTo(Row.STEPS)
        v.tap(Action.PASTE)
        assertEquals(NesEnvelope(listOf(3, 2, 1), 0, -1), sound(emptySlot)!!.mixer)
        v.tap(Action.CUT)
        assertEquals(NesEnvelope(), sound(emptySlot)!!.mixer)
        v.goTo(Row.SLOT)
        v.tap(Action.CUT)
        assertNull(sound(emptySlot))
        // Duplicate copies the sound into the next slot and follows it.
        v.state.instrument = 1
        v.tap(Action.DUPLICATE)
        assertEquals(2, v.state.instrument)
        assertEquals(sound(1)!!.copy(name = sound(1)!!.name + "+"), sound(2))
    }

    @Test
    fun listenNotesSayWhatALaneCannotPlay() {
        val song = session.song
        val buzz = SaaPresets.named("Buzz bass")!!.instrument
        val swell = SaaPresets.named("Slow swell")!!.instrument
        val sweep = SaaPresets.named("Noise sweep")!!.instrument
        val lead = SaaPresets.named("Lead")!!.instrument
        // A hardware envelope on a lane without one; an internal clock that Listen alone leaves at the lowest tone.
        assertNotNull(SaaInstrumentView.channelNote(song, 0, buzz))
        assertNotNull(SaaInstrumentView.channelNote(song, 2, buzz))
        assertNull(SaaInstrumentView.channelNote(song, 2, swell))
        assertNull(SaaInstrumentView.channelNote(song, 5, swell))
        assertNotNull(SaaInstrumentView.channelNote(song, 4, swell))
        // Noise clock 3 follows lane 1's (4's) tone.
        assertNull(SaaInstrumentView.channelNote(song, 3, sweep))
        assertNotNull(SaaInstrumentView.channelNote(song, 4, sweep))
        assertNull(SaaInstrumentView.channelNote(song, 1, lead))
        // Listen plays the selected sound on the chosen lane.
        val v = view { instrument = slot("Buzz bass") }
        v.goTo(Row.LISTEN)
        repeat(2) { v.tap(Action.RIGHT) }
        assertEquals(2, v.state.listenChannel)
        v.tap(Action.AUDITION)
        assertEquals(Triple(2, v.defaultNote(), slot("Buzz bass")), auditions.last())
        // On the CMS Listen reaches chip B's lanes.
        val cms = EditorSession(CmsSystem.blank())
        val w = ViewController(cms, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1; soundPanel("saa").advanced = true })
        w.state.instField = SaaInstrumentView.rows(cms.song, w.state).indexOf(Row.LISTEN)
        repeat(11) { w.tap(Action.RIGHT) }
        assertEquals(11, w.state.listenChannel)
        w.tap(Action.RIGHT)
        assertEquals(0, w.state.listenChannel)
    }


    @Test
    fun emptySlotsAreNotAuditioned() {
        val v = view { instrument = emptySlot }
        v.tap(Action.AUDITION)
        assertTrue(auditions.isEmpty())
        assertNotNull(session.message)
    }
}
