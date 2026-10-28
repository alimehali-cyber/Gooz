package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObjectType
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.absoluteValue
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

object StarRenderer {

    private data class BackgroundStarRecord(
        val eq: CoordinateEngine.Equatorial,
        val magnitude: Double,
        val color: Color,
        val seedHash: Int
    )

    /**
     * Ballesteros (2012) B-V -> effective temperature -> gentle blackbody RGB
     * mixed 45% with pure white (PiXiEED / pole-island-sky photometric star tint).
     */
    private fun bvToGentleStarColor(bvIndex: Double): Color {
        val bv = bvIndex.coerceIn(-0.4, 2.0)
        val tempK = 4600.0 * (1.0 / (0.92 * bv + 1.7) + 1.0 / (0.92 * bv + 0.62))
        val k = tempK / 100.0
        val rRaw = if (k <= 66.0) 255.0 else 329.7 * (k - 60.0).pow(-0.1332)
        val gRaw = if (k <= 66.0) 99.47 * ln(k.coerceAtLeast(1.0)) - 161.12 else 288.12 * (k - 60.0).pow(-0.0755)
        val bRaw = when {
            k >= 66.0 -> 255.0
            k <= 19.0 -> 0.0
            else -> 138.52 * ln((k - 10.0).coerceAtLeast(1.0)) - 305.04
        }
        fun mixChannel(c: Double): Float {
            val clamped = c.coerceIn(0.0, 255.0)
            return ((255.0 * 0.45 + clamped * 0.55) / 255.0).toFloat().coerceIn(0f, 1f)
        }
        return Color(red = mixChannel(rRaw), green = mixChannel(gRaw), blue = mixChannel(bRaw), alpha = 1f)
    }

    /**
     * Resolves a catalog star's gentle blackbody color from its spectral class or temperature.
     */
    private fun resolveRealSkyStarColor(celestialObj: CelestialObject): Color {
        val specClass = celestialObj.spectralType.trim().uppercase().firstOrNull { it in "OBAFGKM" }
        val bv = when (specClass) {
            'O' -> -0.32
            'B' -> -0.18
            'A' -> 0.02
            'F' -> 0.38
            'G' -> 0.65
            'K' -> 1.15
            'M' -> 1.65
            else -> when {
                celestialObj.temperatureK >= 15000 -> -0.22
                celestialObj.temperatureK >= 9500 -> 0.00
                celestialObj.temperatureK >= 6800 -> 0.32
                celestialObj.temperatureK >= 5200 -> 0.65
                celestialObj.temperatureK >= 3900 -> 1.15
                celestialObj.temperatureK > 0 -> 1.62
                else -> 0.15
            }
        }
        return bvToGentleStarColor(bv)
    }

    /**
     * Kasten & Young (1989) optical airmass X(alt), valid all the way down to the horizon.
     */
    private fun airmassKastenYoung(altitudeDeg: Double): Double {
        val altClamped = altitudeDeg.coerceIn(0.0, 90.0)
        val altRad = Math.toRadians(altClamped)
        return 1.0 / (sin(altRad) + 0.50572 * (altClamped + 6.07995).pow(-1.6364))
    }

    /**
     * Extinction-adjusted apparent magnitude normalized so zenith (X = 1) has zero loss.
     */
    private fun effectiveMagnitude(mag: Double, altitudeDeg: Double): Double {
        val kExtinction = 0.26
        return mag + kExtinction * (airmassKastenYoung(altitudeDeg) - 1.0)
    }

