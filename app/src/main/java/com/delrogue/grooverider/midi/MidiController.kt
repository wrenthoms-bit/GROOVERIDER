package com.delrogue.grooverider.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listens to every MIDI controller Android can see -- a USB keyboard such as
 * the Akai MPK Mini on a USB-C cable, Bluetooth, or another app -- and hands
 * what they send to [onEvent] on the main thread. A class-compliant USB
 * controller needs no permission prompt: Android's own MIDI service owns the
 * USB side and this only opens its ports.
 *
 * Nothing here is near the audio thread; events reach the engine the way a
 * finger on a control does.
 */
class MidiController(context: Context, private val onEvent: (MidiEvent) -> Unit) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager: MidiManager? =
        if (appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
            appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        else null

    private class Connection(val device: MidiDevice, val ports: List<MidiOutputPort>, val name: String)
    private val connections = HashMap<Int, Connection>()   // by MidiDeviceInfo.id; main thread only
    private var started = false

    private val _deviceNames = MutableStateFlow<List<String>>(emptyList())
    /** The controllers currently connected, by name. */
    val deviceNames: StateFlow<List<String>> = _deviceNames.asStateFlow()

    /** False on the rare device with no MIDI support at all. */
    val available: Boolean get() = manager != null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = close(device.id)
    }

    /** Main thread. Connects to what is plugged in now and keeps watching for more. */
    fun start() {
        val m = manager ?: return
        if (started) return
        started = true
        val present: Collection<MidiDeviceInfo> =
            if (Build.VERSION.SDK_INT >= 33) {
                m.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, appContext.mainExecutor, deviceCallback)
                m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
            } else {
                @Suppress("DEPRECATION") m.registerDeviceCallback(deviceCallback, main)
                @Suppress("DEPRECATION") m.devices.toList()
            }
        present.forEach { open(it) }
    }

    /** Main thread. */
    fun stop() {
        val m = manager ?: return
        if (!started) return
        started = false
        m.unregisterDeviceCallback(deviceCallback)
        connections.keys.toList().forEach { close(it) }
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        // A device's *output* ports are where its keys and knobs come out.
        if (info.outputPortCount == 0 || info.id in connections) return
        m.openDevice(info, { device ->
            if (device == null) { Log.w(TAG, "could not open ${nameOf(info)}"); return@openDevice }
            if (!started || info.id in connections) { runCatching { device.close() }; return@openDevice }
            val ports = (0 until info.outputPortCount).mapNotNull { index ->
                device.openOutputPort(index)?.also { port ->
                    // one parser per port: running status is per stream
                    val parser = MidiParser { event -> main.post { if (started) onEvent(event) } }
                    port.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) =
                            synchronized(parser) { parser.feed(msg, offset, count) }
                    })
                }
            }
            connections[info.id] = Connection(device, ports, nameOf(info))
            publish()
            Log.i(TAG, "listening to ${nameOf(info)} (${ports.size} port(s))")
        }, main)
    }

    private fun close(id: Int) {
        val connection = connections.remove(id) ?: return
        connection.ports.forEach { runCatching { it.close() } }
        runCatching { connection.device.close() }
        publish()
    }

    private fun publish() { _deviceNames.value = connections.values.map { it.name }.sorted() }

    private fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        return p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
            ?: "MIDI device"
    }

    private companion object { const val TAG = "grvr-midi" }
}
