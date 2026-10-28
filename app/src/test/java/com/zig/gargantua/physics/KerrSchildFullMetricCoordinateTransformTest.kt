package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates the full 4x4 metric tensor coordinate transformation between Boyer-Lindquist
 * and Kerr-Schild Cartesian coordinates.
 *
 * SCIENTIFIC OBJECTIVE:
 * Proves that the production Kerr-Schild metric implemented in Phase M3 represents
 * the exact same Kerr spacetime geometry as the independent Boyer-Lindquist formulation.
 *
 * EXACT COORDINATE RELATIONS & DIFFERENTIAL TRANSFORMATION:
 * Coordinates:
 *   x^μ_BL = (t_BL, r, θ, φ_BL)
 *   x^α_KS = (T_KS, X, Y, Z)
 *
 * Differential 1-form relations (Carter 1968, Misner, Thorne & Wheeler §33.2, Visser 2007):
 *   dT_KS = dt_BL + (2 M r / Δ(r)) dr
 *   dφ_KS = dφ_BL + (a / Δ(r)) dr
 *   dr_KS = dr_BL = dr
 *   dθ_KS = dθ_BL = dθ
 * where Δ(r) = r² - 2 M r + a².
 *
 * Spatial Cartesian mapping from spheroidal (r, θ, φ_KS):
 *   X = (r cos φ_KS - a sin φ_KS) sin θ
 *   Y = (r sin φ_KS + a cos φ_KS) sin θ
 *   Z = r cos θ
 *
 * JACOBIAN MATRIX J^α_μ = ∂x^α_KS / ∂x^μ_BL:
 * Rows: (T, X, Y, Z), Columns: (t_BL, r, θ, φ_BL):
 *   J^0_t = 1,  J^0_r = 2 M r / Δ,  J^0_θ = 0,  J^0_φ = 0
 *   J^1_t = 0,  J^1_r = cos φ_KS sin θ + (a / Δ) dX/dφ_KS,  J^1_θ = dX/dθ,  J^1_φ = dX/dφ_KS
 *   J^2_t = 0,  J^2_r = sin φ_KS sin θ + (a / Δ) dY/dφ_KS,  J^2_θ = dY/dθ,  J^2_φ = dY/dφ_KS
 *   J^3_t = 0,  J^3_r = cos θ,  J^3_θ = -r sin θ,  J^3_φ = 0
 *
 * TENSOR TRANSFORMATION RULES:
 * - Covariant metric:
 *     g^{KS}_{αβ} = (J⁻¹)^μ_α (J⁻¹)^ν_β g^{BL}_{μν}
 *   and conversely:
 *     g^{BL}_{μν} = J^α_μ J^β_ν g^{KS}_{αβ}
 * - Contravariant (inverse) metric:
 *     g^{KS, αβ} = J^α_μ J^β_ν g^{BL, μν}
 */
class KerrSchildFullMetricCoordinateTransformTest {

    /**
     * Constructs the Boyer-Lindquist covariant metric tensor independently from first principles.
     */
    private fun computeIndependentBLMetric(M: Double, a: Double, r: Double, theta: Double): MetricTensor4 {
        val sinT = sin(theta)
        val cosT = cos(theta)
        val sin2 = sinT * sinT
        val cos2 = cosT * cosT
        val a2 = a * a
        val sigma = r * r + a2 * cos2
        val delta = r * r - 2.0 * M * r + a2

        val g_tt = -(1.0 - (2.0 * M * r) / sigma)
        val g_rr = sigma / delta
        val g_thth = sigma
        val g_phph = (r * r + a2 + (2.0 * M * a2 * r * sin2) / sigma) * sin2
        val g_tph = -(2.0 * M * a * r * sin2) / sigma

        val g = DoubleArray(16)
        g[0 * 4 + 0] = g_tt
        g[1 * 4 + 1] = g_rr
        g[2 * 4 + 2] = g_thth
        g[3 * 4 + 3] = g_phph
        g[0 * 4 + 3] = g_tph
        g[3 * 4 + 0] = g_tph
        return MetricTensor4(g)
    }

