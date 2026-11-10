package org.thortracker.ui

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.PaneZoom
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Binding
import org.thortracker.input.Control
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.Cell
import org.thortracker.model.InstalledTuningPack
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Tuning
import org.thortracker.system.ExportFormat
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.opl3.Opl3System

class WorkspaceZoomTest {
    @Test fun discreteLimitsAndNativePreferenceMigration() {
        for (zoom in PaneZoom.entries) {
            assertSame(zoom, PaneZoom.fromStored(zoom.percent.toString()))
            assertSame(zoom, PaneZoom.fromStored(" ${zoom.percent}\n"))
            assertSame(zoom, zoom.step(0))
        }
        assertSame(PaneZoom.SMALL, PaneZoom.SMALL.step(-1))
        assertSame(PaneZoom.LARGEST, PaneZoom.LARGEST.step(1))
        assertSame(PaneZoom.LARGE, PaneZoom.NORMAL.step(Int.MAX_VALUE))
        assertSame(PaneZoom.SMALL, PaneZoom.NORMAL.step(Int.MIN_VALUE))
        for (stored in listOf(null, "", "bad", "74", "151", "125.5", "NaN", "4294967396")) {
            assertSame(PaneZoom.NORMAL, PaneZoom.fromStored(stored))
        }
    }

    @Test fun menuAndReboundControlsChangeOnlyTheTargetPaneAndNotDocumentOrExportBytes() {
        val session = EditorSession(GbSystem.blank())
        session.setTitle("An undoable edit")
        val source = session.song
        val saved = ProjectCodec.encode(source)
        val exported = GbSystem.exports.single().export(source)
        val version = session.documentVersion
        val undo = session.undoLabel
        val panes = Workspace(listOf(ViewState(), ViewState(kind = ViewKind.HELP)))
        val host = Host()
        val popups = Popups({ null }, { listOf(0, 1) })
        val menus = AppMenus(session, panes, Transport(session), popups, host)
        // The app menu explicitly targets controller focus even when opened on the other display.
        menus.appMenu()
        popups.choose(requireNotNull(popups.top).items.indexOfFirst { it.label?.startsWith("Focused view zoom:") == true })
        popups.choose(0)
        assertSame(PaneZoom.LARGE, panes.panes[0].zoom)
        assertSame(PaneZoom.NORMAL, panes.panes[1].zoom)
        assertEquals(1, host.refreshes)
        repeat(4) { menus.zoom(0, 1) }
        assertSame(PaneZoom.LARGEST, panes.panes[0].zoom)
        repeat(4) { menus.zoom(0, -1) }
        assertSame(PaneZoom.SMALL, panes.panes[0].zoom)
        menus.zoom(0, 0)
        assertSame(PaneZoom.NORMAL, panes.panes[0].zoom)

        val zoomControls = listOf(Control.ZOOM_IN, Control.ZOOM_OUT, Control.ZOOM_RESET)
        val custom = Keymap.DEFAULT.with(Control.COPY, Binding(Keys.BUTTON_C))
        // An older custom file has no zoom keys; loading must not take any of its bindings.
        val legacy = custom.toJson().replace(Regex(",?\\s*\\\"ZOOM_(IN|OUT|RESET)\\\"\\s*:\\s*null"), "")
        val loaded = Keymap.fromJson(legacy)
        for (control in Control.entries.filter { it !in zoomControls }) assertEquals(custom.binding(control), loaded.binding(control))
        for (control in zoomControls) assertNull(loaded.binding(control))
        for ((index, control) in zoomControls.withIndex()) {
            val key = Keys.BUTTON_1 + index
            val bound = Keymap.fromJson(loaded.with(control, Binding(key)).toJson())
            val input = HostInput().apply { keymap = bound }
            assertEquals(HostInput.Route.Run(control), input.route(key, true, 0, false, false, ViewKind.PATTERN))
            assertFalse(input.route(key, true, 1, false, false, ViewKind.PATTERN) is HostInput.Route.Run)
        }
        assertSame(source, session.song)
        assertEquals(saved, ProjectCodec.encode(session.song))
        assertArrayEquals(exported, GbSystem.exports.single().export(session.song))
        assertEquals(version, session.documentVersion)
        assertEquals(undo, session.undoLabel)
        session.undo()
        assertEquals(GbSystem.blank().title, session.song.title)
        session.redo()
        assertEquals("An undoable edit", session.song.title)
    }

