package org.thortracker.system.scc

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.SccLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.system.nes.NesEnvelope
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class SccSoundFaceTest {
    private fun patch() = SccInstrument(name = "Keep wave", wave = NesEnvelope(listOf(2), loop = 0),
        volume = NesEnvelope(listOf(14, 11, 5, 0), loop = 0, release = 2),
        arpeggio = NesEnvelope(listOf(-2, 0, 7), loop = 0),
        pitch = NesEnvelope(listOf(3, -7, 4), loop = 1), pitchRelative = true)
    private fun session(variant: SccVariant = SccVariant.SCC): EditorSession {
        val song = variant.system.blank()
        return EditorSession(song.copy(sound = song.scc.withInstrument(1, patch())))
    }
    private fun view(s: EditorSession, field: Int = 2) = ViewController(s,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = field))
    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun recipesHaveExactReadbackBoundsAndMinimalOwnership() {
        val before = patch()
        for (control in 0..3) for (requested in listOf(-100, 0, 1, 2, 3, 999)) {
            val after = SccSoundMacros.apply(control, before, requested)
            val restored = when (control) {
                0 -> after.copy(wave = before.wave)
                1 -> after.copy(volume = before.volume)
                2 -> after.copy(pitch = before.pitch, pitchRelative = before.pitchRelative)
                else -> after.copy(restart = before.restart)
            }
            assertEquals("control $control", before, restored)
            val value = requested.coerceIn(0, SccSoundMacros.maximum(control))
            assertEquals(value, SccSoundMacros.value(control, after))
            assertSame(after, SccSoundMacros.apply(control, after, value))
            assertTrue(after.volume.values.all { it in 0..15 })
            assertTrue(after.pitch.values.all { it in -64..63 })
            assertTrue(after.wave.values.all { it in 1..SccSound.WAVES })
        }
        assertEquals(0, SccSoundMacros.apply(0, before, 4).wave.loop)
        assertSame(before, SccSoundMacros.apply(4, before, 3))
    }

    @Test fun customSequencesNeverFlattenAsASideEffectOfAnotherControl() {
        val before = patch().copy(wave = NesEnvelope(listOf(2, 3, 4), loop = 1))
        assertFalse(SccSoundMacros.enabled(0, before))
        assertSame(before, SccSoundMacros.apply(0, before, 1))
        assertEquals(-1, SccSoundMacros.value(0, before))
        assertEquals(-1, SccSoundMacros.value(1, before))
        assertEquals(-1, SccSoundMacros.value(2, before))
        for (control in 1..3) {
            val after = SccSoundMacros.apply(control, before, 1)
            assertSame(before.wave, after.wave)
            assertSame(before.arpeggio, after.arpeggio)
        }
        val emptyVolume = before.copy(volume = NesEnvelope())
        assertSame(emptyVolume, SccSoundMacros.apply(1, emptyVolume, 0))
        val emptyPitch = before.copy(pitch = NesEnvelope(), pitchRelative = true)
        assertSame(emptyPitch, SccSoundMacros.apply(2, emptyPitch, 0))
    }

    @Test fun bothVariantsPreserveNativeBytesAcrossDrawingAndModeSwitches() {
        for (variant in SccVariant.entries) {
            val s = session(variant)
            val before = s.song
            val bytes = SccLowering.lower(before)
            val p = Panel(s, 720, 480)
            repeat(3) {
                SccSoundFace.mode(p.pane.controller, true); p.draw()
                SccSoundFace.mode(p.pane.controller, false); p.draw()
            }
            assertSame(before, s.song)
            assertArrayEquals(bytes, SccLowering.lower(s.song))
        }
    }

    @Test fun controllerAxesModeAndNameCaretStaySeparateFromMusicalEdits() {
        val s = session()
        val v = view(s, 0)
        val before = s.song
        v.normalise(); assertEquals(2, v.state.instField)
        v.tap(Action.DOWN); assertEquals(2, v.state.instrument)
        v.tap(Action.UP); assertEquals(1, v.state.instrument)
        for (field in listOf(3, 4, 5, 6, -1, 1, 2)) {
            v.tap(Action.RIGHT); assertEquals(field, v.state.instField)
        }
        assertSame(before, s.song)
        v.state.instField = 1
        v.press(Action.EDIT)
        repeat(100) { v.tap(Action.DOWN) }
        assertEquals(EditorSession.MAX_NAME - 1, v.state.textPos)
        repeat(100) { v.tap(Action.UP) }
        assertEquals(0, v.state.textPos)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(before.sccInstrument(1), s.song.sccInstrument(1)!!.copy(name = "Keep wave"))
        s.undo(); assertSame(before, s.song)
        v.state.instField = 3
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertNotEquals(before, s.song)
        s.undo(); assertSame(before, s.song)
        v.state.instField = -1
        v.tap(Action.EDIT); assertTrue(v.state.soundPanel("scc").advanced)
        v.tap(Action.LEFT); assertFalse(v.state.soundPanel("scc").advanced)
        assertSame(before, s.song)
    }

    @Test fun heldEditCannotCrossModeOrSlotChangesAndAuditionIsReleased() {
        val s = session()
        val v = view(s)
        val before = s.song
        var releases = 0
        var starts = 0
        s.onAudition = { _, _, _, _ -> starts++ }
        s.onReleaseAudition = { releases++ }
        v.press(Action.EDIT)
        SccSoundFace.mode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(before, s.song)
        assertEquals(1, starts); assertTrue(releases > 0)
        SccSoundFace.mode(v, false)
        v.press(Action.EDIT)
        SccSoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(before, s.song)
    }

    @Test fun everyChannelOfBothVariantsAuditionsWithoutChangingSharedWaves() {
        for (variant in SccVariant.entries) {
            val s = session(variant)
            val v = view(s, 6)
            val before = s.song
            val heard = mutableListOf<Int>()
            s.onAudition = { channel, _, _, _ -> heard += channel }
            for (channel in 0..4) { SccSoundFace.set(v, 4, channel); v.tap(Action.AUDITION) }
            assertEquals((0..4).toList(), heard)
            assertSame(before, s.song)
            v.state.instField = 2
            v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
            assertSame(before.scc.waves, s.song.scc.waves)
            assertEquals(before.scc.variant, s.song.scc.variant)
            for (slot in 2..SccSound.SLOTS) assertSame(before.sccInstrument(slot), s.song.sccInstrument(slot))
        }
    }

    @Test fun emptySlotCreationIsDeliberateAndUndoable() {
        val s = session()
        val v = view(s)
        v.state.instrument = SccSound.SLOTS
        v.normalise()
        SccSoundFace.mode(v, true); SccSoundFace.mode(v, false)
        assertNull(s.song.sccInstrument(v.state.instrument))
        v.tap(Action.EDIT)
        assertEquals(defaultSccInstrument(v.state.instrument), s.song.sccInstrument(v.state.instrument))
        s.undo(); assertNull(s.song.sccInstrument(v.state.instrument))
    }

    @Test fun touchDragPinsOriginalPatchGroupsUndoAndCancelsStaleWrites() {
        val s = session()
        val p = Panel(s, 960, 720)
        p.state.instField = 3; p.draw()
        val before = s.song
        fun start() { p.draw(); val r = p.painter.controlRect(1); assertTrue(p.painter.down(r.left + 12, r.centerY())) }
        start()
        val r = p.painter.controlRect(1)
        p.painter.move(r.right - 12, r.centerY()); p.draw(); p.painter.up(0f, 0f)
        assertEquals(3, SccSoundMacros.value(1, s.song.sccInstrument(1)!!))
        s.undo(); assertSame(before, s.song)
        start(); s.undo(); p.painter.move(999f, 0f); assertSame(before, s.song); p.painter.up(0f, 0f)
        start(); SccSoundFace.mode(p.pane.controller, true)
        val switched = s.song
        p.painter.move(999f, 0f); assertSame(switched, s.song)
        s.undo(); assertSame(before, s.song)
        SccSoundFace.mode(p.pane.controller, false)
        start(); SccSoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song
        p.painter.move(999f, 0f); assertSame(moved, s.song)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun landscapeDeckShowsEveryControlWithoutHidingTheStoredWave() {
        for (variant in SccVariant.entries) {
            val s = session(variant)
            val before = s.song
            val p = Panel(s, 640, 384)
            for (i in 0 until SccSoundMacros.COUNT) {
                val r = p.painter.controlRect(i)
                assertTrue(r.width() >= 48f && r.height() >= 48f)
                assertTrue(r.top >= p.area.top && r.bottom <= p.area.bottom - 48f)
            }
            assertEquals(p.painter.controlRect(0).top, p.painter.controlRect(1).top, .01f)
            assertTrue(p.painter.controlRect(2).top >= p.painter.controlRect(0).bottom)
            assertSame(before, s.song)
        }
    }

    @Test fun compactPagingAndSharedRamWarningRemainAvailableByTouch() {
        for (variant in SccVariant.entries) for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) {
            val s = session(variant)
            val before = s.song
            val p = Panel(s, 360, 270, zoom)
            repeat(SccSoundMacros.COUNT) { i ->
                val r = p.painter.controlRect(i)
                assertTrue(r.width() >= 48f && r.height() >= 47.99f)
                assertTrue(r.top >= p.area.top && r.bottom <= p.area.bottom)
                p.tap(p.painter.navigationRect(true)); p.draw()
            }
            p.tap(p.painter.slotNavigationRect(true)); p.draw()
            assertEquals(2, p.state.instrument)
            assertSame(before, s.song)
        }
        for (variant in SccVariant.entries) {
            val p = Panel(session(variant), 960, 720)
            p.state.listenChannel = 3; p.draw()
            assertEquals(variant.sharedWave, p.surface.labels.any { it.contains("last wave wins") })
        }
    }

    @Test fun soundReusesTheExactAdvancedBankDrawingAtBothSizes() {
        for ((width, height) in listOf(360 to 270, 960 to 720)) for (variant in SccVariant.entries) {
            val p = Panel(session(variant), width, height)
            p.surface.columnRight = minOf(width * .3f, p.pane.ink.unit * 14)
            p.surface.columnTop = 48f + p.pane.ink.unit * 2
            p.draw()
            val sound = p.surface.ops.toList()
            p.surface.ops.clear()
            SccSoundFace.mode(p.pane.controller, true); p.draw()
            assertTrue(sound.isNotEmpty())
            assertEquals(sound, p.surface.ops)
        }
    }

    @Test fun duplicateTerminatesTouchAndHeldEditsBeforeSelectingTheNewSlot() {
        val s = session()
        val p = Panel(s, 960, 720)
        val v = p.pane.controller
        p.state.instField = 3; p.draw()
        val r = p.painter.controlRect(1)
        p.painter.down(r.left + 12, r.centerY())
        v.tap(Action.DUPLICATE)
        assertEquals(2, p.state.instrument)
        val duplicated = s.song
        p.painter.move(r.right - 12, r.centerY())
        assertSame(duplicated, s.song)
        p.painter.up(0f, 0f)
        v.press(Action.EDIT)
        v.tap(Action.DUPLICATE)
        assertEquals(3, p.state.instrument)
        val heldDuplicate = s.song
        v.tap(Action.RIGHT); v.tap(Action.DUPLICATE); v.tap(Action.CUT); v.tap(Action.PASTE)
        assertSame(heldDuplicate, s.song)
        assertEquals(3, p.state.instrument)
        v.release(Action.EDIT)
        v.tap(Action.DUPLICATE)
        assertEquals(4, p.state.instrument)
    }

    @Test fun soundPasteRejectsEveryEnvelopeKindAndRetainsWholeSoundPaste() {
        val s = session()
        val v = view(s)
        for (kind in SccEnvelopeKind.entries) {
            s.copySccEnvelope(1, kind)
            val before = s.song
            v.tap(Action.PASTE)
            assertSame(before, s.song)
            assertTrue(s.message!!.contains("Advanced"))
        }
        s.copySccInstrument(1)
        v.tap(Action.DOWN); v.tap(Action.PASTE)
        assertEquals(s.song.sccInstrument(1), s.song.sccInstrument(2))
    }

    @Test fun touchingNameRequestsBoundedNativeRenameAndUndo() {
        val s = session()
        val menu = TextMenu()
        s.onMenuRequest = { it(menu) }
        val p = Panel(s, 360, 270)
        val before = s.song
        val left = p.painter.slotNavigationRect(false)
        val right = p.painter.slotNavigationRect(true)
        val x = (left.right + right.left) / 2
        assertTrue(p.painter.down(x, left.centerY())); p.painter.up(x, left.centerY())
        assertEquals("Keep wave", menu.initial)
        assertEquals(EditorSession.MAX_NAME, menu.maximum)
        assertSame(before, s.song)
        menu.accepted!!("N".repeat(100))
        assertEquals(before.sccInstrument(1)!!.copy(name = "N".repeat(EditorSession.MAX_NAME)), s.song.sccInstrument(1))
        s.undo(); assertSame(before, s.song)
    }

    @Test fun renameCallbacksCannotCrossSlotsModesViewsDocumentsOrPatchChanges() {
        for (boundary in 0..4) {
            val s = session()
            val menu = TextMenu()
            s.onMenuRequest = { it(menu) }
            val v = view(s)
            SccSoundFace.rename(v)
            val accept = menu.accepted!!
            when (boundary) {
                0 -> SccSoundFace.moveSlot(v, 1)
                1 -> { SccSoundFace.mode(v, true); SccSoundFace.mode(v, false) }
                2 -> v.state.kind = ViewKind.WAVE
                3 -> s.replaceSong(s.song)
                else -> s.setSccInstrument(1, patch().copy(restart = true), "Other edit")
            }
            val beforeAccept = s.song
            accept("Stale")
            assertSame(beforeAccept, s.song)
        }
    }

    private class TextMenu : MenuContext {
        var initial = ""
        var maximum = 0
        var accepted: ((String) -> Unit)? = null
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) {
            this.initial = initial; maximum = maxLength; this.accepted = accepted
        }
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) = error("Unexpected menu")
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = error("Unexpected file picker")
        override fun status(message: String) = Unit
        override fun error(message: String): Nothing = throw AssertionError(message)
        override fun installRhythmRom(bytes: ByteArray): String? = error("Unexpected ROM import")
    }

    private class Panel(val session: EditorSession, val width: Int, val height: Int, zoom: PaneZoom = PaneZoom.NORMAL) {
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, zoom = zoom)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = SccSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            surface.labels.clear()
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise(); painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
    }
    private class Canvas : Surface {
        val labels = mutableListOf<String>()
        val ops = mutableListOf<String>()
        var columnRight = 0f
        var columnTop = 0f
        private fun keep(x: Float, y: Float, value: String) { if (x < columnRight - .01f && y >= columnTop - .01f) ops += value }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = keep(l, t, "fill $l $t $r $b $color")
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = Unit
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = keep(l, t, "stroke $l $t $r $b $color $width $radius")
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = keep(minOf(x0, x1), minOf(y0, y1), "line $x0 $y0 $x1 $y1 $color $width")
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            labels += s
            keep(x, top, "text $s $x $top $h $color $face $bold $size $align")
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float = s.length * size / 2
    }
}
