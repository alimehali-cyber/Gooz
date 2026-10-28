package com.alijafari.red.astronomy.astro_engine

import com.alijafari.red.astronomy.domain.CalendarSystem
import com.alijafari.red.astronomy.util.toPersianDigits
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * High-precision Eclipse prediction and local circumstances engine.
 *
 * Implements:
 * - Meeus (1998) Chapter 54 (Besselian elements & Eclipse predictions)
 * - Meeus (1998) Chapter 49 (Phases of the Moon with ΔT TT→UTC conversion)
 * - NASA Goddard / Fred Espenak 5-Millennium Canon of Solar & Lunar Eclipses
 * - High-precision topocentric local circumstances (Start C1/P1, Max, End C4/P4, Alt/Az, Obscuration %)
 */
class EclipseEngine {

    companion object {
        private const val DEG2RAD = Math.PI / 180.0
        private const val RAD2DEG = 180.0 / Math.PI
        private const val SYNODIC_MONTH_DAYS = 29.530588853
        private const val EARTH_EQUATORIAL_RADIUS_KM = 6378.137
        private const val EARTH_FLATTENING = 1.0 / 298.257223563
        private const val SUN_RADIUS_KM = 696000.0
        private const val MOON_RADIUS_KM = 1737.4
        private const val AU_KM = 149597870.7

        // Shared singleton instance
        val instance = EclipseEngine()

        /**
         * Convenience helper to evaluate next eclipses for a user location.
         */
        fun getNextEclipses(
            nowMs: Long,
            userLatDeg: Double,
            userLonDeg: Double,
            elevationM: Double = 0.0,
            timezoneId: String = "Asia/Tehran",
            calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI
        ): Pair<EclipseResult, EclipseResult> {
            return instance.getNextEclipses(
                nowMs = nowMs,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem
            )
        }

        /**
         * Convenience helper returning the next [count] solar and next [count] lunar
         * eclipses, each evaluated for the user's location.
         *
         * This is the multi-eclipse counterpart of [getNextEclipses]; the Home screen
         * uses it with count = 3 to let the Solar and Lunar chips list three eclipses
         * each. Both companion entry points share the same singleton, so the resolved
         * results are consistent between them.
         */
        fun getNextEclipseSeries(
            nowMs: Long,
            userLatDeg: Double,
            userLonDeg: Double,
            elevationM: Double = 0.0,
            timezoneId: String = "Asia/Tehran",
            calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI,
            count: Int = 3
        ): Pair<List<EclipseResult>, List<EclipseResult>> {
            return instance.getNextEclipseSeries(
                nowMs = nowMs,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem,
                count = count
            )
        }

        /**
         * Convenience helper for detailed eclipse info.
         */
        fun getDetailedEclipseInfo(
            result: EclipseResult,
            userLatDeg: Double,
            userLonDeg: Double,
            timezoneId: String = "Asia/Tehran"
        ): DetailedEclipseInfo {
            return instance.getDetailedEclipseInfo(result, userLatDeg, userLonDeg, timezoneId)
        }

        fun computeDetailedInfo(
            result: EclipseResult,
            userLatDeg: Double,
            userLonDeg: Double,
            timezoneId: String = "Asia/Tehran"
        ): DetailedEclipseInfo {
            return instance.getDetailedEclipseInfo(result, userLatDeg, userLonDeg, timezoneId)
        }
    }

    /**
     * Catalogue-wide superlatives, computed once per engine instance and shared
     * with [EclipseVisibility] so per-eclipse "significance" text can say things
     * like "the deepest in the catalogue" without rescanning all 77 entries on
     * every recomposition.
     *
     * A missing category is reported as -1L rather than 0L, because 0 ms is the
     * Unix epoch and is (just) a legal instant, whereas -1 never is.
     */
    val catalogueContext: CatalogueContext by lazy {
        val lunar = canonicalEclipses.filter { !it.isSolar }

        val deepest = lunar
            .filter { it.type != EclipseType.PENUMBRAL_LUNAR }
            .maxByOrNull { it.magnitude }
        val longest = lunar
            .filter { it.type == EclipseType.TOTAL_LUNAR }
            .maxByOrNull { it.durationTotalSeconds }
        val longestCentral = canonicalEclipses
            .filter { it.isSolar && it.durationTotalSeconds > 0 }
            .maxByOrNull { it.durationTotalSeconds }

        // A tetrad is a run of four or more consecutive TOTAL lunar eclipses with
        // no partial or penumbral eclipse in between.
        val tetrad = LinkedHashSet<Long>()
        var run = ArrayList<EclipseEvent>()
        for (e in lunar.sortedBy { it.maximumMs }) {
            if (e.type == EclipseType.TOTAL_LUNAR) {
                run.add(e)
            } else {
                if (run.size >= 4) run.forEach { tetrad.add(it.maximumMs) }
                run = ArrayList()
            }
        }
        if (run.size >= 4) run.forEach { tetrad.add(it.maximumMs) }

        CatalogueContext(
            deepestLunarMs = deepest?.maximumMs ?: -1L,
            longestLunarTotalityMs = longest?.maximumMs ?: -1L,
            longestCentralSolarMs = longestCentral?.maximumMs ?: -1L,
            tetradMemberMs = tetrad
        )
    }

    enum class EclipseType {
        TOTAL_SOLAR,
        ANNULAR_SOLAR,
        HYBRID_SOLAR,
        PARTIAL_SOLAR,
        TOTAL_LUNAR,
        PARTIAL_LUNAR,
        PENUMBRAL_LUNAR,
        NONE_SOLAR,
        NONE_LUNAR;

        // Aliases for compatibility
        companion object {
            val SOLAR_TOTAL get() = TOTAL_SOLAR
            val SOLAR_ANNULAR get() = ANNULAR_SOLAR
            val SOLAR_PARTIAL get() = PARTIAL_SOLAR
            val LUNAR_TOTAL get() = TOTAL_LUNAR
            val LUNAR_PARTIAL get() = PARTIAL_LUNAR
            val LUNAR_PENUMBRAL get() = PENUMBRAL_LUNAR
        }
    }

    data class EclipseEvent(
        val type: EclipseType,
        val maximumMs: Long,
        val magnitude: Double,
        val saros: Int,
        val gamma: Double,
        val nameEn: String,
        val nameFa: String,
        val durationTotalSeconds: Int,
        val maxTotalityRegionEn: String,
        val maxTotalityRegionFa: String,
        val descriptionEn: String,
        val descriptionFa: String,
        val isSolar: Boolean,
        // Lunar contact times P1/U1/U2/U3/U4/P4. Every canonical *lunar* entry sets
        // these explicitly from the NASA catalogue durations, and the dynamic
        // (Meeus) search sets them too.
        // They default to `maximumMs` rather than to invented offsets: a solar
        // eclipse has no penumbral/umbral phases, so a solar entry must never
        // carry fabricated contacts. Only the lunar code path reads these fields.
        val penumbralStartMs: Long = maximumMs,
        val umbralStartMs: Long = maximumMs,
        val totalityStartMs: Long = maximumMs,
        val totalityEndMs: Long = maximumMs,
        val umbralEndMs: Long = maximumMs,
        val penumbralEndMs: Long = maximumMs
    ) {
        val id: String get() = "${if (isSolar) "solar" else "lunar"}_$maximumMs"
        val dateUtcMs: Long get() = maximumMs
        val peakTimeMs: Long get() = maximumMs
        val isTotal: Boolean get() = type == EclipseType.TOTAL_SOLAR || type == EclipseType.TOTAL_LUNAR
    }

    data class EclipseResult(
        val event: EclipseEvent,
        val localNameEn: String,
        val localNameFa: String,
        val isLocallyVisible: Boolean,
        val localObscurationPercent: Int,
        val localMagnitude: Double,
        val localStartTimeMs: Long,
        val localPeakTimeMs: Long,
        val localEndTimeMs: Long,
        val targetAltitudeDeg: Double,
        val targetAzimuthDeg: Double,
        val formattedDateEn: String,
        val formattedDateFa: String,
        val localVisibilityTextEn: String,
        val localVisibilityTextFa: String,
        val daysRemaining: Int,
        /**
         * Whether there is anything worth looking at from the observer's location,
         * and why. This is stricter than [isLocallyVisible]: an eclipse can be
         * above the horizon and still have no observable effect (a shallow partial
         * solar eclipse, or a penumbral lunar eclipse below the ~0.6 detection
         * threshold). See [EclipseVisibility] for the criteria and their sources.
         */
        val visibility: EclipseVisibility.Assessment,
        /**
         * Observer-facing extras: traditional name, predicted colour (Danjon band
         * for total lunar eclipses) and what makes the event significant. Depends
         * only on the [event], not on the location, but is stored here so the UI
         * needs a single object.
         */
        val insight: EclipseVisibility.Insight
    )

    /**
     * Catalogue-wide superlatives, computed once and cached, so per-eclipse
     * insight text can say "the deepest in the catalogue" without rescanning
     * all 77 entries on every recomposition.
     */
    data class CatalogueContext(
        /** greatest-eclipse instant of the largest-umbral-magnitude lunar eclipse */
        val deepestLunarMs: Long,
        /** greatest-eclipse instant of the longest lunar totality */
        val longestLunarTotalityMs: Long,
        /** greatest-eclipse instant of the longest central (total/annular) solar phase */
        val longestCentralSolarMs: Long,
        /** greatest-eclipse instants of every lunar eclipse inside a tetrad */
        val tetradMemberMs: Set<Long>
    )

    data class DetailedEclipseInfo(
        val event: EclipseEvent,
        val result: EclipseResult,
        val daysRemaining: Int,
        val localStartTimeStr: String,
        val localPeakTimeStr: String,
        val localEndTimeStr: String,
        val durationTextEn: String,
        val durationTextFa: String,
        val obscurationPercent: Int,
        val targetAltDeg: Double,
        val targetAzDeg: Double,
        val safetyGuideEn: String,
        val safetyGuideFa: String,
        val localMagnitude: Double = result.localMagnitude,
        val maxAltitudeDeg: Double = targetAltDeg,
        val maxAzimuthDeg: Double = targetAzDeg,
        val safetyWarningEn: String = safetyGuideEn,
        val safetyWarningFa: String = safetyGuideFa,
        val observationTipEn: String = "",
        val observationTipFa: String = ""
    )

    private val lunarSolar = LunarSolarEngine()

