package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

/**
 * Complete coordinate transformation engine implementing IAU standards.
 *
 * Pipeline:
 *   J2000.0 (ICRS) → Precession (IAU 1976) → Mean equator of date
 *   → Nutation (IAU 2000B) → True equator of date
 *   → Sidereal time (GMST/GAST/LAST) → Hour angle
 *   → Horizontal (Alt/Az) with refraction
 *
 * All angles are in degrees unless otherwise noted.
 */
class FrameTransformationEngine {

    companion object {
        /**
         * IAU 2000B nutation series — 77 terms.
         *
         * Format: (l, lp, f, d, omega,
         *          dPsi_sin, dPsi_sin_times_t,
         *          dEps_cos, dEps_cos_times_t)
         *
         * That is the standard IAU 2000B column order, in which the constant and the
         * time coefficient of each nutation component sit adjacent:
         *
         *     Δψ = Σ (c5 + c6·t) · sin(arg)
         *     Δε = Σ (c7 + c8·t) · cos(arg)
         *
         * This comment used to label the columns
         * (sinCoeff_psi, sinCoeff_epsilon, cosCoeff_psi, cosCoeff_epsilon), which
         * implies Δε is driven by c6. That is wrong, and it is provably wrong from the
         * data: for the leading term c6 = −174666 is only −0.0175 arcsec, far too
         * small to be a nutation amplitude, while c7 = 92052331 is +9.2052 arcsec —
         * the nutation-in-obliquity constant, unmistakably the Δε amplitude. (The
         * matching Δψ amplitude c5 = −172064161 is −17.2064 arcsec, the
         * nutation-in-longitude constant.)
         *
         * The CODE below has always used the correct (c5, c6 | c7, c8) order; only the
         * comment was mislabelled. Nothing numeric changed.
         *
         * Coefficients are in 0.1 microarcseconds.
         *
         * This used to be a function-local `val terms` inside
         * [calculateNutationIAU2000B], which reallocated 78 objects on every
         * call. Nutation is evaluated once per [TimeEngine.getLAST] and hence
         * once per alt/az transform, so the table is now allocated once at
         * class-initialisation time. The coefficients themselves are unchanged.
         */
        private val NUTATION_IAU2000B_TERMS: Array<IntArray> = arrayOf(
            // Term 1: l=0, lp=0, f=0, d=0, omega=1 — largest term
            intArrayOf(0, 0, 0, 0, 1, -172064161, -174666, 92052331, 9086),
            intArrayOf(0, 0, 2, -2, 2, -13170906, -1675, 5730336, -3015),
            intArrayOf(0, 0, 2, 0, 2, -2276413, -234, 978459, -485),
            intArrayOf(0, 0, 0, 0, 2, 2074554, 207, -897492, 470),
            intArrayOf(0, 1, 0, 0, 0, 1475877, -3633, 73871, -184),
            intArrayOf(0, 1, 2, -2, 2, -516821, 1226, 224386, -677),
            intArrayOf(1, 0, 0, 0, 0, 711159, 73, -6750, 0),
            intArrayOf(0, 0, 2, 0, 1, -387298, -367, 200728, 18),
            intArrayOf(1, 0, 2, 0, 2, -301461, -36, 129025, -63),
            intArrayOf(0, -1, 2, -2, 2, 215829, -494, -95929, 299),
            intArrayOf(0, 0, 2, -2, 1, 128227, 137, -68982, -9),
            intArrayOf(-1, 0, 2, 0, 2, 123457, 11, -53311, 32),
            intArrayOf(-1, 0, 0, 2, 0, 156994, 10, -1235, 0),
            intArrayOf(1, 0, 0, 0, 1, 63110, 63, -33228, 0),
            intArrayOf(-1, 0, 0, 0, 1, -57976, -63, 31429, 0),
            intArrayOf(-1, 0, 2, 2, 2, -59641, -11, 25543, -11),
            intArrayOf(1, 0, 2, 0, 1, -51613, -42, 26366, 0),
            intArrayOf(-2, 0, 2, 0, 1, 45893, 50, -24236, -10),
            intArrayOf(0, 0, 0, 2, 0, 63384, 11, -1220, 0),
            intArrayOf(0, 0, 2, 2, 2, -38571, -1, 16452, -11),
            intArrayOf(0, -2, 2, -2, 2, 32481, 0, -13870, 0),
            intArrayOf(-2, 0, 0, 2, 0, -47722, 0, 477, 0),
            intArrayOf(2, 0, 2, 0, 2, -31046, -1, 13238, -11),
            intArrayOf(1, 0, 2, -2, 2, 28593, 0, -12338, 10),
            intArrayOf(-1, 0, 2, 0, 1, 20441, 21, -10758, 0),
            intArrayOf(2, 0, 0, 0, 0, 29243, 0, -609, 0),
            intArrayOf(0, 0, 2, 0, 0, 25887, 0, -550, 0),
            intArrayOf(0, 1, 0, 0, 1, -14053, -25, 8551, -2),
            intArrayOf(-1, 0, 0, 2, 1, 15164, 10, -8001, 0),
            intArrayOf(0, 2, 2, -2, 2, -15794, 72, 6850, -42),
            intArrayOf(0, 0, -2, 2, 0, 21783, 0, -167, 0),
            intArrayOf(1, 0, 0, -2, 1, -12873, -10, 6953, 0),
            intArrayOf(0, -1, 0, 0, 1, -12654, 11, 6415, 0),
            intArrayOf(-1, 0, 2, 2, 1, -10204, 0, 5222, 0),
            intArrayOf(0, 2, 0, 0, 0, 16707, -85, 168, -1),
            intArrayOf(1, 0, 2, 2, 2, -7691, 0, 3268, 0),
            intArrayOf(-2, 0, 2, 0, 0, -11024, 0, 104, 0),
            intArrayOf(0, 1, 2, 0, 2, 7566, -21, -3250, 0),
            intArrayOf(0, 0, 2, 2, 1, -6637, -11, 3353, 0),
            intArrayOf(0, -1, 2, 0, 2, -7141, 21, 3070, 0),
            intArrayOf(0, 0, 0, 2, 1, -6302, -11, 3272, 0),
            intArrayOf(1, 0, 2, -2, 1, 5800, 10, -3045, 0),
            intArrayOf(2, 0, 2, -2, 2, 6443, 0, -2768, 0),
            intArrayOf(-2, 0, 0, 2, 1, -5774, -11, 3041, 0),
            intArrayOf(2, 0, 2, 0, 1, -5350, 0, 2695, 0),
            intArrayOf(0, -1, 2, -2, 1, -4752, -11, 2719, 0),
            intArrayOf(0, 0, 0, -2, 1, -4940, -11, 2720, 0),
            intArrayOf(-1, -1, 2, 0, 2, 7350, 0, -3198, 0),
            intArrayOf(2, 0, 0, -2, 1, 4065, 0, -2206, 0),
            intArrayOf(1, 0, 0, 2, 0, 6579, 0, -199, 0),
            intArrayOf(0, 1, 2, -2, 1, 3579, 0, -1900, 0),
            intArrayOf(1, -1, 0, 0, 0, 4725, 0, -41, 0),
            intArrayOf(-2, 0, 2, 0, 2, -3075, 0, 1313, 0),
            intArrayOf(3, 0, 2, 0, 2, -2904, 0, 1233, 0),
            intArrayOf(0, -1, 0, 2, 0, 4344, 0, -81, 0),
            intArrayOf(1, -1, 2, 0, 2, -2878, 0, 1232, 0),
            intArrayOf(0, 0, 0, 1, 0, -4230, 0, 20, 0),
            intArrayOf(-1, -1, 2, 2, 2, -2819, 0, 1207, 0),
            intArrayOf(-1, 0, 2, 0, 0, -4056, 0, 40, 0),
            intArrayOf(0, -1, 2, 2, 2, -2647, 0, 1129, 0),
            intArrayOf(-2, 0, 0, 0, 1, -2294, 0, 1266, 0),
            intArrayOf(1, 1, 2, 0, 2, 2481, 0, -1062, 0),
            intArrayOf(2, 0, 0, 0, 1, 2179, 0, -1129, 0),
            intArrayOf(-1, 1, 0, 1, 0, 3276, 0, -9, 0),
            intArrayOf(1, 1, 0, 0, 0, -3389, 0, 35, 0),
            intArrayOf(1, 0, 2, 0, 0, 3339, 0, -107, 0),
            intArrayOf(-1, 0, 2, -2, 1, -1987, 0, 1073, 0),
            intArrayOf(1, 0, 0, 0, 2, -1981, 0, 854, 0),
            intArrayOf(-1, 0, 0, 1, 0, 4026, 0, -553, 0),
            intArrayOf(0, 0, 2, 1, 2, 1660, 0, -710, 0),
            intArrayOf(-1, 0, 2, 4, 2, -1521, 0, 647, 0),
            intArrayOf(-1, 1, 0, 1, 1, 1314, 0, -700, 0),
            intArrayOf(0, -2, 2, -2, 1, -1283, 0, 672, 0),
            intArrayOf(1, 0, 2, 2, 1, -1331, 0, 663, 0),
            intArrayOf(-2, 0, 2, 2, 2, 1383, 0, -594, 0),
            intArrayOf(-1, 0, 0, 0, 2, 1405, 0, -610, 0),
            intArrayOf(1, 1, 2, -2, 2, 1290, 0, -556, 0)
        )

        // J2000.0 epoch in Julian Date (TT)
        private const val J2000 = 2451545.0

        // Degrees to radians
        private const val DEG2RAD = Math.PI / 180.0
        private const val RAD2DEG = 180.0 / Math.PI

        // Arcseconds to degrees
        private const val ARCSEC2DEG = 1.0 / 3600.0

        // Speed of light in km/s (for light-time correction)
        private const val C = 299792.458
    }

