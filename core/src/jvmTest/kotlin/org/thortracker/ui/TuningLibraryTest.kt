package org.thortracker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.Action
import org.thortracker.editor.EditorSession
import org.thortracker.editor.ViewState
import org.thortracker.editor.Workspace
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.InstalledTuningPack
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Scl
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.model.Tuning
import org.thortracker.model.TuningPack
import org.thortracker.model.TuningPackEntry
import org.thortracker.system.ExportFormat

class TuningLibraryTest {
    private val valid = TuningPackEntry("original/tritave.scl", "Same name", "Ratios", "Scale\n2\n3/2 fifth\n3/1 period\n", "Entry author")
    private val invalid = valid.copy(id = "original/invalid.scl", scl = "Scale\n2\n3/2\n1/1\n")
    private fun pack(entries: List<TuningPackEntry>, bundled: Boolean = false) = InstalledTuningPack(
        TuningPack(id = "collection", name = "Test collection", attribution = "Collection licence", entries = entries), bundled)

    private class Host(var packs: List<InstalledTuningPack>) : MenuHost {
        override val modalDisplay = 0
        override val displayCount = 1
        override val keymap = Keymap.DEFAULT
        var loads = 0
        var imports = 0
        val removed = mutableListOf<String>()
        var deferred = false
        var pending: ((List<InstalledTuningPack>) -> Unit)? = null
        var incoming: InstalledTuningPack? = null
        override fun loadTuningPacks(loaded: (List<InstalledTuningPack>) -> Unit) {
            loads++
            if (deferred) pending = loaded else loaded(packs)
        }
        override fun importTuningPack(done: () -> Unit) {
            imports++
            packs = packs + requireNotNull(incoming)
            done()
        }
        override fun removeTuningPack(id: String, done: () -> Unit) {
            removed += id
            packs = packs.filterNot { it.pack.id == id }
            done()
        }
        override fun status(message: String) {}
        override fun refresh() {}
        override fun error(message: String): Unit = throw AssertionError(message)
        private fun unexpected(): Nothing = throw AssertionError("Unexpected host operation")
        override fun applyKeymap(next: Keymap, message: String?) = unexpected()
        override fun resetInput() = unexpected()
        override fun editAction(action: Action) = unexpected()
        override fun switchFocus() = unexpected()
        override fun swap() = unexpected()
        override fun showSongDetails() = unexpected()
        override fun replace(song: Song) = unexpected()
        override fun openProject() = unexpected()
        override fun saveProject() = unexpected()
        override fun saveProjectAs() = unexpected()
        override fun export(song: Song, format: ExportFormat) = unexpected()
        override fun exportWav(song: Song, options: WavOptions) = unexpected()
        override fun showWavExport(): Boolean = unexpected()
        override fun recoverPrevious() = unexpected()
        override fun exportKeymap() = unexpected()
        override fun importKeymap() = unexpected()
        override fun loadTuningFile(loaded: (Tuning) -> Unit) = unexpected()
        override fun loadFile(title: String, extensions: List<String>, maxBytes: Int, loaded: (String, String, ByteArray) -> Unit) = unexpected()
        override fun installEpsmRhythmRom(bytes: ByteArray): String? = unexpected()
        override fun diagnostics(): List<Pair<String, String>> = unexpected()
        override fun shareDiagnostics(text: String) = unexpected()
    }

    private class Fixture(installed: List<InstalledTuningPack>, song: Song = Songs.blank()) {
        val session = EditorSession(song)
        val host = Host(installed)
        val popups = Popups({ null }, { listOf(0) })
        val menus = AppMenus(session, Workspace(listOf(ViewState())), Transport(session), popups, host)
        val top get() = requireNotNull(popups.top)
        fun press(key: Int) { popups.handleKey(key, true, 0); popups.handleKey(key, false, 0) }
        fun choose(prefix: String) {
            val index = top.items.indexOfFirst { it.label?.startsWith(prefix) == true }
            check(index >= 0) { "Missing action $prefix" }
            val target = index + if (top.text == null) 0 else 1
            while (top.selected < target) press(Keys.DPAD_DOWN)
            while (top.selected > target) press(Keys.DPAD_UP)
            press(Keys.BUTTON_A)
        }
        fun collection() { menus.tuningLibrary(); choose("Test collection") }
    }

    @Test fun libraryLoadsOnlyWhenOpenedAndSupportsAsynchronousHosts() {
        val f = Fixture(listOf(pack(listOf(valid))))
        assertEquals(0, f.host.loads)
        f.menus.tuningMenu()
        assertEquals(0, f.host.loads)
        f.host.deferred = true
        f.choose("Tuning library")
        assertEquals(1, f.host.loads)
        f.host.pending!!(f.host.packs)
        f.choose("Test collection")
        f.choose("All")
        f.choose(valid.name)
        assertTrue(f.top.items.any { it.label == "Apply" })
    }

    @Test fun pagesKeepEveryIdentityAndBackReturnsToCollectionRatherThanPreviousPage() {
        val entries = (0 until 65).map { valid.copy(id = "source/$it.scl") }
        val f = Fixture(listOf(pack(entries)))
        f.collection()
        val collection = f.top
        f.choose("All")
        val seen = mutableListOf<String>()
        while (true) {
            assertTrue(f.top.items.size <= 27)
            seen += f.top.items.mapNotNull { it.label }.filter { it.startsWith(valid.name) }.map { it.substringAfter(" · ") }
            if (f.top.items.none { it.label == "Next page" }) break
            f.choose("Next page")
        }
        assertEquals(entries.map { it.id }, seen)
        f.choose("Previous page")
        f.press(Keys.BUTTON_B)
        assertTrue(f.top === collection)
    }

