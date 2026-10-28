package com.zig.chal.config

import com.zig.chal.render.ChalBenchmark
import com.zig.chal.render.ChalCompatibilityReason
import com.zig.chal.render.ChalCamera
import java.io.Serializable

/** Small saveable session; never retain an Activity, SurfaceView or GPU resource over recreation. */
data class ChalRuntimeState(
    val params: ChalSimulationParams,
    val cameraSession: ChalCamera.Session? = null,
    val shaderTime: Double = 0.0,
    val benchmarkReport: ChalBenchmark.BenchmarkReport? = null,
    val preferCompatibility: Boolean = false,
    val compatibilityReason: ChalCompatibilityReason? = null,
    val diagnosticDetails: String? = null
) : Serializable
