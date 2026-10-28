package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.sin

import com.alijafari.red.astronomy.domain.SkyCanvasTheme

object LandscapeRenderer {

    private fun buildMountainPath(width: Float, height: Float, baseHorizonY: Float, peakMaxHeight: Float, phaseShift: Float): Path {
        return Path().apply {
            moveTo(0f, height)
            val points = 32
            val stepX = width / points

            val y0 = baseHorizonY - (sin(phaseShift) * peakMaxHeight * 0.4f).coerceAtLeast(0f)
            lineTo(0f, y0)

            var prevX = 0f
            var prevY = y0

            for (i in 1..points) {
                val x = i * stepX
                val progress = x / width
                val angle1 = progress * Math.PI.toFloat() * 4f + phaseShift
                val angle2 = progress * Math.PI.toFloat() * 8.5f + phaseShift * 1.5f
                val h = (sin(angle1) * 0.65f + sin(angle2) * 0.35f).coerceAtLeast(-0.1f) * peakMaxHeight
                val y = baseHorizonY - h
                val midX = (prevX + x) / 2f
                val midY = (prevY + y) / 2f
                quadraticTo(prevX, prevY, midX, midY)
                prevX = x
                prevY = y
            }
            lineTo(width, prevY)
            lineTo(width, height)
            close()
        }
    }

