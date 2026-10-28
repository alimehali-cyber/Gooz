package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.AstroTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AstroTimeTest {

    private val tolerance = 1e-6  // 6 decimal places for JD comparisons

    @Test
    fun `test J2000 epoch`() {
        // J2000.0 = 2000-01-01 12:00:00 TT = JD 2451545.0 TT
        // In UTC, this is approximately 2000-01-01 11:58:55.816 UTC
        // JD_UTC should be approximately 2451545.0 - ΔT/86400
        // ΔT for 2000 is approximately 63.86 seconds
        // So JD_UTC ≈ 2451545.0 - 63.86/86400 ≈ 2451544.99926

        val astroTime = AstroTime.fromUtcDate(2000, 1, 1, 11, 58, 56)
        val expectedJdUtc = 2451545.0 - 63.86 / 86400.0

        assertEquals(expectedJdUtc, astroTime.jdUtc, 0.0001)
    }

    @Test
    fun `test Delta T for year 2024`() {
        // 73.870536 s is the NASA Espenak-Meeus value for 2024-01-01 00:00 UTC
        // (2005-2050 interval). It is a transcribed literal from an independent port
        // of the published polynomials, NOT recomputed with the app's own formula.
        // The previous version of this test asserted 63.86 + 0.3345*24 - 0.006037*576
        // + 0.001727*13824, which merely restated the (incorrect) production code.
        val astroTime = AstroTime.fromUtcDate(2024, 1, 1, 0, 0, 0)

        assertEquals(73.870536, astroTime.deltaT, 0.01)
    }

    @Test
    fun `test Delta T for year 2026`() {
        // 75.074584 s is the NASA Espenak-Meeus value for 2026-01-01 00:00 UTC
        // (2005-2050 interval). Transcribed literal; see DeltaTPolynomialTest.
        val astroTime = AstroTime.fromUtcDate(2026, 1, 1, 0, 0, 0)

        assertEquals(75.074584, astroTime.deltaT, 0.01)
    }

    @Test
    fun `test JD to AstroTime roundtrip`() {
        val originalJd = 2460000.5
        val astroTime = AstroTime.fromJd(originalJd)
        val roundtrippedJd = astroTime.jdUtc

        assertEquals(originalJd, roundtrippedJd, tolerance)
    }

    @Test
    fun `test jdTt is greater than jdUtc`() {
        val astroTime = AstroTime.fromUtcDate(2026, 8, 12, 0, 0, 0)
        assertTrue("jdTt must be greater than jdUtc because ΔT > 0", astroTime.jdTt > astroTime.jdUtc)
    }

    @Test
    fun `test jcTt for J2000 epoch`() {
        // At J2000.0, jcTt should be approximately 0
        val astroTime = AstroTime.fromUtcDate(2000, 1, 1, 11, 58, 56)
        assertEquals(0.0, astroTime.jcTt, 0.0001)
    }

    @Test
    fun `test jcTt for 2026`() {
        // 2026.0 - 2000.0 = 26 years = 0.26 centuries
        val astroTime = AstroTime.fromUtcDate(2026, 1, 1, 0, 0, 0)
        assertEquals(0.26, astroTime.jcTt, 0.01)
    }

    @Test
    fun `test now() returns non-null`() {
        val now = AstroTime.now()
        assertNotNull(now)
        assertTrue("JD for current time should be > 2450000", now.jdUtc > 2450000.0)
    }

    @Test
    fun `test fromUtcDate creates correct date`() {
        val astroTime = AstroTime.fromUtcDate(2026, 8, 12, 17, 47, 0)
        assertEquals(2026, astroTime.getYear())
    }

    @Test
    fun `test Delta T returns a finite extrapolation instead of throwing`() {
        // deltaT used to throw IllegalArgumentException outside 1620..2150 from a
        // public property, so any out-of-window date was a crash. The model is now
        // total. These values are EXTRAPOLATIONS of the Espenak-Meeus long-term
        // parabola and are not observations, so only finiteness is asserted here.
        val year1000 = AstroTime.fromUtcDate(1000, 1, 1, 0, 0, 0).deltaT
        val year2200 = AstroTime.fromUtcDate(2200, 1, 1, 0, 0, 0).deltaT

        assertTrue("deltaT for year 1000 must be finite, was $year1000", year1000.isFinite())
        assertTrue("deltaT for year 2200 must be finite, was $year2200", year2200.isFinite())

        // Monotonicity only holds WITHIN the post-2150 parabolic branch, where
        //     ΔT = −20 + 32·((y − 1820)/100)²
        // grows with |y − 1820|. It does NOT hold across branches, and an earlier
        // revision asserted `year2200 > year1000`, which is false:
        //   · year 1000 is not in the parabolic branch at all — it falls in the
        //     [500, 1600) 6th-degree interval, where ΔT ≈ 1574 s;
        //   · year 2200 is in the parabolic branch with ΔT ≈ 442 s;
        //   · even evaluated on the parabola alone, year 1000 is 820 years from the
        //     vertex against year 2200's 380, so year 1000 would still be the larger.
        // Measured: ΔT(1000-01-01) = 1574.086 s, ΔT(2200-01-01) = 442.070 s.
        val year2500 = AstroTime.fromUtcDate(2500, 1, 1, 0, 0, 0).deltaT
        assertTrue(
            "deltaT must grow with |y−1820| inside the post-2150 branch " +
                "($year2500 should exceed $year2200)",
            year2500 > year2200
        )
    }

    @Test
    fun `test Delta T for year 1970`() {
        // 40.192941 s is the NASA Espenak-Meeus value for 1970-01-01 00:00 UTC
        // (1961-1986 interval). Transcribed literal; see DeltaTPolynomialTest.
        val astroTime = AstroTime.fromUtcDate(1970, 1, 1, 0, 0, 0)

        assertEquals(40.192941, astroTime.deltaT, 0.01)
    }

    @Test
    fun `test toString contains expected info`() {
        val astroTime = AstroTime.fromUtcDate(2026, 8, 12, 0, 0, 0)
        val str = astroTime.toString()
        assertTrue(str.contains("JD_UTC"))
        assertTrue(str.contains("JD_TT"))
        assertTrue(str.contains("ΔT"))
    }

    @Test
    fun `test equals and hashCode`() {
        val t1 = AstroTime.fromUtcDate(2026, 8, 12, 0, 0, 0)
        val t2 = AstroTime.fromUtcDate(2026, 8, 12, 0, 0, 0)
        val t3 = AstroTime.fromUtcDate(2026, 8, 13, 0, 0, 0)

        assertEquals(t1, t2)
        assertEquals(t1.hashCode(), t2.hashCode())
        assertTrue(t1 != t3)
    }
}
