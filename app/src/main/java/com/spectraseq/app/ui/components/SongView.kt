package com.spectraseq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.engine.EngineState
import com.spectraseq.core.model.Arrangement
import com.spectraseq.core.model.ArrangementOps
import com.spectraseq.core.model.SongClip
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The song timeline (Live's Arrangement View): one lane per track, clips as tiles.
 *
 * - Record: arm "Rec" and launch clips / scenes in the session; what plays is written here.
 * - Tap a clip to select it, hold to add more, hold and drag to move (also to other lanes),
 *   drag a clip's edge to make it shorter or longer (longer loops it).
 * - Tap the ruler to place the cursor (playback start, split point), drag on it to set the loop.
 * - Drag empty space to scroll, pinch to zoom.
 */
@Composable
fun SongView(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier) {
    val project = ui.project ?: return
    val arr = project.arrangement
    val tracks = project.tracks
    val splits by vm.splits.collectAsState()
    val vp = remember { ViewportState(zoomX = splits["song_zoom_x"] ?: 1f, zoomY = splits["song_zoom_y"] ?: 1f, minZoomX = 0.08f, maxZoomX = 8f, minZoomY = 0.6f, maxZoomY = 2.5f) }
    LaunchedEffect(vp) {
        snapshotFlow { vp.zoomX to vp.zoomY }.collectLatest { (zx, zy) -> delay(600); vm.setSplit("song_zoom_x", zx); vm.setSplit("song_zoom_y", zy) }
    }
    val playing by remember(engine) { derivedStateOf { engine.value.playing } }
    val songMode by remember(engine) { derivedStateOf { engine.value.songMode } }
    val overrides by remember(engine) { derivedStateOf { engine.value.songOverrides } }

    Column(modifier) {
        SongToolbar(vm, ui, arr, playing && songMode, overrides.isNotEmpty())
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            // Lane headers follow the timeline's vertical scroll and zoom.
            val laneH = 56.dp
            Column(Modifier.width(76.dp).fillMaxHeight()) {
                Box(Modifier.height(28.dp))
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().viewportPan(vp, panX = false, requireUnconsumed = false)) {
                    Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, -vp.scrollY.roundToInt()) }) {
                        for (t in tracks) {
                            val c = NM.track(t.color)
                            Row(
                                Modifier.height(laneH * vp.zoomY).fillMaxWidth().padding(vertical = 2.dp)
                                    .background(if (t.id == ui.track?.id) NM.surfaceHigh else NM.surface)
                                    .clickable { vm.selectTrack(t.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.width(4.dp).fillMaxHeight().background(c))
                                Text(t.name, Modifier.padding(start = 6.dp), fontSize = 11.sp, color = if (t.id in overrides) NM.queued else NM.text,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            Timeline(vm, ui, engine, vp, laneH, Modifier.weight(1f).fillMaxHeight())
        }
        Text(
            if (arr.isEmpty) "Empty song. Arm Rec, then launch clips or scenes in Session — or use \"Scenes → song\"."
            else "Tap a clip to select · hold to add · hold & drag to move · drag edges to resize · tap the ruler for the cursor, drag it for the loop · pinch to zoom",
            Modifier.padding(4.dp), fontSize = 11.sp, color = NM.textDim,
        )
    }
}

@Composable
private fun SongToolbar(vm: StudioViewModel, ui: StudioUi, arr: Arrangement, songPlaying: Boolean, hasOverrides: Boolean) {
    val sel = ui.songSelection
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(if (songPlaying) "■ Stop" else "▶ Play song", songPlaying, vm::toggleSongPlay, color = NM.play)
        Chip("● Rec", ui.songRecordArmed, vm::toggleSongRecord, color = NM.record)
        Chip("⟲ Loop", arr.loop, vm::toggleSongLoop)
        if (hasOverrides) Chip("Back to song", true, vm::backToSong, color = NM.queued)
        Text(beatLabel(ui.songCursor), fontSize = 12.sp, color = NM.textDim, modifier = Modifier.padding(horizontal = 4.dp))
        if (sel.isNotEmpty()) {
            Chip("✕ ${sel.size}", true, vm::clearSongSelection)
            if (sel.size == 1) Chip("Edit notes", false, { vm.editSongClip(sel.first()) })
            Chip("Duplicate", false, vm::duplicateSongSelection)
            Chip("Delete", false, vm::deleteSongSelection, color = NM.record)
        }
        Chip("Split at cursor", false, vm::splitSongAtCursor)
        Chip("Scenes → song", false, vm::scenesToSong)
    }
}

private fun beatLabel(beat: Double): String {
    val bar = floor(beat / 4).toInt() + 1
    val b = floor(beat % 4).toInt() + 1
    val sixteenth = floor((beat % 1) * 4).toInt() + 1
    return "$bar.$b.$sixteenth"
}

@Composable
private fun Timeline(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, vp: ViewportState, laneHdp: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val project = ui.project ?: return
    val arr = project.arrangement
    val tracks = project.tracks
    val density = LocalDensity.current
    val beatBase = with(density) { 24.dp.toPx() }
    val laneBase = with(density) { laneHdp.toPx() }
    val handlePx = with(density) { 14.dp.toPx() }
    val rulerH = with(density) { 28.dp.toPx() }
    val songLen = max(max(arr.length, ui.songCursor), if (arr.loop) arr.loopEnd else 0.0) + 32.0
    SideEffect { vp.baseW = (songLen * beatBase).toFloat(); vp.baseH = laneBase * tracks.size }
    val bw = beatBase * vp.zoomX // px per beat
    val lh = laneBase * vp.zoomY
    val arrNow by rememberUpdatedState(arr)
    val selection by rememberUpdatedState(ui.songSelection)
    val measurer = rememberTextMeasurer()
    var marquee by remember { mutableStateOf<Rect?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** Snap step in beats for the current zoom: 1/4 beat when zoomed in, a bar when far out. */
    fun snap(): Double = when { bw >= 96f -> 0.25; bw >= 18f -> 1.0; else -> 4.0 }
    fun snapBeats(b: Double): Double { val s = snap(); return Math.round(b / s) * s }
    fun laneOf(y: Float) = (y / lh).toInt()
    fun clipAt(o: Offset): SongClip? {
        val t = tracks.getOrNull(laneOf(o.y)) ?: return null
        val beat = o.x / bw
        return arrNow.clips.firstOrNull { it.trackId == t.id && beat >= it.start && beat < it.end }
    }
    /** +1 right edge, -1 left edge of a clip under [o]. */
    fun edgeAt(o: Offset): Pair<SongClip, Boolean>? {
        val t = tracks.getOrNull(laneOf(o.y)) ?: return null
        for (c in arrNow.clips.filter { it.trackId == t.id }.sortedByDescending { it.id in selection }) {
            val l = (c.start * bw).toFloat(); val r = (c.end * bw).toFloat()
            val inside = min(handlePx, (r - l) / 3)
            if (o.x in (r - inside)..(r + handlePx / 2)) return c to true
            if (o.x in (l - handlePx / 2)..(l + inside)) return c to false
        }
        return null
    }

    Column(modifier) {
        // Ruler: tap = cursor, drag = loop region.
        Canvas(
            Modifier.fillMaxWidth().height(28.dp).background(NM.surface)
                .pointerInput(bw) {
                    detectTapGestures { o -> vm.setSongCursor(snapBeats((o.x + vp.scrollX) / bw.toDouble())) }
                }
                .pointerInput(bw) {
                    var start = 0.0
                    detectDragGestures(onDragStart = { o -> start = snapBeats((o.x + vp.scrollX) / bw.toDouble()) }) { c, _ ->
                        c.consume()
                        val now = snapBeats((c.position.x + vp.scrollX) / bw.toDouble())
                        if (abs(now - start) >= ArrangementOps.MIN_LENGTH) vm.setSongLoop(start, now)
                    }
                },
        ) {
            val sx = vp.scrollX
            translate(-sx, 0f) {
                if (arr.loop || arr.loopEnd > arr.loopStart) {
                    val a = (arr.loopStart * bw).toFloat(); val b = (arr.loopEnd * bw).toFloat()
                    drawRect((if (arr.loop) NM.accent else NM.textDim).copy(alpha = if (arr.loop) 0.45f else 0.15f), Offset(a, 0f), Size(b - a, size.height * 0.4f))
                }
                val barEvery = when { bw * 4 >= 40f -> 1; bw * 4 >= 12f -> 4; else -> 16 }
                val firstBar = floor(sx / (bw * 4)).toInt()
                val lastBar = ((sx + size.width) / (bw * 4)).toInt() + 1
                for (bar in firstBar..lastBar) {
                    if (bar < 0 || bar % barEvery != 0) continue
                    val x = bar * 4 * bw
                    drawLine(NM.textDim, Offset(x, size.height * 0.45f), Offset(x, size.height), 1.5f)
                    val label = measurer.measure("${bar + 1}", TextStyle(fontSize = 10.sp, color = NM.textDim))
                    drawText(label, topLeft = Offset(x + 3f, size.height - label.size.height - 1f))
                }
                val cx = (ui.songCursor * bw).toFloat()
                drawLine(NM.text, Offset(cx, 0f), Offset(cx, size.height), 2f)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { vp.viewW = it.width.toFloat(); vp.viewH = it.height.toFloat(); vp.scrollTo() }) {
            Canvas(
                Modifier.fillMaxSize()
                    .onWheelAt { d, mods, pos ->
                        when {
                            mods.isCommand -> vp.zoomBy(if (d.y < 0) 1.15f else 1f / 1.15f, 1f, pos)
                            mods.isShiftPressed -> vp.scrollBy(d.y * 120f, 0f)
                            else -> vp.scrollBy(d.x * 120f, d.y * 120f)
                        }
                    }
                    // Tap: select a clip (or clear and move the cursor on empty space).
                    .pointerInput(bw, lh, tracks) {
                        detectTapGestures { o0 ->
                            val o = vp.toContent(o0)
                            val c = clipAt(o)
                            if (c != null) {
                                vm.selectSongClip(c.id, additive = false)
                                tracks.firstOrNull { it.id == c.trackId }?.let { vm.selectTrack(it.id) }
                            } else {
                                vm.clearSongSelection()
                                vm.setSongCursor(snapBeats(o.x / bw.toDouble()))
                            }
                        }
                    }
                    // Hold: add to the selection; keep holding and drag to move (empty space: box-select).
                    .pointerInput(bw, lh, tracks) {
                        var mode = 0 // 1 move, 2 marquee
                        var origin = arrNow
                        var ids: Set<String> = emptySet()
                        var acc = Offset.Zero
                        var start = Offset.Zero
                        detectDragGesturesAfterLongPress(
                            onDragStart = { o0 ->
                                if (busy) { mode = 0; return@detectDragGesturesAfterLongPress }
                                val o = vp.toContent(o0)
                                start = o; acc = Offset.Zero
                                val c = clipAt(o)
                                if (c != null) {
                                    ids = if (c.id in selection) selection else selection + c.id
                                    vm.setSongSelection(ids)
                                    vm.checkpoint()
                                    origin = arrNow
                                    mode = 1
                                } else { mode = 2; marquee = Rect(o, o) }
                            },
                            onDragEnd = {
                                if (mode == 2) marquee?.let { m -> selectInRect(vm, ui, tracks, m, bw, lh, selection) }
                                if (mode != 0) marquee = null
                                mode = 0
                            },
                            onDragCancel = { if (mode != 0) marquee = null; mode = 0 },
                        ) { ch, d ->
                            if (mode == 0) return@detectDragGesturesAfterLongPress
                            ch.consume()
                            acc += d
                            if (mode == 1) vm.moveSongClips(ids, origin, snapBeats(acc.x / bw.toDouble()), (acc.y / lh).roundToInt())
                            else {
                                val p = start + acc
                                marquee = Rect(min(start.x, p.x), min(start.y, p.y), max(start.x, p.x), max(start.y, p.y))
                            }
                        }
                    }
                    .viewportPan(vp)
                    // Mouse: click selects (Shift adds), drag moves, drag on empty space box-selects, right-click adds to selection.
                    .pointerInput(bw, lh, tracks) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            if (down.type != PointerType.Mouse) return@awaitEachGesture
                            val shift = currentEvent.keyboardModifiers.isShiftPressed
                            val secondary = currentEvent.buttons.isSecondaryPressed
                            down.consume()
                            busy = true
                            val o = vp.toContent(down.position)
                            val c = clipAt(o)
                            var acc = Offset.Zero
                            var mode = 0
                            var ids: Set<String> = emptySet()
                            var origin = arrNow
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                acc += ch.positionChange(); ch.consume()
                                if (secondary) continue
                                if (mode == 0 && acc.getDistance() > viewConfiguration.touchSlop / 2) {
                                    if (c != null) {
                                        ids = if (c.id in selection) selection else if (shift) selection + c.id else setOf(c.id)
                                        vm.setSongSelection(ids); vm.checkpoint(); origin = arrNow; mode = 1
                                    } else mode = 2
                                }
                                if (mode == 1) vm.moveSongClips(ids, origin, snapBeats(acc.x / bw.toDouble()), (acc.y / lh).roundToInt())
                                else if (mode == 2) {
                                    val p = o + acc
                                    marquee = Rect(min(o.x, p.x), min(o.y, p.y), max(o.x, p.x), max(o.y, p.y))
                                }
                            }
                            when {
                                secondary -> if (c != null) vm.setSongSelection(selection + c.id)
                                mode == 2 -> marquee?.let { m -> selectInRect(vm, ui, tracks, m, bw, lh, if (shift) selection else emptySet()) }
                                mode == 0 -> if (c != null) vm.selectSongClip(c.id, shift) else { vm.clearSongSelection(); vm.setSongCursor(snapBeats(o.x / bw.toDouble())) }
                            }
                            marquee = null
                            busy = false
                        }
                    }
                    // Clip edges: drag to resize (the whole selection when the clip is selected).
                    .pointerInput(bw, lh, tracks) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                            val (clip, right) = edgeAt(vp.toContent(down.position)) ?: return@awaitEachGesture
                            down.consume()
                            busy = true
                            val ids = if (clip.id in selection) selection else setOf(clip.id)
                            vm.checkpoint()
                            val origin = arrNow
                            var acc = 0f
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } > 1) break
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                acc += ch.positionChange().x; ch.consume()
                                vm.resizeSongClips(ids, origin, snapBeats(acc / bw.toDouble()), right)
                            }
                            busy = false
                        }
                    }
                    .viewportPinch(vp, pass = PointerEventPass.Main),
            ) {
                val sx = vp.scrollX; val sy = vp.scrollY
                drawRect(NM.bg)
                translate(-sx, -sy) {
                    val viewL = sx; val viewR = sx + size.width
                    // Lanes
                    tracks.forEachIndexed { i, t ->
                        val y = i * lh
                        if (y + lh < sy || y > sy + size.height) return@forEachIndexed
                        drawRect(if (t.id == ui.track?.id) NM.surface else NM.padDim.copy(alpha = 0.6f), Offset(viewL, y + 1f), Size(size.width, lh - 2f))
                    }
                    // Loop region
                    if (arr.loop) {
                        val a = (arr.loopStart * bw).toFloat(); val b = (arr.loopEnd * bw).toFloat()
                        drawRect(NM.accent.copy(alpha = 0.07f), Offset(a, sy), Size(b - a, size.height))
                    }
                    // Bar / beat lines
                    val top = max(0f, sy); val bottom = min(lh * tracks.size, sy + size.height)
                    val firstBeat = floor(viewL / bw).toInt().coerceAtLeast(0)
                    val lastBeat = (viewR / bw).toInt() + 1
                    for (b in firstBeat..lastBeat) {
                        val bar = b % 4 == 0
                        if (!bar && bw < 14f) continue
                        if (bar && bw * 4 < 10f && b % 16 != 0) continue
                        drawLine(if (bar) NM.line else NM.line.copy(alpha = 0.35f), Offset(b * bw, top), Offset(b * bw, bottom), if (bar) 1.5f else 1f)
                    }
                    // Clips
                    for (c in arr.clips) {
                        val lane = tracks.indexOfFirst { it.id == c.trackId }
                        if (lane < 0) continue
                        val x0 = (c.start * bw).toFloat(); val x1 = (c.end * bw).toFloat()
                        if (x1 < viewL || x0 > viewR) continue
                        val y0 = lane * lh + 3f
                        val h = lh - 6f
                        val color = NM.track(tracks[lane].color)
                        val sel = c.id in ui.songSelection
                        drawRoundRect(color.copy(alpha = if (sel) 0.95f else 0.7f), Offset(x0, y0), Size(max(2f, x1 - x0), h), CornerRadius(8f))
                        // Mini notes (loop repeats shown), clipped to the tile.
                        clipRect(x0, y0, x1, y0 + h) {
                            val notes = c.clip.notes
                            if (notes.isNotEmpty() && x1 - x0 > 8f) {
                                val lo = notes.minOf { it.pitch }; val hi = notes.maxOf { it.pitch }
                                val span = max(1, hi - lo)
                                val len = c.clip.lengthBeats
                                var origin = c.start - c.offset
                                while (origin < c.end) {
                                    for (n in notes) {
                                        val ns = origin + n.start
                                        if (ns + n.duration < c.start || ns >= c.end) continue
                                        val nx = (ns * bw).toFloat()
                                        val ny = y0 + 14f + (h - 20f) * (1f - (n.pitch - lo).toFloat() / span)
                                        drawRect(Color.Black.copy(alpha = 0.55f), Offset(nx, ny), Size(max(2f, (n.duration * bw).toFloat()), 3f))
                                    }
                                    origin += len
                                }
                                // Loop seams
                                var seam = c.start - c.offset + len
                                while (seam < c.end - 1e-6) {
                                    drawLine(Color.Black.copy(alpha = 0.3f), Offset((seam * bw).toFloat(), y0), Offset((seam * bw).toFloat(), y0 + h), 1f)
                                    seam += len
                                }
                            }
                            val name = c.clip.name.ifBlank { tracks[lane].name }
                            if (x1 - x0 > 30f) drawText(measurer.measure(name, TextStyle(fontSize = 10.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)),
                                topLeft = Offset(x0 + 6f, y0 + 2f))
                        }
                        if (sel) drawRoundRect(Color.White, Offset(x0, y0), Size(max(2f, x1 - x0), h), CornerRadius(8f), style = Stroke(3f))
                        // Edge grips
                        drawLine(Color.Black.copy(alpha = 0.35f), Offset(x1 - 4f, y0 + h * 0.3f), Offset(x1 - 4f, y0 + h * 0.7f), 2f)
                    }
                    marquee?.let { m ->
                        drawRect(Color.White.copy(alpha = 0.1f), m.topLeft, m.size)
                        drawRect(Color.White, m.topLeft, m.size, style = Stroke(2f))
                    }
                    // Cursor and playhead
                    val cx = (ui.songCursor * bw).toFloat()
                    drawLine(NM.text.copy(alpha = 0.6f), Offset(cx, top), Offset(cx, bottom), 1.5f)
                    val st = engine.value
                    if (st.playing && st.songMode) {
                        val px = (st.estimatedBeat() * bw).toFloat()
                        drawLine(NM.play, Offset(px, top), Offset(px, bottom), 3f)
                    }
                }
            }
        }
    }
    // Follow the playhead while the song plays.
    val follow by remember(engine) { derivedStateOf { if (engine.value.playing && engine.value.songMode) (engine.value.songBeat / 4).toInt() else -1 } }
    LaunchedEffect(follow) {
        if (follow < 0) return@LaunchedEffect
        val x = follow * 4 * bw
        if (x < vp.scrollX || x > vp.scrollX + vp.viewW * 0.85f) vp.scrollTo(x = x - vp.viewW * 0.1f)
    }
}

private fun selectInRect(vm: StudioViewModel, ui: StudioUi, tracks: List<com.spectraseq.core.model.Track>, m: Rect, bw: Float, lh: Float, base: Set<String>) {
    val arr = ui.project?.arrangement ?: return
    val lanes = tracks.filterIndexed { i, _ -> (i + 1) * lh > m.top && i * lh < m.bottom }.map { it.id }.toSet()
    val picked = ArrangementOps.inSpan(arr, lanes, (m.left / bw).toDouble(), (m.right / bw).toDouble()).map { it.id }
    vm.setSongSelection(base + picked)
}
