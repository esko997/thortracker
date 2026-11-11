package org.thortracker.system.pokey

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.PokeyLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.system.panel.EnvelopeContour
import org.thortracker.ui.*

class PokeySoundFaceTest {
    private fun patch(kind: PokeyKind = PokeyKind.TONE) = PokeyInstrument(
        name = "Keep me", kind = kind, volume = NesEnvelope(listOf(12, 5, 9), loop = 1),
        arpeggio = NesEnvelope(listOf(0, 7), loop = 0), pitch = NesEnvelope(listOf(2, -1)),
        pitchRelative = true, distortion = NesEnvelope(listOf(7)), filterOffset = 17,
        partnerDistortion = 2, partnerVolume = 3, sample = 0, root = 45, rate = 7990, loop = 6,
    )
    private fun session(ins: PokeyInstrument = patch()) = EditorSession(PokeySystem.blank().let {
        it.copy(sound = it.pokey.copy(samples = listOf(PokeySample("Long", List(12) { it }), PokeySample("Short", listOf(1, 9, 3))))
            .withInstrument(1, ins).withInstrument(PokeySystem.SLOTS, null))
    })
    private fun view(s: EditorSession, slot: Int = 1) = ViewController(s, ViewState(kind = ViewKind.INSTRUMENT, instrument = slot))
    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun allFiveKindsKeepNativeFieldsAndEveryControlHasExactOwnership() {
        val s = session().song
        for (kind in PokeyKind.entries) {
            val old = patch(kind)
            for (n in 0..5) {
                val lane = kind.lanes.min()
                val next = PokeySoundMacros.apply(s, old, n, PokeySoundMacros.maximum(s, old, n), lane)
                val restored = when (n) {
                    0 -> next.copy(kind = old.kind, sample = old.sample, loop = old.loop, filterOffset = old.filterOffset)
                    1 -> if (kind == PokeyKind.SAMPLE) next.copy(sample = old.sample, loop = old.loop) else next.copy(distortion = old.distortion)
                    2 -> if (kind == PokeyKind.SAMPLE) next.copy(rate = old.rate) else next.copy(fast = old.fast)
                    3 -> if (kind == PokeyKind.SAMPLE) next.copy(root = old.root) else next.copy(volume = old.volume)
                    4 -> if (kind == PokeyKind.SAMPLE) next.copy(loop = old.loop) else next.copy(filterOffset = old.filterOffset, difference = old.difference)
                    else -> next.copy(partnerVolume = old.partnerVolume)
                }
                assertEquals("$kind control $n", old, restored)
                PokeySoundMacros.value(s, next, n)?.let { assertSame(next, PokeySoundMacros.apply(s, next, n, it, lane)) }
            }
            for (target in PokeyKind.entries) {
                val changed = PokeySoundMacros.apply(s, old, 0, target.ordinal, lane = 0)
                assertEquals(target, changed.kind)
                val state = ViewState(listenChannel = 2)
                assertTrue(PokeyInstrumentView.listenChannel(state, changed) in target.lanes)
            }
        }
    }

    @Test fun customProgramsAndUnavailableHardwareStayExact() {
        val s = session().song
        val old = patch().copy(distortion = NesEnvelope(listOf(5, 6), loop = 0))
        assertNull(PokeySoundMacros.value(s, old, 1))
        assertFalse(PokeySoundMacros.enabled(s, old, 1, 0))
        assertSame(old, PokeySoundMacros.apply(s, old, 1, 1, 0))
        assertNull(PokeySoundMacros.value(s, old, 3))
        assertSame(old, PokeySoundMacros.apply(s, old, 2, 1, 1))
        assertSame(old, PokeySoundMacros.apply(s, old, 4, 4, 0))
        assertSame(old, PokeySoundMacros.apply(s, old, 5, 15, 0))
        for (shape in EnvelopeContour.Shape.entries) {
            val next = PokeySoundMacros.apply(s, old, 3, shape.ordinal, 0)
            assertEquals(shape, EnvelopeContour.read(next.volume)!!.shape)
            assertEquals(12, next.volume.values.maxOrNull())
            assertEquals(old, next.copy(volume = old.volume))
        }
        for (dist in listOf(1, 3, 5, 7)) {
            val alias = patch().copy(distortion = NesEnvelope(listOf(dist)))
            assertSame(alias, PokeySoundMacros.apply(s, alias, 1, PokeySoundMacros.value(s, alias, 1)!!, 0))
        }
    }

