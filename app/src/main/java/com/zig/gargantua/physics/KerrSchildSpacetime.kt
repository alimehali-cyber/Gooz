package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Production Kerr spacetime mathematics in horizon-penetrating Kerr-Schild Cartesian coordinates.
 *
 * ARCHITECTURAL ROLE:
 * This is the production spacetime engine for Gargantua, built independently of the Phase M2
 * Boyer-Lindquist validation layer. M2 serves as an external oracle to cross-validate M3.
 *
 * Primary Reference:
 *   Kyleyhw "black_hole" repository (Kerr-Schild metric formulation).
 * Supporting References:
 *   SpECTRE: gr::Solutions::KerrSchild (analytic Kerr-Schild metric and derivatives).
 *   Misner, Thorne, Wheeler (1973) Gravitation §33.
 *
 * MATHEMATICAL SPECIFICATION:
 * - Coordinates: (T, X, Y, Z) in geometrized units G = c = 1.
 * - Signature: (- + + +), Minkowski background η_μν = diag(-1, 1, 1, 1).
 * - Metric:
 *     g_μν = η_μν + 2 H l_μ l_ν
 * - Inverse Metric:
 *     g^μν = η^μν - 2 H l^μ l^ν
 *   (Analytic inverse, exact to machine precision because l^μ is null with respect to η and g).
 * - Kerr-Schild Scalar H:
 *     H = (M r³) / (r⁴ + a² Z²)
 * - Kerr-Schild Null Vector:
 *     l_μ = ( 1, (r X + a Y)/(r² + a²), (r Y - a X)/(r² + a²), Z / r )
 *     l^μ = η^μν l_ν = ( -1, (r X + a Y)/(r² + a²), (r Y - a X)/(r² + a²), Z / r )
 * - Horizons:
 *     r_+ = M + √(M² - a²)
 *     r_- = M - √(M² - a²)
 */
class KerrSchildSpacetime(
    val params: KerrParameters
) {
    /** Secondary constructor directly taking mass M and spin a. */
    constructor(M: Double, a: Double) : this(KerrParameters(M, a))

    val M: Double get() = params.M
    val a: Double get() = params.a
    val aStar: Double get() = params.aStar
    val rPlus: Double get() = params.rPlus
    val rMinus: Double get() = params.rMinus

    /**
     * Computes the Kerr-Schild radial coordinate r >= 0 from Cartesian coordinates (X, Y, Z).
     */
    fun computeR(X: Double, Y: Double, Z: Double): Double {
        return KerrSchildCoordinates.computeR(a, X, Y, Z)
    }

    /**
     * Computes the Kerr-Schild scalar H = (M r³) / (r⁴ + a² Z²).
     */
    fun computeH(r: Double, Z: Double): Double {
        val r2 = r * r
        val r4 = r2 * r2
        val a2 = a * a
        val z2 = Z * Z
        val denom = r4 + a2 * z2
        if (denom <= 0.0) {
            throw IllegalArgumentException("Singularity at r=0, Z=0 (Kerr ring singularity)")
        }
        return (M * r2 * r) / denom
    }

    /**
     * Computes the Kerr-Schild null 1-form l_μ at the given Cartesian coordinates.
     */
    fun computeNullVector(X: Double, Y: Double, Z: Double): NullVector {
        val r = computeR(X, Y, Z)
        val denomXY = r * r + a * a
        val lx = (r * X + a * Y) / denomXY
        val ly = (r * Y - a * X) / denomXY
        val lz = if (r > 0.0) Z / r else 0.0
        return NullVector(l_t = 1.0, l_x = lx, l_y = ly, l_z = lz)
    }

    /**
     * Evaluates the covariant Kerr-Schild metric tensor g_μν = η_μν + 2 H l_μ l_ν.
     */
    fun metric(X: Double, Y: Double, Z: Double): MetricTensor4 {
        val r = computeR(X, Y, Z)
        val H = computeH(r, Z)
        val twoH = 2.0 * H
        val l = computeNullVector(X, Y, Z)
        val lCov = l.toCovariantArray()

        val g = DoubleArray(16)
        val eta = MetricTensor4.MINKOWSKI_LOWER

        for (mu in 0 until 4) {
            val muOffset = mu * 4
            for (nu in mu until 4) {
                val value = eta[mu, nu] + twoH * lCov[mu] * lCov[nu]
                g[muOffset + nu] = value
                g[nu * 4 + mu] = value // Symmetric tensor
            }
        }
        return MetricTensor4(g)
    }

    /**
     * Evaluates the contravariant (inverse) Kerr-Schild metric tensor:
     *   g^μν = η^μν - 2 H l^μ l^ν
     *
     * Note: This is an exact analytic inverse; no numerical matrix inversion is required.
     */
    fun inverseMetric(X: Double, Y: Double, Z: Double): MetricTensor4 {
        val r = computeR(X, Y, Z)
        val H = computeH(r, Z)
        val twoH = 2.0 * H
        val l = computeNullVector(X, Y, Z)
        val lContra = l.toContravariantArray()

        val gInv = DoubleArray(16)
        val etaUpper = MetricTensor4.MINKOWSKI_UPPER

        for (mu in 0 until 4) {
            val muOffset = mu * 4
            for (nu in mu until 4) {
                val value = etaUpper[mu, nu] - twoH * lContra[mu] * lContra[nu]
                gInv[muOffset + nu] = value
                gInv[nu * 4 + mu] = value // Symmetric tensor
            }
        }
        return MetricTensor4(gInv)
    }

    /**
     * Evaluates the exact analytical spatial gradients of g^μν:
     *   (∂_X g^μν, ∂_Y g^μν, ∂_Z g^μν)
     */
    fun derivativesOfInverseMetric(X: Double, Y: Double, Z: Double): KerrSchildDerivatives.SpatialMetricDerivatives {
        return KerrSchildDerivatives.compute(params, X, Y, Z)
    }

    /**
     * Evaluates the metric inversion residual max_{μ, ν} | g^μα g_αν - δ^μ_ν |.
     */
    fun checkMetricInversionResidual(X: Double, Y: Double, Z: Double): Double {
        val gLower = metric(X, Y, Z)
        val gUpper = inverseMetric(X, Y, Z)
        val product = gUpper * gLower
        return product.maxResidualFromIdentity()
    }

    /**
     * Evaluates the null condition of l_μ with respect to the background Minkowski metric η^μν:
     *   | η^μν l_μ l_ν |
     */
    fun checkNullVectorMinkowskiResidual(X: Double, Y: Double, Z: Double): Double {
        val l = computeNullVector(X, Y, Z)
        return abs(l.minkowskiNorm())
    }

    /**
     * Evaluates the null condition of l_μ with respect to the full physical inverse metric g^μν:
     *   | g^μν l_μ l_ν |
     */
    fun checkNullVectorFullMetricResidual(X: Double, Y: Double, Z: Double): Double {
        val gUpper = inverseMetric(X, Y, Z)
        val lCov = computeNullVector(X, Y, Z).toCovariantArray()
        val contracted = gUpper.contract(lCov, lCov)
        return abs(contracted)
    }
}
