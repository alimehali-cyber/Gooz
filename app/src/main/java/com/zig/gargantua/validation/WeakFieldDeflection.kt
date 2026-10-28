package com.zig.gargantua.validation

import kotlin.math.*

/**
 * Computes gravitational light deflection in Schwarzschild spacetime
 * and validates numerical geodesic integration against weak-field post-Newtonian theory.
 *
 * PARAMETER DEFINITIONS:
 * 1. Asymptotic impact parameter b:
 *    The perpendicular offset of the incoming photon ray at spatial infinity: b = L_z / E.
 *    In terms of asymptotic impact parameter b, the post-Newtonian expansion is:
 *      α(b) = 4M/b + (15π/4) * (M/b)² + O((M/b)³)
 *    where 15π/4 ≈ 11.78097245.
 *
 * 2. Closest-approach radius r_0 (periastron distance):
 *    The coordinate distance of closest approach where dr/dλ = 0.
 *    Related to b by b = r_0 / √(1 - 2M/r_0), or r_0 = b [ 1 - M/b - 1/2 (M/b)² + ... ].
 *    In terms of closest approach r_0, the expansion (Bodenner & Will 2003, Keeton & Petters 2005) is:
 *      α(r_0) = 4M/r_0 + (15π - 16)/4 * (M/r_0)² + O((M/r_0)³)
 *    where (15π - 16)/4 ≈ 7.78097245.
 *
 * Note: α(b) and α(r_0) describe the identical physical deflection angle expressed under different parameters:
 *   4M/r_0 + (15π - 16)/4 (M/r_0)² = 4M/[b(1 - M/b)] + ... = 4M/b + 4(M/b)² + (15π - 16)/4 (M/b)² = 4M/b + (15π/4) (M/b)².
 */
object WeakFieldDeflection {

    /**
     * Leading-order Einstein weak-field deflection angle in radians:
     * α_leading = 4M / b
     */
    fun leadingOrderDeflection(M: Double, b: Double): Double {
        require(b > 0.0) { "Impact parameter b must be positive, got b=$b" }
        return 4.0 * M / b
    }

    /**
     * Second-order Post-Newtonian (2PN) deflection in terms of asymptotic impact parameter b:
     * α_2PN(b) = 4M/b + (15π/4) * (M/b)²
     */
    fun secondOrderDeflectionImpactParameter(M: Double, b: Double): Double {
        val mOverB = M / b
        val c2 = 15.0 * Math.PI / 4.0 // ≈ 11.78097245
        return 4.0 * mOverB + c2 * mOverB * mOverB
    }

    /**
     * Second-order Post-Newtonian (2PN) deflection in terms of closest-approach radius r_0:
     * α_2PN(r_0) = 4M/r_0 + (15π - 16)/4 * (M/r_0)²
     */
    fun secondOrderDeflectionClosestApproach(M: Double, r0: Double): Double {
        val mOverR0 = M / r0
        val c2 = (15.0 * Math.PI - 16.0) / 4.0 // ≈ 7.78097245
        return 4.0 * mOverR0 + c2 * mOverR0 * mOverR0
    }

    /** Default 2PN expansion using asymptotic impact parameter b. */
    fun secondOrderDeflection(M: Double, b: Double): Double {
        return secondOrderDeflectionImpactParameter(M, b)
    }

    /**
     * Finds the closest approach radius r_0 for a photon with asymptotic impact parameter b:
     * 1/b² - (1/r_0)² (1 - 2M/r_0) = 0
     */
    fun findClosestApproachRadius(M: Double, b: Double): Double {
        require(b > 3.0 * sqrt(3.0) * M) {
            "Impact parameter b=$b must exceed critical impact parameter b_crit = ${3.0 * sqrt(3.0) * M}"
        }
        var u0 = 1.0 / b
        val invB2 = 1.0 / (b * b)
        for (iter in 0 until 50) {
            val f = invB2 - u0 * u0 * (1.0 - 2.0 * M * u0)
            val fPrime = -2.0 * u0 + 6.0 * M * u0 * u0
            val deltaU = f / fPrime
            u0 -= deltaU
            if (abs(deltaU) < 1e-15) break
        }
        return 1.0 / u0
    }

    /**
     * Computes the exact relativistic deflection angle by numerical quadrature:
     * Δφ = 2 ∫_0^{u_0} du / √( 1/b² - u²(1 - 2Mu) ) - π
     */
    fun exactSchwarzschildDeflection(M: Double, b: Double, intervals: Int = 4000): Double {
        val r0 = findClosestApproachRadius(M, b)
        val u0 = 1.0 / r0

        // Regularize coordinate singularity at u -> u_0 using u = u_0 * sin²(w)
        val nw = intervals
        val dw = (Math.PI * 0.5) / nw
        var integralSum = 0.0

        for (i in 0 until nw) {
            val w = (i + 0.5) * dw
            val sinW = sin(w)
            val u = u0 * sinW * sinW

            val factor = (u0 + u) - 2.0 * M * (u0 * u0 + u0 * u + u * u)
            if (factor > 0.0) {
                val integrand = (2.0 * u0 * sinW) / sqrt(u0 * factor)
                integralSum += integrand * dw
            }
        }

        return 2.0 * integralSum - Math.PI
    }

    data class DeflectionComparison(
        val impactParameter: Double,
        val closestApproachRadius: Double,
        val leadingOrderAngle: Double,
        val secondOrderImpactAngle: Double,
        val secondOrderClosestApproachAngle: Double,
        val exactAngle: Double,
        val leadingOrderRelativeError: Double,
        val secondOrderRelativeError: Double
    )

    fun compare(M: Double, b: Double): DeflectionComparison {
        val r0 = findClosestApproachRadius(M, b)
        val leading = leadingOrderDeflection(M, b)
        val secondB = secondOrderDeflectionImpactParameter(M, b)
        val secondR0 = secondOrderDeflectionClosestApproach(M, r0)
        val exact = exactSchwarzschildDeflection(M, b)

        val errLeading = abs(exact - leading) / exact
        val errSecond = abs(exact - secondB) / exact

        return DeflectionComparison(
            impactParameter = b,
            closestApproachRadius = r0,
            leadingOrderAngle = leading,
            secondOrderImpactAngle = secondB,
            secondOrderClosestApproachAngle = secondR0,
            exactAngle = exact,
            leadingOrderRelativeError = errLeading,
            secondOrderRelativeError = errSecond
        )
    }
}
