package com.alijafari.red.astronomy.ar.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * World Magnetic Model 2025 (WMM2025), NOAA NCEI / British Geological Survey, valid 2025.0–2030.0.
 *
 * The Gauss coefficients below are the official WMM.COF released 11/13/2024 ("2025.0 WMM-2025"),
 * transcribed programmatically from the coefficient file shipped in two independent packages
 * (`pygeomag` 1.1.0 and `pywmm` 1.1.1, byte-identical coefficient tables). The WMM coefficients
 * and algorithm are in the public domain.
 *
 * The algorithm follows the WMM technical report and the NOAA reference implementation
 * (GeomagnetismLibrary.c: MAG_GeodeticToSpherical, MAG_PcupLow, MAG_Summation,
 * MAG_RotateMagneticVector). It is validated against every row of NOAA's official
 * WMM2025_TEST_VALUES.txt in `Wmm2025Test`.
 *
 * Android's own [android.hardware.GeomagneticField] still embeds WMM-2020 (single precision,
 * "valid until 2025"), which is why the AR compass uses this implementation for declination,
 * inclination and total intensity.
 */
object Wmm2025 {
    const val EPOCH = 2025.0
    const val VALID_FROM = 2025.0
    const val VALID_UNTIL = 2030.0
    private const val N_MAX = 12
    private const val REFERENCE_RADIUS_KM = 6371.2
    private const val WGS84_A_KM = 6378.137
    private const val WGS84_F = 1.0 / 298.257223563

