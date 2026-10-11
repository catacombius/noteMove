package com.spectraseq.core

import com.spectraseq.core.dsp.SampleBank
import com.spectraseq.core.dsp.SampleData
import com.spectraseq.core.dsp.Slicer
import com.spectraseq.core.engine.AudioEngine
import com.spectraseq.core.model.DRUM_BASE_NOTE
import com.spectraseq.core.model.DrumKit
import com.spectraseq.core.model.DrumKits
import com.spectraseq.core.model.DrumPad
import com.spectraseq.core.model.DrumSound
import com.spectraseq.core.model.Project
import com.spectraseq.core.model.SynthPresets
import com.spectraseq.core.model.Track
import com.spectraseq.core.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

class SlicerTest {
    private val sr = 48000

    /** A "drum loop": decaying hits (noise click + tone) at known times over a little background noise. */
    private fun loop(hitsSec: List<Double>, seconds: Double = 2.0): FloatArray {
        val out = FloatArray((seconds * sr).toInt())
        val rnd = java.util.Random(7)
        for (i in out.indices) out[i] = (rnd.nextFloat() - 0.5f) * 0.002f
        hitsSec.forEachIndexed { h, t0 ->
            val start = (t0 * sr).toInt()
            val f = 80.0 + h * 70.0
            for (i in 0 until (0.3 * sr).toInt()) {
                val idx = start + i
                if (idx >= out.size) break
                val env = exp(-i / (0.06 * sr))
                val click = if (i < sr / 500) (rnd.nextFloat() - 0.5f) * 1.2f else 0f
                out[idx] += ((sin(2 * PI * f * i / sr) * 0.6 * env) + click * env).toFloat()
            }
        }
        return out
    }

    @Test
    fun findsTransientsNearTheHits() {
        val hits = listOf(0.0, 0.5, 0.75, 1.25, 1.5)
        val pts = Slicer.transients(loop(hits), sr, sensitivity = 0.5f)
        assertEquals("points: ${pts.toList()}", hits.size, pts.size)
        hits.zip(pts.toList()).forEach { (t, p) ->
            val errMs = (p.toDouble() / sr - t) * 1000
            // Slightly early is fine (we back off to a quiet spot); late would cut the attack.
            assertTrue("hit at $t found at ${p.toDouble() / sr} ($errMs ms)", errMs in -12.0..2.0)
        }
        // Lower sensitivity never finds more slices.
        assertTrue(Slicer.transients(loop(hits), sr, 0.05f).size <= pts.size)
    }

    @Test
    fun equalBeatsAndGuess() {
        assertEquals(listOf(0, 250, 500, 750), Slicer.equal(1000, 4).toList())
        // 2 s at 120 BPM = 4 beats = 1 bar; 8th notes -> 8 slices
        val frames = 2 * sr
        assertEquals(4.0, Slicer.guessLoopBeats(frames, sr, 120.0), 1e-9)
        val b = Slicer.beats(frames, 4.0, 0.5)
        assertEquals(8, b.size)
        assertEquals(frames / 8, b[1])
        // Never more slices than pads.
        assertEquals(Slicer.MAX_SLICES, Slicer.beats(frames, 16.0, 0.25).size)
    }

    @Test
    fun drumKitAndClipFromSlices() {
        val frames = 96000
        val pts = intArrayOf(0, 24000, 48000, 72000)
        val kit = Slicer.toDrumKit("Loop", "s1", pts, frames, DrumKits.KIT_808, choke = true)
        assertEquals(16, kit.pads.size)
        assertEquals(DrumSound.SAMPLE, kit.pads[3].sound)
        assertEquals(0.75f, kit.pads[3].start, 1e-6f)
        assertEquals(1f, kit.pads[3].end, 1e-6f)
        assertEquals(DrumKits.KIT_808.pads[4], kit.pads[4])
        val clip = Slicer.toClip(pts, frames, 4.0)
        assertEquals(4.0, clip.lengthBeats, 1e-9)
        assertEquals(listOf(36, 37, 38, 39), clip.notes.map { it.pitch })
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), clip.notes.map { it.start })
        assertTrue(clip.notes.all { abs(it.duration - 1.0) < 1e-9 })
    }

    @Test
    fun padPlaysOnlyItsSlice() {
        // Sample: 0.25 s of tone, then silence; pad 1's region is the silent half.
        val data = FloatArray(sr / 2) { i -> if (i < sr / 4) sin(2 * PI * 440 * i / sr).toFloat() * 0.8f else 0f }
        val samples = SampleBank().apply { put("s", SampleData(data, sr)) }
        val kit = Slicer.toDrumKit("k", "s", intArrayOf(0, sr / 4), data.size, DrumKits.KIT_808, choke = false)
        val t = Track(name = "D", kind = TrackKind.DRUMS, drumKit = kit)
        val e = AudioEngine(sr, samples)
        e.setProject(Project(name = "p", tracks = listOf(t)))
        val l = FloatArray(sr / 2); val r = FloatArray(sr / 2)
        e.noteOn(t.id, DRUM_BASE_NOTE + 1, 127)
        e.render(l, r, l.size)
        assertTrue("silent slice should be silent", l.maxOf { abs(it) } < 1e-3f)
        e.noteOn(t.id, DRUM_BASE_NOTE, 127)
        java.util.Arrays.fill(l, 0f); java.util.Arrays.fill(r, 0f)
        e.render(l, r, l.size)
        assertTrue(l.maxOf { abs(it) } > 0.05f)
        // Stops at the end of its region (no tone after ~0.25 s plus effect tails).
        assertTrue(l.copyOfRange(sr / 4 + 400, sr / 4 + 4000).maxOf { abs(it) } < 0.05f)
    }

    @Test
    fun everyDrumSoundAndPresetIsAudibleAndFinite() {
        val pads = DrumSound.entries.filter { it != DrumSound.SAMPLE }.map { DrumPad(it.label, it) }
        for (chunk in pads.chunked(16)) {
            val kit = DrumKit("all", chunk + List(16 - chunk.size) { DrumPad("x", DrumSound.CLAVE) })
            val t = Track(name = "D", kind = TrackKind.DRUMS, drumKit = kit)
            chunk.forEachIndexed { i, pad ->
                val e = AudioEngine(sr)
                e.setProject(Project(name = "p", tracks = listOf(t)))
                e.noteOn(t.id, DRUM_BASE_NOTE + i, 110)
                val l = FloatArray(sr / 4); val r = FloatArray(sr / 4)
                e.render(l, r, l.size)
                assertTrue("${pad.sound} finite", l.all { it.isFinite() })
                assertTrue("${pad.sound} audible", l.maxOf { abs(it) } > 0.01f)
            }
        }
        for (k in DrumKits.ALL) assertEquals(k.name, 16, k.pads.size)
        for (preset in SynthPresets.ALL) {
            val t = Track(name = "S", kind = TrackKind.SYNTH, synth = preset)
            val e = AudioEngine(sr)
            e.setProject(Project(name = "p", tracks = listOf(t)))
            e.noteOn(t.id, 60, 110)
            val l = FloatArray(sr); val r = FloatArray(sr)
            e.render(l, r, l.size)
            assertTrue("${preset.name} finite", l.all { it.isFinite() })
            assertTrue("${preset.name} audible", l.maxOf { abs(it) } > 0.005f)
            assertTrue("${preset.name} not clipping hard", l.maxOf { abs(it) } < 1.5f)
        }
        assertEquals(SynthPresets.ALL.size, SynthPresets.ALL.map { it.name }.toSet().size)
    }
}
