package com.alijafari.red.astronomy.astro_engine

import androidx.compose.ui.graphics.Color
import com.alijafari.red.astronomy.domain.ObjectType

object ObservabilityEngine {

    enum class VisibilityLevel(
        val nameEn: String,
        val nameFa: String,
        val color: Color
    ) {
        OUTSTANDING("Outstanding", "فوق‌العاده استثنایی", Color(0xFF2DC653)),
        EXCELLENT("Excellent", "عالی (شرایط رصد ایده‌آل)", Color(0xFF38B000)),
        VERY_GOOD("Very Good", "بسیار خوب (رصد مطلوب)", Color(0xFF70E000)),
        GOOD("Good", "خوب (قابل مشاهده)", Color(0xFF88D436)),
        MARGINAL("Marginal", "حاشیه‌ای (دشوار)", Color(0xFFFFB703)),
        POOR("Poor", "ضعیف (نیازمند ابزار)", Color(0xFFFF8C00)),
        NOT_OBSERVABLE("Not Observable", "غیرقابل رصد", Color(0xFFE63946))
    }

    data class ObservabilityResult(
        val level: VisibilityLevel,
        val scorePercent: Int,
        val altitudeDeg: Double,
        val isVisibleNow: Boolean,
        val bestObservationTimeEn: String,
        val bestObservationTimeFa: String,
        val reasonsEn: List<String>,
        val reasonsFa: List<String>
    )

