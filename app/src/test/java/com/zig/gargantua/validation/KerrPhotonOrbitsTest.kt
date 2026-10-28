package com.zig.gargantua.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.cos

/**
 * Validates equatorial circular photon orbit radii (Bardeen, Press, Teukolsky 1972):
 * r_ph^± = 2M [ 1 + cos( 2/3 arccos(∓ a/M) ) ]
 */
class KerrPhotonOrbitsTest {

    @Test
    fun zeroSpinYieldsIdenticalThreeMPhotonOrbits() {
        val s = KerrSpacetime(M = 1.0, a = 0.0)
        assertEquals("Prograde photon orbit must be 3M at a=0", 3.0, s.rPhotonPrograde(), 1e-14)
        assertEquals("Retrograde photon orbit must be 3M at a=0", 3.0, s.rPhotonRetrograde(), 1e-14)
    }

    @Test
    fun subExtremalSpinMatchesAnalyticalReferenceValues() {
        // Spin a/M = 0.5
        val s05 = KerrSpacetime(M = 1.0, a = 0.5)
        // arccos(-0.5) = 2π/3, 2/3 * 2π/3 = 4π/9
        val expectedProg05 = 2.0 * (1.0 + cos(4.0 * Math.PI / 9.0)) // ≈ 2.3472963553338606
        // arccos(0.5) = π/3, 2/3 * π/3 = 2π/9
        val expectedRetro05 = 2.0 * (1.0 + cos(2.0 * Math.PI / 9.0)) // ≈ 3.532088886237956

        assertEquals(expectedProg05, s05.rPhotonPrograde(), 1e-14)
        assertEquals(expectedRetro05, s05.rPhotonRetrograde(), 1e-14)

        // Spin a/M = 0.9
        val s09 = KerrSpacetime(M = 1.0, a = 0.9)
        val expectedProg09 = 2.0 * (1.0 + cos((2.0 / 3.0) * acos(-0.9)))
        val expectedRetro09 = 2.0 * (1.0 + cos((2.0 / 3.0) * acos(0.9)))

        assertEquals(expectedProg09, s09.rPhotonPrograde(), 1e-14)
        assertEquals(expectedRetro09, s09.rPhotonRetrograde(), 1e-14)

        // Physical ordering constraint: r_+ < r_prog < 3M < r_retro < 4M
        assertTrue(s09.rPlus < s09.rPhotonPrograde())
        assertTrue(s09.rPhotonPrograde() < 3.0)
        assertTrue(3.0 < s09.rPhotonRetrograde())
        assertTrue(s09.rPhotonRetrograde() < 4.0)
    }

    @Test
    fun extremalSpinApproachesMAndFourM() {
        val sExtremal = KerrSpacetime(M = 1.0, a = 1.0)
        // At a/M = 1:
        // arccos(-1) = π => cos(2π/3) = -0.5 => r_prog = 2*(1 - 0.5) = 1.0 M
        // arccos(1) = 0 => cos(0) = 1.0 => r_retro = 2*(1 + 1.0) = 4.0 M
        assertEquals(1.0, sExtremal.rPhotonPrograde(), 1e-14)
        assertEquals(4.0, sExtremal.rPhotonRetrograde(), 1e-14)
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val result = GargantuaReferenceGates.verifyKerrPhotonOrbits(1.0, 0.5)
        assertTrue("Gate 3 (Kerr photon orbits) must pass: ${result.message}", result.passed)
        assertTrue(result.maxObservedError <= result.tolerance)
    }
}
