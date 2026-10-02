package com.notemove.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.engine.EngineState
import com.notemove.core.model.ArpMode
import com.notemove.core.model.Clip
import com.notemove.core.model.ClipOps
import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.Scale
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind

/** Engine state refreshed every display frame. Read `.value` only in the leaf that needs it. */
@Composable
fun rememberEngineState(vm: StudioViewModel): State<EngineState> = produceState(vm.engineState, vm) {
    while (true) withFrameNanos { value = vm.engineState }
}

/** Pitches the sequencer is sounding right now on [track] (for lighting pads during playback). */
fun soundingPitches(state: EngineState, track: Track): Set<Int> {
    val scene = state.playingScenes[track.id] ?: return emptySet()
    val clip = track.clips[scene] ?: return emptySet()
    val pos = state.clipPosition(track.id, clip) ?: return emptySet()
    return clip.notes.filter { pos >= it.start && pos < it.start + minOf(it.duration, 0.2) }.mapTo(HashSet()) { it.pitch }
}

/** Where the playhead is inside the selected clip, or null if that clip is not playing. */
fun playheadIn(state: EngineState, track: Track?, scene: Int, clip: Clip?): Double? {
    if (track == null || clip == null) return null
    if (state.playingScenes[track.id] != scene) return null
    return state.clipPosition(track.id, clip)
}

/**
 * The pad area. [cols] x [rows] for melodic tracks; drum tracks use a 4x4 block, and when [cols] is 8
 * (Move layout) the right 4x4 block plays the selected drum at 16 velocities, as on Push.
 */
@Composable
fun PlaySurface(
    vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, rows: Int, cols: Int, modifier: Modifier = Modifier,
    onSample: () -> Unit = {},
) {
    val project = ui.project ?: return
    val track = ui.track ?: return
    val sounding = soundingPitches(engine.value, track)
    var holdPad by remember { mutableStateOf<Int?>(null) }
    val onHoldDrum: (PadSpec) -> Unit = { s -> (s.id - DRUM_BASE_NOTE).takeIf { it in 0..15 }?.let { vm.selectPad(it); holdPad = it } }
    Box(modifier) {
        if (track.drumLayout) {
            if (cols >= 8) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PadGrid(4, 4, { r, c -> PadLayouts.drumSpec(track, r * 4 + c, ui.heldPitches, sounding, ui.selectedPad) },
                        onDown = { s, v -> vm.padDown(s.id, v) }, onUp = { vm.padUp(it.id) }, modifier = Modifier.weight(1f).fillMaxHeight(),
                        onHold = onHoldDrum)
                    val color = NM.track(track.color)
                    val pitch = DRUM_BASE_NOTE + ui.selectedPad
                    PadGrid(4, 4, { r, c ->
                        val i = r * 4 + c
                        PadSpec(1000 + i, if (i == 0 || i == 15) "${(i + 1) * 8 - 1}" else "", color.copy(alpha = 0.25f + 0.75f * (i + 1) / 16f),
                            lit = false, accent = true)
                    }, onDown = { s, _ -> vm.padDown(pitch, ((s.id - 1000) + 1) * 8 - 1) }, onUp = { vm.padUp(pitch) },
                        modifier = Modifier.weight(1f).fillMaxHeight(), slide = false)
                }
            } else {
                PadGrid(4, 4, { r, c -> PadLayouts.drumSpec(track, r * 4 + c, ui.heldPitches, sounding, ui.selectedPad) },
                    onDown = { s, v -> vm.padDown(s.id, v) }, onUp = { vm.padUp(it.id) }, modifier = Modifier.fillMaxSize(), onHold = onHoldDrum)
            }
        } else {
            PadGrid(rows, cols, { r, c -> PadLayouts.melodicSpec(project, track, r, c, ui.octave, ui.inKey, ui.heldPitches, sounding, ui.rowLayout, cols) },
                onDown = { s, v -> vm.padDown(s.id, v) }, onUp = { vm.padUp(it.id) }, modifier = Modifier.fillMaxSize())
        }
        // Touch & hold a drum pad for its options.
        val hp = holdPad
        DropdownMenu(hp != null, { holdPad = null }) {
            if (hp != null) {
                val pad = track.drumKit?.pads?.getOrNull(hp)
                Text("  Pad ${hp + 1} · ${pad?.name ?: ""}", fontSize = 12.sp, color = NM.textDim)
                DropdownMenuItem({ Text("Edit sound") }, { holdPad = null; vm.setPanel(com.notemove.app.ui.Panel.SOUND) })
                DropdownMenuItem({ Text("Sample into pad…") }, { holdPad = null; onSample() })
                if (pad?.sampleId != null) DropdownMenuItem({ Text("Edit sample (spectral)") }, { holdPad = null; vm.openSampleEditor(pad.sampleId) })
                DropdownMenuItem({ Text("Steps for this pad") }, { holdPad = null; vm.setPanel(com.notemove.app.ui.Panel.EDIT) })
                DropdownMenuItem({ Text("Copy pad") }, { holdPad = null; vm.copyPad(hp) })
                if (vm.hasPadClipboard) DropdownMenuItem({ Text("Paste pad") }, { holdPad = null; vm.pastePad(hp) })
                DropdownMenuItem({ Text("Clear pad's notes in clip") }, { holdPad = null; vm.clearPadNotes(hp) })
            }
        }
    }
}