    private fun horizonFadeFactor(altitudeDeg: Double): Float {
        if (altitudeDeg <= 1.0) return 0f
        if (altitudeDeg >= 12.0) return 1f
        return ((altitudeDeg - 1.0) / 11.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Pogson magnitude-to-radius and magnitude-to-alpha curves (pole-island-sky / PiXiEED).
     */
    private fun magnitudeToRadiusPx(effectiveMag: Double): Float {
        return (1.42 * 2.512.pow((4.6 - effectiveMag) * 0.25)).toFloat().coerceIn(0.50f, 3.35f)
    }

    private fun magnitudeToAlpha(effectiveMag: Double): Float {
        return (2.512.pow((5.7 - effectiveMag) * 0.21)).toFloat().coerceIn(0.14f, 1.0f)
    }

    /**
     * Precomputed 320 naked-eye background stars (2.8 <= mag <= 5.6) fixed in J2000 (RA, Dec)
     * with Galactic-plane concentration and realistic B-V color distribution.
     */
    private val REAL_SKY_BACKGROUND_STARS: List<BackgroundStarRecord> by lazy {
        val list = ArrayList<BackgroundStarRecord>(320)
        var s = 0x5F3759DFL
        fun nextUnit(): Double {
            s = (s * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFFFFFFFFFFL
            return (s % 1000000L).toDouble() / 1000000.0
        }

        for (i in 0 until 320) {
            val u1 = nextUnit()
            val u2 = nextUnit()
            val u3 = nextUnit()
            val u4 = nextUnit()

            val eq = if (i % 10 < 5) {
                // 50% concentrated near Galactic plane (|b| < 22°)
                val lDeg = u1 * 360.0
                val bDeg = (u2 - 0.5) * 40.0 * (0.45 + 0.55 * u3)
                CoordinateEngine.galacticToEquatorial(CoordinateEngine.Galactic(lDeg, bDeg))
            } else {
                // 50% isotropic across celestial sphere
                val raDeg = u1 * 360.0
                val sinDec = (u2 * 2.0 - 1.0).coerceIn(-0.999, 0.999)
                val decDeg = Math.toDegrees(asin(sinDec))
                CoordinateEngine.Equatorial(raDeg, decDeg)
            }

            // Exponentially more faint stars than bright stars (m in 2.85 .. 5.55)
            val mag = 2.85 + (u3.pow(0.62)) * 2.70
            val bv = when {
                u4 < 0.18 -> -0.18 + u4 * 0.8   // B/A blue-white
                u4 < 0.58 -> 0.05 + (u4 - 0.18) * 1.1 // A/F/G white to warm ivory
                u4 < 0.86 -> 0.65 + (u4 - 0.58) * 1.8 // G/K golden-amber
                else -> 1.25 + (u4 - 0.86) * 2.5      // K/M orange-red
            }
            list.add(
                BackgroundStarRecord(
                    eq = eq,
                    magnitude = mag,
                    color = bvToGentleStarColor(bv),
                    seedHash = i * 97 + 31
                )
            )
        }
        list
    }

    fun drawStars(
        drawScope: DrawScope,
        objects: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        starVisibility: Float,
        frameTimeMs: Long,
        theme: SkyCanvasTheme = SkyCanvasTheme.ATMOSPHERIC_SKY,
        latitudeDeg: Double = 0.0,
        lastDeg: Double = 0.0,
        projection: SkyProjection = HeroProjection
    ) {
        if (starVisibility <= 0.05f) return

        when (theme) {
            SkyCanvasTheme.REAL_SKY -> drawRealSkyStars(drawScope, objects, starVisibility, frameTimeMs, latitudeDeg, lastDeg, projection = projection)
            SkyCanvasTheme.ATMOSPHERIC_SKY -> drawCelestialStars(drawScope, objects, starVisibility, frameTimeMs, latitudeDeg, projection = projection)
            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> drawMonochromeStars(drawScope, objects, starVisibility, frameTimeMs, latitudeDeg, baseColor = Color.White, projection = projection)
            SkyCanvasTheme.KIDS_WATERCOLOR -> drawFunStars(drawScope, objects, starVisibility, frameTimeMs, latitudeDeg, projection = projection)
            SkyCanvasTheme.OBSERVATORY -> drawMonochromeStars(drawScope, objects, starVisibility, frameTimeMs, latitudeDeg, baseColor = Color(0xFFF87171), projection = projection)
            SkyCanvasTheme.PAPERCRAFT_DIORAMA -> drawPapercraftStars(drawScope, objects, starVisibility, frameTimeMs, latitudeDeg, projection = projection)
        }
    }

    private fun drawRealSkyStars(
        drawScope: DrawScope,
        objects: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        starVisibility: Float,
        frameTimeMs: Long,
        latitudeDeg: Double,
        lastDeg: Double,
        projection: SkyProjection
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height
        val horizonY = projection.clipBottomPx(height)

        drawScope.clipRect(left = 0f, top = 0f, right = width, bottom = horizonY) {
            // 1. Faint naked-eye celestial background starfield (2.8 <= m <= 5.6)
            for (bgStar in REAL_SKY_BACKGROUND_STARS) {
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    equatorial = bgStar.eq,
                    lastDeg = lastDeg,
                    latitudeDeg = latitudeDeg
                )
                if (horiz.altitudeDeg <= 1.2) continue

                val hFade = horizonFadeFactor(horiz.altitudeDeg)
                if (hFade <= 0.01f) continue

                val effMag = effectiveMagnitude(bgStar.magnitude, horiz.altitudeDeg)
                if (effMag > 5.85) continue

                val airmass = airmassKastenYoung(horiz.altitudeDeg)
                // Atmospheric scintillation increases with airmass near the horizon
                val scintAmp = ((airmass - 1.0) * 0.065 + 0.03).coerceIn(0.03, 0.20).toFloat()
                val scint = 1.0f - scintAmp * (0.5f + 0.5f * sin(frameTimeMs * 0.0022f + bgStar.seedHash))

                val alpha = (starVisibility * hFade * magnitudeToAlpha(effMag) * scint).coerceIn(0f, 0.92f)
                if (alpha <= 0.03f) continue

                val pos = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
                val radius = magnitudeToRadiusPx(effMag).coerceIn(0.50f, 1.35f)

                drawScope.drawCircle(
                    color = bgStar.color.copy(alpha = alpha),
                    radius = radius,
                    center = pos
                )
            }

            // 2. Named catalog stars (Pogson photometry, Ballesteros B-V tint, Kasten-Young extinction, PSF glow)
            for ((celestialObj, horiz) in objects) {
                if (celestialObj.type != ObjectType.STAR || horiz.altitudeDeg <= 0.8) continue

                val hFade = horizonFadeFactor(horiz.altitudeDeg)
                if (hFade <= 0.01f) continue

                val effMag = effectiveMagnitude(celestialObj.magnitude, horiz.altitudeDeg)
                val airmass = airmassKastenYoung(horiz.altitudeDeg)
                val hash = celestialObj.id.hashCode()
                val scintAmp = ((airmass - 1.0) * 0.055 + 0.025).coerceIn(0.025, 0.16).toFloat()
                val scint = 1.0f - scintAmp * (0.5f + 0.5f * sin(frameTimeMs * 0.0024f + (hash % 97)))

                val alpha = (starVisibility * hFade * magnitudeToAlpha(effMag) * scint).coerceIn(0f, 1f)
                if (alpha <= 0.03f) continue

                val center = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
                val starColor = resolveRealSkyStarColor(celestialObj)
                val coreRadius = magnitudeToRadiusPx(effMag)

                // Soft additive Point-Spread Function (PSF) optical halo for bright stars (effMag < 2.2)
                if (effMag < 2.2) {
                    val glowScale = if (effMag < 0.5) 4.8f else 3.8f
                    val glowRadius = coreRadius * glowScale
                    val glowAlpha = (alpha * if (effMag < 0.5) 0.36f else 0.24f).coerceIn(0f, 0.42f)
                    drawScope.drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to starColor.copy(alpha = glowAlpha),
                            0.35f to starColor.copy(alpha = glowAlpha * 0.38f),
                            1.0f to Color.Transparent,
                            center = center,
                            radius = glowRadius
                        ),
                        radius = glowRadius,
                        center = center,
                        blendMode = BlendMode.Plus
                    )
                }

                // Tinted photosphere disk
                drawScope.drawCircle(
                    color = starColor.copy(alpha = alpha),
                    radius = coreRadius,
                    center = center
                )

                // Crisp white pinpoint core for 1st/2nd magnitude stars
                if (effMag < 2.5) {
                    drawScope.drawCircle(
                        color = Color.White.copy(alpha = (alpha * 0.95f).coerceIn(0f, 1f)),
                        radius = (coreRadius * 0.55f).coerceAtLeast(0.65f),
                        center = center
                    )
                }
            }
        }
    }

