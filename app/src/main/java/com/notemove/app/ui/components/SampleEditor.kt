package com.notemove.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notemove.app.ui.StudioUi
import com.notemove.app.ui.StudioViewModel
import com.notemove.app.ui.theme.NM
import com.notemove.core.dsp.SampleOps
import com.notemove.core.dsp.Spectral
import com.notemove.core.dsp.SpectralOp
import com.notemove.core.dsp.SpectralSelection
import com.notemove.core.model.TrackKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private enum class Tool(val label: String) { TIME("Time"), RECT("Rect"), BRUSH("Brush"), PAN("Pan / Zoom") }

/**
 * Spectral sample editor in the spirit of iZotope Iris: see the sample as a spectrogram, select regions
 * in time and frequency with a rectangle or a brush, and erase, attenuate, boost or isolate them.
 * Time-domain tools (trim, delete, reverse, fades, normalise) work on the time selection.
 */
@Composable
fun SampleEditor(vm: StudioViewModel, ui: StudioUi, sampleId: String) {
    val project = ui.project ?: return
    val ref = project.sample(sampleId)
    val source = remember(sampleId) { vm.sampleData(sampleId) }
    if (source == null || ref == null) { LaunchedEffect(Unit) { vm.closeSampleEditor() }; return }
    val sr = source.sampleRate
    val scope = rememberCoroutineScope()

    var data by remember(sampleId) { mutableStateOf(source.data) }
    val undo = remember(sampleId) { ArrayDeque<FloatArray>() }
    var modified by remember { mutableStateOf(false) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf<String?>("Analysing") }
    var progress by remember { mutableFloatStateOf(0f) }
    var tool by remember { mutableStateOf(Tool.RECT) }
    // View range in frames.
    var viewStart by remember(sampleId) { mutableIntStateOf(0) }
    var viewEnd by remember(sampleId) { mutableIntStateOf(source.data.size) }
    // Selections
    var timeSel by remember { mutableStateOf<IntRange?>(null) }
    var rect by remember { mutableStateOf<SpectralSelection.Rect?>(null) }
    val brush = remember { mutableStateListOf<Pair<Float, Float>>() }
    var brushOct by remember { mutableFloatStateOf(0.4f) }
    var amountDb by remember { mutableFloatStateOf(18f) }
    var playhead by remember { mutableIntStateOf(-1) }
    var confirmClose by remember { mutableStateOf(false) }

    val nyquist = sr / 2f
    fun yToHz(y: Float, h: Float) = 20f * (nyquist / 20f).pow(1f - (y / h).coerceIn(0f, 1f))
    fun hzToY(hz: Float, h: Float) = (1f - ln(max(hz, 20f) / 20f) / ln(nyquist / 20f)) * h
    fun xToFrame(x: Float, w: Float) = (viewStart + (x / w).coerceIn(0f, 1f) * (viewEnd - viewStart)).toInt()
    fun frameToX(f: Int, w: Float) = (f - viewStart).toFloat() / max(1, viewEnd - viewStart) * w

    LaunchedEffect(data) {
        busy = "Analysing"
        image = withContext(Dispatchers.Default) { renderSpectrogram(data, sr) }
        busy = null
        if (viewEnd > data.size || viewEnd <= viewStart) { viewStart = 0; viewEnd = data.size }
    }
    LaunchedEffect(Unit) { while (true) withFrameNanos { playhead = vm.engine.previewFrame } }

    fun clearSelection() { timeSel = null; rect = null; brush.clear() }

    fun commit(label: String, op: (FloatArray) -> FloatArray) {
        if (busy != null) return
        busy = label
        scope.launch {
            val before = data
            val result = withContext(Dispatchers.Default) { op(before) }
            if (result.size >= 2) {
                undo.addLast(before); if (undo.size > 20) undo.removeFirst()
                data = result; modified = true
                if (result.size != before.size) { clearSelection(); viewStart = 0; viewEnd = result.size }
            }
            busy = null
        }
    }

    fun spectralSelection(): SpectralSelection? {
        val parts = ArrayList<SpectralSelection>()
        rect?.let(parts::add)
        if (brush.isNotEmpty()) {
            val secPerOct = (viewEnd - viewStart).toFloat() / sr / 10f / 3f
            parts.add(SpectralSelection.Brush(brush.toList(), brushOct, secPerOct))
        }
        if (parts.isEmpty()) timeSel?.let { parts.add(SpectralSelection.Rect(it.first.toFloat() / sr, it.last.toFloat() / sr, 20f, nyquist)) }
        return when (parts.size) { 0 -> null; 1 -> parts[0]; else -> SpectralSelection.Union(parts) }
    }

    fun timeRange(): IntRange? = timeSel ?: rect?.let { (min(it.t0, it.t1) * sr).toInt()..(max(it.t0, it.t1) * sr).toInt() }

    fun spectral(op: SpectralOp) {
        val sel = spectralSelection() ?: return vm.toast("Select a region first (Rect, Brush or Time)")
        commit(op.label) { d -> Spectral.apply(d, sr, sel, op, if (op == SpectralOp.BOOST) amountDb else -amountDb) { progress = it } }
    }

    fun time(label: String, op: (FloatArray, Int, Int) -> FloatArray) {
        val r = timeRange()
        val a = r?.first ?: 0
        val b = (r?.last ?: (data.size - 1)) + 1
        commit(label) { d -> op(d, a.coerceIn(0, d.size), b.coerceIn(0, d.size)) }
    }

    Dialog(onDismissRequest = { if (modified) confirmClose = true else vm.closeSampleEditor() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(NM.bg).safeDrawingPadding().padding(8.dp)) {
            // Top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (modified) confirmClose = true else vm.closeSampleEditor() }) { Icon(Icons.Filled.Close, "Close", tint = NM.text) }
                Column(Modifier.weight(1f)) {
                    Text(ref.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
                    Text(String.format(Locale.ROOT, "%.2f s · %d Hz%s", data.size.toFloat() / sr, sr, if (modified) " · edited" else ""), fontSize = 11.sp, color = NM.textDim)
                }
                IconButton({ undo.removeLastOrNull()?.let { data = it; modified = undo.isNotEmpty() } }, enabled = undo.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = if (undo.isNotEmpty()) NM.text else NM.line)
                }
                IconButton({
                    if (playhead >= 0) vm.engine.stopPreview() else {
                        val r = timeRange()
                        vm.engine.previewSample(data, sr, r?.first ?: viewStart, (r?.last ?: viewEnd))
                    }
                }) { Icon(if (playhead >= 0) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Preview", tint = NM.play) }
                TextButton({ if (modified) vm.toast("Save your edits first, then slice") else vm.openSlicer(sampleId) }) { Text("Slice…") }
                TextButton({ vm.saveSampleAsNew("${ref.name} edit", SampleOps.declick(data, sr)); vm.closeSampleEditor() }, enabled = modified) { Text("Save as new") }
                TextButton({ vm.replaceSample(sampleId, SampleOps.declick(data, sr)); vm.closeSampleEditor() }, enabled = modified) { Text("Save") }
            }
            // Tools
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (t in Tool.entries) Chip(t.label, tool == t, { tool = t })
                Chip("Zoom +", false, {
                    val c = (viewStart + viewEnd) / 2; val half = max(256, (viewEnd - viewStart) / 4)
                    viewStart = max(0, c - half); viewEnd = min(data.size, c + half)
                })
                Chip("Zoom −", false, {
                    val c = (viewStart + viewEnd) / 2; val half = (viewEnd - viewStart)
                    viewStart = max(0, c - half); viewEnd = min(data.size, c + half)
                })
                Chip("Fit", false, { viewStart = 0; viewEnd = data.size })
                Chip("Clear sel.", false, { clearSelection() })
                if (tool == Tool.BRUSH) {
                    Text("Brush", fontSize = 11.sp, color = NM.textDim)
                    Slider(brushOct, { brushOct = it }, valueRange = 0.1f..1.5f, modifier = Modifier.width(120.dp))
                }
            }
            busy?.let {
                Text(it, fontSize = 11.sp, color = NM.textDim)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            // Spectrogram
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(tool, viewStart, viewEnd) {
                            if (tool == Tool.PAN) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val w = size.width.toFloat()
                                    val span = (viewEnd - viewStart).toFloat()
                                    val newSpan = (span / zoom).coerceIn(256f, data.size.toFloat())
                                    val anchor = viewStart + centroid.x / w * span
                                    var s = anchor - centroid.x / w * newSpan - pan.x / w * newSpan
                                    s = s.coerceIn(0f, data.size - newSpan)
                                    viewStart = s.toInt(); viewEnd = (s + newSpan).toInt().coerceAtMost(data.size)
                                }
                            } else {
                                var startX = 0f; var startY = 0f
                                detectDragGestures(
                                    onDragStart = { o ->
                                        startX = o.x; startY = o.y
                                        val w = size.width.toFloat(); val h = size.height.toFloat()
                                        when (tool) {
                                            Tool.TIME -> { val f = xToFrame(o.x, w); timeSel = f..f }
                                            Tool.RECT -> { val t = xToFrame(o.x, w).toFloat() / sr; val hz = yToHz(o.y, h); rect = SpectralSelection.Rect(t, t, hz, hz) }
                                            Tool.BRUSH -> brush.add(xToFrame(o.x, w).toFloat() / sr to yToHz(o.y, h))
                                            Tool.PAN -> Unit
                                        }
                                    },
                                ) { c, _ ->
                                    c.consume()
                                    val w = size.width.toFloat(); val h = size.height.toFloat()
                                    val p = c.position
                                    when (tool) {
                                        Tool.TIME -> { val a = xToFrame(startX, w); val b = xToFrame(p.x, w); timeSel = min(a, b)..max(a, b) }
                                        Tool.RECT -> rect = SpectralSelection.Rect(
                                            xToFrame(startX, w).toFloat() / sr, xToFrame(p.x, w).toFloat() / sr, yToHz(startY, h), yToHz(p.y, h),
                                        )
                                        Tool.BRUSH -> {
                                            val pt = xToFrame(p.x, w).toFloat() / sr to yToHz(p.y, h)
                                            val last = brush.lastOrNull()
                                            if (last == null || abs(last.first - pt.first) > 0.004f || abs(ln(last.second / pt.second)) > 0.03f) brush.add(pt)
                                        }
                                        Tool.PAN -> Unit
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { viewStart = 0; viewEnd = data.size }) },
                ) {
                    val w = size.width; val h = size.height
                    drawRect(Color.Black)
                    image?.let { img ->
                        val x0 = (viewStart.toFloat() / data.size * img.width).toInt().coerceIn(0, img.width - 1)
                        val x1 = (viewEnd.toFloat() / data.size * img.width).toInt().coerceIn(x0 + 1, img.width)
                        drawImage(img, IntOffset(x0, 0), IntSize(x1 - x0, img.height), dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.Low)
                    }
                    // frequency grid
                    for (hz in listOf(100f, 1000f, 10000f)) {
                        if (hz >= nyquist) continue
                        val y = hzToY(hz, h)
                        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y), Offset(w, y), 1f)
                    }
                    timeSel?.let { r ->
                        val a = frameToX(r.first, w); val b = frameToX(r.last, w)
                        drawRect(Color.White.copy(alpha = 0.12f), Offset(a, 0f), Size(max(1f, b - a), h))
                    }
                    rect?.let { rc ->
                        val a = frameToX((min(rc.t0, rc.t1) * sr).toInt(), w); val b = frameToX((max(rc.t0, rc.t1) * sr).toInt(), w)
                        val top = hzToY(max(rc.f0, rc.f1), h); val bot = hzToY(min(rc.f0, rc.f1), h)
                        drawRect(NM.accent.copy(alpha = 0.18f), Offset(a, top), Size(b - a, bot - top))
                        drawRect(NM.accent, Offset(a, top), Size(b - a, bot - top), style = Stroke(2f))
                    }
                    if (brush.isNotEmpty()) {
                        val pxPerOct = h / (ln(nyquist / 20f) / ln(2f))
                        val r = brushOct * pxPerOct
                        for ((t, hz) in brush) drawCircle(NM.solo.copy(alpha = 0.25f), r, Offset(frameToX((t * sr).toInt(), w), hzToY(hz, h)))
                    }
                    if (playhead in viewStart..viewEnd) {
                        val x = frameToX(playhead, w)
                        drawLine(Color.White, Offset(x, 0f), Offset(x, h), 2f)
                    }
                }
                Column(Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Text("${(nyquist / 1000).toInt()} kHz", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
                }
                Text("20 Hz", Modifier.align(Alignment.BottomStart).padding(4.dp), fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
            }
            // Waveform strip (always does time selection)
            Canvas(
                Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp).background(NM.surface)
                    .pointerInput(viewStart, viewEnd) {
                        var sx = 0f
                        detectDragGestures(onDragStart = { o -> sx = o.x; val f = xToFrame(o.x, size.width.toFloat()); timeSel = f..f }) { c, _ ->
                            c.consume()
                            val a = xToFrame(sx, size.width.toFloat()); val b = xToFrame(c.position.x, size.width.toFloat())
                            timeSel = min(a, b)..max(a, b)
                        }
                    },
            ) {
                val w = size.width; val h = size.height
                val cols = w.toInt().coerceAtLeast(1)
                val peaks = SampleOps.peaks(data, cols, viewStart, viewEnd)
                for (i in 0 until cols) {
                    val a = peaks[i] * h / 2
                    drawLine(NM.accent.copy(alpha = 0.8f), Offset(i.toFloat(), h / 2 - a), Offset(i.toFloat(), h / 2 + a), 1f)
                }
                timeSel?.let { r ->
                    val a = frameToX(r.first, w); val b = frameToX(r.last, w)
                    drawRect(Color.White.copy(alpha = 0.18f), Offset(a, 0f), Size(max(1f, b - a), h))
                }
                if (playhead in viewStart..viewEnd) { val x = frameToX(playhead, w); drawLine(Color.White, Offset(x, 0f), Offset(x, h), 2f) }
            }
            // Actions
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Amount ${amountDb.toInt()} dB", fontSize = 11.sp, color = NM.textDim, modifier = Modifier.width(96.dp))
                Slider(amountDb, { amountDb = it }, valueRange = 3f..48f, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Attenuate", false, { spectral(SpectralOp.ATTENUATE) })
                Chip("Erase", false, { spectral(SpectralOp.ERASE) }, color = NM.record)
                Chip("Boost", false, { spectral(SpectralOp.BOOST) })
                Chip("Keep only", false, { spectral(SpectralOp.KEEP) })
                Spacer(Modifier.width(8.dp))
                Chip("Trim", false, { time("Trim") { d, a, b -> if (b - a > 32) SampleOps.trim(d, a, b) else d } })
                Chip("Delete", false, { time("Delete") { d, a, b -> if (b - a < d.size - 32) SampleOps.delete(d, a, b) else d } })
                Chip("Silence", false, { time("Silence") { d, a, b -> SampleOps.silence(d, a, b) } })
                Chip("Reverse", false, { time("Reverse") { d, a, b -> SampleOps.reverse(d, a, b) } })
                Chip("Normalize", false, { time("Normalize") { d, a, b -> SampleOps.normalize(d, 0.89f, a, b) } })
                Chip("Fade in", false, { time("Fade in") { d, a, b -> SampleOps.fade(d, a, b, true) } })
                Chip("Fade out", false, { time("Fade out") { d, a, b -> SampleOps.fade(d, a, b, false) } })
                Chip("−3 dB", false, { time("Gain") { d, a, b -> SampleOps.gain(d, -3f, a, b) } })
                Chip("+3 dB", false, { time("Gain") { d, a, b -> SampleOps.gain(d, 3f, a, b) } })
                if (ui.track?.kind == TrackKind.SAMPLER && ui.track?.sampler?.sampleId == sampleId) {
                    Chip("Set as play region", false, {
                        val r = timeRange() ?: return@Chip vm.toast("Make a time selection first")
                        vm.setSamplerRegion(r.first.toFloat() / data.size, (r.last + 1).toFloat() / data.size)
                    }, color = NM.play)
                }
            }
        }
    }

    if (confirmClose) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClose = false },
            confirmButton = { TextButton({ confirmClose = false; vm.replaceSample(sampleId, SampleOps.declick(data, sr)); vm.closeSampleEditor() }) { Text("Save") } },
            dismissButton = { TextButton({ confirmClose = false; vm.closeSampleEditor() }) { Text("Discard", color = NM.record) } },
            title = { Text("Save your edits?") },
            containerColor = NM.surface,
        )
    }
}

