package com.zig.gargantua.disk

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates Requirement 6: Relativistic frequency shift g = ν_obs / ν_emit.
 *
 * Verifies:
 * 1. Approaching disk material (Doppler blueshift) produces higher frequency shift g than receding side.
 * 2. Receding material exhibits Doppler redshift (g < 1.0).
 * 3. Invariant frequency shift g remains finite, positive, and non-singular across the entire disk.
 * 4. In Schwarzschild (a = 0), symmetric points have equal and opposite orbital Doppler contributions.
 */
class RelativisticFrequencyShiftTest {

    @Test
    fun approachingMaterialProducesHigherFrequencyShiftThanRecedingMaterial() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.5)
        val disk = AccretionDiskModel(M = M, a = 0.5)

        val camX = -30.0
        val camY = 0.0
        val camZ = 5.0

        val r = 10.0
        // Approaching side: Y > 0 (rotating towards camera at -X)
        val hitX = 0.0
        val hitYApp = r
        val hitYRec = -r

        // Backwards camera ray heading in +X direction: rayPx > 0
        val rayPx = 0.98
        val rayPy = 0.0

        val gApp = disk.frequencyShift(spacetime, hitX, hitYApp, rayPx, rayPy, camX, camY, camZ)
        val gRec = disk.frequencyShift(spacetime, hitX, hitYRec, rayPx, rayPy, camX, camY, camZ)

        // Approaching side must have strictly higher frequency shift than receding side
        assertTrue("Approaching g ($gApp) must exceed receding g ($gRec)", gApp > gRec)

        // Receding side must be redshifted (g < 1.0)
        assertTrue("Receding side must be redshifted (g < 1.0), got $gRec", gRec < 1.0)

        // Asymmetry ratio must be substantial
        val ratio = gApp / gRec
        assertTrue("Asymmetry ratio must exceed 1.2, got $ratio", ratio > 1.2)
    }

    @Test
    fun frequencyShiftRemainsFiniteAndPositiveAcrossDiskDomain() {
        val testSpacetimes = listOf(
            KerrSchildSpacetime(M = 1.0, a = 0.0),
            KerrSchildSpacetime(M = 1.0, a = 0.8),
            KerrSchildSpacetime(M = 1.0, a = -0.8)
        )

        for (spacetime in testSpacetimes) {
            val disk = AccretionDiskModel(M = spacetime.M, a = spacetime.a)
            val camX = -25.0
            val camY = 0.0
            val camZ = 4.0

            for (r in listOf(disk.innerRadius + 0.1, 8.0, 12.0, 18.0, disk.outerRadius - 0.1)) {
                for (phi in listOf(0.0, Math.PI * 0.25, Math.PI * 0.5, Math.PI * 0.75, Math.PI, Math.PI * 1.5)) {
                    val hitX = r * kotlin.math.cos(phi)
                    val hitY = r * kotlin.math.sin(phi)
                    val rayPx = 0.8
                    val rayPy = 0.2

                    val g = disk.frequencyShift(spacetime, hitX, hitY, rayPx, rayPy, camX, camY, camZ)

                    assertFalse("g must not be NaN", g.isNaN())
                    assertFalse("g must not be Infinite", g.isInfinite())
                    assertTrue("g must be positive, got $g", g > 0.0)
                    assertTrue("g must be in physically reasonable range [0.05, 5.0], got $g", g in 0.05..5.0)
                }
            }
        }
    }

    @Test
    fun schwarzschildDopplerSymmetry() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)
        val disk = AccretionDiskModel(M = M, a = 0.0)

        val camX = -30.0
        val camY = 0.0
        val camZ = 0.0 // Observer in equatorial plane

        val r = 12.0
        val rayPx = 1.0
        val rayPy = 0.0

        val gApp = disk.frequencyShift(spacetime, 0.0, r, rayPx, rayPy, camX, camY, camZ)
        val gRec = disk.frequencyShift(spacetime, 0.0, -r, rayPx, rayPy, camX, camY, camZ)

        // For symmetric circular orbits in Schwarzschild, the orbital velocity magnitude is identical
        // 1/g_app + 1/g_rec = 2 * u0 * (1/uObs0) because the +- Omega * Lz terms cancel!
        val u0 = disk.emitterFourVelocity(spacetime, 0.0, r)[0]
        val gCam = spacetime.metric(camX, camY, camZ)
        val uObs0 = 1.0 / kotlin.math.sqrt(-gCam[0, 0])

        val sumInvG = (1.0 / gApp) + (1.0 / gRec)
        val expectedSumInvG = 2.0 * (u0 / uObs0)

        assertEquals("Symmetric Doppler shift inversion sum must cancel", expectedSumInvG, sumInvG, 1e-12)
    }
}
