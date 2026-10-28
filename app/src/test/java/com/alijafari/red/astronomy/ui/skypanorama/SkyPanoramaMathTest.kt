package com.alijafari.red.astronomy.ui.skypanorama

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Orientation, wrapping and LOD checks for the panorama. These are pure JVM tests; they mirror the
 * GLSL mapping in [SkyPanoramaShaders] so the convention is checked without a GPU.
 */
class SkyPanoramaMathTest {

    private val tol = 1e-6

    private fun uv(raDeg: Double, decDeg: Double): DoubleArray {
        val d = SkyPanoramaMath.directionFromRaDec(raDeg, decDeg)
        return SkyPanoramaMath.uvForDirection(d[0], d[1], d[2])
    }

    /** Wrap-aware distance on the horizontal (u) axis. */
    private fun wrapDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 1.0
        return minOf(d, 1.0 - d)
    }

    @Test
    fun zenithAtLstZeroEquatorIsXAxisWithWestOnRightAndNorthUp() {
        val basis = SkyPanoramaMath.zenithBasis(0.0, 0.0)
        assertVec(doubleArrayOf(1.0, 0.0, 0.0), basis.forward)
        assertVec(doubleArrayOf(0.0, -1.0, 0.0), basis.right)  // west is screen right
        assertVec(doubleArrayOf(0.0, 0.0, 1.0), basis.up)      // north celestial pole is screen up
    }

    @Test
    fun basisIsOrthonormalForArbitraryObservers() {
        val samples = listOf(-60.0 to 0.0, 0.0 to 30.1141, 123.4 to -33.8688, 250.0 to 64.0, 359.9 to 89.0)
        for ((lst, lat) in samples) {
            val b = SkyPanoramaMath.zenithBasis(lst, lat)
            assertEquals(1.0, dot(b.forward, b.forward), tol)
            assertEquals(1.0, dot(b.right, b.right), tol)
            assertEquals(1.0, dot(b.up, b.up), tol)
            assertEquals(0.0, dot(b.forward, b.right), tol)
            assertEquals(0.0, dot(b.forward, b.up), tol)
            assertEquals(0.0, dot(b.right, b.up), tol)
        }
    }

    @Test
    fun zenithDeclinationEqualsLatitudeAndNorthIsUpInNorthernHemisphere() {
        val lat = 30.0
        val b = SkyPanoramaMath.zenithBasis(0.0, lat)
        // Zenith is at Dec = latitude.
        assertEquals(Math.toRadians(lat), Math.asin(b.forward[2]), 1e-9)
        // Screen up points toward the north celestial pole, so its z component is positive.
        assertTrue(b.up[2] > 0.0)
    }

    @Test
    fun raDecReferencePointsMapToNasaCelestialTexture() {
        // Centre column is RA 0h, the equator is the middle row.
        assertUv(0.5, 0.5, uv(0.0, 0.0))
        // RA increases to the LEFT: 6h sits at u = 0.25, 18h at u = 0.75.
        assertUv(0.25, 0.5, uv(90.0, 0.0))
        assertUv(0.75, 0.5, uv(270.0, 0.0))
        // Declination +90 is the top row (v = 0), -90 is the bottom row (v = 1).
        assertEquals(0.0, uv(0.0, 90.0)[1], 1e-9)
        assertEquals(1.0, uv(0.0, -90.0)[1], 1e-9)
    }

    @Test
    fun galacticCentreLandsInTheLowerRightQuadrantOfTheCelestialMap() {
        // Sgr A* is near RA 266.405 deg, Dec -28.936 deg. With RA increasing to the left, it is
        // 94 deg to the right of the 0h centre column and below the equator.
        val gc = uv(266.405, -28.936)
        assertEquals(0.760, gc[0], 0.001)
        assertEquals(0.661, gc[1], 0.001)
    }

    @Test
    fun screenRightIsWestAndSamplesMoveRightInTheTexture() {
        val basis = SkyPanoramaMath.zenithBasis(0.0, 0.0)
        val tanHalf = Math.tan(Math.toRadians(30.0))
        val rightPixel = SkyPanoramaMath.cameraRay(basis, 0.5, 0.0, tanHalf, tanHalf)
        val leftPixel = SkyPanoramaMath.cameraRay(basis, -0.5, 0.0, tanHalf, tanHalf)
        val uvRight = SkyPanoramaMath.uvForDirection(rightPixel[0], rightPixel[1], rightPixel[2])
        val uvLeft = SkyPanoramaMath.uvForDirection(leftPixel[0], leftPixel[1], leftPixel[2])
        // Right of the screen means decreasing RA, which is larger u in this map.
        assertTrue("right pixel should sample larger u", uvRight[0] > 0.5 && uvLeft[0] < 0.5)
    }

    @Test
    fun screenUpIsNorthAndSamplesMoveUpInTheTexture() {
        val basis = SkyPanoramaMath.zenithBasis(0.0, 0.0)
        val tanHalf = Math.tan(Math.toRadians(30.0))
        val top = SkyPanoramaMath.cameraRay(basis, 0.0, 0.5, tanHalf, tanHalf)
        val bottom = SkyPanoramaMath.cameraRay(basis, 0.0, -0.5, tanHalf, tanHalf)
        val vTop = SkyPanoramaMath.uvForDirection(top[0], top[1], top[2])[1]
        val vBottom = SkyPanoramaMath.uvForDirection(bottom[0], bottom[1], bottom[2])[1]
        assertTrue("top of screen should sample smaller v (north)", vTop < 0.5 && vBottom > 0.5)
    }

    @Test
    fun centreOfViewSamplesTheZenithAndFollowsSiderealRotation() {
        // Zenith at LST 0, lat 0 is RA 0h / Dec 0: the exact centre of the map.
        val at0 = SkyPanoramaMath.zenithBasis(0.0, 0.0)
        val c0 = SkyPanoramaMath.uvForDirection(at0.forward[0], at0.forward[1], at0.forward[2])
        assertUv(0.5, 0.5, c0)

        // Sidereal time advances 15 deg per hour. The zenith moves eastward, so the sampled centre
        // moves LEFT on the map: u = 0.5 - 15/360.
        val at15 = SkyPanoramaMath.zenithBasis(15.0, 0.0)
        val c15 = SkyPanoramaMath.uvForDirection(at15.forward[0], at15.forward[1], at15.forward[2])
        assertEquals(0.5 - 15.0 / 360.0, c15[0], 1e-9)
    }

    @Test
    fun horizontalWrapIsContinuousAcrossTheRaBranchCut() {
        // RA +179.9 and -179.9 are neighbours on the sphere, and must be neighbours on the texture.
        val east = uv(179.9, 10.0)[0]
        val west = uv(-179.9, 10.0)[0]
        assertTrue("seam distance ${wrapDistance(east, west)}", wrapDistance(east, west) < 0.002)
        // Both sides must remain inside the texture's [0, 1) range after fract().
        assertTrue(east >= 0.0 && east < 1.0)
        assertTrue(west >= 0.0 && west < 1.0)
    }

    @Test
    fun texelLodIsZeroForNearOneToOneSamplingAndGrowsForSmallViewports() {
        val fov = Math.toRadians(60.0)
        assertEquals(0f, SkyPanoramaMath.texelLod(4096, fov, 960), 1e-6f)
        assertTrue(SkyPanoramaMath.texelLod(4096, fov, 300) > 1.0f)
        assertEquals(0f, SkyPanoramaMath.texelLod(0, fov, 300), 0f)
    }

    @Test
    fun quantizeLstWrapsAndSnapsToTheQuantum() {
        assertEquals(0.0, SkyPanoramaMath.quantizeLst(359.99), 1e-9)
        assertEquals(10.05, SkyPanoramaMath.quantizeLst(10.04), 1e-9)
        assertEquals(12.0, SkyPanoramaMath.quantizeLst(12.0), 1e-9)
        assertEquals(12.0, SkyPanoramaMath.quantizeLst(-348.0), 1e-9)
    }

    @Test
    fun polarObserverDoesNotProduceNaNBasis() {
        val b = SkyPanoramaMath.zenithBasis(0.0, 90.0)
        for (vec in listOf(b.forward, b.right, b.up)) {
            assertTrue(vec.all { !it.isNaN() })
        }
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun assertVec(expected: DoubleArray, actual: DoubleArray) {
        for (i in 0..2) assertEquals("component $i", expected[i], actual[i], 1e-9)
    }

    private fun assertUv(u: Double, v: Double, actual: DoubleArray) {
        assertEquals("u", u, actual[0], 1e-6)
        assertEquals("v", v, actual[1], 1e-6)
    }
}
