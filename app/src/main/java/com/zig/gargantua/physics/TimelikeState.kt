package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Eight-dimensional phase-space state of a massive test particle with unit rest mass (m = 1)
 * in horizon-penetrating Kerr-Schild Cartesian coordinates.
 *
 * COORDINATE SYSTEM:
 *   Geometrized units G = c = 1.
 *   Spacetime signature (-, +, +, +).
 *   Kerr-Schild coordinates x^μ = (T, X, Y, Z).
 *   Covariant 4-momentum p_μ = (p_T, p_X, p_Y, p_Z).
 *
 * PHYSICAL CONVENTIONS:
 *   Proper time: τ
 *   Unit rest mass: m = 1
 *   4-velocity: u^μ = dx^μ / dτ
 *   Covariant 4-momentum: p_μ = g_μν u^ν
 *   Contravariant 4-momentum: p^μ = g^μν p_ν = u^μ
 *   Timelike mass-shell invariant: g^μν p_μ p_ν = -m² = -1.0
 *   Hamiltonian: H = 1/2 g^μν p_μ p_ν = -0.5
 *   Stationary energy: E = -p_T = -p_0 (conserved along geodesics since ∂_T g^μν = 0)
 *   Axial angular momentum: L_z = X p_Y - Y p_X (conserved along geodesics since ∂_φ g^μν = 0)
 */
data class TimelikeState(
    val tau: Double,
    val T: Double,
    val X: Double,
    val Y: Double,
    val Z: Double,
    val pT: Double,
    val pX: Double,
    val pY: Double,
    val pZ: Double
) {
    /** 4-position as an array [T, X, Y, Z]. */
    val position4D: DoubleArray get() = doubleArrayOf(T, X, Y, Z)

    /** Covariant 4-momentum as an array [p_T, p_X, p_Y, p_Z]. */
    val momentum4D: DoubleArray get() = doubleArrayOf(pT, pX, pY, pZ)

    /** Spatial position [X, Y, Z]. */
    val spatialPosition: DoubleArray get() = doubleArrayOf(X, Y, Z)

    /** Spatial momentum [p_X, p_Y, p_Z]. */
    val spatialMomentum: DoubleArray get() = doubleArrayOf(pX, pY, pZ)

    /** Specific energy E = -p_T (Killing symmetry ∂_T). */
    val energy: Double get() = -pT

    /** Axial angular momentum L_z = X p_Y - Y p_X. */
    val angularMomentumZ: Double get() = X * pY - Y * pX

    /** Spatial coordinate radius R = √(X² + Y² + Z²). */
    val sphericalRadius: Double get() = sqrt(X * X + Y * Y + Z * Z)

    /** Checks whether any state component is NaN or Infinite. */
    val isValid: Boolean get() = !(tau.isNaN() || tau.isInfinite() ||
            T.isNaN() || T.isInfinite() ||
            X.isNaN() || X.isInfinite() ||
            Y.isNaN() || Y.isInfinite() ||
            Z.isNaN() || Z.isInfinite() ||
            pT.isNaN() || pT.isInfinite() ||
            pX.isNaN() || pX.isInfinite() ||
            pY.isNaN() || pY.isInfinite() ||
            pZ.isNaN() || pZ.isInfinite())

    /**
     * Evaluates the contravariant 4-velocity u^μ = g^μν p_ν using the provided spacetime metric.
     */
    fun fourVelocity(spacetime: KerrSchildSpacetime): DoubleArray {
        val gInv = spacetime.inverseMetric(X, Y, Z)
        val p = momentum4D
        val u = DoubleArray(4)
        for (mu in 0 until 4) {
            var sum = 0.0
            for (nu in 0 until 4) {
                sum += gInv[mu, nu] * p[nu]
            }
            u[mu] = sum
        }
        return u
    }

    /**
     * Evaluates the local coordinate 3-velocity v^i = (dx^i / dτ) / (dT / dτ) = u^i / u^0.
     */
    fun coordinateVelocity(spacetime: KerrSchildSpacetime): DoubleArray {
        val u = fourVelocity(spacetime)
        require(abs(u[0]) > 1e-15) { "Singular coordinate time rate u^0 = 0" }
        return doubleArrayOf(u[1] / u[0], u[2] / u[0], u[3] / u[0])
    }
}