    @Test fun sampleControlsClampNativeRangesWithoutChangingRecordings() {
        val s = session(patch(PokeyKind.SAMPLE)).song
        val old = s.pokeyInstrument(1)!!
        assertEquals(-1, PokeySoundMacros.apply(s, old, 1, 1, 0).loop)
        assertEquals(32000, PokeySoundMacros.apply(s, old, 2, Int.MAX_VALUE, 0).rate)
        assertEquals(1000, PokeySoundMacros.apply(s, old, 2, Int.MIN_VALUE, 0).rate)
        assertEquals(PokeyRules.NOTES.last, PokeySoundMacros.apply(s, old, 3, Int.MAX_VALUE, 0).root)
        assertEquals(11, PokeySoundMacros.apply(s, old, 4, Int.MAX_VALUE, 0).loop)
        assertEquals(-1, PokeySoundMacros.apply(s, old, 4, Int.MIN_VALUE, 0).loop)
        assertSame(old, PokeySoundMacros.apply(s, old, 5, 0, 0))
        assertEquals(listOf(1, 9, 3), s.pokey.samples[1].levels)
    }

    @Test fun modeSwitchesPreserveExactNativeImageForEveryKind() {
        for (kind in PokeyKind.entries) {
            val s = session(patch(kind)); val v = view(s)
            val old = s.song; val image = PokeyLowering.lower(old)
            repeat(3) { PokeySoundFace.selectMode(v, true); v.normalise(); PokeySoundFace.selectMode(v, false); v.normalise() }
            assertSame(old, s.song)
            assertArrayEquals(image, PokeyLowering.lower(s.song))
        }
    }

    @Test fun navigationCreationAuditionAndGroupedUndoUseSoundAxes() {
        val s = session(); val v = view(s, PokeySystem.SLOTS)
        val old = s.song
        v.normalise(); assertEquals(2, v.state.instField)
        v.tap(Action.LEFT); assertEquals(1, v.state.instField)
        v.tap(Action.LEFT); assertEquals(-1, v.state.instField)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        assertEquals(3, v.state.instField)
        assertNull(s.song.pokeyInstrument(PokeySystem.SLOTS))
        val heard = ArrayList<Int>()
        s.onAudition = { _, _, slot, _ -> heard += slot }
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(1, s.song.pokeyInstrument(PokeySystem.SLOTS)!!.distortion.values.single())
        assertTrue(heard.isNotEmpty())
        s.undo(); assertSame(old, s.song)
        v.tap(Action.UP); assertEquals(PokeySystem.SLOTS - 1, v.state.instrument)
        assertEquals(3, v.state.instField)
        v.state.instField = 1
        v.press(Action.EDIT); repeat(80) { v.tap(Action.DOWN) }; v.release(Action.EDIT)
        assertEquals(PokeyInstrumentView.NAME_MAX - 1, v.state.textPos)
    }

    @Test fun acceptedHeldVoiceChangeRestartsTheNewPatchOnItsEffectiveLaneBeforeBlockingFurtherEdits() {
        val s = session(); val v = view(s)
        v.state.instField = 2
        v.state.listenChannel = 0
        val heard = ArrayList<Pair<Int, PokeyInstrument?>>()
        s.onAudition = { lane, _, slot, _ -> heard += lane to s.song.pokeyInstrument(slot) }
        val old = s.song
        v.press(Action.EDIT)
        assertEquals(0 to old.pokeyInstrument(1), heard.single())
        v.tap(Action.RIGHT)
        val joined = s.song
        assertEquals(PokeyKind.JOINED, joined.pokeyInstrument(1)!!.kind)
        assertEquals(2, heard.size)
        assertEquals(1 to joined.pokeyInstrument(1), heard.last())
        assertTrue(PokeySoundFace.panel(v.state).editBlocked)
        v.tap(Action.RIGHT)
        assertSame(joined, s.song)
        assertEquals(2, heard.size)
        v.release(Action.EDIT)
        assertFalse(PokeySoundFace.panel(v.state).editBlocked)
        s.undo()
        assertSame(old, s.song)
        s.redo()
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(PokeyKind.FILTERED, s.song.pokeyInstrument(1)!!.kind)
        assertEquals(0 to s.song.pokeyInstrument(1), heard.last())
    }

    @Test fun heldEditCannotCrossModeSlotKindOrExternalFollow() {
        val s = session(); val v = view(s)
        v.state.instField = 3
        v.press(Action.EDIT)
        PokeySoundFace.selectMode(v, true)
        val old = s.song
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(old, s.song)
        PokeySoundFace.selectMode(v, false)
        v.press(Action.EDIT); PokeySoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(old, s.song)
        v.state.instrument = 1; v.state.instField = 2
        v.press(Action.EDIT); v.tap(Action.RIGHT)
        val joined = s.song
        assertEquals(PokeyKind.JOINED, joined.pokeyInstrument(1)!!.kind)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(joined, s.song)
        v.press(Action.EDIT)
        PokeyInstrumentView.follow(v.state, s.selection.copy(instrument = 2), s.song)
        v.tap(Action.RIGHT); v.release(Action.EDIT); assertSame(joined, s.song)
    }

