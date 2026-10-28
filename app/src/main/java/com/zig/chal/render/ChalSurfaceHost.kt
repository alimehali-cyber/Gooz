package com.zig.chal.render

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import com.orchestrsim.blackhole.NativeBridge
import com.zig.chal.config.ChalRuntimeState
import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalRendererBackend
import com.zig.chal.config.ChalSimulationParams

/** A compatibility explanation is information, not a persistent error over the scene. */
enum class ChalCompatibilityReason { ANDROID_VERSION, ABI, VULKAN_VERSION, LIBRARY_LOAD, NATIVE_RUNTIME }

/** Prefer the original Vulkan renderer; recover from JNI/initialization errors without losing state. */
class ChalSurfaceHost(context: Context, initialParams: ChalSimulationParams, initialRuntime: ChalRuntimeState? = null) : FrameLayout(context) {
    private var glSurfaceView: ChalSurfaceView? = null
    private var nativeSurfaceView: XapkNativeSurfaceView? = null
    private var nativeRenderer: XapkChalRenderer? = null
    private lateinit var activeRenderer: ChalRendererBackend
    private var resumed = false
    private var released = false
    private val nativeUnsupportedReason = XapkNativeSupport.unsupportedReason(context)

    val renderer: ChalRendererBackend get() = activeRenderer
    val isUsingXapkRenderer: Boolean get() = nativeRenderer != null
    val hasGraphicsSupport: Boolean get() = nativeRenderer != null || glSurfaceView != null
    val canRetryNative: Boolean get() = nativeUnsupportedReason == null && compatibilityReason == ChalCompatibilityReason.NATIVE_RUNTIME
    val errorMessage: String? get() = renderer.errorMessage
    var compatibilityReason: ChalCompatibilityReason? = null
        private set
    var diagnosticDetails: String? = null
        private set
    var onBackendChanged: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null
        set(value) {
            field = value
            glSurfaceView?.onTap = value
            nativeSurfaceView?.onTap = value
        }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        if (nativeUnsupportedReason == null && initialRuntime?.preferCompatibility != true) installNative(initialParams)
        else installGles(initialParams, initialRuntime?.compatibilityReason ?: nativeUnsupportedReason ?: ChalCompatibilityReason.NATIVE_RUNTIME,
            initialRuntime?.diagnosticDetails ?: NativeBridge.loadFailure?.toString(), initialRuntime)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        renderer.setDisplayRefreshRate(displayRefreshRateHz())
    }

    private fun displayRefreshRateHz(): Double = try {
        (display?.refreshRate?.toDouble() ?: 60.0).takeIf { it.isFinite() && it > 0.0 } ?: 60.0
    } catch (_: RuntimeException) { 60.0 }

    fun setDisplayRefreshRate(refreshRateHz: Double) = renderer.setDisplayRefreshRate(refreshRateHz)

    private fun installNative(params: ChalSimulationParams) {
        detachBackend()
        val native = XapkChalRenderer(params)
        val surface = XapkNativeSurfaceView(context, native)
        nativeRenderer = native
        nativeSurfaceView = surface
        activeRenderer = native
        compatibilityReason = null
        diagnosticDetails = null
        surface.onTap = onTap
        addView(surface, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        native.setDisplayRefreshRate(displayRefreshRateHz())
        if (resumed) native.onResume()
        onBackendChanged?.invoke()
    }

    private fun installGles(params: ChalSimulationParams, reason: ChalCompatibilityReason, details: String?, runtime: ChalRuntimeState? = null) {
        detachBackend()
        compatibilityReason = reason
        diagnosticDetails = details
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if ((manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) < 0x30000) {
            activeRenderer = UnavailableRenderer(params)
        } else {
            val surface = ChalSurfaceView(context, ChalRenderer(params, runtime))
            glSurfaceView = surface
            activeRenderer = surface.renderer
            surface.onTap = onTap
            addView(surface, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            surface.renderer.setDisplayRefreshRate(displayRefreshRateHz())
            if (resumed) surface.onResume()
        }
        onBackendChanged?.invoke()
    }

    /** Called on the UI thread after reading native telemetry. Native process crashes are not catchable. */
    fun recoverNativeFailureIfNeeded() {
        if (released) return
        val native = nativeRenderer ?: return
        val failure = native.errorMessage ?: return
        installGles(native.paramsForPersistence(), ChalCompatibilityReason.NATIVE_RUNTIME, failure)
    }

    fun retryRenderer() {
        if (released || !hasGraphicsSupport) return
        val state = saveRuntimeState()
        if (isUsingXapkRenderer) installNative(state.params)
        else installGles(state.params, compatibilityReason ?: ChalCompatibilityReason.NATIVE_RUNTIME, diagnosticDetails, state)
    }

    fun saveRuntimeState(): ChalRuntimeState = renderer.saveRuntimeState().copy(
        preferCompatibility = nativeUnsupportedReason == null && !isUsingXapkRenderer,
        compatibilityReason = compatibilityReason, diagnosticDetails = diagnosticDetails
    )

    fun retryNative() {
        if (!released && canRetryNative) installNative(renderer.paramsForPersistence())
    }

    private fun detachBackend() {
        // Destroy the old JNI surface BEFORE creating a replacement: the imported library is global.
        // Its later SurfaceHolder callback is idempotent and cannot tear down the new instance.
        nativeRenderer?.onPause()
        nativeRenderer?.onSurfaceDestroyed()
        glSurfaceView?.releaseGl()
        glSurfaceView?.onPause()
        removeAllViews()
        nativeRenderer = null
        nativeSurfaceView = null
        glSurfaceView = null
    }

    fun onResume() {
        if (released || resumed) return
        resumed = true
        nativeRenderer?.onResume() ?: glSurfaceView?.onResume()
    }

    fun onPause() {
        if (released || !resumed) return
        resumed = false
        nativeRenderer?.onPause() ?: glSurfaceView?.onPause()
    }

    fun release() {
        if (released) return
        released = true
        detachBackend()
    }

    fun resetCamera() { nativeRenderer?.resetCamera() ?: glSurfaceView?.resetCamera() }
    fun resetScenarioPitch() { nativeRenderer?.resetPitchForScenario() ?: glSurfaceView?.resetScenarioPitch() }
    // These controls are offered only when GLES is active. There are no inert native stubs.
    fun startCinematic(mode: ChalCamera.CinematicMode, reducedMotion: Boolean) { glSurfaceView?.startCinematic(mode, reducedMotion) }
    fun stopCinematic() { glSurfaceView?.stopCinematic() }
    fun startBenchmark() { glSurfaceView?.startBenchmark() }
    fun cancelBenchmark() { glSurfaceView?.cancelBenchmark() }
}

private class UnavailableRenderer(initialParams: ChalSimulationParams) : ChalRendererBackend {
    override var params = ChalRenderPolicy.normalize(initialParams, native = false)
        private set
    override val errorMessage = "OpenGL ES 3.0 is not supported on this device."
    override fun updateParams(newParams: ChalSimulationParams) { params = newParams }
    override fun editParams(edit: ChalSimulationParams.() -> ChalSimulationParams): ChalSimulationParams = params.edit().also { params = it }
    override fun setDisplayRefreshRate(refreshRateHz: Double) = Unit
    override fun snapshot() = ChalRenderer.ChalSnapshot(params, 0, 0.0, params.features.rayTracingQuality,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, false, null, 0, isReady = false, postProcessingAvailable = false)
}

private object XapkNativeSupport {
    private const val VULKAN_API_VERSION_1_1 = 0x00401000
    fun unsupportedReason(context: Context): ChalCompatibilityReason? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> ChalCompatibilityReason.ANDROID_VERSION
        Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" } -> ChalCompatibilityReason.ABI
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_API_VERSION_1_1) -> ChalCompatibilityReason.VULKAN_VERSION
        !NativeBridge.ensureLoaded(context.applicationContext) -> ChalCompatibilityReason.LIBRARY_LOAD
        else -> null
    }
}
