package org.thortracker.system.ym2612

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Cell
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.ym2612.YmInstrumentView.Page
import org.thortracker.system.ym2612.YmInstrumentView.Row

/** The Mega Drive editor driven through semantic controller actions: channels, effect columns, both instrument kinds. */
class YmEditorTest {
    private val session = EditorSession(YmSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(kind: ViewKind, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = kind).apply { ymPanel.advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun cell(channel: Int, row: Int): Cell = session.song.patterns[0].tracks[channel][row]

    private val firstPsg get() = session.song.ym.instruments.indexOfFirst { it is YmPsgInstrument } + 1

    private fun ViewController.goTo(row: Row) { state.instField = YmInstrumentView.rows(session.song, state).indexOf(row) }

    private fun fm(slot: Int) = session.song.ymInstrument(slot) as YmFmInstrument

    private fun psg(slot: Int) = session.song.ymInstrument(slot) as YmPsgInstrument

    @Test
    fun thirteenChannelsWithFourEffectColumnsAndANonTonalNoise() {
        assertEquals(listOf("FM 1", "FM 2", "FM 3", "FM 4", "FM 5", "FM 6", "PSG 1", "PSG 2", "PSG 3", "Noise", "PCM 1", "PCM 2", "PCM 3"),
            YmSystem.channels.map { it.name })
        assertTrue(YmSystem.channels.take(9).all { it.tonal })
        assertTrue(!YmSystem.channels[YmRules.NOISE].tonal)
        val v = view(ViewKind.PATTERN)
        assertTrue(v.multiColumn())
        assertEquals(4, YmSystem.effectColumns(9))
        assertTrue(YmSystem.errors(session.song).isEmpty())
    }

    @Test
    fun notesOnFmAndPsgChannelsGetTheirKindsSoundAndTwoEffectColumns() {
        session.step = 0
        for (ch in listOf(0, YmRules.PSG_FIRST)) {
            val v = view(ViewKind.PATTERN) { channel = ch; effectColumns = List(YmRules.CHANNELS) { 2 } }
            v.tap(Action.EDIT) // note with the entry sound
            val ins = session.song.ymInstrument(cell(ch, 0).instrument)
            if (ch == 0) assertTrue(ins is YmFmInstrument) else assertTrue("PSG lane got ${ins?.name}", ins is YmPsgInstrument)
            // effect 1: volume; effect 2: vibrato
            repeat(2) { v.tap(Action.RIGHT) }
            v.hold(Action.EDIT, *Array(YmRules.effectsFor(ch).indexOfFirst { it.code == YmRules.FX_VOLUME } + 1) { Action.RIGHT })
            repeat(2) { v.tap(Action.RIGHT) }
            v.hold(Action.EDIT, *Array(YmRules.effectsFor(ch).indexOfFirst { it.code == YmRules.FX_VIBRATO } + 1) { Action.RIGHT })
            assertEquals(YmRules.FX_VOLUME, cell(ch, 0).effectCode(0))
            assertEquals(YmRules.FX_VIBRATO, cell(ch, 0).effectCode(1))
        }
        assertTrue(YmSystem.errors(session.song).toString(), YmSystem.errors(session.song).isEmpty())
    }

    @Test
    fun fmPagesEditCuratedExpertAndDrum() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 3 }
        assertEquals(YmInstrumentView.FM_PAGES, YmInstrumentView.pages(session.song, v.state))
        val before = fm(3)
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertNotEquals(before, session.song.ymInstrument(3))
        assertTrue(auditions.isNotEmpty())
        assertTrue(auditions.last().first in 0 until YmRules.FM_CHANNELS)
        v.goTo(Row.ALGORITHM)
        val alg = fm(3).algorithm
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((alg + 1) % 8, fm(3).algorithm)
        v.goTo(Row.SSG)
        v.hold(Action.EDIT, Action.RIGHT)
        assertTrue(fm(3).operators.any { it.ssgEg >= 8 })
        v.goTo(Row.SUSTAIN_RATE)
        val sr = YmCurated.controls(fm(3)).sustainRate
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((sr + 1).coerceAtMost(31), YmCurated.controls(fm(3)).sustainRate)
        // Expert: operator 2's detune via R1 then Edit+→, AM switches on tap
        v.state.envelope = YmInstrumentView.FM_PAGES.indexOf(Page.EXPERT)
        v.goTo(Row.OP_DT)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(1, v.state.envStep)
        val dt = fm(3).operators[1].dt
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((dt + 1) % 8, fm(3).operators[1].dt)
        v.goTo(Row.OP_AM)
        val am = fm(3).operators[1].am
        v.tap(Action.EDIT)
        assertEquals(!am, fm(3).operators[1].am)
        v.goTo(Row.OP_TL)
        val tl = fm(3).operators[1].tl
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals((tl - 8).coerceAtLeast(0), fm(3).operators[1].tl)
        // Drum: a sweep start gives the sweep a speed
        v.state.envelope = YmInstrumentView.FM_PAGES.indexOf(Page.DRUM)
        session.editYmFm(3, "test") { it.copy(sweep = YmSweep.NONE) }
        v.goTo(Row.SWEEP_START)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(YmSweep(12, 0, 16), fm(3).sweep)
    }

