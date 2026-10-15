package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

/**
 * Standard SGP4 orbital propagator for NORAD Two-Line Element (TLE) sets.
 *
 * Implements the authoritative SGP4 mathematical formulation:
 * - Spacetrack Report No. 3 (Hoots & Roehrich, 1980)
 * - AIAA 2006-6753: "Revisiting Spacetrack Report #3" (Vallado, Crawford, Hujsak, Kelso, 2006)
 *
 * Features:
 * - WGS-72 gravitational constant set (matched to NORAD TLE generation)
 * - Lyddane's formulation for orbital elements and Kepler equation solution
 * - Full short- and long-period gravitational perturbations (J2, J3, J4)
 * - Full atmospheric drag terms (B*, secular acceleration, cubic and quartic terms)
 * - Output in True Equator, Mean Equinox (TEME) frame (km, km/s)
 */
class SGP4Propagator {

    companion object {
        const val MINUTES_PER_DAY = 1440.0
        const val TWO_PI = 2.0 * Math.PI
        const val PI = Math.PI
        const val DEG2RAD = Math.PI / 180.0
        const val RAD2DEG = 180.0 / Math.PI

        // WGS-72 standard gravitational constants for SGP4
        const val EARTH_RADIUS_KM = 6378.135
        const val XKE = 0.074366916133173413 // sqrt(GM) in (Earth Radii)^1.5 / min
        const val J2 = 0.001082616
        const val J3 = -0.00000253881
        const val J4 = -0.00000165597
        const val CK2 = 0.5 * J2
        const val CK4 = -0.375 * J4
        const val XJ3 = J3
        const val S0 = 78.0  // km
        const val Q0 = 120.0 // km
    }

    data class TLEData(
        val epochYear: Int,
        val epochDay: Double,      // Day of year with fractional day (1-based)
        val inclinationDeg: Double,
        val raanDeg: Double,       // Right Ascension of Ascending Node
        val eccentricity: Double,
        val argPerigeeDeg: Double,
        val meanAnomalyDeg: Double,
        val meanMotion: Double,    // Revolutions per day
        val bStar: Double          // B* drag term (1/Earth Radii)
    )

    data class TemEState(
        val xKm: Double,
        val yKm: Double,
        val zKm: Double,
        val vxKmS: Double,
        val vyKmS: Double,
        val vzKmS: Double
    )

    /**
     * Propagate TLE to a given time.
     * @param tle The TLE data
     * @param targetTimeMs Target time in UTC milliseconds
     * @return Position and velocity in TEME frame (km and km/s)
     */
    fun propagate(tle: TLEData, targetTimeMs: Long): TemEState {
        val targetJd = (targetTimeMs / 86400000.0) + 2440587.5
        val epochJd = tleEpochToJulianDate(tle.epochYear, tle.epochDay)
        val tsinceMinutes = (targetJd - epochJd) * MINUTES_PER_DAY

        return sgp4(tle, tsinceMinutes)
    }

    /**
     * Propagate TLE to minutes since epoch.
     */
    fun propagateMinutesSinceEpoch(tle: TLEData, tsinceMinutes: Double): TemEState {
        return sgp4(tle, tsinceMinutes)
    }

