package org.thortracker.system.studio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.thortracker.audio.WavJob
import org.thortracker.audio.WavRenderer
import org.thortracker.audio.WavStatus
import org.thortracker.editor.EditorSession
import org.thortracker.model.Cell
import org.thortracker.model.Pattern
import org.thortracker.model.ProjectCodec
import org.thortracker.model.Song
import org.thortracker.model.Songs

class StudioBounceTest {
    private fun pattern(vararg lanes: List<Cell>, length: Int = lanes.maxOf { it.size }) = Pattern(
        tracks = lanes.map { cells -> List(Songs.ROWS) { cells.getOrElse(it) { Cell.BLANK } } }, length = length)

    private fun simple(length: Int = 1, speed: Int = 1): Song = StudioSound().system.blank().copy(
        ticksPerRow = speed, patterns = listOf(pattern(listOf(Cell(48)), length = length)))

    @Test fun projectionChasesOccurrenceAndPrintsOnlyOnceWithoutUsbOrReturns() {
        var song = simple().addStudioTrack().addStudioTrack()
        song = song.copy(sound = song.studio.copy(buses = listOf(StudioBus())))
            .addStudioInsert(1,StudioKind.DRIVE).addStudioInsert(1,StudioKind.CRUSHER).addStudioInsert(-1,StudioKind.REVERB)
        val added = StudioAssets.EMPTY.add(StudioPcm.of(2, 48000, FloatArray(32) { if(it%2==0) .5f else -.25f }))
        val other = added.assets.add(StudioPcm.of(1, 12000, FloatArray(16) { .25f }))
        val lanes = song.studio.tracks.toMutableList()
        val selected = lanes[1]
        lanes[1] = selected.copy(gain=77,pan=-61,sends=listOf(77,0),bindings=mapOf(
            14 to StudioBinding(0,StudioKind.SAMPLER,7),15 to StudioBinding(selected.inserts.first().id,StudioKind.DRIVE,0)))
        lanes[2] = lanes[2].copy(target=StudioTarget.USB_MIDI)
        val sound = song.studio.withPatch(2,SamplerPatch(assetId=added.id,slices=listOf(StudioSlice(3,2,12))))
            .withPatch(3,PluckPatch()).withPatch(4,SamplerPatch(assetId=other.id))
            .copy(assets=other.assets,tracks=lanes)
        val otherNote = listOf(Cell(80,1)); val usb = listOf(Cell(40,1));
        val set = pattern(otherNote,listOf(Cell(instrument=2,effect=13,param=3).withEffect(1,14,153).withEffect(2,15,255)),usb)
        val body = pattern(otherNote,listOf(Cell(48,effect=13,param=3),Cell(50,3)),usb)
        val parked = pattern(otherNote,listOf(Cell(48,4)),usb)
        // Invalid/unbound on the SELECTED track but only after the chosen order: irrelevant to this print.
        val future = pattern(otherNote,listOf(Cell(70,4,35,10)),usb)
        song = song.copy(sound=sound,patterns=listOf(set,body,parked,future),orders=listOf(0,2,0,1,3),parkedOrders=listOf(1))
        assertThrows(IllegalArgumentException::class.java){ StudioBounce.plan(song,3,2,32) }
        val plan=StudioBounce.plan(song,3,1,32)
        val wire = ByteBuffer.wrap(plan.lower()).order(ByteOrder.LITTLE_ENDIAN)
        fun byte(at:Int)=wire.get(at).toInt() and 255
        assertEquals(1,byte(4));assertEquals(0,byte(7));assertEquals(2,byte(14));assertEquals(2,byte(6))
        assertEquals(listOf(0,0,1),(816..818).map(::byte));assertEquals(selected.midi.id,wire.getInt(16))
        val g=StudioEngine.GRAPH_BASE
        assertEquals(listOf(77,39,0,0,2),(g..g+4).map(::byte))
        assertEquals(listOf(5,6),listOf(byte(g+12),byte(g+44)))
        val b=StudioEngine.PATCH_BASE
        assertEquals(1,byte(b+StudioEngine.PATCH_BYTES));assertEquals(10,byte(b+2*StudioEngine.PATCH_BYTES))
        assertEquals(0,byte(b+3*StudioEngine.PATCH_BYTES));assertEquals(1,wire.getInt(StudioEngine.imageSize+4))
        assertEquals(added.id,wire.getInt(StudioEngine.imageSize+8));assertEquals(StudioEngine.imageSize+24+128,wire.capacity())
        val events=StudioEngine.ROW_INDEX+2*(128+1)
        val history=(0 until wire.getShort(StudioEngine.ROW_INDEX+2).toInt()).map { events+8*it }
        assertEquals(listOf(0,9,10,10),history.map { byte(it+3) })
        assertEquals(3,byte(history[1]+4));assertEquals(60,wire.getShort(history[2]+6).toInt())
        assertEquals(1,byte(history[3]+4));assertEquals(36,wire.getShort(history[3]+6).toInt())
        val solo=song.copy(sound=sound.copy(tracks=listOf(lanes[1])),orders=listOf(0,1),parkedOrders=emptyList(),
            patterns=listOf(set,body).map{ it.copy(tracks=listOf(it.tracks[1])) })
        assertTrue(solo.system.patternLoop(solo,1).let { it.system.errors(it).single() }.contains("missing slice"))
    }

