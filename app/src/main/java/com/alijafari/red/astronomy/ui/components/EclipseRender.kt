package com.alijafari.red.astronomy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.astro_engine.EclipseEngine

/**
 * The eclipse as a picture: a photographic plate, chosen by the *kind* of eclipse being shown.
 *
 * There are five plates and each one is the phenomenon itself, not a stand-in:
 *
 * | Eclipse | Plate |
 * |---|---|
 * | total / hybrid solar | totality — corona, chromosphere, diamond ring |
 * | annular solar | the ring of fire |
 * | partial solar | a bitten solar disc with its glare |
 * | total lunar | the copper "blood moon" disc |
 * | partial / penumbral lunar | the Moon with the umbra biting into one limb |
 *
 * They are 1024 px, CC0-free renders generated for this app, shipped as WebP (21–68 KB each) in
 * `drawable-nodpi` so no density scaling is applied to an image that is already the right resolution.
 *
 * The plates arrive with a black sky rather than transparency — that is what an eclipse photograph
 * looks like — so the render cuts them to a soft circle with a `DstIn` radial mask inside one offscreen
 * layer. Without that, a square black frame would sit on the glass card; with it, the eclipse appears to
 * float on the card and the corona's outer falloff blends into the surface.
 *
 * Nothing here is animated and nothing is random: one image, one mask, so a recomposition cannot make
 * an eclipse flicker.
 */
@Composable
fun EclipseRender(
    type: EclipseEngine.EclipseType,
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

    val plate = plateFor(type)
    // A penumbral eclipse is barely an eclipse: the Moon only grazes the outer shadow, so the plate is
    // dimmed rather than reddened.
    val plateAlpha = if (type == EclipseEngine.EclipseType.PENUMBRAL_LUNAR) 0.72f else 1f

    Box(modifier = semanticsModifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        ) {
            Image(
                painter = painterResource(id = plate),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = plateAlpha,
                modifier = Modifier
                    .fillMaxSize()
                    // A hair of overscan: the plates are centred, and the crop should never leave a seam.
                    .scale(1.04f)
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                // DstIn keeps the plate only where this gradient is opaque, so the frame fades out
                // instead of ending on a hard edge.
                drawRect(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White,
                            0.60f to Color.White,
                            0.86f to Color.White.copy(alpha = 0.45f),
                            1.00f to Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.minDimension * 0.52f
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
        }
    }
}

private fun plateFor(type: EclipseEngine.EclipseType): Int = when (type) {
    EclipseEngine.EclipseType.TOTAL_SOLAR,
    EclipseEngine.EclipseType.HYBRID_SOLAR -> R.drawable.eclipse_solar_total

    EclipseEngine.EclipseType.ANNULAR_SOLAR -> R.drawable.eclipse_solar_annular

    EclipseEngine.EclipseType.PARTIAL_SOLAR,
    EclipseEngine.EclipseType.NONE_SOLAR -> R.drawable.eclipse_solar_partial

    EclipseEngine.EclipseType.TOTAL_LUNAR -> R.drawable.eclipse_lunar_total

    EclipseEngine.EclipseType.PARTIAL_LUNAR,
    EclipseEngine.EclipseType.PENUMBRAL_LUNAR,
    EclipseEngine.EclipseType.NONE_LUNAR -> R.drawable.eclipse_lunar_partial
}
