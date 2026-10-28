package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

object PlanetRenderer {

    fun drawPlanets(
        drawScope: DrawScope,
        planets: List<Triple<PlanetEngine.PlanetType, PlanetEngine.PlanetPosition, CoordinateEngine.Horizontal>>,
        frameTimeMs: Long,
        theme: SkyCanvasTheme = SkyCanvasTheme.CELESTIAL,
        latitudeDeg: Double = 0.0,
        projection: SkyProjection = HeroProjection
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height
        val horizonY = projection.clipBottomPx(height)

        drawScope.clipRect(left = 0f, top = 0f, right = width, bottom = horizonY) {
            planets.forEach { (pType, pPos, horiz) ->
                val center = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)

                when (theme) {
                    SkyCanvasTheme.REAL_SKY -> drawRealSkyPlanet(drawScope, pType, pPos, horiz, center)
                    SkyCanvasTheme.ATMOSPHERIC_SKY -> {
                        when (pType) {
                            PlanetEngine.PlanetType.JUPITER -> drawJupiter(drawScope, center, frameTimeMs)
                            PlanetEngine.PlanetType.SATURN -> drawSaturn(drawScope, center)
                            PlanetEngine.PlanetType.MARS -> drawMars(drawScope, center)
                            PlanetEngine.PlanetType.VENUS -> drawVenus(drawScope, center)
                            PlanetEngine.PlanetType.MERCURY -> drawMercury(drawScope, center)
                            PlanetEngine.PlanetType.URANUS -> drawUranus(drawScope, center)
                            PlanetEngine.PlanetType.NEPTUNE -> drawNeptune(drawScope, center)
                            else -> {}
                        }
                    }
                    SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> drawMonochromePlanet(drawScope, pType, center, baseColor = Color.White)
                    SkyCanvasTheme.KIDS_WATERCOLOR -> drawFunPlanet(drawScope, pType, center)
                    SkyCanvasTheme.OBSERVATORY -> drawMonochromePlanet(drawScope, pType, center, baseColor = Color(0xFFEF4444))
                    SkyCanvasTheme.PAPERCRAFT_DIORAMA -> drawPapercraftPlanet(drawScope, pType, center)
                }
            }
        }
    }

    /**
     * Steady, untwinkling naked-eye point-source planet rendering (pole-island-sky / Stellarium photometry)
     * with true planetary albedo colors, Kasten-Young atmospheric extinction, and soft optical halo.
     */
    private fun drawRealSkyPlanet(
        drawScope: DrawScope,
        pType: PlanetEngine.PlanetType,
        pPos: PlanetEngine.PlanetPosition,
        horiz: CoordinateEngine.Horizontal,
        center: Offset
    ) {
        if (horiz.altitudeDeg <= 0.5) return

        // Kasten & Young (1989) airmass extinction
        val altClamped = horiz.altitudeDeg.coerceIn(0.5, 90.0)
        val airmass = 1.0 / (sin(Math.toRadians(altClamped)) + 0.50572 * (altClamped + 6.07995).pow(-1.6364))
        val effMag = pPos.magnitude + 0.25 * (airmass - 1.0)
        // Naked-eye / twilight visibility cutoff
        if (effMag > 6.5) return

        val horizonFade = if (horiz.altitudeDeg >= 10.0) 1f else ((horiz.altitudeDeg - 0.5) / 9.5).toFloat().coerceIn(0f, 1f)

        val planetColor = when (pType) {
            PlanetEngine.PlanetType.MERCURY -> Color(0xFFC9C0B8) // Warm twilight silver-grey
            PlanetEngine.PlanetType.VENUS -> Color(0xFFFDF6D3)   // Brilliant warm ivory-white
            PlanetEngine.PlanetType.MARS -> Color(0xFFFF7A55)    // Ochre-coral red
            PlanetEngine.PlanetType.JUPITER -> Color(0xFFF5D9A3) // Creamy pale gold
            PlanetEngine.PlanetType.SATURN -> Color(0xFFE8D59A)  // Muted butterscotch yellow
            PlanetEngine.PlanetType.URANUS -> Color(0xFFA5D8E8)  // Pale ice-cyan
            PlanetEngine.PlanetType.NEPTUNE -> Color(0xFF6D8EE8) // Faint deep azure
            else -> Color(0xFFF8FAFC)
        }

        // Pogson magnitude-to-radius clamped so even Venus (-4.4) stays a realistic luminous point source
        val coreRadius = (1.55 * 2.512.pow((4.2 - effMag) * 0.20)).toFloat().coerceIn(1.1f, 3.8f)
        val alpha = (horizonFade * (2.512.pow((6.0 - effMag) * 0.22)).toFloat().coerceIn(0.22f, 1.0f)).coerceIn(0f, 1f)

        // 1. Steady additive atmospheric PSF halo (planets do not twinkle)
        val haloRadius = coreRadius * 4.2f
        val haloAlpha = (alpha * 0.38f).coerceIn(0f, 0.45f)
        drawScope.drawCircle(
            brush = Brush.radialGradient(
                0.0f to planetColor.copy(alpha = haloAlpha),
                0.40f to planetColor.copy(alpha = haloAlpha * 0.38f),
                1.0f to Color.Transparent,
                center = center,
                radius = haloRadius
            ),
            radius = haloRadius,
            center = center,
            blendMode = BlendMode.Plus
        )

        // 2. Albedo-tinted planetary point disk
        drawScope.drawCircle(
            color = planetColor.copy(alpha = alpha),
            radius = coreRadius,
            center = center
        )

        // 3. High-luminance inner core
        drawScope.drawCircle(
            color = Color.White.copy(alpha = (alpha * 0.92f).coerceIn(0f, 1f)),
            radius = coreRadius * 0.52f,
            center = center
        )
    }

    private fun drawMonochromePlanet(
        drawScope: DrawScope,
        pType: PlanetEngine.PlanetType,
        center: Offset,
        baseColor: Color = Color.White
    ) {
        when (pType) {
            PlanetEngine.PlanetType.VENUS -> {
                drawScope.drawCircle(color = baseColor, radius = 7.5f, center = center)
                drawScope.drawCircle(color = baseColor.copy(alpha = 0.3f), radius = 12f, center = center)
            }
            PlanetEngine.PlanetType.MARS -> {
                drawScope.drawCircle(color = baseColor, radius = 6.5f, center = center, style = Stroke(width = 1.2f))
                drawScope.drawCircle(color = baseColor, radius = 2.0f, center = center)
            }
            PlanetEngine.PlanetType.JUPITER -> {
                val r = 9.0f
                drawScope.drawCircle(color = baseColor, radius = r, center = center, style = Stroke(width = 1.2f))
                drawScope.drawLine(color = baseColor, start = Offset(center.x - r * 0.8f, center.y - 2.5f), end = Offset(center.x + r * 0.8f, center.y - 2.5f), strokeWidth = 1.0f)
                drawScope.drawLine(color = baseColor, start = Offset(center.x - r * 0.8f, center.y + 2.5f), end = Offset(center.x + r * 0.8f, center.y + 2.5f), strokeWidth = 1.0f)
            }
            PlanetEngine.PlanetType.SATURN -> {
                val r = 7.0f
                drawScope.drawCircle(color = baseColor, radius = r, center = center, style = Stroke(width = 1.2f))
                drawScope.withTransform({
                    rotate(degrees = -20f, pivot = center)
                }) {
                    drawScope.drawOval(
                        color = baseColor,
                        topLeft = Offset(center.x - 14f, center.y - 4f),
                        size = Size(28f, 8f),
                        style = Stroke(width = 1.2f)
                    )
                }
            }
            PlanetEngine.PlanetType.MERCURY -> {
                drawScope.drawCircle(color = baseColor, radius = 5.0f, center = center, style = Stroke(width = 1.2f))
            }
            PlanetEngine.PlanetType.URANUS -> {
                drawScope.drawCircle(color = baseColor, radius = 7.0f, center = center, style = Stroke(width = 1.0f))
                drawScope.drawCircle(color = baseColor, radius = 4.0f, center = center, style = Stroke(width = 1.0f))
            }
            PlanetEngine.PlanetType.NEPTUNE -> {
                val fillTint = if (baseColor == Color.White) Color(0xFF334155) else baseColor.copy(alpha = 0.25f)
                drawScope.drawCircle(color = fillTint, radius = 6.5f, center = center)
                drawScope.drawCircle(color = baseColor, radius = 6.5f, center = center, style = Stroke(width = 1.0f))
            }
            else -> {}
        }
    }

    private fun drawFunPlanet(drawScope: DrawScope, pType: PlanetEngine.PlanetType, center: Offset) {
        when (pType) {
            PlanetEngine.PlanetType.VENUS -> {
                drawScope.drawCircle(color = Color(0xFFFEF08A), radius = 7.5f, center = center)
                drawScope.drawCircle(color = Color(0xFFCA8A04), radius = 7.5f, center = center, style = Stroke(width = 1.5f))
            }
            PlanetEngine.PlanetType.MARS -> {
                drawScope.drawCircle(color = Color(0xFFF97316), radius = 6.5f, center = center)
                drawScope.drawCircle(color = Color(0xFFC2410C), radius = 6.5f, center = center, style = Stroke(width = 1.5f))
            }
            PlanetEngine.PlanetType.JUPITER -> {
                val r = 9.0f
                drawScope.drawCircle(color = Color(0xFFFDE047), radius = r, center = center)
                drawScope.drawCircle(color = Color(0xFFCA8A04), radius = r, center = center, style = Stroke(width = 2.0f))
                drawScope.drawLine(color = Color(0xFFEA580C), start = Offset(center.x - r * 0.7f, center.y - 3f), end = Offset(center.x + r * 0.7f, center.y - 3f), strokeWidth = 1.5f)
                drawScope.drawLine(color = Color(0xFFEA580C), start = Offset(center.x - r * 0.7f, center.y + 3f), end = Offset(center.x + r * 0.7f, center.y + 3f), strokeWidth = 1.5f)
            }
            PlanetEngine.PlanetType.SATURN -> {
                val r = 7.0f
                drawScope.drawCircle(color = Color(0xFFFDE047), radius = r, center = center)
                drawScope.drawCircle(color = Color(0xFFB45309), radius = r, center = center, style = Stroke(width = 1.5f))
                drawScope.withTransform({
                    rotate(degrees = -20f, pivot = center)
                }) {
                    drawScope.drawOval(
                        color = Color(0xFFF97316),
                        topLeft = Offset(center.x - 14f, center.y - 4.5f),
                        size = Size(28f, 9f),
                        style = Stroke(width = 2.0f)
                    )
                }
            }
            PlanetEngine.PlanetType.MERCURY -> {
                drawScope.drawCircle(color = Color(0xFFCBD5E1), radius = 5.0f, center = center)
                drawScope.drawCircle(color = Color(0xFF475569), radius = 5.0f, center = center, style = Stroke(width = 1.2f))
            }
            PlanetEngine.PlanetType.URANUS -> {
                drawScope.drawCircle(color = Color(0xFF38BDF8), radius = 7.0f, center = center)
                drawScope.drawCircle(color = Color(0xFF0284C7), radius = 7.0f, center = center, style = Stroke(width = 1.5f))
            }
            PlanetEngine.PlanetType.NEPTUNE -> {
                drawScope.drawCircle(color = Color(0xFF3B82F6), radius = 6.5f, center = center)
                drawScope.drawCircle(color = Color(0xFF1D4ED8), radius = 6.5f, center = center, style = Stroke(width = 1.5f))
            }
            else -> {}
        }
    }

    private fun drawJupiter(drawScope: DrawScope, center: Offset, frameTimeMs: Long) {
        val r = 11f
        // Soft Vector Aura
        drawScope.drawCircle(color = Color(0xFFFBBF24).copy(alpha = 0.22f), radius = r * 2.2f, center = center)

        // Core Disk with Horizontal Bands
        drawScope.drawCircle(
            color = Color(0xFFFEF3C7),
            radius = r,
            center = center
        )

        // Minimal Horizontal Vector Band Lines
        val band1Y = center.y - r * 0.35f
        val band2Y = center.y + r * 0.35f
        drawScope.drawLine(
            color = Color(0xFFD97706).copy(alpha = 0.85f),
            start = Offset(center.x - r * 0.85f, band1Y),
            end = Offset(center.x + r * 0.85f, band1Y),
            strokeWidth = 2.2f
        )
        drawScope.drawLine(
            color = Color(0xFFB45309).copy(alpha = 0.85f),
            start = Offset(center.x - r * 0.85f, band2Y),
            end = Offset(center.x + r * 0.85f, band2Y),
            strokeWidth = 2.2f
        )

        // Vector Outline
        drawScope.drawCircle(color = Color(0xFFF59E0B), radius = r, center = center, style = Stroke(width = 1.2f))

        // --- GALILEAN MOONS (Io, Europa, Ganymede, Callisto as tiny vector dots) ---
        val moonDistances = listOf(r * 2.4f, r * 3.6f, r * 5.2f, r * 6.8f)
        val moonSpeeds = listOf(0.0018f, 0.0012f, 0.0008f, 0.0005f)
        val moonColors = listOf(Color(0xFFFDE047), Color(0xFFF1F5F9), Color(0xFFFBBF24), Color(0xFFCBD5E1))

        for (i in 0 until 4) {
            val dist = moonDistances[i]
            val speed = moonSpeeds[i]
            val angle = frameTimeMs * speed + i * 1.57f
            val mx = center.x + dist * cos(angle)
            val my = center.y + (dist * 0.15f) * sin(angle) // Slightly inclined orbit

            drawScope.drawCircle(
                color = moonColors[i],
                radius = 1.8f,
                center = Offset(mx, my)
            )
        }
    }

    private fun drawSaturn(drawScope: DrawScope, center: Offset) {
        val r = 9f

        // Soft Outer Aura
        drawScope.drawCircle(color = Color(0xFFFDE047).copy(alpha = 0.2f), radius = r * 2.2f, center = center)

        // Minimal Tilted Saturn Ring Oval
        val ringRect = Rect(center.x - r * 2.5f, center.y - r * 0.7f, center.x + r * 2.5f, center.y + r * 0.7f)
        drawScope.drawOval(
            color = Color(0xFFFDE047),
            topLeft = ringRect.topLeft,
            size = ringRect.size,
            style = Stroke(width = 2.0f)
        )

        // Saturn Core Disk
        drawScope.drawCircle(
            color = Color(0xFFFEF08A),
            radius = r,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0xFFCA8A04),
            radius = r,
            center = center,
            style = Stroke(width = 1.2f)
        )
    }

    private fun drawMars(drawScope: DrawScope, center: Offset) {
        val r = 7f
        drawScope.drawCircle(color = Color(0xFFEF4444).copy(alpha = 0.25f), radius = r * 2.2f, center = center)
        drawScope.drawCircle(
            color = Color(0xFFEF4444),
            radius = r,
            center = center
        )
        // Vector Polar Ice Cap
        drawScope.drawCircle(
            color = Color.White,
            radius = r * 0.35f,
            center = Offset(center.x, center.y - r * 0.6f)
        )
        drawScope.drawCircle(
            color = Color(0xFFB91C1C),
            radius = r,
            center = center,
            style = Stroke(width = 1.0f)
        )
    }

    private fun drawVenus(drawScope: DrawScope, center: Offset) {
        val r = 8.5f
        drawScope.drawCircle(color = Color(0xFFFEF08A).copy(alpha = 0.35f), radius = r * 2.5f, center = center)
        drawScope.drawCircle(
            color = Color.White,
            radius = r,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0xFFFDE047),
            radius = r,
            center = center,
            style = Stroke(width = 1.2f)
        )
    }

    private fun drawMercury(drawScope: DrawScope, center: Offset) {
        val r = 5.5f
        drawScope.drawCircle(
            color = Color(0xFFE2E8F0),
            radius = r,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0xFF94A3B8),
            radius = r,
            center = center,
            style = Stroke(width = 1.0f)
        )
    }

    private fun drawUranus(drawScope: DrawScope, center: Offset) {
        val r = 6.5f
        drawScope.drawCircle(color = Color(0xFF38BDF8).copy(alpha = 0.3f), radius = r * 2.0f, center = center)
        drawScope.drawCircle(
            color = Color(0xFF38BDF8),
            radius = r,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0xFF0284C7),
            radius = r,
            center = center,
            style = Stroke(width = 1.0f)
        )
    }

    private fun drawNeptune(drawScope: DrawScope, center: Offset) {
        val r = 6f
        drawScope.drawCircle(color = Color(0xFF60A5FA).copy(alpha = 0.3f), radius = r * 2.0f, center = center)
        drawScope.drawCircle(
            color = Color(0xFF3B82F6),
            radius = r,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0xFF1D4ED8),
            radius = r,
            center = center,
            style = Stroke(width = 1.0f)
        )
    }

    private fun drawPapercraftPlanet(drawScope: DrawScope, pType: PlanetEngine.PlanetType, center: Offset) {
        val (radius, paperColor) = when (pType) {
            PlanetEngine.PlanetType.MERCURY -> 6f to Color(0xFFD6CCC2)
            PlanetEngine.PlanetType.VENUS -> 8.5f to Color(0xFFE8D8B8)
            PlanetEngine.PlanetType.MARS -> 7.5f to Color(0xFFE07A5F)
            PlanetEngine.PlanetType.JUPITER -> 13f to Color(0xFFF4A261)
            PlanetEngine.PlanetType.SATURN -> 11f to Color(0xFFE9C46A)
            PlanetEngine.PlanetType.URANUS -> 8f to Color(0xFF81B29A)
            PlanetEngine.PlanetType.NEPTUNE -> 8f to Color(0xFF3D405B)
            else -> 6f to Color(0xFFF7F4EE)
        }

        val shadowOffset = Offset(3.5f, 4.5f)
        val shadowColor = Color(0x30221B18)

        // Drop shadow
        drawScope.drawCircle(
            color = shadowColor,
            radius = radius,
            center = center + shadowOffset
        )

        // Special Papercraft Saturn Ring
        if (pType == PlanetEngine.PlanetType.SATURN) {
            drawScope.drawOval(
                color = Color(0xFFE9C46A).copy(alpha = 0.7f),
                topLeft = Offset(center.x - radius * 2.2f, center.y - radius * 0.7f),
                size = Size(radius * 4.4f, radius * 1.4f),
                style = Stroke(width = 3.5f)
            )
        }

        // Cardstock disc
        drawScope.drawCircle(
            color = paperColor,
            radius = radius,
            center = center
        )
        drawScope.drawCircle(
            color = Color(0x22000000),
            radius = radius,
            center = center,
            style = Stroke(width = 1.0f)
        )
    }
}
