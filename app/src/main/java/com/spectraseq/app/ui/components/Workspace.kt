package com.spectraseq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.StudioViewModel
import com.spectraseq.app.ui.Workspace
import com.spectraseq.app.ui.theme.Appearance
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.app.ui.theme.UiScale

/** The workspace the UI is in, for components that word their hints differently for touch and mouse. */
val LocalWorkspace = staticCompositionLocalOf { Workspace.TOUCH }

/**
 * Live-style info bar: whatever the mouse hovers over can describe itself (what a click, drag or
 * right-click does). Regions nest; the innermost one hovered wins.
 */
object HoverHint {
    private val stack = mutableStateListOf<String>()
    val text: String? get() = stack.lastOrNull()
    internal fun enter(t: String) { stack.add(t) }
    internal fun exit(t: String) { val i = stack.lastIndexOf(t); if (i >= 0) stack.removeAt(i) }
}

/** Shows [text] in the info bar while the mouse is over this element. */
fun Modifier.hint(text: String): Modifier = pointerInput(text) {
    var inside = false
    try {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                when (e.type) {
                    PointerEventType.Enter -> if (!inside) { inside = true; HoverHint.enter(text) }
                    PointerEventType.Exit -> if (inside) { inside = false; HoverHint.exit(text) }
                }
            }
        }
    } finally {
        if (inside) HoverHint.exit(text)
    }
}

/** Switches between the touch and keyboard & mouse workspaces (shows the one you'd switch to). */
@Composable
fun WorkspaceToggleButton(vm: StudioViewModel, workspace: Workspace) {
    val toDesktop = workspace == Workspace.TOUCH
    IconButton(onClick = vm::toggleWorkspace, modifier = Modifier.hint(if (toDesktop) "Switch to the keyboard & mouse workspace (Ctrl+Shift+W)" else "Switch to the touch workspace (Ctrl+Shift+W)")) {
        Icon(if (toDesktop) Icons.Filled.Mouse else Icons.Filled.TouchApp, if (toDesktop) "Keyboard & mouse workspace" else "Touch workspace", tint = NM.text)
    }
}

/** Settings: pick the workspace, its interface size, and whether to suggest it when a mouse connects. */
@Composable
fun WorkspaceSection(vm: StudioViewModel, current: Workspace) {
    val context = LocalContext.current
    val suggest by vm.suggestOnMouse.collectAsState()
    SectionTitle("Workspace")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkspaceCard(
            Icons.Filled.TouchApp, "Touch", "Pads up front, one panel at a time on small screens, gestures (hold, pinch, swipe).",
            current == Workspace.TOUCH, Modifier.weight(1f),
        ) { vm.setWorkspace(Workspace.TOUCH) }
        WorkspaceCard(
            Icons.Filled.Mouse, "Keyboard & mouse", "Desktop layout: Session / Song / Mixer on top, clip editor and devices below, an info bar, denser controls.",
            current == Workspace.DESKTOP, Modifier.weight(1f),
        ) { vm.setWorkspace(Workspace.DESKTOP) }
    }
    // Applied on release: the interface rescales (this slider included) when the value changes.
    var preview by remember(UiScale.desktop) { mutableStateOf(UiScale.desktop) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Keyboard & mouse size", fontSize = 13.sp, color = NM.text, modifier = Modifier.width(150.dp))
        Slider(preview, { preview = Math.round(it * 20) / 20f }, valueRange = 0.6f..1.4f, steps = 15, modifier = Modifier.weight(1f),
            onValueChangeFinished = { if (preview != UiScale.desktop) { UiScale.desktop = preview; Appearance.save(context) } })
        Text("${Math.round(preview * 100)}%", fontSize = 12.sp, color = NM.textDim, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(suggest, { vm.setSuggestOnMouse(it) })
        Text("Offer the keyboard & mouse workspace when a mouse or touchpad connects", fontSize = 13.sp, color = NM.text)
    }
}

@Composable
private fun WorkspaceCard(icon: ImageVector, title: String, text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) NM.accent.copy(alpha = 0.16f) else NM.surfaceHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) NM.accent else NM.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = if (selected) NM.accent else NM.text, modifier = Modifier.size(20.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = NM.text)
        }
        Text(text, fontSize = 12.sp, color = NM.textDim, modifier = Modifier.padding(top = 4.dp))
    }
}
