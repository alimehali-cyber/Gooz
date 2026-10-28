package com.zig.gargantua

import com.zig.gargantua.renderer.GargantuaAnimation
import com.zig.gargantua.renderer.GargantuaRenderState
import com.zig.gargantua.renderer.GargantuaRenderer
import com.zig.gargantua.renderer.GargantuaTelemetry
import com.zig.gargantua.renderer.RenderStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure JVM unit tests for Gargantua render state, atomic snapshot transfer,
 * and thread-safe telemetry emission.
 */
class GargantuaRenderStateTest {

    @Test
    fun defaultStateHasExpectedInitialValues() {
        val state = GargantuaRenderState()
        assertEquals(0, state.viewportWidth)
        assertEquals(0, state.viewportHeight)
        assertEquals(0.5f, state.renderScale, 0.001f)
        assertEquals(32.0f, state.camDist, 0.001f)
        assertEquals(80.0f, state.camInclinationDeg, 0.001f)
        assertEquals(0.0f, state.camAzimuthDeg, 0.001f)
        assertEquals(0.0f, state.camTargetX, 0.001f)
        assertEquals(0.0f, state.camTargetY, 0.001f)
        assertEquals(0.0f, state.camTargetZ, 0.001f)
        assertFalse(state.enableDoppler)
        assertEquals(0.675f, state.exposure, 0.001f)
        assertTrue(state.enableBloom)
        assertEquals(0.22f, state.bloomIntensity, 0.001f)
        assertEquals(1.15f, state.bloomThreshold, 0.001f)
        assertFalse("M9 object rendering must be opt-in", state.enableObject)
        assertEquals(1, state.debugCoarseSamplingBlockSize)
        assertFalse("Debug workload instrumentation must be opt-in", state.enableWorkloadTelemetry)
        assertFalse("Animated disk must default to OFF", state.enableAnimation)
        assertEquals(0, state.animationAmplitudePercent)
        assertEquals(GargantuaAnimation.AnimationSpeed.NORMAL, state.animationSpeed)
        assertEquals(3, GargantuaAnimation.NOISE_OCTAVES)
        assertEquals(
            listOf(
                GargantuaAnimation.NoiseOctave(32, 8, 0.50f),
                GargantuaAnimation.NoiseOctave(64, 16, 0.35f),
                GargantuaAnimation.NoiseOctave(128, 32, 0.15f)
            ),
            GargantuaAnimation.NOISE_OCTAVE_SPECS
        )
        assertTrue(
            GargantuaAnimation.NOISE_OCTAVE_SPECS.all { it.latticeWidth >= it.latticeHeight }
        )
        assertTrue(state.useGeodesicShader)
        assertFalse(state.isPaused)
        assertTrue(state.isDarkTheme)
        assertFalse(state.isPersian)
    }

    @Test
    fun animationControlCyclesOffFifteenFortyEightyAndBack() {
        assertEquals(
            listOf(false to 0, true to 15, true to 40, true to 80),
            GargantuaAnimation.AMPLITUDE_STEPS
        )
        var mode = false to 0
        mode = GargantuaAnimation.nextMode(mode.first, mode.second)
        assertEquals(true to 15, mode)
        mode = GargantuaAnimation.nextMode(mode.first, mode.second)
        assertEquals(true to 40, mode)
        mode = GargantuaAnimation.nextMode(mode.first, mode.second)
        assertEquals(true to 80, mode)
        mode = GargantuaAnimation.nextMode(mode.first, mode.second)
        assertEquals(false to 0, mode)
        assertEquals(true to 15, GargantuaAnimation.nextMode(false, 15))
    }

    @Test
    fun animationTimeDigitsKeepShaderInputsSmallAfterHours() {
        val digits = GargantuaAnimation.timeDigits(8.0 * 60.0 * 60.0)
        assertEquals(GargantuaAnimation.TIME_DIGIT_COUNT, digits.size)
        digits.forEach { assertTrue(it >= 0.0f && it < 16.0f) }
    }

    @Test
    fun deterministicNoiseIsPeriodicAndMeasuredAfterQuantization() {
        val noise = GargantuaAnimation.deterministicNoise()
        val width = GargantuaAnimation.NOISE_WIDTH
        val height = GargantuaAnimation.NOISE_HEIGHT
        val value = { x: Int, y: Int -> noise[y * width + x].toInt() and 0xFF }
        assertTrue(noise.contentEquals(GargantuaAnimation.deterministicNoise()))
        assertEquals(0, noise.minOf { it.toInt() and 0xFF })
        assertEquals(255, noise.maxOf { it.toInt() and 0xFF })
        assertEquals(
            noise.sumOf { it.toInt() and 0xFF }.toFloat() / (noise.size * 255.0f),
            GargantuaAnimation.normalizedMean(noise),
            0.0f
        )
        assertTrue((0 until height).all { kotlin.math.abs(value(0, it) - value(width - 1, it)) <= 12 })
        assertTrue((0 until width).all { kotlin.math.abs(value(it, 0) - value(it, height - 1)) <= 12 })
    }

