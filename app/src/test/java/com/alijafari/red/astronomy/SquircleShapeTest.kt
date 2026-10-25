package com.alijafari.red.astronomy

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.ui.theme.RedShapes
import com.alijafari.red.astronomy.ui.theme.Squircle
import com.alijafari.red.astronomy.ui.theme.SquircleShape
import com.alijafari.red.astronomy.ui.theme.emitSquircleOutline
import com.alijafari.red.astronomy.ui.theme.squircleConcentric
import com.alijafari.red.astronomy.ui.theme.squircleShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the corners actually land, as opposed to the curve shape that [SquircleGeometryTest] pins.
 *
 * These assertions catch the call-site class of mistake: a clamped radius that escapes its slot, a
 * corner wired to the wrong axis, an RTL mirror that swapped the wrong pair, or a path that never
 * closes. The outline is asserted through the pure emitter rather than a rendered `Path`, so the
 * numbers are exact and no Android graphics stack is involved.
 */
class SquircleShapeTest {

    private class Outline(
        val move: Pair<Float, Float>?,
        val lines: List<Pair<Float, Float>>,
        val curves: List<FloatArray>,
        val closed: Boolean
    )

    private fun emit(tl: Float, tr: Float, br: Float, bl: Float, w: Float = 400f, h: Float = 100f): Outline {
        var move: Pair<Float, Float>? = null
        val lines = mutableListOf<Pair<Float, Float>>()
        val curves = mutableListOf<FloatArray>()
        var closed = false
        emitSquircleOutline(
            size = Size(w, h),
            tl = tl, tr = tr, br = br, bl = bl,
            moveTo = { x, y -> move = x to y },
            lineTo = { x, y -> lines.add(x to y) },
            cubicTo = { x1, y1, x2, y2, x3, y3 -> curves.add(floatArrayOf(x1, y1, x2, y2, x3, y3)) },
            close = { closed = true }
        )
        return Outline(move, lines, curves, closed)
    }

    /** Every coordinate the outline emits, straight or curved. */
    private fun Outline.allPoints(): List<Pair<Float, Float>> {
        val points = mutableListOf<Pair<Float, Float>>()
        move?.let { points.add(it) }
        points.addAll(lines)
        for (c in curves) {
            points.add(c[0] to c[1])
            points.add(c[2] to c[3])
            points.add(c[4] to c[5])
        }
        return points
    }

    @Test
    fun `outline stays inside its slot`() {
        val outline = emit(tl = 34f, tr = 34f, br = 34f, bl = 34f, w = 360f, h = 132f)
        val points = outline.allPoints()
        assertTrue("outline must emit points", points.isNotEmpty())
        assertEquals("left", 0f, points.minOf { it.first }, 0.001f)
        assertEquals("right", 360f, points.maxOf { it.first }, 0.001f)
        assertEquals("top", 0f, points.minOf { it.second }, 0.001f)
        assertEquals("bottom", 132f, points.maxOf { it.second }, 0.001f)
    }

    @Test
    fun `outline closes back onto its start point`() {
        val outline = emit(34f, 34f, 34f, 34f)
        assertTrue("outline must be closed", outline.closed)
        val start = outline.move!!
        val lastCurve = outline.curves.last()
        assertEquals("end x", start.first, lastCurve[4], 0.001f)
        assertEquals("end y", start.second, lastCurve[5], 0.001f)
    }

    @Test
    fun `radius is clamped to half the shorter side`() {
        // A 200 px radius in a 100 px tall slot must clamp to 50: the top edge then starts at x = 50
        // and every control point stays inside the slot. Without clamping the corner boxes would
        // overlap and the path would cross itself outside the slot.
        val outline = emit(200f, 200f, 200f, 200f, w = 400f, h = 100f)
        val points = outline.allPoints()
        assertTrue("no point may leave the slot", points.all { it.first >= -0.001f && it.first <= 400.001f })
        assertTrue("no point may leave the slot", points.all { it.second >= -0.001f && it.second <= 100.001f })
        assertEquals("top-left corner runs in by the clamped radius", 50f, outline.move!!.first, 0.001f)
    }

    @Test
    fun `zero radius emits a plain rectangle`() {
        val outline = emit(0f, 0f, 0f, 0f)
        assertEquals(0f to 0f, outline.move)
        assertEquals(3, outline.lines.size)
        assertTrue("a sharp rectangle needs no curves", outline.curves.isEmpty())
        assertTrue(outline.closed)
    }

    @Test
    fun `each corner emits four cubic segments`() {
        val outline = emit(20f, 20f, 20f, 20f)
        // Four corners x four segments.
        assertEquals("four cubics per corner", 16, outline.curves.size)
        // Four straight runs survive between the four corners: top, right, bottom, left.
        assertEquals(4, outline.lines.size)
    }

