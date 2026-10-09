// SPDX-License-Identifier: GPL-3.0-only
package com.notesorcery.fm1link

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Command numbers (web/EDITOR_PROTOCOL.md, docs/NSX_PROTOCOL.md). */
object Cmd {
    const val INFO = 1
    const val SMP_BEGIN = 11
    const val SMP_WRITE = 12
    const val SMP_END = 13
    const val SMP_ERASE = 14
    const val SMP_INFO = 15
    const val PING = 25
    const val BK_LIST = 34
    const val BK_GET = 35
    const val BK_PUT = 36
    const val NSX_CAPS = 80
    const val NSX_TRANSPORT = 81
    const val NSX_TEMPO = 82
}

data class Fm1Info(val version: String, val engines: List<String>, val tracks: Int, val proto: Int)

/** NSX_CAPS: what this FM-1 has. Null from [Fm1Client.caps] when the device is not NoteSorcery. */
data class Fm1Caps(
    val nsxVersion: Int, val firmware: String, val tracks: Int, val synthTracks: Int, val drumTracks: Int,
    val projectSize: Int, val features: Int, val drumChannel: Int, val sampleSlots: Int, val slotKiB: Int,
    val waveSlots: Int, val drumKits: Int, val kit808cm: Int, val kit909cm: Int,
) {
    val clockOut get() = features and 1 != 0
    val songPositionIn get() = features and 2 != 0
    val usbAudioIn get() = features and 4 != 0
    val usbAudioOut get() = features and 8 != 0
}

data class Fm1Transport(val playing: Boolean, val bpm: Int, val beat: Long, val midiOutBits: Int, val sync: Int)

class Fm1Exception(msg: String) : Exception(msg)

/**
 * A NoteSorcery FM-1 over any MIDI transport: [send] writes bytes to its MIDI input (Android: MidiInputPort.send);
 * feed every byte from its output to [feed] (Android: a MidiReceiver), which takes the SysEx replies and hands the
 * rest (notes, clock) to [onMidi]. Requests block the calling thread until the reply (call them off the UI thread);
 * one request at a time, as the device answers in order.
 */