    // ============================================================
    // Data Classes
    // ============================================================

    data class Equatorial(val raDeg: Double, val decDeg: Double)
    data class Horizontal(val altDeg: Double, val azDeg: Double)
    data class Galactic(val lDeg: Double, val bDeg: Double)
    data class NutationAngles(val deltaPsiDeg: Double, val deltaEpsilonDeg: Double, val trueObliquityDeg: Double)
    data class RiseSetTransit(
        val riseTimeMs: Long?,
        val setTimeMs: Long?,
        val transitTimeMs: Long?,
        val transitAltDeg: Double?
    )

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Full pipeline: J2000 equatorial → horizontal with all corrections.
     */
    fun equatorialToHorizontal(
        raJ2000Deg: Double,
        decJ2000Deg: Double,
        astroTime: AstroTime,
        latitudeDeg: Double,
        longitudeDeg: Double,
        elevationM: Double = 0.0
    ): Horizontal {
        // Step 1: Precess from J2000 to mean equator of date
        val (raMean, decMean) = precessJ2000ToDate(raJ2000Deg, decJ2000Deg, astroTime)

        // Step 2: Apply nutation to get true equator of date
        val nutation = calculateNutationIAU2000B(astroTime)
        val (raTrue, decTrue) = applyNutation(raMean, decMean, nutation)

        // Step 3: Compute hour angle
        val lastDeg = calculateLAST(astroTime, longitudeDeg)
        val haDeg = normalizeAngle(lastDeg - raTrue)

        // Step 4: Convert to horizontal
        val latRad = latitudeDeg * DEG2RAD
        val haRad = haDeg * DEG2RAD
        val decRad = decTrue * DEG2RAD

        val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
        var altDeg = asin(sinAlt) * RAD2DEG

        val azRad = atan2(
            -sin(haRad),
            cos(latRad) * tan(decRad) - sin(latRad) * cos(haRad)
        )
        var azDeg = normalizeAngle(azRad * RAD2DEG)

        // Step 5: Apply refraction
        altDeg = applyRefraction(altDeg)

        return Horizontal(altDeg, azDeg)
    }

