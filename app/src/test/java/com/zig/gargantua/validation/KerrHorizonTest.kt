package com.zig.gargantua.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Validates the Kerr horizon formulation:
 * r_+ = M + √(M² - a²)
 * r_- = M - √(M² - a²)
 */
class KerrHorizonTest {

    @Test
    fun zeroSpinReducesStrictlyToSchwarzschild() {
        val s = KerrSpacetime(M = 1.0, a = 0.0)
        assertEquals(2.0, s.rPlus, 1e-15)
        assertEquals(0.0, s.rMinus, 1e-15)
        assertTrue(s.isSchwarzschild)
    }

    @Test
    fun horizonMonotonicallyDecreasesWithSpinMagnitude() {
        val M = 1.0
        val spins = listOf(0.0, 0.2, 0.4, 0.6, 0.8, 0.9, 0.99, 1.0)
        var prevRPlus = Double.POSITIVE_INFINITY

        for (spin in spins) {
            val s = KerrSpacetime(M, spin)
            val expectedRPlus = M + sqrt(M * M - spin * spin)
            assertEquals(expectedRPlus, s.rPlus, 1e-15)
            assertTrue("r_+ must strictly decrease as spin increases", s.rPlus <= prevRPlus)
            prevRPlus = s.rPlus
        }
    }

    @Test
    fun extremalSpinReachesEqualOuterAndInnerHorizon() {
        val M = 1.0
        val s = KerrSpacetime(M = M, a = M)
        assertEquals("Extremal Kerr outer horizon must be M", M, s.rPlus, 1e-15)
        assertEquals("Extremal Kerr inner horizon must be M", M, s.rMinus, 1e-15)
        assertEquals("Δ(r_+) must be zero at the horizon", 0.0, s.delta(s.rPlus), 1e-15)
    }

    @Test
    fun superExtremalSpinIsStrictlyRejected() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            KerrSpacetime(M = 1.0, a = 1.0001)
        }
        assertTrue(
            "Exception message must mention super-extremal rejection",
            exception.message!!.contains("super-extremal")
        )

        assertThrows(IllegalArgumentException::class.java) {
            KerrSpacetime(M = 1.0, a = -1.05)
        }
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val result = GargantuaReferenceGates.verifyKerrHorizon(1.0)
        assertTrue("Gate 2 (Kerr horizon) must pass: ${result.message}", result.passed)
        assertTrue(result.maxObservedError <= result.tolerance)
    }
}
