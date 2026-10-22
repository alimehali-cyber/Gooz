package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import kotlin.math.PI
import kotlin.math.sin

/** What the observer is doing. Chosen from the real Sun altitude, never from weather. */
internal enum class ObserverPose {
    /** Bright daylight: hand up to shade the eyes. */
    SHADING_EYES,

    /** Sunset, sunrise and twilight: looking down at a phone. */
    PHONE,

    /** Astronomical night: arm raised, pointing at the sky. */
    LOOKING_UP
}

/**
 * The observer standing on the grass line of the full-bleed Home scene.
 *
 * This is ZIG's answer to the figure in Samsung's weather screen: one small, calm silhouette that gives
 * the sky a human scale. It is drawn entirely from vector paths, its clothing comes from the theme
 * palette, and its pose comes from the actual Sun altitude — so it is scenery, not decoration that
 * pretends to know the weather.
 *
 * The whole figure is generated per frame at roughly a dozen draw calls, so it can breathe and sway while
 * the cloud veils drift.
 */
object ObserverRenderer {

    /** Idle cycle: a slow breath, about five seconds long. */
    private const val BREATH_SECONDS = 5.2

    /**
     * Period of the slow weight shift, in seconds. Deliberately not a multiple of [BREATH_SECONDS], so the
     * two motions never re-align into a visible loop.
     */
    private const val SWAY_SECONDS = 17.0

    fun draw(
        drawScope: DrawScope,
        composition: SceneComposition,
        theme: SkyCanvasTheme,
        lighting: LightingState,
        frameTimeMs: Long
    ) {
        val palette = figurePalette(theme, lighting)
        val s = composition.figureHeightPx
        if (s < 8f) return

        // Double seconds, wrapped to one day: wall-clock milliseconds are far too large for a Float phase.
        val t = (frameTimeMs % 86_400_000L) / 1000.0
        val breath = sin(2.0 * PI * t / BREATH_SECONDS).toFloat()
        val sway = sin(2.0 * PI * t / SWAY_SECONDS).toFloat()
        val facingLeft = composition.figureCenterX > composition.widthPx * 0.5f
        val facing = if (facingLeft) -1f else 1f
        val pose = poseFor(lighting.sunAltitudeDeg)

        val feet = Offset(
            composition.figureCenterX + sway * s * 0.004f,
            composition.figureFeetY - breath * s * 0.004f
        )

        drawContactShadow(drawScope, feet, s, lighting)

        drawScope.withTransform({
            translate(left = feet.x, top = feet.y)
            rotate(degrees = sway * 0.35f, pivot = Offset(0f, 0f))
            scale(scaleX = 1f + breath * 0.004f, scaleY = 1f + breath * 0.004f, pivot = Offset(0f, 0f))
        }) {
            // Back arm first, so the front arm reads on top of the torso.
            drawArms(drawScope, s, facing, pose, palette, front = false)

            drawLegs(drawScope, s, palette)
            drawTorso(drawScope, s, palette)
            drawHead(drawScope, s, facing, palette, pose, breath)

            drawArms(drawScope, s, facing, pose, palette, front = true)

            if (pose == ObserverPose.PHONE) {
                drawPhone(drawScope, s, facing, lighting)
            }
        }
    }

    /** Pose from the Sun altitude. Daylight, twilight and night each get one readable attitude. */
    internal fun poseFor(sunAltitudeDeg: Double): ObserverPose = when {
        sunAltitudeDeg > 3.0 -> ObserverPose.SHADING_EYES
        sunAltitudeDeg > -7.0 -> ObserverPose.PHONE
        else -> ObserverPose.LOOKING_UP
    }

    private fun drawContactShadow(drawScope: DrawScope, feet: Offset, s: Float, lighting: LightingState) {
        val strength = 0.10f + 0.22f * lighting.ambientBrightness.coerceIn(0f, 1f)
        drawScope.drawOval(
            color = Color.Black.copy(alpha = strength * 0.55f),
            topLeft = Offset(feet.x - s * 0.30f, feet.y - s * 0.020f),
            size = Size(s * 0.60f, s * 0.055f)
        )
        drawScope.drawOval(
            color = Color.Black.copy(alpha = strength),
            topLeft = Offset(feet.x - s * 0.17f, feet.y - s * 0.014f),
            size = Size(s * 0.34f, s * 0.032f)
        )
    }

