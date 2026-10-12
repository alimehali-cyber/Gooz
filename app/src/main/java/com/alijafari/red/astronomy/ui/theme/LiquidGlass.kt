package com.alijafari.red.astronomy.ui.theme

/**
 * ============================================================================
 * LIQUID GLASS PLACEMENT RULES
 * ============================================================================
 *
 * 1. WHERE DOES A COMPONENT BELONG?
 *    - Inside Crossfade (Tabs / Screens / Content Cards):
 *      Any screen or card inside `Crossfade(targetState = uiState.selectedTab)`
 *      in MainActivity is wrapped in `Modifier.layerBackdrop(backdrop)` and
 *      scoped to `CompositionLocalProvider(LocalLiquidGlassBackdrop provides null)`.
 *      These components MUST NOT attempt to call `drawBackdrop()` on that same root
 *      `backdrop` instance (doing so causes the fatal Kyant assertion:
 *      "Can not perform this action inside of drawBackdrop!"). They safely render
 *      high-contrast premium RED design tokens via their `fallbackColor`/`fallbackBorder`.
 *    - Root-Level Sibling Overlays (Floating Bars, Modals, Full-Screen Dialogs):
 *      Components that should render real Liquid Glass optical effects (sampling the
 *      live, animated screen content beneath them) MUST sit OUTSIDE the Crossfade tree
 *      as direct siblings in `MainActivity.kt`'s root Box (e.g., `FloatingBottomBar`,
 *      `LocationSelectorDialog`).
 *
 * 2. EXPLICIT PARAMETER PASSING MANDATE:
 *    - Always pass the root `backdrop: Backdrop?` as an EXPLICIT parameter down into
 *      the overlay composable and forward it to every internal `LiquidGlassSurface(backdrop = backdrop, ...)`.
 *    - NEVER rely on `LocalLiquidGlassBackdrop.current` for components intended to render
 *      real glass, since ambient composition local resolution is intentionally null-scoped
 *      inside the main content tree.
 *
 * 3. CONFIGURATION & CUSTOMIZATION (LocalLiquidGlassConfig / LocalLiquidGlassEnabled):
 *    - `LocalLiquidGlassConfig` (blur, refraction depth/warping, chromatic aberration,
 *      clarity) and `LocalLiquidGlassEnabled` are provided at the root and are NOT scoped
 *      to null anywhere.
 *    - All `LiquidGlassSurface` composables automatically pick up Settings customizations
 *      and gracefully adapt without extra wiring.
 * ============================================================================
 */

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

val LocalLiquidGlassEnabled = staticCompositionLocalOf { true }
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * How much of the theme's own surface a real-glass card lays under its content so the text on it keeps
 * its contrast over an unknown backdrop. Low enough to stay glass, high enough to hold the ink.
 */
private const val LEGIBILITY_PLATE_ALPHA = 0.34f

/**
 * User-configurable Liquid Glass optical characteristics.
 * Persisted in SharedPreferences and dynamically applied across all liquid glass surfaces.
 */
@Immutable
data class LiquidGlassConfig(
    val enabled: Boolean = true,
    val clarity: Float = 1.0f, // 0.0 (frosted) to 1.0 (crystal pure)
    val blurRadiusDp: Float = 0f, // 0 to 24 dp
    val refractionHeightDp: Float = 28f, // 0 to 60 dp
    val refractionAmountDp: Float = 28f, // 0 to 60 dp (refraction warping)
    val chromaticAberration: Boolean = true,
    val hasHighlight: Boolean = true,
    val hasShadow: Boolean = true,
    /**
     * Strength of the soft white tint every glass card wears (0.0 = none, 1.0 = opaque white).
     * The default is deliberately faint: it lifts a card off the sky without hiding it.
     * Surfaces that must stay optically pure opt out through `clearGlass`.
     */
    val tintAlpha: Float = DEFAULT_TINT_ALPHA
) {
    companion object {
        /** The "very soft, slight white tint" the design system ships with. */
        const val DEFAULT_TINT_ALPHA: Float = 0.07f
    }
}

val LocalLiquidGlassConfig = staticCompositionLocalOf { LiquidGlassConfig() }