    /**
     * Constructs the Boyer-Lindquist contravariant (inverse) metric tensor independently from first principles.
     */
    private fun computeIndependentBLInverseMetric(M: Double, a: Double, r: Double, theta: Double): MetricTensor4 {
        val sinT = sin(theta)
        val cosT = cos(theta)
        val sin2 = sinT * sinT
        val cos2 = cosT * cosT
        val a2 = a * a
        val sigma = r * r + a2 * cos2
        val delta = r * r - 2.0 * M * r + a2
        val bigA = (r * r + a2) * (r * r + a2) - a2 * delta * sin2

        val g_tt_inv = -bigA / (sigma * delta)
        val g_rr_inv = delta / sigma
        val g_thth_inv = 1.0 / sigma
        val g_phph_inv = (delta - a2 * sin2) / (sigma * delta * sin2)
        val g_tph_inv = -(2.0 * M * a * r) / (sigma * delta)

        val gInv = DoubleArray(16)
        gInv[0 * 4 + 0] = g_tt_inv
        gInv[1 * 4 + 1] = g_rr_inv
        gInv[2 * 4 + 2] = g_thth_inv
        gInv[3 * 4 + 3] = g_phph_inv
        gInv[0 * 4 + 3] = g_tph_inv
        gInv[3 * 4 + 0] = g_tph_inv
        return MetricTensor4(gInv)
    }

    /**
     * Computes the 4x4 Jacobian matrix J^α_μ = ∂x^α_KS / ∂x^μ_BL.
     */
    private fun computeJacobian(
        M: Double,
        a: Double,
        r: Double,
        theta: Double,
        phiKS: Double
    ): Array<DoubleArray> {
        val delta = r * r - 2.0 * M * r + a * a
        require(delta > 0.0) { "Point must reside outside event horizon (Δ > 0)" }

        val sinT = sin(theta)
        val cosT = cos(theta)
        val sinP = sin(phiKS)
        val cosP = cos(phiKS)

        // dX derivatives with respect to spheroidal coordinates
        val dX_dr_fixed = cosP * sinT
        val dX_dth = (r * cosP - a * sinP) * cosT
        val dX_dph = (-r * sinP - a * cosP) * sinT

        // dY derivatives with respect to spheroidal coordinates
        val dY_dr_fixed = sinP * sinT
        val dY_dth = (r * sinP + a * cosP) * cosT
        val dY_dph = (r * cosP - a * sinP) * sinT

        val J = Array(4) { DoubleArray(4) }

        // Row 0: T_KS
        J[0][0] = 1.0
        J[0][1] = (2.0 * M * r) / delta
        J[0][2] = 0.0
        J[0][3] = 0.0

        // Row 1: X
        J[1][0] = 0.0
        J[1][1] = dX_dr_fixed + (a / delta) * dX_dph
        J[1][2] = dX_dth
        J[1][3] = dX_dph

        // Row 2: Y
        J[2][0] = 0.0
        J[2][1] = dY_dr_fixed + (a / delta) * dY_dph
        J[2][2] = dY_dth
        J[2][3] = dY_dph

        // Row 3: Z
        J[3][0] = 0.0
        J[3][1] = cosT
        J[3][2] = -r * sinT
        J[3][3] = 0.0

        return J
    }

    /**
     * Inverts a 4x4 matrix using Gauss-Jordan elimination with partial pivoting.
     */
    private fun invert4x4(A: Array<DoubleArray>): Array<DoubleArray> {
        val n = 4
        val mat = Array(n) { i ->
            DoubleArray(2 * n) { j ->
                if (j < n) A[i][j] else if (j - n == i) 1.0 else 0.0
            }
        }

        for (i in 0 until n) {
            var pivot = i
            for (k in i + 1 until n) {
                if (abs(mat[k][i]) > abs(mat[pivot][i])) {
                    pivot = k
                }
            }
            val temp = mat[i]
            mat[i] = mat[pivot]
            mat[pivot] = temp

            val pv = mat[i][i]
            require(abs(pv) > 1e-15) { "Jacobian matrix is singular at evaluation point" }

            for (j in 0 until 2 * n) {
                mat[i][j] /= pv
            }
            for (k in 0 until n) {
                if (k != i) {
                    val factor = mat[k][i]
                    for (j in 0 until 2 * n) {
                        mat[k][j] -= factor * mat[i][j]
                    }
                }
            }
        }

        return Array(n) { i ->
            DoubleArray(n) { j -> mat[i][n + j] }
        }
    }

