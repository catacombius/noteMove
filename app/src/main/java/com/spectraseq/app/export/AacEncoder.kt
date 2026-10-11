package com.spectraseq.app.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/** Encodes stereo float audio to AAC-LC in an .m4a file with Android's built-in encoder. */
object AacEncoder {
    fun encode(left: FloatArray, right: FloatArray, sampleRate: Int, out: File, bitrate: Int = 256_000, onProgress: (Float) -> Unit = {}) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxing = false
        val info = MediaCodec.BufferInfo()
        val frames = left.size
        var pos = 0
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.clear()
                        val n = minOf((buf.capacity() / 4), frames - pos) // 2 channels × 2 bytes
                        val sb = buf.asShortBuffer()
                        for (i in 0 until n) {
                            sb.put((left[pos + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                            sb.put((right[pos + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        }
                        val ptsUs = pos * 1_000_000L / sampleRate
                        pos += n
                        if (pos >= frames) {
                            codec.queueInputBuffer(inIdx, 0, n * 4, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else codec.queueInputBuffer(inIdx, 0, n * 4, ptsUs, 0)
                        onProgress(pos.toFloat() / frames)
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start(); muxing = true
                    }
                    outIdx >= 0 -> {
                        val data = codec.getOutputBuffer(outIdx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxing) {
                            data.position(info.offset); data.limit(info.offset + info.size)
                            muxer.writeSampleData(track, data, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (muxing) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
