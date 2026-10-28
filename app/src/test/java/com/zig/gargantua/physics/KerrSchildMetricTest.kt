package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates the Kerr-Schild covariant metric g_μν, contravariant metric g^μν,
 * metric inversion identity, null vector properties, and physical limits.
 */
class KerrSchildMetricTest {

    @Test
    fun metricInverseIdentitySatisfiedAcrossTestGrid() {
        val testConfigs = listOf(
            Pair(1.0, 0.0),    // Schwarzschild
            Pair(1.0, 0.5),    // Moderate spin
            Pair(1.0, -0.7),   // Moderate negative spin
            Pair(1.0, 0.95),   // High spin
            Pair(1.0, -0.999), // Near-extremal retrograde
            Pair(2.5, 2.4)     // Scaled mass
        )

        var globalMaxResidual = 0.0

        for ((M, a) in testConfigs) {
            val spacetime = KerrSchildSpacetime(M, a)
            val rHorizon = spacetime.rPlus

            // Test points outside the horizon across diverse angles and radii
            val testRadii = listOf(rHorizon + 0.05, rHorizon + 0.5, 5.0 * M, 20.0 * M, 100.0 * M)
            val testAngles = listOf(0.1, 0.5, 1.0, 1.5707963, 2.5)

            for (r in testRadii) {
                for (th in testAngles) {
                    val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r, th, phiKS = 0.6)
                    val residual = spacetime.checkMetricInversionResidual(pos.X, pos.Y, pos.Z)
                    if (residual > globalMaxResidual) {
                        globalMaxResidual = residual
                    }
                    assertTrue("Metric inversion residual must be < 1e-12 at r=$r, got $residual", residual < 1e-12)

                    // Also check metric symmetry
                    val gLower = spacetime.metric(pos.X, pos.Y, pos.Z)
                    val gUpper = spacetime.inverseMetric(pos.X, pos.Y, pos.Z)
                    assertTrue("Covariant metric must be symmetric", gLower.isSymmetric(1e-13))
                    assertTrue("Contravariant metric must be symmetric", gUpper.isSymmetric(1e-13))
                }
            }
        }

