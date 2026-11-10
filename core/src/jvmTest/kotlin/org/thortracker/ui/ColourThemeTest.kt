package org.thortracker.ui

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewKind
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.InstalledTuningPack
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Tuning
import org.thortracker.system.ExportFormat
import org.thortracker.system.SoundSystems
import org.thortracker.system.gb.GbSystem

class ColourThemeTest {
    @Test fun storedIdsRoundTripAndUnknownOrMissingPreferencesUseSlate() {
        for (palette in Palette.all) assertSame(palette, Palette.fromId(palette.id))
        for (id in listOf(null, "", "unknown", "PAPER")) assertSame(Palette.SLATE, Palette.fromId(id))
        assertSame(Palette.SLATE, Workspace(listOf(ViewState())).palette)
    }

    @Test fun sharedMenuWorksByControllerAndDirectChoiceWithoutChangingSongHistoryOrAnotherWorkspace() {
        val session = EditorSession(GbSystem.blank())
        session.setTitle("Keep this edit")
        val source = session.song
        val saved = ProjectCodec.encode(source)
        val version = session.documentVersion
        val undo = session.undoLabel
        val workspace = Workspace(listOf(ViewState(), ViewState(kind = ViewKind.HELP)))
        val other = Workspace(listOf(ViewState()))
        var refreshes = 0
        val host = Host { refreshes++ }
        val popups = Popups({ null }, { listOf(0, 1) })
        val menus = AppMenus(session, workspace, Transport(session), popups, host)
        menus.appMenu()
        val themeEntry = requireNotNull(popups.top).items.indexOfFirst { it.label == "Colour theme" }
        assertTrue(themeEntry >= 0)
        popups.choose(themeEntry)
        assertEquals("Slate", requireNotNull(popups.top).items[requireNotNull(popups.top).selected].label)
        popups.handleKey(Keys.DPAD_DOWN, true, 0)
        popups.handleKey(Keys.BUTTON_A, true, 0)
        assertSame(Palette.PAPER, workspace.palette)
        menus.colourTheme()
        popups.choose(requireNotNull(popups.top).items.indexOfFirst { it.label == "Contrast" })
        assertSame(Palette.CONTRAST, workspace.palette)
        assertEquals(2, refreshes)
        assertSame(Palette.SLATE, other.palette)
        assertSame(source, session.song)
        assertEquals(saved, ProjectCodec.encode(session.song))
        assertEquals(version, session.documentVersion)
        assertEquals(undo, session.undoLabel)
        session.undo()
        assertEquals(GbSystem.blank().title, session.song.title)
        session.redo()
        assertEquals("Keep this edit", session.song.title)
    }

    @Test fun existingPaintersAndPopupsRecolourInPlaceWithoutLeakingToAnotherPane() {
        val surface = RecordingSurface()
        val otherSurface = RecordingSurface()
        val other = PaneView(EditorSession(GbSystem.blank()), ViewState(), 1f, otherSurface, { 0L }, {})
        for (system in SoundSystems.ALL) {
            val session = EditorSession(system.blank())
            val pane = PaneView(session, ViewState(), 1f, surface, { 0L }, {})
            for (kind in listOf(ViewKind.PATTERN, ViewKind.INSTRUMENT, ViewKind.HELP).filter { it in system.views }) {
                pane.state = ViewState(kind = kind)
                for (palette in Palette.all) {
                    pane.palette = palette
                    surface.clear()
                    pane.draw(1024, 768)
                    assertEquals("${system.id} $kind ${palette.id}", palette.BG, surface.fills.first())
                    assertTrue(surface.textColours.contains(palette.TEXT))
                }
            }
            assertSame(Palette.SLATE, other.palette)
        }
        other.draw(1024, 768)
        assertEquals(Palette.SLATE.BG, otherSurface.fills.first())
        val popup = DrawnPopup(Ink(1f, surface))
        val popups = Popups({ popup }, { listOf(0) })
        val spec = PopupSpec("Theme dialog", message = "Still open", items = listOf(PopupItem.dismiss()))
        popups.show(spec, 0)
        for (palette in Palette.all) {
            popup.palette = palette
            surface.clear()
            popup.draw(surface, 640f, 480f)
            assertTrue(surface.fills.contains(palette.BG))
            assertTrue(surface.textColours.contains(palette.TEXT))
            assertSame(spec, popups.top)
        }
    }

    private class RecordingSurface : Surface {
        val fills = mutableListOf<Int>()
        val textColours = mutableListOf<Int>()
        fun clear() { fills.clear(); textColours.clear() }
        override fun text(s: String, x: Float, top: Float, h: Float, color: Int, face: Face, bold: Boolean, size: Float, align: Align) { textColours += color }
        override fun textWidth(s: String, face: Face, bold: Boolean, size: Float) = s.length * size / 2
        override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) { fills += color }
        override fun fillRound(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int) { fills += color }
        override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, radius: Float) {}
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {}
        override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float) {}
    }

    private class Host(private val refreshed: () -> Unit) : MenuHost {
        override val modalDisplay = 0
        override val displayCount = 2
        override val keymap = Keymap.DEFAULT
        override fun refresh() = refreshed()
        private fun unexpected(): Nothing = throw AssertionError("Theme selection must not perform document or platform operations")
        override fun replace(song: Song) = unexpected()
        override fun export(song: Song, format: ExportFormat) = unexpected()
        override fun error(message: String) = unexpected()
        override fun status(message: String) = unexpected()
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
