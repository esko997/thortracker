package org.thortracker.system.pce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.model.Phrase
import org.thortracker.system.pce.PceInstrumentView.Page
import org.thortracker.system.pce.PceInstrumentView.Row

/** The PC Engine instrument and wave editors driven through controller actions, and their document edits. */
class PceEditorTest {
    private val session = EditorSession(PceSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(kind: ViewKind = ViewKind.INSTRUMENT, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = kind).apply { soundPanel("pce").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        state.instField = PceInstrumentView.rows(session.song, state).indexOf(row)
        assertTrue("row $row is on the page", state.instField >= 0)
    }

    private fun ViewController.showPage(page: Page) {
        state.envelope = PceInstrumentView.pages(session.song, state).indexOf(page)
        assertTrue("page $page exists", state.envelope >= 0)
    }

    /** The last slot: empty in a new song (the presets fill the first slots). */
    private val slot = PceSystem.SLOTS

    private fun sound(s: Int = slot) = session.song.pceInstrument(s)

    /** Gives the song at least [n] waves (new ones distinct ramps), whatever the built-in set holds. */
    private fun ensureWaves(n: Int) {
        while (session.song.pce.waves.size < n) {
            val k = session.song.pce.waves.size
            assertTrue(session.addPceWave(List(PceSound.WAVE_SIZE) { (it + k) % (PceSound.WAVE_MAX + 1) }) >= 0)
        }
    }

    /** A test sample of [frames] distinct-enough 5-bit frames. */
    private fun sample(name: String, frames: Int, seed: Int) = PceSample(name, List(frames) { (it * seed + seed) % 32 })

    /** Menus run inside the test: [menus] collects every popup's entries, [picked] answers file pickers. */
    private val menus = ArrayList<List<Pair<String, () -> Unit>>>()
    private var picked: Triple<String, String, ByteArray>? = null

    private fun installMenus() {
        session.onMenuRequest = { build ->
            build(object : MenuContext {
                override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) = error("Unexpected text prompt")
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) {
                    val file = this@PceEditorTest.picked ?: return
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

    @Test
    fun emptySlotsAreMadeAndKindsSwitchWithTheirPages() {
        assertNull(sound())
        val v = view { instrument = slot }
        v.tap(Action.EDIT) // on Slot
        assertEquals(defaultPceInstrument(slot), sound())
        assertEquals(PceKind.TONE, sound()!!.kind)
        assertEquals(listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.WAVES), PceInstrumentView.pages(session.song, v.state))
        assertTrue(Row.WAVE in PceInstrumentView.rows(session.song, v.state))
        // Tone → noise: Listen moves to PSG 5, the pages are volume and arpeggio, the Wave row goes.
        v.goTo(Row.KIND)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(PceKind.NOISE, sound()!!.kind)
        assertEquals(PceRules.NOISE_CHANNELS.first, v.state.listenChannel)
        assertEquals(listOf(Page.VOLUME, Page.ARPEGGIO), PceInstrumentView.pages(session.song, v.state))
        assertFalse(Row.WAVE in PceInstrumentView.rows(session.song, v.state))
        assertEquals(Row.KIND, PceInstrumentView.row(session.song, v.state))
        // Noise → sample: the Sample page comes first; the tone settings are kept for switching back.
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(PceKind.SAMPLE, sound()!!.kind)
        assertEquals(listOf(Page.SAMPLE, Page.VOLUME, Page.ARPEGGIO, Page.PITCH), PceInstrumentView.pages(session.song, v.state))
        // Clear on Kind: back to tone; Clear on Slot empties it, undo brings it back.
        v.tap(Action.CLEAR)
        assertEquals(PceKind.TONE, sound()!!.kind)
        v.goTo(Row.SLOT)
        v.tap(Action.CLEAR)
        assertNull(sound())
        session.undo()
        assertEquals(PceKind.TONE, sound()!!.kind)
    }

    @Test
    fun noiseSoundsListenOnlyOnPsg5And6() {
        session.setPceInstrument(slot, defaultPceInstrument(slot, PceKind.NOISE), "test")
        val v = view { instrument = slot; listenChannel = 0 }
        assertEquals(PceRules.NOISE_CHANNELS.first, PceInstrumentView.listenChannel(v.state, sound()))
        v.tap(Action.AUDITION)
        assertEquals(Triple(PceRules.NOISE_CHANNELS.first, v.defaultNote(), slot), auditions.last())
        assertTrue(session.message!!.contains("PSG 5-6"))
        // On PSG 6 it plays there.
        v.goTo(Row.LISTEN)
        repeat(5) { v.tap(Action.RIGHT) }
        assertEquals(5, v.state.listenChannel)
        v.tap(Action.AUDITION)
        assertEquals(5, auditions.last().first)
        // A tone sound plays on whatever channel Listen is on.
        session.setPceKind(slot, PceKind.TONE)
        v.state.listenChannel = 2
        v.tap(Action.AUDITION)
        assertEquals(Triple(2, v.defaultNote(), slot), auditions.last())
        // An empty slot sounds nothing and says how to make a sound.
        val before = auditions.size
        val e = view { instrument = slot - 1 }
        session.setPceInstrument(slot - 1, null, "test")
        e.tap(Action.AUDITION)
        assertEquals(before, auditions.size)
        assertTrue(session.message!!.contains("empty"))
    }

    @Test
    fun envelopeStepsAreDrawnAndEditedWithTheController() {
        session.setPceInstrument(slot, defaultPceInstrument(slot), "test")
        val v = view { instrument = slot }
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        // Volume starts as one step of 31: lower it, then carry it along three more steps.
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN)
        assertEquals(listOf(29), sound()!!.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(29, 29, 29, 29), sound()!!.volume.values)
        assertEquals(3, v.state.envStep)
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(listOf(29, 29, 29, 26), sound()!!.volume.values)
        // One hold is one undo step.
        session.undo()
        assertEquals(listOf(29, 29, 29, 29), sound()!!.volume.values)
        // Clear deletes the step under the cursor; past the end a tap appends the last value.
        v.tap(Action.LEFT)
        v.tap(Action.CLEAR)
        assertEquals(listOf(29, 29, 29), sound()!!.volume.values)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        assertEquals(3, v.state.envStep)
        v.tap(Action.EDIT)
        assertEquals(listOf(29, 29, 29, 29), sound()!!.volume.values)
        // Values stay in range: volume tops out at 31, pitch bottoms out at -128.
        v.tap(Action.LEFT)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(31, sound()!!.volume.values[3])
        v.tap(Action.NEXT_CHANNEL); v.tap(Action.NEXT_CHANNEL) // arpeggio, pitch
        assertEquals(Page.PITCH, PceInstrumentView.page(session.song, v.state))
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(200) { Action.DOWN })
        assertEquals(listOf(PceEnvelope.PITCH.first), sound()!!.pitch.values)
        v.goTo(Row.MODE)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.pitchRelative)
    }