    /**
     * Core Vallado / Spacetrack SGP4 algorithm.
     */
    private fun sgp4(tle: TLEData, tsince: Double): TemEState {
        // Step 1: Initialization from TLE orbital elements
        val radInc = tle.inclinationDeg * DEG2RAD
        val radRaan = tle.raanDeg * DEG2RAD
        val radArgp = tle.argPerigeeDeg * DEG2RAD
        val radMa = tle.meanAnomalyDeg * DEG2RAD
        val ecc = tle.eccentricity.coerceIn(1e-6, 0.999999)
        val bstar = tle.bStar

        val n0 = tle.meanMotion * TWO_PI / MINUTES_PER_DAY // rad/min

        // Recover un-Kozai mean motion (n0dp) and semi-major axis (a0dp)
        val a1 = (XKE / n0).pow(2.0 / 3.0)
        val cosI0 = cos(radInc)
        val sinI0 = sin(radInc)
        val theta2 = cosI0 * cosI0
        val x3thm1 = 3.0 * theta2 - 1.0
        val eosq = ecc * ecc
        val beta0sq = 1.0 - eosq
        val beta0 = sqrt(beta0sq)

        val del1 = 1.5 * (CK2 / (a1 * a1)) * (x3thm1 / (beta0 * beta0sq))
        val ao = a1 * (1.0 - del1 * (1.0 / 3.0 + del1 * (1.0 + del1 * 134.0 / 81.0)))
        val del0 = 1.5 * (CK2 / (ao * ao)) * (x3thm1 / (beta0 * beta0sq))
        val n0dp = n0 / (1.0 + del0)
        val a0dp = ao / (1.0 - del0)

        // Check perigee altitude
        val perigeeKm = (a0dp * (1.0 - ecc) - 1.0) * EARTH_RADIUS_KM
        var s4 = S0 / EARTH_RADIUS_KM + 1.0
        var q0ms4 = (Q0 - S0) / EARTH_RADIUS_KM

        val isimp = if (perigeeKm < 220.0) 1 else 0
        if (perigeeKm < 156.0) {
            s4 = perigeeKm - 78.0
            if (perigeeKm < 98.0) {
                s4 = 20.0
            }
            q0ms4 = ((120.0 - s4) / EARTH_RADIUS_KM).pow(4.0)
            s4 = s4 / EARTH_RADIUS_KM + 1.0
        } else {
            q0ms4 = q0ms4.pow(4.0)
        }

        val pinv = 1.0 / (a0dp * beta0sq)
        val pinvsq = pinv * pinv
        val tsi = 1.0 / (a0dp - s4)
        val eta = a0dp * ecc * tsi
        val etasq = eta * eta
        val eeta = ecc * eta

        val coef1 = q0ms4 * tsi.pow(4.0)
        val coef = coef1 * tsi

        val c2 = coef1 * n0dp * (a0dp * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * CK2 * tsi / beta0sq * x3thm1 * (8.0 + 24.0 * etasq + 3.0 * etasq * etasq))
        val c1 = bstar * c2
        val c3 = if (ecc > 1e-4) coef * n0dp * XJ3 * sinI0 / (CK2 * ecc) else 0.0
        val c4 = 2.0 * n0dp * coef1 * a0dp * beta0sq * (
                eta * (2.0 + 0.5 * etasq) + ecc * (0.5 + 2.0 * etasq) -
                        2.0 * CK2 * tsi / (a0dp * beta0sq) * (
                        3.0 * (1.0 - 3.0 * theta2) * (1.0 + 1.5 * etasq - 2.0 * eeta - 0.5 * eeta * etasq) +
                                0.75 * (1.0 - theta2) * (2.0 * etasq - eeta - eeta * etasq) * cos(2.0 * radArgp)
                        )
                )
        val c5 = 2.0 * coef1 * a0dp * beta0sq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)

        // Secular rates
        val temp1 = 3.0 * CK2 * pinvsq * n0dp
        val temp2 = temp1 * CK2 * pinvsq
        val temp3 = 1.25 * CK4 * pinvsq * pinvsq * n0dp

        val xmdot = n0dp + 0.5 * temp1 * beta0 * x3thm1 + 0.0625 * temp2 * beta0 * (13.0 - 78.0 * theta2 + 137.0 * theta2 * theta2)
        val omgdot = -0.5 * temp1 * (1.0 - 5.0 * theta2) + 0.0625 * temp2 * (7.0 - 114.0 * theta2 + 395.0 * theta2 * theta2) +
                temp3 * (3.0 - 36.0 * theta2 + 49.0 * theta2 * theta2)
        val xnodot = -temp1 * cosI0 + 0.5 * temp2 * (4.0 * cosI0 - 19.0 * cosI0 * theta2) +
                2.0 * temp3 * cosI0 * (3.0 - 7.0 * theta2)

        val d2 = 4.0 * a0dp * tsi * c1 * c1
        val d3 = (4.0 / 3.0) * a0dp * tsi * tsi * (17.0 * a0dp + s4) * c1 * c1 * c1
        val d4 = (2.0 / 3.0) * a0dp * tsi.pow(3.0) * (221.0 * a0dp + 31.0 * s4) * c1.pow(4.0)

