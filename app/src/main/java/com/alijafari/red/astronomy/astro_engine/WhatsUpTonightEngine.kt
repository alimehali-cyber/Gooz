package com.alijafari.red.astronomy.astro_engine

import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.data.catalog.MeteorShowerCatalog
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObjectType
import com.alijafari.red.astronomy.util.toPersianDigits
import java.util.Calendar

object WhatsUpTonightEngine {

    enum class EventVisibilityStatus {
        OPTIMAL,     // Bright / Highly Visible locally
        GOOD,        // Visible under decent conditions
        MARGINAL,    // Low altitude / Requires equipment
        NOT_VISIBLE  // Not visible locally
    }

    data class TonightEvent(
        val id: String,
        val icon: String,
        val titleEn: String,
        val titleFa: String,
        val explanationEn: String,
        val explanationFa: String,
        val timeOrDateStrEn: String,
        val timeOrDateStrFa: String,
        val visibilityStatus: EventVisibilityStatus,
        val visibilityTextEn: String,
        val visibilityTextFa: String,
        val importanceScore: Int, // Higher = ranked first
        val targetObject: CelestialObject? = null
    )

    fun calculateTonightEvents(
        jd: Double = TimeEngine.getJulianDate(),
        userLatDeg: Double,
        userLonDeg: Double,
        isFa: Boolean
    ): List<TonightEvent> {
        val events = mutableListOf<TonightEvent>()
        val nowMs = TimeEngine.getTimestampFromJulianDate(jd)
        val cal = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply { timeInMillis = nowMs }
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
        val gmstDeg = TimeEngine.getLAST(jd, userLonDeg)

        // 1. Check Solar/Lunar Eclipses TONIGHT (only if occurring today/tonight AND locally visible)
        try {
            val (solarEclipse, lunarEclipse) = EclipseEngine.getNextEclipses(nowMs, userLatDeg, userLonDeg)
            val solarHoursDiff = kotlin.math.abs(solarEclipse.localPeakTimeMs - nowMs) / 3600000.0
            val solarOngoing = nowMs in solarEclipse.localStartTimeMs..solarEclipse.localEndTimeMs
            if ((solarHoursDiff <= 18.0 || solarOngoing) && solarEclipse.isLocallyVisible) {
                events.add(
                    TonightEvent(
                        id = solarEclipse.event.id,
                        icon = "sun",
                        titleEn = solarEclipse.localNameEn,
                        titleFa = solarEclipse.localNameFa,
                        explanationEn = solarEclipse.event.descriptionEn,
                        explanationFa = solarEclipse.event.descriptionFa,
                        timeOrDateStrEn = TimeEngine.formatDate(
                            solarEclipse.localPeakTimeMs,
                            com.alijafari.red.astronomy.domain.CalendarSystem.GREGORIAN,
                            isFa = false
                        ),
                        timeOrDateStrFa = solarEclipse.formattedDateFa,
                        visibilityStatus = EventVisibilityStatus.OPTIMAL,
                        visibilityTextEn = solarEclipse.localVisibilityTextEn,
                        visibilityTextFa = solarEclipse.localVisibilityTextFa,
                        importanceScore = 1000,
                        targetObject = CanonicalAstroCatalog.getCanonicalObject("sun")?.let {
                            CanonicalAstroCatalog.toCelestialObject(it)
                        }
                    )
                )
            }
            val lunarHoursDiff = kotlin.math.abs(lunarEclipse.localPeakTimeMs - nowMs) / 3600000.0
            val lunarOngoing = nowMs in lunarEclipse.localStartTimeMs..lunarEclipse.localEndTimeMs
            if ((lunarHoursDiff <= 18.0 || lunarOngoing) && lunarEclipse.isLocallyVisible) {
                events.add(
                    TonightEvent(
                        id = lunarEclipse.event.id,
                        icon = "moon",
                        titleEn = lunarEclipse.localNameEn,
                        titleFa = lunarEclipse.localNameFa,
                        explanationEn = lunarEclipse.event.descriptionEn,
                        explanationFa = lunarEclipse.event.descriptionFa,
                        timeOrDateStrEn = TimeEngine.formatDate(
                            lunarEclipse.localPeakTimeMs,
                            com.alijafari.red.astronomy.domain.CalendarSystem.GREGORIAN,
                            isFa = false
                        ),
                        timeOrDateStrFa = lunarEclipse.formattedDateFa,
                        visibilityStatus = EventVisibilityStatus.OPTIMAL,
                        visibilityTextEn = lunarEclipse.localVisibilityTextEn,
                        visibilityTextFa = lunarEclipse.localVisibilityTextFa,
                        importanceScore = 980,
                        targetObject = CanonicalAstroCatalog.getCanonicalObject("moon")?.let {
                            CanonicalAstroCatalog.toCelestialObject(it)
                        }
                    )
                )
            }
        } catch (_: Exception) {}

        // 2. Active Peak Meteor Showers TONIGHT
        val showers = MeteorShowerCatalog.getMeteorShowers()
        for (shower in showers) {
            val (peakDoy, nameEn, nameFa) = when (shower.id) {
                "shower_perseids" -> Triple(225, "Perseids Meteor Shower Peak", "اوج بارش شهابی برساوشی")
                "shower_geminids" -> Triple(348, "Geminids Meteor Shower Peak", "اوج بارش شهابی دوپیکری")
                "shower_quadrantids" -> Triple(4, "Quadrantids Meteor Shower Peak", "اوج بارش شهابی ربعی")
                "shower_lyrids" -> Triple(113, "Lyrids Meteor Shower Peak", "اوج بارش شهابی شلیاقی")
                "shower_eta_aquariids" -> Triple(126, "Eta Aquariids Peak", "اوج بارش شهابی اتا دلو")
                "shower_orionids" -> Triple(294, "Orionids Meteor Shower Peak", "اوج بارش شهابی جباری")
                "shower_leonids" -> Triple(322, "Leonids Meteor Shower Peak", "اوج بارش شهابی اسدی")
                else -> Triple(-1, shower.nameEn, shower.nameFa)
            }
            if (peakDoy > 0) {
                val diff = Math.abs(dayOfYear - peakDoy)
                if (diff <= 1) { // Peaking tonight
                    events.add(
                        TonightEvent(
                            id = shower.id,
                            icon = "meteor",
                            titleEn = nameEn,
                            titleFa = nameFa,
                            explanationEn = "Peak activity tonight with high ZHR rate (~${shower.zhr} meteors/hr).",
                            explanationFa = "اوج فعالیت امشب با نرخ بارش تا ${shower.zhr} شهاب در ساعت.".toPersianDigits(),
                            timeOrDateStrEn = shower.activePeakDateWindowEn,
                            timeOrDateStrFa = shower.activePeakDateWindowFa,
                            visibilityStatus = EventVisibilityStatus.OPTIMAL,
                            visibilityTextEn = "Best seen after midnight away from city lights",
                            visibilityTextFa = "بهترین زمان رصد پس از نیمه‌شب دور از آلودگی نوری",
                            importanceScore = 900,
                            targetObject = CanonicalAstroCatalog.getCanonicalObject(shower.id)?.let {
                                CanonicalAstroCatalog.toCelestialObject(it)
                            } ?: shower
                        )
                    )
                }
            }
        }

        // 3. Bright ISS Passes Tonight
        try {
            val passes = ISSEngine.predictPasses(userLatDeg, userLonDeg, startTimestampMs = nowMs, scanDays = 1)
            val bestPass = passes.filter { it.maxElevationDeg >= 20.0 }.maxByOrNull { it.maxElevationDeg }
            if (bestPass != null) {
                val timeStr = TimeEngine.formatTime24h(bestPass.maxTimeMs, isFa)
                val elevInt = bestPass.maxElevationDeg.toInt()
                events.add(
                    TonightEvent(
                        id = "iss_pass_tonight",
                        icon = "satellite",
                        titleEn = "International Space Station (ISS) Pass",
                        titleFa = "گذر پرنور ایستگاه فضایی بین‌المللی (ISS)",
                        explanationEn = "Bright naked-eye pass reaching $elevInt° elevation above horizon.",
                        explanationFa = "گذر درخشان با چشم غیرمسلح با اوج ارتفاع $elevInt درجه بالای افق.".toPersianDigits(),
                        timeOrDateStrEn = "Peak Time: $timeStr",
                        timeOrDateStrFa = "زمان اوج گذر: $timeStr",
                        visibilityStatus = EventVisibilityStatus.OPTIMAL,
                        visibilityTextEn = "Mag ${String.format(java.util.Locale.US, "%.1f", bestPass.estimatedMagnitude)} — Highly Visible",
                        visibilityTextFa = "قدر ${String.format(java.util.Locale.US, "%.1f", bestPass.estimatedMagnitude)} — کاملاً شفاف".toPersianDigits(),
                        importanceScore = 850,
                        targetObject = CanonicalAstroCatalog.getCanonicalObject("sat_25544")?.let {
                            CanonicalAstroCatalog.toCelestialObject(it)
                        }
                    )
                )
            }
        } catch (_: Exception) {}

        // 4. Moon Phase & Nightly Elevation
        val moonData = MoonEngine.calculateMoon(jd, userLatDeg, userLonDeg)
        val moonTopo = CoordinateEngine.geocentricToTopocentric(
            geocentric = CoordinateEngine.Equatorial(moonData.raDeg, moonData.decDeg),
            geocentricDistanceKm = moonData.distanceKm,
            lastDeg = gmstDeg,
            latitudeDeg = userLatDeg
        )
        val moonHoriz = CoordinateEngine.equatorialToHorizontal(
            moonTopo,
            gmstDeg,
            userLatDeg
        )
        val moonIllumInt = moonData.illuminationPercent.toInt()
        val phaseNameEn = moonData.phaseNameEn
        val phaseNameFa = moonData.phaseNameFa

        if (moonHoriz.altitudeDeg > 0.0 || moonIllumInt > 85) {
            val moonScore = if (moonIllumInt >= 90) 800 else 700
            events.add(
                TonightEvent(
                    id = "moon_nightly_status",
                    icon = "moon",
                    titleEn = "Moon: $phaseNameEn ($moonIllumInt% Illuminated)",
                    titleFa = "وضعیت ماه: $phaseNameFa ($moonIllumInt٪ درخشندگی)".toPersianDigits(),
                    explanationEn = "Current distance: ${moonData.distanceKm.toInt()} km. Age: ${String.format(java.util.Locale.US, "%.1f", moonData.ageDays)} days.",
                    explanationFa = "فاصله تا زمین: ${moonData.distanceKm.toInt()} کیلومتر. سن ماه: ${String.format(java.util.Locale.US, "%.1f", moonData.ageDays)} روز.".toPersianDigits(),
                    timeOrDateStrEn = "Tonight's Sky",
                    timeOrDateStrFa = "آسمان امشب",
                    visibilityStatus = if (moonHoriz.altitudeDeg > 0) EventVisibilityStatus.OPTIMAL else EventVisibilityStatus.GOOD,
                    visibilityTextEn = if (moonHoriz.altitudeDeg > 0) "Visible now at ${moonHoriz.altitudeDeg.toInt()}° altitude" else "Rises later tonight",
                    visibilityTextFa = if (moonHoriz.altitudeDeg > 0) "هم‌اکنون در ارتفاع ${moonHoriz.altitudeDeg.toInt()} درجه".toPersianDigits() else "طلوع در ادامه امشب",
                    importanceScore = moonScore,
                    targetObject = CanonicalAstroCatalog.getCanonicalObject("moon")?.let {
                        CanonicalAstroCatalog.toCelestialObject(
                            canonicalObj = it,
                            dynamicRa = moonTopo.raDeg,
                            dynamicDec = moonTopo.decDeg
                        )
                    }
                )
            )
        }

        // 5. Bright Planets (Venus, Jupiter, Saturn, Mars) - DO NOT include Jupiter's moons!
        val sunPos = SunEngine.calculatePosition(jd)
        val sunHoriz = CoordinateEngine.equatorialToHorizontal(
            CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg),
            gmstDeg,
            userLatDeg
        )

