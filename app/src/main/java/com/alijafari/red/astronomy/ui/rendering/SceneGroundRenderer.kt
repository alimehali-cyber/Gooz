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
import kotlin.math.pow
import kotlin.math.sin

/**
 * The ground half of the full-bleed Home scene: distant ridges, the mist shelf on the horizon, the solid
 * ground band and the grass line the observer stands on.
 *
 * Everything here is generated from the scene composition and the theme palette, so it follows the Sun
 * and the active theme instead of being a fixed illustration. It is cheap: three filled paths, two
 * gradient rectangles and a fixed number of grass strokes, and none of it changes while the scene idles.
 */
object SceneGroundRenderer {

    /**
     * @param persepolis resolved combined-landscape framing for this exact canvas, or null when
     * the ruins are not part of the scene. When set, a footprint-local worn-earth apron, banked
     * side transition, contact shadows and debris are painted from both the Persepolis platform
     * bounds and the Mil-e Azhdaha slope's projected bounds, so the GL stone sits *in* the
     * ground band instead of ending on a hard line above the grass. Everything else is unchanged:
     * the ridges and the mist shelf stay behind the ruins (they are their distance backdrop),
     * and the ground below the toe remains the foreground field the framing intends.
     */
    fun draw(
        drawScope: DrawScope,
        composition: SceneComposition,
        theme: SkyCanvasTheme,
        lighting: LightingState,
        persepolis: PersepolisFraming.Result? = null
    ) {
        val palette = scenePalette(theme, lighting)
        val width = composition.widthPx
        val height = composition.heightPx
        val horizonY = composition.horizonY
        val papercraft = theme == SkyCanvasTheme.PAPERCRAFT_DIORAMA

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

        // 2. Mist shelf: the band that makes the ridge read as distance rather than as a cut-out.
        val mistTop = horizonY - height * 0.055f
        val mistBottom = horizonY + height * 0.045f
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.55f to palette.haze.copy(alpha = 0.30f),
                1.0f to Color.Transparent,
                startY = mistTop,
                endY = mistBottom
            ),
            topLeft = Offset(0f, mistTop),
            size = androidx.compose.ui.geometry.Size(width, mistBottom - mistTop)
        )

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

        // 7. A soft vignette at the foot of the scene: depth on any theme background, and it stops the
        //    ground from ending on a flat line where the feed begins.
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

    /**
     * The earth the distant GL ruins stand in, painted only around their own footprints.
     *
     * At the half-scale, left-hand placement neither monument runs off both edges, so a
     * full-width grounding band would read as a stripe. Instead this paints one small worn
     * archaeological site: irregular drifts of sand over the field in front of the Persepolis
     * toe, a bank of earth spilling from the tower slope's open right side, broken contact
     * shadows under both the forecourt and the nearer slope front, low drift hummocks, and fine
     * stone-and-sand flecks. The two contact treatments are separate because the slope's front
     * step is closer to the viewer and projects below the Persepolis toe. Every edge is either
     * irregular, gradient-feathered, or both, and the whole patch feathers out to the right
     * into the ordinary ground.
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
        val combinedRight = maxOf(platformRight, slopeRight)
        val footprintWidth = (combinedRight - platformLeft).coerceAtLeast(1f)
        val slopeWidth = (slopeRight - slopeLeft).coerceAtLeast(1f)
        // The worn-earth patch starts a little before the stone (often off-canvas left) and tails
        // off well past the slope's open right side, so the clearing has no vertical boundary.
        val apronLeft = (platformLeft - width * 0.030f).coerceAtLeast(-width * 0.02f)
        val apronRight = (combinedRight + width * 0.145f).coerceAtMost(width * 1.02f)

        val sand = lerp(Color(0xFF292216), Color(0xFFBEA06E), day)
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

        // 1. Irregular worn-earth apron: smoothly undulating top and bottom edges, strongest at the
        //    toe and dissolving downward into the field. The right-hand end tapers almost to a point
        //    before the horizontal gradient reaches transparency, so there is no vertical cut edge.
        val apronTop = toeY - height * 0.032f
        val apronBottom = (toeY + height * 0.095f).coerceAtMost(height)
        val apron = Path().apply {
            val segments = 36
            fun tailTaper(p: Float): Float {
                val t = ((p - 0.76f) / 0.24f).coerceIn(0f, 1f)
                return t * t * (3f - 2f * t)
            }
            moveTo(apronLeft, toeY - height * 0.020f)
            for (i in 1..segments) {
                val p = i / segments.toFloat()
                val x = apronLeft + (apronRight - apronLeft) * p
                val taper = tailTaper(p)
                val topDepth = 0.020f +
                    0.004f * sin(p * PI.toFloat() * 4f + 0.7f) +
                    0.002f * sin(p * PI.toFloat() * 9f + 2.0f)
                val y = toeY - height * topDepth * (1f - taper) + height * 0.012f * taper
                lineTo(x, y)
            }
            for (i in segments downTo 0) {
                val p = i / segments.toFloat()
                val x = apronLeft + (apronRight - apronLeft) * p
                val taper = tailTaper(p)
                val bottomDepth = 0.064f +
                    0.010f * sin(p * PI.toFloat() * 4f + 1.3f) +
                    0.005f * sin(p * PI.toFloat() * 7f + 0.4f)
                val depth = bottomDepth * (1f - taper) + 0.020f * taper
                lineTo(x, (toeY + height * depth).coerceAtMost(height))
            }
            close()
        }
        drawScope.drawPath(
            path = apron,
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.34f to earth.copy(alpha = 0.09f + 0.18f * day),
                0.47f to sand.copy(alpha = 0.11f + 0.21f * day),
                0.66f to Color.Transparent,
                1.0f to Color.Transparent,
                startY = apronTop,
                endY = apronBottom
            )
        )
        // A faint second pass feathered horizontally, so the tapered tail melts into the field.
        drawScope.drawPath(
            path = apron,
            brush = Brush.horizontalGradient(
                0.0f to sand.copy(alpha = 0.025f + 0.035f * day),
                0.68f to sand.copy(alpha = 0.018f + 0.028f * day),
                1.0f to Color.Transparent,
                startX = apronLeft,
                endX = apronRight
            )
        )

        // 2. Earth banked against the slope's open right side: one soft spill of soil toward the
        //    viewer, spanning from under the upper limestone sheets to the apron's tail. Most of
        //    its left side is hidden by the GL slope; the visible right side fades into the field.
        val bankLeft = slopeRight - slopeWidth * 0.42f
        val bankRight = slopeRight + width * 0.145f
        drawSoftDrift(
            cx = (bankLeft + bankRight) / 2f,
            cy = ((toeY + slopeFrontY) / 2f + height * 0.024f).coerceAtMost(height),
            driftWidth = bankRight - bankLeft,
            driftHeight = height * 0.064f,
            outerColor = earth,
            innerColor = sand,
            peakAlpha = 0.17f + 0.16f * day,
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
            innerColor = sand,
            peakAlpha = 0.10f + 0.13f * day,
            layers = 14
        )

        // 3. Broken contact shadow directly in front of the combined stone footprint. The
        //    Persepolis forecourt and tower slope overlap in projection, so one continuous,
        //    irregular shadow prevents a lit seam where the two masses meet.
        val shadowLeft = platformLeft.coerceAtLeast(-width * 0.01f)
        val shadowRight = (combinedRight + width * 0.018f).coerceAtMost(width)
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

        // 4. Low drift hummocks: soft elongated mounds scattered through the apron, denser near
        //    the toe and thinning toward the viewer — light on sand, not separate objects.
        for (i in 0 until 7) {
            val r1 = pseudoRandom(i * 17_171 + 101)
            val r2 = pseudoRandom(i * 23_777 + 57)
            val r3 = pseudoRandom(i * 31_337 + 5)
            drawSoftDrift(
                cx = apronLeft + (apronRight - apronLeft) * (0.08f + 0.84f * ((i + r1 * 0.8f) / 7f)),
                cy = toeY + height * (0.012f + r2 * 0.055f),
                driftWidth = footprintWidth * (0.13f + r2 * 0.16f),
                driftHeight = height * (0.010f + r3 * 0.018f),
                outerColor = earth,
                innerColor = sand,
                peakAlpha = (0.05f + 0.07f * day) * (0.6f + 0.4f * r3),
                layers = 10
            )
        }

        // 5. Fine debris: short light/dark flecks (fallen chips, grit) concentrated at the toe and
        //    dissipating down the apron. Deterministic, so the site does not shimmer between ticks.
        for (i in 0 until 44) {
            val r1 = pseudoRandom(i * 41_003 + 211)
            val r2 = pseudoRandom(i * 13_981 + 37)
            val r3 = pseudoRandom(i * 29_297 + 83)
            val x = apronLeft + (apronRight - apronLeft) * ((i + r1 * 0.9f) / 44f)
            val y = (toeY - height * 0.004f + height * 0.082f * r2 * r2).coerceAtMost(height)
            val len = (2f + r3 * 7f) * (height / 900f).coerceIn(0.7f, 1.8f)
            val fleck = if (r3 > 0.45f) sand else darkEarth
            drawScope.drawLine(
                color = fleck.copy(alpha = (0.10f + 0.16f * day) * (0.5f + 0.5f * r1)),
                start = Offset(x, y),
                end = Offset(x + len, y + (r1 - 0.5f) * len * 0.35f),
                strokeWidth = 1.1f + r3 * 1.5f,
                cap = StrokeCap.Round
            )
        }
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
