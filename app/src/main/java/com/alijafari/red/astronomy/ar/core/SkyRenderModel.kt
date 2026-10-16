package com.alijafari.red.astronomy.ar.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Photometry and display mapping shared by the GL sky (camera off) and the Canvas star/planet glyphs,
 * so that both are exposed by the same "eye": a star fades into the twilight exactly where the
 * rendered sky becomes too bright for it.
 *
 * Units: luminance in cd/m²; sky surface brightness in V mag/arcsec² (b = −2.5 log10(L / 108 000)).
 */
object SkyRenderModel {

    /** cd/m² of a surface of 0 V mag per square arcsecond (Crumey 2014 conversion). */
    const val MAG_ARCSEC2_ZERO_POINT = 108_000.0

    fun luminanceToMagArcsec2(l: Double): Double = -2.5 * log10(max(l, 1e-12) / MAG_ARCSEC2_ZERO_POINT)

    fun magArcsec2ToLuminance(b: Double): Double = MAG_ARCSEC2_ZERO_POINT * 10.0.pow(-0.4 * b)

    /** Photopic luminance weights for linear sRGB. */
    fun luminance(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

    /**
     * Naked-eye limiting magnitude for a point source on a background of [skyMagArcsec2]
     * (Schaefer 1990, PASP 102, 212: m = 7.93 − 5·log10(10^(4.316 − b/5) + 1)).
     */
    fun limitingMagnitude(skyMagArcsec2: Double): Double =
        7.93 - 5.0 * log10(10.0.pow(4.316 - skyMagArcsec2 / 5.0) + 1.0)

    /** Zenith sky brightness (V mag/arcsec²) for a Bortle class 1..9 (natural sky ≈ 21.9). */
    fun bortleZenithMagArcsec2(bortle: Int): Double = when (bortle.coerceIn(1, 9)) {
        1 -> 21.95
        2 -> 21.8
        3 -> 21.5
        4 -> 20.9
        5 -> 20.1
        6 -> 19.3
        7 -> 18.7
        8 -> 18.2
        else -> 17.7
    }

    /**
     * Dark-sky (no Sun, no Moon) background luminance at an apparent altitude, in cd/m²:
     * airglow brightening towards the horizon by the van Rhijn factor (emission layer at 90 km),
     * attenuated by extinction, plus artificial light pollution growing towards the horizon.
     */
    fun nightBackgroundLuminance(altDeg: Double, bortle: Int, extinctionV: Double): Double {
        val natural = magArcsec2ToLuminance(21.95)
        val zenithTotal = magArcsec2ToLuminance(bortleZenithMagArcsec2(bortle))
        val artificial = max(0.0, zenithTotal - natural)
        val alt = max(altDeg, 0.5)
        val z = Math.toRadians(90.0 - alt)
        val earthR = 6371.0; val layer = 90.0
        val s = earthR / (earthR + layer) * sin(z)
        val vanRhijn = 1.0 / sqrt(max(1e-3, 1.0 - s * s))
        val airglow = natural * vanRhijn * extinctionV.coerceIn(0.2, 1.0)
        // Light domes concentrate within ~20° of the horizon.
        val dome = artificial * (1.0 + 4.0 * exp(-alt / 9.0))
        return airglow + dome
    }

    /**
     * Surface brightness assigned to the linear value 1.0 of the NASA SVS panorama. Only the saturated
     * core pixels reach it; after [MILKY_WAY_GAMMA] the Sagittarius/Scutum star clouds (linear ≈ 0.2–0.4)
     * land near 20 V mag/arcsec² and the faint arms near 21.5–22, matching visual observations.
     */
    val MILKY_WAY_PEAK_LUMINANCE: Double = magArcsec2ToLuminance(18.6)

    /**
     * The NASA SVS "Deep Star Maps 2020" Milky Way layer has a larger dynamic range than the visual
     * surface brightness of the Galaxy (≈ 20–22 mag/arcsec²); a 0.75 power compresses its linear values
     * onto that range while keeping the dust lanes (Great Rift) dark.
     */
    const val MILKY_WAY_GAMMA = 0.75

    fun milkyWayLuminance(linearTexel: Double): Double =
        MILKY_WAY_PEAK_LUMINANCE * max(0.0, linearTexel).pow(MILKY_WAY_GAMMA)

    /**
     * Fraction of full adaptation brightness used as the display key: a dark-adapted night sky is
     * perceived as dark even though the eye has adapted, unlike a daytime sky.
     */
    fun displayKey(adaptLuminance: Double): Double {
        val t = ((log10(max(adaptLuminance, 1e-6)) + 4.0) / 7.5).coerceIn(0.0, 1.0)
        val s = t * t * (3.0 - 2.0 * t)
        return 0.30 * (0.06 + 0.94 * s.pow(1.6))
    }

    /** Exposure multiplier for an adaptation luminance (cd/m²). */
    fun exposure(adaptLuminance: Double): Double = displayKey(adaptLuminance) / max(adaptLuminance, 1e-6)

    /** Weight (0..1) of rod (scotopic) vision for an adaptation luminance. */
    fun scotopicWeight(adaptLuminance: Double): Double {
        val t = ((log10(max(adaptLuminance, 1e-6)) + 3.0) / 3.0).coerceIn(0.0, 1.0) // 1e-3 → 1, 1 → 0
        val s = t * t * (3.0 - 2.0 * t)
        return 1.0 - s
    }

    /** ACES filmic fit (Narkowicz 2015). */
    fun aces(x: Double): Double {
        val v = max(0.0, x)
        return ((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14)).coerceIn(0.0, 1.0)
    }

    fun linearToSrgb(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }

    fun srgbToLinear(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    /**
     * Maps linear scene radiance (cd/m² per channel) to display sRGB [0, 1].
     * Mirrors the GLSL in the AR sky shader exactly.
     */
    fun toneMap(r: Double, g: Double, b: Double, adaptLuminance: Double, out: DoubleArray) {
        val e = exposure(adaptLuminance)
        var cr = max(0.0, r) * e; var cg = max(0.0, g) * e; var cb = max(0.0, b) * e
        // Purkinje shift: rods see a desaturated, slightly blue world at low light.
        val w = scotopicWeight(adaptLuminance)
        if (w > 0.0) {
            val scot = 0.36 * cg + 0.62 * cb + 0.02 * cr
            cr = cr * (1 - w) + scot * 0.62 * w
            cg = cg * (1 - w) + scot * 0.80 * w
            cb = cb * (1 - w) + scot * 1.18 * w
        }
        // Filmic curve on luminance only, so glows keep their hue (no clipping to white per channel).
        val y = luminance(cr, cg, cb)
        if (y <= 1e-12) {
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
            return
        }
        val yd = aces(y)
        val k = yd / y
        cr *= k; cg *= k; cb *= k
        // Out-of-gamut: desaturate towards the mapped luminance just enough to fit.
        val m = max(cr, max(cg, cb))
        if (m > 1.0) {
            val sat = ((1.0 - yd) / max(m - yd, 1e-9)).coerceIn(0.0, 1.0)
            cr = yd + (cr - yd) * sat; cg = yd + (cg - yd) * sat; cb = yd + (cb - yd) * sat
        }
        out[0] = linearToSrgb(cr)
        out[1] = linearToSrgb(cg)
        out[2] = linearToSrgb(cb)
    }

    /**
     * Visibility (0..1) of a point source of magnitude [mag] against a sky of [skyMagArcsec2]:
     * 0 below the Schaefer threshold, 1 two magnitudes above it, smooth in between.
     */
    fun starVisibility(mag: Double, skyMagArcsec2: Double, bonusMag: Double = 0.0): Double {
        val lim = limitingMagnitude(skyMagArcsec2) + bonusMag
        val t = ((lim - mag) / 2.0).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    // ------------------------------------------------------------------------------------------------
    // Star colours
    // ------------------------------------------------------------------------------------------------

    /** Effective temperature (K) from B−V (Ballesteros 2012, EPL 97, 34008). */
    fun bvToTemperature(bv: Double): Double {
        val c = bv.coerceIn(-0.4, 2.0)
        return 4600.0 * (1.0 / (0.92 * c + 1.7) + 1.0 / (0.92 * c + 0.62))
    }

    /** Fallback B−V from the spectral class letter when the catalogue has no colour index. */
    fun spectralClassToBv(sp: Char): Double = when (sp.uppercaseChar()) {
        'O' -> -0.32
        'B' -> -0.16
        'A' -> 0.10
        'F' -> 0.42
        'G' -> 0.68
        'K' -> 1.15
        'M' -> 1.60
        'C', 'S', 'N', 'R' -> 2.0
        else -> 0.6
    }

    /**
     * Chromaticity of a black body of temperature [tempK] in linear sRGB, normalized so that the
     * largest channel is 1. CIE 1931 2° colour-matching functions use the multi-lobe Gaussian fit of
     * Wyman, Sloan & Shirley (2013, JCGT 2(2)), integrated against Planck's law at 380–780 nm.
     */
    fun blackbodyLinearSrgb(tempK: Double, out: DoubleArray) {
        var x = 0.0; var y = 0.0; var z = 0.0
        var lambda = 380.0
        while (lambda <= 780.0) {
            val b = planck(lambda * 1e-9, tempK)
            x += b * xBar(lambda); y += b * yBar(lambda); z += b * zBar(lambda)
            lambda += 5.0
        }
        // XYZ → linear sRGB (D65)
        var r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        var g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        var bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        r = max(r, 0.0); g = max(g, 0.0); bl = max(bl, 0.0)
        val m = max(r, max(g, bl))
        out[0] = r / m; out[1] = g / m; out[2] = bl / m
    }

    /** Star tint for display: physical black-body colour pulled towards white by [saturation] (0..1). */
    fun starTint(bv: Double, saturation: Double, out: DoubleArray) {
        blackbodyLinearSrgb(bvToTemperature(bv), out)
        // Normalize to unit luminance, then desaturate.
        val l = luminance(out[0], out[1], out[2])
        for (i in 0..2) out[i] = out[i] / l
        for (i in 0..2) out[i] = 1.0 + (out[i] - 1.0) * saturation
        val m = max(out[0], max(out[1], out[2]))
        for (i in 0..2) out[i] = out[i] / m
    }

    private fun planck(lambdaM: Double, t: Double): Double {
        val c1 = 3.741771852e-16; val c2 = 1.438776877e-2
        return c1 / (lambdaM.pow(5) * (exp(c2 / (lambdaM * t)) - 1.0))
    }

    private fun g(x: Double, mu: Double, s1: Double, s2: Double): Double {
        val s = if (x < mu) s1 else s2
        val t = (x - mu) / s
        return exp(-0.5 * t * t)
    }

    private fun xBar(l: Double) = 1.056 * g(l, 599.8, 37.9, 31.0) + 0.362 * g(l, 442.0, 16.0, 26.7) - 0.065 * g(l, 501.1, 20.4, 26.2)
    private fun yBar(l: Double) = 0.821 * g(l, 568.8, 46.9, 40.5) + 0.286 * g(l, 530.9, 16.3, 31.1)
    private fun zBar(l: Double) = 1.217 * g(l, 437.0, 11.8, 36.0) + 0.681 * g(l, 459.0, 26.0, 13.8)

    // ------------------------------------------------------------------------------------------------
    // Brightness of solar-system bodies as light sources for the sky
    // ------------------------------------------------------------------------------------------------

    /**
     * Ratio of lunar to solar illuminance at the Earth for a lunar phase angle (degrees), from the
     * Moon's V magnitude m = −12.73 + 0.026|α| + 4·10⁻⁹ α⁴ (Allen's Astrophysical Quantities) and
     * the Sun's V = −26.74.
     */
    fun moonToSunIlluminanceRatio(phaseAngleDeg: Double): Double {
        val a = abs(phaseAngleDeg).coerceIn(0.0, 180.0)
        val m = -12.73 + 0.026 * a + 4e-9 * a.pow(4)
        return 10.0.pow(-0.4 * (m + 26.74))
    }

    /** Smoothstep helper. */
    fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
