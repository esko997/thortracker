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
        val activeTracks = (args.getString("modularActiveTracks")?.toIntOrNull()
            ?: if (name == "studio-modular-capacity.thor") 4 else source.studio.tracks.size).coerceIn(1, 8)
        val song = source.copy(patterns = source.patterns.map { pattern ->
            pattern.copy(tracks = pattern.tracks.mapIndexed { lane, cells ->
                if (lane < activeTracks) cells else List(cells.size) { org.thortracker.model.Cell.BLANK }
            })
        })
        val slot = song.studio.patches.indexOfFirst { it is ModularPatch } + 1
        require(slot > 0)
        val original = song.studio.patch(slot) as ModularPatch
        val duration = args.getString("observeMillis")?.toLongOrNull()?.coerceIn(5000, 180000) ?: 30000
        val edits = args.getString("modularEdits") != "false"
        var peakPss = 0L
        var peakNative = 0L
        var revisions = 0
        ChipAudio(instrumentation.targetContext).use { audio ->
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
                    if (revisions % 8 == 0) audio.audition(0, 48 + revisions % 12, slot)
                    if (revisions % 8 == 4) audio.releaseAudition()
                    revisions++
                }
                SystemClock.sleep(400)
                assertNull(audio.error())
                assertTrue("Transport must not restart/stop during graph edits", audio.position().playing)
                peakPss = maxOf(peakPss, Debug.getPss())
                peakNative = maxOf(peakNative, Debug.getNativeHeapAllocatedSize())
            }
            val xruns = audio.diagnostics().getValue("xruns") - initialXruns
            audio.stop()
            await("Modular stop", audio) { !audio.position().playing && !audio.position().pending }
            audio.silence()
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "MODULAR_SOAK fixture=$name activeTracks=$activeTracks edits=$edits revisions=$revisions " +
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
            await("Reduced graph starts again", audio) { audio.position().playing && audio.error() == null }
            audio.silence()
            await("Panic completes", audio) { !audio.position().playing && !audio.position().pending }
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
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
