package com.zig.chal.render

import com.zig.chal.config.ChalPerformanceConfig
import com.zig.chal.config.ChalRayTracingQuality
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Performance Monitor with PID-based adaptive resolution.
 *
 * Verbatim port of the reference engine's `src/performance/monitor.ts` (ring-buffer frame-time
 * statistics, rolling FPS, frame-budget usage, and the damped PID resolution controller).
 */
class ChalPerformanceMonitor(private val nowMillis: () -> Double = { System.nanoTime() / 1_000_000.0 }) {

    /** Performance metrics snapshot (`PerformanceMetrics`). */
    data class PerformanceMetrics(
        val currentFPS: Int = 0,
        val frameTimeMs: Double = 0.0,
        val rollingAverageFPS: Int = 0,
        val quality: ChalRayTracingQuality = ChalRayTracingQuality.HIGH,
        val renderResolution: Double = 1.0
    )

    /** Severity-tagged performance warnings (`PerformanceWarning`). */
    data class PerformanceWarning(
        val severity: String,
        val message: String,
        val suggestions: List<String>
    )

    /** Fixed-size ring buffer for frame times. */
    private class RingBuffer(private val capacity: Int) {
        private val data = DoubleArray(capacity)
        private var index = 0
        private var count = 0

        fun push(value: Double) {
            data[index] = value
            index = (index + 1) % capacity
            if (count < capacity) count++
        }

        fun size(): Int = count

        fun last(): Double = if (count == 0) 0.0 else data[(index - 1 + capacity) % capacity]

        fun clear() {
            index = 0
            count = 0
            for (i in data.indices) data[i] = 0.0
        }

