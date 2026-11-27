package org.thortracker.system.studio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.*
import org.thortracker.input.Keymap
import org.thortracker.model.*
import org.thortracker.system.ExportFormat
import org.thortracker.system.gb.GbSystem
import org.thortracker.ui.*

class StudioUxPolishTest {
    private class RecordingCanvas : Surface {
        val texts = ArrayList<String>()
        val centers = LinkedHashMap<String, Pair<Float, Float>>()
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            texts += s
            centers[s] = x to (top + h * 0.5f)
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size * 0.5f
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = Unit
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = Unit
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = Unit
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = Unit
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = Unit
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = Unit
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = Unit
        fun clear() { texts.clear(); centers.clear() }
    }

    private class MenuCapture : MenuContext {
        var title = ""
        var rows = emptyList<Pair<String, () -> Unit>>()
        var textPrompt: ((String) -> Unit)? = null
        fun pick(prefix: String) = rows.first { it.first.startsWith(prefix) }.second()
        override fun menu(title: String, entries: List<Pair<String, () -> Unit>>, selected: Int) {
            this.title = title
            this.rows = entries
        }
        override fun text(title: String, initial: String, maxLength: Int, accepted: (String) -> Unit) {
            this.title = title
            this.textPrompt = accepted
        }
        override fun pickFile(title: String, extensions: List<String>, maxBytes: Int, picked: (String, String, ByteArray) -> Unit): Unit =
            kotlin.error("Unexpected file picker")
        override fun status(message: String) = Unit
        override fun error(message: String): Unit = kotlin.error("Unexpected menu error: $message")
        override fun installRhythmRom(bytes: ByteArray): String? = null
    }

    @Test fun standardScreensShowMultiDialBanksWhileCompactBrickUsesSingleFieldCarousel() {
        val song = StudioSound().system.blank().let { base ->
            base.copy(sound = base.studio
                .withPatch(1, FmPatch("Bell"))
                .withPatch(2, PercussionPatch("Kit"))
                .withPatch(3, PluckPatch("Wire")))
        }
        val session = EditorSession(song)
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1)
        val canvas = RecordingCanvas()
        val pane = PaneView(session, state, 1f, canvas, { 0L }, {})

        // Standard 640x480: all FM controls visible at once, Presets in slot bar, no ‹/› field carousel needed.
        canvas.clear()
        pane.draw(640, 480)
        for (label in listOf("Brightness", "Harmonic ratio", "Body", "Feedback", "Attack", "Decay", "Level", "Presets")) {
            assertTrue("Expected $label on 640x480 FM face: ${canvas.texts}", label in canvas.texts)
        }

        // Compact 320x240 Brick fallback: keeps single-parameter carousel rather than cramming dials.
        canvas.clear()
        pane.draw(320, 240)
        assertTrue("Brightness" in canvas.texts)
        assertFalse("320x240 must fall back to single-parameter carousel", "Feedback" in canvas.texts && "Decay" in canvas.texts)

        // Drums on 360x640 portrait: segmented Kick/Snare/Hat model switch + all 5 drum dials + 1-tap model selection.
        state.instrument = 2
        pane.refresh()
        canvas.clear()
        pane.draw(360, 640)
        for (label in listOf("Kick", "Snare", "Hat", "Body", "Snap", "Decay", "Tone", "Level")) {
            assertTrue("Expected $label on 360x640 Drums face: ${canvas.texts}", label in canvas.texts)
        }
        val (sx, sy) = canvas.centers.getValue("Snare")
        assertTrue(pane.touchDown(sx, sy))
        pane.touchUp(sx, sy)
        assertEquals(1, (session.song.studio.patch(2) as PercussionPatch).value(0))

