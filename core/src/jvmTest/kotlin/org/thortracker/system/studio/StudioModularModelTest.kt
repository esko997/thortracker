package org.thortracker.system.studio

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.EditorSession
import org.thortracker.editor.withCell
import org.thortracker.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StudioModularModelTest {
    @Test fun snapshotsAndPortableUndoKeepStableNodeCableAndMacroIdentity() {
        val params=mutableMapOf(0 to 1)
        val nodes=mutableListOf(ModularNode(11,ModularKind.OSC,params),ModularNode(12,ModularKind.OUT),ModularNode(77,ModularKind.DELAY))
        val target=ModularTarget(11,2,900,100)
        val dst=mutableListOf(target)
        val macros=MutableList(8){ModularMacro("M$it")};macros[7]=ModularMacro("Narrow",dst)
        val edges=mutableListOf(ModularConnection(5,11,0,12,0),ModularConnection(88,11,0,77,0))
        val graph=ModularGraph(nodes,edges,macros,-1).withoutNode(77)
        val values=mutableMapOf(7 to 69)
        val patch=ModularPatch("Custom",graph,values)
        params[0]=2;edges.clear();dst.clear();nodes.clear();macros.clear();values.clear()
        val blank=StudioSound().system.blank(); val song=blank.copy(sound=blank.studio.withPatch(3,patch))
        val saved=ProjectCodec.decodeBytes(ProjectCodec.encodeBytes(song))
        assertEquals(song,saved)
        val p=saved.studio.patch(3) as ModularPatch
        assertEquals(69,p.value(7));assertEquals(1,p.graph.nodes.first().value(0));assertEquals(listOf(target),p.graph.macros[7].targets)
        assertEquals(78,p.graph.nextNodeId());assertEquals(89,p.graph.nextConnectionId())
        val edit=EditorSession(saved);edit.apply("Remove oscillator") { s -> s.copy(sound=s.studio.withPatch(3,p.copy(graph=p.graph.withoutNode(11)))) }
        val pruned=(edit.song.studio.patch(3) as ModularPatch).graph
        assertEquals(listOf(12),pruned.nodes.map { it.id });assertTrue(pruned.connections.isEmpty());assertTrue(pruned.macros[7].targets.isEmpty())
        edit.undo();assertEquals(saved,edit.song)
    }

    @Test fun graphChecksDistinguishInstantaneousAndSampleDelayedDependencies() {
        val g=ModularGraph(listOf(ModularNode(1,ModularKind.OSC),ModularNode(2,ModularKind.DELAY),
            ModularNode(3,ModularKind.FUNCTION),ModularNode(4,ModularKind.OUT)))
        fun refuses(block:()->Unit) { assertThrows(IllegalArgumentException::class.java){block()} }
        val fm=ModularConnection(1,1,0,1,1)
        refuses { g.copy(connections=listOf(fm)) }
        refuses { g.copy(connections=listOf(fm.copy(destination=99))) }
        refuses { g.copy(connections=listOf(fm.copy(input=0))) } // audio -> pitch not allowed
        refuses { g.copy(nodes=g.nodes+ModularNode(5,ModularKind.OUT)) }
        val edge=ModularConnection(2,1,0,2,0);val feedback=ModularConnection(3,2,0,1,1,700,-120)
        val event=ModularConnection(4,3,1,3,0)
        val allowed=g.copy(connections=listOf(edge,feedback,event))
        refuses { allowed.copy(connections=allowed.connections+edge.copy(id=9)) }
        refuses { g.copy(connections=listOf(event.copy(offset=1))) }
        for(t in listOf(ModularTarget(1,0,0,1),ModularTarget(99,1,0,48),ModularTarget(1,1,-49,0))){
            refuses { g.copy(macros=List(8){if(it==0)ModularMacro("Invalid",listOf(t)) else g.macros[it]}) }
        }
        val mapped=g.macros.toMutableList();mapped[1]=ModularMacro("Tune",listOf(ModularTarget(1,1,12,-12)));mapped[7]=mapped[1]
        refuses { g.copy(macros=mapped) }
        val json=Json.encodeToJsonElement(ModularGraph.serializer(),g).jsonObject
        val excess=JsonArray(List(65){json.getValue("nodes").jsonArray.first()})
        refuses { Json.decodeFromJsonElement(ModularGraph.serializer(),JsonObject(json+("nodes" to excess))) }
    }

    @Test fun macroSevenLowersOnOnlyItsLaneAndBounceCarriesThatExactGraph() {
        val blank=StudioSound().system.blank().addStudioTrack()
        val graph=ModularGraph.initial(); val macros=graph.macros.toMutableList()
        macros[7]=ModularMacro("Inverse",listOf(ModularTarget(4,0,2000,0)))
        val p=ModularPatch("Print",graph.copy(macros=macros,seed=0x72c8),mapOf(7 to 13))
        val lanes=blank.studio.tracks.mapIndexed { i,t -> if(i==0)t.copy(bindings=mapOf(35 to StudioBinding(0,StudioKind.MODULAR,7))) else t }
        val pattern=Pattern.blank(2).withCell(0,0,Cell(48,3,35,255)).withCell(1,0,Cell(48,3)).copy(length=1)
        val song=blank.copy(sound=blank.studio.withPatch(3,p).copy(tracks=lanes),patterns=listOf(pattern))
        val score=ByteArray(StudioEngine.imageSize);StudioEngine.lower(song,score)
        val start=StudioEngine.ROW_INDEX+130
        val cc=(0 until (score[12].toInt() and 255)).map { start+8*it }.filter { score[it+3].toInt()==10 }
        val at=cc.single();assertEquals(0,score[at+2].toInt());assertEquals(0xb7,score[at+5].toInt() and 255);assertEquals(127,score[at+6].toInt())
        assertEquals(13,score[StudioEngine.PATCH_BASE+2*StudioEngine.PATCH_BYTES+4+4*7].toInt())
        val live=StudioEngine.withResources(score,song.studio,false)
        val bounce=StudioBounce.plan(song,0,0,32).lower()
        assertArrayEquals(live.copyOfRange(score.size,live.size),bounce.copyOfRange(score.size,bounce.size))
        val b=ByteBuffer.wrap(bounce).order(ByteOrder.LITTLE_ENDIAN);b.position(score.size)
        assertEquals(0x314d4753,b.int);assertEquals(1,b.int);assertEquals(2,b.int);assertEquals(p.graph.wordCount,b.int)
        assertEquals(1,b.int);assertEquals(0x72c8,b.int)
        b.position(b.limit()-20);assertEquals(7,b.int);assertEquals(4,b.int);assertEquals(0,b.int)
        assertEquals(2f,b.float,0f);assertEquals(0f,b.float,0f)
    }
}
