package com.notemove.core.engine

import com.notemove.core.dsp.DrumMachine
import com.notemove.core.dsp.EffectChain
import com.notemove.core.dsp.FxContext
import com.notemove.core.dsp.Instrument
import com.notemove.core.dsp.Limiter
import com.notemove.core.dsp.PolySynth
import com.notemove.core.dsp.Reverb
import com.notemove.core.dsp.SampleBank
import com.notemove.core.dsp.Sampler
import com.notemove.core.dsp.SoundFontBank
import com.notemove.core.dsp.SoundFontPlayer
import com.notemove.core.dsp.StereoDelay
import com.notemove.core.dsp.Svf
import com.notemove.core.dsp.drive
import com.notemove.core.dsp.normToCutoffHz
import com.notemove.core.dsp.panLeft
import com.notemove.core.dsp.panRight
import com.notemove.core.model.ArpMode
import com.notemove.core.model.ArpSettings
import com.notemove.core.model.BEATS_PER_BAR
import com.notemove.core.model.Clip
import com.notemove.core.model.DrumKits
import com.notemove.core.model.Project
import com.notemove.core.model.SamplerPatch
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** A note captured while recording, in clip-local beats. Delivered to the UI thread for merging. */
data class RecordedNote(
    val trackId: String,
    val scene: Int,
    val pitch: Int,
    val start: Double,
    val duration: Double,
    val velocity: Int,
)

/** A note played live, timed on the engine's free-running beat clock (used by Capture). */
data class PlayedNote(val trackId: String, val pitch: Int, val startBeat: Double, val duration: Double, val velocity: Int, val transportBeat: Double?)

/** Immutable snapshot of what the audio thread is doing, published for the UI. */
data class EngineState(
    val playing: Boolean = false,
    val recording: Boolean = false,
    val songBeat: Double = 0.0,
    val playingScenes: Map<String, Int> = emptyMap(),
    val queuedScenes: Map<String, Int> = emptyMap(),
    val clipStartBeats: Map<String, Double> = emptyMap(),
    val trackPeaks: Map<String, Float> = emptyMap(),
    val masterPeak: Float = 0f,
    val publishedAtNanos: Long = 0L,
    val tempo: Double = 120.0,
    /** Time between rendering audio and hearing it (incl. Bluetooth); visuals are delayed by this much. */
    val outputLatencyMs: Double = 0.0,
) {
    /** Position inside the clip currently playing on [trackId], or null. */
    fun clipPosition(trackId: String, clip: Clip?, nowNanos: Long = System.nanoTime()): Double? {
        if (!playing || clip == null) return null
        val start = clipStartBeats[trackId] ?: return null
        val beat = estimatedBeat(nowNanos)
        if (beat < start) return null
        return (beat - start) % clip.lengthBeats
    }

    /** The beat you are hearing right now (rendered position minus output latency). */
    fun estimatedBeat(nowNanos: Long = System.nanoTime()): Double =
        if (!playing) songBeat else songBeat + ((nowNanos - publishedAtNanos) / 1e9 - outputLatencyMs / 1000.0) * tempo / 60.0
}

class AudioEngine(val sampleRate: Int, val samples: SampleBank = SampleBank(), val soundFonts: SoundFontBank = SoundFontBank()) {
    private sealed interface Cmd {
        data class NoteOn(val trackId: String, val pitch: Int, val velocity: Int) : Cmd
        data class NoteOff(val trackId: String, val pitch: Int) : Cmd
        data class Play(val launchScene: Int?, val countIn: Boolean) : Cmd
        data object Stop : Cmd
        data class Launch(val trackId: String, val scene: Int) : Cmd
        data class StopTrack(val trackId: String) : Cmd
        data class LaunchScene(val scene: Int) : Cmd
        data class Record(val trackId: String, val scene: Int, val countIn: Boolean) : Cmd
        data object StopRecording : Cmd
        data class Repeat(val trackId: String?, val rateBeats: Double) : Cmd
        data object Panic : Cmd
    }

