package org.thortracker.system.pce

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.PceLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class PceSoundFaceTest {
    private fun patch(kind: PceKind = PceKind.TONE) = PceInstrument(
        name = "Kept", kind = kind, wave = 1,
        volume = PceEnvelope(listOf(25, 19, 12, 0), loop = 1, release = 2),
        arpeggio = PceEnvelope(listOf(2, 7, -3), loop = 0),
        pitch = PceEnvelope(listOf(7, -13), loop = 0), pitchRelative = true,
        root = 51, loop = true, loopStart = 3,
    )
    private fun session(kind: PceKind = PceKind.TONE): EditorSession {
        val song = PceSystem.blank()
        return EditorSession(song.copy(sound = song.pce.copy(
            samples = listOf(PceSample("Long", List(16) { it }), PceSample("Short", listOf(0, 31))),
            lfoControl = 2, lfoFrequency = 19, timerReload = 1,
        ).withInstrument(1, patch(kind))))
    }
    private fun view(s: EditorSession, field: Int = 2) = ViewController(s,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = field))
    private fun ViewController.tap(a: Action) { press(a); release(a) }

    @Test fun everySourceOwnsOnlyItsNamedFieldsAndNeverSharedStorage() {
        for (kind in PceKind.entries) {
            val s = session(kind).song.pce
            val before = s.instrument(1)!!
            for (control in 0..4) for (requested in listOf(-100, 0, 1, 2, 3, 999)) {
                val after = PceSoundMacros.apply(control, s, before, requested)
                val restored = when (control) {
                    0 -> after.copy(kind = before.kind)
                    1 -> when (kind) {
                        PceKind.TONE -> after.copy(wave = before.wave)
                        PceKind.NOISE -> after.copy(arpeggio = before.arpeggio)
                        PceKind.SAMPLE -> after.copy(sample = before.sample, loopStart = before.loopStart)
                    }
                    2 -> after.copy(volume = before.volume)
                    3 -> when (kind) {
                        PceKind.TONE -> after.copy(pitch = before.pitch, pitchRelative = before.pitchRelative)
                        PceKind.NOISE -> after.copy(arpeggio = before.arpeggio)
                        PceKind.SAMPLE -> after.copy(root = before.root)
                    }
                    else -> after.copy(loop = before.loop)
                }
                assertEquals("$kind / $control", before, restored)
                assertNull(after.problem())
                if (PceSoundMacros.enabled(control, s, before)) {
                    val n = requested.coerceIn(0, PceSoundMacros.maximum(control, s, before))
                    assertEquals(n, PceSoundMacros.value(control, after))
                    assertSame(after, PceSoundMacros.apply(control, s, after, n))
                }
            }
            assertSame(before, s.instrument(1))
        }
        val s = session(PceKind.SAMPLE).song.pce
        val picked = PceSoundMacros.apply(1, s, s.instrument(1)!!, 1)
        assertEquals(1, picked.loopStart)
        assertEquals(1, picked.sample)
    }

    @Test fun customProgramsRemainExactUntilTheirOwnExplicitRecipeIsChosen() {
        val s = session().song.pce
        val sequence = patch().copy(waves = PceEnvelope(listOf(0, 1), loop = 0))
        assertFalse(PceSoundMacros.enabled(1, s, sequence))
        assertSame(sequence, PceSoundMacros.apply(1, s, sequence, 0))
        assertEquals(-1, PceSoundMacros.value(2, sequence))
        assertEquals(-1, PceSoundMacros.value(3, sequence))
        val contour = PceSoundMacros.apply(2, s, sequence, 1)
        assertEquals(sequence, contour.copy(volume = sequence.volume))
        val noise = patch(PceKind.NOISE)
        assertFalse(PceSoundMacros.enabled(1, s, noise))
        assertSame(noise, PceSoundMacros.apply(1, s, noise, 2))
        val still = PceSoundMacros.apply(3, s, noise, 0)
        assertTrue(PceSoundMacros.enabled(1, s, still))
        val color = PceSoundMacros.apply(1, s, still, 12)
        assertEquals(listOf(12), color.arpeggio.values)
        assertEquals(noise, color.copy(arpeggio = noise.arpeggio))
        val empty = s.copy(samples = emptyList())
        assertFalse(PceSoundMacros.enabled(1, empty, patch(PceKind.SAMPLE)))
        assertFalse(PceSoundMacros.enabled(4, empty, patch(PceKind.SAMPLE)))
    }

    @Test fun openingModesAndRenderingPreserveAllNativeBytes() {
        for (kind in PceKind.entries) {
            val s = session(kind)
            val before = s.song
            val bytes = PceLowering.lower(before)
            val p = Panel(s, 720, 480)
            repeat(3) {
                p.pane.controller.normalise()
                PceSoundFace.mode(p.pane.controller, true); p.draw()
                PceSoundFace.mode(p.pane.controller, false); p.draw()
            }
            assertSame(before, s.song)
            assertArrayEquals(bytes, PceLowering.lower(s.song))
        }
    }

    @Test fun bareAxesNamesAndHeldEditsUseTheSoundContract() {
        val s = session()
        val v = view(s, 0)
        val before = s.song
        v.normalise(); assertEquals(2, v.state.instField)
        v.tap(Action.DOWN); assertEquals(2, v.state.instrument)
        v.tap(Action.UP); assertEquals(1, v.state.instrument)
        for (field in listOf(3, 4, 5, 6, 7, -1, 1, 2)) {
            v.tap(Action.RIGHT); assertEquals(field, v.state.instField)
        }
        assertSame(before, s.song)
        v.state.instField = 1
        v.press(Action.EDIT)
        repeat(100) { v.tap(Action.DOWN) }
        assertEquals(PceInstrumentView.NAME_MAX - 1, v.state.textPos)
        repeat(100) { v.tap(Action.UP) }
        assertEquals(0, v.state.textPos)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(before.pceInstrument(1), s.song.pceInstrument(1)!!.copy(name = "Kept"))
        s.undo(); assertSame(before, s.song)
        v.state.instField = 4
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertNotEquals(before, s.song)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun heldSourceAndModeChangesBlockFurtherEditsAndReleaseTheirAudition() {
        val s = session()
        val v = view(s)
        var releases = 0
        s.onReleaseAudition = { releases++ }
        v.press(Action.EDIT); v.tap(Action.RIGHT)
        assertEquals(PceKind.NOISE, s.song.pceInstrument(1)!!.kind)
        val changed = s.song
        v.tap(Action.RIGHT); v.tap(Action.CLEAR)
        assertSame(changed, s.song)
        v.release(Action.EDIT)
        assertTrue(releases > 0)
        v.press(Action.EDIT)
        PceSoundFace.mode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(changed, s.song)
    }

    @Test fun allHardwareChannelsAreAuditionableWithoutDocumentEdits() {
        for (kind in PceKind.entries) {
            val s = session(kind)
            val v = view(s, 7)
            val before = s.song
            val heard = mutableListOf<Int>()
            s.onAudition = { ch, _, _, _ -> heard += ch }
            val max = PceSoundMacros.maximum(5, s.song.pce, patch(kind))
            for (value in 0..max) { PceSoundFace.set(v, 5, value); v.tap(Action.AUDITION) }
            assertEquals(if (kind == PceKind.NOISE) listOf(4, 5) else (0..5).toList(), heard)
            assertSame(before, s.song)
        }
    }

    @Test fun emptySlotsAreOnlyCreatedByDeliberateMusicalEdits() {
        val s = session()
        val v = view(s)
        v.state.instrument = PceSystem.SLOTS
        v.normalise()
        assertNull(s.song.pceInstrument(v.state.instrument))
        PceSoundFace.mode(v, true); PceSoundFace.mode(v, false)
        assertNull(s.song.pceInstrument(v.state.instrument))
        v.tap(Action.EDIT)
        assertEquals(defaultPceInstrument(v.state.instrument), s.song.pceInstrument(v.state.instrument))
        s.undo(); assertNull(s.song.pceInstrument(v.state.instrument))
    }

    @Test fun touchDragGroupsUndoAndCannotCrossUndoSlotOrModeBoundaries() {
        val s = session()
        val p = Panel(s, 960, 720)
        p.state.instField = 4; p.draw()
        val before = s.song
        fun start() { p.draw(); val r = p.painter.controlRect(2); assertTrue(p.painter.down(r.left + 12, r.centerY())) }
        start()
        var r = p.painter.controlRect(2)
        p.painter.move(r.right - 12, r.centerY()); p.draw()
        p.painter.up(0f, 0f)
        assertEquals(3, PceSoundMacros.value(2, s.song.pceInstrument(1)!!))
        s.undo(); assertSame(before, s.song)
        start(); s.undo(); p.painter.move(900f, 0f); assertSame(before, s.song); p.painter.up(0f, 0f)
        start(); PceSoundFace.mode(p.pane.controller, true)
        val switched = s.song
        p.painter.move(900f, 0f); assertSame(switched, s.song)
        s.undo(); assertSame(before, s.song)
        PceSoundFace.mode(p.pane.controller, false)
        start(); PceSoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song
        p.painter.move(900f, 0f); assertSame(moved, s.song)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun compactPagingKeepsEveryControlAndSlotStepperTouchSized() {
        for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) {
            val s = session()
            val before = s.song
            val p = Panel(s, 360, 270, zoom)
            repeat(PceSoundMacros.COUNT) { i ->
                val r = p.painter.controlRect(i)
                assertTrue(r.width() >= 48f && r.height() >= 47.99f)
                assertTrue(r.top >= p.area.top && r.bottom <= p.area.bottom)
                p.tap(p.painter.navigationRect(true)); p.draw()
            }
            p.tap(p.painter.slotNavigationRect(true)); p.draw()
            assertEquals(2, p.state.instrument)
            assertSame(before, s.song)
        }
    }

    @Test fun soundReusesTheExactAdvancedBankDrawingAtBothSizes() {
        for ((width, height) in listOf(360 to 270, 960 to 720)) for (kind in PceKind.entries) {
            val p = Panel(session(kind), width, height)
            p.surface.columnRight = minOf(width * .26f, p.pane.ink.unit * 12)
            p.surface.columnTop = 48f + p.pane.ink.unit * 2
            p.draw()
            val sound = p.surface.ops.toList()
            p.surface.ops.clear()
            PceSoundFace.mode(p.pane.controller, true); p.draw()
            assertTrue(sound.isNotEmpty())
            assertEquals(sound, p.surface.ops)
        }
    }

    private class Panel(val session: EditorSession, val width: Int, val height: Int, zoom: PaneZoom = PaneZoom.NORMAL) {
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, zoom = zoom)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = PceSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise(); painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
    }
    private class Canvas : Surface {
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
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) =
            keep(x, top, "text $s $x $top $h $color $face $bold $size $align")
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float = s.length * size / 2
    }
}
