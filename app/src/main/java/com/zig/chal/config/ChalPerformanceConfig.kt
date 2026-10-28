package com.zig.chal.config

/**
 * Performance Optimization Configuration.
 *
 * Centralized control for GPU scheduling, resolution scaling, and computational budgets.
 * Governs how the simulation utilizes hardware resources. It is designed to maximize FPS on
 * constrained devices (like mobile/MacBooks) while allowing high-fidelity output on dedicated GPUs.
 *
 * Verbatim port of the reference engine's `src/configs/performance.config.ts`.
 */
object ChalPerformanceConfig {

    /** Adaptive Resolution & Scaling. */
    object Resolution {
        /** Default DPI multiplier (1.0 = native). */
        const val BASE_SCALE: Double = 1.0

        /** Minimum allowed downscaling for potato mode. */
        const val MIN_SCALE: Double = 0.5

        /** Maximum supersampling (Retina/4K). */
        const val MAX_SCALE: Double = 2.0

        /** Hard cap for mobile devices to prevent thermal throttling. */
        const val MOBILE_CAP: Double = 1.0

        /** FPS threshold below which resolution drops. */
        const val ADAPTIVE_THRESHOLD: Int = 58

        /** FPS threshold above which resolution recovers. */
        const val RECOVERY_THRESHOLD: Int = 72

        /** Master toggle for DPI scaling. */
        const val ENABLE_DYNAMIC_SCALING: Boolean = true

        /**
         * PID Controller Coefficients -- tuned for STABILITY, not reactivity.
         * Goal: FPS should barely move once converged. Visual smoothness > peak throughput.
         */
        object Pid {
            /** Proportional: halved to dampen oscillation around setpoint. */
            const val KP: Double = 0.025

            /** Integral: reduced to prevent wind-up drift. */
            const val KI: Double = 0.005

            /** Derivative: doubled to resist sudden frame-time spikes. */
            const val KD: Double = 0.04

            /** Fractional: ignore errors < 5% of frame budget (prevents micro-oscillation). */
            const val DEADZONE: Double = 0.05

            /** Minimum time between resolution changes (prevents GPU pipeline thrashing). */
            const val COOLDOWN_MS: Int = 500

            /** Tighter integral wind-up limit (was effectively 20). */
            const val INTEGRAL_CLAMP: Double = 8.0
        }
    }

    /** Stress-Test Calibration (Hardware Awareness). */
    object Calibration {
        /** 3 seconds of stress-testing at startup. */
        const val DURATION_MS: Int = 3000

        /** FPS needed to maintain 'ultra/high' quality (Cinematic standard). */
        const val MIN_STABLE_FPS: Int = 30

        /** Forced max quality for mobile devices. */
        const val MOBILE_HARD_CAP: String = "medium"
    }

    /** Ray Marching Budget (The Engine's "Gas Pedal"). */
    object Compute {
        /** Balanced default for ray steps. */
        const val MAX_STEPS_DEFAULT: Int = 200

        /** Reduced steps for mobile GPUs. */
        const val MAX_STEPS_MOBILE: Int = 80

        /** Distance threshold to switch to larger steps. */
        const val STEP_OPTIMIZATION_THRESHOLD: Double = 0.05

        /** Enable Level-of-Detail scaling based on camera distance. */
        const val DYNAMIC_LOD: Boolean = true
    }

    /** Scheduler & Loop Management. */
    object Scheduler {
        /** The simulation's heartbeat target. */
        const val TARGET_FPS: Int = 60

        /** FPS when tab is inactive/backgrounded. */
        const val IDLE_THROTTLE_FPS: Int = 30

        /** Max milliseconds per frame (1000/60). */
        const val FRAME_BUDGET_MS: Double = 16.67

        /** Time in ms before throttling kicks in. */
        const val IDLE_TIMEOUT_MS: Int = 30000
    }

    /**
     * ---------------------------------------------------------------------------------------------
     * Chal mobile tuning.
     *
     * These values are an EXTENSION of the ported `performance.config.ts`; the reference tunes for a
     * desktop web canvas where a full-resolution, 128-step march of empty space is affordable. On a
     * phone the same numbers leave the GPU permanently saturated, which is what makes the screen
     * feel slow and the device hot.
     *
     * Everything here is a *starting point* or an *acceleration of convergence*, never a hard
     * ceiling: the reference PID still owns the steady state.
     * ---------------------------------------------------------------------------------------------
     */
    object Mobile {

