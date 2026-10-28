package com.zig.chal

import com.zig.chal.config.ChalFeatures
import com.zig.chal.config.ChalPerformanceConfig
import com.zig.chal.config.ChalPresetName
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.render.ChalPerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the mobile performance work.
 *
 * The rendering cost on a phone is dominated by three things: how many pixels are shaded, how many
 * ray-marching steps each of them runs, and how long the adaptive controller takes to notice that
 * the device cannot keep up. These tests pin the numeric parts of that contract.
 */
class ChalPerformanceTest {

    private val budget = ChalPerformanceConfig.Scheduler.FRAME_BUDGET_MS

    // -----------------------------------------------------------------------------------------
    // Ray-step budget
    // -----------------------------------------------------------------------------------------

    @Test
    fun mobileRayBudgetIsCappedAtTheReferenceMaximum() {
        val cap = ChalPerformanceConfig.Compute.MAX_STEPS_MOBILE
        for (quality in ChalRayTracingQuality.entries) {
            val desktop = ChalFeatures.getMaxRaySteps(quality, isMobile = false)
            val mobile = ChalFeatures.getMaxRaySteps(quality, isMobile = true)
            assertEquals("mobile must cap '$quality' at $cap", minOf(desktop, cap), mobile)
            assertTrue("mobile budget must never exceed the desktop budget", mobile <= desktop)
        }
        // The two heavy tiers are the ones that actually get capped.
        assertEquals(cap, ChalFeatures.getMaxRaySteps(ChalRayTracingQuality.HIGH, isMobile = true))
        assertEquals(cap, ChalFeatures.getMaxRaySteps(ChalRayTracingQuality.ULTRA, isMobile = true))
        assertEquals(0, ChalFeatures.getMaxRaySteps(ChalRayTracingQuality.OFF, isMobile = true))
    }

    // -----------------------------------------------------------------------------------------
    // Adaptive resolution
    // -----------------------------------------------------------------------------------------

    @Test
    fun proportionalRescaleFollowsThePixelCount() {
        // Frame cost is proportional to pixels, i.e. to the square of the scale: to halve the frame
        // time the scale must shrink by sqrt(2).
        val halved = ChalPerformanceMonitor.proportionalScale(
            current = 1.0,
            measuredFrameTimeMs = budget * 2.0,
            targetFrameTimeMs = budget
        )
        assertEquals(1.0 / kotlin.math.sqrt(2.0), halved, 1e-9)

        // A device that is already fast is not punished.
        val unchanged = ChalPerformanceMonitor.proportionalScale(
            current = 0.8,
            measuredFrameTimeMs = budget,
            targetFrameTimeMs = budget
        )
        assertEquals(0.8, unchanged, 1e-9)

        // Degenerate inputs are ignored rather than producing NaN.
        assertEquals(0.8, ChalPerformanceMonitor.proportionalScale(0.8, 0.0, budget), 0.0)
        assertEquals(0.8, ChalPerformanceMonitor.proportionalScale(0.8, Double.NaN, budget), 0.0)
        assertEquals(0.8, ChalPerformanceMonitor.proportionalScale(0.8, budget, 0.0), 0.0)
    }

    @Test
    fun rescaledResolutionStaysInsideTheMobileEnvelope() {
        val monitor = ChalPerformanceMonitor()

        // A catastrophic first second (8x over budget) must not push the scale below the floor.
        monitor.seedRenderScale(1.0)
        monitor.seedRenderScale(
            ChalPerformanceMonitor.proportionalScale(1.0, budget * 8.0, budget)
        )
        assertTrue(
            "the scale must never fall below MIN_SCALE",
            monitor.getMetrics().renderResolution >= ChalPerformanceConfig.Resolution.MIN_SCALE
        )

        // Supersampling is capped at the documented mobile value.
        monitor.seedRenderScale(2.0)
        assertEquals(
            ChalPerformanceConfig.Resolution.MOBILE_CAP,
            monitor.getMetrics().renderResolution,
            1e-9
        )
    }

    @Test
    fun mobileSessionStartsAtTheMobileStartScale() {
        val monitor = ChalPerformanceMonitor()
        assertEquals(
            ChalPerformanceConfig.Mobile.START_SCALE,
            monitor.initialResolutionScale(),
            1e-9
        )
        assertEquals(
            ChalPerformanceConfig.Mobile.START_SCALE,
            ChalSimulationParams.MOBILE_PARAMS.renderScale,
            1e-9
        )
        assertTrue(
            "starting below native resolution must actually reduce the pixel count",
            monitor.initialResolutionScale() < ChalPerformanceConfig.Resolution.BASE_SCALE
        )
    }

