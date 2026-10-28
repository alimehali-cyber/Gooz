package com.alijafari.red.astronomy.astro_engine

import org.shredzone.commons.suncalc.MoonTimes
import java.util.Calendar
import java.util.Date
import kotlin.math.*

/**
 * High-precision Moon calculation engine based on ELP2000-82B and Jean Meeus
 * Astronomical Algorithms (Chapters 48, 49, 53).
 */
object MoonEngine {

    private val lunarSolar = LunarSolarEngine()
    private const val DEG2RAD = Math.PI / 180.0
    private const val RAD2DEG = 180.0 / Math.PI
    private const val SYNODIC_MONTH_DAYS = 29.530588853

    data class MoonPosition(
        val raDeg: Double,
        val decDeg: Double,
        val distanceKm: Double,
        val phaseAngleDeg: Double,
        val illuminatedFraction: Double,
        val ageDays: Double,
        val elongationDeg: Double
    )

    data class MoonData(
        val raDeg: Double,
        val decDeg: Double,
        val phaseNameEn: String,
        val phaseNameFa: String,
        val illuminationPercent: Double,
        val ageDays: Double,
        val phaseAngleRad: Double,
        val distanceKm: Double,
        val altitudeDeg: Double = 0.0,
        val azimuthDeg: Double = 0.0,
        val librationLonDeg: Double = 0.0,
        val librationLatDeg: Double = 0.0,
        val brightLimbAngleDeg: Double = 0.0,
        val earthshineFraction: Double = 0.0,
        val angularDiameterArcmin: Double = 31.0,
        val moonriseTimeMs: Long? = null,
        val moonsetTimeMs: Long? = null,
        val nasaFrameNumber: Int = 1
    )

    data class UpcomingPhaseInfo(
        val phaseNameEn: String,
        val phaseNameFa: String,
        val dateMs: Long,
        val daysFromNow: Int
    )

    /**
     * Calculates geocentric Moon position and illumination parameters.
     * Implements Jean Meeus Astronomical Algorithms Chapter 48.
     */
    fun calculateMoon(astroTime: AstroTime): MoonPosition {
        val lunar = lunarSolar.calculateMoon(astroTime)
        val sun = lunarSolar.calculateSun(astroTime)

        // Geocentric angular separation between Sun and Moon (elongation psi)
        val psiDeg = calculateAngularSeparation(lunar.raDeg, lunar.decDeg, sun.raDeg, sun.decDeg)

        // Selenocentric phase angle i (Sun-Moon-Earth angle, Meeus eq 48.3)
        val rKm = sun.distanceAu * 149597870.7
        val deltaKm = lunar.distanceKm
        val psiRad = psiDeg * DEG2RAD
        val phaseAngleRad = atan2(rKm * sin(psiRad), deltaKm - rKm * cos(psiRad))
        val phaseAngleDeg = (phaseAngleRad * RAD2DEG + 360.0) % 360.0

        // Illuminated fraction of the Moon disk k (Meeus eq 48.1)
        // At New Moon: phaseAngle ~ 180° -> cos(180) = -1 -> k = 0
        // At Full Moon: phaseAngle ~ 0° -> cos(0) = 1 -> k = 1
        val illuminated = ((1.0 + cos(phaseAngleRad)) / 2.0).coerceIn(0.0, 1.0)

        // Ecliptic longitude elongation (0° at New Moon, 90° First Quarter, 180° Full Moon, 270° Last Quarter)
        val elongationDeg = calculateEclipticElongation(lunar.apparentLongitudeDeg, sun.apparentLongitudeDeg)
        val ageDays = (elongationDeg / 360.0) * SYNODIC_MONTH_DAYS

        return MoonPosition(
            raDeg = lunar.raDeg,
            decDeg = lunar.decDeg,
            distanceKm = lunar.distanceKm,
            phaseAngleDeg = phaseAngleDeg,
            illuminatedFraction = illuminated,
            ageDays = ageDays,
            elongationDeg = elongationDeg
        )
    }

