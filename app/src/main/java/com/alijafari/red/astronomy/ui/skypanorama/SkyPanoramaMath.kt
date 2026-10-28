package com.alijafari.red.astronomy.ui.skypanorama

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure (Android-free) orientation and texture-coordinate math for the sky panorama.
 *
 * Conventions (must stay in sync with [SkyPanoramaShaders.FRAGMENT_SHADER]):
 *  - Directions are unit vectors in the ICRF/J2000 equatorial frame: +x toward RA 0h / Dec 0,
 *    +y toward RA 6h / Dec 0, +z toward the north celestial pole.
 *  - Right ascension increases eastward; on the sky as seen from inside the sphere, east is to
 *    the LEFT and west to the RIGHT when north is up.
 *  - The NASA "celestial" star map is centred on RA 0h, RA increases to the LEFT, and north
 *    (Dec +90) is the TOP row. Texture coordinates therefore are:
 *        u = fract(0.5 - RA / 2pi)     (u = 0.5 at RA 0h, u decreases as RA increases)
 *        v = 0.5 - Dec / pi            (v = 0 at the north pole, the first image row)
 *    The first row of the decoded bitmap is uploaded as t = 0, so no vertical flip is applied.
 *  - The camera looks along `forward` (the zenith for the observer), with `up` the tangent
 *    pointing toward the north celestial pole and `right = forward x up`. Screen right is
 *    therefore WEST, which matches the NASA map's left/right orientation.
 */
object SkyPanoramaMath {

    /** Orthonormal camera basis expressed in equatorial (celestial) coordinates. */
    class ViewBasis(
        val forward: DoubleArray,
        val right: DoubleArray,
        val up: DoubleArray
    )

    /** Latitudes beyond this are clamped: at the exact pole the north tangent is undefined. */
    private const val MAX_ABS_LATITUDE_DEG = 89.9

    /** Quantization step for local sidereal time, in degrees (0.05 deg is about 12 s of time). */
    const val LST_QUANTUM_DEG = 0.05

    /** Equatorial unit vector for right ascension and declination in degrees. */
    fun directionFromRaDec(raDeg: Double, decDeg: Double): DoubleArray {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        val cd = cos(dec)
        return doubleArrayOf(cd * cos(ra), cd * sin(ra), sin(dec))
    }

    /**
     * Basis for a zenith-centred view: the camera looks at RA = LST, Dec = latitude, which is the
     * observer's zenith in equatorial coordinates.
     */
    fun zenithBasis(lstDeg: Double, latitudeDeg: Double): ViewBasis {
        val lat = latitudeDeg.coerceIn(-MAX_ABS_LATITUDE_DEG, MAX_ABS_LATITUDE_DEG)
        return basisForForward(directionFromRaDec(lstDeg, lat))
    }

    /**
     * Camera basis that looks along [forward] (an equatorial unit vector), with screen up tangent to the
     * north celestial pole. [forward] must not be parallel to the pole. Used by the Home zenith view.
     */
    fun basisForForward(forward: DoubleArray): ViewBasis =
        basisForForwardAndUp(forward, doubleArrayOf(0.0, 0.0, 1.0))

    /**
     * Camera basis that looks along [forward] with screen up tangent to [upHint]. The horizon-level backdrop passes
     * the observer's zenith here, so the sky is never rolled or upside down in either hemisphere. [upHint] must not
     * be parallel to [forward]. Right is always forward x up, so the basis is orthonormal and not mirrored.
     */
    fun basisForForwardAndUp(forward: DoubleArray, upHint: DoubleArray): ViewBasis {
        val zDotF = upHint[0] * forward[0] + upHint[1] * forward[1] + upHint[2] * forward[2]
        val upRaw = doubleArrayOf(
            upHint[0] - zDotF * forward[0],
            upHint[1] - zDotF * forward[1],
            upHint[2] - zDotF * forward[2]
        )
        val up = normalize(upRaw)
        val right = normalize(cross(forward, up))
        val upOrtho = cross(right, forward)
        return ViewBasis(forward, right, upOrtho)
    }

