package com.alijafari.red.astronomy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.astro_engine.EclipseEngine
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A photorealistic eclipse, drawn rather than photographed.
 *
 * The app has one lunar photograph and no solar one, so the two families are built differently and
 * for a reason:
 *
 *  - **Lunar** eclipses are the real Moon photograph, tinted the way the Earth's umbra actually tints
 *    it (a copper disc whose limb stays brightest, because the limb samples the shallower part of the
 *    umbra). The maria that make the Moon recognisable survive the tint because the shade is applied
 *    with [BlendMode.Multiply] — it darkens the photograph instead of painting over it, which is what
 *    a drawn-on gradient would do.
 *  - **Solar** eclipses are drawn, because the thing being looked at is not a surface but light: the
 *    corona, the chromosphere ring and the diamond ring are all emission, and no disc fill can fake
 *    them. The corona here is a limb-brightened glow plus ~44 deterministic streamers of varying
 *    length and width, which is the detail that separates a real eclipse photograph from a black
 *    circle with a blur.
 *
 * Everything is deterministic ([jitter] is a hash, not a `Random`), so a recomposition cannot make the
 * corona flicker, and the drawing is only invalidated when the eclipse itself changes.
 *
 * [magnitude] only matters for the partial cases, where it sets how far the two discs have slid past
 * each other. It is the *event's* magnitude (fraction of the diameter covered), which is the right
 * quantity for a picture; local obscuration would dim the wrong thing.
 */
@Composable
fun EclipseRender(
    type: EclipseEngine.EclipseType,
    magnitude: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    // Held in a local so the semantics lambda cannot capture the receiver's own property name.
    val described = contentDescription
    val semanticsModifier = if (described != null) {
        modifier.semantics { this.contentDescription = described }
    } else {
        modifier
    }

    if (type.isSolarFamily) {
        Canvas(modifier = semanticsModifier) {
            drawSolarEclipse(type = type, magnitude = magnitude)
        }
    } else {
        LunarEclipseRender(type = type, magnitude = magnitude, modifier = semanticsModifier)
    }
}

private val EclipseEngine.EclipseType.isSolarFamily: Boolean
    get() = when (this) {
        EclipseEngine.EclipseType.TOTAL_SOLAR,
        EclipseEngine.EclipseType.ANNULAR_SOLAR,
        EclipseEngine.EclipseType.HYBRID_SOLAR,
        EclipseEngine.EclipseType.PARTIAL_SOLAR,
        EclipseEngine.EclipseType.NONE_SOLAR -> true

        else -> false
    }

// ---------------------------------------------------------------------------------------------
// Solar
// ---------------------------------------------------------------------------------------------

private val CoronaWhite = Color(0xFFF4F7FF)
private val Chromosphere = Color(0xFFFF5A3C)
private val PhotosphereCore = Color(0xFFFFFDF4)
private val PhotosphereMid = Color(0xFFFFF2C6)
private val PhotosphereEdge = Color(0xFFFFE39A)
private val EclipseShadow = Color(0xFF07070C)

private fun DrawScope.drawSolarEclipse(type: EclipseEngine.EclipseType, magnitude: Float) {
    val c = center
    // Leaves room for the corona: the brightest streams reach ~3 radii from the disc's centre.
    val disc = size.minDimension * 0.30f

    when (type) {
        EclipseEngine.EclipseType.ANNULAR_SOLAR -> drawAnnular(c, disc)
        EclipseEngine.EclipseType.PARTIAL_SOLAR,
        EclipseEngine.EclipseType.NONE_SOLAR -> drawPartialSolar(c, disc, magnitude)
        else -> drawTotalSolar(c, disc)
    }
}