    /** n, m, g, h (nT), ġ, ḣ (nT/yr) for n = 1..12, m = 0..n. */
    private val COEFFICIENTS = doubleArrayOf(
        1.0, 0.0, -29351.8, 0.0, 12.0, 0.0,
        1.0, 1.0, -1410.8, 4545.4, 9.7, -21.5,
        2.0, 0.0, -2556.6, 0.0, -11.6, 0.0,
        2.0, 1.0, 2951.1, -3133.6, -5.2, -27.7,
        2.0, 2.0, 1649.3, -815.1, -8.0, -12.1,
        3.0, 0.0, 1361.0, 0.0, -1.3, 0.0,
        3.0, 1.0, -2404.1, -56.6, -4.2, 4.0,
        3.0, 2.0, 1243.8, 237.5, 0.4, -0.3,
        3.0, 3.0, 453.6, -549.5, -15.6, -4.1,
        4.0, 0.0, 895.0, 0.0, -1.6, 0.0,
        4.0, 1.0, 799.5, 278.6, -2.4, -1.1,
        4.0, 2.0, 55.7, -133.9, -6.0, 4.1,
        4.0, 3.0, -281.1, 212.0, 5.6, 1.6,
        4.0, 4.0, 12.1, -375.6, -7.0, -4.4,
        5.0, 0.0, -233.2, 0.0, 0.6, 0.0,
        5.0, 1.0, 368.9, 45.4, 1.4, -0.5,
        5.0, 2.0, 187.2, 220.2, 0.0, 2.2,
        5.0, 3.0, -138.7, -122.9, 0.6, 0.4,
        5.0, 4.0, -142.0, 43.0, 2.2, 1.7,
        5.0, 5.0, 20.9, 106.1, 0.9, 1.9,
        6.0, 0.0, 64.4, 0.0, -0.2, 0.0,
        6.0, 1.0, 63.8, -18.4, -0.4, 0.3,
        6.0, 2.0, 76.9, 16.8, 0.9, -1.6,
        6.0, 3.0, -115.7, 48.8, 1.2, -0.4,
        6.0, 4.0, -40.9, -59.8, -0.9, 0.9,
        6.0, 5.0, 14.9, 10.9, 0.3, 0.7,
        6.0, 6.0, -60.7, 72.7, 0.9, 0.9,
        7.0, 0.0, 79.5, 0.0, -0.0, 0.0,
        7.0, 1.0, -77.0, -48.9, -0.1, 0.6,
        7.0, 2.0, -8.8, -14.4, -0.1, 0.5,
        7.0, 3.0, 59.3, -1.0, 0.5, -0.8,
        7.0, 4.0, 15.8, 23.4, -0.1, 0.0,
        7.0, 5.0, 2.5, -7.4, -0.8, -1.0,
        7.0, 6.0, -11.1, -25.1, -0.8, 0.6,
        7.0, 7.0, 14.2, -2.3, 0.8, -0.2,
        8.0, 0.0, 23.2, 0.0, -0.1, 0.0,
        8.0, 1.0, 10.8, 7.1, 0.2, -0.2,
        8.0, 2.0, -17.5, -12.6, 0.0, 0.5,
        8.0, 3.0, 2.0, 11.4, 0.5, -0.4,
        8.0, 4.0, -21.7, -9.7, -0.1, 0.4,
        8.0, 5.0, 16.9, 12.7, 0.3, -0.5,
        8.0, 6.0, 15.0, 0.7, 0.2, -0.6,
        8.0, 7.0, -16.8, -5.2, -0.0, 0.3,
        8.0, 8.0, 0.9, 3.9, 0.2, 0.2,
        9.0, 0.0, 4.6, 0.0, -0.0, 0.0,
        9.0, 1.0, 7.8, -24.8, -0.1, -0.3,
        9.0, 2.0, 3.0, 12.2, 0.1, 0.3,
        9.0, 3.0, -0.2, 8.3, 0.3, -0.3,
        9.0, 4.0, -2.5, -3.3, -0.3, 0.3,
        9.0, 5.0, -13.1, -5.2, 0.0, 0.2,
        9.0, 6.0, 2.4, 7.2, 0.3, -0.1,
        9.0, 7.0, 8.6, -0.6, -0.1, -0.2,
        9.0, 8.0, -8.7, 0.8, 0.1, 0.4,
        9.0, 9.0, -12.9, 10.0, -0.1, 0.1,
        10.0, 0.0, -1.3, 0.0, 0.1, 0.0,
        10.0, 1.0, -6.4, 3.3, 0.0, 0.0,
        10.0, 2.0, 0.2, 0.0, 0.1, -0.0,
        10.0, 3.0, 2.0, 2.4, 0.1, -0.2,
        10.0, 4.0, -1.0, 5.3, -0.0, 0.1,
        10.0, 5.0, -0.6, -9.1, -0.3, -0.1,
        10.0, 6.0, -0.9, 0.4, 0.0, 0.1,
        10.0, 7.0, 1.5, -4.2, -0.1, 0.0,
        10.0, 8.0, 0.9, -3.8, -0.1, -0.1,
        10.0, 9.0, -2.7, 0.9, -0.0, 0.2,
        10.0, 10.0, -3.9, -9.1, -0.0, -0.0,
        11.0, 0.0, 2.9, 0.0, 0.0, 0.0,
        11.0, 1.0, -1.5, 0.0, -0.0, -0.0,
        11.0, 2.0, -2.5, 2.9, 0.0, 0.1,
        11.0, 3.0, 2.4, -0.6, 0.0, -0.0,
        11.0, 4.0, -0.6, 0.2, 0.0, 0.1,
        11.0, 5.0, -0.1, 0.5, -0.1, -0.0,
        11.0, 6.0, -0.6, -0.3, 0.0, -0.0,
        11.0, 7.0, -0.1, -1.2, -0.0, 0.1,
        11.0, 8.0, 1.1, -1.7, -0.1, -0.0,
        11.0, 9.0, -1.0, -2.9, -0.1, 0.0,
        11.0, 10.0, -0.2, -1.8, -0.1, 0.0,
        11.0, 11.0, 2.6, -2.3, -0.1, 0.0,
        12.0, 0.0, -2.0, 0.0, 0.0, 0.0,
        12.0, 1.0, -0.2, -1.3, 0.0, -0.0,
        12.0, 2.0, 0.3, 0.7, -0.0, 0.0,
        12.0, 3.0, 1.2, 1.0, -0.0, -0.1,
        12.0, 4.0, -1.3, -1.4, -0.0, 0.1,
        12.0, 5.0, 0.6, -0.0, -0.0, -0.0,
        12.0, 6.0, 0.6, 0.6, 0.1, -0.0,
        12.0, 7.0, 0.5, -0.1, -0.0, -0.0,
        12.0, 8.0, -0.1, 0.8, 0.0, 0.0,
        12.0, 9.0, -0.4, 0.1, 0.0, -0.0,
        12.0, 10.0, -0.2, -1.0, -0.1, -0.0,
        12.0, 11.0, -1.3, 0.1, -0.0, 0.0,
        12.0, 12.0, -0.7, 0.2, -0.1, -0.1
    )

