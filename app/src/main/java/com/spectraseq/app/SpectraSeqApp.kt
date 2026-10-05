package com.spectraseq.app

import android.app.Application
import android.content.Context
import android.media.AudioManager
import com.spectraseq.app.audio.AudioOutput
import com.spectraseq.app.data.ProjectRepository
import com.spectraseq.app.midi.BleMidi
import com.spectraseq.app.midi.MidiInput
import com.spectraseq.core.dsp.SampleBank
import com.spectraseq.core.dsp.SoundFontBank
import com.spectraseq.core.engine.AudioEngine

/**
 * Process-wide objects. The engine and audio output live here (not in the activity) so folding or
 * unfolding the phone, rotating or switching displays never interrupts playback.
 */
class SpectraSeqApp : Application() {
    lateinit var engine: AudioEngine
        private set
    lateinit var output: AudioOutput
        private set
    lateinit var repository: ProjectRepository
        private set
    lateinit var midi: MidiInput
        private set
    lateinit var bleMidi: BleMidi
        private set

    override fun onCreate() {
        super.onCreate()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it in 22050..96000 } ?: 48000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..4096 } ?: 192
        engine = AudioEngine(rate, SampleBank(), SoundFontBank())
        output = AudioOutput(engine, rate, burst)
        repository = ProjectRepository(this)
        midi = MidiInput(this)
        bleMidi = BleMidi(this)
    }
}

val Context.app: SpectraSeqApp get() = applicationContext as SpectraSeqApp
