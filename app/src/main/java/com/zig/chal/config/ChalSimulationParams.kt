package com.zig.chal.config

import com.zig.chal.physics.ChalPhysicsConstants
import kotlin.math.tan
import java.io.Serializable

/**
 * Core simulation types.
 *
 * Shared value model for the XAPK-backed renderer and Chal's explicitly approximate GLES fallback.
 * XAPK-facing defaults/ranges are mirrored here so persisted controls can move between backends.
 */
data class ChalSimulationParams(
    val mass: Double = ChalSimulationConfig.MASS.default,
    /** Spin is the dimensionless Kerr parameter chi; the XAPK UI range is [0, 0.99]. */
    val spin: Double = ChalSimulationConfig.SPIN.default,
    val diskDensity: Double = ChalSimulationConfig.DISK_DENSITY.default,
    val diskTemp: Double = ChalSimulationConfig.DISK_TEMP.default,
    val lensing: Double = ChalSimulationConfig.LENSING.default,
    val paused: Boolean = false,
    val zoom: Double = ChalSimulationConfig.ZOOM.default,
    /** Legacy GLES 60 Hz increment; convert for its rad/s display. Native keeps engine units. */
    val autoSpin: Double = ChalSimulationConfig.AUTO_SPIN.default,
    val diskSize: Double = ChalSimulationConfig.DISK_SIZE.default,
    val diskScaleHeight: Double = ChalSimulationConfig.DISK_SCALE_HEIGHT.default,
    val frameDraggingStrength: Double = ChalSimulationConfig.FRAME_DRAGGING.default,
    val bloomThreshold: Double = ChalSimulationConfig.BLOOM_THRESHOLD.default,
    val bloomIntensity: Double = ChalSimulationConfig.BLOOM_INTENSITY.default,
    val adaptiveResolution: Boolean = true,
    val automaticQuality: Boolean = false,
    val batterySaver: Boolean = false,
    val reducedMotion: Boolean = false,
    val renderScale: Double = ChalSimulationConfig.RENDER_SCALE.default,
    val features: ChalFeatureToggles = ChalSimulationConfig.FEATURES,
    val performancePreset: ChalPresetName = ChalSimulationConfig.DEFAULT_PRESET_MODE,
    val cameraYaw: Double = XapkCameraState.DEFAULT_YAW.toDouble(),
    val verticalAngle: Double = ChalSimulationConfig.VERTICAL_ANGLE.default
) : Serializable {
    companion object {
        /** Reference default (desktop web canvas). */
        val DEFAULT_PARAMS = ChalSimulationParams()

        /** Legacy Chal-only startup profile used while constructing the GLES fallback. */
        val MOBILE_PARAMS = ChalSimulationParams(
            renderScale = ChalPerformanceConfig.Mobile.START_SCALE,
            features = ChalFeatures.getPreset(ChalPresetName.BALANCED),
            performancePreset = ChalPresetName.BALANCED
        )
    }
}

/** Normalized pointer state handed to the shader as `u_mouse`. */
data class ChalMouseState(val x: Double, val y: Double)

/**
 * Camera limits and initial framing.
 *
 * These live here (rather than in the renderer) so the JVM unit tests can gate the framing math
 * without a GL context.
 */
object ChalCameraConfig {

    /** `DEFAULT_ZOOM = SIMULATION_CONFIG.zoom.default`. */
    const val DEFAULT_ZOOM: Double = 100.0
    const val MIN_ZOOM: Double = 1.5
    const val MAX_ZOOM: Double = 100.0
    const val FOV_DEGREES: Double = 45.0

    /** 70% of viewport (60-80% range). */
    const val TARGET_VIEWPORT_COVERAGE: Double = 0.7

    /** Outer disk is ~12x event horizon. */
    const val ACCRETION_DISK_OUTER_RADIUS_MULTIPLIER: Double = 12.0

    /** Default auto-spin if not provided. */
    val DEFAULT_AUTO_SPIN: Double = ChalSimulationConfig.AUTO_SPIN.default

    /** `(SIMULATION_CONFIG.verticalAngle.default * PI) / 180`. */
    val DEFAULT_VERTICAL_ANGLE: Double = ChalSimulationConfig.VERTICAL_ANGLE.default * Math.PI / 180.0

    /** Momentum decay applied per camera tick. */
    const val DAMPING: Double = 0.92

    /**
     * Calculate optimal initial zoom distance based on black hole mass and viewport dimensions.
     *
     * Direct port of `calculateInitialZoom(mass, viewportWidth, viewportHeight)`.
     */
    fun calculateInitialZoom(mass: Double, viewportWidth: Double, viewportHeight: Double): Double {
        if (!mass.isFinite() || mass <= 0.0) return DEFAULT_ZOOM
        if (!viewportWidth.isFinite() || !viewportHeight.isFinite() ||
            viewportWidth <= 0.0 || viewportHeight <= 0.0
        ) {
            return DEFAULT_ZOOM
        }

        return try {
            val aspectRatio = viewportWidth / viewportHeight
            val eventHorizonRadius = mass * ChalPhysicsConstants.SCHWARZSCHILD_RADIUS_SOLAR
            val diskOuterRadius = eventHorizonRadius * ACCRETION_DISK_OUTER_RADIUS_MULTIPLIER
            val fovRadians = FOV_DEGREES * Math.PI / 180.0
            val halfFov = fovRadians / 2.0
            val tanHalfFov = tan(halfFov)

            if (tanHalfFov <= 0.0) return DEFAULT_ZOOM

            val baseDistance = diskOuterRadius / tanHalfFov
            val adjustedDistance = baseDistance / TARGET_VIEWPORT_COVERAGE
            var aspectRatioAdjustment = 1.0
            if (aspectRatio < 1.0) {
                aspectRatioAdjustment = 1.0 / aspectRatio
            }

            val finalDistance = adjustedDistance * aspectRatioAdjustment
            val normalizedZoom = (finalDistance / diskOuterRadius) * (mass * 3.5)

            // `clampAndValidate` in the reference returns the fallback for any non-finite value.
            if (!normalizedZoom.isFinite()) DEFAULT_ZOOM
            else normalizedZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        } catch (_: Exception) {
            DEFAULT_ZOOM
        }
    }
}
