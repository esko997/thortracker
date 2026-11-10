package org.thortracker.system.ws

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
import org.thortracker.system.ws.WsInstrumentView.Page
import org.thortracker.system.ws.WsInstrumentView.Row

/** The WonderSwan instrument and wave editors driven through controller actions, and their document edits, on both models. */
class WsEditorTest {
    private val color = EditorSession(WscSystem.blank())
    private val mono = EditorSession(WsSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        for (s in listOf(color, mono)) s.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(session: EditorSession = color, kind: ViewKind = ViewKind.INSTRUMENT, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = kind).apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.goTo(row: Row) {
        state.instField = WsInstrumentView.rows(session.song, state).indexOf(row)
        assertTrue("row $row is on the page", state.instField >= 0)
    }

    private fun ViewController.showPage(page: Page) {
        state.envelope = WsInstrumentView.pages(session.song, state).indexOf(page)
        assertTrue("page $page exists", state.envelope >= 0)
    }

    /** The last slot: empty in a new song (the presets fill the first slots). */
    private val slot = WsRules.SLOTS

    private fun sound(session: EditorSession = color, s: Int = slot) = session.song.wsInstrument(s)

    /** Menus run inside the test: [menus] collects every popup's entries, [picked] answers file pickers. */
    private val menus = ArrayList<List<Pair<String, () -> Unit>>>()
    private var picked: Triple<String, String, ByteArray>? = null

    private fun installMenus(session: EditorSession) {
        session.onMenuRequest = { build ->
            build(object : MenuContext {
                override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { menus += entries }
                override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) {
                    val file = this@WsEditorTest.picked ?: return
                    picked(file.first, file.second, file.third)
                }
                override fun status(message: String) = Unit
                override fun error(message: String) = throw AssertionError(message)
                override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("no ROM install expected")
            })
        }
    }

