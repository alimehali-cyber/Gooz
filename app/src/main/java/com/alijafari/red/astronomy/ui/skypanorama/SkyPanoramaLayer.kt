package com.alijafari.red.astronomy.ui.skypanorama

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose entry point for the panorama. Place it BEFORE the overlay canvas in the same container:
 * embedded views are drawn beneath later Compose content, so the legacy atmosphere must be skipped
 * once [onStatusChanged] reports [SkyPanoramaStatus.READY].
 *
 * The caller owns [state]; it should be derived with [SkyPanoramaState.fromSkyState] from the same
 * LAST and latitude values that drive the existing hero overlays.
 */
@Composable
fun SkyPanoramaLayer(
    state: SkyPanoramaState,
    modifier: Modifier = Modifier,
    onStatusChanged: (SkyPanoramaStatus) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            SkyPanoramaSurfaceView(context).apply {
                this.onStatusChanged = onStatusChanged
                updateState(state)
            }
        },
        update = { view ->
            view.onStatusChanged = onStatusChanged
            view.updateState(state)
        },
        onRelease = { view -> view.release() },
        modifier = modifier
    )
}
