package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * True while the one-shot splash is on screen (provided by MainActivity).
 *
 * This splash is only a Compose overlay on top of the already-composed Home scene, and a
 * `SurfaceView` set to z-order-on-top (the Persepolis + Mil-e Azhdaha GL relief) composites
 * above the entire window regardless of Compose order — it would punch straight through the
 * splash. Surface-backed layers therefore consult this local and stay uncomposed until the
 * splash completes.
 */
val LocalSplashActive = compositionLocalOf { false }

/**
 * Splash Screen for "RED".
 * Displays a fully code-drawn Compose Canvas paper-craft art style splash illustration
 * featuring layered cut-paper mountains, soft clouds, warm glowing sun, starry cosmos, and 'RED' typography.
 */
@Composable
fun PremiumSplashScreen(
    onSplashComplete: () -> Unit
) {
    val splashAlpha = remember { Animatable(0f) }
    val splashScale = remember { Animatable(1.05f) }
    val splashContainerAlpha = remember { Animatable(1f) }

    // Subtle breathing animation for sun and clouds
    val infiniteTransition = rememberInfiniteTransition(label = "splash_motion")
    val cloudDrift by infiniteTransition.animateFloat(
        initialValue = -15f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cloud_drift"
    )

    LaunchedEffect(Unit) {
        // Entrance animation
        launch {
            splashAlpha.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 700, easing = LinearOutSlowInEasing)
            )
        }
        launch {
            splashScale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
            )
        }

        // Hold display
        delay(2200)

        // Smooth exit fade out
        splashContainerAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 500, easing = LinearOutSlowInEasing)
        )

        onSplashComplete()
    }

    if (splashContainerAlpha.value > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = splashContainerAlpha.value }
                .background(Color(0xFF0D0B14))
                .testTag("premium_splash_screen"),
            contentAlignment = Alignment.Center
        ) {
            // Paper-craft artwork rendered directly via Compose Canvas
            PaperCraftSplashArtwork(
                cloudOffset = cloudDrift,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = splashAlpha.value
                        scaleX = splashScale.value
                        scaleY = splashScale.value
                    }
            )

            // Stylized 'RED' branding overlay
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-30).dp)
                    .graphicsLayer {
                        alpha = splashAlpha.value
                        scaleX = splashScale.value
                        scaleY = splashScale.value
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Main 'ZIG' Title
                Text(
                    text = "ZIG",
                    color = Color(0xFFFFF1F2),
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 16.sp,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier.padding(start = 16.dp) // Optical centering for wide tracking
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Subtitle
                Box(
                    modifier = Modifier
                        .background(
                            color = Color(0x661A0B2E),
                            shape = squircleShape(12.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "ASTRONOMY & ORBITAL TRACKER",
                        color = Color(0xFFFFD4D8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                }
            }

            // Bottom decorative credit
            Text(
                text = "NIGHT SKY EXPLORATION",
                color = Color(0x88FFA8B5),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .graphicsLayer { alpha = splashAlpha.value * 0.8f }
            )
        }
    }
}

/**
 * Draws layered paper-cut mountains, glowing celestial sun, stars, and atmospheric clouds
 * with depth shading mimicking hand-cut paper crafts.
 */
@Composable
private fun PaperCraftSplashArtwork(
    cloudOffset: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // 1. Twilight Cosmos Sky Gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF090814),
                    Color(0xFF160E29),
                    Color(0xFF2E1035),
                    Color(0xFF4C153B),
                    Color(0xFF6B1D3D)
                ),
                startY = 0f,
                endY = h
            ),
            size = size
        )

        // 2. Cosmic Stars & Stardust
        drawStars(w, h)

        // 3. Glowing Celestial Sun with Layered Corona
        val sunCenterX = w * 0.5f
        val sunCenterY = h * 0.38f
        val sunRadius = w * 0.22f

        // Outer sun glow rings (paper-cut concentric layers)
        drawCircle(
            color = Color(0x18FF5E62),
            radius = sunRadius * 1.85f,
            center = Offset(sunCenterX, sunCenterY)
        )
        drawCircle(
            color = Color(0x28FF6B6B),
            radius = sunRadius * 1.50f,
            center = Offset(sunCenterX, sunCenterY)
        )
        drawCircle(
            color = Color(0x40FFA07A),
            radius = sunRadius * 1.22f,
            center = Offset(sunCenterX, sunCenterY)
        )
        // Core radiant sun disk
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFEEB2),
                    Color(0xFFFF9472),
                    Color(0xFFE23E57)
                ),
                center = Offset(sunCenterX, sunCenterY),
                radius = sunRadius
            ),
            radius = sunRadius,
            center = Offset(sunCenterX, sunCenterY)
        )

        // 4. Background Paper Clouds (Drifting softly)
        drawPaperCloud(
            cx = w * 0.22f + cloudOffset,
            cy = h * 0.26f,
            scale = w * 0.24f,
            color = Color(0x25FFCCD5)
        )
        drawPaperCloud(
            cx = w * 0.82f - cloudOffset * 0.8f,
            cy = h * 0.32f,
            scale = w * 0.28f,
            color = Color(0x30FFAAB8)
        )

        // 5. Layer 1: Distant Paper Mountains (Deep Plum Silhouette)
        val path1 = Path().apply {
            moveTo(0f, h * 0.52f)
            cubicTo(w * 0.2f, h * 0.44f, w * 0.35f, h * 0.56f, w * 0.52f, h * 0.46f)
            cubicTo(w * 0.68f, h * 0.38f, w * 0.85f, h * 0.54f, w, h * 0.48f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = path1,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF380E2B), Color(0xFF22081B)),
                startY = h * 0.4f,
                endY = h
            )
        )

        // 6. Layer 2: Mid-Distant Ridge (Wine / Maroon Sharp Paper Peaks)
        val path2 = Path().apply {
            moveTo(0f, h * 0.60f)
            lineTo(w * 0.18f, h * 0.50f)
            lineTo(w * 0.38f, h * 0.63f)
            lineTo(w * 0.62f, h * 0.48f)
            lineTo(w * 0.82f, h * 0.61f)
            lineTo(w, h * 0.54f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = path2,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF5E1338), Color(0xFF3D0C24)),
                startY = h * 0.48f,
                endY = h
            )
        )

        // 7. Layer 3: Mid-Ground Mountain Range (Rich Crimson & Ruby)
        val path3 = Path().apply {
            moveTo(0f, h * 0.67f)
            cubicTo(w * 0.15f, h * 0.62f, w * 0.28f, h * 0.55f, w * 0.44f, h * 0.58f)
            cubicTo(w * 0.58f, h * 0.60f, w * 0.72f, h * 0.52f, w * 0.88f, h * 0.64f)
            lineTo(w, h * 0.62f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = path3,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF8C1D40), Color(0xFF5A1028)),
                startY = h * 0.52f,
                endY = h
            )
        )

        // 8. Mid-Layer Fluffy Cloud
        drawPaperCloud(
            cx = w * 0.48f + cloudOffset * 0.5f,
            cy = h * 0.62f,
            scale = w * 0.36f,
            color = Color(0x35FFB3C1)
        )

        // 9. Layer 4: Vivid Coral & Scarlet Front Ridge
        val path4 = Path().apply {
            moveTo(0f, h * 0.76f)
            lineTo(w * 0.26f, h * 0.66f)
            lineTo(w * 0.54f, h * 0.78f)
            lineTo(w * 0.78f, h * 0.68f)
            lineTo(w, h * 0.77f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = path4,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFC92A4C), Color(0xFF7A142D)),
                startY = h * 0.66f,
                endY = h
            )
        )

        // 10. Layer 5: Dynamic Warm Crimson Paper Dunes (Foreground)
        val path5 = Path().apply {
            moveTo(0f, h * 0.85f)
            cubicTo(w * 0.30f, h * 0.76f, w * 0.65f, h * 0.90f, w, h * 0.81f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = path5,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFE63956), Color(0xFF85152F)),
                startY = h * 0.76f,
                endY = h
            )
        )

        // 11. Layer 6: Deep Foreground Paper Cut Hill
        val path6 = Path().apply {
            moveTo(0f, h * 0.92f)
            cubicTo(w * 0.40f, h * 0.88f, w * 0.70f, h * 0.95f, w, h * 0.90f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(
            path = path6,
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFF2E0917), Color(0xFF14030A)),
                startY = h * 0.88f,
                endY = h
            )
        )
    }
}

