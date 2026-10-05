package com.spectraseq.core

import com.spectraseq.core.dsp.SoundFont
import com.spectraseq.core.dsp.SoundFontBank
import com.spectraseq.core.engine.AudioEngine
import com.spectraseq.core.model.Project
import com.spectraseq.core.model.SoundFontPatch
import com.spectraseq.core.model.Track
import com.spectraseq.core.model.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Builds a minimal but complete SF2 file (one looped sine sample, one instrument, two presets). */
object Sf2Writer {
    private fun le(size: Int, f: ByteBuffer.() -> Unit): ByteArray = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()
    private fun chunk(id: String, body: ByteArray): ByteArray {
        val pad = if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        return id.toByteArray() + le(4) { putInt(body.size) } + body + pad
    }
    private fun list(type: String, vararg chunks: ByteArray): ByteArray = chunk("LIST", type.toByteArray() + chunks.fold(ByteArray(0)) { a, b -> a + b })
    private fun name20(s: String) = ByteArray(20).also { s.toByteArray().copyInto(it, 0, 0, minOf(19, s.length)) }

    fun build(sampleRate: Int = 44100, rootKey: Int = 69, freq: Double = 440.0): ByteArray {
        // One second of sine at root pitch plus 46 zero samples (required padding).
        val n = sampleRate
        val pcm = ByteBuffer.allocate((n + 46) * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) pcm.putShort((sin(2 * PI * freq * i / sampleRate) * 20000).toInt().toShort())
        val info = list("INFO", chunk("ifil", le(4) { putShort(2); putShort(1) }), chunk("INAM", "Test Font\u0000".toByteArray()))
        val sdta = list("sdta", chunk("smpl", pcm.array()))
        // phdr: two presets (program 0 bank 0, program 0 bank 128) + terminal
        val phdr = ByteArrayOutputStream().apply {
            write(name20("Sine Lead") + le(18) { putShort(0); putShort(0); putShort(0); putInt(0); putInt(0); putInt(0) })
            write(name20("Sine Drums") + le(18) { putShort(0); putShort(128); putShort(1); putInt(0); putInt(0); putInt(0) })
            write(name20("EOP") + le(18) { putShort(0); putShort(0); putShort(2); putInt(0); putInt(0); putInt(0) })
        }.toByteArray()
        // pbag: zone0 -> gens 0..1 (instrument 0), zone1 -> gens 1..2, terminal
        val pbag = le(12) { putShort(0); putShort(0); putShort(1); putShort(0); putShort(2); putShort(0) }
        val pmod = ByteArray(10)
        val pgen = le(12) { putShort(41); putShort(0); putShort(41); putShort(0); putShort(0); putShort(0) }
        val inst = name20("Sine") + le(2) { putShort(0) } + name20("EOI") + le(2) { putShort(1) }
        val ibag = le(8) { putShort(0); putShort(0); putShort(3); putShort(0) }
        val imod = ByteArray(10)
        // igen: sampleModes=1 (loop), overridingRootKey=-1 (use sample), sampleID=0, terminal
        val igen = le(16) { putShort(54); putShort(1); putShort(38); putShort(-2400); putShort(53); putShort(0); putShort(0); putShort(0) }
        val shdr = name20("sine") + le(26) {
            putInt(0); putInt(n); putInt(0); putInt(sampleRate / freq.toInt() * 100); putInt(sampleRate)
            put(rootKey.toByte()); put(0); putShort(0); putShort(1)
        } + name20("EOS") + ByteArray(26)
        val pdta = list("pdta", chunk("phdr", phdr), chunk("pbag", pbag), chunk("pmod", pmod), chunk("pgen", pgen),
            chunk("inst", inst), chunk("ibag", ibag), chunk("imod", imod), chunk("igen", igen), chunk("shdr", shdr))
        val body = "sfbk".toByteArray() + info + sdta + pdta
        return "RIFF".toByteArray() + le(4) { putInt(body.size) } + body
    }
}

class SoundFontTest {
    @Test
    fun parsesPresetsAndZones() {
        val sf = SoundFont.parse(Sf2Writer.build(), "x")
        assertEquals("Test Font", sf.name)
        assertEquals(2, sf.presets.size)
        val lead = sf.preset(0, 0)!!
        assertEquals("Sine Lead", lead.name)
        val z = lead.zones.single()
        assertEquals(69, z.rootKey)
        assertEquals(1, z.loopMode)
        assertEquals(44100, z.sampleRate)
        assertEquals(128, sf.presets[1].bank)
    }

    @Test
    fun playsAtTheRightPitch() {
        val sr = 48000
        val fonts = SoundFontBank().apply { put("f", SoundFont.parse(Sf2Writer.build(), "x")) }
        val t = Track(name = "SF", kind = TrackKind.SOUNDFONT, soundfont = SoundFontPatch(fontId = "f", bank = 0, program = 0))
        val e = AudioEngine(sr, soundFonts = fonts)
        e.setProject(Project(name = "p", tracks = listOf(t)))
        e.noteOn(t.id, 81, 127) // one octave above the root: 880 Hz
        val l = FloatArray(sr / 2); val r = FloatArray(sr / 2)
        e.render(l, r, l.size)
        // Count zero crossings over the second half (steady state).
        var crossings = 0
        for (i in sr / 4 + 1 until sr / 2) if ((l[i - 1] < 0) != (l[i] < 0)) crossings++
        val hz = crossings / 2.0 / 0.25
        assertTrue("expected ~880 Hz, got $hz", abs(hz - 880) < 20)
        assertTrue(t.drumLayout.not())
        assertTrue(t.copy(soundfont = t.soundfont!!.copy(bank = 128)).drumLayout)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonSoundFonts() { SoundFont.parse(ByteArray(64), "x") }
}
