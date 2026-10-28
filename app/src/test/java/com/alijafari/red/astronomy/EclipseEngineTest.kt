package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.EclipseEngine
import com.alijafari.red.astronomy.domain.CalendarSystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EclipseEngineTest {

    private lateinit var engine: EclipseEngine

    @Before
    fun setUp() {
        engine = EclipseEngine()
    }

    @Test
    fun testNextSolarEclipseIsFound() {
        val afterMs = 1704067200000L // Jan 1, 2024
        val eclipse = engine.findNextSolarEclipse(afterMs)
        assertNotNull("Solar eclipse should be found", eclipse)
        assertTrue("Eclipse maximum should be after start time", eclipse!!.maximumMs > afterMs)
    }

    @Test
    fun testNextLunarEclipseIsFound() {
        val afterMs = 1704067200000L // Jan 1, 2024
        val eclipse = engine.findNextLunarEclipse(afterMs)
        assertNotNull("Lunar eclipse should be found", eclipse)
        assertTrue("Eclipse maximum should be after start time", eclipse!!.maximumMs > afterMs)
    }

    @Test
    fun testEclipseMagnitudeIsPositive() {
        val afterMs = 1704067200000L // Jan 1, 2024
        val solar = engine.findNextSolarEclipse(afterMs)!!
        val lunar = engine.findNextLunarEclipse(afterMs)!!

        assertTrue("Solar magnitude should be positive", solar.magnitude > 0.0)
        assertTrue("Lunar magnitude should be positive", lunar.magnitude > 0.0)
    }

    @Test
    fun testEclipseTypeIsValid() {
        val afterMs = 1704067200000L // Jan 1, 2024
        val solar = engine.findNextSolarEclipse(afterMs)!!
        val lunar = engine.findNextLunarEclipse(afterMs)!!

        val validSolar = setOf(
            EclipseEngine.EclipseType.PARTIAL_SOLAR,
            EclipseEngine.EclipseType.ANNULAR_SOLAR,
            EclipseEngine.EclipseType.TOTAL_SOLAR,
            EclipseEngine.EclipseType.HYBRID_SOLAR,
            EclipseEngine.EclipseType.SOLAR_PARTIAL,
            EclipseEngine.EclipseType.SOLAR_ANNULAR,
            EclipseEngine.EclipseType.SOLAR_TOTAL
        )
        val validLunar = setOf(
            EclipseEngine.EclipseType.PENUMBRAL_LUNAR,
            EclipseEngine.EclipseType.PARTIAL_LUNAR,
            EclipseEngine.EclipseType.TOTAL_LUNAR,
            EclipseEngine.EclipseType.LUNAR_PENUMBRAL,
            EclipseEngine.EclipseType.LUNAR_PARTIAL,
            EclipseEngine.EclipseType.LUNAR_TOTAL
        )

        assertTrue("Solar eclipse type should be valid solar", solar.type in validSolar)
        assertTrue("Lunar eclipse type should be valid lunar", lunar.type in validLunar)
    }

    @Test
    fun testFindEclipsesInRange() {
        val startMs = 1704067200000L // Jan 1, 2024
        val endMs = 1735689600000L   // Jan 1, 2025

        val eclipses = engine.findEclipses(startMs, endMs)
        assertEquals("Should find all 4 canonical eclipses in 2024", 4, eclipses.size)

        for (e in eclipses) {
            assertTrue("Eclipse time should be in range", e.maximumMs in startMs..endMs)
        }
    }

    @Test
    fun testNewMoonAndFullMoonTimes() {
        val afterMs = 1704067200000L // Jan 1, 2024
        val newMoon = engine.findNextNewMoon(afterMs)
        val fullMoon = engine.findNextFullMoon(afterMs)

        assertTrue("New moon should be after Jan 2024", newMoon > afterMs)
        assertTrue("Full moon should be after Jan 2024", fullMoon > afterMs)

        // New moon and full moon should be roughly half a synodic month apart
        val diffDays = Math.abs(fullMoon - newMoon) / 86400000.0
        assertTrue(
            "New and full moon should be ~14.7 days apart, got $diffDays",
            diffDays in 13.0..16.0
        )
    }

    @Test
    fun testCanonicalTimestampsMatchNasaUtcAndVisibilityInIran() {
        // 2027-08-02 Total Solar Eclipse (10:07 UTC = 1817201220000L)
        val solar2027 = engine.findNextSolarEclipse(1810000000000L)!!
        assertEquals(1817201220000L, solar2027.maximumMs)

        // In Luxor, Egypt (25.6872N, 32.6396E), 2027-08-02 is a Total Solar Eclipse (100% obscuration)
        val luxorEval = engine.evaluateEclipse(solar2027, 25.6872, 32.6396, 80.0, 1810000000000L)
        assertTrue("Luxor must see 2027-08-02 eclipse", luxorEval.isLocallyVisible)
        assertEquals(100, luxorEval.localObscurationPercent)

        // In Nurabad, Iran (30.1141N, 51.5217E), 2027-08-02 is a deep Partial Solar Eclipse (~58% linear mag, ~49% area obscuration)
        val nurabadEval = engine.evaluateEclipse(solar2027, 30.1141, 51.5217, 940.0, 1810000000000L)
        assertTrue("Nurabad must see 2027-08-02 partial eclipse", nurabadEval.isLocallyVisible)
        assertTrue("Nurabad area obscuration should be between 45% and 60%", nurabadEval.localObscurationPercent in 45..60)
        assertTrue("Nurabad linear magnitude should be between 0.50 and 0.65", nurabadEval.localMagnitude in 0.50..0.65)

        // 2028-12-31 Total Lunar Eclipse (16:52 UTC = 1861894320000L) is completely visible across Iran
        val lunar2028 = engine.findNextLunarEclipse(1860000000000L)!!
        assertEquals(1861894320000L, lunar2028.maximumMs)
        val iranLunar2028 = engine.evaluateEclipse(lunar2028, 30.1141, 51.5217, 940.0, 1860000000000L)
        assertTrue("2028-12-31 Total Lunar Eclipse must be visible in Iran", iranLunar2028.isLocallyVisible)
        assertEquals(100, iranLunar2028.localObscurationPercent)
    }

    @Test
    fun testDynamicMeeusSearchPre2024AndPost2030() {
        // Pre-2024 range (2020)
        val start2020 = 1577836800000L // 2020-01-01
        val end2020 = 1609459200000L   // 2021-01-01
        val eclipses2020 = engine.findEclipses(start2020, end2020)
        assertTrue("Should find at least 4 eclipses in 2020 via Meeus Ch. 54", eclipses2020.size >= 4)

        // Post-2030 range (2031-2033)
        val start2031 = 1924992000000L // 2031-01-01
        val end2033 = 2019686400000L   // 2034-01-01
        val eclipsesPost2030 = engine.findEclipses(start2031, end2033)
        assertTrue("Should find eclipses in 2031-2033", eclipsesPost2030.size >= 6)
        assertTrue(
            "Dynamic search must be able to classify Total eclipses",
            eclipsesPost2030.any {
                it.type == EclipseEngine.EclipseType.TOTAL_SOLAR ||
                    it.type == EclipseEngine.EclipseType.TOTAL_LUNAR
            }
        )
    }

    // ---------------------------------------------------------------------
    // Eclipse canon: values verified against NASA GSFC (Fred Espenak)
    //   Five Millennium Catalog of Solar Eclipses: 2001 to 2100
    //   Five Millennium Catalog of Lunar Eclipses: 2001 to 2100
    // ---------------------------------------------------------------------

    /** Whole canonical table: 2024-01-01 through 2040-12-12. */
    private fun allCanonical(): List<EclipseEngine.EclipseEvent> =
        engine.findEclipses(1704067200000L, 2238912000000L)

    @Test
    fun testCanonicalTableCovers2024Through2040WithoutGaps() {
        val all = allCanonical()
        // 2024 through 2040 inclusive: no gap may force a fallback to the approximate
        // Meeus dynamic search inside the covered window.
        assertTrue("Table must extend to the last catalogued eclipse (2040-11-18)", all.size >= 77)
        for (year in 2024..2039) {
            val startMs = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(year, java.util.Calendar.JANUARY, 1)
            }.timeInMillis
            val endMs = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear(); set(year + 1, java.util.Calendar.JANUARY, 1)
            }.timeInMillis - 1
            assertTrue("Year $year must have at least 2 eclipses", all.count { it.maximumMs in startMs..endMs } >= 2)
        }
    }

    @Test
    fun testMissingPenumbralLunarEclipseOf2027July18IsPresent() {
        // NASA 5MCLE catalogue number 09711 — the smallest penumbral lunar eclipse of
        // the 21st century. It was absent from the table entirely.
        val found = engine.findEclipses(1815000000000L, 1816500000000L)
            .firstOrNull { it.type == EclipseEngine.EclipseType.PENUMBRAL_LUNAR }
        assertNotNull("2027-07-18 penumbral lunar eclipse must be in the table", found)
        assertEquals("saros", 110, found!!.saros)
        assertEquals("gamma", -1.5758, found.gamma, 1e-4)
        // magnitude of a PENUMBRAL lunar eclipse is the penumbral magnitude
        assertEquals("penumbral magnitude", 0.0014, found.magnitude, 1e-4)
    }

    @Test
    fun testCorrectedMagnitudesAndGammasMatchNasaCatalogue() {
        // Each row below was wrong in the table and is now corrected against the
        // NASA GSFC five-millennium catalogues.
        val byMs = allCanonical().associateBy { it.maximumMs }
        fun check(ms: Long, saros: Int, gamma: Double, magnitude: Double) {
            val e = byMs[ms] ?: error("no eclipse at $ms")
            assertEquals("saros @$ms", saros, e.saros)
            assertEquals("gamma @$ms", gamma, e.gamma, 1e-4)
            assertEquals("magnitude @$ms", magnitude, e.magnitude, 1e-4)
        }
        // Penumbral magnitudes (previously 0.9520 and 0.9347) and gamma values
        check(1803165180000L, 143, -1.0480, 0.9266) // 2027-02-20 penumbral lunar
        check(1923085680000L, 145, -1.0731, 0.9416) // 2030-12-09 penumbral lunar
        check(1846520400000L, 120, -0.7903, 0.3892) // 2028-07-06 partial lunar (gamma was -0.7936)
        // Solar values
        check(1771330380000L, 121, -0.9743, 0.9630) // 2026-02-17 annular (gamma was -0.9738)
        check(1743245280000L, 149, 1.0405, 0.9376)  // 2025-03-29 partial (magnitude was 0.9358)
        check(1847847360000L, 146, -0.6056, 1.0560) // 2028-07-22 total (magnitude was 1.0556)
    }

    @Test
    fun testSolarEntriesCarryNoFabricatedLunarContacts() {
        // A solar eclipse has no penumbral/umbral phases. The data class used to default
        // these to maximumMs +/- 2h/1h/15m, which invented contact times for every
        // solar entry; they must now collapse onto greatest eclipse.
        val solar = allCanonical().filter { it.isSolar }
        assertTrue("table must contain solar eclipses", solar.isNotEmpty())
        for (e in solar) {
            assertEquals("P1 @${e.maximumMs}", e.maximumMs, e.penumbralStartMs)
            assertEquals("U1 @${e.maximumMs}", e.maximumMs, e.umbralStartMs)
            assertEquals("U2 @${e.maximumMs}", e.maximumMs, e.totalityStartMs)
            assertEquals("U3 @${e.maximumMs}", e.maximumMs, e.totalityEndMs)
            assertEquals("U4 @${e.maximumMs}", e.maximumMs, e.umbralEndMs)
            assertEquals("P4 @${e.maximumMs}", e.maximumMs, e.penumbralEndMs)
        }
    }

    @Test
    fun testLunarContactsAreChronologicallyOrdered() {
        val lunar = allCanonical().filter { !it.isSolar }
        assertTrue("table must contain lunar eclipses", lunar.isNotEmpty())
        for (e in lunar) {
            val contacts = listOf(
                e.penumbralStartMs, e.umbralStartMs, e.totalityStartMs,
                e.maximumMs, e.totalityEndMs, e.umbralEndMs, e.penumbralEndMs
            )
            for (i in 1 until contacts.size) {
                assertTrue(
                    "lunar contacts out of order at ${e.maximumMs}",
                    contacts[i] >= contacts[i - 1]
                )
            }
            assertTrue("penumbral phase must be non-degenerate at ${e.maximumMs}",
                e.penumbralEndMs > e.penumbralStartMs)
        }
    }

    @Test
    fun testLunarPenumbralSpansMatchNasaDurations() {
        // Penumbral durations (P1 -> P4) in minutes, straight from the 5MCLE catalogue.
        val expectedPenMinutes = mapOf(
            1711350780000L to 279.1, // 2024-03-25
            1726627440000L to 246.3, // 2024-09-18
            1741935540000L to 362.6, // 2025-03-14
            1757268720000L to 326.7, // 2025-09-07
            1772537640000L to 338.6, // 2026-03-03
            1803165180000L to 241.0, // 2027-02-20
            1907778780000L to 278.2, // 2030-06-15
            1923085680000L to 279.2  // 2030-12-09
        )
        val byMs = allCanonical().associateBy { it.maximumMs }
        for ((ms, minutes) in expectedPenMinutes) {
            val e = byMs[ms] ?: continue
            val actualMinutes = (e.penumbralEndMs - e.penumbralStartMs) / 60000.0
            assertEquals("P1->P4 minutes @$ms", minutes, actualMinutes, 0.6)
        }
    }

    // ---------------------------------------------------------------------
    // "Next 3 eclipses of each kind"
    // ---------------------------------------------------------------------

    @Test
    fun testNextThreeSolarAndThreeLunarEclipsesAreReturned() {
        val nowMs = 1_784_000_000_000L // 2026-07-14
        val (solar, lunar) = engine.getNextEclipseSeries(
            nowMs = nowMs,
            userLatDeg = 35.6892,
            userLonDeg = 51.3890,
            elevationM = 1200.0,
            timezoneId = "Asia/Tehran",
            calendarSystem = CalendarSystem.SOLAR_HIJRI,
            count = 3
        )

        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val dates = { list: List<EclipseEngine.EclipseResult> ->
            list.map { fmt.format(java.util.Date(it.event.maximumMs)) }
        }

        // These are the next three *observable* eclipses from Tehran, not the next
        // three in time. The events that used to occupy these slots are gone for
        // documented astronomical reasons:
        //
        //   2026-08-12 solar — greatest eclipse 17:47Z, i.e. 21:17 in Tehran
        //                      (UTC+3:30). Tehran sunset in mid-August is about
        //                      19:30, so the Sun is below the horizon throughout.
        //   2027-02-06 solar — greatest eclipse 16:00Z = 19:30 in Tehran; sunset
        //                      in mid-February is about 18:00. Below the horizon.
        //   2027-07-18 lunar — a penumbral eclipse whose penumbral magnitude is
        //                      0.0014. Espenak's threshold for detecting one at
        //                      all is 0.6, so there is nothing to see.
        //
        // What remains:
        //   2027-08-02 solar — greatest eclipse 10:07Z, mid-morning in Tehran,
        //                      ~35% obscuration with the Sun 62° up.
        //   2030-06-01 solar — ~53% obscuration, Sun 45° up.
        //   2031-05-21 solar — a shallow ~1% partial, which is the floor of what
        //                      counts as observable through a solar filter.
        //   2026-08-28 lunar — greatest eclipse 04:13Z = 07:43 in Tehran, but the
        //                      full Moon has already set (moonset ~06:15, about 45
        //                      minutes after a ~05:57 sunrise). Below the horizon.
        //                      (Luxor does see it: 04:13Z = 06:13 there, right at
        //                      moonset, and it is Luxor's next lunar eclipse.)
        //
        // What remains for Tehran:
        //   2027-02-20 lunar — penumbral magnitude 0.927, above the 0.6 threshold,
        //                      Moon 66° up at maximum.
        //   2028-07-06 lunar — partial, umbral magnitude 0.389, Moon 18° up.
        //   2028-12-31 lunar — total, Moon 22° up.
        assertEquals("3 solar eclipses", listOf("2027-08-02", "2030-06-01", "2031-05-21"), dates(solar))
        assertEquals("3 lunar eclipses", listOf("2027-02-20", "2028-07-06", "2028-12-31"), dates(lunar))

        assertTrue("every solar entry must be solar", solar.all { it.event.isSolar })
        assertTrue("every lunar entry must be lunar", lunar.all { !it.event.isSolar })
        assertEquals("no duplicate solar events", 3, solar.distinctBy { it.event.maximumMs }.size)
        assertEquals("no duplicate lunar events", 3, lunar.distinctBy { it.event.maximumMs }.size)
        for (i in 1 until solar.size) {
            assertTrue("solar series must be chronological",
                solar[i].event.maximumMs > solar[i - 1].event.maximumMs)
        }
        for (i in 1 until lunar.size) {
            assertTrue("lunar series must be chronological",
                lunar[i].event.maximumMs > lunar[i - 1].event.maximumMs)
        }
        assertTrue("every result must be in the future",
            (solar + lunar).all { it.event.maximumMs > nowMs })
    }

    @Test
    fun testEclipseSeriesIsEvaluatedForTheObserversLocation() {
        // Lunar eclipses are visible from anywhere on the night side, so both cities
        // see the same EVENTS — but the local circumstances (whether the Moon is up,
        // and how high) must differ. That is what proves the series is location aware.
        //
        // Note: the same is deliberately NOT asserted for the solar series. Since the
        // series is filtered to genuinely observable events (see EclipseVisibility),
        // two cities can legitimately receive different solar eclipses: a 0.4%
        // partial is skipped for one observer and a 60% partial is offered to another.
        val nowMs = 1_784_000_000_000L
        val tehran = engine.getNextEclipseSeries(nowMs, 35.6892, 51.3890, 1200.0, "Asia/Tehran", CalendarSystem.SOLAR_HIJRI, 3)
        val sydney = engine.getNextEclipseSeries(nowMs, -33.8688, 151.2093, 20.0, "Australia/Sydney", CalendarSystem.SOLAR_HIJRI, 3)
        val differing = tehran.second.indices.count { i ->
            val s = sydney.second.getOrNull(i)
            s == null ||
                tehran.second[i].isLocallyVisible != s.isLocallyVisible ||
                kotlin.math.abs(tehran.second[i].targetAltitudeDeg - s.targetAltitudeDeg) > 1.0
        }
        assertTrue("lunar circumstances must differ between Tehran and Sydney ($differing)", differing >= 1)
    }

    @Test
    fun testGetNextEclipsesIsTheFirstOfTheSeries() {
        val nowMs = 1_784_000_000_000L
        val (singleSolar, singleLunar) = engine.getNextEclipses(
            nowMs, 35.6892, 51.3890, 1200.0, "Asia/Tehran", CalendarSystem.SOLAR_HIJRI
        )
        val (seriesSolar, seriesLunar) = engine.getNextEclipseSeries(
            nowMs, 35.6892, 51.3890, 1200.0, "Asia/Tehran", CalendarSystem.SOLAR_HIJRI, 3
        )
        assertEquals("single solar must equal first of solar series",
            seriesSolar.first().event.maximumMs, singleSolar.event.maximumMs)
        assertEquals("single lunar must equal first of lunar series",
            seriesLunar.first().event.maximumMs, singleLunar.event.maximumMs)
    }

    @Test
    fun testOngoingEclipseNotDroppedImmediatelyAfterPeak() {
        // 10 minutes after peak of 2025-09-07 Total Lunar Eclipse (18:22 UTC, while totality is still active until 18:52 UTC)
        val duringTotalityMs = 1757268720000L + 10 * 60 * 1000L
        val (_, lunarRes) = engine.getNextEclipses(
            nowMs = duringTotalityMs,
            userLatDeg = 35.6892,
            userLonDeg = 51.3890,
            elevationM = 1200.0,
            timezoneId = "Asia/Tehran",
            calendarSystem = CalendarSystem.SOLAR_HIJRI
        )
        assertEquals(
            "Ongoing lunar eclipse must remain active until P4 end contact",
            1757268720000L,
            lunarRes.event.maximumMs
        )
    }
}
