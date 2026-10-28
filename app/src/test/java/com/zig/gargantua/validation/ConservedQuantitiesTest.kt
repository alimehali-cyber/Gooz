package com.zig.gargantua.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Validates that energy E = -p_t and axial angular momentum L_z = p_φ
 * are conserved along geodesics, and that the drift detection layer catches
 * significant deviations.
 */
class ConservedQuantitiesTest {

    @Test
    fun nonEquatorialKerrTrajectoryConservesAllIntegralsOfMotion() {
        // Genuinely non-trivial test: r and θ both evolve dynamically.
        // Verifies E, Lz, Carter Q/K, and H at EVERY sampled state along the trajectory.
        val s = KerrSpacetime(M = 1.0, a = 0.7)
        val integrator = GeodesicIntegrator(s)

        val r0 = 25.0
        val theta0 = Math.PI / 3.0 // 60 degrees (off-equatorial)
        val initial = NullHamiltonian.createNullState(
            spacetime = s,
            r = r0,
            theta = theta0,
            energy = 1.0,
            Lz = 8.0,
            p_theta = 0.3,
            inward = true
        )

        val qInit = KerrConservedQuantities.evaluate(s, initial)
        val path = integrator.integrate(initial, stepSize = 0.05, maxSteps = 400)
        assertTrue("Trajectory must execute at least 300 steps", path.size >= 300)

        var minR = r0
        var maxR = r0
        var minTheta = theta0
        var maxTheta = theta0
        var maxEDrift = 0.0
        var maxLzDrift = 0.0
        var maxQDrift = 0.0
        var maxKDrift = 0.0
        var maxH = 0.0

        for (state in path) {
            minR = minOf(minR, state.r)
            maxR = maxOf(maxR, state.r)
            minTheta = minOf(minTheta, state.theta)
            maxTheta = maxOf(maxTheta, state.theta)

            val q = KerrConservedQuantities.evaluate(s, state)
            val eErr = abs(q.energy - qInit.energy) / qInit.energy
            val lzErr = abs(q.Lz - qInit.Lz) / abs(qInit.Lz)
            val qErr = abs(q.carterConstantQ - qInit.carterConstantQ) / abs(qInit.carterConstantQ)
            val kErr = abs(q.carterConstantK - qInit.carterConstantK) / abs(qInit.carterConstantK)
            val hErr = abs(q.hamiltonian)

            maxEDrift = maxOf(maxEDrift, eErr)
            maxLzDrift = maxOf(maxLzDrift, lzErr)
            maxQDrift = maxOf(maxQDrift, qErr)
            maxKDrift = maxOf(maxKDrift, kErr)
            maxH = maxOf(maxH, hErr)

            // Strict per-step checks
            assertEquals("Energy E must be conserved at every step", qInit.energy, q.energy, 1e-12)
            assertEquals("Angular momentum Lz must be conserved at every step", qInit.Lz, q.Lz, 1e-12)
            assertTrue("Carter Q drift must be < 1e-9 at every step", qErr < 1e-9)
            assertTrue("Carter K drift must be < 1e-9 at every step", kErr < 1e-9)
            assertTrue("Hamiltonian |H| must remain < 1e-9 at every step", hErr < 1e-9)
        }

        // Prove that both r and θ underwent substantial dynamic evolution
        val deltaR = maxR - minR
        val deltaTheta = maxTheta - minTheta
        assertTrue("Radius r must evolve non-trivially (observed Δr=$deltaR)", deltaR > 10.0)
        assertTrue("Colatitude θ must evolve non-trivially (observed Δθ=$deltaTheta rad)", deltaTheta > 0.15)

        // Documented maximum errors across the entire 400-step path
        assertTrue("Max energy drift across path < 1e-12 (observed $maxEDrift)", maxEDrift < 1e-12)
        assertTrue("Max Lz drift across path < 1e-12 (observed $maxLzDrift)", maxLzDrift < 1e-12)
        assertTrue("Max Carter Q drift across path < 1e-9 (observed $maxQDrift)", maxQDrift < 1e-9)
        assertTrue("Max Carter K drift across path < 1e-9 (observed $maxKDrift)", maxKDrift < 1e-9)
        assertTrue("Max Hamiltonian drift across path < 1e-9 (observed $maxH)", maxH < 1e-9)
    }

    @Test
    fun driftDetectorFlagsArtificialPerturbation() {
        val s = KerrSpacetime(M = 1.0, a = 0.5)
        val initial = NullHamiltonian.createNullState(
            spacetime = s,
            r = 15.0,
            theta = Math.PI * 0.5,
            energy = 1.0,
            Lz = 4.0
        )

        // Artificially corrupted state with 5% energy error
        val corruptedState = initial.copy(p_t = initial.p_t * 1.05)
        val report = KerrConservedQuantities.measureDrift(s, initial, corruptedState, relativeTolerance = 1e-4)

        assertTrue("Drift detector must flag corrupted state", report.hasSignificantDrift)
        assertTrue("Energy drift must be approximately 5%", abs(report.energyDriftRelative - 0.05) < 1e-3)
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val s = KerrSpacetime(1.0, 0.7)
        val result = GargantuaReferenceGates.verifyConservedQuantitiesDrift(s)
        assertTrue("Gate 5 (Conserved quantities drift) must pass: ${result.message}", result.passed)
        assertTrue(result.maxObservedError <= result.tolerance)
    }
}
