package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Validates Requirement 2: Production adaptive integrator verification.
 *
 * Demonstrates the essential properties of the production adaptive policy of gargantua_geodesic.frag
 * (KerrPhotonIntegrator defaults):
 *   Δλ(r) = clamp(0.085 r, 0.035, 0.55) if r > 6 and (moving outward or r > 18), else clamp(0.085 r, 0.035, 0.32),
 *   limited to mix(0.032, 0.075, (r - r_capture)/0.95) inside the capture zone:
 * 1. Step sizes scale with radius: smaller in strong-field / photon sphere, larger asymptotically.
 * 2. Strict numerical stability through high-curvature regions (|H| bounded, zero NaNs).
 * 3. Monotonic convergence under adaptive parameter refinement (κ -> κ/2 -> κ/4).
 *
 * Note: Fixed-step RK4 convergence is validated independently in [IntegratorConvergenceTest].
 */
class ProductionAdaptiveIntegratorTest {

    @Test
    fun adaptiveStepIsSmallerInStrongFieldAndLargerFartherOut() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)
        val integrator = KerrPhotonIntegrator(
            spacetime = spacetime,
            escapeRadius = 45.0,
            maxSteps = 800
        )

        // Deflecting ray with impact parameter b = 6.5M starting at X = -35M
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -35.0, Y = 6.5, Z = 0.0,
            dx = 1.0, dy = 0.0, dz = 0.0
        )

        val result = integrator.traceRay(initialPhoton, recordPath = true)
        assertTrue("Ray must escape to background", result.isEscaped)
        assertNotNull("Path must be recorded", result.path)
        assertNotNull("Step sizes must be recorded", result.stepSizes)

        val steps = result.stepSizes!!
        val path = result.path!!
        assertTrue("Path must have multiple steps", steps.size > 20)

        // Find step near closest approach (strong field) and step at start (weak field)
        val initialStep = steps.first()
        val minStep = result.minStepSizeTaken
        val maxStep = result.maxStepSizeTaken

        assertTrue("Step size in strong field must be strictly smaller than maximum step", minStep < maxStep)
        assertTrue("Initial weak-field step must be strictly larger than periastron step", initialStep > minStep)

        // Verify that every recorded step strictly followed the shader's adaptive policy
        val rCapture = spacetime.rPlus + 0.05
        var previousR = com.zig.gargantua.physics.KerrSchildCoordinates.computeR(spacetime.a, path[0].x, path[0].y, path[0].z)
        var movingOutward = false
        for (i in 0 until steps.size) {
            val pt = path[i]
            val r = com.zig.gargantua.physics.KerrSchildCoordinates.computeR(spacetime.a, pt.x, pt.y, pt.z)
            if (r > previousR) movingOutward = true
            previousR = r
            var expectedStep = if (r > 6.0 && (movingOutward || r > 18.0)) (0.085 * r).coerceIn(0.035, 0.55)
            else (0.085 * r).coerceIn(0.035, 0.32)
            if (r > rCapture && r < rCapture + 0.95) {
                expectedStep = kotlin.math.min(expectedStep, 0.032 + (0.075 - 0.032) * (r - rCapture) / 0.95)
            }
            val actualStep = steps[i]
            assertEquals(
                "Step at r=$r ($actualStep) must follow the shader step policy",
                expectedStep,
                actualStep,
                1e-12
            )
        }
    }

    /**
     * High-spin (a = -0.92) ray with b = 5.2 from X = -40M. A reference integration with a 17x smaller
     * step factor (0.005, steps in [0.0005, 0.05]) captures this ray (minR 1.403, r_capture 1.442), so the
     * physically correct classification is CAPTURED. The production policy captures it too (104 steps).
     * The Hamiltonian is checked only before capture, as a scale-free residual (|p| diverges near r+):
     * measured 1.09e-2, tolerance 2.5e-2 (the bound established in [KerrNullConstraintTest]).
     * Note: the previous non-production policy (0.05 r clamped to [0.01, 0.35]) grazed r = 1.451 and
     * bounced out numerically ("escaped", scale-free residual 0.98); that was a misclassification, not
     * a physics result, and is why this test no longer uses it.
     */
    @Test
    fun adaptiveIntegratorRemainsNumericallyStableThroughStrongCurvature() {
        val M = 1.0
        // Test high spin retrograde Kerr black hole
        val spacetime = KerrSchildSpacetime(M = M, a = -0.92)
        val integrator = KerrPhotonIntegrator(
            spacetime = spacetime,
            escapeRadius = 50.0,
            maxSteps = 800
        )

        // Near-critical impact parameter skimming very close to photon sphere
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -40.0, Y = 5.2, Z = 0.5,
            dx = 1.0, dy = 0.0, dz = 0.0
        )

        val result = integrator.traceRay(initialPhoton, recordPath = true)

        // Assert numerical stability:
        // 1. Min radius reached deep in strong field (r < 5M)
        assertTrue("Ray must penetrate into strong field, got ${result.minRadiusReached}", result.minRadiusReached < 5.0)

        // 2. Physical classification (reference integration: CAPTURED)
        assertTrue("Ray must be CAPTURED, got ${result.terminationReason}", result.isCaptured)

        // 3. Pre-capture Hamiltonian residual bounded (scale-free, see KerrNullConstraintTest)
        assertTrue(
            "Pre-capture scale-free Hamiltonian residual must remain < 2.5e-2, got ${result.maxRelativeHamiltonianResidual}",
            result.maxRelativeHamiltonianResidual < 2.5e-2
        )

        // 4. No NaNs or infinities anywhere in path
        result.path?.forEach { state ->
            assertFalse("x must be finite", state.x.isNaN() || state.x.isInfinite())
            assertFalse("y must be finite", state.y.isNaN() || state.y.isInfinite())
            assertFalse("z must be finite", state.z.isNaN() || state.z.isInfinite())
            assertFalse("px must be finite", state.p_x.isNaN() || state.p_x.isInfinite())
            assertFalse("py must be finite", state.p_y.isNaN() || state.p_y.isInfinite())
            assertFalse("pz must be finite", state.p_z.isNaN() || state.p_z.isInfinite())
        }
    }

    @Test
    fun productionAdaptiveIntegratorDemonstratesMonotonicConvergenceWithParameterRefinement() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.7)

        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -30.0, Y = 6.5, Z = 2.0,
            dx = 1.0, dy = 0.0, dz = 0.0
        )

        // Production policy and two successively halved copies (factor, min, inner max and outer max all
        // halved). Measured: err12 = 0.287, err23 = 0.0298.
        val int1 = KerrPhotonIntegrator(spacetime, escapeRadius = 40.0, maxSteps = 1500) // Production baseline
        val int2 = KerrPhotonIntegrator(spacetime, baseStepFactor = 0.0425, minStepSize = 0.0175, maxStepSize = 0.16, maxOuterStepSize = 0.275, escapeRadius = 40.0, maxSteps = 3000)
        val int3 = KerrPhotonIntegrator(spacetime, baseStepFactor = 0.02125, minStepSize = 0.00875, maxStepSize = 0.08, maxOuterStepSize = 0.1375, escapeRadius = 40.0, maxSteps = 6000)

        val res1 = int1.traceRay(initialPhoton)
        val res2 = int2.traceRay(initialPhoton)
        val res3 = int3.traceRay(initialPhoton)

        assertTrue("All runs must escape", res1.isEscaped && res2.isEscaped && res3.isEscaped)

        // Compare final spatial coordinates upon reaching escape boundary
        fun dist(s1: PhotonState4D, s2: PhotonState4D): Double {
            val dx = s1.x - s2.x
            val dy = s1.y - s2.y
            val dz = s1.z - s2.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        val err12 = dist(res1.finalState, res2.finalState)
        val err23 = dist(res2.finalState, res3.finalState)

        // Tightening adaptive parameters must monotonically reduce trajectory error: err23 < err12
        assertTrue(
            "Adaptive refinement must produce monotonic convergence: err23 ($err23) < err12 ($err12)",
            err23 < err12
        )
    }
}
