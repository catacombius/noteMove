package com.notemove.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.engine.EngineState
import com.notemove.core.model.Scale
import java.util.Locale
import kotlin.math.floor

@Composable
fun TransportBar(
    vm: StudioViewModel,
    ui: StudioUi,
    engine: State<EngineState>,
    compact: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = ui.project ?: return
    val playing by remember(engine) { derivedStateOf { engine.value.playing } }
    val recording by remember(engine) { derivedStateOf { engine.value.recording } }
    val position by remember(engine) {
        derivedStateOf {
            val b = engine.value.estimatedBeat()
            if (!engine.value.playing) "1.1" else if (b < 0) "−${(-floor(b)).toInt()}"
            else "${floor(b / 4).toInt() + 1}.${floor(b % 4).toInt() + 1}"
        }
    }
    var more by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(56.dp).background(NM.surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Sets", tint = NM.text) }
        Column(Modifier.weight(1f).clickable(onClick = onSettings).padding(horizontal = 4.dp)) {
            Text(project.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NM.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${String.format(Locale.ROOT, "%.1f", project.tempo)} BPM · ${Scale.NOTE_NAMES[project.rootNote]} ${project.scale.label}" +
                    if (compact) "" else " · $position",
                fontSize = 11.sp, color = NM.textDim, maxLines = 1,
            )
        }
        if (!compact) {
            IconButton(onClick = vm::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (ui.canUndo) NM.text else NM.line) }
            IconButton(onClick = vm::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo", tint = if (ui.canRedo) NM.text else NM.line) }
            IconButton(onClick = vm::toggleMetronome) { Icon(Icons.Filled.Timer, "Metronome", tint = if (ui.metronome) NM.accent else NM.textDim) }
            Chip("Capture", false, vm::capture, color = NM.play)
            Spacer(Modifier.size(4.dp))
        }
        if (vm.bluetoothOutput) {
            Text("BT ${vm.latencyMs.toInt()}ms", fontSize = 10.sp, color = NM.queued, modifier = Modifier.clickable(onClick = onSettings).padding(horizontal = 4.dp))
        }
        RoundButton(if (playing) NM.play else NM.surfaceHigh, onClick = vm::togglePlay) {
            Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, if (playing) "Stop" else "Play", tint = if (playing) Color.Black else NM.text)
        }
        Spacer(Modifier.size(6.dp))
        RoundButton(if (recording) NM.record else NM.surfaceHigh, onClick = vm::toggleRecord) {
            Icon(Icons.Filled.FiberManualRecord, "Record", tint = if (recording) Color.White else NM.record)
        }
        if (!compact) IconButton(onClick = onExport) { Icon(Icons.Filled.IosShare, "Export to Live", tint = NM.text) }
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More", tint = NM.text) }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem({ Text("Export to Ableton Live…") }, { more = false; onExport() })
                DropdownMenuItem({ Text("Set settings…") }, { more = false; onSettings() })
                if (compact) {
                    DropdownMenuItem({ Text("Undo") }, { more = false; vm.undo() }, enabled = ui.canUndo)
                    DropdownMenuItem({ Text("Redo") }, { more = false; vm.redo() }, enabled = ui.canRedo)
                    DropdownMenuItem({ Text(if (ui.metronome) "Metronome off" else "Metronome on") }, { more = false; vm.toggleMetronome() })
                    DropdownMenuItem({ Text("Capture MIDI") }, { more = false; vm.capture() })
                }
                DropdownMenuItem({ Text(if (ui.countIn) "Count-in: on" else "Count-in: off") }, { more = false; vm.toggleCountIn() })
                DropdownMenuItem({ Text("Tap tempo") }, { vm.tapTempo() })
            }
        }
    }
}

@Composable
fun RoundButton(color: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
