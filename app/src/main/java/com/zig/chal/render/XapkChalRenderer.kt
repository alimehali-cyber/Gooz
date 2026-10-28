package com.zig.chal.render

import android.view.Surface
import com.orchestrsim.blackhole.NativeBridge
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalRendererBackend
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.config.XapkCameraState
import com.zig.chal.config.XapkParameterBlock
import com.zig.chal.config.XapkRendererContract
import com.zig.chal.physics.ChalKerrMetric
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * UI adapter over the XAPK's Vulkan/Rust renderer. The renderer itself remains the extracted native
 * library; this class only mirrors the JNI lifecycle, 16-float/four-int state contract, camera
 * gestures, and telemetry consumed by Chal's existing Lab UI.
 */
class XapkChalRenderer(initialParams: ChalSimulationParams) : ChalRendererBackend {
    private val lock = Any()

    @Volatile
    override var params: ChalSimulationParams = ChalRenderPolicy.normalize(initialParams, native = true)
        private set

    @Volatile
    override var errorMessage: String? = null
        private set

    @Volatile
    private var surfaceReady = false

    @Volatile
    private var surfaceConfigured = false

    private var cameraYaw = params.cameraYaw.toFloat()
    private var cameraPitch = (params.verticalAngle / 180.0).toFloat()
    private var cameraDistance = params.zoom.toFloat()
    private var resumed = false
    private var targetFps = 60
    private val telemetry = FloatArray(3)
    private var telemetryFps = 0
    private var telemetryFrameTimeMs = 0.0
    private var telemetryScale = XapkRendererContract.qualityFor(initialParams.features.rayTracingQuality).renderScale.toDouble()

    init {
        if (!NativeBridge.ensureLoaded()) {
            errorMessage = "Could not load the XAPK Vulkan renderer: ${NativeBridge.loadFailure.orEmpty()}"
        } else {
            cameraPitch = (params.verticalAngle / 180.0).toFloat()
                .coerceIn(XapkCameraState.MIN_PITCH, XapkCameraState.MAX_PITCH)
        }
    }

    override fun updateParams(newParams: ChalSimulationParams) { editParams { newParams } }

    override fun editParams(edit: ChalSimulationParams.() -> ChalSimulationParams): ChalSimulationParams = synchronized(lock) {
        val previous = params
        val updated = ChalRenderPolicy.normalize(params.edit(), native = true)
        params = updated
        if (updated.cameraYaw != previous.cameraYaw) cameraYaw = updated.cameraYaw.toFloat()
        if (updated.verticalAngle != previous.verticalAngle) {
            cameraPitch = (updated.verticalAngle / 180.0).toFloat()
                .coerceIn(XapkCameraState.MIN_PITCH, XapkCameraState.MAX_PITCH)
        }
        if (updated.zoom != previous.zoom) cameraDistance = updated.zoom.toFloat()
        if (surfaceConfigured && NativeBridge.ensureLoaded()) pushState()
        params
    }

    override fun setDisplayRefreshRate(refreshRateHz: Double) {
        targetFps = if (refreshRateHz.isFinite() && refreshRateHz > 0.0) refreshRateHz.roundToInt().coerceIn(1, 60) else 60
    }

