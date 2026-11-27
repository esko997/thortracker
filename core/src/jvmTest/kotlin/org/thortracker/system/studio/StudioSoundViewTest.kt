package org.thortracker.system.studio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.MenuContext
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.system.midi.MidiTrack
import org.thortracker.system.studio.StudioSoundView.Param
import org.thortracker.ui.Align
import org.thortracker.ui.Face
import org.thortracker.ui.PaneView
import org.thortracker.ui.Rect
import org.thortracker.ui.Surface

class StudioSoundViewTest {
    @Test fun releasingEditKeepsTheNoteUntilHeldAuditionIsReleased() {
        val panel = Panel(session(), 640, 480)
        var releases = 0
        panel.session.onReleaseAudition = { releases++ }
        panel.state.instField = Param.CUTOFF.field
        panel.draw()
        panel.pane.press(Action.AUDITION)
        panel.pane.press(Action.EDIT)
        panel.key(Action.RIGHT)
        panel.pane.release(Action.EDIT)
        assertEquals(62, panel.patch().cutoff)
        assertEquals(0, releases)
        panel.pane.release(Action.AUDITION)
        assertEquals(1, releases)
    }

    @Test fun bothFacesKeepTheSameBankAndEveryControlFitsBrickPortraitAndCompact() {
        for ((w, h) in listOf(640 to 480, 360 to 640, 320 to 240)) {
            val panel = Panel(session(), w, h)
            val before = panel.session.song
            panel.draw()
            val bank = panel.surface.bank.toList()
            assertTrue(bank.any { it.startsWith("07|") })
            panel.assertControlsFit()
            panel.tap(panel.painter.modeTarget(true))
            panel.draw()
            assertTrue(StudioSoundView.panel(panel.state).advanced)
            assertEquals(bank, panel.surface.bank)
            panel.assertControlsFit()
            assertTrue("Advanced exposes signed, exact semitones", "+17" in panel.surface.text)
            panel.tap(panel.painter.modeTarget(false))
            assertFalse(StudioSoundView.panel(panel.state).advanced)
            assertSame(before, panel.session.song)
            assertFalse(panel.session.canUndo)
        }
    }

    @Test fun exactEntryAndControllerAdjustTheSamePatchAndStaleEntryCannotChangeAnotherSlot() {
        val panel = Panel(session(), 360, 640)
        val before = panel.session.song
        val menu = EntryMenu()
        panel.session.onMenuRequest = { it(menu) }
        panel.draw()
        panel.tap(panel.painter.modeTarget(true))
        panel.key(Action.RIGHT)
        panel.key(Action.RIGHT)
        panel.draw()
        panel.tap(panel.painter.fieldTarget(Param.TUNE))
        menu.input!!("-19")
        val original = before.studio.patch(7) as MonoPatch
        assertEquals(original.copy(tune = -19), panel.patch())
        panel.draw()
        panel.tap(panel.painter.modeTarget(false))
        panel.key(Action.RIGHT) // Default Wave -> Cutoff on Sound, independent of Advanced's Tune focus.
        panel.pane.press(Action.EDIT)
        panel.key(Action.RIGHT)
        panel.key(Action.RIGHT)
        panel.pane.release(Action.EDIT)
        assertEquals(original.copy(tune = -19, cutoff = 63), panel.patch())
        panel.key(Action.UNDO)
        assertEquals(original.copy(tune = -19), panel.patch())
        panel.key(Action.UNDO)
        assertSame(before, panel.session.song)
        panel.key(Action.REDO)
        assertEquals(-19, panel.patch().tune)
        panel.draw()
        panel.tap(panel.painter.nameTarget())
        val lateName = menu.input!!
        panel.key(Action.DOWN)
        assertEquals(8, panel.state.instrument)
        assertEquals(8, panel.session.selection.instrument)
        lateName("Wrong slot")
        assertEquals("Original", panel.session.song.studio.patch(7)!!.name)
        assertEquals("Init", panel.patch().name)
        panel.draw()
        panel.tap(panel.painter.nameTarget())
        menu.input!!("New patch")
        assertEquals("New patch", panel.patch().name)
        panel.key(Action.UNDO)
        assertEquals("Init", panel.patch().name)
    }