    @Test
    fun fullCovariantAndContravariantMetricTransformationEquivalence() {
        val testConfigs = listOf(
            Pair(1.0, 0.0),    // Schwarzschild
            Pair(1.0, 0.5),    // Moderate prograde
            Pair(1.0, -0.5),   // Moderate retrograde
            Pair(1.0, 0.9),    // High spin prograde
            Pair(1.0, -0.9),   // High spin retrograde
            Pair(2.5, 2.0)     // Mass scaled prograde
        )

        val thetaSamples = listOf(0.3, 0.7, 1.1, 1.4, 2.0, 2.5)
        val phiSamples = listOf(0.0, 0.5, 1.2, 2.3, 3.14159, 4.5)

        var globalMaxAbsCov = 0.0
        var globalMaxRelCov = 0.0
        var globalMaxAbsContra = 0.0
        var globalMaxRelContra = 0.0
        var totalEvaluatedCases = 0

        for ((M, a) in testConfigs) {
            val spacetime = KerrSchildSpacetime(M, a)
            val rPlus = spacetime.rPlus

            val radii = listOf(
                rPlus + 0.2 * M,  // Strong field outside horizon
                rPlus + 0.8 * M,  // Strong field intermediate
                3.5 * M,          // Moderate field
                8.0 * M,          // Moderate field outer
                50.0 * M          // Weak field
            )

            for (r in radii) {
                for (th in thetaSamples) {
                    for (ph in phiSamples) {
                        val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r, th, phiKS = ph)
                        val J = computeJacobian(M, a, r, th, phiKS = ph)
                        val J_inv = invert4x4(J)

                        // 1. Independent Boyer-Lindquist metrics
                        val g_bl = computeIndependentBLMetric(M, a, r, th)
                        val g_bl_inv = computeIndependentBLInverseMetric(M, a, r, th)

                        // 2. Production Kerr-Schild metrics from M3
                        val g_ks_prod = spacetime.metric(pos.X, pos.Y, pos.Z)
                        val g_ks_inv_prod = spacetime.inverseMetric(pos.X, pos.Y, pos.Z)

                        // 3. Transform BL covariant metric into Kerr-Schild:
                        // g^{KS}_{αβ} = (J⁻¹)^μ_α (J⁻¹)^ν_β g^{BL}_{μν}
                        val g_ks_transformed = DoubleArray(16)
                        for (alpha in 0 until 4) {
                            val aOffset = alpha * 4
                            for (beta in 0 until 4) {
                                var sum = 0.0
                                for (mu in 0 until 4) {
                                    val jInvMu = J_inv[mu][alpha]
                                    for (nu in 0 until 4) {
                                        sum += jInvMu * J_inv[nu][beta] * g_bl[mu, nu]
                                    }
                                }
                                g_ks_transformed[aOffset + beta] = sum
                            }
                        }
                        val g_ks_transTensor = MetricTensor4(g_ks_transformed)

                        // Verify symmetry of transformed tensor
                        assertTrue("Transformed metric must be symmetric", g_ks_transTensor.isSymmetric(1e-12))

                        // Compare all 16 covariant components
                        for (alpha in 0 until 4) {
                            for (beta in 0 until 4) {
                                val act = g_ks_transTensor[alpha, beta]
                                val exp = g_ks_prod[alpha, beta]
                                val absErr = abs(act - exp)
                                if (absErr > globalMaxAbsCov) globalMaxAbsCov = absErr
                                if (abs(exp) > 1e-3) {
                                    val relErr = absErr / abs(exp)
                                    if (relErr > globalMaxRelCov) globalMaxRelCov = relErr
                                }
                                assertEquals(
                                    "Covariant metric mismatch at ($alpha, $beta) for M=$M, a=$a, r=$r, θ=$th, φ=$ph",
                                    exp,
                                    act,
                                    1e-11
                                )
                            }
                        }

                        // 4. Transform BL contravariant (inverse) metric into Kerr-Schild:
                        // g^{KS, αβ} = J^α_μ J^β_ν g^{BL, μν}
                        val g_ks_inv_transformed = DoubleArray(16)
                        for (alpha in 0 until 4) {
                            val aOffset = alpha * 4
                            for (beta in 0 until 4) {
                                var sum = 0.0
                                for (mu in 0 until 4) {
                                    val jAlphaMu = J[alpha][mu]
                                    for (nu in 0 until 4) {
                                        sum += jAlphaMu * J[beta][nu] * g_bl_inv[mu, nu]
                                    }
                                }
                                g_ks_inv_transformed[aOffset + beta] = sum
                            }
                        }
                        val g_ks_inv_transTensor = MetricTensor4(g_ks_inv_transformed)

                        // Verify symmetry of transformed inverse tensor
                        assertTrue("Transformed inverse metric must be symmetric", g_ks_inv_transTensor.isSymmetric(1e-12))

                        // Compare all 16 contravariant components
                        for (alpha in 0 until 4) {
                            for (beta in 0 until 4) {
                                val act = g_ks_inv_transTensor[alpha, beta]
                                val exp = g_ks_inv_prod[alpha, beta]
                                val absErr = abs(act - exp)
                                if (absErr > globalMaxAbsContra) globalMaxAbsContra = absErr
                                if (abs(exp) > 1e-3) {
                                    val relErr = absErr / abs(exp)
                                    if (relErr > globalMaxRelContra) globalMaxRelContra = relErr
                                }
                                assertEquals(
                                    "Contravariant metric mismatch at ($alpha, $beta) for M=$M, a=$a, r=$r, θ=$th, φ=$ph",
                                    exp,
                                    act,
                                    1e-11
                                )
                            }
                        }

                        // 5. Backward transformation: g^{BL}_{μν} = J^α_μ J^β_ν g^{KS}_{αβ}
                        for (mu in 0 until 4) {
                            for (nu in 0 until 4) {
                                var sum = 0.0
                                for (alpha in 0 until 4) {
                                    val jAlphaMu = J[alpha][mu]
                                    for (beta in 0 until 4) {
                                        sum += jAlphaMu * J[beta][nu] * g_ks_prod[alpha, beta]
                                    }
                                }
                                val expectedBL = g_bl[mu, nu]
                                assertEquals(
                                    "Backward transformation to BL mismatch at ($mu, $nu) for M=$M, a=$a, r=$r",
                                    expectedBL,
                                    sum,
                                    1e-10 * (1.0 + abs(expectedBL))
                                )
                            }
                        }

                        totalEvaluatedCases++
                    }
                }
            }
        }

        assertTrue("Must evaluate at least 500 configurations", totalEvaluatedCases >= 500)

        // Strict verification of residuals across all tested regimes
        assertTrue(
            "Global max absolute error for covariant metric must be < 1e-11 (observed: $globalMaxAbsCov)",
            globalMaxAbsCov < 1e-11
        )
        assertTrue(
            "Global max relative error for significant covariant metric components must be < 1e-11 (observed: $globalMaxRelCov)",
            globalMaxRelCov < 1e-11
        )
        assertTrue(
            "Global max absolute error for contravariant metric must be < 1e-11 (observed: $globalMaxAbsContra)",
            globalMaxAbsContra < 1e-11
        )
        assertTrue(
            "Global max relative error for significant contravariant metric components must be < 1e-11 (observed: $globalMaxRelContra)",
            globalMaxRelContra < 1e-11
        )
    }
}
