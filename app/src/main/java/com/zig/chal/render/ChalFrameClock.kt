package com.zig.chal.render

/**
 * Measures time between admitted frames, not between GLSurfaceView callbacks. Skipped vsyncs must
 * accumulate: resetting the timestamp on every callback permanently stalls a half-rate renderer.
 * The caller resets this clock after lifecycle/pause transitions so background time is not animated.
 */
class ChalFrameClock {
    private var lastFrameMs: Double? = null

    fun reset(nowMs: Double) {
        lastFrameMs = nowMs.takeIf { it.isFinite() }
    }

    /** Milliseconds until [frameDelta] would admit a frame; 0 when one is due or the clock is unset. */
    fun remainingMs(nowMs: Double, budgetMs: Double): Double {
        val last = lastFrameMs ?: return 0.0
        if (!nowMs.isFinite() || !budgetMs.isFinite() || budgetMs <= 0.0 || nowMs < last) return 0.0
        return (budgetMs * ADMIT_FRACTION - (nowMs - last)).coerceAtLeast(0.0)
    }

    fun frameDelta(nowMs: Double, budgetMs: Double, force: Boolean = false): Double? {
        if (!nowMs.isFinite() || !budgetMs.isFinite() || budgetMs <= 0.0) return null
        val last = lastFrameMs
        if (last == null || nowMs < last) {
            lastFrameMs = nowMs
            return 0.0
        }
        val elapsed = nowMs - last
        if (!force && elapsed < budgetMs * ADMIT_FRACTION) return null
        lastFrameMs = nowMs
        return elapsed
    }

    private companion object {
        /** A callback that arrives a hair early (vsync jitter) is still admitted. */
        const val ADMIT_FRACTION = 0.95
    }
}
