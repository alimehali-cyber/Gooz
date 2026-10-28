package com.alijafari.red.astronomy.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.astro_engine.MoonEngine
import com.alijafari.red.astronomy.astro_engine.SunEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import java.util.Calendar
import kotlin.math.*

/**
 * Celestial Fabric / Dynamic Silk Lighting State
 * Encapsulates living astronomical lighting, season, and dynamic contrast.
 */
data class CelestialLighting(
    val sunAltitudeDeg: Double,
    val sunAzimuthDeg: Double,
    val moonAltitudeDeg: Double,
    val moonAzimuthDeg: Double,
    val moonIlluminationPercent: Double,
    val seasonFactor: Float, // 0.0 = Winter (Cornflower), 1.0 = Summer (Ice Blue)
    val dailyPhaseName: String,
    val isDark: Boolean,
    val backgroundColor: Color,
    val surfaceColor: Color,
    val surfaceVariantColor: Color,
    val primaryAccent: Color,
    val secondaryAccent: Color,
    val tertiaryAccent: Color,
    val outlineColor: Color,
    val textPrimaryColor: Color,
    val textSecondaryColor: Color,
    val textTertiaryColor: Color,
    val sheenDirection: Offset,
    val sheenIntensity: Float,
    val sheenWidth: Float,
    val sheenColor: Color,
    val grazingBorderColor: Color
)

val LocalCelestialLighting = compositionLocalOf {
    defaultCelestialLighting()
}

fun defaultCelestialLighting(): CelestialLighting {
    return CelestialLighting(
        sunAltitudeDeg = 45.0,
        sunAzimuthDeg = 180.0,
        moonAltitudeDeg = -30.0,
        moonAzimuthDeg = 90.0,
        moonIlluminationPercent = 50.0,
        seasonFactor = 1.0f,
        dailyPhaseName = "Daylight",
        isDark = false,
        backgroundColor = Color(0xFFE2EFF7),
        surfaceColor = Color(0xFFF0F6FA),
        surfaceVariantColor = Color(0xFFDBEAF4),
        primaryAccent = Color(0xFF0284C7),
        secondaryAccent = Color(0xFF0EA5E9),
        tertiaryAccent = Color(0xFF6366F1),
        outlineColor = Color(0xFFBAE6FD),
        textPrimaryColor = Color(0xFF0C1929),
        textSecondaryColor = Color(0xFF475569),
        textTertiaryColor = Color(0xFF64748B),
        sheenDirection = Offset(0f, 1f),
        sheenIntensity = 0.22f,
        sheenWidth = 0.70f,
        sheenColor = Color(0xFFFFFFFF),
        grazingBorderColor = Color(0x330284C7)
    )
}

/**
 * Smoothly interpolates between two colors.
 */
fun lerpSilkColor(c1: Color, c2: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = c1.red + (c2.red - c1.red) * f,
        green = c1.green + (c2.green - c1.green) * f,
        blue = c1.blue + (c2.blue - c1.blue) * f,
        alpha = c1.alpha + (c2.alpha - c1.alpha) * f
    )
}

/**
 * Computes standard WCAG relative luminance for an sRGB Color.
 */
