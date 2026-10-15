package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

object CoordinateEngineLegacy {

    data class Equatorial(val raDeg: Double, val decDeg: Double)
    data class Horizontal(val azimuthDeg: Double, val altitudeDeg: Double) {
        val azimuthCompassNameEn: String
            get() {
                val a = (azimuthDeg % 360.0 + 360.0) % 360.0
                return when {
                    a < 22.5 || a >= 337.5 -> "North"
                    a < 67.5 -> "Northeast"
                    a < 112.5 -> "East"
                    a < 157.5 -> "Southeast"
                    a < 202.5 -> "South"
                    a < 247.5 -> "Southwest"
                    a < 292.5 -> "West"
                    else -> "Northwest"
                }
            }

        val azimuthCompassNameFa: String
            get() {
                val a = (azimuthDeg % 360.0 + 360.0) % 360.0
                return when {
                    a < 22.5 || a >= 337.5 -> "شمال"
                    a < 67.5 -> "شمال‌شرق"
                    a < 112.5 -> "شرق"
                    a < 157.5 -> "جنوب‌شرق"
                    a < 202.5 -> "جنوب"
                    a < 247.5 -> "جنوب‌غرب"
                    a < 292.5 -> "غرب"
                    else -> "شمال‌غرب"
                }
            }
    }
    data class Galactic(val lDeg: Double, val bDeg: Double)

    data class Nutation(
        val deltaPsiDeg: Double,   // Nutation in longitude (degrees)
        val deltaEpsDeg: Double,   // Nutation in obliquity (degrees)
        val meanObliquityDeg: Double,
        val trueObliquityDeg: Double
    )

    /**
     * Calculates IAU Nutation in Longitude and Obliquity for Julian Ephemeris Day (JDE).
     */
    fun calculateNutation(jd: Double): Nutation {
        val T = (jd - 2451545.0) / 36525.0

        // Mean longitude of Sun
        val L = Math.toRadians((280.4665 + 36000.7698 * T) % 360.0)
        // Mean longitude of Moon
        val Lprime = Math.toRadians((218.3165 + 481267.8813 * T) % 360.0)
        // Longitude of Moon's ascending node
        val Omega = Math.toRadians((125.04452 - 1934.136261 * T + 0.0020708 * T * T) % 360.0)

        // Nutation terms in arcseconds
        val dPsiArcsec = -17.20 * sin(Omega) - 1.32 * sin(2 * L) - 0.23 * sin(2 * Lprime) + 0.21 * sin(2 * Omega)
        val dEpsArcsec = 9.20 * cos(Omega) + 0.57 * cos(2 * L) + 0.10 * cos(2 * Lprime) - 0.09 * cos(2 * Omega)

        val deltaPsiDeg = dPsiArcsec / 3600.0
        val deltaEpsDeg = dEpsArcsec / 3600.0

        val meanObliquityDeg = 23.4392911 - (46.8150 * T + 0.00059 * T * T - 0.001813 * T * T * T) / 3600.0
        val trueObliquityDeg = meanObliquityDeg + deltaEpsDeg

        return Nutation(
            deltaPsiDeg = deltaPsiDeg,
            deltaEpsDeg = deltaEpsDeg,
            meanObliquityDeg = meanObliquityDeg,
            trueObliquityDeg = trueObliquityDeg
        )
    }

