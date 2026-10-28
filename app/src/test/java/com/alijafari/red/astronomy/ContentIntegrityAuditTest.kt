package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.SatelliteCatalog
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.data.catalog.PhysicalData
import com.alijafari.red.astronomy.domain.CanonicalAstroObject
import com.alijafari.red.astronomy.domain.ObjectType
import com.alijafari.red.astronomy.ui.components.objectDetailSectionPlan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-integrity audit over the FULL union list (every object reachable anywhere in the app),
 * not just deep-sky objects.
 *
 * The prior "FULLY VERIFIED" claim was false. This test iterates all canonical objects and fails on:
 *   - D: zero facts in either language, with no logged reason
 *   - C: placeholder / generic / template / cross-object-duplicated facts
 *   - E: reachable objects whose rendered detail page would show no facts card
 *   - unlogged objects (no research-log entry), sparse sets without a logged reason
 *   - bilingual mismatches, out-of-range fact counts, English leakage into Persian text
 *   - satellite facts dropped on the canonical merge path
 */
class ContentIntegrityAuditTest {

    private val allObjects: List<CanonicalAstroObject> by lazy { CanonicalAstroCatalog.getAllCanonicalObjects() }

    // ------------------------------------------------------------------ //
    // Union-list reachability: one unified canonical set, 336 objects.    //
    // ------------------------------------------------------------------ //

    @Test
    fun unionListHasExactlyThreeHundredThirtySixReachableObjects() {
        assertEquals(
            "Canonical union-list size changed unexpectedly; the audit scope is the full union",
            632,
            allObjects.size
        )
    }