    /**
     * Resolves a star's visual tint from its Harvard spectral classification (O, B, A, F, G, K, M),
     * falling back to effective temperature or apparent magnitude if spectralType is absent.
     */
    private fun resolveSpectralColor(celestialObj: CelestialObject): Color {
        val specClass = celestialObj.spectralType.trim().uppercase().firstOrNull { it in "OBAFGKM" }
        return when (specClass) {
            'O' -> Color(0xFF93C5FD) // Deep blue
            'B' -> Color(0xFFBFDBFE) // Blue-white (e.g. Rigel, Spica, Regulus)
            'A' -> Color(0xFFF8FAFC) // Crisp white (e.g. Sirius, Vega, Deneb, Altair)
            'F' -> Color(0xFFFEF9C3) // Yellow-white (e.g. Canopus, Procyon, Polaris)
            'G' -> Color(0xFFFDE047) // Warm yellow (e.g. Capella, Rigil Kentaurus)
            'K' -> Color(0xFFFDBA74) // Orange (e.g. Arcturus, Aldebaran, Pollux)
            'M' -> Color(0xFFFCA5A5) // Red-orange supergiants (e.g. Betelgeuse, Antares)
            else -> when {
                celestialObj.temperatureK >= 10000 -> Color(0xFFBFDBFE)
                celestialObj.temperatureK >= 7500 -> Color(0xFFF8FAFC)
                celestialObj.temperatureK >= 6000 -> Color(0xFFFEF9C3)
                celestialObj.temperatureK >= 5000 -> Color(0xFFFDE047)
                celestialObj.temperatureK >= 3700 -> Color(0xFFFDBA74)
                celestialObj.temperatureK > 0 -> Color(0xFFFCA5A5)
                celestialObj.magnitude < -0.5 -> Color(0xFF93C5FD)
                celestialObj.magnitude < 0.5 -> Color(0xFFFEF08A)
                celestialObj.magnitude < 1.2 -> Color(0xFFFCA5A5)
                else -> Color(0xFFF8FAFC)
            }
        }
    }

