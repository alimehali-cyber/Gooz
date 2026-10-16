package com.alijafari.red.astronomy.ar.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Allocation-light 3-vector / 3x3-matrix helpers for the AR sky pipeline.
 *
 * Matrices are row-major `DoubleArray(9)`; vectors are `DoubleArray(3)`. Rotation builders follow
 * the IAU SOFA convention exactly (frame rotations): `rotX(φ)` = R1(φ) and `rotZ(ψ)` = R3(ψ), i.e.
 *
 *     R1(φ) = | 1    0      0    |      R3(ψ) = |  cos ψ  sin ψ  0 |
 *             | 0  cos φ  sin φ  |              | −sin ψ  cos ψ  0 |
 *             | 0 −sin φ  cos φ  |              |    0      0    1 |
 *
 * so that SOFA formulas such as `iauFw2m` can be transcribed one-to-one.
 */
object M3 {

    fun identity(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** out = a · b (safe when out aliases a or b). */
    fun mul(a: DoubleArray, b: DoubleArray, out: DoubleArray = DoubleArray(9)): DoubleArray {
        val r0 = a[0] * b[0] + a[1] * b[3] + a[2] * b[6]
        val r1 = a[0] * b[1] + a[1] * b[4] + a[2] * b[7]
        val r2 = a[0] * b[2] + a[1] * b[5] + a[2] * b[8]
        val r3 = a[3] * b[0] + a[4] * b[3] + a[5] * b[6]
        val r4 = a[3] * b[1] + a[4] * b[4] + a[5] * b[7]
        val r5 = a[3] * b[2] + a[4] * b[5] + a[5] * b[8]
        val r6 = a[6] * b[0] + a[7] * b[3] + a[8] * b[6]
        val r7 = a[6] * b[1] + a[7] * b[4] + a[8] * b[7]
        val r8 = a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
        out[0] = r0; out[1] = r1; out[2] = r2
        out[3] = r3; out[4] = r4; out[5] = r5
        out[6] = r6; out[7] = r7; out[8] = r8
        return out
    }

    fun transpose(a: DoubleArray, out: DoubleArray = DoubleArray(9)): DoubleArray {
        val t1 = a[1]; val t2 = a[2]; val t5 = a[5]
        out[0] = a[0]; out[4] = a[4]; out[8] = a[8]
        out[1] = a[3]; out[3] = t1
        out[2] = a[6]; out[6] = t2
        out[5] = a[7]; out[7] = t5
        return out
    }

    /** out = m · v (safe when out aliases v). */
    fun mulVec(m: DoubleArray, v: DoubleArray, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val x = m[0] * v[0] + m[1] * v[1] + m[2] * v[2]
        val y = m[3] * v[0] + m[4] * v[1] + m[5] * v[2]
        val z = m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
        out[0] = x; out[1] = y; out[2] = z
        return out
    }

    /** out = mᵀ · v (safe when out aliases v). */
    fun mulTVec(m: DoubleArray, v: DoubleArray, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val x = m[0] * v[0] + m[3] * v[1] + m[6] * v[2]
        val y = m[1] * v[0] + m[4] * v[1] + m[7] * v[2]
        val z = m[2] * v[0] + m[5] * v[1] + m[8] * v[2]
        out[0] = x; out[1] = y; out[2] = z
        return out
    }

    /** SOFA R1(φ): frame rotation about the x axis. */
    fun rotX(phi: Double): DoubleArray {
        val c = cos(phi); val s = sin(phi)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, s, 0.0, -s, c)
    }

    /** SOFA R2(θ): frame rotation about the y axis. */
    fun rotY(theta: Double): DoubleArray {
        val c = cos(theta); val s = sin(theta)
        return doubleArrayOf(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c)
    }

    /** SOFA R3(ψ): frame rotation about the z axis. */
    fun rotZ(psi: Double): DoubleArray {
        val c = cos(psi); val s = sin(psi)
        return doubleArrayOf(c, s, 0.0, -s, c, 0.0, 0.0, 0.0, 1.0)
    }

    /** Largest absolute deviation of mᵀm from the identity (orthonormality check). */
    fun orthonormalityError(m: DoubleArray): Double {
        val t = transpose(m)
        val p = mul(t, m)
        var e = 0.0
        for (i in 0 until 9) {
            val ideal = if (i % 4 == 0) 1.0 else 0.0
            val d = kotlin.math.abs(p[i] - ideal)
            if (d > e) e = d
        }
        return e
    }
}

object V3 {
    fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    fun norm(a: DoubleArray): Double = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    fun cross(a: DoubleArray, b: DoubleArray, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val x = a[1] * b[2] - a[2] * b[1]
        val y = a[2] * b[0] - a[0] * b[2]
        val z = a[0] * b[1] - a[1] * b[0]
        out[0] = x; out[1] = y; out[2] = z
        return out
    }

    /** Normalizes in place; returns the original length. */
    fun normalizeInPlace(a: DoubleArray): Double {
        val n = norm(a)
        if (n > 0.0) {
            a[0] /= n; a[1] /= n; a[2] /= n
        }
        return n
    }

    /** Unit vector for spherical angles (radians): x toward (lon 0, lat 0), z toward lat +90°. */
    fun fromSpherical(lonRad: Double, latRad: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val cl = cos(latRad)
        out[0] = cl * cos(lonRad)
        out[1] = cl * sin(lonRad)
        out[2] = sin(latRad)
        return out
    }

    /** Longitude in [0, 2π) of a vector. */
    fun lonOf(v: DoubleArray): Double {
        val l = atan2(v[1], v[0])
        return if (l < 0.0) l + 2.0 * PI else l
    }

    /** Latitude in [−π/2, π/2] of a (not necessarily unit) vector. */
    fun latOf(v: DoubleArray): Double {
        val n = norm(v)
        if (n <= 0.0) return 0.0
        return asin((v[2] / n).coerceIn(-1.0, 1.0))
    }

    /** Angle between two unit vectors, robust for tiny and near-π separations. */
    fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
        val c = cross(a, b)
        return atan2(norm(c), dot(a, b))
    }
}

object AngleUtil {
    const val DEG = PI / 180.0
    const val RAD = 180.0 / PI
    const val ARCSEC = PI / (180.0 * 3600.0)
    const val TWO_PI = 2.0 * PI

    fun wrap2Pi(a: Double): Double {
        val r = a - TWO_PI * floor(a / TWO_PI)
        return if (r >= TWO_PI) r - TWO_PI else r
    }

    fun wrapPi(a: Double): Double {
        var r = wrap2Pi(a)
        if (r > PI) r -= TWO_PI
        return r
    }

    fun wrap360(a: Double): Double {
        val r = a - 360.0 * floor(a / 360.0)
        return if (r >= 360.0) r - 360.0 else r
    }

    /** Wraps to [−180, 180). */
    fun wrap180(a: Double): Double {
        val r = wrap360(a + 180.0) - 180.0
        return r
    }
}
