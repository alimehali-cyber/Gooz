package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.astro_engine.VSOP87Engine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Heliocentric VSOP87D coordinates of date against the full VSOP87D series as evaluated by PyMeeus
 * 0.5.12 (Meeus Appendix III tables) at JDE 2461330.33413 (2026-10-16 20:00 UTC), and geocentric
 * positions against astropy/ERFA. The previous engine had Mars ~20° and Saturn ~8° off.
 */
class Vsop87dAccuracyTest {

    private val engine = VSOP87Engine()

    @Test
    fun `heliocentric coordinates match the full theory`() {
        val jde = 2461330.33333333 + 69.2 / 86400.0
        // planet, L, B (deg), R (AU) from PyMeeus geometric_heliocentric_position(tofk5=False)
        val ref = mapOf(
            VSOP87Engine.Planet.EARTH to doubleArrayOf(23.478105029515064, 0.00010273105992251166, 0.9968345099494711),
            VSOP87Engine.Planet.VENUS to doubleArrayOf(19.06605081381167, -2.8753798255085217, 0.7252141452325958),
            VSOP87Engine.Planet.MARS to doubleArrayOf(93.49086333761625, 1.278573725635423, 1.5772909755613602),
            VSOP87Engine.Planet.JUPITER to doubleArrayOf(132.7650260793473, 0.6907915829840108, 5.312064398849829),
            VSOP87Engine.Planet.SATURN to doubleArrayOf(11.718469800192622, -2.4299955273630047, 9.429834832052231)
        )
        for ((p, v) in ref) {
            val h = engine.calculateTt(p, jde)
            assertEquals("$p L", v[0], h.longitudeDeg, 2.0 / 3600.0)
            assertEquals("$p B", v[1], h.latitudeDeg, 2.0 / 3600.0)
            assertEquals("$p R", v[2], h.distanceAu, 2e-6 * v[2])
        }
    }

    @Test
    fun `geocentric planet positions agree with an independent ephemeris`() {
        val at = AstroTime(1792180800000L)
        // PyMeeus geocentric apparent RA/Dec (deg); the engine returns geometric mean-of-date values,
        // which differ by nutation + aberration (< 40 arcsec).
        val ref = mapOf(
            PlanetEngine.PlanetType.VENUS to doubleArrayOf(210.03680057187577, -20.124437106011246),
            PlanetEngine.PlanetType.MARS to doubleArrayOf(133.4683326493133, 18.816570273208026),
            PlanetEngine.PlanetType.JUPITER to doubleArrayOf(144.80733695021365, 14.706654812270168),
            PlanetEngine.PlanetType.SATURN to doubleArrayOf(10.572017658389992, 1.6034739367764093)
        )
        for ((p, v) in ref) {
            val pos = PlanetEngine.calculatePlanet(p, at)
            assertEquals("$p RA", v[0], pos.raDeg, 60.0 / 3600.0)
            assertEquals("$p Dec", v[1], pos.decDeg, 60.0 / 3600.0)
        }
    }
}
