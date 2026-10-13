package com.spectraseq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.Panel
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.components.Chip
import com.spectraseq.app.ui.components.ClipEditor
import com.spectraseq.app.ui.components.EffectsPanel
import com.spectraseq.app.ui.components.HoverHint
import com.spectraseq.app.ui.components.MixerPanel
import com.spectraseq.app.ui.components.PushSurface
import com.spectraseq.app.ui.components.SessionGrid
import com.spectraseq.app.ui.components.SongView
import com.spectraseq.app.ui.components.SoundPanel
import com.spectraseq.app.ui.components.SplitPane
import com.spectraseq.app.ui.components.TransportBar
import com.spectraseq.app.ui.components.hint
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.engine.EngineState
import com.spectraseq.core.model.Scale

private const val SESSION_HINT = "Session · click a clip to launch it, an empty slot to select it · right-click a clip, scene or track for options · Ctrl+wheel resizes the slots"
private const val SONG_HINT = "Song · click selects, drag moves (also between lanes), drag an edge to resize, drag empty space to box-select · right-click for options · wheel scrolls, Ctrl+wheel zooms · click the ruler for the cursor, drag it for the loop"
private const val MIX_HINT = "Mixer · drag or wheel a fader (Shift = fine), double-click resets to 0 dB · right-click a strip for track options, a fader for preset levels"
private const val CLIP_HINT = "Clip · click empty space adds a note, double-click a note deletes it, drag moves (Ctrl copies), drag ▸ resizes, drag empty space box-selects · right-click for options · wheel scrolls, Shift sideways, Ctrl zooms"
private const val PADS_HINT = "Pads · click to play, or play from the computer keyboard (A W S E D …, Z/X octave, C/V velocity)"
private const val DEVICE_HINT = "Device · drag or wheel a knob (Shift = fine), double-click resets · right-click a knob or device for options"
private const val DEFAULT_HINT = "Right-click anything for options · Space play / stop · Tab Session → Song → Mixer · Shift+Tab clip ↔ device · Ctrl+Shift+W touch workspace"

/**
 * Keyboard & mouse workspace, laid out like Live on a laptop: the main view (Session, Song or Mixer)
 * on top; the clip editor (or pads) and the selected track's device below, side by side when there is
 * room; an info bar at the bottom that explains whatever the mouse is over.
 */
@Composable
internal fun DesktopLayout(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions) {
    val splits by vm.splits.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TransportBar(vm, ui, engine, compact = false, onBack = vm::closeProject, onSettings = a.onSettings, onExport = a.onExport)
        DesktopViewBar(vm, ui)
        SplitPane(
            splits["desk_v"] ?: 0.5f, { vm.setSplit("desk_v", it) }, vertical = true,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), default = 0.5f, minPane = 120.dp,
            first = {
                Box(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    when (ui.deskMain) {
                        Panel.SONG -> SongView(vm, ui, engine, Modifier.fillMaxSize().hint(SONG_HINT), onEditTrack = a.onEditTrack)
                        Panel.MIX -> MixerPanel(vm, ui, engine, Modifier.fillMaxSize().hint(MIX_HINT), onEditTrack = a.onEditTrack)
                        else -> SessionGrid(vm, ui, engine, Modifier.fillMaxSize().hint(SESSION_HINT), onEditTrack = { a.onEditTrack(it.id) })
                    }
                }
            },
            second = {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth >= 900.dp) {
                        SplitPane(
                            splits["desk_h"] ?: 0.6f, { vm.setSplit("desk_h", it) }, vertical = false,
                            modifier = Modifier.fillMaxSize(), default = 0.6f, minPane = 280.dp,
                            first = { DetailPane(vm, ui, engine, a, ui.deskEditor) },
                            second = { DetailPane(vm, ui, engine, a, ui.deskDevice) },
                        )
                    } else {
                        DetailPane(vm, ui, engine, a, if (ui.deskDeviceFocus) ui.deskDevice else ui.deskEditor)
                    }
                }
            },
        )
        InfoBar(vm, ui)
    }
}

@Composable
private fun DetailPane(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions, panel: Panel) {
    Box(Modifier.fillMaxSize().padding(top = 2.dp, bottom = 4.dp)) {
        when (panel) {
            Panel.PLAY -> PushSurface(vm, ui, engine, cols = 8, modifier = Modifier.fillMaxSize().hint(PADS_HINT), onSample = a.onSample, splitKey = "desk_steps")
            Panel.SOUND -> SoundPanel(vm, ui, a.onSample, Modifier.fillMaxSize().hint(DEVICE_HINT))
            Panel.FX -> EffectsPanel(vm, ui, Modifier.fillMaxSize().hint(DEVICE_HINT))
            else -> ClipEditor(vm, ui, engine, Modifier.fillMaxSize().hint(CLIP_HINT))
        }
    }
}

/** View switches: the main view on the left, the detail views (editor | device) on the right. */
@Composable
private fun DesktopViewBar(vm: StudioViewModel, ui: StudioUi) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((p, key) in listOf(Panel.SESSION to "Tab", Panel.SONG to "Tab", Panel.MIX to "Tab")) {
            Chip(if (p == Panel.MIX) "Mixer" else p.label, ui.deskMain == p, { vm.setPanel(p) }, Modifier.hint("${p.label} view ($key cycles Session → Song → Mixer)"))
        }
        Spacer(Modifier.width(18.dp))
        Text("DETAIL", fontSize = 10.sp, color = NM.textDim, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        val editorOn = !ui.deskDeviceFocus
        Chip("Clip", ui.deskEditor == Panel.EDIT, { vm.setPanel(Panel.EDIT) }, Modifier.hint("Clip editor: piano roll or drum steps (Shift+Tab switches clip ↔ device)"),
            color = if (editorOn) NM.accent else NM.textDim)
        Chip("Pads", ui.deskEditor == Panel.PLAY, { vm.setPanel(Panel.PLAY) }, Modifier.hint("Pads and step sequencer"),
            color = if (editorOn) NM.accent else NM.textDim)
        Text("|", color = NM.line, fontSize = 14.sp)
        Chip("Sound", ui.deskDevice == Panel.SOUND, { vm.setPanel(Panel.SOUND) }, Modifier.hint("The selected track's instrument"),
            color = if (!editorOn) NM.accent else NM.textDim)
        Chip("Effects", ui.deskDevice == Panel.FX, { vm.setPanel(Panel.FX) }, Modifier.hint("The selected track's effect chain"),
            color = if (!editorOn) NM.accent else NM.textDim)
    }
}

/** Live-style info view: explains what's under the mouse; otherwise shows the main shortcuts. */
@Composable
private fun InfoBar(vm: StudioViewModel, ui: StudioUi) {
    val keys by vm.computerKeys.collectAsState()
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(NM.surface).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(HoverHint.text ?: DEFAULT_HINT, fontSize = 11.sp, color = NM.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (keys.enabled) {
            val base = (if (ui.track?.drumLayout == true) keys.drumOctave else keys.octave) + 2
            Text("⌨ ${Scale.noteName(base * 12)} · vel ${keys.velocity}", fontSize = 11.sp, color = NM.accent, maxLines = 1)
        }
        ui.track?.let { t ->
            Text("${t.name} · ${ui.project?.sceneName(ui.selectedScene)}", fontSize = 11.sp, color = NM.track(t.color), maxLines = 1)
        }
    }
}
