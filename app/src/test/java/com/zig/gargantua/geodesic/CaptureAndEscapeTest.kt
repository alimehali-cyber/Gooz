package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates Requirement 5: Deterministic capture vs escape classification.
 * Rays aimed into the black hole must be captured at the horizon; rays aimed away or with
 * super-critical impact parameters must escape to the background boundary.
 */
class CaptureAndEscapeTest {

    @Test
    fun captureClassificationForSubCriticalRays() {
        val testSpacetimes = listOf(
            KerrSchildSpacetime(M = 1.0, a = 0.0),    // Schwarzschild
            KerrSchildSpacetime(M = 1.0, a = 0.8),    // Prograde
            KerrSchildSpacetime(M = 1.0, a = -0.8)    // Retrograde
        )

        for (st in testSpacetimes) {
            val integrator = KerrPhotonIntegrator(st, captureHorizonMargin = 0.05, maxSteps = 1000)

            // Direct head-on ray into black hole: camera at X = 30, aim at origin (target = 0, 0, 0)
            val camera = CameraModel(posX = 30.0, posY = 0.0, posZ = 0.0)
            val headOnRay = camera.createInitialPhotonState(st, ndcX = 0.0, ndcY = 0.0)

            val headOnResult = integrator.traceRay(headOnRay)
            assertTrue("Head-on ray must be CAPTURED for a=${st.a}", headOnResult.isCaptured)
            assertTrue(
                "Final radius must be within capture margin of rPlus",
                headOnResult.finalState.computeR(st.a) <= st.rPlus + 0.06
            )

            // Small impact parameter ray (b = 2.0M < b_crit): starts at X = -30, Y = 2.0, moves +X
            val subCritRay = CameraModel.createNullStateFromDirection(
                st, X = -30.0, Y = 2.0, Z = 0.0, dx = 1.0, dy = 0.0, dz = 0.0
            )
            val subCritResult = integrator.traceRay(subCritRay)
            assertTrue("Sub-critical impact parameter ray must be CAPTURED for a=${st.a}", subCritResult.isCaptured)
        }
    }

    @Test
    fun escapeClassificationForSuperCriticalRays() {
        val testSpacetimes = listOf(
            KerrSchildSpacetime(M = 1.0, a = 0.0),
            KerrSchildSpacetime(M = 1.0, a = 0.85),
            KerrSchildSpacetime(M = 1.0, a = -0.85)
        )

        for (st in testSpacetimes) {
            val integrator = KerrPhotonIntegrator(st, escapeRadius = 50.0, maxSteps = 1000)

            // 1. Ray directed radially away from black hole
            val outwardRay = CameraModel.createNullStateFromDirection(
                st, X = 20.0, Y = 10.0, Z = 5.0, dx = 1.0, dy = 0.5, dz = 0.25
            )
            val outwardResult = integrator.traceRay(outwardRay)
            assertTrue("Outward ray must ESCAPE for a=${st.a}", outwardResult.isEscaped)
            assertTrue("Final radius must exceed escape boundary", outwardResult.finalState.computeR(st.a) >= 50.0)

            // 2. Large impact parameter ray (b = 20.0M >> b_crit)
            val largeBRay = CameraModel.createNullStateFromDirection(
                st, X = -40.0, Y = 20.0, Z = 0.0, dx = 1.0, dy = 0.0, dz = 0.0
            )
            val largeBResult = integrator.traceRay(largeBRay)
            assertTrue("Large impact parameter ray must ESCAPE for a=${st.a}", largeBResult.isEscaped)
            assertTrue("Final radius must reach escape radius", largeBResult.finalState.computeR(st.a) >= 50.0)
        }
    }
}
