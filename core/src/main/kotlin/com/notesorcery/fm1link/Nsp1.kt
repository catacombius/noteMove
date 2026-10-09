// SPDX-License-Identifier: GPL-3.0-only
package com.notesorcery.fm1link

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A note in beats from the clip's start (velocity 1..127; pitch: MIDI, C4 = 60). */
data class Fm1Note(val pitch: Int, val start: Double, val duration: Double, val velocity: Int)

/** One track's pattern as a loop of [lengthBeats]. */
data class Fm1Clip(val name: String, val lengthBeats: Double, val notes: List<Fm1Note>)

/** A track of the FM-1: 1..6 synths, 7..8 drum machines. [volume] is linear gain (1.0 = 0 dB), [pan] -1..1. */
data class Fm1Track(
    val index: Int,
    val name: String,
    val isDrum: Boolean,
    val engine: String,
    val volume: Double,
    val pan: Double,
    val mute: Boolean,
    /** scene index -> clip (a scene without notes on this track has none) */
    val clips: Map<Int, Fm1Clip>,
)

/** The FM-1's song: its sections as scenes (A..D), eight tracks. The shape of NoteMove's Project / an .als set. */
data class Fm1Song(
    val name: String,
    val tempo: Double,
    val swing: Int,
    val rootNote: Int,
    /** Ableton Live's name of the scale ("Minor", "Dorian" …) */
    val scaleName: String,
    val sceneNames: List<String>,
    val tracks: List<Fm1Track>,
) {
    val sceneCount: Int get() = sceneNames.size
    fun noteCount(): Int = tracks.sumOf { t -> t.clips.values.sumOf { it.notes.size } }
}

/** A decoded NSP1 project (firmware/src/project.c project_t). */
class Nsp1Project(val globals: ShortArray, val selected: Int, val tracks: List<Nsp1Track>)

class Nsp1Track(
    val index: Int,
    val p: ShortArray,
    val engine: Int,
    val preset: Int,
    /** NSTEP steps of 10 bytes: synth note[4], n, time, flags, vel, lvl, rat; drum on[2], lvl[4], rat[4] */
    val steps: Array<ByteArray>,
    val micro: ByteArray,
    val fill: ByteArray,
    val isDrum: Boolean,
)

/**
 * NoteSorcery's project format and how its steps play (firmware seq.c), as web/als/nsals.js has it: both turn a
 * project into the same notes (tests: Nsp1Test against the firmware's own fixture and nsals.js's output).
 */
object Nsp1 {
    /** core.h / project.c; tests/nsp1_export.c prints the firmware's and Nsp1Test compares. */
    object Layout {
        const val MAGIC = 0x3150534E          // "NSP1"
        const val SIZE = 7604
        const val P_COUNT = 61
        const val G_COUNT = 34
        const val NTRK = 8
        const val NPART = 6
        const val NSTEP = 64
        const val NLOCK = 24
        const val P_LEVEL = 0
        const val P_ROOT = 25
        const val P_SCALE = 26
        const val P_SLEN = 29
        const val P_SDIV = 30
        const val P_SSWING = 31
        const val P_SGATE = 32
        const val P_PAN = 39
        const val P_MUTE = 40
        const val G_BPM = 0
        const val G_SWING = 1
        val ENGINES = listOf("ANALOG", "TRIO", "FM6", "SAMPLE", "ACID", "WAVE")
    }

    /** the FM-1's scales (params.c N_SCALE) as Live names them */
    val LIVE_SCALE = listOf("Chromatic", "Major", "Minor", "Dorian", "Mixolydian", "Major Pentatonic", "Minor Pentatonic",
        "Harmonic Minor", "Phrygian", "Lydian", "Locrian", "Melodic Minor", "Minor Blues", "Whole Tone", "Half-whole Dim.",
        "Whole-half Dim.")

    /** the drum lanes' GM notes (drums.c LANE_NOTE): what the FM-1 plays and sends */
    val LANE_NOTE = intArrayOf(36, 35, 38, 39, 42, 46, 44, 37, 40, 43, 48, 49, 51, 70, 63, 56)
    val LANE_NAME = listOf("KICK", "KICK 2", "SNARE", "CLAP", "CL HAT", "OP HAT", "PEDAL HAT", "RIM", "SNARE 2",
        "TOM LO", "TOM HI", "CRASH", "RIDE", "SHAKER", "CONGA", "COWBELL")

    private val DIV_DEN = intArrayOf(1, 2, 4, 8, 3, 6)
    private val DIV_BEATS = intArrayOf(2, 4, 8)
    private val LV_VEL = intArrayOf(0, 42, 72, 127)
    private const val ST_NOTE = 0
    private const val ST_TIE = 1
    private const val SF_ACCENT = 1
    private const val SF_SLIDE = 2
    private const val FC_FILL = 1

