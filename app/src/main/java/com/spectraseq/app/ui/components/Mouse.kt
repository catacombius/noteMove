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

/** Ctrl (or Cmd on Mac-layout keyboards). */
val PointerKeyboardModifiers.isCommand: Boolean get() = isCtrlPressed || isMetaPressed

/** Click + touch & hold, where a right-click also counts as the hold. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.holdClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier =
    (if (onLongClick != null) onSecondaryClick { onLongClick() } else this)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
