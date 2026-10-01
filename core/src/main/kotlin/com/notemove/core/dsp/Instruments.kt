package com.notemove.core.dsp

import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.DRUM_PAD_COUNT
import com.notemove.core.model.DrumKit
import com.notemove.core.model.DrumPad
import com.notemove.core.model.DrumSound
import com.notemove.core.model.FilterMode
import com.notemove.core.model.SamplerPatch
import com.notemove.core.model.SynthPatch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.pow

/** Mono PCM audio held in memory. */
class SampleData(val data: FloatArray, val sampleRate: Int) {
    val frames get() = data.size
}

/** Thread-safe registry of loaded samples, shared between the UI and the audio thread. */
class SampleBank {
    private val map = ConcurrentHashMap<String, SampleData>()
    operator fun get(id: String?): SampleData? = id?.let { map[it] }
    fun put(id: String, data: SampleData) { map[id] = data }
    fun remove(id: String) { map.remove(id) }
    fun clear() = map.clear()
    fun ids(): Set<String> = map.keys
}

interface Instrument {
    fun noteOn(pitch: Int, velocity: Int)
    fun noteOff(pitch: Int)
    fun allNotesOff(hard: Boolean = false)
    /** Adds [n] frames of audio into the buffers. */
    fun render(outL: FloatArray, outR: FloatArray, n: Int)
}

// ---------------------------------------------------------------------------------------------
// Poly synth
// ---------------------------------------------------------------------------------------------

class PolySynth(private val sampleRate: Float, var patch: SynthPatch, voices: Int = 10) : Instrument {
    private val voices = Array(voices) { SynthVoice(sampleRate) }
    private val noise = Noise(1234)
    private var lfoPhase = 0f
    private var counter = 0L
    private val heldMono = ArrayList<Int>()

    override fun noteOn(pitch: Int, velocity: Int) {
        val p = patch
        if (p.mono || p.glide > 0f) {
            heldMono.remove(pitch); heldMono.add(pitch)
            val v = voices[0]
            val legato = v.gate
            v.start(pitch, velocity, p, counter++, glideFrom = if (v.active) v.currentNote else -1f, retrigger = !legato)
            for (i in 1 until voices.size) voices[i].kill()
            return
        }
        // Re-use a voice already playing this pitch, else a free one, else steal the oldest.
        val voice = voices.firstOrNull { it.active && it.pitch == pitch }
            ?: voices.firstOrNull { !it.active }
            ?: voices.filter { !it.gate }.minByOrNull { it.age }
            ?: voices.minBy { it.age }
        voice.start(pitch, velocity, p, counter++, glideFrom = -1f, retrigger = true)
    }

    override fun noteOff(pitch: Int) {
        val p = patch
        if (p.mono || p.glide > 0f) {
            heldMono.remove(pitch)
            val v = voices[0]
            if (v.pitch == pitch) {
                if (heldMono.isNotEmpty()) v.start(heldMono.last(), v.velocity, p, counter++, v.currentNote, retrigger = false)
                else v.release()
            }
            return
        }
        voices.forEach { if (it.pitch == pitch && it.gate) it.release() }
    }

    override fun allNotesOff(hard: Boolean) {
        heldMono.clear()
        voices.forEach { if (hard) it.kill() else it.release() }
    }

    override fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        val p = patch
        val lfoInc = p.lfoRate / sampleRate
        // LFO is evaluated once per block (blocks are small).
        val lfo = fastSin(lfoPhase)
        lfoPhase += lfoInc * n
        lfoPhase -= kotlin.math.floor(lfoPhase)
        for (v in voices) if (v.active) v.render(outL, outR, n, p, lfo, noise)
    }
}

private class SynthVoice(private val sr: Float) {
    var active = false
    var gate = false
    var pitch = -1
    var velocity = 0
    var age = 0L
    var currentNote = 0f
    private var targetNote = 0f
    private var glideCoef = 0f
    private val osc1 = Oscillator()
    private val osc2 = Oscillator()
    private val sub = Oscillator()
    private val amp = Adsr(sr)
    private val fenv = Adsr(sr)
    private val filter = Svf()
    private val filter2 = Svf()
    private var velGain = 1f

