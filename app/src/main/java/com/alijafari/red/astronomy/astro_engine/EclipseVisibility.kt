package com.alijafari.red.astronomy.astro_engine

import com.alijafari.red.astronomy.util.toPersianDigits
import java.util.Locale

/**
 * Visibility criteria for solar and lunar eclipses, plus the observer-facing
 * "tell me more" payload: what the eclipse is called, what colour the Moon will
 * turn, and what makes it significant.
 *
 * WHY THIS EXISTS
 * ---------------
 * "Is it visible?" is not one question. It is two, and they are independent:
 *
 *   1. Can the body be seen at all from this location? (is it above the horizon
 *      during the event, and is the event still going where it can be seen?)
 *   2. Is there anything to see when it is? (is the eclipse deep enough for a
 *      human being, or an instrument, to notice?)
 *
 * Point 2 is the one most eclipse code ignores, and it is where the interesting
 * astronomy lives:
 *
 *   - A SOLAR eclipse with the Sun up is technically observable at any depth,
 *     because a filter makes the bite visible. But below roughly 1% obscuration
 *     there is nothing to see worth planning around, and below ~75% the ambient
 *     light does not change at all.
 *   - A PENUMBRAL LUNAR eclipse is famously undetectable. NASA's Five Millennium
 *     Canon (Espenak & Meeus) is explicit: "they cannot be detected visually -
 *     with or without optical aid - unless the eclipse magnitude is greater than
 *     ~0.6". EclipseWise adds that a penumbral eclipse "only becomes readily
 *     apparent when it is within ~0.05 magnitudes of becoming a partial eclipse".
 *
 * Thresholds below that are quoted from a source carry their source in a
 * comment. Thresholds that are engineering judgement (there is no published
 * value) are marked as such and are deliberately conservative.
 */
object EclipseVisibility {

    // ------------------------------------------------------------------
    // Thresholds
    // ------------------------------------------------------------------

    /**
     * Minimum fraction of the Sun's disk AREA that must be covered for the
     * eclipse to count as observable. Engineering judgement, not a published
     * figure: a 1% area bite is clearly visible through a certified solar filter,
     * while a fraction of a percent is not distinguishable from the Sun's limb.
     * Deliberately generous, so genuinely observable shallow eclipses are listed
     * rather than hidden.
     */
    const val SOLAR_MIN_OBSCURATION = 0.01

    /**
     * Obscuration at which the surroundings start to look dim.
     * Source: Wikipedia "Solar eclipse" ("If three-quarters or more of the Sun
     * is obscured, then an effect can be observed by which the daylight appears
     * to be dim, as if the sky were overcast, yet objects still cast sharp
     * shadows") and the analysis in "Does it get darker during a partial solar
     * eclipse?" (Holmes): "it's not until the obscuration gets to at least 75%
     * (a magnitude of 0.80) that anything significant is noticeable".
     */
    const val SOLAR_NOTICEABLE_OBSCURATION = 0.75

    /** Obscuration above which the crescent is unmistakable through a filter. */
    const val SOLAR_GOOD_OBSCURATION = 0.25

    /**
     * Minimum penumbral magnitude for a penumbral lunar eclipse to be detectable.
     * Source: Espenak & Meeus, Five Millennium Canon of Lunar Eclipses (NASA
     * TP-2009-214172) and EclipseWise "Visual Appearance of Lunar Eclipses":
     * "they cannot be detected visually - with or without optical aid - unless
     * the eclipse magnitude is greater than ~0.6" and "A penumbral magnitude
     * greater than ~0.6 is needed before skilled observers can detect faint
     * shading across the Moon's disk."
     */
    const val LUNAR_PENUMBRAL_MIN_MAGNITUDE = 0.6

    /**
     * Minimum UMBRAL magnitude for a partial lunar eclipse to count as
     * observable. Engineering judgement: NASA publishes no threshold for partial
     * umbral eclipses (unlike penumbral ones), because any umbral bite is
     * readily visible. 0.02 is set so that the handful of extremely marginal
     * partials (e.g. umbral magnitude 0.014) are reported as not observable
     * instead of being advertised as an event.
     */
    const val LUNAR_PARTIAL_MIN_MAGNITUDE = 0.02

    /** Umbral magnitude at which a partial lunar eclipse becomes unmistakable. */
    const val LUNAR_GOOD_UMBRAL_MAGNITUDE = 0.4