    /**
     * Equatorial unit vector for a horizontal direction. This is the exact inverse of
     * `CoordinateEngine.equatorialToHorizontal` (azimuth from north through east, hour angle = LST - RA),
     * so a direction computed by the engine maps back to the same equatorial point on the photograph.
     */
    fun directionFromHorizontal(azimuthDeg: Double, altitudeDeg: Double, latitudeDeg: Double, lstDeg: Double): DoubleArray {
        val t = Math.toRadians(lstDeg)
        val p = Math.toRadians(latitudeDeg)
        val a = Math.toRadians(azimuthDeg)
        val h = Math.toRadians(altitudeDeg)
        // Local frame: north, east and zenith expressed in equatorial coordinates.
        val n = doubleArrayOf(-sin(p) * cos(t), -sin(p) * sin(t), cos(p))
        val e = doubleArrayOf(-sin(t), cos(t), 0.0)
        val z = doubleArrayOf(cos(p) * cos(t), cos(p) * sin(t), sin(p))
        val north = cos(h) * cos(a)
        val east = cos(h) * sin(a)
        val up = sin(h)
        return DoubleArray(3) { i -> north * n[i] + east * e[i] + up * z[i] }
    }

    /**
     * Texture coordinates (u, v) for an equatorial direction. This mirrors the fragment shader
     * exactly, so unit tests can check the shader's mapping on the CPU.
     */
    fun uvForDirection(x: Double, y: Double, z: Double): DoubleArray {
        val ra = atan2(y, x)                       // -pi..pi, eastward positive
        val dec = asin(z.coerceIn(-1.0, 1.0))
        val u = fract(0.5 - ra / (2.0 * PI))
        val v = 0.5 - dec / PI
        return doubleArrayOf(u, v)
    }

    /**
     * Equatorial direction of a screen pixel. [ndcX]/[ndcY] are in -1..1 with +y up.
     * Mirrors the vertex/fragment shader pair.
     */
    fun cameraRay(basis: ViewBasis, ndcX: Double, ndcY: Double, tanHalfX: Double, tanHalfY: Double): DoubleArray {
        val f = basis.forward
        val r = basis.right
        val u = basis.up
        val v = doubleArrayOf(
            f[0] + ndcX * tanHalfX * r[0] + ndcY * tanHalfY * u[0],
            f[1] + ndcX * tanHalfX * r[1] + ndcY * tanHalfY * u[1],
            f[2] + ndcX * tanHalfX * r[2] + ndcY * tanHalfY * u[2]
        )
        return normalize(v)
    }

    /**
     * Mip level for a screen-uniform LOD. The panorama is sampled with an explicit LOD instead of
     * implicit derivatives, which avoids a seam where the RA branch cut at +/-pi would otherwise
     * produce a discontinuous derivative.
     *
     * Texels per pixel = (texels per radian) x (radians per pixel), where an equirectangular map
     * of width W has W / (2 pi) texels per radian in both directions.
     */
    fun texelLod(textureWidth: Int, fovYRad: Double, viewportHeightPx: Int): Float {
        if (textureWidth <= 0 || viewportHeightPx <= 0) return 0f
        val texelsPerRadian = textureWidth / (2.0 * PI)
        val radiansPerPixel = fovYRad / viewportHeightPx
        val texelsPerPixel = texelsPerRadian * radiansPerPixel
        return max(0.0, log2(texelsPerPixel)).toFloat()
    }

    /** Wraps a sidereal time into [0, 360) and snaps it to [LST_QUANTUM_DEG]. */
    fun quantizeLst(lstDeg: Double): Double {
        val wrapped = ((lstDeg % 360.0) + 360.0) % 360.0
        val snapped = Math.round(wrapped / LST_QUANTUM_DEG) * LST_QUANTUM_DEG
        return if (snapped >= 360.0) snapped - 360.0 else snapped
    }

    fun fract(x: Double): Double = x - Math.floor(x)

    fun cross(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    fun normalize(v: DoubleArray): DoubleArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        require(len > 1e-12) { "Cannot normalize a zero-length vector" }
        return doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }
}
