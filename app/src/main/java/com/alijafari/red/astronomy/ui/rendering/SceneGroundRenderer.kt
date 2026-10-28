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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The ground half of the full-bleed Home scene.
 *
 * Two art paths share this entry point:
 *
 *  - **Stylised themes** keep the original simple band — two sine ridges, a mist shelf, solid
 *    ground and a grass line — because flat illustration is those themes' whole point.
 *  - **Photographic themes with the Persepolis + Mil-e Azhdaha relief** get the full
 *    Marvdasht landscape ([drawMarvdashtLandscape]): a haze-layered far Mamasani fold
 *    range and the Dimeh-Mil / Mil-e Azhdaha limestone massif west of Nurabad built
 *    from geology-shaped profiles (soft bedrock
 *    benching, a carved water-gap, strata dipping off the fold hinge, pale caprock, talus
 *    cones and cliff streaks) with sun-facing facet shading, piedmont foothills and
 *    alluvial fans, a semi-arid steppe/cultivation plain with an irrigation canal, the
 *    monuments' own earthworks, a broad foreground Pulvar river (wet banks, shallow
 *    bed, deep thalweg, point bars, sheen/stochastic glitter tracking the real Sun or
 *    Moon, tree reflections, reed clumps), a space-colonization parkland of pistachio,
 *    Zagros oak, flowering cherry and tall Paulownia with Artemisia sage scrub, and a
 *    uniform grass *field* — one textured green mass rendered as a few bucketed fills,
 *    never individual blades — lush and cool-toned, richest along the river banks.
 *    The monuments' backdrop is the Nurabad (Mamasani) country the Mil-e Azhdaha tower
 *    actually stands in: the folded Zagros whalebacks, the rocky Dimeh-Mil ridge and
 *    the wide gorge (Tang-e Farakh / Tang-e Kalleh) cut through it — art-directed to
 *    the scene's framing.
 *
 * Everything is generated from the scene composition, theme palette and live lighting, and
 * every placement is deterministic, so the landscape never shimmers between clock ticks.
 */
object SceneGroundRenderer {

    // Art-directed skyline envelopes (see LandscapeProfile): x in 0..1 across the canvas.
    // Far Mamasani folds: a complete encircling range, saddled behind Persepolis. The
    // Dimeh-Mil ridge (the Mil-e Azhdaha tower's own massif west of Nurabad): a
    // low shoulder behind the ruins and readout, swelling to the great crest at the right
    // that backs the Mil-e Azhdaha tower, then continuing off-frame.
    private val MAMASANI_FOLD_ENVELOPE = floatArrayOf(
        0f, 0.58f, 0.18f, 0.78f, 0.38f, 0.64f, 0.55f, 0.76f, 0.72f, 0.72f, 0.88f, 0.82f, 1f, 0.74f
    )
    // The Dimeh-Mil ridge behind Mil-e Azhdaha (Kuh-e Shirmard / Tang-e Kalleh massif,
    // west of Nurabad): a folded whaleback anticline — long gentle back-limb rising from
    // the left, hinge near x = 0.76, then the crest continuing high off-frame at the right.
    private val DIMEH_MIL_ENVELOPE = floatArrayOf(
        0f, 0.26f, 0.14f, 0.34f, 0.30f, 0.42f, 0.45f, 0.50f, 0.58f, 0.55f,
        0.68f, 0.70f, 0.76f, 0.85f, 0.86f, 0.76f, 1f, 0.60f
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
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 151, aspect = 1.22f, trunkFraction = 0.38f, attractorCount = 105, lean = -0.04f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 252, aspect = 1.38f, trunkFraction = 0.33f, attractorCount = 92, lean = 0.01f
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

    /** The woody species of the valley floor, each with its own growth + foliage recipe. */
    private enum class TreeKind { PISTACHIO, OAK, CHERRY, SAGE, PAULOWNIA }

    // Zagros oak (Quercus brantii): strongly decurrent — a short gnarled trunk losing
    // itself into wide, twisted, self-pruned limbs under a broad flattened crown.
    private val OAK_SKELETONS: List<List<LandscapeProfile.BranchSegment>> by lazy {
        listOf(
            LandscapeProfile.treeSkeleton(
                seed = 707, aspect = 1.75f, trunkFraction = 0.30f, attractorCount = 115,
                lean = 0.02f, scaffoldSpread = 1.30f, arch = 0.5f, gnarl = 0.55f,
                occupancy = 0.8f, selfPrune = 0.5f, shellBias = 0.80f, topFlatten = 0.78f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 808, aspect = 1.60f, trunkFraction = 0.26f, attractorCount = 105,
                lean = -0.04f, scaffoldSpread = 1.20f, arch = 0.6f, gnarl = 0.65f,
                occupancy = 0.8f, selfPrune = 0.6f, shellBias = 0.80f, topFlatten = 0.76f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 111, aspect = 1.68f, trunkFraction = 0.28f, attractorCount = 110,
                lean = 0.03f, scaffoldSpread = 1.25f, arch = 0.55f, gnarl = 0.60f,
                occupancy = 0.8f, selfPrune = 0.55f, shellBias = 0.80f, topFlatten = 0.77f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 313, aspect = 1.80f, trunkFraction = 0.32f, attractorCount = 100,
                lean = -0.02f, scaffoldSpread = 1.35f, arch = 0.45f, gnarl = 0.50f,
                occupancy = 0.8f, selfPrune = 0.45f, shellBias = 0.82f, topFlatten = 0.79f
            ).sortedByDescending { it.r0 }
        )
    }

    // Flowering cherry (Somei Yoshino habit): ascending limbs opening into a rounded
    // vase, outer branches arching — in bloom the canopy is a pale-pink cloud on dark
    // wood, flowers before leaves.
    private val CHERRY_SKELETONS: List<List<LandscapeProfile.BranchSegment>> by lazy {
        listOf(
            LandscapeProfile.treeSkeleton(
                seed = 909, aspect = 1.45f, trunkFraction = 0.36f, attractorCount = 125,
                lean = 0.02f, vase = 0.55f, scaffoldSpread = 0.60f, arch = 0.8f,
                gnarl = 0.15f, occupancy = 0.6f, selfPrune = 0.25f,
                shellBias = 0.35f, topFlatten = 0.95f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 1010, aspect = 1.30f, trunkFraction = 0.40f, attractorCount = 115,
                lean = -0.03f, vase = 0.65f, scaffoldSpread = 0.50f, arch = 0.9f,
                gnarl = 0.20f, occupancy = 0.6f, selfPrune = 0.3f,
                shellBias = 0.35f, topFlatten = 0.96f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 1313, aspect = 1.38f, trunkFraction = 0.38f, attractorCount = 118,
                lean = -0.02f, vase = 0.60f, scaffoldSpread = 0.55f, arch = 0.85f,
                gnarl = 0.18f, occupancy = 0.6f, selfPrune = 0.28f,
                shellBias = 0.35f, topFlatten = 0.955f
            ).sortedByDescending { it.r0 }
        )
    }

    // Paulownia (princess/empress tree, Paulownia tomentosa): a tall straight
    // trunk under a broad rounded-to-umbrella crown of very large heart-shaped
    // leaves, flowering in upright lavender panicles just as the leaves expand.
    // Long clear trunk (trunkFraction ~0.44), flattened umbrella top, half-open
    // crown shell — the space-colonization recipe for that habit.
    private val PAULOWNIA_SKELETONS: List<List<LandscapeProfile.BranchSegment>> by lazy {
        listOf(
            LandscapeProfile.treeSkeleton(
                seed = 1111, aspect = 1.08f, trunkFraction = 0.42f, attractorCount = 120,
                lean = 0.02f, scaffoldSpread = 0.95f, arch = 0.35f, gnarl = 0.25f,
                occupancy = 0.65f, selfPrune = 0.35f, shellBias = 0.60f, topFlatten = 0.80f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 1212, aspect = 0.98f, trunkFraction = 0.46f, attractorCount = 110,
                lean = -0.03f, scaffoldSpread = 0.90f, arch = 0.30f, gnarl = 0.30f,
                occupancy = 0.65f, selfPrune = 0.30f, shellBias = 0.58f, topFlatten = 0.82f
            ).sortedByDescending { it.r0 },
            LandscapeProfile.treeSkeleton(
                seed = 1515, aspect = 1.02f, trunkFraction = 0.44f, attractorCount = 115,
                lean = 0.01f, scaffoldSpread = 0.92f, arch = 0.32f, gnarl = 0.28f,
                occupancy = 0.65f, selfPrune = 0.32f, shellBias = 0.59f, topFlatten = 0.81f
            ).sortedByDescending { it.r0 }
        )
    }

    /**
     * Static vegetation geometry for one landscape layout, cached across draws.
     *
     * Trees, scrub and grass never move — the valley is still air by design — yet their
     * geometry used to be rebuilt as ~13k individual draw calls on EVERY canvas draw:
     * once a second when idle and on every frame while scrubbing or while a selection
     * is shown. That rebuild is what made the sky canvas (and Home scrolling under it)
     * hitch. Instead everything is baked once per layout into bucketed [Path]s —
     * branches by width class × bark pass, foliage dabs by tone role, grass by colour
     * bucket — and each draw replays ~30 path draws with the current lighting colours.
     * Because the same Path instances are reused untouched, the hardware renderer's
     * path cache keeps their tessellation between frames as well. The key captures
     * everything placement depends on (canvas size, ground anchors, the framing the
     * aprons and river follow, and which side the Sun is on — branch shadow offsets
     * and rim dabs are baked with its sign); any change rebuilds exactly once.
     */
    private data class VegetationKey(
        val width: Float,
        val height: Float,
        val groundTopY: Float,
        val toeY: Float,
        val platformLeftX: Float,
        val platformRightX: Float,
        val milSlopeLeftX: Float,
        val milSlopeFrontY: Float,
        val sunSign: Float
    )

    /** One tree's ground shadow, drawn per draw so its length still tracks the Sun. */
    private class TreeShadowSpec(val cx: Float, val by: Float, val hgt: Float)

    /** All branch segments of one width class: anti-sun shadow pass + lit pass. */
    private class BranchBucket(val widthPx: Float, val shadowP: Path, val litP: Path)

    private class VegetationGeometry(
        val key: VegetationKey,
        val treeShadows: List<TreeShadowSpec>,
        val branchBuckets: List<BranchBucket>,
        val leafDarkP: Path,
        val leafAltP: Path,
        val leafBodyDimP: Path,
        val leafBodyBrightP: Path,
        val leafRimP: Path,
        val leafGlowP: Path,
        val silverDarkP: Path,
        val silverBodyP: Path,
        val silverRimP: Path,
        val silverGlowP: Path,
        val oakDarkP: Path,
        val oakAltP: Path,
        val oakDimP: Path,
        val oakBrightP: Path,
        val oakRimP: Path,
        val oakGlowP: Path,
        val oakFruitP: Path,
        val paulowniaLeafDarkP: Path,
        val paulowniaLeafP: Path,
        val paulowniaFlowerShadowP: Path,
        val paulowniaFlowerLitP: Path,
        val twigP: Path,
        val blossomShadowP: Path,
        val blossomMidP: Path,
        val blossomLitP: Path,
        val petalP: Path,
        val fieldBuckets: List<Path>,
        val nearBankFieldP: Path
    )

    /** Last built layout's vegetation; replaced whenever the key changes. Main-thread only. */
    private var vegetationCache: VegetationGeometry? = null

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

        // 5. (No horizon catch-light either: the old full-width pale wash with a hard
        //    straight top edge at groundTopY read as a white band across the scene at
        //    the readout's day/date line, flaring as scrubbing swept through daytime
        //    because its alpha scaled with ambient brightness. Any straight-edged pale
        //    rect at the horizon does this — the ridges' own silhouettes are the only
        //    horizon feature allowed.)

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
        capAmount: Float = 0f,
        scrub: Color = Color.Transparent,
        scrubAmt: Float = 0f
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
                if (scrubAmt > 0f) {
                    // Oak-scrub slopes (Zagros Quercus brantii woodland clothes the
                    // lower two-thirds of these hills): a broken green-grey wash that
                    // strengthens toward the foot and breaks up per facet.
                    val vegBand = ((frac - 0.38f) / 0.62f).coerceIn(0f, 1f)
                    val vegNoise = 0.35f + 0.65f * LandscapeProfile.hash01(microSeed + 9, i * 13 + 5)
                    c = lerp(c, scrub, vegBand * scrubAmt * vegNoise)
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
     * Back to front: far Mamasani fold range in haze; lively cool-green plain; the
     * folded Dimeh-Mil / Mil-e Azhdaha massif (benched profile, the Tang-e Farakh
     * gorge, dipping strata, caprock,
     * gullies, talus, varnish streaks); piedmont foothills and alluvial fans; a
     * cultivation mosaic and canal tree-line; the monuments' own earthworks; the broad
     * Pulvar river (layered water, point bars, ripples, pebbles, sheen + thinned glitter,
     * tree reflections, reed clumps); a space-colonization tree parkland and sage scrub;
     * a uniform grass field (no individual blades). Parameters were tuned in
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
                190, height * 0.095f, MAMASANI_FOLD_ENVELOPE, seed = 11,
                benchAmp = 0.08f, benchStep = 0.16f
            ),
            // Opaque and only lightly hazed: at 0.72 alpha washed 55% toward a
            // near-white haze, this range plus the sky bleeding through it was the
            // glowing white band across the daytime scene at the readout's date line.
            lit = lerp(palette.farRidge, rockLit, 0.35f).copy(alpha = 1f),
            shade = lerp(palette.farRidge, rockShade, 0.35f).copy(alpha = 1f),
            haze = atmosphere, hazeTop = 0.12f, hazeBase = 0.25f,
            sunSign = sunSign, facetStrength = 0.32f, strataAmp = 0f,
            microSeed = 3, crestAlpha = 0.05f + 0.10f * lightLevel, capAmount = 0.25f,
            scrub = lerp(Color(0xFF0D130D), Color(0xFF46543C), lightLevel), scrubAmt = 0.35f
        )
        // Far oak-speckle: the folded Mamasani hills carry open oak woodland; at this
        // range it is only a grain in the slope tone, denser toward the feet.
        run {
            val farHeights = LandscapeProfile.mountainHeights(
                190, height * 0.095f, MAMASANI_FOLD_ENVELOPE, seed = 11,
                benchAmp = 0.08f, benchStep = 0.16f
            )
            val speck = Path()
            for (k in 0 until 130) {
                val x = width * LandscapeProfile.hash01(401, k * 13 + 3)
                val top = farBase - LandscapeProfile.heightAt(farHeights, (x / width).coerceIn(0f, 1f))
                val hh = farBase - top
                if (hh < height * 0.03f) continue
                val y = top + hh * (0.35f + 0.60f * LandscapeProfile.hash01(403, k * 7 + 1))
                val r = height * (0.0010f + 0.0012f * LandscapeProfile.hash01(405, k * 5 + 2))
                speck.addOval(
                    androidx.compose.ui.geometry.Rect(x - r, y - r * 0.7f, x + r, y + r * 0.7f)
                )
            }
            drawScope.drawPath(
                speck,
                lerp(Color(0xFF0A0F0A), Color(0xFF3E4C34), lightLevel).copy(alpha = 0.10f + 0.16f * lightLevel)
            )
        }

