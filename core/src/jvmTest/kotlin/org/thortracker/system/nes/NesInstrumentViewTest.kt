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
        assertEquals("off", NesInstrumentView.valueText(NesEnvelopeKind.MIXER, 3, ChannelKind.PULSE, NesChip.EPSM))
        v.state.envelope = 4
        assertFalse(NesInstrumentView.dpcmPage(epsm.song, v.state))
        assertEquals(NesEnvelopeKind.NOISE, NesInstrumentView.kind(epsm.song, v.state))
        goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(40) { Action.UP })
        assertEquals(listOf(31), ins().noise.values)

        // Page 5: the SSG hardware envelope; Listen moves to an SSG channel.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(NesInstrumentView.ENV_PAGE, NesInstrumentView.epsmPage(epsm.song, v.state))
        assertNull(NesInstrumentView.envelope(epsm.song, v.state))
        assertTrue(NesRules.isEpsmSsg(expansion, v.state.listenChannel))
        goTo(Row.ENV_SHAPE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(S5bShape.SAW_DOWN, ins().epsmEnvelope.shape)
        goTo(Row.ENV_AUTO)
        v.tap(Action.EDIT)
        assertFalse(ins().epsmEnvelope.autoPitch)
        goTo(Row.ENV_PERIOD)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(2 * S5bEnvelope.EPSM_DEFAULT.period, ins().epsmEnvelope.period)
        v.tap(Action.CLEAR)
        assertEquals(S5bEnvelope.EPSM_DEFAULT.period, ins().epsmEnvelope.period)
        // A hardware envelope sets the SSG's level: the volume envelope is not heard there.
        assertEquals("Not heard: the SSG envelope (saw down) sets the level",
            NesInstrumentView.channelNote(NesEnvelopeKind.VOLUME, ChannelKind.PULSE, NesChip.EPSM, shape = ins().epsmEnvelope.shape))

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
        assertEquals("left only", NesEpsmPage.value(epsm.song, ins(), Row.EPSM_PAN))
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
        assertEquals("PMS", NesEpsmPage.label(Row.OPN_FMS))
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
        assertTrue(NesEpsmPage.value(epsm.song, ins(), Row.RHYTHM_LEVEL).contains("drum level ${(level - 1) * 2} of 31"))
        assertEquals("Rhythm drums ignore it", NesInstrumentView.channelNote(NesEnvelopeKind.ARPEGGIO, ChannelKind.SAMPLE, NesChip.EPSM))
        assertEquals("Rhythm drums ignore it", NesInstrumentView.channelNote(NesEnvelopeKind.PITCH, ChannelKind.SAMPLE, NesChip.EPSM))
        assertEquals(listOf("Kick", "Snare", "Cymbal", "Hi-hat", "Tom", "Rim"),
            NesEpsmPage.DRUM_REGISTERS.indices.map { NesRules.channelName(expansion, NesRules.EPSM_RHYTHM1 + it) })
        assertTrue(NesInstrumentView.helpSections(org.thortracker.input.Keymap.DEFAULT, expansion).any { (title, rows) ->
            title == "EPSM rhythm" && rows.any { it.second.contains("fixed drum") }
        })
        // The ROM row says whether the ROM is installed, and Edit there opens the ROM picker.
        if (!Ym2608RhythmRom.installed) {
            assertTrue(NesEpsmPage.value(epsm.song, ins(), Row.RHYTHM_ROM).startsWith("missing"))
            assertTrue(NesEpsmPage.dim(epsm.song, ins(), Row.RHYTHM_ROM))
        }
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
        assertEquals(S5bEnvelope.EPSM_DEFAULT, ins().epsmEnvelope)
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