    fun drawHorizonLandscape(
        drawScope: DrawScope,
        lightingState: LightingState,
        frameTimeMs: Long,
        theme: SkyCanvasTheme = SkyCanvasTheme.ATMOSPHERIC_SKY
    ) {
        if (theme == SkyCanvasTheme.PAPERCRAFT_DIORAMA) {
            drawPapercraftDioramaLandscape(drawScope, lightingState)
            return
        }

        val width = drawScope.size.width
        val height = drawScope.size.height

        // Reduced visible height: Repositioned lower at 85% of screen height
        val baseHorizonY = height * 0.85f

        // 1. Atmospheric Horizon Mist / Fog Layer
        val mistColor = when (theme) {
            SkyCanvasTheme.REAL_SKY -> when {
                lightingState.sunAltitudeDeg > 6.0 -> lightingState.horizonTone.copy(alpha = 0.16f)
                lightingState.sunAltitudeDeg in -6.0..6.0 -> lightingState.horizonTone.copy(alpha = 0.22f)
                lightingState.sunAltitudeDeg in -15.0..-6.0 -> lightingState.horizonTone.copy(alpha = 0.14f)
                else -> Color(0xFF1E293B).copy(alpha = 0.10f)
            }
            SkyCanvasTheme.OBSERVATORY -> Color(0xFF991B1B).copy(alpha = 0.22f)
            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (lightingState.sunAltitudeDeg > 0.0) {
                Color(0xFF64748B).copy(alpha = 0.18f)
            } else {
                Color(0xFF94A3B8).copy(alpha = 0.14f)
            }
            SkyCanvasTheme.KIDS_WATERCOLOR -> if (lightingState.sunAltitudeDeg > 0.0) {
                Color(0xFFFF85A1).copy(alpha = 0.28f)
            } else {
                Color(0xFF6C5CE7).copy(alpha = 0.25f)
            }
            else -> when {
                lightingState.sunAltitudeDeg > 6.0 -> Color(0xFF38BDF8).copy(alpha = 0.22f)
                lightingState.sunAltitudeDeg in 0.0..6.0 -> Color(0xFFF59E0B).copy(alpha = 0.40f)
                lightingState.sunAltitudeDeg in -6.0..0.0 -> Color(0xFFC084FC).copy(alpha = 0.30f)
                lightingState.sunAltitudeDeg in -12.0..-6.0 -> Color(0xFF818CF8).copy(alpha = 0.20f)
                else -> Color(0xFF38BDF8).copy(alpha = 0.10f)
            }
        }

        drawScope.drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, mistColor, mistColor.copy(alpha = 0.5f)),
                startY = baseHorizonY - 25f,
                endY = baseHorizonY + 15f
            ),
            topLeft = Offset(0f, baseHorizonY - 25f),
            size = drawScope.size.copy(height = 40f)
        )

        val (layer1Color, layer2Color, darkSilhouetteColor) = when (theme) {
            SkyCanvasTheme.REAL_SKY -> if (lightingState.sunAltitudeDeg > 2.0) {
                Triple(
                    Color(0xFF334E68).copy(alpha = 0.55f),
                    Color(0xFF1E3348).copy(alpha = 0.80f),
                    Color(0xFF0A141F)
                )
            } else if (lightingState.sunAltitudeDeg > -8.0) {
                Triple(
                    Color(0xFF1E293B).copy(alpha = 0.65f),
                    Color(0xFF0F172A).copy(alpha = 0.85f),
                    Color(0xFF040812)
                )
            } else {
                Triple(
                    Color(0xFF0E1525).copy(alpha = 0.72f),
                    Color(0xFF070B14).copy(alpha = 0.90f),
                    Color(0xFF020409)
                )
            }
            SkyCanvasTheme.OBSERVATORY -> Triple(
                Color(0xFF3B0404).copy(alpha = 0.7f),
                Color(0xFF240202).copy(alpha = 0.85f),
                Color(0xFF120000)
            )
            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> if (lightingState.sunAltitudeDeg > 0.0) {
                Triple(Color(0xFF94A3B8), Color(0xFF475569), Color(0xFF18181B))
            } else {
                Triple(Color(0xFF334155), Color(0xFF1E293B), Color(0xFF09090B))
            }
            SkyCanvasTheme.KIDS_WATERCOLOR -> if (lightingState.sunAltitudeDeg > 0.0) {
                Triple(Color(0xFFA8DADC), Color(0xFF457B9D), Color(0xFF1D3557))
            } else {
                Triple(Color(0xFF483D8B), Color(0xFF2E2A72), Color(0xFF161336))
            }
            else -> Triple(
                lightingState.horizonTone.copy(alpha = 0.45f),
                lightingState.horizonTone.copy(alpha = 0.75f),
                Color(0xFF020617)
            )
        }

        // --- LAYER 1: Distant Mountain Range (Soft atmospheric perspective) ---
        val layer1Path = buildMountainPath(width, height, baseHorizonY, peakMaxHeight = 15f, phaseShift = 0f)
        drawScope.drawPath(path = layer1Path, color = layer1Color)

        // --- LAYER 2: Midground Mountain Ridge ---
        val layer2Path = buildMountainPath(width, height, baseHorizonY + 10f, peakMaxHeight = 11f, phaseShift = 1.3f)
        drawScope.drawPath(path = layer2Path, color = layer2Color)

        // --- LAYER 3: Foreground Landscape Silhouette (Dark solid vector) ---
        val layer3Path = buildMountainPath(width, height, baseHorizonY + 20f, peakMaxHeight = 8f, phaseShift = 2.5f)
        drawScope.drawPath(path = layer3Path, color = darkSilhouetteColor)

        // --- CITY AMBIENT LIGHTS AT NIGHT ---
        if (lightingState.sunAltitudeDeg < -6.0 && theme != SkyCanvasTheme.MONOCHROME_SCIENTIFIC && theme != SkyCanvasTheme.REAL_SKY) {
            val cityGlowX1 = width * 0.30f
            val cityGlowX2 = width * 0.70f
            val glowPulse = 0.85f + 0.15f * sin(frameTimeMs * 0.0012f).toFloat()

            val glow1Colors = if (theme == SkyCanvasTheme.OBSERVATORY) {
                listOf(
                    Color(0xFFEF4444).copy(alpha = 0.16f * glowPulse),
                    Color(0xFF991B1B).copy(alpha = 0.05f * glowPulse),
                    Color.Transparent
                )
            } else {
                listOf(
                    Color(0xFFF59E0B).copy(alpha = 0.18f * glowPulse),
                    Color(0xFFFBBF24).copy(alpha = 0.05f * glowPulse),
                    Color.Transparent
                )
            }

            val glow2Colors = if (theme == SkyCanvasTheme.OBSERVATORY) {
                listOf(
                    Color(0xFFDC2626).copy(alpha = 0.12f * glowPulse),
                    Color.Transparent
                )
            } else {
                listOf(
                    Color(0xFF38BDF8).copy(alpha = 0.14f * glowPulse),
                    Color.Transparent
                )
            }

            drawScope.drawCircle(
                brush = Brush.radialGradient(
                    colors = glow1Colors,
                    center = Offset(cityGlowX1, baseHorizonY + 20f),
                    radius = 50f
                ),
                radius = 50f,
                center = Offset(cityGlowX1, baseHorizonY + 20f)
            )

            drawScope.drawCircle(
                brush = Brush.radialGradient(
                    colors = glow2Colors,
                    center = Offset(cityGlowX2, baseHorizonY + 25f),
                    radius = 45f
                ),
                radius = 45f,
                center = Offset(cityGlowX2, baseHorizonY + 25f)
            )
        }
    }

    private fun drawPapercraftDioramaLandscape(
        drawScope: DrawScope,
        lightingState: LightingState
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height
        val baseHorizonY = height * 0.85f

        val shadowColor = Color(0x38221B19)
        val shadowOffset = Offset(3.5f, -4.0f)

        // LAYER 1: Far Background Mountain Ridge (Soft Pastel Lavender/Sage)
        val layer1Path = buildMountainPath(width, height, baseHorizonY - 12f, peakMaxHeight = 24f, phaseShift = 0.5f)
        val layer1Color = Color(0xFFA3B18A) // Soft pastel sage
        drawScope.withTransform({
            translate(left = shadowOffset.x, top = shadowOffset.y)
        }) {
            drawPath(
                path = layer1Path,
                color = shadowColor,
                style = androidx.compose.ui.graphics.drawscope.Fill
            )
        }
        // Cardstock
        drawScope.drawPath(path = layer1Path, color = layer1Color)
        drawScope.drawPath(path = layer1Path, color = Color(0x22000000), style = Stroke(width = 1.2f))

        // LAYER 2: Midground Mountain Ridge (Warm Pastel Terracotta / Sand)
        val layer2Path = buildMountainPath(width, height, baseHorizonY + 2f, peakMaxHeight = 18f, phaseShift = 1.8f)
        val layer2Color = Color(0xFFD4A373) // Warm muted terracotta sand
        drawScope.withTransform({
            translate(left = shadowOffset.x, top = shadowOffset.y)
        }) {
            drawPath(
                path = layer2Path,
                color = shadowColor
            )
        }
        // Cardstock
        drawScope.drawPath(path = layer2Path, color = layer2Color)
        drawScope.drawPath(path = layer2Path, color = Color(0x22000000), style = Stroke(width = 1.2f))

        // LAYER 3: Rolling Foreground Paper Hills (Pastel Forest Green)
        val layer3Path = buildMountainPath(width, height, baseHorizonY + 16f, peakMaxHeight = 14f, phaseShift = 3.2f)
        val layer3Color = Color(0xFF588157) // Soft pastel eucalyptus forest
        drawScope.withTransform({
            translate(left = shadowOffset.x, top = shadowOffset.y)
        }) {
            drawPath(
                path = layer3Path,
                color = shadowColor
            )
        }
        // Cardstock
        drawScope.drawPath(path = layer3Path, color = layer3Color)
        drawScope.drawPath(path = layer3Path, color = Color(0x22000000), style = Stroke(width = 1.2f))

        // LAYER 4: Front Base Frame Cardstock Edge (Rich Dark Sepia/Slate)
        val layer4Path = Path().apply {
            moveTo(0f, baseHorizonY + 28f)
            lineTo(width, baseHorizonY + 28f)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }
        val layer4Color = Color(0xFF3A405A) // Dark slate paper base
        drawScope.drawPath(path = layer4Path, color = layer4Color)
        drawScope.drawPath(path = layer4Path, color = Color(0x33000000), style = Stroke(width = 1.5f))
    }
}