    /**
     * Calculates topocentric Moon data for observer location.
     */
    fun calculateMoon(
        jd: Double,
        latitude: Double = 30.1141,
        longitude: Double = 51.5217,
        elevationM: Double = 0.0,
        computeRiseSet: Boolean = true
    ): MoonData {
        val astroTime = AstroTime.fromJd(jd)
        val lunar = lunarSolar.calculateMoon(astroTime)
        val sun = lunarSolar.calculateSun(astroTime)

        val distanceKm = lunar.distanceKm
        val raDeg = lunar.raDeg
        val decDeg = lunar.decDeg

        val lastDeg = TimeEngine.getLAST(jd, longitude)
        val topoEq = CoordinateEngine.geocentricToTopocentric(
            geocentric = CoordinateEngine.Equatorial(raDeg, decDeg),
            geocentricDistanceKm = distanceKm,
            lastDeg = lastDeg,
            latitudeDeg = latitude,
            elevationM = elevationM
        )

        // Accurate Phase Angle and Illumination Fraction (Meeus Chapter 48)
        val psiDeg = calculateAngularSeparation(lunar.raDeg, lunar.decDeg, sun.raDeg, sun.decDeg)
        val rKm = sun.distanceAu * 149597870.7
        val psiRad = psiDeg * DEG2RAD
        val phaseAngleRad = atan2(rKm * sin(psiRad), distanceKm - rKm * cos(psiRad))
        val illuminatedFraction = ((1.0 + cos(phaseAngleRad)) / 2.0).coerceIn(0.0, 1.0)
        val illuminationPercent = (illuminatedFraction * 100.0).coerceIn(0.0, 100.0)

        // Ecliptic elongation & true lunar age
        val elongationDeg = calculateEclipticElongation(lunar.apparentLongitudeDeg, sun.apparentLongitudeDeg)
        val ageDays = (elongationDeg / 360.0) * SYNODIC_MONTH_DAYS

        // Standard IAU Astronomical Phase Nomenclature based on elongation
        val (phaseEn, phaseFa) = when {
            elongationDeg < 11.25 || elongationDeg >= 348.75 -> "New Moon" to "ماه نو"
            elongationDeg < 78.75 -> "Waxing Crescent" to "هلال فزاینده"
            elongationDeg < 101.25 -> "First Quarter" to "تربیع اول"
            elongationDeg < 168.75 -> "Waxing Gibbous" to "احدب فزاینده"
            elongationDeg < 191.25 -> "Full Moon" to "بدر کامل (ماه تمام)"
            elongationDeg < 258.75 -> "Waning Gibbous" to "احدب کاهنده"
            elongationDeg < 281.25 -> "Last Quarter" to "تربیع دوم"
            else -> "Waning Crescent" to "هلال کاهنده"
        }

        // Optical & Physical Libration (Meeus Chapter 53)
        val T = astroTime.jcTt
        val omega = (125.0445479 - 1934.1362891 * T + 0.0020754 * T * T) % 360.0
        val lGeo = lunar.apparentLongitudeDeg
        val bGeo = lunar.apparentLatitudeDeg
        val bRad = bGeo * DEG2RAD
        val W = lGeo - omega
        val W_rad = W * DEG2RAD
        val I_incl = 1.54242 * DEG2RAD
        val sinb = sin(bRad)
        val cosb = cos(bRad)
        val sinL_O = sin(W_rad)
        val cosL_O = cos(W_rad)

        val sinbLibr = sinb * cos(I_incl) - cosb * sin(I_incl) * sinL_O
        val librationLatDeg = asin(sinbLibr.coerceIn(-1.0, 1.0)) * RAD2DEG
        val librationLonDeg = atan2(-sinL_O * cosb * cos(I_incl) + sinb * sin(I_incl), cosL_O * cosb) * RAD2DEG

        // Position angle of the illuminated limb chi (Meeus eq 48.5)
        val sunRaRad = sun.raDeg * DEG2RAD
        val sunDecRad = sun.decDeg * DEG2RAD
        val moonRaRad = topoEq.raDeg * DEG2RAD
        val moonDecRad = topoEq.decDeg * DEG2RAD

        val numChi = cos(sunDecRad) * sin(sunRaRad - moonRaRad)
        val denChi = sin(sunDecRad) * cos(moonDecRad) - cos(sunDecRad) * sin(moonDecRad) * cos(sunRaRad - moonRaRad)
        var brightLimbAngleDeg = atan2(numChi, denChi) * RAD2DEG
        if (brightLimbAngleDeg < 0) brightLimbAngleDeg += 360.0

        // Earthshine: sunlight reflected by Earth onto the Moon's night side. Its
        // brightness tracks Earth's illuminated fraction as seen from the Moon,
        // which is (1 - k) where k is the Moon's illuminated fraction as seen from
        // here — so it is brightest at new moon and zero at full moon. Stored as
        // a FRACTION 0..1; MoonDetailWindow multiplies by 100 for display.
        val earthshineFraction = (1.0 - illuminatedFraction).coerceIn(0.0, 1.0)

        // Topocentric angular diameter (arcminutes)
        val angularDiameterArcmin = 2.0 * asin(1737.4 / distanceKm) * RAD2DEG * 60.0

        val horiz = CoordinateEngine.equatorialToHorizontal(
            equatorial = topoEq,
            lastDeg = lastDeg,
            latitudeDeg = latitude,
            observerElevationM = elevationM
        )

        val millis = astroTime.utcMs
        val (riseMs, setMs) = if (computeRiseSet) {
            val targetDate = Date(millis)
            try {
                val times = MoonTimes.compute().on(targetDate).at(latitude, longitude).execute()
                val rise = times.rise?.toInstant()?.toEpochMilli()
                val set = times.set?.toInstant()?.toEpochMilli()
                Pair(rise, set)
            } catch (e: Throwable) {
                Pair(millis - 36000000L, millis + 14000000L)
            }
        } else {
            Pair(null, null)
        }

        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val hourOfDay = cal.get(Calendar.HOUR_OF_DAY)
        val frame = (((dayOfYear - 1) * 24) + hourOfDay + 1).coerceIn(1, 8784)

        return MoonData(
            raDeg = topoEq.raDeg,
            decDeg = topoEq.decDeg,
            phaseNameEn = phaseEn,
            phaseNameFa = phaseFa,
            illuminationPercent = illuminationPercent,
            ageDays = ageDays,
            phaseAngleRad = phaseAngleRad,
            distanceKm = distanceKm,
            altitudeDeg = horiz.altitudeDeg,
            azimuthDeg = horiz.azimuthDeg,
            librationLonDeg = librationLonDeg,
            librationLatDeg = librationLatDeg,
            brightLimbAngleDeg = brightLimbAngleDeg,
            earthshineFraction = earthshineFraction,
            angularDiameterArcmin = angularDiameterArcmin,
            moonriseTimeMs = riseMs,
            moonsetTimeMs = setMs,
            nasaFrameNumber = frame
        )
    }

