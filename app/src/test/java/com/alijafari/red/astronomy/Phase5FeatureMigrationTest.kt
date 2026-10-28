package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.*
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.domain.ObjectType
import org.junit.Assert.*
import org.junit.Test

class Phase5FeatureMigrationTest {

    @Test
    fun testAstronomyCatalogDelegatesToCanonicalCatalogAndDispatchEngine() {
        val jd = TimeEngine.getJulianDate()
        val masterList = AstronomyCatalog.getAllObjects(jd)
        assertTrue("Master catalog should contain canonical objects", masterList.isNotEmpty())

        val mars = AstronomyCatalog.getById("planet_mars", jd)
        assertNotNull("Mars should be found via AstronomyCatalog", mars)
        assertEquals(ObjectType.PLANET, mars?.type)

        val canonicalMars = CanonicalAstroCatalog.getCanonicalObject("planet_mars")
        assertNotNull("Canonical Mars must exist", canonicalMars)
        assertEquals(canonicalMars?.canonicalId, mars?.id)

        // Verify dynamic position calculated by AstroDispatchEngine matches AstronomyCatalog
        val timestampMs = TimeEngine.getTimestampFromJulianDate(jd)
        val state = AstroDispatchEngine.calculateState("planet_mars", timestampMs)
        assertNotNull("State for Mars should be calculated by AstroDispatchEngine", state)
        assertEquals(state!!.raDeg, mars!!.raDeg, 0.001)
        assertEquals(state.decDeg, mars.decDeg, 0.001)
    }

    @Test
    fun testWhatsUpTonightEngineUsesCanonicalObjects() {
        // Evaluate at nighttime (20:00 UTC) so planets are observable above horizon
        val nightTimeMs = 1789761600000L
        val jd = TimeEngine.getJulianDate(nightTimeMs)
        val events = WhatsUpTonightEngine.calculateTonightEvents(
            jd = jd,
            userLatDeg = 35.6892,
            userLonDeg = 51.3890,
            isFa = false
        )

        assertTrue("Tonight events should not be empty", events.isNotEmpty())

        val eventsWithTarget = events.mapNotNull { it.targetObject }
        assertTrue("At least some events should have targetObjects", eventsWithTarget.isNotEmpty())

        for (target in eventsWithTarget) {
            val canonicalId = CanonicalAstroCatalog.resolveCanonicalId(target.id)
            val canonicalObj = CanonicalAstroCatalog.getCanonicalObject(canonicalId)
            assertNotNull("Event target ${target.id} must resolve to a canonical object", canonicalObj)
        }
    }

    @Test
    fun testLegacyAliasResolutionAcrossFeatures() {
        val aliasesToTest = listOf(
            "iss" to "sat_25544",
            "sat_iss" to "sat_25544",
            "m31" to "dso_m31_andromeda",
            "sirius" to "star_cma_sirius",
            "jupiter" to "planet_jupiter",
            "sun" to "sun",
            "moon" to "moon"
        )

        for ((alias, expectedCanonicalId) in aliasesToTest) {
            val resolvedId = CanonicalAstroCatalog.resolveCanonicalId(alias)
            assertEquals("Alias '$alias' should resolve to '$expectedCanonicalId'", expectedCanonicalId, resolvedId)

            val obj = AstronomyCatalog.getById(alias)
            assertNotNull("Object for alias '$alias' must be found", obj)
            assertEquals(expectedCanonicalId, obj?.id)
        }
    }

    @Test
    fun testLabFeatureCanonicalObjects() {
        val earth = AstronomyCatalog.getById("planet_earth")
        assertNotNull("Earth should be in AstronomyCatalog for Lab start selection", earth)
        assertEquals("planet_earth", earth?.id)

        val sirius = AstronomyCatalog.getById("star_cma_sirius")
        assertNotNull("Sirius should be in AstronomyCatalog for Lab destination selection", sirius)
        assertEquals("star_cma_sirius", sirius?.id)
    }
}
