package com.spectraseq.app.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.spectraseq.core.engine.AudioEngine

/**
 * Pumps the engine into a low-latency float AudioTrack on a dedicated audio-priority thread.
 * The buffer starts at two bursts and grows by one burst whenever an underrun is detected.
 *
 * Latency is measured from [AudioTrack.getTimestamp] (frames written vs. frames actually presented),
 * which includes Bluetooth codec/transport delay — typically 150–300 ms on A2DP headphones versus
 * ~10–30 ms on the phone speaker or wired/USB audio.
 */
class AudioOutput(private val engine: AudioEngine, private val sampleRate: Int, private val burst: Int) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var latencyMs: Float = 0f
        private set
    /** Name of the device audio is currently routed to. */
    @Volatile var routeName: String = ""
        private set
    @Volatile var bluetooth: Boolean = false
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "SpectraSeqAudio").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    val isRunning get() = running

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBytes, burst * 8 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Could not open audio output", e)
            running = false
            return
        }
        var bufferFrames = burst * 2
        runCatching { track.setBufferSizeInFrames(bufferFrames).let { if (it > 0) bufferFrames = it } }
        val l = FloatArray(burst)
        val r = FloatArray(burst)
        val inter = FloatArray(burst * 2)
        var underruns = track.underrunCount
        var written = 0L
        val ts = AudioTimestamp()
        var lastMeasure = 0L
        var measuredFrames = -1.0
        track.play()
        try {
            while (running) {
                engine.render(l, r, burst)
                for (i in 0 until burst) { inter[2 * i] = l[i]; inter[2 * i + 1] = r[i] }
                var off = 0
                while (off < inter.size && running) {
                    val w = track.write(inter, off, inter.size - off, AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { Log.w(TAG, "write error $w"); break }
                    off += w
                }
                written += burst
                val u = track.underrunCount
                if (u > underruns && bufferFrames < track.bufferCapacityInFrames) {
                    underruns = u
                    bufferFrames = minOf(track.bufferCapacityInFrames, bufferFrames + burst)
                    runCatching { track.setBufferSizeInFrames(bufferFrames).let { if (it > 0) bufferFrames = it } }
                }
                val now = System.nanoTime()
                if (now - lastMeasure > 250_000_000L) {
                    lastMeasure = now
                    if (runCatching { track.getTimestamp(ts) }.getOrDefault(false) && ts.framePosition > 0) {
                        val presentedNow = ts.framePosition + (now - ts.nanoTime) / 1e9 * sampleRate
                        val lat = written - presentedNow
                        if (lat in 0.0..(sampleRate * 1.5)) measuredFrames = if (measuredFrames < 0) lat else measuredFrames * 0.8 + lat * 0.2
                    }
                    updateRoute(track)
                }
                val latencyFrames = if (measuredFrames > 0) measuredFrames.toInt() else track.bufferSizeInFrames + burst
                engine.outputLatencyFrames = latencyFrames
                latencyMs = latencyFrames * 1000f / sampleRate
            }
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }

    private fun updateRoute(track: AudioTrack) {
        val d = runCatching { track.routedDevice }.getOrNull() ?: return
        bluetooth = d.type in BLUETOOTH_TYPES
        routeName = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeName(d.type)
    }

    companion object {
        private const val TAG = "AudioOutput"
        val BLUETOOTH_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
            26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */, 30, /* BLE_BROADCAST */
        )

        fun typeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            in BLUETOOTH_TYPES -> "Bluetooth"
            else -> "Audio output"
        }
    }
}