    /**
     * Calculates precise human observability score (0-100), classification, and positive/negative reasons.
     */
    fun calculateObservability(
        altitudeDeg: Double,
        sunAltitudeDeg: Double,
        moonIlluminationPercent: Double,
        objectMagnitude: Double,
        bortleClass: Int = 4,
        objectType: ObjectType? = null,
        objectId: String = ""
    ): ObservabilityResult {
        val reasonsEn = mutableListOf<String>()
        val reasonsFa = mutableListOf<String>()
        var score = 0

        val isBelowHorizon = altitudeDeg <= 0.0
        val isDaylight = sunAltitudeDeg >= 0.0
        val isTwilight = sunAltitudeDeg < 0.0 && sunAltitudeDeg > -18.0
        val isTrueDarkness = sunAltitudeDeg <= -18.0

        if (isBelowHorizon) {
            reasonsEn.add("✕ Target is currently below local horizon (${String.format("%.1f", altitudeDeg)}°)")
            reasonsFa.add("✕ جرم هم‌اکنون زیر افق قرار دارد (${String.format("%.1f", altitudeDeg)} درجه)")
            return ObservabilityResult(
                level = VisibilityLevel.NOT_OBSERVABLE,
                scorePercent = 0,
                altitudeDeg = altitudeDeg,
                isVisibleNow = false,
                bestObservationTimeEn = "Wait for object to rise above horizon",
                bestObservationTimeFa = "منتظر طلوع جرم بالای افق باشید",
                reasonsEn = reasonsEn,
                reasonsFa = reasonsFa
            )
        }

        // Special handling for Sun
        if (objectType == ObjectType.SUN || objectId.contains("sun")) {
            return if (altitudeDeg > 0) {
                ObservabilityResult(
                    level = VisibilityLevel.OUTSTANDING,
                    scorePercent = 100,
                    altitudeDeg = altitudeDeg,
                    isVisibleNow = true,
                    bestObservationTimeEn = "Daytime (WARNING: Certified Solar Filter Mandatory!)",
                    bestObservationTimeFa = "روز (هشدار: استفاده از فیلتر مخصوص خورشید اجباری است!)",
                    reasonsEn = listOf("✓ Sun is above horizon", "Warning: Requires ISO-certified solar filter"),
                    reasonsFa = listOf("✓ خورشید بالای افق قرار دارد", "Warning: نیازمند فیلتر استاندارد خورشیدی")
                )
            } else {
                ObservabilityResult(
                    level = VisibilityLevel.NOT_OBSERVABLE,
                    scorePercent = 0,
                    altitudeDeg = altitudeDeg,
                    isVisibleNow = false,
                    bestObservationTimeEn = "Sun is currently set (Nighttime)",
                    bestObservationTimeFa = "خورشید در حال حاضر غروب کرده است",
                    reasonsEn = listOf("✕ Sun is below horizon"),
                    reasonsFa = listOf("✕ خورشید زیر افق است")
                )
            }
        }

        // 1. Altitude & Atmospheric Extinction Check
        when {
            altitudeDeg >= 40.0 -> {
                score += 35
                reasonsEn.add("✓ High altitude in clear atmosphere (${altitudeDeg.toInt()}°)")
                reasonsFa.add("✓ ارتفاع بالا در جو شفاف (${altitudeDeg.toInt()} درجه)")
            }
            altitudeDeg >= 20.0 -> {
                score += 25
                reasonsEn.add("✓ Medium altitude (${altitudeDeg.toInt()}°)")
                reasonsFa.add("✓ ارتفاع متوسط بالای افق (${altitudeDeg.toInt()} درجه)")
            }
            altitudeDeg >= 10.0 -> {
                score += 15
                reasonsEn.add("Warning: Low altitude subject to horizon extinction (${altitudeDeg.toInt()}°)")
                reasonsFa.add("Warning: ارتفاع پایین تحت تاثیر شکست نوری افق (${altitudeDeg.toInt()} درجه)")
            }
            else -> {
                score += 5
                reasonsEn.add("✕ Very close to horizon (<10°) - Heavy extinction")
                reasonsFa.add("✕ بسیار نزدیک به افق (کمتر از ۱۰ درجه) - افت شدید نور")
            }
        }

        // 2. Solar Twilight & Darkness Factor
        when {
            isTrueDarkness -> {
                score += 35
                reasonsEn.add("✓ True astronomical darkness (Sun $sunAltitudeDeg°)")
                reasonsFa.add("✓ تاریکی کامل نجومی (ارتفاع خورشید $sunAltitudeDeg درجه)")
            }
            sunAltitudeDeg <= -12.0 -> {
                score += 25
                reasonsEn.add("✓ Nautical twilight darkness")
                reasonsFa.add("✓ گرگ و میش دریانوردی (تاریکی مناسب)")
            }
            sunAltitudeDeg <= -6.0 -> {
                score += 15
                if (objectMagnitude > 1.0) {
                    reasonsEn.add("Warning: Civil twilight glow reduces contrast")
                    reasonsFa.add("Warning: روشنایی شفق شهری کنتراست را کاهش می‌دهد")
                } else {
                    reasonsEn.add("✓ Bright object visible in civil twilight")
                    reasonsFa.add("✓ جرم درخشان در شفق شهری قابل مشاهده است")
                }
            }
            else -> {
                if (objectType == ObjectType.MOON || objectMagnitude < -3.0) {
                    score += 10
                    reasonsEn.add("Warning: Visible during daytime/dusk due to high brightness")
                    reasonsFa.add("Warning: به دلیل درخشندگی بالا در روز/شفق دیده می‌شود")
                } else {
                    score += 0
                    reasonsEn.add("✕ Sunlight masks faint astronomical objects")
                    reasonsFa.add("✕ نور خورشید جرم‌های کم‌نور را محو می‌کند")
                }
            }
        }

        // 3. Moon Phase & Interference
        if (objectType == ObjectType.MOON) {
            score += 20
            reasonsEn.add("✓ Target is the Moon (${moonIlluminationPercent.toInt()}% illuminated)")
            reasonsFa.add("✓ جرم هدف ماه است (${moonIlluminationPercent.toInt()}٪ درخشندگی)")
        } else {
            if (moonIlluminationPercent < 25.0) {
                score += 15
                reasonsEn.add("✓ Minimal Moon interference (Crescent / New Moon)")
                reasonsFa.add("✓ کمترین تداخل نور ماه (هلال / ماه نو)")
            } else if (moonIlluminationPercent < 60.0) {
                score += 10
                reasonsEn.add("✓ Moderate Moon brightness")
                reasonsFa.add("✓ روشنایی متوسط ماه")
            } else {
                if (objectType == ObjectType.DEEP_SKY) {
                    score += 2
                    reasonsEn.add("✕ Bright Full Moon washes out deep sky contrast")
                    reasonsFa.add("✕ ماه کامل کنتراست جرم‌های اعماق فضا را محو می‌کند")
                } else {
                    score += 8
                    reasonsEn.add("Warning: Bright Moon in sky")
                    reasonsFa.add("Warning: حضور ماه پرنور در آسمان")
                }
            }
        }

        // 4. Light Pollution (Bortle Class)
        val bortleImpact = when {
            bortleClass <= 3 -> {
                score += 15
                reasonsEn.add("✓ Excellent dark sky (Bortle $bortleClass)")
                reasonsFa.add("✓ آسمان تاریک رصدی (بورتل $bortleClass)")
            }
            bortleClass <= 5 -> {
                score += 10
                reasonsEn.add("✓ Suburban sky (Bortle $bortleClass)")
                reasonsFa.add("✓ آسمان نیمه‌شهری (بورتل $bortleClass)")
            }
            else -> {
                if (objectType == ObjectType.DEEP_SKY) {
                    score += 2
                    reasonsEn.add("✕ City light pollution obscures nebulae & galaxies (Bortle $bortleClass)")
                    reasonsFa.add("✕ آلودگی نوری شهری سحابی‌ها و کهکشان‌ها را محو می‌کند (بورتل $bortleClass)")
                } else {
                    score += 5
                    reasonsEn.add("Warning: Urban light pollution present (Bortle $bortleClass)")
                    reasonsFa.add("Warning: آلودگی نوری شهری موجود است (بورتل $bortleClass)")
                }
            }
        }

        val totalScore = score.coerceIn(0, 100)

        val level = when {
            totalScore >= 88 -> VisibilityLevel.OUTSTANDING
            totalScore >= 75 -> VisibilityLevel.EXCELLENT
            totalScore >= 62 -> VisibilityLevel.VERY_GOOD
            totalScore >= 48 -> VisibilityLevel.GOOD
            totalScore >= 32 -> VisibilityLevel.MARGINAL
            totalScore >= 15 -> VisibilityLevel.POOR
            else -> VisibilityLevel.NOT_OBSERVABLE
        }

        val isVisible = altitudeDeg > 0.0 && (sunAltitudeDeg < -6.0 || objectMagnitude < -3.0 || objectType == ObjectType.MOON)

        val timeEn = if (isVisible) "Observable right now in your sky" else "Look during peak darkness after sunset"
        val timeFa = if (isVisible) "هم‌اکنون در آسمان شما قابل رصد است" else "در تاریکی مطلق پس از غروب آفتاب رصد کنید"

        return ObservabilityResult(
            level = level,
            scorePercent = totalScore,
            altitudeDeg = altitudeDeg,
            isVisibleNow = isVisible,
            bestObservationTimeEn = timeEn,
            bestObservationTimeFa = timeFa,
            reasonsEn = reasonsEn,
            reasonsFa = reasonsFa
        )
    }
}