    /**
     * NASA / Fred Espenak (GSFC) canon of eclipses, 2024 to 2040.
     *
     * Sources — every number below is copied from, or derived from, these catalogues:
     *   - Five Millennium Catalog of Solar Eclipses: 2001 to 2100
     *     https://eclipse.gsfc.nasa.gov/SEcat5/SE2001-2100.html
     *   - Five Millennium Catalog of Lunar Eclipses: 2001 to 2100
     *     https://eclipse.gsfc.nasa.gov/LEcat5/LE2001-2100.html
     *   - Solar/Lunar Eclipse decade tables 2021-2030 and 2031-2040
     *
     * `maximumMs` is the UTC instant of greatest eclipse (catalogue TD minus Delta-T).
     * `magnitude` is the NASA global magnitude: for solar eclipses the fraction of the
     * Sun's diameter obscured, for umbral/partial/total lunar eclipses the umbral
     * magnitude, and for PENUMBRAL_LUNAR the penumbral magnitude.
     * `durationTotalSeconds` is the central duration for total/annular/hybrid solar
     * eclipses, the total-phase duration for total lunar eclipses, and 0 otherwise.
     * Lunar contacts P1..P4 are derived from the catalogue's penumbral / partial /
     * total durations; solar entries carry no global contacts (they are computed
     * topocentrically for the observer instead — see evaluateSolarEclipseLocal).
     */
    private val canonicalEclipses = listOf(
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1711350780000L, // 2024-03-25 07:13 UTC
            magnitude = 0.9557,
            saros = 113,
            gamma = 1.0609,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Antarctica, Alaska, NE Asia",
            maxTotalityRegionFa = "قاره آمریکا، جنوبگان، آلاسکا و شمال شرق آسیا",
            descriptionEn = "A deep penumbral lunar eclipse where 95.6% of the Moon disk passed through Earth's penumbral shadow.",
            descriptionFa = "خسوف عمیق نیم‌سایه‌ای که طی آن ۹۵.۶٪ قرص ماه وارد نیم‌سایه زمین شد.",
            isSolar = false,
            penumbralStartMs = 1711342407000L, // 04:53 UTC
            umbralStartMs = 1711350780000L, // 07:13 UTC
            totalityStartMs = 1711350780000L, // 07:13 UTC
            totalityEndMs = 1711350780000L, // 07:13 UTC
            umbralEndMs = 1711350780000L, // 07:13 UTC
            penumbralEndMs = 1711359153000L // 09:32 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 1712600220000L, // 2024-04-08 18:17 UTC
            magnitude = 1.0566,
            saros = 139,
            gamma = 0.3431,
            nameEn = "Great North American Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل آمریکای شمالی",
            durationTotalSeconds = 268,
            maxTotalityRegionEn = "Mexico, United States (Texas to Maine), Eastern Canada",
            maxTotalityRegionFa = "مکزیک، ایالات متحده آمریکا (تگزاس تا مین)، شرق کانادا",
            descriptionEn = "A major total solar eclipse with a maximum totality duration of 4 minutes and 28 seconds.",
            descriptionFa = "یکی از باشکوه‌ترین کسوف‌های قرن با مدت گرفت کامل ۴ دقیقه و ۲۸ ثانیه.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 1726627440000L, // 2024-09-18 02:44 UTC
            magnitude = 0.0848,
            saros = 118,
            gamma = -0.9792,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa, Middle East, Western Asia",
            maxTotalityRegionFa = "آمریکا، اروپا، آفریقا، خاورمیانه و غرب آسیا",
            descriptionEn = "A shallow partial lunar eclipse with 8.5% of the Moon in Earth's dark umbral shadow.",
            descriptionFa = "خسوف جزئی با ورود ۸.۵٪ از قرص ماه به بخش تاریک سایه زمین.",
            isSolar = false,
            penumbralStartMs = 1726620051000L, // 00:40 UTC
            umbralStartMs = 1726625556000L, // 02:12 UTC
            totalityStartMs = 1726627440000L, // 02:44 UTC
            totalityEndMs = 1726627440000L, // 02:44 UTC
            umbralEndMs = 1726629324000L, // 03:15 UTC
            penumbralEndMs = 1726634829000L // 04:47 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1727894700000L, // 2024-10-02 18:45 UTC
            magnitude = 0.9326,
            saros = 144,
            gamma = -0.3509,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 445,
            maxTotalityRegionEn = "Pacific Ocean, Southern Chile, Southern Argentina (Patagonia)",
            maxTotalityRegionFa = "اقیانوس آرام، جنوب شیلی و آرژانتین (پاتاگونیا)",
            descriptionEn = "A dramatic 'Ring of Fire' annular solar eclipse visible across the South Pacific and Patagonia.",
            descriptionFa = "کسوف حلقوی زیبا (حلقه آتش) در اقیانوس آرام و جنوب آمریکای جنوبی.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1741935540000L, // 2025-03-14 06:59 UTC
            magnitude = 1.1784,
            saros = 123,
            gamma = 0.3484,
            nameEn = "Total Lunar Eclipse (Blood Moon)",
            nameFa = "خسوف کامل (ماه سرخ)",
            durationTotalSeconds = 3924,
            maxTotalityRegionEn = "Americas, Pacific, Atlantic, Western Europe, Western Africa",
            maxTotalityRegionFa = "قاره آمریکا، اقیانوس آرام، غرب اروپا و غرب آفریقا",
            descriptionEn = "A total lunar eclipse lasting 65 minutes of totality turning the Moon copper-red.",
            descriptionFa = "ماه گرفتگی کامل به مدت ۶۵ دقیقه که طی آن قرص ماه به رنگ سرخ مسی درمی‌آید.",
            isSolar = false,
            penumbralStartMs = 1741924662000L, // 03:57 UTC
            umbralStartMs = 1741928991000L, // 05:09 UTC
            totalityStartMs = 1741933578000L, // 06:26 UTC
            totalityEndMs = 1741937502000L, // 07:31 UTC
            umbralEndMs = 1741942089000L, // 08:48 UTC
            penumbralEndMs = 1741946418000L // 10:00 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1743245280000L, // 2025-03-29 10:48 UTC
            magnitude = 0.9376,
            saros = 149,
            gamma = 1.0405,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "North America (NE), Greenland, Iceland, Northern & Western Europe, NW Russia",
            maxTotalityRegionFa = "شمال شرق آمریکا، گرینلند، ایسلند، شمال و غرب اروپا و روسیه",
            descriptionEn = "A deep partial solar eclipse visible across Greenland, Iceland, the UK, and Northern Europe.",
            descriptionFa = "کسوف جزئی عمیق با پوشش تا ۹۳٪ در گرینلند و شمال اروپا.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1757268720000L, // 2025-09-07 18:12 UTC
            magnitude = 1.3619,
            saros = 128,
            gamma = -0.2752,
            nameEn = "Total Lunar Eclipse (Blood Moon)",
            nameFa = "خسوف کامل (ماه خونین)",
            durationTotalSeconds = 4926,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia, Middle East, Iran",
            maxTotalityRegionFa = "اروپا، آفریقا، سراسر آسیا، استرالیا، خاورمیانه و ایران",
            descriptionEn = "A spectacular total lunar eclipse with 82 minutes of totality, wonderfully visible from Europe, Africa, Asia, and Iran.",
            descriptionFa = "خسوف کامل تماشایی با ۸۲ دقیقه گرفت کامل که در سراسر ایران، خاورمیانه، اروپا و آسیا عالی دیده می‌شود.",
            isSolar = false,
            penumbralStartMs = 1757258919000L, // 15:28 UTC
            umbralStartMs = 1757262438000L, // 16:27 UTC
            totalityStartMs = 1757266257000L, // 17:30 UTC
            totalityEndMs = 1757271183000L, // 18:53 UTC
            umbralEndMs = 1757275002000L, // 19:56 UTC
            penumbralEndMs = 1757278521000L // 20:55 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1758483720000L, // 2025-09-21 19:42 UTC
            magnitude = 0.855,
            saros = 154,
            gamma = -1.0651,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "South Pacific, New Zealand, Antarctica",
            maxTotalityRegionFa = "اقیانوس آرام جنوبی، نیوزیلند و جنوبگان",
            descriptionEn = "A partial solar eclipse visible in the South Pacific, New Zealand, and Antarctica.",
            descriptionFa = "کسوف جزئی قابل مشاهده در نیوزیلند و اقیانوس منجمد جنوبی.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1771330380000L, // 2026-02-17 12:13 UTC
            magnitude = 0.963,
            saros = 121,
            gamma = -0.9743,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 140,
            maxTotalityRegionEn = "Antarctica, Southern Indian Ocean; Partial in S Africa, S South America",
            maxTotalityRegionFa = "جنوبگان، اقیانوس هند جنوبی؛ جزئی در جنوب آفریقا و جنوب آمریکای جنوبی",
            descriptionEn = "An annular solar eclipse across Antarctica and the Southern oceans.",
            descriptionFa = "کسوف حلقوی در قاره قطب جنوب و آب‌های اقیانوس منجمد جنوبی.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1772537640000L, // 2026-03-03 11:34 UTC
            magnitude = 1.1507,
            saros = 133,
            gamma = -0.3765,
            nameEn = "Total Lunar Eclipse (Blood Moon)",
            nameFa = "خسوف کامل (ماه سرخ)",
            durationTotalSeconds = 3498,
            maxTotalityRegionEn = "Asia, Australia, Pacific, Americas",
            maxTotalityRegionFa = "شرق آسیا، استرالیا، اقیانوس آرام و قاره آمریکا",
            descriptionEn = "Total lunar eclipse with 59 minutes of totality visible across East Asia, Australia, and the Americas.",
            descriptionFa = "خسوف کامل با ۵۹ دقیقه گرفتگی کامل در شرق آسیا، استرالیا و آمریکا.",
            isSolar = false,
            penumbralStartMs = 1772527482000L, // 08:44 UTC
            umbralStartMs = 1772531424000L, // 09:50 UTC
            totalityStartMs = 1772535891000L, // 11:04 UTC
            totalityEndMs = 1772539389000L, // 12:03 UTC
            umbralEndMs = 1772543856000L, // 13:17 UTC
            penumbralEndMs = 1772547798000L // 14:23 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 1786556820000L, // 2026-08-12 17:47 UTC
            magnitude = 1.0386,
            saros = 126,
            gamma = 0.8977,
            nameEn = "Total Solar Eclipse (Great European Eclipse)",
            nameFa = "خورشیدگرفتگی کامل اروپا و اسپانیا",
            durationTotalSeconds = 138,
            maxTotalityRegionEn = "Greenland, Western Iceland, Northern Spain (A Coruña to Palma de Mallorca)",
            maxTotalityRegionFa = "گرینلند، ایسلند، شمال و مرکز اسپانیا؛ جزئی در سراسر اروپا و شمال آفریقا",
            descriptionEn = "A magnificent total solar eclipse sweeping across Greenland, Iceland, and Northern Spain. Partial eclipse visible across Europe and North Africa.",
            descriptionFa = "کسوف کامل در گرینلند، ایسلند و اسپانیا. فاز جزئی در سراسر اروپا و شمال آفریقا قابل مشاهده است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 1787890380000L, // 2026-08-28 04:13 UTC
            magnitude = 0.9299,
            saros = 138,
            gamma = 0.4964,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی عمیق",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa, Middle East, Western Asia",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا، خاورمیانه و غرب آسیا",
            descriptionEn = "A very deep partial lunar eclipse with 93% of the Moon covered by Earth's dark umbra.",
            descriptionFa = "خسوف جزئی بسیار عمیق که در آن ۹۳٪ قرص ماه در سایه تاریک زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 1787880246000L, // 01:24 UTC
            umbralStartMs = 1787884437000L, // 02:33 UTC
            totalityStartMs = 1787890380000L, // 04:13 UTC
            totalityEndMs = 1787890380000L, // 04:13 UTC
            umbralEndMs = 1787896323000L, // 05:52 UTC
            penumbralEndMs = 1787900514000L // 07:01 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1801929600000L, // 2027-02-06 16:00 UTC
            magnitude = 0.9281,
            saros = 131,
            gamma = -0.2952,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 471,
            maxTotalityRegionEn = "Chile, Argentina, Atlantic Ocean, Ivory Coast, Ghana, Nigeria",
            maxTotalityRegionFa = "شیلی، آرژانتین، اقیانوس اطلس، ساحل عاج، غنا و نیجریه",
            descriptionEn = "An annular solar eclipse spanning South America and West Africa with a duration of 7m 51s.",
            descriptionFa = "کسوف حلقوی زیبا به مدت ۷ دقیقه و ۵۱ ثانیه در آمریکای جنوبی و غرب آفریقا.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1803165180000L, // 2027-02-20 23:13 UTC
            magnitude = 0.9266,
            saros = 143,
            gamma = -1.048,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa, Middle East, Asia",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا، خاورمیانه و آسیا",
            descriptionEn = "A deep penumbral eclipse visible across Europe, Africa, Iran, and the Middle East.",
            descriptionFa = "خسوف نیم‌سایه‌ای قابل رویت در سراسر ایران، خاورمیانه، اروپا و آفریقا.",
            isSolar = false,
            penumbralStartMs = 1803157950000L, // 21:12 UTC
            umbralStartMs = 1803165180000L, // 23:13 UTC
            totalityStartMs = 1803165180000L, // 23:13 UTC
            totalityEndMs = 1803165180000L, // 23:13 UTC
            umbralEndMs = 1803165180000L, // 23:13 UTC
            penumbralEndMs = 1803172410000L // 01:13 UTC (+1d)
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1815926573000L, // 2027-07-18 16:02 UTC
            magnitude = 0.0014,
            saros = 110,
            gamma = -1.5758,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Southeast Asia, Australia, Pacific",
            maxTotalityRegionFa = "جنوب شرق آسیا، استرالیا و اقیانوس آرام",
            descriptionEn = "A penumbral lunar eclipse. 0.1% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۰.۱ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 1815926219000L, // 15:56 UTC
            umbralStartMs = 1815926573000L, // 16:02 UTC
            totalityStartMs = 1815926573000L, // 16:02 UTC
            totalityEndMs = 1815926573000L, // 16:02 UTC
            umbralEndMs = 1815926573000L, // 16:02 UTC
            penumbralEndMs = 1815926927000L // 16:08 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 1817201220000L, // 2027-08-02 10:07 UTC
            magnitude = 1.079,
            saros = 136,
            gamma = 0.1421,
            nameEn = "Great Eclipse of the Century (Total Solar Eclipse)",
            nameFa = "کسوف قرن (خورشیدگرفتگی کامل خاورمیانه و شمال آفریقا)",
            durationTotalSeconds = 383,
            maxTotalityRegionEn = "Gibraltar, S Spain, Morocco, Algeria, Tunisia, Libya, Egypt (Luxor 6m22s!), Saudi Arabia (Jeddah, Mecca), Yemen, Somalia; Partial across ALL of Europe, Middle East, Iran & Africa",
            maxTotalityRegionFa = "جبل‌طارق، جنوب اسپانیا، مراکش، الجزایر، تونس، لیبی، مصر (اقصر ۶ دقیقه و ۲۲ ثانیه!)، عربستان (جده و مکه)، یمن، سومالی؛ جزئی در سراسر ایران، خاورمیانه، اروپا و آفریقا",
            descriptionEn = "The greatest solar eclipse of the 21st century on land with over 6 minutes and 22 seconds of totality over Luxor Egypt, Mecca, and North Africa! Deep partial eclipse visible across Iran and the Middle East.",
            descriptionFa = "باشکوه‌ترین و طولانی‌ترین خورشیدگرفتگی قرن بیست و یکم با گرفت کامل بیش از ۶ دقیقه و ۲۲ ثانیه در اقصر مصر و مکه! فاز جزئی بسیار عمیق در سراسر ایران و خاورمیانه قابل مشاهده است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1818486840000L, // 2027-08-17 07:14 UTC
            magnitude = 0.5456,
            saros = 148,
            gamma = 1.2797,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Pacific Ocean, Americas, Eastern Asia, Australia",
            maxTotalityRegionFa = "اقیانوس آرام، قاره آمریکا، شرق آسیا و استرالیا",
            descriptionEn = "A minor penumbral eclipse visible across the Pacific rim and Americas.",
            descriptionFa = "خسوف نیم‌سایه‌ای در حاشیه اقیانوس آرام و قاره آمریکا.",
            isSolar = false,
            penumbralStartMs = 1818480282000L, // 05:24 UTC
            umbralStartMs = 1818486840000L, // 07:14 UTC
            totalityStartMs = 1818486840000L, // 07:14 UTC
            totalityEndMs = 1818486840000L, // 07:14 UTC
            umbralEndMs = 1818486840000L, // 07:14 UTC
            penumbralEndMs = 1818493398000L // 09:03 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 1831263180000L, // 2028-01-12 04:13 UTC
            magnitude = 0.0662,
            saros = 115,
            gamma = 0.9817,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa, Middle East",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا و غرب خاورمیانه",
            descriptionEn = "A shallow partial lunar eclipse with 6.6% umbral grazing at the Moon's northern limb.",
            descriptionFa = "خسوف جزئی کم‌عمق با ورود ۶.۶٪ از لبه ماه به سایه تاریک زمین.",
            isSolar = false,
            penumbralStartMs = 1831255659000L, // 02:07 UTC
            umbralStartMs = 1831261500000L, // 03:45 UTC
            totalityStartMs = 1831263180000L, // 04:13 UTC
            totalityEndMs = 1831263180000L, // 04:13 UTC
            umbralEndMs = 1831264860000L, // 04:41 UTC
            penumbralEndMs = 1831270701000L // 06:18 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1832512080000L, // 2028-01-26 15:08 UTC
            magnitude = 0.9208,
            saros = 141,
            gamma = 0.3901,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 627,
            maxTotalityRegionEn = "Ecuador, Colombia, Brazil, Suriname, Portugal, Spain",
            maxTotalityRegionFa = "اکوادور، کلمبیا، برزیل، پرتغال و اسپانیا",
            descriptionEn = "An extraordinary 10-minute annular eclipse extending from South America to the Iberian Peninsula.",
            descriptionFa = "کسوف حلقوی کم‌نظیر ۱۰ دقیقه‌ای از آمریکای جنوبی تا شبه‌جزیره ایبری.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 1846520400000L, // 2028-07-06 18:20 UTC
            magnitude = 0.3892,
            saros = 120,
            gamma = -0.7903,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia, Middle East, Iran",
            maxTotalityRegionFa = "اروپا، آفریقا، آسیا، استرالیا، خاورمیانه و ایران",
            descriptionEn = "A partial lunar eclipse covering 39% of the Moon's diameter, well visible across Iran and the Middle East.",
            descriptionFa = "خسوف جزئی با پوشش ۳۹٪ از قطر ماه که در سراسر ایران و خاورمیانه به خوبی قابل رصد است.",
            isSolar = false,
            penumbralStartMs = 1846511082000L, // 15:44 UTC
            umbralStartMs = 1846516155000L, // 17:09 UTC
            totalityStartMs = 1846520400000L, // 18:20 UTC
            totalityEndMs = 1846520400000L, // 18:20 UTC
            umbralEndMs = 1846524645000L, // 19:30 UTC
            penumbralEndMs = 1846529718000L // 20:55 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 1847847360000L, // 2028-07-22 02:56 UTC
            magnitude = 1.056,
            saros = 146,
            gamma = -0.6056,
            nameEn = "Total Solar Eclipse (Great Australian Eclipse)",
            nameFa = "خورشیدگرفتگی کامل استرالیا و سیدنی",
            durationTotalSeconds = 310,
            maxTotalityRegionEn = "Australia (directly over Sydney!), New Zealand",
            maxTotalityRegionFa = "استرالیا (مستقیماً از فراز شهر سیدنی!) و نیوزیلند",
            descriptionEn = "A spectacular total solar eclipse passing directly over Sydney Harbour with over 5 minutes of totality in NW Australia.",
            descriptionFa = "کسوف کامل بی‌نظیر که مستقیماً از بالای شهر سیدنی عبور می‌کند و بیش از ۵ دقیقه گرفت کامل دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1861894320000L, // 2028-12-31 16:52 UTC
            magnitude = 1.2463,
            saros = 125,
            gamma = 0.3258,
            nameEn = "New Year's Eve Total Lunar Eclipse (Blood Moon)",
            nameFa = "خسوف کامل شب سال نو (ماه خونین)",
            durationTotalSeconds = 4278,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia, Middle East, Iran",
            maxTotalityRegionFa = "اروپا، آفریقا، سراسر آسیا، استرالیا، خاورمیانه و ایران",
            descriptionEn = "A breathtaking total lunar eclipse on New Year's Eve 2028 with 72 minutes of totality, completely visible from Iran and the Middle East.",
            descriptionFa = "خسوف کامل رویایی در شب سال نو ۲۰۲۸ با ۷۲ دقیقه گرفتگی کامل و رصد بی‌نقص در سراسر ایران و خاورمیانه.",
            isSolar = false,
            penumbralStartMs = 1861884234000L, // 14:03 UTC
            umbralStartMs = 1861888056000L, // 15:07 UTC
            totalityStartMs = 1861892181000L, // 16:16 UTC
            totalityEndMs = 1861896459000L, // 17:27 UTC
            umbralEndMs = 1861900584000L, // 18:36 UTC
            penumbralEndMs = 1861904406000L // 19:40 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1863105180000L, // 2029-01-14 17:13 UTC
            magnitude = 0.8714,
            saros = 151,
            gamma = 1.0553,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "North America, Central America",
            maxTotalityRegionFa = "آمریکای شمالی و آمریکای مرکزی",
            descriptionEn = "A deep partial solar eclipse visible across North and Central America.",
            descriptionFa = "کسوف جزئی عمیق قابل رویت در آمریکای شمالی و مرکزی.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1875931560000L, // 2029-06-12 04:06 UTC
            magnitude = 0.4576,
            saros = 118,
            gamma = 1.2943,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Arctic, Scandinavia, Alaska, Northern Russia, Northern Canada",
            maxTotalityRegionFa = "شمالگان، اسکاندیناوی، آلاسکا، شمال روسیه و شمال کانادا",
            descriptionEn = "A high-latitude partial solar eclipse across the Arctic and Northern Europe.",
            descriptionFa = "کسوف جزئی در عرض‌های جغرافیایی شمالی، اسکاندیناوی و شمالگان.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1877138520000L, // 2029-06-26 03:22 UTC
            magnitude = 1.8436,
            saros = 130,
            gamma = 0.0124,
            nameEn = "Central Total Lunar Eclipse (Deep Blood Moon)",
            nameFa = "خسوف کامل مرکزی (ماه خونین بسیار عمیق)",
            durationTotalSeconds = 6114,
            maxTotalityRegionEn = "Americas, Europe, Africa, Western Middle East",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا و غرب خاورمیانه",
            descriptionEn = "One of the longest and deepest total lunar eclipses of the century with 102 minutes of totality as the Moon passes dead-center through Earth's shadow.",
            descriptionFa = "یکی از طولانی‌ترین و عمیق‌ترین خسوف‌های کامل قرن با ۱۰۲ دقیقه گرفت کامل و عبور ماه از مرکز سایه زمین.",
            isSolar = false,
            penumbralStartMs = 1877128467000L, // 00:34 UTC
            umbralStartMs = 1877131935000L, // 01:32 UTC
            totalityStartMs = 1877135463000L, // 02:31 UTC
            totalityEndMs = 1877141577000L, // 04:12 UTC
            umbralEndMs = 1877145105000L, // 05:11 UTC
            penumbralEndMs = 1877148573000L // 06:09 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1878478620000L, // 2029-07-11 15:37 UTC
            magnitude = 0.2303,
            saros = 156,
            gamma = -1.4191,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Southern Chile, Southern Argentina (Patagonia), Antarctica",
            maxTotalityRegionFa = "جنوب شیلی، جنوب آرژانتین و جنوبگان",
            descriptionEn = "A partial solar eclipse visible across southern Patagonia and the Antarctic Peninsula.",
            descriptionFa = "کسوف جزئی قابل مشاهده در جنوب شیلی و آرژانتین.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1891177380000L, // 2029-12-05 15:03 UTC
            magnitude = 0.8911,
            saros = 123,
            gamma = -1.0609,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Antarctica, Southern Argentina, Southern Chile",
            maxTotalityRegionFa = "جنوبگان، جنوب آرژانتین و جنوب شیلی",
            descriptionEn = "A deep partial solar eclipse visible across Antarctica and southernmost South America.",
            descriptionFa = "کسوف جزئی عمیق در قاره قطب جنوب و جنوب آمریکای جنوبی.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1892500920000L, // 2029-12-20 22:42 UTC
            magnitude = 1.1174,
            saros = 135,
            gamma = -0.3811,
            nameEn = "Winter Solstice Total Lunar Eclipse (Blood Moon)",
            nameFa = "خسوف کامل شب یلدا (ماه خونین)",
            durationTotalSeconds = 3222,
            maxTotalityRegionEn = "Europe, Africa, Asia, Middle East, Iran, Americas",
            maxTotalityRegionFa = "سراسر ایران، خاورمیانه، اروپا، آفریقا، آسیا و آمریکا",
            descriptionEn = "A remarkable Yalda / Winter Solstice total lunar eclipse with 54 minutes of totality high in the midnight sky across Iran, the Middle East, and Europe.",
            descriptionFa = "خسوف کامل تماشایی در شب یلدا با ۵۴ دقیقه گرفت کامل در اوج آسمان نیمه‌شب ایران، خاورمیانه و اروپا.",
            isSolar = false,
            penumbralStartMs = 1892490180000L, // 19:43 UTC
            umbralStartMs = 1892494521000L, // 20:55 UTC
            totalityStartMs = 1892499309000L, // 22:15 UTC
            totalityEndMs = 1892502531000L, // 23:08 UTC
            umbralEndMs = 1892507319000L, // 00:28 UTC
            penumbralEndMs = 1892511660000L // 01:41 UTC (+1d)
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1906525680000L, // 2030-06-01 06:28 UTC
            magnitude = 0.9443,
            saros = 128,
            gamma = 0.5626,
            nameEn = "Eurasian Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی اوراسیا و خاورمیانه",
            durationTotalSeconds = 321,
            maxTotalityRegionEn = "Algeria, Tunisia, Libya, Greece, Turkey, Russia, Kazakhstan, China, Japan; Deep partial across Iran & Europe",
            maxTotalityRegionFa = "الجزایر، تونس، لیبی، یونان، ترکیه، روسیه، قزاقستان، چین و ژاپن؛ جزئی عمیق در سراسر ایران و اروپا",
            descriptionEn = "A major annular solar eclipse crossing the Mediterranean, Turkey, and Eurasia, producing a deep morning partial eclipse across Iran.",
            descriptionFa = "کسوف حلقوی بزرگ در مدیترانه، ترکیه و اوراسیا که در سراسر ایران به صورت خورشیدگرفتگی جزئی عمیق صبحگاهی دیده می‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 1907778780000L, // 2030-06-15 18:33 UTC
            magnitude = 0.5025,
            saros = 140,
            gamma = 0.7534,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia, Middle East, Iran",
            maxTotalityRegionFa = "ایران، خاورمیانه، اروپا، آفریقا، آسیا و استرالیا",
            descriptionEn = "A partial lunar eclipse covering 50% of the Moon's diameter, completely visible throughout Iran and the Middle East.",
            descriptionFa = "خسوف جزئی با پوشش ۵۰٪ از قطر ماه، کاملاً قابل رصد در سراسر ایران و خاورمیانه.",
            isSolar = false,
            penumbralStartMs = 1907770434000L, // 16:13 UTC
            umbralStartMs = 1907774448000L, // 17:20 UTC
            totalityStartMs = 1907778780000L, // 18:33 UTC
            totalityEndMs = 1907778780000L, // 18:33 UTC
            umbralEndMs = 1907783112000L, // 19:45 UTC
            penumbralEndMs = 1907787126000L // 20:52 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 1921819860000L, // 2030-11-25 06:51 UTC
            magnitude = 1.0468,
            saros = 133,
            gamma = -0.3867,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل آفریقا و استرالیا",
            durationTotalSeconds = 224,
            maxTotalityRegionEn = "Namibia, Botswana, South Africa, Indian Ocean, Australia",
            maxTotalityRegionFa = "نامیبیا، بوتسوانا، آفریقای جنوبی، اقیانوس هند و استرالیا",
            descriptionEn = "A total solar eclipse spanning Southern Africa, the Indian Ocean, and Australia.",
            descriptionFa = "کسوف کامل از جنوب آفریقا و اقیانوس هند تا استرالیا.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1923085680000L, // 2030-12-09 22:28 UTC
            magnitude = 0.9416,
            saros = 145,
            gamma = -1.0731,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa, Middle East, Iran, Asia",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا، ایران، خاورمیانه و آسیا",
            descriptionEn = "A deep penumbral lunar eclipse visible across Iran, Europe, Africa, and the Americas.",
            descriptionFa = "خسوف نیم‌سایه‌ای عمیق قابل رویت در سراسر ایران، اروپا، آفریقا و قاره آمریکا.",
            isSolar = false,
            penumbralStartMs = 1923077304000L, // 20:08 UTC
            umbralStartMs = 1923085680000L, // 22:28 UTC
            totalityStartMs = 1923085680000L, // 22:28 UTC
            totalityEndMs = 1923085680000L, // 22:28 UTC
            umbralEndMs = 1923085680000L, // 22:28 UTC
            penumbralEndMs = 1923094056000L // 00:47 UTC (+1d)
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1935892244000L, // 2031-05-07 03:50 UTC
            magnitude = 0.8814,
            saros = 112,
            gamma = -1.0694,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa",
            maxTotalityRegionFa = "قاره آمریکا، اروپا و آفریقا",
            descriptionEn = "A penumbral lunar eclipse. 88.1% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۸۸.۱ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 1935885125000L, // 01:52 UTC
            umbralStartMs = 1935892244000L, // 03:50 UTC
            totalityStartMs = 1935892244000L, // 03:50 UTC
            totalityEndMs = 1935892244000L, // 03:50 UTC
            umbralEndMs = 1935892244000L, // 03:50 UTC
            penumbralEndMs = 1935899363000L // 05:49 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1937114086000L, // 2031-05-21 07:14 UTC
            magnitude = 0.9589,
            saros = 138,
            gamma = -0.197,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 326,
            maxTotalityRegionEn = "Africa, s Asia, E. Indies, Australia (Annular: Angola, Congo, Zambia, Tanzania, s India, Malaysia, Indonesia)",
            maxTotalityRegionFa = "آفریقا، جنوب آسیا، هند شرقی و استرالیا",
            descriptionEn = "An annular solar eclipse of magnitude 0.959. The ring of fire lasts up to 5m 26s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۵۹. حلقه آتش در طول مسیر مرکزی تا ۵ دقیقه و ۲۶ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1938426239000L, // 2031-06-05 11:43 UTC
            magnitude = 0.1292,
            saros = 150,
            gamma = 1.4731,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "East Indies, Australia, Pacific",
            maxTotalityRegionFa = "هند شرقی، استرالیا و اقیانوس آرام",
            descriptionEn = "A penumbral lunar eclipse. 12.9% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۱۲.۹ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 1938423371000L, // 10:56 UTC
            umbralStartMs = 1938426239000L, // 11:43 UTC
            totalityStartMs = 1938426239000L, // 11:43 UTC
            totalityEndMs = 1938426239000L, // 11:43 UTC
            umbralEndMs = 1938426239000L, // 11:43 UTC
            penumbralEndMs = 1938429107000L // 12:31 UTC
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 1951112726000L, // 2031-10-30 07:45 UTC
            magnitude = 0.7161,
            saros = 117,
            gamma = 1.1773,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas",
            maxTotalityRegionFa = "قاره آمریکا",
            descriptionEn = "A penumbral lunar eclipse. 71.6% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۷۱.۶ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 1951105772000L, // 05:49 UTC
            umbralStartMs = 1951112726000L, // 07:45 UTC
            totalityStartMs = 1951112726000L, // 07:45 UTC
            totalityEndMs = 1951112726000L, // 07:45 UTC
            umbralEndMs = 1951112726000L, // 07:45 UTC
            penumbralEndMs = 1951119680000L // 09:41 UTC
        ),
        EclipseEvent(
            type = EclipseType.HYBRID_SOLAR,
            maximumMs = 1952456772000L, // 2031-11-14 21:06 UTC
            magnitude = 1.0106,
            saros = 143,
            gamma = 0.3078,
            nameEn = "Hybrid Solar Eclipse",
            nameFa = "خورشیدگرفتگی مرکب",
            durationTotalSeconds = 68,
            maxTotalityRegionEn = "Pacific, s US, C. America, nw S. America (Hybrid: Pacific, Panama)",
            maxTotalityRegionFa = "اقیانوس آرام، جنوب آمریکا، آمریکای مرکزی و شمال غرب آمریکای جنوبی",
            descriptionEn = "A hybrid (annular-total) solar eclipse of magnitude 1.011, appearing annular along part of its path and total along another.",
            descriptionFa = "خورشیدگرفتگی مرکب (حلقوی-کامل) با قدر ۱.۰۱۱ که در بخشی از مسیر حلقوی و در بخش دیگر کامل دیده می‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1966518812000L, // 2032-04-25 15:13 UTC
            magnitude = 1.1913,
            saros = 122,
            gamma = -0.3558,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 3930,
            maxTotalityRegionEn = "e Africa, Asia, Australia, Pacific",
            maxTotalityRegionFa = "شرق آفریقا، آسیا، استرالیا و اقیانوس آرام",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.191. The Moon stays completely inside Earth's umbra for 65m 30s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۱۹۱. قرص ماه به مدت ۶۵ دقیقه و ۳۰ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 1966508540000L, // 12:22 UTC
            umbralStartMs = 1966512476000L, // 13:27 UTC
            totalityStartMs = 1966516847000L, // 14:40 UTC
            totalityEndMs = 1966520777000L, // 15:46 UTC
            umbralEndMs = 1966525148000L, // 16:59 UTC
            penumbralEndMs = 1966529084000L // 18:04 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 1967721923000L, // 2032-05-09 13:25 UTC
            magnitude = 0.9957,
            saros = 148,
            gamma = -0.9375,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 22,
            maxTotalityRegionEn = "s S. America, s Africa (Annular: s Atlantic)",
            maxTotalityRegionFa = "جنوب آمریکای جنوبی و جنوب آفریقا",
            descriptionEn = "An annular solar eclipse of magnitude 0.996. The ring of fire lasts up to 0m 22s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۹۶. حلقه آتش در طول مسیر مرکزی تا ۰ دقیقه و ۲۲ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1981738941000L, // 2032-10-18 19:02 UTC
            magnitude = 1.1028,
            saros = 127,
            gamma = 0.4169,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 2826,
            maxTotalityRegionEn = "Africa, Europe, Asia, Australia",
            maxTotalityRegionFa = "آفریقا، اروپا، آسیا و استرالیا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.103. The Moon stays completely inside Earth's umbra for 47m 06s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۱۰۳. قرص ماه به مدت ۴۷ دقیقه و ۰۶ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 1981729479000L, // 16:24 UTC
            umbralStartMs = 1981733064000L, // 17:24 UTC
            totalityStartMs = 1981737528000L, // 18:38 UTC
            totalityEndMs = 1981740354000L, // 19:25 UTC
            umbralEndMs = 1981744818000L, // 20:40 UTC
            penumbralEndMs = 1981748403000L // 21:40 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 1983072774000L, // 2032-11-03 05:32 UTC
            magnitude = 0.8554,
            saros = 153,
            gamma = 1.0643,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Asia",
            maxTotalityRegionFa = "آسیا",
            descriptionEn = "A partial solar eclipse of magnitude 0.855; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۸۵۵؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 1995818476000L, // 2033-03-30 18:01 UTC
            magnitude = 1.0462,
            saros = 120,
            gamma = 0.9778,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل",
            durationTotalSeconds = 157,
            maxTotalityRegionEn = "N. America (Total: e Russia, Alaska)",
            maxTotalityRegionFa = "آمریکای شمالی (فاز کامل: شرق روسیه و آلاسکا)",
            descriptionEn = "A total solar eclipse of magnitude 1.046. Totality lasts up to 2m 37s along the central path.",
            descriptionFa = "خورشیدگرفتگی کامل با قدر ۱.۰۴۶. مدت فاز کامل در طول مسیر مرکزی تا ۲ دقیقه و ۳۷ ثانیه است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 1997118751000L, // 2033-04-14 19:12 UTC
            magnitude = 1.0944,
            saros = 132,
            gamma = 0.3954,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 2952,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia",
            maxTotalityRegionFa = "اروپا، آفریقا، آسیا و استرالیا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.094. The Moon stays completely inside Earth's umbra for 49m 12s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۰۹۴. قرص ماه به مدت ۴۹ دقیقه و ۱۲ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 1997107915000L, // 16:11 UTC
            umbralStartMs = 1997112301000L, // 17:25 UTC
            totalityStartMs = 1997117275000L, // 18:47 UTC
            totalityEndMs = 1997120227000L, // 19:37 UTC
            umbralEndMs = 1997125201000L, // 21:00 UTC
            penumbralEndMs = 1997129587000L // 22:13 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2011096391000L, // 2033-09-23 13:53 UTC
            magnitude = 0.689,
            saros = 125,
            gamma = -1.1583,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "s S. America, Antarctica",
            maxTotalityRegionFa = "جنوب آمریکای جنوبی و جنوبگان",
            descriptionEn = "A partial solar eclipse of magnitude 0.689; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۶۸۹؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 2012381703000L, // 2033-10-08 10:55 UTC
            magnitude = 1.3497,
            saros = 137,
            gamma = -0.2889,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 4728,
            maxTotalityRegionEn = "Asia, Australia, Pacific, Americas",
            maxTotalityRegionFa = "آسیا، استرالیا، اقیانوس آرام و قاره آمریکا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.350. The Moon stays completely inside Earth's umbra for 78m 48s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۳۵۰. قرص ماه به مدت ۷۸ دقیقه و ۴۸ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2012372325000L, // 08:18 UTC
            umbralStartMs = 2012375631000L, // 09:13 UTC
            totalityStartMs = 2012379339000L, // 10:15 UTC
            totalityEndMs = 2012384067000L, // 11:34 UTC
            umbralEndMs = 2012387775000L, // 12:36 UTC
            penumbralEndMs = 2012391081000L // 13:31 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 2026462645000L, // 2034-03-20 10:17 UTC
            magnitude = 1.0458,
            saros = 130,
            gamma = 0.2894,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل",
            durationTotalSeconds = 249,
            maxTotalityRegionEn = "Africa, Europe, w Asia (Total: Nigeria, Cameroon, Chad, Sudan, Egypt, Saudi Arabia, Iran, Afghanistan, Pakistan, India, China)",
            maxTotalityRegionFa = "آفریقا، اروپا و غرب آسیا (فاز کامل: نیجریه، کامرون، چاد، سودان، مصر، عربستان، ایران، افغانستان، پاکستان، هند و چین)",
            descriptionEn = "A total solar eclipse of magnitude 1.046. Totality lasts up to 4m 09s along the central path.",
            descriptionFa = "خورشیدگرفتگی کامل با قدر ۱.۰۴۶. مدت فاز کامل در طول مسیر مرکزی تا ۴ دقیقه و ۰۹ ثانیه است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 2027703939000L, // 2034-04-03 19:05 UTC
            magnitude = 0.8545,
            saros = 142,
            gamma = 1.1144,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia",
            maxTotalityRegionFa = "اروپا، آفریقا، آسیا و استرالیا",
            descriptionEn = "A penumbral lunar eclipse. 85.5% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۸۵.۵ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 2027695977000L, // 16:52 UTC
            umbralStartMs = 2027703939000L, // 19:05 UTC
            totalityStartMs = 2027703939000L, // 19:05 UTC
            totalityEndMs = 2027703939000L, // 19:05 UTC
            umbralEndMs = 2027703939000L, // 19:05 UTC
            penumbralEndMs = 2027711901000L // 21:18 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 2041690687000L, // 2034-09-12 16:18 UTC
            magnitude = 0.9736,
            saros = 135,
            gamma = -0.3936,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 178,
            maxTotalityRegionEn = "C. & S. America (Annular: Chile, Bolivia, Argentina, Paraguay, Brazil)",
            maxTotalityRegionFa = "آمریکای مرکزی و جنوبی (فاز حلقوی: شیلی، بولیوی، آرژانتین، پاراگوئه و برزیل)",
            descriptionEn = "An annular solar eclipse of magnitude 0.974. The ring of fire lasts up to 2m 58s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۷۴. حلقه آتش در طول مسیر مرکزی تا ۲ دقیقه و ۵۸ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 2043024376000L, // 2034-09-28 02:46 UTC
            magnitude = 0.0144,
            saros = 147,
            gamma = -1.011,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa",
            maxTotalityRegionFa = "قاره آمریکا، اروپا و آفریقا",
            descriptionEn = "A partial lunar eclipse of umbral magnitude 0.014, so 1.4% of the Moon's diameter is inside Earth's umbra at greatest eclipse.",
            descriptionFa = "خسوف جزئی با قدر سایه‌ای ۰.۰۱۴؛ در اوج گرفتگی ۱.۴ درصد از قطر ماه درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2043016915000L, // 00:41 UTC
            umbralStartMs = 2043023575000L, // 02:32 UTC
            totalityStartMs = 2043024376000L, // 02:46 UTC
            totalityEndMs = 2043024376000L, // 02:46 UTC
            umbralEndMs = 2043025177000L, // 02:59 UTC
            penumbralEndMs = 2043031837000L // 04:50 UTC
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 2055747891000L, // 2035-02-22 09:04 UTC
            magnitude = 0.9652,
            saros = 114,
            gamma = -1.0367,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "e Asia, Pacific, Americas",
            maxTotalityRegionFa = "شرق آسیا، اقیانوس آرام و قاره آمریکا",
            descriptionEn = "A penumbral lunar eclipse. 96.5% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۹۶.۵ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 2055740220000L, // 06:57 UTC
            umbralStartMs = 2055747891000L, // 09:04 UTC
            totalityStartMs = 2055747891000L, // 09:04 UTC
            totalityEndMs = 2055747891000L, // 09:04 UTC
            umbralEndMs = 2055747891000L, // 09:04 UTC
            penumbralEndMs = 2055755562000L // 11:12 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 2057094273000L, // 2035-03-09 23:04 UTC
            magnitude = 0.9919,
            saros = 140,
            gamma = -0.4368,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 48,
            maxTotalityRegionEn = "Australia, New Zealand, s Pacific, Mexico, Antarctica (Annular: New Zealand, Pacific)",
            maxTotalityRegionFa = "استرالیا، نیوزیلند، جنوب اقیانوس آرام، مکزیک و جنوبگان",
            descriptionEn = "An annular solar eclipse of magnitude 0.992. The ring of fire lasts up to 0m 48s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۹۲. حلقه آتش در طول مسیر مرکزی تا ۰ دقیقه و ۴۸ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 2071098654000L, // 2035-08-19 01:10 UTC
            magnitude = 0.1037,
            saros = 119,
            gamma = 0.9433,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa, Mid East",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا و خاورمیانه",
            descriptionEn = "A partial lunar eclipse of umbral magnitude 0.104, so 10.4% of the Moon's diameter is inside Earth's umbra at greatest eclipse.",
            descriptionFa = "خسوف جزئی با قدر سایه‌ای ۰.۱۰۴؛ در اوج گرفتگی ۱۰.۴ درصد از قطر ماه درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2071089960000L, // 22:46 UTC
            umbralStartMs = 2071096359000L, // 00:32 UTC
            totalityStartMs = 2071098654000L, // 01:10 UTC
            totalityEndMs = 2071098654000L, // 01:10 UTC
            umbralEndMs = 2071100949000L, // 01:49 UTC
            penumbralEndMs = 2071107348000L // 03:35 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 2072310925000L, // 2035-09-02 01:55 UTC
            magnitude = 1.032,
            saros = 145,
            gamma = 0.3727,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل",
            durationTotalSeconds = 174,
            maxTotalityRegionEn = "e Asia, Pacific (Total: China, Korea, Japan, Pacific)",
            maxTotalityRegionFa = "شرق آسیا و اقیانوس آرام (فاز کامل: چین، کره، ژاپن و اقیانوس آرام)",
            descriptionEn = "A total solar eclipse of magnitude 1.032. Totality lasts up to 2m 54s along the central path.",
            descriptionFa = "خورشیدگرفتگی کامل با قدر ۱.۰۳۲. مدت فاز کامل در طول مسیر مرکزی تا ۲ دقیقه و ۵۴ ثانیه است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 2086380704000L, // 2036-02-11 22:11 UTC
            magnitude = 1.2995,
            saros = 124,
            gamma = -0.311,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 4470,
            maxTotalityRegionEn = "Americas, Europe, Africa, Asia, w Australia",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا، آسیا و غرب استرالیا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.300. The Moon stays completely inside Earth's umbra for 74m 30s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۳۰۰. قرص ماه به مدت ۷۴ دقیقه و ۳۰ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2086371221000L, // 19:33 UTC
            umbralStartMs = 2086374647000L, // 20:30 UTC
            totalityStartMs = 2086378469000L, // 21:34 UTC
            totalityEndMs = 2086382939000L, // 22:48 UTC
            umbralEndMs = 2086386761000L, // 23:52 UTC
            penumbralEndMs = 2086390187000L // 00:49 UTC (+1d)
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2087700327000L, // 2036-02-27 04:45 UTC
            magnitude = 0.6286,
            saros = 150,
            gamma = -1.1942,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Antarctica, s Australia, New Zealand",
            maxTotalityRegionFa = "جنوبگان، جنوب استرالیا و نیوزیلند",
            descriptionEn = "A partial solar eclipse of magnitude 0.629; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۶۲۹؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2100421844000L, // 2036-07-23 10:30 UTC
            magnitude = 0.1991,
            saros = 117,
            gamma = -1.425,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "s Atlantic",
            maxTotalityRegionFa = "جنوب اقیانوس اطلس",
            descriptionEn = "A partial solar eclipse of magnitude 0.199; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۱۹۹؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 2101690270000L, // 2036-08-07 02:51 UTC
            magnitude = 1.4544,
            saros = 129,
            gamma = 0.2004,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 5718,
            maxTotalityRegionEn = "Americas, Europe, Africa, w Asia",
            maxTotalityRegionFa = "قاره آمریکا، اروپا، آفریقا و غرب آسیا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.454. The Moon stays completely inside Earth's umbra for 95m 18s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۴۵۴. قرص ماه به مدت ۹۵ دقیقه و ۱۸ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2101679107000L, // 23:45 UTC
            umbralStartMs = 2101683331000L, // 00:55 UTC
            totalityStartMs = 2101687411000L, // 02:03 UTC
            totalityEndMs = 2101693129000L, // 03:38 UTC
            umbralEndMs = 2101697209000L, // 04:46 UTC
            penumbralEndMs = 2101701433000L // 05:57 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2102952263000L, // 2036-08-21 17:24 UTC
            magnitude = 0.8622,
            saros = 155,
            gamma = 1.0825,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Alaska, Canada, Arctic, w Europe, nw Africa",
            maxTotalityRegionFa = "آلاسکا، کانادا، شمالگان، غرب اروپا و شمال غرب آفریقا",
            descriptionEn = "A partial solar eclipse of magnitude 0.862; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۸۶۲؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2115712053000L, // 2037-01-16 09:47 UTC
            magnitude = 0.7049,
            saros = 122,
            gamma = 1.1477,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "n Africa, Europe, Mid East, w Asia",
            maxTotalityRegionFa = "شمال آفریقا، اروپا، خاورمیانه و غرب آسیا",
            descriptionEn = "A partial solar eclipse of magnitude 0.705; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۷۰۵؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 2117023216000L, // 2037-01-31 14:00 UTC
            magnitude = 1.2074,
            saros = 134,
            gamma = 0.3619,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 3822,
            maxTotalityRegionEn = "e Europe, e Africa, Asia, Australia, Pacific, N. America",
            maxTotalityRegionFa = "شرق اروپا، شرق آفریقا، آسیا، استرالیا، اقیانوس آرام و آمریکای شمالی",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.207. The Moon stays completely inside Earth's umbra for 63m 42s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۲۰۷. قرص ماه به مدت ۶۳ دقیقه و ۴۲ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2117013853000L, // 11:24 UTC
            umbralStartMs = 2117017291000L, // 12:21 UTC
            totalityStartMs = 2117021305000L, // 13:28 UTC
            totalityEndMs = 2117025127000L, // 14:32 UTC
            umbralEndMs = 2117029141000L, // 15:39 UTC
            penumbralEndMs = 2117032579000L // 16:36 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 2131065553000L, // 2037-07-13 02:39 UTC
            magnitude = 1.0413,
            saros = 127,
            gamma = -0.7246,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل",
            durationTotalSeconds = 238,
            maxTotalityRegionEn = "E. Indies, Australia, Pacific (Total: Australia, New Zealand)",
            maxTotalityRegionFa = "هند شرقی، استرالیا و اقیانوس آرام (فاز کامل: استرالیا و نیوزیلند)",
            descriptionEn = "A total solar eclipse of magnitude 1.041. Totality lasts up to 3m 58s along the central path.",
            descriptionFa = "خورشیدگرفتگی کامل با قدر ۱.۰۴۱. مدت فاز کامل در طول مسیر مرکزی تا ۳ دقیقه و ۵۸ ثانیه است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 2132280510000L, // 2037-07-27 04:08 UTC
            magnitude = 0.8095,
            saros = 139,
            gamma = -0.5582,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa",
            maxTotalityRegionFa = "قاره آمریکا، اروپا و آفریقا",
            descriptionEn = "A partial lunar eclipse of umbral magnitude 0.809, so 81.0% of the Moon's diameter is inside Earth's umbra at greatest eclipse.",
            descriptionFa = "خسوف جزئی با قدر سایه‌ای ۰.۸۰۹؛ در اوج گرفتگی ۸۱.۰ درصد از قطر ماه درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2132270286000L, // 01:18 UTC
            umbralStartMs = 2132274738000L, // 02:32 UTC
            totalityStartMs = 2132280510000L, // 04:08 UTC
            totalityEndMs = 2132280510000L, // 04:08 UTC
            umbralEndMs = 2132286282000L, // 05:44 UTC
            penumbralEndMs = 2132290734000L // 06:58 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 2146311948000L, // 2038-01-05 13:45 UTC
            magnitude = 0.9728,
            saros = 132,
            gamma = 0.4169,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 198,
            maxTotalityRegionEn = "e N. America, n S. America, Atlantic, Africa, Europe (Annular: Cuba, Dom.Rep., Ghana, Niger, Chad, Egypt)",
            maxTotalityRegionFa = "شرق آمریکای شمالی، شمال آمریکای جنوبی، اقیانوس اطلس، آفریقا و اروپا",
            descriptionEn = "An annular solar eclipse of magnitude 0.973. The ring of fire lasts up to 3m 18s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۷۳. حلقه آتش در طول مسیر مرکزی تا ۳ دقیقه و ۱۸ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 2147658509000L, // 2038-01-21 03:48 UTC
            magnitude = 0.8996,
            saros = 144,
            gamma = 1.071,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Americas, Europe, Africa",
            maxTotalityRegionFa = "قاره آمریکا، اروپا و آفریقا",
            descriptionEn = "A penumbral lunar eclipse. 90.0% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۹۰.۰ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 2147651135000L, // 01:45 UTC
            umbralStartMs = 2147658509000L, // 03:48 UTC
            totalityStartMs = 2147658509000L, // 03:48 UTC
            totalityEndMs = 2147658509000L, // 03:48 UTC
            umbralEndMs = 2147658509000L, // 03:48 UTC
            penumbralEndMs = 2147665883000L // 05:51 UTC
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 2160355419000L, // 2038-06-17 02:43 UTC
            magnitude = 0.4422,
            saros = 111,
            gamma = 1.3082,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "e N. America, C. & S. America, Africa, w Europe",
            maxTotalityRegionFa = "شرق آمریکای شمالی، آمریکای مرکزی و جنوبی، آفریقا و غرب اروپا",
            descriptionEn = "A penumbral lunar eclipse. 44.2% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۴۴.۲ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 2160350130000L, // 01:15 UTC
            umbralStartMs = 2160355419000L, // 02:43 UTC
            totalityStartMs = 2160355419000L, // 02:43 UTC
            totalityEndMs = 2160355419000L, // 02:43 UTC
            umbralEndMs = 2160355419000L, // 02:43 UTC
            penumbralEndMs = 2160360708000L // 04:11 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 2161690291000L, // 2038-07-02 13:31 UTC
            magnitude = 0.9911,
            saros = 137,
            gamma = 0.0398,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 60,
            maxTotalityRegionEn = "N. & C. America, S. America, Africa, Europe, Mid East (Annular: Colombia, Venezuela, Morocco, Mali, Niger, Chad, Sudan, Ethiopia, Kenya)",
            maxTotalityRegionFa = "آمریکای شمالی و مرکزی، آمریکای جنوبی، آفریقا، اروپا و خاورمیانه",
            descriptionEn = "An annular solar eclipse of magnitude 0.991. The ring of fire lasts up to 1m 00s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۹۱. حلقه آتش در طول مسیر مرکزی تا ۱ دقیقه و ۰۰ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 2162892872000L, // 2038-07-16 11:34 UTC
            magnitude = 0.4999,
            saros = 149,
            gamma = -1.2837,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Australia, e Asia, Pacific, w Americas",
            maxTotalityRegionFa = "استرالیا، شرق آسیا، اقیانوس آرام و غرب قاره آمریکا",
            descriptionEn = "A penumbral lunar eclipse. 50.0% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۵۰.۰ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 2162887100000L, // 09:58 UTC
            umbralStartMs = 2162892872000L, // 11:34 UTC
            totalityStartMs = 2162892872000L, // 11:34 UTC
            totalityEndMs = 2162892872000L, // 11:34 UTC
            umbralEndMs = 2162892872000L, // 11:34 UTC
            penumbralEndMs = 2162898644000L // 13:10 UTC
        ),
        EclipseEvent(
            type = EclipseType.PENUMBRAL_LUNAR,
            maximumMs = 2175702216000L, // 2038-12-11 17:43 UTC
            magnitude = 0.8046,
            saros = 116,
            gamma = -1.1448,
            nameEn = "Penumbral Lunar Eclipse",
            nameFa = "خسوف نیم‌سایه‌ای",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia",
            maxTotalityRegionFa = "اروپا، آفریقا، آسیا و استرالیا",
            descriptionEn = "A penumbral lunar eclipse. 80.5% of the Moon's diameter passes through Earth's penumbral shadow; no umbral darkening occurs.",
            descriptionFa = "خسوف نیم‌سایه‌ای که طی آن ۸۰.۵ درصد از قطر ماه از نیم‌سایه زمین می‌گذرد؛ هیچ تاریکیِ سایه‌ای رخ نمی‌دهد.",
            isSolar = false,
            penumbralStartMs = 2175694461000L, // 15:34 UTC
            umbralStartMs = 2175702216000L, // 17:43 UTC
            totalityStartMs = 2175702216000L, // 17:43 UTC
            totalityEndMs = 2175702216000L, // 17:43 UTC
            umbralEndMs = 2175702216000L, // 17:43 UTC
            penumbralEndMs = 2175709971000L // 19:52 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 2176937926000L, // 2038-12-26 00:58 UTC
            magnitude = 1.0268,
            saros = 142,
            gamma = -0.2881,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل",
            durationTotalSeconds = 138,
            maxTotalityRegionEn = "se Asia, E. Indies, Australia, New Zealand, s Pacific, Antarctica (Total: Australia, New Zealand, s Pacific)",
            maxTotalityRegionFa = "جنوب شرق آسیا، هند شرقی، استرالیا، نیوزیلند، جنوب اقیانوس آرام و جنوبگان",
            descriptionEn = "A total solar eclipse of magnitude 1.027. Totality lasts up to 2m 18s along the central path.",
            descriptionFa = "خورشیدگرفتگی کامل با قدر ۱.۰۲۷. مدت فاز کامل در طول مسیر مرکزی تا ۲ دقیقه و ۱۸ ثانیه است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 2190999181000L, // 2039-06-06 18:53 UTC
            magnitude = 0.8846,
            saros = 121,
            gamma = 0.546,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia",
            maxTotalityRegionFa = "اروپا، آفریقا، آسیا و استرالیا",
            descriptionEn = "A partial lunar eclipse of umbral magnitude 0.885, so 88.5% of the Moon's diameter is inside Earth's umbra at greatest eclipse.",
            descriptionFa = "خسوف جزئی با قدر سایه‌ای ۰.۸۸۵؛ در اوج گرفتگی ۸۸.۵ درصد از قطر ماه درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2190990280000L, // 16:24 UTC
            umbralStartMs = 2190993802000L, // 17:23 UTC
            totalityStartMs = 2190999181000L, // 18:53 UTC
            totalityEndMs = 2190999181000L, // 18:53 UTC
            umbralEndMs = 2191004560000L, // 20:22 UTC
            penumbralEndMs = 2191008082000L // 21:21 UTC
        ),
        EclipseEvent(
            type = EclipseType.ANNULAR_SOLAR,
            maximumMs = 2192289090000L, // 2039-06-21 17:11 UTC
            magnitude = 0.9454,
            saros = 147,
            gamma = 0.8312,
            nameEn = "Annular Solar Eclipse",
            nameFa = "خورشیدگرفتگی حلقوی",
            durationTotalSeconds = 245,
            maxTotalityRegionEn = "N. America, w Europe (Annular: Alaska, n Canada, Norway, Sweden, Finland, Estonia, Russia)",
            maxTotalityRegionFa = "آمریکای شمالی و غرب اروپا (فاز حلقوی: آلاسکا، شمال کانادا، نروژ، سوئد، فنلاند، استونی و روسیه)",
            descriptionEn = "An annular solar eclipse of magnitude 0.945. The ring of fire lasts up to 4m 05s along the central path.",
            descriptionFa = "خورشیدگرفتگی حلقوی با قدر ۰.۹۴۵. حلقه آتش در طول مسیر مرکزی تا ۴ دقیقه و ۰۵ ثانیه دوام دارد.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_LUNAR,
            maximumMs = 2206284903000L, // 2039-11-30 16:55 UTC
            magnitude = 0.9426,
            saros = 126,
            gamma = -0.4721,
            nameEn = "Partial Lunar Eclipse",
            nameFa = "خسوف جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Europe, Africa, Asia, Australia, Pacific",
            maxTotalityRegionFa = "اروپا، آفریقا، آسیا، استرالیا و اقیانوس آرام",
            descriptionEn = "A partial lunar eclipse of umbral magnitude 0.943, so 94.3% of the Moon's diameter is inside Earth's umbra at greatest eclipse.",
            descriptionFa = "خسوف جزئی با قدر سایه‌ای ۰.۹۴۳؛ در اوج گرفتگی ۹۴.۳ درصد از قطر ماه درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2206274100000L, // 13:55 UTC
            umbralStartMs = 2206278723000L, // 15:12 UTC
            totalityStartMs = 2206284903000L, // 16:55 UTC
            totalityEndMs = 2206284903000L, // 16:55 UTC
            umbralEndMs = 2206291083000L, // 18:38 UTC
            penumbralEndMs = 2206295706000L // 19:55 UTC
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_SOLAR,
            maximumMs = 2207578941000L, // 2039-12-15 16:22 UTC
            magnitude = 1.0356,
            saros = 152,
            gamma = -0.9458,
            nameEn = "Total Solar Eclipse",
            nameFa = "خورشیدگرفتگی کامل",
            durationTotalSeconds = 111,
            maxTotalityRegionEn = "s S. America, Antarctica (Total: Antarctica)",
            maxTotalityRegionFa = "جنوب آمریکای جنوبی و جنوبگان",
            descriptionEn = "A total solar eclipse of magnitude 1.036. Totality lasts up to 1m 51s along the central path.",
            descriptionFa = "خورشیدگرفتگی کامل با قدر ۱.۰۳۶. مدت فاز کامل در طول مسیر مرکزی تا ۱ دقیقه و ۵۱ ثانیه است.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2220320497000L, // 2040-05-11 03:41 UTC
            magnitude = 0.5306,
            saros = 119,
            gamma = -1.2529,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "Australia, N.Z., Antarctica",
            maxTotalityRegionFa = "استرالیا، نیوزیلند و جنوبگان",
            descriptionEn = "A partial solar eclipse of magnitude 0.531; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۵۳۱؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 2221645497000L, // 2040-05-26 11:44 UTC
            magnitude = 1.5348,
            saros = 131,
            gamma = -0.1872,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 5532,
            maxTotalityRegionEn = "e Asia, Australia, Pacific, w Americas",
            maxTotalityRegionFa = "شرق آسیا، استرالیا، اقیانوس آرام و غرب قاره آمریکا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.535. The Moon stays completely inside Earth's umbra for 92m 12s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۵۳۵. قرص ماه به مدت ۹۲ دقیقه و ۱۲ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2221635855000L, // 09:04 UTC
            umbralStartMs = 2221639176000L, // 09:59 UTC
            totalityStartMs = 2221642731000L, // 10:58 UTC
            totalityEndMs = 2221648263000L, // 12:31 UTC
            umbralEndMs = 2221651818000L, // 13:30 UTC
            penumbralEndMs = 2221655139000L // 14:25 UTC
        ),
        EclipseEvent(
            type = EclipseType.PARTIAL_SOLAR,
            maximumMs = 2235668857000L, // 2040-11-04 19:07 UTC
            magnitude = 0.8074,
            saros = 124,
            gamma = 1.0993,
            nameEn = "Partial Solar Eclipse",
            nameFa = "خورشیدگرفتگی جزئی",
            durationTotalSeconds = 0,
            maxTotalityRegionEn = "N. & C. America",
            maxTotalityRegionFa = "آمریکای شمالی و مرکزی",
            descriptionEn = "A partial solar eclipse of magnitude 0.807; no location on Earth sees totality.",
            descriptionFa = "خورشیدگرفتگی جزئی با قدر ۰.۸۰۷؛ در هیچ نقطه‌ای از زمین فاز کامل دیده نمی‌شود.",
            isSolar = true
        ),
        EclipseEvent(
            type = EclipseType.TOTAL_LUNAR,
            maximumMs = 2236878195000L, // 2040-11-18 19:03 UTC
            magnitude = 1.3974,
            saros = 136,
            gamma = 0.2361,
            nameEn = "Total Lunar Eclipse",
            nameFa = "خسوف کامل",
            durationTotalSeconds = 5268,
            maxTotalityRegionEn = "e Americas, Europe, Africa, Asia, Australia",
            maxTotalityRegionFa = "شرق قاره آمریکا، اروپا، آفریقا، آسیا و استرالیا",
            descriptionEn = "A total lunar eclipse of umbral magnitude 1.397. The Moon stays completely inside Earth's umbra for 87m 48s.",
            descriptionFa = "خسوف کامل با قدر سایه‌ای ۱.۳۹۷. قرص ماه به مدت ۸۷ دقیقه و ۴۸ ثانیه کاملاً درون سایه زمین قرار می‌گیرد.",
            isSolar = false,
            penumbralStartMs = 2236867587000L, // 16:06 UTC
            umbralStartMs = 2236871583000L, // 17:13 UTC
            totalityStartMs = 2236875561000L, // 18:19 UTC
            totalityEndMs = 2236880829000L, // 19:47 UTC
            umbralEndMs = 2236884807000L, // 20:53 UTC
            penumbralEndMs = 2236888803000L // 22:00 UTC
        )
    )

    private val canonicalCoverageStartMs: Long = 1704067200000L // 2024-01-01 00:00 UTC

    /**
     * Converts JDE (Julian Ephemeris Day in Terrestrial Time TT) to UTC epoch milliseconds.
     */
    private fun jdeToUtcMs(jde: Double): Long {
        val deltaTSec = AstroTime.fromJd(jde).deltaT
        val jdUtc = jde - deltaTSec / 86400.0
        return ((jdUtc - 2440587.5) * 86400000.0).roundToLong()
    }

    /**
     * Calculates time of New Moon for lunation k using Meeus Chapter 49.
     */
    fun newMoonTime(k: Long): Long {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        var jde = 2451550.09766 + 29.530588861 * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4

        val E = 1.0 - 0.002516 * t - 0.0000074 * t2
        val M = (2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3) * DEG2RAD
        val Mp = (201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4) * DEG2RAD
        val F = (160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4) * DEG2RAD
        val omega = (124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3) * DEG2RAD

        jde += -0.40720 * sin(Mp) +
                0.17241 * E * sin(M) +
                0.01608 * sin(2.0 * Mp) +
                0.01039 * sin(2.0 * F) +
                0.00739 * E * sin(Mp - M) -
                0.00514 * E * sin(Mp + M) +
                0.00208 * E * E * sin(2.0 * M) -
                0.00111 * sin(Mp - 2.0 * F) -
                0.00057 * sin(Mp + 2.0 * F) +
                0.00056 * E * sin(2.0 * Mp + M) -
                0.00042 * sin(3.0 * Mp) +
                0.00042 * E * sin(M + 2.0 * F) +
                0.00038 * E * sin(M - 2.0 * F) -
                0.00024 * E * sin(2.0 * Mp - M) -
                0.00017 * sin(omega)

        return jdeToUtcMs(jde)
    }

    /**
     * Calculates time of Full Moon for lunation k using Meeus Chapter 49.
     */
    fun fullMoonTime(k: Long): Long {
        val kFull = k + 0.5
        val t = kFull / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        var jde = 2451550.09766 + 29.530588861 * kFull + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4

        val E = 1.0 - 0.002516 * t - 0.0000074 * t2
        val M = (2.5534 + 29.10535670 * kFull - 0.0000014 * t2 - 0.00000011 * t3) * DEG2RAD
        val Mp = (201.5643 + 385.81693528 * kFull + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4) * DEG2RAD
        val F = (160.7108 + 390.67050284 * kFull - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4) * DEG2RAD
        val omega = (124.7746 - 1.56375588 * kFull + 0.0020672 * t2 + 0.00000215 * t3) * DEG2RAD

        jde += -0.40614 * sin(Mp) +
                0.17302 * E * sin(M) +
                0.01614 * sin(2.0 * Mp) +
                0.01043 * sin(2.0 * F) +
                0.00734 * E * sin(Mp - M) -
                0.00515 * E * sin(Mp + M) +
                0.00209 * E * E * sin(2.0 * M) -
                0.00111 * sin(Mp - 2.0 * F) -
                0.00057 * sin(Mp + 2.0 * F) +
                0.00056 * E * sin(2.0 * Mp + M) -
                0.00042 * sin(3.0 * Mp) +
                0.00042 * E * sin(M + 2.0 * F) +
                0.00038 * E * sin(M - 2.0 * F) -
                0.00024 * E * sin(2.0 * Mp - M) -
                0.00017 * sin(omega)

        return jdeToUtcMs(jde)
    }

    /**
     * Calculates time of First (0.25) or Last (0.75) Quarter Moon for lunation k.
     */
    fun quarterMoonTime(k: Long, phaseOffset: Double): Long {
        val kPhase = k + phaseOffset
        val t = kPhase / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t

        var jde = 2451550.09766 + 29.530588861 * kPhase + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4

        val E = 1.0 - 0.002516 * t - 0.0000074 * t2
        val M = (2.5534 + 29.10535670 * kPhase - 0.0000014 * t2 - 0.00000011 * t3) * DEG2RAD
        val Mp = (201.5643 + 385.81693528 * kPhase + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4) * DEG2RAD
        val F = (160.7108 + 390.67050284 * kPhase - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4) * DEG2RAD

        val isFirstQuarter = (phaseOffset < 0.5)
        val sign = if (isFirstQuarter) 1.0 else -1.0

        jde += -0.62801 * sin(Mp) +
                0.17172 * E * sin(M) -
                0.01183 * E * sin(Mp + M) +
                0.00862 * sin(2.0 * Mp) +
                0.00804 * sin(2.0 * F) +
                0.00454 * E * sin(Mp - M) +
                0.00204 * E * E * sin(2.0 * M) -
                0.00180 * sin(Mp - 2.0 * F) -
                0.00070 * sin(Mp + 2.0 * F) -
                0.00040 * sin(3.0 * Mp) +
                sign * (0.00306 - 0.00038 * E * cos(M) + 0.00026 * cos(Mp))

        return jdeToUtcMs(jde)
    }

    fun findNextNewMoon(afterMs: Long): Long {
        val baseJd = afterMs / 86400000.0 + 2440587.5
        var k = floor((baseJd - 2451550.09766) / SYNODIC_MONTH_DAYS).toLong() - 1L
        while (true) {
            val t = newMoonTime(k)
            if (t > afterMs) return t
            k++
        }
    }

    fun findNextFullMoon(afterMs: Long): Long {
        val baseJd = afterMs / 86400000.0 + 2440587.5
        var k = floor((baseJd - 2451550.09766) / SYNODIC_MONTH_DAYS).toLong() - 1L
        while (true) {
            val t = fullMoonTime(k)
            if (t > afterMs) return t
            k++
        }
    }

    fun findNextSolarEclipse(afterMs: Long): EclipseEvent? {
        if (afterMs >= canonicalCoverageStartMs) {
            val canonical = canonicalEclipses.firstOrNull { it.isSolar && it.maximumMs > afterMs }
            if (canonical != null) return canonical
        }
        val dynamic = searchDynamicEclipse(afterMs, isSolar = true)
        if (dynamic != null && afterMs < canonicalCoverageStartMs) {
            val firstCanonical = canonicalEclipses.firstOrNull { it.isSolar && it.maximumMs > afterMs }
            if (firstCanonical != null && firstCanonical.maximumMs <= dynamic.maximumMs + 5 * 86400000L) {
                return firstCanonical
            }
        }
        return dynamic
    }

    fun findNextLunarEclipse(afterMs: Long): EclipseEvent? {
        if (afterMs >= canonicalCoverageStartMs) {
            val canonical = canonicalEclipses.firstOrNull { !it.isSolar && it.maximumMs > afterMs }
            if (canonical != null) return canonical
        }
        val dynamic = searchDynamicEclipse(afterMs, isSolar = false)
        if (dynamic != null && afterMs < canonicalCoverageStartMs) {
            val firstCanonical = canonicalEclipses.firstOrNull { !it.isSolar && it.maximumMs > afterMs }
            if (firstCanonical != null && firstCanonical.maximumMs <= dynamic.maximumMs + 5 * 86400000L) {
                return firstCanonical
            }
        }
        return dynamic
    }

    fun findEclipses(startMs: Long, endMs: Long): List<EclipseEvent> {
        if (endMs < startMs) return emptyList()
        val result = mutableListOf<EclipseEvent>()
        var curMs = startMs - 1L
        while (curMs < endMs) {
            val s = findNextSolarEclipse(curMs)
            val l = findNextLunarEclipse(curMs)
            val next = listOfNotNull(s, l)
                .filter { it.maximumMs in startMs..endMs }
                .minByOrNull { it.maximumMs }
                ?: break
            result.add(next)
            curMs = next.maximumMs + 5 * 86400000L
        }
        return result
    }

    /**
     * Evaluates whether an eclipse is visible from the user's specific location and
     * computes the exact local circumstances: start, max obscuration %, altitude, azimuth, end time.
     */
    fun evaluateEclipse(
        event: EclipseEvent,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double = 0.0,
        nowMs: Long = System.currentTimeMillis(),
        timezoneId: String = "Asia/Tehran",
        calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI
    ): EclipseResult {
        return if (event.isSolar) {
            evaluateSolarEclipseLocal(
                event = event,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                nowMs = nowMs,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem
            )
        } else {
            evaluateLunarEclipseLocal(
                event = event,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                nowMs = nowMs,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem
            )
        }
    }

    private data class SolarStepEval(
        val sunAltDeg: Double,
        val sunAzDeg: Double,
        val sunRadiusDeg: Double,
        val moonRadiusDeg: Double,
        val sepDeg: Double
    )

    private fun evalSolarAt(
        timeMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double
    ): SolarStepEval {
        val jd = timeMs / 86400000.0 + 2440587.5
        val astroTime = AstroTime.fromJd(jd)
        val lastDeg = TimeEngine.getLAST(jd, userLonDeg)

        val sunGeo = lunarSolar.calculateSun(astroTime)
        val moonGeo = lunarSolar.calculateMoon(astroTime)

        val sunDistKm = sunGeo.distanceAu * AU_KM
        val sunTopoEq = CoordinateEngine.geocentricToTopocentric(
            geocentric = CoordinateEngine.Equatorial(sunGeo.raDeg, sunGeo.decDeg),
            geocentricDistanceKm = sunDistKm,
            lastDeg = lastDeg,
            latitudeDeg = userLatDeg,
            elevationM = elevationM
        )
        val moonTopoEq = CoordinateEngine.geocentricToTopocentric(
            geocentric = CoordinateEngine.Equatorial(moonGeo.raDeg, moonGeo.decDeg),
            geocentricDistanceKm = moonGeo.distanceKm,
            lastDeg = lastDeg,
            latitudeDeg = userLatDeg,
            elevationM = elevationM
        )

        val sunHoriz = CoordinateEngine.equatorialToHorizontal(
            equatorial = sunTopoEq,
            lastDeg = lastDeg,
            latitudeDeg = userLatDeg,
            observerElevationM = elevationM
        )

        // Topocentric lunar distance (augmentation of the Moon's apparent semi-diameter)
        val latRad = userLatDeg * DEG2RAD
        val u = atan((1.0 - EARTH_FLATTENING) * tan(latRad))
        val rhoSinPhiP = (1.0 - EARTH_FLATTENING) * sin(u) + (elevationM / 6378137.0) * sin(latRad)
        val rhoCosPhiP = cos(u) + (elevationM / 6378137.0) * cos(latRad)
        val sinPi = EARTH_EQUATORIAL_RADIUS_KM / moonGeo.distanceKm
        val hRad = (lastDeg - moonGeo.raDeg) * DEG2RAD
        val decRad = moonGeo.decDeg * DEG2RAD
        val dx = cos(decRad) * cos(hRad) - rhoCosPhiP * sinPi
        val dy = cos(decRad) * sin(hRad)
        val dz = sin(decRad) - rhoSinPhiP * sinPi
        val moonTopoDistKm = moonGeo.distanceKm * sqrt(dx * dx + dy * dy + dz * dz)

        val sunRadiusDeg = asin((SUN_RADIUS_KM / sunDistKm).coerceIn(-1.0, 1.0)) * RAD2DEG
        val moonRadiusDeg = asin((MOON_RADIUS_KM / moonTopoDistKm).coerceIn(-1.0, 1.0)) * RAD2DEG

        val sepDeg = calculateAngularSeparation(
            sunTopoEq.raDeg, sunTopoEq.decDeg,
            moonTopoEq.raDeg, moonTopoEq.decDeg
        )

        return SolarStepEval(
            sunAltDeg = sunHoriz.altitudeDeg,
            sunAzDeg = sunHoriz.azimuthDeg,
            sunRadiusDeg = sunRadiusDeg,
            moonRadiusDeg = moonRadiusDeg,
            sepDeg = sepDeg
        )
    }

    /**
     * High-precision local circumstances for Solar Eclipse at user location.
     */
    private fun evaluateSolarEclipseLocal(
        event: EclipseEvent,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double,
        nowMs: Long,
        timezoneId: String,
        calendarSystem: CalendarSystem
    ): EclipseResult {
        val maxMs = event.maximumMs
        val stepMs = 60 * 1000L // 1-minute coarse step
        val scanRadiusMs = (3.5 * 3600 * 1000).toLong()

        var firstContactMs = 0L
        var lastContactMs = 0L
        var peakTimeMs = maxMs
        var maxObscuration = 0.0
        var maxMagnitude = 0.0
        var minVisibleSepDeg = Double.MAX_VALUE
        var peakSunRadiusDeg = 0.266
        var peakMoonRadiusDeg = 0.272
        var peakSunAlt = 0.0
        var peakSunAz = 0.0

        var curTime = maxMs - scanRadiusMs
        while (curTime <= maxMs + scanRadiusMs) {
            val step = evalSolarAt(curTime, userLatDeg, userLonDeg, elevationM)
            if (step.sepDeg < step.sunRadiusDeg + step.moonRadiusDeg && step.sunAltDeg > -0.5) {
                val obscuration = calculateDiskOverlapFraction(
                    step.sunRadiusDeg,
                    step.moonRadiusDeg,
                    step.sepDeg
                )
                val mag = computeLocalSolarMagnitude(step.sunRadiusDeg, step.moonRadiusDeg, step.sepDeg)

                if (firstContactMs == 0L) firstContactMs = curTime
                lastContactMs = curTime

                if (obscuration > maxObscuration || (obscuration == maxObscuration && step.sepDeg < minVisibleSepDeg)) {
                    maxObscuration = obscuration
                    maxMagnitude = mag
                    minVisibleSepDeg = step.sepDeg
                    peakTimeMs = curTime
                    peakSunRadiusDeg = step.sunRadiusDeg
                    peakMoonRadiusDeg = step.moonRadiusDeg
                    peakSunAlt = step.sunAltDeg
                    peakSunAz = step.sunAzDeg
                }
            }
            curTime += stepMs
        }

        // Refine peak with 10-second sub-steps around coarse peakTimeMs
        if (firstContactMs != 0L) {
            var refineTime = peakTimeMs - stepMs
            val refineEnd = peakTimeMs + stepMs
            while (refineTime <= refineEnd) {
                val step = evalSolarAt(refineTime, userLatDeg, userLonDeg, elevationM)
                if (step.sepDeg < step.sunRadiusDeg + step.moonRadiusDeg && step.sunAltDeg > -0.5) {
                    val obscuration = calculateDiskOverlapFraction(
                        step.sunRadiusDeg,
                        step.moonRadiusDeg,
                        step.sepDeg
                    )
                    val mag = computeLocalSolarMagnitude(step.sunRadiusDeg, step.moonRadiusDeg, step.sepDeg)
                    if (step.sepDeg < minVisibleSepDeg) {
                        minVisibleSepDeg = step.sepDeg
                        maxObscuration = obscuration
                        maxMagnitude = mag
                        peakTimeMs = refineTime
                        peakSunRadiusDeg = step.sunRadiusDeg
                        peakMoonRadiusDeg = step.moonRadiusDeg
                        peakSunAlt = step.sunAltDeg
                        peakSunAz = step.sunAzDeg
                    }
                }
                refineTime += 10_000L
            }
        }

        val isVisible = maxObscuration >= 0.001 && firstContactMs != 0L
        val isLocalTotal = isVisible &&
                peakMoonRadiusDeg >= peakSunRadiusDeg &&
                minVisibleSepDeg <= (peakMoonRadiusDeg - peakSunRadiusDeg) + 0.002
        val isLocalAnnular = isVisible &&
                !isLocalTotal &&
                peakSunRadiusDeg > peakMoonRadiusDeg &&
                minVisibleSepDeg <= (peakSunRadiusDeg - peakMoonRadiusDeg) + 0.002

        val obscurationPercent = when {
            !isVisible -> 0
            isLocalTotal -> 100
            else -> (maxObscuration * 100.0).roundToInt().coerceIn(1, 99)
        }

        val maxEval = evalSolarAt(maxMs, userLatDeg, userLonDeg, elevationM)
        if (!isVisible) {
            peakSunAlt = maxEval.sunAltDeg
            peakSunAz = maxEval.sunAzDeg
        }

        val localTypeStrEn: String
        val localTypeStrFa: String
        if (isVisible) {
            when {
                isLocalTotal -> {
                    localTypeStrEn = "Total Solar Eclipse"
                    localTypeStrFa = "خورشیدگرفتگی کامل"
                }
                isLocalAnnular -> {
                    localTypeStrEn = "Annular Solar Eclipse (Ring of Fire)"
                    localTypeStrFa = "خورشیدگرفتگی حلقوی (حلقه آتش)"
                }
                else -> {
                    localTypeStrEn = "Partial Solar Eclipse ($obscurationPercent% coverage)"
                    localTypeStrFa = "خورشیدگرفتگی جزئی (${obscurationPercent.toString().toPersianDigits()}٪ پوشش)"
                }
            }
        } else {
            localTypeStrEn = "Not Visible Locally (${event.nameEn})"
            localTypeStrFa = "عدم رویت در موقعیت شما (${event.nameFa})"
        }

        val timeZone = TimeEngine.resolveTimeZone(timezoneId)
        val (formattedEn, formattedFa) = formatDates(
            ms = if (isVisible) peakTimeMs else event.maximumMs,
            timeZone = timeZone,
            calendarSystem = calendarSystem
        )

        val visibilityTextEn: String
        val visibilityTextFa: String
        if (isVisible) {
            val altStr = "${peakSunAlt.roundToInt()}°"
            visibilityTextEn = "Visible as $localTypeStrEn (Sun Altitude: $altStr at peak, $obscurationPercent% coverage)."
            visibilityTextFa = "قابل رویت به صورت $localTypeStrFa (ارتفاع خورشید در اوج: ${altStr.toPersianDigits()}، پوشش ${"$obscurationPercent%".toPersianDigits()})."
        } else {
            if (maxEval.sunAltDeg <= -0.5) {
                visibilityTextEn = "Not visible directly: Occurs during nighttime at your location (Sun is below the horizon)."
                visibilityTextFa = "عدم رویت مستقیم: این گرفتگی در طول شب رخ می‌دهد (خورشید در موقعیت شما زیر افق قرار دارد)."
            } else {
                visibilityTextEn = "Not visible directly: Your location is outside the Moon's shadow path (0% coverage)."
                visibilityTextFa = "عدم رویت مستقیم: موقعیت شما خارج از مسیر سایه و نیم‌سایه ماه قرار دارد (پوشش ۰٪)."
            }
        }

        val refPeakMs = if (isVisible) peakTimeMs else event.maximumMs
        val daysRemaining = max(0, ((refPeakMs - nowMs) / 86400000.0).roundToInt())

        return EclipseResult(
            event = event,
            localNameEn = localTypeStrEn,
            localNameFa = localTypeStrFa,
            isLocallyVisible = isVisible,
            localObscurationPercent = obscurationPercent,
            localMagnitude = if (isVisible) maxMagnitude else 0.0,
            localStartTimeMs = if (isVisible) firstContactMs else maxMs,
            localPeakTimeMs = if (isVisible) peakTimeMs else maxMs,
            localEndTimeMs = if (isVisible) lastContactMs else maxMs,
            targetAltitudeDeg = peakSunAlt,
            targetAzimuthDeg = peakSunAz,
            formattedDateEn = formattedEn,
            formattedDateFa = formattedFa,
            localVisibilityTextEn = visibilityTextEn,
            localVisibilityTextFa = visibilityTextFa,
            daysRemaining = daysRemaining,
            visibility = EclipseVisibility.assessSolar(
                event = event,
                isLocallyVisible = isVisible,
                localStartTimeMs = if (isVisible) firstContactMs else maxMs,
                localEndTimeMs = if (isVisible) lastContactMs else maxMs,
                localObscurationPercent = obscurationPercent,
                targetAltitudeDeg = peakSunAlt
            ),
            insight = EclipseVisibility.insight(event, catalogueContext)
        )
    }

    private fun computeLocalSolarMagnitude(
        sunRadiusDeg: Double,
        moonRadiusDeg: Double,
        sepDeg: Double
    ): Double {
        if (sepDeg >= sunRadiusDeg + moonRadiusDeg) return 0.0
        if (sepDeg <= abs(sunRadiusDeg - moonRadiusDeg)) {
            return moonRadiusDeg / sunRadiusDeg
        }
        return (sunRadiusDeg + moonRadiusDeg - sepDeg) / (2.0 * sunRadiusDeg)
    }

    /**
     * Magnitude of a lunar eclipse at [timeMs], linearly interpolated between the
     * catalogue contact times.
     *
     * The magnitude is 0 at first contact, peaks at greatest eclipse and returns to 0
     * at last contact. For a total eclipse it additionally passes through exactly 1.0
     * at the beginning (U2) and end (U3) of totality, because those are the instants
     * when the Moon becomes and then ceases to be fully immersed in the umbra.
     * For a penumbral eclipse the same triangular profile is applied to the penumbral
     * contacts P1 / greatest / P4 and to the penumbral magnitude.
     *
     * Returns 0.0 outside the eclipse, and also whenever the contact times are
     * degenerate (unknown), so callers never see a fabricated value.
     */
    private fun lunarMagnitudeAt(event: EclipseEvent, timeMs: Long): Double {
        val isPenumbral = event.type == EclipseType.PENUMBRAL_LUNAR
        val firstMs = if (isPenumbral) event.penumbralStartMs else event.umbralStartMs
        val lastMs = if (isPenumbral) event.penumbralEndMs else event.umbralEndMs
        val peakMag = event.magnitude
        if (lastMs <= firstMs || peakMag <= 0.0) return 0.0
        if (timeMs <= firstMs || timeMs >= lastMs) return 0.0

        if (!isPenumbral && event.type == EclipseType.TOTAL_LUNAR) {
            val u2 = event.totalityStartMs
            val u3 = event.totalityEndMs
            val maxMs = event.maximumMs
            // U1 -> U2 : 0 -> 1
            if (u2 > firstMs && timeMs <= u2) {
                return ((timeMs - firstMs).toDouble() / (u2 - firstMs).toDouble()).coerceIn(0.0, 1.0)
            }
            // U3 -> U4 : 1 -> 0
            if (u3 < lastMs && timeMs >= u3) {
                return ((lastMs - timeMs).toDouble() / (lastMs - u3).toDouble()).coerceIn(0.0, 1.0)
            }
            // U2 -> greatest : 1 -> peak
            if (maxMs > u2 && timeMs < maxMs) {
                val f = (timeMs - u2).toDouble() / (maxMs - u2).toDouble()
                return (1.0 + (peakMag - 1.0) * f).coerceIn(0.0, maxOf(1.0, peakMag))
            }
            // greatest -> U3 : peak -> 1
            if (u3 > maxMs) {
                val f = (u3 - timeMs).toDouble() / (u3 - maxMs).toDouble()
                return (1.0 + (peakMag - 1.0) * f).coerceIn(0.0, maxOf(1.0, peakMag))
            }
            return peakMag
        }

        return if (timeMs < event.maximumMs) {
            val span = event.maximumMs - firstMs
            if (span <= 0L) peakMag else (peakMag * (timeMs - firstMs).toDouble() / span.toDouble()).coerceIn(0.0, peakMag)
        } else {
            val span = lastMs - event.maximumMs
            if (span <= 0L) peakMag else (peakMag * (lastMs - timeMs).toDouble() / span.toDouble()).coerceIn(0.0, peakMag)
        }
    }

    /**
     * High-precision local circumstances for Lunar Eclipse at user location.
     */
    private fun evaluateLunarEclipseLocal(
        event: EclipseEvent,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double,
        nowMs: Long,
        timezoneId: String,
        calendarSystem: CalendarSystem
    ): EclipseResult {
        val maxMs = event.maximumMs
        val p1Ms = event.penumbralStartMs
        val p4Ms = event.penumbralEndMs

        // Step through [P1, P4] in 2-minute increments to find the exact local visibility window
        val stepMs = 2 * 60 * 1000L
        var localStartMs = 0L
        var localEndMs = 0L
        var curTime = p1Ms
        while (curTime <= p4Ms) {
            val horiz = getMoonHorizAt(curTime, userLatDeg, userLonDeg, elevationM)
            if (horiz.altitudeDeg > -0.5) {
                if (localStartMs == 0L) localStartMs = curTime
                localEndMs = curTime
            }
            curTime += stepMs
        }

        val maxHoriz = getMoonHorizAt(maxMs, userLatDeg, userLonDeg, elevationM)
        val p1Alt = getMoonAltAt(p1Ms, userLatDeg, userLonDeg, elevationM)
        val p4Alt = getMoonAltAt(p4Ms, userLatDeg, userLonDeg, elevationM)

        val isVisibleAtPeak = maxHoriz.altitudeDeg > -0.5
        val isVisibleAtAnyTime = isVisibleAtPeak || localStartMs != 0L || p1Alt > -0.5 || p4Alt > -0.5

        if (isVisibleAtAnyTime && localStartMs == 0L) {
            localStartMs = if (p1Alt > -0.5) p1Ms else maxMs
            localEndMs = if (p4Alt > -0.5) p4Ms else maxMs
        }

        val localPeakMs = if (isVisibleAtAnyTime) {
            maxMs.coerceIn(localStartMs, localEndMs)
        } else {
            maxMs
        }
        val localPeakHoriz = if (localPeakMs == maxMs) {
            maxHoriz
        } else {
            getMoonHorizAt(localPeakMs, userLatDeg, userLonDeg, elevationM)
        }

        // Local magnitude & umbral obscuration at localPeakMs.
        // If the Moon is above the horizon at greatest eclipse the observer sees the
        // full catalogue magnitude. If the Moon only rises (or sets) part-way through
        // the event, the magnitude is interpolated between the catalogue contacts.
        val (localMag, obscurationPercent) = if (!isVisibleAtAnyTime) {
            Pair(0.0, 0)
        } else if (isVisibleAtPeak || localPeakMs == maxMs) {
            val obs = when (event.type) {
                EclipseType.TOTAL_LUNAR -> 100
                EclipseType.PARTIAL_LUNAR -> (event.magnitude * 100.0).roundToInt().coerceIn(1, 99)
                EclipseType.PENUMBRAL_LUNAR -> 0
                else -> 0
            }
            Pair(event.magnitude, obs)
        } else {
            // Visible only before moonset or after moonrise (localPeakMs != maxMs)
            val magAtPeak = lunarMagnitudeAt(event, localPeakMs)
            val inUmbra = (event.type == EclipseType.TOTAL_LUNAR || event.type == EclipseType.PARTIAL_LUNAR) &&
                    localPeakMs in event.umbralStartMs..event.umbralEndMs &&
                    event.umbralEndMs > event.umbralStartMs
            val obs = if (inUmbra) {
                if (magAtPeak >= 1.0) 100 else (magAtPeak * 100.0).roundToInt().coerceIn(1, 99)
            } else {
                0
            }
            Pair(magAtPeak, obs)
        }

        val timeZone = TimeEngine.resolveTimeZone(timezoneId)
        val (formattedEn, formattedFa) = formatDates(
            ms = if (isVisibleAtAnyTime) localPeakMs else event.maximumMs,
            timeZone = timeZone,
            calendarSystem = calendarSystem
        )

        val visibilityTextEn: String
        val visibilityTextFa: String
        if (isVisibleAtAnyTime) {
            val altStr = "${localPeakHoriz.altitudeDeg.roundToInt().coerceAtLeast(0)}°"
            if (p1Alt > -0.5 && p4Alt > -0.5 && isVisibleAtPeak) {
                visibilityTextEn = "Fully visible from start to finish! Moon is above the horizon throughout (Altitude: $altStr at peak)."
                visibilityTextFa = "کاملاً قابل رصد در تمام مراحل! ماه در تمام مدت بالای افق قرار دارد (ارتفاع در اوج: ${altStr.toPersianDigits()})."
            } else if (p1Alt <= -0.5) {
                if (isVisibleAtPeak) {
                    visibilityTextEn = "Visible after Moonrise including maximum eclipse (Moon altitude is $altStr at peak)."
                    visibilityTextFa = "قابل رصد پس از طلوع ماه به همراه اوج گرفتگی (ارتفاع ماه در زمان اوج: ${altStr.toPersianDigits()})."
                } else {
                    visibilityTextEn = "Final stages visible at Moonrise (maximum eclipse occurs before Moonrise)."
                    visibilityTextFa = "مراحل پایانی گرفتگی هنگام طلوع ماه قابل رصد است (اوج گرفتگی پیش از طلوع ماه رخ می‌دهد)."
                }
            } else {
                if (isVisibleAtPeak) {
                    visibilityTextEn = "Visible until Moonset including maximum eclipse (Moon altitude is $altStr at peak)."
                    visibilityTextFa = "قابل رصد تا زمان غروب ماه به همراه اوج گرفتگی (ارتفاع ماه در زمان اوج: ${altStr.toPersianDigits()})."
                } else {
                    visibilityTextEn = "Initial stages visible before Moonset (Moon sets before maximum eclipse)."
                    visibilityTextFa = "مراحل آغازین گرفتگی پیش از غروب ماه قابل رصد است (ماه پیش از اوج گرفتگی غروب می‌کند)."
                }
            }
        } else {
            visibilityTextEn = "Not visible directly: Occurs during daytime (Moon is below the horizon at your location)."
            visibilityTextFa = "عدم رویت مستقیم: این خسوف در طول روز رخ می‌دهد (ماه در موقعیت شما زیر افق قرار دارد)."
        }

        val refPeakMs = if (isVisibleAtAnyTime) localPeakMs else event.maximumMs
        val daysRemaining = max(0, ((refPeakMs - nowMs) / 86400000.0).roundToInt())

        return EclipseResult(
            event = event,
            localNameEn = if (isVisibleAtAnyTime) event.nameEn else "Not Visible Locally (${event.nameEn})",
            localNameFa = if (isVisibleAtAnyTime) event.nameFa else "عدم رویت در موقعیت شما (${event.nameFa})",
            isLocallyVisible = isVisibleAtAnyTime,
            localObscurationPercent = obscurationPercent,
            localMagnitude = localMag,
            localStartTimeMs = if (isVisibleAtAnyTime) localStartMs else maxMs,
            localPeakTimeMs = localPeakMs,
            localEndTimeMs = if (isVisibleAtAnyTime) localEndMs else maxMs,
            targetAltitudeDeg = if (isVisibleAtAnyTime) localPeakHoriz.altitudeDeg else maxHoriz.altitudeDeg,
            targetAzimuthDeg = if (isVisibleAtAnyTime) localPeakHoriz.azimuthDeg else maxHoriz.azimuthDeg,
            formattedDateEn = formattedEn,
            formattedDateFa = formattedFa,
            localVisibilityTextEn = visibilityTextEn,
            localVisibilityTextFa = visibilityTextFa,
            daysRemaining = daysRemaining,
            visibility = EclipseVisibility.assessLunar(
                event = event,
                isLocallyVisible = isVisibleAtAnyTime,
                localStartTimeMs = if (isVisibleAtAnyTime) localStartMs else maxMs,
                localEndTimeMs = if (isVisibleAtAnyTime) localEndMs else maxMs,
                localMagnitude = localMag,
                targetAltitudeDeg = if (isVisibleAtAnyTime) localPeakHoriz.altitudeDeg else maxHoriz.altitudeDeg
            ),
            insight = EclipseVisibility.insight(event, catalogueContext)
        )
    }

    private fun getMoonHorizAt(
        timeMs: Long,
        latDeg: Double,
        lonDeg: Double,
        elevationM: Double
    ): CoordinateEngine.Horizontal {
        val jd = timeMs / 86400000.0 + 2440587.5
        val astroTime = AstroTime.fromJd(jd)
        val lastDeg = TimeEngine.getLAST(jd, lonDeg)
        val moonGeo = lunarSolar.calculateMoon(astroTime)
        val moonTopo = CoordinateEngine.geocentricToTopocentric(
            geocentric = CoordinateEngine.Equatorial(moonGeo.raDeg, moonGeo.decDeg),
            geocentricDistanceKm = moonGeo.distanceKm,
            lastDeg = lastDeg,
            latitudeDeg = latDeg,
            elevationM = elevationM
        )
        return CoordinateEngine.equatorialToHorizontal(
            equatorial = moonTopo,
            lastDeg = lastDeg,
            latitudeDeg = latDeg,
            observerElevationM = elevationM
        )
    }

    private fun getMoonAltAt(timeMs: Long, latDeg: Double, lonDeg: Double, elevationM: Double): Double {
        return getMoonHorizAt(timeMs, latDeg, lonDeg, elevationM).altitudeDeg
    }

    /**
     * Gets both next Solar and next Lunar eclipse evaluated for the user's location.
     * Keeps an actively ongoing eclipse visible until its end contact rather than dropping it at peak.
     */
    fun getNextEclipses(
        nowMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double = 0.0,
        timezoneId: String = "Asia/Tehran",
        calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI
    ): Pair<EclipseResult, EclipseResult> {
        val (solarSeries, lunarSeries) = getNextEclipseSeries(
            nowMs = nowMs,
            userLatDeg = userLatDeg,
            userLonDeg = userLonDeg,
            elevationM = elevationM,
            timezoneId = timezoneId,
            calendarSystem = calendarSystem,
            count = 1
        )
        return Pair(solarSeries.first(), lunarSeries.first())
    }

    /**
     * Returns the next [count] solar eclipses and the next [count] lunar eclipses,
     * each evaluated for the observer's location: local visibility window, local
     * magnitude, local obscuration, peak altitude/azimuth and formatted local dates.
     *
     * Both lists are chronological by the event's greatest-eclipse instant and never
     * contain duplicates.
     *
     * The first entry of each series obeys the same rule as [getNextEclipses]: an
     * eclipse that is already under way stays listed until its last contact rather
     * than vanishing the moment its peak passes.
     */
    fun getNextEclipseSeries(
        nowMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double = 0.0,
        timezoneId: String = "Asia/Tehran",
        calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI,
        count: Int = 3
    ): Pair<List<EclipseResult>, List<EclipseResult>> {
        val take = count.coerceAtLeast(1)
        return Pair(
            collectNextEclipses(
                isSolar = true,
                count = take,
                nowMs = nowMs,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem
            ),
            collectNextEclipses(
                isSolar = false,
                count = take,
                nowMs = nowMs,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem
            )
        )
    }

    /**
     * Walks the eclipse canon forward from [nowMs] collecting up to [count] events of
     * one kind (solar or lunar) that are actually **observable** from the observer's
     * location, evaluating each one against [EclipseVisibility]'s criteria.
     *
     * "Observable" is deliberately stricter than "in the sky". A partial solar
     * eclipse covering 0.4% of the Sun, or a penumbral lunar eclipse of magnitude
     * 0.0014, is above the horizon and still shows nothing a human can detect, so
     * listing it as one of the "next 3 eclipses" would be misleading. Those events
     * are skipped here. See [EclipseVisibility] for the thresholds and their
     * published sources.
     *
     * The first iteration looks back by 3.5 h so an in-progress eclipse is still
     * offered; afterwards the cursor always moves past the previous event's last
     * contact, which guarantees forward progress.
     *
     * The list is never returned empty: if nothing observable lies ahead, the
     * nearest eclipse that was evaluated is kept instead, so callers that read
     * `.first()` (notably [getNextEclipses]) still get a sane next event.
     */
    private fun collectNextEclipses(
        isSolar: Boolean,
        count: Int,
        nowMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        elevationM: Double,
        timezoneId: String,
        calendarSystem: CalendarSystem
    ): List<EclipseResult> {
        val lookbackMs = (3.5 * 3600 * 1000).toLong()
        val results = ArrayList<EclipseResult>(count)
        // Nearest rejected events, kept only so the list never ends up empty.
        val nearestUnobservable = ArrayList<EclipseResult>(count)
        var cursorMs = nowMs - lookbackMs

        // Belt-and-braces bound: the catalogue is finite, and every branch below
        // strictly advances `cursorMs` past the current event's maximum, so the
        // walk can visit each catalogue entry of this kind at most once.
        var iterations = 0
        val iterationLimit =
            canonicalEclipses.count { it.isSolar == isSolar } + 1

        while (results.size < count && iterations++ < iterationLimit) {
            val event = if (isSolar) {
                findNextSolarEclipse(cursorMs)
            } else {
                findNextLunarEclipse(cursorMs)
            }
            if (event == null) break
            if (event.maximumMs <= cursorMs) break

            val result = evaluateEclipse(
                event = event,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                elevationM = elevationM,
                nowMs = nowMs,
                timezoneId = timezoneId,
                calendarSystem = calendarSystem
            )

            val lastContactMs = if (isSolar) {
                // Solar entries carry no global contacts (see EclipseEvent); the
                // topocentric scan in evaluateSolarEclipseLocal supplies the local ones.
                if (result.isLocallyVisible) max(result.localEndTimeMs, event.maximumMs)
                else event.maximumMs + lookbackMs / 2
            } else {
                if (result.isLocallyVisible) max(result.localEndTimeMs, event.penumbralEndMs)
                else event.penumbralEndMs
            }

            if (results.isEmpty() && nowMs > lastContactMs) {
                // The lookback reached an eclipse that has already finished. Move on to
                // the following one instead of reporting a past event.
                cursorMs = max(nowMs, event.maximumMs)
                continue
            }

            if (result.visibility.isObservable) {
                results.add(result)
            } else if (nearestUnobservable.size < count) {
                // Not observable from here (below the horizon, or too shallow to
                // notice). Remember it, but keep looking for something better.
                nearestUnobservable.add(result)
            }
            cursorMs = max(event.maximumMs, lastContactMs)
        }

        if (results.isEmpty()) {
            // Nothing observable lies ahead. Prefer the nearest eclipse the walk
            // actually evaluated — it is still the next one in time — so the card
            // shows the true "next" event rather than a jump to the start of the
            // table. Only if the walk produced nothing at all (the canon is
            // exhausted) fall back to the first eclipse of this kind, which keeps
            // callers that read .first() — notably getNextEclipses — safe.
            val nextBest = nearestUnobservable.firstOrNull()
            if (nextBest != null) {
                results.add(nextBest)
            } else {
                val fallback = if (isSolar) {
                    canonicalEclipses.firstOrNull { it.isSolar }
                } else {
                    canonicalEclipses.firstOrNull { !it.isSolar }
                }
                if (fallback != null) {
                    results.add(
                        evaluateEclipse(
                            event = fallback,
                            userLatDeg = userLatDeg,
                            userLonDeg = userLonDeg,
                            elevationM = elevationM,
                            nowMs = nowMs,
                            timezoneId = timezoneId,
                            calendarSystem = calendarSystem
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Generates full detailed modal info for an evaluated EclipseResult.
     */
    fun getDetailedEclipseInfo(
        result: EclipseResult,
        userLatDeg: Double,
        userLonDeg: Double,
        timezoneId: String = "Asia/Tehran"
    ): DetailedEclipseInfo {
        val event = result.event
        val resolvedTzId = if (timezoneId.isNotBlank()) {
            timezoneId
        } else if (userLatDeg in 24.0..40.0 && userLonDeg in 44.0..64.0) {
            "Asia/Tehran"
        } else {
            TimeZone.getDefault().id
        }
        val timeZone = TimeEngine.resolveTimeZone(resolvedTzId)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.US).apply {
            this.timeZone = timeZone
        }

        val startStr = if (result.isLocallyVisible) timeFmt.format(Date(result.localStartTimeMs)) else "—"
        val peakStr = timeFmt.format(Date(result.localPeakTimeMs))
        val endStr = if (result.isLocallyVisible) timeFmt.format(Date(result.localEndTimeMs)) else "—"

        val durationMinutes = if (result.isLocallyVisible) {
            max(0L, (result.localEndTimeMs - result.localStartTimeMs) / 60000L)
        } else {
            0L
        }
        val hours = durationMinutes / 60
        val mins = durationMinutes % 60
        val durationEn = if (!result.isLocallyVisible) {
            "Not visible locally"
        } else if (hours > 0) {
            "${hours}h ${mins}m"
        } else {
            "${mins}m"
        }
        val durationFa = if (!result.isLocallyVisible) {
            "غیرقابل رویت در موقعیت شما"
        } else if (hours > 0) {
            "${hours} ساعت و ${mins} دقیقه".toPersianDigits()
        } else {
            "${mins} دقیقه".toPersianDigits()
        }

        val safetyEn = if (event.isSolar) {
            "CRITICAL EYE SAFETY: Never look directly at the Sun without certified ISO 12312-2 solar eclipse glasses! Regular sunglasses, smoked glass, or unfiltered camera lenses can cause permanent retinal damage."
        } else {
            "100% SAFE FOR THE NAKED EYE: Lunar eclipses are completely safe to observe directly without any eye protection, binoculars, or telescope filters."
        }

        val safetyFa = if (event.isSolar) {
            "هشدار حیاتی سلامت چشم: هرگز بدون عینک مخصوص خورشیدگرفتگی (استاندارد ISO 12312-2) یا فیلتر استاندارد به خورشید نگاه نکنید! عینک آفتابی معمولی یا فیلم رادیولوژی به شبکیه چشم آسیب دائمی می‌زند."
        } else {
            "کاملاً ایمن برای چشم: تماشای ماه‌گرفتگی (خسوف) با چشم غیرمسلح، دوربین دوچشمی یا تلسکوپ کاملاً بی‌خطر است و نیاز به هیچ فیلتری ندارد."
        }

        val tipEn = if (event.isSolar) {
            "Use a pinhole projector or solar filter to watch the Moon's silhouette advance across the solar disk. Notice sharp crescent shadows under trees during peak coverage."
        } else {
            "During peak umbral eclipse, Earth's atmosphere refracts sunset-red light onto the lunar surface (Rayleigh scattering). Binoculars bring out subtle copper and turquoise hues along the shadow edge."
        }

        val tipFa = if (event.isSolar) {
            "از فیلتر خورشیدی یا روش تصویرسازی روزنه‌ای (سایه برگ درختان روی زمین) برای مشاهده هلال‌های زیبای خورشید در زمان اوج گرفتگی استفاده کنید."
        } else {
            "در زمان اوج خسوف، شکست نور خورشید در جو زمین باعث سرخ‌فام شدن قرص ماه می‌شود. استفاده از دوربین دوچشمی رنگ‌های مسی و فیروزه‌ای لبه سایه زمین را به‌وضوح نشان می‌دهد."
        }

        return DetailedEclipseInfo(
            event = event,
            result = result,
            daysRemaining = result.daysRemaining,
            localStartTimeStr = startStr,
            localPeakTimeStr = peakStr,
            localEndTimeStr = endStr,
            durationTextEn = durationEn,
            durationTextFa = durationFa,
            obscurationPercent = result.localObscurationPercent,
            targetAltDeg = round(result.targetAltitudeDeg * 10.0) / 10.0,
            targetAzDeg = round(result.targetAzimuthDeg * 10.0) / 10.0,
            safetyGuideEn = safetyEn,
            safetyGuideFa = safetyFa,
            localMagnitude = result.localMagnitude,
            observationTipEn = tipEn,
            observationTipFa = tipFa
        )
    }

    private fun formatDates(
        ms: Long,
        timeZone: TimeZone = TimeEngine.TEHRAN_TIME_ZONE,
        calendarSystem: CalendarSystem = CalendarSystem.SOLAR_HIJRI
    ): Pair<String, String> {
        val enStr = TimeEngine.formatDate(
            timestampMs = ms,
            calendarSystem = calendarSystem,
            isFa = false,
            timeZone = timeZone
        )

        val faStr = TimeEngine.formatDate(
            timestampMs = ms,
            calendarSystem = calendarSystem,
            isFa = true,
            timeZone = timeZone
        )

        return Pair(enStr, faStr)
    }

    /**
     * Exact geometric area overlap fraction of Sun disk covered by Moon disk.
     */
    private fun calculateDiskOverlapFraction(rSun: Double, rMoon: Double, d: Double): Double {
        if (d >= rSun + rMoon) return 0.0
        if (d <= abs(rSun - rMoon)) {
            return if (rMoon >= rSun) 1.0 else (rMoon * rMoon) / (rSun * rSun)
        }

        val r1 = rSun
        val r2 = rMoon
        val d2 = d * d
        val r1Sq = r1 * r1
        val r2Sq = r2 * r2

        val alpha = acos(((d2 + r1Sq - r2Sq) / (2.0 * d * r1)).coerceIn(-1.0, 1.0))
        val beta = acos(((d2 + r2Sq - r1Sq) / (2.0 * d * r2)).coerceIn(-1.0, 1.0))

        val area1 = r1Sq * alpha
        val area2 = r2Sq * beta
        val areaTriangle = 0.5 * sqrt(max(0.0, (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2)))

        val intersectionArea = area1 + area2 - areaTriangle
        val sunArea = Math.PI * r1Sq

        return (intersectionArea / sunArea).coerceIn(0.0, 1.0)
    }

    private fun calculateAngularSeparation(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r1 = ra1 * DEG2RAD
        val d1 = dec1 * DEG2RAD
        val r2 = ra2 * DEG2RAD
        val d2 = dec2 * DEG2RAD
        val cosSep = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(r1 - r2)
        return acos(cosSep.coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    /**
     * Full Meeus Chapter 54 dynamic eclipse search for dates outside the canonical table.
     */
    private fun searchDynamicEclipse(afterMs: Long, isSolar: Boolean): EclipseEvent? {
        val baseJd = afterMs / 86400000.0 + 2440587.5
        var k = floor((baseJd - 2451550.09766) / SYNODIC_MONTH_DAYS).toLong() - 1L

        for (i in 0..72) {
            val kVal = if (isSolar) k.toDouble() else k + 0.5
            val T = kVal / 1236.85
            val T2 = T * T
            val T3 = T2 * T
            val T4 = T3 * T

            val Fdeg = (((160.7108 + 390.67050284 * kVal - 0.0016118 * T2 - 0.00000227 * T3 + 0.000000011 * T4) % 360.0) + 360.0) % 360.0
            if (abs(sin(Fdeg * DEG2RAD)) <= 0.36) {
                var jde = 2451550.09766 + 29.530588861 * kVal + 0.00015437 * T2 - 0.000000150 * T3 + 0.00000000073 * T4
                val E = 1.0 - 0.002516 * T - 0.0000074 * T2
                val M = (2.5534 + 29.10535670 * kVal - 0.0000014 * T2 - 0.00000011 * T3) * DEG2RAD
                val Mp = (201.5643 + 385.81693528 * kVal + 0.0107582 * t2OrZero(T2) + 0.00001238 * T3 - 0.000000058 * T4) * DEG2RAD
                val omega = (124.7746 - 1.56375588 * kVal + 0.0020672 * T2 + 0.00000215 * T3) * DEG2RAD
                val F1 = (Fdeg - 0.02665 * sin(omega)) * DEG2RAD
                val A1 = (299.77 + 0.107408 * kVal - 0.009173 * T2) * DEG2RAD

                jde += (if (isSolar) -0.4075 else -0.4065) * sin(Mp) +
                        (if (isSolar) 0.1721 else 0.1727) * E * sin(M) +
                        0.0161 * sin(2.0 * Mp) -
                        0.0097 * sin(2.0 * F1) +
                        0.0073 * E * sin(Mp - M) -
                        0.0050 * E * sin(Mp + M) -
                        0.0023 * sin(Mp - 2.0 * F1) +
                        0.0021 * E * sin(2.0 * M) +
                        0.0012 * sin(Mp + 2.0 * F1) +
                        0.0006 * E * sin(2.0 * Mp + M) -
                        0.0004 * sin(3.0 * Mp) -
                        0.0003 * E * sin(M + 2.0 * F1) +
                        0.0003 * sin(A1) -
                        0.0002 * E * sin(M - 2.0 * F1) -
                        0.0002 * E * sin(2.0 * Mp - M) -
                        0.0002 * sin(omega)

                val maxMs = jdeToUtcMs(jde)
                if (maxMs > afterMs) {
                    val P = 0.2070 * E * sin(M) +
                            0.0024 * E * sin(2.0 * M) -
                            0.0392 * sin(Mp) +
                            0.0116 * sin(2.0 * Mp) -
                            0.0073 * E * sin(Mp + M) +
                            0.0067 * E * sin(Mp - M) +
                            0.0118 * sin(2.0 * F1)
                    val Q = 5.2207 -
                            0.0048 * E * cos(M) +
                            0.0020 * E * cos(2.0 * M) -
                            0.3299 * cos(Mp) -
                            0.0060 * E * cos(Mp + M) +
                            0.0041 * E * cos(Mp - M)
                    val W = abs(cos(F1))
                    val gamma = (P * cos(F1) + Q * sin(F1)) * (1.0 - 0.0048 * W)
                    val u = 0.0059 +
                            0.0046 * E * cos(M) -
                            0.0182 * cos(Mp) +
                            0.0004 * cos(2.0 * Mp) -
                            0.0005 * cos(M + Mp)

                    if (isSolar) {
                        val absGamma = abs(gamma)
                        if (absGamma <= 1.5433 + u) {
                            val type: EclipseType
                            val mag: Double
                            val durSec: Int
                            if (absGamma < 0.9972) {
                                val omegaH = 0.00464 * sqrt(max(0.0, 1.0 - gamma * gamma))
                                type = when {
                                    u < 0.0 -> EclipseType.TOTAL_SOLAR
                                    u > omegaH -> EclipseType.ANNULAR_SOLAR
                                    else -> EclipseType.HYBRID_SOLAR
                                }
                                mag = if (type == EclipseType.ANNULAR_SOLAR) {
                                    ((0.5460 - u) / 0.5460).coerceIn(0.88, 0.999)
                                } else {
                                    (1.0 + abs(u) / 0.273).coerceIn(1.001, 1.08)
                                }
                                durSec = 210
                            } else {
                                type = EclipseType.PARTIAL_SOLAR
                                mag = ((1.5433 + u - absGamma) / (0.5461 + 2.0 * u)).coerceIn(0.01, 0.99)
                                durSec = 0
                            }
                            val (nameEn, nameFa) = when (type) {
                                EclipseType.TOTAL_SOLAR -> "Total Solar Eclipse" to "خورشیدگرفتگی کامل"
                                EclipseType.ANNULAR_SOLAR -> "Annular Solar Eclipse" to "خورشیدگرفتگی حلقوی"
                                EclipseType.HYBRID_SOLAR -> "Hybrid Solar Eclipse" to "خورشیدگرفتگی مرکب"
                                else -> "Partial Solar Eclipse" to "خورشیدگرفتگی جزئی"
                            }
                            return EclipseEvent(
                                type = type,
                                maximumMs = maxMs,
                                magnitude = mag,
                                saros = 136,
                                gamma = gamma,
                                nameEn = nameEn,
                                nameFa = nameFa,
                                durationTotalSeconds = durSec,
                                maxTotalityRegionEn = "Global Eclipse Path",
                                maxTotalityRegionFa = "مسیر جهانی گرفتگی",
                                descriptionEn = "Predicted solar eclipse computed via Meeus Chapter 54 Besselian elements.",
                                descriptionFa = "خورشیدگرفتگی محاسبه‌شده با الگوریتم نجومی میوس (فصل ۵۴).",
                                isSolar = true
                            )
                        }
                    } else {
                        val absGamma = abs(gamma)
                        val magPen = (1.5573 + u - absGamma) / 0.5450
                        val magUmb = (1.0128 - u - absGamma) / 0.5450
                        if (magPen > 0.0) {
                            val type: EclipseType
                            val mag: Double
                            when {
                                magUmb >= 1.0 -> {
                                    type = EclipseType.TOTAL_LUNAR
                                    mag = magUmb
                                }
                                magUmb > 0.0 -> {
                                    type = EclipseType.PARTIAL_LUNAR
                                    mag = magUmb
                                }
                                else -> {
                                    type = EclipseType.PENUMBRAL_LUNAR
                                    mag = magPen
                                }
                            }
                            val n = 0.5458 + 0.0400 * cos(Mp)
                            val tp = 1.5573 + u
                            val pp = 1.0128 - u
                            val tau = 0.4678 - u
                            val sdPenMs = ((1.0 / n) * sqrt(max(0.0, tp * tp - gamma * gamma)) * 3600000.0).roundToLong()
                            val sdUmbMs = if (pp * pp > gamma * gamma) {
                                ((1.0 / n) * sqrt(max(0.0, pp * pp - gamma * gamma)) * 3600000.0).roundToLong()
                            } else 0L
                            val sdTotMs = if (tau * tau > gamma * gamma) {
                                ((1.0 / n) * sqrt(max(0.0, tau * tau - gamma * gamma)) * 3600000.0).roundToLong()
                            } else 0L
                            val durSec = when (type) {
                                EclipseType.TOTAL_LUNAR -> ((2L * sdTotMs) / 1000L).toInt()
                                EclipseType.PARTIAL_LUNAR -> ((2L * sdUmbMs) / 1000L).toInt()
                                else -> 0
                            }
                            val (nameEn, nameFa) = when (type) {
                                EclipseType.TOTAL_LUNAR -> "Total Lunar Eclipse (Blood Moon)" to "خسوف کامل (ماه خونین)"
                                EclipseType.PARTIAL_LUNAR -> "Partial Lunar Eclipse" to "خسوف جزئی"
                                else -> "Penumbral Lunar Eclipse" to "خسوف نیم‌سایه‌ای"
                            }
                            return EclipseEvent(
                                type = type,
                                maximumMs = maxMs,
                                magnitude = mag,
                                saros = 135,
                                gamma = gamma,
                                nameEn = nameEn,
                                nameFa = nameFa,
                                durationTotalSeconds = durSec,
                                maxTotalityRegionEn = "Nighttime Hemisphere",
                                maxTotalityRegionFa = "نیمکره شب زمین",
                                descriptionEn = "Predicted lunar eclipse computed via Meeus Chapter 54 Besselian elements.",
                                descriptionFa = "ماه‌گرفتگی محاسبه‌شده با الگوریتم نجومی میوس (فصل ۵۴).",
                                isSolar = false,
                                penumbralStartMs = maxMs - sdPenMs,
                                umbralStartMs = if (sdUmbMs > 0L) maxMs - sdUmbMs else maxMs,
                                totalityStartMs = if (sdTotMs > 0L) maxMs - sdTotMs else maxMs,
                                totalityEndMs = if (sdTotMs > 0L) maxMs + sdTotMs else maxMs,
                                umbralEndMs = if (sdUmbMs > 0L) maxMs + sdUmbMs else maxMs,
                                penumbralEndMs = maxMs + sdPenMs
                            )
                        }
                    }
                }
            }
            k++
        }
        return null
    }

    private fun t2OrZero(t2: Double): Double = t2
}