    @Test fun choosingSamplesWithoutDataOpensTheRealImporterWithoutMakingInvalidSound() {
        val s = EditorSession(PokeySystem.blank().let { it.copy(sound = it.pokey.copy(samples = emptyList())) })
        val v = view(s)
        val old = s.song
        val entries = ArrayList<String>()
        s.onMenuRequest = { build -> build(object : MenuContext {
            override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) = error("Unexpected text prompt")
            private val captured = entries
            override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) { entries.mapTo(captured) { it.first } }
            override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = Unit
            override fun status(message: String) = Unit
            override fun error(message: String) = throw AssertionError(message)
            override fun installRhythmRom(bytes: ByteArray): String? = throw AssertionError("Unexpected ROM")
        }) }
        PokeySoundFace.set(v, 0, PokeyKind.SAMPLE.ordinal)
        assertTrue(entries.isNotEmpty())
        assertSame(old, s.song)
    }

    @Test fun touchingAnEmptiedPreviouslyPopulatedSlotCreatesItsOwnDefault() {
        val s = session()
        val slot = PokeySystem.SLOTS
        s.setPokeyInstrument(slot, patch(), "Fixture")
        val p = Panel(s, 960, 720)
        p.state.instrument = slot; p.state.instField = 3; p.draw()
        s.setPokeyInstrument(slot, null, "Empty")
        p.draw()
        p.painter.down(p.face.valueX(1, 0f), p.face.controlRect(1).centerY())
        p.painter.up(0f, 0f)
        assertEquals(defaultPokeyInstrument(slot), s.song.pokeyInstrument(slot))
    }

    private class Panel(val session: EditorSession, val width: Int, val height: Int) {
        val surface = AtariPanelSurface()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = PokeyInstrumentPainter(pane)
        val face get() = painter.soundFace
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            pane.controller.normalise()
            painter.draw(surface, Rect(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f))
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(0f, 0f) }
    }

    @Test fun touchDragRepaintsAsOneUndoAndCannotEscapeItsSlotModeOrHistory() {
        val s = session(); val p = Panel(s, 960, 720)
        fun start() { p.state.instField = 3; p.draw(); p.painter.down(p.face.valueX(1, 1f), p.face.controlRect(1).centerY()) }
        val old = s.song
        start(); p.draw(); p.painter.move(p.face.valueX(1, 0.5f), 0f); p.painter.up(0f, 0f)
        assertEquals(2, s.song.pokeyInstrument(1)!!.distortion.values.single())
        s.undo(); assertSame(old, s.song)
        start(); s.undo(); p.painter.move(999f, 0f); assertSame(old, s.song)
        start(); PokeySoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song; p.painter.move(999f, 0f); assertSame(moved, s.song)
        s.undo(); assertSame(old, s.song)
        p.state.instrument = 1; start(); PokeySoundFace.selectMode(p.pane.controller, true)
        val switched = s.song; p.painter.move(999f, 0f); assertSame(switched, s.song)
        s.undo(); assertSame(old, s.song)
    }

    @Test fun handheldLandscapeExposesAllSixTouchControlsWithoutChangingThePatch() {
        for ((width, height) in listOf(640 to 384, 552 to 408)) {
            val s = session()
            val original = s.song
            val p = Panel(s, width, height)
            p.draw()
            val rects = (0..5).map(p.face::controlRect)
            for ((i, r) in rects.withIndex()) {
                assertTrue("$width/$height control $i", r.width() >= 48f && r.height() >= 48f)
                assertTrue(r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height)
                for (other in rects.take(i))
                    assertTrue(r.right <= other.left || r.left >= other.right || r.bottom <= other.top || r.top >= other.bottom)
            }
            assertSame(original, s.song)
        }
    }

    @Test fun compactFacePagesEveryControlAndSlotsAtTouchSize() {
        val s = session(); val old = s.song
        val p = Panel(s, 360, 270)
        for (n in 0..5) {
            p.draw()
            assertEquals(n + 2, p.state.instField)
            val r = p.face.controlRect(n)
            assertTrue("control $n height ${r.height()}", r.height() >= 48f)
            assertTrue(r.width() >= 48f)
            val next = p.face.navigationRect(true)
            assertTrue(next.width() >= 48f && next.height() >= 48f)
            p.tap(next)
        }
        p.draw(); p.tap(p.face.slotNavigationRect(true)); assertEquals(2, p.state.instrument)
        p.draw(); p.tap(p.face.modeRect(true)); assertTrue(PokeySoundFace.panel(p.state).advanced)
        p.draw(); p.tap(p.face.modeRect(false)); assertFalse(PokeySoundFace.panel(p.state).advanced)
        assertSame(old, s.song)
    }
}

/** Inert native-surface substitute: exercises layout and pointer ownership, not screenshot pixels. */
internal class AtariPanelSurface : Surface {
    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = Unit
    override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = Unit
    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = Unit
    override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = Unit
    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
    override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) = Unit
    override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
}
