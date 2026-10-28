package com.alijafari.red.astronomy.ui.skypanorama

import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometric horizontal coordinates: the same formulas as the engine, without atmospheric refraction.
 *
 * The engine raises altitude by refraction (Bennett-style), about 0.7 degrees near the horizon and about 0.04
 * degrees at 24 degrees. Home and the backdrop both use the engine, so refraction is shared and is not a camera error. Exact
 * camera checks therefore use this geometric value, and [refractionIsTheOnlyDifference] bounds the engine's shift.
 */
object GeometricHorizontal {

    fun of(raDeg: Double, decDeg: Double, lstDeg: Double, latitudeDeg: Double): CoordinateEngine.Horizontal {
        val ha = Math.toRadians(lstDeg - raDeg)
        val dec = Math.toRadians(decDeg)
        val lat = Math.toRadians(latitudeDeg)
        val sinAlt = sin(dec) * sin(lat) + cos(dec) * cos(lat) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val cosAz = (sin(dec) - sin(lat) * sin(alt)) / (cos(lat) * cos(alt)).coerceAtLeast(1e-9)
        val sinAz = -cos(dec) * sin(ha) / cos(alt).coerceAtLeast(1e-9)
        var az = Math.toDegrees(atan2(sinAz, cosAz))
        if (az < 0) az += 360.0
        return CoordinateEngine.Horizontal(az, Math.toDegrees(alt))
    }

    /**
     * Asserts the engine's altitude differs from the geometric altitude only by refraction: never below, and by at
     * most 1.0 degree. Below -1.5 degrees the engine applies no refraction, so the two must be equal.
     */
    fun refractionIsTheOnlyDifference(
        raDeg: Double,
        decDeg: Double,
        lstDeg: Double,
        latitudeDeg: Double,
        onFailure: (String) -> Unit
    ) {
        val geometric = of(raDeg, decDeg, lstDeg, latitudeDeg)
        val engine = CoordinateEngine.equatorialToHorizontal(
            CoordinateEngine.Equatorial(raDeg, decDeg), lstDeg, latitudeDeg
        )
        val diff = engine.altitudeDeg - geometric.altitudeDeg
        val ok = if (geometric.altitudeDeg > -1.5) diff > -1e-9 && diff < 1.0 else abs(diff) < 1e-9
        if (!ok) onFailure("engine altitude $diff deg from geometric at alt ${geometric.altitudeDeg}")
    }
}
