package com.alijafari.red.astronomy.ar.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * How bright and how big a point source (star, planet, satellite, Galilean moon) is drawn, from its
 * observed magnitude and the limiting magnitude of the sky behind it. The same rule is used for every
 * point-like glyph so that a 2nd-magnitude planet and a 2nd-magnitude star look alike.
 */
object ArPhotometry {

    class PointLook {
        /** Radius of the PSF core sprite (px). */
        var coreRadiusPx = 0f
        var coreAlpha = 0f
        /** Radius and alpha of the glare halo for bright sources (0 = none). */
        var haloRadiusPx = 0f
        var haloAlpha = 0f
    }

    /**
     * @param mag observed magnitude (after extinction)
     * @param limitingMag faintest magnitude visible in this part of the sky (with zoom bonus)
     * @param density display density (px per dp)
     */
    fun pointLook(mag: Double, limitingMag: Double, density: Float, out: PointLook): PointLook {
        val s = limitingMag - mag
        if (s <= 0.0) {
            out.coreRadiusPx = 0f; out.coreAlpha = 0f; out.haloRadiusPx = 0f; out.haloAlpha = 0f
            return out
        }
        val coreDp = (0.75 + 0.36 * s).coerceIn(0.75, 4.8)
        out.coreRadiusPx = (coreDp * density).toFloat()
        out.coreAlpha = SkyRenderModel.smoothstep(0.0, 1.6, s).toFloat()
        if (s > 4.2) {
            out.haloRadiusPx = (coreDp * 3.4 * density).toFloat()
            out.haloAlpha = min(0.38, (s - 4.2) * 0.075).toFloat()
        } else {
            out.haloRadiusPx = 0f
            out.haloAlpha = 0f
        }
        return out
    }

    /**
     * Limiting magnitude used for drawing.
     *  - Virtual sky (camera off): the Schaefer threshold of the actual sky brightness in that direction,
     *    plus the gain of a narrower field of view (zoom acts like optical aid, ≈ 2.2 mag per decade).
     *  - Camera mode: the real sky is behind the camera image, so stars are drawn as finder marks with
     *    a limit that only depends on the darkness of the sky (Sun altitude).
     */
    fun limitingMagnitude(skyMagArcsec2: Double, zoom: Double, cameraMode: Boolean, sunAltDeg: Double): Double {
        val zoomBonus = 2.2 * log10(max(zoom, 1.0))
        return if (!cameraMode) {
            SkyRenderModel.limitingMagnitude(skyMagArcsec2) + zoomBonus
        } else {
            val base = when {
                sunAltDeg < -12.0 -> 5.6
                sunAltDeg < -6.0 -> 4.6
                sunAltDeg < 0.0 -> 3.6
                else -> 2.6
            }
            base + zoomBonus
        }
    }
}
