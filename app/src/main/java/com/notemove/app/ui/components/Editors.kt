package com.notemove.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.engine.EngineState
import com.notemove.core.model.ClipOps
import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.DRUM_PAD_COUNT
import com.notemove.core.model.Note
import com.notemove.core.model.Scale
import com.notemove.core.model.TrackKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/** Clip length, loop, quantise, transpose and grid tools shown above the editors. */
@Composable
fun ClipToolbar(vm: StudioViewModel, ui: StudioUi, modifier: Modifier = Modifier) {
    val clip = ui.clip
    var lenMenu by remember { mutableStateOf(false) }
    var gridMenu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Chip(if (clip != null) "${formatBars(clip.lengthBeats)} bars" else "No clip", false, { lenMenu = true })
            DropdownMenu(lenMenu, { lenMenu = false }) {
                for (bars in listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0)) {
                    DropdownMenuItem({ Text("${formatBars(bars * 4)} bar${if (bars > 1) "s" else ""}") }, {
                        lenMenu = false
                        if (clip == null) ui.track?.let { vm.createClip(it.id, ui.selectedScene, max(1, bars.toInt())) }
                        else vm.setClipLength(bars * 4)
                    })
                }
            }
        }
        Box {
            Chip("Grid ${ClipOps.GRIDS.firstOrNull { abs(it.first - ui.stepGrid) < 1e-6 }?.second ?: ""}", false, { gridMenu = true })
            DropdownMenu(gridMenu, { gridMenu = false }) {
                for ((g, label) in ClipOps.GRIDS) DropdownMenuItem({ Text(label) }, { vm.setStepGrid(g); gridMenu = false })
            }
        }
        if (clip != null) {
            Chip("×2 Loop", false, vm::duplicateLoop)
            Chip("Quantize", false, { vm.quantizeClip(ui.stepGrid) })
            Chip("◀", false, { vm.nudgeClip(-ui.stepGrid) })
            Chip("▶", false, { vm.nudgeClip(ui.stepGrid) })
            if (ui.track?.kind != TrackKind.DRUMS) {
                Chip("−1", false, { vm.transposeClip(-1) })
                Chip("+1", false, { vm.transposeClip(1) })
                Chip("−Oct", false, { vm.transposeClip(-12) })
                Chip("+Oct", false, { vm.transposeClip(12) })
            }
            Chip("Clear", false, vm::clearClipNotes, color = NM.record)
        }
    }
}

private fun formatBars(beats: Double): String {
    val bars = beats / 4
    return if (bars == floor(bars)) bars.toInt().toString() else bars.toString()
}

/**
 * Drum step sequencer: one row per pad (pad 16 on top, like the pad grid), one column per step.
 * Tap toggles a hit; long-press and drag up/down changes its velocity.
 */
