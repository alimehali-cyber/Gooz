package com.zig.chal.config

/** Backend capabilities and validation. Never advertise identical or unimplemented quality tiers. */
object ChalRenderPolicy {
    val fallbackQualities = listOf(ChalRayTracingQuality.LOW, ChalRayTracingQuality.MEDIUM, ChalRayTracingQuality.HIGH)
    val nativeQualities = fallbackQualities + ChalRayTracingQuality.ULTRA
    val fallbackPresets = listOf(ChalPresetName.MAXIMUM_PERFORMANCE, ChalPresetName.BALANCED, ChalPresetName.HIGH_QUALITY)

    fun quality(quality: ChalRayTracingQuality, native: Boolean): ChalRayTracingQuality = when {
        quality == ChalRayTracingQuality.OFF -> ChalRayTracingQuality.LOW
        !native && quality == ChalRayTracingQuality.ULTRA -> ChalRayTracingQuality.HIGH
        else -> quality
    }

    fun hasRayMarching(quality: ChalRayTracingQuality): Boolean =
        quality != ChalRayTracingQuality.OFF && quality != ChalRayTracingQuality.LOW

    fun normalize(params: ChalSimulationParams, native: Boolean): ChalSimulationParams {
        val features = params.features.copy(
            rayTracingQuality = quality(params.features.rayTracingQuality, native),
            spacetimeVisualization = false // retired: no visualization implementation exists
        )
        return params.copy(
            mass = value(params.mass, ChalSimulationConfig.MASS),
            spin = value(params.spin, ChalSimulationConfig.SPIN),
            lensing = value(params.lensing, ChalSimulationConfig.LENSING),
            frameDraggingStrength = value(params.frameDraggingStrength, ChalSimulationConfig.FRAME_DRAGGING),
            diskDensity = value(params.diskDensity, ChalSimulationConfig.DISK_DENSITY),
            diskTemp = value(params.diskTemp, ChalSimulationConfig.DISK_TEMP),
            diskSize = value(params.diskSize, ChalSimulationConfig.DISK_SIZE),
            diskScaleHeight = value(params.diskScaleHeight, ChalSimulationConfig.DISK_SCALE_HEIGHT),
            bloomThreshold = value(params.bloomThreshold, ChalSimulationConfig.BLOOM_THRESHOLD),
            bloomIntensity = value(params.bloomIntensity, ChalSimulationConfig.BLOOM_INTENSITY),
            autoSpin = value(params.autoSpin, ChalSimulationConfig.AUTO_SPIN),
            zoom = value(params.zoom, ChalSimulationConfig.ZOOM),
            cameraYaw = normalizeYaw(params.cameraYaw),
            verticalAngle = value(params.verticalAngle, if (native) ChalSimulationConfig.VERTICAL_ANGLE
                else ChalSimulationConfig.VERTICAL_ANGLE.copy(min = 0.001 * 180.0 / Math.PI, max = 180.0 - 0.001 * 180.0 / Math.PI)),
            paused = !native && params.paused,
            renderScale = if (native) XapkRendererContract.qualityFor(features.rayTracingQuality).renderScale.toDouble()
                else value(params.renderScale, ChalSimulationConfig.RENDER_SCALE)
                    .coerceAtMost(if (params.batterySaver) 0.75 else 1.0),
            features = features,
            performancePreset = ChalFeatures.matchesPreset(features)
        )
    }

    fun normalizeYaw(yaw: Double): Double {
        if (!yaw.isFinite()) return XapkCameraState.DEFAULT_YAW.toDouble()
        val wrapped = yaw % 1.0
        return if (wrapped < 0.0) wrapped + 1.0 else wrapped
    }

    private fun value(value: Double, config: ChalParameterConfig): Double =
        (if (value.isFinite()) value else config.default).coerceIn(config.min, config.max)
}

/** Keep the original 60 Hz visual speed while making motion independent of the display refresh. */
object ChalMotion {
    const val REFERENCE_FPS = 60.0
    const val SHADER_TIME_PER_SECOND = 0.6 // original 0.01 shader-time units × 60 rendered frames
    fun radiansPerSecond(legacyAutoPan: Double): Double = legacyAutoPan * REFERENCE_FPS
    fun legacyAutoPan(radiansPerSecond: Double): Double = radiansPerSecond / REFERENCE_FPS
}
