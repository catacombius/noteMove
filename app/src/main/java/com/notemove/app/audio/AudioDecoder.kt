package com.notemove.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.notemove.core.export.Wav
import java.nio.ByteOrder

/** Decodes any audio file Android can read (wav, mp3, m4a, flac, ogg…) into mono float at [targetRate]. */
object AudioDecoder {
    private const val MAX_SECONDS = 60

    fun decode(context: Context, uri: Uri, targetRate: Int): FloatArray {
        // Fast path for WAV.
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val d = Wav.readMono(input)
                return resample(d.mono, d.sampleRate, targetRate)
            }
        }
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in file")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val out = FloatList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone && out.size < MAX_SECONDS * rate) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var s = 0f
                                repeat(channels) { s += fb.get() }
                                out.add(s / channels)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var s = 0f
                                repeat(channels) { s += sb.get() / 32768f }
                                out.add(s / channels)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        return resample(out.toArray(), rate, targetRate)
    }

    fun resample(data: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || data.isEmpty()) return data
        val ratio = from.toDouble() / to
        val n = (data.size / ratio).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val pos = i * ratio
            val idx = pos.toInt().coerceAtMost(data.size - 1)
            val next = (idx + 1).coerceAtMost(data.size - 1)
            val frac = (pos - idx).toFloat()
            out[i] = data[idx] + (data[next] - data[idx]) * frac
        }
        return out
    }

    private class FloatList {
        private var arr = FloatArray(1 shl 16)
        var size = 0
            private set
        fun add(v: Float) {
            if (size == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[size++] = v
        }
        fun toArray() = arr.copyOf(size)
    }
}
