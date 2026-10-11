package com.spectraseq.core.export

import com.spectraseq.core.dsp.SampleBank
import com.spectraseq.core.dsp.SoundFontBank
import com.spectraseq.core.engine.AudioEngine
import com.spectraseq.core.model.LaunchQuantization
import com.spectraseq.core.model.Project

/** Renders audio faster than real time using the same engine as live playback. */
class OfflineRenderer(private val sampleRate: Int, private val samples: SampleBank, private val soundFonts: SoundFontBank = SoundFontBank()) {

    class Stereo(val left: FloatArray, val right: FloatArray)

    /**
     * Renders one clip of [trackId] as a seamless loop: the clip plays twice and the second pass is
     * kept, so reverb and delay tails wrap around the loop point like they do in the app.
     */
    fun renderClipLoop(project: Project, trackId: String, scene: Int): Stereo? {
        val track = project.track(trackId) ?: return null
        val clip = track.clips[scene] ?: return null
        val solo = project.copy(tracks = listOf(track.copy(mute = false, solo = false)))
        val frames = beatsToFrames(project, clip.lengthBeats)
        val engine = AudioEngine(sampleRate, samples, soundFonts)
        engine.setProject(solo)
        engine.launchClip(trackId, scene)
        renderFrames(engine, frames) // first pass, discarded
        return renderFrames(engine, frames)
    }

    /** Renders the used scenes in order (each [repeats] times) plus a short tail. */
    fun renderSong(project: Project, repeats: Int = 1, tailSeconds: Double = 2.0, onProgress: (Float) -> Unit = {}): Stereo {
        val scenes = project.usedScenes()
        val engine = AudioEngine(sampleRate, samples, soundFonts)
        // Scene changes are placed exactly by the renderer, so launches must not wait for the next bar.
        engine.setProject(project.copy(launchQuantization = LaunchQuantization.NONE))
        val parts = ArrayList<Stereo>()
        scenes.forEachIndexed { i, s ->
            engine.launchScene(s)
            val frames = beatsToFrames(project, project.sceneLength(s) * repeats)
            parts.add(renderFrames(engine, frames))
            onProgress((i + 1f) / (scenes.size + 1))
        }
        engine.stop()
        parts.add(renderFrames(engine, (tailSeconds * sampleRate).toInt()))
        onProgress(1f)
        val total = parts.sumOf { it.left.size }
        val l = FloatArray(total); val r = FloatArray(total)
        var o = 0
        for (p in parts) { System.arraycopy(p.left, 0, l, o, p.left.size); System.arraycopy(p.right, 0, r, o, p.right.size); o += p.left.size }
        return Stereo(l, r)
    }

    /**
     * Renders the song timeline from [fromBeat] to [toBeat] (default: the whole song, or the loop region when
     * [loopOnly]) plus a tail. Returns null when the song is empty.
     */
    fun renderArrangement(
        project: Project,
        loopOnly: Boolean = false,
        tailSeconds: Double = 2.0,
        onProgress: (Float) -> Unit = {},
    ): Stereo? {
        val arr = project.arrangement
        if (arr.isEmpty) return null
        val from = if (loopOnly) arr.loopStart else 0.0
        val to = if (loopOnly) arr.loopEnd else arr.length
        if (to <= from) return null
        val engine = AudioEngine(sampleRate, samples, soundFonts)
        // Looping is for listening; a render plays straight through.
        engine.setProject(project.copy(arrangement = arr.copy(loop = false)))
        engine.playSong(from)
        val total = beatsToFrames(project, to - from)
        val out = renderFramesProgress(engine, total) { onProgress(it * 0.95f) }
        engine.stop()
        val tail = renderFrames(engine, (tailSeconds * sampleRate).toInt())
        onProgress(1f)
        return concat(listOf(out, tail))
    }

    /** One render per track (soloed, with its effects and sends) over the same span as [renderArrangement] / [renderSong]. */
    fun renderStems(project: Project, useSong: Boolean, loopOnly: Boolean = false, onProgress: (Float) -> Unit = {}): List<Pair<String, Stereo>> {
        val tracks = project.tracks
        return tracks.mapIndexedNotNull { i, t ->
            val solo = project.copy(tracks = project.tracks.map { it.copy(solo = it.id == t.id, mute = false) })
            val part: (Float) -> Unit = { f -> onProgress((i + f) / tracks.size) }
            val audio = if (useSong) renderArrangement(solo, loopOnly, onProgress = part) else renderSong(solo, onProgress = part)
            audio?.let { t.name to it }
        }
    }

    private fun renderFramesProgress(engine: AudioEngine, frames: Int, onProgress: (Float) -> Unit): Stereo {
        val l = FloatArray(frames); val r = FloatArray(frames)
        val chunk = 4096
        var done = 0
        while (done < frames) {
            val n = minOf(chunk, frames - done)
            val part = renderFrames(engine, n)
            System.arraycopy(part.left, 0, l, done, n); System.arraycopy(part.right, 0, r, done, n)
            done += n
            onProgress(done.toFloat() / frames)
        }
        return Stereo(l, r)
    }

    private fun concat(parts: List<Stereo>): Stereo {
        val total = parts.sumOf { it.left.size }
        val l = FloatArray(total); val r = FloatArray(total)
        var o = 0
        for (p in parts) { System.arraycopy(p.left, 0, l, o, p.left.size); System.arraycopy(p.right, 0, r, o, p.right.size); o += p.left.size }
        return Stereo(l, r)
    }

    private fun beatsToFrames(project: Project, beats: Double) = (beats * 60.0 / project.tempo * sampleRate).toInt()

    private fun renderFrames(engine: AudioEngine, frames: Int): Stereo {
        val l = FloatArray(frames); val r = FloatArray(frames)
        val chunk = 512
        val bl = FloatArray(chunk); val br = FloatArray(chunk)
        var done = 0
        while (done < frames) {
            val n = minOf(chunk, frames - done)
            engine.render(bl, br, n)
            System.arraycopy(bl, 0, l, done, n); System.arraycopy(br, 0, r, done, n)
            done += n
        }
        return Stereo(l, r)
    }
}