/** Totality: corona, chromosphere ring, and the diamond ring still burning at one point of the limb. */
private fun DrawScope.drawTotalSolar(c: Offset, disc: Float) {
    // Wide, faint halo first — the corona's outer falloff, which in a photograph is the last thing
    // that fades before the background.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CoronaWhite.copy(alpha = 0.20f),
                CoronaWhite.copy(alpha = 0.07f),
                Color.Transparent
            ),
            center = c,
            radius = disc * 3.4f
        ),
        radius = disc * 3.4f,
        center = c
    )

    // Corona streamers. Real coronas are never radially uniform: they are a handful of long equatorial
    // plumes and many short polar brushes. The hash gives that irregularity deterministically.
    for (i in 0 until 46) {
        val spread = jitter(i)
        val reach = disc * (1.30f + 1.85f * spread * spread)
        val width = disc * (0.035f + 0.115f * jitter(i + 211))
        val alpha = 0.055f + 0.115f * jitter(i + 97)
        val angle = i * (360f / 46f) + (spread - 0.5f) * 5f

        rotate(degrees = angle, pivot = c) {
            drawOval(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.62f to CoronaWhite.copy(alpha = alpha * 0.55f),
                    1.0f to CoronaWhite.copy(alpha = alpha),
                    startY = c.y - reach,
                    endY = c.y - disc * 0.92f
                ),
                topLeft = Offset(c.x - width / 2f, c.y - reach),
                size = Size(width, reach)
            )
        }
    }

    // The bright inner corona: a tight glow hugging the limb, which is what makes the disc look
    // *eclipsed* rather than *missing*. Four stops, transparent at both ends — a gradient that simply
    // ended on a bright colour would paint a hard-edged disc out to its radius instead of a glow.
    drawCircle(
        brush = Brush.radialGradient(
            0.00f to Color.Transparent,
            0.62f to CoronaWhite.copy(alpha = 0.10f),
            0.80f to CoronaWhite.copy(alpha = 0.52f),
            1.00f to Color.Transparent,
            center = c,
            radius = disc * 1.55f
        ),
        radius = disc * 1.55f,
        center = c
    )

    // The Moon itself: the darkest thing on the card, with no detail at all.
    drawCircle(color = EclipseShadow, radius = disc, center = c)

    // Chromosphere — the thin red rim only visible in the seconds around second and third contact.
    drawCircle(
        brush = Brush.sweepGradient(
            colors = listOf(
                Chromosphere.copy(alpha = 0.10f),
                Chromosphere.copy(alpha = 0.75f),
                Chromosphere.copy(alpha = 0.10f),
                Chromosphere.copy(alpha = 0.75f),
                Chromosphere.copy(alpha = 0.10f)
            ),
            center = c
        ),
        radius = disc * 1.012f,
        center = c,
        style = Stroke(width = disc * 0.022f)
    )

    // Diamond ring: the last photospheric point still uncovered, at roughly the 10 o'clock position.
    val ringAngle = Math.toRadians(148.0)
    val ringPoint = Offset(
        x = c.x + disc * cos(ringAngle).toFloat(),
        y = c.y - disc * sin(ringAngle).toFloat()
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White,
                CoronaWhite.copy(alpha = 0.50f),
                Color.Transparent
            ),
            center = ringPoint,
            radius = disc * 0.62f
        ),
        radius = disc * 0.62f,
        center = ringPoint
    )
    drawCircle(color = Color.White, radius = disc * 0.030f, center = ringPoint)
}

/** Annularity: the "ring of fire" — the Moon is too small to cover the photosphere. */
private fun DrawScope.drawAnnular(c: Offset, disc: Float) {
    val sun = disc * 1.34f
    val moon = sun * 0.905f

    // Glare around the whole ring. Two stops, because the photosphere is far brighter than the sky.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFE9B0).copy(alpha = 0.42f),
                Color(0xFFFFC46B).copy(alpha = 0.12f),
                Color.Transparent
            ),
            center = c,
            radius = sun * 2.5f
        ),
        radius = sun * 2.5f,
        center = c
    )

    // The uncovered photosphere: a hot disc that the Moon is about to leave a ring of.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(PhotosphereCore, PhotosphereMid, PhotosphereEdge),
            center = c,
            radius = sun
        ),
        radius = sun,
        center = c
    )
    drawCircle(color = EclipseShadow, radius = moon, center = c)

    // Baily's beads: the ring is not uniform — it is broken where lunar valleys let light through.
    for (i in 0 until 7) {
        val t = jitter(i + 31)
        val angle = Math.toRadians(360.0 * i / 7.0 + t * 30.0)
        val bead = Offset(
            x = c.x + moon * cos(angle).toFloat(),
            y = c.y + moon * sin(angle).toFloat()
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.55f + 0.35f * t),
            radius = sun * (0.012f + 0.016f * t),
            center = bead
        )
    }
}

