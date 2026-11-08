package org.thortracker.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Songs
import org.thortracker.system.SoundSystems

/** The WAV export plumbing in core over a fake native job (the engine side: app/src/test/cpp/wav_render_test.c). */
class WavExportTest {
    /** A render of [total] frames completing after 10 runs, its loop point known after [loopAt]; [order] per run. */
    private class FakeJob(val total: Long, val loopAt: Int, val capped: Boolean = false, val orderAt: (Int) -> Int) : WavJob {
        var runs = 0
        var closed = false

        override fun run(ticks: Int): Boolean {
            runs++
            return runs >= 10
        }

        override fun status() = WavStatus(
            frames = total * runs / 10,
            target = if (runs >= loopAt) total else 0,
            order = orderAt(runs),
            looped = runs >= loopAt,
            size = if (runs >= 10) 44 + total * 4 else 0,
            capped = capped && runs >= 10,
        )

        override fun close() { closed = true }
    }

    private class Opened(val engineId: String, val image: ByteArray, val options: WavOptions, val spool: String)

    @Test
    fun rendersEverySystemsPlaybackImageWithItsEngine() {
        for (system in SoundSystems.ALL) {
            val song = system.demo()
            var opened: Opened? = null
            val options = WavOptions(loops = 2, fadeSeconds = 3)
            WavExport(song, options, "/spool", WavRenderer { id, image, o, spool -> opened = Opened(id, image, o, spool); FakeJob(48000, 5) { 0 } }).close()
            val o = checkNotNull(opened) { system.id }
            assertEquals(system.id, system.engine.id, o.engineId)
            val expected = ByteArray(system.engine.imageSize).also { system.engine.lower(song, it) }
            assertArrayEquals(system.id, expected, o.image)
            assertEquals(options, o.options)
            assertEquals("/spool", o.spool)
        }
    }

    @Test
    fun aCompleteRenderIsNeverRunAgain() {
        val job = FakeJob(480000, 4) { it }
        val export = WavExport(Songs.demo(), WavOptions(), "/spool", WavRenderer { _, _, _, _ -> job })
        while (!export.step()) Unit
        assertTrue(export.step())
        assertEquals(10, job.runs)
        assertEquals(44 + 480000L * 4, export.size)
        export.close()
        assertTrue(job.closed)
    }

    @Test
    fun progressFollowsOrdersThenTheKnownLengthAndNeverGoesBack() {
        val song = Songs.demo()
        val orders = Songs.playedOrders(song).size
        // Order 1 of the song before the loop point is known, then exact (but the job reports order 0 again).
        val job = FakeJob(1000, 3) { if (it < 3) it else 0 }
        val export = WavExport(song, WavOptions(loops = 1), "/spool", WavRenderer { _, _, _, _ -> job })
        assertEquals(0f, export.progress(), 0f)
        export.step()
        assertEquals(1f / orders / 2, export.progress(), 1e-6f)
        assertEquals(null, export.totalSeconds)
        export.step()
        val before = export.progress()
        export.step()   // loop point known: 300 of 1000 frames
        assertEquals(maxOf(before, 0.3f), export.progress(), 1e-6f)
        var last = export.progress()
        while (!export.step()) {
            val p = export.progress()
            assertTrue(p >= last && p < 1f)
            last = p
        }
        assertEquals(1f, export.progress(), 0f)
    }

    @Test
    fun aRenderStoppedAtTheLimitIsNeverReportedAsTheWholeSong() {
        val whole = WavExport(Songs.demo(), WavOptions(), "/spool", WavRenderer { _, _, _, _ -> FakeJob(48000 * 90L, 2) { 0 } })
        while (!whole.step()) Unit
        assertFalse(whole.capped)
        assertEquals("1:30", whole.summary())
        for (loopAt in listOf(2, 99)) {   // loop point known (passes cut short), or never reached
            val cut = WavExport(Songs.demo(), WavOptions(), "/spool", WavRenderer { _, _, _, _ -> FakeJob(48000 * 605L, loopAt, capped = true) { 0 } })
            while (!cut.step()) Unit
            assertTrue(cut.capped)
            assertTrue(cut.summary(), cut.summary().startsWith("10:05; ") && cut.summary().contains("10:00"))
        }
    }
}
