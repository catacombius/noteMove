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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
    /** Called when a finger rests on one pad for [holdMillis] without sliding (the note is released first). */
    onHold: ((PadSpec) -> Unit)? = null,
    holdMillis: Long = 600,
) {
    val specFn by rememberUpdatedState(spec)
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    val hold by rememberUpdatedState(onHold)
    val active = remember { HashMap<PointerId, PadSpec>() }
    val downAt = remember { HashMap<PointerId, Long>() }
    val ignored = remember { HashSet<PointerId>() }
    BoxWithConstraints(modifier) {
        val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { gap.dp.toPx() }
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(rows, cols) {
                    awaitPointerEventScope {
                        while (true) {
                            // Poll while fingers are down so a motionless hold can be detected.
                            val event = if (hold != null && active.isNotEmpty()) withTimeoutOrNull(50L) { awaitPointerEvent() } else awaitPointerEvent()
                            if (event == null) {
                                val now = System.currentTimeMillis()
                                for ((id, padSpec) in active.entries.toList()) {
                                    if (now - (downAt[id] ?: now) >= holdMillis) {
                                        active.remove(id); downAt.remove(id)
                                        up(padSpec)
                                        hold?.invoke(padSpec)
                                    }
                                }
                                continue
                            }
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val cellW = (w + gapPx) / cols
                            val cellH = (h + gapPx) / rows
                            // Mouse right-click on a pad = touch & hold (options) without playing it.
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                val c = event.changes.first()
                                val col = (c.position.x / cellW).toInt()
                                val rowFromTop = (c.position.y / cellH).toInt()
                                if (col in 0 until cols && rowFromTop in 0 until rows) specFn(rows - 1 - rowFromTop, col)?.let { hold?.invoke(it) }
                                event.changes.forEach { it.consume(); ignored.add(it.id) }
                                continue
                            }
                            for (c in event.changes) {
                                if (c.id in ignored) { c.consume(); if (!c.pressed) ignored.remove(c.id); continue }
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
                                        downAt[c.id] = System.currentTimeMillis()
                                        down(hit, velocityFor(c.pressure, (y - rowFromTop * cellH) / (cellH - gapPx)))
                                    } else if (prev != null && slide && hit != null && hit.id != prev.id) {
                                        up(prev)
                                        active[c.id] = hit
                                        downAt[c.id] = System.currentTimeMillis()
                                        down(hit, velocityFor(c.pressure, (y - rowFromTop * cellH) / (cellH - gapPx)))
                                    }
                                    c.consume()
                                } else if (prev != null) {
                                    active.remove(c.id)
                                    downAt.remove(c.id)
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
    /**
     * Pitch at (row, col) for an isomorphic melodic layout (Push style): columns step through the scale,
     * rows go up a 4th / 3rd, or continue sequentially from the row below.
     */
    fun melodicPitch(project: Project, row: Int, col: Int, octave: Int, inKey: Boolean,
                     layout: com.notemove.app.ui.RowLayout = com.notemove.app.ui.RowLayout.FOURTHS, cols: Int = 8): Int {
        return if (inKey) {
            val step = if (layout.inKeySteps > 0) layout.inKeySteps else cols
            project.scale.degreeToPitch(row * step + col, project.rootNote, octave)
        } else {
            val step = if (layout.semitones > 0) layout.semitones else cols
            (octave + 2) * 12 + project.rootNote + row * step + col
        }
    }

    fun melodicSpec(project: Project, track: Track, row: Int, col: Int, octave: Int, inKey: Boolean, held: Set<Int>, playing: Set<Int>,
                    layout: com.notemove.app.ui.RowLayout = com.notemove.app.ui.RowLayout.FOURTHS, cols: Int = 8, selected: Int? = null): PadSpec? {
        val pitch = melodicPitch(project, row, col, octave, inKey, layout, cols)
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
            selected = selected == pitch,
        )
    }

    fun drumSpec(track: Track, pad: Int, held: Set<Int>, playing: Set<Int>, selected: Int): PadSpec {
        val pitch = DRUM_BASE_NOTE + pad
        return PadSpec(
            id = pitch,
            label = padName(track, pad),
            base = NM.track(track.color),
            lit = pitch in held || pitch in playing,
            selected = pad == selected,
        )
    }

    fun isDrums(track: Track) = track.drumLayout

    /** Pad label: the kit's pad name, or the General MIDI drum name for SoundFont drum kits. */
    fun padName(track: Track, pad: Int): String =
        if (track.kind == TrackKind.DRUMS) (track.drumKit ?: DrumKits.KIT_808).pads.getOrNull(pad)?.name ?: ""
        else com.notemove.core.dsp.GmDrums.name(DRUM_BASE_NOTE + pad)
}
