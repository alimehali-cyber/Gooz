package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.CoordinateEngineLegacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent regression tests for the geometric rise / transit / set solver in
 * [CoordinateEngineLegacy.calculateRiseSetTransit].
 *
 * Reference used to generate the expected strings
 * -----------------------------------------------
 *   A separate implementation written from first principles: GMST from the standard
 *   IAU polynomial
 *       GMST = 280.46061837 + 360.98564736629·(JD − 2451545.0) + 0.000387933·T² − T³/38710000,
 *   T = (JD − 2451545)/36525, LAST = GMST + longitude, and the analytic solution
 *       cos H₀ = (sin h₀ − sin φ sin δ) / (cos φ cos δ)
 *       t_event = startOfDay + ((target hour angle − hour angle at start of day) mod 360)
 *                 / 15.0410686 hours.
 *   It shares no code with the app.
 *
 *   Retrieved/generated: 2026-10-13.
 *
 * Known modelling differences and their size
 * ------------------------------------------
 *   - The reference uses GMST; the app uses GAST (GMST + nutation). The equation of
 *     the equinoxes is under 1.3 s of time, so at the 1-minute resolution of the
 *     returned "HH:mm" strings it is invisible. Every fixture below was chosen to sit
 *     at least 19 s clear of a minute boundary so this cannot flip a digit.
 *   - The solver holds RA/Dec constant over the day. That is exact for the stars these
 *     fixtures use and is not an approximation being tested here.
 *   - No atmospheric model beyond the fixed h₀ (see the horizon constants); pressure
 *     and temperature are not inputs to this function.
 *
 * Tolerance: the function returns formatted "HH:mm" strings, so ±1 minute is the finest
 * resolution available. Fixtures were deliberately chosen so the quantities under test
 * (≈3 min for the solar-semidiameter convention, 210 min for the timezone) are far
 * larger than that.
 */
class RiseSetTransitTest {

    private fun jdOf(year: Int, month: Int, day: Int, hour: Int = 12): Double =
        AstroTime.fromUtcDate(year, month, day, hour, 0, 0).jdUtc

    /** isFa=false so the strings come back as Latin-digit "HH:mm" and can be compared directly. */
    private fun riseSet(
        raDeg: Double,
        decDeg: Double,
        latDeg: Double,
        lonDeg: Double,
        jd: Double,
        timezoneId: String? = null,
        horizonAltDeg: Double = CoordinateEngineLegacy.STAR_HORIZON_ALT_DEG
    ) = CoordinateEngine.calculateRiseSetTransit(
        raDeg = raDeg,
        decDeg = decDeg,
        latDeg = latDeg,
        lonDeg = lonDeg,
        jd = jd,
        isFa = false,
        timezoneId = timezoneId,
        horizonAltDeg = horizonAltDeg
    )

    // ---------------------------------------------------------------------------------
    // Point sources: the analytic solution against the independent reference
    // ---------------------------------------------------------------------------------

    @Test
    fun `point source on the celestial equator`() {
        val rst = riseSet(raDeg = 0.0, decDeg = 0.0, latDeg = 30.0, lonDeg = 0.0,
            jd = jdOf(2026, 1, 1), timezoneId = "UTC")

        assertEquals("rise", "11:12", rst.riseTimeStr)
        assertEquals("transit", "17:14", rst.transitTimeStr)
        assertEquals("set", "23:16", rst.setTimeStr)
        assertTrue("not circumpolar", !rst.isCircumpolar)
        assertTrue("rises", !rst.neverRises)
    }

    @Test
    fun `point source at positive declination`() {
        // Longer diurnal arc than the equatorial case: rises earlier, sets later.
        val rst = riseSet(raDeg = 0.0, decDeg = 20.0, latDeg = 30.0, lonDeg = 0.0,
            jd = jdOf(2026, 1, 1), timezoneId = "UTC")

        assertEquals("rise", "10:24", rst.riseTimeStr)
        assertEquals("transit", "17:14", rst.transitTimeStr)
        assertEquals("set", "00:08", rst.setTimeStr)
    }

    // ---------------------------------------------------------------------------------
    // Edge cases: objects that never rise, and objects that never set
    // ---------------------------------------------------------------------------------

