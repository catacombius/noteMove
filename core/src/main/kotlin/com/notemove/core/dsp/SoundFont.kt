package com.notemove.core.dsp

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A parsed SoundFont 2 (.sf2) bank: 16-bit sample pool plus presets resolved down to playable zones
 * (preset generators added to instrument generators, key/velocity ranges intersected).
 */
class SoundFont(val name: String, val samples: ShortArray, val presets: List<Preset>) {

    class Preset(val name: String, val bank: Int, val program: Int, val zones: List<Zone>) {
        val label get() = String.format(java.util.Locale.ROOT, "%03d:%03d %s", bank, program, name)
    }

    /** Everything needed to play one sample for a key/velocity range. Times in seconds, levels linear. */
    class Zone(
        val keyLo: Int, val keyHi: Int, val velLo: Int, val velHi: Int,
        val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int,
        /** 0 = no loop, 1 = loop continuously, 3 = loop until release, then play to the end. */
        val loopMode: Int,
        val sampleRate: Int,
        val rootKey: Int,
        /** Total tuning offset in cents (coarse, fine, sample pitch correction). */
        val tuneCents: Int,
        /** Cents per key (100 = normal). */
        val scaleTuning: Int,
        val gain: Float,
        /** -1..1 */
        val pan: Float,
        val delay: Float, val attack: Float, val hold: Float, val decay: Float, val sustain: Float, val release: Float,
        /** Filter cutoff in Hz (>= 18 kHz means off) and resonance 0..1. */
        val cutoffHz: Float, val resonance: Float,
        val exclusiveClass: Int,
    )

    fun preset(bank: Int, program: Int): Preset? =
        presets.firstOrNull { it.bank == bank && it.program == program } ?: presets.firstOrNull { it.program == program }

    companion object {
        fun parse(input: InputStream, fallbackName: String = "SoundFont"): SoundFont = parse(input.readBytes(), fallbackName)

        fun parse(bytes: ByteArray, fallbackName: String = "SoundFont"): SoundFont {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size > 12 && tag(bytes, 0) == "RIFF" && tag(bytes, 8) == "sfbk") { "Not a SoundFont 2 file" }
            var name = fallbackName
            var smpl: ShortArray? = null
            val pdta = HashMap<String, Pair<Int, Int>>() // chunk -> (offset, size)
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = tag(bytes, pos); val size = bb.getInt(pos + 4); val body = pos + 8
                if (id == "LIST") {
                    val listType = tag(bytes, body)
                    var p = body + 4
                    val end = min(bytes.size, body + size)
                    while (p + 8 <= end) {
                        val cid = tag(bytes, p); val csize = bb.getInt(p + 4); val cbody = p + 8
                        when (listType) {
                            "INFO" -> if (cid == "INAM") name = cString(bytes, cbody, csize).ifBlank { fallbackName }
                            "sdta" -> if (cid == "smpl") {
                                val n = min(csize, bytes.size - cbody) / 2
                                smpl = ShortArray(n).also { arr -> ByteBuffer.wrap(bytes, cbody, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(arr) }
                            }
                            "pdta" -> pdta[cid] = cbody to csize
                        }
                        p = cbody + csize + (csize and 1)
                    }
                }
                pos = body + size + (size and 1)
            }
            val pool = smpl ?: error("SoundFont has no sample data")
            return SoundFont(name, pool, Builder(bb, pdta, pool.size).presets())
        }