/** Octave, layout, repeat, velocity and capture controls above the pads. */
@Composable
fun PadToolbar(vm: StudioViewModel, ui: StudioUi, modifier: Modifier = Modifier) {
    val project = ui.project ?: return
    val track = ui.track ?: return
    var repeatMenu by remember { mutableStateOf(false) }
    var layoutMenu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Push's "Layout" button: play the grid, or split it into steps + pads.
        for (m in com.notemove.app.ui.PadMode.entries) Chip(m.label, ui.padMode == m, { vm.setPadMode(m) }, color = NM.track(track.color))
        ToggleBox("Accent", ui.accent, vm::toggleAccent, onColor = NM.queued)
        if (!track.drumLayout) {
            Box {
                Chip("Rows: ${ui.rowLayout.label}", false, { layoutMenu = true })
                DropdownMenu(layoutMenu, { layoutMenu = false }) {
                    for (l in com.notemove.app.ui.RowLayout.entries) DropdownMenuItem({ Text(l.label) }, { vm.setRowLayout(l); layoutMenu = false })
                }
            }
        }
        if (!track.drumLayout) {
            Chip("Oct −", false, { vm.setOctave(ui.octave - 1) })
            Text("C${ui.octave}", color = NM.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Chip("Oct +", false, { vm.setOctave(ui.octave + 1) })
            ToggleBox(if (ui.inKey) "In Key" else "Chromatic", ui.inKey, vm::toggleInKey)
            Text("${Scale.NOTE_NAMES[project.rootNote]} ${project.scale.label}", color = NM.textDim, fontSize = 12.sp)
        } else {
            Text(PadLayouts.padName(track, ui.selectedPad), color = NM.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box {
            ToggleBox(if (ui.noteRepeat > 0) "Repeat ${rateLabel(ui.noteRepeat)}" else "Repeat", ui.noteRepeat > 0, { repeatMenu = true })
            DropdownMenu(repeatMenu, { repeatMenu = false }) {
                DropdownMenuItem({ Text("Off") }, { vm.setNoteRepeat(0.0); repeatMenu = false })
                for ((rate, label) in listOf(1.0 to "1/4", 0.5 to "1/8", 1.0 / 3 to "1/8T", 0.25 to "1/16", 1.0 / 6 to "1/16T", 0.125 to "1/32")) {
                    DropdownMenuItem({ Text(label) }, { vm.setNoteRepeat(rate); repeatMenu = false })
                }
            }
        }
        ArpButton(vm, track)
        ToggleBox("Fixed Vel", ui.fixedVelocity, vm::toggleFixedVelocity)
        ToggleBox("Capture", false, vm::capture, onColor = NM.play)
    }
}

private fun rateLabel(r: Double) = ClipOps.GRIDS.firstOrNull { kotlin.math.abs(it.first - r) < 1e-6 }?.second ?: ""

/**
 * Move's row of 16 step buttons: steps of the selected drum pad, or of the last played note on a
 * melodic track. Tap a step to toggle it; swipe sideways to slide between pages (at least two).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StepStrip(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier, rowsOf8: Boolean = false) {
    val track = ui.track ?: return
    val clip = ui.clip
    val grid = ui.stepGrid
    val pitch = if (track.drumLayout) DRUM_BASE_NOTE + ui.selectedPad else ui.lastPlayedPitch
    val pageBeats = grid * 16
    val length = clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val pages = sequencerPages(ui, pageBeats)
    val inClipPages = kotlin.math.ceil(length / pageBeats - 1e-9).toInt()
    val head = playheadIn(engine.value, track, ui.selectedScene, clip)
    val color = NM.track(track.color)
    val pitches = if (track.drumLayout) setOf(pitch) else ui.heldPitches.ifEmpty { setOf(pitch) }
    val pager = rememberStepPagerState(ui, pages)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (track.drumLayout) "Steps · ${PadLayouts.padName(track, ui.selectedPad)}" else "Steps · ${pitches.sorted().joinToString { Scale.noteName(it) }}",
                color = NM.textDim, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            PageDots(pager, pages, inClipPages)
        }
        Spacer(Modifier.height(4.dp))
        StepPager(vm, ui, pages, Modifier.weight(1f).fillMaxWidth(), pager) { page ->
            val perRow = if (rowsOf8) 8 else 16
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until 16 / perRow) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (k in 0 until perRow) {
                            val i = r * perRow + k
                            val start = page * pageBeats + i * grid
                            val inClip = start < length - 1e-9
                            val on = clip != null && pitches.any { p -> clip.notes.any { it.pitch == p && kotlin.math.abs(it.start - start) < grid / 2 } }
                            val isHead = head != null && head >= start && head < start + grid
                            val beatStart = (i % 4 == 0)
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            on -> color
                                            !inClip -> NM.padDim.copy(alpha = 0.5f)
                                            beatStart -> NM.surfaceHigh
                                            else -> NM.pad.copy(alpha = 0.7f)
                                        },
                                    )
                                    .then(if (isHead) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                                    .clickable { vm.toggleStepAt(pitches, start, grid, pageBeats) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Arp on/off; touch & hold (or tap the arrow) for its settings. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ArpButton(vm: StudioViewModel, track: Track) {
    val a = track.arp
    var menu by remember { mutableStateOf(false) }
    val color = NM.track(track.color)
    Box {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .border(1.dp, if (a.enabled) color else NM.line, RoundedCornerShape(6.dp))
                    .background(if (a.enabled) color.copy(alpha = 0.25f) else Color.Transparent)
                    .combinedClickable(onClick = { vm.setArp { it.copy(enabled = !it.enabled) } }, onLongClick = { menu = true })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(if (a.enabled) "Arp ${a.mode.label} ${rateLabel(a.rate)}" else "Arp", fontSize = 12.sp,
                    color = if (a.enabled) color else NM.textDim, fontWeight = FontWeight.Bold)
            }
            Text("▾", Modifier.clickable { menu = true }.padding(4.dp), color = NM.textDim, fontSize = 12.sp)
        }
        DropdownMenu(menu, { menu = false }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(300.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Arpeggiator", fontWeight = FontWeight.SemiBold, color = NM.text, modifier = Modifier.weight(1f))
                    ToggleBox(if (a.enabled) "ON" else "OFF", a.enabled, { vm.setArp { it.copy(enabled = !it.enabled) } }, onColor = color)
                }
                SectionTitle("Mode")
                FlowRowCompat { for (m in ArpMode.entries) Chip(m.label, a.mode == m, { vm.setArp { it.copy(mode = m) } }, color = color) }
                SectionTitle("Rate")
                FlowRowCompat {
                    for ((r, label) in listOf(1.0 to "1/4", 0.5 to "1/8", 1.0 / 3 to "1/8T", 0.25 to "1/16", 1.0 / 6 to "1/16T", 0.125 to "1/32")) {
                        Chip(label, kotlin.math.abs(a.rate - r) < 1e-6, { vm.setArp { it.copy(rate = r) } }, color = color)
                    }
                }
                SectionTitle("Octaves")
                FlowRowCompat { for (o in 1..4) Chip("$o", a.octaves == o, { vm.setArp { it.copy(octaves = o) } }, color = color) }
                SectionTitle("Gate ${(a.gate * 100).toInt()}%")
                androidx.compose.material3.Slider(a.gate, { g -> vm.setArp { it.copy(gate = g) } }, valueRange = 0.1f..1f)
                ToggleBox("Latch (keeps playing after release)", a.latch, { vm.setArp { it.copy(latch = !it.latch) } }, onColor = color)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}
