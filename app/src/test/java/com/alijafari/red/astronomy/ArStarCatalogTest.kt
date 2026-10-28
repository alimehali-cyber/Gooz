package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.ArConstellationCatalog
import com.alijafari.red.astronomy.ar.core.ArStarCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The packed Yale Bright Star Catalogue (5th rev. ed.) and the constellation figures shipped in
 * `assets/ar`, checked against reference values (BSC5 / SIMBAD, J2000.0).
 */
class ArStarCatalogTest {

    private fun asset(name: String): File {
        val candidates = listOf("src/main/assets/ar/$name", "app/src/main/assets/ar/$name")
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Asset not found: $name (searched ${candidates.joinToString()})")
    }

    private val catalog: ArStarCatalog by lazy {
        asset("stars_bsc5.bin").inputStream().use { s ->
            asset("star_names.txt").inputStream().use { n -> ArStarCatalog.parse(s, n) }
        }
    }

    private fun star(hr: Int): Int = catalog.indexOfHr(hr).also { assertTrue("HR $hr missing", it >= 0) }

    @Test
    fun `contains every BSC5 star to magnitude 6_5`() {
        assertEquals(8404, catalog.size)
        for (i in 0 until catalog.size) {
            assertTrue(catalog.vmag[i] <= 6.5f)
            assertTrue(catalog.raDeg[i] in 0.0..360.0)
            assertTrue(catalog.decDeg[i] in -90.0..90.0)
        }
        assertTrue("named stars", catalog.names.size >= 300)
    }

    @Test
    fun `reference stars have their catalogue positions, magnitudes and motions`() {
        data class Ref(val hr: Int, val name: String, val ra: Double, val dec: Double, val v: Double)
        val refs = listOf(
            Ref(2491, "Sirius", 101.2871, -16.7161, -1.46),
            Ref(7001, "Vega", 279.2346, 38.7837, 0.03),
            Ref(424, "Polaris", 37.9529, 89.2642, 2.02),
            Ref(2061, "Betelgeuse", 88.7929, 7.4071, 0.50),
            Ref(5340, "Arcturus", 213.9154, 19.1824, -0.04)
        )
        for (r in refs) {
            val i = star(r.hr)
            assertEquals(r.name, catalog.name(i))
            // BSC5 positions are given to 0.1 s / 1″: 0.0005° tolerance.
            assertEquals("${r.name} RA", r.ra, catalog.raDeg[i], 0.0005)
            assertEquals("${r.name} Dec", r.dec, catalog.decDeg[i], 0.0005)
            assertEquals("${r.name} V", r.v, catalog.vmag[i].toDouble(), 0.006)
        }
        // Arcturus: μα* = −1.093″/yr, μδ = −1.999″/yr (FK5).
        val arcturus = star(5340)
        assertEquals(-1.093, catalog.pmRaCosDec[arcturus], 0.002)
        assertEquals(-1.999, catalog.pmDec[arcturus], 0.002)
        // Colours: Sirius white (B−V 0.00), Betelgeuse red (1.85).
        assertEquals(0.0, catalog.colorIndex(star(2491)), 0.02)
        assertEquals(1.85, catalog.colorIndex(star(2061)), 0.02)
    }

    @Test
    fun `constellation figures cover the 88 IAU constellations with valid star indices`() {
        val figures = asset("constellations.txt").inputStream().use { ArConstellationCatalog.parse(it, catalog.size) }
        assertEquals(88, figures.size)
        assertEquals(88, figures.map { it.code }.toSet().size)
        for (c in figures) {
            assertTrue("${c.code} has segments", c.segments.isNotEmpty() && c.segments.size % 2 == 0)
            assertTrue("${c.code} indices", c.segments.all { it in 0 until catalog.size })
            assertTrue("${c.code} names", c.nameEn.isNotBlank() && c.nameFa.isNotBlank())
        }
        // Serpens Caput and Cauda are one constellation.
        assertEquals(1, figures.count { it.code == "Ser" })
        // Orion's figure joins Betelgeuse (HR 2061) and Rigel (HR 1713) to the rest.
        val ori = figures.first { it.code == "Ori" }
        assertTrue(ori.segments.contains(star(2061)))
        assertTrue(ori.segments.contains(star(1713)))
    }
}
