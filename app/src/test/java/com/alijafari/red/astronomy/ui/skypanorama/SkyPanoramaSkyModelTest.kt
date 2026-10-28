package com.alijafari.red.astronomy.ui.skypanorama

import com.alijafari.red.astronomy.ui.rendering.RealSkyPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Time-of-day transition tests. Pure JVM: no Compose, no GL. */
class SkyPanoramaSkyModelTest {

    // ---- Night weight: day, twilight and night boundaries -------------------------------------

    @Test
    fun daytimeAndSunsetAboveCivilTwilightShowNoPanorama() {
        for (alt in listOf(90.0, 35.0, 14.0, 5.0, 0.0, -3.0, -5.99, -6.0)) {
            assertEquals("sunAlt=$alt", 0f, SkyPanoramaSkyModel.nightWeight(alt), 0f)
        }
    }

    @Test
    fun astronomicalNightShowsOnlyThePanorama() {
        for (alt in listOf(-18.0, -18.5, -25.0, -90.0)) {
            assertEquals("sunAlt=$alt", 1f, SkyPanoramaSkyModel.nightWeight(alt), 0f)
        }
    }

    @Test
    fun twilightMidpointIsHalfBlended() {
        // Midpoint of the -6 .. -18 window is -12: smoothstep(0.5) == 0.5 exactly.
        assertEquals(0.5f, SkyPanoramaSkyModel.nightWeight(-12.0), 1e-6f)
    }

    @Test
    fun nanSunAltitudeIsTreatedAsNight() {
        assertEquals(1f, SkyPanoramaSkyModel.nightWeight(Double.NaN), 0f)
    }

    @Test
    fun nightWeightIsMonotonicNonIncreasingWithSunAltitude() {
        var previous = Float.MAX_VALUE
        var alt = -30.0
        while (alt <= 40.0) {
            val w = SkyPanoramaSkyModel.nightWeight(alt)
            assertTrue("weight rose at $alt", w <= previous + 1e-7f)
            assertTrue("weight out of range at $alt", w in 0f..1f)
            previous = w
            alt += 0.01
        }
    }

    @Test
    fun nightWeightHasNoJumpsAndZeroSlopeAtBothEnds() {
        var previous = SkyPanoramaSkyModel.nightWeight(-30.0)
        var maxStep = 0f
        var alt = -30.0
        while (alt <= 40.0) {
            val w = SkyPanoramaSkyModel.nightWeight(alt)
            maxStep = maxOf(maxStep, abs(w - previous))
            previous = w
            alt += 0.01
        }
        // Smoothstep max slope is 1.5 per unit of t, i.e. 1.5 / 12 per degree -> 0.00125 per 0.01 deg.
        assertTrue("largest per-0.01deg step $maxStep", maxStep < 0.0015f)

        // Flat at both ends: essentially no change within 0.1 deg of the boundaries.
        // Smoothstep: 3t^2 near the edge, so 0.1 deg away from -6 the weight is about 2e-4.
        assertTrue(SkyPanoramaSkyModel.nightWeight(-6.1) < 3e-4f)
        assertTrue(abs(SkyPanoramaSkyModel.nightWeight(-17.9) - 1f) < 1e-3f)
    }

    // ---- Palette: same existing Real Sky values, transitions and daytime colour ---------------

    @Test
    fun daytimePaletteIsTheExistingBlueSkyAtAndAboveThirtyFiveDegrees() {
        val g = RealSkyPalette.gradientAt(60.0, 0f)
        assertEquals(0x0E / 255f, g.zenith.r, 1e-6f)
        assertEquals(0x2B / 255f, g.zenith.g, 1e-6f)
        assertEquals(0x63 / 255f, g.zenith.b, 1e-6f)
        assertEquals(0xE6 / 255f, g.horizon.b, 1e-6f)
        // Blue dominates at zenith and at the horizon in daylight.
        assertTrue(g.zenith.b > g.zenith.r && g.horizon.b > g.horizon.r)
    }

    @Test
    fun paletteAtAnchorsIsExactAndBelowAstronomicalNightIsTheLastAnchor() {
        val atZero = RealSkyPalette.gradientAt(0.0, 0f)
        assertEquals(0x8C / 255f, atZero.horizon.r, 1e-6f) // sunrise/sunset horizon glow
        assertEquals(0x1E / 255f, atZero.mid.r, 1e-6f)
        val deepNight = RealSkyPalette.gradientAt(-40.0, 0f)
        val atMinus18 = RealSkyPalette.gradientAt(-18.0, 0f)
        assertEquals(atMinus18, deepNight)
    }

    @Test
    fun paletteInterpolatesLinearlyBetweenAnchors() {
        // Midway between anchors +15 and +5 is +10: exact average.
        val g = RealSkyPalette.gradientAt(10.0, 0f)
        val a = RealSkyPalette.gradientAt(15.0, 0f).zenith
        val b = RealSkyPalette.gradientAt(5.0, 0f).zenith
        assertEquals((a.r + b.r) / 2f, g.zenith.r, 1e-6f)
        assertEquals((a.g + b.g) / 2f, g.zenith.g, 1e-6f)
        assertEquals((a.b + b.b) / 2f, g.zenith.b, 1e-6f)
    }