/** Spectrogram as a bitmap: x = time, y = log frequency (20 Hz at the bottom), Iris-style heat colours. */
private fun renderSpectrogram(data: FloatArray, sr: Int): ImageBitmap {
    val fft = if (data.size > sr * 20) 1024 else 2048
    val sg = Spectral.spectrogram(data, sr, fft, fft / 4)
    val h = 256
    val step = max(1, sg.frames / 3000)
    val w = max(1, sg.frames / step)
    val nyquist = sr / 2f
    val rowBin = IntArray(h) { y ->
        val hz = 20f * (nyquist / 20f).pow(1f - y.toFloat() / (h - 1))
        (hz / nyquist * (sg.bins - 1)).toInt().coerceIn(0, sg.bins - 1)
    }
    val palette = IntArray(256) { heat(it / 255f) }
    val px = IntArray(w * h)
    for (x in 0 until w) {
        val f = x * step
        for (y in 0 until h) {
            // Max over the bins this row covers so high-frequency detail isn't lost.
            val b0 = rowBin[y]; val b1 = if (y > 0) rowBin[y - 1] else sg.bins - 1
            var m = 0
            for (b in b0..max(b0, b1)) m = max(m, sg.level(f, b))
            px[y * w + x] = palette[m]
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private fun heat(t: Float): Int {
    // black → deep blue → purple → red → orange → yellow → white
    val stops = floatArrayOf(0f, 0.25f, 0.45f, 0.65f, 0.8f, 0.92f, 1f)
    val cols = intArrayOf(0x000000, 0x0B0B3B, 0x5A1A8C, 0xC4234B, 0xF0752A, 0xF7D046, 0xFFFFFF)
    var i = 0
    while (i < stops.size - 2 && t > stops[i + 1]) i++
    val k = ((t - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
    fun ch(c: Int, s: Int) = (c shr s) and 0xFF
    val r = (ch(cols[i], 16) + (ch(cols[i + 1], 16) - ch(cols[i], 16)) * k).toInt()
    val g = (ch(cols[i], 8) + (ch(cols[i + 1], 8) - ch(cols[i], 8)) * k).toInt()
    val b = (ch(cols[i], 0) + (ch(cols[i + 1], 0) - ch(cols[i], 0)) * k).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