    @Test
    fun `object far north of the observer is circumpolar`() {
        val rst = riseSet(raDeg = 0.0, decDeg = 70.0, latDeg = 60.0, lonDeg = 0.0,
            jd = jdOf(2026, 1, 1), timezoneId = "UTC")

        assertTrue("lat 60 / dec +70 is circumpolar (cos H0 < -1)", rst.isCircumpolar)
        assertTrue("must not also report neverRises", !rst.neverRises)
        assertEquals("Circumpolar (Always visible)", rst.riseTimeStr)
    }

    @Test
    fun `object far south of the observer never rises`() {
        val rst = riseSet(raDeg = 0.0, decDeg = -70.0, latDeg = 60.0, lonDeg = 0.0,
            jd = jdOf(2026, 1, 1), timezoneId = "UTC")

        assertTrue("lat 60 / dec -70 never rises (cos H0 > 1)", rst.neverRises)
        assertTrue("must not also report circumpolar", !rst.isCircumpolar)
        assertEquals("Never rises", rst.riseTimeStr)
    }

    // ---------------------------------------------------------------------------------
    // Horizon convention: the Sun is not a point source
    // ---------------------------------------------------------------------------------

    /**
     * The Sun's event is defined by its UPPER LIMB, i.e. h₀ = −0.833° =
     * −(0.5667° refraction + 0.2667° semidiameter). Using the point-source −0.5667°
     * for the Sun reports sunrise about 3 minutes late and sunset about 3 minutes
     * early at this latitude.
     */
    @Test
    fun `the sun uses the upper limb horizon and not the point source horizon`() {
        val jd = jdOf(2026, 3, 20)
        val asPointSource = riseSet(0.0, -10.0, 67.0, 0.0, jd, "UTC",
            CoordinateEngineLegacy.STAR_HORIZON_ALT_DEG)
        val asSun = riseSet(0.0, -10.0, 67.0, 0.0, jd, "UTC",
            CoordinateEngineLegacy.SUN_HORIZON_ALT_DEG)

        assertEquals("sun rise", "07:37", asSun.riseTimeStr)
        assertEquals("sun transit", "12:07", asSun.transitTimeStr)
        assertEquals("sun set", "16:38", asSun.setTimeStr)

        assertEquals("point-source rise", "07:40", asPointSource.riseTimeStr)
        assertEquals("point-source set", "16:35", asPointSource.setTimeStr)

        // Transit is unaffected: h0 only changes the rise/set hour angle, never the meridian.
        assertEquals("transit must be identical for both conventions",
            asPointSource.transitTimeStr, asSun.transitTimeStr)
    }

    @Test
    fun `horizonAltDegFor maps catalog ids to the right convention`() {
        assertEquals(CoordinateEngineLegacy.SUN_HORIZON_ALT_DEG,
            CoordinateEngineLegacy.horizonAltDegFor("sun"), 0.0)
        assertEquals(CoordinateEngineLegacy.MOON_HORIZON_ALT_DEG,
            CoordinateEngineLegacy.horizonAltDegFor("moon"), 0.0)
        assertEquals(CoordinateEngineLegacy.STAR_HORIZON_ALT_DEG,
            CoordinateEngineLegacy.horizonAltDegFor("sirius"), 0.0)
        assertEquals(CoordinateEngineLegacy.STAR_HORIZON_ALT_DEG,
            CoordinateEngineLegacy.horizonAltDegFor(null), 0.0)
    }

    // ---------------------------------------------------------------------------------
    // Timezone: the regression that made every non-Iranian user see Tehran wall clock
    // ---------------------------------------------------------------------------------