    /** Umbral magnitude at which a partial lunar eclipse is dramatic. */
    const val LUNAR_STRONG_UMBRAL_MAGNITUDE = 0.8

    /** Altitude above which atmospheric extinction stops being a factor. */
    const val HIGH_ALT_DEG = 30.0
    /** Altitude below which the object sits in thick air and haze. */
    const val MEDIUM_ALT_DEG = 10.0
    /** Altitude below which a flat, unobstructed horizon becomes essential. */
    const val LOW_ALT_DEG = 2.0

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    enum class Grade(val labelEn: String, val labelFa: String) {
        /** Central (total/annular) at this location, or a total lunar eclipse. */
        SPECTACULAR("Spectacular", "بی‌نظیر"),
        /** Deep partial: the sky dims, or most of the Moon is in shadow. */
        EXCELLENT("Excellent", "عالی"),
        /** Clearly visible, but the surroundings look unchanged. */
        GOOD("Good", "خوب"),
        /** Technically observable; needs care or equipment to appreciate. */
        MARGINAL("Marginal", "ضعیف"),
        /** Nothing to see from this location. */
        NOT_OBSERVABLE("Not observable", "غیرقابل رصد")
    }

    data class Assessment(
        val isObservable: Boolean,
        val grade: Grade,
        /** Why it is or is not observable, in one sentence. */
        val reasonEn: String,
        val reasonFa: String,
        /** How comfortable the altitude makes the observation. */
        val altitudeNoteEn: String,
        val altitudeNoteFa: String
    )

    data class Insight(
        /** Popular or traditional name for this kind of eclipse. */
        val nicknameEn: String,
        val nicknameFa: String,
        /** What you will actually see: colour, shape, Danjon scale. */
        val appearanceEn: String,
        val appearanceFa: String,
        /** Computed or historical significance. */
        val significanceEn: String,
        val significanceFa: String
    )

    // ------------------------------------------------------------------
    // Assessment
    // ------------------------------------------------------------------

    fun assess(result: EclipseEngine.EclipseResult): Assessment =
        assess(
            event = result.event,
            isLocallyVisible = result.isLocallyVisible,
            localStartTimeMs = result.localStartTimeMs,
            localEndTimeMs = result.localEndTimeMs,
            localObscurationPercent = result.localObscurationPercent,
            localMagnitude = result.localMagnitude,
            targetAltitudeDeg = result.targetAltitudeDeg
        )

    /**
     * Primitive form, used by [EclipseEngine] while it is still building the
     * [EclipseEngine.EclipseResult]: the assessment is derived from the very
     * fields that go into it, so it cannot take the result itself.
     */
    fun assess(
        event: EclipseEngine.EclipseEvent,
        isLocallyVisible: Boolean,
        localStartTimeMs: Long,
        localEndTimeMs: Long,
        localObscurationPercent: Int,
        localMagnitude: Double,
        targetAltitudeDeg: Double
    ): Assessment = if (event.isSolar) {
        assessSolar(
            event, isLocallyVisible, localStartTimeMs, localEndTimeMs,
            localObscurationPercent, targetAltitudeDeg
        )
    } else {
        assessLunar(
            event, isLocallyVisible, localStartTimeMs, localEndTimeMs,
            localMagnitude, targetAltitudeDeg
        )
    }

