package com.zig.gargantua.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class GargantuaRenderReadyGateTest {
    @Test
    fun emitsOneRequestOnlyAfterResourcesAndNonEmptySurfaceAreReady() {
        var requests = 0
        val gate = GargantuaRenderReadyGate { requests++ }

        gate.markSurfaceSize(1080, 2400)
        assertEquals("A size without GLES resources must not request a frame", 0, requests)

        gate.markResourcesReady()
        assertEquals("The ready handshake must request the first frame", 1, requests)

        gate.markResourcesReady()
        gate.markSurfaceSize(1080, 2400)
        assertEquals("Repeated ready callbacks must not create a render loop", 1, requests)
    }

    @Test
    fun resetAllowsSameSizeEglRecreationToRequestAnotherFirstFrame() {
        var requests = 0
        val gate = GargantuaRenderReadyGate { requests++ }

        gate.markSurfaceSize(1080, 2400)
        gate.markResourcesReady()
        assertEquals(1, requests)

        gate.reset()
        gate.markSurfaceSize(1080, 2400)
        gate.markResourcesReady()
        assertEquals("A new EGL generation must get a new first presentation", 2, requests)
    }

    @Test
    fun zeroSizedSurfaceDoesNotTriggerUntilItBecomesPresentable() {
        var requests = 0
        val gate = GargantuaRenderReadyGate { requests++ }

        gate.markResourcesReady()
        gate.markSurfaceSize(0, 0)
        assertEquals(0, requests)

        gate.markSurfaceSize(1080, 2400)
        assertEquals(1, requests)
    }
}
