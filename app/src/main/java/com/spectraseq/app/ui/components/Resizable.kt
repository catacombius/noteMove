package com.spectraseq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spectraseq.app.ui.theme.NM

/**
 * Two panes with a draggable divider. Drag the handle to resize, double-tap it to reset.
 * [fraction] is the share of the first pane; it is clamped so neither pane gets smaller than [minPane].
 */
@Composable
fun SplitPane(
    fraction: Float,
    onFraction: (Float) -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    default: Float = 0.5f,
    minPane: Dp = 96.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val onF by rememberUpdatedState(onFraction)
    // Local value while dragging (smooth), committed on release.
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(fraction) }
    val f = if (dragging) local else fraction
    BoxWithConstraints(modifier) {
        val totalPx = with(density) { (if (vertical) maxHeight else maxWidth).toPx() }
        val handle = 14.dp
        val handlePx = with(density) { handle.toPx() }
        val minF = (with(density) { minPane.toPx() } / totalPx).coerceIn(0.05f, 0.45f)
        val clamped = f.coerceIn(minF, 1f - minF)
        val firstSize = with(density) { ((totalPx - handlePx) * clamped).toDp() }
        val handleMod = Modifier.pointerInput(totalPx, vertical) {
            detectDragGestures(
                onDragStart = { dragging = true; local = clamped },
                onDragEnd = { dragging = false; onF(local) },
                onDragCancel = { dragging = false },
            ) { c, d ->
                c.consume()
                local = (local + (if (vertical) d.y else d.x) / (totalPx - handlePx)).coerceIn(minF, 1f - minF)
            }
        }.pointerInput(default) { detectTapGestures(onDoubleTap = { onF(default) }) }
        if (vertical) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(firstSize)) { first() }
                Box(handleMod.fillMaxWidth().height(handle), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(48.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (dragging) NM.accent else NM.line))
                }
                Box(Modifier.fillMaxWidth().weight(1f)) { second() }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxHeight().width(firstSize)) { first() }
                Box(handleMod.fillMaxHeight().width(handle), contentAlignment = Alignment.Center) {
                    Box(Modifier.height(48.dp).width(4.dp).clip(RoundedCornerShape(2.dp)).background(if (dragging) NM.accent else NM.line))
                }
                Box(Modifier.fillMaxHeight().weight(1f)) { second() }
            }
        }
    }
}
