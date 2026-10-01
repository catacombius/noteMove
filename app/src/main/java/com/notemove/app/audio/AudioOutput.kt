package com.notemove.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.notemove.core.engine.AudioEngine

/**
 * Pumps the engine into a low-latency float AudioTrack on a dedicated audio-priority thread.
 * The buffer starts at two bursts and grows by one burst whenever an underrun is detected.
 */
class AudioOutput(private val engine: AudioEngine, private val sampleRate: Int, private val burst: Int) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var latencyMs: Float = 0f
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "NoteMoveAudio").apply { start() }
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
                val u = track.underrunCount
                if (u > underruns && bufferFrames < track.bufferCapacityInFrames) {
                    underruns = u
                    bufferFrames = minOf(track.bufferCapacityInFrames, bufferFrames + burst)
                    runCatching { track.setBufferSizeInFrames(bufferFrames).let { if (it > 0) bufferFrames = it } }
                }
                val latencyFrames = track.bufferSizeInFrames + burst
                engine.outputLatencyFrames = latencyFrames
                latencyMs = latencyFrames * 1000f / sampleRate
            }
        } finally {
            runCatching { track.pause(); track.flush(); track.stop() }
            track.release()
        }
    }

    companion object { private const val TAG = "AudioOutput" }
}
