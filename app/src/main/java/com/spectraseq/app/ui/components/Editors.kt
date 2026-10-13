package com.spectraseq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.engine.EngineState
import com.spectraseq.core.model.ClipOps
import com.spectraseq.core.model.DRUM_BASE_NOTE
import com.spectraseq.core.model.DRUM_PAD_COUNT
import com.spectraseq.core.model.Note
import com.spectraseq.core.model.Scale
import com.spectraseq.core.model.TrackKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            Chip("Select all", false, vm::selectAllNotes)
            if (vm.hasNoteClipboard) Chip("Paste", false, vm::pasteNotes)
            Chip("×2 Loop", false, vm::duplicateLoop)
            Chip("Quantize", false, { vm.quantizeClip(ui.stepGrid) })
            Chip("◀", false, { vm.nudgeClip(-ui.stepGrid) })
            Chip("▶", false, { vm.nudgeClip(ui.stepGrid) })
            if (ui.track?.drumLayout != true) {
                Chip("−1", false, { vm.transposeClip(-1) })
                Chip("+1", false, { vm.transposeClip(1) })
                Chip("−Oct", false, { vm.transposeClip(-12) })
                Chip("+Oct", false, { vm.transposeClip(12) })
            }
            Chip("Clear", false, vm::clearClipNotes, color = NM.record)
        }
    }
}

/** Actions for the selected notes; replaces the clip toolbar while a selection exists. */
@Composable
fun NoteSelectionBar(vm: StudioViewModel, ui: StudioUi, modifier: Modifier = Modifier) {
    val sel = ui.noteSelection
    val clip = ui.clip ?: return
    val g = ui.stepGrid
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip("✕ ${sel.size} selected", true, vm::clearNoteSelection)
        Chip("All", false, vm::selectAllNotes)
        Chip("Copy", false, vm::copyNotes)
        if (vm.hasNoteClipboard) Chip("Paste", false, vm::pasteNotes)
        Chip("Duplicate", false, vm::duplicateSelectedNotes)
        Chip("Delete", false, vm::deleteSelectedNotes, color = NM.record)
        Chip("◀", false, { vm.transformNotes { n -> n.copy(start = ((n.start - g) % clip.lengthBeats + clip.lengthBeats) % clip.lengthBeats) } })
        Chip("▶", false, { vm.transformNotes { n -> n.copy(start = (n.start + g) % clip.lengthBeats) } })
        Chip("▲", false, { vm.transformNotes { n -> n.copy(pitch = (n.pitch + 1).coerceAtMost(127)) } })
        Chip("▼", false, { vm.transformNotes { n -> n.copy(pitch = (n.pitch - 1).coerceAtLeast(0)) } })
        if (ui.track?.drumLayout != true) {
            Chip("+Oct", false, { vm.transformNotes { n -> n.copy(pitch = (n.pitch + 12).coerceAtMost(127)) } })
            Chip("−Oct", false, { vm.transformNotes { n -> n.copy(pitch = (n.pitch - 12).coerceAtLeast(0)) } })
        }
        Chip("Vel −", false, { vm.transformNotes { n -> n.copy(velocity = (n.velocity - 10).coerceAtLeast(1)) } })
        Chip("Vel +", false, { vm.transformNotes { n -> n.copy(velocity = (n.velocity + 10).coerceAtMost(127)) } })
        Chip("Len ÷2", false, { vm.transformNotes { n -> n.copy(duration = max(1.0 / 32, n.duration / 2)) } })
        Chip("Len ×2", false, { vm.transformNotes { n -> n.copy(duration = min(clip.lengthBeats - n.start, n.duration * 2)) } })
        Chip("Legato", false, vm::legatoSelectedNotes)
        Chip("Quantize", false, { vm.transformNotes { n -> n.copy(start = ClipOps.snap(n.start, g) % clip.lengthBeats) } })
    }
}

private fun formatBars(beats: Double): String {
    val bars = beats / 4
    return if (bars == floor(bars)) bars.toInt().toString() else bars.toString()
}

