package com.zig.chal.render

import com.zig.chal.config.ChalFeatureToggles
import com.zig.chal.config.ChalFeatures
import com.zig.chal.config.ChalPresetName
import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalSimulationParams
import java.io.Serializable

/** GLES-only comparison of distinct presets at a fixed resolution. The user's state is restored. */
class ChalBenchmark(private val nowMillis: () -> Double = { System.nanoTime() / 1_000_000.0 }) {
    data class BenchmarkResult(
        val presetName: ChalPresetName,
        val averageFPS: Double,
        val minFPS: Double,
        val maxFPS: Double,
        val averageFrameTimeMs: Double,
        val testDurationSeconds: Double
    ) : Serializable

    data class HardwareInfo(val isMobile: Boolean, val hasIntegratedGPU: Boolean, val devicePixelRatio: Double) : Serializable
    data class BenchmarkReport(val results: List<BenchmarkResult>, val recommendedPreset: ChalPresetName, val hardwareInfo: HardwareInfo, val renderScale: Double = 1.0) : Serializable
    enum class State { IDLE, RUNNING, COMPLETED, CANCELLED }

    companion object {
        const val TEST_DURATION_MS = 5_000.0
        const val WARMUP_MS = 1_000.0
        val PRESETS_TO_TEST: List<ChalPresetName> = ChalRenderPolicy.fallbackPresets

        fun recommend(results: List<BenchmarkResult>): ChalPresetName {
            // Prefer fidelity only when it actually meets a usable frame rate.
            for (threshold in listOf(60.0, 35.0)) {
                for (preset in PRESETS_TO_TEST.asReversed()) {
                    if (results.any { it.presetName == preset && it.averageFPS >= threshold }) return preset
                }
            }
            // If none is usable, choose the FASTEST, never the slowest test.
            return results.maxByOrNull { it.averageFPS }?.presetName ?: ChalPresetName.MAXIMUM_PERFORMANCE
        }
    }

    @Volatile var state: State = State.IDLE
        private set
    val results = mutableListOf<BenchmarkResult>()
    @Volatile var originalParams: ChalSimulationParams? = null
        private set
    private var currentPresetIndex = 0
    private var testedScale = 1.0
    private var waitForPresentation = false
    private var awaitingPresentation = false
    private var testStartTime = 0.0
    private var fpsSum = 0.0
    private var fpsCount = 0
    private var fpsMin = Double.MAX_VALUE
    private var fpsMax = 0.0

    fun start(params: ChalSimulationParams, renderScale: Double = params.renderScale, waitForFirstPresentation: Boolean = false) {
        if (state == State.RUNNING) return
        originalParams = params
        testedScale = renderScale
        waitForPresentation = waitForFirstPresentation
        results.clear()
        currentPresetIndex = 0
        state = State.RUNNING
        startPresetTest()
    }

    fun cancel(): ChalSimulationParams? {
        if (state != State.RUNNING) return null
        state = State.CANCELLED
        return takeRestoreParams()
    }

    /** Available after completion as well as cancellation; consumed exactly once. */
    fun takeRestoreParams(): ChalSimulationParams? {
        val restore = originalParams
        originalParams = null
        return restore
    }

    fun currentPreset(): ChalPresetName? =
        if (state == State.RUNNING) PRESETS_TO_TEST.getOrNull(currentPresetIndex) else null

    fun currentProgress(): Double =
        if (state == State.RUNNING && !awaitingPresentation) ((nowMillis() - testStartTime) / TEST_DURATION_MS).coerceIn(0.0, 1.0) else 0.0

    fun tick(currentFPS: Double, onComplete: (BenchmarkReport) -> Unit): ChalPresetName? {
        if (state != State.RUNNING || awaitingPresentation) return null
        val elapsed = nowMillis() - testStartTime
        // Exclude shader warmup and the previous preset's rolling FPS.
        if (elapsed >= WARMUP_MS && currentFPS > 0.0 && currentFPS.isFinite()) {
            fpsSum += currentFPS
            fpsCount++
            fpsMin = minOf(fpsMin, currentFPS)
            fpsMax = maxOf(fpsMax, currentFPS)
        }
        if (elapsed < TEST_DURATION_MS) return null
        finishPresetTest()
        currentPresetIndex++
        if (currentPresetIndex < PRESETS_TO_TEST.size) {
            startPresetTest()
            return PRESETS_TO_TEST[currentPresetIndex]
        }
        state = State.COMPLETED
        onComplete(BenchmarkReport(results.toList(), recommend(results), HardwareInfo(true, true, 1.0), testedScale))
        // Keep originalParams until the renderer restores it. Publishing a report must not lose it.
        return null
    }

    private fun startPresetTest() {
        testStartTime = nowMillis()
        awaitingPresentation = waitForPresentation
        fpsSum = 0.0
        fpsCount = 0
        fpsMin = Double.MAX_VALUE
        fpsMax = 0.0
    }

    /** Start measurement only after compilation and the first visible frame of this preset. */
    fun markPresetPresented(): Boolean {
        if (state != State.RUNNING || !awaitingPresentation) return false
        awaitingPresentation = false
        testStartTime = nowMillis()
        return true
    }

    private fun finishPresetTest() {
        val preset = PRESETS_TO_TEST.getOrNull(currentPresetIndex) ?: return
        val average = if (fpsCount > 0) fpsSum / fpsCount else 0.0
        results += BenchmarkResult(preset, average, if (fpsCount > 0) fpsMin else 0.0, fpsMax,
            if (average > 0.0) 1_000.0 / average else 0.0, TEST_DURATION_MS / 1_000.0)
    }

    fun restoreReport(report: BenchmarkReport) {
        if (state == State.RUNNING) return
        results.clear()
        results.addAll(report.results)
        state = State.COMPLETED
    }

    fun featuresFor(preset: ChalPresetName): ChalFeatureToggles = ChalFeatures.getPreset(preset)
}