    fun assessSolar(
        event: EclipseEngine.EclipseEvent,
        isLocallyVisible: Boolean,
        localStartTimeMs: Long,
        localEndTimeMs: Long,
        localObscurationPercent: Int,
        targetAltitudeDeg: Double
    ): Assessment {
        val obscuration = localObscurationPercent / 100.0
        val alt = targetAltitudeDeg

        // Gate 1: the Sun must be above the horizon sometime during the event.
        // The local scan already refuses to report contacts when the Sun is down,
        // so a non-visible result here means "never rose".
        if (!isLocallyVisible || localEndTimeMs <= localStartTimeMs) {
            return Assessment(
                isObservable = false,
                grade = Grade.NOT_OBSERVABLE,
                reasonEn = "The Sun stays below your horizon for the whole of this eclipse, so no part of it is visible from your location.",
                reasonFa = "خورشید در تمام مدت این گرفتگی زیر افق موقعیت شما می‌ماند، بنابراین هیچ بخشی از آن را نمی‌بینید.",
                altitudeNoteEn = altitudeNoteEn(alt, "the Sun"),
                altitudeNoteFa = altitudeNoteFa(alt)
            )
        }

        // Gate 2: there must be enough coverage to be worth looking at.
        if (obscuration < SOLAR_MIN_OBSCURATION) {
            val pctEn = formatPercentEn(obscuration)
            val pctFa = formatPercentFa(obscuration)
            return Assessment(
                isObservable = false,
                grade = Grade.NOT_OBSERVABLE,
                reasonEn = "At your location the Moon covers only $pctEn of the Sun's disk. A bite that small is not distinguishable even through a certified solar filter.",
                reasonFa = "در موقعیت شما ماه تنها $pctFa از قرص خورشید را می‌پوشاند. چنین بریدگی کوچکی حتی با فیلتر استاندارد خورشیدی هم از لبهٔ خورشید بازشناخته نمی‌شود.",
                altitudeNoteEn = altitudeNoteEn(alt, "the Sun"),
                altitudeNoteFa = altitudeNoteFa(alt)
            )
        }

        val grade = when {
            event.type == EclipseEngine.EclipseType.TOTAL_SOLAR &&
                obscuration >= 0.999 -> Grade.SPECTACULAR
            event.type == EclipseEngine.EclipseType.ANNULAR_SOLAR &&
                obscuration >= 0.999 -> Grade.SPECTACULAR
            obscuration >= SOLAR_NOTICEABLE_OBSCURATION -> Grade.EXCELLENT
            obscuration >= SOLAR_GOOD_OBSCURATION -> Grade.GOOD
            else -> Grade.MARGINAL
        }

        val pctEn = formatPercentEn(obscuration)
        val pctFa = formatPercentFa(obscuration)
        val reasonEn = when (grade) {
            Grade.SPECTACULAR ->
                "At your location this eclipse reaches its central phase, with $pctEn of the Sun covered at maximum."
            Grade.EXCELLENT ->
                "A deep partial eclipse at your location: $pctEn of the Sun's disk is covered, enough that the daylight visibly dims while shadows stay sharp."
            Grade.GOOD ->
                "A clear partial eclipse at your location: $pctEn of the Sun's disk is covered. The crescent is obvious through a solar filter, though the daylight looks unchanged."
            else ->
                "A shallow partial eclipse at your location: only $pctEn of the Sun's disk is covered. You will need a certified solar filter to see anything at all."
        }
        val reasonFa = when (grade) {
            Grade.SPECTACULAR ->
                "در موقعیت شما این گرفتگی به فاز مرکزی می‌رسد و در اوج، $pctFa از قرص خورشید پوشیده می‌شود."
            Grade.EXCELLENT ->
                "در موقعیت شما یک گرفتگی جزئیِ عمیق رخ می‌دهد: $pctFa از قرص خورشید پوشیده می‌شود، یعنی به اندازه‌ای که تاریک شدن نور روز را حس می‌کنید و در عین حال سایه‌ها همچنان تیز می‌مانند."
            Grade.GOOD ->
                "در موقعیت شما یک گرفتگی جزئیِ روشن رخ می‌دهد: $pctFa از قرص خورشید پوشیده می‌شود. هلال خورشید با فیلتر خورشیدی کاملاً پیداست، ولی روشناییِ محیط تغییر محسوسی نمی‌کند."
            else ->
                "در موقعیت شما تنها $pctFa از قرص خورشید پوشیده می‌شود. برای دیدن همین اندازه هم به فیلتر استاندارد خورشیدی نیاز دارید."
        }

        return Assessment(
            isObservable = true,
            grade = grade,
            reasonEn = reasonEn,
            reasonFa = reasonFa,
            altitudeNoteEn = altitudeNoteEn(alt, "the Sun"),
            altitudeNoteFa = altitudeNoteFa(alt)
        )
    }

