package com.spectraseq.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.Panel
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.guardTextInput
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.model.DeviceHelp
import com.spectraseq.core.model.NodeKind
import com.spectraseq.core.model.Track

/** The track actions offered wherever a track appears (session header, mixer strip, song lane, track chips). */
fun trackMenu(vm: StudioViewModel, t: Track, onEdit: (() -> Unit)?, extra: MenuBuilder.() -> Unit = {}): List<MenuEntry> = menu {
    header("${t.name} · ${t.kind.label}")
    if (onEdit != null) item("Rename / colour…", onClick = onEdit)
    item("Mute", checked = t.mute) { vm.toggleMute(t.id) }
    item("Solo", checked = t.solo) { vm.toggleSolo(t.id) }
    item("Stop clips") { vm.stopTrack(t.id) }
    extra()
    divider()
    item("Sound…") { vm.selectTrack(t.id); vm.setPanel(Panel.SOUND) }
    item("Effects…") { vm.selectTrack(t.id); vm.setPanel(Panel.FX) }
    divider()
    item("Duplicate track") { vm.duplicateTrack(t.id) }
    item("Move left") { vm.moveTrack(t.id, -1) }
    item("Move right") { vm.moveTrack(t.id, 1) }
    divider()
    item("Delete track", danger = true) { vm.deleteTrack(t.id) }
}

/** A one-field rename dialog. */
@Composable
fun RenameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onDone(text.trim()); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.guardTextInput()) },
        containerColor = NM.surface,
    )
}

/** The explanation pop-up for a patcher node: what it does, each input and knob, and something to try. */
@Composable
fun NodeHelpDialog(kind: NodeKind, onDismiss: () -> Unit) {
    val h = DeviceHelp.node(kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Got it") } },
        title = { Text(kind.label) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(h.summary, fontSize = 14.sp, color = NM.text)
                if (kind.inputs.isNotEmpty()) {
                    SectionTitle("Inputs (left dots)")
                    kind.inputs.forEachIndexed { i, name -> HelpLine(name, h.inputs[i]) }
                }
                if (kind.hasOutput) {
                    SectionTitle("Output (right dot)")
                    Text(if (kind == NodeKind.LFO || kind == NodeKind.ENV || kind == NodeKind.KNOB) "A control signal: wire it into a mod input."
                        else "The result. Wire it to other nodes or to Output; one output can feed many inputs.", fontSize = 13.sp, color = NM.textDim)
                }
                if (kind.params.isNotEmpty()) {
                    SectionTitle("Knobs")
                    kind.params.forEachIndexed { i, name -> HelpLine(name, h.params[i]) }
                }
                SectionTitle("Try this")
                Text(h.tip, fontSize = 13.sp, color = NM.text)
            }
        },
        containerColor = NM.surface,
    )
}

@Composable
private fun HelpLine(name: String, text: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = NM.accent, fontWeight = FontWeight.SemiBold)) { append(name) }
            append(" — ")
            append(text)
        },
        fontSize = 13.sp, color = NM.text, modifier = Modifier.padding(vertical = 2.dp),
    )
}