        // Dragging Snap dial directly adjusts Snap in one undo step.
        canvas.clear()
        pane.draw(360, 640)
        val beforeSnap = (session.song.studio.patch(2) as PercussionPatch).value(2)
        val (dx, dy) = canvas.centers.getValue("Snap")
        assertTrue(pane.touchDown(dx, dy))
        pane.touchMove(dx + 32f, dy - 16f)
        pane.touchUp(dx + 32f, dy - 16f)
        assertTrue((session.song.studio.patch(2) as PercussionPatch).value(2) > beforeSnap)
        session.undo()
        assertEquals(beforeSnap, (session.song.studio.patch(2) as PercussionPatch).value(2))
    }

    @Test fun trackStripShowsLiveValuesDragsPedalKnobsAndAddsTracksFromRouter() {
        val base = StudioSound().system.blank().addStudioInsert(0, StudioKind.DELAY)
        val delay = base.studio.tracks[0].inserts.single()
        val boundTrack = base.studio.tracks[0].copy(
            gain = 125, pan = -30,
            bindings = mapOf(14 to StudioBinding(delay.id, StudioKind.DELAY, 2)),
        )
        val session = EditorSession(base.copy(sound = base.studio.copy(tracks = listOf(boundTrack))))
        val state = ViewState(kind = ViewKind.STUDIO_STRIP)
        val canvas = RecordingCanvas()
        val pane = PaneView(session, state, 1f, canvas, { 0L }, {})
        val menu = MenuCapture()
        session.onMenuRequest = { it(menu) }

        // Strip overview displays live gain/pan readouts, occupied pedal status, and bound E–Z count.
        canvas.clear()
        pane.draw(640, 480)
        assertTrue("125 %" in canvas.texts)
        assertTrue("L30" in canvas.texts)
        assertTrue("ON" in canvas.texts)
        assertTrue(canvas.texts.any { it.contains("1 E–Z") })

        // Dragging Pan fader tile directly moves pan.
        val (px, py) = canvas.centers.getValue("Pan")
        assertTrue(pane.touchDown(px, py))
        pane.touchMove(px + 90f, py)
        pane.touchUp(px + 90f, py)
        assertTrue(session.song.studio.tracks[0].pan > -30)

        // Opening Delay shows [E] badge on Feedback and lets touch drag the Feedback knob directly.
        state.stripPedal = delay.id
        state.pedalField = 2
        pane.refresh()
        canvas.clear()
        pane.draw(640, 480)
        assertTrue("Expected bound letter E badge on Feedback knob: ${canvas.texts}", "E" in canvas.texts)
        val beforeFb = session.song.studio.tracks[0].inserts.single().value(2)
        val (fx, fy) = canvas.centers.getValue("Feedback")
        assertTrue(pane.touchDown(fx, fy))
        pane.touchMove(fx + 36f, fy - 20f)
        pane.touchUp(fx + 36f, fy - 20f)
        assertTrue(session.song.studio.tracks[0].inserts.single().value(2) > beforeFb)

        // Route menu offers Add track and switches the strip to Track 2.
        state.stripPedal = 0
        state.stripField = StudioStripView.ROUTE
        pane.press(Action.EDIT); pane.release(Action.EDIT)
        menu.pick("Add track")
        assertEquals(2, session.song.studio.tracks.size)
        assertEquals(1, state.channel)
    }

    @Test fun modularBuildShowsElsewherePortStubsPercentCableReadoutsAndFollowChain() {
        val graph = ModularGraph(
            nodes = listOf(
                ModularNode(1, ModularKind.NOTE),
                ModularNode(2, ModularKind.OSC),
                ModularNode(3, ModularKind.FUNCTION),
                ModularNode(4, ModularKind.OUT),
            ),
            connections = listOf(
                ModularConnection(1, 1, 0, 2, 0),
                ModularConnection(2, 1, 1, 3, 1), // Gate -> 03 Function (elsewhere when viewing 01 -> 02!)
                ModularConnection(3, 2, 0, 4, 0, scale = -450, offset = 125),
            ),
            macros = List(8) { i ->
                if (i == 0) ModularMacro("Sweep", listOf(ModularTarget(2, 1, -12, 12))) else ModularMacro("Macro ${i + 1}")
            },
        )
        val base = StudioSound().system.blank()
        val track0 = base.studio.tracks[0].copy(bindings = mapOf(15 to StudioBinding(0, StudioKind.MODULAR, 0)))
        val session = EditorSession(base.copy(sound = base.studio.copy(tracks = listOf(track0)).withPatch(1, ModularPatch("Lab", graph))))
        val state = ViewState(kind = ViewKind.INSTRUMENT, instrument = 1, modularSource = 1, modularDestination = 2)
        val canvas = RecordingCanvas()
        val pane = PaneView(session, state, 1f, canvas, { 0L }, {})
        val menu = MenuCapture()
        session.onMenuRequest = { it(menu) }

        // Performance face shows bound letter F, target subtitle, and Unbound labels on unused macros.
        canvas.clear()
        pane.draw(640, 480)
        assertTrue("F" in canvas.texts)
        assertTrue(canvas.texts.any { it.contains("Oscillator Tune") })
        assertTrue("Unbound" in canvas.texts)

        // Build face shows →03 elsewhere indicator on Gate and offers Oscillator controls even while Send from is 01 Note.
        StudioSoundView.selectMode(pane.controller, true)
        canvas.clear()
        pane.draw(640, 480)
        assertTrue("Expected →03 elsewhere stub on Note:Gate: ${canvas.texts}", "→03" in canvas.texts)
        assertTrue(canvas.texts.any { it.contains("Oscillator controls") })

        // Cable inspector formats permille as readable percentages.
        ModularControls.activate(pane.controller, ModularControls.CABLES)
        menu.pick("02 Oscillator:Audio → 04 Output:Left")
        assertTrue("Expected −45% in ${menu.rows.map { it.first }}", menu.rows.any { it.first == "Amount  −45%" })
        assertTrue("Expected +12.5% in ${menu.rows.map { it.first }}", menu.rows.any { it.first == "Offset  +12.5%" })

        // Send-from picker offers 1-click Follow chain › 02 Oscillator → 04 Output.
        ModularControls.activate(pane.controller, ModularControls.SOURCE)
        menu.pick("Follow chain › 02 Oscillator")
        assertEquals(2, state.modularSource)
        assertEquals(4, state.modularDestination)
    }

    @Test fun patternHeaderOffersAddTrackAndStripOnlyOnStudioSongs() {
        val gbCanvas = RecordingCanvas()
        PaneView(EditorSession(GbSystem.blank()), ViewState(), 1f, gbCanvas, { 0L }, {}).draw(1280, 720)
        assertFalse("+ Track" in gbCanvas.texts)
        assertFalse("Strip" in gbCanvas.texts)

        val studioSession = EditorSession(StudioSound().system.blank())
        val studioState = ViewState()
        val studioCanvas = RecordingCanvas()
        val pane = PaneView(studioSession, studioState, 1f, studioCanvas, { 0L }, {})
        pane.draw(1280, 720)
        assertTrue("+ Track" in studioCanvas.texts)
        assertTrue("Strip" in studioCanvas.texts)

        val (ax, ay) = studioCanvas.centers.getValue("+ Track")
        assertTrue(pane.touchDown(ax, ay))
        pane.touchUp(ax, ay)
        assertEquals(2, studioSession.song.studio.tracks.size)
        assertEquals(1, studioState.channel)
    }

    @Test fun groupedMainMenuKeepsHeadingsNonSelectableAndExposesStudioTracksDirectly() {
        val session = EditorSession(StudioSound().system.blank())
        val popups = Popups({ null }, { listOf(0) })
        val menus = AppMenus(session, Workspace(listOf(ViewState())), Transport(session), popups, object : MenuHost {
            override val modalDisplay = 0
            override val displayCount = 1
            override val keymap = Keymap.DEFAULT
            override fun refresh() = Unit
            override fun status(message: String) = Unit
            override fun error(message: String): Unit = kotlin.error(message)
            override fun applyKeymap(next: Keymap, message: String?) = Unit
            override fun resetInput() = Unit
            override fun editAction(action: Action) = Unit
            override fun switchFocus() = Unit
            override fun swap() = Unit
            override fun showSongDetails() = Unit
            override fun replace(song: Song) = Unit
            override fun openProject() = Unit
            override fun saveProject() = Unit
            override fun saveProjectAs() = Unit
            override fun export(song: Song, format: ExportFormat) = Unit
            override fun exportWav(song: Song, options: WavOptions) = Unit
            override fun showWavExport() = false
            override fun recoverPrevious() = Unit
            override fun exportKeymap() = Unit
            override fun importKeymap() = Unit
            override fun loadTuningPacks(loaded: (List<InstalledTuningPack>) -> Unit) = Unit
            override fun importTuningPack(done: () -> Unit) = Unit
            override fun removeTuningPack(id: String, done: () -> Unit) = Unit
            override fun loadTuningFile(loaded: (Tuning) -> Unit) = Unit
            override fun loadFile(title: String, extensions: List<String>, maxBytes: Int, loaded: (String, String, ByteArray) -> Unit) = Unit
            override fun installRhythmRom(bytes: ByteArray): String? = null
            override fun diagnostics() = emptyList<Pair<String, String>>()
            override fun shareDiagnostics(text: String) = Unit
        })
        menus.appMenu()
        val spec = requireNotNull(popups.top)
        assertTrue(spec.selectable(spec.selected))
        assertEquals("Files", spec.items[spec.selected].label)
        val tracksIdx = spec.items.indexOfFirst { it.label == "Studio tracks and destinations…" }
        assertTrue(tracksIdx >= 0)
        popups.choose(tracksIdx)
        val trackMenu = requireNotNull(popups.top)
        assertEquals("Studio track 1 of 1", trackMenu.title)
        assertEquals("Add track", trackMenu.items.first().label)
    }
}
