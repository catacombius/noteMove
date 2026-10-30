package com.spectraseq.app.edition

import android.content.Context
import com.botanica.engine.BotanicaEngine
import com.spectraseq.core.dsp.ExternalInstruments
import com.spectraseq.core.model.TrackKind

/** SpectraSeq Garden: SpectraSeq with Botanica's engine as an instrument (GARDEN tracks). */
object Edition {
    val trackKinds: List<TrackKind> = TrackKind.entries.toList()

    fun install(context: Context) {
        ExternalInstruments.garden = { track, sampleRate -> GardenInstrument(sampleRate, track) }
    }
}

/**
 * Patches from presets, on the UI thread: a spare engine (never played) applies a preset over a patch's values and
 * reads back every parameter, as the plug-in's browser does.
 */
object GardenPresets {
    private val scratch by lazy { BotanicaEngine(48000) }

    @Synchronized
    fun apply(values: List<Double>, preset: BotanicaEngine.Preset): List<Double> {
        val params = BotanicaEngine.PARAMS
        for (p in params) scratch.setParam(p.id, if (values.size == params.size) values[p.id] else p.default)
        scratch.loadPreset(preset)
        return params.map { scratch.getParam(it.id) }
    }

    /** A new GARDEN track's sound: the first factory sound and kit. */
    fun initial(): List<Double> = apply(apply(emptyList(), BotanicaEngine.SOUNDS[0]), BotanicaEngine.KITS[0])
}
