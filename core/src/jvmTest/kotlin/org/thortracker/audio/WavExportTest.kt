package org.thortracker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thortracker.model.Songs

/** Export completion and progress transitions; real engine output is covered by wav_render_test.c. */
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

}
