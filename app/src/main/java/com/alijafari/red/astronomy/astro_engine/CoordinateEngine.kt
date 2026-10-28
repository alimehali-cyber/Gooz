package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

object CoordinateEngine {
    data class Equatorial(val raDeg: Double, val decDeg: Double)
    data class Horizontal(val azimuthDeg: Double, val altitudeDeg: Double) {
        val azimuthCompassNameEn: String
            get() {
                val a = (azimuthDeg % 360.0 + 360.0) % 360.0
                return when {
                    a < 22.5 || a >= 337.5 -> "North"
                    a < 67.5 -> "Northeast"
                    a < 112.5 -> "East"
                    a < 157.5 -> "Southeast"
                    a < 202.5 -> "South"
                    a < 247.5 -> "Southwest"
                    a < 292.5 -> "West"
                    else -> "Northwest"
                }
            }

        val azimuthCompassNameFa: String
            get() {
                val a = (azimuthDeg % 360.0 + 360.0) % 360.0
                return when {
                    a < 22.5 || a >= 337.5 -> "شمال"
                    a < 67.5 -> "شمال‌شرق"
                    a < 112.5 -> "شرق"
                    a < 157.5 -> "جنوب‌شرق"
                    a < 202.5 -> "جنوب"
                    a < 247.5 -> "جنوب‌غرب"
                    a < 292.5 -> "غرب"
                    else -> "شمال‌غرب"
                }
            }
    }
    data class Galactic(val lDeg: Double, val bDeg: Double)
    data class Nutation(
        val deltaPsiDeg: Double,
        val deltaEpsDeg: Double,
        val meanObliquityDeg: Double,
        val trueObliquityDeg: Double
    )
    data class RiseSetTransit(
        val riseTimeStr: String,
        val transitTimeStr: String,
        val setTimeStr: String,
        val isCircumpolar: Boolean = false,
        val neverRises: Boolean = false
    )