    fun assessLunar(
        event: EclipseEngine.EclipseEvent,
        isLocallyVisible: Boolean,
        localStartTimeMs: Long,
        localEndTimeMs: Long,
        localMagnitude: Double,
        targetAltitudeDeg: Double
    ): Assessment {
        val alt = targetAltitudeDeg

        // Gate 1: the Moon must be above the horizon sometime during the event.
        if (!isLocallyVisible || localEndTimeMs <= localStartTimeMs) {
            return Assessment(
                isObservable = false,
                grade = Grade.NOT_OBSERVABLE,
                reasonEn = "The Moon stays below your horizon for the whole of this eclipse, so no part of it is visible from your location.",
                reasonFa = "ماه در تمام مدت این خسوف زیر افق موقعیت شما می‌ماند، بنابراین هیچ بخشی از آن را نمی‌بینید.",
                altitudeNoteEn = altitudeNoteEn(alt, "the Moon"),
                altitudeNoteFa = altitudeNoteFa(alt)
            )
        }

        // Gate 2: is there enough eclipse to notice?
        // Use the LOCAL magnitude (the depth at the moment you can actually see
        // it) rather than the global one: if the Moon only rises for the last
        // few minutes of a partial eclipse, the shadow is barely on it.
        val localMag = localMagnitude
        val isPenumbral = event.type == EclipseEngine.EclipseType.PENUMBRAL_LUNAR

        if (isPenumbral && localMag < LUNAR_PENUMBRAL_MIN_MAGNITUDE) {
            val magEn = formatMagEn(localMag)
            val magFa = formatMagFa(localMag)
            return Assessment(
                isObservable = false,
                grade = Grade.NOT_OBSERVABLE,
                reasonEn = "This is a penumbral eclipse of magnitude $magEn. NASA's Five Millennium Canon notes that penumbral eclipses cannot be detected visually, with or without optical aid, until the magnitude exceeds about 0.6.",
                reasonFa = "قدر نیم‌سایه‌ای این خسوف $magFa است. در کاتالوگ پنج‌هزارسالهٔ ناسا آمده است که خسوف نیم‌سایه‌ای را تا وقتی قدر آن از حدود ۰٫۶ بیشتر نشود، نه با چشم غیرمسلح و نه با ابزار اپتیکی نمی‌توان تشخیص داد.",
                altitudeNoteEn = altitudeNoteEn(alt, "the Moon"),
                altitudeNoteFa = altitudeNoteFa(alt)
            )
        }

        if (!isPenumbral && localMag < LUNAR_PARTIAL_MIN_MAGNITUDE) {
            val magEn = formatMagEn(localMag)
            val magFa = formatMagFa(localMag)
            return Assessment(
                isObservable = false,
                grade = Grade.NOT_OBSERVABLE,
                reasonEn = "Only $magEn of the Moon's diameter enters Earth's umbra as seen from your location. A bite that small is not distinguishable from an ordinary full Moon.",
                reasonFa = "در موقعیت شما تنها $magFa از قطر ماه وارد سایهٔ زمین می‌شود. چنین بریدگی کوچکی از یک ماه کاملِ معمولی بازشناخته نمی‌شود.",
                altitudeNoteEn = altitudeNoteEn(alt, "the Moon"),
                altitudeNoteFa = altitudeNoteFa(alt)
            )
        }

        val grade = when {
            event.type == EclipseEngine.EclipseType.TOTAL_LUNAR -> Grade.SPECTACULAR
            // A penumbral eclipse is never more than faint shading, however large
            // its penumbral magnitude: the scale is different from the umbral one
            // (a penumbral magnitude of 0.9 still means the Moon entirely misses
            // the umbra). So it caps at MARGINAL even at 0.99.
            isPenumbral -> Grade.MARGINAL
            localMag >= LUNAR_STRONG_UMBRAL_MAGNITUDE -> Grade.EXCELLENT
            localMag >= LUNAR_GOOD_UMBRAL_MAGNITUDE -> Grade.GOOD
            else -> Grade.MARGINAL
        }

        val magEn = formatMagEn(localMag)
        val magFa = formatMagFa(localMag)
        val reasonEn = when {
            event.type == EclipseEngine.EclipseType.TOTAL_LUNAR ->
                "Total from your location: the whole Moon enters Earth's umbra, with an umbral magnitude of $magEn."
            isPenumbral ->
                "This is a penumbral eclipse of magnitude $magEn. Above the 0.6 detection threshold, so experienced observers can make out faint shading across the Moon, and nothing more."
            else ->
                "A partial umbral eclipse at your location: $magEn of the Moon's diameter sits inside Earth's umbra, and the dark bite on the lunar limb is obvious to the naked eye."
        }
        val reasonFa = when {
            event.type == EclipseEngine.EclipseType.TOTAL_LUNAR ->
                "در موقعیت شما این خسوف کامل است: همهٔ قرص ماه وارد سایهٔ زمین می‌شود و قدر سایه‌ای آن به $magFa می‌رسد."
            isPenumbral ->
                "قدر نیم‌سایه‌ای این خسوف $magFa است. این مقدار از آستانهٔ ۰٫۶ بیشتر است، بنابراین رصدگران با‌تجربه می‌توانند سایه‌ای بسیار ملایم روی قرص ماه تشخیص دهند، و بیش از این هم خبری نیست."
            else ->
                "در موقعیت شما یک خسوف جزئی رخ می‌دهد: $magFa از قطر ماه درون سایهٔ زمین قرار می‌گیرد و بریدگی تاریک لبهٔ ماه با چشم غیرمسلح کاملاً پیداست."
        }

        return Assessment(
            isObservable = true,
            grade = grade,
            reasonEn = reasonEn,
            reasonFa = reasonFa,
            altitudeNoteEn = altitudeNoteEn(alt, "the Moon"),
            altitudeNoteFa = altitudeNoteFa(alt)
        )
    }

