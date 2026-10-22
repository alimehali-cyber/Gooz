package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.alijafari.red.astronomy.domain.SkyCanvasTheme

/**
 * Colours shared by the three art layers of the full-bleed scene: the ground band, the cloud veils and
 * the observer.
 *
 * Every value is derived from the active [SkyCanvasTheme] and from [LightingState], so the scene keeps the
 * theme's identity and still follows the real Sun: warm at golden hour, blue at noon, near-black at
 * astronomical night. No weather data is involved; these are art direction values, not a forecast.
 */
internal data class ScenePalette(
    /** Hazy distant ridge, just under the horizon. */
    val farRidge: Color,
    /** Nearer ridge, above the solid ground. */
    val midRidge: Color,
    /** Mist that sits on the horizon and softens both ridges. */
    val haze: Color,
    /** Top of the solid ground band. */
    val groundTop: Color,
    /** Bottom of the solid ground band, where it meets the app background. */
    val groundBottom: Color,
    /** Grass tufts along the top of the ground band. */
    val grass: Color,
    /** Cloud veil tint. */
    val cloud: Color,
    /** Multiplier on the cloud veil alpha. 0 hides the layer for a theme. */
    val cloudStrength: Float,
    /** Outline colour of paper-cut edges. Fully transparent for the photographic themes. */
    val cutEdge: Color
)

/** Clothing and rim colours for the observer. */
internal data class FigurePalette(
    val jacket: Color,
    val trousers: Color,
    val skin: Color,
    val shoe: Color,
    val cap: Color,
    /** Rim light that separates the figure from the sky. */
    val rim: Color
)

private val NightFarRidge = Color(0xFF111C30)
private val NightMidRidge = Color(0xFF0A131F)
private val NightGroundTop = Color(0xFF122019)
private val NightGroundBottom = Color(0xFF03070A)

/** Ridge and ground colours for a theme at the current Sun altitude. */
internal fun scenePalette(theme: SkyCanvasTheme, lighting: LightingState): ScenePalette {
    val day = lighting.ambientBrightness.coerceIn(0f, 1f)
    val twilight = (1f - kotlin.math.abs(lighting.sunAltitudeDeg.toFloat()).coerceAtMost(12f) / 12f)

    return when (theme) {
        SkyCanvasTheme.REAL_SKY -> {
            // Both ridges stay clearly darker than the noon sky: aerial perspective lightens them, it does
            // not dissolve them.
            val far = lerp(Color(0xFF1E3348), Color(0xFF7C9BB0), day)
            val mid = lerp(NightMidRidge, Color(0xFF44664F), day)
            ScenePalette(
                farRidge = far.copy(alpha = 0.50f + 0.22f * day),
                midRidge = mid.copy(alpha = 0.72f + 0.18f * day),
                haze = lerp(Color(0xFF22304A), lighting.horizonTone, day * 0.55f),
                groundTop = lerp(NightGroundTop, Color(0xFF3E5B3C), day),
                groundBottom = lerp(NightGroundBottom, Color(0xFF0C1A13), day),
                grass = lerp(Color(0xFF1C3326), Color(0xFF8FB86A), day * 0.85f),
                cloud = lerp(Color(0xFF2C3E5E), Color(0xFFFFF6E8), day),
                // The photographic sky is already fully dressed, so it gets only a faint veil for motion.
                cloudStrength = 0.20f,
                cutEdge = Color.Transparent
            )
        }

        SkyCanvasTheme.ATMOSPHERIC_SKY -> {
            val far = lerp(Color(0xFF1B2735), lighting.horizonTone, 0.30f + 0.25f * day)
            val mid = lerp(Color(0xFF0B1424), lighting.horizonTone, 0.14f + 0.18f * day)
            ScenePalette(
                farRidge = far.copy(alpha = 0.50f + 0.30f * day),
                midRidge = mid.copy(alpha = 0.88f),
                haze = lerp(Color(0xFF1B2A4A), lighting.horizonTone, 0.35f + 0.45f * day),
                groundTop = lerp(Color(0xFF0E2A2A), Color(0xFF2F6B5A), day),
                groundBottom = lerp(Color(0xFF050C11), Color(0xFF08171A), day),
                grass = lerp(Color(0xFF173B36), Color(0xFF63B08A), day * 0.9f),
                cloud = lerp(Color(0xFF2A3A5C), Color(0xFFFFF8F0), day),
                cloudStrength = 0.60f,
                cutEdge = Color.Transparent
            )
        }

        SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> {
            val far = lerp(Color(0xFF2A2F38), Color(0xFFB8C0CC), day)
            val mid = lerp(Color(0xFF14181E), Color(0xFF8A93A0), day)
            ScenePalette(
                farRidge = far.copy(alpha = 0.45f),
                midRidge = mid.copy(alpha = 0.80f),
                haze = lerp(Color(0xFF1A1F26), Color(0xFFE2E8F0), day),
                groundTop = lerp(Color(0xFF101317), Color(0xFFD4DAE2), day),
                groundBottom = lerp(Color(0xFF05070A), Color(0xFF8E969F), day),
                grass = lerp(Color(0xFF2B3138), Color(0xFF6B747E), day),
                cloud = lerp(Color(0xFF4B5563), Color(0xFFFFFFFF), day),
                cloudStrength = 0.28f,
                cutEdge = Color.Transparent
            )
        }

        SkyCanvasTheme.KIDS_WATERCOLOR -> {
            val far = lerp(Color(0xFF2E2A72), Color(0xFFA8DADC), day)
            val mid = lerp(Color(0xFF241F5C), Color(0xFF7FB8C6), day)
            ScenePalette(
                farRidge = far.copy(alpha = 0.70f),
                midRidge = mid.copy(alpha = 0.90f),
                haze = lerp(Color(0xFF3B1F52), Color(0xFFFFE3EC), day),
                groundTop = lerp(Color(0xFF2B2A5E), Color(0xFF8FD3A8), day),
                groundBottom = lerp(Color(0xFF141336), Color(0xFF3F8E77), day),
                grass = lerp(Color(0xFF3D348B), Color(0xFF4CAF8C), day),
                cloud = lerp(Color(0xFF3D348B), Color(0xFFFFFDF8), day),
                cloudStrength = 0.80f,
                cutEdge = Color.Transparent
            )
        }

        SkyCanvasTheme.OBSERVATORY -> ScenePalette(
            farRidge = Color(0xFF4A0E0E).copy(alpha = 0.70f),
            midRidge = Color(0xFF2A0505).copy(alpha = 0.90f),
            haze = Color(0xFF6B1414),
            groundTop = lerp(Color(0xFF210404), Color(0xFF43100C), day * 0.4f),
            groundBottom = Color(0xFF0A0000),
            grass = Color(0xFF5E1414),
            cloud = Color(0xFF6B1B1B),
            cloudStrength = 0.34f,
            cutEdge = Color.Transparent
        )

        SkyCanvasTheme.PAPERCRAFT_DIORAMA -> {
            val night = day < 0.35f
            ScenePalette(
                farRidge = if (night) Color(0xFF3B4256) else Color(0xFFA3B18A),
                midRidge = if (night) Color(0xFF2C3446) else Color(0xFFD4A373),
                haze = if (night) Color(0xFF4A4E69) else Color(0xFFF7F3E9),
                groundTop = if (night) Color(0xFF2A3140) else Color(0xFF588157),
                groundBottom = if (night) Color(0xFF191E28) else Color(0xFF3A405A),
                grass = if (night) Color(0xFF39405A) else Color(0xFF7C9A6B),
                cloud = if (night) Color(0xFF3D4A5D) else Color(0xFFFFFDF8),
                cloudStrength = 0f,
                cutEdge = if (night) Color(0x33000000) else Color(0x22000000)
            )
        }
    }.let { palette ->
        // Golden hour warms the whole ground band a little, which is what sells the low Sun.
        if (lighting.sunAltitudeDeg in -2.0..10.0 && theme != SkyCanvasTheme.MONOCHROME_SCIENTIFIC) {
            val warm = lerp(Color.White, Color(0xFFFFB877), 0.35f * twilight)
            palette.copy(
                farRidge = palette.farRidge,
                haze = lerp(palette.haze, warm, 0.35f * twilight),
                grass = lerp(palette.grass, warm, 0.30f * twilight)
            )
        } else palette
    }
}

