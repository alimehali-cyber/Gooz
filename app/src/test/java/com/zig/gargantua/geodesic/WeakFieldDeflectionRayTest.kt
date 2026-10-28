package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.validation.WeakFieldDeflection as M2WeakField
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Requirement 3: Weak-field gravitational deflection comparison against Phase M2 reference.
 * Compares numerical RK4 deflection against Einstein's 4M/b and 2PN expansions, demonstrating
 * monotonic convergence as impact parameter b increases.
 */
class WeakFieldDeflectionRayTest {

    @Test
    fun numericalDeflectionConvergesToWeakFieldPrediction() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)
        val integrator = KerrPhotonIntegrator(
            spacetime = spacetime,
            escapeRadius = 500.0,
            maxSteps = 2500,
            baseStepFactor = 0.05
        )

        val impactParameters = listOf(50.0, 100.0, 200.0)
        val relativeErrorsLeading = mutableListOf<Double>()
        val relativeErrors2PN = mutableListOf<Double>()

        for (b in impactParameters) {
            // Initial position: incoming from X = -450M with impact parameter Y = b, Z = 0
            val x0 = -450.0
            val y0 = b
            val z0 = 0.0

            val initialPhoton = CameraModel.createNullStateFromDirection(
                spacetime = spacetime,
                X = x0, Y = y0, Z = z0,
                dx = 1.0, dy = 0.0, dz = 0.0
            )

            val result = integrator.traceRay(initialPhoton)
            assertTrue("Weak-field ray at b=$b must escape", result.isEscaped)

            // Measure numerical deflection angle from spatial velocities
            val vFinal = result.finalState.velocity(spacetime)
            val numericalDeflection = abs(atan2(vFinal[2], vFinal[1]))

            // Compare against M2 reference values
            val einsteinPrediction = M2WeakField.leadingOrderDeflection(M, b)
            val twoPnPrediction = M2WeakField.secondOrderDeflectionImpactParameter(M, b)

            val relErrLeading = abs(numericalDeflection - einsteinPrediction) / einsteinPrediction
            val relErr2PN = abs(numericalDeflection - twoPnPrediction) / twoPnPrediction

            relativeErrorsLeading.add(relErrLeading)
            relativeErrors2PN.add(relErr2PN)

            // At large b, deflection must agree with Einstein's formula within a few percent
            assertTrue("Leading order error at b=$b must be < 10%", relErrLeading < 0.10)
            if (b >= 100.0) {
                assertTrue("Leading order error at b=$b must be < 5%", relErrLeading < 0.05)
                assertTrue("2PN error at b=$b must be < 2%", relErr2PN < 0.02)
            }
        }

        // Monotonic convergence: error decreases as b increases
        assertTrue(
            "Error at b=100M must be smaller than b=50M",
            relativeErrorsLeading[1] < relativeErrorsLeading[0]
        )
        assertTrue(
            "Error at b=200M must be smaller than b=100M",
            relativeErrorsLeading[2] < relativeErrorsLeading[1]
        )
    }
}
