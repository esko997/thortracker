package org.thortracker.midi

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

interface MidiOutputPorts : AutoCloseable {
    fun endpoints(): List<MidiEndpoint>
    fun open(endpoint: MidiEndpoint, onOpened: (MidiConnection?) -> Unit)
}

/**
 * Discovers Android's USB MIDI 1.0 input ports for outbound messages. Android owns USB mode and
 * enumerates both the local peripheral bridge and USB-host devices through [MidiManager].
 *
 * Discovery and opening run on a private worker. All client callbacks run asynchronously on the
 * main looper. Close stops discovery and rejects new I/O immediately, then releases Android handles
 * on the worker. An outstanding [open] still completes once, with null if canceled.
 */
class AndroidMidiPorts(
    context: Context,
    private val onEndpointsChanged: () -> Unit,
    private val onError: (String) -> Unit,
) : MidiOutputPorts {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val thread = HandlerThread("Thor USB MIDI").apply { start() }
    private val worker = Handler(thread.looper)

    @Volatile
    private var snapshot: List<MidiEndpoint> = emptyList()

    // The worker owns platform registration, the route table, opening requests and live handles.
    private var manager: MidiManager? = null
    private var unavailableReason: String? = null
    private val keyRegistry = UsbMidiRouteKeys()
    private var routes: Map<MidiEndpoint, MidiDeviceInfo> = emptyMap()
    private val pending = mutableMapOf<OpenRequest, Runnable>()
    private val connections = mutableSetOf<AndroidMidiConnection>()
    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            if (device.type == MidiDeviceInfo.TYPE_USB) refresh()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (device.type == MidiDeviceInfo.TYPE_USB) {
                keyRegistry.forget(device.id)
                refresh()
            }
        }
    }

    init {
        worker.post { initialize() }
    }

    /** Returns the current immutable snapshot. Initial discovery completes asynchronously. */
    override fun endpoints(): List<MidiEndpoint> = if (closed.get()) emptyList() else snapshot

    /**
     * Open a destination from a current snapshot. Both its logical key and its live address must
     * match. On failure or cancellation [onOpened] receives null. Opening errors also reach
     * [onError]. Input ports are exclusive on Android, including across applications.
     */
    override fun open(endpoint: MidiEndpoint, onOpened: (MidiConnection?) -> Unit) {
        val request = OpenRequest(endpoint, onOpened)
        if (closed.get() || !worker.post { beginOpen(request) }) deliver(request, null)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.post {
            try {
                manager?.unregisterDeviceCallback(deviceCallback)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Could not unregister the MIDI device callback", failure)
            } finally {
                manager = null
                routes = emptyMap()
                snapshot = emptyList()
                pending.keys.toList().forEach { finishRequest(it, null) }
                connections.forEach { it.close() }
                connections.clear()
                thread.quitSafely()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun initialize() {
        if (closed.get()) return
        try {
            val service = if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
                appContext.getSystemService(MidiManager::class.java)
            } else {
                null
            }
            if (service == null) {
                unavailableReason = "Android MIDI is unavailable on this device. " +
                    "Use an Android device with USB MIDI support."
            } else {
                if (Build.VERSION.SDK_INT >= 33) {
                    service.registerDeviceCallback(
                        MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                        { task -> worker.post(task) },
                        deviceCallback,
                    )
                } else {
                    service.registerDeviceCallback(deviceCallback, worker)
                }
                manager = service
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not initialize Android MIDI", failure)
            unavailableReason = "Could not start Android MIDI. Reopen Thor Tracker and check USB MIDI setup."
        }
        val failure = unavailableReason
        if (failure == null) {
            refresh(initial = true)
        } else {
            publish(emptyMap(), initial = true)
            reportError(failure)
        }
    }

    @Suppress("DEPRECATION")
    private fun refresh(initial: Boolean = false) {
        val service = manager ?: return
        if (closed.get()) return
        val next = try {
            val devices = if (Build.VERSION.SDK_INT >= 33) {
                service.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
            } else {
                service.devices.asList()
            }
            buildRoutes(devices.filter { it.type == MidiDeviceInfo.TYPE_USB && it.inputPortCount > 0 })
                .also { unavailableReason = null }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not enumerate Android USB MIDI", failure)
            val message = "Could not list Android USB MIDI ports. Reconnect USB or reopen Thor Tracker. $USB_SETUP"
            unavailableReason = message
            reportError(message)
            emptyMap()
        }
        publish(next, initial)
    }

    private fun buildRoutes(devices: List<MidiDeviceInfo>): Map<MidiEndpoint, MidiDeviceInfo> {
        val prefixes = keyRegistry.update(devices.associate { it.id to usbIdentity(it) })
        val selected = devices.flatMap { device ->
            val properties = device.properties
            val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(
                    properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER),
                    properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
                ).joinToString(" ").trim().ifEmpty { "USB MIDI" }
            device.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.map { port ->
                val portName = port.name?.takeIf { it.isNotBlank() }
                val label = when {
                    portName != null -> "$name · $portName"
                    device.inputPortCount > 1 -> "$name · Port ${port.portNumber + 1}"
                    else -> name
                }
                MidiEndpoint(
                    key = "${prefixes.getValue(device.id)}:${port.portNumber}",
                    label = label,
                    deviceId = device.id,
                    portNumber = port.portNumber,
                ) to device
            }
        }
        val labelCounts = selected.groupingBy { it.first.label }.eachCount()
        return selected.map { (endpoint, device) ->
            val display = if (labelCounts.getValue(endpoint.label) > 1) {
                endpoint.copy(label = "${endpoint.label} (USB ${endpoint.deviceId}, port ${endpoint.portNumber + 1})")
            } else {
                endpoint
            }
            display to device
        }.sortedWith(compareBy({ it.first.label }, { it.first.deviceId }, { it.first.portNumber })).toMap()
    }

    @Suppress("DEPRECATION")
    private fun usbIdentity(device: MidiDeviceInfo): UsbMidiIdentity? {
        val properties = device.properties
        val usb = if (Build.VERSION.SDK_INT >= 33) {
            properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)
        } else {
            properties.getParcelable<UsbDevice>(MidiDeviceInfo.PROPERTY_USB_DEVICE)
        } ?: return null
        // Use MidiManager's descriptors. UsbDevice.getSerialNumber may require USB permission.
        val serial = properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER)
            ?.takeIf { it.isNotBlank() } ?: return null
        return UsbMidiIdentity(usb.vendorId, usb.productId, serial)
    }

    private fun publish(next: Map<MidiEndpoint, MidiDeviceInfo>, initial: Boolean) {
        routes = next
        val previous = snapshot
        snapshot = Collections.unmodifiableList(next.keys.toList())
        for (request in pending.keys.toList()) {
            if (liveRoute(request.endpoint) == null) {
                finishRequest(request, null, unavailableReason ?: unavailable(request.endpoint))
            }
        }
        for (connection in connections) {
            if (liveRoute(connection.endpoint) == null) {
                val message = "${connection.endpoint.label} is no longer available. " +
                    "Silence any stuck notes on the receiver, then reconnect and reselect the port."
                if (connection.disconnect(message)) reportError(message)
            }
        }
        if (initial || previous != snapshot) main.post { if (!closed.get()) onEndpointsChanged() }
    }

    private fun liveRoute(endpoint: MidiEndpoint) = routes.entries.firstOrNull { (current, _) ->
        current.deviceId == endpoint.deviceId && current.portNumber == endpoint.portNumber &&
            current.key == endpoint.key
    }

    private fun beginOpen(request: OpenRequest) {
        if (closed.get()) {
            deliver(request, null)
            return
        }
        refresh()
        if (closed.get()) {
            deliver(request, null)
            return
        }
        val service = manager
        val route = liveRoute(request.endpoint)
        if (service == null || route == null) {
            reportError(unavailableReason ?: unavailable(request.endpoint))
            deliver(request, null)
            return
        }
        val timeout = Runnable {
            finishRequest(request, null, "Opening ${request.endpoint.label} timed out. Reconnect the USB device and try again.")
        }
        pending[request] = timeout
        worker.postDelayed(timeout, OPEN_TIMEOUT_MS)
        try {
            // Keep the raw callback independent of our looper. Android may return a device after
            // cancellation or quit; whoever receives it must still close it.
            service.openDevice(route.value, { device ->
                if (!worker.post { finishOpen(request, device) }) closeHandle(device)
            }, null)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not open the Android MIDI device", failure)
            finishRequest(request, null, openFailed(request.endpoint))
        }
    }

    private fun finishOpen(request: OpenRequest, device: MidiDevice?) {
        if (closed.get() || request !in pending) {
            closeHandle(device)
            finishRequest(request, null)
            return
        }
        refresh()
        val endpoint = liveRoute(request.endpoint)?.key
        if (endpoint == null || closed.get()) {
            closeHandle(device)
            finishRequest(request, null)
            return
        }
        val port = try {
            device?.openInputPort(endpoint.portNumber)
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not open the Android MIDI input port", failure)
            null
        }
        if (port == null || device == null) {
            closeHandle(device)
            finishRequest(request, null, openFailed(endpoint))
            return
        }
        val connection = AndroidMidiConnection(endpoint, port::send, port::flush, {
            closeHandle(port)
            closeHandle(device)
        }, closed::get) { connection ->
            worker.post { connections.remove(connection) }
        }
        connections.add(connection)
        finishRequest(request, connection)
    }

    private fun finishRequest(request: OpenRequest, connection: AndroidMidiConnection?, error: String? = null) {
        val timeout = pending.remove(request) ?: return
        worker.removeCallbacks(timeout)
        if (error != null) reportError(error)
        deliver(request, connection)
    }

    private fun deliver(request: OpenRequest, connection: AndroidMidiConnection?) {
        main.post {
            request.onOpened(connection?.takeUnless { closed.get() || it.isClosed })
        }
    }

    private fun reportError(message: String) {
        main.post { if (!closed.get()) onError(message) }
    }

    private fun unavailable(endpoint: MidiEndpoint): String =
        "USB MIDI port ${endpoint.label} is unavailable. $USB_SETUP Then select the port again."

    private fun openFailed(endpoint: MidiEndpoint): String =
        "Could not open ${endpoint.label}. Close other apps using the port and check the USB connection."

    private class OpenRequest(val endpoint: MidiEndpoint, val onOpened: (MidiConnection?) -> Unit)

    private companion object {
        const val TAG = "ThorUsbMidi"
        const val OPEN_TIMEOUT_MS = 5_000L
        const val USB_SETUP = "Choose MIDI in Android's USB preferences when connected to a computer, " +
            "or attach a class-compliant USB MIDI device."

        fun closeHandle(handle: Closeable?) {
            try {
                handle?.close()
            } catch (failure: Exception) {
                Log.w(TAG, "Could not release an Android MIDI handle", failure)
            }
        }
    }
}
