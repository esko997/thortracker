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
        val v = controller(2)
        val before = session.song
        val export = GbSystem.exports.first { it.extension == "uge" }
        val bytes = export.export(before)
        assertTrue(GbSoundView.friendly(v.state))
        v.tap(Action.LEFT)
        assertEquals(1, v.state.instField)
        v.tap(Action.LEFT)
        assertEquals(-1, v.state.instField)
        v.tap(Action.RIGHT) // Bare Left/Right leave Sound's mode row through the ring; they never switch mode.
        assertFalse(v.state.gbPanel.advanced)
        assertEquals(1, v.state.instField)
        v.tap(Action.LEFT)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertTrue(v.state.gbPanel.advanced)
        assertEquals(-1, v.state.instField)
        v.tap(Action.LEFT) // Advanced keeps its precise mode row: bare Left returns to Sound.
        assertFalse(v.state.gbPanel.advanced)
        assertEquals(-1, v.state.instField)
        v.tap(Action.EDIT)
        assertTrue(v.state.gbPanel.advanced)
        v.tap(Action.EDIT)
        assertFalse(v.state.gbPanel.advanced)
        v.press(Action.EDIT)
        v.tap(Action.LEFT) // Selecting the already-active Sound tab consumes the hold, not a release toggle.
        v.release(Action.EDIT)
        assertFalse(v.state.gbPanel.advanced)
        assertEquals(-1, v.state.instField)
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
            if ((0..3).any { p.painter.lcd.controlRect(it) == null }) {
                assertNotNull(p.painter.lcd.navigationRect(true))
                assertNotNull(p.painter.lcd.navigationRect(false))
            }
            for (i in 0..3) {
                val rect = p.painter.lcd.controlRect(i)!!
                assertInside(rect, p.area, 48 * d)
                p.painter.lcd.navigationRect(true)?.let { next ->
                    for (forward in listOf(false, true)) assertInside(p.painter.lcd.navigationRect(forward)!!, p.area, 48 * d)
                    p.tap(next)
                    p.draw()
                    assertEquals((i + 1) % 4 + 2, p.state.instField)
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
    fun soundUpDownChooseSlotsAndLeftRightRingModeNameAndControlsInEveryBank() {
        val before = session.song
        for (kind in GbBank.entries) {
            val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT, bank = kind.ordinal, instField = 3))
            assertTrue(GbSoundView.friendly(v.state))
            v.tap(Action.DOWN); v.tap(Action.DOWN)
            assertEquals(3, v.state.instrument)
            assertEquals("$kind: choosing a slot keeps the focused control", 3, v.state.instField)
            v.tap(Action.UP)
            assertEquals(2, v.state.instrument)
            val last = GbMusicalMacros.count(kind) + 1
            for (field in (4..last) + listOf(-1, 1, 2, 3)) {
                v.tap(Action.RIGHT)
                assertEquals("$kind ring forward", field, v.state.instField)
            }
            for (field in listOf(2, 1, -1) + (last downTo 3)) {
                v.tap(Action.LEFT)
                assertEquals("$kind ring back", field, v.state.instField)
            }
            repeat(3) { v.tap(Action.LEFT) }
            assertEquals(-1, v.state.instField)
            v.tap(Action.DOWN) // The mode row keeps its focus while Up/Down choose the slot.
            assertEquals(3, v.state.instrument)
            repeat(4) { v.tap(Action.UP) }
            assertEquals(1, v.state.instrument)
            assertEquals(-1, v.state.instField)
            assertTrue(GbSoundView.friendly(v.state))
            v.tap(Action.NEXT_CHANNEL) // L1/R1 still choose the bank.
            assertEquals((kind.ordinal + 1) % GbBank.entries.size, v.state.bank)
        }
        assertSame(before, session.song)
        assertFalse(session.canUndo)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun freshSoundEditsItsFirstControlAndNeverRestsOnTheSlotField() {
        val original = session.song.gb.pulse[0]
        val v = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT))
        assertEquals("a new pane's stored field", 0, v.state.instField)
        v.press(Action.EDIT); v.tap(Action.RIGHT); v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertEquals(2, v.state.instField)
        assertEquals("Edit+Right shapes the first control, not the slot", 1, v.state.instrument)
        assertEquals(original.copy(duty = 2), session.song.gb.pulse[0])
        v.tap(Action.NEXT_CHANNEL)
        v.tap(Action.DOWN)
        assertEquals(2, v.state.instField)
        val edited = session.song
        // Advanced keeps its slot field: a fresh precise pane's Edit+Right still chooses the next slot.
        val precise = ViewController(session, ViewState(kind = ViewKind.INSTRUMENT).apply { gbPanel.advanced = true })
        precise.press(Action.EDIT); precise.tap(Action.RIGHT); precise.release(Action.EDIT)
        assertEquals(0, precise.state.instField)
        assertEquals(2, precise.state.instrument)
        assertSame(edited, session.song)
        // Touch: a fresh Sound pane, slot and bank taps all leave a musical control focused.
        val p = panel()
        assertEquals(2, p.state.instField)
        p.tap(p.painter.slotRect(3))
        p.draw()
        p.tap(p.painter.tabRect(GbBank.NOISE))
        p.draw()
        assertEquals(3, p.state.instrument)
        assertEquals(2, p.state.instField)
        assertSame(edited, session.song)
    }

    @Test
    fun soundNameEditsLettersWithEditLeftRightAndMovesTheClampedCaretWithEditUpDown() {
        session.setInstrument(GbBank.PULSE, 1, session.song.gb.pulse[0].copy(name = "Lead"))
        val v = controller(1)
        v.press(Action.EDIT)
        repeat(4) { v.tap(Action.DOWN) }
        assertEquals(4, v.state.textPos)
        assertEquals("Edit+Down moves the caret, not the slot", 1, v.state.instrument)
        v.tap(Action.RIGHT); v.tap(Action.RIGHT)
        v.release(Action.EDIT)
        assertEquals("LeadB", session.song.gb.pulse[0].name)
        v.tap(Action.CLEAR)
        assertEquals("Lead", session.song.gb.pulse[0].name)
        v.press(Action.EDIT)
        repeat(40) { v.tap(Action.DOWN) }
        assertEquals(EditorSession.MAX_NAME - 1, v.state.textPos)
        v.tap(Action.UP)
        assertEquals(EditorSession.MAX_NAME - 2, v.state.textPos)
        repeat(40) { v.tap(Action.UP) }
        assertEquals(0, v.state.textPos)
        v.release(Action.EDIT)
        assertEquals("Lead", session.song.gb.pulse[0].name)
        assertTrue(heard.isEmpty())
        v.tap(Action.RIGHT) // Bare Left/Right leave the name through the ring.
        assertEquals(2, v.state.instField)
        assertEquals(0, v.state.textPos)
        val hints = ArrayList<org.thortracker.editor.Hint>()
        v.state.instField = 1
        GbSoundView.hints(v, hints)
        assertTrue(hints.any { it.text.startsWith("Move letter cursor") })
    }

    @Test
    fun soundEditUpDownStillShapesTheFocusedControlLikeEditLeftRight() {
        session.onAudition = { _, _, _, _ -> }
        val other = EditorSession(GbSystem.blank())
        val before = session.song
        val vertical = controller(2)
        val horizontal = ViewController(other, ViewState(kind = ViewKind.INSTRUMENT, instField = 2))
        vertical.press(Action.EDIT); vertical.tap(Action.UP); vertical.tap(Action.UP); vertical.tap(Action.DOWN); vertical.release(Action.EDIT)
        horizontal.press(Action.EDIT); horizontal.tap(Action.RIGHT); horizontal.tap(Action.RIGHT); horizontal.tap(Action.LEFT); horizontal.release(Action.EDIT)
        assertNotSame(before, session.song)
        assertEquals(other.song, session.song)
        assertEquals(1, vertical.state.instrument)
        session.undo()
        assertSame(before, session.song)
    }

    @Test
    fun advancedKeepsItsPreciseVerticalFieldsAndHorizontalSlots() {
        val v = controller(2)
        val before = session.song
        GbSoundView.setAdvanced(v, true)
        assertEquals(2, v.state.instField)
        v.tap(Action.DOWN)
        assertEquals(3, v.state.instField)
        assertEquals(1, v.state.instrument)
        v.tap(Action.RIGHT)
        assertEquals(2, v.state.instrument)
        assertEquals(3, v.state.instField)
        repeat(3) { v.tap(Action.UP) }
        assertEquals(0, v.state.instField)
        v.tap(Action.UP)
        assertEquals(-1, v.state.instField)
        v.tap(Action.DOWN); v.tap(Action.DOWN)
        assertEquals(1, v.state.instField)
        v.tap(Action.RIGHT) // Advanced's name keeps Left/Right as its letter cursor.
        assertEquals(1, v.state.textPos)
        assertEquals(2, v.state.instrument)
        assertTrue(v.state.gbPanel.advanced)
        assertSame(before, session.song)
    }

    @Test
    fun soundSlotTapsKeepTheFocusedControlWhileAdvancedSlotTapsFocusTheSlot() {
        val p = panel()
        val before = session.song
        p.state.instField = 4
        p.draw()
        p.tap(p.painter.slotRect(5))
        assertEquals(5, p.state.instrument)
        assertEquals(4, p.state.instField)
        val short = panel(320, 240, 1f)
        short.state.instField = 3
        short.draw()
        short.tap(short.painter.slotNavigationRect(true)!!)
        assertEquals(2, short.state.instrument)
        assertEquals(3, short.state.instField)
        p.tap(p.painter.modeRect(true))
        p.draw()
        p.tap(p.painter.slotRect(6))
        assertEquals(6, p.state.instrument)
        assertEquals(0, p.state.instField)
        assertSame(before, session.song)
    }

    @Test
    fun everyBankSharesTheAdvancedHeaderAndSidebarInBothFaces() {
        // Wide column, narrow grid on top, and short compact lists (cycling and tabbed banks).
        val displays = listOf(Triple(1240, 1080, 2.3f), Triple(400, 900, 1f), Triple(320, 240, 1f), Triple(640, 320, 1f))
        for ((w, h, d) in displays) for (kind in GbBank.entries) {
            val p = panel(w, h, d)
            p.state.bank = kind.ordinal
            p.state.instrument = 2
            p.state.instField = 2
            fun sidebar(): Pair<List<RecordingSurface.Op>, List<String>> {
                p.draw()
                val edge = p.painter.modeRect(false)
                val ops = p.surface.ops.filter { it.x < edge.left || it.y < edge.top }
                val targets = GbBank.entries.map { p.painter.tabRect(it).toString() } +
                    (1..HugeRules.INSTRUMENT_SLOTS).map { p.painter.slotRect(it).toString() } +
                    listOf(false, true).map { p.painter.slotNavigationRect(it).toString() }
                return ops to targets
            }
            val where = "${w}x$h@$d $kind"
            val sound = sidebar()
            assertTrue(where, GbSoundView.friendly(p.state))
            assertTrue("$where header", sound.first.any { it.text == "Sound 02" })
            assertTrue("$where slot names", sound.first.any { it.text == session.song.bank(kind)[1].name })
            p.state.gbPanel.advanced = true
            val advanced = sidebar()
            assertEquals(where, advanced.first, sound.first)
            assertEquals(where, advanced.second, sound.second)
        }
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
            surface.texts.clear(); surface.colors.clear(); surface.ops.clear()
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
        /** One primitive, located by its centre (text: anchor x and row centre) and fully described by [detail]. */
        data class Op(val x: Float, val y: Float, val text: String?, val color: Int, val detail: String)
        val texts = ArrayList<String>()
        val colors = HashSet<Int>()
        val ops = ArrayList<Op>()
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
            colors += color; ops += Op((l + r) / 2, (t + b) / 2, null, color, "rect $l $t $r $b")
        }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) {
            colors += color; ops += Op((l + r) / 2, (t + b) / 2, null, color, "round $l $t $r $b $radius")
        }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {
            ops += Op((l + r) / 2, (t + b) / 2, null, color, "stroke $l $t $r $b $width $radius")
        }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {
            ops += Op((x0 + x1) / 2, (y0 + y1) / 2, null, color, "line $x0 $y0 $x1 $y1 $width")
        }
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
            if (count > 0) ops += Op(xs[0], ys[0], null, color, "polyline ${xs.take(count)} ${ys.take(count)} $width")
        }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            texts += s; colors += color
            ops += Op(x, top + h / 2, s, color, "text $top $h $face $bold $size $align")
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }
}
