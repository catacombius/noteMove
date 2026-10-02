package com.notemove.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.DeviceLayout
import com.notemove.app.ui.Overlay
import com.notemove.app.ui.Panel
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.components.Chip
import com.notemove.app.ui.components.EffectsPanel
import com.notemove.app.ui.components.SampleEditor
import com.notemove.app.ui.components.SplitPane
import com.notemove.app.ui.components.ClipEditor
import com.notemove.app.ui.components.ExportSheet
import com.notemove.app.ui.components.MixerPanel
import com.notemove.app.ui.components.PushSurface
import com.notemove.app.ui.components.PlaySurface
import com.notemove.app.ui.components.SampleSheet
import com.notemove.app.ui.components.SliceEditor
import com.notemove.app.ui.components.SessionGrid
import com.notemove.app.ui.components.SettingsSheet
import com.notemove.app.ui.components.SoundPanel
import com.notemove.app.ui.components.StepStrip
import com.notemove.app.ui.components.TrackDialog
import com.notemove.app.ui.components.TransportBar
import com.notemove.app.ui.components.rememberEngineState
import com.notemove.app.ui.theme.NM
import com.notemove.core.engine.EngineState

@Composable
fun StudioScreen(vm: StudioViewModel, ui: StudioUi, layout: DeviceLayout) {
    val engine = rememberEngineState(vm)
    var settings by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var sample by remember { mutableStateOf(false) }
    var editTrackId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    // Sheets requested from the keyboard (Ctrl+E, Ctrl+,).
    val overlay by vm.overlayRequest.collectAsState()
    LaunchedEffect(overlay) {
        when (overlay) {
            Overlay.SETTINGS -> settings = true
            Overlay.EXPORT -> export = true
            null -> Unit
        }
        vm.overlayRequest.value = null
    }
    // Back closes whatever is on top first (sheets, editors, selections), and only then leaves the set.
    BackHandler {
        when {
            ui.editingSampleId != null -> vm.closeSampleEditor()
            ui.slicingSampleId != null -> vm.closeSlicer()
            settings -> settings = false
            export -> { vm.resetExport(); export = false }
            sample -> sample = false
            editTrackId != null -> editTrackId = null
            ui.noteSelection.isNotEmpty() -> vm.clearNoteSelection()
            ui.clipSelection.isNotEmpty() -> vm.clearClipSelection()
            else -> vm.closeProject()
        }
    }

    val actions = StudioActions(
        onSettings = { settings = true },
        onExport = { export = true },
        onSample = { sample = true },
        onEditTrack = { editTrackId = it },
    )

    Box(Modifier.fillMaxSize().background(NM.bg)) {
        when (layout.mode) {
            DeviceLayout.Mode.COVER -> if (layout.wideCover) SplitLayout(vm, ui, engine, actions, layout, horizontal = true) else CoverLayout(vm, ui, engine, actions)
            DeviceLayout.Mode.SPREAD -> SplitLayout(vm, ui, engine, actions, layout, horizontal = layout.landscape)
            DeviceLayout.Mode.TABLETOP -> TabletopLayout(vm, ui, engine, actions, layout)
            DeviceLayout.Mode.BOOK -> BookLayout(vm, ui, engine, actions, layout)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 72.dp))
    }

    if (settings) SettingsSheet(vm, ui) { settings = false }
    if (export) ExportSheet(vm, ui) { export = false }
    if (sample) SampleSheet(vm, ui) { sample = false }
    ui.editingSampleId?.let { SampleEditor(vm, ui, it) }
    ui.slicingSampleId?.let { SliceEditor(vm, ui, it) }
    editTrackId?.let { id -> ui.project?.track(id)?.let { TrackDialog(vm, it) { editTrackId = null } } ?: run { editTrackId = null } }
}

private class StudioActions(
    val onSettings: () -> Unit,
    val onExport: () -> Unit,
    val onSample: () -> Unit,
    val onEditTrack: (String) -> Unit,
)

// ----------------------------------------------------------------------------------------------
// Outer (cover) screen: Note-style, one panel at a time with bottom navigation.
// ----------------------------------------------------------------------------------------------

@Composable
private fun CoverLayout(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions) {
    val splits by vm.splits.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TransportBar(vm, ui, engine, compact = true, onBack = vm::closeProject, onSettings = a.onSettings, onExport = a.onExport)
        TrackStrip(vm, ui, a)
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            when (ui.panel) {
                Panel.SESSION -> SessionGrid(vm, ui, engine, Modifier.fillMaxSize(), colWidth = 86.dp, onEditTrack = { a.onEditTrack(it.id) })
                Panel.PLAY -> PushSurface(vm, ui, engine, cols = 4, modifier = Modifier.fillMaxSize(), onSample = a.onSample, splitKey = "cover_steps")
                Panel.EDIT -> ClipEditor(vm, ui, engine, Modifier.fillMaxSize(), compact = true)
                Panel.SOUND -> SoundPanel(vm, ui, a.onSample, Modifier.fillMaxSize())
                Panel.FX -> EffectsPanel(vm, ui, Modifier.fillMaxSize())
                Panel.MIX -> MixerPanel(vm, ui, engine, Modifier.fillMaxSize())
            }
        }
        NavigationBar(containerColor = NM.surface, tonalElevation = 0.dp, modifier = Modifier.height(64.dp)) {
            for (p in Panel.entries) {
                NavigationBarItem(
                    selected = ui.panel == p,
                    onClick = { vm.setPanel(p) },
                    icon = { Icon(iconFor(p), p.label) },
                    label = { Text(p.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = NM.surfaceHigh, selectedIconColor = NM.accent, selectedTextColor = NM.accent),
                )
            }
        }
    }
}

