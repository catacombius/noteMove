// SPDX-License-Identifier: GPL-3.0-only
package com.notesorcery.fm1link

/**
 * The byte encodings of the FM-1's editor protocol (docs/NSX_PROTOCOL.md, web/EDITOR_PROTOCOL.md): every SysEx data
 * byte is 7 bit. A frame is `F0 7D 46 4C cmd args… F7`.
 */
object Codec {
    /** 7D 46 4C: the non-commercial ID, then "FL" (Felucca, which NoteSorcery is built on). */
    val HEADER = byteArrayOf(0x7D, 0x46, 0x4C)

    /** A request frame for [cmd] with its 7-bit [args]. */
    fun frame(cmd: Int, args: IntArray = IntArray(0)): ByteArray {
        val out = ByteArray(args.size + 6)
        out[0] = 0xF0.toByte()
        HEADER.copyInto(out, 1)
        out[4] = (cmd and 0x7F).toByte()
        for (i in args.indices) out[5 + i] = (args[i] and 0x7F).toByte()
        out[out.size - 1] = 0xF7.toByte()
        return out
    }

    /** A complete frame (F0 … F7) -> its command and data, or null when it is not an FM-1 editor frame. */
    fun parse(frame: ByteArray): Pair<Int, IntArray>? {
        if (frame.size < 6 || frame[0] != 0xF0.toByte() || frame[frame.size - 1] != 0xF7.toByte()) return null
        for (i in 0 until 3) if (frame[1 + i] != HEADER[i]) return null
        val cmd = frame[4].toInt() and 0x7F
        return cmd to IntArray(frame.size - 6) { frame[5 + it].toInt() and 0x7F }
    }

    /** v14: value + 8192 in two 7-bit bytes, LSB first (-8192..8191). */
    fun v14(v: Int): IntArray {
        val u = v.coerceIn(-8192, 8191) + 8192
        return intArrayOf(u and 0x7F, (u shr 7) and 0x7F)
    }

    fun readV14(a: IntArray, at: Int): Int = (a[at] or (a[at + 1] shl 7)) - 8192

    /** n 7-bit groups, LSB first (u21: 3, u35: 5). */
    fun uN(v: Long, n: Int): IntArray = IntArray(n) { ((v shr (7 * it)) and 0x7F).toInt() }

    fun readUN(a: IntArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((a[at + i].toLong() and 0x7F) shl (7 * i))
        return v
    }

    /** pack7 as the editor protocol has it (editor.c ed_pack7): groups of up to 7 bytes, each after a byte of their
     *  top bits (bit j = byte j's bit 7). Not the OTA updater's bit stream. */
    fun pack7(data: ByteArray, from: Int = 0, to: Int = data.size): IntArray {
        val out = ArrayList<Int>((to - from) * 8 / 7 + 2)
        var i = from
        while (i < to) {
            val k = minOf(7, to - i)
            var m = 0
            for (j in 0 until k) m = m or (((data[i + j].toInt() shr 7) and 1) shl j)
            out.add(m)
            for (j in 0 until k) out.add(data[i + j].toInt() and 0x7F)
            i += k
        }
        return out.toIntArray()
    }

    /** pack7 -> bytes (editor.c ed_unpack7). */
    fun unpack7(a: IntArray, from: Int = 0, to: Int = a.size): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = from
        while (i < to) {
            val m = a[i++]
            var j = 0
            while (j < 7 && i < to) {
                out.write((a[i++] and 0x7F) or (((m shr j) and 1) shl 7))
                j++
            }
        }
        return out.toByteArray()
    }

    /** A 0-terminated ASCII string at [at] -> (string, index after its 0). */
    fun readString(a: IntArray, at: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = at
        while (i < a.size && a[i] != 0) sb.append(a[i++].toChar())
        return sb.toString() to minOf(a.size, i + 1)
    }

    /** CRC-32 (zlib), as BK_LIST and the sample slots have it. */
    fun crc32(b: ByteArray): Long = java.util.zip.CRC32().also { it.update(b) }.value
}

/**
 * Splits a raw MIDI byte stream (Android's MidiReceiver.onSend gives arbitrary chunks) into complete SysEx frames
 * and everything else. Realtime bytes (F8..FF) may arrive inside a SysEx and are passed on at once; any other
 * status byte ends a SysEx that was not ended (it is dropped). Frames longer than [maxFrame] are dropped.
 * Not thread safe: one per input port.
 */
class SysExAssembler(
    private val onFrame: (ByteArray) -> Unit,
    private val onOther: (Int) -> Unit = {},
    private val maxFrame: Int = 4096,
) {
    private val buf = java.io.ByteArrayOutputStream()
    private var inSysex = false
    private var tooLong = false

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        for (i in offset until offset + count) feedByte(bytes[i].toInt() and 0xFF)
    }

    fun feedByte(b: Int) {
        if (b >= 0xF8) { onOther(b); return }               // realtime: anywhere, never part of a frame
        if (b == 0xF0) {
            buf.reset(); buf.write(b); inSysex = true; tooLong = false
            return
        }
        if (inSysex) {
            if (b == 0xF7) {
                buf.write(b)
                inSysex = false
                if (!tooLong) onFrame(buf.toByteArray())
                buf.reset()
                return
            }
            if (b and 0x80 == 0) {
                if (buf.size() >= maxFrame) tooLong = true else buf.write(b)
                return
            }
            inSysex = false                                // another status: the SysEx was cut off
            buf.reset()
        }
        onOther(b)
    }
}
