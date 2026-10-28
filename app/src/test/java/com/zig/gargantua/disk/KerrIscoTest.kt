package com.zig.gargantua.disk

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates Requirement 1: Kerr ISCO (Innermost Stable Circular Orbit) calculation.
 *
 * Verifies exact analytical properties:
 * 1. Schwarzschild limit: r_ISCO = 6M for a = 0.
 * 2. Prograde monotonic decrease: as a increases from 0 to M, r_ISCO decreases from 6M towards 1M.
 * 3. Retrograde monotonic increase: as a decreases from 0 to -M, r_ISCO increases from 6M towards 9M.
 * 4. Near-extremal numerical stability for |a*| -> 1.0.
 * 5. Mass linearity: r_ISCO(kM, ka) = k * r_ISCO(M, a).
 */
class KerrIscoTest {

    @Test
    fun schwarzschildIscoMatchesExactSixM() {
        val M = 1.0
        val isco = KerrIsco.compute(M, 0.0)
        assertEquals("Schwarzschild ISCO must be exactly 6.0M", 6.0 * M, isco, 1e-14)

        val scaledM = 2.5
        val scaledIsco = KerrIsco.compute(scaledM, 0.0)
        assertEquals("Schwarzschild ISCO must scale linearly with mass", 6.0 * scaledM, scaledIsco, 1e-14)
    }

    @Test
    fun progradeSpinMonotonicallyDecreasesIscoTowardsOneM() {
        val M = 1.0
        val progradeSpins = listOf(0.0, 0.2, 0.4, 0.6, 0.8, 0.9, 0.95, 0.99, 0.999)
        var previousIsco = Double.MAX_VALUE

        for (a in progradeSpins) {
            val isco = KerrIsco.compute(M, a)
            assertTrue(
                "Prograde ISCO must monotonically decrease with spin: spin $a yielded $isco >= previous $previousIsco",
                isco < previousIsco
            )
            assertTrue("Prograde ISCO must remain >= 1.0M, got $isco", isco >= 1.0 * M)
            previousIsco = isco
        }

        // For a* = 0.999, ISCO is very close to 1M (~1.18M)
        val nearExtremal = KerrIsco.compute(M, 0.999)
        assertTrue("Near-extremal prograde ISCO must approach 1M, got $nearExtremal", nearExtremal < 1.3 * M)
    }

    @Test
    fun retrogradeSpinMonotonicallyIncreasesIscoTowardsNineM() {
        val M = 1.0
        val retrogradeSpins = listOf(0.0, -0.2, -0.4, -0.6, -0.8, -0.9, -0.95, -0.99, -0.999)
        var previousIsco = 0.0

        for (a in retrogradeSpins) {
            val isco = KerrIsco.compute(M, a)
            assertTrue(
                "Retrograde ISCO must monotonically increase with magnitude: spin $a yielded $isco <= previous $previousIsco",
                isco > previousIsco
            )
            assertTrue("Retrograde ISCO must remain <= 9.0M, got $isco", isco <= 9.0 * M)
            previousIsco = isco
        }

        // For a* = -0.999, ISCO is very close to 9M (~8.98M)
        val nearExtremal = KerrIsco.compute(M, -0.999)
        assertTrue("Near-extremal retrograde ISCO must approach 9M, got $nearExtremal", nearExtremal > 8.9 * M)
    }

    @Test
    fun nearExtremalSpinsRemainNumericallyStable() {
        val M = 1.0
        val extremePositive = KerrIsco.compute(M, 0.999999)
        assertFalse("Extreme positive ISCO must not be NaN", extremePositive.isNaN())
        assertFalse("Extreme positive ISCO must not be Infinite", extremePositive.isInfinite())
        assertTrue("Extreme positive ISCO in [1.0, 1.1]", extremePositive in 1.0..1.1)

        val extremeNegative = KerrIsco.compute(M, -0.999999)
        assertFalse("Extreme negative ISCO must not be NaN", extremeNegative.isNaN())
        assertFalse("Extreme negative ISCO must not be Infinite", extremeNegative.isInfinite())
        assertTrue("Extreme negative ISCO in [8.9, 9.0]", extremeNegative in 8.9..9.0)
    }

    @Test
    fun massScalingLinearity() {
        val spins = listOf(0.0, 0.5, -0.5, 0.85, -0.92)
        val M1 = 1.0
        val M2 = 3.7

        for (aOverM in spins) {
            val isco1 = KerrIsco.compute(M1, aOverM * M1)
            val isco2 = KerrIsco.compute(M2, aOverM * M2)

            val ratio = isco2 / isco1
            assertEquals("ISCO must scale linearly with mass M", M2 / M1, ratio, 1e-12)
        }
    }
}
