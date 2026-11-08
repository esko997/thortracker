package org.thortracker.system.nes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.nes.NesInstrumentView.Row

/** The NES instrument editor driven only through controller actions, and its envelope edits. */
class NesInstrumentViewTest {
    private val session = EditorSession(NesSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        state.instField = NesInstrumentView.rows(state, session.song).indexOf(row)
    }

    private val sound get() = session.song.nes.instruments[0]

    @Test
    fun anUnplayableAuditionIsAPassingMessageNotAnAudioCommand() {
        val floor = NesRules.LOWEST_TONAL   // A-1: the pulses' and triangle's longest period
        session.audition(channel = 0, note = floor - 1, instrument = 1)
        assertTrue(auditions.isEmpty())
        assertEquals("Pulse 1 cannot play below A-1", session.message)
        session.audition(channel = 0, note = floor, instrument = 1)
        assertEquals(listOf(Triple(0, floor, 1)), auditions)
        // The noise channel has no such floor.
        session.audition(channel = 3, note = floor - 1, instrument = 1)
        assertEquals(Triple(3, floor - 1, 1), auditions.last())
    }

    @Test
    fun rowsFollowTheEnvelope() {
        val st = ViewState()
        assertTrue(Row.RELEASE in NesInstrumentView.rows(st, session.song))
        assertFalse(Row.MODE in NesInstrumentView.rows(st, session.song))
        st.envelope = NesEnvelopeKind.PITCH.ordinal
        assertFalse(Row.RELEASE in NesInstrumentView.rows(st, session.song))
        assertTrue(Row.MODE in NesInstrumentView.rows(st, session.song))
        assertFalse(Row.CHIP in NesInstrumentView.rows(st, session.song)) // 2A03-only songs keep their rows
    }