/**
 * The soft white tint a glass surface wears, straight from Settings.
 * Returns [Color.Transparent] at zero so callers can skip the draw entirely.
 */
@Composable
@ReadOnlyComposable
fun liquidGlassTintColor(
    alphaOverride: Float? = null,
    config: LiquidGlassConfig = LocalLiquidGlassConfig.current
): Color {
    val alpha = (alphaOverride ?: config.tintAlpha).coerceIn(0f, 1f)
    return if (alpha <= 0.001f) Color.Transparent else Color.White.copy(alpha = alpha)
}

/**
 * Text colors that stay legible on top of glass, whatever the theme, the backdrop or the tint setting.
 *
 * Glass is translucent, so a fixed text color can end up on top of a bright day sky in a dark theme or
 * on top of a heavy white tint in a light one. This resolves the effective card color (the theme surface
 * the legibility plate holds in place, plus the user's white tint) and picks near-black or near-white
 * text from its luminance, so contrast never depends on the caller guessing.
 */
@Immutable
data class GlassTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color
)

@Composable
@ReadOnlyComposable
fun liquidGlassTextColors(
    config: LiquidGlassConfig = LocalLiquidGlassConfig.current,
    tintAlpha: Float? = null,
    clearGlass: Boolean = false
): GlassTextColors {
    val surface = RedTheme.colors.surfaceElevated
    val tint = if (clearGlass) 0f else (tintAlpha ?: config.tintAlpha).coerceIn(0f, 1f)

    fun over(base: Color, overlay: Color, fraction: Float): Color = Color(
        red = base.red + (overlay.red - base.red) * fraction,
        green = base.green + (overlay.green - base.green) * fraction,
        blue = base.blue + (overlay.blue - base.blue) * fraction,
        alpha = 1f
    )

    // The card this ink will sit on: the theme surface token (which the legibility plate in
    // [liquidGlass] keeps dominant even over real glass) plus the user's white tint on top of it. The
    // clarity frost adds the same surface token at partial alpha, so it cannot move this estimate.
    val effective = over(surface, Color.White, tint)
    val luminance = 0.299f * effective.red + 0.587f * effective.green + 0.114f * effective.blue
    val ink = if (luminance > 0.55f) Color(0xFF080A12) else Color(0xFFF8FAFF)
    return GlassTextColors(
        primary = ink,
        secondary = ink.copy(alpha = 0.74f),
        tertiary = ink.copy(alpha = 0.56f)
    )
}

/**
 * Android version capability check for native Backdrop shaders.
 * Liquid Glass physical refraction requires Android 13+ (API 33+).
 */