    /**
     * Altitude for prose. Whole degrees are plenty once the object is clear of
     * the horizon; below [MEDIUM_ALT_DEG] a decimal matters, because the
     * difference between 3° and 3.9° is the difference between "low" and "in
     * the trees". Always formatted with [Locale.US] so a device locale using
     * non-ASCII digits cannot leak into the text.
     */
    private fun fmtAlt(altDeg: Double): String =
        if (altDeg >= MEDIUM_ALT_DEG) "%.0f".format(Locale.US, altDeg)
        else "%.1f".format(Locale.US, altDeg)

    private fun altitudeNoteEn(altDeg: Double, body: String): String {
        val a = fmtAlt(altDeg)
        return when {
            altDeg >= HIGH_ALT_DEG -> "$body stands $a° high at maximum: no trouble from haze or thick air."
            altDeg >= MEDIUM_ALT_DEG -> "$body stands $a° up at maximum: comfortable viewing."
            altDeg >= LOW_ALT_DEG -> "$body is only $a° up at maximum: low in thick air, so you need a flat, clear horizon."
            else -> "$body sits within $a° of the horizon at maximum, so this is very hard to observe in practice."
        }
    }

    private fun altitudeNoteFa(altDeg: Double): String {
        val a = fmtAlt(altDeg).toPersianDigits()
        return when {
            altDeg >= HIGH_ALT_DEG -> "در لحظهٔ اوج، ارتفاع آن $a درجه است؛ نه کدریِ هوا مزاحم است و نه ضخامت جو."
            altDeg >= MEDIUM_ALT_DEG -> "در لحظهٔ اوج، ارتفاع آن $a درجه است؛ شرایط رصد راحت است."
            altDeg >= LOW_ALT_DEG -> "در لحظهٔ اوج، ارتفاع آن تنها $a درجه است؛ در هوای غلیظ و نزدیک به افق است و به افق صاف و باز نیاز دارید."
            else -> "در لحظهٔ اوج، ارتفاع آن کمتر از $a درجه است و درست در لبهٔ افق قرار دارد، بنابراین رصد آن در عمل بسیار دشوار است."
        }
    }

    // ------------------------------------------------------------------
    // Insight: nickname, appearance, significance
    // ------------------------------------------------------------------

    /**
     * @param context precomputed catalogue superlatives (see [EclipseEngine.catalogueContext]).
     */
    fun insight(
        event: EclipseEngine.EclipseEvent,
        context: EclipseEngine.CatalogueContext
    ): Insight = if (event.isSolar) solarInsight(event, context) else lunarInsight(event, context)

