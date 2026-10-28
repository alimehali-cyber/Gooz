package com.alijafari.red.astronomy.ui.backdrop

import com.alijafari.red.astronomy.ui.rendering.SkyTimeModel

/**
 * The inputs that determine one Live Sky computation. This is a pure value.
 *
 * It carries the same inputs the Home hero uses: the base instant (simulation or live clock) and the drag offset
 * (including the animated return to zero), plus the selected observer. Data-class equality decides whether work is
 * redone, so an identical request never restarts the computation.
 *
 * Changes are not quantised here. The live clock already ticks once a second, the same as Home does, so the backdrop
 * sees the same base instant Home sees. The throttling of the computation is done by the frame pipeline.
 */
data class LiveSkyFrameRequest(
    val visible: Boolean,
    val baseTimeMs: Long,
    val dragOffsetHours: Float,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double
) {
    val needsCompute: Boolean
        get() = visible

    /** The Julian Date the sky is computed for. Same formula as the Home hero. */
    val julianDate: Double
        get() = SkyTimeModel.effectiveJd(baseTimeMs, dragOffsetHours)
}
