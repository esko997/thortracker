package org.thortracker.audio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.midi.*
import org.thortracker.model.*
import org.thortracker.system.midi.*
import java.util.PriorityQueue
import java.util.concurrent.*

class MidiTimelineTest {
    private class Clock : ScheduledThreadPoolExecutor(1) {
        var now = 0L
        private var serial = 0L
        private inner class Task(val time: Long, val seq: Long, command: Runnable) :
            FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit) = unit.convert(time - now, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        private val pending = PriorityQueue(compareBy<Task> { it.time }.thenBy { it.seq })
        override fun execute(command: Runnable) = command.run()
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
            Task(now + unit.toNanos(delay), serial++, command).also { pending.add(it) }
        fun advance(to: Long) {
            while (pending.isNotEmpty() && pending.peek().time <= to) {
                val task = pending.remove()
                now = task.time
                if (!task.isCancelled) { task.run(); task.get() }
            }
            now = to
        }
    }
    private data class Message(val nanos: Long, val bytes: List<Int>)
    private class Receiver : MidiOutputPorts, MidiConnection {
        override val endpoint = MidiEndpoint("port", "Test fixture", 1, 0)
        val trace = mutableListOf<Message>()
        override fun endpoints() = listOf(endpoint)
        override fun open(endpoint: MidiEndpoint, onOpened: (MidiConnection?) -> Unit) = onOpened(this)
        override fun send(data: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
            trace += Message(timestampNanos, data.slice(offset until offset + count).map { it.toInt() and 255 })
        }
        override fun flush() {}
        override fun close() {}
    }

    @Test fun sharedPitchSurvivesAuditionAndStopCancelsClockAndDelayedNotes() {
        val clock = Clock()
        val receiver = Receiver()
        val sound = MidiSound(listOf(MidiTrack(1, 0, "port", gate = 50), MidiTrack(2, 0, "port", gate = 100)),
            clock = true, nextId = 3)
        val rows = List(Songs.ROWS) { if (it == 0) Cell(note = 48) else Cell(note = 52, effect = 6, param = 200) }
        val doc = Song(patterns = listOf(Pattern(tracks = listOf(rows, rows))), orders = listOf(0), sound = sound)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play()
            clock.advance(30_000_000)
            audio.audition(0, 48, 1)
            clock.advance(80_000_000)
            audio.releaseAudition()
            assertFalse(receiver.trace.any { it.bytes.first() == 0x80 })
            clock.advance(126_000_000)
            audio.stop()
            val stopped = receiver.trace.toList()
            clock.advance(500_000_000)
            assertEquals(stopped, receiver.trace)
            assertEquals(1, stopped.count { it.bytes.first() == 0x90 })
            assertEquals(listOf(0x80, 60, 0), stopped.single { it.bytes.first() == 0x80 }.bytes)
            assertEquals(listOf(0xfa), stopped.first().bytes)
            assertEquals(listOf(0xfc), stopped.last().bytes)
            assertEquals((0..6).map { 125_000_000L * it / 6 }, stopped.filter { it.bytes == listOf(0xf8) }.map { it.nanos })
        }
    }

    @Test fun seekReconstructsControlsAndPatternLoopsAtTheSameTempo() {
        val clock = Clock()
        val receiver = Receiver()
        val sound = MidiSound(listOf(MidiTrack(1, 0, "port")))
        val rows = List(Songs.ROWS) { when (it) { 0 -> Cell(effect = 9, param = 78); 1 -> Cell(note = 57); else -> Cell.BLANK } }
        val doc = Song(patterns = listOf(Pattern(tracks = listOf(rows), length = 2)), orders = listOf(0), sound = sound)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play(0, 1)
            clock.advance(126_000_000)
            assertEquals(listOf(0xb0, 1, 78), receiver.trace.first().bytes)
            assertEquals(listOf(0x90, 69, 100), receiver.trace[1].bytes)
            assertEquals(Message(125_000_000, listOf(0xb0, 1, 78)), receiver.trace.last())
            assertEquals(0, audio.position().row)
        }
    }
}