fun calculateRelativeLuminance(color: Color): Float {
    fun channelLuminance(c: Float): Float {
        return if (c <= 0.04045f) {
            c / 12.92f
        } else {
            ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }
    val r = channelLuminance(color.red)
    val g = channelLuminance(color.green)
    val b = channelLuminance(color.blue)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * Derives crisp, dynamic, WCAG AAA readable text and accent colors guaranteed to have
 * maximum legibility against any background/surface color throughout the day.
 */
fun deriveDynamicTextColors(
    surfaceBg: Color,
    seasonFactor: Float
): Triple<Color, Color, Color> {
    val lum = calculateRelativeLuminance(surfaceBg)
    return if (lum < 0.25f) {
        // Dark surface -> Crisp light text
        // High contrast pure white textPrimary, soft slate secondary, muted tertiary
        val primary = Color(0xFFF8FAFC)
        val secondary = Color(0xFFCBD5E1)
        val tertiary = Color(0xFF94A3B8)
        Triple(primary, secondary, tertiary)
    } else {
        // Light surface -> Deep high-contrast obsidian ink text
        val deepObsidian = lerpSilkColor(
            Color(0xFF0B132B), // Deep midnight ink in winter
            Color(0xFF0A1626), // Deep navy obsidian in summer
            seasonFactor
        )
        val secondary = Color(0xFF334155) // Slate 700
        val tertiary = Color(0xFF64748B)  // Slate 500
        Triple(deepObsidian, secondary, tertiary)
    }
}

/**
 * Computes the authoritative Celestial Fabric lighting and color palette based on
 * RED's existing date/time and Sun/Moon astronomical positions.
 */
fun calculateCelestialLighting(
    timestampMs: Long = System.currentTimeMillis(),
    latDeg: Double = 30.1141,
    lonDeg: Double = 51.5217
): CelestialLighting {
    val jd = TimeEngine.getJulianDate(timestampMs)
    val sunAltAz = SunEngine.getSunAltAz(jd, latDeg, lonDeg)
    val sunAlt = sunAltAz.altitudeDeg
    val sunAz = sunAltAz.azimuthDeg

    val moonData = MoonEngine.calculateMoon(jd, latDeg, lonDeg)
    val moonAlt = moonData.altitudeDeg
    val moonAz = moonData.azimuthDeg
    val moonIllum = moonData.illuminationPercent

    // 1. Season Calculation: Smooth sinusoidal interpolation across the year
    // Day 172 (June 21) = Summer Solstice (Peak Ice Blue = 1.0)
    // Day 355 (Dec 21) = Winter Solstice (Peak Cornflower Blue = 0.0)
    val cal = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply {
        timeInMillis = timestampMs
    }
    val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
    val daysInYear = if (cal.getActualMaximum(Calendar.DAY_OF_YEAR) == 366) 366.0 else 365.0
    val seasonPhase = 2.0 * Math.PI * (dayOfYear - 172.0) / daysInYear
    var seasonFactor = ((1.0 + cos(seasonPhase)) / 2.0).toFloat().coerceIn(0f, 1f)
    if (latDeg < 0.0) {
        seasonFactor = 1.0f - seasonFactor // Invert for Southern hemisphere
    }

    // 2. Seasonal Daytime Color Palettes
    // Summer Daytime: Crisp, refreshing Ice Blue Silk
    val summerDayBg = Color(0xFFE0EDF6)
    val summerDaySurface = Color(0xFFEEF5FA)
    val summerDaySurfaceVar = Color(0xFFD6E6F2)
    val summerDayPrimary = Color(0xFF0284C7)      // Ice Cobalt
    val summerDaySecondary = Color(0xFF0EA5E9)    // Glacial Cyan
    val summerDayTertiary = Color(0xFF0284C7)
    val summerDayOutline = Color(0xFFBAE6FD)

    // Winter Daytime: Elegant Cornflower Blue Silk
    val winterDayBg = Color(0xFFE8EEFA)
    val winterDaySurface = Color(0xFFF3F6FD)
    val winterDaySurfaceVar = Color(0xFFDEE6F8)
    val winterDayPrimary = Color(0xFF2563EB)      // Deep Cornflower Blue
    val winterDaySecondary = Color(0xFF6366F1)    // Periwinkle Silk
    val winterDayTertiary = Color(0xFF4F46E5)
    val winterDayOutline = Color(0xFFC7D2FE)

    // Interpolated Daytime Silk Palette according to season (Spring/Autumn transition smoothly)
    val dayBg = lerpSilkColor(winterDayBg, summerDayBg, seasonFactor)
    val daySurface = lerpSilkColor(winterDaySurface, summerDaySurface, seasonFactor)
    val daySurfaceVar = lerpSilkColor(winterDaySurfaceVar, summerDaySurfaceVar, seasonFactor)
    val dayPrimary = lerpSilkColor(winterDayPrimary, summerDayPrimary, seasonFactor)
    val daySecondary = lerpSilkColor(winterDaySecondary, summerDaySecondary, seasonFactor)
    val dayTertiary = lerpSilkColor(winterDayTertiary, summerDayTertiary, seasonFactor)
    val dayOutline = lerpSilkColor(winterDayOutline, summerDayOutline, seasonFactor)

    // 3. Daily Cycle Palettes for Twilight & Night
    // Golden Hour (Sun altitude 0° to 12°)
    val goldenBg = Color(0xFFF7EFE6)
    val goldenSurface = Color(0xFFFDFCF9)
    val goldenSurfaceVar = Color(0xFFF5E8D6)
    val goldenPrimary = Color(0xFFD97706)         // Amber Silk
    val goldenSecondary = Color(0xFFF59E0B)       // Warm Champagne Gold
    val goldenTertiary = Color(0xFFB45309)
    val goldenOutline = Color(0xFFFDE68A)

    // Sunset / Civil Twilight (Sun altitude -6° to 0°)
    val civilBg = Color(0xFF1E1528)
    val civilSurface = Color(0xFF281C36)
    val civilSurfaceVar = Color(0xFF38274C)
    val civilPrimary = Color(0xFFF97316)          // Sunset Coral Silk
    val civilSecondary = Color(0xFFA855F7)        // Amethyst Twilight Silk
    val civilTertiary = Color(0xFFFB923C)
    val civilOutline = Color(0x66F97316)

    // Nautical Twilight (Sun altitude -12° to -6°)
    val nauticalBg = Color(0xFF101424)
    val nauticalSurface = Color(0xFF171E34)
    val nauticalSurfaceVar = Color(0xFF222B4A)
    val nauticalPrimary = Color(0xFF818CF8)       // Indigo Twilight
    val nauticalSecondary = Color(0xFFA5B4FC)
    val nauticalTertiary = Color(0xFF38BDF8)
    val nauticalOutline = Color(0x40818CF8)

    // Astronomical Twilight (Sun altitude -18° to -12°)
    val astroBg = Color(0xFF0C0F1D)
    val astroSurface = Color(0xFF12172A)
    val astroSurfaceVar = Color(0xFF1B233E)
    val astroPrimary = Color(0xFF38BDF8)          // Starlight Ice
    val astroSecondary = Color(0xFF60A5FA)
    val astroTertiary = Color(0xFF818CF8)
    val astroOutline = Color(0x3338BDF8)

    // Night (True Dark, Sun altitude < -18°)
    val nightBg = Color(0xFF080B14)               // Midnight Celestial Void Silk
    val nightSurface = Color(0xFF0F1424)          // Woven Midnight Silk
    val nightSurfaceVar = Color(0xFF171F36)
    val nightPrimary = Color(0xFF38BDF8)          // Pure Celestial Starlight
    val nightSecondary = Color(0xFF818CF8)        // Moonlit Silk
    val nightTertiary = Color(0xFFFDE68A)         // Pale Starlight Gold
    val nightOutline = Color(0x2938BDF8)

    // 4. Smooth Continuous Evolution Across the Daily Cycle
    val phaseName: String
    val bgCol: Color
    val surfCol: Color
    val surfVarCol: Color
    val primCol: Color
    val secCol: Color
    val tertCol: Color
    val outCol: Color

    when {
        sunAlt >= 22.0 -> {
            phaseName = "Daylight"
            bgCol = dayBg; surfCol = daySurface; surfVarCol = daySurfaceVar
            primCol = dayPrimary; secCol = daySecondary; tertCol = dayTertiary
            outCol = dayOutline
        }
        sunAlt in 10.0..22.0 -> {
            phaseName = "Afternoon Light"
            val f = ((sunAlt - 10.0) / 12.0).toFloat()
            bgCol = lerpSilkColor(goldenBg, dayBg, f)
            surfCol = lerpSilkColor(goldenSurface, daySurface, f)
            surfVarCol = lerpSilkColor(goldenSurfaceVar, daySurfaceVar, f)
            primCol = lerpSilkColor(goldenPrimary, dayPrimary, f)
            secCol = lerpSilkColor(goldenSecondary, daySecondary, f)
            tertCol = lerpSilkColor(goldenTertiary, dayTertiary, f)
            outCol = lerpSilkColor(goldenOutline, dayOutline, f)
        }
        sunAlt in 0.0..10.0 -> {
            phaseName = "Golden Hour"
            val f = (sunAlt / 10.0).toFloat()
            bgCol = lerpSilkColor(goldenBg, goldenBg, f)
            surfCol = lerpSilkColor(goldenSurface, goldenSurface, f)
            surfVarCol = lerpSilkColor(goldenSurfaceVar, goldenSurfaceVar, f)
            primCol = lerpSilkColor(civilPrimary, goldenPrimary, f)
            secCol = lerpSilkColor(civilSecondary, goldenSecondary, f)
            tertCol = lerpSilkColor(civilTertiary, goldenTertiary, f)
            outCol = lerpSilkColor(civilOutline, goldenOutline, f)
        }
        sunAlt in -6.0..0.0 -> {
            phaseName = "Civil Twilight"
            val f = ((sunAlt + 6.0) / 6.0).toFloat()
            bgCol = lerpSilkColor(nauticalBg, civilBg, f)
            surfCol = lerpSilkColor(nauticalSurface, civilSurface, f)
            surfVarCol = lerpSilkColor(nauticalSurfaceVar, civilSurfaceVar, f)
            primCol = lerpSilkColor(nauticalPrimary, civilPrimary, f)
            secCol = lerpSilkColor(nauticalSecondary, civilSecondary, f)
            tertCol = lerpSilkColor(nauticalTertiary, civilTertiary, f)
            outCol = lerpSilkColor(nauticalOutline, civilOutline, f)
        }
        sunAlt in -12.0..-6.0 -> {
            phaseName = "Nautical Twilight"
            val f = ((sunAlt + 12.0) / 6.0).toFloat()
            bgCol = lerpSilkColor(astroBg, nauticalBg, f)
            surfCol = lerpSilkColor(astroSurface, nauticalSurface, f)
            surfVarCol = lerpSilkColor(astroSurfaceVar, nauticalSurfaceVar, f)
            primCol = lerpSilkColor(astroPrimary, nauticalPrimary, f)
            secCol = lerpSilkColor(astroSecondary, nauticalSecondary, f)
            tertCol = lerpSilkColor(astroTertiary, nauticalTertiary, f)
            outCol = lerpSilkColor(astroOutline, nauticalOutline, f)
        }
        sunAlt in -18.0..-12.0 -> {
            phaseName = "Astronomical Twilight"
            val f = ((sunAlt + 18.0) / 6.0).toFloat()
            bgCol = lerpSilkColor(nightBg, astroBg, f)
            surfCol = lerpSilkColor(nightSurface, astroSurface, f)
            surfVarCol = lerpSilkColor(nightSurfaceVar, astroSurfaceVar, f)
            primCol = lerpSilkColor(nightPrimary, astroPrimary, f)
            secCol = lerpSilkColor(nightSecondary, astroSecondary, f)
            tertCol = lerpSilkColor(nightTertiary, astroTertiary, f)
            outCol = lerpSilkColor(nightOutline, astroOutline, f)
        }
        else -> {
            phaseName = "Night (True Dark)"
            bgCol = nightBg; surfCol = nightSurface; surfVarCol = nightSurfaceVar
            primCol = nightPrimary; secCol = nightSecondary; tertCol = nightTertiary
            outCol = nightOutline
        }
    }

    // Dynamic contrast determination based on surface luminance
    val surfaceLuminance = calculateRelativeLuminance(surfCol)
    val isDark = surfaceLuminance < 0.35f
    val (txtPrimCol, txtSecCol, txtTertCol) = deriveDynamicTextColors(surfCol, seasonFactor)

    // 5. Living Astronomical Sheen (Driven strictly by Sun or Moon position)
    val sheenDir: Offset
    val sheenIntensity: Float
    val sheenWidth: Float
    val sheenColor: Color
    val grazingBorderColor: Color

    if (sunAlt > 0.0) {
        // Solar Sheen
        val sunAzRad = Math.toRadians(sunAz)
        sheenDir = Offset(sin(sunAzRad).toFloat(), -cos(sunAzRad).toFloat())

        if (sunAlt >= 25.0) {
            // High Sun: cleaner, softer, even reflection
            val altFactor = (sunAlt / 90.0).toFloat().coerceIn(0.2f, 1.0f)
            sheenIntensity = (0.16f + 0.10f * sqrt(altFactor)).coerceIn(0.15f, 0.28f)
            sheenWidth = 0.75f
            sheenColor = Color(0xFFFFFFFF)
            grazingBorderColor = primCol.copy(alpha = 0.25f)
        } else {
            // Low Sun / Golden Hour: broader, warmer, more directional reflection
            val lowFactor = (1.0f - (sunAlt / 25.0).toFloat()).coerceIn(0f, 1f)
            sheenIntensity = (0.20f + 0.12f * lowFactor).coerceIn(0.20f, 0.35f)
            sheenWidth = 0.55f
            sheenColor = lerpSilkColor(Color(0xFFFEF3C7), Color(0xFFFFFFFF), 1.0f - lowFactor)
            grazingBorderColor = lerpSilkColor(Color(0xFFF59E0B), primCol, 1.0f - lowFactor).copy(alpha = 0.35f)
        }
    } else {
        // Sun below horizon: Solar reflection removed
        if (moonAlt > 0.0) {
            // Cool moonlight sheen driven by Moon position and illumination
            val moonAzRad = Math.toRadians(moonAz)
            sheenDir = Offset(sin(moonAzRad).toFloat(), -cos(moonAzRad).toFloat())
            val moonFactor = (moonAlt / 90.0).toFloat().coerceIn(0.1f, 1.0f)
            val illumFactor = (moonIllum / 100.0).toFloat().coerceIn(0.05f, 1.0f)
            sheenIntensity = (0.04f + 0.08f * sqrt(moonFactor) * (illumFactor.pow(0.75f))).coerceIn(0.03f, 0.14f)
            sheenWidth = 0.65f
            sheenColor = Color(0xFFE2E8F0)
            grazingBorderColor = Color(0xFF38BDF8).copy(alpha = (0.15f * illumFactor).coerceIn(0.08f, 0.25f))
        } else {
            // Pure celestial fabric without specular highlight
            sheenDir = Offset(0f, -1f)
            sheenIntensity = 0.0f
            sheenWidth = 0.8f
            sheenColor = Color.Transparent
            grazingBorderColor = outCol
        }
    }

    return CelestialLighting(
        sunAltitudeDeg = sunAlt,
        sunAzimuthDeg = sunAz,
        moonAltitudeDeg = moonAlt,
        moonAzimuthDeg = moonAz,
        moonIlluminationPercent = moonIllum,
        seasonFactor = seasonFactor,
        dailyPhaseName = phaseName,
        isDark = isDark,
        backgroundColor = bgCol,
        surfaceColor = surfCol,
        surfaceVariantColor = surfVarCol,
        primaryAccent = primCol,
        secondaryAccent = secCol,
        tertiaryAccent = tertCol,
        outlineColor = outCol,
        textPrimaryColor = txtPrimCol,
        textSecondaryColor = txtSecCol,
        textTertiaryColor = txtTertCol,
        sheenDirection = sheenDir,
        sheenIntensity = sheenIntensity,
        sheenWidth = sheenWidth,
        sheenColor = sheenColor,
        grazingBorderColor = grazingBorderColor
    )
}

/**
 * Creates MaterialTheme ColorScheme for Celestial Fabric (Dynamic Silk)
 */
fun createDynamicSilkColorScheme(celestial: CelestialLighting): ColorScheme {
    return if (celestial.isDark) {
        darkColorScheme(
            primary = celestial.primaryAccent,
            onPrimary = celestial.textPrimaryColor,
            primaryContainer = celestial.primaryAccent.copy(alpha = 0.22f),
            onPrimaryContainer = celestial.textPrimaryColor,
            secondary = celestial.secondaryAccent,
            onSecondary = celestial.textPrimaryColor,
            secondaryContainer = celestial.secondaryAccent.copy(alpha = 0.18f),
            onSecondaryContainer = celestial.textPrimaryColor,
            tertiary = celestial.tertiaryAccent,
            onTertiary = celestial.textPrimaryColor,
            background = celestial.backgroundColor,
            onBackground = celestial.textPrimaryColor,
            surface = celestial.surfaceColor,
            onSurface = celestial.textPrimaryColor,
            surfaceVariant = celestial.surfaceVariantColor,
            onSurfaceVariant = celestial.textSecondaryColor,
            outline = celestial.outlineColor,
            outlineVariant = celestial.outlineColor.copy(alpha = 0.5f)
        )
    } else {
        lightColorScheme(
            primary = celestial.primaryAccent,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = celestial.surfaceVariantColor,
            onPrimaryContainer = celestial.textPrimaryColor,
            secondary = celestial.secondaryAccent,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = celestial.surfaceVariantColor,
            onSecondaryContainer = celestial.textPrimaryColor,
            tertiary = celestial.tertiaryAccent,
            onTertiary = Color(0xFFFFFFFF),
            background = celestial.backgroundColor,
            onBackground = celestial.textPrimaryColor,
            surface = celestial.surfaceColor,
            onSurface = celestial.textPrimaryColor,
            surfaceVariant = celestial.surfaceVariantColor,
            onSurfaceVariant = celestial.textSecondaryColor,
            outline = celestial.outlineColor,
            outlineVariant = celestial.outlineColor.copy(alpha = 0.5f)
        )
    }
}

/**
 * Silk Surface Elevation Levels for Visual Hierarchy
 */
enum class SilkElevation {
    HERO,        // Rich woven texture + living directional sheen + grazing starlight border
    PRIMARY_CARD,// Smooth woven silk + gentle directional sheen
    SECONDARY,   // Matte silk + subtle border
    INSET_MATTE, // Inset matte textured fabric
    CONTROL      // Clean interactive silk control
}

/**
 * Custom Modifier for rendering premium woven silk surfaces with living sheen
 */
fun Modifier.silkSurface(
    celestial: CelestialLighting,
    elevation: SilkElevation = SilkElevation.PRIMARY_CARD,
    shape: Shape = squircleShape(16.dp),
    borderWidth: Dp = 1.dp
): Modifier = this.drawBehind {
    drawSilkFabric(
        celestial = celestial,
        elevation = elevation,
        shape = shape,
        borderWidth = borderWidth
    )
}

/**
 * Draws the woven silk material with subtle thread lattice, directional living sheen, and grazing edge.
 */
private fun DrawScope.drawSilkFabric(
    celestial: CelestialLighting,
    elevation: SilkElevation,
    shape: Shape,
    borderWidth: Dp
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    val baseColor = when (elevation) {
        SilkElevation.HERO -> celestial.surfaceColor
        SilkElevation.PRIMARY_CARD -> celestial.surfaceColor
        SilkElevation.SECONDARY -> celestial.surfaceVariantColor
        SilkElevation.INSET_MATTE -> celestial.surfaceVariantColor.copy(alpha = 0.5f)
        SilkElevation.CONTROL -> celestial.surfaceVariantColor
    }

    // 1. Base Silk Fill
    drawRect(color = baseColor)

    // 2. Subtle Dual-Angle Woven Texture (Restrained micro-hatch lattice)
    if (elevation == SilkElevation.HERO || elevation == SilkElevation.PRIMARY_CARD) {
        val weaveAlpha = if (elevation == SilkElevation.HERO) 0.04f else 0.025f
        val weaveBrush1 = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = weaveAlpha),
                Color.Black.copy(alpha = weaveAlpha * 0.8f),
                Color.White.copy(alpha = weaveAlpha)
            ),
            start = Offset(0f, 0f),
            end = Offset(w * 0.5f, h * 0.5f),
            tileMode = TileMode.Repeated
        )
        drawRect(brush = weaveBrush1, blendMode = BlendMode.Overlay)
    }

    // 3. Realistic Living Sheen (Calculated strictly from astronomical Sun/Moon positions)
    if (celestial.sheenIntensity > 0.005f && (elevation == SilkElevation.HERO || elevation == SilkElevation.PRIMARY_CARD || elevation == SilkElevation.CONTROL)) {
        val mult = if (elevation == SilkElevation.HERO) 1.25f else 1.0f
        val intensity = (celestial.sheenIntensity * mult).coerceIn(0f, 0.40f)

        val dirX = celestial.sheenDirection.x
        val dirY = celestial.sheenDirection.y

        val startX = (0.5f - dirX * 0.5f) * w
        val startY = (0.5f - dirY * 0.5f) * h
        val endX = (0.5f + dirX * 0.5f) * w
        val endY = (0.5f + dirY * 0.5f) * h

        val sheenBrush = Brush.linearGradient(
            0.0f to Color.Transparent,
            0.4f to celestial.sheenColor.copy(alpha = intensity * 0.4f),
            0.5f to celestial.sheenColor.copy(alpha = intensity),
            0.6f to celestial.sheenColor.copy(alpha = intensity * 0.4f),
            1.0f to Color.Transparent,
            start = Offset(startX, startY),
            end = Offset(endX, endY)
        )
        drawRect(brush = sheenBrush)
    }

    // 4. Subtle Edge / Grazing-Light Border
    val strokePx = borderWidth.toPx()
    val borderColor = if (elevation == SilkElevation.HERO) celestial.grazingBorderColor else celestial.outlineColor.copy(alpha = 0.45f)
    drawRect(
        color = borderColor,
        style = Stroke(width = strokePx)
    )
}