    private val gMain = DoubleArray((N_MAX + 1) * (N_MAX + 2) / 2)
    private val hMain = DoubleArray(gMain.size)
    private val gDot = DoubleArray(gMain.size)
    private val hDot = DoubleArray(gMain.size)

    init {
        var i = 0
        while (i < COEFFICIENTS.size) {
            val n = COEFFICIENTS[i].toInt()
            val m = COEFFICIENTS[i + 1].toInt()
            val idx = n * (n + 1) / 2 + m
            gMain[idx] = COEFFICIENTS[i + 2]
            hMain[idx] = COEFFICIENTS[i + 3]
            gDot[idx] = COEFFICIENTS[i + 4]
            hDot[idx] = COEFFICIENTS[i + 5]
            i += 6
        }
    }

    /**
     * Geomagnetic field elements. X north, Y east, Z down (nT); H horizontal intensity, F total
     * intensity (nT); declination D (east positive) and inclination I (down positive) in degrees.
     */
    data class Field(
        val x: Double,
        val y: Double,
        val z: Double,
        val h: Double,
        val f: Double,
        val declinationDeg: Double,
        val inclinationDeg: Double,
        val decimalYear: Double,
        val isWithinValidity: Boolean
    ) {
        /**
         * One-sigma declination uncertainty (degrees) from the official WMM2025 error model,
         * σD = sqrt(0.26² + (5417 nT / H)²).
         */
        val declinationUncertaintyDeg: Double get() = sqrt(0.26 * 0.26 + (5417.0 / h) * (5417.0 / h))

        /** One-sigma inclination uncertainty (degrees) of the WMM2025 error model. */
        val inclinationUncertaintyDeg: Double get() = 0.20

        /** One-sigma total-intensity uncertainty (nT) of the WMM2025 error model. */
        val totalIntensityUncertaintyNt: Double get() = 138.0
    }

