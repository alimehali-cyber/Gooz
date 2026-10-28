package com.zig.chal

import com.zig.chal.config.ChalCameraConfig
import com.zig.chal.config.ChalSimulationConfig
import com.zig.chal.physics.ChalKerrMetric
import com.zig.chal.physics.ChalPhysicsConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Physics gates for the Chal engine.
 *
 * The expectations are the textbook Kerr values (Bardeen, Press & Teukolsky 1972) that the
 * reference engine's `src/physics/kerr-metric.ts` also satisfies, so the closed-form Kotlin port and
 * the GPU shader path (which evaluates the same expressions) cannot drift apart unnoticed.
 */
class ChalPhysicsTest {

    private val tolerance = 1e-6

    @Test
    fun schwarzschildHorizonIsTwoMasses() {
        assertEquals(2.0, ChalKerrMetric.calculateEventHorizon(1.0, 0.0), tolerance)
        assertEquals(20.0, ChalKerrMetric.calculateEventHorizon(10.0, 0.0), tolerance)
    }

    @Test
    fun nearExtremalHorizonShrinksTowardOneMass() {
        // r+ = M + sqrt(M^2 - a^2); at a* = 0.999 => 1 + sqrt(0.001999)
        val horizon = ChalKerrMetric.calculateEventHorizon(1.0, 0.999)
        assertEquals(1.0 + kotlin.math.sqrt(0.001999), horizon, 1e-9)
        assertTrue("spin shrinks the horizon", horizon < 1.05)
    }

    @Test
    fun spinIsClampedToTheKerrLimit() {
        // a* = 1 is clamped, so the horizon is exactly M rather than NaN.
        assertEquals(1.0, ChalKerrMetric.calculateEventHorizon(1.0, 1.0), tolerance)
        assertEquals(1.0, ChalKerrMetric.calculateEventHorizon(1.0, 2.5), tolerance)
    }

    @Test
    fun schwarzschildPhotonSphereIsThreeMasses() {
        assertEquals(3.0, ChalKerrMetric.calculatePhotonSphere(1.0, 0.0), tolerance)
        assertEquals(30.0, ChalKerrMetric.calculatePhotonSphere(10.0, 0.0), tolerance)
    }

    @Test
    fun photonSphereIsTwoMassesAtExtremalSpin() {
        // r_ph = 2M[1 + cos(2/3 acos(-a*))]; a* -> 1 gives 2M * (1 + cos(2/3 * pi)) = 1M... the
        // prograde ring collapses toward M at extremality.
        val r = ChalKerrMetric.calculatePhotonSphere(1.0, 0.999)
        assertTrue("prograde photon sphere shrinks with spin: $r", r < 3.0 && r > 1.0)
    }

    @Test
    fun schwarzschildIscoIsSixMasses() {
        assertEquals(6.0, ChalKerrMetric.calculateIsco(1.0, 0.0, prograde = true), tolerance)
        assertEquals(6.0, ChalKerrMetric.calculateIsco(1.0, 0.0, prograde = false), tolerance)
    }

    @Test
    fun iscoMatchesBardeenPressTeukolskyValues() {
        // Exact Bardeen-Press-Teukolsky (1972) values: a* = 0.5 -> 4.2330 M prograde / 7.5546 M retrograde,
        // a* = 0.9 -> 2.3209 M prograde / 8.7174 M retrograde (cross-checked against the reference
        // engine's `calculateISCO` in `src/physics/kerr-metric.ts`).
        assertEquals(4.2330, ChalKerrMetric.calculateIsco(1.0, 0.5, prograde = true), 5e-3)
        assertEquals(7.5546, ChalKerrMetric.calculateIsco(1.0, 0.5, prograde = false), 5e-3)
        assertEquals(2.3209, ChalKerrMetric.calculateIsco(1.0, 0.9, prograde = true), 5e-3)
        assertEquals(8.7174, ChalKerrMetric.calculateIsco(1.0, 0.9, prograde = false), 5e-3)
    }

    @Test
    fun progradeIscoIsInsideRetrogradeIsco() {
        for (spin in listOf(-0.9, -0.5, -0.1, 0.1, 0.5, 0.99)) {
            val prograde = ChalKerrMetric.calculateIsco(1.0, spin, prograde = true)
            val retrograde = ChalKerrMetric.calculateIsco(1.0, spin, prograde = false)
            assertTrue("spin $spin: prograde $prograde < retrograde $retrograde", prograde < retrograde)
        }
    }

