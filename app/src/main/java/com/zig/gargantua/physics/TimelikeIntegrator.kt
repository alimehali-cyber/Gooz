package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Production 4th-order Runge-Kutta integrator for massive test particles (unit rest mass m = 1)
 * in horizon-penetrating Kerr-Schild Cartesian coordinates.
 *
 * HAMILTONIAN TIMELIKE GEODESIC EQUATIONS:
 *   H = 1/2 g^μν p_μ p_ν = -1/2
 *   dx^μ / dτ = ∂H / ∂p_μ = g^μν p_ν
 *   dp_i / dτ = -∂H / ∂x^i = -1/2 (∂_i g^αβ) p_α p_β
 *   dp_0 / dτ = 0 (exact Killing symmetry along stationary time ∂_T)
 *
 * ARCHITECTURAL INTEGRITY:
 * Uses the exact same [KerrSchildSpacetime] metric and [KerrSchildDerivatives] as the
 * production photon engine, proving that null geodesics and timelike geodesics traverse
 * the exact same underlying Kerr geometry.
 */
class TimelikeIntegrator(
    val spacetime: KerrSchildSpacetime,
    val escapeRadius: Double = 60.0,
    val captureMargin: Double = 0.05,
    val maxSteps: Int = 10000,
    val minStep: Double = 0.001,
    val maxStep: Double = 0.25,
    val stepFactor: Double = 0.05
) {
    init {
        require(escapeRadius > spacetime.rPlus) { "Escape radius must exceed horizon radius" }
        require(captureMargin >= 0.0) { "Capture margin must be non-negative" }
        require(maxSteps > 0) { "Max steps must be positive" }
        require(minStep > 0.0 && maxStep >= minStep) { "Invalid step bounds: minStep=$minStep, maxStep=$maxStep" }
        require(stepFactor > 0.0) { "Step factor must be positive" }
    }

    enum class TerminationState {
        ACTIVE,
        CAPTURED,
        ESCAPED,
        MAX_STEPS,
        INVALID_STATE
    }

    data class IntegrationResult(
        val finalState: TimelikeState,
        val terminationState: TerminationState,
        val stepsTaken: Int,
        val properTime: Double,
        val minRadiusReached: Double,
        val maxMassShellResidual: Double,
        val energyDrift: Double,
        val angularMomentumDrift: Double,
        val carterDrift: Double,
        val trajectory: List<TimelikeState>? = null
    ) {
        val isCaptured: Boolean get() = terminationState == TerminationState.CAPTURED
        val isEscaped: Boolean get() = terminationState == TerminationState.ESCAPED
        val isMaxSteps: Boolean get() = terminationState == TerminationState.MAX_STEPS
        val isValid: Boolean get() = terminationState != TerminationState.INVALID_STATE
    }

    /**
     * Evaluates the phase-space derivatives:
     *   dY/dτ = [dT/dτ, dX/dτ, dY/dτ, dZ/dτ, dp_T/dτ, dp_X/dτ, dp_Y/dτ, dp_Z/dτ]
     */
    fun evaluateDerivatives(state: TimelikeState): DoubleArray {
        val X = state.X
        val Y = state.Y
        val Z = state.Z
        val p = state.momentum4D

        // 1. Inverse metric g^μν at current spatial position
        val gInv = spacetime.inverseMetric(X, Y, Z)

        // 2. Exact spatial derivatives ∂_i g^αβ
        val dG = KerrSchildDerivatives.compute(spacetime.params, X, Y, Z)
        val dg_dX = dG.d_dX
        val dg_dY = dG.d_dY
        val dg_dZ = dG.d_dZ

        // 3. dx^μ / dτ = g^μν p_ν
        var dT = 0.0
        var dX = 0.0
        var dY = 0.0
        var dZ = 0.0
        for (nu in 0 until 4) {
            val pNu = p[nu]
            dT += gInv[0, nu] * pNu
            dX += gInv[1, nu] * pNu
            dY += gInv[2, nu] * pNu
            dZ += gInv[3, nu] * pNu
        }

        // 4. dp_i / dτ = -1/2 (∂_i g^αβ) p_α p_β
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (alpha in 0 until 4) {
            val pA = p[alpha]
            for (beta in 0 until 4) {
                val pB = p[beta]
                val pProduct = pA * pB
                sumX += dg_dX[alpha, beta] * pProduct
                sumY += dg_dY[alpha, beta] * pProduct
                sumZ += dg_dZ[alpha, beta] * pProduct
            }
        }

        return doubleArrayOf(
            dT,
            dX,
            dY,
            dZ,
            0.0, // dp_T / dτ = 0 (stationary)
            -0.5 * sumX,
            -0.5 * sumY,
            -0.5 * sumZ
        )
    }

    /**
     * Advances the state by a single 4th-order Runge-Kutta step of size dτ.
     */
    fun rk4Step(state: TimelikeState, dtau: Double): TimelikeState {
        // Stage 1
        val k1 = evaluateDerivatives(state)

        // Stage 2
        val halfDt = 0.5 * dtau
        val s2 = TimelikeState(
            tau = state.tau + halfDt,
            T = state.T + halfDt * k1[0],
            X = state.X + halfDt * k1[1],
            Y = state.Y + halfDt * k1[2],
            Z = state.Z + halfDt * k1[3],
            pT = state.pT + halfDt * k1[4],
            pX = state.pX + halfDt * k1[5],
            pY = state.pY + halfDt * k1[6],
            pZ = state.pZ + halfDt * k1[7]
        )
        val k2 = evaluateDerivatives(s2)

        // Stage 3
        val s3 = TimelikeState(
            tau = state.tau + halfDt,
            T = state.T + halfDt * k2[0],
            X = state.X + halfDt * k2[1],
            Y = state.Y + halfDt * k2[2],
            Z = state.Z + halfDt * k2[3],
            pT = state.pT + halfDt * k2[4],
            pX = state.pX + halfDt * k2[5],
            pY = state.pY + halfDt * k2[6],
            pZ = state.pZ + halfDt * k2[7]
        )
        val k3 = evaluateDerivatives(s3)

        // Stage 4
        val s4 = TimelikeState(
            tau = state.tau + dtau,
            T = state.T + dtau * k3[0],
            X = state.X + dtau * k3[1],
            Y = state.Y + dtau * k3[2],
            Z = state.Z + dtau * k3[3],
            pT = state.pT + dtau * k3[4],
            pX = state.pX + dtau * k3[5],
            pY = state.pY + dtau * k3[6],
            pZ = state.pZ + dtau * k3[7]
        )
        val k4 = evaluateDerivatives(s4)

        val sixthDt = dtau / 6.0
        return TimelikeState(
            tau = state.tau + dtau,
            T = state.T + sixthDt * (k1[0] + 2.0 * k2[0] + 2.0 * k3[0] + k4[0]),
            X = state.X + sixthDt * (k1[1] + 2.0 * k2[1] + 2.0 * k3[1] + k4[1]),
            Y = state.Y + sixthDt * (k1[2] + 2.0 * k2[2] + 2.0 * k3[2] + k4[2]),
            Z = state.Z + sixthDt * (k1[3] + 2.0 * k2[3] + 2.0 * k3[3] + k4[3]),
            pT = state.pT + sixthDt * (k1[4] + 2.0 * k2[4] + 2.0 * k3[4] + k4[4]),
            pX = state.pX + sixthDt * (k1[5] + 2.0 * k2[5] + 2.0 * k3[5] + k4[5]),
            pY = state.pY + sixthDt * (k1[6] + 2.0 * k2[6] + 2.0 * k3[6] + k4[6]),
            pZ = state.pZ + sixthDt * (k1[7] + 2.0 * k2[7] + 2.0 * k3[7] + k4[7])
        )
    }

    /**
     * Computes the adaptive proper-time step size based on local coordinate radius r.
     */
    fun computeAdaptiveStepSize(r: Double): Double {
        val distToHorizon = max(0.0, r - spacetime.rPlus)
        return (stepFactor * distToHorizon).coerceIn(minStep, maxStep)
    }

    /**
     * Integrates the timelike geodesic starting from [initialState].
     *
     * @param initialState Normalized physical starting state.
     * @param maxProperTime Maximum proper time τ to integrate before halting (default 1000.0).
     * @param recordTrajectory If true, records each intermediate state in the trajectory list.
     * @return [IntegrationResult] detailing termination reason, diagnostics, and invariants.
     */
    fun integrate(
        initialState: TimelikeState,
        maxProperTime: Double = 1000.0,
        recordTrajectory: Boolean = false
    ): IntegrationResult {
        var currentState = initialState
        val trajectoryList = if (recordTrajectory) mutableListOf(initialState) else null

        val captureRadius = spacetime.rPlus + captureMargin
        var minRadiusReached = spacetime.computeR(currentState.X, currentState.Y, currentState.Z)
        var maxMassShellResidual = TimelikeConservedQuantities.evaluateMassShellResidual(spacetime, currentState)

        val q0 = TimelikeConservedQuantities.evaluate(spacetime, initialState)
        var maxEnergyDrift = 0.0
        var maxAngularMomentumDrift = 0.0
        var maxCarterDrift = 0.0

        var terminationState = TerminationState.ACTIVE
        var stepsTaken = 0

        while (stepsTaken < maxSteps) {
            val rCurrent = spacetime.computeR(currentState.X, currentState.Y, currentState.Z)
            if (rCurrent < minRadiusReached) {
                minRadiusReached = rCurrent
            }

            // Check termination: CAPTURED
            if (rCurrent <= captureRadius) {
                terminationState = TerminationState.CAPTURED
                break
            }

            // Check termination: ESCAPED
            if (rCurrent >= escapeRadius) {
                // Confirm outward radial velocity
                val u = currentState.fourVelocity(spacetime)
                val radialVelocity = (currentState.X * u[1] + currentState.Y * u[2] + currentState.Z * u[3]) / rCurrent
                if (radialVelocity > 0.0) {
                    terminationState = TerminationState.ESCAPED
                    break
                }
            }

            // Check target proper time
            val remainingProperTime = maxProperTime - (currentState.tau - initialState.tau)
            if (remainingProperTime <= 1e-12) {
                terminationState = TerminationState.ACTIVE
                break
            }

            // Compute step size
            var dtau = computeAdaptiveStepSize(rCurrent)
            if (dtau > remainingProperTime) {
                dtau = remainingProperTime
            }

            // Advance state
            val nextState = rk4Step(currentState, dtau)

            // Validate state sanity
            if (!nextState.isValid) {
                terminationState = TerminationState.INVALID_STATE
                break
            }

            currentState = nextState
            stepsTaken++

            if (recordTrajectory) {
                trajectoryList?.add(currentState)
            }

            // Monitor conservation
            val qCurr = TimelikeConservedQuantities.evaluate(spacetime, currentState)
            if (qCurr.massShellResidual > maxMassShellResidual) {
                maxMassShellResidual = qCurr.massShellResidual
            }
            val dE = abs(qCurr.energy - q0.energy)
            if (dE > maxEnergyDrift) maxEnergyDrift = dE

            val dLz = abs(qCurr.angularMomentumZ - q0.angularMomentumZ)
            if (dLz > maxAngularMomentumDrift) maxAngularMomentumDrift = dLz

            val dQ = abs(qCurr.carterConstantQ - q0.carterConstantQ)
            if (dQ > maxCarterDrift) maxCarterDrift = dQ
        }

        if (stepsTaken >= maxSteps && terminationState == TerminationState.ACTIVE) {
            terminationState = TerminationState.MAX_STEPS
        }

        return IntegrationResult(
            finalState = currentState,
            terminationState = terminationState,
            stepsTaken = stepsTaken,
            properTime = currentState.tau - initialState.tau,
            minRadiusReached = minRadiusReached,
            maxMassShellResidual = maxMassShellResidual,
            energyDrift = maxEnergyDrift,
            angularMomentumDrift = maxAngularMomentumDrift,
            carterDrift = maxCarterDrift,
            trajectory = trajectoryList
        )
    }
}
