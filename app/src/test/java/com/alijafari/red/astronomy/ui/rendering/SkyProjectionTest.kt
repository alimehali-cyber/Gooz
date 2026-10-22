package com.alijafari.red.astronomy.ui.rendering

import androidx.compose.ui.geometry.Offset
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.ui.skypanorama.GeometricHorizontal
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaFraming
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaMath
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

/**
 * Checks for the per-target sky projection. The Home projection must match the existing Home mapping exactly. The
 * backdrop camera must place each object on the photographic texel for the same equatorial point.
 */
class SkyProjectionTest {

    private val width = 1080f
    private val height = 2340f
    private val lat = 45.31

    private fun camera(lst: Double, latitude: Double = lat) =
        CameraProjection(lst, latitude, SkyPanoramaFraming.APP_BACKDROP)

    private fun wrapDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 1.0
        return minOf(d, 1.0 - d)
    }

    /** Pixel -> texel, through the same camera equations the panorama shader uses. */
    private fun texelOfPixel(lst: Double, latitude: Double, offset: Offset, w: Float, h: Float): DoubleArray {
        val basis = SkyPanoramaFraming.APP_BACKDROP.basis(lst, latitude)
        val tanHalfY = tan(Math.toRadians(SkyPanoramaFraming.APP_BACKDROP.fovYDeg.toDouble()) / 2.0)
        val tanHalfX = tanHalfY * w / h
        val ndcX = 2.0 * offset.x / w - 1.0
        val ndcY = 1.0 - 2.0 * offset.y / h
        val ray = SkyPanoramaMath.cameraRay(basis, ndcX, ndcY, tanHalfX, tanHalfY)
        return SkyPanoramaMath.uvForDirection(ray[0], ray[1], ray[2])
    }

    @Test
    fun homeProjectionMatchesTheExistingHomeMappingExactly() {
        val samples = listOf(
            doubleArrayOf(0.0, 0.0), doubleArrayOf(180.0, 40.0), doubleArrayOf(90.0, -5.0), doubleArrayOf(275.0, 72.0)
        )
        for (s in samples) {
            val az = s[0]
            val alt = s[1]
            assertEquals(
                HeroSkyProjection.project(az, alt, 400f, 900f, lat),
                HeroProjection.objectPosition(az, alt, 400f, 900f, lat)
            )
            assertEquals(
                HeroSkyProjection.project(az, alt, 400f, 900f, lat),
                HeroProjection.project(az, alt, 400f, 900f, lat)
            )
        }
        assertEquals(900f * HeroSkyProjection.HORIZON_FRACTION, HeroProjection.clipBottomPx(900f), 0f)
        assertEquals(500f - 18f, HeroProjection.beltAnchorY(Offset(10f, 300f), 500f), 0f)
        assertEquals(
            CoordinateEngine.calculateMoonLimbScreenAngleDeg(120.0, 20.0, 140.0, 5.0),
            HeroProjection.moonLimbAngleDeg(120.0, 20.0, 140.0, 5.0, 400f, 900f, lat),
            0.0
        )
    }

    @Test
    fun engineDirectionsRoundTripThroughTheCameraToTheSameEquatorialPoint() {
        // Every on-screen object must land on the texel of its own RA/Dec. This is the alignment with the photograph.
        val random = Random(7)
        var checked = 0
        repeat(4000) {
            val ra = random.nextDouble() * 360.0
            val dec = -85.0 + random.nextDouble() * 170.0
            val lst = random.nextDouble() * 360.0
            val latitude = -70.0 + random.nextDouble() * 140.0
            // Geometric altitude: the engine's refraction (under 1 degree) is checked separately, so the exact texel
            // comparison below tests only the camera and the photograph.
            val horiz = GeometricHorizontal.of(ra, dec, lst, latitude)
            val p = camera(lst, latitude).project(horiz.azimuthDeg, horiz.altitudeDeg, width, height, latitude)
            if (p == SkyProjection.OFF_CANVAS) return@repeat
            if (p.x < 0f || p.x > width || p.y < 0f || p.y > height) return@repeat
            val texel = texelOfPixel(lst, latitude, p, width, height)
            val e = SkyPanoramaMath.directionFromRaDec(ra, dec)
            val expected = SkyPanoramaMath.uvForDirection(e[0], e[1], e[2])
            // Pixel centres are exact here, so the texel error is limited to float rounding of the offset.
            assertTrue("u mismatch at ra=$ra dec=$dec", wrapDistance(texel[0], expected[0]) < 1e-4)
            assertTrue("v mismatch at ra=$ra dec=$dec", abs(texel[1] - expected[1]) < 1e-4)
            checked++
        }
        assertTrue("expected many on-screen samples, got $checked", checked > 200)
    }

    @Test
    fun objectsBelowTheGeometricHorizonAreHiddenOnTheBackdropButStillProjectForGlow() {
        val c = camera(120.0)
        assertEquals(SkyProjection.OFF_CANVAS, c.objectPosition(180.0, -5.0, width, height, lat))
        assertFalse(c.project(180.0, -5.0, width, height, lat) == SkyProjection.OFF_CANVAS)
        assertEquals(height, c.clipBottomPx(height), 0f)
    }

    @Test
    fun objectsBehindTheCameraAreOffCanvas() {
        val c = camera(120.0)
        // The opposite of the view centre (south-facing camera at 40 degrees altitude) lies behind the camera.
        assertEquals(SkyProjection.OFF_CANVAS, c.project(0.0, -40.0, width, height, lat))
    }

    @Test
    fun moonLimbOnTheBackdropPointsAtTheSunOnScreen() {
        val c = camera(120.0)
        // Both bodies on the centre meridian, the Sun higher: the lit limb points straight up (canvas angle -90).
        val up = c.moonLimbAngleDeg(180.0, 20.0, 180.0, 60.0, width, height, lat)
        assertEquals(-90.0, up, 2.0)
        // Sun a little further west than the Moon at the same altitude: the lit limb points to the right.
        val right = c.moonLimbAngleDeg(180.0, 20.0, 200.0, 20.0, width, height, lat)
        assertTrue("expected a rightward limb, got $right", cos(Math.toRadians(right)) > 0.8)
    }

    @Test
    fun targetsChooseTheirLandscapeFramingAndProjection() {
        val frame = SkySceneModel.compute(TimeEngine.getJulianDate(1_700_000_000_000L), lat, 13.4, 0.0)
        assertTrue(SkyRenderTarget.HOME_HERO.drawsLandscape)
        assertFalse(SkyRenderTarget.APP_BACKDROP.drawsLandscape)
        assertSame(SkyPanoramaFraming.HOME_ZENITH, SkyRenderTarget.HOME_HERO.panoramaFraming)
        assertSame(SkyPanoramaFraming.APP_BACKDROP, SkyRenderTarget.APP_BACKDROP.panoramaFraming)
        assertSame(HeroProjection, SkyRenderTarget.HOME_HERO.projectionFor(frame))
        assertTrue(SkyRenderTarget.APP_BACKDROP.projectionFor(frame) is CameraProjection)
    }

    @Test
    fun theFullBleedSceneMovesTheHorizonAndKeepsTheSkyMapping() {
        val frame = SkySceneModel.compute(TimeEngine.getJulianDate(1_700_000_000_000L), lat, 13.4, 0.0)
        val scene = SkyRenderTarget.HOME_SCENE.projectionFor(frame)

        assertTrue(SkyRenderTarget.HOME_SCENE.drawsScene)
        assertFalse(SkyRenderTarget.HOME_HERO.drawsScene)
        assertEquals(900f * SkySceneLayout.HORIZON_FRACTION, scene.clipBottomPx(900f), 0f)
        assertEquals(900f * SkySceneLayout.HORIZON_FRACTION, scene.horizonY(400f, 900f), 0f)

        // The mapping is the same cylindrical Home mapping: only the row altitude 0° lands on has moved.
        val onHorizon = scene.project(180.0, 0.0, 400f, 900f, lat)
        assertEquals(400f * 0.5f, onHorizon.x, 0.01f)
        assertEquals(900f * SkySceneLayout.HORIZON_FRACTION, onHorizon.y, 0.01f)
        val zenith = scene.project(180.0, 89.0, 400f, 900f, lat)
        assertTrue("zenith must stay near the top", zenith.y < 900f * SkySceneLayout.HORIZON_FRACTION * 0.5f)

        // The composition agrees with the projection and puts the observer on, not above, the ground.
        val composition = SkySceneLayout.composition(400f, 900f)
        assertEquals(scene.horizonY(400f, 900f), composition.horizonY, 0.01f)
        assertTrue(composition.groundTopY > composition.horizonY)
        assertTrue(composition.figureFeetY > composition.groundTopY)
        assertTrue(composition.figureFeetY < composition.heightPx * 0.8f)
        assertTrue(composition.figureCenterX > 400f * 0.5f)
        assertTrue(SkySceneLayout.composition(400f, 900f, figureOnRight = false).figureCenterX < 400f * 0.5f)
    }

    @Test
    fun homeAndBackdropPanoramaStatesDifferOnlyByFraming() {
        val frame = SkySceneModel.compute(TimeEngine.getJulianDate(1_700_000_000_000L), lat, 13.4, 0.0)
        val home = SkyPanoramaState.fromSceneFrame(frame, SkyPanoramaFraming.HOME_ZENITH)
        val backdrop = SkyPanoramaState.fromSceneFrame(frame, SkyPanoramaFraming.APP_BACKDROP)
        assertEquals(home.lstDeg, backdrop.lstDeg, 0.0)
        assertEquals(home.sunAltDeg, backdrop.sunAltDeg, 0.0)
        assertNotEquals(home, backdrop)
        assertNotEquals(home.basis.forward.toList(), backdrop.basis.forward.toList())
    }
}