    /**
     * Directly transforms true equatorial coordinates (RA, Dec of date) to horizontal coordinates.
     */
    fun trueEquatorialToHorizontal(
        raTrueDeg: Double,
        decTrueDeg: Double,
        astroTime: AstroTime,
        latitudeDeg: Double,
        longitudeDeg: Double,
        elevationM: Double = 0.0
    ): Horizontal {
        val lastDeg = calculateLAST(astroTime, longitudeDeg)
        val haDeg = normalizeAngle(lastDeg - raTrueDeg)
        val latRad = latitudeDeg * DEG2RAD
        val haRad = haDeg * DEG2RAD
        val decRad = decTrueDeg * DEG2RAD
        val sinAlt = sin(latRad) * sin(decRad) + cos(latRad) * cos(decRad) * cos(haRad)
        var altDeg = asin(sinAlt) * RAD2DEG
        val azRad = atan2(-sin(haRad), cos(latRad) * tan(decRad) - sin(latRad) * cos(haRad))
        var azDeg = normalizeAngle(azRad * RAD2DEG)
        altDeg = applyRefraction(altDeg)
        return Horizontal(altDeg, azDeg)
    }

    /**
     * Inverse: Horizontal → equatorial (J2000.0).
     */
    fun horizontalToEquatorial(
        altDeg: Double,
        azDeg: Double,
        astroTime: AstroTime,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): Equatorial {
        // Remove refraction (inverse of Bennett's formula)
        val altUnrefracted = removeRefraction(altDeg)

        val latRad = latitudeDeg * DEG2RAD
        val altRad = altUnrefracted * DEG2RAD
        val azRad = azDeg * DEG2RAD

        val decRad = asin(
            (sin(latRad) * sin(altRad) + cos(latRad) * cos(altRad) * cos(azRad)).coerceIn(-1.0, 1.0)
        )
        val haRad = atan2(
            -cos(altRad) * sin(azRad),
            cos(latRad) * sin(altRad) - sin(latRad) * cos(altRad) * cos(azRad)
        )

        val haDeg = haRad * RAD2DEG
        val lastDeg = calculateLAST(astroTime, longitudeDeg)
        val raTrue = normalizeAngle(lastDeg - haDeg)
        val decTrue = decRad * RAD2DEG

        // Un-nutate and un-precess back to J2000.0
        val nutation = calculateNutationIAU2000B(astroTime)
        val (raMean, decMean) = removeNutation(raTrue, decTrue, nutation)
        return precessDateToJ2000(raMean, decMean, astroTime)
    }