    /** A 16-bit PCM `.wav` of [samples] (interleaved when [channels] is 2) at [rate] Hz. */
    private fun wav(samples: ShortArray, rate: Int, channels: Int = 1): ByteArray {
        val data = samples.size * 2
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        out.write("RIFF".toByteArray()); u32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); u32(16); u16(1); u16(channels); u32(rate); u32(rate * 2 * channels); u16(2 * channels); u16(16)
        out.write("data".toByteArray()); u32(data)
        for (s in samples) u16(s.toInt())
        return out.toByteArray()
    }

    @Test
    fun kindsSwitchWithTheirPagesAndKeepTheOtherKindsFields() {
        assertNull(sound())
        val v = view { instrument = slot }
        v.tap(Action.EDIT) // on Slot
        assertEquals(defaultWsInstrument(slot), sound())
        assertEquals(listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.WAVES), WsInstrumentView.pages(color.song, v.state))
        // Give the tone a sweep, then make it noise: Listen moves to CH4, the tap rows appear, the sweep is kept.
        color.editWsInstrument(slot, "test") { it.copy(sweep = -7, sweepTime = 2) }
        v.goTo(Row.KIND)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(WsKind.NOISE, sound()!!.kind)
        assertEquals(WsRules.NOISE_LANE, v.state.listenChannel)
        assertEquals(listOf(Page.VOLUME, Page.ARPEGGIO, Page.PITCH, Page.TAPS), WsInstrumentView.pages(color.song, v.state))
        assertTrue(Row.TAP in WsInstrumentView.rows(color.song, v.state) && Row.SWEEP !in WsInstrumentView.rows(color.song, v.state))
        assertEquals(-7, sound()!!.sweep)
        assertEquals(Row.KIND, WsInstrumentView.row(color.song, v.state))
        // Noise → sample: the Sample page comes first and the Color shows the Feed row.
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(WsKind.SAMPLE, sound()!!.kind)
        assertEquals(Page.SAMPLE, WsInstrumentView.pages(color.song, v.state).first())
        assertTrue(Row.FEED in WsInstrumentView.rows(color.song, v.state))
        // Clear on Kind: back to tone with its sweep.
        v.tap(Action.CLEAR)
        assertEquals(WsKind.TONE, sound()!!.kind)
        assertEquals(-7, sound()!!.sweep)
        assertEquals(2, sound()!!.sweepTime)
        // Clear on Slot empties it; undo brings it back.
        v.goTo(Row.SLOT)
        v.tap(Action.CLEAR)
        assertNull(sound())
        color.undo()
        assertEquals(WsKind.TONE, sound()!!.kind)
    }

    @Test
    fun feedSwitchesOnlyOnTheColorAndKeepsRootAndRate() {
        color.setWsInstrument(slot, defaultWsInstrument(slot, WsKind.SAMPLE).copy(root = 60, rate = 2), "test")
        val v = view { instrument = slot }
        assertEquals(listOf(Page.SAMPLE, Page.VOLUME, Page.ARPEGGIO, Page.PITCH), WsInstrumentView.pages(color.song, v.state))
        v.goTo(Row.FEED)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.isDma)
        assertEquals(listOf(Page.SAMPLE, Page.VOLUME), WsInstrumentView.pages(color.song, v.state))
        v.showPage(Page.SAMPLE)
        assertTrue(Row.RATE in WsInstrumentView.rows(color.song, v.state) && Row.ROOT !in WsInstrumentView.rows(color.song, v.state))
        v.goTo(Row.RATE)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, sound()!!.rate)
        v.goTo(Row.BACKWARDS)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.backwards)
        assertEquals(60, sound()!!.root)
        // Back to the CPU: root and rate both kept; backwards is kept but not played.
        assertTrue(color.setWsFeed(slot, WsFeed.CPU))
        assertEquals(60, sound()!!.root)
        assertEquals(3, sound()!!.rate)
        assertTrue(sound()!!.backwards)
        // The original model has no Sound DMA and no Feed row.
        mono.setWsInstrument(slot, defaultWsInstrument(slot, WsKind.SAMPLE), "test")
        assertFalse(mono.setWsFeed(slot, WsFeed.DMA))
        assertFalse(sound(mono)!!.isDma)
        val m = view(mono) { instrument = slot }
        assertFalse(Row.FEED in WsInstrumentView.rows(mono.song, m.state))
    }

    @Test
    fun listenPlaysOnlyWhereTheSoundFits() {
        color.setWsInstrument(slot, defaultWsInstrument(slot, WsKind.NOISE), "test")
        val v = view { instrument = slot; listenChannel = 0 }
        assertEquals(WsRules.NOISE_LANE, WsInstrumentView.listenChannel(color.song, v.state, sound()))
        v.tap(Action.AUDITION)
        assertEquals(Triple(WsRules.NOISE_LANE, v.defaultNote(), slot), auditions.last())
        // A tone listens on its lane, but not on Hyper Voice.
        color.setWsKind(slot, WsKind.TONE)
        v.state.listenChannel = 2
        v.tap(Action.AUDITION)
        assertEquals(2, auditions.last().first)
        v.state.listenChannel = WsRules.HYPER_VOICE
        v.tap(Action.AUDITION)
        assertEquals(0, auditions.last().first)
        // A sample sound without samples says so instead of sounding.
        color.setWsKind(slot, WsKind.SAMPLE)
        val before = auditions.size
        v.tap(Action.AUDITION)
        assertEquals(before, auditions.size)
    }

    @Test
    fun envelopesAreDrawnAndKeptInTheirRanges() {
        color.setWsInstrument(slot, defaultWsInstrument(slot), "test")
        val v = view { instrument = slot }
        v.showPage(Page.VOLUME)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN)
        assertEquals(listOf(13), sound()!!.volume.values)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(13, 13, 13), sound()!!.volume.values)
        v.tap(Action.LEFT)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(15, sound()!!.volume.values[1])
        color.undo()
        assertEquals(listOf(13, 13, 13), sound()!!.volume.values)
        // The noise tap sequence holds 0..7 and starts on the sound's tap mode.
        color.setWsInstrument(slot, defaultWsInstrument(slot, WsKind.NOISE).copy(tap = 5), "test")
        v.showPage(Page.TAPS)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.tap(Action.EDIT)
        assertEquals(listOf(5), sound()!!.taps.values)
        v.tap(Action.LEFT)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(listOf(7), sound()!!.taps.values)
        // Loop and release stay legal.
        val e = WsEnvelope(listOf(1, 2, 3), loop = 2, release = 2)
        assertEquals(WsEnvelope(listOf(1, 2, 3), 1, 2), WsEnvelopeKind.ARPEGGIO.fixed(e, WsEnvelope.ARPEGGIO))
        assertEquals(WsEnvelope(listOf(7, 7), -1, -1), WsEnvelopeKind.TAP.fixed(WsEnvelope(listOf(9, 12), release = 2), WsEnvelope.TAPS))
        assertNull(WsEnvelopes.removed(WsEnvelopeKind.VOLUME, e, 0, WsEnvelope.VOLUME).problem(WsEnvelope.VOLUME))
    }

    @Test
    fun sweepAndNoiseSettingsStepInRange() {
        color.setWsInstrument(slot, defaultWsInstrument(slot), "test")
        val v = view { instrument = slot }
        v.goTo(Row.SWEEP)
        v.hold(Action.EDIT, *Array(10) { Action.DOWN })
        assertEquals(-128, sound()!!.sweep)
        v.goTo(Row.SWEEP_TIME)
        v.hold(Action.EDIT, *Array(10) { Action.UP })
        assertEquals(31, sound()!!.sweepTime)
        v.tap(Action.CLEAR)
        assertEquals(0, sound()!!.sweepTime)
        color.setWsKind(slot, WsKind.NOISE)
        v.goTo(Row.TAP)
        v.hold(Action.EDIT, *Array(12) { Action.RIGHT })
        assertEquals(7, sound()!!.tap)
        v.goTo(Row.LFSR_RESET)
        v.tap(Action.EDIT)
        assertTrue(sound()!!.lfsrReset)
    }

    @Test
    fun presetsBringTheirWaveOrSampleAndRespectTheModel() {
        // A sweep tone preset: its wave is the song's or added.
        val laser = WsPresets.named("Laser (CH3)")!!
        assertTrue(color.applyWsPreset(slot, laser))
        assertEquals(laser.wave, color.song.ws.waves[sound()!!.wave])
        assertEquals(laser.instrument.sweep, sound()!!.sweep)
        // A sample preset adds its sample once.
        val kick = WsPresets.named("Kick (voice)")!!
        assertTrue(color.applyWsPreset(slot, kick))
        assertEquals(1, color.song.ws.samples.size)
        assertEquals(kick.sample!!.data, color.song.ws.samples[sound()!!.sample].data)
        assertTrue(color.applyWsPreset(slot - 1, kick))
        assertEquals(1, color.song.ws.samples.size)
        // DMA presets exist on the Color only.
        val dma = WsPresets.named("Kick (DMA)")!!
        assertTrue(dma in WsPresets.presets(true) && dma !in WsPresets.presets(false))
        assertFalse(mono.applyWsPreset(slot, dma))
        assertNull(sound(mono))
        // From an empty slot, the first step lands on the model's first preset; Edit opens the list.
        val v = view { instrument = slot - 2 }
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        val first = WsPresets.presets(true).first()
        assertEquals(0, WsInstrumentView.presetIndex(color.song, sound(color, slot - 2)))
        assertEquals(first.instrument.kind, sound(color, slot - 2)!!.kind)
        installMenus(color)
        v.tap(Action.EDIT)
        assertEquals(WsPresets.presets(true).size, menus.single().size)
    }

    @Test
    fun wavesAreDuplicatedAndDeletedWithEveryReferenceRenumbered() {
        val count = color.song.ws.waves.size
        color.setWsInstrument(slot, defaultWsInstrument(slot).copy(wave = 2, waves = WsEnvelope(listOf(2, 3))), "test")
        color.setCell(0, 0, 0, Cell().withEffect(1, WsRules.FX_WAVE, 3))
        val third = color.song.ws.waves[3]
        val w = view(kind = ViewKind.WAVE) { wave = 2 }
        w.tap(Action.DUPLICATE)
        assertEquals(3, w.state.wave)
        assertEquals(count + 1, color.song.ws.waves.size)
        assertEquals(third, color.song.ws.waves[4])
        assertEquals(2, sound()!!.wave)
        assertEquals(listOf(2, 4), sound()!!.waves.values)
        assertEquals(4, color.song.patterns[0].tracks[0][0].effectParam(1))
        // Delete wave 2: its uses move to wave 1, later ones down.
        assertTrue(waveReferences(color.song, 2) >= 2)
        w.state.wave = 2
        w.state.waveRow = 0
        w.state.waveCol = WsWaveView.Tool.DELETE.ordinal
        w.tap(Action.EDIT)
        assertEquals(count, color.song.ws.waves.size)
        assertEquals(1, sound()!!.wave)
        assertEquals(listOf(1, 3), sound()!!.waves.values)
        assertEquals(3, color.song.patterns[0].tracks[0][0].effectParam(1))
        assertEquals(third, color.song.ws.waves[3])
        // Drawing stays within 0..15.
        val d = view(kind = ViewKind.WAVE) { wave = 0; waveRow = 1; waveCol = 0; waveLevel = 15 }
        d.hold(Action.EDIT, Action.UP, Action.UP)
        assertEquals(15, color.song.ws.waves[0][0])
        assertEquals(WsWaveShapes.invert(List(32) { 15 }), List(32) { 0 })
    }

    @Test
    fun theStarterKitFollowsTheShownSoundsFeed() {
        installMenus(color)
        color.setWsInstrument(slot - 8, defaultWsInstrument(slot - 8, WsKind.SAMPLE).copy(feed = WsFeed.DMA), "test")
        val v = view { instrument = slot - 8 }
        assertEquals(Page.SAMPLE, WsInstrumentView.page(color.song, v.state))
        v.goTo(Row.ADD)
        v.tap(Action.EDIT)
        assertEquals(2, menus.single().size)
        menus.single()[0].second()
        val kit = WsStarterKit.samples()
        assertEquals(kit.map { it.name }, color.song.ws.samples.map { it.name })
        assertTrue(sound(color, slot - 8)!!.isDma)
        for (i in 1 until kit.size) {
            val ins = sound(color, slot - 8 + i)!!
            assertEquals(i, ins.sample)
            assertTrue(ins.isDma)
        }
        // Adding it again reuses the stored bytes.
        menus.clear()
        v.tap(Action.EDIT)
        menus.single()[0].second()
        assertEquals(kit.size, color.song.ws.samples.size)
        // The song still lowers: each DMA-played sample gets its copy.
        assertEquals(emptyList<String>(), WscSystem.errors(color.song))
    }

    @Test
    fun wavFilesKeepStereoOnTheColorAndMixOnTheOriginal() {
        val rate = WsSamples.timerRateHz(WsSound.DEFAULT_TIMER)
        val stereo = ShortArray(rate / 5) { (if (it % 2 == 0) 12000 else -12000).toShort() }
        for ((session, keeps) in listOf(color to true, mono to false)) {
            installMenus(session)
            menus.clear()
            picked = Triple("Zap", ".wav", wav(stereo, rate, channels = 2))
            session.setWsInstrument(slot, defaultWsInstrument(slot, WsKind.SAMPLE).copy(root = 60), "test")
            val v = view(session) { instrument = slot }
            v.goTo(Row.ADD)
            v.tap(Action.EDIT)
            menus.single()[1].second()
            val sample = session.song.ws.samples.single()
            assertEquals(keeps, sample.stereo)
            assertEquals(rate / 10, sample.frames)
            assertTrue(sample.data.all { it in 0..255 })
            assertEquals(0, sound(session)!!.sample)
            assertEquals(60, sound(session)!!.root)
            session.undo()
            assertTrue(session.song.ws.samples.isEmpty())
        }
    }

    @Test
    fun deletingASampleRenumbersTheSounds() {
        val samples = listOf(WsSample("A", List(40) { it }), WsSample("B", List(50) { 2 * it }), WsSample("C", List(60) { 3 * it }))
        color.addWsSamples(slot - 2, samples, "test") { i, name -> WsStarterKit.instrument(i, name) }
        assertEquals(listOf(0, 1, 2), (slot - 2..slot).map { sound(color, it)!!.sample })
        color.editWsInstrument(slot, "test") { it.copy(loop = true, loopStart = 59) }
        val v = view { instrument = slot }
        v.goTo(Row.SAMPLES)
        v.state.dpcmSample = 1
        v.tap(Action.CLEAR)
        assertEquals(listOf("A", "C"), color.song.ws.samples.map { it.name })
        assertEquals(listOf(0, 0, 1), (slot - 2..slot).map { sound(color, it)!!.sample })
        assertEquals(59, sound()!!.loopStart)
        assertEquals(2, color.deleteWsSample(0))
        assertEquals(listOf(0, 0, 0), (slot - 2..slot).map { sound(color, it)!!.sample })
    }

    @Test
    fun copyPasteMovesEnvelopesBetweenKinds() {
        color.setWsInstrument(slot - 1, defaultWsInstrument(slot - 1), "test")
        val v = view { instrument = slot - 1 }
        v.showPage(Page.ARPEGGIO)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, *Array(80) { Action.UP }, Action.RIGHT)
        assertEquals(listOf(63, 63), sound(color, slot - 1)!!.arpeggio.values)
        v.tap(Action.COPY)
        v.tap(Action.PREV_CHANNEL) // volume
        v.tap(Action.PASTE)
        assertEquals("clamped into volume's range", listOf(15, 15), sound(color, slot - 1)!!.volume.values)
        v.goTo(Row.SLOT)
        v.tap(Action.COPY)
        v.tap(Action.RIGHT)
        assertEquals(slot, v.state.instrument)
        v.tap(Action.PASTE)
        assertEquals(sound(color, slot - 1), sound())
        // A DMA sound pasted into an original-model song becomes CPU-fed.
        val dma = defaultWsInstrument(1, WsKind.SAMPLE).copy(feed = WsFeed.DMA)
        assertEquals(WsFeed.CPU, wsInstrumentFor(mono.song.ws, dma).feed)
    }

    @Test
    fun keysBelowTheLowestNoteNeitherPlayNorWrite() {
        val k = view(kind = ViewKind.KEYBOARD) { octave = 0 }
        assertFalse(k.keyPlayable(0)) // C-0, below G-1
        k.keyDown(0)
        assertTrue(auditions.isEmpty())
        k.touchKeyUp()
    }
}
