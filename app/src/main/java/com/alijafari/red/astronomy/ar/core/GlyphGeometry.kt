package com.alijafari.red.astronomy.ar.core

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Builds the sprite-frame geometry (see [GlyphRaster]) of a solar-system body from apparent positions
 * of date and the IAU rotation model.
 */
object GlyphGeometry {

    /** Speed of light in AU per day. */
    private const val C_AU_PER_DAY = 173.1446326846693

    class Result(
        val geometry: GlyphRaster.Geometry,
        /** Phase angle Sun–body–observer, degrees. */
        val phaseAngleDeg: Double,
        /** Illuminated fraction of the disc. */
        val illuminatedFraction: Double,
        /** Position angle (degrees, from celestial north through east) of the bright limb's midpoint. */
        val brightLimbPaDeg: Double,
        /** Position angle of the body's north pole, degrees. */
        val polePaDeg: Double,
        /** Sub-observer planetographic latitude (Saturn: ring opening B), degrees. */
        val subObserverLatDeg: Double
    )

    /**
     * @param body           which body
     * @param jdTt           observation time (TT)
     * @param bodyTod        unit vector observer → body, true equator of date
     * @param bodyDistAu     observer–body distance (AU)
     * @param sunTod         unit vector observer → Sun, true equator of date
     * @param sunDistAu      observer–Sun distance (AU)
     * @param npb            GCRS → true-of-date rotation (ArSkyFrame.npbMatrix)
     * @param earthshine     relative earthshine for the Moon (0 for planets)
     * @param tint           atmospheric extinction colour
     */
    fun build(
        body: BodyOrientation.Body,
        jdTt: Double,
        bodyTod: DoubleArray,
        bodyDistAu: Double,
        sunTod: DoubleArray,
        sunDistAu: Double,
        npb: DoubleArray,
        earthshine: Double = 0.0,
        tint: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)
    ): Result {
        val u = bodyTod.copyOf(); V3.normalizeInPlace(u)
        // Tangent basis on the sky at the body: east e, north n (e × n = u).
        val zAxis = doubleArrayOf(0.0, 0.0, 1.0)
        val e = V3.cross(zAxis, u)
        if (V3.norm(e) < 1e-9) { e[0] = 0.0; e[1] = 1.0; e[2] = 0.0 }
        V3.normalizeInPlace(e)
        val n = V3.cross(u, e)

        // Orientation at the time the light left the body.
        val lightTime = bodyDistAu / C_AU_PER_DAY
        val axesIcrf = BodyOrientation.bodyAxesIcrf(body, jdTt - lightTime)
        val pole = M3.mulVec(npb, doubleArrayOf(axesIcrf[0], axesIcrf[1], axesIcrf[2]))
        val pm = M3.mulVec(npb, doubleArrayOf(axesIcrf[3], axesIcrf[4], axesIcrf[5]))

        // Direction body → Sun.
        val s = DoubleArray(3) { sunTod[it] * sunDistAu - u[it] * bodyDistAu }
        V3.normalizeInPlace(s)

        fun toSprite(v: DoubleArray): DoubleArray = doubleArrayOf(-V3.dot(v, e), V3.dot(v, n), -V3.dot(v, u))

        val geo = GlyphRaster.Geometry(
            pole = toSprite(pole),
            primeMeridian = toSprite(pm),
            sunDir = toSprite(s),
            earthshine = earthshine,
            tint = tint
        )
        val toObserver = doubleArrayOf(-u[0], -u[1], -u[2])
        val phase = Math.toDegrees(acos(V3.dot(s, toObserver).coerceIn(-1.0, 1.0)))
        val k = (1.0 + kotlin.math.cos(Math.toRadians(phase))) / 2.0
        // Position angles: PA = atan2(east component, north component).
        val limbPa = AngleUtil.wrap360(Math.toDegrees(atan2(V3.dot(s, e), V3.dot(s, n))))
        val polePa = AngleUtil.wrap360(Math.toDegrees(atan2(V3.dot(pole, e), V3.dot(pole, n))))
        val subLat = Math.toDegrees(asin(V3.dot(pole, toObserver).coerceIn(-1.0, 1.0)))
        return Result(geo, phase, k, limbPa, polePa, subLat)
    }

    /** Angular radius in arcseconds of a body of equatorial radius [radiusKm] at [distAu]. */
    fun angularRadiusArcsec(radiusKm: Double, distAu: Double): Double {
        val d = distAu * 149597870.7
        return Math.toDegrees(asin((radiusKm / d).coerceIn(0.0, 1.0))) * 3600.0
    }
}
