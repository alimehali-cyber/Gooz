package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.CoordinateEngineLegacy
import com.alijafari.red.astronomy.astro_engine.MoonEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Moon meridian transit, world-wide.
 *
 * `ObjectDetailModal` used to derive the Moon's transit from the analytic rise/set/transit
 * solver, which samples the body's RA once and closes the hour angle at the sidereal rate.
 * For the Moon that is wrong twice over (see `CoordinateEngineLegacy.calculateMoonTransitTimeMs`)
 * and the displayed time drifted with the moment the panel was opened: measured in Tehran on
 * 2026-10-15 the error grew as err[min] = 2.19 x (hours since local midnight - 15.54), i.e.
 * -35.5 min when opened at 00:30 and +14.8 min when opened at 23:30, against a true transit of
 * 15:32:35 +03:30. These tests pin the fixed-point solver to an independent reference and pin
 * the invariance that the fix buys: the answer must not depend on when it is asked.
 *
 * REFERENCE. PyEphem 4.2.1 `Observer.next_transit(Moon)` -- the Moon's apparent *topocentric*
 * meridian crossing, which is the same definition the engine solves (HA = LAST - RA = 0 with
 * the topocentric RA). The instants below are that oracle converted to UTC milliseconds. The
 * engine's own RA comes from ELP2000 / Meeus 47.a rather than PyEphem's theory, so a small
 * residual is expected and allowed: 2 seconds of time is 0.008 deg of hour angle, about
 * two orders of magnitude tighter than the 1-minute criterion this change is judged by, and
 * still far above the arcsecond-level disagreement between the two lunar theories.
 */
class MoonTransitGlobalTest {

    private data class MoonTransitCity(
        val name: String,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val elevationM: Double,
        val timezoneId: String,
        val oracleTransitUtcMs: Long
    )