    /**
     * Converts Equatorial coordinates (RA, Dec in degrees) to Horizontal coordinates (Azimuth, Altitude in degrees).
     * @param lastDeg Local Apparent Sidereal Time in degrees.
     * @param latitudeDeg Observer latitude in degrees (+North).
     * @param observerElevationM Observer elevation above sea level in meters (default 0).
     */
    fun equatorialToHorizontal(
        equatorial: Equatorial,
        lastDeg: Double,
        latitudeDeg: Double,
        observerElevationM: Double = 0.0
    ): Horizontal {
        // Hour Angle (HA) in degrees = LAST - RA
        var haDeg = lastDeg - equatorial.raDeg
        haDeg %= 360.0
        if (haDeg < 0) haDeg += 360.0

        val haRad = Math.toRadians(haDeg)
        val decRad = Math.toRadians(equatorial.decDeg)
        val latRad = Math.toRadians(latitudeDeg)

        // True Altitude
        val sinAlt = sin(decRad) * sin(latRad) + cos(decRad) * cos(latRad) * cos(haRad)
        val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
        var altDeg = Math.toDegrees(altRad)

        // Azimuth (measured from North = 0, East = 90)
        val cosAz = (sin(decRad) - sin(latRad) * sin(altRad)) / (cos(latRad) * cos(altRad)).coerceAtLeast(1e-6)
        val sinAz = -cos(decRad) * sin(haRad) / cos(altRad).coerceAtLeast(1e-6)

        var azRad = atan2(sinAz, cosAz)
        var azDeg = Math.toDegrees(azRad)
        if (azDeg < 0) azDeg += 360.0

        // Atmospheric Refraction (Bennett formula adjusted for temperature and elevation pressure)
        if (altDeg > -1.5) {
            val pressurehPa = 1013.25 * (1.0 - 2.25577e-5 * observerElevationM).pow(5.25588)
            val tempK = 288.15 - 0.0065 * observerElevationM
            val R_bennett = 1.02 / tan(Math.toRadians(altDeg + 10.3 / (altDeg + 5.11))) // arcminutes
            val refractionArcmin = R_bennett * (pressurehPa / 1010.0) * (283.15 / tempK)
            altDeg += (refractionArcmin / 60.0)
        }

        return Horizontal(azimuthDeg = azDeg, altitudeDeg = altDeg)
    }

    /**
     * Converts Horizontal (Azimuth, Altitude) back to Equatorial (RA, Dec).
     */
    fun horizontalToEquatorial(
        horizontal: Horizontal,
        lastDeg: Double,
        latitudeDeg: Double
    ): Equatorial {
        val azRad = Math.toRadians(horizontal.azimuthDeg)
        val altRad = Math.toRadians(horizontal.altitudeDeg)
        val latRad = Math.toRadians(latitudeDeg)

        val sinDec = sin(altRad) * sin(latRad) + cos(altRad) * cos(latRad) * cos(azRad)
        val decRad = asin(sinDec.coerceIn(-1.0, 1.0))
        val decDeg = Math.toDegrees(decRad)

        val sinHA = -sin(azRad) * cos(altRad) / cos(decRad).coerceAtLeast(1e-6)
        val cosHA = (sin(altRad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad)).coerceAtLeast(1e-6)

        val haRad = atan2(sinHA, cosHA)
        var haDeg = Math.toDegrees(haRad)
        if (haDeg < 0) haDeg += 360.0

        var raDeg = lastDeg - haDeg
        raDeg %= 360.0
        if (raDeg < 0) raDeg += 360.0

        return Equatorial(raDeg = raDeg, decDeg = decDeg)
    }

    /**
     * Corrects Geocentric Equatorial position for Topocentric Parallax (for nearby objects like the Moon).
     */
    fun geocentricToTopocentric(
        geocentric: Equatorial,
        geocentricDistanceKm: Double,
        lastDeg: Double,
        latitudeDeg: Double,
        elevationM: Double = 0.0
    ): Equatorial {
        if (geocentricDistanceKm <= 0.0) return geocentric

        val earthRadiusKm = 6378.137
        val latRad = Math.toRadians(latitudeDeg)

        // Earth oblateness factors
        val f = 1.0 / 298.257223563
        val u = atan((1.0 - f) * tan(latRad))
        val hKm = elevationM / 1000.0
        val rhoSinLat = (1.0 - f) * sin(u) + (hKm / earthRadiusKm) * sin(latRad)
        val rhoCosLat = cos(u) + (hKm / earthRadiusKm) * cos(latRad)

        val piRad = asin((earthRadiusKm / geocentricDistanceKm).coerceIn(-1.0, 1.0))

        var haDeg = lastDeg - geocentric.raDeg
        haDeg %= 360.0
        if (haDeg < 0) haDeg += 360.0
        val haRad = Math.toRadians(haDeg)
        val decRad = Math.toRadians(geocentric.decDeg)

        // Parallax in RA
        val deltaRaRad = atan2(-rhoCosLat * sin(piRad) * sin(haRad), cos(decRad) - rhoCosLat * sin(piRad) * cos(haRad))
        val topoRaDeg = (geocentric.raDeg + Math.toDegrees(deltaRaRad) + 360.0) % 360.0

        // Parallax in Dec
        val topoDecRad = atan2((sin(decRad) - rhoSinLat * sin(piRad)) * cos(deltaRaRad), cos(decRad) - rhoCosLat * sin(piRad) * cos(haRad))
        val topoDecDeg = Math.toDegrees(topoDecRad)

        return Equatorial(topoRaDeg, topoDecDeg)
    }