@Composable
fun DrumStepGrid(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier, cell: Dp = 30.dp) {
    val track = ui.track ?: return
    val clip = ui.clip
    val grid = ui.stepGrid
    val length = clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val steps = max(1, kotlin.math.round(length / grid).toInt())
    val kit = track.drumKit
    val color = NM.track(track.color)
    val density = LocalDensity.current
    val cellPx = with(density) { cell.toPx() }
    val labelW = 64.dp
    val notes by rememberUpdatedState(clip?.notes ?: emptyList())
    Row(modifier) {
        Column(Modifier.width(labelW)) {
            for (row in 0 until DRUM_PAD_COUNT) {
                val pad = DRUM_PAD_COUNT - 1 - row
                Box(
                    Modifier.height(cell).fillMaxWidth()
                        .background(if (pad == ui.selectedPad) NM.surfaceHigh else Color.Transparent)
                        .pointerInput(pad) { detectTapGestures { vm.selectPad(pad); vm.auditionPad(pad) } },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(kit?.pads?.getOrNull(pad)?.name ?: "Pad ${pad + 1}", Modifier.padding(start = 4.dp), fontSize = 11.sp,
                        color = if (pad == ui.selectedPad) NM.text else NM.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val hs = rememberScrollState()
        Box(Modifier.horizontalScroll(hs)) {
            val w = cell * steps
            Canvas(
                Modifier.size(w, cell * DRUM_PAD_COUNT)
                    .pointerInput(steps, grid) {
                        detectTapGestures { o ->
                            val step = (o.x / cellPx).toInt()
                            val pad = DRUM_PAD_COUNT - 1 - (o.y / cellPx).toInt()
                            if (step in 0 until steps && pad in 0 until DRUM_PAD_COUNT) {
                                vm.selectPad(pad)
                                vm.toggleStep(DRUM_BASE_NOTE + pad, step * grid, grid)
                            }
                        }
                    }
                    .pointerInput(steps, grid) {
                        var target: Note? = null
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o ->
                                val step = (o.x / cellPx).toInt()
                                val pitch = DRUM_BASE_NOTE + DRUM_PAD_COUNT - 1 - (o.y / cellPx).toInt()
                                target = notes.firstOrNull { it.pitch == pitch && abs(it.start - step * grid) < grid / 2 }
                            },
                            onDrag = { c, d ->
                                c.consume()
                                val t = target ?: return@detectDragGesturesAfterLongPress
                                val nv = (t.velocity - d.y / 3).toInt().coerceIn(1, 127)
                                if (nv != t.velocity) {
                                    val updated = t.copy(velocity = nv)
                                    vm.setNotes(notes.map { if (it == t) updated else it }, undoable = false)
                                    target = updated
                                }
                            },
                        )
                    },
            ) {
                for (r in 0 until DRUM_PAD_COUNT) for (s in 0 until steps) {
                    val beat = s * grid
                    val bg = if (floor(beat / 1.0 + 1e-9).toInt() % 2 == 0) NM.pad else NM.padDim
                    drawRoundRect(bg, Offset(s * cellPx + 1.5f, r * cellPx + 1.5f), Size(cellPx - 3f, cellPx - 3f), CornerRadius(5f))
                }
                for (n in notes) {
                    val pad = n.pitch - DRUM_BASE_NOTE
                    if (pad !in 0 until DRUM_PAD_COUNT) continue
                    val r = DRUM_PAD_COUNT - 1 - pad
                    val s = n.start / grid
                    val alpha = 0.35f + 0.65f * n.velocity / 127f
                    drawRoundRect(color.copy(alpha = alpha), Offset((s * cellPx).toFloat() + 1.5f, r * cellPx + 1.5f), Size(cellPx - 3f, cellPx - 3f), CornerRadius(5f))
                }
                // Bar lines
                var b = 0.0
                while (b <= length + 1e-9) {
                    val x = (b / grid * cellPx).toFloat()
                    drawLine(NM.textDim.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), 2f)
                    b += 4.0
                }
                val head = playheadIn(engine.value, track, ui.selectedScene, clip)
                if (head != null) {
                    val x = (head / grid * cellPx).toFloat()
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3f)
                }
            }
        }
    }
}

/**
 * Piano roll. Rows are pitches (only in-scale rows when "In Key" is on), columns are grid steps.
 * Tap an empty cell to add a note, tap a note to delete it, long-press a note and drag sideways to
 * change its length or up/down to change its velocity.
 */