    private fun drawCelestialStars(
        drawScope: DrawScope,
        objects: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        starVisibility: Float,
        frameTimeMs: Long,
        latitudeDeg: Double,
        projection: SkyProjection
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        objects.forEach { (celestialObj, horiz) ->
            val center = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
            val sx = center.x
            val sy = center.y

            if (celestialObj.type == ObjectType.DEEP_SKY) {
                drawAndromedaCelestial(drawScope, center, starVisibility, frameTimeMs)
            } else if (celestialObj.type == ObjectType.STAR) {
                val hash = celestialObj.id.hashCode()
                val twinkleFreq = 0.002f + (hash % 10) * 0.0003f
                val twinklePhase = (hash % 100) * 0.1f

                val twinkle = 0.35f + 0.65f * sin(frameTimeMs * twinkleFreq + twinklePhase).toFloat().absoluteValue
                val alpha = (starVisibility * (0.6f + 0.4f * twinkle)).coerceIn(0f, 1f)

                val baseRadius = (3.6f - celestialObj.magnitude.toFloat() * 0.5f).coerceAtLeast(1.2f) * (0.85f + 0.25f * twinkle)

                val spectralColor = resolveSpectralColor(celestialObj)

                if (celestialObj.magnitude < 1.2) {
                    drawScope.drawCircle(
                        color = spectralColor.copy(alpha = 0.18f * alpha),
                        radius = baseRadius * 3.5f,
                        center = center
                    )

                    val spikeLength = baseRadius * 2.8f * twinkle
                    drawScope.drawLine(
                        color = spectralColor.copy(alpha = 0.5f * alpha),
                        start = Offset(sx - spikeLength, sy),
                        end = Offset(sx + spikeLength, sy),
                        strokeWidth = 1.0f
                    )
                    drawScope.drawLine(
                        color = spectralColor.copy(alpha = 0.5f * alpha),
                        start = Offset(sx, sy - spikeLength),
                        end = Offset(sx, sy + spikeLength),
                        strokeWidth = 1.0f
                    )
                }

                drawScope.drawCircle(
                    color = spectralColor.copy(alpha = alpha),
                    radius = baseRadius,
                    center = center
                )
            }
        }
    }

