package org.thortracker.midi

import java.io.IOException

/**
 * A live Android MIDI 1.0 destination. [deviceId] is only meaningful while Android advertises
 * the device. Persist [key] as a route preference, and match it exactly against a fresh snapshot.
 * A key can be session-only. USB devices can also report identical serial descriptors, so keys
 * derived from descriptors are evidence of identity, not a hardware identity guarantee.
 */
data class MidiEndpoint(val key: String, val label: String, val deviceId: Int, val portNumber: Int)

/**
 * An exclusively opened Android MIDI input port. Share it among tracks using the same endpoint.
 * The caller owns sequencing and note cleanup. Use a worker thread for I/O and serialize musical
 * operations in the intended order. Closing releases the port and device without a delivery ACK.
 */
interface MidiConnection : AutoCloseable {
    val endpoint: MidiEndpoint

    /**
     * Sends bytes with a timestamp in the [System.nanoTime] domain. Success means Android took
     * the bytes, without confirming receipt by the USB peer. Throws [IOException] when closed,
     * disconnected or on I/O failure. The connection closes on an I/O failure.
     */
    @Throws(IOException::class)
    fun send(data: ByteArray, offset: Int, count: Int, timestampNanos: Long)

    /**
     * Asks Android to discard data it still has queued. Bytes already delivered cannot be
     * recalled. Throws [IOException] on a closed/disconnected port or I/O failure.
     */
    @Throws(IOException::class)
    fun flush()

    override fun close()
}

internal class AndroidMidiConnection(
    override val endpoint: MidiEndpoint,
    private val sendToPort: (ByteArray, Int, Int, Long) -> Unit,
    private val flushPort: () -> Unit,
    private val closeHandles: () -> Unit,
    private val ownerClosed: () -> Boolean,
    private val onClosed: (AndroidMidiConnection) -> Unit,
) : MidiConnection {
    private val lock = Any()

    @Volatile
    private var closedReason: String? = null

    val isClosed: Boolean get() = closedReason != null

    override fun send(data: ByteArray, offset: Int, count: Int, timestampNanos: Long) {
        require(offset >= 0 && count >= 0 && count <= data.size - offset) {
            "MIDI byte range is outside the buffer"
        }
        write("send MIDI to") { sendToPort(data, offset, count, timestampNanos) }
    }

    override fun flush() = write("flush MIDI on", flushPort)

    override fun close() {
        disconnect("USB MIDI port ${endpoint.label} is closed. Open the destination again.")
    }

    fun disconnect(reason: String): Boolean = synchronized(lock) { closeLocked(reason) }

    private inline fun write(operation: String, writeToPort: () -> Unit) {
        synchronized(lock) {
            closedReason?.let { throw IOException(it) }
            if (ownerClosed()) throw IOException("The Android MIDI transport is closed.")
            try {
                writeToPort()
            } catch (failure: IOException) {
                val message = "Could not $operation ${endpoint.label}. Check the USB connection, " +
                    "silence any stuck notes on the receiver, and reopen the destination."
                closeLocked(message)
                throw IOException(message, failure)
            }
        }
    }

    private fun closeLocked(reason: String): Boolean {
        if (closedReason != null) return false
        closedReason = reason
        try {
            closeHandles()
        } finally {
            onClosed(this)
        }
        return true
    }
}
