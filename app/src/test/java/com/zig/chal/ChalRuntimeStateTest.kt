package com.zig.chal

import com.zig.chal.config.*
import com.zig.chal.render.ChalCamera
import com.zig.chal.render.ChalCompatibilityReason
import com.zig.chal.render.ChalRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ChalRuntimeStateTest {
    @Test fun theFullViewAndTourStateCanActuallyBeSavedToAnAndroidBundle() {
        var params = ChalSimulationParams(mass = 4.0, cameraYaw = 0.7, verticalAngle = 30.0, zoom = 40.0, paused = false)
        val camera = ChalCamera().apply { restore(params); startCinematic(ChalCamera.CinematicMode.ORBIT, params, false) }
        repeat(10) { camera.update(1.0 / 60, params) { params = params.it() } }
        val original = ChalRuntimeState(params, camera.saveSession(), shaderTime = 4.2,
            preferCompatibility = true, compatibilityReason = ChalCompatibilityReason.NATIVE_RUNTIME, diagnosticDetails = "test")
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { stream -> stream.writeObject(original) } }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ChalRuntimeState }
        assertEquals(original, restored)
        val renderer = ChalRenderer(restored.params, restored)
        assertEquals(restored.params, renderer.params)
        assertEquals(restored.cameraSession, renderer.saveRuntimeState().cameraSession)
        assertEquals(4.2, renderer.saveRuntimeState().shaderTime, 0.0)
    }
    @Test fun aDiveDoesNotSaveItsTemporaryZeroAutoPanAsTheUsersPreference() {
        val renderer = ChalRenderer(ChalSimulationParams(autoSpin = 0.012))
        renderer.startCinematic(ChalCamera.CinematicMode.DIVE, false)
        assertEquals(0.0, renderer.params.autoSpin, 0.0)
        assertEquals(0.012, renderer.paramsForPersistence().autoSpin, 0.0)
        renderer.stopCinematic()
        assertEquals(0.012, renderer.params.autoSpin, 0.0)
    }
    @Test fun poseRestoreHandlesLegacyYawAndExtremePitch() {
        val params = ChalRenderPolicy.normalize(ChalSimulationParams(cameraYaw = -0.25, verticalAngle = 1000.0), native = true)
        assertEquals(0.75, params.cameraYaw, 0.0)
        assertEquals(171.0, params.verticalAngle, 0.0)
    }
    @Test fun backendNormalizationKeepsMeaningfulStoredOptions() {
        val original = ChalSimulationParams(renderScale = 0.65, features = ChalSimulationParams.DEFAULT_PARAMS.features.copy(bloom = false, kerrShadow = true, spacetimeVisualization = true))
        val normalized = ChalRenderPolicy.normalize(original, false)
        assertEquals(0.65, normalized.renderScale, 0.0)
        assertFalse(normalized.features.bloom)
        assertTrue(normalized.features.kerrShadow)
        assertFalse(normalized.features.spacetimeVisualization)
    }
    @Test fun reducedMotionRestoreDoesNotLoseTheAutoPanPreferenceOfATemporaryDive() {
        val initial = ChalSimulationParams(autoSpin = 0.012)
        val camera = ChalCamera().apply { restore(initial); startCinematic(ChalCamera.CinematicMode.DIVE, initial, false) }
        val runtime = ChalRuntimeState(initial.copy(autoSpin = 0.0, reducedMotion = true), camera.saveSession())
        val restored = ChalRenderer(runtime.params, runtime)
        assertEquals(0.012, restored.paramsForPersistence().autoSpin, 0.0)
        assertFalse(restored.saveRuntimeState().cameraSession!!.cinematic.active)
    }

}
