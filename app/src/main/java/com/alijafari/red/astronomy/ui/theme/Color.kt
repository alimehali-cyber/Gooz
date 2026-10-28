package com.alijafari.red.astronomy.ui.theme

import androidx.compose.ui.graphics.Color

// ==========================================
// RED Core Brand & Semantic Colors
// ==========================================
val RedAccentVermilion = Color(0xFFE53935)       // RED signature vermilion crimson
val RedAccentSubtle = Color(0x28E53935)
val RedAccentLight = Color(0xFFD32F2F)

val RedCelestialGold = Color(0xFFD4AF37)
val RedCelestialBlue = Color(0xFF38BDF8)
val RedCelestialPurple = Color(0xFFA855F7)

// Status & Semantic Colors
val StatusSuccess = Color(0xFF34C759)
// Deep amber: the previous #FF9F0A measured 1.95:1 on the light card surface, which is not a
// warning anyone can read. This clears 3:1 on both the light and the dark card.
val StatusWarning = Color(0xFFB45309)
val StatusError = Color(0xFFFF453A)
val StatusInfo = Color(0xFF0A84FF)

// ==========================================
// Pure White & Light Design System Tokens
// ==========================================
val PureWhiteBackground = Color(0xFFFFFFFF)
val SoftCreamSurface = Color(0xFFFAF8F5)         // Ivory Soft Cream
val SoftCreamCardBorder = Color(0xFFEAE5D9)      // Subtle warm cream border
val SoftCreamVariant = Color(0xFFF3EFE6)         // Cream container

// Old-Money / Classic Accent Colors
val OldMoneyBurgundy = Color(0xFF800020)         // Deep Royal Mars Crimson / Burgundy
val OldMoneyBurgundyLight = Color(0xFF9F1239)    // Rich Mars Accent
val OldMoneyChampagneGold = Color(0xFFC5A059)    // Warm Champagne Gold
val OldMoneyBronze = Color(0xFFB8860B)           // Warm Bronze Accent
val OldMoneySlate = Color(0xFF111118)            // Rich High-Contrast Black Slate
val OldMoneySlateMuted = Color(0xFF4A4A5A)       // High-Contrast Slate Secondary
val OldMoneyNavy = Color(0xFF182238)             // Deep Luxury Navy

// ==========================================
// RED Celestial & Dark Night Color Tokens
// ==========================================
val BackgroundPrimary = Color(0xFF090A0F)      // Deep Celestial Void
val BackgroundCard = Color(0xFF12131C)         // Deep Slate Card Surface
val CardSurface = Color(0x12FFFFFF)            // Light glass overlay
val CardBorder = Color(0x1AFFFFFF)             // Subtle light border

// Mars Crimson. Deliberately a mid-tone: #9F1239 measured 2.1:1 on the dark card surface and
// white text needs ~5:1 on it, while this sits at 3.1:1 on the dark card and 4.7:1 on the light
// one, so the accent icon and the accent button work in every theme.
val AccentPrimary = Color(0xFFD32F2F)          // Mars Crimson / Old Money Burgundy
val AccentSecondary = Color(0xFFC5A059)        // Champagne Gold
val AccentTertiary = Color(0xFF2563EB)         // Royal Indigo Blue

val TextPrimary = Color(0xFFFAF9F6)            // Ivory Pure White
val TextSecondary = Color(0xFFC2C2CA)          // High contrast soft gray
val TextTertiary = Color(0xFF8E8E9A)           // Muted text

val StatusExcellent = Color(0xFF059669)        // Deep Rich Emerald
val StatusGood = Color(0xFFD97706)             // Deep Warm Amber

val GradientCardStart = Color(0xFF1A1829)
val GradientCardEnd = Color(0xFF0E0D17)

val BottomNavBackground = Color(0xFA0E0D14)

// ==========================================
// Papercraft Pastel Color System
// ==========================================
val PapercraftBackground = Color(0xFFF6F3EC)       // Soft Matte Warm Linen Cardstock
val PapercraftSurface = Color(0xFFFAF8F3)          // Layered Off-white Textured Cardstock
val PapercraftSurfaceVariant = Color(0xFFEBE5D8)   // Secondary Layer Cardstock
val PapercraftPrimary = Color(0xFF8B5E56)          // Muted Soft Terracotta / Vintage Paper Clay
val PapercraftSecondary = Color(0xFFD4A373)        // Soft Warm Sand / Cardstock Gold
val PapercraftTertiary = Color(0xFF6B8E85)         // Soft Pastel Sage / Muted Eucalyptus
val PapercraftTextPrimary = Color(0xFF2C2523)      // Deep Warm Sepia Charcoal
val PapercraftTextSecondary = Color(0xFF6E655F)    // Muted Sepia
val PapercraftOutline = Color(0xFFD8D0C3)          // Visible Cut Paper Edge Shadow Border

// ==========================================
// Legacy color mappings for backward compatibility
// ==========================================
val NavyBackground = BackgroundPrimary
val NavySurface = BackgroundCard
val NavySurfaceVariant = Color(0xFF1A1726)
val NavyPrimary = AccentPrimary
val NavyOnPrimary = Color(0xFFFFFFFF)
val NavyPrimaryContainer = Color(0x269F1239)
val NavyOnPrimaryContainer = TextPrimary
val NavyOutline = CardBorder
val NavyTextPrimary = TextPrimary
val NavyTextSecondary = TextSecondary

val AccentEmerald = StatusExcellent
val AccentTeal = Color(0xFF0D9488)
val AccentAmber = StatusGood
val AccentCyan = AccentPrimary
val AccentViolet = AccentPrimary
val AccentRose = StatusWarning
val AccentBlue = AccentTertiary
