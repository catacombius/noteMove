package com.spectraseq.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.PadMode
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.engine.EngineState
import com.spectraseq.core.model.DRUM_BASE_NOTE
import com.spectraseq.core.model.Scale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The pad surface, modelled on Ableton Push:
 * - **Play**: the whole grid plays (in-key/chromatic melodic layout, or drum pads + 16 velocities),
 *   with Move's 16-step row underneath.
 * - **Sequence**: the top half becomes a step sequencer for the selected drum pad / held notes, the
 *   bottom half keeps playing pads; drum tracks also get Push's loop selector (bottom right).
 *
 * The number of rows adapts to the space: 8×8 on the unfolded Fold, 4×8 when the area is wide and short.
 */
@Composable
fun PushSurface(
    vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, cols: Int, modifier: Modifier = Modifier,
    onSample: () -> Unit = {}, splitKey: String = "move_steps",
) {
    val splits by vm.splits.collectAsState()
    Column(modifier) {
        PadToolbar(vm, ui, Modifier.padding(bottom = 6.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tall = maxHeight > maxWidth * 0.62f
            val rows = when {
                cols >= 8 -> if (tall) 8 else 4
                else -> if (maxHeight > maxWidth * 1.3f) 8 else 6
            }
            if (ui.padMode == PadMode.SEQUENCE) {
                SequenceSurface(vm, ui, engine, rows.coerceAtLeast(4), cols, Modifier.fillMaxSize())
            } else {
                SplitPane(
                    splits[splitKey] ?: 0.82f, { vm.setSplit(splitKey, it) }, vertical = true,
                    modifier = Modifier.fillMaxSize(), default = 0.82f, minPane = 40.dp,
                    first = { PlaySurface(vm, ui, engine, rows = rows, cols = cols, modifier = Modifier.fillMaxSize(), onSample = onSample) },
                    second = { StepStrip(vm, ui, engine, Modifier.fillMaxSize(), rowsOf8 = cols < 8) },
                )
            }
        }
    }
}

@Composable
private fun SequenceSurface(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, rows: Int, cols: Int, modifier: Modifier) {
    val track = ui.track ?: return
    val project = ui.project ?: return
    val stepRows = rows / 2
    val padRows = rows - stepRows
    val drums = track.drumLayout
    val pitches = if (drums) setOf(DRUM_BASE_NOTE + ui.selectedPad) else ui.heldPitches.ifEmpty { setOf(ui.lastPlayedPitch) }
    val steps = stepRows * cols
    val pageBeats = steps * ui.stepGrid
    val length = ui.clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val pages = sequencerPages(ui, pageBeats)
    val inClipPages = ceil(length / pageBeats - 1e-9).toInt()
    val pager = rememberStepPagerState(ui, pages)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Page strip (always visible; drums on wide grids also get the Push loop selector below).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (drums) "Sequencing ${PadLayouts.padName(track, ui.selectedPad)}" else "Sequencing ${pitches.sorted().joinToString { Scale.noteName(it) }}",
                fontSize = 11.sp, color = NM.textDim, modifier = Modifier.weight(1f),
            )
            PageDots(pager, pages, inClipPages)
        }
        StepPager(vm, ui, pages, Modifier.weight(stepRows.toFloat()).fillMaxWidth(), pager) { page ->
            StepGrid(vm, ui, engine, stepRows, cols, pitches, page, Modifier.fillMaxSize())
        }
        Box(Modifier.weight(padRows.toFloat()).fillMaxWidth()) {
            val sounding = soundingPitches(engine.value, track)
            if (drums) {
                if (cols >= 8) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PadGrid(4, 4, { r, c -> PadLayouts.drumSpec(track, r * 4 + c, ui.heldPitches, sounding, ui.selectedPad) },
                            onDown = { s, v -> vm.padDown(s.id, v) }, onUp = { vm.padUp(it.id) }, modifier = Modifier.weight(1f).fillMaxHeight())
                        LoopSelector(vm, ui, engine, pageBeats, Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    PadGrid(4, 4, { r, c -> PadLayouts.drumSpec(track, r * 4 + c, ui.heldPitches, sounding, ui.selectedPad) },
                        onDown = { s, v -> vm.padDown(s.id, v) }, onUp = { vm.padUp(it.id) }, modifier = Modifier.fillMaxSize())
                }
            } else {
                PadGrid(padRows, cols, { r, c ->
                    PadLayouts.melodicSpec(project, track, r, c, ui.octave, ui.inKey, ui.heldPitches, sounding, ui.rowLayout, cols, selected = ui.lastPlayedPitch)
                }, onDown = { s, v -> vm.padDown(s.id, v) }, onUp = { vm.padUp(it.id) }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * Push-style step pads. Bright = a note of the sequenced pitch starts on this step (brightness =
 * velocity), dim dot = other notes on this step, white outline = playhead. Tap toggles; touch & hold
 * opens velocity / length / nudge options for the step.
 */
@Composable
private fun StepGrid(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, rows: Int, cols: Int, pitches: Set<Int>, page: Int, modifier: Modifier) {
    val track = ui.track ?: return
    val clip = ui.clip
    val grid = ui.stepGrid
    val steps = rows * cols
    val pageBeats = steps * grid
    val length = clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val head = playheadIn(engine.value, track, ui.selectedScene, clip)
    val color = NM.track(track.color)
    var holdStep by remember { mutableStateOf<Double?>(null) }
    Box(modifier) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (c in 0 until cols) {
                        val i = r * cols + c
                        val start = page * pageBeats + i * grid
                        val inClip = start < length - 1e-9
                        val own = clip?.notes?.firstOrNull { it.pitch in pitches && abs(it.start - start) < grid / 2 }
                        val other = clip?.notes?.any { it.pitch !in pitches && abs(it.start - start) < grid / 2 } == true
                        val isHead = head != null && head >= start && head < start + grid
                        val beat = (i % 4 == 0)
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        own != null -> color.copy(alpha = 0.35f + 0.65f * own.velocity / 127f)
                                        !inClip -> NM.padDim.copy(alpha = 0.5f)
                                        beat -> NM.surfaceHigh
                                        else -> NM.pad
                                    },
                                )
                                .border(if (isHead) 2.dp else 0.dp, if (isHead) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                                .combinedClickableCompat(
                                    { vm.toggleStepAt(pitches, start, grid, pageBeats) },
                                    { if (inClip) holdStep = start },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (other && own == null) Box(Modifier.fillMaxSize(0.18f).clip(RoundedCornerShape(50)).background(NM.textDim))
                        }
                    }
                }
            }
        }
        val hs = holdStep
        DropdownMenu(hs != null, { holdStep = null }) {
            if (hs != null) {
                val has = clip?.notes?.any { it.pitch in pitches && abs(it.start - hs) < grid / 2 } == true
                Text("  Step ${((hs / grid).toInt() % steps) + 1}", fontSize = 12.sp, color = NM.textDim)
                if (!has) DropdownMenuItem({ Text("Add note") }, { holdStep = null; vm.toggleStepAt(pitches, hs, grid, pageBeats) })
                if (has) {
                    for ((label, v) in listOf("Soft (40)" to 40, "Medium (80)" to 80, "Hard (110)" to 110, "Accent (127)" to 127)) {
                        DropdownMenuItem({ Text("Velocity $label") }, { holdStep = null; vm.editStepNotes(pitches, hs, grid) { it.copy(velocity = v) } })
                    }
                    HorizontalDivider()
                    for ((label, k) in listOf("½ step" to 0.5, "1 step" to 1.0, "2 steps" to 2.0, "4 steps" to 4.0, "8 steps" to 8.0)) {
                        DropdownMenuItem({ Text("Length $label") }, { holdStep = null; vm.editStepNotes(pitches, hs, grid) { it.copy(duration = grid * k * 0.95) } })
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Nudge earlier") }, { holdStep = null; vm.editStepNotes(pitches, hs, grid) { it.copy(start = (it.start - grid / 4).coerceAtLeast(0.0)) } })
                    DropdownMenuItem({ Text("Nudge later") }, { holdStep = null; vm.editStepNotes(pitches, hs, grid) { it.copy(start = it.start + grid / 4) } })
                    DropdownMenuItem({ Text("Delete", color = NM.record) }, { holdStep = null; vm.editStepNotes(pitches, hs, grid) { null } })
                }
            }
        }
    }
}

