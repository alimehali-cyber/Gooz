package com.alijafari.red.astronomy.ui.skypanorama

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts the GLES 3.0 panorama inside the Home hero.
 *
 * A [TextureView] is used instead of a `SurfaceView`: a SurfaceView is punched through the window
 * and ignores the Compose `clip(SquircleShape)` applied to the hero, whereas a TextureView is
 * composited as a normal view and honours the rounded clipping.
 *
 * Status is reported on the main thread. [SkyPanoramaStatus.READY] is reported only after the
 * first panorama frame has been swapped, so the legacy sky remains visible until the panorama is
 * actually on screen, which avoids a black or empty flash.
 */
class SkyPanoramaSurfaceView(
    context: Context,
    private val config: SkyPanoramaConfig = SkyPanoramaConfig()
) : TextureView(context), TextureView.SurfaceTextureListener {

    /** Main-thread callback. */
    var onStatusChanged: ((SkyPanoramaStatus) -> Unit)? = null
        set(value) {
            field = value
            if (!released) value?.invoke(status)
        }

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var renderer: SkyPanoramaRenderer? = null
    private var decodeJob: Job? = null
    private var latestState: SkyPanoramaState? = null
    private var status = SkyPanoramaStatus.LOADING

    /** Main thread. Set by [release]; once true, no queued callback may touch the view again. */
    private var released = false

    init {
        // Transparent until the first frame, so the legacy sky underneath shows through.
        isOpaque = false
        isClickable = false
        isFocusable = false
        surfaceTextureListener = this
    }

    /** Main thread. Only changed states reach the GL thread, so an unchanged sky never redraws. */
    fun updateState(state: SkyPanoramaState) {
        if (latestState == state) return
        latestState = state
        renderer?.setState(state)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // The listener is invoked only on the GL thread after construction, so `owner` is assigned
        // before any callback can read it.
        lateinit var owner: SkyPanoramaRenderer
        owner = SkyPanoramaRenderer(object : SkyPanoramaRenderer.Listener {
            override fun onGlReady(maxTextureSize: Int) {
                mainHandler.post { if (!released) startDecode(owner, maxTextureSize) }
            }

            override fun onFrameShown() {
                mainHandler.post { if (!released && renderer === owner) setStatus(SkyPanoramaStatus.READY) }
            }

            override fun onGlFailure(reason: String) {
                Log.w(TAG, "Panorama unavailable: $reason")
                mainHandler.post { if (!released) failRenderer(owner) }
            }
        })
        renderer = owner
        owner.setConfig(config)
        owner.attach(surface, width, height)
        val pending = latestState
        if (pending != null) owner.setState(pending)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.setViewport(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // Intentionally empty: the panorama is drawn on demand, not on every SurfaceTexture update.
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Returning false: the renderer releases the SurfaceTexture on its GL thread, after the EGL
        // surface that uses it has been destroyed.
        stopRenderer()
        setStatus(SkyPanoramaStatus.LOADING)
        return false
    }

    /** Main thread. Cancels pending work and releases GL resources. Safe to call repeatedly. */
    fun release() {
        released = true
        stopRenderer()
        scope.cancel()
    }

    private fun stopRenderer() {
        decodeJob?.cancel()
        decodeJob = null
        renderer?.detachAndStop()
        renderer = null
    }

    /**
     * Main thread. Any panorama failure (GL init, texture decode, texture upload) ends here: the
     * renderer's GL thread and context are released and the legacy fallback stays visible.
     * Safe to call for a renderer that has already been stopped.
     */
    private fun failRenderer(owner: SkyPanoramaRenderer) {
        if (renderer === owner) {
            // Not stopRenderer(): this may run inside the decode coroutine, which must not cancel itself.
            decodeJob = null
            owner.detachAndStop()
            renderer = null
        }
        setStatus(SkyPanoramaStatus.UNAVAILABLE)
    }

    private fun startDecode(owner: SkyPanoramaRenderer, maxTextureSize: Int) {
        if (released || renderer !== owner) return
        decodeJob?.cancel()
        val tier = SkyPanoramaTextureLoader.chooseTier(config.qualityTier, maxTextureSize)
        decodeJob = scope.launch {
            try {
                // Shared, read-only decode. This host uploads it into its own texture and never recycles it.
                val bitmap = SkyPanoramaTextureCache.obtain(appContext, config.assetPath, tier)
                if (renderer === owner) owner.uploadTexture(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Panorama texture unavailable: ${e.message}")
                if (!released) failRenderer(owner)
            }
        }
    }

    private fun setStatus(next: SkyPanoramaStatus) {
        if (released || status == next) return
        status = next
        onStatusChanged?.invoke(next)
    }

    private companion object {
        const val TAG = "SkyPanoramaView"
    }
}