    @Test fun magnificationTravelsWithViewSwapsAndViewChords() {
        val first = ViewState(zoom = PaneZoom.LARGEST)
        val second = ViewState(kind = ViewKind.KEYBOARD, zoom = PaneZoom.SMALL)
        val workspace = Workspace(listOf(first, second))
        workspace.showView(ViewKind.HELP, GbSystem.views)
        assertSame(PaneZoom.LARGEST, first.zoom)
        workspace.showView(ViewKind.HELP, GbSystem.views)
        assertSame(ViewKind.PATTERN, first.kind)
        workspace.swap()
        assertSame(first, workspace.panes[1])
        assertSame(PaneZoom.LARGEST, workspace.panes[1].zoom)
        assertSame(PaneZoom.SMALL, workspace.panes[0].zoom)
        assertSame(PaneZoom.LARGEST, first.copy().zoom)
    }

    @Test fun normalSizeKeepsOriginalMetricsAndResetRestoresDrawingWithoutScalingModalControls() {
        val surface = RecordingSurface()
        val state = ViewState()
        val pane = PaneView(EditorSession(GbSystem.blank()), state, 1f, surface, { 0L }, {})
        for ((width, height) in listOf(320 to 240, 640 to 480, 1280 to 800)) {
            state.zoom = PaneZoom.NORMAL
            pane.draw(width, height)
            val baseline = surface.commands.toList()
            val unit = maxOf(9f, minOf(12f, minOf(width / 31f, height / 24f)))
            assertEquals(unit, pane.ink.unit, 0f)
            for (zoom in PaneZoom.entries) {
                state.zoom = zoom
                surface.clear()
                pane.draw(width, height)
                assertEquals(unit * zoom.factor, pane.ink.unit, 0f)
                assertEquals(12f, pane.ink.dp(12f), 0f) // physical targets, borders and shell stay stable
            }
            state.zoom = PaneZoom.NORMAL
            surface.clear()
            pane.draw(width, height)
            assertEquals(baseline, surface.commands)
            surface.clear()
        }
        val popup = DrawnPopup(Ink(1f, surface))
        val popups = Popups({ popup }, { listOf(0) })
        popups.show(PopupSpec("Menu", items = listOf(PopupItem.dismiss())), 0)
        surface.clear()
        popup.draw(surface, 640f, 480f)
        val normalPopup = surface.commands.toList()
        pane.ink.layout(640, 480, PaneZoom.LARGEST)
        surface.clear()
        popup.draw(surface, 640f, 480f)
        assertEquals(normalPopup, surface.commands)
    }

    @Test fun compactGridKeepsFocusedCellVisibleAndTouchUsesTheNewGeometry() {
        for ((width, height) in listOf(320 to 240, 400 to 420, 640 to 480)) {
            val session = EditorSession(Opl3System.blank())
            val channel = Opl3System.channels.lastIndex
            session.setCell(0, channel, 50, Cell(48, 1))
            val state = ViewState(row = 50, channel = channel)
            val surface = RecordingSurface()
            val pane = PaneView(session, state, 1f, surface, { 0L }, {})
            pane.controllerFocused = true
            for (zoom in PaneZoom.entries) {
                pane.touchScrolled = true
                state.zoom = zoom
                pane.refresh()
                surface.clear()
                pane.draw(width, height)
                val note = surface.texts.first { it.value == Opl3System.noteName(48) && it.face == Face.MONO }
                assertTrue(note.x >= 0 && note.x + surface.textWidth(note.value, Face.MONO, false, note.size) <= width)
                assertTrue(note.top >= 0 && note.top + note.height <= height - pane.ink.unit * 1.9f)
                val x = note.x + 1f
                val y = note.top + note.height / 2
                state.row = 0
                state.channel = 0
                assertTrue(pane.touchDown(x, y))
                pane.touchUp(x, y)
                assertEquals(50, state.row)
                assertEquals(channel, state.channel)
                assertEquals(0, state.field)
                assertTrue(pane.touchDown(x, y))
                state.zoom = if (zoom == PaneZoom.LARGEST) PaneZoom.SMALL else PaneZoom.LARGEST
                pane.refresh()
                assertFalse(pane.touchActive)
                assertFalse("Old hit rectangles must not accept a new gesture before repaint", pane.touchDown(x, y))
            }
        }
    }

    @Test fun everyExpandedEffectFieldRemainsVisibleAndTouchableAtBothZoomExtremes() {
        for (density in listOf(1f, 2f)) {
            val width = (320 * density).toInt()
            val height = (240 * density).toInt()
            val session = EditorSession(Opl3System.blank())
            val state = ViewState(row = 50, channel = Opl3System.channels.lastIndex,
                effectColumns = List(Opl3System.channels.size) { Cell.MAX_EFFECT_COLUMNS })
            val surface = RecordingSurface()
            val pane = PaneView(session, state, density, surface, { 0L }, {})
            pane.controllerFocused = true
            for (zoom in listOf(PaneZoom.SMALL, PaneZoom.LARGEST)) {
                state.zoom = zoom
                for (field in 0 until org.thortracker.editor.ViewController.MAX_FIELDS) {
                    state.field = field
                    pane.refresh()
                    surface.clear()
                    pane.draw(width, height)
                    val cursor = surface.commands.single { it[0] == "stroke" && it[5] == pane.palette.FOCUS }
                    val left = cursor[1] as Float
                    val top = cursor[2] as Float
                    val right = cursor[3] as Float
                    val bottom = cursor[4] as Float
                    assertTrue("field=$field zoom=$zoom", left >= 0 && right <= width && top >= 0 && bottom <= height)
                    val x = (left + right) / 2
                    val y = (top + bottom) / 2
                    state.field = 0
                    assertTrue(pane.touchDown(x, y))
                    pane.touchUp(x, y)
                    assertEquals(field, state.field)
                }
            }
        }
    }

