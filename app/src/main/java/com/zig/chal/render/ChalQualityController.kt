package com.zig.chal.render

import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalRenderPolicy

/** Optional, conservative quality selection from real presented FPS, not a device's RAM size. */
class ChalQualityController {
    private var lowSince: Double? = null
    private var highSince: Double? = null
    private var ceilingIndex: Int? = null
    private var lastChangeMs = Double.NEGATIVE_INFINITY

    fun reset() {
        lowSince = null
        highSince = null
        ceilingIndex = null
        lastChangeMs = Double.NEGATIVE_INFINITY
    }

    fun observe(fps: Int, targetFps: Int, quality: ChalRayTracingQuality, native: Boolean, nowMs: Double): ChalRayTracingQuality? {
        if (fps <= 0 || targetFps <= 0 || !nowMs.isFinite()) {
            lowSince = null
            highSince = null
            return null
        }
        val tiers = if (native) ChalRenderPolicy.nativeQualities else ChalRenderPolicy.fallbackQualities
        val index = tiers.indexOf(ChalRenderPolicy.quality(quality, native))
        if (fps < targetFps * 0.75) {
            highSince = null
            if (lowSince == null) lowSince = nowMs
            if (nowMs - lowSince!! >= 3_000.0 && nowMs - lastChangeMs >= 5_000.0 && index > 0) {
                lowSince = null
                ceilingIndex = minOf(ceilingIndex ?: tiers.lastIndex, index - 1)
                lastChangeMs = nowMs
                return tiers[index - 1]
            }
        } else if (fps >= targetFps * 0.95) {
            lowSince = null
            if (highSince == null) highSince = nowMs
            if (nowMs - highSince!! >= 12_000.0 && nowMs - lastChangeMs >= 12_000.0 && index < minOf(tiers.lastIndex, ceilingIndex ?: tiers.lastIndex)) {
                highSince = null
                lastChangeMs = nowMs
                return tiers[index + 1]
            }
        } else {
            lowSince = null
            highSince = null
        }
        return null
    }
}
