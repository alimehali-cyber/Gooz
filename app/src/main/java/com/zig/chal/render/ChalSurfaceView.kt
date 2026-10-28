package com.zig.chal.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import kotlin.math.hypot

/**
 * `GLSurfaceView` hosting the Chal Kerr ray-marcher.
 *
 * Touch contract, mirroring the reference engine's pointer handlers:
 *  - 1-finger drag rotates (theta/phi) with throw momentum
 *  - 2-finger pinch zooms (`zoomDelta = (1 - ratio) * 2.0`)
 *  - 2-finger pan moves the camera centre
 *
 * Every mutation is dispatched with `queueEvent`, so the camera state is only ever touched on the
 * GL thread -- the same single-writer discipline the reference gets from its animation loop.
 */
class ChalSurfaceView(
    context: Context,
    val renderer: ChalRenderer = ChalRenderer()
) : GLSurfaceView(context) {

    /** Fired on the UI thread when a tap (not a drag) lands, used to toggle the overlay chrome. */
    var onTap: (() -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var pointerCount = 0
    private var isDragging = false
    private var touchMoved = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            touchMoved = true
            val span = detector.currentSpan.toDouble()
            queueEvent { renderer.onPinchStart(span) }
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val span = detector.currentSpan.toDouble()
            if (span > 0.0) {
                queueEvent { renderer.onPinch(span) }
            }
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // The adaptive controller and the frame gate pace to the panel, not to a hard-coded 60 Hz:
        // 90 Hz panels can only hold 90 or 45, and chasing 60 there just drains the resolution.
        renderer.setDisplayRefreshRate(displayRefreshRateHz())
    }

    /** Panel refresh rate in Hz, falling back to 60 when the window is not attached yet. */
    private fun displayRefreshRateHz(): Double {
        return try {
            val rate = display?.refreshRate?.toDouble() ?: 60.0
            if (rate.isFinite() && rate > 0.0) rate else 60.0
        } catch (t: Throwable) {
            60.0
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (renderer.isBenchmarkRunning()) return true
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true)
                pointerCount = 1
                isDragging = true
                touchMoved = false
                lastX = event.x
                lastY = event.y
                val x = event.x.toDouble()
                val y = event.y.toDouble()
                queueEvent { renderer.onPointerDown(x, y) }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pointerCount = event.pointerCount
                lastFocusX = focusX(event)
                lastFocusY = focusY(event)
                isDragging = false
                touchMoved = true
                val count = pointerCount
                queueEvent { renderer.onPointerUp(); renderer.onTouchCountChanged(count) }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    pointerCount = event.pointerCount
                    val fx = focusX(event)
                    val fy = focusY(event)
                    val dx = (fx - lastFocusX).toDouble()
                    val dy = (fy - lastFocusY).toDouble()
                    if (hypot(dx, dy) > 2.0) {
                        queueEvent { renderer.onPan(dx, dy) }
                        touchMoved = true
                    }
                    lastFocusX = fx
                    lastFocusY = fy
                } else if (isDragging) {
                    val x = event.x
                    val y = event.y
                    if (hypot((x - lastX).toDouble(), (y - lastY).toDouble()) > 2.0) touchMoved = true
                    val xd = x.toDouble()
                    val yd = y.toDouble()
                    queueEvent { renderer.onPointerMove(xd, yd) }
                    lastX = x
                    lastY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pointerCount = event.pointerCount - 1
                if (pointerCount <= 0) {
                    queueEvent { renderer.onPointerUp() }
                } else if (pointerCount == 1) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                    val x = lastX.toDouble()
                    val y = lastY.toDouble()
                    queueEvent { renderer.onPointerDown(x, y) }
                    isDragging = true
                } else {
                    isDragging = false
                    lastFocusX = focusX(event, event.actionIndex)
                    lastFocusY = focusY(event, event.actionIndex)
                    val count = pointerCount
                    queueEvent { renderer.onTouchCountChanged(count) }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerCount = 0
                isDragging = false
                queueEvent { renderer.onPointerUp() }
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && !touchMoved) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.setDisplayRefreshRate(displayRefreshRateHz())
    }

    override fun onResume() {
        queueEvent { renderer.onHostResume() }
        super.onResume()
    }

    override fun onPause() {
        queueEvent { renderer.onHostPause() }
        super.onPause()
    }

    fun resetScenarioPitch() { queueEvent { renderer.resetScenarioPitch() } }

    private fun focusX(event: MotionEvent, excludedIndex: Int = -1): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) if (i != excludedIndex) sum += event.getX(i)
        return sum / (event.pointerCount - if (excludedIndex >= 0) 1 else 0)
    }

    private fun focusY(event: MotionEvent, excludedIndex: Int = -1): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) if (i != excludedIndex) sum += event.getY(i)
        return sum / (event.pointerCount - if (excludedIndex >= 0) 1 else 0)
    }

    /** Reset the camera from the UI thread (the camera is only ever touched on the GL thread). */
    fun resetCamera() {
        queueEvent { renderer.resetCamera() }
    }

    /** Start a cinematic from the UI thread. */
    fun startCinematic(mode: ChalCamera.CinematicMode, reducedMotion: Boolean) {
        queueEvent { renderer.startCinematic(mode, reducedMotion) }
    }

    /** Stop a cinematic from the UI thread. */
    fun stopCinematic() {
        queueEvent { renderer.stopCinematic() }
    }

    /** Start the performance suite from the UI thread. */
    fun startBenchmark() {
        queueEvent { renderer.startBenchmark() }
    }

    /** Abort the performance suite from the UI thread. */
    fun cancelBenchmark() {
        queueEvent { renderer.cancelBenchmark() }
    }

    /** Release GL resources on the GL thread when the host screen goes away. */
    fun releaseGl() {
        queueEvent { renderer.release() }
    }
}
