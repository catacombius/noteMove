package com.notemove.core

import com.notemove.core.export.LiveSetExporter
import com.notemove.core.export.LiveSetImporter
import com.notemove.core.model.Clip
import com.notemove.core.model.ClipOps
import com.notemove.core.model.Note
import com.notemove.core.model.Project
import com.notemove.core.model.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory

class LiveSetExporterTest {

    private fun sampleProject(): Project {
        val base = Project.createDefault("Test & <Sketch>").copy(tempo = 98.5, rootNote = 2, scale = Scale.DORIAN, sceneCount = 4)
        val drums = base.tracks[0]
        val bass = base.tracks[1]
        val beat = Clip(name = "Beat", lengthBeats = 4.0).withNotes(
            (0 until 4).map { Note(36, it.toDouble(), 0.25, 110) } + (0 until 8).map { Note(40, it * 0.5, 0.1, 80) },
        )
        val line = Clip(lengthBeats = 8.0).withNotes(listOf(Note(38, 0.0, 1.5, 100), Note(41, 2.0, 0.5, 90), Note(45, 6.75, 1.0, 64)))
        return base.copy(
            tracks = listOf(
                drums.withClip(0, beat).withClip(2, ClipOps.setLength(beat.copy(id = "b2"), 2.0)),
                bass.withClip(0, line).copy(volume = 0.5f, pan = -0.25f, mute = true),
            ) + base.tracks.drop(2),
            sceneNames = mapOf(0 to "Intro"),
        )
    }

    private fun parse(xml: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).documentElement

    @Test
    fun producesWellFormedLiveSetWithoutPlaceholders() {
        val project = sampleProject()
        val xml = LiveSetExporter().buildXml(project)
        assertFalse("unfilled placeholder", xml.contains("{{"))
        assertFalse("unfilled id", xml.contains("@@"))
        val root = parse(xml)
        assertEquals("Ableton", root.tagName)
        val liveSet = root.getElementsByTagName("LiveSet").item(0) as Element
        val tracks = liveSet.getElementsByTagName("MidiTrack")
        assertEquals(project.tracks.size, tracks.length)
        val scenes = (liveSet.getElementsByTagName("Scenes").item(0) as Element).getElementsByTagName("Scene")
        assertEquals(4, scenes.length)
        for (i in 0 until tracks.length) {
            val t = tracks.item(i) as Element
            val mainSeq = t.getElementsByTagName("MainSequencer").item(0) as Element
            val slotList = mainSeq.getElementsByTagName("ClipSlotList").item(0) as Element
            val slots = (0 until slotList.childNodes.length).count { (slotList.childNodes.item(it) as? Element)?.tagName == "ClipSlot" }
            assertEquals("clip slots must match scenes", 4, slots)
        }
        assertTrue(xml.contains("<Manual Value=\"98.5\"/>"))
        assertTrue(xml.contains("<Name Value=\"Dorian\"/>"))
    }

    @Test
    fun pointeeIdsAreUniqueAndBelowNextPointeeId() {
        val xml = LiveSetExporter().buildXml(sampleProject())
        val targetIds = Regex("<(?:\\w*Target|ControllerTargets\\.\\d+|Pointee) Id=\"(\\d+)\"").findAll(xml).map { it.groupValues[1].toInt() }.toList()
        assertEquals("duplicate automation target ids", targetIds.size, targetIds.toSet().size)
        val next = Regex("<NextPointeeId Value=\"(\\d+)\"/>").find(xml)!!.groupValues[1].toInt()
        assertTrue(next > targetIds.max())
    }

    @Test
    fun roundTripsThroughImporter() {
        val project = sampleProject()
        val bytes = LiveSetExporter().exportBytes(project)
        // gzip magic
        assertEquals(0x1f, bytes[0].toInt() and 0xff)
        assertEquals(0x8b, bytes[1].toInt() and 0xff)
        val back = LiveSetImporter.import(ByteArrayInputStream(bytes), "Back")
        assertEquals(98.5, back.tempo, 1e-9)
        assertEquals(Scale.DORIAN, back.scale)
        assertEquals(2, back.rootNote)
        assertEquals(project.tracks.size, back.tracks.size)
        for ((a, b) in project.tracks.zip(back.tracks)) {
            assertEquals(a.name, b.name)
            assertEquals(a.clips.keys, b.clips.keys)
            for ((scene, clip) in a.clips) {
                val other = b.clips.getValue(scene)
                assertEquals(clip.lengthBeats, other.lengthBeats, 1e-9)
                assertEquals(clip.notes.map { Triple(it.pitch, it.start, it.velocity) }, other.notes.map { Triple(it.pitch, it.start, it.velocity) })
            }
        }
        // Keep a copy around for manual inspection / external validation.
        File("build/test-output").apply { mkdirs() }.resolve("sketch.als").writeBytes(bytes)
    }

    @Test
    fun arrangementLaysOutScenesSequentially() {
        val xml = LiveSetExporter().buildXml(sampleProject(), LiveSetExporter.Options(arrangement = true, arrangementRepeats = 1))
        // Scene 0 is 8 beats (longest clip), scene 2 starts after it.
        assertTrue(xml.contains("<MidiClip Id=\"1\" Time=\"8\">"))
        val gz = GZIPInputStream(ByteArrayInputStream(LiveSetExporter().exportBytes(sampleProject()))).readBytes()
        assertTrue(gz.isNotEmpty())
    }
}
