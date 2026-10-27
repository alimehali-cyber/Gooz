package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ground half of the full-bleed Home scene.
 *
 * Two art paths share this entry point:
 *
 *  - **Stylised themes** keep the original simple band — two sine ridges, a mist shelf, solid
 *    ground and a grass line — because flat illustration is those themes' whole point.
 *  - **Photographic themes with the Persepolis + Mil-e Azhdaha relief** get the full
 *    Marvdasht landscape ([drawMarvdashtLandscape]): a haze-layered far Zagros range and
 *    the Kuh-e Rahmat limestone wall built from geology-shaped profiles (soft bedrock
 *    benching, a carved water-gap, strata dipping off the fold hinge, pale caprock, talus
 *    cones and cliff streaks) with sun-facing facet shading, piedmont foothills and
 *    alluvial fans, a semi-arid steppe/cultivation plain with an irrigation canal, the
 *    monuments' own earthworks, a broad foreground Pulvar river (wet banks, shallow
 *    bed, deep thalweg, point bars, sheen/stochastic glitter tracking the real Sun or
 *    Moon, tree reflections, reed clumps), a space-colonization pistachio parkland with
 *    Artemisia sage scrub, and a three-band batched grass system (Voronoi clumps,
 *    Bézier blades, Stipa/Bromus/riparian species). It is the monuments' actual geography —
 *    Persepolis stands at the foot of Kuh-e Rahmat on the Marvdasht plain near the Pulvar
 *    River, ringed by Zagros ranges — art-directed to the scene's framing.
 *
 * Everything is generated from the scene composition, theme palette and live lighting, and
 * every placement is deterministic, so the landscape never shimmers between clock ticks.
 */
object SceneGroundRenderer {

    // Art-directed skyline envelopes (see LandscapeProfile): x in 0..1 across the canvas.
    // Far Zagros: a complete encircling range, saddled behind Persepolis. Kuh-e Rahmat: a
    // low shoulder behind the ruins and readout, swelling to the great crest at the right
    // that backs the Mil-e Azhdaha tower, then continuing off-frame.
    private val FAR_ZAGROS_ENVELOPE = floatArrayOf(
        0f, 0.70f, 0.18f, 0.95f, 0.38f, 0.75f, 0.55f, 0.92f, 0.72f, 0.88f, 0.88f, 1f, 1f, 0.90f
    )
    // Kuh-e Rahmat as a folded whaleback anticline: long gentle back-limb rising from the
    // left, hinge near x = 0.76, then the crest continuing high off-frame at the right.
    private val KUH_RAHMAT_ENVELOPE = floatArrayOf(
        0f, 0.32f, 0.14f, 0.40f, 0.30f, 0.50f, 0.45f, 0.58f, 0.58f, 0.62f,
        0.68f, 0.88f, 0.76f, 1f, 0.86f, 0.88f, 1f, 0.72f
    )
    private val FOOTHILL_ENVELOPE = floatArrayOf(0f, 0.7f, 0.5f, 1f, 1f, 0.5f)

    // Space-colonization tree skeletons (LandscapeProfile.treeSkeleton), grown once on
    // first use and cached for the process — wild pistachio/almond forms in unit space.
    // Generation is bounded (<= 420 nodes each) and happens off the draw hot path; the
    // per-frame cost is only strokes into the cached geometry.
    private val TREE_SKELETONS: List<List<LandscapeProfile.BranchSegment>> by lazy {
        listOf(
            LandscapeProfile.treeSkeleton(
                seed = 101, aspect = 1.30f, trunkFraction = 0.36f, attractorCount = 110, lean = 0.03f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 202, aspect = 1.15f, trunkFraction = 0.40f, attractorCount = 100, lean = -0.02f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 303, aspect = 1.45f, trunkFraction = 0.30f, attractorCount = 85, lean = 0.05f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 505, aspect = 1.05f, trunkFraction = 0.44f, attractorCount = 90, lean = 0.08f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 606, aspect = 1.55f, trunkFraction = 0.26f, attractorCount = 95, lean = -0.06f
            ).sortedByDescending { it.r0 }
        )
    }
    private val BUSH_SKELETONS: List<List<LandscapeProfile.BranchSegment>> by lazy {
        (0 until 3).map { k ->
            LandscapeProfile.treeSkeleton(
                seed = 404 + k * 77, aspect = 1.5f, trunkFraction = 0.12f, attractorCount = 46
            ).sortedByDescending { it.r0 }
        }
    }

