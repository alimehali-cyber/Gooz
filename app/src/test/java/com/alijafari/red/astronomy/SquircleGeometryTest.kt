package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ui.theme.Squircle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The squircle is the app's single corner geometry, so its numbers are verified on every build rather
 * than trusted.
 *
 * Every assertion here re-derives the property from the shipped constants in
 * [Squircle.CORNER_HALF] / [Squircle.CORNER] — no golden values are copied from the fit that
 * produced them, so a transcription error, a flipped mirror or a drift in the exponent fails the
 * build instead of quietly shipping a corner with a visible kink.
 *
 * The properties are the definition of the shape:
 *
 *  1. the corner starts and ends exactly on the corner box's two axes;
 *  2. it leaves each axis exactly tangent to it (G1 at the edge join);
 *  3. it leaves each axis with **zero curvature** (G2 at the edge join — the whole point);
 *  4. curvature is continuous across the four internal segment joins (G2 inside the corner);
 *  5. it stays on the analytic superellipse `x^n + (1 - y)^n = 1` to well under a pixel;
 *  6. it is convex and its half-curve is an exact mirror of its other half;
 *  7. it is visibly squarer than the circular corner `RoundedCornerShape` would have drawn.
 */
class SquircleGeometryTest {

    private val exponent = Squircle.EXPONENT.toDouble()

    private class Segment(
        val p0: Pair<Double, Double>,
        val p1: Pair<Double, Double>,
        val p2: Pair<Double, Double>,
        val p3: Pair<Double, Double>
    )

    /** Exactly how `buildSquirclePath` walks [Squircle.CORNER]: 4 segments, 3 points each. */
    private fun cornerSegments(): List<Segment> {
        val table = Squircle.CORNER
        assertEquals("CORNER must hold 4 segments x 3 points x 2 coords", 24, table.size)
        val startPoints = mutableListOf<Pair<Double, Double>>()
        startPoints.add(0.0 to 0.0)
        for (i in 0 until 4) {
            startPoints.add(table[i * 6 + 4].toDouble() to table[i * 6 + 5].toDouble())
        }
        val segments = mutableListOf<Segment>()
        for (i in 0 until 4) {
            segments.add(
                Segment(
                    p0 = startPoints[i],
                    p1 = table[i * 6].toDouble() to table[i * 6 + 1].toDouble(),
                    p2 = table[i * 6 + 2].toDouble() to table[i * 6 + 3].toDouble(),
                    p3 = startPoints[i + 1]
                )
            )
        }
        return segments
    }

    private fun point(segment: Segment, t: Double): Pair<Double, Double> {
        val mt = 1.0 - t
        val x = mt * mt * mt * segment.p0.first +
            3 * mt * mt * t * segment.p1.first +
            3 * mt * t * t * segment.p2.first +
            t * t * t * segment.p3.first
        val y = mt * mt * mt * segment.p0.second +
            3 * mt * mt * t * segment.p1.second +
            3 * mt * t * t * segment.p2.second +
            t * t * t * segment.p3.second
        return x to y
    }

    private fun firstDerivative(segment: Segment, t: Double): Pair<Double, Double> {
        val mt = 1.0 - t
        val dx = 3 * mt * mt * (segment.p1.first - segment.p0.first) +
            6 * mt * t * (segment.p2.first - segment.p1.first) +
            3 * t * t * (segment.p3.first - segment.p2.first)
        val dy = 3 * mt * mt * (segment.p1.second - segment.p0.second) +
            6 * mt * t * (segment.p2.second - segment.p1.second) +
            3 * t * t * (segment.p3.second - segment.p2.second)
        return dx to dy
    }

    private fun secondDerivative(segment: Segment, t: Double): Pair<Double, Double> {
        val mt = 1.0 - t
        val ddx = 6 * mt * (segment.p2.first - 2 * segment.p1.first + segment.p0.first) +
            6 * t * (segment.p3.first - 2 * segment.p2.first + segment.p1.first)
        val ddy = 6 * mt * (segment.p2.second - 2 * segment.p1.second + segment.p0.second) +
            6 * t * (segment.p3.second - 2 * segment.p2.second + segment.p1.second)
        return ddx to ddy
    }

