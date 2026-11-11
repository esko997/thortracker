package org.thortracker.system.opn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.OpnPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.input.Keymap
import org.thortracker.model.Cell
import org.thortracker.model.Song
import org.thortracker.system.fm.RhythmRomMenu
import org.thortracker.system.fm.Ym2608RhythmRom
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.nes.S5bShape
import org.thortracker.system.opn.OpnInstrumentView.Page
import org.thortracker.system.opn.OpnInstrumentView.Row

/** The OPN-family instrument editor driven through controller actions on both chips, its sample tools and song settings. */
class OpnEditorTest {
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    /** Menus opened (title, entries), files asked for (extensions, size limit), errors and statuses reported. */
    private val menus = ArrayList<Pair<String, List<Pair<String, () -> Unit>>>>()
    private val picks = ArrayList<Pair<List<String>, Int>>()
    private val errors = ArrayList<String>()

    private fun session(song: Song): EditorSession = EditorSession(song).also { s ->
        s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        s.onMenuRequest = { build ->
            build(object : MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += title to entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) {
                    picks += extensions to maxBytes
                }
                override fun status(message: String) = Unit
                override fun error(message: String) { errors += message }
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("nothing was picked")
            })
        }
    }

    private fun view(session: EditorSession, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply { soundPanel("opn").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        val i = OpnInstrumentView.rows(session.song, state).indexOf(row)
        assertTrue("$row is not on page ${OpnInstrumentView.page(session.song, state)}", i >= 0)
        state.instField = i
    }

    private fun ViewController.showPage(page: Page) {
        val i = OpnInstrumentView.pages(session.song, state).indexOf(page)
        assertTrue("$page is not a page of this sound", i >= 0)
        state.envelope = i
    }

    /** Puts the starting sound of [part] into [slot]. */
    private fun EditorSession.put(slot: Int, part: OpnPart) {
        OpnEdits.set(this, slot, OpnEdits.newSound(song.opn.chip, part, slot), "test")
    }

    private fun EditorSession.ins(slot: Int): OpnInstrument? = song.opn.instrument(slot)

    /** The YM2608 song with [count] generated samples and no ADPCM sounds but the ones a test adds. */
    private fun withSamples(s: EditorSession, count: Int): List<OpnaSample> {
        val kit = OpnSampleKit.KINDS.take(count).map { OpnSampleKit.generate(it) }
        s.apply("test") { song -> song.copy(sound = song.opn.copy(samples = kit, instruments = song.opn.instruments.map { if (it is OpnAdpcmSound) null else it })) }
        return kit
    }

    // ---- pages and rows ---------------------------------------------------------------------------------------

    @Test
    fun pagesFollowThePartAndTheYm2203HidesOpnaOnlyRows() {
        val opna = session(OpnaSystem.blank())
        opna.put(40, OpnPart.FM); opna.put(41, OpnPart.SSG); opna.put(42, OpnPart.RHYTHM); opna.put(43, OpnPart.ADPCM)
        val st = ViewState(kind = ViewKind.INSTRUMENT)
        val expected = mapOf(40 to OpnInstrumentView.FM_PAGES, 41 to OpnInstrumentView.SSG_PAGES, 42 to OpnInstrumentView.RHYTHM_PAGES,
            43 to OpnInstrumentView.ADPCM_PAGES)
        for ((slot, pages) in expected) {
            st.instrument = slot
            assertEquals(pages, OpnInstrumentView.pages(opna.song, st))
        }
        assertEquals(listOf(Page.SOUND, Page.EXPERT, Page.CH3, Page.FM_DRUM), OpnInstrumentView.FM_PAGES)
        assertEquals(listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.MIXER, Page.NOISE, Page.HARDWARE, Page.DRUM), OpnInstrumentView.SSG_PAGES)

        st.instrument = 40
        st.envelope = OpnInstrumentView.FM_PAGES.indexOf(Page.SOUND)
        assertTrue(OpnInstrumentView.rows(opna.song, st).containsAll(listOf(Row.VIBRATO, Row.TREMOLO)))
        st.envelope = OpnInstrumentView.FM_PAGES.indexOf(Page.EXPERT)
        assertTrue(OpnInstrumentView.rows(opna.song, st).containsAll(listOf(Row.AMS, Row.FMS, Row.OP_AM)))

        val opn = session(OpnSystem.blank())
        opn.put(40, OpnPart.FM)
        st.envelope = OpnInstrumentView.FM_PAGES.indexOf(Page.SOUND)
        assertFalse(OpnInstrumentView.rows(opn.song, st).any { it == Row.VIBRATO || it == Row.TREMOLO })
        st.envelope = OpnInstrumentView.FM_PAGES.indexOf(Page.EXPERT)
        val expert = OpnInstrumentView.rows(opn.song, st)
        assertFalse(expert.any { it == Row.AMS || it == Row.FMS || it == Row.OP_AM })
        assertTrue(expert.containsAll(OpnInstrumentView.GRID_OPN))

        // An empty slot shows the pages of the Listen channel's part.
        st.instrument = 60
        opn.put(60, OpnPart.FM)
        OpnEdits.set(opn, 60, null, "test")
        st.listenChannel = OpnChip.YM2203.ssgFirst
        assertEquals(OpnInstrumentView.SSG_PAGES, OpnInstrumentView.pages(opn.song, st))
    }

    @Test
    fun typeStepsThroughTheChipsPartsKeepingTheName() {
        val opn = session(OpnSystem.blank())
        OpnEdits.set(opn, 60, null, "test")
        val v = view(opn) { instrument = 60; listenChannel = 0 }
        v.tap(Action.EDIT) // on Slot: a new FM voice
        assertTrue(opn.ins(60) is OpnFmSound)
        assertEquals("Sound 3C", opn.ins(60)!!.name)
        v.goTo(Row.TYPE)
        v.tap(Action.EDIT)
        assertTrue(opn.ins(60) is OpnSsgSound)
        assertEquals("Sound 3C", opn.ins(60)!!.name)
        assertEquals(OpnChip.YM2203.ssgFirst, v.state.listenChannel)
        v.goTo(Row.TYPE)
        v.tap(Action.EDIT) // the YM2203 has two parts: back to FM
        assertTrue(opn.ins(60) is OpnFmSound)
        assertEquals(0, v.state.listenChannel)

        val opna = session(OpnaSystem.blank())
        OpnEdits.set(opna, 60, null, "test")
        val w = view(opna) { instrument = 60; listenChannel = 0 }
        w.tap(Action.EDIT)
        val kinds = ArrayList<OpnPart>()
        repeat(4) {
            w.goTo(Row.TYPE)
            w.tap(Action.EDIT)
            kinds += opna.ins(60)!!.part
        }
        assertEquals(listOf(OpnPart.SSG, OpnPart.RHYTHM, OpnPart.ADPCM, OpnPart.FM), kinds)
        w.state.instField = 0
        w.tap(Action.CLEAR)
        assertNull(opna.ins(60))
    }

    // ---- FM -------------------------------------------------------------------------------------------------

    @Test
    fun fmPagesEditCuratedExpertCh3AndDrum() {
        val s = session(OpnaSystem.blank())
        s.put(40, OpnPart.FM)
        val v = view(s) { instrument = 40; listenChannel = 0 }
        fun fm() = s.ins(40) as OpnFmSound
        v.goTo(Row.PRESET)
        val before = fm()
        v.hold(Action.EDIT, Action.RIGHT)
        assertNotEquals(before, s.ins(40))
        assertTrue(auditions.last().first in 0 until OpnChip.YM2608.fmChannels)
        v.goTo(Row.ALGORITHM)
        val alg = fm().algorithm
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((alg + 1) % 8, fm().algorithm)
        v.goTo(Row.TREMOLO)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, fm().ams)

        // Expert: operator 2's detune via R1 then Edit+→; AM switches on a tap.
        v.showPage(Page.EXPERT)
        v.goTo(Row.OP_DT)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(1, v.state.envStep)
        val dt = fm().operators[1].dt
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((dt + 1) % 8, fm().operators[1].dt)
        v.goTo(Row.OP_AM)
        val am = fm().operators[1].am
        v.tap(Action.EDIT)
        assertEquals(!am, fm().operators[1].am)

        // Ch3: operator 2 a semitone and 8/64 up, the song's FM 3 mode, audition on FM 3.
        OpnEdits.editFm(s, 40, "test") { it.copy(ch3 = listOf(0, 0, 0, 0)) }
        v.showPage(Page.CH3)
        v.goTo(Row.CH3_SEMI)
        v.state.envStep = 1
        v.hold(Action.EDIT, Action.RIGHT)
        v.goTo(Row.CH3_FINE)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(listOf(0, 72, 0, 0), fm().ch3)
        v.goTo(Row.CH3_SEMI)
        v.hold(Action.EDIT, *Array(60) { Action.UP }) // clamped at +4 octaves
        assertEquals(OpnPlayerImage.CH3_LIMIT, fm().ch3[1])
        v.tap(Action.CLEAR)
        assertEquals(0, fm().ch3[1])
        v.goTo(Row.FM3_MODE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(OpnFm3Mode.SPECIAL, s.song.opn.fm3Mode)
        v.tap(Action.AUDITION)
        assertEquals(OpnPlayerImage.CH_FM3, auditions.last().first)

        // Drum: a sweep start gives the sweep a speed.
        v.showPage(Page.FM_DRUM)
        OpnEdits.editFm(s, 40, "test") { it.copy(sweep = org.thortracker.system.ym2612.YmSweep.NONE) }
        v.goTo(Row.SWEEP_START)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(org.thortracker.system.ym2612.YmSweep(12, 0, 16), fm().sweep)
        v.goTo(Row.DRUM_PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(OpnInstrumentView.fmPresets(drums = true).first().name, fm().name)
        assertEquals(OpnInstrumentView.fmPresets(drums = true).first().instrument.let { (it as org.thortracker.system.ym2612.YmFmInstrument).sweep }, fm().sweep)
    }

    @Test
    fun ym2203PresetsDropTheLfoDepths() {
        val s = session(OpnSystem.blank())
        s.put(40, OpnPart.FM)
        val v = view(s) { instrument = 40 }
        v.goTo(Row.PRESET)
        repeat(OpnInstrumentView.fmPresets(drums = false).size) {
            v.hold(Action.EDIT, Action.RIGHT)
            val fm = s.ins(40) as OpnFmSound
            assertTrue(fm.ams == 0 && fm.fms == 0 && fm.operators.none { it.am })
        }
    }

    // ---- SSG ------------------------------------------------------------------------------------------------

    @Test
    fun ssgPagesEditEnvelopesMixerNoiseAndTheHardwareEnvelope() {
        val s = session(OpnSystem.blank())
        s.put(41, OpnPart.SSG)
        val v = view(s) { instrument = 41; listenChannel = 0 }
        fun ssg() = s.ins(41) as OpnSsgSound
        v.tap(Action.AUDITION)
        assertEquals(OpnChip.YM2203.ssgFirst, auditions.last().first)
        assertTrue(auditions.last().second >= OpnRules.lowest(OpnChip.YM2203, OpnChip.YM2203.ssgFirst))

        OpnEdits.editSsg(s, 41, "test") { it.copy(volume = NesEnvelope(listOf(15, 12))) }
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(listOf(14, 12), ssg().volume.values)
        v.state.envStep = 2
        v.tap(Action.EDIT)
        assertEquals(listOf(14, 12, 12), ssg().volume.values)
        v.state.envStep = 2
        v.tap(Action.CLEAR)
        assertEquals(2, ssg().volume.values.size)

        // Mixer: an empty envelope is tone (2); a step above is "neither" (3). Noise: periods from 0.
        OpnEdits.editSsg(s, 41, "test") { it.copy(mixer = NesEnvelope(), noise = NesEnvelope()) }
        v.showPage(Page.MIXER)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP)
        assertEquals(listOf(3), ssg().mixer.values)
        v.hold(Action.EDIT, Action.UP) // clamped to the mixer's range
        assertEquals(listOf(3), ssg().mixer.values)
        v.showPage(Page.NOISE)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.RIGHT)
        assertEquals(listOf(2, 2), ssg().noise.values)

        // Hardware envelope: shape, manual period, doubled period.
        v.showPage(Page.HARDWARE)
        v.goTo(Row.ENV_SHAPE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(S5bShape.SAW_DOWN, ssg().envelope.shape)
        v.goTo(Row.ENV_AUTO)
        v.tap(Action.EDIT)
        assertFalse(ssg().envelope.autoPitch)
        val period = ssg().envelope.period
        v.goTo(Row.ENV_PERIOD)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(period * 2, ssg().envelope.period)
        v.goTo(Row.ENV_SHAPE)
        v.tap(Action.CLEAR)
        assertEquals(S5bShape.OFF, ssg().envelope.shape)

        // Copy an envelope from Volume, paste it into Arpeggio (values clamped to the arpeggio's range).
        v.showPage(Page.VOLUME)
        v.goTo(Row.LENGTH)
        v.tap(Action.COPY)
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.LENGTH)
        v.tap(Action.PASTE)
        assertEquals(ssg().volume.values, ssg().arpeggio.values)
    }

    // ---- rhythm ---------------------------------------------------------------------------------------------

    @Test
    fun rhythmPageEditsLevelAndPanAndTheRomRowOpensThePicker() {
        val s = session(OpnaSystem.blank())
        s.put(42, OpnPart.RHYTHM)
        val v = view(s) { instrument = 42; listenChannel = 0 }
        fun rhythm() = s.ins(42) as OpnRhythmSound
        assertEquals(OpnInstrumentView.RHYTHM_PAGES, OpnInstrumentView.pages(s.song, v.state))
        v.goTo(Row.RHYTHM_LEVEL)
        val level = rhythm().level
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(level - 1, rhythm().level)
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(0, rhythm().level)
        v.tap(Action.CLEAR)
        assertEquals(31, rhythm().level)
        v.goTo(Row.PAN)
        v.tap(Action.EDIT)
        assertEquals(true to false, rhythm().left to rhythm().right)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(false to false, rhythm().left to rhythm().right)
        v.tap(Action.CLEAR)
        assertEquals(true to true, rhythm().left to rhythm().right)

        // Presets name their drum: Listen moves to that rhythm channel.
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        val drum = OpnChip.RHYTHM_NAMES.indexOf(rhythm().name)
        assertTrue(drum >= 0)
        assertEquals(OpnChip.YM2608.rhythmFirst + drum, v.state.listenChannel)

        // Listen sounds the drum on a rhythm channel, or (without the ROM) refuses with a message.
        val heard = auditions.size
        v.tap(Action.AUDITION)
        if (Ym2608RhythmRom.installed) assertEquals(OpnChip.YM2608.rhythmFirst + drum, auditions.last().first)
        else { assertEquals(heard, auditions.size); assertEquals(Ym2608RhythmRom.MISSING, s.message) }

        v.goTo(Row.RHYTHM_ROM)
        v.tap(Action.EDIT)
        assertEquals(listOf(Ym2608RhythmRom.EXTENSIONS to RhythmRomMenu.MAX_FILE), picks)
    }

    // ---- ADPCM ----------------------------------------------------------------------------------------------

    @Test
    fun samplePageChoosesTheSampleRootLevelPanAndLoop() {
        val s = session(OpnaSystem.blank())
        withSamples(s, 3)
        s.put(43, OpnPart.ADPCM)
        val v = view(s) { instrument = 43; listenChannel = 0 }
        fun adpcm() = s.ins(43) as OpnAdpcmSound
        assertEquals(0, adpcm().sample)
        v.goTo(Row.SAMPLE)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(2, adpcm().sample) // clamped to the last sample
        v.goTo(Row.ROOT)
        v.hold(Action.EDIT, Action.UP, Action.LEFT)
        assertEquals(48 + 11, adpcm().root)
        v.tap(Action.AUDITION)
        assertEquals(Triple(OpnChip.YM2608.adpcmChannel, 48 + 11, 43), auditions.last()) // octave 4 plays the root
        v.tap(Action.OCTAVE_DOWN)
        v.tap(Action.AUDITION)
        assertEquals(48 - 1, auditions.last().second)
        v.goTo(Row.ADPCM_LEVEL)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(255 - 16, adpcm().level)
        v.goTo(Row.PAN)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(false to false, adpcm().left to adpcm().right)
        v.goTo(Row.LOOP_SAMPLE)
        v.tap(Action.EDIT)
        assertTrue(adpcm().loop)
        v.tap(Action.CLEAR)
        assertFalse(adpcm().loop)
        // The Drum page edits the ADPCM sound's sweep.
        v.showPage(Page.DRUM)
        v.goTo(Row.SWEEP_SPEED)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, adpcm().sweep.speed)
    }

    @Test
    fun trimmingRenamingAndToolsChangeTheSoundsSample() {
        val s = session(OpnaSystem.blank())
        val kit = withSamples(s, 2)
        s.put(43, OpnPart.ADPCM)
        val v = view(s) { instrument = 43 }
        fun sample() = s.song.opn.samples[0]
        val original = sample()

        // Trimming the end drops whole 32-byte units exactly; the start re-encodes one unit shorter.
        v.goTo(Row.TRIM_END)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(original.data.size - 32, sample().data.size)
        assertArrayEquals(original.data.toByteArray().copyOf(original.data.size - 32), sample().data.toByteArray())
        v.hold(Action.EDIT, Action.UP)
        assertEquals(original.data.size - 32 - 512, sample().data.size)
        v.goTo(Row.TRIM_START)
        val size = sample().data.size
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(size - 32, sample().data.size)
        v.hold(Action.EDIT, Action.LEFT) // trimming never adds
        assertEquals(size - 32, sample().data.size)
        s.undo()
        assertEquals(size, sample().data.size)

        // The sample's name, letter by letter.
        v.goTo(Row.SAMPLE_NAME)
        v.hold(Action.EDIT, Action.RIGHT)
        assertNotEquals(kit[0].name, sample().name)
        v.tap(Action.CLEAR)
        assertEquals(kit[0].name.length - 1, sample().name.length)

        // Tools: the menu lists every operation; gain down then normalize brings the peak back up.
        v.goTo(Row.SAMPLE_TOOLS)
        v.tap(Action.EDIT)
        val tools = menus.last().second
        val labels = tools.map { it.first }
        for (word in listOf("Normalize", "Gain", "Reverse", "Fade in", "Fade out", "Resample", "Replace", "Delete")) {
            assertTrue("no $word in $labels", labels.any { it.startsWith(word) })
        }
        tools.first { it.first == "Gain −12 dB" }.second()
        val quiet = OpnEdits.peak(sample())
        assertTrue(quiet < OpnEdits.peak(original))
        tools.first { it.first.startsWith("Normalize") }.second()
        assertTrue(OpnEdits.peak(sample()) > quiet * 3)
        tools.first { it.first == "Reverse" }.second()
        tools.first { it.first == "Fade out…" }.second()
        menus.last().second.first().second() // 10 ms
        tools.first { it.first.startsWith("Resample") }.second()
        val rates = menus.last().second
        rates.first { it.first.startsWith("8000 Hz") }.second()
        assertEquals(8000, sample().rate)
        assertTrue(sample().data.size < original.data.size)
        assertTrue(errors.isEmpty())

        // Pure edits: reversing twice changes nothing but the encoding; fades keep the length.
        val song = s.song
        assertEquals(sample().data.size, OpnEdits.fadeIn(song, 0, 100).opn.samples[0].data.size)
        assertEquals(song, OpnEdits.gain(song, 0, 0.0))
    }

    @Test
    fun deletingSamplesKeepsEverySoundsIndexAndAsksOrRefuses() {
        val s = session(OpnaSystem.blank())
        withSamples(s, 3)
        OpnEdits.set(s, 43, OpnPresets.adpcm(0, "A"), "test")
        OpnEdits.set(s, 44, OpnPresets.adpcm(2, "C"), "test")
        OpnEdits.set(s, 45, OpnPresets.adpcm(1, "B"), "test")
        val names = s.song.opn.samples.map { it.name }
        val v = view(s) { instrument = 45 }

        // Sound 45's sample (1) is not played in the arrangement: Clear on Sample asks, then deletes and empties 45.
        v.goTo(Row.SAMPLE)
        v.tap(Action.CLEAR)
        val ask = menus.last().second
        assertEquals("Keep it", ask[0].first)
        ask[0].second()
        assertEquals(3, s.song.opn.samples.size)
        ask[1].second()
        assertEquals(listOf(names[0], names[2]), s.song.opn.samples.map { it.name })
        assertNull(s.ins(45))
        assertEquals(0, (s.ins(43) as OpnAdpcmSound).sample)
        assertEquals(1, (s.ins(44) as OpnAdpcmSound).sample) // followed its sample down
        assertEquals(names[2], s.song.opn.samples[(s.ins(44) as OpnAdpcmSound).sample].name)

        // A sample whose sound the arrangement plays is refused, naming the sound.
        val ch = OpnChip.YM2608.adpcmChannel
        s.setCell(s.song.orders[0], ch, 0, Cell(note = 48, instrument = 43))
        val asked = menus.size
        val w = view(s) { instrument = 43 }
        w.goTo(Row.SAMPLE)
        w.tap(Action.CLEAR)
        assertEquals(asked, menus.size)
        assertTrue(errors.last(), errors.last().contains("2B"))
        assertEquals(2, s.song.opn.samples.size)

        // Pure deletion of an unused sample: indices above it move down, the rest stay.
        val song = OpnEdits.deleteSample(OpnEdits.setSound(s.song, 43, null), 0)
        assertEquals(0, (song.opn.instrument(44) as OpnAdpcmSound).sample)
        assertEquals(listOf(names[2]), song.opn.samples.map { it.name })
    }

    @Test
    fun theAddRowGeneratesAStarterSampleForTheSound() {
        val s = session(OpnaSystem.blank())
        withSamples(s, 1)
        s.put(43, OpnPart.ADPCM)
        val v = view(s) { instrument = 43 }
        v.goTo(Row.SAMPLE_ADD)
        v.tap(Action.EDIT)
        val add = menus.last().second
        assertTrue(add[0].first.startsWith("Import .wav"))
        add[0].second()
        assertEquals(listOf(".wav") to OpnSampleMenu.MAX_FILE, picks.last())
        val kind = OpnSampleKit.KINDS.last()
        add.first { it.first == "Generate: $kind" }.second()
        assertEquals(2, s.song.opn.samples.size)
        assertEquals(1, (s.ins(43) as OpnAdpcmSound).sample)
        assertEquals(OpnSampleKit.generate(kind), s.song.opn.samples[1])

        // An empty slot gets an ADPCM sound playing the new sample.
        OpnEdits.set(s, 50, null, "test")
        val added = OpnEdits.addAndUse(s.song, 50, s.song.opn.samples[0])
        assertEquals(2, (added.opn.instrument(50) as OpnAdpcmSound).sample)
    }

    // ---- song settings and help ----------------------------------------------------------------------------------

    @Test
    fun songSettingsStayInTheirRanges() {
        assertEquals(listOf("Tick rate", "FM 3 mode", "CSM rate"), OpnSongSettings.forChip(OpnChip.YM2203).map { it.label })
        assertEquals(listOf("Tick rate", "FM 3 mode", "CSM rate", "LFO", "Rhythm level"), OpnSongSettings.forChip(OpnChip.YM2608).map { it.label })
        assertEquals(OpnSongSettings.forChip(OpnChip.YM2608), OpnaSystem.songSettings)
        fun setting(label: String) = OpnSongSettings.forChip(OpnChip.YM2608).single { it.label == label }
        var song = OpnaSystem.blank()

        val tick = setting("Tick rate")
        assertTrue(tick.value(song).startsWith("59.77 Hz"))
        song = tick.adjust(song, 0, 100)
        assertEquals(OpnPlayerImage.TIMER_B_MAX, song.opn.timerB)
        song = tick.adjust(song, -1, -100)
        assertEquals(OpnPlayerImage.TIMER_B_MIN, song.opn.timerB)
        assertTrue(tick.adjust(song, -1, 0) === song)
        song = tick.reset(song)
        assertEquals(OpnPlayerImage.TIMER_B_DEFAULT, song.opn.timerB)

        val mode = setting("FM 3 mode")
        song = mode.adjust(song, 1, 0)
        assertEquals(OpnFm3Mode.SPECIAL, song.opn.fm3Mode)
        song = mode.adjust(song, 1, 0)
        assertEquals(OpnFm3Mode.CSM, song.opn.fm3Mode)
        song = mode.adjust(song, 1, 0)
        assertEquals(OpnFm3Mode.NORMAL, song.opn.fm3Mode)

        val csm = setting("CSM rate")
        song = csm.adjust(song, 0, 1000)
        assertEquals(OpnPlayerImage.TIMER_A_MAX, song.opn.timerA)
        assertTrue(csm.value(song).contains("Hz"))
        song = csm.adjust(song, 0, -1000)
        assertEquals(0, song.opn.timerA)

        val lfo = setting("LFO")
        song = lfo.adjust(song, 1, 0)
        assertEquals(0x08, song.opn.lfo)
        song = lfo.adjust(song, 0, 10)
        assertEquals(0x0F, song.opn.lfo)
        assertTrue(lfo.value(song).contains("72.2 Hz"))
        song = lfo.reset(song)
        assertEquals(0, song.opn.lfo)

        val rhythm = setting("Rhythm level")
        song = rhythm.adjust(song, 1, 0)
        assertEquals(63, song.opn.rhythmLevel)
        song = rhythm.adjust(song, 0, -100)
        assertEquals(0, song.opn.rhythmLevel)
        song = rhythm.reset(song)
        assertEquals(63, song.opn.rhythmLevel)
    }

    @Test
    fun helpNamesEveryPartOfTheChip() {
        val opna = OpnInstrumentView.helpSections(Keymap.DEFAULT, OpnChip.YM2608)
        assertEquals(listOf("Instruments", "FM voices", "SSG sounds", "Rhythm and ADPCM"), opna.map { it.first })
        val opn = OpnInstrumentView.helpSections(Keymap.DEFAULT, OpnChip.YM2203)
        assertEquals(listOf("Instruments", "FM voices", "SSG sounds"), opn.map { it.first })
        assertTrue(opn.flatMap { it.second }.any { it.first == "YM2203" })
        assertTrue(opna.flatMap { it.second }.any { it.first == "Ch3" && it.second.contains("special") })
        assertEquals(OpnInstrumentView, OpnaSystem.view(ViewKind.INSTRUMENT))
        assertEquals(OpnInstrumentView, OpnSystem.view(ViewKind.INSTRUMENT))
    }
}
