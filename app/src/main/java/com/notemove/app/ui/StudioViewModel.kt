package com.notemove.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notemove.app.app
import com.notemove.app.audio.AudioDecoder
import com.notemove.app.audio.MicRecorder
import com.notemove.app.data.ProjectSummary
import com.notemove.app.export.ExportManager
import com.notemove.app.midi.MidiInput
import com.notemove.core.dsp.SampleData
import com.notemove.core.engine.EngineState
import com.notemove.core.export.ProjectPackager
import com.notemove.core.model.BEATS_PER_BAR
import com.notemove.core.model.Clip
import com.notemove.core.model.ClipOps
import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.DrumKit
import com.notemove.core.model.DrumKits
import com.notemove.core.model.DrumPad
import com.notemove.core.model.DrumSound
import com.notemove.core.model.Note
import com.notemove.core.model.Project
import com.notemove.core.model.SampleRef
import com.notemove.core.model.SamplerPatch
import com.notemove.core.model.SynthPatch
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.Track
import com.notemove.core.model.TrackFx
import com.notemove.core.model.TrackKind
import com.notemove.core.model.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The views the editor area can show (bottom tabs on the outer screen, side tabs on the inner screen). */
enum class Panel(val label: String) { SESSION("Session"), PLAY("Play"), EDIT("Edit"), SOUND("Sound"), MIX("Mix") }

