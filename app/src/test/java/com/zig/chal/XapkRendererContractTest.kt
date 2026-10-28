package com.zig.chal

import com.zig.chal.config.ChalFeatureToggles
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.config.ChalXapkScenario
import com.zig.chal.config.XapkCameraState
import com.zig.chal.config.XapkRendererContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XapkRendererContractTest {
    @Test
    fun nativeParameterBlockKeepsXapkFieldOrderAndNormalizedSpin() {
        val params = xapkDefaults().copy(mass = 10.0, spin = 0.5)
        val block = XapkRendererContract.build(params)

        assertEquals(16, block.floats.size)
        assertEquals(4, block.integers.size)
        assertEquals(10.0f, block.floats[0], 0.0f)
        assertEquals(0.5f, block.floats[1], 0.0f)
        assertEquals(0.7f, block.floats[2], 1e-6f)
        assertEquals(2.0f, block.floats[3], 0.0f)
        assertEquals(4.0f, block.floats[4], 0.0f)
        assertEquals(9_500.0f, block.floats[5], 0.0f)
        assertEquals(50.0f, block.floats[6], 0.0f)
        assertEquals(0.2f, block.floats[7], 1e-6f)
        assertEquals(1.0f, block.floats[8], 0.0f)
        assertEquals(0.45f, block.floats[9], 1e-6f)
        assertEquals(0.005f, block.floats[10], 1e-7f)
        assertEquals(0.5f, block.floats[11], 0.0f)
        assertEquals(0.539f, block.floats[12], 1e-6f)
        assertEquals(100.0f, block.floats[13], 0.0f)
        assertEquals(1.0f, block.floats[14], 0.0f)
        assertEquals(0.0f, block.floats[15], 0.0f)

        // This is the documented seven-bit XAPK mask, without Chal-only toggles.
        assertEquals(128, block.integers[0])
        assertEquals(31, block.integers[1])
        assertEquals(1, block.integers[2])
        assertEquals(1, block.integers[3])
    }

    @Test
    fun qualityPresetsMatchXapkStepsFlagsAndRenderScales() {
        val expected = listOf(
            ChalRayTracingQuality.LOW to Triple(32, 0.5f, 0 to 0),
            ChalRayTracingQuality.MEDIUM to Triple(64, 0.75f, 1 to 0),
            ChalRayTracingQuality.HIGH to Triple(128, 1.0f, 1 to 1),
            ChalRayTracingQuality.ULTRA to Triple(256, 1.0f, 1 to 1)
        )
        expected.forEach { (quality, values) ->
            val block = XapkRendererContract.build(
                xapkDefaults().copy(features = xapkDefaults().features.copy(rayTracingQuality = quality))
            )
            assertEquals(values.first, block.integers[0])
            assertEquals(values.second, block.floats[14], 0.0f)
            assertEquals(values.third.first, block.integers[2])
            assertEquals(values.third.second, block.integers[3])
        }
    }

    @Test
    fun xapkScenariosRestoreTheirPhysicalValuesAndCameraDistance() {
        val stellar = ChalXapkScenario.STELLAR.applyTo(xapkDefaults())
        assertEquals(10.0, stellar.mass, 0.0)
        assertEquals(0.5, stellar.spin, 0.0)
        assertEquals(80.0, stellar.diskSize, 0.0)
        assertEquals(80.0, stellar.zoom, 0.0)
        assertEquals(97.02, stellar.verticalAngle, 1e-10)
        assertEquals(1.0, stellar.bloomThreshold, 0.0)
        assertEquals(ChalRayTracingQuality.HIGH, stellar.features.rayTracingQuality)

        val sgrA = ChalXapkScenario.SGR_A_PROXY.applyTo(xapkDefaults())
        assertEquals(4.0, sgrA.mass, 0.0)
        assertEquals(0.94, sgrA.spin, 0.0)
        assertEquals(0.9, sgrA.lensing, 1e-10)
        assertEquals(60.0, sgrA.zoom, 0.0)
        assertEquals(8_000.0, sgrA.diskTemp, 0.0)
        assertEquals(ChalRayTracingQuality.HIGH, sgrA.features.rayTracingQuality)

        val maximal = ChalXapkScenario.MAXIMAL_SPIN.applyTo(xapkDefaults())
        assertEquals(2.0, maximal.mass, 0.0)
        assertEquals(0.99, maximal.spin, 0.0)
        assertEquals(30.0, maximal.zoom, 0.0)
        assertEquals(20_000.0, maximal.diskTemp, 0.0)
        assertEquals(ChalRayTracingQuality.ULTRA, maximal.features.rayTracingQuality)
        assertTrue(ChalXapkScenario.MAXIMAL_SPIN.matches(maximal))

        val schwarzschild = ChalXapkScenario.SCHWARZSCHILD.applyTo(xapkDefaults())
        assertEquals(1.0, schwarzschild.mass, 0.0)
        assertEquals(0.0, schwarzschild.spin, 0.0)
        assertEquals(30.0, schwarzschild.zoom, 0.0)
        assertEquals(9_500.0, schwarzschild.diskTemp, 0.0)
        assertEquals(ChalRayTracingQuality.HIGH, schwarzschild.features.rayTracingQuality)
    }

    @Test
    fun normalizedXapkPitchMapsToTheChalPolarAngleWithoutUnitDrift() {
        assertEquals(97.02, XapkCameraState.DEFAULT_POLAR_ANGLE_DEGREES, 1e-10)
        assertEquals(XapkCameraState.DEFAULT_PITCH, (XapkCameraState.DEFAULT_POLAR_ANGLE_DEGREES / 180.0).toFloat(), 1e-6f)
    }

    @Test
    fun cameraFieldsUseXapkRangesAndFeatureBits() {
        val block = XapkRendererContract.build(
            xapkDefaults().copy(
                features = xapkDefaults().features.copy(
                    gravitationalLensing = false,
                    accretionDisk = false,
                    dopplerBeaming = false,
                    photonSphereGlow = false,
                    backgroundStars = false,
                    relativisticJets = true,
                    gravitationalRedshift = true
                )
            ),
            camera = XapkCameraState(yaw = -0.25f, pitch = 1.0f, distance = 0.5f)
        )

        assertEquals(0.75f, block.floats[11], 1e-6f)
        assertEquals(XapkCameraState.MAX_PITCH, block.floats[12], 0.0f)
        assertEquals(XapkCameraState.MIN_DISTANCE, block.floats[13], 0.0f)
        assertEquals(XapkRendererContract.FEATURE_JETS or XapkRendererContract.FEATURE_REDSHIFT, block.integers[1])
        assertFalse(block.floats[1] < 0.0f)
        assertTrue(block.floats[12] <= XapkCameraState.MAX_PITCH)
    }

    private fun xapkDefaults(): ChalSimulationParams {
        val features = ChalFeatureToggles(
            gravitationalLensing = true,
            rayTracingQuality = ChalRayTracingQuality.HIGH,
            accretionDisk = true,
            dopplerBeaming = true,
            backgroundStars = true,
            photonSphereGlow = true,
            bloom = true,
            relativisticJets = false,
            gravitationalRedshift = false,
            kerrShadow = false,
            spacetimeVisualization = false
        )
        return ChalSimulationParams(
            mass = 1.0,
            spin = 0.5,
            diskDensity = 4.0,
            diskTemp = 9_500.0,
            lensing = 0.7,
            zoom = 100.0,
            autoSpin = 0.005,
            diskSize = 50.0,
            diskScaleHeight = 0.2,
            frameDraggingStrength = 2.0,
            bloomThreshold = 1.0,
            bloomIntensity = 0.45,
            renderScale = 1.0,
            features = features
        )
    }
}