    fun start(pitch: Int, velocity: Int, p: SynthPatch, age: Long, glideFrom: Float, retrigger: Boolean) {
        this.pitch = pitch
        this.velocity = velocity
        this.age = age
        targetNote = (pitch + p.transpose).toFloat()
        currentNote = if (glideFrom >= 0f && p.glide > 0f) glideFrom else targetNote
        glideCoef = if (p.glide > 0f) kotlin.math.exp(-1f / (p.glide * sr)) else 0f
        velGain = 0.25f + 0.75f * (velocity / 127f)
        amp.set(p.attack, p.decay, p.sustain, p.release)
        fenv.set(p.filterAttack, p.filterDecay, p.filterSustain, p.filterRelease)
        if (retrigger || !active) {
            if (!active) { filter.reset(); filter2.reset(); osc2.phase = 0.37f }
            amp.gate(true); fenv.gate(true)
        }
        active = true
        gate = true
    }

    fun release() { gate = false; amp.gate(false); fenv.gate(false) }

    fun kill() { active = false; gate = false; amp.reset(); fenv.reset(); pitch = -1 }

    fun render(outL: FloatArray, outR: FloatArray, n: Int, p: SynthPatch, lfo: Float, noise: Noise) {
        amp.set(p.attack, p.decay, p.sustain, p.release)
        fenv.set(p.filterAttack, p.filterDecay, p.filterSustain, p.filterRelease)
        val mix2 = clamp(p.oscMix, 0f, 1f)
        val mix1 = 1f - mix2
        val sub = clamp(p.subLevel, 0f, 1f)
        val nz = clamp(p.noiseLevel, 0f, 1f)
        val baseCut = p.cutoff * 10f // in "octaves" over 20Hz scale: 0..10
        val keyOct = (currentNote - 60f) / 12f * p.keyTracking
        val gain = p.gain * velGain * 0.5f
        for (i in 0 until n) {
            if (glideCoef > 0f) currentNote = targetNote + (currentNote - targetNote) * glideCoef else currentNote = targetNote
            val note = currentNote + lfo * p.lfoToPitch * 2f
            val f1 = midiToHz(note) / sr
            val f2 = midiToHz(note + p.osc2Semi + p.osc2Detune / 100f) / sr
            var s = osc1.next(p.osc1, f1, noise) * mix1 + osc2.next(p.osc2, f2, noise) * mix2
            if (sub > 0f) s += this.sub.next(com.notemove.core.model.Waveform.SQUARE, f1 * 0.5f, noise) * sub * 0.7f
            if (nz > 0f) s += noise.next() * nz
            val fe = fenv.next()
            val oct = baseCut + keyOct + fe * p.filterEnvAmount * 6f + lfo * p.lfoToFilter * 3f
            val cutHz = 20f * 2f.pow(oct.coerceIn(0f, 10f))
            filter.set(cutHz, p.resonance, sr)
            filter.process(s)
            val y = when (p.filterMode) {
                FilterMode.LOW_PASS -> { filter2.set(cutHz, 0f, sr); filter2.process(filter.lp); filter2.lp }
                FilterMode.HIGH_PASS -> filter.hp
                FilterMode.BAND_PASS -> filter.bp
            }
            val a = amp.next()
            val out = y * a * gain
            outL[i] += out
            outR[i] += out
        }
        if (!amp.isActive) { active = false; pitch = -1 }
    }
}

// ---------------------------------------------------------------------------------------------
// Drum machine (synthesised kit + sample pads)
// ---------------------------------------------------------------------------------------------

class DrumMachine(private val sampleRate: Float, var kit: DrumKit, private val samples: SampleBank) : Instrument {
    private val voices = Array(DRUM_PAD_COUNT) { DrumVoice(sampleRate, it) }

    override fun noteOn(pitch: Int, velocity: Int) {
        val pad = pitch - DRUM_BASE_NOTE
        if (pad !in 0 until DRUM_PAD_COUNT) return
        val p = kit.pads.getOrNull(pad) ?: return
        if (p.chokeGroup != 0) {
            kit.pads.forEachIndexed { i, other -> if (i != pad && other.chokeGroup == p.chokeGroup) voices[i].choke() }
        }
        voices[pad].trigger(p, velocity, samples[p.sampleId])
    }

    override fun noteOff(pitch: Int) {
        val pad = pitch - DRUM_BASE_NOTE
        if (pad in 0 until DRUM_PAD_COUNT) voices[pad].release()
    }

    override fun allNotesOff(hard: Boolean) { if (hard) voices.forEach { it.choke() } }

    override fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        for (v in voices) if (v.active) v.render(outL, outR, n)
    }
}

