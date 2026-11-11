package org.thortracker.system.ym2612

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.YmLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.NameEdit
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class YmSoundFaceTest {
    private fun patch(algorithm: Int = 4) = YmFmInstrument(
        name = "Keep me", algorithm = algorithm, feedback = 3, ams = 2, fms = 4,
        operators = List(4) { i -> YmOperator(dt = i, mul = i + 1, tl = 30 + i * 9, ks = i, ar = 24 + i,
            am = i % 2 == 0, d1r = 7 + i, d2r = 2 + i, sl = 5 + i, rr = 4 + i) },
        sweep = YmSweep(12, -3, 5), ch3 = listOf(64, -128, 3, 0),
    )

    @Test fun everyAlgorithmUsesRealCarriersAndOnlyOwnedFields() {
        for (algorithm in 0..7) {
            val before = patch(algorithm)
            for (control in YmSoundControl.entries) {
                val changed = YmSoundMacros.apply(control, before, when (control) {
                    YmSoundControl.BRIGHTNESS -> 100
                    YmSoundControl.STRUCTURE -> (algorithm + 1) % 8
                    YmSoundControl.HARMONICS -> 4
                    YmSoundControl.EDGE -> 6
                    YmSoundControl.CONTOUR -> 0
                })
                assertEquals(before.name, changed.name)
                assertEquals(before.ch3, changed.ch3)
                assertSame(before.sweep, changed.sweep)
                assertEquals(before.ams, changed.ams)
                assertEquals(before.fms, changed.fms)
                if (control == YmSoundControl.STRUCTURE) {
                    assertEquals(YmCurated.withAlgorithm(before, (algorithm + 1) % 8), changed)
                    for (i in 0..3) assertEquals(before.operators[i], changed.operators[i].copy(tl = before.operators[i].tl))
                    continue
                }
                assertEquals(before.algorithm, changed.algorithm)
                assertEquals(if (control == YmSoundControl.EDGE) 6 else before.feedback, changed.feedback)
                for (i in 0..3) {
                    val old = before.operators[i]
                    val now = changed.operators[i]
                    when (control) {
                        YmSoundControl.BRIGHTNESS -> if (before.isCarrier(i)) assertSame(old, now) else assertEquals(old, now.copy(tl = old.tl))
                        YmSoundControl.HARMONICS -> if (before.isCarrier(i)) assertSame(old, now) else {
                            assertEquals(7, now.mul); assertEquals(old, now.copy(mul = old.mul))
                        }
                        YmSoundControl.CONTOUR -> if (!before.isCarrier(i)) assertSame(old, now) else {
                            assertEquals(old, now.copy(ar = old.ar, d1r = old.d1r, sl = old.sl, d2r = old.d2r, rr = old.rr))
                            assertEquals(listOf(31, 12, 8, 7, 7), listOf(now.ar, now.d1r, now.sl, now.d2r, now.rr))
                        }
                        else -> assertSame(old, now)
                    }
                }
                if (algorithm != 7 && control == YmSoundControl.BRIGHTNESS) {
                    assertEquals(100, YmSoundMacros.value(control, changed))
                    val delta = YmSoundMacros.value(control, before)!! - 100
                    for (i in 0..3) if (!before.isCarrier(i)) assertEquals((before.operators[i].tl + delta).coerceIn(0, 127), changed.operators[i].tl)
                }
            }
        }
    }

    @Test fun customReadbackDoesNotApproximateOrNormalizeAndExactChoicesAreIdentityNoops() {
        val before = patch()
        assertNull(YmSoundMacros.value(YmSoundControl.HARMONICS, before))
        assertNull(YmSoundMacros.value(YmSoundControl.CONTOUR, before))
        for (control in YmSoundControl.entries) {
            assertSame(before, YmSoundMacros.step(control, before, 0))
            assertSame(before, YmSoundMacros.apply(control, before, -1))
            assertSame(before, YmSoundMacros.apply(control, before, control.maximum + 1))
            for (value in 0..control.maximum) {
                val selected = YmSoundMacros.apply(control, before, value)
                assertEquals(value, YmSoundMacros.value(control, selected))
                assertSame(selected, YmSoundMacros.apply(control, selected, value))
            }
        }
        assertEquals(patch(), before)
    }

    @Test fun additiveAndSsgAreExplicitlyUnavailableWithoutTouchingOtherModes() {
        val additive = patch(7)
        for (control in listOf(YmSoundControl.BRIGHTNESS, YmSoundControl.HARMONICS)) {
            assertFalse(YmSoundMacros.enabled(control, additive))
            assertNull(YmSoundMacros.value(control, additive))
            assertSame(additive, YmSoundMacros.apply(control, additive, 0))
            assertSame(additive, YmSoundMacros.step(control, additive, 1))
        }
        assertTrue(YmSoundMacros.enabled(YmSoundControl.EDGE, additive))
        val special = patch().withOperator(1, patch().operators[1].copy(ssgEg = 10))
        assertFalse(YmSoundMacros.enabled(YmSoundControl.CONTOUR, special))
        assertSame(special, YmSoundMacros.apply(YmSoundControl.CONTOUR, special, 0))
        val toneSsg = patch().withOperator(0, patch().operators[0].copy(ssgEg = 10))
        assertTrue(YmSoundMacros.enabled(YmSoundControl.CONTOUR, toneSsg))
        assertSame(toneSsg.operators[0], YmSoundMacros.apply(YmSoundControl.CONTOUR, toneSsg, 1).operators[0])
        for (bits in 0..15) {
            val raw = patch().withOperator(1, patch().operators[1].copy(ssgEg = bits))
            assertEquals(bits < 8, YmSoundMacros.enabled(YmSoundControl.CONTOUR, raw))
            assertEquals(bits, YmSoundMacros.apply(YmSoundControl.CONTOUR, raw, 0).operators[1].ssgEg)
        }
    }

    private fun ViewController.tap(action: Action) { press(action); release(action) }
    private fun session(ins: YmFmInstrument = patch()) = EditorSession(YmSystem.blank()).also { it.setYmInstrument(1, ins, "Fixture") }
    private fun view(session: EditorSession, field: Int = 2) = ViewController(session,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = field))

    @Test fun openingSwitchingAndExactNoopsKeepLoweredBytesAndEveryOtherSlot() {
        val session = session()
        val v = view(session)
        val before = session.song
        val bytes = YmLowering.lower(before)
        repeat(3) {
            v.normalise()
            YmSoundFace.selectMode(v, true)
            v.normalise()
            YmSoundFace.selectMode(v, false)
        }
        for (control in YmSoundControl.entries) {
            YmSoundMacros.value(control, before.ymInstrument(1) as YmFmInstrument)?.let { value ->
                session.editYmFm(1, "Noop") { YmSoundMacros.apply(control, it, value) }
            }
        }
        assertSame(before, session.song)
        assertArrayEquals(bytes, YmLowering.lower(session.song))
        assertFalse(v.state.ymPanel.advanced)
    }

    @Test fun controllerHoldAuditionsSourcePatchAndGroupsUndoWithoutCarrierLoudnessEdits() {
        val session = session()
        val v = view(session)
        val before = session.song
        val heard = ArrayList<YmInstrument?>()
        session.onAudition = { _, _, slot, _ -> heard += session.song.ymInstrument(slot) }
        v.press(Action.EDIT)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertSame(before.ymInstrument(1), heard.first())
        assertEquals(3, heard.size)
        val after = session.song.ymInstrument(1) as YmFmInstrument
        for (i in 0..3) if (after.isCarrier(i)) assertSame((before.ymInstrument(1) as YmFmInstrument).operators[i], after.operators[i])
        for (slot in 2..YmSystem.SLOTS) assertSame(before.ymInstrument(slot), session.song.ymInstrument(slot))
        session.undo()
        assertSame(before, session.song)
    }

    @Test fun heldEditCannotCrossModesAndModeRowIsReachableWithController() {
        val session = session()
        val v = view(session)
        val before = session.song
        v.press(Action.EDIT)
        YmSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR)
        v.release(Action.EDIT)
        assertSame(before, session.song)
        v.state.instField = 0
        v.tap(Action.UP)
        assertEquals(-1, v.state.instField)
        v.tap(Action.LEFT)
        assertFalse(v.state.ymPanel.advanced)
        // Sound's mode row: bare Down chooses the next slot and keeps the mode focused; Right enters the ring at Name.
        v.tap(Action.DOWN)
        assertEquals(-1, v.state.instField)
        assertEquals(2, v.state.instrument)
        v.tap(Action.RIGHT)
        assertEquals(1, v.state.instField)
        v.tap(Action.NEXT_CHANNEL)
        assertEquals(3, v.state.instField)
        assertSame(before, session.song)
    }

    @Test fun soundBareAxesChooseSlotsAndCycleParametersWithoutEditing() {
        val session = session()
        session.setYmInstrument(2, patch(2), "Fixture")
        val v = view(session, 3)
        val before = session.song
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument); assertEquals(3, v.state.instField)
        v.tap(Action.UP)
        assertEquals(1, v.state.instrument); assertEquals(3, v.state.instField)
        v.tap(Action.UP)
        assertEquals(1, v.state.instrument); assertEquals(3, v.state.instField)
        for (field in listOf(4, 5, 6, -1, 1, 2, 3)) { v.tap(Action.RIGHT); assertEquals(field, v.state.instField) }
        for (field in listOf(2, 1, -1, 6, 5, 4, 3)) { v.tap(Action.LEFT); assertEquals(field, v.state.instField) }
        assertFalse(v.state.ymPanel.advanced)
        repeat(3) { v.tap(Action.LEFT) }
        assertEquals(-1, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument); assertEquals(-1, v.state.instField)
        v.tap(Action.UP)
        assertEquals(1, v.state.instrument); assertEquals(-1, v.state.instField)
        assertFalse(v.state.ymPanel.advanced)
        assertSame(before, session.song)
        // On the mode row only Edit chooses the face, and the mode stays focused.
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertTrue(v.state.ymPanel.advanced); assertEquals(-1, v.state.instField)
        v.tap(Action.EDIT)
        assertFalse(v.state.ymPanel.advanced); assertEquals(-1, v.state.instField)
        v.press(Action.EDIT); v.tap(Action.LEFT); v.release(Action.EDIT)
        assertFalse(v.state.ymPanel.advanced); assertEquals(-1, v.state.instField)
        assertSame(before, session.song)
    }

    @Test fun nameEditsLettersWithHeldHorizontalAndMovesCaretWithHeldVerticalWithinTheLimit() {
        val session = session()
        val v = view(session, 1)
        val before = session.song
        val max = YmInstrumentView.NAME_MAX
        v.press(Action.EDIT)
        repeat(max + 3) { v.tap(Action.DOWN) }
        assertEquals(max - 1, v.state.textPos)
        v.tap(Action.UP)
        assertEquals(max - 2, v.state.textPos)
        repeat(max + 3) { v.tap(Action.UP) }
        assertEquals(0, v.state.textPos)
        v.release(Action.EDIT)
        assertSame(before, session.song)
        assertEquals(1, v.state.instrument); assertEquals(1, v.state.instField)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        val first = NameEdit.edit("Keep me", 0, 1, max)
        assertEquals(before.ymInstrument(1)!!.named(first), session.song.ymInstrument(1))
        v.press(Action.EDIT)
        repeat(max) { v.tap(Action.DOWN) }
        v.tap(Action.LEFT)
        v.release(Action.EDIT)
        assertEquals(max - 1, v.state.textPos)
        val last = session.song.ymInstrument(1)!!.name
        assertEquals(NameEdit.edit(first, max - 1, -1, max), last)
        assertTrue(last.length <= max)
        session.undo(); session.undo()
        assertSame(before, session.song)
        // Bare Left leaves the name through the ring; bare Down chooses a slot and keeps the name focused.
        v.tap(Action.LEFT)
        assertEquals(-1, v.state.instField); assertEquals(0, v.state.textPos)
        v.tap(Action.RIGHT)
        assertEquals(1, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument); assertEquals(1, v.state.instField)
        // An empty slot has no name to edit; letters never create a sound.
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, session.song)
    }

    @Test fun freshSoundEditsTheFirstControlAndSlotFocusNeverStrandsSound() {
        val session = session()
        val v = view(session, 0)
        val before = session.song
        // A fresh view's Slot field: Edit+direction shapes the first musical control at once.
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(1, v.state.instrument); assertEquals(2, v.state.instField)
        val shaped = YmSoundMacros.step(YmSoundControl.BRIGHTNESS, before.ymInstrument(1) as YmFmInstrument, 1)
        assertNotSame(before.ymInstrument(1), shaped)
        assertEquals(shaped, session.song.ymInstrument(1))
        session.undo()
        assertSame(before, session.song)
        // An Edit tap is no slot action in Sound: it lands on the first control and changes nothing.
        v.state.instField = 0
        v.tap(Action.EDIT)
        assertEquals(2, v.state.instField)
        assertSame(before, session.song)
        // Leaving an FM slot for a PSG one and stepping off its Slot row back onto FM settles on the first control.
        session.setYmInstrument(2, defaultYmPsg(2), "PSG")
        val withPsg = session.song
        v.state.instField = 3
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instrument); assertFalse(YmSoundFace.friendly(session.song, v.state))
        repeat(3) { v.tap(Action.UP) }
        assertEquals(0, v.state.instField)
        v.tap(Action.RIGHT)
        assertEquals(3, v.state.instrument); assertTrue(YmSoundFace.friendly(session.song, v.state))
        assertEquals(2, v.state.instField)
        assertSame(withPsg, session.song)
        // The empty slot is created by shaping a control.
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(YmSoundMacros.step(YmSoundControl.BRIGHTNESS, defaultYmFm(3), 1), session.song.ymInstrument(3))
    }

    @Test fun advancedKeepsItsPreciseRowNavigation() {
        val session = session()
        session.setYmInstrument(2, patch(1), "Fixture")
        val v = view(session, 0)
        YmSoundFace.selectMode(v, true)
        v.state.instField = 0
        val before = session.song
        v.tap(Action.RIGHT)
        assertEquals(2, v.state.instrument); assertEquals(0, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(1, v.state.instField)
        v.tap(Action.RIGHT)
        assertEquals(1, v.state.textPos)
        repeat(3) { v.tap(Action.DOWN) }
        assertEquals(4, v.state.instField)
        v.tap(Action.RIGHT)
        assertEquals(YmInstrumentView.Page.EXPERT, YmInstrumentView.page(session.song, v.state))
        repeat(5) { v.tap(Action.UP) }
        assertEquals(-1, v.state.instField)
        assertTrue(v.state.ymPanel.advanced)
        v.tap(Action.DOWN)
        assertEquals(0, v.state.instField); assertEquals(2, v.state.instrument)
        assertSame(before, session.song)
    }

    @Test fun nonFmKeepsItsExactEditorAndAuditionKind() {
        val session = session()
        session.setYmInstrument(2, defaultYmPsg(2), "PSG")
        session.setYmInstrument(3, defaultYmPcm(3), "PCM")
        val v = view(session, 0)
        val before = session.song
        for (slot in 2..3) {
            YmInstrumentView.moveSlot(v, slot - v.state.instrument)
            assertFalse(YmSoundFace.friendly(session.song, v.state))
            val expected = if (slot == 2) YmInstrumentView.PSG_PAGES else YmInstrumentView.PCM_PAGES
            assertEquals(expected, YmInstrumentView.pages(session.song, v.state))
            YmSoundFace.selectMode(v, true)
            assertFalse(v.state.ymPanel.advanced)
        }
        assertSame(before, session.song)
    }

    /** Records draw calls that start inside the left column ([columnRight], below [columnTop]) while [ops] is set. */
    private class Canvas : Surface {
        var ops: MutableList<String>? = null
        var columnRight = 0f
        var columnTop = 0f
        private fun keep(l: Float, t: Float, op: () -> String) { if (l < columnRight - 0.01f && t >= columnTop - 0.01f) ops?.add(op()) }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = keep(l, t) { "fill $l $t $r $b $color" }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = keep(l, t) { "round $l $t $r $b $radius $color" }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) =
            keep(l, t) { "stroke $l $t $r $b $color $width $radius" }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) =
            keep(minOf(x0, x1), minOf(y0, y1)) { "line $x0 $y0 $x1 $y1 $color $width" }
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = keep(cx - radius, cy - radius) { "circle $cx $cy $radius $color" }
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) =
            keep(cx - radius, cy - radius) { "ring $cx $cy $radius $color $width" }
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
            if (count > 0) keep((0 until count).minOf { xs[it] }, (0 until count).minOf { ys[it] }) { "poly ${xs.take(count)} ${ys.take(count)} $color" }
        }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) =
            keep(x, top) { "text $s $x $top $h $color $face $bold $size $align" }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float): Float = s.length * size / 2
    }

    private class Panel(val session: EditorSession, val width: Int, val height: Int, density: Float = 1f, zoom: PaneZoom = PaneZoom.NORMAL) {
        val surface = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 2, zoom = zoom)
        val pane = PaneView(session, state, density, surface, { 0L }, {})
        val painter = YmSoundPainter(pane)
        val area = Rect()
        init { draw() }
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            pane.controller.normalise()
            painter.draw(surface, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
    }

    @Test fun dragUsesOriginalPatchSurvivesRepaintAndIsOneUndo() {
        val session = session()
        val panel = Panel(session, 960, 720)
        val before = session.song
        val row = panel.painter.controlRect(0)!!
        val y = row.centerY()
        panel.painter.down(panel.painter.valueX(0, 0f), y)
        panel.draw()
        panel.painter.move(panel.painter.valueX(0, 1f), y)
        panel.draw()
        panel.painter.move(panel.painter.valueX(0, 0.5f), y)
        panel.painter.up(0f, 0f)
        assertEquals(YmSoundMacros.apply(YmSoundControl.BRIGHTNESS, before.ymInstrument(1) as YmFmInstrument, 64), session.song.ymInstrument(1))
        session.undo()
        assertSame(before, session.song)
    }

    @Test fun undoModeAndSlotChangesTerminateOwnedDrags() {
        val session = session()
        val panel = Panel(session, 960, 720)
        val before = session.song
        fun start() { panel.draw(); panel.painter.down(panel.painter.valueX(0, 0f), panel.painter.controlRect(0)!!.centerY()) }
        start()
        session.undo()
        panel.painter.move(panel.painter.valueX(0, 1f), 0f)
        assertSame(before, session.song)
        panel.painter.up(0f, 0f)
        start()
        YmSoundFace.selectMode(panel.pane.controller, true)
        val switched = session.song
        panel.painter.move(panel.painter.valueX(0, 1f), 0f)
        assertSame(switched, session.song)
        session.undo()
        assertSame(before, session.song)
        YmSoundFace.selectMode(panel.pane.controller, false)
        start()
        YmInstrumentView.moveSlot(panel.pane.controller, 1)
        val moved = session.song
        panel.painter.move(999f, 0f)
        assertSame(moved, session.song)
        session.undo()
        assertSame(before, session.song)
    }

    @Test fun compactControlsAndNamedSlotPagingRemainTouchSizedWithoutEditing() {
        val session = session()
        val before = session.song
        for ((width, height, density) in listOf(Triple(360, 270, 1f), Triple(1024, 768, 2f), Triple(1240, 1080, 2.3f))) {
            for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) {
                val panel = Panel(session, width, height, density, zoom)
                panel.pane.palette = Palette.CONTRAST
                panel.draw()
                if ((0..4).any { panel.painter.controlRect(it) == null }) {
                    // Hidden controls must be reachable through touch alone, not by assigning instField.
                    panel.state.instField = 2
                    panel.draw()
                    repeat(5) { i ->
                        assertNotNull(panel.painter.controlRect(i))
                        val next = requireNotNull(panel.painter.navigationRect(true))
                        panel.tap(next); panel.draw()
                        assertEquals((i + 1) % 5 + 2, panel.state.instField)
                    }
                }
                for (i in 0..4) {
                    panel.state.instField = i + 2
                    panel.draw()
                    val r = panel.painter.controlRect(i)!!
                    assertTrue(r.width() >= 48 * density - .001f)
                    assertTrue(r.height() >= 48 * density - .001f)
                    assertTrue(r.top >= panel.area.top && r.bottom <= panel.area.bottom)
                    panel.painter.navigationRect(true)?.let { nav ->
                        assertTrue("navigation $width x $height/$density/$zoom: ${nav.height()}",
                            nav.height() >= 48 * density - .001f)
                        panel.tap(nav); panel.draw()
                        assertEquals((i + 1) % 5 + 2, panel.state.instField)
                    }
                }
                assertNotNull(panel.painter.slotRect(1))
                assertNotNull(panel.painter.slotRect(2))
                val field = panel.state.instField
                val stepper = panel.painter.slotNavigationRect(true)!!
                assertTrue(stepper.width() >= 48 * density - .001f && stepper.height() >= 48 * density - .001f)
                panel.tap(stepper); panel.draw()
                assertEquals(2, panel.state.instrument)
                assertEquals(field, panel.state.instField)
                assertNotNull(panel.painter.slotRect(2))
                assertSame(before, session.song)
            }
        }
    }

    @Test fun soundAndAdvancedDrawTheExactSameLeftColumn() {
        val session = session()
        session.setYmInstrument(2, patch(2).named("Second"), "Fixture")
        session.setYmInstrument(4, defaultYmPsg(4), "PSG")
        session.setYmInstrument(5, defaultYmPcm(5), "PCM")
        for ((width, height, density) in listOf(Triple(360, 270, 1f), Triple(960, 720, 1f), Triple(1024, 768, 2f))) {
            for (zoom in listOf(PaneZoom.NORMAL, PaneZoom.LARGEST)) for (slot in listOf(1, 40)) for (field in listOf(2, 5)) {
                val panel = Panel(session, width, height, density, zoom)
                panel.state.instrument = slot
                fun column(advanced: Boolean): List<String> {
                    YmSoundFace.selectMode(panel.pane.controller, advanced)
                    panel.state.instField = field
                    panel.draw()
                    panel.surface.columnRight = panel.painter.slotRect(slot)!!.right
                    panel.surface.columnTop = panel.painter.modeRect(false)!!.bottom
                    val ops = ArrayList<String>()
                    panel.surface.ops = ops
                    panel.draw()
                    panel.surface.ops = null
                    return ops
                }
                val sound = column(false)
                val advanced = column(true)
                assertTrue(sound.any { it.startsWith("text Sound ${EditorSession.hex2(slot)} ") })
                assertTrue(sound.any { it.startsWith("text ${EditorSession.hex2(slot)} ") })
                assertEquals("$width x $height/$density/$zoom slot $slot field $field", advanced, sound)
            }
        }
    }

    @Test fun sharedColumnTapsChooseSlotsKeepingSoundFocusWhileAdvancedTapsStillFocusTheSlotRow() {
        val session = session()
        session.setYmInstrument(3, patch(1), "Fixture")
        val before = session.song
        val panel = Panel(session, 960, 720)
        panel.state.instField = 4
        panel.draw()
        panel.tap(panel.painter.slotRect(3)!!); panel.draw()
        assertEquals(3, panel.state.instrument); assertEquals(4, panel.state.instField)
        assertFalse(panel.state.ymPanel.advanced)
        panel.tap(panel.painter.slotNavigationRect(false)!!); panel.draw()
        assertEquals(2, panel.state.instrument); assertEquals(4, panel.state.instField)
        panel.tap(panel.painter.nameTarget()!!)
        assertEquals(1, panel.state.instField)
        panel.draw()
        val fifth = panel.painter.slotRect(5)!!
        val bounds = listOf(fifth.left, fifth.top, fifth.right, fifth.bottom)
        YmSoundFace.selectMode(panel.pane.controller, true)
        panel.draw()
        val advanced = panel.painter.slotRect(5)!!
        assertEquals(bounds, listOf(advanced.left, advanced.top, advanced.right, advanced.bottom))
        panel.tap(advanced)
        assertEquals(5, panel.state.instrument); assertEquals(0, panel.state.instField)
        assertTrue(panel.state.ymPanel.advanced)
        assertSame(before, session.song)
    }

    @Test fun unavailableControlsAreReadOnlyByTouchAndController() {
        val session = session(patch(7))
        val panel = Panel(session, 360, 270)
        val before = session.song
        for (control in listOf(YmSoundControl.BRIGHTNESS, YmSoundControl.HARMONICS)) {
            panel.state.instField = control.ordinal + 2
            panel.draw()
            panel.tap(panel.painter.controlRect(control.ordinal)!!)
            panel.pane.controller.press(Action.EDIT)
            panel.pane.controller.tap(Action.RIGHT)
            panel.pane.controller.release(Action.EDIT)
            assertSame(before, session.song)
        }
        session.setYmInstrument(1, patch().withOperator(1, patch().operators[1].copy(ssgEg = 10)), "SSG")
        val special = session.song
        panel.state.instField = YmSoundControl.CONTOUR.ordinal + 2
        panel.draw()
        panel.tap(panel.painter.controlRect(YmSoundControl.CONTOUR.ordinal)!!)
        assertSame(special, session.song)
    }

    @Test fun touchAndControllerHaveIdenticalLoweringAndHeldModeTouchCannotWrite() {
        val touched = session()
        val controlled = session()
        val panel = Panel(touched, 960, 720)
        val r = panel.painter.controlRect(0)!!
        panel.painter.down(panel.painter.valueX(0, 0.5f), r.centerY())
        panel.painter.up(0f, 0f)
        val v = view(controlled)
        val initial = YmSoundMacros.value(YmSoundControl.BRIGHTNESS, controlled.song.ymInstrument(1) as YmFmInstrument)!!
        v.press(Action.EDIT)
        repeat(initial - 64) { v.tap(Action.LEFT) }
        v.release(Action.EDIT)
        assertArrayEquals(YmLowering.lower(controlled.song), YmLowering.lower(touched.song))
        val before = touched.song
        panel.pane.controller.press(Action.EDIT)
        panel.tap(panel.painter.modeRect(true)!!)
        panel.draw()
        panel.pane.controller.tap(Action.RIGHT)
        panel.pane.controller.release(Action.EDIT)
        assertSame(before, touched.song)
        panel.tap(panel.painter.modeRect(false)!!)
        panel.draw()
        assertFalse(panel.state.ymPanel.advanced)
        assertSame(before, touched.song)
    }
}
