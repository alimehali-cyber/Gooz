package com.alijafari.red.astronomy.ui.rendering

/**
 * Compose-free copy of the existing "Real Sky" palette, keyed on the Sun altitude.
 *
 * The values are the same anchors that [AtmosphereRenderer] used before; they live here so the
 * Milky Way panorama can use the same daytime, sunrise, sunset and twilight sky without duplicating
 * the numbers, and so the transition can be unit-tested on the JVM. Colours are sRGB components in
 * 0..1, identical to Compose's `Color(0xAARRGGBB)` components.
 */
object RealSkyPalette {

    /** An sRGB colour, components 0..1. */
    data class Rgb(val r: Float, val g: Float, val b: Float) {
        fun lerp(other: Rgb, t: Float): Rgb {
            val f = t.coerceIn(0f, 1f)
            return Rgb(
                r = r + (other.r - r) * f,
                g = g + (other.g - g) * f,
                b = b + (other.b - b) * f
            )
        }
    }

    /** Vertical sky gradient: zenith at the top, horizon at the bottom of the hero. */
    data class Gradient(val zenith: Rgb, val mid: Rgb, val horizon: Rgb)

    /** Gradient stops, as fractions of the hero height from the top. */
    const val MID_STOP = 0.55f
    const val HORIZON_STOP = 0.86f

    private data class GradientAnchor(val altDeg: Double, val zenith: Rgb, val mid: Rgb, val horizon: Rgb)
    private data class DomeAnchor(val altDeg: Double, val color: Rgb, val alpha: Float)

    /** Sun altitude above which the dome is gone in the legacy Real Sky (hard cut-off). */
    const val DOME_LEGACY_TOP_DEG = 14.0

    /** Sun altitude at which the extended (panorama-mode) dome fades to nothing. */
    const val DOME_EXTENDED_END_DEG = 35.0

    private val GRADIENT_ANCHORS = listOf(
        GradientAnchor(35.0, hex(0xFF0E2B63), hex(0xFF2358A3), hex(0xFF7CB4E6)),
        GradientAnchor(15.0, hex(0xFF102C60), hex(0xFF285AA0), hex(0xFF88B6DC)),
        GradientAnchor(5.0, hex(0xFF0B1E44), hex(0xFF2E4D7E), hex(0xFFD6A67E)),
        GradientAnchor(0.0, hex(0xFF08142E), hex(0xFF1E3158), hex(0xFF8C5B6E)),
        GradientAnchor(-3.0, hex(0xFF050B1C), hex(0xFF132040), hex(0xFF3E3B63)),
        GradientAnchor(-6.0, hex(0xFF030712), hex(0xFF0B142C), hex(0xFF212F56)),
        GradientAnchor(-12.0, hex(0xFF02040B), hex(0xFF060B1A), hex(0xFF101C38)),
        GradientAnchor(-18.0, hex(0xFF02030A), hex(0xFF040711), hex(0xFF080E1D))
    )

    private val MOON_SKY_TINT = hex(0xFF182A4A)

    private val DOME_ANCHORS = listOf(
        DomeAnchor(14.0, hex(0xFFFFF4CC), 0.18f),
        DomeAnchor(5.0, hex(0xFFFFB066), 0.42f),
        DomeAnchor(2.0, hex(0xFFFFA252), 0.56f),
        DomeAnchor(-3.0, hex(0xFFFF7A3D), 0.48f),
        DomeAnchor(-9.0, hex(0xFF3A4A80), 0.34f),
        DomeAnchor(-15.0, hex(0xFF0E1A3A), 0.16f),
        DomeAnchor(-18.0, hex(0xFF0E1A3A), 0.0f)
    )

    /** Altitude below which the dome is not drawn at all. */
    const val DOME_BOTTOM_DEG = -18.0

    /**
     * Sky gradient for the Sun altitude, including the moonlight wash on dark nights. Same arithmetic
     * as the legacy `drawRealSkyAtmosphere`.
     */
    fun gradientAt(sunAltDeg: Double, moonGlowIntensity: Float): Gradient {
        val first = GRADIENT_ANCHORS.first()
        val last = GRADIENT_ANCHORS.last()
        var zenith = last.zenith
        var mid = last.mid
        var horizon = last.horizon

        if (sunAltDeg >= first.altDeg) {
            zenith = first.zenith
            mid = first.mid
            horizon = first.horizon
        } else if (sunAltDeg > last.altDeg) {
            for (i in 0 until GRADIENT_ANCHORS.size - 1) {
                val upper = GRADIENT_ANCHORS[i]
                val lower = GRADIENT_ANCHORS[i + 1]
                if (sunAltDeg <= upper.altDeg && sunAltDeg >= lower.altDeg) {
                    val t = ((upper.altDeg - sunAltDeg) / (upper.altDeg - lower.altDeg)).toFloat()
                    zenith = upper.zenith.lerp(lower.zenith, t)
                    mid = upper.mid.lerp(lower.mid, t)
                    horizon = upper.horizon.lerp(lower.horizon, t)
                    break
                }
            }
        }

        if (sunAltDeg < -6.0 && moonGlowIntensity > 0.02f) {
            val moonWash = (moonGlowIntensity * 0.16f).coerceIn(0f, 0.16f)
            zenith = zenith.lerp(MOON_SKY_TINT, moonWash * 0.55f)
            mid = mid.lerp(MOON_SKY_TINT, moonWash * 0.80f)
            horizon = horizon.lerp(MOON_SKY_TINT, moonWash)
        }
        return Gradient(zenith, mid, horizon)
    }

    /**
     * Sun-centred twilight glow as (colour, alpha), or null when it is not drawn.
     *
     * Legacy (`extendPastHorizonGlow = false`): drawn for -18 < sunAlt <= 14, exactly as before.
     * Panorama mode (`true`): the same colour and alpha, but the alpha fades to zero between 14 and
     * 35 degrees instead of stopping abruptly at 14, so the Sun glow has no hard edge in daylight.
     */
    fun twilightDome(sunAltDeg: Double, extendPastHorizonGlow: Boolean): Pair<Rgb, Float>? {
        if (sunAltDeg <= DOME_BOTTOM_DEG) return null
        if (sunAltDeg > DOME_LEGACY_TOP_DEG) {
            if (!extendPastHorizonGlow || sunAltDeg >= DOME_EXTENDED_END_DEG) return null
            val fade = smoothstep(DOME_LEGACY_TOP_DEG, DOME_EXTENDED_END_DEG, sunAltDeg).toFloat()
            return DOME_ANCHORS.first().color to DOME_ANCHORS.first().alpha * (1f - fade)
        }
        for (i in 0 until DOME_ANCHORS.size - 1) {
            val upper = DOME_ANCHORS[i]
            val lower = DOME_ANCHORS[i + 1]
            if (sunAltDeg <= upper.altDeg && sunAltDeg >= lower.altDeg) {
                val t = ((upper.altDeg - sunAltDeg) / (upper.altDeg - lower.altDeg)).toFloat()
                return upper.color.lerp(lower.color, t) to (upper.alpha + (lower.alpha - upper.alpha) * t)
            }
        }
        return null
    }

    /** Hermite smoothstep on [edge0, edge1], clamped. Pure helper, public for the panorama model. */
    fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun hex(argb: Long): Rgb = Rgb(
        r = ((argb shr 16) and 0xFF).toFloat() / 255f,
        g = ((argb shr 8) and 0xFF).toFloat() / 255f,
        b = (argb and 0xFF).toFloat() / 255f
    )
}
