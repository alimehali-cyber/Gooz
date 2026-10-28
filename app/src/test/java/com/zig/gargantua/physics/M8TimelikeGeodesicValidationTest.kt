package com.zig.gargantua.physics

import com.zig.gargantua.geodesic.KerrPhotonIntegrator
import com.zig.gargantua.geodesic.PhotonState4D
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Milestone M8 Validation Suite: Timelike Object / Worldline Engine.
 *
 * SCIENTIFIC AND ARCHITECTURAL ACCEPTANCE CRITERIA:
 * 1. Flat/Minkowski Limit (M -> 0, a -> 0): Constant velocity, straight worldlines, exact proper time.
 * 2. Schwarzschild Limit (a = 0): Radial infall, non-radial bound motion, capture vs escape.
 * 3. Kerr Rotation (a != 0): Prograde vs retrograde frame-dragging asymmetry, 3D inclined Carter constant.
 * 4. Circular-Equatorial Reference Orbit: Stability and comparison against exact Keplerian frequency Ω.
 * 5. Strong-Field Plunge: Clean horizon capture, future-directedness (dT/dτ > 0), mass-shell preservation.
 * 6. Azimuthal Symmetry: Trajectories rotated by azimuthal angle φ match rotated initial conditions.
 * 7. 4-Step Convergence Audit: Production vs half-max, half-min, and both-halved step policies.
 * 8. Shared Spacetime Geometry: Explicit cross-validation proving null and timelike geodesics share the
 *    exact same Kerr metric mathematics.
 */
class M8TimelikeGeodesicValidationTest {

    // =========================================================================
    // SCENARIO 1: Flat / Minkowski Limit (M -> 0, a = 0)
    // =========================================================================
    @Test
    fun scenario1_minkowskiLimitYieldsStraightWorldlinesAndExactProperTime() {
        // Physical limit M -> 0 approaches flat Minkowski spacetime
        val flatSpacetime = KerrSchildSpacetime(1e-9, 0.0)
        val integrator = TimelikeIntegrator(
            spacetime = flatSpacetime,
            escapeRadius = 100.0,
            captureMargin = 0.0,
            maxSteps = 2000,
            minStep = 0.01,
            maxStep = 0.5,
            stepFactor = 0.1
        )

        // Initial velocity vy = 0.6c along Y, vx = 0, vz = 0
        // Gamma factor γ = 1 / √(1 - 0.6²) = 1 / 0.8 = 1.25
        val state0 = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = flatSpacetime,
            X = 5.0,
            Y = 0.0,
            Z = 0.0,
            vx = 0.0,
            vy = 0.6,
            vz = 0.0
        )

        val targetTau = 10.0
        val result = integrator.integrate(state0, maxProperTime = targetTau)

        assertEquals(TimelikeIntegrator.TerminationState.ACTIVE, result.terminationState)
        assertEquals(targetTau, result.properTime, 1e-6)

        val finalState = result.finalState
        // In Minkowski: dT/dτ = γ = 1.25 => ΔT = 1.25 * 10 = 12.5
        // ΔY = vy * ΔT = 0.6 * 12.5 = 7.5
        // X = 5.0, Z = 0.0
        val gamma = 1.0 / sqrt(1.0 - 0.6 * 0.6)
        val expectedT = gamma * targetTau
        val expectedY = 0.6 * expectedT

        assertEquals(expectedT, finalState.T, 1e-5)
        assertEquals(5.0, finalState.X, 1e-5)
        assertEquals(expectedY, finalState.Y, 1e-5)
        assertEquals(0.0, finalState.Z, 1e-5)

        // Exact Minkowski proper time relation: Δτ² = ΔT² - ΔX² - ΔY² - ΔZ²
        val deltaT = finalState.T - state0.T
        val deltaX = finalState.X - state0.X
        val deltaY = finalState.Y - state0.Y
        val deltaZ = finalState.Z - state0.Z
        val computedTau = sqrt(deltaT * deltaT - deltaX * deltaX - deltaY * deltaY - deltaZ * deltaZ)
        assertEquals(targetTau, computedTau, 1e-5)

