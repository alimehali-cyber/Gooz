package com.zig.gargantua.renderer

import com.zig.gargantua.geodesic.GpuEquivalentIntegrator
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Phase M5 Performance Repair:
 * 1. Resolution scaling bounds and pixel count reduction factor.
 * 2. Mathematical equivalence of factored Kerr-Schild derivatives vs explicit 4x4 matrix assembly.
 * 3. Mathematical equivalence of factored equatorial disk metric norm vs explicit 4x4 matrix contraction.
 * 4. Preservation of 1.0f full-resolution scientific ceiling.
 */
class GargantuaPerformanceRepairTest {

    @Test
    fun scaledResolutionCalculatesAccuratelyAndReducesWorkload() {
        val width = 1080
        val height = 2400
        val scale = 0.5f

        val renderW = max(1, (width * scale).roundToInt())
        val renderH = max(1, (height * scale).roundToInt())

        assertEquals(540, renderW)
        assertEquals(1200, renderH)

        val nativePixels = width.toLong() * height.toLong()
        val scaledPixels = renderW.toLong() * renderH.toLong()

        // 540x1200 is exactly 1/4 (25%) of 1080x2400, giving a 4x reduction in raymarching workload
        val workloadRatio = scaledPixels.toDouble() / nativePixels.toDouble()
        assertEquals(0.25, workloadRatio, 1e-4)
    }

    @Test
    fun scientificCeilingPreservesNativeResolution() {
        val width = 1440
        val height = 3200
        val scale = 1.0f

        val renderW = max(1, (width * scale).roundToInt())
        val renderH = max(1, (height * scale).roundToInt())

        assertEquals(width, renderW)
        assertEquals(height, renderH)
    }