        val planetList = listOf(
            Triple("planet_venus", PlanetEngine.PlanetType.VENUS, "Venus" to "ناهید (زهره)"),
            Triple("planet_jupiter", PlanetEngine.PlanetType.JUPITER, "Jupiter" to "مشتری (برجیس)"),
            Triple("planet_saturn", PlanetEngine.PlanetType.SATURN, "Saturn" to "کیوان (زحل)"),
            Triple("planet_mars", PlanetEngine.PlanetType.MARS, "Mars" to "مریخ (بهرام)")
        )

        for ((id, type, names) in planetList) {
            val pos = PlanetEngine.calculatePlanet(type, jd)
            val horiz = CoordinateEngine.equatorialToHorizontal(
                CoordinateEngine.Equatorial(pos.raDeg, pos.decDeg),
                gmstDeg,
                userLatDeg
            )
            val obs = ObservabilityEngine.calculateObservability(
                altitudeDeg = horiz.altitudeDeg,
                sunAltitudeDeg = sunHoriz.altitudeDeg,
                moonIlluminationPercent = moonData.illuminationPercent,
                objectMagnitude = pos.magnitude,
                objectType = ObjectType.PLANET,
                objectId = id
            )

            if (horiz.altitudeDeg > 5.0 || obs.scorePercent > 40) {
                val pScore = 750 + (pos.magnitude * -50).toInt() + (horiz.altitudeDeg * 2).toInt()
                events.add(
                    TonightEvent(
                        id = "event_$id",
                        icon = if (id == "planet_saturn") "planet" else "star",
                        titleEn = "Planet ${names.first} Visible",
                        titleFa = "سیاره ${names.second} در آسمان امشب",
                        explanationEn = "Shining brightly at magnitude ${String.format(java.util.Locale.US, "%.1f", pos.magnitude)} in ${horiz.azimuthCompassNameEn}.",
                        explanationFa = "درخشش با قدر ${String.format(java.util.Locale.US, "%.1f", pos.magnitude)} در سمت ${horiz.azimuthCompassNameFa}.".toPersianDigits(),
                        timeOrDateStrEn = "Evening / Night Sky",
                        timeOrDateStrFa = "آسمان شبانگاهی",
                        visibilityStatus = if (horiz.altitudeDeg > 15.0) EventVisibilityStatus.OPTIMAL else EventVisibilityStatus.GOOD,
                        visibilityTextEn = "Altitude: ${horiz.altitudeDeg.toInt()}° — ${obs.level.nameEn}",
                        visibilityTextFa = "ارتفاع: ${horiz.altitudeDeg.toInt()} درجه — ${obs.level.nameFa}".toPersianDigits(),
                        importanceScore = pScore,
                        targetObject = CanonicalAstroCatalog.getCanonicalObject(id)?.let {
                            CanonicalAstroCatalog.toCelestialObject(
                                canonicalObj = it,
                                dynamicRa = pos.raDeg,
                                dynamicDec = pos.decDeg,
                                dynamicMag = pos.magnitude
                            )
                        }
                    )
                )
            }
        }

