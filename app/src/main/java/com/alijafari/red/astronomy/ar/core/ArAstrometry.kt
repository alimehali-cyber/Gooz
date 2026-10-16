package com.alijafari.red.astronomy.ar.core

import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.FrameTransformationEngine
import com.alijafari.red.astronomy.astro_engine.VSOP87Engine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Time scales for the AR pipeline.
 *
 * UTC → TT uses the exact IERS leap-second table (TAI − UTC) from 1972 onward, so that
 * TT − UTC = 32.184 s + (TAI − UTC) (69.184 s since 2017-01-01). Outside the table (before 1972)
 * the app-wide ΔT model of [AstroTime] is used. UT1 is taken equal to UTC; |UT1 − UTC| < 0.9 s by
 * definition of UTC, i.e. < 13.5″ of Earth rotation, far below the AR error budget.
 */
object ArTimeScales {
    const val TT_MINUS_TAI = 32.184
    const val JD_UNIX_EPOCH = 2440587.5

    /** (JD of UTC midnight at which the value takes effect, TAI − UTC in seconds). IERS Bulletin C. */
    private val LEAP_TABLE: Array<DoubleArray> = arrayOf(
        doubleArrayOf(2441317.5, 10.0), // 1972-01-01
        doubleArrayOf(2441499.5, 11.0), // 1972-07-01
        doubleArrayOf(2441683.5, 12.0), // 1973-01-01
        doubleArrayOf(2442048.5, 13.0), // 1974-01-01
        doubleArrayOf(2442413.5, 14.0), // 1975-01-01
        doubleArrayOf(2442778.5, 15.0), // 1976-01-01
        doubleArrayOf(2443144.5, 16.0), // 1977-01-01
        doubleArrayOf(2443509.5, 17.0), // 1978-01-01
        doubleArrayOf(2443874.5, 18.0), // 1979-01-01
        doubleArrayOf(2444239.5, 19.0), // 1980-01-01
        doubleArrayOf(2444786.5, 20.0), // 1981-07-01
        doubleArrayOf(2445151.5, 21.0), // 1982-07-01
        doubleArrayOf(2445516.5, 22.0), // 1983-07-01
        doubleArrayOf(2446247.5, 23.0), // 1985-07-01
        doubleArrayOf(2447161.5, 24.0), // 1988-01-01
        doubleArrayOf(2447892.5, 25.0), // 1990-01-01
        doubleArrayOf(2448257.5, 26.0), // 1991-01-01
        doubleArrayOf(2448804.5, 27.0), // 1992-07-01
        doubleArrayOf(2449169.5, 28.0), // 1993-07-01
        doubleArrayOf(2449534.5, 29.0), // 1994-07-01
        doubleArrayOf(2450083.5, 30.0), // 1996-01-01
        doubleArrayOf(2450630.5, 31.0), // 1997-07-01
        doubleArrayOf(2451179.5, 32.0), // 1999-01-01
        doubleArrayOf(2453736.5, 33.0), // 2006-01-01
        doubleArrayOf(2454832.5, 34.0), // 2009-01-01
        doubleArrayOf(2456109.5, 35.0), // 2012-07-01
        doubleArrayOf(2457204.5, 36.0), // 2015-07-01
        doubleArrayOf(2457754.5, 37.0)  // 2017-01-01 (no leap second announced since)
    )

    fun jdUtcFromEpochMillis(ms: Long): Double = ms / 86_400_000.0 + JD_UNIX_EPOCH

    /** TT − UTC in seconds. */
    fun ttMinusUtcSeconds(jdUtc: Double): Double {
        if (jdUtc < LEAP_TABLE[0][0]) {
            // Pre-1972: fall back to the application's ΔT polynomial (TT − UT1 ≈ TT − UTC).
            return AstroTime.fromJd(jdUtc).deltaT
        }
        var taiMinusUtc = LEAP_TABLE[0][1]
        for (row in LEAP_TABLE) {
            if (jdUtc >= row[0]) taiMinusUtc = row[1] else break
        }
        return TT_MINUS_TAI + taiMinusUtc
    }