fun isLiquidGlassSupported(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

/**
 * RED Liquid Glass Optical Specification
 *
 * Real Liquid Glass material specification based on Kyant0 Backdrop architecture:
 * - Real-time background pixel sampling & optical refraction
 * - Spatial vibrancy enhancement
 * - Configurable Gaussian blur diffusion
 * - Convex / concave lens distortion with optional chromatic dispersion
 * - Ambient highlight, external ambient shadow, and internal bevel shadow
 */
@Immutable
data class LiquidGlassStyle(
    val blurRadius: Dp = 8.dp,
    val refractionHeight: Dp = 24.dp,
    val refractionAmount: Dp = 24.dp,
    val chromaticAberration: Boolean = false,
    val hasHighlight: Boolean = true,
    val shadowRadius: Dp = 12.dp,
    val innerShadowRadius: Dp = 2.dp
)

object LiquidGlassDefaults {
    val NavigationBar = LiquidGlassStyle(
        blurRadius = 0.dp,
        refractionHeight = 28.dp,
        refractionAmount = 28.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 12.dp,
        innerShadowRadius = 2.dp
    )

    val Header = LiquidGlassStyle(
        blurRadius = 0.dp,
        refractionHeight = 22.dp,
        refractionAmount = 22.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 8.dp,
        innerShadowRadius = 1.5.dp
    )

    val Pill = LiquidGlassStyle(
        blurRadius = 0.dp,
        refractionHeight = 16.dp,
        refractionAmount = 16.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 6.dp,
        innerShadowRadius = 1.5.dp
    )

    val Card = LiquidGlassStyle(
        blurRadius = 1.dp,
        refractionHeight = 22.dp,
        refractionAmount = 22.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 8.dp,
        innerShadowRadius = 2.dp
    )

    val Modal = LiquidGlassStyle(
        blurRadius = 2.dp,
        refractionHeight = 28.dp,
        refractionAmount = 28.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 16.dp,
        innerShadowRadius = 3.dp
    )

    val Window = LiquidGlassStyle(
        blurRadius = 1.dp, // 1 / 24 blur intensity for floating sheets and dialog backdrop
        refractionHeight = 16.dp,
        refractionAmount = 16.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 16.dp,
        innerShadowRadius = 2.dp
    )

    val LocationCard = LiquidGlassStyle(
        blurRadius = 12.dp, // 12 / 24 blur intensity for location cards
        refractionHeight = 24.dp,
        refractionAmount = 24.dp,
        chromaticAberration = true,
        hasHighlight = true,
        shadowRadius = 8.dp,
        innerShadowRadius = 1.5.dp
    )
}

/**
 * Reusable Liquid Glass Modifier.
 *
 * Applies real-time backdrop sampling, lens refraction, vibrancy, blur,
 * highlights, and shadows when enabled & supported.
 * Falls back cleanly to premium RED design system surface tokens on Android 12-
 * or when disabled by user preference.
 */
@Composable
fun Modifier.liquidGlass(
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    shape: Shape = RoundedCornerShape(RedCornerRadius.window),
    style: LiquidGlassStyle = LiquidGlassDefaults.NavigationBar,
    fallbackColor: Color = RedTheme.colors.surfaceElevated,
    fallbackBorder: BorderStroke = BorderStroke(1.dp, RedTheme.colors.border),
    fallbackShadowElevation: Dp = RedElevation.floating,
    glassTint: Color = Color.Transparent,
    glassBorder: BorderStroke? = BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.5f)),
    clearGlass: Boolean = false,
    enabled: Boolean = LocalLiquidGlassEnabled.current
): Modifier {
    val config = LocalLiquidGlassConfig.current
    val isGlassActive = enabled && config.enabled && isLiquidGlassSupported() && backdrop != null

    // Legibility plate. Real glass samples pixels this surface cannot know — a bright day sky behind a
    // dark-theme card would leave light text on a bright plate. A partly transparent wash of the theme's
    // own surface keeps the card's effective color near the token that [liquidGlassTextColors] resolves
    // the text against, so contrast survives any backdrop. Surfaces that must stay optically pure
    // (`clearGlass`, i.e. the Sky Canvas card) opt out and stay fully clear.
    val legibilityPlate =
        if (clearGlass) Color.Transparent else RedTheme.colors.surfaceElevated.copy(alpha = LEGIBILITY_PLATE_ALPHA)

    // One tint decision for both render paths: an explicit `glassTint` wins, a surface that must stay
    // optically pure (`clearGlass`) wears none, and everything else takes the soft white tint from Settings.
    val activeTint = when {
        glassTint != Color.Transparent -> glassTint
        clearGlass -> Color.Transparent
        else -> liquidGlassTintColor(config = config)
    }

    return if (isGlassActive) {
        // Dynamic blur radius
        val activeBlur = (config.blurRadiusDp + style.blurRadius.value).coerceAtLeast(0f).dp

        // Dynamic refraction depth & warping amount scaled by user preferences
        val baseRefractionHeight = style.refractionHeight.value
        val activeRefractionHeight = (config.refractionHeightDp * (if (baseRefractionHeight > 0f) baseRefractionHeight / 28f else 1f)).coerceAtLeast(0f).dp

        val baseRefractionAmount = style.refractionAmount.value
        val activeRefractionAmount = (config.refractionAmountDp * (if (baseRefractionAmount > 0f) baseRefractionAmount / 28f else 1f)).coerceAtLeast(0f).dp

        val activeChromatic = config.chromaticAberration && style.chromaticAberration
        val activeHighlight = config.hasHighlight && style.hasHighlight
        val activeShadow = if (config.hasShadow) style.shadowRadius else 0.dp
        val activeInnerShadow = if (config.hasShadow) style.innerShadowRadius else 0.dp

        // Clarity frosted tint: when clarity < 1.0f, introduce frosted tint opacity
        val clarityFrostedColor = if (config.clarity < 0.99f) {
            RedTheme.colors.surfaceElevated.copy(alpha = (1.0f - config.clarity) * 0.45f)
        } else {
            Color.Transparent
        }

        this
            .drawBackdrop(
                backdrop = backdrop!!,
                shape = { shape },
                effects = {
                    vibrancy()
                    if (activeBlur > 0.dp) {
                        blur(activeBlur.toPx())
                    }
                    if (activeRefractionHeight > 0.dp || activeRefractionAmount > 0.dp) {
                        lens(
                            refractionHeight = activeRefractionHeight.toPx(),
                            refractionAmount = activeRefractionAmount.toPx(),
                            chromaticAberration = activeChromatic
                        )
                    }
                },
                highlight = if (activeHighlight) { { Highlight.Ambient } } else null,
                shadow = if (activeShadow > 0.dp) { { Shadow(radius = activeShadow) } } else null,
                innerShadow = if (activeInnerShadow > 0.dp) { { InnerShadow(radius = activeInnerShadow) } } else null
            )
            .then(if (clarityFrostedColor != Color.Transparent) Modifier.background(clarityFrostedColor, shape) else Modifier)
            .then(if (legibilityPlate != Color.Transparent) Modifier.background(legibilityPlate, shape) else Modifier)
            .then(if (activeTint != Color.Transparent) Modifier.background(activeTint, shape) else Modifier)
            .then(if (glassBorder != null) Modifier.border(glassBorder, shape) else Modifier)
            .clip(shape)
    } else {
        this
            .then(if (fallbackShadowElevation > 0.dp) Modifier.shadow(fallbackShadowElevation, shape) else Modifier)
            .background(fallbackColor, shape)
            .then(if (activeTint != Color.Transparent) Modifier.background(activeTint, shape) else Modifier)
            .border(fallbackBorder, shape)
            .clip(shape)
    }
}

