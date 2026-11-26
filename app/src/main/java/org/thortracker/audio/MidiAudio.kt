package org.thortracker.audio

import android.content.Context
import org.thortracker.midi.*
import org.thortracker.model.Pattern
import org.thortracker.model.Song
import org.thortracker.model.Songs
import org.thortracker.system.midi.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** One worker owns every connection, scheduled row, and sounding note. Nothing is queued ahead in the receiver. */
class MidiAudio internal constructor(
    private val worker: java.util.concurrent.ScheduledExecutorService,
    private val now: () -> Long,
    makePorts: (changed: () -> Unit, error: (String) -> Unit) -> MidiOutputPorts,
    private val report: (String) -> Unit,
) : TrackerAudio {
    constructor(context: Context, report: (String) -> Unit) : this(
        ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }, System::nanoTime,
        { changed, error -> AndroidMidiPorts(context, changed, error) }, report,
    )
    @Volatile private var closed = false
    @Volatile private var position = PlaybackPosition()
    @Volatile private var problem: String? = null
    private var song: Song? = null
    private var nextArrangement: Song? = null
    private var generation = 0L
    private val transportRequest = AtomicLong()
    private var activeRequest = 0L
    private var scheduled: java.util.concurrent.ScheduledFuture<*>? = null
    // Android's send copies the bytes synchronously. Only this worker writes the buffer.
    // Coalesce only messages already due at one musical offset on the same USB port.
    // Android's peripheral bridge otherwise receives one socket write per three-byte message.
    private val message = ByteArray(240)
    private var grouping = false
    private var groupPort: String? = null
    private var groupSize = 0
    private val outputs = mutableMapOf<String, MidiConnection>()
    private val opening = mutableSetOf<String>()
    private data class Address(val port: String, val channel: Int)
    private data class Key(val address: Address, val pitch: Int)
    private val notes = mutableMapOf<Key, MutableSet<Int>>()
    private val bent = mutableSetOf<Address>()
    private val loweredExpression = mutableSetOf<Address>()
    private val levels = mutableMapOf<Address, ChannelLevels>()
    private data class ChannelLevels(var bend: Double = 0.0, var expression: Double = 1.0)
    private data class Control(val address: Address, val cc: Int?)
    private data class ControlWrite(val control: Control, val value: Int)
    private val sustain = mutableSetOf<Address>()
    private val started = mutableSetOf<String>()
    private data class RowKey(val pattern: Int, val row: Int, val duration: Long)
    private data class RowEvent(val track: MidiTrack, val timed: TimedEvent)
    private val cachedRows = LinkedHashMap<RowKey, List<RowEvent>>(16, 0.75f, true)
    private var cachedPatterns: List<Pattern>? = null
    private var cachedSound: MidiSound? = null
    private var cachedEvents = 0
    private val ports: MidiOutputPorts = makePorts({ submit { connect() } }, { message ->
        submit { halt(); problem = message; report(message) }
    })

    @Volatile private var connected = emptySet<String>()
    internal fun ready(key: String) = key in connected
    private fun MidiEndpoint.matches(other: MidiEndpoint) =
        key == other.key && deviceId == other.deviceId && portNumber == other.portNumber

    fun endpoints(): List<MidiEndpoint> = ports.endpoints()
    private fun submit(action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed) guarded(action) } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* activity closed */ }
    }
    private fun guarded(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            halt()
            problem = e.message ?: "USB MIDI output failed"
            report(problem!!)
        }
    }
    private fun connect() {
        val required = (song?.sound as? MidiSound)?.tracks.orEmpty().map { it.port }.toSet()
        val live = endpoints()
        val stale = outputs.filter { (key, connection) -> key !in required || live.none { it.matches(connection.endpoint) } }.keys
        if (stale.isNotEmpty()) halt()
        for (key in stale) outputs.remove(key)?.close()
        connected = outputs.keys.toSet()
        for (endpoint in live) {
            if (endpoint.key !in required || endpoint.key in outputs || !opening.add(endpoint.key)) continue
            ports.open(endpoint) { connection ->
                synchronized(this) {
                    if (closed) connection?.close() else worker.execute {
                        opening.remove(endpoint.key)
                        if (connection != null) {
                            if (!closed && (song?.sound as? MidiSound)?.tracks.orEmpty().any { it.port == endpoint.key } &&
                                endpoints().any { it.matches(endpoint) }) {
                                outputs[endpoint.key] = connection
                                connected = outputs.keys.toSet()
                            } else connection.close()
                        }
                    }
                }
            }
        }
    }

    override fun update(song: Song) = submit {
        val soundChanged = this.song?.sound != song.sound
        if (soundChanged) halt()
        this.song = song
        if (position.playing) {
            song.system.errors(song).firstOrNull()?.let { error(it) }
            nextArrangement = song.system.arranged(song)
        }
        if (soundChanged || (song.sound as? MidiSound)?.tracks.orEmpty().any { it.port !in outputs }) connect()
    }
    private fun send(key: String, status: Int, a: Int = 0, b: Int = 0, count: Int = 3) {
        if (groupSize > 0 && (groupPort != key || groupSize + count > message.size)) sendGroup()
        groupPort = key
        message[groupSize++] = status.toByte()
        if (count >= 2) message[groupSize++] = a.toByte()
        if (count >= 3) message[groupSize++] = b.toByte()
        if (!grouping) sendGroup()
    }

    private fun sendGroup() {
        if (groupSize == 0) return
        val key = requireNotNull(groupPort)
        val count = groupSize
        groupSize = 0
        groupPort = null
        val out = outputs[key] ?: error("Choose a connected output under Files → MIDI tracks")
        try { out.send(message, 0, count, now()) }
        catch (e: java.io.IOException) {
            outputs.remove(key)?.close()
            connected = outputs.keys.toSet()
            throw e
        }
    }
    private fun release(key: Key, owner: Int) {
        val owners = notes[key] ?: return
        if (owners.remove(owner) && owners.isEmpty()) {
            send(key.address.port, 0x80 + key.address.channel, key.pitch + 12, 0)
            notes.remove(key)
        }
    }
    private fun deliver(track: MidiTrack, owner: Int, event: MusicalEvent) {
        val addr = Address(track.port, track.channel)
        fun bytes(status: Int, a: Int, b: Int) = send(addr.port, status + addr.channel, a, b)
        fun seven(level: Double) = (127 * level).roundToInt().coerceIn(0, 127)
        when (event) {
            is MusicalEvent.Attack -> {
                val key = Key(addr, event.pitch)
                val owners = notes.getOrPut(key) { mutableSetOf() }
                if (owners.isEmpty()) bytes(0x90, event.pitch + 12, seven(event.strength).coerceAtLeast(1))
                owners.add(owner)
            }
            is MusicalEvent.Release -> release(Key(addr, event.pitch), owner)
            MusicalEvent.Cut -> notes.keys.toList().forEach { release(it, owner) }
            else -> applyControl(track, event)?.let(::writeControl)
        }
    }

    /** Reduce actions in dispatch order. Bend is stored as wheel displacement so two tracks
     * on one channel still share one wheel even when their configured ranges differ. */
    private fun applyControl(track: MidiTrack, event: MusicalEvent): ControlWrite? {
        val addr = Address(track.port, track.channel)
        val state = levels.getOrPut(addr) { ChannelLevels() }
        val cc = when (event) {
            is MusicalEvent.Pitch -> {
                state.bend = (event.semitones / track.bendRange).coerceIn(-1.0, 1.0)
                null
            }
            is MusicalEvent.PitchStep -> {
                state.bend = (state.bend + event.semitones / track.bendRange).coerceIn(-1.0, 1.0)
                null
            }
            is MusicalEvent.ExpressionStep -> {
                state.expression = (state.expression + event.amount).coerceIn(0.0, 1.0)
                11
            }
            is MusicalEvent.Parameter -> {
                val controller = track.bindings[event.slot].controller
                if (controller == 11) state.expression = event.level
                return ControlWrite(Control(addr, controller), (127 * event.level).roundToInt())
            }
            else -> return null
        }
        val value = if (cc == null) (8192 + 8192 * state.bend).roundToInt().coerceIn(0, 16383)
            else (127 * state.expression).roundToInt()
        return ControlWrite(Control(addr, cc), value)
    }

    private fun writeControl(write: ControlWrite) {
        val (addr, cc) = write.control
        val value = write.value
        if (cc == null) {
            if (value != 8192 || addr in bent) {
                send(addr.port, 0xe0 + addr.channel, value and 127, value shr 7)
                if (value == 8192) bent.remove(addr) else bent.add(addr)
            }
        } else {
            send(addr.port, 0xb0 + addr.channel, cc, value)
            if (cc == 11) { if (value < 127) loweredExpression.add(addr) else loweredExpression.remove(addr) }
            if (cc == 64) { if (value >= 64) sustain.add(addr) else sustain.remove(addr) }
        }
    }
    private fun patch(t: MidiTrack) {
        t.bank?.let { send(t.port, 0xb0 + t.channel, 0, it shr 7); send(t.port, 0xb0 + t.channel, 32, it and 127) }
        t.program?.let { send(t.port, 0xc0 + t.channel, it, count = 2) }
    }

    private fun clearRowCache() {
        cachedRows.clear()
        cachedPatterns = null
        cachedSound = null
        cachedEvents = 0
    }

    private fun rowEvents(doc: Song, patternIndex: Int, row: Int, duration: Long): List<RowEvent> {
        val state = doc.sound as MidiSound
        if (cachedPatterns !== doc.patterns || cachedSound !== state) {
            clearRowCache()
            cachedPatterns = doc.patterns
            cachedSound = state
        }
        val key = RowKey(patternIndex, row, duration)
        cachedRows[key]?.let { return it }
        val pattern = doc.patterns[patternIndex]
        val events = mutableListOf<RowEvent>()
        state.tracks.forEachIndexed { lane, track ->
            val cell = pattern.tracks[lane][row]
            if (!cell.isBlank) for (event in musicalRow(track, cell, duration)) events += RowEvent(track, event)
        }
        // Stable sort retains lane order and the order emitted by musicalRow at equal offsets.
        events.sortBy { it.timed.offset }
        if (events.size <= MAX_CACHED_EVENTS) {
            val oldest = cachedRows.entries.iterator()
            while (cachedRows.size >= Songs.ROWS || cachedEvents + events.size > MAX_CACHED_EVENTS) {
                cachedEvents -= oldest.next().value.size
                oldest.remove()
            }
            cachedRows[key] = events
            cachedEvents += events.size
        }
        return events
    }

    // Replay controller history silently through the live reducer. Relative motion means
    // that identical pattern instances can finish at different values on successive orders.
    private fun chase(doc: Song, order: Int, row: Int, request: Long): Collection<ControlWrite>? {
        val final = mutableMapOf<Control, ControlWrite>()
        val units = rowTimeUnits(doc)
        var fraction = 0L
        for (orderIndex in 0..order) {
            val pattern = doc.orders[orderIndex]
            val length = if (orderIndex == order) row else doc.patterns[pattern].length
            for (r in 0 until length) {
                if (!currentRequest(request)) return null
                val end = fraction + units
                for ((track, timed) in rowEvents(doc, pattern, r, end / TICK_HZ)) {
                    when (val event = timed.event) {
                        is MusicalEvent.Attack, is MusicalEvent.Release, MusicalEvent.Cut -> Unit
                        else -> applyControl(track, event)?.let { final[it.control] = it }
                    }
                }
                fraction = end % TICK_HZ
            }
        }
        return final.values
    }

    // Keep fractions of a nanosecond in units of 1/TICK_HZ ns across row boundaries.
    private fun rowTimeUnits(doc: Song) = 1_000_000_000L * doc.ticksPerRow
    private fun currentRequest(request: Long) = !closed && transportRequest.get() == request

    @Synchronized override fun play(order: Int, row: Int) {
        if (closed) return
        val request = transportRequest.incrementAndGet()
        position = PlaybackPosition(pending = true)
        submit {
            if (!currentRequest(request)) return@submit
            halt()
            position = PlaybackPosition(pending = true)
            val document = requireNotNull(song)
            val invalid = document.system.errors(document).firstOrNull()
            if (!currentRequest(request)) return@submit
            if (invalid != null) error(invalid)
            val state = document.sound as MidiSound
            connect()
            require(state.tracks.all { it.port in outputs }) { "Bind each MIDI track to a connected output in Files → MIDI tracks, then play" }
            val arranged = document.system.arranged(document)
            val first = order.coerceIn(arranged.orders.indices)
            problem = null
            val firstRow = row.coerceIn(0 until arranged.patterns[arranged.orders[first]].length)
            val controls = chase(arranged, first, firstRow, request) ?: return@submit
            rowEvents(arranged, arranged.orders[first], firstRow, rowTimeUnits(arranged) / TICK_HZ)
            if (!currentRequest(request)) return@submit
            for (track in state.tracks) {
                if (!currentRequest(request)) return@submit
                patch(track)
            }
            for (write in controls) {
                if (!currentRequest(request)) return@submit
                writeControl(write)
            }
            if (state.clock) for (key in state.tracks.map { it.port }.distinct()) {
                if (!currentRequest(request)) return@submit
                send(key, 0xfa, count = 1)
                started.add(key)
            }
            if (!currentRequest(request)) return@submit
            activeRequest = request
            position = PlaybackPosition(true, first, firstRow)
            val start = now()
            later(start) { playRow(arranged, first, firstRow, start, 0L) }
        }
    }
    private fun later(deadline: Long, action: () -> Unit) {
        val request = activeRequest
        if (!currentRequest(request)) return
        val epoch = generation
        scheduled = worker.schedule(Runnable { if (currentRequest(request) && generation == epoch) guarded(action) },
            (deadline - now()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }
    private fun pulseClock() {
        for (key in started) {
            if (!currentRequest(activeRequest)) return
            send(key, 0xf8, count = 1)
        }
    }

    private fun playRow(doc: Song, order: Int, row: Int, start: Long, fraction: Long) {
        val state = doc.sound as MidiSound
        if (state.clock) pulseClock()
        if (!currentRequest(activeRequest)) return
        position = PlaybackPosition(true, order, row)
        val pattern = doc.orders[order]
        val units = rowTimeUnits(doc)
        val endUnits = fraction + units
        val duration = endUnits / TICK_HZ
        val events = rowEvents(doc, pattern, row, duration)
        var index = 0
        var pulse = 1

        fun deliverAt(offset: Long) {
            grouping = true
            try {
                while (index < events.size && events[index].timed.offset == offset && currentRequest(activeRequest)) {
                    val (track, timed) = events[index++]
                    deliver(track, timed.trackId, timed.event)
                }
            } finally {
                grouping = false
                sendGroup()
            }
        }

        fun scheduleNext() {
            val eventAt = events.getOrNull(index)?.timed?.offset ?: Long.MAX_VALUE
            val clockAt = if (state.clock && pulse < CLOCKS_PER_ROW) {
                duration * pulse / CLOCKS_PER_ROW
            } else Long.MAX_VALUE
            val offset = minOf(eventAt, clockAt)
            if (offset == Long.MAX_VALUE) {
                val end = row + 1 == doc.patterns[pattern].length
                val next = if (!end) order else if (order == doc.orders.lastIndex) doc.loopOrder else order + 1
                later(start + duration) {
                    val current = nextArrangement ?: doc
                    nextArrangement = null
                    val nextOrder = next.coerceIn(current.orders.indices)
                    val nextLength = current.patterns[current.orders[nextOrder]].length
                    playRow(current, nextOrder, if (end || row + 1 >= nextLength) 0 else row + 1,
                        start + duration, endUnits % TICK_HZ)
                }
            } else {
                val clockDue = clockAt == offset
                // Compare offsets on the musical row grid first, keeping its simultaneous
                // clock and controller actions together even when the clock rounds by 1 ns.
                val due = if (clockDue) {
                    (fraction * CLOCKS_PER_ROW + units * pulse) / (TICK_HZ * CLOCKS_PER_ROW)
                } else offset
                // A late worker still delivers the original groups in order. One callback at
                // a time lets queued commands run between overdue groups.
                later(start + due) {
                    if (clockDue) {
                        pulseClock()
                        pulse++
                    }
                    if (eventAt == offset) deliverAt(offset)
                    scheduleNext()
                }
            }
        }

        deliverAt(0L)
        scheduleNext()
    }
    override fun audition(channel: Int, note: Int, instrument: Int, detune: Int) = submit {
        val track = (song?.sound as? MidiSound)?.tracks?.getOrNull(channel) ?: return@submit
        require(track.port in outputs) { "Bind this track to an available USB MIDI output before auditioning" }
        problem = null
        notes.keys.toList().forEach { release(it, -1) }
        if (!position.playing) patch(track)
        deliver(track, -1, MusicalEvent.Attack(note, track.velocity / 127.0))
    }
    override fun releaseAudition() = submit { notes.keys.toList().forEach { release(it, -1) } }
    private fun halt() {
        generation++
        nextArrangement = null
        scheduled?.cancel(false)
        scheduled = null
        clearRowCache()
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { failure = e } }
        // Only due messages reach Android. Flushing here could discard a preceding note-off.
        started.forEach { attempt { send(it, 0xfc, count = 1) } }
        notes.keys.forEach { attempt { send(it.address.port, 0x80 + it.address.channel, it.pitch + 12, 0) } }
        sustain.forEach { attempt { send(it.port, 0xb0 + it.channel, 64, 0) } }
        bent.forEach { attempt { send(it.port, 0xe0 + it.channel, 0, 64) } }
        loweredExpression.forEach { attempt { send(it.port, 0xb0 + it.channel, 11, 127) } }
        levels.clear(); loweredExpression.clear()
        started.clear(); notes.clear(); sustain.clear(); bent.clear()
        position = position.copy(playing = false, pending = false)
        failure?.let { problem = "USB cleanup failed: ${it.message}. Rebind and use Panic on the receiver."; report(problem!!) }
    }
    @Synchronized fun panic() {
        transportRequest.incrementAndGet()
        submit {
            halt()
            val tracks = (song?.sound as? MidiSound)?.tracks.orEmpty().distinctBy { it.port to it.channel }
            for (t in tracks.filter { it.port in outputs }) {
                for (cc in listOf(64, 123, 120)) send(t.port, 0xb0 + t.channel, cc, 0)
                send(t.port, 0xe0 + t.channel, 0, 64)
            }
        }
    }
    @Synchronized override fun stop() {
        transportRequest.incrementAndGet()
        submit { halt() }
    }
    override fun position() = position
    override fun error() = problem
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        worker.execute {
            try { halt() } finally {
                ports.close()
                worker.shutdown()
            }
        }
    }

    private companion object {
        const val TICK_HZ = 48L
        const val CLOCKS_PER_ROW = 6
        // Full rows on 64 dense tracks fill the event budget before the row count limit.
        const val MAX_CACHED_EVENTS = 16_384
    }
}
