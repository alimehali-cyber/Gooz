package com.alijafari.red.astronomy.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import com.alijafari.red.astronomy.domain.ThemeMode

// Material 3 Color Schemes
private val SpaceColorScheme = darkColorScheme(
    primary = RedAccentVermilion,
    onPrimary = Color.White,
    primaryContainer = RedAccentSubtle,
    onPrimaryContainer = TextPrimary,
    secondary = RedCelestialGold,
    onSecondary = Color.White,
    tertiary = RedCelestialBlue,
    onTertiary = Color.White,
    background = BackgroundPrimary,
    onBackground = TextPrimary,
    surface = BackgroundCard,
    onSurface = TextPrimary,
    surfaceVariant = Color(0xFF171824),
    onSurfaceVariant = TextSecondary,
    outline = CardBorder
)

private val OledSpaceColorScheme = darkColorScheme(
    primary = RedAccentVermilion,
    onPrimary = Color.White,
    primaryContainer = RedAccentSubtle,
    onPrimaryContainer = Color.White,
    secondary = RedCelestialGold,
    onSecondary = Color.White,
    tertiary = RedCelestialBlue,
    onTertiary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF0C0C10),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF27272A)
)

private val LightSpaceColorScheme = lightColorScheme(
    primary = RedAccentLight,
    onPrimary = Color.White,
    primaryContainer = SoftCreamVariant,
    onPrimaryContainer = RedAccentLight,
    secondary = OldMoneyChampagneGold,
    onSecondary = Color.White,
    tertiary = RedCelestialBlue,
    onTertiary = Color.White,
    background = PureWhiteBackground,
    onBackground = OldMoneySlate,
    surface = SoftCreamSurface,
    onSurface = OldMoneySlate,
    surfaceVariant = Color(0xFFF3EFE6),
    onSurfaceVariant = OldMoneySlateMuted,
    outline = SoftCreamCardBorder
)

private val PapercraftPastelColorScheme = lightColorScheme(
    primary = PapercraftPrimary,
    onPrimary = Color.White,
    primaryContainer = PapercraftSurfaceVariant,
    onPrimaryContainer = PapercraftPrimary,
    secondary = PapercraftSecondary,
    onSecondary = PapercraftTextPrimary,
    tertiary = PapercraftTertiary,
    onTertiary = Color.White,
    background = PapercraftBackground,
    onBackground = PapercraftTextPrimary,
    surface = PapercraftSurface,
    onSurface = PapercraftTextPrimary,
    surfaceVariant = PapercraftSurfaceVariant,
    onSurfaceVariant = PapercraftTextSecondary,
    outline = PapercraftOutline
)

private fun lerpColor(c1: Color, c2: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = c1.red + (c2.red - c1.red) * f,
        green = c1.green + (c2.green - c1.green) * f,
        blue = c1.blue + (c2.blue - c1.blue) * f,
        alpha = c1.alpha + (c2.alpha - c1.alpha) * f
    )
}

/**
 * WCAG relative luminance of a color, with the sRGB transfer function applied (the plain 0.299/0.587/
 * 0.114 weighting used for the glass ink is a brightness estimate, not a contrast measure).
 */
private fun relativeLuminance(color: Color): Float {
    fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    return 0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
}

/** WCAG contrast ratio between two opaque colors: 1.0 (identical) to 21.0 (black on white). */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Composites a translucent color over an opaque one, the way the screen will show it. */
private fun over(base: Color, overlay: Color): Color {
    val a = overlay.alpha
    return Color(
        red = base.red + (overlay.red - base.red) * a,
        green = base.green + (overlay.green - base.green) * a,
        blue = base.blue + (overlay.blue - base.blue) * a,
        alpha = 1f
    )
}

/** The two inks a theme can draw on — near-black or near-white, chosen from the surface's luminance. */
private fun inkFor(surface: Color): Color =
    if (relativeLuminance(surface) > 0.35f) Color(0xFF090B12) else Color(0xFFF7F9FF)

/**
 * The ink for a surface: `preferred` when it really reads on `background`, otherwise the theme's own ink
 * at the given weight.
 *
 * This is what makes "text is readable in every theme" a property of the theme instead of a promise. The
 * bundled light palette's `textQuaternary` (#D1D5DB) on its own #F8F9FA surface measures 1.4:1 and simply
 * cannot be read; a muted step loses the same way whenever a dynamic theme substitutes surfaces. Rather
 * than hope every call site picked a readable step, each text token is measured against the surface it is
 * drawn on and repaired when it fails.
 */
