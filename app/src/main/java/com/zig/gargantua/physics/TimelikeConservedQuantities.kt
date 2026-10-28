package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Conserved quantities, invariants, and mass-shell diagnostics for timelike geodesics
 * in Kerr spacetime for a test particle of unit rest mass (m = 1).
 *
 * CONSERVED QUANTITIES:
 * 1. Specific Energy E = -p_T (Killing vector ξ^μ = ∂_T).
 * 2. Axial Angular Momentum L_z = X p_Y - Y p_X (Killing vector ψ^μ = ∂_φ).
 * 3. Carter Constant Q (associated with the 2nd-rank Killing-Yano / conformal Killing tensor K_μν):
 *      Q = p_θ² + cos²θ [ a² (1 - E²) + L_z² / sin²θ ]
 *    and the related constant of motion K = Q + (L_z - a E)².
 * 4. Hamiltonian H = 1/2 g^μν p_μ p_ν = -0.5.
 * 5. Mass-Shell Residual: |g^μν p_μ p_ν + 1.0|.
 */
object TimelikeConservedQuantities {

    data class Quantities(
        val energy: Double,
        val angularMomentumZ: Double,
        val carterConstantQ: Double,
        val carterConstantK: Double,
        val hamiltonian: Double,
        val massShellResidual: Double
    )

    /**
     * Evaluates all conserved quantities and Hamiltonian diagnostics for the given state.
     *
     * @param spacetime Production Kerr-Schild metric.
     * @param state 8D timelike state.
     * @return [Quantities] containing E, L_z, Q, K, H, and mass-shell error.
     */
    fun evaluate(spacetime: KerrSchildSpacetime, state: TimelikeState): Quantities {
        val X = state.X
        val Y = state.Y
        val Z = state.Z
        val p = state.momentum4D

        // 1. Specific Energy E = -p_0 = -p_T
        val E = -state.pT

        // 2. Axial Angular Momentum L_z = X p_Y - Y p_X
        val Lz = state.angularMomentumZ

        // 3. Coordinate transformation to Boyer-Lindquist polar angle θ
        val r = spacetime.computeR(X, Y, Z)
        val rSafe = max(1e-12, r)
        val cosT = (Z / rSafe).coerceIn(-1.0, 1.0)
        val sinT2 = max(1e-12, 1.0 - cosT * cosT)
        val sinT = sqrt(sinT2)

        // Covariant momentum p_θ = ∂X/∂θ p_X + ∂Y/∂θ p_Y + ∂Z/∂θ p_Z
        // In Kerr-Schild Cartesian coordinates:
        // ∂X/∂θ = X cotθ, ∂Y/∂θ = Y cotθ, ∂Z/∂θ = -r sinθ
        val cotT = cosT / sinT
        val pTheta = cotT * (X * state.pX + Y * state.pY) - rSafe * sinT * state.pZ

        // Carter constant Q for unit rest mass m = 1:
        // Q = p_θ² + cos²θ [ a² (1 - E²) + L_z² / sin²θ ]
        val a = spacetime.a
        val carterQ = pTheta * pTheta + (cosT * cosT) * (a * a * (1.0 - E * E) + (Lz * Lz) / sinT2)
        val carterK = carterQ + (Lz - a * E) * (Lz - a * E)

        // 4. Hamiltonian H = 1/2 g^μν p_μ p_ν
        val gInv = spacetime.inverseMetric(X, Y, Z)
        var twoH = 0.0
        for (mu in 0 until 4) {
            for (nu in 0 until 4) {
                twoH += gInv[mu, nu] * p[mu] * p[nu]
            }
        }
        val H = 0.5 * twoH
        val massShellResidual = abs(twoH + 1.0)

        return Quantities(
            energy = E,
            angularMomentumZ = Lz,
            carterConstantQ = carterQ,
            carterConstantK = carterK,
            hamiltonian = H,
            massShellResidual = massShellResidual
        )
    }

    /**
     * Evaluates the Hamiltonian value H = 1/2 g^μν p_μ p_ν directly.
     */
    fun evaluateHamiltonian(spacetime: KerrSchildSpacetime, state: TimelikeState): Double {
        val gInv = spacetime.inverseMetric(state.X, state.Y, state.Z)
        val p = state.momentum4D
        var twoH = 0.0
        for (mu in 0 until 4) {
            for (nu in 0 until 4) {
                twoH += gInv[mu, nu] * p[mu] * p[nu]
            }
        }
        return 0.5 * twoH
    }

    /**
     * Evaluates the mass-shell residual |g^μν p_μ p_ν + 1.0|.
     */
    fun evaluateMassShellResidual(spacetime: KerrSchildSpacetime, state: TimelikeState): Double {
        return abs(2.0 * evaluateHamiltonian(spacetime, state) + 1.0)
    }
}
