package com.notemove.app.midi

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import com.notesorcery.fm1link.Fm1Caps
import com.notesorcery.fm1link.Fm1Client
import com.notesorcery.fm1link.Fm1Midi
import com.notesorcery.fm1link.Fm1Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.LockSupport

/**
 * An M-VAVE FM-1 running NoteSorcery on USB (or Bluetooth MIDI): pull its song (working project and sections A..D)
 * into NoteMove, and send it NoteMove's clock so it plays along (on the FM-1: GLO > SYSTEM > SYNC = USB).
 * The FM-1 shows up as "Felucca" (the firmware family) and is confirmed by its NSX_CAPS reply (fm1link).
 */
class Fm1Link(context: Context) {
    sealed interface State {
        data object None : State
        data class Found(val name: String) : State
        data class Ready(val name: String, val caps: Fm1Caps) : State
    }

    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null
    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<State>(State.None)
    val state: StateFlow<State> = _state

    private var device: MidiDevice? = null
    private var deviceId = -1
    private var inPort: MidiInputPort? = null       // to the FM-1
    private var outPort: MidiOutputPort? = null     // from the FM-1
    @Volatile private var client: Fm1Client? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = consider(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) { if (device.id == deviceId) close() }
    }

    @Suppress("DEPRECATION") // getDevices() is the only call covering minSdk 29.
    fun start() {
        val m = manager ?: return
        m.registerDeviceCallback(callback, handler)
        m.devices.forEach(::consider)
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        clock.stop()
        close()
    }

    private fun looksLikeFm1(info: MidiDeviceInfo): Boolean {
        val p = info.properties
        val names = listOf(MidiDeviceInfo.PROPERTY_NAME, MidiDeviceInfo.PROPERTY_PRODUCT, MidiDeviceInfo.PROPERTY_MANUFACTURER)
            .mapNotNull { p.getString(it)?.lowercase() }
        return names.any { n -> "felucca" in n || "fm-1" in n || "notesorcery" in n || "hügelton" in n || "hugelton" in n }
    }

    private fun consider(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (device != null || !looksLikeFm1(info) || info.inputPortCount == 0 || info.outputPortCount == 0) return
        m.openDevice(info, { d ->
            if (d == null || device != null) { d?.close(); return@openDevice }
            val ip = d.openInputPort(0)
            val op = d.openOutputPort(0)
            if (ip == null || op == null) { ip?.close(); op?.close(); d.close(); return@openDevice }
            device = d; deviceId = info.id; inPort = ip; outPort = op
            val c = Fm1Client({ b -> runCatching { ip.send(b, 0, b.size) } })
            op.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = c.feed(msg, offset, count)
            })
            client = c
            val name = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "FM-1"
            _state.value = State.Found(name)
            Thread {                                          // (requests block: never on the main thread)
                val caps = runCatching { c.caps() }.getOrNull()
                if (caps != null && client === c) _state.value = State.Ready(name, caps)
            }.start()
        }, handler)
    }

    private fun close() {
        clock.stop()
        client = null
        runCatching { outPort?.close() }; runCatching { inPort?.close() }; runCatching { device?.close() }
        outPort = null; inPort = null; device = null; deviceId = -1
        _state.value = State.None
    }

    /** The FM-1's song (working project and its sections), read over SysEx; [progress] in bytes. */
    suspend fun pullSong(name: String, progress: (Int, Int) -> Unit = { _, _ -> }): Fm1Song = withContext(Dispatchers.IO) {
        val c = client ?: error("No FM-1 connected")
        c.pullSong(name, progress = progress)
    }

    private fun sendRaw(vararg b: Int) {
        val bytes = ByteArray(b.size) { b[it].toByte() }
        runCatching { inPort?.send(bytes, 0, bytes.size) }
    }

    /**
     * MIDI clock to the FM-1 while [isPlaying]: Song Position 0, START and 24 pulses a beat at [bpm], STOP; started
     * [latencyNanos] late so the FM-1 sounds with NoteMove's audio, not ahead of it. One thread, ~1 ms resolution.
     */
    inner class ClockOut {
        @Volatile private var thread: Thread? = null

        fun start(isPlaying: () -> Boolean, bpm: () -> Double, latencyNanos: () -> Long) {
            if (thread != null) return
            thread = Thread({
                var running = false
                var nextAt = 0L
                while (thread === Thread.currentThread()) {
                    val now = System.nanoTime()
                    val play = isPlaying()
                    if (play && !running) {
                        val at = now + latencyNanos()
                        while (System.nanoTime() < at) LockSupport.parkNanos(200_000)
                        val spp = Fm1Midi.songPosition(0)
                        sendRaw(spp[0].toInt() and 0xFF, spp[1].toInt(), spp[2].toInt())
                        sendRaw(Fm1Midi.START, Fm1Midi.CLOCK)
                        nextAt = System.nanoTime() + Fm1Midi.pulseNanos(bpm())
                        running = true
                    } else if (!play && running) {
                        sendRaw(Fm1Midi.STOP)
                        running = false
                    } else if (running && now >= nextAt) {
                        sendRaw(Fm1Midi.CLOCK)
                        nextAt += Fm1Midi.pulseNanos(bpm())
                        if (now - nextAt > 100_000_000L) nextAt = now   // (asleep for long: no burst)
                    }
                    val wait = if (running) minOf(1_000_000L, maxOf(50_000L, nextAt - System.nanoTime())) else 2_000_000L
                    LockSupport.parkNanos(wait)
                }
                if (running) sendRaw(Fm1Midi.STOP)
            }, "fm1-clock").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        }

        fun stop() {
            val t = thread ?: return
            thread = null
            LockSupport.unpark(t)
        }

        val on: Boolean get() = thread != null
    }

    val clock = ClockOut()
    val available get() = manager != null
}
