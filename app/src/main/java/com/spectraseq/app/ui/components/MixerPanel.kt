package com.spectraseq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
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

/**
 * Channel strips (volume, pan, sends, mute/solo, meters), master and the shared delay / reverb. Strips
 * drop their knobs as the panel gets shorter so the faders stay usable; touch & hold or right-click a
 * strip for the track menu.
 */
@Composable
fun MixerPanel(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier, onEditTrack: ((String) -> Unit)? = null) {
    val project = ui.project ?: return
    val state = engine.value
    BoxWithConstraints(modifier) {
    val compact = maxHeight < 380.dp
    val tiny = maxHeight < 280.dp
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in project.tracks) {
            val color = NM.track(t.color)
            val selected = t.id == ui.track?.id
            ContextMenuBox(
                {
                    trackMenu(vm, t, onEditTrack?.let { e -> { e(t.id) } }) {
                        item("Volume to 0 dB") { vm.setVolume(t.id, 0.8f) }
                        item("Centre pan") { vm.setPan(t.id, 0f) }
                        item("Sends off") { vm.selectTrack(t.id); vm.setTrackFx { it.copy(delaySend = 0f, reverbSend = 0f) } }
                    }
                },
                Modifier.width(84.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(if (selected) NM.surfaceHigh else NM.surface),
                onClick = { vm.selectTrack(t.id) },
            ) {
                Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!tiny) {
                        Knob("Pan", (t.pan + 1) / 2, { vm.setPan(t.id, it * 2 - 1) }, color = color, bipolar = true, size = if (compact) 28.dp else 38.dp, display = { panLabel(it * 2 - 1) })
                        val sends: @Composable () -> Unit = {
                            Knob("Delay", t.fx.delaySend, { v -> vm.selectTrack(t.id); vm.setTrackFx { it.copy(delaySend = v) } }, color = color, default = 0f, size = if (compact) 20.dp else 32.dp)
                            Knob("Reverb", t.fx.reverbSend, { v -> vm.selectTrack(t.id); vm.setTrackFx { it.copy(reverbSend = v) } }, color = color, default = 0f, size = if (compact) 20.dp else 32.dp)
                        }
                        if (compact) Row { sends() } else sends()
                    }
                    Fader(t.volume, { vm.setVolume(t.id, it) }, state.trackPeaks[t.id] ?: 0f, color, Modifier.weight(1f).padding(vertical = 6.dp))
                    if (!tiny) Text(dbLabel(t.volume), fontSize = 10.sp, color = NM.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ToggleBox("M", t.mute, { vm.toggleMute(t.id) }, onColor = NM.queued)
                        ToggleBox("S", t.solo, { vm.toggleSolo(t.id) }, onColor = NM.solo)
                    }
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
        Column(Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(NM.surface).verticalScroll(rememberScrollState()).padding(6.dp)) {
            Text("Delay", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
            Row {
                Knob("Time", (g.delaySixteenths - 1) / 7f, { v -> vm.setGlobalFx { it.copy(delaySixteenths = 1 + (v * 7).toInt()) } }, size = 36.dp,
                    display = { "${1 + (it * 7).toInt()}/16" })
                Knob("Feedback", g.delayFeedback / 0.95f, { v -> vm.setGlobalFx { it.copy(delayFeedback = v * 0.95f) } }, size = 36.dp)
            }
            Knob("Level", g.delayLevel, { v -> vm.setGlobalFx { it.copy(delayLevel = v) } }, size = 36.dp)
        }
        Column(Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(NM.surface).verticalScroll(rememberScrollState()).padding(6.dp)) {
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
}

private fun dbLabel(fader: Float): String {
    val gain = fader * fader / 0.64f
    if (gain <= 0.0001f) return "-∞ dB"
    val db = 20 * log10(gain)
    return "${if (db >= 0) "+" else ""}${"%.1f".format(db)} dB"
}
