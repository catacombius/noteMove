package com.spectraseq.app.edition

import com.botanica.engine.BotanicaEngine
import com.spectraseq.core.dsp.Instrument
import com.spectraseq.core.model.GardenPatch
import com.spectraseq.core.model.Track
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * A GARDEN track's instrument: a Botanica engine of its own, playing the track's notes on the sequencer's clock.
 * Everything that touches the engine runs on the audio thread, as the engine wants; a changed patch is applied there
 * too, parameter by parameter (only the ones that changed).
 */
class GardenInstrument(sampleRate: Float, track: Track) : Instrument {
    private val engine = BotanicaEngine(sampleRate.roundToInt())
    @Volatile private var wanted: GardenPatch = track.garden ?: GardenPatch()
    private var applied: GardenPatch? = null
    private var values = DoubleArray(0)
    private var released = false

    override fun update(track: Track) { wanted = track.garden ?: GardenPatch() }

    private fun apply(p: GardenPatch) {
        val v = p.values
        if (v.size == BotanicaEngine.PARAMS.size) {
            for (i in v.indices) if (i >= values.size || values[i] != v[i]) engine.setParam(i, v[i])
            values = v.toDoubleArray()
        }
        applied = p
    }

    override fun noteOn(pitch: Int, velocity: Int) = engine.noteOn(pitch, velocity / 127f)
    override fun noteOff(pitch: Int) = engine.noteOff(pitch)
    override fun allNotesOff(hard: Boolean) = engine.allNotesOff()

    override fun setTransport(tempo: Double, beat: Double, playing: Boolean) = engine.setTransport(tempo, beat, playing)

    override fun render(outL: FloatArray, outR: FloatArray, n: Int) {
        if (released) return
        val w = wanted
        if (w !== applied) apply(w)
        engine.renderAdd(outL, outR, n)
    }

    // (the engine is freed off the audio thread: it is no longer played once released)
    override fun release() {
        if (released) return
        released = true
        closer.execute { engine.close() }
    }

    companion object {
        private val closer = Executors.newSingleThreadExecutor { r -> Thread(r, "garden-close").apply { isDaemon = true } }
    }
}