        fun average(): Double {
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) sum += data[i]
            return sum / count
        }
    }

    /** `WINDOW = 90` -- 90 frames (~1.5 s at 60 fps) for a stable rolling average. */
    private val window = 90
    private val frameTimes = RingBuffer(window)

    private var cachedAvgTime = 0.0
    private var cachedAvgFPS = 0.0
    private var cacheValid = false

    private var currentQuality: ChalRayTracingQuality = ChalRayTracingQuality.HIGH
    private var renderResolution: Double = 1.0
    private var renderCeiling: Double = 1.0

    /*
     * Hardware awareness. The reference sniffs the user agent for
     * android|webos|iphone|ipad|ipod|blackberry|iemobile|opera mini; every Chal deployment target
     * is a phone or tablet, so the mobile branch of that test is the one that always applies.
     */
    private val isMobile: Boolean = ChalPerformanceConfig.Mobile.IS_MOBILE_HARDWARE
    private var isCalibrating: Boolean = true
    private var calibrationStartTime: Double = nowMillis()
    private var maxAllowedQuality: ChalRayTracingQuality = ChalRayTracingQuality.ULTRA

    // PID Controller State (Phase 4.1: Stabilization)
    private var errorIntegral = 0.0
    private var prevError = 0.0
    private var isStabilized = false
    private var lastResolutionChangeTime = 0.0

    // Chal mobile extension: direct (non-PID) convergence. See ChalPerformanceConfig.Mobile.
    private var fastRecalibrationPending = true
    private var overBudgetFrames = 0

    /** Frame time the controller aims for; follows the display's refresh rate. */
    private var targetFrameTimeMs: Double = ChalPerformanceConfig.Scheduler.FRAME_BUDGET_MS

    private val metrics = PerformanceMetrics()

    init {
        // Mobile devices skip the stress-test and start at the hard quality cap.
        if (isMobile) {
            maxAllowedQuality = mobileHardCap()
            currentQuality = maxAllowedQuality
            isCalibrating = false
        }
    }

    /** `PERFORMANCE_CONFIG.calibration.mobileHardCap` resolved to a quality enum. */
    private fun mobileHardCap(): ChalRayTracingQuality =
        ChalRayTracingQuality.fromId(ChalPerformanceConfig.Calibration.MOBILE_HARD_CAP)
            ?: ChalRayTracingQuality.MEDIUM

    /**
     * Feed one frame's delta time (ms) and return the updated metrics.
     */
    fun updateMetrics(deltaTime: Double, adaptive: Boolean = true, maximumScale: Double = 1.0, controllerDelta: Double = deltaTime): PerformanceMetrics {
        renderCeiling = if (maximumScale.isFinite()) maximumScale.coerceIn(0.5, 1.0) else 1.0
        setRenderResolution(renderResolution)
        if (deltaTime.isFinite() && deltaTime > 0.0) frameTimes.push(deltaTime)
        cacheValid = false

        val now = nowMillis()

        // Calibration Phase logic
        if (isCalibrating) {
            if (now - calibrationStartTime > ChalPerformanceConfig.Calibration.DURATION_MS) {
                isCalibrating = false
                finalizeCalibration()
            }
        } else if (adaptive && ChalPerformanceConfig.Resolution.ENABLE_DYNAMIC_SCALING) {
            // Chal mobile extension: settle the render scale from the measured frame time first,
            // then let the reference's PID trim it. The PID's own step size is deliberately tiny
            // (0.01 * correction, one change per 500 ms cooldown) and takes about a minute to walk
            // the scale down from native resolution on a phone; after the direct rescale the PID is
            // inside its cooldown, so the two never fight over the same frame.
            applyFastRecalibration()
            applyPidScaling(controllerDelta)
            trackOverBudget(controllerDelta)
        }

        return getMetrics(deltaTime)
    }

    /** Settle the calibration phase early (`endCalibration`). */
    fun endCalibration() {
        isCalibrating = false
        finalizeCalibration()
    }

    /**
     * Post-calibration quality decision: if the device could not sustain 30 FPS over the stress
     * window, step the tier down once and remember the new ceiling.
     */
    private fun finalizeCalibration() {
        if (frameTimes.size() == 0) return
        ensureCache()
        val avgFps = cachedAvgFPS

        if (avgFps < ChalPerformanceConfig.Calibration.MIN_STABLE_FPS) {
            if (currentQuality == ChalRayTracingQuality.ULTRA) {
                setQuality(ChalRayTracingQuality.HIGH)
            } else if (currentQuality == ChalRayTracingQuality.HIGH) {
                setQuality(ChalRayTracingQuality.MEDIUM)
            }
            maxAllowedQuality = currentQuality
        }
    }

    /** True when the rolling average has fallen under the adaptive threshold. */
    fun shouldReduceQuality(): Boolean {
        if (isCalibrating) return false
        ensureCache()
        return frameTimes.size() >= window &&
            cachedAvgFPS < ChalPerformanceConfig.Resolution.ADAPTIVE_THRESHOLD
    }

    /** True when there is headroom to step back up (never past the hardware cap). */
    fun shouldIncreaseQuality(): Boolean {
        if (isCalibrating) return false
        ensureCache()
        if (currentQuality == ChalRayTracingQuality.ULTRA ||
            (currentQuality == ChalRayTracingQuality.HIGH && maxAllowedQuality == ChalRayTracingQuality.HIGH) ||
            (currentQuality == ChalRayTracingQuality.MEDIUM && maxAllowedQuality == ChalRayTracingQuality.MEDIUM)
        ) {
            return false
        }
        return frameTimes.size() >= window &&
            cachedAvgFPS > ChalPerformanceConfig.Resolution.RECOVERY_THRESHOLD
    }

    /** Operator-facing warnings (`getWarnings`). */
    fun getWarnings(): List<PerformanceWarning> {
        val currentMetrics = getMetrics()
        val budgetUsage = getFrameTimeBudgetUsage()
        val warnings = mutableListOf<PerformanceWarning>()

        if (currentMetrics.rollingAverageFPS < 30) {
            warnings += PerformanceWarning(
                severity = "critical",
                message = "Critical performance issue detected",
                suggestions = listOf("Disable Gravitational Lensing", "Set Quality to Low")
            )
        } else if (currentMetrics.rollingAverageFPS < 60) {
            warnings += PerformanceWarning(
                severity = "warning",
                message = "Performance warning: FPS below 60",
                suggestions = listOf("Reduce Ray Tracing Quality", "Disable Bloom")
            )
        }

        if (budgetUsage > 100.0) {
            warnings += PerformanceWarning(
                severity = "info",
                message = "Frame time budget exceeded (>${
                    String.format(
                        java.util.Locale.US,
                        "%.1f",
                        1000.0 / ChalPerformanceConfig.Scheduler.TARGET_FPS
                    )
                }ms)",
                suggestions = listOf("Enable Adaptive Resolution")
            )
        }

        return warnings
    }

    /** Clear the rolling statistics (`reset`). */
    fun reset() {
        frameTimes.clear()
        cacheValid = false
    }

    /**
     * Point the controller at a display refresh rate (`ChalPerformanceConfig.Mobile.targetFrameTimeMs`).
     * Called once by the renderer with the panel's rate.
     */
    fun setTargetFrameTime(frameTimeMs: Double) {
        if (!frameTimeMs.isFinite() || frameTimeMs <= 0.0) return
        if (frameTimeMs == targetFrameTimeMs) return
        targetFrameTimeMs = frameTimeMs.coerceIn(
            ChalPerformanceConfig.Mobile.MIN_TARGET_FRAME_MS,
            ChalPerformanceConfig.Mobile.MAX_TARGET_FRAME_MS
        )
        fastRecalibrationPending = true
    }

    /** Current adaptive target (tests / telemetry). */
    fun getTargetFrameTime(): Double = targetFrameTimeMs

    /** PID-Based Adaptive Scaling (Phase 4.1). */
    private fun applyPidScaling(dt: Double) {
        val target = targetFrameTimeMs * 0.95 // 95% headroom
        val error = target - dt
        val now = nowMillis()

        val kp = ChalPerformanceConfig.Resolution.Pid.KP
        val ki = ChalPerformanceConfig.Resolution.Pid.KI
        val kd = ChalPerformanceConfig.Resolution.Pid.KD
        val deadzone = ChalPerformanceConfig.Resolution.Pid.DEADZONE
        val cooldownMs = ChalPerformanceConfig.Resolution.Pid.COOLDOWN_MS
        val integralClamp = ChalPerformanceConfig.Resolution.Pid.INTEGRAL_CLAMP

        // DEADZONE: If the error is within `deadzone` fraction of target, do nothing.
        // This prevents sub-millisecond oscillation from causing resolution ping-pong.
        val absError = abs(error)
        if (absError < target * deadzone) {
            // We're close enough. Zero the derivative to prevent jitter on re-entry.
            prevError = error
            isStabilized = true
            return
        }

        // COOLDOWN: Don't change resolution more than once per `cooldownMs`.
        // Each resolution change forces the GPU to reconfigure framebuffer state,
        // which itself causes a frame-time spike that feeds back into the PID.
        if (now - lastResolutionChangeTime < cooldownMs) {
            prevError = error
            return
        }

        // 1. Proportional
        val pOut = kp * error

        // 2. Integral (tightly clamped to prevent wind-up)
        errorIntegral = min(
            maxOf(errorIntegral + error, -integralClamp),
            integralClamp
        )
        val iOut = ki * errorIntegral

        // 3. Derivative
        val dOut = kd * (error - prevError)
        prevError = error

        // Total correction
        val adjustment = pOut + iOut + dOut

        // Apply to resolution (damped, with minimum step to avoid float noise)
        val delta = adjustment * 0.01
        if (abs(delta) < 0.001) return // Sub-pixel change -- skip

        val newRes = renderResolution + delta
        setRenderResolution(newRes)
        lastResolutionChangeTime = now

        // If we are consistently within 3% of target, mark as stabilized
        isStabilized = absError < target * 0.03
    }

    /**
     * Chal mobile extension -- direct convergence of the render scale.
     *
     * Fragment cost is proportional to the number of pixels, so `scale ~ sqrt(target / measured)`.
     * One measurement replaces the PID's ~1 %-of-scale-per-second crawl, which is what makes a
     * phone stop feeling slow in the first second instead of the first minute.
     */
    private fun applyFastRecalibration() {
        if (!fastRecalibrationPending) return
        if (frameTimes.size() < ChalPerformanceConfig.Mobile.FAST_RECALIBRATION_FRAMES) return

        ensureCache()
        val measured = max(cachedAvgTime, ChalPerformanceConfig.Mobile.MIN_MEASURED_FRAME_MS)
        val target = targetFrameTimeMs
        setRenderResolution(proportionalScale(renderResolution, measured, target))

        fastRecalibrationPending = false
        overBudgetFrames = 0
        errorIntegral = 0.0
        prevError = 0.0
        lastResolutionChangeTime = nowMillis()
    }

    /**
     * Chal mobile extension -- thermal guard. A sustained over-budget stretch (clocks dropping as
     * the device heats up) asks for another direct rescale instead of waiting for the PID.
     */
    private fun trackOverBudget(deltaTime: Double) {
        val limit = targetFrameTimeMs * ChalPerformanceConfig.Mobile.OVER_BUDGET_FACTOR
        overBudgetFrames = if (deltaTime > limit) overBudgetFrames + 1 else 0
        if (overBudgetFrames >= ChalPerformanceConfig.Mobile.FAST_RECALIBRATION_TRIGGER_FRAMES) {
            fastRecalibrationPending = true
            overBudgetFrames = 0
        }
    }

    /** Ask for another direct rescale (used when the quality tier or the parameters change). */
    fun requestFastRecalibration() {
        fastRecalibrationPending = true
    }

    /** True while the direct rescale is still pending (telemetry / tests). */
    fun isFastRecalibrationPending(): Boolean = fastRecalibrationPending

    private fun ensureCache() {
        if (!cacheValid) {
            cachedAvgTime = frameTimes.average()
            cachedAvgFPS = if (cachedAvgTime > 0.0) 1000.0 / cachedAvgTime else 0.0
            cacheValid = true
        }
    }

    fun getMetrics(currentDeltaTime: Double? = null): PerformanceMetrics {
        ensureCache()

        val dt = currentDeltaTime ?: (if (frameTimes.size() > 0) frameTimes.last() else 0.0)
        val fps = if (dt > 0.0) 1000.0 / dt else 0.0

        return PerformanceMetrics(
            currentFPS = Math.round(fps).toInt(),
            frameTimeMs = Math.round(cachedAvgTime * 100.0) / 100.0,
            rollingAverageFPS = Math.round(cachedAvgFPS).toInt(),
            quality = currentQuality,
            renderResolution = renderResolution
        )
    }

    fun setQuality(quality: ChalRayTracingQuality) {
        currentQuality = quality
    }

    /** The adaptive tier ceiling (mobile hard cap, or the post-calibration ceiling). */
    fun getMaxAllowedQuality(): ChalRayTracingQuality = maxAllowedQuality

    /**
     * Seed the resolution scale from the user-facing `renderScale` parameter.
     *
     * The reference does this in `useAnimation`: `setRenderResolution(params.renderScale || 1.0)`,
     * after which the PID controller owns the value.
     */
    fun seedRenderScale(scale: Double) {
        if (scale.isFinite() && scale > 0.0) setRenderResolution(scale)
    }

    private fun setRenderResolution(res: Double) {
        // `mobileCap` is documented as a hard cap, but the reference never actually reads it; on a
        // phone supersampling above native resolution is pure heat.
        val ceiling = if (isMobile) {
            min(ChalPerformanceConfig.Resolution.MAX_SCALE, ChalPerformanceConfig.Resolution.MOBILE_CAP)
        } else {
            ChalPerformanceConfig.Resolution.MAX_SCALE
        }
        renderResolution = min(
            maxOf(res, ChalPerformanceConfig.Resolution.MIN_SCALE),
            min(ceiling, renderCeiling)
        )
    }

    /** The resolution scale a mobile session should start from (`Mobile.START_SCALE`). */
    fun initialResolutionScale(): Double =
        if (isMobile) {
            min(ChalPerformanceConfig.Mobile.START_SCALE, ChalPerformanceConfig.Resolution.MOBILE_CAP)
        } else {
            ChalPerformanceConfig.Resolution.BASE_SCALE
        }

    companion object {
        /**
         * Pixel-proportional rescale: fragment cost scales with the pixel count, i.e. with the
         * square of the resolution scale, so the corrected scale is
         * `current * sqrt(target / measured)`. Pure function so it can be unit tested.
         */
        fun proportionalScale(current: Double, measuredFrameTimeMs: Double, targetFrameTimeMs: Double): Double {
            if (!current.isFinite() || current <= 0.0) return current
            if (!measuredFrameTimeMs.isFinite() || measuredFrameTimeMs <= 0.0) return current
            if (!targetFrameTimeMs.isFinite() || targetFrameTimeMs <= 0.0) return current
            val ratio = (targetFrameTimeMs / measuredFrameTimeMs).coerceIn(0.04, 4.0)
            return current * sqrt(ratio)
        }
    }

    /** Frame budget usage as a percentage of one 60 FPS frame. */
    fun getFrameTimeBudgetUsage(): Double {
        ensureCache()
        val targetTime = 1000.0 / ChalPerformanceConfig.Scheduler.TARGET_FPS
        return (cachedAvgTime / targetTime) * 100.0
    }

    fun isStabilized(): Boolean = isStabilized
}
