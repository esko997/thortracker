package org.thortracker.system.cbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.backend.CbmImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.cbm.CbmInstrumentView.Page
import org.thortracker.system.cbm.CbmInstrumentView.Row

/** The VIC-20 and C16/Plus/4 instrument editor driven through controller actions, its document edits and song settings. */
class CbmEditorTest {
    private var session = EditorSession(TedSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    private fun use(system: CbmSystem) {
        session = EditorSession(system.blank())
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    init {
        use(TedSystem)
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
        state.instField = CbmInstrumentView.rows(session.song, state).indexOf(row)
        assertTrue("row $row is on the page", state.instField >= 0)
    }

    private fun ViewController.showPage(page: Page) {
        state.envelope = CbmInstrumentView.pages(session.song, state).indexOf(page)
        assertTrue("page $page exists", state.envelope >= 0)
    }

    /** The last slot: empty in a new song (the presets fill the first slots). */
    private val slot = CbmSystem.SLOTS

    /** A slot after the presets with empty slots after it (for the starter kit). */
    private val kitSlot = 40

    private fun sound(s: Int = slot) = session.song.cbmInstrument(s)

    private val menus = ArrayList<List<Pair<String, () -> Unit>>>()
    private var picked: Triple<String, String, ByteArray>? = null

    private fun installMenus() {
        session.onMenuRequest = { build ->
            build(object : MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) {
                    val file = this@CbmEditorTest.picked ?: return
                    picked(file.first, file.second, file.third)
                }
                override fun status(message: String) = Unit
                override fun error(message: String) = throw AssertionError(message)
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("no ROM install expected")
            })
        }
    }