private fun iconFor(p: Panel): ImageVector = when (p) {
    Panel.SESSION -> Icons.Filled.Apps
    Panel.PLAY -> Icons.Filled.GridOn
    Panel.EDIT -> Icons.Filled.Edit
    Panel.SOUND -> Icons.Filled.GraphicEq
    Panel.FX -> Icons.Filled.AutoAwesome
    Panel.MIX -> Icons.Filled.Tune
}

/** Quick track switcher (Note shows the tracks along the top). */
@Composable
private fun TrackStrip(vm: StudioViewModel, ui: StudioUi, a: StudioActions) {
    val project = ui.project ?: return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in project.tracks) Chip(t.name, t.id == ui.track?.id, { vm.selectTrack(t.id) }, color = NM.track(t.color))
        Text("Scene ${ui.selectedScene + 1}", fontSize = 11.sp, color = NM.textDim, modifier = Modifier.padding(start = 6.dp))
        ui.track?.let { t -> Chip("•••", false, { a.onEditTrack(t.id) }) }
    }
}

// ----------------------------------------------------------------------------------------------
// Inner screen: editor pane + Move-style pad surface.
// ----------------------------------------------------------------------------------------------

private val sidePanels = listOf(Panel.SESSION, Panel.EDIT, Panel.SOUND, Panel.FX, Panel.MIX)

@Composable
private fun PanelTabs(vm: StudioViewModel, ui: StudioUi, modifier: Modifier = Modifier) {
    val current = if (ui.panel == Panel.PLAY) Panel.SESSION else ui.panel
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (p in sidePanels) {
            Chip(p.label, p == current, { vm.setPanel(p) })
        }
        Spacer(Modifier.weight(1f))
        ui.track?.let { t ->
            Text(t.name, color = NM.track(t.color), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("  ·  ${ui.project?.sceneName(ui.selectedScene)}", color = NM.textDim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SidePanel(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions, modifier: Modifier = Modifier) {
    Column(modifier) {
        PanelTabs(vm, ui)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (if (ui.panel == Panel.PLAY) Panel.SESSION else ui.panel) {
                Panel.SESSION, Panel.PLAY -> SessionGrid(vm, ui, engine, Modifier.fillMaxSize(), onEditTrack = { a.onEditTrack(it.id) })
                Panel.EDIT -> ClipEditor(vm, ui, engine, Modifier.fillMaxSize())
                Panel.SOUND -> SoundPanel(vm, ui, a.onSample, Modifier.fillMaxSize())
                Panel.FX -> EffectsPanel(vm, ui, Modifier.fillMaxSize())
                Panel.MIX -> MixerPanel(vm, ui, engine, Modifier.fillMaxSize())
            }
        }
    }
}

/** Push/Move surface: 8 columns of pads; Play or Sequence mode (drag the divider to resize). */
@Composable
private fun MoveSurface(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions, modifier: Modifier = Modifier) {
    PushSurface(vm, ui, engine, cols = 8, modifier = modifier, onSample = a.onSample)
}

@Composable
private fun SplitLayout(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions, layout: DeviceLayout, horizontal: Boolean) {
    val splits by vm.splits.collectAsState()
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TransportBar(vm, ui, engine, compact = layout.mode == DeviceLayout.Mode.COVER, onBack = vm::closeProject, onSettings = a.onSettings, onExport = a.onExport)
        val key = if (horizontal) "spread_h" else "spread_v"
        SplitPane(
            splits[key] ?: 0.5f, { vm.setSplit(key, it) }, vertical = !horizontal,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(10.dp), default = 0.5f, minPane = 160.dp,
            first = { SidePanel(vm, ui, engine, a, Modifier.fillMaxSize()) },
            second = { MoveSurface(vm, ui, engine, a, Modifier.fillMaxSize()) },
        )
    }
}

/** Half-folded, hinge horizontal: clips / editor above the hinge, pads on the lower half lying on the table. */
@Composable
private fun TabletopLayout(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions, layout: DeviceLayout) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = layout.hingeDp.coerceIn(120.dp, maxOf(120.dp, maxHeight - 160.dp))
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.height(top).fillMaxWidth().safeDrawingPadding()) {
                TransportBar(vm, ui, engine, compact = false, onBack = vm::closeProject, onSettings = a.onSettings, onExport = a.onExport)
                SidePanel(vm, ui, engine, a, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp))
            }
            Spacer(Modifier.height(layout.hingeSizeDp + 8.dp))
            MoveSurface(vm, ui, engine, a, Modifier.weight(1f).fillMaxWidth().safeDrawingPadding().padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

/** Half-folded, hinge vertical (book): editor on the left page, pads on the right; the divider starts at the hinge. */
@Composable
private fun BookLayout(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, a: StudioActions, layout: DeviceLayout) {
    val splits by vm.splits.collectAsState()
    val hingeFraction = (layout.hingeDp.value / layout.widthDp.coerceAtLeast(1)).coerceIn(0.2f, 0.8f)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        TransportBar(vm, ui, engine, compact = false, onBack = vm::closeProject, onSettings = a.onSettings, onExport = a.onExport)
        SplitPane(
            splits["book"] ?: hingeFraction, { vm.setSplit("book", it) }, vertical = false,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), default = hingeFraction, minPane = 200.dp,
            first = { SidePanel(vm, ui, engine, a, Modifier.fillMaxSize()) },
            second = { MoveSurface(vm, ui, engine, a, Modifier.fillMaxSize()) },
        )
    }
}