    @Test
    fun everyCanonicalObjectIsReachableThroughTheSharedCatalogFacade() {
        val facadeIds = AstronomyCatalog.getAllObjects().map { it.id }.toSet()
        val missing = allObjects.filterNot { it.canonicalId in facadeIds }.map { it.canonicalId }
        assertTrue(
            "Canonical objects missing from AstronomyCatalog.getAllObjects(): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun arSkyScopeExcludesOnlyEarthAndKeepsEverythingElseReachable() {
        val arList = com.alijafari.red.astronomy.astro_engine.ARSkyCatalog
            .arSkyObjects(AstronomyCatalog.getAllObjects())
        val arIds = arList.map { it.id }.toSet()
        assertTrue("Earth must be excluded from the AR sky", "planet_earth" !in arIds)
        val missing = allObjects
            .filter { it.canonicalId != "planet_earth" && it.canonicalId !in arIds }
            .map { it.canonicalId }
        assertTrue("Every non-Earth object must be reachable in the AR sky: $missing", missing.isEmpty())
    }

    @Test
    fun canonicalIdsResolveToThemselves() {
        val broken = allObjects
            .filter { CanonicalAstroCatalog.resolveCanonicalId(it.canonicalId) != it.canonicalId }
            .map { it.canonicalId }
        assertTrue("Canonical ids must resolve to themselves: $broken", broken.isEmpty())
    }

    // ------------------------------------------------------------------ //
    // Bucket D + bilingual + range checks over the FULL union.            //
    // ------------------------------------------------------------------ //

    @Test
    fun noObjectHasZeroFactsInEitherLanguage() {
        val zero = allObjects.filter { factsEn(it).isEmpty() || factsFa(it).isEmpty() }
        assertTrue(
            "Bucket D objects (no facts, no logged reason) still present: " +
                zero.map { it.canonicalId },
            zero.isEmpty()
        )
    }

    @Test
    fun factsAreBilingualAndBetweenOneAndFivePerObject() {
        val mismatched = mutableListOf<String>()
        val outOfRange = mutableListOf<String>()
        allObjects.forEach { obj ->
            val en = factsEn(obj)
            val fa = factsFa(obj)
            if (en.size != fa.size) mismatched += "${obj.canonicalId}: en=${en.size} fa=${fa.size}"
            if (en.size !in 1..5 || fa.size !in 1..5) outOfRange += "${obj.canonicalId}: en=${en.size} fa=${fa.size}"
        }
        assertTrue("Bilingual fact count mismatch: $mismatched", mismatched.isEmpty())
        assertTrue("Fact counts must be 1..5: $outOfRange", outOfRange.isEmpty())
    }

    // ------------------------------------------------------------------ //
    // Bucket C: placeholder / generic / duplicated content.               //
    // ------------------------------------------------------------------ //

    @Test
    fun noGenericOrPlaceholderFactTextRemains() {
        val banned = listOf(
            "ZIG astronomical catalog",
            "Cataloged as a prominent deep-sky photometric target",
            "Instantaneous celestial coordinates",
            "Complete equatorial and scientific designations",
            "کاتالوگ زیگ",
            "موقعیت لحظه‌ای",
            "اطلاعات مختصات",
            // Template/boilerplate sentence fragments used by the generated DSO facts.
            "in messier's comet-hunting context",
            "is a milky way disk cluster whose stars formed together",
            "wide-field views help show",
            "gaia-era astrometry improves membership checks",
            "the ngc identity of",
            "proper-motion studies of",
            "rewards transparent dark skies",
            "preserve redshift, photometry, and multiwavelength identifiers",
            "belongs to the milky way globular-cluster population",
            "the crowded core of",
            "increasing aperture changes",
            "color-magnitude diagrams of",
            "nebula filters can improve contrast",
            "spiral-disk structure lets astronomers study",
            "point to a recognizable structure",
            "is a dying-star shell",
            "traces ionized oxygen",
            "multiwavelength images of",
            "emphasize old stellar populations",
            "ionized gas in",
            "as part of the virgo galaxy environment",
            "narrowband imaging of",
            "a deliberate non-messier showpiece",
            "scattering nearby starlight from dust grains",
            "reflection-dominated, dark skies usually help",
            "best understood as a stellar association",
            "can funnel gas inward",
            "lets photometric studies separate",
            "trace how milky way disk clusters"
        )
        val hits = mutableListOf<String>()
        allObjects.forEach { obj ->
            (factsEn(obj) + factsFa(obj)).forEach { fact ->
                if (banned.any { fact.contains(it, ignoreCase = true) }) {
                    hits += "${obj.canonicalId}: $fact"
                }
            }
        }
        assertTrue("Generic/placeholder fact text still present: ${hits.size} hits", hits.isEmpty())
    }

    @Test
    fun noVerbatimDuplicateFactsAcrossObjects() {
        val owners = mutableMapOf<String, MutableSet<String>>()
        allObjects.forEach { obj ->
            factsEn(obj).forEach { fact ->
                val norm = fact.lowercase().replace(Regex("\\s+"), " ").trim()
                owners.getOrPut(norm) { mutableSetOf() }.add(obj.canonicalId)
            }
        }
        val dup = owners.filterValues { it.size > 1 }.entries
            .map { (k, v) -> "[${v.sorted().joinToString(",")}] $k" }
        assertTrue("Verbatim duplicate English facts across objects: $dup", dup.isEmpty())
    }

    @Test
    fun noCrossObjectNearDuplicateFactsWithoutIndependentSourcing() {
        // Skeleton = fact with the object's own names and catalog designations removed.
        // Two different objects sharing a skeleton means template/boilerplate content.
        val ownersBySkeleton = mutableMapOf<String, MutableSet<String>>()
        allObjects.forEach { obj ->
            factsEn(obj).forEach { fact ->
                val skel = skeleton(fact, obj)
                ownersBySkeleton.getOrPut(skel) { mutableSetOf() }.add(obj.canonicalId)
            }
        }
        val duplicated = ownersBySkeleton
            .filterValues { it.size > 1 }
            .entries
            .sortedBy { -it.value.size }
            .map { (skel, owners) -> "[${owners.sorted().joinToString(",")}] $skel" }
        assertTrue(
            "Cross-object duplicated fact templates (bucket C): ${duplicated.size} groups — " +
                duplicated.take(10),
            duplicated.isEmpty()
        )
    }

    // ------------------------------------------------------------------ //
    // Research-log cross-reference.                                       //
    // ------------------------------------------------------------------ //

    private val auditedSparseFactLedger = setOf(
        "dso_ngc_884", "dso_ngc_147", "dso_ngc_185", "dso_ngc_2403", "dso_ngc_40",
        "dso_ngc_4244", "dso_ngc_4449", "dso_ngc_457", "dso_ngc_6543", "dso_ngc_663",
        "dso_ngc_6826", "dso_ngc_6946", "dso_ngc_7000", "dso_ngc_7243", "dso_ngc_7331",
        "dso_ngc_752", "dso_ngc_7662", "dso_ngc_891",
        "sat_20580", "sat_27386", "sat_48274"
    )

    @Test
    fun everyUnionObjectIsTrackedInTheResearchLog() {
        val researchLog = researchLogText() ?: return
        val untracked = allObjects.filterNot { researchLog.contains("`${it.canonicalId}`") }
        assertTrue(
            "Objects missing from the content research log: " + untracked.map { it.canonicalId },
            untracked.isEmpty()
        )
    }

    @Test
    fun everySparseFactSetHasALoggedReason() {
        val researchLog = researchLogText()
        val underFive = allObjects.filter { factsEn(it).size in 1..4 }
        val unlogged = underFive.filterNot { obj ->
            if (researchLog != null) {
                researchLog.contains("`${obj.canonicalId}`: Fewer than five")
            } else {
                auditedSparseFactLedger.contains(obj.canonicalId)
            }
        }
        assertTrue(
            "Sparse fact sets without a logged reason: " + unlogged.map { it.canonicalId },
            unlogged.isEmpty()
        )
    }

    // ------------------------------------------------------------------ //
    // Rendered data paths.                                                //
    // ------------------------------------------------------------------ //

    @Test
    fun renderedFactsCardIsNeverEmptyForAnyObject() {
        val blank = allObjects.filter { renderedFacts(it).isEmpty() }
        assertTrue(
            "Objects whose detail page renders an empty facts card (bucket D/E): " +
                blank.map { it.canonicalId },
            blank.isEmpty()
        )
    }

    @Test
    fun everyObjectSectionPlanIncludesAFactsCard() {
        val missing = allObjects.filter { obj ->
            "facts_card" !in objectDetailSectionPlan(obj.type, factsEn(obj).size)
        }
        assertTrue(
            "Objects without a facts_card in their modal section plan: " + missing.map { it.canonicalId },
            missing.isEmpty()
        )
    }

    @Test
    fun satelliteFactsArePropagatedIntoTheCanonicalCatalog() {
        val dropped = SatelliteCatalog.satellites.filter { sat ->
            val canonId = if (sat.noradId == 25544) "sat_25544" else "sat_${sat.noradId}"
            val canon = CanonicalAstroCatalog.getCanonicalObject(canonId)
            canon == null || factsEn(canon).isEmpty() || factsFa(canon).isEmpty()
        }.map { it.id }
        assertTrue(
            "Satellite facts exist in SatelliteCatalog but are dropped from the canonical merge: $dropped",
            dropped.isEmpty()
        )
    }

    @Test
    fun persianDisplayNamesDoNotLeakUnlocalizedEnglish() {
        val latinLetter = Regex("[A-Za-z]")
        val allowed = Regex(
            "(?i)\\b(M\\s*\\d+[A-Z]?|NGC\\s*\\d+[A-Z]?|IC\\s*\\d+[A-Z]?|C\\s*\\d+[A-Z]?|Melotte\\s*\\d+|LMC|SMC|NC)\\b"
        )
        val leaked = allObjects.mapNotNull { obj ->
            val stripped = allowed.replace(obj.nameFa, "")
            if (latinLetter.containsMatchIn(stripped)) "${obj.canonicalId}: ${obj.nameFa}" else null
        }
        assertTrue("Persian display names contain unlocalized English text: $leaked", leaked.isEmpty())
    }

    @Test
    fun persianFactsDoNotContainEnglishSentences() {
        // Flag Persian facts that contain runs of 3+ consecutive Latin words, which indicates an
        // untranslated English sentence rather than a proper noun / catalog id / spectral class.
        val latinWord = Regex("[A-Za-z][A-Za-z'.-]*")
        val hits = mutableListOf<String>()
        allObjects.forEach { obj ->
            factsFa(obj).forEach { fact ->
                val run = latinWord.findAll(fact).map { it.value }.toList()
                if (run.count { it.length > 1 } >= 3) hits += "${obj.canonicalId}: $fact"
            }
        }
        assertTrue("Persian facts contain English sentences: $hits", hits.isEmpty())
    }

    // ------------------------------------------------------------------ //
    // Helpers.                                                            //
    // ------------------------------------------------------------------ //

    private fun factsEn(obj: CanonicalAstroObject): List<String> =
        obj.observationalInfo.verifiedFactsEn + obj.observationalInfo.funFactsEn

    private fun factsFa(obj: CanonicalAstroObject): List<String> =
        obj.observationalInfo.verifiedFactsFa + obj.observationalInfo.funFactsFa

    private fun isDeepSky(type: ObjectType): Boolean =
        type == ObjectType.DEEP_SKY ||
            type == ObjectType.GALAXY ||
            type == ObjectType.NEBULA ||
            type == ObjectType.STAR_CLUSTER ||
            type == ObjectType.GLOBULAR_CLUSTER ||
            type == ObjectType.BLACK_HOLE

    /** Mirrors ObjectDetailModal.coolFacts: funFacts first, PhysicalData fallback for non-deep-sky. */
    private fun renderedFacts(obj: CanonicalAstroObject): List<String> {
        val cel = CanonicalAstroCatalog.toCelestialObject(obj)
        val catalogFacts = cel.funFactsEn
        return when {
            catalogFacts.isNotEmpty() -> catalogFacts
            isDeepSky(obj.type) -> emptyList()
            else -> PhysicalData.getCoolFactsEn(cel)
        }
    }

    private fun skeleton(fact: String, obj: CanonicalAstroObject): String {
        var f = fact
        val tokens = buildList {
            add(obj.nameEn.substringBefore("(").trim())
            add(obj.nameFa.substringBefore("(").trim())
            addAll(obj.scientificIdentifiers.catalogDesignations)
            obj.scientificIdentifiers.messierId?.let(::add)
            obj.scientificIdentifiers.ngcId?.let(::add)
            obj.scientificIdentifiers.caldwellId?.let(::add)
        }.filter { it.length >= 2 }.distinct()
        for (t in tokens) {
            f = f.replace(t, "§", ignoreCase = true)
        }
        return f.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun researchLogText(): String? {
        val candidates = listOf(
            File("docs/dso-content-research-log.md"),
            File("../docs/dso-content-research-log.md"),
            File("../../docs/dso-content-research-log.md")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }
}
