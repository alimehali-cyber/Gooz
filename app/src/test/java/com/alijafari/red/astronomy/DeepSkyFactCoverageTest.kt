package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.domain.CanonicalAstroObject
import com.alijafari.red.astronomy.domain.ObjectType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSkyFactCoverageTest {

    @Test
    fun everyFinalDeepSkyObjectHasFactsOrTrackedExemption() {
        val objects = deepSkyObjects()
        assertEquals("Corrected canonical deep-sky catalog count changed", 239, objects.size)

        val zeroCoverage = objects.filter { obj ->
            obj.observationalInfo.verifiedFactsEn.isEmpty() && obj.observationalInfo.funFactsEn.isEmpty() &&
                obj.observationalInfo.verifiedFactsFa.isEmpty() && obj.observationalInfo.funFactsFa.isEmpty()
        }
        val untrackedZeroCoverage = zeroCoverage.filterNot { it.canonicalId in zeroFactExemptions }

        assertTrue(
            "Deep-sky objects without facts must have an explicit exemption with a reason: " +
                untrackedZeroCoverage.map { it.canonicalId },
            untrackedZeroCoverage.isEmpty()
        )
    }

    @Test
    fun deepSkyFactsAreBilingualNonGenericAndNoMoreThanFivePerObject() {
        val bannedSnippets = listOf(
            "ZIG astronomical catalog",
            "Cataloged as a prominent deep-sky photometric target",
            "Instantaneous celestial coordinates",
            "Complete equatorial and scientific designations",
            "کاتالوگ زیگ",
            "موقعیت لحظه‌ای",
            "اطلاعات مختصات"
        )
        val mismatched = mutableListOf<String>()
        val outOfRange = mutableListOf<String>()
        val generic = mutableListOf<String>()

        deepSkyObjects().forEach { obj ->
            val factsEn = factsEn(obj)
            val factsFa = factsFa(obj)
            val exempt = obj.canonicalId in zeroFactExemptions

            if (factsEn.size != factsFa.size) {
                mismatched += "${obj.canonicalId}: en=${factsEn.size} fa=${factsFa.size}"
            }
            if (factsEn.size !in 0..5 || factsFa.size !in 0..5) {
                outOfRange += "${obj.canonicalId}: en=${factsEn.size} fa=${factsFa.size}"
            }
            if (!exempt && (factsEn.isEmpty() || factsFa.isEmpty())) {
                outOfRange += "${obj.canonicalId}: non-exempt empty fact side"
            }
            (factsEn + factsFa).forEach { fact ->
                if (bannedSnippets.any { fact.contains(it, ignoreCase = true) }) {
                    generic += "${obj.canonicalId}: $fact"
                }
            }
        }

        assertTrue("Bilingual fact count mismatch: $mismatched", mismatched.isEmpty())
        assertTrue("Fact counts must be 0..5 and non-empty unless exempt: $outOfRange", outOfRange.isEmpty())
        assertTrue("Generic/template fact text found: $generic", generic.isEmpty())
    }

    @Test
    fun everySparseDeepSkyFactSetHasResearchLogReason() {
        val researchLog = researchLogText()
        val underFive = deepSkyObjects().filter { obj -> factsEn(obj).size in 1..4 || factsFa(obj).size in 1..4 }
        val unlogged = underFive.filterNot { obj ->
            (researchLog == null || researchLog.contains("`${obj.canonicalId}`: Fewer than five")) &&
                partialCoverageReasonLedger.contains(obj.canonicalId)
        }

        assertEquals(
            "Sparse fact set ledger should match the audited partial-coverage object count",
            18,
            underFive.size
        )
        assertTrue(
            "Objects with fewer than five facts need explicit research-log reasons: " +
                unlogged.map { it.canonicalId },
            unlogged.isEmpty()
        )
    }

    @Test
    fun researchLogTracksEveryDeepSkyObjectWithFactCoverage() {
        val researchLog = researchLogText() ?: return
        val untracked = deepSkyObjects().filterNot { obj -> researchLog.contains("`${obj.canonicalId}`") }

        assertTrue(
            "Every final deep-sky object must appear in the content research log: " +
                untracked.map { it.canonicalId },
            untracked.isEmpty()
        )
    }

    private fun deepSkyObjects(): List<CanonicalAstroObject> =
        CanonicalAstroCatalog.getAllCanonicalObjects().filter { it.type.isDeepSkyForFactCoverage() }

    private fun factsEn(obj: CanonicalAstroObject): List<String> =
        obj.observationalInfo.verifiedFactsEn + obj.observationalInfo.funFactsEn

    private fun factsFa(obj: CanonicalAstroObject): List<String> =
        obj.observationalInfo.verifiedFactsFa + obj.observationalInfo.funFactsFa

    private fun ObjectType.isDeepSkyForFactCoverage(): Boolean =
        this == ObjectType.DEEP_SKY ||
            this == ObjectType.GALAXY ||
            this == ObjectType.NEBULA ||
            this == ObjectType.STAR_CLUSTER ||
            this == ObjectType.GLOBULAR_CLUSTER ||
            this == ObjectType.BLACK_HOLE

    private fun researchLogText(): String? {
        val candidates = listOf(
            File("docs/dso-content-research-log.md"),
            File("../docs/dso-content-research-log.md"),
            File("../../docs/dso-content-research-log.md")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
    }

    private val zeroFactExemptions: Map<String, String> = emptyMap()

    private val partialCoverageReasonLedger = setOf(
        "dso_ngc_884",
        "dso_ngc_147",
        "dso_ngc_185",
        "dso_ngc_2403",
        "dso_ngc_40",
        "dso_ngc_4244",
        "dso_ngc_4449",
        "dso_ngc_457",
        "dso_ngc_6543",
        "dso_ngc_663",
        "dso_ngc_6826",
        "dso_ngc_6946",
        "dso_ngc_7000",
        "dso_ngc_7243",
        "dso_ngc_7331",
        "dso_ngc_752",
        "dso_ngc_7662",
        "dso_ngc_891"
    )
}