    /** On a VRC6 song a sound picks its chip; VRC6 sounds get eight duties and a saw volume, and no DPCM page. */
    @Test
    fun vrc6SoundsChooseTheirChip() {
        val vrc6 = EditorSession(NesVrc6System.blank())
        val v = ViewController(vrc6, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1 })
        fun goTo(row: Row) { v.state.instField = NesInstrumentView.rows(v.state, vrc6.song).indexOf(row) }
        val ins = { vrc6.song.nes.instruments[0] }
        assertTrue(Row.CHIP in NesInstrumentView.rows(v.state, vrc6.song))
        assertFalse(Row.SAW in NesInstrumentView.rows(v.state, vrc6.song))
        assertEquals(NesEnvelopeKind.entries.size + 1, NesInstrumentView.pages(vrc6.song, v.state))
        goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.VRC6, ins().chip)
        assertEquals(NesRules.VRC6_PULSE1, v.state.listenChannel) // Listen follows to a VRC6 channel
        assertTrue(Row.SAW in NesInstrumentView.rows(v.state, vrc6.song))
        assertEquals(NesEnvelopeKind.entries.size, NesInstrumentView.pages(vrc6.song, v.state)) // no DPCM page
        goTo(Row.SAW)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(Vrc6SawVolume.QUARTER, ins().sawVolume)
        v.tap(Action.CLEAR)
        assertEquals(Vrc6SawVolume.HALF, ins().sawVolume)
        // Duty runs to 7 on a VRC6 sound.
        v.state.envelope = NesEnvelopeKind.DUTY.ordinal
        goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(7), ins().duty.values)
        // Back to 2A03: duties clamp to 0-3, the saw volume resets, Listen returns to a 2A03 channel.
        v.state.envelope = NesEnvelopeKind.VOLUME.ordinal
        goTo(Row.CHIP)
        v.tap(Action.CLEAR)
        assertEquals(NesChip.APU, ins().chip)
        assertEquals(listOf(3), ins().duty.values)
        // A copied VRC6 sound pasted into a 2A03 song becomes a 2A03 sound.
        val vrc6Sound = NesInstrument("V", chip = NesChip.VRC6, duty = NesEnvelope(listOf(6)), sawVolume = Vrc6SawVolume.FULL)
        val pasted = nesInstrumentFor(session.song, vrc6Sound)
        assertEquals(NesChip.APU, pasted.chip)
        assertEquals(listOf(3), pasted.duty.values)
        assertEquals(Vrc6SawVolume.HALF, pasted.sawVolume)
        // 2A03 songs refuse the VRC6 chip.
        assertFalse(session.setNesChip(1, NesChip.VRC6))
    }

    @Test
    fun stepsAreDrawnAndEditedWithTheController() {
        val v = view { instrument = 1 }
        v.goTo(Row.STEPS)
        // Volume starts as one step of 15: lower it, then carry it along three more steps.
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN)
        assertEquals(listOf(13), sound.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(13, 13, 13, 13), sound.volume.values)
        assertEquals(3, v.state.envStep)
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(listOf(13, 13, 13, 10), sound.volume.values)
        // One hold is one undo step.
        session.undo()
        assertEquals(listOf(13, 13, 13, 13), sound.volume.values)
        // Clear deletes the step under the cursor.
        v.tap(Action.LEFT)
        v.tap(Action.CLEAR)
        assertEquals(listOf(13, 13, 13), sound.volume.values)
        // Past the end, a tap appends the last value.
        v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        assertEquals(3, v.state.envStep)
        v.tap(Action.EDIT)
        assertEquals(listOf(13, 13, 13, 13), sound.volume.values)
    }

    @Test
    fun valuesStayInTheirEnvelopesRange() {
        val v = view { instrument = 2; envelope = NesEnvelopeKind.DUTY.ordinal }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(3), session.song.nes.instruments[1].duty.values)
        v.tap(Action.NEXT_CHANNEL) // the DPCM page
        assertTrue(NesInstrumentView.dpcmPage(v.state))
        v.tap(Action.NEXT_CHANNEL) // to volume (wraps)
        assertEquals(NesEnvelopeKind.VOLUME, NesInstrumentView.kind(v.state))
        v.tap(Action.PREV_CHANNEL); v.tap(Action.PREV_CHANNEL); v.tap(Action.PREV_CHANNEL) // DPCM, duty, pitch
        assertEquals(NesEnvelopeKind.PITCH, NesInstrumentView.kind(v.state))
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(80) { Action.DOWN })
        assertEquals(listOf(-64), session.song.nes.instruments[1].pitch.values)
    }

    @Test
    fun lengthLoopAndReleaseStayLegal() {
        val v = view { instrument = 1 }
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.UP) // +8
        assertEquals(9, sound.volume.values.size)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT) // a release needs a loop: it loops from step 0 and releases at step 1
        assertEquals(0, sound.volume.loop)
        assertEquals(1, sound.volume.release)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(5, sound.volume.release)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, *Array(9) { Action.RIGHT })
        assertEquals("the loop stays before the release", 4, sound.volume.loop)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, *Array(6) { Action.LEFT })
        assertEquals(3, sound.volume.values.size)
        assertEquals(-1, sound.volume.release)
        assertEquals(2, sound.volume.loop)
        v.goTo(Row.LOOP)
        v.tap(Action.CLEAR)
        assertEquals(-1, sound.volume.loop)
    }

    @Test
    fun releaseOnlyOnVolume() {
        val e = NesEnvelope(listOf(1, 2, 3), loop = 0, release = 2)
        assertEquals(-1, NesEnvelopeKind.ARPEGGIO.fixed(e).release)
        assertEquals(e, NesEnvelopeKind.VOLUME.fixed(e))
        assertEquals("no release without a loop", -1, NesEnvelopeKind.VOLUME.fixed(e.copy(loop = -1)).release)
        assertEquals(NesEnvelope(listOf(2, 3), 0, 1), NesEnvelopes.removed(NesEnvelopeKind.VOLUME, e, 0))
    }

    @Test
    fun listenPlaysTheChosenChannelAndSlot() {
        val v = view { instrument = 5 }
        v.goTo(Row.LISTEN)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        v.press(Action.AUDITION)
        assertEquals(2, auditions.last().first)
        assertEquals(5, auditions.last().third)
        v.release(Action.AUDITION)
    }

    @Test
    fun copyPasteEnvelopeBetweenKinds() {
        val v = view { instrument = 1; envelope = NesEnvelopeKind.ARPEGGIO.ordinal }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(20) { Action.UP }, Action.RIGHT)
        assertEquals(listOf(20, 20), sound.arpeggio.values)
        v.tap(Action.COPY)
        v.tap(Action.NEXT_CHANNEL) // pitch
        v.tap(Action.NEXT_CHANNEL) // duty
        v.tap(Action.PASTE)
        assertEquals("clamped into duty's range", listOf(3, 3), sound.duty.values)
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        v.tap(Action.RIGHT)
        v.tap(Action.PASTE)
        assertEquals(sound, session.song.nes.instruments[1])
    }

    @Test
    fun duplicateAndResetSlot() {
        val v = view { instrument = 1 }
        v.goTo(Row.NAME)
        v.hold(Action.EDIT, Action.RIGHT)
        val named = sound.name
        v.goTo(Row.SLOT)
        v.tap(Action.DUPLICATE)
        assertEquals(2, v.state.instrument)
        assertEquals("$named+", session.song.nes.instruments[1].name)
        v.tap(Action.CLEAR)
        assertEquals(defaultNesInstrument(2), session.song.nes.instruments[1])
    }

    @Test
    fun noWaveViewOnNes() {
        assertFalse(ViewKind.WAVE in NesSystem.views)
        assertEquals("NES (FamiStudio) has no Wave view", Workspace.missingView(ViewKind.WAVE, NesSystem))
        assertNull(Workspace.missingView(ViewKind.WAVE, GbSystem))
        // A pane showing Wave falls back to Pattern on an NES song.
        val v = ViewController(session, ViewState(kind = ViewKind.WAVE))
        v.press(Action.DOWN)
        assertEquals(ViewKind.PATTERN, v.state.kind)
    }

    @Test
    fun switchingSystemsKeepsSelectionAndEntrySoundsInRange() {
        val gb = EditorSession(GbSystem.blank())
        val sounds = ViewController(gb, ViewState(kind = ViewKind.INSTRUMENT))
        sounds.tap(Action.NEXT_CHANNEL); sounds.tap(Action.NEXT_CHANNEL) // GB noise bank
        assertEquals(2, gb.selection.bank)
        gb.replaceSong(NesSystem.blank())
        assertEquals("the NES has one bank", 0, gb.selection.bank)
        val pattern = ViewController(gb, ViewState(kind = ViewKind.KEYBOARD))
        pattern.follow()
        pattern.setKeyboardSound(40)
        assertEquals(40, pattern.pen(0))
        gb.replaceSong(GbSystem.blank())
        assertEquals("a GB bank has 15 slots", 15, pattern.pen(0))
    }

    private fun sample(name: String, size: Int = 17) = DpcmSample(name, List(size) { 0x55 })

    private fun dpcmView() = view { instrument = 1; envelope = NesInstrumentView.DPCM_PAGE }

    @Test
    fun theDpcmPageMapsKeysWithTheController() {
        session.addDpcmSamples(listOf(sample("Kick"), sample("Snare")), "Add")
        val v = dpcmView()
        val key = v.state.dpcmKey
        v.goTo(Row.SAMPLE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(DpcmMapping(0), sound.dpcm[key])
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, sound.dpcm[key]?.sample)
        v.goTo(Row.PITCH)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(14, sound.dpcm[key]?.pitch)
        v.goTo(Row.SAMPLE_LOOP)
        v.tap(Action.EDIT)
        assertEquals(true, sound.dpcm[key]?.loop)
        v.goTo(Row.LEVEL)
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(8, sound.dpcm[key]?.dmcInitial)
        v.tap(Action.CLEAR)
        assertEquals(-1, sound.dpcm[key]?.dmcInitial)
        // Listen plays the shown key on the DPCM channel, whichever channel Listen was on.
        v.tap(Action.AUDITION)
        assertEquals(Triple(NesRules.DPCM, key, 1), auditions.last())
        // The sample list maps the selected sample to another key.
        v.goTo(Row.KEY)
        v.tap(Action.RIGHT)
        v.goTo(Row.SAMPLES)
        v.tap(Action.LEFT)
        assertEquals(0, v.state.dpcmSample)
        v.tap(Action.EDIT)
        assertEquals(0, sound.dpcm[key + 1]?.sample)
        // Edit+← on the key jumps to the previous mapped key.
        v.goTo(Row.KEY)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(key, v.state.dpcmKey)
        assertEquals(1, v.state.dpcmSample)
        // Deleting a sample removes the keys that played it and renumbers the others.
        v.goTo(Row.SAMPLES)
        v.tap(Action.LEFT)
        v.tap(Action.CLEAR)
        assertEquals(listOf("Snare"), session.song.nes.samples.map { it.name })
        assertEquals(mapOf(key to 0), sound.dpcm.mapValues { it.value.sample })
        session.undo()
        assertEquals(2, session.song.nes.samples.size)
    }

    @Test
    fun samplesAreRenamedAndTheirLevelSet() {
        session.addDpcmSamples(listOf(sample("Hat")), "Add")
        val v = dpcmView()
        v.goTo(Row.SAMPLE_NAME)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals("HatA", session.song.nes.samples[0].name)
        v.goTo(Row.SAMPLE_LEVEL)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(DpcmSample.DEFAULT_DMC_INITIAL - 8, session.song.nes.samples[0].dmcInitial)
    }

    @Test
    fun addingSamplesMapsThemOnFreeKeysAndReusesKnownBytes() {
        session.addAndMapDpcmSamples(1, listOf(sample("Kick")), 36, 15, "Add")
        val keys = session.addAndMapDpcmSamples(1, listOf(sample("Kick again"), sample("Snare", 33)), 36, 12, "Add")
        assertEquals(listOf(37, 38), keys)
        assertEquals(listOf("Kick", "Snare"), session.song.nes.samples.map { it.name })
        assertEquals(DpcmMapping(0, pitch = 12), sound.dpcm[37])
        assertEquals("Samples", sound.name)
    }

    @Test
    fun theAddRowOpensTheStarterKitMenu() {
        val menus = ArrayList<List<Pair<String, () -> Unit>>>()
        session.onMenuRequest = { build ->
            build(object : org.thortracker.editor.MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = Unit
                override fun status(message: String) = Unit
                override fun error(message: String) = throw AssertionError(message)
            })
        }
        val v = dpcmView()
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        assertEquals(2, menus.single().size)
        menus.single()[0].second()
        val kit = DpcmStarterKit.samples()
        assertEquals(kit.map { it.name }, session.song.nes.samples.map { it.name })
        // Laid out on General MIDI drum keys from C-3 (the shown key's octave); both toms share one sample.
        assertEquals(DpcmStarterKit.NOTES.mapValues { it.value.first }, sound.dpcm.mapValues { it.value.sample })
        assertEquals(15, sound.dpcm[36]?.pitch)
    }

    @Test
    fun patternNotesOnDpcmStepThroughMappedKeys() {
        session.addAndMapDpcmSamples(1, listOf(sample("Kick"), sample("Snare", 33)), 40, 15, "Add")
        session.setDpcmMapping(1, 45, DpcmMapping(1), "Map")
        val p = ViewController(session, ViewState(kind = ViewKind.PATTERN, channel = NesRules.DPCM))
        p.tap(Action.EDIT)
        assertEquals(40, session.song.patterns[0].tracks[NesRules.DPCM][0].note)
        assertEquals("E-3: Kick · rate F", session.message)
        p.tap(Action.UP)
        p.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(45, session.song.patterns[0].tracks[NesRules.DPCM][0].note)
        p.hold(Action.EDIT, Action.RIGHT)
        assertEquals(45, session.song.patterns[0].tracks[NesRules.DPCM][0].note)
        assertEquals(listOf(40, 41, 45), NesSystem.mappedNotes(session.song, NesRules.DPCM, 1))
        assertNull(NesSystem.mappedNotes(session.song, 0, 1))
    }
}
