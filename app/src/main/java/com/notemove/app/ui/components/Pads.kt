package com.notemove.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notemove.app.ui.theme.NM
import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.DrumKits
import com.notemove.core.model.Project
import com.notemove.core.model.Scale
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind

/** What one pad shows and plays. */
data class PadSpec(
    val id: Int,
    val label: String,
    val base: Color,
    val lit: Boolean,
    val accent: Boolean = false,
    val dim: Boolean = false,
    val selected: Boolean = false,
)

/**
 * A multi-touch pad grid. Row 0 is the bottom row, as on Push, Move and Note.
 * Every finger is tracked independently; sliding a finger onto another pad plays it (glissando).
 * Velocity comes from touch pressure when the screen reports it, otherwise from where the pad is hit
 * (top edge = loudest).
 */
@Composable
fun PadGrid(
    rows: Int,
    cols: Int,
    spec: (row: Int, col: Int) -> PadSpec?,
    onDown: (PadSpec, velocity: Int) -> Unit,
    onUp: (PadSpec) -> Unit,
    modifier: Modifier = Modifier,
    gap: Int = 6,
    slide: Boolean = true,
) {
    val specFn by rememberUpdatedState(spec)
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val active = remember { HashMap<PointerId, PadSpec>() }
    BoxWithConstraints(modifier) {
        val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { gap.dp.toPx() }
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(rows, cols) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val cellW = (w + gapPx) / cols
                            val cellH = (h + gapPx) / rows
                            for (c in event.changes) {
                                val x = c.position.x
                                val y = c.position.y
                                val col = (x / cellW).toInt()
                                val rowFromTop = (y / cellH).toInt()
                                val inside = x in 0f..w && y in 0f..h && col in 0 until cols && rowFromTop in 0 until rows
                                val hit = if (inside) specFn(rows - 1 - rowFromTop, col) else null
                                val prev = active[c.id]
                                if (c.pressed) {
                                    if (prev == null && !c.previousPressed && hit != null) {
                                        active[c.id] = hit
                                        down(hit, velocityFor(c.pressure, (y - rowFromTop * cellH) / (cellH - gapPx)))
                                    } else if (prev != null && slide && hit != null && hit.id != prev.id) {
                                        up(prev)
                                        active[c.id] = hit
                                        down(hit, velocityFor(c.pressure, (y - rowFromTop * cellH) / (cellH - gapPx)))
                                    }
                                    c.consume()
                                } else if (prev != null) {
                                    active.remove(c.id)
                                    up(prev)
                                    c.consume()
                                }
                            }
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(gap.dp),
        ) {
            for (rowFromTop in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                    for (col in 0 until cols) {
                        val s = spec(rows - 1 - rowFromTop, col)
                        Pad(s, Modifier.weight(1f).fillMaxSize())
                    }
                }
            }
        }
    }
}

private fun velocityFor(pressure: Float, yFrac: Float): Int {
    // Many screens report a constant 1.0 pressure; only trust values that actually vary.
    if (pressure > 0.05f && pressure < 0.99f) return (30 + pressure * 97).toInt().coerceIn(1, 127)
    val f = 1f - yFrac.coerceIn(0f, 1f)
    return (45 + f * 82).toInt().coerceIn(1, 127)
}

@Composable
private fun Pad(s: PadSpec?, modifier: Modifier) {
    if (s == null) { Box(modifier.clip(RoundedCornerShape(10.dp)).background(NM.padDim)); return }
    val bg = when {
        s.lit -> s.base
        s.accent -> s.base.copy(alpha = 0.55f)
        s.dim -> NM.padDim
        else -> NM.pad
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(if (s.selected) Modifier.border(2.dp, NM.text.copy(alpha = 0.8f), RoundedCornerShape(10.dp)) else Modifier),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (s.label.isNotEmpty()) Text(
            s.label, Modifier.padding(6.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
            color = if (s.lit) Color.Black else NM.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start,
        )
    }
}

// ------------------------------------------------------------------------------------------
// Layout helpers: map grid cells to notes for drum and melodic tracks.
// ------------------------------------------------------------------------------------------

object PadLayouts {
    /** Pitch at (row, col) for an isomorphic melodic layout: rows go up a fourth, columns one step. */
    fun melodicPitch(project: Project, row: Int, col: Int, octave: Int, inKey: Boolean): Int {
        return if (inKey) project.scale.degreeToPitch(row * 3 + col, project.rootNote, octave)
        else (octave + 2) * 12 + project.rootNote + row * 5 + col
    }

    fun melodicSpec(project: Project, track: Track, row: Int, col: Int, octave: Int, inKey: Boolean, held: Set<Int>, playing: Set<Int>): PadSpec? {
        val pitch = melodicPitch(project, row, col, octave, inKey)
        if (pitch !in 0..127) return null
        val color = NM.track(track.color)
        val isRoot = Math.floorMod(pitch - project.rootNote, 12) == 0
        val inScale = project.scale.contains(pitch, project.rootNote)
        return PadSpec(
            id = pitch,
            label = if (isRoot || (row == 0 && col == 0)) Scale.noteName(pitch) else "",
            base = color,
            lit = pitch in held || pitch in playing,
            accent = isRoot,
            dim = !inScale,
        )
    }

    fun drumSpec(track: Track, pad: Int, held: Set<Int>, playing: Set<Int>, selected: Int): PadSpec {
        val kit = track.drumKit ?: DrumKits.KIT_808
        val pitch = DRUM_BASE_NOTE + pad
        return PadSpec(
            id = pitch,
            label = kit.pads.getOrNull(pad)?.name ?: "",
            base = NM.track(track.color),
            lit = pitch in held || pitch in playing,
            selected = pad == selected,
        )
    }

    fun isDrums(track: Track) = track.kind == TrackKind.DRUMS
}