    @Test
    fun factoredSpatialDerivativesMatchMatrixFormWithinMachinePrecision() {
        val M = 1.0f
        val a = 0.8f

        val testPositions = listOf(
            floatArrayOf(12.0f, -8.0f, 3.0f),
            floatArrayOf(4.5f, 2.0f, -1.2f),
            floatArrayOf(-7.0f, -11.0f, 0.4f),
            floatArrayOf(3.2f, -1.5f, 0.1f) // Near photon sphere
        )

        val testMomenta = listOf(
            floatArrayOf(0.8f, -0.4f, 0.1f),
            floatArrayOf(-0.6f, 0.5f, -0.2f),
            floatArrayOf(0.1f, -0.9f, 0.3f)
        )

        for (pos in testPositions) {
            val X = pos[0]
            val Y = pos[1]
            val Z = pos[2]
            val r = GpuEquivalentIntegrator.compute_r_KS(a, X, Y, Z)

            // Method 1: Explicit 4x4 matrix computation from GpuEquivalentIntegrator
            val gInv = GpuEquivalentIntegrator.compute_g_inv(M, a, X, Y, Z, r)
            val (dg_dX, dg_dY, dg_dZ) = GpuEquivalentIntegrator.compute_dg_inv(M, a, X, Y, Z, r)

            for (pSpatial in testMomenta) {
                val p = floatArrayOf(1.0f, pSpatial[0], pSpatial[1], pSpatial[2]) // backward-trace covector, p_0 = +1

                // Method 1: Matrix-vector evaluation
                var dX_mat = 0.0f
                var dY_mat = 0.0f
                var dZ_mat = 0.0f
                for (nu in 0 until 4) {
                    dX_mat += gInv[1][nu] * p[nu]
                    dY_mat += gInv[2][nu] * p[nu]
                    dZ_mat += gInv[3][nu] * p[nu]
                }

                var sumX = 0.0f
                var sumY = 0.0f
                var sumZ = 0.0f
                for (i in 0 until 4) {
                    for (j in 0 until 4) {
                        val pTerm = p[i] * p[j]
                        sumX += dg_dX[i][j] * pTerm
                        sumY += dg_dY[i][j] * pTerm
                        sumZ += dg_dZ[i][j] * pTerm
                    }
                }
                val dPx_mat = -0.5f * sumX
                val dPy_mat = -0.5f * sumY
                val dPz_mat = -0.5f * sumZ

                // Method 2: Factored analytical shader evaluation
                val a2 = a * a
                val z2 = Z * Z
                val r2 = r * r
                val r3 = r2 * r
                val r4 = r2 * r2
                val denom_sigma = max(1.0e-20f, r4 + a2 * z2)

                val dr_dX = (r3 * X) / denom_sigma
                val dr_dY = (r3 * Y) / denom_sigma
                val dr_dZ = (Z * r * (r2 + a2)) / denom_sigma

                val H = (M * r3) / denom_sigma
                val denom_sigma2 = denom_sigma * denom_sigma
                val dH_dr = M * r2 * (3.0f * a2 * z2 - r4) / denom_sigma2

                val dH_dX = dH_dr * dr_dX
                val dH_dY = dH_dr * dr_dY
                val dH_dZ = dH_dr * dr_dZ - (2.0f * M * a2 * r3 * Z) / denom_sigma2

                val denom_v = r2 + a2
                val denom_v2 = denom_v * denom_v
                val lx = if (denom_v > 1.0e-12f) (r * X + a * Y) / denom_v else 0.0f
                val ly = if (denom_v > 1.0e-12f) (r * Y - a * X) / denom_v else 0.0f
                val lz = if (r > 1.0e-7f) Z / r else 0.0f

                val Lp = -1.0f + lx * pSpatial[0] + ly * pSpatial[1] + lz * pSpatial[2] // l^0 p_0 = -p_0 = -1
                val twoH = 2.0f * H

                val dX_fact = pSpatial[0] - (twoH * Lp) * lx
                val dY_fact = pSpatial[1] - (twoH * Lp) * ly
                val dZ_fact = pSpatial[2] - (twoH * Lp) * lz

                val dv_X = 2.0f * r * dr_dX
                val duX_X = dr_dX * X + r
                val dlX_X = (duX_X * denom_v - (r * X + a * Y) * dv_X) / denom_v2
                val duY_X = dr_dX * Y - a
                val dlY_X = (duY_X * denom_v - (r * Y - a * X) * dv_X) / denom_v2
                val dlZ_X = if (r > 1.0e-7f) (-Z * dr_dX) / r2 else 0.0f
                val dl_p_X = dlX_X * pSpatial[0] + dlY_X * pSpatial[1] + dlZ_X * pSpatial[2]

                val dv_Y = 2.0f * r * dr_dY
                val duX_Y = dr_dY * X + a
                val dlX_Y = (duX_Y * denom_v - (r * X + a * Y) * dv_Y) / denom_v2
                val duY_Y = dr_dY * Y + r
                val dlY_Y = (duY_Y * denom_v - (r * Y - a * X) * dv_Y) / denom_v2
                val dlZ_Y = if (r > 1.0e-7f) (-Z * dr_dY) / r2 else 0.0f
                val dl_p_Y = dlX_Y * pSpatial[0] + dlY_Y * pSpatial[1] + dlZ_Y * pSpatial[2]

                val dv_Z = 2.0f * r * dr_dZ
                val duX_Z = (dr_dZ * X)
                val dlX_Z = (duX_Z * denom_v - (r * X + a * Y) * dv_Z) / denom_v2
                val duY_Z = (dr_dZ * Y)
                val dlY_Z = (duY_Z * denom_v - (r * Y - a * X) * dv_Z) / denom_v2
                val dlZ_Z = if (r > 1.0e-7f) (r - Z * dr_dZ) / r2 else 0.0f
                val dl_p_Z = dlX_Z * pSpatial[0] + dlY_Z * pSpatial[1] + dlZ_Z * pSpatial[2]

                val dPx_fact = Lp * (dH_dX * Lp + twoH * dl_p_X)
                val dPy_fact = Lp * (dH_dY * Lp + twoH * dl_p_Y)
                val dPz_fact = Lp * (dH_dZ * Lp + twoH * dl_p_Z)

                // Verify exact numerical match between matrix form and factored form
                assertEquals("dX must match exactly", dX_mat, dX_fact, 1e-5f)
                assertEquals("dY must match exactly", dY_mat, dY_fact, 1e-5f)
                assertEquals("dZ must match exactly", dZ_mat, dZ_fact, 1e-5f)

                assertEquals("dPx must match exactly", dPx_mat, dPx_fact, 1e-5f)
                assertEquals("dPy must match exactly", dPy_mat, dPy_fact, 1e-5f)
                assertEquals("dPz must match exactly", dPz_mat, dPz_fact, 1e-5f)
            }
        }
    }

    @Test
    fun factoredDiskNormMatchesMatrixContraction() {
        val M = 1.0f
        val a = 0.85f

        val radii = listOf(3.5f, 6.0f, 10.0f, 18.0f)
        val angles = listOf(0.0f, 0.785f, 1.57f, 3.14f, 4.71f)

        for (r in radii) {
            for (phi in angles) {
                val X = r * cos(phi)
                val Y = r * sin(phi)
                val Z = 0.0f

                val omega = sqrt(M) / (r.pow(1.5f) + a * sqrt(M))
                val vEmit = floatArrayOf(1.0f, -omega * Y, omega * X, 0.0f)

                // Method 1: Matrix contraction
                val gHit = GpuEquivalentIntegrator.compute_g_lower(M, a, X, Y, Z, r)
                var contractMat = 0.0f
                for (i in 0 until 4) {
                    for (j in 0 until 4) {
                        contractMat += gHit[i][j] * vEmit[i] * vEmit[j]
                    }
                }

                // Method 2: Factored contraction
                val a2 = a * a
                val denom_v = r * r + a2
                val lx = (r * X + a * Y) / denom_v
                val ly = (r * Y - a * X) / denom_v
                val H = M / r

                val l_dot_u = 1.0f + omega * (ly * X - lx * Y)
                val eta_u_u = -1.0f + (omega * omega) * (X * X + Y * Y)
                val contractFact = eta_u_u + 2.0f * H * (l_dot_u * l_dot_u)

                assertEquals("Equatorial disk 4-velocity norm must match exactly", contractMat, contractFact, 1e-5f)
            }
        }
    }
}
