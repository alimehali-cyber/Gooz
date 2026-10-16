package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.JupiterMoonsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Galilean satellites against Meeus' Example 44.a (1992 Dec 16, 0h UT; X positive to the west):
 * Io −3.4502 +0.2137, Europa +7.4418 +0.2752, Ganymede +1.2011 +0.5900, Callisto +7.0720 +1.0289,
 * computed there with the full E5 theory, and against the E5 theory (PyMeeus) for 2026.
 */
class JupiterMoonsEngineTest {

    @Test
    fun `reproduces Meeus example 44a within the low-accuracy method's tolerance`() {
        val xy = JupiterMoonsEngine.meeusXY(2448972.5)
        val expected = listOf(-3.4502 to 0.2137, 7.4418 to 0.2752, 1.2011 to 0.5900, 7.0720 to 1.0289)
        for (i in 0..3) {
            assertEquals("x of moon $i", expected[i].first, xy[i].first, 0.06)
            assertEquals("y of moon $i", expected[i].second, xy[i].second, 0.08)
        }
    }

    @Test
    fun `matches the E5 theory in 2026 and keeps phenomena consistent`() {
        val xy = JupiterMoonsEngine.meeusXY(2461330.3333)
        // PyMeeus JupiterMoons.rectangular_positions_jovian_equatorial (E5)
        val e5 = listOf(5.840 to 0.001, 9.049 to 0.014, 7.190 to 0.025, -22.149 to 0.057)
        for (i in 0..3) assertEquals("x of moon $i", e5[i].first, xy[i].first, 0.06)
        val sys = JupiterMoonsEngine.calculateJupiterMoons(2461330.3333)
        for (m in sys.moons.take(4)) {
            val dist = kotlin.math.hypot(m.offsetRaArcsec * kotlin.math.cos(Math.toRadians(sys.jupiterPos.decDeg)), m.offsetDecArcsec)
            assertEquals(m.angularDistanceArcsec, dist, 1e-6)
            assertTrue("Galilean moons stay within ~10 arcmin of Jupiter", m.angularDistanceArcsec < 700.0)
        }
    }
}
