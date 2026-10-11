package com.spectraseq.core.model

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/**
 * A clip placed on the song timeline (Live's Arrangement View). It holds its own copy of the notes, so
 * editing the session clip afterwards doesn't change the recorded song. Longer than [Clip.lengthBeats]
 * means the clip loops; [offset] is where in the clip playback starts.
 */
@Serializable
data class SongClip(
    val id: String = newId(),
    val trackId: String,
    /** Position on the timeline, in beats. */
    val start: Double,
    val length: Double,
    val clip: Clip,
    val offset: Double = 0.0,
) {
    val end: Double get() = start + length

    /** Clip-local position for a song beat inside this clip. */
    fun localBeat(songBeat: Double): Double {
        val len = clip.lengthBeats
        val l = (songBeat - start + offset) % len
        return if (l < 0) l + len else l
    }
}

/** The song: clips on per-track lanes plus a loop region. */
@Serializable
data class Arrangement(
    val clips: List<SongClip> = emptyList(),
    val loopStart: Double = 0.0,
    val loopEnd: Double = 16.0,
    val loop: Boolean = false,
) {
    /** End of the last clip, in beats (0 when empty). */
    val length: Double get() = clips.maxOfOrNull { it.end } ?: 0.0
    val isEmpty: Boolean get() = clips.isEmpty()

    fun clipsOn(trackId: String): List<SongClip> = clips.filter { it.trackId == trackId }.sortedBy { it.start }

    fun clipAt(trackId: String, beat: Double): SongClip? = clips.firstOrNull { it.trackId == trackId && beat >= it.start && beat < it.end }
}

/** Editing operations on the song. All return a new [Arrangement]; lengths never go below [MIN_LENGTH]. */
object ArrangementOps {
    const val MIN_LENGTH = 0.25

    /** Places [clip] on its lane, cutting away whatever it overlaps there (like recording over it in Live). */
    fun place(a: Arrangement, clip: SongClip): Arrangement {
        val kept = a.clips.flatMap { c -> if (c.trackId != clip.trackId) listOf(c) else cutOut(c, clip.start, clip.end) }
        return a.copy(clips = kept + clip)
    }

    /** What remains of [c] after removing the span [from, to). */
    fun cutOut(c: SongClip, from: Double, to: Double): List<SongClip> {
        if (to <= c.start || from >= c.end) return listOf(c)
        val out = ArrayList<SongClip>(2)
        if (from > c.start + 1e-9) out.add(c.copy(length = from - c.start))
        if (to < c.end - 1e-9) {
            val cut = to - c.start
            out.add(c.copy(id = newId(), start = to, length = c.end - to, offset = (c.offset + cut) % c.clip.lengthBeats))
        }
        return out.filter { it.length >= 1e-6 }
    }

    fun move(a: Arrangement, ids: Set<String>, deltaBeats: Double, trackFor: (SongClip) -> String = { it.trackId }): Arrangement {
        val minStart = a.clips.filter { it.id in ids }.minOfOrNull { it.start } ?: return a
        val d = max(deltaBeats, -minStart)
        return a.copy(clips = a.clips.map { if (it.id in ids) it.copy(start = it.start + d, trackId = trackFor(it)) else it })
    }

    /** Moves the end ([right]) or the start (left, keeping the end and the audio in place) of the given clips. */
    fun resize(a: Arrangement, ids: Set<String>, deltaBeats: Double, right: Boolean): Arrangement = a.copy(
        clips = a.clips.map { c ->
            if (c.id !in ids) c
            else if (right) c.copy(length = max(MIN_LENGTH, c.length + deltaBeats))
            else {
                val ns = (c.start + deltaBeats).coerceIn(0.0, c.end - MIN_LENGTH)
                val shift = ns - c.start
                val len = c.clip.lengthBeats
                c.copy(start = ns, length = c.end - ns, offset = ((c.offset + shift) % len + len) % len)
            }
        },
    )

    fun split(a: Arrangement, id: String, atBeat: Double): Arrangement {
        val c = a.clips.firstOrNull { it.id == id } ?: return a
        if (atBeat <= c.start + MIN_LENGTH / 2 || atBeat >= c.end - MIN_LENGTH / 2) return a
        val left = c.copy(length = atBeat - c.start)
        val right = c.copy(id = newId(), start = atBeat, length = c.end - atBeat, offset = (c.offset + (atBeat - c.start)) % c.clip.lengthBeats)
        return a.copy(clips = a.clips.filter { it.id != id } + left + right)
    }

    /** Copies the clips right after the selection (as a block), returning the new arrangement and the copies' ids. */
    fun duplicate(a: Arrangement, ids: Set<String>): Pair<Arrangement, Set<String>> {
        val sel = a.clips.filter { it.id in ids }
        if (sel.isEmpty()) return a to emptySet()
        val span = sel.maxOf { it.end } - sel.minOf { it.start }
        var out = a
        val newIds = HashSet<String>()
        for (c in sel) {
            val copy = c.copy(id = newId(), start = c.start + span)
            newIds.add(copy.id)
            out = place(out, copy)
        }
        return out to newIds
    }

    fun delete(a: Arrangement, ids: Set<String>): Arrangement = a.copy(clips = a.clips.filter { it.id !in ids })

    /** Removes everything in [from, to) on every lane and closes the gap (Live's "Delete Time"). */
    fun deleteTime(a: Arrangement, from: Double, to: Double): Arrangement {
        val gap = to - from
        if (gap <= 0) return a
        val clips = a.clips.flatMap { cutOut(it, from, to) }.map { if (it.start >= to - 1e-9) it.copy(start = it.start - gap) else it }
        return a.copy(clips = clips)
    }

    /** Clips that touch the span, for the selection rectangle. */
    fun inSpan(a: Arrangement, trackIds: Set<String>, from: Double, to: Double): List<SongClip> =
        a.clips.filter { it.trackId in trackIds && it.start < max(from, to) && it.end > min(from, to) }

    /**
     * Turns song-recording events (clip [scene] of a track played from start to end) into song clips, each
     * holding a copy of the clip's notes as they are now (so notes overdubbed during the take are included).
     */
    fun fromRecording(a: Arrangement, project: Project, events: List<com.spectraseq.core.engine.SongEvent>): Arrangement {
        var out = a
        for (e in events.sortedBy { it.start }) {
            val clip = project.track(e.trackId)?.clips?.get(e.scene) ?: continue
            if (e.end - e.start < 1e-3) continue
            out = place(out, SongClip(trackId = e.trackId, start = e.start, length = e.end - e.start, clip = clip.copy(id = newId()), offset = e.offset))
        }
        return out
    }

    fun setLoop(a: Arrangement, start: Double, end: Double, on: Boolean = a.loop): Arrangement =
        a.copy(loopStart = max(0.0, min(start, end)), loopEnd = max(min(start, end) + MIN_LENGTH, max(start, end)), loop = on)
}
