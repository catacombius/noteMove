package com.notemove.core.export

import com.notemove.core.model.Clip
import com.notemove.core.model.Project
import com.notemove.core.model.Track
import com.notemove.core.model.TrackColors
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * Writes an Ableton Live Set (.als, gzipped XML, Live 11 schema; opens in Live 11 and 12).
 *
 * Every track becomes a MIDI track. Clips sit in the Session View slots matching their scene;
 * optionally scenes are also laid out one after another in the Arrangement View.
 * Drum tracks use Drum Rack note numbers (pad 1 = C1), so dropping any Drum Rack on the track plays the beat.
 */
class LiveSetExporter(private val templates: Templates = Templates.fromResources()) {

    class Templates(val head: String, val track: String, val clip: String, val scene: String, val tail: String) {
        companion object {
            fun fromResources(): Templates {
                fun load(name: String): String =
                    LiveSetExporter::class.java.getResourceAsStream("/als/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("Missing Live Set template $name")
                return Templates(load("head.xml"), load("miditrack.xml"), load("midiclip.xml"), load("scene.xml"), load("tail.xml"))
            }
        }
    }

    data class Options(
        /** Also place scenes sequentially in the Arrangement View. */
        val arrangement: Boolean = true,
        /** How many times each scene is repeated in the arrangement. */
        val arrangementRepeats: Int = 2,
    )

    fun export(project: Project, out: OutputStream, options: Options = Options()) {
        GZIPOutputStream(out).use { gz -> gz.write(buildXml(project, options).toByteArray(Charsets.UTF_8)) }
    }

    fun exportBytes(project: Project, options: Options = Options()): ByteArray =
        ByteArrayOutputStream().also { export(project, it, options) }.toByteArray()

    fun buildXml(project: Project, options: Options = Options()): String {
        val ids = IdCounter(40_000)
        val sceneCount = maxOf(project.sceneCount, (project.tracks.flatMap { it.clips.keys }.maxOrNull() ?: -1) + 1, 1)
        val sb = StringBuilder(64 * 1024 + project.tracks.size * 32 * 1024)

        // Arrangement timeline: each used scene, in order, for its longest clip × repeats.
        val sceneTimes = LinkedHashMap<Int, Pair<Double, Double>>() // scene -> (start, length)
        var t = 0.0
        for (s in project.usedScenes()) {
            val len = project.sceneLength(s) * options.arrangementRepeats.coerceAtLeast(1)
            sceneTimes[s] = t to len
            t += len
        }

        val tracksXml = StringBuilder()
        project.tracks.forEachIndexed { index, track ->
            tracksXml.append(trackXml(project, track, index, sceneCount, sceneTimes, options, ids)).append('\n')
        }
        val scenesXml = StringBuilder()
        for (s in 0 until sceneCount) {
            scenesXml.append(
                templates.scene
                    .replace("{{SCENE_ID}}", s.toString())
                    .replace("{{NAME}}", xmlEscape(project.sceneNames[s] ?: ""))
                    // Scene tempo is disabled (IsTempoEnabled=false); Live stores it as a whole number.
                    .replace("{{TEMPO}}", Math.round(project.tempo).toString()),
            ).append('\n')
        }
        val tail = templates.tail
            .replace("{{SCENES}}", scenesXml.toString().trimEnd())
            .replace("{{TEMPO}}", num(project.tempo))
            .replace("{{ROOT_NOTE}}", project.rootNote.toString())
            .replace("{{SCALE_NAME}}", xmlEscape(project.scale.liveName))
        sb.append(templates.head.trimEnd()).append('\n').append(tracksXml).append(tail)
        return sb.toString().replace("{{NEXT_POINTEE_ID}}", (ids.peek() + 1).toString())
    }

