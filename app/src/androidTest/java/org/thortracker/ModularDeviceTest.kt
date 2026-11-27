package org.thortracker

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thortracker.audio.ChipAudio
import org.thortracker.model.ProjectCodec
import org.thortracker.system.studio.*

/** Opt-in real AAudio checks. Reads a staged fixture; never writes the editor document or settings. */
@RunWith(AndroidJUnit4::class)
class ModularDeviceTest {
    @Test fun heldAuditionLiveGraphReplacementAndTransportSoak() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("modularFixture")
        assumeTrue(name != null)
        require(name in setOf("studio-modular.thor", "studio-modular-capacity.thor"))
        val file = java.io.File(instrumentation.targetContext.cacheDir, name!!)
        val source = file.inputStream().use { input -> ProjectCodec.readDocument { bytes, offset, length ->
            input.read(bytes, offset, length)
        } }
        val maxRack = args.getString("modularMaxRack") == "true"
        require(!maxRack || name == "studio-modular-capacity.thor")
        val staged = if (maxRack) {
            var instance = 1
            val tracks = source.studio.tracks.map { track -> track.copy(
                inserts = List(4) { StudioInsert(instance++, StudioKind.DELAY) },
                sends = listOf(25, 25),
                bindings = track.bindings.filterValues { it.instance == 0 },
            ) }
            val buses = List(2) { StudioBus(List(4) { StudioInsert(instance++, StudioKind.DELAY) }) }
            source.copy(sound = source.studio.copy(tracks = tracks, buses = buses, nextInstance = instance))
        } else source
        val activeTracks = (args.getString("modularActiveTracks")?.toIntOrNull()
            ?: if (maxRack) 3 else if (name == "studio-modular-capacity.thor") 4 else source.studio.tracks.size).coerceIn(1, 8)
        val song = staged.copy(patterns = staged.patterns.map { pattern ->
            pattern.copy(tracks = pattern.tracks.mapIndexed { lane, cells ->
                if (lane < activeTracks) cells else List(cells.size) { org.thortracker.model.Cell.BLANK }
            })
        })
        val slot = song.studio.patches.indexOfFirst { it is ModularPatch } + 1
        require(slot > 0)
        val original = song.studio.patch(slot) as ModularPatch
        val duration = args.getString("observeMillis")?.toLongOrNull()?.coerceIn(5000, 180000) ?: 30000
        val edits = args.getString("modularEdits") != "false"
        val auditions = args.getString("modularAudition")?.let { it == "true" } ?: edits
        val sampleMemory = args.getString("modularSampleMemory") != "false"
        var peakPss = 0L
        var peakNative = 0L
        var revisions = 0
        var iterations = 0
        ChipAudio(instrumentation.targetContext).use { audio ->
            audio.setTimingProbe(args.getString("modularTiming") == "true")
            peakPss = Debug.getPss()
            peakNative = Debug.getNativeHeapAllocatedSize()
            audio.update(song)
            audio.play()
            await("Modular starts", audio) { audio.position().playing }
            val initialXruns = audio.diagnostics().getValue("xruns")
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < duration) {
                if (edits) {
                    // Reverse storage order, not signal connections: all node IDs and live histories survive.
                    val graph = if (revisions % 2 == 0) original.graph.copy(nodes = original.graph.nodes.reversed()) else original.graph
                    val patch = original.copy(graph = graph, values = original.values + (0 to (revisions * 7 % 128)))
                    audio.update(song.copy(sound = song.studio.withPatch(slot, patch)))
                }
                if (auditions) {
                    if (iterations % 8 == 0) audio.audition(0, 48 + iterations % 12, slot)
                    if (iterations % 8 == 4) audio.releaseAudition()
                }
                if (edits) {
                    revisions++
                }
                iterations++
                SystemClock.sleep(400)
                assertNull(audio.error())
                assertTrue("Transport must not restart/stop during graph edits", audio.position().playing)
                if (sampleMemory) {
                    peakPss = maxOf(peakPss, Debug.getPss())
                    peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
                }
            }
            val xruns = audio.diagnostics().getValue("xruns") - initialXruns
            peakPss = maxOf(peakPss, Debug.getPss())
            peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
            audio.stop()
            await("Modular stop", audio) { !audio.position().playing && !audio.position().pending }
            audio.silence()
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "MODULAR_SOAK fixture=$name maxRack=$maxRack activeTracks=$activeTracks edits=$edits auditions=$auditions revisions=$revisions periodicMemory=$sampleMemory " +
                    "initialXruns=$initialXruns measuredXruns=$xruns peakPssKiB=$peakPss " +
                    "peakNativeBytes=$peakNative ${audio.diagnostics()}\n")
            })
        }
    }

    @Test fun oversizedLiveEditStopsOldGraphAndSmallerProjectCanResume() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modularFixture") != null)
        val song = studioModularDemo()
        val generous = StudioModularLimits("test reserve", Long.MAX_VALUE, 2L * 1024 * 1024 * 1024, 12, 14)
        val limit = generous.copy(workUnits = StudioModularAdmission.demand(song, generous)!!.workUnits)
        val larger = song.copy(sound = song.studio.copy(patches = song.studio.patches.map { patch ->
            if (patch !is ModularPatch) patch else {
                var graph = patch.graph
                while (graph.nodes.size < 64) graph = graph.copy(nodes = graph.nodes +
                    ModularNode(graph.nextNodeId(), ModularKind.NOISE))
                patch.copy(graph = graph)
            }
        }))
        assertNotNull(StudioModularAdmission.problem(larger, limit))
        val original = ProjectCodec.encodeBytes(larger)
        ChipAudio(InstrumentationRegistry.getInstrumentation().targetContext, modularLimits = limit).use { audio ->
            audio.update(song)
            audio.play()
            await("Admitted graph starts", audio) { audio.position().playing }
            audio.audition(0, 48, 1)
            waitUntil("Audition overlap refused") { audio.error()?.contains("test reserve") == true }
            assertTrue("Refused new audition must not stop the admitted song", audio.position().playing)
            audio.update(larger)
            waitUntil("Oversized edit silences obsolete graph") {
                audio.error()?.contains("test reserve") == true && !audio.position().playing && !audio.position().pending
            }
            audio.play()
            SystemClock.sleep(100)
            assertFalse(audio.position().playing)
            assertArrayEquals(original, ProjectCodec.encodeBytes(larger))
            audio.update(song)
            audio.play()
            waitUntil("Reduced graph starts again") { audio.position().playing && audio.error() == null }
            audio.silence()
            await("Panic completes", audio) { !audio.position().playing && !audio.position().pending }
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
        }
    }

    @Test fun releasedAuditionRetainsItsBudgetUntilTheNativeTailEnds() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modularFixture") != null)
        val song = studioModularDemo()
        val generous = StudioModularLimits("tail reserve", Long.MAX_VALUE, 2L * 1024 * 1024 * 1024, 12, 14)
        val limit = generous.copy(workUnits = StudioModularAdmission.demand(song, generous, 1)!!.workUnits)
        val larger = song.copy(sound = song.studio.copy(patches = song.studio.patches.map { patch ->
            if (patch !is ModularPatch) patch else patch.copy(graph = patch.graph.copy(
                nodes = patch.graph.nodes + ModularNode(patch.graph.nextNodeId(), ModularKind.NOISE)))
        }))
        assertNull(StudioModularAdmission.problem(larger, limit))
        assertNotNull(StudioModularAdmission.problem(larger, limit, 1))
        ChipAudio(InstrumentationRegistry.getInstrumentation().targetContext, modularLimits = limit).use { audio ->
            audio.update(song)
            audio.play()
            await("Original song starts", audio) { audio.position().playing }
            audio.audition(0, 48, 1)
            await("Audition is published or sounding", audio) { audio.diagnostics()["auditionActive"] == 1L }
            SystemClock.sleep(40)
            audio.releaseAudition()
            audio.update(larger)
            waitUntil("Released tail still reserves overlap") {
                audio.error()?.contains("tail reserve") == true && !audio.position().playing && !audio.position().pending
            }
            audio.update(song)
            audio.play()
            waitUntil("Original song resumes") { audio.position().playing && audio.error() == null }
            audio.audition(0, 48, 1)
            await("Second audition starts", audio) { audio.diagnostics()["auditionActive"] == 1L }
            SystemClock.sleep(40)
            audio.releaseAudition()
            val deadline = SystemClock.elapsedRealtime() + 12000
            while (audio.diagnostics()["auditionActive"] != 0L && SystemClock.elapsedRealtime() < deadline) {
                assertNull(audio.error())
                SystemClock.sleep(20)
            }
            assertEquals("Native tail finishes", 0L, audio.diagnostics().getValue("auditionActive"))
            audio.update(larger)
            audio.stop()
            waitUntil("Update and Stop complete") { !audio.position().playing && !audio.position().pending }
            assertNull("Finished tail no longer reserves overlap", audio.error())
            audio.play()
            await("Larger song starts without audition", audio) { audio.position().playing }
            audio.silence()
        }
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue(message, condition())
    }

    private fun await(message: String, audio: ChipAudio, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            assertNull("$message: ${audio.error()}", audio.error())
            SystemClock.sleep(10)
        }
        assertTrue(message, condition())
    }
}
