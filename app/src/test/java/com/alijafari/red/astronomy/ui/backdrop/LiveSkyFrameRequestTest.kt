package com.alijafari.red.astronomy.ui.backdrop

import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.ui.rendering.SkyTimeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSkyFrameRequestTest {

    private val base = 1_700_000_000_000L
    private val minute = 60_000L

    private fun request(
        visible: Boolean = true,
        baseTimeMs: Long = base,
        dragOffsetHours: Float = 0f,
        lat: Double = 35.6892,
        lon: Double = 51.3890,
        elev: Double = 1100.0
    ) = LiveSkyFrameRequest(visible, baseTimeMs, dragOffsetHours, lat, lon, elev)

    @Test
    fun zeroOffsetRequestUsesTheBaseInstantExactly() {
        // With no drag, the backdrop's instant is the same instant the Home hero computes for the base time.
        assertEquals(TimeEngine.getJulianDate(base), request().julianDate, 0.0)
    }

    @Test
    fun requestJulianDateIsTheSameFormulaAsHome() {
        val offset = -4.25f
        assertEquals(SkyTimeModel.effectiveJd(base, offset), request(dragOffsetHours = offset).julianDate, 0.0)
    }

    @Test
    fun dragOffsetMovesTheEffectiveInstantAndTheRequest() {
        val dragged = request(dragOffsetHours = 6f)
        assertNotEquals(request(), dragged)
        assertEquals(TimeEngine.getJulianDate(base) + 0.25, dragged.julianDate, 1e-9)
    }

    @Test
    fun everyDragStepProducesADifferentRequest() {
        // A drag or return animation changes the offset in small steps. Each step must reach the producer.
        val steps = listOf(0f, 0.1f, 0.2f, 0.3f)
        val requests = steps.map { request(dragOffsetHours = it) }
        assertEquals(requests.size, requests.toSet().size)
    }

    @Test
    fun identicalInputsProduceAnEqualRequest() {
        // Equal requests do not restart the producer, so an unchanged sky does no work.
        assertEquals(request(dragOffsetHours = 2f), request(dragOffsetHours = 2f))
    }

    @Test
    fun liveClockTicksAreNotQuantisedAwayFromTheHomeInstant() {
        // The base is used as given. Home and the backdrop see the same clock value, so no extra rounding is applied.
        val tick = base + 1_000L
        assertEquals(TimeEngine.getJulianDate(tick), request(baseTimeMs = tick).julianDate, 0.0)
        assertNotEquals(request(baseTimeMs = base), request(baseTimeMs = tick))
    }

    @Test
    fun simulationBaseIsUsedWhenTheTimeMachineIsActive() {
        val simulated = base - 5 * minute
        val baseTime = SkyTimeModel.baseTimeMs(isSimulation = true, simulationTimeMs = simulated, liveTimeMs = base)
        assertEquals(simulated, baseTime)
        assertEquals(TimeEngine.getJulianDate(simulated), request(baseTimeMs = baseTime).julianDate, 0.0)
    }

    @Test
    fun observerChangesProduceANewRequest() {
        assertNotEquals(request(), request(lat = 51.5074))
        assertNotEquals(request(), request(lon = 0.1278))
        assertNotEquals(request(), request(elev = 5.0))
    }

    @Test
    fun onlyVisibleRequestsNeedAComputation() {
        assertTrue(request(visible = true).needsCompute)
        assertFalse(request(visible = false).needsCompute)
    }
}
