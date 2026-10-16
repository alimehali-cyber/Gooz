package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.HumanVision
import com.alijafari.red.astronomy.ar.core.LunarPhotometry
import com.alijafari.red.astronomy.ar.core.MoonLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Photometry of the Moon and of earthshine, and their visibility, against the published relations:
 * Krisciunas & Schaefer 1991 (PASP 103, 1033), the Big Bear earthshine programme (Qiu et al. 2003;
 * Pallé et al. 2003, 2016), the Lommel–Seeliger law, Crumey 2014 (MNRAS 442, 2600) with Blackwell's
 * 1946 thresholds, CIE 146:2002 and Thejll et al. 2014 (A&A 563, A38).
 */
class LunarAppearanceTest {

    private val d = LunarPhotometry.MEAN_DISTANCE_KM

    /** Disc integral of the Lommel–Seeliger law 2μ₀/(μ₀+μ) for a uniform sphere at phase angle α. */
    private fun lommelSeeligerNumeric(alphaDeg: Double, n: Int = 500): Double {
        fun integral(a: Double): Double {
            val sx = sin(a); val sz = cos(a)
            var sum = 0.0
            for (j in 0 until n) for (i in 0 until n) {
                val x = -1 + (i + 0.5) * 2.0 / n; val y = -1 + (j + 0.5) * 2.0 / n
                val r2 = x * x + y * y
                if (r2 >= 1.0) continue
                val z = sqrt(1 - r2)
                val mu0 = x * sx + z * sz
                if (mu0 > 0) sum += 2 * mu0 / (mu0 + z)
            }
            return sum
        }
        return integral(Math.toRadians(alphaDeg)) / integral(0.0)
    }

    @Test
    fun `full Moon illuminance and luminance follow Krisciunas and Schaefer`() {
        // K&S eq. 8 and 9 at the mean distance: 10^(-0.4(-12.73 + 16.57)) fc = 0.3133 lx.
        val e = LunarPhotometry.illuminanceLux(LunarPhotometry.magnitudeV(0.0, d))
        assertEquals(10.7639 * 10.0.pow(-0.4 * (16.57 - 12.73)), e, 1e-9)
        assertEquals(0.3133, e, 5e-4)
        // Mean surface luminance = E / Ω with Ω the disc's solid angle.
        assertEquals(e / LunarPhotometry.discSolidAngle(d), LunarPhotometry.fullDiscLuminance(d), 1e-6)
        assertEquals(4882.0, LunarPhotometry.fullDiscLuminance(d), 5.0)
        // Inverse-square: at perigee (356 500 km) the full Moon is 0.16 mag brighter.
        assertEquals(5 * log10(356_500.0 / d), LunarPhotometry.magnitudeV(0.0, 356_500.0) + 12.73, 1e-12)
        // K&S phase law: the quarter Moon is 2.60 mag fainter than full.
        assertEquals(0.026 * 90 + 4e-9 * 90.0.pow(4), LunarPhotometry.phaseMagnitudeDrop(90.0), 1e-12)
    }

    @Test
    fun `Lommel-Seeliger disc integral matches the closed form`() {
        for (a in listOf(0.0, 20.0, 45.0, 90.0, 120.0, 150.0, 165.0)) {
            assertEquals("α=$a", lommelSeeligerNumeric(a), LunarPhotometry.lommelSeeligerPhase(Math.toRadians(a)), 2e-3)
        }
    }

    @Test
    fun `shaded surface reproduces the observed lunar phase curve`() {
        // The renderer's sunlit radiance is L_full · 2μ₀/(μ₀+μ) · f(α): its disc integral must give the K&S curve.
        for (a in listOf(10.0, 45.0, 90.0, 120.0, 150.0)) {
            val integrated = lommelSeeligerNumeric(a) * LunarPhotometry.surfacePhaseCorrection(a)
            val ks = 10.0.pow(-0.4 * LunarPhotometry.phaseMagnitudeDrop(a))
            assertEquals("α=$a", ks, integrated, ks * 6e-3)
            // And the sunlit part's mean luminance is that light spread over the illuminated fraction.
            val k = LunarPhotometry.illuminatedFraction(a)
            assertEquals(LunarPhotometry.fullDiscLuminance(d) * ks / k, LunarPhotometry.litMeanLuminance(a, d), 1e-6)
        }
    }

    @Test
    fun `earthshine follows the Big Bear earthshine relation`() {
        val r = LunarPhotometry.EARTH_RADIUS_KM / d
        // New Moon (full Earth, β = 0): (2/3) p* (R_E/R_EM)².
        assertEquals(2.0 / 3.0 * 0.30 * r * r, LunarPhotometry.earthshineRatio(180.0, d), 1e-12)
        // Lambert phase function of the Earth: 1/π at quadrature, 0 when the Earth is new.
        assertEquals(1.0 / PI, LunarPhotometry.earthLambertPhase(PI / 2), 1e-12)
        assertEquals(0.0, LunarPhotometry.earthLambertPhase(PI), 1e-12)
        assertEquals(0.0, LunarPhotometry.earthshineRatio(0.0, d), 1e-15)
        // A 150° crescent: earthshine ≈ 0.236 cd/m² (14.15 V mag/arcsec²), ~1/1300 of the sunlit crescent.
        val es = LunarPhotometry.earthshineLuminance(150.0, d)
        assertEquals(0.236, es, 0.003)
        assertEquals(14.15, 12.58 - 2.5 * log10(es), 0.02)
        val ratio = es / LunarPhotometry.litMeanLuminance(150.0, d)
        assertTrue("earthshine/crescent = $ratio", ratio in 6e-4..9e-4)
        // Earthshine brightens towards new Moon.
        assertTrue(LunarPhotometry.earthshineLuminance(160.0, d) > LunarPhotometry.earthshineLuminance(120.0, d))
        assertTrue(LunarPhotometry.earthshineLuminance(120.0, d) > LunarPhotometry.earthshineLuminance(60.0, d))
    }

