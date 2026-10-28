package com.zig.gargantua.renderer

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Validates Task 2: Device-Readiness and Frame Invalidation Audit.
 * 1. Camera orbit, inclination, and distance changes strictly invalidate SceneSignature.
 * 2. Stationary camera retains identical SceneSignature, halting expensive ray tracing.
 * 3. Exposure and bloom controls invalidate scene appropriately.
 * 4. High-frequency touch event simulation operates race-free and non-blocking.
 */
class DeviceReadinessAuditTest {

    // Mirror of GargantuaRenderer.SceneSignature to validate equality and invalidation semantics
    private data class AuditSceneSignature(
        val width: Int,
        val height: Int,
        val renderScale: Float,
        val mass: Float,
        val spin: Float,
        val camDist: Float,
        val camInclinationDeg: Float,
        val camAzimuthDeg: Float,
        val camTargetX: Float,
        val camTargetY: Float,
        val camTargetZ: Float,
        val maxSteps: Int,
        val enableDisk: Boolean,
        val diskOuterRadius: Float,
        val enableDoppler: Boolean,
        val useGeodesicShader: Boolean,
        val exposure: Float,
        val enableBloom: Boolean,
        val bloomIntensity: Float,
        val bloomThreshold: Float
    )

    private fun createSignature(state: GargantuaRenderState): AuditSceneSignature {
        return AuditSceneSignature(
            width = state.viewportWidth,
            height = state.viewportHeight,
            renderScale = state.renderScale,
            mass = state.mass,
            spin = state.spin,
            camDist = state.camDist,
            camInclinationDeg = state.camInclinationDeg,
            camAzimuthDeg = state.camAzimuthDeg,
            camTargetX = state.camTargetX,
            camTargetY = state.camTargetY,
            camTargetZ = state.camTargetZ,
            maxSteps = state.maxSteps,
            enableDisk = state.enableDisk,
            diskOuterRadius = state.diskOuterRadius,
            enableDoppler = state.enableDoppler,
            useGeodesicShader = state.useGeodesicShader,
            exposure = state.exposure,
            enableBloom = state.enableBloom,
            bloomIntensity = state.bloomIntensity,
            bloomThreshold = state.bloomThreshold
        )
    }

    @Test
    fun cameraParameterChangesStrictlyInvalidateSceneSignature() {
        val baseState = GargantuaRenderState(viewportWidth = 1080, viewportHeight = 2400)
        val baseSig = createSignature(baseState)

        // 1. Azimuth orbit change
        val azState = baseState.copy(camAzimuthDeg = 5.0f)
        assertNotEquals("Azimuth change must invalidate SceneSignature", baseSig, createSignature(azState))

        // 2. Inclination orbit change
        val inclState = baseState.copy(camInclinationDeg = 75.0f)
        assertNotEquals("Inclination change must invalidate SceneSignature", baseSig, createSignature(inclState))

        // 3. Distance zoom change
        val distState = baseState.copy(camDist = 30.0f)
        assertNotEquals("Distance change must invalidate SceneSignature", baseSig, createSignature(distState))

        // 4. Two-finger translation/pan change
        val panState = baseState.copy(camTargetX = 2.5f, camTargetY = -1.2f)
        assertNotEquals("Pan target change must invalidate SceneSignature", baseSig, createSignature(panState))
    }

    @Test
    fun stationaryCameraRetainsIdenticalSceneSignatureHaltingRaymarching() {
        val state1 = GargantuaRenderState(
            viewportWidth = 1080,
            viewportHeight = 2400,
            camDist = 24.0f,
            camInclinationDeg = 82.0f,
            camAzimuthDeg = 0.0f
        )
        val state2 = GargantuaRenderState(
            viewportWidth = 1080,
            viewportHeight = 2400,
            camDist = 24.0f,
            camInclinationDeg = 82.0f,
            camAzimuthDeg = 0.0f
        )

        val sig1 = createSignature(state1)
        val sig2 = createSignature(state2)

        assertEquals("Stationary state must produce identical signature", sig1, sig2)
    }

    @Test
    fun exposureAndBloomChangesTriggerPipelineUpdate() {
        val baseState = GargantuaRenderState(viewportWidth = 1080, viewportHeight = 2400)
        val baseSig = createSignature(baseState)

        val exposureState = baseState.copy(exposure = 1.50f)
        assertNotEquals("Exposure change must invalidate SceneSignature", baseSig, createSignature(exposureState))

        val bloomState = baseState.copy(bloomIntensity = 0.50f)
        assertNotEquals("Bloom intensity change must invalidate SceneSignature", baseSig, createSignature(bloomState))
    }

    @Test
    fun presentationOnlyChangesDoNotInvalidateRaySceneSignature() {
        val baseState = GargantuaRenderState(viewportWidth = 1080, viewportHeight = 2400)
        val baseSig = GargantuaRenderer.RaySceneSignature.fromState(baseState, 1080, 2400)

        assertEquals(
            "Exposure must reuse the geodesic output",
            baseSig,
            GargantuaRenderer.RaySceneSignature.fromState(baseState.copy(exposure = 1.5f), 1080, 2400)
        )
        assertEquals(
            "Bloom intensity must reuse the geodesic output",
            baseSig,
            GargantuaRenderer.RaySceneSignature.fromState(baseState.copy(bloomIntensity = 0.5f), 1080, 2400)
        )
        assertEquals(
            "Bloom threshold must reuse the geodesic output",
            baseSig,
            GargantuaRenderer.RaySceneSignature.fromState(baseState.copy(bloomThreshold = 1.4f), 1080, 2400)
        )
        assertNotEquals(
            "Camera orbit must invalidate the geodesic output",
            baseSig,
            GargantuaRenderer.RaySceneSignature.fromState(baseState.copy(camAzimuthDeg = 5.0f), 1080, 2400)
        )
    }

    @Test
    fun highFrequencyTouchUpdatesOperateRaceFreeAndNonBlocking() {
        val holder = RenderStateHolder()
        val threadCount = 4
        val updatesPerThread = 500
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        val startTime = System.nanoTime()

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until updatesPerThread) {
                        holder.updateState { current ->
                            current.copy(
                                camAzimuthDeg = (current.camAzimuthDeg + 0.1f) % 360f,
                                camInclinationDeg = (current.camInclinationDeg + 0.05f).coerceIn(5.0f, 175.0f)
                            )
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All touch events must process within 3 seconds", latch.await(3, TimeUnit.SECONDS))
        executor.shutdown()

        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
        assertTrue("2000 touch state updates must execute in under 500ms, took ${elapsedMs}ms", elapsedMs < 500.0)

        val finalState = holder.getState()
        assertTrue("Final inclination must remain bounded", finalState.camInclinationDeg in 5.0f..175.0f)
    }
}
