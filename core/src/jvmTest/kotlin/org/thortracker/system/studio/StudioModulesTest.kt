package org.thortracker.system.studio

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.editor.EditorSession
import org.thortracker.editor.withCell
import org.thortracker.model.*

class StudioModulesTest {
    @Test fun v1MonoBankUpgradesWithoutChangingAControlOrRoute() {
        val base=StudioSound().system.blank().addStudioTrack()
        val patch=MonoPatch("Custom",1,99,74,23,31,67,47,-19,13)
        val s=base.studio.withPatch(32,patch)
        val song=base.copy(sound=s.copy(tracks=s.tracks.mapIndexed { i,t -> if(i==1) t.copy(target=StudioTarget.USB_MIDI) else t }))
        val root=Json.parseToJsonElement(ProjectCodec.encode(song)).jsonObject
        val doc=root.getValue("song").jsonObject; val sound=doc.getValue("sound").jsonObject
        val oldSound=JsonObject(mapOf("patches" to JsonArray(sound.getValue("patches").jsonArray.map { JsonObject(it.jsonObject-"type") }),
            "tracks" to JsonArray(sound.getValue("tracks").jsonArray.map { JsonObject(it.jsonObject.filterKeys { key -> key=="midi" || key=="target" }) }),
            "nextId" to JsonPrimitive(3),"clock" to JsonPrimitive(false)))
        val old=JsonObject(root+mapOf("backendVersion" to JsonPrimitive(1),"song" to JsonObject(doc+("sound" to oldSound))))
        val restored=ProjectCodec.decode(old.toString())
        assertEquals(song,restored); assertEquals(patch,restored.studio.patch(32))
    }

    @Test fun cloneReorderRemoveAndUndoKeepTrackLocalCommandsAndIndependentIdentities() {
        var song=StudioSound().system.blank().addStudioInsert(0,StudioKind.DELAY).addStudioInsert(0,StudioKind.DRIVE)
        val lane=song.studio.tracks.single();val delay=lane.inserts.first()
        song=song.copy(sound=song.studio.copy(tracks=listOf(lane.copy(bindings=mapOf(14 to StudioBinding(delay.id,delay.kind,2))))),
            patterns=listOf(song.patterns.single().withCell(0,1,Cell(45,1,14,211).withEffect(3,8,99))))
        val cloned=song.cloneStudioTrack(0);val copy=cloned.studio.tracks[1];val copyDelay=copy.inserts.first()
        assertNotEquals(delay.id,copyDelay.id); assertEquals(copyDelay.id,copy.bindings.getValue(14).instance)
        val reordered=cloned.copy(sound=cloned.studio.copy(tracks=listOf(cloned.studio.tracks[0],copy.copy(inserts=copy.inserts.reversed()))))
        val session=EditorSession(ProjectCodec.decode(ProjectCodec.encode(reordered)))
        assertTrue(assertThrows(IllegalArgumentException::class.java) { session.song.removeStudioInsert(1,copyDelay.id) }.message!!.contains("Clear bindings"))
        session.apply("Clear and remove") { it.removeStudioInsert(1,copyDelay.id,true) }
        val pattern=session.song.patterns.single()
        assertEquals(14,pattern.tracks[0][1].effect); assertEquals(0,pattern.tracks[1][1].effect)
        assertEquals(99,pattern.tracks[1][1].effectParam(3));assertEquals(listOf(delay.id,lane.inserts[1].id),session.song.studio.tracks[0].inserts.map { it.id })
        session.undo();assertEquals(reordered,session.song);assertTrue(session.song.system.errors(session.song).isEmpty())
        val wire=ByteArray(StudioEngine.imageSize);StudioEngine.lower(session.song,wire)
        val start=StudioEngine.ROW_INDEX+130
        val controls=(0 until (wire[12].toInt() and 255)).map { start+8*it }.filter { wire[it+3].toInt()==10 }
        assertEquals(listOf(1,2),controls.map { wire[it+4].toInt() })
        assertEquals(List(2){(95*211+127)/255},controls.map { wire[it+6].toInt() and 255 })
    }

    @Test fun inheritedInstrumentAcrossLoopMustMatchBindingAndSlice() {
        val empty=StudioSound().system.blank();val binding=StudioBinding(0,StudioKind.MONO,1)
        val state=empty.studio.withPatch(2,FmPatch()).let { it.copy(tracks=listOf(it.tracks.single().copy(bindings=mapOf(35 to binding)))) }
        val pattern=empty.patterns.single().withCell(0,0,Cell(effect=35,param=203)).withCell(0,1,Cell(40,2))
        val song=empty.copy(sound=state,patterns=listOf(pattern))
        assertTrue(song.system.errors(song).single().contains("Z requires MONO, not FM"))
        val fixed=song.copy(patterns=listOf(pattern.withCell(0,0,Cell(instrument=1,effect=35,param=203))))
        assertTrue(fixed.system.errors(fixed).isEmpty())
        val unbound=fixed.copy(patterns=listOf(pattern.withCell(0,0,Cell(effect=14,param=203))))
        assertTrue(unbound.system.errors(unbound).single().contains("assign E"))
        val missing=fixed.copy(patterns=listOf(pattern.withCell(0,0,Cell(effect=13,param=4))))
        assertTrue(missing.system.errors(missing).single().contains("missing slice"))
    }

    @Test fun patternPreviewChasesPriorPlayedInstrumentsButNotParkedOrders() {
        val blank=StudioSound().system.blank()
        val lane=blank.studio.tracks.single().copy(bindings=mapOf(14 to StudioBinding(0,StudioKind.FM,2)))
        val sound=blank.studio.withPatch(2,FmPatch()).copy(tracks=listOf(lane))
        val first=Pattern.blank(1).withCell(0,0,Cell(instrument=2)).copy(length=1)
        val parked=Pattern.blank(1).withCell(0,0,Cell(instrument=1)).copy(length=1)
        val loop=Pattern.blank(1).withCell(0,0,Cell(48,effect=14,param=125)).copy(length=1)
        val song=blank.copy(sound=sound,patterns=listOf(first,parked,loop),orders=listOf(0,1,2),parkedOrders=listOf(1))
        val heard=song.system.patternLoop(song,2)
        assertTrue(heard.system.errors(heard).isEmpty())
        val score=ByteArray(StudioEngine.imageSize)
        StudioEngine.lower(heard,score)
        assertEquals(1,score[14].toInt()) // row zero in the editor is physical order 1 after chase
        assertEquals(1,score[6].toInt()) // the loop never replays the lead-in
        assertEquals(2,score[8].toInt())
    }
}