        // Section 6C reporting requirement: verify maximum residual over deterministic test grid
        assertTrue("Global maximum residual over grid must be < 1e-12 (observed: $globalMaxResidual)", globalMaxResidual < 1e-12)
    }

    @Test
    fun kerrNullVectorPropertiesPreserved() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.8)

        val testPoints = listOf(
            Triple(5.0, 2.0, 1.0),
            Triple(3.0, -4.0, 2.0),
            Triple(10.0, 10.0, 5.0),
            Triple(0.01, 0.01, 3.0),
            Triple(100.0, 0.0, 50.0)
        )

        for ((x, y, z) in testPoints) {
            // 1. Null with respect to Minkowski background: η^μν l_μ l_ν = 0
            val resEta = spacetime.checkNullVectorMinkowskiResidual(x, y, z)
            assertEquals("l_μ must be null with respect to η", 0.0, resEta, 1e-13)

            // 2. Null with respect to full physical metric: g^μν l_μ l_ν = 0
            val resG = spacetime.checkNullVectorFullMetricResidual(x, y, z)
            assertEquals("l_μ must be null with respect to g^μν", 0.0, resG, 1e-13)
        }
    }

    @Test
    fun schwarzschildReductionMatchesAnalyticKerrSchildForm() {
        val M = 1.5
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)

        val x = 3.0
        val y = 4.0
        val z = 12.0
        val R = sqrt(x * x + y * y + z * z) // 13.0

        val g = spacetime.metric(x, y, z)
        val twoH = 2.0 * M / R

        // Expected Schwarzschild Kerr-Schild metric:
        // g_TT = -1 + 2M/R
        // g_Ti = 2M/R * (x_i / R)
        // g_ij = δ_ij + 2M/R * (x_i x_j / R²)
        assertEquals("g_TT must match -1 + 2M/R", -1.0 + twoH, g[0, 0], 1e-14)
        assertEquals("g_TX must match 2M x / R²", twoH * (x / R), g[0, 1], 1e-14)
        assertEquals("g_TY must match 2M y / R²", twoH * (y / R), g[0, 2], 1e-14)
        assertEquals("g_TZ must match 2M z / R²", twoH * (z / R), g[0, 3], 1e-14)
        assertEquals("g_XX must match 1 + 2M x² / R³", 1.0 + twoH * (x * x) / (R * R), g[1, 1], 1e-14)
        assertEquals("g_XY must match 2M x y / R³", twoH * (x * y) / (R * R), g[1, 2], 1e-14)

        // Inverse metric
        val gInv = spacetime.inverseMetric(x, y, z)
        assertEquals("g^TT must match -(1 + 2M/R)", -(1.0 + twoH), gInv[0, 0], 1e-14)
        assertEquals("g^TX must match 2M x / R²", twoH * (x / R), gInv[0, 1], 1e-14)

        // Event horizon is strictly at R = 2M
        assertEquals("Schwarzschild horizon must equal 2M", 2.0 * M, spacetime.rPlus, 1e-14)
    }

    @Test
    fun minkowskiLimitProducesFlatMetric() {
        // As mass M -> 0 with finite spin a, spacetime reduces to flat Minkowski space
        // expressed in oblate spheroidal Kerr-Schild coordinates: H = 0, g_μν = η_μν
        val a = 0.7
        val r = 5.0
        val z = 2.0
        val denom = r * r * r * r + a * a * z * z

        // H = M r³ / (r⁴ + a² Z²) -> 0 as M -> 0
        val H_limit = (0.0 * r * r * r) / denom
        assertEquals("Scalar H must vanish in Minkowski limit", 0.0, H_limit, 1e-16)

        // Small mass limit
        val M_small = 1e-12
        val spacetime = KerrSchildSpacetime(M = M_small, a = 0.5 * M_small)
        val g = spacetime.metric(X = 5.0, Y = 3.0, Z = 2.0)
        val eta = MetricTensor4.MINKOWSKI_LOWER

        for (i in 0 until 4) {
            for (j in 0 until 4) {
                assertEquals("g_μν must approach η_μν as M -> 0", eta[i, j], g[i, j], 1e-11)
            }
        }
    }

    @Test
    fun ergosphereBoundaryMatchesStaticLimitCondition() {
        // The static limit / ergosphere boundary occurs where the time-translation Killing vector
        // becomes null: g_TT = 0 <=> 2H = 1 <=> r = M + √(M² - a² cos²θ).
        val M = 1.0
        val a = 0.8
        val spacetime = KerrSchildSpacetime(M, a)

        val thetas = listOf(0.0, 0.4, 0.785, 1.2, Math.PI * 0.5)

        for (th in thetas) {
            val rErgo = spacetime.params.rErgosphere(th)
            val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r = rErgo, theta = th, phiKS = 0.0)

            // On the boundary, g_TT must be zero within tight tolerance
            val g = spacetime.metric(pos.X, pos.Y, pos.Z)
            assertEquals("g_TT on ergosphere boundary must be 0", 0.0, g[0, 0], 1e-11)

            // Outside the ergosphere (r > r_E), g_TT < 0 (timelike Killing vector)
            val posOutside = KerrSchildCoordinates.fromBoyerLindquist(a, r = rErgo + 0.5, theta = th, phiKS = 0.0)
            val gOutside = spacetime.metric(posOutside.X, posOutside.Y, posOutside.Z)
            assertTrue("Outside ergosphere, g_TT must be negative", gOutside[0, 0] < 0.0)

            // Inside the ergosphere (r_+ < r < r_E for th != 0), g_TT > 0 (spacelike Killing vector)
            if (th > 0.05) {
                val rMid = 0.5 * (spacetime.rPlus + rErgo)
                if (rMid > spacetime.rPlus && rMid < rErgo) {
                    val posInside = KerrSchildCoordinates.fromBoyerLindquist(a, r = rMid, theta = th, phiKS = 0.0)
                    val gInside = spacetime.metric(posInside.X, posInside.Y, posInside.Z)
                    assertTrue("Inside ergosphere, g_TT must be positive", gInside[0, 0] > 0.0)
                }
            }
        }
    }
}