/**
 * A note grid shared by the drum step sequencer and the piano roll.
 *
 * - Tap empty: add a note. Tap a note: delete it.
 * - Touch & hold a note: select it, then drag to move the selection (time and pitch).
 * - Touch & hold empty space and drag: marquee-select.
 * - While notes are selected, taps add/remove notes from the selection; tap empty space to deselect.
 */
@Composable
private fun NoteGrid(
    vm: StudioViewModel,
    ui: StudioUi,
    engine: State<EngineState>,
    rows: List<Int>,
    cellW: Dp,
    cellH: Dp,
    noteLength: Double,
    rowBackground: (Int) -> Color,
    vp: ViewportState,
    modifier: Modifier = Modifier,
) {
    val track = ui.track ?: return
    val clip = ui.clip
    val grid = ui.stepGrid
    val length = clip?.lengthBeats ?: (ui.newClipBars * 4.0)
    val steps = max(1, kotlin.math.round(length / grid).toInt())
    val density = LocalDensity.current
    val baseCw = with(density) { cellW.toPx() }
    val baseCh = with(density) { cellH.toPx() }
    SideEffect { vp.baseW = baseCw * steps; vp.baseH = baseCh * rows.size }
    // Cell size at the current zoom; gesture handlers restart when it changes.
    val cw = baseCw * vp.zoomX
    val ch = baseCh * vp.zoomY
    val color = NM.track(track.color)
    val notes by rememberUpdatedState(clip?.notes ?: emptyList())
    val selection by rememberUpdatedState(ui.noteSelection)
    var marquee by remember { mutableStateOf<Rect?>(null) }
    var resizing by remember { mutableStateOf(false) }
    var mouseBusy by remember { mutableStateOf(false) }
    val handlePx = with(density) { 16.dp.toPx() }
    val notchPx = with(density) { wheelNotchDp.dp.toPx() }
    // Context menu (touch & hold and release without dragging, or right-click): the note under it, or empty space.
    val gridMenu = rememberContextMenu()
    var menuNote by remember { mutableStateOf<Note?>(null) }
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    fun openMenu(note: Note?, content: Offset, view: Offset) { menuNote = note; menuAt = content; gridMenu.show(view) }

    /**
     * Finds the length handle under [o]: +1 = right edge (every note), -1 = left edge (selected notes).
     * Selected notes win so a whole selection can be resized from any of its notes.
     */
    fun handleHit(o: Offset): Pair<Note, Int>? {
        val r = (o.y / ch).toInt()
        val pitch = rows.getOrNull(r) ?: return null
        val candidates = notes.filter { it.pitch == pitch }.sortedByDescending { it in selection }
        for (n in candidates) {
            val left = (n.start / grid * cw).toFloat()
            val right = (n.end / grid * cw).toFloat()
            val inside = handlePx * 0.75f
            if (o.x in (right - minOf(inside, (right - left) / 2))..(right + handlePx)) return n to 1
            if (n in selection && o.x in (left - handlePx)..(left + minOf(inside, (right - left) / 2))) return n to -1
        }
        return null
    }

    fun hit(o: Offset): Note? {
        val r = (o.y / ch).toInt()
        val pitch = rows.getOrNull(r) ?: return null
        val beat = o.x / cw * grid
        return notes.firstOrNull { it.pitch == pitch && beat >= it.start - 1e-6 && beat < it.start + max(it.duration, grid * 0.6) }
    }

    /** [orig] shifted by the drag distance [acc], snapped to steps and rows. */
    fun movedNotes(orig: List<Note>, acc: Offset): List<Note> {
        val dStep = (acc.x / cw).roundToInt()
        val dRow = (acc.y / ch).roundToInt()
        return orig.map { n ->
            val r = (rows.indexOf(n.pitch) + dRow).coerceIn(0, rows.lastIndex)
            n.copy(start = (n.start + dStep * grid).coerceIn(0.0, length - grid * 0.5), pitch = rows[r])
        }
    }

    fun inMarquee(m: Rect): List<Note> = notes.filter { n ->
        val r = rows.indexOf(n.pitch)
        if (r < 0) return@filter false
        val nr = Rect((n.start / grid * cw).toFloat(), r * ch, ((n.start + max(n.duration, grid * 0.5)) / grid * cw).toFloat(), (r + 1) * ch)
        nr.overlaps(m)
    }

    fun addNoteAt(o: Offset) {
        val step = (o.x / cw).toInt()
        val r = (o.y / ch).toInt()
        if (r !in rows.indices || step !in 0 until steps) return
        val pitch = rows[r]
        if (clip == null) vm.createClip(track.id, ui.selectedScene)
        vm.setNotes(notes + Note(pitch, step * grid, noteLength, 100))
        vm.padDown(pitch, 100); vm.padUp(pitch)
    }

    Box(modifier.clipToBounds().onSizeChanged { vp.viewW = it.width.toFloat(); vp.viewH = it.height.toFloat(); vp.scrollTo(); gridMenu.anchorH = it.height.toFloat() }) {
        ContextMenuPopup(gridMenu) { noteGridMenu(vm, ui, menuNote, drums = track.drumLayout, onAddHere = { addNoteAt(menuAt) }, onFit = { vp.setZoom(x = vp.viewW / max(1f, vp.baseW)); vp.scrollTo(x = 0f) }) }
        Canvas(
            Modifier.fillMaxSize()
                .hoverCursor { o -> if (handleHit(vp.toContent(o)) != null) ResizeHorizontalIcon else PointerIcon.Default }
                // Mouse wheel scrolls (Shift = sideways), Ctrl + wheel zooms in time around the cursor.
                .onWheelAt { d, mods, pos ->
                    when {
                        mods.isCommand -> vp.zoomBy(Math.pow(1.15, -d.y.toDouble()).toFloat(), 1f, pos)
                        mods.isShiftPressed -> vp.scrollBy((d.y + d.x) * notchPx, 0f)
                        else -> vp.scrollBy(d.x * notchPx, d.y * notchPx)
                    }
                }
                .pointerInput(steps, grid, rows, cw, ch) {
                    detectTapGestures { o0 ->
                        val o = vp.toContent(o0)
                        val step = (o.x / cw).toInt()
                        val r = (o.y / ch).toInt()
                        if (r !in rows.indices || step !in 0 until steps) return@detectTapGestures
                        val n = hit(o)
                        if (selection.isNotEmpty()) {
                            if (n != null) vm.toggleNoteSelection(n) else vm.clearNoteSelection()
                            return@detectTapGestures
                        }
                        if (n != null) vm.setNotes(notes - n) else addNoteAt(o)
                    }
                }
                .pointerInput(steps, grid, rows, cw, ch) {
                    var mode = 0 // 1 = move, 2 = marquee
                    var start = Offset.Zero
                    var acc = Offset.Zero
                    var orig: List<Note> = emptyList()
                    var current: Set<Note> = emptySet()
                    var startView = Offset.Zero
                    var held: Note? = null
                    detectDragGesturesAfterLongPress(
                        onDragStart = { o0 ->
                            if (resizing || mouseBusy) { mode = 0; return@detectDragGesturesAfterLongPress }
                            val o = vp.toContent(o0)
                            start = o; acc = Offset.Zero; startView = o0
                            held = hit(o)
                            val n = hit(o)
                            if (n != null) {
                                val sel = if (n in selection) selection else if (selection.isNotEmpty()) selection + n else setOf(n)
                                vm.setNoteSelection(sel)
                                vm.checkpoint()
                                orig = sel.toList(); current = sel; mode = 1
                            } else {
                                mode = 2; marquee = Rect(o, o)
                            }
                        },
                        onDragEnd = {
                            // Held and released without dragging: open the context menu instead.
                            if (mode != 0 && acc.getDistance() < viewConfiguration.touchSlop) openMenu(held, start, startView)
                            else if (mode == 2) marquee?.let { m -> vm.setNoteSelection(selection + inMarquee(m)) }
                            if (mode != 0) marquee = null
                            mode = 0
                        },
                        onDragCancel = { if (mode != 0) marquee = null; mode = 0 },
                    ) { c, d ->
                        if (resizing || mode == 0) return@detectDragGesturesAfterLongPress
                        c.consume()
                        acc += d
                        if (mode == 2) {
                            val p = start + acc
                            marquee = Rect(min(start.x, p.x), min(start.y, p.y), max(start.x, p.x), max(start.y, p.y))
                        } else if (mode == 1) {
                            val moved = movedNotes(orig, acc)
                            if (moved.toSet() != current) {
                                vm.replaceNotes(current, moved)
                                current = moved.toSet()
                            }
                        }
                    }
                }
                // One finger drag on the grid pans (with fling); two fingers pinch-zoom (last modifier below).
                .viewportPan(vp)
                // Mouse: click empty = add a note (or deselect), click a note = select it (Shift/Ctrl = add to the
                // selection), double-click a note = delete it, drag a note = move (Ctrl + drag = copy), drag empty
                // space = box-select, right-click a note = add it to the selection.
                .pointerInput(steps, grid, rows, cw, ch) {
                    var lastClickAt = 0L
                    var lastClickNote: Note? = null
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        if (down.type != PointerType.Mouse || currentEvent.buttons.isTertiaryPressed) return@awaitEachGesture
                        val mods = currentEvent.keyboardModifiers
                        val additive = mods.isShiftPressed || mods.isCommand
                        val copy = mods.isCommand
                        val secondary = currentEvent.buttons.isSecondaryPressed
                        down.consume()
                        mouseBusy = true
                        val downPos = vp.toContent(down.position)
                        val n = hit(downPos)
                        var mode = 0 // 1 = move, 2 = marquee
                        var acc = Offset.Zero
                        var orig: List<Note> = emptyList()
                        var current: Set<Note> = emptySet()
                        val slop = viewConfiguration.touchSlop / 2
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            acc += c.positionChange()
                            c.consume()
                            if (secondary) continue
                            if (mode == 0 && acc.getDistance() > slop) {
                                if (n != null) {
                                    val sel = when { n in selection -> selection; additive -> selection + n; else -> setOf(n) }
                                    vm.checkpoint()
                                    orig = sel.toList()
                                    if (copy) vm.setNoteSelection(emptySet()) else { current = sel; vm.setNoteSelection(sel) }
                                    mode = 1
                                } else mode = 2
                            }
                            if (mode == 1) {
                                val moved = movedNotes(orig, acc)
                                if (copy && moved == orig) {
                                    if (current.isNotEmpty()) { vm.replaceNotes(current, emptyList(), select = false); current = emptySet() }
                                } else if (moved.toSet() != current) {
                                    vm.replaceNotes(current, moved)
                                    current = moved.toSet()
                                }
                            } else if (mode == 2) {
                                val p = downPos + acc
                                marquee = Rect(min(downPos.x, p.x), min(downPos.y, p.y), max(downPos.x, p.x), max(downPos.y, p.y))
                            }
                        }
                        when {
                            secondary -> {
                                // Right-click a note: select it (unless it's already in the selection), then show the menu.
                                if (n != null && n !in selection) vm.setNoteSelection(setOf(n))
                                openMenu(n, downPos, down.position)
                            }
                            mode == 2 -> marquee?.let { m -> vm.setNoteSelection((if (additive) selection else emptySet()) + inMarquee(m)) }
                            mode == 0 -> {
                                val now = System.currentTimeMillis()
                                val double = n != null && n == lastClickNote && now - lastClickAt < 400
                                lastClickAt = now; lastClickNote = n
                                when {
                                    double && n != null -> { vm.setNotes(notes - n); vm.setNoteSelection(selection - n); lastClickNote = null }
                                    n != null && additive -> vm.toggleNoteSelection(n)
                                    n != null -> { vm.setNoteSelection(setOf(n)); vm.padDown(n.pitch, n.velocity); vm.padUp(n.pitch) }
                                    selection.isNotEmpty() && !additive -> vm.clearNoteSelection()
                                    else -> addNoteAt(downPos)
                                }
                            }
                        }
                        marquee = null
                        mouseBusy = false
                    }
                }
                // Length handles: drag the ▸ at a note's end (or ◂ at the start of selected notes) to resize.
                // Dragging a handle of a selected note resizes the whole selection by the same amount.
                .pointerInput(steps, grid, rows, cw, ch) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (currentEvent.buttons.isSecondaryPressed) return@awaitEachGesture
                        val (note, side) = handleHit(vp.toContent(down.position)) ?: return@awaitEachGesture
                        down.consume()
                        resizing = true
                        val wasSelected = note in selection
                        val targets = if (wasSelected) selection.toList() else listOf(note)
                        val minDur = grid / 4
                        var current = targets.toSet()
                        var acc = 0f
                        vm.checkpoint()
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } > 1) break // a second finger: pinch takes over
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            acc += c.positionChange().x
                            c.consume()
                            val delta = (acc / cw).roundToInt() * grid
                            val resized = targets.map { n ->
                                if (side > 0) n.copy(duration = (n.duration + delta).coerceIn(min(minDur, n.duration), max(minDur, length - n.start)))
                                else {
                                    val end = n.end
                                    val ns = (n.start + delta).coerceIn(0.0, max(0.0, end - minDur))
                                    n.copy(start = ns, duration = end - ns)
                                }
                            }
                            if (resized.toSet() != current) {
                                vm.replaceNotes(current, resized, select = wasSelected)
                                current = resized.toSet()
                            }
                        }
                        resizing = false
                    }
                }
                .viewportPinch(vp),
        ) {
            val contentW = cw * steps
            val contentH = ch * rows.size
            val sx = vp.scrollX
            val sy = vp.scrollY
            // Only what's on screen is drawn.
            val r0 = (sy / ch).toInt().coerceIn(0, rows.size)
            val r1 = ((sy + size.height) / ch).toInt().plus(1).coerceIn(0, rows.size)
            val s0 = (sx / cw).toInt().coerceIn(0, steps)
            val s1 = ((sx + size.width) / cw).toInt().plus(1).coerceIn(0, steps)
            drawRect(NM.bg)
            translate(-sx, -sy) {
            for (r in r0 until r1) drawRect(rowBackground(rows[r]), Offset(sx, r * ch), Size(min(size.width, contentW - sx), ch - 1f))
            val lineTop = max(0f, sy)
            val lineBottom = min(contentH, sy + size.height)
            for (s in s0..s1) {
                val beat = s * grid
                val strong = abs(beat % 4.0) < 1e-6
                val medium = abs(beat % 1.0) < 1e-6
                // Thin out step lines when zoomed far out.
                if (!strong && !medium && cw < 6f) continue
                drawLine(if (strong) NM.textDim else if (medium) NM.line else NM.line.copy(alpha = 0.4f), Offset(s * cw, lineTop), Offset(s * cw, lineBottom), if (strong) 2f else 1f)
            }
            val firstBeat = s0 * grid
            val lastBeat = s1 * grid
            for (n in notes) {
                if (n.end < firstBeat || n.start > lastBeat) continue
                val r = rows.indexOf(n.pitch)
                if (r < r0 - 1 || r > r1 || r < 0) continue
                val x = (n.start / grid * cw).toFloat()
                val w = max(cw * 0.5f, (n.duration / grid * cw).toFloat() - 2f)
                val sel = n in selection
                drawRoundRect(color.copy(alpha = 0.4f + 0.6f * n.velocity / 127f), Offset(x + 1f, r * ch + 2f), Size(w, ch - 4f), CornerRadius(6f))
                if (sel) drawRoundRect(Color.White, Offset(x + 1f, r * ch + 2f), Size(w, ch - 4f), CornerRadius(6f), style = Stroke(3f))
                // Length handles
                val cy = r * ch + ch / 2
                val half = min(ch * 0.22f, handlePx * 0.38f)
                val xr = x + 1f + w
                val arrowColor = Color.White.copy(alpha = if (sel) 0.95f else 0.6f)
                drawPath(Path().apply {
                    moveTo(xr - half * 1.4f, cy - half); lineTo(xr - half * 1.4f, cy + half); lineTo(xr - 1f, cy); close()
                }, arrowColor)
                if (sel) {
                    drawLine(Color.White, Offset(xr - 1.5f, r * ch + 4f), Offset(xr - 1.5f, (r + 1) * ch - 4f), 3f)
                    val xl = x + 1f
                    drawPath(Path().apply {
                        moveTo(xl + half * 1.4f, cy - half); lineTo(xl + half * 1.4f, cy + half); lineTo(xl + 1f, cy); close()
                    }, arrowColor)
                    drawLine(Color.White, Offset(xl + 1.5f, r * ch + 4f), Offset(xl + 1.5f, (r + 1) * ch - 4f), 3f)
                }
            }
            marquee?.let { m ->
                drawRect(Color.White.copy(alpha = 0.12f), m.topLeft, m.size)
                drawRect(Color.White, m.topLeft, m.size, style = Stroke(2f))
            }
            val head = playheadIn(engine.value, track, ui.selectedScene, clip)
            if (head != null) {
                val x = (head / grid * cw).toFloat()
                drawLine(Color.White, Offset(x, lineTop), Offset(x, lineBottom), 3f)
            }
            }
        }
    }
}

