package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.PI
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

    fun draw(
        drawScope: DrawScope,
        composition: SceneComposition,
        theme: SkyCanvasTheme,
        lighting: LightingState
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