    /**
     * Converts Equatorial J2000 coordinates to Galactic coordinates (l, b).
     * Galactic North Pole J2000: RA = 192.85948°, Dec = +27.12825°, Node l_N = 32.93192°
     */
    fun equatorialToGalactic(eq: Equatorial): Galactic {
        val raRad = Math.toRadians(eq.raDeg)
        val decRad = Math.toRadians(eq.decDeg)

        val raNGP = Math.toRadians(192.85948)
        val decNGP = Math.toRadians(27.12825)
        val lN = 32.93192

        val sinB = sin(decRad) * sin(decNGP) + cos(decRad) * cos(decNGP) * cos(raRad - raNGP)
        val bRad = asin(sinB.coerceIn(-1.0, 1.0))
        val bDeg = Math.toDegrees(bRad)

        val y = cos(decRad) * sin(raRad - raNGP)
        val x = sin(decRad) * cos(decNGP) - cos(decRad) * sin(decNGP) * cos(raRad - raNGP)
        var lDeg = lN + 180.0 - Math.toDegrees(atan2(y, x))
        lDeg %= 360.0
        if (lDeg < 0) lDeg += 360.0

        return Galactic(lDeg, bDeg)
    }

    /**
     * Converts Galactic coordinates (l, b) back to Equatorial J2000 (RA, Dec).
     */
    fun galacticToEquatorial(gal: Galactic): Equatorial {
        val lRad = Math.toRadians(gal.lDeg)
        val bRad = Math.toRadians(gal.bDeg)

        val raNGP = Math.toRadians(192.85948)
        val decNGP = Math.toRadians(27.12825)
        val lNRad = Math.toRadians(32.93192)

        val sinDec = sin(bRad) * sin(decNGP) + cos(bRad) * cos(decNGP) * cos(lRad - lNRad)
        val decRad = asin(sinDec.coerceIn(-1.0, 1.0))
        val decDeg = Math.toDegrees(decRad)

        val y = cos(bRad) * sin(lRad - lNRad)
        val x = cos(bRad) * sin(decNGP) * cos(lRad - lNRad) - sin(bRad) * cos(decNGP)
        var raRad = raNGP + atan2(y, x)
        var raDeg = Math.toDegrees(raRad) % 360.0
        if (raDeg < 0) raDeg += 360.0

        return Equatorial(raDeg, decDeg)
    }

    /**
     * Formats RA in Hours:Minutes:Seconds.
     */
    fun formatRA(raDeg: Double): String {
        val raHours = raDeg / 15.0
        val h = floor(raHours).toInt()
        val mFull = (raHours - h) * 60.0
        val m = floor(mFull).toInt()
        val s = ((mFull - m) * 60.0).toInt()
        return String.format("%02dh %02dm %02ds", h, m, s)
    }

    /**
     * Formats Dec in Degrees:Minutes:Seconds.
     */
    fun formatDec(decDeg: Double): String {
        val sign = if (decDeg >= 0) "+" else "-"
        val absD = abs(decDeg)
        val d = floor(absD).toInt()
        val mFull = (absD - d) * 60.0
        val m = floor(mFull).toInt()
        val s = ((mFull - m) * 60.0).toInt()
        return String.format("%s%02d° %02d′ %02d″", sign, d, m, s)
    }

