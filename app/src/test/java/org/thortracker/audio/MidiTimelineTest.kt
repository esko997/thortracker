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

    @Test fun consecutiveSlidesShareTheWheelAndExpressionWithBoundCC11() {
        val clock = Clock()
        val receiver = Receiver()
        val first = MidiTrack(1, 0, "port", program = 3)
        val second = first.copy(id = 2, program = null, bindings = listOf(ControlBinding("Expression", 11), ControlBinding()))
        val sound = MidiSound(listOf(first, second), nextId = 3)
        val lane = listOf(Cell(effect = 1, param = 100).withEffect(1, 4, 20),
            Cell(effect = 1, param = 100).withEffect(1, 3, 10),
            Cell(effect = 1, param = 60).withEffect(1, 3, 10),
            Cell(effect = 2, param = 50).withEffect(1, 4, 5), Cell(note = 57))
        val rows = List(Songs.ROWS) { lane.getOrElse(it) { Cell.BLANK } }
        val controls = List(Songs.ROWS) { when (it) {
            0 -> Cell(effect = 9, param = 50)
            2 -> Cell(effect = 9, param = 125)
            else -> Cell.BLANK
        } }
        val doc = Song(patterns = listOf(Pattern(tracks = listOf(rows, controls), length = 5)), orders = listOf(0), sound = sound)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play()
            audio.audition(1, 42, 1) // Held voice on the same wheel during the effect-only rows.
            fun checkAt(t: Long, wheel: Int, expression: Int) {
                clock.advance(t)
                val pitch = receiver.trace.last { it.bytes[0] == 0xe0 }.bytes
                assertEquals(wheel, pitch[1] + 128 * pitch[2])
                assertEquals(expression, receiver.trace.last { it.bytes.take(2) == listOf(0xb0, 11) }.bytes[2])
            }
            checkAt(124_999_999, 12288, 30)
            checkAt(125_000_000, 12288, 30) // No boundary reset.
            checkAt(249_999_999, 16383, 40)
            checkAt(374_999_999, 16383, 127) // CC11 override + slide saturates both controls.
            checkAt(499_999_999, 14336, 122) // Reverses immediately from the clamp.
            checkAt(500_000_000, 8192, 122) // A fresh pattern note centers pitch only.
            checkAt(749_999_999, 12288, 30) // Looped row reinstates the explicit CC value.
            checkAt(775_000_000, 13107, 32) // First increment of the next row.
            audio.stop()
            val stopped = receiver.trace.toList()
            checkAt(900_000_000, 8192, 127)
            assertEquals(stopped, receiver.trace)
            assertEquals(1, stopped.count { it.bytes == listOf(0xc0, 3) })
        }
    }

    @Test fun clampedSlidesReverseIndependentlyOnSeparateChannels() {
        val receiver = Receiver()
        val clock = Clock()
        val low = MidiTrack(1, 0, "port", bendRange = 1, bindings = listOf(ControlBinding("Expression", 11), ControlBinding()))
        val sound = MidiSound(listOf(low, low.copy(id = 2, channel = 1)), nextId = 3)
        val lowRows = List(Songs.ROWS) { row -> when (row) {
            0 -> Cell(effect = 9, param = 3).withEffect(1, 4, 10).withEffect(2, 2, 255)
            1 -> Cell(effect = 3, param = 10).withEffect(1, 1, 25)
            else -> Cell.BLANK
        } }
        val highRows = List(Songs.ROWS) { if (it == 1) Cell(effect = 4, param = 20) else Cell.BLANK }
        val doc = Song(patterns = listOf(Pattern(tracks = listOf(lowRows, highRows))), orders = listOf(0), sound = sound)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play()
            clock.advance(124_999_999)
            assertEquals(listOf(0xe0, 0, 0), receiver.trace.last { it.bytes[0] == 0xe0 }.bytes)
            assertEquals(listOf(0xb0, 11, 0), receiver.trace.last { it.bytes[0] == 0xb0 }.bytes)
            clock.advance(249_999_999)
            assertEquals(listOf(0xe0, 0, 16), receiver.trace.last { it.bytes[0] == 0xe0 }.bytes)
            assertEquals(listOf(0xb0, 11, 10), receiver.trace.last { it.bytes[0] == 0xb0 }.bytes)
            assertEquals(listOf(0xb1, 11, 107), receiver.trace.last { it.bytes[0] == 0xb1 }.bytes)
        }
    }

    @Test fun repeatedOrderSeekAndLoopCarryRelativeSlides() {
        val clock = Clock()
        val receiver = Receiver()
        val sound = MidiSound(listOf(MidiTrack(1, 0, "port", bendRange = 4)))
        val rows = List(Songs.ROWS) { if (it == 0) Cell(effect = 2, param = 50).withEffect(1, 4, 10) else Cell.BLANK }
        val doc = Song(patterns = listOf(Pattern(tracks = listOf(rows), length = 1)), orders = listOf(0, 0, 0),
            loopOrder = 1, sound = sound)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            fun check(wheel: Int, level: Int) {
                assertEquals(listOf(0xe0, wheel and 127, wheel shr 7), receiver.trace.last { it.bytes[0] == 0xe0 }.bytes)
                assertEquals(listOf(0xb0, 11, level), receiver.trace.last { it.bytes[0] == 0xb0 }.bytes)
            }
            audio.update(doc)
            audio.play(2, 0)
            clock.advance(0)
            check(6144, 107) // Chase both earlier occurrences.
            clock.advance(124_999_999)
            check(5120, 97)
            clock.advance(249_999_999)
            check(4096, 87) // Automatic loop keeps the last values.
            audio.stop()
            audio.play()
            clock.advance(624_999_998)
            check(5120, 97) // Straight traversal reaches the same values.
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
