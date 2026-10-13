package com.spectraseq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.CornerRadius
import kotlin.math.pow
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
import com.spectraseq.app.ui.theme.NM

/** Default knob size for a panel; [AdaptiveKnobs] picks it from the available width. */
val LocalKnobSize = staticCompositionLocalOf { 52.dp }

/** Gives knobs inside a size that suits the space: smaller on the cover screen, larger unfolded. */
@Composable
fun AdaptiveKnobs(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val size = when {
            maxWidth < 380.dp -> 44.dp
            maxWidth > 900.dp -> 60.dp
            else -> 52.dp
        }
        CompositionLocalProvider(LocalKnobSize provides size) { content() }
    }
}

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
    size: Dp = LocalKnobSize.current,
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
            // Inner cap with a position dot (SpectraSeq style) instead of a pointer line.
            val c = center
            val capR = arcSize.width / 2 - stroke * 1.6f
            drawCircle(NM.surfaceHigh, capR, c)
            drawCircle(NM.line, capR, c, style = Stroke(1.dp.toPx()))
            val angle = Math.toRadians((135.0 + value * 270.0))
            val dr = capR - 4.dp.toPx()
            drawCircle(NM.text, 2.6.dp.toPx(), Offset(c.x + (dr * kotlin.math.cos(angle)).toFloat(), c.y + (dr * kotlin.math.sin(angle)).toFloat()))
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
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = NM.accent, onLongClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = 0.9f) else NM.surfaceHigh)
            .then(if (onLongClick != null) Modifier.holdClickable(onClick, onLongClick) else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.Black else NM.text, maxLines = 1)
    }
}

/**
 * Vertical fader: a rounded channel filled flat with the track colour, a segmented level meter with peak
 * hold beside it, and a round cap that shows the gain in dB while you drag. Drag, wheel or tap to set;
 * double-tap resets to 0 dB; touch & hold or right-click for preset levels.
 */
