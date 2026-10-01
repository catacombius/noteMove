package com.notemove.core

import com.notemove.core.engine.AudioEngine
import com.notemove.core.export.MidiFileWriter
import com.notemove.core.export.OfflineRenderer
import com.notemove.core.export.Wav
import com.notemove.core.model.Clip
import com.notemove.core.model.ClipOps
import com.notemove.core.model.DrumKits
import com.notemove.core.model.Note
import com.notemove.core.model.Project
import com.notemove.core.model.Scale
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind
import com.notemove.core.engine.PlayedNote
import com.notemove.core.export.ProjectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.abs

class EngineTest {
    private val sr = 48000

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i].toDouble()
        return kotlin.math.sqrt(s / (to - from).coerceAtLeast(1))
    }

    private fun render(engine: AudioEngine, frames: Int): Pair<FloatArray, FloatArray> {
        val l = FloatArray(frames); val r = FloatArray(frames)
        val bl = FloatArray(256); val br = FloatArray(256)
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            engine.render(bl, br, n)
            System.arraycopy(bl, 0, l, done, n); System.arraycopy(br, 0, r, done, n)
            done += n
        }
        return l to r
    }

    @Test
    fun everyDrumAndSynthSoundIsAudibleAndFinite() {
        val drums = Track(name = "D", kind = TrackKind.DRUMS, drumKit = DrumKits.KIT_BOOM)
        for (kit in DrumKits.ALL) for (pad in 0 until 16) {
            val p = Project(name = "x", tracks = listOf(drums.copy(drumKit = kit)))
            val e = AudioEngine(sr); e.setProject(p)
            e.noteOn(drums.id, 36 + pad, 120)
            val (l, _) = render(e, sr / 2)
            assertTrue("${kit.name} pad $pad silent", rms(l) > 1e-4)
            assertTrue("${kit.name} pad $pad not finite", l.all { it.isFinite() && abs(it) <= 1.01f })
        }
        for (patch in SynthPresets.ALL) {
            val t = Track(name = "S", kind = TrackKind.SYNTH, synth = patch)
            val e = AudioEngine(sr); e.setProject(Project(name = "x", tracks = listOf(t)))
            e.noteOn(t.id, 60, 100)
            val (l, _) = render(e, sr)
            assertTrue("${patch.name} silent", rms(l) > 1e-4)
            assertTrue("${patch.name} not finite", l.all { it.isFinite() })
        }
    }

    @Test
    fun clipPlaysOnTheBeat() {
        val t = Track(name = "D", kind = TrackKind.DRUMS, drumKit = DrumKits.KIT_909, clips = mapOf(0 to Clip(lengthBeats = 4.0, notes = listOf(Note(36, 1.0, 0.25, 127)))))
        val p = Project(name = "x", tempo = 120.0, tracks = listOf(t))
        val e = AudioEngine(sr); e.setProject(p)
        e.launchClip(t.id, 0)
        val (l, _) = render(e, sr) // 2 beats at 120 bpm
        val beatFrame = sr / 2
        assertTrue("silence before the hit", rms(l, 0, beatFrame - 200) < 1e-5)
        assertTrue("hit after beat 1", rms(l, beatFrame, beatFrame + 2400) > 1e-3)
        assertTrue(e.state.playing)
    }

    @Test
    fun recordingCapturesLiveNotesIntoClipTime() {
        val t = Track(name = "K", kind = TrackKind.SYNTH, synth = SynthPresets.KEYS, clips = mapOf(1 to Clip(lengthBeats = 4.0)))
        val p = Project(name = "x", tempo = 120.0, tracks = listOf(t))
        val e = AudioEngine(sr); e.setProject(p)
        e.record(t.id, 1, countIn = false)
        render(e, sr / 2) // 1 beat
        e.noteOn(t.id, 64, 90)
        render(e, sr / 4) // half a beat
        e.noteOff(t.id, 64)
        render(e, 512)
        e.stopRecording()
        render(e, 512)
        val rec = e.drainRecorded()
        assertEquals(1, rec.size)
        assertEquals(64, rec[0].pitch)
        assertEquals(1, rec[0].scene)
        assertEquals(1.0, rec[0].start, 0.02)
        assertEquals(0.5, rec[0].duration, 0.03)
    }

    @Test
    fun countInDelaysClipByOneBar() {
        val t = Track(name = "D", kind = TrackKind.DRUMS, drumKit = DrumKits.KIT_909, clips = mapOf(0 to Clip(lengthBeats = 4.0, notes = listOf(Note(36, 0.0, 0.25, 127)))))
        val e = AudioEngine(sr); e.setProject(Project(name = "x", tracks = listOf(t)))
        e.record(t.id, 0, countIn = true)
        val (l, _) = render(e, sr * 2 + 4800) // 4 beats count-in, then the hit
        // During count-in only metronome clicks are heard; kick lands at frame sr*2.
        assertTrue(rms(l, sr * 2, sr * 2 + 4800) > rms(l, sr * 2 - 4800, sr * 2) * 2)
    }

    @Test
    fun swingDelaysOffbeatSixteenths() {
        assertEquals(0.25 + 0.5 * 0.125, AudioEngine.swungStart(0.25, 0.5f), 1e-9)
        assertEquals(0.5, AudioEngine.swungStart(0.5, 0.5f), 1e-9)
        assertEquals(0.3, AudioEngine.swungStart(0.3, 0.5f), 1e-9)
    }

    @Test
    fun captureBuildsBarAlignedClip() {
        val played = listOf(
            PlayedNote("t", 60, 100.0, 0.5, 100, 9.0),
            PlayedNote("t", 62, 101.0, 0.5, 100, 10.0),
            PlayedNote("t", 64, 103.5, 0.5, 100, 12.5),
        )
        val clip = ClipOps.capture(played)!!
        assertEquals(8.0, clip.lengthBeats, 1e-9) // spans bars 3-4 → 2 bars
        assertEquals(listOf(1.0, 2.0, 4.5), clip.notes.map { it.start })
        val free = ClipOps.capture(played.map { it.copy(transportBeat = null) })!!
        assertEquals(0.0, free.notes.first().start, 1e-9)
    }

    @Test
    fun quantizeAndStepToggle() {
        val c = Clip(lengthBeats = 4.0).withNotes(listOf(Note(60, 0.27, 0.2), Note(62, 3.97, 0.2)))
        val q = ClipOps.quantize(c, 0.25)
        assertEquals(listOf(0.0, 0.25), q.notes.map { it.start })
        val s = ClipOps.toggleStep(Clip(), 36, 0.5, 0.25)
        assertEquals(1, s.notes.size)
        assertEquals(0, ClipOps.toggleStep(s, 36, 0.5, 0.25).notes.size)
    }

    @Test
    fun scaleDegrees() {
        assertEquals(60, Scale.MAJOR.degreeToPitch(0, 0, 3))
        assertEquals(71, Scale.MAJOR.degreeToPitch(6, 0, 3))
        assertEquals(72, Scale.MAJOR.degreeToPitch(7, 0, 3))
        assertEquals(59, Scale.MAJOR.degreeToPitch(-1, 0, 3))
        assertEquals("C3", Scale.noteName(60))
    }

    @Test
    fun exportsRenderAndSerialize() {
        val p0 = Project.createDefault("Song")
        val drum = p0.tracks[0].withClip(0, Clip(lengthBeats = 4.0, notes = (0 until 4).map { Note(36, it.toDouble(), 0.2, 100) }))
        val bass = p0.tracks[1].withClip(0, Clip(lengthBeats = 4.0, notes = listOf(Note(36, 0.0, 2.0, 100))))
        val p = p0.copy(tracks = listOf(drum, bass) + p0.tracks.drop(2))
        val r = OfflineRenderer(sr, com.notemove.core.dsp.SampleBank())
        val loop = r.renderClipLoop(p, drum.id, 0)!!
        assertEquals(sr * 2, loop.left.size) // 4 beats at 120 bpm
        assertTrue(rms(loop.left) > 1e-3)
        val song = r.renderSong(p)
        assertTrue(song.left.size > sr * 2)
        val wav = Wav.bytes(sr, loop.left, loop.right)
        val back = Wav.readMono(ByteArrayInputStream(wav))
        assertEquals(sr, back.sampleRate)
        assertEquals(loop.left.size, back.mono.size)
        val mid = MidiFileWriter.song(p)
        assertEquals("MThd", String(mid, 0, 4))
        assertNotNull(ProjectJson.decode(ProjectJson.encode(p)).tracks[0].clips[0])
        assertEquals(p, ProjectJson.decode(ProjectJson.encode(p)))
    }
}
