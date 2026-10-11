package com.spectraseq.core.export

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16/24-bit PCM WAV reader & writer. */
object Wav {
    /** Writes interleaved stereo (or mono when [right] is null) 24-bit PCM. */
    fun write(out: OutputStream, sampleRate: Int, left: FloatArray, right: FloatArray?, bits: Int = 24) {
        val channels = if (right == null) 1 else 2
        val frames = left.size
        val bytesPerSample = bits / 8
        val dataSize = frames * channels * bytesPerSample
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * bytesPerSample)
            .putShort((channels * bytesPerSample).toShort()).putShort(bits.toShort())
        header.put("data".toByteArray()).putInt(dataSize)
        out.write(header.array())
        val buf = ByteArray(4096 * channels * bytesPerSample)
        var pos = 0
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val v = (if (c == 0) left[i] else right!![i]).coerceIn(-1f, 1f)
                if (bits == 16) {
                    val s = (v * 32767f).toInt()
                    buf[pos++] = s.toByte(); buf[pos++] = (s shr 8).toByte()
                } else {
                    val s = (v * 8388607f).toInt()
                    buf[pos++] = s.toByte(); buf[pos++] = (s shr 8).toByte(); buf[pos++] = (s shr 16).toByte()
                }
            }
            if (pos >= buf.size - channels * bytesPerSample) { out.write(buf, 0, pos); pos = 0 }
        }
        if (pos > 0) out.write(buf, 0, pos)
        out.flush()
    }

    fun bytes(sampleRate: Int, left: FloatArray, right: FloatArray?, bits: Int = 24): ByteArray =
        ByteArrayOutputStream().also { write(it, sampleRate, left, right, bits) }.toByteArray()

    class Decoded(val sampleRate: Int, val mono: FloatArray)

    /** Reads PCM 8/16/24/32-bit or 32-bit float WAV files, mixing to mono. */
    fun readMono(input: InputStream): Decoded {
        val all = DataInputStream(input).readBytes()
        val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
        require(all.size >= 12 && String(all, 0, 4) == "RIFF" && String(all, 8, 4) == "WAVE") { "Not a WAV file" }
        var pos = 12
        var format = 1; var channels = 1; var rate = 44100; var bits = 16
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= all.size) {
            val id = String(all, pos, 4)
            val len = bb.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = bb.getShort(body + 2).toInt()
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                    if (format == 0xFFFE && len >= 26) format = bb.getShort(body + 24).toInt() and 0xFFFF
                }
                "data" -> { dataOff = body; dataLen = minOf(len, all.size - body) }
            }
            pos = body + len + (len and 1)
        }
        require(dataOff >= 0) { "WAV has no data chunk" }
        val bps = bits / 8
        val frames = dataLen / (bps * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val o = dataOff + (f * channels + c) * bps
                sum += when {
                    format == 3 && bits == 32 -> bb.getFloat(o)
                    bits == 8 -> ((all[o].toInt() and 0xFF) - 128) / 128f
                    bits == 16 -> bb.getShort(o) / 32768f
                    bits == 24 -> ((all[o].toInt() and 0xFF) or ((all[o + 1].toInt() and 0xFF) shl 8) or (all[o + 2].toInt() shl 16)) / 8388608f
                    bits == 32 -> bb.getInt(o) / 2147483648f
                    else -> 0f
                }
            }
            out[f] = sum / channels
        }
        return Decoded(rate, out)
    }
}
