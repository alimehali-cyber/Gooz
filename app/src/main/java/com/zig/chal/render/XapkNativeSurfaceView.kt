package com.zig.chal.render

import android.content.Context
import android.graphics.PixelFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Android SurfaceView with the same surface and touch callback contract as the XAPK. */
class XapkNativeSurfaceView(
    context: Context,
    private val xapkRenderer: XapkChalRenderer
) : SurfaceView(context), SurfaceHolder.Callback {
    var onTap: (() -> Unit)? = null
    private var hadMultiplePointers = false
    private var pinchBaseDistance = 100.0f
    private var cumulativePinchScale = 1.0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!hadMultiplePointers) performClick()
            return true
        }

        override fun onScroll(
            first: MotionEvent?,
            current: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            xapkRenderer.onDrag(distanceX, distanceY, width, height)
            return true
        }
    })

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchBaseDistance = xapkRenderer.params.zoom.toFloat()
                cumulativePinchScale = 1.0f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                cumulativePinchScale *= detector.scaleFactor
                xapkRenderer.onPinch(pinchBaseDistance, cumulativePinchScale)
                return true
            }
        }
    )

    init {
        holder.setFormat(PixelFormat.OPAQUE)
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            hadMultiplePointers = false
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (event.pointerCount > 1) hadMultiplePointers = true
        scaleGestureDetector.onTouchEvent(event)
        if (!scaleGestureDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        xapkRenderer.onSurfaceCreated(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        xapkRenderer.onSurfaceChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        xapkRenderer.onSurfaceDestroyed()
    }
}
