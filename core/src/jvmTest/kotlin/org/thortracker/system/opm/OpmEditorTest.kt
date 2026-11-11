package org.thortracker.system.opm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.opm.OpmInstrumentView.Page
import org.thortracker.system.opm.OpmInstrumentView.Row

/**
 * The OPM instrument editor: the curated controls against the operator registers they own, the presets, and the view
 * driven through semantic controller actions (pages, the Expert grid, noise and Listen on FM 8, slots, clipboard).
 */
class OpmEditorTest {
    private val session = EditorSession(OpmSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
    }

    private fun view(setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply { soundPanel("opm").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ViewController.show(page: Page) { state.envelope = OpmInstrumentView.PAGES.indexOf(page) }

    private fun ViewController.goTo(row: Row) { state.instField = OpmInstrumentView.rows(state).indexOf(row) }

    private fun ins(slot: Int) = session.song.opmInstrument(slot)!!

    /** A free slot holding the Lead preset (the default sound), whatever the blank song keeps in its first slots. */
    private val lead = 60.also { session.setOpmInstrument(it, OpmPresets.instrument("Lead"), "test") }

    private val leadSound = OpmPresets.instrument("Lead")

    /** [a] and [b] equal in every operator field except those [owned] lets differ. */
    private fun assertOnly(a: OpmInstrument, b: OpmInstrument, owned: (Int, OpmOperator, OpmOperator) -> OpmOperator) {
        assertEquals(a.copy(operators = a.operators.mapIndexed { i, o -> owned(i, o, b.operators[i]) }), b)
    }

    // ---- curated controls ---------------------------------------------------------------------------------

    @Test
    fun curatedControlsWriteExactlyTheirFieldsAndReadBack() {
        val ins = leadSound
        val c = OpmCurated.controls(ins)
        assertTrue("Lead's ratio is a curated set", c.ratioExact)
        // Every control written with its own value changes nothing.
        assertEquals(ins, OpmCurated.withAlgorithm(ins, c.algorithm))
        assertEquals(ins, OpmCurated.withRatio(ins, c.ratio))
        assertEquals(ins, OpmCurated.withLevel(ins, c.level))
        assertEquals(ins, OpmCurated.withBrightness(ins, c.brightness))
        assertEquals(ins, OpmCurated.withFeedback(ins, c.feedback))
        assertEquals(ins, OpmCurated.withVibrato(ins, c.vibrato))
        assertEquals(ins, OpmCurated.withTremolo(ins, c.tremolo))
        assertEquals(ins, OpmCurated.withNoise(ins, false))

        // Ratios own MUL, DT1 and DT2 only; the DT2 sets read back exactly.
        val bell = OpmCurated.RATIOS.indexOfFirst { it.tag == "bell" }
        val rung = OpmCurated.withRatio(ins, bell)
        assertOnly(ins, rung) { _, o, n -> o.copy(mul = n.mul, dt1 = n.dt1, dt2 = n.dt2) }
        assertEquals(OpmCurated.RATIOS[bell].dt2s, rung.operators.map { it.dt2 })
        assertEquals(bell, OpmCurated.controls(rung).ratio)
        assertTrue(OpmCurated.controls(rung).ratioExact)

        // Level owns the carriers' TL, brightness the modulators'.
        val loud = OpmCurated.withLevel(ins, 100)
        assertEquals(100, OpmCurated.controls(loud).level)
        assertOnly(ins, loud) { i, o, n -> if (ins.isCarrier(i)) o.copy(tl = n.tl) else o }
        val dull = OpmCurated.withBrightness(ins, 50)
        assertEquals(50, OpmCurated.controls(dull).brightness)
        assertOnly(ins, dull) { i, o, n -> if (ins.isCarrier(i)) o else o.copy(tl = n.tl) }

        // Envelope macros.
        val held = OpmCurated.withSustain(ins, 10)
        assertTrue(held.operators.all { it.d1l == 5 })
        assertOnly(ins, held) { _, o, n -> o.copy(d1l = n.d1l) }
        val fading = OpmCurated.withSustainRate(ins, 9)
        assertEquals(9, OpmCurated.controls(fading).sustainRate)
        assertOnly(ins, fading) { _, o, n -> o.copy(d2r = n.d2r) }
        val decay = OpmCurated.withDecay(ins, 20)
        assertOnly(ins, decay) { i, o, n -> if (ins.isCarrier(i)) o.copy(d1r = n.d1r) else o }
        val tone = OpmCurated.withToneDecay(ins, 20)
        assertOnly(ins, tone) { i, o, n -> if (ins.isCarrier(i)) o else o.copy(d1r = n.d1r) }
        assertOnly(ins, OpmCurated.withAttack(ins, 12)) { _, o, n -> o.copy(ar = n.ar) }
        assertOnly(ins, OpmCurated.withRelease(ins, 3)) { _, o, n -> o.copy(rr = n.rr) }
        assertOnly(ins, OpmCurated.withKeyScale(ins, 2)) { _, o, n -> o.copy(ks = n.ks) }

        // Tremolo: AMS with AM on exactly the carriers; vibrato is PMS.
        val trem = OpmCurated.withTremolo(ins, 2)
        assertEquals(2, trem.ams)
        assertEquals((0..3).map { ins.isCarrier(it) }, trem.operators.map { it.am })
        assertTrue(OpmCurated.controls(trem).tremoloExact)
        assertEquals(5, OpmCurated.withVibrato(ins, 5).pms)

        // Algorithms keep loudness and brightness; additive has no brightness.
        val chain = OpmCurated.withAlgorithm(ins, 0)
        assertEquals(c.level, OpmCurated.controls(chain).level)
        assertEquals(c.brightness, OpmCurated.controls(chain).brightness)
        val additive = OpmCurated.withAlgorithm(ins, 7)
        assertEquals(-1, OpmCurated.controls(additive).brightness)
        assertEquals(c.level, OpmCurated.controls(additive).level)
    }

    @Test
    fun handEditsReadAsTheNearestChoiceMarkedApproximate() {
        val ins = leadSound
        val edited = ins.withOperator(0, ins.operators[0].copy(dt2 = 1))
        val c = OpmCurated.controls(edited)
        assertFalse(c.ratioExact)
        assertTrue(c.ratio in OpmCurated.RATIOS.indices)
        // Stepping the approximate ratio writes a curated set exactly.
        val stepped = OpmInstrumentView.step(edited, Row.RATIO, 0, 1, 0)
        assertTrue(OpmCurated.controls(stepped).ratioExact)
        assertEquals((c.ratio + 1) % OpmCurated.RATIOS.size, OpmCurated.controls(stepped).ratio)
        // Envelope hand edits are approximate too.
        val uneven = ins.withOperator(1, ins.operators[1].copy(rr = 2))
        assertFalse(OpmCurated.controls(uneven).envelopeExact)
        val tremolo = OpmCurated.withTremolo(ins, 1)
        assertFalse(OpmCurated.controls(tremolo.withOperator(0, tremolo.operators[0].copy(am = true))).tremoloExact)
    }

    @Test
    fun noiseOwnsTheFlagAndItsFrequency() {
        val on = OpmCurated.withNoise(leadSound, true)
        assertTrue(on.noise)
        assertEquals(OpmCurated.DEFAULT_NOISE_FREQUENCY, on.noiseFrequency)
        assertNull(on.problem())
        assertEquals(31, OpmCurated.withNoiseFrequency(on, 40).noiseFrequency)
        val off = OpmCurated.withNoise(OpmCurated.withNoiseFrequency(on, 5), false)
        assertEquals(leadSound, off)
        assertNull(off.problem())
        // No frequency without noise.
        assertEquals(leadSound, OpmCurated.withNoiseFrequency(leadSound, 9))
    }

    // ---- presets ------------------------------------------------------------------------------------------

    @Test
    fun everyPresetIsLegalAndNoisePresetsAreNoise() {
        assertTrue(OpmPresets.ALL.isNotEmpty())
        assertEquals(OpmPresets.ALL.size, OpmPresets.ALL.map { it.name }.toSet().size)
        for (p in OpmPresets.ALL) {
            assertNull("${p.name}: ${p.instrument.problem()}", p.instrument.problem())
            assertEquals(p.name, p.instrument.name)
            assertEquals(p.name, p.category == OpmPresets.Category.NOISE, p.instrument.noise)
            p.note?.let { assertTrue("${p.name} voiced at $it", it in OpmRules.NOTES) }
            assertEquals(p, OpmPresets.named(p.name))
            assertTrue(OpmCurated.summary(p.instrument).isNotEmpty())
        }
        assertTrue(OpmPresets.ALL.any { it.category == OpmPresets.Category.DRUM && it.instrument.sweep.active })
        assertTrue("a preset uses DT2", OpmPresets.ALL.any { p -> p.instrument.operators.any { it.dt2 != 0 } })
        assertFalse(defaultOpmInstrument(1).noise)
    }

    // ---- the view -------------------------------------------------------------------------------------------

    @Test
    fun soundPageRowsStepTheirFields() {
        val v = view { instrument = lead }
        v.show(Page.SOUND)
        v.goTo(Row.ALGORITHM)
        val alg = ins(lead).algorithm
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((alg + 1) % 8, ins(lead).algorithm)
        assertTrue(auditions.isNotEmpty())
        v.goTo(Row.RATIO)
        val ratio = OpmCurated.controls(ins(lead)).ratio
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((ratio + 1) % OpmCurated.RATIOS.size, OpmCurated.controls(ins(lead)).ratio)
        v.goTo(Row.SUSTAIN_RATE)
        val sr = OpmCurated.controls(ins(lead)).sustainRate
        v.hold(Action.EDIT, Action.RIGHT)
        assertTrue(ins(lead).operators.all { it.d2r == (sr + 1).coerceAtMost(31) })
        v.goTo(Row.LEVEL)
        val level = OpmCurated.controls(ins(lead)).level
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals((level - 2).coerceAtLeast(0), OpmCurated.controls(ins(lead)).level)
        v.goTo(Row.VIBRATO)
        val pms = ins(lead).pms
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((pms + 1).coerceAtMost(7), ins(lead).pms)
        v.goTo(Row.TREMOLO)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(1, ins(lead).ams)
        assertEquals((0..3).map { ins(lead).isCarrier(it) }, ins(lead).operators.map { it.am })
        // Preset replaces the sound.
        val before = ins(lead)
        v.goTo(Row.PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertNotEquals(before, ins(lead))
        assertTrue(OpmPresets.ALL.any { it.instrument == ins(lead) })
    }

    @Test
    fun expertGridStepsOneOperatorFieldAndDt2StaysInRange() {
        val v = view { instrument = lead }
        v.show(Page.EXPERT)
        v.goTo(Row.OP_DT2)
        v.tap(Action.NEXT_CHANNEL)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(2, v.state.envStep)
        val others = ins(lead).operators.filterIndexed { i, _ -> i != 2 }
        assertEquals(0, ins(lead).operators[2].dt2)
        for (expected in listOf(1, 2, 3, 3)) {
            v.hold(Action.EDIT, Action.RIGHT)
            assertEquals(expected, ins(lead).operators[2].dt2)
        }
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(2, ins(lead).operators[2].dt2)
        assertEquals(others, ins(lead).operators.filterIndexed { i, _ -> i != 2 })
        v.goTo(Row.OP_DT1)
        val dt1 = ins(lead).operators[2].dt1
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((dt1 + 1) % 8, ins(lead).operators[2].dt1)
        v.goTo(Row.OP_AM)
        val am = ins(lead).operators[2].am
        v.tap(Action.EDIT)
        assertEquals(!am, ins(lead).operators[2].am)
        v.goTo(Row.OP_TL)
        val tl = ins(lead).operators[2].tl
        v.hold(Action.EDIT, Action.DOWN)
        assertEquals((tl - 8).coerceAtLeast(0), ins(lead).operators[2].tl)
        v.goTo(Row.OP_D1L)
        v.tap(Action.CLEAR)
        assertEquals(OpmOperator().d1l, ins(lead).operators[2].d1l)
        v.goTo(Row.PMS)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(7, ins(lead).pms)
        v.goTo(Row.AMS)
        v.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT, Action.RIGHT)
        assertEquals(3, ins(lead).ams)
        assertNull(ins(lead).problem())
    }

    @Test
    fun noiseMovesListenToFm8AndBack() {
        val v = view { instrument = lead; listenChannel = 2 }
        v.show(Page.DRUM)
        v.goTo(Row.NOISE)
        v.tap(Action.EDIT)
        assertTrue(ins(lead).noise)
        assertEquals(OpmRules.NOISE_CHANNEL, v.state.listenChannel)
        v.goTo(Row.NOISE_FREQ)
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(OpmCurated.DEFAULT_NOISE_FREQUENCY - 1, ins(lead).noiseFrequency)
        // A noise sound listens on FM 8 even when Listen is moved elsewhere.
        v.state.listenChannel = 0
        v.tap(Action.AUDITION)
        assertEquals(OpmRules.NOISE_CHANNEL, auditions.last().first)
        assertEquals(lead, auditions.last().third)
        v.goTo(Row.NOISE)
        v.tap(Action.EDIT)
        assertFalse(ins(lead).noise)
        assertEquals(0, ins(lead).noiseFrequency)
        // Without noise the sound listens where Listen is.
        v.state.listenChannel = 3
        v.tap(Action.AUDITION)
        assertEquals(3, auditions.last().first)
    }

    @Test
    fun drumPresetsIncludeNoiseSoundsThatListenOnFm8() {
        val v = view { instrument = lead; listenChannel = 1 }
        v.show(Page.DRUM)
        v.goTo(Row.DRUM_PRESET)
        val noise = OpmPresets.ALL.first { it.category == OpmPresets.Category.NOISE }
        var guard = 0
        while (session.song.opmInstrument(lead) != noise.instrument && guard++ < OpmPresets.ALL.size) v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(noise.instrument, session.song.opmInstrument(lead))
        assertEquals(OpmRules.NOISE_CHANNEL, v.state.listenChannel)
        assertTrue(OpmInstrumentView.presets(drums = true).none { it.category == OpmPresets.Category.FM })
        // Drum page sweep: a start gives the sweep a speed.
        session.editOpmInstrument(lead, "test") { it.copy(sweep = OpmSweep.NONE) }
        v.goTo(Row.SWEEP_START)
        v.hold(Action.EDIT, Action.UP)
        assertEquals(OpmSweep(12, 0, 16), ins(lead).sweep)
    }

    @Test
    fun emptySlotsAreMadeOnFirstEditAndClearedFromSlot() {
        val v = view { instrument = 50 }
        assertNull(session.song.opmInstrument(50))
        v.tap(Action.EDIT) // on Slot
        assertEquals(defaultOpmInstrument(50), session.song.opmInstrument(50))
        v.tap(Action.CLEAR)
        assertNull(session.song.opmInstrument(50))
        // Any sound setting on an empty slot makes the default sound and edits it.
        v.goTo(Row.FEEDBACK)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals(OpmCurated.controls(defaultOpmInstrument(50)).feedback + 1, ins(50).feedback)
    }

    @Test
    fun copyPasteCutAndDuplicateMoveSounds() {
        val v = view { instrument = lead }
        v.tap(Action.COPY)
        v.state.instrument = 55
        v.tap(Action.PASTE)
        assertEquals(ins(lead), session.song.opmInstrument(55))
        v.tap(Action.DUPLICATE)
        assertEquals(56, v.state.instrument)
        assertEquals(ins(lead).copy(name = ins(lead).name + "+"), session.song.opmInstrument(56))
        v.tap(Action.CUT)
        assertNull(session.song.opmInstrument(56))
        v.state.instrument = 57
        v.tap(Action.PASTE)
        assertEquals(ins(lead).copy(name = ins(lead).name + "+"), session.song.opmInstrument(57))
    }

}