    /** A mono 16-bit PCM `.wav` of [samples] at [rate] Hz. */
    private fun wav(samples: ShortArray, rate: Int): ByteArray {
        val data = samples.size * 2
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        out.write("RIFF".toByteArray()); u32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); u32(16); u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16)
        out.write("data".toByteArray()); u32(data)
        for (s in samples) u16(s.toInt())
        return out.toByteArray()
    }

    // ---- sounds and pages ------------------------------------------------------------------------------------------

    @Test
    fun tedSlotsSwitchBetweenToneAndSampleWithTheirPages() {
        assertNull(sound())
        val v = view { instrument = slot }
        v.tap(Action.EDIT) // on Slot
        assertEquals(defaultCbmInstrument(slot), sound())
        assertEquals(listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.WAVE), CbmInstrumentView.pages(session.song, v.state))
        assertTrue(Row.KIND in CbmInstrumentView.rows(session.song, v.state))
        assertTrue(Row.WAVE in CbmInstrumentView.rows(session.song, v.state))
        v.goTo(Row.KIND)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(CbmKind.SAMPLE, sound()!!.kind)
        assertEquals(listOf(Page.SAMPLE, Page.ARPEGGIO, Page.PITCH), CbmInstrumentView.pages(session.song, v.state))
        assertFalse(Row.WAVE in CbmInstrumentView.rows(session.song, v.state))
        assertEquals(Row.KIND, CbmInstrumentView.row(session.song, v.state))
        // Clear on Kind: back to tone; Clear on Slot empties it, undo brings it back.
        v.tap(Action.CLEAR)
        assertEquals(CbmKind.TONE, sound()!!.kind)
        v.goTo(Row.SLOT)
        v.tap(Action.CLEAR)
        assertNull(sound())
        session.undo()
        assertEquals(CbmKind.TONE, sound()!!.kind)
    }

    @Test
    fun theTedWaveRowSwitchesSquareAndNoise() {
        session.setCbmInstrument(slot, defaultCbmInstrument(slot), "test")
        val v = view { instrument = slot }
        v.goTo(Row.WAVE)
        v.tap(Action.EDIT)
        assertEquals(CbmImage.WAVE_NOISE, sound()!!.wave)
        assertTrue(sound()!!.playsNoise)
        v.tap(Action.CLEAR)
        assertEquals(CbmImage.WAVE_SQUARE, sound()!!.wave)
    }

    @Test
    fun theVicHasOneKindAndOneWave() {
        use(VicSystem)
        val v = view { instrument = slot }
        v.tap(Action.EDIT)
        val rows = CbmInstrumentView.rows(session.song, v.state)
        assertFalse(Row.KIND in rows)
        assertFalse(Row.WAVE in rows)
        assertFalse(session.setCbmKind(slot, CbmKind.SAMPLE))
        assertEquals(CbmKind.TONE, sound()!!.kind)
        // Listen plays on whichever lane is chosen; the Noise lane plays it as noise.
        v.state.listenChannel = 3
        v.tap(Action.AUDITION)
        assertEquals(Triple(3, v.defaultNote(), slot), auditions.last())
        assertTrue(session.addCbmSamples(slot, CbmStarterKit.samples(), "kit") { s, n -> CbmStarterKit.instrument(s, n) }.isEmpty())
    }

    @Test
    fun tedListenFollowsWhereTheSoundPlays() {
        val v = view { instrument = slot; listenChannel = 2 }
        // A square sound with Listen on the DAC lane plays on Voice 1.
        session.setCbmInstrument(slot, defaultCbmInstrument(slot), "test")
        v.tap(Action.AUDITION)
        assertEquals(0, auditions.last().first)
        // A sound that can play noise plays on Voice 2.
        session.setCbmInstrument(slot, defaultCbmInstrument(slot).copy(waves = CbmEnvelope(listOf(1, 2))), "test")
        v.state.listenChannel = 0
        v.tap(Action.AUDITION)
        assertEquals(1, auditions.last().first)
        // A sample sound plays on the DAC lane (once the song has a sample).
        session.addCbmSamples(slot, listOf(CbmSample("s", listOf(4, 8, 0))), "test") { s, n -> CbmStarterKit.instrument(s, n) }
        assertEquals(CbmKind.SAMPLE, sound()!!.kind)
        v.tap(Action.AUDITION)
        assertEquals(Triple(CbmImage.TED_DAC_CHANNEL, v.defaultNote(), slot), auditions.last())
        // An empty slot sounds nothing.
        val before = auditions.size
        session.setCbmInstrument(slot, null, "test")
        v.tap(Action.AUDITION)
        assertEquals(before, auditions.size)
    }

    // ---- envelopes -----------------------------------------------------------------------------------------------

    @Test
    fun theMasterVolumeEnvelopeUsesTheChipsRange() {
        for (system in listOf(VicSystem, TedSystem)) {
            use(system)
            val max = system.chip.maxVolume
            session.setCbmInstrument(slot, defaultCbmInstrument(slot), "test")
            val v = view { instrument = slot }
            v.showPage(Page.VOLUME)
            v.goTo(Row.STEPS)
            // An empty master volume envelope starts from the full level.
            v.hold(Action.EDIT, Action.DOWN)
            assertEquals(listOf(max - 1), sound()!!.volume.values)
            v.hold(Action.EDIT, *Array(20) { Action.UP })
            assertEquals(listOf(max), sound()!!.volume.values)
            v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
            assertEquals(listOf(max, max, max), sound()!!.volume.values)
            session.undo()
            assertEquals(listOf(max), sound()!!.volume.values)
            // The wave envelope starts from the sound's wave; the VIC's tops out at 1, the TED's at 2 (noise).
            v.showPage(Page.WAVE)
            v.goTo(Row.STEPS)
            v.state.envStep = 0
            v.hold(Action.EDIT, *Array(5) { Action.UP })
            assertEquals(listOf(system.chip.maxWave), sound()!!.waves.values)
            assertNull(sound()!!.problem(system.chip))
        }
    }

    @Test
    fun loopAndReleaseStayLegal() {
        session.setCbmInstrument(slot, defaultCbmInstrument(slot), "test")
        val v = view { instrument = slot }
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.UP) // +8
        assertEquals(8, sound()!!.arpeggio.values.size)
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, sound()!!.arpeggio.release)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(4, sound()!!.arpeggio.release)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, *Array(9) { Action.RIGHT })
        assertEquals(3, sound()!!.arpeggio.loop)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, *Array(6) { Action.LEFT })
        assertEquals(2, sound()!!.arpeggio.values.size)
        assertEquals(-1, sound()!!.arpeggio.release)
        assertEquals(1, sound()!!.arpeggio.loop)
        assertNull(sound()!!.problem(CbmChip.TED))
        val e = CbmEnvelope(listOf(1, 2, 3), loop = 2, release = 2)
        assertEquals(CbmEnvelope(listOf(1, 2, 3), 1, 2), CbmEnvelopeKind.ARPEGGIO.fixed(e, CbmEnvelope.ARPEGGIO))
        assertEquals(CbmEnvelope(listOf(1, 1, 1), -1, -1), CbmEnvelopeKind.WAVE.fixed(CbmEnvelope(listOf(1, 5, 9), release = 3), 0..1))
    }

    @Test
    fun copyPasteMovesEnvelopesAndSounds() {
        session.setCbmInstrument(slot - 1, defaultCbmInstrument(slot - 1), "test")
        val v = view { instrument = slot - 1 }
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(80) { Action.UP }, Action.RIGHT)
        assertEquals(listOf(63, 63), sound(slot - 1)!!.arpeggio.values)
        v.tap(Action.COPY)
        v.tap(Action.PREV_CHANNEL) // master volume
        v.tap(Action.PASTE)
        assertEquals("clamped into the TED's master volume", listOf(8, 8), sound(slot - 1)!!.volume.values)
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        v.tap(Action.RIGHT)
        assertEquals(slot, v.state.instrument)
        v.tap(Action.PASTE)
        assertEquals(sound(slot - 1), sound())
        // A TED noise sound pasted into a VIC song becomes a VIC square with legal envelopes.
        val ted = CbmInstrument("n", wave = 2, volume = CbmEnvelope(listOf(8)), waves = CbmEnvelope(listOf(2, 0)))
        val vic = cbmInstrumentFor(VicSystem.blank().cbm, ted)
        assertEquals(CbmImage.WAVE_SQUARE, vic.wave)
        assertEquals(listOf(1, 0), vic.waves.values)
        assertNull(vic.problem(CbmChip.VIC))
        assertEquals(CbmKind.TONE, cbmInstrumentFor(VicSystem.blank().cbm, CbmStarterKit.instrument(0, "k")).kind)
    }

    // ---- presets -------------------------------------------------------------------------------------------------

    @Test
    fun presetsAreTheSongsChips() {
        for (system in listOf(VicSystem, TedSystem)) {
            use(system)
            val presets = CbmPresets.forChip(system.chip)
            val v = view { instrument = slot }
            v.goTo(Row.PRESET)
            v.hold(Action.EDIT, Action.RIGHT)
            assertEquals(presets.first().instrument, sound())
            assertEquals(0, CbmInstrumentView.presetIndex(session.song, sound()))
            v.hold(Action.EDIT, *Array(presets.size) { Action.RIGHT })
            assertEquals(0, CbmInstrumentView.presetIndex(session.song, sound()))
            menus.clear()
            installMenus()
            v.goTo(Row.PRESET)
            v.tap(Action.EDIT)
            assertEquals(presets.size, menus.single().size)
            menus.single().last().second()
            assertEquals(presets.size - 1, CbmInstrumentView.presetIndex(session.song, sound()))
            v.tap(Action.CLEAR)
            assertEquals(defaultCbmInstrument(slot), sound())
        }
    }

    // ---- samples -------------------------------------------------------------------------------------------------

    @Test
    fun wavImportsAreQuantisedToTheNineDacLevels() {
        session.setCbmKind(slot, CbmKind.SAMPLE)
        val v = view { instrument = slot }
        v.showPage(Page.SAMPLE)
        v.goTo(Row.ADD)
        installMenus()
        val rate = CbmSamples.rateHz(CbmVideo.PAL, CbmSound.DEFAULT_TIMER)
        picked = Triple("ramp.wav", "wav", wav(shortArrayOf(-32768, 0, 32767, -16384, 16384), rate))
        v.tap(Action.EDIT)
        menus.single()[1].second()
        val samples = session.song.cbm.samples
        assertEquals(listOf(0, 4, 8, 2, 6), samples.single().data)
        assertEquals("ramp.wav", samples.single().name)
        assertEquals(0, sound()!!.sample)
        // Down-sampling averages: two source samples per frame.
        assertEquals(listOf(6, 0), CbmSamples.fromWav(wav(shortArrayOf(0, 32767, -32768, -32768), rate * 2), rate))
        // Levels: the nearest of nine, centre 4.
        assertEquals(listOf(0, 4, 8), listOf(-32768, 0, 32767).map { CbmSamples.level(it) })
        // The import follows the song's DAC rate.
        session.apply("rate") { s -> s.copy(sound = s.cbm.copy(dacTimer = 0)) }
        assertEquals(13855, CbmSampleMenu.rateHz(session.song))
    }

    @Test
    fun theStarterKitFillsTheNextEmptySlots() {
        installMenus()
        val v = view { instrument = kitSlot }
        session.setCbmKind(kitSlot, CbmKind.SAMPLE)
        v.showPage(Page.SAMPLE)
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        menus.single()[0].second()
        val sound = session.song.cbm
        assertEquals(CbmStarterKit.samples(), sound.samples)
        assertEquals(listOf(0, 1, 2, 3), (kitSlot..kitSlot + 3).map { sound.instrument(it)!!.sample })
        assertTrue((kitSlot..kitSlot + 3).all { sound.instrument(it)!!.kind == CbmKind.SAMPLE })
        assertTrue(TedSystem.errors(session.song).isEmpty())
        // The kit again reuses the stored frames.
        assertEquals(listOf(slot), session.addCbmSamples(slot, CbmStarterKit.samples().take(1), "again") { s, n -> CbmStarterKit.instrument(s, n) })
        assertEquals(4, session.song.cbm.samples.size)
        // Deleting a sample moves later references down; its users take the one before.
        assertEquals(2, session.deleteCbmSample(0))
        assertEquals(listOf(0, 0, 1, 2), (kitSlot..kitSlot + 3).map { session.song.cbm.instrument(it)!!.sample })
        assertEquals(3, session.song.cbm.samples.size)
        session.undo()
        assertEquals(4, session.song.cbm.samples.size)
    }

    // ---- song settings ---------------------------------------------------------------------------------------------

    @Test
    fun songSettingsCoverMachineMultispeedVolumeAndTheDacRate() {
        assertEquals(3, VicSystem.songSettings.size)
        assertEquals(4, TedSystem.songSettings.size)
        for (system in listOf(VicSystem, TedSystem)) {
            val (machine, multispeed, volume) = system.songSettings
            val song = system.blank()
            val ntsc = machine.adjust(song, 1, 0)
            assertEquals(CbmVideo.NTSC, ntsc.cbm.video)
            assertEquals(CbmVideo.PAL, machine.adjust(ntsc, 1, 0).cbm.video)
            assertEquals(CbmVideo.PAL, machine.reset(ntsc).cbm.video)
            assertTrue(machine.adjust(song, 1, 1) === song)
            assertEquals(4, multispeed.adjust(song, 10, 0).cbm.multispeed)
            assertEquals(1, multispeed.adjust(song, -10, 0).cbm.multispeed)
            assertEquals(system.ticksPerSecond(song) * 2, system.ticksPerSecond(multispeed.adjust(song, 1, 0)), 1e-9)
            assertEquals(system.chip.maxVolume, volume.adjust(song, 5, 0).cbm.volume)
            assertEquals(system.chip.maxVolume - 4, volume.adjust(song, 0, -1).cbm.volume)
            assertEquals(0, volume.adjust(song, -20, 0).cbm.volume)
            assertEquals(system.chip.maxVolume, volume.reset(volume.adjust(song, -3, 0)).cbm.volume)
        }
        val dac = TedSystem.songSettings[3]
        val song = TedSystem.blank()
        assertEquals(CbmSound.DEFAULT_TIMER + 1, dac.adjust(song, 1, 0).cbm.dacTimer)
        assertEquals(CbmSound.DEFAULT_TIMER + 16, dac.adjust(song, 0, 1).cbm.dacTimer)
        assertEquals(255, dac.adjust(song, 0, 100).cbm.dacTimer)
        assertEquals(0, dac.adjust(song, -100, 0).cbm.dacTimer)
        assertEquals(CbmSound.DEFAULT_TIMER, dac.reset(dac.adjust(song, 5, 0)).cbm.dacTimer)
        assertTrue(TedSystem.structuralErrors(dac.adjust(song, 0, 100)).isEmpty())
    }
}