class Fm1Client(
    private val send: (ByteArray) -> Unit,
    private val onMidi: (Int) -> Unit = {},
    private val timeoutMs: Long = 1500,
) {
    private val replies = LinkedBlockingQueue<Pair<Int, IntArray>>()
    private val assembler = SysExAssembler({ f -> Codec.parse(f)?.let { replies.offer(it) } }, onMidi)
    private val lock = Any()

    /** Bytes from the FM-1 (any chunks, any thread: serialised here). */
    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        synchronized(assembler) { assembler.feed(bytes, offset, count) }
    }

    /** One request and its reply's data; retried [retries] times on silence. */
    fun request(cmd: Int, args: IntArray = IntArray(0), timeout: Long = timeoutMs, retries: Int = 1): IntArray = synchronized(lock) {
        var left = retries
        while (true) {
            replies.clear()
            send(Codec.frame(cmd, args))
            val deadline = System.nanoTime() + timeout * 1_000_000
            while (true) {
                val wait = deadline - System.nanoTime()
                if (wait <= 0) break
                val r = replies.poll(wait, TimeUnit.NANOSECONDS) ?: break
                if (r.first == cmd) return r.second
            }
            if (left-- <= 0) throw Fm1Exception("no reply from the FM-1 (command $cmd)")
        }
        @Suppress("UNREACHABLE_CODE") IntArray(0)
    }

    fun info(): Fm1Info {
        val a = request(Cmd.INFO)
        var (version, i) = Codec.readString(a, 0)
        val nEngines = a[i]
        i += 4                                              // NENGINES, P_COUNT, G_COUNT, NSTEP
        i += 1                                              // P_E0
        val engines = ArrayList<String>()
        repeat(nEngines) { val (s, j) = Codec.readString(a, i); engines.add(s); i = j }
        val tracks = a.getOrElse(i) { 4 }
        val proto = a.getOrElse(i + 1) { 0 }
        return Fm1Info(version, engines, tracks, proto)
    }

    /** NSX_CAPS, or null for an FM-1 that is not NoteSorcery (SLOOP, Felucca: no reply). */
    fun caps(): Fm1Caps? {
        val a = try { request(Cmd.NSX_CAPS, timeout = 600, retries = 0) } catch (e: Fm1Exception) { return null }
        val (tag, i0) = Codec.readString(a, 0)
        if (tag != "NSX") return null
        var i = i0
        val ver = a[i++]
        val (fw, i1) = Codec.readString(a, i)
        i = i1
        val ntrk = a[i++]; val npart = a[i++]; val ndr = a[i++]
        val size = Codec.readUN(a, i, 3).toInt(); i += 3
        i++                                                 // "N"
        val flags = a[i++]; val drch = a[i++]; val slots = a[i++]; val kib = a[i++]; val waves = a[i++]
        val kits = a[i++]; val cm8 = a[i++]; val cm9 = a[i]
        return Fm1Caps(ver, fw, ntrk, npart, ndr, size, flags, drch, slots, kib, waves, kits, cm8, cm9)
    }

    /** NSX_TRANSPORT: 0 query, 1 play, 2 stop, 3 toggle. */
    fun transport(op: Int = 0): Fm1Transport {
        val a = request(Cmd.NSX_TRANSPORT, intArrayOf(op))
        return Fm1Transport(a[0] != 0, Codec.readV14(a, 1), Codec.readUN(a, 3, 3), a[6], a[7])
    }

    fun play() = transport(1)
    fun stop() = transport(2)

    /** NSX_TEMPO: sets (bpm != null) and answers the BPM. */
    fun tempo(bpm: Int? = null): Int = Codec.readV14(request(Cmd.NSX_TEMPO, bpm?.let { Codec.v14(it) } ?: IntArray(0)), 0)

    /** BK_LIST: object id -> (length, CRC-32). Takes the device's snapshot that [readObject] reads. */
    fun listObjects(): Map<Int, Pair<Int, Long>> {
        val a = request(Cmd.BK_LIST, timeout = 5000, retries = 0)
        if (a[0] != 0) throw Fm1Exception("backup list: rc ${a[0]} (no flash?)")
        val n = a[1]
        val out = LinkedHashMap<Int, Pair<Int, Long>>()
        var i = 2
        repeat(n) {
            val id = a[i]
            val len = Codec.readUN(a, i + 1, 5).toInt()
            val crc = Codec.readUN(a, i + 6, 5)
            out[id] = len to crc
            i += 11
        }
        return out
    }

    /** One object of the last [listObjects], in 256-byte reads, its CRC checked. */
    fun readObject(id: Int, len: Int, crc: Long, progress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = minOf(256, len - off)
            val a = request(Cmd.BK_GET, intArrayOf(id) + Codec.uN(off.toLong(), 5) + intArrayOf(n and 0x7F, n shr 7), retries = 2)
            if (a[1] != 0) throw Fm1Exception(if (a[1] == 5) "the device's snapshot is gone: list again" else "read of object $id: rc ${a[1]}")
            val d = Codec.unpack7(a, 9)                   // id, rc, offset u35, count (2), pack7
            if (d.size != n) throw Fm1Exception("read of object $id at $off: ${d.size} bytes")
            d.copyInto(out, off)
            off += n
            progress(off, len)
        }
        if (Codec.crc32(out) != crc) throw Fm1Exception("object $id: CRC mismatch")
        return out
    }

    /** The FM-1's song: the working project (object 0) and sections A..D (2..5) -> [Fm1Song]. */
    fun pullSong(name: String = "FM-1 sketch", gmDrums: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): Fm1Song {
        val list = listObjects()
        val want = listOf(0, 2, 3, 4, 5).filter { (list[it]?.first ?: 0) > 0 }
        val total = want.sumOf { list[it]!!.first }
        var done = 0
        val got = HashMap<Int, ByteArray>()
        for (id in want) {
            val (len, crc) = list[id]!!
            got[id] = readObject(id, len, crc) { d, _ -> progress(done + d, total) }
            done += len
        }
        return Nsp1.song(got[0], listOf(got[2], got[3], got[4], got[5]), name, gmDrums)
    }

    /** A sample slot (0..3 = USR1..USR4) filled with [slot]: begin (erases), the data in 256-byte writes, the header. */
    fun uploadSample(index: Int, slot: SampleSlot, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val b = request(Cmd.SMP_BEGIN, intArrayOf(index), timeout = 5000, retries = 0)
        if (b.getOrElse(1) { 1 } != 0) throw Fm1Exception("sample slot ${index + 1}: begin failed (rc ${b.getOrNull(1)})")
        var off = 0
        while (off < slot.data.size) {
            val n = minOf(256, slot.data.size - off)
            val a = SampleSlot.DATA_OFF + off
            val r = request(Cmd.SMP_WRITE, intArrayOf(index) + Codec.uN(a.toLong(), 3) + Codec.pack7(slot.data, off, off + n), retries = 2)
            if (r.getOrElse(4) { 1 } != 0) throw Fm1Exception("sample slot ${index + 1}: write at $a failed (rc ${r.getOrNull(4)})")
            off += n
            progress(off, slot.data.size)
        }
        val e = request(Cmd.SMP_END, intArrayOf(index) + Codec.pack7(slot.header), timeout = 5000, retries = 0)
        if (e.getOrElse(1) { 1 } != 0) throw Fm1Exception("sample slot ${index + 1}: end failed (rc ${e.getOrNull(1)})")
    }
}

/** MIDI realtime / system common bytes to drive an FM-1 (SYNC = USB) or follow one. */
object Fm1Midi {
    const val CLOCK = 0xF8
    const val START = 0xFA
    const val CONTINUE = 0xFB
    const val STOP = 0xFC
    const val PPQN = 24

    /** Song Position Pointer: [sixteenths] since the top (F2 lsb msb). */
    fun songPosition(sixteenths: Int): ByteArray {
        val v = sixteenths.coerceIn(0, 16383)
        return byteArrayOf(0xF2.toByte(), (v and 0x7F).toByte(), (v shr 7).toByte())
    }

    /** the FM-1's MIDI channel (0-based) of track [track] (0..7): synths 0..5, drums the drum channel and the next */
    fun channelOf(track: Int, drumChannel1Based: Int = 10): Int =
        if (track < 6) track else (drumChannel1Based - 1 + (track - 6)) and 15

    /** nanoseconds between clock pulses at [bpm] */
    fun pulseNanos(bpm: Double): Long = (60_000_000_000.0 / (bpm * PPQN)).toLong()
}
