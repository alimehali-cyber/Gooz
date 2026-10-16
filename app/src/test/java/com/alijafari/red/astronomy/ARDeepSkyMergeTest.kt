package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.domain.ObjectType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ARDeepSkyMergeTest {

    @Test
    fun expandedEngineDeepSkyCatalogIsMergedWithoutCanonicalDuplicates() {
        val report = CanonicalAstroCatalog.getDeepSkyMergeReport()

        assertEquals(252, report.engineCatalogCount)
        assertEquals(15, report.originalHandAuthoredDsoCount)
        // Ten engine rows overlap hand-authored DSOs by Messier/NGC token. NGC 869 and
        // NGC 884 are intentionally retained as separate selectable components of the
        // Double Cluster; C14 is treated as an alias of NGC 869, not a third component.
        // Seventeen Caldwell/NGC duplicate rows are merged into the NGC canonical object.
        assertEquals(10, report.skippedCanonicalDuplicateCount)
        assertEquals(1, report.skippedEngineDuplicateCount)
        assertEquals(17, report.mergedCatalogDuplicateCount)
        assertEquals(224, report.insertedCount)
        assertEquals(239, report.finalDeepSkyCount)
        assertEquals(632, report.finalCatalogCount)
        assertTrue(report.duplicateCanonicalIds.isEmpty())
    }

    @Test
    fun handAuthoredDsoWinsWhenCatalogIdentifierOverlaps() {
        val m31 = CanonicalAstroCatalog.getCanonicalObject("M31")
        assertNotNull(m31)
        val resolvedM31 = m31!!
        assertEquals("dso_m31_andromeda", resolvedM31.canonicalId)
        assertEquals("AND", resolvedM31.scientificIdentifiers.constellationCode)
        assertTrue(resolvedM31.physicalProperties.angularSizeArcmin != null)
        assertTrue(resolvedM31.observationalInfo.bestViewingMonthEn.isNotBlank())
    }

    @Test
    fun doubleClusterComponentsRemainIndependentlySelectable() {
        val ngc869 = CanonicalAstroCatalog.getCanonicalObject("NGC 869")
        val ngc884 = CanonicalAstroCatalog.getCanonicalObject("NGC 884")
        val c14 = CanonicalAstroCatalog.getCanonicalObject("C14")

        assertNotNull(ngc869)
        assertNotNull(ngc884)
        assertNotNull(c14)
        val aggregate = CanonicalAstroCatalog.getCanonicalObject("dso_double_cluster")

        assertEquals("dso_ngc_869", ngc869!!.canonicalId)
        assertEquals("dso_ngc_884", ngc884!!.canonicalId)
        assertEquals("dso_ngc_869", c14!!.canonicalId)
        assertNull("The old hand-authored aggregate must not collapse the two NGC clusters", aggregate)
    }

    @Test
    fun duplicatedCaldwellDesignationsResolveToSingleNgcCanonicalObjects() {
        val fireworks = CanonicalAstroCatalog.getCanonicalObject("NGC 6946")
        val caldwell12 = CanonicalAstroCatalog.getCanonicalObject("C12")
        val caldwell9 = CanonicalAstroCatalog.getCanonicalObject("C9")

        assertNotNull(fireworks)
        assertNotNull(caldwell12)
        assertEquals("dso_ngc_6946", fireworks!!.canonicalId)
        assertEquals("dso_ngc_6946", caldwell12!!.canonicalId)
        assertNull("C9 is not NGC 6946 and must not remain as a stale alias", caldwell9)
    }

    @Test
    fun messier73IsNotMislabeledAsWinnecke4() {
        val m40 = CanonicalAstroCatalog.getCanonicalObject("M40")
        val m73 = CanonicalAstroCatalog.getCanonicalObject("M73")

        assertNotNull(m40)
        assertNotNull(m73)
        assertTrue(m40!!.nameEn.contains("Winnecke 4"))
        assertTrue(m73!!.nameEn.contains("M73 Asterism"))
        assertTrue(m73.observationalInfo.descriptionEn.contains("unrelated stars"))
    }

    @Test
    fun newEngineDsoIsSearchableAndProjectableThroughMasterCatalog() {
        val m101 = CanonicalAstroCatalog.getCanonicalObject("M101")
        assertNotNull(m101)
        val resolvedM101 = m101!!
        assertEquals("dso_m101", resolvedM101.canonicalId)
        assertEquals(ObjectType.GALAXY, resolvedM101.type)
        assertTrue(resolvedM101.scientificIdentifiers.catalogDesignations.any { it.equals("M101", ignoreCase = true) })

        val obj = AstronomyCatalog.getById("NGC 5457")
        assertNotNull(obj)
        assertEquals("dso_m101", obj!!.id)

        val compactObj = AstronomyCatalog.getById("NGC5457")
        assertNotNull(compactObj)
        assertEquals("dso_m101", compactObj!!.id)
    }
}
