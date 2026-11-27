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

    @Test fun emptySamplerArtworkImportsARecordingAndUndoRestoresTheEmptySlot() {
        val fixture = session()
        val before = fixture.song.copy(sound = fixture.song.studio.withPatch(7, SamplerPatch()))
        fixture.replaceSong(before)
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 7, samplerPage = 1)
        val canvas = Canvas()
        val pane = PaneView(fixture, state, 1f, canvas, { 0L }, {})
        var choose: (() -> Unit)? = null
        var deliver: ((String, String, ByteArray) -> Unit)? = null
        val menu = object : EntryMenu() {
            override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) {
                choose = entries.single().second
            }
            override fun pickFile(title: String, extensions: List<String>, maxBytes: Int,
                picked: (String, String, ByteArray) -> Unit) { deliver = picked }
        }
        fixture.onMenuRequest = { it(menu) }
        pane.controller.normalise()
        pane.draw(640, 480)
        val (x,y) = canvas.centers.getValue("Choose recording")
        assertTrue(pane.touchDown(x,y))
        pane.touchUp(x,y)
        assertSame(before,fixture.song)
        choose!!()
        assertSame(before,fixture.song)
        val wav = java.nio.ByteBuffer.allocate(48).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(8000); putShort(1); putShort(8)
            put("data".toByteArray()); putInt(4); put(byteArrayOf(-128,-1,0,-128))
        }.array()
        deliver!!("Fresh", ".wav", wav)
        val patch = fixture.song.studio.patch(7) as SamplerPatch
        val pcm = fixture.song.studio.assets[patch.assetId!!]!!
        assertEquals("Fresh",patch.name)
        assertEquals(8000,pcm.sampleRate)
        assertEquals(4,pcm.frames)
        assertEquals(-1f,pcm.sample(2),0f)
        assertEquals(before.studio.patch(8),fixture.song.studio.patch(8))
        pane.press(Action.UNDO); pane.release(Action.UNDO)
        assertSame(before,fixture.song)
    }

    private fun drawnPatch() = WavetablePatch("Charcoal", mapOf(0 to 173), StudioWaves.of(List(4) { wave ->
        List(64) { i -> when(i) { 0 -> 127; 63 -> -128; else -> (i * 7 + wave * 37) % 251 - 125 } }
    }))

    private fun PaneView.key(a: Action) { press(a); release(a) }
    private fun PaneView.tap(canvas: Canvas, label: String) {
        val (x,y) = canvas.centers.getValue(label)
        assertTrue(touchDown(x,y)); touchUp(x,y)
    }
    private fun sessionWith(p: StudioPatch): EditorSession = session().let { fixture ->
        EditorSession(fixture.song.copy(sound=fixture.song.studio.withPatch(7,p)))
    }

    @Test fun morphRailLabelsRecallTheAuthoredSourcesWithoutRewritingThem() {
        val original=drawnPatch();val fixture=sessionWith(original)
        val canvas=Canvas();val pane=PaneView(fixture,ViewState(kind=ViewKind.INSTRUMENT,instrument=7),1f,canvas,{0L},{})
        for((i,label) in listOf("A","B","C","D").withIndex()) {
            pane.draw(360,640);pane.tap(canvas,label)
            val actual=fixture.song.studio.patch(7) as WavetablePatch
            assertEquals(i*100,actual.value(0))
            assertSame(original.waves,actual.waves)
        }
    }

    @Test fun drawingASparseReverseStrokeInADuplicateKeepsTheSourceAndUndoesOnce() {
        val original=drawnPatch(); val fixture=sessionWith(original)
        val state=ViewState(kind=ViewKind.INSTRUMENT,instrument=7,waveSource=3)
        val canvas=Canvas(); val pane=PaneView(fixture,state,1f,canvas,{0L},{})
        pane.key(Action.DUPLICATE)
        val before=fixture.song
        assertEquals(8,state.instrument)
        pane.draw(360,640); pane.tap(canvas,"Draw")
        pane.draw(360,640)
        val grid=canvas.waveBounds
        assertTrue(pane.touchDown(grid.right-.1f,grid.top+.1f)); pane.touchUp(grid.right-.1f,grid.top+.1f)
        assertEquals(63,state.wavePoint)
        assertSame(before,fixture.song) // Selecting a point or Draw doesn't write.
        pane.draw(360,640)
        pane.press(Action.AUDITION)
        var retriggers=0
        fixture.onAudition={ _,_,_,_->retriggers++ }
        val ver=fixture.documentVersion
        assertTrue(pane.touchDown(grid.right-.1f,grid.top+.1f))
        pane.touchMove(grid.left-grid.width()*.1f,grid.bottom+8f)
        assertEquals(ver+1,fixture.documentVersion) // even though all 64 points were crossed
        pane.touchMove(grid.left,grid.top-8f) // vertical revisit writes only point 1
        pane.touchCancel()
        val after=fixture.song.studio.patch(8) as WavetablePatch
        assertEquals(127,after.waves.point(3,0))
        assertEquals(-124,after.waves.point(3,1))
        assertEquals(2,after.waves.point(3,32))
        assertEquals(127,after.waves.point(3,63))
        assertEquals(173,after.value(0))
        for(w in 0..2) for(i in 0..63) assertEquals(original.waves.point(w,i),after.waves.point(w,i))
        assertEquals(original,fixture.song.studio.patch(7))
        assertEquals(0,retriggers)
        pane.release(Action.AUDITION)
        pane.key(Action.UNDO); assertSame(before,fixture.song)
        pane.key(Action.REDO); pane.key(Action.COPY); pane.key(Action.DOWN); pane.key(Action.PASTE)
        assertEquals(after,fixture.song.studio.patch(9))
        pane.draw(320,240); pane.tap(canvas,"Advanced")
        val exact=EntryMenu(); fixture.onMenuRequest={ it(exact) }
        // Advanced begins at Morph. Its raw 173 is not rounded to a rail stop or an automation byte.
        pane.key(Action.EDIT); exact.input!!("241")
        val pasted=fixture.song.studio.patch(9) as WavetablePatch
        assertEquals(after.copy(values=after.values+(0 to 241)),pasted)
        assertEquals(after,fixture.song.studio.patch(8))
    }

    @Test fun resizeUndoSlotChangeAndSiblingEditsNeverRedirectACapturedStroke() {
        for(event in listOf("resize","undo","slot","sibling")) {
            val original=drawnPatch(); val fixture=sessionWith(original)
            val state=ViewState(kind=ViewKind.INSTRUMENT,instrument=7,wavetablePage=1)
            val canvas=Canvas(); val pane=PaneView(fixture,state,1f,canvas,{0L},{})
            pane.draw(640,480)
            val g=canvas.waveBounds
            assertTrue(pane.touchDown(g.left+.1f,g.bottom-.1f))
            pane.touchMove(g.centerX(),g.top)
            assertNotEquals(original,fixture.song.studio.patch(7))
            when(event) {
                "resize"->pane.draw(320,240)
                "undo"->{pane.key(Action.UNDO);assertSame(original,fixture.song.studio.patch(7))}
                "slot"->{pane.key(Action.DOWN);assertEquals(8,state.instrument)}
                "sibling"->{
                    val other=PaneView(fixture,ViewState(kind=ViewKind.INSTRUMENT,instrument=7,instField=3),1f,Canvas(),{0L},{})
                    other.press(Action.EDIT);other.key(Action.RIGHT);other.release(Action.EDIT)
                    assertEquals(174,(fixture.song.studio.patch(7) as WavetablePatch).value(0))
                }
            }
            val accepted=fixture.song
            pane.touchMove(g.right,g.bottom);pane.touchUp(g.right,g.bottom)
            assertSame(event,accepted,fixture.song)
            assertFalse(event,fixture.inGesture)
        }
    }

    @Test fun controllerChoosesSourceAndLastPointWithoutMovingMorphAndEditsOneGroupedHeight() {
        val original=drawnPatch(); val fixture=sessionWith(original)
        val state=ViewState(kind=ViewKind.INSTRUMENT,instrument=7)
        val pane=PaneView(fixture,state,1f,Canvas(),{0L},{})
        val exact=EntryMenu(); fixture.onMenuRequest={ it(exact) }
        pane.draw(320,240)
        fun adjust(vararg directions:Action) {pane.press(Action.EDIT);for(d in directions) pane.key(d);pane.release(Action.EDIT)}
        adjust(Action.RIGHT) // Play → Draw
        pane.key(Action.RIGHT);adjust(Action.RIGHT) // Source B
        pane.key(Action.RIGHT);pane.key(Action.EDIT);exact.input!!("64")
        assertEquals(1,state.waveSource);assertEquals(63,state.wavePoint)
        assertFalse(fixture.canUndo)
        pane.key(Action.RIGHT);adjust(Action.RIGHT,Action.RIGHT,Action.RIGHT)
        val expected=original.copy(waves=original.waves.withPoint(1,63,-125))
        assertEquals(expected,fixture.song.studio.patch(7))
        pane.key(Action.UNDO);assertEquals(original,fixture.song.studio.patch(7))
        assertFalse(fixture.canUndo)
        pane.key(Action.DOWN);assertEquals(8,state.instrument)
    }

    @Test fun undoWhileHoldingHeightBlocksRepeatUntilANewPress() {
        val original=drawnPatch();val fixture=sessionWith(original)
        val state=ViewState(kind=ViewKind.INSTRUMENT,instrument=7,wavetablePage=1,instField=5)
        val pane=PaneView(fixture,state,1f,Canvas(),{0L},{})
        pane.press(Action.EDIT);pane.key(Action.LEFT)
        assertEquals(126,(fixture.song.studio.patch(7) as WavetablePatch).waves.point(0,0))
        pane.key(Action.UNDO);pane.key(Action.LEFT);pane.release(Action.EDIT)
        assertEquals(original,fixture.song.studio.patch(7))
        assertFalse(fixture.canUndo)
    }

    @Test fun tappingPluckStrikesOnceAndHeldStrikeEditsDoNotRetrigger() {
        val original=PluckPatch("Foil",mapOf(1 to 52,3 to 1371));val fixture=sessionWith(original)
        val canvas=Canvas(); val state=ViewState(kind=ViewKind.INSTRUMENT,instrument=7)
        val pane=PaneView(fixture,state,1f,canvas,{0L},{})
        val strikes=ArrayList<Int>();var releases=0
        fixture.onAudition={_,_,slot,_->strikes+=slot};fixture.onReleaseAudition={releases++}
        pane.draw(320,240);pane.tap(canvas,"Tap to strike")
        assertEquals(listOf(7),strikes);assertEquals(1,releases);assertFalse(fixture.canUndo)
        pane.key(Action.RIGHT) // Material → Strike
        pane.press(Action.AUDITION);pane.press(Action.EDIT);pane.key(Action.RIGHT);pane.key(Action.RIGHT)
        pane.release(Action.EDIT)
        assertEquals(original.withValue(1,54),fixture.song.studio.patch(7))
        assertEquals(listOf(7,7),strikes);assertEquals(1,releases)
        pane.release(Action.AUDITION);pane.key(Action.UNDO);assertEquals(original,fixture.song.studio.patch(7))
    }

    private class Canvas : Surface {
        val bank = ArrayList<String>()
        val text = ArrayList<String>()
        val centers = HashMap<String,Pair<Float,Float>>()
        val waveBounds = Rect()
        var bankRight = 0f
        var bankTop = 0f
        var bankBottom = 0f
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = Unit
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = Unit
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = Unit
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = Unit
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {
            // Only the Draw trace has 64 points. Test fixture includes both full-scale endpoints.
            if(count==64) waveBounds.set(xs[0],ys.take(count).min(),xs[count-1],ys.take(count).max())
        }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            text += s
            centers[s] = x to top+h/2
            if (x < bankRight && top >= bankTop && top < bankBottom) bank += "$s|$x|$top|$h|$color|$face|$bold|$size|$align"
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2f
    }

    private open class EntryMenu : MenuContext {
        var input: ((String) -> Unit)? = null
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int): Unit = error("Unexpected choice menu: $title")
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) { input = accepted }
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit): Unit = error("Unexpected file picker")
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
