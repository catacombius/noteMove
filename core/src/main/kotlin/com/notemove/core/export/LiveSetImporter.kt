package com.notemove.core.export

import com.notemove.core.model.Clip
import com.notemove.core.model.DrumKits
import com.notemove.core.model.Note
import com.notemove.core.model.Project
import com.notemove.core.model.Scale
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.Track
import com.notemove.core.model.TrackColors
import com.notemove.core.model.TrackKind
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * Reads the MIDI content of an Ableton Live Set (.als, Live 10–12) back into a project, so a set
 * that went to the computer can come back to the phone. Audio tracks and devices are skipped.
 */
object LiveSetImporter {

    class ImportException(message: String) : Exception(message)

    fun import(input: InputStream, fallbackName: String): Project {
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val b0 = buffered.read(); val b1 = buffered.read()
        buffered.reset()
        val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { isExpandEntityReferences = false }
        }
        val doc = try { factory.newDocumentBuilder().parse(stream) } catch (e: Exception) {
            throw ImportException("Not a readable Live Set: ${e.message}")
        }
        val liveSet = doc.documentElement.child("LiveSet") ?: throw ImportException("Not a Live Set (no LiveSet element)")

        val master = liveSet.child("MasterTrack") ?: liveSet.child("MainTrack")
        val tempo = master?.path("DeviceChain", "Mixer", "Tempo", "Manual")?.value()?.toDoubleOrNull() ?: 120.0
        val scaleName = liveSet.path("ScaleInformation", "Name")?.value()
        val root = liveSet.path("ScaleInformation", "RootNote")?.value()?.toIntOrNull() ?: 0
        val scale = Scale.entries.firstOrNull { it.liveName.equals(scaleName, ignoreCase = true) } ?: Scale.MINOR

        data class Raw(val name: String, val color: Int, val drums: Boolean, val session: Map<Int, Clip>, val arrangement: List<Pair<Double, Clip>>)

        val raws = ArrayList<Raw>()
        val tracks = liveSet.child("Tracks") ?: throw ImportException("Live Set has no tracks")
        for (t in tracks.children()) {
            if (t.tagName != "MidiTrack") continue
            val name = t.path("Name", "UserName")?.value()?.takeIf { it.isNotBlank() }
                ?: t.path("Name", "EffectiveName")?.value()?.takeIf { it.isNotBlank() }
                ?: "MIDI ${raws.size + 1}"
            val liveColor = t.child("Color")?.value()?.toIntOrNull() ?: 0
            val seq = t.path("DeviceChain", "MainSequencer")
            val session = LinkedHashMap<Int, Clip>()
            seq?.child("ClipSlotList")?.children()?.forEachIndexed { i, slot ->
                val clip = slot.path("ClipSlot", "Value", "MidiClip")
                if (clip != null) readClip(clip)?.let { session[i] = it }
            }
            val arrangement = ArrayList<Pair<Double, Clip>>()
            seq?.path("ClipTimeable", "ArrangerAutomation", "Events")?.children()?.forEach { c ->
                if (c.tagName == "MidiClip") readClip(c)?.let { arrangement.add((c.getAttribute("Time").toDoubleOrNull() ?: 0.0) to it) }
            }
            val hasDrumRack = t.getElementsByTagName("DrumGroupDevice").length > 0
            val lname = name.lowercase()
            val drums = hasDrumRack || listOf("drum", "kit", "beat", "perc", "808", "909").any { lname.contains(it) }
            raws.add(Raw(name, liveColor, drums, session, arrangement.sortedBy { it.first }))
        }
        if (raws.isEmpty()) throw ImportException("This Live Set has no MIDI tracks")

        val useArrangement = raws.all { it.session.isEmpty() } && raws.any { it.arrangement.isNotEmpty() }
        // When only the arrangement has content, each distinct arrangement start time becomes a scene.
        val starts = if (useArrangement) raws.flatMap { r -> r.arrangement.map { it.first } }.distinct().sorted() else emptyList()

        val result = raws.take(Project.MAX_TRACKS).mapIndexed { i, r ->
            val clips: Map<Int, Clip> = if (useArrangement) {
                r.arrangement.associate { (time, clip) -> starts.indexOf(time) to clip }.filterKeys { it in 0 until Project.MAX_SCENES }
            } else r.session.filterKeys { it in 0 until Project.MAX_SCENES }
            val colorIdx = TrackColors.ALL.indices.minByOrNull { abs(TrackColors.ALL[it].liveIndex - r.color) } ?: i
            if (r.drums) Track(name = r.name, kind = TrackKind.DRUMS, color = colorIdx, drumKit = DrumKits.KIT_808, clips = clips)
            else Track(name = r.name, kind = TrackKind.SYNTH, color = colorIdx, synth = SynthPresets.ALL[i % SynthPresets.ALL.size], clips = clips)
        }
        val sceneCount = (result.flatMap { it.clips.keys }.maxOrNull() ?: 0) + 1
        return Project(
            name = fallbackName,
            tempo = tempo.coerceIn(20.0, 999.0),
            rootNote = root.coerceIn(0, 11),
            scale = scale,
            tracks = result,
            sceneCount = sceneCount.coerceIn(8, Project.MAX_SCENES),
        )
    }

    private fun readClip(clip: Element): Clip? {
        val loop = clip.child("Loop")
        val loopStart = loop?.child("LoopStart")?.value()?.toDoubleOrNull() ?: 0.0
        val loopEnd = loop?.child("LoopEnd")?.value()?.toDoubleOrNull()
            ?: clip.child("CurrentEnd")?.value()?.toDoubleOrNull() ?: 4.0
        val length = (loopEnd - loopStart).takeIf { it > 0.01 } ?: 4.0
        val notes = ArrayList<Note>()
        clip.path("Notes", "KeyTracks")?.children()?.forEach { kt ->
            val key = kt.child("MidiKey")?.value()?.toIntOrNull() ?: return@forEach
            kt.child("Notes")?.children()?.forEach { ev ->
                if (ev.tagName != "MidiNoteEvent") return@forEach
                if (ev.getAttribute("IsEnabled") == "false") return@forEach
                val start = (ev.getAttribute("Time").toDoubleOrNull() ?: return@forEach) - loopStart
                val dur = ev.getAttribute("Duration").toDoubleOrNull() ?: 0.25
                val vel = ev.getAttribute("Velocity").toDoubleOrNull()?.toInt() ?: 100
                if (start >= -1e-6 && start < length) notes.add(Note(key.coerceIn(0, 127), maxOf(0.0, start), dur, vel.coerceIn(1, 127)))
            }
        }
        val name = clip.child("Name")?.value() ?: ""
        return Clip(name = name, lengthBeats = length).withNotes(notes)
    }

    // --- tiny DOM helpers
    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        val nl = childNodes
        for (i in 0 until nl.length) (nl.item(i) as? Element)?.let(out::add)
        return out
    }

    private fun Element.child(tag: String): Element? = children().firstOrNull { it.tagName == tag }

    private fun Element.path(vararg tags: String): Element? {
        var e: Element? = this
        for (t in tags) e = e?.child(t) ?: return null
        return e
    }

    private fun Element.value(): String? = if (hasAttribute("Value")) getAttribute("Value") else null
}
