package com.zig.gargantua.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Validates the Schwarzschild limit (a = 0):
 * 1. Event horizon r_+ = 2M
 * 2. Critical impact parameter b_crit = 3√3 M
 * 3. Photon sphere r_ph = 3M
 */
class SchwarzschildLimitTest {

    @Test
    fun eventHorizonRadiusEqualsTwoM() {
        for (m in listOf(0.5, 1.0, 2.0, 10.0)) {
            val s = KerrSpacetime(M = m, a = 0.0)
            assertEquals("Schwarzschild horizon r_+ must equal 2M", 2.0 * m, s.rPlus, 1e-15)
            assertEquals("Schwarzschild inner horizon r_- must equal 0", 0.0, s.rMinus, 1e-15)
        }
    }

    @Test
    fun criticalPhotonImpactParameterEqualsThreeSqrtThreeM() {
        val M = 1.0
        val s = KerrSpacetime(M = M, a = 0.0)
        val expectedBcrit = 3.0 * sqrt(3.0) * M

        assertEquals("Critical impact parameter must be 3√3 M", expectedBcrit, s.bCritSchwarzschild, 1e-14)
        assertEquals("Photon sphere radius must be 3M", 3.0 * M, s.rPhotonSchwarzschild, 1e-15)
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val result = GargantuaReferenceGates.verifySchwarzschildLimit(1.0)
        assertTrue("Gate 1 (Schwarzschild limit) must pass: ${result.message}", result.passed)
        assertTrue("Observed error must be strictly within tolerance", result.maxObservedError <= result.tolerance)
    }

    @Test
    fun photonImpactParameterScatteringVersusCapture() {
        val M = 1.0
        val s = KerrSpacetime(M = M, a = 0.0)
        val bCrit = s.bCritSchwarzschild
        val integrator = GeodesicIntegrator(s)

        // Subcritical photon (b < b_crit): captured by event horizon
        val bSub = bCrit - 0.2
        val subState = NullHamiltonian.createNullState(
            spacetime = s,
            r = 15.0 * M,
            theta = Math.PI * 0.5,
            energy = 1.0,
            Lz = bSub,
            inward = true
        )
        val subPath = integrator.integrate(subState, stepSize = 0.05, maxSteps = 1000)
        assertTrue(
            "Subcritical photon (b < b_crit) must be captured through r_+",
            subPath.last().r <= s.rPlus * 1.05
        )

        // Supercritical photon (b > b_crit): scatters back to large radius
        val bSuper = bCrit + 0.5
        val superState = NullHamiltonian.createNullState(
            spacetime = s,
            r = 15.0 * M,
            theta = Math.PI * 0.5,
            energy = 1.0,
            Lz = bSuper,
            inward = true
        )
        val superPath = integrator.integrate(superState, stepSize = 0.05, maxSteps = 1000)
        val finalState = superPath.last()
        assertTrue(
            "Supercritical photon (b > b_crit) must turn around and escape (r=${finalState.r})",
            finalState.r > s.rPhotonSchwarzschild && finalState.p_r > 0.0
        )
    }
}
