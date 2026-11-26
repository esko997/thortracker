package org.thortracker.audio

import android.content.Context
import org.thortracker.midi.*
import org.thortracker.model.Song
import org.thortracker.system.midi.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private var scheduled: java.util.concurrent.ScheduledFuture<*>? = null
    private val outputs = mutableMapOf<String, MidiConnection>()
    private val opening = mutableSetOf<String>()
    private data class Address(val port: String, val channel: Int)
    private data class Key(val address: Address, val pitch: Int)
    private val notes = mutableMapOf<Key, MutableSet<Int>>()
    private val bent = mutableSetOf<Address>()
    private val sustain = mutableSetOf<Address>()
    private val started = mutableSetOf<String>()
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
        if (this.song?.sound != song.sound) halt()
        this.song = song
        if (position.playing) {
            song.system.errors(song).firstOrNull()?.let { error(it) }
            nextArrangement = song.system.arranged(song)
        }
        connect()
    }
    private fun send(key: String, vararg bytes: Int) {
        val out = outputs[key] ?: error("Choose a connected output under Files → MIDI tracks")
        val data = ByteArray(bytes.size) { bytes[it].toByte() }
        try { out.send(data, 0, data.size, now()) }
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
            is MusicalEvent.Expression -> bytes(0xb0, 11, seven(event.level))
            is MusicalEvent.Parameter -> {
                val cc = track.bindings[event.slot].controller
                bytes(0xb0, cc, seven(event.level))
                if (cc == 64) { if (seven(event.level) >= 64) sustain.add(addr) else sustain.remove(addr) }
            }
            is MusicalEvent.Pitch -> {
                if (event.semitones != 0.0 || addr in bent) {
                    val value = (8192 + 8192 * event.semitones / track.bendRange).roundToInt().coerceIn(0, 16383)
                    bytes(0xe0, value and 127, value shr 7)
                    if (value == 8192) bent.remove(addr) else bent.add(addr)
                }
            }
        }
    }
    private fun patch(t: MidiTrack) {
        t.bank?.let { send(t.port, 0xb0 + t.channel, 0, it shr 7); send(t.port, 0xb0 + t.channel, 32, it and 127) }
        t.program?.let { send(t.port, 0xc0 + t.channel, it) }
    }
    // A seek replays only the final controller values before the cursor. Repeated orders reuse
    // their pattern summary. Destination/channel keys account for tracks that share a receiver.
    private fun chase(doc: Song, order: Int, row: Int) {
        data class Setting(val address: Address, val controller: Int)
        data class Value(val track: MidiTrack, val event: MusicalEvent)
        val state = doc.sound as MidiSound
        val summaries = mutableMapOf<Pair<Int, Int>, Map<Setting, Value>>()
        val final = mutableMapOf<Setting, Value>()
        for (orderIndex in 0..order) {
            val patternId = doc.orders[orderIndex]
            val pattern = doc.patterns[patternId]
            val length = if (orderIndex == order) row else pattern.length
            val summary = summaries.getOrPut(patternId to length) {
                val values = mutableMapOf<Setting, Value>()
                for (r in 0 until length) {
                    val events = state.tracks.flatMapIndexed { lane, track ->
                        val cell = pattern.tracks[lane][r]
                        if (cell.isBlank) emptyList() else musicalRow(track, cell, 60000).map { track to it }
                    }.sortedBy { it.second.offset }
                    for ((t, timed) in events) {
                        val cc = when (val e = timed.event) {
                            is MusicalEvent.Parameter -> t.bindings[e.slot].controller
                            is MusicalEvent.Expression -> 11
                            is MusicalEvent.Pitch -> -1
                            else -> continue
                        }
                        values[Setting(Address(t.port, t.channel), cc)] = Value(t, timed.event)
                    }
                }
                values
            }
            final.putAll(summary)
        }
        final.values.forEach { deliver(it.track, it.track.id, it.event) }
    }

    override fun play(order: Int, row: Int) {
        position = PlaybackPosition(pending = true)
        submit {
            halt()
            val document = requireNotNull(song)
            document.system.errors(document).firstOrNull()?.let { error(it) }
            val state = document.sound as MidiSound
            connect()
            require(state.tracks.all { it.port in outputs }) { "Bind each MIDI track to a connected output in Files → MIDI tracks, then play" }
            val arranged = document.system.arranged(document)
            val first = order.coerceIn(arranged.orders.indices)
            problem = null
            state.tracks.forEach(::patch)
            val firstRow = row.coerceIn(0 until arranged.patterns[arranged.orders[first]].length)
            chase(arranged, first, firstRow)
            if (state.clock) for (key in state.tracks.map { it.port }.distinct()) {
                send(key, 0xfa); started.add(key)
            }
            scheduleRow(arranged, first, firstRow, now())
        }
    }
    private fun later(deadline: Long, action: () -> Unit) {
        if (closed) return
        val epoch = generation
        scheduled = worker.schedule(Runnable { if (!closed && generation == epoch) guarded(action) },
            (deadline - now()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    }
    private fun scheduleRow(doc: Song, order: Int, row: Int, start: Long) {
        position = PlaybackPosition(true, order, row)
        val state = doc.sound as MidiSound
        val pattern = doc.patterns[doc.orders[order]]
        val duration = 1_000_000_000L * doc.ticksPerRow / 48
        val actions = mutableListOf<Pair<Long, () -> Unit>>()
        if (state.clock) for (pulse in 0 until 6) actions += duration * pulse / 6 to {
            started.forEach { send(it, 0xf8) }
        }
        state.tracks.forEachIndexed { i, track ->
            for (e in musicalRow(track, pattern.tracks[i][row], duration)) actions += e.offset to { deliver(track, e.trackId, e.event) }
        }
        actions.sortBy { it.first }
        fun dispatch(index: Int) {
            if (index == actions.size) {
                val end = row + 1 == pattern.length
                val next = if (!end) order else if (order == doc.orders.lastIndex) doc.loopOrder else order + 1
                later(start + duration) {
                    val current = nextArrangement ?: doc
                    nextArrangement = null
                    val nextOrder = next.coerceIn(current.orders.indices)
                    val nextLength = current.patterns[current.orders[nextOrder]].length
                    scheduleRow(current, nextOrder, if (end || row + 1 >= nextLength) 0 else row + 1, start + duration)
                }
            } else later(start + actions[index].first) {
                var i = index
                do { actions[i++].second() } while (i < actions.size && actions[i].first == actions[index].first)
                dispatch(i)
            }
        }
        dispatch(0)
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
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (e: Exception) { failure = e } }
        // Only due messages reach Android. Flushing here could discard a preceding note-off.
        started.forEach { attempt { send(it, 0xfc) } }
        notes.keys.forEach { attempt { send(it.address.port, 0x80 + it.address.channel, it.pitch + 12, 0) } }
        sustain.forEach { attempt { send(it.port, 0xb0 + it.channel, 64, 0) } }
        bent.forEach { attempt { send(it.port, 0xe0 + it.channel, 0, 64) } }
        started.clear(); notes.clear(); sustain.clear(); bent.clear()
        position = position.copy(playing = false, pending = false)
        failure?.let { problem = "USB cleanup failed: ${it.message}. Rebind and use Panic on the receiver."; report(problem!!) }
    }
    fun panic() = submit {
        halt()
        val tracks = (song?.sound as? MidiSound)?.tracks.orEmpty().distinctBy { it.port to it.channel }
        for (t in tracks.filter { it.port in outputs }) {
            for (cc in listOf(64, 123, 120)) send(t.port, 0xb0 + t.channel, cc, 0)
            send(t.port, 0xe0 + t.channel, 0, 64)
        }
    }
    override fun stop() = submit { halt() }
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
}
