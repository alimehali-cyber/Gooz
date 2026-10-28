package com.zig.chal

import com.zig.chal.config.*
import com.zig.chal.physics.ChalKerrMetric
import com.zig.chal.physics.ChalObserverReadouts
import com.zig.chal.render.ChalPerformanceMonitor
import com.zig.chal.render.ChalQualityController
import com.zig.chal.render.ChalRenderer
import org.junit.Assert.*
import org.junit.Test

class ChalControlsRegressionTest {
    @Test fun fallbackHasThreeRealQualityTiersAndNativeKeepsUltra() {
        assertEquals(listOf(ChalRayTracingQuality.LOW, ChalRayTracingQuality.MEDIUM, ChalRayTracingQuality.HIGH), ChalRenderPolicy.fallbackQualities)
        assertTrue(ChalRenderPolicy.nativeQualities.contains(ChalRayTracingQuality.ULTRA))
        assertEquals(ChalRayTracingQuality.HIGH, ChalRenderPolicy.quality(ChalRayTracingQuality.ULTRA, false))
    }
    @Test fun retiredSpacetimeControlIsNotOffered() {
        assertFalse(ChalFeatureToggle.entries.any { it.name.contains("SPACETIME") })
    }
    @Test fun previewOffersOnlyImplementedModules() {
        val params = ChalSimulationParams(features = ChalFeatures.getPreset(ChalPresetName.MAXIMUM_PERFORMANCE))
        assertEquals(setOf(ChalFeatureToggle.ACCRETION_DISK, ChalFeatureToggle.PHOTON_SPHERE, ChalFeatureToggle.BACKGROUND_STARS, ChalFeatureToggle.VOLUMETRIC_BLOOM),
            ChalFeatureToggle.available(params, false, true).toSet())
    }
    @Test fun nativeBloomNowHasAnActualDisableAction() {
        val params = ChalSimulationParams()
        assertTrue(ChalFeatureToggle.available(params, true, true).contains(ChalFeatureToggle.VOLUMETRIC_BLOOM))
        val block = XapkRendererContract.build(params.copy(features = params.features.copy(bloom = false), bloomIntensity = 1.2))
        assertEquals(0f, block.floats[9], 0f)
    }
    @Test fun unavailableBloomAndDiskDependentModulesAreRemoved() {
        val params = ChalSimulationParams().let { it.copy(features = it.features.copy(accretionDisk = false)) }
        val available = ChalFeatureToggle.available(params, false, false)
        assertFalse(available.contains(ChalFeatureToggle.VOLUMETRIC_BLOOM))
        assertFalse(available.contains(ChalFeatureToggle.RELATIVISTIC_JETS))
        assertFalse(available.contains(ChalFeatureToggle.DOPPLER_BEAMING))
    }
    @Test fun manualResolutionDoesNotDrift() {
        val monitor = ChalPerformanceMonitor { 10_000.0 }
        monitor.seedRenderScale(0.65)
        repeat(200) { monitor.updateMetrics(100.0, adaptive = false) }
        assertEquals(0.65, monitor.getMetrics().renderResolution, 1e-12)
        assertEquals(10, monitor.getMetrics().currentFPS)
    }
    @Test fun batterySaverCapsActualResolution() {
        val monitor = ChalPerformanceMonitor { 10_000.0 }
        monitor.seedRenderScale(1.0)
        monitor.updateMetrics(33.33, adaptive = false, maximumScale = 0.75)
        assertEquals(0.75, monitor.getMetrics().renderResolution, 1e-12)
    }
    @Test fun actualQualityIsReportedRatherThanTheOldRamCap() {
        val monitor = ChalPerformanceMonitor { 10_000.0 }
        monitor.setQuality(ChalRayTracingQuality.HIGH)
        assertEquals(ChalRayTracingQuality.HIGH, monitor.getMetrics().quality)
    }
    @Test fun observerReadoutsUseTheSameRadiusAsTheShader() {
        val params = ChalSimulationParams(mass = 10.0, zoom = 30.0)
        assertEquals(60.0, ChalObserverReadouts.fallbackRadius(params), 0.0)
        val rate = ChalKerrMetric.calculateTimeDilation(60.0, 10.0)
        assertEquals(rate, ChalObserverReadouts.clockRate(params), 1e-12)
        assertEquals(0.22474487139158894, ChalObserverReadouts.redshift(rate), 1e-12)
    }
    @Test fun observerReadoutsRespectShaderKamikazeProtection() {
        val params = ChalSimulationParams(mass = 10.0, spin = 0.0, zoom = 1.5)
        assertEquals(30.0, ChalObserverReadouts.fallbackRadius(params), 1e-12)
        val preview = params.copy(features = params.features.copy(rayTracingQuality = ChalRayTracingQuality.LOW))
        assertEquals(3.0, ChalObserverReadouts.fallbackRadius(preview), 0.0)
        assertTrue(ChalObserverReadouts.clockRate(preview).isNaN())
    }
    @Test fun slidersAndNumericEntryUseTheDeclaredStep() {
        assertEquals(12.4, ChalSliderMath.quantize(12.36, ChalSimulationConfig.MASS), 1e-12)
        assertEquals(10_500.0, ChalSliderMath.quantize(10_501.0, ChalSimulationConfig.DISK_TEMP), 0.0)
        assertEquals(0.99, ChalSliderMath.quantize(1.0, ChalSimulationConfig.SPIN), 0.0)
    }
    @Test fun numericEntryAcceptsPersianAndArabicDigitsButNotNaN() {
        assertEquals(12.3, ChalSliderMath.parseNumber("۱۲٫۳")!!, 0.0)
        assertEquals(-0.3, ChalSliderMath.parseNumber("−٠٫٣")!!, 0.0)
        assertNull(ChalSliderMath.parseNumber("NaN"))
        assertNull(ChalSliderMath.parseNumber("Infinity"))
    }
    @Test fun backendValidationRejectsNonFiniteParameters() {
        val params = ChalRenderPolicy.normalize(ChalSimulationParams(mass = Double.NaN, spin = Double.POSITIVE_INFINITY, renderScale = 2.0), false)
        assertEquals(1.0, params.mass, 0.0)
        assertEquals(0.5, params.spin, 0.0)
        assertEquals(1.0, params.renderScale, 0.0)
    }
    @Test fun uiEditsMergeIntoTheLatestCameraState() {
        val renderer = ChalRenderer(ChalSimulationParams(cameraYaw = 0.7, verticalAngle = 30.0, zoom = 35.0))
        renderer.editParams { copy(mass = 10.0) }
        assertEquals(0.7, renderer.params.cameraYaw, 1e-10)
        assertEquals(30.0, renderer.params.verticalAngle, 0.0)
        assertEquals(35.0, renderer.params.zoom, 0.0)
    }
    @Test fun pausedPinchIsIncludedInTheNextLiveState() {
        val renderer = ChalRenderer(ChalSimulationParams(paused = true, zoom = 30.0))
        renderer.onPinchStart(100.0)
        renderer.onPinch(120.0)
        assertEquals(29.6, renderer.params.zoom, 1e-9)
        assertTrue(renderer.params.paused)
    }
    @Test fun autoQualityWaitsForSustainedSlowFrames() {
        val controller = ChalQualityController()
        assertNull(controller.observe(20, 60, ChalRayTracingQuality.HIGH, false, 0.0))
        assertNull(controller.observe(20, 60, ChalRayTracingQuality.HIGH, false, 2_999.0))
        assertEquals(ChalRayTracingQuality.MEDIUM, controller.observe(20, 60, ChalRayTracingQuality.HIGH, false, 3_000.0))
    }
    @Test fun autoQualityDoesNotRepeatFailedUpgrades() {
        val controller = ChalQualityController()
        controller.observe(20, 60, ChalRayTracingQuality.HIGH, false, 0.0)
        controller.observe(20, 60, ChalRayTracingQuality.HIGH, false, 3_000.0)
        controller.observe(60, 60, ChalRayTracingQuality.MEDIUM, false, 4_000.0)
        assertNull(controller.observe(60, 60, ChalRayTracingQuality.MEDIUM, false, 30_000.0))
    }
    @Test fun autoQualityCanCautiouslyRaiseAHealthyInitialTier() {
        val controller = ChalQualityController()
        controller.observe(60, 60, ChalRayTracingQuality.MEDIUM, false, 0.0)
        assertEquals(ChalRayTracingQuality.HIGH, controller.observe(60, 60, ChalRayTracingQuality.MEDIUM, false, 12_000.0))
    }
    @Test fun reducedMotionSuppressesNativeAutoPanWithoutLosingTheSetting() {
        val params = ChalSimulationParams(autoSpin = 0.012, reducedMotion = true)
        assertEquals(0f, XapkRendererContract.build(params).floats[10], 0f)
        assertEquals(0.012, params.autoSpin, 0.0)
    }
}
