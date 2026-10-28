package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrParameters
import com.zig.gargantua.physics.KerrSchildCoordinates
import com.zig.gargantua.physics.KerrSchildSpacetime
import kotlin.math.*

/**
 * Complete 8-dimensional phase-space state of a photon in Kerr-Schild Cartesian coordinates.
 *
 * Coordinates: x^μ = (T, X, Y, Z)
 * Canonical Momenta: p_μ = (p_T, p_X, p_Y, p_Z) = (-E, p_X, p_Y, p_Z)
 * Affine Parameter: λ
 *
 * For null geodesics, the Hamiltonian is:
 *   H = 1/2 g^μν p_μ p_ν = 0
 */
data class PhotonState4D(
    val t: Double = 0.0,
    val x: Double,
    val y: Double,
    val z: Double,
    val p_t: Double = -1.0, // Conserved energy at infinity: p_0 = -E = -1.0
    val p_x: Double,
    val p_y: Double,
    val p_z: Double,
    val affineLambda: Double = 0.0
) {
    /** Conserved energy E = -p_0. */
    val energy: Double get() = -p_t

    /** Kerr-Schild radial coordinate r >= 0. */
    fun computeR(a: Double): Double = KerrSchildCoordinates.computeR(a, x, y, z)

    /** Euclidean distance from origin R = √(X² + Y² + Z²). */
    val euclideanRadius: Double get() = sqrt(x * x + y * y + z * z)

    /**
     * Evaluates the null Hamiltonian H = 1/2 g^μν p_μ p_ν.
     * For physical photons, H must be 0 within numerical integration tolerance.
     */
    fun hamiltonian(spacetime: KerrSchildSpacetime): Double {
        val gInv = spacetime.inverseMetric(x, y, z)
        val p = doubleArrayOf(p_t, p_x, p_y, p_z)
        return 0.5 * gInv.contract(p, p)
    }

    /**
     * Conserved axial angular momentum L_z in Kerr-Schild Cartesian coordinates:
     *   L_z = p_μ ξ^μ_{(φ)} = X p_Y - Y p_X
     * Corresponding to the rotational Killing vector ξ_{(φ)} = -Y ∂_X + X ∂_Y.
     */
    val conservedLz: Double get() = x * p_y - y * p_x

    /**
     * Evaluates the Carter constant Q along the geodesic by projecting into
     * Boyer-Lindquist polar momentum:
     *   p_θ = dX/dθ p_X + dY/dθ p_Y + dZ/dθ p_Z
     *   Q = p_θ² + cos²θ ( L_z² / sin²θ - a² E² )
     */
    fun conservedCarterQ(params: KerrParameters): Double {
        val a = params.a
        val r = computeR(a)
        if (r <= 0.0) return 0.0

        val cosT = (z / r).coerceIn(-1.0, 1.0)
        val theta = acos(cosT)
        val sinT = max(1e-12, sin(theta))

        val phiKS = KerrSchildCoordinates.computePhiKS(a, r, x, y)
        val sinP = sin(phiKS)
        val cosP = cos(phiKS)

        // dX/dθ, dY/dθ, dZ/dθ
        val dX_dth = (r * cosP - a * sinP) * cosT
        val dY_dth = (r * sinP + a * cosP) * cosT
        val dZ_dth = -r * sinT

        val pTheta = dX_dth * p_x + dY_dth * p_y + dZ_dth * p_z
        val lz = conservedLz
        val e = energy

        val angularTerm = (lz * lz) / (sinT * sinT) - a * a * e * e
        return pTheta * pTheta + cosT * cosT * angularTerm
    }

    /** Returns 4-velocity dx^μ/dλ = g^μν p_ν in Kerr-Schild coordinates. */
    fun velocity(spacetime: KerrSchildSpacetime): DoubleArray {
        val gInv = spacetime.inverseMetric(x, y, z)
        val p = doubleArrayOf(p_t, p_x, p_y, p_z)
        val v = DoubleArray(4)
        for (mu in 0 until 4) {
            var sum = 0.0
            for (nu in 0 until 4) {
                sum += gInv[mu, nu] * p[nu]
            }
            v[mu] = sum
        }
        return v
    }
}
