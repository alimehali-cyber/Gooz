package com.zig.chal.physics

import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalSimulationParams

/** Uses the GLES shader's actual camera placement, including its near-horizon safety relocation. */
object ChalObserverReadouts {
    fun fallbackRadius(params: ChalSimulationParams): Double {
        val radius = params.zoom * 2.0 // the shader deliberately does NOT multiply camera distance by mass
        val horizon = ChalKerrMetric.calculateEventHorizon(params.mass, params.spin)
        return if (ChalRenderPolicy.hasRayMarching(params.features.rayTracingQuality) && radius < horizon * 1.5) {
            horizon * 1.5
        } else radius
    }

    fun clockRate(params: ChalSimulationParams): Double {
        val radius = fallbackRadius(params)
        // A stationary Schwarzschild observer cannot exist inside 2M; do not display a fake rate
        // for a near-horizon Kerr camera that may lie inside that limit.
        return if (radius <= 2.0 * params.mass) Double.NaN else ChalKerrMetric.calculateTimeDilation(radius, params.mass)
    }

    fun redshift(clockRate: Double): Double =
        if (clockRate.isNaN()) Double.NaN else if (clockRate > 0.0 && clockRate.isFinite()) 1.0 / clockRate - 1.0 else Double.POSITIVE_INFINITY
}
