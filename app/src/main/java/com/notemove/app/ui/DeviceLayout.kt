package com.notemove.app.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * How the studio arranges itself on the current display. Tuned for the Galaxy Z Fold family:
 *
 * - [Mode.COVER]    — the tall, narrow outer (cover) screen: one panel at a time, Note-style.
 * - [Mode.SPREAD]   — the near-square inner screen, flat: session + editor beside a Move-style 4×8 pad surface.
 * - [Mode.TABLETOP] — inner screen half-folded with the hinge horizontal: clips above the hinge, pads below,
 *                     like having Move on the table.
 * - [Mode.BOOK]     — inner screen half-folded with the hinge vertical: the two halves become two panes.
 *
 * Any other phone or tablet falls into these by size (narrow = COVER, wide = SPREAD).
 */
data class DeviceLayout(
    val mode: Mode,
    val widthDp: Int,
    val heightDp: Int,
    /** Position of the hinge from the top (TABLETOP) or left (BOOK) of the window, in dp. */
    val hingeDp: Dp = 0.dp,
    /** Hinge thickness (0 on a seamless Fold display). */
    val hingeSizeDp: Dp = 0.dp,
) {
    enum class Mode { COVER, SPREAD, TABLETOP, BOOK }

    val landscape get() = widthDp > heightDp
    /** Compact cover screens turned sideways get a two-column layout too. */
    val wideCover get() = mode == Mode.COVER && landscape && widthDp >= 640
}

@Composable
fun rememberDeviceLayout(activity: Activity): DeviceLayout {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    var fold by remember { mutableStateOf<FoldingFeature?>(null) }
    LaunchedEffect(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        }
    }
    val w = config.screenWidthDp
    val h = config.screenHeightDp
    val f = fold
    if (f != null && f.state == FoldingFeature.State.HALF_OPENED) {
        // Window bounds are in pixels relative to the window; we draw edge-to-edge from (0,0).
        val b = f.bounds
        return if (f.orientation == FoldingFeature.Orientation.HORIZONTAL) {
            DeviceLayout(DeviceLayout.Mode.TABLETOP, w, h, with(density) { b.top.toDp() }, with(density) { b.height().toDp() })
        } else {
            DeviceLayout(DeviceLayout.Mode.BOOK, w, h, with(density) { b.left.toDp() }, with(density) { b.width().toDp() })
        }
    }
    // The inner Fold display is ~ 4:3.6 and >= 600dp wide; the cover display is ~ 21:9 and narrow.
    val shortSide = minOf(w, h)
    val mode = if (shortSide >= 600) DeviceLayout.Mode.SPREAD else DeviceLayout.Mode.COVER
    return DeviceLayout(mode, w, h)
}