    @Test fun helpAtChangedTextSizeMatchesAFreshPaneEvenWhenWrapWidthIsUnchanged() {
        val session = EditorSession(GbSystem.blank())
        val surface = RecordingSurface()
        val state = ViewState(kind = ViewKind.HELP, helpPage = 1)
        val pane = PaneView(session, state, 1f, surface, { 0L }, {})
        val painter = HelpPainter(pane)
        pane.ink.layout(640, 480)
        val bodyWidth = 640f - pane.ink.unit * 1.8f
        painter.draw(surface, Rect(0f, 0f, 640f, 420f))
        // Match the old wrap width exactly; font size alone must invalidate the cached lines.
        pane.ink.layout(640, 480, PaneZoom.LARGEST)
        val area = Rect(0f, 0f, bodyWidth + pane.ink.unit * 1.8f, 420f)
        assertEquals(bodyWidth, area.width() - pane.ink.unit * 1.8f, 0f)
        surface.clear()
        painter.draw(surface, area)
        val expectedSurface = RecordingSurface()
        val fresh = PaneView(session, state.copy(), 1f, expectedSurface, { 0L }, {})
        fresh.ink.layout(640, 480, PaneZoom.LARGEST)
        HelpPainter(fresh).draw(expectedSurface, area)
        assertEquals(expectedSurface.commands, surface.commands)
    }

    private data class Text(val value: String, val x: Float, val top: Float, val height: Float, val size: Float, val face: Face)
    private class RecordingSurface : Surface {
        val commands = mutableListOf<List<Any>>()
        val texts = mutableListOf<Text>()
        private fun record(vararg values: Any) { commands += values.toList() }
        fun clear() { commands.clear(); texts.clear() }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) {
            texts += Text(s, x, top, h, size, face)
            record("text", s, x, top, h, color, face, bold, size, align)
        }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) = record("rect", l, t, r, b, color)
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) = record("round", l, t, r, b, radius, color)
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) = record("stroke", l, t, r, b, color, width, radius)
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = record("line", x0, y0, x1, y1, color, width)
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) = record("circle", cx, cy, radius, color)
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) = record("circleStroke", cx, cy, radius, color, width)
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) = record("polyline", xs.take(count), ys.take(count), color, width)
    }

    private class Host : MenuHost {
        override val modalDisplay = 1
        override val displayCount = 2
        override val keymap = Keymap.DEFAULT
        var refreshes = 0
        override fun refresh() { refreshes++ }
        override fun status(message: String) {}
        private fun unexpected(): Nothing = throw AssertionError("Zoom must not perform document or platform operations")
        override fun replace(song: Song) = unexpected()
        override fun export(song: Song, format: ExportFormat) = unexpected()
        override fun error(message: String) = unexpected()
        override fun applyKeymap(next: Keymap, message: String?) = unexpected()
        override fun resetInput() = unexpected()
        override fun editAction(action: Action) = unexpected()
        override fun switchFocus() = unexpected()
        override fun swap() = unexpected()
        override fun showSongDetails() = unexpected()
        override fun openProject() = unexpected()
        override fun saveProject() = unexpected()
        override fun saveProjectAs() = unexpected()
        override fun exportWav(song: Song, options: WavOptions) = unexpected()
        override fun showWavExport(): Boolean = unexpected()
        override fun recoverPrevious() = unexpected()
        override fun exportKeymap() = unexpected()
        override fun importKeymap() = unexpected()
        override fun loadTuningPacks(loaded: (List<InstalledTuningPack>) -> Unit) = unexpected()
        override fun importTuningPack(done: () -> Unit) = unexpected()
        override fun removeTuningPack(id: String, done: () -> Unit) = unexpected()
        override fun loadTuningFile(loaded: (Tuning) -> Unit) = unexpected()
        override fun loadFile(title: String, extensions: List<String>, maxBytes: Int, loaded: (String, String, ByteArray) -> Unit) = unexpected()
        override fun installRhythmRom(bytes: ByteArray): String? = unexpected()
        override fun diagnostics(): List<Pair<String, String>> = unexpected()
        override fun shareDiagnostics(text: String) = unexpected()
    }
}