/** Clothing for the observer, following the theme and the brightness of the scene. */
internal fun figurePalette(theme: SkyCanvasTheme, lighting: LightingState): FigurePalette {
    val day = lighting.ambientBrightness.coerceIn(0f, 1f)
    return when (theme) {
        SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> {
            val ink = lerp(Color(0xFF0A0A0A), Color(0xFF18181B), day)
            FigurePalette(
                jacket = ink,
                trousers = ink,
                skin = lerp(Color(0xFF3F3F46), Color(0xFF52525B), day),
                shoe = Color(0xFF000000),
                cap = ink,
                rim = lerp(Color(0xFFCBD5E1), Color.Black, day)
            )
        }

        SkyCanvasTheme.KIDS_WATERCOLOR -> FigurePalette(
            jacket = lerp(Color(0xFF2D2A6E), Color(0xFFFFB4A2), day),
            trousers = lerp(Color(0xFF1D1B4B), Color(0xFF457B9D), day),
            skin = lerp(Color(0xFF7A5A44), Color(0xFFE8B48C), day),
            shoe = Color(0xFF1D3557),
            cap = lerp(Color(0xFF2D2A6E), Color(0xFFFFD670), day),
            rim = Color(0x66FFE3EC)
        )

        SkyCanvasTheme.OBSERVATORY -> FigurePalette(
            jacket = lerp(Color(0xFF2A0808), Color(0xFF7F1D1D), day),
            trousers = Color(0xFF210404),
            skin = lerp(Color(0xFF4A2A20), Color(0xFFC08A5E), day),
            shoe = Color(0xFF120000),
            cap = Color(0xFF3B0404),
            rim = Color(0x55EF4444)
        )

        SkyCanvasTheme.PAPERCRAFT_DIORAMA -> FigurePalette(
            jacket = if (day < 0.35f) Color(0xFF3C4358) else Color(0xFFE0A458),
            trousers = if (day < 0.35f) Color(0xFF262C3A) else Color(0xFF3A405A),
            skin = if (day < 0.35f) Color(0xFF6E5544) else Color(0xFFE8C39E),
            shoe = Color(0xFF221B19),
            cap = if (day < 0.35f) Color(0xFF333A4C) else Color(0xFFC8894A),
            rim = Color(0x22FFFFFF)
        )

        else -> FigurePalette(
            jacket = lerp(Color(0xFF232C46), Color(0xFFE0A458), day),
            trousers = lerp(Color(0xFF161B28), Color(0xFF33405A), day),
            skin = lerp(Color(0xFF5C4636), Color(0xFFDCA173), day),
            shoe = Color(0xFF0E1118),
            cap = lerp(Color(0xFF1E2638), Color(0xFFC8894A), day),
            rim = lerp(Color(0xFF9DBBE8), Color.White, day)
        )
    }
}
