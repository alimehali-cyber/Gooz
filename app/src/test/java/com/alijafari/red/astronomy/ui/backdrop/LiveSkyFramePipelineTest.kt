package com.alijafari.red.astronomy.ui.backdrop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveSkyFramePipelineTest {

    /**
     * Collects [flow] for [advanceMs] of virtual time, then stops collecting. Sources here keep running, as a real
     * request stream does. `sample` does not emit a pending value when upstream completes, so a finite source would
     * hide its last request.
     */
    private fun <T> TestScope.collectFor(flow: Flow<T>, advanceMs: Long): List<T> {
        val out = mutableListOf<T>()
        val job = launch { flow.collect { out += it } }
        advanceTimeBy(advanceMs)
        runCurrent()
        job.cancel()
        return out
    }

    @Test
    fun equalConsecutiveRequestsAreComputedOnce() = runTest {
        var computes = 0
        val source = flow {
            repeat(5) { emit(1) }
            delay(10_000)
        }
        val out = collectFor(source.latestFrames(intervalMs = 10) { computes++; it }, advanceMs = 100)
        assertEquals(1, computes)
        assertEquals(listOf(1), out)
    }

    @Test
    fun rapidRequestsAreCoalescedToTheSampleRate() = runTest {
        var computes = 0
        // One new request every millisecond for 200 ms: a 60 FPS drag, far faster than the sample interval.
        val source = flow {
            for (i in 1..200) {
                emit(i)
                delay(1)
            }
            delay(10_000)
        }
        val out = collectFor(source.latestFrames(intervalMs = 33) { computes++; it }, advanceMs = 250)
        assertTrue("expected at most ~7 computations, got $computes", computes <= 8)
        assertTrue(out.isNotEmpty())
        assertEquals(out.sorted(), out)
    }

    @Test
    fun supersededComputationsAreCancelledAndNeverEmitted() = runTest {
        val completed = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val source = flow {
            for (i in 1..10) {
                emit(i)
                delay(10)
            }
            delay(10_000)
        }
        // Each computation takes 50 ms, but a new request arrives every 10 ms, so almost every one is superseded.
        val out = collectFor(
            source.latestFrames(intervalMs = 10) { request ->
                try {
                    delay(50)
                    completed += request
                    request
                } catch (e: CancellationException) {
                    cancelled += request
                    throw e
                }
            },
            advanceMs = 600
        )
        assertTrue("superseded work must be cancelled", cancelled.isNotEmpty())
        // A stale result is never emitted after a newer one.
        assertEquals(out.sorted(), out)
        assertEquals(out.distinct(), out)
        // The newest request is not superseded, so it finishes and is the last frame shown.
        assertEquals(10, out.last())
        assertTrue(completed.all { it in out })
    }

    @Test
    fun aFailedComputationKeepsTheLastFrameAndEmitsNothingForIt() = runTest {
        val source = flow {
            emit(1)
            delay(50)
            emit(2)
            delay(50)
            emit(3)
            delay(10_000)
        }
        // 2 fails (null). It must not be emitted, so the frame for 1 stays on screen until 3 is ready.
        val out = collectFor(source.latestFrames(intervalMs = 33) { if (it == 2) null else it }, advanceMs = 300)
        assertEquals(listOf(1, 3), out)
    }

    @Test
    fun noComputationRunsWithoutRequests() = runTest {
        var computes = 0
        val out = collectFor(
            flow<Int> { delay(1_000) }.latestFrames(intervalMs = 33) { computes++; it },
            advanceMs = 900
        )
        assertFalse(out.isNotEmpty())
        assertEquals(0, computes)
    }
}