/** Context menu for the note grid: actions for the held note (and the selection), or for empty space. */
private fun noteGridMenu(vm: StudioViewModel, ui: StudioUi, note: Note?, drums: Boolean, onAddHere: () -> Unit, onFit: () -> Unit): List<MenuEntry> = menu {
    val sel = ui.noteSelection
    val clip = ui.clip
    if (note != null && clip != null) {
        header(if (sel.size > 1) "${sel.size} notes" else "${if (drums) "Pad ${note.pitch - DRUM_BASE_NOTE + 1}" else Scale.noteName(note.pitch)} · velocity ${note.velocity}")
        item("Delete", "Del", danger = true) { if (sel.isEmpty()) vm.setNotes(clip.notes - note) else vm.deleteSelectedNotes() }
        item("Duplicate", "Ctrl+D", onClick = vm::duplicateSelectedNotes)
        item("Copy", "Ctrl+C", onClick = vm::copyNotes)
        item("Cut", "Ctrl+X") { vm.copyNotes(); vm.deleteSelectedNotes() }
        if (vm.hasNoteClipboard) item("Paste", "Ctrl+V", onClick = vm::pasteNotes)
        divider()
        item("Quantize", "Ctrl+U") { val g = ui.stepGrid; vm.transformNotes { n -> n.copy(start = ClipOps.snap(n.start, g) % clip.lengthBeats) } }
        item("Legato", onClick = vm::legatoSelectedNotes)
        item("Length ÷2") { vm.transformNotes { n -> n.copy(duration = max(1.0 / 32, n.duration / 2)) } }
        item("Length ×2") { vm.transformNotes { n -> n.copy(duration = min(clip.lengthBeats - n.start, n.duration * 2)) } }
        if (!drums) {
            item("Transpose +1", "↑", keepOpen = true) { vm.transformNotes { n -> n.copy(pitch = (n.pitch + 1).coerceAtMost(127)) } }
            item("Transpose −1", "↓", keepOpen = true) { vm.transformNotes { n -> n.copy(pitch = (n.pitch - 1).coerceAtLeast(0)) } }
            item("Octave up", "Shift+↑") { vm.transformNotes { n -> n.copy(pitch = (n.pitch + 12).coerceAtMost(127)) } }
            item("Octave down", "Shift+↓") { vm.transformNotes { n -> n.copy(pitch = (n.pitch - 12).coerceAtLeast(0)) } }
        }
        header("Velocity")
        for (v in intArrayOf(127, 100, 80, 60, 40)) item("$v", checked = sel.all { it.velocity == v }) { vm.transformNotes { n -> n.copy(velocity = v) } }
        divider()
        item("Select all", "Ctrl+A", onClick = vm::selectAllNotes)
        item("Deselect", "Esc", onClick = vm::clearNoteSelection)
    } else {
        item(if (clip == null) "Start a clip with a note here" else "Add a note here", onClick = onAddHere)
        if (vm.hasNoteClipboard && clip != null) item("Paste", "Ctrl+V", onClick = vm::pasteNotes)
        if (clip != null) {
            item("Select all", "Ctrl+A", onClick = vm::selectAllNotes)
            if (sel.isNotEmpty()) item("Deselect", "Esc", onClick = vm::clearNoteSelection)
            divider()
            item("Quantize clip", "Ctrl+U") { vm.quantizeClip(ui.stepGrid) }
            item("Double the loop", onClick = vm::duplicateLoop)
        }
        divider()
        header("Grid")
        for ((g, label) in ClipOps.GRIDS) item(label, checked = abs(g - ui.stepGrid) < 1e-6) { vm.setStepGrid(g) }
        divider()
        item("Zoom to fit", onClick = onFit)
    }
}

