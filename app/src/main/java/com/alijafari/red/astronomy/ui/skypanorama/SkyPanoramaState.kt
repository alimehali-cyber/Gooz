package com.alijafari.red.astronomy.ui.skypanorama

import com.alijafari.red.astronomy.ui.rendering.SkySceneFrame

/**
 * Camera framing for the panorama. The texture and the shader are identical for every framing; only the view
 * direction and the vertical field of view differ.
 *
 *  - [HOME_ZENITH] is the Home hero's camera: it looks at the zenith, with the Home hero's 60° field of view.
 *    Its output must not change.
 *  - [APP_BACKDROP] is the full-screen Live Sky backdrop's camera. It looks [BACKDROP_CENTER_ALT_DEG] above the
 *    equator-facing horizon (south in the north, north in the south, as Home does) with a wide field of view,
 *    so the Sun, Moon, planets and stars near the horizon fall inside the picture, and the bottom of the screen
 *    is still photographic sky with no landscape.
 */
/** Home hero vertical field of view, degrees. */
private const val HOME_FOV_Y_DEG: Float = 60f

/** Backdrop vertical field of view, degrees. At 40° altitude this spans about -12° to +92° on the centre column. */
private const val BACKDROP_FOV_Y_DEG: Float = 104f

/** Altitude of the backdrop camera's view centre, degrees. */
private const val BACKDROP_CENTER_ALT_DEG: Double = 40.0

enum class SkyPanoramaFraming(val fovYDeg: Float) {
    HOME_ZENITH(fovYDeg = HOME_FOV_Y_DEG),
    APP_BACKDROP(fovYDeg = BACKDROP_FOV_Y_DEG);

    /** Camera basis in equatorial coordinates for this framing at a local sidereal time and latitude. */
    fun basis(lstDeg: Double, latitudeDeg: Double): SkyPanoramaMath.ViewBasis = when (this) {
        HOME_ZENITH -> SkyPanoramaMath.zenithBasis(lstDeg, latitudeDeg)
        APP_BACKDROP -> SkyPanoramaMath.basisForForwardAndUp(
            forward = SkyPanoramaMath.directionFromHorizontal(
                azimuthDeg = equatorFacingAzimuthDeg(latitudeDeg),
                altitudeDeg = BACKDROP_CENTER_ALT_DEG,
                latitudeDeg = latitudeDeg,
                lstDeg = lstDeg
            ),
            // Keep the zenith at the top of the screen, so the horizon-level view is upright in both hemispheres.
            upHint = SkyPanoramaMath.directionFromHorizontal(
                azimuthDeg = 0.0,
                altitudeDeg = 90.0,
                latitudeDeg = latitudeDeg,
                lstDeg = lstDeg
            )
        )
    }

    companion object {
        /** Same rule as [com.alijafari.red.astronomy.ui.rendering.HeroSkyProjection]: south in the north, north in the south. */
        fun equatorFacingAzimuthDeg(latitudeDeg: Double): Double = if (latitudeDeg >= 0.0) 180.0 else 0.0
    }
}

/**
 * Sky-state inputs for the panorama camera, derived from values the Home hero already computes:
 * the Local Apparent Sidereal Time (`TimeEngine.getLAST`) at the Time Machine / simulated instant,
 * and the observer's latitude from `UserLocation`.
 *
 * Equality is based on the quantized LST, latitude, sky values and framing. The renderer redraws when, and only
 * when, this value changes, so an unchanged sky never triggers a GL frame.
 */
data class SkyPanoramaState(
    val lstDeg: Double,
    val latitudeDeg: Double,
    /** Quantized Sun altitude of the hero, degrees. Drives the daytime palette and the night blend. */
    val sunAltDeg: Double = -90.0,
    /** Quantized moon-glow intensity from the existing lighting model, 0..1. */
    val moonGlow: Float = 0f,
    /** Camera framing. Home keeps [SkyPanoramaFraming.HOME_ZENITH]; the Live Sky backdrop uses its own. */
    val framing: SkyPanoramaFraming = SkyPanoramaFraming.HOME_ZENITH
) {
    /** Camera basis in equatorial coordinates. Derived from the framing, so it does not affect equality. */
    val basis: SkyPanoramaMath.ViewBasis = framing.basis(lstDeg, latitudeDeg)

    /** Daytime palette and night blend weight. Derived, so it does not affect equality. */
    val sky: SkyPanoramaSkyModel.Sky = SkyPanoramaSkyModel.skyFor(sunAltDeg, moonGlow)

    companion object {
        /** Builds a quantized state from the hero's existing sky values. */
        fun fromSkyState(
            lastDeg: Double,
            latitudeDeg: Double,
            sunAltDeg: Double,
            moonGlowIntensity: Float,
            framing: SkyPanoramaFraming = SkyPanoramaFraming.HOME_ZENITH
        ): SkyPanoramaState = SkyPanoramaState(
            lstDeg = SkyPanoramaMath.quantizeLst(lastDeg),
            latitudeDeg = latitudeDeg,
            sunAltDeg = SkyPanoramaSkyModel.quantizeSunAlt(sunAltDeg),
            moonGlow = SkyPanoramaSkyModel.quantizeMoonGlow(moonGlowIntensity),
            framing = framing
        )

        /**
         * The panorama inputs for one computed sky frame. The Home hero and the Live Sky backdrop both call this, so
         * the same frame always yields the same panorama state for the same framing.
         */
        fun fromSceneFrame(
            frame: SkySceneFrame,
            framing: SkyPanoramaFraming = SkyPanoramaFraming.HOME_ZENITH
        ): SkyPanoramaState = fromSkyState(
            lastDeg = frame.lastDeg,
            latitudeDeg = frame.latitudeDeg,
            sunAltDeg = frame.sunHoriz.altitudeDeg,
            moonGlowIntensity = frame.lightingState.moonGlowIntensity,
            framing = framing
        )
    }
}

/** Lifecycle of the panorama layer as seen by the Compose hero. */
enum class SkyPanoramaStatus {
    /** Surface or texture not yet presented. The legacy Sky Canvas stays visible underneath. */
    LOADING,

    /** At least one panorama frame has been presented. The legacy sky background is skipped. */
    READY,

    /** Texture or GL setup failed. The legacy Sky Canvas stays visible. */
    UNAVAILABLE
}
