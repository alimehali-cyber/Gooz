package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.validation.KerrSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates Requirement 7: Deterministic Schwarzschild shadow validation.
 *
 * Verifies that the shadow boundary of the black hole emerging from numerical Kerr-Schild
 * geodesic ray tracing matches the theoretical critical impact parameter:
 *   b_crit = 3√3 M ≈ 5.1961524 M
 * directly referencing the authoritative M2 [KerrSpacetime.bCritSchwarzschild].
 */
class SchwarzschildShadowTest {

    private fun traceRay(
        integrator: KerrPhotonIntegrator,
        spacetime: KerrSchildSpacetime,
        startX: Double,
        startY: Double,
        startZ: Double,
        dirX: Double,
        dirY: Double,
        dirZ: Double
    ): KerrPhotonIntegrator.RayTraceResult {
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = startX, Y = startY, Z = startZ,
            dx = dirX, dy = dirY, dz = dirZ
        )
        return integrator.traceRay(initialState = initialPhoton)
    }

    @Test
    fun numericalShadowBoundaryMatchesM2TheoreticalCriticalImpactParameter() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)
        val integrator = KerrPhotonIntegrator(spacetime)

        // M2 reference critical impact parameter
        val m2Reference = KerrSpacetime(M = M, a = 0.0)
        val bCritTheoretical = m2Reference.bCritSchwarzschild

        val observerX = -60.0

        // Subcritical ray (b < b_crit) must be captured into horizon
        val subcriticalB = bCritTheoretical * 0.95 // ~ 4.936
        val subResult = traceRay(integrator, spacetime, observerX, subcriticalB, 0.0, 1.0, 0.0, 0.0)
        assertTrue(
            "Ray with b = $subcriticalB < b_crit ($bCritTheoretical) must be captured",
            subResult.isCaptured
        )

        // Supercritical ray (b > b_crit) must escape to asymptotic infinity
        val supercriticalB = bCritTheoretical * 1.05 // ~ 5.456
        val superResult = traceRay(integrator, spacetime, observerX, supercriticalB, 0.0, 1.0, 0.0, 0.0)
        assertFalse(
            "Ray with b = $supercriticalB > b_crit ($bCritTheoretical) must escape",
            superResult.isCaptured
        )

        // Bisection search to find the numerical shadow boundary
        var bLow = 4.8
        var bHigh = 5.5
        for (iter in 0 until 14) {
            val bMid = 0.5 * (bLow + bHigh)
            val res = traceRay(integrator, spacetime, observerX, bMid, 0.0, 1.0, 0.0, 0.0)
            if (res.isCaptured) {
                bLow = bMid
            } else {
                bHigh = bMid
            }
        }

        val bCritNumerical = 0.5 * (bLow + bHigh)
        val relError = abs(bCritNumerical - bCritTheoretical) / bCritTheoretical

        // For observer at R = 60M, finite-distance corrections are ~ M/R < 0.02.
        // Numerical bisection matches to < 0.2%.
        assertTrue(
            "Numerical shadow boundary $bCritNumerical must agree with M2 b_crit ($bCritTheoretical) within 0.2%, got error $relError",
            relError < 0.002
        )
    }

    @Test
    fun shadowBoundaryIsSymmetricBetweenEquatorialAndPolarPlanes() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)
        val integrator = KerrPhotonIntegrator(spacetime)
        val observerX = -60.0

        // Find boundary in equatorial plane (Y axis)
        var bLowY = 4.8
        var bHighY = 5.5
        for (iter in 0 until 10) {
            val bMid = 0.5 * (bLowY + bHighY)
            val res = traceRay(integrator, spacetime, observerX, bMid, 0.0, 1.0, 0.0, 0.0)
            if (res.isCaptured) bLowY = bMid else bHighY = bMid
        }
        val bCritY = 0.5 * (bLowY + bHighY)

        // Find boundary in polar plane (Z axis)
        var bLowZ = 4.8
        var bHighZ = 5.5
        for (iter in 0 until 10) {
            val bMid = 0.5 * (bLowZ + bHighZ)
            val res = traceRay(integrator, spacetime, observerX, 0.0, bMid, 1.0, 0.0, 0.0)
            if (res.isCaptured) bLowZ = bMid else bHighZ = bMid
        }
        val bCritZ = 0.5 * (bLowZ + bHighZ)

        assertEquals("Schwarzschild shadow must be spherically symmetric", bCritY, bCritZ, 0.005)
    }
}
