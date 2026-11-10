package org.thortracker.ui

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.Selection
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Keymap
import org.thortracker.model.*
import org.thortracker.system.ExportFormat
import org.thortracker.system.SoundSystem
import org.thortracker.system.SoundSystems
import org.thortracker.system.gb.GbSystem
import org.thortracker.system.opl3.Opl3System

class ConversionMenuTest {
    private class Host(val session: EditorSession) : MenuHost {
        override val modalDisplay = 0
        override val displayCount = 1
        override val keymap = Keymap.DEFAULT
        var replacements = 0
        var previous: Song? = null
        var currentFile: String? = "original.thor"
        var exported: Pair<Song, ExportFormat>? = null
        val errors = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        override fun replace(song: Song) {
            replacements++
            previous = session.song
            currentFile = null
            session.replaceSong(song)
        }
        override fun export(song: Song, format: ExportFormat) { exported = song to format }
        override fun error(message: String) { errors += message }
        override fun status(message: String) { statuses += message }
        override fun refresh() {}
        private fun unexpected(): Nothing = throw AssertionError("Unexpected host operation")
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

    private class Fixture(song: Song = GbSystem.blank()) {
        val session = EditorSession(song)
        val host = Host(session)
        val popups = Popups({ null }, { listOf(0) })
        val transport = Transport(session)
        val menus = AppMenus(session, Workspace(listOf(ViewState())), transport, popups, host)
        val top get() = requireNotNull(popups.top)
        fun choose(label: String) {
            val index = top.items.indexOfFirst { it.label == label }
            check(index >= 0) { "Missing $label in ${top.title}: ${top.items.map { it.label }}" }
            popups.choose(index)
        }
        fun preview(target: SoundSystem = Opl3System) {
            popups.closeAll()
            menus.fileMenu()
            choose("Convert to system…")
            val family = target.family
            if (family != null) choose("$family…")
            val group = target.familyGroup
            if (group != null) choose("$group…")
            choose(target.name)
        }
    }

    @Test fun previewCancelAndConfirmationDefaultNeverReplaceOrAlterEditorState() {
        val f = Fixture()
        f.session.setTitle("Unsaved original")
        f.session.select(Selection(row = 12, channel = 1))
        val source = f.session.song
        val selection = f.session.selection
        val version = f.session.documentVersion
        val undo = f.session.undoLabel
        f.preview()
        assertEquals("Cancel", f.top.items[f.top.selected].label)
        assertSame(source, f.session.song)
        assertEquals(selection, f.session.selection)
        assertEquals(version, f.session.documentVersion)
        assertEquals(undo, f.session.undoLabel)
        assertEquals("original.thor", f.host.currentFile)
        assertNull(f.host.previous)
        f.popups.choose(f.top.selected)
        assertFalse(f.popups.isOpen)
        assertEquals(0, f.host.replacements)
        f.preview()
        f.choose("Apply conversion…")
        assertEquals("Replace working song?", f.top.title)
        assertNull(f.top.items[f.top.selected].action)
        f.popups.choose(f.top.selected)
        assertSame(source, f.session.song)
        assertEquals(0, f.host.replacements)
    }

    @Test fun onlyExplicitAcceptanceUsesProtectedReplacementOnce() {
        val f = Fixture()
        val source = f.session.song
        f.preview()
        f.choose("Apply conversion…")
        assertEquals(0, f.host.replacements)
        f.choose("Continue")
        assertEquals(1, f.host.replacements)
        assertSame(source, f.host.previous)
        assertNull(f.host.currentFile)
        assertEquals(Opl3System.id, f.session.song.system.id)
        assertFalse(f.popups.isOpen)
    }

    @Test fun editsEvenWhenUndoneAndDocumentReplacementsRejectStalePreview() {
        for (change in listOf<(EditorSession) -> Unit>(
            { it.setTitle("Edited during preview") },
            { it.setTitle("Changed then undone"); it.undo() },
            { it.replaceSong(it.song) },
        )) {
            val f = Fixture()
            f.preview()
            f.choose("Apply conversion…")
            change(f.session)
            val before = f.session.song
            f.choose("Continue")
            assertEquals(0, f.host.replacements)
            assertSame(before, f.session.song)
            assertTrue(f.host.errors.single().contains("stale"))
            assertEquals("original.thor", f.host.currentFile)
        }
    }

    @Test fun sameSystemSelectionDoesNotEvenOfferReplacement() {
        val f = Fixture()
        val source = f.session.song
        f.preview(GbSystem)
        assertFalse(f.popups.isOpen)
        assertSame(source, f.session.song)
        assertEquals(0, f.host.replacements)
        assertTrue(f.host.statuses.single().contains("unchanged"))
    }

    @Test fun groupedChooserReachesEveryRegisteredSystemWithoutChangingTheDocument() {
        val f = Fixture()
        val source = f.session.song
        for (target in SoundSystems.ALL) {
            f.preview(target)
            if (target.id != source.system.id) {
                assertEquals("Convert to ${target.name}", f.top.title)
                assertEquals("Cancel", f.top.items[f.top.selected].label)
                f.choose("Cancel")
            }
            assertSame(source, f.session.song)
            assertEquals(0, f.host.replacements)
            assertNull(f.host.previous)
            assertEquals("original.thor", f.host.currentFile)
        }
    }

    @Test fun invalidSourceStillOffersFullReportButNeverApply() {
        val f = Fixture(GbSystem.blank().copy(orders = listOf(999)))
        val source = f.session.song
        f.preview()
        assertFalse(f.top.items.any { it.label == "Apply conversion…" })
        assertTrue(f.top.message!!.contains("blocked"))
        f.choose("Save report as .txt…")
        val (carrier, format) = requireNotNull(f.host.exported)
        assertTrue(carrier.system.errors(carrier).isEmpty())
        assertTrue(format.export(carrier).decodeToString().contains("BLOCKER [Source]"))
        assertSame(source, f.session.song)
        assertEquals(0, f.host.replacements)
    }

    @Test fun reportPaginationReachesEveryPageAndExportRemainsBoundToPreview() {
        val f = Fixture(GbSystem.demo())
        val source = f.session.song
        f.preview()
        val preview = f.top
        f.choose("Read complete report")
        val pages = mutableListOf<String>()
        do {
            pages += requireNotNull(f.top.message)
            val next = f.top.items.any { it.label == "Next page" }
            if (next) f.choose("Next page")
        } while (next)
        assertTrue(pages.size > 1)
        assertEquals(pages.size, f.top.title.substringAfterLast('/').toInt())
        f.popups.back()
        assertSame(preview, f.top)
        f.choose("Save report as .txt…")
        assertSame(source, f.session.song)
        val (carrier, format) = requireNotNull(f.host.exported)
        assertEquals("txt", format.extension)
        val saved = format.export(carrier).decodeToString()
        assertTrue(saved.contains("Lane mapping"))
        assertTrue(saved.contains("Song: ${source.title}"))
        for (page in pages) for (line in page.lines()) if (line.isNotEmpty()) assertTrue(line, saved.contains(line))
        f.session.setTitle("A different document title")
        assertEquals(saved, format.export(f.session.song).decodeToString())
        assertEquals(0, f.host.replacements)
        assertEquals("original.thor", f.host.currentFile)
    }
}
