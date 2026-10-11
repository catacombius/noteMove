package com.spectraseq.app.ui.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

/**
 * A 2D window onto zoomable content (piano roll, step grid, song timeline): scroll offset in pixels and an
 * independent zoom per axis. Content size at zoom 1 is set by the owner every composition ([baseW], [baseH]).
 */
@Stable
class ViewportState(
    zoomX: Float = 1f,
    zoomY: Float = 1f,
    val minZoomX: Float = 0.25f,
    val maxZoomX: Float = 6f,
    val minZoomY: Float = 0.5f,
    val maxZoomY: Float = 3f,
) {
    var scrollX by mutableFloatStateOf(0f)
        private set
    var scrollY by mutableFloatStateOf(0f)
        private set
    var zoomX by mutableFloatStateOf(zoomX)
        private set
    var zoomY by mutableFloatStateOf(zoomY)
        private set
    /** Visible size in pixels. */
    var viewW by mutableFloatStateOf(1f)
    var viewH by mutableFloatStateOf(1f)
    /** Content size at zoom 1, in pixels. */
    var baseW by mutableFloatStateOf(1f)
    var baseH by mutableFloatStateOf(1f)

    val contentW get() = baseW * zoomX
    val contentH get() = baseH * zoomY
    internal var fling: Job? = null

    fun scrollTo(x: Float = scrollX, y: Float = scrollY) {
        scrollX = x.coerceIn(0f, max(0f, contentW - viewW))
        scrollY = y.coerceIn(0f, max(0f, contentH - viewH))
    }

    fun scrollBy(dx: Float, dy: Float) = scrollTo(scrollX + dx, scrollY + dy)

    /** Zooms by [fx] / [fy] keeping the content under [focus] (a point in the view) in place. */
    fun zoomBy(fx: Float, fy: Float, focus: Offset) {
        val nzx = (zoomX * fx).coerceIn(minZoomX, maxZoomX)
        val nzy = (zoomY * fy).coerceIn(minZoomY, maxZoomY)
        val cx = (scrollX + focus.x) / zoomX
        val cy = (scrollY + focus.y) / zoomY
        zoomX = nzx; zoomY = nzy
        scrollTo(cx * nzx - focus.x, cy * nzy - focus.y)
    }

    fun setZoom(x: Float = zoomX, y: Float = zoomY) {
        zoomX = x.coerceIn(minZoomX, maxZoomX); zoomY = y.coerceIn(minZoomY, maxZoomY)
        scrollTo()
    }

    /** Converts a point in the view to content coordinates. */
    fun toContent(o: Offset) = Offset(o.x + scrollX, o.y + scrollY)

    internal fun startFling(scope: CoroutineScope, velocity: Offset, density: Density) {
        fling?.cancel()
        if (abs(velocity.x) < 50f && abs(velocity.y) < 50f) return
        fling = scope.launch {
            val decay = splineBasedDecay<Float>(density)
            launch {
                var last = 0f
                AnimationState(0f, -velocity.x).animateDecay(decay) { scrollBy(value - last, 0f); last = value }
            }
            launch {
                var last = 0f
                AnimationState(0f, -velocity.y).animateDecay(decay) { scrollBy(0f, value - last); last = value }
            }
        }
    }
}

/**
 * One-finger touch drag pans the viewport (both axes at once) with fling. It steps aside for gestures
 * other handlers own: mouse input, a press already consumed (e.g. a note's length handle), and a finger
 * that rests long enough to become a touch & hold.
 */
fun Modifier.viewportPan(state: ViewportState, panX: Boolean = true, panY: Boolean = true, requireUnconsumed: Boolean = true): Modifier = composed {
    val scope = rememberCoroutineScope()
    pointerInput(state, panX, panY, requireUnconsumed) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = requireUnconsumed)
            state.fling?.cancel()
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            var slopHit = false
            val started = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis - 40) {
                awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume(); slopHit = true }
            }
            if (started == null || !slopHit) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(started.uptimeMillis, started.position)
            drag(started.id) { c ->
                val d = c.positionChange()
                state.scrollBy(if (panX) -d.x else 0f, if (panY) -d.y else 0f)
                tracker.addPosition(c.uptimeMillis, c.position)
                c.consume()
            }
            val v = tracker.calculateVelocity()
            state.startFling(scope, Offset(if (panX) v.x else 0f, if (panY) v.y else 0f), this@pointerInput)
        }
    }
}

