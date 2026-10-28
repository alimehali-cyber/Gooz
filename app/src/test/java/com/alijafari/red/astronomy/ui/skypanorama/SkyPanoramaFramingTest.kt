package com.alijafari.red.astronomy.ui.skypanorama

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Framing and direction checks for the panorama camera. The Home framing must stay as it was. The backdrop framing
 * must be orthonormal and face the equator side of the sky. Directions must invert the engine exactly, because that is
 * what keeps the objects on the photograph.
 */
class SkyPanoramaFramingTest {

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    @Test
    fun homeFramingKeepsTheZenithCameraAndItsSixtyDegreeField() {
        assertEquals(60f, SkyPanoramaFraming.HOME_ZENITH.fovYDeg, 0f)
        for (lst in listOf(0.0, 73.4, 301.2)) {
            for (lat in listOf(-50.0, 0.0, 45.31)) {
                val framed = SkyPanoramaFraming.HOME_ZENITH.basis(lst, lat)
                val legacy = SkyPanoramaMath.zenithBasis(lst, lat)
                for (i in 0..2) {
                    assertEquals(legacy.forward[i], framed.forward[i], 0.0)
                    assertEquals(legacy.right[i], framed.right[i], 0.0)
                    assertEquals(legacy.up[i], framed.up[i], 0.0)
                }
            }
        }
    }

    @Test
    fun backdropFramingFacesTheEquatorSideAtFortyDegreesAndUsesAWideField() {
        assertEquals(104f, SkyPanoramaFraming.APP_BACKDROP.fovYDeg, 0f)
        val north = SkyPanoramaFraming.APP_BACKDROP.basis(120.0, 45.31).forward
        val expectedNorthern = SkyPanoramaMath.directionFromHorizontal(180.0, 40.0, 45.31, 120.0)
        assertTrue(dot(north, expectedNorthern) > 1.0 - 1e-12)
        // Southern observers face north, as Home does.
        val south = SkyPanoramaFraming.APP_BACKDROP.basis(120.0, -30.0).forward
        val expectedSouthern = SkyPanoramaMath.directionFromHorizontal(0.0, 40.0, -30.0, 120.0)
        assertTrue(dot(south, expectedSouthern) > 1.0 - 1e-12)
    }

    @Test
    fun backdropBasisIsOrthonormalAndItsRightSideIsThePhysicalSide() {
        val random = Random(3)
        repeat(500) {
            val lst = random.nextDouble() * 360.0
            val lat = -80.0 + random.nextDouble() * 160.0
            val b = SkyPanoramaFraming.APP_BACKDROP.basis(lst, lat)
            assertEquals(1.0, dot(b.forward, b.forward), 1e-9)
            assertEquals(1.0, dot(b.right, b.right), 1e-9)
            assertEquals(1.0, dot(b.up, b.up), 1e-9)
            assertEquals(0.0, dot(b.forward, b.right), 1e-9)
            assertEquals(0.0, dot(b.forward, b.up), 1e-9)
            assertEquals(0.0, dot(b.right, b.up), 1e-9)
            // Facing south in the north (right = west) and facing north in the south (right = east): the view is
            // the physical, non-mirrored sky seen by an observer standing at this latitude.
            val side = if (lat >= 0.0) SkyPanoramaMath.directionFromHorizontal(270.0, 0.0, lat, lst)
            else SkyPanoramaMath.directionFromHorizontal(90.0, 0.0, lat, lst)
            assertTrue("right must point to the physical west/east side at lat $lat", dot(b.right, side) > 1.0 - 1e-9)
        }
    }

    @Test
    fun backdropKeepsTheZenithAboveTheCentreInEveryHemisphere() {
        val random = Random(5)
        repeat(500) {
            val lst = random.nextDouble() * 360.0
            val lat = -80.0 + random.nextDouble() * 160.0
            val b = SkyPanoramaFraming.APP_BACKDROP.basis(lst, lat)
            val zenith = SkyPanoramaMath.directionFromHorizontal(0.0, 90.0, lat, lst)
            // The view centre sits 40 degrees up, so the zenith projects onto screen-up with cos(40 degrees).
            assertEquals(Math.cos(Math.toRadians(40.0)), dot(b.up, zenith), 1e-9)
        }
    }

    @Test
    fun engineDirectionsInvertToTheSameEquatorialPoint() {
        val random = Random(11)
        repeat(3000) {
            val ra = random.nextDouble() * 360.0
            val dec = -85.0 + random.nextDouble() * 170.0
            val lst = random.nextDouble() * 360.0
            val lat = -80.0 + random.nextDouble() * 160.0
            val h = GeometricHorizontal.of(ra, dec, lst, lat)
            val back = SkyPanoramaMath.directionFromHorizontal(h.azimuthDeg, h.altitudeDeg, lat, lst)
            val forward = SkyPanoramaMath.directionFromRaDec(ra, dec)
            assertTrue("ra=$ra dec=$dec lst=$lst lat=$lat", dot(back, forward) > 1.0 - 1e-9)
        }
    }

    @Test
    fun engineAltitudeDiffersFromGeometryOnlyByRefraction() {
        val random = Random(13)
        repeat(3000) {
            val ra = random.nextDouble() * 360.0
            val dec = -85.0 + random.nextDouble() * 170.0
            val lst = random.nextDouble() * 360.0
            val lat = -80.0 + random.nextDouble() * 160.0
            GeometricHorizontal.refractionIsTheOnlyDifference(ra, dec, lst, lat) { fail(it) }
        }
    }

    @Test
    fun panoramaStateEqualityFollowsTheFraming() {
        val a = SkyPanoramaState.fromSkyState(100.0, 45.0, -10.0, 0f)
        val sameHome = SkyPanoramaState.fromSkyState(100.0, 45.0, -10.0, 0f, SkyPanoramaFraming.HOME_ZENITH)
        val backdrop = SkyPanoramaState.fromSkyState(100.0, 45.0, -10.0, 0f, SkyPanoramaFraming.APP_BACKDROP)
        assertEquals(a, sameHome)
        assertTrue(a != backdrop)
        assertEquals(SkyPanoramaFraming.APP_BACKDROP, backdrop.framing)
    }

    @Test
    fun theRendererTakesItsFieldOfViewFromTheStateFraming() {
        val source = readSource("com/alijafari/red/astronomy/ui/skypanorama/SkyPanoramaRenderer.kt")
        assertTrue(
            "renderer must read the field of view from the state's framing",
            source.contains("current.framing.fovYDeg")
        )
        assertTrue("the framing must not depend on the config field", !source.contains("cfg.fovYDeg"))
    }

    private fun readSource(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "app/src/main/java/$relative")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Source not found: $relative")
        return file.readText()
    }
}