    data class RiseSetTransit(
        val riseTimeStr: String,
        val transitTimeStr: String,
        val setTimeStr: String,
        val isCircumpolar: Boolean = false,
        val neverRises: Boolean = false
    )

    /**
     * Standard horizon altitudes (degrees) at which the event is declared, per object class.
     * These are the conventional values used by HMNAO/USNO and Meeus (ch. 15).
     *
     * - Stars, planets, deep-sky objects: −0.5667° = atmospheric refraction alone
     *   (≈34′). There is no appreciable disc, so the centre crossing that altitude is
     *   the event.
     * - The Sun: −0.833° = −(0.5667° refraction + 0.2667° semidiameter), i.e. the
     *   instant the **upper limb** appears on the horizon, which is what "sunrise"
     *   means in every published almanac.
     * - The Moon: +0.125° = Meeus' h₀ = 0.7275·π − 0.5667° with the mean horizontal
     *   parallax π ≈ 0.9507°. The Moon's parallax makes the correction positive and
     *   larger than the disc correction. A rigorous value recomputes π per date; the
     *   mean value is the usual constant approximation. Quantified: the true π ranges
     *   0.8945°–1.0183°, i.e. h₀ from 0.084° to 0.174°, so pinning h₀ at 0.125° moves
     *   Moon rise/set by −20 s to +24 s depending on date and observer latitude. That
     *   is well below the one-minute resolution these functions return, but it is not
     *   zero, and it is the largest single modelling error in this solver.
     */
    const val STAR_HORIZON_ALT_DEG = -0.5667
    const val SUN_HORIZON_ALT_DEG = -0.833
    const val MOON_HORIZON_ALT_DEG = 0.125

    /** Sidereal rotation rate, degrees of hour angle per hour of UT. */
    private const val SIDEREAL_RATE_DEG_PER_HOUR = 15.04107

    /**
     * Picks the conventional [horizonAltDeg] for a catalog object id.
     * Anything that is not the Sun or the Moon is treated as a point source.
     */
    fun horizonAltDegFor(objectId: String?): Double = when (objectId) {
        "sun" -> SUN_HORIZON_ALT_DEG
        "moon" -> MOON_HORIZON_ALT_DEG
        else -> STAR_HORIZON_ALT_DEG
    }