/**
 * Reusable Native Liquid Glass Surface Composable with built-in responsive touch feedback,
 * high-contrast styling, and automatic RED premium fallback.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
    style: LiquidGlassStyle = LiquidGlassDefaults.Card,
    shape: Shape = RoundedCornerShape(RedCornerRadius.window),
    fallbackColor: Color = RedTheme.colors.surfaceElevated,
    fallbackBorder: BorderStroke = BorderStroke(1.dp, RedTheme.colors.border),
    fallbackShadowElevation: Dp = RedElevation.floating,
    glassTint: Color = Color.Transparent,
    glassBorder: BorderStroke? = BorderStroke(0.75.dp, RedTheme.colors.border.copy(alpha = 0.5f)),
    clearGlass: Boolean = false,
    enabled: Boolean = LocalLiquidGlassEnabled.current,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && onClick != null) 0.97f else 1.0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "LiquidGlassPress"
    )

    // Readability is solved once, here, instead of at every call site: the token set this surface hands
    // down carries text colors resolved against the glass it is actually drawn on (surface + frost + the
    // user's tint). Every child that reads RedTheme.colors.textPrimary/Secondary/Tertiary — section
    // headers, badges, metric rows — inherits a contrast-safe color without knowing glass exists.
    val glassText = liquidGlassTextColors(clearGlass = clearGlass)
    val surfaceColors = RedTheme.colors.copy(
        textPrimary = glassText.primary,
        textSecondary = glassText.secondary,
        textTertiary = glassText.tertiary
    )

    Box(
        modifier = modifier
            .scale(pressScale)
            .liquidGlass(
                backdrop = backdrop,
                shape = shape,
                style = style,
                fallbackColor = fallbackColor,
                fallbackBorder = fallbackBorder,
                fallbackShadowElevation = fallbackShadowElevation,
                glassTint = glassTint,
                glassBorder = glassBorder,
                clearGlass = clearGlass,
                enabled = enabled
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
    ) {
        CompositionLocalProvider(LocalRedColors provides surfaceColors) {
            content()
        }
    }
}











