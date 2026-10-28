package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Exact analytical spatial derivatives of the contravariant Kerr-Schild metric g^μν.
 *
 * M4 REQUIREMENT:
 * Hamilton's equations for photon raymarching in Phase M4 require:
 *   dp_i / dλ = -1/2 (∂_i g^αβ) p_α p_β
 * for spatial coordinates i in {X, Y, Z} (indices 1, 2, 3).
 *
 * MATHEMATICAL DERIVATION:
 * The contravariant Kerr-Schild metric is:
 *   g^μν = η^μν - 2 H l^μ l^ν
 * Since the background Minkowski metric η^μν is constant, differentiating with respect to x^i gives:
 *   ∂_i g^μν = -2 (∂_i H) l^μ l^ν - 2 H (∂_i l^μ) l^ν - 2 H l^μ (∂_i l^ν)
 *
 * 1. Radial derivatives ∂_i r:
 *    From (X² + Y²)/(r² + a²) + Z²/r² = 1:
 *      ∂_X r = (r³ X) / (r⁴ + a² Z²)
 *      ∂_Y r = (r³ Y) / (r⁴ + a² Z²)
 *      ∂_Z r = (Z r (r² + a²)) / (r⁴ + a² Z²)
 *
 * 2. Scalar H derivatives ∂_i H:
 *    With H = (M r³) / (r⁴ + a² Z²):
 *      ∂H/∂r = M r² (3 a² Z² - r⁴) / (r⁴ + a² Z²)²
 *      ∂_X H = (∂H/∂r) (∂_X r)
 *      ∂_Y H = (∂H/∂r) (∂_Y r)
 *      ∂_Z H = (∂H/∂r) (∂_Z r) - (2 M a² r³ Z) / (r⁴ + a² Z²)²
 *
 * 3. Null vector derivatives ∂_i l^μ:
 *    With l^0 = -1 (constant, so ∂_i l^0 = 0), and spatial components:
 *      l^1 = (r X + a Y) / (r² + a²)
 *      l^2 = (r Y - a X) / (r² + a²)
 *      l^3 = Z / r
 *    Differentiating via quotient and chain rules produces closed-form algebraic expressions
 *    with zero finite-difference truncation error.
 */
object KerrSchildDerivatives {

    /**
     * Container for the three 4x4 spatial gradient tensors:
     *   d_dX = ∂_X g^μν
     *   d_dY = ∂_Y g^μν
     *   d_dZ = ∂_Z g^μν
     */
    data class SpatialMetricDerivatives(
        val d_dX: MetricTensor4,
        val d_dY: MetricTensor4,
        val d_dZ: MetricTensor4
    ) {
        /** Accesses derivative tensor by spatial index (1=X, 2=Y, 3=Z). */
        operator fun get(spatialIndex: Int): MetricTensor4 = when (spatialIndex) {
            1 -> d_dX
            2 -> d_dY
            3 -> d_dZ
            else -> throw IllegalArgumentException("Spatial index must be 1 (X), 2 (Y), or 3 (Z), got $spatialIndex")
        }
    }

    /**
     * Computes the exact analytical spatial gradients of g^μν at the given Cartesian coordinates.
     */
    fun compute(params: KerrParameters, X: Double, Y: Double, Z: Double): SpatialMetricDerivatives {
        val M = params.M
        val a = params.a
        val r = KerrSchildCoordinates.computeR(a, X, Y, Z)

        val r2 = r * r
        val r3 = r2 * r
        val r4 = r2 * r2
        val a2 = a * a
        val z2 = Z * Z

        val denomSigma = r4 + a2 * z2
        require(denomSigma > 1e-28) {
            "Cannot evaluate metric derivatives at or near Kerr ring singularity (r=0, Z=0)"
        }

        // 1. Exact radial derivatives ∂_i r
        val dr_dX = (r3 * X) / denomSigma
        val dr_dY = (r3 * Y) / denomSigma
        val dr_dZ = (Z * r * (r2 + a2)) / denomSigma

        // 2. Kerr-Schild scalar H and derivatives ∂_i H
        val H = (M * r3) / denomSigma
        val denomSigma2 = denomSigma * denomSigma
        val dH_dr = M * r2 * (3.0 * a2 * z2 - r4) / denomSigma2

        val dH_dX = dH_dr * dr_dX
        val dH_dY = dH_dr * dr_dY
        val dH_dZ = dH_dr * dr_dZ - (2.0 * M * a2 * r3 * Z) / denomSigma2

        // 3. Null vector l^μ = (-1, l_x, l_y, l_z)
        val denomV = r2 + a2
        val denomV2 = denomV * denomV

        val lx = (r * X + a * Y) / denomV
        val ly = (r * Y - a * X) / denomV
        val lz = if (r > 0.0) Z / r else 0.0
        val lUpper = doubleArrayOf(-1.0, lx, ly, lz)

        // Helper to differentiate l^μ with respect to coordinate x^i
        fun differentiateL(
            dr_di: Double,
            isX: Boolean,
            isY: Boolean,
            isZ: Boolean
        ): DoubleArray {
            val dl0 = 0.0
            val dv = 2.0 * r * dr_di

            // d/di (r X + a Y)
            val duX = dr_di * X + (if (isX) r else 0.0) + (if (isY) a else 0.0)
            val dlX = (duX * denomV - (r * X + a * Y) * dv) / denomV2

            // d/di (r Y - a X)
            val duY = dr_di * Y + (if (isY) r else 0.0) - (if (isX) a else 0.0)
            val dlY = (duY * denomV - (r * Y - a * X) * dv) / denomV2

            // d/di (Z / r)
            val dlZ = if (r > 0.0) {
                ((if (isZ) 1.0 else 0.0) * r - Z * dr_di) / r2
            } else 0.0

            return doubleArrayOf(dl0, dlX, dlY, dlZ)
        }

        val dl_dX = differentiateL(dr_dX, isX = true, isY = false, isZ = false)
        val dl_dY = differentiateL(dr_dY, isX = false, isY = true, isZ = false)
        val dl_dZ = differentiateL(dr_dZ, isX = false, isY = false, isZ = true)

        // 4. Assemble ∂_i g^μν = -2 (∂_i H) l^μ l^ν - 2 H (∂_i l^μ) l^ν - 2 H l^μ (∂_i l^ν)
        fun assembleDg(dH_di: Double, dl_di: DoubleArray): MetricTensor4 {
            val dg = DoubleArray(16)
            for (mu in 0 until 4) {
                val muOffset = mu * 4
                for (nu in mu until 4) {
                    val valMuNu = -2.0 * dH_di * lUpper[mu] * lUpper[nu] -
                        2.0 * H * dl_di[mu] * lUpper[nu] -
                        2.0 * H * lUpper[mu] * dl_di[nu]

                    dg[muOffset + nu] = valMuNu
                    dg[nu * 4 + mu] = valMuNu // Enforce symmetry
                }
            }
            return MetricTensor4(dg)
        }

        return SpatialMetricDerivatives(
            d_dX = assembleDg(dH_dX, dl_dX),
            d_dY = assembleDg(dH_dY, dl_dY),
            d_dZ = assembleDg(dH_dZ, dl_dZ)
        )
    }
}
