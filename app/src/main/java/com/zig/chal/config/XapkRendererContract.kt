package com.zig.chal.config

/** XAPK camera defaults and clamp limits, kept in its normalized angular coordinate system. */
data class XapkCameraState(
    val yaw: Float = DEFAULT_YAW,
    val pitch: Float = DEFAULT_PITCH,
    val distance: Float = DEFAULT_DISTANCE
) {
    companion object {
        const val DEFAULT_YAW = 0.5f
        const val DEFAULT_PITCH = 0.539f
        const val DEFAULT_DISTANCE = 100.0f
        const val MIN_PITCH = 0.05f
        const val MAX_PITCH = 0.95f
        const val MIN_DISTANCE = 1.5f
        const val MAX_DISTANCE = 100.0f
        const val DRAG_PITCH_SENSITIVITY = 0.9f

        /** XAPK normalized polar pitch maps to an angle in [0, 180] degrees for Chal. */
        const val DEFAULT_POLAR_ANGLE_DEGREES = 97.02
    }
}

/**
 * Field-order contract sent to `NativeBridge.nativeSetParams` by BlackHole 1.0.0.
 * The spin slot is the dimensionless Kerr parameter chi; do not multiply it by mass here.
 */
data class XapkParameterBlock(
    val floats: FloatArray,
    val integers: IntArray
)

object XapkRendererContract {
    const val PARAMETER_FLOAT_COUNT = 16
    const val PARAMETER_INT_COUNT = 4

    const val FEATURE_LENSING = 1
    const val FEATURE_DISK = 1 shl 1
    const val FEATURE_DOPPLER = 1 shl 2
    const val FEATURE_PHOTON_GLOW = 1 shl 3
    const val FEATURE_STARS = 1 shl 4
    const val FEATURE_JETS = 1 shl 5
    const val FEATURE_REDSHIFT = 1 shl 6

    /** BlackHole's quality enum: (steps, render scale, medium flag, high flag). */
    enum class Quality(
        val steps: Int,
        val renderScale: Float,
        val mediumFlag: Int,
        val highFlag: Int,
        val preferenceName: String
    ) {
        LOW(32, 0.5f, 0, 0, "LOW"),
        MEDIUM(64, 0.75f, 1, 0, "MEDIUM"),
        HIGH(128, 1.0f, 1, 1, "HIGH"),
        ULTRA(256, 1.0f, 1, 1, "ULTRA")
    }

    fun qualityFor(quality: ChalRayTracingQuality): Quality = when (quality) {
        ChalRayTracingQuality.OFF,
        ChalRayTracingQuality.LOW -> Quality.LOW
        ChalRayTracingQuality.MEDIUM -> Quality.MEDIUM
        ChalRayTracingQuality.HIGH -> Quality.HIGH
        ChalRayTracingQuality.ULTRA -> Quality.ULTRA
    }

    fun chalQualityFor(preferenceName: String?): ChalRayTracingQuality? = when (preferenceName?.uppercase()) {
        "LOW" -> ChalRayTracingQuality.LOW
        "MEDIUM" -> ChalRayTracingQuality.MEDIUM
        "HIGH" -> ChalRayTracingQuality.HIGH
        "ULTRA" -> ChalRayTracingQuality.ULTRA
        else -> null
    }

    fun build(
        params: ChalSimulationParams,
        camera: XapkCameraState = XapkCameraState()
    ): XapkParameterBlock {
        val quality = qualityFor(params.features.rayTracingQuality)
        var featureMask = 0
        if (params.features.gravitationalLensing) featureMask = featureMask or FEATURE_LENSING
        if (params.features.accretionDisk) featureMask = featureMask or FEATURE_DISK
        if (params.features.dopplerBeaming) featureMask = featureMask or FEATURE_DOPPLER
        if (params.features.photonSphereGlow) featureMask = featureMask or FEATURE_PHOTON_GLOW
        if (params.features.backgroundStars) featureMask = featureMask or FEATURE_STARS
        if (params.features.relativisticJets) featureMask = featureMask or FEATURE_JETS
        if (params.features.gravitationalRedshift) featureMask = featureMask or FEATURE_REDSHIFT

        val zoom = camera.distance.coerceIn(XapkCameraState.MIN_DISTANCE, XapkCameraState.MAX_DISTANCE)
        val normalizedYaw = normalizeYaw(camera.yaw)
        val normalizedPitch = camera.pitch.coerceIn(XapkCameraState.MIN_PITCH, XapkCameraState.MAX_PITCH)
        val bloomIntensity = if (params.features.bloom) params.bloomIntensity else 0.0

        return XapkParameterBlock(
            floats = floatArrayOf(
                params.mass.toFloat().coerceIn(0.1f, 20.0f),
                params.spin.toFloat().coerceIn(0.0f, 0.99f),
                params.lensing.toFloat().coerceIn(0.0f, 2.0f),
                params.frameDraggingStrength.toFloat().coerceIn(0.0f, 4.0f),
                params.diskDensity.toFloat().coerceIn(0.0f, 10.0f),
                params.diskTemp.toFloat().coerceIn(2_000.0f, 25_000.0f),
                params.diskSize.toFloat().coerceIn(10.0f, 120.0f),
                params.diskScaleHeight.toFloat().coerceIn(0.05f, 0.45f),
                params.bloomThreshold.toFloat().coerceIn(0.0f, 4.0f),
                bloomIntensity.toFloat().coerceIn(0.0f, 2.0f),
                (if (params.reducedMotion) 0.0 else params.autoSpin).toFloat().coerceIn(-0.05f, 0.05f),
                normalizedYaw,
                normalizedPitch,
                zoom,
                quality.renderScale,
                0.0f
            ),
            integers = intArrayOf(
                quality.steps,
                featureMask,
                quality.mediumFlag,
                quality.highFlag
            )
        )
    }

    private fun normalizeYaw(yaw: Float): Float {
        if (!yaw.isFinite()) return XapkCameraState.DEFAULT_YAW
        val wrapped = yaw % 1.0f
        return if (wrapped < 0.0f) wrapped + 1.0f else wrapped
    }
}