        val t2cof = 1.5 * c1
        val t3cof = d2 + 2.0 * c1 * c1
        val t4cof = 0.25 * (3.0 * d3 + 12.0 * c1 * d2 + 3.0 * c1.pow(3.0))
        val t5cof = 0.2 * (3.0 * d4 + 12.0 * c1 * d3 + 6.0 * d2 * d2 + 12.0 * c1 * c1 * d2 + 2.0 * c1.pow(4.0))

        val omgcof = bstar * c3 * cos(radArgp)
        val xmcof = if (ecc > 1e-4) -(2.0 / 3.0) * coef1 * bstar / eeta else 0.0
        val xnodcf = 3.5 * beta0sq * temp1 * cosI0 * c1

        // Step 2: Update mean elements for time tsince
        val xmdf = radMa + xmdot * tsince
        val omgadf = radArgp + omgdot * tsince
        val xnoddf = radRaan + xnodot * tsince

        var omega = omgadf
        var xmp = xmdf
        val tsq = tsince * tsince
        val xnode = xnoddf + xnodcf * tsq
        // Drag is applied ONLY in the isimp != 1 branch below, exactly as in the
        // reference. Applying d2/d3/d4 and t3cof/t4cof/t5cof here as well would
        // count them twice (and would also leak them into the isimp == 1 path,
        // perigee < 220 km, where the reference never applies them).
        var tempa = 1.0 - c1 * tsince
        var tempe = bstar * c4 * tsince
        var templ = t2cof * tsq

