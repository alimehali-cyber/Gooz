package com.zig.gargantua.renderer

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Immutable render-state snapshot passed from the UI thread to the OpenGL ES render thread.
 * Guarantees race-free updates and prevents state shearing without blocking the render loop.
 */
data class GargantuaRenderState(
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
    val renderScale: Float = 0.5f, // Scaled rendering factor in (0.25..1.0), 1.0 = native full resolution ceiling
    val isPaused: Boolean = false,
    val isDarkTheme: Boolean = true,
    val isPersian: Boolean = false,
    val mass: Float = 1.0f,
    val spin: Float = 0.8f,
    val camDist: Float = 32.0f,
    val camInclinationDeg: Float = 80.0f,
    val camAzimuthDeg: Float = 0.0f,
    val camTargetX: Float = 0.0f,
    val camTargetY: Float = 0.0f,
    val camTargetZ: Float = 0.0f,
    val maxSteps: Int = 220,
    val useGeodesicShader: Boolean = true,
    val enableDisk: Boolean = true,
    val diskOuterRadius: Float = 22.0f,
    // M9 remains available as validated infrastructure, but is not part of the production baseline.
    // It must be explicitly enabled by an instrumentation/experimental caller.
    val enableObject: Boolean = false,
    val objectRadius: Float = 0.45f,
    val objectOrbitRadius: Float = 6.5f,
    val objectPhi0: Float = 0.0f,
    val objectZ: Float = 0.0f,
    val enableDoppler: Boolean = false, // false = Movie Mode (Default, Balanced), true = Strict GR Beaming (g^4)
    val exposure: Float = 0.675f,       // Exposure for the luminance-preserving ACES composite
    val enableBloom: Boolean = true,
    val bloomIntensity: Float = 0.22f,  // Ethereal glow without washing out cloud details
    val bloomThreshold: Float = 1.15f,  // High threshold: only incandescent highlights bleed, preserving dark lanes
    // Temporary uniform spatial ray sampling control. The startup default remains 1x1.
    val debugCoarseSamplingBlockSize: Int = 1,
    // Debug-only GPU workload instrumentation. Production rendering leaves this disabled.
    val enableWorkloadTelemetry: Boolean = false,
    // Temporary, non-persisted animated-disk control. TEL and ANIM are mutually exclusive in the HUD.
    val enableAnimation: Boolean = false,
    val animationAmplitudePercent: Int = 0,
    val animationSpeed: GargantuaAnimation.AnimationSpeed = GargantuaAnimation.AnimationSpeed.NORMAL,
    // TEMPORARY physical-GPU pipeline diagnostics (GargantuaGpuDiagnostics). 0 = OFF: no diagnostics
    // code, resource or readback runs and rendering is unchanged.
    val diagnosticView: Int = 0,
    // TEMPORARY A/B of the animation programs' integer/sampler precision (0 = as shipped); set only by
    // the forensic run on the renderer's per-frame effective state.
    val diagnosticRecordPrecision: Int = 0,
    // TEMPORARY forensic run: a change rebuilds the animation programs and retraces the cache.
    val diagnosticRebuildGeneration: Int = 0
)

/**
 * CPU-side pass timings around OpenGL submissions.
 *
 * These are deliberately labelled CPU timings: GLES commands are normally asynchronous and these
 * values are not GPU execution times. A true hardware timer is reported separately when available.
 */
data class GargantuaPassTimings(
    val geodesicCpuSubmitMs: Float = 0f,
    val coarseUpscaleCpuSubmitMs: Float = 0f,
    val brightPassCpuSubmitMs: Float = 0f,
    val horizontalBlurCpuSubmitMs: Float = 0f,
    val verticalBlurCpuSubmitMs: Float = 0f,
    val compositeCpuSubmitMs: Float = 0f,
    val animationModulationCpuSubmitMs: Float = 0f,
    val animationApplyCpuSubmitMs: Float = 0f,
    val gpuTimerAvailable: Boolean = false
)

/**
 * Bounded workload statistics collected by the optional debug instrumentation path.
 * Counts are zero/unavailable unless [GargantuaRenderState.enableWorkloadTelemetry] is enabled
 * in a debug build. No production frame depends on GPU readback for these values.
 */
