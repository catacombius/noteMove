package com.notemove.app.midi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Bluetooth LE MIDI (BLE-MIDI) controllers: scans for the standard MIDI service, connects through
 * Android's MIDI service and keeps the connection open. Once connected the device shows up as a normal
 * MIDI device, so [MidiInput] picks up its notes like a USB controller. Last devices are reconnected
 * automatically on start.
 */
class BleMidi(private val context: Context) {
    data class Found(val address: String, val name: String, val connected: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val midi: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null
    private val prefs = context.getSharedPreferences("ble_midi", Context.MODE_PRIVATE)
    private val open = HashMap<String, MidiDevice>()
    private val names = HashMap<String, String>()

    private val _devices = MutableStateFlow<List<Found>>(emptyList())
    val devices: StateFlow<List<Found>> = _devices
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    val available get() = midi != null && btManager?.adapter != null &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Runtime permissions needed for scanning and connecting on this Android version. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPermissions() = requiredPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            names[d.address] = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: d.address
            publish()
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _status.value = "Bluetooth scan failed ($errorCode). Is Bluetooth on?"
        }
    }

    @SuppressLint("MissingPermission")
    fun scan(seconds: Int = 15) {
        if (!available) { _status.value = "Bluetooth MIDI isn't available on this device"; return }
        if (!hasPermissions()) { _status.value = "Bluetooth permission needed"; return }
        val adapter = btManager?.adapter ?: return
        if (!adapter.isEnabled) { _status.value = "Turn on Bluetooth first"; return }
        val scanner = adapter.bluetoothLeScanner ?: return
        stopScan()
        _status.value = "Scanning… put your controller in Bluetooth MIDI pairing mode"
        _scanning.value = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }.onFailure { _status.value = "Scan failed: ${it.message}"; _scanning.value = false }
        handler.postDelayed({ stopScan() }, seconds * 1000L)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!_scanning.value) return
        runCatching { btManager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
        _scanning.value = false
        if (_status.value.startsWith("Scanning")) _status.value = if (names.isEmpty()) "No Bluetooth MIDI devices found" else ""
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val m = midi ?: return
        if (!hasPermissions()) { _status.value = "Bluetooth permission needed"; return }
        if (open.containsKey(address)) return
        val device: BluetoothDevice = runCatching { btManager?.adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        _status.value = "Connecting to ${names[address] ?: address}…"
        m.openBluetoothDevice(device, { md ->
            if (md == null) { _status.value = "Couldn't connect to ${names[address] ?: address}"; return@openBluetoothDevice }
            open[address] = md
            names.putIfAbsent(address, md.info.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: address)
            remember(address, true)
            _status.value = "Connected: ${names[address]}"
            publish()
        }, handler)
    }

    fun disconnect(address: String) {
        open.remove(address)?.let { runCatching { it.close() } }
        remember(address, false)
        _status.value = ""
        publish()
    }

    /** Reconnects to controllers used last time (only if permission was already granted). */
    fun reconnectSaved() {
        if (!available || !hasPermissions()) return
        val saved = prefs.getStringSet("addresses", emptySet()).orEmpty()
        for (a in saved) { names.putIfAbsent(a, prefs.getString("name_$a", a) ?: a); connect(a) }
    }

    fun close() {
        stopScan()
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun remember(address: String, on: Boolean) {
        val set = prefs.getStringSet("addresses", emptySet()).orEmpty().toMutableSet()
        if (on) set.add(address) else set.remove(address)
        prefs.edit().putStringSet("addresses", set).putString("name_$address", names[address] ?: address).apply()
    }

    private fun publish() {
        _devices.value = names.map { (a, n) -> Found(a, n, open.containsKey(a)) }.sortedBy { !it.connected }
    }

    companion object {
        val MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }
}
