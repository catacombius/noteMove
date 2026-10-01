package com.notemove.core.export

import com.notemove.core.model.Clip
import com.notemove.core.model.Project
import com.notemove.core.model.TrackKind
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Standard MIDI File (type 1) writer. */
object MidiFileWriter {
    private const val PPQ = 480

    private data class Ev(val tick: Int, val order: Int, val bytes: ByteArray)

    /** The whole project as a song: used scenes in order, each repeated [repeats] times, one MIDI track per track. */
    fun song(project: Project, repeats: Int = 1): ByteArray {
        val tracks = ArrayList<ByteArray>()
        tracks.add(tempoTrack(project))
        val scenes = project.usedScenes()
        project.tracks.forEachIndexed { ti, track ->
            val events = ArrayList<Ev>()
            var offset = 0.0
            for (s in scenes) {
                val sceneLen = project.sceneLength(s) * repeats
                track.clips[s]?.let { clip -> addLooped(events, clip, offset, sceneLen, channel(track.kind, ti)) }
                offset += sceneLen
            }
            tracks.add(trackChunk(track.name, events))
        }
        return file(tracks)
    }

    /** A single clip as a type-1 file with one note track. */
    fun clip(project: Project, clip: Clip, name: String, kind: TrackKind): ByteArray {
        val events = ArrayList<Ev>()
        addLooped(events, clip, 0.0, clip.lengthBeats, channel(kind, 0))
        return file(listOf(tempoTrack(project), trackChunk(name, events)))
    }

    private fun channel(kind: TrackKind, index: Int) = if (kind == TrackKind.DRUMS) 9 else (index % 15).let { if (it >= 9) it + 1 else it }

    private fun addLooped(events: MutableList<Ev>, clip: Clip, offset: Double, span: Double, ch: Int) {
        var loopStart = 0.0
        while (loopStart < span - 1e-9) {
            for (n in clip.notes) {
                val start = loopStart + n.start
                if (start >= span) continue
                val end = minOf(start + n.duration, span, loopStart + clip.lengthBeats + n.duration)
                val on = tick(offset + start)
                val off = maxOf(on + 1, tick(offset + end))
                events.add(Ev(on, 1, byteArrayOf((0x90 or ch).toByte(), n.pitch.toByte(), n.velocity.coerceIn(1, 127).toByte())))
                events.add(Ev(off, 0, byteArrayOf((0x80 or ch).toByte(), n.pitch.toByte(), 64)))
            }
            loopStart += clip.lengthBeats
        }
    }

    private fun tick(beat: Double) = (beat * PPQ).roundToInt()

    private fun tempoTrack(project: Project): ByteArray {
        val mpqn = (60_000_000.0 / project.tempo).roundToInt()
        val ev = listOf(
            Ev(0, 0, byteArrayOf(0xFF.toByte(), 0x03) + vlq(project.name.toByteArray().size) + project.name.toByteArray()),
            Ev(0, 0, byteArrayOf(0xFF.toByte(), 0x51, 0x03, (mpqn shr 16).toByte(), (mpqn shr 8).toByte(), mpqn.toByte())),
            Ev(0, 0, byteArrayOf(0xFF.toByte(), 0x58, 0x04, 4, 2, 24, 8)),
        )
        return chunk(ev)
    }

    private fun trackChunk(name: String, events: List<Ev>): ByteArray {
        val nameBytes = name.toByteArray()
        val all = listOf(Ev(0, -1, byteArrayOf(0xFF.toByte(), 0x03) + vlq(nameBytes.size) + nameBytes)) +
            events.sortedWith(compareBy({ it.tick }, { it.order }))
        return chunk(all)
    }

    private fun chunk(events: List<Ev>): ByteArray {
        val body = ByteArrayOutputStream()
        var last = 0
        for (e in events) {
            body.write(vlq(e.tick - last)); body.write(e.bytes); last = e.tick
        }
        body.write(vlq(0)); body.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        val b = body.toByteArray()
        return "MTrk".toByteArray() + int32(b.size) + b
    }

    private fun file(tracks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("MThd".toByteArray())
        out.write(int32(6))
        out.write(int16(1)); out.write(int16(tracks.size)); out.write(int16(PPQ))
        tracks.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun vlq(value: Int): ByteArray {
        var v = value.coerceAtLeast(0)
        val bytes = ArrayList<Byte>()
        bytes.add((v and 0x7F).toByte())
        v = v shr 7
        while (v > 0) { bytes.add(0, ((v and 0x7F) or 0x80).toByte()); v = v shr 7 }
        return bytes.toByteArray()
    }
}