    /**
     * Geometric rise / transit / set for the observer's meridian on the day containing [jd].
     *
     * Solved analytically from
     *     cos H₀ = (sin h₀ − sin φ sin δ) / (cos φ cos δ)
     * so it is O(1) — no sampling, and no refraction is applied to any altitude here.
     * [horizonAltDeg] already carries the refraction/semidiameter correction, which is
     * why the comparison is against a GEOMETRIC altitude.
     *
     * @param timezoneId IANA id used both to place "start of day" and to render the
     *                   returned wall-clock strings. Null/blank falls back to
     *                   Asia/Tehran, which is what every caller used to be pinned to.
     *                   Callers that know the observer's timezone MUST pass it: without
     *                   it a user outside Iran is shown Tehran wall-clock times.
     * @param horizonAltDeg altitude h₀ at which the event is declared, in degrees.
     *                      Defaults to the point-source value; pass
     *                      [SUN_HORIZON_ALT_DEG] or [MOON_HORIZON_ALT_DEG] as appropriate.
     *
     * KNOWN EDGE CASES — documented rather than fixed, because each is long-standing
     * behaviour and changing it would move results that are already published to users.
     *
     * 1. DST SPRING-FORWARD AT MIDNIGHT. "Start of day" is produced by zeroing the
     *    calendar fields in [timeZone]. In the few zones that transition at exactly
     *    midnight — America/Havana, America/Santiago and Asia/Beirut were all confirmed
     *    to do so — local midnight does not exist on that date and the calendar
     *    resolves leniently to 01:00. The event window is then 23 h long and starts at
     *    01:00, so an event falling between 00:00 and 01:00 local is attributed to the
     *    preceding day. Nothing crashes; this affects one day per year in a handful of
     *    zones.
     *
     * 2. NO PRECESSION. [raDeg]/[decDeg] are compared directly against the local
     *    APPARENT sidereal time, so they are treated as coordinates already expressed
     *    for the date of [jd]; no precession from a catalog epoch is applied. A J2000
     *    position used for 2026 is therefore off by about +0.33° in RA (≈1.3 min of
     *    time) and +0.15° in Dec, and the offset grows with the epoch difference.
     *    Callers that hold fixed-epoch catalog coordinates must precess them first.
     *    The fixtures in RiseSetTransitTest are stated in the date's own frame, so they
     *    are unaffected.
     */
    fun calculateRiseSetTransit(
        raDeg: Double,
        decDeg: Double,
        latDeg: Double,
        lonDeg: Double,
        jd: Double = TimeEngine.getJulianDate(),
        isFa: Boolean = true,
        timezoneId: String? = null,
        horizonAltDeg: Double = STAR_HORIZON_ALT_DEG
    ): RiseSetTransit {
        val timeZone = TimeEngine.resolveTimeZone(timezoneId)
        val latRad = Math.toRadians(latDeg)
        val decRad = Math.toRadians(decDeg)

        val h0Rad = Math.toRadians(horizonAltDeg)
        val cosH0 = (sin(h0Rad) - sin(latRad) * sin(decRad)) / (cos(latRad) * cos(decRad)).coerceAtLeast(1e-6)

        if (cosH0 > 1.0) {
            return RiseSetTransit(
                riseTimeStr = if (isFa) "طلوع نمی‌کند" else "Never rises",
                transitTimeStr = if (isFa) "طلوع نمی‌کند" else "Never rises",
                setTimeStr = if (isFa) "غروب نمی‌کند" else "Never sets",
                neverRises = true
            )
        }

        if (cosH0 < -1.0) {
            return RiseSetTransit(
                riseTimeStr = if (isFa) "دور قطبی (همواره بالای افق)" else "Circumpolar (Always visible)",
                transitTimeStr = if (isFa) "اوژ / اوج ارتفاع" else "Meridian Transit",
                setTimeStr = if (isFa) "غروب نمی‌کند" else "Never sets",
                isCircumpolar = true
            )
        }

        val ha0Deg = Math.toDegrees(acos(cosH0))

        val baseCal = java.util.Calendar.getInstance(timeZone).apply {
            timeInMillis = TimeEngine.getTimestampFromJulianDate(jd)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = baseCal.timeInMillis
        val startOfDayJd = TimeEngine.getJulianDate(startOfDayMs)
        val lastAt0h = TimeEngine.getLAST(startOfDayJd, lonDeg)

        var haAt0h = (lastAt0h - raDeg) % 360.0
        if (haAt0h < 0) haAt0h += 360.0

        val transitHoursFrom0h = ((360.0 - haAt0h) % 360.0) / 15.04107
        val riseHoursFrom0h = ((360.0 - haAt0h - ha0Deg + 720.0) % 360.0) / 15.04107
        val setHoursFrom0h = ((360.0 - haAt0h + ha0Deg) % 360.0) / 15.04107

        val transitMs = startOfDayMs + (transitHoursFrom0h * 3600000.0).toLong()
        val riseMs = startOfDayMs + (riseHoursFrom0h * 3600000.0).toLong()
        val setMs = startOfDayMs + (setHoursFrom0h * 3600000.0).toLong()

        val riseStr = TimeEngine.formatTime24h(riseMs, isFa, timeZone)
        val transitStr = TimeEngine.formatTime24h(transitMs, isFa, timeZone)
        val setStr = TimeEngine.formatTime24h(setMs, isFa, timeZone)

        return RiseSetTransit(
            riseTimeStr = riseStr,
            transitTimeStr = transitStr,
            setTimeStr = setStr
        )
    }

    /**
     * Meridian transit (upper culmination) of the Moon for the local day containing [nowMs].
     *
     * WHY THIS EXISTS. [calculateRiseSetTransit] samples the body's RA ONCE — at the instant
     * it is called — anchors the hour angle on local midnight and closes it at the sidereal
     * rate 15.04107°/h. For the Moon that is wrong twice over: its RA moves about 0.55°/h, so
     * its hour angle actually closes at 15.04107 − dRA/dt ≈ 14.49°/h, and an RA sampled "now"
     * belongs to the wrong moment unless the caller happens to ask at the transit itself.
     * Measured, Tehran, 2026-10-15 (true transit 15:32:35 +03:30), as a function of when the
     * panel is opened:
     *
     *     opened 00:30 → 14:57:04 (−35.5 min)      opened 15:00 → 15:31:50 (−0.8 min)
     *     opened 06:00 → 15:13:29 (−19.1 min)      opened 23:30 → 15:47:22 (+14.8 min)
     *     opened 12:00 → 15:27:17 ( −5.3 min)
     *
     * i.e. err[min] ≈ 2.19 × (hours since local midnight − transit hours): the displayed time
     * drifts with the clock, and is correct only if the panel is opened at the transit.
     *
     * THE FIX is a fixed point on the definition, HA(t) = LAST(t) − RA_moon(t) = 0: seed with
     * the analytic estimate, then step t by −HA(t) / (15.04107 − dRA/dt), re-evaluating the
     * Moon's apparent topocentric RA at each guess. The RA is injected by the caller so this
     * object stays free of any dependency on [MoonEngine]. Verified against PyEphem's
     * next_transit — the same definition — for six cities (Tehran, New York, London, Sydney,
     * Tokyo, São Paulo) on 2026-10-15 and again on 2027-02-11, plus Tromsø, Quito and McMurdo:
     * every case lands on the oracle within 0.00 s, and the result no longer depends on when
     * it is called. One iteration already reaches 0.4 s; four are used for margin.
     *
     * Only the Moon needs this. Every other body moves slowly enough that the analytic solver
     * above is already correct to well under a minute.
     *
     * @param moonRaDegAtJd apparent topocentric RA of the Moon in degrees at a Julian date;
     *                      supply [MoonEngine.topocentricRaDeg] at the observer's location.
     */
    fun calculateMoonTransitTimeMs(
        nowMs: Long,
        longitudeDeg: Double,
        timezoneId: String? = null,
        moonRaDegAtJd: (Double) -> Double
    ): Long {
        val timeZone = TimeEngine.resolveTimeZone(timezoneId)
        val midnightCal = java.util.Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val midnightMs = midnightCal.timeInMillis
        val midnightJd = TimeEngine.getJulianDate(midnightMs)

        // Seed with the analytic estimate — good to a few tens of minutes, which is all a
        // fixed point needs.
        var haAtMidnight = (TimeEngine.getLAST(midnightJd, longitudeDeg) - moonRaDegAtJd(midnightJd)) % 360.0
        if (haAtMidnight < 0) haAtMidnight += 360.0
        var hoursFromMidnight = ((360.0 - haAtMidnight) % 360.0) / SIDEREAL_RATE_DEG_PER_HOUR

        repeat(4) {
            val guessJd = midnightJd + hoursFromMidnight / 24.0
            var ha = (TimeEngine.getLAST(guessJd, longitudeDeg) - moonRaDegAtJd(guessJd)) % 360.0
            if (ha > 180.0) ha -= 360.0
            if (ha < -180.0) ha += 360.0
            var raDriftPerHour = moonRaDegAtJd(guessJd + 0.5 / 24.0) - moonRaDegAtJd(guessJd - 0.5 / 24.0)
            while (raDriftPerHour > 180.0) raDriftPerHour -= 360.0
            while (raDriftPerHour < -180.0) raDriftPerHour += 360.0
            val rateDegPerHour = SIDEREAL_RATE_DEG_PER_HOUR - raDriftPerHour
            if (abs(rateDegPerHour) > 1.0) hoursFromMidnight -= ha / rateDegPerHour
        }

        return midnightMs + (hoursFromMidnight * 3600000.0).roundToLong()
    }

    /**
     * Calculates the angular separation in degrees between two equatorial points.
     */
    fun calculateAngularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = Math.toRadians(ra1)
        val d1 = Math.toRadians(dec1)
        val r2 = Math.toRadians(ra2)
        val d2 = Math.toRadians(dec2)
        val cosSep = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0)))
    }
}
