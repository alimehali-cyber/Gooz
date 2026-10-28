package com.zig.chal

import com.zig.chal.config.*
import com.zig.chal.render.ChalBenchmark
import org.junit.Assert.*
import org.junit.Test

class ChalBenchmarkRegressionTest {
    private fun result(preset: ChalPresetName, fps: Double) = ChalBenchmark.BenchmarkResult(preset, fps, fps, fps, 1000.0 / fps, 5.0)
    @Test fun unusableRunsRecommendTheFastestNotTheSlowest() {
        val results = listOf(result(ChalPresetName.MAXIMUM_PERFORMANCE, 30.0), result(ChalPresetName.BALANCED, 25.0), result(ChalPresetName.HIGH_QUALITY, 15.0))
        assertEquals(ChalPresetName.MAXIMUM_PERFORMANCE, ChalBenchmark.recommend(results))
    }
    @Test fun fastestFallbackDoesNotAssumePresetOrder() {
        assertEquals(ChalPresetName.BALANCED, ChalBenchmark.recommend(listOf(result(ChalPresetName.MAXIMUM_PERFORMANCE, 20.0), result(ChalPresetName.BALANCED, 30.0))))
    }
    @Test fun highestFidelityThatMeetsSixtyFpsWins() {
        assertEquals(ChalPresetName.HIGH_QUALITY, ChalBenchmark.recommend(listOf(result(ChalPresetName.MAXIMUM_PERFORMANCE, 90.0), result(ChalPresetName.BALANCED, 75.0), result(ChalPresetName.HIGH_QUALITY, 60.0))))
    }
    @Test fun thirtyFiveFpsFallbackPrefersFidelity() {
        assertEquals(ChalPresetName.HIGH_QUALITY, ChalBenchmark.recommend(listOf(result(ChalPresetName.MAXIMUM_PERFORMANCE, 55.0), result(ChalPresetName.BALANCED, 45.0), result(ChalPresetName.HIGH_QUALITY, 35.0))))
    }
    @Test fun onlyDistinctSupportedGlesPresetsAreMeasured() {
        assertEquals(ChalRenderPolicy.fallbackPresets, ChalBenchmark.PRESETS_TO_TEST)
        assertFalse(ChalBenchmark.PRESETS_TO_TEST.contains(ChalPresetName.ULTRA_QUALITY))
    }
    @Test fun completionKeepsAllOriginalSettingsAvailableUntilRestored() {
        var now = 0.0
        val benchmark = ChalBenchmark { now }
        val original = ChalSimulationParams(paused = true, adaptiveResolution = true, automaticQuality = true, renderScale = 0.65,
            zoom = 30.0, cameraYaw = 0.7, features = ChalFeatures.getPreset(ChalPresetName.BALANCED))
        var report: ChalBenchmark.BenchmarkReport? = null
        benchmark.start(original, renderScale = 0.6)
        repeat(ChalBenchmark.PRESETS_TO_TEST.size) {
            now += 1_000.0
            benchmark.tick(40.0) { report = it }
            now += 4_000.0
            benchmark.tick(40.0) { report = it }
        }
        assertEquals(ChalBenchmark.State.COMPLETED, benchmark.state)
        assertNotNull(report)
        assertEquals(0.6, report!!.renderScale, 0.0)
        assertEquals(original, benchmark.originalParams)
        assertEquals(original, benchmark.takeRestoreParams())
        assertNull(benchmark.takeRestoreParams())
    }
    @Test fun cancellationRestoresAllSettingsOnce() {
        val benchmark = ChalBenchmark { 0.0 }
        val original = ChalSimulationParams(renderScale = 0.6, reducedMotion = true, batterySaver = true, paused = true)
        benchmark.start(original)
        assertEquals(original, benchmark.cancel())
        assertEquals(ChalBenchmark.State.CANCELLED, benchmark.state)
        assertNull(benchmark.cancel())
    }
    @Test fun warmupAndInvalidFpsAreNotCounted() {
        var now = 0.0
        val benchmark = ChalBenchmark { now }
        benchmark.start(ChalSimulationParams())
        now = 500.0; benchmark.tick(200.0) {}
        now = 1_000.0; benchmark.tick(Double.NaN) {}
        now = 2_000.0; benchmark.tick(0.0) {}
        now = 5_000.0; benchmark.tick(30.0) {}
        assertEquals(30.0, benchmark.results.single().averageFPS, 0.0)
    }
    @Test fun duplicateStartsCannotOverwriteTheRestorePoint() {
        val benchmark = ChalBenchmark { 0.0 }
        val original = ChalSimulationParams(mass = 2.0)
        benchmark.start(original)
        benchmark.start(original.copy(mass = 10.0))
        assertEquals(original, benchmark.originalParams)
    }
    @Test fun coldCompilationIsExcludedUntilAPresetHasActuallyBeenPresented() {
        var now = 0.0
        val benchmark = ChalBenchmark { now }
        benchmark.start(ChalSimulationParams(), waitForFirstPresentation = true)
        now = 20_000.0
        assertNull(benchmark.tick(1.0) { fail("Compilation must not finish a test") })
        assertTrue(benchmark.markPresetPresented())
        assertFalse(benchmark.markPresetPresented())
        now = 21_000.0
        benchmark.tick(60.0) {}
        now = 25_000.0
        benchmark.tick(60.0) {}
        assertEquals(60.0, benchmark.results.single().averageFPS, 0.0)
    }

}
