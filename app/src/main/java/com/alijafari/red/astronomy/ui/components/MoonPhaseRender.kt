package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.MoonEngine
import kotlin.math.abs

/**
 * The photographic Moon render: the full-Moon plate, the live phase terminator and limb darkening.
 *
 * This is the render the retired Moon screen used to own, lifted out unchanged so the Home screen can
 * host it inside a card. It is deliberately pure presentation:
 *  - no date navigator, no phase caption, no ephemeris text — the caller owns any chrome around it;
 *  - no gesture handling, so it can sit inside a card without stealing the parent's input.
 *
 * The terminator orientation comes from the Sun/Moon geometry through
 * [CoordinateEngine.calculateMoonLimbScreenAngleDeg], and the disc is flipped for southern latitudes,
 * exactly as it was on the Moon screen. The very slow ±1.5° wobble is the same idle animation.
 */
@Composable
fun MoonPhaseRender(
    moonData: MoonEngine.MoonData,
    sunHoriz: CoordinateEngine.Horizontal,
    latitude: Double,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "MoonRotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = -1.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Rotation"
    )

    val limbScreenAngleDeg = remember(moonData.azimuthDeg, moonData.altitudeDeg, sunHoriz.azimuthDeg, sunHoriz.altitudeDeg) {
        CoordinateEngine.calculateMoonLimbScreenAngleDeg(
            moonAzimuthDeg = moonData.azimuthDeg,
            moonAltitudeDeg = moonData.altitudeDeg,
            sunAzimuthDeg = sunHoriz.azimuthDeg,
            sunAltitudeDeg = sunHoriz.altitudeDeg
        ).toFloat()
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Realistic Moon image + Phase Canvas Shader
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .graphicsLayer {
                    rotationZ = if (latitude < 0) 180f + rotationAngle else rotationAngle
                }
        ) {
            // High-Res Photographic Moon Asset
            Image(
                painter = painterResource(id = R.drawable.img_full_moon_photo_1785673146290),
                contentDescription = moonData.phaseNameFa,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Dynamic Lunar Phase Shadow Overlay
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)

                val ill = moonData.illuminationPercent / 100.0

                if (ill < 0.98) {
                    val targetShadowAlpha = 0.965f
                    val numSteps = 16
                    val stepAlpha = targetShadowAlpha / numSteps
                    val feather = radius * 0.08f

                    for (step in 0 until numSteps) {
                        val t = (step.toFloat() / (numSteps - 1) - 0.5f) * 2f
                        val offset = t * feather

                        val shadowPath = Path()
                        shadowPath.addArc(
                            Rect(center.x - radius, center.y - radius, center.x + radius, center.y + radius),
                            90f,
                            180f
                        )

                        val k = (2.0 * ill - 1.0).toFloat()
                        val stepInnerWidth = (abs(k) * radius + offset).coerceAtLeast(0f)
                        val innerRect = Rect(center.x - stepInnerWidth, center.y - radius, center.x + stepInnerWidth, center.y + radius)

                        val innerSweep = if (k >= 0) -180f else 180f
                        shadowPath.arcTo(innerRect, 270f, innerSweep, false)
                        shadowPath.close()

                        rotate(limbScreenAngleDeg, center) {
                            drawPath(
                                path = shadowPath,
                                color = Color(0xFF030307).copy(alpha = stepAlpha)
                            )
                        }
                    }
                }

                // Ambient Edge Limb Darkening
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF05050D).copy(alpha = 0.38f)
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
    }
}
