package com.zig.chal.config

import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChalSettingsStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(XapkSimulationSettingsStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
    @Before fun clear() {
        preferences.edit().clear().commit()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    @Test fun allPhysicalDisplayAndViewOptionsRoundTrip() {
        val store = XapkSimulationSettingsStore(context)
        val original = ChalSimulationParams(mass = 4.0, spin = 0.94, zoom = 38.0, cameraYaw = 0.7, verticalAngle = 32.0,
            renderScale = 0.65, paused = true, adaptiveResolution = false, automaticQuality = true,
            batterySaver = true, reducedMotion = true, features = ChalFeatures.getPreset(ChalPresetName.BALANCED).copy(bloom = false, kerrShadow = true))
        store.save(original)
        val restored = store.load()
        assertEquals(original.mass, restored.mass, 1e-6)
        assertEquals(original.spin, restored.spin, 1e-6)
        assertEquals(original.zoom, restored.zoom, 1e-6)
        assertEquals(original.cameraYaw, restored.cameraYaw, 1e-6)
        assertEquals(original.verticalAngle, restored.verticalAngle, 1e-6)
        assertEquals(original.renderScale, restored.renderScale, 1e-6)
        assertFalse("pause is transient and must not outlive the visit", restored.paused)
        assertFalse(restored.adaptiveResolution)
        assertTrue(restored.automaticQuality)
        assertTrue(restored.batterySaver)
        assertTrue(restored.reducedMotion)
        assertEquals(original.features, restored.features)
    }
    @Test fun legacyOffIsNotSilentlyPersistedAsLow() {
        val store = XapkSimulationSettingsStore(context)
        store.save(ChalSimulationParams().let { it.copy(features = it.features.copy(rayTracingQuality = ChalRayTracingQuality.OFF)) })
        assertEquals(ChalRayTracingQuality.OFF, store.load().features.rayTracingQuality)
        assertEquals(ChalRayTracingQuality.LOW, ChalRenderPolicy.normalize(store.load(), false).features.rayTracingQuality)
    }
    @Test fun originalNativePreferenceKeysRemainReadable() {
        preferences.edit().putFloat("mass", 10f).putFloat("spin", 0.5f).putString("preset", "ULTRA")
            .putBoolean("enDisk", false).putBoolean("enStars", false).commit()
        val restored = XapkSimulationSettingsStore(context).load()
        assertEquals(10.0, restored.mass, 0.0)
        assertEquals(ChalRayTracingQuality.ULTRA, restored.features.rayTracingQuality)
        assertFalse(restored.features.accretionDisk)
        assertFalse(restored.features.backgroundStars)
    }
    @Test fun malformedAndNonFinitePreferencesUseSafeDefaults() {
        preferences.edit().putFloat("mass", Float.NaN).putFloat("spin", Float.POSITIVE_INFINITY)
            .putString("chal.renderScale", "wrong type").putInt("chal.paused", 7).commit()
        val restored = XapkSimulationSettingsStore(context).load()
        assertEquals(ChalSimulationConfig.MASS.default, restored.mass, 0.0)
        assertEquals(ChalSimulationConfig.SPIN.default, restored.spin, 0.0)
        assertTrue(restored.renderScale.isFinite())
        assertFalse(restored.paused)
    }
    @Test fun freshSettingsHonorSystemReducedMotion() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(XapkSimulationSettingsStore(context).load().reducedMotion)
    }
    @Test fun explicitMotionPreferenceIsNotOverwrittenByTheSystemDefault() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        preferences.edit().putBoolean("chal.reducedMotion", false).commit()
        assertFalse(XapkSimulationSettingsStore(context).load().reducedMotion)
    }
    @Test fun defaultsDoNotReadTheUsersOldPhysicalSettings() {
        preferences.edit().putFloat("mass", 20f).putFloat("spin", 0.99f).commit()
        val defaults = XapkSimulationSettingsStore(context).defaults()
        assertEquals(ChalSimulationConfig.MASS.default, defaults.mass, 0.0)
        assertEquals(ChalSimulationConfig.SPIN.default, defaults.spin, 0.0)
        assertFalse(defaults.paused)
    }
    @Test fun aPausedSceneIsNotRestoredPausedOnTheNextVisit() {
        val store = XapkSimulationSettingsStore(context)
        store.save(ChalSimulationParams(paused = true))
        assertFalse(store.load().paused)
        assertFalse("no pause key may be written at all", preferences.contains("chal.paused"))
    }
}
