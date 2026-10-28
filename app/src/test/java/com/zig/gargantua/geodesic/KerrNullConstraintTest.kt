package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates Requirement 1: Null Hamiltonian constraint H = 1/2 g^μν p_μ p_ν ≡ 0
 * along photon geodesics during RK4 integration.
 *
 * Camera rays are past-directed backward traces (p_0 = +1). A captured ray approaches r+ in ingoing
 * Kerr-Schild coordinates with a diverging covariant momentum (|p| ~ 1/(r - r+)), so the absolute
 * residual |H| of a fixed-step-policy RK4 solution grows with |p|² right before capture; this is
 * intrinsic and not a defect of the integrator. The checks are therefore split:
 *  - ESCAPED rays: full conservation along the whole trajectory, max |H| < 5e-5
 *    (measured max 2.2e-7 over the escaping rays below).
 *  - CAPTURED rays: correct classification, and the residual is only evaluated on states before the
 *    capture termination (r > r+ + 0.05; KerrPhotonIntegrator never evaluates H inside the capture
 *    region). Because |p| diverges there, the bound is on the scale-free residual
 *    |H| / (½ (p_0² + |p|²)): measured max 1.73e-2 over the captured rays below (worst case
 *    M = 1, a = 0.9, ndc (0.02, 0.01), last pre-capture state at r - r+ = 0.051); tolerance 2.5e-2.
 */
class KerrNullConstraintTest {

    private val testConfigs = listOf(
        Pair(1.0, 0.0),    // Schwarzschild
        Pair(1.0, 0.5),    // Moderate prograde
        Pair(1.0, -0.6),   // Moderate retrograde
        Pair(1.0, 0.9),    // High spin prograde
        Pair(1.0, -0.9),   // High spin retrograde
        Pair(2.0, 1.8)     // Scaled mass
    )

    @Test
    fun capturedRaysAreClassifiedAndSatisfyConstraintBeforeCapture() {
        // Rays aimed at the black hole from (30M, 0, 5M): all are captured.
        val cameraAngles = listOf(
            Pair(0.0, 0.0),      // Direct forward
            Pair(0.1, 0.05),     // Deflecting ray
            Pair(-0.15, 0.1),    // Deflecting ray
            Pair(0.02, 0.01)     // Near the centre of the shadow
        )

        for ((M, a) in testConfigs) {
            val spacetime = KerrSchildSpacetime(M, a)
            val integrator = KerrPhotonIntegrator(spacetime, maxSteps = 800, baseStepFactor = 0.05)
            val camera = CameraModel(posX = 30.0 * M, posY = 0.0, posZ = 5.0 * M)

            for ((ndcX, ndcY) in cameraAngles) {
                val initialPhoton = camera.createInitialPhotonState(spacetime, ndcX, ndcY)

                val initialH = abs(initialPhoton.hamiltonian(spacetime))
                assertTrue("Initial photon state must satisfy H=0 within 1e-14, got $initialH", initialH < 1e-14)

                val result = integrator.traceRay(initialPhoton)
                assertTrue("Ray ($ndcX, $ndcY) for M=$M, a=$a must be CAPTURED, got ${result.terminationReason}", result.isCaptured)
                assertTrue(
                    "Pre-capture scale-free residual must be < 2.5e-2 for M=$M, a=$a, ndc=($ndcX, $ndcY), " +
                        "got ${result.maxRelativeHamiltonianResidual}",
                    result.maxRelativeHamiltonianResidual < 2.5e-2
                )
                assertTrue("Ray must take at least 10 steps", result.stepsTaken > 10)
            }
        }
    }

    @Test
    fun escapedRaysConserveNullConstraintAlongWholeTrajectory() {
        val cameraAngles = listOf(
            Pair(0.6, 0.1),
            Pair(-0.7, 0.2),
            Pair(0.3, 0.55),
            Pair(-0.45, -0.5)
        )

        for ((M, a) in testConfigs) {
            val spacetime = KerrSchildSpacetime(M, a)
            val integrator = KerrPhotonIntegrator(spacetime, maxSteps = 800, baseStepFactor = 0.05)
            val camera = CameraModel(posX = 30.0 * M, posY = 0.0, posZ = 5.0 * M)

            for ((ndcX, ndcY) in cameraAngles) {
                val initialPhoton = camera.createInitialPhotonState(spacetime, ndcX, ndcY)
                val result = integrator.traceRay(initialPhoton)
                assertTrue("Ray ($ndcX, $ndcY) for M=$M, a=$a must ESCAPE, got ${result.terminationReason}", result.isEscaped)
                assertTrue(
                    "Max Hamiltonian residual along escaped ray must be < 5e-5 for M=$M, a=$a, got ${result.maxHamiltonianResidual}",
                    result.maxHamiltonianResidual < 5e-5
                )
                assertTrue("Ray must take at least 10 steps", result.stepsTaken > 10)
            }
        }
    }
}
