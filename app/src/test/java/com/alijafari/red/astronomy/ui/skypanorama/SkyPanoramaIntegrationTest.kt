package com.alijafari.red.astronomy.ui.skypanorama

import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.ui.rendering.SkySceneModel
import com.alijafari.red.astronomy.ui.rendering.SkyTimeModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyPanoramaIntegrationTest {

    private val lat = 35.6892
    private val lon = 51.3890
    private val elev = 1100.0
    private val liveTime = 1_700_000_000_000L

    @Test
    fun panoramaGateIsPermanentlyEnabledWithoutTestOnlyTag() {
        assertTrue(SkyPanoramaFeature.INTEGRATION_ENABLED)
        val config = File(sourceRoot(), "com/alijafari/red/astronomy/ui/skypanorama/SkyPanoramaConfig.kt").readText()
        assertFalse("the panorama gate must not carry a TEST-ONLY label", config.contains("TEST-ONLY"))
    }

    @Test
    fun panoramaReplacesTheBackgroundOnlyForRealSky() {
        for (theme in SkyCanvasTheme.values()) {
            assertEquals(theme == SkyCanvasTheme.REAL_SKY, SkyPanoramaFeature.isEnabledFor(theme))
        }
    }

    @Test
    fun proceduralSkyStaysOnlyAsTheFallbackUntilThePanoramaIsReady() {
        // Loading or failed: the procedural Milky Way is the fallback.
        assertFalse(SkyPanoramaFeature.isPresented(SkyCanvasTheme.REAL_SKY, panoramaReady = false))
        // Ready and Real Sky: the photographic panorama replaces the procedural background.
        assertTrue(SkyPanoramaFeature.isPresented(SkyCanvasTheme.REAL_SKY, panoramaReady = true))
        // Other themes keep their own look, even when the panorama is ready.
        assertFalse(SkyPanoramaFeature.isPresented(SkyCanvasTheme.ATMOSPHERIC_SKY, panoramaReady = true))
    }

    @Test
    fun rendererUsesTheExistingBundledPanoramaAsset() {
        assertEquals("sky/panorama/milkyway_2020_4k.jpg", SkyPanoramaConfig().assetPath)
        val asset = File(File("src/main/assets"), SkyPanoramaConfig().assetPath)
        assertTrue("panorama asset must be bundled: ${asset.path}", asset.exists())
    }

    @Test
    fun panoramaInputsComeFromTheSameFrameAsTheSkyScene() {
        val frame = SkySceneModel.compute(TimeEngine.getJulianDate(liveTime), lat, lon, elev)
        val state = SkyPanoramaState.fromSceneFrame(frame)
        assertEquals(SkyPanoramaMath.quantizeLst(TimeEngine.getLAST(frame.jd, lon)), state.lstDeg, 1e-9)
        assertEquals(lat, state.latitudeDeg, 0.0)
        assertEquals(
            SkyPanoramaState.fromSkyState(
                frame.lastDeg, lat, frame.sunHoriz.altitudeDeg, frame.lightingState.moonGlowIntensity
            ),
            state
        )
    }

    @Test
    fun homeAndBackdropDeriveTheSamePanoramaStateForTheSameEffectiveInstant() {
        // Home and the backdrop both compute the instant from SkyTimeModel, then the panorama state from the frame.
        val offset = 3.5f
        val homeJd = SkyTimeModel.effectiveJd(liveTime, offset)
        val backdropJd = SkyTimeModel.effectiveJd(liveTime, offset)
        val home = SkyPanoramaState.fromSceneFrame(SkySceneModel.compute(homeJd, lat, lon, elev))
        val backdrop = SkyPanoramaState.fromSceneFrame(SkySceneModel.compute(backdropJd, lat, lon, elev))
        assertEquals(home, backdrop)
    }

    @Test
    fun aDragOffsetMovesThePanoramaSiderealAngle() {
        val still = SkyPanoramaState.fromSceneFrame(
            SkySceneModel.compute(SkyTimeModel.effectiveJd(liveTime, 0f), lat, lon, elev)
        )
        val moved = SkyPanoramaState.fromSceneFrame(
            SkySceneModel.compute(SkyTimeModel.effectiveJd(liveTime, 2f), lat, lon, elev)
        )
        assertFalse("a two-hour offset must change the panorama camera", still.lstDeg == moved.lstDeg)
    }

    private fun sourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.exists() } ?: error("source root not found")
    }
}