    @Test
    fun animationEnableParticipatesInRaySceneInvalidationSignatureButAmplitudeDoesNot() {
        val off = GargantuaRenderer.RaySceneSignature.fromState(GargantuaRenderState(), 540, 1200)
        val on = GargantuaRenderer.RaySceneSignature.fromState(
            GargantuaRenderState(enableAnimation = true, animationAmplitudePercent = 15), 540, 1200
        )
        val onOtherAmplitude = GargantuaRenderer.RaySceneSignature.fromState(
            GargantuaRenderState(enableAnimation = true, animationAmplitudePercent = 80), 540, 1200
        )
        assertFalse(off == on)
        assertEquals(on, onOtherAmplitude)
    }

    @Test
    fun coarseSamplingModeParticipatesInRaySceneInvalidationSignature() {
        val modes = listOf(1, 2, 3, 4, 6, 8)
        modes.forEach { mode ->
            val signature = GargantuaRenderer.RaySceneSignature.fromState(
                GargantuaRenderState(debugCoarseSamplingBlockSize = mode),
                540,
                1200
            )
            assertEquals(mode, signature.debugCoarseSamplingBlockSize)
        }
    }

    @Test
    fun renderScaleCanBeConfiguredUpToScientificCeiling() {
        val state = GargantuaRenderState(renderScale = 1.0f)
        assertEquals(1.0f, state.renderScale, 0.001f)

        val holder = RenderStateHolder(state)
        holder.updateState { it.copy(renderScale = 0.75f) }
        assertEquals(0.75f, holder.getState().renderScale, 0.001f)
    }

    @Test
    fun stateHolderPerformsAtomicUpdatesWithoutShearing() {
        val holder = RenderStateHolder()

        holder.updateState {
            it.copy(viewportWidth = 1080, viewportHeight = 2400)
        }

        val updated = holder.getState()
        assertEquals(1080, updated.viewportWidth)
        assertEquals(2400, updated.viewportHeight)
        assertFalse(updated.isPaused)

        holder.updateState { it.copy(isPaused = true) }
        assertTrue(holder.getState().isPaused)
        assertEquals(1080, holder.getState().viewportWidth)
    }

    @Test
    fun stateHolderInvokesInvalidationOnlyForActualStateChanges() {
        val holder = RenderStateHolder()
        var invalidations = 0
        holder.setStateChangeListener { invalidations++ }

        holder.updateState { it }
        assertEquals("Identical state must not request a render", 0, invalidations)

        holder.updateState { it.copy(camAzimuthDeg = 15.0f) }
        assertEquals("Camera changes must request a render", 1, invalidations)

        holder.setState(holder.getState())
        assertEquals("Setting an identical snapshot must not request a render", 1, invalidations)

        holder.updateState { it.copy(debugCoarseSamplingBlockSize = 4) }
        assertEquals("Changing the sampling mode must request a render", 2, invalidations)
        assertEquals(4, holder.getState().debugCoarseSamplingBlockSize)

        holder.notifyRenderNeeded()
        assertEquals("Lifecycle invalidation must request a render without mutating state", 3, invalidations)
    }

    @Test
    fun stateHolderHandlesConcurrentUpdatesRaceFree() {
        val holder = RenderStateHolder()
        val threadCount = 8
        val iterationsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        holder.updateState {
                            it.copy(viewportWidth = it.viewportWidth + 1)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Concurrent updates must complete within timeout", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threadCount * iterationsPerThread, holder.getState().viewportWidth)
    }

    @Test
    fun telemetryHolderEmitsExpectedDiagnostics() {
        val holder = RenderStateHolder()
        val initialTelemetry = holder.getTelemetry()

        assertEquals(0f, initialTelemetry.fps, 0.001f)
        assertEquals("Detecting...", initialTelemetry.glesVersion)
        assertNull(initialTelemetry.errorMessage)
        assertFalse(initialTelemetry.workloadStats.available)
        assertFalse(initialTelemetry.passTimings.gpuTimerAvailable)
        assertTrue(initialTelemetry.adaptiveWorkload.startsWith("Unavailable"))

        holder.setTelemetry(
            GargantuaTelemetry(
                fps = 59.8f,
                frameTimeMs = 16.7f,
                glesVersion = "OpenGL ES 3.2",
                glRenderer = "Adreno 740",
                isInitialized = true,
                renderScale = 0.5f,
                renderResolution = "540x1200",
                isHdrActive = true,
                exposure = 1.25f,
                camDist = 24.0f,
                camInclinationDeg = 82.0f,
                camAzimuthDeg = 0.0f
            )
        )

        val updated = holder.getTelemetry()
        assertEquals(59.8f, updated.fps, 0.01f)
        assertEquals(16.7f, updated.frameTimeMs, 0.01f)
        assertEquals("OpenGL ES 3.2", updated.glesVersion)
        assertEquals(0.5f, updated.renderScale, 0.001f)
        assertEquals("540x1200", updated.renderResolution)
        assertTrue(updated.isHdrActive)
        assertEquals(1.25f, updated.exposure, 0.001f)
        assertEquals(24.0f, updated.camDist, 0.001f)
        assertTrue(updated.isInitialized)
    }
}
