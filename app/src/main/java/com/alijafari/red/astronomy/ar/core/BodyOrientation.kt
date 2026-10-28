package com.alijafari.red.astronomy.ar.core

import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotational elements of the Moon and planets from the IAU Working Group on Cartographic Coordinates
 * and Rotational Elements (Archinal et al. 2011, 2018, Celest. Mech. Dyn. Astr. 109/130).
 *
 * α0, δ0: ICRF (≈ J2000) direction of the body's north pole; W: prime-meridian angle measured
 * eastward along the body's equator from the ascending node of that equator on the ICRF equator.
 * d = days and T = Julian centuries (TDB ≈ TT) from J2000.0, evaluated at the time the light left the
 * body.
 */
object BodyOrientation {

    enum class Body { MOON, MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE }

    /** Equatorial radius (km) and flattening, IAU 2015 values. */
    fun radiusKm(body: Body): Double = when (body) {
        Body.MOON -> 1737.4
        Body.MERCURY -> 2440.53
        Body.VENUS -> 6051.8
        Body.MARS -> 3396.19
        Body.JUPITER -> 71492.0
        Body.SATURN -> 60268.0
        Body.URANUS -> 25559.0
        Body.NEPTUNE -> 24764.0
    }

    fun flattening(body: Body): Double = when (body) {
        Body.MARS -> (3396.19 - 3376.20) / 3396.19
        Body.JUPITER -> (71492.0 - 66854.0) / 71492.0
        Body.SATURN -> (60268.0 - 54364.0) / 60268.0
        Body.URANUS -> (25559.0 - 24973.0) / 25559.0
        Body.NEPTUNE -> (24764.0 - 24341.0) / 24764.0
        else -> 0.0
    }

    /** Pole right ascension, declination and prime meridian W, all in degrees. */
    fun elements(body: Body, jdTt: Double): DoubleArray {
        val d = jdTt - 2451545.0
        val t = d / 36525.0
        return when (body) {
            Body.MOON -> moon(d, t)
            Body.MERCURY -> {
                val m1 = rad(174.7910857 + 4.092335 * d)
                val m2 = rad(349.5821714 + 8.184670 * d)
                val m3 = rad(164.3732571 + 12.277005 * d)
                val m4 = rad(339.1643429 + 16.369340 * d)
                val m5 = rad(153.9554286 + 20.461675 * d)
                doubleArrayOf(
                    281.0103 - 0.0328 * t,
                    61.4155 - 0.0049 * t,
                    329.5988 + 6.1385108 * d + 0.01067257 * sin(m1) - 0.00112309 * sin(m2) -
                        0.00011040 * sin(m3) - 0.00002539 * sin(m4) - 0.00000571 * sin(m5)
                )
            }
            Body.VENUS -> doubleArrayOf(272.76, 67.16, 160.20 - 1.4813688 * d)
            Body.MARS -> doubleArrayOf(317.68143 - 0.1061 * t, 52.88650 - 0.0609 * t, 176.630 + 350.89198226 * d)
            Body.JUPITER -> {
                val ja = rad(99.360714 + 4850.4046 * t)
                val jb = rad(175.895369 + 1191.9605 * t)
                val jc = rad(300.323162 + 262.5475 * t)
                val jd = rad(114.012305 + 6070.2476 * t)
                val je = rad(49.511251 + 64.3000 * t)
                doubleArrayOf(
                    268.056595 - 0.006499 * t + 0.000117 * sin(ja) + 0.000938 * sin(jb) +
                        0.001432 * sin(jc) + 0.000030 * sin(jd) + 0.002150 * sin(je),
                    64.495303 + 0.002413 * t + 0.000050 * cos(ja) + 0.000404 * cos(jb) +
                        0.000617 * cos(jc) - 0.000013 * cos(jd) + 0.000926 * cos(je),
                    284.95 + 870.5360000 * d
                )
            }
            Body.SATURN -> doubleArrayOf(40.589 - 0.036 * t, 83.537 - 0.004 * t, 38.90 + 810.7939024 * d)
            Body.URANUS -> doubleArrayOf(257.311, -15.175, 203.81 - 501.1600928 * d)
            Body.NEPTUNE -> {
                val n = rad(357.85 + 52.316 * t)
                doubleArrayOf(299.36 + 0.70 * sin(n), 43.46 - 0.51 * cos(n), 249.978 + 541.1397757 * d - 0.48 * sin(n))
            }
        }
    }

    private fun moon(d: Double, t: Double): DoubleArray {
        val e1 = rad(125.045 - 0.0529921 * d)
        val e2 = rad(250.089 - 0.1059842 * d)
        val e3 = rad(260.008 + 13.0120009 * d)
        val e4 = rad(176.625 + 13.3407154 * d)
        val e5 = rad(357.529 + 0.9856003 * d)
        val e6 = rad(311.589 + 26.4057084 * d)
        val e7 = rad(134.963 + 13.0649930 * d)
        val e8 = rad(276.617 + 0.3287146 * d)
        val e9 = rad(34.226 + 1.7484877 * d)
        val e10 = rad(15.134 - 0.1589763 * d)
        val e11 = rad(119.743 + 0.0036096 * d)
        val e12 = rad(239.961 + 0.1643573 * d)
        val e13 = rad(25.053 + 12.9590088 * d)
        val a0 = 269.9949 + 0.0031 * t - 3.8787 * sin(e1) - 0.1204 * sin(e2) + 0.0700 * sin(e3) -
            0.0172 * sin(e4) + 0.0072 * sin(e6) - 0.0052 * sin(e10) + 0.0043 * sin(e13)
        val d0 = 66.5392 + 0.0130 * t + 1.5419 * cos(e1) + 0.0239 * cos(e2) - 0.0278 * cos(e3) +
            0.0068 * cos(e4) - 0.0029 * cos(e6) + 0.0009 * cos(e7) + 0.0008 * cos(e10) - 0.0009 * cos(e13)
        val w = 38.3213 + 13.17635815 * d - 1.4e-12 * d * d + 3.5610 * sin(e1) + 0.1208 * sin(e2) -
            0.0642 * sin(e3) + 0.0158 * sin(e4) + 0.0252 * sin(e5) - 0.0066 * sin(e6) - 0.0047 * sin(e7) -
            0.0046 * sin(e8) + 0.0028 * sin(e9) + 0.0052 * sin(e10) + 0.0040 * sin(e11) +
            0.0019 * sin(e12) - 0.0044 * sin(e13)
        return doubleArrayOf(a0, d0, w)
    }

    /**
     * Body-fixed axes in the ICRF: [0..2] north pole P, [3..5] prime meridian on the equator X_b,
     * [6..8] Y_b = P × X_b (90° east).
     */
    fun bodyAxesIcrf(body: Body, jdTt: Double): DoubleArray {
        val e = elements(body, jdTt)
        val a0 = rad(e[0]); val d0 = rad(e[1]); val w = rad(e[2])
        val p = doubleArrayOf(cos(d0) * cos(a0), cos(d0) * sin(a0), sin(d0))
        // Ascending node of the body equator on the ICRF equator: Q = ẑ × P / |ẑ × P| (RA = α0 + 90°).
        val q = doubleArrayOf(-sin(a0), cos(a0), 0.0)
        val pq = V3.cross(p, q)
        val x = DoubleArray(3) { q[it] * cos(w) + pq[it] * sin(w) }
        val y = V3.cross(p, x)
        return doubleArrayOf(p[0], p[1], p[2], x[0], x[1], x[2], y[0], y[1], y[2])
    }

    private fun rad(deg: Double) = Math.toRadians(deg)
}
