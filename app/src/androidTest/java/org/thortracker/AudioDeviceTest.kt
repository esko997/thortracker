package org.thortracker

import org.thortracker.system.gb.HugeRules
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.thortracker.audio.ChipAudio
import org.thortracker.model.Songs
import org.junit.Assert.assertNotNull
import org.thortracker.backend.CbmImage
import org.thortracker.system.cbm.CbmKind
import org.thortracker.system.cbm.TedSystem
import org.thortracker.system.cbm.cbm
import org.thortracker.system.cbm.cbmInstrument
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.thortracker.system.midi.MidiTrack
import org.thortracker.system.studio.StudioSound
import org.thortracker.system.studio.MonoPatch
import org.thortracker.system.studio.StudioTarget
import org.thortracker.system.studio.StudioTrack
import org.thortracker.system.studio.studio

/** Uses the real JNI engine and Android audio device, not a fake callback or sequencer. */
@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @Test
    fun liveEditsAndAuditionDoNotRestartTheSong() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ChipAudio(instrumentation.targetContext).use { audio ->
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
            audio.audition(1, 72, 1) // C-6
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
    fun queuedAuditionUsesTheSongBeforeLaterInstrumentEdits() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val demo = TedSystem.demo()
        val sample = (1..64).mapNotNull { demo.cbmInstrument(it) }.first { it.kind == CbmKind.SAMPLE }
        val sampleSong = demo.copy(sound = demo.cbm.withInstrument(64, sample))
        val toneSong = sampleSong.copy(sound = sampleSong.cbm.withInstrument(64, sample.copy(kind = CbmKind.TONE)))
        for (updateQueued in listOf(false, true)) {
            ChipAudio(context).use { audio ->
                // Hold only the control executor, never the real-time callback. Both queue shapes must retain
                // causal order: a processed update, and an update still eligible for coalescing.
                val executor = ChipAudio::class.java.getDeclaredField("executor").run {
                    isAccessible = true
                    get(audio) as ScheduledThreadPoolExecutor
                }
                if (!updateQueued) {
                    audio.update(sampleSong)
                    executor.submit {}.get(5, TimeUnit.SECONDS)
                }
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                executor.execute { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
                try {
                    assertTrue("control queue entered", entered.await(5, TimeUnit.SECONDS))
                    if (updateQueued) audio.update(sampleSong)
                    audio.audition(CbmImage.TED_DAC_CHANNEL, 24, 64) // C-2 is valid for this sample.
                    audio.update(toneSong) // No new audition: C-2 is below the tone lanes' range.
                } finally {
                    resume.countDown()
                }
                executor.submit {}.get(5, TimeUnit.SECONDS)
                assertNull("later kind edit reinterpreted a queued sample audition; updateQueued=$updateQueued", audio.error())
                assertTrue("sample audition reached the native engine", audio.diagnostics().getValue("lastAuditionPrepNs") > 0)

                // The later edit must still arrive, and invalid *current* requests must still report errors.
                audio.audition(CbmImage.TED_DAC_CHANNEL, 48, 64)
                executor.submit {}.get(5, TimeUnit.SECONDS)
                assertNotNull("tone on the DAC lane must be rejected", audio.error())
                audio.audition(0, 48, 64)
                executor.submit {}.get(5, TimeUnit.SECONDS)
                assertNull("valid current tone audition clears its command error", audio.error())
                audio.releaseAudition()
            }
        }
    }
    @Test
    fun closingThePlayerReleasesItsNativeHandleEveryTime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val silentSong = Songs.blank()
        repeat(50) { iteration ->
            val audio = ChipAudio(context)
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
        ChipAudio(context).use { audio ->
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

    /** A looped pattern replays itself in the real driver, including when its own Bxx would leave it. */
    @Test
    fun aPatternLoopReplaysOnlyThatPattern() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ChipAudio(context).use { audio ->
            val blank = Songs.blank()
            val pattern = blank.patterns[0]
            val jumpToOrder2 = pattern.copy(name = "B", length = 6).let { p ->
                p.copy(tracks = p.tracks.mapIndexed { ch, t -> if (ch == 0) t.toMutableList().also {
                    it[3] = org.thortracker.model.Cell(org.thortracker.model.Cell.EMPTY, 0, HugeRules.FX_POSITION_JUMP, 0x03)
                } else t })
            }
            val song = blank.copy(
                patterns = listOf(pattern.copy(length = 5), jumpToOrder2, pattern.copy(name = "C", length = 4)),
                orders = listOf(0, 1, 2),
                ticksPerRow = 2,
            )
            audio.update(HugeRules.patternLoop(song, 1))
            audio.play(0, 0)
            await("output transport starts", audio) { audio.position().playing }
            val seen = ArrayList<Pair<Int, Int>>()
            val until = SystemClock.elapsedRealtime() + 1500
            while (SystemClock.elapsedRealtime() < until) {
                val p = audio.position()
                if (p.playing && seen.lastOrNull() != (p.order to p.row)) seen += p.order to p.row
                SystemClock.sleep(3)
            }
            audio.stop()
            assertNull(audio.error())
            assertTrue("only the looped order played, up to its B row: $seen", seen.all { (o, r) -> o == 0 && r <= 3 })
            val wraps = seen.zipWithNext().count { (a, b) -> a.second == 3 && b.second == 0 }
            assertTrue("the pattern replayed at least twice: $seen", wraps >= 2)
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
        }
    }

    /** The real driver plays the arrangement without the parked order: order 1 of what it hears is pattern C. */
    @Test
    fun aParkedOrderIsSkipped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ChipAudio(context).use { audio ->
            val blank = Songs.blank()
            val pattern = blank.patterns[0]
            val song = blank.copy(
                patterns = listOf(pattern.copy(length = 5), pattern.copy(name = "B", length = 3), pattern.copy(name = "C", length = 7)),
                orders = listOf(0, 1, 2),
                parkedOrders = listOf(1),
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
            assertTrue("only two orders are heard: $seen", seen.all { (o, _) -> o in 0..1 })
            // Pattern B is 3 rows long; reaching row 6 of the second order proves it is pattern C.
            assertTrue("the second heard order is pattern C: $seen", (1 to 6) in seen && (0 to 4) in seen)
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
        }
    }

    @Test
    fun studioUsbClockReadsOrderedNativeRowsAndTheirExactLivePatchVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sound = StudioSound(tracks = listOf(
            StudioTrack(MidiTrack(1, 0)),
            StudioTrack(MidiTrack(2, 1), StudioTarget.USB_MIDI),
        ), nextId = 3)
        val song = sound.system.blank().copy(sound = sound, patterns = listOf(
            org.thortracker.model.Pattern.blank(sound.tracks.size).copy(length = 4)))
        ChipAudio(context, true).use { audio ->
            audio.update(song)
            audio.play(0, 0)
            val row = LongArray(5)
            var cursor = -1L
            fun nextRow(): org.thortracker.model.Song {
                var heard: org.thortracker.model.Song? = null
                await("rendered Studio row", audio) {
                    heard = audio.studioRow(cursor, row)
                    heard != null
                }
                cursor = row[0]
                return checkNotNull(heard)
            }
            var previousTime = 0L
            repeat(6) { index ->
                assertEquals(sound, nextRow().studio)
                assertEquals(0L, row[1])
                assertEquals((index % 4).toLong(), row[2])
                assertTrue("native row deadlines advance across loops", row[3] > previousTime)
                previousTime = row[3]
            }
            val edited = song.copy(sound = sound.withPatch(1, (sound.patch(1) as MonoPatch).copy(cutoff = 23)))
            audio.update(edited)
            var heard = nextRow()
            repeat(8) { if ((heard.studio.patch(1) as MonoPatch).cutoff != 23) heard = nextRow() }
            assertEquals(23, (heard.studio.patch(1) as MonoPatch).cutoff)
            assertEquals(sound.tracks, heard.studio.tracks)
            audio.stop()
            await("mixed native timeline stops", audio) { audio.position().let { !it.pending && !it.playing } }
            audio.play(0, 2)
            cursor = -1
            assertEquals(23, (nextRow().studio.patch(1) as MonoPatch).cutoff)
            assertEquals(2L, row[2])
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
            audio.stop()
        }
    }

    @Test
    fun studioPatchEditsAuditionAndSeekPreserveNativeTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val blank = org.thortracker.system.studio.StudioSound().system.blank()
        val song = blank.copy(patterns = blank.patterns.map { pattern ->
            pattern.copy(tracks = pattern.tracks.map { cells ->
                cells.mapIndexed { row, _ ->
                    if (row % 4 == 0) org.thortracker.model.Cell(note = 36 + row / 4, instrument = 1)
                    else org.thortracker.model.Cell.BLANK
                }
            })
        })
        ChipAudio(instrumentation.targetContext).use { audio ->
            audio.update(song)
            audio.play()
            await("Studio starts and advances", audio) { audio.position().playing && audio.position().row >= 3 }
            val before = audio.position().row
            val sound = song.sound as org.thortracker.system.studio.StudioSound
            val changed = sound.withPatch(1, (sound.patch(1) as MonoPatch).copy(waveform = 1, cutoff = 28, resonance = 65))
            audio.update(song.copy(sound = changed))
            await("Studio keeps advancing after a live patch edit", audio) {
                audio.position().playing && audio.position().row > before
            }
            audio.audition(0, 60, 1)
            await("Studio audition reaches the real engine", audio) {
                audio.diagnostics().getValue("lastAuditionPrepNs") > 0
            }
            audio.releaseAudition()
            val auditionRow = audio.position().row
            await("Studio continues after releasing the borrowed voice", audio) {
                audio.position().playing && audio.position().row > auditionRow
            }
            audio.stop()
            await("Studio stop completes", audio) { !audio.position().playing && !audio.position().pending }
            audio.play(0, 16)
            await("Studio seeks to the requested phrase", audio) {
                val position = audio.position()
                position.playing && position.order == 0 && position.row in 16..23
            }
            assertEquals("Studio engine fault", 0L, audio.diagnostics().getValue("driverFaults"))
            audio.stop()
            await("Studio stops before switching systems", audio) {
                !audio.position().playing && !audio.position().pending
            }
            audio.update(Songs.demo())
            audio.play()
            await("Game Boy still plays after Studio", audio) {
                audio.position().playing && audio.position().row in 1..7
            }
            audio.stop()
            await("Game Boy stop completes", audio) { !audio.position().playing && !audio.position().pending }
        }
    }

    /** Opt-in capacity run against an exported fixture staged in this test app's target cache. */
    @Test fun studioCapacityTopologyAndAuditionSoak() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("studioSoakFixture")
        org.junit.Assume.assumeTrue(name != null)
        require(name == "studio-capacity.thor" || name == "studio-demo.thor")
        val file = java.io.File(instrumentation.targetContext.cacheDir, name!!)
        val song = file.inputStream().use { source ->
            org.thortracker.model.ProjectCodec.readDocument { bytes, offset, length ->
                source.read(bytes, offset, length)
            }
        }
        val duration = args.getString("observeMillis")?.toLongOrNull()?.coerceIn(5000, 180000) ?: 30000
        var peakPss = 0
        var peakNative = 0L
        ChipAudio(instrumentation.targetContext).use { audio ->
            audio.update(song)
            audio.play()
            await("capacity fixture starts", audio) { audio.position().playing }
            val start = SystemClock.elapsedRealtime()
            var revision = 0
            while (SystemClock.elapsedRealtime() - start < duration) {
                val changed = song.studio.copy(tracks = song.studio.tracks.map { lane ->
                    lane.copy(inserts = lane.inserts.reversed().mapIndexed { index, pedal ->
                        if (index == 0 && revision % 2 == 0 &&
                            lane.bindings.values.none { it.instance == pedal.id }) pedal.copy(
                            kind = org.thortracker.system.studio.StudioKind.REVERB, values = emptyMap())
                        else pedal
                    })
                })
                audio.update(song.copy(sound = if (revision % 2 == 0) changed else song.studio))
                if (revision % 4 == 0) audio.audition(0, 48 + revision % 12, 1)
                if (revision % 4 == 1) audio.releaseAudition()
                SystemClock.sleep(400)
                assertNull(audio.error())
                assertTrue("topology edits must not stop playback", audio.position().playing)
                peakPss = maxOf(peakPss, android.os.Debug.getPss())
                peakNative = maxOf(peakNative, android.os.Debug.getNativeHeapAllocatedSize())
                revision++
            }
            audio.stop()
            await("capacity stop", audio) { !audio.position().playing && !audio.position().pending }
            audio.silence()
            assertEquals(0L, audio.diagnostics().getValue("driverFaults"))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "STUDIO_CAPACITY fixture=$name revisions=$revision " +
                    "peakPssKiB=$peakPss peakNativeBytes=$peakNative ${audio.diagnostics()}\n")
            })
        }
    }

    private fun await(message: String, audio: ChipAudio, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) {
            assertNull("$message: ${audio.error()}", audio.error())
            SystemClock.sleep(10)
        }
        assertTrue(message, condition())
    }
}
