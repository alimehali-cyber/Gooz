package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset

/**
 * Shared HeroSky Projection Model for RED Astronomical Canvas.
 *
 * Defines the unified equator-facing visual projection:
 * - In Northern Hemisphere (latitude >= 0°):
 *   Viewer faces South (center = 180°).
 *   East (90°) is at Left (x = 0.25 * width), South (180°) is Center (x = 0.5 * width),
 *   West (270°) is at Right (x = 0.75 * width). North (0°/360°) is at the seam behind the viewer.
 *   Normal daytime diurnal motion flows: East -> South -> West (LEFT -> CENTER -> RIGHT).
 *
 * - In Southern Hemisphere (latitude < 0°):
 *   Viewer faces North (center = 0°).
 *   West (270°) is at Left (x = 0.25 * width), North (0°/360°) is Center (x = 0.5 * width),
 *   East (90°) is at Right (x = 0.75 * width). South (180°) is at the seam behind the viewer.
 *   Normal daytime diurnal motion flows: East -> North -> West (RIGHT -> CENTER -> LEFT).
 *
 * - Equator (latitude == 0°):
 *   Stable default facing South (center = 180°).
 *
 * - The projection preserves true astronomical orientations without mirroring celestial bodies.
 * - Altitude 0°: Horizon (altitude = 0° maps to horizonY = canvasHeight * 0.85).
 * - Altitude +90°: Zenith (altitude = +90° maps to topMargin = 24dp/px).
 * - Altitudes < 0° scale continuously below the horizon line.
 */
object HeroSkyProjection {

    const val HORIZON_FRACTION = 0.85f
    const val TOP_MARGIN_PX = 24.0f

    /**
     * Normalizes an angle into the continuous signed range [-180°, +180°].
     */
    fun normalizeSignedAngle(deg: Double): Double {
        return (((deg % 360.0) + 540.0) % 360.0) - 180.0
    }

    /**
     * Projects spherical Horizontal coordinates (azimuth, altitude) into screen space (x, y)
     * for Hero Sky panoramic canvas, oriented towards the equator based on observer latitude.
     *
     * @param azimuthDeg Azimuth in degrees [0, 360) where 0 = North, 90 = East, 180 = South, 270 = West
     * @param altitudeDeg Altitude in degrees [-90, +90] where 0 = Horizon, +90 = Zenith
     * @param canvasWidth Width of the drawing canvas in pixels
     * @param canvasHeight Height of the drawing canvas in pixels
     * @param latitudeDeg Observer latitude in degrees [-90, +90]
     * @return Screen coordinate Offset(x, y)
     */
    fun project(
        azimuthDeg: Double,
        altitudeDeg: Double,
        canvasWidth: Float,
        canvasHeight: Float,
        latitudeDeg: Double = 0.0,
        horizonFraction: Float = HORIZON_FRACTION
    ): Offset {
        if (canvasWidth <= 0f || canvasHeight <= 0f) {
            return Offset.Zero
        }

        // Calculate continuous relative azimuth centered on the equator-facing direction:
        // - Northern Hemisphere (latitude >= 0°): Center is South (180°).
        //   relAz = normalizeSignedAngle(azimuthDeg - 180.0)
        //   East (90°) -> -90° (Left), South (180°) -> 0° (Center), West (270°) -> +90° (Right)
        // - Southern Hemisphere (latitude < 0°): Center is North (0°).
        //   relAz = normalizeSignedAngle(azimuthDeg)
        //   West (270°) -> -90° (Left), North (0°) -> 0° (Center), East (90°) -> +90° (Right)
        val relAz = if (latitudeDeg >= 0.0) {
            normalizeSignedAngle(azimuthDeg - 180.0)
        } else {
            normalizeSignedAngle(azimuthDeg)
        }

        val x = ((0.5 + relAz / 360.0) * canvasWidth).toFloat()

        val horizonY = canvasHeight * horizonFraction.coerceIn(0.05f, 0.98f)
        val scale = (horizonY - TOP_MARGIN_PX) / 90.0f
        val y = horizonY - (altitudeDeg.toFloat() * scale)

        return Offset(x, y)
    }

    /**
     * Calculates the shortest Euclidean screen distance between a tap position and a projected celestial object,
     * accounting for continuous horizontal cylindrical wraparound at the canvas boundary.
     */
    fun screenDistance(
        tapPos: Offset,
        projectedPos: Offset,
        canvasWidth: Float
    ): Float {
        if (canvasWidth <= 0f) return Float.MAX_VALUE
        val dxDirect = kotlin.math.abs(tapPos.x - projectedPos.x)
        val dxWrapped = kotlin.math.min(dxDirect, canvasWidth - dxDirect)
        val dy = tapPos.y - projectedPos.y
        return kotlin.math.hypot(dxWrapped, dy)
    }

    /**
     * Angular azimuth shortest distance in degrees between two azimuths, correctly handling 0°/360° wraparound.
     * E.g. distance between 359° and 1° is 2°.
     */
    fun azimuthDistanceDeg(az1: Double, az2: Double): Double {
        val diff = kotlin.math.abs(az1 - az2) % 360.0
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /**
     * Returns the horizon line Y coordinate on the canvas.
     */
    fun getHorizonY(canvasHeight: Float, horizonFraction: Float = HORIZON_FRACTION): Float {
        return canvasHeight * horizonFraction.coerceIn(0.05f, 0.98f)
    }
}
