package org.thortracker.audio

import org.thortracker.midi.MidiConnection
import java.io.File
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Opt-in instrumentation of an already-open Activity port. The real worker still sends every message. */
internal class ActivityMidiTimingProbe(audio: MidiAudio, key: String) {
    private val worker = MidiAudio::class.java.getDeclaredField("worker").also { it.isAccessible = true }
        .get(audio) as ScheduledExecutorService
    private val status = IntArray(40_000)
    private val sent = LongArray(status.size)
    private val returned = LongArray(status.size)
    private val longestWrite = LongArray(status.size)
    private var count = 0
    private var maximum = 0L
    private val edits = LongArray(1200)
    private val dispatched = LongArray(edits.size)
    private var editCount = 0

    init {
        worker.submit {
            @Suppress("UNCHECKED_CAST")
            val outputs = MidiAudio::class.java.getDeclaredField("outputs").also { it.isAccessible = true }
                .get(audio) as MutableMap<String, MidiConnection>
            val port = outputs.getValue(key)
            outputs[key] = object : MidiConnection by port {
                override fun send(data: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
                    port.send(data, offset, count, timestampNanos)
                    val done = System.nanoTime()
                    maximum = maxOf(maximum, done - timestampNanos)
                    val byte = data[offset].toInt() and 255
                    if (count == 1 && byte >= 0xf8) record(byte, timestampNanos, done)
                }
            }
        }.get(5, TimeUnit.SECONDS)
    }

    private fun record(byte: Int, timestamp: Long, done: Long) {
        status[count] = byte
        sent[count] = timestamp
        returned[count] = done
        longestWrite[count] = maximum
        maximum = 0
        count++
    }

    fun markEdit() {
        val enqueued = System.nanoTime()
        worker.execute {
            edits[editCount] = enqueued
            dispatched[editCount] = System.nanoTime()
            editCount++
        }
    }

    fun save(file: File) {
        if (worker.isShutdown) check(worker.awaitTermination(5, TimeUnit.SECONDS))
        else worker.submit {}.get(5, TimeUnit.SECONDS)
        check(file.createNewFile())
        file.bufferedWriter().use { out ->
            for (i in 0 until count) out.appendLine("${status[i]} ${sent[i]} ${returned[i]} ${longestWrite[i]}")
            for (i in 0 until editCount) out.appendLine("edit ${edits[i]} ${dispatched[i]}")
        }
    }
}
