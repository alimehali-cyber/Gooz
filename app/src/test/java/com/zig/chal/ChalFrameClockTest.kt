package com.zig.chal

import com.zig.chal.config.ChalPerformanceConfig
import com.zig.chal.render.ChalFrameClock
import org.junit.Assert.*
import org.junit.Test

class ChalFrameClockTest {
    private fun frames(refresh: Int, limit: Int): Int {
        val clock = ChalFrameClock().apply { reset(0.0) }
        val budget = ChalPerformanceConfig.Mobile.targetFrameTimeMs(refresh.toDouble(), limit)
        return (1..refresh).count { clock.frameDelta(it * 1000.0 / refresh, budget) != null }
    }
    @Test fun sixtyHzDoesNotStarveAtThirtyFps() { assertEquals(30, frames(60, 30)) }
    @Test fun ninetyHzAdmitsFortyFiveFrames() { assertEquals(45, frames(90, 60)) }
    @Test fun oneTwentyHzAdmitsSixtyFrames() { assertEquals(60, frames(120, 60)) }
    @Test fun oneFortyFourHzAdmitsFortyEightFrames() { assertEquals(48, frames(144, 60)) }
    @Test fun oneSixtyFiveHzAdmitsFiftyFiveFrames() { assertEquals(55, frames(165, 60)) }
    @Test fun sixtyHzKeepsSixtyFrames() { assertEquals(60, frames(60, 60)) }
    @Test fun skippedVsyncsAccumulate() {
        val clock = ChalFrameClock().apply { reset(0.0) }
        assertNull(clock.frameDelta(8.3, 16.67))
        assertEquals(16.7, clock.frameDelta(16.7, 16.67)!!, 1e-9)
    }
    @Test fun pausedEditsCanForceAnImmediateFrame() {
        val clock = ChalFrameClock().apply { reset(0.0) }
        assertEquals(2.0, clock.frameDelta(2.0, 33.33, force = true)!!, 0.0)
    }
    @Test fun lifecycleResetExcludesBackgroundTime() {
        val clock = ChalFrameClock().apply { reset(0.0); reset(90_000.0) }
        assertNull(clock.frameDelta(90_008.0, 16.67))
        assertEquals(17.0, clock.frameDelta(90_017.0, 16.67)!!, 0.0)
    }
    @Test fun slowFramesKeepTheirRealIntervalForFps() {
        val clock = ChalFrameClock().apply { reset(0.0) }
        assertEquals(250.0, clock.frameDelta(250.0, 16.67)!!, 0.0)
    }
    @Test fun badBudgetsAndTimestampsAreIgnored() {
        val clock = ChalFrameClock()
        assertNull(clock.frameDelta(Double.NaN, 16.67))
        assertNull(clock.frameDelta(100.0, 0.0))
        assertNull(clock.frameDelta(100.0, Double.POSITIVE_INFINITY))
    }
    @Test fun remainingTimeCountsDownToZeroAndMatchesAdmission() {
        val clock = ChalFrameClock().apply { reset(0.0) }
        assertEquals(16.67 * 0.95, clock.remainingMs(0.0, 16.67), 1e-9)
        assertEquals(16.67 * 0.95 - 8.0, clock.remainingMs(8.0, 16.67), 1e-9)
        assertEquals(0.0, clock.remainingMs(15.9, 16.67), 0.0)
        assertNull(clock.frameDelta(clock.remainingMs(8.0, 16.67) + 8.0 - 0.01, 16.67))
        assertNotNull(clock.frameDelta(clock.remainingMs(8.0, 16.67) + 8.0 + 0.01, 16.67))
    }
    @Test fun remainingTimeIsZeroForAnUnsetClockBackwardTimeOrBadInput() {
        val clock = ChalFrameClock()
        assertEquals(0.0, clock.remainingMs(10.0, 16.67), 0.0)
        clock.reset(100.0)
        assertEquals(0.0, clock.remainingMs(50.0, 16.67), 0.0)
        assertEquals(0.0, clock.remainingMs(Double.NaN, 16.67), 0.0)
        assertEquals(0.0, clock.remainingMs(110.0, Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, clock.remainingMs(110.0, 0.0), 0.0)
    }
}
