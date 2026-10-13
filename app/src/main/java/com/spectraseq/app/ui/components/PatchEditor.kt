package com.spectraseq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spectraseq.app.ui.FxTarget
import com.spectraseq.app.ui.StudioUi
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.core.model.DeviceHelp
import com.spectraseq.core.model.FactoryPatches
import com.spectraseq.core.model.NodeKind
import com.spectraseq.core.model.PatchGraph
import com.spectraseq.core.model.PatchNode
import com.spectraseq.core.model.PatchWire
import kotlin.math.hypot

private const val NODE_W = 150f
private const val HEADER = 26f
private const val PORT_ROW = 22f

private fun nodeHeight(n: PatchNode) = HEADER + maxOf(1, n.kind.inputs.size) * PORT_ROW + 6f
private fun inPort(n: PatchNode, i: Int) = Offset(n.x, n.y + HEADER + PORT_ROW / 2 + i * PORT_ROW)
private fun outPort(n: PatchNode) = Offset(n.x + NODE_W, n.y + HEADER + PORT_ROW / 2)
private fun helpBadge(n: PatchNode) = Offset(n.x + NODE_W - 14f, n.y + HEADER / 2)

/**
 * Effect creator, part 2: a modular patcher. Wire filters, delays, shapers, LFOs, envelope followers,
 * math and knobs together; feedback loops are allowed through a Delay. Up to 8 Knob nodes become the
 * device's knobs. Edits apply live.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PatchEditor(vm: StudioViewModel, ui: StudioUi, target: FxTarget, slotId: String, onClose: () -> Unit) {
    val project = ui.project ?: return
    val slot = vm.effectsOf(project, target).firstOrNull { it.id == slotId }
    val graph = slot?.patch
    if (slot == null || graph == null) { LaunchedEffect(Unit) { onClose() }; return }
    var selected by remember { mutableStateOf<Int?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var startMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Int?>(null) } // -1 patch name, else knob index
    var help by remember { mutableStateOf<NodeKind?>(null) }
    fun set(undoable: Boolean = false, f: (PatchGraph) -> PatchGraph) = vm.updateEffectSlot(target, slotId, undoable) { s -> s.copy(patch = f(s.patch ?: graph)) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogTrackpad()
        Column(Modifier.fillMaxSize().background(NM.bg).safeDrawingPadding().padding(10.dp)) {
            AdaptiveBar(leading = {
                IconButton(onClose) { Icon(Icons.Filled.Close, "Done", tint = NM.text) }
                Column(Modifier.weight(1f).clickable { renaming = -1 }) {
                    Text(graph.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
                    Text("Patch · drag from an output (right dot) to an input (left dot) · tap ? on a node to learn what it does · pinch to zoom", fontSize = 11.sp, color = NM.textDim)
                }
            }, actions = {
                Box {
                    TextButton({ startMenu = true }) { Text("Start from…") }
                    DropdownMenu(startMenu, { startMenu = false }) {
                        for (f in FactoryPatches.ALL) DropdownMenuItem({ Text(f.name) }, {
                            startMenu = false; selected = null
                            vm.updateEffectSlot(target, slotId) { it.copy(patch = f.copy(id = graph.id), values = f.knobValues) }
                        })
                    }
                }
                Box {
                    TextButton({ addMenu = true }) { Text("+ Node") }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        for (k in NodeKind.entries.filter { it != NodeKind.IN && it != NodeKind.OUT }) DropdownMenuItem({
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(k.label, fontSize = 14.sp, color = NM.text)
                                Text(DeviceHelp.node(k).summary, fontSize = 11.sp, color = NM.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
                            }
                        }, {
                            addMenu = false
                            val id = graph.nextId()
                            set(undoable = true) { it.addNode(k, 200f + (id % 4) * 40f, 260f + (id % 3) * 50f) }
                            selected = id
                        })
                    }
                }
                TextButton({ vm.savePatchToLibrary(graph.copy(knobValues = slot.values)) }) { Text("Save to library") }
            })
            PatchCanvas(graph, selected, Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(NM.surface),
                onSelect = { selected = it },
                onMove = { id, x, y -> set { g -> g.copy(nodes = g.nodes.map { if (it.id == id) it.copy(x = x, y = y) else it }) } },
                onWire = { w ->
                    val next = graph.toggleWire(w)
                    if (next === graph && w !in graph.wires) vm.toast("That connection would make a loop without a Delay")
                    else set(undoable = true) { next }
                },
                onHelp = { help = it },
                menuFor = { node, at ->
                    menu {
                        if (node != null) {
                            header(node.kind.label)
                            item("What does this do?") { help = node.kind }
                            if (node.kind != NodeKind.IN && node.kind != NodeKind.OUT) {
                                item("Duplicate") {
                                    val id = graph.nextId()
                                    set(undoable = true) { g -> g.addNode(node.kind, node.x + 30f, node.y + 40f).let { ng -> ng.copy(nodes = ng.nodes.map { if (it.id == id) it.copy(params = node.params) else it }) } }
                                    selected = id
                                }
                            }
                            if (graph.wires.any { it.from == node.id || it.to == node.id }) item("Disconnect all wires") {
                                set(undoable = true) { g -> g.copy(wires = g.wires.filter { it.from != node.id && it.to != node.id }) }
                            }
                            if (node.kind != NodeKind.IN && node.kind != NodeKind.OUT) {
                                divider()
                                item("Delete node", danger = true) { if (selected == node.id) selected = null; set(undoable = true) { it.removeNode(node.id) } }
                            }
                        } else {
                            header("Add a node here")
                            for (k in NodeKind.entries.filter { it != NodeKind.IN && it != NodeKind.OUT }) item(k.label) {
                                val id = graph.nextId()
                                set(undoable = true) { it.addNode(k, (at.x - NODE_W / 2).coerceIn(0f, 1500f), (at.y - HEADER / 2).coerceIn(0f, 950f)) }
                                selected = id
                            }
                        }
                    }
                },
            )
            // Selected node / exposed knobs
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                val node = selected?.let { graph.node(it) }
                if (node != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(node.kind.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NM.text, modifier = Modifier.weight(1f))
                        Chip("? Explain", false, { help = node.kind })
                        if (node.kind != NodeKind.IN && node.kind != NodeKind.OUT) Chip("Delete node", false, { selected = null; set(undoable = true) { it.removeNode(node.id) } }, color = NM.record)
                    }
                    Text(DeviceHelp.node(node.kind).summary, fontSize = 12.sp, color = NM.textDim, modifier = Modifier.padding(bottom = 2.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        node.kind.params.forEachIndexed { pi, name ->
                            Knob(name, node.params.getOrElse(pi) { 0f }, { v ->
                                set { g -> g.copy(nodes = g.nodes.map { if (it.id == node.id) it.copy(params = it.params.mapIndexed { i, x -> if (i == pi) v else x }) else it }) }
                            }, color = NM.accent, size = 44.dp, default = node.kind.defaults().getOrElse(pi) { 0f }, display = { node.kind.paramLabel(pi, it) })
                        }
                    }
                    val incoming = graph.wires.filter { it.to == node.id }
                    if (incoming.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Inputs:", fontSize = 11.sp, color = NM.textDim)
                            for (w in incoming) {
                                val from = graph.node(w.from)?.kind?.label ?: "?"
                                Chip("$from → ${node.kind.inputs.getOrElse(w.port) { "" }}  ✕", false, { set(undoable = true) { it.copy(wires = it.wires - w) } })
                            }
                        }
                    }
                } else Text("Tap a node to edit it, tap its ? (or touch & hold / right-click it) to learn what it does. Add Knob nodes to give the device its own knobs.", fontSize = 12.sp, color = NM.textDim)
                SectionTitle("Device knobs")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    graph.knobLabels.forEachIndexed { ki, label ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Knob(label, slot.value(ki), { vm.setEffectValue(target, slotId, ki, it) }, size = 40.dp)
                            Text("Rename", fontSize = 10.sp, color = NM.textDim, modifier = Modifier.clickable { renaming = ki }.padding(2.dp))
                        }
                    }
                }
            }
        }
    }

    help?.let { NodeHelpDialog(it) { help = null } }

    renaming?.let { which ->
        var text by remember(which) { mutableStateOf(if (which < 0) graph.name else graph.knobLabels.getOrElse(which) { "" }) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            confirmButton = {
                TextButton({
                    val t = text.trim().ifBlank { if (which < 0) "My Patch" else "Knob ${which + 1}" }
                    set(undoable = true) { g -> if (which < 0) g.copy(name = t) else g.copy(knobLabels = g.knobLabels.mapIndexed { i, s -> if (i == which) t.take(14) else s }) }
                    renaming = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
            title = { Text(if (which < 0) "Patch name" else "Knob name") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            containerColor = NM.surface,
        )
    }
}

@Composable
private fun PatchCanvas(
    graph: PatchGraph, selected: Int?, modifier: Modifier,
    onSelect: (Int?) -> Unit, onMove: (Int, Float, Float) -> Unit, onWire: (PatchWire) -> Unit,
    onHelp: (NodeKind) -> Unit, menuFor: (PatchNode?, Offset) -> List<MenuEntry>,
) {
    val density = LocalDensity.current
    val dp = with(density) { 1.dp.toPx() }
    val vp = remember { ViewportState(minZoomX = 0.4f, maxZoomX = 2.5f) }
    SideEffect { vp.baseW = 1600f * dp; vp.baseH = 1000f * dp * vp.zoomX }
    val g by rememberUpdatedState(graph)
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    val wire by rememberUpdatedState(onWire)
    val showHelp by rememberUpdatedState(onHelp)
    val menuOf by rememberUpdatedState(menuFor)
    val canvasMenu = rememberContextMenu()
    var menuNode by remember { mutableStateOf<PatchNode?>(null) }
    var menuAt by remember { mutableStateOf(Offset.Zero) }
    fun openMenu(n: PatchNode?, patchPos: Offset, view: Offset) { menuNode = n; menuAt = patchPos; canvasMenu.show(view) }
    val notch = with(density) { wheelNotchDp.dp.toPx() }
    val measurer = rememberTextMeasurer()
    var wireFrom by remember { mutableStateOf<Int?>(null) }
    var wireEnd by remember { mutableStateOf(Offset.Zero) }

    /** View point → patch units (dp at zoom 1). */
    fun toPatch(o: Offset): Offset { val s = dp * vp.zoomX; return Offset((o.x + vp.scrollX) / s, (o.y + vp.scrollY) / s) }
    fun nodeAt(p: Offset) = g.nodes.lastOrNull { p.x in it.x..(it.x + NODE_W) && p.y in it.y..(it.y + nodeHeight(it)) }
    fun onBadge(n: PatchNode, p: Offset) = hypot(p.x - helpBadge(n).x, p.y - helpBadge(n).y) < 13f
    fun outPortAt(p: Offset) = g.nodes.lastOrNull { it.kind.hasOutput && hypot(p.x - outPort(it).x, p.y - outPort(it).y) < 16f }
    fun inPortAt(p: Offset): Pair<PatchNode, Int>? {
        for (n in g.nodes.reversed()) for (i in n.kind.inputs.indices) if (hypot(p.x - inPort(n, i).x, p.y - inPort(n, i).y) < 18f) return n to i
        return null
    }

    Box(modifier.clipToBounds().onSizeChanged { vp.viewW = it.width.toFloat(); vp.viewH = it.height.toFloat(); vp.scrollTo(); canvasMenu.anchorH = it.height.toFloat() }) {
        ContextMenuPopup(canvasMenu) { menuOf(menuNode, menuAt) }
        Canvas(
            Modifier.fillMaxSize()
                .onWheelAt { d, mods, pos ->
                    when {
                        mods.isCommand -> vp.zoomBy(Math.pow(1.15, -d.y.toDouble()).toFloat(), 1f, pos)
                        mods.isShiftPressed -> vp.scrollBy((d.y + d.x) * notch, 0f)
                        else -> vp.scrollBy(d.x * notch, d.y * notch)
                    }
                }
                .hoverCursor { o ->
                    val p = toPatch(o)
                    when { outPortAt(p) != null || inPortAt(p) != null -> PointerIcon.Crosshair; nodeAt(p) != null -> PointerIcon.Hand; else -> PointerIcon.Default }
                }
                // Right-click: node menu, or "add a node here" on empty space.
                .onSecondaryClick { o -> val p = toPatch(o); openMenu(nodeAt(p), p, o) }
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { o -> val p = toPatch(o); if (nodeAt(p) == null) openMenu(null, p, o) }) { o -> if (nodeAt(toPatch(o)) == null) select(null) }
                }
                // Drag empty space to scroll (mouse too: there is nothing else to drag there).
                .viewportPan(vp, mouse = true)
                // Nodes: drag to move, tap to select; output dots: drag a wire to an input dot.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val p0 = toPatch(down.position)
                        val port = outPortAt(p0)
                        val node = if (port == null) nodeAt(p0) else null
                        if (port == null && node == null) return@awaitEachGesture
                        down.consume()
                        if (port != null) { wireFrom = port.id; wireEnd = p0 }
                        val startX = node?.x ?: 0f; val startY = node?.y ?: 0f
                        var acc = Offset.Zero
                        var moved = false
                        var held = false
                        while (true) {
                            // A node held still for the long-press time opens its menu.
                            val waitFor = viewConfiguration.longPressTimeoutMillis - (currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis).minus(down.uptimeMillis)
                            val ev = if (node != null && !moved && !held && down.type != PointerType.Mouse) withTimeoutOrNull(waitFor.coerceAtLeast(1L)) { awaitPointerEvent() } else awaitPointerEvent()
                            if (ev == null) { held = true; openMenu(node, p0, down.position); continue }
                            if (ev.changes.count { it.pressed } > 1) { wireFrom = null; return@awaitEachGesture }
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { c.consume(); break }
                            acc += c.positionChange(); c.consume()
                            if (held) continue
                            if (acc.getDistance() > viewConfiguration.touchSlop) moved = true
                            val s = dp * vp.zoomX
                            if (port != null) wireEnd = toPatch(c.position)
                            else if (node != null && moved) move(node.id, (startX + acc.x / s).coerceIn(0f, 1500f), (startY + acc.y / s).coerceIn(0f, 950f))
                        }
                        if (port != null) {
                            inPortAt(wireEnd)?.let { (n, i) -> wire(PatchWire(port.id, n.id, i)) }
                            wireFrom = null
                        } else if (node != null && !moved && !held) {
                            if (onBadge(node, p0)) showHelp(node.kind) else select(node.id)
                        }
                    }
                }
                .viewportPinch(vp, zoomY = false, pass = PointerEventPass.Main),
        ) {
            val s = dp * vp.zoomX
            fun v(p: Offset) = Offset(p.x * s - vp.scrollX, p.y * s - vp.scrollY)
            // Grid dots
            val step = 40f * s
            var gx = -(vp.scrollX % step)
            while (gx < size.width) {
                var gy = -(vp.scrollY % step)
                while (gy < size.height) { drawCircle(NM.line.copy(alpha = 0.5f), 1.2f, Offset(gx, gy)); gy += step }
                gx += step
            }
            // Wires
            for (w in g.wires) {
                val a = g.node(w.from) ?: continue
                val b = g.node(w.to) ?: continue
                drawWire(v(outPort(a)), v(inPort(b, w.port)), if (b.kind == NodeKind.DELAY && w.from != b.id) NM.queued else NM.accent)
            }
            wireFrom?.let { id -> g.node(id)?.let { drawWire(v(outPort(it)), v(wireEnd), NM.text) } }
            // Nodes
            for (n in g.nodes) {
                val tl = v(Offset(n.x, n.y))
                val w = NODE_W * s; val h = nodeHeight(n) * s
                val sel = n.id == selected
                val headerColor = when (n.kind) {
                    NodeKind.IN, NodeKind.OUT -> NM.play
                    NodeKind.LFO, NodeKind.ENV, NodeKind.KNOB, NodeKind.MATH, NodeKind.NOISE -> NM.solo
                    else -> NM.accent
                }
                drawRoundRect(NM.surfaceHigh, tl, Size(w, h), CornerRadius(8f * s))
                drawRoundRect(headerColor.copy(alpha = 0.85f), tl, Size(w, HEADER * s), CornerRadius(8f * s))
                if (sel) drawRoundRect(NM.text, tl, Size(w, h), CornerRadius(8f * s), style = Stroke(2.5f))
                val title = if (n.kind == NodeKind.KNOB) "Knob ${NodeKind.knobIndex(n.params.getOrElse(0) { 0f }) + 1}" else n.kind.label
                drawText(measurer.measure(title, TextStyle(fontSize = (11 * vp.zoomX).sp, color = Color.Black, fontWeight = FontWeight.SemiBold)), topLeft = tl + Offset(8f * s, 5f * s))
                // "?" badge: tap for the explanation.
                val bc = v(helpBadge(n))
                drawCircle(Color.Black.copy(alpha = 0.35f), 8f * s, bc)
                val q = measurer.measure("?", TextStyle(fontSize = (10 * vp.zoomX).sp, color = Color.White, fontWeight = FontWeight.Bold))
                drawText(q, topLeft = bc - Offset(q.size.width / 2f, q.size.height / 2f))
                n.kind.inputs.forEachIndexed { i, label ->
                    val pp = v(inPort(n, i))
                    drawCircle(NM.text, 5f * s, pp)
                    drawText(measurer.measure(label, TextStyle(fontSize = (10 * vp.zoomX).sp, color = NM.textDim)), topLeft = pp + Offset(9f * s, -7f * s))
                }
                if (n.kind.hasOutput) drawCircle(headerColor, 6f * s, v(outPort(n)))
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWire(a: Offset, b: Offset, color: Color) {
    val dx = maxOf(40f, kotlin.math.abs(b.x - a.x) * 0.5f)
    val path = Path().apply { moveTo(a.x, a.y); cubicTo(a.x + dx, a.y, b.x - dx, b.y, b.x, b.y) }
    drawPath(path, color, style = Stroke(3f))
}