    private fun drawLegs(drawScope: DrawScope, s: Float, palette: FigurePalette) {
        val hipY = -0.455f * s
        val ankleY = -0.030f * s
        for (side in listOf(-1f, 1f)) {
            drawScope.drawLine(
                color = palette.trousers,
                start = Offset(side * 0.048f * s, hipY),
                end = Offset(side * 0.058f * s, ankleY),
                strokeWidth = 0.074f * s,
                cap = StrokeCap.Round
            )
            drawScope.drawOval(
                color = palette.shoe,
                topLeft = Offset(side * 0.058f * s - 0.062f * s, ankleY - 0.020f * s),
                size = Size(0.124f * s, 0.042f * s)
            )
        }
    }

    private fun drawTorso(drawScope: DrawScope, s: Float, palette: FigurePalette) {
        val hipY = -0.455f * s
        val shoulderY = -0.762f * s
        val hipHalf = 0.080f * s
        val shoulderHalf = 0.104f * s

        val torso = Path().apply {
            moveTo(-hipHalf, hipY)
            quadraticTo(-hipHalf - 0.014f * s, -0.62f * s, -shoulderHalf, shoulderY)
            quadraticTo(0f, shoulderY - 0.030f * s, shoulderHalf, shoulderY)
            quadraticTo(hipHalf + 0.014f * s, -0.62f * s, hipHalf, hipY)
            quadraticTo(0f, hipY + 0.020f * s, -hipHalf, hipY)
            close()
        }

        drawScope.drawPath(
            path = torso,
            brush = Brush.verticalGradient(
                0.0f to lerpColor(palette.jacket, Color.White, 0.16f),
                0.55f to palette.jacket,
                1.0f to lerpColor(palette.jacket, Color.Black, 0.22f),
                startY = shoulderY - 0.03f * s,
                endY = hipY
            )
        )

        // Collar: one line is enough to read as clothing at this size.
        drawScope.drawLine(
            color = lerpColor(palette.jacket, Color.Black, 0.25f),
            start = Offset(-0.042f * s, shoulderY - 0.006f * s),
            end = Offset(0.042f * s, shoulderY - 0.006f * s),
            strokeWidth = 0.016f * s,
            cap = StrokeCap.Round
        )

        // Rim light along the upper silhouette, the way sky light catches a shoulder.
        drawScope.drawPath(
            path = torso,
            color = palette.rim.copy(alpha = 0.22f),
            style = Stroke(width = 1.3f, join = StrokeJoin.Round)
        )
    }

    private fun drawHead(
        drawScope: DrawScope,
        s: Float,
        facing: Float,
        palette: FigurePalette,
        pose: ObserverPose,
        breath: Float
    ) {
        val neckY = -0.800f * s
        val headCenter = Offset(0f, -0.888f * s)
        val headRadius = 0.077f * s
        val tilt = when (pose) {
            ObserverPose.LOOKING_UP -> facing * -7f + breath * 1.2f
            ObserverPose.PHONE -> facing * 6f
            ObserverPose.SHADING_EYES -> facing * -2f
        }

        drawScope.drawLine(
            color = palette.skin,
            start = Offset(0f, neckY + 0.012f * s),
            end = Offset(0f, headCenter.y + 0.03f * s),
            strokeWidth = 0.056f * s,
            cap = StrokeCap.Round
        )

        drawScope.withTransform({ rotate(degrees = tilt, pivot = Offset(0f, neckY)) }) {
            drawScope.drawCircle(color = palette.skin, radius = headRadius, center = headCenter)
            drawScope.drawCircle(
                color = palette.rim.copy(alpha = 0.18f),
                radius = headRadius,
                center = headCenter,
                style = Stroke(width = 1.2f)
            )

            // Cap: a domed crown plus a brim on the facing side.
            drawScope.drawArc(
                color = palette.cap,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(headCenter.x - headRadius * 1.06f, headCenter.y - headRadius * 1.16f),
                size = Size(headRadius * 2.12f, headRadius * 2.12f)
            )
            drawScope.drawOval(
                color = palette.cap,
                topLeft = Offset(
                    headCenter.x + (if (facing < 0f) -headRadius * 2.0f else -headRadius * 0.15f),
                    headCenter.y - headRadius * 0.34f
                ),
                size = Size(headRadius * 2.15f, headRadius * 0.42f)
            )
        }
    }