    fun jdTtFromJdUtc(jdUtc: Double): Double = jdUtc + ttMinusUtcSeconds(jdUtc) / 86400.0

    /** Decimal (Julian) year, used for the geomagnetic model and proper motions. */
    fun decimalYearFromEpochMillis(ms: Long): Double = 2000.0 + (jdUtcFromEpochMillis(ms) - 2451545.0) / 365.25
}

/**
 * Atmospheric refraction with the observer's measured (or standard-atmosphere) pressure and
 * temperature.
 *
 *  - True → apparent: Sæmundsson (1986), R = 1.02′ · cot(h + 10.3°/(h + 5.11°)), as given in
 *    Meeus, *Astronomical Algorithms* 2nd ed., eq. 16.4, scaled by (P/1010 hPa)·(283 K/T).
 *  - Apparent → true: Bennett (1982), R = 1′ · cot(hₐ + 7.31°/(hₐ + 4.4°)) (Meeus eq. 16.3),
 *    with the same pressure/temperature scaling. The two formulas are mutual inverses to ~0.1′.
 */
object ArRefraction {
    const val STANDARD_PRESSURE_HPA = 1010.0
    const val STANDARD_TEMPERATURE_C = 10.0

    /** ISO 2533 standard-atmosphere pressure (hPa) at a height above mean sea level (m). */
    fun standardPressureHpa(heightM: Double): Double =
        1013.25 * Math.pow(1.0 - 2.25577e-5 * heightM.coerceIn(-500.0, 11000.0), 5.25588)

    /** ISO 2533 standard-atmosphere temperature (°C) at a height above mean sea level (m). */
    fun standardTemperatureC(heightM: Double): Double = 15.0 - 0.0065 * heightM.coerceIn(-500.0, 11000.0)

    private fun scale(pressureHpa: Double, temperatureC: Double): Double =
        (pressureHpa / 1010.0) * (283.0 / (273.0 + temperatureC.coerceIn(-90.0, 60.0)))

    /** Refraction (degrees) to ADD to a true (geometric) altitude in degrees. Zero well below the horizon. */
    fun refractionFromTrueDeg(trueAltDeg: Double, pressureHpa: Double, temperatureC: Double): Double {
        if (trueAltDeg < -1.5 || trueAltDeg > 90.0) return 0.0
        val h = trueAltDeg
        val rArcmin = 1.02 / tan(Math.toRadians(h + 10.3 / (h + 5.11)))
        return max(0.0, rArcmin * scale(pressureHpa, temperatureC) / 60.0)
    }

    /** Refraction (degrees) to SUBTRACT from an apparent altitude in degrees. Zero well below the horizon. */
    fun refractionFromApparentDeg(apparentAltDeg: Double, pressureHpa: Double, temperatureC: Double): Double {
        if (apparentAltDeg < -1.0 || apparentAltDeg > 90.0) return 0.0
        val h = apparentAltDeg
        val rArcmin = 1.0 / tan(Math.toRadians(h + 7.31 / (h + 4.4)))
        return max(0.0, rArcmin * scale(pressureHpa, temperatureC) / 60.0)
    }
}

/**
 * Observer (WGS84 geodetic) plus the local atmosphere used for refraction.
 *
 * @param heightM height above mean sea level in metres (used for the standard-atmosphere fallback
 *        and the topocentric position; the ellipsoid/geoid difference is irrelevant at this level).
 */
data class ArObserver(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val heightM: Double = 0.0,
    val pressureHpa: Double = ArRefraction.standardPressureHpa(heightM),
    val temperatureC: Double = ArRefraction.standardTemperatureC(heightM)
)

