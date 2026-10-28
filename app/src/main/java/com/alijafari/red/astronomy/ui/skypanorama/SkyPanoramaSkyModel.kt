package com.alijafari.red.astronomy.ui.skypanorama

import com.alijafari.red.astronomy.ui.rendering.RealSkyPalette

/**
 * Time-of-day integration for the panorama. Pure Kotlin, no Compose or GL, so it can be unit-tested.
 *
 * The sky colour comes from the existing Real Sky palette ([RealSkyPalette]) keyed on the same Sun
 * altitude the hero already uses. The photographic panorama is blended over that palette by a single
 * [nightWeight] derived from the Sun altitude:
 *  - Sun altitude at or above [DAY_FULL_DEG] (-6 deg, the start of civil twilight): weight 0, the
 *    blue daytime palette is shown and the Milky Way is not drawn.
 *  - Sun altitude at or below [NIGHT_FULL_DEG] (-18 deg, end of astronomical twilight): weight 1, the
 *    photographic panorama is shown exactly as in Phase 1.
 *  - In between: a Hermite smoothstep, so the transition has zero slope at both ends and no band or
 *    threshold edge.
 */
object SkyPanoramaSkyModel {

    const val DAY_FULL_DEG = -6.0
    const val NIGHT_FULL_DEG = -18.0

    /** Sun altitude quantum for the renderer state. Sub-quantum changes do not trigger a GL frame. */
    const val SUN_ALT_QUANTUM_DEG = 0.05

    /** Moon-glow quantum for the renderer state. */
    const val MOON_GLOW_QUANTUM = 0.01f

    /** Everything the GL pass needs for the sky, derived from the quantized inputs. */
    data class Sky(val gradient: RealSkyPalette.Gradient, val nightWeight: Float)

    /** 0 in daylight, 1 at night, smooth in between. NaN is treated as night (no daytime override). */
    fun nightWeight(sunAltDeg: Double): Float {
        if (sunAltDeg.isNaN()) return 1f
        if (sunAltDeg >= DAY_FULL_DEG) return 0f
        if (sunAltDeg <= NIGHT_FULL_DEG) return 1f
        // 0 at DAY_FULL, 1 at NIGHT_FULL
        val t = (DAY_FULL_DEG - sunAltDeg) / (DAY_FULL_DEG - NIGHT_FULL_DEG)
        return (t * t * (3.0 - 2.0 * t)).toFloat()
    }

    fun skyFor(sunAltDeg: Double, moonGlowIntensity: Float): Sky = Sky(
        gradient = RealSkyPalette.gradientAt(sunAltDeg, moonGlowIntensity),
        nightWeight = nightWeight(sunAltDeg)
    )

    fun quantizeSunAlt(sunAltDeg: Double): Double {
        if (sunAltDeg.isNaN()) return sunAltDeg
        return Math.round(sunAltDeg / SUN_ALT_QUANTUM_DEG) * SUN_ALT_QUANTUM_DEG
    }

    fun quantizeMoonGlow(glow: Float): Float {
        if (glow.isNaN()) return 0f
        return Math.round(glow / MOON_GLOW_QUANTUM) * MOON_GLOW_QUANTUM
    }
}
