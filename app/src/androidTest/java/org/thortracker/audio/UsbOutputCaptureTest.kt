package org.thortracker.audio

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.thortracker.model.*
import org.thortracker.system.midi.*

/** Opt in only after isolating the test handheld's physical cable and starting an external capture. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class UsbOutputCaptureTest {
    @Test fun sendTraceToPhysicalCable() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalMidiCapture") == "yes")
        val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
        MidiAudio(InstrumentationRegistry.getInstrumentation().targetContext) { errors.add(it) }.use { audio ->
            fun await(condition: () -> Boolean) {
                val until = SystemClock.elapsedRealtime() + 5000
                while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10)
                assertTrue("USB MIDI state: ${errors.toList()}", condition())
            }
            await { audio.endpoints().isNotEmpty() }
            val port = audio.endpoints().single()
            val sound = MidiSound(listOf(MidiTrack(1, 0, port.key)), clock = true)
            val cells = List(Songs.ROWS) { if (it == 0) Cell(note = 48) else Cell.BLANK }
            audio.update(Song(patterns = listOf(Pattern(tracks = listOf(cells))), orders = listOf(0), sound = sound))
            await { audio.ready(port.key) }
            audio.play()
            await { audio.position().playing }
            SystemClock.sleep(480)
            audio.stop()
            await { !audio.position().playing }
            assertTrue(errors.toString(), errors.isEmpty())
        }
    }
}
