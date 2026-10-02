package com.notemove.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.dsp.SampleOps
import com.notemove.core.dsp.Slicer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * Slice a sample across the drum pads, like Move's / Simpler's slicing: slice points come from
 * transients, the beat grid or equal divisions, and can be added, moved and removed by hand.
 * The result is a drum track with one slice per pad, plus (optionally) a clip that replays the loop.
 */
@Composable
fun SliceEditor(vm: StudioViewModel, ui: StudioUi, sampleId: String) {
    val project = ui.project ?: return
    val ref = project.sample(sampleId)
    val sample = remember(sampleId) { vm.sampleData(sampleId) }
    if (sample == null || ref == null) { LaunchedEffect(Unit) { vm.closeSlicer() }; return }
    val data = sample.data
    val sr = sample.sampleRate
    val frames = data.size

    var mode by remember { mutableStateOf(Slicer.Mode.TRANSIENTS) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var equalCount by remember { mutableIntStateOf(8) }
    var division by remember { mutableDoubleStateOf(0.5) }
    var loopBeats by remember(sampleId) { mutableDoubleStateOf(Slicer.guessLoopBeats(frames, sr, project.tempo)) }
    var points by remember(sampleId) { mutableStateOf(intArrayOf(0)) }
    var makeClip by remember { mutableStateOf(true) }
    var choke by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var playhead by remember { mutableIntStateOf(-1) }
    var current by remember { mutableIntStateOf(-1) }
    var canvasW by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(mode, sensitivity, equalCount, division, loopBeats) {
        when (mode) {
            Slicer.Mode.TRANSIENTS -> {
                busy = true
                try { points = withContext(Dispatchers.Default) { Slicer.transients(data, sr, sensitivity) } } finally { busy = false }
            }
            Slicer.Mode.BEATS -> { busy = false; points = Slicer.beats(frames, loopBeats, division) }
            Slicer.Mode.EQUAL -> { busy = false; points = Slicer.equal(frames, equalCount) }
            Slicer.Mode.MANUAL -> busy = false
        }
    }
    LaunchedEffect(Unit) { while (true) withFrameNanos { playhead = vm.engine.previewFrame } }

    fun audition(i: Int, p: IntArray = points) {
        if (i !in p.indices) return
        current = i
        vm.engine.previewSample(data, sr, p[i], if (i + 1 < p.size) p[i + 1] else frames)
    }
    fun sliceAt(frame: Int): Int = points.indexOfLast { it <= frame }.coerceAtLeast(0)
    fun setManual(newPoints: List<Int>): IntArray {
        mode = Slicer.Mode.MANUAL
        return Slicer.normalize(newPoints, frames, sr).also { points = it }
    }

    val markerHitPx = with(LocalDensity.current) { 22.dp.toPx() }
    val seconds = frames.toDouble() / sr
    val loopBpm = loopBeats * 60.0 / seconds

    Dialog(onDismissRequest = vm::closeSlicer, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(NM.bg).safeDrawingPadding().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(vm::closeSlicer) { Icon(Icons.Filled.Close, "Close", tint = NM.text) }
                Column(Modifier.weight(1f)) {
                    Text("Slice · ${ref.name}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
                    Text(
                        String.format(Locale.ROOT, "%.2f s · %d slices%s", seconds, points.size, if (busy) " · finding transients…" else ""),
                        fontSize = 11.sp, color = NM.textDim,
                    )
                }
                IconButton({ if (playhead >= 0) vm.engine.stopPreview() else { current = -1; vm.engine.previewSample(data, sr) } }) {
                    Icon(if (playhead >= 0) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Play whole sample", tint = NM.play)
                }
            }
            // Mode
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (m in Slicer.Mode.entries) Chip(m.label, mode == m, { mode = m })
            }
            Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (mode) {
                    Slicer.Mode.TRANSIENTS -> {
                        Text("Sensitivity ${(sensitivity * 100).toInt()}%", fontSize = 12.sp, color = NM.textDim)
                        Slider(sensitivity, { sensitivity = it }, Modifier.width(220.dp))
                    }
                    Slicer.Mode.BEATS -> for ((d, label) in listOf(4.0 to "1 bar", 2.0 to "1/2", 1.0 to "1/4", 0.5 to "1/8", 0.25 to "1/16")) {
                        Chip(label, abs(division - d) < 1e-9, { division = d })
                    }
                    Slicer.Mode.EQUAL -> for (n in listOf(2, 4, 6, 8, 12, 16)) Chip("$n", equalCount == n, { equalCount = n })
                    Slicer.Mode.MANUAL -> {
                        Text("Tap to add a slice · drag a marker to move it · hold or right-click a marker to remove it", fontSize = 12.sp, color = NM.textDim)
                        Chip("Clear", false, { setManual(listOf(0)) })
                    }
                }
            }
            // Waveform with slice markers
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp)).background(NM.surface)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .onSizeChanged { canvasW = it.width.toFloat().coerceAtLeast(1f) }
                        .onSecondaryClick { o ->
                            val f = (o.x / canvasW * frames).toInt()
                            nearestMarker(points, f, (markerHitPx / canvasW * frames).toInt())?.takeIf { it > 0 }
                                ?.let { i -> setManual(points.toList().filterIndexed { k, _ -> k != i }) }
                        }
                        .pointerInput(frames) {
                            detectTapGestures(
                                onTap = { o ->
                                    val f = (o.x / size.width * frames).toInt().coerceIn(0, frames - 1)
                                    if (mode == Slicer.Mode.MANUAL) {
                                        if (points.size >= Slicer.MAX_SLICES) { vm.toast("16 slices max — one per pad"); return@detectTapGestures }
                                        val snapped = Slicer.snapToZero(data, f, sr)
                                        val np = setManual(points.toList() + snapped)
                                        audition(np.indexOfLast { it <= snapped }.coerceAtLeast(0), np)
                                    } else audition(sliceAt(f))
                                },
                                onLongPress = { o ->
                                    val f = (o.x / size.width * frames).toInt()
                                    val i = nearestMarker(points, f, (markerHitPx / size.width * frames).toInt())
                                    if (i != null && i > 0) setManual(points.toList().filterIndexed { k, _ -> k != i })
                                    else audition(sliceAt(f))
                                },
                            )
                        }
                        .pointerInput(frames) {
                            var dragging = -1
                            detectDragGestures(
                                onDragStart = { o ->
                                    val f = (o.x / size.width * frames).toInt()
                                    dragging = nearestMarker(points, f, (markerHitPx / size.width * frames).toInt()) ?: -1
                                    if (dragging == 0) dragging = -1 // the first slice always starts at the beginning
                                },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 },
                            ) { c, _ ->
                                if (dragging < 0) return@detectDragGestures
                                c.consume()
                                val p = points
                                val lo = p[dragging - 1] + sr / 100
                                val hi = if (dragging + 1 < p.size) p[dragging + 1] - sr / 100 else frames - sr / 100
                                if (hi <= lo) return@detectDragGestures
                                val f = (c.position.x / size.width * frames).toInt().coerceIn(lo, hi)
                                val next = p.copyOf().also { it[dragging] = f }
                                mode = Slicer.Mode.MANUAL
                                points = next
                            }
                        },
                ) {
                    val w = size.width; val h = size.height
                    val p = points
                    // Alternate shading per slice, highlight the one playing.
                    for (i in p.indices) {
                        val a = p[i].toFloat() / frames * w
                        val b = (if (i + 1 < p.size) p[i + 1] else frames).toFloat() / frames * w
                        val col = when {
                            i == current && playhead >= 0 -> NM.accent.copy(alpha = 0.28f)
                            i % 2 == 0 -> NM.pad.copy(alpha = 0.6f)
                            else -> NM.padDim.copy(alpha = 0.6f)
                        }
                        drawRect(col, Offset(a, 0f), Size(b - a, h))
                    }
                    val cols = w.toInt().coerceAtLeast(1)
                    val peaks = SampleOps.peaks(data, cols, 0, frames)
                    for (x in 0 until cols) {
                        val amp = peaks[x] * h * 0.45f
                        drawLine(NM.accent.copy(alpha = 0.85f), Offset(x.toFloat(), h / 2 - amp), Offset(x.toFloat(), h / 2 + amp), 1f)
                    }
                    for (i in p.indices) {
                        val x = p[i].toFloat() / frames * w
                        drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, h), 2f)
                        val t = 9.dp.toPx()
                        drawPath(Path().apply { moveTo(x, 0f); lineTo(x + t, 0f); lineTo(x, t * 1.3f); close() }, Color.White)
                    }
                    if (playhead in 0 until frames) {
                        val x = playhead.toFloat() / frames * w
                        drawLine(NM.play, Offset(x, 0f), Offset(x, h), 2f)
                    }
                }
                // Slice numbers
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        val p = points
                        for (i in p.indices) {
                            val frac = p[i].toFloat() / frames
                            Text("${i + 1}", Modifier.fillMaxWidth().padding(start = 4.dp).offsetFraction(frac), fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
            // Slice pads (tap to audition)
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (i in 0 until Slicer.MAX_SLICES) {
                    val has = i < points.size
                    Box(
                        Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(5.dp))
                            .background(if (i == current && playhead >= 0) NM.accent else if (has) NM.surfaceHigh else NM.padDim)
                            .then(if (has) Modifier.clickable { audition(i) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", fontSize = 10.sp, color = if (has) NM.text else NM.line) }
                }
            }
            // Loop length (for Beats mode and the clip)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Loop", fontSize = 12.sp, color = NM.textDim)
                for (beats in listOf(2.0, 4.0, 8.0, 16.0, 32.0)) {
                    Chip(if (beats < 4) "½ bar" else "${(beats / 4).toInt()} bar${if (beats > 4) "s" else ""}", abs(loopBeats - beats) < 1e-9, { loopBeats = beats })
                }
                Text(String.format(Locale.ROOT, "≈ %.1f BPM", loopBpm), fontSize = 12.sp, color = NM.textDim)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(makeClip, { makeClip = it })
                Text("Make a clip that plays the slices in order (follows the set's tempo)", fontSize = 12.sp, color = NM.text, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(choke, { choke = it })
                Text("Slices cut each other off (mono, like a loop)", fontSize = 12.sp, color = NM.text, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button({ vm.sliceToDrumRack(sampleId, points, loopBeats, makeClip, choke) }, enabled = !busy) {
                    Text("Slice to Drum Rack · ${points.size} pads")
                }
            }
        }
    }
}

/** Index of the slice point within [tolerance] frames of [frame], if any. */
private fun nearestMarker(points: IntArray, frame: Int, tolerance: Int): Int? {
    var best = -1
    var bestD = Int.MAX_VALUE
    for (i in points.indices) {
        val d = abs(points[i] - frame)
        if (d < bestD) { bestD = d; best = i }
    }
    return if (best >= 0 && bestD <= tolerance) best else null
}

/** Shifts content right by a fraction of the available width. */
private fun Modifier.offsetFraction(frac: Float): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        layout(constraints.maxWidth, placeable.height) {
            placeable.place((constraints.maxWidth * frac).toInt(), 0)
        }
    },
)
