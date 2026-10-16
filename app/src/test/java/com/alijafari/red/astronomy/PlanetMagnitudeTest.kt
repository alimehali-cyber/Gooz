package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.astro_engine.PlanetEngine.PlanetType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

/**
 * Planetary magnitudes follow Mallama & Hilton (2018), the formulas of The Astronomical Almanac.
 * Reference numbers come from the authors' reference implementation as ported in Skyfield
 * (`skyfield.magnitudelib`), evaluated with the same geometry.
 */
class PlanetMagnitudeTest {

    /** Synthetic geometry with given r, Δ and phase angle α (only these enter the phase-curve planets). */
    private fun mag(p: PlanetType, r: Double, d: Double, alphaDeg: Double): Double {
        val a = Math.toRadians(alphaDeg)
        return PlanetEngine.apparentMagnitude(p, doubleArrayOf(r, 0.0, 0.0), doubleArrayOf(d * cos(a), d * sin(a), 0.0), 0.2)
    }

    @Test
    fun `phase curves match the reference implementation`() {
        val cases = listOf(
            Triple(PlanetType.MERCURY, 10.0, -0.113177), Triple(PlanetType.MERCURY, 50.0, 1.011447),
            Triple(PlanetType.MERCURY, 90.0, 2.258324), Triple(PlanetType.MERCURY, 130.0, 4.138046),
            Triple(PlanetType.MERCURY, 170.0, 8.679367),
            Triple(PlanetType.VENUS, 10.0, -4.360295), Triple(PlanetType.VENUS, 80.0, -3.182508),
            Triple(PlanetType.VENUS, 150.0, -1.217237), Triple(PlanetType.VENUS, 165.0, -0.672814),
            Triple(PlanetType.VENUS, 175.0, -0.337058),
            Triple(PlanetType.MARS, 5.0, -1.490905), Triple(PlanetType.MARS, 40.0, -0.902520),
            Triple(PlanetType.MARS, 60.0, -0.670600), Triple(PlanetType.MARS, 120.0, 1.506200),
            Triple(PlanetType.JUPITER, 6.0, -9.375044), Triple(PlanetType.JUPITER, 12.0, -9.310736),
            Triple(PlanetType.JUPITER, 30.0, -9.101650), Triple(PlanetType.JUPITER, 90.0, -7.985859)
        )
        for ((p, a, expected) in cases) {
            assertEquals("$p at α=$a°", expected, mag(p, 1.0, 1.0, a), 1e-5)
        }
    }

    @Test
    fun `apparent magnitudes on 2021-10-04 including Saturn's rings and Uranus' tilt`() {
        val t = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(2021, 9, 4, 0, 0, 0) }.timeInMillis
        val at = AstroTime(t)
        // Reference implementation with the same (heliocentric, light-time corrected) geometry; the
        // Saturn/Uranus sub-latitudes computed independently in ICRS with ERFA (Saturn ring opening 19.4°).
        val expected = mapOf(
            PlanetType.MERCURY to 2.412, PlanetType.VENUS to -4.2466, PlanetType.MARS to 1.5864,
            PlanetType.JUPITER to -2.6889, PlanetType.SATURN to 0.5106, PlanetType.URANUS to 5.7009,
            PlanetType.NEPTUNE to 7.6903
        )
        for ((p, v) in expected) {
            assertEquals(p.name, v, PlanetEngine.calculatePlanet(p, at).magnitude, 0.002)
        }
        // Pluto + Charon: V(1,0) = −1.01, 0.041 mag/deg (Explanatory Supplement 1992, Table 7.48.1) → ≈ 14.4.
        assertEquals(14.40, PlanetEngine.calculatePlanet(PlanetType.PLUTO, at).magnitude, 0.05)
    }
}
