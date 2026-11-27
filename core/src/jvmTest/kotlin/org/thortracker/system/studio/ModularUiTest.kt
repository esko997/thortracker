package org.thortracker.system.studio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.*
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.ui.*

class ModularUiTest {
    private class Popup : MenuContext {
        var rows = emptyList<Pair<String,() -> Unit>>()
        lateinit var accept: (String) -> Unit
        fun pick(label: String) = rows.first { it.first.startsWith(label) }.second()
        fun digits(n: String) {
            pick("Choose digits")
            for (ch in n) pick(if (ch=='-') "Minus" else ch.toString())
            pick("Apply $n")
        }
        fun typeNumber(n: String) { pick("Type a number"); accept(n) }
        override fun menu(title: String,entries: List<Pair<String,() -> Unit>>,selected: Int) { rows=entries }
        override fun text(title: String,initial: String,maxLength: Int,accepted: (String) -> Unit) { accept=accepted }
        override fun pickFile(title: String,extensions: List<String>,maxBytes: Int,picked: (String,String,ByteArray) -> Unit) = error("Unexpected file picker")
        override fun status(message: String) = Unit
        override fun error(message: String) = Unit
        override fun installRhythmRom(bytes: ByteArray): String? = null
    }
    private class Canvas : Surface {
        val labels = mutableMapOf<String,Pair<Float,Float>>()
        override fun text(s: String,x: Float,top: Float,h: Float,color: Int,face: Face,bold: Boolean,size: Float,align: Align) { labels[s]=x to top+h/2 }
        override fun textWidth(s: String,face: Face,bold: Boolean,size: Float)=s.length*size/2
        override fun fillRect(l: Float,t: Float,r: Float,b: Float,color: Int) = Unit
        override fun fillRound(l: Float,t: Float,r: Float,b: Float,radius: Float,color: Int) = Unit
        override fun strokeRect(l: Float,t: Float,r: Float,b: Float,color: Int,width: Float,radius: Float) = Unit
        override fun line(x0: Float,y0: Float,x1: Float,y1: Float,color: Int,width: Float) = Unit
        override fun fillCircle(cx: Float,cy: Float,radius: Float,color: Int) = Unit
        override fun strokeCircle(cx: Float,cy: Float,radius: Float,color: Int,width: Float) = Unit
        override fun polyline(xs: FloatArray,ys: FloatArray,count: Int,color: Int,width: Float) = Unit
    }
    private class Display(val session: EditorSession, val width: Int = 640, val height: Int = 480) {
        val st=ViewState(kind=ViewKind.INSTRUMENT,field=2)
        val canvas=Canvas()
        val pane=PaneView(session,st,1f,canvas,{ 0L },{})
        fun draw() { canvas.labels.clear(); pane.draw(width,height) }
        fun point(label: String): Pair<Float,Float> = canvas.labels[label] ?: canvas.labels.entries.first { it.key.startsWith("$label /") }.value
        fun tap(label: String) { draw(); val (x,y)=point(label); assertTrue(label,pane.touchDown(x,y)); pane.touchUp(x,y) }
        fun key(action: Action) { pane.press(action); pane.release(action) }
        fun field(key: Int) {
            // Take the same horizontal navigation path as a controller. No field pokes or direct editor calls.
            repeat(16) {
                if (ModularControls.fields(st).getOrNull(st.instField-2)==key) return
                this.key(Action.RIGHT)
            }
            error("Field $key not reachable")
        }
        fun open(key: Int) { field(key); this.key(Action.EDIT) }
        fun adjust(direction: Action) { pane.press(Action.EDIT); key(direction); pane.release(Action.EDIT) }
    }
    private fun session(): EditorSession {
        val graph=ModularGraph(nodes=listOf(ModularNode(1,ModularKind.OSC),ModularNode(2,ModularKind.OUT),ModularNode(3,ModularKind.NOTE)),
            macros=List(8){ModularMacro(if(it==0) "Drift" else "Macro ${it+1}")})
        return EditorSession(Song(patterns=listOf(Pattern.blank(1)),orders=listOf(0),sound=StudioSound().withPatch(1,ModularPatch("Test patch",graph))))
    }
    private fun EditorSession.patch()=song.studio.patch(1) as ModularPatch

    @Test fun newModularLoadsAnInstrumentWithEightPlayableMappingsAndKeepsTheOldSlotInUndo() {
        val s=session(); val original=s.song; val ui=Display(s); val popup=Popup(); s.onMenuRequest={ it(popup) }
        ui.open(ModularControls.LIBRARY); popup.pick("Modular"); popup.pick("New Modular"); popup.pick("Use ")
        val p=s.patch()
        assertEquals("Patchwork Bass",p.name)
        assertEquals(8,p.graph.macros.count { macro -> macro.targets.any { it.minimum != it.maximum } })
        ui.key(Action.UNDO); assertSame(original,s.song)
    }