private class DrumVoice(private val sr: Float, padIndex: Int) {
    var active = false
    private var sound = DrumSound.KICK
    private var pad: DrumPad? = null
    private var t = 0 // samples since trigger
    private val amp = Decay()
    private val pitchEnv = Decay()
    private val noiseEnv = Decay()
    private val noise = Noise(777 + padIndex * 31)
    private val f1 = Svf()
    private val f2 = Svf()
    private var phase = 0f
    private val metal = FloatArray(6)
    private var gain = 1f
    private var gl = 0.7f
    private var gr = 0.7f
    private var tuneRatio = 1f
    private var chokeFade = 1f
    private var choking = false
    // Sample playback
    private var sample: SampleData? = null
    private var pos = 0.0
    private var rate = 1.0
    private var held = false

    fun trigger(p: DrumPad, velocity: Int, sampleData: SampleData?) {
        pad = p
        sound = if (p.sound == DrumSound.SAMPLE && sampleData == null) DrumSound.CLAVE else p.sound
        sample = if (p.sound == DrumSound.SAMPLE) sampleData else null
        t = 0
        phase = 0f
        metal.fill(0f)
        f1.reset(); f2.reset()
        val vel = velocity / 127f
        gain = p.level * (0.15f + 0.85f * vel * vel)
        gl = panLeft(p.pan); gr = panRight(p.pan)
        tuneRatio = 2f.pow(p.tune / 12f)
        chokeFade = 1f
        choking = false
        held = true
        val d = p.decay.coerceIn(0.05f, 4f)
        val tone = p.tone
        when (sound) {
            DrumSound.KICK -> { amp.trigger(1f, 0.45f * d, sr); pitchEnv.trigger(1f, 0.06f + 0.04f * tone, sr) }
            DrumSound.SUB_KICK -> { amp.trigger(1f, 0.9f * d, sr); pitchEnv.trigger(1f, 0.08f, sr) }
            DrumSound.SNARE -> { amp.trigger(1f, 0.16f * d, sr); noiseEnv.trigger(1f, 0.22f * d, sr); pitchEnv.trigger(1f, 0.03f, sr) }
            DrumSound.CLAP -> { amp.trigger(1f, 0.25f * d, sr) }
            DrumSound.RIM -> { amp.trigger(1f, 0.035f * d, sr) }
            DrumSound.CLOSED_HAT -> { amp.trigger(1f, 0.05f * d, sr) }
            DrumSound.PEDAL_HAT -> { amp.trigger(1f, 0.11f * d, sr) }
            DrumSound.OPEN_HAT -> { amp.trigger(1f, 0.45f * d, sr) }
            DrumSound.TOM_LOW, DrumSound.TOM_MID, DrumSound.TOM_HIGH -> { amp.trigger(1f, 0.35f * d, sr); pitchEnv.trigger(1f, 0.08f, sr) }
            DrumSound.CRASH -> { amp.trigger(1f, 1.6f * d, sr) }
            DrumSound.RIDE -> { amp.trigger(1f, 1.2f * d, sr) }
            DrumSound.COWBELL -> { amp.trigger(1f, 0.3f * d, sr) }
            DrumSound.SHAKER -> { amp.trigger(1f, 0.09f * d, sr) }
            DrumSound.CONGA -> { amp.trigger(1f, 0.22f * d, sr); pitchEnv.trigger(1f, 0.02f, sr) }
            DrumSound.CLAVE -> { amp.trigger(1f, 0.06f * d, sr) }
            DrumSound.ZAP -> { amp.trigger(1f, 0.25f * d, sr); pitchEnv.trigger(1f, 0.05f * d, sr) }
            DrumSound.SAMPLE -> {
                amp.trigger(1f, 30f * d, sr)
                pos = 0.0
                rate = (sample!!.sampleRate / sr.toDouble()) * tuneRatio
            }
        }
        active = true
    }

    fun release() { held = false }

    fun choke() { if (active) choking = true }

    fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        val p = pad ?: return
        val tone = p.tone
        for (i in 0 until n) {
            val s = when (sound) {
                DrumSound.KICK, DrumSound.SUB_KICK -> {
                    val sub = sound == DrumSound.SUB_KICK
                    val base = (if (sub) 42f else 48f) * tuneRatio
                    val f = base + (if (sub) 120f else 180f + 140f * tone) * pitchEnv.next()
                    phase += f / sr
                    if (phase >= 1f) phase -= 1f
                    val click = if (t < sr * 0.004f) noise.next() * 0.5f * tone * (1f - t / (sr * 0.004f)) else 0f
                    softClip((fastSin(phase) * 1.4f + click)) * amp.next()
                }
                DrumSound.SNARE -> {
                    val f = 185f * tuneRatio * (1f + 0.5f * pitchEnv.next())
                    phase += f / sr; if (phase >= 1f) phase -= 1f
                    f1.set(1800f + 5000f * tone, 0.2f, sr)
                    f1.process(noise.next())
                    fastSin(phase) * amp.next() * 0.7f + f1.hp * noiseEnv.next() * 0.8f
                }
                DrumSound.CLAP -> {
                    val ms = t * 1000f / sr
                    val burst = if (ms < 30f) { val k = (ms % 10f) / 10f; 1f - k } else 0f
                    f1.set(1100f * tuneRatio + 600f * tone, 0.5f, sr)
                    f1.process(noise.next())
                    val env = amp.next()
                    f1.bp * (if (ms < 30f) burst else env) * 1.6f
                }
                DrumSound.RIM -> {
                    phase += 1700f * tuneRatio / sr; if (phase >= 1f) phase -= 1f
                    f1.set(2000f * tuneRatio, 0.6f, sr)
                    f1.process(if (phase < 0.5f) 1f else -1f)
                    f1.bp * amp.next() * 1.2f
                }
                DrumSound.CLOSED_HAT, DrumSound.PEDAL_HAT, DrumSound.OPEN_HAT, DrumSound.CRASH, DrumSound.RIDE -> {
                    val cymbal = sound == DrumSound.CRASH || sound == DrumSound.RIDE
                    val m = metallic(tuneRatio * if (sound == DrumSound.RIDE) 0.8f else 1f)
                    val src = m * (if (cymbal) 0.6f else 0.8f) + noise.next() * (if (cymbal) 0.5f else 0.3f + 0.3f * tone)
                    f1.set((if (cymbal) 5500f else 7000f) + 3000f * tone, 0.1f, sr)
                    f1.process(src)
                    f1.hp * amp.next() * (if (sound == DrumSound.RIDE) 0.6f else 0.8f)
                }
                DrumSound.TOM_LOW, DrumSound.TOM_MID, DrumSound.TOM_HIGH -> {
                    val base = when (sound) { DrumSound.TOM_LOW -> 90f; DrumSound.TOM_MID -> 130f; else -> 180f } * tuneRatio
                    val f = base * (1f + 0.6f * pitchEnv.next())
                    phase += f / sr; if (phase >= 1f) phase -= 1f
                    val nz = if (t < sr * 0.01f) noise.next() * 0.2f * tone else 0f
                    (fastSin(phase) + nz) * amp.next()
                }
                DrumSound.COWBELL -> {
                    phase += 540f * tuneRatio / sr; if (phase >= 1f) phase -= 1f
                    metal[0] += 800f * tuneRatio / sr; if (metal[0] >= 1f) metal[0] -= 1f
                    val sq = (if (phase < 0.5f) 1f else -1f) + (if (metal[0] < 0.5f) 1f else -1f)
                    f1.set(2600f, 0.4f, sr); f1.process(sq)
                    f1.bp * amp.next() * 0.8f
                }
                DrumSound.SHAKER -> {
                    val attack = (t / (sr * 0.015f)).coerceAtMost(1f)
                    f1.set(6000f + 4000f * tone, 0.2f, sr); f1.process(noise.next())
                    f1.hp * amp.next() * attack * 0.8f
                }
                DrumSound.CONGA -> {
                    val f = 260f * tuneRatio * (1f + 0.3f * pitchEnv.next())
                    phase += f / sr; if (phase >= 1f) phase -= 1f
                    fastSin(phase) * amp.next()
                }
                DrumSound.CLAVE -> {
                    phase += 2500f * tuneRatio / sr; if (phase >= 1f) phase -= 1f
                    fastSin(phase) * amp.next() * 0.8f
                }
                DrumSound.ZAP -> {
                    val f = 80f * tuneRatio + 2400f * pitchEnv.next()
                    phase += f / sr; if (phase >= 1f) phase -= 1f
                    fastSin(phase) * amp.next() * 0.8f
                }
                DrumSound.SAMPLE -> {
                    val smp = sample
                    if (smp == null || pos >= smp.frames - 1) { amp.trigger(0f, 0.001f, sr); 0f } else {
                        val idx = pos.toInt()
                        val frac = (pos - idx).toFloat()
                        val d = smp.data
                        pos += rate
                        (d[idx] + (d[idx + 1] - d[idx]) * frac) * amp.next()
                    }
                }
            }
            if (choking) {
                chokeFade *= 0.995f
            }
            val out = s * gain * chokeFade
            outL[i] += out * gl
            outR[i] += out * gr
            t++
        }
        if (amp.value < 1e-4f || chokeFade < 1e-3f) active = false
    }

    // Six detuned square waves, as in the classic analogue hi-hat circuit.
    private val metalFreqs = floatArrayOf(205.3f, 304.4f, 369.6f, 522.7f, 540f, 800f)
    private fun metallic(ratio: Float): Float {
        var sum = 0f
        for (k in 0 until 6) {
            metal[k] += metalFreqs[k] * 2.2f * ratio / sr
            if (metal[k] >= 1f) metal[k] -= 1f
            sum += if (metal[k] < 0.5f) 1f else -1f
        }
        return sum / 6f
    }
}

