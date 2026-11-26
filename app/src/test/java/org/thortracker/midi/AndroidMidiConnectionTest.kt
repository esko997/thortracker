package org.thortracker.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AndroidMidiConnectionTest {
    private val endpoint = MidiEndpoint("usb:session:test:0:0", "USB MIDI", 13, 0)

    @Test
    fun disconnectRejectsBothOperationsAndReleasesOwnershipOnce() {
        var released = 0
        var removed = 0
        val connection = AndroidMidiConnection(
            endpoint,
            sendToPort = { _, _, _, _ -> error("disconnected send reached Android") },
            flushPort = { error("disconnected flush reached Android") },
            closeHandles = { released++ },
            ownerClosed = { false },
            onClosed = { removed++ },
        )

        assertTrue(connection.disconnect("USB disconnected"))
        assertEquals("USB disconnected", assertThrows(IOException::class.java) { connection.flush() }.message)
        assertThrows(IOException::class.java) { connection.send(byteArrayOf(1), 0, 1, 42) }
        assertFalse(connection.disconnect("second disconnect"))
        connection.close()
        assertEquals(1, released)
        assertEquals(1, removed)
    }

    @Test
    fun socketFailureClosesTheConnectionAndPreservesTheCause() {
        val brokenPipe = IOException("broken pipe")
        var released = 0
        var writes = 0
        val connection = AndroidMidiConnection(
            endpoint,
            sendToPort = { _, _, _, _ ->
                writes++
                throw brokenPipe
            },
            flushPort = { error("flush reached the broken socket") },
            closeHandles = { released++ },
            ownerClosed = { false },
            onClosed = {},
        )

        val failure = assertThrows(IOException::class.java) {
            connection.send(byteArrayOf(1), 0, 1, 42)
        }
        assertSame(brokenPipe, failure.cause)
        assertTrue(failure.message.orEmpty().contains(endpoint.label))
        assertThrows(IOException::class.java) { connection.flush() }
        assertTrue(connection.isClosed)
        assertEquals(1, released)
        assertEquals(1, writes)
    }

    @Test
    fun ownerCloseRejectsWorkBeforeHandlesAreReleased() {
        var ownerClosed = false
        var released = false
        val connection = AndroidMidiConnection(
            endpoint,
            sendToPort = { _, _, _, _ -> error("send reached Android during shutdown") },
            flushPort = { error("flush reached Android during shutdown") },
            closeHandles = { released = true },
            ownerClosed = { ownerClosed },
            onClosed = {},
        )

        ownerClosed = true
        assertThrows(IOException::class.java) { connection.send(byteArrayOf(1), 0, 1, 42) }
        assertThrows(IOException::class.java) { connection.flush() }
        assertFalse(released)
        connection.close()
        assertTrue(released)
    }
}