    private fun curvature(segment: Segment, t: Double): Double {
        val (dx, dy) = firstDerivative(segment, t)
        val (ddx, ddy) = secondDerivative(segment, t)
        val speed = hypot(dx, dy)
        if (speed < 1e-12) return 0.0
        return abs(dx * ddy - dy * ddx) / speed.pow(3)
    }

    /** First-order distance from a point to the analytic superellipse corner `x^n + (1-y)^n = 1`. */
    private fun distanceToSuperellipse(x: Double, y: Double): Double {
        val u = max(x, 0.0)
        val v = max(1.0 - y, 0.0)
        val f = u.pow(exponent) + v.pow(exponent) - 1.0
        val gu = if (u > 0.0) exponent * u.pow(exponent - 1.0) else 0.0
        val gv = if (v > 0.0) exponent * v.pow(exponent - 1.0) else 0.0
        val gradient = hypot(gu, gv)
        return if (gradient < 1e-12) 0.0 else abs(f) / gradient
    }

    @Test
    fun `exponent is the documented heavy squircle`() {
        // Lamé: 2 is a circle, 4 is the squircle proper, 5 is the heavier curve this app wears and
        // infinity is a square. Pinned exactly so the corner the app draws cannot drift away from the
        // one the documentation and the fitted control points describe. `n` only ever moves together
        // with a refit of CORNER_HALF — the deviation and G2 assertions below enforce that.
        assertEquals(5.0, exponent, 0.0)
        assertTrue("a squircle needs n > 2", exponent > 2.0)
    }

    @Test
    fun `corner starts and ends on the axes of its box`() {
        val segments = cornerSegments()
        assertEquals(0.0, segments.first().p0.first, 1e-9)
        assertEquals(0.0, segments.first().p0.second, 1e-9)
        assertEquals(1.0, segments.last().p3.first, 1e-9)
        assertEquals(1.0, segments.last().p3.second, 1e-9)
    }

    @Test
    fun `corner meets each edge at a tangent`() {
        val segments = cornerSegments()

        // Start: the edge runs along +x, so the incoming tangent must be exactly horizontal.
        val (sx, sy) = firstDerivative(segments.first(), 0.0)
        assertTrue("start tangent must be horizontal, dy=$sy", abs(sy) < 1e-9)
        assertTrue("start tangent must point along +x", sx > 0.0)

        // End: the edge runs along +y, so the outgoing tangent must be exactly vertical.
        val (ex, ey) = firstDerivative(segments.last(), 1.0)
        assertTrue("end tangent must be vertical, dx=$ex", abs(ex) < 1e-9)
        assertTrue("end tangent must point along +y", ey > 0.0)
    }

    @Test
    fun `corner meets each edge with zero curvature`() {
        // This is what makes the join G2 instead of G1: curvature has to vanish at the edge, exactly
        // as it does along the straight edge it is joining.
        val segments = cornerSegments()
        assertEquals("curvature at the edge join", 0.0, curvature(segments.first(), 0.0), 1e-6)
        assertEquals("curvature at the edge join", 0.0, curvature(segments.last(), 1.0), 1e-6)
    }

    @Test
    fun `curvature is continuous across every internal join`() {
        val segments = cornerSegments()
        for (i in 1 until segments.size) {
            val before = curvature(segments[i - 1], 1.0)
            val after = curvature(segments[i], 0.0)
            val scale = max(0.5 * (before + after), 1e-9)
            val relativeJump = abs(before - after) / scale
            assertTrue(
                "curvature jumps $relativeJump at joint $i ($before -> $after); a squircle must be G2",
                relativeJump < 1e-3
            )
            // Position continuity: the next segment must start where the previous one ended.
            assertEquals(segments[i - 1].p3.first, segments[i].p0.first, 1e-6)
            assertEquals(segments[i - 1].p3.second, segments[i].p0.second, 1e-6)
        }
    }