private fun readableToken(
    preferred: Color,
    background: Color,
    ink: Color,
    minRatio: Float,
    inkAlpha: Float
): Color = if (contrastRatio(over(background, preferred), background) >= minRatio) {
    preferred
} else {
    ink.copy(alpha = inkAlpha)
}

/**
 * An accent that survives its surface. Instead of swapping the hue out for something generic, the color
 * is walked toward black (on a light surface) or white (on a dark one) until it clears [minRatio], so a
 * gold chip stays gold and a blue icon stays blue — just readable.
 */
private fun repairedAccent(color: Color, background: Color, minRatio: Float): Color {
    if (contrastRatio(over(background, color), background) >= minRatio) return color
    val target = if (relativeLuminance(background) > 0.35f) Color.Black else Color.White
    var best = color
    for (step in 1..24) {
        val f = step / 24f
        best = Color(
            red = color.red + (target.red - color.red) * f,
            green = color.green + (target.green - color.green) * f,
            blue = color.blue + (target.blue - color.blue) * f,
            alpha = 1f
        )
        if (contrastRatio(best, background) >= minRatio) return best
    }
    return best
}

/**
 * Repairs a token set so its text hierarchy and accents are legible on its own surfaces.
 *
 * The measuring surface is deliberately the *hardest* card background for the ink this theme uses: the
 * lightest surface when the ink is light, the darkest one when the ink is dark. Anything that clears the
 * hard case clears the easy one too.
 */
private fun withReadableInk(tokens: RedColorTokens): RedColorTokens {
    val ink = inkFor(tokens.surfaceElevated)
    val prefersDarkInk = relativeLuminance(tokens.surfaceElevated) > 0.35f
    val hardBackground = if (prefersDarkInk) {
        if (relativeLuminance(tokens.surfaceElevated) < relativeLuminance(tokens.surfaceGrouped)) tokens.surfaceElevated else tokens.surfaceGrouped
    } else {
        if (relativeLuminance(tokens.surfaceElevated) > relativeLuminance(tokens.surfaceGrouped)) tokens.surfaceElevated else tokens.surfaceGrouped
    }
    return tokens.copy(
        textPrimary = readableToken(tokens.textPrimary, hardBackground, ink, 4.5f, 1f),
        textSecondary = readableToken(tokens.textSecondary, hardBackground, ink, 3.0f, 0.78f),
        textTertiary = readableToken(tokens.textTertiary, hardBackground, ink, 2.2f, 0.62f),
        textQuaternary = readableToken(tokens.textQuaternary, hardBackground, ink, 1.7f, 0.46f),
        accentRed = repairedAccent(tokens.accentRed, hardBackground, 3.0f),
        accentGold = repairedAccent(tokens.accentGold, hardBackground, 3.0f),
        accentBlue = repairedAccent(tokens.accentBlue, hardBackground, 3.0f),
        accentPurple = repairedAccent(tokens.accentPurple, hardBackground, 3.0f),
        statusSuccess = repairedAccent(tokens.statusSuccess, hardBackground, 3.0f),
        statusWarning = repairedAccent(tokens.statusWarning, hardBackground, 3.0f),
        statusError = repairedAccent(tokens.statusError, hardBackground, 3.0f),
        statusInfo = repairedAccent(tokens.statusInfo, hardBackground, 3.0f),
        // Translucent hairlines are meant to be subtle, so only an opaque border is held to the floor.
        border = if (tokens.border.alpha > 0.9f && contrastRatio(tokens.border, tokens.background) < 1.25f) {
            if (relativeLuminance(tokens.background) > 0.35f) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.16f)
        } else {
            tokens.border
        }
    )
}