/**
 * One epoch of the AR sky: every matrix needed to turn catalogue positions into apparent
 * topocentric East-North-Up unit vectors.
 *
 * Model (IAU 2006/2000B, as in the IERS Conventions 2010 / SOFA):
 *  - Bias-precession-nutation `NPB` from the Fukushima–Williams angles of IAU 2006 precession
 *    (Capitaine et al. 2003; SOFA `iauPfw06`, `iauFw2m`), with IAU 2000B nutation
 *    ([FrameTransformationEngine.calculateNutationIAU2000B], McCarthy & Luzum 2003).
 *  - Annual + diurnal aberration with the relativistic formula of SOFA `iauAb` (light deflection
 *    by the Sun, ≤ 0.004″ at 90° elongation, is neglected).
 *  - Earth rotation: GMST from the Earth Rotation Angle (IERS 2010 eq. 5.15, 5.32) plus the
 *    equation of the equinoxes → GAST; UT1 ≈ UTC.
 *  - Local frame: geodetic East-North-Up of the WGS84 observer; refraction from [ArRefraction].
 *
 * All vectors use x = East, y = North, z = Up in the local frame.
 */
class ArSkyFrame(val jdUtc: Double, val observer: ArObserver) {

    val jdTt: Double = ArTimeScales.jdTtFromJdUtc(jdUtc)

    /** Julian centuries TT from J2000.0. */
    val t: Double = (jdTt - 2451545.0) / 36525.0

    /** Julian years TT from J2000.0 (proper-motion epoch difference). */
    val yearsSinceJ2000: Double = (jdTt - 2451545.0) / 365.25

    val dpsi: Double
    val deps: Double
    val epsA: Double

    /** GCRS → mean equator & equinox of date (bias + precession). */
    val pMatrix: DoubleArray

    /** GCRS → true equator & equinox of date (bias + precession + nutation). */
    val npbMatrix: DoubleArray

    /** Mean of date → true of date (nutation). */
    val nMatrix: DoubleArray

    val gmstRad: Double
    val gastRad: Double

    /** Local apparent sidereal time in degrees [0, 360). */
    val lastDeg: Double

    /** True equator of date → local ENU (rotation only). */
    val enuFromTod: DoubleArray

    /** GCRS → local ENU. */
    val enuFromGcrs: DoubleArray

    /** Mean equator of date → local ENU. */
    val enuFromMod: DoubleArray

    /** Observer velocity (Earth orbital + diurnal) in the GCRS, units of c. */
    val velocityGcrs: DoubleArray

    /** Observer geocentric position in the true-of-date frame, kilometres. */
    val observerTodKm: DoubleArray

    private val bm1: Double

