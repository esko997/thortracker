package org.thortracker.audio

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.thortracker.midi.AndroidMidiPorts
import org.thortracker.midi.MidiConnection
import org.thortracker.midi.MidiEndpoint
import org.thortracker.midi.MidiOutputPorts
import org.thortracker.model.*
import org.thortracker.system.midi.*
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Opt in only after isolating the test handheld's physical cable and starting an external capture. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class UsbOutputCaptureTest {
    @Test fun sendTraceToPhysicalCable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalMidiCapture") == "yes")
        val args = InstrumentationRegistry.getArguments()
        val seconds = args.getString("seconds")?.toInt()?.also { require(it in 1..1805) }
        val tracks = args.getString("tracks")?.toInt() ?: 1
        require(tracks in 1..16)
        val speed = args.getString("speed")?.toInt() ?: 6
        val dense = args.getString("dense") == "yes"
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val recordSends = args.getString("recordSends") == "yes"
        require(!recordSends || (seconds ?: 0) in 1..360)
        val trace = if (recordSends) {
            val file = File(context.cacheDir, "physical-midi-submissions.bin")
            check(file.createNewFile()) { "Submission trace already exists" }
            DataOutputStream(file.outputStream().buffered(65536))
        } else null
        val worker = if (trace != null) ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true } else null
        try {
            val audio = if (worker != null && trace != null) {
                MidiAudio(worker, System::nanoTime, { changed, error ->
                    RecordingPorts(AndroidMidiPorts(context, changed, error), trace)
                }) { errors.add(it) }
            } else MidiAudio(context) { errors.add(it) }
            audio.use { runFixture(it, errors, args, seconds, tracks, speed, dense) }
        } finally {
            if (worker != null) assertTrue("USB worker stopped", worker.awaitTermination(5, TimeUnit.SECONDS))
            trace?.close()
        }
    }

    private fun runFixture(audio: MidiAudio, errors: Collection<String>, args: android.os.Bundle,
                           seconds: Int?, tracks: Int, speed: Int, dense: Boolean) {
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 5000
            while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10)
            assertTrue("USB MIDI state: ${errors.toList()}", condition())
        }
        await { audio.endpoints().isNotEmpty() }
        val port = audio.endpoints().single()
        val transitions = args.getString("transitions") == "yes"
        val sound = MidiSound(List(tracks) {
            MidiTrack(it + 1, if (transitions) it / 2 else it, port.key)
        },
            clock = true, nextId = tracks + 1)
        fun pattern(length: Int) = Pattern(length = length, tracks = List(tracks) { track ->
            List(Songs.ROWS) { row ->
                when {
                    row >= length -> Cell.BLANK
                    seconds == null -> if (row == 0) Cell(note = 48) else Cell.BLANK
                    else -> {
                        val note = Cell(note = 48 + track + row % 5)
                        if (!dense) note else when (track % 3) {
                            0 -> note.copy(effect = 0, param = 0x37, more = listOf(Fx(5, 2), Fx(1, 6), Fx(9, row)))
                            1 -> note.copy(effect = 7, param = 2, more = listOf(Fx(4, 3), Fx(10, row), Fx(8, 90)))
                            else -> note.copy(effect = 6, param = 32, more = listOf(Fx(2, 4), Fx(3, 2), Fx(9, row)))
                        }
                    }
                }
            }
        })
        val song = Song(patterns = listOf(pattern(7), pattern(11)), orders = listOf(0, 1),
            ticksPerRow = speed, sound = sound)
        audio.update(song)
        await { audio.ready(port.key) }
        val reconnectTag = args.getString("reconnectTag")
        if (reconnectTag != null) {
            require(reconnectTag.matches(Regex("midi-route-[0-9]+")))
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            fun marker(name: String) = File(cache, "$reconnectTag.$name")
            audio.play()
            await { audio.position().playing }
            marker("playing").writeText("ready")
            await { !audio.position().playing && !audio.ready(port.key) }
            marker("lost").writeText("stopped")
            val deadline = SystemClock.elapsedRealtime() + 30000
            while (!marker("receiver").isFile && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertTrue(marker("receiver").isFile)
            assertFalse(audio.position().playing)
            val newPort = audio.endpoints().single()
            audio.update(song.copy(sound = sound.copy(tracks = sound.tracks.map { it.copy(port = newPort.key) })))
            await { audio.ready(newPort.key) }
            audio.play()
            await { audio.position().playing }
            SystemClock.sleep(1200)
            audio.stop()
            await { !audio.position().playing }
            return
        }
        if (transitions) {
            repeat(100) { i ->
                audio.play(i % 2, i % 7)
                await { audio.position().playing }
                audio.audition(0, 100, 1)
                SystemClock.sleep(listOf(18L, 39L, 122L, 126L)[i % 4])
                when (i % 4) {
                    0 -> audio.panic()
                    1 -> {
                        audio.play(1, 3)
                        await { audio.position().playing && audio.position().order == 1 }
                        audio.stop()
                    }
                    else -> audio.stop()
                }
                await { !audio.position().playing }
                SystemClock.sleep(135)
            }
            assertTrue(errors.toString(), errors.isEmpty())
            return
        }
        audio.play()
        await { audio.position().playing }
        if (args.getString("changeTempo") == "yes") {
            for (liveSpeed in listOf(6, 4, 8)) {
                audio.update(song.copy(ticksPerRow = liveSpeed))
                SystemClock.sleep(60000)
                assertTrue(audio.position().playing)
            }
        } else {
            SystemClock.sleep(seconds?.times(1000L) ?: 480L)
        }
        // Deliberately hold a second pitch across Stop so an independent receiver can check cleanup.
        audio.audition(0, 57, 1)
        audio.stop()
        await { !audio.position().playing }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    /** Record successfully submitted bytes on the Android clock for comparison with the independent receiver. */
    private class RecordingPorts(private val ports: MidiOutputPorts, private val trace: DataOutputStream) :
        MidiOutputPorts by ports {
        override fun open(endpoint: MidiEndpoint, onOpened: (MidiConnection?) -> Unit) {
            ports.open(endpoint) { connection ->
                onOpened(connection?.let { port ->
                    object : MidiConnection by port {
                        override fun send(data: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
                            port.send(data, offset, count, timestampNanos)
                            trace.writeLong(timestampNanos)
                            trace.writeByte(count)
                            trace.write(data, offset, count)
                        }
                    }
                })
            }
        }
    }
}
