package org.thortracker.ui

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.audio.Transport
import org.thortracker.audio.WavOptions
import org.thortracker.editor.*
import org.thortracker.input.Keymap
import org.thortracker.input.Keys
import org.thortracker.model.*
import org.thortracker.system.ExportFormat
import org.thortracker.system.midi.MidiTrack
import org.thortracker.system.studio.*

class StudioBounceMenuTest {
    // Only the offline-worker boundary is substituted. The actual popup stack, document commands and asset bank run.
    private class Host : MenuHost {
        override var modalDisplay = 1
        override val displayCount = 2
        override val keymap = Keymap.DEFAULT
        lateinit var plan: StudioBouncePlan
        lateinit var progress: (Long) -> Unit
        lateinit var complete: (Result<StudioPcm?>) -> Unit
        var busy = false
        var starts = 0
        var cancels = 0
        val errors = ArrayList<String>()
        val statuses = ArrayList<String>()
        override fun startStudioBounce(plan: StudioBouncePlan, progress: (Long) -> Unit, complete: (Result<StudioPcm?>) -> Unit): (() -> Unit)? {
            if (busy) return null
            this.plan = plan; this.progress = progress; this.complete = complete; starts++
            return { cancels++ }
        }
        override fun refresh() = Unit
        override fun error(message: String) { errors += message }
        override fun status(message: String) { statuses += message }
        private fun unexpected(): Nothing = throw AssertionError("Bounce invoked an unrelated host operation")
        override fun replace(song: Song) = unexpected()
        override fun export(song: Song, format: ExportFormat) = unexpected()
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
        override fun showWavExport() = false
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

    private class Fixture {
        val host = Host()
        val origin = ViewState(kind=ViewKind.PATTERN, order=1, channel=1)
        val workspace = Workspace(listOf(ViewState(kind=ViewKind.PATTERN),origin), focus=0)
        private val pattern = Pattern.blank(2).let { p -> p.copy(length=1, tracks=p.tracks.map { lane ->
            lane.mapIndexed { row, cell -> if(row==0) Cell(49,1) else cell }
        }) }
        val original = Song(ticksPerRow=2, patterns=listOf(pattern), orders=listOf(0,0),
            sound=StudioSound(tracks=listOf(StudioTrack(MidiTrack(1,0)), StudioTrack(MidiTrack(3,1))),nextId=4))
        val session = EditorSession(original)
        private val layer = object : PopupView {
            override val fieldFocused=false
            override fun show(spec: PopupSpec, hasParent: Boolean, popups: Popups) = Unit
            override fun hide() = Unit
            override fun select(entry: Int) = Unit
            override fun page(direction: Int) = Unit
            override fun focusField() = Unit
        }
        val popups = Popups({layer},{listOf(0,1)})
        val menus = AppMenus(session,workspace,Transport(session),popups,host)
        val top get() = requireNotNull(popups.top)
        fun select(label:String) {
            val index=top.items.indexOfFirst { it.label == label }
            check(index>=0) { "Missing $label in ${top.title}: ${top.items.map { it.label }}" }
            popups.choose(index)
        }
        fun key(code:Int) { popups.handleKey(code,true,0); popups.handleKey(code,false,0) }
        fun start() {
            menus.appMenu(); select("Bounce this track to sampler…")
            key(Keys.BUTTON_A) // First eligible Init, never implicit 01.
            key(Keys.BUTTON_A) // Render.
        }
        fun reopen() { menus.appMenu(); select("Show track bounce…") }
        fun finish(): StudioPcm {
            val pcm=StudioPcm.of(2,48000,FloatArray(host.plan.frames.toInt()*2){ if(it%2==0) 0.25f else -0.125f })
            host.complete(Result.success(pcm)); return pcm
        }
    }