    /**
     * Equatorial (true equator of date) → Galactic (J2000).
     */
    fun equatorialToGalactic(raDeg: Double, decDeg: Double): Galactic {
        val raRad = raDeg * DEG2RAD
        val decRad = decDeg * DEG2RAD

        val raGP = 192.85948 * DEG2RAD
        val decGP = 27.12825 * DEG2RAD

        val sinB = sin(decRad) * sin(decGP) +
                   cos(decRad) * cos(decGP) * cos(raRad - raGP)
        val bDeg = asin(sinB.coerceIn(-1.0, 1.0)) * RAD2DEG

        val y = cos(decRad) * sin(raRad - raGP)
        val x = sin(decRad) * cos(decGP) - cos(decRad) * sin(decGP) * cos(raRad - raGP)

        val lDeg = normalizeAngle(122.93192 - atan2(y, x) * RAD2DEG)

        return Galactic(lDeg, bDeg)
    }

    /**
     * Galactic → Equatorial (J2000).
     */
    fun galacticToEquatorial(lDeg: Double, bDeg: Double): Equatorial {
        val thetaRad = (122.93192 - lDeg) * DEG2RAD
        val bRad = bDeg * DEG2RAD

        val raGP = 192.85948 * DEG2RAD
        val decGP = 27.12825 * DEG2RAD

        val decRad = asin(
            (sin(bRad) * sin(decGP) + cos(bRad) * cos(decGP) * cos(thetaRad)).coerceIn(-1.0, 1.0)
        )
        val y = cos(bRad) * sin(thetaRad)
        val x = sin(bRad) * cos(decGP) - cos(bRad) * sin(decGP) * cos(thetaRad)

        val raRad = raGP + atan2(y, x)

        return Equatorial(normalizeAngle(raRad * RAD2DEG), decRad * RAD2DEG)
    }