    @Test
    fun `earthshine is 0_15 mag bluer in B-V than moonshine`() {
        val t = MoonLook.EARTHSHINE_TINT
        // Luminance-neutral.
        assertEquals(1.0, 0.2126 * t[0] + 0.7152 * t[1] + 0.0722 * t[2], 1e-12)
        // The tilt is −0.4 · 0.150 mag over 550 → 440 nm, sampled at 611 / 549 / 464 nm.
        val perNm = 0.4 * 0.150 / 110.0
        assertEquals(perNm * (611.0 - 464.0), log10(t[2] / t[0]), 1e-12)
        assertTrue(t[2] > t[1] && t[1] > t[0])
    }

    @Test
    fun `Crumey threshold model reproduces Blackwell's data`() {
        // (target diameter arcmin, log B in cd/m², log C from Blackwell 1946 table 8 as plotted by Crumey 2014 fig. 6)
        val cases = listOf(
            Triple(18.2, -1.4, -1.3), Triple(18.2, -4.3, 0.5), Triple(3.6, 0.4, -1.3),
            Triple(3.6, -2.6, 0.7), Triple(0.595, 1.6, -0.1), Triple(0.595, -3.6, 2.8)
        )
        for ((diameter, logB, logC) in cases) {
            val rad = Math.toRadians(diameter / 120.0)
            val c = HumanVision.thresholdContrast(10.0.pow(logB), PI * rad * rad, fieldFactor = 1.0)
            assertEquals("${diameter}′ at log B = $logB", logC, log10(c), 0.3)
        }
        // Large targets in daylight obey Weber's law at C∞ ≈ k₄ = 2.72e-3 (eq. 38).
        val cInf = HumanVision.thresholdContrast(3000.0, 1e-2, fieldFactor = 1.0)
        assertEquals(2.72e-3, cInf, 3e-4)
        // Thresholds rise as the background darkens.
        assertTrue(HumanVision.thresholdContrast(1e-3, 6e-5) > HumanVision.thresholdContrast(1.0, 6e-5))
    }

    @Test
    fun `CIE 146 general disability glare equation`() {
        // θ = 1°, age 25, p = 0.5: 10 + (5 + 0.05)(1 + 0.4⁴) + 0.00125.
        assertEquals(10.0 + 5.05 * (1 + 0.4.pow(4)) + 0.00125, HumanVision.glareSpread(1.0), 1e-9)
        // Validity domain 0.1°–100°: clamped outside.
        assertEquals(HumanVision.glareSpread(0.1), HumanVision.glareSpread(0.01), 0.0)
        assertTrue(HumanVision.glareSpread(0.2) > HumanVision.glareSpread(0.5))
    }

    @Test
    fun `earthshine is seen at night and in late twilight but not in daylight`() {
        val omega = LunarPhotometry.discSolidAngle(d)
        fun earthshineStrength(phase: Double, sky: Double): Double {
            val es = LunarPhotometry.earthshineLuminance(phase, d) * 0.8
            val dark = (1 - LunarPhotometry.illuminatedFraction(phase)) * omega
            return HumanVision.visibilityStrength(es, sky, dark) * HumanVision.luminanceShare(es, sky)
        }
        // 150° crescent: dark rural night, nautical twilight, the end of civil twilight, sunset, daylight.
        assertTrue(earthshineStrength(150.0, 3e-4) > 0.99)
        assertTrue(earthshineStrength(150.0, 0.1) in 0.5..0.8)
        assertTrue(earthshineStrength(150.0, 3.0) in 0.01..0.1)
        assertEquals(0.0, earthshineStrength(150.0, 300.0), 0.0)
        assertEquals(0.0, earthshineStrength(150.0, 5000.0), 0.0)
        // Fainter earthshine at quarter needs a darker sky than a crescent's.
        assertTrue(earthshineStrength(90.0, 3.0) < earthshineStrength(150.0, 3.0))
    }

    @Test
    fun `daytime Moon is pale and a thin crescent near the Sun is all but invisible`() {
        val omega = LunarPhotometry.discSolidAngle(d)
        fun litWeight(phase: Double, sky: Double): Double {
            val lit = LunarPhotometry.litMeanLuminance(phase, d) * 0.8
            val area = LunarPhotometry.illuminatedFraction(phase) * omega
            return HumanVision.visibilityStrength(lit, sky, area) * HumanVision.luminanceShare(lit, sky)
        }
        assertTrue(litWeight(0.0, 3e-3) > 0.999)       // night
        assertTrue(litWeight(0.0, 3000.0) in 0.4..0.7) // full Moon by day
        assertTrue(litWeight(90.0, 3000.0) in 0.1..0.3) // quarter Moon by day
        assertTrue(litWeight(150.0, 8000.0) < 0.03)     // two-day crescent by day
    }

    @Test
    fun `night side display level is the Home card's for a thin crescent and fades towards quarter`() {
        assertEquals(MoonLook.NIGHT_SIDE_ENCODED_LEVEL, MoonLook.nightSideLevel(150.0, d, 1.0), 1e-9)
        assertTrue(MoonLook.nightSideLevel(120.0, d, 1.0) < MoonLook.nightSideLevel(150.0, d, 1.0))
        assertTrue(MoonLook.nightSideLevel(90.0, d, 1.0) < MoonLook.nightSideLevel(120.0, d, 1.0))
        assertEquals(0.0, MoonLook.nightSideLevel(0.0, d, 1.0), 1e-6)
    }
}
