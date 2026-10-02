package com.notemove.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.theme.NM

/**
 * Rotary knob (like the encoders on Move). Drag up/down to change, double-tap to reset.
 * [value] is normalised 0..1; [display] renders the value label.
 */
@Composable
fun Knob(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = NM.accent,
    default: Float = 0.5f,
    bipolar: Boolean = false,
    size: Dp = 52.dp,
    display: (Float) -> String = { "${(it * 100).toInt()}" },
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var menu by remember { mutableStateOf(false) }
    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
        Canvas(
            Modifier
                .size(size)
                // Mouse: wheel turns the knob (Shift = fine), right-click opens the menu.
                .onWheel { d, mods -> change((current - (d.y + d.x) * if (mods.isShiftPressed) 0.005f else 0.03f).coerceIn(0f, 1f)) }
                .onSecondaryClick { menu = true }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { c, dy ->
                        c.consume()
                        change((current - dy / (size.toPx() * 3.5f)).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(default) { detectTapGestures(onDoubleTap = { change(default) }, onLongPress = { menu = true }) },
        ) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2 + 2f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(NM.line, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val start = if (bipolar) 270f else 135f
            val sweep = if (bipolar) (value - 0.5f) * 270f else value * 270f
            drawArc(color, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            val angle = Math.toRadians((135.0 + value * 270.0))
            val r = arcSize.width / 2 - stroke
            val c = center
            drawLine(NM.text, c, Offset(c.x + (r * kotlin.math.cos(angle)).toFloat(), c.y + (r * kotlin.math.sin(angle)).toFloat()), 3.dp.toPx(), StrokeCap.Round)
        }
        // Touch & hold a knob for precise values.
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Reset (${display(default)})") }, { menu = false; change(default) })
            DropdownMenuItem({ Text("Minimum") }, { menu = false; change(0f) })
            DropdownMenuItem({ Text("Centre") }, { menu = false; change(0.5f) })
            DropdownMenuItem({ Text("Maximum") }, { menu = false; change(1f) })
            DropdownMenuItem({ Text("Fine +1%") }, { change((current + 0.01f).coerceAtMost(1f)) })
            DropdownMenuItem({ Text("Fine −1%") }, { change((current - 0.01f).coerceAtLeast(0f)) })
        }
        }
        Text(display(value), fontSize = 11.sp, color = NM.text, maxLines = 1)
        Text(label, fontSize = 10.sp, color = NM.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Small selectable chip used for modes, grids and presets. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = NM.accent) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.9f) else NM.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.Black else NM.text, maxLines = 1)
    }
}

/** Vertical fader with a level meter, used in the mixer. */
@Composable
fun Fader(value: Float, onChange: (Float) -> Unit, level: Float, color: Color, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .width(44.dp)
            .onWheel { d, mods -> change((current - d.y * if (mods.isShiftPressed) 0.005f else 0.03f).coerceIn(0f, 1f)) }
            .pointerInput(Unit) {
                detectVerticalDragGestures { c, dy -> c.consume(); change((current - dy / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { change(0.8f) }) { o -> change((1f - o.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(NM.padDim, size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f))
        // meter
        val lv = (level.coerceIn(0f, 1.2f) / 1.2f)
        drawRect(if (level > 0.98f) NM.record else NM.play.copy(alpha = 0.8f), Offset(4f, h * (1 - lv)), Size(8f, h * lv))
        // fill
        drawRect(color.copy(alpha = 0.35f), Offset(16f, h * (1 - value)), Size(w - 20f, h * value))
        drawRect(color, Offset(12f, h * (1 - value) - 3f), Size(w - 12f, 6f))
        // 0 dB mark (value 0.8)
        drawLine(NM.textDim, Offset(14f, h * 0.2f), Offset(w, h * 0.2f), 1f)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier.padding(top = 8.dp, bottom = 4.dp), fontSize = 11.sp, color = NM.textDim, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
fun ToggleBox(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onColor: Color = NM.accent) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (on) onColor else NM.line, RoundedCornerShape(6.dp))
            .background(if (on) onColor.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 12.sp, color = if (on) onColor else NM.textDim, fontWeight = FontWeight.Bold) }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