    class FormatException(msg: String) : Exception(msg)

    fun fnv1a(b: ByteArray, n: Int): Int {
        var s = 0x811C9DC5.toInt()
        for (i in 0 until n) s = (s xor (b[i].toInt() and 0xFF)) * 16777619
        return s
    }

    fun decode(bytes: ByteArray): Nsp1Project {
        val L = Layout
        if (bytes.size != L.SIZE) throw FormatException("not an NSP1 project (${bytes.size} bytes, expected ${L.SIZE})")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != L.MAGIC || bb.getInt(4) != L.SIZE) throw FormatException("not an NSP1 project (magic / size)")
        if (bb.getInt(L.SIZE - 4) != fnv1a(bytes, L.SIZE - 4)) throw FormatException("NSP1 project damaged (checksum)")
        var o = 8
        val g = ShortArray(L.G_COUNT) { bb.getShort(o + 2 * it) }
        o += 2 * L.G_COUNT
        val sel = bytes[o].toInt() and 0xFF
        val ntrk = bytes[o + 2].toInt() and 0xFF
        o += 4
        if (ntrk != L.NTRK) throw FormatException("NSP1 with $ntrk tracks (expected ${L.NTRK})")
        val tracks = ArrayList<Nsp1Track>(L.NTRK)
        for (t in 0 until L.NTRK) {
            val p = ShortArray(L.P_COUNT) { bb.getShort(o + 2 * it) }
            o += 2 * L.P_COUNT
            val engine = bytes[o].toInt() and 0xFF
            val preset = bytes[o + 1].toInt() and 0xFF
            o += 2
            val steps = Array(L.NSTEP) { bytes.copyOfRange(o + 10 * it, o + 10 * it + 10) }
            o += 10 * L.NSTEP
            val micro = bytes.copyOfRange(o, o + L.NSTEP)
            o += L.NSTEP + 4 * L.NLOCK                     // (the parameter locks: not notes)
            val fill = bytes.copyOfRange(o, o + L.NSTEP / 4)
            o += L.NSTEP / 4
            tracks.add(Nsp1Track(t, p, engine, preset, steps, micro, fill, t >= L.NPART))
        }
        if (o != L.SIZE - 4) throw FormatException("NSP1 layout mismatch")
        return Nsp1Project(g, sel, tracks)
    }

    private fun stepBeats(div: Int): Double {
        val d = Math.floorMod(div, 9)
        return if (d < 6) 1.0 / DIV_DEN[d] else DIV_BEATS[d - 6].toDouble()
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    /**
     * One track of [prj] as a loop: the notes the FM-1 plays (its DIV, LEN, GATE, swing, nudges, ties, slides,
     * ratchets, dynamics; fill-only steps left out). Drum lanes: [gmDrums] their GM notes, else Drum Rack pads
     * (lane k = note 36 + k).
     */
    fun trackClip(prj: Nsp1Project, ti: Int, gmDrums: Boolean = false, name: String = ""): Fm1Clip {
        val L = Layout
        val t = prj.tracks[ti]
        val len = (t.p[L.P_SLEN].toInt()).coerceIn(1, L.NSTEP)
        val div = t.p[L.P_SDIV].toInt()
        val sb = stepBeats(div)
        val swingPct = (t.p[L.P_SSWING] + prj.globals[L.G_SWING]).coerceIn(0, 100)
        val sw = if (Math.floorMod(div, 9) < 4) swingPct * sb / 200.0 else 0.0
        val gatePct = maxOf(1, t.p[L.P_SGATE].toInt())
        fun at(i: Int) = i * sb + (if (i and 1 != 0) sw else 0.0) + t.micro[i] / 64.0 * sb
        fun slen(i: Int) = sb + (if (i and 1 != 0) -sw else sw)
        fun gateOf(i: Int) = slen(i) * gatePct / 128.0
        fun fillOnly(i: Int) = ((t.fill[i shr 2].toInt() shr (2 * (i and 3))) and 3) == FC_FILL
        val lengthBeats = len * sb
        val notes = ArrayList<Fm1Note>()
        fun push(pitch: Int, start0: Double, dur: Double, vel: Int) {
            val start = maxOf(0.0, start0)
            if (start >= lengthBeats) return
            notes.add(Fm1Note(pitch, round6(start), round6(maxOf(0.001, dur)), vel.coerceIn(1, 127)))
        }
        var i = 0
        while (i < len) {
            val r = t.steps[i]
            fun u(k: Int) = r[k].toInt() and 0xFF
            if (fillOnly(i)) { i++; continue }
            if (t.isDrum) {
                val on = u(0) or (u(1) shl 8)
                for (l in 0 until 16) {
                    if ((on shr l) and 1 == 0) continue
                    val lvl = (u(2 + (l shr 2)) shr ((l and 3) * 2)) and 3
                    val hits = 1 + ((u(6 + (l shr 2)) shr ((l and 3) * 2)) and 3)
                    val pitch = if (gmDrums) LANE_NOTE[l] else 36 + l
                    val vel = if (lvl != 0) LV_VEL[lvl] else 100
                    for (h in 0 until hits) push(pitch, at(i) + h * slen(i) / hits, slen(i) / hits, vel)
                }
                i++
                continue
            }
            val n = u(4)
            val time = u(5)
            val flags = u(6)
            if (time != ST_NOTE || n == 0) { i++; continue }
            var ties = 0
            while (i + ties + 1 < len && (t.steps[i + ties + 1][5].toInt() and 0xFF) == ST_TIE) ties++
            val lastFlags = t.steps[i + ties][6].toInt() and 0xFF
            val slide = (lastFlags and SF_SLIDE) != 0 && i + ties + 1 < len
            val held = at(i + ties) - at(i)
            for (k in 0 until minOf(n, 4)) {
                val base = if (flags and SF_ACCENT != 0) 127 else if (u(7) != 0) u(7) else 96
                val lv = (u(8) shr (2 * k)) and 3
                val vel = if (lv != 0) LV_VEL[lv] else base
                val hits = if (ties > 0) 1 else 1 + ((u(9) shr (2 * k)) and 3)
                val dur = when {
                    slide -> held + slen(i + ties) + sb / 16
                    ties > 0 -> held + minOf(gateOf(i + ties) + slen(i + ties) / 2, slen(i + ties))
                    else -> gateOf(i) / hits
                }
                for (h in 0 until hits) push(u(k), at(i) + h * slen(i) / hits, dur, vel)
            }
            i += ties + 1
        }
        notes.sortWith(compareBy({ it.start }, { it.pitch }))
        return Fm1Clip(name, lengthBeats, notes)
    }

    private fun levelToGain(lvl: Int): Double =
        if (lvl <= 0) 0.0003162278 else Math.pow(10.0, (lvl - 104) / 40.0).coerceIn(0.0003162278, 1.99526)

    /**
     * The FM-1's song from its projects: the sections A..D (null = empty) as scenes; without any, the working
     * project as scene A ([withWorking]: also with them, first, as "LIVE").
     */
    fun song(working: ByteArray?, sections: List<ByteArray?>, name: String = "FM-1 sketch", gmDrums: Boolean = false,
             withWorking: Boolean = false): Fm1Song {
        val L = Layout
        val scenes = ArrayList<Pair<String, Nsp1Project>>()
        sections.forEachIndexed { k, b -> if (b != null && b.isNotEmpty()) scenes.add(("ABCD".getOrNull(k)?.toString() ?: "S${k + 1}") to decode(b)) }
        if (scenes.isEmpty() || withWorking) {
            val w = working ?: throw FormatException("no project to read")
            scenes.add(0, (if (scenes.isEmpty()) "A" else "LIVE") to decode(w))
        }
        val head = scenes[0].second
        val key = head.tracks.firstOrNull { it.index < L.NPART && it.p[L.P_SCALE].toInt() != 0 } ?: head.tracks[0]
        val tracks = (0 until L.NTRK).map { ti ->
            val t0 = head.tracks[ti]
            val engine = if (t0.isDrum) "DRUMS" else L.ENGINES.getOrElse(t0.engine) { "SYNTH" }
            val tname = if (t0.isDrum) "DRUMS ${ti - L.NPART + 1}" else "T${ti + 1} $engine"
            val clips = LinkedHashMap<Int, Fm1Clip>()
            scenes.forEachIndexed { s, (sn, prj) ->
                val c = trackClip(prj, ti, gmDrums, "$sn $tname")
                if (c.notes.isNotEmpty()) clips[s] = c
            }
            Fm1Track(ti, tname, t0.isDrum, engine, levelToGain(t0.p[L.P_LEVEL].toInt()),
                (t0.p[L.P_PAN] / 64.0).coerceIn(-1.0, 1.0), t0.p[L.P_MUTE].toInt() != 0, clips)
        }
        val bpm = head.globals[L.G_BPM].toDouble()
        return Fm1Song(name, if (bpm <= 0) 120.0 else bpm.coerceIn(20.0, 999.0),
            head.globals[L.G_SWING].toInt(), Math.floorMod(key.p[L.P_ROOT].toInt(), 12),
            LIVE_SCALE.getOrElse(key.p[L.P_SCALE].toInt()) { "Minor" }, scenes.map { it.first }, tracks)
    }
}