    @Test
    fun paletteChangesSmoothlyAcrossSunriseAndSunset() {
        var previous = RealSkyPalette.gradientAt(-20.0, 0f)
        var maxStep = 0f
        var alt = -20.0
        while (alt <= 40.0) {
            val g = RealSkyPalette.gradientAt(alt, 0f)
            maxStep = maxOf(maxStep, channelStep(previous, g))
            previous = g
            alt += 0.05
        }
        assertTrue("largest palette step per 0.05 deg: $maxStep", maxStep < 0.01f)
    }

    @Test
    fun moonWashOnlyAppliesToDarkNightsWithAMoon() {
        val dark = RealSkyPalette.gradientAt(-12.0, 0f)
        val moonDark = RealSkyPalette.gradientAt(-12.0, 1f)
        assertTrue("moon should brighten a dark night", moonDark.zenith.b > dark.zenith.b)
        // Above -6 deg there is no wash, whatever the moon.
        assertEquals(RealSkyPalette.gradientAt(-3.0, 0f), RealSkyPalette.gradientAt(-3.0, 1f))
        // Tiny moon glow below the existing 0.02 gate is ignored.
        assertEquals(dark, RealSkyPalette.gradientAt(-12.0, 0.01f))
    }

    // ---- Sun glow dome: legacy values preserved, panorama mode has no hard edge ---------------

    @Test
    fun legacyDomeKeepsItsExistingHardCutoffAtFourteenDegrees() {
        assertTrue(RealSkyPalette.twilightDome(14.0, extendPastHorizonGlow = false) != null)
        assertTrue(RealSkyPalette.twilightDome(14.0001, extendPastHorizonGlow = false) == null)
        assertTrue(RealSkyPalette.twilightDome(-18.0, extendPastHorizonGlow = false) == null)
    }

    @Test
    fun panoramaDomeFadesOutInsteadOfCuttingOffAtFourteenDegrees() {
        val atEdge = RealSkyPalette.twilightDome(14.0, true)!!.second
        val justAbove = RealSkyPalette.twilightDome(14.05, true)!!.second
        assertEquals(0.18f, atEdge, 1e-6f)
        assertTrue("step at 14 deg: ${atEdge - justAbove}", abs(atEdge - justAbove) < 0.01f)
        assertTrue(RealSkyPalette.twilightDome(35.0, true) == null)
        val mid = RealSkyPalette.twilightDome(25.0, true)!!.second
        assertTrue("mid fade $mid", mid > 0f && mid < 0.18f)
    }

    @Test
    fun domeIsNotDrawnAtOrBelowAstronomicalNight() {
        assertTrue(RealSkyPalette.twilightDome(-18.0, true) == null)
        assertTrue(RealSkyPalette.twilightDome(-25.0, true) == null)
    }

    // ---- Derived sky and renderer-state quantization ------------------------------------------

    @Test
    fun skyForDaytimeUsesDaylightWeightAndBluePalette() {
        val sky = SkyPanoramaSkyModel.skyFor(40.0, 0f)
        assertEquals(0f, sky.nightWeight, 0f)
        assertEquals(RealSkyPalette.gradientAt(40.0, 0f), sky.gradient)
    }

    @Test
    fun stateQuantizesSunAltitudeSoSubQuantumChangesDoNotRedraw() {
        val a = SkyPanoramaState.fromSkyState(100.0, 52.0, 10.01, 0f)
        val b = SkyPanoramaState.fromSkyState(100.0, 52.0, 10.00, 0f)
        val c = SkyPanoramaState.fromSkyState(100.0, 52.0, 10.20, 0f)
        assertEquals(a, b)
        assertTrue("0.2 deg change must redraw", a != c)
    }

    @Test
    fun stateQuantizesMoonGlowAndDerivesTheNightWeight() {
        val s = SkyPanoramaState.fromSkyState(100.0, 52.0, -20.0, 0.004f)
        assertEquals(1f, s.sky.nightWeight, 0f)
        assertEquals(0f, s.moonGlow, 0f)
        val twilight = SkyPanoramaState.fromSkyState(100.0, 52.0, -12.0, 0.5f)
        assertEquals(0.5f, twilight.sky.nightWeight, 1e-6f)
        assertEquals(0.5f, twilight.moonGlow, 1e-6f)
    }

    private fun channelStep(a: RealSkyPalette.Gradient, b: RealSkyPalette.Gradient): Float {
        val pairs = listOf(
            a.zenith to b.zenith, a.mid to b.mid, a.horizon to b.horizon
        )
        var m = 0f
        for ((p, q) in pairs) {
            m = maxOf(m, abs(p.r - q.r), abs(p.g - q.g), abs(p.b - q.b))
        }
        return m
    }
}