    /**
     * @param latitudeDeg geodetic latitude (WGS84)
     * @param longitudeDeg geodetic longitude, east positive
     * @param heightKm height above the WGS84 ellipsoid in kilometres
     * @param decimalYear e.g. 2026.79; extrapolated (and flagged) outside 2025.0–2030.0
     */
    fun calculate(latitudeDeg: Double, longitudeDeg: Double, heightKm: Double, decimalYear: Double): Field {
        val lat = Math.toRadians(latitudeDeg.coerceIn(-89.9999, 89.9999))
        val lon = Math.toRadians(longitudeDeg)

        // --- MAG_GeodeticToSpherical ---------------------------------------------------------------
        val e2 = WGS84_F * (2.0 - WGS84_F)
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val rc = WGS84_A_KM / sqrt(1.0 - e2 * sinLat * sinLat)
        val xp = (rc + heightKm) * cosLat
        val zp = (rc * (1.0 - e2) + heightKm) * sinLat
        val r = sqrt(xp * xp + zp * zp)
        val phig = Math.asin(zp / r) // geocentric latitude

        // --- time-adjusted coefficients ---------------------------------------------------------------
        val dt = decimalYear - EPOCH
        val size = gMain.size
        val g = DoubleArray(size) { gMain[it] + dt * gDot[it] }
        val hh = DoubleArray(size) { hMain[it] + dt * hDot[it] }

        // --- MAG_PcupLow: Schmidt semi-normalized associated Legendre functions ------------------------
        val x = sin(phig)
        val pcup = DoubleArray(size)
        val dpcup = DoubleArray(size)
        val z = sqrt((1.0 - x) * (1.0 + x))
        pcup[0] = 1.0
        dpcup[0] = 0.0
        for (n in 1..N_MAX) {
            for (m in 0..n) {
                val index = n * (n + 1) / 2 + m
                if (n == m) {
                    val index1 = (n - 1) * n / 2 + m - 1
                    pcup[index] = z * pcup[index1]
                    dpcup[index] = z * dpcup[index1] + x * pcup[index1]
                } else if (n == 1 && m == 0) {
                    val index1 = (n - 1) * n / 2 + m
                    pcup[index] = x * pcup[index1]
                    dpcup[index] = x * dpcup[index1] - z * pcup[index1]
                } else if (n > 1 && n != m) {
                    val index1 = (n - 2) * (n - 1) / 2 + m
                    val index2 = (n - 1) * n / 2 + m
                    if (m > n - 2) {
                        pcup[index] = x * pcup[index2]
                        dpcup[index] = x * dpcup[index2] - z * pcup[index2]
                    } else {
                        val k = ((n - 1) * (n - 1) - m * m).toDouble() / ((2 * n - 1) * (2 * n - 3)).toDouble()
                        pcup[index] = x * pcup[index2] - k * pcup[index1]
                        dpcup[index] = x * dpcup[index2] - z * pcup[index2] - k * dpcup[index1]
                    }
                }
            }
        }
        val schmidt = DoubleArray(size)
        schmidt[0] = 1.0
        for (n in 1..N_MAX) {
            val index = n * (n + 1) / 2
            val index1 = (n - 1) * n / 2
            schmidt[index] = schmidt[index1] * (2 * n - 1).toDouble() / n.toDouble()
            for (m in 1..n) {
                val idx = n * (n + 1) / 2 + m
                val idx1 = n * (n + 1) / 2 + m - 1
                val factor = if (m == 1) 2.0 else 1.0
                schmidt[idx] = schmidt[idx1] * sqrt(((n - m + 1).toDouble() * factor) / (n + m).toDouble())
            }
        }
        for (n in 1..N_MAX) {
            for (m in 0..n) {
                val index = n * (n + 1) / 2 + m
                pcup[index] *= schmidt[index]
                // The derivative is taken with respect to latitude, not colatitude.
                dpcup[index] = -dpcup[index] * schmidt[index]
            }
        }

        // --- MAG_Summation ----------------------------------------------------------------------------
        var bx = 0.0
        var by = 0.0
        var bz = 0.0
        val ratio = REFERENCE_RADIUS_KM / r
        var radiusPower = ratio * ratio // (a/r)^(n+2) built iteratively
        for (n in 1..N_MAX) {
            radiusPower *= ratio
            for (m in 0..n) {
                val index = n * (n + 1) / 2 + m
                val cosml = cos(m * lon)
                val sinml = sin(m * lon)
                val gc = g[index] * cosml + hh[index] * sinml
                bz -= radiusPower * gc * (n + 1) * pcup[index]
                by += radiusPower * (g[index] * sinml - hh[index] * cosml) * m * pcup[index]
                bx -= radiusPower * gc * dpcup[index]
            }
        }
        val cosPhig = cos(phig)
        by = if (abs(cosPhig) > 1.0e-10) by / cosPhig else by // latitude is clamped away from the pole

        // --- MAG_RotateMagneticVector: spherical → geodetic ------------------------------------------
        val psi = phig - lat
        val bzE = bx * sin(psi) + bz * cos(psi)
        val bxE = bx * cos(psi) - bz * sin(psi)
        val byE = by

        val hIntensity = sqrt(bxE * bxE + byE * byE)
        val fIntensity = sqrt(hIntensity * hIntensity + bzE * bzE)
        val decl = Math.toDegrees(atan2(byE, bxE))
        val incl = Math.toDegrees(atan2(bzE, hIntensity))
        return Field(
            x = bxE,
            y = byE,
            z = bzE,
            h = hIntensity,
            f = fIntensity,
            declinationDeg = decl,
            inclinationDeg = incl,
            decimalYear = decimalYear,
            isWithinValidity = decimalYear >= VALID_FROM && decimalYear <= VALID_UNTIL
        )
    }
}
