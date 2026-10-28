package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.AstroTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Independent regression tests for [AstroTime.deltaT].
 *
 * These tests intentionally do NOT use the production implementation to generate
 * any expected value. Every expected number below was produced by a separate,
 * independently written port of the NASA Espenak–Meeus polynomial series
 * (Espenak & Meeus, "Five Millennium Canon of Solar Eclipses: −1999 to +3000",
 * NASA/TP-2006-214141, §2.7) and transcribed here as a literal.
 *
 * Reference used to generate the literals
 * ---------------------------------------
 *   Source        : https://eclipse.gsfc.nasa.gov/SEcat5/deltatpoly.html
 *                   ("Polynomial Expressions for Delta T", NASA GSFC)
 *   Cross-checks  : NASA/TP-2006-214141 (5MCSE-Text11.pdf) eqs. 2-11 … 2-18
 *                   NASA/TP-2009-214172 (5MCLE-Text10.pdf)
 *   Retrieved     : 2026-10-13
 *   Time scale    : ΔT = TT − UT1, with UT1 ≈ UTC (|UT1 − UTC| < 0.9 s, as in Meeus)
 *   Decimal year  : NASA uses y = year + (month − 0.5)/12;
 *                   the app uses y = 2000 + (JD_UTC − 2451545.0)/365.25.
 *                   The two differ by under two days, i.e. < 0.01 s of ΔT, which is
 *                   why the tolerance below is 0.01 s rather than 1e-9.
 *   Atmospheric /
 *   observer model: not applicable (ΔT is purely a time-scale quantity).
 */
class DeltaTPolynomialTest {

    /** Helper so the table below reads as (year, month, day, expectedDeltaTSeconds). */
    private fun deltaTOf(y: Int, m: Int, d: Int): Double =
        AstroTime.fromUtcDate(y, m, d, 0, 0, 0).deltaT

    /**
     * Literal NASA reference values, one per published interval that the app can
     * actually reach (1900–2100), plus the historical intervals it can no longer
     * reach only because the time machine clamps to 1900.
     *
     * DECIMAL-YEAR CONVENTION — read before regenerating any value below.
     *
     * Every expected value here is computed under THIS implementation's convention,
     *     y = 2000 + (JD_UTC − 2451545.0) / 365.25
     * which is the one [AstroTime.calculateDeltaT] evaluates. Do NOT regenerate them
     * from the Espenak & Meeus page convention,
     *     y = year + (month − 0.5) / 12
     * The two coincide near the middle of each month but differ by up to 17 days near
     * month boundaries, worth up to 0.110 s of ΔT: 19 of the 21 values below would
     * then miss a 0.01 s tolerance.
     *
     * Tolerance rationale: 0.01 s covers float round-off and nothing else (the
     * convention difference is zero here by construction), while still being roughly
     * four orders of magnitude tighter than the smallest defect this test guards
     * against (the 188 s error at y = 2050).
     */
    @Test
    fun `matches NASA Espenak-Meeus reference values`() {
        val cases = arrayOf(
            // 1900–1920
            Triple(1900, 7 to 1, -2.061584),
            // 1920–1941
            Triple(1925, 7 to 1, 23.892667),
            // 1941–1961
            Triple(1941, 7 to 1, 25.057170),
            Triple(1950, 7 to 1, 29.270683),
            Triple(1960, 12 to 30, 33.548082),
            // 1961–1986
            Triple(1961, 7 to 1, 33.769704),
            Triple(1975, 7 to 1, 45.976912),
            // 1986–2005
            Triple(1986, 7 to 1, 55.095595),
            Triple(1995, 7 to 1, 61.194167),
            Triple(2004, 12 to 30, 64.719401),
            // 2005–2050
            Triple(2005, 7 to 1, 64.859558),
            Triple(2010, 7 to 1, 66.917017),
            Triple(2020, 7 to 1, 71.871564),
            Triple(2024, 7 to 1, 74.166126),
            Triple(2026, 7 to 1, 75.379629),
            Triple(2030, 7 to 1, 77.942402),
            Triple(2040, 7 to 1, 85.132856),
            Triple(2049, 12 to 31, 92.998588),
            // 2050–2150
            Triple(2050, 7 to 1, 94.009133),
            Triple(2075, 7 to 1, 146.956917),
            Triple(2100, 7 to 1, 203.904481)
        )

        for ((year, md, expected) in cases) {
            val actual = deltaTOf(year, md.first, md.second)
            assertEquals(
                "ΔT for $year-${md.first}-${md.second}: expected $expected (NASA), got $actual",
                expected, actual, 0.01
            )
        }
    }

    /**
     * NASA states ΔT reaches "roughly +67 s in 2010, +93 s in 2050 and +203 s in 2100".
     * These are rounded narrative anchors, so the tolerance is 1 s. They tie the model
     * back to the published text rather than only to a transcription of the coefficients.
     */
    @Test
    fun `matches NASA published narrative anchors`() {
        assertEquals(67.0, deltaTOf(2010, 1, 1), 1.0)
        assertEquals(93.0, deltaTOf(2050, 1, 1), 1.0)
        assertEquals(203.0, deltaTOf(2100, 1, 1), 1.0)
        assertEquals(442.0, deltaTOf(2200, 1, 1), 1.0)
    }