        private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.US_ASCII)
        private fun cString(b: ByteArray, o: Int, n: Int): String {
            var e = o
            while (e < o + n && e < b.size && b[e] != 0.toByte()) e++
            return String(b, o, e - o, Charsets.ISO_8859_1).trim()
        }
    }

    private class Builder(val bb: ByteBuffer, val pdta: Map<String, Pair<Int, Int>>, val poolSize: Int) {
        private fun count(id: String, rec: Int) = (pdta[id]?.second ?: 0) / rec
        private fun off(id: String) = pdta[id]?.first ?: error("SoundFont is missing the $id chunk")
        private fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
        private fun s16(o: Int) = bb.getShort(o).toInt()
        private fun u32(o: Int) = bb.getInt(o).toLong() and 0xFFFFFFFFL
        private fun name(o: Int): String {
            val sb = StringBuilder()
            for (i in 0 until 20) { val c = bb.get(o + i).toInt() and 0xFF; if (c == 0) break; sb.append(c.toChar()) }
            return sb.toString().trim()
        }

        class Shdr(val name: String, val start: Int, val end: Int, val loopStart: Int, val loopEnd: Int, val rate: Int, val pitch: Int, val correction: Int, val type: Int)

        /** Generator values for one zone (null = not set). */
        private fun zoneGens(genChunk: String, from: Int, to: Int): Array<Int?> {
            val gens = arrayOfNulls<Int>(61)
            val o = off(genChunk)
            for (i in from until to) {
                val r = o + i * 4
                val oper = u16(r)
                if (oper < gens.size) gens[oper] = if (oper == 43 || oper == 44) u16(r + 2) else s16(r + 2)
            }
            return gens
        }

        private fun bags(bagChunk: String, from: Int, to: Int): List<Pair<Int, Int>> {
            val o = off(bagChunk)
            return (from until to).map { u16(o + it * 4) to u16(o + (it + 1) * 4) }
        }

        fun presets(): List<Preset> {
            val nShdr = count("shdr", 46)
            val shdrO = off("shdr")
            val shdrs = (0 until nShdr - 1).map { i ->
                val r = shdrO + i * 46
                Shdr(name(r), u32(r + 20).toInt(), u32(r + 24).toInt(), u32(r + 28).toInt(), u32(r + 32).toInt(),
                    u32(r + 36).toInt(), bb.get(r + 40).toInt() and 0xFF, bb.get(r + 41).toInt(), u16(r + 44))
            }
            val nInst = count("inst", 22)
            val instO = off("inst")
            // Instrument zones: (gens, sample) with the instrument's global zone merged in.
            val instZones = (0 until nInst - 1).map { i ->
                val b0 = u16(instO + i * 22 + 20); val b1 = u16(instO + (i + 1) * 22 + 20)
                var global: Array<Int?>? = null
                val zones = ArrayList<Array<Int?>>()
                for ((g0, g1) in bags("ibag", b0, b1)) {
                    val g = zoneGens("igen", g0, g1)
                    if (g[53] == null) { if (zones.isEmpty() && global == null) global = g } else zones.add(g)
                }
                zones.map { z -> Array(61) { k -> z[k] ?: global?.get(k) } }
            }
            val nPh = count("phdr", 38)
            val phO = off("phdr")
            val out = ArrayList<Preset>()
            for (i in 0 until nPh - 1) {
                val r = phO + i * 38
                val pname = name(r); val program = u16(r + 20); val bank = u16(r + 22)
                val b0 = u16(r + 24); val b1 = u16(phO + (i + 1) * 38 + 24)
                var pglobal: Array<Int?>? = null
                val zones = ArrayList<Zone>()
                for ((g0, g1) in bags("pbag", b0, b1)) {
                    val pg = zoneGens("pgen", g0, g1)
                    val inst = pg[41]
                    if (inst == null) { if (zones.isEmpty() && pglobal == null) pglobal = pg; continue }
                    val pz = Array(61) { k -> pg[k] ?: pglobal?.get(k) }
                    for (iz in instZones.getOrNull(inst) ?: continue) {
                        val sh = shdrs.getOrNull(iz[53] ?: continue) ?: continue
                        if (sh.type and 0x8000 != 0) continue // ROM samples
                        makeZone(pz, iz, sh)?.let(zones::add)
                    }
                }
                if (zones.isNotEmpty()) out.add(Preset(pname, bank, program, zones))
            }
            return out.sortedWith(compareBy({ it.bank }, { it.program }))
        }

        private fun makeZone(p: Array<Int?>, z: Array<Int?>, s: Shdr): Zone? {
            fun range(k: Int): IntRange {
                val a = z[k]?.let { (it and 0xFF)..((it shr 8) and 0xFF) } ?: 0..127
                val b = p[k]?.let { (it and 0xFF)..((it shr 8) and 0xFF) } ?: 0..127
                return max(a.first, b.first)..min(a.last, b.last)
            }
            fun inst(k: Int, def: Int) = z[k] ?: def
            fun add(k: Int, def: Int) = inst(k, def) + (p[k] ?: 0)
            val keys = range(43); val vels = range(44)
            if (keys.isEmpty() || vels.isEmpty()) return null
            val start = s.start + inst(0, 0) + inst(4, 0) * 32768
            val end = s.end + inst(1, 0) + inst(12, 0) * 32768
            val ls = s.loopStart + inst(2, 0) + inst(45, 0) * 32768
            val le = s.loopEnd + inst(3, 0) + inst(50, 0) * 32768
            if (start < 0 || end > poolSize || end - start < 2) return null
            val root = z[58]?.takeIf { it in 0..127 } ?: s.pitch.takeIf { it in 0..127 } ?: 60
            fun tc(k: Int, def: Int) = 2.0.pow(add(k, def).coerceIn(-12000, 8000) / 1200.0).toFloat()
            val sustainCb = add(37, 0).coerceIn(0, 1440)
            val atten = add(48, 0).coerceIn(0, 1440)
            val fcCents = add(8, 13500).coerceIn(1500, 13500)
            return Zone(
                keyLo = keys.first, keyHi = keys.last, velLo = vels.first, velHi = vels.last,
                start = start, end = end, loopStart = ls.coerceIn(start, end), loopEnd = le.coerceIn(start, end),
                loopMode = inst(54, 0) and 3, sampleRate = s.rate.coerceIn(400, 192000), rootKey = root,
                tuneCents = add(51, 0) * 100 + add(52, 0) + s.correction, scaleTuning = add(56, 100),
                // Initial attenuation scaled by 0.4, as FluidSynth and the original EMU hardware do; most .sf2 files are voiced for that.
                gain = 10f.pow(-atten * 0.4f / 200f),
                pan = (add(17, 0).coerceIn(-500, 500) / 500f),
                delay = tc(33, -12000), attack = tc(34, -12000), hold = tc(35, -12000), decay = tc(36, -12000),
                sustain = 10f.pow(-sustainCb / 200f), release = tc(38, -12000).coerceAtLeast(0.005f),
                cutoffHz = (8.176 * 2.0.pow(fcCents / 1200.0)).toFloat(), resonance = (add(9, 0).coerceIn(0, 960) / 960f),
                exclusiveClass = inst(57, 0),
            )
        }
    }
}

