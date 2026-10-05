package com.spectraseq.app.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Listens to every connected MIDI controller (USB, or Bluetooth MIDI paired in system settings) —
 * keyboards, pad controllers, or Ableton Move / Push in MIDI mode — and forwards notes to [listener].
 */
class MidiInput(context: Context) {
    interface Listener {
        fun onNoteOn(channel: Int, pitch: Int, velocity: Int)
        fun onNoteOff(channel: Int, pitch: Int)
        fun onControlChange(channel: Int, cc: Int, value: Int) {}
    }

    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null
    private val handler = Handler(Looper.getMainLooper())
    private val open = HashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
    private val _devices = MutableStateFlow<List<String>>(emptyList())
    val devices: StateFlow<List<String>> = _devices
    val available get() = manager != null

    @Volatile var listener: Listener? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = openDevice(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = closeDevice(device.id)
    }

    @Suppress("DEPRECATION") // getDevices() is the only call covering minSdk 29.
    fun start() {
        val m = manager ?: return
        m.registerDeviceCallback(callback, handler)
        m.devices.forEach(::openDevice)
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        open.keys.toList().forEach(::closeDevice)
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (open.containsKey(info.id) || info.outputPortCount == 0) return
        m.openDevice(info, { device ->
            if (device == null) return@openDevice
            val ports = (0 until info.outputPortCount).mapNotNull { idx ->
                device.openOutputPort(idx)?.also { it.connect(Parser()) }
            }
            open[info.id] = device to ports
            publish()
        }, handler)
    }

    private fun closeDevice(id: Int) {
        val (device, ports) = open.remove(id) ?: return
        ports.forEach { runCatching { it.close() } }
        runCatching { device.close() }
        publish()
    }

    private fun publish() {
        _devices.value = open.values.map { (d, _) ->
            d.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"
        }
    }

    /** Minimal MIDI byte-stream parser with running status. */
    private inner class Parser : MidiReceiver() {
        private var status = 0
        private val data = IntArray(2)
        private var count = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            for (i in offset until offset + count) feed(msg[i].toInt() and 0xFF)
        }

        private fun feed(b: Int) {
            if (b >= 0xF8) return // realtime (clock etc.)
            if (b >= 0x80) { status = if (b < 0xF0) b else 0; this.count = 0; return }
            if (status == 0) return
            data[this.count++] = b
            val type = status and 0xF0
            val needed = if (type == 0xC0 || type == 0xD0) 1 else 2
            if (this.count < needed) return
            this.count = 0
            val ch = status and 0x0F
            val l = listener ?: return
            when (type) {
                0x90 -> if (data[1] == 0) l.onNoteOff(ch, data[0]) else l.onNoteOn(ch, data[0], data[1])
                0x80 -> l.onNoteOff(ch, data[0])
                0xB0 -> l.onControlChange(ch, data[0], data[1])
            }
        }
    }
}