    private fun drawMonochromeStars(
        drawScope: DrawScope,
        objects: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        starVisibility: Float,
        frameTimeMs: Long,
        latitudeDeg: Double,
        baseColor: Color = Color.White,
        projection: SkyProjection
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        objects.forEach { (celestialObj, horiz) ->
            val center = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
            val sx = center.x
            val sy = center.y

            if (celestialObj.type == ObjectType.DEEP_SKY) {
                // Faint elliptical outline with soft translucent center
                val alpha = (starVisibility * 0.45f).coerceIn(0f, 1f)
                if (alpha > 0.05f) {
                    drawScope.withTransform({
                        rotate(degrees = -35f, pivot = center)
                    }) {
                        val gWidth = 32f
                        val gHeight = 14f
                        // Translucent center
                        drawScope.drawOval(
                            color = baseColor.copy(alpha = 0.12f * alpha),
                            topLeft = Offset(center.x - gWidth / 2f, center.y - gHeight / 2f),
                            size = Size(gWidth, gHeight)
                        )
                        // Faint outline
                        drawScope.drawOval(
                            color = baseColor.copy(alpha = 0.35f * alpha),
                            topLeft = Offset(center.x - gWidth / 2f, center.y - gHeight / 2f),
                            size = Size(gWidth, gHeight),
                            style = Stroke(width = 1.0f)
                        )
                    }
                }
            } else if (celestialObj.type == ObjectType.STAR) {
                val hash = celestialObj.id.hashCode().absoluteValue
                val twinkleFreq = 0.002f + (hash % 10) * 0.0003f
                val twinklePhase = (hash % 100) * 0.1f

                // Twinkle modulating OPACITY ONLY (no scaling, no glow)
                val twinkle = 0.30f + 0.70f * sin(frameTimeMs * twinkleFreq + twinklePhase).toFloat().absoluteValue
                val alpha = (starVisibility * twinkle).coerceIn(0f, 1f)
                val starColor = baseColor.copy(alpha = alpha)

                val symbolType = hash % 4
                when (symbolType) {
                    0 -> { // Tiny Dot
                        drawScope.drawCircle(
                            color = starColor,
                            radius = 1.8f,
                            center = center
                        )
                    }
                    1 -> { // 4-Point Small Sparkle
                        val r = 4.5f
                        drawScope.drawLine(color = starColor, start = Offset(sx - r, sy), end = Offset(sx + r, sy), strokeWidth = 1.0f)
                        drawScope.drawLine(color = starColor, start = Offset(sx, sy - r), end = Offset(sx, sy + r), strokeWidth = 1.0f)
                    }
                    2 -> { // Tiny Cross
                        val r = 3.0f
                        drawScope.drawLine(color = starColor, start = Offset(sx - r, sy - r), end = Offset(sx + r, sy + r), strokeWidth = 1.0f)
                        drawScope.drawLine(color = starColor, start = Offset(sx - r, sy + r), end = Offset(sx + r, sy - r), strokeWidth = 1.0f)
                    }
                    else -> { // Tiny Diamond
                        val dPath = Path().apply {
                            moveTo(sx, sy - 3.5f)
                            lineTo(sx + 2.5f, sy)
                            lineTo(sx, sy + 3.5f)
                            lineTo(sx - 2.5f, sy)
                            close()
                        }
                        drawScope.drawPath(path = dPath, color = starColor, style = Stroke(width = 1.0f))
                    }
                }
            }
        }
    }