    /**
     * @param persepolis resolved combined-landscape framing for this exact canvas, or null when
     * the ruins are not part of the scene. When set (the photographic themes), the full
     * Marvdasht landscape is painted and the GL stone sits *in* it: the ranges are its
     * backdrop, footprint-local worn-earth aprons, contact shadows and debris are painted
     * from the Persepolis platform bounds and the Mil-e Azhdaha slope's projected bounds,
     * and the plain/stream below the toe remain the foreground the framing intends.
     * @param sunPosPx the Sun's projected screen position this frame, or null when far below
     * the horizon — it aims the mountain facet light, the cast shadows and the river glitter.
     * @param moonPosPx the Moon's projected screen position, for the night glitter.
     */
    fun draw(
        drawScope: DrawScope,
        composition: SceneComposition,
        theme: SkyCanvasTheme,
        lighting: LightingState,
        persepolis: PersepolisFraming.Result? = null,
        sunPosPx: Offset? = null,
        moonPosPx: Offset? = null
    ) {
        val palette = scenePalette(theme, lighting)
        val width = composition.widthPx
        val height = composition.heightPx
        val horizonY = composition.horizonY
        val papercraft = theme == SkyCanvasTheme.PAPERCRAFT_DIORAMA

        if (persepolis != null) {
            drawMarvdashtLandscape(drawScope, composition, palette, lighting, persepolis, sunPosPx, moonPosPx)
            drawBottomFade(drawScope, width, height)
            return
        }

        // 1. Distant ridge, just under the horizon.
        val farRidge = ridgePath(
            width = width,
            baselineY = horizonY + height * 0.012f,
            amplitude = composition.farRidgeAmplitude,
            phase = 0.6f,
            waves = 3.0f,
            detail = 7.0f
        )
        drawScope.drawPath(path = farRidge, color = palette.farRidge)
        if (papercraft) {
            drawScope.drawPath(path = farRidge, color = palette.cutEdge, style = Stroke(width = 1.4f))
        }

        // (No horizon mist shelf: a full-width pale haze band centred on the horizon read as
        // a white stripe across the scene, right behind the readout's day/date line. The
        // ridges carry their own alpha-blended aerial perspective instead.)

        // 3. Mid ridge, closer and darker, anchored on the ground band.
        val midRidge = ridgePath(
            width = width,
            baselineY = composition.groundTopY,
            amplitude = composition.midRidgeAmplitude,
            phase = 2.1f,
            waves = 2.0f,
            detail = 9.0f
        )
        drawScope.drawPath(path = midRidge, color = palette.midRidge)
        if (papercraft) {
            drawScope.drawPath(path = midRidge, color = palette.cutEdge, style = Stroke(width = 1.4f))
        }

        // 4. The solid ground.
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to palette.groundTop,
                0.35f to palette.groundTop,
                1.0f to palette.groundBottom,
                startY = composition.groundTopY,
                endY = height
            ),
            topLeft = Offset(0f, composition.groundTopY),
            size = androidx.compose.ui.geometry.Size(width, (height - composition.groundTopY).coerceAtLeast(1f))
        )

        // 5. A thin lit edge where the ground meets the mist: the horizon catch-light.
        val edgeAlpha = 0.10f + 0.18f * lighting.ambientBrightness.coerceIn(0f, 1f)
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to palette.haze.copy(alpha = edgeAlpha),
                1.0f to Color.Transparent,
                startY = composition.groundTopY,
                endY = composition.groundTopY + height * 0.06f
            ),
            topLeft = Offset(0f, composition.groundTopY),
            size = androidx.compose.ui.geometry.Size(width, height * 0.06f)
        )

        // 5b. Combined-landscape earthwork: the worn ground the platform and tower slope stand in.
        if (persepolis != null) {
            drawCombinedLandscapeGrounding(drawScope, composition, palette, lighting, persepolis)
        }

        // 6. Grass line. Papercraft keeps hard cardstock edges instead of strokes.
        if (!papercraft) {
            drawGrass(
                drawScope = drawScope,
                composition = composition,
                color = palette.grass,
                brightness = lighting.ambientBrightness.coerceIn(0f, 1f)
            )
        }

        // 7. A soft vignette at the foot of the scene.
        drawBottomFade(drawScope, width, height)
    }

    /**
     * The shared foot vignette: depth on any theme background, and it stops the ground from
     * ending on a flat line where the feed begins.
     */
    private fun drawBottomFade(drawScope: DrawScope, width: Float, height: Float) {
        val fadeTop = height * 0.90f
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                1.0f to Color.Black.copy(alpha = 0.26f),
                startY = fadeTop,
                endY = height
            ),
            topLeft = Offset(0f, fadeTop),
            size = androidx.compose.ui.geometry.Size(width, height - fadeTop)
        )
    }

    /** This colour at [factor]× brightness (alpha unchanged). */
    private fun Color.scaled(factor: Float): Color = Color(
        red = (red * factor).coerceIn(0f, 1f),
        green = (green * factor).coerceIn(0f, 1f),
        blue = (blue * factor).coerceIn(0f, 1f),
        alpha = alpha
    )

    /**
     * One soft elliptical patch (single colour) with the many-equal-alpha-shell falloff the
     * grounding drifts use: source-over accumulation reaches [peakAlpha] at the centre in
     * steps too small to read as contour rings.
     */
    private fun drawSoftPatch(
        drawScope: DrawScope,
        cx: Float,
        cy: Float,
        patchWidth: Float,
        patchHeight: Float,
        color: Color,
        peakAlpha: Float,
        layers: Int = 10
    ) {
        val safeLayers = layers.coerceAtLeast(2)
        val shellAlpha = 1f - (1f - peakAlpha.coerceIn(0f, 0.95f)).pow(1f / safeLayers)
        for (layer in 0 until safeLayers) {
            val t = layer / (safeLayers - 1).toFloat()
            val scale = 1f - 0.92f * t
            val w = patchWidth * scale
            val h = patchHeight * scale
            drawScope.drawOval(
                color = color.copy(alpha = shellAlpha * color.alpha),
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = androidx.compose.ui.geometry.Size(w, h)
            )
        }
    }

    /**
     * One mountain range as sun-faceted vertical strips over a [LandscapeProfile] skyline.
     *
     * Each strip is a trapezoid from the crest line down to [baseY], filled with a vertical
     * gradient: slope-facing facet colour at the top (lit/shade chosen by the profile's wide-
     * window slope against the Sun's side, so whole faces light coherently instead of
     * striping), darkening and dissolving into [haze] toward the foot — aerial perspective
     * doing the depth work. A whisper of altitude banding ([strataAmp]) suggests folded
     * limestone bedding without drawing a single hard contour, and a hairline crest
     * catch-light separates the skyline from the sky at glancing light.
     */
    private fun drawMountainLayer(
        drawScope: DrawScope,
        width: Float,
        sceneHeight: Float,
        baseY: Float,
        heights: FloatArray,
        lit: Color,
        shade: Color,
        haze: Color,
        hazeTop: Float,
        hazeBase: Float,
        sunSign: Float,
        facetStrength: Float,
        strataAmp: Float,
        microSeed: Int,
        crestAlpha: Float,
        strataDip: Float = 0f,
        hingeX: Float = 0f,
        capAmount: Float = 0f
    ) {
        val n = heights.size - 1
        if (n < 2 || width <= 0f) return
        val step = width / n
        for (i in 0 until n) {
            val h0 = heights[i]
            val h1 = heights[i + 1]
            if (h0 < 1f && h1 < 1f) continue
            val x0 = step * i
            val x1 = step * (i + 1)
            val top0 = baseY - h0
            val top1 = baseY - h1
            val lo = heights[(i - 2).coerceAtLeast(0)]
            val hi = heights[(i + 3).coerceAtMost(n)]
            val slope = (hi - lo) / (step * 4f).coerceAtLeast(1f)
            val facing = (-slope * 1.05f * sunSign).coerceIn(-1f, 1f)
            val micro = 0.95f + 0.10f * LandscapeProfile.hash01(microSeed, i * 37 + 11)
            val facet = lerp(shade, lit, 0.5f + 0.5f * facing * facetStrength).scaled(micro)

            fun colorAt(frac: Float): Color {
                var c = lerp(facet, facet.scaled(0.80f), frac)
                c = lerp(c, haze, (hazeTop + (hazeBase - hazeTop) * frac).coerceIn(0f, 1f))
                if (capAmount > 0f) {
                    // Pale hard-limestone caprock hugging the crest (Asmari-type beds).
                    val cap = (1f - frac * 3.3f).coerceIn(0f, 1f)
                    if (cap > 0f) c = lerp(c, c.scaled(1.28f), cap * capAmount)
                }
                if (strataAmp > 0f) {
                    val yTop = (top0 + top1) / 2f
                    val y = yTop + (baseY - yTop) * frac
                    // Beds dip away from the fold hinge in a chevron (fold-and-thrust Zagros),
                    // not flat contour lines: the phase field tilts with |x - hinge|.
                    val xMid = (x0 + x1) / 2f
                    val k = 1f + strataAmp *
                        sin((((baseY - y) + strataDip * abs(xMid - hingeX)) / sceneHeight) * 105f +
                            1.7f * sin(i * 0.11f))
                    c = c.scaled(k)
                }
                return c
            }

            val strip = Path().apply {
                moveTo(x0, top0)
                lineTo(x1, top1)
                lineTo(x1, baseY)
                lineTo(x0, baseY)
                close()
            }
            drawScope.drawPath(
                path = strip,
                brush = Brush.verticalGradient(
                    0.0f to colorAt(0f),
                    0.38f to colorAt(0.38f),
                    0.72f to colorAt(0.72f),
                    1.0f to colorAt(1f),
                    startY = minOf(top0, top1),
                    endY = baseY
                )
            )
        }
        if (crestAlpha > 0.01f) {
            val crest = Path()
            var penDown = false
            for (i in heights.indices) {
                val h = heights[i]
                val x = step * i
                val y = baseY - h
                if (h > sceneHeight * 0.012f) {
                    if (penDown) crest.lineTo(x, y) else {
                        crest.moveTo(x, y)
                        penDown = true
                    }
                } else {
                    penDown = false
                }
            }
            drawScope.drawPath(
                path = crest,
                color = lerp(lit, Color.White, 0.35f).copy(alpha = crestAlpha),
                style = Stroke(width = (sceneHeight * 0.0012f).coerceIn(0.8f, 2f))
            )
        }
    }

    /**
     * The photographic Marvdasht landscape the two monuments stand in.
     *
     * Back to front: far Zagros range in haze; semi-arid steppe plain; the
     * folded Kuh-e Rahmat wall (benched profile, water-gap, dipping strata, caprock,
     * gullies, talus, varnish streaks); piedmont foothills and alluvial fans; a
     * cultivation mosaic and canal tree-line; the monuments' own earthworks; the broad
     * Pulvar river (layered water, point bars, ripples, pebbles, sheen + thinned glitter,
     * tree reflections, reed clumps); a space-colonization tree parkland and sage scrub;
     * a three-band batched grass system. Composition and parameters were tuned in
     * `tools/persepolis/render_framing_preview.py` against the exact framing; colours are
     * derived per-frame from the theme palette and the live Sun, so the whole valley warms
     * at golden hour and dissolves into a moonlit silhouette at night. The GL relief
     * composites above this paint, so everything here is either behind the stone (ranges,
     * foothills) or below its contact rows (plain, stream, foreground trees).
     */
    private fun drawMarvdashtLandscape(
        drawScope: DrawScope,
        composition: SceneComposition,
        palette: ScenePalette,
        lighting: LightingState,
        framing: PersepolisFraming.Result,
        sunPosPx: Offset?,
        moonPosPx: Offset?
    ) {
        val width = composition.widthPx
        val height = composition.heightPx
        val horizonY = composition.horizonY
        val groundTopY = composition.groundTopY
        val toeY = framing.toeYPx
        val day = lighting.ambientBrightness.coerceIn(0f, 1f)
        val moonGlow = lighting.moonGlowIntensity.coerceIn(0f, 1f)
        val lightLevel = maxOf(day, moonGlow * 0.35f)
        val sunAlt = lighting.sunAltitudeDeg.toFloat()
        val sunSign = if (sunPosPx != null && sunPosPx.x >= width * 0.5f) 1f else -1f
        val atmosphere = palette.haze
        val sand = lerp(Color(0xFF292216), Color(0xFFBEA06E), day)

        val rockLit = lerp(Color(0xFF161C28), Color(0xFFA68B6E), lightLevel)
        val rockShade = lerp(Color(0xFF0B1018), Color(0xFF6E5A4C), lightLevel)

        fun oval(cx: Float, cy: Float, w: Float, h: Float, color: Color) {
            drawScope.drawOval(
                color = color,
                topLeft = Offset(cx - w / 2f, cy - h / 2f),
                size = androidx.compose.ui.geometry.Size(w, h)
            )
        }

        // 1. Far Zagros range: the encircling mountains, deep in aerial perspective. Its
        //    strips fill down to the plain so no sky sliver survives under the range.
        val farBase = groundTopY + height * 0.006f
        drawMountainLayer(
            drawScope = drawScope, width = width, sceneHeight = height, baseY = farBase,
            heights = LandscapeProfile.mountainHeights(
                120, height * 0.130f, FAR_ZAGROS_ENVELOPE, seed = 11,
                benchAmp = 0.12f, benchStep = 0.16f
            ),
            lit = lerp(palette.farRidge, rockLit, 0.35f),
            shade = lerp(palette.farRidge, rockShade, 0.35f),
            haze = atmosphere, hazeTop = 0.28f, hazeBase = 0.55f,
            sunSign = sunSign, facetStrength = 0.32f, strataAmp = 0f,
            microSeed = 3, crestAlpha = 0.05f + 0.10f * lightLevel, capAmount = 0.25f
        )

        // (No horizon mist shelf here either — with the hazy far range behind it, the old
        // shelf stacked into a white band across the canvas at the readout's day/date line.
        // The range's own haze dissolve supplies the aerial perspective by itself.)

        // 3. The Marvdasht plain: dry olive-straw steppe, darkening toward the viewer,
        //    with a horizon catch-light at its top edge.
        val steppeTop = lerp(palette.groundTop, Color(0xFF7C7046), 0.55f * day)
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to steppeTop,
                0.38f to lerp(steppeTop, palette.groundBottom, 0.45f),
                1.0f to palette.groundBottom,
                startY = groundTopY,
                endY = height
            ),
            topLeft = Offset(0f, groundTopY),
            size = androidx.compose.ui.geometry.Size(width, (height - groundTopY).coerceAtLeast(1f))
        )
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to atmosphere.copy(alpha = 0.10f + 0.18f * day),
                1.0f to Color.Transparent,
                startY = groundTopY,
                endY = groundTopY + height * 0.06f
            ),
            topLeft = Offset(0f, groundTopY),
            size = androidx.compose.ui.geometry.Size(width, height * 0.06f)
        )

        // 4. Kuh-e Rahmat: the great limestone wall at Persepolis' back. Nearer, warmer and
        //    more strongly faceted than the far range, with folded strata and gullies.
        val massifBase = toeY - height * 0.004f
        val massifHeights = LandscapeProfile.mountainHeights(
            140, height * 0.205f, KUH_RAHMAT_ENVELOPE, seed = 29,
            benchAmp = 0.22f, benchStep = 0.13f,
            notches = floatArrayOf(0.63f, 0.030f, 0.38f) // water-gap; its fan spills below
        )
        drawMountainLayer(
            drawScope = drawScope, width = width, sceneHeight = height, baseY = massifBase,
            heights = massifHeights,
            lit = rockLit, shade = rockShade,
            haze = atmosphere, hazeTop = 0.10f, hazeBase = 0.30f,
            sunSign = sunSign, facetStrength = 0.65f, strataAmp = 0.045f,
            microSeed = 5, crestAlpha = 0.08f + 0.16f * lightLevel,
            strataDip = 0.9f, hingeX = width * 0.76f, capAmount = 0.38f
        )
        // Erosion gullies: dark seams from crest to fan apex with a faint lit lip.
        fun massifTopAt(x: Float): Float =
            massifBase - LandscapeProfile.heightAt(massifHeights, (x / width).coerceIn(0f, 1f))
        val gullyDark = rockShade.scaled(0.55f).copy(alpha = 0.10f + 0.22f * lightLevel)
        val gullyLip = rockLit.copy(alpha = 0.12f * lightLevel)
        val gullyWidth = (height * 0.0016f).coerceIn(1f, 3f)
        for (k in 0 until 11) {
            val p = 0.35f + 0.63f * LandscapeProfile.hash01(317, k * 13 + 4)
            val x0 = p * width
            val top0 = massifTopAt(x0)
            if (massifBase - top0 < height * 0.04f) continue
            val drift = (LandscapeProfile.hash01(173, k * 7 + 9) - 0.5f) * width * 0.05f
            val dark = Path()
            val lip = Path()
            for (s in 0..8) {
                val t = s / 8f
                val x = x0 + drift * t + sin(t * 5f + k * 1.7f) * height * 0.004f * (1f - t)
                val y = maxOf(top0 + (massifBase - top0) * t, massifTopAt(x) + 2f)
                if (s == 0) {
                    dark.moveTo(x, y)
                    lip.moveTo(x + gullyWidth, y)
                } else {
                    dark.lineTo(x, y)
                    lip.lineTo(x + gullyWidth, y)
                }
            }
            drawScope.drawPath(dark, gullyDark, style = Stroke(width = gullyWidth))
            drawScope.drawPath(lip, gullyLip, style = Stroke(width = gullyWidth * 0.6f))
        }
        // Talus cones under the steepest faces: scree resting at ~35 degrees of repose,
        // paler and warmer than the bedrock above it.
        run {
            val nIdx = massifHeights.size - 1
            val stepX = width / nIdx
            val chosenX = ArrayList<Float>()
            val order = (4 until nIdx - 3).sortedByDescending { i ->
                abs(massifHeights[i + 3] - massifHeights[i - 3])
            }
            for (i in order) {
                val h = massifHeights[i]
                val x = stepX * i
                if (h < height * 0.07f) continue
                if (chosenX.any { abs(it - x) < width * 0.09f }) continue
                chosenX.add(x)
                val apexY = massifBase - h * 0.38f
                val halfW = h * 0.38f * 1.43f
                val footY = massifBase + height * 0.012f
                val cone = Path().apply {
                    moveTo(x, apexY)
                    lineTo(x - halfW, footY)
                    lineTo(x + halfW, footY)
                    close()
                }
                drawScope.drawPath(cone, sand.copy(alpha = 0.05f + 0.11f * lightLevel))
                for (r in listOf(-0.5f, 0f, 0.5f)) {
                    drawScope.drawLine(
                        color = sand.copy(alpha = 0.04f + 0.06f * lightLevel),
                        start = Offset(x, apexY),
                        end = Offset(x + halfW * r, footY),
                        strokeWidth = 1f,
                        cap = StrokeCap.Round
                    )
                }
                if (chosenX.size >= 5) break
            }
        }
        // Desert-varnish streaks: dark rain-washed runnels on the steepest cliffs only.
        run {
            val nIdx = massifHeights.size - 1
            val stepX = width / nIdx
            val streakColor = rockShade.scaled(0.45f).copy(alpha = 0.10f + 0.12f * lightLevel)
            var drawn = 0
            for (i in 3 until nIdx - 2) {
                val slope = abs(massifHeights[i + 2] - massifHeights[i - 2]) /
                    (stepX * 4f).coerceAtLeast(1f)
                if (slope < 0.55f) continue
                if (LandscapeProfile.hash01(331, i * 7 + 3) > 0.45f) continue
                val x = stepX * i + (LandscapeProfile.hash01(337, i * 5 + 1) - 0.5f) * stepX
                val y0 = massifTopAt(x) + height * 0.006f
                drawScope.drawLine(
                    color = streakColor,
                    start = Offset(x, y0),
                    end = Offset(
                        x + height * 0.002f,
                        y0 + height * (0.016f + 0.020f * LandscapeProfile.hash01(339, i * 3 + 2))
                    ),
                    strokeWidth = (height * 0.0016f).coerceIn(1f, 3f),
                    cap = StrokeCap.Round
                )
                if (++drawn >= 14) break
            }
        }
        // Haze pooling where the massif meets the piedmont.
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.6f to atmosphere.copy(alpha = 0.16f),
                1.0f to Color.Transparent,
                startY = massifBase - height * 0.028f,
                endY = massifBase + height * 0.010f
            ),
            topLeft = Offset(0f, massifBase - height * 0.028f),
            size = androidx.compose.ui.geometry.Size(width, height * 0.038f)
        )

        // 5. Piedmont foothills: one low eroded undulation in front of the massif foot.
        val footBase = toeY + height * 0.028f
        val footHeights = LandscapeProfile.foothillHeights(80, height * 0.021f, FOOTHILL_ENVELOPE, seed = 77)
        val foothill = Path().apply {
            val n = footHeights.size - 1
            moveTo(0f, footBase - footHeights[0])
            for (i in 1..n) lineTo(width * i / n, footBase - footHeights[i])
            lineTo(width, footBase)
            lineTo(0f, footBase)
            close()
        }
        drawScope.drawPath(
            path = foothill,
            brush = Brush.verticalGradient(
                0.0f to lerp(palette.midRidge, Color(0xFF77683F), 0.5f * day),
                1.0f to lerp(palette.midRidge.scaled(0.70f), Color(0xFF4E4429), 0.5f * day),
                startY = footBase - height * 0.021f,
                endY = footBase
            )
        )

        // 6. Alluvial fans: pale gravel skirts where the gullies debouch onto the plain.
        val fanColor = lerp(steppeTop, Color(0xFFB29066), 0.55f * day)
            .copy(alpha = 0.14f + 0.12f * day)
        for ((p, spread) in listOf(0.56f to 0.075f, 0.63f to 0.095f, 0.82f to 0.08f, 0.93f to 0.07f)) {
            val ax = p * width
            val ay = massifBase - 2f
            val by = toeY + height * 0.036f
            val fan = Path().apply {
                moveTo(ax, ay)
                for (j in 0..6) {
                    val t = j / 6f
                    lineTo(
                        ax + width * spread * (t * 2f - 1f),
                        by + height * 0.007f * sin(PI.toFloat() * t)
                    )
                }
                close()
            }
            drawScope.drawPath(fan, fanColor)
            for (r in listOf(-0.55f, 0f, 0.55f)) {
                drawScope.drawLine(
                    color = sand.copy(alpha = 0.08f * lightLevel),
                    start = Offset(ax, ay),
                    end = Offset(ax + width * spread * r, by),
                    strokeWidth = 1f,
                    cap = StrokeCap.Round
                )
            }
        }

        // 7. Cultivation mosaic: vast soft plots, irrigated green against fallow straw,
        //    kept feather-edged and low-contrast so the plain reads as land use, not shapes.
        val irrigated = lerp(palette.groundTop, Color(0xFF5C7038), 0.5f * day)
            .copy(alpha = 0.16f + 0.14f * day)
        val fallow = lerp(palette.groundTop, Color(0xFFB59660), 0.5f * day)
            .copy(alpha = 0.14f + 0.12f * day)
        for ((cx, cy, w, h, green) in listOf(
            listOf(0.16f, 0.875f, 0.55f, 0.040f, 1f), listOf(0.62f, 0.855f, 0.62f, 0.034f, 0f),
            listOf(0.40f, 0.935f, 0.70f, 0.050f, 1f), listOf(0.85f, 0.920f, 0.55f, 0.044f, 0f),
            listOf(0.08f, 0.960f, 0.50f, 0.046f, 0f)
        )) {
            oval(width * cx, height * cy, width * w, height * h, if (green > 0.5f) irrigated else fallow)
        }

        // 8. An irrigation canal and its tree line crossing the mid-foreground — the
        //    Marvdasht plain has been farmed since Cyrus, and a green thread through dry
        //    steppe is its most characteristic mark.
        val canopy = lerp(Color(0xFF0D140D), Color(0xFF415232), lightLevel)
        val canopyDark = canopy.scaled(0.70f)
        val canopyLight = lerp(canopy, Color(0xFF93A468), 0.45f * lightLevel)
        val canalY = toeY + height * 0.048f
        drawScope.drawLine(
            color = lerp(palette.groundTop, Color(0xFF33402A), 0.5f * day).copy(alpha = 0.55f),
            start = Offset(width * 0.015f, canalY - height * 0.004f),
            end = Offset(width * 0.985f, canalY + height * 0.006f),
            strokeWidth = (height * 0.0022f).coerceIn(1f, 4f),
            cap = StrokeCap.Round
        )
        for (i in 0 until 26) {
            val x = width * (0.03f + 0.94f * (i + LandscapeProfile.hash01(77, i * 3 + 1)) / 26f)
            val y = canalY - height * 0.004f + (x / width) * height * 0.010f
            val r = height * (0.0025f + 0.0035f * LandscapeProfile.hash01(33, i * 5 + 2))
            oval(x, y, r * 2f, r * 1.4f, canopyDark.copy(alpha = 0.55f))
        }

        // 9. The monuments' own earthworks (aprons, talus, contact shadows, debris).
        drawCombinedLandscapeGrounding(drawScope, composition, palette, lighting, framing)

        // 10. The Pulvar river in the near foreground: the viewer stands on its far bank.
        // Doubled in width again (v3): the centreline sits a little lower so the far edge
        // of the wet-bank fringe still clears the Mil slope's front step at every meander.
        val riverY = (framing.milSlopeFrontYPx + height * 0.062f).coerceIn(height * 0.895f, height * 0.945f)
        val riverMargin = width * 0.02f
        fun riverP(x: Float): Float = ((x + riverMargin) / (width + 2f * riverMargin)).coerceIn(0f, 1f)
        fun riverCenterY(x: Float): Float {
            val p = riverP(x)
            return riverY + height * 0.017f * sin(p * 4.6f + 0.7f) +
                height * 0.008f * LandscapeProfile.fbm(p * 3.1f, 41, octaves = 3) + p * height * 0.014f
        }
        fun riverHalfW(x: Float, y: Float): Float {
            val p = riverP(x)
            val depth = ((y / height - 0.86f) / 0.12f).coerceIn(0f, 1f)
            val pool = 1f + 0.45f * maxOf(0f, sin(p * 4.6f + 2.0f))
            // ~3.8x the original stream (doubled twice): a broad river whose near edge can
            // run out of the frame's bottom-right pools — the viewer stands in the shallows.
            return height * (0.021f + 0.025f * depth) * pool
        }
        // Meander phase: +1 bulges toward the viewer, -1 away. Drives the thalweg offset
        // (deep water hugs the outer bank) and the gravel point bars (inner bank).
        fun riverBend(x: Float): Float = sin(riverP(x) * 4.6f + 0.7f)
        fun ribbonPath(scale: Float, offset: Float = 0f): Path = Path().apply {
            val steps = 28
            for (i in 0..steps) {
                val x = -riverMargin + (width + 2f * riverMargin) * i / steps
                val yc = riverCenterY(x)
                val y = yc + riverBend(x) * offset * riverHalfW(x, yc) - riverHalfW(x, yc) * scale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            for (i in steps downTo 0) {
                val x = -riverMargin + (width + 2f * riverMargin) * i / steps
                val yc = riverCenterY(x)
                lineTo(x, yc + riverBend(x) * offset * riverHalfW(x, yc) + riverHalfW(x, yc) * scale)
            }
            close()
        }
        fun bankPath(sign: Float): Path = Path().apply {
            val steps = 28
            for (i in 0..steps) {
                val x = -riverMargin + (width + 2f * riverMargin) * i / steps
                val y = riverCenterY(x) + sign * riverHalfW(x, riverCenterY(x))
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val deepWater = lerp(Color(0xFF0A141C), Color(0xFF2E4A52), day)
        val shallowWater = lerp(Color(0xFF0E1814), Color(0xFF4A5A4C), day) // bed through thin water
        val wetBank = lerp(Color(0xFF0C0E08), Color(0xFF4C4434), day)      // damp darkened shore
        val reflection = lerp(lerp(lighting.horizonTone, lighting.skyTone, 0.35f), deepWater, 0.22f)
        // Layered water, wettest to deepest: damp bank fringe, shallow bed showing at the
        // edges, the sky's reflection, then the deep thalweg hugging each outer bank.
        drawScope.drawPath(ribbonPath(1.18f), wetBank.copy(alpha = 0.55f + 0.30f * day))
        drawScope.drawPath(ribbonPath(1f), shallowWater)
        drawScope.drawPath(ribbonPath(0.84f), reflection.copy(alpha = 0.90f))
        drawScope.drawPath(ribbonPath(0.22f, offset = 0.45f), deepWater.copy(alpha = 0.60f))
        for (i in 0 until 8) { // dark slow water breaking the reflection
            val x = width * LandscapeProfile.hash01(97, i * 11 + 4)
            val yc = riverCenterY(x)
            drawSoftPatch(drawScope, x, yc, width * 0.07f, riverHalfW(x, yc) * 1.0f, deepWater, 0.18f, layers = 8)
        }
        // Pale gravel point bars inside the bends, where the current drops its load.
        for (i in 0..28) {
            val x = -riverMargin + (width + 2f * riverMargin) * i / 28f
            val b = riverBend(x)
            if (abs(b) <= 0.72f) continue
            val yc = riverCenterY(x)
            drawSoftPatch(
                drawScope, x, yc - b * riverHalfW(x, yc) * 0.82f,
                width * 0.07f, riverHalfW(x, yc) * 0.5f, sand,
                peakAlpha = 0.10f + 0.20f * day, layers = 8
            )
        }
        // Flow ripples: small sky-coloured dashes riding the current.
        val rippleColor = lerp(lighting.skyTone, Color.White, 0.25f)
        for (i in 0 until 90) {
            val x = width * LandscapeProfile.hash01(179, i * 17 + 3)
            val yc = riverCenterY(x)
            val y = yc + (LandscapeProfile.hash01(73, i * 7 + 5) - 0.5f) * 1.7f * riverHalfW(x, yc)
            val len = height * (0.004f + 0.010f * LandscapeProfile.hash01(39, i * 3 + 1))
            drawScope.drawLine(
                color = rippleColor.copy(alpha = 0.05f + 0.13f * lightLevel),
                start = Offset(x, y),
                end = Offset(x + len, y + len * 0.10f),
                strokeWidth = (height * 0.0014f).coerceIn(0.8f, 2.5f),
                cap = StrokeCap.Round
            )
        }
        // Submerged pebbles freckling the near shallows.
        for (i in 0 until 26) {
            val x = width * LandscapeProfile.hash01(231, i * 19 + 5)
            val yc = riverCenterY(x)
            val y = yc + riverHalfW(x, yc) * (0.62f + 0.3f * LandscapeProfile.hash01(47, i * 3 + 2))
            val r = height * (0.0012f + 0.0018f * LandscapeProfile.hash01(63, i * 5 + 1))
            oval(x, y, r * 2f, r, sand.copy(alpha = 0.10f + 0.18f * day))
        }
        drawScope.drawPath(
            bankPath(-1f),
            canopyDark.copy(alpha = 0.45f),
            style = Stroke(width = (height * 0.0016f).coerceIn(1f, 3f))
        )
        drawScope.drawPath(
            bankPath(1f),
            sand.copy(alpha = 0.40f),
            style = Stroke(width = (height * 0.0016f).coerceIn(1f, 3f))
        )
        val riparian = lerp(canopy, Color(0xFF5A7244), 0.4f * day)
        for (i in 0 until 12) { // greener grass along the far bank
            val x = width * (i + LandscapeProfile.hash01(51, i * 3 + 1)) / 12f
            val yc = riverCenterY(x)
            drawSoftPatch(
                drawScope, x, yc - riverHalfW(x, yc) - height * 0.004f,
                width * 0.13f, height * 0.016f, riparian, peakAlpha = 0.06f + 0.14f * day, layers = 10
            )
        }
        // Glitter: broken dashes concentrated in the Sun's (or Moon's) reflection column.
        val glint: Triple<Float, Float, Color>? = when {
            sunPosPx != null && sunAlt > -2f -> Triple(
                sunPosPx.x.coerceIn(0f, width),
                (1f - sunAlt.coerceIn(0f, 38f) / 38f) * 0.9f,
                lerp(Color(0xFFFFE9C4), lighting.horizonTone, 0.35f)
            )
            moonPosPx != null -> Triple(
                moonPosPx.x.coerceIn(0f, width),
                0.45f * (lighting.moonIlluminationPercent.toFloat() / 100f).coerceIn(0f, 1f),
                Color(0xFFDCE8FF)
            )
            else -> null
        }
        if (glint != null && glint.second > 0.01f) {
            val (glintX, glintStrength, glintColor) = glint
            // Broad Fresnel sheen across the reflection column, under the sparkles.
            val sheenY = riverCenterY(glintX)
            drawSoftPatch(
                drawScope, glintX, sheenY, width * 0.52f, riverHalfW(glintX, sheenY) * 1.7f,
                glintColor, peakAlpha = 0.10f * glintStrength, layers = 10
            )
            drawSoftPatch(
                drawScope, glintX, sheenY, width * 0.30f, riverHalfW(glintX, sheenY) * 1.5f,
                glintColor, peakAlpha = 0.20f * glintStrength, layers = 10
            )
            for (i in 0 until 64) {
                val x = width * LandscapeProfile.hash01(137, i * 7 + 2)
                val gauss = exp(-(((x - glintX) / (width * 0.17f)).pow(2)))
                // Stochastic thinning: sparkles are a point process whose density follows
                // the column, not a uniform rain of dashes.
                if (LandscapeProfile.hash01(193, i * 11 + 8) > 0.25f + 0.75f * gauss) continue
                val yc = riverCenterY(x)
                val hw = riverHalfW(x, yc)
                val y = yc + (LandscapeProfile.hash01(79, i * 5 + 3) - 0.5f) * 1.5f * hw
                val alpha = (glintStrength * gauss *
                    (0.35f + 0.65f * LandscapeProfile.hash01(31, i * 3 + 1))).coerceIn(0f, 0.9f)
                if (alpha < 0.02f) continue
                val len = height * (0.006f + 0.023f * LandscapeProfile.hash01(57, i * 9 + 5) * (0.4f + gauss))
                drawScope.drawLine(
                    color = glintColor.copy(alpha = alpha),
                    start = Offset(x, y),
                    end = Offset(x + len, y),
                    strokeWidth = (height * 0.002f).coerceIn(1f, 3.5f),
                    cap = StrokeCap.Round
                )
                // Rare star sparkles at the column's heart.
                if (gauss > 0.78f && LandscapeProfile.hash01(297, i * 3 + 1) > 0.6f) {
                    val r = height * (0.002f + 0.002f * LandscapeProfile.hash01(41, i * 7 + 2))
                    drawScope.drawLine(
                        color = glintColor.copy(alpha = alpha * 0.5f),
                        start = Offset(x + len / 2f, y - r),
                        end = Offset(x + len / 2f, y + r),
                        strokeWidth = (height * 0.0014f).coerceIn(0.8f, 2.5f),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        // Broken reflections of the two foreground trees in the near water.
        for ((txFrac, thFrac) in listOf(0.115f to 0.068f, 0.875f to 0.058f)) {
            val tx = width * txFrac
            val th = height * thFrac
            val yc = riverCenterY(tx)
            val hw = riverHalfW(tx, yc)
            val bot = yc + hw
            for (j in 0 until 6) {
                val y = bot - (j + 0.5f) * (th * 0.55f / 6f)
                if (y < yc - hw) break
                val wdt = th * 1.1f * (0.55f - 0.06f * j)
                val xo = (LandscapeProfile.hash01(77, (txFrac * 1000f).toInt() + j * 7) - 0.5f) * height * 0.006f
                drawScope.drawLine(
                    color = canopyDark.copy(alpha = (0.30f * (1f - j / 6f) * (0.4f + 0.6f * lightLevel))),
                    start = Offset(tx + xo - wdt / 2f, y),
                    end = Offset(tx + xo + wdt / 2f, y),
                    strokeWidth = (height * 0.002f).coerceIn(1f, 3.5f),
                    cap = StrokeCap.Round
                )
            }
        }
        // Reed clumps (Phragmites) at the banks: grouped curved stems, feathery plumes.
        val plumeColor = lerp(Color(0xFF201A10), Color(0xFFB2966E), day)
        for (ci in 0 until 7) {
            val p = 0.08f + 0.84f * (ci + LandscapeProfile.hash01(93, ci * 3 + 1)) / 7f
            val x0 = -riverMargin + (width + 2f * riverMargin) * p
            val yc = riverCenterY(x0)
            val side = if (ci % 2 == 0) -1f else 1f
            val by = yc + side * riverHalfW(x0, yc) * 1.02f
            for (si in 0 until 8) {
                val x = x0 + (LandscapeProfile.hash01(31, ci * 31 + si * 7) - 0.5f) * width * 0.018f
                val bh = height * (0.014f + 0.012f * LandscapeProfile.hash01(17, ci * 17 + si * 3))
                val lean = (LandscapeProfile.hash01(13, ci * 13 + si * 5) - 0.5f) * bh * 0.35f
                val stem = Path()
                for ((k, t) in listOf(0f, 0.33f, 0.66f, 1f).withIndex()) {
                    val px = x + lean * t.pow(1.6f)
                    val py = by - bh * t
                    if (k == 0) stem.moveTo(px, py) else stem.lineTo(px, py)
                }
                drawScope.drawPath(
                    stem, riparian.copy(alpha = 0.45f + 0.25f * day),
                    style = Stroke(width = (height * 0.0014f).coerceIn(0.8f, 2.5f))
                )
                if (si % 3 == 0) {
                    oval(
                        x + lean, by - bh - height * 0.0015f,
                        height * 0.0028f, height * 0.006f,
                        plumeColor.copy(alpha = 0.35f + 0.30f * day)
                    )
                }
            }
        }

        // 11. Scrub and trees: wild pistachio/almond of the Zagros foothills — sparse,
        //     drought-grey, shadowed away from the Sun. Nothing grows in the monuments'
        //     worn clearings.
        val shadowColor = Color.Black.copy(alpha = 0.16f + 0.18f * day)
        val shadowLen = (1.6f - sunAlt.coerceIn(0f, 60f) / 45f).coerceIn(0.5f, 1.8f)
        // One space-colonization skeleton planted at (cx, by) with total height [hgt]:
        // pipe-model woody structure (thick-first, shadow pass offset anti-sun for
        // roundness on near trees), then foliage as *leaf-cluster dabs* at the branch
        // terminals — several small offset clusters per terminal with a dark core, body
        // tones and a sun-side rim, the 2D analogue of the leaf-cluster cards real-time
        // vegetation is built from. [silvery] swaps the palette to grey-green Artemisia
        // sage scrub. Small/distant instances drop the second bark pass and use fewer
        // dabs (LOD by height), which is what makes a 28-tree valley affordable.
        val silverLeaf = lerp(Color(0xFF0E120E), Color(0xFF8E9C84), lightLevel)
        val silverDark = silverLeaf.scaled(0.55f)
        val silverRim = lerp(silverLeaf, Color.White, 0.22f * lightLevel)
        fun drawSkeletonTree(
            cx: Float,
            by: Float,
            hgt: Float,
            skeleton: List<LandscapeProfile.BranchSegment>,
            flip: Boolean,
            foliage: Float,
            seed: Int,
            silvery: Boolean = false
        ) {
            val sx = if (flip) -1f else 1f
            val detail = hgt > height * 0.034f
            oval(cx - sunSign * hgt * 0.3f * shadowLen, by, hgt * (0.7f + 0.6f * shadowLen), hgt * 0.085f, shadowColor)
            val barkDark = lerp(Color(0xFF0A0806), Color(0xFF35271A), lightLevel)
            val barkLit = lerp(Color(0xFF14100C), Color(0xFF5A4430), lightLevel)
            for (seg in skeleton) {
                val wdt = ((seg.r0 + seg.r1) * hgt).coerceIn(0.8f, (hgt * 0.09f).coerceAtLeast(1f))
                val x0 = cx + sx * seg.x0 * hgt
                val y0 = by - seg.y0 * hgt
                val x1 = cx + sx * seg.x1 * hgt
                val y1 = by - seg.y1 * hgt
                if (detail) {
                    drawScope.drawLine(
                        barkDark,
                        Offset(x0 - sunSign * wdt * 0.22f, y0), Offset(x1 - sunSign * wdt * 0.22f, y1),
                        wdt, StrokeCap.Round
                    )
                }
                drawScope.drawLine(barkLit, Offset(x0, y0), Offset(x1, y1), wdt, StrokeCap.Round)
            }
            val backlit = sunAlt in -1f..9f && day > 0.05f
            val leafDark = if (silvery) silverDark else canopyDark
            val leafBody = if (silvery) silverLeaf else canopy
            val leafRimBase = if (silvery) silverRim else canopyLight
            val rimColor = if (backlit) lerp(leafRimBase, lighting.horizonTone, 0.45f) else leafRimBase
            val dabs = if (detail) 4 else 2
            var ci = 0
            for (seg in skeleton) {
                if (!seg.terminal && seg.depth < 7) continue
                ci++
                if (LandscapeProfile.hash01(seed * 71 + 13, ci * 3 + 1) > foliage) continue
                val px = cx + sx * seg.x1 * hgt
                val py = by - seg.y1 * hgt
                val cr = hgt * (0.045f + 0.040f * LandscapeProfile.hash01(seed * 37 + 7, ci * 5 + 2))
                val tone = 0.85f + 0.3f * LandscapeProfile.hash01(seed * 53 + 3, ci * 7 + 4)
                for (d in 0 until dabs) {
                    val ox = (LandscapeProfile.hash01(seed * 97 + 3, ci * 31 + d * 7) - 0.5f) * cr * 1.15f
                    val oy = (LandscapeProfile.hash01(seed * 89 + 5, ci * 37 + d * 11) - 0.5f) * cr * 0.75f
                    val r = cr * (0.42f + 0.30f * LandscapeProfile.hash01(seed * 83 + 9, ci * 41 + d * 13))
                    val col = when {
                        d == 0 -> leafDark.scaled(tone)                       // shadow core
                        d == dabs - 1 && detail -> rimColor.copy(alpha = 0.55f) // sun-side rim
                        else -> leafBody.scaled(tone * (0.9f + 0.2f * d / dabs))
                    }
                    val shiftX = when {
                        d == 0 -> -sunSign * cr * 0.20f
                        d == dabs - 1 -> sunSign * cr * 0.22f
                        else -> 0f
                    }
                    oval(px + ox + shiftX, py + oy + if (d == 0) cr * 0.14f else 0f, r * 2f, r * 1.25f, col)
                }
            }
        }
        fun bush(cx: Float, by: Float, s: Float, idx: Int) {
            // Two shrub species share the foothills: olive pistachio scrub and
            // silver-grey Artemisia sage (every third bush).
            drawSkeletonTree(
                cx, by, s * 2.1f, BUSH_SKELETONS[idx % 3], idx % 2 == 0, 0.62f,
                seed = idx * 3 + 1, silvery = idx % 3 == 1
            )
        }
        val persepolisApronL = framing.platformLeftXPx - width * 0.030f
        val persepolisApronR = framing.platformRightXPx + width * 0.105f
        val slopeApronL = framing.milSlopeLeftXPx - width * 0.065f
        fun inApron(x: Float, y: Float): Boolean =
            (x in persepolisApronL..persepolisApronR && y < toeY + height * 0.095f) ||
                (x > slopeApronL && y < framing.milSlopeFrontYPx + height * 0.095f)
        for (i in 0 until 16) {
            val x = width * LandscapeProfile.hash01(197, i * 11 + 3)
            if (i < 7) {
                val y = toeY - height * 0.004f + height * 0.010f * LandscapeProfile.hash01(39, i * 3 + 1)
                if (!inApron(x, y)) {
                    bush(x, y, height * (0.005f + 0.004f * LandscapeProfile.hash01(71, i * 5 + 2)), i)
                }
            } else {
                val y = height * (0.90f + 0.075f * LandscapeProfile.hash01(53, i * 7 + 4))
                val ycBush = riverCenterY(x)
                if (abs(y - ycBush) >= riverHalfW(x, ycBush) * 1.30f && !inApron(x, y)) {
                    bush(x, y, height * (0.008f + 0.010f * LandscapeProfile.hash01(71, i * 5 + 2)), i)
                }
            }
        }
        // Lone pistachio between the sites; two foreground trees on the near bank (bases
        // set from the widened river's actual edge, not a fixed offset).
        fun nearBankY(x: Float): Float {
            val yc = riverCenterY(x)
            return yc + riverHalfW(x, yc) * 1.35f + height * 0.006f
        }
        drawSkeletonTree(
            (framing.platformRightXPx + framing.milSlopeLeftXPx) / 2f, toeY + height * 0.020f,
            height * 0.040f, TREE_SKELETONS[2], flip = false, foliage = 0.9f, seed = 7
        )
        drawSkeletonTree(
            width * 0.115f, nearBankY(width * 0.115f),
            height * 0.068f, TREE_SKELETONS[0], flip = false, foliage = 0.95f, seed = 11
        )
        drawSkeletonTree(
            width * 0.875f, nearBankY(width * 0.875f),
            height * 0.058f, TREE_SKELETONS[1], flip = true, foliage = 0.95f, seed = 13
        )
        // 25 more trees (27 deterministic plantings; clearings and open water refuse a
        // couple, which reads as natural gaps): a riparian gallery on the far bank, an
        // open woodland along the canal and foothills, scattered plain pistachios and two
        // more near-bank trees — the Zagros oak/pistachio parkland this plain really has.
        fun plantTree(x: Float, y: Float, hgt: Float, idx: Int) {
            // Trees are refused only in the worn earth right at the stone's feet; the
            // aprons' faint forward tails are ordinary foreground field where the
            // riparian and canal trees belong.
            if (inApron(x, y) && y < toeY + height * 0.038f) return
            val ycHere = riverCenterY(x)
            if (abs(y - ycHere) < riverHalfW(x, ycHere) * 1.22f) return
            drawSkeletonTree(
                x, y, hgt, TREE_SKELETONS[idx % TREE_SKELETONS.size],
                flip = idx % 2 == 1,
                foliage = 0.82f + 0.15f * LandscapeProfile.hash01(701, idx * 3 + 1),
                seed = idx * 7 + 3
            )
        }
        for (i in 0 until 9) { // riparian gallery, far bank
            val x = width * (0.05f + 0.90f * (i + LandscapeProfile.hash01(601, i * 3 + 1)) / 9f)
            val yc = riverCenterY(x)
            val y = yc - riverHalfW(x, yc) * (1.45f + 0.30f * LandscapeProfile.hash01(603, i * 5 + 2))
            plantTree(x, y, height * (0.030f + 0.018f * LandscapeProfile.hash01(607, i * 7 + 3)), 100 + i)
        }
        for (i in 0 until 6) { // canal tree line, among the canopy dots
            val x = width * (0.08f + 0.84f * (i + LandscapeProfile.hash01(613, i * 3 + 1)) / 6f)
            val y = canalY - height * 0.004f + (x / width) * height * 0.010f
            plantTree(x, y, height * (0.020f + 0.012f * LandscapeProfile.hash01(617, i * 5 + 2)), 120 + i)
        }
        for (i in 0 until 6) { // foothill open woodland
            val x = width * LandscapeProfile.hash01(619, i * 11 + 5)
            val y = toeY + height * (0.006f + 0.024f * LandscapeProfile.hash01(631, i * 7 + 2))
            plantTree(x, y, height * (0.022f + 0.012f * LandscapeProfile.hash01(633, i * 3 + 1)), 140 + i)
        }
        for (i in 0 until 4) { // scattered plain pistachios
            val x = width * (0.10f + 0.80f * LandscapeProfile.hash01(641, i * 13 + 7))
            val y = height * (0.855f + 0.045f * LandscapeProfile.hash01(643, i * 5 + 3))
            plantTree(x, y, height * (0.026f + 0.014f * LandscapeProfile.hash01(647, i * 7 + 2)), 160 + i)
        }
        for ((j, xf) in listOf(0.34f, 0.63f).withIndex()) { // two more near-bank trees
            val x = width * (xf + (LandscapeProfile.hash01(653, j * 3 + 1) - 0.5f) * 0.03f)
            plantTree(x, nearBankY(x), height * (0.050f + 0.012f * LandscapeProfile.hash01(657, j * 5 + 2)), 180 + j)
        }

        // 12. Steppe grass v3 — the researched recipe from production grass systems
        //     (Ghost-of-Tsushima GDC, GodotGrass, Unity-Grass), translated to static 2D:
        //       * blades are tapered strips along a cubic-Bezier bend profile
        //         (LandscapeProfile.bladeBendX; stiffness separates wiry dry blades from
        //         lush ones);
        //       * true Voronoi clumps (LandscapeProfile.grassClump) share height, lean,
        //         dryness and density, softened toward neutral at borders via F2-F1;
        //       * tri-tone shading: ground-tinted AO root -> body -> tip, tips warmed by
        //         fake translucency at low Sun;
        //       * three distance bands (colour-field carpet + micro-blades, stroked
        //         tufts, hero filled blades with centre ribs) with ALL geometry batched
        //         into colour-bucket Paths — thousands of blades for ~10 draw calls;
        //       * real Zagros steppe species at hero range: Stipa feather grass
        //         (fountain tufts, tall culms, fans of silky awns), Bromus (nodding
        //         purplish panicles), broad green riparian tufts, litter and forbs.
        //     Moisture (noise + river + canal) drives density and green-vs-straw;
        //     nothing grows in the water or on the monuments' worn clearings.
        val backlight = if (sunAlt in -2f..14f && day > 0.04f) 0.40f else 0.10f
        val gRootDry = lerp(lerp(Color(0xFF171307), Color(0xFF4A3A1E), day), steppeTop, 0.30f)
        val gTipDry = lerp(lerp(Color(0xFF2A2416), Color(0xFFC4A468), day), lighting.horizonTone, backlight * 0.35f)
        val gRootGreen = lerp(lerp(Color(0xFF0A1206), Color(0xFF26341A), day), steppeTop, 0.30f)
        val gTipGreen = lerp(lerp(Color(0xFF16200F), Color(0xFF7E9455), day), lighting.horizonTone, backlight * 0.30f)
        val awnCol = lerp(Color(0xFF241E12), Color(0xFFD8C096), day)
        val panicleCol = lerp(Color(0xFF1C1410), Color(0xFFA8845E), day)
        val forbCol = lerp(Color(0xFF201A10), Color(0xFFD9C98A), day)
        val dryRootP = Path(); val dryTipP = Path(); val greenRootP = Path(); val greenTipP = Path()
        val awnP = Path(); val panicleP = Path(); val litterP = Path()
        val fillDryP = Path(); val fillGreenP = Path(); val forbP = Path()
        val densityScale = ((width * height) / (1080f * 1260f)).coerceIn(0.6f, 2.2f)

        fun canalYAt(x: Float): Float = canalY - height * 0.004f + (x / width) * height * 0.010f
        fun grassAllowed(x: Float, y: Float): Boolean {
            if (inApron(x, y)) return false
            val yc = riverCenterY(x)
            return abs(y - yc) >= riverHalfW(x, yc) * 1.26f
        }
        fun moistureAt(x: Float, y: Float, depth: Float): Float {
            var m = 0.5f + 0.5f * LandscapeProfile.fbm(x / width * 4.2f + depth * 1.7f, 91, octaves = 3)
            val yc = riverCenterY(x)
            m += (1f - abs(y - yc) / (riverHalfW(x, yc) * 2.6f + height * 0.008f)).coerceIn(0f, 1f) * 0.50f
            m += (1f - abs(y - canalYAt(x)) / (height * 0.018f)).coerceIn(0f, 1f) * 0.35f
            return m.coerceIn(0f, 1f)
        }
        fun bladePt(bx: Float, by: Float, bh: Float, lean: Float, stiff: Float, t: Float): Offset =
            Offset(
                bx + lean * bh * LandscapeProfile.bladeBendX(t, stiff),
                by - bh * t * (1f - 0.10f * t * abs(lean)) // arched blades lose a little height
            )
        // One stroked blade: root half into the dark bucket, tip half into the light one.
        fun strokeBlade(bx: Float, by: Float, bh: Float, lean: Float, stiff: Float, dark: Path, light: Path) {
            var p = bladePt(bx, by, bh, lean, stiff, 0f)
            dark.moveTo(p.x, p.y)
            p = bladePt(bx, by, bh, lean, stiff, 0.28f); dark.lineTo(p.x, p.y)
            p = bladePt(bx, by, bh, lean, stiff, 0.52f); dark.lineTo(p.x, p.y)
            light.moveTo(p.x, p.y)
            p = bladePt(bx, by, bh, lean, stiff, 0.76f); light.lineTo(p.x, p.y)
            p = bladePt(bx, by, bh, lean, stiff, 1f); light.lineTo(p.x, p.y)
        }
        // (a) Carpet: the ground-cover colour field real fields have between tufts — this
        //     is what makes the far plain read as *covered*, not sprinkled.
        val carpetDry = lerp(gTipDry, steppeTop, 0.55f)
        val carpetGreen = lerp(gTipGreen, steppeTop, 0.55f)
        for (i in 0 until 70) {
            val x = width * LandscapeProfile.hash01(811, i * 13 + 3)
            val depth = LandscapeProfile.hash01(809, i * 17 + 5).pow(1.25f)
            val y = groundTopY + (height - groundTopY) * depth
            if (!grassAllowed(x, y)) continue
            val moist = moistureAt(x, y, depth)
            drawSoftPatch(
                drawScope, x, y,
                width * (0.10f + 0.10f * LandscapeProfile.hash01(813, i * 7 + 1)),
                height * (0.010f + 0.016f * depth),
                if (moist > 0.52f) carpetGreen else carpetDry,
                peakAlpha = 0.05f + 0.10f * day, layers = 6
            )
        }
        // (b) Far micro-blades: pure texture at range.
        val farCount = (1300 * densityScale).toInt()
        for (i in 0 until farCount) {
            val x = width * LandscapeProfile.hash01(821, i * 13 + 7)
            val depth = 0.46f * LandscapeProfile.hash01(823, i * 17 + 3).pow(1.15f)
            val y = groundTopY + (height - groundTopY) * depth
            if (!grassAllowed(x, y)) continue
            val moist = moistureAt(x, y, depth)
            val clump = LandscapeProfile.grassClump(x / width, depth, 517)
            if (LandscapeProfile.hash01(827, i * 19 + 5) >
                (0.18f + 0.82f * moist) * (0.30f + 0.70f * clump.density01)) continue
            val soft = 0.45f + 0.55f * clump.edge01
            val bh = height * (0.0035f + 0.010f * depth) * (1f + (clump.heightMul - 1f) * soft) *
                (0.75f + 0.5f * LandscapeProfile.hash01(829, i * 23 + 7))
            val lean = clump.lean * soft + (LandscapeProfile.hash01(831, i * 29 + 9) - 0.5f) * 0.5f
            val dryMix = 0.5f * (1f - moist) + 0.5f * clump.dryness01
            val stiff = 0.25f + 0.45f * (1f - dryMix)
            if (dryMix > 0.5f) strokeBlade(x, y, bh, lean, stiff, dryRootP, dryTipP)
            else strokeBlade(x, y, bh, lean, stiff, greenRootP, greenTipP)
        }
        // (c) Mid tufts: stroked blade fans (the river rejects most attempts mid-band;
        //     survivors dress the far strip and the banks).
        val midTufts = (520 * densityScale).toInt()
        for (i in 0 until midTufts) {
            val x = width * LandscapeProfile.hash01(839, i * 13 + 1)
            val depth = 0.30f + 0.62f * LandscapeProfile.hash01(843, i * 17 + 3)
            val y = groundTopY + (height - groundTopY) * depth
            if (!grassAllowed(x, y)) continue
            val moist = moistureAt(x, y, depth)
            val clump = LandscapeProfile.grassClump(x / width, depth, 517)
            if (LandscapeProfile.hash01(853, i * 19 + 5) >
                (0.22f + 0.78f * moist) * (0.35f + 0.65f * clump.density01)) continue
            val soft = 0.45f + 0.55f * clump.edge01
            val hm = 1f + (clump.heightMul - 1f) * soft
            val dryMix = 0.5f * (1f - moist) + 0.5f * clump.dryness01
            val stiff = 0.25f + 0.45f * (1f - dryMix)
            val darkB = if (dryMix > 0.5f) dryRootP else greenRootP
            val lightB = if (dryMix > 0.5f) dryTipP else greenTipP
            val blades = 5 + (5 * LandscapeProfile.hash01(857, i * 11 + 5)).toInt()
            for (b in 0 until blades) {
                val bx = x + (LandscapeProfile.hash01(859, i * 101 + b * 37) - 0.5f) * width * 0.011f
                val bh = height * (0.005f + 0.013f * depth) * hm *
                    (0.7f + 0.6f * LandscapeProfile.hash01(863, i * 57 + b * 19))
                val lean = clump.lean * soft + (LandscapeProfile.hash01(867, i * 67 + b * 23) - 0.5f) * 0.55f
                strokeBlade(bx, y, bh, lean, stiff, darkB, lightB)
            }
        }
        // (d) Hero tufts on the near strip: filled tapered blades with a centre rib
        //     (the rounded-normal trick), an AO contact patch, and species seed heads.
        val heroTufts = (130 * densityScale).toInt()
        for (i in 0 until heroTufts) {
            val x = width * LandscapeProfile.hash01(877, i * 13 + 3)
            val depth = 0.84f + 0.16f * LandscapeProfile.hash01(881, i * 17 + 5)
            val y = groundTopY + (height - groundTopY) * depth
            if (!grassAllowed(x, y)) continue
            val moist = moistureAt(x, y, depth)
            val clump = LandscapeProfile.grassClump(x / width, depth, 517)
            if (LandscapeProfile.hash01(883, i * 19 + 7) >
                (0.30f + 0.70f * moist) * (0.40f + 0.60f * clump.density01)) continue
            val soft = 0.45f + 0.55f * clump.edge01
            val hm = 1f + (clump.heightMul - 1f) * soft
            val dryMix = 0.5f * (1f - moist) + 0.5f * clump.dryness01
            val stiff = 0.22f + 0.50f * (1f - dryMix)
            val fillB = if (dryMix > 0.5f) fillDryP else fillGreenP
            val lightB = if (dryMix > 0.5f) dryTipP else greenTipP
            drawSoftPatch(
                drawScope, x, y, height * 0.034f, height * 0.008f, Color(0xFF0A0C06),
                peakAlpha = 0.10f + 0.10f * day, layers = 5
            )
            val pick = LandscapeProfile.hash01(887, i * 3 + 1)
            val species = when {
                moist > 0.62f -> 2              // riparian: broad, upright, plain
                dryMix > 0.58f && pick < 0.55f -> 0  // Stipa feather grass
                pick < 0.82f -> 1               // Bromus
                else -> 2
            }
            val blades = 7 + (5 * LandscapeProfile.hash01(889, i * 11 + 5)).toInt()
            for (b in 0 until blades) {
                val bx = x + (LandscapeProfile.hash01(893, i * 101 + b * 37) - 0.5f) * width * 0.012f
                val bh = height * (0.012f + 0.016f * depth) * hm *
                    (0.72f + 0.55f * LandscapeProfile.hash01(899, i * 57 + b * 19)) *
                    if (species == 0 && b < 2) 1.3f else 1f // Stipa culms rise above the fountain
                val lean = clump.lean * soft + (LandscapeProfile.hash01(901, i * 67 + b * 23) - 0.5f) * 0.6f +
                    if (species == 0) (b - blades / 2f) * 0.07f else 0f // Stipa fans outward
                val wdt = maxOf(1.3f, bh * if (species == 2) 0.11f else 0.08f)
                val ts = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
                val tip = bladePt(bx, y, bh, lean, stiff, 1f)
                for ((k, t) in ts.withIndex()) {
                    val p = bladePt(bx, y, bh, lean, stiff, t)
                    val px = p.x - wdt * (1f - t) / 2f
                    if (k == 0) fillB.moveTo(px, p.y) else fillB.lineTo(px, p.y)
                }
                for (k in 5 downTo 0) {
                    val t = ts[k]
                    val p = bladePt(bx, y, bh, lean, stiff, t)
                    fillB.lineTo(p.x + wdt * (1f - t) / 2f, p.y)
                }
                fillB.close()
                // Centre rib highlight (rounded-normal fake).
                var rp = bladePt(bx, y, bh, lean, stiff, 0.15f)
                lightB.moveTo(rp.x, rp.y)
                rp = bladePt(bx, y, bh, lean, stiff, 0.55f); lightB.lineTo(rp.x, rp.y)
                lightB.lineTo(tip.x, tip.y)
                if (b < 2 && species == 0) { // Stipa: a fan of long silky awns
                    for (a in 0 until 5) {
                        val spread = (a - 2) * 0.13f + (LandscapeProfile.hash01(907, i * 31 + a * 7) - 0.5f) * 0.05f
                        val len = bh * (0.42f + 0.16f * LandscapeProfile.hash01(911, i * 37 + a * 11))
                        awnP.moveTo(tip.x, tip.y)
                        awnP.lineTo(tip.x + spread * len * 0.5f + lean * len * 0.2f, tip.y - len * 0.55f)
                        awnP.lineTo(tip.x + spread * len + lean * len * 0.35f, tip.y - len)
                    }
                } else if (b < 2 && species == 1) { // Bromus: nodding branched panicle
                    for (a in 0 until 4) {
                        val dx = (a - 1.5f) * bh * 0.055f
                        val top = tip.y - bh * (0.05f + 0.04f * (3 - a))
                        panicleP.moveTo(tip.x, tip.y)
                        panicleP.lineTo(tip.x + dx, top)
                        panicleP.lineTo(tip.x + dx * 1.4f, top + bh * 0.07f) // spikelet nods back down
                    }
                }
            }
            if (moist > 0.55f && LandscapeProfile.hash01(917, i * 41 + 3) < 0.30f) { // sparse forbs
                for (f in 0 until 3) {
                    val fx = x + (LandscapeProfile.hash01(919, i * 43 + f * 7) - 0.5f) * width * 0.014f
                    val fy = y - height * (0.008f + 0.010f * LandscapeProfile.hash01(923, i * 47 + f * 11))
                    val r = height * 0.0016f
                    forbP.addOval(androidx.compose.ui.geometry.Rect(fx - r, fy - r, fx + r, fy + r))
                }
            }
        }
        // (e) Dry litter flecks — last season's blades lying flat in the dry patches.
        for (i in 0 until (240 * densityScale).toInt()) {
            val x = width * LandscapeProfile.hash01(929, i * 13 + 3)
            val depth = 0.3f + 0.7f * LandscapeProfile.hash01(931, i * 17 + 5)
            val y = groundTopY + (height - groundTopY) * depth
            if (!grassAllowed(x, y)) continue
            val clump = LandscapeProfile.grassClump(x / width, depth, 517)
            if (clump.dryness01 < 0.45f) continue
            val len = height * (0.004f + 0.008f * depth * LandscapeProfile.hash01(933, i * 23 + 7))
            litterP.moveTo(x, y)
            litterP.lineTo(x + len, y - len * 0.08f)
        }
        // Flush the buckets: fills first, then strokes dark -> light, then the bright bits.
        val strokeW = (height * 0.0013f).coerceIn(0.9f, 2.0f)
        drawScope.drawPath(fillDryP, lerp(gRootDry, gTipDry, 0.45f).copy(alpha = 0.55f + 0.35f * day))
        drawScope.drawPath(fillGreenP, lerp(gRootGreen, gTipGreen, 0.45f).copy(alpha = 0.55f + 0.35f * day))
        drawScope.drawPath(dryRootP, gRootDry.copy(alpha = 0.45f + 0.40f * day), style = Stroke(width = strokeW, cap = StrokeCap.Round))
        drawScope.drawPath(greenRootP, gRootGreen.copy(alpha = 0.45f + 0.40f * day), style = Stroke(width = strokeW, cap = StrokeCap.Round))
        drawScope.drawPath(dryTipP, gTipDry.copy(alpha = 0.40f + 0.40f * day), style = Stroke(width = strokeW, cap = StrokeCap.Round))
        drawScope.drawPath(greenTipP, gTipGreen.copy(alpha = 0.40f + 0.40f * day), style = Stroke(width = strokeW, cap = StrokeCap.Round))
        drawScope.drawPath(litterP, gTipDry.copy(alpha = 0.25f + 0.25f * day), style = Stroke(width = strokeW, cap = StrokeCap.Round))
        drawScope.drawPath(panicleP, panicleCol.copy(alpha = 0.45f + 0.35f * day), style = Stroke(width = strokeW, cap = StrokeCap.Round))
        drawScope.drawPath(awnP, awnCol.copy(alpha = 0.35f + 0.35f * day), style = Stroke(width = 0.8f, cap = StrokeCap.Round))
        drawScope.drawPath(forbP, forbCol.copy(alpha = 0.40f + 0.35f * day))

        // 13. Foreground corner shade: the suggestion of near scrub at the viewer's feet,
        //     closing the composition's bottom corners.
        drawSoftPatch(drawScope, width * 0.02f, height, width * 0.5f, height * 0.07f, Color(0xFF080A06), 0.08f + 0.06f * day)
        drawSoftPatch(drawScope, width * 0.98f, height, width * 0.5f, height * 0.07f, Color(0xFF080A06), 0.08f + 0.06f * day)
    }

    /**
     * The earth the distant GL ruins stand in, painted only around their own footprints.
     *
     * At the half-scale placement Persepolis is in the left distance and Mil-e Azhdaha is a
     * separate far-right site whose slope runs off the edge, so one continuous grounding band
     * would invent a sand bridge across the plain between them. Instead this paints two small
     * worn clearings and leaves the intervening field ordinary: irregular earth in front of the
     * Persepolis toe, and limestone dust/talus around the tower slope's visible downslope and
     * front step. Broken contact shadows, low drift hummocks and fine debris are likewise local
     * to each footprint. The two contact treatments remain separate because the slope's front
     * step is closer to the viewer and projects below the Persepolis toe. Every edge is either
     * irregular, gradient-feathered, or both.
     *
     * It is still drawn under the GL surface, so within the stone footprints only the parts in
     * front of their edges show; outside them, the patch is the dusty clearing the ruins occupy.
     * Colours are mixed from the active ground palette toward sand and scaled by the same
     * Sun-altitude brightness as the rest of the ground, so the patch warms, cools and disappears
     * into astronomical night with the terrain around it. All placement noise is deterministic.
     */
    private fun drawCombinedLandscapeGrounding(
        drawScope: DrawScope,
        composition: SceneComposition,
        palette: ScenePalette,
        lighting: LightingState,
        framing: PersepolisFraming.Result
    ) {
        val width = composition.widthPx
        val height = composition.heightPx
        val toeY = framing.toeYPx
        val day = lighting.ambientBrightness.coerceIn(0f, 1f)

        val platformLeft = framing.platformLeftXPx
        val platformRight = framing.platformRightXPx
        val slopeLeft = framing.milSlopeLeftXPx
        val slopeRight = framing.milSlopeRightXPx
        val slopeFrontY = framing.milSlopeFrontYPx.coerceAtMost(height)
        val platformWidth = (platformRight - platformLeft).coerceAtLeast(1f)
        val slopeWidth = (slopeRight - slopeLeft).coerceAtLeast(1f)
        // Two local clearings, not one archaeological causeway: Persepolis' earth tails into
        // the field shortly past its cut-back platform, while the slope's dust begins around
        // its visible downslope feather and is allowed to continue off the right edge with it.
        val persepolisApronLeft = (platformLeft - width * 0.030f).coerceAtLeast(-width * 0.02f)
        val persepolisApronRight = platformRight + width * 0.105f
        val slopeApronLeft = slopeLeft - width * 0.065f
        val slopeApronRight = width * 1.03f

        val sand = lerp(Color(0xFF292216), Color(0xFFBEA06E), day)
        val limestoneDust = lerp(Color(0xFF2B2118), Color(0xFFC7A06B), day)
        val earth = lerp(palette.groundTop, sand, 0.42f)
        val darkEarth = lerp(palette.groundBottom, sand, 0.18f)

        // Soft elliptical falloff built from many equal-alpha shells. Solving the shell alpha
        // from the desired centre opacity makes the source-over accumulation reach that peak at
        // the middle while changing by only a percent or two per shell — low enough that the
        // shells disappear instead of reading as contour rings.
        fun drawSoftDrift(
            cx: Float,
            cy: Float,
            driftWidth: Float,
            driftHeight: Float,
            outerColor: Color,
            innerColor: Color,
            peakAlpha: Float,
            layers: Int
        ) {
            val safeLayers = layers.coerceAtLeast(2)
            val shellAlpha = 1f - (1f - peakAlpha.coerceIn(0f, 0.95f)).pow(1f / safeLayers)
            for (layer in 0 until safeLayers) {
                val t = layer / (safeLayers - 1).toFloat()
                val scale = 1f - 0.92f * t
                val w = driftWidth * scale
                val h = driftHeight * scale
                drawScope.drawOval(
                    color = lerp(outerColor, innerColor, t).copy(alpha = shellAlpha),
                    topLeft = Offset(cx - w / 2f, cy - h / 2f),
                    size = androidx.compose.ui.geometry.Size(w, h)
                )
            }
        }

        // 1. Two irregular worn-earth aprons: smoothly undulating edges, strongest at each
        //    site's own contact row and dissolving downward into the field. Both ends taper and
        //    fade horizontally, so an on-screen patch edge never becomes a vertical cut line.
        fun drawApron(left: Float, right: Float, baseY: Float, peakScale: Float, phase: Float) {
            if (right <= left) return
            val apronTop = baseY - height * 0.032f
            val apronBottom = (baseY + height * 0.095f).coerceAtMost(height)
            fun smooth01(t: Float): Float {
                val x = t.coerceIn(0f, 1f)
                return x * x * (3f - 2f * x)
            }
            fun endTaper(p: Float): Float = maxOf(
                smooth01((0.16f - p) / 0.16f),
                smooth01((p - 0.76f) / 0.24f)
            )
            val apron = Path().apply {
                val segments = 36
                moveTo(left, baseY + height * 0.012f)
                for (i in 1..segments) {
                    val p = i / segments.toFloat()
                    val x = left + (right - left) * p
                    val taper = endTaper(p)
                    val topDepth = 0.020f +
                        0.004f * sin(p * PI.toFloat() * 4f + phase) +
                        0.002f * sin(p * PI.toFloat() * 9f + phase * 2.7f)
                    val y = baseY - height * topDepth * (1f - taper) + height * 0.012f * taper
                    lineTo(x, y)
                }
                for (i in segments downTo 0) {
                    val p = i / segments.toFloat()
                    val x = left + (right - left) * p
                    val taper = endTaper(p)
                    val bottomDepth = 0.064f +
                        0.010f * sin(p * PI.toFloat() * 4f + phase + 0.6f) +
                        0.005f * sin(p * PI.toFloat() * 7f + phase * 0.6f)
                    val depth = bottomDepth * (1f - taper) + 0.020f * taper
                    lineTo(x, (baseY + height * depth).coerceAtMost(height))
                }
                close()
            }
            drawScope.drawPath(
                path = apron,
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.34f to earth.copy(alpha = (0.09f + 0.18f * day) * peakScale),
                    0.47f to sand.copy(alpha = (0.11f + 0.21f * day) * peakScale),
                    0.66f to Color.Transparent,
                    1.0f to Color.Transparent,
                    startY = apronTop,
                    endY = apronBottom
                )
            )
            // A faint second pass feathered at both ends melts each clearing into the field.
            drawScope.drawPath(
                path = apron,
                brush = Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    0.18f to sand.copy(alpha = (0.025f + 0.035f * day) * peakScale),
                    0.78f to sand.copy(alpha = (0.018f + 0.028f * day) * peakScale),
                    1.0f to Color.Transparent,
                    startX = left,
                    endX = right
                )
            )
        }
        drawApron(persepolisApronLeft, persepolisApronRight, toeY, peakScale = 1f, phase = 0.7f)
        drawApron(slopeApronLeft, slopeApronRight, slopeFrontY, peakScale = 0.78f, phase = 2.3f)

        // 2. Talus banked around the slope's visible downslope feather. The upslope itself now
        //    exits the right edge, so the useful host-side work is at the left/front transition
        //    where the lowest limestone sheets die into the ordinary field.
        drawSoftDrift(
            cx = slopeLeft + slopeWidth * 0.20f,
            cy = (slopeFrontY + height * 0.020f).coerceAtMost(height),
            driftWidth = slopeWidth * 0.66f,
            driftHeight = height * 0.058f,
            outerColor = earth,
            innerColor = limestoneDust,
            peakAlpha = 0.15f + 0.15f * day,
            layers = 16
        )

        // A paler wash of limestone dust immediately in front of the stepped sheets. It sits
        // below the GL slope's lowest front edge, so the sheets appear to shed their own pale
        // grit onto the darker field rather than terminating on the generic apron colour.
        drawSoftDrift(
            cx = (slopeLeft + slopeRight) / 2f,
            cy = (slopeFrontY + height * 0.016f).coerceAtMost(height),
            driftWidth = slopeWidth * 1.14f,
            driftHeight = height * 0.038f,
            outerColor = earth,
            innerColor = limestoneDust,
            peakAlpha = 0.10f + 0.13f * day,
            layers = 14
        )

        // 3. Broken contact shadow directly in front of the Persepolis forecourt only. The
        //    far-right slope gets its own softer crescent below; the plain between the sites
        //    must not inherit a continuous shadow from either one.
        val shadowLeft = platformLeft.coerceAtLeast(-width * 0.01f)
        val shadowRight = (platformRight + width * 0.018f).coerceAtMost(width)
        val shadow = Path().apply {
            val segments = 32
            moveTo(shadowLeft, toeY - height * 0.003f)
            lineTo(shadowRight, toeY - height * 0.002f)
            for (i in segments downTo 0) {
                val p = i / segments.toFloat()
                val x = shadowLeft + (shadowRight - shadowLeft) * p
                val depth = 0.018f +
                    0.005f * sin(p * PI.toFloat() * 6f + 0.8f) +
                    0.002f * sin(p * PI.toFloat() * 11f + 2.2f)
                lineTo(x, (toeY + height * depth).coerceAtMost(height))
            }
            close()
        }
        drawScope.drawPath(
            path = shadow,
            brush = Brush.verticalGradient(
                0.0f to Color.Black.copy(alpha = 0.12f + 0.20f * day),
                1.0f to Color.Transparent,
                startY = toeY - height * 0.004f,
                endY = (toeY + height * 0.027f).coerceAtMost(height)
            )
        )

        // 3b. A deeper, very soft contact crescent under the slope's front step. The GL sheets
        //     cover its upper half; the visible lower half anchors the nearer limestone edge
        //     without drawing a second hard horizontal shadow line.
        drawSoftDrift(
            cx = (slopeLeft + slopeRight) / 2f,
            cy = (slopeFrontY + height * 0.004f).coerceAtMost(height),
            driftWidth = slopeWidth * 1.06f,
            driftHeight = height * 0.024f,
            outerColor = darkEarth,
            innerColor = Color.Black,
            peakAlpha = 0.13f + 0.17f * day,
            layers = 14
        )

        // 4. Low drift hummocks and fine debris, kept inside the two clearings. They are
        //    deterministic, denser at each contact row and dissipate toward the viewer; none
        //    is scattered across the untouched plain between the monuments.
        fun drawHummocks(
            count: Int,
            left: Float,
            right: Float,
            baseY: Float,
            siteWidth: Float,
            lightColor: Color,
            seedOffset: Int
        ) {
            for (i in 0 until count) {
                val r1 = pseudoRandom(i * 17_171 + seedOffset)
                val r2 = pseudoRandom(i * 23_777 + seedOffset + 57)
                val r3 = pseudoRandom(i * 31_337 + seedOffset + 5)
                drawSoftDrift(
                    cx = left + (right - left) * (0.08f + 0.84f * ((i + r1 * 0.8f) / count)),
                    cy = baseY + height * (0.012f + r2 * 0.055f),
                    driftWidth = siteWidth * (0.13f + r2 * 0.16f),
                    driftHeight = height * (0.010f + r3 * 0.018f),
                    outerColor = earth,
                    innerColor = lightColor,
                    peakAlpha = (0.05f + 0.07f * day) * (0.6f + 0.4f * r3),
                    layers = 10
                )
            }
        }

        fun drawDebris(
            count: Int,
            left: Float,
            right: Float,
            baseY: Float,
            lightFleck: Color,
            seedOffset: Int
        ) {
            for (i in 0 until count) {
                val r1 = pseudoRandom(i * 41_003 + seedOffset)
                val r2 = pseudoRandom(i * 13_981 + seedOffset + 37)
                val r3 = pseudoRandom(i * 29_297 + seedOffset + 83)
                val x = left + (right - left) * ((i + r1 * 0.9f) / count)
                val y = (baseY - height * 0.004f + height * 0.082f * r2 * r2).coerceAtMost(height)
                val len = (2f + r3 * 7f) * (height / 900f).coerceIn(0.7f, 1.8f)
                val fleck = if (r3 > 0.45f) lightFleck else darkEarth
                drawScope.drawLine(
                    color = fleck.copy(alpha = (0.10f + 0.16f * day) * (0.5f + 0.5f * r1)),
                    start = Offset(x, y),
                    end = Offset(x + len, y + (r1 - 0.5f) * len * 0.35f),
                    strokeWidth = 1.1f + r3 * 1.5f,
                    cap = StrokeCap.Round
                )
            }
        }

        drawHummocks(
            count = 5,
            left = persepolisApronLeft,
            right = persepolisApronRight,
            baseY = toeY,
            siteWidth = platformWidth,
            lightColor = sand,
            seedOffset = 101
        )
        drawHummocks(
            count = 3,
            left = slopeApronLeft,
            right = slopeApronRight,
            baseY = slopeFrontY,
            siteWidth = slopeWidth,
            lightColor = limestoneDust,
            seedOffset = 40_501
        )
        drawDebris(
            count = 30,
            left = persepolisApronLeft,
            right = persepolisApronRight,
            baseY = toeY,
            lightFleck = sand,
            seedOffset = 211
        )
        drawDebris(
            count = 18,
            left = slopeApronLeft,
            right = slopeApronRight,
            baseY = slopeFrontY,
            lightFleck = limestoneDust,
            seedOffset = 61_927
        )
    }

    /**
     * A soft ridgeline: two summed sines at different frequencies, sampled and smoothed with quadratics so
     * it never shows the polyline.
     */
    private fun ridgePath(
        width: Float,
        baselineY: Float,
        amplitude: Float,
        phase: Float,
        waves: Float,
        detail: Float,
        points: Int = 36
    ): Path = Path().apply {
        val step = width / points
        var prevX = 0f
        var prevY = baselineY - heightFor(0f, waves, detail, phase, amplitude)
        moveTo(0f, prevY)
        for (i in 1..points) {
            val x = step * i
            val y = baselineY - heightFor(i / points.toFloat(), waves, detail, phase, amplitude)
            val midX = (prevX + x) / 2f
            val midY = (prevY + y) / 2f
            quadraticTo(prevX, prevY, midX, midY)
            prevX = x
            prevY = y
        }
        lineTo(width, prevY)
        lineTo(width, baselineY + amplitude * 2f)
        lineTo(0f, baselineY + amplitude * 2f)
        close()
    }

    /** Ridge height at [progress] in 0..1, always non-negative. */
    private fun heightFor(progress: Float, waves: Float, detail: Float, phase: Float, amplitude: Float): Float {
        val a = sin(progress * PI.toFloat() * 2f * waves + phase)
        val b = sin(progress * PI.toFloat() * 2f * detail + phase * 1.7f)
        return ((a * 0.65f + b * 0.35f) * 0.5f + 0.5f) * amplitude
    }

    /**
     * Grass tufts along the top of the ground band. Positions are deterministic, so the line does not
     * shimmer when the scene redraws on the next clock tick.
     */
    private fun drawGrass(
        drawScope: DrawScope,
        composition: SceneComposition,
        color: Color,
        brightness: Float
    ) {
        val width = composition.widthPx
        val height = composition.heightPx
        val tufts = 96
        val baseAlpha = 0.30f + 0.30f * brightness

        for (i in 0 until tufts) {
            val r1 = pseudoRandom(i * 2_654_435_761.toInt())
            val r2 = pseudoRandom(i * 40_503 + 17)
            val r3 = pseudoRandom(i * 12_289 + 91)
            val x = width * ((i + r1 * 0.9f) / tufts)
            // Tufts run from the grass line down into the first few percent of the ground, thinning as
            // they go, so the strip under the ridge is not a flat band of colour.
            val depth = r2 * height * 0.11f
            val y = composition.groundTopY + depth
            val bladeHeight = height * (0.008f + r3 * 0.014f) * (1f - depth / (height * 0.13f)).coerceIn(0.4f, 1f)
            val lean = (r1 - 0.5f) * bladeHeight * 0.45f
            val alpha = baseAlpha * (0.55f + 0.45f * r2) * (1f - depth / (height * 0.22f)).coerceIn(0.30f, 1f)

            drawScope.drawLine(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                start = Offset(x, y),
                end = Offset(x + lean, y - bladeHeight),
                strokeWidth = 1.4f + r3 * 1.1f,
                cap = StrokeCap.Round
            )
            // A second, shorter blade on most tufts gives the line some body.
            if (r3 > 0.35f) {
                drawScope.drawLine(
                    color = color.copy(alpha = (alpha * 0.75f).coerceIn(0f, 1f)),
                    start = Offset(x + 1.6f, y),
                    end = Offset(x + lean * 1.8f - 1.2f, y - bladeHeight * 0.68f),
                    strokeWidth = 1.1f,
                    cap = StrokeCap.Round
                )
            }
        }
    }

    /**
     * Small deterministic 0..1 value.
     *
     * The mask clears the sign bit, so the value is in `0 .. 2^31-1`; dividing by `2^31` is what puts it
     * in `0 .. 1`. The divisor was `2^24-1` once, which mapped the same value to `0 .. 128` — the grass
     * line was then laid out over 128 screen widths, and 95 of the 96 tufts were drawn outside the
     * canvas (only one of them was ever visible). Any change here changes the silhouette of the ground,
     * so it is worth re-reading this comment before "tidying" the constant.
     */
    private fun pseudoRandom(seed: Int): Float {
        var x = seed * 1_103_515_245 + 12_345
        x = x xor (x ushr 13)
        x *= 0x5bd1e995
        x = x xor (x ushr 15)
        return (x and 0x7FFFFFFF) / 2_147_483_648f
    }
}
