package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.domain.SkyCanvasTheme

/**
 * Draws the shared Sky Canvas layers for a [SkySceneFrame]: atmosphere, Milky Way, stars, Sun, Moon,
 * planets and, on the Home hero only, the horizon landscape. The layers are drawn in that order.
 *
 * The sky model is shared by every surface. What differs per surface is chosen by a [SkyRenderTarget]: the
 * projection of sky directions onto pixels, the clip below which objects are hidden, and whether the landscape is
 * drawn. Interaction layers (selection ring, particles) stay in the caller.
 */
object SkySceneRenderer {

    /**
     * Azimuth and altitude (degrees) of a point 1° from the Moon towards celestial north
     * ([towardsNorth]) or celestial west, in the AR frame of [sunMoon] (true equator of date), so the
     * photographic Moon is oriented exactly as the AR screen orients it.
     */
    internal fun offsetAltAz(sunMoon: com.alijafari.red.astronomy.ar.core.ArSkySceneComputer.SunMoon, towardsNorth: Boolean): Pair<Double, Double> {
        val u = sunMoon.moon.placed.enu
        val pole = sunMoon.frame.enuFromTod.let { m -> doubleArrayOf(m[2], m[5], m[8]) }
        // North tangent n = pole − (pole·u)u; east e = n × u (so that e × n = u); west = −e.
        val d = pole[0] * u[0] + pole[1] * u[1] + pole[2] * u[2]
        val n = doubleArrayOf(pole[0] - d * u[0], pole[1] - d * u[1], pole[2] - d * u[2])
        val nl = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-12)
        for (i in 0..2) n[i] /= nl
        val t = if (towardsNorth) n else doubleArrayOf(
            -(n[1] * u[2] - n[2] * u[1]), -(n[2] * u[0] - n[0] * u[2]), -(n[0] * u[1] - n[1] * u[0])
        )
        val step = Math.toRadians(1.0)
        val p = DoubleArray(3) { u[it] * kotlin.math.cos(step) + t[it] * kotlin.math.sin(step) }
        val alt = Math.toDegrees(kotlin.math.asin(p[2].coerceIn(-1.0, 1.0)))
        val az = (Math.toDegrees(kotlin.math.atan2(p[0], p[1])) + 360.0) % 360.0
        return az to alt
    }

    /**
     * @param panoramaActive true while the photographic panorama on this surface supplies the sky gradient and the
     *   procedural Milky Way is replaced. Both the Home hero and the Live Sky backdrop set this.
     * @param target the surface being drawn. It defaults to the Home hero, so existing Home calls draw as before.
     * @param moonSprites the photographic Moon (the Home Moon card's look; see [SkyMoonSprites]) for the
     *   Real Sky and Atmospheric Sky themes; the Moon appears with its first sprite (a fraction of a second).
     *   Without it, or if it cannot be rendered, the procedural Moon is drawn.
     * @param bortle the observer's Bortle class, for the night sky in front of the Moon.
     */
    fun drawSky(
        drawScope: DrawScope,
        frame: SkySceneFrame,
        theme: SkyCanvasTheme,
        frameTimeMs: Long,
        panoramaActive: Boolean,
        target: SkyRenderTarget = SkyRenderTarget.HOME_HERO,
        moonSprites: SkyMoonSprites? = null,
        bortle: Int = 3
    ) {
        val canvasW = drawScope.size.width
        val canvasH = drawScope.size.height
        val userLat = frame.latitudeDeg
        val lastDeg = frame.lastDeg
        val sunHoriz = frame.sunHoriz
        val moonData = frame.moonData
        val lightingState = frame.lightingState
        val projection = target.projectionFor(frame)
        val clipBottom = projection.clipBottomPx(canvasH)

        // Raw Sun position, used for the glow. Null when the Sun is far below the horizon.
        val sunPosPx = if (sunHoriz.altitudeDeg > -18.0) {
            projection.project(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, canvasW, canvasH, userLat)
        } else null

        // 1. Atmosphere Renderer. While the panorama is on screen it supplies the sky gradient and night
        // sky itself; this pass then draws only the Sun glow and Belt of Venus, faded by night weight.
        AtmosphereRenderer.drawAtmosphere(
            drawScope = drawScope,
            lightingState = lightingState,
            sunPosPx = sunPosPx,
            theme = theme,
            sunAzimuthDeg = sunHoriz.azimuthDeg,
            latitudeDeg = userLat,
            panoramaMode = panoramaActive,
            projection = projection
        )

        // 2. Milky Way Renderer (procedural; replaced by the photographic panorama when active)
        if (!panoramaActive) {
            MilkyWayRenderer.drawMilkyWay(
                drawScope = drawScope,
                galacticPoints = frame.galacticPlanePoints,
                lightingState = lightingState,
                frameTimeMs = frameTimeMs,
                theme = theme,
                latitudeDeg = userLat,
                lastDeg = lastDeg,
                projection = projection
            )
        }

        // 3. Star Renderer
        StarRenderer.drawStars(
            drawScope = drawScope,
            objects = frame.catalogStars,
            starVisibility = lightingState.starVisibility,
            frameTimeMs = frameTimeMs,
            theme = theme,
            latitudeDeg = userLat,
            lastDeg = lastDeg,
            projection = projection
        )

        // 4. Sun Renderer
        if (sunPosPx != null && sunHoriz.altitudeDeg > -12.0) {
            SunRenderer.drawSun(
                drawScope = drawScope,
                center = projection.objectPosition(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, canvasW, canvasH, userLat),
                sunAltitudeDeg = sunHoriz.altitudeDeg,
                frameTimeMs = frameTimeMs,
                theme = theme,
                clipBottomPx = clipBottom
            )
        }

        // 5. Moon Renderer
        if (moonData.altitudeDeg > -12.0) {
            val moonCenter = projection.objectPosition(moonData.azimuthDeg, moonData.altitudeDeg, canvasW, canvasH, userLat)
            val baseMoonRadius = with(drawScope) { 26.dp.toPx() }
            val moonPulseScale = AstronomyAnimator.computePulse(frameTimeMs, 4000f, 0.88f, 1.12f)

            // The lit limb points at the Sun as it appears on this surface's screen.
            val limbScreenAngleDeg = projection.moonLimbAngleDeg(
                moonAzimuthDeg = moonData.azimuthDeg,
                moonAltitudeDeg = moonData.altitudeDeg,
                sunAzimuthDeg = sunHoriz.azimuthDeg,
                sunAltitudeDeg = sunHoriz.altitudeDeg,
                width = canvasW,
                height = canvasH,
                latitudeDeg = userLat
            )

            val photoTheme = theme == SkyCanvasTheme.REAL_SKY || theme == SkyCanvasTheme.ATMOSPHERIC_SKY
            val sunMoon = frame.sunMoon
            val photoRadius = if (theme == SkyCanvasTheme.REAL_SKY) baseMoonRadius * 0.48f else baseMoonRadius
            val photo = if (photoTheme && !frame.isSolarEclipse && moonSprites != null && sunMoon != null) {
                moonSprites.sprite(frame, photoRadius, bortle)
            } else null
            if (photo != null && sunMoon != null) {
                // Screen directions of celestial north and west at the Moon on this surface.
                val (northAz, northAlt) = offsetAltAz(sunMoon, towardsNorth = true)
                val (westAz, westAlt) = offsetAltAz(sunMoon, towardsNorth = false)
                val northDeg = projection.moonLimbAngleDeg(moonData.azimuthDeg, moonData.altitudeDeg, northAz, northAlt, canvasW, canvasH, userLat)
                val westDeg = projection.moonLimbAngleDeg(moonData.azimuthDeg, moonData.altitudeDeg, westAz, westAlt, canvasW, canvasH, userLat)
                MoonRenderer.drawPhotoMoon(
                    drawScope = drawScope,
                    center = moonCenter,
                    radius = photoRadius,
                    sprite = photo,
                    northDeg = northDeg,
                    westDeg = westDeg,
                    isLunarEclipse = frame.isLunarEclipse,
                    lightingState = lightingState,
                    theme = theme,
                    frameTimeMs = frameTimeMs,
                    illuminationPercent = moonData.illuminationPercent,
                    clipBottomPx = clipBottom
                )
            } else if (!(photoTheme && moonSprites != null && sunMoon != null && !frame.isSolarEclipse && !moonSprites.failed)) {
                // Stylised themes, eclipses of the Sun, or no photographic Moon available.
                MoonRenderer.drawMoon(
                    drawScope = drawScope,
                    center = moonCenter,
                    radius = baseMoonRadius,
                    illuminationPercent = moonData.illuminationPercent,
                    phaseAngleRad = moonData.phaseAngleRad,
                    isLunarEclipse = frame.isLunarEclipse,
                    isSolarEclipse = frame.isSolarEclipse,
                    moonPulseScale = moonPulseScale,
                    lightingState = lightingState,
                    frameTimeMs = frameTimeMs,
                    isWaxing = (moonData.ageDays < 14.765),
                    theme = theme,
                    limbScreenAngleDeg = limbScreenAngleDeg,
                    clipBottomPx = clipBottom
                )
            }
        }

        // 6. Planet Renderer
        PlanetRenderer.drawPlanets(
            drawScope = drawScope,
            planets = frame.planetPositions,
            frameTimeMs = frameTimeMs,
            theme = theme,
            latitudeDeg = userLat,
            projection = projection
        )

        // 7. Horizon Landscape Silhouette Layer. Home only: the backdrop is uninterrupted sky to the bottom edge.
        if (target.drawsLandscape) {
            LandscapeRenderer.drawHorizonLandscape(
                drawScope = drawScope,
                lightingState = lightingState,
                frameTimeMs = frameTimeMs,
                theme = theme
            )
        }
    }
}