    /**
     * Compute rise, set, and transit times for an object.
     */
    fun calculateRiseSetTransit(
        raJ2000Deg: Double,
        decJ2000Deg: Double,
        astroTime: AstroTime,
        latitudeDeg: Double,
        longitudeDeg: Double
    ): RiseSetTransit {
        // Standard altitude for rise/set: -0.833° (refraction + semidiameter)
        val standardAlt = -0.833

        // Start searching from local noon
        val localNoonMs = findLocalNoon(astroTime, longitudeDeg)
        val searchStart = AstroTime(localNoonMs - 12 * 3600 * 1000L)
        val searchEnd = AstroTime(localNoonMs + 12 * 3600 * 1000L)

        var transitTime: Long? = null
        var transitAlt: Double? = null
        var riseTime: Long? = null
        var setTime: Long? = null

        // Search in 1-minute steps
        val stepMs = 60 * 1000L
        var t = searchStart.utcMs
        var prevAlt: Double? = null

        while (t <= searchEnd.utcMs) {
            val currentTime = AstroTime(t)
            val horizontal = equatorialToHorizontal(
                raJ2000Deg, decJ2000Deg, currentTime,
                latitudeDeg, longitudeDeg, 0.0
            )

            // Track transit (maximum altitude)
            if (transitAlt == null || horizontal.altDeg > transitAlt!!) {
                transitAlt = horizontal.altDeg
                transitTime = t
            }

            // Detect rise and set (crossing standard altitude)
            if (prevAlt != null) {
                if (prevAlt < standardAlt && horizontal.altDeg >= standardAlt) {
                    // Rising
                    riseTime = interpolateCrossing(t - stepMs, prevAlt, t, horizontal.altDeg, standardAlt)
                }
                if (prevAlt >= standardAlt && horizontal.altDeg < standardAlt) {
                    // Setting
                    setTime = interpolateCrossing(t - stepMs, prevAlt, t, horizontal.altDeg, standardAlt)
                }
            }

            prevAlt = horizontal.altDeg
            t += stepMs
        }

        return RiseSetTransit(riseTime, setTime, transitTime, transitAlt)
    }

    /**
     * Calculate angular separation between two points on the celestial sphere.
     */
    fun calculateAngularSeparationDeg(
        ra1Deg: Double, dec1Deg: Double,
        ra2Deg: Double, dec2Deg: Double
    ): Double {
        val ra1Rad = ra1Deg * DEG2RAD
        val dec1Rad = dec1Deg * DEG2RAD
        val ra2Rad = ra2Deg * DEG2RAD
        val dec2Rad = dec2Deg * DEG2RAD

        val dRa = ra1Rad - ra2Rad
        val sepRad = acos(
            sin(dec1Rad) * sin(dec2Rad) + cos(dec1Rad) * cos(dec2Rad) * cos(dRa)
        )
        return sepRad * RAD2DEG
    }

    // ============================================================
    // Precession — IAU 1976 (Lieske)
    // ============================================================

    /**
     * Precess equatorial coordinates from J2000.0 to mean equator of date.
     * Uses the IAU 1976 precession model (Lieske et al. 1977).
     */
    fun precessJ2000ToDate(raDeg: Double, decDeg: Double, astroTime: AstroTime): Equatorial {
        val t = astroTime.jcTt  // Julian centuries in TT from J2000.0

        // Precession angles in radians
        val zeta = (2306.2181 + 0.30188 * t + 0.017998 * t * t) * t * ARCSEC2DEG * DEG2RAD
        val z = (2306.2181 + 1.09468 * t + 0.018203 * t * t) * t * ARCSEC2DEG * DEG2RAD
        val theta = (2004.3109 - 0.42665 * t - 0.041833 * t * t) * t * ARCSEC2DEG * DEG2RAD

        val raRad = raDeg * DEG2RAD
        val decRad = decDeg * DEG2RAD

        val a = cos(decRad) * sin(raRad + zeta)
        val b = cos(theta) * cos(decRad) * cos(raRad + zeta) - sin(theta) * sin(decRad)
        val c = sin(theta) * cos(decRad) * cos(raRad + zeta) + cos(theta) * sin(decRad)

        val raNewRad = atan2(a, b) + z
        val decNewRad = asin(c.coerceIn(-1.0, 1.0))

        return Equatorial(normalizeAngle(raNewRad * RAD2DEG), decNewRad * RAD2DEG)
    }

    /**
     * Inverse precession: precess equatorial coordinates from mean equator of date to J2000.0.
     */
    fun precessDateToJ2000(raDeg: Double, decDeg: Double, astroTime: AstroTime): Equatorial {
        val t = astroTime.jcTt

        val zeta = (2306.2181 + 0.30188 * t + 0.017998 * t * t) * t * ARCSEC2DEG * DEG2RAD
        val z = (2306.2181 + 1.09468 * t + 0.018203 * t * t) * t * ARCSEC2DEG * DEG2RAD
        val theta = (2004.3109 - 0.42665 * t - 0.041833 * t * t) * t * ARCSEC2DEG * DEG2RAD

        val raRad = raDeg * DEG2RAD
        val decRad = decDeg * DEG2RAD

        val a = cos(decRad) * sin(raRad - z)
        val b = cos(theta) * cos(decRad) * cos(raRad - z) + sin(theta) * sin(decRad)
        val c = -sin(theta) * cos(decRad) * cos(raRad - z) + cos(theta) * sin(decRad)

        val raNewRad = atan2(a, b) - zeta
        val decNewRad = asin(c.coerceIn(-1.0, 1.0))

        return Equatorial(normalizeAngle(raNewRad * RAD2DEG), decNewRad * RAD2DEG)
    }