    private val sr = sampleRate.toFloat()
    private val commands = ConcurrentLinkedQueue<Cmd>()
    private val recorded = ConcurrentLinkedQueue<RecordedNote>()
    private val playedRing = ArrayDeque<PlayedNote>()
    private val playedLock = Any()

    @Volatile private var pendingProject: Project? = null
    private var project: Project? = null
    private val players = LinkedHashMap<String, TrackPlayer>()

    @Volatile var state = EngineState()
        private set

    /** Output latency of the device in frames; used to place live-played notes where the player heard them. */
    @Volatile var outputLatencyFrames: Int = 0
    /** Extra user-set compensation for recording (e.g. Bluetooth headphones that under-report their delay). */
    @Volatile var extraLatencyMs: Double = 0.0
    @Volatile var metronomeEnabled: Boolean = false
    @Volatile var metronomeLevel: Float = 0.5f

    // Transport (audio thread only)
    private var playing = false
    private var songBeat = 0.0
    private var freeBeat = 0.0
    private var recordTrackId: String? = null
    private var recordScene = -1
    private var countingIn = false
    private var repeatTrackId: String? = null
    private var repeatRate = 0.0
    private var lastPublish = 0L

    // Buffers
    private val block = 32
    private val tl = FloatArray(block)
    private val tr = FloatArray(block)
    private val ml = FloatArray(block)
    private val mr = FloatArray(block)
    private val dsl = FloatArray(block)
    private val dsr = FloatArray(block)
    private val rsl = FloatArray(block)
    private val rsr = FloatArray(block)
    private val delay = StereoDelay(sampleRate * 3)
    private val reverb = Reverb(sr)
    private val limiter = Limiter(sr)
    private val click = Click(sr)
    private var masterPeak = 0f
    private val fxCtx = FxContext(sr)
    private val masterChain = EffectChain(sr)
    private val arpRandom = java.util.Random(7)

    /** One-shot audition of raw sample data (sample editor), mixed in after the master effects. */
    private class Preview(val data: FloatArray, val rate: Double, val end: Int, var pos: Double)
    @Volatile private var preview: Preview? = null

    /** Frame of the sample being previewed, or -1 when nothing plays. */
    @Volatile var previewFrame: Int = -1
        private set

    // ------------------------------------------------------------------ public API (any thread)

    fun setProject(p: Project) { pendingProject = p }

    fun noteOn(trackId: String, pitch: Int, velocity: Int) = commands.add(Cmd.NoteOn(trackId, pitch, velocity.coerceIn(1, 127)))
    fun noteOff(trackId: String, pitch: Int) = commands.add(Cmd.NoteOff(trackId, pitch))
    fun play(launchSceneIfIdle: Int? = null, countIn: Boolean = false) = commands.add(Cmd.Play(launchSceneIfIdle, countIn))
    fun stop() = commands.add(Cmd.Stop)
    fun launchClip(trackId: String, scene: Int) = commands.add(Cmd.Launch(trackId, scene))
    fun stopTrack(trackId: String) = commands.add(Cmd.StopTrack(trackId))
    fun launchScene(scene: Int) = commands.add(Cmd.LaunchScene(scene))
    fun record(trackId: String, scene: Int, countIn: Boolean) = commands.add(Cmd.Record(trackId, scene, countIn))
    fun stopRecording() = commands.add(Cmd.StopRecording)
    /** Note repeat for held notes on [trackId]; rate in beats (0.25 = 16ths), 0 disables. */
    fun setNoteRepeat(trackId: String?, rateBeats: Double) = commands.add(Cmd.Repeat(trackId, rateBeats))
    fun panic() = commands.add(Cmd.Panic)

    /** Plays [data] (recorded at [dataRate]) from [start] to [end] frames, for auditioning in the sample editor. */
    fun previewSample(data: FloatArray, dataRate: Int, start: Int = 0, end: Int = data.size) {
        if (data.size < 2) return
        preview = Preview(data, dataRate.toDouble() / sampleRate, end.coerceIn(1, data.size), start.coerceIn(0, data.size - 1).toDouble())
    }

