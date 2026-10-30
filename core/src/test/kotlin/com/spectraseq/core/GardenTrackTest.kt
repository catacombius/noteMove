package com.spectraseq.core

import com.spectraseq.core.dsp.ExternalInstruments
import com.spectraseq.core.dsp.Instrument
import com.spectraseq.core.engine.AudioEngine
import com.spectraseq.core.export.ProjectJson
import com.spectraseq.core.model.GardenPatch
import com.spectraseq.core.model.Project
import com.spectraseq.core.model.Track
import com.spectraseq.core.model.TrackKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GARDEN tracks: they load in old and new sets, play as a synth without the Garden, and through its factory with it. */
class GardenTrackTest {
    private val sr = 48000

    @After fun noFactory() { ExternalInstruments.garden = null }

    private fun play(e: AudioEngine, frames: Int): Double {
        val l = FloatArray(256); val r = FloatArray(256)
        var sum = 0.0
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            l.fill(0f); r.fill(0f)
            e.render(l, r, n)
            for (i in 0 until n) sum += l[i] * l[i].toDouble()
            done += n
        }
        return kotlin.math.sqrt(sum / frames)
    }

    @Test
    fun setsWithAndWithoutGardenTracksLoad() {
        val old = Project(name = "old", tracks = listOf(Track(name = "S", kind = TrackKind.SYNTH)))
        val json = ProjectJson.encode(old).replace(",\"garden\":null", "")
        assertTrue("the old set has no garden field", !json.contains("\"garden\""))
        assertNull(ProjectJson.decode(json).tracks[0].garden)
        val patch = GardenPatch(sound = "Felt Keys", kit = "Moss", values = listOf(0.5, 0.25))
        val withGarden = old.copy(tracks = listOf(Track(name = "G", kind = TrackKind.GARDEN, garden = patch)))
        val back = ProjectJson.decode(ProjectJson.encode(withGarden))
        assertEquals(TrackKind.GARDEN, back.tracks[0].kind)
        assertEquals(patch, back.tracks[0].garden)
    }

    @Test
    fun withoutTheGardenItPlaysAsASynth() {
        val t = Track(name = "G", kind = TrackKind.GARDEN, garden = GardenPatch())
        val e = AudioEngine(sr)
        e.setProject(Project(name = "x", tracks = listOf(t)))
        play(e, 256)
        e.noteOn(t.id, 60, 110)
        assertTrue("silent", play(e, sr / 4) > 1e-4)
    }

    private class Fake : Instrument {
        var on = 0; var tempo = 0.0; var updates = 0; var released = false
        override fun noteOn(pitch: Int, velocity: Int) { on++ }
        override fun noteOff(pitch: Int) {}
        override fun allNotesOff(hard: Boolean) {}
        override fun render(outL: FloatArray, outR: FloatArray, n: Int) { for (i in 0 until n) outL[i] += 0.1f }
        override fun setTransport(tempo: Double, beat: Double, playing: Boolean) { this.tempo = tempo }
        override fun update(track: Track) { updates++ }
        override fun release() { released = true }
    }

    @Test
    fun withTheGardenItsFactoryPlaysItAndIsToldEverything() {
        val made = ArrayList<Fake>()
        ExternalInstruments.garden = { _, _ -> Fake().also { made.add(it) } }
        val t = Track(name = "G", kind = TrackKind.GARDEN, garden = GardenPatch())
        val p = Project(name = "x", tempo = 97.0, tracks = listOf(t))
        val e = AudioEngine(sr)
        e.setProject(p)
        play(e, 256)
        e.noteOn(t.id, 60, 100)
        assertTrue("heard", play(e, 1024) > 0.01)
        assertEquals(1, made.size)
        assertEquals(1, made[0].on)
        assertEquals(97.0, made[0].tempo, 1e-9)
        e.setProject(p.copy(tracks = listOf(t.copy(garden = GardenPatch(sound = "Other")))))
        play(e, 256)
        assertTrue("told of the change", made[0].updates > 0)
        e.setProject(p.copy(tracks = listOf(t.copy(kind = TrackKind.SYNTH))))
        play(e, 256)
        assertTrue("released when the track is no longer a garden", made[0].released)
    }
}