    @Test
    fun theWaveSequenceStepsThroughTheSongsWavesFromTheSoundsWave() {
        session.setPceInstrument(slot, defaultPceInstrument(slot).copy(wave = 1), "test")
        val v = view { instrument = slot }
        v.showPage(Page.WAVES)
        v.goTo(Row.STEPS)
        v.tap(Action.EDIT) // an empty sequence's first step starts on the sound's own wave
        assertEquals(listOf(1), sound()!!.waves.values)
        // The tap left the cursor on the append position (as on the NES); back onto the new step.
        assertEquals(1, v.state.envStep)
        v.tap(Action.LEFT)
        v.hold(Action.EDIT, *Array(100) { Action.UP })
        assertEquals(listOf(session.song.pce.waves.size - 1), sound()!!.waves.values)
    }

    @Test
    fun loopAndReleaseStayLegal() {
        session.setPceInstrument(slot, defaultPceInstrument(slot), "test")
        val v = view { instrument = slot }
        v.showPage(Page.VOLUME)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.UP) // +8
        assertEquals(9, sound()!!.volume.values.size)
        // A release needs no loop on the PC Engine: it starts at step 1.
        v.goTo(Row.RELEASE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(-1, sound()!!.volume.loop)
        assertEquals(1, sound()!!.volume.release)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(5, sound()!!.volume.release)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, *Array(9) { Action.RIGHT })
        assertEquals("the loop stays before the release", 4, sound()!!.volume.loop)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, *Array(6) { Action.LEFT })
        assertEquals(3, sound()!!.volume.values.size)
        assertEquals(-1, sound()!!.volume.release)
        assertEquals(2, sound()!!.volume.loop)
        v.goTo(Row.LOOP)
        v.tap(Action.CLEAR)
        assertEquals(-1, sound()!!.volume.loop)
        // Every envelope may release; values are clamped and loops kept before releases.
        val e = PceEnvelope(listOf(1, 2, 3), loop = 2, release = 2)
        assertEquals(PceEnvelope(listOf(1, 2, 3), 1, 2), PceEnvelopeKind.ARPEGGIO.fixed(e, PceEnvelope.ARPEGGIO))
        assertEquals(PceEnvelope(listOf(1, 1, 1), -1, -1), PceEnvelopeKind.WAVE.fixed(PceEnvelope(listOf(1, 5, 9), release = 3), 0..1))
        assertEquals(PceEnvelope(listOf(2, 3), 0, 1), PceEnvelopes.removed(PceEnvelopeKind.VOLUME, e, 0, PceEnvelope.VOLUME))
        assertNull(PceEnvelopes.removed(PceEnvelopeKind.VOLUME, e, 0, PceEnvelope.VOLUME).problem(PceEnvelope.VOLUME))
    }

    @Test
    fun copyPasteEnvelopesBetweenKindsAndSoundsBetweenSlots() {
        session.setPceInstrument(slot - 1, defaultPceInstrument(slot - 1), "test")
        val v = view { instrument = slot - 1 }
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(80) { Action.UP }, Action.RIGHT)
        assertEquals(listOf(63, 63), sound(slot - 1)!!.arpeggio.values)
        v.tap(Action.COPY)
        v.tap(Action.NEXT_CHANNEL) // pitch
        v.tap(Action.PASTE)
        assertEquals(listOf(63, 63), sound(slot - 1)!!.pitch.values)
        v.tap(Action.PREV_CHANNEL); v.tap(Action.PREV_CHANNEL) // volume
        v.tap(Action.PASTE)
        assertEquals("clamped into volume's range", listOf(31, 31), sound(slot - 1)!!.volume.values)
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        v.tap(Action.RIGHT)
        assertEquals(slot, v.state.instrument)
        v.tap(Action.PASTE)
        assertEquals(sound(slot - 1), sound())
        // Duplicate refuses past the last slot.
        v.tap(Action.DUPLICATE)
        assertTrue(session.message!!.contains("last slot"))
    }

    @Test
    fun presetsReplaceTheSoundWithTheirWave() {
        val v = view { instrument = slot }
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        val preset = PcePresets.ALL.first()
        val ins = sound()!!
        assertEquals(preset.instrument.kind, ins.kind)
        assertEquals(preset.name.take(PceInstrumentView.NAME_MAX), ins.name)
        preset.wave?.let { assertEquals(it, session.song.pce.waves[ins.wave]) }
        assertEquals(0, PceInstrumentView.presetIndex(session.song, ins))
        // The next one, wrapping through every preset; a noise preset listens on PSG 5-6.
        v.hold(Action.EDIT, *Array(PcePresets.ALL.size) { Action.RIGHT })
        assertEquals(0, PceInstrumentView.presetIndex(session.song, sound()))
        val noise = PcePresets.ALL.indexOfFirst { it.category == PcePresets.Category.NOISE }
        if (noise >= 0) {
            v.state.listenChannel = 0
            v.hold(Action.EDIT, *Array(noise) { Action.RIGHT })
            assertEquals(PceKind.NOISE, sound()!!.kind)
            assertTrue(v.state.listenChannel in PceRules.NOISE_CHANNELS)
        }
        // Edit opens the list; choosing an entry applies it.
        installMenus()
        v.goTo(Row.PRESET)
        v.tap(Action.EDIT)
        assertEquals(PcePresets.ALL.size, menus.single().size)
        menus.single()[0].second()
        assertEquals(0, PceInstrumentView.presetIndex(session.song, sound()))
        // Clear: the default sound of the kind.
        v.tap(Action.CLEAR)
        assertEquals(defaultPceInstrument(slot, sound()!!.kind), sound())
    }

    @Test
    fun theWaveRowPicksAndShowsTheSoundsWave() {
        ensureWaves(4)
        session.setPceInstrument(slot, defaultPceInstrument(slot), "test")
        val v = view { instrument = slot }
        v.goTo(Row.WAVE)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(2, sound()!!.wave)
        assertEquals(2, session.selection.wave)
        v.tap(Action.EDIT)
        assertEquals(ViewKind.WAVE, session.selection.context)
        v.tap(Action.CLEAR)
        assertEquals(0, sound()!!.wave)
    }

    @Test
    fun theWaveViewDrawsAndShapesTheSelectedWave() {
        ensureWaves(4)
        val w = view(ViewKind.WAVE) { wave = 0; waveRow = 1; waveCol = 0; waveLevel = 8 }
        w.tap(Action.EDIT)
        assertEquals(8, session.song.pce.waves[0][0])
        w.hold(Action.EDIT, Action.RIGHT, Action.UP, Action.UP)
        assertEquals(10, session.song.pce.waves[0][1])
        session.undo()
        assertEquals(8, session.song.pce.waves[0][0])
        // Tools: invert, shapes.
        val before = session.song.pce.waves[0]
        w.state.waveRow = 0
        w.state.waveCol = PceWaveView.Tool.INVERT.ordinal
        w.tap(Action.EDIT)
        assertEquals(before.map { PceSound.WAVE_MAX - it }, session.song.pce.waves[0])
        w.state.waveCol = PceWaveView.Tool.SHAPE.ordinal
        w.tap(Action.EDIT)
        assertTrue(PceWaveShapes.presetOf(session.song.pce.waves[0]) >= 0)
        w.hold(Action.EDIT, Action.RIGHT)
        assertTrue(PceWaveShapes.presetOf(session.song.pce.waves[0]) >= 0)
        // Use in sound: the selected tone sound plays the shown wave.
        session.setPceInstrument(slot, defaultPceInstrument(slot), "test")
        session.select(session.selection.copy(instrument = slot))
        w.state.wave = 2
        w.state.waveCol = PceWaveView.Tool.USE.ordinal
        w.tap(Action.EDIT)
        assertEquals(2, sound()!!.wave)
        w.tap(Action.AUDITION)
        assertEquals(slot, auditions.last().third)
        // Copy / paste a wave.
        w.tap(Action.COPY)
        w.state.wave = 3
        w.tap(Action.PASTE)
        assertEquals(session.song.pce.waves[2], session.song.pce.waves[3])
    }

    @Test
    fun addingDuplicatingAndDeletingWavesRenumbersEveryReference() {
        ensureWaves(4)
        val count = session.song.pce.waves.size
        session.setPceInstrument(slot, defaultPceInstrument(slot).copy(wave = 2, waves = PceEnvelope(listOf(2, 3))), "test")
        session.setCell(0, 0, 0, Cell().withEffect(1, PceRules.FX_WAVE, 3))
        val third = session.song.pce.waves[3]
        // Duplicate wave 2 through the Wave view: the copy is wave 3, old wave 3 moves to 4 and its uses follow.
        val w = view(ViewKind.WAVE) { wave = 2 }
        w.tap(Action.DUPLICATE)
        assertEquals(3, w.state.wave)
        assertEquals(count + 1, session.song.pce.waves.size)
        assertEquals(session.song.pce.waves[2], session.song.pce.waves[3])
        assertEquals(third, session.song.pce.waves[4])
        assertEquals(2, sound()!!.wave)
        assertEquals(listOf(2, 4), sound()!!.waves.values)
        assertEquals(4, session.song.patterns[0].tracks[0][0].effectParam(1))
        // Delete wave 2: its uses (this sound's wave and sequence step, and the new song's sounds on it) move to wave 1,
        // later waves (and their uses) move down.
        val uses = waveReferences(session.song, 2)
        assertTrue(uses >= 2)
        w.state.wave = 2
        w.state.waveRow = 0
        w.state.waveCol = PceWaveView.Tool.DELETE.ordinal
        w.tap(Action.EDIT)
        assertEquals(count, session.song.pce.waves.size)
        assertEquals(1, sound()!!.wave)
        assertEquals(listOf(1, 3), sound()!!.waves.values)
        assertEquals(3, session.song.patterns[0].tracks[0][0].effectParam(1))
        assertEquals(third, session.song.pce.waves[3])
        // New wave appends.
        w.state.waveCol = PceWaveView.Tool.NEW.ordinal
        w.tap(Action.EDIT)
        assertEquals(count + 1, session.song.pce.waves.size)
        assertEquals(count, w.state.wave)
        assertEquals(PcePresets.WAVES.first().second, session.song.pce.waves[count])
        // One undo step each.
        session.undo(); session.undo(); session.undo()
        assertEquals(count, session.song.pce.waves.size)
        assertEquals(2, sound()!!.wave)
        assertEquals(3, session.song.patterns[0].tracks[0][0].effectParam(1))
    }

    @Test
    fun theOnlyWaveOfToneSoundsIsNotDeleted() {
        val song = session.song
        val one = song.copy(sound = song.pce.copy(waves = listOf(song.pce.waves[0])))
        session.replaceSong(one)
        assertEquals(-1, session.deletePceWave(0))
        assertEquals(1, session.song.pce.waves.size)
    }

    @Test
    fun theStarterKitAddsSamplesAndOneSoundEach() {
        installMenus()
        session.setPceInstrument(slot - 8, defaultPceInstrument(slot - 8, PceKind.SAMPLE), "test")
        val v = view { instrument = slot - 8 }
        assertEquals(Page.SAMPLE, PceInstrumentView.page(session.song, v.state))
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        assertEquals(2, menus.single().size)
        menus.single()[0].second()
        val kit = PceStarterKit.samples(PceSamples.rateHz(session.song.pce.timerReload))
        assertEquals(kit.map { it.name }, session.song.pce.samples.map { it.name })
        // The shown sample sound plays the first; the others get the next empty slots.
        assertEquals(0, sound(slot - 8)!!.sample)
        for (i in 1 until kit.size) {
            val ins = sound(slot - 8 + i)!!
            assertEquals(PceKind.SAMPLE, ins.kind)
            assertEquals(i, ins.sample)
        }
        // Adding it again reuses the stored frames.
        menus.clear()
        v.tap(Action.EDIT)
        menus.single()[0].second()
        assertEquals(kit.size, session.song.pce.samples.size)
        // Listening plays the sample sound.
        v.tap(Action.AUDITION)
        assertEquals(Triple(v.state.listenChannel, v.defaultNote(), slot - 8), auditions.last())
    }

    @Test
    fun theStarterKitIsMadeAtTheSongsDdaRate() {
        installMenus()
        session.replaceSong(session.song.let { s -> s.copy(sound = s.pce.copy(timerReload = 1)) })
        session.setPceInstrument(slot - 8, defaultPceInstrument(slot - 8, PceKind.SAMPLE), "test")
        val v = view { instrument = slot - 8 }
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        menus.single()[0].second()
        // At 3496 Hz the kick still lasts 0.25 s on its root note (not twice as long, an octave down).
        val kick = session.song.pce.samples[PceStarterKit.KICK]
        assertEquals(0.25, PceSamples.seconds(kick.data.size, 1), 1.0 / PceSamples.rateHz(1))
    }

    @Test
    fun aWavFileIsConvertedAtTheSongsDdaRate() {
        installMenus()
        val rate = PceSamples.rateHz(session.song.pce.timerReload)
        picked = Triple("Zap", ".wav", wav(ShortArray(rate / 10) { (if (it % 20 < 10) 12000 else -12000).toShort() }, rate))
        session.setPceInstrument(slot, defaultPceInstrument(slot, PceKind.SAMPLE).copy(root = 60), "test")
        val v = view { instrument = slot }
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        menus.single()[1].second()
        val sample = session.song.pce.samples.single()
        assertEquals("Zap", sample.name)
        assertTrue(sample.data.size in rate / 10 - 1..rate / 10 + 1)
        assertTrue(sample.data.all { it in 0..PceSound.WAVE_MAX })
        // The shown sample sound keeps its settings and plays the new sample.
        assertEquals(0, sound()!!.sample)
        assertEquals(60, sound()!!.root)
        // One undo step removes it.
        session.undo()
        assertTrue(session.song.pce.samples.isEmpty())
    }

    @Test
    fun sampleSettingsAndDeletingASampleRenumberTheSounds() {
        val samples = listOf(sample("A", 40, 3), sample("B", 50, 5), sample("C", 60, 7))
        session.addPceSamples(slot - 2, samples, "test") { i, name -> PceStarterKit.instrument(i, name) }
        assertEquals(listOf(0, 1, 2), (slot - 2..slot).map { sound(it)!!.sample })
        val v = view { instrument = slot }
        assertEquals(Page.SAMPLE, PceInstrumentView.page(session.song, v.state))
        // Sample, root note, loop and loop start of sound 64 (sample C).
        v.goTo(Row.SAMPLE)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(1, sound()!!.sample)
        assertEquals(1, v.state.dpcmSample)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(2, sound()!!.sample)
        v.goTo(Row.ROOT)
        v.hold(Action.EDIT, Action.UP, Action.RIGHT)
        assertEquals(PceInstrument.ROOT + 13, sound()!!.root)
        v.tap(Action.CLEAR)
        assertEquals(PceInstrument.ROOT, sound()!!.root)
        v.goTo(Row.SAMPLE_LOOP)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.loop)
        v.goTo(Row.LOOP_START)
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.UP, Action.UP, Action.RIGHT)
        assertEquals("clamped to the last frame of C", 59, sound()!!.loopStart)
        // Rename the listed sample.
        v.goTo(Row.SAMPLE_NAME)
        v.tap(Action.RIGHT)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals("CA", session.song.pce.samples[2].name)
        // Delete sample B from the list: sound 63 (B) plays A, sound 64 (C) follows C down to 1.
        v.goTo(Row.SAMPLES)
        v.tap(Action.LEFT)
        assertEquals(1, v.state.dpcmSample)
        v.tap(Action.CLEAR)
        assertEquals(listOf("A", "CA"), session.song.pce.samples.map { it.name })
        assertEquals(listOf(0, 0, 1), (slot - 2..slot).map { sound(it)!!.sample })
        assertEquals(59, sound()!!.loopStart)
        // Delete A: sound 62 and 63 play the new first sample; 64's loop start stays inside it.
        assertEquals(2, session.deletePceSample(0))
        assertEquals(listOf(0, 0, 0), (slot - 2..slot).map { sound(it)!!.sample })
        // The list picks the sound's sample with Edit.
        session.undo()
        v.state.dpcmSample = 0
        v.goTo(Row.SAMPLES)
        v.tap(Action.EDIT)
        assertEquals(0, sound()!!.sample)
        assertEquals(39, sound()!!.loopStart)
    }

    /** Sound 63 plays A, sound 64 plays B looping from frame 50; then the clipboard holds sound 64. */
    private fun copyLoopingSampleSound(): PceInstrument {
        session.addPceSamples(slot - 1, listOf(sample("A", 40, 3), sample("B", 60, 5)), "test") { i, name -> PceStarterKit.instrument(i, name) }
        session.editPceInstrument(slot, "test") { it.copy(loop = true, loopStart = 50) }
        session.copyPceInstrument(slot)
        return sound()!!
    }

    private fun songWithSamples(samples: List<PceSample>) = PceSystem.blank().let { s -> s.copy(sound = s.pce.copy(samples = samples)) }

    @Test
    fun aPastedSampleSoundBringsItsFramesAndLoopToAnotherSong() {
        val copied = copyLoopingSampleSound()
        val b = session.song.pce.samples[1]
        val other = songWithSamples(listOf(sample("Z", 10, 7), sample("Y", 20, 9)))
        session.replaceSong(other)
        assertTrue(session.pastePce(slot, null))
        // B is added after the other song's samples; the sound plays it with its loop unchanged.
        assertEquals(other.pce.samples + b, session.song.pce.samples)
        assertEquals(copied.copy(sample = 2), sound())
        // One undo step restores the other song exactly.
        session.undo()
        assertEquals(other, session.song)
        assertFalse(session.canUndo)
    }

    @Test
    fun aPastedSampleSoundOutlivesItsDeletedSample() {
        val copied = copyLoopingSampleSound()
        session.deletePceSample(1)
        val deleted = session.song
        assertTrue(session.pastePce(slot, null))
        assertEquals(listOf("A", "B"), session.song.pce.samples.map { it.name })
        assertEquals(copied, sound())
        session.undo()
        assertEquals(deleted, session.song)
    }

    @Test
    fun aPastedSampleSoundReusesTheSameFramesAndRespectsTheSampleLimit() {
        val copied = copyLoopingSampleSound()
        val b = session.song.pce.samples[1]
        // The other song already holds B's frames (under another name): nothing is added.
        val holding = songWithSamples(listOf(sample("Z", 10, 7), b.copy(name = "Same frames")))
        session.replaceSong(holding)
        assertTrue(session.pastePce(slot, null))
        assertEquals(holding.pce.samples, session.song.pce.samples)
        assertEquals(copied.copy(sample = 1), sound())
        // A full song without B's frames refuses the paste and stays as it was, with nothing to undo.
        val full = songWithSamples(List(PceSound.MAX_SAMPLES) { i -> PceSample("S$i", listOf(i % 32, i / 32)) })
        session.replaceSong(full)
        assertFalse(session.pastePce(slot, null))
        assertTrue(session.song === full)
        assertFalse(session.canUndo)
        // A full song holding B's frames plays them.
        val fullHolding = songWithSamples(full.pce.samples.dropLast(1) + b)
        session.replaceSong(fullHolding)
        assertTrue(session.pastePce(slot, null))
        assertEquals(fullHolding.pce.samples, session.song.pce.samples)
        assertEquals(copied.copy(sample = PceSound.MAX_SAMPLES - 1), sound())
    }

    @Test
    fun aClampedSampleIndexKeepsTheLoopStartInsideTheSampleItNowPlays() {
        // A sound naming a sample the song does not have plays its last one; the loop start fits that sample.
        val song = songWithSamples(listOf(sample("Z", 10, 7), sample("Y", 100, 9))).pce
        val ins = PceInstrument("x", PceKind.SAMPLE, sample = 5, loop = true, loopStart = 50)
        assertEquals(ins.copy(sample = 1), pceInstrumentFor(song, ins))
        assertEquals(ins.copy(sample = 1, loopStart = 99), pceInstrumentFor(song, ins.copy(loopStart = 200)))
    }

    @Test
    fun aSampleSoundWithoutSamplesSaysSoInsteadOfSounding() {
        session.setPceInstrument(slot, defaultPceInstrument(slot, PceKind.SAMPLE), "test")
        val v = view { instrument = slot }
        v.tap(Action.AUDITION)
        assertTrue(auditions.isEmpty())
    }

    @Test
    fun sketchNoiseAssignmentIsAtomicAndKeepsTheCursorOnRejection() {
        session.applyPcePreset(slot, PcePresets.named("Snare")!!)
        val v = view(ViewKind.SKETCH) { channel = 0; instrument = slot }
        v.state.sketchPhrase = Phrase(listOf(48, null, 50, null))
        v.sketchSetPhraseField(ViewController.PHRASE_INSERT)
        val before = session.song
        val selection = session.selection
        v.tap(Action.EDIT)
        assertEquals(before, session.song)
        assertEquals(selection, session.selection)
        v.state.channel = 4
        v.tap(Action.EDIT)
        assertEquals(Cell(note = 48, instrument = slot), session.song.patterns[0].tracks[4][0])
        assertEquals(Cell(note = 50, instrument = slot), session.song.patterns[0].tracks[4][2])
        assertEquals(4, session.selection.row)
        assertTrue(PceSystem.errors(session.song).isEmpty())
    }

    @Test
    fun keyboardRefusesNoiseOnToneOnlyLanesWithoutWritingOrAdvancing() {
        session.applyPcePreset(slot, PcePresets.named("Snare")!!)
        session.step = 1
        val k = view(ViewKind.KEYBOARD) { octave = 4; entryMode = true }
        for (ch in 0..3) {
            k.setKeyboardChannel(ch)
            k.setKeyboardSound(slot)
            val before = session.song
            val row = session.selection.row
            val heard = auditions.size
            k.keyDown(0)
            assertEquals(before, session.song)
            assertEquals(row, session.selection.row)
            assertEquals(heard, auditions.size)
            assertEquals(PceValidator.cellProblem(before, ch, Cell(note = 48, instrument = slot)), session.message)
            k.touchKeyUp()
        }
        for (ch in PceRules.NOISE_CHANNELS) {
            k.setKeyboardChannel(ch)
            val row = session.selection.row
            k.keyDown(0)
            assertEquals(Cell(note = 48, instrument = slot), session.song.patterns[0].tracks[ch][row])
            assertEquals(row + 1, session.selection.row)
            assertEquals(Triple(ch, 48, slot), auditions.last())
            assertTrue(PceSystem.errors(session.song).isEmpty())
            k.touchKeyUp()
        }
    }

    @Test
    fun patternNoiseEntryAndSoundAdjustmentAreCheckedBeforeMutation() {
        session.applyPcePreset(slot, PcePresets.named("Snare")!!)
        session.setPceInstrument(slot - 1, defaultPceInstrument(slot - 1), "Tone")
        val v = view(ViewKind.PATTERN) { channel = 0; field = 0 }
        session.select(session.selection.copy(instrument = slot))
        v.follow()
        val before = session.song
        val row = v.state.row
        v.tap(Action.EDIT)
        assertEquals(before, session.song)
        assertEquals(row, v.state.row)
        assertTrue(auditions.isEmpty())
        session.setCell(0, 0, row, Cell(note = 48, instrument = slot - 1))
        v.state.field = 1
        val withTone = session.song
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(withTone, session.song)
        assertEquals(slot - 1, session.song.patterns[0].tracks[0][row].instrument)
        // The identical sound adjustment is legal on a noise-capable channel.
        session.setCell(0, 4, row, Cell(note = 48, instrument = slot - 1))
        v.state.channel = 4
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(slot, session.song.patterns[0].tracks[4][row].instrument)
        assertTrue(PceSystem.errors(session.song).isEmpty())
    }

    @Test
    fun notePreflightDoesNotBlockUnfinishedEffectsOrRepairAndOff() {
        session.applyPcePreset(slot, PcePresets.named("Snare")!!)
        // Imported invalid data is preserved; clearing the bad note must remain possible.
        session.setCell(0, 0, 0, Cell(note = 48, instrument = slot))
        val v = view(ViewKind.PATTERN) { channel = 0; row = 0; field = 0 }
        v.tap(Action.CLEAR)
        assertEquals(Cell(), session.song.patterns[0].tracks[0][0])
        v.tap(Action.CLEAR)
        assertEquals(Cell(note = Cell.CUT), session.song.patterns[0].tracks[0][0])
        assertEquals(1, session.selection.row)
        v.tap(Action.UP)
        // A legal assignment must not be blocked by an unrelated effect still being edited.
        session.setCell(0, 4, 0, Cell(effect = PceRules.FX_VOLUME, param = 32))
        val k = view(ViewKind.KEYBOARD) { octave = 4; entryMode = true }
        k.setKeyboardChannel(4)
        k.setKeyboardSound(slot)
        k.keyDown(0)
        k.touchKeyUp()
        assertEquals(Cell(note = 48, instrument = slot, effect = PceRules.FX_VOLUME, param = 32),
            session.song.patterns[0].tracks[4][0])
    }

    @Test
    fun noiseReplacementChecksParkedBindingsAndTheirInheritedNotes() {
        session.setPceInstrument(slot, defaultPceInstrument(slot), "Tone")
        session.setCell(0, 0, 0, Cell(note = 48, instrument = slot))
        session.setCell(0, 0, 1, Cell(note = 50))
        val used = session.song.patterns[0]
        session.replaceSong(session.song.copy(patterns = listOf(PceSystem.blank().patterns[0], used),
            orders = listOf(0, 1), parkedOrders = listOf(1)))
        val before = session.song
        val snare = PcePresets.named("Snare")!!
        assertFalse(session.applyPcePreset(slot, snare))
        assertEquals(before, session.song)
        assertFalse(session.setPceKind(slot, PceKind.NOISE))
        assertEquals(before, session.song)
        session.hold(PceClip.Sound(snare.instrument, null))
        assertFalse(session.pastePce(slot, null))
        assertEquals(before, session.song)
        assertFalse(session.canUndo)
        // Moving the binding and its inherited notes is an explicit user edit, never a side effect of the preset.
        val moved = used.copy(tracks = used.tracks.mapIndexed { ch, track -> when (ch) {
            0 -> PceSystem.blank().patterns[0].tracks[0]
            4 -> used.tracks[0]
            else -> track
        } })
        session.replaceSong(before.copy(patterns = listOf(before.patterns[0], moved)))
        assertTrue(session.applyPcePreset(slot, snare))
        assertEquals(PceKind.NOISE, session.song.pce.instrument(slot)!!.kind)
        assertEquals(Cell(note = 50), session.song.patterns[1].tracks[4][1])
        assertTrue(PceSystem.errors(session.song).isEmpty())
    }

    @Test
    fun existingInvalidNoiseDocumentsRemainEditableAndRepairable() {
        session.applyPcePreset(slot, PcePresets.named("Snare")!!)
        session.setCell(0, 0, 0, Cell(note = 48, instrument = slot))
        assertTrue(session.editPceEnvelope(slot, PceEnvelopeKind.VOLUME, "Edit volume") { PceEnvelope(listOf(20, 0)) })
        assertEquals(slot, session.song.patterns[0].tracks[0][0].instrument)
        assertTrue(session.setPceKind(slot, PceKind.TONE))
        assertTrue(PceSystem.errors(session.song).isEmpty())
    }

    @Test
    fun keyboardRejectsBelowTheLowestNoteAndWritesTheBoundaryNote() {
        val k = view(ViewKind.KEYBOARD) { octave = 0; entryMode = true }
        k.setKeyboardSound(1)
        session.step = 1
        val before = session.song
        k.keyDown(8) // G#0, immediately below A-0.
        assertEquals(before, session.song)
        assertEquals(0, session.selection.row)
        assertTrue(auditions.isEmpty())
        k.touchKeyUp()
        k.keyDown(9)
        assertEquals(9, session.song.patterns[0].tracks[0][0].note)
        assertEquals(1, session.selection.row)
        k.touchKeyUp()
    }
}