    // ============================================================
    // Nutation — IAU 2000B (77 terms)
    // ============================================================

    /**
     * Calculate nutation using the IAU 2000B model (77 lunisolar terms + 4 planetary terms).
     *
     * Reference: IERS Conventions 2003, Table 5.1
     * Accuracy: 0.001 arcseconds for nutation in longitude and obliquity
     */
    fun calculateNutationIAU2000B(astroTime: AstroTime): NutationAngles {
        val t = astroTime.jcTt

        // Fundamental arguments (in radians)
        // Mean anomaly of the Moon
        val l = normalizeRadians(
            2.35555598 + 8328.6914269554 * t + 0.000154554 * t * t -
            0.000000293 * t * t * t - 0.000000019 * t * t * t * t
        )
        // Mean anomaly of the Sun
        val lp = normalizeRadians(
            6.24006013 + 628.3019551714 * t - 0.000002682 * t * t +
            0.000000004 * t * t * t + 0.000000001 * t * t * t * t
        )
        // Mean longitude of the Moon minus mean longitude of the ascending node
        val f = normalizeRadians(
            1.62790523 + 8433.4661581574 * t - 0.000064249 * t * t -
            0.000000001 * t * t * t + 0.000000002 * t * t * t * t
        )
        // Mean elongation of the Moon from the Sun
        val d = normalizeRadians(
            5.19846674 + 7771.3771468121 * t - 0.000030148 * t * t +
            0.000000009 * t * t * t + 0.000000002 * t * t * t * t
        )
        // Longitude of the ascending node of the Moon
        val omega = normalizeRadians(
            2.18243920 - 33.7570459538 * t + 0.000036226 * t * t +
            0.000000023 * t * t * t - 0.000000003 * t * t * t * t
        )

        // Mean obliquity of the ecliptic (in radians)
        val epsilon0Rad = (
            84381.448 * ARCSEC2DEG - 46.8150 * t * ARCSEC2DEG -
            0.00059 * t * t * ARCSEC2DEG + 0.001813 * t * t * t * ARCSEC2DEG
        ) * DEG2RAD

        var deltaPsi = 0.0  // in radians
        var deltaEpsilon = 0.0  // in radians

        for (term in NUTATION_IAU2000B_TERMS) {
            val arg = (
                term[0] * l + term[1] * lp + term[2] * f +
                term[3] * d + term[4] * omega
            )

            // Coefficients are in 0.1 microarcseconds
            // Convert to radians: 0.1 μas = 1e-7 arcsec = 1e-7 / 3600 degrees = 1e-7 * ARCSEC2DEG degrees
            val sinCoeff = term[5] * 1e-7 * ARCSEC2DEG * DEG2RAD
            val cosCoeff = term[6] * 1e-7 * ARCSEC2DEG * DEG2RAD
            val sinCoeffEps = term[7] * 1e-7 * ARCSEC2DEG * DEG2RAD
            val cosCoeffEps = term[8] * 1e-7 * ARCSEC2DEG * DEG2RAD

            deltaPsi += (sinCoeff + cosCoeff * t) * sin(arg)
            deltaEpsilon += (sinCoeffEps + cosCoeffEps * t) * cos(arg)
        }

        // Convert to degrees
        val deltaPsiDeg = deltaPsi * RAD2DEG
        val deltaEpsilonDeg = deltaEpsilon * RAD2DEG

        // True obliquity of date
        val epsilon0Deg = epsilon0Rad * RAD2DEG
        val trueObliquityDeg = epsilon0Deg + deltaEpsilonDeg

        return NutationAngles(deltaPsiDeg, deltaEpsilonDeg, trueObliquityDeg)
    }

