package org.thortracker.system.vsu

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.Palette
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class VsuSoundFaceTest {
    private fun patch() = VsuInstrument(name = "Untouched RAM", wave = 2, level = 11, envelope = true,
        grow = true, interval = 6, repeat = true, autoLength = true, length = 11, noiseTap = 4,
        freqMod = VsuFreqMod(on = false, modulation = true, repeat = true, slowClock = false, interval = 6, up = true, shift = 4),
        sweep = VsuSweep(7, -4, 3))
    private fun session(ins: VsuInstrument = patch()): EditorSession {
        val base = VsuSystem.blank()
        return EditorSession(base.copy(sound = base.vsu.withInstrument(1, ins)))
    }
    private fun view(s: EditorSession, slot: Int = 1) = ViewController(s,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = slot)).also { it.normalise() }
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun ViewController.edit(vararg actions: Action) { press(Action.EDIT); actions.forEach { tap(it) }; release(Action.EDIT) }

    @Test fun everyLaneHasMusicalControlsWithMinimalFieldOwnership() {
        val old = patch()
        for (lane in 0..5) {
            for (control in 1 until VsuSoundMacros.COUNT) {
                for (choice in 0..VsuSoundMacros.maximum(control, lane)) {
                    val next = VsuSoundMacros.apply(control, old, choice, lane)
                    val restored = when (control) {
                        1 -> if (lane == VsuRules.NOISE_LANE) next.copy(noiseTap = old.noiseTap) else next.copy(wave = old.wave)
                        2 -> next.copy(envelope = old.envelope, grow = old.grow, repeat = old.repeat, interval = old.interval)
                        3 -> next.copy(autoLength = old.autoLength, length = old.length)
                        4 -> next.copy(sweep = old.sweep, freqMod = old.freqMod)
                        else -> next.copy(level = old.level)
                    }
                    assertEquals("lane $lane control $control", old, restored)
                    if (control == 4 && lane != VsuRules.SWEEP_LANE) assertSame(old.freqMod, next.freqMod)
                    assertEquals(choice, VsuSoundMacros.value(control, next, lane))
                    assertSame(next, VsuSoundMacros.apply(control, next, choice, lane))
                    assertNull(next.problem())
                }
            }
            assertSame(old, VsuSoundMacros.apply(0, old, (lane + 1) % 6, lane))
        }
    }

    @Test fun customProgramsAreNeverNormalizedAndTwoMotionEnginesRemainAdvancedOnly() {
        val old = patch()
        assertNull(VsuSoundMacros.value(2, old, 0))
        assertNull(VsuSoundMacros.value(3, old, 0))
        assertNull(VsuSoundMacros.value(4, old, 0))
        assertEquals("Custom", VsuSoundMacros.label(2, old, 0))
        val both = old.copy(freqMod = old.freqMod.copy(on = true))
        assertNotNull(VsuSoundMacros.reason(4, both, VsuRules.SWEEP_LANE))
        for (choice in 0..4) assertSame(both, VsuSoundMacros.apply(4, both, choice, VsuRules.SWEEP_LANE))
        // On other lanes hardware motion is ignored, not erased by software motion edits.
        val otherLane = VsuSoundMacros.apply(4, both, 1, 0)
        assertSame(both.freqMod, otherLane.freqMod)
        // Inactive raw gate and envelope fields survive selecting the currently audible setting.
        val inactive = old.copy(envelope = false, autoLength = false, sweep = old.sweep.copy(speed = 0))
        assertSame(inactive, VsuSoundMacros.apply(2, inactive, 0, 0))
        assertSame(inactive, VsuSoundMacros.apply(3, inactive, 3, 0))
        assertSame(inactive, VsuSoundMacros.apply(4, inactive, 0, 0))
        assertEquals(0, VsuSoundMacros.apply(5, old, -99, 0).level)
        assertEquals(15, VsuSoundMacros.apply(5, old, 99, 0).level)
    }

    @Test fun waveFiveSoftwareMotionDisarmsEvenDisabledUpwardHardwareOverflow() {
        for (on in listOf(false, true)) for (shift in 0..7) {
            val hardware = patch().copy(sweep = VsuSweep.NONE,
                freqMod = VsuFreqMod(on = on, modulation = false, up = true, shift = shift,
                    interval = 3, slowClock = true, repeat = true))
            assertNull(VsuSoundMacros.value(4, hardware, VsuRules.SWEEP_LANE))
            for (choice in 0..2) {
                val next = VsuSoundMacros.apply(4, hardware, choice, VsuRules.SWEEP_LANE)
                // Native VSU checks overflow before the enable bit: modulation must be selected as well as disabled.
                assertFalse(next.freqMod.on)
                assertTrue(next.freqMod.modulation)
                assertEquals(hardware.freqMod.copy(on = false, modulation = true), next.freqMod)
                assertEquals(hardware, next.copy(sweep = hardware.sweep, freqMod = hardware.freqMod))
                assertEquals(choice, VsuSoundMacros.value(4, next, VsuRules.SWEEP_LANE))
                assertSame(next, VsuSoundMacros.apply(4, next, choice, VsuRules.SWEEP_LANE))
                assertSame(hardware.freqMod, VsuSoundMacros.apply(4, hardware, choice, 0).freqMod)
            }
        }
    }

    @Test fun listeningAndDrawingNeverRewriteSharedWaveOrModulationRam() {
        val s = session()
        val v = view(s)
        val before = s.song
        val heard = ArrayList<Int>()
        s.onAudition = { channel, _, _, _ -> heard += channel }
        for (lane in 0..5) {
            VsuSoundFace.set(v, 0, lane)
            v.tap(Action.AUDITION)
            assertEquals(lane, heard.last())
            assertSame(before, s.song)
        }
        val empty = view(s, VsuSystem.SLOTS)
        VsuSoundFace.set(empty, 0, 5)
        empty.tap(Action.EDIT)
        assertNull(s.song.vsuInstrument(VsuSystem.SLOTS))
        assertSame(before, s.song)
        VsuSoundFace.set(v, 0, 4)
        VsuSoundFace.set(v, 4, 4)
        assertSame(before.vsu.waves, s.song.vsu.waves)
        assertSame(before.vsu.modulation, s.song.vsu.modulation)
        assertEquals(patch().freqMod.copy(on = true, modulation = true, repeat = true, slowClock = true, interval = 2), s.song.vsuInstrument(1)!!.freqMod)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun controllerUsesSpatialNavigationAndModeChangesBlockHeldEdits() {
        val s = session()
        val v = view(s)
        val before = s.song
        assertEquals(2, v.state.instField)
        v.tap(Action.RIGHT)
        assertEquals(3, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument)
        assertEquals(3, v.state.instField)
        v.tap(Action.UP)
        assertSame(before, s.song)
        v.state.instField = 7
        v.edit(Action.LEFT, Action.LEFT)
        assertEquals(patch().level - 2, s.song.vsuInstrument(1)!!.level)
        s.undo(); assertSame(before, s.song)
        v.press(Action.EDIT)
        VsuSoundFace.lane(v, 5)
        v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertSame(before, s.song)
        v.press(Action.EDIT)
        VsuSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertSame(before, s.song)
        v.state.instField = 0
        v.tap(Action.UP)
        assertEquals(-1, v.state.instField)
        v.tap(Action.EDIT)
        assertFalse(v.state.soundPanel("vsu").advanced)
        assertSame(before, s.song)
        v.state.instField = 1
        v.state.textPos = VsuInstrumentView.NAME_MAX - 1
        v.edit(Action.DOWN, Action.DOWN)
        assertEquals(VsuInstrumentView.NAME_MAX - 1, v.state.textPos)
    }

    @Test fun explicitMusicalEditsCreateValidSlotsWithOneUndo() {
        val s = session()
        val v = view(s, VsuSystem.SLOTS)
        val before = s.song
        v.state.instField = 7
        v.edit(Action.LEFT, Action.LEFT)
        val created = s.song.vsuInstrument(VsuSystem.SLOTS)!!
        assertNull(created.problem())
        assertEquals(defaultVsuInstrument(VsuSystem.SLOTS).level - 2, created.level)
        assertSame(before.vsu.waves, s.song.vsu.waves)
        assertSame(before.vsu.modulation, s.song.vsu.modulation)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun scopeModelsUseActualEnvelopeGateAndNoiseTiming() {
        val falling = VsuInstrument(level = 3, envelope = true, interval = 0)
        assertEquals(3, VsuSoundMacros.envelopeLevel(falling, 0f))
        assertEquals(2, VsuSoundMacros.envelopeLevel(falling, 16f))
        assertEquals(0, VsuSoundMacros.envelopeLevel(falling, 80f))
        assertEquals(3, VsuSoundMacros.envelopeLevel(falling.copy(repeat = true), 62f))
        val growing = falling.copy(level = 14, grow = true, repeat = true)
        assertEquals(15, VsuSoundMacros.envelopeLevel(growing, 16f))
        assertEquals(14, VsuSoundMacros.envelopeLevel(growing, 31f))
        assertEquals(0, VsuSoundMacros.envelopeLevel(falling.copy(autoLength = true, length = 0), 4f))
        val nativeTaps = intArrayOf(14, 10, 13, 4, 8, 6, 9, 11)
        for ((tap, length) in listOf(32767, 1953, 254, 217, 73, 63, 42, 28).withIndex()) {
            // vsu_chip.c reads only bits 7/tap for feedback and bit 0 for audio.
            // The higher, unused shift-history bits have an initial fill transient.
            val feedbackMask = (1 shl (maxOf(7, nativeTaps[tap]) + 1)) - 1
            var register = 0
            repeat(length) { step ->
                register = VsuSoundMacros.noiseStep(register, tap)
                if (step < length - 1) assertNotEquals(0, register and feedbackMask)
            }
            assertEquals(0, register and feedbackMask)
            val settled = register
            var resetPhase = 0
            repeat(length) {
                resetPhase = VsuSoundMacros.noiseStep(resetPhase, tap)
                register = VsuSoundMacros.noiseStep(register, tap)
                assertEquals(resetPhase and 1, register and 1)
            }
            assertEquals(settled, register)
        }
        assertEquals(1, VsuSoundMacros.noiseStep(0, 0))
    }

    private class TextPrompt : MenuContext {
        var initial = ""
        var maximum = 0
        lateinit var accepted: (String) -> Unit
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) {
            this.initial = initial; maximum = maxLength; this.accepted = accepted
        }
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { throw AssertionError("Unexpected menu") }
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) { throw AssertionError("Unexpected picker") }
        override fun status(message: String) = Unit
        override fun error(message: String) { throw AssertionError(message) }
        override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("Unexpected ROM")
    }

    @Test fun touchNameOpensBoundedNativeTextEntryAndUndoRestoresOnlyItsPinnedInstrument() {
        val s = session()
        val panel = Panel(s, 360, 270)
        val prompt = TextPrompt()
        s.onMenuRequest = { it(prompt) }
        val before = s.song
        panel.tap(panel.painter.nameTarget())
        assertEquals(patch().name, prompt.initial)
        assertEquals(VsuInstrumentView.NAME_MAX, prompt.maximum)
        assertSame(before, s.song)
        prompt.accepted("N".repeat(VsuInstrumentView.NAME_MAX + 8))
        assertEquals(patch().copy(name = "N".repeat(VsuInstrumentView.NAME_MAX)), s.song.vsuInstrument(1))
        assertSame(before.vsu.waves, s.song.vsu.waves)
        assertSame(before.vsu.modulation, s.song.vsu.modulation)
        s.undo()
        assertSame(before, s.song)
        panel.state.instrument = VsuSystem.SLOTS
        panel.draw()
        panel.tap(panel.painter.nameTarget())
        assertEquals("", prompt.initial)
        prompt.accepted("New voice")
        assertEquals(defaultVsuInstrument(VsuSystem.SLOTS).copy(name = "New voice"), s.song.vsuInstrument(VsuSystem.SLOTS))
    }

    @Test fun delayedNameResponsesCannotRetargetOrOverwriteUndoAndModeChanges() {
        val s = session()
        val panel = Panel(s)
        val prompt = TextPrompt()
        s.onMenuRequest = { it(prompt) }
        val before = s.song
        for (change in 0..3) {
            panel.state.instrument = 1
            VsuSoundFace.selectMode(panel.pane.controller, false)
            panel.draw()
            panel.tap(panel.painter.nameTarget())
            val reply = prompt.accepted
            when (change) {
                0 -> VsuSoundFace.moveSlot(panel.pane.controller, 1)
                1 -> { VsuSoundFace.selectMode(panel.pane.controller, true); VsuSoundFace.selectMode(panel.pane.controller, false) }
                2 -> { s.editVsuInstrument(1, "Other edit") { it.copy(level = 1) }; s.undo() }
                else -> panel.tap(panel.painter.nameTarget())
            }
            reply("Stale")
            assertSame(before, s.song)
        }
    }

    private class Canvas : Surface {
        val bank = ArrayList<String>()
        var bankRight = 0f
        private fun keep(x: Float, y: Float, text: String) { if (x < bankRight && y >= 48f) bank += text }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { keep(l, t, "fill $l $t $r $b $color") }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { keep(l, t, "round $l $t $r $b $radius $color") }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) { keep(l, t, "stroke $l $t $r $b $color $width $radius") }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) { keep(minOf(x0, x1), minOf(y0, y1), "line $x0 $y0 $x1 $y1 $color $width") }
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            keep(x, top, "text $s $x $top $h $color $face $bold $size $align")
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }
    private class Panel(val session: EditorSession, val width: Int = 960, val height: Int = 720) {
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 7)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = VsuSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise()
            surface.bank.clear()
            painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
        fun start() { draw(); painter.down(painter.valueX(5, 0f), painter.controlRect(5).centerY()) }
    }

    @Test fun touchUsesPinnedGroupedUndoAndCancelsAfterLaneSlotModeAndUndo() {
        val s = session()
        val panel = Panel(s)
        val before = s.song
        panel.start()
        panel.draw()
        panel.painter.move(panel.painter.valueX(5, 1f), panel.painter.controlRect(5).centerY())
        panel.painter.up(0f, 0f)
        assertEquals(patch().copy(level = 15), s.song.vsuInstrument(1))
        s.undo(); assertSame(before, s.song)
        for (cancel in 0..3) {
            VsuSoundFace.selectMode(panel.pane.controller, false)
            panel.state.instrument = 1; panel.state.instField = 7; panel.state.listenChannel = 0
            panel.start()
            when (cancel) {
                0 -> s.undo()
                1 -> VsuSoundFace.selectMode(panel.pane.controller, true)
                2 -> VsuSoundFace.moveSlot(panel.pane.controller, 1)
                else -> VsuSoundFace.lane(panel.pane.controller, 5)
            }
            val after = s.song
            panel.painter.move(999f, 0f)
            assertSame(after, s.song)
            panel.painter.up(0f, 0f)
            if (cancel != 0) s.undo()
            assertSame(before, s.song)
        }
    }

    @Test fun compactTouchPagesEveryLaneAndControlAtPhysicalSizeWithoutEditing() {
        val s = session()
        val before = s.song
        val panel = Panel(s, 360, 270)
        panel.pane.palette = Palette.CONTRAST
        for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) {
            panel.state.zoom = zoom
            for (lane in 0..5) {
                VsuSoundFace.lane(panel.pane.controller, lane)
                panel.state.instField = 2; panel.draw()
                repeat(VsuSoundMacros.COUNT) { i ->
                    val r = panel.painter.controlRect(i)
                    assertTrue(r.width() >= 48f)
                    assertTrue(r.height() >= 48f)
                    assertTrue(r.bottom <= panel.area.bottom)
                    assertEquals(i + 2, panel.state.instField)
                    panel.tap(panel.painter.navigationRect(true)); panel.draw()
                }
            }
        }
        panel.tap(panel.painter.slotNavigationRect(true)); panel.draw()
        assertEquals(2, panel.state.instrument)
        panel.tap(panel.painter.modeRect(true)); panel.draw()
        assertTrue(panel.state.soundPanel("vsu").advanced)
        panel.tap(panel.painter.modeRect(false)); panel.draw()
        assertFalse(panel.state.soundPanel("vsu").advanced)
        panel.tap(panel.painter.nameTarget())
        assertEquals(1, panel.state.instField)
        panel.pane.controller.edit(Action.RIGHT)
        assertNotEquals(before, s.song)
        s.undo()
        assertSame(before, s.song)
    }

    @Test fun advancedAndSoundUseExactlyTheSameSidebar() {
        val s = session()
        val panel = Panel(s)
        panel.surface.bankRight = minOf(panel.area.width() * .26f, panel.pane.ink.unit * 12f) - .01f
        panel.draw()
        val soundBank = panel.surface.bank.toList()
        VsuSoundFace.selectMode(panel.pane.controller, true)
        panel.state.instField = 1
        panel.draw()
        assertEquals(soundBank, panel.surface.bank)
    }
}