fun createDynamicSkyColorScheme(sunAltitudeDeg: Double): androidx.compose.material3.ColorScheme {
    // 4 Key Lighting States
    val dayBg = Color(0xFF0F2B48)
    val daySurf = Color(0xFF16375B)
    val daySurfVar = Color(0xFF1E4571)
    val dayPrimary = Color(0xFF38BDF8)
    val daySecondary = Color(0xFF60A5FA)
    val dayText = Color(0xFFF8FAFC)

    val sunsetBg = Color(0xFF2E122D)
    val sunsetSurf = Color(0xFF3F193C)
    val sunsetSurfVar = Color(0xFF5B2252)
    val sunsetPrimary = Color(0xFFF97316)
    val sunsetSecondary = Color(0xFFFB923C)
    val sunsetText = Color(0xFFFFF7ED)

    val twilightBg = Color(0xFF0F172A)
    val twilightSurf = Color(0xFF1E293B)
    val twilightSurfVar = Color(0xFF334155)
    val twilightPrimary = Color(0xFF818CF8)
    val twilightSecondary = Color(0xFFA5B4FC)
    val twilightText = Color(0xFFF8FAFC)

    val nightBg = Color(0xFF0B0F19)
    val nightSurf = Color(0xFF111827)
    val nightSurfVar = Color(0xFF1F2937)
    val nightPrimary = RedAccentVermilion
    val nightSecondary = Color(0xFFC084FC)
    val nightText = Color(0xFFF8FAFC)

    val (bgTriple, accentTriple) = when {
        sunAltitudeDeg >= 6.0 -> {
            val f = ((sunAltitudeDeg - 6.0) / 12.0).toFloat().coerceIn(0f, 1f)
            Triple(
                lerpColor(dayBg, dayBg, f),
                lerpColor(daySurf, daySurf, f),
                lerpColor(daySurfVar, daySurfVar, f)
            ) to Triple(
                lerpColor(dayPrimary, dayPrimary, f),
                lerpColor(daySecondary, daySecondary, f),
                lerpColor(dayText, dayText, f)
            )
        }
        sunAltitudeDeg in 0.0..6.0 -> {
            val f = (sunAltitudeDeg / 6.0).toFloat()
            Triple(
                lerpColor(sunsetBg, dayBg, f),
                lerpColor(sunsetSurf, daySurf, f),
                lerpColor(sunsetSurfVar, daySurfVar, f)
            ) to Triple(
                lerpColor(sunsetPrimary, dayPrimary, f),
                lerpColor(sunsetSecondary, daySecondary, f),
                lerpColor(sunsetText, dayText, f)
            )
        }
        sunAltitudeDeg in -12.0..0.0 -> {
            val f = ((sunAltitudeDeg + 12.0) / 12.0).toFloat()
            Triple(
                lerpColor(twilightBg, sunsetBg, f),
                lerpColor(twilightSurf, sunsetSurf, f),
                lerpColor(twilightSurfVar, sunsetSurfVar, f)
            ) to Triple(
                lerpColor(twilightPrimary, sunsetPrimary, f),
                lerpColor(twilightSecondary, sunsetSecondary, f),
                lerpColor(twilightText, sunsetText, f)
            )
        }
        sunAltitudeDeg in -18.0..-12.0 -> {
            val f = ((sunAltitudeDeg + 18.0) / 6.0).toFloat()
            Triple(
                lerpColor(nightBg, twilightBg, f),
                lerpColor(nightSurf, twilightSurf, f),
                lerpColor(nightSurfVar, twilightSurfVar, f)
            ) to Triple(
                lerpColor(nightPrimary, twilightPrimary, f),
                lerpColor(nightSecondary, twilightSecondary, f),
                lerpColor(nightText, twilightText, f)
            )
        }
        else -> {
            Triple(nightBg, nightSurf, nightSurfVar) to Triple(nightPrimary, nightSecondary, nightText)
        }
    }

    val bg = bgTriple.first
    val surface = bgTriple.second
    val surfaceVar = bgTriple.third
    val primaryColor = accentTriple.first
    val secondaryColor = accentTriple.second
    val textColor = accentTriple.third

    return darkColorScheme(
        primary = primaryColor,
        onPrimary = textColor,
        primaryContainer = primaryColor.copy(alpha = 0.2f),
        onPrimaryContainer = textColor,
        secondary = secondaryColor,
        onSecondary = textColor,
        tertiary = AccentTertiary,
        onTertiary = textColor,
        background = bg,
        onBackground = textColor,
        surface = surface,
        onSurface = textColor,
        surfaceVariant = surfaceVar,
        onSurfaceVariant = TextSecondary,
        outline = primaryColor.copy(alpha = 0.4f)
    )
}

