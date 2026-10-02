package com.notemove.app.ui.components

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.model.DrumKits
import com.notemove.core.model.DrumSound
import com.notemove.core.model.FilterMode
import com.notemove.core.model.SamplerPatch
import com.notemove.core.model.Scale
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.TrackKind
import com.notemove.core.model.Waveform
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/** Instrument and track-effect editing for the selected track. [onSample] opens the sampling sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundPanel(vm: StudioViewModel, ui: StudioUi, onSample: () -> Unit, modifier: Modifier = Modifier) {
    val track = ui.track ?: return
    val color = NM.track(track.color)
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(track.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            for (k in TrackKind.entries) Chip(k.label, track.kind == k, { vm.changeTrackKind(k) }, color = color)
        }
        when (track.kind) {
            TrackKind.SYNTH -> SynthEditor(vm, ui)
            TrackKind.DRUMS -> DrumEditor(vm, ui, onSample)
            TrackKind.SAMPLER -> SamplerEditor(vm, ui, onSample)
            TrackKind.SOUNDFONT -> SoundFontEditor(vm, ui)
        }
        SectionTitle("Track effects")
        val fx = track.fx
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Knob("Filter", fx.filterCutoff, { v -> vm.setTrackFx { it.copy(filterCutoff = v) } }, color = color, default = 1f, display = ::cutoffLabel)
            Knob("Reso", fx.filterResonance, { v -> vm.setTrackFx { it.copy(filterResonance = v) } }, color = color, default = 0.1f)
            Knob("Drive", fx.drive, { v -> vm.setTrackFx { it.copy(drive = v) } }, color = color, default = 0f)
            Knob("Delay", fx.delaySend, { v -> vm.setTrackFx { it.copy(delaySend = v) } }, color = color, default = 0f)
            Knob("Reverb", fx.reverbSend, { v -> vm.setTrackFx { it.copy(reverbSend = v) } }, color = color, default = 0f)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SynthEditor(vm: StudioViewModel, ui: StudioUi) {
    val track = ui.track ?: return
    val p = track.synth ?: SynthPresets.KEYS
    val color = NM.track(track.color)
    SectionTitle("Preset")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (preset in SynthPresets.ALL) Chip(preset.name, p.name == preset.name, { vm.setSynth(preset, undoable = true) }, color = color)
    }
    SectionTitle("Oscillators")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Osc 1", fontSize = 11.sp, color = NM.textDim)
        for (w in Waveform.entries) Chip(w.label, p.osc1 == w, { vm.setSynth(p.copy(osc1 = w), true) }, color = color)
    }
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Osc 2", fontSize = 11.sp, color = NM.textDim)
        for (w in Waveform.entries) Chip(w.label, p.osc2 == w, { vm.setSynth(p.copy(osc2 = w), true) }, color = color)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("Mix", p.oscMix, { vm.setSynth(p.copy(oscMix = it)) }, color = color)
        Knob("Osc2 Semi", (p.osc2Semi + 24) / 48f, { vm.setSynth(p.copy(osc2Semi = (it * 48 - 24).toInt())) }, color = color, bipolar = true,
            display = { "${(it * 48 - 24).toInt()} st" })
        Knob("Detune", (p.osc2Detune + 50) / 100f, { vm.setSynth(p.copy(osc2Detune = it * 100 - 50)) }, color = color, bipolar = true,
            display = { "${(it * 100 - 50).toInt()} ct" })
        Knob("Sub", p.subLevel, { vm.setSynth(p.copy(subLevel = it)) }, color = color, default = 0f)
        Knob("Noise", p.noiseLevel, { vm.setSynth(p.copy(noiseLevel = it)) }, color = color, default = 0f)
        Knob("Transpose", (p.transpose + 24) / 48f, { vm.setSynth(p.copy(transpose = (it * 48 - 24).toInt())) }, color = color, bipolar = true,
            display = { "${(it * 48 - 24).toInt()} st" })
    }
    SectionTitle("Filter")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (m in FilterMode.entries) Chip(m.label, p.filterMode == m, { vm.setSynth(p.copy(filterMode = m), true) }, color = color)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("Cutoff", p.cutoff, { vm.setSynth(p.copy(cutoff = it)) }, color = color, display = ::cutoffLabel)
        Knob("Reso", p.resonance, { vm.setSynth(p.copy(resonance = it)) }, color = color)
        Knob("Env Amt", (p.filterEnvAmount + 1) / 2, { vm.setSynth(p.copy(filterEnvAmount = it * 2 - 1)) }, color = color, bipolar = true,
            display = { "${((it * 2 - 1) * 100).toInt()}%" })
        Knob("Key Trk", p.keyTracking, { vm.setSynth(p.copy(keyTracking = it)) }, color = color)
        Knob("F Attack", timeToNorm(p.filterAttack), { vm.setSynth(p.copy(filterAttack = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("F Decay", timeToNorm(p.filterDecay), { vm.setSynth(p.copy(filterDecay = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("F Sustain", p.filterSustain, { vm.setSynth(p.copy(filterSustain = it)) }, color = color)
        Knob("F Release", timeToNorm(p.filterRelease), { vm.setSynth(p.copy(filterRelease = normToTime(it))) }, color = color, display = ::timeLabel)
    }
    SectionTitle("Amp envelope")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("Attack", timeToNorm(p.attack), { vm.setSynth(p.copy(attack = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("Decay", timeToNorm(p.decay), { vm.setSynth(p.copy(decay = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("Sustain", p.sustain, { vm.setSynth(p.copy(sustain = it)) }, color = color)
        Knob("Release", timeToNorm(p.release), { vm.setSynth(p.copy(release = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("Volume", p.gain, { vm.setSynth(p.copy(gain = it)) }, color = color, default = 0.7f)
    }
    SectionTitle("Modulation")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("LFO Rate", (ln(p.lfoRate / 0.05f) / ln(400f)), { vm.setSynth(p.copy(lfoRate = 0.05f * 400f.pow(it))) }, color = color,
            display = { String.format(Locale.ROOT, "%.2f Hz", 0.05f * 400f.pow(it)) })
        Knob("LFO→Pitch", p.lfoToPitch, { vm.setSynth(p.copy(lfoToPitch = it)) }, color = color, default = 0f)
        Knob("LFO→Filter", p.lfoToFilter, { vm.setSynth(p.copy(lfoToFilter = it)) }, color = color, default = 0f)
        Knob("Glide", p.glide / 0.5f, { vm.setSynth(p.copy(glide = it * 0.5f)) }, color = color, default = 0f, display = { "${(it * 500).toInt()} ms" })
        ToggleBox(if (p.mono) "Mono" else "Poly", p.mono, { vm.setSynth(p.copy(mono = !p.mono), true) }, onColor = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrumEditor(vm: StudioViewModel, ui: StudioUi, onSample: () -> Unit) {
    val track = ui.track ?: return
    val kit = track.drumKit ?: DrumKits.KIT_808
    val color = NM.track(track.color)
    SectionTitle("Kit")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in DrumKits.ALL) Chip(k.name, kit.name == k.name, { vm.setKit(k) }, color = color)
    }
    SectionTitle("Pad")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        kit.pads.forEachIndexed { i, pad ->
            Chip("${i + 1} ${pad.name}", i == ui.selectedPad, { vm.selectPad(i); vm.auditionPad(i) }, color = color)
        }
    }
    val idx = ui.selectedPad
    val pad = kit.pads[idx]
    SectionTitle("Sound · ${pad.name}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (s in DrumSound.entries) {
            if (s == DrumSound.SAMPLE && pad.sampleId == null) continue
            Chip(s.label, pad.sound == s, { vm.setPad(idx, pad.copy(sound = s, name = if (s == DrumSound.SAMPLE) pad.name else s.label), true); vm.auditionPad(idx) }, color = color)
        }
        Chip("Sample…", false, onSample, color = color)
        pad.sampleId?.let { sid -> Chip("Edit sample…", false, { vm.openSampleEditor(sid) }, color = color) }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("Tune", (pad.tune + 24) / 48f, { vm.setPad(idx, pad.copy(tune = it * 48 - 24)) }, color = color, bipolar = true,
            display = { String.format(Locale.ROOT, "%.1f st", it * 48 - 24) })
        Knob("Decay", (pad.decay - 0.1f) / 2.9f, { vm.setPad(idx, pad.copy(decay = 0.1f + it * 2.9f)) }, color = color, default = 0.9f / 2.9f,
            display = { String.format(Locale.ROOT, "%.2f×", 0.1f + it * 2.9f) })
        Knob("Tone", pad.tone, { vm.setPad(idx, pad.copy(tone = it)) }, color = color)
        Knob("Level", pad.level, { vm.setPad(idx, pad.copy(level = it)) }, color = color, default = 0.8f)
        Knob("Pan", (pad.pan + 1) / 2, { vm.setPad(idx, pad.copy(pan = it * 2 - 1)) }, color = color, bipolar = true,
            display = { panLabel(it * 2 - 1) })
        Knob("Choke", pad.chokeGroup / 4f, { vm.setPad(idx, pad.copy(chokeGroup = (it * 4).toInt())) }, color = color, default = 0f,
            display = { val g = (it * 4).toInt(); if (g == 0) "Off" else "Grp $g" })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SamplerEditor(vm: StudioViewModel, ui: StudioUi, onSample: () -> Unit) {
    val track = ui.track ?: return
    val project = ui.project ?: return
    val p = track.sampler ?: SamplerPatch()
    val color = NM.track(track.color)
    SectionTitle("Sample")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(project.sample(p.sampleId)?.name ?: "No sample loaded", color = NM.text, fontSize = 14.sp)
        Chip("Record / Load…", false, onSample, color = color)
        p.sampleId?.let { sid -> Chip("Edit (spectral)…", false, { vm.openSampleEditor(sid) }, color = color) }
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleBox("Loop", p.loop, { vm.setSampler(p.copy(loop = !p.loop), true) }, onColor = color)
        ToggleBox("Reverse", p.reverse, { vm.setSampler(p.copy(reverse = !p.reverse), true) }, onColor = color)
        ToggleBox(if (p.pitched) "Pitched" else "One-shot", p.pitched, { vm.setSampler(p.copy(pitched = !p.pitched), true) }, onColor = color)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("Start", p.start, { vm.setSampler(p.copy(start = it)) }, color = color, default = 0f)
        Knob("End", p.end, { vm.setSampler(p.copy(end = it)) }, color = color, default = 1f)
        Knob("Root", (p.rootNote - 24) / 72f, { vm.setSampler(p.copy(rootNote = 24 + (it * 72).toInt())) }, color = color, default = 0.5f,
            display = { Scale.noteName(24 + (it * 72).toInt()) })
        Knob("Attack", timeToNorm(p.attack), { vm.setSampler(p.copy(attack = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("Decay", timeToNorm(p.decay), { vm.setSampler(p.copy(decay = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("Sustain", p.sustain, { vm.setSampler(p.copy(sustain = it)) }, color = color, default = 1f)
        Knob("Release", timeToNorm(p.release), { vm.setSampler(p.copy(release = normToTime(it))) }, color = color, display = ::timeLabel)
        Knob("Filter", p.cutoff, { vm.setSampler(p.copy(cutoff = it)) }, color = color, default = 1f, display = ::cutoffLabel)
        Knob("Reso", p.resonance, { vm.setSampler(p.copy(resonance = it)) }, color = color, default = 0.1f)
        Knob("Volume", p.gain, { vm.setSampler(p.copy(gain = it)) }, color = color, default = 0.8f)
    }
}

// Envelope times map 0..1 to 1 ms .. 8 s exponentially.
private fun normToTime(n: Float): Float = 0.001f * 8000f.pow(n.coerceIn(0f, 1f))
private fun timeToNorm(t: Float): Float = (ln(t.coerceAtLeast(0.001f) / 0.001f) / ln(8000f)).coerceIn(0f, 1f)

private fun timeLabel(n: Float): String {
    val t = normToTime(n)
    return if (t < 1f) "${(t * 1000).toInt()} ms" else String.format(Locale.ROOT, "%.2f s", t)
}

private fun cutoffLabel(n: Float): String {
    val hz = 20f * 1000f.pow(n)
    return if (n >= 0.995f) "Open" else if (hz < 1000) "${hz.toInt()} Hz" else String.format(Locale.ROOT, "%.1f kHz", hz / 1000)
}

fun panLabel(p: Float): String = when {
    kotlin.math.abs(p) < 0.02f -> "C"
    p < 0 -> "${(-p * 50).toInt()}L"
    else -> "${(p * 50).toInt()}R"
}

/** Load .sf2 files and pick a preset (bank:program). Bank 128 presets are General MIDI drum kits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundFontEditor(vm: StudioViewModel, ui: StudioUi) {
    val track = ui.track ?: return
    val patch = track.soundfont ?: com.notemove.core.model.SoundFontPatch()
    val color = NM.track(track.color)
    val context = androidx.compose.ui.platform.LocalContext.current
    val library by vm.fontLibrary.collectAsState()
    val busy by vm.fontBusy.collectAsState()
    val fontsVersion by vm.fontsVersion.collectAsState()
    var query by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSoundFont(uri, displayName(context, uri))
    }
    SectionTitle("SoundFont")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(patch.fontName.ifBlank { "No SoundFont loaded" }, color = NM.text, fontSize = 14.sp)
        Chip("Load .sf2…", false, { picker.launch(arrayOf("*/*")) }, color = color)
    }
    busy?.let { Text(it, fontSize = 12.sp, color = NM.textDim) }
    if (library.isNotEmpty()) {
        SectionTitle("Library")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (ref in library) Chip(ref.name, ref.id == patch.fontId, { vm.useSoundFont(ref) }, color = color)
        }
    }
    val presets = remember(patch.fontId, fontsVersion) { vm.presetsOf(patch.fontId) }
    if (patch.fontId != null && presets.isEmpty() && busy == null) Text("Loading presets…", fontSize = 12.sp, color = NM.textDim)
    if (presets.isNotEmpty()) {
        SectionTitle("Preset · ${patch.presetName}")
        androidx.compose.material3.OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search presets") },
            modifier = Modifier.fillMaxWidth())
        val shown = presets.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.take(160)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (pr in shown) {
                Chip(if (pr.bank == 128) "🥁 ${pr.name}" else "${pr.program + 1} ${pr.name}", pr.bank == patch.bank && pr.program == patch.program,
                    { vm.setSoundFontPreset(pr) }, color = color)
            }
        }
        Text("Bank 128 presets are drum kits and use the drum pad layout (C1 = Kick).", fontSize = 11.sp, color = NM.textDim, modifier = Modifier.padding(top = 4.dp))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Knob("Volume", patch.gain, { vm.setSoundFontGain(it) }, color = color, default = 0.8f)
    }
}
