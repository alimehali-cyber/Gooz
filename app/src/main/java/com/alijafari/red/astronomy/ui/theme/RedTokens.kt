package com.alijafari.red.astronomy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * RED Design System - Central Design Tokens
 * Follows modern Apple-inspired design principles (restraint, strong hierarchy,
 * concentric geometry, adaptive surfaces, content-first layout) without copying iOS.
 */

// Spacing Scale (Standard 4dp/8dp base grid)
object RedSpacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val quad: Dp = 40.dp
    val section: Dp = 48.dp
}

// Concentric Corner Radii Scale
// Outer radius R -> Nested element radius r = R - padding
object RedCornerRadius {
    val none: Dp = 0.dp
    val xs: Dp = 6.dp       // Small badges, tags
    val sm: Dp = 10.dp      // Inner items, nested chips, small controls
    val md: Dp = 14.dp      // Standard buttons, text inputs, nested section blocks
    /**
     * The one radius every card and window in the app wears — the Home moon card set the standard.
     * [lg], [xl] and [xxl] are aliases of it so the three historical "container" steps of the scale
     * cannot drift apart again: a card is a card, whichever token the call site happens to use.
     * Small controls above keep their own radii, so the concentric hierarchy still reads.
     */
    val window: Dp = 56.dp
    val lg: Dp = window     // Primary cards, list grouped containers
    val xl: Dp = window     // Hero cards, sheet dialogs
    val xxl: Dp = window    // Floating bars, modals
    val pill: Dp = 999.dp   // Capsule / pill buttons
    val full: Dp = 999.dp   // Fully rounded
}

// Restrained Elevation / Depth Scale
object RedElevation {
    val none: Dp = 0.dp
    val subtle: Dp = 1.dp
    val card: Dp = 2.dp
    val popover: Dp = 4.dp
    val modal: Dp = 8.dp
    val floatingNav: Dp = 6.dp
    val floating: Dp = 6.dp
}

// Standard Icon Sizes
object RedIconSize {
    val xs: Dp = 14.dp
    val sm: Dp = 18.dp
    val md: Dp = 22.dp
    val lg: Dp = 28.dp
    val xl: Dp = 36.dp
}

// Standard Control Heights (Respects min 44dp/48dp touch targets)
object RedControlHeight {
    val compact: Dp = 32.dp
    val standard: Dp = 40.dp
    val regular: Dp = 44.dp     // Minimum interactive touch target
    val prominent: Dp = 48.dp
    val large: Dp = 56.dp
}

/**
 * Adaptive Color Tokens for RED Design System
 */
data class RedColorTokens(
    val isDark: Boolean,
    // Backgrounds
    val background: Color,
    val backgroundSecondary: Color,
    val backgroundTertiary: Color,
    // Surfaces
    val surface: Color,
    val surfaceElevated: Color,
    val surfaceGrouped: Color,
    val surfaceVariant: Color,
    // Text Hierarchy
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textQuaternary: Color,
    // Borders & Hairlines
    val border: Color,
    val separator: Color,
    val separatorOpaque: Color,
    // Signature RED Brand Accent
    val accentRed: Color,
    val accentRedSubtle: Color,
    // Secondary & Tertiary Celestial Accents
    val accentGold: Color,
    val accentBlue: Color,
    val accentPurple: Color,
    // Semantic Status Colors
    val statusSuccess: Color,
    val statusSuccessContainer: Color,
    val statusWarning: Color,
    val statusWarningContainer: Color,
    val statusError: Color,
    val statusErrorContainer: Color,
    val statusInfo: Color,
    val statusInfoContainer: Color,
    // Navigation & Overlay
    val navSurface: Color,
    val navBorder: Color
)

