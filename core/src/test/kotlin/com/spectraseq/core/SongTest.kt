package com.spectraseq.core

import com.spectraseq.core.engine.AudioEngine
import com.spectraseq.core.export.AudioTools
import com.spectraseq.core.export.OfflineRenderer
import com.spectraseq.core.export.ProjectJson
import com.spectraseq.core.dsp.SampleBank
import com.spectraseq.core.model.Arrangement
import com.spectraseq.core.model.ArrangementOps
import com.spectraseq.core.model.Clip
import com.spectraseq.core.model.LaunchQuantization
import com.spectraseq.core.model.Note
import com.spectraseq.core.model.Project
import com.spectraseq.core.model.SongClip
import com.spectraseq.core.model.SynthPresets
import com.spectraseq.core.model.Track
import com.spectraseq.core.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SongTest {
    private val sr = 48000
    private val synth = Track(name = "S", kind = TrackKind.SYNTH, synth = SynthPresets.PLUCK)

    private fun clip(len: Double, vararg starts: Double) = Clip(lengthBeats = len, notes = starts.map { Note(60, it, 0.25, 110) })

    /** Render [beats] at 120 BPM and return the frames where the signal first rises after silence. */
    private fun onsets(e: AudioEngine, beats: Double): List<Double> {
        val frames = (beats * 0.5 * sr).toInt()
        val l = FloatArray(frames); val r = FloatArray(frames)
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            val bl = FloatArray(n); val br = FloatArray(n)
            e.render(bl, br, n)
            System.arraycopy(bl, 0, l, done, n); done += n
        }
        val out = ArrayList<Double>()
        var quiet = 0
        for (i in l.indices) {
            if (abs(l[i]) > 0.02f) { if (quiet > sr / 20) out.add(i / (0.5 * sr)); quiet = 0 } else quiet++
        }
        return out
    }

    @Test
    fun songPlaysSnapshotNotesAtTheirBeats() {
        // Clip with one note per bar start, placed at beat 4 for 4 beats; also the clip loops (length 2 -> plays twice).
        val sc = SongClip(trackId = synth.id, start = 4.0, length = 4.0, clip = clip(2.0, 0.0))
        val p = Project(name = "p", tracks = listOf(synth), arrangement = Arrangement(listOf(sc)))
        val e = AudioEngine(sr)
        e.setProject(p)
        e.playSong(0.0)
        val hits = onsets(e, 10.0)
        assertEquals("onsets $hits", 2, hits.size)
        assertEquals(4.0, hits[0], 0.05)
        assertEquals(6.0, hits[1], 0.05)
    }

    @Test
    fun recordingLaunchesBuildsSongClips() {
        val t = synth.copy(clips = mapOf(0 to clip(4.0, 0.0), 1 to clip(4.0, 0.0, 1.0)))
        val p = Project(name = "p", tracks = listOf(t), launchQuantization = LaunchQuantization.BAR)
        val e = AudioEngine(sr)
        e.setProject(p)
        e.setSongRecord(true)
        e.launchClip(t.id, 0)          // starts transport, scene 0 from beat 0
        onsets(e, 2.0)
        e.launchClip(t.id, 1)          // switches at the next bar (beat 4)
        onsets(e, 8.0)                 // now at beat 10
        e.stop()
        onsets(e, 0.5)
        val events = e.drainSongEvents()
        assertEquals(2, events.size)
        assertEquals(0, events[0].scene); assertEquals(0.0, events[0].start, 1e-6); assertEquals(4.0, events[0].end, 1e-6)
        assertEquals(1, events[1].scene); assertEquals(4.0, events[1].start, 1e-6); assertTrue(events[1].end > 9.9)
        val song = ArrangementOps.fromRecording(Arrangement(), p, events)
        assertEquals(2, song.clips.size)
        assertEquals(2, song.clips.first { it.start == 4.0 }.clip.notes.size)
    }

    @Test
    fun opsPlaceSplitResizeDuplicate() {
        val c = SongClip(trackId = "t", start = 0.0, length = 8.0, clip = clip(4.0, 0.0))
        var a = Arrangement(listOf(c))
        a = ArrangementOps.place(a, SongClip(trackId = "t", start = 2.0, length = 2.0, clip = clip(1.0)))
        assertEquals(3, a.clips.size) // left part, new clip, right part
        val right = a.clips.first { it.start == 4.0 }
        assertEquals(0.0, right.offset, 1e-9) // 4 beats into a 4-beat clip wraps to 0
        a = ArrangementOps.split(a, right.id, 6.0)
        assertEquals(4, a.clips.size)
        val (dup, ids) = ArrangementOps.duplicate(a, setOf(a.clips.first { it.start == 6.0 }.id))
        assertEquals(1, ids.size)
        assertEquals(8.0, dup.clips.first { it.id in ids }.start, 1e-9)
        val resized = ArrangementOps.resize(dup, ids, -1.0, right = false)
        val r = resized.clips.first { it.id in ids }
        assertEquals(7.0, r.start, 1e-9); assertEquals(3.0, r.length, 1e-9)
        assertEquals(10.0, resized.length, 1e-9)
        val p = Project(name = "x", tracks = emptyList(), arrangement = resized)
        assertEquals(p, ProjectJson.decode(ProjectJson.encode(p)))
    }

    @Test
    fun rendersSongAndStems() {
        val drums = Track(name = "D", kind = TrackKind.DRUMS, drumKit = com.spectraseq.core.model.DrumKits.KIT_808)
        val arr = Arrangement(
            listOf(
                SongClip(trackId = synth.id, start = 0.0, length = 4.0, clip = clip(4.0, 0.0, 1.0, 2.0, 3.0)),
                SongClip(trackId = drums.id, start = 0.0, length = 4.0, clip = Clip(lengthBeats = 4.0, notes = listOf(Note(36, 0.0, 0.25), Note(38, 1.0, 0.25)))),
            ),
        )
        val p = Project(name = "p", tracks = listOf(synth, drums), arrangement = arr)
        val r = OfflineRenderer(sr, SampleBank())
        val song = r.renderArrangement(p, tailSeconds = 1.0)
        assertNotNull(song)
        assertEquals(2 * sr + sr, song!!.left.size) // 4 beats at 120 BPM + 1 s tail
        assertTrue(AudioTools.peak(song) > 0.05f)
        val stems = r.renderStems(p, useSong = true)
        assertEquals(listOf("S", "D"), stems.map { it.first })
        stems.forEach { assertTrue(it.first, AudioTools.peak(it.second) > 0.01f) }
        val n = AudioTools.normalize(song, -1f)
        assertEquals(0.891f, AudioTools.peak(n), 0.01f)
        val down = AudioTools.resample(song, 48000, 44100)
        assertEquals((song.left.size * 44100.0 / 48000).toInt(), down.left.size)
    }
}