    @Test
    fun iscoScalesLinearlyWithMass() {
        assertEquals(
            ChalKerrMetric.calculateIsco(1.0, 0.7, prograde = true) * 3.0,
            ChalKerrMetric.calculateIsco(3.0, 0.7, prograde = true),
            1e-9
        )
    }

    @Test
    fun timeDilationIsZeroAtTheHorizonAndUnityAtInfinity() {
        assertEquals(0.0, ChalKerrMetric.calculateTimeDilation(2.0, 1.0), tolerance)
        assertEquals(0.0, ChalKerrMetric.calculateTimeDilation(1.0, 1.0), tolerance)
        assertEquals(1.0, ChalKerrMetric.calculateTimeDilation(1e9, 1.0), 1e-4)
    }

    @Test
    fun timeDilationMatchesSchwarzschildFormula() {
        // sqrt(1 - 2M/r) at r = 10M, M = 1.
        assertEquals(kotlin.math.sqrt(1.0 - 2.0 / 10.0), ChalKerrMetric.calculateTimeDilation(10.0, 1.0), 1e-12)
    }

    @Test
    fun physicsConstantsMatchTheReferenceConfiguration() {
        assertEquals(1.0, ChalPhysicsConstants.SPEED_OF_LIGHT, 0.0)
        assertEquals(1.0, ChalPhysicsConstants.GRAVITATIONAL_CONSTANT, 0.0)
        assertEquals(10000.0, ChalPhysicsConstants.RayMarching.MAX_DISTANCE, 0.0)
        assertEquals(0.01, ChalPhysicsConstants.RayMarching.MIN_STEP, 0.0)
        assertEquals(1.2, ChalPhysicsConstants.RayMarching.MAX_STEP, 0.0)
        assertEquals(1.15, ChalPhysicsConstants.RayMarching.HORIZON_THRESHOLD, 0.0)
        assertEquals(0.45, ChalPhysicsConstants.Accretion.DISK_HEIGHT_MULTIPLIER, 0.0)
        assertEquals(0.75, ChalPhysicsConstants.Accretion.TURBULENCE_SCALE, 0.0)
        assertEquals(2.5, ChalPhysicsConstants.Accretion.TURBULENCE_DETAIL, 0.0)
        assertEquals(0.12, ChalPhysicsConstants.Accretion.TIME_SCALE, 0.0)
        assertEquals(0.25, ChalPhysicsConstants.Accretion.DENSITY_FALLOFF, 0.0)
        assertEquals(2.0, ChalPhysicsConstants.Gravity.FRAME_DRAGGING_STRENGTH, 0.0)
        assertEquals(2.0, ChalPhysicsConstants.SCHWARZSCHILD_RADIUS_SOLAR, 0.0)
    }

    @Test
    fun simulationParameterRangesMatchTheReferenceSchema() {
        assertEquals(1.0, ChalSimulationConfig.MASS.default, 0.0)
        assertEquals(20.0, ChalSimulationConfig.MASS.max, 0.0)
        assertEquals(0.5, ChalSimulationConfig.SPIN.default, 0.0)
        assertEquals(0.0, ChalSimulationConfig.SPIN.min, 0.0)
        assertEquals(0.99, ChalSimulationConfig.SPIN.max, 0.0)
        assertEquals(100.0, ChalSimulationConfig.ZOOM.default, 0.0)
        assertEquals(9500.0, ChalSimulationConfig.DISK_TEMP.default, 0.0)
        assertEquals(0.7, ChalSimulationConfig.LENSING.default, 0.0)
        assertEquals(0.2, ChalSimulationConfig.DISK_SCALE_HEIGHT.default, 0.0)
    }

    @Test
    fun initialZoomSolveStaysInsideTheCameraLimits() {
        val zoom = ChalCameraConfig.calculateInitialZoom(1.0, 1080.0, 2340.0)
        assertTrue(
            "initial zoom $zoom must respect the camera bounds",
            zoom >= ChalCameraConfig.MIN_ZOOM && zoom <= ChalCameraConfig.MAX_ZOOM
        )
        // Degenerate viewports fall back to the configured default.
        assertEquals(ChalCameraConfig.DEFAULT_ZOOM, ChalCameraConfig.calculateInitialZoom(0.0, 0.0, 0.0), 0.0)
        assertEquals(ChalCameraConfig.DEFAULT_ZOOM, ChalCameraConfig.calculateInitialZoom(1.0, 0.0, 100.0), 0.0)
    }

    @Test
    fun defaultVerticalAngleIsTheConfiguredTilt() {
        assertEquals(97.02 * Math.PI / 180.0, ChalCameraConfig.DEFAULT_VERTICAL_ANGLE, 1e-12)
    }
}
