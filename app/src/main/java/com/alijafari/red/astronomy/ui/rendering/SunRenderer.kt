package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.cos
import kotlin.math.sin

object SunRenderer {

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * f,
            green = a.green + (b.green - a.green) * f,
            blue = a.blue + (b.blue - a.blue) * f,
            alpha = a.alpha + (b.alpha - a.alpha) * f
        )
    }

    fun drawSun(
        drawScope: DrawScope,
        center: Offset,
        sunAltitudeDeg: Double,
        frameTimeMs: Long,
        theme: SkyCanvasTheme = SkyCanvasTheme.CELESTIAL,
        clipBottomPx: Float? = null
    ) {
        if (sunAltitudeDeg < -10.0) return

        val horizonY = clipBottomPx ?: (drawScope.size.height * 0.85f)
        drawScope.clipRect(
            left = 0f,
            top = 0f,
            right = drawScope.size.width,
            bottom = horizonY
        ) {
            when (theme) {
                SkyCanvasTheme.REAL_SKY -> drawRealSkySun(drawScope, center, sunAltitudeDeg)
                SkyCanvasTheme.ATMOSPHERIC_SKY -> drawCelestialSun(drawScope, center, sunAltitudeDeg, frameTimeMs)
                SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> drawMonochromeSun(drawScope, center, sunAltitudeDeg, frameTimeMs, isObservatory = false)
                SkyCanvasTheme.KIDS_WATERCOLOR -> drawFunSun(drawScope, center, sunAltitudeDeg, frameTimeMs)
                SkyCanvasTheme.OBSERVATORY -> drawMonochromeSun(drawScope, center, sunAltitudeDeg, frameTimeMs, isObservatory = true)
                SkyCanvasTheme.PAPERCRAFT_DIORAMA -> drawPapercraftSun(drawScope, center)
            }
        }
    }

    /**
     * Physical rayless Sun with altitude-dependent Rayleigh reddening, 3-layer Mie forward-scattering
     * aureole, and limb-darkened photosphere core.
     */
    private fun drawRealSkySun(
        drawScope: DrawScope,
        center: Offset,
        sunAltitudeDeg: Double
    ) {
        if (sunAltitudeDeg < -2.0) return

        val sunRadius = drawScope.run { 10.5.dp.toPx() }
        // Atmospheric reddening factor: 0.0 at high altitude (>= 22°), 1.0 right at the horizon (<= 0°)
        val reddening = ((22.0 - sunAltitudeDeg) / 22.0).coerceIn(0.0, 1.0).toFloat()

        val coreColor = lerpColor(Color(0xFFFFFEF8), Color(0xFFFFE599), reddening)
        val limbColor = lerpColor(Color(0xFFFFF2B0), Color(0xFFFF7A3D), reddening)
        val aureoleInner = lerpColor(Color(0xFFFFF4C2), Color(0xFFFF8F4D), reddening)
        val aureoleOuter = lerpColor(Color(0xFFFFE699), Color(0xFFE0533C), reddening)

        // 1. Wide Mie forward-scattering atmospheric aureole
        val outerHaloRadius = sunRadius * 5.2f
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                0.0f to aureoleInner.copy(alpha = 0.34f),
                0.35f to aureoleOuter.copy(alpha = 0.16f),
                0.70f to aureoleOuter.copy(alpha = 0.05f),
                1.0f to Color.Transparent,
                center = center,
                radius = outerHaloRadius
            ),
            radius = outerHaloRadius,
            center = center,
            blendMode = BlendMode.Plus
        )

        // 2. Inner coronal glare bloom
        val innerBloomRadius = sunRadius * 2.25f
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                0.0f to coreColor.copy(alpha = 0.68f),
                0.48f to limbColor.copy(alpha = 0.32f),
                1.0f to Color.Transparent,
                center = center,
                radius = innerBloomRadius
            ),
            radius = innerBloomRadius,
            center = center,
            blendMode = BlendMode.Plus
        )

        // 3. Limb-darkened solar photosphere disk (no cartoon rays or stroke border)
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                0.0f to coreColor,
                0.78f to coreColor,
                1.0f to limbColor,
                center = center,
                radius = sunRadius
            ),
            radius = sunRadius,
            center = center
        )
    }

    private fun drawCelestialSun(
        drawScope: DrawScope,
        center: Offset,
        sunAltitudeDeg: Double,
        frameTimeMs: Long
    ) {
        val sunRadius = drawScope.run { 20.dp.toPx() }
        val shinePulse = 1.0f + 0.08f * sin(frameTimeMs * 0.0025f).toFloat()
        val rayWiggleTime = frameTimeMs * 0.003f

        val diskColor = Color(0xFFFDE047)
        val strokeColor = Color(0xFFF59E0B)
        val rayColor = Color(0xFFFBBF24)
        val glowColor = Color(0xFFFEF08A).copy(alpha = 0.35f)

        // 1. Soft Outer Glowing Aura
        val glowRadius = sunRadius * 3.2f * shinePulse
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor, glowColor.copy(alpha = 0.1f), Color.Transparent),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )

        // 2. Vector Rays
        val rayCount = 12
        val baseRayStart = sunRadius + drawScope.run { 4.dp.toPx() }

        for (i in 0 until rayCount) {
            val baseAngle = (i * (2.0 * Math.PI / rayCount)).toFloat()
            val wiggleAngle = baseAngle + 0.03f * sin(rayWiggleTime + i * 1.3f).toFloat()

            val rayLength = if (i % 2 == 0) {
                drawScope.run { 14.dp.toPx() } * (0.9f + 0.2f * sin(rayWiggleTime + i).toFloat())
            } else {
                drawScope.run { 8.dp.toPx() } * (0.9f + 0.2f * cos(rayWiggleTime + i).toFloat())
            }

            val startX = center.x + baseRayStart * cos(wiggleAngle)
            val startY = center.y + baseRayStart * sin(wiggleAngle)
            val endX = center.x + (baseRayStart + rayLength) * cos(wiggleAngle)
            val endY = center.y + (baseRayStart + rayLength) * sin(wiggleAngle)

            drawScope.drawLine(
                color = rayColor,
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = drawScope.run { 2.5.dp.toPx() },
                cap = StrokeCap.Round
            )
        }

        // 3. Disk & Outline
        drawScope.drawCircle(color = diskColor, radius = sunRadius, center = center)
        drawScope.drawCircle(color = strokeColor, radius = sunRadius, center = center, style = Stroke(width = drawScope.run { 2.dp.toPx() }))
    }

    private fun drawMonochromeSun(
        drawScope: DrawScope,
        center: Offset,
        sunAltitudeDeg: Double,
        frameTimeMs: Long,
        isObservatory: Boolean = false
    ) {
        val sunRadius = drawScope.run { 18.dp.toPx() }
        val isDay = sunAltitudeDeg > 0.0

        // Adaptive contrast stroke (red in observatory night-vision mode; black on white day, white on dark night in monochrome)
        val baseColor = when {
            isObservatory -> Color(0xFFEF4444)
            isDay -> Color(0xFF18181B)
            else -> Color(0xFFFAFAFA)
        }

        // Subtle opacity glow pulse
        val opacityGlow = 0.15f + 0.12f * sin(frameTimeMs * 0.002f).toFloat()
        val glowRadius = sunRadius * 2.2f
        drawScope.drawCircle(
            color = baseColor.copy(alpha = opacityGlow),
            radius = glowRadius,
            center = center
        )

        // Minimal elegant short rays with perfect spacing
        val rayCount = 8
        val rayStart = sunRadius + drawScope.run { 5.dp.toPx() }
        val rayLength = drawScope.run { 6.dp.toPx() }
        for (i in 0 until rayCount) {
            val angle = (i * (2.0 * Math.PI / rayCount)).toFloat()
            val startX = center.x + rayStart * cos(angle)
            val startY = center.y + rayStart * sin(angle)
            val endX = center.x + (rayStart + rayLength) * cos(angle)
            val endY = center.y + (rayStart + rayLength) * sin(angle)

            drawScope.drawLine(
                color = baseColor.copy(alpha = 0.85f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = drawScope.run { 1.5.dp.toPx() },
                cap = StrokeCap.Round
            )
        }

        // Fill during sunset: as altitude drops below 4deg, slowly fills with soft grey/crimson
        if (sunAltitudeDeg in -5.0..4.0) {
            val fillAlpha = ((4.0 - sunAltitudeDeg) / 9.0).coerceIn(0.0, 1.0).toFloat() * 0.45f
            val fillColor = when {
                isObservatory -> Color(0xFF991B1B)
                isDay -> Color(0xFF71717A)
                else -> Color(0xFFA1A1AA)
            }
            drawScope.drawCircle(
                color = fillColor.copy(alpha = fillAlpha),
                radius = sunRadius,
                center = center
            )
        }

        // Outlined circle: minimal, perfect proportions, no fill during daytime
        drawScope.drawCircle(
            color = baseColor,
            radius = sunRadius,
            center = center,
            style = Stroke(width = drawScope.run { 1.8.dp.toPx() })
        )
    }

    private fun drawFunSun(
        drawScope: DrawScope,
        center: Offset,
        sunAltitudeDeg: Double,
        frameTimeMs: Long
    ) {
        val sunRadius = drawScope.run { 22.dp.toPx() }
        val rayWiggleTime = frameTimeMs * 0.003f

        val diskColor = Color(0xFFFDE047)   // Crayon Bright Yellow
        val strokeColor = Color(0xFFEA580C) // Bold Crayon Orange Outline
        val rayColor = Color(0xFFF59E0B)    // Crayon Golden Rays

        // 1. Playful Radiating Rays (12 wavy hand-drawn crayon rays)
        val rayCount = 12
        val baseRayStart = sunRadius + drawScope.run { 4.dp.toPx() }

        for (i in 0 until rayCount) {
            val baseAngle = (i * (2.0 * Math.PI / rayCount)).toFloat()
            val wiggleAngle = baseAngle + 0.04f * sin(rayWiggleTime + i * 1.3f).toFloat()

            val rayLength = if (i % 2 == 0) {
                drawScope.run { 16.dp.toPx() } * (0.9f + 0.2f * sin(rayWiggleTime + i).toFloat())
            } else {
                drawScope.run { 10.dp.toPx() } * (0.9f + 0.2f * cos(rayWiggleTime + i).toFloat())
            }

            val startX = center.x + baseRayStart * cos(wiggleAngle)
            val startY = center.y + baseRayStart * sin(wiggleAngle)
            val endX = center.x + (baseRayStart + rayLength) * cos(wiggleAngle)
            val endY = center.y + (baseRayStart + rayLength) * sin(wiggleAngle)

            val midX = (startX + endX) / 2f + drawScope.run { 2.dp.toPx() } * sin(rayWiggleTime + i * 2f).toFloat()
            val midY = (startY + endY) / 2f + drawScope.run { 2.dp.toPx() } * cos(rayWiggleTime + i * 2f).toFloat()

            val rayPath = Path().apply {
                moveTo(startX, startY)
                quadraticTo(midX, midY, endX, endY)
            }

            drawScope.drawPath(
                path = rayPath,
                color = rayColor,
                style = Stroke(
                    width = drawScope.run { 3.2.dp.toPx() },
                    cap = StrokeCap.Round
                )
            )
        }

        // 2. Central Sun Disk
        drawScope.drawCircle(
            color = diskColor,
            radius = sunRadius,
            center = center
        )

        // 3. Bold Crayon Outline
        drawScope.drawCircle(
            color = strokeColor,
            radius = sunRadius,
            center = center,
            style = Stroke(width = drawScope.run { 3.dp.toPx() })
        )

        // 4. Cute Minimalist Vector Face (Kid's drawing style: two eyes + cheerful smile)
        val eyeOffset = sunRadius * 0.35f
        val eyeRadius = drawScope.run { 2.2.dp.toPx() }
        val eyeY = center.y - sunRadius * 0.15f

        // Left Eye
        drawScope.drawCircle(
            color = strokeColor,
            radius = eyeRadius,
            center = Offset(center.x - eyeOffset, eyeY)
        )
        // Right Eye
        drawScope.drawCircle(
            color = strokeColor,
            radius = eyeRadius,
            center = Offset(center.x + eyeOffset, eyeY)
        )

        // Cheerful Curved Smile
        val smilePath = Path().apply {
            val smileY = center.y + sunRadius * 0.1f
            val smileWidth = sunRadius * 0.45f
            moveTo(center.x - smileWidth, smileY)
            quadraticTo(
                center.x, smileY + sunRadius * 0.38f,
                center.x + smileWidth, smileY
            )
        }
        drawScope.drawPath(
            path = smilePath,
            color = strokeColor,
            style = Stroke(
                width = drawScope.run { 2.2.dp.toPx() },
                cap = StrokeCap.Round
            )
        )
    }

    private fun drawPapercraftSun(drawScope: DrawScope, center: Offset) {
        val r = 24f
        val shadowOffset = Offset(4f, 5f)
        val shadowColor = Color(0x302A201C)

        // Outer Layer 1 Shadow
        drawScope.drawCircle(
            color = shadowColor,
            radius = r * 2.2f,
            center = center + shadowOffset
        )
        // Outer Layer 1 (Pastel Cream/Peach Cardstock Aura)
        drawScope.drawCircle(
            color = Color(0xFFFDE2E4),
            radius = r * 2.2f,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0x22000000),
            radius = r * 2.2f,
            center = center,
            style = Stroke(width = 1f)
        )

        // Mid Layer 2 Shadow
        drawScope.drawCircle(
            color = shadowColor,
            radius = r * 1.6f,
            center = center + shadowOffset
        )
        // Mid Layer 2 (Warm Pastel Yellow Cardstock)
        drawScope.drawCircle(
            color = Color(0xFFFFF1C5),
            radius = r * 1.6f,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0x22000000),
            radius = r * 1.6f,
            center = center,
            style = Stroke(width = 1f)
        )

        // Inner Layer 3 Shadow
        drawScope.drawCircle(
            color = shadowColor,
            radius = r,
            center = center + shadowOffset
        )
        // Inner Layer 3 (Pastel Gold Center Disc)
        drawScope.drawCircle(
            color = Color(0xFFF3C5B6),
            radius = r,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0x22000000),
            radius = r,
            center = center,
            style = Stroke(width = 1.2f)
        )
    }
}
