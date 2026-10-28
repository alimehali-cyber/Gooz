package com.zig.gargantua.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates symmetry principles and geometrized scaling:
 * 1. a = 0 removes spin asymmetry (prograde == retrograde).
 * 2. Positive/negative spin sign symmetry (prograde(a) == retrograde(-a)).
 * 3. Linear mass scaling under G = c = 1 geometrized units.
 */
class SymmetriesAndScalingTest {

    @Test
    fun zeroSpinHasIdenticalProgradeAndRetrogradeRadii() {
        val s = KerrSpacetime(M = 1.0, a = 0.0)
        assertEquals(s.rPhotonPrograde(), s.rPhotonRetrograde(), 1e-15)
        assertEquals(3.0, s.rPhotonPrograde(), 1e-15)
    }

    @Test
    fun spinSignReversalSwapsProgradeAndRetrograde() {
        for (spin in listOf(0.2, 0.5, 0.8, 0.95)) {
            val sPlus = KerrSpacetime(M = 1.0, a = spin)
            val sMinus = KerrSpacetime(M = 1.0, a = -spin)

            assertEquals("Horizon must depend only on a²", sPlus.rPlus, sMinus.rPlus, 1e-15)
            assertEquals("Inner horizon must depend only on a²", sPlus.rMinus, sMinus.rMinus, 1e-15)

            // Co-rotating and counter-rotating radii depend only on |a|
            assertEquals(
                sPlus.rPhotonCorotating(),
                sMinus.rPhotonCorotating(),
                1e-14
            )
            assertEquals(
                sPlus.rPhotonCounterrotating(),
                sMinus.rPhotonCounterrotating(),
                1e-14
            )

            // Parity symmetry: (+a, +Lz) orbit has identical radius to (-a, -Lz) orbit
            assertEquals(
                "Spin sign reversal parity must match (+a, +Lz) with (-a, -Lz)",
                sPlus.rPhotonForSignedLz(+1.0),
                sMinus.rPhotonForSignedLz(-1.0),
                1e-14
            )
            assertEquals(
                sPlus.rPhotonForSignedLz(-1.0),
                sMinus.rPhotonForSignedLz(+1.0),
                1e-14
            )

            // Reversing spin sign swaps an orbit with fixed coordinate angular momentum (+Lz) from co- to counter-rotating
            assertEquals(
                sPlus.rPhotonCorotating(),
                sPlus.rPhotonForSignedLz(+1.0),
                1e-14
            )
            assertEquals(
                sPlus.rPhotonCounterrotating(),
                sMinus.rPhotonForSignedLz(+1.0),
                1e-14
            )
        }
    }

    @Test
    fun geometrizedMassScalingPreservesDimensionlessRatios() {
        val s1 = KerrSpacetime(M = 1.0, a = 0.7)
        val alpha = 3.7
        val sAlpha = KerrSpacetime(M = 1.0 * alpha, a = 0.7 * alpha)

        // Lengths scale linearly with alpha
        assertEquals(s1.rPlus * alpha, sAlpha.rPlus, 1e-13)
        assertEquals(s1.rMinus * alpha, sAlpha.rMinus, 1e-13)
        assertEquals(s1.rPhotonPrograde() * alpha, sAlpha.rPhotonPrograde(), 1e-13)
        assertEquals(s1.rPhotonRetrograde() * alpha, sAlpha.rPhotonRetrograde(), 1e-13)

        // Dimensionless ratios remain strictly invariant
        assertEquals(s1.rPlus / s1.M, sAlpha.rPlus / sAlpha.M, 1e-14)
        assertEquals(s1.rPhotonPrograde() / s1.M, sAlpha.rPhotonPrograde() / sAlpha.M, 1e-14)
        assertEquals(s1.rPhotonRetrograde() / s1.M, sAlpha.rPhotonRetrograde() / sAlpha.M, 1e-14)
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val result = GargantuaReferenceGates.verifySymmetriesAndLimits()
        assertTrue("Gate 7 (Symmetries and limits) must pass: ${result.message}", result.passed)
        assertTrue(result.maxObservedError <= result.tolerance)
    }

    @Test
    fun allReferenceGatesExecuteSuccessfully() {
        val results = GargantuaReferenceGates.runAllGates()
        assertEquals(15, results.size)
        for (r in results) {
            assertTrue("Gate ${r.gateId} must pass: ${r.message}", r.passed)
        }
    }
}
