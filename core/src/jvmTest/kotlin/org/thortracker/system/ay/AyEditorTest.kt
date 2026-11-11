package org.thortracker.system.ay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.thortracker.model.Cell
import org.thortracker.system.ay.AyInstrumentView.Page
import org.thortracker.system.ay.AyInstrumentView.Row
import org.thortracker.system.nes.NesEnvelope

/** The AY-3-8910 / YM2149 editor driven through semantic controller actions: lanes, effect columns, every instrument page. */
class AyEditorTest {
    private val session = EditorSession(AyZx128System.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(kind: ViewKind, setup: ViewState.() -> Unit = {}) = ViewController(session,
        ViewState(kind = kind).apply { soundPanel("ay").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun cell(channel: Int, row: Int): Cell = session.song.patterns[session.song.orders[0]].tracks[channel][row]

    private fun ViewController.goTo(row: Row) {
        val i = AyInstrumentView.rows(state).indexOf(row)
        assertTrue("$row is not on page ${AyInstrumentView.page(state)}", i >= 0)
        state.instField = i
    }

    private fun ViewController.page(page: Page) { state.envelope = AyInstrumentView.PAGES.indexOf(page) }

    private fun ins(slot: Int) = session.song.ayInstrument(slot)!!

    @Test
    fun threeLanesWithFourEffectColumnsOnEveryVariant() {
        for (system in listOf(AyZx128System, AyMsxSystem, AyAtariStSystem, AyCpcSystem)) {
            assertEquals(listOf("Channel A", "Channel B", "Channel C"), system.channels.map { it.name })
            assertTrue(system.channels.all { it.tonal })
            assertEquals(List(3) { 4 }, (0 until 3).map { system.effectColumns(it) })
            assertTrue(system.errors(system.blank()).isEmpty())
            assertEquals(AyInstrumentView, system.view(ViewKind.INSTRUMENT))
        }
        assertTrue(view(ViewKind.PATTERN).multiColumn())
    }

    @Test
    fun notesOnEveryLaneGetASoundAndTwoEffectColumns() {
        session.step = 0
        for (ch in 0 until AyRules.CHANNELS) {
            val v = view(ViewKind.PATTERN) { channel = ch; effectColumns = List(AyRules.CHANNELS) { 2 } }
            v.tap(Action.EDIT) // note with the entry sound
            assertNotNull("lane $ch got no sound", session.song.ayInstrument(cell(ch, 0).instrument))
            // effect 1: volume; effect 2: vibrato
            repeat(2) { v.tap(Action.RIGHT) }
            v.hold(Action.EDIT, *Array(AyRules.effectsFor(ch).indexOfFirst { it.code == AyRules.FX_VOLUME } + 1) { Action.RIGHT })
            repeat(2) { v.tap(Action.RIGHT) }
            v.hold(Action.EDIT, *Array(AyRules.effectsFor(ch).indexOfFirst { it.code == AyRules.FX_VIBRATO } + 1) { Action.RIGHT })
            assertEquals(AyRules.FX_VOLUME, cell(ch, 0).effectCode(0))
            assertEquals(AyRules.FX_VIBRATO, cell(ch, 0).effectCode(1))
        }
        assertTrue(AyZx128System.errors(session.song).toString(), AyZx128System.errors(session.song).isEmpty())
    }

    @Test
    fun soundPageEditsPresetGatesNoiseAndTheHardwareEnvelope() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 1; listenChannel = 1 }
        assertEquals(Page.SOUND, AyInstrumentView.page(v.state))
        val before = ins(1)
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertNotEquals(before, ins(1))
        assertEquals(1, auditions.last().first) // Listen on Channel B
        // Gates: Tone → Noise → Tone + noise → Neither → Tone (one-value mixer envelopes; tone only is the empty one)
        session.editAy(1, "test") { it.copy(mixer = NesEnvelope()) }
        v.goTo(Row.GATES)
        val gates = ArrayList<List<Int>>()
        repeat(4) { v.hold(Action.EDIT, Action.RIGHT); gates += ins(1).mixer.values }
        assertEquals(listOf(listOf(2), listOf(3), listOf(0), emptyList<Int>()), gates)
        // Noise period: none → 0, ↑ +4, Clear → none
        session.editAy(1, "test") { it.copy(noise = NesEnvelope()) }
        v.goTo(Row.NOISE_PERIOD)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(listOf(0), ins(1).noise.values)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(listOf(AyInstrumentView.NOISE_COARSE), ins(1).noise.values)
        v.tap(Action.CLEAR)
        assertTrue(ins(1).noise.values.isEmpty())
        // A varying envelope still steps from its first value.
        session.editAy(1, "test") { it.copy(noise = NesEnvelope(listOf(5, 9))) }
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(listOf(6), ins(1).noise.values)
        // Hardware envelope: off → 8; back twice wraps to 15
        session.editAy(1, "test") { it.copy(envelope = AyEnvelopeSettings()) }
        v.goTo(Row.SHAPE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(8, ins(1).envelope.shape)
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals(15, ins(1).envelope.shape)
        // Period: auto steps the octave; a tap switches to manual, where ←→ is fine and ↑↓ one R12 step; Clear: auto J+0
        v.goTo(Row.PERIOD)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, ins(1).envelope.octave)
        v.tap(Action.EDIT)
        assertFalse(ins(1).envelope.auto)
        assertEquals(1, ins(1).envelope.octave) // kept while manual
        v.hold(Action.EDIT, Action.UP, Action.RIGHT)
        assertEquals(AyInstrumentView.PERIOD_COARSE + 1, ins(1).envelope.period)
        v.tap(Action.CLEAR)
        assertEquals(AyEnvelopeSettings(shape = 15, auto = true, octave = 0, period = AyInstrumentView.PERIOD_COARSE + 1), ins(1).envelope)
        v.goTo(Row.SYNC)
        v.tap(Action.EDIT)
        assertTrue(ins(1).envelope.sync)
        // Drum sweep: a start gives the sweep a speed
        session.editAy(1, "test") { it.copy(sweep = AySweep()) }
        v.goTo(Row.SWEEP_START)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(AySweep(12, 0, 16), ins(1).sweep)
        assertTrue(AyZx128System.errors(session.song).isEmpty())
    }

    @Test
    fun listenReadingNamesTheRegistersTheNoteWrites() {
        val variant = AyVariant.ZX128
        val plain = AyInstrument()
        val r = AyInstrumentView.reading(variant, plain, 57)
        assertEquals(252, r.tonePeriod) // A-4 on the ZX Spectrum 128 (docs/AUDIO_ENGINE.md)
        assertEquals(142, AyInstrumentView.reading(AyVariant.CPC, plain, 57).tonePeriod) // A-4 on the Amstrad CPC's 1 MHz chip
        assertNull(r.envelopePeriod)
        assertEquals(1, r.gates)
        assertNull(r.noise)
        val buzzer = AyInstrument(envelope = AyEnvelopeSettings(shape = 10, auto = true, octave = 0, sync = true))
        val b = AyInstrumentView.reading(variant, buzzer, 45)
        val ep = AyRules.autoEnvelopePeriod(variant, 45 * 64, 0, 10)
        assertEquals(ep, b.envelopePeriod)
        assertTrue(b.synced)
        assertEquals(AyRules.syncedTonePeriod(variant, ep, 0, 10), b.tonePeriod)
        val manual = AyInstrument(envelope = AyEnvelopeSettings(shape = 9, auto = false, period = 0x400))
        assertEquals(0x400, AyInstrumentView.reading(variant, manual, 57).envelopePeriod)
        assertEquals(252, AyInstrumentView.reading(variant, manual, 57).tonePeriod)
        assertTrue(AyInstrumentView.readingText(session.song, buzzer, 45).contains("EP"))
    }

    @Test
    fun softwareEnvelopePagesEditStepsWithTheirRanges() {
        val slot = 2
        val v = view(ViewKind.INSTRUMENT) { instrument = slot }
        // Volume: Edit+↓ lowers step 0; Edit on the append position adds a step; Clear deletes one.
        session.editAy(slot, "test") { it.copy(volume = NesEnvelope(listOf(15, 12))) }
        v.page(Page.VOLUME)
        v.goTo(Row.STEPS)
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals(listOf(14, 12), ins(slot).volume.values)
        v.state.envStep = 2
        v.tap(Action.EDIT)
        assertEquals(listOf(14, 12, 12), ins(slot).volume.values)
        v.state.envStep = 2
        v.tap(Action.CLEAR)
        assertEquals(2, ins(slot).volume.values.size)
        // Release needs a loop: setting one loops from step 0.
        session.editAy(slot, "test") { it.copy(volume = NesEnvelope(listOf(15, 12, 10, 8))) }
        v.goTo(Row.ENV_RELEASE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, ins(slot).volume.loop)
        assertEquals(1, ins(slot).volume.release)
        // Arpeggio: drawing along carries the value.
        session.editAy(slot, "test") { it.copy(arpeggio = NesEnvelope()) }
        v.page(Page.ARPEGGIO)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.RIGHT)
        assertEquals(listOf(2, 2), ins(slot).arpeggio.values)
        // Pitch: Mode switches relative/absolute.
        v.page(Page.PITCH)
        v.goTo(Row.MODE)
        val relative = ins(slot).pitchRelative
        v.tap(Action.EDIT)
        assertEquals(!relative, ins(slot).pitchRelative)
        // Mixer: values stay 0..3.
        session.editAy(slot, "test") { it.copy(mixer = NesEnvelope(listOf(1))) }
        v.page(Page.MIXER)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.UP)
        assertEquals(listOf(3), ins(slot).mixer.values)
        v.hold(Action.EDIT, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN, Action.DOWN)
        assertEquals(listOf(0), ins(slot).mixer.values)
        // Noise: an empty envelope's first step starts at 0; values stay 0..31; Length repeats the last value.
        session.editAy(slot, "test") { it.copy(noise = NesEnvelope()) }
        v.page(Page.NOISE)
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP)
        assertEquals(listOf(1), ins(slot).noise.values)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(listOf(1, 1), ins(slot).noise.values)
        v.goTo(Row.LOOP)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(0, ins(slot).noise.loop)
        assertEquals(-1, ins(slot).noise.release) // no release off volume
        session.editAy(slot, "test") { it.copy(noise = NesEnvelope(listOf(30))) }
        v.goTo(Row.STEPS)
        v.state.envStep = 0
        v.hold(Action.EDIT, Action.UP, Action.UP, Action.UP)
        assertEquals(listOf(31), ins(slot).noise.values)
        // An empty mixer envelope extended by Length starts from tone only.
        session.editAy(slot, "test") { it.copy(mixer = NesEnvelope()) }
        v.page(Page.MIXER)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT)
        assertEquals(listOf(1, 1), ins(slot).mixer.values)
        assertTrue(ins(slot).problem() == null)
    }

    @Test
    fun envelopeExpertPageEditsShapeModeOctavePeriodAndSync() {
        val slot = 3
        val v = view(ViewKind.INSTRUMENT) { instrument = slot }
        session.editAy(slot, "test") { it.copy(envelope = AyEnvelopeSettings()) }
        v.page(Page.ENVELOPE)
        v.goTo(Row.RAW_SHAPE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(8, ins(slot).envelope.shape)
        v.goTo(Row.AUTO)
        v.tap(Action.EDIT)
        assertFalse(ins(slot).envelope.auto)
        v.goTo(Row.RAW_PERIOD)
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(1 + AyInstrumentView.PERIOD_COARSE, ins(slot).envelope.period)
        session.editAy(slot, "test") { it.copy(envelope = it.envelope.copy(period = 65530, octave = 7)) }
        v.hold(Action.EDIT, Action.UP)
        assertEquals(65535, ins(slot).envelope.period)
        v.goTo(Row.OCTAVE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(7, ins(slot).envelope.octave)
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals(5, ins(slot).envelope.octave)
        v.goTo(Row.SYNC)
        v.tap(Action.EDIT)
        assertTrue(ins(slot).envelope.sync)
        v.goTo(Row.RAW_SHAPE)
        v.tap(Action.CLEAR)
        assertEquals(0, ins(slot).envelope.shape)
        assertNull(ins(slot).problem())
    }

    @Test
    fun shapesAreDrawnAsTheChipStepsThem() {
        val down = IntArray(16) { 15 - it }
        val up = IntArray(16) { it }
        fun of(vararg ramps: IntArray) = ramps.reduce { a, b -> a + b }
        assertArrayEquals(of(down, down), AyInstrumentView.shapeLevels(8, 16, 2))
        assertArrayEquals(of(down, IntArray(16)), AyInstrumentView.shapeLevels(9, 16, 2))
        assertArrayEquals(of(down, up, down), AyInstrumentView.shapeLevels(10, 16, 3))
        assertArrayEquals(of(down, IntArray(16) { 15 }), AyInstrumentView.shapeLevels(11, 16, 2))
        assertArrayEquals(of(up, up), AyInstrumentView.shapeLevels(12, 16, 2))
        assertArrayEquals(of(up, IntArray(16) { 15 }), AyInstrumentView.shapeLevels(13, 16, 2))
        assertArrayEquals(of(up, down, up), AyInstrumentView.shapeLevels(14, 16, 3))
        assertArrayEquals(of(up, IntArray(16)), AyInstrumentView.shapeLevels(15, 16, 2))
        // The eight aliases play as 9 and 15.
        for (r13 in 0..7) {
            val as_ = AyInstrumentView.canonicalShape(r13)
            assertEquals(if (r13 < 4) 9 else 15, as_)
            assertArrayEquals(AyInstrumentView.shapeLevels(as_, 16, 3), AyInstrumentView.shapeLevels(r13, 16, 3))
        }
        assertEquals(32, AyInstrumentView.rampSteps(AyVariant.ATARI_ST))
        assertEquals(16, AyInstrumentView.rampSteps(AyVariant.ZX128))
        assertEquals(16, AyInstrumentView.rampSteps(AyVariant.MSX))
        assertEquals(16, AyInstrumentView.rampSteps(AyVariant.CPC))
        // Only the Amstrad CPC's stock socket is stereo: A left, B both, C right.
        assertEquals(listOf("left", "both sides", "right"), (0 until 3).map { AyInstrumentView.side(AyVariant.CPC, it) })
        for (v in listOf(AyVariant.ZX128, AyVariant.MSX, AyVariant.ATARI_ST)) assertNull(AyInstrumentView.side(v, 0))
        assertEquals(31, AyInstrumentView.shapeLevels(8, 32, 1)[0])
    }

    @Test
    fun emptySlotsTakeTheSquareLeadOnTheirFirstEdit() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 50 }
        assertNull(session.song.ayInstrument(50))
        v.tap(Action.EDIT) // on Slot
        assertEquals(defaultAyInstrument(50), session.song.ayInstrument(50))
        assertEquals("Sound 32", ins(50).name)
        v.tap(Action.CLEAR) // on Slot
        assertNull(session.song.ayInstrument(50))
        // The first edit of a setting starts from the Square lead, as one undo step.
        v.goTo(Row.GATES)
        v.hold(Action.EDIT, Action.RIGHT)
        val expected = AyInstrumentView.stepGates(defaultAyInstrument(50).mixer, 1)
        assertEquals(AyEnvelopes.constant(AyEnvelopeKind.MIXER, expected), ins(50).mixer)
        assertEquals("Sound 32", ins(50).name)
        session.undo()
        assertNull(session.song.ayInstrument(50))
        auditions.clear()
        v.tap(Action.AUDITION)
        assertTrue(auditions.isEmpty()) // an empty slot does not sound
    }

    @Test
    fun copyPasteMovesSoundsEnvelopesAndHardwareSettings() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 1 }
        v.state.instField = 0
        v.tap(Action.COPY)
        v.state.instrument = 60
        v.tap(Action.PASTE)
        assertEquals(ins(1), ins(60))
        // A volume envelope pasted into the mixer page: values clamped to 0..3, no release off volume.
        session.editAy(60, "test") { it.copy(volume = NesEnvelope(listOf(15, 2, 1), loop = 0, release = 1)) }
        v.page(Page.VOLUME)
        v.goTo(Row.LENGTH)
        v.tap(Action.COPY)
        v.page(Page.MIXER)
        v.goTo(Row.LENGTH)
        v.tap(Action.PASTE)
        assertEquals(NesEnvelope(listOf(3, 2, 1), loop = 0, release = -1), ins(60).mixer)
        // Hardware envelope settings from the Envelope page into another sound.
        session.editAy(60, "test") { it.copy(envelope = AyEnvelopeSettings(shape = 12, auto = false, octave = 2, period = 0x123, sync = true)) }
        v.page(Page.ENVELOPE)
        v.goTo(Row.RAW_PERIOD)
        v.tap(Action.COPY)
        v.state.instrument = 61
        v.tap(Action.PASTE)
        assertEquals(ins(60).envelope, ins(61).envelope)
        // Cut on the Envelope page clears only the hardware settings.
        v.tap(Action.CUT)
        assertEquals(AyEnvelopeSettings(), ins(61).envelope)
    }

    @Test
    fun helpNamesThePagesAndTheSharedResources() {
        val sections = AyZx128System.help.viewSections(Keymap.DEFAULT)
        assertTrue(sections.map { it.first }.containsAll(listOf("Instruments", "Sound page", "Envelope pages", "Shared noise and envelope")))
        assertEquals(sections, AyInstrumentView.helpSections(Keymap.DEFAULT))
        assertTrue(sections.all { (_, rows) -> rows.isNotEmpty() })
    }

    @Test
    fun laneTagsShowWhoOwnsTheSharedEnvelope() {
        val buzzer = AyInstrument(name = "Buzz", envelope = AyEnvelopeSettings(shape = 8))
        session.setAyInstrument(1, buzzer, "test")
        session.setAyInstrument(2, buzzer.copy(name = "Buzz 2", envelope = AyEnvelopeSettings(shape = 10, octave = 1)), "test")
        val pattern = session.song.orders[0]
        session.setCell(pattern, 0, 0, Cell(note = 45, instrument = 1))
        session.setCell(pattern, 1, 2, Cell(note = 52, instrument = 2))
        val early = AyZx128System.sharedState(session.song, 0, 1)!!
        assertEquals("ENV", early.tags[0])
        assertNull(early.tags[1])
        val late = AyZx128System.sharedState(session.song, 0, 3)!!
        assertEquals("env", late.tags[0])
        assertEquals("ENV", late.tags[1])
        assertTrue(late.lines.isNotEmpty())
    }
}
