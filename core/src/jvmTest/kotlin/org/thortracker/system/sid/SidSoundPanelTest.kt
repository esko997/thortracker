package org.thortracker.system.sid

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.GtSong
import org.thortracker.backend.SidLowering
import org.thortracker.backend.SidPlayerImage
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Palette
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class SidSoundPanelTest {
    private fun arbitrary() = SidInstrument(
        name = "My SID patch", attack = 3, decay = 11, sustain = 6, release = 13,
        restart = SidRestart.LEGATO, gateTimer = 3, firstWave = 0xFF,
        vibrato = SidVibrato(7, 5, 2, true),
        wave = SidProgram(listOf(SidRow.wave(0x31, 12), SidRow.delay(3), SidRow.wave(0x41), SidRow.jump(2)), 23),
        pulse = SidProgram(listOf(SidRow.pulseWidth(0x357), SidRow.modulate(13, 7), SidRow.jump(2)), 34),
        filter = SidProgram(listOf(SidRow.filter(SidTables.BANDPASS, 6, 2), SidRow.cutoff(73), SidRow.modulate(6, -3)), 42),
    )
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun ViewController.hold(a: Action, vararg moves: Action) {
        press(a); for (m in moves) tap(m); release(a)
    }

    @Test fun nameCaretMovesWithEditUpDownAndStopsAtTheLastEditableCharacter() {
        val s = EditorSession(SidSystem.blank())
        val original = "ABCDEFGHIJKLMNOP"
        s.setSidInstrument(1, SidInstrument(name = original))
        val p = panel(s)
        p.state.instField = 1
        val before = s.song
        p.ctl.hold(Action.EDIT, *Array(32) { Action.DOWN })
        assertEquals(SidInstrument.NAME_MAX - 1, p.state.textPos)
        assertEquals(1, p.state.instField)
        assertEquals(1, p.state.instrument)
        assertSame(before, s.song) // Moving between letters never edits.
        p.ctl.hold(Action.EDIT, Action.RIGHT)
        assertEquals("ABCDEFGHIJKLMNOQ", s.song.sid.instruments[0].name)
        s.undo()
        assertEquals(original, s.song.sid.instruments[0].name)
        p.ctl.hold(Action.EDIT, Action.UP)
        assertEquals(SidInstrument.NAME_MAX - 2, p.state.textPos)
        p.ctl.hold(Action.EDIT, *Array(32) { Action.UP })
        assertEquals(0, p.state.textPos)
        assertSame(before, s.song)
        assertTrue(p.ctl.hints().any { it.text == "Previous / next letter" })
        assertTrue(p.ctl.hints().any { it.text == "Change letter" })
        // Bare directions on the name navigate: vertical chooses the slot, horizontal walks the fields.
        p.ctl.tap(Action.DOWN)
        assertEquals(2, p.state.instrument)
        assertEquals(1, p.state.instField)
        p.ctl.tap(Action.RIGHT)
        assertEquals(2, p.state.instField)
        p.ctl.tap(Action.LEFT)
        assertEquals(1, p.state.instField)
        assertEquals(0, p.state.textPos)
        p.ctl.tap(Action.LEFT)
        assertEquals(-1, p.state.instField)
        assertSame(before, s.song)
    }

    @Test fun soundUpDownChoosesSlotsAndLeftRightWalksModeNameAndControls() {
        val s = EditorSession(SidSystem.blank())
        val p = panel(s)
        val before = s.song
        p.state.instField = 4
        p.ctl.tap(Action.DOWN)
        assertEquals(2, p.state.instrument)
        assertEquals(4, p.state.instField)
        p.ctl.tap(Action.UP)
        p.ctl.tap(Action.UP)
        assertEquals(1, p.state.instrument)
        assertEquals(4, p.state.instField)
        val ring = ArrayList<Int>()
        repeat(8) { p.ctl.tap(Action.RIGHT); ring += p.state.instField }
        assertEquals(listOf(5, 6, -1, 1, 2, 3, 4, 5), ring)
        repeat(6) { p.ctl.tap(Action.LEFT) }
        assertEquals(6, p.state.instField)
        // The mode row keeps its focus while Up/Down choose the slot; only Edit changes the face.
        p.state.instField = -1
        p.ctl.tap(Action.DOWN)
        assertEquals(2, p.state.instrument)
        assertEquals(-1, p.state.instField)
        assertFalse(p.state.sidPanel.advanced)
        p.ctl.hold(Action.EDIT, Action.RIGHT)
        assertTrue(p.state.sidPanel.advanced)
        assertEquals(-1, p.state.instField)
        p.ctl.hold(Action.EDIT, Action.LEFT)
        assertFalse(p.state.sidPanel.advanced)
        assertEquals(-1, p.state.instField)
        p.ctl.tap(Action.EDIT)
        assertTrue(p.state.sidPanel.advanced)
        p.ctl.tap(Action.EDIT)
        assertFalse(p.state.sidPanel.advanced)
        assertEquals(-1, p.state.instField)
        p.ctl.tap(Action.LEFT)
        assertEquals(6, p.state.instField)
        assertFalse(p.state.sidPanel.advanced)
        // A fresh view starts on the slot (field 0); the walk joins the ring from there.
        p.state.instField = 0
        p.ctl.tap(Action.RIGHT)
        assertEquals(2, p.state.instField)
        assertSame(before, s.song)
    }

    @Test fun advancedKeepsItsPreciseRowNavigation() {
        val s = EditorSession(SidSystem.blank())
        s.setSidInstrument(1, SidInstrument(name = "ABC"))
        val p = panel(s)
        SidSoundFace.selectMode(p.ctl, true)
        p.state.instField = 0
        val before = s.song
        p.ctl.tap(Action.RIGHT)
        assertEquals(2, p.state.instrument)
        assertEquals(0, p.state.instField)
        p.ctl.tap(Action.LEFT)
        p.ctl.tap(Action.DOWN)
        assertEquals(SidInstrumentView.Row.NAME, SidInstrumentView.row(p.state))
        p.ctl.tap(Action.RIGHT)
        assertEquals(1, p.state.textPos)
        assertEquals(1, p.state.instrument)
        p.ctl.tap(Action.UP)
        assertEquals(0, p.state.instField)
        p.ctl.tap(Action.UP)
        assertEquals(-1, p.state.instField)
        p.ctl.tap(Action.DOWN)
        assertEquals(0, p.state.instField)
        assertTrue(p.state.sidPanel.advanced)
        assertSame(before, s.song)
    }

    @Test fun soundAndAdvancedShareTheIdenticalListAndItsHitTestingOnBothChips() {
        for (chip in SidChip.entries) {
            val s = EditorSession(SidSystem.blank())
            s.editSidSound("Chip") { it.copy(chip = chip) }
            s.setSidInstrument(1, arbitrary())
            s.setSidInstrument(30, SidInstrument(name = "Far away"))
            for ((width, height) in listOf(1000 to 700, 480 to 300)) {
                val p = panel(s, width, height)
                p.state.instrument = 30
                for ((soundField, advancedField) in listOf(3 to 2, 0 to 0, -1 to -1)) {
                    SidSoundFace.selectMode(p.ctl, false)
                    p.state.instField = soundField
                    p.draw()
                    val listRight = p.painter.slotRect(30)!!.right
                    val sound = p.surface.ops.filter { it.first < listRight - 0.01f }.map { it.second }
                    SidSoundFace.selectMode(p.ctl, true)
                    p.state.instField = advancedField
                    p.draw()
                    val advanced = p.surface.ops.filter { it.first < listRight - 0.01f }.map { it.second }
                    assertEquals("$chip ${width}x$height", sound, advanced)
                    assertTrue(sound.any { it.contains("Far away") })
                    assertTrue(sound.any { it.contains("C64 SID · ${chip.label}") })
                }
                // One hit test: Sound keeps the focused control, Advanced moves to its Slot row.
                val row = p.painter.slotRect(29)!!
                SidSoundFace.selectMode(p.ctl, false)
                p.state.instField = 5
                p.draw()
                assertEquals(row.bounds(), p.painter.slotRect(29)!!.bounds())
                p.tap(row)
                assertEquals(29, p.state.instrument)
                assertEquals(5, p.state.instField)
                SidSoundFace.selectMode(p.ctl, true)
                p.state.instField = 3
                p.draw()
                p.tap(p.painter.slotRect(30)!!)
                assertEquals(30, p.state.instrument)
                assertEquals(0, p.state.instField)
            }
        }
    }

    private fun Rect.bounds() = listOf(left, top, right, bottom)

    @Test fun arbitraryReadbackAndEveryFaceSwitchAreReadOnlyOnBothChips() {
        for (chip in SidChip.entries) {
            val s = EditorSession(SidSystem.blank())
            s.editSidSound("Chip") { it.copy(chip = chip) }
            s.setSidInstrument(1, arbitrary())
            val before = s.song
            val patch = before.sid.instruments[0]
            val p = panel(s)
            assertFalse(p.state.sidPanel.advanced)
            assertEquals(listOf(null, null, null, 13, null), SidSoundControl.entries.map { SidSoundMacros.value(it, patch) })
            repeat(4) {
                p.tap(p.painter.modeRect(true)); p.draw()
                p.tap(p.painter.modeRect(false)); p.draw()
            }
            assertSame(before, s.song)
            assertTrue(p.surface.texts.contains("Custom"))
            assertTrue(p.surface.texts.contains("Shared filter · last start wins"))
            assertTrue(p.surface.texts.contains("Custom routing · see Advanced"))
        }
    }

    @Test fun eachMacroOwnsOnlyItsDeclaredFieldsAndKeepsProgramLocations() {
        val original = arbitrary()
        for (control in SidSoundControl.entries) for (index in 0 until SidSoundMacros.steps(control)) {
            val after = SidSoundMacros.apply(control, original, index)
            val restored = when (control) {
                SidSoundControl.VOICE -> after.copy(wave = original.wave)
                SidSoundControl.PULSE -> after.copy(pulse = original.pulse)
                SidSoundControl.CONTOUR -> after.copy(attack = original.attack, decay = original.decay, sustain = original.sustain)
                SidSoundControl.TAIL -> after.copy(release = original.release)
                SidSoundControl.COLOR -> after.copy(filter = original.filter)
            }
            assertEquals(control.toString(), original, restored)
            assertEquals(original.wave.at, after.wave.at)
            assertEquals(original.pulse.at, after.pulse.at)
            assertEquals(original.filter.at, after.filter.at)
            assertSame(after, SidSoundMacros.apply(control, after, index))
        }
    }

    @Test fun matchingExplicitStopsArePreservedAndComplexLookalikesStayCustom() {
        for (control in listOf(SidSoundControl.VOICE, SidSoundControl.PULSE, SidSoundControl.COLOR)) {
            val index = if (control == SidSoundControl.VOICE) 1 else 2
            val canonical = SidSoundMacros.apply(control, arbitrary(), index)
            val table = when (control) { SidSoundControl.VOICE -> SidTables.WAVE; SidSoundControl.PULSE -> SidTables.PULSE; else -> SidTables.FILTER }
            val program = canonical.program(table)
            val explicit = canonical.withProgram(table, program.copy(rows = program.rows + SidRow.jump(0)))
            assertEquals(index, SidSoundMacros.value(control, explicit))
            assertSame(explicit, SidSoundMacros.apply(control, explicit, index))
            val looping = canonical.withProgram(table, program.copy(rows = program.rows + SidRow.jump(1)))
            assertNull(SidSoundMacros.value(control, looping))
        }
        val relative = SidInstrument(wave = SidProgram(listOf(SidRow.wave(0x21, 12))))
        assertNull(SidSoundMacros.value(SidSoundControl.VOICE, relative))
    }

    @Test fun pulseSelectionBootstrapsOnlyAnEmptyProgramAndNeverNormalizesMatchingPulse() {
        val initial = SidInstrument()
        val pulse = SidSoundMacros.apply(SidSoundControl.VOICE, initial, 2)
        assertEquals(listOf(SidRow.wave(0x41)), pulse.wave.rows)
        assertEquals(listOf(SidRow.pulseWidth(0x800)), pulse.pulse.rows)
        assertEquals(initial, pulse.copy(wave = initial.wave, pulse = initial.pulse))
        val custom = arbitrary()
        assertEquals(custom.pulse, SidSoundMacros.apply(SidSoundControl.VOICE, custom, 2).pulse)
        val inherited = pulse.copy(pulse = SidProgram())
        assertSame(inherited, SidSoundMacros.apply(SidSoundControl.VOICE, inherited, 2))
        assertFalse(SidSoundFace.enabled(SidSoundControl.PULSE, initial))
        assertTrue(SidSoundFace.enabled(SidSoundControl.PULSE, pulse))
    }

    @Test fun contourAndTailAreIndependentAndColorIsHonestAboutSharedOwnership() {
        val original = arbitrary()
        val contour = SidSoundMacros.apply(SidSoundControl.CONTOUR, original, 0)
        assertEquals(listOf(0, 7, 0, 13), listOf(contour.attack, contour.decay, contour.sustain, contour.release))
        val tail = SidSoundMacros.apply(SidSoundControl.TAIL, contour, 9)
        assertEquals(contour, tail.copy(release = contour.release))
        for (i in 1 until SidSoundMacros.steps(SidSoundControl.COLOR)) {
            val color = SidSoundMacros.apply(SidSoundControl.COLOR, original, i)
            assertEquals(7, color.filter.rows[0].right and 7)
            assertEquals(original, color.copy(filter = original.filter))
        }
        val leave = SidSoundMacros.apply(SidSoundControl.COLOR, original, 0)
        assertTrue(leave.filter.rows.isEmpty()) // No routing-off command: another voice's filter is left alone.
    }

    @Test fun waveCommandsCannotMakeFriendlyReadoutsOrKnobsLie() {
        val commands = listOf(
            SidSoundControl.CONTOUR to SidRules.FX_ATTACK_DECAY,
            SidSoundControl.CONTOUR to SidRules.FX_SUSTAIN_RELEASE,
            SidSoundControl.TAIL to SidRules.FX_SUSTAIN_RELEASE,
            SidSoundControl.PULSE to SidRules.FX_PULSE_PROGRAM,
            SidSoundControl.COLOR to SidRules.FX_FILTER_PROGRAM,
            SidSoundControl.COLOR to SidRules.FX_FILTER_CONTROL,
            SidSoundControl.COLOR to SidRules.FX_CUTOFF,
        )
        for ((control, command) in commands) {
            val ins = SidInstrument(wave = SidProgram(listOf(SidRow.wave(0x41), SidRow.command(command, 1))))
            assertNull(SidSoundMacros.value(control, ins))
            assertFalse(SidSoundFace.enabled(control, ins))
            assertSame(ins, SidSoundMacros.apply(control, ins, 1))
            // A deliberate new Voice program removes those overrides, without changing other programs.
            val plain = SidSoundMacros.apply(SidSoundControl.VOICE, ins, 2)
            assertTrue(SidSoundFace.enabled(control, plain))
        }
    }

    @Test fun touchAndControllerProduceTheSamePatch() {
        val pointer = panel(EditorSession(SidSystem.blank()))
        val buttons = panel(EditorSession(SidSystem.blank()))
        val controls = intArrayOf(0, 1, 2, 3, 4)
        val choices = intArrayOf(2, 3, 2, 11, 2)
        for (i in controls.indices) {
            val control = SidSoundControl.entries[i]
            pointer.draw()
            val rect = pointer.painter.controlRect(i)!!
            val x = pointer.painter.valueX(i, choices[i].toFloat() / (SidSoundMacros.steps(control) - 1))
            pointer.painter.down(x, rect.centerY())
            pointer.painter.up(x, rect.centerY())
            buttons.state.instField = i + 2
            val current = SidSoundMacros.value(control, buttons.pane.session.song.sid.instruments[0])!!
            val delta = choices[i] - current
            buttons.ctl.press(Action.EDIT)
            repeat(kotlin.math.abs(delta)) { buttons.ctl.tap(if (delta > 0) Action.RIGHT else Action.LEFT) }
            buttons.ctl.release(Action.EDIT)
        }
        assertEquals(buttons.pane.session.song, pointer.pane.session.song)
    }

    @Test fun controllerEditingAuditionsAndGroupsUndoWithoutChangingUnownedPrograms() {
        val s = EditorSession(SidSystem.blank())
        val calls = ArrayList<Int>()
        s.onAudition = { ch, _, _, _ -> calls += ch }
        val p = panel(s)
        p.state.instField = 2
        val original = s.song
        p.ctl.hold(Action.EDIT, Action.RIGHT) // Saw -> Pulse, initializes empty width.
        assertEquals(2, SidSoundMacros.value(SidSoundControl.VOICE, s.song.sid.instruments[0]))
        assertTrue(calls.size >= 2)
        p.state.instField = 3
        p.ctl.hold(Action.EDIT, Action.RIGHT, Action.RIGHT, Action.RIGHT) // Square -> Flutter
        assertEquals(4, SidSoundMacros.value(SidSoundControl.PULSE, s.song.sid.instruments[0]))
        s.undo()
        assertEquals(1, SidSoundMacros.value(SidSoundControl.PULSE, s.song.sid.instruments[0]))
        s.undo()
        assertSame(original, s.song)
    }

    @Test fun aModeChangeBlocksHeldEditAndRemembersEachFaceCursor() {
        val s = EditorSession(SidSystem.blank())
        val p = panel(s)
        p.state.instField = 4
        p.ctl.press(Action.EDIT)
        p.tap(p.painter.modeRect(true))
        assertTrue(p.state.sidPanel.editBlocked)
        val before = s.song
        p.ctl.tap(Action.RIGHT)
        assertSame(before, s.song)
        p.ctl.release(Action.EDIT)
        p.state.instField = 7
        p.tap(p.painter.modeRect(false))
        assertEquals(4, p.state.instField)
        p.tap(p.painter.modeRect(true))
        assertEquals(7, p.state.instField)
        assertSame(before, s.song)
        p.state.instField = 0
        p.ctl.tap(Action.UP)
        assertEquals(-1, p.state.instField)
        p.ctl.tap(Action.LEFT)
        assertFalse(p.state.sidPanel.advanced)
        p.ctl.press(Action.CLEAR)
        p.tap(p.painter.modeRect(true))
        p.ctl.release(Action.CLEAR)
        assertSame(before, s.song)
    }

    @Test fun dragIsOneUndoAndCannotContinueAfterUndoModeSlotOrDocumentChanges() {
        val s = EditorSession(SidSystem.blank())
        val p = panel(s)
        p.state.instField = 5
        p.draw()
        val before = s.song
        val r = p.painter.controlRect(3)!!
        p.painter.down(p.painter.valueX(3, 0f), r.centerY())
        p.painter.move(p.painter.valueX(3, 0.5f), r.centerY())
        p.painter.move(p.painter.valueX(3, 1f), r.centerY())
        p.painter.up(0f, 0f)
        assertEquals(15, s.song.sid.instruments[0].release)
        s.undo()
        assertSame(before, s.song)
        for (change in 0..4) {
            p.state.instrument = 1
            p.state.kind = ViewKind.INSTRUMENT
            SidSoundFace.selectMode(p.ctl, false)
            p.state.instField = 5
            p.draw()
            val control = p.painter.controlRect(3)!!
            p.painter.down(p.painter.valueX(3, 0f), control.centerY())
            when (change) {
                0 -> s.undo()
                1 -> SidSoundFace.selectMode(p.ctl, true)
                2 -> SidInstrumentView.moveSlot(p.ctl, 1)
                3 -> s.replaceSong(SidSystem.blank())
                4 -> p.state.kind = ViewKind.PATTERN
            }
            val after = s.song
            p.painter.move(p.painter.valueX(3, 1f), control.centerY())
            p.painter.up(0f, 0f)
            assertSame("change $change", after, s.song)
        }
    }

    @Test fun compactControlsAndEverySlotAreReachableAtTouchSize() {
        val p = panel(EditorSession(SidSystem.blank()), 480, 300)
        for (i in 0..4) {
            p.draw()
            val r = p.painter.controlRect(i)!!
            assertTrue(r.height() >= 48f)
            assertTrue(r.left >= p.area.left && r.right <= p.area.right)
            assertTrue(r.top >= p.area.top && r.bottom <= p.area.bottom)
            val nav = p.painter.navigationRect(true)!!
            assertTrue(nav.width() >= 48f && nav.height() >= 48f)
            p.tap(nav)
        }
        val focus = p.state.instField
        var last = 1
        repeat(63) {
            p.draw()
            assertNotNull(p.painter.slotRect(p.state.instrument))
            val step = p.painter.slotNavigationRect(true)
            assertTrue(step.width() >= 48f && step.height() >= 48f)
            assertTrue(step.right <= p.area.right && step.bottom <= p.area.bottom)
            last = p.state.instrument
            p.tap(step)
        }
        assertEquals(63, last)
        assertEquals(focus, p.state.instField)
        p.draw()
        p.tap(p.painter.slotNavigationRect(false))
        assertEquals(62, p.state.instrument)
        assertEquals(focus, p.state.instField)
        p.tap(p.painter.nameRect())
        assertEquals(1, p.state.instField)
        assertTrue(p.surface.texts.any { it.startsWith("Sound") })
    }

    @Test fun contrastUsesSemanticColorsAndPulseIsVisiblyUnavailableOnOtherWaves() {
        val p = panel(EditorSession(SidSystem.blank()))
        p.pane.palette = Palette.CONTRAST
        p.draw()
        assertTrue(p.surface.texts.contains("Pulse voice only"))
        assertFalse(p.surface.colors.contains(0xFF25216B.toInt()))
        assertTrue(p.surface.colors.contains(Palette.CONTRAST.FOCUS))
        val before = p.pane.session.song
        val r = p.painter.controlRect(1)!!
        p.tap(r)
        assertSame(before, p.pane.session.song)
    }

    @Test fun everyChoiceRemainsNativeGoatTrackerDataOnBothChips() {
        for (chip in SidChip.entries) for (control in SidSoundControl.entries) for (index in 0 until SidSoundMacros.steps(control)) {
            val s = EditorSession(SidSystem.blank())
            s.editSidSound("Chip") { it.copy(chip = chip) }
            s.setSidInstrument(1, SidSoundMacros.apply(control, arbitrary(), index))
            assertEquals(emptyList<String>(), SidSystem.errors(s.song))
            val gt = GtSong.of(s.song)
            val patch = s.song.sid.instruments[0]
            assertEquals(patch.attackDecay, gt.instruments[0].ad)
            assertEquals(patch.sustainRelease, gt.instruments[0].sr)
            assertEquals(patch.wave.at, gt.instruments[0].wave)
            assertEquals(patch.pulse.at, gt.instruments[0].pulse)
            assertEquals(patch.filter.at, gt.instruments[0].filter)
            val image = ByteArray(SidPlayerImage.IMAGE_SIZE)
            val lowered = SidLowering.lower(gt, image)
            assertTrue(lowered.dataEnd > 0)
        }
    }

    private class RecordingSurface : Surface {
        val texts = ArrayList<String>()
        val colors = HashSet<Int>()
        /** Every drawing command with its leftmost x, to compare one region of two frames exactly. */
        val ops = ArrayList<Pair<Float, String>>()
        fun clear() { texts.clear(); colors.clear(); ops.clear() }
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { colors += color; ops += l to "rect $l $t $r $b $color" }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { colors += color; ops += l to "round $l $t $r $b $radius $color" }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) { colors += color; ops += l to "stroke $l $t $r $b $color $width $radius" }
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) { colors += color; ops += minOf(x0, x1) to "line $x0 $y0 $x1 $y1 $color $width" }
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) { colors += color; ops += cx - radius to "circle $cx $cy $radius $color" }
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) { colors += color; ops += cx - radius to "ring $cx $cy $radius $color $width" }
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
            colors += color
            ops += (xs.take(count).minOrNull() ?: 0f) to "polyline ${xs.take(count)} ${ys.take(count)} $color $width"
        }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            texts += s; colors += color; ops += x to "text $s $x $top $h $color $face $bold $size $align"
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
    }
    private class Panel(val pane: PaneView, val painter: SidInstrumentPainter, val surface: RecordingSurface, val width: Int, val height: Int) {
        val area = Rect(0f, 0f, width.toFloat(), height.toFloat())
        val state get() = pane.state
        val ctl get() = pane.controller
        fun draw() { pane.ink.layout(width, height, state.zoom); ctl.normalise(); surface.clear(); painter.draw(surface, area) }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
    }
    private fun panel(session: EditorSession, width: Int = 1000, height: Int = 700): Panel {
        val surface = RecordingSurface()
        val pane = PaneView(session, ViewState(kind = ViewKind.INSTRUMENT, instField = 2), 1f, surface, { 0L }, {})
        return Panel(pane, SidInstrumentPainter(pane), surface, width, height).also { it.draw() }
    }
}
