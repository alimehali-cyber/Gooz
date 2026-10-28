package com.alijafari.red.astronomy.ui.persepolis

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compose-era host for [PersepolisMilAzhdahaScene]: the combined Persepolis + Mil-e Azhdaha
 * relief drawn into a [TextureView] by a hand-rolled EGL thread, instead of the supplied
 * file's original window-level overlay view.
 *
 * Why a TextureView and not the file's original window-level overlay design: a window-level Surface owns
 * a separate window-level surface, and with z-order-on-top it composites above the *entire
 * activity window* — no Compose sibling can ever cover it. The monument layer therefore
 * punched straight through the search overlay, the fullscreen viewer and every other
 * in-window overlay, and kept the sky-scene's rectangles (clip, scroll, scrim dimming) from
 * applying to it: the relief read as a pasted-on picture rather than part of the sky canvas.
 * A TextureView rasterises into the window like any other view, so the relief now honours
 * Compose z-order, clipping and scrolling exactly like the painted layers around it — the
 * search scrim dims it, overlays cover it, and it scrolls with the Home feed. The trade is
 * the cost other GL hosts in this app already accept ([com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaSurfaceView],
 * [com.alijafari.red.astronomy.ar.android.ArSkyGlView]): one texture copy per presented
 * frame, and a hand-owned EGL lifecycle instead of the framework-managed one.
 *
 * The scene itself is untouched: [PersepolisMilAzhdahaScene.init] / `draw` / `release` stay
 * the only GL calls. [PersepolisMilAzhdahaScene.draw] does not swap, so this host swaps
 * explicitly after each rendered frame. Rendering stays strictly on demand — one frame per
 * Sun/Moon/framing change, one per surface (re)creation or resume — exactly the cadence the
 * old render-when-dirty surface had.
 *
 * The view is non-clickable and non-focusable and consumes no touches, so tap-to-identify
 * and finger time travel on the surrounding canvas are unaffected.
 */
class PersepolisTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    /** The scene, exposed for framing controls and explicit-light APIs. Its init/draw/release
     *  run on the render thread owned below; its setters are synchronised and may be called
     *  from any thread. */
    val scene = PersepolisMilAzhdahaScene()

    private var renderer: Renderer? = null

    /** Main thread. Set by [release]; once true, no callback may restart rendering. */
    private var released = false

    init {
        // Transparent: the stone shows over the painted ground, its sky shows through.
        isOpaque = false
        isClickable = false
        isFocusable = false
        surfaceTextureListener = this
    }

    /** Astronomical azimuth/altitude; colour & ambient derived automatically by the scene. */
    fun setSun(azimuthDeg: Float, altitudeDeg: Float) {
        scene.setSun(azimuthDeg, altitudeDeg)
        renderer?.requestDraw()
    }

    /** Moon as second light; scale [intensity] by illuminated fraction (≈0.22 = full). */
    fun setMoon(azimuthDeg: Float, altitudeDeg: Float, intensity: Float = 0.22f) {
        scene.setMoon(azimuthDeg, altitudeDeg, intensity)
        renderer?.requestDraw()
    }

    /** Framing: visible world width in metres (116 = the preview look), the
     *  ground-line position as a fraction of the view height from the top
     *  (0.80 = the preview look; 0.995 plants the base on the bottom edge), and
     *  optionally the combined frame's anchor x as a fraction from the left. */
    fun setFraming(
        visibleWidthMeters: Float,
        baseFractionFromTop: Float,
        centerFractionFromLeft: Float = 0.5f,
    ) {
        scene.visibleWidthMeters = visibleWidthMeters
        scene.baseFractionFromTop = baseFractionFromTop
        scene.centerFractionFromLeft = centerFractionFromLeft
        renderer?.requestDraw()
    }

    /** Lifecycle, forwarded by the Compose host: resume drawing (and repaint once). The
     *  EGL context is kept across pauses — the static VBOs never re-upload just because the
     *  Activity backgrounded (same intent as the old preserve-on-pause flag). */
    fun onResume() {
        renderer?.setPaused(false)
    }

    /** Lifecycle, forwarded by the Compose host: stop drawing until [onResume]. The GL
     *  thread and context stay alive; [scene]'s state keeps up with the ephemeris meanwhile,
     *  so the first frame after resume is already correct. */
    fun onPause() {
        renderer?.setPaused(true)
    }

    /** Main thread. Releases the scene's GL objects on the render thread, tears the EGL
     *  context down and stops the thread. Terminal for this view instance (the Compose host
     *  creates a fresh view if the layer is composed again). Safe to call repeatedly. */
    fun release() {
        released = true
        stopRenderer()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (released) return
        val owner = Renderer(scene)
        renderer = owner
        owner.attach(surface, width, height)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.setViewport(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Returning false: the renderer releases the SurfaceTexture on its GL thread, after
        // the EGL surface that uses it has been destroyed.
        stopRenderer()
        return false
    }

    private fun stopRenderer() {
        renderer?.detachAndStop()
        renderer = null
    }

    /**
     * Owns the GLES 3.0 context and the EGL window surface for one attachment of the view.
     * Every GL and EGL call runs on its dedicated handler thread. The scene draws on demand
     * only: after attach, after a viewport change, after resume, and after a light/framing
     * change posted through [requestDraw] (coalesced, so a scrub that posts faster than the
     * thread renders cannot flood the queue).
     */
    private class Renderer(private val scene: PersepolisMilAzhdahaScene) {

        private val thread = HandlerThread("PersepolisReliefGl").apply { start() }
        private val handler = Handler(thread.looper)
        private val drawPending = AtomicBoolean(false)

        // ---- GL-thread-only state -------------------------------------------------------
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var surfaceTexture: SurfaceTexture? = null
        private var viewportWidth = 0
        private var viewportHeight = 0
        private var paused = false
        private var failed = false

        /** Creates EGL + the scene's GL resources for [surface], then draws the first frame.
         *  Any state already pushed into the scene (sun/moon/framing set before the surface
         *  existed) is picked up by that frame. */
        fun attach(surface: SurfaceTexture, width: Int, height: Int) = handler.post {
            surfaceTexture = surface
            viewportWidth = width
            viewportHeight = height
            try {
                initEgl(surface)
                scene.init()
                drawFrame()
            } catch (t: Throwable) {
                Log.w(TAG, "Persepolis relief GL init failed", t)
                failed = true
                releaseSceneQuietly()
                releaseGl()
            }
        }

        fun setViewport(width: Int, height: Int) = handler.post {
            viewportWidth = width
            viewportHeight = height
            drawFrame()
        }

        fun setPaused(value: Boolean) = handler.post {
            paused = value
            if (!paused) drawFrame()
        }

        /** Posts one draw; concurrent requests collapse into the queued one. */
        fun requestDraw() {
            if (drawPending.compareAndSet(false, true)) {
                handler.post {
                    drawPending.set(false)
                    drawFrame()
                }
            }
        }

        /** Releases the scene's GL objects while the context is current, then tears down
         *  EGL and stops the thread. The renderer cannot be reused afterwards. */
        fun detachAndStop() {
            handler.post {
                releaseSceneQuietly()
                releaseGl()
                thread.quitSafely()
            }
        }

        private fun drawFrame() {
            if (failed || paused || eglSurface == EGL14.EGL_NO_SURFACE) return
            if (viewportWidth <= 0 || viewportHeight <= 0) return
            try {
                // The scene draws with a transparent clear (draw(clear = true)); it never
                // swaps — the framework surface host swapped on its behalf, and so do we.
                scene.draw(viewportWidth, viewportHeight, clear = true)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                }
            } catch (t: Throwable) {
                // A GL failure must never take Home down: drop the layer and keep the
                // painted landscape underneath (the app's other GL hosts do the same).
                Log.w(TAG, "Persepolis relief draw failed", t)
                failed = true
                releaseSceneQuietly()
                releaseGl()
            }
        }

        private fun initEgl(surface: SurfaceTexture) {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            eglDisplay = display

            // Same config the original overlay view asked its config chooser for:
            // RGBA8888 for alpha compositing + 16-bit depth (the scene depth-tests the
            // tower against the facade, so the buffer is not optional).
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            check(
                EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                    numConfigs[0] > 0 && configs[0] != null
            ) { "No GLES 3.0 EGL config available" }
            val eglConfig = configs[0]!!

            val contextAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed (0x${Integer.toHexString(EGL14.eglGetError())})" }
            eglContext = context

            val windowSurface = EGL14.eglCreateWindowSurface(display, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(windowSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            eglSurface = windowSurface

            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        }

        /** GL thread. Deletes the scene's programs/buffers; only while a context exists —
         *  with no live context there is nothing of the scene's on the GPU to delete. */
        private fun releaseSceneQuietly() {
            if (eglContext == EGL14.EGL_NO_CONTEXT) return
            try {
                scene.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Persepolis relief GL cleanup failed", t)
            }
        }

        /** GL thread. Destroys the EGL surface/context and releases the SurfaceTexture.
         *  Deliberately no eglTerminate: the display connection is process-wide and the
         *  app's other GL hosts (panorama, AR sky) may be alive on it. */
        private fun releaseGl() {
            val display = eglDisplay
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            }
            surfaceTexture?.release()
            surfaceTexture = null
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        private companion object {
            /** EGL_OPENGL_ES3_BIT (0x0040), defined here because EGL14 does not expose it. */
            const val EGL_OPENGL_ES3_BIT = 0x0040
            /** EGL_CONTEXT_CLIENT_VERSION (0x3098). */
            const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        }
    }

    private companion object {
        const val TAG = "PersepolisTextureView"
    }
}