    /**
     * Guards the specific historical defect: the 1986–2005 quadratic coefficient
     * 0.060374 had been written as 0.006037 (ten times too small) and the t⁴ and t⁵
     * terms had been dropped. At 2026-07-01 that combination yields ≈ 98.8 s against
     * NASA's 75.38 s.
     *
     * This test fails against the old implementation on both assertions.
     */
    @Test
    fun `rejects the historical 0_006037 coefficient typo`() {
        val t = AstroTime.fromUtcDate(2026, 7, 1, 0, 0, 0)
        val t_ = (t.jdUtc - 2451545.0) / 365.25 // = y − 2000 for the 1986–2005 branch

        val withTypo = 63.86 +
            0.3345 * t_ -
            0.006037 * t_ * t_ +
            0.001727 * t_ * t_ * t_ // note: no t⁴, no t⁵ — both were dropped too

        assertEquals(75.379629, t.deltaT, 0.01)
        assertTrue(
            "ΔT must not reproduce the 0.006037 typo polynomial " +
                "(typo gives ${"%.2f".format(withTypo)} s, NASA gives 75.38 s)",
            abs(t.deltaT - withTypo) > 20.0
        )
    }

    /**
     * A wrong polynomial is bad; a large step is worse, because it makes every derived
     * position jump as the user scrubs the time machine. The old code stepped by
     * +350.7 s at y = 1950 and by −188.4 s at y = 2050.
     *
     * WHAT THIS ACTUALLY MEASURES. It samples 1900-01-02 and then 1900–2100 on days
     * 1, 8, 15 and 22 — a 7–8 DAY step, not a daily one — and bounds the change in ΔT
     * between consecutive samples. Over that grid (9 648 samples) the worst step is
     * 0.064614 s, at 2100-11-01, against the 0.1 s threshold below: a margin of only
     * 0.0354 s.
     *
     * This is a REGRESSION GUARD, not a continuity proof. The published series is in
     * fact discontinuous at 6 of its 14 internal boundaries, the largest step being
     * −0.251115 s at y = 1600; see the CONTINUITY note on
     * [AstroTime.calculateDeltaT]. Those steps are smaller than the drift over one
     * 7–8 day sampling interval, so the sweep passes despite them. The guard is still
     * effective — it catches the historical +350.7 s and −188.4 s defects by more than
     * three orders of magnitude — but do NOT read the 0.1 s threshold as "ΔT is
     * continuous to 0.1 s", and do not tighten it without re-measuring.
     */
    @Test
    fun `is continuous across the whole time-machine range`() {
        var previous = deltaTOf(1900, 1, 2)
        var worstStep = 0.0
        var worstDate = ""

        var year = 1900
        while (year <= 2100) {
            for (month in 1..12) {
                for (day in intArrayOf(1, 8, 15, 22)) {
                    val current = deltaTOf(year, month, day)
                    val step = abs(current - previous)
                    if (step > worstStep) {
                        worstStep = step
                        worstDate = "$year-$month-$day"
                    }
                    previous = current
                }
            }
            year += 1
        }

        // Threshold rationale: 0.1 s is more than three orders of magnitude tighter
        // than the +350.7 s / −188.4 s defects this guards against, while clearing the
        // measured worst step (0.064614 s) by 0.0354 s. It is deliberately NOT tighter:
        // the published series' own boundary steps exceed that margin.
        assertTrue(
            "ΔT changed by $worstStep s between consecutive samples around $worstDate; " +
                "expected < 0.1 s (the measured worst step over this grid is 0.064614 s " +
                "at 2100-11-01, so the margin is only 0.0354 s)",
            worstStep < 0.1
        )
    }

    /**
     * The old implementation threw [IllegalArgumentException] for y < 1620 and
     * y > 2150 from a public property, which was a crash on any out-of-window date.
     * The corrected model is total: inside the Canon interval (−1999 … +3000) it
     * returns the published values, and outside it continues with the same
     * long-term parabola Espenak & Meeus prescribe. Those outer values are an
     * EXTRAPOLATION, not observations, so this test only asserts the documented
     * contract (finite, finite, monotonic-ish, no throw) — not an accuracy claim.
     */
    @Test
    fun `returns a finite value outside the Canon interval instead of throwing`() {
        for (year in intArrayOf(-1000, 0, 1000, 1500, 1600, 2200, 2500, 3000)) {
            val value = deltaTOf(year, 6, 15)
            assertTrue("ΔT for y=$year must be finite, was $value", value.isFinite())
        }
        // Sanity on the documented long-term trend: ΔT grows without bound away from 1820.
        assertTrue(deltaTOf(3000, 6, 15) > deltaTOf(2200, 6, 15))
        assertTrue(deltaTOf(-1000, 6, 15) > deltaTOf(0, 6, 15))
    }

    /** ΔT must feed TT consistently: JD_TT = JD_UTC + ΔT/86400. */
    @Test
    fun `julian date in TT is offset from UTC by delta T`() {
        val t = AstroTime.fromUtcDate(2026, 7, 1, 0, 0, 0)
        assertEquals(t.jdUtc + t.deltaT / 86400.0, t.jdTt, 1e-12)
        assertEquals((t.jdTt - 2451545.0) / 36525.0, t.jcTt, 1e-12)
    }
}