/** Partial: two discs that never line up. The Moon is always drawn as a black disc in front. */
private fun DrawScope.drawPartialSolar(c: Offset, disc: Float, magnitude: Float) {
    val sun = disc * 1.18f
    val moon = sun * 1.005f
    // Magnitude 1 means the discs coincide; 0 means they barely touch. Offset along one diagonal.
    val gap = (1f - magnitude.coerceIn(0f, 1f)) * sun * 2.0f
    val moonCenter = Offset(
        x = c.x + gap * 0.94f,
        y = c.y - gap * 0.34f
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFFFE9B0).copy(alpha = 0.34f),
                Color.Transparent
            ),
            center = c,
            radius = sun * 2.1f
        ),
        radius = sun * 2.1f,
        center = c
    )

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(PhotosphereCore, PhotosphereEdge),
            center = c,
            radius = sun
        ),
        radius = sun,
        center = c
    )

    drawCircle(color = EclipseShadow, radius = moon, center = moonCenter)
    // The crescent's outer edge keeps a spark of chromosphere, as it does in real partial photos.
    drawCircle(
        color = Chromosphere.copy(alpha = 0.30f),
        radius = moon * 1.02f,
        center = moonCenter,
        style = Stroke(width = sun * 0.012f)
    )
}

// ---------------------------------------------------------------------------------------------
// Lunar
// ---------------------------------------------------------------------------------------------

/**
 * The Moon photograph, shaded by the Earth's umbra.
 *
 * The shade is a single radial gradient applied with [BlendMode.Multiply] inside an offscreen layer,
 * so it can only ever affect the Moon's own pixels and never the card behind it. Where the gradient is
 * centred is what distinguishes the three lunar cases:
 *
 *  - total — centred on the disc: dark core, copper limb, the classic "blood moon";
 *  - partial — pushed toward one limb: that limb is still in full sunlight while the other is deep in
 *    the umbra, and the terminator between them is soft, exactly as the umbra's edge is;
 *  - penumbral — pushed far out and grey rather than copper, because the Moon is only in the
 *    penumbra and nothing about it turns red.
 */
@Composable
private fun LunarEclipseRender(
    type: EclipseEngine.EclipseType,
    magnitude: Float,
    modifier: Modifier = Modifier
) {
    val depth = when (type) {
        EclipseEngine.EclipseType.TOTAL_LUNAR -> 1f
        EclipseEngine.EclipseType.PARTIAL_LUNAR -> 0.52f + 0.36f * magnitude.coerceIn(0f, 1f)
        EclipseEngine.EclipseType.PENUMBRAL_LUNAR -> 0.30f
        else -> 0.45f
    }
    val penumbral = type == EclipseEngine.EclipseType.PENUMBRAL_LUNAR

    val umbraCore = if (penumbral) Color(0xFF6E6B68) else Color(0xFF360D05)
    val umbraMid = if (penumbral) Color(0xFFB9B4AE) else Color(0xFF8E3A1F)
    val litLimb = if (penumbral) Color(0xFFF2EFEA) else Color(0xFFF0CBA8)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.86f)
                .clip(CircleShape)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_full_moon_photo_1785673146290),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.06f)
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val c = center
                val r = size.minDimension / 2f

                // depth = 1 puts the umbra's centre on the Moon's centre; shallower depths slide it
                // toward the limb that is still lit.
                val umbraCenter = Offset(x = c.x + r * 1.75f * (1f - depth), y = c.y)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(umbraCore, umbraMid, litLimb),
                        center = umbraCenter,
                        radius = r * 1.75f
                    ),
                    radius = r,
                    center = c,
                    blendMode = BlendMode.Multiply
                )

                // A penumbral eclipse has no hard edge anywhere, so it gets no limb highlight at all.
                if (!penumbral) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xFF2A0B06).copy(alpha = 0.55f)
                            ),
                            center = c,
                            radius = r
                        ),
                        radius = r,
                        center = c,
                        blendMode = BlendMode.SrcAtop
                    )
                }
            }
        }
    }
}

/**
 * A deterministic hash in `[0, 1)`.
 *
 * Used instead of `Random` so the corona is stable across recompositions: a flickering corona would
 * look like a rendering bug, and re-seeding it per frame would also defeat the draw cache.
 */
private fun jitter(seed: Int): Float {
    val x = sin(seed * 12.9898f + 78.233f) * 43758.5453f
    return x - floor(x)
}