    private fun drawArms(
        drawScope: DrawScope,
        s: Float,
        facing: Float,
        pose: ObserverPose,
        palette: FigurePalette,
        front: Boolean
    ) {
        // The front arm hangs on the side the figure faces; the back arm on the other side.
        val shoulder = Offset((if (front) 0.092f else -0.092f) * s * facing, -0.735f * s)
        val handColor = palette.skin
        val armWidth = 0.052f * s

        fun arm(elbow: Offset, hand: Offset) {
            val path = Path().apply {
                moveTo(shoulder.x, shoulder.y)
                quadraticTo(elbow.x, elbow.y, hand.x, hand.y)
            }
            drawScope.drawPath(
                path = path,
                color = palette.jacket,
                style = Stroke(width = armWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawScope.drawCircle(color = handColor, radius = armWidth * 0.52f, center = hand)
        }

        fun restArm() {
            val path = Path().apply {
                moveTo(shoulder.x, shoulder.y)
                quadraticTo(
                    shoulder.x + 0.030f * s * facing,
                    -0.60f * s,
                    shoulder.x + 0.018f * s * facing,
                    -0.475f * s
                )
            }
            drawScope.drawPath(
                path = path,
                color = lerpColor(palette.jacket, Color.Black, 0.08f),
                style = Stroke(width = armWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawScope.drawCircle(
                color = handColor,
                radius = armWidth * 0.50f,
                center = Offset(shoulder.x + 0.018f * s * facing, -0.470f * s)
            )
        }

        when (pose) {
            ObserverPose.LOOKING_UP -> if (front) {
                arm(
                    elbow = Offset(shoulder.x + 0.070f * s * facing, -0.870f * s),
                    hand = Offset(shoulder.x + 0.120f * s * facing, -1.030f * s)
                )
            } else {
                restArm()
            }

            ObserverPose.PHONE -> if (front) {
                arm(
                    elbow = Offset(shoulder.x + 0.040f * s * facing, -0.620f * s),
                    hand = Offset(shoulder.x - 0.050f * s * facing, -0.628f * s)
                )
            } else {
                arm(
                    elbow = Offset(shoulder.x - 0.038f * s * facing, -0.600f * s),
                    hand = Offset(shoulder.x - 0.064f * s * facing, -0.612f * s)
                )
            }

            ObserverPose.SHADING_EYES -> if (front) {
                arm(
                    elbow = Offset(shoulder.x + 0.062f * s * facing, -0.800f * s),
                    hand = Offset(shoulder.x + 0.028f * s * facing, -0.878f * s)
                )
            } else {
                restArm()
            }
        }
    }

    private fun drawPhone(drawScope: DrawScope, s: Float, facing: Float, lighting: LightingState) {
        val phoneWidth = 0.062f * s
        val phoneHeight = 0.036f * s
        val center = Offset(0.006f * s * facing, -0.640f * s)
        val topLeft = Offset(center.x - phoneWidth / 2f, center.y - phoneHeight / 2f)

        drawScope.drawRoundRect(
            color = Color(0xFF0B0F16),
            topLeft = topLeft,
            size = Size(phoneWidth, phoneHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(phoneHeight * 0.22f)
        )
        val glow = 0.35f + 0.45f * (1f - lighting.ambientBrightness.coerceIn(0f, 1f))
        drawScope.drawRect(
            color = Color(0xFFBFDBFE).copy(alpha = glow),
            topLeft = Offset(topLeft.x + phoneWidth * 0.12f, topLeft.y + phoneHeight * 0.12f),
            size = Size(phoneWidth * 0.76f, phoneHeight * 0.76f)
        )
    }

    private fun lerpColor(from: Color, to: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = from.red + (to.red - from.red) * f,
            green = from.green + (to.green - from.green) * f,
            blue = from.blue + (to.blue - from.blue) * f,
            alpha = from.alpha + (to.alpha - from.alpha) * f
        )
    }
}