    /**
     * Apparent topocentric right ascension of the Moon, in degrees, at a given instant.
     *
     * This is the same quantity [calculateMoon] publishes as [MoonData.raDeg] — the geocentric
     * ELP2000 position reduced for the observer's parallax against the local sidereal time —
     * but without the phase, libration, rise/set or NASA-frame work, so it is cheap enough to
     * be evaluated several times per iteration of the Moon's meridian-transit fixed point in
     * [CoordinateEngineLegacy.calculateMoonTransitTimeMs].
     */
    fun topocentricRaDeg(
        jd: Double,
        latitude: Double = 30.1141,
        longitude: Double = 51.5217,
        elevationM: Double = 0.0
    ): Double {
        val lunar = lunarSolar.calculateMoon(AstroTime.fromJd(jd))
        return CoordinateEngine.geocentricToTopocentric(
            geocentric = CoordinateEngine.Equatorial(lunar.raDeg, lunar.decDeg),
            geocentricDistanceKm = lunar.distanceKm,
            lastDeg = TimeEngine.getLAST(jd, longitude),
            latitudeDeg = latitude,
            elevationM = elevationM
        ).raDeg
    }

    /**
     * Computes the precise upcoming timestamps for the four primary phases:
     * New Moon, First Quarter, Full Moon, and Last Quarter using Meeus Chapter 49.
     */
    fun getUpcomingMajorPhases(baseJd: Double): List<UpcomingPhaseInfo> {
        val baseMs = ((baseJd - 2440587.5) * 86400000.0).toLong()
        val engine = EclipseEngine()

        val kBase = floor((baseJd - 2451550.09766) / SYNODIC_MONTH_DAYS).toLong()

        val candidatePhases = mutableListOf<UpcomingPhaseInfo>()

        for (kOffset in 0..2) {
            val k = kBase + kOffset

            // 1. New Moon (phase k + 0.0)
            val nmMs = engine.newMoonTime(k)
            if (nmMs > baseMs) {
                val diffDays = ((nmMs - baseMs) / 86400000.0).roundToInt()
                candidatePhases.add(UpcomingPhaseInfo("New Moon", "ماه نو", nmMs, max(1, diffDays)))
            }

            // 2. First Quarter (phase k + 0.25)
            val fqMs = engine.quarterMoonTime(k, 0.25)
            if (fqMs > baseMs) {
                val diffDays = ((fqMs - baseMs) / 86400000.0).roundToInt()
                candidatePhases.add(UpcomingPhaseInfo("First Quarter", "تربیع اول", fqMs, max(1, diffDays)))
            }

            // 3. Full Moon (phase k + 0.5)
            val fmMs = engine.fullMoonTime(k)
            if (fmMs > baseMs) {
                val diffDays = ((fmMs - baseMs) / 86400000.0).roundToInt()
                candidatePhases.add(UpcomingPhaseInfo("Full Moon", "بدر کامل", fmMs, max(1, diffDays)))
            }

            // 4. Last Quarter (phase k + 0.75)
            val lqMs = engine.quarterMoonTime(k, 0.75)
            if (lqMs > baseMs) {
                val diffDays = ((lqMs - baseMs) / 86400000.0).roundToInt()
                candidatePhases.add(UpcomingPhaseInfo("Last Quarter", "تربیع دوم", lqMs, max(1, diffDays)))
            }
        }

        return candidatePhases.sortedBy { it.dateMs }.take(4)
    }

    private fun calculateAngularSeparation(ra1Deg: Double, dec1Deg: Double, ra2Deg: Double, dec2Deg: Double): Double {
        val r1 = ra1Deg * DEG2RAD
        val d1 = dec1Deg * DEG2RAD
        val r2 = ra2Deg * DEG2RAD
        val d2 = dec2Deg * DEG2RAD

        val cosSep = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(cosSep.coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    private fun calculateEclipticElongation(moonLonDeg: Double, sunLonDeg: Double): Double {
        var elong = moonLonDeg - sunLonDeg
        while (elong < 0.0) elong += 360.0
        while (elong >= 360.0) elong -= 360.0
        return elong
    }
}
