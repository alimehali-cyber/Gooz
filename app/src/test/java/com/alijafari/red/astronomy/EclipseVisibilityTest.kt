package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.EclipseEngine
import com.alijafari.red.astronomy.astro_engine.EclipseVisibility
import com.alijafari.red.astronomy.domain.CalendarSystem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the eclipse *visibility* criteria and the richer per-eclipse
 * information (colour prediction, traditional name, significance).
 *
 * Every threshold asserted here is either quoted from a published source or
 * explicitly marked as engineering judgement in [EclipseVisibility]. Expected
 * catalogue values are taken from NASA GSFC's decade tables, not from the
 * production code:
 *  - Lunar:  https://eclipse.gsfc.nasa.gov/LEdecade/LEdecade2021.html
 *            https://eclipse.gsfc.nasa.gov/LEdecade/LEdecade2031.html
 *  - Solar:  https://eclipse.gsfc.nasa.gov/SEdecade/SEdecade2021.html
 */
class EclipseVisibilityTest {

    private lateinit var engine: EclipseEngine

    @Before
    fun setUp() {
        engine = EclipseEngine()
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private val tehranLat = 35.6892
    private val tehranLon = 51.3890
    private val tehranElev = 1200.0
    private val tehranTz = "Asia/Tehran"

    /** 2026-07-14T00:00Z — comfortably between catalogue entries. */
    private val nowMs = 1_784_000_000_000L

    private fun lunarEvent(
        type: EclipseEngine.EclipseType,
        magnitude: Double,
        maximumMs: Long = 1_800_000_000_000L,
        durationTotalSeconds: Int = 0,
        gamma: Double = 0.0
    ) = EclipseEngine.EclipseEvent(
        type = type,
        maximumMs = maximumMs,
        magnitude = magnitude,
        saros = 1,
        gamma = gamma,
        nameEn = "Test Lunar Eclipse",
        nameFa = "خسوف آزمایشی",
        durationTotalSeconds = durationTotalSeconds,
        maxTotalityRegionEn = "",
        maxTotalityRegionFa = "",
        descriptionEn = "",
        descriptionFa = "",
        isSolar = false
    )

    private fun solarEvent(
        type: EclipseEngine.EclipseType,
        magnitude: Double,
        maximumMs: Long = 1_800_000_000_000L,
        durationTotalSeconds: Int = 0
    ) = EclipseEngine.EclipseEvent(
        type = type,
        maximumMs = maximumMs,
        magnitude = magnitude,
        saros = 1,
        gamma = 0.0,
        nameEn = "Test Solar Eclipse",
        nameFa = "خورشیدگرفتگی آزمایشی",
        durationTotalSeconds = durationTotalSeconds,
        maxTotalityRegionEn = "",
        maxTotalityRegionFa = "",
        descriptionEn = "",
        descriptionFa = "",
        isSolar = true
    )

    private fun assessLunar(
        event: EclipseEngine.EclipseEvent,
        localMagnitude: Double,
        isLocallyVisible: Boolean = true,
        altitudeDeg: Double = 45.0
    ) = EclipseVisibility.assessLunar(
        event = event,
        isLocallyVisible = isLocallyVisible,
        localStartTimeMs = event.maximumMs - 3_600_000L,
        localEndTimeMs = event.maximumMs + 3_600_000L,
        localMagnitude = localMagnitude,
        targetAltitudeDeg = altitudeDeg
    )

    private fun assessSolar(
        event: EclipseEngine.EclipseEvent,
        obscurationPercent: Int,
        isLocallyVisible: Boolean = true,
        altitudeDeg: Double = 45.0
    ) = EclipseVisibility.assessSolar(
        event = event,
        isLocallyVisible = isLocallyVisible,
        localStartTimeMs = event.maximumMs - 3_600_000L,
        localEndTimeMs = event.maximumMs + 3_600_000L,
        localObscurationPercent = obscurationPercent,
        targetAltitudeDeg = altitudeDeg
    )

    // ------------------------------------------------------------------
    // 1. Lunar: the penumbral detection threshold (published value)
    // ------------------------------------------------------------------

    @Test
    fun testPenumbralLunarEclipseBelowNasaThresholdIsNotObservable() {
        // Espenak & Meeus, Five Millennium Canon / EclipseWise: penumbral eclipses
        // "cannot be detected visually - with or without optical aid - unless the
        // eclipse magnitude is greater than ~0.6".
        val barely = assessLunar(
            lunarEvent(EclipseEngine.EclipseType.PENUMBRAL_LUNAR, 0.9266),
            localMagnitude = 0.599
        )
        assertFalse("0.599 is below the 0.6 detection threshold", barely.isObservable)
        assertEquals(EclipseVisibility.Grade.NOT_OBSERVABLE, barely.grade)

        val atThreshold = assessLunar(
            lunarEvent(EclipseEngine.EclipseType.PENUMBRAL_LUNAR, 0.9266),
            localMagnitude = 0.60
        )
        assertTrue("0.60 must meet the threshold", atThreshold.isObservable)
    }

    @Test
    fun testRealPenumbralEclipseBelowThresholdIsReported() {
        // 2027-07-18: NASA lists umbral magnitude -1.068, i.e. a penumbral
        // eclipse with penumbral magnitude 0.0014. There is nothing to see.
        val event = lunarEvent(
            EclipseEngine.EclipseType.PENUMBRAL_LUNAR,
            magnitude = 0.0014,
            maximumMs = 1_815_926_573_000L,
            gamma = -1.5758
        )
        val a = assessLunar(event, localMagnitude = 0.0014)
        assertFalse("a 0.0014 penumbral eclipse is undetectable", a.isObservable)
        assertTrue(
            "the reason must cite the 0.6 detection threshold",
            a.reasonEn.contains("0.6")
        )
    }

    @Test
    fun testDeepPenumbralEclipseIsObservable() {
        // 2027-02-20: NASA umbral magnitude -0.057 → penumbral magnitude 0.9266,
        // comfortably above the detection threshold.
        val event = lunarEvent(
            EclipseEngine.EclipseType.PENUMBRAL_LUNAR,
            magnitude = 0.9266,
            maximumMs = 1_803_165_180_000L,
            gamma = -1.048
        )
        val a = assessLunar(event, localMagnitude = 0.9266)
        assertTrue("a 0.9266 penumbral eclipse is detectable", a.isObservable)
        assertEquals(EclipseVisibility.Grade.MARGINAL, a.grade)
    }

    @Test
    fun testSubTwoPercentPartialUmbralIsNotObservable() {
        // 2034-09-28: NASA lists umbral magnitude 0.014 with a 00h27m partial
        // phase. That bite is not distinguishable from an ordinary full Moon.
        val event = lunarEvent(
            EclipseEngine.EclipseType.PARTIAL_LUNAR,
            magnitude = 0.0144,
            maximumMs = 2_043_024_376_000L
        )
        assertFalse(assessLunar(event, localMagnitude = 0.0144).isObservable)
        assertTrue(assessLunar(event, localMagnitude = 0.10).isObservable)
    }

    @Test
    fun testLunarUmbralMagnitudeGrading() {
        val partial = lunarEvent(EclipseEngine.EclipseType.PARTIAL_LUNAR, 0.9299)
        assertEquals(
            "0.9 partial umbral is dramatic",
            EclipseVisibility.Grade.EXCELLENT,
            assessLunar(partial, localMagnitude = 0.90).grade
        )
        assertEquals(
            "0.5 partial umbral is unmistakable",
            EclipseVisibility.Grade.GOOD,
            assessLunar(partial, localMagnitude = 0.50).grade
        )
        assertEquals(
            "0.05 partial umbral is marginal",
            EclipseVisibility.Grade.MARGINAL,
            assessLunar(partial, localMagnitude = 0.05).grade
        )
        assertEquals(
            "any total lunar eclipse is spectacular",
            EclipseVisibility.Grade.SPECTACULAR,
            assessLunar(
                lunarEvent(EclipseEngine.EclipseType.TOTAL_LUNAR, 1.8436),
                localMagnitude = 1.84
            ).grade
        )
    }

    // ------------------------------------------------------------------
    // 2. Solar: the obscuration thresholds
    // ------------------------------------------------------------------

    @Test
    fun testSolarObscurationGrading() {
        val partial = solarEvent(EclipseEngine.EclipseType.PARTIAL_SOLAR, 0.5)
        assertEquals(
            EclipseVisibility.Grade.MARGINAL,
            assessSolar(partial, obscurationPercent = 5).grade
        )
        assertEquals(
            EclipseVisibility.Grade.GOOD,
            assessSolar(partial, obscurationPercent = 30).grade
        )
        assertEquals(
            EclipseVisibility.Grade.EXCELLENT,
            assessSolar(partial, obscurationPercent = 80).grade
        )
    }

    @Test
    fun testSolarBelowOnePercentIsNotObservable() {
        val partial = solarEvent(EclipseEngine.EclipseType.PARTIAL_SOLAR, 0.02)
        assertFalse(assessSolar(partial, obscurationPercent = 0).isObservable)
        assertTrue(assessSolar(partial, obscurationPercent = 1).isObservable)
    }

    @Test
    fun testCentralSolarEclipseIsSpectacular() {
        assertEquals(
            EclipseVisibility.Grade.SPECTACULAR,
            assessSolar(
                solarEvent(EclipseEngine.EclipseType.TOTAL_SOLAR, 1.0386),
                obscurationPercent = 100
            ).grade
        )
        assertEquals(
            EclipseVisibility.Grade.SPECTACULAR,
            assessSolar(
                solarEvent(EclipseEngine.EclipseType.ANNULAR_SOLAR, 0.9208),
                obscurationPercent = 100
            ).grade
        )
    }

    // ------------------------------------------------------------------
    // 3. The horizon gate applies to both kinds
    // ------------------------------------------------------------------

    @Test
    fun testBelowHorizonIsNeverObservable() {
        val lunar = assessLunar(
            lunarEvent(EclipseEngine.EclipseType.TOTAL_LUNAR, 1.8436),
            localMagnitude = 1.84,
            isLocallyVisible = false,
            altitudeDeg = -20.0
        )
        assertFalse("a total lunar eclipse below the horizon is not observable", lunar.isObservable)
        assertEquals(EclipseVisibility.Grade.NOT_OBSERVABLE, lunar.grade)

        val solar = assessSolar(
            solarEvent(EclipseEngine.EclipseType.TOTAL_SOLAR, 1.0386),
            obscurationPercent = 100,
            isLocallyVisible = false,
            altitudeDeg = -15.0
        )
        assertFalse("a total solar eclipse below the horizon is not observable", solar.isObservable)
        assertEquals(EclipseVisibility.Grade.NOT_OBSERVABLE, solar.grade)
    }

    @Test
    fun testAltitudeNotesTrackAltitude() {
        val partial = solarEvent(EclipseEngine.EclipseType.PARTIAL_SOLAR, 0.5)
        assertTrue(
            "whole degrees once the object is clear of the horizon",
            assessSolar(partial, obscurationPercent = 50, altitudeDeg = 55.0)
                .altitudeNoteEn.contains("55°")
        )
        assertTrue(
            "a decimal is kept below 10°, where 3° vs 3.9° matters",
            assessSolar(partial, obscurationPercent = 50, altitudeDeg = 3.4)
                .altitudeNoteEn.contains("3.4°")
        )
        assertTrue(
            "a horizon-hugging eclipse must warn about the horizon",
            assessSolar(partial, obscurationPercent = 50, altitudeDeg = 1.0)
                .altitudeNoteEn.contains("horizon")
        )
    }

    // ------------------------------------------------------------------
    // 4. The series is filtered to genuinely observable events
    // ------------------------------------------------------------------

    @Test
    fun testSeriesContainsOnlyObservableEvents() {
        val (solar, lunar) = engine.getNextEclipseSeries(
            nowMs, tehranLat, tehranLon, tehranElev, tehranTz, CalendarSystem.SOLAR_HIJRI, 3
        )
        for (list in listOf(solar, lunar)) {
            assertTrue("a series must never be empty", list.isNotEmpty())
            // Everything except a possible trailing "nearest unobservable" fallback
            // must satisfy the visibility criteria.
            val body = if (list.size == 1) emptyList() else list.dropLast(1)
            body.forEach { r ->
                assertTrue(
                    "${r.event.nameEn} @ ${r.event.maximumMs} is not observable from Tehran " +
                        "but was listed: ${r.visibility.reasonEn}",
                    r.visibility.isObservable
                )
            }
        }
    }

    @Test
    fun testSeriesRespectsTheRequestedCount() {
        val (solar, lunar) = engine.getNextEclipseSeries(
            nowMs, tehranLat, tehranLon, tehranElev, tehranTz, CalendarSystem.SOLAR_HIJRI, 3
        )
        assertTrue("solar series must hold at most 3", solar.size <= 3)
        assertTrue("lunar series must hold at most 3", lunar.size <= 3)
    }

    @Test
    fun testUndetectablePenumbralEclipseIsSkippedFromTehran() {
        // 2027-07-18 (penumbral magnitude 0.0014) must never appear as the next
        // lunar eclipse from Tehran, because nothing can be seen.
        val (_, lunar) = engine.getNextEclipseSeries(
            nowMs, tehranLat, tehranLon, tehranElev, tehranTz, CalendarSystem.SOLAR_HIJRI, 3
        )
        lunar.forEach { r ->
            assertNotEquals(
                "2027-07-18 is a 0.0014 penumbral eclipse and cannot be detected",
                1_815_926_573_000L,
                r.event.maximumMs
            )
        }
    }

    @Test
    fun testSeriesDiffersBetweenDistantObservers() {
        // The whole point of location awareness: two cities on opposite sides of
        // the planet must not get an identical set of "next 3" solar eclipses.
        val tehranSolar = engine.getNextEclipseSeries(
            nowMs, tehranLat, tehranLon, tehranElev, tehranTz, CalendarSystem.SOLAR_HIJRI, 3
        ).first
        val sydneySolar = engine.getNextEclipseSeries(
            nowMs, -33.8688, 151.2093, 20.0, "Australia/Sydney", CalendarSystem.SOLAR_HIJRI, 3
        ).first

        val sameEvents =
            tehranSolar.map { it.event.maximumMs } == sydneySolar.map { it.event.maximumMs }
        val circumstancesDiffer = tehranSolar.indices.any { i ->
            sydneySolar.getOrNull(i)?.let { s ->
                s.isLocallyVisible != tehranSolar[i].isLocallyVisible ||
                    kotlin.math.abs(s.targetAltitudeDeg - tehranSolar[i].targetAltitudeDeg) > 1.0 ||
                    s.localObscurationPercent != tehranSolar[i].localObscurationPercent
            } ?: true
        }
        assertTrue(
            "Tehran and Sydney must not report identical solar circumstances " +
                "(tehran=${tehranSolar.map { it.event.maximumMs }}, " +
                "sydney=${sydneySolar.map { it.event.maximumMs }})",
            !sameEvents || circumstancesDiffer
        )
    }

    // ------------------------------------------------------------------
    // 4b. Location awareness, checked against NASA's eclipse paths
    // ------------------------------------------------------------------

    private fun firstOfKind(
        lat: Double, lon: Double, elev: Double, tz: String,
        solar: Boolean
    ): EclipseEngine.EclipseResult {
        val (s, l) = engine.getNextEclipseSeries(
            nowMs, lat, lon, elev, tz, CalendarSystem.SOLAR_HIJRI, 3
        )
        return (if (solar) s else l).first()
    }

    @Test
    fun testLuxorSeesThe2027August2Totality() {
        // NASA: 2027-08-02 total, path "[Total: Morocco, Spain, Algeria, Libya,
        // Egypt, Saudi Arabia, Yemen, Somalia]". Luxor is inside it.
        val r = firstOfKind(25.6872, 32.6396, 89.0, "Africa/Cairo", solar = true)
        assertEquals(
            "2027-08-02 must be the next observable solar eclipse from Luxor",
            1_817_201_220_000L, r.event.maximumMs
        )
        assertEquals(
            "Luxor is inside the totality path, so obscuration must be 100%",
            100, r.localObscurationPercent
        )
        assertEquals(EclipseVisibility.Grade.SPECTACULAR, r.visibility.grade)
    }

    @Test
    fun testSydneySeesThe2028July22Totality() {
        // NASA: 2028-07-22 total, path "[Total: Australia, N. Z.]".
        val r = firstOfKind(-33.8688, 151.2093, 20.0, "Australia/Sydney", solar = true)
        assertEquals(
            "2028-07-22 must be the next observable solar eclipse from Sydney",
            1_847_847_360_000L, r.event.maximumMs
        )
        assertEquals(100, r.localObscurationPercent)
        assertEquals(EclipseVisibility.Grade.SPECTACULAR, r.visibility.grade)
    }

    @Test
    fun testTehranOnlyGetsAPartialOfThe2027August2Eclipse() {
        // Same event as Luxor sees in totality, but Tehran is roughly 2,000 km
        // north of the path, so it must NOT be reported as anything like total.
        val r = firstOfKind(tehranLat, tehranLon, tehranElev, tehranTz, solar = true)
        assertEquals(1_817_201_220_000L, r.event.maximumMs)
        assertTrue(
            "Tehran is outside the totality path, so obscuration must be well under 100%",
            r.localObscurationPercent in 20..60
        )
        assertNotEquals(
            "a 35% partial must never be graded SPECTACULAR",
            EclipseVisibility.Grade.SPECTACULAR, r.visibility.grade
        )
    }

    @Test
    fun testEachCityGetsItsOwnLunarList() {
        // Lunar eclipses are visible from the whole night side, so which ones a
        // given city can watch depends entirely on where it is when the Moon is up.
        val tehran = engine.getNextEclipseSeries(
            nowMs, tehranLat, tehranLon, tehranElev, tehranTz, CalendarSystem.SOLAR_HIJRI, 3
        ).second.map { it.event.maximumMs }
        val sydney = engine.getNextEclipseSeries(
            nowMs, -33.8688, 151.2093, 20.0, "Australia/Sydney", CalendarSystem.SOLAR_HIJRI, 3
        ).second.map { it.event.maximumMs }
        val luxor = engine.getNextEclipseSeries(
            nowMs, 25.6872, 32.6396, 89.0, "Africa/Cairo", CalendarSystem.SOLAR_HIJRI, 3
        ).second.map { it.event.maximumMs }

        assertNotEquals(
            "Tehran and Sydney must not share a lunar list " +
                "(tehran=$tehran, sydney=$sydney)", tehran, sydney
        )
        assertNotEquals(
            "Tehran and Luxor must not share a lunar list " +
                "(tehran=$tehran, luxor=$luxor)", tehran, luxor
        )
    }

    // ------------------------------------------------------------------
    // 5. Catalogue context (superlatives), checked against NASA
    // ------------------------------------------------------------------

    // 2029-06-26 is the deepest (umbral magnitude 1.844) AND the longest
    // (01h42m totality) lunar eclipse in the 2024-2040 catalogue.
    private val ms2029Jun26 = 1_877_138_520_000L

    // 2028-01-26 annular, central duration 10m27s = 627 s: the longest central
    // phase of any solar eclipse between 2024 and 2040.
    private val ms2028Jan26 = 1_832_512_080_000L

    @Test
    fun testDeepestAndLongestLunarEclipseIs2029June26() {
        val ctx = engine.catalogueContext
        assertEquals(
            "NASA lists 2029-06-26 with umbral magnitude 1.844 — the deepest in range",
            ms2029Jun26, ctx.deepestLunarMs
        )
        assertEquals(
            "NASA lists 2029-06-26 with 01h42m totality — the longest in range",
            ms2029Jun26, ctx.longestLunarTotalityMs
        )
    }

    @Test
    fun testLongestCentralSolarEclipseIs2028January26() {
        assertEquals(
            "NASA lists 2028-01-26 annular with a 10m27s central duration",
            ms2028Jan26, engine.catalogueContext.longestCentralSolarMs
        )
    }

    @Test
    fun testOnlyTetradIsThe2032To2033Sequence() {
        // A tetrad is four or more consecutive TOTAL lunar eclipses with no
        // partial or penumbral eclipse in between. NASA's decade tables show
        // exactly one such run in 2024-2040:
        //   2032-04-25 T, 2032-10-18 T, 2033-04-14 T, 2033-10-08 T
        // (then 2034-04-03 is penumbral, ending the run). The neighbouring runs
        // are only three long: 2025-03-14/2025-09-07/2026-03-03 and
        // 2036-02-11/2036-08-07/2037-01-31.
        val expected = setOf(
            1_966_518_812_000L, // 2032-04-25
            1_981_738_941_000L, // 2032-10-18
            1_997_118_751_000L, // 2033-04-14
            2_012_381_703_000L  // 2033-10-08
        )
        assertEquals(
            "exactly one tetrad exists in the catalogue",
            expected, engine.catalogueContext.tetradMemberMs
        )
    }

    @Test
    fun testTetradMembershipIsSurfacedInSignificance() {
        val ctx = engine.catalogueContext
        val tetradEvent = lunarEvent(
            EclipseEngine.EclipseType.TOTAL_LUNAR, 1.1913,
            maximumMs = 1_966_518_812_000L, durationTotalSeconds = 3930
        )
        val insight = EclipseVisibility.insight(tetradEvent, ctx)
        assertTrue(
            "2032-04-25 is part of a tetrad and must say so",
            insight.significanceEn.contains("tetrad", ignoreCase = true)
        )
        assertTrue(
            insight.significanceFa.contains("تتراد")
        )
    }

    // ------------------------------------------------------------------
    // 6. Colour prediction and naming
    // ------------------------------------------------------------------

    @Test
    fun testTotalLunarEclipsePredictsARedMoon() {
        val ctx = engine.catalogueContext
        val deepTotal = lunarEvent(
            EclipseEngine.EclipseType.TOTAL_LUNAR, 1.8436,
            maximumMs = ms2029Jun26, durationTotalSeconds = 6114
        )
        val deep = EclipseVisibility.insight(deepTotal, ctx)
        assertEquals("a total lunar eclipse is a blood moon", "Blood Moon", deep.nicknameEn)
        assertTrue(
            "a deep total eclipse must predict a red/rusty Moon",
            deep.appearanceEn.contains("deep red") || deep.appearanceEn.contains("rusty")
        )
        assertTrue(
            "the Danjon scale must be cited",
            deep.appearanceEn.contains("Danjon")
        )

        val shallowTotal = lunarEvent(
            EclipseEngine.EclipseType.TOTAL_LUNAR, 1.0944,
            maximumMs = 1_997_118_751_000L, durationTotalSeconds = 2952
        )
        assertTrue(
            "a shallow total eclipse predicts a brighter copper/orange Moon",
            EclipseVisibility.insight(shallowTotal, ctx).appearanceEn.contains("copper")
        )
    }

    @Test
    fun testPersianColourPredictionIsPresentAndLinted() {
        // Persian text must exist and must not be a copy of the English one.
        val ctx = engine.catalogueContext
        val total = lunarEvent(
            EclipseEngine.EclipseType.TOTAL_LUNAR, 1.8436,
            maximumMs = ms2029Jun26, durationTotalSeconds = 6114
        )
        val insight = EclipseVisibility.insight(total, ctx)
        assertTrue(insight.appearanceFa.contains("قرمز"))
        assertTrue(insight.nicknameFa.contains("ماه خونین"))
        assertFalse(
            "Persian must not be left as English",
            insight.appearanceFa == insight.appearanceEn
        )
    }

    @Test
    fun testPenumbralEclipsePredictsNoColourChange() {
        val ctx = engine.catalogueContext
        val pen = lunarEvent(
            EclipseEngine.EclipseType.PENUMBRAL_LUNAR, 0.9266,
            maximumMs = 1_803_165_180_000L
        )
        val insight = EclipseVisibility.insight(pen, ctx)
        assertTrue(
            insight.appearanceEn.contains("No colour change", ignoreCase = true)
        )
        assertTrue(insight.appearanceFa.contains("تغییر رنگی"))
    }

    @Test
    fun testPartialLunarEclipsePredictsNoColourChangeButADarkBite() {
        val ctx = engine.catalogueContext
        val partial = lunarEvent(
            EclipseEngine.EclipseType.PARTIAL_LUNAR, 0.9299,
            maximumMs = 1_787_890_380_000L
        )
        val insight = EclipseVisibility.insight(partial, ctx)
        assertTrue(insight.appearanceEn.contains("No colour change", ignoreCase = true))
        assertTrue(insight.appearanceEn.contains("dark grey", ignoreCase = true))
    }

    @Test
    fun testAnnularSolarEclipseIsNamedRingOfFire() {
        val insight = EclipseVisibility.insight(
            solarEvent(
                EclipseEngine.EclipseType.ANNULAR_SOLAR, 0.9208,
                maximumMs = ms2028Jan26, durationTotalSeconds = 627
            ),
            engine.catalogueContext
        )
        assertEquals("Ring of Fire", insight.nicknameEn)
        assertTrue(insight.appearanceEn.contains("ring of fire", ignoreCase = true))
        assertTrue(
            "the longest central phase must be called out",
            insight.significanceEn.contains("longest", ignoreCase = true)
        )
    }

    @Test
    fun testSarosSeriesIsAlwaysReported() {
        val ctx = engine.catalogueContext
        val event = lunarEvent(
            EclipseEngine.EclipseType.TOTAL_LUNAR, 1.8436,
            maximumMs = ms2029Jun26, durationTotalSeconds = 6114
        )
        // saros = 1 in the fixture
        assertTrue(
            EclipseVisibility.insight(event, ctx).significanceEn.contains("Saros series 1")
        )
    }

    @Test
    fun testEveryCatalogueEventProducesNonEmptyInsight() {
        val ctx = engine.catalogueContext
        val (solar, lunar) = engine.getNextEclipseSeries(
            nowMs, tehranLat, tehranLon, tehranElev, tehranTz, CalendarSystem.SOLAR_HIJRI, 3
        )
        for (r in solar + lunar) {
            assertTrue("nickname must not be blank", r.insight.nicknameEn.isNotBlank())
            assertTrue("appearance must not be blank", r.insight.appearanceEn.isNotBlank())
            assertTrue("Persian appearance must not be blank", r.insight.appearanceFa.isNotBlank())
            assertTrue("Persian nickname must not be blank", r.insight.nicknameFa.isNotBlank())
            assertTrue(
                "Persian significance must not be blank when English is present",
                r.insight.significanceFa.isNotBlank() == r.insight.significanceEn.isNotBlank()
            )
        }
    }
}