/**
 * Draws soft paper-cut cloud shapes made of overlapping circles and pill bases.
 */
private fun DrawScope.drawPaperCloud(cx: Float, cy: Float, scale: Float, color: Color) {
    val r1 = scale * 0.38f
    val r2 = scale * 0.50f
    val r3 = scale * 0.34f

    drawCircle(color = color, radius = r1, center = Offset(cx - scale * 0.35f, cy + scale * 0.05f))
    drawCircle(color = color, radius = r2, center = Offset(cx, cy - scale * 0.10f))
    drawCircle(color = color, radius = r3, center = Offset(cx + scale * 0.35f, cy + scale * 0.08f))

    // Flat base of paper cloud
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - scale * 0.55f, cy),
        size = Size(scale * 1.10f, scale * 0.32f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(scale * 0.16f, scale * 0.16f)
    )
}

/**
 * Draws a deterministic field of stars and twinkle points across the upper night sky.
 */
private fun DrawScope.drawStars(w: Float, h: Float) {
    val starCoords = listOf(
        Pair(0.12f, 0.08f), Pair(0.28f, 0.05f), Pair(0.45f, 0.09f), Pair(0.68f, 0.04f),
        Pair(0.85f, 0.07f), Pair(0.92f, 0.14f), Pair(0.06f, 0.18f), Pair(0.22f, 0.15f),
        Pair(0.38f, 0.20f), Pair(0.62f, 0.16f), Pair(0.78f, 0.22f), Pair(0.88f, 0.28f),
        Pair(0.15f, 0.32f), Pair(0.32f, 0.28f), Pair(0.55f, 0.24f), Pair(0.72f, 0.10f),
        Pair(0.82f, 0.03f), Pair(0.04f, 0.38f), Pair(0.95f, 0.22f), Pair(0.48f, 0.03f)
    )

    starCoords.forEachIndexed { index, (rx, ry) ->
        val x = rx * w
        val y = ry * h
        val radius = if (index % 4 == 0) 2.2f else if (index % 2 == 0) 1.5f else 1.0f
        val alpha = if (index % 3 == 0) 0.9f else 0.6f

        drawCircle(
            color = Color(0xFFFFF0F5).copy(alpha = alpha),
            radius = radius,
            center = Offset(x, y)
        )

        // Subtle cross glint for larger stars
        if (index % 5 == 0) {
            drawLine(
                color = Color(0x80FFFFFF),
                start = Offset(x - 4f, y),
                end = Offset(x + 4f, y),
                strokeWidth = 1f
            )
            drawLine(
                color = Color(0x80FFFFFF),
                start = Offset(x, y - 4f),
                end = Offset(x, y + 4f),
                strokeWidth = 1f
            )
        }
    }
}


