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
import com.notemove.core.model.ArpSettings
import com.notemove.core.model.BEATS_PER_BAR
import com.notemove.core.model.EffectSlot
import com.notemove.core.model.EffectType
import com.notemove.core.model.MAX_EFFECTS
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
import com.notemove.core.model.SoundFontPatch
import com.notemove.core.model.SoundFontRef
import com.notemove.core.dsp.SoundFont
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
enum class Panel(val label: String) { SESSION("Session"), PLAY("Play"), EDIT("Edit"), SOUND("Sound"), FX("FX"), MIX("Mix") }

/** Push-style pad modes: play the full grid, or split it into step sequencer (top) and pads (bottom). */
enum class PadMode(val label: String) { PLAY("Play"), SEQUENCE("Sequence") }

/** Interval between pad rows in the melodic layout (Push's "Layout" setting). */
enum class RowLayout(val label: String, val inKeySteps: Int, val semitones: Int) {
    FOURTHS("4ths", 3, 5), THIRDS("3rds", 2, 4), SEQUENTIAL("Sequential", -1, -1)
}

/** Sheets the keyboard can open (Ctrl+E, Ctrl+,). */
enum class Overlay { SETTINGS, EXPORT }

/** Live-style computer MIDI keyboard: [octave] is where the A key sits on melodic tracks (C3 = middle C), [drumOctave] on drums. */
data class ComputerKeys(val enabled: Boolean = true, val octave: Int = 3, val drumOctave: Int = 1, val velocity: Int = 100)

/** A clip slot, used for multi-selection in the session grid. */
data class SlotRef(val trackId: String, val scene: Int)