    @Test
    fun `corners are tangent to the edges they join`() {
        val outline = emit(20f, 20f, 20f, 20f, w = 400f, h = 100f)

        // Top-right corner: the first control point must still lie on the top edge (y = 0), which is
        // what makes the join tangent — and, because the following control point is also on that
        // line, curvature at the join is zero.
        val trFirst = outline.curves[0]
        assertEquals("top-right corner leaves the top edge tangentially", 0f, trFirst[1], 0.001f)
        assertEquals("top-right corner leaves the top edge tangentially", 0f, trFirst[3], 0.001f)

        // Bottom-left corner's first control point must lie on the bottom edge, and the top-left
        // corner's last control point must lie on the top edge again.
        val blFirst = outline.curves[8]
        assertEquals("bottom-left corner leaves the bottom edge", 100f, blFirst[1], 0.001f)
        val tlLast = outline.curves[15]
        assertEquals("top-left corner returns to the top edge", 0f, tlLast[5], 0.001f)
        assertEquals("top-left corner returns to the top edge", 0f, tlLast[3], 0.001f)
    }

    @Test
    fun `corner run-up follows the continuous-corner reach rule`() {
        // The straight run of the top edge ends one *corner box* away from the slot's corner, and the
        // box is EXTENT times the nominal radius — not the radius itself, which is where a circular
        // corner would start turning. This is the rule that makes the corner look curved.
        val radius = 20f
        val reach = radius * Squircle.EXTENT
        val outline = emit(radius, radius, radius, radius, w = 400f, h = 100f)
        assertEquals("first straight run stops one corner box short", 400f - reach, outline.lines[0].first, 0.001f)
        assertEquals("top edge", 0f, outline.lines[0].second, 0.001f)
        assertEquals("the run must be shorter than a circular corner's", true, reach > radius)
        assertEquals("left edge run ends at the top-left corner", 0f, outline.lines[3].first, 0.001f)
    }

    @Test
    fun `independent radii are honoured per corner`() {
        val e = Squircle.EXTENT
        val outline = emit(tl = 10f, tr = 40f, br = 30f, bl = 20f, w = 400f, h = 200f)
        assertEquals("top-left uses its own box", 10f * e, outline.move!!.first, 0.001f)
        assertEquals("top-right uses its own box", 400f - 40f * e, outline.lines[0].first, 0.001f)
        assertEquals("right edge run uses the bottom-right box", 200f - 30f * e, outline.lines[1].second, 0.001f)
        assertEquals("bottom edge run uses the bottom-left box", 20f * e, outline.lines[2].first, 0.001f)
    }

    @Test
    fun `shape equality is structural`() {
        assertEquals(squircleShape(34.dp), squircleShape(34.dp))
        assertNotEquals(squircleShape(34.dp), squircleShape(20.dp))
        assertNotEquals(
            squircleShape(34.dp),
            squircleShape(topStart = 34.dp, topEnd = 34.dp, bottomEnd = 34.dp, bottomStart = 0.dp)
        )
        assertEquals(squircleShape(34.dp).hashCode(), squircleShape(34.dp).hashCode())
    }

    @Test
    fun `copy changes one corner and keeps the rest`() {
        val base = squircleShape(34.dp)
        val copied = base.copy(bottomStart = CornerSize(4.dp))
        assertEquals(
            squircleShape(topStart = 34.dp, topEnd = 34.dp, bottomEnd = 34.dp, bottomStart = 4.dp),
            copied
        )
        assertEquals("the original is untouched", squircleShape(34.dp), base)
    }

    @Test
    fun `concentric radius subtracts the gap in box space and never goes negative`() {
        // Corner boxes are EXTENT x radius, so a child inset by `gap` needs a radius smaller by
        // gap / EXTENT — not by the gap itself. 34 - 10/1.6 = 27.75.
        val expected = (34f - 10f / Squircle.EXTENT).dp
        assertEquals(expected, squircleConcentric(34.dp, 10.dp))
        assertEquals("a gap wider than the corner collapses it", 0.dp, squircleConcentric(34.dp, 60.dp))
    }

    @Test
    fun `named shapes are squircles and capsules stay circular`() {
        assertTrue("container is a squircle", RedShapes.container is SquircleShape)
        assertTrue("control is a squircle", RedShapes.control is SquircleShape)
        assertTrue("item is a squircle", RedShapes.item is SquircleShape)
        assertTrue("a capsule is a circle-ended pill, not a squircle", RedShapes.capsule !is SquircleShape)
    }
}