    fun calculateNutation(jd: Double) = CoordinateEngineLegacy.calculateNutation(jd)
    fun equatorialToHorizontal(equatorial: Equatorial, lastDeg: Double, latitudeDeg: Double, observerElevationM: Double = 0.0): Horizontal {
        val legacyHoriz = CoordinateEngineLegacy.equatorialToHorizontal(
            CoordinateEngineLegacy.Equatorial(equatorial.raDeg, equatorial.decDeg),
            lastDeg, latitudeDeg, observerElevationM
        )
        return Horizontal(legacyHoriz.azimuthDeg, legacyHoriz.altitudeDeg)
    }
    fun horizontalToEquatorial(horizontal: Horizontal, lastDeg: Double, latitudeDeg: Double): Equatorial {
        val legacyEq = CoordinateEngineLegacy.horizontalToEquatorial(
            CoordinateEngineLegacy.Horizontal(horizontal.azimuthDeg, horizontal.altitudeDeg),
            lastDeg, latitudeDeg
        )
        return Equatorial(legacyEq.raDeg, legacyEq.decDeg)
    }
    fun geocentricToTopocentric(geocentric: Equatorial, geocentricDistanceKm: Double, lastDeg: Double, latitudeDeg: Double, elevationM: Double = 0.0): Equatorial {
        val legacyEq = CoordinateEngineLegacy.geocentricToTopocentric(
            CoordinateEngineLegacy.Equatorial(geocentric.raDeg, geocentric.decDeg),
            geocentricDistanceKm, lastDeg, latitudeDeg, elevationM
        )
        return Equatorial(legacyEq.raDeg, legacyEq.decDeg)
    }
    fun equatorialToGalactic(eq: Equatorial): Galactic {
        val legacyGal = CoordinateEngineLegacy.equatorialToGalactic(CoordinateEngineLegacy.Equatorial(eq.raDeg, eq.decDeg))
        return Galactic(legacyGal.lDeg, legacyGal.bDeg)
    }
    fun galacticToEquatorial(gal: Galactic): Equatorial {
        val legacyEq = CoordinateEngineLegacy.galacticToEquatorial(CoordinateEngineLegacy.Galactic(gal.lDeg, gal.bDeg))
        return Equatorial(legacyEq.raDeg, legacyEq.decDeg)
    }
    fun formatRA(raDeg: Double) = CoordinateEngineLegacy.formatRA(raDeg)
    fun formatDec(decDeg: Double) = CoordinateEngineLegacy.formatDec(decDeg)
    /**
     * @see CoordinateEngineLegacy.calculateRiseSetTransit for the meaning of
     * [timezoneId] and [horizonAltDeg]. Both default to the historical behaviour, so
     * callers that do not pass them are unaffected — but a caller that knows the
     * observer's timezone must pass it, otherwise the returned wall-clock strings are
     * rendered in Asia/Tehran.
     */
    fun calculateRiseSetTransit(
        raDeg: Double,
        decDeg: Double,
        latDeg: Double,
        lonDeg: Double,
        jd: Double = TimeEngine.getJulianDate(),
        isFa: Boolean = true,
        timezoneId: String? = null,
        horizonAltDeg: Double = CoordinateEngineLegacy.STAR_HORIZON_ALT_DEG
    ): RiseSetTransit {
        val legacyRst = CoordinateEngineLegacy.calculateRiseSetTransit(
            raDeg, decDeg, latDeg, lonDeg, jd, isFa, timezoneId, horizonAltDeg
        )
        return RiseSetTransit(legacyRst.riseTimeStr, legacyRst.transitTimeStr, legacyRst.setTimeStr, legacyRst.isCircumpolar, legacyRst.neverRises)
    }
    fun calculateAngularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double) = CoordinateEngineLegacy.calculateAngularSeparationDeg(ra1, dec1, ra2, dec2)

    /**
     * Calculates the parallactic angle q (in degrees) for an equatorial object at given local sidereal time and latitude.
     * q is the angle between the great circle through the celestial pole and the zenith.
     * Returns angle in degrees in range [-180, +180].
     */
    fun calculateParallacticAngleDeg(raDeg: Double, decDeg: Double, lastDeg: Double, latitudeDeg: Double): Double {
        var haDeg = lastDeg - raDeg
        haDeg %= 360.0
        if (haDeg < 0) haDeg += 360.0

        val haRad = Math.toRadians(haDeg)
        val decRad = Math.toRadians(decDeg)
        val latRad = Math.toRadians(latitudeDeg)

        val sinH = sin(haRad)
        val cosH = cos(haRad)
        val tanLat = tan(latRad)
        val cosDec = cos(decRad)
        val sinDec = sin(decRad)

        val num = sinH
        val den = tanLat * cosDec - sinDec * cosH
        val qRad = atan2(num, den)
        return Math.toDegrees(qRad)
    }

    /**
     * Calculates the position angle of the Moon's bright limb relative to the local zenith (Zenith Angle / Vertex Angle).
     * theta = chi - q (Meeus Chapter 14 & 48).
     * Returns normalized angle in [0, 360) degrees.
     */
    fun calculateZenithAngleDeg(brightLimbAngleDeg: Double, parallacticAngleDeg: Double): Double {
        var zenithAngle = (brightLimbAngleDeg - parallacticAngleDeg) % 360.0
        if (zenithAngle < 0) zenithAngle += 360.0
        return zenithAngle
    }

    /**
     * Calculates the apparent direction angle (in degrees) of the Moon's illuminated bright limb
     * in screen coordinates (+X = Right / greater Azimuth, +Y = Down / decreasing Altitude).
     *
     * Derived rigorously from the local tangent-plane spherical geometry from Moon to Sun.
     *
     * Returns angle in degrees in [-180, +180] where:
     *   -90° = pointing UP (towards Zenith)
     *     0° = pointing RIGHT (+X, eastward/greater azimuth)
     *   +90° = pointing DOWN (towards Nadir)
     *   180° = pointing LEFT (-X, westward/smaller azimuth)
     */
    fun calculateMoonLimbScreenAngleDeg(
        moonAzimuthDeg: Double,
        moonAltitudeDeg: Double,
        sunAzimuthDeg: Double,
        sunAltitudeDeg: Double
    ): Double {
        val azMRad = Math.toRadians(moonAzimuthDeg)
        val altMRad = Math.toRadians(moonAltitudeDeg)
        val azSRad = Math.toRadians(sunAzimuthDeg)
        val altSRad = Math.toRadians(sunAltitudeDeg)

        val dAz = azSRad - azMRad
        val vx = cos(altSRad) * sin(dAz)
        val vy = -(sin(altSRad) * cos(altMRad) - cos(altSRad) * sin(altMRad) * cos(dAz))

        return Math.toDegrees(atan2(vy, vx))
    }
}
