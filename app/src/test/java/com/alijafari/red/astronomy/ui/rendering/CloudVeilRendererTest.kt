package com.alijafari.red.astronomy.ui.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the cloud veils' deterministic hash.
 *
 * Regression guard for the white band across the Home scene: the hash masks 31 bits,
 * so it must be scaled by 2^31 − 1. At 2^24 − 1 it returned 0..128 — the Y jitter
 * threw veils far off-canvas, the alpha factor ran up to ~32×, and one cirrus streak
 * landed just above the horizon, where the scene-motion clip at the horizon line
 * sliced it into a flat-topped glowing rectangle over the mountains.
 */
class CloudVeilRendererTest {

    @Test
    fun pseudoRandomStaysInUnitRangeAndIsDeterministic() {
        // Every seed the veil layers actually use (layer/ instance grids), and beyond.
        for (seed in -50..600) {
            val value = CloudVeilRenderer.pseudoRandom(seed)
            assertTrue("pseudoRandom in 0..1 (seed=$seed): $value", value in 0f..1f)
            assertEquals(value, CloudVeilRenderer.pseudoRandom(seed), 0f)
        }
    }

    @Test
    fun cirrusStreakThatFormedTheBandIsBackInItsLayer() {
        // Layer 0, instance 1 — the streak that formed the band. Its three hashes are
        // seeded at 37 (x), 17 (y jitter) and 53 (alpha); all were ≫ 1 before the fix:
        // y jitter 29.8 threw the sprite ~830 px down to the horizon, and the alpha
        // factor 23.5 drew it at 0.75 alpha instead of the intended ~0.03.
        assertTrue(CloudVeilRenderer.pseudoRandom(37) in 0f..1f)
        assertTrue(CloudVeilRenderer.pseudoRandom(17) in 0f..1f)
        assertTrue(CloudVeilRenderer.pseudoRandom(53) in 0f..1f)
        val alphaFactor = 0.75f + 0.25f * CloudVeilRenderer.pseudoRandom(53)
        assertTrue("alpha factor in 0.75..1: $alphaFactor", alphaFactor in 0.75f..1f)
        val yJitterFraction = (CloudVeilRenderer.pseudoRandom(17) - 0.5f) * 0.02f
        assertTrue("y jitter within ±1% of scene height: $yJitterFraction", yJitterFraction in -0.01f..0.01f)
    }
}