    fun stopPreview() { preview = null; previewFrame = -1 }

    fun drainRecorded(): List<RecordedNote> {
        if (recorded.isEmpty()) return emptyList()
        val out = ArrayList<RecordedNote>()
        while (true) out.add(recorded.poll() ?: break)
        return out
    }

    /** Notes played live in the last [maxAgeBeats] beats (for Capture). */
    fun recentlyPlayed(trackId: String): List<PlayedNote> = synchronized(playedLock) { playedRing.filter { it.trackId == trackId } }

    fun clearPlayed(trackId: String) = synchronized(playedLock) { playedRing.removeAll { it.trackId == trackId } }

    // ------------------------------------------------------------------ audio thread

    /** Renders interleaved-free stereo audio. Call from a single audio thread. */
    fun render(outL: FloatArray, outR: FloatArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            renderBlock(n)
            System.arraycopy(ml, 0, outL, done, n)
            System.arraycopy(mr, 0, outR, done, n)
            done += n
        }
        publish()
    }

    private fun syncProject() {
        val p = pendingProject ?: return
        if (p === project) return
        project = p
        val ids = p.tracks.map { it.id }.toSet()
        players.keys.retainAll(ids)
        val ordered = LinkedHashMap<String, TrackPlayer>()
        for (t in p.tracks) {
            val existing = players[t.id]
            ordered[t.id] = if (existing != null && existing.kind == t.kind) existing.also { it.update(t) } else TrackPlayer(t)
        }
        players.clear(); players.putAll(ordered)
    }

    private fun beatsPerFrame(): Double = (project?.tempo ?: 120.0) / 60.0 / sampleRate

    private fun renderBlock(n: Int) {
        syncProject()
        val p = project
        ml.fill(0f, 0, n); mr.fill(0f, 0, n)
        dsl.fill(0f, 0, n); dsr.fill(0f, 0, n); rsl.fill(0f, 0, n); rsr.fill(0f, 0, n)
        if (p == null) { while (commands.poll() != null) Unit; return }
        val bpf = beatsPerFrame()
        val b0 = songBeat
        val b1 = b0 + n * bpf
        processCommands(p, b0)
        if (playing) sequence(p, b0, b1)
        noteRepeat(if (playing) b0 else freeBeat, if (playing) b1 else freeBeat + n * bpf)
        arpTick(p, b0, b1, freeBeat, freeBeat + n * bpf)
        fxCtx.tempo = p.tempo
        fxCtx.beat = if (playing) b0 else freeBeat

        val anySolo = p.tracks.any { it.solo }
        for (t in p.tracks) {
            val pl = players[t.id] ?: continue
            tl.fill(0f, 0, n); tr.fill(0f, 0, n)
            pl.instrument.render(tl, tr, n)
            pl.processFx(t, tl, tr, n)
            if (t.effects.isNotEmpty()) pl.chain.process(t.effects, tl, tr, n, fxCtx)
            val audible = !t.mute && (!anySolo || t.solo)
            val gl = t.volume * t.volume * panLeft(t.pan) * 1.41f
            val gr = t.volume * t.volume * panRight(t.pan) * 1.41f
            var peak = 0f
            for (i in 0 until n) {
                val l = tl[i] * gl; val r = tr[i] * gr
                val a = max(abs(l), abs(r))
                if (a > peak) peak = a
                if (audible) {
                    ml[i] += l; mr[i] += r
                    dsl[i] += l * t.fx.delaySend; dsr[i] += r * t.fx.delaySend
                    rsl[i] += l * t.fx.reverbSend; rsr[i] += r * t.fx.reverbSend
                }
            }
            pl.peak = max(peak, pl.peak * 0.97f)
        }
        val g = p.globalFx
        delay.setDelaySamples((g.delaySixteenths * 0.25 / bpf).toInt())
        delay.process(dsl, dsr, ml, mr, n, g.delayFeedback, g.delayLevel)
        reverb.process(rsl, rsr, ml, mr, n, g.reverbSize, g.reverbDamping, g.reverbLevel)

        // Metronome (always during count-in)
        if (playing && (metronomeEnabled || countingIn)) {
            val k = ceil(b0)
            if (k < b1) click.trigger(Math.floorMod(k.toLong(), BEATS_PER_BAR.toLong()) == 0L)
        }
        if (p.masterEffects.isNotEmpty()) masterChain.process(p.masterEffects, ml, mr, n, fxCtx)
        click.render(ml, mr, n, metronomeLevel)
        renderPreview(n)

        val mv = p.masterVolume
        var mp = 0f
        for (i in 0 until n) { ml[i] *= mv; mr[i] *= mv }
        limiter.process(ml, mr, n)
        for (i in 0 until n) { val a = max(abs(ml[i]), abs(mr[i])); if (a > mp) mp = a }
        masterPeak = max(mp, masterPeak * 0.97f)

        if (playing) {
            songBeat = b1
            if (countingIn && songBeat >= 0) countingIn = false
        }
        freeBeat += n * bpf
    }

    private fun latencyBeats(): Double = outputLatencyFrames * beatsPerFrame() + extraLatencyMs / 1000.0 * (project?.tempo ?: 120.0) / 60.0

    private fun processCommands(p: Project, b0: Double) {
        while (true) {
            val c = commands.poll() ?: break
            when (c) {
                is Cmd.NoteOn -> {
                    val pl = players[c.trackId] ?: continue
                    val arp = p.track(c.trackId)?.arp
                    if (arp != null && arp.enabled) { pl.arpPress(c.pitch, c.velocity, if (playing) b0 else freeBeat, arp); continue }
                    pl.instrument.noteOn(c.pitch, c.velocity)
                    pl.liveHeld[c.pitch] = LiveNote(c.velocity, if (playing) b0 - latencyBeats() else null, freeBeat - latencyBeats())
                    if (repeatRate > 0 && repeatTrackId == c.trackId) pl.repeatStart = if (playing) b0 else freeBeat
                }
                is Cmd.NoteOff -> {
                    val pl = players[c.trackId] ?: continue
                    val held = pl.liveHeld.remove(c.pitch)
                    if (held == null) { pl.arpRelease(c.pitch, p.track(c.trackId)?.arp); continue }
                    pl.instrument.noteOff(c.pitch)
                    finishLiveNote(c.trackId, pl, c.pitch, held, b0)
                }
                is Cmd.Play -> if (!playing) startTransport(p, c.launchScene, c.countIn)
                Cmd.Stop -> stopTransport()
                is Cmd.Launch -> launch(c.trackId, c.scene, p)
                is Cmd.StopTrack -> {
                    val pl = players[c.trackId] ?: continue
                    if (playing) pl.queuedScene = STOP else { pl.playingScene = NONE; pl.flushSequencedNotes() }
                }
                is Cmd.LaunchScene -> {
                    for (t in p.tracks) {
                        if (t.clips.containsKey(c.scene)) launch(t.id, c.scene, p)
                        else players[t.id]?.let { if (playing) it.queuedScene = STOP else it.playingScene = NONE }
                    }
                }
                is Cmd.Record -> {
                    recordTrackId = c.trackId
                    recordScene = c.scene
                    val pl = players[c.trackId]
                    if (!playing) {
                        pl?.playingScene = c.scene
                        pl?.queuedScene = NONE
                        startTransport(p, null, c.countIn)
                    } else if (pl != null && pl.playingScene != c.scene) {
                        pl.queuedScene = c.scene
                    }
                }
                Cmd.StopRecording -> stopRecording(b0)
                is Cmd.Repeat -> {
                    repeatTrackId = c.trackId; repeatRate = c.rateBeats
                    players[c.trackId]?.repeatStart = if (playing) b0 else freeBeat
                }
                Cmd.Panic -> players.values.forEach { it.instrument.allNotesOff(hard = true); it.pending.clear(); it.arpReset() }
            }
        }
    }

    private fun launch(trackId: String, scene: Int, p: Project) {
        val pl = players[trackId] ?: return
        val t = p.track(trackId) ?: return
        if (!t.clips.containsKey(scene)) return
        if (!playing) {
            // Launching while stopped arms the clip; transport start picks it up.
            pl.playingScene = scene
            pl.queuedScene = NONE
            startTransport(p, null, false)
        } else {
            pl.queuedScene = scene
        }
    }

    private fun startTransport(p: Project, launchScene: Int?, countIn: Boolean) {
        players.values.forEach { it.arpFlush() }
        playing = true
        countingIn = countIn
        songBeat = if (countIn) -BEATS_PER_BAR else 0.0
        val anyArmed = players.values.any { it.playingScene >= 0 || it.queuedScene >= 0 }
        if (!anyArmed && launchScene != null) {
            for (t in p.tracks) if (t.clips.containsKey(launchScene)) players[t.id]?.playingScene = launchScene
        }
        for (pl in players.values) {
            if (pl.queuedScene >= 0) { pl.playingScene = pl.queuedScene }
            pl.queuedScene = NONE
            pl.clipStart = 0.0
            pl.lastLocal = -1.0
        }
    }

    private fun stopTransport() {
        if (recordTrackId != null) stopRecording(songBeat)
        playing = false
        countingIn = false
        players.values.forEach { it.arpFlush(); it.arpOrigin = freeBeat }
        for (pl in players.values) {
            pl.flushSequencedNotes()
            pl.queuedScene = NONE
        }
        songBeat = 0.0
    }

    private fun stopRecording(b0: Double) {
        val id = recordTrackId ?: return
        val pl = players[id]
        if (pl != null) for ((pitch, held) in pl.liveHeld) finishLiveNote(id, pl, pitch, held, b0, keepHeld = true)
        recordTrackId = null
        recordScene = -1
    }

    private fun finishLiveNote(trackId: String, pl: TrackPlayer, pitch: Int, held: LiveNote, b0: Double, keepHeld: Boolean = false) {
        val nowFree = freeBeat - latencyBeats()
        val dur = max(0.02, nowFree - held.freeStart)
        synchronized(playedLock) {
            playedRing.addLast(PlayedNote(trackId, pitch, held.freeStart, dur, held.velocity, held.songStart))
            while (playedRing.size > 2048) playedRing.removeFirst()
        }
        if (trackId == recordTrackId && held.songStart != null && pl.playingScene == recordScene) {
            recordNote(trackId, pl, pitch, held.songStart, dur, held.velocity)
        }
        if (keepHeld) held.songStart?.let { pl.liveHeld[pitch] = held.copy(songStart = b0, freeStart = freeBeat) }
    }

    private fun recordNote(trackId: String, pl: TrackPlayer, pitch: Int, songStart: Double, dur: Double, velocity: Int) {
        val clip = project?.track(trackId)?.clips?.get(recordScene) ?: return
        if (songStart < pl.clipStart - 0.5) return
        val len = clip.lengthBeats
        var local = (songStart - pl.clipStart) % len
        if (local < 0) local += len
        // Notes played a hair before the loop point belong to the start of the clip.
        if (len - local < 0.03) local = 0.0
        recorded.add(RecordedNote(trackId, recordScene, pitch, local, minOf(dur, len), velocity))
    }

    private fun sequence(p: Project, b0: Double, b1: Double) {
        val quant = p.launchQuantization.beats
        for (t in p.tracks) {
            val pl = players[t.id] ?: continue
            // Launch quantisation
            if (pl.queuedScene != NONE) {
                val boundary = if (quant <= 0.0) b0 else max(0.0, ceil(b0 / quant - 1e-9) * quant)
                if (boundary < b1) {
                    pl.flushSequencedNotes()
                    if (pl.queuedScene == STOP) pl.playingScene = NONE
                    else { pl.playingScene = pl.queuedScene; pl.clipStart = boundary; pl.lastLocal = -1.0 }
                    pl.queuedScene = NONE
                }
            }
            // Pending note-offs
            if (pl.pending.isNotEmpty()) {
                val it = pl.pending.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.offBeat < b1) { pl.instrument.noteOff(e.pitch); it.remove() }
                }
            }
            if (b1 <= 0.0) continue
            val clip = t.clips[pl.playingScene] ?: continue
            if (clip.notes.isEmpty() || b1 <= pl.clipStart) continue
            val len = clip.lengthBeats
            val from = max(b0, pl.clipStart)
            val l0 = (from - pl.clipStart) % len
            val l1 = l0 + (b1 - from)
            fireNotes(pl, clip, p.swing, l0, minOf(l1, len), from - l0)
            if (l1 > len) fireNotes(pl, clip, p.swing, 0.0, l1 - len, from - l0 + len)
        }
    }

    private fun fireNotes(pl: TrackPlayer, clip: Clip, swing: Float, lo: Double, hi: Double, loopOrigin: Double) {
        for (note in clip.notes) {
            val s = swungStart(note.start, swing)
            if (s >= lo && s < hi) {
                // Retriggering the same pitch: close the previous one first.
                val prev = pl.pending.firstOrNull { it.pitch == note.pitch }
                if (prev != null) { pl.instrument.noteOff(note.pitch); pl.pending.remove(prev) }
                pl.instrument.noteOn(note.pitch, note.velocity)
                pl.pending.add(PendingOff(note.pitch, loopOrigin + s + max(0.01, note.duration)))
            }
        }
    }

    private fun noteRepeat(b0: Double, b1: Double) {
        val id = repeatTrackId ?: return
        if (repeatRate <= 0) return
        val pl = players[id] ?: return
        if (pl.liveHeld.isEmpty()) return
        // Grid-aligned retriggers; the initial hit came from the pad itself.
        val next = ceil(b0 / repeatRate - 1e-9) * repeatRate
        if (next >= b1 || next <= pl.repeatStart + 1e-6) return
        for ((pitch, held) in pl.liveHeld.entries.toList()) {
            pl.instrument.noteOff(pitch)
            pl.instrument.noteOn(pitch, held.velocity)
            if (playing && recordTrackId == id && pl.playingScene == recordScene) {
                recordNote(id, pl, pitch, next, repeatRate * 0.5, held.velocity)
            }
        }
        pl.repeatStart = next
    }

    /** Steps every running arpeggiator. Grid follows the song while playing, else starts at the first press. */
    private fun arpTick(p: Project, b0: Double, b1: Double, f0: Double, f1: Double) {
        for (t in p.tracks) {
            val pl = players[t.id] ?: continue
            val c0 = if (playing) b0 else f0
            val c1 = if (playing) b1 else f1
            if (pl.arpOffs.isNotEmpty()) {
                val it = pl.arpOffs.iterator()
                while (it.hasNext()) { val e = it.next(); if (e.offBeat < c1) { pl.instrument.noteOff(e.pitch); it.remove() } }
            }
            val a = t.arp
            if (!a.enabled) { if (pl.arpKeys.isNotEmpty() || pl.arpDown.isNotEmpty()) pl.arpReset(); continue }
            if (pl.arpKeys.isEmpty()) continue
            val rate = a.rate.coerceAtLeast(1.0 / 64)
            val origin = if (playing) 0.0 else pl.arpOrigin
            val step = origin + ceil((c0 - origin) / rate - 1e-9) * rate
            if (step >= c1 || step <= pl.arpLastStep + 1e-9) continue
            pl.arpLastStep = step
            val notes = arpNotes(pl, a)
            val len = rate * a.gate.coerceIn(0.05f, 1f)
            for ((pitch, vel) in notes) {
                if (pitch !in 0..127) continue
                pl.instrument.noteOff(pitch)
                pl.instrument.noteOn(pitch, vel)
                pl.arpOffs.removeAll { it.pitch == pitch }
                pl.arpOffs.add(PendingOff(pitch, step + len))
                if (playing && recordTrackId == t.id && pl.playingScene == recordScene) recordNote(t.id, pl, pitch, step, len, vel)
                synchronized(playedLock) {
                    playedRing.addLast(PlayedNote(t.id, pitch, f0 + (step - c0), len, vel, if (playing) step else null))
                    while (playedRing.size > 2048) playedRing.removeFirst()
                }
            }
        }
    }

    private fun arpNotes(pl: TrackPlayer, a: ArpSettings): List<Pair<Int, Int>> {
        val keys = pl.arpKeys.entries.map { it.key to it.value }
        val octs = a.octaves.coerceIn(1, 4)
        val idx = pl.arpIndex++
        if (a.mode == ArpMode.CHORD) {
            val o = (idx % octs) * 12
            return keys.map { (k, v) -> k + o to v }
        }
        val base = when (a.mode) {
            ArpMode.AS_PLAYED -> keys
            ArpMode.DOWN -> keys.sortedByDescending { it.first }
            else -> keys.sortedBy { it.first }
        }
        val expanded = if (a.mode == ArpMode.DOWN) (octs - 1 downTo 0).flatMap { o -> base.map { (k, v) -> k + o * 12 to v } }
        else (0 until octs).flatMap { o -> base.map { (k, v) -> k + o * 12 to v } }
        val seq = if (a.mode == ArpMode.UP_DOWN && expanded.size > 2) expanded + expanded.reversed().drop(1).dropLast(1) else expanded
        if (seq.isEmpty()) return emptyList()
        return listOf(if (a.mode == ArpMode.RANDOM) seq[arpRandom.nextInt(seq.size)] else seq[idx % seq.size])
    }

    private fun renderPreview(n: Int) {
        val pv = preview ?: return
        val d = pv.data
        for (i in 0 until n) {
            val idx = pv.pos.toInt()
            if (idx >= pv.end - 1) { preview = null; previewFrame = -1; return }
            val f = (pv.pos - idx).toFloat()
            val s = (d[idx] + (d[idx + 1] - d[idx]) * f) * 0.8f
            ml[i] += s; mr[i] += s
            pv.pos += pv.rate
        }
        previewFrame = pv.pos.toInt()
    }

    private fun publish() {
        val now = System.nanoTime()
        if (now - lastPublish < 15_000_000L) return
        lastPublish = now
        val playingScenes = HashMap<String, Int>()
        val queued = HashMap<String, Int>()
        val starts = HashMap<String, Double>()
        val peaks = HashMap<String, Float>()
        for ((id, pl) in players) {
            if (pl.playingScene >= 0) { playingScenes[id] = pl.playingScene; starts[id] = pl.clipStart }
            if (pl.queuedScene != NONE) queued[id] = pl.queuedScene
            peaks[id] = pl.peak
        }
        state = EngineState(
            playing = playing,
            recording = recordTrackId != null,
            songBeat = songBeat,
            playingScenes = playingScenes,
            queuedScenes = queued,
            clipStartBeats = starts,
            trackPeaks = peaks,
            masterPeak = masterPeak,
            publishedAtNanos = now,
            tempo = project?.tempo ?: 120.0,
            outputLatencyMs = outputLatencyFrames * 1000.0 / sampleRate + extraLatencyMs,
        )
    }

    private data class LiveNote(val velocity: Int, val songStart: Double?, val freeStart: Double)
    private data class PendingOff(val pitch: Int, val offBeat: Double)

    private inner class TrackPlayer(track: Track) {
        val kind = track.kind
        val instrument: Instrument = createInstrument(track)
        var playingScene = NONE
        var queuedScene = NONE
        var clipStart = 0.0
        var lastLocal = -1.0
        var peak = 0f
        var repeatStart = 0.0
        val pending = ArrayList<PendingOff>()
        val liveHeld = LinkedHashMap<Int, LiveNote>()
        val chain = EffectChain(sr)
        // Arpeggiator state (audio thread)
        val arpKeys = LinkedHashMap<Int, Int>()
        val arpDown = HashSet<Int>()
        val arpOffs = ArrayList<PendingOff>()
        var arpOrigin = 0.0
        var arpIndex = 0
        var arpLastStep = -1e9

        fun arpPress(pitch: Int, velocity: Int, now: Double, a: ArpSettings) {
            // With latch, a fresh press after letting go of everything starts a new pattern.
            if (a.latch && arpDown.isEmpty()) arpKeys.clear()
            if (arpKeys.isEmpty()) { arpOrigin = now; arpIndex = 0; arpLastStep = -1e9 }
            arpKeys[pitch] = velocity
            arpDown.add(pitch)
        }

        fun arpRelease(pitch: Int, a: ArpSettings?) {
            arpDown.remove(pitch)
            if (a == null || !a.latch || !a.enabled) arpKeys.remove(pitch)
        }

        fun arpFlush() {
            for (e in arpOffs) instrument.noteOff(e.pitch)
            arpOffs.clear()
            arpLastStep = -1e9
        }

        fun arpReset() { arpFlush(); arpKeys.clear(); arpDown.clear(); arpIndex = 0 }
        private val fl = Svf()
        private val fr = Svf()

        fun update(t: Track) {
            when (val i = instrument) {
                is PolySynth -> i.patch = t.synth ?: SynthPresets.KEYS
                is DrumMachine -> i.kit = t.drumKit ?: DrumKits.KIT_808
                is Sampler -> i.patch = t.sampler ?: SamplerPatch()
                is SoundFontPlayer -> t.soundfont?.let { sf -> i.fontId = sf.fontId; i.bank = sf.bank; i.program = sf.program; i.gain = sf.gain }
            }
        }

        fun flushSequencedNotes() {
            for (e in pending) instrument.noteOff(e.pitch)
            pending.clear()
        }

        fun processFx(t: Track, l: FloatArray, r: FloatArray, n: Int) {
            val fx = t.fx
            if (fx.filterCutoff < 0.995f) {
                val hz = normToCutoffHz(fx.filterCutoff)
                fl.set(hz, fx.filterResonance, sr); fr.set(hz, fx.filterResonance, sr)
                for (i in 0 until n) { fl.process(l[i]); l[i] = fl.lp; fr.process(r[i]); r[i] = fr.lp }
            }
            if (fx.drive > 0.01f) for (i in 0 until n) { l[i] = drive(l[i], fx.drive); r[i] = drive(r[i], fx.drive) }
        }
    }

    private fun createInstrument(t: Track): Instrument = when (t.kind) {
        TrackKind.DRUMS -> DrumMachine(sr, t.drumKit ?: DrumKits.KIT_808, samples)
        TrackKind.SYNTH -> PolySynth(sr, t.synth ?: SynthPresets.KEYS)
        TrackKind.SAMPLER -> Sampler(sr, t.sampler ?: SamplerPatch(), samples)
        TrackKind.SOUNDFONT -> t.soundfont.let { sf -> SoundFontPlayer(sr, soundFonts, sf?.fontId, sf?.bank ?: 0, sf?.program ?: 0, sf?.gain ?: 0.8f) }
    }

    companion object {
        const val NONE = -1
        const val STOP = -2

        /** Swing delays every off-beat 16th by up to a 32nd note. */
        fun swungStart(start: Double, swing: Float): Double {
            if (swing <= 0f) return start
            val sixteenth = start / 0.25
            val idx = floor(sixteenth + 1e-6)
            if (abs(sixteenth - idx) > 1e-3) return start
            return if (idx.toLong() % 2L == 1L) start + swing * 0.125 else start
        }
    }
}

/** Metronome click: short sine blip, accented on the downbeat. */
private class Click(private val sr: Float) {
    private var phase = 0f
    private var freq = 1000f
    private var env = 0f
    private val decay = kotlin.math.exp(-1f / (0.02f * sr))

    fun trigger(accent: Boolean) { freq = if (accent) 1600f else 1000f; env = 1f; phase = 0f }

    fun render(l: FloatArray, r: FloatArray, n: Int, level: Float) {
        if (env < 1e-4f) return
        for (i in 0 until n) {
            phase += freq / sr; if (phase >= 1f) phase -= 1f
            val s = com.notemove.core.dsp.fastSin(phase) * env * level * 0.5f
            env *= decay
            l[i] += s; r[i] += s
        }
    }
}
