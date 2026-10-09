package com.notemove.core

import com.notemove.core.export.LiveSetExporter
import com.notemove.core.export.MidiFileWriter
import com.notemove.core.fm1.Fm1Import
import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.DrumKits
import com.notemove.core.model.TrackKind
import com.notesorcery.fm1link.Nsp1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An FM-1 song (fixtures: projects written by NoteSorcery's firmware) as a NoteMove project, and on to Live. */
class Fm1ImportTest {
    private fun res(n: String) = javaClass.classLoader.getResourceAsStream("fm1/$n")!!.readBytes()

    private fun song() = Nsp1.song(res("live.nsp1"), listOf(res("secA.nsp1"), res("secB.nsp1"), null, null), "FM-1 test")

    @Test
    fun eightTracksSectionsAsScenes() {
        val s = song()
        val p = Fm1Import.toProject(s)
        assertEquals(8, p.tracks.size)
        assertEquals(listOf(TrackKind.SYNTH, TrackKind.SYNTH, TrackKind.SYNTH, TrackKind.SYNTH, TrackKind.SYNTH, TrackKind.SYNTH,
            TrackKind.DRUMS, TrackKind.DRUMS), p.tracks.map { it.kind })
        assertEquals(120.0, p.tempo, 0.0)
        assertEquals(mapOf(0 to "A", 1 to "B"), p.sceneNames)
        assertTrue(p.sceneCount >= 2)
        assertEquals(4.0, p.tracks[0].clips[0]!!.lengthBeats, 0.0)
        assertEquals(8.0, p.tracks[0].clips[1]!!.lengthBeats, 0.0)
        val fmNotes = s.noteCount()
        assertEquals(fmNotes, p.tracks.sumOf { t -> t.clips.values.sumOf { it.notes.size } })
        assertEquals("T1 ACID", p.tracks[0].name)
    }

    @Test
    fun drumLanesLandOnThePadsOfTheSameSound() {
        val p = Fm1Import.toProject(song())
        val drums = p.tracks[6]
        val pads = DrumKits.KIT_808.pads
        val names = drums.clips[0]!!.notes.map { pads[it.pitch - DRUM_BASE_NOTE].name }.toSet()
        assertEquals(setOf("Kick", "Closed Hat", "Snare"), names)
        // every lane lands on a pad of its kind
        val lanes = Nsp1.LANE_NAME
        for (l in 0 until 16) {
            val pad = pads[Fm1Import.LANE_TO_PAD[l]].name.lowercase()
            val lane = lanes[l].lowercase()
            val key = when {
                lane.startsWith("kick") -> "kick"; lane.startsWith("snare") -> "snare"; lane == "cl hat" -> "closed"
                lane == "op hat" -> "open"; lane == "tom lo" -> "low"; lane == "tom hi" -> "high"; else -> lane.split(" ")[0]
            }
            assertTrue("lane $lane -> pad $pad", pad.contains(key))
        }
    }

    @Test
    fun goesOnToAbletonLive() {
        val p = Fm1Import.toProject(song())
        val xml = LiveSetExporter().buildXml(p)
        assertEquals(8, Regex("<MidiTrack Id=").findAll(xml).count())
        assertTrue(xml.contains("<Name Value=\"A\"/>") && xml.contains("<Name Value=\"B\"/>"))
        val mid = MidiFileWriter.song(p)
        assertEquals('M'.code.toByte(), mid[0])
    }
}