    @Test fun eligibilityIncludesAllStoredRowsAndNeverTreatsNamedOrEditedInitAsEmpty() {
        val blank = simple()
        val parked = pattern(listOf(Cell(instrument=4)))
        val hidden = List(64) { if(it==63) Cell(instrument=5) else Cell.BLANK }
        val song = blank.copy(sound=blank.studio.withPatch(2,MonoPatch(level=1)).withPatch(3,MonoPatch("Used")),
            patterns=listOf(blank.patterns.single(),parked,pattern(hidden,length=1)),orders=listOf(0,1),parkedOrders=listOf(1))
        assertEquals((6..32).toList(),StudioBounce.slots(song))
        assertThrows(IllegalArgumentException::class.java){StudioBounce.plan(song,0,0,5)}
    }

    @Test fun frameAndOriginalBankLimitsAreCheckedBeforeRendering() {
        assertEquals(2097000L,StudioBounce.plan(simple(9,233),0,0,2).frames)
        // 2098 = 2 × 1049 cannot be represented by a valid row count/speed. 2100 is the next legal product.
        assertThrows(IllegalArgumentException::class.java){StudioBounce.plan(simple(10,210),0,0,2)}
        val blank=simple();val samples=StudioPcm.MAX_BYTES / 4
        val full=StudioAssets.EMPTY.add(StudioPcm.decode(2,48000,samples) { 0f })
        val left=full.assets.add(StudioPcm.decode(2,48000,samples-2000) { 0f })
        val sound=blank.studio.withPatch(30,SamplerPatch(assetId=full.id)).withPatch(31,SamplerPatch(assetId=left.id)).copy(assets=left.assets)
        val song=blank.copy(sound=sound)
        assertEquals(8000,StudioBounce.plan(song,0,0,2).decodedBytes)
        val extra=left.assets.add(StudioPcm.of(1,48000,floatArrayOf(.1f)))
        assertThrows(IllegalArgumentException::class.java){StudioBounce.plan(song.copy(sound=sound.copy(assets=extra.assets)),0,0,2)}
        var ids=StudioAssets.EMPTY
        repeat(32) { ids=ids.add(StudioPcm.of(1,48000,floatArrayOf(it/32f))).assets }
        assertThrows(IllegalArgumentException::class.java){StudioBounce.plan(blank.copy(sound=blank.studio.copy(assets=ids)),0,0,2)}
    }

    private fun wav(frames: Int, rate: Int=48000): ByteArray = ByteBuffer.allocate(44+4*frames).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray());putInt(36+4*frames);put("WAVEfmt ".toByteArray());putInt(16)
        putShort(1);putShort(2);putInt(rate);putInt(rate*4);putShort(4);putShort(16)
        put("data".toByteArray());putInt(4*frames);repeat(frames){putShort(16384);putShort(-8192)}
    }.array()

    @Test fun bounceInstallsOnlyRecordingAndSamplerAndSurvivesUndoSaveAndReopen() {
        val song=simple();val session=EditorSession(song);val plan=StudioBounce.plan(song,0,0,32)
        val bytes=wav(1000);val pcm=plan.decode(bytes)
        assertEquals(4044,plan.spoolBytes);assertEquals(8000,plan.decodedBytes)
        assertThrows(IllegalArgumentException::class.java){plan.decode(wav(999))}
        assertThrows(IllegalArgumentException::class.java){plan.decode(wav(1000,24000))}
        assertThrows(IllegalArgumentException::class.java){StudioBounce.install(song.copy(title="changed"),plan,pcm)}
        assertTrue(session.apply("Bounce track to sampler"){StudioBounce.install(it,plan,pcm)})
        val printed=session.song;assertSame(song.patterns,printed.patterns);assertSame(song.studio.tracks,printed.studio.tracks)
        val saved=ProjectCodec.decodeBytes(ProjectCodec.encodeBytes(printed))
        val patch=saved.studio.patch(32) as SamplerPatch
        assertEquals(0,patch.value(4));assertEquals(100,patch.value(7));assertEquals(0,patch.value(3))
        val sample=saved.studio.assets[patch.assetId!!]!!
        assertEquals(.5f,sample.sample(1998),0f);assertEquals(-.25f,sample.sample(1999),0f)
        val version=session.documentVersion;session.undo();assertSame(song,session.song);assertFalse(session.canUndo)
        assertTrue(session.documentVersion>version);session.redo();assertSame(printed,session.song)
    }

    @Test fun cancelAndBadNativeLengthNeverReturnAPartialRecording() {
        val plan=StudioBounce.plan(simple(),0,0,2)
        var closeCount=0;var running=true;var latest=0L
        fun renderer(done:Boolean)=WavRenderer { _,_,_,_ -> object:WavJob {
            override fun run(ticks:Int)=done
            override fun status()=WavStatus(900,1000,0,true,3644,false)
            override fun close(){closeCount++}
        } }
        assertFalse(plan.render(renderer(false),"unused",{!running}) { latest=it;running=false })
        assertEquals(900L,latest);assertEquals(1,closeCount)
        assertThrows(IllegalStateException::class.java){plan.render(renderer(true),"unused",{false}){}}
        assertEquals(2,closeCount)
    }
}