/**
 * Two-finger pinch and pan. The zoom follows the pinch direction: spreading horizontally zooms time,
 * vertically zooms rows (either can be switched off). Consumes the events so single-finger editing
 * gestures underneath cancel.
 */
fun Modifier.viewportPinch(state: ViewportState, zoomX: Boolean = true, zoomY: Boolean = true, pass: PointerEventPass = PointerEventPass.Main): Modifier =
    pointerInput(state, zoomX, zoomY) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(pass)
                val pts = e.changes.filter { it.pressed }
                if (pts.size < 2) continue
                state.fling?.cancel()
                val a = pts[0]; val b = pts[1]
                val prevA = a.previousPosition; val prevB = b.previousPosition
                val prevC = (prevA + prevB) / 2f
                val curC = (a.position + b.position) / 2f
                val minSpan = 48f
                val pdx = abs(prevA.x - prevB.x); val cdx = abs(a.position.x - b.position.x)
                val pdy = abs(prevA.y - prevB.y); val cdy = abs(a.position.y - b.position.y)
                val fx = if (zoomX && pdx > minSpan && cdx > minSpan) cdx / pdx else 1f
                val fy = if (zoomY && pdy > minSpan && cdy > minSpan) cdy / pdy else 1f
                state.scrollBy(prevC.x - curC.x, prevC.y - curC.y)
                if (fx != 1f || fy != 1f) state.zoomBy(fx, fy, curC)
                e.changes.forEach { it.consume() }
            }
        }
    }

/**
 * Free 2D drag for content inside a pair of [ScrollState]s (whose own scroll gestures are disabled),
 * so diagonal swipes move both ways at once, with fling. Children keep their taps and long presses.
 */
fun Modifier.pan2D(h: ScrollState, v: ScrollState): Modifier = composed {
    val scope = rememberCoroutineScope()
    var job: Job? = null
    pointerInput(h, v) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            job?.cancel()
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            var slopHit = false
            val started = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume(); slopHit = true }
            if (started == null || !slopHit) return@awaitEachGesture
            val tracker = VelocityTracker()
            drag(started.id) { c ->
                val d = c.positionChange()
                h.dispatchRawDelta(-d.x); v.dispatchRawDelta(-d.y)
                tracker.addPosition(c.uptimeMillis, c.position)
                c.consume()
            }
            val vel = tracker.calculateVelocity()
            val density = this@pointerInput
            job = scope.launch {
                val decay = splineBasedDecay<Float>(density)
                launch { var last = 0f; AnimationState(0f, -vel.x).animateDecay(decay) { h.dispatchRawDelta(value - last); last = value } }
                launch { var last = 0f; AnimationState(0f, -vel.y).animateDecay(decay) { v.dispatchRawDelta(value - last); last = value } }
            }
        }
    }
}

/**
 * Pinch with two fingers to scale something (e.g. session slot size). Runs before children
 * ([PointerEventPass.Initial]) and consumes, so the children's taps cancel.
 */
fun Modifier.pinchScale(onScale: (fx: Float, fy: Float) -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(onScale)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val pts = e.changes.filter { it.pressed }
                if (pts.size < 2) continue
                val a = pts[0]; val b = pts[1]
                val pdx = abs(a.previousPosition.x - b.previousPosition.x); val cdx = abs(a.position.x - b.position.x)
                val pdy = abs(a.previousPosition.y - b.previousPosition.y); val cdy = abs(a.position.y - b.position.y)
                cb(if (pdx > 48f && cdx > 48f) cdx / pdx else 1f, if (pdy > 48f && cdy > 48f) cdy / pdy else 1f)
                e.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * Push-style zoom for step sequencers: a horizontal pinch steps the grid resolution
 * (spread = finer steps, squeeze = coarser). Runs before the step pads and consumes.
 */
fun Modifier.pinchSteps(onStep: (finer: Boolean) -> Unit): Modifier = composed {
    val cb by rememberUpdatedState(onStep)
    pointerInput(Unit) {
        awaitPointerEventScope {
            var acc = 0f
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val pts = e.changes.filter { it.pressed }
                if (pts.size < 2) { acc = 0f; continue }
                val a = pts[0]; val b = pts[1]
                val pdx = abs(a.previousPosition.x - b.previousPosition.x); val cdx = abs(a.position.x - b.position.x)
                if (pdx > 40f && cdx > 40f) acc += ln(cdx / pdx)
                val step = ln(1.6f)
                if (acc > step) { cb(true); acc = 0f } else if (acc < -step) { cb(false); acc = 0f }
                e.changes.forEach { it.consume() }
            }
        }
    }
}
