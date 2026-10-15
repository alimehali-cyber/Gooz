package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

/**
 * Sun engine — delegates to LunarSolarEngine (VSOP87).
 */
object SunEngine {

    private val lunarSolar = LunarSolarEngine()

    data class SunPosition(
        val raDeg: Double,
        val decDeg: Double,
        val distanceAu: Double = 1.0,
        val equationOfTimeMinutes: Double = 0.0,
        val apparentLongitudeDeg: Double = 0.0,
        val eclipticLongitudeDeg: Double = apparentLongitudeDeg,
        val distanceAU: Double = distanceAu,
        val apparentDiameterArcmin: Double = 32.0 / distanceAu
    )

    data class TwilightPhase(
        val nameEn: String,
        val nameFa: String,
        val descriptionEn: String,
        val descriptionFa: String,
        val isDaylight: Boolean
    )

    fun calculateSun(astroTime: AstroTime): SunPosition {
        val solar = lunarSolar.calculateSun(astroTime)
        return SunPosition(
            raDeg = solar.raDeg,
            decDeg = solar.decDeg,
            distanceAu = solar.distanceAu,
            equationOfTimeMinutes = solar.equationOfTimeMinutes,
            apparentLongitudeDeg = solar.apparentLongitudeDeg
        )
    }

    fun calculatePosition(jd: Double): SunPosition {
        val astroTime = AstroTime.fromJd(jd)
        return calculateSun(astroTime)
    }

    fun getSunAltitude(timestampMs: Long, userLatDeg: Double, userLonDeg: Double): Double {
        val jd = TimeEngine.getJulianDate(timestampMs)
        return getSunAltAz(jd, userLatDeg, userLonDeg).altitudeDeg
    }

    fun getSunAltAz(jd: Double, userLatDeg: Double, userLonDeg: Double): CoordinateEngine.Horizontal {
        val sunPos = calculatePosition(jd)
        val lastDeg = TimeEngine.getLAST(jd, userLonDeg)
        return CoordinateEngine.equatorialToHorizontal(
            CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg),
            lastDeg,
            userLatDeg
        )
    }

    /**
     * Determines twilight phase based on Sun altitude.
     */
    fun getTwilightPhase(sunAltitudeDeg: Double): TwilightPhase {
        return when {
            sunAltitudeDeg > 0.0 -> TwilightPhase(
                nameEn = "Daylight",
                nameFa = "روز / روشنایی",
                descriptionEn = "Sun is above horizon",
                descriptionFa = "خورشید بالای افق است",
                isDaylight = true
            )
            sunAltitudeDeg > -6.0 -> TwilightPhase(
                nameEn = "Civil Twilight (Golden Hour)",
                nameFa = "گرگ و میش شهری (ساعت طلایی)",
                descriptionEn = "Brightest stars become visible",
                descriptionFa = "درخشان‌ترین ستارگان پدیدار می‌شوند",
                isDaylight = false
            )
            sunAltitudeDeg > -12.0 -> TwilightPhase(
                nameEn = "Nautical Twilight",
                nameFa = "گرگ و میش دریانوردی",
                descriptionEn = "Navigation stars visible, horizon visible",
                descriptionFa = "ستارگان اصلی ناوبری نمایان می‌شوند",
                isDaylight = false
            )
            sunAltitudeDeg > -18.0 -> TwilightPhase(
                nameEn = "Astronomical Twilight",
                nameFa = "گرگ و میش نجومی",
                descriptionEn = "Sky darkening, deep space targets emerging",
                descriptionFa = "آسمان تاریک شده و جرم‌های اعماق فضا قابل رویت می‌شوند",
                isDaylight = false
            )
            else -> TwilightPhase(
                nameEn = "Night (True Dark)",
                nameFa = "شب کامل نجومی",
                descriptionEn = "Optimal darkness for observation",
                descriptionFa = "تاریکی مطلق و شرایط ایده‌آل برای رصد نجومی",
                isDaylight = false
            )
        }
    }
}