        // 4-momentum remains constant in Minkowski space
        assertEquals(state0.pT, finalState.pT, 1e-5)
        assertEquals(state0.pX, finalState.pX, 1e-5)
        assertEquals(state0.pY, finalState.pY, 1e-5)
        assertEquals(state0.pZ, finalState.pZ, 1e-5)
    }

    // =========================================================================
    // SCENARIO 2: Schwarzschild Limit (a = 0)
    // =========================================================================
    @Test
    fun scenario2_schwarzschildRadialInfallBoundOrbitAndEscape() {
        val schwSpacetime = KerrSchildSpacetime(1.0, 0.0)
        assertEquals(2.0, schwSpacetime.rPlus, 1e-12)

        val integrator = TimelikeIntegrator(
            spacetime = schwSpacetime,
            escapeRadius = 50.0,
            captureMargin = 0.05,
            maxSteps = 5000,
            minStep = 0.001,
            maxStep = 0.25,
            stepFactor = 0.05
        )

        // 2A: Radial Infall from rest at r = 8.0M
        val radialInfall = TimelikeOrbitFactory.createRadialInfallFromRest(
            spacetime = schwSpacetime,
            r0 = 8.0
        )
        val infallResult = integrator.integrate(radialInfall, maxProperTime = 50.0)

        assertEquals(TimelikeIntegrator.TerminationState.CAPTURED, infallResult.terminationState)
        assertTrue(infallResult.isCaptured)
        // Particle falls strictly along X axis: Y and Z remain 0, angular momentum remains 0
        assertEquals(0.0, infallResult.finalState.Y, 1e-10)
        assertEquals(0.0, infallResult.finalState.Z, 1e-10)
        assertEquals(0.0, infallResult.angularMomentumDrift, 1e-10)
        assertTrue("Min radius reached must be <= r+ + margin", infallResult.minRadiusReached <= 2.051)

        // 2B: Non-radial bound circular orbit at r = 8.0M (r_ISCO = 6.0M in Schwarzschild)
        val boundCircular = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = schwSpacetime,
            r = 8.0,
            isPrograde = true
        )
        val boundResult = integrator.integrate(boundCircular, maxProperTime = 30.0)
        assertEquals(TimelikeIntegrator.TerminationState.ACTIVE, boundResult.terminationState)
        // Specific energy for circular orbit at r=8M: E = (1 - 2/8) / √(1 - 3/8) = (0.75) / √0.625 ≈ 0.9487 < 1.0 (bound)
        assertTrue("Specific energy of bound orbit must be < 1.0", boundCircular.energy < 1.0)
        assertTrue("Bound orbit radius must remain bounded near r=8M", abs(boundResult.finalState.sphericalRadius - 8.0) < 0.25)

        // 2C: Hyperbolic escape trajectory with E > 1.0
        val escapeState = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = schwSpacetime,
            X = 8.0,
            Y = 0.0,
            Z = 0.0,
            vx = 0.3, // Strong outward radial velocity
            vy = 0.3,
            vz = 0.0
        )
        assertTrue("Escape trajectory must have E > 1.0", escapeState.energy > 1.0)

        val escapeResult = integrator.integrate(escapeState, maxProperTime = 150.0)
        assertEquals(TimelikeIntegrator.TerminationState.ESCAPED, escapeResult.terminationState)
        assertTrue(escapeResult.isEscaped)
        assertTrue(escapeResult.finalState.sphericalRadius >= 50.0)
    }

    // =========================================================================
    // SCENARIO 3: Kerr Rotation (a != 0), Prograde vs Retrograde & Carter Constant
    // =========================================================================
    @Test
    fun scenario3_kerrRotationProgradeRetrogradeAsymmetryAndCarterConservation() {
        val kerrSpacetime = KerrSchildSpacetime(1.0, 0.8)
        val integrator = TimelikeIntegrator(
            spacetime = kerrSpacetime,
            escapeRadius = 50.0,
            captureMargin = 0.05,
            maxSteps = 5000,
            minStep = 0.001,
            maxStep = 0.2,
            stepFactor = 0.05
        )

        val rTest = 8.0
        val prograde = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = kerrSpacetime,
            r = rTest,
            isPrograde = true
        )
        val retrograde = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = kerrSpacetime,
            r = rTest,
            isPrograde = false
        )

        // 3A: Frame dragging asymmetry:
        // In Kerr spacetime, prograde orbits have positive L_z and require less energy for stability
        assertTrue("Prograde L_z must be positive", prograde.angularMomentumZ > 0.0)
        assertTrue("Retrograde L_z must be negative", retrograde.angularMomentumZ < 0.0)
        assertTrue("Prograde energy must be less than retrograde energy", prograde.energy < retrograde.energy)

        // 3B: Non-equatorial inclined 3D orbit conserving Carter constant Q
        val inclinedState = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = kerrSpacetime,
            X = 7.0,
            Y = 0.0,
            Z = 3.0,
            vx = 0.0,
            vy = 0.22,
            vz = 0.04
        )

        val q0 = TimelikeConservedQuantities.evaluate(kerrSpacetime, inclinedState)
        assertTrue("Carter constant Q must be strictly positive for inclined orbit", q0.carterConstantQ > 0.0)

        val inclinedResult = integrator.integrate(inclinedState, maxProperTime = 25.0)
        assertEquals(TimelikeIntegrator.TerminationState.ACTIVE, inclinedResult.terminationState)

        // Verify conservation along 3D orbit
        assertTrue("Energy drift must be < 1e-6, got ${inclinedResult.energyDrift}", inclinedResult.energyDrift < 1e-6)
        assertTrue("L_z drift must be < 1e-6, got ${inclinedResult.angularMomentumDrift}", inclinedResult.angularMomentumDrift < 1e-6)
        assertTrue("Carter constant Q drift must be < 1e-3, got ${inclinedResult.carterDrift}", inclinedResult.carterDrift < 1e-3)
        assertTrue("Max mass-shell residual must be < 1e-6, got ${inclinedResult.maxMassShellResidual}", inclinedResult.maxMassShellResidual < 1e-6)
    }

    // =========================================================================
    // SCENARIO 4: Circular-Equatorial Reference Orbit Comparison
    // =========================================================================
    @Test
    fun scenario4_circularEquatorialReferenceOrbitFrequencyAndStability() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val r0 = 10.0
        val state0 = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = r0,
            isPrograde = true
        )

        val integrator = TimelikeIntegrator(
            spacetime = spacetime,
            maxSteps = 5000,
            minStep = 0.001,
            maxStep = 0.2,
            stepFactor = 0.05
        )

        // Integrate for a significant portion of an orbit (proper time τ = 30.0)
        val result = integrator.integrate(state0, maxProperTime = 30.0)
        assertEquals(TimelikeIntegrator.TerminationState.ACTIVE, result.terminationState)

        val finalState = result.finalState
        val finalR = finalState.sphericalRadius

        // Verify radial stability: orbit remains circular within 0.15M
        val radialDrift = abs(finalR - r0)
        assertTrue("Equatorial orbit radial drift must be < 0.15M, got $radialDrift", radialDrift < 0.15)

        // Analytical Keplerian angular velocity in Kerr: Ω = √M / (r^(3/2) + a √M)
        val expectedOmega = 1.0 / (r0.pow(1.5) + spacetime.a)
        val finalPhi = atan2(finalState.Y, finalState.X)
        val deltaPhi = if (finalPhi < 0) finalPhi + 2.0 * Math.PI else finalPhi
        val numericalOmega = deltaPhi / finalState.T

        // Frequency matches to within 3% over the non-linear Kerr-Schild coordinate integration
        val relativeFreqDiff = abs(numericalOmega - expectedOmega) / expectedOmega
        assertTrue("Numerical orbital frequency must closely match Keplerian Ω ($expectedOmega vs $numericalOmega, relDiff=$relativeFreqDiff)", relativeFreqDiff < 0.03)

        // Carter Q must remain exactly 0 for equatorial motion
        assertTrue("Carter drift for equatorial orbit must be < 1e-8, got ${result.carterDrift}", result.carterDrift < 1e-8)
    }

    // =========================================================================
    // SCENARIO 5: Strong-Field Plunge and Capture Termination
    // =========================================================================
    @Test
    fun scenario5_strongFieldPlungeTerminatesAsCapturedWithStrictFutureDirectedness() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val rHorizon = spacetime.rPlus // 1.6

        // Initialize inside ISCO with negative radial velocity toward horizon
        val plungeState = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            X = 3.0,
            Y = 0.0,
            Z = 0.0,
            vx = -0.3,
            vy = 0.05,
            vz = 0.0
        )

        val integrator = TimelikeIntegrator(
            spacetime = spacetime,
            captureMargin = 0.05,
            maxSteps = 5000,
            minStep = 0.0005,
            maxStep = 0.1,
            stepFactor = 0.04
        )

        val result = integrator.integrate(plungeState, maxProperTime = 50.0, recordTrajectory = true)

        assertEquals(TimelikeIntegrator.TerminationState.CAPTURED, result.terminationState)
        assertTrue(result.isCaptured)
        assertTrue("Min radius reached must be <= r+ + margin (1.65), got ${result.minRadiusReached}", result.minRadiusReached <= rHorizon + 0.051)

        // Future-directed check: coordinate time T must strictly increase along all steps
        val traj = result.trajectory!!
        assertTrue("Trajectory must contain recorded steps", traj.size > 10)
        for (i in 1 until traj.size) {
            assertTrue("Coordinate time T must strictly increase: T[${i-1}]=${traj[i-1].T} vs T[$i]=${traj[i].T}", traj[i].T > traj[i - 1].T)
            assertTrue("State must remain valid", traj[i].isValid)
        }

        // Mass-shell residual remains strictly bounded throughout plunge
        assertTrue("Max mass-shell residual during plunge must be < 1e-4, got ${result.maxMassShellResidual}", result.maxMassShellResidual < 1e-4)
    }

    // =========================================================================
    // SCENARIO 6: Azimuthal Symmetry around Spin Axis +Z
    // =========================================================================
    @Test
    fun scenario6_azimuthalSymmetryPreservedUnderRotation() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val integrator = TimelikeIntegrator(spacetime)

        val r0 = 8.0
        val v0 = 0.2
        val state1 = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            X = r0,
            Y = 0.0,
            Z = 1.0,
            vx = 0.0,
            vy = v0,
            vz = 0.02
        )

        val dtau = 1.0
        val step1 = integrator.rk4Step(state1, dtau)

        // Rotate initial state by angle φ = π/2:
        // X' = -Y = 0, Y' = X = r0
        // vx' = -vy = -v0, vy' = vx = 0
        val state2 = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            X = 0.0,
            Y = r0,
            Z = 1.0,
            vx = -v0,
            vy = 0.0,
            vz = 0.02
        )

        val step2 = integrator.rk4Step(state2, dtau)

        // The state after rotation should match step1 rotated by π/2:
        // Expected X2 = -step1.Y, Expected Y2 = step1.X
        assertEquals(-step1.Y, step2.X, 1e-12)
        assertEquals(step1.X, step2.Y, 1e-12)
        assertEquals(step1.Z, step2.Z, 1e-12)
        assertEquals(step1.T, step2.T, 1e-12)
        assertEquals(step1.tau, step2.tau, 1e-12)
    }

    // =========================================================================
    // SCENARIO 7: 4-Step Convergence & Step-Policy Audit
    // =========================================================================
    @Test
    fun scenario7_convergenceAuditComparesStepPolicies() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val targetTau = 20.0

        val circularState = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = 10.0,
            isPrograde = true
        )

        data class PolicyConfig(val name: String, val stepFactor: Double, val minStep: Double, val maxStep: Double)

        val policies = listOf(
            PolicyConfig("Production", 0.05, 0.001, 0.25),
            PolicyConfig("Half Max", 0.05, 0.001, 0.125),
            PolicyConfig("Half Min", 0.05, 0.0005, 0.25),
            PolicyConfig("Both Halved", 0.025, 0.0005, 0.125)
        )

        val results = mutableMapOf<String, TimelikeIntegrator.IntegrationResult>()

        for (policy in policies) {
            val integrator = TimelikeIntegrator(
                spacetime = spacetime,
                stepFactor = policy.stepFactor,
                minStep = policy.minStep,
                maxStep = policy.maxStep,
                maxSteps = 10000
            )
            val res = integrator.integrate(circularState, maxProperTime = targetTau)
            assertEquals(TimelikeIntegrator.TerminationState.ACTIVE, res.terminationState)
            assertFalse("Convergence run must not hit MAX_STEPS", res.isMaxSteps)
            results[policy.name] = res

            // Check invariant conservation across all policies
            assertTrue("${policy.name} energy drift must be < 1e-10", res.energyDrift < 1e-10)
            assertTrue("${policy.name} L_z drift must be < 1e-10", res.angularMomentumDrift < 1e-10)
            assertTrue("${policy.name} mass-shell residual must be < 1e-8", res.maxMassShellResidual < 1e-8)
        }

        // Compare position and momentum differences against reference run ("Both Halved")
        val refResult = results["Both Halved"]!!
        val refFinal = refResult.finalState

        for (policyName in listOf("Production", "Half Max", "Half Min")) {
            val run = results[policyName]!!
            val finalSt = run.finalState

            val deltaPos = sqrt(
                (finalSt.X - refFinal.X).pow(2) +
                (finalSt.Y - refFinal.Y).pow(2) +
                (finalSt.Z - refFinal.Z).pow(2)
            )
            val deltaMom = sqrt(
                (finalSt.pX - refFinal.pX).pow(2) +
                (finalSt.pY - refFinal.pY).pow(2) +
                (finalSt.pZ - refFinal.pZ).pow(2)
            )

            // RK4 global truncation error on smooth trajectory is exceptionally small
            assertTrue("$policyName vs Both Halved position difference must be < 1e-5, got $deltaPos", deltaPos < 1e-5)
            assertTrue("$policyName vs Both Halved momentum difference must be < 1e-5, got $deltaMom", deltaMom < 1e-5)
        }
    }

    // =========================================================================
    // SCENARIO 8: Architectural Proof - Shared Kerr-Schild Metric Dependency
    // =========================================================================
    @Test
    fun scenario8_massiveAndPhotonIntegratorsShareIdenticalKerrSchildGeometry() {
        val sharedSpacetime = KerrSchildSpacetime(1.0, 0.8)

        val timelikeIntegrator = TimelikeIntegrator(sharedSpacetime)
        val photonIntegrator = KerrPhotonIntegrator(sharedSpacetime)

        // 1. Prove identical metric tensor evaluation at arbitrary 3D point
        val testX = 6.5
        val testY = -3.2
        val testZ = 1.4

        val gLowerTimelike = timelikeIntegrator.spacetime.metric(testX, testY, testZ)
        val gLowerPhoton = photonIntegrator.spacetime.metric(testX, testY, testZ)
        assertArrayEquals(gLowerTimelike.elements, gLowerPhoton.elements, 1e-15)

        val gUpperTimelike = timelikeIntegrator.spacetime.inverseMetric(testX, testY, testZ)
        val gUpperPhoton = photonIntegrator.spacetime.inverseMetric(testX, testY, testZ)
        assertArrayEquals(gUpperTimelike.elements, gUpperPhoton.elements, 1e-15)

        // 2. Prove identical spatial derivatives
        val derivTimelike = timelikeIntegrator.spacetime.derivativesOfInverseMetric(testX, testY, testZ)
        val derivPhoton = photonIntegrator.spacetime.derivativesOfInverseMetric(testX, testY, testZ)
        assertArrayEquals(derivTimelike.d_dX.elements, derivPhoton.d_dX.elements, 1e-15)
        assertArrayEquals(derivTimelike.d_dY.elements, derivPhoton.d_dY.elements, 1e-15)
        assertArrayEquals(derivTimelike.d_dZ.elements, derivPhoton.d_dZ.elements, 1e-15)

        // 3. Prove identical horizon and ISCO geometry
        assertEquals(timelikeIntegrator.spacetime.rPlus, photonIntegrator.spacetime.rPlus, 1e-15)
        assertEquals(timelikeIntegrator.spacetime.rMinus, photonIntegrator.spacetime.rMinus, 1e-15)
        assertEquals(timelikeIntegrator.spacetime.a, photonIntegrator.spacetime.a, 1e-15)
        assertEquals(timelikeIntegrator.spacetime.M, photonIntegrator.spacetime.M, 1e-15)

        // 4. Verify that Hamilton's equations for both engines use the exact same formula:
        //    dx^i / dλ = g^{iν} p_ν
        //    dp_i / dλ = -1/2 (∂_i g^{αβ}) p_α p_β
        // Test with p_0 = -1.0 for a photon vs massive particle having the same spatial momentum
        val pX = 0.2
        val pY = -0.1
        val pZ = 0.05
        val photonDerivs = photonIntegrator.evaluateDerivatives(doubleArrayOf(testX, testY, testZ, pX, pY, pZ))

        val timelikeStateWithP0Minus1 = TimelikeState(
            tau = 0.0,
            T = 0.0,
            X = testX,
            Y = testY,
            Z = testZ,
            pT = -1.0,
            pX = pX,
            pY = pY,
            pZ = pZ
        )
        val timelikeDerivs = timelikeIntegrator.evaluateDerivatives(timelikeStateWithP0Minus1)

        // Spatial velocity dx^i / dλ:
        assertEquals(photonDerivs[0], timelikeDerivs[1], 1e-15) // dX
        assertEquals(photonDerivs[1], timelikeDerivs[2], 1e-15) // dY
        assertEquals(photonDerivs[2], timelikeDerivs[3], 1e-15) // dZ

        // Momentum derivatives dp_i / dλ:
        assertEquals(photonDerivs[3], timelikeDerivs[5], 1e-15) // dpX
        assertEquals(photonDerivs[4], timelikeDerivs[6], 1e-15) // dpY
        assertEquals(photonDerivs[5], timelikeDerivs[7], 1e-15) // dpZ
    }
}
