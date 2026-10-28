package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

/**
 * High-precision lunar and solar position engine.
 *
 * Lunar: ELP2000-82B truncated theory (Meeus 1998, Chapter 47)
 *   - Accuracy: ~10 arcseconds in longitude, ~4 arcseconds in latitude
 *   - 60 terms each for longitude, latitude, distance
 *
 * Solar: VSOP87 truncated series (Meeus 1998, Chapter 25)
 *   - Accuracy: ~1 arcsecond
 */
class LunarSolarEngine {

    companion object {
        private const val DEG2RAD = Math.PI / 180.0
        private const val RAD2DEG = 180.0 / Math.PI
        private const val ARCSEC2DEG = 1.0 / 3600.0
    }

    data class LunarPosition(
        val geocentricLongitudeDeg: Double,
        val geocentricLatitudeDeg: Double,
        val distanceKm: Double,
        val horizontalParallaxDeg: Double,
        val apparentLongitudeDeg: Double,
        val apparentLatitudeDeg: Double,
        val raDeg: Double,
        val decDeg: Double
    )

    data class SolarPosition(
        val geocentricLongitudeDeg: Double,
        val geocentricLatitudeDeg: Double,
        val distanceAu: Double,
        val apparentLongitudeDeg: Double,
        val raDeg: Double,
        val decDeg: Double,
        val equationOfTimeMinutes: Double
    )

    /**
     * Calculate high-precision lunar position.
     * @param astroTime Time in TT
     * @return Lunar geocentric position
     */
    fun calculateMoon(astroTime: AstroTime): LunarPosition {
        val t = astroTime.jcTt  // Julian centuries in TT from J2000.0

        // Fundamental arguments (in degrees, from Meeus Chapter 47)
        val Lp = normalizeAngle(218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
            1.0 / 538841.0 * t * t * t - 1.0 / 65194000.0 * t * t * t * t)
        val D = normalizeAngle(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t +
            1.0 / 545868.0 * t * t * t - 1.0 / 113065000.0 * t * t * t * t)
        val M = normalizeAngle(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t +
            1.0 / 24490000.0 * t * t * t)
        val Mp = normalizeAngle(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t +
            1.0 / 69699.0 * t * t * t - 1.0 / 14712000.0 * t * t * t * t)
        val F = normalizeAngle(93.2720950 + 483202.0175233 * t - 0.0036539 * t * t -
            1.0 / 3526000.0 * t * t * t + 1.0 / 863310000.0 * t * t * t * t)

        val A1 = normalizeAngle(119.75 + 131.849 * t)
        val A2 = normalizeAngle(53.09 + 479264.290 * t)
        val A3 = normalizeAngle(313.45 + 481266.484 * t)
        val E = 1.0 - 0.002516 * t - 0.0000074 * t * t

        val lTerms = arrayOf(
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, 6288774.0),
            doubleArrayOf(2.0, 0.0, -1.0, 0.0, 1274027.0),
            doubleArrayOf(2.0, 0.0, 0.0, 0.0, 658314.0),
            doubleArrayOf(0.0, 0.0, 2.0, 0.0, 213618.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, -185116.0),
            doubleArrayOf(0.0, 0.0, 0.0, 2.0, -114332.0),
            doubleArrayOf(2.0, 0.0, -2.0, 0.0, 58793.0),
            doubleArrayOf(2.0, -1.0, -1.0, 0.0, 57066.0),
            doubleArrayOf(2.0, 0.0, 1.0, 0.0, 53322.0),
            doubleArrayOf(2.0, -1.0, 0.0, 0.0, 45758.0),
            doubleArrayOf(0.0, 1.0, -1.0, 0.0, -40923.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, -34720.0),
            doubleArrayOf(0.0, 1.0, 1.0, 0.0, -30383.0),
            doubleArrayOf(2.0, 0.0, 0.0, -2.0, 15327.0),
            doubleArrayOf(0.0, 0.0, 1.0, 2.0, -12528.0),
            doubleArrayOf(0.0, 0.0, 1.0, -2.0, 10980.0),
            doubleArrayOf(4.0, 0.0, -1.0, 0.0, 10675.0),
            doubleArrayOf(0.0, 0.0, 3.0, 0.0, 10034.0),
            doubleArrayOf(4.0, 0.0, -2.0, 0.0, 8548.0),
            doubleArrayOf(2.0, 1.0, -1.0, 0.0, -7888.0),
            doubleArrayOf(2.0, 1.0, 0.0, 0.0, -6766.0),
            doubleArrayOf(1.0, 0.0, -1.0, 0.0, -5163.0),
            doubleArrayOf(1.0, 1.0, 0.0, 0.0, 4987.0),
            doubleArrayOf(2.0, -1.0, 1.0, 0.0, 4036.0),
            doubleArrayOf(2.0, 0.0, 2.0, 0.0, 3994.0),
            doubleArrayOf(4.0, 0.0, 0.0, 0.0, 3861.0),
            doubleArrayOf(2.0, 0.0, -3.0, 0.0, 3665.0),
            doubleArrayOf(0.0, 1.0, -2.0, 0.0, -2689.0),
            doubleArrayOf(2.0, 0.0, -1.0, 2.0, -2602.0),
            doubleArrayOf(2.0, -1.0, -2.0, 0.0, 2390.0),
            doubleArrayOf(1.0, 0.0, 1.0, 0.0, -2348.0),
            doubleArrayOf(2.0, -2.0, 0.0, 0.0, 2236.0),
            doubleArrayOf(0.0, 1.0, 2.0, 0.0, -2120.0),
            doubleArrayOf(0.0, 2.0, 0.0, 0.0, -2069.0),
            doubleArrayOf(2.0, -2.0, -1.0, 0.0, 2048.0),
            doubleArrayOf(2.0, 0.0, 1.0, -2.0, -1773.0),
            doubleArrayOf(2.0, 0.0, 0.0, 2.0, -1595.0),
            doubleArrayOf(4.0, -1.0, -1.0, 0.0, 1215.0),
            doubleArrayOf(0.0, 0.0, 2.0, 2.0, -1110.0),
            doubleArrayOf(3.0, 0.0, -1.0, 0.0, -892.0),
            doubleArrayOf(2.0, 1.0, 1.0, 0.0, -810.0),
            doubleArrayOf(4.0, -1.0, -2.0, 0.0, 759.0),
            doubleArrayOf(0.0, 2.0, -1.0, 0.0, -713.0),
            doubleArrayOf(2.0, 2.0, -1.0, 0.0, -700.0),
            doubleArrayOf(2.0, 1.0, -2.0, 0.0, 691.0),
            doubleArrayOf(2.0, -1.0, 0.0, -2.0, 596.0),
            doubleArrayOf(4.0, 0.0, 1.0, 0.0, 549.0),
            doubleArrayOf(0.0, 0.0, 4.0, 0.0, 537.0),
            doubleArrayOf(4.0, -1.0, 0.0, 0.0, 520.0),
            doubleArrayOf(1.0, 0.0, -2.0, 0.0, -487.0),
            doubleArrayOf(2.0, 1.0, 0.0, -2.0, -399.0),
            doubleArrayOf(0.0, 0.0, 2.0, -2.0, -381.0),
            doubleArrayOf(1.0, 1.0, 1.0, 0.0, 351.0),
            doubleArrayOf(3.0, 0.0, -2.0, 0.0, -340.0),
            doubleArrayOf(4.0, 0.0, -3.0, 0.0, 330.0),
            doubleArrayOf(2.0, -1.0, 2.0, 0.0, 327.0),
            doubleArrayOf(0.0, 2.0, 1.0, 0.0, -323.0),
            doubleArrayOf(1.0, 1.0, -1.0, 0.0, 299.0),
            doubleArrayOf(2.0, 0.0, 3.0, 0.0, 294.0),
            doubleArrayOf(2.0, 0.0, -1.0, -2.0, 0.0)
        )