    /**
     * This is the bug the timezone parameter fixes. Before, "start of day" was pinned to
     * midnight Asia/Tehran and the result was rendered through
     * [com.alijafari.red.astronomy.astro_engine.TimeEngine.formatTime24h], whose default
     * timezone is also Asia/Tehran — so a user in Berlin, London or New York was shown
     * rise/set times in Iranian wall-clock, offset by their UTC difference (here exactly
     * 3 h 30 min).
     */
    @Test
    fun `the observer timezone controls the rendered wall clock`() {
        val jd = jdOf(2026, 1, 1)

        val utc = riseSet(0.0, 0.0, 30.0, 0.0, jd, "UTC")
        assertEquals("UTC rise", "11:12", utc.riseTimeStr)
        assertEquals("UTC transit", "17:14", utc.transitTimeStr)
        assertEquals("UTC set", "23:16", utc.setTimeStr)

        val tehran = riseSet(0.0, 0.0, 30.0, 0.0, jd, "Asia/Tehran")
        assertEquals("Tehran rise (UTC+3:30)", "14:42", tehran.riseTimeStr)
        assertEquals("Tehran transit", "20:44", tehran.transitTimeStr)
        assertEquals("Tehran set", "02:50", tehran.setTimeStr)

        // Only the rendering timezone differs, not the geometry: rise, transit and set all
        // move by the same +3:30 offset.
        assertEquals("transit shift", "17:14", utc.transitTimeStr)
        assertEquals("transit shift", "20:44", tehran.transitTimeStr)
    }

    /**
     * Documents the fallback rather than endorsing it: a caller that omits the timezone
     * still gets Asia/Tehran, which is why every UI call site now passes
     * `uiState.userLocation.timezoneId` explicitly.
     */
    @Test
    fun `omitting the timezone falls back to Tehran for backward compatibility`() {
        val jd = jdOf(2026, 1, 1)
        val omitted = riseSet(0.0, 0.0, 30.0, 0.0, jd, null)
        val tehran = riseSet(0.0, 0.0, 30.0, 0.0, jd, "Asia/Tehran")

        assertEquals(tehran.riseTimeStr, omitted.riseTimeStr)
        assertEquals(tehran.transitTimeStr, omitted.transitTimeStr)
        assertEquals(tehran.setTimeStr, omitted.setTimeStr)
    }

    /** Longitude must move the event independently of the timezone used to render it. */
    @Test
    fun `longitude shifts the event independently of the rendering timezone`() {
        val jd = jdOf(2026, 1, 1)
        val greenwich = riseSet(0.0, 0.0, 30.0, 0.0, jd, "UTC")
        val tehranLon = riseSet(0.0, 0.0, 30.0, 51.5217, jd, "UTC")

        assertEquals("rise at 51.5217E", "07:47", tehranLon.riseTimeStr)
        assertEquals("set at 51.5217E", "19:50", tehranLon.setTimeStr)
        assertEquals("greenwich rise", "11:12", greenwich.riseTimeStr)
        assertEquals("greenwich transit", "17:14", greenwich.transitTimeStr)

        // KNIFE-EDGE FIXTURE — do NOT "correct" this back to 13:49.
        //
        // Moving 51.5217 deg east moves every event earlier by
        //     51.5217 / 15.0410686 deg-per-hour = 3.4254016 h = 3 h 25 min 31.446 s.
        // The Greenwich transit is at 17:14:31.190, so the transit at 51.5217E is at
        //     17:14:31.190 − 3:25:31.446 = 13:48:59.745 UTC
        // — only 0.255 s short of 13:49:00.
        //
        // Both the hour-angle solve and the event-to-millisecond conversion truncate:
        // `(transitHoursFrom0h * 3600000.0).toLong()` truncates toward zero, and
        // TimeEngine.formatTime24h uses SimpleDateFormat("HH:mm"), which truncates
        // rather than rounds. So the correct string is "13:48".
        //
        // The old literal ("13:49") came from applying the shift rounded to
        // "3 h 25.5 min" against the already-truncated 17:14, which lands 0.3 s into
        // the next minute. Two independent references agree on the true instant:
        //   · root-find on LAST(t)                        -> 13:48:59.745
        //   · PyEphem 4.2.1 next_transit (epoch pinned to
        //     the observation date, so it is not precessed) -> 13:48:59.665
        //
        // Because this literal sits 0.255 s from a minute boundary, any future change
        // to the nutation series, the GMST polynomial or the 15.04107 rate constant
        // must be re-derived here rather than assumed.
        assertEquals("transit at 51.5217E", "13:48", tehranLon.transitTimeStr)
        assertEquals("longitude must shift transit by 3 h 25 min",
            "13:48", tehranLon.transitTimeStr)
    }
}
