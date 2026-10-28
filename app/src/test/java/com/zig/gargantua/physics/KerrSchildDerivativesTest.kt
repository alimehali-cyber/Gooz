package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates the exact analytical spatial gradients of the contravariant metric:
 *   ∂_X g^μν, ∂_Y g^μν, ∂_Z g^μν
 * against an independent high-accuracy 4th-order finite-difference validation oracle.
 *
 * SECTION 7 VALIDATION METHODOLOGY:
 * - Oracle Method: 4th-order central finite difference:
 *     f'(x) ≈ [ -f(x + 2h) + 8 f(x + h) - 8 f(x - h) + f(x - 2h) ] / (12 h)
 * - Step Size Justification: h = 1.0e-5.
 *     In IEEE 754 Double precision (machine epsilon ε ≈ 1.1e-16), 4th-order truncation
 *     error is O(h⁴) ≈ 1.0e-20 (negligible), while roundoff error is O(ε / h) ≈ 1.0e-11.
 *     Therefore, the finite-difference approximation has a theoretical error bound of ~1.0e-10.
 * - Tolerance: Absolute difference |analytic - finite_diff| < 1.0e-8 across all 16 components
 *     for all 3 spatial directions (48 derivative components per test point).
 * - Scope: Covers weak field, moderate field, strong field outside the horizon,
 *     positive and negative spin, and near-axis configurations.
 */
class KerrSchildDerivativesTest {

    private val oracleStepH = 1.0e-5
    private val derivativeTolerance = 1.0e-8

    /**
     * Independent 4th-order central finite difference oracle for g^μν spatial gradients.
     * Note: This oracle is used ONLY in tests, never in production code.
     */
    private fun computeFiniteDifferenceGradients(
        spacetime: KerrSchildSpacetime,
        X: Double,
        Y: Double,
        Z: Double
    ): KerrSchildDerivatives.SpatialMetricDerivatives {
        val h = oracleStepH

        fun diffDirection(dir: Int): MetricTensor4 {
            fun evalAt(delta: Double): MetricTensor4 {
                val dx = if (dir == 1) delta else 0.0
                val dy = if (dir == 2) delta else 0.0
                val dz = if (dir == 3) delta else 0.0
                return spacetime.inverseMetric(X + dx, Y + dy, Z + dz)
            }

            val g_m2 = evalAt(-2.0 * h)
            val g_m1 = evalAt(-h)
            val g_p1 = evalAt(h)
            val g_p2 = evalAt(2.0 * h)

            val dg = DoubleArray(16)
            val denom = 12.0 * h
            for (mu in 0 until 4) {
                val offset = mu * 4
                for (nu in 0 until 4) {
                    val idx = offset + nu
                    dg[idx] = (-g_p2.elements[idx] + 8.0 * g_p1.elements[idx] - 8.0 * g_m1.elements[idx] + g_m2.elements[idx]) / denom
                }
            }
            return MetricTensor4(dg)
        }

        return KerrSchildDerivatives.SpatialMetricDerivatives(
            d_dX = diffDirection(1),
            d_dY = diffDirection(2),
            d_dZ = diffDirection(3)
        )
    }

    private fun assertDerivativesMatch(
        expectedOracle: KerrSchildDerivatives.SpatialMetricDerivatives,
        actualAnalytic: KerrSchildDerivatives.SpatialMetricDerivatives,
        context: String
    ) {
        for (dir in 1..3) {
            val dirName = when (dir) { 1 -> "X"; 2 -> "Y"; else -> "Z" }
            val expTensor = expectedOracle[dir]
            val actTensor = actualAnalytic[dir]

            for (mu in 0 until 4) {
                for (nu in 0 until 4) {
                    val exp = expTensor[mu, nu]
                    val act = actTensor[mu, nu]
                    val diff = abs(act - exp)
                    assertTrue(
                        "Derivative mismatch for ∂_$dirName g^[$mu,$nu] in $context: analytic=$act, oracle=$exp, diff=$diff",
                        diff < derivativeTolerance
                    )
                }
            }
        }
    }

