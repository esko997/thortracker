package org.thortracker.system.vsu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.vsu.VsuInstrumentView.Page
import org.thortracker.system.vsu.VsuInstrumentView.Row

/** The Virtual Boy instrument and wave editors driven through semantic controller actions. */
class VsuEditorTest {
    private val session = EditorSession(VsuSystem.blank())
    private val auditions = ArrayList<Triple<Int, Int, Int>>()
    private var releases = 0

    init {
        session.onAudition = { ch, note, ins, _ -> auditions += Triple(ch, note, ins) }
        session.onReleaseAudition = { releases++ }
    }

    private fun view(kind: ViewKind, setup: ViewState.() -> Unit = {}) =
        ViewController(session, ViewState(kind = kind).apply { soundPanel("vsu").advanced = true }.apply(setup))

    private fun ViewController.tap(a: Action) { press(a); release(a) }

    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a)
        for (m in moves) { press(m); release(m) }
        release(a)
    }

    private fun ins(slot: Int) = session.song.vsuInstrument(slot)

    private fun ViewController.goTo(row: Row) { state.instField = VsuInstrumentView.rows(state).indexOf(row) }

    @Test
    fun soundFieldsEditUndoAndSound() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 1 }
        val before = ins(1)!!
        v.goTo(Row.LEVEL)
        v.hold(Action.EDIT, Action.LEFT, Action.LEFT)
        assertEquals(before.level - 2, ins(1)!!.level)
        // Holding Edit sounded the sound on the Listen lane, and releasing it stopped.
        assertTrue(auditions.isNotEmpty() && auditions.all { it.first == 0 && it.third == 1 })
        assertTrue(releases > 0)
        // One hold is one undo step.
        session.undo()
        assertEquals(before, ins(1))
        // Switches flip on a tap; a length switches auto length on.
        v.goTo(Row.ENVELOPE)
        v.tap(Action.EDIT)
        assertEquals(!before.envelope, ins(1)!!.envelope)
        v.goTo(Row.LENGTH)
        v.hold(Action.EDIT, Action.RIGHT)
        assertTrue(ins(1)!!.autoLength)
        assertEquals(before.length + 1, ins(1)!!.length)
        v.goTo(Row.WAVE)
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((before.wave + 1) % 5, ins(1)!!.wave)
        // Clear restores the field's default.
        v.tap(Action.CLEAR)
        assertEquals(defaultVsuInstrument(1).wave, ins(1)!!.wave)
    }

    @Test
    fun presetsReplaceTheSoundAndMoveListenToTheirLane() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 40 }
        assertNull(ins(40))
        v.goTo(Row.PRESET)
        // From no preset, stepping back lands on the last preset: a noise drum, heard on Noise.
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(VsuPresets.ALL.last().instrument, ins(40))
        assertEquals(VsuRules.NOISE_LANE, v.state.listenChannel)
        assertEquals(VsuRules.NOISE_LANE, auditions.last().first)
        assertEquals(40, auditions.last().third)
        // The Sweep/Mod page's preset row steps through Wave 5 sounds only and listens on Wave 5.
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(Page.WAVE5, VsuInstrumentView.page(v.state))
        v.goTo(Row.FM_PRESET)
        v.hold(Action.EDIT, Action.RIGHT)
        assertTrue(ins(40)!!.freqMod.on)
        assertEquals(VsuRules.SWEEP_LANE, v.state.listenChannel)
        // Wave 5 fields step on that page.
        v.goTo(Row.FM_SHIFT)
        val shift = ins(40)!!.freqMod.shift
        v.hold(Action.EDIT, Action.RIGHT)
        assertEquals((shift + 1).coerceAtMost(7), ins(40)!!.freqMod.shift)
        v.goTo(Row.FM_DIRECTION)
        val up = ins(40)!!.freqMod.up
        v.tap(Action.EDIT)
        assertEquals(!up, ins(40)!!.freqMod.up)
        // The Pitch sweep page: a start gives the sweep a speed.
        v.tap(Action.NEXT_CHANNEL)
        v.goTo(Row.SWEEP_START)
        session.editVsuInstrument(40, "test") { it.copy(sweep = VsuSweep.NONE) }
        v.hold(Action.EDIT, Action.UP)
        assertEquals(VsuSweep(12, 0, 16), ins(40)!!.sweep)
    }

    @Test
    fun emptySlotsAreMadeOnFirstEditAndListenStaysInTheLanesRange() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 40 }
        v.tap(Action.EDIT) // on Slot
        assertEquals("Sound 28", ins(40)!!.name)
        v.tap(Action.CLEAR)
        assertNull(ins(40))
        // A low listening octave is raised into the wave lanes' range (D#2); Noise plays it as it is.
        val w = view(ViewKind.INSTRUMENT) { instrument = 1; octave = 1; listenChannel = 0 }
        w.tap(Action.AUDITION)
        assertEquals(Triple(0, 27, 1), auditions.last())
        val n = view(ViewKind.INSTRUMENT) { instrument = 13; octave = 1; listenChannel = 5 }
        n.tap(Action.AUDITION)
        assertEquals(Triple(5, 12, 13), auditions.last())
    }

    @Test
    fun copyPasteAndDuplicateSounds() {
        val v = view(ViewKind.INSTRUMENT) { instrument = 2 }
        v.tap(Action.COPY)
        v.state.instrument = 30
        v.tap(Action.PASTE)
        assertEquals(ins(2), ins(30))
        v.tap(Action.DUPLICATE)
        assertEquals(31, v.state.instrument)
        assertEquals(ins(2)!!.copy(name = ins(2)!!.name + "+"), ins(31))
    }

    @Test
    fun waveViewDrawsWithTheControllerAndUndoesAsOneStroke() {
        val v = view(ViewKind.WAVE) { wave = 0; waveRow = 1; waveCol = 0; waveLevel = 40 }
        val before = session.song.vsu.waves[0]
        v.hold(Action.EDIT, Action.RIGHT, Action.UP)
        assertEquals(40, session.song.vsu.waves[0][0])
        assertEquals(41, session.song.vsu.waves[0][1])
        // Other waves are untouched; one undo restores the stroke.
        assertEquals(VsuSystem.blank().vsu.waves.drop(1), session.song.vsu.waves.drop(1))
        session.undo()
        assertEquals(before, session.song.vsu.waves[0])
        // Clear sets the sample under the pen to the centre.
        v.tap(Action.CLEAR)
        assertEquals(32, session.song.vsu.waves[0][1])
    }

    @Test
    fun waveToolsPresetsAndListen() {
        val v = view(ViewKind.WAVE) { wave = 0; waveRow = 0; waveCol = VsuWaveView.Tool.PRESET.ordinal }
        // Wave 1 is the sine: the next preset is the triangle.
        v.tap(Action.EDIT)
        assertEquals(VsuPresets.WAVES[1].samples, session.song.vsu.waves[0])
        v.hold(Action.EDIT, Action.LEFT)
        assertEquals(VsuPresets.WAVES[0].samples, session.song.vsu.waves[0])
        // Invert.
        v.state.waveCol = VsuWaveView.Tool.INVERT.ordinal
        v.tap(Action.EDIT)
        assertEquals(VsuPresets.WAVES[0].samples.map { 63 - it }, session.song.vsu.waves[0])
        // Use in sound: the selected sound (01) now plays wave 1; Listen sounds it on Wave 1.
        v.state.waveCol = VsuWaveView.Tool.USE.ordinal
        v.tap(Action.EDIT)
        assertEquals(0, ins(1)!!.wave)
        v.tap(Action.AUDITION)
        assertEquals(0, auditions.last().first)
        assertEquals(1, auditions.last().third)
    }

    @Test
    fun theModulationTableIsTheSixthPage() {
        val v = view(ViewKind.WAVE) { wave = 4; waveRow = 1; waveCol = 3; waveLevel = 8 }
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(VsuWaves.MOD_PAGE, v.state.wave)
        // The pen reaches negative values on the table; Edit draws there.
        v.state.waveLevel = -100
        v.hold(Action.EDIT, Action.DOWN)
        // The press drew -100 at the pen, the DOWN moved it to -101 and drew there: the same sample.
        assertEquals(-101, session.song.vsu.modulation[3])
        assertEquals(-101, v.state.waveLevel)
        // A clear sets the value to 0.
        v.tap(Action.CLEAR)
        assertEquals(0, session.song.vsu.modulation[3])
    }

    @Test
    fun modulationToolsAndUseInSound() {
        val v = view(ViewKind.WAVE) { wave = VsuWaves.MOD_PAGE; waveRow = 0; waveCol = VsuWaveView.Tool.INVERT.ordinal }
        val before = session.song.vsu.modulation
        v.tap(Action.EDIT)
        assertEquals(before.map { (-it).coerceIn(-128, 127) }, session.song.vsu.modulation)
        v.state.waveCol = VsuWaveView.Tool.USE.ordinal
        v.tap(Action.EDIT)
        val fm = ins(1)!!.freqMod
        assertTrue(fm.on && fm.modulation && fm.interval > 0)
        // Listen on the table's page plays a modulating sound on Wave 5.
        v.tap(Action.AUDITION)
        assertEquals(VsuRules.SWEEP_LANE, auditions.last().first)
        // A wave copied onto the table's page is refused (different ranges).
        session.copyVsuWavePage(0)
        assertTrue(!session.pasteVsuWavePage(VsuWaves.MOD_PAGE))
    }
}