    @Test
    fun psgPagesEditEnvelopesNoiseAndListenOnPsg() {
        val slot = firstPsg
        val v = view(ViewKind.INSTRUMENT) { instrument = slot; listenChannel = 0 }
        assertEquals(YmInstrumentView.PSG_PAGES, YmInstrumentView.pages(session.song, v.state))
        // Listen moves to a PSG channel for a PSG sound.
        v.tap(Action.AUDITION)
        assertEquals(YmRules.PSG_FIRST, auditions.last().first)
        // Volume steps: Edit+↓ lowers step 0; Edit on the append position adds a step.
        session.editYmPsg(slot, "test") { it.copy(volume = NesEnvelope(listOf(15, 12))) }
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(listOf(14, 12), psg(slot).volume.values)
        v.state.envStep = 2
        v.tap(Action.EDIT)
        assertEquals(listOf(14, 12, 12), psg(slot).volume.values)
        v.state.envStep = 2
        v.tap(Action.CLEAR)
        assertEquals(2, psg(slot).volume.values.size)
        // Arpeggio page: drawing along carries the value
        v.state.envelope = YmInstrumentView.PSG_PAGES.indexOf(Page.ARPEGGIO)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.RIGHT)
        assertEquals(listOf(2, 2), psg(slot).arpeggio.values.take(2))
        // Noise page: white/periodic and the rate
        v.state.envelope = YmInstrumentView.PSG_PAGES.indexOf(Page.NOISE)
        v.goTo(Row.NOISE_TYPE)
        val noise = psg(slot).noise
        v.tap(Action.EDIT)
        assertEquals(noise xor YmPsgInstrument.WHITE, psg(slot).noise)
        v.goTo(Row.NOISE_RATE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(((noise and 3) + 1) % 4, psg(slot).noise and 3)
    }

    @Test
    fun emptySlotsTakeTheListenKindAndTypeSwitches() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 50; listenChannel = YmRules.NOISE }
        assertNull(session.song.ymInstrument(50))
        assertEquals(YmInstrumentView.PSG_PAGES, YmInstrumentView.pages(session.song, v.state))
        v.tap(Action.EDIT) // on Slot
        assertTrue(session.song.ymInstrument(50) is YmPsgInstrument)
        assertEquals("Sound 32", session.song.ymInstrument(50)!!.name)
        v.goTo(Row.TYPE)
        v.tap(Action.EDIT) // PSG → PCM kit
        assertTrue(session.song.ymInstrument(50) is YmPcmInstrument)
        assertEquals("Sound 32", session.song.ymInstrument(50)!!.name)
        assertEquals(YmRules.PCM_FIRST, v.state.listenChannel)
        assertEquals(YmInstrumentView.PCM_PAGES, YmInstrumentView.pages(session.song, v.state))
        v.goTo(Row.TYPE)
        v.tap(Action.EDIT) // PCM kit → FM
        assertTrue(session.song.ymInstrument(50) is YmFmInstrument)
        assertEquals("Sound 32", session.song.ymInstrument(50)!!.name)
        assertEquals(0, v.state.listenChannel)
        assertEquals(YmInstrumentView.FM_PAGES, YmInstrumentView.pages(session.song, v.state))
        v.state.instField = 0
        v.tap(Action.CLEAR)
        assertNull(session.song.ymInstrument(50))
    }

    @Test
    fun noisePresetsListenOnNoiseAndCopyPasteMovesSounds() {
        val slot = firstPsg
        val v = view(ViewKind.INSTRUMENT) { instrument = slot; listenChannel = YmRules.PSG_FIRST }
        v.goTo(Row.PRESET)
        val noise = YmPresets.ALL.first { it.category == YmPresets.Category.NOISE }
        while (session.song.ymInstrument(slot) != noise.instrument) v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(YmRules.NOISE, v.state.listenChannel)
        v.state.instField = 0
        v.tap(Action.COPY)
        v.state.instrument = 60
        v.tap(Action.PASTE)
        assertEquals(noise.instrument, session.song.ymInstrument(60))
    }

}