    private fun drawFunStars(
        drawScope: DrawScope,
        objects: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        starVisibility: Float,
        frameTimeMs: Long,
        latitudeDeg: Double = 0.0,
        projection: SkyProjection
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        objects.forEach { (celestialObj, horiz) ->
            val center = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
            val sx = center.x
            val sy = center.y

            if (celestialObj.type == ObjectType.DEEP_SKY) {
                drawAndromedaCelestial(drawScope, center, starVisibility, frameTimeMs)
            } else if (celestialObj.type == ObjectType.STAR) {
                val hash = celestialObj.id.hashCode().absoluteValue
                val twinkleFreq = 0.002f + (hash % 10) * 0.0003f
                val twinkle = 0.4f + 0.6f * sin(frameTimeMs * twinkleFreq + hash).toFloat().absoluteValue
                val alpha = (starVisibility * twinkle).coerceIn(0f, 1f)

                val pastelColor = when (hash % 3) {
                    0 -> Color(0xFFFEF08A) // Soft Crayon Yellow
                    1 -> Color(0xFFE9D5FF) // Crayon Pastel Lavender
                    else -> Color(0xFFF8FAFC) // Crayon White
                }.copy(alpha = alpha)

                val symbolType = hash % 3
                if (symbolType == 0) {
                    // Playful 5-Point Star
                    val sPath = Path()
                    val outerR = 6.0f
                    val innerR = 2.5f
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) outerR else innerR
                        val angle = (i * Math.PI / 5 - Math.PI / 2).toFloat()
                        val px = sx + r * cos(angle)
                        val py = sy + r * sin(angle)
                        if (i == 0) sPath.moveTo(px, py) else sPath.lineTo(px, py)
                    }
                    sPath.close()
                    drawScope.drawPath(path = sPath, color = pastelColor)
                    drawScope.drawPath(path = sPath, color = Color(0xFFD97706).copy(alpha = alpha), style = Stroke(width = 1.0f))
                } else if (symbolType == 1) {
                    // Crayon X
                    val r = 4f
                    drawScope.drawLine(color = pastelColor, start = Offset(sx - r, sy - r), end = Offset(sx + r, sy + r), strokeWidth = 2.0f, cap = StrokeCap.Round)
                    drawScope.drawLine(color = pastelColor, start = Offset(sx - r, sy + r), end = Offset(sx + r, sy - r), strokeWidth = 2.0f, cap = StrokeCap.Round)
                } else {
                    // Crayon Dot
                    drawScope.drawCircle(color = pastelColor, radius = 2.8f, center = center)
                }
            }
        }
    }

    private fun drawAndromedaCelestial(
        drawScope: DrawScope,
        center: Offset,
        starVisibility: Float,
        frameTimeMs: Long
    ) {
        val alpha = (starVisibility * 0.55f).coerceIn(0f, 1f)
        if (alpha <= 0.05f) return

        val width = 36f
        val height = 16f
        val pulse = 1.0f + 0.05f * sin(frameTimeMs * 0.0008f).toFloat()

        drawScope.withTransform({
            rotate(degrees = -35f, pivot = center)
        }) {
            drawScope.drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFC084FC).copy(alpha = 0.30f * alpha * pulse),
                        Color(0xFF818CF8).copy(alpha = 0.12f * alpha * pulse),
                        Color.Transparent
                    ),
                    center = center,
                    radius = width * 0.6f
                ),
                topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                size = Size(width, height)
            )

            drawScope.drawOval(
                color = Color(0xFFF1F5F9).copy(alpha = 0.7f * alpha),
                topLeft = Offset(center.x - width * 0.18f, center.y - height * 0.25f),
                size = Size(width * 0.36f, height * 0.5f)
            )
        }
    }

    private fun drawPapercraftStars(
        drawScope: DrawScope,
        objects: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        starVisibility: Float,
        frameTimeMs: Long,
        latitudeDeg: Double,
        projection: SkyProjection
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        objects.forEach { (celestialObj, horiz) ->
            if (celestialObj.type == ObjectType.STAR || celestialObj.type == ObjectType.DEEP_SKY) {
                val center = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
                val sx = center.x
                val sy = center.y

                val visAlpha = starVisibility.coerceIn(0f, 1f)
                val size = (5f - celestialObj.magnitude.toFloat() * 0.6f).coerceIn(2.5f, 7.5f)
                val paperColor = (if (celestialObj.magnitude < 1.0) Color(0xFFFFF3B0) else Color(0xFFFFFDF8)).copy(alpha = visAlpha)
                val shadowColor = Color(0x28201A18).copy(alpha = 0.16f * visAlpha)
                val strokeColor = Color(0x22000000).copy(alpha = 0.13f * visAlpha)
                val shadowOffset = Offset(2.5f, 3f)

                if (celestialObj.magnitude < 1.5) {
                    // 4-point paper cutout star shape
                    val starPath = Path().apply {
                        moveTo(center.x, center.y - size * 2.2f)
                        quadraticTo(center.x, center.y, center.x + size * 2.2f, center.y)
                        quadraticTo(center.x, center.y, center.x, center.y + size * 2.2f)
                        quadraticTo(center.x, center.y, center.x - size * 2.2f, center.y)
                        quadraticTo(center.x, center.y, center.x, center.y - size * 2.2f)
                        close()
                    }
                    val shadowPath = Path().apply {
                        val sc = center + shadowOffset
                        moveTo(sc.x, sc.y - size * 2.2f)
                        quadraticTo(sc.x, sc.y, sc.x + size * 2.2f, sc.y)
                        quadraticTo(sc.x, sc.y, sc.x, sc.y + size * 2.2f)
                        quadraticTo(sc.x, sc.y, sc.x - size * 2.2f, sc.y)
                        quadraticTo(sc.x, sc.y, sc.x, sc.y - size * 2.2f)
                        close()
                    }
                    drawScope.drawPath(path = shadowPath, color = shadowColor)
                    drawScope.drawPath(path = starPath, color = paperColor)
                    drawScope.drawPath(path = starPath, color = strokeColor, style = Stroke(width = 0.8f))
                } else {
                    // Small circular cardstock punchout
                    drawScope.drawCircle(
                        color = shadowColor,
                        radius = size * 0.9f,
                        center = center + shadowOffset
                    )
                    drawScope.drawCircle(
                        color = paperColor,
                        radius = size * 0.9f,
                        center = center
                    )
                }
            }
        }
    }
}