    private fun solarInsight(
        event: EclipseEngine.EclipseEvent,
        context: EclipseEngine.CatalogueContext
    ): Insight {
        val nickname = when (event.type) {
            EclipseEngine.EclipseType.ANNULAR_SOLAR -> "Ring of Fire" to "حلقهٔ آتش"
            EclipseEngine.EclipseType.TOTAL_SOLAR -> "Total Solar Eclipse" to "خورشیدگرفتگی کامل"
            EclipseEngine.EclipseType.HYBRID_SOLAR -> "Hybrid Eclipse" to "خورشیدگرفتگی مرکب"
            else -> "Partial Solar Eclipse" to "خورشیدگرفتگی جزئی"
        }

        val appearanceEn = when (event.type) {
            EclipseEngine.EclipseType.ANNULAR_SOLAR ->
                "At maximum the Sun becomes a brilliant ring, the \"ring of fire\": the Moon is slightly too small to cover it completely. The sky never goes dark."
            EclipseEngine.EclipseType.TOTAL_SOLAR ->
                "The Sun is completely covered and the solar corona appears: a pearly white halo, only ever visible during totality. The sky darkens to deep twilight and planets and bright stars come out."
            EclipseEngine.EclipseType.HYBRID_SOLAR ->
                "A rare hybrid: the eclipse is annular along part of its path and total along another, so it shows a ring from some places and a corona from others."
            else ->
                "The Sun is bitten into a crescent. Its colour and the daylight around you change very little, so the whole event is only visible through a certified solar filter."
        }
        val appearanceFa = when (event.type) {
            EclipseEngine.EclipseType.ANNULAR_SOLAR ->
                "در لحظهٔ اوج، خورشید به شکل حلقه‌ای درخشان درمی‌آید که به آن حلقهٔ آتش می‌گویند؛ چون اندازهٔ ظاهری ماه کمی از خورشید کوچک‌تر است و نمی‌تواند آن را کامل بپوشاند. آسمان هرگز کاملاً تاریک نمی‌شود."
            EclipseEngine.EclipseType.TOTAL_SOLAR ->
                "خورشید کاملاً پوشیده می‌شود و تاج خورشیدی پدیدار می‌شود: هاله‌ای سفید و مرواریدی که دیدن آن فقط در همان چند دقیقهٔ فاز کامل ممکن است. آسمان به تاریکیِ شفقِ عمیق می‌رسد و سیاره‌ها و ستارگان پرنور پدیدار می‌شوند."
            EclipseEngine.EclipseType.HYBRID_SOLAR ->
                "این یک گرفتگی مرکب و کمیاب است: در بخشی از مسیر حلقوی و در بخش دیگر کامل دیده می‌شود، بنابراین از بعضی نقاط حلقهٔ آتش و از بعضی نقاط تاج خورشیدی دیده خواهد شد."
            else ->
                "لبهٔ خورشید به شکل هلال درمی‌آید. نه رنگ آن تغییر محسوسی می‌کند و نه روشناییِ محیط، بنابراین دیدن کل این رخداد فقط با فیلتر استاندارد خورشیدی ممکن است."
        }

        val bits = mutableListOf<String>()
        val bitsFa = mutableListOf<String>()
        val secs = event.durationTotalSeconds
        if (secs > 0) {
            val mm = secs / 60
            val ss = secs % 60
            bits += "Totality (or annularity) lasts up to ${mm}m ${"%02d".format(Locale.US, ss)}s on the central line."
            bitsFa += "مدت فاز کامل یا حلقوی در خط مرکزی تا ${mm.toString().toPersianDigits()} دقیقه و ${"%02d".format(Locale.US, ss).toPersianDigits()} ثانیه است."
        }
        bits += "Member of Saros series ${event.saros}."
        bitsFa += "این گرفتگی عضو دورهٔ ساروس ${event.saros.toString().toPersianDigits()} است."
        if (context.longestCentralSolarMs == event.maximumMs && secs > 0) {
            bits += "The longest central phase of any solar eclipse in this app's catalogue (2024 to 2040)."
            bitsFa += "درازترین فاز مرکزی در میان همهٔ خورشیدگرفتگی‌های فهرست این برنامه (۲۰۲۴ تا ۲۰۴۰)."
        }
        if (secs >= 300) {
            bits += "A long central phase like this one only happens when the Moon is near perigee and Earth near aphelion."
            bitsFa += "فاز مرکزیِ درازی مانند این فقط زمانی رخ می‌دهد که ماه در نزدیکیِ حضیض و زمین در نزدیکیِ اوج خود باشد."
        }

        return Insight(
            nicknameEn = nickname.first,
            nicknameFa = nickname.second,
            appearanceEn = appearanceEn,
            appearanceFa = appearanceFa,
            significanceEn = bits.joinToString(" "),
            significanceFa = bitsFa.joinToString(" ")
        )
    }

