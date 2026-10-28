package com.zig.gargantua.disk

import kotlin.math.*

/**
 * Exact analytical Innermost Stable Circular Orbit (ISCO) calculation for Kerr spacetime.
 *
 * Based on the classical Bardeen, Press, and Teukolsky (1972) formulation:
 *   Z_1 = 1 + (1 - a_*²)^(1/3) [ (1 + a_*)^(1/3) + (1 - a_*)^(1/3) ]
 *   Z_2 = √( 3 a_*² + Z_1² )
 *   r_ISCO = M [ 3 + Z_2 ∓ √( (3 - Z_1)(3 + Z_1 + 2 Z_2) ) ]
 *
 * SIGN CONVENTION (consistent with Phase M2/M3):
 * - a_* = a / M ∈ (-1, 1)
 * - Prograde (co-rotating, a_* > 0): minus sign (-), r_ISCO decreases from 6M to 1M as a_* -> 1.
 * - Retrograde (counter-rotating, a_* < 0): plus sign (+), r_ISCO increases from 6M to 9M as a_* -> -1.
 * - Schwarzschild limit (a_* = 0): r_ISCO = 6M.
 */
object KerrIsco {

    /**
     * Computes the equatorial circular orbit ISCO radius in units of M.
     *
     * @param M Black hole mass (> 0).
     * @param a Kerr spin parameter with sign, |a| < M.
     * @return Physical ISCO coordinate radius r_ISCO.
     */
    fun compute(M: Double, a: Double): Double {
        require(M > 0.0) { "Black hole mass must be positive, got M=$M" }
        val aStarRaw = a / M
        require(abs(aStarRaw) < 1.0) {
            "Dimensionless spin |a*| must be strictly sub-extremal (< 1.0), got $aStarRaw"
        }

        // Handle exact Schwarzschild limit
        if (abs(aStarRaw) < 1e-15) {
            return 6.0 * M
        }

        // Clamp very close to extremal limit for numerical stability in float/double root evaluation
        val aStar = aStarRaw.coerceIn(-0.999999999, 0.999999999)
        val absA = abs(aStar)

        val oneMinusA2 = max(0.0, 1.0 - absA * absA)
        val cbrtOneMinusA2 = oneMinusA2.pow(1.0 / 3.0)
        val cbrtPlus = (1.0 + absA).pow(1.0 / 3.0)
        val cbrtMinus = max(0.0, 1.0 - absA).pow(1.0 / 3.0)

        val z1 = 1.0 + cbrtOneMinusA2 * (cbrtPlus + cbrtMinus)
        val z2 = sqrt(3.0 * absA * absA + z1 * z1)

        val factorUnderRadical = max(0.0, (3.0 - z1) * (3.0 + z1 + 2.0 * z2))
        val radical = sqrt(factorUnderRadical)

        val rIscoOverM = if (aStar >= 0.0) {
            // Prograde (co-rotating)
            3.0 + z2 - radical
        } else {
            // Retrograde (counter-rotating)
            3.0 + z2 + radical
        }

        return M * rIscoOverM
    }

    /**
     * Computes the prograde (co-rotating) ISCO coordinate radius.
     */
    fun progradeIscoRadius(M: Double, a: Double): Double = compute(M, abs(a))

    /**
     * Computes the retrograde (counter-rotating) ISCO coordinate radius.
     */
    fun retrogradeIscoRadius(M: Double, a: Double): Double = compute(M, -abs(a))
}