    @Test
    fun mobileSessionStartsFromTheBalancedPreset() {
        val params = ChalSimulationParams.MOBILE_PARAMS
        assertEquals(ChalPresetName.BALANCED, params.performancePreset)
        assertEquals(ChalRayTracingQuality.MEDIUM, params.features.rayTracingQuality)
        assertFalse("bloom is the most expensive post pass", params.features.bloom)
        assertTrue("lensing and the disk stay on", params.features.gravitationalLensing)
        assertTrue(params.features.accretionDisk)
        assertTrue(
            "the preset label must match the preset it advertises",
            ChalFeatures.matchesPreset(params.features) == ChalPresetName.BALANCED
        )
    }

    @Test
    fun pacingFollowsTheDisplayRefreshRate() {
        val m = ChalPerformanceConfig.Mobile
        // 60 Hz panels hold 60 fps.
        assertEquals(1000.0 / 60.0, m.targetFrameTimeMs(60.0), 1e-9)
        // 120 Hz renders every other vsync; 144 Hz renders every third for 48 fps.
        assertEquals(1000.0 / 60.0, m.targetFrameTimeMs(120.0), 1e-9)
        assertEquals(1000.0 / 48.0, m.targetFrameTimeMs(144.0), 1e-9)
        // A 90 Hz panel can hold 90 or 45, never 60: pace to 45 so the adaptive controller stops
        // draining the resolution for a frame time the panel cannot display.
        assertEquals(1000.0 / 45.0, m.targetFrameTimeMs(90.0), 1e-9)
        // Unknown / unattached displays fall back to the reference budget.
        assertEquals(1000.0 / 60.0, m.targetFrameTimeMs(0.0), 1e-9)
        assertEquals(1000.0 / 60.0, m.targetFrameTimeMs(Double.NaN), 1e-9)
        // Uniform 50 Hz panels are paced by their own rate.
        assertEquals(1000.0 / 50.0, m.targetFrameTimeMs(50.0), 1e-9)
    }

    @Test
    fun theAdaptiveTargetAcceptsThePanelRate() {
        val monitor = ChalPerformanceMonitor()
        assertEquals(budget, monitor.getTargetFrameTime(), 1e-9)

        monitor.setTargetFrameTime(ChalPerformanceConfig.Mobile.targetFrameTimeMs(90.0))
        assertEquals(1000.0 / 45.0, monitor.getTargetFrameTime(), 1e-9)
        assertTrue("a new target must restart the direct rescale", monitor.isFastRecalibrationPending())

        // Nonsense values are clamped, never propagated.
        monitor.setTargetFrameTime(0.0)
        assertEquals(1000.0 / 45.0, monitor.getTargetFrameTime(), 1e-9)
        monitor.setTargetFrameTime(1e9)
        assertEquals(ChalPerformanceConfig.Mobile.MAX_TARGET_FRAME_MS, monitor.getTargetFrameTime(), 1e-9)
    }

    @Test
    fun thermostatAsksForARescalWhenTheDeviceSustainsOverBudgetFrames() {
        val monitor = ChalPerformanceMonitor()
        // Fast-forward past the initial convergence pass.
        repeat(ChalPerformanceConfig.Mobile.FAST_RECALIBRATION_FRAMES + 5) { monitor.updateMetrics(budget) }
        assertFalse("the first pass must have completed", monitor.isFastRecalibrationPending())

        val trigger = ChalPerformanceConfig.Mobile.FAST_RECALIBRATION_TRIGGER_FRAMES
        val hot = budget * (ChalPerformanceConfig.Mobile.OVER_BUDGET_FACTOR + 0.5)
        repeat(trigger - 1) { monitor.updateMetrics(hot) }
        assertFalse("one frame short of the window must not trigger", monitor.isFastRecalibrationPending())
        monitor.updateMetrics(hot)
        assertTrue("a sustained over-budget stretch must ask for a rescale", monitor.isFastRecalibrationPending())

        // A single bad frame is not a thermal event.
        val fresh = ChalPerformanceMonitor()
        repeat(ChalPerformanceConfig.Mobile.FAST_RECALIBRATION_FRAMES + 5) { fresh.updateMetrics(budget) }
        fresh.updateMetrics(hot)
        repeat(2) { fresh.updateMetrics(budget) }
        assertFalse(fresh.isFastRecalibrationPending())
    }
}
