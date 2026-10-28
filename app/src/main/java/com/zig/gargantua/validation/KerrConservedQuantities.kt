package com.zig.gargantua.validation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Evaluates conserved quantities and numerical drift along Kerr geodesics.
 *
 * For stationary and axisymmetric Kerr spacetime:
 * 1. Energy at infinity: E = -p_t (exact symmetry along Killing vector ∂_t)
 * 2. Axial angular momentum: L_z = p_φ (exact symmetry along Killing vector ∂_φ)
 * 3. Carter constant Q (separable Carter symmetry via Killing tensor K_μν)
 * 4. Hamiltonian H = 1/2 g^μν p_μ p_ν = 0 (exact along affine parametrization)
 */
object KerrConservedQuantities {

    data class Quantities(
        val energy: Double,
        val Lz: Double,
        val carterConstantQ: Double,
        val carterConstantK: Double,
        val hamiltonian: Double
    )

    data class DriftReport(
        val initial: Quantities,
        val current: Quantities,
        val energyDriftRelative: Double,
        val lzDriftRelative: Double,
        val carterDriftRelative: Double,
        val hamiltonianMaxAbsolute: Double,
        val hasSignificantDrift: Boolean
    )

    /**
     * Computes Carter constant Q and K for null geodesics:
     * Q = p_θ² + cos²θ ( -a² E² + L_z² / sin²θ )
     * K = Q + (L_z - a E)²
     */
    fun evaluate(spacetime: KerrSpacetime, state: NullHamiltonian.PhotonState): Quantities {
        val e = state.energy
        val lz = state.Lz
        val cosT = cos(state.theta)
        val sinT = sin(state.theta).coerceAtLeast(1e-12)
        val a = spacetime.a

        val q = state.p_theta * state.p_theta +
            cosT * cosT * (-a * a * e * e + (lz * lz) / (sinT * sinT))
        val k = q + (lz - a * e) * (lz - a * e)
        val h = NullHamiltonian.evaluate(spacetime, state)

        return Quantities(
            energy = e,
            Lz = lz,
            carterConstantQ = q,
            carterConstantK = k,
            hamiltonian = h
        )
    }

    /**
     * Evaluates drift between initial and final/current state.
     */
    fun measureDrift(
        spacetime: KerrSpacetime,
        initial: NullHamiltonian.PhotonState,
        current: NullHamiltonian.PhotonState,
        relativeTolerance: Double = 1e-4,
        hamiltonianTolerance: Double = 1e-6
    ): DriftReport {
        val qInit = evaluate(spacetime, initial)
        val qCurr = evaluate(spacetime, current)

        val eDrift = relativeError(qInit.energy, qCurr.energy)
        val lzDrift = relativeError(qInit.Lz, qCurr.Lz)
        val qDrift = relativeError(qInit.carterConstantK, qCurr.carterConstantK)
        val hDrift = abs(qCurr.hamiltonian)

        val hasDrift = eDrift > relativeTolerance ||
            lzDrift > relativeTolerance ||
            qDrift > relativeTolerance ||
            hDrift > hamiltonianTolerance

        return DriftReport(
            initial = qInit,
            current = qCurr,
            energyDriftRelative = eDrift,
            lzDriftRelative = lzDrift,
            carterDriftRelative = qDrift,
            hamiltonianMaxAbsolute = hDrift,
            hasSignificantDrift = hasDrift
        )
    }

    private fun relativeError(expected: Double, actual: Double): Double {
        val denom = abs(expected).coerceAtLeast(1e-8)
        return abs(actual - expected) / denom
    }
}
