package com.notemove.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.notemove.app.ui.ExportState
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.export.ProjectPackager
import com.notemove.core.model.ClipOps
import com.notemove.core.model.LaunchQuantization
import com.notemove.core.model.Scale
import com.notemove.core.model.Track
import com.notemove.core.model.TrackColors
import java.util.Locale

/** Set-wide settings: name, tempo, swing, key, quantisation, MIDI devices. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(vm: StudioViewModel, ui: StudioUi, onDismiss: () -> Unit) {
    val project = ui.project ?: return
    val devices by vm.midiDevices.collectAsState()
    var name by remember(project.id) { mutableStateOf(project.name) }
    ModalBottomSheet(onDismissRequest = { vm.renameProject(name); onDismiss() }, containerColor = NM.surface) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            OutlinedTextField(name, { name = it }, label = { Text("Set name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SectionTitle("Tempo · ${String.format(Locale.ROOT, "%.1f", project.tempo)} BPM")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("−1", false, { vm.setTempo(project.tempo - 1) })
                Slider(project.tempo.toFloat(), { vm.setTempo(Math.round(it).toDouble()) }, valueRange = 40f..240f, modifier = Modifier.weight(1f))
                Chip("+1", false, { vm.setTempo(project.tempo + 1) })
                Chip("Tap", false, vm::tapTempo, color = NM.accent)
            }
            SectionTitle("Swing · ${(project.swing * 100).toInt()}%")
            Slider(project.swing, { vm.setSwing(it) }, valueRange = 0f..1f)
            SectionTitle("Key")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Scale.NOTE_NAMES.forEachIndexed { i, n -> Chip(n, project.rootNote == i, { vm.setKey(i, project.scale) }) }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in Scale.entries) Chip(s.label, project.scale == s, { vm.setKey(project.rootNote, s) })
            }
            SectionTitle("Clip launch quantization")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (q in LaunchQuantization.entries) Chip(q.label, project.launchQuantization == q, { vm.setLaunchQuantization(q) })
            }
            SectionTitle("Record quantization")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("Off", ui.recordQuantize == null, { vm.setRecordQuantize(null) })
                for ((g, label) in ClipOps.GRIDS) Chip(label, ui.recordQuantize?.let { kotlin.math.abs(it - g) < 1e-6 } == true, { vm.setRecordQuantize(g) })
            }
            SectionTitle("New clip length")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (b in listOf(1, 2, 4, 8, 16)) Chip("$b bar${if (b > 1) "s" else ""}", ui.newClipBars == b, { vm.setNewClipBars(b) })
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleBox("Count-in", ui.countIn, vm::toggleCountIn)
                ToggleBox("Metronome", ui.metronome, vm::toggleMetronome)
            }
            SectionTitle("MIDI controllers")
            Text(
                if (devices.isEmpty()) "None connected. Plug in a USB MIDI keyboard / pad controller (or Move / Push in MIDI mode) — notes play the selected track."
                else devices.joinToString(" · "),
                fontSize = 12.sp, color = NM.textDim,
            )
            SectionTitle("Layout")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Drag the dividers between areas to resize them; double-tap a divider to reset it.", fontSize = 12.sp, color = NM.textDim, modifier = Modifier.weight(1f))
                Chip("Reset layout", false, vm::resetSplits)
            }
            SectionTitle("Audio")
            Text("Output latency ≈ ${vm.latencyMs.toInt()} ms · ${vm.engine.sampleRate} Hz", fontSize = 12.sp, color = NM.textDim)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Export to Ableton Live: a zipped Live project folder or just the .als, shared or saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: StudioViewModel, ui: StudioUi, onDismiss: () -> Unit) {
    val project = ui.project ?: return
    val state by vm.export.collectAsState()
    val context = LocalContext.current
    var renderClips by remember { mutableStateOf(true) }
    var mixdown by remember { mutableStateOf(true) }
    var midi by remember { mutableStateOf(true) }
    var arrangement by remember { mutableStateOf(true) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::saveExportTo) }
    val saveAls = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let(vm::saveExportTo) }
    ModalBottomSheet(onDismissRequest = { vm.resetExport(); onDismiss() }, containerColor = NM.surface) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Export to Ableton Live", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            Text(
                "Creates a Live Set (.als) with every track as a MIDI track, clips in Session View and scenes laid out in " +
                    "Arrangement View. Opens in Live 11 and 12.",
                fontSize = 12.sp, color = NM.textDim, modifier = Modifier.padding(vertical = 6.dp),
            )
            CheckRow("Render every clip as an audio loop (Samples/Rendered)", renderClips) { renderClips = it }
            CheckRow("Render a mixdown of the scenes in order", mixdown) { mixdown = it }
            CheckRow("Include MIDI files", midi) { midi = it }
            CheckRow("Lay out scenes in Arrangement View", arrangement) { arrangement = it }
            Spacer(Modifier.height(10.dp))
            when (val s = state) {
                ExportState.Idle -> {
                    Button(
                        { vm.exportProject(ProjectPackager.Options(renderClips, mixdown, midi, arrangement)) },
                        Modifier.fillMaxWidth(),
                    ) { Text("Build Live project (.zip)") }
                    OutlinedButton({ vm.exportAls(arrangement) }, Modifier.fillMaxWidth()) { Text("Live Set only (.als)") }
                }
                is ExportState.Working -> {
                    Text(s.step, color = NM.text, fontSize = 13.sp)
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                is ExportState.Ready -> {
                    Text("Ready: ${s.file.name} (${s.file.length() / 1024} KB)", color = NM.text, fontSize = 13.sp)
                    Button({ context.startActivity(vm.exporter.shareIntent(s.file, s.mime)) }, Modifier.fillMaxWidth()) {
                        Text("Share… (Drive, Dropbox, Nearby Share, email)")
                    }
                    OutlinedButton({ if (s.file.name.endsWith(".zip")) saveZip.launch(s.file.name) else saveAls.launch(s.file.name) }, Modifier.fillMaxWidth()) {
                        Text("Save to device…")
                    }
                    TextButton({ vm.resetExport() }) { Text("Export again") }
                }
                is ExportState.Failed -> {
                    Text("Export failed: ${s.error}", color = NM.record, fontSize = 13.sp)
                    TextButton({ vm.resetExport() }) { Text("Try again") }
                }
            }
            Text(
                "On the computer: unzip, open \"${ProjectPackager.safeName(project.name)}.als\", then drop a Drum Rack or instrument " +
                    "on each track — or drag the rendered loops onto audio tracks to keep the exact sounds from your phone.",
                fontSize = 11.sp, color = NM.textDim, modifier = Modifier.padding(vertical = 10.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(text, fontSize = 13.sp, color = NM.text)
    }
}

/** Sampling: record from the mic, import an audio file, or reuse a sample from this set. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SampleSheet(vm: StudioViewModel, ui: StudioUi, onDismiss: () -> Unit) {
    val project = ui.project ?: return
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var seconds by remember { mutableFloatStateOf(0f) }
    var name by remember { mutableStateOf("Sample ${project.samples.size + 1}") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recording = vm.startMic() else vm.toast("Microphone permission is needed to sample")
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { vm.importSample(uri, displayName(context, uri)); onDismiss() }
    }
    LaunchedEffect(recording) {
        while (recording) withFrameNanos { level = vm.mic.level; seconds = vm.mic.seconds; if (!vm.mic.recording) recording = false }
    }
    val target = if (ui.track?.kind == com.notemove.core.model.TrackKind.DRUMS) "pad ${ui.selectedPad + 1}" else "this track"
    ModalBottomSheet(onDismissRequest = { if (recording) vm.mic.stop(); onDismiss() }, containerColor = NM.surface) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Sample into $target", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(NM.padDim)) {
                Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(14.dp).background(if (level > 0.95f) NM.record else NM.play))
            }
            Text(if (recording) String.format(Locale.ROOT, "Recording… %.1f s", seconds) else "Silence at the start and end is trimmed automatically.",
                fontSize = 12.sp, color = NM.textDim, modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!recording) {
                    Button({
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recording = vm.startMic()
                        else permission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("● Record") }
                } else {
                    Button({ recording = false; vm.stopMic(name); onDismiss() }) { Text("■ Stop & use") }
                }
                OutlinedButton({ pick.launch(arrayOf("audio/*")) }, enabled = !recording) { Text("Import audio file…") }
            }
            if (project.samples.isNotEmpty()) {
                SectionTitle("Samples in this set")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (ref in project.samples) Chip(ref.name, false, { vm.assignExistingSample(ref); onDismiss() })
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"

/** Rename / recolour a track. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackDialog(vm: StudioViewModel, track: Track, onDismiss: () -> Unit) {
    var name by remember(track.id) { mutableStateOf(track.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ vm.renameTrack(track.id, name); onDismiss() }) { Text("Done") } },
        dismissButton = { TextButton({ vm.deleteTrack(track.id); onDismiss() }) { Text("Delete track", color = NM.record) } },
        title = { Text("Track") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                SectionTitle("Colour")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrackColors.ALL.indices.forEach { i ->
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(NM.track(i))
                                .then(if (i == track.color) Modifier.border(3.dp, NM.text, RoundedCornerShape(16.dp)) else Modifier)
                                .clickable { vm.setTrackColor(track.id, i) },
                        )
                    }
                }
            }
        },
        containerColor = NM.surface,
    )
}