/** Where an effect chain lives: a track, or the master bus (trackId == null). */
data class FxTarget(val trackId: String?)

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
    /** Multi-selection of clip slots (touch & hold a clip, then tap others). */
    val clipSelection: Set<SlotRef> = emptySet(),
    /** Multi-selection of notes in the selected clip. */
    val noteSelection: Set<Note> = emptySet(),
    /** Multi-selection of sets in the library. */
    val librarySelection: Set<String> = emptySet(),
    /** Sample open in the spectral editor. */
    val editingSampleId: String? = null,
    /** Sample open in the slicer. */
    val slicingSampleId: String? = null,
    val fxTarget: FxTarget = FxTarget(null),
    val padMode: PadMode = PadMode.PLAY,
    val rowLayout: RowLayout = RowLayout.FOURTHS,
    /** Push's Accent: every pad and step plays at full velocity. */
    val accent: Boolean = false,
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
    val exporter = ExportManager(application, repo, engine.sampleRate, engine.samples, engine.soundFonts)
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
        viewModelScope.launch(Dispatchers.IO) {
            repo.loadSamples(p, engine.samples)
            repo.loadSoundFonts(p, engine.soundFonts)
            engine.setProject(_ui.value.project ?: p) // re-resolve presets once fonts are parsed
            fontsVersion.value++
        }
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
        _ui.update { it.copy(selectedTrackId = id, heldPitches = emptySet(), stepPage = 0, noteSelection = emptySet(), fxTarget = FxTarget(id)) }
        if (_ui.value.noteRepeat > 0) engine.setNoteRepeat(id, _ui.value.noteRepeat)
    }

    fun selectScene(scene: Int) = _ui.update { it.copy(selectedScene = scene, stepPage = 0, noteSelection = emptySet()) }
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
        val vel = if (_ui.value.accent) 127 else if (_ui.value.fixedVelocity) 100 else velocity
        engine.noteOn(t.id, pitch, vel)
        _ui.update {
            it.copy(
                heldPitches = it.heldPitches + pitch, lastPlayedPitch = pitch,
                selectedPad = if (t.drumLayout && pitch - DRUM_BASE_NOTE in 0..15) pitch - DRUM_BASE_NOTE else it.selectedPad,
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

    fun toggleStep(pitch: Int, start: Double, length: Double, velocity: Int = if (_ui.value.accent) 127 else 100) {
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
            TrackKind.SOUNDFONT -> Track(name = "SoundFont ${p.tracks.count { it.kind == kind } + 1}", kind = kind, color = color, soundfont = SoundFontPatch())
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
            TrackKind.SOUNDFONT -> t.copy(kind = kind, soundfont = t.soundfont ?: SoundFontPatch())
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
                        TrackKind.SYNTH, TrackKind.SOUNDFONT -> tr.copy(kind = TrackKind.SAMPLER, sampler = SamplerPatch(sampleId = ref.id, name = name))
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

    // ------------------------------------------------------------------ layout (resizable panes)

    private val prefs = application.getSharedPreferences("layout", android.content.Context.MODE_PRIVATE)
    private val _splits = MutableStateFlow(prefs.all.mapNotNull { (k, v) -> (v as? Float)?.let { k to it } }.toMap())
    val splits: StateFlow<Map<String, Float>> = _splits.asStateFlow()

    fun setSplit(key: String, fraction: Float) {
        _splits.update { it + (key to fraction) }
        prefs.edit().putFloat(key, fraction).apply()
    }

    fun resetSplits() { _splits.value = emptyMap(); prefs.edit().clear().apply() }

    // ------------------------------------------------------------------ arpeggiator

    fun setArp(f: (ArpSettings) -> ArpSettings) {
        val t = _ui.value.track ?: return
        // Release anything held so no note hangs when switching between arp and direct play.
        _ui.value.heldPitches.forEach { engine.noteOff(t.id, it) }
        _ui.update { it.copy(heldPitches = emptySet()) }
        edit { p -> p.updateTrack(t.id) { it.copy(arp = f(it.arp)) } }
    }

    // ------------------------------------------------------------------ effects

    fun setFxTarget(target: FxTarget) = _ui.update { it.copy(fxTarget = target) }

    fun effectsOf(p: Project, target: FxTarget): List<EffectSlot> =
        if (target.trackId == null) p.masterEffects else p.track(target.trackId)?.effects ?: emptyList()

    private fun editEffects(target: FxTarget, undoable: Boolean = true, f: (List<EffectSlot>) -> List<EffectSlot>) = edit(undoable) { p ->
        if (target.trackId == null) p.copy(masterEffects = f(p.masterEffects))
        else p.updateTrack(target.trackId) { it.copy(effects = f(it.effects)) }
    }

    fun addEffect(target: FxTarget, type: EffectType) {
        val p = _ui.value.project ?: return
        if (effectsOf(p, target).size >= MAX_EFFECTS) return toast("Up to $MAX_EFFECTS effects per chain")
        editEffects(target) { it + EffectSlot(type = type) }
    }

    fun removeEffect(target: FxTarget, id: String) = editEffects(target) { l -> l.filter { it.id != id } }
    fun toggleEffect(target: FxTarget, id: String) = editEffects(target) { l -> l.map { if (it.id == id) it.copy(enabled = !it.enabled) else it } }
    fun setEffectValue(target: FxTarget, id: String, index: Int, v: Float) =
        editEffects(target, undoable = false) { l -> l.map { if (it.id == id) it.with(index, v) else it } }
    fun resetEffect(target: FxTarget, id: String) = editEffects(target) { l -> l.map { if (it.id == id) it.copy(values = it.type.defaults()) else it } }
    fun duplicateEffect(target: FxTarget, id: String) {
        val p = _ui.value.project ?: return
        if (effectsOf(p, target).size >= MAX_EFFECTS) return toast("Up to $MAX_EFFECTS effects per chain")
        editEffects(target) { l -> l.flatMap { if (it.id == id) listOf(it, it.copy(id = newId())) else listOf(it) } }
    }
    fun moveEffect(target: FxTarget, id: String, delta: Int) = editEffects(target) { l ->
        val i = l.indexOfFirst { it.id == id }; val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i < 0 || i == j) l else l.toMutableList().apply { add(j, removeAt(i)) }
    }

    // ------------------------------------------------------------------ clip multi-selection & clipboard

    private var clipClipboard: List<Triple<Int, Int, Clip>> = emptyList() // (track offset, scene offset, clip)
    private var noteClipboard: List<Note> = emptyList()
    val hasClipClipboard get() = clipClipboard.isNotEmpty()
    val hasNoteClipboard get() = noteClipboard.isNotEmpty()

    fun toggleClipSelection(ref: SlotRef) = _ui.update {
        it.copy(clipSelection = if (ref in it.clipSelection) it.clipSelection - ref else it.clipSelection + ref)
    }
    fun clearClipSelection() = _ui.update { it.copy(clipSelection = emptySet()) }

    fun selectClipsInScene(scene: Int) {
        val p = _ui.value.project ?: return
        _ui.update { it.copy(clipSelection = p.tracks.filter { t -> t.clips.containsKey(scene) }.map { t -> SlotRef(t.id, scene) }.toSet()) }
    }

    fun selectClipsInTrack(trackId: String) {
        val t = _ui.value.project?.track(trackId) ?: return
        _ui.update { it.copy(clipSelection = t.clips.keys.map { s -> SlotRef(trackId, s) }.toSet()) }
    }

    private fun selectedClips(p: Project) = _ui.value.clipSelection.mapNotNull { ref ->
        val ti = p.tracks.indexOfFirst { it.id == ref.trackId }
        val clip = p.tracks.getOrNull(ti)?.clips?.get(ref.scene)
        if (clip == null) null else Triple(ti, ref.scene, clip)
    }

    fun copySelectedClips() {
        val p = _ui.value.project ?: return
        val sel = selectedClips(p)
        if (sel.isEmpty()) return
        val t0 = sel.minOf { it.first }; val s0 = sel.minOf { it.second }
        clipClipboard = sel.map { Triple(it.first - t0, it.second - s0, it.third) }
        toast("Copied ${sel.size} clip${if (sel.size > 1) "s" else ""}")
    }

    /** Pastes copied clips with their top-left corner at the selected slot. */
    fun pasteClips() {
        val s = _ui.value
        val p = s.project ?: return
        if (clipClipboard.isEmpty()) return
        val ti0 = p.tracks.indexOfFirst { it.id == s.track?.id }.coerceAtLeast(0)
        val needScenes = s.selectedScene + clipClipboard.maxOf { it.second } + 1
        edit { pr ->
            var out = pr.copy(sceneCount = maxOf(pr.sceneCount, minOf(needScenes, Project.MAX_SCENES)))
            for ((dt, ds, clip) in clipClipboard) {
                val t = out.tracks.getOrNull(ti0 + dt) ?: continue
                val scene = s.selectedScene + ds
                if (scene >= Project.MAX_SCENES) continue
                out = out.updateTrack(t.id) { it.withClip(scene, clip.copy(id = newId())) }
            }
            out
        }
    }

    fun deleteSelectedClips() {
        val sel = _ui.value.clipSelection
        sel.map { it.trackId }.distinct().forEach { engine.stopTrack(it) }
        edit { p -> sel.fold(p) { acc, ref -> acc.updateTrack(ref.trackId) { it.withClip(ref.scene, null) } } }
        clearClipSelection()
    }

    fun duplicateSelectedClips() {
        copySelectedClips()
        val p = _ui.value.project ?: return
        val sel = selectedClips(p)
        if (sel.isEmpty()) return
        val span = sel.maxOf { it.second } - sel.minOf { it.second } + 1
        val t0 = sel.minOf { it.first }
        _ui.update { it.copy(selectedTrackId = p.tracks[t0].id, selectedScene = sel.minOf { c -> c.second } + span) }
        pasteClips()
        clearClipSelection()
    }

    fun quantizeSelectedClips(grid: Double) = edit { p ->
        _ui.value.clipSelection.fold(p) { acc, ref ->
            acc.updateTrack(ref.trackId) { t -> t.clips[ref.scene]?.let { t.withClip(ref.scene, ClipOps.quantize(it, grid)) } ?: t }
        }
    }

    fun launchSelectedClips() = _ui.value.clipSelection.forEach { engine.launchClip(it.trackId, it.scene) }

    // ------------------------------------------------------------------ note multi-selection

    fun setNoteSelection(notes: Set<Note>) = _ui.update { it.copy(noteSelection = notes) }
    fun toggleNoteSelection(n: Note) = _ui.update { it.copy(noteSelection = if (n in it.noteSelection) it.noteSelection - n else it.noteSelection + n) }
    fun clearNoteSelection() = _ui.update { it.copy(noteSelection = emptySet()) }
    fun selectAllNotes() = _ui.update { it.copy(noteSelection = it.clip?.notes?.toSet() ?: emptySet()) }

    /** Applies [f] to the selected notes (or all notes when nothing is selected) and keeps them selected. */
    fun transformNotes(undoable: Boolean = true, f: (Note) -> Note?) {
        val s = _ui.value
        val clip = s.clip ?: return
        val targets = s.noteSelection.ifEmpty { clip.notes.toSet() }
        val newSel = HashSet<Note>()
        val out = clip.notes.mapNotNull { n ->
            if (n in targets) f(n)?.also { if (s.noteSelection.isNotEmpty()) newSel.add(it) } else n
        }
        editClip(undoable) { it.withNotes(out) }
        _ui.update { it.copy(noteSelection = newSel) }
    }

    fun deleteSelectedNotes() {
        val sel = _ui.value.noteSelection
        if (sel.isEmpty()) return
        editClip { c -> c.withNotes(c.notes.filter { it !in sel }) }
        clearNoteSelection()
    }

    fun copyNotes() {
        val s = _ui.value
        val notes = s.noteSelection.ifEmpty { s.clip?.notes?.toSet() ?: emptySet() }
        if (notes.isEmpty()) return
        val t0 = notes.minOf { it.start }
        noteClipboard = notes.map { it.copy(start = it.start - t0) }
        toast("Copied ${notes.size} notes")
    }

    /** Pastes at the end of the current selection (or at the start of the clip). */
    fun pasteNotes() {
        val s = _ui.value
        val clip = s.clip ?: return
        if (noteClipboard.isEmpty()) return
        val at = s.noteSelection.maxOfOrNull { it.end }?.let { ClipOps.snap(it, s.stepGrid) } ?: 0.0
        val pasted = noteClipboard.map { it.copy(start = (it.start + at) % clip.lengthBeats) }
        editClip { c -> c.withNotes(c.notes + pasted) }
        _ui.update { it.copy(noteSelection = pasted.toSet()) }
    }

    fun duplicateSelectedNotes() {
        val s = _ui.value
        val clip = s.clip ?: return
        val sel = s.noteSelection
        if (sel.isEmpty()) return
        val span = ClipOps.snap(sel.maxOf { it.end } - sel.minOf { it.start }, s.stepGrid).coerceAtLeast(s.stepGrid)
        val copies = sel.map { it.copy(start = (it.start + span) % clip.lengthBeats) }
        editClip { c -> c.withNotes(c.notes + copies) }
        _ui.update { it.copy(noteSelection = copies.toSet()) }
    }

    fun legatoSelectedNotes() {
        val s = _ui.value
        val clip = s.clip ?: return
        val sel = s.noteSelection.ifEmpty { clip.notes.toSet() }
        val starts = clip.notes.map { it.start }.distinct().sorted()
        transformNotes { n ->
            if (n !in sel) n else {
                val next = starts.firstOrNull { it > n.start + 1e-6 } ?: clip.lengthBeats
                n.copy(duration = next - n.start)
            }
        }
    }

    // ------------------------------------------------------------------ library multi-selection

    fun toggleLibrarySelection(id: String) = _ui.update {
        it.copy(librarySelection = if (id in it.librarySelection) it.librarySelection - id else it.librarySelection + id)
    }
    fun clearLibrarySelection() = _ui.update { it.copy(librarySelection = emptySet()) }
    fun deleteSelectedProjects() {
        val ids = _ui.value.librarySelection
        viewModelScope.launch(Dispatchers.IO) { ids.forEach(repo::delete); _library.value = repo.list() }
        clearLibrarySelection()
    }
    fun duplicateSelectedProjects() {
        val ids = _ui.value.librarySelection
        val names = _library.value.associate { it.id to it.name }
        viewModelScope.launch(Dispatchers.IO) { ids.forEach { repo.duplicate(it, "${names[it] ?: "Set"} copy") }; _library.value = repo.list() }
        clearLibrarySelection()
    }

    // ------------------------------------------------------------------ sample editor

    /** Sample used by the selected sampler track or drum pad. */
    fun currentSampleId(): String? {
        val t = _ui.value.track ?: return null
        return when (t.kind) {
            TrackKind.SAMPLER -> t.sampler?.sampleId
            TrackKind.DRUMS -> t.drumKit?.pads?.getOrNull(_ui.value.selectedPad)?.sampleId
            TrackKind.SYNTH, TrackKind.SOUNDFONT -> null
        }
    }

    fun openSampleEditor(sampleId: String? = currentSampleId()) {
        if (sampleId == null || engine.samples[sampleId] == null) return toast("Load or record a sample first")
        _ui.update { it.copy(editingSampleId = sampleId) }
    }

    fun closeSampleEditor() { engine.stopPreview(); _ui.update { it.copy(editingSampleId = null) } }

    fun sampleData(id: String): SampleData? = engine.samples[id]

    /** Replaces a sample's audio everywhere it is used (and on disk). */
    fun replaceSample(id: String, data: FloatArray) {
        val p = _ui.value.project ?: return
        val ref = p.sample(id) ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.sampleFile(p.id, ref.fileName).outputStream().use { com.notemove.core.export.Wav.write(it, engine.sampleRate, data, null, 24) }
            }
            engine.samples.put(id, SampleData(data, engine.sampleRate))
            edit { pr -> pr.copy(samples = pr.samples.map { if (it.id == id) it.copy(frames = data.size, sampleRate = engine.sampleRate) else it }) }
            toast("Sample saved")
        }
    }

    /** Saves edited audio as a new sample and assigns it to the current pad / sampler. */
    fun saveSampleAsNew(name: String, data: FloatArray) = assignSample(name, data)

    // ------------------------------------------------------------------ slicing

    fun openSlicer(sampleId: String? = currentSampleId()) {
        if (sampleId == null || engine.samples[sampleId] == null) return toast("Load or record a sample first")
        engine.stopPreview()
        _ui.update { it.copy(slicingSampleId = sampleId, editingSampleId = null) }
    }

    fun closeSlicer() { engine.stopPreview(); _ui.update { it.copy(slicingSampleId = null) } }

    /**
     * Makes a new drum track with slice `i` of [sampleId] on pad `i` (Move / Live's "Slice to Drum Rack"),
     * optionally with a clip in the selected scene that replays the slices in their original rhythm.
     */
    fun sliceToDrumRack(sampleId: String, points: IntArray, loopBeats: Double, makeClip: Boolean, choke: Boolean) {
        val p = _ui.value.project ?: return
        val ref = p.sample(sampleId) ?: return
        val data = engine.samples[sampleId] ?: return
        if (p.tracks.size >= Project.MAX_TRACKS) return toast("Track limit reached — delete a track first")
        val name = ref.name.take(14)
        val kit = com.notemove.core.dsp.Slicer.toDrumKit("$name Slices", sampleId, points, data.frames, DrumKits.KIT_808, choke)
        val scene = _ui.value.selectedScene
        val color = (p.tracks.maxOfOrNull { it.color } ?: 0) + 3
        var track = Track(name = "$name Slices", kind = TrackKind.DRUMS, color = color, drumKit = kit)
        if (makeClip) track = track.withClip(scene, com.notemove.core.dsp.Slicer.toClip(points, data.frames, loopBeats, name))
        edit { it.copy(tracks = it.tracks + track) }
        closeSlicer()
        _ui.update { it.copy(selectedTrackId = track.id, selectedPad = 0, noteSelection = emptySet()) }
        toast("Sliced into ${points.size} pads${if (makeClip) " · clip in ${p.sceneName(scene)}" else ""}")
    }

    /** Sets the sampler's start/end (fractions) from a selection in the editor. */
    fun setSamplerRegion(start: Float, end: Float) {
        val t = _ui.value.track ?: return
        if (t.kind != TrackKind.SAMPLER) return
        setSampler((t.sampler ?: SamplerPatch()).copy(start = start, end = end), undoable = true)
    }

    // ------------------------------------------------------------------ gestures support

    /** Pushes an undo step before a continuous gesture (dragging notes) that edits without undo. */
    fun checkpoint() {
        _ui.value.project?.let { undo.addLast(it); if (undo.size > 100) undo.removeFirst(); redo.clear() }
        _ui.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = false) }
    }

    /** Swaps [old] notes for [new] ones (moving a selection) and selects the new ones. */
    fun replaceNotes(old: Set<Note>, new: List<Note>, select: Boolean = true) {
        editClip(undoable = false) { c -> c.withNotes(c.notes.filter { it !in old } + new) }
        if (select) _ui.update { it.copy(noteSelection = new.toSet()) }
    }

    /**
     * Toggles [pitches] on the step at [start]. Steps on pages past the clip's end extend the clip to
     * include that page, so the sequencer always has room to the right.
     */
    fun toggleStepAt(pitches: Set<Int>, start: Double, grid: Double, pageBeats: Double) {
        val s = _ui.value
        val t = s.track ?: return
        val needLen = kotlin.math.ceil((start + grid - 1e-9) / pageBeats) * pageBeats
        val clip = t.clips[s.selectedScene]
        if (clip == null) {
            createClip(t.id, s.selectedScene)
            val made = _ui.value.clip
            if (made != null && made.lengthBeats < needLen) setClipLength(needLen)
        } else if (start >= clip.lengthBeats - 1e-9) {
            setClipLength(needLen)
        }
        for (p in pitches) toggleStep(p, start, grid)
    }

    private var padClipboard: DrumPad? = null
    val hasPadClipboard get() = padClipboard != null

    fun copyPad(index: Int) {
        padClipboard = _ui.value.track?.drumKit?.pads?.getOrNull(index)
        toast("Copied pad ${index + 1}")
    }

    fun pastePad(index: Int) {
        val pad = padClipboard ?: return
        setPad(index, pad, undoable = true)
    }

    fun clearPadNotes(index: Int) {
        val pitch = DRUM_BASE_NOTE + index
        editClip { c -> c.withNotes(c.notes.filter { it.pitch != pitch }) }
    }

    // ------------------------------------------------------------------ Push-style pads

    fun setPadMode(m: PadMode) = _ui.update { it.copy(padMode = m) }
    fun setRowLayout(l: RowLayout) = _ui.update { it.copy(rowLayout = l) }
    fun toggleAccent() = _ui.update { it.copy(accent = !it.accent) }

    /** Edits the note that starts on a step (velocity / length / nudge from the step's hold menu). */
    fun editStepNotes(pitches: Set<Int>, start: Double, grid: Double, f: (Note) -> Note?) {
        if (_ui.value.clip == null) return
        editClip { c ->
            c.withNotes(c.notes.mapNotNull { n -> if (n.pitch in pitches && kotlin.math.abs(n.start - start) < grid / 2) f(n) else n })
        }
    }

    // ------------------------------------------------------------------ SoundFonts

    private val _fontLibrary = MutableStateFlow<List<SoundFontRef>>(emptyList())
    val fontLibrary: StateFlow<List<SoundFontRef>> = _fontLibrary.asStateFlow()
    /** Bumped whenever SoundFonts finish loading, so preset lists refresh. */
    val fontsVersion = MutableStateFlow(0)
    private val _fontBusy = MutableStateFlow<String?>(null)
    val fontBusy: StateFlow<String?> = _fontBusy.asStateFlow()

    fun refreshFontLibrary() { viewModelScope.launch(Dispatchers.IO) { _fontLibrary.value = repo.soundFontLibrary() } }

    fun presetsOf(fontId: String?): List<SoundFont.Preset> = engine.soundFonts[fontId]?.presets ?: emptyList()

    /** Copies an .sf2 into the library, loads it and puts it on the selected track. */
    fun importSoundFont(uri: Uri, displayName: String) {
        val name = displayName.substringBeforeLast('.').ifBlank { "SoundFont" }
        _fontBusy.value = "Loading $name…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ref = getApplication<Application>().contentResolver.openInputStream(uri)?.use { repo.addSoundFont(name, it) } ?: error("Can't open file")
                    if (!engine.soundFonts.contains(ref.id)) engine.soundFonts.put(ref.id, SoundFont.parse(repo.soundFontFile(ref).readBytes(), name))
                    ref
                }
            }
            _fontBusy.value = null
            fontsVersion.value++
            result.onSuccess { useSoundFont(it); refreshFontLibrary() }
                .onFailure { toast(if (it is OutOfMemoryError) "That SoundFont is too large for memory" else "Not a valid .sf2: ${it.message}") }
        }
    }

    /** Uses a library SoundFont on the selected track (loading it first if needed). */
    fun useSoundFont(ref: SoundFontRef) {
        val t = _ui.value.track ?: return
        viewModelScope.launch {
            if (!engine.soundFonts.contains(ref.id)) {
                _fontBusy.value = "Loading ${ref.name}…"
                withContext(Dispatchers.IO) { runCatching { engine.soundFonts.put(ref.id, SoundFont.parse(repo.soundFontFile(ref).readBytes(), ref.name)) } }
                _fontBusy.value = null
                fontsVersion.value++
            }
            val first = presetsOf(ref.id).firstOrNull()
            edit { p ->
                p.copy(soundFonts = if (p.soundFonts.any { it.id == ref.id }) p.soundFonts else p.soundFonts + ref).updateTrack(t.id) {
                    it.copy(kind = TrackKind.SOUNDFONT, soundfont = SoundFontPatch(ref.id, ref.name, first?.bank ?: 0, first?.program ?: 0, first?.name ?: ""))
                }
            }
        }
    }

    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Free sound libraries being downloaded: pack id -> progress 0..1. */
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    /** Downloads a free SoundFont into the library and (optionally) puts it on the selected track. */
    fun downloadSoundPack(pack: com.notemove.app.data.SoundPack, useNow: Boolean = true) {
        if (pack.id in _downloads.value) return
        _downloads.update { it + (pack.id to 0f) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(getApplication<Application>().cacheDir, "${pack.id}.download")
                    try {
                        com.notemove.app.data.SoundLibrary.download(pack, tmp) { f -> _downloads.update { m -> if (pack.id in m) m + (pack.id to f) else m } }
                        val ref = tmp.inputStream().use { repo.addSoundFont(pack.name, it) }
                        if (!engine.soundFonts.contains(ref.id)) engine.soundFonts.put(ref.id, SoundFont.parse(repo.soundFontFile(ref).readBytes(), pack.name))
                        ref
                    } finally {
                        tmp.delete()
                    }
                }
            }
            _downloads.update { it - pack.id }
            fontsVersion.value++
            result.onSuccess { ref -> refreshFontLibrary(); if (useNow) useSoundFont(ref); toast("${pack.name} is ready — pick a preset") }
                .onFailure { toast(if (it is OutOfMemoryError) "Not enough memory for ${pack.name}" else "Download failed: ${it.message ?: "no connection"}") }
        }
    }

    fun setSoundFontPreset(preset: SoundFont.Preset) {
        val t = _ui.value.track ?: return
        engine.panic()
        edit { p -> p.updateTrack(t.id) { it.copy(soundfont = (it.soundfont ?: SoundFontPatch()).copy(bank = preset.bank, program = preset.program, presetName = preset.name)) } }
    }

    fun setSoundFontGain(g: Float) {
        val t = _ui.value.track ?: return
        edit(undoable = false) { p -> p.updateTrack(t.id) { it.copy(soundfont = (it.soundfont ?: SoundFontPatch()).copy(gain = g)) } }
    }

    // ------------------------------------------------------------------ audio route, latency, display

    val outputRoute: String get() = nm.output.routeName
    val bluetoothOutput: Boolean get() = nm.output.bluetooth
    val bleMidi = nm.bleMidi

    private val settings = application.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    private val _extraLatency = MutableStateFlow(settings.getFloat("extra_latency_ms", 0f))
    val extraLatencyMs: StateFlow<Float> = _extraLatency.asStateFlow()
    private val _hideNavBar = MutableStateFlow(settings.getBoolean("hide_nav", false))
    val hideNavBar: StateFlow<Boolean> = _hideNavBar.asStateFlow()

    fun setExtraLatency(ms: Float) {
        _extraLatency.value = ms
        engine.extraLatencyMs = ms.toDouble()
        settings.edit().putFloat("extra_latency_ms", ms).apply()
    }

    fun setHideNavBar(on: Boolean) {
        _hideNavBar.value = on
        settings.edit().putBoolean("hide_nav", on).apply()
    }

    // ------------------------------------------------------------------ keyboard & overlays

    private val _computerKeys = MutableStateFlow(ComputerKeys(enabled = settings.getBoolean("computer_keyboard", true)))
    val computerKeys: StateFlow<ComputerKeys> = _computerKeys.asStateFlow()
    val overlayRequest = MutableStateFlow<Overlay?>(null)

    fun requestOverlay(o: Overlay) { overlayRequest.value = o }

    fun toggleComputerKeyboard() {
        val on = !_computerKeys.value.enabled
        _computerKeys.update { it.copy(enabled = on) }
        settings.edit().putBoolean("computer_keyboard", on).apply()
        toast(if (on) "Computer MIDI keyboard on (A–' play notes, Z/X octave, C/V velocity)" else "Computer MIDI keyboard off")
    }

    private val keysOnDrums get() = _ui.value.track?.drumLayout == true

    /** MIDI note the A key plays right now. */
    fun keyboardBaseNote(): Int = _computerKeys.value.let { (if (keysOnDrums) it.drumOctave else it.octave) + 2 } * 12

    fun shiftKeyboardOctave(delta: Int) {
        _computerKeys.update { k ->
            if (keysOnDrums) k.copy(drumOctave = (k.drumOctave + delta).coerceIn(-2, 8)) else k.copy(octave = (k.octave + delta).coerceIn(-2, 8))
        }
        toast("Keyboard: ${com.notemove.core.model.Scale.noteName(keyboardBaseNote())} – ${com.notemove.core.model.Scale.noteName((keyboardBaseNote() + 17).coerceAtMost(127))}")
    }

    fun stepKeyboardVelocity(up: Boolean) {
        _computerKeys.update { it.copy(velocity = com.notemove.app.input.ComputerKeyboard.nextVelocity(it.velocity, up)) }
        toast("Keyboard velocity ${_computerKeys.value.velocity}")
    }

    init {
        engine.extraLatencyMs = _extraLatency.value.toDouble()
        refreshFontLibrary()
        bleMidi.reconnectSaved()
    }
}
