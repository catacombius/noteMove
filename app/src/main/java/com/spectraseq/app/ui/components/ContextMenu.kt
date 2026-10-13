package com.spectraseq.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.theme.NM

/*
 * Context menus: touch & hold or right-click anything that has more to offer. The menu opens where the
 * finger or cursor is, and shows the keyboard shortcut next to an action when there is one.
 */

sealed interface MenuEntry

data class MenuAction(
    val label: String,
    val shortcut: String? = null,
    val danger: Boolean = false,
    val enabled: Boolean = true,
    /** Shows a check mark (true) or leaves room for one (false); null = not a toggle. */
    val checked: Boolean? = null,
    /** Keeps the menu open after the action (for steppers like "Fine +1%"). */
    val keepOpen: Boolean = false,
    val onClick: () -> Unit,
) : MenuEntry

data object MenuDivider : MenuEntry

data class MenuHeader(val label: String) : MenuEntry

/** A paragraph of help text inside the menu. */
data class MenuInfo(val text: String) : MenuEntry

class MenuBuilder {
    val entries = ArrayList<MenuEntry>()
    fun item(label: String, shortcut: String? = null, danger: Boolean = false, enabled: Boolean = true, checked: Boolean? = null, keepOpen: Boolean = false, onClick: () -> Unit) {
        entries.add(MenuAction(label, shortcut, danger, enabled, checked, keepOpen, onClick))
    }
    fun divider() { if (entries.isNotEmpty() && entries.last() !is MenuDivider) entries.add(MenuDivider) }
    fun header(label: String) { entries.add(MenuHeader(label)) }
    fun info(text: String) { entries.add(MenuInfo(text)) }
    fun addAll(other: List<MenuEntry>) { entries.addAll(other) }
}

fun menu(block: MenuBuilder.() -> Unit): List<MenuEntry> = MenuBuilder().apply(block).entries.let { l -> if (l.lastOrNull() is MenuDivider) l.dropLast(1) else l }

/** Where (and whether) a context menu is open, relative to its anchor. */
@Stable
class ContextMenuState {
    var open by mutableStateOf(false)
        private set
    var at by mutableStateOf(Offset.Zero)
        private set
    internal var anchorH by mutableStateOf(0f)

    fun show(position: Offset = Offset.Zero) { at = position; open = true }
    /** Opens just below the anchor (for buttons and chips). */
    fun showBelow() = show(Offset(0f, anchorH))
    fun dismiss() { open = false }
}

@Composable
fun rememberContextMenu() = remember { ContextMenuState() }

/**
 * The menu itself. Put it inside the anchor's [Box] (after the content); [entries] is only built while
 * the menu is open, so it can read current state freely.
 */
@Composable
fun ContextMenuPopup(state: ContextMenuState, entries: () -> List<MenuEntry>) {
    val d = LocalDensity.current
    val offset = with(d) { DpOffset(state.at.x.toDp(), (state.at.y - state.anchorH).toDp()) }
    DropdownMenu(state.open, { state.dismiss() }, offset = offset, containerColor = NM.surface) {
        for (e in entries()) when (e) {
            is MenuAction -> DropdownMenuItem(
                text = { Text(e.label, color = if (e.danger) NM.record else if (e.enabled) NM.text else NM.textDim, fontSize = 14.sp) },
                onClick = { if (!e.keepOpen) state.dismiss(); e.onClick() },
                enabled = e.enabled,
                leadingIcon = if (e.checked == null) null else {
                    { if (e.checked) Icon(Icons.Filled.Check, null, tint = NM.accent) else Box(Modifier.widthIn(24.dp)) }
                },
                trailingIcon = if (e.shortcut == null) null else {
                    { Text(e.shortcut, fontSize = 12.sp, color = NM.textDim) }
                },
            )
            MenuDivider -> HorizontalDivider(color = NM.line)
            is MenuHeader -> Text(e.label.uppercase(), fontSize = 10.sp, color = NM.textDim, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp))
            is MenuInfo -> Text(e.text, fontSize = 12.sp, color = NM.text, lineHeight = 16.sp,
                modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}

/**
 * Remembers where the last press landed (without consuming it) so a long press that only reports
 * "it happened" can open its menu at the finger.
 */
fun Modifier.trackPress(state: ContextMenuState, last: (Offset) -> Unit): Modifier = this
    .onSizeChanged { state.anchorH = it.height.toFloat() }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                e.changes.firstOrNull { it.pressed && !it.previousPressed }?.let { last(it.position) }
            }
        }
    }

/**
 * A tappable area with a context menu on touch & hold and on right-click. The menu opens at the press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMenuBox(
    entries: () -> List<MenuEntry>,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    state: ContextMenuState = rememberContextMenu(),
    content: @Composable BoxScope.() -> Unit,
) {
    val lastPress = remember { floatArrayOf(0f, 0f) }
    Box(
        modifier
            .trackPress(state) { lastPress[0] = it.x; lastPress[1] = it.y }
            .onSecondaryClick { state.show(it) }
            .combinedClickable(
                onClick = { onClick?.invoke() },
                onDoubleClick = onDoubleClick,
                onLongClick = { state.show(Offset(lastPress[0], lastPress[1])) },
            ),
    ) {
        content()
        ContextMenuPopup(state, entries)
    }
}

/**
 * Adds a context menu to an existing anchor without taking over its taps: touch & hold (when no other
 * gesture claims the press) and right-click open the menu. Use with a [ContextMenuPopup] in the same Box.
 */
fun Modifier.contextMenu(state: ContextMenuState, hold: Boolean = true): Modifier = this
    .onSizeChanged { state.anchorH = it.height.toFloat() }
    .onSecondaryClick { state.show(it) }
    .then(if (hold) Modifier.pointerInput(state) {
        detectTapGestures(onLongPress = { state.show(it) })
    } else Modifier)

/** Lets [showBelow] know the anchor's height. */
fun Modifier.menuAnchor(state: ContextMenuState): Modifier = onSizeChanged { state.anchorH = it.height.toFloat() }
