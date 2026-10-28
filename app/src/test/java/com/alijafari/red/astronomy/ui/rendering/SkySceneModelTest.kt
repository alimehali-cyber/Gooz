package com.alijafari.red.astronomy.ui.rendering

import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.astro_engine.SunEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class SkySceneModelTest {

    private val londonLat = 51.5074
    private val londonLon = -0.1278
    private val londonElev = 35.0

    // 2024-06-21 12:00 UTC, near local solar noon at Greenwich, so the Sun is high in the sky at 51.5°N.
    private val solsticeNoonJd: Double = TimeEngine.getJulianDate(Instant.parse("2024-06-21T12:00:00Z").toEpochMilli())

    @Test
    fun quantizeTimeFloorsToTheMinute() {
        val minute = SkySceneModel.TIME_QUANTUM_MS
        assertEquals(minute * 2, SkySceneModel.quantizeTimeMs(minute * 2 + minute - 1))
        assertEquals(minute * 2, SkySceneModel.quantizeTimeMs(minute * 2))
        // Negative timestamps floor toward negative infinity rather than toward zero.
        assertEquals(-minute, SkySceneModel.quantizeTimeMs(-1L))
    }

    @Test
    fun sunInTheSharedModelMatchesTheEnginesDirectly() {
        val frame = SkySceneModel.compute(solsticeNoonJd, londonLat, londonLon, londonElev)

        val sun = SunEngine.calculatePosition(solsticeNoonJd)
        val expected = CoordinateEngine.equatorialToHorizontal(
            CoordinateEngine.Equatorial(sun.raDeg, sun.decDeg),
            TimeEngine.getLAST(solsticeNoonJd, londonLon),
            londonLat,
            londonElev
        )
        assertEquals(expected.altitudeDeg, frame.sunHoriz.altitudeDeg, 1e-9)
        assertEquals(expected.azimuthDeg, frame.sunHoriz.azimuthDeg, 1e-9)
        assertEquals(TimeEngine.getLAST(solsticeNoonJd, londonLon), frame.lastDeg, 1e-9)
    }

    @Test
    fun sunIsHighAtSolsticeNoonInLondon() {
        // Expected altitude is about 62°. The bounds are loose on purpose: this checks the chain end to end, not precision.
        val frame = SkySceneModel.compute(solsticeNoonJd, londonLat, londonLon, londonElev)
        assertTrue("sun altitude=${frame.sunHoriz.altitudeDeg}", frame.sunHoriz.altitudeDeg in 50.0..70.0)
    }

    @Test
    fun drawnFiltersMatchTheHomeCanvasRules() {
        val frame = SkySceneModel.compute(solsticeNoonJd, londonLat, londonLon, londonElev)

        for ((obj, horiz) in frame.catalogStars) {
            assertTrue("star ${obj.id} below horizon", horiz.altitudeDeg > 0.0)
            assertTrue("star ${obj.id} too faint", obj.magnitude <= 4.5)
        }
        for ((type, _, horiz) in frame.planetPositions) {
            assertTrue("Pluto must not be drawn", type != PlanetEngine.PlanetType.PLUTO)
            assertTrue("planet $type too low", horiz.altitudeDeg > -2.0)
        }
        for (point in frame.galacticPlanePoints) {
            assertTrue("galactic point too low", point.altitudeDeg > -5.0)
        }
    }

    @Test
    fun computeIsDeterministicAndSafeFromWorkerThreads() {
        // The backdrop computes on Dispatchers.Default while Home computes on the main thread. Both must agree.
        val reference = SkySceneModel.compute(solsticeNoonJd, londonLat, londonLon, londonElev)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = pool.invokeAll(
                List(8) {
                    Callable { SkySceneModel.compute(solsticeNoonJd, londonLat, londonLon, londonElev) }
                }
            ).map { it.get() }
            for (frame in results) {
                assertEquals(reference.sunHoriz, frame.sunHoriz)
                assertEquals(reference.moonData.illuminationPercent, frame.moonData.illuminationPercent, 0.0)
                assertEquals(reference.catalogStars.size, frame.catalogStars.size)
                assertEquals(reference.planetPositions.size, frame.planetPositions.size)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
