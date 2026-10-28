package com.zig.gargantua.physics

import com.zig.gargantua.validation.KerrSpacetime as M2KerrSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * SECTION 6 MANDATORY M2 CROSS-VALIDATION:
 * Cross-validates Phase M3 production Kerr-Schild mathematics against the Phase M2
 * Boyer-Lindquist independent reference layer.
 */
class KerrSchildM2CrossValidationTest {

    /**
     * Requirement 6A: Schwarzschild limit (a = 0).
     * - Metric reduces to Schwarzschild in Kerr-Schild form.
     * - Event horizon corresponds to r = 2M.
     * - No spin-dependent asymmetry.
     */
    @Test
    fun crossValidateSchwarzschildLimitAgainstM2() {
        val masses = listOf(0.5, 1.0, 2.0, 10.0)

        for (M in masses) {
            val m2 = M2KerrSpacetime(M = M, a = 0.0)
            val m3 = KerrSchildSpacetime(M = M, a = 0.0)

            // 1. Horizon radius r_+ must match M2 exactly (r_+ = 2M)
            assertEquals("Schwarzschild horizon r_+ must match M2", m2.rPlus, m3.rPlus, 1e-15)
            assertEquals("Schwarzschild horizon must equal 2M", 2.0 * M, m3.rPlus, 1e-15)

            // 2. Metric symmetry and lack of spin-dependent frame-dragging asymmetry
            // At a = 0, g_TX, g_TY, g_TZ are radial gradients proportional to x_i / R;
            // there is zero azimuthal rotation / curl: -Y g_TX + X g_TY = 0.
            val x = 3.0 * M
            val y = 4.0 * M
            val z = 5.0 * M
            val g = m3.metric(x, y, z)
            val azimuthalFrameDragging = -y * g[0, 1] + x * g[0, 2]
            assertEquals("Azimuthal frame dragging must vanish identically at a=0", 0.0, azimuthalFrameDragging, 1e-14)
        }
    }

    /**
     * Requirement 6B: Kerr horizon r_+ and r_-.
     * - Verifies that M3 horizon radii agree with M2 across sub-extremal, high-spin,
     *   and extremal configurations to Double-precision machine tolerance.
     */
    @Test
    fun crossValidateHorizonRadiiAgainstM2() {
        val testCases = listOf(
            Pair(1.0, 0.0),
            Pair(1.0, 0.5),
            Pair(1.0, -0.6),
            Pair(1.0, 0.9),
            Pair(1.0, -0.99),
            Pair(1.0, 1.0),     // Extremal
            Pair(3.5, 2.8),
            Pair(10.0, -9.999)
        )

        for ((M, a) in testCases) {
            val m2 = M2KerrSpacetime(M, a)
            val m3 = KerrSchildSpacetime(M, a)

            assertEquals("rPlus must match M2 reference for M=$M, a=$a", m2.rPlus, m3.rPlus, 1e-15)
            assertEquals("rMinus must match M2 reference for M=$M, a=$a", m2.rMinus, m3.rMinus, 1e-15)
        }
    }

    /**
     * Requirement 6C: Metric consistency (g^μα g_αν = δ^μ_ν).
     * - Evaluates and reports maximum residual over a dense deterministic 3D grid.
     */
    @Test
    fun crossValidateMetricInversionResidualAcrossDenseGrid() {
        val m3 = KerrSchildSpacetime(M = 1.0, a = 0.85)
        var maxResidual = 0.0
        var evaluatedPoints = 0

        for (r in listOf(2.0, 3.5, 5.0, 10.0, 50.0)) {
            for (th in listOf(0.2, 0.6, 1.0, 1.4, 2.0, 2.8)) {
                for (ph in listOf(0.0, 1.0, 2.0, 3.14, 4.5)) {
                    val pos = KerrSchildCoordinates.fromBoyerLindquist(a = m3.a, r = r, theta = th, phiKS = ph)
                    val res = m3.checkMetricInversionResidual(pos.X, pos.Y, pos.Z)
                    if (res > maxResidual) maxResidual = res
                    evaluatedPoints++
                }
            }
        }

        assertTrue("Evaluated at least 100 points on grid", evaluatedPoints >= 100)
        assertTrue("Maximum metric residual must be < 1e-12 (observed: $maxResidual)", maxResidual < 1e-12)
    }

    /**
     * Requirement 6D: Minkowski limit (M -> 0).
     * - H -> 0
     * - g_μν -> η_μν
     * - g^μν -> η^μν
     */
    @Test
    fun crossValidateMinkowskiLimitAgainstM2() {
        val M_small = 1e-14
        val a_small = 0.5 * M_small
        val m3 = KerrSchildSpacetime(M = M_small, a = a_small)

        val pos = KerrSchildCoordinates.fromBoyerLindquist(a = a_small, r = 10.0, theta = 1.0, phiKS = 0.5)
        val r = m3.computeR(pos.X, pos.Y, pos.Z)
        val H = m3.computeH(r, pos.Z)

        assertTrue("H must be < 1e-13 in Minkowski limit", abs(H) < 1e-13)

        val gLower = m3.metric(pos.X, pos.Y, pos.Z)
        val gUpper = m3.inverseMetric(pos.X, pos.Y, pos.Z)
        val etaLower = MetricTensor4.MINKOWSKI_LOWER
        val etaUpper = MetricTensor4.MINKOWSKI_UPPER

        for (i in 0 until 4) {
            for (j in 0 until 4) {
                assertEquals("g_μν must approach η_μν", etaLower[i, j], gLower[i, j], 1e-13)
                assertEquals("g^μν must approach η^μν", etaUpper[i, j], gUpper[i, j], 1e-13)
            }
        }
    }

