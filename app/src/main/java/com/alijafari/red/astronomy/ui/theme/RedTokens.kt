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
    val xs: Dp = 4.dp       // Small badges, tags
    val sm: Dp = 8.dp       // Inner items, nested chips, small controls
    val md: Dp = 10.dp      // Standard buttons, text inputs, nested section blocks
    /**
     * The one radius every card and window in the app wears — the Home moon card set the standard.
     * [lg], [xl] and [xxl] are aliases of it so the three historical "container" steps of the scale
     * cannot drift apart again: a card is a card, whichever token the call site happens to use.
     * Small controls above keep their own radii, so the concentric hierarchy still reads.
     */
    val window: Dp = 34.dp      // iOS 27 liquid glass medium/large radius
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
    background = Color(0xFF000000),          // iOS 27 system background primary (dark)
    backgroundSecondary = Color(0xFF1C1C1E),  // iOS 27 system background secondary (dark)
    backgroundTertiary = Color(0xFF2C2C2E),   // iOS 27 system background tertiary (dark)
    surface = Color(0xFF1C1C1E),
    surfaceElevated = Color(0xFF2C2C2E),
    surfaceGrouped = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF3A3A3C),
    textPrimary = Color(0xFFFFFFFF),         // iOS 27 label primary (dark)
    textSecondary = Color(0xB3EBEBF5),       // iOS 27 label secondary (dark, 70%)
    textTertiary = Color(0x4DEBEBF5),        // iOS 27 label tertiary (dark, 30%)
    textQuaternary = Color(0x28EBEBF5),      // iOS 27 label quaternary (dark, 16%)
    border = Color(0x2BFFFFFF),             // iOS 27 separator non-opaque (dark, 17%)
    separator = Color(0x2BFFFFFF),          // 17% hairline separator (dark)
    separatorOpaque = Color(0xFF38383A),     // iOS 27 opaque separator (dark)
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
    background = Color(0xFFFFFFFF),          // iOS 27 system background primary (light)
    backgroundSecondary = Color(0xFFF2F2F7),  // iOS 27 system background secondary (light)
    backgroundTertiary = Color(0xFFFFFFFF),   // iOS 27 system background tertiary (light)
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF2F2F7),
    surfaceGrouped = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFFF2F2F7),
    textPrimary = Color(0xFF000000),         // iOS 27 label primary (light)
    textSecondary = Color(0x993C3C43),       // iOS 27 label secondary (light, 60%)
    textTertiary = Color(0x4D3C3C43),        // iOS 27 label tertiary (light, 30%)
    textQuaternary = Color(0x2E3C3C43),      // iOS 27 label quaternary (light, 18%)
    border = Color(0x1F000000),             // iOS 27 separator non-opaque (light, 12%)
    separator = Color(0x1F000000),          // 12% hairline separator (light)
    separatorOpaque = Color(0xFFC6C6C8),     // iOS 27 opaque separator (light)
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
