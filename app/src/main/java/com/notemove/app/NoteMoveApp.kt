package com.notemove.app

import android.app.Application
import android.content.Context
import android.media.AudioManager
import com.notemove.app.audio.AudioOutput
import com.notemove.app.data.ProjectRepository
import com.notemove.app.midi.BleMidi
import com.notemove.app.midi.MidiInput
import com.notemove.core.dsp.SampleBank
import com.notemove.core.dsp.SoundFontBank
import com.notemove.core.engine.AudioEngine

/**
 * Process-wide objects. The engine and audio output live here (not in the activity) so folding or
 * unfolding the phone, rotating or switching displays never interrupts playback.
 */
class NoteMoveApp : Application() {
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

val Context.app: NoteMoveApp get() = applicationContext as NoteMoveApp