/** Loaded SoundFonts, shared by the UI and the audio thread. */
class SoundFontBank {
    private val map = ConcurrentHashMap<String, SoundFont>()
    operator fun get(id: String?): SoundFont? = id?.let { map[it] }
    fun put(id: String, sf: SoundFont) { map[id] = sf }
    fun contains(id: String) = map.containsKey(id)
}

/** Plays one preset of a SoundFont. */
class SoundFontPlayer(private val sampleRate: Float, private val fonts: SoundFontBank, var fontId: String?, var bank: Int, var program: Int, var gain: Float) : Instrument {
    private val voices = Array(32) { SfVoice(sampleRate) }
    private var counter = 0L
    private var cachedFont: SoundFont? = null
    private var cachedKey = ""
    private var preset: SoundFont.Preset? = null

    private fun currentPreset(): Pair<SoundFont, SoundFont.Preset>? {
        val sf = fonts[fontId] ?: return null
        val key = "$fontId:$bank:$program"
        if (sf !== cachedFont || key != cachedKey) { cachedFont = sf; cachedKey = key; preset = sf.preset(bank, program) }
        val p = preset ?: return null
        return sf to p
    }

    override fun noteOn(pitch: Int, velocity: Int) {
        val (sf, p) = currentPreset() ?: return
        for (z in p.zones) {
            if (pitch !in z.keyLo..z.keyHi || velocity !in z.velLo..z.velHi) continue
            if (z.exclusiveClass != 0) voices.forEach { if (it.active && it.exclusive == z.exclusiveClass) it.fastRelease() }
            val v = voices.firstOrNull { !it.active } ?: voices.filter { it.releasing }.minByOrNull { it.age } ?: voices.minBy { it.age }
            v.start(sf.samples, z, pitch, velocity, counter++)
        }
    }

    override fun noteOff(pitch: Int) { voices.forEach { if (it.active && it.pitch == pitch) it.release() } }

    override fun allNotesOff(hard: Boolean) { voices.forEach { if (hard) it.kill() else it.release() } }

    override fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        for (v in voices) if (v.active) v.render(outL, outR, n, gain)
    }
}

private class SfVoice(private val sr: Float) {
    var active = false
    var pitch = -1
    var age = 0L
    var exclusive = 0
    var releasing = false
    private var data: ShortArray = ShortArray(0)
    private var zone: SoundFont.Zone? = null
    private var pos = 0.0
    private var inc = 1.0
    private var vel = 1f
    private var gl = 0.7f
    private var gr = 0.7f
    // DAHDSR
    private var stage = 0 // 0 delay 1 attack 2 hold 3 decay 4 sustain 5 release
    private var env = 0f
    private var t = 0f
    private var releaseCoef = 0f
    private val filter = Svf()

