// SPDX-License-Identifier: GPL-3.0-only
package com.notesorcery.fm1link

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A zone of a user sample slot: mono samples at [SampleSlot.RATE], played from [root], over the keys lo..hi. */
class SlotZone(val samples: ShortArray, val root: Int, val lo: Int? = null, val hi: Int? = null)

/**
 * A user sample slot (USR1..USR4) as the FM-1 stores it (firmware eng_sample.c; tools/sampleio.py user_slot, the same
 * bytes): a 480-byte header (magic "FSMP", zones) and the IMA-ADPCM data, 22050 Hz mono, 64 KiB in all. Uploaded with
 * SMP_BEGIN / SMP_WRITE / SMP_END ([Fm1Client.uploadSample]). A SoundFont preset goes in the same way: its samples
 * as zones (one per key range), resampled to the slot's rate ([resample]).
 */
class SampleSlot(val header: ByteArray, val data: ByteArray) {
    companion object {
        const val SIZE = 64 * 1024
        const val DATA_OFF = 512
        const val RATE = 22050
        const val ZONES = 16
        const val HDR_LEN = 32 + ZONES * 28
        const val MAX_DATA = SIZE - DATA_OFF
        private const val MAGIC = 0x504D5346              // "FSMP"

        private val STEP = intArrayOf(7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50,
            55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449,
            494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749,
            3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767)
        private val IDX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

        /** IMA ADPCM, 4 bit, low nibble first, from predictor 0 / index 0 (sampleio.py ima_encode). */
        fun imaEncode(x: ShortArray): ByteArray {
            var pred = 0
            var idx = 0
            val out = ByteArray((x.size + 1) / 2)
            for (n in x.indices) {
                val step = STEP[idx]
                var diff = x[n] - pred
                var code = 0
                if (diff < 0) { code = 8; diff = -diff }
                var vd = step shr 3
                if (diff >= step) { code = code or 4; diff -= step; vd += step }
                if (diff >= step shr 1) { code = code or 2; diff -= step shr 1; vd += step shr 1 }
                if (diff >= step shr 2) { code = code or 1; vd += step shr 2 }
                pred = (if (code and 8 != 0) pred - vd else pred + vd).coerceIn(-32768, 32767)
                idx = (idx + IDX[code and 7]).coerceIn(0, 88)
                out[n shr 1] = (out[n shr 1].toInt() or (code shl (4 * (n and 1)))).toByte()
            }
            return out
        }

        /** Sorted roots -> key ranges, each halfway to its neighbours (sampleio.py key_split). */
        fun keySplit(roots: List<Int>): List<Pair<Int, Int>> = roots.indices.map { j ->
            val lo = if (j == 0) 0 else (roots[j - 1] + roots[j]) / 2 + 1
            val hi = if (j == roots.size - 1) 127 else (roots[j] + roots[j + 1]) / 2
            lo to hi
        }

        /** float or int16 PCM at [from] Hz -> [to] Hz, linear (enough for a slot: the FM-1 plays it at 22 kHz). */
        fun resample(x: FloatArray, from: Int, to: Int = RATE): FloatArray {
            if (from == to || x.isEmpty()) return x.copyOf()
            val n = maxOf(1, (x.size.toLong() * to / from).toInt())
            return FloatArray(n) { i ->
                val p = i.toDouble() * from / to
                val k = p.toInt()
                val f = (p - k).toFloat()
                val a = x[minOf(k, x.size - 1)]
                val b = x[minOf(k + 1, x.size - 1)]
                a + (b - a) * f
            }
        }

        /** mono float -> int16, peak normalised to 30000 (sampleio.py to_int16). */
        fun toInt16(x: FloatArray): ShortArray {
            var pk = 1e-9f
            for (v in x) pk = maxOf(pk, Math.abs(v))
            return ShortArray(x.size) { (x[it] / pk * 30000f).toInt().coerceIn(-32768, 32767).toShort() }
        }

        /** zones -> the slot (header, ADPCM). Zones without lo/hi split the keyboard between their roots. */
        fun build(name: String, zones: List<SlotZone>): SampleSlot {
            require(zones.size in 1..ZONES) { "1..$ZONES zones per slot" }
            class Z(val off: Int, val n: Int, val root: Int, var lo: Int?, var hi: Int?)
            val data = java.io.ByteArrayOutputStream()
            val zs = zones.map { z ->
                val adp = imaEncode(z.samples)
                val zz = Z(data.size(), z.samples.size, z.root.coerceIn(0, 127), z.lo, z.hi)
                data.write(adp)
                zz
            }.sortedBy { it.root }
            require(data.size() <= MAX_DATA) {
                "too long: ${data.size()} B of ADPCM, a slot holds $MAX_DATA B (${"%.1f".format(MAX_DATA * 2.0 / RATE)} s in all)"
            }
            val split = keySplit(zs.map { it.root })
            zs.forEachIndexed { j, z -> if (z.lo == null) { z.lo = split[j].first; z.hi = split[j].second } }
            val bytes = data.toByteArray()
            val h = ByteBuffer.allocate(HDR_LEN).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(MAGIC).putShort(1).put(zs.size.toByte()).put(0)
            val nm = name.uppercase().filter { it in ' '..'~' }.take(8).toByteArray(Charsets.US_ASCII)
            h.put(nm.copyOf(8))
            h.putInt(bytes.size).putInt(Codec.crc32(bytes).toInt()).put(ByteArray(8))
            val rate = Math.round(RATE / 44100.0 * 65536).toInt()
            for (z in zs) {
                h.putInt(z.off).putInt(z.n).putInt(0).putInt(z.n - 1).putInt(rate)
                h.putShort((z.root * 16).toShort()).putShort(0)       // (the predictor at the loop start: 0, as 0 is)
                h.put(0).put(z.lo!!.toByte()).put(z.hi!!.toByte()).put(0)
            }
            return SampleSlot(h.array(), bytes)
        }
    }

    val zoneCount: Int get() = header[6].toInt() and 0xFF
}
