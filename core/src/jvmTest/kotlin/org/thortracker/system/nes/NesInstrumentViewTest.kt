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
import org.thortracker.input.Keymap
import org.thortracker.system.ChannelKind
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

    // ---- N163 -------------------------------------------------------------------------------------------------

    /** A song with [channels] N163 channels whose sound 1 is an N163 sound, shown on its Waves page. */
    private fun n163(channels: Int): ViewController {
        val s = EditorSession(NesN163System.of(channels).blank())
        s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        assertTrue(s.setNesChip(1, NesChip.N163))
        return ViewController(s, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1; envelope = NesInstrumentView.WAVE_PAGE })
    }

    private val ViewController.wave: N163Wave get() = session.song.nes.instruments[state.instrument - 1].wave

    /** 2A03-only songs keep exactly the rows and pages they had before the expansions. */
    @Test
    fun plainNesRowsAndPagesAreUnchanged() {
        val song = session.song
        val st = ViewState()
        val head = listOf(Row.SLOT, Row.NAME, Row.LISTEN, Row.ENVELOPE)
        val expected = mapOf(
            NesEnvelopeKind.VOLUME to head + listOf(Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS),
            NesEnvelopeKind.ARPEGGIO to head + listOf(Row.LENGTH, Row.LOOP, Row.STEPS),
            NesEnvelopeKind.PITCH to head + listOf(Row.LENGTH, Row.LOOP, Row.MODE, Row.STEPS),
            NesEnvelopeKind.DUTY to head + listOf(Row.LENGTH, Row.LOOP, Row.STEPS),
        )
        for ((kind, rows) in expected) {
            st.envelope = kind.ordinal
            assertEquals(rows, NesInstrumentView.rows(st, song))
            assertFalse(NesInstrumentView.wavePage(song, st))
        }
        st.envelope = NesInstrumentView.DPCM_PAGE
        assertEquals(NesInstrumentView.DPCM_ROWS, NesInstrumentView.rows(st, song))
        assertEquals(NesEnvelopeKind.entries.size + 1, NesInstrumentView.pages(song, st))
        assertFalse(NesInstrumentView.helpSections(Keymap.DEFAULT).any { it.first == "N163 sounds" })
        // The VRC7's FM page keeps its rows.
        val vrc7 = EditorSession(NesVrc7System.blank())
        assertTrue(vrc7.setNesChip(1, NesChip.VRC7))
        val fm = ViewState().apply { instrument = 1; envelope = NesInstrumentView.FM_PAGE }
        assertEquals(NesInstrumentView.FM_ROWS, NesInstrumentView.rows(fm, vrc7.song))
        assertFalse(NesInstrumentView.wavePage(vrc7.song, fm))
    }

    /** On an N163 song a sound picks its chip; N163 sounds keep volume, arpeggio and pitch, and their fourth page is Waves. */
    @Test
    fun n163SoundsChooseTheirChipAndGetTheWavesPage() {
        val s = EditorSession(NesN163System.of(4).blank())
        s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        val v = ViewController(s, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1; envelope = NesEnvelopeKind.DUTY.ordinal })
        val ins = { s.song.nes.instruments[0] }
        val rows = { NesInstrumentView.rows(v.state, s.song) }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.UP, Action.UP)
        assertEquals(listOf(2), ins().duty.values)
        assertTrue(Row.CHIP in rows())
        assertFalse(Row.SAW in rows())
        assertEquals(NesEnvelopeKind.entries.size + 1, NesInstrumentView.pages(s.song, v.state))
        v.goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.N163, ins().chip)
        assertEquals("N163 sounds have no duty", emptyList<Int>(), ins().duty.values)
        assertEquals(N163Wave.DEFAULT, ins().wave)
        assertEquals("Listen follows to an N163 channel", NesRules.N163_FIRST, v.state.listenChannel)
        assertEquals(NesEnvelopeKind.entries.size, NesInstrumentView.pages(s.song, v.state)) // no DPCM page
        assertTrue("the duty page is now the Waves page", NesInstrumentView.wavePage(s.song, v.state))
        assertEquals(NesInstrumentView.WAVE_ROWS, rows())
        assertEquals(Row.CHIP, NesInstrumentView.row(v.state, s.song))
        assertNull(NesInstrumentView.envelope(s.song, v.state))
        // Listen plays the sound on its N163 channel.
        v.tap(Action.AUDITION)
        assertEquals(NesRules.N163_FIRST, auditions.last().first)
        assertEquals(1, auditions.last().third)
        // The pages wrap: volume, arpeggio, pitch, Waves.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesEnvelopeKind.VOLUME, NesInstrumentView.kind(v.state))
        assertEquals(listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS), rows())
        assertEquals(Row.CHIP, NesInstrumentView.row(v.state, s.song))
        v.tap(Action.PREV_CHANNEL)
        assertTrue(NesInstrumentView.wavePage(s.song, v.state))
        // What the N163's channels do with the envelopes.
        assertEquals("N163: 4-bit linear volume", NesInstrumentView.channelNote(NesEnvelopeKind.VOLUME, ChannelKind.WAVE, NesChip.N163))
        assertEquals("+ raises the pitch (N163 frequency steps of 2^4)",
            NesInstrumentView.channelNote(NesEnvelopeKind.PITCH, ChannelKind.WAVE, NesChip.N163, n163Channels = 4))
        assertEquals("N163 sounds play on the N163 channels only",
            NesInstrumentView.channelNote(NesEnvelopeKind.VOLUME, ChannelKind.PULSE, NesChip.N163, channelChip = NesChip.APU))
        // Back to 2A03: the waves reset, the DPCM page returns.
        v.goTo(Row.WAVE_SIZE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(20, ins().wave.size)
        v.goTo(Row.CHIP)
        v.tap(Action.CLEAR)
        assertEquals(NesChip.APU, ins().chip)
        assertEquals(N163Wave.DEFAULT, ins().wave)
        assertEquals(NesEnvelopeKind.entries.size + 1, NesInstrumentView.pages(s.song, v.state))
        // Help describes the Waves page with the variant's RAM.
        val help = NesInstrumentView.helpSections(Keymap.DEFAULT, NesExpansion.N163_4).single { it.first == "N163 sounds" }.second.toMap()
        assertTrue(help.getValue("Position").contains("0-191"))
        assertTrue("Samples" in help && "Wave" in help && "Preset" in help && "RAM map" in help)
    }

    @Test
    fun waveSamplesAreDrawnWithTheController() {
        val v = n163(1)
        val sine = N163Presets.sine(16)
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP, Action.UP)
        val raised = sine.toMutableList().also { it[0] = sine[0] + 2 }
        assertEquals(listOf<List<Int>>(raised), v.wave.waves)
        // Edit+→ carries the value along, one undo step per hold.
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        val drawn = raised.toMutableList().also { for (i in 1..3) it[i] = raised[0] }
        assertEquals(listOf<List<Int>>(drawn), v.wave.waves)
        assertEquals(3, v.state.waveCol)
        assertNull("drawn waves are Custom", N163Presets.presetOf(v.wave.waves, 16))
        v.session.undo()
        assertEquals(listOf<List<Int>>(raised), v.wave.waves)
        // Samples stay 0..15.
        v.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(15, v.wave.waves[0][3])
        // Clear puts the sample back to 8 (the N163's silence).
        v.tap(Action.CLEAR)
        assertEquals(N163Waves.CENTRE, v.wave.waves[0][3])
        assertEquals("Clear wave sample", v.session.undoLabel)
        // Clear held: ←→ jump 8 samples and clear nothing.
        v.hold(Action.CLEAR, Action.RIGHT)
        assertEquals(11, v.state.waveCol)
        v.hold(Action.CLEAR, Action.RIGHT)
        assertEquals(15, v.state.waveCol)
        assertEquals(N163Waves.CENTRE, v.wave.waves[0][3])
        v.tap(Action.LEFT)
        assertEquals(14, v.state.waveCol)
    }

    @Test
    fun wavesAreAddedAndDeletedWithinTheirLimits() {
        val v = n163(1)
        v.goTo(Row.WAVE_INDEX)
        v.tap(Action.EDIT)
        assertEquals(2, v.wave.waves.size)
        assertEquals("the copy is shown", 1, v.state.wave)
        assertEquals("Duplicate wave", v.session.undoLabel)
        v.hold(Action.EDIT, Action.UP) // eight more at the end
        assertEquals(10, v.wave.waves.size)
        assertEquals(10, v.wave.repeat.size)
        v.tap(Action.LEFT)
        assertEquals(0, v.state.wave)
        v.tap(Action.CLEAR)
        assertEquals(9, v.wave.waves.size)
        assertEquals("Delete wave", v.session.undoLabel)
        // FamiStudio keeps at most 64 waves...
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(N163Wave.MAX_WAVES, v.wave.waves.size)
        v.tap(Action.EDIT)
        assertEquals(N163Wave.MAX_WAVES, v.wave.waves.size)
        // ...and 1024 samples: 240-sample waves (the 1-channel N163's whole wave RAM) keep 4.
        v.goTo(Row.WAVE_SIZE)
        v.hold(Action.EDIT, *Array(15) { Action.UP })
        assertEquals(240, v.wave.size)
        assertEquals(4, v.wave.waves.size)
        assertEquals(listOf(N163Presets.sine(240)), v.wave.waves.distinct())
        assertEquals(4, v.wave.repeat.size)
        // A sound keeps at least one wave.
        v.goTo(Row.WAVE_INDEX)
        repeat(5) { v.tap(Action.CLEAR) }
        assertEquals(1, v.wave.waves.size)
        assertEquals(0, v.state.wave)
    }

    @Test
    fun sizeAndPositionStayInTheN163Ram() {
        // 8 channels: 128 samples of wave RAM (the registers take 64 of the 128 bytes).
        val v8 = n163(8)
        assertEquals(128, N163Waves.ram(v8.session.song))
        assertEquals("RAM 0-15 of 0-127 (8 channels: 128 bytes minus 64 for registers)", NesInstrumentView.ramText(v8.wave, 8))
        v8.goTo(Row.WAVE_SIZE)
        v8.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(128, v8.wave.size)
        assertEquals("a preset is made again at the new size", listOf(N163Presets.sine(128)), v8.wave.waves)
        v8.goTo(Row.WAVE_POSITION)
        v8.hold(Action.EDIT, Action.RIGHT)
        assertEquals("no room after a 128-sample wave", 0, v8.wave.position)
        v8.goTo(Row.WAVE_SIZE)
        v8.tap(Action.CLEAR)
        assertEquals(16, v8.wave.size)
        v8.goTo(Row.WAVE_POSITION)
        v8.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(112, v8.wave.position)
        v8.goTo(Row.WAVE_SIZE)
        v8.hold(Action.EDIT, Action.RIGHT)
        assertEquals("the wave already ends at the registers", 16, v8.wave.size)
        v8.hold(Action.EDIT, Action.LEFT)
        assertEquals(12, v8.wave.size)
        v8.goTo(Row.WAVE_POSITION)
        v8.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(116, v8.wave.position)
        // 1 channel: 240 samples.
        val v1 = n163(1)
        v1.goTo(Row.WAVE_POSITION)
        v1.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(224, v1.wave.position)
        assertEquals("RAM 224-239 of 0-239 (1 channel: 128 bytes minus 8 for registers)", NesInstrumentView.ramText(v1.wave, 1))
        v1.tap(Action.CLEAR)
        assertEquals(0, v1.wave.position)
        v1.goTo(Row.WAVE_SIZE)
        v1.hold(Action.EDIT, *Array(20) { Action.UP })
        assertEquals(240, v1.wave.size)
        // Pasted into a song with more channels, the sound is fitted into that song's RAM.
        val fitted = nesInstrumentFor(v8.session.song, v1.session.song.nes.instruments[0])
        assertEquals(128, fitted.wave.size)
        assertEquals(0, fitted.wave.position)
        assertEquals(listOf(N163Waves.resampled(N163Presets.sine(240), 128)), fitted.wave.waves)
        // Sizes and the pitch: FamiStudio's note tables are for 16 samples.
        assertEquals("16 samples: in tune", NesInstrumentView.sizeText(16))
        assertEquals("32 samples: one octave lower", NesInstrumentView.sizeText(32))
        assertEquals("8 samples: one octave higher", NesInstrumentView.sizeText(8))
        assertEquals("24 samples: 7.0 semitones lower — out of tune", NesInstrumentView.sizeText(24))
        // Drawn waves are resampled to the nearest sample.
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), N163Waves.resampled(listOf(0, 1, 2, 3), 8))
        assertEquals(listOf(1, 3, 5, 7), N163Waves.resampled((0..7).toList(), 4))
    }

    @Test
    fun presetsApplyFamiStudiosWaves() {
        val v = n163(2)
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.DOWN)
        assertNull(N163Presets.presetOf(v.wave.waves, 16))
        v.goTo(Row.WAVE_PRESET)
        v.tap(Action.CLEAR)
        assertEquals(listOf(N163Presets.sine(16)), v.wave.waves)
        assertEquals(N163Preset.SINE, N163Presets.presetOf(v.wave.waves, 16))
        assertEquals("Set wave preset", v.session.undoLabel)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(N163Presets.waves(N163Preset.TRIANGLE, 16, 1), v.wave.waves)
        // Three waves: the PWM presets change the width from wave to wave.
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, v.wave.waves.size)
        v.goTo(Row.WAVE_PRESET)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT }) // sawtooth, square 50%, square 25%, flat, PWM 1
        assertEquals(N163Presets.waves(N163Preset.PWM, 16, 3), v.wave.waves)
        v.tap(Action.EDIT)
        assertEquals(N163Presets.waves(N163Preset.PWM2, 16, 3), v.wave.waves)
        // From drawn waves the first step goes to the first preset.
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP)
        assertNull(N163Presets.presetOf(v.wave.waves, 16))
        v.goTo(Row.WAVE_PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(N163Presets.waves(N163Preset.SINE, 16, 3), v.wave.waves)
    }

    @Test
    fun waveLoopAndReleaseFollowTheVolumeEnvelopesRules() {
        val v = n163(3)
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(4, v.wave.waves.size)
        v.goTo(Row.WAVE_RELEASE)
        v.hold(Action.EDIT, Action.RIGHT) // a release needs a loop: it loops from wave 0 and releases at wave 1
        assertEquals(0, v.wave.loop)
        assertEquals(1, v.wave.release)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals("release < count", 3, v.wave.release)
        v.goTo(Row.WAVE_LOOP)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT })
        assertEquals("the loop stays before the release", 2, v.wave.loop)
        // Deleting a wave before them moves both.
        v.goTo(Row.WAVE_INDEX)
        v.tap(Action.CLEAR)
        assertEquals(3, v.wave.waves.size)
        assertEquals(1, v.wave.loop)
        assertEquals(2, v.wave.release)
        // Fewer waves drop a release past the end.
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(2, v.wave.waves.size)
        assertEquals(1, v.wave.loop)
        assertEquals(-1, v.wave.release)
        v.goTo(Row.WAVE_LOOP)
        v.tap(Action.CLEAR)
        assertEquals(-1, v.wave.loop)
        assertEquals("Clear wave loop", v.session.undoLabel)
        // No release without a loop; none on a single wave.
        assertEquals(-1, N163Waves.fixed(N163Wave(waves = List(3) { N163Presets.sine(16) }, repeat = listOf(1, 1, 1), release = 2), 240).release)
        assertEquals(-1, N163Waves.withRelease(N163Wave.DEFAULT, 1, 240).release)
        // Repeats are 1-15 frames per wave.
        v.goTo(Row.WAVE_REPEAT)
        v.hold(Action.EDIT, *Array(5) { Action.UP })
        assertEquals(listOf(15, 1), v.wave.repeat)
        v.tap(Action.CLEAR)
        assertEquals(listOf(1, 1), v.wave.repeat)
    }

    @Test
    fun copyPasteKeepsTheWaves() {
        val v = n163(2)
        val s = v.session
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP)
        val drawn = v.wave.waves[0]
        v.tap(Action.COPY)
        assertEquals(NesClip.Wave(drawn), s.clipboard)
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT) // a second wave
        v.tap(Action.RIGHT) // shown
        assertEquals(1, v.state.wave)
        v.goTo(Row.WAVE_PRESET)
        v.tap(Action.CLEAR) // both waves sine
        v.goTo(Row.WAVE_SAMPLES)
        v.tap(Action.PASTE)
        assertEquals(listOf(N163Presets.sine(16), drawn), v.wave.waves)
        assertEquals("Paste wave", s.undoLabel)
        // A wave pasted onto waves of another size is resampled to it.
        v.goTo(Row.WAVE_SIZE)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(32, v.wave.size)
        assertEquals(N163Waves.resampled(drawn, 32), v.wave.waves[1])
        v.goTo(Row.WAVE_PRESET)
        v.tap(Action.CLEAR)
        v.goTo(Row.WAVE_SAMPLES)
        v.tap(Action.PASTE)
        assertEquals(N163Waves.resampled(drawn, 32), v.wave.waves[1])
        // On a sound row, copy and paste take the whole sound, waves included.
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        val copied = s.song.nes.instruments[0]
        v.tap(Action.RIGHT)
        assertEquals(2, v.state.instrument)
        v.tap(Action.PASTE)
        assertEquals(copied, s.song.nes.instruments[1])
        // In a 2A03-only song it becomes a 2A03 sound without waves.
        val plain = nesInstrumentFor(session.song, copied)
        assertEquals(NesChip.APU, plain.chip)
        assertEquals(N163Wave.DEFAULT, plain.wave)
    }
}