    fun start(d: ShortArray, z: SoundFont.Zone, key: Int, velocity: Int, age: Long) {
        data = d; zone = z; pitch = key; this.age = age; exclusive = z.exclusiveClass
        pos = z.start.toDouble()
        val cents = (key - z.rootKey) * z.scaleTuning + z.tuneCents
        inc = 2.0.pow(cents / 1200.0) * z.sampleRate / sr
        val v = velocity / 127f
        vel = v * v * z.gain
        gl = panLeft(z.pan); gr = panRight(z.pan)
        stage = if (z.delay > 0.002f) 0 else 1
        env = 0f; t = 0f; releasing = false
        releaseCoef = kotlin.math.exp(-6.9f / max(1f, z.release * sr))
        filter.reset()
        active = true
    }

    fun release() { if (active && !releasing) { releasing = true; stage = 5; releaseCoef = kotlin.math.exp(-6.9f / max(1f, (zone?.release ?: 0.1f) * sr)) } }
    fun fastRelease() { releasing = true; stage = 5; releaseCoef = kotlin.math.exp(-6.9f / (0.01f * sr)) }
    fun kill() { active = false; pitch = -1 }

    fun render(outL: FloatArray, outR: FloatArray, n: Int, gain: Float) {
        val z = zone ?: return
        val loop = z.loopMode == 1 || (z.loopMode == 3 && !releasing)
        val loopLen = (z.loopEnd - z.loopStart).toDouble()
        val useFilter = z.cutoffHz < 17000f
        if (useFilter) filter.set(z.cutoffHz, z.resonance * 0.8f, sr)
        val dt = 1f / sr
        for (i in 0 until n) {
            // Envelope
            when (stage) {
                0 -> { t += dt; if (t >= z.delay) { stage = 1; t = 0f } }
                1 -> { env = if (z.attack <= 0.002f) 1f else env + dt / z.attack; if (env >= 1f) { env = 1f; stage = 2; t = 0f } }
                2 -> { t += dt; if (t >= z.hold) { stage = 3; t = 0f } }
                3 -> {
                    val k = kotlin.math.exp(-6.9f / max(1f, z.decay * sr))
                    env = z.sustain + (env - z.sustain) * k
                    if (env - z.sustain < 1e-4f) { env = z.sustain; stage = 4 }
                }
                4 -> env = z.sustain
                5 -> { env *= releaseCoef; if (env < 1e-4f) { kill(); return } }
            }
            if (stage == 4 && z.sustain <= 1e-4f) { kill(); return }
            // Sample
            if (loop && loopLen > 1 && pos >= z.loopEnd) pos -= loopLen
            val idx = pos.toInt()
            if (idx >= z.end - 1 || idx >= data.size - 1) { kill(); return }
            val f = (pos - idx).toFloat()
            var s = (data[idx] + (data[idx + 1] - data[idx]) * f) / 32768f
            pos += inc
            if (useFilter) { filter.process(s); s = filter.lp }
            val out = s * env * vel * gain
            outL[i] += out * gl
            outR[i] += out * gr
        }
    }
}

/** General MIDI drum names for keys 35–81, used to label SoundFont drum pads. */
object GmDrums {
    private val names = mapOf(
        35 to "Kick 2", 36 to "Kick", 37 to "Side Stick", 38 to "Snare", 39 to "Clap", 40 to "Snare 2", 41 to "Low Tom 2",
        42 to "Closed Hat", 43 to "Low Tom", 44 to "Pedal Hat", 45 to "Mid Tom 2", 46 to "Open Hat", 47 to "Mid Tom",
        48 to "High Tom 2", 49 to "Crash", 50 to "High Tom", 51 to "Ride", 52 to "China", 53 to "Ride Bell",
        54 to "Tambourine", 55 to "Splash", 56 to "Cowbell", 57 to "Crash 2", 58 to "Vibraslap", 59 to "Ride 2",
        60 to "Hi Bongo", 61 to "Lo Bongo", 62 to "Mute Conga", 63 to "Hi Conga", 64 to "Lo Conga", 65 to "Hi Timbale",
        66 to "Lo Timbale", 67 to "Hi Agogo", 68 to "Lo Agogo", 69 to "Cabasa", 70 to "Maracas", 75 to "Claves",
    )
    fun name(key: Int) = names[key] ?: "Key $key"
}