    init {
        val tc = t
        // --- IAU 2006 Fukushima–Williams angles (SOFA iauPfw06), arcseconds → radians -------------
        val gamb = (-0.052928 + (10.556378 + (0.4932044 + (-0.00031238 + (-0.000002788 +
            0.0000000260 * tc) * tc) * tc) * tc) * tc) * AngleUtil.ARCSEC
        val phib = (84381.412819 + (-46.811016 + (0.0511268 + (0.00053289 + (-0.000000440 +
            (-0.0000000176) * tc) * tc) * tc) * tc) * tc) * AngleUtil.ARCSEC
        val psib = (-0.041775 + (5038.481484 + (1.5584175 + (-0.00018522 + (-0.000026452 +
            (-0.0000000148) * tc) * tc) * tc) * tc) * tc) * AngleUtil.ARCSEC
        // Mean obliquity IAU 2006 (SOFA iauObl06)
        epsA = (84381.406 + (-46.836769 + (-0.0001831 + (0.00200340 + (-0.000000576 +
            (-0.0000000434) * tc) * tc) * tc) * tc) * tc) * AngleUtil.ARCSEC

        val nut = FrameTransformationEngine().calculateNutationIAU2000B(AstroTime.fromJd(jdUtc))
        dpsi = Math.toRadians(nut.deltaPsiDeg)
        deps = Math.toRadians(nut.deltaEpsilonDeg)

        // iauFw2m: R = R1(−ε) · R3(−ψ) · R1(φ̄) · R3(γ̄)
        pMatrix = fw2m(gamb, phib, psib, epsA)
        npbMatrix = fw2m(gamb, phib, psib + dpsi, epsA + deps)
        nMatrix = M3.mul(npbMatrix, M3.transpose(pMatrix))

        // --- Earth rotation -------------------------------------------------------------------------
        val du = jdUtc - 2451545.0 // UT1 ≈ UTC
        val frac = du - floor(du)
        val era = AngleUtil.wrap2Pi(2.0 * PI * (frac + 0.7790572732640 + 0.00273781191135448 * du))
        val gmstPoly = (0.014506 + (4612.156534 + (1.3915817 + (-0.00000044 + (-0.000029956 +
            (-0.0000000368) * tc) * tc) * tc) * tc) * tc) * AngleUtil.ARCSEC
        gmstRad = AngleUtil.wrap2Pi(era + gmstPoly)
        // Equation of the equinoxes (IAU 2000): Δψ cos εA + complementary terms (Ω only, µas level).
        val omega = Math.toRadians(125.04455501 - 1934.1362891 * tc)
        val eqeq = dpsi * cos(epsA) + (0.00264096 * sin(omega) + 0.00006352 * sin(2.0 * omega)) * AngleUtil.ARCSEC
        gastRad = AngleUtil.wrap2Pi(gmstRad + eqeq)
        lastDeg = AngleUtil.wrap360(Math.toDegrees(gastRad) + observer.longitudeDeg)

        // --- Local frame ----------------------------------------------------------------------------
        val lat = Math.toRadians(observer.latitudeDeg.coerceIn(-89.999999, 89.999999))
        val lon = Math.toRadians(observer.longitudeDeg)
        val sl = sin(lat); val cl = cos(lat); val so = sin(lon); val co = cos(lon)
        val enuFromEcef = doubleArrayOf(
            -so, co, 0.0,
            -sl * co, -sl * so, cl,
            cl * co, cl * so, sl
        )
        enuFromTod = M3.mul(enuFromEcef, M3.rotZ(gastRad))
        enuFromGcrs = M3.mul(enuFromTod, npbMatrix)
        enuFromMod = M3.mul(enuFromTod, nMatrix)

        // Observer geocentric position (WGS84) in ECEF, then true-of-date.
        val a = 6378.137
        val f = 1.0 / 298.257223563
        val e2 = f * (2.0 - f)
        val nRad = a / sqrt(1.0 - e2 * sl * sl)
        val hKm = observer.heightM / 1000.0
        val ecef = doubleArrayOf((nRad + hKm) * cl * co, (nRad + hKm) * cl * so, (nRad * (1.0 - e2) + hKm) * sl)
        observerTodKm = M3.mulTVec(M3.rotZ(gastRad), ecef)

        // --- Observer velocity --------------------------------------------------------------------
        val vOrbit = earthHeliocentricVelocityGcrsAuPerDay(jdUtc, pMatrix, epsA)
        val cAuPerDay = 173.1446326846693
        // Diurnal: ω × r in true-of-date, km/s → AU/day, then to GCRS.
        val omegaEarth = 7.292115e-5 // rad/s
        val vDiurnalTodKmS = doubleArrayOf(-omegaEarth * observerTodKm[1], omegaEarth * observerTodKm[0], 0.0)
        val kmPerSecToAuPerDay = 86400.0 / 149597870.7
        val vDiurnalGcrs = M3.mulTVec(npbMatrix, doubleArrayOf(
            vDiurnalTodKmS[0] * kmPerSecToAuPerDay,
            vDiurnalTodKmS[1] * kmPerSecToAuPerDay,
            0.0
        ))
        velocityGcrs = doubleArrayOf(
            (vOrbit[0] + vDiurnalGcrs[0]) / cAuPerDay,
            (vOrbit[1] + vDiurnalGcrs[1]) / cAuPerDay,
            (vOrbit[2] + vDiurnalGcrs[2]) / cAuPerDay
        )
        bm1 = sqrt(1.0 - V3.dot(velocityGcrs, velocityGcrs))
    }

    /**
     * Applies stellar (annual + diurnal) aberration to a GCRS unit vector in place
     * (SOFA `iauAb` without light deflection).
     */
    fun aberrateGcrsInPlace(p: DoubleArray) {
        val v = velocityGcrs
        val pdv = p[0] * v[0] + p[1] * v[1] + p[2] * v[2]
        val w1 = 1.0 + pdv / (1.0 + bm1)
        val x = bm1 * p[0] + w1 * v[0]
        val y = bm1 * p[1] + w1 * v[1]
        val z = bm1 * p[2] + w1 * v[2]
        val n = sqrt(x * x + y * y + z * z)
        p[0] = x / n; p[1] = y / n; p[2] = z / n
    }