    private fun trackXml(
        project: Project, track: Track, index: Int, sceneCount: Int,
        sceneTimes: Map<Int, Pair<Double, Double>>, options: Options, ids: IdCounter,
    ): String {
        val color = TrackColors.liveIndex(track.color)
        val slots = StringBuilder()
        for (s in 0 until sceneCount) {
            val clip = track.clips[s]
            slots.append("\t\t\t\t\t\t\t<ClipSlot Id=\"").append(s).append("\">\n")
                .append("\t\t\t\t\t\t\t\t<LomId Value=\"0\"/>\n\t\t\t\t\t\t\t\t<ClipSlot>\n")
            if (clip == null) slots.append("\t\t\t\t\t\t\t\t\t<Value/>\n")
            else slots.append("\t\t\t\t\t\t\t\t\t<Value>\n")
                .append(clipXml(project, clip, color, 0, 0.0, clip.lengthBeats, true, clip.name))
                .append("\n\t\t\t\t\t\t\t\t\t</Value>\n")
            slots.append("\t\t\t\t\t\t\t\t</ClipSlot>\n\t\t\t\t\t\t\t\t<HasStop Value=\"true\"/>\n")
                .append("\t\t\t\t\t\t\t\t<NeedRefreeze Value=\"true\"/>\n\t\t\t\t\t\t\t</ClipSlot>\n")
        }
        val freezeSlots = StringBuilder()
        for (s in 0 until sceneCount) {
            freezeSlots.append("\t\t\t\t\t\t\t<ClipSlot Id=\"").append(s).append("\">\n")
                .append("\t\t\t\t\t\t\t\t<LomId Value=\"0\"/>\n\t\t\t\t\t\t\t\t<ClipSlot>\n\t\t\t\t\t\t\t\t\t<Value/>\n")
                .append("\t\t\t\t\t\t\t\t</ClipSlot>\n\t\t\t\t\t\t\t\t<HasStop Value=\"true\"/>\n")
                .append("\t\t\t\t\t\t\t\t<NeedRefreeze Value=\"true\"/>\n\t\t\t\t\t\t\t</ClipSlot>\n")
        }
        val arrangement = StringBuilder()
        if (options.arrangement) {
            var clipId = 0
            for ((scene, span) in sceneTimes) {
                val clip = track.clips[scene] ?: continue
                arrangement.append(clipXml(project, clip, color, clipId++, span.first, span.second, false, clip.name)).append('\n')
            }
        }
        var xml = templates.track
            .replace("{{TRACK_ID}}", (10 + index).toString())
            .replace("{{NAME}}", xmlEscape(track.name))
            .replace("{{COLOR}}", color.toString())
            .replace("{{SPEAKER_ON}}", if (track.mute) "false" else "true")
            .replace("{{PAN}}", num(track.pan.toDouble()))
            .replace("{{VOLUME}}", num(liveVolume(track.volume)))
            .replace("{{CLIPSLOTS}}", slots.toString().trimEnd())
            .replace("{{FREEZE_CLIPSLOTS}}", freezeSlots.toString().trimEnd())
            .replace("{{ARRANGEMENT_CLIPS}}", arrangement.toString().trimEnd())
        xml = ids.fill(xml)
        return xml
    }

    /**
     * A MIDI clip. Session clips loop over their own length; arrangement clips span [length] beats
     * starting at [time] and loop the clip content inside that span.
     */
    private fun clipXml(project: Project, clip: Clip, color: Int, id: Int, time: Double, length: Double, session: Boolean, name: String): String {
        val keyTracks = StringBuilder()
        var noteId = 1
        clip.notes.groupBy { it.pitch }.toSortedMap().entries.forEachIndexed { i, (pitch, notes) ->
            keyTracks.append("\t\t\t\t<KeyTrack Id=\"").append(i).append("\">\n\t\t\t\t\t<Notes>\n")
            for (n in notes.sortedBy { it.start }) {
                keyTracks.append("\t\t\t\t\t\t<MidiNoteEvent Time=\"").append(num(n.start))
                    .append("\" Duration=\"").append(num(n.duration.coerceAtLeast(0.001)))
                    .append("\" Velocity=\"").append(n.velocity.coerceIn(1, 127))
                    .append("\" VelocityDeviation=\"0\" OffVelocity=\"64\" Probability=\"1\" IsEnabled=\"true\" NoteId=\"")
                    .append(noteId++).append("\"/>\n")
            }
            keyTracks.append("\t\t\t\t\t</Notes>\n\t\t\t\t\t<MidiKey Value=\"").append(pitch).append("\"/>\n\t\t\t\t</KeyTrack>\n")
        }
        val start = if (session) 0.0 else time
        val end = if (session) clip.lengthBeats else time + length
        return templates.clip
            .replace("{{CLIP_ID}}", id.toString())
            .replace("{{TIME}}", num(start))
            .replace("{{CURRENT_START}}", num(start))
            .replace("{{CURRENT_END}}", num(end))
            .replace("{{LENGTH}}", num(clip.lengthBeats))
            .replace("{{LOOP_ON}}", "true")
            .replace("{{NAME}}", xmlEscape(name))
            .replace("{{COLOR}}", color.toString())
            .replace("{{KEY_TRACKS}}", keyTracks.toString().trimEnd())
            .replace("{{NEXT_NOTE_ID}}", noteId.toString())
            .replace("{{ROOT_NOTE}}", project.rootNote.toString())
            .replace("{{SCALE_NAME}}", xmlEscape(project.scale.liveName))
            .replace("{{IN_KEY}}", "false")
            .prependIndent("\t\t\t\t\t\t\t\t\t\t")
    }

    /** Hands out unique ids for automation / modulation targets (the template marks them with "@@"). */
    private class IdCounter(private var next: Int) {
        fun peek() = next
        fun fill(xml: String): String {
            val sb = StringBuilder(xml.length + 1024)
            var i = 0
            while (true) {
                val j = xml.indexOf("Id=\"@@\"", i)
                if (j < 0) { sb.append(xml, i, xml.length); break }
                sb.append(xml, i, j).append("Id=\"").append(next++).append('"')
                i = j + 7
            }
            return sb.toString()
        }
    }

    companion object {
        /** Live's mixer volume is linear gain (1.0 = 0 dB). Our faders use a squared curve. */
        fun liveVolume(fader: Float): Double = (fader * fader / 0.64).coerceIn(0.0003162278, 1.99526)

        fun num(v: Double): String {
            if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
            return String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
        }

        fun xmlEscape(s: String): String = buildString {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c.code >= 0x20 || c == '\t') append(c)
            }
        }
    }
}