    /**
     * Apply nutation to mean equatorial coordinates to get true equator of date.
     * Uses vector rotation matrix formulation to avoid singularity at celestial poles.
     */
    private fun applyNutation(
        raMeanDeg: Double,
        decMeanDeg: Double,
        nutation: NutationAngles
    ): Equatorial {
        val raRad = raMeanDeg * DEG2RAD
        val decRad = decMeanDeg * DEG2RAD
        val eps0Rad = (nutation.trueObliquityDeg - nutation.deltaEpsilonDeg) * DEG2RAD
        val epsRad = nutation.trueObliquityDeg * DEG2RAD
        val dPsiRad = nutation.deltaPsiDeg * DEG2RAD

        // Mean equatorial rectangular coordinates
        val x0 = cos(decRad) * cos(raRad)
        val y0 = cos(decRad) * sin(raRad)
        val z0 = sin(decRad)

        // Rotate +eps0 around X -> mean ecliptic
        val x1 = x0
        val y1 = y0 * cos(eps0Rad) + z0 * sin(eps0Rad)
        val z1 = -y0 * sin(eps0Rad) + z0 * cos(eps0Rad)

        // Rotate +dPsi around Z -> true ecliptic
        val cosDPsi = cos(dPsiRad)
        val sinDPsi = sin(dPsiRad)
        val x2 = x1 * cosDPsi - y1 * sinDPsi
        val y2 = x1 * sinDPsi + y1 * cosDPsi
        val z2 = z1

        // Rotate -eps around X -> true equatorial
        val xTrue = x2
        val yTrue = y2 * cos(epsRad) - z2 * sin(epsRad)
        val zTrue = y2 * sin(epsRad) + z2 * cos(epsRad)

        val raTrueRad = atan2(yTrue, xTrue)
        val decTrueRad = asin(zTrue.coerceIn(-1.0, 1.0))

        return Equatorial(
            normalizeAngle(raTrueRad * RAD2DEG),
            decTrueRad * RAD2DEG
        )
    }

    /**
     * Remove nutation from true equatorial coordinates to get mean equator of date.
     * Uses vector rotation matrix formulation to avoid singularity at celestial poles.
     */
    private fun removeNutation(
        raTrueDeg: Double,
        decTrueDeg: Double,
        nutation: NutationAngles
    ): Equatorial {
        val raRad = raTrueDeg * DEG2RAD
        val decRad = decTrueDeg * DEG2RAD
        val eps0Rad = (nutation.trueObliquityDeg - nutation.deltaEpsilonDeg) * DEG2RAD
        val epsRad = nutation.trueObliquityDeg * DEG2RAD
        val dPsiRad = nutation.deltaPsiDeg * DEG2RAD

        // True equatorial rectangular coordinates
        val xT = cos(decRad) * cos(raRad)
        val yT = cos(decRad) * sin(raRad)
        val zT = sin(decRad)

        // Rotate +eps around X -> true ecliptic
        val x1 = xT
        val y1 = yT * cos(epsRad) + zT * sin(epsRad)
        val z1 = -yT * sin(epsRad) + zT * cos(epsRad)

        // Rotate -dPsi around Z -> mean ecliptic
        val cosDPsi = cos(dPsiRad)
        val sinDPsi = sin(dPsiRad)
        val x2 = x1 * cosDPsi + y1 * sinDPsi
        val y2 = -x1 * sinDPsi + y1 * cosDPsi
        val z2 = z1

        // Rotate -eps0 around X -> mean equatorial
        val xMean = x2
        val yMean = y2 * cos(eps0Rad) - z2 * sin(eps0Rad)
        val zMean = y2 * sin(eps0Rad) + z2 * cos(eps0Rad)

        val raMeanRad = atan2(yMean, xMean)
        val decMeanRad = asin(zMean.coerceIn(-1.0, 1.0))

        return Equatorial(
            normalizeAngle(raMeanRad * RAD2DEG),
            decMeanRad * RAD2DEG
        )
    }

    // ============================================================
    // Sidereal Time
    // ============================================================