    private fun lunarInsight(
        event: EclipseEngine.EclipseEvent,
        context: EclipseEngine.CatalogueContext
    ): Insight {
        val nickname = when (event.type) {
            EclipseEngine.EclipseType.TOTAL_LUNAR -> "Blood Moon" to "ماه خونین"
            EclipseEngine.EclipseType.PARTIAL_LUNAR -> "Partial Umbral Eclipse" to "خسوف جزئی"
            else -> "Penumbral Eclipse" to "خسوف نیم‌سایه‌ای"
        }

        // ---- colour, graded by how deep the Moon goes into the umbra ----
        // The Danjon scale (Andre Danjon, 1921) grades the brightness of a total
        // lunar eclipse from L=0 (Moon almost invisible) to L=4 (bright
        // copper-red with a bluish rim). The exact value on the night depends on
        // the stratospheric aerosol load, which cannot be predicted: after a
        // major eruption such as Pinatubo in 1991 the Moon can go almost black.
        // What CAN be predicted is the DEPTH of the umbral passage, and deeper
        // passages are darker and redder, so the expectation is given as a band.
        val umMag = event.magnitude
        val appearanceEn: String
        val appearanceFa: String
        when (event.type) {
            EclipseEngine.EclipseType.TOTAL_LUNAR -> {
                val (enHue, faHue, band) = when {
                    umMag >= 1.5 -> Triple(
                        "deep red to rusty brown",
                        "قرمز تیره تا قهوه‌ای زنگاری",
                        "L=1 to L=2"
                    )
                    umMag >= 1.2 -> Triple(
                        "brick red",
                        "قرمز آجری",
                        "L=2 to L=3"
                    )
                    else -> Triple(
                        "bright copper or orange, with a bright outer rim",
                        "مسّی روشن یا نارنجی، با حاشیه‌ای پرنور",
                        "L=3 to L=4"
                    )
                }
                appearanceEn =
                    "Expect the Moon to turn $enHue. It passes deep into Earth's umbra, so the only light reaching it is sunlight bent through Earth's atmosphere, which strips out the blue and leaves the red: this is why a totally eclipsed Moon is called a blood moon. On the Danjon scale that normally means $band. The exact tint is not predictable in advance: volcanic dust in the stratosphere can darken it dramatically, even to L=0, as it did after Mount Pinatubo in 1991."
                appearanceFa =
                    "انتظار می‌رود ماه به رنگ $faHue درآید. ماه از بخش عمیق سایهٔ زمین می‌گذرد، بنابراین تنها نوری که به آن می‌رسد همان نور خورشید است که با عبور از جو زمین خم شده و رنگ آبی‌اش را از دست داده است؛ به همین دلیل به ماه گرفتار در خسوف کامل، ماه خونین می‌گویند. در مقیاس دانژون این معمولاً به معنای $band است. رنگ دقیق از پیش قابل پیش‌بینی نیست: گرد و غبار آتشفشانی در لایهٔ استراتوسفر می‌تواند آن را به‌شدت تیره کند و حتی تا L=۰ برساند، همان‌گونه که پس از فوران کوه پیناتوبو در سال ۱۹۹۱ چنین شد."
            }
            EclipseEngine.EclipseType.PARTIAL_LUNAR -> {
                appearanceEn =
                    "No colour change. The part of the Moon inside Earth's umbra turns dark grey to brown, while the rest of the disk stays at its usual brilliant white and, because your eye adapts to the bright part, the shadowed edge looks all the blacker."
                appearanceFa =
                    "هیچ تغییر رنگی رخ نمی‌دهد. بخشی از ماه که درون سایهٔ زمین است خاکستری تیره تا قهوه‌ای می‌شود و بقیهٔ قرص به همان سپیدیِ همیشگی می‌ماند؛ و چون چشم شما خود را با بخش روشن تطبیق می‌دهد، بخش سایه‌گرفته تیره‌تر هم به نظر می‌رسد."
            }
            else -> {
                appearanceEn =
                    "No colour change at all. At best, a very faint grey shading washes across the disk, and telling it apart from an ordinary full Moon means comparing against a photograph taken before the eclipse."
                appearanceFa =
                    "هیچ تغییر رنگی رخ نمی‌دهد. در بهترین حالت، سایه‌ای بسیار ملایم و خاکستری روی قرص ماه می‌افتد که تشخیص آن از یک ماه کاملِ معمولی نیازمند مقایسه با عکسی است که پیش از خسوف گرفته باشید."
            }
        }

        val bits = mutableListOf<String>()
        val bitsFa = mutableListOf<String>()

        bits += "Member of Saros series ${event.saros}."
        bitsFa += "این خسوف عضو دورهٔ ساروس ${event.saros.toString().toPersianDigits()} است."

        val absGamma = kotlin.math.abs(event.gamma)
        if (absGamma <= 0.1) {
            bits += "The Moon passes almost straight through the centre of Earth's shadow (gamma ${"%.3f".format(Locale.US, event.gamma)}), which makes this a notably deep eclipse."
            bitsFa += "ماه تقریباً از مرکز سایهٔ زمین می‌گذرد (گاما برابر با ${"%.3f".format(Locale.US, event.gamma).toPersianDigits()}) و به همین دلیل این خسوف عمق قابل‌توجهی دارد."
        }

        if (context.deepestLunarMs == event.maximumMs && umMag > 0.0) {
            bits += "The deepest lunar eclipse in this app's catalogue (2024 to 2040)."
            bitsFa += "عمیق‌ترین ماه‌گرفتگی در فهرست این برنامه (۲۰۲۴ تا ۲۰۴۰)."
        }
        if (context.longestLunarTotalityMs == event.maximumMs) {
            bits += "The longest totality of any lunar eclipse in this app's catalogue (2024 to 2040)."
            bitsFa += "درازترین فاز کامل در میان همهٔ ماه‌گرفتگی‌های فهرست این برنامه (۲۰۲۴ تا ۲۰۴۰)."
        }
        if (context.tetradMemberMs.contains(event.maximumMs)) {
            bits += "One of four consecutive total lunar eclipses, a sequence known as a tetrad."
            bitsFa += "یکی از چهار خسوف کاملِ پیاپی است؛ به چنین دنباله‌ای تتراد می‌گویند."
        }

        // Traditional full-moon name. These are Northern Hemisphere seasonal
        // names (Algonquian, Anglo-Saxon and Celtic in origin, popularised by
        // almanacs), so the attribution matters: they are not Iranian, and they
        // name the month's full Moon, not the eclipse itself.
        val fm = fullMoonName(event.maximumMs)
        if (fm != null) {
            bits += "It falls on the full Moon that almanacs call the ${fm.first}."
            bitsFa += "این خسوف با ماه کاملی هم‌زمان است که در تقویم‌های سنتی نیم‌کرهٔ شمالی آن را ${fm.second} می‌نامند."
        }

        return Insight(
            nicknameEn = nickname.first,
            nicknameFa = nickname.second,
            appearanceEn = appearanceEn,
            appearanceFa = appearanceFa,
            significanceEn = bits.joinToString(" "),
            significanceFa = bitsFa.joinToString(" ")
        )
    }