    /**
     * Apparent topocentric ENU unit vector (before refraction) of a star / deep-sky object given by
     * its ICRS (≈ J2000) direction and optional proper motion.
     *
     * @param pmRaCosDecArcsecYr μα* = μα·cos δ in arcsec per Julian year (Hipparcos/BSC5 convention)
     * @param pmDecArcsecYr μδ in arcsec per Julian year
     */
    fun enuFromCatalog(
        raDeg: Double,
        decDeg: Double,
        pmRaCosDecArcsecYr: Double = 0.0,
        pmDecArcsecYr: Double = 0.0,
        out: DoubleArray = DoubleArray(3)
    ): DoubleArray {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        val sa = sin(ra); val ca = cos(ra); val sd = sin(dec); val cd = cos(dec)
        out[0] = cd * ca; out[1] = cd * sa; out[2] = sd
        if (pmRaCosDecArcsecYr != 0.0 || pmDecArcsecYr != 0.0) {
            // Linear space motion on the tangent plane: u(t) = u0 + Δt (μα* p + μδ q), p = ∂u/∂α / cos δ, q = ∂u/∂δ.
            val dt = yearsSinceJ2000
            val ma = pmRaCosDecArcsecYr * AngleUtil.ARCSEC * dt
            val md = pmDecArcsecYr * AngleUtil.ARCSEC * dt
            out[0] += ma * (-sa) + md * (-sd * ca)
            out[1] += ma * ca + md * (-sd * sa)
            out[2] += md * cd
            V3.normalizeInPlace(out)
        }
        aberrateGcrsInPlace(out)
        return M3.mulVec(enuFromGcrs, out, out)
    }

    /** ENU unit vector (before refraction) of an apparent true-equator-of-date direction (RA/Dec of date). */
    fun enuFromTrueOfDate(raDeg: Double, decDeg: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
        V3.fromSpherical(Math.toRadians(raDeg), Math.toRadians(decDeg), out)
        return M3.mulVec(enuFromTod, out, out)
    }

    /**
     * ENU unit vector (before refraction) of a geometric (light-time corrected, no aberration)
     * mean-equator-of-date direction, optionally at a finite geocentric distance (AU) for parallax.
     * Aberration is applied here.
     */
    fun enuFromMeanOfDateGeometric(raDeg: Double, decDeg: Double, distanceAu: Double = 0.0, out: DoubleArray = DoubleArray(3)): DoubleArray {
        V3.fromSpherical(Math.toRadians(raDeg), Math.toRadians(decDeg), out)
        // mean of date → GCRS for the aberration step
        M3.mulTVec(pMatrix, out, out)
        aberrateGcrsInPlace(out)
        // GCRS → true of date
        M3.mulVec(npbMatrix, out, out)
        if (distanceAu > 0.0) {
            val dKm = distanceAu * 149597870.7
            out[0] = out[0] * dKm - observerTodKm[0]
            out[1] = out[1] * dKm - observerTodKm[1]
            out[2] = out[2] * dKm - observerTodKm[2]
            V3.normalizeInPlace(out)
        }
        return M3.mulVec(enuFromTod, out, out)
    }

    /** ENU → true-of-date unit vector (inverse rotation, no refraction handling). */
    fun todFromEnu(enu: DoubleArray, out: DoubleArray = DoubleArray(3)): DoubleArray = M3.mulTVec(enuFromTod, enu, out)

    /** ENU → GCRS unit vector (inverse rotation; aberration not removed, < 21″). */
    fun gcrsFromEnu(enu: DoubleArray, out: DoubleArray = DoubleArray(3)): DoubleArray = M3.mulTVec(enuFromGcrs, enu, out)

