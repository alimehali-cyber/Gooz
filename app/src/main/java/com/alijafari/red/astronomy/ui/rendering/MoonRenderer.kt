package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

object MoonRenderer {

    fun drawMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        illuminationPercent: Double,
        phaseAngleRad: Double = 0.0,
        isLunarEclipse: Boolean = false,
        isSolarEclipse: Boolean = false,
        moonPulseScale: Float = 1.0f,
        lightingState: LightingState,
        frameTimeMs: Long = System.currentTimeMillis(),
        isWaxing: Boolean = true,
        theme: SkyCanvasTheme = SkyCanvasTheme.CELESTIAL,
        brightLimbAngleDeg: Double = 0.0,
        parallacticAngleDeg: Double = 0.0,
        limbScreenAngleDeg: Double? = null,
        clipBottomPx: Float? = null
    ) {
        val horizonY = clipBottomPx ?: (drawScope.size.height * 0.85f)
        drawScope.clipRect(
            left = 0f,
            top = 0f,
            right = drawScope.size.width,
            bottom = horizonY
        ) {
            if (isSolarEclipse) {
                val eclipseRadius = if (theme == SkyCanvasTheme.REAL_SKY) radius * 0.48f else radius
                drawSolarEclipse(drawScope, center, eclipseRadius, theme)
                return@clipRect
            }

            // Rigorous screen orientation angle of the bright limb
            val rotationDeg = (limbScreenAngleDeg ?: run {
                val zenithAngle = CoordinateEngine.calculateZenithAngleDeg(brightLimbAngleDeg, parallacticAngleDeg)
                if (isWaxing) zenithAngle - 90.0 else zenithAngle - 270.0
            }).toFloat()

            when (theme) {
                SkyCanvasTheme.REAL_SKY -> drawRealSkyMoon(drawScope, center, radius * 0.48f, illuminationPercent, isLunarEclipse, lightingState, rotationDeg)
                SkyCanvasTheme.ATMOSPHERIC_SKY -> drawCelestialMoon(drawScope, center, radius, illuminationPercent, isLunarEclipse, moonPulseScale, lightingState, frameTimeMs, rotationDeg)
                SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> drawMonochromeMoon(drawScope, center, radius, illuminationPercent, isLunarEclipse, rotationDeg, isObservatory = false)
                SkyCanvasTheme.KIDS_WATERCOLOR -> drawFunMoon(drawScope, center, radius, illuminationPercent, isLunarEclipse, moonPulseScale, lightingState, frameTimeMs, rotationDeg)
                SkyCanvasTheme.OBSERVATORY -> drawMonochromeMoon(drawScope, center, radius, illuminationPercent, isLunarEclipse, rotationDeg, isObservatory = true)
                SkyCanvasTheme.PAPERCRAFT_DIORAMA -> drawPapercraftMoon(drawScope, center, radius, illuminationPercent, rotationDeg, isLunarEclipse)
            }
        }
    }

    /**
     * The photographic Moon (the Home Moon card's plate, rendered by [SkyMoonSprites] with the true phase,
     * libration, axis position angle, earthshine and visibility against the sky), drawn so that celestial
     * north and west in the sprite land on the screen directions [northDeg] and [westDeg] (canvas angles
     * from +x towards +y, as returned by [SkyProjection.moonLimbAngleDeg]); mirrored projections are
     * handled. The moonlight bloom of the Real Sky theme and the aura of the Atmospheric theme are kept,
     * scaled by how much the eye's glare around the Moon stands out against the sky.
     */
    fun drawPhotoMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        sprite: SkyMoonSprites.Sprite,
        northDeg: Double,
        westDeg: Double,
        isLunarEclipse: Boolean,
        lightingState: LightingState,
        theme: SkyCanvasTheme,
        frameTimeMs: Long,
        illuminationPercent: Double,
        clipBottomPx: Float? = null
    ) {
        val horizonY = clipBottomPx ?: (drawScope.size.height * 0.85f)
        drawScope.clipRect(left = 0f, top = 0f, right = drawScope.size.width, bottom = horizonY) {
            val glare = sprite.glareWeight.toFloat().coerceIn(0f, 1f)
            val phaseFrac = (illuminationPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
            if (theme == SkyCanvasTheme.ATMOSPHERIC_SKY) {
                val auraRadius = radius * 3.2f
                val a1 = if (isLunarEclipse) Color(0xFFEF4444).copy(alpha = 0.30f) else Color(0xFFFEF9C3).copy(alpha = 0.35f * lightingState.bloomIntensity * glare)
                val a2 = if (isLunarEclipse) Color(0xFF991B1B).copy(alpha = 0.10f) else Color(0xFF38BDF8).copy(alpha = 0.12f * lightingState.bloomIntensity * glare)
                drawScope.drawCircle(
                    brush = Brush.radialGradient(colors = listOf(a1, a2, Color.Transparent), center = center, radius = auraRadius),
                    radius = auraRadius,
                    center = center
                )
            } else {
                // Moonlight bloom: ocular and atmospheric glare, as strong as it stands out against the sky.
                val bloomAlpha = if (isLunarEclipse) 0.22f * glare else (0.06f + 0.28f * phaseFrac) * lightingState.bloomIntensity.coerceIn(0.35f, 1.0f) * glare
                if (bloomAlpha > 0.003f) {
                    val bloomColor = if (isLunarEclipse) Color(0xFFC84B31) else Color(0xFFF4F0E6)
                    val bloomRadius = radius * (2.6f + 1.2f * phaseFrac)
                    drawScope.drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to bloomColor.copy(alpha = bloomAlpha),
                            0.42f to bloomColor.copy(alpha = bloomAlpha * 0.35f),
                            1.0f to Color.Transparent,
                            center = center,
                            radius = bloomRadius
                        ),
                        radius = bloomRadius,
                        center = center,
                        blendMode = BlendMode.Plus
                    )
                }
            }

            // Sprite axes (x = celestial west, y = celestial north) → screen.
            val nRad = Math.toRadians(northDeg)
            val nx = cos(nRad).toFloat(); val ny = sin(nRad).toFloat()
            // West is north turned 90° clockwise on a y-down canvas unless the projection mirrors the sky.
            var wx = -ny; var wy = nx
            val wRad = Math.toRadians(westDeg)
            if (cos(wRad).toFloat() * wx + sin(wRad).toFloat() * wy < 0f) { wx = -wx; wy = -wy }
            val s = radius / sprite.bodyRadiusPx
            val half = sprite.light.width / 2f
            val sx = s * wx; val kx = -s * nx
            val sy = s * wy; val ky = -s * ny
            val matrix = android.graphics.Matrix().apply {
                setValues(floatArrayOf(sx, kx, center.x - (sx + kx) * half, sy, ky, center.y - (sy + ky) * half, 0f, 0f, 1f))
            }
            val eclipseFilter = if (isLunarEclipse) android.graphics.PorterDuffColorFilter(
                android.graphics.Color.rgb(214, 96, 64), android.graphics.PorterDuff.Mode.MULTIPLY
            ) else null
            // The Moon's light adds to the sky behind it (SCREEN), as in GlyphRaster.renderMoon.
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
                colorFilter = eclipseFilter
            }
            drawScope.drawIntoCanvas { canvas -> canvas.nativeCanvas.drawBitmap(sprite.light, matrix, paint) }
        }
    }

    /**
     * Photorealistic borderless Moon with Earthshine (Da Vinci glow), anatomical lunar maria washes,
     * soft regolith limb darkening, and additive atmospheric moonlight bloom.
     */
    private fun drawRealSkyMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        illuminationPercent: Double,
        isLunarEclipse: Boolean,
        lightingState: LightingState,
        rotationDeg: Float
    ) {
        val phaseFrac = (illuminationPercent / 100.0).coerceIn(0.0, 1.0).toFloat()

        // 1. Soft additive atmospheric moonlight bloom scaled by illuminated fraction
        val bloomAlpha = if (isLunarEclipse) {
            0.22f
        } else {
            (0.06f + 0.28f * phaseFrac) * lightingState.bloomIntensity.coerceIn(0.35f, 1.0f)
        }
        val bloomColor = if (isLunarEclipse) Color(0xFFC84B31) else Color(0xFFF4F0E6)
        val bloomRadius = radius * (2.6f + 1.2f * phaseFrac)
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                0.0f to bloomColor.copy(alpha = bloomAlpha),
                0.42f to bloomColor.copy(alpha = bloomAlpha * 0.35f),
                1.0f to Color.Transparent,
                center = center,
                radius = bloomRadius
            ),
            radius = bloomRadius,
            center = center,
            blendMode = BlendMode.Plus
        )

        // 2. Unlit lunar hemisphere with Earthshine (Da Vinci glow, strongest for crescent phases)
        val earthshineIntensity = ((1f - phaseFrac) * (0.35f + 0.65f * (1f - abs(phaseFrac - 0.18f) / 0.82f))).coerceIn(0.02f, 0.18f)
        val darkDiskBase = Color(0xFF0A0F1D)
        val earthshineTint = Color(0xFF8EA4C8)
        drawScope.drawCircle(
            color = darkDiskBase,
            radius = radius,
            center = center
        )
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                0.0f to earthshineTint.copy(alpha = earthshineIntensity * 0.55f),
                0.78f to earthshineTint.copy(alpha = earthshineIntensity * 0.85f),
                1.0f to earthshineTint.copy(alpha = earthshineIntensity),
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // 3. Illuminated phase path (centered at 0° / +X, rotated by rotationDeg toward the Sun)
        if (phaseFrac > 0.005f) {
            val illuminatedPath = Path().apply {
                if (phaseFrac >= 0.995f) {
                    addOval(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius))
                } else {
                    addArc(
                        Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                        -90f,
                        180f
                    )
                    val k = 2.0f * phaseFrac - 1.0f
                    val stepInnerWidth = (abs(k) * radius).coerceAtLeast(0f)
                    val innerRect = Rect(center.x - stepInnerWidth, center.y - radius, center.x + stepInnerWidth, center.y + radius)
                    val innerSweep = if (k >= 0f) 180f else -180f
                    arcTo(innerRect, 90f, innerSweep, false)
                    close()
                }
            }

            val litCenterColor = if (isLunarEclipse) Color(0xFFD65A38) else Color(0xFFF6F2E8)
            val litLimbColor = if (isLunarEclipse) Color(0xFF7A1E14) else Color(0xFFDDD5C5)
            val mariaColor = if (isLunarEclipse) Color(0xFF4A100A).copy(alpha = 0.32f) else Color(0xFF948E84).copy(alpha = 0.28f)

            drawScope.rotate(rotationDeg, center) {
                drawScope.clipPath(illuminatedPath) {
                    // Base limb-shaded lunar regolith
                    drawScope.drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to litCenterColor,
                            0.75f to litCenterColor,
                            1.0f to litLimbColor,
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )

                    // Anatomical naked-eye Lunar Maria washes (Imbrium, Serenitatis, Tranquillitatis, Crisium, Procellarum, Humorum)
                    val mariaFeatures = listOf(
                        Triple(-0.22f, -0.26f, 0.34f), // Mare Imbrium
                        Triple(+0.14f, -0.20f, 0.24f), // Mare Serenitatis
                        Triple(+0.30f, +0.04f, 0.26f), // Mare Tranquillitatis
                        Triple(+0.56f, -0.10f, 0.16f), // Mare Crisium
                        Triple(-0.44f, +0.06f, 0.38f), // Oceanus Procellarum
                        Triple(-0.18f, +0.36f, 0.26f)  // Mare Nubium / Humorum
                    )
                    for ((dx, dy, rFrac) in mariaFeatures) {
                        val mCenter = Offset(center.x + dx * radius, center.y + dy * radius)
                        val mRadius = (rFrac * radius).coerceAtLeast(1f)
                        drawScope.drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to mariaColor,
                                0.65f to mariaColor.copy(alpha = mariaColor.alpha * 0.45f),
                                1.0f to Color.Transparent,
                                center = mCenter,
                                radius = mRadius
                            ),
                            radius = mRadius,
                            center = mCenter
                        )
                    }
                }
            }
        }
    }

    private fun drawSolarEclipse(drawScope: DrawScope, center: Offset, radius: Float, theme: SkyCanvasTheme) {
        val coronaColor = when (theme) {
            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> Color.White.copy(alpha = 0.4f)
            SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444).copy(alpha = 0.55f)
            SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFFFF3B0).copy(alpha = 0.7f)
            else -> Color(0xFF60A5FA).copy(alpha = 0.6f)
        }
        val innerCorona = if (theme == SkyCanvasTheme.OBSERVATORY) Color(0xFFFCA5A5) else Color.White
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(innerCorona, coronaColor, Color.Transparent),
                center = center,
                radius = radius * 4.0f
            ),
            radius = radius * 4.0f,
            center = center
        )
        drawScope.drawCircle(
            color = if (theme == SkyCanvasTheme.OBSERVATORY) Color(0xFF1A0000) else Color(0xFF0F172A),
            radius = radius,
            center = center
        )
        drawScope.drawCircle(
            color = when (theme) {
                SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> Color.White
                SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
                SkyCanvasTheme.PAPERCRAFT_DIORAMA -> Color(0xFFF3C5B6)
                else -> Color(0xFF60A5FA)
            },
            radius = radius,
            center = center,
            style = Stroke(width = 1.5f)
        )
    }

    private fun drawMonochromeMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        illuminationPercent: Double,
        isLunarEclipse: Boolean,
        rotationDeg: Float = 0f,
        isObservatory: Boolean = false
    ) {
        // Monochromatic / Observatory vector Moon
        val illuminatedColor = when {
            isObservatory && isLunarEclipse -> Color(0xFF991B1B)
            isObservatory -> Color(0xFFEF4444)
            isLunarEclipse -> Color(0xFFD4D4D8)
            else -> Color.White
        }
        val shadowedColor = if (isObservatory) Color(0xFF260505) else Color(0xFF262626)
        val outlineColor = if (isObservatory) Color(0xFFEF4444).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.5f)

        // 1. Draw base shadowed disk
        drawScope.drawCircle(
            color = shadowedColor,
            radius = radius,
            center = center
        )

        // 2. Draw illuminated phase portion (centered at 0° / Right, rotated to rotationDeg)
        val phaseFrac = (illuminationPercent / 100.0).coerceIn(0.0, 1.0)
        if (phaseFrac > 0.02) {
            val illuminatedPath = Path()
            // Outer semicircular arc on the right side (-90° top to +90° bottom)
            illuminatedPath.addArc(
                Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                -90f,
                180f
            )

            val k = (2.0 * phaseFrac - 1.0).toFloat()
            val stepInnerWidth = (abs(k) * radius).coerceAtLeast(0f)
            val innerRect = Rect(center.x - stepInnerWidth, center.y - radius, center.x + stepInnerWidth, center.y + radius)

            // Inner terminator arc from +90° bottom to -90° top
            val innerSweep = if (k >= 0) 180f else -180f
            illuminatedPath.arcTo(innerRect, 90f, innerSweep, false)
            illuminatedPath.close()

            drawScope.rotate(rotationDeg, center) {
                drawPath(
                    path = illuminatedPath,
                    color = illuminatedColor
                )
            }
        }

        // 3. Subtle depth outline (minimal, architectural stroke)
        drawScope.drawCircle(
            color = outlineColor,
            radius = radius,
            center = center,
            style = Stroke(width = drawScope.run { 1.2.dp.toPx() })
        )
    }

    private fun drawCelestialMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        illuminationPercent: Double,
        isLunarEclipse: Boolean,
        moonPulseScale: Float,
        lightingState: LightingState,
        frameTimeMs: Long,
        rotationDeg: Float = 0f
    ) {
        val shinePulse = 1.0f + 0.08f * sin(frameTimeMs * 0.0022f).toFloat() * moonPulseScale

        // Soft Outer Shining Glow Aura
        val auraRadius = radius * 3.2f * shinePulse
        val auraColor1 = if (isLunarEclipse) Color(0xFFEF4444).copy(alpha = 0.30f) else Color(0xFFFEF9C3).copy(alpha = 0.35f * lightingState.bloomIntensity)
        val auraColor2 = if (isLunarEclipse) Color(0xFF991B1B).copy(alpha = 0.10f) else Color(0xFF38BDF8).copy(alpha = 0.12f * lightingState.bloomIntensity)

        drawScope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(auraColor1, auraColor2, Color.Transparent),
                center = center,
                radius = auraRadius
            ),
            radius = auraRadius,
            center = center
        )

        val diskColor = if (isLunarEclipse) Color(0xFFDC2626) else Color(0xFFFEF9C3)
        drawScope.drawCircle(color = diskColor, radius = radius, center = center)

        // Craters
        val craterColor = if (isLunarEclipse) Color(0xFF991B1B).copy(alpha = 0.4f) else Color(0xFFCBD5E1).copy(alpha = 0.5f)
        drawScope.drawCircle(color = craterColor, radius = radius * 0.22f, center = Offset(center.x - radius * 0.32f, center.y - radius * 0.25f))
        drawScope.drawCircle(color = craterColor, radius = radius * 0.18f, center = Offset(center.x + radius * 0.30f, center.y + radius * 0.28f))

        // Phase shadow (unilluminated region centered at 180° / Left, bright limb at 0° / Right rotated by rotationDeg)
        val phaseFrac = (illuminationPercent / 100.0).coerceIn(0.0, 1.0)
        if (phaseFrac < 0.98) {
            val baseShadowColor = Color(0xEE0F172A)
            val shadowPath = Path()

            // Outer shadow arc around the left edge (+90° bottom to -90° top)
            shadowPath.addArc(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), 90f, 180f)
            val k = (2.0 * phaseFrac - 1.0).toFloat()
            val stepInnerWidth = (abs(k) * radius).coerceAtLeast(0f)
            val innerRect = Rect(center.x - stepInnerWidth, center.y - radius, center.x + stepInnerWidth, center.y + radius)

            // Inner terminator arc from -90° top to +90° bottom
            val innerSweep = if (k >= 0) -180f else 180f
            shadowPath.arcTo(innerRect, 270f, innerSweep, false)
            shadowPath.close()

            drawScope.rotate(rotationDeg, center) {
                drawPath(path = shadowPath, color = baseShadowColor)
            }
        }

        drawScope.drawCircle(color = Color(0xFFFDE047).copy(alpha = 0.8f), radius = radius, center = center, style = Stroke(width = 1.5f))
    }

    private fun drawFunMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        illuminationPercent: Double,
        isLunarEclipse: Boolean,
        moonPulseScale: Float,
        lightingState: LightingState,
        frameTimeMs: Long,
        rotationDeg: Float = 0f
    ) {
        val shinePulse = 1.0f + 0.08f * sin(frameTimeMs * 0.0022f).toFloat() * moonPulseScale

        // 1. Soft Outer Shining Glow Aura (Pulsating)
        val auraRadius = radius * 3.2f * shinePulse
        val auraColor1 = if (isLunarEclipse) Color(0xFFEF4444).copy(alpha = 0.30f) else Color(0xFFFEF9C3).copy(alpha = 0.35f * lightingState.bloomIntensity)
        val auraColor2 = if (isLunarEclipse) Color(0xFF991B1B).copy(alpha = 0.10f) else Color(0xFF38BDF8).copy(alpha = 0.12f * lightingState.bloomIntensity)

        drawScope.drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(auraColor1, auraColor2, Color.Transparent),
                center = center,
                radius = auraRadius
            ),
            radius = auraRadius,
            center = center
        )

        // 2. Playful Radiating Shining Beamlets / Sparkles (Kid's drawing style)
        val rayCount = 8
        val rayStart = radius + drawScope.run { 4.dp.toPx() }
        val rayColor = if (isLunarEclipse) Color(0xFFF87171) else Color(0xFFBAE6FD)

        for (i in 0 until rayCount) {
            val angleRad = (i * (2.0 * Math.PI / rayCount) + frameTimeMs * 0.0005).toFloat()
            val rayLen = drawScope.run { 8.dp.toPx() } * (0.8f + 0.3f * sin(frameTimeMs * 0.003f + i).toFloat())

            val startX = center.x + rayStart * cos(angleRad)
            val startY = center.y + rayStart * sin(angleRad)
            val endX = center.x + (rayStart + rayLen) * cos(angleRad)
            val endY = center.y + (rayStart + rayLen) * sin(angleRad)

            drawScope.drawLine(
                color = rayColor.copy(alpha = 0.65f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = drawScope.run { 2.dp.toPx() },
                cap = StrokeCap.Round
            )
        }

        // 3. Central Kid's Moon Disk
        val diskColor = if (isLunarEclipse) Color(0xFFDC2626) else Color(0xFFFEF9C3)
        val strokeColor = if (isLunarEclipse) Color(0xFF7F1D1D) else Color(0xFF0284C7)

        drawScope.drawCircle(
            color = diskColor,
            radius = radius,
            center = center
        )

        // 4. Cute Kid's Craters & Cute Face
        val craterColor = if (isLunarEclipse) Color(0xFF991B1B).copy(alpha = 0.4f) else Color(0xFFE2E8F0).copy(alpha = 0.7f)
        val craterBorder = if (isLunarEclipse) Color(0xFF7F1D1D).copy(alpha = 0.5f) else Color(0xFFBAE6FD)

        val c1Center = Offset(center.x - radius * 0.32f, center.y - radius * 0.25f)
        val c1Radius = radius * 0.22f
        drawScope.drawCircle(color = craterColor, radius = c1Radius, center = c1Center)
        drawScope.drawCircle(color = craterBorder, radius = c1Radius, center = c1Center, style = Stroke(width = 1.2f))

        val c2Center = Offset(center.x + radius * 0.30f, center.y + radius * 0.28f)
        val c2Radius = radius * 0.18f
        drawScope.drawCircle(color = craterColor, radius = c2Radius, center = c2Center)
        drawScope.drawCircle(color = craterBorder, radius = c2Radius, center = c2Center, style = Stroke(width = 1.0f))

        // Cute Face
        val eyeOffset = radius * 0.30f
        val eyeRadius = drawScope.run { 1.8.dp.toPx() }
        val eyeY = center.y - radius * 0.10f

        drawScope.drawCircle(color = strokeColor, radius = eyeRadius, center = Offset(center.x - eyeOffset, eyeY))
        drawScope.drawCircle(color = strokeColor, radius = eyeRadius, center = Offset(center.x + eyeOffset, eyeY))

        val smilePath = Path().apply {
            val smileY = center.y + radius * 0.12f
            val smileWidth = radius * 0.38f
            moveTo(center.x - smileWidth, smileY)
            quadraticTo(
                center.x, smileY + radius * 0.32f,
                center.x + smileWidth, smileY
            )
        }
        drawScope.drawPath(
            path = smilePath,
            color = strokeColor,
            style = Stroke(width = drawScope.run { 1.8.dp.toPx() }, cap = StrokeCap.Round)
        )

        // Phase shadow
        val phaseFrac = (illuminationPercent / 100.0).coerceIn(0.0, 1.0)
        if (phaseFrac < 0.98) {
            val baseShadowColor = Color(0xEE070A14)
            val shadowPath = Path()

            shadowPath.addArc(Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius), 90f, 180f)
            val k = (2.0 * phaseFrac - 1.0).toFloat()
            val stepInnerWidth = (abs(k) * radius).coerceAtLeast(0f)
            val innerRect = Rect(center.x - stepInnerWidth, center.y - radius, center.x + stepInnerWidth, center.y + radius)

            val innerSweep = if (k >= 0) -180f else 180f
            shadowPath.arcTo(innerRect, 270f, innerSweep, false)
            shadowPath.close()

            drawScope.rotate(rotationDeg, center) {
                drawPath(path = shadowPath, color = baseShadowColor)
            }
        }

        drawScope.drawCircle(
            color = strokeColor,
            radius = radius,
            center = center,
            style = Stroke(width = drawScope.run { 2.5.dp.toPx() })
        )
    }

    private fun drawPapercraftMoon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        illuminationPercent: Double,
        rotationDeg: Float = 0f,
        isLunarEclipse: Boolean = false
    ) {
        val shadowOffset = Offset(4f, 5f)
        val shadowColor = Color(0x352A221E)
        val paperCream = if (isLunarEclipse) Color(0xFFE07A5F) else Color(0xFFFAF8F3)
        val paperDarkCard = Color(0xFF3B4050)
        val auraColor = if (isLunarEclipse) Color(0x44E07A5F) else Color(0x33FFF3B0)

        // Drop shadow for the main paper moon disc
        drawScope.drawCircle(
            color = shadowColor,
            radius = radius * 1.2f,
            center = center + shadowOffset
        )

        // Outer Paper Aura Ring
        drawScope.drawCircle(
            color = auraColor,
            radius = radius * 1.35f,
            center = center
        )

        // Main Paper Moon Disc
        drawScope.drawCircle(
            color = paperCream,
            radius = radius * 1.2f,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0x22000000),
            radius = radius * 1.2f,
            center = center,
            style = Stroke(width = 1.2f)
        )

        // Phase Cutout Shadow Layer
        val phaseFrac = (illuminationPercent / 100.0).coerceIn(0.0, 1.0)
        if (phaseFrac < 0.98) {
            val r = radius * 1.2f
            val shadowPath = Path()

            shadowPath.addArc(Rect(center.x - r, center.y - r, center.x + r, center.y + r), 90f, 180f)
            val k = (2.0 * phaseFrac - 1.0).toFloat()
            val stepInnerWidth = (abs(k) * r).coerceAtLeast(0f)
            val innerRect = Rect(center.x - stepInnerWidth, center.y - r, center.x + stepInnerWidth, center.y + r)

            val innerSweep = if (k >= 0) -180f else 180f
            shadowPath.arcTo(innerRect, 270f, innerSweep, false)
            shadowPath.close()

            drawScope.rotate(rotationDeg, center) {
                drawPath(path = shadowPath, color = paperDarkCard)
                drawPath(path = shadowPath, color = Color(0x33000000), style = Stroke(width = 1.2f))
            }
        }
    }
}
