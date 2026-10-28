package com.zig.chal.config

import com.zig.chal.render.ChalRenderer

/** Small UI-facing contract shared by the XAPK Vulkan renderer and Chal's GLES fallback. */
interface ChalRendererBackend {
    val params: ChalSimulationParams
    val errorMessage: String?
    fun snapshot(): ChalRenderer.ChalSnapshot
    fun updateParams(newParams: ChalSimulationParams)
    /** Apply a UI edit to the latest live state, never a stale telemetry copy. */
    fun editParams(edit: ChalSimulationParams.() -> ChalSimulationParams): ChalSimulationParams
    /** Benchmarks must not overwrite saved user settings with a temporary test preset. */
    fun paramsForPersistence(): ChalSimulationParams = params
    fun saveRuntimeState(): ChalRuntimeState = ChalRuntimeState(paramsForPersistence())
    fun setDisplayRefreshRate(refreshRateHz: Double)
}