    private companion object {
        /** 2026-10-15, six cities, sun-side and anti-sun-side, both hemispheres. */
        val CITIES_20261015 = listOf(
            MoonTransitCity("Tehran", 35.6892, 51.3890, 1200.0, "Asia/Tehran", 1792065755210L),
            MoonTransitCity("New York", 40.7128, -74.0060, 10.0, "America/New_York", 1792096928356L),
            MoonTransitCity("London", 51.5074, -0.1278, 11.0, "Europe/London", 1792078562209L),
            MoonTransitCity("Sydney", -33.8688, 151.2093, 58.0, "Australia/Sydney", 1792040941076L),
            MoonTransitCity("Tokyo", 35.6762, 139.6503, 40.0, "Asia/Tokyo", 1792043814402L),
            MoonTransitCity("Sao Paulo", -23.5505, -46.6333, 760.0, "America/Sao_Paulo", 1792090123505L)
        )

        /** 2027-02-11: a different lunar phase, and the northern winter DST rules. */
        val CITIES_20270211 = listOf(
            MoonTransitCity("Tehran", 35.6892, 51.3890, 1200.0, "Asia/Tehran", 1802347648338L),
            MoonTransitCity("New York", 40.7128, -74.0060, 10.0, "America/New_York", 1802378691412L),
            MoonTransitCity("London", 51.5074, -0.1278, 11.0, "Europe/London", 1802360399191L),
            MoonTransitCity("Sydney", -33.8688, 151.2093, 58.0, "Australia/Sydney", 1802322952034L),
            MoonTransitCity("Tokyo", 35.6762, 139.6503, 40.0, "Asia/Tokyo", 1802325811176L),
            MoonTransitCity("Sao Paulo", -23.5505, -46.6333, 760.0, "America/Sao_Paulo", 1802371912982L)
        )

        const val REFERENCE_TOLERANCE_MS = 2000L

        fun localMidnightMs(year: Int, month: Int, day: Int, timezoneId: String): Long {
            val tz = TimeZone.getTimeZone(timezoneId)
            return Calendar.getInstance(tz).apply {
                set(year, month, day, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        fun transitFromEngine(nowMs: Long, city: MoonTransitCity): Long =
            CoordinateEngineLegacy.calculateMoonTransitTimeMs(
                nowMs = nowMs,
                longitudeDeg = city.longitudeDeg,
                timezoneId = city.timezoneId
            ) { jd ->
                MoonEngine.topocentricRaDeg(
                    jd = jd,
                    latitude = city.latitudeDeg,
                    longitude = city.longitudeDeg,
                    elevationM = city.elevationM
                )
            }

        fun localTime(ms: Long, timezoneId: String): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone(timezoneId)
            }.format(Date(ms))
    }

    @Test
    fun moonTransitMatchesTheTopocentricReferenceInSixCitiesWorldwide() {
        val dates = listOf(
            Triple(2026, Calendar.OCTOBER, 15) to CITIES_20261015,
            Triple(2027, Calendar.FEBRUARY, 11) to CITIES_20270211
        )
        for ((date, cities) in dates) {
            val (year, month, day) = date
            for (city in cities) {
                val midnightMs = localMidnightMs(year, month, day, city.timezoneId)
                val engineMs = transitFromEngine(midnightMs, city)
                val deltaMs = engineMs - city.oracleTransitUtcMs
                println(
                    "${city.name} ${year}-${month + 1}-$day: engine " +
                        "${localTime(engineMs, city.timezoneId)} local, reference " +
                        "${localTime(city.oracleTransitUtcMs, city.timezoneId)} local, " +
                        "delta ${deltaMs} ms"
                )
                assertEquals(
                    "${city.name} ${year}-${month + 1}-$day: moon transit must match the " +
                        "topocentric reference (PyEphem next_transit) within " +
                        "${REFERENCE_TOLERANCE_MS} ms; engine gave " +
                        "${localTime(engineMs, city.timezoneId)} local " +
                        "(${deltaMs / 1000.0} s off)",
                    city.oracleTransitUtcMs.toDouble(),
                    engineMs.toDouble(),
                    REFERENCE_TOLERANCE_MS.toDouble()
                )
            }
        }
    }

    @Test
    fun moonTransitDoesNotDependOnWhenThePanelIsOpened() {
        val tehran = CITIES_20261015.first { it.name == "Tehran" }
        val tz = TimeZone.getTimeZone(tehran.timezoneId)
        // The five moments from the bug report: the panel is a live screen, so the same
        // question gets asked at any hour. All five must give the same transit.
        val openedAt = listOf(0 to 30, 6 to 0, 12 to 0, 15 to 0, 23 to 30).map { (hour, minute) ->
            Calendar.getInstance(tz).apply {
                set(2026, Calendar.OCTOBER, 15, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        val answers = openedAt.map { transitFromEngine(it, tehran) }
        answers.forEachIndexed { index, answerMs ->
            val (hour, minute) = listOf(0 to 30, 6 to 0, 12 to 0, 15 to 0, 23 to 30)[index]
            println(
                "opened %02d:%02d -> %s local (delta %d ms from the reference)"
                    .format(hour, minute, localTime(answerMs, tehran.timezoneId),
                        answerMs - tehran.oracleTransitUtcMs)
            )
            assertEquals(
                "Panel opened at %02d:%02d must still show the 15:32:35 transit, not a "
                    .format(hour, minute) +
                    "time that drifted with the clock; engine gave " +
                    "${localTime(answerMs, tehran.timezoneId)} (${(answerMs - tehran.oracleTransitUtcMs) / 1000.0} s off)",
                tehran.oracleTransitUtcMs.toDouble(),
                answerMs.toDouble(),
                REFERENCE_TOLERANCE_MS.toDouble()
            )
        }
        assertEquals(
            "Every open time must yield the identical instant, not merely a close one",
            1,
            answers.distinct().size
        )
    }
}
