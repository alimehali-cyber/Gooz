package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates numerical stability, edge cases, mass scaling, and unphysical parameter rejection.
 */
class KerrSchildStabilityAndLimitsTest {

    @Test
    fun rejectsNonPositiveMass() {
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = 0.0, a = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = -1.0, a = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = Double.NaN, a = 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = Double.POSITIVE_INFINITY, a = 0.0)
        }
    }

    @Test
    fun rejectsSuperExtremalSpin() {
        // Physical black holes require |a| <= M to possess an event horizon.
        // Super-extremal spacetimes (|a| > M) possess naked singularities and must be rejected.
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = 1.0, a = 1.0001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = 1.0, a = -1.0001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = 2.0, a = 2.5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KerrParameters(M = 1.0, a = Double.NaN)
        }
    }

    @Test
    fun allowsStrictExtremalSpin() {
        // Extremal Kerr (|a| = M) has coinciding horizons r_+ = r_- = M
        val extPro = KerrParameters(M = 1.5, a = 1.5)
        assertTrue("Must recognize extremal spin", extPro.isExtremal)
        assertEquals("Extremal horizon r_+ must equal M", 1.5, extPro.rPlus, 1e-15)
        assertEquals("Extremal horizon r_- must equal M", 1.5, extPro.rMinus, 1e-15)

        val extRetro = KerrParameters(M = 2.0, a = -2.0)
        assertTrue("Must recognize extremal negative spin", extRetro.isExtremal)
        assertEquals("Extremal retrograde horizon r_+ must equal M", 2.0, extRetro.rPlus, 1e-15)
        assertEquals("Extremal retrograde horizon r_- must equal M", 2.0, extRetro.rMinus, 1e-15)
    }

    @Test
    fun nearExtremalSpinRemainsFiniteAndStable() {
        val nearExtremalSpins = listOf(0.999, -0.999, 0.9999, -0.9999, 0.999999)
        val M = 1.0

        for (a in nearExtremalSpins) {
            val spacetime = KerrSchildSpacetime(M, a)
            val rPlus = spacetime.rPlus

            // Sample points extremely close to the horizon
            for (deltaR in listOf(1.0e-4, 1.0e-2, 0.1, 1.0)) {
                val r = rPlus + deltaR
                val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r, theta = 0.7, phiKS = 1.2)

                // 1. Metric evaluation
                val g = spacetime.metric(pos.X, pos.Y, pos.Z)
                for (i in 0 until 4) {
                    for (j in 0 until 4) {
                        assertFalse("Metric must be finite at near-extremal spin", g[i, j].isNaN() || g[i, j].isInfinite())
                    }
                }

                // 2. Inverse metric evaluation
                val gInv = spacetime.inverseMetric(pos.X, pos.Y, pos.Z)
                for (i in 0 until 4) {
                    for (j in 0 until 4) {
                        assertFalse("Inverse metric must be finite", gInv[i, j].isNaN() || gInv[i, j].isInfinite())
                    }
                }

                // 3. Metric inversion identity
                val res = spacetime.checkMetricInversionResidual(pos.X, pos.Y, pos.Z)
                assertTrue("Inversion residual must remain < 1e-11 at near-extremal spin (got $res)", res < 1e-11)

                // 4. Derivatives evaluation
                val dG = spacetime.derivativesOfInverseMetric(pos.X, pos.Y, pos.Z)
                for (dir in 1..3) {
                    val dgDir = dG[dir]
                    for (i in 0 until 4) {
                        for (j in 0 until 4) {
                            assertFalse("Derivatives must be finite", dgDir[i, j].isNaN() || dgDir[i, j].isInfinite())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun largeRadiusWeakFieldAsymptotics() {
        val M = 1.0
        val a = 0.9
        val spacetime = KerrSchildSpacetime(M, a)

        val largeRadii = listOf(1.0e3, 1.0e4, 1.0e5)

        for (r in largeRadii) {
            val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r, theta = 0.8, phiKS = 0.5)

            // At large r, H = M r³ / (r⁴ + a² Z²) ~ M / r -> 0
            val rCalc = spacetime.computeR(pos.X, pos.Y, pos.Z)
            val H = spacetime.computeH(rCalc, pos.Z)
            val expectedH = M / r
            assertEquals("H must scale as M/r at large distances", expectedH, H, expectedH * 0.01)

            // Metric must approach Minkowski
            val g = spacetime.metric(pos.X, pos.Y, pos.Z)
            val eta = MetricTensor4.MINKOWSKI_LOWER
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    val expected = eta[i, j]
                    val diff = abs(g[i, j] - expected)
                    assertTrue("g[$i,$j] at r=$r must be close to η within 1e-2", diff < 1.0e-2)
                }
            }
        }
    }

    @Test
    fun massScalingSymmetry() {
        // Under geometrized mass scaling:
        // M' = λ M, a' = λ a, X' = λ X, Y' = λ Y, Z' = λ Z
        // Spacetime coordinates and lengths scale linearly: r' = λ r.
        // Dimensionless quantities are invariant:
        // H' = (λM (λr)³) / ((λr)⁴ + (λa)² (λZ)²) = H
        // l'_μ = l_μ
        // g'_μν = g_μν
        // Spatial derivatives scale as inverse length: ∂_i g'^μν = λ⁻¹ ∂_i g^μν.
        val lambda = 3.5
        val M1 = 1.0
        val a1 = 0.6
        val X1 = 4.0
        val Y1 = 3.0
        val Z1 = 2.0

        val s1 = KerrSchildSpacetime(M1, a1)
        val sScaled = KerrSchildSpacetime(M = M1 * lambda, a = a1 * lambda)

        val X2 = X1 * lambda
        val Y2 = Y1 * lambda
        val Z2 = Z1 * lambda

        // 1. Radial coordinate scales by λ
        val r1 = s1.computeR(X1, Y1, Z1)
        val r2 = sScaled.computeR(X2, Y2, Z2)
        assertEquals("Radius must scale by λ", r1 * lambda, r2, 1e-12)

        // 2. Scalar H is invariant
        val H1 = s1.computeH(r1, Z1)
        val H2 = sScaled.computeH(r2, Z2)
        assertEquals("Scalar H must be scale-invariant", H1, H2, 1e-14)

        // 3. Metric tensor components are invariant
        val g1 = s1.metric(X1, Y1, Z1)
        val g2 = sScaled.metric(X2, Y2, Z2)
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                assertEquals("Metric components must be scale-invariant", g1[i, j], g2[i, j], 1e-14)
            }
        }

        // 4. Metric spatial derivatives scale as λ⁻¹
        val dG1 = s1.derivativesOfInverseMetric(X1, Y1, Z1)
        val dG2 = sScaled.derivativesOfInverseMetric(X2, Y2, Z2)
        for (dir in 1..3) {
            val t1 = dG1[dir]
            val t2 = dG2[dir]
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    assertEquals(
                        "Derivative ∂_$dir g^[$i,$j] must scale as 1/λ",
                        t1[i, j] / lambda,
                        t2[i, j],
                        1e-14
                    )
                }
            }
        }
    }
}