data class GargantuaWorkloadStats(
    val available: Boolean = false,
    /** Unchanged internal HDR/output dimensions. */
    val internalWidth: Int = 0,
    val internalHeight: Int = 0,
    /** Dimensions of the texture whose fragments execute the expensive ray integration. */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val samplingBlockSize: Int = 1,
    val shadedBlocks: Long = 0L,
    val primaryRayCalculations: Long = 0L,
    val primaryRayReductionVsBaseline: Float = 1f,
    val tier0Pixels: Long = 0L,
    val tier1Pixels: Long = 0L,
    val tier2Pixels: Long = 0L,
    /** Number of actually dispatched ray integrations satisfying the difficulty gate. */
    val difficultRayCount: Long = 0L,
    val totalRaysFrame: Long = 0L,
    /** Average dispatched rays per shaded block, not per upscaled output pixel. */
    val averageRaysPerPixel: Float = 0f,
    val maximumRaysPerPixel: Int = 0,
    val totalIntegrationSteps: Long = 0L,
    val averageIntegrationSteps: Float = 0f,
    val maximumIntegrationSteps: Int = 0,
    val diskIntersections: Long = 0L
) {
    val totalPixels: Long get() = tier0Pixels + tier1Pixels + tier2Pixels
    val totalInternalPixels: Long get() = internalWidth.toLong() * internalHeight.toLong()

    companion object {
        fun unavailable(): GargantuaWorkloadStats = GargantuaWorkloadStats()
    }
}

/**
 * Real-time performance and presentation telemetry emitted by the GL render thread to the UI thread.
 */
data class GargantuaTelemetry(
    /** Recent active composite-submission rate; it intentionally remains unchanged while idle. */
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val glesVersion: String = "Detecting...",
    val glRenderer: String = "",
    val isInitialized: Boolean = false,
    val errorMessage: String? = null,
    val spin: Float = 0.8f,
    val isGeodesicActive: Boolean = true,
    val isDiskActive: Boolean = true,
    val isObjectActive: Boolean = false,
    val iscoRadius: Float = 2.91f,
    val renderScale: Float = 0.5f,
    val renderResolution: String = "",
    val isHdrActive: Boolean = false,
    val exposure: Float = 1.8f,
    val camDist: Float = 32.0f,
    val camInclinationDeg: Float = 80.0f,
    val camAzimuthDeg: Float = 0.0f,
    val camTargetX: Float = 0.0f,
    val camTargetY: Float = 0.0f,
    val camTargetZ: Float = 0.0f,
    val adaptiveWorkload: String = "Unavailable (debug instrumentation disabled)",
    val avgRaysPerPixel: Float = 0f,
    val maxRaysPerPixel: Int = 0,
    val workloadStats: GargantuaWorkloadStats = GargantuaWorkloadStats(),
    val passTimings: GargantuaPassTimings = GargantuaPassTimings(),
    val animationFps: Float = 0f,
    val animationFrameTimeMs: Float = 0f,
    val animationStatus: String = "ANIM OFF",
    val gpuDiagnosticsReport: String = "",
    val gpuDiagnosticsStatus: String = "",
    val animationDiagnostics: String = "ANIM DIAGNOSTICS: state=PLAIN cache=false since=0ms field=none rebuilds=0 notReady=none block=1 render=0x0",
    val timerQueryAvailable: Boolean = false
)

/**
 * Thread-safe holder for render state and telemetry using atomic references.
 */
class RenderStateHolder(initial: GargantuaRenderState = GargantuaRenderState()) {

    private val stateRef = AtomicReference(initial)
    private val telemetryRef = AtomicReference(GargantuaTelemetry())
    private val stateChangeListenerRef = AtomicReference<(() -> Unit)?>(null)

    fun getState(): GargantuaRenderState = stateRef.get()

    /**
     * Installs a non-blocking invalidation callback used by [GargantuaSurfaceView]. The callback is
     * invoked after a successful state change, so RENDERMODE_WHEN_DIRTY can wake the GL thread
     * without making the renderer loop continuously.
     */
    fun setStateChangeListener(listener: (() -> Unit)?) {
        stateChangeListenerRef.set(listener)
    }

    /** Requests a frame without fabricating a state mutation, for surface/context lifecycle events. */
    fun notifyRenderNeeded() {
        stateChangeListenerRef.get()?.invoke()
    }

    fun updateState(transform: (GargantuaRenderState) -> GargantuaRenderState): GargantuaRenderState {
        var current: GargantuaRenderState
        var next: GargantuaRenderState
        do {
            current = stateRef.get()
            next = transform(current)
        } while (!stateRef.compareAndSet(current, next))
        if (next != current) {
            stateChangeListenerRef.get()?.invoke()
        }
        return next
    }

    fun setState(state: GargantuaRenderState) {
        val previous = stateRef.getAndSet(state)
        if (previous != state) {
            stateChangeListenerRef.get()?.invoke()
        }
    }

    fun getTelemetry(): GargantuaTelemetry = telemetryRef.get()

    fun setTelemetry(telemetry: GargantuaTelemetry) {
        telemetryRef.set(telemetry)
    }

    fun updateTelemetry(transform: (GargantuaTelemetry) -> GargantuaTelemetry) {
        var current: GargantuaTelemetry
        var next: GargantuaTelemetry
        do {
            current = telemetryRef.get()
            next = transform(current)
        } while (!telemetryRef.compareAndSet(current, next))
    }
}
