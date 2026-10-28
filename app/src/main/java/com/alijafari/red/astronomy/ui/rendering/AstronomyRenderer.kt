package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.GalacticEngine
import com.alijafari.red.astronomy.astro_engine.MoonEngine
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.SkyCanvasTheme

/**
 * RED Mathematical Astronomy Engine (RMAE)
 * AstronomyRenderer: The master procedural rendering pipeline.
 * Replaces primitive artwork with pure mathematical calculations.
 */
object AstronomyRenderer {

    fun renderSkyCanvas(
        drawScope: DrawScope,
        lightingState: LightingState,
        sunHoriz: CoordinateEngine.Horizontal,
        moonData: MoonEngine.MoonData,
        planets: List<Triple<PlanetEngine.PlanetType, PlanetEngine.PlanetPosition, CoordinateEngine.Horizontal>>,
        stars: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
        galacticPoints: List<GalacticEngine.GalacticPlanePoint>,
        selectedObjectPos: Offset?,
        frameTimeMs: Long,
        theme: SkyCanvasTheme = SkyCanvasTheme.ATMOSPHERIC_SKY,
        drawGrid: Boolean = false,
        latitudeDeg: Double = 0.0
    ) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        val sunPosPx = if (sunHoriz.altitudeDeg > -18.0) {
            HeroSkyProjection.project(sunHoriz.azimuthDeg, sunHoriz.altitudeDeg, width, height, latitudeDeg)
        } else null

        // 1. Atmosphere & Sky Gradient
        AtmosphereRenderer.drawAtmosphere(
            drawScope = drawScope,
            lightingState = lightingState,
            sunPosPx = sunPosPx,
            theme = theme,
            sunAzimuthDeg = sunHoriz.azimuthDeg,
            latitudeDeg = latitudeDeg
        )

        // 2. Coordinate Grid (if enabled)
        if (drawGrid) {
            drawCoordinateGrid(drawScope, theme, latitudeDeg)
        }

        // 3. Milky Way Procedural Bands
        MilkyWayRenderer.drawMilkyWay(
            drawScope = drawScope,
            galacticPoints = galacticPoints,
            lightingState = lightingState,
            frameTimeMs = frameTimeMs,
            theme = theme,
            latitudeDeg = latitudeDeg
        )

        // 4. Constellation Lines
        ConstellationRenderer.drawConstellationLines(
            drawScope = drawScope,
            stars = stars,
            starVisibility = lightingState.starVisibility,
            frameTimeMs = frameTimeMs,
            theme = theme,
            latitudeDeg = latitudeDeg
        )

        // 5. Stars & Deep Sky Objects
        StarRenderer.drawStars(
            drawScope = drawScope,
            objects = stars,
            starVisibility = lightingState.starVisibility,
            frameTimeMs = frameTimeMs,
            theme = theme,
            latitudeDeg = latitudeDeg
        )

        // 6. Sun
        if (sunPosPx != null && sunHoriz.altitudeDeg > -12.0) {
            SunRenderer.drawSun(
                drawScope = drawScope,
                center = sunPosPx,
                sunAltitudeDeg = sunHoriz.altitudeDeg,
                frameTimeMs = frameTimeMs,
                theme = theme
            )
        }

        // 7. Moon
        if (moonData.altitudeDeg > -12.0) {
            val moonCenter = HeroSkyProjection.project(moonData.azimuthDeg, moonData.altitudeDeg, width, height, latitudeDeg)
            
            MoonRenderer.drawMoon(
                drawScope = drawScope,
                center = moonCenter,
                radius = 26f,
                illuminationPercent = moonData.illuminationPercent,
                phaseAngleRad = moonData.phaseAngleRad,
                isLunarEclipse = false,
                isSolarEclipse = false,
                moonPulseScale = AstronomyAnimator.computePulse(frameTimeMs, 4000f, 0.95f, 1.05f),
                lightingState = lightingState,
                frameTimeMs = frameTimeMs,
                isWaxing = (moonData.ageDays < 14.765),
                theme = theme
            )
        }

        // 8. Planets
        PlanetRenderer.drawPlanets(
            drawScope = drawScope,
            planets = planets,
            frameTimeMs = frameTimeMs,
            theme = theme,
            latitudeDeg = latitudeDeg
        )

        // 9. Horizon Landscape Silhouette
        LandscapeRenderer.drawHorizonLandscape(
            drawScope = drawScope,
            lightingState = lightingState,
            frameTimeMs = frameTimeMs,
            theme = theme
        )

        // 10. Target Selection Indicator Ring
        selectedObjectPos?.let { pos ->
            drawSelectionIndicator(drawScope, pos, frameTimeMs, theme)
        }
    }

    private fun drawCoordinateGrid(drawScope: DrawScope, theme: SkyCanvasTheme, latitudeDeg: Double = 0.0) {
        val width = drawScope.size.width
        val height = drawScope.size.height

        val gridColor = when (theme) {
            SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1).copy(alpha = 0.25f)
            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> Color.White.copy(alpha = 0.15f)
            SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444).copy(alpha = 0.20f)
            else -> Color.White.copy(alpha = 0.10f)
        }

        // Horizontal Altitude Circles
        for (alt in 15..75 step 15) {
            val y = HeroSkyProjection.project(0.0, alt.toDouble(), width, height, latitudeDeg).y
            drawScope.drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1.0f
            )
        }

        // Vertical Azimuth Lines
        for (az in 45..315 step 45) {
            val x = HeroSkyProjection.project(az.toDouble(), 0.0, width, height, latitudeDeg).x
            drawScope.drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1.0f
            )
        }
    }

    private fun drawSelectionIndicator(drawScope: DrawScope, pos: Offset, frameTimeMs: Long, theme: SkyCanvasTheme) {
        val pulse = AstronomyAnimator.computePulse(frameTimeMs, 2000f, 0.9f, 1.15f)
        val ringColor = when (theme) {
            SkyCanvasTheme.MONOCHROME_SCIENTIFIC -> Color.White
            SkyCanvasTheme.OBSERVATORY -> Color(0xFFEF4444)
            SkyCanvasTheme.KIDS_WATERCOLOR -> Color(0xFFFF85A1)
            else -> Color(0xFF38BDF8)
        }

        drawScope.drawCircle(
            color = ringColor.copy(alpha = 0.5f),
            radius = 32f * pulse,
            center = pos,
            style = Stroke(width = 1.8f)
        )
        drawScope.drawCircle(
            color = ringColor,
            radius = 20f,
            center = pos,
            style = Stroke(width = 2.2f)
        )
    }
}