    @Test fun hiddenCompletionKeepsOtherPaneAndPopupIntactUntilExplicitInstall() {
        val f=Fixture(); f.start()
        assertEquals(1,f.host.plan.order); assertEquals(1,f.host.plan.channel); assertEquals(2,f.host.plan.slot)
        assertEquals(1,f.popups.display)
        f.key(Keys.BACK); assertFalse(f.popups.isOpen)
        f.host.modalDisplay=0; f.origin.switchKind(ViewKind.SONG); f.origin.channel=0
        f.menus.appMenu(); val unrelated=f.top
        f.host.progress(1000); val pcm=f.finish()
        assertSame(unrelated,f.popups.top); assertEquals(0,f.popups.display); assertEquals(0,f.workspace.focus)
        assertSame(f.original,f.session.song); assertFalse(f.session.canUndo)
        f.select("Show track bounce…"); assertEquals(1,f.popups.display)
        f.select("Install sampler 02")
        val after=f.session.song; val patch=after.studio.patch(2) as SamplerPatch
        assertEquals(pcm,after.studio.assets[patch.assetId!!]); assertEquals(f.original.patterns,after.patterns)
        assertEquals(f.original.studio.tracks,after.studio.tracks); assertEquals(0,f.workspace.focus)
        f.session.undo(); assertSame(f.original,f.session.song); assertFalse(f.session.canUndo)
        f.session.redo(); assertSame(after,f.session.song); assertEquals(1,f.host.starts)
    }

    @Test fun chooserExcludesNamedSoundsAndHiddenRowReferencesAndRefusesUsb() {
        val f=Fixture()
        val song=f.original.copy(sound=f.original.studio.withPatch(2,MonoPatch(name="Keep me")),
            patterns=f.original.patterns.map { p -> p.copy(tracks=p.tracks.map { lane ->
                lane.mapIndexed { i,cell -> if(i==63) Cell(instrument=3) else cell }
            }) })
        f.session.replaceSong(song)
        f.menus.appMenu();f.select("Bounce this track to sampler…")
        assertEquals("04 · Init",f.top.items.first().label)
        f.popups.closeAll()
        f.session.replaceSong(song.copy(sound=song.studio.copy(tracks=song.studio.tracks.map { it.copy(target=StudioTarget.USB_MIDI) })))
        f.menus.appMenu();f.select("Bounce this track to sampler…")
        assertTrue(f.host.errors.single().contains("USB"));assertEquals(0,f.host.starts);assertFalse(f.session.canUndo)
    }

    @Test fun staleResultsCannotInstallAfterReferencingSlotOrUndoBackToSameSnapshot() {
        for(undo in listOf(false,true)) {
            val f=Fixture(); f.start()
            f.session.apply("Reference spare") { song -> song.copy(patterns=song.patterns.map { p ->
                p.copy(tracks=p.tracks.map { lane -> lane.mapIndexed { i,cell -> if(i==42) Cell(instrument=2) else cell } })
            }) }
            if(undo) { f.session.undo(); assertSame(f.original,f.session.song) }
            val before=f.session.song; val version=f.session.documentVersion
            f.finish(); f.select("Install sampler 02")
            assertSame(before,f.session.song); assertEquals(version,f.session.documentVersion)
            assertTrue(f.host.errors.single().contains("discarded"))
            f.menus.appMenu(); assertFalse(f.top.items.any { it.label=="Show track bounce…" })
        }
    }

    @Test fun activeGestureKeepsResultForRetryWithoutJoiningItsUndoGroup() {
        val f=Fixture(); f.start(); f.finish()
        val token=f.session.beginGesture("Other display's drag")
        f.select("Install sampler 02")
        assertTrue(f.session.isGestureOpen(token)); assertSame(f.original,f.session.song)
        f.session.endGesture(token); f.reopen(); f.select("Install sampler 02")
        assertEquals("Bounce to sampler 02",f.session.undoLabel)
        f.session.undo(); assertSame(f.original,f.session.song); assertFalse(f.session.canUndo)
    }

    @Test fun busyCancelDiscardAndLateWorkerDeliveryNeverInstallOrStartAnotherRender() {
        val f=Fixture(); f.host.busy=true; f.start()
        assertTrue(f.host.errors.single().contains("busy")); assertFalse(f.popups.isOpen)
        f.host.busy=false; f.start(); val first=f.host.complete
        f.select("Hide (keep rendering)"); f.reopen(); assertEquals(1,f.host.starts)
        f.select("Cancel bounce"); assertEquals(1,f.host.cancels)
        f.reopen(); assertEquals(1,f.host.starts); first(Result.success(null))
        f.start(); f.finish(); first(Result.failure(IllegalStateException("late callback")))
        assertEquals("Bounce ready",f.top.title); f.select("Discard")
        assertEquals(2,f.host.starts); assertEquals(1,f.host.errors.size)
        assertSame(f.original,f.session.song); assertFalse(f.session.canUndo)
    }
}
