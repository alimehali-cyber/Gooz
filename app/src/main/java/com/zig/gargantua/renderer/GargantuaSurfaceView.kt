package com.zig.gargantua.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import kotlin.math.*

/**
 * Native Android GLSurfaceView hosting the Gargantua OpenGL ES 3.x renderer.
 * Handles interactive observer navigation:
 * - 1-finger orbit: rotates azimuth and polar inclination.
 * - 2-finger pan/translate: smoothly shifts observer target along camera view plane.
 * - 2-finger pinch: zooms observer distance bounded in [12M, 60M].
 * - Seamless pointer transitions eliminating movement jitter.
 * Strictly requires GLES 3.x.
 */
class GargantuaSurfaceView(
    context: Context,
    val renderer: GargantuaRenderer = GargantuaRenderer(context)
) : GLSurfaceView(context) {

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var hasWindowFocus = false
    private var isDragging = false
    private var touchMoved = false
    var onRenderTap: (() -> Unit)? = null

    private val animationHandler = Handler(Looper.getMainLooper())
    private val animationTicker = object : Runnable {
        override fun run() {
            if (shouldTickAnimation()) {
                requestRender()
                animationHandler.postDelayed(this, GargantuaAnimation.TICK_INTERVAL_MS)
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            if (scaleFactor > 0f) {
                renderer.stateHolder.updateState { current ->
                    current.copy(
                        camDist = (current.camDist / scaleFactor).coerceIn(12.0f, 60.0f)
                    )
                }
            }
            return true
        }
    })

    init {
        Log.i(TAG, "Initializing Gargantua GLSurfaceView for OpenGL ES 3.x")

        // Strictly request client version 3 (no GLES2 fallback)
        setEGLContextClientVersion(3)

        // Standard 8-8-8-8 RGBA framebuffer configuration
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)

        // Preserve EGL context across onPause to avoid costly shader recompilation on transient pauses
        preserveEGLContextOnPause = true

        // Install the callback before setRenderer. setRenderer starts the GL thread, and a surface
        // can already be available when a view is reattached; the renderer's surface callbacks must
        // therefore never observe a missing invalidation listener.
        renderer.stateHolder.setStateChangeListener {
            requestRender()
            syncAnimationTicker()
        }
        renderer.setRenderReadyListener {
            // Queue behind the renderer callback itself. The GL thread then requests the frame
            // after resource/size setup has returned to GLSurfaceView's lifecycle loop.
            queueEvent {
                renderer.requestPresentation()
                requestRender()
            }
        }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY

        // Capture state mutations made during construction and request once even before the view is
        // attached. onAttachedToWindow/onSurfaceCreated/onSurfaceChanged provide the post-attach
        // and post-surface requests that WHEN_DIRTY cannot infer by itself.
        requestRender()
    }

    /**
     * Requests a lifecycle presentation without starting a continuous render loop.
     * The request is queued onto GLSurfaceView's GL thread so onResume is complete before the dirty
     * frame is requested. This is required when EGL preserves the context and emits no new
     * onSurfaceCreated callback.
     */
    fun requestFrameForLifecycle() {
        queueEvent {
            renderer.requestPresentation()
            requestRender()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The constructor request may have occurred before SurfaceView attachment and is not a
        // sufficient first-frame guarantee for a newly composed/navigation-created view.
        requestFrameForLifecycle()
        syncAnimationTicker()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            requestFrameForLifecycle()
        }
    }

    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        val focusGained = focused && !hasWindowFocus
        hasWindowFocus = focused
        if (focusGained) {
            // Covers display-off/unlock paths that restore window focus without recreating the EGL
            // context or emitting a new SurfaceHolder callback.
            requestFrameForLifecycle()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        // This is the first callback after the native SurfaceHolder becomes available. Request
        // here as well as from Renderer.onSurfaceCreated so the dirty request cannot be lost in
        // the gap between AndroidView attachment and EGL callback delivery.
        requestFrameForLifecycle()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        super.surfaceChanged(holder, format, width, height)
        requestFrameForLifecycle()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                touchMoved = false
                isDragging = true
                traceInput(GargantuaLensAnalysis.ACTION_DOWN, 0f, 0f)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastFocusX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastFocusY = (event.getY(0) + event.getY(1)) * 0.5f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1 && (abs(event.x - lastTouchX) > 2.0f || abs(event.y - lastTouchY) > 2.0f)) {
                    touchMoved = true
                }
                if (event.pointerCount >= 2) {
                    touchMoved = true
                }
                if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y

                    renderer.stateHolder.updateState { current ->
                        var newAzimuth = (current.camAzimuthDeg - dx * 0.35f) % 360.0f
                        if (newAzimuth < 0.0f) newAzimuth += 360.0f
                        val newInclination = (current.camInclinationDeg - dy * 0.35f).coerceIn(5.0f, 175.0f)
                        current.copy(
                            camAzimuthDeg = newAzimuth,
                            camInclinationDeg = newInclination
                        )
                    }
                    traceInput(GargantuaLensAnalysis.ACTION_MOVE, dx, dy)
                } else if (event.pointerCount >= 2) {
                    val focusX = (event.getX(0) + event.getX(1)) * 0.5f
                    val focusY = (event.getY(0) + event.getY(1)) * 0.5f
                    val dFocusX = focusX - lastFocusX
                    val dFocusY = focusY - lastFocusY
                    lastFocusX = focusX
                    lastFocusY = focusY

                    // Two-finger camera translation/pan
                    renderer.stateHolder.updateState { current ->
                        val inclRad = Math.toRadians(current.camInclinationDeg.toDouble())
                        val azRad = Math.toRadians(current.camAzimuthDeg.toDouble())

                        val rX = -sin(azRad)
                        val rY = cos(azRad)

                        val upX = -cos(inclRad) * cos(azRad)
                        val upY = -cos(inclRad) * sin(azRad)
                        val upZ = sin(inclRad)

                        val viewDim = max(100, min(width, height))
                        val panFactor = (current.camDist / viewDim) * 0.8f

                        // Translate target opposite to screen drag so scene follows fingers
                        val deltaX = (-rX * dFocusX + upX * dFocusY) * panFactor
                        val deltaY = (-rY * dFocusX + upY * dFocusY) * panFactor
                        val deltaZ = (upZ * dFocusY) * panFactor

                        current.copy(
                            camTargetX = (current.camTargetX + deltaX.toFloat()).coerceIn(-30.0f, 30.0f),
                            camTargetY = (current.camTargetY + deltaY.toFloat()).coerceIn(-30.0f, 30.0f),
                            camTargetZ = (current.camTargetZ + deltaZ.toFloat()).coerceIn(-30.0f, 30.0f)
                        )
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Smooth pointer transition: re-anchor remaining touch point to eliminate jitter
                val liftingIndex = event.actionIndex
                val remainingIndex = if (liftingIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastTouchX = event.getX(remainingIndex)
                    lastTouchY = event.getY(remainingIndex)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP && !touchMoved && event.pointerCount == 1) {
                    onRenderTap?.invoke()
                }
                isDragging = false
                touchMoved = false
                traceInput(GargantuaLensAnalysis.ACTION_UP, 0f, 0f)
            }
        }
        return true
    }

    /** DIAG only: records the processed event and the camera state it produced (no effect while DIAG is off). */
    private fun traceInput(action: Int, dx: Float, dy: Float) {
        val state = renderer.stateHolder.getState()
        if (state.diagnosticView == 0) return
        renderer.noteDiagnosticInput(
            GargantuaLensAnalysis.InputEvent(System.nanoTime(), action, dx, dy, state.camAzimuthDeg, state.camInclinationDeg)
        )
    }

    /**
     * Resets the observer camera to the cinematic default view.
     */
    fun resetCamera() {
        renderer.stateHolder.updateState {
            it.copy(
                camDist = 32.0f,
                camInclinationDeg = 80.0f,
                camAzimuthDeg = 0.0f,
                camTargetX = 0.0f,
                camTargetY = 0.0f,
                camTargetZ = 0.0f
            )
        }
    }

    override fun onPause() {
        stopAnimationTicker()
        renderer.stateHolder.updateState { it.copy(isPaused = true) }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        queueEvent {
            renderer.onResume()
            renderer.requestPresentation()
            requestRender()
        }
        renderer.stateHolder.updateState { it.copy(isPaused = false) }
        syncAnimationTicker()
    }

    override fun onDetachedFromWindow() {
        stopAnimationTicker()
        super.onDetachedFromWindow()
        // Queue release on GL thread
        queueEvent {
            renderer.release()
        }
    }

    private fun shouldTickAnimation(): Boolean {
        val state = renderer.stateHolder.getState()
        return isAttachedToWindow &&
            windowVisibility == VISIBLE &&
            !state.isPaused &&
            !state.enableWorkloadTelemetry &&
            ((state.enableAnimation && state.animationAmplitudePercent > 0) || state.diagnosticView != 0)
    }

    private fun syncAnimationTicker() {
        animationHandler.removeCallbacks(animationTicker)
        if (shouldTickAnimation()) {
            animationHandler.postDelayed(animationTicker, GargantuaAnimation.TICK_INTERVAL_MS)
        }
    }

    private fun stopAnimationTicker() {
        animationHandler.removeCallbacks(animationTicker)
    }

    companion object {
        private const val TAG = "GargantuaSurfaceView"
    }
}
