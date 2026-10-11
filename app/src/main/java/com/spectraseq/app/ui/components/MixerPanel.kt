package com.spectraseq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.engine.EngineState
import kotlin.math.log10

/** Channel strips (volume, pan, sends, mute/solo, meters), master and the shared delay / reverb. */
@Composable
fun MixerPanel(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier) {
    val project = ui.project ?: return
    val state = engine.value
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in project.tracks) {
            val color = NM.track(t.color)
            val selected = t.id == ui.track?.id
            Column(
                Modifier.width(84.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                    .background(if (selected) NM.surfaceHigh else NM.surface)
                    .clickable { vm.selectTrack(t.id) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Knob("Pan", (t.pan + 1) / 2, { vm.setPan(t.id, it * 2 - 1) }, color = color, bipolar = true, size = 38.dp, display = { panLabel(it * 2 - 1) })
                Knob("Delay", t.fx.delaySend, { v -> vm.selectTrack(t.id); vm.setTrackFx { it.copy(delaySend = v) } }, color = color, default = 0f, size = 32.dp)
                Knob("Reverb", t.fx.reverbSend, { v -> vm.selectTrack(t.id); vm.setTrackFx { it.copy(reverbSend = v) } }, color = color, default = 0f, size = 32.dp)
                Fader(t.volume, { vm.setVolume(t.id, it) }, state.trackPeaks[t.id] ?: 0f, color, Modifier.weight(1f).padding(vertical = 6.dp))
                Text(dbLabel(t.volume), fontSize = 10.sp, color = NM.textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToggleBox("M", t.mute, { vm.toggleMute(t.id) }, onColor = NM.queued)
                    ToggleBox("S", t.solo, { vm.toggleSolo(t.id) }, onColor = NM.solo)
                }
            }
        }
        Column(
            Modifier.width(96.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(NM.surface).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Master", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            Fader(project.masterVolume, vm::setMasterVolume, state.masterPeak, NM.accent, Modifier.weight(1f).padding(vertical = 6.dp))
            Text("${(project.masterVolume * 100).toInt()}%", fontSize = 10.sp, color = NM.textDim)
        }
        val g = project.globalFx
        Column(Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(NM.surface).padding(6.dp)) {
            Text("Delay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            Row {
                Knob("Time", (g.delaySixteenths - 1) / 7f, { v -> vm.setGlobalFx { it.copy(delaySixteenths = 1 + (v * 7).toInt()) } }, size = 36.dp,
                    display = { "${1 + (it * 7).toInt()}/16" })
                Knob("Feedback", g.delayFeedback / 0.95f, { v -> vm.setGlobalFx { it.copy(delayFeedback = v * 0.95f) } }, size = 36.dp)
            }
            Knob("Level", g.delayLevel, { v -> vm.setGlobalFx { it.copy(delayLevel = v) } }, size = 36.dp)
        }
        Column(Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(NM.surface).padding(6.dp)) {
            Text("Reverb", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            Row {
                Knob("Size", g.reverbSize, { v -> vm.setGlobalFx { it.copy(reverbSize = v) } }, size = 36.dp)
                Knob("Damping", g.reverbDamping, { v -> vm.setGlobalFx { it.copy(reverbDamping = v) } }, size = 36.dp)
            }
            Knob("Level", g.reverbLevel, { v -> vm.setGlobalFx { it.copy(reverbLevel = v) } }, size = 36.dp)
        }
        Box(Modifier.width(8.dp))
    }
}

private fun dbLabel(fader: Float): String {
    val gain = fader * fader / 0.64f
    if (gain <= 0.0001f) return "-∞ dB"
    val db = 20 * log10(gain)
    return "${if (db >= 0) "+" else ""}${"%.1f".format(db)} dB"
}
