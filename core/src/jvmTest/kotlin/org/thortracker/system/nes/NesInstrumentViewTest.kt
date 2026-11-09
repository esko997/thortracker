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
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom
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
        assertEquals(NesInstrumentView.DPCM_PAGE + 1, NesInstrumentView.pages(vrc6.song, v.state))
        goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.VRC6, ins().chip)
        assertEquals(NesRules.VRC6_PULSE1, v.state.listenChannel) // Listen follows to a VRC6 channel
        assertTrue(Row.SAW in NesInstrumentView.rows(v.state, vrc6.song))
        assertEquals(NesInstrumentView.DPCM_PAGE, NesInstrumentView.pages(vrc6.song, v.state)) // no DPCM page
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

    /**
     * On an MMC5 song every sound is a 2A03 sound: no Chip row, Listen reaches the MMC5 pulses, which play every
     * envelope as the 2A03 pulses do, and then the PCM lane; auditions on the pulses keep their A-1 floor.
     */
    @Test
    fun mmc5SongsKeepOneChip() {
        val mmc5 = EditorSession(NesMmc5System.blank())
        val heard = ArrayList<Triple<Int, Int, Int>>()
        mmc5.onAudition = { ch, note, ins, _ -> heard += Triple(ch, note, ins) }
        val v = ViewController(mmc5, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1; listenChannel = NesRules.DPCM })
        assertFalse(Row.CHIP in NesInstrumentView.rows(v.state, mmc5.song))
        assertFalse(Row.SAW in NesInstrumentView.rows(v.state, mmc5.song))
        // Listen steps from DPCM onto the MMC5 pulses, then the PCM lane.
        v.state.instField = NesInstrumentView.rows(v.state, mmc5.song).indexOf(Row.LISTEN)
        v.tap(Action.RIGHT)
        assertEquals(NesRules.MMC5_PULSE1, v.state.listenChannel)
        v.tap(Action.RIGHT)
        assertEquals(NesRules.MMC5_PULSE2, v.state.listenChannel)
        v.tap(Action.RIGHT)
        assertEquals(NesRules.MMC5_PCM, v.state.listenChannel)
        // Their envelopes all apply, like a 2A03 pulse's.
        val chip = NesRules.chip(NesExpansion.MMC5, NesRules.MMC5_PULSE1)
        assertEquals(NesChip.APU, chip)
        for (kind in listOf(NesEnvelopeKind.VOLUME, NesEnvelopeKind.ARPEGGIO, NesEnvelopeKind.PITCH, NesEnvelopeKind.DUTY)) {
            assertNull(NesInstrumentView.channelNote(kind, NesRules.kind(NesExpansion.MMC5, NesRules.MMC5_PULSE2), NesChip.APU, channelChip = chip))
        }
        // Auditions on an MMC5 pulse: a 2A03 sound plays, below A-1 is refused.
        mmc5.audition(channel = NesRules.MMC5_PULSE1, note = 57, instrument = 1)
        assertEquals(listOf(Triple(NesRules.MMC5_PULSE1, 57, 1)), heard)
        mmc5.audition(channel = NesRules.MMC5_PULSE1, note = NesRules.LOWEST_TONAL - 1, instrument = 1)
        assertEquals("MMC5 Pulse 1 cannot play below A-1", mmc5.message)
        assertEquals(1, heard.size)
        // The bank has one chip: no other chip can be chosen.
        assertFalse(mmc5.setNesChip(1, NesChip.VRC6))
        assertEquals(NesChip.APU, mmc5.song.nes.instruments[0].chip)
    }

    /**
     * A 5B sound: no duty and no DPCM page; its pages are volume, arpeggio, pitch, mixer, noise and the hardware envelope,
     * whose rows set the shape, the period mode, the octave (clamped to -8..8) and the period (×2 per coarse step, at
     * most 65535). Leaving the 5B drops its mixer, noise and envelope.
     */
    @Test
    fun s5bSoundsEditMixerNoiseAndTheHardwareEnvelope() {
        val s5b = EditorSession(NesS5bSystem.blank())
        val v = ViewController(s5b, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1 })
        fun goTo(row: Row) { v.state.instField = NesInstrumentView.rows(v.state, s5b.song).indexOf(row) }
        val ins = { s5b.song.nes.instruments[0] }
        goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.S5B, ins().chip)
        assertEquals(NesRules.S5B_SQUARE1, v.state.listenChannel)
        assertEquals(NesInstrumentView.S5B_ENV_PAGE + 1, NesInstrumentView.pages(s5b.song, v.state))
        // Page 3 is the mixer: values 0-3, drawn up from the tone default.
        v.state.envelope = 3
        assertEquals(NesEnvelopeKind.MIXER, NesInstrumentView.kind(s5b.song, v.state))
        assertFalse(NesInstrumentView.dpcmPage(s5b.song, v.state))
        goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(5) { Action.UP })
        assertEquals(listOf(3), ins().mixer.values)
        v.hold(Action.EDIT, *Array(5) { Action.DOWN })
        assertEquals(listOf(0), ins().mixer.values)
        // Page 4 is the noise period, 0-31 (not the DPCM page on a 5B sound).
        v.state.envelope = 4
        assertEquals(NesEnvelopeKind.NOISE, NesInstrumentView.kind(s5b.song, v.state))
        goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(40) { Action.UP })
        assertEquals(listOf(31), ins().noise.values)
        // Page 5: the hardware envelope.
        v.state.envelope = NesInstrumentView.S5B_ENV_PAGE
        assertTrue(NesInstrumentView.s5bEnvPage(s5b.song, v.state))
        assertNull(NesInstrumentView.envelope(s5b.song, v.state))
        goTo(Row.ENV_SHAPE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(S5bShape.SAW_DOWN, ins().s5b.shape)
        goTo(Row.ENV_OCTAVE)
        v.hold(Action.EDIT, *Array(5) { Action.UP })
        assertEquals(S5bEnvelope.OCTAVES.last, ins().s5b.octave)
        goTo(Row.ENV_AUTO)
        v.tap(Action.EDIT)
        assertFalse(ins().s5b.autoPitch)
        goTo(Row.ENV_PERIOD)
        v.hold(Action.EDIT, *Array(8) { Action.UP })
        assertEquals(S5bEnvelope.PERIODS.last, ins().s5b.period)
        v.tap(Action.CLEAR)
        assertEquals(S5bEnvelope.DEFAULT.period, ins().s5b.period)
        // Back to 2A03: the 5B's envelopes and settings go.
        goTo(Row.CHIP)
        v.tap(Action.CLEAR)
        assertEquals(NesChip.APU, ins().chip)
        assertTrue(ins().mixer.values.isEmpty() && ins().noise.values.isEmpty())
        assertEquals(S5bEnvelope.DEFAULT, ins().s5b)
    }

    /**
     * On a VRC7 song a VRC7 sound's fourth page is its FM patch: editing a field of a built-in patch makes it the custom
     * patch, starting from that patch's registers; the sustain rows set the song's one setting; back to 2A03 (or pasted
     * into a song without the VRC7) the patch is dropped.
     */
    @Test
    fun vrc7SoundsEditTheirFmPatch() {
        val vrc7 = EditorSession(NesVrc7System.blank())
        val v = ViewController(vrc7, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1 })
        fun goTo(row: Row) { v.state.instField = NesInstrumentView.rows(v.state, vrc7.song).indexOf(row) }
        val ins = { vrc7.song.nes.instruments[0] }
        goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.VRC7, ins().chip)
        assertEquals(NesRules.VRC7_FM1, v.state.listenChannel) // Listen follows to an FM channel
        v.state.envelope = NesInstrumentView.FM_PAGE
        assertTrue(NesInstrumentView.fmPage(vrc7.song, v.state))
        // Bell → Guitar: a built-in patch holds its ROM registers.
        goTo(Row.PATCH)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(2, ins().fm.preset)
        assertEquals(Vrc7Presets.data(2), ins().fm.regs)
        // The carrier's attack one lower: now Custom, every other register still Guitar's.
        goTo(Row.FM_AR)
        v.tap(Action.RIGHT)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(Vrc7Presets.CUSTOM, ins().fm.preset)
        assertEquals(Vrc7Param.AR.get(Vrc7Presets.data(2), 1) - 1, Vrc7Param.AR.get(ins().fm.regs, 1))
        assertEquals(Vrc7Param.AR.get(Vrc7Presets.data(2), 0), Vrc7Param.AR.get(ins().fm.regs, 0))
        assertEquals(Vrc7Presets.data(2).filterIndexed { i, _ -> i != 5 }, ins().fm.regs.filterIndexed { i, _ -> i != 5 })
        // Release sustain is the song's setting, not the sound's.
        goTo(Row.SUSTAIN_RELEASE)
        v.tap(Action.EDIT)
        assertEquals(Vrc7Sustain(release = true), vrc7.song.nes.vrc7Sustain)
        v.tap(Action.CLEAR)
        assertEquals(Vrc7Sustain(), vrc7.song.nes.vrc7Sustain)
        // A VRC7 sound pasted into a 2A03 song becomes a 2A03 sound without its patch.
        val pasted = nesInstrumentFor(session.song, ins())
        assertEquals(NesChip.APU, pasted.chip)
        assertEquals(Vrc7Patch.DEFAULT, pasted.fm)
        // Back to 2A03.
        v.state.envelope = NesEnvelopeKind.VOLUME.ordinal
        goTo(Row.CHIP)
        v.tap(Action.CLEAR)
        assertEquals(NesChip.APU, ins().chip)
        assertEquals(Vrc7Patch.DEFAULT, ins().fm)
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
        assertTrue(NesInstrumentView.dpcmPage(session.song, v.state))
        v.tap(Action.NEXT_CHANNEL) // to volume (wraps)
        assertEquals(NesEnvelopeKind.VOLUME, NesInstrumentView.kind(session.song, v.state))
        v.tap(Action.PREV_CHANNEL); v.tap(Action.PREV_CHANNEL); v.tap(Action.PREV_CHANNEL) // DPCM, duty, pitch
        assertEquals(NesEnvelopeKind.PITCH, NesInstrumentView.kind(session.song, v.state))
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
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("no ROM install expected")
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
        assertFalse(NesInstrumentView.helpSections(Keymap.DEFAULT).any { it.first == "N163 sounds" || it.first == "FDS sounds" })
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
        v.goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.N163, ins().chip)
        assertEquals("N163 sounds have no duty", emptyList<Int>(), ins().duty.values)
        assertEquals(N163Wave.DEFAULT, ins().wave)
        assertEquals("Listen follows to an N163 channel", NesRules.N163_FIRST, v.state.listenChannel)
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
        assertEquals(NesEnvelopeKind.VOLUME, NesInstrumentView.kind(s.song, v.state))
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
        v.tap(Action.NEXT_CHANNEL) // from the duty page (the Waves page's place) to the DPCM page again
        assertTrue(NesInstrumentView.dpcmPage(s.song, v.state))
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
        assertEquals(WavePreset.SINE, N163Presets.presetOf(v.wave.waves, 16))
        assertEquals("Set wave preset", v.session.undoLabel)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(N163Presets.waves(WavePreset.TRIANGLE, 16, 1), v.wave.waves)
        // Three waves: the PWM presets change the width from wave to wave.
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, v.wave.waves.size)
        v.goTo(Row.WAVE_PRESET)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT }) // sawtooth, square 50%, square 25%, flat, PWM 1
        assertEquals(N163Presets.waves(WavePreset.PWM, 16, 3), v.wave.waves)
        v.tap(Action.EDIT)
        assertEquals(N163Presets.waves(WavePreset.PWM2, 16, 3), v.wave.waves)
        // From drawn waves the first step goes to the first preset.
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP)
        assertNull(N163Presets.presetOf(v.wave.waves, 16))
        v.goTo(Row.WAVE_PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(N163Presets.waves(WavePreset.SINE, 16, 3), v.wave.waves)
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
        assertEquals(NesClip.Wave(drawn, N163Wave.SAMPLE.last), s.clipboard)
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

    // ---- FDS --------------------------------------------------------------------------------------------------

    /** An FDS song whose sound 1 is an FDS sound, shown on [page] (its Waves page unless asked). */
    private fun fds(page: Int = NesInstrumentView.WAVE_PAGE): ViewController {
        val s = EditorSession(NesFdsSystem.blank())
        s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        assertTrue(s.setNesChip(1, NesChip.FDS))
        return ViewController(s, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1; envelope = page })
    }

    private val ViewController.fds: FdsSound get() = session.song.nes.instruments[state.instrument - 1].fds

    /** On an FDS song a sound picks its chip; FDS sounds keep three envelopes, then Waves and Modulation pages. */
    @Test
    fun fdsSoundsChooseTheirChipAndGetTheWavesAndModulationPages() {
        val s = EditorSession(NesFdsSystem.blank())
        s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        val v = ViewController(s, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1; envelope = NesEnvelopeKind.DUTY.ordinal })
        val ins = { s.song.nes.instruments[0] }
        val rows = { NesInstrumentView.rows(v.state, s.song) }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.UP, Action.UP)
        assertEquals(listOf(2), ins().duty.values)
        assertTrue(Row.CHIP in rows())
        assertEquals("2A03 sounds keep DPCM", NesInstrumentView.DPCM_PAGE + 1, NesInstrumentView.pages(s.song, v.state))
        v.goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.FDS, ins().chip)
        assertEquals("FDS sounds have no duty", emptyList<Int>(), ins().duty.values)
        assertEquals(FdsSound.DEFAULT, ins().fds)
        assertEquals("Listen follows to the FDS channel", NesRules.FDS, v.state.listenChannel)
        assertEquals("volume, arpeggio, pitch, Waves, Modulation", NesInstrumentView.MOD_PAGE + 1, NesInstrumentView.pages(s.song, v.state))
        assertTrue("the duty page is now the Waves page", NesInstrumentView.fdsWavePage(s.song, v.state))
        assertFalse(NesInstrumentView.wavePage(s.song, v.state))
        assertEquals(NesInstrumentView.FDS_WAVE_ROWS, rows())
        assertFalse("no size or position on FDS waves", Row.WAVE_SIZE in rows() || Row.WAVE_POSITION in rows())
        assertEquals(Row.CHIP, NesInstrumentView.row(v.state, s.song))
        assertNull(NesInstrumentView.envelope(s.song, v.state))
        // Listen plays the sound on the FDS channel.
        v.tap(Action.AUDITION)
        assertEquals(NesRules.FDS, auditions.last().first)
        assertEquals(1, auditions.last().third)
        // The fifth page is Modulation, not DPCM.
        v.tap(Action.NEXT_CHANNEL)
        assertTrue(NesInstrumentView.modPage(s.song, v.state))
        assertFalse(NesInstrumentView.dpcmPage(s.song, v.state))
        assertEquals(NesInstrumentView.MOD_ROWS, rows())
        assertEquals(Row.CHIP, NesInstrumentView.row(v.state, s.song))
        // The pages wrap: volume, arpeggio, pitch, Waves, Modulation.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesEnvelopeKind.VOLUME, NesInstrumentView.kind(s.song, v.state))
        assertEquals(listOf(Row.SLOT, Row.NAME, Row.CHIP, Row.LISTEN, Row.ENVELOPE, Row.LENGTH, Row.LOOP, Row.RELEASE, Row.STEPS), rows())
        v.tap(Action.PREV_CHANNEL)
        assertTrue(NesInstrumentView.modPage(s.song, v.state))
        v.tap(Action.PREV_CHANNEL)
        assertTrue(NesInstrumentView.fdsWavePage(s.song, v.state))
        // What the FDS channel does with the envelopes; the N163's texts are unchanged.
        assertEquals("FDS: volume 0-15 sets the gain 0-30 (× 2)", NesInstrumentView.channelNote(NesEnvelopeKind.VOLUME, ChannelKind.WAVE, NesChip.FDS))
        assertEquals("+ raises the pitch (FDS frequency steps)", NesInstrumentView.channelNote(NesEnvelopeKind.PITCH, ChannelKind.WAVE, NesChip.FDS))
        assertEquals("FDS sounds play on the FDS channel only",
            NesInstrumentView.channelNote(NesEnvelopeKind.VOLUME, ChannelKind.PULSE, NesChip.FDS, channelChip = NesChip.APU))
        assertEquals("N163: 4-bit linear volume", NesInstrumentView.channelNote(NesEnvelopeKind.VOLUME, ChannelKind.WAVE, NesChip.N163))
        // Back to 2A03: the FDS settings reset, the DPCM page returns.
        v.goTo(Row.FDS_MASTER)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(FdsMasterVolume.TWO_THIRDS, ins().fds.masterVolume)
        v.goTo(Row.CHIP)
        v.tap(Action.CLEAR)
        assertEquals(NesChip.APU, ins().chip)
        assertEquals(FdsSound.DEFAULT, ins().fds)
        assertEquals(NesInstrumentView.DPCM_PAGE + 1, NesInstrumentView.pages(s.song, v.state))
        // A copied FDS sound pasted into a 2A03 song becomes a 2A03 sound without FDS settings.
        val plain = nesInstrumentFor(session.song, NesInstrument("F", chip = NesChip.FDS, fds = FdsSound(modDepth = 9)))
        assertEquals(NesChip.APU, plain.chip)
        assertEquals(FdsSound.DEFAULT, plain.fds)
    }

    @Test
    fun fdsHelpDescribesTheWavesAndModulationPages() {
        val sections = NesInstrumentView.helpSections(Keymap.DEFAULT, NesExpansion.FDS).toMap()
        val sounds = sections.getValue("FDS sounds").toMap()
        val mod = sections.getValue("FDS modulation").toMap()
        assertTrue(listOf("Chip", "Waves page", "Tuning", "Preset", "Master volume", "Wave", "Repeat", "Loop / release", "Samples").all { it in sounds })
        assertTrue(sounds.getValue("Tuning").contains(NesInstrumentView.FDS_TUNING))
        assertTrue(NesInstrumentView.FDS_TUNING.startsWith("One cycle per wave sounds as named"))
        assertTrue(listOf("Modulation page", "Table", "Preset", "Speed", "Depth", "Delay", "Auto-mod").all { it in mod })
        assertFalse("N163 help stays the N163's", "FDS sounds" in NesInstrumentView.helpSections(Keymap.DEFAULT, NesExpansion.N163_4).toMap())
        assertFalse("FDS sounds" in NesInstrumentView.helpSections(Keymap.DEFAULT, NesExpansion.VRC7).toMap())
    }

    @Test
    fun fdsWaveSamplesAreDrawnWithTheController() {
        val v = fds()
        val sine = FdsPresets.SINE
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP, Action.UP)
        val raised = sine.toMutableList().also { it[0] = sine[0] + 2 }
        assertEquals(listOf<List<Int>>(raised), v.fds.waves)
        // Edit+→ carries the value along, one undo step per hold.
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        val drawn = raised.toMutableList().also { for (i in 1..3) it[i] = raised[0] }
        assertEquals(listOf<List<Int>>(drawn), v.fds.waves)
        assertEquals(3, v.state.waveCol)
        assertNull("drawn waves are Custom", FdsPresets.presetOf(v.fds.waves))
        v.session.undo()
        assertEquals(listOf<List<Int>>(raised), v.fds.waves)
        // Samples stay 0..63.
        v.hold(Action.EDIT, *Array(70) { Action.UP })
        assertEquals(63, v.fds.waves[0][3])
        v.hold(Action.EDIT, *Array(70) { Action.DOWN })
        assertEquals(0, v.fds.waves[0][3])
        // Clear puts the sample back to the middle level, 32.
        v.tap(Action.CLEAR)
        assertEquals(FdsEdits.CENTRE, v.fds.waves[0][3])
        assertEquals("Clear wave sample", v.session.undoLabel)
        // Clear held: ←→ jump 8 samples and clear nothing; the wave has 64 samples.
        v.hold(Action.CLEAR, Action.RIGHT)
        assertEquals(11, v.state.waveCol)
        v.hold(Action.CLEAR, *Array(10) { Action.RIGHT })
        assertEquals(FdsSound.WAVE_SIZE - 1, v.state.waveCol)
        assertEquals(FdsEdits.CENTRE, v.fds.waves[0][3])
        v.tap(Action.LEFT)
        assertEquals(62, v.state.waveCol)
    }

    @Test
    fun fdsWavesAreAddedAndDeletedWithinFamiStudiosLimit() {
        val v = fds()
        v.goTo(Row.WAVE_INDEX)
        v.tap(Action.EDIT)
        assertEquals(2, v.fds.waves.size)
        assertEquals("the copy is shown", 1, v.state.wave)
        assertEquals("Duplicate wave", v.session.undoLabel)
        v.hold(Action.EDIT, Action.UP) // eight more at the end
        assertEquals(10, v.fds.waves.size)
        assertEquals(10, v.fds.repeat.size)
        // FamiStudio keeps at most 16 waves per FDS sound.
        v.hold(Action.EDIT, Action.UP)
        assertEquals(FdsSound.MAX_WAVES, v.fds.waves.size)
        v.tap(Action.EDIT)
        assertEquals(FdsSound.MAX_WAVES, v.fds.waves.size)
        assertEquals("Sound 01 holds 16 waves, the most FamiStudio keeps for an FDS sound", v.session.message)
        v.tap(Action.LEFT)
        assertEquals(0, v.state.wave)
        v.tap(Action.CLEAR)
        assertEquals(FdsSound.MAX_WAVES - 1, v.fds.waves.size)
        assertEquals("Delete wave", v.session.undoLabel)
        // A sound keeps at least one wave.
        repeat(20) { v.tap(Action.CLEAR) }
        assertEquals(1, v.fds.waves.size)
        assertEquals(0, v.state.wave)
    }

    @Test
    fun fdsWaveRepeatLoopAndReleaseFollowTheVolumeEnvelopesRules() {
        val v = fds()
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(4, v.fds.waves.size)
        v.goTo(Row.WAVE_RELEASE)
        v.hold(Action.EDIT, Action.RIGHT) // a release needs a loop: it loops from wave 0 and releases at wave 1
        assertEquals(0, v.fds.loop)
        assertEquals(1, v.fds.release)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals("release < count", 3, v.fds.release)
        v.goTo(Row.WAVE_LOOP)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT })
        assertEquals("the loop stays before the release", 2, v.fds.loop)
        // Deleting a wave before them moves both.
        v.goTo(Row.WAVE_INDEX)
        v.tap(Action.CLEAR)
        assertEquals(3, v.fds.waves.size)
        assertEquals(1, v.fds.loop)
        assertEquals(2, v.fds.release)
        // Fewer waves drop a release past the end.
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(2, v.fds.waves.size)
        assertEquals(1, v.fds.loop)
        assertEquals(-1, v.fds.release)
        v.goTo(Row.WAVE_LOOP)
        v.tap(Action.CLEAR)
        assertEquals(-1, v.fds.loop)
        assertEquals("Clear wave loop", v.session.undoLabel)
        // No release without a loop; none on a single wave.
        assertEquals(-1, FdsEdits.fixed(FdsSound(waves = List(3) { FdsPresets.SINE }, repeat = listOf(1, 1, 1), release = 2)).release)
        assertEquals(-1, FdsEdits.withRelease(FdsSound.DEFAULT, 1).release)
        // Repeats are 1-15 frames per wave.
        v.goTo(Row.WAVE_REPEAT)
        v.hold(Action.EDIT, *Array(5) { Action.UP })
        assertEquals(listOf(15, 1), v.fds.repeat)
        v.tap(Action.CLEAR)
        assertEquals(listOf(1, 1), v.fds.repeat)
    }

    @Test
    fun fdsPresetsApplyFamiStudiosWavesAndThePwmSweep() {
        val v = fds()
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.DOWN)
        assertNull(FdsPresets.presetOf(v.fds.waves))
        v.goTo(Row.WAVE_PRESET)
        v.tap(Action.CLEAR)
        assertEquals(listOf(FdsPresets.SINE), v.fds.waves)
        assertEquals("Set wave preset", v.session.undoLabel)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(FdsPresets.waves(WavePreset.TRIANGLE, 1), v.fds.waves)
        // Three waves: the PWM presets change the width from wave to wave.
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, v.fds.waves.size)
        v.goTo(Row.WAVE_PRESET)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT }) // sawtooth, square 50%, square 25%, flat, PWM 1
        assertEquals(FdsPresets.waves(WavePreset.PWM, 3), v.fds.waves)
        assertEquals("each wave its own width", 3, v.fds.waves.distinct().size)
        v.tap(Action.EDIT)
        assertEquals(FdsPresets.waves(WavePreset.PWM2, 3), v.fds.waves)
        // From drawn waves the first step goes to the first preset.
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP)
        assertNull(FdsPresets.presetOf(v.fds.waves))
        v.goTo(Row.WAVE_PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(FdsPresets.waves(WavePreset.SINE, 3), v.fds.waves)
    }

    @Test
    fun fdsMasterVolumeSteps() {
        val v = fds()
        v.goTo(Row.FDS_MASTER)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(FdsMasterVolume.TWO_THIRDS, v.fds.masterVolume)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT })
        assertEquals(FdsMasterVolume.TWO_FIFTHS, v.fds.masterVolume)
        v.tap(Action.CLEAR)
        assertEquals(FdsMasterVolume.FULL, v.fds.masterVolume)
        assertEquals("Set master volume", v.session.undoLabel)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(FdsMasterVolume.FULL, v.fds.masterVolume)
    }

    @Test
    fun fdsCopyPasteKeepsAndRescalesWaves() {
        val v = fds()
        val s = v.session
        v.goTo(Row.WAVE_SAMPLES)
        v.hold(Action.EDIT, Action.UP)
        val drawn = v.fds.waves[0]
        v.tap(Action.COPY)
        assertEquals(NesClip.Wave(drawn, FdsSound.SAMPLE.last), s.clipboard)
        v.goTo(Row.WAVE_INDEX)
        v.hold(Action.EDIT, Action.RIGHT) // a second wave
        v.tap(Action.RIGHT) // shown
        assertEquals(1, v.state.wave)
        v.goTo(Row.WAVE_PRESET)
        v.tap(Action.CLEAR) // both waves sine
        v.goTo(Row.WAVE_SAMPLES)
        v.tap(Action.PASTE)
        assertEquals(listOf(FdsPresets.SINE, drawn), v.fds.waves)
        assertEquals("Paste wave", s.undoLabel)
        // Waves crossing chips are rescaled to the other chip's levels (and resampled to its size).
        assertEquals(listOf(0, 34, 63), NesClip.Wave(listOf(0, 8, 15), N163Wave.SAMPLE.last).levels(FdsSound.SAMPLE.last))
        assertEquals(listOf(0, 8, 15), NesClip.Wave(listOf(0, 32, 63), FdsSound.SAMPLE.last).levels(N163Wave.SAMPLE.last))
        val fromN163 = FdsEdits.withWave(FdsSound.DEFAULT, 0, NesClip.Wave(N163Presets.sine(16), N163Wave.SAMPLE.last).levels(FdsSound.SAMPLE.last))
        assertEquals(FdsSound.WAVE_SIZE, fromN163.waves[0].size)
        // The Waves and Modulation pages have no envelope to paste into.
        v.state.envelope = NesEnvelopeKind.VOLUME.ordinal
        v.goTo(Row.STEPS)
        v.tap(Action.COPY)
        assertTrue(s.clipboard is NesClip.Envelope)
        v.state.envelope = NesInstrumentView.WAVE_PAGE
        v.goTo(Row.WAVE_PRESET)
        v.tap(Action.PASTE)
        assertEquals("The Waves page has no envelope: paste envelopes on the volume, arpeggio or pitch page", s.message)
        v.state.envelope = NesInstrumentView.MOD_PAGE
        v.goTo(Row.MOD_DEPTH)
        v.tap(Action.PASTE)
        assertEquals("The Modulation page has no envelope: paste envelopes on the volume, arpeggio or pitch page", s.message)
    }

    @Test
    fun theModulationTableIsDrawnBesideTheCurveTheChipPlays() {
        val v = fds(NesInstrumentView.MOD_PAGE)
        assertTrue(NesInstrumentView.modPage(v.session.song, v.state))
        assertEquals(NesInstrumentView.MOD_ROWS, NesInstrumentView.rows(v.state, v.session.song))
        assertEquals(FdsPresets.FLAT_MOD, v.fds.modulation)
        v.goTo(Row.MOD_TABLE)
        // Entry 0 always resets the counter, whatever its target.
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(10, v.fds.modulation[0])
        assertEquals(FdsSound.MOD_RESET, v.fds.modTable[0])
        assertEquals("Entry 0 of 32: target 10, plays 0 (reset)", NesInstrumentView.modEntryText(v.fds, 0))
        // ←→ move along the table; a target the chip reaches in one step plays as drawn.
        v.tap(Action.RIGHT)
        assertEquals(1, v.state.programField)
        v.hold(Action.EDIT, *Array(8) { Action.UP })
        assertEquals(8, v.fds.modulation[1])
        assertEquals(8, v.fds.modCurve[1])
        assertEquals("Entry 1 of 32: target 8, plays 8 (step +8)", NesInstrumentView.modEntryText(v.fds, 1))
        // Edit+→ carries the target along.
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, v.state.programField)
        assertEquals(listOf(10, 8, 8, 8), v.fds.modulation.take(4))
        // A jump bigger than the chip's steps: FamiStudio's greedy encoding gets only part way, and the curve shows it.
        v.hold(Action.EDIT, *Array(22) { Action.UP })
        assertEquals(30, v.fds.modulation[3])
        assertEquals(16, v.fds.modCurve[3])
        assertEquals("Entry 3 of 32: target 30, plays 16 (step +8)", NesInstrumentView.modEntryText(v.fds, 3))
        assertEquals(FdsSound.modTableCurve(FdsSound.encodeModulation(v.fds.modulation)), v.fds.modCurve)
        // Targets stay −64..63.
        v.hold(Action.EDIT, *Array(50) { Action.UP })
        assertEquals(63, v.fds.modulation[3])
        v.hold(Action.EDIT, *Array(200) { Action.DOWN })
        assertEquals(-64, v.fds.modulation[3])
        v.tap(Action.CLEAR)
        assertEquals(0, v.fds.modulation[3])
        assertEquals("Clear mod target", v.session.undoLabel)
        // Clear held: ←→ jump 8 entries; the table has 32.
        v.hold(Action.CLEAR, Action.RIGHT)
        assertEquals(11, v.state.programField)
        v.hold(Action.CLEAR, *Array(5) { Action.RIGHT })
        assertEquals(FdsSound.MOD_SIZE - 1, v.state.programField)
        // Table presets: FamiStudio's, without the PWM ones.
        v.goTo(Row.MOD_PRESET)
        assertNull(FdsPresets.modPresetOf(v.fds.modulation))
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(FdsPresets.modulation(WavePreset.SINE), v.fds.modulation)
        v.hold(Action.EDIT, *Array(10) { Action.RIGHT })
        assertEquals("Flat is the last table preset", FdsPresets.FLAT_MOD, v.fds.modulation)
        v.tap(Action.EDIT)
        assertEquals(FdsPresets.FLAT_MOD, v.fds.modulation)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(FdsPresets.modulation(WavePreset.SQUARE25), v.fds.modulation)
        v.tap(Action.CLEAR)
        assertEquals(FdsPresets.FLAT_MOD, v.fds.modulation)
        assertEquals("Set mod preset", v.session.undoLabel)
    }

    @Test
    fun modSpeedStepsReachEveryManualSpeed() {
        val v = fds(NesInstrumentView.MOD_PAGE)
        v.goTo(Row.MOD_SPEED)
        v.hold(Action.EDIT, *Array(8) { Action.UP }) // 16 … 128: a low byte of $80 is a speed like any other
        assertEquals(0x80, v.fds.modSpeed)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(0x7F, v.fds.modSpeed)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(0x81, v.fds.modSpeed)
        v.hold(Action.EDIT, *Array(300) { Action.UP })
        assertEquals(FdsSound.MOD_SPEED.last, v.fds.modSpeed)
        v.tap(Action.CLEAR)
        assertEquals(0, v.fds.modSpeed)
        assertEquals("Set mod speed", v.session.undoLabel)
        // The row shows the table's rate: CPU × speed / 2^22.
        assertEquals(1789773.0 * 12 / 4194304.0, NesInstrumentView.modRateHz(12), 1e-9)
        assertEquals("12: the table runs at 5.12 Hz", NesInstrumentView.modSpeedText(FdsSound(modSpeed = 12)))
        assertEquals("0: the table stands still", NesInstrumentView.modSpeedText(FdsSound()))
        assertTrue(NesInstrumentView.modSpeedText(FdsSound(modSpeed = 0x80)).startsWith("128: the table runs at"))
    }

    @Test
    fun modDepthAndDelayStayInTheirRanges() {
        val v = fds(NesInstrumentView.MOD_PAGE)
        v.goTo(Row.MOD_DEPTH)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(63, v.fds.modDepth)
        v.tap(Action.CLEAR)
        assertEquals(0, v.fds.modDepth)
        assertEquals("Set mod depth", v.session.undoLabel)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, v.fds.modDepth)
        assertEquals("0: no modulation", NesInstrumentView.modDepthText(0))
        v.goTo(Row.MOD_DELAY)
        v.hold(Action.EDIT, *Array(40) { Action.UP })
        assertEquals(255, v.fds.modDelay)
        v.tap(Action.CLEAR)
        assertEquals(0, v.fds.modDelay)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(0, v.fds.modDelay)
        // FamiStudio's engine turns modulation on in frame d: delay d holds it off for d − 1 frames.
        assertEquals("1: modulation from the note's first frame", NesInstrumentView.modDelayText(1))
        assertEquals("3: modulation held off for the first 2 frames (33 ms)", NesInstrumentView.modDelayText(3))
    }

    @Test
    fun autoModTogglesAndSetsItsRatio() {
        val v = fds(NesInstrumentView.MOD_PAGE)
        // The ratio rows are greyed while auto-mod is off.
        v.goTo(Row.MOD_NUMER)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, v.fds.autoModNumer)
        assertEquals("Turn Auto-mod on to set its ratio", v.session.message)
        v.goTo(Row.MOD_AUTO)
        v.tap(Action.EDIT)
        assertTrue(v.fds.autoMod)
        assertEquals("Set auto-mod", v.session.undoLabel)
        // With auto-mod on, the notes set the speed: the speed row is greyed.
        v.goTo(Row.MOD_SPEED)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, v.fds.modSpeed)
        assertEquals("Auto-mod sets the mod speed from each note: turn Auto-mod off to set it", v.session.message)
        assertTrue(NesInstrumentView.modSpeedText(v.fds).startsWith("auto: the note's frequency × 1"))
        v.goTo(Row.MOD_NUMER)
        v.hold(Action.EDIT, *Array(5) { Action.RIGHT })
        assertEquals(6, v.fds.autoModNumer)
        v.goTo(Row.MOD_DENOM)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(4, v.fds.autoModDenom)
        assertEquals(3 to 2, v.fds.autoModFraction)
        assertEquals("3/2", NesInstrumentView.ratioText(v.fds))
        assertEquals("× 3/2 (reduced from 6/4)", NesInstrumentView.autoModText(v.fds))
        // 1..32 each.
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(32, v.fds.autoModDenom)
        v.tap(Action.CLEAR)
        assertEquals(1, v.fds.autoModDenom)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(1, v.fds.autoModDenom)
        // Large numerators overflow the engine's 16-bit multiply on high notes: the row says from which note.
        assertTrue(NesInstrumentView.autoModText(FdsSound(autoMod = true, autoModNumer = 32)).contains("overflows"))
        assertEquals("× 1/1", NesInstrumentView.autoModText(FdsSound(autoMod = true)))
        // Off again: Edit+←→ and Clear.
        v.goTo(Row.MOD_AUTO)
        v.hold(Action.EDIT, Action.RIGHT)
        assertFalse(v.fds.autoMod)
        v.tap(Action.EDIT)
        assertTrue(v.fds.autoMod)
        v.tap(Action.CLEAR)
        assertFalse(v.fds.autoMod)
    }

    /** Moving between slots on the Modulation page keeps it for FDS sounds and leaves it for others. */
    @Test
    fun theModulationPageFollowsTheSlotsChip() {
        val v = fds(NesInstrumentView.MOD_PAGE)
        assertTrue(v.session.setNesChip(2, NesChip.FDS))
        v.goTo(Row.MOD_PRESET)
        v.tap(Action.RIGHT)
        assertEquals(2, v.state.instrument)
        assertTrue(NesInstrumentView.modPage(v.session.song, v.state))
        v.tap(Action.RIGHT) // slot 3: a 2A03 sound
        assertEquals(3, v.state.instrument)
        assertEquals(0, v.state.envelope)
        assertFalse(NesInstrumentView.dpcmPage(v.session.song, v.state))
    }

    // ---- EPSM -----------------------------------------------------------------------------------------------

    /**
     * An EPSM sound on an EPSM song: no duty, no DPCM page; mixer and noise envelopes, the SSG hardware envelope, the FM
     * patch through the Mega Drive editor's curated rows and the Expert grid (each edit lands in the exported registers),
     * and the rhythm page, where the level is the volume envelope's first step and the pitch is fixed. Back to 2A03 the
     * EPSM settings go.
     */
    @Test
    fun epsmSoundsEditSsgFmAndRhythmPages() {
        val epsm = EditorSession(NesEpsmSystem.blank())
        val expansion = NesExpansion.EPSM
        val v = ViewController(epsm, ViewState(kind = ViewKind.INSTRUMENT).apply { instrument = 1 })
        fun goTo(row: Row) {
            v.state.instField = NesInstrumentView.rows(v.state, epsm.song).indexOf(row)
            assertEquals(row, NesInstrumentView.row(v.state, epsm.song))
        }
        val ins = { epsm.song.nes.instruments[0] }
        goTo(Row.CHIP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(NesChip.EPSM, ins().chip)
        assertEquals(NesChip.EPSM, NesRules.chip(expansion, v.state.listenChannel)) // Listen follows to an EPSM channel
        assertEquals(NesInstrumentView.EPSM_RHYTHM_PAGE + 1, NesInstrumentView.pages(epsm.song, v.state))
        assertTrue(ins().duty.values.isEmpty())

        // Page 3 is the SSG mixer (0-3), page 4 the noise period (0-31): not duty and DPCM on an EPSM sound.
        v.state.envelope = 3
        assertEquals(NesEnvelopeKind.MIXER, NesInstrumentView.kind(epsm.song, v.state))
        goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(5) { Action.UP })
        assertEquals(listOf(3), ins().mixer.values)
        v.state.envelope = 4
        assertFalse(NesInstrumentView.dpcmPage(epsm.song, v.state))
        assertEquals(NesEnvelopeKind.NOISE, NesInstrumentView.kind(epsm.song, v.state))
        goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(40) { Action.UP })
        assertEquals(listOf(31), ins().noise.values)

        // Page 5: the SSG hardware envelope (the 5B's page, the shared `s5b` settings); Listen moves to an SSG channel.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesInstrumentView.S5B_ENV_PAGE, v.state.envelope)
        assertTrue(NesInstrumentView.s5bEnvPage(epsm.song, v.state))
        assertNull(NesInstrumentView.epsmPage(epsm.song, v.state))
        assertNull(NesInstrumentView.envelope(epsm.song, v.state))
        assertTrue(NesRules.isEpsmSsg(expansion, v.state.listenChannel))
        goTo(Row.ENV_SHAPE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(S5bShape.SAW_DOWN, ins().s5b.shape)
        goTo(Row.ENV_AUTO)
        v.tap(Action.EDIT)
        assertFalse(ins().s5b.autoPitch)
        // An EPSM sound starts from FamiStudio's EPSM default period, 4000 (the 5B's is 1000), and Clear returns to it.
        goTo(Row.ENV_PERIOD)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(2 * S5bEnvelope.EPSM_DEFAULT.period, ins().s5b.period)
        v.tap(Action.CLEAR)
        assertEquals(S5bEnvelope.EPSM_DEFAULT.period, ins().s5b.period)

        // Page 6: the FM patch's curated rows; Listen moves to FM 1.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesInstrumentView.EPSM_FM_PAGE, NesInstrumentView.epsmPage(epsm.song, v.state))
        assertEquals(NesRules.EPSM_FM1, v.state.listenChannel)
        val before = ins().epsm
        assertEquals(EpsmPatch.DEFAULT, before)
        goTo(Row.OPN_ALGORITHM)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((before.algorithm + 1) % 8, ins().epsm.algorithm)
        assertEquals((ins().epsm.feedback shl 3) or ins().epsm.algorithm, ins().epsm.registers(0)[0])
        goTo(Row.OPN_FEEDBACK)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(2, ins().epsm.feedback)
        assertEquals((2 shl 3) or ins().epsm.algorithm, ins().epsm.registers(0)[0])
        goTo(Row.EPSM_PAN)
        v.hold(Action.EDIT, Action.RIGHT)
        assertTrue(ins().epsm.left && !ins().epsm.right)
        assertEquals(0x80, ins().epsm.registers(0)[1] and 0xC0) // $B4: L on, R off
        // A preset loads a Mega Drive FM voice's operators, keeping the pan.
        goTo(Row.OPN_PRESET)
        v.tap(Action.EDIT)
        assertEquals(NesEpsmPage.PRESETS.first().name, NesEpsmPage.preset(ins().epsm)?.name)
        assertTrue(ins().epsm.left && !ins().epsm.right)
        v.tap(Action.CLEAR)
        assertEquals(EpsmPatch.DEFAULT.copy(right = false), ins().epsm)

        // Page 7: every register; the operator column moves with left/right, edits land in that operator's registers.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesInstrumentView.EPSM_EXPERT_PAGE, NesInstrumentView.epsmPage(epsm.song, v.state))
        goTo(Row.OPN_OP_TL)
        v.tap(Action.RIGHT)
        v.tap(Action.RIGHT)
        assertEquals(2, NesEpsmPage.operator(v.state))
        val tl = ins().epsm.operators[2].tl
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(tl + 1, ins().epsm.operators[2].tl)
        assertEquals(tl + 1, ins().epsm.registers(0)[2 + 7 * 2 + 1]) // operator 3's $40
        assertEquals(EpsmPatch.DEFAULT.operators[1], ins().epsm.operators[1])
        goTo(Row.OPN_OP_AM)
        v.tap(Action.EDIT)
        assertTrue(ins().epsm.operators[2].am)
        assertEquals(0x80, ins().epsm.registers(0)[2 + 7 * 2 + 3] and 0x80) // operator 3's $60 AM bit
        goTo(Row.OPN_OP_SSG)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(8, ins().epsm.operators[2].ssgEg)
        assertEquals(8, ins().epsm.registers(0)[2 + 7 * 2 + 6]) // operator 3's $90
        goTo(Row.OPN_FMS)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, ins().epsm.pms)
        goTo(Row.OPN_OP_TL)
        v.tap(Action.CLEAR)
        assertEquals(EpsmPatch.DEFAULT.operators[2].tl, ins().epsm.operators[2].tl)

        // Page 8: the rhythm drums play one fixed ROM sound each; the level is the volume envelope's first step.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesInstrumentView.EPSM_RHYTHM_PAGE, NesInstrumentView.epsmPage(epsm.song, v.state))
        assertEquals(NesRules.EPSM_RHYTHM1, v.state.listenChannel)
        goTo(Row.RHYTHM_LEVEL)
        val level = NesEpsmPage.rhythmLevel(ins())
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(level - 1, ins().volume.values.first())
        assertEquals(listOf("Kick", "Snare", "Cymbal", "Hi-hat", "Tom", "Rim"),
            NesEpsmPage.DRUM_REGISTERS.indices.map { NesRules.channelName(expansion, NesRules.EPSM_RHYTHM1 + it) })
        // The ROM row says whether the ROM is installed, and Edit there opens the ROM picker.
        if (!Ym2608RhythmRom.installed) assertTrue(NesEpsmPage.dim(epsm.song, ins(), Row.RHYTHM_ROM))
        val picks = ArrayList<Pair<List<String>, Int>>()
        epsm.onMenuRequest = { build ->
            build(object : org.thortracker.editor.MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) = Unit
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) {
                    picks += extensions to maxBytes
                }
                override fun status(message: String) = Unit
                override fun error(message: String) = throw AssertionError(message)
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("nothing was picked")
            })
        }
        goTo(Row.RHYTHM_ROM)
        v.tap(Action.EDIT)
        assertEquals(listOf(Ym2608RhythmRom.EXTENSIONS to 64 * 1024), picks)

        // Back to 2A03: the EPSM's patch, mixer, noise and hardware envelope go, and so do its pages.
        goTo(Row.CHIP)
        v.tap(Action.CLEAR)
        assertEquals(NesChip.APU, ins().chip)
        assertEquals(0, v.state.envelope)
        assertTrue(ins().mixer.values.isEmpty() && ins().noise.values.isEmpty())
        assertEquals(EpsmPatch.DEFAULT, ins().epsm)
        assertEquals(S5bEnvelope.DEFAULT, ins().s5b)
    }

    /** Selecting a rhythm channel in the pattern shows an EPSM sound's rhythm page; another channel leaves it. */
    @Test
    fun aRhythmChannelSelectionShowsTheRhythmPage() {
        val epsm = EditorSession(NesEpsmSystem.blank())
        epsm.setNesChip(1, NesChip.EPSM)
        val st = ViewState(kind = ViewKind.INSTRUMENT)
        NesInstrumentView.follow(st, epsm.selection.copy(instrument = 1, channel = NesRules.EPSM_RHYTHM1 + 2), epsm.song)
        assertEquals(NesInstrumentView.EPSM_RHYTHM_PAGE, st.envelope)
        NesInstrumentView.follow(st, epsm.selection.copy(instrument = 1, channel = NesRules.EPSM_FM1), epsm.song)
        assertEquals(0, st.envelope)
    }

    /**
     * The rhythm ROM import checks the file before the host sees it: a wrong size or a wrong SHA-256 is an error naming
     * the file and the host keeps nothing. The accepted path needs the user's own ROM (never in the repository): set
     * THOR_RHYTHM_ROM to its path to run it.
     */
    @Test
    fun theRhythmRomImportChecksSizeAndHashBeforeInstalling() {
        val installed = ArrayList<ByteArray>()
        val errors = ArrayList<String>()
        val statuses = ArrayList<String>()
        val menu = object : org.thortracker.editor.MenuContext {
            override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) = Unit
            override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = Unit
            override fun status(message: String) { statuses += message }
            override fun error(message: String) { errors += message }
            override fun installRhythmRom(bytes: ByteArray): String? { installed += bytes; return null }
        }
        RhythmRomMenu.import(menu, "short.bin", ByteArray(100))
        assertTrue(installed.isEmpty())
        assertTrue(errors.last(), errors.last().startsWith("short.bin was not installed: ") && errors.last().contains("${Ym2608RhythmRom.SIZE}"))
        RhythmRomMenu.import(menu, "zeros.rom", ByteArray(Ym2608RhythmRom.SIZE))
        assertTrue(installed.isEmpty())
        assertTrue(errors.last(), errors.last().startsWith("zeros.rom was not installed: ") && errors.last().contains("SHA-256"))
        assertTrue(statuses.isEmpty())
        val path = System.getenv("THOR_RHYTHM_ROM") ?: return
        val rom = java.io.File(path).readBytes()
        RhythmRomMenu.import(menu, "ym2608_adpcm_rom.bin", rom)
        assertTrue(installed.single() === rom)
        assertTrue(statuses.single().startsWith("YM2608 rhythm ROM installed from ym2608_adpcm_rom.bin"))
        // A host that cannot keep it reports why.
        val failing = object : org.thortracker.editor.MenuContext by menu {
            override fun installRhythmRom(bytes: ByteArray): String? = "the disk is full"
        }
        RhythmRomMenu.import(failing, "ym2608_adpcm_rom.bin", rom)
        assertEquals("ym2608_adpcm_rom.bin was not installed: the disk is full", errors.last())
    }
}
