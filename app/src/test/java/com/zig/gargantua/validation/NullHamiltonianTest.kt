package com.zig.gargantua.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Validates the null Hamiltonian constraint:
 * H = 1/2 g^μν p_μ p_ν = 0
 */
class NullHamiltonianTest {

    @Test
    fun exactNullInitialStateEvaluatesToZeroHamiltonian() {
        val spacetime = KerrSpacetime(M = 1.0, a = 0.8)

        for (r in listOf(3.0, 5.0, 10.0, 25.0)) {
            for (th in listOf(Math.PI * 0.5, Math.PI * 0.35, Math.PI * 0.65)) {
                val state = NullHamiltonian.createNullState(
                    spacetime = spacetime,
                    r = r,
                    theta = th,
                    energy = 1.0,
                    Lz = 2.0,
                    p_theta = 0.3,
                    inward = true
                )

                val h = NullHamiltonian.evaluate(spacetime, state)
                assertEquals("Null Hamiltonian must be 0 within 1e-12", 0.0, h, 1e-12)
                assertTrue("isNull predicate must return true", NullHamiltonian.isNull(spacetime, state, 1e-12))
            }
        }
    }

    @Test
    fun independentCarterConstructedNullStateSatisfiesZeroHamiltonian() {
        // Breaks state-construction circularity: constructs momentum via Carter's first-order integrals
        // and evaluates H independently through the metric tensor.
        val spacetime = KerrSpacetime(M = 1.0, a = 0.7)

        for (r in listOf(8.0, 15.0, 30.0)) {
            for (th in listOf(Math.PI * 0.4, Math.PI * 0.5, Math.PI * 0.6)) {
                val state = NullHamiltonian.createCarterAnalyticNullState(
                    spacetime = spacetime,
                    r = r,
                    theta = th,
                    energy = 1.0,
                    Lz = 2.5,
                    carterQ = 4.0,
                    inward = true,
                    upward = true
                )

                val h = NullHamiltonian.evaluate(spacetime, state)
                assertEquals("Independent Carter-constructed state must satisfy H = 0 within 1e-13", 0.0, h, 1e-13)
            }
        }
    }

    @Test
    fun forbiddenStateThrowsException() {
        val spacetime = KerrSpacetime(M = 1.0, a = 0.8)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            // High angular momentum at low radius exceeds available energy: classically forbidden turning region
            NullHamiltonian.createNullState(
                spacetime = spacetime,
                r = 3.0,
                theta = Math.PI * 0.3,
                energy = 1.0,
                Lz = 3.5,
                p_theta = 0.8
            )
        }
    }

    @Test
    fun perturbedStateViolatesNullCondition() {
        val spacetime = KerrSpacetime(M = 1.0, a = 0.6)
        val validState = NullHamiltonian.createNullState(
            spacetime = spacetime,
            r = 10.0,
            theta = Math.PI * 0.5,
            energy = 1.0,
            Lz = 4.0
        )

        // Artificially perturb p_r
        val perturbedState = validState.copy(p_r = validState.p_r * 1.1)
        val h = NullHamiltonian.evaluate(spacetime, perturbedState)

        assertTrue("Perturbed momentum must yield non-zero Hamiltonian", abs(h) > 1e-4)
        assertFalse("isNull predicate must detect non-zero Hamiltonian", NullHamiltonian.isNull(spacetime, perturbedState, 1e-6))
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val s = KerrSpacetime(1.0, 0.7)
        val result = GargantuaReferenceGates.verifyNullHamiltonian(s)
        assertTrue("Gate 4 (Null Hamiltonian) must pass: ${result.message}", result.passed)
        assertTrue(result.maxObservedError <= result.tolerance)
    }
}