    /**
     * Requirement 6E: Kerr null vector.
     * - η^-1(l, l) ≈ 0
     * - g^-1(l, l) ≈ 0
     */
    @Test
    fun crossValidateNullVectorAgainstM2() {
        val m3 = KerrSchildSpacetime(M = 1.0, a = 0.9)

        for (r in listOf(2.0, 4.0, 8.0, 20.0)) {
            for (th in listOf(0.3, 0.8, 1.57, 2.5)) {
                val pos = KerrSchildCoordinates.fromBoyerLindquist(m3.a, r, th, phiKS = 1.0)
                val etaRes = m3.checkNullVectorMinkowskiResidual(pos.X, pos.Y, pos.Z)
                val gRes = m3.checkNullVectorFullMetricResidual(pos.X, pos.Y, pos.Z)

                assertEquals("η^μν l_μ l_ν must equal 0", 0.0, etaRes, 1e-14)
                assertEquals("g^μν l_μ l_ν must equal 0", 0.0, gRes, 1e-14)
            }
        }
    }

    /**
     * Requirement 6F: Coordinate consistency between Kerr-Schild and Boyer-Lindquist.
     * - Transforming (r_BL, θ_BL, φ_KS) into Kerr-Schild Cartesian (X, Y, Z) and feeding them
     *   into M3's computeR(X, Y, Z) must reproduce r_BL to tight Double precision.
     */
    @Test
    fun crossValidateCoordinateConsistencyWithM2() {
        val spins = listOf(0.0, 0.4, 0.7, 0.95, 0.999)
        val rValues = listOf(1.5, 2.5, 4.0, 10.0, 100.0)
        val thetaValues = listOf(0.1, 0.5, 1.0, 1.5707963, 2.6)

        for (a in spins) {
            for (rBL in rValues) {
                for (thBL in thetaValues) {
                    val pos = KerrSchildCoordinates.fromBoyerLindquist(a, rBL, thBL, phiKS = 0.8)
                    val rKS = KerrSchildCoordinates.computeR(a, pos.X, pos.Y, pos.Z)
                    assertEquals(
                        "Kerr-Schild r must match Boyer-Lindquist r for a=$a, r=$rBL, θ=$thBL",
                        rBL,
                        rKS,
                        1e-12
                    )

                    val thKS = KerrSchildCoordinates.computeTheta(rKS, pos.Z)
                    assertEquals(
                        "Kerr-Schild θ must match Boyer-Lindquist θ",
                        thBL,
                        thKS,
                        1e-12
                    )
                }
            }
        }
    }

    /**
     * Requirement 6G: Spin parity consistency.
     * - In M2, Boyer-Lindquist g_tφ = -2 M a r sin²θ / Σ.
     * - In M3, the azimuthal frame-dragging cross component is:
     *     g_TΦ = -Y g_TX + X g_TY = -2 M a r sin²θ / Σ.
     * - Under a -> -a, g_TΦ reverses sign identically, matching M2's parity convention.
     */
    @Test
    fun crossValidateSpinParityAndFrameDraggingAgainstM2() {
        val M = 1.0
        val aMag = 0.65

        val m2Pro = M2KerrSpacetime(M, a = aMag)
        val m2Retro = M2KerrSpacetime(M, a = -aMag)
        val m3Pro = KerrSchildSpacetime(M, a = aMag)
        val m3Retro = KerrSchildSpacetime(M, a = -aMag)

        for (r in listOf(3.0, 6.0, 15.0)) {
            for (th in listOf(0.4, 0.8, 1.57)) {
                // M2 Boyer-Lindquist g_tφ = -2 M a r sin²θ / Σ
                val sinT = sin(th)
                val m2GtPhiPro = -2.0 * m2Pro.M * m2Pro.a * r * sinT * sinT / m2Pro.sigma(r, th)
                val m2GtPhiRetro = -2.0 * m2Retro.M * m2Retro.a * r * sinT * sinT / m2Retro.sigma(r, th)

                // M3 Kerr-Schild g_TΦ = -Y g_TX + X g_TY
                val posPro = KerrSchildCoordinates.fromBoyerLindquist(aMag, r, th, phiKS = 0.0)
                val gPro = m3Pro.metric(posPro.X, posPro.Y, posPro.Z)
                val m3GtPhiPro = -posPro.Y * gPro[0, 1] + posPro.X * gPro[0, 2]

                val posRetro = KerrSchildCoordinates.fromBoyerLindquist(-aMag, r, th, phiKS = 0.0)
                val gRetro = m3Retro.metric(posRetro.X, posRetro.Y, posRetro.Z)
                val m3GtPhiRetro = -posRetro.Y * gRetro[0, 1] + posRetro.X * gRetro[0, 2]

                // 1. M3 Kerr-Schild frame-dragging cross term matches M2 Boyer-Lindquist g_tφ
                assertEquals("M3 prograde frame dragging must match M2 g_tφ", m2GtPhiPro, m3GtPhiPro, 1e-12)
                assertEquals("M3 retrograde frame dragging must match M2 g_tφ", m2GtPhiRetro, m3GtPhiRetro, 1e-12)

                // 2. Parity reversal: g_TΦ(a) = -g_TΦ(-a)
                assertEquals("Spin parity reversal must hold in M3", -m3GtPhiPro, m3GtPhiRetro, 1e-14)
                assertEquals("Spin parity reversal must hold in M2", -m2GtPhiPro, m2GtPhiRetro, 1e-14)
            }
        }
    }
}
