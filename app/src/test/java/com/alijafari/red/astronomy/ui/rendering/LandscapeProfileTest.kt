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
    fun benchesTerraceAndNotchesCarveWithoutBreakingBounds() {
        val envelope = floatArrayOf(0f, 0.6f, 0.5f, 1f, 1f, 0.8f)
        val plain = LandscapeProfile.mountainHeights(140, 240f, envelope, seed = 29)
        val benched = LandscapeProfile.mountainHeights(
            140, 240f, envelope, seed = 29, benchAmp = 0.5f, benchStep = 0.14f
        )
        assertTrue(benched.all { it in 0f..240f })
        // Terracing measurably changes the profile but never by more than a bench step.
        var changed = 0
        for (i in plain.indices) {
            val d = abs(benched[i] - plain[i])
            assertTrue("bench shift bounded at $i: $d", d <= 240f * 0.14f)
            if (d > 0.5f) changed++
        }
        assertTrue("benches actually bite ($changed samples)", changed > 20)

        val notched = LandscapeProfile.mountainHeights(
            140, 240f, envelope, seed = 29,
            notches = floatArrayOf(0.5f, 0.03f, 0.4f)
        )
        val atNotch = notched[70]
        assertTrue("notch carves the crest ($atNotch vs ${plain[70]})", atNotch < plain[70] * 0.75f)
        // …and heals away from the water-gap.
        assertEquals(plain[10], notched[10], plain[10] * 0.02f + 0.01f)
        assertTrue(notched.all { it >= 0f })
    }

    @Test
    fun treeSkeletonsAreDeterministicBoundedAndPipeShaped() {
        val a = LandscapeProfile.treeSkeleton(seed = 101, aspect = 1.3f, trunkFraction = 0.36f, attractorCount = 90)
        val b = LandscapeProfile.treeSkeleton(seed = 101, aspect = 1.3f, trunkFraction = 0.36f, attractorCount = 90)
        assertEquals("same seed, same tree", a, b)
        val other = LandscapeProfile.treeSkeleton(seed = 202, aspect = 1.3f, trunkFraction = 0.36f, attractorCount = 90)
        assertTrue("different seed, different tree", a != other)

        assertTrue("skeleton has substance (${a.size} segments)", a.size in 40..440)
        assertTrue("terminals exist for foliage", a.count { it.terminal } >= 15)
        for (s in a) {
            assertTrue("x within crown (${s.x1})", abs(s.x1) <= 1.3f * 0.75f)
            assertTrue("y within tree (${s.y1})", s.y1 in -0.01f..1.06f)
            assertTrue("radii positive", s.r0 > 0f && s.r1 > 0f)
            assertTrue("child never fatter than parent (pipe model)", s.r1 <= s.r0 * 1.06f + 0.001f)
        }
        val trunk = a.first()
        assertEquals(0f, trunk.x0, 0f)
        assertEquals(0f, trunk.y0, 0f)
        assertTrue("trunk base is the stoutest segment", a.all { it.r0 <= trunk.r0 + 1e-4f })
        val crownTop = a.maxOf { it.y1 }
        assertTrue("crown reaches up ($crownTop)", crownTop > 0.8f)
        val spread = a.maxOf { it.x1 } - a.minOf { it.x1 }
        assertTrue("crown spreads ($spread)", spread > 0.7f)
    }

    @Test
    fun grassClumpsAreDeterministicSharedAndBounded() {
        val a = LandscapeProfile.grassClump(0.30f, 0.62f, seed = 517)
        assertEquals(a, LandscapeProfile.grassClump(0.30f, 0.62f, seed = 517))
        // Two blades a hair apart almost always share their clump's traits exactly —
        // that patchiness is the entire point (measured over many sample pairs).
        var shared = 0
        val pairs = 120
        for (i in 0 until pairs) {
            val x = (i * 37 % 97) / 97f + 0.01f
            val d = (i * 53 % 89) / 89f + 0.01f
            val p = LandscapeProfile.grassClump(x, d, seed = 517)
            val q = LandscapeProfile.grassClump(x + 0.004f, d + 0.004f, seed = 517)
            if (p.heightMul == q.heightMul && p.lean == q.lean) shared++
            assertTrue("heightMul bounded", p.heightMul in 0.7f..1.35f)
            assertTrue("lean bounded", p.lean in -0.55f..0.55f)
            assertTrue("dryness bounded", p.dryness01 in 0f..1f)
            assertTrue("density bounded", p.density01 in 0f..1f)
            assertTrue("edge bounded", p.edge01 in 0f..1f)
        }
        assertTrue("neighbours usually share a clump ($shared/$pairs)", shared > pairs * 2 / 3)
        // And distant patches genuinely differ somewhere.
        val far = LandscapeProfile.grassClump(0.85f, 0.20f, seed = 517)
        assertTrue("clumps vary across the plain", far != a)
    }

    @Test
    fun bladeBendIsMonotonicRootedAndTipExact() {
        for (stiffness in listOf(0f, 0.35f, 0.7f, 1f)) {
            assertEquals(0f, LandscapeProfile.bladeBendX(0f, stiffness), 1e-6f)
            assertEquals(1f, LandscapeProfile.bladeBendX(1f, stiffness), 1e-6f)
            var prev = 0f
            var t = 0.05f
            while (t <= 1.001f) {
                val v = LandscapeProfile.bladeBendX(t.coerceAtMost(1f), stiffness)
                assertTrue("bend monotonic (s=$stiffness t=$t)", v >= prev - 1e-6f)
                assertTrue("bend bounded (s=$stiffness t=$t)", v in 0f..1f)
                prev = v
                t += 0.05f
            }
        }
        // A stiff blade has bent further by mid-height than a supple one.
        assertTrue(
            LandscapeProfile.bladeBendX(0.5f, 1f) > LandscapeProfile.bladeBendX(0.5f, 0f)
        )
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