    @Test
    fun weakFieldDerivatives() {
        // Asymptotically flat weak-field regime (R = 100M and 1000M)
        val M = 1.0
        val a = 0.5
        val spacetime = KerrSchildSpacetime(M, a)

        val testPoints = listOf(
            Triple(100.0, 50.0, 30.0),
            Triple(-500.0, 400.0, -300.0),
            Triple(1000.0, 0.0, 500.0)
        )

        for ((x, y, z) in testPoints) {
            val analytic = spacetime.derivativesOfInverseMetric(x, y, z)
            val oracle = computeFiniteDifferenceGradients(spacetime, x, y, z)
            assertDerivativesMatch(oracle, analytic, "Weak-field at ($x, $y, $z)")
        }
    }

    @Test
    fun moderateFieldDerivatives() {
        val M = 1.0
        val spacetimePrograde = KerrSchildSpacetime(M, a = 0.7)
        val spacetimeRetrograde = KerrSchildSpacetime(M, a = -0.7)

        val testPoints = listOf(
            Triple(4.0, 3.0, 2.0),
            Triple(-5.0, 4.0, -3.0),
            Triple(3.0, -4.0, 5.0)
        )

        for ((x, y, z) in testPoints) {
            // Prograde
            val anPro = spacetimePrograde.derivativesOfInverseMetric(x, y, z)
            val orPro = computeFiniteDifferenceGradients(spacetimePrograde, x, y, z)
            assertDerivativesMatch(orPro, anPro, "Moderate prograde at ($x, $y, $z)")

            // Retrograde
            val anRetro = spacetimeRetrograde.derivativesOfInverseMetric(x, y, z)
            val orRetro = computeFiniteDifferenceGradients(spacetimeRetrograde, x, y, z)
            assertDerivativesMatch(orRetro, anRetro, "Moderate retrograde at ($x, $y, $z)")
        }
    }

    @Test
    fun strongFieldOutsideHorizonDerivatives() {
        // Points close to event horizon where spacetime curvature is intense
        val M = 1.0
        val a = 0.95
        val spacetime = KerrSchildSpacetime(M, a)
        val rPlus = spacetime.rPlus // 1.0 + √(1 - 0.95²) ≈ 1.31225

        // Sample points just outside event horizon
        val radii = listOf(rPlus + 0.1, rPlus + 0.3, rPlus + 0.8)
        val thetas = listOf(0.4, 0.8, 1.2, 1.57)

        for (r in radii) {
            for (th in thetas) {
                val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r, th, phiKS = 0.7)
                val analytic = spacetime.derivativesOfInverseMetric(pos.X, pos.Y, pos.Z)
                val oracle = computeFiniteDifferenceGradients(spacetime, pos.X, pos.Y, pos.Z)
                assertDerivativesMatch(oracle, analytic, "Strong-field at r=$r, θ=$th")
            }
        }
    }

    @Test
    fun nearExtremalSpinDerivatives() {
        // Near-extremal spin a/M = ±0.999
        val M = 1.0
        for (a in listOf(0.999, -0.999)) {
            val spacetime = KerrSchildSpacetime(M, a)
            val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r = 2.5, theta = 0.6, phiKS = 1.1)
            val analytic = spacetime.derivativesOfInverseMetric(pos.X, pos.Y, pos.Z)
            val oracle = computeFiniteDifferenceGradients(spacetime, pos.X, pos.Y, pos.Z)
            assertDerivativesMatch(oracle, analytic, "Near-extremal spin a=$a")
        }
    }

    @Test
    fun nearAxisDerivatives() {
        // Near polar axis (X, Y << Z) where coordinate transformations have rapid angular variations
        val M = 1.0
        val a = 0.8
        val spacetime = KerrSchildSpacetime(M, a)

        val nearAxisPoints = listOf(
            Triple(1.0e-4, 1.0e-4, 4.0),
            Triple(-1.0e-4, 2.0e-4, -5.0),
            Triple(1.0e-3, 0.0, 3.0)
        )

        for ((x, y, z) in nearAxisPoints) {
            val analytic = spacetime.derivativesOfInverseMetric(x, y, z)
            val oracle = computeFiniteDifferenceGradients(spacetime, x, y, z)
            assertDerivativesMatch(oracle, analytic, "Near-axis at ($x, $y, $z)")
        }
    }
}