    /**
     * Traditional Northern Hemisphere full-Moon name for the month containing
     * [ms] (UTC). Source: the Farmer's Almanac and timeanddate's "Traditional
     * Full Moon Names". Attributed rather than asserted as universal, because
     * these names come from Algonquian, Anglo-Saxon and Celtic seasons.
     */
    private fun fullMoonName(ms: Long): Pair<String, String>? {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ms
        return when (cal.get(java.util.Calendar.MONTH)) {
            java.util.Calendar.JANUARY -> "Wolf Moon" to "ماه گرگ"
            java.util.Calendar.FEBRUARY -> "Snow Moon" to "ماه برف"
            java.util.Calendar.MARCH -> "Worm Moon" to "ماه کرم"
            java.util.Calendar.APRIL -> "Pink Moon" to "ماه صورتی"
            java.util.Calendar.MAY -> "Flower Moon" to "ماه گل"
            java.util.Calendar.JUNE -> "Strawberry Moon" to "ماه توت‌فرنگی"
            java.util.Calendar.JULY -> "Buck Moon" to "ماه گوزن"
            java.util.Calendar.AUGUST -> "Sturgeon Moon" to "ماه تاس‌ماهی"
            java.util.Calendar.SEPTEMBER -> "Harvest Moon" to "ماه خرمن"
            java.util.Calendar.OCTOBER -> "Hunter's Moon" to "ماه شکار"
            java.util.Calendar.NOVEMBER -> "Beaver Moon" to "ماه سگ‌آبی"
            java.util.Calendar.DECEMBER -> "Cold Moon" to "ماه سرد"
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private fun formatPercentEn(fraction: Double): String {
        val pct = fraction * 100.0
        return if (pct < 1.0) "%.2f".format(Locale.US, pct) + "%" else "%.0f".format(Locale.US, pct) + "%"
    }

    private fun formatPercentFa(fraction: Double): String {
        val pct = fraction * 100.0
        return if (pct < 1.0) "%.2f".format(Locale.US, pct).toPersianDigits() + "٪"
        else "%.0f".format(Locale.US, pct).toPersianDigits() + "٪"
    }

    private fun formatMagEn(mag: Double) = "%.3f".format(Locale.US, mag)

    private fun formatMagFa(mag: Double) = "%.3f".format(Locale.US, mag).toPersianDigits()
}
