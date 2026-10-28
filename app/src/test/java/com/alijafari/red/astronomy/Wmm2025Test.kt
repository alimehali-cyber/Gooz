package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.Wmm2025
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WMM2025 against every row of NOAA NCEI's official WMM2025_TEST_VALUES.txt
 * (https://www.ncei.noaa.gov/sites/default/files/2025-02/WMM2025_TEST_VALUES.txt).
 * The published values are rounded to 0.1 nT and 0.01°.
 */
class Wmm2025Test {

    // date, height km, lat, lon, X, Y, Z, H, F, I, D
    private val rows = listOf(
        doubleArrayOf(2025.0, 0.0, 80.0, 0.0, 6521.6, 145.9, 54791.5, 6523.2, 55178.5, 83.21, 1.28),
        doubleArrayOf(2025.0, 0.0, 0.0, 120.0, 39677.8, -109.6, -10580.2, 39677.9, 41064.3, -14.93, -0.16),
        doubleArrayOf(2025.0, 0.0, -80.0, 240.0, 6117.5, 15751.9, -52022.5, 16898.1, 54698.2, -72.00, 68.78),
        doubleArrayOf(2025.0, 100.0, 80.0, 0.0, 6216.0, 92.4, 52598.8, 6216.7, 52964.9, 83.26, 0.85),
        doubleArrayOf(2025.0, 100.0, 0.0, 120.0, 37688.6, -96.2, -10152.1, 37688.7, 39032.1, -15.08, -0.15),
        doubleArrayOf(2025.0, 100.0, -80.0, 240.0, 5907.6, 14780.3, -49540.7, 15917.1, 52035.0, -72.19, 68.21),
        doubleArrayOf(2027.5, 0.0, 80.0, 0.0, 6500.8, 294.5, 54869.4, 6507.5, 55253.9, 83.24, 2.59),
        doubleArrayOf(2027.5, 0.0, 0.0, 120.0, 39701.6, -167.4, -10381.8, 39702.0, 41036.9, -14.65, -0.24),
        doubleArrayOf(2027.5, 0.0, -80.0, 240.0, 6200.7, 15730.3, -51783.7, 16908.3, 54474.2, -71.92, 68.49),
        doubleArrayOf(2027.5, 100.0, 80.0, 0.0, 6196.7, 233.8, 52670.5, 6201.1, 53034.3, 83.29, 2.16),
        doubleArrayOf(2027.5, 100.0, 0.0, 120.0, 37711.5, -148.7, -9969.8, 37711.8, 39007.4, -14.81, -0.23),
        doubleArrayOf(2027.5, 100.0, -80.0, 240.0, 5984.0, 14760.1, -49317.7, 15927.0, 51825.7, -72.10, 67.93)
    )

    @Test
    fun `matches every official NOAA WMM2025 test value`() {
        for (r in rows) {
            val f = Wmm2025.calculate(r[2], r[3], r[1], r[0])
            val tag = "date=${r[0]} h=${r[1]} lat=${r[2]} lon=${r[3]}"
            assertEquals("X $tag", r[4], f.x, 0.06)
            assertEquals("Y $tag", r[5], f.y, 0.06)
            assertEquals("Z $tag", r[6], f.z, 0.06)
            assertEquals("H $tag", r[7], f.h, 0.06)
            assertEquals("F $tag", r[8], f.f, 0.06)
            assertEquals("I $tag", r[9], f.inclinationDeg, 0.006)
            assertEquals("D $tag", r[10], f.declinationDeg, 0.006)
        }
    }

    @Test
    fun `flags dates outside the model validity and gives a sane Frankfurt field`() {
        val f = Wmm2025.calculate(50.11, 8.68, 0.11, 2026.79)
        assertTrue(f.isWithinValidity)
        assertEquals(3.8, f.declinationDeg, 0.3)
        assertEquals(65.9, f.inclinationDeg, 0.5)
        assertEquals(49200.0, f.f, 400.0)
        assertTrue("WMM error-model sigma D must be sub-degree at mid latitudes", f.declinationUncertaintyDeg < 1.0)
        assertFalse(Wmm2025.calculate(50.11, 8.68, 0.0, 2031.0).isWithinValidity)
    }
}
