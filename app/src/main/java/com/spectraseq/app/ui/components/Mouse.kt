package com.spectraseq.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.spectraseq.app.input.TrackpadGestures

/*
 * Mouse and trackpad support (Bluetooth / USB mouse, Samsung DeX, keyboard covers):
 * right-click does whatever touch & hold does, and the scroll wheel adjusts or scrolls.
 */

/** Right-click: runs [action] and swallows the click so it doesn't also act as a tap. */
fun Modifier.onSecondaryClick(action: (Offset) -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                    e.changes.forEach { it.consume() }
                    cb(e.changes.first().position)
                    // Swallow the rest of this click.
                    while (true) {
                        val next = awaitPointerEvent(PointerEventPass.Initial)
                        next.changes.forEach { it.consume() }
                        if (next.changes.none { it.pressed }) break
                    }
                }
            }
        }
    }
}

/** Scroll wheel / two-finger trackpad scroll. [delta] is in wheel notches (positive y = down). */
fun Modifier.onWheel(action: (delta: Offset, mods: PointerKeyboardModifiers) -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Scroll) {
                    val d = e.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                    if (d != Offset.Zero) {
                        cb(d, e.keyboardModifiers)
                        e.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
}

/** Like [onWheel], also passing the pointer position (for zooming around the cursor). */
fun Modifier.onWheelAt(action: (delta: Offset, mods: PointerKeyboardModifiers, position: Offset) -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(action)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                if (e.type == PointerEventType.Scroll) {
                    val d = e.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                    if (d != Offset.Zero) {
                        cb(d, e.keyboardModifiers, e.changes.first().position)
                        e.changes.forEach { it.consume() }
                    }
                }
            }
        }
    }
}

/** Ctrl (or Cmd on Mac-layout keyboards). */
val PointerKeyboardModifiers.isCommand: Boolean get() = isCtrlPressed || isMetaPressed

/** Click + touch & hold, where a right-click also counts as the hold. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.holdClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    (if (onLongClick != null) onSecondaryClick { onLongClick() } else this)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)

/** Touchpad gestures inside a full-screen dialog (dialogs have their own window, so the activity's hook misses them). */
@Composable
fun DialogTrackpad() {
    val view = LocalView.current
    val density = LocalDensity.current.density
    DisposableEffect(view, density) {
        val undo = (view.parent as? DialogWindowProvider)?.window?.let { TrackpadGestures.install(it, density) } ?: {}
        onDispose { undo() }
    }
}

/** Pixels per wheel notch in the zoomable grids (touchpad scrolling is converted at the same rate). */
val wheelNotchDp = TrackpadGestures.NOTCH_DP

/** Mouse / touchpad cursors. */
val ResizeHorizontalIcon: PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW)
val ResizeVerticalIcon: PointerIcon = PointerIcon(android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW)

/** A cursor that depends on what's under the mouse (e.g. resize arrows over a note's edge). */
fun Modifier.hoverCursor(icon: (Offset) -> PointerIcon): Modifier = composed {
    var current by remember { mutableStateOf(PointerIcon.Default) }
    val f by rememberUpdatedState(icon)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Move || e.type == PointerEventType.Enter) {
                    val c = e.changes.firstOrNull() ?: continue
                    if (c.type == PointerType.Mouse && !c.pressed) current = f(c.position)
                }
            }
        }
    }.pointerHoverIcon(current)
}
