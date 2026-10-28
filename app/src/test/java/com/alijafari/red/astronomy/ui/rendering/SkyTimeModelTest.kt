package com.alijafari.red.astronomy.ui.rendering

import com.alijafari.red.astronomy.astro_engine.TimeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTimeModelTest {

    private val live = 1_700_000_000_000L

    @Test
    fun liveModeUsesTheLiveClockAndSimulationModeUsesTheTimeMachine() {
        assertEquals(live, SkyTimeModel.baseTimeMs(isSimulation = false, simulationTimeMs = 42L, liveTimeMs = live))
        assertEquals(42L, SkyTimeModel.baseTimeMs(isSimulation = true, simulationTimeMs = 42L, liveTimeMs = live))
    }

    @Test
    fun zeroOffsetGivesTheBaseInstantForBothScenes() {
        // When the drag offset is zero, the Home hero and the backdrop compute the same Julian Date.
        assertEquals(TimeEngine.getJulianDate(live), SkyTimeModel.effectiveJd(live, 0f), 0.0)
    }

    @Test
    fun offsetIsAppliedInDaysFromHours() {
        assertEquals(TimeEngine.getJulianDate(live) + 0.5, SkyTimeModel.effectiveJd(live, 12f), 1e-9)
        assertEquals(TimeEngine.getJulianDate(live) - 0.5, SkyTimeModel.effectiveJd(live, -12f), 1e-9)
    }

    @Test
    fun effectiveJdUsesTheHomeArithmeticExactly() {
        // Home has always computed base JD + offset / 24.0. The shared function must produce the same double.
        val offset = 3.7f
        val homeStyle = TimeEngine.getJulianDate(live) + (offset / 24.0)
        assertEquals(homeStyle, SkyTimeModel.effectiveJd(live, offset), 0.0)
    }

    @Test
    fun dragRangeIsPlusMinusTwelveHours() {
        assertEquals(12f, SkyTimeModel.MAX_DRAG_OFFSET_HOURS, 0f)
    }

    @Test
    fun returnAnimationStepsMoveMonotonicallyBackToTheBaseInstant() {
        // The Home return animates the offset toward zero. Each step must move the sky the same way, and the last
        // step must land exactly on the base instant, so both scenes converge.
        val start = 9.0f
        val steps = (0..20).map { start * (1f - it / 20f) }
        val jds = steps.map { SkyTimeModel.effectiveJd(live, it) }
        for (i in 1 until jds.size) assertTrue(jds[i] <= jds[i - 1])
        assertEquals(TimeEngine.getJulianDate(live), jds.last(), 0.0)
    }

    @Test
    fun simulatedBaseWithOffsetKeepsTheSameOffsetSemantics() {
        val simulated = live - 86_400_000L
        val base = SkyTimeModel.baseTimeMs(isSimulation = true, simulationTimeMs = simulated, liveTimeMs = live)
        assertEquals(TimeEngine.getJulianDate(simulated) + 1.0, SkyTimeModel.effectiveJd(base, 24f), 1e-9)
    }
}
