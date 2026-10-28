package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.GalacticEngine
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

object MilkyWayRenderer {

    private data class GalacticCloudNode(
        val lDeg: Double,
        val centerEq: CoordinateEngine.Equatorial,
        val northBranchEq: CoordinateEngine.Equatorial,
        val southBranchEq: CoordinateEngine.Equatorial,
        val surfaceBrightness: Float,
        val widthScale: Float,
        val riftStrength: Float,
        val coreWarmth: Float
    )

    private data class StarCloudSpeck(
        val eq: CoordinateEngine.Equatorial,
        val radiusPx: Float,
        val baseAlpha: Float,
        val color: Color
    )

    private fun angularDiffDeg(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    private fun gaussian(lDeg: Double, centerDeg: Double, sigmaDeg: Double): Double {
        val d = angularDiffDeg(lDeg, centerDeg) / sigmaDeg
        return exp(-0.5 * d * d)
    }

    /**
     * Precomputed 90-node Galactic structure model (every 4° of Galactic longitude l)
     * with V-band surface brightness, bulge width, Great Rift bifurcation, and Coalsack dust lane.
     */
    private val GALACTIC_NODES: List<GalacticCloudNode> by lazy {
        (0 until 360 step 4).map { lInt ->
            val l = lInt.toDouble()

            // True longitudinal surface brightness components (Sagittarius bulge, Scutum, Cygnus, Carina-Crux, Vela, Perseus)
            val bulge = 0.68 * gaussian(l, 0.0, 22.0)
            val scutum = 0.28 * gaussian(l, 27.0, 10.0)
            val cygnus = 0.34 * gaussian(l, 76.0, 14.0)
            val cassPerseus = 0.18 * gaussian(l, 125.0, 22.0)
            val vela = 0.22 * gaussian(l, 265.0, 16.0)
            val carinaCrux = 0.44 * gaussian(l, 296.0, 16.0)
            val centaurusNorma = 0.30 * gaussian(l, 328.0, 15.0)
            val diskBaseline = 0.16

            val brightness = (diskBaseline + bulge + scutum + cygnus + cassPerseus + vela + carinaCrux + centaurusNorma)
                .coerceIn(0.16, 1.0)
                .toFloat()

            // Bulge is ~2x wider than the winter anticenter
            val widthScale = (0.68 + 0.78 * gaussian(l, 0.0, 26.0) + 0.25 * gaussian(l, 296.0, 18.0) + 0.20 * gaussian(l, 76.0, 16.0))
                .coerceIn(0.68, 1.50)
                .toFloat()

            // Great Rift dark dust lane (l ~ 345° through 0° to 85°) + Southern Coalsack (l ~ 303°)
            val greatRift = (0.85 * gaussian(l, 22.0, 28.0) + 0.65 * gaussian(l, 68.0, 18.0) + 0.45 * gaussian(l, 303.0, 7.0))
                .coerceIn(0.0, 0.90)
                .toFloat()

            // Warmth peaks in the old stellar population of the Galactic bulge and inner disk
            val warmth = (0.85 * gaussian(l, 0.0, 32.0) + 0.35 * gaussian(l, 296.0, 20.0) + 0.30 * gaussian(l, 27.0, 14.0))
                .coerceIn(0.0, 1.0)
                .toFloat()

            val branchOffsetDeg = 2.8 + 1.4 * greatRift
            GalacticCloudNode(
                lDeg = l,
                centerEq = CoordinateEngine.galacticToEquatorial(CoordinateEngine.Galactic(l, 0.0)),
                northBranchEq = CoordinateEngine.galacticToEquatorial(CoordinateEngine.Galactic(l, +branchOffsetDeg)),
                southBranchEq = CoordinateEngine.galacticToEquatorial(CoordinateEngine.Galactic(l, -branchOffsetDeg)),
                surfaceBrightness = brightness,
                widthScale = widthScale,
                riftStrength = greatRift,
                coreWarmth = warmth
            )
        }
    }

    /**
     * Deterministic unresolved star-cloud stipple points in J2000 (RA, Dec) concentrated in
     * the Sagittarius, Scutum, Cygnus, and Carina star clouds.
     */
    private val STAR_CLOUD_SPECKS: List<StarCloudSpeck> by lazy {
        val list = ArrayList<StarCloudSpeck>(140)
        for (i in 0 until 140) {
            val h1 = (i * 73 + 19) % 360
            val h2 = (i * 137 + 47) % 1000
            val h3 = (i * 251 + 83) % 1000

            // Bias longitude toward the rich star clouds (Sagittarius/Scutum/Cygnus/Carina)
            val lDeg = when (i % 5) {
                0, 1 -> ((h1 % 64) - 30.0 + 360.0) % 360.0 // Bulge & Scutum (-30°..+34°)
                2 -> 60.0 + (h1 % 34)                       // Cygnus (60°..94°)
                3 -> 280.0 + (h1 % 55)                      // Carina-Crux-Centaurus (280°..335°)
                else -> h1.toDouble()                       // General Galactic plane
            }
            // Latitude spread +/- 5.5°, avoiding the exact Great Rift center when rift is strong
            val rawB = ((h2 / 1000.0) - 0.5) * 11.0
            val bDeg = if (abs(rawB) < 1.1 && (lDeg < 80.0 || lDeg > 340.0)) {
                if (rawB >= 0.0) rawB + 1.6 else rawB - 1.6
            } else rawB

            val eq = CoordinateEngine.galacticToEquatorial(CoordinateEngine.Galactic(lDeg, bDeg))
            val radius = 0.55f + (h3 % 5) * 0.11f
            val alpha = 0.06f + (h2 % 7) * 0.015f
            val tint = when (i % 4) {
                0 -> Color(0xFFFFF8EB) // Warm cream star cloud
                1 -> Color(0xFFE2E8F0) // Soft silver-white
                2 -> Color(0xFFDBEAFE) // Faint OB-association blue-white
                else -> Color(0xFFFEF3C7) // Bulge gold-ivory
            }
            list.add(StarCloudSpeck(eq = eq, radiusPx = radius, baseAlpha = alpha, color = tint))
        }
        list
    }

    private fun horizonExtinctionFactor(altitudeDeg: Double): Float {
        if (altitudeDeg <= 2.5) return 0f
        if (altitudeDeg >= 24.0) return 1f
        val t = ((altitudeDeg - 2.5) / 21.5).toFloat().coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerpColor(a: Color, b: Color, t: Float): Color {
        val f = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * f,
            green = a.green + (b.green - a.green) * f,
            blue = a.blue + (b.blue - a.blue) * f,
            alpha = a.alpha + (b.alpha - a.alpha) * f
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun drawMilkyWay(
        drawScope: DrawScope,
        galacticPoints: List<GalacticEngine.GalacticPlanePoint>,
        lightingState: LightingState,
        frameTimeMs: Long,
        theme: SkyCanvasTheme = SkyCanvasTheme.CELESTIAL,
        latitudeDeg: Double = 0.0,
        lastDeg: Double = 0.0,
        projection: SkyProjection = HeroProjection
    ) {
        // Only render the photometric multi-isophote Milky Way in REAL_SKY mode;
        // the old generic stroke line remains removed across all themes.
        if (theme != SkyCanvasTheme.REAL_SKY) return
        if (lightingState.sunAltitudeDeg > -6.0) return

        val darknessFactor = (((-lightingState.sunAltitudeDeg - 6.0) / 11.0).coerceIn(0.0, 1.0)).toFloat()
        val moonDimming = (1.0f - lightingState.moonGlowIntensity * 0.72f).coerceIn(0.12f, 1.0f)
        val masterVisibility = darknessFactor * moonDimming
        if (masterVisibility <= 0.02f) return

        val width = drawScope.size.width
        val height = drawScope.size.height
        val horizonY = projection.clipBottomPx(height)

        // Colors from PiXiEED / pole-island-sky 5-level isophote palette
        val outerHaloTint = Color(0xFF5C6896)   // Level 1-2: diffuse silver-blue outer galactic halo
        val midCloudCool = Color(0xFF9692AA)    // Level 3: intermediate galactic disk
        val midCloudWarm = Color(0xFFBEACA6)    // Level 4: inner star clouds
        val bulgeCoreTint = Color(0xFFE6D2B8)   // Level 5: warm cream-ivory Sagittarius/Carina core

        drawScope.clipRect(left = 0f, top = 0f, right = width, bottom = horizonY) {
            // 1. Multi-isophote additive Gaussian star clouds & Great Rift bifurcation
            for (node in GALACTIC_NODES) {
                val centerHoriz = CoordinateEngine.equatorialToHorizontal(
                    equatorial = node.centerEq,
                    lastDeg = lastDeg,
                    latitudeDeg = latitudeDeg
                )
                if (centerHoriz.altitudeDeg <= 2.5) continue

                val extinction = horizonExtinctionFactor(centerHoriz.altitudeDeg)
                val nodeAlpha = masterVisibility * extinction * node.surfaceBrightness
                if (nodeAlpha <= 0.004f) continue

                val centerPos = projection.objectPosition(
                    centerHoriz.azimuthDeg,
                    centerHoriz.altitudeDeg,
                    width,
                    height,
                    latitudeDeg
                )

                // Level 1 & 2: Wide diffuse outer Galactic halo (52..86 px radius, very low alpha)
                val outerRadius = 56f * node.widthScale
                val outerAlpha = (nodeAlpha * 0.026f).coerceIn(0f, 0.032f)
                drawScope.drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to outerHaloTint.copy(alpha = outerAlpha),
                        0.50f to outerHaloTint.copy(alpha = outerAlpha * 0.48f),
                        1.0f to Color.Transparent,
                        center = centerPos,
                        radius = outerRadius
                    ),
                    radius = outerRadius,
                    center = centerPos,
                    blendMode = BlendMode.Plus
                )

                // Level 3 & 4: Bifurcated star clouds around the Great Rift dark dust lane
                val midTint = lerpColor(midCloudCool, midCloudWarm, node.coreWarmth)
                if (node.riftStrength > 0.22f) {
                    // Split luminosity into northern (b > 0) and southern (b < 0) branches around the dark dust rift
                    val northHoriz = CoordinateEngine.equatorialToHorizontal(
                        equatorial = node.northBranchEq,
                        lastDeg = lastDeg,
                        latitudeDeg = latitudeDeg
                    )
                    val southHoriz = CoordinateEngine.equatorialToHorizontal(
                        equatorial = node.southBranchEq,
                        lastDeg = lastDeg,
                        latitudeDeg = latitudeDeg
                    )
                    val branchRadius = 28f * node.widthScale
                    val branchAlpha = (nodeAlpha * 0.022f).coerceIn(0f, 0.028f)

                    if (northHoriz.altitudeDeg > 2.5) {
                        val nPos = projection.objectPosition(northHoriz.azimuthDeg, northHoriz.altitudeDeg, width, height, latitudeDeg)
                        drawScope.drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to midTint.copy(alpha = branchAlpha),
                                0.55f to midTint.copy(alpha = branchAlpha * 0.4f),
                                1.0f to Color.Transparent,
                                center = nPos,
                                radius = branchRadius
                            ),
                            radius = branchRadius,
                            center = nPos,
                            blendMode = BlendMode.Plus
                        )
                    }
                    if (southHoriz.altitudeDeg > 2.5) {
                        val sPos = projection.objectPosition(southHoriz.azimuthDeg, southHoriz.altitudeDeg, width, height, latitudeDeg)
                        val southBoost = if (node.coreWarmth > 0.5f) 1.25f else 1.0f
                        val sColor = lerpColor(midTint, bulgeCoreTint, node.coreWarmth * 0.7f)
                        drawScope.drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to sColor.copy(alpha = (branchAlpha * southBoost).coerceAtMost(0.034f)),
                                0.52f to sColor.copy(alpha = branchAlpha * 0.42f),
                                1.0f to Color.Transparent,
                                center = sPos,
                                radius = branchRadius * 1.1f
                            ),
                            radius = branchRadius * 1.1f,
                            center = sPos,
                            blendMode = BlendMode.Plus
                        )
                    }
                } else {
                    // Unsplit section of the Milky Way disk
                    val midRadius = 32f * node.widthScale
                    val midAlpha = (nodeAlpha * 0.024f).coerceIn(0f, 0.030f)
                    drawScope.drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to midTint.copy(alpha = midAlpha),
                            0.55f to midTint.copy(alpha = midAlpha * 0.42f),
                            1.0f to Color.Transparent,
                            center = centerPos,
                            radius = midRadius
                        ),
                        radius = midRadius,
                        center = centerPos,
                        blendMode = BlendMode.Plus
                    )
                }

                // Level 5: Brightest star-cloud cores (Sagittarius bulge, Scutum, Cygnus, Carina)
                if (node.surfaceBrightness > 0.52f) {
                    val coreStrength = ((node.surfaceBrightness - 0.52f) / 0.48f).coerceIn(0f, 1f)
                    val coreRadius = 22f * node.widthScale
                    val coreAlpha = (nodeAlpha * coreStrength * (1f - node.riftStrength * 0.45f) * 0.032f).coerceIn(0f, 0.035f)
                    if (coreAlpha > 0.003f) {
                        drawScope.drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to bulgeCoreTint.copy(alpha = coreAlpha),
                                0.50f to bulgeCoreTint.copy(alpha = coreAlpha * 0.40f),
                                1.0f to Color.Transparent,
                                center = centerPos,
                                radius = coreRadius
                            ),
                            radius = coreRadius,
                            center = centerPos,
                            blendMode = BlendMode.Plus
                        )
                    }
                }
            }

            // 2. Faint resolved star-cloud micro-specks (sub-pixel / pinpoint, moving rigidly with the sky)
            for (speck in STAR_CLOUD_SPECKS) {
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    equatorial = speck.eq,
                    lastDeg = lastDeg,
                    latitudeDeg = latitudeDeg
                )
                if (horiz.altitudeDeg <= 4.0) continue
                val ext = horizonExtinctionFactor(horiz.altitudeDeg)
                val alpha = (speck.baseAlpha * masterVisibility * ext).coerceIn(0f, 0.22f)
                if (alpha <= 0.015f) continue

                val pos = projection.objectPosition(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitudeDeg)
                drawScope.drawCircle(
                    color = speck.color.copy(alpha = alpha),
                    radius = speck.radiusPx,
                    center = pos
                )
            }
        }
    }
}
