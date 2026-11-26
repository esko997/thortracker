package org.thortracker.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class UsbMidiRouteKeysTest {
    private val serial = UsbMidiIdentity(100, 200, "physical-unit-37")
    private val session = UUID(0, 1)

    @Test
    fun serialRouteSurvivesNewAndroidAddressAndAppSession() {
        val original = UsbMidiRouteKeys(session).update(mapOf(4 to serial)).getValue(4)
        val newSession = UsbMidiRouteKeys(UUID(0, 2))

        assertEquals(original, newSession.update(mapOf(37 to serial)).getValue(37))
        assertNotEquals(original, newSession.update(mapOf(37 to serial.copy(productId = 201))).getValue(37))
        assertFalse(original.contains(serial.serial))
    }

    @Test
    fun unverifiedRoutesAreDistinctAndExpireWhenAttachmentEnds() {
        val keys = UsbMidiRouteKeys(session)
        val first = keys.update(mapOf(7 to null, 9 to null))
        assertNotEquals(first.getValue(7), first.getValue(9))
        assertEquals(first.getValue(7), keys.update(mapOf(7 to null)).getValue(7))
        assertNotEquals(first.getValue(9), keys.update(mapOf(9 to null)).getValue(9))

        keys.forget(7)
        val newKey = keys.update(mapOf(7 to null)).getValue(7)
        assertNotEquals(first.getValue(7), newKey)
        assertNotEquals(newKey, UsbMidiRouteKeys(UUID(0, 2)).update(mapOf(7 to null)).getValue(7))
    }

    @Test
    fun removingOneDuplicateCannotRestoreAnAmbiguousSavedRoute() {
        val keys = UsbMidiRouteKeys(session)
        val saved = keys.update(mapOf(1 to serial)).getValue(1)
        val duplicate = keys.update(mapOf(1 to serial, 2 to serial))

        assertFalse(saved in duplicate.values)
        assertNotEquals(duplicate.getValue(1), duplicate.getValue(2))
        assertEquals(duplicate.getValue(2), keys.update(mapOf(2 to serial)).getValue(2))
        keys.update(emptyMap())
        assertNotEquals(saved, keys.update(mapOf(3 to serial)).getValue(3))
    }
}