        /** Every Chal deployment target is a phone or tablet (mirrors the reference UA sniff). */
        const val IS_MOBILE_HARDWARE: Boolean = true

        /**
         * Initial virtual-viewport scale on mobile.
         *
         * The reference starts at `renderScale.default = 1.0` (native pixels) and lets the PID walk
         * down at `0.01 * correction` per 500 ms cooldown -- roughly half a pixel-scale per second.
         * On a phone that means a laggy first minute. Starting at 0.75 costs almost nothing visually
         * (the TAA resolve upsamples, and the scene is a smooth ray-marched image) while cutting the
         * fragment count to 56%, and the PID can still raise it back to [Resolution.MOBILE_CAP]
         * whenever the device has headroom.
         */
        const val START_SCALE: Double = 0.75

        /**
         * Frames of measurement before the first direct (non-PID) rescale.
         *
         * One second at 60 fps is enough to know whether the device can hold the frame budget.
         */
        const val FAST_RECALIBRATION_FRAMES: Int = 60

        /**
         * Consecutive frames above [overBudgetFactor] x the frame budget that re-trigger a direct
         * rescale. This is the thermal guard: a phone that sustained 60 fps in a cold room will drop
         * clocks after a few minutes, and the PID alone is too damped to react in useful time.
         */
        const val FAST_RECALIBRATION_TRIGGER_FRAMES: Int = 45

        /** Frame-time multiple that counts as "over budget" for the thermal guard. */
        const val OVER_BUDGET_FACTOR: Double = 1.6

        /**
         * Lower bound on the frame time used for the direct rescale, so a single hitch cannot
         * collapse the resolution.
         */
        const val MIN_MEASURED_FRAME_MS: Double = 8.0

        /** Refresh rates at or above this render every other vsync to hold 60 fps. */
        const val HIGH_REFRESH_CUTOFF_HZ: Double = 118.0

        /** Refresh rates at or above this (but below [HIGH_REFRESH_CUTOFF_HZ]) pace to half the panel. */
        const val MID_REFRESH_CUTOFF_HZ: Double = 85.0

        /** Slowest frame time the adaptive controller will chase (30 fps). */
        const val MAX_TARGET_FRAME_MS: Double = 1000.0 / 15.0

        /** Fastest frame time the adaptive controller will chase (120 fps). */
        const val MIN_TARGET_FRAME_MS: Double = 8.33

        /**
         * Frame time the adaptive controller and the render gate should aim for on a display with
         * the given refresh rate.
         *
         * Vsync quantises the achievable rates: a 60 Hz panel can hold 60, a 120 Hz panel holds 60
         * by rendering every other refresh, but a 90 Hz panel can only hold 90 or 45 -- chasing 60
         * there makes the PID drain the resolution toward `MIN_SCALE` for a frame time it can never
         * reach. Pacing to half the panel keeps the picture sharp and the motion even.
         */
        fun targetFrameTimeMs(refreshRateHz: Double, fpsLimit: Int = Scheduler.TARGET_FPS): Double {
            val rate = if (refreshRateHz.isFinite() && refreshRateHz > 0.0) refreshRateHz else 60.0
            val limit = fpsLimit.coerceIn(1, Scheduler.TARGET_FPS).toDouble()
            // An integer number of vsyncs: 60→60, 90→45, 120→60, 144→48, 165→55.
            // Small tolerance accommodates panels that report 60.01 rather than exactly 60 Hz.
            val divisor = kotlin.math.ceil(rate / limit - 0.03).coerceAtLeast(1.0)
            return (1000.0 / minOf(rate / divisor, limit)).coerceIn(MIN_TARGET_FRAME_MS, MAX_TARGET_FRAME_MS)
        }
    }

    /** WebGL Context Attributes (Power Management) -> GLSurfaceView / EGL attributes on Android. */
    object Context {
        /** Depth buffer disabled if 2D quad render (saves bandwidth). */
        const val DEPTH: Boolean = false

        /** Disable MSAA as we use ray-marching (saves GPU). */
        const val ANTIALIAS: Boolean = false

        const val STENCIL: Boolean = false

        const val ALPHA: Boolean = false
    }
}
