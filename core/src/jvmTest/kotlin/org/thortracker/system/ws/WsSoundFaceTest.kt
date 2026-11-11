package org.thortracker.system.ws

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

class WsSoundFaceTest {
    private fun patch() = WsInstrument(name = "Keep fields", wave = 1, tap = 6, sample = 0,
        root = 51, rate = 2, loopStart = 2, backwards = true, sweep = -9, sweepTime = 3,
        lfsrReset = true, volume = WsEnvelope(listOf(13, 8, 5, 0), loop = 1, release = 3),
        arpeggio = WsEnvelope(listOf(0, 4, 7), loop = 0), pitchRelative = true)
    private fun session(color: Boolean = true, instrument: WsInstrument = patch()): EditorSession {
        val base = if (color) WscSystem.blank() else WsSystem.blank()
        val sound = base.ws.copy(samples = listOf(WsSample("One", listOf(128, 255, 0, 128)), WsSample("Short", listOf(128))))
            .withInstrument(1, instrument)
        return EditorSession(base.copy(sound = sound))
    }
    private fun view(s: EditorSession, slot: Int = 1) = ViewController(s,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = slot)).also { it.normalise() }
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun ViewController.edit(vararg actions: Action) { press(Action.EDIT); actions.forEach { tap(it) }; release(Action.EDIT) }

    @Test fun voiceChoicesPreserveAllInactiveFieldsAndRespectTheModel() {
        for (color in listOf(false, true)) {
            val sound = session(color).song.ws
            val old = patch()
            assertEquals(if (color) 3 else 2, WsSoundMacros.maximum(0, old, sound))
            for (choice in 0..WsSoundMacros.maximum(0, old, sound)) {
                val next = WsSoundMacros.apply(0, old, choice, sound, 0)
                assertEquals(old, next.copy(kind = old.kind, feed = old.feed))
                assertEquals(choice, WsSoundMacros.value(0, next, 0))
                assertSame(next, WsSoundMacros.apply(0, next, choice, sound, 0))
                assertNull(next.problem())
            }
            assertSame(old, WsSoundMacros.apply(0, old, 2, sound.copy(samples = emptyList()), 0))
        }
    }

    @Test fun sourceContourAndBendHaveExactOwnershipAndCustomReadback() {
        val sound = session().song.ws
        val old = patch()
        assertNull(WsSoundMacros.value(2, old, 2))
        assertNull(WsSoundMacros.value(3, old, 2))
        for (shape in 0..3) {
            val next = WsSoundMacros.apply(2, old, shape, sound, 2)
            assertEquals(old, next.copy(volume = old.volume))
            assertEquals(13, next.volume.values.maxOrNull())
            assertEquals(shape, WsSoundMacros.value(2, next, 2))
            assertSame(next, WsSoundMacros.apply(2, next, shape, sound, 2))
        }
        val wave = WsSoundMacros.apply(1, old, 0, sound, 2)
        assertEquals(old.copy(wave = 0), wave)
        val sequence = old.copy(waves = WsEnvelope(listOf(0, 1), loop = 0))
        assertNull(WsSoundMacros.value(1, sequence, 2))
        assertNotNull(WsSoundMacros.reason(1, sequence, sound, 2))
        assertSame(sequence, WsSoundMacros.apply(1, sequence, 0, sound, 2))
        assertSame(old, WsSoundMacros.apply(3, old, 1, sound, 0))
        val programmed = old.copy(pitch = WsEnvelope(listOf(0, 12)))
        assertSame(programmed, WsSoundMacros.apply(3, programmed, 1, sound, 2))
        val bent = WsSoundMacros.apply(3, old, 4, sound, 2)
        assertEquals(old.copy(sweep = 4, sweepTime = 7), bent)
        val still = WsSoundMacros.apply(3, old, 0, sound, 2)
        assertEquals(old.copy(sweep = 0), still)
        val noise = old.copy(kind = WsKind.NOISE)
        assertEquals(noise.copy(tap = 7), WsSoundMacros.apply(1, noise, 99, sound, 3))
        assertEquals(noise.copy(lfsrReset = false), WsSoundMacros.apply(3, noise, 0, sound, 3))
        val taps = noise.copy(taps = WsEnvelope(listOf(1, 2)))
        assertNull(WsSoundMacros.value(1, taps, 3))
        assertSame(taps, WsSoundMacros.apply(1, taps, 0, sound, 3))
    }

    @Test fun sampleSelectionTuningAndPlaybackPreserveBytesAndOtherVoicePrograms() {
        val sound = session().song.ws
        for (feed in WsFeed.entries) {
            val old = patch().copy(kind = WsKind.SAMPLE, feed = feed)
            val shorter = WsSoundMacros.apply(1, old, 1, sound, 1)
            assertEquals(old.copy(sample = 1, loopStart = 0), shorter)
            val tune = WsSoundMacros.apply(3, old, 999, sound, 1)
            assertEquals(if (old.isDma) old.copy(rate = 3) else old.copy(root = WsRules.NOTES.last), tune)
            val reverseLoop = WsSoundMacros.apply(4, old, 3, sound, 1)
            assertEquals(old.copy(loop = true), reverseLoop)
            assertNull(shorter.problem())
        }
    }

    @Test fun listeningNeverCreatesOrChangesAPatchAndSamplesCanReachHyperVoice() {
        val s = session()
        val v = view(s, WsRules.SLOTS)
        val before = s.song
        v.state.instField = 6
        v.tap(Action.EDIT)
        WsSoundFace.set(v, 4, 2)
        assertEquals(2, v.state.listenChannel)
        assertSame(before, s.song)
        assertNull(s.song.wsInstrument(WsRules.SLOTS))
        val sample = patch().copy(kind = WsKind.SAMPLE)
        s.setWsInstrument(1, sample, "Sample")
        val sampleView = view(s)
        val saved = s.song
        WsSoundFace.cycleLane(sampleView, 1)
        assertEquals(WsRules.HYPER_VOICE, sampleView.state.listenChannel)
        WsSoundFace.cycleLane(sampleView, 1)
        assertEquals(WsRules.VOICE_LANE, sampleView.state.listenChannel)
        assertSame(saved, s.song)
        assertSame(saved.ws.waves, s.song.ws.waves)
        assertSame(saved.ws.samples, s.song.ws.samples)
    }

    @Test fun heldVoiceChangeRestartsTheAcceptedKindOnItsNewLaneBeforeBlockingFurtherEdits() {
        val s = session()
        val v = view(s)
        val heard = ArrayList<Pair<Int, WsKind>>()
        s.onAudition = { channel, _, slot, _ -> heard += channel to s.song.wsInstrument(slot)!!.kind }
        val before = s.song
        v.press(Action.EDIT)
        assertEquals(0 to WsKind.TONE, heard.last())
        val count = heard.size
        v.tap(Action.RIGHT)
        assertEquals(count + 1, heard.size)
        assertEquals(WsRules.NOISE_LANE to WsKind.NOISE, heard.last())
        assertTrue(v.state.soundPanel("ws").editBlocked)
        val noise = s.song
        v.tap(Action.RIGHT)
        assertSame(noise, s.song)
        assertEquals(count + 1, heard.size)
        v.release(Action.EDIT)
        s.undo()
        assertSame(before, s.song)
    }

    @Test fun navigationModeSwitchAndGroupedUndoPreserveTheExactPatch() {
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
        v.state.instField = 4
        v.edit(Action.RIGHT, Action.RIGHT)
        assertNotEquals(before, s.song)
        s.undo()
        assertSame(before, s.song)
        v.press(Action.EDIT)
        WsSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertSame(before, s.song)
        v.state.instField = 0
        v.tap(Action.UP)
        assertEquals(-1, v.state.instField)
        v.tap(Action.EDIT)
        assertFalse(v.state.soundPanel("ws").advanced)
        assertSame(before, s.song)
        v.state.instField = 1
        v.state.textPos = WsInstrumentView.NAME_MAX - 1
        v.edit(Action.DOWN, Action.DOWN)
        assertEquals(WsInstrumentView.NAME_MAX - 1, v.state.textPos)
    }

    @Test fun soundClipboardKeepsMusicalFocusAndDoesNotPasteIntoHiddenEnvelopePages() {
        val s = session()
        val v = view(s, WsRules.SLOTS)
        v.state.instField = 6
        s.copyWsInstrument(1)
        v.tap(Action.PASTE)
        assertEquals(patch(), s.song.wsInstrument(WsRules.SLOTS))
        assertEquals(6, v.state.instField)
        s.undo()
        val before = s.song
        s.copyWsEnvelope(1, WsEnvelopeKind.VOLUME)
        v.tap(Action.PASTE)
        assertSame(before, s.song)
    }

    @Test fun explicitEditsCreateValidSlotsButUnavailableSourceDoesNot() {
        val s = session()
        val v = view(s, WsRules.SLOTS)
        WsSoundFace.set(v, 2, 1)
        assertNotNull(s.song.wsInstrument(WsRules.SLOTS))
        assertNull(WsValidator.instrumentProblem(s.song.ws, WsRules.SLOTS))
        s.undo()
        assertNull(s.song.wsInstrument(WsRules.SLOTS))
        val empty = EditorSession(WscSystem.blank())
        val ev = view(empty, WsRules.SLOTS)
        val before = empty.song
        WsSoundFace.set(ev, 0, 3)
        assertSame(before, empty.song)
    }

    @Test fun noisePreviewUsesTheNativeResetSequenceAndTapPeriods() {
        val nativeTaps = intArrayOf(14, 10, 13, 4, 8, 6, 9, 11)
        for (tap in 0..7) {
            // ws_apu.c feeds back bits 7/tap and emits the new low bit. Bits above both taps
            // contain shift history, not feedback state, and need not return to reset's zero.
            val feedbackMask = (1 shl (maxOf(7, nativeTaps[tap]) + 1)) - 1
            val length = WsRules.TAP_LENGTHS[tap]
            var register = 0
            repeat(length) { step ->
                register = WsSoundMacros.noiseStep(register, tap)
                if (step < length - 1) assertNotEquals(0, register and feedbackMask)
            }
            assertEquals(0, register and feedbackMask)
            val settled = register
            var resetPhase = 0
            repeat(length) {
                resetPhase = WsSoundMacros.noiseStep(resetPhase, tap)
                register = WsSoundMacros.noiseStep(register, tap)
                assertEquals(resetPhase and 1, register and 1)
            }
            assertEquals(settled, register)
        }
        assertEquals(1, WsSoundMacros.noiseStep(0, 0))
        assertEquals(3, WsSoundMacros.noiseStep(1, 0))
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
        assertEquals(WsInstrumentView.NAME_MAX, prompt.maximum)
        assertSame(before, s.song)
        prompt.accepted("N".repeat(WsInstrumentView.NAME_MAX + 8))
        assertEquals(patch().copy(name = "N".repeat(WsInstrumentView.NAME_MAX)), s.song.wsInstrument(1))
        assertSame(before.ws.waves, s.song.ws.waves)
        assertSame(before.ws.samples, s.song.ws.samples)
        s.undo()
        assertSame(before, s.song)
        panel.state.instrument = WsRules.SLOTS
        panel.draw()
        panel.tap(panel.painter.nameTarget())
        assertEquals("", prompt.initial)
        prompt.accepted("New voice")
        assertEquals(defaultWsInstrument(WsRules.SLOTS).copy(name = "New voice"), s.song.wsInstrument(WsRules.SLOTS))
    }

    @Test fun delayedNameResponsesCannotRetargetOrOverwriteUndoAndModeChanges() {
        val s = session()
        val panel = Panel(s)
        val prompt = TextPrompt()
        s.onMenuRequest = { it(prompt) }
        val before = s.song
        for (change in 0..3) {
            panel.state.instrument = 1
            WsSoundFace.selectMode(panel.pane.controller, false)
            panel.draw()
            panel.tap(panel.painter.nameTarget())
            val reply = prompt.accepted
            when (change) {
                0 -> WsSoundFace.moveSlot(panel.pane.controller, 1)
                1 -> { WsSoundFace.selectMode(panel.pane.controller, true); WsSoundFace.selectMode(panel.pane.controller, false) }
                2 -> { s.editWsInstrument(1, "Other edit") { it.copy(tap = 1) }; s.undo() }
                else -> panel.tap(panel.painter.nameTarget()) // A newer request supersedes the old callback.
            }
            reply("Stale")
            assertSame(before, s.song)
        }
    }

    private class Canvas : Surface {
        val labels = ArrayList<String>()
        val bank = ArrayList<String>()
        var bankRight = 0f
        var bankTop = 48f
        private fun keep(x: Float, y: Float, text: String) { if (x < bankRight && y >= bankTop) bank += text }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { keep(l, t, "fill $l $t $r $b $color") }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { keep(l, t, "round $l $t $r $b $radius $color") }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) { keep(l, t, "stroke $l $t $r $b $color $width $radius") }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) { keep(minOf(x0, x1), minOf(y0, y1), "line $x0 $y0 $x1 $y1 $color $width") }
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            labels += s
            keep(x, top, "text $s $x $top $h $color $face $bold $size $align")
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }
    private class Panel(val session: EditorSession, val width: Int = 960, val height: Int = 720) {
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 4)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = WsSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise()
            surface.labels.clear(); surface.bank.clear()
            painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
        fun start() { draw(); painter.down(painter.valueX(2, 1f), painter.controlRect(2).centerY()) }
    }

    @Test fun pinnedDragsUseOneUndoAndStopOnModeSlotLaneOrUndo() {
        val s = session()
        val panel = Panel(s)
        val before = s.song
        panel.start()
        panel.draw()
        panel.painter.move(panel.painter.valueX(2, .34f), panel.painter.controlRect(2).centerY())
        panel.painter.up(0f, 0f)
        assertEquals(WsSoundMacros.apply(2, patch(), 1, before.ws, 0), s.song.wsInstrument(1))
        s.undo(); assertSame(before, s.song)
        for (cancel in 0..3) {
            WsSoundFace.selectMode(panel.pane.controller, false)
            panel.state.instrument = 1; panel.state.instField = 4; panel.state.listenChannel = 0
            panel.start()
            when (cancel) {
                0 -> s.undo()
                1 -> WsSoundFace.selectMode(panel.pane.controller, true)
                2 -> WsSoundFace.moveSlot(panel.pane.controller, 1)
                else -> WsSoundFace.lane(panel.pane.controller, 2)
            }
            val after = s.song
            panel.painter.move(999f, 0f)
            assertSame(after, s.song)
            panel.painter.up(0f, 0f)
            if (cancel != 0) s.undo()
            assertSame(before, s.song)
        }
    }

    @Test fun landscapePocketStripKeepsAllFiveControlsVisibleAndTouchSized() {
        val s = session()
        val before = s.song
        val panel = Panel(s, 640, 384)
        assertFalse(panel.painter.compact)
        for (i in 0 until WsSoundMacros.COUNT) {
            val r = panel.painter.controlRect(i)
            assertTrue(r.width() >= 48f && r.height() >= 48f)
            assertTrue(r.bottom <= panel.area.bottom)
            assertEquals(panel.painter.controlRect(0).top, r.top, .01f)
            if (i > 0) assertTrue(r.left >= panel.painter.controlRect(i - 1).right)
        }
        assertTrue(panel.painter.listenRect().width() >= 48f)
        assertSame(before, s.song)
    }

    @Test fun compactTouchCanReachEveryControlModeSlotAndSampleListeningLane() {
        val s = session(instrument = patch().copy(kind = WsKind.SAMPLE))
        val before = s.song
        val panel = Panel(s, 360, 270)
        panel.pane.palette = Palette.CONTRAST
        for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) {
            panel.state.zoom = zoom; panel.state.instField = 2; panel.draw()
            repeat(WsSoundMacros.COUNT) { i ->
                val r = panel.painter.controlRect(i)
                assertTrue(r.width() >= 48f)
                assertTrue(r.height() >= 48f)
                assertTrue(r.bottom <= panel.area.bottom)
                assertEquals(i + 2, panel.state.instField)
                panel.tap(panel.painter.navigationRect(true)); panel.draw()
            }
        }
        panel.tap(panel.painter.listenRect())
        assertEquals(WsRules.HYPER_VOICE, panel.state.listenChannel)
        panel.tap(panel.painter.slotNavigationRect(true)); panel.draw()
        assertEquals(2, panel.state.instrument)
        panel.tap(panel.painter.modeRect(true)); panel.draw()
        assertTrue(panel.state.soundPanel("ws").advanced)
        panel.tap(panel.painter.modeRect(false)); panel.draw()
        assertFalse(panel.state.soundPanel("ws").advanced)
        panel.tap(panel.painter.nameTarget())
        assertEquals(1, panel.state.instField)
        panel.pane.controller.edit(Action.RIGHT)
        assertNotEquals(before, s.song)
        s.undo()
        assertSame(before, s.song)
    }

    @Test fun bothFacesDrawTheIdenticalAdvancedSidebar() {
        val s = session()
        val panel = Panel(s)
        panel.surface.bankRight = minOf(panel.area.width() * .26f, panel.pane.ink.unit * 12f) - .01f
        panel.draw()
        val soundBank = panel.surface.bank.toList()
        WsSoundFace.selectMode(panel.pane.controller, true)
        panel.state.instField = 1
        panel.draw()
        assertEquals(soundBank, panel.surface.bank)
    }
}
