package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildCoordinates
import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Validates Requirement 6: Integrator convergence.
 *
 * Fixed-step RK4 errors are measured against a high-resolution reference solution (h_ref = 0.1/16)
 * of the same initial value problem, separately for escaping, turning and captured rays (a = 0.8,
 * backward-traced camera convention p_0 = +1). For a 4th-order method halving h reduces the error by
 * ~16. Measured error ratios err(0.4)/err(0.2) and err(0.2)/err(0.1):
 *   escaping b = 8        : 15.88, 15.95
 *   turning  b = 6        : 15.81, 15.92
 *   captured b = 3        : 14.47, 15.43 (integrated only up to λ = 18.8, where the reference is at
 *                           r = 2.646, just outside the capture-refinement boundary r_capture + 0.95 = 2.60)
 *
 * Diagnosis of the former 2.05 ratio: the previous test ray (start (5, 3, 0), direction (-0.8, -0.2, 0),
 * λ = 4, a = 0) plunges: the reference solution is at r = 2.0044 at λ = 4 (r+ = 2), where the ingoing
 * Kerr-Schild covariant momentum of a past-directed ray diverges (|p| = 311 at λ = 4 for h = 0.1). The
 * solution is not smooth on the step scale there, so RK4 is far from its asymptotic regime: errors
 * against the reference are 0.898, 0.425, 0.195 (ratios 2.11, 2.18, order ~1.1). Captured rays are
 * therefore only tested up to the capture boundary. At the capture threshold itself (r = 2.05, a = 0)
 * the measured ratios are 4.88 and 7.74 for the same reason. The production integrator is unchanged.
 */
class IntegratorConvergenceTest {

    private fun integrateFixedLambda(
        integrator: KerrPhotonIntegrator,
        initialState: PhotonState4D,
        totalLambda: Double,
        h: Double
    ): DoubleArray {
        val steps = (totalLambda / h + 0.5).toInt()
        var st = doubleArrayOf(
            initialState.x,
            initialState.y,
            initialState.z,
            initialState.p_x,
            initialState.p_y,
            initialState.p_z
        )

        for (i in 0 until steps) {
            st = integrator.rk4Step(st, h, initialState.p_t)
        }
        return st
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Returns the errors of h = 0.4, 0.2, 0.1 against the h = 0.1/16 reference and the reference end radius. */
    private fun errorsAgainstReference(
        spacetime: KerrSchildSpacetime,
        start: DoubleArray,
        totalLambda: Double
    ): Pair<DoubleArray, Double> {
        val integrator = KerrPhotonIntegrator(spacetime)
        val initial = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = start[0], Y = start[1], Z = start[2],
            dx = 1.0, dy = 0.0, dz = 0.0
        )
        val reference = integrateFixedLambda(integrator, initial, totalLambda, 0.1 / 16.0)
        val errors = doubleArrayOf(0.4, 0.2, 0.1).map { h ->
            distance(integrateFixedLambda(integrator, initial, totalLambda, h), reference)
        }.toDoubleArray()
        val rEnd = KerrSchildCoordinates.computeR(spacetime.a, reference[0], reference[1], reference[2])
        return Pair(errors, rEnd)
    }

    private fun assertFourthOrder(label: String, errors: DoubleArray) {
        val ratio1 = errors[0] / errors[1]
        val ratio2 = errors[1] / errors[2]
        println("$label: errors ${errors.toList()}, ratios $ratio1, $ratio2")
        assertTrue("$label: error must decrease with smaller step", errors[2] < errors[1] && errors[1] < errors[0])
        assertTrue("$label: ratio err(0.4)/err(0.2) = $ratio1 must be close to 16", ratio1 in 12.0..18.0)
        assertTrue("$label: ratio err(0.2)/err(0.1) = $ratio2 must be close to 16", ratio2 in 12.0..18.0)
    }

    @Test
    fun rk4ConvergesAtFourthOrderOnEscapingRay() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.8)
        val (errors, _) = errorsAgainstReference(spacetime, doubleArrayOf(-20.0, 8.0, 0.5), 30.0)
        assertFourthOrder("escaping b=8", errors)
    }

    @Test
    fun rk4ConvergesAtFourthOrderOnTurningRay() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.8)
        val (errors, rEnd) = errorsAgainstReference(spacetime, doubleArrayOf(-20.0, 6.0, 0.5), 30.0)
        assertTrue("Turning ray must be moving away again at the end (r_end = $rEnd)", rEnd > 5.0)
        assertFourthOrder("turning b=6", errors)
    }

    @Test
    fun rk4ConvergesAtFourthOrderOnCapturedRayUpToCaptureBoundary() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.8)
        val rCaptureBoundary = spacetime.rPlus + 0.05 + 0.95
        val (errors, rEnd) = errorsAgainstReference(spacetime, doubleArrayOf(-20.0, 3.0, 0.5), 18.8)
        assertTrue(
            "Captured ray must be compared only outside the capture boundary: r_end = $rEnd, boundary = $rCaptureBoundary",
            rEnd > rCaptureBoundary && rEnd < rCaptureBoundary + 0.1
        )
        assertFourthOrder("captured b=3", errors)
    }
}
