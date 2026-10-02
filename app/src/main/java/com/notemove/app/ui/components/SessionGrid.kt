package com.notemove.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.SlotRef
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.engine.EngineState
import com.notemove.core.model.Project
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind

/**
 * Session View: tracks are columns, scenes are rows (as in Live). Tap a clip to launch it, tap an
 * empty slot to select it for recording, long-press for clip / scene / track actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionGrid(
    vm: StudioViewModel,
    ui: StudioUi,
    engine: State<EngineState>,
    modifier: Modifier = Modifier,
    colWidth: Dp = 92.dp,
    rowHeight: Dp = 46.dp,
    onEditTrack: (Track) -> Unit = {},
) {
    val project = ui.project ?: return
    // Only recompose slots when which clips are playing / queued changes, not every frame.
    val playing by remember(engine) { derivedStateOf { engine.value.playingScenes } }
    val queued by remember(engine) { derivedStateOf { engine.value.queuedScenes } }
    val isPlaying by remember(engine) { derivedStateOf { engine.value.playing } }
    val blink by remember(engine) { derivedStateOf { (engine.value.estimatedBeat() * 2).toLong() % 2L == 0L } }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Column(modifier) {
        if (ui.clipSelection.isNotEmpty()) ClipSelectionBar(vm, ui)
        // Track headers
        Row(Modifier.horizontalScroll(hScroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in project.tracks) TrackHeader(vm, t, t.id == ui.track?.id, colWidth, onEditTrack)
            AddTrackButton(vm, colWidth)
            Box(Modifier.width(64.dp))
        }
        Box(Modifier.weight(1f, fill = true)) {
            Column(Modifier.verticalScroll(vScroll).horizontalScroll(hScroll)) {
                for (scene in 0 until project.sceneCount) {
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (t in project.tracks) {
                            ClipSlot(vm, ui, t, scene, playing[t.id] == scene && isPlaying, queued[t.id] == scene, blink, colWidth, rowHeight)
                        }
                        Box(Modifier.width(colWidth))
                        SceneButton(vm, project, scene, scene == ui.selectedScene, rowHeight)
                    }
                }
                Row(Modifier.padding(top = 6.dp)) {
                    Chip("+ Scene", false, vm::addScene)
                }
            }
        }
        // Stop buttons
        Row(Modifier.horizontalScroll(hScroll).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (t in project.tracks) {
                val active = playing.containsKey(t.id) && isPlaying
                Box(
                    Modifier.width(colWidth).height(30.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (active) NM.surfaceHigh else NM.padDim)
                        .holdClickable(onClick = { vm.stopTrack(t.id) }),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Stop, "Stop ${t.name}", tint = if (active) NM.text else NM.textDim, modifier = Modifier.size(18.dp)) }
            }
            Box(Modifier.width(colWidth))
            Box(
                Modifier.width(64.dp).height(30.dp).clip(RoundedCornerShape(6.dp)).background(NM.surfaceHigh)
                    .holdClickable(onClick = vm::stopAll),
                contentAlignment = Alignment.Center,
            ) { Text("Stop all", fontSize = 11.sp, color = NM.text) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackHeader(vm: StudioViewModel, t: Track, selected: Boolean, width: Dp, onEdit: (Track) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val color = NM.track(t.color)
    Box {
        Column(
            Modifier.width(width).clip(RoundedCornerShape(6.dp))
                .background(if (selected) color.copy(alpha = 0.3f) else NM.surfaceHigh)
                .border(if (selected) 2.dp else 0.dp, if (selected) color else Color.Transparent, RoundedCornerShape(6.dp))
                .holdClickable(onClick = { vm.selectTrack(t.id) }, onLongClick = { menu = true })
                .padding(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                Text(" ${t.name}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NM.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(t.kind.label, fontSize = 10.sp, color = NM.textDim)
                if (t.mute) Text("M", fontSize = 10.sp, color = NM.queued, fontWeight = FontWeight.Bold)
                if (t.solo) Text("S", fontSize = 10.sp, color = NM.solo, fontWeight = FontWeight.Bold)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Edit track…") }, { menu = false; onEdit(t) })
            DropdownMenuItem({ Text("Select all clips") }, { menu = false; vm.selectClipsInTrack(t.id) })
            DropdownMenuItem({ Text(if (t.mute) "Unmute" else "Mute") }, { menu = false; vm.toggleMute(t.id) })
            DropdownMenuItem({ Text(if (t.solo) "Unsolo" else "Solo") }, { menu = false; vm.toggleSolo(t.id) })
            DropdownMenuItem({ Text("Duplicate") }, { menu = false; vm.duplicateTrack(t.id) })
            DropdownMenuItem({ Text("Move left") }, { menu = false; vm.moveTrack(t.id, -1) })
            DropdownMenuItem({ Text("Move right") }, { menu = false; vm.moveTrack(t.id, 1) })
            HorizontalDivider()
            DropdownMenuItem({ Text("Delete", color = NM.record) }, { menu = false; vm.deleteTrack(t.id) })
        }
    }
}

@Composable
private fun AddTrackButton(vm: StudioViewModel, width: Dp) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.width(width).height(44.dp).clip(RoundedCornerShape(6.dp)).background(NM.padDim)
                .clickable { menu = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, "Add track", tint = NM.textDim) }
        DropdownMenu(menu, { menu = false }) {
            for (k in TrackKind.entries) DropdownMenuItem({ Text("${k.label} track") }, { menu = false; vm.addTrack(k) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipSlot(
    vm: StudioViewModel, ui: StudioUi, t: Track, scene: Int,
    playing: Boolean, queued: Boolean, blink: Boolean, width: Dp, height: Dp,
) {
    val clip = t.clips[scene]
    val selected = ui.track?.id == t.id && ui.selectedScene == scene
    val ref = SlotRef(t.id, scene)
    val multi = ui.clipSelection.isNotEmpty()
    val inSelection = ref in ui.clipSelection
    val color = NM.track(t.color)
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.width(width).height(height).clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        clip == null -> NM.padDim
                        playing -> color
                        else -> color.copy(alpha = 0.45f)
                    },
                )
                .border(
                    if (selected || queued || inSelection) 2.dp else 0.dp,
                    when {
                        inSelection -> NM.solo
                        queued && blink -> NM.queued
                        selected -> NM.text
                        else -> Color.Transparent
                    },
                    RoundedCornerShape(6.dp),
                )
                .holdClickable(
                    onClick = {
                        when {
                            multi && clip != null -> vm.toggleClipSelection(ref)
                            multi -> { vm.selectTrack(t.id); vm.selectScene(scene) } // paste target
                            clip != null -> vm.launchClip(t.id, scene)
                            else -> { vm.selectTrack(t.id); vm.selectScene(scene) }
                        }
                    },
                    onLongClick = { vm.selectTrack(t.id); vm.selectScene(scene); menu = true },
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (clip != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp).alpha(if (playing) 1f else 0.6f), tint = if (playing) Color.Black else NM.text)
                    Text(
                        clip.name.ifBlank { "${clip.notes.size} notes" }, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (playing) Color.Black else NM.text,
                    )
                }
            } else if (selected) {
                Text("●", fontSize = 10.sp, color = NM.record.copy(alpha = 0.7f))
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (clip == null) {
                for (bars in listOf(1, 2, 4, 8)) DropdownMenuItem({ Text("New $bars-bar clip") }, { menu = false; vm.createClip(t.id, scene, bars) })
                if (vm.hasClipClipboard) DropdownMenuItem({ Text("Paste here") }, { menu = false; vm.pasteClips() })
            } else {
                DropdownMenuItem({ Text(if (inSelection) "Deselect" else "Select (multi)") }, { menu = false; vm.toggleClipSelection(ref) })
                DropdownMenuItem({ Text("Select all in scene") }, { menu = false; vm.selectClipsInScene(scene) })
                DropdownMenuItem({ Text("Select all in track") }, { menu = false; vm.selectClipsInTrack(t.id) })
                HorizontalDivider()
                DropdownMenuItem({ Text("Launch") }, { menu = false; vm.launchClip(t.id, scene) })
                DropdownMenuItem({ Text("Copy") }, { menu = false; vm.clearClipSelection(); vm.toggleClipSelection(ref); vm.copySelectedClips(); vm.clearClipSelection() })
                if (vm.hasClipClipboard) DropdownMenuItem({ Text("Paste here") }, { menu = false; vm.pasteClips() })
                DropdownMenuItem({ Text("Duplicate") }, { menu = false; vm.duplicateClip(t.id, scene) })
                DropdownMenuItem({ Text("Double loop") }, { menu = false; vm.duplicateLoop() })
                DropdownMenuItem({ Text("Quantize") }, { menu = false; vm.quantizeClip(ui.stepGrid) })
                HorizontalDivider()
                DropdownMenuItem({ Text("Delete", color = NM.record) }, { menu = false; vm.deleteClip(t.id, scene) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SceneButton(vm: StudioViewModel, project: Project, scene: Int, selected: Boolean, height: Dp) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.width(120.dp).height(height).clip(RoundedCornerShape(6.dp))
                .background(if (selected) NM.surfaceHigh else NM.surface)
                .holdClickable(onClick = { vm.launchScene(scene) }, onLongClick = { menu = true })
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.PlayArrow, "Launch scene", tint = NM.play, modifier = Modifier.size(18.dp))
            Text(project.sceneName(scene), fontSize = 11.sp, color = NM.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Select clips in scene") }, { menu = false; vm.selectClipsInScene(scene) })
            DropdownMenuItem({ Text("Duplicate scene") }, { menu = false; vm.duplicateScene(scene) })
            DropdownMenuItem({ Text("Insert empty scene below") }, {
                menu = false
                vm.duplicateScene(scene); vm.edit { p -> p.copy(tracks = p.tracks.map { it.withClip(scene + 1, null) }) }
            })
            HorizontalDivider()
            DropdownMenuItem({ Text("Delete scene", color = NM.record) }, { menu = false; vm.deleteScene(scene) })
        }
    }
}

@Composable
private fun ClipSelectionBar(vm: StudioViewModel, ui: StudioUi) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("✕ ${ui.clipSelection.size} clips", true, vm::clearClipSelection, color = NM.solo)
        Chip("Launch", false, vm::launchSelectedClips, color = NM.play)
        Chip("Copy", false, vm::copySelectedClips)
        if (vm.hasClipClipboard) Chip("Paste at slot", false, vm::pasteClips)
        Chip("Duplicate", false, vm::duplicateSelectedClips)
        Chip("Quantize", false, { vm.quantizeSelectedClips(ui.stepGrid) })
        Chip("Delete", false, vm::deleteSelectedClips, color = NM.record)
        Text("tap clips to add/remove", fontSize = 11.sp, color = NM.textDim)
    }
}
