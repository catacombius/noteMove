package com.spectraseq.app.input

import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.Window
import kotlin.math.abs
import kotlin.math.exp

/**
 * Touchpad gestures (keyboard covers, Bluetooth touchpads, DeX). Android 14+ reports a two-finger
 * scroll as a fake finger dragging (classification TWO_FINGER_SWIPE) and three/four-finger swipes as
 * MULTI_FINGER_SWIPE. Left alone, a two-finger scroll over the piano roll would act like a finger and
 * add or move notes. Instead:
 *
 * - Two-finger scroll becomes scroll-wheel input at the cursor (smooth, 1:1 with the fingers) with
 *   momentum after you lift, which every list, grid and timeline already understands. Holding Ctrl
 *   turns it into zoom, like a mouse wheel.
 * - A three-finger swipe left or right calls [onSwipe] (switches panels), on systems that pass
 *   these on to apps (many keep them for system navigation).
 * - Pinch arrives as two fingers and is left to the views (they already pinch-zoom).
 *
 * Pointer-device scroll events (DeX, some touchpads send these directly) are untouched.
 */
class TrackpadGestures(
    /** Delivers a synthetic scroll event into the window's views. */
    private val dispatchScroll: (MotionEvent) -> Unit,
    /** Delivers a touch event (used to cancel a gesture the views had already started seeing). */
    private val dispatchTouch: (MotionEvent) -> Unit,
    /** Pixels of finger travel per wheel notch; views scroll [notchPx] per notch, so travel maps 1:1. */
    var notchPx: Float,
    private val swipeMinPx: Float,
    private val onSwipe: (direction: Int) -> Unit,
) {
    private var mode = 0 // 0 idle, 1 two-finger scroll, 2 multi-finger swipe
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var meta = 0
    private var deviceId = 0
    private var tracker: VelocityTracker? = null

    // Momentum after lifting the fingers.
    private var vx = 0f
    private var vy = 0f
    private var lastFrameNs = 0L
    private var flinging = false
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!flinging) return
            val dt = if (lastFrameNs == 0L) 1f / 60 else ((now - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.05f)
            lastFrameNs = now
            scroll(vx * dt, vy * dt)
            val decay = exp(-4.2f * dt)
            vx *= decay; vy *= decay
            if (abs(vx) < 25f && abs(vy) < 25f) flinging = false else Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Returns true when the event was a touchpad gesture and has been handled. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) stopFling()
        if (mode == 0) {
            val cls = ev.classification
            mode = when (cls) {
                MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE -> 1
                CLASSIFICATION_MULTI_FINGER_SWIPE -> 2
                else -> return false
            }
            // The views saw the start of this gesture before it was classified: cancel it for them.
            if (action != MotionEvent.ACTION_DOWN) {
                val c = MotionEvent.obtain(ev)
                c.action = MotionEvent.ACTION_CANCEL
                dispatchTouch(c)
                c.recycle()
            }
            downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
            deviceId = ev.deviceId
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
        }
        meta = ev.metaState
        tracker?.addMovement(ev)
        when (action) {
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - lastX
                val dy = ev.y - lastY
                lastX = ev.x; lastY = ev.y
                if (mode == 1) scroll(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 1 && action == MotionEvent.ACTION_UP) {
                    tracker?.computeCurrentVelocity(1000)
                    val fx = tracker?.xVelocity ?: 0f
                    val fy = tracker?.yVelocity ?: 0f
                    if (abs(fx) > 150f || abs(fy) > 150f) startFling(fx, fy)
                } else if (mode == 2 && action == MotionEvent.ACTION_UP) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > swipeMinPx && abs(dx) > abs(dy) * 1.5f) onSwipe(if (dx < 0) 1 else -1)
                }
                tracker?.recycle(); tracker = null
                mode = 0
            }
        }
        return true
    }

    /** Finger travel ([dx], [dy] in px, content following the fingers) as wheel notches at the gesture's start. */
    private fun scroll(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = downX; y = downY
            // Wheel up (positive VSCROLL) reveals content above, i.e. the fingers moved down.
            setAxisValue(MotionEvent.AXIS_VSCROLL, dy / notchPx)
            setAxisValue(MotionEvent.AXIS_HSCROLL, -dx / notchPx)
        })
        val t = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, props, coords, meta, 0, 1f, 1f, deviceId, 0, InputDevice.SOURCE_MOUSE, 0)
        dispatchScroll(e)
        e.recycle()
    }

    private fun startFling(fx: Float, fy: Float) {
        vx = fx; vy = fy
        lastFrameNs = 0L
        if (!flinging) { flinging = true; Choreographer.getInstance().postFrameCallback(frame) }
    }

    fun stopFling() {
        if (flinging) { flinging = false; Choreographer.getInstance().removeFrameCallback(frame) }
    }

    companion object {
        /** MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE (not in the public SDK constants). */
        private const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4

        /** Wheel notch size used by the zoomable grids, in dp (so touchpad scrolling stays 1:1). */
        const val NOTCH_DP = 64f

        /**
         * Hooks a dialog's window (dialogs don't pass through the activity). Returns an undo function.
         */
        fun install(window: Window, density: Float, onSwipe: (Int) -> Unit = {}): () -> Unit {
            val orig = window.callback ?: return {}
            lateinit var g: TrackpadGestures
            val wrapper = object : Window.Callback by orig {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean = g.onTouchEvent(event) || orig.dispatchTouchEvent(event)
            }
            g = TrackpadGestures({ orig.dispatchGenericMotionEvent(it) }, { orig.dispatchTouchEvent(it) }, NOTCH_DP * density, 120f * density, onSwipe)
            window.callback = wrapper
            return { g.stopFling(); if (window.callback === wrapper) window.callback = orig }
        }
    }
}
