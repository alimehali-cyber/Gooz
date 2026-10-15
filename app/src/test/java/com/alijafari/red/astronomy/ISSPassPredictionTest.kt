package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.ISSEngine
import com.alijafari.red.astronomy.astro_engine.ISSEngine.PassClassification
import com.alijafari.red.astronomy.astro_engine.SunEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class ISSPassPredictionTest {

    private val tehranLat = 35.6892
    private val tehranLon = 51.3890

    @Test
    fun testVisiblePassesFilterOutDaylightAndDeepShadow() {
        val startMs = 1755734400000L // Approx August 2025
        val visiblePasses = ISSEngine.predictPasses(
            userLatDeg = tehranLat,
            userLonDeg = tehranLon,
            startTimestampMs = startMs,
            scanDays = 14,
            visibleOnly = true
        )

        for (pass in visiblePasses) {
            assertTrue("Every visible pass must have isVisible == true", pass.isVisible)
            assertTrue("Every visible pass must reach at least 8.0° elevation", pass.maxElevationDeg >= 8.0)
            assertTrue("Every visible pass must have at least 20s duration", pass.passDurationSec >= 20)

            // Verify observer sky is dark enough at peak (Sun altitude <= -6.0°)
            val sunAlt = SunEngine.getSunAltitude(pass.maxTimeMs, tehranLat, tehranLon)
            assertTrue("Sun altitude at peak of visible pass must be <= -6.0° (civil twilight ended)", sunAlt <= -6.0)

            // Verify satellite is sunlit at max time
            val posAtMax = ISSEngine.calculateTopocentricPos(pass.maxTimeMs, tehranLat, tehranLon, 940.0, ISSEngine.cachedTLE)
            assertTrue("Satellite must be illuminated by Sun at maxTime", posAtMax.isSunlit)

            // Verify valid classification for visible pass
            assertTrue(
                "Pass classification should be visible type",
                pass.classification in listOf(
                    PassClassification.OUTSTANDING,
                    PassClassification.EXCELLENT,
                    PassClassification.VERY_GOOD,
                    PassClassification.GOOD,
                    PassClassification.MARGINAL
                )
            )
        }
    }

    @Test
    fun testAllPassesIncludesDaylightAndEclipsedPassesWithCorrectFlag() {
        val startMs = 1755734400000L
        val allPasses = ISSEngine.predictPasses(
            userLatDeg = tehranLat,
            userLonDeg = tehranLon,
            startTimestampMs = startMs,
            scanDays = 7,
            visibleOnly = false
        )

        assertTrue("Should detect multiple geometric passes in 7 days", allPasses.size >= 10)

        val visiblePasses = allPasses.filter { it.isVisible }
        val nonVisiblePasses = allPasses.filter { !it.isVisible }

        assertTrue("Should contain both visible and non-visible geometric passes", visiblePasses.isNotEmpty() && nonVisiblePasses.isNotEmpty())

        for (nonVis in nonVisiblePasses) {
            assertFalse("Non-visible pass should have isVisible == false", nonVis.isVisible)
            assertTrue(
                "Non-visible pass classification should be non-visible category",
                nonVis.classification in listOf(
                    PassClassification.DAYLIGHT_ONLY,
                    PassClassification.INVISIBLE_SHADOW,
                    PassClassification.NOT_VISIBLE,
                    PassClassification.POOR
                )
            )
        }
    }

    @Test
    fun testConicalShadowModel() {
        // Satellite directly on night side behind center of Earth at 400 km altitude
        // Should be in shadow
        val jd = 2460000.0
        val gmstDeg = 0.0

        // Sub-solar point is at RA 0, Dec 0
        // Point at X = -6778.0, Y = 0, Z = 0 is directly in anti-solar direction behind Earth
        val isNightSideSunlit = ISSEngine.checkIssSunlit(
            jd = jd,
            gmstDeg = gmstDeg,
            xEcef = -6778.0,
            yEcef = 0.0,
            zEcef = 0.0
        )
        assertFalse("Satellite directly behind Earth must be in shadow (eclipsed)", isNightSideSunlit)

        // Point at X = +6778.0, Y = 0, Z = 0 is directly in sub-solar direction towards Sun
        val isDaySideSunlit = ISSEngine.checkIssSunlit(
            jd = jd,
            gmstDeg = gmstDeg,
            xEcef = 6778.0,
            yEcef = 0.0,
            zEcef = 0.0
        )
        assertTrue("Satellite facing Sun must be sunlit", isDaySideSunlit)
    }

    @Test
    fun testNurabadPassOnAug21() {
        val nurabadLat = 30.1141
        val nurabadLon = 51.5217

        val liveTle = ISSEngine.TLEData(
            name = "ISS (ZARYA)",
            line1 = "1 25544U 98067A   26231.79184849  .00010403  00000+0  19311-3 0  9999",
            line2 = "2 25544  51.6331 345.2935 0007660  63.7133 296.4642 15.49516880581615"
        )

        // Aug 20 2026 00:00:00 UTC
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.AUGUST, 20, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = cal.timeInMillis

        val allPasses = ISSEngine.predictPasses(
            userLatDeg = nurabadLat,
            userLonDeg = nurabadLon,
            startTimestampMs = startMs,
            tle = liveTle,
            scanDays = 3,
            visibleOnly = false
        )

        val iranTz = TimeZone.getTimeZone("GMT+03:30")
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = iranTz
        }
        fun iranMs(hour: Int, minute: Int): Long = Calendar.getInstance(iranTz).apply {
            set(2026, Calendar.AUGUST, 21, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        println("=== ALL NURABAD GEOMETRIC PASSES (Count: ${allPasses.size}) ===")
        for (p in allPasses) {
            val sunAlt = SunEngine.getSunAltitude(p.maxTimeMs, nurabadLat, nurabadLon)
            val topo = ISSEngine.calculateTopocentricPos(p.maxTimeMs, nurabadLat, nurabadLon, 940.0, liveTle)
            println("Pass: Start=${sdf.format(java.util.Date(p.startTimeMs))}, Peak=${sdf.format(java.util.Date(p.maxTimeMs))}, End=${sdf.format(java.util.Date(p.endTimeMs))}, MaxElev=${String.format(Locale.US, "%.1f", p.maxElevationDeg)}°, SunAlt=${String.format(Locale.US, "%.1f", sunAlt)}°, SatSunlit=${topo.isSunlit}, Vis=${p.isVisible}, Class=${p.classification.name}, Reason=${p.summaryReasonEn}")
        }

        val visiblePasses = ISSEngine.predictPasses(
            userLatDeg = nurabadLat,
            userLonDeg = nurabadLon,
            startTimestampMs = startMs,
            tle = liveTle,
            scanDays = 3,
            visibleOnly = true
        )

        println("=== NURABAD VISIBLE PASSES (Count: ${visiblePasses.size}) ===")
        for (p in visiblePasses) {
            println("Visible Pass: Start=${sdf.format(java.util.Date(p.startTimeMs))}, Peak=${sdf.format(java.util.Date(p.maxTimeMs))}, End=${sdf.format(java.util.Date(p.endTimeMs))}, MaxElev=${String.format(Locale.US, "%.1f", p.maxElevationDeg)}°, Class=${p.classification.name}")
        }

        // The 2026-08-21 ~04:09 Iran flyover above Nurabad is real, but it is a 9.32 deg
        // graze, and the predictor's peak filter (ISSEngine.kt:401, "flyovers below 9.5 deg
        // are filtered out") must reject it. This assertion used to require the opposite --
        // that the pass be listed as visible -- which only held while the propagator was
        // wrong. Independent references for the same geometry, same instants:
        //
        //   python-sgp4 2.25, WGS-72 (the AIAA 2006-6753 reference implementation): 9.3170 deg
        //   official AIAA 2006-6753 C++ (run from this repo's own kernels, WGS-72):  9.3168 deg
        //   Skyfield 1.55 / ERFA, same topocentric reduction as ISSEngine:            9.317 deg
        //
        // The app now computes 9.317 deg; before the fix it computed 9.320 deg. The pass sits
        // 0.18 deg under the cut either way, so the filter is what decides it, not the fix --
        // which is why the fix must not be allowed to change the outcome, and why the test has
        // to track the filter rather than demand a pass the physics does not support.
        val windowStartMs = iranMs(4, 0)
        val windowEndMs = iranMs(4, 20)
        val inWindow = { ms: Long -> ms in windowStartMs..windowEndMs }

        assertTrue(
            "The 04:09 Iran pass peaks at 9.32 deg and must not survive the 9.5 deg filter; " +
                "geometric list still contains " +
                allPasses.filter { inWindow(it.maxTimeMs) }.map { sdf.format(java.util.Date(it.maxTimeMs)) },
            allPasses.none { inWindow(it.maxTimeMs) }
        )
        assertTrue(
            "The filtered-out pass must not appear among visible passes either",
            visiblePasses.none { inWindow(it.maxTimeMs) }
        )

        // Measure the peak directly from the topocentric solver, so this test fails loudly if
        // the propagator ever drifts away from the reference instead of quietly listing a pass.
        var peakElevDeg = -90.0
        var peakMs = windowStartMs
        var t = windowStartMs
        while (t <= windowEndMs) {
            val elev = ISSEngine.calculateTopocentricPos(t, nurabadLat, nurabadLon, 940.0, liveTle).elevationDeg
            if (elev > peakElevDeg) {
                peakElevDeg = elev
                peakMs = t
            }
            t += 250L
        }
        println(
            "Independent geometric peak: ${sdf.format(java.util.Date(peakMs))} Iran, " +
                "${String.format(Locale.US, "%.4f", peakElevDeg)} deg (reference 9.3168 / 9.3170)"
        )

        assertEquals(
            "Nurabad 04:09 peak elevation must be 9.32 deg +/- 0.05 (references 9.3168-9.3170); was $peakElevDeg",
            9.32, peakElevDeg, 0.05
        )
        assertTrue(
            "Peak must sit just under the 9.5 deg filter at ISSEngine.kt:401, was $peakElevDeg",
            peakElevDeg < 9.5
        )
        assertTrue(
            "Peak time must be 04:09 Iran, was ${sdf.format(java.util.Date(peakMs))}",
            sdf.format(java.util.Date(peakMs)).startsWith("2026-08-21 04:0")
        )
    }
}
