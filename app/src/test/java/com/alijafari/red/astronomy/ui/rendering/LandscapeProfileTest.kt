package com.alijafari.red.astronomy.ui.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contracts for the photographic landscape's profile math: every value bounded, every
 * skyline deterministic (the ground must never shimmer between clock ticks), envelopes
 * honouring their control points, and mountain heights forming a continuous crested wall
 * rather than noise soup.
 */
class LandscapeProfileTest {

    @Test
    fun hashAndNoiseStayInRangeAndAreDeterministic() {
        for (seed in listOf(0, 3, 11, 29, 317, -7)) {
            for (i in -50..500) {
                val h = LandscapeProfile.hash01(seed, i)
                assertTrue("hash01 in 0..1 (seed=$seed i=$i): $h", h in 0f..1f)
                assertEquals(h, LandscapeProfile.hash01(seed, i), 0f)
            }
            var x = -4f
            while (x < 40f) {
                val n = LandscapeProfile.valueNoise(x, seed)
                assertTrue("valueNoise in -1..1 (seed=$seed x=$x): $n", n in -1f..1f)
                assertEquals(n, LandscapeProfile.valueNoise(x, seed), 0f)
                val f = LandscapeProfile.fbm(x * 0.31f, seed)
                assertTrue("fbm in -1..1 (seed=$seed x=$x): $f", f in -1f..1f)
                x += 0.37f
            }
        }
    }

    @Test
    fun valueNoiseIsContinuousAcrossLatticePoints() {
        for (k in -3..12) {
            val before = LandscapeProfile.valueNoise(k - 1e-4f, 11)
            val at = LandscapeProfile.valueNoise(k.toFloat(), 11)
            assertTrue("continuous at lattice $k", abs(at - before) < 0.01f)
        }
    }

    @Test
    fun ridgedStaysInUnitRangeAndReachesCrestedPeaks() {
        var maxSeen = 0f
        var p = 0f
        while (p <= 1f) {
            val r = LandscapeProfile.ridged(p, seed = 29)
            assertTrue("ridged in 0..1 (p=$p): $r", r in 0f..1f)
            assertEquals(r, LandscapeProfile.ridged(p, seed = 29), 0f)
            if (r > maxSeen) maxSeen = r
            p += 0.004f
        }
        assertTrue("ridged actually crests (max=$maxSeen)", maxSeen > 0.55f)
    }

    @Test
    fun envelopeHitsControlPointsAndHoldsItsEnds() {
        val pts = floatArrayOf(0f, 0.3f, 0.4f, 0.9f, 1f, 0.5f)
        assertEquals(0.3f, LandscapeProfile.envelope(-0.2f, pts), 1e-4f)
        assertEquals(0.3f, LandscapeProfile.envelope(0f, pts), 1e-4f)
        assertEquals(0.9f, LandscapeProfile.envelope(0.4f, pts), 1e-4f)
        assertEquals(0.5f, LandscapeProfile.envelope(1f, pts), 1e-4f)
        assertEquals(0.5f, LandscapeProfile.envelope(1.7f, pts), 1e-4f)
        // Smoothstep segments are monotonic between their control points.
        var prev = LandscapeProfile.envelope(0f, pts)
        var p = 0.02f
        while (p <= 0.4f) {
            val v = LandscapeProfile.envelope(p, pts)
            assertTrue("rising segment monotonic at $p", v >= prev - 1e-6f)
            prev = v
            p += 0.02f
        }
    }

    @Test
    fun mountainHeightsFormAContinuousWallWithinTheEnvelope() {
        val envelope = floatArrayOf(0f, 0.3f, 0.6f, 0.8f, 0.8f, 1f, 1f, 0.7f)
        val heights = LandscapeProfile.mountainHeights(140, 240f, envelope, seed = 29)
        assertEquals(141, heights.size)
        for (i in heights.indices) {
            val p = i / 140f
            val ceiling = 240f * LandscapeProfile.envelope(p, envelope)
            assertTrue("height positive at $i", heights[i] > 0f)
            assertTrue(
                "height under envelope ceiling at $i (${heights[i]} <= $ceiling)",
                heights[i] <= ceiling + 0.01f
            )
            assertTrue(
                "wall never collapses to the plain at $i",
                heights[i] >= ceiling * 0.40f - 0.01f
            )
        }
        // The crest at the envelope's peak must clear the shoulder decisively.
        val shoulder = heights.copyOfRange(0, 20).max()
        val crest = heights.copyOfRange(100, 125).max()
        assertTrue("crest ($crest) towers over shoulder ($shoulder)", crest > shoulder * 1.5f)
        // Height sampling agrees with the array at its nodes.
        assertEquals(heights[70], LandscapeProfile.heightAt(heights, 0.5f), 0.01f)
        assertEquals(heights.first(), LandscapeProfile.heightAt(heights, -1f), 0.01f)
        assertEquals(heights.last(), LandscapeProfile.heightAt(heights, 2f), 0.01f)
    }

    @Test
    fun foothillHeightsAreSmoothComparedToTheMassif() {
        val envelope = floatArrayOf(0f, 0.7f, 0.5f, 1f, 1f, 0.5f)
        val foothills = LandscapeProfile.foothillHeights(80, 26f, envelope, seed = 77)
        assertEquals(81, foothills.size)
        var maxStep = 0f
        for (i in 1 until foothills.size) {
            maxStep = maxOf(maxStep, abs(foothills[i] - foothills[i - 1]))
        }
        assertTrue("foothills undulate gently (max step $maxStep)", maxStep < 26f * 0.12f)
        assertTrue(foothills.all { it in 0f..26f })
    }
}