    @Test fun heldEditDragKeepsPreviewButBlocksDirectionsAndUndoEndsThePointerEdit() {
        val panel = Panel(session(), 640, 480)
        val before = panel.session.song
        panel.state.instField = Param.CUTOFF.field
        panel.draw()
        val knob = panel.painter.fieldTarget(Param.CUTOFF)
        val x = knob.centerX()
        val y = knob.centerY()
        val heard = ArrayList<Int>()
        panel.session.onAudition = { _, _, slot, _ -> heard += (panel.session.song.studio.patch(slot) as MonoPatch).cutoff }
        panel.pane.press(Action.EDIT)
        assertTrue(panel.pane.touchDown(x, y))
        panel.pane.touchMove(x, y - 28f)
        assertEquals(listOf(61, 86), heard)
        panel.draw() // The document now exposes a different SoundSystem instance.
        panel.key(Action.LEFT)
        assertEquals(86, panel.patch().cutoff)
        panel.pane.touchMove(x, y + 28f)
        panel.pane.touchUp(x, y + 28f)
        panel.pane.release(Action.EDIT)
        assertEquals(listOf(61, 86, 36), heard)
        panel.key(Action.UNDO)
        assertSame(before, panel.session.song)
        panel.key(Action.REDO)
        assertEquals(36, panel.patch().cutoff)
        panel.draw()
        assertTrue(panel.pane.touchDown(x, y))
        panel.pane.touchMove(x, y - 28f)
        panel.key(Action.UNDO)
        panel.pane.touchMove(x, y + 40f)
        panel.pane.touchCancel()
        assertEquals(36, panel.patch().cutoff)
        assertFalse(panel.session.inGesture)
    }

    @Test fun waveAndLevelTouchDoNotAlterOtherParametersAndCancelKeepsOnlyTheAcceptedDrag() {
        val panel = Panel(session(), 360, 640)
        val initial = panel.patch()
        panel.draw()
        panel.tap(panel.painter.waveTarget(0))
        assertEquals(initial.copy(waveform = 0), panel.patch())
        panel.draw()
        val rail = panel.painter.fieldTarget(Param.LEVEL)
        assertTrue(panel.pane.touchDown(rail.centerX(), rail.centerY()))
        panel.pane.touchMove(rail.right, rail.centerY())
        assertEquals(100, panel.patch().level)
        panel.pane.touchMove(rail.left, rail.centerY())
        panel.pane.touchCancel()
        panel.pane.touchMove(rail.right, rail.centerY())
        assertEquals(initial.copy(waveform = 0, level = 0), panel.patch())
        panel.key(Action.UNDO)
        assertEquals(initial.copy(waveform = 0), panel.patch())
        panel.key(Action.UNDO)
        assertEquals(initial, panel.patch())
    }

    @Test fun slotFollowingAndLocksDoNotRedirectASeparatePaneAndSoundNeverAuditionsUsb() {
        val tracks = listOf(StudioTrack(MidiTrack(1, 12), StudioTarget.USB_MIDI), StudioTrack(MidiTrack(2, 2)))
        val session = session(tracks)
        val primary = Panel(session, 640, 480)
        val sibling = Panel(session, 360, 640)
        val auditions = ArrayList<Pair<Int, Int>>()
        session.onAudition = { channel, _, slot, _ -> auditions += channel to slot }
        primary.key(Action.DOWN)
        sibling.pane.refresh()
        assertEquals(8, sibling.state.instrument)
        sibling.state.lockedSubject = 8
        primary.key(Action.UP)
        sibling.pane.refresh()
        assertEquals(8, sibling.state.instrument)
        primary.key(Action.AUDITION)
        sibling.key(Action.AUDITION)
        assertEquals(listOf(1 to 7, 1 to 8), auditions)
        session.replaceSong(session.song.copy(sound = session.song.studio.copy(tracks = tracks.map { it.copy(target = StudioTarget.USB_MIDI) })))
        primary.key(Action.AUDITION)
        assertEquals(2, auditions.size)
        assertTrue(session.message.orEmpty().contains("internal track"))
    }

    private class Panel(val session: EditorSession, private val width: Int, private val height: Int) {
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 7)
        val surface = Canvas()
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = StudioSoundPainter(pane)
        private val area = Rect()

        fun draw() {
            pane.ink.layout(width, height, state.zoom)
            pane.controller.normalise()
            surface.bankRight = minOf(width * 0.26f, pane.ink.unit * 12f)
            surface.bankTop = pane.ink.unit * 2f
            area.set(0f, 0f, width.toFloat(), height - pane.ink.unit * 1.9f)
            surface.bankBottom = area.bottom
            painter.draw(surface, area) // Mirror the hit geometry while PaneView owns and routes the live pointer.
            surface.bank.clear()
            surface.text.clear()
            pane.draw(width, height)
        }