/**
 * Push's loop selector: one pad per page of steps. Lit = inside the clip, white = page shown, green =
 * page playing. Tap to view a page; touch & hold to set the clip's loop to end at that page.
 */
@Composable
private fun LoopSelector(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, pageBeats: Double, modifier: Modifier) {
    val track = ui.track ?: return
    val clip = ui.clip
    val length = clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val head = playheadIn(engine.value, track, ui.selectedScene, clip)
    val playingPage = head?.let { (it / pageBeats).toInt() }
    val color = NM.track(track.color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 3 downTo 0) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 4) {
                    val p = (3 - r) * 4 + c
                    val inClip = p * pageBeats < length - 1e-9
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    p == ui.stepPage -> NM.text
                                    p == playingPage -> NM.play
                                    inClip -> color.copy(alpha = 0.45f)
                                    else -> NM.padDim
                                },
                            )
                            .combinedClickableCompat(
                                { if (inClip) vm.setStepPage(p) },
                                {
                                    if (clip == null) vm.createClip(track.id, ui.selectedScene, maxOf(1, ((p + 1) * pageBeats / 4).toInt()))
                                    vm.setClipLength((p + 1) * pageBeats); vm.setStepPage(p)
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { if (p == 0 || p == playingPage) Text("${p + 1}", fontSize = 10.sp, color = if (p == ui.stepPage) Color.Black else NM.textDim) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.holdClickable(onClick = onClick, onLongClick = onLongClick)