        if (isimp != 1) {
            val delomg = omgcof * tsince
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm0temp = 1.0 + eta * cos(radMa)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delm0temp * delm0temp * delm0temp)
            val temp = delomg + delm
            xmp = xmdf + temp
            omega = omgadf - temp
            val tcube = tsq * tsince
            val tfour = tsq * tsq
            tempa = tempa - d2 * tsq - d3 * tcube - d4 * tfour
            tempe = tempe + bstar * c5 * (sin(xmp) - sin(radMa))
            templ = templ + t3cof * tcube + tfour * (t4cof + tsince * t5cof)
        }

        val a = a0dp * tempa * tempa
        val e = (ecc - tempe).coerceIn(1e-6, 0.999999)
        val xl = xmp + omega + xnode + n0dp * templ
        val beta = sqrt(1.0 - e * e)
        val n = XKE / a.pow(1.5)

        // Long-period periodics: the J3 pair that shifts the mean (xl, ay) before the Kepler
        // solve. The label here used to read "(Lyddane)"; this block is not the Lyddane
        // transformation, and that mislabel is part of why the two coefficients below stayed
        // wrong. a3ovk2 = -J3/CK2 in Earth-radii units (AE = 1), per AIAA 2006-6753.
        val axn = e * cos(omega)
        val tempPl = 1.0 / (a * beta * beta)
        // J3 long-period coefficients. Published SGP4 (Vallado/CelesTrak,
        // AIAA 2006-6753): a3ovk2 = -J3 / CK2 in Earth-radii units (AE = 1).
        val a3ovk2 = -XJ3 / CK2
        val onePlusCosI0 = 1.0 + cosI0
        val xlCoeff = 0.125 * a3ovk2 * sinI0 * (3.0 + 5.0 * cosI0) /
                (if (abs(onePlusCosI0) > 1.5e-12) onePlusCosI0 else 1.5e-12)
        val ayCoeff = 0.25 * a3ovk2 * sinI0
        val xll = tempPl * xlCoeff * axn
        val ayn = e * sin(omega) + tempPl * ayCoeff
        val xlt = xl + xll
        val el2 = axn * axn + ayn * ayn

        // Solve Kepler's equation for (E + omega)
        val uMean = ((xlt - xnode) % TWO_PI + TWO_PI) % TWO_PI
        var epw = uMean
        for (iter in 0..15) {
            val sinEPW = sin(epw)
            val cosEPW = cos(epw)
            val f = epw - axn * sinEPW + ayn * cosEPW - uMean
            val fPrime = 1.0 - axn * cosEPW - ayn * sinEPW
            val delta = f / fPrime
            epw -= delta
            if (abs(delta) < 1e-12) break
        }

        // Short period preliminary quantities
        val sinEPW = sin(epw)
        val cosEPW = cos(epw)
        val ecose = axn * cosEPW + ayn * sinEPW
        val esine = axn * sinEPW - ayn * cosEPW
        val pl = a * (1.0 - el2)
        val r = a * (1.0 - ecose)
        val rdot = sqrt(a) * esine / r
        val rfdot = sqrt(pl) / r
        val betal = sqrt(1.0 - el2)
        val sinu = (a / r) * (sinEPW - ayn - axn * esine / (1.0 + betal))
        val cosu = (a / r) * (cosEPW - axn + ayn * esine / (1.0 + betal))
        val uVal = atan2(sinu, cosu)
        val sin2u = sin(2.0 * uVal)
        val cos2u = cos(2.0 * uVal)

        val tempPlShort = 1.0 / pl
        val temp1a = CK2 * tempPlShort
        val temp2a = temp1a * tempPlShort

        // Update for short-period periodics
        val rk = r * (1.0 - 1.5 * temp2a * betal * x3thm1) + 0.5 * temp1a * (1.0 - theta2) * cos2u
        val uk = uVal - 0.25 * temp2a * (7.0 * theta2 - 1.0) * sin2u
        val xnodek = xnode + 1.5 * temp2a * cosI0 * sin2u
        val xinck = radInc + 1.5 * temp2a * cosI0 * sinI0 * cos2u
        val rdotk = rdot - n * temp1a * (1.0 - theta2) * sin2u
        val rfdotk = rfdot + n * temp1a * ((1.0 - theta2) * cos2u + 1.5 * x3thm1)

        // Orientation unit vectors in TEME
        val sinuk = sin(uk)
        val cosuk = cos(uk)
        val sinik = sin(xinck)
        val cosik = cos(xinck)
        val sinnodk = sin(xnodek)
        val cosnodk = cos(xnodek)

        val mx = -sinnodk * cosik
        val my = cosnodk * cosik
        val mz = sinik

        val nx = cosnodk
        val ny = sinnodk

        val ux = nx * cosuk + mx * sinuk
        val uy = ny * cosuk + my * sinuk
        val uz = mz * sinuk

        val vx = -nx * sinuk + mx * cosuk
        val vy = -ny * sinuk + my * cosuk
        val vz = mz * cosuk

        // Position (km) and velocity (km/s) in TEME
        val xKm = rk * ux * EARTH_RADIUS_KM
        val yKm = rk * uy * EARTH_RADIUS_KM
        val zKm = rk * uz * EARTH_RADIUS_KM

        val vFactor = EARTH_RADIUS_KM * XKE / 60.0
        val vxKmS = (rdotk * ux + rfdotk * vx) * vFactor
        val vyKmS = (rdotk * uy + rfdotk * vy) * vFactor
        val vzKmS = (rdotk * uz + rfdotk * vz) * vFactor

        return TemEState(xKm, yKm, zKm, vxKmS, vyKmS, vzKmS)
    }

    /**
     * Converts TLE epoch (two-digit/four-digit year and day of year with fraction)
     * to exact Julian Date (UTC).
     */
    fun tleEpochToJulianDate(year: Int, dayFrac: Double): Double {
        val fullYear = if (year < 57) 2000 + year else if (year < 100) 1900 + year else year
        val y0 = fullYear - 1
        val jan1Jd = 1721425.5 + 365.0 * y0 + (y0 / 4) - (y0 / 100) + (y0 / 400)
        return jan1Jd + (dayFrac - 1.0)
    }

    /**
     * Converts TLE epoch to UTC milliseconds.
     */
    fun tleEpochToMs(year: Int, dayFrac: Double): Long {
        val jd = tleEpochToJulianDate(year, dayFrac)
        return ((jd - 2440587.5) * 86400000.0).roundToLong()
    }
}