        fun tap(rect: Rect) {
            val x = rect.centerX()
            val y = rect.centerY()
            assertTrue(pane.touchDown(x, y))
            pane.touchUp(x, y)
        }

        fun key(action: Action) { pane.press(action); pane.release(action) }
        fun patch() = session.song.studio.patch(state.instrument) as MonoPatch

        fun assertControlsFit() {
            for (param in Param.entries) {
                val r = painter.fieldTarget(param)
                assertTrue("${param.label} at $width×$height: $r", r.width() > 24f && r.height() > 16f &&
                    r.left >= surface.bankRight && r.right < width && r.top > surface.bankTop && r.bottom < area.bottom)
                for (other in Param.entries) if (param != other) {
                    val o = painter.fieldTarget(other)
                    assertTrue("${param.label} overlaps ${other.label} at $width×$height",
                        r.left >= o.right || o.left >= r.right || r.top >= o.bottom || o.top >= r.bottom)
                }
            }
        }
    }

    @Test fun touchingFmBrightnessEditsOnlyItsOperatorAndKeepsTheBankWhenSwitchingFaces() {
        val original = FmPatch("Custom", mapOf(0 to 317, 1 to 127, 2 to 391, 5 to 2831))
        val fixture = session()
        val doc = fixture.song.copy(sound = fixture.song.studio.withPatch(7, original))
        fixture.replaceSong(doc)
        val st = ViewState(kind = ViewKind.INSTRUMENT, instrument = 7)
        val canvas = Canvas()
        val pane = PaneView(fixture, st, 1f, canvas, { 0L }, {})
        fun draw() {
            pane.ink.layout(640, 480, st.zoom)
            canvas.bankRight = minOf(640 * 0.26f, pane.ink.unit * 12f)
            canvas.bankTop = pane.ink.unit * 2f
            canvas.bankBottom = 480 - pane.ink.unit * 1.9f
            canvas.bank.clear()
            canvas.centers.clear()
            pane.draw(640, 480)
        }
        fun tap(label: String) {
            val (x,y) = canvas.centers.getValue(label)
            assertTrue(pane.touchDown(x,y))
            pane.touchUp(x,y)
        }
        draw()
        val bank = canvas.bank.toList()
        tap("Advanced")
        draw()
        assertEquals(bank,canvas.bank)
        assertSame(doc,fixture.song)
        tap("Sound")
        draw()
        val (x,y) = canvas.centers.getValue("Brightness")
        assertTrue(pane.touchDown(x,y))
        pane.touchMove(x+20,y)
        pane.touchMove(x+40,y)
        pane.touchUp(x+40,y)
        val next = fixture.song.studio.patch(7) as FmPatch
        assertTrue(next.value(2)>391)
        assertEquals(original.withValue(2,next.value(2)),next)
        pane.press(Action.UNDO)
        pane.release(Action.UNDO)
        assertSame(doc,fixture.song)
    }

    private class Canvas : Surface {
        val bank = ArrayList<String>()
        val text = ArrayList<String>()
        val centers = HashMap<String,Pair<Float,Float>>()
        var bankRight = 0f
        var bankTop = 0f
        var bankBottom = 0f
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = Unit
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = Unit
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = Unit
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = Unit
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            text += s
            centers[s] = x to top+h/2
            if (x < bankRight && top >= bankTop && top < bankBottom) bank += "$s|$x|$top|$h|$color|$face|$bold|$size|$align"
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2f
    }

    private class EntryMenu : MenuContext {
        var input: ((String) -> Unit)? = null
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) = error("Unexpected choice menu: $title")
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) { input = accepted }
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit) = error("Unexpected file picker")
        override fun status(message: String) = Unit
        override fun error(message: String): Nothing = throw AssertionError(message)
        override fun installRhythmRom(bytes: ByteArray): String? = null
    }

    companion object {
        private fun session(tracks: List<StudioTrack> = listOf(StudioTrack(MidiTrack(1, 0)))): EditorSession {
            val sound = StudioSound(tracks = tracks, nextId = tracks.size + 1).withPatch(7,
                MonoPatch(name = "Original", waveform = 1, cutoff = 61, tune = 17))
            return EditorSession(Song(patterns = listOf(Pattern.blank(tracks.size)), orders = listOf(0), sound = sound))
        }
    }
}