        val bTerms = arrayOf(
            doubleArrayOf(0.0, 0.0, 0.0, 1.0, 5128122.0),
            doubleArrayOf(0.0, 0.0, 1.0, 1.0, 280602.0),
            doubleArrayOf(0.0, 0.0, 1.0, -1.0, 277693.0),
            doubleArrayOf(2.0, 0.0, 0.0, -1.0, 173237.0),
            doubleArrayOf(2.0, 0.0, -1.0, 1.0, 55413.0),
            doubleArrayOf(2.0, 0.0, -1.0, -1.0, 46271.0),
            doubleArrayOf(2.0, 0.0, 0.0, 1.0, 32573.0),
            doubleArrayOf(0.0, 0.0, 2.0, 1.0, 17198.0),
            doubleArrayOf(2.0, 0.0, 1.0, -1.0, 9266.0),
            doubleArrayOf(0.0, 0.0, 2.0, -1.0, 8822.0),
            doubleArrayOf(2.0, -1.0, 0.0, -1.0, 8216.0),
            doubleArrayOf(2.0, 0.0, -2.0, -1.0, 4324.0),
            doubleArrayOf(2.0, 0.0, 1.0, 1.0, 4200.0),
            doubleArrayOf(2.0, 1.0, 0.0, -1.0, -3359.0),
            doubleArrayOf(2.0, -1.0, -1.0, 1.0, 2463.0),
            doubleArrayOf(2.0, -1.0, 0.0, 1.0, 2211.0),
            doubleArrayOf(2.0, -1.0, -1.0, -1.0, 2065.0),
            doubleArrayOf(0.0, 1.0, -1.0, -1.0, -1870.0),
            doubleArrayOf(4.0, 0.0, -1.0, -1.0, 1828.0),
            doubleArrayOf(0.0, 1.0, 0.0, 1.0, -1794.0),
            doubleArrayOf(0.0, 0.0, 0.0, 3.0, -1749.0),
            doubleArrayOf(0.0, 1.0, -1.0, 1.0, -1565.0),
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, -1491.0),
            doubleArrayOf(0.0, 1.0, 1.0, 1.0, -1475.0),
            doubleArrayOf(0.0, 1.0, 1.0, -1.0, -1410.0),
            doubleArrayOf(0.0, 1.0, 0.0, -1.0, -1344.0),
            doubleArrayOf(1.0, 0.0, 0.0, -1.0, -1335.0),
            doubleArrayOf(0.0, 0.0, 3.0, 1.0, 1107.0),
            doubleArrayOf(4.0, 0.0, -2.0, -1.0, 1021.0),
            doubleArrayOf(4.0, 0.0, -1.0, 1.0, 833.0),
            doubleArrayOf(0.0, 0.0, 1.0, -3.0, 777.0),
            doubleArrayOf(4.0, 0.0, -2.0, 1.0, 671.0),
            doubleArrayOf(2.0, 0.0, 0.0, -3.0, 607.0),
            doubleArrayOf(2.0, 0.0, 2.0, -1.0, 596.0),
            doubleArrayOf(2.0, -1.0, 1.0, -1.0, 491.0),
            doubleArrayOf(2.0, 0.0, -2.0, 1.0, -451.0),
            doubleArrayOf(0.0, 0.0, 3.0, -1.0, 439.0),
            doubleArrayOf(2.0, 0.0, 2.0, 1.0, 422.0),
            doubleArrayOf(2.0, 0.0, -3.0, -1.0, 421.0),
            doubleArrayOf(2.0, 1.0, -1.0, 1.0, -366.0),
            doubleArrayOf(2.0, 1.0, 0.0, 1.0, -351.0),
            doubleArrayOf(4.0, 0.0, 0.0, 1.0, 331.0),
            doubleArrayOf(2.0, -1.0, 1.0, 1.0, 315.0),
            doubleArrayOf(2.0, -2.0, 0.0, -1.0, 302.0),
            doubleArrayOf(0.0, 0.0, 1.0, 3.0, -283.0),
            doubleArrayOf(2.0, 1.0, 1.0, -1.0, -229.0),
            doubleArrayOf(1.0, 1.0, 0.0, -1.0, 223.0),
            doubleArrayOf(1.0, 1.0, 0.0, 1.0, 223.0),
            doubleArrayOf(0.0, 1.0, -2.0, -1.0, -220.0),
            doubleArrayOf(2.0, 1.0, -1.0, -1.0, -220.0),
            doubleArrayOf(1.0, 0.0, 1.0, 1.0, -185.0),
            doubleArrayOf(2.0, -1.0, -2.0, -1.0, 181.0),
            doubleArrayOf(0.0, 1.0, 2.0, 1.0, -177.0),
            doubleArrayOf(4.0, 0.0, -3.0, -1.0, 176.0),
            doubleArrayOf(4.0, 0.0, 0.0, -1.0, 166.0),
            doubleArrayOf(4.0, 0.0, -1.0, -3.0, -164.0),
            doubleArrayOf(0.0, 0.0, 2.0, 3.0, 132.0),
            doubleArrayOf(4.0, 0.0, -2.0, -3.0, -119.0),
            doubleArrayOf(2.0, 0.0, 1.0, -3.0, 115.0),
            doubleArrayOf(2.0, 0.0, 0.0, 3.0, -107.0)
        )

        val rTerms = arrayOf(
            doubleArrayOf(0.0, 0.0, 1.0, 0.0, -20905355.0),
            doubleArrayOf(2.0, 0.0, -1.0, 0.0, -3699111.0),
            doubleArrayOf(2.0, 0.0, 0.0, 0.0, -2955968.0),
            doubleArrayOf(0.0, 0.0, 2.0, 0.0, -569925.0),
            doubleArrayOf(0.0, 1.0, 0.0, 0.0, 48888.0),
            doubleArrayOf(0.0, 0.0, 0.0, 2.0, -3149.0),
            doubleArrayOf(2.0, 0.0, -2.0, 0.0, 246158.0),
            doubleArrayOf(2.0, -1.0, -1.0, 0.0, -152138.0),
            doubleArrayOf(2.0, 0.0, 1.0, 0.0, -170733.0),
            doubleArrayOf(2.0, -1.0, 0.0, 0.0, -204586.0),
            doubleArrayOf(0.0, 1.0, -1.0, 0.0, -129620.0),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 108743.0),
            doubleArrayOf(0.0, 1.0, 1.0, 0.0, 104755.0),
            doubleArrayOf(2.0, 0.0, 0.0, -2.0, 10321.0),
            doubleArrayOf(0.0, 0.0, 1.0, 2.0, 79661.0),
            doubleArrayOf(0.0, 0.0, 1.0, -2.0, -34782.0),
            doubleArrayOf(4.0, 0.0, -1.0, 0.0, -23210.0),
            doubleArrayOf(0.0, 0.0, 3.0, 0.0, -21636.0),
            doubleArrayOf(4.0, 0.0, -2.0, 0.0, 24208.0),
            doubleArrayOf(2.0, 1.0, -1.0, 0.0, 30824.0),
            doubleArrayOf(2.0, 1.0, 0.0, 0.0, -8379.0),
            doubleArrayOf(1.0, 0.0, -1.0, 0.0, -16675.0),
            doubleArrayOf(1.0, 1.0, 0.0, 0.0, -12831.0),
            doubleArrayOf(2.0, -1.0, 1.0, 0.0, -10445.0),
            doubleArrayOf(2.0, 0.0, 2.0, 0.0, -11650.0),
            doubleArrayOf(4.0, 0.0, 0.0, 0.0, 14403.0),
            doubleArrayOf(2.0, 0.0, -3.0, 0.0, -7003.0),
            doubleArrayOf(0.0, 1.0, -2.0, 0.0, 10056.0),
            doubleArrayOf(2.0, 0.0, -1.0, 2.0, 6322.0),
            doubleArrayOf(2.0, -1.0, -2.0, 0.0, -9884.0),
            doubleArrayOf(1.0, 0.0, 1.0, 0.0, -2348.0),
            doubleArrayOf(2.0, -2.0, 0.0, 0.0, -4950.0),
            doubleArrayOf(0.0, 1.0, 2.0, 0.0, 4130.0),
            doubleArrayOf(0.0, 2.0, 0.0, 0.0, -3958.0),
            doubleArrayOf(2.0, -2.0, -1.0, 0.0, 3258.0),
            doubleArrayOf(2.0, 0.0, 1.0, -2.0, 2616.0),
            doubleArrayOf(2.0, 0.0, 0.0, 2.0, -1897.0),
            doubleArrayOf(4.0, -1.0, -1.0, 0.0, -2117.0),
            doubleArrayOf(0.0, 0.0, 2.0, 2.0, 2354.0),
            doubleArrayOf(3.0, 0.0, -1.0, 0.0, -892.0),
            doubleArrayOf(2.0, 1.0, 1.0, 0.0, -810.0),
            doubleArrayOf(4.0, -1.0, -2.0, 0.0, 759.0),
            doubleArrayOf(0.0, 2.0, -1.0, 0.0, -713.0),
            doubleArrayOf(2.0, 2.0, -1.0, 0.0, -700.0),
            doubleArrayOf(2.0, 1.0, -2.0, 0.0, 691.0),
            doubleArrayOf(2.0, -1.0, 0.0, -2.0, 596.0),
            doubleArrayOf(4.0, 0.0, 1.0, 0.0, 549.0),
            doubleArrayOf(0.0, 0.0, 4.0, 0.0, 537.0),
            doubleArrayOf(4.0, -1.0, 0.0, 0.0, 520.0),
            doubleArrayOf(1.0, 0.0, -2.0, 0.0, -487.0),
            doubleArrayOf(2.0, 1.0, 0.0, -2.0, -399.0),
            doubleArrayOf(0.0, 0.0, 2.0, -2.0, -381.0),
            doubleArrayOf(1.0, 1.0, 1.0, 0.0, 351.0),
            doubleArrayOf(3.0, 0.0, -2.0, 0.0, -340.0),
            doubleArrayOf(4.0, 0.0, -3.0, 0.0, 330.0),
            doubleArrayOf(2.0, -1.0, 2.0, 0.0, 327.0),
            doubleArrayOf(0.0, 2.0, 1.0, 0.0, -323.0),
            doubleArrayOf(1.0, 1.0, -1.0, 0.0, 299.0),
            doubleArrayOf(2.0, 0.0, 3.0, 0.0, 294.0),
            doubleArrayOf(2.0, 0.0, -1.0, -2.0, 0.0)
        )

        var sumL = 0.0
        for (term in lTerms) {
            val arg = (term[0] * D + term[1] * M + term[2] * Mp + term[3] * F) * DEG2RAD
            var coeff = term[4]
            if (abs(term[1]) == 1.0) coeff *= E
            if (abs(term[1]) == 2.0) coeff *= E * E
            sumL += coeff * sin(arg)
        }

        var sumB = 0.0
        for (term in bTerms) {
            val arg = (term[0] * D + term[1] * M + term[2] * Mp + term[3] * F) * DEG2RAD
            var coeff = term[4]
            if (abs(term[1]) == 1.0) coeff *= E
            if (abs(term[1]) == 2.0) coeff *= E * E
            sumB += coeff * sin(arg)
        }

        var sumR = 0.0
        for (term in rTerms) {
            val arg = (term[0] * D + term[1] * M + term[2] * Mp + term[3] * F) * DEG2RAD
            var coeff = term[4]
            if (abs(term[1]) == 1.0) coeff *= E
            if (abs(term[1]) == 2.0) coeff *= E * E
            sumR += coeff * cos(arg)
        }

        val sumLPlanetary = (
            3958.0 * sin(A1 * DEG2RAD) +
            1962.0 * sin((Lp - F) * DEG2RAD) +
            318.0 * sin(A2 * DEG2RAD)
        )
        val sumBPlanetary = (
            -2235.0 * sin(Lp * DEG2RAD) +
            382.0 * sin(A3 * DEG2RAD) +
            175.0 * sin((A1 - F) * DEG2RAD) +
            175.0 * sin((A1 + F) * DEG2RAD) +
            127.0 * sin((Lp - Mp) * DEG2RAD) -
            115.0 * sin((Lp + Mp) * DEG2RAD)
        )

        val lonDeg = normalizeAngle(Lp + sumL / 1e6 + sumLPlanetary / 1e6)
        val latDeg = sumB / 1e6 + sumBPlanetary / 1e6
        val distKm = 385000.56 + sumR / 1000.0

        val parallaxDeg = asin(6378.14 / distKm) * RAD2DEG

        val nutation = calculateNutation(astroTime)
        val apparentLonDeg = lonDeg + nutation.deltaPsiDeg

        val epsilon = meanObliquity(astroTime.jcTt)
        val epsilonRad = epsilon * DEG2RAD
        val lonRad = apparentLonDeg * DEG2RAD
        val latRad = latDeg * DEG2RAD

        val raRad = atan2(
            sin(lonRad) * cos(epsilonRad) - tan(latRad) * sin(epsilonRad),
            cos(lonRad)
        )
        val decRad = asin(
            sin(latRad) * cos(epsilonRad) + cos(latRad) * sin(epsilonRad) * sin(lonRad)
        )

        return LunarPosition(
            geocentricLongitudeDeg = lonDeg,
            geocentricLatitudeDeg = latDeg,
            distanceKm = distKm,
            horizontalParallaxDeg = parallaxDeg,
            apparentLongitudeDeg = apparentLonDeg,
            apparentLatitudeDeg = latDeg,
            raDeg = normalizeAngle(raRad * RAD2DEG),
            decDeg = decRad * RAD2DEG
        )
    }

    /**
     * Calculate high-precision solar position using VSOP87 truncated series.
     * @param astroTime Time in TT
     * @return Solar geocentric position
     */
    fun calculateSun(astroTime: AstroTime): SolarPosition {
        // VSOP87D series in Meeus Table 32.A uses Julian millennia tau = (JDE - 2451545.0) / 365250.0 = jcTt / 10.0
        val t = astroTime.jcTt / 10.0

        val L0 = (
            175347046.0 + 0.0 * t +
            3341656.0 * cos((4.6692568 + 6283.0758500 * t)) +
            34894.0 * cos((4.62610 + 12566.15170 * t)) +
            3497.0 * cos((2.7441 + 5753.3849 * t)) +
            3418.0 * cos((2.8289 + 3.5231 * t)) +
            3136.0 * cos((3.6277 + 77713.7715 * t)) +
            2676.0 * cos((4.4181 + 7860.4194 * t)) +
            2343.0 * cos((6.1352 + 3930.2097 * t)) +
            1324.0 * cos((0.7425 + 11506.7698 * t)) +
            1273.0 * cos((2.0371 + 529.6910 * t)) +
            1199.0 * cos((1.1096 + 1577.3435 * t)) +
            990.0 * cos((5.233 + 5884.927 * t)) +
            902.0 * cos((2.045 + 26.298 * t)) +
            857.0 * cos((3.508 + 398.149 * t)) +
            780.0 * cos((1.179 + 5223.694 * t)) +
            753.0 * cos((2.533 + 5507.553 * t)) +
            505.0 * cos((4.583 + 18849.228 * t)) +
            492.0 * cos((4.205 + 775.523 * t)) +
            357.0 * cos((2.920 + 0.067 * t)) +
            317.0 * cos((5.849 + 11790.629 * t)) +
            284.0 * cos((1.899 + 796.298 * t)) +
            271.0 * cos((0.315 + 10977.079 * t)) +
            243.0 * cos((0.345 + 5486.778 * t)) +
            206.0 * cos((4.806 + 2544.314 * t)) +
            205.0 * cos((1.869 + 5573.143 * t)) +
            202.0 * cos((2.458 + 6069.777 * t)) +
            156.0 * cos((0.833 + 213.299 * t)) +
            132.0 * cos((3.411 + 2942.463 * t)) +
            126.0 * cos((1.083 + 20.775 * t)) +
            115.0 * cos((0.645 + 0.980 * t)) +
            103.0 * cos((0.636 + 4694.003 * t)) +
            102.0 * cos((0.976 + 15720.839 * t)) +
            102.0 * cos((4.267 + 7.114 * t)) +
            99.0 * cos((6.21 + 2146.17 * t)) +
            98.0 * cos((0.68 + 155.42 * t)) +
            86.0 * cos((5.98 + 161000.69 * t)) +
            85.0 * cos((1.30 + 6275.96 * t)) +
            85.0 * cos((3.67 + 71430.70 * t)) +
            80.0 * cos((1.81 + 17260.15 * t)) +
            79.0 * cos((3.04 + 12036.46 * t)) +
            75.0 * cos((1.76 + 5088.63 * t)) +
            74.0 * cos((3.50 + 3154.69 * t)) +
            74.0 * cos((4.68 + 801.82 * t)) +
            70.0 * cos((0.83 + 9437.76 * t)) +
            62.0 * cos((3.98 + 8827.39 * t)) +
            61.0 * cos((1.82 + 7084.90 * t)) +
            57.0 * cos((2.78 + 6286.60 * t)) +
            56.0 * cos((4.39 + 14143.50 * t)) +
            56.0 * cos((3.47 + 6279.55 * t)) +
            52.0 * cos((0.19 + 12139.55 * t)) +
            52.0 * cos((1.33 + 1748.02 * t)) +
            51.0 * cos((0.28 + 5856.48 * t)) +
            49.0 * cos((0.49 + 1194.45 * t)) +
            41.0 * cos((5.37 + 8429.24 * t)) +
            41.0 * cos((2.40 + 19651.05 * t)) +
            39.0 * cos((6.17 + 10447.39 * t)) +
            37.0 * cos((6.04 + 10213.29 * t)) +
            37.0 * cos((2.57 + 1059.38 * t)) +
            36.0 * cos((1.71 + 2352.87 * t)) +
            36.0 * cos((1.78 + 6812.77 * t)) +
            33.0 * cos((0.59 + 17789.85 * t)) +
            30.0 * cos((0.44 + 83996.85 * t)) +
            30.0 * cos((2.74 + 1349.87 * t)) +
            25.0 * cos((3.16 + 4690.48 * t))
        )

        val L1 = (
            628331966747.0 +
            206059.0 * cos((2.678235 + 6283.075850 * t)) +
            4303.0 * cos((2.6351 + 12566.1517 * t)) +
            425.0 * cos((1.590 + 3.523 * t)) +
            119.0 * cos((5.796 + 26.298 * t)) +
            109.0 * cos((2.966 + 1577.344 * t)) +
            93.0 * cos((2.59 + 18849.23 * t)) +
            72.0 * cos((1.14 + 529.69 * t)) +
            68.0 * cos((1.87 + 398.15 * t)) +
            67.0 * cos((4.41 + 5507.55 * t)) +
            59.0 * cos((2.89 + 5223.69 * t)) +
            56.0 * cos((2.17 + 155.42 * t)) +
            45.0 * cos((0.40 + 796.30 * t)) +
            36.0 * cos((0.47 + 775.52 * t)) +
            29.0 * cos((2.65 + 7.11 * t)) +
            21.0 * cos((5.34 + 0.98 * t)) +
            19.0 * cos((1.85 + 5486.78 * t)) +
            19.0 * cos((4.97 + 213.30 * t)) +
            17.0 * cos((2.99 + 6275.96 * t)) +
            16.0 * cos((0.03 + 2544.31 * t)) +
            16.0 * cos((1.43 + 2146.17 * t)) +
            15.0 * cos((1.21 + 10977.08 * t)) +
            12.0 * cos((2.83 + 1748.02 * t)) +
            12.0 * cos((3.26 + 5088.63 * t)) +
            12.0 * cos((5.27 + 1194.45 * t)) +
            12.0 * cos((2.08 + 4694.00 * t)) +
            11.0 * cos((0.77 + 553.57 * t)) +
            10.0 * cos((1.30 + 6286.60 * t)) +
            10.0 * cos((4.24 + 1349.87 * t)) +
            9.0 * cos((2.70 + 242.73 * t)) +
            8.0 * cos((5.96 + 951.72 * t)) +
            8.0 * cos((1.65 + 9437.76 * t)) +
            7.0 * cos((1.07 + 11790.63 * t))
        )

        val L2 = (
            52919.0 +
            8720.0 * cos((1.0721 + 6283.0758 * t)) +
            309.0 * cos((0.867 + 12566.152 * t)) +
            27.0 * cos((0.05 + 3.52 * t)) +
            16.0 * cos((5.19 + 26.30 * t)) +
            16.0 * cos((3.68 + 155.42 * t)) +
            10.0 * cos((0.76 + 18849.23 * t)) +
            9.0 * cos((2.06 + 77713.77 * t)) +
            7.0 * cos((0.83 + 775.52 * t)) +
            5.0 * cos((4.66 + 1577.34 * t)) +
            4.0 * cos((1.03 + 7.11 * t)) +
            4.0 * cos((3.44 + 5573.14 * t)) +
            3.0 * cos((5.14 + 796.30 * t)) +
            3.0 * cos((6.05 + 5507.55 * t)) +
            3.0 * cos((1.19 + 242.73 * t)) +
            3.0 * cos((6.12 + 529.69 * t)) +
            3.0 * cos((0.31 + 398.15 * t)) +
            3.0 * cos((2.28 + 553.57 * t)) +
            2.0 * cos((4.38 + 5223.69 * t)) +
            2.0 * cos((3.75 + 0.98 * t))
        )

        val L3 = (
            289.0 * cos((5.844 + 6283.076 * t)) +
            35.0 * cos((0.0 + 0.0 * t)) +
            17.0 * cos((5.49 + 12566.15 * t)) +
            3.0 * cos((5.20 + 155.42 * t)) +
            1.0 * cos((4.72 + 3.52 * t)) +
            1.0 * cos((5.30 + 18849.23 * t)) +
            1.0 * cos((5.97 + 242.73 * t))
        )

        val L4 = (
            114.0 * cos((3.142 + 0.0 * t)) +
            8.0 * cos((4.13 + 6283.08 * t)) +
            1.0 * cos((3.84 + 12566.15 * t))
        )

        val B0 = (
            280.0 * cos((3.199 + 84334.662 * t)) +
            102.0 * cos((5.422 + 5507.553 * t)) +
            80.0 * cos((3.88 + 5223.69 * t)) +
            44.0 * cos((3.70 + 2352.87 * t)) +
            32.0 * cos((4.00 + 1577.34 * t))
        )

        val B1 = (
            9.0 * cos((3.90 + 5507.55 * t)) +
            6.0 * cos((1.73 + 5223.69 * t))
        )

        val R0 = (
            100013989.0 +
            1670700.0 * cos((3.0984635 + 6283.0758500 * t)) +
            13956.0 * cos((3.05525 + 12566.15170 * t)) +
            3084.0 * cos((5.1985 + 77713.7715 * t)) +
            1628.0 * cos((1.1739 + 5753.3849 * t)) +
            1576.0 * cos((2.8469 + 7860.4194 * t)) +
            925.0 * cos((5.453 + 11506.770 * t)) +
            542.0 * cos((4.564 + 3930.210 * t)) +
            472.0 * cos((3.661 + 5884.927 * t)) +
            346.0 * cos((0.964 + 5507.553 * t)) +
            329.0 * cos((5.900 + 5223.694 * t)) +
            307.0 * cos((0.299 + 5573.143 * t)) +
            243.0 * cos((4.273 + 11790.629 * t)) +
            212.0 * cos((5.847 + 1577.344 * t)) +
            186.0 * cos((5.022 + 10977.079 * t)) +
            175.0 * cos((3.012 + 18849.228 * t)) +
            110.0 * cos((5.055 + 5486.778 * t)) +
            98.0 * cos((0.89 + 6069.78 * t)) +
            86.0 * cos((5.69 + 15720.84 * t)) +
            86.0 * cos((1.27 + 161000.69 * t)) +
            65.0 * cos((0.27 + 17260.15 * t)) +
            63.0 * cos((0.92 + 529.69 * t)) +
            57.0 * cos((2.01 + 83996.85 * t)) +
            56.0 * cos((5.24 + 71430.70 * t)) +
            49.0 * cos((3.25 + 2544.31 * t)) +
            47.0 * cos((2.58 + 775.52 * t)) +
            45.0 * cos((5.54 + 9437.76 * t)) +
            43.0 * cos((6.01 + 6275.96 * t)) +
            39.0 * cos((5.36 + 4694.00 * t)) +
            38.0 * cos((2.39 + 8827.39 * t)) +
            37.0 * cos((0.83 + 19651.05 * t)) +
            37.0 * cos((4.90 + 12139.55 * t)) +
            36.0 * cos((1.67 + 12036.46 * t)) +
            35.0 * cos((1.84 + 2942.46 * t)) +
            33.0 * cos((0.24 + 7084.90 * t)) +
            32.0 * cos((0.18 + 5088.63 * t)) +
            32.0 * cos((1.78 + 398.15 * t)) +
            28.0 * cos((1.21 + 6286.60 * t)) +
            28.0 * cos((1.90 + 6279.55 * t)) +
            26.0 * cos((4.59 + 10447.39 * t))
        )

        val R1 = (
            103019.0 * cos((1.107490 + 6283.075850 * t)) +
            1721.0 * cos((1.0644 + 12566.1517 * t)) +
            702.0 * cos((3.142 + 0.0 * t)) +
            32.0 * cos((1.02 + 18849.23 * t)) +
            31.0 * cos((2.84 + 5507.55 * t)) +
            25.0 * cos((1.32 + 5223.69 * t)) +
            18.0 * cos((1.42 + 1577.34 * t)) +
            10.0 * cos((5.91 + 10977.08 * t)) +
            9.0 * cos((1.42 + 6275.96 * t)) +
            9.0 * cos((0.27 + 5486.78 * t))
        )

        val R2 = (
            4359.0 * cos((5.7846 + 6283.0758 * t)) +
            124.0 * cos((5.579 + 12566.152 * t)) +
            12.0 * cos((3.14 + 0.0 * t)) +
            9.0 * cos((3.63 + 77713.77 * t)) +
            6.0 * cos((1.87 + 5573.14 * t)) +
            3.0 * cos((5.47 + 18849.23 * t))
        )

        val R3 = (
            145.0 * cos((4.273 + 6283.076 * t)) +
            7.0 * cos((3.92 + 12566.15 * t))
        )

        val L = (L0 + L1 * t + L2 * t * t + L3 * t * t * t + L4 * t * t * t * t) / 1e8
        val B = (B0 + B1 * t) / 1e8
        val R = (R0 + R1 * t + R2 * t * t + R3 * t * t * t) / 1e8

        val geocentricLonDeg = normalizeAngle(L * RAD2DEG + 180.0)
        val geocentricLatDeg = -B * RAD2DEG

        val aberration = -20.4898 / 3600.0
        val apparentLonDeg = geocentricLonDeg + aberration / R

        val nutation = calculateNutation(astroTime)
        val apparentLonWithNutation = apparentLonDeg + nutation.deltaPsiDeg

        val epsilon = meanObliquity(astroTime.jcTt)
        val epsilonRad = epsilon * DEG2RAD
        val lonRad = apparentLonWithNutation * DEG2RAD
        val latRad = geocentricLatDeg * DEG2RAD

        val raRad = atan2(
            sin(lonRad) * cos(epsilonRad) - tan(latRad) * sin(epsilonRad),
            cos(lonRad)
        )
        val decRad = asin(
            sin(latRad) * cos(epsilonRad) + cos(latRad) * sin(epsilonRad) * sin(lonRad)
        )

        val equationOfTime = calculateEquationOfTime(astroTime)

        return SolarPosition(
            geocentricLongitudeDeg = geocentricLonDeg,
            geocentricLatitudeDeg = geocentricLatDeg,
            distanceAu = R,
            apparentLongitudeDeg = apparentLonWithNutation,
            raDeg = normalizeAngle(raRad * RAD2DEG),
            decDeg = decRad * RAD2DEG,
            equationOfTimeMinutes = equationOfTime
        )
    }

    /**
     * Calculate equation of time in minutes.
     */
    private fun calculateEquationOfTime(astroTime: AstroTime): Double {
        val t = astroTime.jcTt
        val L0 = normalizeAngle(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val M = normalizeAngle(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t

        val C = (
            (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(M * DEG2RAD) +
            (0.019993 - 0.000101 * t) * sin(2 * M * DEG2RAD) +
            0.000289 * sin(3 * M * DEG2RAD)
        )

        val trueLong = L0 + C
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(omega * DEG2RAD)

        val y = tan(23.439291 * DEG2RAD / 2.0)
        val y2 = y * y

        val eot = 4.0 * (
            y2 * sin(2 * L0 * DEG2RAD) -
            2 * e * sin(M * DEG2RAD) +
            4 * e * y2 * sin(M * DEG2RAD) * cos(2 * L0 * DEG2RAD) -
            0.5 * y2 * y2 * sin(4 * L0 * DEG2RAD) -
            1.25 * e * e * sin(2 * M * DEG2RAD)
        )

        return eot
    }

    /**
     * Calculate nutation (delegates to FrameTransformationEngine).
     */
    private fun calculateNutation(astroTime: AstroTime): NutationResult {
        val frameEngine = FrameTransformationEngine()
        val nutation = frameEngine.calculateNutationIAU2000B(astroTime)
        return NutationResult(nutation.deltaPsiDeg, nutation.deltaEpsilonDeg)
    }

    private data class NutationResult(val deltaPsiDeg: Double, val deltaEpsilonDeg: Double)

    private fun meanObliquity(jcTt: Double): Double {
        return 23.439291 - 0.0130042 * jcTt
    }

    private fun normalizeAngle(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }
}