    /** Applies refraction in place to a topocentric ENU unit vector (true → apparent). */
    fun refractInPlace(enu: DoubleArray) {
        val altDeg = Math.toDegrees(asin(enu[2].coerceIn(-1.0, 1.0)))
        val r = ArRefraction.refractionFromTrueDeg(altDeg, observer.pressureHpa, observer.temperatureC)
        if (r == 0.0) return
        setAltitudeInPlace(enu, altDeg + r)
    }

    companion object {
        /** SOFA iauFw2m: R = R1(−eps) · R3(−psi) · R1(phib) · R3(gamb). */
        fun fw2m(gamb: Double, phib: Double, psi: Double, eps: Double): DoubleArray {
            var r = M3.rotZ(gamb)
            r = M3.mul(M3.rotX(phib), r)
            r = M3.mul(M3.rotZ(-psi), r)
            r = M3.mul(M3.rotX(-eps), r)
            return r
        }

        /** Keeps the azimuth of an ENU vector and sets its altitude (degrees). */
        fun setAltitudeInPlace(enu: DoubleArray, altDeg: Double) {
            val h = hypot2(enu[0], enu[1])
            val alt = Math.toRadians(altDeg)
            if (h < 1e-12) {
                enu[2] = sin(alt)
                return
            }
            val c = cos(alt) / h
            enu[0] *= c
            enu[1] *= c
            enu[2] = sin(alt)
        }

        fun altitudeDeg(enu: DoubleArray): Double = Math.toDegrees(asin(enu[2].coerceIn(-1.0, 1.0)))

        /** Azimuth in degrees [0, 360), measured from North through East. */
        fun azimuthDeg(enu: DoubleArray): Double {
            if (abs(enu[0]) < 1e-15 && abs(enu[1]) < 1e-15) return 0.0
            return AngleUtil.wrap360(Math.toDegrees(atan2(enu[0], enu[1])))
        }

        fun enuFromAltAz(altDeg: Double, azDeg: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
            val a = Math.toRadians(altDeg)
            val z = Math.toRadians(azDeg)
            out[0] = cos(a) * sin(z)
            out[1] = cos(a) * cos(z)
            out[2] = sin(a)
            return out
        }

        private fun hypot2(x: Double, y: Double): Double = sqrt(x * x + y * y)

        /**
         * Heliocentric velocity of the Earth (AU/day) in the GCRS, from a symmetric difference of the
         * VSOP87D series (heliocentric ecliptic & equinox of date). The Sun's barycentric velocity
         * (≤ 0.015 km/s, ≤ 0.01″ of aberration) is neglected.
         */
        fun earthHeliocentricVelocityGcrsAuPerDay(jdUtc: Double, pMatrix: DoubleArray, epsA: Double): DoubleArray {
            val vsop = VSOP87Engine()
            val h = 0.05 // days
            val p1 = vsop.calculate(VSOP87Engine.Planet.EARTH, AstroTime.fromJd(jdUtc + h))
            val p0 = vsop.calculate(VSOP87Engine.Planet.EARTH, AstroTime.fromJd(jdUtc - h))
            val r1 = sphToRect(p1.longitudeDeg, p1.latitudeDeg, p1.distanceAu)
            val r0 = sphToRect(p0.longitudeDeg, p0.latitudeDeg, p0.distanceAu)
            val vEcl = doubleArrayOf((r1[0] - r0[0]) / (2 * h), (r1[1] - r0[1]) / (2 * h), (r1[2] - r0[2]) / (2 * h))
            // Ecliptic of date → mean equator of date: vector rotation by +εA about x (= frame R1(−εA)).
            val ce = cos(epsA); val se = sin(epsA)
            val vMod = doubleArrayOf(vEcl[0], vEcl[1] * ce - vEcl[2] * se, vEcl[1] * se + vEcl[2] * ce)
            // Mean of date → GCRS
            return M3.mulTVec(pMatrix, vMod)
        }

        private fun sphToRect(lonDeg: Double, latDeg: Double, r: Double): DoubleArray {
            val l = Math.toRadians(lonDeg); val b = Math.toRadians(latDeg)
            return doubleArrayOf(r * cos(b) * cos(l), r * cos(b) * sin(l), r * sin(b))
        }
    }
}
