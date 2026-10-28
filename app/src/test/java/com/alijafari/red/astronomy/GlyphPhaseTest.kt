package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.ar.core.GlyphRaster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The photoreal glyphs show the true phase: the ray-cast disc is lit over exactly the illuminated
 * fraction k = (1 + cos i)/2, on the side facing the Sun, and the scene geometry reproduces Meeus'
 * worked examples for the Moon (Ex. 48.a) and Venus (Ex. 41.a).
 */
class GlyphPhaseTest {

    private fun utcMillis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    /** Sun at phase angle [phaseDeg] in position angle [paDeg] (from north through east). */
    private fun render(phaseDeg: Double, paDeg: Double, size: Int, radius: Double): IntArray {
        val i = Math.toRadians(phaseDeg); val pa = Math.toRadians(paDeg)
        // Sprite frame: x = west (image right), y = north (image up), z = towards the observer.
        val sun = doubleArrayOf(-sin(pa) * sin(i), cos(pa) * sin(i), cos(i))
        val spec = GlyphRaster.BodySpec(
            texture = null,
            fallbackLinear = doubleArrayOf(0.5, 0.5, 0.5),
            shading = GlyphRaster.Shading.LAMBERT,
            opacityGain = 1e7 // every lit sample opaque, every unlit one transparent
        )
        val geo = GlyphRaster.Geometry(
            pole = doubleArrayOf(0.0, 1.0, 0.0),
            primeMeridian = doubleArrayOf(0.0, 0.0, 1.0),
            sunDir = sun
        )
        val out = IntArray(size * size)
        GlyphRaster.renderBody(size, radius, spec, geo, out)
        return out
    }

    @Test
    fun `lit area equals the illuminated fraction and faces the Sun`() {
        val size = 260; val radius = 120.0
        for ((phase, pa) in listOf(0.0 to 0.0, 60.0 to 90.0, 90.0 to 250.0, 120.0 to 30.0, 150.0 to 180.0)) {
            val px = render(phase, pa, size, radius)
            var lit = 0.0; var cx = 0.0; var cy = 0.0
            for (j in 0 until size) for (i in 0 until size) {
                val a = ((px[j * size + i] ushr 24) and 255) / 255.0
                lit += a
                cx += a * (i + 0.5 - size / 2.0); cy += a * -(j + 0.5 - size / 2.0)
            }
            val disc = Math.PI * radius * radius
            val k = (1 + cos(Math.toRadians(phase))) / 2
            assertEquals("illuminated fraction at i=$phase°", k, lit / disc, 0.012)
            if (phase > 0.0) {
                // Image angle of the lit centroid, clockwise from up: east is left, so PA maps to −PA.
                val angle = Math.toDegrees(atan2(cx, cy))
                val expected = -pa
                val diff = ((angle - expected + 540.0) % 360.0) - 180.0
                assertTrue("bright side at PA $pa (image angle $angle)", abs(diff) < 1.0)
            }
        }
    }

    @Test
    fun `Moon phase matches Meeus example 48a`() {
        // 1992 April 12, 0h TD (TT − UTC = 59.184 s): k = 0.6786 geocentric.
        val t = utcMillis(1992, 4, 11, 23, 59, 1)
        // Observer at the sub-lunar point (α = 134.69°, δ = 13.77°): topocentric = geocentric direction.
        val snap = ArSkySceneComputer.compute(t, ArObserver(13.769, -64.6, 0.0), null, emptyList(), emptyList(), emptyMap(), null)
        assertTrue("Moon at the zenith", snap.moon.placed.altDeg > 88.5)
        assertEquals(0.6786, snap.moon.illuminatedFraction, 0.001)
        assertEquals(69.0756, snap.moon.glyph!!.phaseAngleDeg, 0.05)
    }

    @Test
    fun `Venus phase matches Meeus example 41a`() {
        // 1992 December 20, 0h TD: k = 0.647.
        val t = utcMillis(1992, 12, 19, 23, 59, 1)
        val snap = ArSkySceneComputer.compute(t, ArObserver(0.0, 0.0, 0.0), null, emptyList(), emptyList(), emptyMap(), null)
        val venus = snap.bodies.first { it.id == "planet_venus" }
        assertEquals(0.647, venus.illuminatedFraction, 0.001)
        assertEquals(72.96, venus.glyph!!.phaseAngleDeg, 0.02)
    }
}
