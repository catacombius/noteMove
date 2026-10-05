package com.spectraseq.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.abs
import kotlin.math.max

/** Records mono audio from the microphone for sampling (like Note's / Move's sampler). */
class MicRecorder(private val sampleRate: Int) {
    @Volatile var level: Float = 0f
        private set
    @Volatile var recording = false
        private set
    @Volatile var seconds: Float = 0f
        private set

    private var thread: Thread? = null
    private val chunks = ArrayList<FloatArray>()
    private var total = 0

    @SuppressLint("MissingPermission") // Caller checks RECORD_AUDIO.
    fun start(maxSeconds: Int = 30): Boolean {
        if (recording) return true
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBytes <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, minBytes * 4)
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, minBytes * 4)
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        synchronized(chunks) { chunks.clear(); total = 0 }
        recording = true
        val maxFrames = maxSeconds * sampleRate
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buf = FloatArray(1024)
            rec.startRecording()
            try {
                while (recording && total < maxFrames) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    var peak = 0f
                    for (i in 0 until n) peak = max(peak, abs(buf[i]))
                    level = max(peak, level * 0.8f)
                    synchronized(chunks) { chunks.add(buf.copyOf(n)); total += n }
                    seconds = total.toFloat() / sampleRate
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                recording = false
            }
        }, "SpectraSeqMic").apply { start() }
        return true
    }

    /** Stops and returns the trimmed, normalised take (or null if it was silent). */
    fun stop(): FloatArray? {
        recording = false
        thread?.join(1000)
        thread = null
        level = 0f
        val all = synchronized(chunks) {
            val out = FloatArray(total)
            var o = 0
            for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
            chunks.clear(); total = 0
            out
        }
        return process(all, sampleRate)
    }

    companion object {
        /** Trims silence at both ends, normalises to -1 dBFS and adds short fades. */
        fun process(data: FloatArray, sampleRate: Int, threshold: Float = 0.02f): FloatArray? {
            if (data.isEmpty()) return null
            var peak = 0f
            for (v in data) peak = max(peak, abs(v))
            if (peak < 0.003f) return null
            val gate = max(threshold * peak, 0.002f)
            var start = data.indexOfFirst { abs(it) > gate }
            var end = data.indexOfLast { abs(it) > gate }
            if (start < 0 || end <= start) return null
            start = max(0, start - sampleRate / 200) // keep 5 ms of pre-roll for the transient
            end = minOf(data.size - 1, end + sampleRate / 10)
            val out = data.copyOfRange(start, end + 1)
            val gain = 0.89f / peak
            val fadeIn = minOf(out.size / 4, sampleRate / 1000)
            val fadeOut = minOf(out.size / 4, sampleRate / 50)
            for (i in out.indices) {
                var g = gain
                if (i < fadeIn) g *= i.toFloat() / fadeIn
                val fromEnd = out.size - 1 - i
                if (fromEnd < fadeOut) g *= fromEnd.toFloat() / fadeOut
                out[i] *= g
            }
            return out
        }
    }
}