        // (No horizon mist shelf here either — with the hazy far range behind it, the old
        // shelf stacked into a white band across the canvas at the readout's day/date line.
        // The range's own haze dissolve supplies the aerial perspective by itself.)

        // 3. The plain: a lively cool-green field floor, darkening toward the viewer.
        //    (The old dry olive-straw base read yellowy and half-dead under the new
        //    green field; the whole floor now lives in the same cool green family.)
        val steppeTop = lerp(palette.groundTop, Color(0xFF5F7F45), 0.62f * day)
        val fieldBottom = lerp(palette.groundBottom, Color(0xFF3C6034), 0.58f * day)
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to steppeTop,
                0.38f to lerp(steppeTop, fieldBottom, 0.45f),
                1.0f to fieldBottom,
                startY = groundTopY,
                endY = height
            ),
            topLeft = Offset(0f, groundTopY),
            size = androidx.compose.ui.geometry.Size(width, (height - groundTopY).coerceAtLeast(1f))
        )
        // (No horizon catch-light wash on the plain's top edge: its hard straight top
        // edge at groundTopY was the white band visible across the scene at the
        // readout's day/date line, strongest while scrubbing through daytime.)

        // 4. The Dimeh-Mil massif: the rocky, relatively green limestone ridge the
        //    Mil-e Azhdaha tower stands against west of Nurabad (Kuh-e Shirmard /
        //    Tang-e Kalleh). Nearer, paler and more strongly faceted than the far
        //    folds, with folded strata, gullies — and the wide gorge (Tang-e Farakh)
        //    cut through its crest, the spring at its mouth feeding the green below.
        val massifBase = toeY - height * 0.004f
        val massifHeights = LandscapeProfile.mountainHeights(
            210, height * 0.145f, DIMEH_MIL_ENVELOPE, seed = 29,
            benchAmp = 0.16f, benchStep = 0.13f,
            notches = floatArrayOf(
                0.70f, 0.034f, 0.42f, // Tang-e Farakh: the wide gorge through the crest
                0.34f, 0.020f, 0.24f  // a lesser cleft on the back-limb
            )
        )
        drawMountainLayer(
            drawScope = drawScope, width = width, sceneHeight = height, baseY = massifBase,
            heights = massifHeights,
            lit = lerp(rockLit, Color(0xFFC4AC84), 0.25f * lightLevel), // white-veined limestone
            shade = rockShade,
            haze = atmosphere, hazeTop = 0.05f, hazeBase = 0.14f,
            sunSign = sunSign, facetStrength = 0.78f, strataAmp = 0.06f,
            microSeed = 5, crestAlpha = 0.10f + 0.18f * lightLevel,
            strataDip = 0.9f, hingeX = width * 0.76f, capAmount = 0.45f,
            scrub = lerp(Color(0xFF0D140D), Color(0xFF4C5E3A), lightLevel), scrubAmt = 0.5f
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
        // The gorge (Tang-e Farakh): a shadowed cleft where the notch cuts the crest,
        // and the Dimeh-Mil spring greening its mouth — the tower's valley is watered.
        run {
            val gx = width * 0.70f
            val gTop = massifTopAt(gx)
            val gorge = Path().apply {
                moveTo(gx - width * 0.016f, gTop - height * 0.002f)
                lineTo(gx + width * 0.016f, gTop - height * 0.002f)
                lineTo(gx + width * 0.004f, massifBase - height * 0.010f)
                lineTo(gx - width * 0.004f, massifBase - height * 0.010f)
                close()
            }
            drawScope.drawPath(gorge, rockShade.scaled(0.5f).copy(alpha = 0.28f + 0.20f * lightLevel))
            drawSoftPatch(
                drawScope, gx, massifBase + height * 0.004f, width * 0.07f, height * 0.011f,
                lerp(Color(0xFF0E1A0E), Color(0xFF55763E), lightLevel),
                peakAlpha = 0.08f + 0.16f * lightLevel, layers = 8
            )
        }
        // Near oak-speckle on the massif: open Quercus brantii woodland grain.
        run {
            val speck = Path()
            for (k in 0 until 320) {
                val x = width * LandscapeProfile.hash01(409, k * 13 + 3)
                val top = massifTopAt(x)
                val hh = massifBase - top
                if (hh < height * 0.045f) continue
                val y = top + hh * (0.30f + 0.66f * LandscapeProfile.hash01(411, k * 7 + 1))
                val r = height * (0.0012f + 0.0016f * LandscapeProfile.hash01(413, k * 5 + 2))
                speck.addOval(
                    androidx.compose.ui.geometry.Rect(x - r, y - r * 0.7f, x + r, y + r * 0.7f)
                )
            }
            drawScope.drawPath(
                speck,
                lerp(Color(0xFF0A0F0A), Color(0xFF42532F), lightLevel).copy(alpha = 0.14f + 0.20f * lightLevel)
            )
        }
        // (No haze-pooling wash at the massif foot: another full-width pale rect, and
        // one more contributor to the horizontal white band. Banned like the rest.)

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
                0.0f to lerp(palette.midRidge, Color(0xFF66763F), 0.5f * day),
                1.0f to lerp(palette.midRidge.scaled(0.70f), Color(0xFF3F4E2A), 0.5f * day),
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
        val irrigated = lerp(palette.groundTop, Color(0xFF4E7A38), 0.55f * day)
            .copy(alpha = 0.20f + 0.16f * day)
        val fallow = lerp(palette.groundTop, Color(0xFF8E9A5E), 0.5f * day)
            .copy(alpha = 0.08f + 0.08f * day)
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
        // One ribbon edge at a fractional half-width (inside the true bank line).
        fun edgePath(scale: Float, offset: Float = 0f): Path = Path().apply {
            val steps = 28
            for (i in 0..steps) {
                val x = -riverMargin + (width + 2f * riverMargin) * i / steps
                val yc = riverCenterY(x)
                val y = yc + riverBend(x) * offset * riverHalfW(x, yc) - riverHalfW(x, yc) * scale
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
        // Fresnel-graded reflection (Schlick, F0 ≈ 0.02 in spirit): the far water is
        // seen at a grazing angle and mirrors the sky almost fully; the near water is
        // seen steeply, so the bed and deep water show through and the sky's share
        // falls. A flat 0.90 reflection read as a plastic sheet; the vertical grade
        // is what makes the same ribbon read as a surface lying in the light.
        drawScope.drawPath(
            ribbonPath(0.84f),
            brush = Brush.verticalGradient(
                0.0f to reflection.copy(alpha = 0.96f),
                0.55f to reflection.copy(alpha = 0.82f),
                1.0f to lerp(reflection, deepWater, 0.55f).copy(alpha = 0.60f),
                startY = riverY - height * 0.045f,
                endY = riverY + height * 0.085f
            )
        )
        drawScope.drawPath(ribbonPath(0.22f, offset = 0.45f), deepWater.copy(alpha = 0.60f))
        // Far-bank waterline: at grazing incidence the waterline itself catches the
        // sky — a hairline of reflected light just inside the far edge, separating
        // the water from the bank shadow line drawn later on the true edge.
        drawScope.drawPath(
            edgePath(0.93f),
            reflection.copy(alpha = 0.16f + 0.26f * lightLevel),
            style = Stroke(width = (height * 0.0012f).coerceIn(0.8f, 2f), cap = StrokeCap.Round)
        )
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
            // Cox–Munk glitter: a sparkle exists only where a ripple facet happens to
            // mirror the Sun/Moon to the viewer, so sparkle *density, brightness and
            // size* all follow the 2D Gaussian falloff in slope space around the
            // specular point (the sheen's centre) — dense, long and bright at the
            // heart, sparse and short at the fringes. The old 1D column rained
            // uniform dashes at any height in the ribbon; this is a point process
            // thinned by the facet probability itself.
            for (i in 0 until 72) {
                val x = width * LandscapeProfile.hash01(137, i * 7 + 2)
                val dxS = (x - glintX) / (width * 0.17f)
                if (abs(dxS) > 2.6f) continue // beyond ~2.6σ there are no facets to catch
                val yc = riverCenterY(x)
                val hw = riverHalfW(x, yc)
                val yOff = (LandscapeProfile.hash01(79, i * 5 + 3) - 0.5f) * 1.7f
                val y = yc + yOff * hw * 0.82f
                val glitter = LandscapeProfile.sunGlitter(dxS, yOff / 0.55f)
                if (LandscapeProfile.hash01(193, i * 11 + 8) > 0.10f + 0.90f * glitter) continue
                val alpha = (glintStrength * glitter *
                    (0.30f + 0.70f * LandscapeProfile.hash01(31, i * 3 + 1))).coerceIn(0f, 0.92f)
                if (alpha < 0.02f) continue
                val len = height * (0.005f + 0.020f * LandscapeProfile.hash01(57, i * 9 + 5)) *
                    (0.35f + 0.85f * glitter)
                drawScope.drawLine(
                    color = glintColor.copy(alpha = alpha),
                    start = Offset(x, y),
                    end = Offset(x + len, y),
                    strokeWidth = ((height * 0.002f) * (0.7f + 0.6f * glitter)).coerceIn(1f, 3.5f),
                    cap = StrokeCap.Round
                )
                // Rare star sparkles at the specular heart.
                if (glitter > 0.72f && LandscapeProfile.hash01(297, i * 3 + 1) > 0.62f) {
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
        // Broken reflections of the two foreground trees in the near water: ripple-
        // torn horizontal slices. Deeper slices sit on water that has travelled
        // farther from the tree, so their wobble grows, their dashes split, and
        // hash-driven gaps open in them — a reflection is a signal the surface
        // keeps interrupting, not a stack of neat bars.
        for ((txFrac, thFrac) in listOf(0.115f to 0.068f, 0.875f to 0.058f)) {
            val tx = width * txFrac
            val th = height * thFrac
            val yc = riverCenterY(tx)
            val hw = riverHalfW(tx, yc)
            val bot = yc + hw
            val seedBase = (txFrac * 1000f).toInt()
            for (j in 0 until 7) {
                val y = bot - (j + 0.5f) * (th * 0.55f / 7f)
                if (y < yc - hw) break
                // Ripple gaps: some slices are simply missing, more often deeper.
                if (j > 0 && LandscapeProfile.hash01(277, seedBase + j * 7) < 0.10f + 0.05f * j) continue
                val wdt = th * 1.1f * (0.55f - 0.045f * j)
                val wobble = (LandscapeProfile.hash01(77, seedBase + j * 7) - 0.5f) *
                    height * 0.006f * (0.6f + 0.35f * j)
                val alpha = 0.30f * (1f - j / 7f) * (0.4f + 0.6f * lightLevel)
                val strokeW = (height * 0.002f).coerceIn(1f, 3.5f)
                drawScope.drawLine(
                    color = canopyDark.copy(alpha = alpha),
                    start = Offset(tx + wobble - wdt / 2f, y),
                    end = Offset(tx + wobble + wdt / 2f, y),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
                // A second, shorter fragment torn sideways off the deeper slices.
                if (j >= 2 && LandscapeProfile.hash01(281, seedBase + j * 11) > 0.45f) {
                    val fragW = wdt * (0.30f + 0.20f * LandscapeProfile.hash01(283, seedBase + j * 13))
                    val fragX = tx + wobble * 1.7f +
                        (LandscapeProfile.hash01(293, seedBase + j * 17) - 0.5f) * wdt * 0.5f
                    drawScope.drawLine(
                        color = canopyDark.copy(alpha = alpha * 0.7f),
                        start = Offset(fragX - fragW / 2f, y + th * 0.028f),
                        end = Offset(fragX + fragW / 2f, y + th * 0.028f),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
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

        // 11-12. Trees, scrub and steppe grass — baked once per layout, replayed per draw
        //     (see VegetationGeometry for why). Placement and species logic are unchanged
        //     from when this was drawn directly, with two deliberate exceptions: the
        //     shrubs that used to straddle the river's near side are gone (only the seven
        //     foothill shrubs remain), and the foreground corner-shade patches at the
        //     viewer's feet are gone too — the scene's bottom now dissolves into the app
        //     backdrop instead (HeroSkyCanvas's bottom merge over drawBottomFade).
        val vegKey = VegetationKey(
            width, height, groundTopY, toeY,
            framing.platformLeftXPx, framing.platformRightXPx,
            framing.milSlopeLeftXPx, framing.milSlopeFrontYPx, sunSign
        )
        fun buildVegetation(): VegetationGeometry {
            val treeShadows = mutableListOf<TreeShadowSpec>()
            val branchBuckets = listOf(1.1f, 1.8f, 2.8f, 4.2f, 6.2f, 9f, 13f, 19f, 27f)
                .map { BranchBucket(it, Path(), Path()) }
            val leafDarkP = Path(); val leafAltP = Path(); val leafBodyDimP = Path(); val leafBodyBrightP = Path(); val leafRimP = Path()
            val leafGlowP = Path()
            val silverDarkP = Path(); val silverBodyP = Path(); val silverRimP = Path(); val silverGlowP = Path()
            val oakDarkP = Path(); val oakAltP = Path(); val oakDimP = Path(); val oakBrightP = Path(); val oakRimP = Path()
            val oakGlowP = Path(); val oakFruitP = Path(); val twigP = Path()
            val paulowniaLeafDarkP = Path(); val paulowniaLeafP = Path()
            val paulowniaFlowerShadowP = Path(); val paulowniaFlowerLitP = Path()
            val blossomShadowP = Path(); val blossomMidP = Path(); val blossomLitP = Path(); val petalP = Path()
            val fieldBuckets = List(8) { Path() }
            val nearBankFieldP = Path()

            fun addOvalTo(path: Path, cx: Float, cy: Float, w: Float, h: Float) {
                path.addOval(
                    androidx.compose.ui.geometry.Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                )
            }
            // One leaf cluster as an irregular lobed polygon (LandscapeProfile
            // .clusterPolygon) instead of an oval: the perimeter alternates lobes
            // and notches, so crowns fray like foliage instead of scalloping like
            // cotton balls. Same bake-once path cost class as the oval it replaces.
            fun addClusterTo(
                path: Path, cx: Float, cy: Float, r: Float, seed: Int, index: Int,
                squish: Float = 0.78f, lobes: Int = 7
            ) {
                val pts = LandscapeProfile.clusterPolygon(r, squish, seed, index, lobes)
                path.moveTo(cx + pts[0], cy + pts[1])
                var k = 2
                while (k < pts.size) {
                    path.lineTo(cx + pts[k], cy + pts[k + 1])
                    k += 2
                }
                path.close()
            }
            // One skeleton planted at (cx, by) with total height [hgt]: pipe-model woody
            // structure bucketed by width class (shadow pass offset anti-sun for
            // roundness on near trees), then foliage as *leaf-cluster dabs* at the
            // branch terminals bucketed by tone role — the 2D analogue of the
            // leaf-cluster cards real-time vegetation (SpeedTree & co.) is built from.
            // Two of that world's shading rules do the realism work here: each cluster
            // is lit by a *spherized crown normal* (the direction from crown centre to
            // cluster dotted with the Sun — crowns shade as volumes, not as noise), and
            // darkened by *crown-depth ambient occlusion* (interior clusters sit in the
            // crown's own shade; the shell carries the light). Species ([kind]) change
            // cluster size/density/placement and the colour buckets: pistachio scrub,
            // silver Artemisia sage, dense terminal-tufted oak, and cherry in blossom
            // (flowers smothering the outer branches before the leaves, Somei Yoshino
            // fashion, with petal litter under the crown). Small/distant instances drop
            // the second bark pass and use fewer dabs (LOD by height).
            fun buildSkeletonTree(
                cx: Float,
                by: Float,
                hgt: Float,
                skeleton: List<LandscapeProfile.BranchSegment>,
                flip: Boolean,
                foliage: Float,
                seed: Int,
                kind: TreeKind = TreeKind.PISTACHIO
            ) {
                val sx = if (flip) -1f else 1f
                val detail = hgt > height * 0.034f
                treeShadows += TreeShadowSpec(cx, by, hgt)
                for (seg in skeleton) {
                    val wdt = ((seg.r0 + seg.r1) * hgt).coerceIn(0.8f, (hgt * 0.09f).coerceAtLeast(1f))
                    val bIdx = branchBuckets.indexOfFirst { it.widthPx >= wdt }
                        .let { if (it < 0) branchBuckets.lastIndex else it }
                    val bucket = branchBuckets[bIdx]
                    val x0 = cx + sx * seg.x0 * hgt
                    val y0 = by - seg.y0 * hgt
                    val x1 = cx + sx * seg.x1 * hgt
                    val y1 = by - seg.y1 * hgt
                    // Branches are not ruler-straight: each segment bows a little
                    // (Sapling/MTree curvature + straightness lesson), the bow
                    // hashed from the segment itself so it is stable per layout.
                    // Trunk segments bow least; twigs wander most.
                    val segLen = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)).coerceAtLeast(1f)
                    val bowKey = ((seg.x0 + seg.y0) * 10000f).toInt() + seg.depth * 131
                    val bowAmt = (LandscapeProfile.hash01(seed * 337 + 7, bowKey) - 0.5f) *
                        segLen * if (seg.depth < 2) 0.05f else 0.16f
                    val perpX = -(y1 - y0) / segLen
                    val perpY = (x1 - x0) / segLen
                    val ctrlX = (x0 + x1) / 2f + perpX * bowAmt
                    val ctrlY = (y0 + y1) / 2f + perpY * bowAmt
                    if (detail) {
                        bucket.shadowP.moveTo(x0 - sunSign * wdt * 0.22f, y0)
                        bucket.shadowP.lineTo(x1 - sunSign * wdt * 0.22f, y1)
                    }
                    bucket.litP.moveTo(x0, y0)
                    bucket.litP.quadraticBezierTo(ctrlX, ctrlY, x1, y1)
                    // Trunk taper read: the lowest segments are re-stroked one
                    // width class up, so the base flares into its limbs instead
                    // of ending in a uniform pole (Sapling root-flare lesson).
                    if (seg.depth <= 1) {
                        branchBuckets.getOrNull(bIdx + 1)?.let { wider ->
                            wider.litP.moveTo(x0, y0)
                            wider.litP.quadraticBezierTo(ctrlX, ctrlY, x1, y1)
                        }
                    }
                }
                // Crown envelope in flipped unit space, for the volume shading below.
                var minUx = Float.MAX_VALUE; var maxUx = -Float.MAX_VALUE
                var topUy = 0f; var lowUy = Float.MAX_VALUE
                for (seg in skeleton) {
                    val ux = sx * seg.x1
                    if (ux < minUx) minUx = ux
                    if (ux > maxUx) maxUx = ux
                    if (seg.y1 > topUy) topUy = seg.y1
                    if (seg.depth >= 3 && seg.y1 < lowUy) lowUy = seg.y1
                }
                if (lowUy == Float.MAX_VALUE) lowUy = topUy * 0.4f
                val ccx = (minUx + maxUx) / 2f
                val ccy = (topUy + lowUy) / 2f
                val crx = ((maxUx - minUx) / 2f).coerceAtLeast(0.05f)
                val cry = ((topUy - lowUy) / 2f).coerceAtLeast(0.05f)

                // Per-instance crown proportions: the same skeleton carries a
                // narrower or broader leaf mass per planted tree (its woody
                // structure unchanged), so neighbours stop sharing one outline.
                val crownWide = 0.88f + 0.24f * LandscapeProfile.hash01(seed * 613 + 9, 7)
                // Painterly under-mass: ONE jagged dark polygon spanning the
                // crown envelope, laid down before any cluster. It fills the
                // gaps between terminals with shaded interior foliage, so the
                // crown reads as one cohesive leaf mass that the lit clusters
                // then break up — instead of separate dotted blobs with the
                // field showing through. One polygon per tree, at any range.
                run {
                    val massBucket = when (kind) {
                        TreeKind.SAGE -> silverDarkP
                        TreeKind.OAK -> oakDarkP
                        TreeKind.CHERRY -> blossomShadowP
                        TreeKind.PAULOWNIA -> paulowniaLeafDarkP
                        else -> leafDarkP
                    }
                    addClusterTo(
                        massBucket,
                        cx + ccx * hgt, by - ccy * hgt,
                        crx * hgt * 0.84f * crownWide, seed * 419 + 7, 5,
                        squish = (cry / crx).coerceIn(0.45f, 1.05f), lobes = 9
                    )
                }
                // Per-instance foliage variation: each planted tree leans a
                // hashed share of its body clusters into the variant-tone bucket,
                // so no two crowns of a species share quite the same green
                // (Sapling seed-variation lesson, at bucket granularity).
                val altFrac = LandscapeProfile.hash01(seed * 771 + 5, 3) * 0.50f
                val dabs = when (kind) {
                    TreeKind.OAK, TreeKind.CHERRY, TreeKind.PAULOWNIA -> if (detail) 5 else 3
                    else -> if (detail) 4 else 2
                }
                val minDepth = if (kind == TreeKind.CHERRY || kind == TreeKind.PAULOWNIA) 6 else 7
                var ci = 0
                for (seg in skeleton) {
                    if (!seg.terminal && seg.depth < minDepth) continue
                    ci++
                    val ux = sx * seg.x1
                    val ex = ((ux - ccx) / crx).coerceIn(-1f, 1f)
                    val ey = ((seg.y1 - ccy) / cry).coerceIn(-1f, 1f)
                    val r01 = sqrt(ex * ex + ey * ey).coerceIn(0f, 1f) // 1 = crown shell
                    val facing = ex * sunSign * 0.85f + ey * 0.55f
                    // Oak leafs in tufts at the shell; its shaded interior stays woody.
                    val accept = if (kind == TreeKind.OAK) foliage * (0.55f + 0.45f * r01) else foliage
                    if (LandscapeProfile.hash01(seed * 71 + 13, ci * 3 + 1) > accept) continue
                    // Cluster position: branch tip, with its offset from the
                    // crown centre scaled by this tree's crown-width variation.
                    val px = cx + ccx * hgt + (sx * seg.x1 - ccx) * hgt * crownWide
                    val py = by - seg.y1 * hgt
                    val crMul = when (kind) {
                        TreeKind.OAK -> 1.25f
                        TreeKind.CHERRY -> 0.98f // blossom smothers: clusters must overlap, not dot
                        TreeKind.PAULOWNIA -> 1.50f // huge heart leaves read coarse at range
                        else -> 1f
                    }
                    val cr = hgt * (0.045f + 0.040f * LandscapeProfile.hash01(seed * 37 + 7, ci * 5 + 2)) * crMul
                    // Depth layering under every lit cluster: a darker, slightly
                    // larger jagged underlay dropped down/anti-sun. This is what
                    // stops a crown reading as one flat cut-out — clusters sit IN
                    // shade of their neighbours like real leaf masses.
                    if (detail) {
                        val under = when (kind) {
                            TreeKind.SAGE -> silverDarkP
                            TreeKind.OAK -> oakDarkP
                            TreeKind.CHERRY -> blossomShadowP
                            TreeKind.PAULOWNIA -> paulowniaLeafDarkP
                            else -> leafDarkP
                        }
                        addClusterTo(
                            under, px + sunSign * cr * 0.05f, py + cr * 0.14f,
                            cr * 0.85f, seed * 409 + 3, ci * 77 + 1
                        )
                    }
                    val tone = 0.85f + 0.3f * LandscapeProfile.hash01(seed * 53 + 3, ci * 7 + 4)
                    // Spherized-normal light × crown-depth AO × per-cluster variation.
                    val light = (0.50f + 0.42f * facing) * (0.42f + 0.58f * r01) * tone
                    // Ordered-dither offset for the tone thresholds below: bucket
                    // boundaries are crossed at hashed light values, so the
                    // dim->bright transition grades across the crown instead of
                    // splitting it into two flat zones with spotty contrast.
                    val dith = (LandscapeProfile.hash01(seed * 59 + 3, ci * 31 + 7) - 0.5f)
                    // Cluster-of-clusters (the SpeedTree leaf-card lesson, in 2D): one
                    // smooth oval reads as a lollipop. A dark core, body satellites
                    // orbited around it at ~0.4·cr, and one small edge dab pushed out
                    // along the crown normal give a scalloped, lacey silhouette with
                    // real gaps between clusters — at the same oval budget as before.
                    // The edge dab is also where the light model splits three ways:
                    // sun-facing shell → rim catch-light; anti-sun shell → the glow
                    // bucket (leaf translucency: thin leaves transmit low sun, Habel
                    // et al. — coloured warm at replay when the tree is backlit);
                    // anywhere else it is ordinary body foliage.
                    val backShell = facing < -0.25f && r01 > 0.55f
                    for (d in 0 until dabs) {
                        val baseR = if (kind == TreeKind.CHERRY) {
                            cr * (0.38f + 0.30f * LandscapeProfile.hash01(seed * 83 + 9, ci * 41 + d * 13))
                        } else {
                            cr * (0.42f + 0.30f * LandscapeProfile.hash01(seed * 83 + 9, ci * 41 + d * 13))
                        }
                        val isCore = d == 0
                        val isEdge = d == dabs - 1 && dabs > 1
                        val rimDab = isEdge && detail && facing > 0.15f
                        val glowDab = isEdge && detail && backShell && kind != TreeKind.CHERRY
                        val ox: Float
                        val oy: Float
                        val r: Float
                        when {
                            isCore -> {
                                ox = (LandscapeProfile.hash01(seed * 97 + 3, ci * 31 + d * 7) - 0.5f) * cr * 0.35f -
                                    sunSign * cr * 0.20f
                                oy = (LandscapeProfile.hash01(seed * 89 + 5, ci * 37 + d * 11) - 0.5f) * cr * 0.25f +
                                    cr * 0.14f + if (kind == TreeKind.OAK) cr * 0.08f else 0f
                                r = baseR
                            }
                            isEdge -> {
                                // Out along the crown normal (screen y is flipped).
                                ox = ex * cr * 0.52f + sunSign * cr * 0.10f
                                oy = -ey * cr * 0.40f + if (kind == TreeKind.OAK) cr * 0.06f else 0f
                                r = baseR * 0.52f
                            }
                            else -> {
                                val ang = LandscapeProfile.hash01(seed * 101 + 7, ci * 43 + d * 17) * 2f * PI.toFloat()
                                val dist = cr * (0.30f + 0.24f * LandscapeProfile.hash01(seed * 103 + 11, ci * 47 + d * 19))
                                ox = cos(ang) * dist
                                oy = -sin(ang) * dist * 0.72f +
                                    if (kind == TreeKind.OAK) cr * 0.08f else 0f // oak tufts settle downward
                                r = baseR * 0.62f
                            }
                        }
                        val target = when (kind) {
                            TreeKind.SAGE -> when {
                                isCore -> silverDarkP
                                rimDab -> silverRimP
                                glowDab -> silverGlowP
                                else -> silverBodyP
                            }
                            TreeKind.OAK -> when {
                                isCore -> oakDarkP
                                rimDab -> oakRimP
                                glowDab -> oakGlowP
                                light < 0.55f + dith * 0.28f -> oakDimP
                                LandscapeProfile.hash01(seed * 211 + 17, ci * 29 + d) < altFrac -> oakAltP
                                else -> oakBrightP
                            }
                            TreeKind.CHERRY -> when {
                                light < 0.42f + dith * 0.16f -> blossomShadowP
                                light < 0.72f + dith * 0.16f -> blossomMidP
                                else -> blossomLitP
                            }
                            TreeKind.PAULOWNIA -> when {
                                isCore -> paulowniaLeafDarkP
                                rimDab -> paulowniaLeafP
                                glowDab -> paulowniaLeafP
                                light < 0.55f + dith * 0.24f -> paulowniaLeafDarkP
                                else -> paulowniaLeafP
                            }
                            else -> when {
                                isCore -> leafDarkP
                                rimDab -> leafRimP
                                glowDab -> leafGlowP
                                light < 0.55f + dith * 0.28f -> leafBodyDimP
                                LandscapeProfile.hash01(seed * 211 + 17, ci * 29 + d) < altFrac -> leafAltP
                                else -> leafBodyBrightP
                            }
                        }
                        // Shell clusters shrink and fray; interior clusters
                        // swell — the crown's edge goes lacey while its heart
                        // stays dense (leaf cards do the same by distribution).
                        val shellGrade = when {
                            r01 > 0.80f -> 0.82f
                            r01 < 0.35f -> 1.10f
                            else -> 1f
                        }
                        addClusterTo(target, px + ox, py + oy, r * shellGrade, seed * 401 + 11, ci * 101 + d * 13)
                    }
                    // Paulownia flowers before/with the leaves: upright foxglove-like
                    // panicles standing off the outer terminals — pale lavender in the
                    // light, mauve in the crown's shade. They are what makes the species
                    // identifiable across a valley in spring, like cherry blossom.
                    if (kind == TreeKind.PAULOWNIA && seg.terminal &&
                        LandscapeProfile.hash01(seed * 191 + 23, ci * 19 + 7) < 0.72f
                    ) {
                        val flowerP = if (light >= 0.50f) paulowniaFlowerLitP else paulowniaFlowerShadowP
                        addOvalTo(
                            flowerP,
                            px + (LandscapeProfile.hash01(seed * 193 + 29, ci * 23 + 3) - 0.5f) * cr * 0.45f,
                            py - cr * 0.55f,
                            cr * (0.50f + 0.18f * LandscapeProfile.hash01(seed * 197 + 31, ci * 29 + 5)),
                            cr * 1.15f
                        )
                    }
                    // Fine twig tips poking just past the leaf shell — the lace on the
                    // crown's lit edge. Without them every crown ends in a smooth
                    // leaf wall; with them the silhouette frays like a real tree's.
                    if (detail && seg.terminal && r01 > 0.70f &&
                        LandscapeProfile.hash01(seed * 163 + 11, ci * 7 + 2) < 0.45f
                    ) {
                        val tl = cr * (0.35f + 0.30f * LandscapeProfile.hash01(seed * 167 + 13, ci * 11 + 3))
                        twigP.moveTo(px, py)
                        twigP.lineTo(px + ex * tl, py - ey * tl - cr * 0.08f)
                    }
                    // Oak fruit: pale acorn cups catching the light inside the shell —
                    // the field mark of Quercus brantii at reading distance.
                    if (kind == TreeKind.OAK && detail && light > 0.55f &&
                        LandscapeProfile.hash01(seed * 173 + 17, ci * 13 + 5) < 0.55f
                    ) {
                        val fr = hgt * 0.0055f
                        addOvalTo(
                            oakFruitP,
                            px + (LandscapeProfile.hash01(seed * 179 + 19, ci * 17 + 7) - 0.5f) * cr * 0.8f,
                            py + cr * 0.25f, fr * 2f, fr * 1.4f
                        )
                    }
                    // Cherry leafs only as the bloom fades: a whisper of emerging leaves
                    // among the blossom (drawn in the shared leaf buckets).
                    if (kind == TreeKind.CHERRY &&
                        LandscapeProfile.hash01(seed * 131 + 7, ci * 3 + 2) < 0.22f
                    ) {
                        addOvalTo(leafBodyDimP, px, py + cr * 0.15f, cr * 0.85f, cr * 0.55f)
                    }
                }
                if (kind == TreeKind.CHERRY) {
                    // Fallen petals: a pale litter under the crown and a few still
                    // drifting beside it — hanami trees snow their own ground pink.
                    for (p in 0 until 12) {
                        val gx = cx + sx * ccx * hgt +
                            (LandscapeProfile.hash01(seed * 139 + 3, p * 7 + 1) - 0.5f) * crx * hgt * 2.1f
                        val gy = by + hgt * 0.014f * LandscapeProfile.hash01(seed * 149 + 5, p * 11 + 2)
                        addOvalTo(petalP, gx, gy, hgt * 0.020f, hgt * 0.006f)
                    }
                    for (p in 0 until 5) {
                        val ax = cx + sx * ccx * hgt +
                            (LandscapeProfile.hash01(seed * 151 + 7, p * 13 + 3) - 0.5f) * crx * hgt * 2.3f
                        val ay = by - hgt * (0.22f + 0.38f * LandscapeProfile.hash01(seed * 157 + 9, p * 17 + 4))
                        addOvalTo(petalP, ax, ay, hgt * 0.011f, hgt * 0.007f)
                    }
                }
            }
            fun bush(cx: Float, by: Float, s: Float, idx: Int) {
                // Two shrub species share the foothills: olive pistachio scrub and
                // silver-grey Artemisia sage (every third bush).
                buildSkeletonTree(
                    cx, by, s * 2.1f, BUSH_SKELETONS[idx % 3], idx % 2 == 0, 0.62f,
                    seed = idx * 3 + 1, kind = if (idx % 3 == 1) TreeKind.SAGE else TreeKind.PISTACHIO
                )
            }
            val persepolisApronL = framing.platformLeftXPx - width * 0.030f
            val persepolisApronR = framing.platformRightXPx + width * 0.105f
            val slopeApronL = framing.milSlopeLeftXPx - width * 0.065f
            fun inApron(x: Float, y: Float): Boolean =
                (x in persepolisApronL..persepolisApronR && y < toeY + height * 0.095f) ||
                    (x > slopeApronL && y < framing.milSlopeFrontYPx + height * 0.095f)
            // Foothill shrubs only — nothing shrubby on the river's near side any more.
            for (i in 0 until 7) {
                val x = width * LandscapeProfile.hash01(197, i * 11 + 3)
                val y = toeY - height * 0.004f + height * 0.010f * LandscapeProfile.hash01(39, i * 3 + 1)
                if (!inApron(x, y)) {
                    bush(x, y, height * (0.005f + 0.004f * LandscapeProfile.hash01(71, i * 5 + 2)), i)
                }
            }
            // Lone Zagros oak between the sites; the near-bank foreground frame is an
            // oak at the left and a flowering cherry at the right (bases set from the
            // widened river's actual edge, not a fixed offset) — real trees doing the
            // foreground's work, where a fringe of generic grass spikes used to.
            fun nearBankY(x: Float): Float {
                val yc = riverCenterY(x)
                return yc + riverHalfW(x, yc) * 1.35f + height * 0.006f
            }
            buildSkeletonTree(
                (framing.platformRightXPx + framing.milSlopeLeftXPx) / 2f, toeY + height * 0.020f,
                height * 0.044f, OAK_SKELETONS[0], flip = false, foliage = 0.92f, seed = 7,
                kind = TreeKind.OAK
            )
            buildSkeletonTree(
                width * 0.115f, nearBankY(width * 0.115f),
                height * 0.072f, OAK_SKELETONS[1], flip = false, foliage = 0.95f, seed = 11,
                kind = TreeKind.OAK
            )
            buildSkeletonTree(
                width * 0.875f, nearBankY(width * 0.875f),
                height * 0.066f, CHERRY_SKELETONS[0], flip = true, foliage = 0.97f, seed = 13,
                kind = TreeKind.CHERRY
            )
            // 27 deterministic plantings (clearings and open water refuse a couple,
            // which reads as natural gaps): a riparian gallery on the far bank, an open
            // woodland along the canal and foothills, scattered plain pistachios and two
            // more near-bank trees — the Zagros oak/pistachio parkland this plain has.
            fun plantTree(x: Float, y: Float, hgt: Float, idx: Int, kind: TreeKind = TreeKind.PISTACHIO): Boolean {
                // Trees are refused only in the worn earth right at the stone's feet; the
                // aprons' faint forward tails are ordinary foreground field where the
                // riparian and canal trees belong.
                if (inApron(x, y) && y < toeY + height * 0.038f) return false
                val ycHere = riverCenterY(x)
                if (abs(y - ycHere) < riverHalfW(x, ycHere) * 1.22f) return false
                val skeleton = when (kind) {
                    TreeKind.OAK -> OAK_SKELETONS[idx % OAK_SKELETONS.size]
                    TreeKind.CHERRY -> CHERRY_SKELETONS[idx % CHERRY_SKELETONS.size]
                    TreeKind.PAULOWNIA -> PAULOWNIA_SKELETONS[idx % PAULOWNIA_SKELETONS.size]
                    else -> TREE_SKELETONS[idx % TREE_SKELETONS.size]
                }
                buildSkeletonTree(
                    x, y, hgt, skeleton,
                    flip = idx % 2 == 1,
                    foliage = 0.82f + 0.15f * LandscapeProfile.hash01(701, idx * 3 + 1),
                    seed = idx * 7 + 3,
                    kind = kind
                )
                return true
            }
            for (i in 0 until 9) { // riparian gallery, far bank — cherries among the pistachios
                val x = width * (0.05f + 0.90f * (i + LandscapeProfile.hash01(601, i * 3 + 1)) / 9f)
                val yc = riverCenterY(x)
                val y = yc - riverHalfW(x, yc) * (1.45f + 0.30f * LandscapeProfile.hash01(603, i * 5 + 2))
                val kind = when (i) {
                    1, 4, 6 -> TreeKind.CHERRY
                    3, 7 -> TreeKind.OAK
                    else -> TreeKind.PISTACHIO
                }
                plantTree(x, y, height * (0.030f + 0.018f * LandscapeProfile.hash01(607, i * 7 + 3)), 100 + i, kind)
            }
            for (i in 0 until 6) { // canal tree line, among the canopy dots
                val x = width * (0.08f + 0.84f * (i + LandscapeProfile.hash01(613, i * 3 + 1)) / 6f)
                val y = canalY - height * 0.004f + (x / width) * height * 0.010f
                plantTree(
                    x, y, height * (0.020f + 0.012f * LandscapeProfile.hash01(617, i * 5 + 2)), 120 + i,
                    kind = if (i == 2) TreeKind.CHERRY else TreeKind.PISTACHIO
                )
            }
            for (i in 0 until 6) { // foothill open woodland
                val x = width * LandscapeProfile.hash01(619, i * 11 + 5)
                val y = toeY + height * (0.006f + 0.024f * LandscapeProfile.hash01(631, i * 7 + 2))
                plantTree(
                    x, y, height * (0.022f + 0.012f * LandscapeProfile.hash01(633, i * 3 + 1)), 140 + i,
                    kind = if (i == 2) TreeKind.OAK else TreeKind.PISTACHIO
                )
            }
            for (i in 0 until 4) { // scattered plain pistachios
                val x = width * (0.10f + 0.80f * LandscapeProfile.hash01(641, i * 13 + 7))
                val y = height * (0.855f + 0.045f * LandscapeProfile.hash01(643, i * 5 + 3))
                plantTree(x, y, height * (0.026f + 0.014f * LandscapeProfile.hash01(647, i * 7 + 2)), 160 + i)
            }
            for ((j, xf) in listOf(0.34f, 0.63f).withIndex()) { // near bank: cherry + oak
                val x = width * (xf + (LandscapeProfile.hash01(653, j * 3 + 1) - 0.5f) * 0.03f)
                plantTree(
                    x, nearBankY(x),
                    height * (0.054f + 0.012f * LandscapeProfile.hash01(657, j * 5 + 2)), 180 + j,
                    kind = if (j == 0) TreeKind.CHERRY else TreeKind.OAK
                )
            }

            // Grove expansion (+33 trees): 15 Paulownia, 10 flowering cherry, 8 Zagros
            // oak, planted where each species belongs — Paulownia on the moist ground
            // along the river and canal (it is a waterside tree wherever it grows),
            // cherry in the riparian gallery and plain groves where its blossom reads,
            // oak in the dry groves and scattered plain. Heights follow perspective:
            // small at the foothills, largest on the near bank. A refused spot retries
            // a step away from the water, so these counts are the counts that stand.
            fun farBankY(x: Float, mul: Float): Float {
                val yc = riverCenterY(x)
                return yc - riverHalfW(x, yc) * mul
            }
            fun canalLineY(x: Float): Float =
                canalY - height * 0.004f + (x / width) * height * 0.010f
            var groveIdx = 200
            fun plantGrove(x: Float, y: Float, hgt: Float, kind: TreeKind) {
                if (plantTree(x, y, hgt, groveIdx, kind)) {
                    groveIdx++
                    return
                }
                if (plantTree(x, y - height * 0.014f, hgt, groveIdx, kind)) {
                    groveIdx++
                    return
                }
                if (plantTree(x, y + height * 0.014f, hgt, groveIdx, kind)) groveIdx++
            }
            // Paulownia x15: 5 far bank, 4 canal, 4 plain grove, 2 near-bank frame.
            for ((j, xf) in listOf(0.09f, 0.27f, 0.48f, 0.69f, 0.90f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, farBankY(x, 1.52f + 0.22f * LandscapeProfile.hash01(1201, j * 3 + 1)),
                    height * (0.038f + 0.010f * LandscapeProfile.hash01(1203, j * 5 + 2)),
                    TreeKind.PAULOWNIA
                )
            }
            for ((j, xf) in listOf(0.16f, 0.36f, 0.58f, 0.80f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, canalLineY(x),
                    height * (0.028f + 0.008f * LandscapeProfile.hash01(1207, j * 5 + 2)),
                    TreeKind.PAULOWNIA
                )
            }
            for ((j, t) in listOf(
                Triple(0.24f, 0.858f, 0.044f), Triple(0.44f, 0.862f, 0.040f),
                Triple(0.64f, 0.856f, 0.046f), Triple(0.84f, 0.864f, 0.042f)
            ).withIndex()) {
                plantGrove(
                    width * t.first, height * t.second,
                    height * (t.third + 0.006f * LandscapeProfile.hash01(1211, j * 5 + 2)),
                    TreeKind.PAULOWNIA
                )
            }
            for ((j, xf) in listOf(0.20f, 0.78f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, nearBankY(x),
                    height * (0.056f + 0.008f * LandscapeProfile.hash01(1213, j * 5 + 2)),
                    TreeKind.PAULOWNIA
                )
            }
            // Flowering cherry x10: 3 far bank, 2 canal, 5 plain groves.
            for ((j, xf) in listOf(0.18f, 0.58f, 0.84f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, farBankY(x, 1.55f + 0.20f * LandscapeProfile.hash01(1217, j * 3 + 1)),
                    height * (0.030f + 0.008f * LandscapeProfile.hash01(1219, j * 5 + 2)),
                    TreeKind.CHERRY
                )
            }
            for ((j, xf) in listOf(0.26f, 0.68f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, canalLineY(x),
                    height * (0.024f + 0.006f * LandscapeProfile.hash01(1223, j * 5 + 2)),
                    TreeKind.CHERRY
                )
            }
            for ((j, t) in listOf(
                Triple(0.34f, 0.852f, 0.034f), Triple(0.52f, 0.858f, 0.038f),
                Triple(0.74f, 0.850f, 0.032f), Triple(0.42f, 0.866f, 0.036f),
                Triple(0.92f, 0.860f, 0.034f)
            ).withIndex()) {
                plantGrove(
                    width * t.first, height * t.second,
                    height * (t.third + 0.006f * LandscapeProfile.hash01(1229, j * 5 + 2)),
                    TreeKind.CHERRY
                )
            }
            // Zagros oak x8: 3 dry groves, 3 scattered plain, 2 far bank.
            for ((j, t) in listOf(
                Triple(0.12f, 0.850f, 0.028f), Triple(0.48f, 0.854f, 0.030f),
                Triple(0.86f, 0.866f, 0.028f)
            ).withIndex()) {
                plantGrove(
                    width * t.first, height * t.second,
                    height * (t.third + 0.006f * LandscapeProfile.hash01(1231, j * 5 + 2)),
                    TreeKind.OAK
                )
            }
            for ((j, t) in listOf(
                Triple(0.30f, 0.864f, 0.036f), Triple(0.58f, 0.868f, 0.038f),
                Triple(0.78f, 0.860f, 0.034f)
            ).withIndex()) {
                plantGrove(
                    width * t.first, height * t.second,
                    height * (t.third + 0.006f * LandscapeProfile.hash01(1237, j * 5 + 2)),
                    TreeKind.OAK
                )
            }
            for ((j, xf) in listOf(0.36f, 0.95f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, farBankY(x, 1.58f + 0.18f * LandscapeProfile.hash01(1241, j * 3 + 1)),
                    height * (0.032f + 0.008f * LandscapeProfile.hash01(1243, j * 5 + 2)),
                    TreeKind.OAK
                )
            }

            // Near-bank additions (+5): three more flowering cherries and two more
            // Zagros oaks along the viewer's own bank, filling the riparian grove
            // between the Paulownia frames — blossom and oak shade over the water.
            for ((j, xf) in listOf(0.40f, 0.55f, 0.70f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, nearBankY(x),
                    height * (0.056f + 0.008f * LandscapeProfile.hash01(1253, j * 5 + 2)),
                    TreeKind.CHERRY
                )
            }
            for ((j, xf) in listOf(0.47f, 0.66f).withIndex()) {
                val x = width * xf
                plantGrove(
                    x, nearBankY(x),
                    height * (0.052f + 0.008f * LandscapeProfile.hash01(1257, j * 5 + 2)),
                    TreeKind.OAK
                )
            }

            // Uniform grass field — the researched distant-grass solution (the
            // ground-as-grass impostor of hexaquo's LOD work, Ghost-of-Tsushima's
            // field-as-one-mass shading): NO individual blades anywhere. The field
            // is a single textured mass — a perspective grid of overlapping tone
            // cells quantized into eight colour buckets, so the whole valley floor
            // costs eight path fills per frame. Cell tone is albedo patch noise
            // (fbm) x moisture (river/canal proximity), in a lively cool green;
            // near the banks the same field deepens into a lush ribbon. Mip-like
            // behaviour comes free: far rows are smaller and their tone contrast
            // is compressed toward the distance tone, so the texture smooths with
            // range exactly like a mipmapped ground texture.
            fun canalYAt(x: Float): Float = canalY - height * 0.004f + (x / width) * height * 0.010f
            fun grassAllowed(x: Float, y: Float): Boolean {
                if (inApron(x, y)) return false
                val yc = riverCenterY(x)
                return abs(y - yc) >= riverHalfW(x, yc) * 1.10f
            }
            fun moistureAt(x: Float, y: Float, depth: Float): Float {
                var m = 0.5f + 0.5f * LandscapeProfile.fbm(x / width * 4.2f + depth * 1.7f, 91, octaves = 3)
                val yc = riverCenterY(x)
                m += (1f - abs(y - yc) / (riverHalfW(x, yc) * 2.6f + height * 0.008f)).coerceIn(0f, 1f) * 0.50f
                m += (1f - abs(y - canalYAt(x)) / (height * 0.018f)).coerceIn(0f, 1f) * 0.35f
                return m.coerceIn(0f, 1f)
            }
            // The near-bank lush ribbon: one filled band hugging the water's near
            // edge — the same field, deepened by the river's moisture.
            run {
                val steps = 28
                for (i in 0..steps) {
                    val x = -width * 0.02f + (width * 1.04f) * i / steps
                    val yc = riverCenterY(x)
                    val y = yc + riverHalfW(x, yc) * 1.03f
                    if (i == 0) nearBankFieldP.moveTo(x, y) else nearBankFieldP.lineTo(x, y)
                }
                for (i in steps downTo 0) {
                    val x = -width * 0.02f + (width * 1.04f) * i / steps
                    val yc = riverCenterY(x)
                    nearBankFieldP.lineTo(x, yc + riverHalfW(x, yc) * 1.58f)
                }
                nearBankFieldP.close()
            }
            // The field itself: perspective rows of overlapping tone cells.
            val fieldTop = toeY + height * 0.008f // the plain below the foothill skirt
            var rowY = fieldTop
            var row = 0
            while (rowY < height && row < 90) {
                val depth = ((rowY - fieldTop) / (height - fieldTop)).coerceIn(0f, 1f)
                val rowH = height * (0.005f + 0.009f * depth)
                val cellW = width * (0.020f + 0.024f * depth)
                var x = -cellW * 0.5f + cellW * LandscapeProfile.hash01(2001, row * 7 + 1)
                var col = 0
                while (x < width + cellW) {
                    val cx = x + (LandscapeProfile.hash01(2003, row * 131 + col * 17) - 0.5f) * cellW * 0.35f
                    val cy = rowY + (LandscapeProfile.hash01(2005, row * 137 + col * 19) - 0.5f) * rowH * 0.5f
                    col++
                    x += cellW
                    if (!grassAllowed(cx, cy)) continue
                    if (cx > framing.milSlopeLeftXPx - width * 0.065f &&
                        cy < framing.milSlopeFrontYPx + height * 0.14f) continue // Mil slope front stays stone
                    if (abs(cy - canalYAt(cx)) < height * 0.0065f) continue // the canal thread stays open
                    val moist = moistureAt(cx, cy, depth)
                    val patch = 0.5f + 0.5f * LandscapeProfile.fbm(
                        cx / width * 4.6f + depth * 2.3f, 733, octaves = 3
                    )
                    // Tone: patch albedo x moisture, contrast compressed with
                    // distance (the mipmap lesson), never dipping into straw.
                    var tone = (0.18f + 0.52f * patch + 0.38f * moist).coerceIn(0f, 1f)
                    tone = 0.5f + (tone - 0.5f) * 0.60f * (1f - 0.45f * (1f - depth))
                    val bucket = (tone * (fieldBuckets.size - 1)).toInt().coerceIn(0, fieldBuckets.size - 1)
                    addOvalTo(fieldBuckets[bucket], cx, cy, cellW * 1.60f, rowH * 1.05f)
                }
                rowY += rowH * 0.85f
                row++
            }
            return VegetationGeometry(
                key = vegKey,
                treeShadows = treeShadows,
                branchBuckets = branchBuckets,
                leafDarkP = leafDarkP,
                leafAltP = leafAltP,
                leafBodyDimP = leafBodyDimP,
                leafBodyBrightP = leafBodyBrightP,
                leafRimP = leafRimP,
                leafGlowP = leafGlowP,
                silverDarkP = silverDarkP,
                silverBodyP = silverBodyP,
                silverRimP = silverRimP,
                silverGlowP = silverGlowP,
                oakDarkP = oakDarkP,
                oakAltP = oakAltP,
                oakDimP = oakDimP,
                oakBrightP = oakBrightP,
                oakRimP = oakRimP,
                oakGlowP = oakGlowP,
                oakFruitP = oakFruitP,
                paulowniaLeafDarkP = paulowniaLeafDarkP,
                paulowniaLeafP = paulowniaLeafP,
                paulowniaFlowerShadowP = paulowniaFlowerShadowP,
                paulowniaFlowerLitP = paulowniaFlowerLitP,
                twigP = twigP,
                blossomShadowP = blossomShadowP,
                blossomMidP = blossomMidP,
                blossomLitP = blossomLitP,
                petalP = petalP,
                fieldBuckets = fieldBuckets,
                nearBankFieldP = nearBankFieldP
            )
        }
        val veg = vegetationCache?.takeIf { it.key == vegKey }
            ?: buildVegetation().also { vegetationCache = it }

        // The uniform grass field goes down first in the replay — eight bucketed
        // fills for the whole valley floor, then the near-bank lush ribbon — so
        // tree shadows, trunks and crowns all stand *in* it. Cool lively greens,
        // deep in shade to sunlit at the bucket tops; at night the same buckets
        // collapse toward the dark ground tone.
        val fieldDeep = lerp(Color(0xFF0A1610), Color(0xFF38663A), day)
        val fieldLit = lerp(Color(0xFF101F14), Color(0xFF74A460), day)
        for (i in veg.fieldBuckets.indices) {
            val t = (i + 0.5f) / veg.fieldBuckets.size
            drawScope.drawPath(veg.fieldBuckets[i], lerp(fieldDeep, fieldLit, t).copy(alpha = 0.94f))
        }
        drawScope.drawPath(
            veg.nearBankFieldP,
            lerp(lerp(Color(0xFF0A1610), Color(0xFF3E7038), day), fieldLit, 0.25f).copy(alpha = 0.95f)
        )

        // Replay with live colours: ground shadows (length still tracks the Sun),
        // bark buckets dark-then-lit, foliage by tone role, the soft carpet/AO
        // patches, then the grass buckets dark -> light with the bright bits last.
        val shadowColor = Color.Black.copy(alpha = 0.16f + 0.18f * day)
        val shadowLen = (1.6f - sunAlt.coerceIn(0f, 60f) / 45f).coerceIn(0.5f, 1.8f)
        for (s in veg.treeShadows) {
            oval(
                s.cx - sunSign * s.hgt * 0.3f * shadowLen, s.by,
                s.hgt * (0.7f + 0.6f * shadowLen), s.hgt * 0.085f, shadowColor
            )
        }
        val barkDark = lerp(Color(0xFF0A0806), Color(0xFF35271A), lightLevel)
        val barkLit = lerp(Color(0xFF14100C), Color(0xFF5A4430), lightLevel)
        for (bucket in veg.branchBuckets) {
            val style = Stroke(width = bucket.widthPx, cap = StrokeCap.Round)
            drawScope.drawPath(bucket.shadowP, barkDark, style = style)
            drawScope.drawPath(bucket.litP, barkLit, style = style)
        }
        val silverLeaf = lerp(Color(0xFF0E120E), Color(0xFF8E9C84), lightLevel)
        val silverDark = silverLeaf.scaled(0.55f)
        val silverRim = lerp(silverLeaf, Color.White, 0.22f * lightLevel)
        val backlit = sunAlt in -1f..9f && day > 0.05f
        val rimColor = if (backlit) lerp(canopyLight, lighting.horizonTone, 0.45f) else canopyLight
        val silverRimCol = if (backlit) lerp(silverRim, lighting.horizonTone, 0.45f) else silverRim
        // Leaf translucency (Habel): anti-sun shell clusters are thin leaves with
        // low sun behind them — they transmit, glowing warm and saturated, instead
        // of shading like the sun-side shell. At any other hour they are just dim
        // body foliage. This is the single strongest "alive tree" cue at golden hour.
        val transmit = if (backlit) 1f else 0f
        val leafGlowCol = lerp(
            canopy.scaled(0.78f),
            lerp(canopy, Color(0xFFC2D96A), 0.55f * lightLevel).let {
                lerp(it, lighting.horizonTone, 0.30f)
            },
            transmit
        )
        val silverGlowCol = lerp(
            silverLeaf.scaled(0.78f),
            lerp(silverLeaf, Color(0xFFE4E8C8), 0.45f * lightLevel),
            transmit
        )
        drawScope.drawPath(veg.leafDarkP, canopyDark)
        drawScope.drawPath(veg.leafBodyDimP, canopy.scaled(0.92f))
        drawScope.drawPath(veg.leafBodyBrightP, canopy.scaled(1.10f))
        drawScope.drawPath(veg.leafAltP, lerp(canopy, Color(0xFF9DBE6E), 0.32f * lightLevel))
        drawScope.drawPath(veg.leafRimP, rimColor.copy(alpha = 0.55f))
        drawScope.drawPath(veg.leafGlowP, leafGlowCol.copy(alpha = 0.85f))
        drawScope.drawPath(veg.silverDarkP, silverDark)
        drawScope.drawPath(veg.silverBodyP, silverLeaf)
        drawScope.drawPath(veg.silverRimP, silverRimCol.copy(alpha = 0.55f))
        drawScope.drawPath(veg.silverGlowP, silverGlowCol.copy(alpha = 0.85f))
        // Oak: deep olive, darker-hearted than pistachio, sun-warmed shell.
        val oakBody = lerp(Color(0xFF0B120A), Color(0xFF3A5228), lightLevel)
        val oakRimBase = lerp(oakBody, Color(0xFFB9CD8E), 0.45f * lightLevel)
        val oakRimCol = if (backlit) lerp(oakRimBase, lighting.horizonTone, 0.45f) else oakRimBase
        val oakGlowCol = lerp(
            oakBody.scaled(0.78f),
            lerp(oakBody, Color(0xFFA8BF62), 0.55f * lightLevel).let {
                lerp(it, lighting.horizonTone, 0.30f)
            },
            transmit
        )
        drawScope.drawPath(veg.oakDarkP, oakBody.scaled(0.55f))
        drawScope.drawPath(veg.oakDimP, oakBody.scaled(0.85f))
        drawScope.drawPath(veg.oakBrightP, lerp(oakBody, Color(0xFF7FA05A), 0.38f * lightLevel))
        drawScope.drawPath(veg.oakAltP, lerp(oakBody, Color(0xFF8FA653), 0.34f * lightLevel))
        drawScope.drawPath(veg.oakRimP, oakRimCol.copy(alpha = 0.55f))
        drawScope.drawPath(veg.oakGlowP, oakGlowCol.copy(alpha = 0.85f))
        // Acorn cups: pale tan specks in the lit shell.
        drawScope.drawPath(
            veg.oakFruitP,
            lerp(Color(0xFF241A0E), Color(0xFFC8A468), lightLevel).copy(alpha = 0.80f)
        )
        // Twig tips fraying the crown edge, over the foliage they poke out of.
        drawScope.drawPath(
            veg.twigP, barkLit.copy(alpha = 0.55f),
            style = Stroke(width = (height * 0.0011f).coerceIn(0.8f, 1.6f), cap = StrokeCap.Round)
        )
        // Cherry blossom: mauve shadow -> pale pink -> near-white lit shell (Somei
        // Yoshino fades pink to white as it opens); petals are translucent, so at
        // golden hour the lit shell takes the sky's warmth like thin paper held to it.
        val blossomShadow = lerp(Color(0xFF171014), Color(0xFFBE8CA6), lightLevel)
        var blossomMid = lerp(Color(0xFF201318), Color(0xFFF2C4D6), lightLevel)
        var blossomLit = lerp(Color(0xFF2A1A20), Color(0xFFFFEFF5), lightLevel)
        if (backlit) {
            blossomMid = lerp(blossomMid, lighting.horizonTone, 0.18f)
            blossomLit = lerp(blossomLit, lighting.horizonTone, 0.35f)
        }
        drawScope.drawPath(veg.blossomShadowP, blossomShadow)
        drawScope.drawPath(veg.blossomMidP, blossomMid)
        drawScope.drawPath(veg.blossomLitP, blossomLit)
        drawScope.drawPath(veg.petalP, blossomMid.copy(alpha = 0.80f))
        // Paulownia: big soft green leaves, darker in the crown heart; upright
        // lavender panicles over them, warming with the sky at golden hour like
        // any thin petal held to the light.
        val paulLeaf = lerp(Color(0xFF0C140B), Color(0xFF5A7A36), lightLevel)
        drawScope.drawPath(veg.paulowniaLeafDarkP, paulLeaf.scaled(0.58f))
        drawScope.drawPath(veg.paulowniaLeafP, paulLeaf)
        var paulFlowerShadow = lerp(Color(0xFF120E18), Color(0xFF7E6FA8), lightLevel)
        var paulFlowerLit = lerp(Color(0xFF1A1424), Color(0xFFC9B6EE), lightLevel)
        if (backlit) {
            paulFlowerShadow = lerp(paulFlowerShadow, lighting.horizonTone, 0.15f)
            paulFlowerLit = lerp(paulFlowerLit, lighting.horizonTone, 0.30f)
        }
        drawScope.drawPath(veg.paulowniaFlowerShadowP, paulFlowerShadow)
        drawScope.drawPath(veg.paulowniaFlowerLitP, paulFlowerLit)

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