@Composable
fun Fader(value: Float, onChange: (Float) -> Unit, level: Float, color: Color, modifier: Modifier = Modifier) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }
    val faderMenu = rememberContextMenu()
    // Peak hold: jumps up instantly, falls back slowly.
    val peak = remember { FloatArray(1) }
    val peakAt = remember { LongArray(1) }
    val now = System.nanoTime()
    val meterPos = levelToMeter(level)
    if (meterPos >= peak[0] || now - peakAt[0] > 1_200_000_000L) { peak[0] = meterPos; peakAt[0] = now }
    val measurer = rememberTextMeasurer()
    Box(modifier.width(52.dp).menuAnchor(faderMenu)) {
    Canvas(
        Modifier.matchParentSize()
            .onWheel { d, mods -> change((current - d.y * if (mods.isShiftPressed) 0.005f else 0.03f).coerceIn(0f, 1f)) }
            .onSecondaryClick { faderMenu.show(it) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { c, dy -> c.consume(); change((current - dy / size.height).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { change(0.8f) }, onLongPress = { faderMenu.show(it) }) { o -> change((1f - o.y / size.height).coerceIn(0f, 1f)) }
            },
    ) {
        val w = size.width
        val h = size.height
        val capR = 11.dp.toPx()
        val top = capR
        val travel = h - capR * 2
        fun yOf(v: Float) = top + travel * (1f - v)

        // Level meter (left column): 24 flat segments in the track colour; the ones above 0 dBFS light red.
        val segs = 24
        val mx = 2.dp.toPx()
        val mw = 7.dp.toPx()
        val segH = travel / segs
        val clipFrom = 60f / 66f
        for (i in 0 until segs) {
            val frac = (i + 0.5f) / segs
            val on = frac <= meterPos
            val c = if (frac > clipFrom) NM.record else color
            drawRoundRect(if (on) c else NM.padDim, Offset(mx, top + travel - (i + 1) * segH + 1f), Size(mw, segH - 2f), CornerRadius(2f))
        }
        val py = top + travel * (1f - peak[0])
        if (peak[0] > 0.02f) drawLine(NM.text, Offset(mx, py), Offset(mx + mw, py), 2f)

        // Channel (pill) filled with one flat colour up to the fader position.
        val cx = mx + mw + 6.dp.toPx()
        val cw = w - cx - 2.dp.toPx()
        val radius = CornerRadius(cw / 2)
        drawRoundRect(NM.padDim, Offset(cx, top - cw / 4), Size(cw, travel + cw / 2), radius)
        val fy = yOf(value)
        drawRoundRect(color.copy(alpha = 0.55f), Offset(cx, fy), Size(cw, top + travel + cw / 4 - fy), radius)
        // dB ticks: 0, -6, -12, -24
        for (db in intArrayOf(0, -6, -12, -24)) {
            val ty = yOf(dbToFader(db.toFloat()))
            drawLine(if (db == 0) NM.text.copy(alpha = 0.7f) else NM.textDim.copy(alpha = 0.45f), Offset(cx + cw * 0.18f, ty), Offset(cx + cw * 0.82f, ty), if (db == 0) 2f else 1f)
        }
        // Cap
        val capC = Offset(cx + cw / 2, fy)
        drawCircle(NM.bg, capR + 2.dp.toPx(), capC)
        drawCircle(NM.surfaceHigh, capR, capC)
        drawCircle(color, capR, capC, style = Stroke(2.5.dp.toPx()))
        drawCircle(color, 3.dp.toPx(), capC)
        if (dragging) {
            val label = measurer.measure(faderDb(value), TextStyle(fontSize = 11.sp, color = NM.text, fontWeight = FontWeight.Bold))
            val bx = (capC.x - label.size.width / 2f).coerceIn(0f, w - label.size.width)
            val by = (fy - capR - label.size.height - 6.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(NM.bg, Offset(bx - 4.dp.toPx(), by - 2.dp.toPx()),
                Size(label.size.width + 8.dp.toPx(), label.size.height + 4.dp.toPx()), CornerRadius(6f))
            drawText(label, topLeft = Offset(bx, by))
        }
    }
    ContextMenuPopup(faderMenu) {
        menu {
            header(faderDb(current))
            for (db in intArrayOf(3, 0, -3, -6, -12, -24)) item(if (db == 0) "0 dB (reset)" else String.format(java.util.Locale.ROOT, "%+d dB", db)) { change(dbToFader(db.toFloat())) }
            item("−∞ (silent)") { change(0f) }
            divider()
            item("Fine +0.5 dB", keepOpen = true) { change(nudgeDb(current, 0.5f)) }
            item("Fine −0.5 dB", keepOpen = true) { change(nudgeDb(current, -0.5f)) }
        }
    }
    }
}

private fun nudgeDb(v: Float, db: Float): Float {
    val gain = v * v / 0.64f
    if (gain <= 0.0001f) return if (db > 0) dbToFader(-60f) else 0f
    return dbToFader(20 * kotlin.math.log10(gain) + db)
}

/** Fader position 0..1 for a gain in dB (0.8 = 0 dB, gain = v² / 0.64). */
fun dbToFader(db: Float): Float = kotlin.math.sqrt(0.64f * 10f.pow(db / 20f)).coerceIn(0f, 1f)

fun faderDb(v: Float): String {
    val gain = v * v / 0.64f
    if (gain <= 0.0001f) return "−∞ dB"
    val db = 20 * kotlin.math.log10(gain)
    return String.format(java.util.Locale.ROOT, "%+.1f dB", db)
}

/** Meter position 0..1 for a peak level: −60 dB … +6 dB. */
private fun levelToMeter(level: Float): Float {
    if (level <= 0.001f) return 0f
    return ((20 * kotlin.math.log10(level) + 60f) / 66f).coerceIn(0f, 1f)
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
    Row(modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/**
 * A title bar whose action buttons move to a second, scrollable row when the window is narrow (cover
 * screen, split screen, large interface size), so the title never gets squeezed away.
 */
@Composable
fun AdaptiveBar(
    modifier: Modifier = Modifier,
    breakpoint: Dp = 600.dp,
    leading: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= breakpoint) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { leading(); actions() }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { leading() }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        }
    }
}
