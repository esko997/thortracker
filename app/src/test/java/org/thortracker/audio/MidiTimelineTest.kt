package org.thortracker.audio

import org.junit.Assert.*
import org.junit.Test
import org.thortracker.midi.*
import org.thortracker.model.*
import org.thortracker.system.midi.*
import org.thortracker.system.studio.*
import java.util.PriorityQueue
import java.util.concurrent.*

class MidiTimelineTest {
    private class Clock(private val queueCommands: Boolean = false) : ScheduledThreadPoolExecutor(1) {
        var now = 0L
            private set
        var peakPending = 0
            private set
        private var serial = 0L
        private inner class Task(val time: Long, val seq: Long, command: Runnable) :
            FutureTask<Unit>(command, Unit), ScheduledFuture<Unit> {
            override fun getDelay(unit: TimeUnit) = unit.convert(time - now, TimeUnit.NANOSECONDS)
            override fun compareTo(other: Delayed) = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
        }
        private val pending = PriorityQueue(compareBy<Task> { it.time }.thenBy { it.seq })
        override fun execute(command: Runnable) {
            if (queueCommands) schedule(command, 0, TimeUnit.NANOSECONDS) else command.run()
        }
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            val task = Task(now + unit.toNanos(delay), serial++, command)
            pending.add(task)
            peakPending = maxOf(peakPending, pending.count { !it.isCancelled })
            return task
        }
        fun stallUntil(to: Long) {
            require(to >= now)
            now = to
        }
        fun advance(to: Long) {
            require(to >= now)
            while (pending.isNotEmpty() && pending.peek().time <= to) {
                val task = pending.remove()
                now = maxOf(now, task.time)
                if (!task.isCancelled) { task.run(); task.get() }
            }
            now = to
        }
    }
    private data class Message(val nanos: Long, val bytes: List<Int>)
    private class Receiver : MidiOutputPorts, MidiConnection {
        override val endpoint = MidiEndpoint("port", "Test fixture", 1, 0)
        val trace = mutableListOf<Message>()
        val packetSizes = mutableListOf<Int>()
        var onSend: (Message) -> Unit = {}
        override fun endpoints() = listOf(endpoint)
        override fun open(endpoint: MidiEndpoint, onOpened: (MidiConnection?) -> Unit) = onOpened(this)
        override fun send(data: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
            packetSizes += count
            var i = offset
            while (i < offset + count) {
                val status = data[i].toInt() and 255
                val length = when (status shr 4) {
                    0xf -> 1
                    0xc, 0xd -> 2
                    else -> 3
                }
                require(status >= 128 && i + length <= offset + count)
                val message = Message(timestampNanos, data.slice(i until i + length).map { it.toInt() and 255 })
                trace += message
                onSend(message)
                i += length
            }
        }
        override fun flush() {}
        override fun close() {}
    }

    private fun oneRow(cell: Cell, speed: Int = 6, track: MidiTrack = MidiTrack(1, 0, "port")) =
        Song(patterns = listOf(Pattern(tracks = listOf(listOf(cell) + List(Songs.ROWS - 1) { Cell.BLANK }), length = 1)),
            orders = listOf(0), ticksPerRow = speed, sound = MidiSound(listOf(track), clock = true))

    @Test fun simultaneousAttacksUseBoundedWholeMessagePackets() {
        val clock = Clock()
        val receiver = Receiver()
        val tracks = List(64) { MidiTrack(it + 1, it % 16, "port") }
        val cells = List(64) { lane -> List(Songs.ROWS) { row ->
            if (row == 0) Cell(note = lane, effect = 9, param = lane) else Cell.BLANK
        } }
        val doc = Song(patterns = listOf(Pattern(tracks = cells)), orders = listOf(0),
            sound = MidiSound(tracks, clock = true, nextId = 65))
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play()
            clock.advance(0)
            assertEquals(listOf(1, 1, 240, 144), receiver.packetSizes)
            for (lane in tracks.indices) {
                assertEquals(listOf(0xb0 + lane % 16, 1, lane), receiver.trace[2 + lane * 2].bytes)
                assertEquals(listOf(0x90 + lane % 16, 12 + lane, 100), receiver.trace[3 + lane * 2].bytes)
            }
            audio.stop()
            assertEquals(64, receiver.trace.count { it.bytes[0] shr 4 == 8 })
        }
    }

    @Test fun fractionalClockKeepsItsPhaseAcrossLoopsAndLiveSpeedChange() {
        val clock = Clock()
        val receiver = Receiver()
        val doc = oneRow(Cell(effect = 3, param = 20), speed = 4)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play()
            clock.advance(20_000_000)
            audio.update(doc.copy(ticksPerRow = 8))
            val clockDenominator = 48L * 6
            val expected = (0 until 6000).map { pulse ->
                val ticks = minOf(pulse, 6) * 4L + maxOf(0, pulse - 6) * 8L
                1_000_000_000L * ticks / clockDenominator
            }
            clock.advance(expected.last())
            assertEquals(expected, receiver.trace.filter { it.bytes == listOf(0xf8) }.map { it.nanos })
            // Clock and a relative increment at the same row subdivision retain their order.
            assertEquals(listOf(listOf(0xf8), listOf(0xb0, 11, 127)),
                receiver.trace.filter { it.nanos == expected[7] }.map { it.bytes })
            assertEquals(1, clock.peakPending)
        }
    }

    @Test fun overdueGroupsPreserveReleasesAndEveryClockThenReturnToTheirDeadlines() {
        val clock = Clock()
        val receiver = Receiver()
        val doc = oneRow(Cell(note = 48), track = MidiTrack(1, 0, "port", gate = 50))
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(doc)
            audio.play()
            clock.advance(0)
            clock.stallUntil(70_000_000)
            clock.advance(70_000_000)
            assertEquals(listOf(0xf8, 0xf8, 0xf8, 0x80),
                receiver.trace.filter { it.nanos == 70_000_000L }.map { it.bytes.first() })
            clock.advance(125_000_000)
            assertEquals(listOf(0L, 70_000_000L, 70_000_000L, 70_000_000L, 83_333_333L, 104_166_666L, 125_000_000L),
                receiver.trace.filter { it.bytes == listOf(0xf8) }.map { it.nanos })
            assertEquals(listOf(0xf8, 0x90), receiver.trace.filter { it.nanos == 125_000_000L }.map { it.bytes.first() })
            assertEquals(1, clock.peakPending)
        }
    }

    @Test fun livePatternEditReplacesCachedEventsAtNextRowBoundary() {
        val clock = Clock()
        val receiver = Receiver()
        val original = oneRow(Cell(note = 48))
        val edited = original.copy(patterns = oneRow(Cell(note = 50)).patterns)
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }.use { audio ->
            audio.update(original)
            audio.play()
            clock.advance(130_000_000)
            audio.update(edited)
            clock.advance(250_000_000)
            assertEquals(listOf(Message(0, listOf(0x90, 60, 100)),
                Message(125_000_000, listOf(0x90, 60, 100)),
                Message(250_000_000, listOf(0x90, 62, 100))),
                receiver.trace.filter { it.bytes.first() == 0x90 })
            assertEquals(1, receiver.trace.count { it.bytes == listOf(0xfa) })
        }
    }

    @Test fun stopCancelsAnUnstartedPlayAndAnAlreadyOverdueCallback() {
        val clock = Clock(queueCommands = true)
        val receiver = Receiver()
        val doc = oneRow(Cell(note = 48).withEffect(0, 7, 4))
        val audio = MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }
        audio.use {
            audio.update(doc)
            clock.advance(0)
            audio.play()
            audio.stop()
            clock.advance(0)
            assertTrue(receiver.trace.isEmpty())

            audio.play()
            clock.advance(0)
            assertEquals(listOf(0xfa, 0xf8, 0x90), receiver.trace.map { it.bytes.first() })
            clock.stallUntil(75_000_000)
            audio.stop()
            clock.advance(750_000_000)
            assertEquals(listOf(0xfa, 0xf8, 0x90, 0xfc, 0x80), receiver.trace.map { it.bytes.first() })
            assertFalse(audio.position().playing)
            assertFalse(audio.position().pending)
        }
        clock.advance(clock.now)
    }

    @Test fun stopDuringSeekSetupPreventsStartAndClock() {
        val clock = Clock(queueCommands = true)
        val receiver = Receiver()
        val audio = MidiAudio(clock, { clock.now }, { _, _ -> receiver }) { fail(it) }
        audio.use {
            receiver.onSend = { if (it.bytes[0] == 0xc0) audio.stop() }
            val doc = oneRow(Cell(effect = 4, param = 5)).let {
                it.copy(orders = listOf(0, 0), sound = MidiSound(listOf(MidiTrack(1, 0, "port", program = 5)), clock = true))
            }
            audio.update(doc)
            clock.advance(0)
            audio.play(1, 0)
            clock.advance(500_000_000)
            assertEquals(listOf(Message(0, listOf(0xc0, 5))), receiver.trace)
            assertFalse(audio.position().pending)
        }
        clock.advance(clock.now)
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
    @Test fun mixedUsbWaitsForRenderedRowAndCancelsAtRouteChange() {
        val clock = Clock()
        val receiver = Receiver()
        val midi = MidiTrack(2, 4, "port")
        val state = StudioSound(listOf(StudioTrack(MidiTrack(1, 0)), StudioTrack(midi, StudioTarget.USB_MIDI)), clock = true, nextId = 3)
        val monoRows = List(Songs.ROWS) { Cell(25) }
        val usbRows = List(Songs.ROWS) { Cell(48 + it % 12) }
        val song = Song(patterns = listOf(Pattern(tracks = listOf(monoRows, usbRows), length = 2)), orders = listOf(0), sound = state)
        var playing = false
        var starts = 0
        val control = object : TrackerAudio {
            override fun update(song: Song) {}
            override fun play(order: Int, row: Int) { playing = true; starts++ }
            override fun stop() { playing = false }
            override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) {}
            override fun releaseAudition() {}
            override fun position() = PlaybackPosition(playing)
            override fun error(): String? = null
            override fun close() {}
        }
        val timeline = StudioTimeline(control) { cursor, data ->
            check(playing)
            val first = cursor < 0
            data[0] = if (first) 1 else 2
            data[1] = 0; data[2] = if (first) 0 else 1
            data[3] = if (first) 40_000_000 else 190_000_000 // hardware row 2 is NOT at nominal 165 ms
            song
        }
        val failures = mutableListOf<String>()
        MidiAudio(clock, { clock.now }, { _, _ -> receiver }, timeline) { failures += it }.use { audio ->
            audio.update(song); audio.play()
            clock.advance(39_999_999); assertTrue(receiver.trace.isEmpty())
            clock.advance(189_999_999)
            assertEquals(listOf(Message(40_000_000, listOf(0x94, 60, 100))), receiver.trace.filter { it.bytes[0] shr 4 == 9 })
            clock.advance(190_000_000)
            assertEquals(Message(190_000_000, listOf(0x94, 61, 100)), receiver.trace.last())
            val unbound = state.copy(tracks = listOf(state.tracks[0], StudioTrack(midi.copy(port = ""), StudioTarget.USB_MIDI)))
            audio.update(song.copy(sound = unbound))
            assertFalse(playing)
            val last = receiver.trace.toList()
            assertTrue(last.any { it.bytes == listOf(0x84, 61, 0) })
            audio.play(); clock.advance(1_000_000_000)
            assertEquals(1, starts); assertEquals(last, receiver.trace)
            assertTrue(failures.single().contains("Bind each MIDI track"))
        }
    }
}
