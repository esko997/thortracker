package org.thortracker

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thortracker.audio.HugeAudio
import org.thortracker.model.Songs

/** Uses the real JNI engine and Android audio device, not a fake callback or sequencer. */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @Test
    fun liveEditsAndAuditionDoNotRestartTheSong() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        HugeAudio(instrumentation.targetContext).use { audio ->
            val song = Songs.demo()
            audio.update(song)
            val playRequestedAt = SystemClock.elapsedRealtime()
            audio.play(0, 0)
            await("output transport starts", audio) { audio.position().playing }
            val observedStartMs = SystemClock.elapsedRealtime() - playRequestedAt
            await("driver advances beyond its initial row", audio) { audio.position().row >= 3 }
            val before = audio.position()
            assertEquals(0, before.order)

            // A shorter row duration must be published without seeking back to row zero.
            audio.update(song.copy(ticksPerRow = 3))
            val observeUntil = SystemClock.elapsedRealtime() + 350
            while (SystemClock.elapsedRealtime() < observeUntil) {
                val current = audio.position()
                assertTrue("live update stopped transport", current.playing)
                assertEquals("live update changed order unexpectedly", 0, current.order)
                assertTrue("live update rewound the playhead", current.row >= before.row)
                SystemClock.sleep(10)
            }
            assertTrue("live edit did not reach the callback", audio.diagnostics().getValue("lastLiveApplyNs") > 0)
            assertTrue("driver stopped progressing after edit", audio.position().row > before.row)

            val beforeAudition = audio.position()
            audio.audition(1, 36, 1)
            await("audition prepared", audio) { audio.diagnostics().getValue("lastAuditionPrepNs") > 0 }
            SystemClock.sleep(120)
            audio.releaseAudition()
            val afterAudition = audio.position()
            assertTrue("audition stopped the song", afterAudition.playing)
            assertEquals(beforeAudition.order, afterAudition.order)
            assertTrue("audition rewound the song", afterAudition.row >= beforeAudition.row)
            assertNull(audio.error())
            // Optional hardware soak: -e observeMillis 30000. No microphone or output recording.
            val observationMs = InstrumentationRegistry.getArguments().getString("observeMillis")
                ?.toLongOrNull()?.coerceIn(0, 60000) ?: 0
            val observationStarted = SystemClock.elapsedRealtime()
            var reportAt = observationStarted + 10000
            while (SystemClock.elapsedRealtime() - observationStarted < observationMs) {
                assertTrue("transport stopped during observation", audio.position().playing)
                if (SystemClock.elapsedRealtime() >= reportAt) {
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "AUDIO_SOAK elapsedMs=${SystemClock.elapsedRealtime() - observationStarted} ${audio.diagnostics()}\n")
                    })
                    reportAt += 10000
                }
                SystemClock.sleep(40)
            }
            val diagnostics = audio.diagnostics()
            assertEquals("driver faulted during live editing", 0L, diagnostics.getValue("driverFaults"))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "AUDIO_DIAGNOSTICS observedPlayStartMs=$observedStartMs $diagnostics\n")
            })
            audio.stop()
            await("transport stops", audio) { !audio.position().playing }
        }
    }
    @Test
    fun closingThePlayerReleasesItsNativeHandleEveryTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val silentSong = Songs.blank()
        repeat(50) { iteration ->
            val audio = HugeAudio(context)
            try {
                audio.update(silentSong)
                audio.play(0, 0)
                await("output starts on cycle $iteration", audio) { audio.position().playing }
                audio.stop()
            } finally {
                audio.close()
            }
            assertTrue("native player survived close on cycle $iteration: ${audio.diagnostics()}",
                audio.diagnostics().isEmpty())
        }
    }

    /** The real driver must leave a short pattern after its last played row, never reaching hidden rows. */
    @Test
    fun shortPatternsMoveOnAfterTheirLastRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        HugeAudio(context).use { audio ->
            val blank = Songs.blank()
            val pattern = blank.patterns[0]
            val song = blank.copy(
                patterns = listOf(pattern.copy(length = 5), pattern.copy(name = "B", length = 3)),
                orders = listOf(0, 1),
                ticksPerRow = 2,
            )
            audio.update(song)
            audio.play(0, 0)
            await("output transport starts", audio) { audio.position().playing }
            val seen = HashSet<Pair<Int, Int>>()
            val until = SystemClock.elapsedRealtime() + 1500
            while (SystemClock.elapsedRealtime() < until) {
                val p = audio.position()
                if (p.playing) seen += p.order to p.row
                SystemClock.sleep(3)
            }
            audio.stop()
            assertNull(audio.error())
            assertTrue("rows past a pattern's length were played: $seen", seen.all { (o, r) -> r < if (o == 0) 5 else 3 })
            assertTrue("both short patterns played to their last row: $seen", (0 to 4) in seen && (1 to 2) in seen)
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
        }
    }

    private fun await(message: String, audio: HugeAudio, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            assertNull("$message: ${audio.error()}", audio.error())
            SystemClock.sleep(10)
        }
        assertTrue(message, condition())
    }
}
