package org.thortracker.midi

import java.security.MessageDigest
import java.util.UUID

internal data class UsbMidiIdentity(val vendorId: Int, val productId: Int, val serial: String) {
    fun keyPrefix(): String {
        val descriptor = "$vendorId:$productId:$serial"
        val hash = MessageDigest.getInstance("SHA-256").digest(descriptor.toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "usb:serial-v1:$hex"
    }
}

/**
 * The OS peripheral bridge has no peer identity. It and host devices with no readable serial
 * get opaque keys for this attachment only. Once a serial collides, keep it ambiguous for the
 * lifetime of this adapter, even if only one of the original candidates remains attached.
 */
internal class UsbMidiRouteKeys(private val session: UUID = UUID.randomUUID()) {
    private val attachments = mutableMapOf<Int, Long>()
    private val ambiguous = mutableSetOf<UsbMidiIdentity>()
    private var nextAttachment = 0L

    fun update(devices: Map<Int, UsbMidiIdentity?>): Map<Int, String> {
        attachments.keys.retainAll(devices.keys)
        val counts = devices.values.filterNotNull().groupingBy { it }.eachCount()
        ambiguous.addAll(counts.filterValues { it > 1 }.keys)
        return devices.mapValues { (id, identity) ->
            if (identity != null && identity !in ambiguous) {
                identity.keyPrefix()
            } else {
                val attachment = attachments.getOrPut(id) { nextAttachment++ }
                "usb:session:$session:$attachment"
            }
        }
    }

    fun forget(deviceId: Int) {
        attachments.remove(deviceId)
    }
}