@Composable
fun REDTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    sunAltitudeDeg: Double = -20.0,
    userLatitude: Double = 30.1141,
    userLongitude: Double = 51.5217,
    timestampMs: Long = System.currentTimeMillis(),
    isPersian: Boolean = false,
    content: @Composable () -> Unit
) {
    val celestialLighting = remember(timestampMs, userLatitude, userLongitude) {
        calculateCelestialLighting(timestampMs, userLatitude, userLongitude)
    }

    val colorScheme = when (themeMode) {
        ThemeMode.GLASS -> SpaceColorScheme
        ThemeMode.LIGHT -> LightSpaceColorScheme
        ThemeMode.DARK -> OledSpaceColorScheme
    }

    val redColorTokens = when (themeMode) {
        ThemeMode.GLASS -> RedDarkColorTokens
        ThemeMode.LIGHT -> RedLightColorTokens
        ThemeMode.DARK -> RedDarkColorTokens.copy(
            background = Color.Black,
            backgroundSecondary = Color(0xFF060608),
            backgroundTertiary = Color(0xFF0C0C10),
            surface = Color.Black,
            surfaceElevated = Color(0xFF0E0E12),
            surfaceGrouped = Color(0xFF14141A),
            surfaceVariant = Color(0xFF0A0A0E),
            border = Color(0xFF27272A),
            separator = Color(0xFF18181B)
        )
    }

    // Every token set goes through the contrast repair, so no theme — bundled or dynamic — can hand a
    // call site text or accent ink that dies on the surface it is drawn on.
    val readableColorTokens = withReadableInk(redColorTokens)

    // Material's ink roles are aligned with those tokens: screens exist that put `colorScheme.onSurface`
    // text on `RedTheme.colors` surfaces (and the reverse), so the two palettes must agree on what ink is.
    val readableMaterialScheme = colorScheme.copy(
        onBackground = readableColorTokens.textPrimary,
        onSurface = readableColorTokens.textPrimary,
        onSurfaceVariant = readableColorTokens.textSecondary
    )

    // The app's one and only type-face decision: Persian renders in Vazirmatn, English keeps the
    // platform default face. Two channels, both fed from that single rule, so no call site anywhere
    // has to care:
    //  * the Material 3 Typography object -> every MaterialTheme.typography.* usage, and every plain
    //    Text() / custom-token Text(style = ...) call, because MaterialTheme exposes typography's
    //    bodyLarge as the ambient text style that those styles merge onto (a TextStyle only overrides
    //    properties it sets itself, so the RED tokens in Type.kt deliberately leave fontFamily unset).
    //  * LocalAppFontFamily -> text measured straight onto a Canvas, which never merges that ambient
    //    style (CompassARScreen / ISSScreen labels).
    val appFontFamily = redFontFamily(isPersian)

    CompositionLocalProvider(
        LocalCelestialLighting provides celestialLighting,
        LocalRedColors provides readableColorTokens,
        LocalRedSpacing provides RedSpacing,
        LocalRedRadius provides RedCornerRadius,
        LocalRedElevation provides RedElevation,
        LocalAppFontFamily provides appFontFamily
    ) {
        MaterialTheme(
            colorScheme = readableMaterialScheme,
            typography = redTypographyFor(isPersian),
            // Every Material surface that reads MaterialTheme.shapes — Card, AlertDialog, bottom
            // sheets, text fields, menus, chips — inherits the app's squircle corners from here
            // instead of Material's circular-arc defaults, so there is one corner geometry in the
            // app and no component can silently reintroduce a G1 join.
            shapes = RedShapes.material,
            content = {
                // Material 3's Text uses whatever `style` it is handed *as-is* — it does not merge the
                // theme's ambient style into an explicitly passed one — so the typography swap above
                // only reaches call sites that read MaterialTheme.typography. Re-providing the ambient
                // style with the locale face is what covers every Text() that passes no style at all
                // (placeholders, bare labels). .copy() only replaces the family, so sizes, weights and
                // line heights inherited from the theme stay exactly as they were.
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = appFontFamily),
                    content = content
                )
            }
        )
    }
}