/** Drum step sequencer: one row per pad (pad 16 on top, like the pad grid). */
@Composable
fun DrumStepGrid(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, vp: ViewportState, modifier: Modifier = Modifier, cell: Dp = 30.dp) {
    val track = ui.track ?: return
    val rows = remember { (DRUM_PAD_COUNT - 1 downTo 0).map { DRUM_BASE_NOTE + it } }
    Row(modifier) {
        RowLabels(vp, Modifier.width(64.dp)) {
            for (pitch in rows) {
                val pad = pitch - DRUM_BASE_NOTE
                Box(
                    Modifier.height(cell * vp.zoomY).fillMaxWidth()
                        .background(if (pad == ui.selectedPad) NM.surfaceHigh else Color.Transparent)
                        .pointerInput(pad) { detectTapGestures { vm.selectPad(pad); vm.auditionPad(pad) } },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(PadLayouts.padName(track, pad).ifBlank { "Pad ${pad + 1}" }, Modifier.padding(start = 4.dp), fontSize = 11.sp,
                        color = if (pad == ui.selectedPad) NM.text else NM.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        NoteGrid(vm, ui, engine, rows, cell, cell, ui.stepGrid * 0.95, { p ->
            if (p - DRUM_BASE_NOTE == ui.selectedPad) NM.surfaceHigh else NM.pad.copy(alpha = 0.55f)
        }, vp, Modifier.weight(1f))
    }
}

/** Piano roll. Rows are pitches (only in-scale rows when "In Key" is on). */
@Composable
fun NoteEditor(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, vp: ViewportState, modifier: Modifier = Modifier, cellW: Dp = 28.dp, cellH: Dp = 26.dp) {
    val project = ui.project ?: return
    val track = ui.track ?: return
    val clip = ui.clip
    val inKey = ui.inKey && project.scale.intervals.size < 12
    val rows: List<Int> = remember(project.rootNote, project.scale, inKey) {
        (127 downTo 0).filter { !inKey || project.scale.contains(it, project.rootNote) }
    }
    val color = NM.track(track.color)
    val ch = with(LocalDensity.current) { cellH.toPx() }
    LaunchedEffect(track.id, ui.selectedScene, rows.size) {
        val focus = clip?.notes?.maxByOrNull { it.pitch }?.pitch ?: ((ui.octave + 3) * 12 + project.rootNote)
        val idx = rows.indexOfFirst { it <= focus }.coerceAtLeast(0)
        vp.baseH = ch * rows.size
        vp.scrollTo(y = max(0f, (idx - 3) * ch * vp.zoomY))
    }
    Row(modifier) {
        RowLabels(vp, Modifier.width(44.dp)) {
            for (p in rows) {
                val black = Scale.NOTE_NAMES[p % 12].contains('#')
                val root = Math.floorMod(p - project.rootNote, 12) == 0
                Box(
                    Modifier.height(cellH * vp.zoomY).fillMaxWidth().background(if (root) color.copy(alpha = 0.35f) else if (black) NM.padDim else NM.surfaceHigh)
                        .pointerInput(p) { detectTapGestures(onPress = { vm.padDown(p, 100); tryAwaitRelease(); vm.padUp(p) }) },
                    contentAlignment = Alignment.CenterStart,
                ) { Text(Scale.noteName(p), Modifier.padding(start = 4.dp), fontSize = 10.sp, color = NM.text) }
            }
        }
        NoteGrid(vm, ui, engine, rows, cellW, cellH, ui.stepGrid, { p ->
            val black = Scale.NOTE_NAMES[p % 12].contains('#')
            if (black && !inKey) NM.padDim else NM.pad.copy(alpha = 0.55f)
        }, vp, Modifier.weight(1f))
    }
}

/** Row names beside a grid: they follow the grid's vertical scroll and zoom, and can be dragged to scroll. */
@Composable
private fun RowLabels(vp: ViewportState, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight().clipToBounds().viewportPan(vp, panX = false, requireUnconsumed = false).viewportPinch(vp, zoomX = false, pass = PointerEventPass.Initial)) {
        Column(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).offset { IntOffset(0, -vp.scrollY.roundToInt()) }) { content() }
    }
}

/** The clip editor for the selected track: drum step grid or piano roll. */
@Composable
fun ClipEditor(vm: StudioViewModel, ui: StudioUi, engine: State<EngineState>, modifier: Modifier = Modifier, compact: Boolean = false) {
    val track = ui.track ?: return
    val drums = track.drumLayout
    // One viewport per kind of editor; its zoom is remembered between sessions.
    val zoomKey = if (drums) "zoom_drums" else "zoom_keys"
    val splits by vm.splits.collectAsState()
    val vp = remember(drums) {
        ViewportState(zoomX = splits["${zoomKey}_x"] ?: 1f, zoomY = splits["${zoomKey}_y"] ?: 1f, minZoomY = if (drums) 0.6f else 0.5f)
    }
    LaunchedEffect(vp) {
        snapshotFlow { vp.zoomX to vp.zoomY }.collectLatest { (zx, zy) ->
            delay(600)
            vm.setSplit("${zoomKey}_x", zx); vm.setSplit("${zoomKey}_y", zy)
        }
    }
    Column(modifier) {
        ui.editingSongClip?.let { sc ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("← Song", true, vm::exitSongClipEditing, color = NM.accent)
                Text("Editing a song clip at bar ${(sc.start / 4).toInt() + 1} (its own copy of the notes)", fontSize = 12.sp, color = NM.textDim)
            }
        }
        if (ui.noteSelection.isNotEmpty()) NoteSelectionBar(vm, ui) else ClipToolbar(vm, ui)
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            if (track.drumLayout) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val cell = ((maxHeight - 2.dp) / DRUM_PAD_COUNT).coerceIn(18.dp, if (compact) 30.dp else 40.dp)
                    DrumStepGrid(vm, ui, engine, vp, Modifier.fillMaxSize(), cell = cell)
                }
            } else {
                NoteEditor(vm, ui, engine, vp, Modifier.fillMaxSize(), cellW = if (compact) 26.dp else 32.dp)
            }
        }
        Text(
            if (ui.clip == null) "Empty slot: tap the grid to start a clip, or press Record."
            else "Tap to add/remove · drag ▸ at a note's end to change its length (selected notes: ◂ ▸, all together) · hold a note to select & move · hold empty space to box-select · drag to scroll, pinch to zoom (sideways = time, up/down = rows)",
            Modifier.padding(4.dp), fontSize = 11.sp, color = NM.textDim, fontWeight = FontWeight.Medium,
        )
    }
}
