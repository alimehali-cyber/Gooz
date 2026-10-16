package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.SkyAtmosphere
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.tan

/**
 * The physically based sky (spectral Rayleigh + Mie + ozone single/multiple scattering LUTs) and the
 * dark-sky model, checked against published reference values.
 */
class SkyAtmosphereTest {

    private val atm = SkyAtmosphere()
    private val out = DoubleArray(3)

    private fun zenithLuminance(lut: FloatArray): Double {
        val v = SkyAtmosphere.elevationToV(Math.toRadians(89.9)) * SkyAtmosphere.SKY_LUT_H - 0.5
        SkyAtmosphere.bilinear(lut, SkyAtmosphere.SKY_LUT_W, SkyAtmosphere.SKY_LUT_H, 0.0, v, out)
        return SkyRenderModel.luminance(out[0], out[1], out[2])
    }

    @Test
    fun `zenith extinction matches the sea-level standard and reddens the light`() {
        val t = DoubleArray(3)
        atm.extinctionRgb(PI / 2, 0.0, t)
        val kV = -2.5 * log10(t[1])
        // Clear sea-level sites: k_V ≈ 0.15–0.25 mag/airmass (Rayleigh 0.11 + ozone 0.02 + aerosol).
        assertTrue("k_V = $kV", kV in 0.15..0.25)
        assertTrue("blue is extinguished more than red", t[2] < t[1] && t[1] < t[0])
        // Higher sites see less extinction.
        val high = DoubleArray(3)
        atm.extinctionRgb(PI / 2, 2.5, high)
        assertTrue(high[1] > t[1])
    }

    @Test
    fun `daytime zenith luminance lies in the CIE clear-sky band`() {
        // Krisch's clear-sky zenith luminance (as used with the CIE standard clear sky):
        // Lz = (1.376 T − 1.81) tan h + 0.38 kcd/m², for Linke turbidity T between 2 and 3.5.
        for (h in listOf(30.0, 45.0, 60.0)) {
            val lz = zenithLuminance(atm.buildSkyViewLut(Math.toRadians(h), 0.1))
            val lo = ((1.376 * 2.0 - 1.81) * tan(Math.toRadians(h)) + 0.38) * 1000.0
            val hi = ((1.376 * 3.5 - 1.81) * tan(Math.toRadians(h)) + 0.38) * 1000.0
            assertTrue("sun $h°: Lz=$lz cd/m² not in [$lo, $hi]", lz in lo..hi)
        }
        val lut = atm.buildSkyViewLut(Math.toRadians(45.0), 0.1)
        val v = SkyAtmosphere.elevationToV(Math.toRadians(89.9)) * SkyAtmosphere.SKY_LUT_H - 0.5
        SkyAtmosphere.bilinear(lut, SkyAtmosphere.SKY_LUT_W, SkyAtmosphere.SKY_LUT_H, 0.0, v, out)
        assertTrue("the clear daytime zenith is blue", out[2] > out[1] && out[1] > out[0])
    }

    @Test
    fun `twilight darkens monotonically through the standard twilight brightnesses`() {
        var previous = Double.MAX_VALUE
        val mags = HashMap<Int, Double>()
        for (h in listOf(10, 0, -3, -6, -9, -12, -15, -18)) {
            val lz = zenithLuminance(atm.buildSkyViewLut(Math.toRadians(h.toDouble()), 0.1))
            assertTrue("zenith must darken as the Sun sets (h=$h)", lz < previous)
            previous = lz
            mags[h] = SkyRenderModel.luminanceToMagArcsec2(lz)
        }
        // Zenith V brightness in twilight (e.g. Patat et al. 2006, A&A 455, 385): ≈ 11–13 mag/arcsec² at
        // the end of civil twilight and ≈ 17.5–19.5 at the end of nautical twilight.
        assertTrue("−6°: ${mags[-6]}", mags[-6]!! in 11.0..13.5)
        assertTrue("−12°: ${mags[-12]}", mags[-12]!! in 17.0..19.8)
        // At astronomical night scattered sunlight is far below the natural sky (≈ 21.9).
        assertTrue("−18°: ${mags[-18]}", mags[-18]!! > 23.5)
    }

    @Test
    fun `dark sky follows Krisciunas-Schaefer and the Bortle zenith brightness`() {
        val tz = 0.83
        for (b in 1..9) {
            val zenith = SkyRenderModel.luminanceToMagArcsec2(SkyRenderModel.nightBackgroundLuminance(90.0, b, tz))
            val expected = minOf(SkyRenderModel.bortleZenithMagArcsec2(b), SkyRenderModel.NATURAL_ZENITH_MAG_ARCSEC2)
            assertEquals("Bortle $b zenith", expected, zenith, 0.005)
        }
        // Natural sky towards the horizon: brighter by the airglow path, X·10^(−0.4k(X−1)).
        val z30 = SkyRenderModel.nightBackgroundLuminance(30.0, 1, tz) / SkyRenderModel.nightBackgroundLuminance(90.0, 1, tz)
        val x = 1.0 / kotlin.math.sqrt(1.0 - 0.96 * 0.75)
        assertEquals(x * Math.pow(tz, x - 1.0), z30, 1e-9)
        assertTrue(z30 > 1.0)
    }

    @Test
    fun `limiting magnitude follows Schaefer`() {
        // Schaefer (1990): ≈ 6.5 mag under a 21.8 mag/arcsec² sky, ≈ 4 under 18 mag/arcsec².
        assertEquals(6.53, SkyRenderModel.limitingMagnitude(21.8), 0.05)
        assertEquals(3.97, SkyRenderModel.limitingMagnitude(18.0), 0.05)
        assertTrue(abs(SkyRenderModel.limitingMagnitude(22.0) - SkyRenderModel.limitingMagnitude(21.0)) < 1.0)
    }
}