        // 6. Planetary & Lunar Conjunction Check
        for ((id, type, names) in planetList) {
            val pos = PlanetEngine.calculatePlanet(type, jd)
            val sepDeg = CoordinateEngine.calculateAngularSeparationDeg(
                moonTopo.raDeg, moonTopo.decDeg,
                pos.raDeg, pos.decDeg
            )
            if (sepDeg < 5.0) {
                val sepStr = String.format(java.util.Locale.US, "%.1f", sepDeg)
                events.add(
                    TonightEvent(
                        id = "conj_moon_$id",
                        icon = "celestial",
                        titleEn = "Close Conjunction: Moon & ${names.first}",
                        titleFa = "مقارنه نزدیک ماه و ${names.second}",
                        explanationEn = "The Moon and ${names.first} appear just $sepStr° apart in tonight's sky.",
                        explanationFa = "ماه و ${names.second} تنها با فاصله $sepStr درجه در کنار یکدیگر می‌درخشند.".toPersianDigits(),
                        timeOrDateStrEn = "Tonight",
                        timeOrDateStrFa = "امشب",
                        visibilityStatus = EventVisibilityStatus.OPTIMAL,
                        visibilityTextEn = "Separation: $sepStr° — Beautiful Naked Eye View",
                        visibilityTextFa = "فاصله زاویه‌ای: $sepStr درجه — رصد جذاب با چشم غیرمسلح".toPersianDigits(),
                        importanceScore = 920,
                        targetObject = CanonicalAstroCatalog.getCanonicalObject(id)?.let {
                            CanonicalAstroCatalog.toCelestialObject(
                                canonicalObj = it,
                                dynamicRa = pos.raDeg,
                                dynamicDec = pos.decDeg,
                                dynamicMag = pos.magnitude
                            )
                        }
                    )
                )
            }
        }

        return events.sortedByDescending { it.importanceScore }
    }
}
