package org.thortracker.system.cbm

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.backend.CbmImage
import org.thortracker.backend.CbmLowering
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.NameEdit
import org.thortracker.editor.ViewController
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class CbmSoundFaceTest {
    private fun patch() = CbmInstrument(name = "Keep me", wave = 1,
        volume = CbmEnvelope(listOf(8, 4, 2, 0), loop = 1, release = 3),
        waves = CbmEnvelope(listOf(1, 0, 1, 0), loop = 0, release = 3),
        arpeggio = CbmEnvelope(listOf(0, 7, 12), loop = 0),
        pitch = CbmEnvelope(listOf(-3, 4), loop = 0), pitchRelative = true,
        sample = 1, root = 51, loop = true, loopStart = 2)

    private fun session(system: CbmSystem = TedSystem, instrument: CbmInstrument = patch()): EditorSession {
        val song = system.blank()
        val sound = song.cbm.copy(samples = if (system == TedSystem)
            listOf(CbmSample("Long", listOf(4, 8, 4, 0)), CbmSample("Short", listOf(4, 0, 8))) else emptyList())
        return EditorSession(song.copy(sound = sound.withInstrument(1, instrument)))
    }
    private fun view(s: EditorSession, field: Int = 2) = ViewController(s,
        ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = field))
    private fun ViewController.tap(a: Action) { press(a); release(a) }
    private fun ViewController.edit(vararg moves: Action) { press(Action.EDIT); for (a in moves) tap(a); release(Action.EDIT) }

    @Test fun openingAndSwitchingKeepAllFieldsAndNativeImageForBothChips() {
        for (system in listOf(VicSystem, TedSystem)) {
            val s = session(system)
            val v = view(s, 0)
            val before = s.song
            val bytes = CbmLowering.lower(before)
            repeat(3) {
                v.normalise()
                CbmSoundFace.selectMode(v, true)
                v.normalise()
                CbmSoundFace.selectMode(v, false)
            }
            assertEquals(2, v.state.instField)
            assertSame(before, s.song)
            assertArrayEquals(bytes, CbmLowering.lower(s.song))
        }
    }

    @Test fun gatesAndSharedMasterHaveExactOwnershipAndCustomReadback() {
        for (chip in CbmChip.entries) {
            val sound = CbmSound.blank(chip)
            val before = patch()
            assertNull(CbmSoundMacros.value(CbmSoundControl.GATE, sound, before, 0))
            assertNull(CbmSoundMacros.value(CbmSoundControl.MASTER, sound, before, 0))
            for (c in listOf(CbmSoundControl.GATE, CbmSoundControl.MASTER)) for (n in 0..3) {
                val after = CbmSoundMacros.set(c, sound, before, n)
                assertEquals(n, CbmSoundMacros.value(c, sound, after, 0))
                assertSame(after, CbmSoundMacros.set(c, sound, after, n))
                assertEquals(before, if (c == CbmSoundControl.GATE) after.copy(waves = before.waves) else after.copy(volume = before.volume))
                assertNull(after.problem(chip))
                if (c == CbmSoundControl.MASTER) assertTrue(after.volume.values.all { it in 0..chip.maxVolume })
            }
        }
    }

    @Test fun tedSourcePreservesGateTimingAndAllInactiveRecordingFields() {
        val sound = CbmSound.blank(CbmChip.TED)
        val before = patch()
        val noise = CbmSoundMacros.set(CbmSoundControl.VOICE, sound, before, 1)
        assertEquals(before.copy(wave = 2, waves = before.waves.copy(values = listOf(2, 0, 2, 0))), noise)
        val sample = CbmSoundMacros.set(CbmSoundControl.VOICE, sound, noise, 2)
        assertEquals(noise.copy(kind = CbmKind.SAMPLE), sample)
        assertEquals(noise, CbmSoundMacros.set(CbmSoundControl.VOICE, sound, sample, 1))
        val mixed = before.copy(waves = CbmEnvelope(listOf(1, 2, 0), loop = 0))
        assertNull(CbmSoundMacros.value(CbmSoundControl.VOICE, sound, mixed, 0))
        assertFalse(CbmSoundMacros.enabled(CbmSoundControl.GATE, sound, mixed))
        assertSame(mixed, CbmSoundMacros.set(CbmSoundControl.GATE, sound, mixed, 1))
        val parked = CbmSoundMacros.set(CbmSoundControl.VOICE, sound, mixed, 2)
        assertEquals(mixed, CbmSoundMacros.set(CbmSoundControl.VOICE, sound, parked, 0))
    }

    @Test fun recordingControlsUseExistingSamplesAndRespectBoundsWithoutTouchingTonePrograms() {
        val s = session(instrument = patch().copy(kind = CbmKind.SAMPLE, sample = 0, loopStart = 3))
        val before = s.song.cbm.instrument(1)!!
        val sound = s.song.cbm
        assertEquals(listOf(CbmSoundControl.VOICE, CbmSoundControl.SAMPLE, CbmSoundControl.ROOT,
            CbmSoundControl.LOOP, CbmSoundControl.LOOP_START), CbmSoundMacros.controls(CbmChip.TED, before))
        assertEquals(before.copy(sample = 1, loopStart = 2), CbmSoundMacros.set(CbmSoundControl.SAMPLE, sound, before, 99))
        assertEquals(before.copy(root = CbmImage.ROOT_MIN), CbmSoundMacros.set(CbmSoundControl.ROOT, sound, before, -99))
        assertEquals(before.copy(root = CbmImage.ROOT_MAX), CbmSoundMacros.set(CbmSoundControl.ROOT, sound, before, 999))
        assertEquals(before.copy(loopStart = 0), CbmSoundMacros.set(CbmSoundControl.LOOP_START, sound, before, -1))
        val once = CbmSoundMacros.set(CbmSoundControl.LOOP, sound, before, 0)
        assertEquals(before.copy(loop = false), once)
        assertSame(once, CbmSoundMacros.set(CbmSoundControl.LOOP_START, sound, once, 0))
        assertSame(before, CbmSoundMacros.set(CbmSoundControl.SAMPLE, sound.copy(samples = emptyList()), before, 1))
    }

    @Test fun bareAxesAndNameCaretMatchSoundContract() {
        for (system in listOf(VicSystem, TedSystem)) {
            val s = session(system)
            val v = view(s, 0)
            v.normalise()
            val before = s.song
            v.tap(Action.DOWN); assertEquals(2, v.state.instrument); assertEquals(2, v.state.instField)
            v.tap(Action.UP); assertEquals(1, v.state.instrument)
            v.tap(Action.LEFT); assertEquals(1, v.state.instField)
            v.edit(*Array(40) { Action.DOWN }); assertEquals(CbmInstrumentView.NAME_MAX - 1, v.state.textPos)
            v.edit(*Array(40) { Action.UP }); assertEquals(0, v.state.textPos)
            assertSame(before, s.song)
            v.edit(Action.RIGHT)
            assertEquals(NameEdit.edit("Keep me", 0, 1, CbmInstrumentView.NAME_MAX), s.song.cbm.instrument(1)!!.name)
            s.undo(); assertSame(before, s.song)
            v.tap(Action.LEFT); assertEquals(-1, v.state.instField)
            v.edit(Action.RIGHT); assertTrue(CbmSoundFace.panel(v).advanced)
            v.tap(Action.EDIT); assertFalse(CbmSoundFace.panel(v).advanced)
            assertSame(before, s.song)
        }
    }

    @Test fun creationAuditionAndGroupedUndoUseNativeLaneRules() {
        for (system in listOf(VicSystem, TedSystem)) {
            val s = session(system)
            val v = view(s)
            v.state.instrument = CbmSystem.SLOTS
            val before = s.song
            v.normalise(); v.tap(Action.EDIT)
            assertSame(before, s.song)
            val heard = ArrayList<Int>()
            s.onAudition = { lane, _, _, _ -> heard += lane }
            v.edit(Action.RIGHT)
            val ins = s.song.cbm.instrument(CbmSystem.SLOTS)!!
            assertNull(ins.problem(system.chip))
            v.tap(Action.AUDITION)
            assertEquals(if (system == TedSystem) 1 else 0, heard.last())
            s.undo(); assertSame(before, s.song)
        }
        val s = session(VicSystem, CbmInstrument())
        val v = view(s)
        val before = s.song
        v.edit(Action.RIGHT, Action.RIGHT)
        assertEquals(2, CbmSoundMacros.value(CbmSoundControl.GATE, s.song.cbm, s.song.cbm.instrument(1)!!, 0))
        s.undo(); assertSame(before, s.song)
    }

    @Test fun allVicLanesAndTedToneNoiseDacAreAuditionedWithoutRewritingPatches() {
        val vic = session(VicSystem)
        val vv = view(vic)
        val lanes = ArrayList<Int>()
        vic.onAudition = { ch, _, _, _ -> lanes += ch }
        val before = vic.song
        for (lane in 0..3) { CbmSoundFace.set(vv, CbmSoundControl.LANE, lane); vv.tap(Action.AUDITION) }
        assertEquals(listOf(0, 1, 2, 3), lanes)
        assertSame(before, vic.song)
        val ted = session()
        val vt = view(ted)
        ted.onAudition = { ch, _, _, _ -> lanes += ch }
        for (lane in 0..1) { CbmSoundFace.set(vt, CbmSoundControl.LANE, lane); vt.tap(Action.AUDITION) }
        CbmSoundFace.set(vt, CbmSoundControl.VOICE, 1); vt.tap(Action.AUDITION)
        CbmSoundFace.set(vt, CbmSoundControl.VOICE, 2); vt.tap(Action.AUDITION)
        assertEquals(listOf(0, 1, 1, CbmImage.TED_DAC_CHANNEL), lanes.takeLast(4))
    }

    @Test fun modeSlotAndTypeChangesBlockStillHeldEdit() {
        val s = session()
        val v = view(s)
        val before = s.song
        v.press(Action.EDIT)
        CbmSoundFace.selectMode(v, true)
        v.tap(Action.RIGHT); v.tap(Action.CLEAR); v.release(Action.EDIT)
        assertSame(before, s.song)
        CbmSoundFace.selectMode(v, false)
        v.press(Action.EDIT)
        CbmSoundFace.moveSlot(v, 1)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
        CbmSoundFace.moveSlot(v, -1)
        v.state.instField = 2
        v.press(Action.EDIT)
        v.tap(Action.RIGHT)
        val noise = s.song
        v.tap(Action.RIGHT)
        assertSame(noise, s.song)
        v.release(Action.EDIT)
        v.edit(Action.RIGHT)
        assertEquals(CbmKind.SAMPLE, s.song.cbm.instrument(1)!!.kind)
    }

    private class Canvas : Surface {
        val bank = ArrayList<String>()
        private var bounds: Rect? = null
        private var area = Rect()
        private var bankColor = 0
        fun begin(area: Rect, bankColor: Int) {
            this.area = area
            this.bankColor = bankColor
            bounds = null
            bank.clear()
        }
        private fun save(x: Float, y: Float, value: String) {
            val r = bounds ?: return
            if (x >= r.left && x < r.right && y >= r.top && y < r.bottom) bank += value
        }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) = save(x, top, "$s/$x/$top/$h/$color/$face/$bold/$size/$align")
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
            // The real bank background reaches the body's bottom; the full-width header does not.
            // Discover its bounds from rendering rather than duplicating drawBank's layout formula.
            if (bounds == null && l == area.left && r < area.right && b == area.bottom && color == bankColor)
                bounds = Rect(l, t, r, b)
            save(l, t, "$l/$t/$r/$b/$color")
        }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = save(l, t, "$l/$t/$r/$b/$radius/$color")
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = save(l, t, "$l/$t/$r/$b/$color/$width/$radius")
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) =
            save(minOf(x0, x1), minOf(y0, y1), "line/$x0/$y0/$x1/$y1/$color/$width")
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) =
            save(cx - radius, cy - radius, "circle/$cx/$cy/$radius/$color")
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) =
            save(cx - radius, cy - radius, "ring/$cx/$cy/$radius/$color/$width")
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
            if (count > 0) save((0 until count).minOf { xs[it] }, (0 until count).minOf { ys[it] },
                "poly/${xs.take(count)}/${ys.take(count)}/$color/$width")
        }
    }
    private class Panel(val session: EditorSession, val width: Int = 360, val height: Int = 270) {
        val canvas = Canvas()
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, instField = 2)
        val pane = PaneView(session, state, 1f, canvas, { 0L }, {})
        val painter = CbmSoundPainter(pane)
        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            pane.controller.normalise()
            val area = Rect(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            canvas.begin(area, pane.ink.palette.LANE)
            painter.draw(canvas, area)
        }
        fun tap(r: Rect) { assertTrue(painter.down(r.centerX(), r.centerY())); painter.up(r.centerX(), r.centerY()) }
        init { draw() }
    }

    @Test fun compactTouchPagesEveryControlWithPhysicalTargetsAndReusesAdvancedBank() {
        for (system in listOf(VicSystem, TedSystem)) for (sample in listOf(false, true)) {
            if (system == VicSystem && sample) continue
            val s = session(system, patch().copy(kind = if (sample) CbmKind.SAMPLE else CbmKind.TONE))
            val p = Panel(s)
            val before = s.song
            assertTrue(p.painter.compact)
            val count = CbmSoundFace.controls(p.pane.controller).size
            repeat(count) { i ->
                p.draw()
                val r = p.painter.controlRect(i)
                assertTrue(r.width() >= 48f); assertTrue(r.height() >= 48f)
                p.tap(p.painter.navigationRect(true))
            }
            assertEquals(2, p.state.instField)
            p.tap(p.painter.nameTarget()); assertEquals(1, p.state.instField)
            p.draw(); val bank = p.canvas.bank.toList()
            assertTrue("Capture includes the selected first slot, not just lower rows", bank.any { it.startsWith("01/") })
            p.tap(p.painter.modeRect(true)); p.draw()
            assertEquals(bank, p.canvas.bank)
            p.tap(p.painter.modeRect(false)); p.draw()
            p.tap(p.painter.slotNavigationRect(true)); assertEquals(2, p.state.instrument)
            assertSame(before, s.song)
        }
    }

    @Test fun touchDragGroupsUndoAndCannotSurviveUndoModeOrSlotChange() {
        val s = session(VicSystem, CbmInstrument())
        val p = Panel(s, 960, 720)
        val before = s.song
        fun start() { p.draw(); p.painter.down(p.painter.valueX(0, 1f / 3), p.painter.controlRect(0).centerY()) }
        start(); p.draw(); p.painter.move(p.painter.valueX(0, 1f), 0f); p.painter.up(0f, 0f)
        assertEquals(3, CbmSoundMacros.value(CbmSoundControl.GATE, s.song.cbm, s.song.cbm.instrument(1)!!, 0))
        s.undo(); assertSame(before, s.song)
        start(); s.undo(); p.painter.move(p.painter.valueX(0, 1f), 0f); p.painter.up(0f, 0f)
        assertSame(before, s.song)
        start(); CbmSoundFace.selectMode(p.pane.controller, true)
        val switched = s.song
        p.painter.move(p.painter.valueX(0, 1f), 0f); assertSame(switched, s.song)
        s.undo(); assertSame(before, s.song)
        CbmSoundFace.selectMode(p.pane.controller, false)
        start(); CbmSoundFace.moveSlot(p.pane.controller, 1)
        val moved = s.song
        p.painter.move(p.painter.valueX(0, 1f), 0f); assertSame(moved, s.song)
        s.undo(); assertSame(before, s.song)
    }
    @Test fun touchVoiceKindChangeCancelsOldRailAndSampleControlsRemainReachable() {
        val s = session()
        val p = Panel(s)
        val before = s.song
        p.painter.down(p.painter.valueX(0, 1f), p.painter.controlRect(0).centerY())
        val sample = s.song
        assertEquals(CbmKind.SAMPLE, sample.cbm.instrument(1)!!.kind)
        p.draw()
        p.painter.move(p.painter.valueX(0, 0f), 0f); p.painter.up(0f, 0f)
        assertSame(sample, s.song)
        repeat(4) { p.tap(p.painter.navigationRect(true)); p.draw() }
        assertEquals(6, p.state.instField)
        assertTrue(p.painter.controlRect(4).width() >= 48f)
        s.undo(); assertSame(before, s.song)
    }

    @Test fun touchAndControllerGateEditsHaveIdenticalNativeImages() {
        val touched = session(VicSystem, CbmInstrument())
        val p = Panel(touched)
        p.painter.down(p.painter.valueX(0, 2f / 3), p.painter.controlRect(0).centerY())
        p.painter.up(0f, 0f)
        val controlled = session(VicSystem, CbmInstrument())
        view(controlled).edit(Action.RIGHT, Action.RIGHT)
        assertArrayEquals(CbmLowering.lower(controlled.song), CbmLowering.lower(touched.song))
    }

    @Test fun externalSlotLockCannotRedirectHeldEdits() {
        val s = session()
        val v = view(s)
        val before = s.song
        v.press(Action.EDIT)
        CbmInstrumentView.applyLock(v.state, 2)
        v.tap(Action.RIGHT); v.release(Action.EDIT)
        assertSame(before, s.song)
    }

    @Test fun recordingDragRestoresOriginalLoopStartAfterVisitingAShortSample() {
        val song = TedSystem.blank()
        val original = patch().copy(kind = CbmKind.SAMPLE, sample = 0, loopStart = 90)
        val sound = song.cbm.copy(samples = listOf(
            CbmSample("Long", List(100) { it % 9 }),
            CbmSample("Short", List(10) { it % 9 }))).withInstrument(1, original)
        val s = EditorSession(song.copy(sound = sound))
        val p = Panel(s)
        p.state.instField = 3
        p.draw()
        p.painter.down(p.painter.valueX(1, 0f), p.painter.controlRect(1).centerY())
        p.painter.move(p.painter.valueX(1, 1f), 0f)
        assertEquals(original.copy(sample = 1, loopStart = 9), s.song.cbm.instrument(1))
        p.draw()
        p.painter.move(p.painter.valueX(1, 0f), 0f)
        assertEquals(original, s.song.cbm.instrument(1))
        p.painter.up(0f, 0f)
    }

    @Test fun acceptedHeldVoiceEditAuditionsNewVoiceBeforeBlockingFurtherChanges() {
        val s = session()
        val v = view(s)
        val heard = ArrayList<Pair<Int, CbmInstrument>>()
        s.onAudition = { lane, _, slot, _ -> heard += lane to s.song.cbm.instrument(slot)!! }
        v.press(Action.EDIT)
        v.tap(Action.RIGHT)
        val noise = s.song.cbm.instrument(1)!!
        assertEquals(listOf(0, 1), heard.map { it.first })
        assertEquals(noise, heard.last().second)
        assertEquals(2, noise.wave)
        v.tap(Action.RIGHT)
        assertEquals(2, heard.size)
        assertSame(noise, s.song.cbm.instrument(1))
        v.release(Action.EDIT)
    }

    @Test fun heldListenFollowsLaneSetterAndChannelCommandsOnBothChips() {
        for (system in listOf(VicSystem, TedSystem)) {
            val s = session(system)
            val v = view(s)
            val before = s.song
            val heard = ArrayList<Int>()
            s.onAudition = { lane, _, _, _ -> heard += lane }
            val last = if (system == VicSystem) 3 else 1
            v.press(Action.AUDITION)
            CbmSoundFace.set(v, CbmSoundControl.LANE, last)
            v.tap(Action.NEXT_CHANNEL)
            v.tap(Action.PREV_CHANNEL)
            assertEquals(listOf(0, last, 0, last), heard)
            assertSame(before, s.song)
            v.release(Action.AUDITION)
        }
    }

    @Test fun tedToneReadbackAndNavigationUseResolvedVoiceNotTheParkedDacLane() {
        val s = session()
        val v = view(s, 5)
        v.state.listenChannel = CbmImage.TED_DAC_CHANNEL
        val before = s.song
        assertEquals(0, CbmSoundFace.value(v, CbmSoundControl.LANE))
        v.edit(Action.RIGHT)
        assertEquals(1, v.state.listenChannel)
        assertEquals(1, CbmSoundFace.value(v, CbmSoundControl.LANE))
        v.state.listenChannel = CbmImage.TED_DAC_CHANNEL
        for (lane in listOf(1, 0, 1, 0)) {
            v.tap(Action.NEXT_CHANNEL)
            assertEquals(lane, v.state.listenChannel)
            assertEquals(lane, CbmInstrumentView.listenChannel(s.song, v.state, s.song.cbm.instrument(1)))
        }
        assertSame(before, s.song)
    }

    @Test fun pasteAndCutCannotRedirectHeldEditIntoAChangedKindOrEmptySlot() {
        val s = session()
        val sample = patch().copy(kind = CbmKind.SAMPLE)
        s.setCbmInstrument(2, sample, "Sample fixture")
        s.copyCbmInstrument(2)
        val v = view(s)
        v.press(Action.EDIT)
        v.tap(Action.PASTE)
        assertEquals(sample, s.song.cbm.instrument(1))
        val pasted = s.song
        v.tap(Action.RIGHT); v.tap(Action.CLEAR)
        assertSame(pasted, s.song)
        v.release(Action.EDIT)
        v.press(Action.EDIT)
        v.tap(Action.CUT)
        assertNull(s.song.cbm.instrument(1))
        val cut = s.song
        v.tap(Action.RIGHT); v.tap(Action.CLEAR)
        assertSame(cut, s.song)
        v.release(Action.EDIT)
    }
}
