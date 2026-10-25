package com.alijafari.red.astronomy.astro_engine

import com.alijafari.red.astronomy.domain.CelestialObject
import kotlin.math.atan2
import kotlin.math.sqrt

data class FinderData(
    val targetObject: CelestialObject,
    val targetAzimuthDeg: Double,
    val targetAltitudeDeg: Double,
    val deltaAzimuthDeg: Float,
    val deltaAltitudeDeg: Float,
    val totalAngularDistanceDeg: Float,
    val arrowAngleRad: Float,
    val proximityFraction: Float,
    val instructionFa: String,
    val instructionEn: String,
    val isArrived: Boolean,
    val isBelowHorizon: Boolean
)

object FinderEngine {

    fun calculateFinderData(
        target: CelestialObject,
        phoneAzimuthDeg: Double,
        phoneAltitudeDeg: Double,
        userLat: Double,
        userLon: Double,
        userAltMeters: Double = 0.0,
        jd: Double = TimeEngine.getJulianDate()
    ): FinderData {
        val lastDeg = TimeEngine.getLAST(jd, userLon)
        val horiz = if (target.id == "sat_iss" || target.id == "iss" || target.type == com.alijafari.red.astronomy.domain.ObjectType.SATELLITE || target.id.startsWith("sat_")) {
            val timestampMs = TimeEngine.getTimestampFromJulianDate(jd)
            val state = SatelliteEngine.calculateSatelliteState(
                idOrNoradOrAlias = target.id,
                timestampMs = timestampMs,
                userLatDeg = userLat,
                userLonDeg = userLon,
                userAltMeters = userAltMeters
            )
            if (state != null) {
                CoordinateEngine.Horizontal(azimuthDeg = state.topocentric.azimuthDeg, altitudeDeg = state.topocentric.elevationDeg)
            } else {
                val satItem = SatelliteEngine.resolveSatelliteItem(target.id) ?: SatelliteCatalog.getById("iss_zarya")
                val topo = ISSEngine.calculateTopocentricPos(
                    timestampMs = timestampMs,
                    userLatDeg = userLat,
                    userLonDeg = userLon,
                    userAltMeters = userAltMeters,
                    tle = SatelliteEngine.getEffectiveTle(satItem)
                )
                CoordinateEngine.Horizontal(azimuthDeg = topo.azimuthDeg, altitudeDeg = topo.elevationDeg)
            }
        } else if (target.type == com.alijafari.red.astronomy.domain.ObjectType.SUN) {
            SunEngine.getSunAltAz(jd, userLat, userLon)
        } else if (target.id == "moon") {
            val mData = MoonEngine.calculateMoon(jd, userLat, userLon)
            CoordinateEngine.Horizontal(azimuthDeg = mData.azimuthDeg, altitudeDeg = mData.altitudeDeg)
        } else {
            CoordinateEngine.equatorialToHorizontal(
                CoordinateEngine.Equatorial(target.raDeg, target.decDeg),
                lastDeg,
                userLat
            )
        }

        val targetAz = horiz.azimuthDeg
        val targetAlt = horiz.altitudeDeg

        var dAz = (targetAz - phoneAzimuthDeg).toFloat()
        while (dAz > 180f) dAz -= 360f
        while (dAz < -180f) dAz += 360f

        val dAlt = (targetAlt - phoneAltitudeDeg).toFloat()

        // Spherical angular separation
        val targetAzRad = Math.toRadians(targetAz)
        val targetAltRad = Math.toRadians(targetAlt)
        val phoneAzRad = Math.toRadians(phoneAzimuthDeg)
        val phoneAltRad = Math.toRadians(phoneAltitudeDeg)
        val cosSep = kotlin.math.sin(targetAltRad) * kotlin.math.sin(phoneAltRad) +
                kotlin.math.cos(targetAltRad) * kotlin.math.cos(phoneAltRad) * kotlin.math.cos(targetAzRad - phoneAzRad)
        val totalDist = Math.toDegrees(kotlin.math.acos(cosSep.coerceIn(-1.0, 1.0))).toFloat()

        // Effective horizontal delta scaled by cos(targetAlt) to prevent high-altitude yaw distortion
        val effDAz = (dAz * kotlin.math.cos(targetAltRad)).toFloat()

        // Screen angle: 0 points UP, +PI/2 points RIGHT, etc.
        val arrowAngleRad = atan2(effDAz, dAlt)

        val isBelowHorizon = targetAlt < 0.0
        val isArrived = totalDist <= 2.0f
        val proximityFraction = (1.0f - (totalDist / 60.0f)).coerceIn(0.0f, 1.0f)

        val (instFa, instEn) = when {
            isBelowHorizon -> {
                "هشدار: این جرم در حال حاضر زیر افق قرار دارد (${String.format("%.1f", targetAlt)}°)" to
                        "Warning: Target is currently below horizon (${String.format("%.1f", targetAlt)}°)"
            }
            isArrived -> {
                "هدف دقیقاً در مرکز دید شماست!" to "Target acquired in center reticle!"
            }
            dAz > 15f && dAlt > 10f -> {
                "به سمت راست بچرخید و بالاتر را نگاه کنید" to "Turn Right & Pitch Up"
            }
            dAz > 15f && dAlt < -10f -> {
                "به سمت راست بچرخید و پایین‌تر را نگاه کنید" to "Turn Right & Pitch Down"
            }
            dAz > 15f -> {
                "به سمت راست بچرخید" to "Turn Right"
            }
            dAz < -15f && dAlt > 10f -> {
                "به سمت چپ بچرخید و بالاتر را نگاه کنید" to "Turn Left & Pitch Up"
            }
            dAz < -15f && dAlt < -10f -> {
                "به سمت چپ بچرخید و پایین‌تر را نگاه کنید" to "Turn Left & Pitch Down"
            }
            dAz < -15f -> {
                "به سمت چپ بچرخید" to "Turn Left"
            }
            dAlt > 10f -> {
                "مستقیماً به بالاتر نگاه کنید" to "Pitch Up"
            }
            dAlt < -10f -> {
                "مستقیماً به پایین‌تر نگاه کنید" to "Pitch Down"
            }
            else -> {
                "بسیار نزدیک شدید — مرکز هدف‌گیر را روی نقطه تنظیم کنید" to "Very close — center the reticle on target"
            }
        }

        return FinderData(
            targetObject = target,
            targetAzimuthDeg = targetAz,
            targetAltitudeDeg = targetAlt,
            deltaAzimuthDeg = dAz,
            deltaAltitudeDeg = dAlt,
            totalAngularDistanceDeg = totalDist,
            arrowAngleRad = arrowAngleRad,
            proximityFraction = proximityFraction,
            instructionFa = instFa,
            instructionEn = instEn,
            isArrived = isArrived,
            isBelowHorizon = isBelowHorizon
        )
    }
}