    @Test
    fun `corner tracks the analytic superellipse to well under a pixel`() {
        // 0.01 % of the radius is ~0.012 px at the app's 40 dp radius on a 3x display.
        val tolerance = 0.0005 // 0.05 % of r
        var worst = 0.0
        for (segment in cornerSegments()) {
            for (i in 0..600) {
                val (x, y) = point(segment, i / 600.0)
                worst = max(worst, distanceToSuperellipse(x, y))
            }
        }
        assertTrue("max deviation $worst of r exceeds $tolerance", worst < tolerance)
    }

    @Test
    fun `corner is convex`() {
        // Every turn must go the same way, or the outline would have a dimple.
        val samples = mutableListOf<Pair<Double, Double>>()
        for (segment in cornerSegments()) {
            for (i in 0..100) samples.add(point(segment, i / 100.0))
        }
        for (i in 1 until samples.size - 1) {
            val ax = samples[i].first - samples[i - 1].first
            val ay = samples[i].second - samples[i - 1].second
            val bx = samples[i + 1].first - samples[i].first
            val by = samples[i + 1].second - samples[i].second
            val cross = ax * by - ay * bx
            assertTrue("outline turns the wrong way at sample $i ($cross)", cross >= -1e-9)
        }
    }

    @Test
    fun `second half is the exact mirror of the first`() {
        // The second half is generated as (x, y) -> (1 - y, 1 - x) of the first, so the corner cannot
        // drift out of symmetry and the 45 degree tangent stays exact.
        val table = Squircle.CORNER
        fun at(i: Int): Pair<Float, Float> = table[i * 2] to table[i * 2 + 1]
        // Points 0..4 are segments A and B (all but the diagonal point); their mirrors are points
        // 10..6, paired outside-in. Point 5 is the diagonal point, which the mirror maps to itself.
        val mirroredOrder = intArrayOf(10, 9, 8, 7, 6)
        for (k in 0 until 5) {
            val source = at(k)
            val mirror = at(mirroredOrder[k])
            assertEquals(
                "mirror x of point $k",
                1f - source.second,
                mirror.first,
                1e-6f
            )
            assertEquals(
                "mirror y of point $k",
                1f - source.first,
                mirror.second,
                1e-6f
            )
        }
        // The diagonal point must be its own mirror: x + y = 1.
        val diagonal = at(5)
        assertEquals("diagonal point lies on x + y = 1", 1f, diagonal.first + diagonal.second, 1e-6f)
    }

    @Test
    fun `corner reaches the documented extent along each edge`() {
        // EXTENT_RATIO is used to keep nested corners concentric; it must match the curve.
        val diagonalX = Squircle.CORNER[10].toDouble() // the 45 degree point, x
        assertEquals(Squircle.EXTENT_RATIO.toDouble(), diagonalX, 1e-5)

        // 2^(-1/n): where the superellipse crosses its own diagonal, as a fraction of its radius.
        val expected = 2.0.pow(-1.0 / exponent)
        assertEquals(expected, Squircle.EXTENT_RATIO.toDouble(), 1e-5)
    }

    @Test
    fun `corner is squarer than a circular corner`() {
        // A circular corner of the same nominal radius leaves the 45 degree point on the line
        // x + y = 1 offset inward by r(1 - 1/sqrt(2)) in both axes. The squircle's diagonal point
        // must sit measurably closer to the box corner, otherwise the app shipped a round corner
        // and nobody would notice from the tests.
        val circleDiagonalX = 1.0 / sqrt(2.0)
        val squircleDiagonalX = Squircle.CORNER[10].toDouble()
        assertTrue(
            "squircle diagonal $squircleDiagonalX should exceed circle diagonal $circleDiagonalX",
            squircleDiagonalX > circleDiagonalX + 0.05
        )
    }
}