@Composable
fun NoteEditor(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier, cellW: Dp = 28.dp, cellH: Dp = 26.dp) {
    val project = ui.project ?: return
    val track = ui.track ?: return
    val clip = ui.clip
    val grid = ui.stepGrid
    val length = clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val steps = max(1, kotlin.math.round(length / grid).toInt())
    val inKey = ui.inKey && project.scale.intervals.size < 12
    val rows: List<Int> = remember(project.rootNote, project.scale, inKey) {
        (127 downTo 0).filter { !inKey || project.scale.contains(it, project.rootNote) }
    }
    val color = NM.track(track.color)
    val density = LocalDensity.current
    val cw = with(density) { cellW.toPx() }
    val ch = with(density) { cellH.toPx() }
    val notes by rememberUpdatedState(clip?.notes ?: emptyList())
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    // Start scrolled to the notes in the clip, or around the current octave.
    LaunchedEffect(track.id, ui.selectedScene, rows.size) {
        val focus = clip?.notes?.maxByOrNull { it.pitch }?.pitch ?: ((ui.octave + 3) * 12 + project.rootNote)
        val idx = rows.indexOfFirst { it <= focus }.coerceAtLeast(0)
        vScroll.scrollTo(max(0, ((idx - 3) * ch).toInt()))
    }
    Row(modifier) {
        Column(Modifier.width(44.dp).verticalScroll(vScroll)) {
            for (p in rows) {
                val black = Scale.NOTE_NAMES[p % 12].contains('#')
                val root = (p - project.rootNote) % 12 == 0
                Box(
                    Modifier.height(cellH).fillMaxWidth().background(if (root) color.copy(alpha = 0.35f) else if (black) NM.padDim else NM.surfaceHigh)
                        .pointerInput(p) { detectTapGestures(onPress = { vm.padDown(p, 100); tryAwaitRelease(); vm.padUp(p) }) },
                    contentAlignment = Alignment.CenterStart,
                ) { Text(Scale.noteName(p), Modifier.padding(start = 4.dp), fontSize = 10.sp, color = NM.text) }
            }
        }
        Box(Modifier.verticalScroll(vScroll).horizontalScroll(hScroll)) {
            Canvas(
                Modifier.size(cellW * steps, cellH * rows.size)
                    .pointerInput(steps, grid, rows) {
                        detectTapGestures { o ->
                            val step = (o.x / cw).toInt()
                            val r = (o.y / ch).toInt()
                            if (r !in rows.indices || step !in 0 until steps) return@detectTapGestures
                            val pitch = rows[r]
                            val beat = o.x / cw * grid
                            val hit = notes.firstOrNull { it.pitch == pitch && beat >= it.start - 1e-6 && beat < it.start + max(it.duration, grid * 0.5) }
                            if (hit != null) vm.setNotes(notes - hit)
                            else {
                                if (clip == null) vm.createClip(track.id, ui.selectedScene)
                                vm.setNotes(notes + Note(pitch, step * grid, grid, 100))
                                vm.padDown(pitch, 100); vm.padUp(pitch)
                            }
                        }
                    }
                    .pointerInput(steps, grid, rows) {
                        var target: Note? = null
                        var dx = 0f
                        var dy = 0f
                        var orig: Note? = null
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o ->
                                val r = (o.y / ch).toInt()
                                val pitch = rows.getOrNull(r)
                                val beat = o.x / cw * grid
                                target = notes.firstOrNull { it.pitch == pitch && beat >= it.start - 1e-6 && beat < it.start + max(it.duration, grid * 0.5) }
                                orig = target; dx = 0f; dy = 0f
                            },
                            onDrag = { c, d ->
                                c.consume()
                                val t = target ?: return@detectDragGesturesAfterLongPress
                                val o = orig ?: return@detectDragGesturesAfterLongPress
                                dx += d.x; dy += d.y
                                val newDur = max(grid, ClipOps.snap(o.duration + dx / cw * grid, grid)).coerceAtMost(length - o.start)
                                val newVel = (o.velocity - dy / 3).toInt().coerceIn(1, 127)
                                val updated = t.copy(duration = newDur, velocity = newVel)
                                if (updated != t) {
                                    vm.setNotes(notes.map { if (it == t) updated else it }, undoable = false)
                                    target = updated
                                }
                            },
                        )
                    },
            ) {
                rows.forEachIndexed { r, p ->
                    val black = Scale.NOTE_NAMES[p % 12].contains('#')
                    drawRect(if (black && !inKey) NM.padDim else NM.pad.copy(alpha = 0.55f), Offset(0f, r * ch), Size(size.width, ch - 1f))
                }
                for (s in 0..steps) {
                    val beat = s * grid
                    val strong = abs(beat % 4.0) < 1e-6
                    val medium = abs(beat % 1.0) < 1e-6
                    drawLine(if (strong) NM.textDim else if (medium) NM.line else NM.line.copy(alpha = 0.4f), Offset(s * cw, 0f), Offset(s * cw, size.height), if (strong) 2f else 1f)
                }
                for (n in notes) {
                    val r = rows.indexOf(n.pitch)
                    if (r < 0) continue
                    val x = (n.start / grid * cw).toFloat()
                    val w = max(4f, (n.duration / grid * cw).toFloat() - 2f)
                    drawRoundRect(color.copy(alpha = 0.4f + 0.6f * n.velocity / 127f), Offset(x + 1f, r * ch + 2f), Size(w, ch - 4f), CornerRadius(6f))
                }
                val head = playheadIn(engine.value, track, ui.selectedScene, clip)
                if (head != null) {
                    val x = (head / grid * cw).toFloat()
                    drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3f)
                }
            }
        }
    }
}

/** The clip editor for the selected track: drum step grid or piano roll. */
@Composable
fun ClipEditor(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier, compact: Boolean = false) {
    val track = ui.track ?: return
    Column(modifier) {
        ClipToolbar(vm, ui)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            if (track.kind == TrackKind.DRUMS) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val cell = ((maxHeight - 2.dp) / DRUM_PAD_COUNT).coerceIn(18.dp, if (compact) 30.dp else 40.dp)
                    DrumStepGrid(vm, ui, engine, Modifier.fillMaxSize().verticalScroll(rememberScrollState()), cell = cell)
                }
            } else {
                NoteEditor(vm, ui, engine, Modifier.fillMaxSize(), cellW = if (compact) 26.dp else 32.dp)
            }
        }
        if (ui.clip == null) {
            Text("Empty slot: tap the grid to start a clip, or press Record.", Modifier.padding(4.dp), fontSize = 11.sp, color = NM.textDim, fontWeight = FontWeight.Medium)
        }
    }
}