    @Test fun dragOrControllerCanWireAcrossThePatchAndEditBothTransforms() {
        for ((w,h) in listOf(320 to 240,360 to 640,1280 to 720)) {
            val s=session(); val ui=Display(s,w,h); val popup=Popup(); s.onMenuRequest={ it(popup) }
            ui.tap("Build"); ui.open(ModularControls.DESTINATION); popup.pick("02 Output")
            ui.draw(); val (x,y)=ui.point("Audio"); val (dx,dy)=ui.point("Left")
            assertTrue(ui.pane.touchDown(x,y)); ui.pane.touchMove(dx,dy); ui.pane.touchUp(dx,dy)
            val link=ModularConnection(1,1,0,2,0); assertEquals(listOf(link),s.patch().graph.connections)
            fun editCable() { ui.open(ModularControls.CABLES); popup.pick("01 Oscillator:Audio → 02 Output:Left") }
            editCable(); popup.pick("Amount"); popup.digits("-450")
            editCable(); popup.pick("Offset"); popup.digits("125")
            assertEquals(link.copy(scale=-450,offset=125),s.patch().graph.connections.single())
            editCable(); popup.pick("Disconnect"); assertTrue(s.patch().graph.connections.isEmpty())
            ui.open(ModularControls.CABLES); popup.pick("Add cable"); popup.pick("03 Note"); popup.pick("Pitch")
            assertEquals(listOf("01 Oscillator"),popup.rows.map { it.first }); popup.pick("01 Oscillator"); popup.pick("Pitch")
            assertEquals(ModularConnection(2,3,0,1,0),s.patch().graph.connections.single())
            ui.key(Action.UNDO); assertTrue(s.patch().graph.connections.isEmpty())
            ui.key(Action.UNDO); assertEquals(link.copy(scale=-450,offset=125),s.patch().graph.connections.single())
        }
    }
    @Test fun incompleteCableNeverSurvivesUndoResizeOrAnEditFromTheOtherFace() {
        for (event in listOf("undo","resize","sibling","slot")) {
            val s=session(); val back=Display(s); val front=Display(s); val popup=Popup(); s.onMenuRequest={ it(popup) }
            back.tap("Build"); back.open(ModularControls.DESTINATION); popup.pick("02 Output")
            back.draw(); val (x,y)=back.point("Audio"); val (dx,dy)=back.point("Left")
            assertTrue(back.pane.touchDown(x,y)); back.pane.touchMove(dx,dy)
            when(event) {
                "undo" -> back.key(Action.UNDO) // Also when the new project has no history entries yet.
                "resize" -> back.pane.draw(360,640)
                "sibling" -> { front.field(0); front.adjust(Action.RIGHT); assertEquals(1,s.patch().value(0)) }
                "slot" -> back.key(Action.DOWN)
            }
            val accepted=s.song; back.pane.touchUp(dx,dy)
            assertSame(event,accepted,s.song); assertTrue(s.patch().graph.connections.isEmpty()); assertFalse(s.inGesture)
            assertFalse(StudioSoundView.panel(front.st).advanced); assertTrue(StudioSoundView.panel(back.st).advanced)
        }
    }
    @Test fun namedRibbonGroupsDragAndUndoCannotResumeIt() {
        val s=session(); val ui=Display(s,360,640); val original=s.song
        ui.draw(); val (x,y)=ui.point("Drift"); assertTrue(ui.pane.touchDown(x,y))
        ui.pane.touchMove(x+50,y); val first=s.patch().value(0); assertTrue(first>0)
        ui.pane.touchMove(x+130,y); assertTrue(s.patch().value(0)>first)
        assertSame((original.studio.patch(1) as ModularPatch).graph,s.patch().graph)
        ui.key(Action.UNDO); ui.pane.touchMove(x+155,y); ui.pane.touchUp(x+155,y)
        assertSame(original,s.song); assertFalse(s.canUndo); assertFalse(s.inGesture)
    }
    @Test fun bindTwoControlsWithIndependentEndpointsAndRemoveOneModuleAtomically() {
        val s=session(); val ui=Display(s); val popup=Popup(); s.onMenuRequest={ it(popup) }
        fun macro() { ui.open(0); popup.pick("Edit bindings") }
        fun target(n: String,p: String,a: String,b: String) { macro(); popup.pick("Add target"); popup.pick(n); popup.pick(p); popup.digits(a); popup.digits(b) }
        macro(); popup.pick("Rename"); popup.pick("Type a name"); popup.accept("Squeeze")
        target("01 Oscillator","Tune","-12","12"); target("02 Output","Gain","1700","0")
        val targets=listOf(ModularTarget(1,1,-12,12),ModularTarget(2,0,1700,0))
        assertEquals(ModularMacro("Squeeze",targets),s.patch().graph.macros.first()); val before=s.song
        macro(); popup.pick("Add target"); popup.pick("01 Oscillator"); popup.pick("Width"); popup.digits("90"); popup.pick("Type a number")
        val late=popup.accept; ui.adjust(Action.RIGHT); late("750"); assertEquals(targets,s.patch().graph.macros.first().targets)
        ui.key(Action.UNDO); assertSame(before,s.song)
        ui.tap("Build"); ui.open(ModularControls.OVERVIEW); popup.pick("01 Oscillator"); popup.pick("Remove module"); popup.pick("Remove with")
        assertEquals(listOf(targets[1]),s.patch().graph.macros.first().targets); assertFalse(s.patch().graph.nodes.any { it.id==1 })
        ui.key(Action.UNDO); assertSame(before,s.song)
    }
    @Test fun addAndEditANewModuleAndWriteTheNamedMacroThroughExistingPatternLetter() {
        val s=session(); val ui=Display(s); val popup=Popup(); s.onMenuRequest={ it(popup) }
        // Both face changes here use A on the face selector (no touch).
        ui.key(Action.LEFT); ui.key(Action.LEFT); ui.key(Action.EDIT)
        assertTrue(StudioSoundView.panel(ui.st).advanced)
        ui.open(ModularControls.OVERVIEW); popup.pick("Add module"); popup.pick("Filter")
        ui.open(ModularControls.PARAMETER); popup.pick("Cutoff"); popup.accept("5300")
        ui.adjust(Action.RIGHT); assertEquals(5301,s.patch().graph.nodes.last().value(0))
        ui.field(ModularControls.LIBRARY); ui.key(Action.RIGHT); ui.key(Action.EDIT)
        assertFalse(StudioSoundView.panel(ui.st).advanced)
        ui.open(0); popup.pick("Write automation"); popup.pick("Enter another"); val stale=popup.accept
        ui.key(Action.DOWN); stale("7F"); assertTrue(s.song.studio.tracks[0].bindings.isEmpty()); ui.key(Action.UP)
        ui.open(0); popup.pick("Write automation"); popup.pick("Enter another"); popup.accept("7F")
        assertEquals(StudioBinding(0,StudioKind.MODULAR,0),s.song.studio.tracks[0].bindings[14])
        assertEquals(0x7f,s.song.cellAt(0,0,0).effectParam(0)); assertEquals(0,s.patch().value(0))
    }
    @Test fun boundParameterOpensItsMacroInsteadOfEditingAnInaudibleBaseValue() {
        val s=session(); val ui=Display(s); val popup=Popup(); s.onMenuRequest={ it(popup) }
        ui.open(0); popup.pick("Edit bindings"); popup.pick("Add target")
        popup.pick("01 Oscillator"); popup.pick("Tune"); popup.typeNumber("-12"); popup.typeNumber("12")
        val graph=s.patch().graph
        ui.tap("Build"); ui.open(ModularControls.PARAMETER); popup.pick("Tune ← Drift")
        popup.pick("Set value"); popup.typeNumber("95")
        assertEquals(95,s.patch().value(0)); assertEquals(graph,s.patch().graph)
        val accepted=s.song
        ui.adjust(Action.RIGHT)
        assertSame("A bound base value must not silently change without affecting sound",accepted,s.song)
        ui.key(Action.UNDO); assertEquals(0,s.patch().value(0)); assertEquals(graph,s.patch().graph)
    }
    @Test fun standaloneControllerCanNameAMacroAndSetTheFullSignedSeedWithoutTyping() {
        val s=session(); val ui=Display(s); val popup=Popup(); s.onMenuRequest={ it(popup) }
        val before=s.song
        ui.open(0); popup.pick("Edit bindings"); popup.pick("Rename"); popup.pick("Spell")
        for ((group,ch) in listOf("ABCDEFG" to "F","opqrst" to "o","abcdefg" to "g")) {
            popup.pick(group); popup.pick(ch)
        }
        assertSame(before,s.song); popup.pick("Use Fog")
        assertEquals("Fog",s.patch().graph.macros.first().name)
        ui.tap("Build"); ui.open(ModularControls.SEED); popup.digits("-2147483648")
        assertEquals(Int.MIN_VALUE,s.patch().graph.seed); ui.key(Action.UNDO)
        assertEquals("Fog",s.patch().graph.macros.first().name); assertEquals(1,s.patch().graph.seed)
    }
}
