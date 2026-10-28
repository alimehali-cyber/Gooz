package com.alijafari.red.astronomy.ui.backdrop

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkyLiveClockTest {

    @Test
    fun noSubscriberMeansNoClockReads() = runTest {
        var reads = 0
        SkyLiveClock(backgroundScope, nowMs = { reads++; 0L })
        advanceTimeBy(10_000)
        runCurrent()
        // The only read is the initial value at construction. With no collector the clock never ticks.
        assertEquals(1, reads)
    }

    @Test
    fun subscribedClockTicksOncePerSecondAndReadsTheSharedValue() = runTest {
        var now = 1_000L
        val clock = SkyLiveClock(backgroundScope, nowMs = { now })
        val seen = mutableListOf<Long>()
        val job = launch { clock.timeMs.collect { seen += it } }
        runCurrent()
        now = 2_000L
        advanceTimeBy(1_000)
        runCurrent()
        now = 3_000L
        advanceTimeBy(1_000)
        runCurrent()
        job.cancel()
        assertTrue(seen.contains(2_000L))
        assertTrue(seen.contains(3_000L))
    }

    @Test
    fun unsubscribingStopsTheClock() = runTest {
        var reads = 0
        val clock = SkyLiveClock(backgroundScope, nowMs = { reads++; reads.toLong() })
        val job = launch { clock.timeMs.collect { } }
        advanceTimeBy(3_000)
        runCurrent()
        job.cancel()
        runCurrent()
        val readsAtStop = reads
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("no reads may happen once the last collector has left", readsAtStop, reads)
    }
}
