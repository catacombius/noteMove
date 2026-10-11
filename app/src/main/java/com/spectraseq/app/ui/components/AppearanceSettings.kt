package com.spectraseq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spectraseq.app.ui.theme.Appearance
import com.spectraseq.app.ui.theme.NM
import com.spectraseq.app.ui.theme.Palette
import com.spectraseq.app.ui.theme.Palettes
import com.spectraseq.app.ui.theme.TrackColorSet
import com.spectraseq.app.ui.theme.UiScale

/** Theme, accent colour, track colours and interface size. Changes apply immediately and are remembered. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection() {
    val context = LocalContext.current
    fun save() = Appearance.save(context)

    SectionTitle("Theme")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in Palettes.ALL) PaletteSwatch(p, NM.palette == p) { NM.palette = p; save() }
    }
    SectionTitle("Accent colour")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(NM.palette.accent)
                .border(2.dp, if (NM.accentOverride == null) NM.text else Color.Transparent, CircleShape)
                .clickable { NM.accentOverride = null; save() },
            contentAlignment = Alignment.Center,
        ) { Text("A", fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Bold) }
        for (c in Palettes.ACCENTS) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c)
                    .border(2.dp, if (NM.accentOverride == c) NM.text else Color.Transparent, CircleShape)
                    .clickable { NM.accentOverride = c; save() },
            )
        }
    }
    HueSatPicker(NM.accent, Modifier.fillMaxWidth().padding(top = 8.dp)) { NM.accentOverride = it; save() }
    Text("\"A\" uses the theme's own accent.", fontSize = 11.sp, color = NM.textDim)

    SectionTitle("Track colours")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (s in TrackColorSet.entries) Chip(s.label, NM.trackSet == s, { NM.trackSet = s; save() })
    }
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 0 until 16) Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(NM.track(i)))
    }

    SectionTitle("Interface size")
    ScaleRow("Outer (cover) screen", UiScale.cover) { UiScale.cover = it; save() }
    ScaleRow("Inner screen", UiScale.inner) { UiScale.inner = it; save() }
    Text("Each screen keeps its own size, so folding and unfolding switches automatically.", fontSize = 11.sp, color = NM.textDim)
}

@Composable
private fun ScaleRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = NM.text, modifier = Modifier.width(150.dp))
        // Snap to 5 % steps so the layout doesn't re-flow on every pixel of the drag.
        Slider(value, { onChange((Math.round(it * 20) / 20f)) }, valueRange = 0.8f..1.5f, steps = 13, modifier = Modifier.weight(1f))
        Text("${Math.round(value * 100)}%", fontSize = 12.sp, color = NM.textDim, modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}

@Composable
private fun PaletteSwatch(p: Palette, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(92.dp).clip(RoundedCornerShape(10.dp)).background(p.bg)
            .border(2.dp, if (selected) NM.accent else p.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(p.surfaceHigh))
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(p.pad))
            Box(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(p.accent))
        }
        Text(p.name, fontSize = 12.sp, color = p.text, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Hue across, saturation down; the colour is always bright enough to read on dark backgrounds. */
@Composable
fun HueSatPicker(color: Color, modifier: Modifier = Modifier, onPick: (Color) -> Unit) {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color.toArgb(), it) }
    fun pick(x: Float, y: Float, w: Float, h: Float) {
        val hue = (x / w).coerceIn(0f, 1f) * 360f
        val sat = 1f - (y / h).coerceIn(0f, 1f) * 0.8f
        onPick(Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, 1f))))
    }
    Canvas(
        modifier.height(56.dp).clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { o -> pick(o.x, o.y, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume(); pick(c.position.x, c.position.y, size.width.toFloat(), size.height.toFloat()) } },
    ) {
        val hues = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }
        drawRect(Brush.horizontalGradient(hues))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.8f))))
        val x = hsv[0] / 360f * size.width
        val y = ((1f - hsv[1]) / 0.8f).coerceIn(0f, 1f) * size.height
        drawCircle(Color.Black, 9.dp.toPx(), Offset(x, y), style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 9.dp.toPx(), Offset(x, y), style = Stroke(1.5.dp.toPx()))
        drawRoundRect(Color.Black.copy(alpha = 0.3f), cornerRadius = CornerRadius(8f), style = Stroke(1f))
    }
}