data class StudioUi(
    val project: Project? = null,
    val selectedTrackId: String? = null,
    val selectedScene: Int = 0,
    val selectedPad: Int = 0,
    val panel: Panel = Panel.PLAY,
    val octave: Int = 2,
    val inKey: Boolean = true,
    val fixedVelocity: Boolean = false,
    val countIn: Boolean = true,
    val metronome: Boolean = false,
    /** Record quantisation grid in beats, null = off. */
    val recordQuantize: Double? = 0.25,
    val noteRepeat: Double = 0.0,
    val stepGrid: Double = 0.25,
    val stepPage: Int = 0,
    val newClipBars: Int = 2,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val heldPitches: Set<Int> = emptySet(),
    val lastPlayedPitch: Int = 60,
    val message: String? = null,
) {
    val track: Track? get() = project?.tracks?.firstOrNull { it.id == selectedTrackId } ?: project?.tracks?.firstOrNull()
    val clip: Clip? get() = track?.clips?.get(selectedScene)
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Working(val step: String, val progress: Float) : ExportState
    data class Ready(val file: File, val mime: String) : ExportState
    data class Failed(val error: String) : ExportState
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val nm = application.app
    val engine = nm.engine
    private val repo = nm.repository
    val exporter = ExportManager(application, repo, engine.sampleRate, engine.samples)
    val mic = MicRecorder(engine.sampleRate)
    val midiDevices = nm.midi.devices

    private val _ui = MutableStateFlow(StudioUi())
    val ui: StateFlow<StudioUi> = _ui.asStateFlow()

    private val _library = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val library: StateFlow<List<ProjectSummary>> = _library.asStateFlow()

    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private val undo = ArrayDeque<Project>()
    private val redo = ArrayDeque<Project>()
    private var saveJob: Job? = null

    val engineState: EngineState get() = engine.state
    val latencyMs: Float get() = nm.output.latencyMs

    init {
        refreshLibrary()
        // Merge notes recorded by the audio thread into the clip they were played into.
        viewModelScope.launch {
            while (isActive) {
                delay(40)
                val rec = engine.drainRecorded()
                if (rec.isEmpty()) continue
                val q = _ui.value.recordQuantize
                edit(undoable = false) { p ->
                    rec.groupBy { it.trackId to it.scene }.entries.fold(p) { acc, (key, notes) ->
                        acc.updateTrack(key.first) { t ->
                            val clip = t.clips[key.second] ?: return@updateTrack t
                            t.withClip(key.second, ClipOps.merge(clip, notes.map { Note(it.pitch, it.start, it.duration, it.velocity) }, q))
                        }
                    }
                }
            }
        }
        nm.midi.listener = object : MidiInput.Listener {
            override fun onNoteOn(channel: Int, pitch: Int, velocity: Int) = padDown(pitch, velocity)
            override fun onNoteOff(channel: Int, pitch: Int) = padUp(pitch)
        }
        nm.midi.start()
    }

    override fun onCleared() {
        nm.midi.listener = null
        flushSave()
    }

    // ------------------------------------------------------------------ library

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) { _library.value = repo.list() }
    }

    fun newProject(name: String) {
        val p = Project.createDefault(name.ifBlank { "Sketch ${_library.value.size + 1}" })
        repo.save(p)
        open(p)
    }

    fun openProject(id: String) {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { repo.load(id) } ?: return@launch toast("Could not open project")
            open(p)
        }
    }

    private fun open(p: Project) {
        engine.stop()
        engine.panic()
        viewModelScope.launch(Dispatchers.IO) { repo.loadSamples(p, engine.samples) }
        undo.clear(); redo.clear()
        engine.setProject(p)
        _ui.value = StudioUi(project = p, selectedTrackId = p.tracks.firstOrNull()?.id, metronome = _ui.value.metronome, countIn = _ui.value.countIn)
        engine.metronomeEnabled = _ui.value.metronome
    }

    fun closeProject() {
        engine.stop()
        flushSave()
        _ui.value = StudioUi()
        refreshLibrary()
    }

    fun deleteProject(id: String) = viewModelScope.launch(Dispatchers.IO) { repo.delete(id); _library.value = repo.list() }

    fun duplicateProject(id: String, name: String) = viewModelScope.launch(Dispatchers.IO) { repo.duplicate(id, name); _library.value = repo.list() }

    fun renameProject(name: String) = edit { it.copy(name = name.ifBlank { it.name }) }

    fun importFile(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { exporter.import(uri, displayName) } }
            result.onSuccess { open(it); toast("Imported ${it.tracks.size} tracks") }
                .onFailure { toast("Import failed: ${it.message}") }
            refreshLibrary()
        }
    }

    // ------------------------------------------------------------------ editing core

    fun edit(undoable: Boolean = true, f: (Project) -> Project) {
        val cur = _ui.value.project ?: return
        val next = f(cur)
        if (next == cur) return
        if (undoable) {
            undo.addLast(cur); if (undo.size > 100) undo.removeFirst()
            redo.clear()
        }
        val stamped = next.copy(modifiedAt = System.currentTimeMillis())
        engine.setProject(stamped)
        _ui.update { it.copy(project = stamped, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        scheduleSave()
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        _ui.value.project?.let { redo.addLast(it) }
        applyHistory(prev)
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        _ui.value.project?.let { undo.addLast(it) }
        applyHistory(next)
    }

    private fun applyHistory(p: Project) {
        engine.setProject(p)
        _ui.update { s ->
            s.copy(
                project = p,
                selectedTrackId = s.selectedTrackId?.takeIf { id -> p.tracks.any { it.id == id } } ?: p.tracks.firstOrNull()?.id,
                canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
            )
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(600)
            _ui.value.project?.let(repo::save)
        }
    }

    fun flushSave() {
        saveJob?.cancel()
        _ui.value.project?.let { p -> runCatching { repo.save(p) } }
    }

    private fun editTrack(f: (Track) -> Track) {
        val id = _ui.value.track?.id ?: return
        edit { p -> p.updateTrack(id, f) }
    }

    fun editClip(undoable: Boolean = true, f: (Clip) -> Clip) {
        val s = _ui.value
        val t = s.track ?: return
        val clip = t.clips[s.selectedScene] ?: return
        edit(undoable) { p -> p.updateTrack(t.id) { it.withClip(s.selectedScene, f(clip)) } }
    }

    fun toast(msg: String) { _ui.update { it.copy(message = msg) } }
    fun clearMessage() { _ui.update { it.copy(message = null) } }

    // ------------------------------------------------------------------ selection & settings

    fun selectTrack(id: String) {
        val prev = _ui.value.track?.id
        if (prev != null && prev != id) {
            _ui.value.heldPitches.forEach { engine.noteOff(prev, it) }
        }
        _ui.update { it.copy(selectedTrackId = id, heldPitches = emptySet(), stepPage = 0) }
        if (_ui.value.noteRepeat > 0) engine.setNoteRepeat(id, _ui.value.noteRepeat)
    }

    fun selectScene(scene: Int) = _ui.update { it.copy(selectedScene = scene, stepPage = 0) }
    fun selectPad(pad: Int) = _ui.update { it.copy(selectedPad = pad) }
    fun setPanel(p: Panel) = _ui.update { it.copy(panel = p) }
    fun setOctave(o: Int) = _ui.update { it.copy(octave = o.coerceIn(-1, 7)) }
    fun toggleInKey() = _ui.update { it.copy(inKey = !it.inKey) }
    fun toggleFixedVelocity() = _ui.update { it.copy(fixedVelocity = !it.fixedVelocity) }
    fun toggleCountIn() = _ui.update { it.copy(countIn = !it.countIn) }
    fun setRecordQuantize(q: Double?) = _ui.update { it.copy(recordQuantize = q) }
    fun setStepGrid(g: Double) = _ui.update { it.copy(stepGrid = g, stepPage = 0) }
    fun setStepPage(p: Int) = _ui.update { it.copy(stepPage = p.coerceAtLeast(0)) }
    fun setNewClipBars(b: Int) = _ui.update { it.copy(newClipBars = b) }

    fun toggleMetronome() {
        _ui.update { it.copy(metronome = !it.metronome) }
        engine.metronomeEnabled = _ui.value.metronome
    }

    fun setNoteRepeat(rate: Double) {
        _ui.update { it.copy(noteRepeat = rate) }
        engine.setNoteRepeat(_ui.value.track?.id, rate)
    }

    fun setTempo(bpm: Double) = edit(undoable = false) { it.copy(tempo = bpm.coerceIn(20.0, 300.0)) }
    fun setSwing(s: Float) = edit(undoable = false) { it.copy(swing = s.coerceIn(0f, 1f)) }
    fun setKey(root: Int, scale: com.notemove.core.model.Scale) = edit { it.copy(rootNote = root, scale = scale) }
    fun setLaunchQuantization(q: com.notemove.core.model.LaunchQuantization) = edit { it.copy(launchQuantization = q) }
    fun setMasterVolume(v: Float) = edit(undoable = false) { it.copy(masterVolume = v) }
    fun setGlobalFx(f: (com.notemove.core.model.GlobalFx) -> com.notemove.core.model.GlobalFx) = edit(undoable = false) { it.copy(globalFx = f(it.globalFx)) }

    private val tapTimes = ArrayDeque<Long>()
    fun tapTempo() {
        val now = System.nanoTime()
        if (tapTimes.isNotEmpty() && now - tapTimes.last() > 2_000_000_000L) tapTimes.clear()
        tapTimes.addLast(now)
        if (tapTimes.size > 5) tapTimes.removeFirst()
        if (tapTimes.size >= 2) {
            val avg = (tapTimes.last() - tapTimes.first()) / (tapTimes.size - 1).toDouble()
            setTempo(Math.round(60e9 / avg * 10) / 10.0)
        }
    }

    // ------------------------------------------------------------------ playing

    fun padDown(pitch: Int, velocity: Int) {
        val t = _ui.value.track ?: return
        val vel = if (_ui.value.fixedVelocity) 100 else velocity
        engine.noteOn(t.id, pitch, vel)
        _ui.update {
            it.copy(
                heldPitches = it.heldPitches + pitch, lastPlayedPitch = pitch,
                selectedPad = if (t.kind == TrackKind.DRUMS && pitch - DRUM_BASE_NOTE in 0..15) pitch - DRUM_BASE_NOTE else it.selectedPad,
            )
        }
    }

    fun padUp(pitch: Int) {
        val t = _ui.value.track ?: return
        engine.noteOff(t.id, pitch)
        _ui.update { it.copy(heldPitches = it.heldPitches - pitch) }
    }

    fun togglePlay() {
        if (engine.state.playing) engine.stop() else engine.play(_ui.value.selectedScene, countIn = false)
    }

    fun toggleRecord() {
        val s = _ui.value
        if (engine.state.recording) { engine.stopRecording(); return }
        val t = s.track ?: return
        if (t.clips[s.selectedScene] == null) {
            edit { p -> p.updateTrack(t.id) { it.withClip(s.selectedScene, Clip(lengthBeats = s.newClipBars * BEATS_PER_BAR)) } }
        } else {
            // One undo step for the whole take.
            _ui.value.project?.let { undo.addLast(it); redo.clear(); _ui.update { u -> u.copy(canUndo = true, canRedo = false) } }
        }
        engine.record(t.id, s.selectedScene, countIn = s.countIn && !engine.state.playing)
    }

    /** Move's Capture: make a clip from what was just played, even though recording was off. */
    fun capture() {
        val s = _ui.value
        val t = s.track ?: return
        val clip = ClipOps.capture(engine.recentlyPlayed(t.id)) ?: return toast("Play something first, then Capture")
        val scene = if (t.clips[s.selectedScene] == null) s.selectedScene
            else (0 until (s.project?.sceneCount ?: 8)).firstOrNull { t.clips[it] == null } ?: return toast("No free clip slot on this track")
        edit { p -> p.updateTrack(t.id) { it.withClip(scene, clip) } }
        engine.clearPlayed(t.id)
        _ui.update { it.copy(selectedScene = scene) }
        engine.launchClip(t.id, scene)
        toast("Captured ${clip.notes.size} notes · ${clip.bars.toInt()} bar${if (clip.bars > 1) "s" else ""}")
    }

    fun launchClip(trackId: String, scene: Int) {
        _ui.update { it.copy(selectedTrackId = trackId, selectedScene = scene) }
        val t = _ui.value.project?.track(trackId) ?: return
        if (t.clips[scene] != null) engine.launchClip(trackId, scene)
    }

    fun launchScene(scene: Int) { _ui.update { it.copy(selectedScene = scene) }; engine.launchScene(scene) }
    fun stopTrack(trackId: String) = engine.stopTrack(trackId)
    fun stopAll() { _ui.value.project?.tracks?.forEach { engine.stopTrack(it.id) } }

    // ------------------------------------------------------------------ clips

    fun createClip(trackId: String, scene: Int, bars: Int = _ui.value.newClipBars) {
        edit { p -> p.updateTrack(trackId) { if (it.clips[scene] != null) it else it.withClip(scene, Clip(lengthBeats = bars * BEATS_PER_BAR)) } }
        _ui.update { it.copy(selectedTrackId = trackId, selectedScene = scene) }
    }

    fun deleteClip(trackId: String, scene: Int) {
        engine.stopTrack(trackId)
        edit { p -> p.updateTrack(trackId) { it.withClip(scene, null) } }
    }

    fun duplicateClip(trackId: String, scene: Int) {
        val p = _ui.value.project ?: return
        val t = p.track(trackId) ?: return
        val clip = t.clips[scene] ?: return
        val target = (scene + 1 until p.sceneCount).firstOrNull { t.clips[it] == null }
        if (target == null) {
            if (p.sceneCount >= Project.MAX_SCENES) return toast("No free slot")
            edit { pr -> pr.copy(sceneCount = pr.sceneCount + 1).updateTrack(trackId) { it.withClip(pr.sceneCount, clip.copy(id = newId())) } }
            _ui.update { it.copy(selectedScene = p.sceneCount) }
        } else {
            edit { pr -> pr.updateTrack(trackId) { it.withClip(target, clip.copy(id = newId())) } }
            _ui.update { it.copy(selectedScene = target) }
        }
    }

    fun clearClipNotes() = editClip { it.copy(notes = emptyList()) }
    fun quantizeClip(grid: Double) = editClip { ClipOps.quantize(it, grid) }
    fun duplicateLoop() = editClip { ClipOps.duplicateLoop(it) }
    fun setClipLength(beats: Double) = editClip { ClipOps.setLength(it, beats) }
    fun transposeClip(semis: Int) = editClip { ClipOps.transpose(it, semis) }
    fun nudgeClip(beats: Double) = editClip { ClipOps.nudge(it, beats) }
    fun renameClip(name: String) = editClip { it.copy(name = name) }

    fun toggleStep(pitch: Int, start: Double, length: Double, velocity: Int = 100) {
        val s = _ui.value
        val t = s.track ?: return
        if (t.clips[s.selectedScene] == null) createClip(t.id, s.selectedScene)
        editClip { ClipOps.toggleStep(it, pitch, start, length, velocity) }
    }

    fun setNotes(notes: List<Note>, undoable: Boolean = true) = editClip(undoable) { it.withNotes(notes) }

    fun duplicateScene(scene: Int) {
        val p = _ui.value.project ?: return
        if (p.sceneCount >= Project.MAX_SCENES) return toast("Scene limit reached")
        edit { pr ->
            val shifted = pr.tracks.map { t ->
                val clips = t.clips.mapKeys { (k, _) -> if (k > scene) k + 1 else k }.toMutableMap()
                t.clips[scene]?.let { clips[scene + 1] = it.copy(id = newId()) }
                t.copy(clips = clips)
            }
            pr.copy(tracks = shifted, sceneCount = pr.sceneCount + 1,
                sceneNames = pr.sceneNames.mapKeys { (k, _) -> if (k > scene) k + 1 else k })
        }
        _ui.update { it.copy(selectedScene = scene + 1) }
    }

    fun deleteScene(scene: Int) {
        val p = _ui.value.project ?: return
        if (p.sceneCount <= 1) return
        edit { pr ->
            val shifted = pr.tracks.map { t -> t.copy(clips = t.clips.filterKeys { it != scene }.mapKeys { (k, _) -> if (k > scene) k - 1 else k }) }
            pr.copy(tracks = shifted, sceneCount = pr.sceneCount - 1,
                sceneNames = pr.sceneNames.filterKeys { it != scene }.mapKeys { (k, _) -> if (k > scene) k - 1 else k })
        }
        _ui.update { it.copy(selectedScene = it.selectedScene.coerceAtMost(p.sceneCount - 2)) }
    }

    fun renameScene(scene: Int, name: String) = edit { it.copy(sceneNames = it.sceneNames + (scene to name)) }
    fun addScene() = edit { if (it.sceneCount < Project.MAX_SCENES) it.copy(sceneCount = it.sceneCount + 1) else it }

    // ------------------------------------------------------------------ tracks

    fun addTrack(kind: TrackKind) {
        val p = _ui.value.project ?: return
        if (p.tracks.size >= Project.MAX_TRACKS) return toast("Track limit reached")
        val color = (p.tracks.maxOfOrNull { it.color } ?: 0) + 3
        val t = when (kind) {
            TrackKind.DRUMS -> Track(name = "Drums ${p.tracks.count { it.kind == kind } + 1}", kind = kind, color = color, drumKit = DrumKits.KIT_909)
            TrackKind.SYNTH -> Track(name = "Synth ${p.tracks.count { it.kind == kind } + 1}", kind = kind, color = color, synth = SynthPresets.PAD)
            TrackKind.SAMPLER -> Track(name = "Sampler ${p.tracks.count { it.kind == kind } + 1}", kind = kind, color = color, sampler = SamplerPatch())
        }
        edit { it.copy(tracks = it.tracks + t) }
        selectTrack(t.id)
    }

    fun deleteTrack(id: String) {
        val p = _ui.value.project ?: return
        if (p.tracks.size <= 1) return toast("A set needs at least one track")
        engine.stopTrack(id)
        edit { it.copy(tracks = it.tracks.filter { t -> t.id != id }) }
        if (_ui.value.selectedTrackId == id) _ui.update { it.copy(selectedTrackId = _ui.value.project?.tracks?.firstOrNull()?.id) }
    }

    fun duplicateTrack(id: String) {
        val p = _ui.value.project ?: return
        if (p.tracks.size >= Project.MAX_TRACKS) return toast("Track limit reached")
        val t = p.track(id) ?: return
        val copy = t.copy(id = newId(), name = "${t.name} 2", solo = false)
        val idx = p.tracks.indexOf(t)
        edit { it.copy(tracks = it.tracks.toMutableList().apply { add(idx + 1, copy) }) }
    }

    fun moveTrack(id: String, delta: Int) = edit { p ->
        val i = p.tracks.indexOfFirst { it.id == id }
        val j = (i + delta).coerceIn(0, p.tracks.lastIndex)
        if (i < 0 || i == j) p else p.copy(tracks = p.tracks.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun renameTrack(id: String, name: String) = edit { p -> p.updateTrack(id) { it.copy(name = name.ifBlank { it.name }) } }
    fun setTrackColor(id: String, color: Int) = edit { p -> p.updateTrack(id) { it.copy(color = color) } }
    fun setVolume(id: String, v: Float) = edit(undoable = false) { p -> p.updateTrack(id) { it.copy(volume = v.coerceIn(0f, 1f)) } }
    fun setPan(id: String, v: Float) = edit(undoable = false) { p -> p.updateTrack(id) { it.copy(pan = v.coerceIn(-1f, 1f)) } }
    fun toggleMute(id: String) = edit { p -> p.updateTrack(id) { it.copy(mute = !it.mute) } }
    fun toggleSolo(id: String) = edit { p -> p.updateTrack(id) { it.copy(solo = !it.solo) } }
    fun setTrackFx(f: (TrackFx) -> TrackFx) = editTrack { it.copy(fx = f(it.fx)) }

    fun changeTrackKind(kind: TrackKind) = editTrack { t ->
        when (kind) {
            TrackKind.DRUMS -> t.copy(kind = kind, drumKit = t.drumKit ?: DrumKits.KIT_808)
            TrackKind.SYNTH -> t.copy(kind = kind, synth = t.synth ?: SynthPresets.KEYS)
            TrackKind.SAMPLER -> t.copy(kind = kind, sampler = t.sampler ?: SamplerPatch())
        }
    }

    // ------------------------------------------------------------------ sounds

    fun setSynth(patch: SynthPatch, undoable: Boolean = false) = edit(undoable) { p -> p.updateTrack(_ui.value.track!!.id) { it.copy(synth = patch) } }
    fun setKit(kit: DrumKit) = editTrack { it.copy(drumKit = kit) }
    fun setPad(index: Int, pad: DrumPad, undoable: Boolean = false) = edit(undoable) { p ->
        p.updateTrack(_ui.value.track!!.id) { t -> t.copy(drumKit = (t.drumKit ?: DrumKits.KIT_808).withPad(index, pad)) }
    }
    fun setSampler(patch: SamplerPatch, undoable: Boolean = false) = edit(undoable) { p -> p.updateTrack(_ui.value.track!!.id) { it.copy(sampler = patch) } }

    fun auditionPad(pad: Int) {
        val t = _ui.value.track ?: return
        engine.noteOn(t.id, DRUM_BASE_NOTE + pad, 110)
        viewModelScope.launch { delay(150); engine.noteOff(t.id, DRUM_BASE_NOTE + pad) }
    }

    /** Adds audio as a project sample and assigns it to the selected drum pad or sampler track. */
    fun assignSample(name: String, data: FloatArray) {
        val p = _ui.value.project ?: return
        val t = _ui.value.track ?: return
        viewModelScope.launch {
            val ref: SampleRef = withContext(Dispatchers.IO) { repo.addSample(p.id, name, data, engine.sampleRate) }
            engine.samples.put(ref.id, SampleData(data, engine.sampleRate))
            edit { pr ->
                pr.copy(samples = pr.samples + ref).updateTrack(t.id) { tr ->
                    when (tr.kind) {
                        TrackKind.DRUMS -> {
                            val kit = tr.drumKit ?: DrumKits.KIT_808
                            val pad = _ui.value.selectedPad
                            tr.copy(drumKit = kit.withPad(pad, kit.pads[pad].copy(name = name.take(12), sound = DrumSound.SAMPLE, sampleId = ref.id, tune = 0f, decay = 1f)))
                        }
                        TrackKind.SAMPLER -> tr.copy(sampler = (tr.sampler ?: SamplerPatch()).copy(sampleId = ref.id, name = name, start = 0f, end = 1f))
                        TrackKind.SYNTH -> tr.copy(kind = TrackKind.SAMPLER, sampler = SamplerPatch(sampleId = ref.id, name = name))
                    }
                }
            }
            toast("Sample \"$name\" loaded")
        }
    }

    fun assignExistingSample(ref: SampleRef) {
        if (engine.samples[ref.id] == null) return toast("Sample missing")
        val t = _ui.value.track ?: return
        edit { pr ->
            pr.updateTrack(t.id) { tr ->
                when (tr.kind) {
                    TrackKind.DRUMS -> {
                        val kit = tr.drumKit ?: DrumKits.KIT_808
                        val pad = _ui.value.selectedPad
                        tr.copy(drumKit = kit.withPad(pad, kit.pads[pad].copy(name = ref.name.take(12), sound = DrumSound.SAMPLE, sampleId = ref.id)))
                    }
                    else -> tr.copy(kind = TrackKind.SAMPLER, sampler = (tr.sampler ?: SamplerPatch()).copy(sampleId = ref.id, name = ref.name))
                }
            }
        }
    }

    fun importSample(uri: Uri, name: String) {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { AudioDecoder.decode(getApplication(), uri, engine.sampleRate) } }
            data.onSuccess { d ->
                val processed = MicRecorder.process(d, engine.sampleRate, threshold = 0.005f) ?: return@onSuccess toast("That file is silent")
                assignSample(name.substringBeforeLast('.'), processed)
            }.onFailure { toast("Could not read audio: ${it.message}") }
        }
    }

    fun startMic(): Boolean = mic.start()

    fun stopMic(name: String) {
        val data = mic.stop() ?: return toast("Nothing recorded (too quiet)")
        assignSample(name, data)
    }

    // ------------------------------------------------------------------ export

    fun exportProject(options: ProjectPackager.Options) {
        val p = _ui.value.project ?: return
        flushSave()
        _export.value = ExportState.Working("Preparing", 0f)
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                exporter.buildProjectZip(p, options) { step, pr -> _export.value = ExportState.Working(step, pr) }
            }.onSuccess { _export.value = ExportState.Ready(it, "application/zip") }
                .onFailure { _export.value = ExportState.Failed(it.message ?: it.toString()) }
        }
    }

    fun exportAls(arrangement: Boolean) {
        val p = _ui.value.project ?: return
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { exporter.buildAls(p, arrangement) }
                .onSuccess { _export.value = ExportState.Ready(it, "application/octet-stream") }
                .onFailure { _export.value = ExportState.Failed(it.message ?: it.toString()) }
        }
    }

    fun saveExportTo(uri: Uri) {
        val s = _export.value as? ExportState.Ready ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { exporter.copyTo(s.file, uri) }
                .onSuccess { toast("Saved ${s.file.name}") }
                .onFailure { toast("Save failed: ${it.message}") }
        }
    }

    fun resetExport() { _export.value = ExportState.Idle }
}
