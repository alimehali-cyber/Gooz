package com.zig.gargantua.validation

import kotlin.math.abs

/**
 * 4th-Order Runge-Kutta (RK4) canonical geodesic integrator for Kerr spacetime.
 * Integrates Hamilton's canonical equations of motion along affine parameter λ:
 *   dx^μ / dλ = ∂H / ∂p_μ
 *   dp_μ / dλ = -∂H / ∂x^μ
 */
class GeodesicIntegrator(val spacetime: KerrSpacetime) {

    data class StateDerivatives(
        val dt: Double,
        val dr: Double,
        val dtheta: Double,
        val dphi: Double,
        val dp_t: Double,
        val dp_r: Double,
        val dp_theta: Double,
        val dp_phi: Double
    )

    fun computeDerivatives(state: NullHamiltonian.PhotonState): StateDerivatives {
        val r = state.r
        val theta = state.theta
        val g = spacetime.inverseMetric(r, theta)
        val dg = spacetime.inverseMetricDerivatives(r, theta)

        val pt = state.p_t
        val pr = state.p_r
        val pth = state.p_theta
        val pph = state.p_phi

        // Velocities: dx^μ / dλ = g^μν p_ν
        val dt = g.g_tt * pt + g.g_tph * pph
        val dr = g.g_rr * pr
        val dth = g.g_thth * pth
        val dph = g.g_tph * pt + g.g_phph * pph

        // Conserved canonical momenta: ∂_t g = 0 and ∂_φ g = 0
        val dpt = 0.0
        val dpph = 0.0

        // Forces: dp_μ / dλ = -1/2 (∂_μ g^αβ) p_α p_β
        val dpr = -0.5 * (
            dg.dr_g_tt * pt * pt +
            2.0 * dg.dr_g_tph * pt * pph +
            dg.dr_g_phph * pph * pph +
            dg.dr_g_rr * pr * pr +
            dg.dr_g_thth * pth * pth
        )

        val dpth = -0.5 * (
            dg.dth_g_tt * pt * pt +
            2.0 * dg.dth_g_tph * pt * pph +
            dg.dth_g_phph * pph * pph +
            dg.dth_g_rr * pr * pr +
            dg.dth_g_thth * pth * pth
        )

        return StateDerivatives(dt, dr, dth, dph, dpt, dpr, dpth, dpph)
    }

    /**
     * Single Classical 4th-Order Runge-Kutta integration step.
     */
    fun stepRK4(state: NullHamiltonian.PhotonState, dlambda: Double): NullHamiltonian.PhotonState {
        // k1
        val k1 = computeDerivatives(state)

        // k2
        val s2 = applyStep(state, k1, dlambda * 0.5)
        val k2 = computeDerivatives(s2)

        // k3
        val s3 = applyStep(state, k2, dlambda * 0.5)
        val k3 = computeDerivatives(s3)

        // k4
        val s4 = applyStep(state, k3, dlambda)
        val k4 = computeDerivatives(s4)

        // Weighted combination: y_{n+1} = y_n + h/6 (k1 + 2k2 + 2k3 + k4)
        val dt = (k1.dt + 2.0 * k2.dt + 2.0 * k3.dt + k4.dt) * (dlambda / 6.0)
        val dr = (k1.dr + 2.0 * k2.dr + 2.0 * k3.dr + k4.dr) * (dlambda / 6.0)
        val dth = (k1.dtheta + 2.0 * k2.dtheta + 2.0 * k3.dtheta + k4.dtheta) * (dlambda / 6.0)
        val dph = (k1.dphi + 2.0 * k2.dphi + 2.0 * k3.dphi + k4.dphi) * (dlambda / 6.0)
        val dpr = (k1.dp_r + 2.0 * k2.dp_r + 2.0 * k3.dp_r + k4.dp_r) * (dlambda / 6.0)
        val dpth = (k1.dp_theta + 2.0 * k2.dp_theta + 2.0 * k3.dp_theta + k4.dp_theta) * (dlambda / 6.0)

        return NullHamiltonian.PhotonState(
            t = state.t + dt,
            r = state.r + dr,
            theta = state.theta + dth,
            phi = state.phi + dph,
            p_t = state.p_t,
            p_r = state.p_r + dpr,
            p_theta = state.p_theta + dpth,
            p_phi = state.p_phi
        )
    }

    private fun applyStep(
        base: NullHamiltonian.PhotonState,
        deriv: StateDerivatives,
        factor: Double
    ): NullHamiltonian.PhotonState {
        return NullHamiltonian.PhotonState(
            t = base.t + deriv.dt * factor,
            r = base.r + deriv.dr * factor,
            theta = base.theta + deriv.dtheta * factor,
            phi = base.phi + deriv.dphi * factor,
            p_t = base.p_t + deriv.dp_t * factor,
            p_r = base.p_r + deriv.dp_r * factor,
            p_theta = base.p_theta + deriv.dp_theta * factor,
            p_phi = base.p_phi + deriv.dp_phi * factor
        )
    }

    /**
     * Integrates trajectory until maxSteps or until stopCondition returns true.
     */
    fun integrate(
        initialState: NullHamiltonian.PhotonState,
        stepSize: Double,
        maxSteps: Int,
        stopCondition: (NullHamiltonian.PhotonState) -> Boolean = { false }
    ): List<NullHamiltonian.PhotonState> {
        val path = ArrayList<NullHamiltonian.PhotonState>(maxSteps.coerceAtMost(5000))
        var current = initialState
        path.add(current)

        for (i in 0 until maxSteps) {
            if (stopCondition(current)) break
            current = stepRK4(current, stepSize)
            path.add(current)
            if (current.r <= spacetime.rPlus * 1.001) break // Horizon capture
        }

        return path
    }
}