// Default Dark Palette (Obsidian / Deep Slate)
val RedDarkColorTokens = RedColorTokens(
    isDark = true,
    background = Color(0xFF090A0F),
    backgroundSecondary = Color(0xFF0F1017),
    backgroundTertiary = Color(0xFF151722),
    surface = Color(0xFF12131C),
    surfaceElevated = Color(0xFF1A1C28),
    surfaceGrouped = Color(0xFF202332),
    surfaceVariant = Color(0xFF171824),
    textPrimary = Color(0xFFFAF9F6),
    textSecondary = Color(0xFFB0B4C0),
    textTertiary = Color(0xFF7A8090),
    textQuaternary = Color(0xFF4C5262),
    border = Color(0x1FFFFFFF),             // 12% subtle white border
    separator = Color(0x14FFFFFF),          // 8% hairline separator
    separatorOpaque = Color(0xFF222432),
    accentRed = Color(0xFFE53935),          // Refined RED vermilion crimson
    accentRedSubtle = Color(0x28E53935),
    accentGold = Color(0xFFD4AF37),
    accentBlue = Color(0xFF38BDF8),
    accentPurple = Color(0xFFA855F7),
    statusSuccess = Color(0xFF34C759),      // System Green
    statusSuccessContainer = Color(0x2634C759),
    statusWarning = Color(0xFFFF9F0A),      // System Amber
    statusWarningContainer = Color(0x26FF9F0A),
    statusError = Color(0xFFFF453A),        // System Red
    statusErrorContainer = Color(0x26FF453A),
    statusInfo = Color(0xFF0A84FF),         // System Blue
    statusInfoContainer = Color(0x260A84FF),
    navSurface = Color(0xF012131C),
    navBorder = Color(0x26FFFFFF)
)

// Default Light Palette (Pure Light & Warm Ivory)
val RedLightColorTokens = RedColorTokens(
    isDark = false,
    background = Color(0xFFF8F9FA),
    backgroundSecondary = Color(0xFFF1F3F5),
    backgroundTertiary = Color(0xFFE9ECEF),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF8F9FA),
    surfaceGrouped = Color(0xFFF1F3F5),
    surfaceVariant = Color(0xFFF3EFE6),
    textPrimary = Color(0xFF111827),
    textSecondary = Color(0xFF4B5563),
    textTertiary = Color(0xFF9CA3AF),
    textQuaternary = Color(0xFFD1D5DB),
    border = Color(0x14000000),             // 8% subtle black border
    separator = Color(0x0F000000),          // 6% hairline separator
    separatorOpaque = Color(0xFFE5E7EB),
    accentRed = Color(0xFFD32F2F),          // High-contrast deep RED
    accentRedSubtle = Color(0x1CD32F2F),
    accentGold = Color(0xFFB8860B),
    accentBlue = Color(0xFF0284C7),
    accentPurple = Color(0xFF7C3AED),
    statusSuccess = Color(0xFF2E7D32),
    statusSuccessContainer = Color(0x1A2E7D32),
    statusWarning = Color(0xFFD97706),
    statusWarningContainer = Color(0x1AD97706),
    statusError = Color(0xFFDC2626),
    statusErrorContainer = Color(0x1ADC2626),
    statusInfo = Color(0xFF0284C7),
    statusInfoContainer = Color(0x1A0284C7),
    navSurface = Color(0xF0FFFFFF),
    navBorder = Color(0x1A000000)
)

// Composition Locals
val LocalRedColors = staticCompositionLocalOf { RedDarkColorTokens }
val LocalRedSpacing = staticCompositionLocalOf { RedSpacing }
val LocalRedRadius = staticCompositionLocalOf { RedCornerRadius }
val LocalRedElevation = staticCompositionLocalOf { RedElevation }

/**
 * Accessor for the RED Design System
 */
object RedTheme {
    val colors: RedColorTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalRedColors.current

    val spacing: RedSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalRedSpacing.current

    val radius: RedCornerRadius
        @Composable
        @ReadOnlyComposable
        get() = LocalRedRadius.current

    val elevation: RedElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalRedElevation.current
}
