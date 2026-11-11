package org.thortracker.system.gb

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
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

/** Sound and Advanced are two editors of one patch, never conversions on entry. */
class GbPulseSoundViewTest {
    private val session = EditorSession(GbSystem.blank())
    private val heard = ArrayList<GbInstrument>()
    private var released = 0

    init {
        session.onAudition = { ch, _, slot, _ ->
            assertEquals(0, ch)
            heard += session.song.gb.pulse[slot - 1]
        }
        session.onReleaseAudition = { released++ }
    }

    private fun controller(field: Int = 2) = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, instField = field))
    private fun ViewController.tap(action: Action) { press(action); release(action) }

    @Test
    fun defaultModeAndBothControllerSwitchPathsPreserveExactBytesAndHistory() {
        val v = controller(0)
        val before = session.song
        val export = GbSystem.exports.first { it.extension == "uge" }
        val bytes = export.export(before)
        assertTrue(GbSoundView.friendly(v.state))
        v.tap(Action.UP)
        assertEquals(-1, v.state.instField)
        v.tap(Action.RIGHT)
        assertTrue(v.state.gbPanel.advanced)
        v.tap(Action.LEFT)
        assertFalse(v.state.gbPanel.advanced)
        v.tap(Action.EDIT)
        assertTrue(v.state.gbPanel.advanced)
        v.tap(Action.EDIT)
        assertFalse(v.state.gbPanel.advanced)
        v.press(Action.EDIT)
        v.tap(Action.LEFT) // Selecting the already-active Sound tab consumes the hold, not a release toggle.
        v.release(Action.EDIT)
        assertFalse(v.state.gbPanel.advanced)
        assertSame(before, session.song)
        assertArrayEquals(bytes, export.export(session.song))
        assertFalse(session.canUndo)
        assertTrue(heard.isEmpty())
        assertEquals(1, v.state.instrument)
        assertEquals(0, v.state.bank)
        val otherPane = ViewState(kind = ViewKind.INSTRUMENT)
        GbSoundView.setAdvanced(v, true)
        assertFalse(otherPane.gbPanel.advanced)
        assertTrue(v.state.snapshot().gbPanel.advanced)
        assertNull(v.state.snapshot().gbPanel.cancelTouch)
        assertNotSame(v.state.gbPanel, v.state.snapshot().gbPanel)
    }

    @Test
    fun customAdvancedPatchIsReadOnlyUntilAMusicalControlIsChosen() {
        val custom = GbInstrument(name = "My precise patch", kind = GbBank.PULSE, duty = 3, volume = 7,
            envelopeDirection = 1, envelopePace = 3, lengthEnabled = true, length = 19,
            sweepTime = 5, sweepShift = 2, sweepDirection = 1)
        session.setInstrument(GbBank.PULSE, 1, custom)
        val before = session.song
        val history = session.undoLabel
        val p = panel()
        assertNull(GbMusicalMacros.value(1, session.song.gb.pulse[0]))
        p.tap(p.painter.modeRect(true))
        p.draw()
        assertTrue(p.state.gbPanel.advanced)
        p.state.instField = 7
        p.tap(p.painter.modeRect(false))
        p.draw()
        assertFalse(p.state.gbPanel.advanced)
        p.tap(p.painter.modeRect(true))
        assertEquals(7, p.state.instField)
        assertSame(before, session.song)
        assertSame(before.gb.pulse[0], session.song.gb.pulse[0])
        assertEquals(history, session.undoLabel)
    }

    @Test
    fun heldControllerMacroChangesAreHeardAndUndoAsOneRealPatchEdit() {
        val v = controller(2)
        val before = session.song
        val original = before.gb.pulse[0]
        v.press(Action.EDIT)
        v.tap(Action.RIGHT)
        v.tap(Action.RIGHT)
        assertEquals(2, session.song.gb.pulse[0].duty)
        assertEquals(original.copy(duty = 2), session.song.gb.pulse[0])
        assertEquals(3, heard.size)
        v.release(Action.EDIT)
        assertTrue(released > 0)
        session.undo()
        assertSame(before, session.song)
        assertFalse(session.canUndo)
    }

    @Test
    fun sameSlotCanBeShapedMusicallyThenRefinedPreciselyAndResetMusically() {
        val v = controller(3)
        val before = session.song
        v.press(Action.AUDITION)
        v.press(Action.EDIT)
        // Factory pulse is Custom; first forward choice adopts Pluck, then Held, then Swell.
        repeat(3) { v.tap(Action.RIGHT) }
        v.release(Action.EDIT)
        assertEquals(3, session.song.gb.pulse[0].volume)
        assertEquals(1, session.song.gb.pulse[0].envelopeDirection)
        assertEquals(2, GbPulseMacros.value(GbPulseControl.SHAPE, session.song.gb.pulse[0]))
        val musical = session.song
        GbSoundView.setAdvanced(v, true)
        v.state.instField = 3 // Precise volume, not Sound's Shape.
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(4, session.song.gb.pulse[0].volume)
        GbSoundView.setAdvanced(v, false)
        assertEquals(3, v.state.instField)
        assertNull(GbPulseMacros.value(GbPulseControl.SHAPE, session.song.gb.pulse[0]))
        v.tap(Action.CLEAR)
        assertEquals(0, GbPulseMacros.value(GbPulseControl.SHAPE, session.song.gb.pulse[0]))
        assertTrue(heard.size >= 5)
        v.release(Action.AUDITION)
        session.undo() // Reset Shape.
        session.undo() // Precise volume.
        assertSame(musical, session.song)
        session.undo() // One held Shape gesture.
        assertSame(before, session.song)
    }

    @Test
    fun touchModeSwitchRetainsPhysicalHoldsWithoutEditingOnMoveOrRelease() {
        val p = panel()
        p.state.instField = 2
        val ctl = p.pane.controller
        ctl.press(Action.AUDITION)
        ctl.press(Action.EDIT)
        val before = session.song
        val hearings = heard.size
        val releases = released
        p.tap(p.painter.modeRect(true))
        assertTrue(ctl.editHeld)
        assertTrue(ctl.auditionHeld)
        assertEquals(releases, released)
        ctl.tap(Action.RIGHT)
        ctl.release(Action.EDIT)
        assertSame(before, session.song)
        assertEquals(hearings, heard.size)
        assertFalse(session.canUndo)
        ctl.release(Action.AUDITION)
        // A fresh physical press edits the precise editor, not a permanently blocked controller.
        p.state.instField = 3
        ctl.press(Action.EDIT); ctl.tap(Action.LEFT); ctl.release(Action.EDIT)
        assertEquals(before.gb.pulse[0].volume - 1, session.song.gb.pulse[0].volume)
    }

    @Test
    fun waveAndNoiseOpenTheirTypedSoundFacesAndBanksKeepTheirBindings() {
        val p = panel()
        val before = session.song
        for (kind in listOf(GbBank.WAVE, GbBank.NOISE)) {
            p.pane.controller.tap(Action.NEXT_CHANNEL)
            assertEquals(kind.ordinal, p.state.bank)
            assertTrue(GbSoundView.friendly(p.state))
            p.draw()
            assertNotNull(p.painter.lcd.controlRect(0))
            p.tap(p.painter.modeRect(true))
            p.draw()
            assertFalse(GbSoundView.friendly(p.state))
            assertNotNull(p.painter.rowRect(1))
            p.tap(p.painter.modeRect(false))
        }
        p.pane.controller.tap(Action.NEXT_CHANNEL)
        assertTrue(GbSoundView.friendly(p.state))
        assertSame(before, session.song)
        assertFalse(session.canUndo)
    }

    @Test
    fun touchDragHearsUpdatesGroupsUndoAndHistoryInvalidatesTheOldPointer() {
        val p = panel()
        val control = GbPulseControl.CHARACTER
        val row = p.painter.lcd.controlRect(control.ordinal)!!
        val before = session.song
        p.pane.controller.press(Action.AUDITION)
        p.painter.down(p.painter.lcd.valueX(control.ordinal, 0f), row.centerY())
        p.painter.move(p.painter.lcd.valueX(control.ordinal, 0.5f), row.centerY())
        p.painter.move(p.painter.lcd.valueX(control.ordinal, 1f), row.centerY())
        assertEquals(2, session.song.gb.pulse[0].duty)
        assertEquals(3, heard.size)
        p.painter.up(0f, 0f)
        session.undo()
        assertSame(before, session.song)
        assertFalse(session.canUndo)
        p.painter.down(p.painter.lcd.valueX(control.ordinal, 1f), row.centerY())
        session.undo()
        p.painter.move(p.painter.lcd.valueX(control.ordinal, 0.5f), row.centerY())
        p.painter.up(0f, 0f)
        assertSame(before, session.song)
        assertTrue(session.canRedo)
        p.pane.controller.release(Action.AUDITION)
    }

    @Test
    fun slotAndModeChangesCancelOldTargetsBeforeAnotherGestureWithoutWaitingForPaint() {
        val p = panel()
        val control = GbPulseControl.CHARACTER
        val y = p.painter.lcd.controlRect(control.ordinal)!!.centerY()
        val x = p.painter.lcd.valueX(control.ordinal, 0.5f)
        val oldSlot = session.song.gb.pulse[1]
        p.painter.down(x, y)
        val edited = session.song
        p.state.instrument = 2
        GbSoundView.publish(p.pane.controller)
        p.painter.move(p.painter.lcd.valueX(control.ordinal, 1f), y)
        p.painter.up(0f, 0f)
        assertSame(edited, session.song)
        assertSame(oldSlot, session.song.gb.pulse[1])
        session.undo()
        p.state.instrument = 1
        p.painter.down(x, y)
        val afterDrag = session.song
        GbSoundView.setAdvanced(p.pane.controller, true)
        p.state.instField = 3
        p.pane.controller.press(Action.EDIT)
        p.pane.controller.tap(Action.LEFT)
        p.pane.controller.release(Action.EDIT)
        p.painter.move(0f, y)
        p.painter.up(0f, 0f)
        session.undo()
        assertSame("New precise edit must not join the cancelled pointer's Undo", afterDrag, session.song)
        session.undo()
        assertEquals(0, session.song.gb.pulse[0].duty)
    }

    @Test
    fun preciseDragIsAlsoCancelledWhenReturningToSound() {
        val p = panel()
        p.tap(p.painter.modeRect(true)); p.draw()
        val row = p.painter.rowRect(3)!!
        p.painter.down(p.painter.railX(3, 0.5f)!!, row.centerY())
        val after = session.song
        GbSoundView.setAdvanced(p.pane.controller, false)
        p.painter.move(p.painter.railX(3, 1f)!!, row.centerY())
        p.painter.up(0f, 0f)
        assertSame(after, session.song)
        assertFalse(p.state.gbPanel.advanced)
    }

    @Test
    fun shortPanesReachEveryControlAndSlotAtPhysicalTouchSizeWithoutEditing() {
        val displays = listOf(Triple(320, 240, 1f), Triple(360, 270, 1f), Triple(640, 320, 1f), Triple(1024, 600, 2f))
        for ((w, h, d) in displays) for (zoom in PaneZoom.entries) {
            val p = panel(w, h, d, zoom)
            val before = session.song
            p.state.instField = 2
            p.draw()
            for (i in 0..3) {
                val rect = p.painter.lcd.controlRect(i)!!
                assertInside(rect, p.area, 48 * d)
                p.painter.lcd.navigationRect(true)?.let { next ->
                    for (forward in listOf(false, true)) assertInside(p.painter.lcd.navigationRect(forward)!!, p.area, 48 * d)
                    p.tap(next)
                    p.draw()
                }
            }
            assertEquals(2, p.state.instField)
            for (forward in listOf(false, true)) assertInside(p.painter.modeRect(forward), p.area, 48 * d)
            val slotNext = p.painter.slotNavigationRect(true)!!
            assertInside(slotNext, p.area, 48 * d)
            p.tap(slotNext)
            assertEquals(2, p.state.instrument)
            assertSame(before, session.song)
        }
    }

    @Test
    fun compactBankShowsSelectableNeighboursAndKeepsLaterSlotsVisible() {
        val p = panel()
        val before = session.song
        assertInside(p.painter.slotRect(2), p.area, 48 * 2.3f)
        p.tap(p.painter.slotRect(2))
        assertEquals(2, p.state.instrument)
        repeat(13) {
            p.tap(p.painter.slotNavigationRect(true)!!)
            p.draw()
            assertInside(p.painter.slotRect(p.state.instrument), p.area, 48 * 2.3f)
        }
        assertEquals(15, p.state.instrument)
        assertSame(before, session.song)
    }

    @Test
    fun roomySurfaceKeepsFourMusicalControlsAndThemeRolesWithoutRegisterText() {
        for (palette in Palette.all) {
            val p = panel()
            p.pane.palette = palette
            p.state.instField = 4 // Length exposes Custom -> Pluck before editing.
            p.draw()
            assertFalse(p.painter.lcd.compact)
            for (control in GbPulseControl.entries) assertInside(p.painter.lcd.controlRect(control.ordinal)!!, p.area, 48 * 2.3f)
            if (palette == Palette.CONTRAST) assertTrue(p.surface.colors.contains(palette.TEXT))
        }
    }

    @Test
    fun waveAndNoiseTouchMatchControllerNativeBytesAndExportWithOneUndoPerGesture() {
        val export = GbSystem.exports.first { it.extension == "uge" }
        for (kind in listOf(GbBank.WAVE, GbBank.NOISE)) {
            val source = GbInstrument(name = "Exact nonpulse", kind = kind, volume = 9, envelopePace = 3,
                envelopeDirection = 1, length = 17, lengthEnabled = true, wave = 11, waveVolume = 3,
                noiseWidth = 1, duty = 3, sweepTime = 5, sweepShift = 2)
            session.setInstrument(kind, 1, source)
            val p = panel()
            p.state.bank = kind.ordinal
            for (control in 0..2) {
                p.state.instField = control + 2
                p.draw()
                val before = session.song
                val other = EditorSession(before)
                val keyboard = ViewController(other, ViewState(kind = ViewKind.INSTRUMENT,
                    bank = kind.ordinal, instField = control + 2))
                val max = GbMusicalMacros.steps(kind, control) - 1
                val target = if (kind == GbBank.NOISE && control == 0) 0 else max
                val row = p.painter.lcd.controlRect(control)!!
                p.painter.down(p.painter.lcd.valueX(control, 0f), row.centerY())
                for (rank in 1..target) p.painter.move(p.painter.lcd.valueX(control, rank.toFloat() / max), row.centerY())
                p.painter.up(0f, 0f)
                keyboard.press(Action.EDIT)
                repeat(max + 1) {
                    val current = GbMusicalMacros.value(control, GbSoundView.sound(other.song, keyboard.state)!!)
                    if (current != target) keyboard.tap(if (current != null && current > target) Action.LEFT else Action.RIGHT)
                }
                keyboard.release(Action.EDIT)
                assertEquals(other.song, session.song)
                assertArrayEquals(export.export(other.song), export.export(session.song))
                val nativeTouch = org.thortracker.backend.HugeLowering.lower(session.song)
                val nativeKeys = org.thortracker.backend.HugeLowering.lower(other.song)
                assertArrayEquals(if (kind == GbBank.WAVE) nativeKeys.wave[0] else nativeKeys.noise[0],
                    if (kind == GbBank.WAVE) nativeTouch.wave[0] else nativeTouch.noise[0])
                assertSame(before.gb.waves, session.song.gb.waves)
                session.undo()
                assertSame(before, session.song)
            }
        }
    }

    @Test
    fun nonpulseModeSwitchesPreserveCustomPatchesAndHeldEditCannotLeakAcrossFaces() {
        session.onAudition = { _, _, _, _ -> }
        val export = GbSystem.exports.first { it.extension == "uge" }
        for (kind in listOf(GbBank.WAVE, GbBank.NOISE)) {
            session.setInstrument(kind, 1, GbInstrument(kind = kind, volume = 7, envelopePace = 5,
                length = 19, lengthEnabled = true, wave = 9, waveVolume = 2, noiseWidth = 1))
            val p = panel()
            p.state.bank = kind.ordinal
            p.state.instField = 4
            p.draw()
            val before = session.song
            val bytes = export.export(before)
            val undo = session.undoLabel
            val ctl = p.pane.controller
            ctl.press(Action.EDIT)
            p.tap(p.painter.modeRect(true))
            ctl.tap(Action.RIGHT)
            p.draw()
            p.tap(p.painter.modeRect(false))
            p.draw()
            val row = p.painter.lcd.controlRect(2)!!
            p.painter.down(p.painter.lcd.valueX(2, 1f), row.centerY())
            p.painter.move(p.painter.lcd.valueX(2, 0f), row.centerY())
            p.painter.up(0f, 0f)
            ctl.release(Action.EDIT)
            assertSame(before, session.song)
            assertEquals(undo, session.undoLabel)
            assertArrayEquals(bytes, export.export(session.song))
            assertNull(GbMusicalMacros.value(2, GbSoundView.sound(session.song, p.state)!!))
        }
    }

    @Test
    fun compactNonpulseFacesReachEveryControlAndAllFifteenNamedSlots() {
        for (kind in listOf(GbBank.WAVE, GbBank.NOISE)) {
            val p = panel(320, 240, 1f)
            p.state.bank = kind.ordinal
            p.state.instField = 2
            val before = session.song
            repeat(3) { control ->
                p.draw()
                assertInside(p.painter.lcd.controlRect(control)!!, p.area, 48f)
                p.tap(p.painter.lcd.navigationRect(true)!!)
            }
            repeat(15) { slot ->
                p.draw()
                assertEquals(slot + 1, p.state.instrument)
                assertInside(p.painter.slotRect(slot + 1), p.area, 48f)
                if (slot < 14) p.tap(p.painter.slotNavigationRect(true)!!)
            }
            assertSame(before, session.song)
        }
    }

    private fun assertInside(r: Rect, area: Rect, minimum: Float) {
        assertTrue("width ${r.width()} < $minimum", r.width() + 0.1f >= minimum)
        assertTrue("height ${r.height()} < $minimum", r.height() + 0.1f >= minimum)
        assertTrue("outside pane", r.left >= area.left && r.top >= area.top && r.right <= area.right && r.bottom <= area.bottom + 0.1f)
    }

    private inner class Panel(val width: Int, val height: Int, density: Float, zoom: PaneZoom) {
        val state = ViewState(kind = ViewKind.INSTRUMENT, zoom = zoom)
        val surface = RecordingSurface()
        val pane = PaneView(session, state, density, surface, { 0L }, {})
        val painter = SoundPainter(pane)
        val area = Rect()
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            surface.texts.clear(); surface.colors.clear()
            pane.controller.normalise()
            painter.draw(surface, area)
        }
        fun tap(r: Rect) {
            assertTrue(painter.down(r.centerX(), r.centerY()))
            painter.up(r.centerX(), r.centerY())
        }
    }

    private fun panel(w: Int = 1240, h: Int = 1080, d: Float = 2.3f, zoom: PaneZoom = PaneZoom.NORMAL) = Panel(w, h, d, zoom).also { it.draw() }

    private class RecordingSurface : Surface {
        val texts = ArrayList<String>()
        val colors = HashSet<Int>()
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { colors += color }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { colors += color }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {}
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) { texts += s; colors += color }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }
}
