package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Fundamental physical parameters for Kerr spacetime in geometrized units (G = c = 1).
 *
 * Primary Reference:
 *   Kyleyhw "black_hole" repository (Kerr metric and geodesic formulation).
 * Supporting References:
 *   Misner, Thorne, Wheeler (1973) Gravitation, §33.
 *   Carroll (2004) Spacetime and Geometry, §6.
 *
 * CONVENTIONS:
 * - Units: Geometrized units where G = c = 1. Mass M and spin a have dimensions of length [L].
 * - Dimensionless spin: a* = a / M in [-1.0, 1.0].
 * - Orientation: Spin vector points along the positive Z-axis (J = M a z_hat).
 *   Prograde rotation corresponds to positive a; retrograde rotation corresponds to negative a.
 * - Horizons:
 *     r_+ = M + √(M² - a²)  (Event horizon)
 *     r_- = M - √(M² - a²)  (Cauchy / inner horizon)
 * - Static Limit / Ergosphere:
 *     r_E(θ) = M + √(M² - a² cos²θ)
 *     Inside the ergosphere (r_+ < r < r_E), the asymptotic time-translation Killing vector
 *     becomes spacelike (g_TT > 0), forcing all physical particles to co-rotate with the black hole.
 */
data class KerrParameters(
    val M: Double,
    val a: Double
) {
    init {
        require(!M.isNaN() && !M.isInfinite() && M > 0.0) {
            "Black hole mass M must be finite and strictly positive, got $M"
        }
        require(!a.isNaN() && !a.isInfinite() && abs(a) <= M) {
            "Black hole spin |a| must not exceed mass M (got a=$a, M=$M). Super-extremal spacetimes are unphysical."
        }
    }

    /** Dimensionless spin parameter a* = a / M in [-1.0, 1.0]. */
    val aStar: Double get() = a / M

    /** Event horizon radius r_+ = M + √(M² - a²). */
    val rPlus: Double get() = M + sqrt(max(0.0, M * M - a * a))

    companion object {
        /** Computes the outer event horizon radius r_+ = M + √(M² - a²). */
        fun outerHorizonRadius(M: Double, a: Double): Double = KerrParameters(M, a).rPlus
    }

    /** Cauchy (inner) horizon radius r_- = M - √(M² - a²). */
    val rMinus: Double get() = M - sqrt(max(0.0, M * M - a * a))

    /** Whether the black hole is extremal (|a| == M). */
    val isExtremal: Boolean get() = abs(a) == M

    /**
     * Radius of the ergosphere (static limit surface) at colatitude θ:
     * r_E(θ) = M + √(M² - a² cos²θ).
     */
    fun rErgosphere(theta: Double): Double {
        val cosT = cos(theta)
        return M + sqrt(max(0.0, M * M - a * a * cosT * cosT))
    }

    /**
     * Radius of the ergosphere computed from Kerr-Schild Cartesian coordinates:
     * cos²θ = Z² / r².
     */
    fun rErgosphereFromZ(r: Double, Z: Double): Double {
        if (r <= 0.0) return M + sqrt(max(0.0, M * M))
        val cos2T = (Z * Z) / (r * r)
        return M + sqrt(max(0.0, M * M - a * a * cos2T.coerceIn(0.0, 1.0)))
    }

    /**
     * Checks if a given radial coordinate r lies inside or on the event horizon (r <= r_+).
     */
    fun isInsideHorizon(r: Double): Boolean = r <= rPlus

    /**
     * Checks if a Kerr-Schild Cartesian point (X, Y, Z) lies inside or on the event horizon.
     */
    fun isInsideHorizonCartesian(X: Double, Y: Double, Z: Double): Boolean {
        val r = KerrSchildCoordinates.computeR(a, X, Y, Z)
        return isInsideHorizon(r)
    }

    /**
     * Checks if a point lies inside or on the ergosphere boundary.
     */
    fun isInsideErgosphere(r: Double, theta: Double): Boolean {
        return r <= rErgosphere(theta)
    }
}
