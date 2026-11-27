package org.thortracker.system.studio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.*
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.Song

class StudioModuleUiTest {
    /** A user can leave a popup open while another pane edits the same pattern. */
    @Test fun writeBindsAnInstanceAndAnEmptyColumnAtomicallyAndNeverOverwrites() {
        val song = blank().addStudioInsert(0,StudioKind.DELAY)
        val session = EditorSession(song)
        val fx = ViewState(kind=ViewKind.PATTERN,field=4,row=2)
        val menu = Choices()
        val pedal = song.studio.tracks[0].inserts.single()
        val param = pedal.kind.parameters.first { it.name=="Feedback" }
        val target = StudioBinding(pedal.id,pedal.kind,param.id)
        fun write() = StudioAssignments.writeParameter(session,menu,fx,target,param,pedal.value(param.id))
        write(); menu.pick("Enter another"); menu.number!!("FF")
        val changed=session.song
        assertEquals(target, changed.studio.tracks[0].bindings[14])
        assertEquals(Cell.BLANK.withEffect(1,14,255), changed.cellAt(0,0,2))
        write()
        assertTrue(menu.problem.orEmpty().contains("already contains"))
        assertSame(changed,session.song)
        ViewController(session,fx).press(Action.UNDO)
        assertSame(song,session.song)
        fx.field=4;write();val stale=menu.first("Write E")
        session.editCell(0,0,2) { it.withEffect(1,12,1) };stale()
        assertTrue(session.song.studio.tracks[0].bindings.isEmpty())
        assertEquals(12,session.song.cellAt(0,0,2).effectCode(1))
    }

    @Test fun movingAndRemovingAPedalKeepsOtherBindingsAndUndoRestoresCommands() {
        val doc=blank().addStudioInsert(0,StudioKind.DELAY).addStudioInsert(0,StudioKind.DRIVE)
        val s=doc.studio;val t=s.tracks[0];val delay=t.inserts.first();val drive=t.inserts.last()
        val bound=t.copy(bindings=mapOf(14 to StudioBinding(delay.id,delay.kind,2),15 to StudioBinding(drive.id,drive.kind,1)))
        val cell=Cell(note=48,instrument=1).withEffect(0,14,0x83).withEffect(1,15,0x42)
        val session=EditorSession(doc.copy(sound=s.copy(tracks=listOf(bound))).withPattern(0,doc.patterns[0].withCell(0,0,cell)))
        val st=ViewState(kind=ViewKind.STUDIO_STRIP,stripPedal=delay.id,pedalField=delay.kind.parameters.size+2)
        val v=ViewController(session,st);val m=Choices();session.onMenuRequest={it(m)}
        fun tap(a:Action){ v.press(a);v.release(a) }
        tap(Action.EDIT);m.pick("Move later")
        val moved=session.song
        assertEquals(listOf(drive.id,delay.id),moved.studio.tracks[0].inserts.map { it.id })
        assertEquals(delay.id,st.stripPedal)
        assertEquals(cell,moved.cellAt(0,0,0));assertEquals(bound.bindings,moved.studio.tracks[0].bindings)
        tap(Action.EDIT);m.pick("Remove pedal")
        assertSame(moved,session.song) // Confirmation is not yet an edit.
        m.pick("Remove and clear")
        assertEquals(cell.withEffect(0,0,0),session.song.cellAt(0,0,0))
        assertEquals(setOf(15),session.song.studio.tracks[0].bindings.keys)
        tap(Action.UNDO);assertSame(moved,session.song)
    }

    @Test fun samplerControllerMovesStableMarkerAndRefusesReferencedRemoval() {
        val data=StudioPcm.of(1,48000,FloatArray(48){ it/48f })
        val bank=StudioAssets.EMPTY.add(data)
        val sampler=SamplerPatch(assetId=bank.id,end=48,slices=listOf(StudioSlice(9,0,12),StudioSlice(3,12,48)))
        val doc=blank();val initial=doc.copy(sound=doc.studio.copy(assets=bank.assets).withPatch(1,sampler))
            .withPattern(0,doc.patterns[0].withCell(0,3,Cell(note=48,instrument=1,effect=13,param=3)))
        val session=EditorSession(initial); val st=ViewState(kind=ViewKind.INSTRUMENT,samplerPage=1,samplerSlice=3)
        val v=ViewController(session,st); val menu=Choices(); session.onMenuRequest={it(menu)}
        val ids=StudioModuleControls.fields(sampler,st)
        st.instField=2+ids.indexOf(StudioModuleControls.MARK_IN)
        var heard=-1;session.onAudition={_,_,_,selector->heard=selector}
        v.press(Action.AUDITION);assertEquals(4,heard);v.release(Action.AUDITION)
        v.press(Action.EDIT);v.press(Action.RIGHT);v.press(Action.RIGHT);v.release(Action.EDIT)
        val changed=session.song.studio.patch(1) as SamplerPatch
        assertEquals(listOf(StudioSlice(9,0,12),StudioSlice(3,14,48)),changed.slices)
        assertSame(data,session.song.studio.assets[bank.id])
        st.instField=2+ids.indexOf(StudioModuleControls.CHOP);v.press(Action.EDIT);v.release(Action.EDIT)
        menu.pick("Remove slice 03");menu.pick("Remove marker pair")
        assertTrue(menu.problem.orEmpty().contains("D03"));assertSame(changed,session.song.studio.patch(1))
        v.press(Action.UNDO);v.release(Action.UNDO);assertSame(initial,session.song)
    }

    @Test fun cutLastSamplerAndPasteRestoresTheImmutableRecording() {
        val pcm=StudioPcm.of(2,44100,floatArrayOf(-.4f,.25f,.1f,.9f));val a=StudioAssets.EMPTY.add(pcm)
        val sample=SamplerPatch(name="Hat",assetId=a.id,end=2)
        val song=blank();val session=EditorSession(song.copy(sound=song.studio.copy(assets=a.assets).withPatch(1,sample)))
        val st=ViewState(kind=ViewKind.INSTRUMENT);val v=ViewController(session,st)
        v.press(Action.CUT);v.release(Action.CUT);assertTrue(session.song.studio.assets.ids.isEmpty())
        v.press(Action.DOWN);v.release(Action.DOWN);v.press(Action.PASTE);v.release(Action.PASTE)
        val pasted=session.song.studio.patch(2) as SamplerPatch
        assertEquals(sample.name,pasted.name);assertSame(pcm,session.song.studio.assets[pasted.assetId!!]);assertTrue(session.song.studio.patch(1) is MonoPatch)
    }

    private class Choices : MenuContext {
        var rows=emptyList<Pair<String,()->Unit>>()
        var number:((String)->Unit)?=null
        var problem:String?=null
        fun first(prefix:String)=rows.first { it.first.startsWith(prefix) }.second
        fun pick(prefix:String)=first(prefix)()
        override fun menu(title:String,entries:List<Pair<String,()->Unit>>,selected:Int){rows=entries}
        override fun text(title:String,initial:String,maxLength:Int,accepted:(String)->Unit){number=accepted}
        override fun pickFile(title:String,extensions:List<String>,maxBytes:Int,picked:(String,String,ByteArray)->Unit)=error("Unexpected picker")
        override fun status(message:String) {}
        override fun error(message:String){problem=message}
        override fun installRhythmRom(bytes:ByteArray):String?=null
    }
    private fun blank()=Song(patterns=listOf(Pattern.blank(1)),orders=listOf(0),sound=StudioSound())
}
