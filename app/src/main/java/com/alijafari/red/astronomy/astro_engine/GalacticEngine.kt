package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

object GalacticEngine {

    data class GalacticCenterInfo(
        val raDeg: Double = 266.41683,   // Sgr A* RA J2000 = 17h 45m 40.04s
        val decDeg: Double = -29.00781,  // Sgr A* Dec J2000 = -29° 00' 28.2"
        val azimuthDeg: Double,
        val altitudeDeg: Double
    )

    data class GalacticPlanePoint(
        val galLongitudeDeg: Double,
        val raDeg: Double,
        val decDeg: Double,
        val azimuthDeg: Double,
        val altitudeDeg: Double
    )

    data class AndromedaInfo(
        val raDeg: Double = 10.6847,     // M31 RA J2000 = 00h 42m 44.3s
        val decDeg: Double = 41.2692,    // M31 Dec J2000 = +41° 16' 09"
        val distanceLightYears: Double = 2500000.0,
        val angularSizeMinutes: String = "190′ × 60′",
        val magnitude: Double = 3.44,
        val azimuthDeg: Double,
        val altitudeDeg: Double,
        val visibilityRating: String,    // e.g. "Optimal (Naked Eye)", "Binoculars", "Obscured"
        val isAboveHorizon: Boolean
    )

    /**
     * Computes real-time position of the Galactic Center (Sagittarius A*).
     */
    fun calculateGalacticCenter(
        jd: Double,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double = 0.0
    ): GalacticCenterInfo {
        val raDeg = 266.41683
        val decDeg = -29.00781
        val lastDeg = TimeEngine.getLAST(jd, userLonDeg)

        val horiz = CoordinateEngine.equatorialToHorizontal(
            equatorial = CoordinateEngine.Equatorial(raDeg, decDeg),
            lastDeg = lastDeg,
            latitudeDeg = userLatDeg,
            observerElevationM = elevationM
        )

        return GalacticCenterInfo(
            azimuthDeg = horiz.azimuthDeg,
            altitudeDeg = horiz.altitudeDeg
        )
    }

    private val GALACTIC_EQUATOR_EQ: List<Pair<Double, CoordinateEngine.Equatorial>> by lazy {
        (0..350 step 10).map { lDeg ->
            val l = lDeg.toDouble()
            l to CoordinateEngine.galacticToEquatorial(CoordinateEngine.Galactic(l, 0.0))
        }
    }

    /**
     * Generates 36 points tracing the Galactic Equator (b = 0°) across the sky for live AR view.
     */
    fun calculateGalacticPlanePoints(
        jd: Double,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double = 0.0
    ): List<GalacticPlanePoint> {
        val lastDeg = TimeEngine.getLAST(jd, userLonDeg)
        return calculateGalacticPlanePointsWithLast(lastDeg, userLatDeg, elevationM)
    }

    /**
     * Generates 36 points tracing the Galactic Equator (b = 0°) using a precomputed LAST angle.
     */
    fun calculateGalacticPlanePointsWithLast(
        lastDeg: Double,
        userLatDeg: Double,
        elevationM: Double = 0.0
    ): List<GalacticPlanePoint> {
        return GALACTIC_EQUATOR_EQ.map { (lDeg, eq) ->
            val horiz = CoordinateEngine.equatorialToHorizontal(
                equatorial = eq,
                lastDeg = lastDeg,
                latitudeDeg = userLatDeg,
                observerElevationM = elevationM
            )

            GalacticPlanePoint(
                galLongitudeDeg = lDeg,
                raDeg = eq.raDeg,
                decDeg = eq.decDeg,
                azimuthDeg = horiz.azimuthDeg,
                altitudeDeg = horiz.altitudeDeg
            )
        }
    }

    /**
     * Computes high-precision observational data for the Andromeda Galaxy (M31).
     */
    fun calculateAndromeda(
        jd: Double,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double = 0.0
    ): AndromedaInfo {
        val raDeg = 10.6847
        val decDeg = 41.2692
        val lastDeg = TimeEngine.getLAST(jd, userLonDeg)

        val horiz = CoordinateEngine.equatorialToHorizontal(
            equatorial = CoordinateEngine.Equatorial(raDeg, decDeg),
            lastDeg = lastDeg,
            latitudeDeg = userLatDeg,
            observerElevationM = elevationM
        )

        val sunAlt = SunEngine.getSunAltitude(((jd - 2440587.5) * 86400000.0).toLong(), userLatDeg, userLonDeg)

        val rating = when {
            horiz.altitudeDeg < 0.0 -> "Below Horizon"
            sunAlt > -6.0 -> "Daylight / Twilight Blur"
            horiz.altitudeDeg > 30.0 && sunAlt < -18.0 -> "Optimal Darkness (Naked Eye)"
            horiz.altitudeDeg > 10.0 -> "Visible with Binoculars"
            else -> "Low on Horizon"
        }

        return AndromedaInfo(
            azimuthDeg = horiz.azimuthDeg,
            altitudeDeg = horiz.altitudeDeg,
            visibilityRating = rating,
            isAboveHorizon = horiz.altitudeDeg > 0.0
        )
    }
}