    @Test fun invalidEntriesStayVisibleAndShowTheActualParserReasonWithoutAnApplyAction() {
        val f = Fixture(listOf(pack(listOf(invalid, valid))))
        val before = f.session.song
        f.collection(); f.choose("All"); f.choose(invalid.name)
        val reason = try { Scl.parse(invalid.scl, invalid.name); throw AssertionError("Fixture must be invalid") }
        catch (e: IllegalArgumentException) { e.message!! }
        assertTrue(f.top.message!!.contains(reason))
        assertFalse(f.top.items.any { it.label == "Apply" })
        assertTrue(f.top.message!!.contains(invalid.id))
        assertEquals(before, f.session.song)
    }

    @Test fun categoryAndSearchFindIdentitiesAndCreditsWithoutNormalisingStoredNames() {
        val controlled = valid.copy(id = "special/\u0001original.scl", name = "Odd\nname", category = "Other", attribution = "Specific AUTHOR")
        val f = Fixture(listOf(pack(listOf(valid, controlled, invalid))))
        f.collection(); f.choose("Categories"); f.choose("Other"); f.choose("Odd\\nname")
        assertTrue(f.top.message!!.contains("special/\\u0001original.scl"))
        f.popups.closeAll()
        f.collection(); f.choose("Search")
        f.top.text!!.value = "specific author"
        f.press(Keys.ENTER)
        assertEquals(1, f.top.items.count { it.label?.startsWith("Odd\\nname") == true })
        f.choose("Odd\\nname"); f.choose("Apply")
        assertTrue(f.session.song.tuning!!.provenance.contains(controlled.id))
        f.collection(); f.choose("Search")
        f.top.text!!.value = "no such scale"
        f.press(Keys.ENTER)
        assertEquals(1, f.top.items.size) // only Back: no unrelated entry or Apply action
    }

    @Test fun applyPreservesCurrentRootToleranceNotesAndSourceAcrossProjectAndUndo() {
        val previous = Scl.parse("Old\n1\n2/1\n", "").copy(root = 40, tolerance = 10, provenance = "Earlier source")
        val f = Fixture(listOf(pack(listOf(valid))), Songs.demo().copy(tuning = previous))
        val before = f.session.song
        f.collection(); f.choose("All"); f.choose(valid.name)
        assertTrue(f.top.message!!.contains("3/1 period"))
        assertTrue(f.top.message!!.contains(Scl.parse(valid.scl, valid.name).period.toString()))
        // A live edit while details are open must not get overwritten by a stale chooser snapshot.
        val current = previous.copy(root = 43, tolerance = 20)
        f.session.setTuning(current)
        f.choose("Apply")
        val selected = f.session.song.tuning!!
        assertEquals(current.root, selected.root)
        assertEquals(current.tolerance, selected.tolerance)
        assertEquals(Scl.parse(valid.scl, valid.name).cents, selected.cents)
        assertEquals(before.patterns, f.session.song.patterns)
        assertTrue(selected.provenance.contains(valid.id))
        assertTrue(selected.provenance.contains(valid.attribution))
        assertTrue(selected.provenance.contains(f.host.packs.single().pack.attribution))
        assertEquals(f.session.song, ProjectCodec.decode(ProjectCodec.encode(f.session.song)))
        f.session.undo(); assertEquals(current, f.session.song.tuning)
        f.session.redo(); assertEquals(selected, f.session.song.tuning)
        f.menus.tuningMenu(); f.choose("Tuning source")
        assertTrue(f.top.message!!.contains(valid.id))
    }

    @Test fun removalRequiresConfirmationNeverRemovesBundledPacksAndKeepsSongTuning() {
        val selected = Scl.parse(valid.scl, valid.name).copy(provenance = "Kept credit")
        val f = Fixture(listOf(pack(listOf(valid))), Songs.blank().copy(tuning = selected))
        f.collection(); f.choose("Remove collection")
        assertEquals(emptyList<String>(), f.host.removed)
        f.press(Keys.BUTTON_B)
        assertEquals(emptyList<String>(), f.host.removed)
        f.choose("Remove collection"); f.choose("Remove")
        assertEquals(listOf("collection"), f.host.removed)
        assertEquals(selected, f.session.song.tuning)
        assertFalse(f.top.items.any { it.label?.startsWith("Test collection") == true })
        val bundled = Fixture(listOf(pack(listOf(valid), bundled = true)))
        bundled.collection()
        assertFalse(bundled.top.items.any { it.tone == PopupItem.Tone.DANGER || it.label?.startsWith("Remove") == true })
    }

    @Test fun importingReloadsTheCatalogueAndDoesNotChangeTheSong() {
        val f = Fixture(emptyList())
        f.host.incoming = pack(listOf(valid))
        val before = f.session.song
        f.menus.tuningLibrary(); f.choose("Import pack")
        assertEquals(1, f.host.imports)
        assertEquals(2, f.host.loads)
        f.choose("Test collection"); f.choose("All"); f.choose(valid.name)
        assertEquals(before, f.session.song)
    }
}