// ---------------------------------------------------------------------------------------------
// Sampler
// ---------------------------------------------------------------------------------------------

class Sampler(private val sampleRate: Float, var patch: SamplerPatch, private val samples: SampleBank, voices: Int = 8) : Instrument {
    private val voices = Array(voices) { SamplerVoice(sampleRate) }
    private var counter = 0L

    override fun noteOn(pitch: Int, velocity: Int) {
        val data = samples[patch.sampleId] ?: return
        val voice = voices.firstOrNull { !it.active } ?: voices.minBy { it.age }
        voice.start(pitch, velocity, patch, data, counter++)
    }

    override fun noteOff(pitch: Int) { voices.forEach { if (it.pitch == pitch) it.release() } }

    override fun allNotesOff(hard: Boolean) { voices.forEach { if (hard) it.kill() else it.release() } }

    override fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        val p = patch
        for (v in voices) if (v.active) v.render(outL, outR, n, p)
    }
}

private class SamplerVoice(private val sr: Float) {
    var active = false
    var pitch = -1
    var age = 0L
    private var data: SampleData? = null
    private var pos = 0.0
    private var rate = 1.0
    private var startF = 0.0
    private var endF = 0.0
    private val amp = Adsr(sr)
    private val filter = Svf()
    private var vel = 1f

    fun start(pitch: Int, velocity: Int, p: SamplerPatch, d: SampleData, age: Long) {
        this.pitch = pitch; this.age = age; data = d
        val s = clamp(minOf(p.start, p.end), 0f, 1f)
        val e = clamp(maxOf(p.start, p.end), 0f, 1f)
        startF = s * (d.frames - 1).toDouble()
        endF = max(startF + 1, e * (d.frames - 1).toDouble())
        val semis = if (p.pitched) pitch - p.rootNote else 0
        rate = d.sampleRate / sr.toDouble() * 2.0.pow(semis / 12.0)
        pos = if (p.reverse) endF else startF
        vel = 0.2f + 0.8f * velocity / 127f
        amp.set(p.attack, p.decay, p.sustain, p.release)
        amp.gate(true)
        filter.reset()
        active = true
    }

    fun release() = amp.gate(false)
    fun kill() { active = false; amp.reset() }

    fun render(outL: FloatArray, outR: FloatArray, n: Int, p: SamplerPatch) {
        val d = data ?: return
        val buf = d.data
        amp.set(p.attack, p.decay, p.sustain, p.release)
        val filt = p.cutoff < 0.99f
        if (filt) filter.set(normToCutoffHz(p.cutoff), p.resonance, sr)
        val g = p.gain * vel
        for (i in 0 until n) {
            if (p.reverse) {
                if (pos <= startF) { if (p.loop) pos = endF else { kill(); return } }
            } else if (pos >= endF) { if (p.loop) pos = startF else { kill(); return } }
            val idx = pos.toInt().coerceIn(0, buf.size - 2)
            val frac = (pos - idx).toFloat()
            var s = buf[idx] + (buf[idx + 1] - buf[idx]) * frac
            pos += if (p.reverse) -rate else rate
            if (filt) { filter.process(s); s = filter.lp }
            val out = s * amp.next() * g
            outL[i] += out; outR[i] += out
        }
        if (!amp.isActive) kill()
    }
}
