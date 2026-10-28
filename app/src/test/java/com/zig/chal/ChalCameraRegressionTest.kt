package com.zig.chal

import com.zig.chal.config.ChalCameraConfig
import com.zig.chal.config.ChalMotion
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.render.ChalCamera
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ChalCameraRegressionTest {
    private class Rig(var params: ChalSimulationParams = ChalSimulationParams()) {
        val camera = ChalCamera().apply { restore(params) }
        fun tick(dt: Double) { camera.update(dt, params) { params = params.it() } }
    }
    @Test fun autoPanSpeedIsIndependentOfFrameRate() {
        for (fps in listOf(30, 45, 60, 120)) {
            val rig = Rig()
            repeat(fps) { rig.tick(1.0 / fps) }
            assertEquals(PI + 0.3, rig.camera.snapshot().theta, 1e-9)
        }
    }
    @Test fun speedReadoutConvertsTheLegacySixtyHzIncrement() {
        assertEquals(0.3, ChalMotion.radiansPerSecond(0.005), 1e-12)
        assertEquals(0.005, ChalMotion.legacyAutoPan(0.3), 1e-12)
    }
    @Test fun shaderTimeKeepsTheOriginalSixtyHzVisualSpeed() {
        for (fps in listOf(30, 45, 60)) {
            assertEquals(0.6, (1..fps).sumOf { 1.0 / fps * ChalMotion.SHADER_TIME_PER_SECOND }, 1e-9)
        }
    }
    @Test fun pausedTourDoesNotAdvanceAndResumesWithoutAJump() {
        val actual = Rig()
        val reference = Rig()
        for (rig in listOf(actual, reference)) rig.camera.startCinematic(ChalCamera.CinematicMode.ORBIT, rig.params, false)
        repeat(20) { actual.tick(1.0 / 60); reference.tick(1.0 / 60) }
        actual.params = actual.params.copy(paused = true)
        val pose = actual.camera.snapshot()
        repeat(10_000) { actual.tick(0.1) }
        assertEquals(pose, actual.camera.snapshot())
        actual.params = actual.params.copy(paused = false)
        repeat(10) { actual.tick(1.0 / 60); reference.tick(1.0 / 60) }
        assertEquals(reference.camera.snapshot().theta, actual.camera.snapshot().theta, 1e-10)
        assertEquals(reference.params.zoom, actual.params.zoom, 1e-10)
    }
    @Test fun savedTourRetainsItsPhaseAndUnwrappedAzimuth() {
        val original = Rig()
        original.camera.startCinematic(ChalCamera.CinematicMode.ORBIT, original.params, false)
        repeat(3_000) { original.tick(1.0 / 60) }
        val restored = Rig(original.params)
        restored.camera.restoreSession(original.camera.saveSession(), restored.params)
        assertEquals(original.camera.snapshot(), restored.camera.snapshot())
        repeat(30) { original.tick(1.0 / 60); restored.tick(1.0 / 60) }
        assertEquals(original.camera.snapshot(), restored.camera.snapshot())
        assertEquals(original.params.zoom, restored.params.zoom, 1e-10)
    }
    @Test fun reducedMotionStopsDecorativeMovementButAllowsManualRotation() {
        val rig = Rig(ChalSimulationParams(reducedMotion = true))
        rig.camera.startCinematic(ChalCamera.CinematicMode.ORBIT, rig.params, false)
        assertFalse(rig.camera.isCinematic)
        repeat(60) { rig.tick(1.0 / 60) }
        assertEquals(PI, rig.camera.snapshot().theta, 1e-10)
        rig.camera.onPointerDown(0.0, 0.0)
        rig.camera.onPointerMove(20.0, 10.0)
        assertTrue(rig.camera.snapshot().theta > PI)
    }
    @Test fun aSecondPointerAlsoSuppressesAutoPan() {
        val rig = Rig()
        rig.camera.setTouchCount(2)
        repeat(60) { rig.tick(1.0 / 60) }
        assertEquals(PI, rig.camera.snapshot().theta, 1e-10)
    }
    @Test fun pausedDragStillClampsThePolarAngle() {
        val rig = Rig(ChalSimulationParams(paused = true))
        rig.camera.onPointerDown(0.0, 0.0)
        rig.camera.onPointerMove(0.0, 10_000.0)
        rig.tick(1.0 / 60)
        assertTrue(rig.camera.snapshot().phi < PI)
        assertTrue(rig.camera.snapshot().phi > 0.0)
    }
    @Test fun scenariosResetPitchWithoutResettingYaw() {
        val rig = Rig(ChalSimulationParams(cameraYaw = 0.7, verticalAngle = 30.0))
        rig.camera.resetScenarioPitch()
        assertEquals(0.7 * 2 * PI, rig.camera.snapshot().theta, 1e-10)
        assertEquals(ChalCameraConfig.DEFAULT_VERTICAL_ANGLE, rig.camera.snapshot().phi, 1e-10)
    }
    @Test fun stoppingRecoveryReallyStopsTheDirector() {
        val rig = Rig(ChalSimulationParams(autoSpin = 0.013))
        rig.camera.startCinematic(ChalCamera.CinematicMode.ORBIT, rig.params, false)
        repeat(1_201) { rig.tick(0.1) }
        assertTrue(rig.camera.isRecovering)
        assertEquals(0.013, rig.camera.stopCinematic()!!, 1e-10)
        assertFalse(rig.camera.isCinematic)
        assertFalse(rig.camera.isRecovering)
    }
    @Test fun resetViewDoesNotChangePhysicalOrFeatureSettings() {
        val rig = Rig(ChalSimulationParams(mass = 10.0, spin = 0.9, paused = true, zoom = 30.0))
        val features = rig.params.features
        rig.camera.reset { rig.params = rig.params.it() }
        assertEquals(10.0, rig.params.mass, 0.0)
        assertEquals(0.9, rig.params.spin, 0.0)
        assertEquals(features, rig.params.features)
        assertEquals(100.0, rig.params.zoom, 0.0)
        assertFalse(rig.params.paused)
    }
    @Test fun inertiaTravelsTheSameDistanceAtThirtyAndSixtyFrames() {
        val slow = Rig(ChalSimulationParams(autoSpin = 0.0))
        val fast = Rig(ChalSimulationParams(autoSpin = 0.0))
        for (rig in listOf(slow, fast)) {
            rig.camera.onPointerDown(0.0, 0.0)
            rig.camera.onPointerMove(100.0, 0.0)
            rig.camera.onPointerUp()
        }
        repeat(15) { slow.tick(1.0 / 30) }
        repeat(30) { fast.tick(1.0 / 60) }
        assertEquals(fast.camera.snapshot().theta, slow.camera.snapshot().theta, 1e-9)
        assertEquals(fast.camera.snapshot().thetaVelocity, slow.camera.snapshot().thetaVelocity, 1e-9)
    }
    @Test fun reducedMotionDoesNotContinueAnInertialThrow() {
        val rig = Rig(ChalSimulationParams(reducedMotion = true))
        rig.camera.onPointerDown(0.0, 0.0)
        rig.camera.onPointerMove(20.0, 0.0)
        rig.camera.onPointerUp()
        val theta = rig.camera.snapshot().theta
        repeat(30) { rig.tick(1.0 / 30) }
        assertEquals(theta, rig.camera.snapshot().theta, 1e-9)
    }

}
