package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.domain.CanonicalAstroObject
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogIntegrityTest {

    @Test
    fun canonicalIdsAreUniqueAcrossWholeCatalog() {
        val duplicates = duplicateValues(
            CanonicalAstroCatalog.getAllCanonicalObjects(),
            key = { it.canonicalId }
        )

        assertTrue("Duplicate canonical IDs found: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun catalogDesignationsResolveToOneCanonicalObject() {
        val entries = CanonicalAstroCatalog.getAllCanonicalObjects().flatMap { obj ->
            normalizedDesignationKeys(obj).map { key -> key to obj.canonicalId }
        }
        val duplicates = entries
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.distinct().sorted() }
            .filterValues { it.size > 1 }

        assertTrue("Catalog designations attached to multiple objects: $duplicates", duplicates.isEmpty())
    }

    @Test
    fun englishAndPersianDisplayNamesAreUnique() {
        val objects = CanonicalAstroCatalog.getAllCanonicalObjects()
        val duplicateEnglish = duplicateValues(objects, key = { it.nameEn.trim().lowercase() })
        val duplicatePersian = duplicateValues(objects, key = { it.nameFa.trim().lowercase() })

        assertTrue("Duplicate English display names found: $duplicateEnglish", duplicateEnglish.isEmpty())
        assertTrue("Duplicate Persian display names found: $duplicatePersian", duplicatePersian.isEmpty())
    }

    @Test
    fun persianDisplayNamesDoNotLeakSilentEnglish() {
        val leaked = CanonicalAstroCatalog.getAllCanonicalObjects()
            .mapNotNull { obj ->
                val stripped = allowedPersianNameLatinTokens.replace(obj.nameFa, "")
                if (latinLetter.containsMatchIn(stripped)) "${obj.canonicalId}: ${obj.nameFa}" else null
            }

        assertTrue("Persian display names contain unlocalized English text: $leaked", leaked.isEmpty())
    }

    @Test
    fun verifiedFactsAreBilingualAndDoNotUseGenericFallbackTemplates() {
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
        val generic = mutableListOf<String>()

        CanonicalAstroCatalog.getAllCanonicalObjects().forEach { obj ->
            val factsEn = obj.observationalInfo.verifiedFactsEn + obj.observationalInfo.funFactsEn
            val factsFa = obj.observationalInfo.verifiedFactsFa + obj.observationalInfo.funFactsFa
            if (factsEn.size != factsFa.size) {
                mismatched += "${obj.canonicalId}: en=${factsEn.size} fa=${factsFa.size}"
            }
            (factsEn + factsFa).forEach { fact ->
                if (bannedSnippets.any { fact.contains(it, ignoreCase = true) }) {
                    generic += "${obj.canonicalId}: $fact"
                }
            }
        }

        assertTrue("Bilingual fact count mismatch: $mismatched", mismatched.isEmpty())
        assertTrue("Generic fact fallback text still present: $generic", generic.isEmpty())
    }

    @Test
    fun approvedBorderlinePairsRemainSeparate() {
        val m102 = CanonicalAstroCatalog.getCanonicalObject("M102")
        val ngc3115 = CanonicalAstroCatalog.getCanonicalObject("NGC 3115")
        val veilWest = CanonicalAstroCatalog.getCanonicalObject("NGC 6960")
        val veilEast = CanonicalAstroCatalog.getCanonicalObject("NGC 6992")

        assertTrue(m102 != null && ngc3115 != null && m102.canonicalId != ngc3115.canonicalId)
        assertTrue(veilWest != null && veilEast != null && veilWest.canonicalId != veilEast.canonicalId)
    }

    @Test
    fun closeCoordinateDuplicatesAreRejectedUnlessExplicitlyAllowlisted() {
        val allowlistedPairs = setOf(
            pairKey("dso_m102", "dso_ngc_3115"),
            pairKey("dso_ngc_6960", "dso_ngc_6992"),
            pairKey("star_bsc_hr_2693", "star_cma_wezen"),
            pairKey("star_bsc_hr_545", "star_bsc_hr_546"),
            pairKey("star_bsc_hr_3208", "star_bsc_hr_3209"),
            pairKey("star_bsc_hr_4374", "star_bsc_hr_4375"),
            pairKey("star_bsc_hr_4825", "star_bsc_hr_4826"),
            pairKey("star_bsc_hr_4968", "star_bsc_hr_4969"),
            pairKey("star_bsc_hr_5977", "star_bsc_hr_5978"),
            pairKey("star_bsc_hr_6369", "star_bsc_hr_6370")
        )
        val positioned = CanonicalAstroCatalog.getAllCanonicalObjects()
            .filter { it.staticPosition != null }
        val suspected = mutableListOf<String>()

        for (i in positioned.indices) {
            val first = positioned[i]
            for (j in i + 1 until positioned.size) {
                val second = positioned[j]
                if (first.type != second.type) continue
                val key = pairKey(first.canonicalId, second.canonicalId)
                if (key in allowlistedPairs) continue
                val separation = angularSeparationDegrees(first, second)
                val deltaMag = kotlin.math.abs(
                    first.physicalProperties.magnitude - second.physicalProperties.magnitude
                )
                if (separation <= 0.05 && deltaMag <= 0.5) {
                    suspected += "${first.canonicalId} <-> ${second.canonicalId} sep=$separation dMag=$deltaMag"
                }
            }
        }

        assertTrue("Coordinate-proximity duplicates found: $suspected", suspected.isEmpty())
    }

    private fun duplicateValues(
        objects: List<CanonicalAstroObject>,
        key: (CanonicalAstroObject) -> String
    ): Map<String, List<String>> {
        return objects
            .groupBy(key) { it.canonicalId }
            .mapValues { (_, ids) -> ids.distinct().sorted() }
            .filterKeys { it.isNotBlank() }
            .filterValues { it.size > 1 }
    }

    private fun normalizedDesignationKeys(obj: CanonicalAstroObject): Set<String> {
        val ids = obj.scientificIdentifiers
        val raw = buildList {
            addAll(ids.catalogDesignations)
            addAll(obj.legacyIds)
            ids.messierId?.let(::add)
            ids.ngcId?.let(::add)
            ids.caldwellId?.let(::add)
            ids.noradId?.let { add("NORAD $it") }
            ids.hipId?.let { add("HIP $it") }
            ids.hdId?.let { add("HD $it") }
            ids.bayerDesignation.takeIf { it.isNotBlank() }?.let { add("BAYER $it") }
        }
        return raw.mapNotNull(::normalizedDesignationKey).toSet()
    }

    private fun normalizedDesignationKey(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val compact = trimmed.lowercase().replace(Regex("[^a-z0-9]+"), "")
        return when {
            compact.matches(Regex("m\\d+[a-z]?")) -> compact
            compact.matches(Regex("ngc\\d+[a-z]?")) -> compact
            compact.matches(Regex("ic\\d+[a-z]?")) -> compact
            compact.matches(Regex("c\\d+[a-z]?")) -> compact
            compact.matches(Regex("melotte\\d+[a-z]?")) -> compact
            compact.matches(Regex("mel\\d+[a-z]?")) -> "melotte${compact.removePrefix("mel")}"
            compact.matches(Regex("norad\\d+")) -> compact
            compact.matches(Regex("hip\\d+")) -> compact
            compact.matches(Regex("hd\\d+")) -> compact
            trimmed.startsWith("BAYER ") -> "bayer:${trimmed.removePrefix("BAYER ").lowercase()}"
            else -> null
        }
    }

    private val latinLetter = Regex("[A-Za-z]")

    private val allowedPersianNameLatinTokens = Regex(
        pattern = "(?i)\\b(M\\s*\\d+[A-Z]?|NGC\\s*\\d+[A-Z]?|IC\\s*\\d+[A-Z]?|C\\s*\\d+[A-Z]?|Melotte\\s*\\d+|LMC|SMC|NC)\\b"
    )

    private fun pairKey(a: String, b: String): Set<String> = setOf(a, b)

    private fun angularSeparationDegrees(a: CanonicalAstroObject, b: CanonicalAstroObject): Double {
        val posA = requireNotNull(a.staticPosition)
        val posB = requireNotNull(b.staticPosition)
        val ra1 = Math.toRadians(posA.raDeg)
        val dec1 = Math.toRadians(posA.decDeg)
        val ra2 = Math.toRadians(posB.raDeg)
        val dec2 = Math.toRadians(posB.decDeg)
        val cosine = (sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2))
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }
}
