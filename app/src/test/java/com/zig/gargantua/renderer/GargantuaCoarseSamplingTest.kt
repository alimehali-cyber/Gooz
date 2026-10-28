package com.zig.gargantua.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class GargantuaCoarseSamplingTest {
    @Test
    fun supportedModesAreExactlyTheSixUniformBlockSizes() {
        val supported = listOf(1, 2, 3, 4, 6, 8)
        assertEquals(supported, GargantuaCoarseSampling.supportedBlockSizes())
        assertEquals(1, GargantuaCoarseSampling.sanitizeBlockSize(1))
        assertEquals(2, GargantuaCoarseSampling.sanitizeBlockSize(2))
        assertEquals(3, GargantuaCoarseSampling.sanitizeBlockSize(3))
        assertEquals(4, GargantuaCoarseSampling.sanitizeBlockSize(4))
        assertEquals(6, GargantuaCoarseSampling.sanitizeBlockSize(6))
        assertEquals(8, GargantuaCoarseSampling.sanitizeBlockSize(8))
        assertEquals(1, GargantuaCoarseSampling.sanitizeBlockSize(0))
        assertEquals(1, GargantuaCoarseSampling.sanitizeBlockSize(5))
        assertEquals(1, GargantuaCoarseSampling.sanitizeBlockSize(7))
        assertEquals(1, GargantuaCoarseSampling.sanitizeBlockSize(-1))
        assertEquals(supported, supported.filter { GargantuaCoarseSampling.sanitizeBlockSize(it) == it })
    }

    @Test
    fun evenInternalDimensionsHaveExpectedUniformGridDimensions() {
        val baseline = GargantuaCoarseSampling.grid(540, 1200, 1)
        val two = GargantuaCoarseSampling.grid(540, 1200, 2)
        val three = GargantuaCoarseSampling.grid(540, 1200, 3)
        val four = GargantuaCoarseSampling.grid(540, 1200, 4)
        val six = GargantuaCoarseSampling.grid(540, 1200, 6)
        val eight = GargantuaCoarseSampling.grid(540, 1200, 8)

        assertEquals(540, baseline.rayWidth)
        assertEquals(1200, baseline.rayHeight)
        assertEquals(270, two.rayWidth)
        assertEquals(600, two.rayHeight)
        assertEquals(180, three.rayWidth)
        assertEquals(400, three.rayHeight)
        assertEquals(135, four.rayWidth)
        assertEquals(300, four.rayHeight)
        assertEquals(90, six.rayWidth)
        assertEquals(200, six.rayHeight)
        assertEquals(68, eight.rayWidth)
        assertEquals(150, eight.rayHeight)

        assertEquals(1.0f, baseline.primaryRayReductionVsBaseline, 0.001f)
        assertEquals(4.0f, two.primaryRayReductionVsBaseline, 0.001f)
        assertEquals(9.0f, three.primaryRayReductionVsBaseline, 0.001f)
        assertEquals(16.0f, four.primaryRayReductionVsBaseline, 0.001f)
        assertEquals(36.0f, six.primaryRayReductionVsBaseline, 0.001f)
        assertEquals(63.529f, eight.primaryRayReductionVsBaseline, 0.01f)
    }

    @Test
    fun oddDimensionsRoundUpEveryUniformGridWithoutChangingInternalTarget() {
        val three = GargantuaCoarseSampling.grid(541, 1201, 3)
        val six = GargantuaCoarseSampling.grid(541, 1201, 6)

        assertEquals(541, three.internalWidth)
        assertEquals(1201, three.internalHeight)
        assertEquals(181, three.rayWidth)
        assertEquals(401, three.rayHeight)
        assertEquals(91, six.rayWidth)
        assertEquals(201, six.rayHeight)
        assertEquals(541L * 1201L, three.internalPixels)
        assertEquals(91L * 201L, six.shadedBlocks)
    }
}