    /** SurfaceHolder.Callback: XAPK sends the Android Surface directly to JNI. */
    fun onSurfaceCreated(surface: Surface) {
        synchronized(lock) {
            if (!NativeBridge.ensureLoaded()) {
                errorMessage = "Could not load the XAPK Vulkan renderer: ${NativeBridge.loadFailure.orEmpty()}"
                return
            }
            try {
                NativeBridge.nativeOnSurfaceCreated(surface)
                surfaceReady = true
                surfaceConfigured = false
                errorMessage = null
                NativeBridge.nativeOnPause(!resumed)
            } catch (failure: Throwable) {
                try { NativeBridge.nativeOnSurfaceDestroyed() } catch (_: Throwable) { /* best-effort cleanup of a partial initialization */ }
                surfaceReady = false
                surfaceConfigured = false
                errorMessage = "XAPK Vulkan surface initialization failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        synchronized(lock) {
            if (!surfaceReady || width <= 0 || height <= 0) return
            try {
                NativeBridge.nativeOnSurfaceChanged(width, height)
                surfaceConfigured = true
                errorMessage = null
                // The reference calls its parameter and camera setters after every size change.
                pushState()
                NativeBridge.nativeOnPause(!resumed)
            } catch (failure: Throwable) {
                surfaceConfigured = false
                errorMessage = "XAPK Vulkan surface resize failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    fun onSurfaceDestroyed() {
        synchronized(lock) {
            if (!surfaceReady) return
            try {
                try { NativeBridge.nativeOnPause(true) } catch (_: Throwable) { /* teardown must still run */ }
                NativeBridge.nativeOnSurfaceDestroyed()
            } catch (failure: Throwable) {
                // A failed teardown does not affect a future surface. Do not publish it as a render
                // error: that would permanently switch to the compatibility renderer on return from
                // the background. If the next surface really cannot start, THAT failure is reported.
                android.util.Log.w("ChalXapk", "XAPK Vulkan surface teardown failed", failure)
            } finally {
                surfaceReady = false
                surfaceConfigured = false
            }
        }
    }

    fun onResume() {
        synchronized(lock) {
            resumed = true
            if (!surfaceReady || !NativeBridge.ensureLoaded()) return
            try {
                NativeBridge.nativeOnPause(false)
            } catch (failure: Throwable) {
                errorMessage = "XAPK Vulkan resume failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    fun onPause() {
        synchronized(lock) {
            resumed = false
            if (!surfaceReady || !NativeBridge.ensureLoaded()) return
            try {
                NativeBridge.nativeOnPause(true)
            } catch (failure: Throwable) {
                errorMessage = "XAPK Vulkan pause failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    /** Exact GestureDetector scroll mapping from the XAPK's f3.a listener. */
    fun onDrag(distanceX: Float, distanceY: Float, viewWidth: Int, viewHeight: Int) {
        val minimumDimension = max(1, minOf(viewWidth, viewHeight)).toFloat()
        synchronized(lock) {
            cameraYaw = wrapYaw(cameraYaw - distanceX / minimumDimension)
            cameraPitch = (cameraPitch - distanceY / minimumDimension * XapkCameraState.DRAG_PITCH_SENSITIVITY)
                .coerceIn(XapkCameraState.MIN_PITCH, XapkCameraState.MAX_PITCH)
            params = params.copy(cameraYaw = cameraYaw.toDouble(), verticalAngle = cameraPitch.toDouble() * 180.0)
            sendCamera()
        }
    }

    /** XAPK scale listener accumulates detector factors and uses baseDistance / scale. */
    fun onPinch(baseDistance: Float, cumulativeScale: Float) {
        synchronized(lock) {
            val safeScale = cumulativeScale.coerceAtLeast(0.1f)
            cameraDistance = (baseDistance / safeScale)
                .coerceIn(XapkCameraState.MIN_DISTANCE, XapkCameraState.MAX_DISTANCE)
            params = params.copy(zoom = cameraDistance.toDouble())
            sendCamera()
        }
    }

    fun resetCamera() {
        synchronized(lock) {
            cameraYaw = XapkCameraState.DEFAULT_YAW
            cameraPitch = XapkCameraState.DEFAULT_PITCH
            cameraDistance = params.zoom.toFloat()
            params = params.copy(cameraYaw = cameraYaw.toDouble(), verticalAngle = XapkCameraState.DEFAULT_POLAR_ANGLE_DEGREES)
            if (surfaceConfigured) pushState()
        }
    }

    /** A scenario resets pitch and distance but leaves yaw untouched on both backends. */
    fun resetPitchForScenario() {
        synchronized(lock) {
            cameraPitch = XapkCameraState.DEFAULT_PITCH
            params = params.copy(verticalAngle = XapkCameraState.DEFAULT_POLAR_ANGLE_DEGREES)
            if (surfaceConfigured) pushState()
        }
    }

    /** Read XAPK's three-float telemetry block and its Rust Kerr helper values. */
    override fun snapshot(): ChalRenderer.ChalSnapshot = synchronized(lock) {
        if (surfaceConfigured) {
            try {
                NativeBridge.nativeGetTelemetry(telemetry)
                telemetryFps = if (telemetry[0].isFinite()) telemetry[0].roundToInt().coerceAtLeast(0) else 0
                telemetryFrameTimeMs = if (telemetryFps > 0) 1_000.0 / telemetryFps else 0.0
                if (telemetry[2].isFinite() && telemetry[2] > 0.0f) telemetryScale = telemetry[2].toDouble()
            } catch (failure: Throwable) {
                errorMessage = "XAPK Vulkan telemetry failed: ${failure.message ?: failure.javaClass.simpleName}"
            }
        }

        val mass = params.mass.coerceIn(0.1, 20.0)
        val spin = params.spin.coerceIn(0.0, 0.99)
        val horizon = nativeMetricOrFallback {
            NativeBridge.nativeKerrHorizon(mass, spin)
        } ?: ChalKerrMetric.calculateEventHorizon(mass, spin)
        val photonSphere = nativeMetricOrFallback {
            NativeBridge.nativeKerrPhotonSphere(mass, spin)
        } ?: ChalKerrMetric.calculatePhotonSphere(mass, spin)
        val isco = nativeMetricOrFallback {
            NativeBridge.nativeKerrIsco(mass, spin, true)
        } ?: ChalKerrMetric.calculateIsco(mass, spin, prograde = true)

        val radius = max(params.zoom * mass, horizon * 1.01)
        val timeDilation = ChalKerrMetric.calculateTimeDilation(radius, mass)
        val redshift = if (timeDilation > 0.0) (1.0 / timeDilation) - 1.0 else Double.POSITIVE_INFINITY
        val frameBudget = if (telemetryFrameTimeMs > 0.0) {
            telemetryFrameTimeMs / (1_000.0 / targetFps) * 100.0
        } else {
            0.0
        }
        val quality = params.features.rayTracingQuality.let {
            if (it == ChalRayTracingQuality.OFF) ChalRayTracingQuality.LOW else it
        }

        ChalRenderer.ChalSnapshot(
            params = params,
            currentFps = telemetryFps,
            frameTimeMs = telemetryFrameTimeMs,
            quality = quality,
            budgetUsage = frameBudget,
            eventHorizonRadius = horizon,
            photonSphereRadius = photonSphere,
            iscoRadius = isco,
            timeDilation = timeDilation,
            redshift = redshift,
            isCinematic = false,
            cinematicMode = null,
            targetFps = targetFps,
            actualRenderScale = telemetryScale,
            postProcessingAvailable = true,
            isReady = surfaceConfigured
        )
    }

    private fun pushState() {
        val block: XapkParameterBlock = XapkRendererContract.build(
            params = params,
            camera = XapkCameraState(cameraYaw, cameraPitch, cameraDistance)
        )
        try {
            NativeBridge.nativeSetParams(block.floats, block.integers)
            sendCamera()
        } catch (failure: Throwable) {
            errorMessage = "XAPK Vulkan parameter update failed: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }

    private fun sendCamera() {
        if (!surfaceConfigured || !NativeBridge.ensureLoaded()) return
        try {
            NativeBridge.nativeSetCamera(cameraYaw, cameraPitch, cameraDistance)
        } catch (failure: Throwable) {
            errorMessage = "XAPK Vulkan camera update failed: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }

    private inline fun nativeMetricOrFallback(block: () -> Double): Double? {
        if (!NativeBridge.ensureLoaded()) return null
        return try {
            block().takeIf { it.isFinite() }
        } catch (failure: Throwable) {
            // A readout helper failing is not a rendering failure: use the Kotlin Kerr metric instead
            // and keep the (working) native renderer instead of triggering the compatibility fallback.
            android.util.Log.w("ChalXapk", "Native Kerr helper failed; using the Kotlin metric", failure)
            null
        }
    }

    private fun wrapYaw(yaw: Float): Float {
        val wrapped = yaw % 1.0f
        return if (wrapped < 0.0f) wrapped + 1.0f else wrapped
    }
}
