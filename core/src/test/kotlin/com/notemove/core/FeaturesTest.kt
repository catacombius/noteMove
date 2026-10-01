package com.notemove.core

import com.notemove.core.dsp.EffectChain
import com.notemove.core.dsp.FxContext
import com.notemove.core.dsp.SampleOps
import com.notemove.core.dsp.Spectral
import com.notemove.core.dsp.SpectralOp
import com.notemove.core.dsp.SpectralSelection
import com.notemove.core.engine.AudioEngine
import com.notemove.core.export.ProjectJson
import com.notemove.core.model.ArpMode
import com.notemove.core.model.ArpSettings
import com.notemove.core.model.Clip
import com.notemove.core.model.EffectSlot
import com.notemove.core.model.EffectType
import com.notemove.core.model.Project
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class FeaturesTest {
    private val sr = 48000

    private fun render(e: AudioEngine, frames: Int) {
        val l = FloatArray(256); val r = FloatArray(256)
        var done = 0
        while (done < frames) { val n = minOf(256, frames - done); e.render(l, r, n); done += n }
    }

    @Test
    fun arpRecordsStepsOnTheGrid() {
        val t = Track(name = "A", kind = TrackKind.SYNTH, synth = SynthPresets.PLUCK,
            arp = ArpSettings(enabled = true, mode = ArpMode.UP, rate = 0.25, octaves = 2, gate = 0.5f),
            clips = mapOf(0 to Clip(lengthBeats = 4.0)))
        val e = AudioEngine(sr); e.setProject(Project(name = "x", tempo = 120.0, tracks = listOf(t)))
        e.record(t.id, 0, countIn = false)
        render(e, 64)
        e.noteOn(t.id, 60, 100); e.noteOn(t.id, 64, 100)
        render(e, sr) // two beats -> 8 sixteenths
        e.noteOff(t.id, 60); e.noteOff(t.id, 64)
        render(e, 512)
        val rec = e.drainRecorded().sortedBy { it.start }
        assertTrue("arp produced ${rec.size} notes", rec.size in 7..9)
        // Up over two octaves: 60 64 72 76 60 …
        assertEquals(listOf(60, 64, 72, 76, 60), rec.take(5).map { it.pitch })
        for (n in rec) {
            assertEquals("on grid", 0.0, (n.start / 0.25) - Math.round(n.start / 0.25), 1e-6)
            assertEquals(0.125, n.duration, 1e-9)
        }
    }

    @Test
    fun arpLatchKeepsPlaying() {
        val t = Track(name = "A", kind = TrackKind.SYNTH, synth = SynthPresets.PLUCK,
            arp = ArpSettings(enabled = true, mode = ArpMode.CHORD, rate = 0.5, latch = true))
        val e = AudioEngine(sr); e.setProject(Project(name = "x", tracks = listOf(t)))
        e.noteOn(t.id, 60, 100); render(e, 128); e.noteOff(t.id, 60)
        val l = FloatArray(sr); val r = FloatArray(sr)
        e.render(l, r, sr)
        val rms = sqrt(l.map { it * it.toDouble() }.average())
        assertTrue("latched arp still sounding", rms > 1e-3)
    }

    @Test
    fun everyEffectIsStableAndAudible() {
        val n = 4800
        for (type in EffectType.entries) {
            val chain = EffectChain(sr.toFloat())
            val ctx = FxContext(sr.toFloat())
            val slots = listOf(EffectSlot(type = type, values = type.params.map { 0.8f }))
            var energy = 0.0
            for (block in 0 until 20) {
                val l = FloatArray(n) { (sin(2 * PI * 220 * (block * n + it) / sr) * 0.5).toFloat() }
                val r = l.copyOf()
                chain.process(slots, l, r, n, ctx)
                ctx.beat += n / ctx.samplesPerBeat
                assertTrue("${type.label} produced NaN", l.all { it.isFinite() } && r.all { it.isFinite() })
                assertTrue("${type.label} exploded", l.all { abs(it) < 8f })
                energy += l.sumOf { it * it.toDouble() }
            }
            assertTrue("${type.label} silent", energy > 1.0)
        }
    }

    @Test
    fun effectsAndArpSurviveSerialization() {
        val p = Project.createDefault("fx").let { p0 ->
            p0.copy(
                masterEffects = listOf(EffectSlot(type = EffectType.COMPRESSOR)),
                tracks = p0.tracks.map { it.copy(effects = listOf(EffectSlot(type = EffectType.DELAY).with(1, 0.7f)), arp = ArpSettings(enabled = true, octaves = 3)) },
            )
        }
        assertEquals(p, ProjectJson.decode(ProjectJson.encode(p)))
        // Old projects without these fields still load.
        val legacy = ProjectJson.encode(Project.createDefault("old")).replace("\"effects\":[],", "").replace("\"masterEffects\":[],", "")
        assertTrue(ProjectJson.decode(legacy).tracks.all { it.effects.isEmpty() })
    }

    @Test
    fun spectralEditReconstructsAndRemovesSelectedBand() {
        val len = sr // 1 second: 300 Hz + 3 kHz
        val x = FloatArray(len) { (0.4 * sin(2 * PI * 300 * it / sr) + 0.4 * sin(2 * PI * 3000 * it / sr)).toFloat() }
        // Empty selection = identity.
        val same = Spectral.apply(x, sr, SpectralSelection.Rect(0f, 0f, 20f, 20f, 0f, 0f), SpectralOp.ERASE)
        var err = 0.0
        for (i in 4096 until len - 4096) err = maxOf(err, abs(same[i] - x[i]).toDouble())
        assertTrue("reconstruction error $err", err < 1e-3)
        // Erase 2–5 kHz over the whole file: the 3 kHz tone disappears, 300 Hz stays.
        val erased = Spectral.apply(x, sr, SpectralSelection.Rect(0f, 1f, 2000f, 5000f), SpectralOp.ERASE)
        fun tone(d: FloatArray, f: Double): Double {
            var re = 0.0; var im = 0.0
            for (i in 8000 until 40000) { re += d[i] * kotlin.math.cos(2 * PI * f * i / sr); im += d[i] * sin(2 * PI * f * i / sr) }
            return sqrt(re * re + im * im) / 32000
        }
        assertTrue("3k removed", tone(erased, 3000.0) < tone(x, 3000.0) * 0.05)
        assertTrue("300 kept", tone(erased, 300.0) > tone(x, 300.0) * 0.9)
        val sg = Spectral.spectrogram(x, sr)
        assertTrue(sg.frames > 10 && sg.bins == Spectral.FFT_SIZE / 2 + 1)
    }

    @Test
    fun sampleOps() {
        val d = FloatArray(100) { it / 100f }
        assertEquals(50, SampleOps.trim(d, 25, 75).size)
        assertEquals(0.99f, SampleOps.reverse(d)[0], 1e-6f)
        assertEquals(0.89f, SampleOps.normalize(d).max(), 1e-5f)
        assertEquals(80, SampleOps.delete(d, 10, 30).size)
        assertEquals(0f, SampleOps.fade(d, 0, 100, fadeIn = true)[0], 1e-6f)
    }

    @Test
    fun previewPlaysAndFinishes() {
        val e = AudioEngine(sr); e.setProject(Project.createDefault("p"))
        e.previewSample(FloatArray(sr / 10) { 0.5f }, sr)
        val l = FloatArray(1024); val r = FloatArray(1024)
        e.render(l, r, 1024)
        assertTrue(l.any { it != 0f })
        render(e, sr / 5)
        assertEquals(-1, e.previewFrame)
    }
}
