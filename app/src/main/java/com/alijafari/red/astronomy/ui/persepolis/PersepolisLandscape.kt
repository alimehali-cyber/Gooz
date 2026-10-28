package com.alijafari.red.astronomy.ui.persepolis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.ui.rendering.PersepolisFraming
import com.alijafari.red.astronomy.ui.rendering.SkySceneFrame

/**
 * Feature gate for the combined Persepolis + Mil-e Azhdaha landscape, mirroring
 * [com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaFeature].
 *
 * The two ruins are realistically lit limestone reliefs, so they belong to the two photographic
 * themes. The
 * other themes are deliberate flat styles — cut paper, watercolour, monochrome, and the red
 * night-vision Observatory mode, whose whole purpose is to emit nothing but red light — and they keep
 * their existing stylised ground unchanged. The gate is the single switch for the whole feature: the
 * GL layer in HeroSkyCanvas and the grounding paint in the sky pass both read it.
 */
object PersepolisFeature {
    // Enabled in every build. Set to false only to disable the combined landscape entirely.
    const val INTEGRATION_ENABLED = true

    /** True for the photographic themes the realistic relief is art-directed for. */
    fun isEnabledFor(theme: SkyCanvasTheme): Boolean =
        INTEGRATION_ENABLED &&
            (theme == SkyCanvasTheme.REAL_SKY || theme == SkyCanvasTheme.ATMOSPHERIC_SKY)
}

/**
 * Moonlight intensity for [PersepolisTextureView.setMoon] from the illuminated fraction.
 *
 * The model's own guidance: ≈0.22 is a bright full Moon, scaled by the illuminated fraction. The view
 * additionally fades the light out as the Moon approaches the horizon and switches it off below it.
 */
internal fun persepolisMoonIntensity(illuminationPercent: Double): Float =
    (0.22 * illuminationPercent.coerceIn(0.0, 100.0) / 100.0).toFloat()

/**
 * Compose host for [PersepolisTextureView]: the Apadana east façade in the left distance and
 * the Mil-e Azhdaha tower on its limestone hillside as the ground of the Home sky scene.
 *
 * The view is a transparent `TextureView` (RGBA8888 + depth, render-on-demand) laid over
 * the sky canvas, showing through wherever there is no
 * stone. Unlike the supplied file's original window-level overlay view, a TextureView is
 * composited into the window like any other view, so the relief is ordinary scene
 * content: Compose siblings drawn after it (the motion layer, the readout, overlays such
 * as the sky search) cover and dim it, its bounds follow the canvas's clip, and it scrolls
 * with the Home feed instead of trailing a window-level surface behind it. [PersepolisFraming]
 * keeps opaque
 * stone clear of the scene readout and the header as composition, so the caption reads
 * against sky; what lies beneath the stone is where it belongs physically: cloud veils drift
 * behind the
 * columns.
 *
 * The lighting feed is ZIG's own ephemeris, unchanged: [SkySceneFrame.sunHoriz] and
 * [SkySceneFrame.moonData] already carry astronomical azimuth (0° = N, 90° = E) and altitude — exactly
 * the convention [PersepolisTextureView.setSun]/[PersepolisTextureView.setMoon] take — so
 * both ruins are lit by the
 * same Sun and Moon that light the sky behind it, including while the finger scrubs time. Updates are
 * keyed on the ephemeris values, so the GL view renders one frame per sky-model change (the usual 15 s
 * bucket at idle, per frame while scrubbing) and costs nothing between them.
 *
 * The view itself is non-clickable and non-focusable and consumes no touches, so tap-to-identify and
 * finger time travel on the surrounding canvas are unaffected. Lifecycle is forwarded from the
 * composition's [androidx.lifecycle.LifecycleOwner], as the host's render thread requires.
 */
@Composable
fun PersepolisLandscape(
    frame: SkySceneFrame,
    framing: PersepolisFraming.Result,
    modifier: Modifier = Modifier
) {
    var view by remember { mutableStateOf<PersepolisTextureView?>(null) }

    val sunAzimuthDeg = frame.sunHoriz.azimuthDeg.toFloat()
    val sunAltitudeDeg = frame.sunHoriz.altitudeDeg.toFloat()
    val moonAzimuthDeg = frame.moonData.azimuthDeg.toFloat()
    val moonAltitudeDeg = frame.moonData.altitudeDeg.toFloat()
    val moonIntensity = persepolisMoonIntensity(frame.moonData.illuminationPercent)

    // One GL frame per ephemeris/framing change. Keyed on the values (not on the frame object), so
    // recompositions for clouds, particles or the 1 Hz clock do not touch the GL view at all.
    LaunchedEffect(view, sunAzimuthDeg, sunAltitudeDeg) {
        view?.setSun(sunAzimuthDeg, sunAltitudeDeg)
    }
    LaunchedEffect(view, moonAzimuthDeg, moonAltitudeDeg, moonIntensity) {
        view?.setMoon(moonAzimuthDeg, moonAltitudeDeg, moonIntensity)
    }
    LaunchedEffect(view, framing) {
        view?.setFraming(
            framing.visibleWidthMeters,
            framing.baseFractionFromTop,
            framing.centerFractionFromLeft
        )
    }

    // Render-thread lifecycle: gate drawing with the host's lifecycle, resume with it. Adding the observer
    // syncs it to the current lifecycle state, so a view created while already RESUMED is resumed too.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, view) {
        val currentView = view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentView?.onResume()
                Lifecycle.Event.ON_PAUSE -> currentView?.onPause()
                else -> Unit
            }
        }
        if (currentView != null) lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        factory = { context ->
            PersepolisTextureView(context).apply {
                // Framing and lights are set before the surface exists, so the first drawn frame is
                // already the composed one; the effects above keep them current afterwards.
                setFraming(
                    framing.visibleWidthMeters,
                    framing.baseFractionFromTop,
                    framing.centerFractionFromLeft
                )
                setSun(sunAzimuthDeg, sunAltitudeDeg)
                setMoon(moonAzimuthDeg, moonAltitudeDeg, moonIntensity)
                view = this
            }
        },
        // All state flows through the keyed effects above; there is nothing to push per recomposition.
        update = {},
        onRelease = { released ->
            // Terminal for this view instance: frees the scene's GL objects on its render
            // thread and stops that thread. If the surface is already gone, the texture
            // callback covers the renderer's teardown instead.
            released.release()
            if (view === released) view = null
        },
        modifier = modifier
    )
}