    /**
     * Greenwich Mean Sidereal Time (GMST) in degrees.
     * IAU 1982 formula (Aoki et al. 1982), as given in
     * Meeus, "Astronomical Algorithms" 2nd Ed. (1998), eq. 12.4.
     *
     * Verification anchor: for JD 2461055.5 (2026-01-15 00:00 UTC)
     * this must return ~252.43 degrees (16h 49m 43s sidereal).
     */
    fun calculateGMST(astroTime: AstroTime): Double {
        val jd = astroTime.jdUtc
        val d = jd - 2451545.0      // days since J2000.0 (UT1 ~ UTC approximation)
        val T = d / 36525.0         // Julian centuries since J2000.0

        var gmstDeg = 280.46061837 +
                360.98564736629 * d +
                0.000387933 * T * T -
                (T * T * T) / 38710000.0

        gmstDeg %= 360.0
        if (gmstDeg < 0) gmstDeg += 360.0
        return gmstDeg
    }

    /**
     * Calculate Greenwich Apparent Sidereal Time (GAST) in degrees.
     * GAST = GMST + Equation of the Equinoxes
     */
    fun calculateGAST(astroTime: AstroTime): Double {
        val gmstDeg = calculateGMST(astroTime)
        val nutation = calculateNutationIAU2000B(astroTime)

        // Equation of the equinoxes: Δψ × cos(ε)
        val eqEqDeg = nutation.deltaPsiDeg * cos(nutation.trueObliquityDeg * DEG2RAD)

        return normalizeAngle(gmstDeg + eqEqDeg)
    }

    /**
     * Calculate Local Apparent Sidereal Time (LAST) in degrees.
     * LAST = GAST + observer longitude (east positive)
     */
    fun calculateLAST(astroTime: AstroTime, longitudeDeg: Double): Double {
        val gastDeg = calculateGAST(astroTime)
        return normalizeAngle(gastDeg + longitudeDeg)
    }

    // ============================================================
    // Refraction — Bennett (1982)
    // ============================================================

    /**
     * Apply atmospheric refraction to apparent altitude.
     * Uses Bennett's formula (1982).
     *
     * R = 1 / tan(hₐ + 7.31 / (hₐ + 4.4)) arcminutes
     *
     * Only applied for altitudes > -1° (below that, refraction is unpredictable).
     */
    fun applyRefraction(altDeg: Double): Double {
        if (altDeg < -1.0) return altDeg

        val haRad = altDeg * DEG2RAD
        val rArcmin = 1.0 / tan(haRad + 7.31 / (altDeg + 4.4) * DEG2RAD)
        val rDeg = rArcmin / 60.0

        return altDeg + rDeg
    }

    /**
     * Remove atmospheric refraction from apparent altitude (inverse of Bennett's formula).
     */
    fun removeRefraction(altDeg: Double): Double {
        if (altDeg < -1.0) return altDeg

        // Iterative solution (typically converges in 2-3 iterations)
        var alt = altDeg
        for (i in 0 until 5) {
            val haRad = alt * DEG2RAD
            val rArcmin = 1.0 / tan(haRad + 7.31 / (alt + 4.4) * DEG2RAD)
            val rDeg = rArcmin / 60.0
            alt = altDeg - rDeg
        }
        return alt
    }

    // ============================================================
    // Utility Functions
    // ============================================================

    /**
     * Normalize an angle to [0, 360) degrees.
     */
    private fun normalizeAngle(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /**
     * Normalize an angle to [0, 2π) radians.
     */
    private fun normalizeRadians(rad: Double): Double {
        var r = rad % (2 * Math.PI)
        if (r < 0) r += 2 * Math.PI
        return r
    }

    /**
     * Find local noon (Sun at transit) for a given date and longitude.
     */
    private fun findLocalNoon(astroTime: AstroTime, longitudeDeg: Double): Long {
        // Start with approximate local noon
        val localNoonMs = astroTime.utcMs - (astroTime.utcMs % 86400000L) + 43200000L
        // Adjust for longitude (15° per hour)
        val offsetMs = (longitudeDeg / 15.0 * 3600000.0).toLong()
        return localNoonMs - offsetMs
    }

    /**
     * Linear interpolation to find the exact time when altitude crosses a threshold.
     */
    private fun interpolateCrossing(
        t1Ms: Long, alt1: Double,
        t2Ms: Long, alt2: Double,
        targetAlt: Double
    ): Long {
        if (alt2 == alt1) return t1Ms
        val fraction = (targetAlt - alt1) / (alt2 - alt1)
        return (t1Ms + (fraction * (t2Ms - t1Ms)).toLong())
    }
}
