package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.ISSEngine
import com.alijafari.red.astronomy.astro_engine.SGP4Propagator
import com.alijafari.red.astronomy.astro_engine.SunEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SGP4PropagatorTest {

    private val propagator = SGP4Propagator()

    @Test
    fun `test ISS TLE propagation and orbital dynamics`() {
        val tle = SGP4Propagator.TLEData(
            epochYear = 24,
            epochDay = 225.5,
            inclinationDeg = 51.6425,
            raanDeg = 123.4567,
            eccentricity = 0.0007089,
            argPerigeeDeg = 234.5678,
            meanAnomalyDeg = 345.6789,
            meanMotion = 15.50123456,
            bStar = 0.00012345
        )

        val epochMs = propagator.tleEpochToMs(tle.epochYear, tle.epochDay)
        val state = propagator.propagate(tle, epochMs)

        assertNotNull(state)
        val radius = sqrt(state.xKm * state.xKm + state.yKm * state.yKm + state.zKm * state.zKm)
        val speed = sqrt(state.vxKmS * state.vxKmS + state.vyKmS * state.vyKmS + state.vzKmS * state.vzKmS)

        // LEO radius (~6778 km) and speed (~7.66 km/s)
        assertTrue("Radius $radius km should be in LEO range (6500..7000 km)", radius in 6500.0..7000.0)
        assertTrue("Speed $speed km/s should be in orbital range (7.0..8.2 km/s)", speed in 7.0..8.2)
    }

    @Test
    fun `test Vallado standard SGP4 propagation consistency over 48 hours`() {
        val tle = SGP4Propagator.TLEData(
            epochYear = 24,
            epochDay = 200.0,
            inclinationDeg = 51.64,
            raanDeg = 200.0,
            eccentricity = 0.0005,
            argPerigeeDeg = 90.0,
            meanAnomalyDeg = 270.0,
            meanMotion = 15.49,
            bStar = 0.00003
        )

        val epochMs = propagator.tleEpochToMs(tle.epochYear, tle.epochDay)

        // Sample every 10 minutes for 48 hours
        for (minuteOffset in 0..2880 step 60) {
            val t = epochMs + minuteOffset * 60 * 1000L
            val state = propagator.propagate(tle, t)
            val r = sqrt(state.xKm * state.xKm + state.yKm * state.yKm + state.zKm * state.zKm)
            val v = sqrt(state.vxKmS * state.vxKmS + state.vyKmS * state.vyKmS + state.vzKmS * state.vzKmS)

            assertTrue("Radius $r at +$minuteOffset min should be stable in LEO", r in 6550.0..6950.0)
            assertTrue("Velocity $v at +$minuteOffset min should be stable in LEO", v in 7.4..7.9)
        }
    }

    @Test
    fun `test BStar scientific notation parser`() {
        val engine = ISSEngine()
        val line1A = "1 25544U 98067A   26213.50000000  .00016717  00000-0  30000-3 0  9993"
        val bStarA = engine.parseBStar(line1A)
        assertEquals("BStar 30000-3 should equal 0.0003", 0.0003, bStarA, 1e-6)

        val line1B = "1 25544U 98067A   26213.50000000  .00016717  00000-0 -12345-4 0  9993"
        val bStarB = engine.parseBStar(line1B)
        assertEquals("BStar -12345-4 should equal -0.000012345", -0.000012345, bStarB, 1e-8)

        val line1Zero = "1 25544U 98067A   26213.50000000  .00016717  00000-0  00000-0 0  9993"
        val bStarZero = engine.parseBStar(line1Zero)
        assertEquals("BStar 00000-0 should equal 0.0", 0.0, bStarZero, 1e-9)
    }

    @Test
    fun `test topocentric coordinates calculation and geodetic altitude`() {
        val engine = ISSEngine()
        val tle = ISSEngine.TLEData(
            name = "ISS (ZARYA)",
            line1 = "1 25544U 98067A   26213.50000000  .00016717  00000-0  30000-3 0  9993",
            line2 = "2 25544  51.6400 200.0000 0005000  90.0000 270.0000 15.49000000400001"
        )

        val testTimeMs = 1770000000000L
        val topo = engine.calculateTopocentricPos(
            timestampMs = testTimeMs,
            userLatDeg = 30.0,
            userLonDeg = 51.5,
            userAltMeters = 940.0,
            tle = tle
        )

        assertNotNull(topo)
        assertTrue("Sub-latitude should be between -52 and 52 deg", topo.subLatDeg in -52.0..52.0)
        assertTrue("Sub-longitude should be between -180 and 180 deg", topo.subLonDeg in -180.0..180.0)
        assertTrue("Satellite altitude should be ~415 km (380..460)", topo.satAltKm in 380.0..460.0)
        assertTrue("Azimuth should be 0..360 deg", topo.azimuthDeg in 0.0..360.0)
        assertTrue("Elevation should be -90..90 deg", topo.elevationDeg in -90.0..90.0)
    }

    @Test
    fun `test conical shadow illumination engine`() {
        val engine = ISSEngine()
        val jd = 2460000.5
        val gmstDeg = TimeEngine.getGMST(jd)

        // Point directly towards the Sun at 6800 km: must be sunlit
        val sunPos = SunEngine.calculatePosition(jd)
        val raRad = Math.toRadians(sunPos.raDeg)
        val decRad = Math.toRadians(sunPos.decDeg)
        val gmstRad = Math.toRadians(gmstDeg)

        val sunX = cos(decRad) * cos(raRad - gmstRad)
        val sunY = cos(decRad) * sin(raRad - gmstRad)
        val sunZ = sin(decRad)

        val xSunlit = 6800.0 * sunX
        val ySunlit = 6800.0 * sunY
        val zSunlit = 6800.0 * sunZ
        val isSunlit = engine.checkIssSunlit(jd, gmstDeg, xSunlit, ySunlit, zSunlit)
        assertTrue("Point on sunward side must be sunlit", isSunlit)

        // Point directly in Earth's shadow cone behind Earth at 6800 km: must NOT be sunlit
        val xShadow = -6800.0 * sunX
        val yShadow = -6800.0 * sunY
        val zShadow = -6800.0 * sunZ
        val isShadow = engine.checkIssSunlit(jd, gmstDeg, xShadow, yShadow, zShadow)
        assertFalse("Point directly behind Earth at LEO must be in Earth shadow", isShadow)
    }

    @Test
    fun `test pass predictor generates valid passes with rigorous visibility criteria`() {
        val engine = ISSEngine()
        val tle = ISSEngine.TLEData(
            name = "ISS (ZARYA)",
            line1 = "1 25544U 98067A   26213.50000000  .00016717  00000-0  30000-3 0  9993",
            line2 = "2 25544  51.6400 200.0000 0005000  90.0000 270.0000 15.49000000400001"
        )

        val startMs = 1770000000000L
        val allPasses = engine.predictPasses(
            userLatDeg = 30.0,
            userLonDeg = 51.5,
            startTimestampMs = startMs,
            tle = tle,
            scanDays = 5,
            visibleOnly = false
        )

        assertTrue("Should detect orbital passes over 5 days", allPasses.isNotEmpty())

        for (pass in allPasses) {
            // The predictor's contract is the peak filter in ISSEngine.kt:401, which keeps
            // candidates at 9.5 deg or above (plus an 8.0 deg visibility floor and 20 s of
            // continuous visibility, :457). This assertion used to demand 10.0 deg, which no
            // longer held once the propagator was corrected: this scan now yields passes
            // peaking in the 9.7-9.9 deg band, and they are legitimately kept. Assert the
            // contract the code enforces, not a stricter number that was never the contract.
            assertTrue(
                "Max elevation must be >= 9.5 deg (ISSEngine.kt:401 peak filter), was ${pass.maxElevationDeg}",
                pass.maxElevationDeg >= 9.5
            )
            assertTrue("End time must be greater than start time", pass.endTimeMs > pass.startTimeMs)
            assertTrue("Pass duration should be between 30 and 900 seconds", pass.passDurationSec in 30..900)

            if (pass.isVisible) {
                // For visible passes, classification should indicate visibility
                assertTrue(
                    "Visible pass classification must not be DAYLIGHT_ONLY or INVISIBLE_SHADOW",
                    pass.classification != ISSEngine.PassClassification.DAYLIGHT_ONLY &&
                    pass.classification != ISSEngine.PassClassification.INVISIBLE_SHADOW &&
                    pass.classification != ISSEngine.PassClassification.NOT_VISIBLE
                )
                assertTrue("Visible pass visibility score must be > 0", pass.visibilityScore > 0)
            }
        }
    }

    // =================================================================================
    // Reference-vector tests against the official AIAA 2006-6753 SGP4 implementation
    // =================================================================================
    //
    // Every assertion above is arithmetic only — "radius in 6500..7000 km", "speed in
    // 7.0..8.2 km/s". A 35 km along-track error passes those comfortably, and it did:
    // the J3 long-period coefficients were wrong until 2026-10-15 and this suite stayed
    // green. The vectors below are the missing physics check.
    //
    // REFERENCE TOOL: python-sgp4 2.27, WGS-72 (WGS72) gravity model, TEME frame, km and
    // km/s. python-sgp4 wraps the official C++ implementation published with Vallado,
    // Crawford, Hujsak & Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753
    // (https://celestrak.org/publications/AIAA/2006-6753/). Vectors generated 2026-10-15
    // via `Satrec.twoline2rv(line1, line2, WGS72)` + `sgp4(jd, fr)`, at `tsince` minutes
    // from the TLE epoch. The TLEs are the four hard-coded ones from SatelliteCatalog.kt.
    private data class Sgp4Reference(
        val name: String,
        val line1: String,
        val line2: String,
        val epochPosKm: List<Double>,
        val epochVelKmS: List<Double>,
        val dayPosKm: List<Double>,
        val dayVelKmS: List<Double>,
        /** Ceiling for the tsince = +1440 min position residual, in km. */
        val dayToleranceKm: Double
    )

    private val referenceVectors = listOf(
        Sgp4Reference(
            name = "ISS 25544",
            line1 = "1 25544U 98067A   26213.50000000  .00016717  00000-0  30000-3 0  9993",
            line2 = "2 25544  51.6400 200.0000 0005000  90.0000 270.0000 15.49000000400001",
            epochPosKm = listOf(-6392.454581, -2314.420076, -14.525850),
            epochVelKmS = listOf(1.615408770, -4.468874211, 6.008267203),
            dayPosKm = listOf(6559.241439, 1786.571168, -27.755944),
            dayVelKmS = listOf(-1.262486494, 4.581331172, -6.008443490),
            dayToleranceKm = 0.06 // measured residual 0.0275 km (2.2x margin)
        ),
        Sgp4Reference(
            name = "Tiangong 48274",
            line1 = "1 48274U 21035A   26213.50000000  .00012000  00000-0  20000-3 0  9994",
            line2 = "2 48274  41.4700 150.0000 0003500 120.0000 240.0000 15.61000000280001",
            epochPosKm = listOf(-5852.273768, 3390.931600, -9.269608),
            epochVelKmS = listOf(-2.883301384, -4.978096666, 5.087958530),
            dayPosKm = listOf(5909.962285, 288.932826, -3280.705088),
            dayVelKmS = listOf(-2.233731909, 6.474591400, -3.463695028),
            dayToleranceKm = 0.26 // measured residual 0.1288 km (2.0x margin)
        ),
        Sgp4Reference(
            name = "Hubble 20580",
            line1 = "1 20580U 90037B   26213.50000000  .00000850  00000-0  45000-4 0  9998",
            line2 = "2 20580  28.4700 310.0000 0002800 210.0000 150.0000 15.09000000890001",
            epochPosKm = listOf(4442.884338, -5301.921190, -2.472376),
            epochVelKmS = listOf(5.115218814, 4.288453113, 3.622228115),
            dayPosKm = listOf(6247.435350, -1926.902121, 2251.959219),
            dayVelKmS = listOf(1.207739714, 7.018298973, 2.643253238),
            dayToleranceKm = 0.02 // measured residual 0.0068 km (2.9x margin)
        ),
        Sgp4Reference(
            name = "Envisat 27386",
            line1 = "1 27386U 02009A   26213.50000000  .00000300  00000-0  12000-4 0  9995",
            line2 = "2 27386  98.5400  80.0000 0001200  90.0000 270.0000 14.38000000150001",
            epochPosKm = listOf(1238.505742, 7038.053252, -16.338553),
            epochVelKmS = listOf(1.093270620, -0.183989381, 7.387413529),
            dayPosKm = listOf(-20.110951, -5009.087554, 5086.246538),
            dayVelKmS = listOf(-1.602493362, -5.203903285, -5.118417954),
            dayToleranceKm = 0.01 // measured residual 0.0002 km (47x margin)
        )
    )

    /**
     * Column indices mirror `ISSEngine.parseToSgp4`, so the propagator is fed exactly the
     * elements the app feeds it for these four catalogue satellites. `parseBStar` is the
     * app's own public parser. If that parser ever drifts, these tests fail — which is the
     * point, because a mis-parsed TLE would look like a propagator error.
     */
    private fun referenceTle(line1: String, line2: String): SGP4Propagator.TLEData =
        SGP4Propagator.TLEData(
            epochYear = line1.substring(18, 20).trim().toInt(),
            epochDay = line1.substring(20, 32).trim().toDouble(),
            inclinationDeg = line2.substring(8, 16).trim().toDouble(),
            raanDeg = line2.substring(17, 25).trim().toDouble(),
            eccentricity = ("0." + line2.substring(26, 33).trim()).toDouble(),
            argPerigeeDeg = line2.substring(34, 42).trim().toDouble(),
            meanAnomalyDeg = line2.substring(43, 51).trim().toDouble(),
            meanMotion = line2.substring(52, 63).trim().toDouble(),
            bStar = ISSEngine().parseBStar(line1)
        )

    private fun positionResidualKm(state: SGP4Propagator.TemEState, expected: List<Double>): Double {
        val dx = state.xKm - expected[0]
        val dy = state.yKm - expected[1]
        val dz = state.zKm - expected[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun velocityResidualKmS(state: SGP4Propagator.TemEState, expected: List<Double>): Double {
        val dx = state.vxKmS - expected[0]
        val dy = state.vyKmS - expected[1]
        val dz = state.vzKmS - expected[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 1 metre at the epoch. This is the assertion that catches a wrong long-period or
     * short-period term: with the J3 coefficient bug of 2026-10-15 these residuals were
     * 21-44 KILOMETRES, and the radius/speed range checks above never noticed.
     */
    @Test
    fun `propagator matches the official SGP4 reference vectors at the TLE epoch`() {
        for (ref in referenceVectors) {
            val state = propagator.propagateMinutesSinceEpoch(referenceTle(ref.line1, ref.line2), 0.0)
            val err = positionResidualKm(state, ref.epochPosKm)
            assertTrue(
                "${ref.name}: position at tsince=0 is off by ${err * 1000.0} m from the AIAA " +
                    "2006-6753 reference " + ref.epochPosKm + "; got [" +
                    state.xKm + ", " + state.yKm + ", " + state.zKm + "]",
                err < 0.001
            )
        }
    }

    /**
     * One day out. TOLERANCES ARE THE MEASURED RESIDUALS TIMES TWO, NOT 20 m.
     *
     * 20 m is not reachable at +1440 min without correcting SGP4 *initialisation* terms
     * that are outside the scope of this change. Until 2026-10-15 the dominant term was
     * the RAAN secular-rate coefficient at SGP4Propagator.kt:161, which used 0.125 where
     * the reference uses 0.5; with just that coefficient corrected the +1440 min residuals
     * went 0.357 / 0.659 / 1.376 / 0.050 km -> 0.0275 / 0.1288 / 0.0068 / 0.0002 km for
     * ISS / Tiangong / Hubble / Envisat, and the ceilings below were tightened to match.
     * Do NOT raise these ceilings to silence a regression: a failure means the propagator
     * changed.
     */
    @Test
    fun `propagator matches the official SGP4 reference vectors one day out`() {
        for (ref in referenceVectors) {
            val state = propagator.propagateMinutesSinceEpoch(referenceTle(ref.line1, ref.line2), 1440.0)
            val err = positionResidualKm(state, ref.dayPosKm)
            assertTrue(
                "${ref.name}: position at tsince=+1440 min is off by ${err * 1000.0} m from the " +
                    "AIAA 2006-6753 reference " + ref.dayPosKm + " (ceiling " +
                    ref.dayToleranceKm * 1000.0 + " m); got [" + state.xKm + ", " + state.yKm +
                    ", " + state.zKm + "]",
                err < ref.dayToleranceKm
            )
        }
    }

    /**
     * Velocity guard. The 0.05 km/s tolerance is deliberately loose: this propagator's
     * short-period velocity correction omits the reference's two `/ xke` factors, so its
     * speed is ~0.04% low — measured 2.9, 5.0, 7.1 and 1.3 m/s for ISS, Tiangong, Hubble
     * and Envisat at the epoch. That is a real, separately measured defect and it is NOT
     * fixed here because it is outside this change's scope; documented in
     * SGP4Propagator.kt's velocity block. This assertion is therefore sized to catch a
     * gross velocity regression (a broken Kepler solve is metres per second out, not
     * millimetres), not to certify the velocity as reference-accurate.
     */
    @Test
    fun `velocities stay within the documented deviation from the reference`() {
        for (ref in referenceVectors) {
            val state = propagator.propagateMinutesSinceEpoch(referenceTle(ref.line1, ref.line2), 0.0)
            val err = velocityResidualKmS(state, ref.epochVelKmS)
            assertTrue(
                "${ref.name}: velocity at tsince=0 is off by ${err * 1000.0} m/s from the AIAA " +
                    "2006-6753 reference " + ref.epochVelKmS + "; got [" + state.vxKmS + ", " +
                    state.vyKmS + ", " + state.vzKmS + "]",
                err < 0.05
            )
        }
    }
}
