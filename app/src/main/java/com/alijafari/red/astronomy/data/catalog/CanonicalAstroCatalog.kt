package com.alijafari.red.astronomy.data.catalog

import com.alijafari.red.astronomy.astro_engine.DeepSkyCatalog as EngineDeepSkyCatalog
import com.alijafari.red.astronomy.astro_engine.DeepSkyEngine
import com.alijafari.red.astronomy.astro_engine.SatelliteCatalog
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.domain.CanonicalAstroObject
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObservationalInfo
import com.alijafari.red.astronomy.domain.ObjectType
import com.alijafari.red.astronomy.domain.PhysicalProperties
import com.alijafari.red.astronomy.domain.ScientificIdentifiers
import com.alijafari.red.astronomy.domain.StaticPosition
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Authoritative Canonical Astronomy Catalog for RED Astronomy.
 *
 * Serves as the single source of truth for astronomical object identity, metadata, localized names,
 * parent/child relationships, and legacy ID resolutions.
 */
object CanonicalAstroCatalog {

    // --- 1. SOLAR SYSTEM CANONICAL OBJECTS ---

    val SUN = CanonicalAstroObject(
        canonicalId = "sun",
        legacyIds = listOf("sun_main", "sun_sol"),
        type = ObjectType.SUN,
        nameEn = "Sun (Sol)",
        nameFa = "خورشید (مهر / هور)",
        searchAliasesEn = listOf("sol", "sun", "solar"),
        searchAliasesFa = listOf("خورشید", "مهر", "هور", "آفتاب"),
        childIds = listOf(
            "planet_mercury", "planet_venus", "planet_earth", "planet_mars",
            "planet_jupiter", "planet_saturn", "planet_uranus", "planet_neptune", "planet_pluto"
        ),
        physicalProperties = PhysicalProperties(
            magnitude = -26.74,
            diameterKm = 1392700.0,
            diameterComparedToEarth = 109.3,
            massKg = 1.989e30,
            massComparedToEarth = 333000.0,
            surfaceGravityMS2 = 274.0,
            surfaceGravityComparedToEarth = 27.94,
            distanceKm = 149597870.7,
            distanceLightYears = 0.00001581,
            temperatureK = 5778,
            rotationPeriodHours = 600.0,
            diameterDisplayEn = "1,392,700 km (109.3× Earth)",
            diameterDisplayFa = "۱,۳۹۲,۷۰۰ کیلومتر (۱۰۹.۳ برابر زمین)",
            massDisplayEn = "1.989 × 10³⁰ kg (333,000× Earth)",
            massDisplayFa = "۱.۹۸۹ × ۱۰³⁰ کیلوگرم (۳۳۳,۰۰۰ برابر زمین)",
            gravityDisplayEn = "274.0 m/s² (27.94× Earth)",
            gravityDisplayFa = "۲۷۴.۰ متر بر مجذور ثانیه (۲۷.۹۴ برابر زمین)",
            distanceDisplayEn = "149,600,000 km | 0.0000158 light-years (1.0 AU)",
            distanceDisplayFa = "۱۴۹,۶۰۰,۰۰۰ کیلومتر | ۰.۰۰۰۰۱۵۸ سال نوری (۱ واحد نجومی)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "G-type Main-Sequence Star (Yellow Dwarf)",
            categoryFa = "ستاره رشته اصلی نوع G (کوتوله زرد)",
            descriptionEn = "The central star of the Solar System, containing 99.86% of all system mass.",
            descriptionFa = "ستاره مرکزی منظومه‌شمسی که ۹۹.۸۶ درصد کل جرم منظومه را در خود جای داده است.",
            observationTipEn = "WARNING: Never view the Sun directly without proper certified solar filters!",
            observationTipFa = "هشدار: هرگز بدون فیلتر مجهز و استاندارد خورشیدی مستقیماً به خورشید نگاه نکنید!",
            verifiedFactsEn = listOf(
                "The Sun's core temperature reaches 15 million °C (27 million °F), fusing 600 million tons of hydrogen into helium every second.",
                "Photons generated in the core take 10,000 to 170,000 years to diffuse to the surface, but then travel to Earth in just 8 minutes and 20 seconds.",
                "The Sun contains approximately 99.86% of the total mass of the entire Solar System.",
                "The Sun's magnetic field undergoes a complete polarity flip every 11 years as part of its solar activity cycle.",
                "Classified as a G2V main-sequence yellow dwarf star, the Sun is currently 4.6 billion years old and about halfway through its main-sequence lifetime."
            ),
            verifiedFactsFa = listOf(
                "هسته خورشید با دمای ۱۵ میلیون درجه سانتی‌گراد، در هر ثانیه ۶۰۰ میلیون تن هیدروژن را به هلیوم تبدیل می‌کند.",
                "فوتون‌های تولیدشده در هسته خورشید بین ۱۰ هزار تا ۱۷۰ هزار سال در حرکت انتشاری هستند تا به سطح برسند، اما از سطح تا زمین تنها ۸ دقیقه و ۲۰ ثانیه در راهند.",
                "خورشید بیش از ۹۹.۸۶ درصد از کل جرم کل منظومه شمسی را به خود اختصاص داده است.",
                "میدان مغناطیسی خورشید هر ۱۱ سال یک‌بار در چرخه فعالیت خورشیدی به طور کامل معکوس می‌شود و جای قطب‌ها عوض می‌شود.",
                "خورشید یک ستاره رشته اصلی با رده طیفی G2V (کوتوله زرد) با سن تقریبی ۴.۶ میلیارد سال است و در نیمه عمر رشته اصلی خود قرار دارد."
            )
        )
    )

    val EARTH = CanonicalAstroObject(
        canonicalId = "planet_earth",
        legacyIds = listOf("earth"),
        type = ObjectType.PLANET,
        nameEn = "Earth (Terra)",
        nameFa = "زمین (کره خاک)",
        searchAliasesEn = listOf("earth", "terra", "home planet"),
        searchAliasesFa = listOf("زمین", "گیتی", "سیاره مادری"),
        parentId = "sun",
        childIds = listOf("moon", "sat_25544", "sat_20580", "sat_48274", "sat_25989", "sat_33591", "sat_36508", "sat_20624", "sat_40069"),
        physicalProperties = PhysicalProperties(
            magnitude = -3.8,
            diameterKm = 12742.0,
            diameterComparedToEarth = 1.00,
            massKg = 5.972e24,
            massComparedToEarth = 1.00,
            surfaceGravityMS2 = 9.80665,
            surfaceGravityComparedToEarth = 1.00,
            distanceKm = 0.0,
            distanceLightYears = 0.0,
            temperatureK = 288,
            rotationPeriodHours = 23.93,
            orbitalPeriodDays = 365.256,
            diameterDisplayEn = "12,742 km (1.00× Earth)",
            diameterDisplayFa = "۱۲,۷۴۲ کیلومتر (۱.۰۰ برابر زمین)",
            massDisplayEn = "5.972 × 10²⁴ kg (1.00× Earth)",
            massDisplayFa = "۵.۹۷۲ × ۱۰²⁴ کیلوگرم (۱.۰۰ برابر زمین)",
            gravityDisplayEn = "9.81 m/s² (1.00× Earth)",
            gravityDisplayFa = "۹.۸۱ متر بر مجذور ثانیه (۱.۰۰ برابر زمین)",
            distanceDisplayEn = "0 km (Right beneath you)",
            distanceDisplayFa = "۰ کیلومتر (زیر پای شما)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Terrestrial Planet (Home)",
            categoryFa = "سیاره خاکی (خانه ما)",
            descriptionEn = "Our home planet, third planet from the Sun and starting origin for celestial observations.",
            descriptionFa = "سیاره مادری ما، سومین سیاره منظومه شمسی و مبدأ مرجع رصدها و سفرهای فضایی.",
            observationTipEn = "Origin reference for all astronomical calculations.",
            observationTipFa = "مبدأ مرجع تمامی محاسبات نجومی و سفرهای فضایی.",
            verifiedFactsEn = listOf(
                "Earth is the only planetary body known to maintain stable liquid water oceans and harbor active biological life.",
                "Earth's molten iron-nickel outer core generates a powerful magnetosphere that deflects lethal solar wind and cosmic radiation.",
                "Earth's atmosphere comprises 78.08% nitrogen, 20.95% oxygen, 0.93% argon, and trace gases, balancing respiration and temperature.",
                "Earth is the only terrestrial planet in the Solar System with active plate tectonics that continuously recycle crustal carbon.",
                "Earth orbits the Sun at an average velocity of 29.78 km/s (107,200 km/h), completing one revolution every 365.256 days."
            ),
            verifiedFactsFa = listOf(
                "زمین تنها جهان شناخته‌شده در کیهان است که دارای اقیانوس‌های آب مایع پایدار و حیات زیستی پویا است.",
                "هسته بیرونی مذاب آهن و نیکل زمین میدان مغناطیسی نیرومندی ایجاد می‌کند که سیاره را در برابر بادهای خورشیدی و پرتوهای کیهانی محافظت می‌نماید.",
                "جو زمین حاوی ۷۸٪ نیتروژن و ۲۱٪ اکسیژن است که تعادل دمایی و تنفسی مناسب برای حیات را فراهم می‌سازد.",
                "زمین تنها سیاره خاکی منظومه شمسی است که دارای صفحات تکتونیکی فعال برای بازیافت کربن پوسته است.",
                "زمین با سرعت میانگین ۲۹.۷۸ کیلومتر بر ثانیه (۱۰۷,۲۰۰ کیلومتر بر ساعت) به دور خورشید گردش می‌کند."
            )
        )
    )

    val MOON = CanonicalAstroObject(
        canonicalId = "moon",
        legacyIds = listOf("moon_main", "moon_luna"),
        type = ObjectType.MOON,
        nameEn = "Moon (Luna)",
        nameFa = "ماه (لونا / باختر)",
        searchAliasesEn = listOf("moon", "luna", "selene"),
        searchAliasesFa = listOf("ماه", "لونا", "قمر زمین"),
        parentId = "planet_earth",
        physicalProperties = PhysicalProperties(
            magnitude = -12.74,
            diameterKm = 3474.8,
            diameterComparedToEarth = 0.2727,
            massKg = 7.342e22,
            massComparedToEarth = 0.0123,
            surfaceGravityMS2 = 1.622,
            surfaceGravityComparedToEarth = 0.1654,
            distanceKm = 384400.0,
            distanceLightYears = 0.0000000406,
            temperatureK = 250,
            rotationPeriodHours = 655.7,
            orbitalPeriodDays = 27.32,
            diameterDisplayEn = "3,475 km (0.273× Earth)",
            diameterDisplayFa = "۳,۴۷۵ کیلومتر (۰.۲۷۳ برابر زمین)",
            massDisplayEn = "7.342 × 10²² kg (0.0123× Earth)",
            massDisplayFa = "۷.۳۴۲ × ۱۰²² کیلوگرم (۰.۰۱۲۳ برابر زمین)",
            gravityDisplayEn = "1.62 m/s² (0.165× Earth)",
            gravityDisplayFa = "۱.۶۲ متر بر مجذور ثانیه (۰.۱۶۵ برابر زمین)",
            distanceDisplayEn = "384,400 km | 0.00000004 light-years (dynamic elliptical orbit)",
            distanceDisplayFa = "۳۸۴,۴۰۰ کیلومتر (متغیر در مدار بیضوی)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Natural Satellite of Earth",
            categoryFa = "تنها قمر طبیعی کره زمین",
            descriptionEn = "Earth's sole natural satellite, influencing tides and serving as a celestial clock.",
            descriptionFa = "تنها قمر طبیعی زمین، عامل ایجاد جزر و مد و درخشان‌ترین جرم شبانه آسمان.",
            observationTipEn = "Best viewed around quarter phases when shadows reveal crater depth.",
            observationTipFa = "بهترین رصد در فاز تربیع اول و دوم است که سایه‌های دهانه‌ها به زیبایی مشخص می‌شوند.",
            verifiedFactsEn = listOf(
                "Laser retroreflectors left by Apollo missions confirm the Moon is receding from Earth at a rate of 3.8 cm per year due to tidal friction.",
                "Tidal locking forces the Moon's rotation period to match its orbital period (27.32 days), keeping the same hemisphere permanently facing Earth.",
                "The complete lunar synodic month (New Moon to New Moon phase cycle) lasts exactly 29.53059 days.",
                "Surface temperatures swing dramatically from +120 °C at lunar noon to -130 °C at night, plummeting to -246 °C in permanently shadowed polar craters.",
                "Surface gravity on the Moon is 1.622 m/s² (about 1/6th of Earth's), driving significant ocean tides on Earth."
            ),
            verifiedFactsFa = listOf(
                "ماه با سرعت ۳.۸ سانتی‌متر در سال به دلیل انتقال گشتاور جزر و مدی در حال دور شدن تدریجی از زمین است.",
                "به دلیل قفل همگام (جزر و مدی)، دوره چرخش ماه به دور خود با دوره گردش آن به دور زمین یکسان (۲۷.۳۲ روز) است و همواره یک سمت آن دیده می‌شود.",
                "چرخه کامل فازهای ماه (از ماه نو تا ماه نو بعدی) یا ماه هلالی برابر ۲۹.۵۳ روز به طول می‌انجامد.",
                "دمای سطح ماه نوسان شدیدی دارد: از ۱۲۰+ درجه سانتی‌گراد در نیمروز تا ۱۳۰- درجه در شب و ۲۴۶- درجه در گودال‌های تاریک قطبی.",
                "نیروی گرانش ماه (۱.۶۲۲ متر بر مجذور ثانیه) حدود یک‌ششم گرانش زمین است و عامل اصلی ایجاد جزر و مد در اقیانوس‌ها می‌باشد."
            )
        )
    )

    val MERCURY = CanonicalAstroObject(
        canonicalId = "planet_mercury",
        type = ObjectType.PLANET,
        nameEn = "Mercury",
        nameFa = "عطارد (تیر)",
        searchAliasesEn = listOf("mercury", "hermes"),
        searchAliasesFa = listOf("عطارد", "تیر"),
        parentId = "sun",
        physicalProperties = PhysicalProperties(
            magnitude = -0.4,
            diameterKm = 4879.4,
            diameterComparedToEarth = 0.3829,
            massKg = 3.301e23,
            massComparedToEarth = 0.0553,
            surfaceGravityMS2 = 3.70,
            surfaceGravityComparedToEarth = 0.377,
            distanceKm = null, // Dynamically computed via orbital ephemeris
            distanceLightYears = null,
            temperatureK = 440,
            rotationPeriodHours = 1407.6,
            orbitalPeriodDays = 87.97,
            diameterDisplayEn = "4,879 km (0.383× Earth)",
            diameterDisplayFa = "۴,۸۷۹ کیلومتر (۰.۳۸۳ برابر زمین)",
            massDisplayEn = "3.301 × 10²³ kg (0.055× Earth)",
            massDisplayFa = "۳.۳۰۱ × ۱۰²³ کیلوگرم (۰.۰۵۵ برابر زمین)",
            gravityDisplayEn = "3.70 m/s² (0.377× Earth)",
            gravityDisplayFa = "۳.۷۰ متر بر مجذور ثانیه (۰.۳۷۷ برابر زمین)",
            distanceDisplayEn = "77M to 222M km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۷۷ تا ۲۲۲ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Terrestrial Planet",
            categoryFa = "سیاره خاکی (سنگی)",
            descriptionEn = "Innermost planet of the Solar System, heavily cratered and experiencing extreme temperature swings.",
            descriptionFa = "نزدیک‌ترین سیاره به خورشید با نوسانات دمایی شدید و سطح پر از دهانه‌های برخوردی.",
            observationTipEn = "View near maximum elongation low on twilight horizons.",
            observationTipFa = "تنها در زمان کشیدگی مداری در سپیده‌دم یا غروب آفتاب پایین افق قابل رصد است.",
            verifiedFactsEn = listOf(
                "Mercury is the fastest planet in the Solar System, orbiting the Sun at an average speed of 47.36 km/s and completing a year in 88 Earth days.",
                "Due to a 3:2 spin-orbit resonance, Mercury rotates three times on its axis for every two orbits around the Sun, making one solar day 176 Earth days.",
                "Mercury has the most extreme temperature swings in the Solar System, ranging from 430 °C (800 °F) by day to -180 °C (-290 °F) at night.",
                "Mercury's massive iron-rich metallic core constitutes approximately 85% of its entire planetary radius.",
                "Despite extreme daytime heat, permanently shadowed craters at Mercury's poles shelter significant deposits of pure water ice."
            ),
            verifiedFactsFa = listOf(
                "عطارد سریع‌ترین سیاره منظومه شمسی است و یک سال آن تنها ۸۸ روز زمین طول می‌کشد.",
                "اختلاف دمای روز و شب در عطارد بیشترین میزان در منظومه شمسی است (از ۴۳۰ درجه بالای صفر تا ۱۸۰- زیر صفر).",
                "عطارد با وجود نزدیکی به خورشید، گرم‌ترین سیاره نیست؛ زیرا جوی برای به دام انداختن گرما ندارد.",
                "هسته فلزی آهن در عطارد حدود ۸۵ درصد از شعاع کل این سیاره را تشکیل می‌دهد.",
                "گودال‌های قطبی عطارد به دلیل زاویه میل صفر درجه محوری، هرگز نور خورشید را نمی‌بینند و دارای یخ آب هستند."
            )
        )
    )

    val VENUS = CanonicalAstroObject(
        canonicalId = "planet_venus",
        type = ObjectType.PLANET,
        nameEn = "Venus",
        nameFa = "زهره (ناهید)",
        searchAliasesEn = listOf("venus", "morning star", "evening star", "aphrodite"),
        searchAliasesFa = listOf("زهره", "ناهید", "ستاره صبحگاهی", "ستاره شامگاهی"),
        parentId = "sun",
        physicalProperties = PhysicalProperties(
            magnitude = -4.4,
            diameterKm = 12103.6,
            diameterComparedToEarth = 0.9499,
            massKg = 4.867e24,
            massComparedToEarth = 0.8150,
            surfaceGravityMS2 = 8.87,
            surfaceGravityComparedToEarth = 0.9045,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 737,
            rotationPeriodHours = -5832.5,
            orbitalPeriodDays = 224.7,
            diameterDisplayEn = "12,104 km (0.950× Earth)",
            diameterDisplayFa = "۱۲,۱۰۴ کیلومتر (۰.۹۵۰ برابر زمین)",
            massDisplayEn = "4.867 × 10²⁴ kg (0.815× Earth)",
            massDisplayFa = "۴.۸۶۷ × ۱۰²⁴ کیلوگرم (۰.۸۱۵ برابر زمین)",
            gravityDisplayEn = "8.87 m/s² (0.904× Earth)",
            gravityDisplayFa = "۸.۸۷ متر بر مجذور ثانیه (۰.۹۰۴ برابر زمین)",
            distanceDisplayEn = "38M to 261M km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۳۸ تا ۲۶۱ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Terrestrial Planet (Runaway Greenhouse)",
            categoryFa = "سیاره خاکی (گلخانه‌ای شدید)",
            descriptionEn = "Brightest planet in our night sky, shrouded in dense sulfuric acid clouds and runaway greenhouse climate.",
            descriptionFa = "درخشان‌ترین سیاره آسمان شب با جو فوق‌العاده متراکم دی‌اکسید کربن و ابرهای اسید سولفوریک.",
            observationTipEn = "Exhibits crescent and gibbous phases like the Moon in small telescopes.",
            observationTipFa = "حتی در تلسکوپ‌های کوچک فازهای هلال و تثلیث آن ماننده ماه دیده می‌شود.",
            verifiedFactsEn = listOf(
                "Venus is the hottest planet in the Solar System with a runaway greenhouse surface temperature of 465 °C (870 °F), hot enough to melt lead.",
                "Venus rotates retrograde (clockwise) on its axis, meaning the Sun rises in the west and sets in the east.",
                "A single sidereal rotation of Venus (243 Earth days) is longer than its orbital year around the Sun (224.7 Earth days).",
                "Surface atmospheric pressure on Venus is 92 bars (9.2 MPa), equivalent to the crushing pressure 900 meters deep in Earth's oceans.",
                "Venus is perpetually veiled by reflective clouds of concentrated sulfuric acid that reflect over 75% of incoming sunlight."
            ),
            verifiedFactsFa = listOf(
                "زهره داغ‌ترین سیاره منظومه شمسی است که دمای سطح آن به دلیل اثر گلخانه‌ای شدید به ۴۶۵ درجه سانتی‌گراد می‌رسد.",
                "جهت چرخش زهره به دور خود معکوس (ساعت‌گرد) است؛ بنابراین خورشید در زهره از غرب طلوع و در شرق غروب می‌کند.",
                "یک روز در زهره (۲۴۳ روز زمین) طولانی‌تر از یک سال آن (۲۲۵ روز زمین) به طول می‌انجامد.",
                "فشار جو در سطح زهره ۹۲ برابر فشار جو زمین است (معادل فشار آب در عمق ۹۰۰ متری اقیانوس).",
                "ابرهای غلیظ زهره از قطرات اسید سولفوریک تشکیل شده‌اند و بیش از ۷۵ درصد نور خورشید را بازمی‌تابانند."
            )
        )
    )

    val MARS = CanonicalAstroObject(
        canonicalId = "planet_mars",
        type = ObjectType.PLANET,
        nameEn = "Mars",
        nameFa = "مریخ (بهرام)",
        searchAliasesEn = listOf("mars", "red planet", "ares"),
        searchAliasesFa = listOf("مریخ", "بهرام", "سیاره سرخ"),
        parentId = "sun",
        childIds = listOf("mars_phobos", "mars_deimos"),
        physicalProperties = PhysicalProperties(
            magnitude = -1.5,
            diameterKm = 6779.0,
            diameterComparedToEarth = 0.5320,
            massKg = 6.417e23,
            massComparedToEarth = 0.1074,
            surfaceGravityMS2 = 3.72,
            surfaceGravityComparedToEarth = 0.3794,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 210,
            rotationPeriodHours = 24.62,
            orbitalPeriodDays = 686.98,
            diameterDisplayEn = "6,779 km (0.532× Earth)",
            diameterDisplayFa = "۶,۷۷۹ کیلومتر (۰.۵۳۲ برابر زمین)",
            massDisplayEn = "6.417 × 10²³ kg (0.107× Earth)",
            massDisplayFa = "۶.۴۱۷ × ۱۰²³ کیلوگرم (۰.۱۰۷ برابر زمین)",
            gravityDisplayEn = "3.72 m/s² (0.379× Earth)",
            gravityDisplayFa = "۳.۷۲ متر بر مجذور ثانیه (۰.۳۷۹ برابر زمین)",
            distanceDisplayEn = "55M to 401M km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۵۵ تا ۴۰۱ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Terrestrial Planet (Red Planet)",
            categoryFa = "سیاره خاکی (سیاره سرخ)",
            descriptionEn = "The Red Planet, hosting Olympus Mons and Valles Marineris with polar ice caps.",
            descriptionFa = "سیاره سرخ با آتشفشان عظیم المپوس و دره مارینر، پذیرای مریخ‌نوردهای متعدد.",
            observationTipEn = "Look for crisp red hue; polar ice caps visible during favorable opposition.",
            observationTipFa = "رنگ سرخ متمایز آن هویداست؛ در مقابله‌های مداری کلاهک‌های یخی قطبی با تلسکوپ دیده می‌شوند.",
            verifiedFactsEn = listOf(
                "Olympus Mons on Mars is the largest volcano in the Solar System, rising 21.9 km (72,000 ft) high—nearly three times the height of Mount Everest.",
                "Valles Marineris is a colossal canyon system spanning over 4,000 km across Mars, four times deeper and ten times longer than the Grand Canyon.",
                "The characteristic reddish-orange hue of Mars is caused by abundant iron(III) oxide (ferric oxide / rust) in its surface regolith.",
                "Mars possesses two small, irregular moons, Phobos and Deimos, which are thought to be captured carbonaceous asteroids.",
                "A Martian solar day, known as a sol, lasts 24 hours, 39 minutes, and 35 seconds, remarkably close to an Earth day."
            ),
            verifiedFactsFa = listOf(
                "کوه المپوس در مریخ بزرگ‌ترین آتشفشان منظومه شمسی است که ارتفاعی ۳ برابر کوه اورست (۲۱.۹ کیلومتر) دارد.",
                "دره والز مارینریس در مریخ دره‌ای غول‌پیکر به طول ۴,۰۰۰ کیلومتر است که کل پهنای ایالات متحده را می‌پوشاند.",
                "رنگ سرخ مریخ ناشی از اکسید آهن (زنگ‌زدگی) موجود در خاک و غبار سطح آن است.",
                "مریخ دارای دو قمر کوچک و ناهموار به نام‌های فوبوس و دیموس است که احتمالاً سیارک‌های به دام افتاده هستند.",
                "یک روز در مریخ (سول) بسیار نزدیک به روز زمین است و ۲۴ ساعت و ۳۹ دقیقه طول می‌کشد."
            )
        )
    )

    val JUPITER = CanonicalAstroObject(
        canonicalId = "planet_jupiter",
        type = ObjectType.PLANET,
        nameEn = "Jupiter",
        nameFa = "مشتری (برجیس / هرمز)",
        searchAliasesEn = listOf("jupiter", "jove", "zeus"),
        searchAliasesFa = listOf("مشتری", "برجیس", "هرمز"),
        parentId = "sun",
        childIds = listOf("jup_io", "jup_europa", "jup_ganymede", "jup_callisto", "jup_elara"),
        physicalProperties = PhysicalProperties(
            magnitude = -2.7,
            diameterKm = 139822.0,
            diameterComparedToEarth = 10.973,
            massKg = 1.898e27,
            massComparedToEarth = 317.83,
            surfaceGravityMS2 = 24.79,
            surfaceGravityComparedToEarth = 2.528,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 165,
            rotationPeriodHours = 9.93,
            orbitalPeriodDays = 4332.59,
            diameterDisplayEn = "139,822 km (10.97× Earth)",
            diameterDisplayFa = "۱۳۹,۸۲۲ کیلومتر (۱۰.۹۷ برابر زمین)",
            massDisplayEn = "1.898 × 10²⁷ kg (317.8× Earth)",
            massDisplayFa = "۱.۸۹۸ × ۱۰²⁷ کیلوگرم (۳۱۷.۸ برابر زمین)",
            gravityDisplayEn = "24.79 m/s² (2.528× Earth)",
            gravityDisplayFa = "۲۴.۷۹ متر بر مجذور ثانیه (۲.۵۲۸ برابر زمین)",
            distanceDisplayEn = "588M to 968M km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۵۸۸ تا ۹۶۸ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Gas Giant Planet",
            categoryFa = "سیاره غول گازی",
            descriptionEn = "Largest planet in the Solar System, featuring the Great Red Spot and 95 moons.",
            descriptionFa = "بزرگ‌ترین سیاره منظومه شمسی دارای لکه سرخ بزرگ و حداقل ۹۵ قمر تاییدشده.",
            observationTipEn = "Galilean moons (Io, Europa, Ganymede, Callisto) easily visible in binoculars.",
            observationTipFa = "چهار قمر گالیله‌ای آن (ایو، اروپا، گانیمد، کالیستو) حتی با دوربین دوچشمی کوچک دیده می‌شوند.",
            verifiedFactsEn = listOf(
                "Jupiter is more than twice as massive as all other Solar System planets combined, containing 317.8 Earth masses.",
                "The Great Red Spot is a persistent anticyclonic storm larger than the diameter of Earth, continuously observed for over 350 years.",
                "Jupiter has the shortest day of all planets, completing one full axial rotation in just 9 hours and 55 minutes.",
                "Jupiter possesses a powerful magnetic field 20,000 times stronger than Earth's, driving intense auroral rings and radiation belts.",
                "Jupiter hosts 95 recognized moons, including the four massive Galilean moons: volcanic Io, oceanic Europa, giant Ganymede, and cratered Callisto."
            ),
            verifiedFactsFa = listOf(
                "لکه سرخ بزرگ مشتری طوفانی عظیم و کهن است که ابعادی بزرگ‌تر از کل کره زمین دارد.",
                "مشتری دارای قوی‌ترین میدان مغناطیسی در میان سیارات است که ۲۰,۰۰۰ برابر قوی‌تر از میدان مغناطیسی زمین می‌باشد.",
                "مشتری سریع‌ترین سرعت دوران به دور خود را دارد و یک شبانه‌روز آن تنها ۹ ساعت و ۵۵ دقیقه طول می‌کشد.",
                "چهار قمر بزرگ مشتری (گالیله‌ای) شامل گانی‌مید (بزرگ‌ترین قمر کیهان)، اروپا (اقیانوس زیرسطحی)، یو (آتشفشانی) و کالیستو هستند.",
                "مشتری به عنوان سپر گرانشی منظومه شمسی عمل کرده و بسیاری از دنباله‌دارها و سیارک‌های سرگردان را به سوی خود می‌کشد."
            )
        )
    )

    val SATURN = CanonicalAstroObject(
        canonicalId = "planet_saturn",
        type = ObjectType.PLANET,
        nameEn = "Saturn",
        nameFa = "زحل (کیوان)",
        searchAliasesEn = listOf("saturn", "cronus"),
        searchAliasesFa = listOf("زحل", "کیوان"),
        parentId = "sun",
        childIds = listOf("sat_titan", "sat_enceladus"),
        physicalProperties = PhysicalProperties(
            magnitude = 0.5,
            diameterKm = 116460.0,
            diameterComparedToEarth = 9.140,
            massKg = 5.683e26,
            massComparedToEarth = 95.16,
            surfaceGravityMS2 = 10.44,
            surfaceGravityComparedToEarth = 1.065,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 134,
            rotationPeriodHours = 10.7,
            orbitalPeriodDays = 10759.22,
            diameterDisplayEn = "116,460 km (9.14× Earth)",
            diameterDisplayFa = "۱۱۶,۴۶۰ کیلومتر (۹.۱۴ برابر زمین)",
            massDisplayEn = "5.683 × 10²⁶ kg (95.2× Earth)",
            massDisplayFa = "۵.۶۸۳ × ۱۰²۶ کیلوگرم (۹۵.۲ برابر زمین)",
            gravityDisplayEn = "10.44 m/s² (1.065× Earth)",
            gravityDisplayFa = "۱۰.۴۴ متر بر مجذور ثانیه (۱.۰۶۵ برابر زمین)",
            distanceDisplayEn = "1.2B to 1.66B km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۱.۲ تا ۱.۶۶ میلیارد کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Gas Giant Planet (Ringed Planet)",
            categoryFa = "سیاره غول گازی (ارباب حلقه‌ها)",
            descriptionEn = "Famous ringed gas giant planet made of ice particles and rock fragments.",
            descriptionFa = "ارباب حلقه‌های منظومه شمسی با حلقه‌های خیره‌کننده یخ و غبار و قمر شگفت‌انگیز تیتان.",
            observationTipEn = "Rings easily resolved with small telescope; Titan visible as a star-like moon.",
            observationTipFa = "حلقه‌های آن با یک تلسکوپ آماتوری تفکیک شده و قمر تیتان مانند ستاره‌ای نزدیک آن می‌درخشد.",
            verifiedFactsEn = listOf(
                "Saturn's extensive ring system spans up to 282,000 km in width but is remarkably thin, averaging only about 10 to 30 meters in thickness.",
                "Saturn has the lowest mean density of any planet in the Solar System (0.687 g/cm³)—it is less dense than liquid water.",
                "Saturn's giant moon Titan is the only moon with a dense atmosphere and stable surface lakes and seas of liquid methane and ethane.",
                "A persistent hexagonal jet stream cloud pattern spanning 30,000 km across rotates around Saturn's north pole.",
                "Saturn is the planet with the most known moons in the Solar System, with 146 officially recognized moons."
            ),
            verifiedFactsFa = listOf(
                "حلقه‌های تماشایی زحل از میلیاردها قطعه یخ، غبار و سنگ با ضخامتی تنها حدود ۱۰ تا ۳۰ متر تشکیل شده‌اند.",
                "چگالی زحل از آب کمتر است (۰.۶۸۷ گرم بر سانتی‌متر مکعب)؛ اگر اقیانوسی به اندازه کافی بزرگ وجود داشت، زحل روی آب شناور می‌ماند!",
                "قمر تایتان زحل تنها قمر منظومه شمسی با جوی غلیظ و دریاچه‌های مایع متان و اتان است.",
                "در قطب شمال زحل، یک طوفان شش‌ضلعی (هگزاگون) شگفت‌انگیز و مداوم به عرض ۳۰,۰۰۰ کیلومتر وجود دارد.",
                "زحل تا کنون دارای ۱۴۶ قمر تاییدشده رسمی است که بیشترین تعداد در منظومه شمسی محسوب می‌شود."
            )
        )
    )

    val URANUS = CanonicalAstroObject(
        canonicalId = "planet_uranus",
        type = ObjectType.PLANET,
        nameEn = "Uranus",
        nameFa = "اورانوس",
        searchAliasesEn = listOf("uranus"),
        searchAliasesFa = listOf("اورانوس"),
        parentId = "sun",
        physicalProperties = PhysicalProperties(
            magnitude = 5.7,
            diameterKm = 50724.0,
            diameterComparedToEarth = 3.981,
            massKg = 8.681e25,
            massComparedToEarth = 14.536,
            surfaceGravityMS2 = 8.69,
            surfaceGravityComparedToEarth = 0.886,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 76,
            rotationPeriodHours = -17.24,
            orbitalPeriodDays = 30685.4,
            diameterDisplayEn = "50,724 km (3.98× Earth)",
            diameterDisplayFa = "۵۰,۷۲۴ کیلومتر (۳.۹۸ برابر زمین)",
            massDisplayEn = "8.681 × 10²⁵ kg (14.54× Earth)",
            massDisplayFa = "۸.۶۸۱ × ۱۰²۵ کیلوگرم (۱۴.۵۴ برابر زمین)",
            gravityDisplayEn = "8.69 m/s² (0.886× Earth)",
            gravityDisplayFa = "۸.۶۹ متر بر مجذور ثانیه (۰.۸۸۶ برابر زمین)",
            distanceDisplayEn = "2.6B to 3.15B km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۲.۶ تا ۳.۱۵ میلیارد کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Ice Giant Planet",
            categoryFa = "سیاره غول یخی",
            descriptionEn = "Ice giant planet orbiting sideways on an extreme 98-degree axial tilt.",
            descriptionFa = "غول یخی با انحراف محوری ۹۸ درجه که عملاً به پهلو در مدار خود می‌چرخد.",
            observationTipEn = "Appears as a faint blue-green dot through small telescopes.",
            observationTipFa = "با تلسکوپ آماتوری مانند دیسک کوچکی به رنگ آبی-فیروزه‌ای دیده می‌شود.",
            verifiedFactsEn = listOf(
                "Uranus has an extreme axial tilt of 97.77 degrees, effectively rotating on its side as it orbits the Sun.",
                "Uranus possesses the coldest planetary atmosphere in the Solar System, with minimum temperatures dropping to -224 °C (49 K).",
                "The cyan/aquamarine color of Uranus is caused by atmospheric methane gas absorbing red light in its upper atmosphere.",
                "Because of its sideways rotation, each pole of Uranus experiences 42 years of continuous sunlight followed by 42 years of darkness.",
                "Uranus features a system of 13 faint, narrow planetary rings composed of dark boulders and dust grains."
            ),
            verifiedFactsFa = listOf(
                "اورانوس انحراف محوری عجیب ۹۸ درجه‌ای دارد و عملاً روی مدار خود به دور خورشید به پهلو می‌چرخد.",
                "اورانوس سردترین جو را در میان سیارات منظومه شمسی دارد که دمای آن به ۲۲۴- درجه سانتی‌گراد (۴۹ کلوین) می‌رسد.",
                "رنگ فیروزه‌ای-آبی اورانوس به دلیل وجود گاز متان در جو بالای آن است که نور سرخ را جذب می‌کند.",
                "اورانوس دارای ۱۳ حلقه باریک و تاریک است که پس از حلقه‌های زحل کشف شدند.",
                "به دلیل چرخش به پهلو، هر قطب اورانوس ۴۲ سال مداوم روشنایی خورشید و ۴۲ سال تاریکی مطلق پیاپی را تجربه می‌کند."
            )
        )
    )

    val NEPTUNE = CanonicalAstroObject(
        canonicalId = "planet_neptune",
        type = ObjectType.PLANET,
        nameEn = "Neptune",
        nameFa = "نپتون",
        searchAliasesEn = listOf("neptune", "poseidon"),
        searchAliasesFa = listOf("نپتون"),
        parentId = "sun",
        childIds = listOf("nep_triton"),
        physicalProperties = PhysicalProperties(
            magnitude = 7.8,
            diameterKm = 49244.0,
            diameterComparedToEarth = 3.865,
            massKg = 1.024e26,
            massComparedToEarth = 17.147,
            surfaceGravityMS2 = 11.15,
            surfaceGravityComparedToEarth = 1.137,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 72,
            rotationPeriodHours = 16.11,
            orbitalPeriodDays = 60189.0,
            diameterDisplayEn = "49,244 km (3.86× Earth)",
            diameterDisplayFa = "۴۹,۲۴۴ کیلومتر (۳.۸۶ برابر زمین)",
            massDisplayEn = "1.024 × 10²⁶ kg (17.15× Earth)",
            massDisplayFa = "۱.۰۲۴ × ۱۰²۶ کیلوگرم (۱۷.۱۵ برابر زمین)",
            gravityDisplayEn = "11.15 m/s² (1.137× Earth)",
            gravityDisplayFa = "۱۱.۱۵ متر بر مجذور ثانیه (۱.۱۳۷ برابر زمین)",
            distanceDisplayEn = "4.3B to 4.7B km (Dynamic real-time ephemeris)",
            distanceDisplayFa = "۴.۳ تا ۴.۷ میلیارد کیلومتر (محاسبه دینامیکی لحظه‌ای)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Ice Giant Planet",
            categoryFa = "سیاره غول یخی",
            descriptionEn = "Outermost major planet in our solar system with supersonic winds exceeding 2,000 km/h.",
            descriptionFa = "دورترین سیاره اصلی منظومه شمسی دارای شدیدترین بادهای مداری با سرعت ۲۰۰۰ کیلومتر در ساعت.",
            observationTipEn = "Requires dark sky and telescope to distinguish faint deep-blue disk.",
            observationTipFa = "برای دیدن قرص آبی‌رنگ عمیق آن نیاز به تلسکوپ و آسمان کاملاً تاریک دارید.",
            verifiedFactsEn = listOf(
                "Neptune harbors the fastest recorded winds in the Solar System, reaching supersonic speeds over 2,100 km/h (1,300 mph).",
                "Neptune was the first planet discovered through mathematical prediction rather than empirical observation, based on orbital perturbations of Uranus.",
                "Neptune's largest moon, Triton, is the only large moon in the Solar System with a retrograde orbit, indicating it was a captured Kuiper Belt object.",
                "Neptune completes one orbit around the Sun every 164.8 Earth years, having completed only one full orbit since its discovery in 1846.",
                "Despite being the most distant major planet from the Sun, Neptune emits 2.6 times more internal thermal energy than it absorbs from solar radiation."
            ),
            verifiedFactsFa = listOf(
                "نپتون دارای شدیدترین بادهای منظومه شمسی است که سرعت آن‌ها به بیش از ۲,۱۰۰ کیلومتر بر ساعت می‌رسد.",
                "نپتون نخستین سیاره‌ای بود که وجود آن ابتدا از طریق محاسبات ریاضی گرانشی پیش‌بینی و سپس با تلسکوپ کشف شد.",
                "قمر بزرگ نپتون، تریتون، تنها قمر بزرگ منظومه شمسی است که مداری معکوس (مخالف جهت چرخش سیاره) دارد.",
                "فاصله نپتون از خورشید به قدری زیاد است که یک سال در نپتون معادل ۱۶۴.۸ سال زمین به طول می‌انجامد.",
                "با وجود فاصله دور از خورشید، نپتون ۲.۶ برابر انرژی گرمایی بیشتری نسبت به گرمای دریافتی از خورشید به فضا تابش می‌کند."
            )
        )
    )

    val PLUTO = CanonicalAstroObject(
        canonicalId = "planet_pluto",
        type = ObjectType.DWARF_PLANET,
        nameEn = "Pluto (Dwarf Planet)",
        nameFa = "پلوتو (سیاره کوتوله)",
        searchAliasesEn = listOf("pluto", "dwarf planet"),
        searchAliasesFa = listOf("پلوتو", "سیاره کوتوله"),
        parentId = "sun",
        physicalProperties = PhysicalProperties(
            magnitude = 14.2,
            diameterKm = 2376.6,
            diameterComparedToEarth = 0.1865,
            massKg = 1.303e22,
            massComparedToEarth = 0.00218,
            surfaceGravityMS2 = 0.62,
            surfaceGravityComparedToEarth = 0.0632,
            distanceKm = null,
            distanceLightYears = null,
            temperatureK = 44,
            rotationPeriodHours = -153.3,
            orbitalPeriodDays = 90560.0,
            diameterDisplayEn = "2,377 km (0.186× Earth)",
            diameterDisplayFa = "۲,۳۷۷ کیلومتر (۰.۱۸۶ برابر زمین)",
            massDisplayEn = "1.303 × 10²² kg (0.0022× Earth)",
            massDisplayFa = "۱.۳۰۳ × ۱۰²۲ کیلوگرم (۰.۰۰۲۲ برابر زمین)",
            gravityDisplayEn = "0.62 m/s² (0.063× Earth)",
            gravityDisplayFa = "۰.۶۲ متر بر مجذور ثانیه (۰.۰۶۳ برابر زمین)",
            distanceDisplayEn = "4.4B to 7.5B km (Highly eccentric orbit)",
            distanceDisplayFa = "۴.۴ تا ۷.۵ میلیارد کیلومتر (مدار به شدت بیضوی)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Dwarf Planet (Kuiper Belt World)",
            categoryFa = "سیاره کوتوله (جهان کمربند کایپر)",
            descriptionEn = "Famous dwarf planet in the Kuiper Belt with heart-shaped nitrogen ice glacier Tombaugh Regio.",
            descriptionFa = "سیاره کوتوله مشهور کمربند کایپر با یخچال نیتروژنی قلبی‌شکل تامبا.",
            observationTipEn = "Extremely faint; visible only in large amateur or professional telescopes.",
            observationTipFa = "بسیار کم‌نور؛ تنها با تلسکوپ‌های بزرگ رصدخانه‌ای یا ابزارهای حرفه‌ای قابل ردیابی است.",
            verifiedFactsEn = listOf(
                "Pluto features a massive heart-shaped glacier named Tombaugh Regio, consisting primarily of bright frozen nitrogen, carbon monoxide, and methane.",
                "Pluto and its largest moon, Charon, form a mutually tidally locked binary system whose center of mass (barycenter) lies outside Pluto.",
                "Pluto's orbit is highly eccentric and inclined at 17 degrees; for 20 years of its 248-year orbit, it is closer to the Sun than Neptune.",
                "In 2006, the International Astronomical Union (IAU) redefined the term 'planet', reclassifying Pluto as a dwarf planet.",
                "Pluto has a tenuous nitrogen atmosphere that expands when closer to perihelion and freezes out onto the surface when farther away."
            ),
            verifiedFactsFa = listOf(
                "پلوتو دارای یک منطقه یخچالی نیتروژنی قلبی‌شکل معروف به نام «تومبا رجیو» است.",
                "قمر بزرگ پلوتو، شارون، ابعادی نصف پلوتو دارد و این دو جرم یک سامانه دوتایی قفل‌شده را تشکیل می‌دهند.",
                "مدار پلوتو کاملاً بیضی شکل است و در بخشی از مدار خود به خورشید نزدیک‌تر از نپتون می‌شود.",
                "در سال ۲۰۰۶، اتحادیه بین‌المللی اخترشناسی (IAU) تعریف سیاره را تغییر داد و پلوتو به عنوان سیاره کوتوله طبقه‌بندی شد.",
                "جو رقیق پلوتو هنگام نزدیک شدن به خورشید تبخیر شده و هنگام دور شدن منجمد و بر سطح می‌بارد."
            )
        )
    )

    // --- 2. JOVIAN MOONS (RESOLVED DUPLICATES) ---

    val IO = CanonicalAstroObject(
        canonicalId = "jup_io",
        legacyIds = listOf("jupiter_io", "io", "galilean_moon_io"),
        type = ObjectType.MOON,
        nameEn = "Io (Galilean Moon)",
        nameFa = "ایو (قمر مشتری)",
        parentId = "planet_jupiter",
        physicalProperties = PhysicalProperties(
            magnitude = 5.0,
            diameterKm = 3643.2,
            diameterComparedToEarth = 0.2859,
            massKg = 8.932e22,
            massComparedToEarth = 0.01495,
            surfaceGravityMS2 = 1.796,
            surfaceGravityComparedToEarth = 0.1831,
            distanceKm = 421700.0,
            distanceLightYears = null,
            temperatureK = 110,
            diameterDisplayEn = "3,643 km (0.286× Earth / Volcanic Moon)",
            diameterDisplayFa = "۳,۶۴۳ کیلومتر (۰.۲۸۶ برابر زمین / قمر آتشفشانی)",
            massDisplayEn = "8.932 × 10²² kg (0.0150× Earth)",
            massDisplayFa = "۸.۹۳۲ × ۱۰²۲ کیلوگرم (۰.۰۱۵۰ برابر زمین)",
            gravityDisplayEn = "1.796 m/s² (0.183× Earth)",
            gravityDisplayFa = "۱.۷۹۶ متر بر مجذور ثانیه (۰.۱۸۳ برابر زمین)",
            distanceDisplayEn = "421,700 km from Jupiter (Earth distance dynamic via Jupiter)",
            distanceDisplayFa = "۴۲۱,۷۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Galilean Volcanic Moon",
            categoryFa = "قمر آتشفشانی گالیله‌ای",
            descriptionEn = "Most volcanically active body in the Solar System, erupting sulfur plumes up to 500 km high.",
            descriptionFa = "فعال‌ترین جرم آتشفشانی منظومه شمسی با فوران‌های گوگردی تا ارتفاع ۵۰۰ کیلومتر.",
            verifiedFactsEn = listOf(
                "Io is the most volcanically active body in the Solar System with over 400 active sulfur volcanoes erupting plumes up to 500 km high.",
                "Tidal heating from orbital resonance (4:2:1 Laplace resonance) with Europa and Ganymede powers Io's intense internal volcanism.",
                "Continuous lava flows constantly resurface Io, giving it a vibrant colorful yellow, black, and red 'pizza-like' appearance.",
                "Io's motion through Jupiter's powerful magnetic field generates an electric current ring of approximately 1 million amperes.",
                "Unlike outer icy moons, Io consists primarily of silicate rock surrounding a molten iron or iron-sulfide core."
            ),
            verifiedFactsFa = listOf(
                "آیو فعال‌ترین جرم از نظر آتشفشانی در تمام منظومه شمسی است که فوران‌های گوگردی آن تا ارتفاع ۵۰۰ کیلومتری به فضا پرتاب می‌شوند.",
                "علت اصلی آتشفشان‌های شدید آیو، گرمایش جزر و مدی ناشی از رزونانس مداری ۴:۲:۱ با مشتری، اروپا و گانی‌مید است.",
                "سطح آیو دائماً با گدازه‌های تازه پوشانده می‌شود و ظاهر زرد، قرمز و سیاهی شبیه به پیتزا به آن می‌دهد.",
                "حرکت آیو در میدان مغناطیسی مشتری یک جریان الکتریکی عظیم با شدت ۱ میلیون آمپر ایجاد می‌کند.",
                "برخلاف بیشتر قمرهای یخ‌زده منظومه شمسی بیرونی، آیو عمدتاً از سنگ‌های سیلیکاتی و هسته آهنی مذاب تشکیل شده است."
            )
        )
    )

    val EUROPA = CanonicalAstroObject(
        canonicalId = "jup_europa",
        legacyIds = listOf("jupiter_europa", "europa", "galilean_moon_europa"),
        type = ObjectType.MOON,
        nameEn = "Europa (Galilean Moon)",
        nameFa = "اروپا (قمر مشتری)",
        parentId = "planet_jupiter",
        physicalProperties = PhysicalProperties(
            magnitude = 5.3,
            diameterKm = 3121.6,
            diameterComparedToEarth = 0.2450,
            massKg = 4.800e22,
            massComparedToEarth = 0.008037,
            surfaceGravityMS2 = 1.315,
            surfaceGravityComparedToEarth = 0.1341,
            distanceKm = 670900.0,
            distanceLightYears = null,
            temperatureK = 102,
            diameterDisplayEn = "3,122 km (0.245× Earth)",
            diameterDisplayFa = "۳,۱۲۲ کیلومتر (۰.۲۴۵ برابر زمین)",
            massDisplayEn = "4.800 × 10²² kg (0.0080× Earth)",
            massDisplayFa = "۴.۸۰۰ × ۱۰²۲ کیلوگرم (۰.۰۰۸۰ برابر زمین)",
            gravityDisplayEn = "1.315 m/s² (0.134× Earth)",
            gravityDisplayFa = "۱.۳۱۵ متر بر مجذور ثانیه (۰.۱۳۴ برابر زمین)",
            distanceDisplayEn = "670,900 km from Jupiter (Earth distance dynamic via Jupiter)",
            distanceDisplayFa = "۶۷۰,۹۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Galilean Ocean Moon",
            categoryFa = "قمر اقیانوسی گالیله‌ای",
            descriptionEn = "Smooth icy world hiding a global liquid water ocean beneath its frozen crust with high potential for habitability.",
            descriptionFa = "جهان یخی با اقیانوس آب مایع سرتاسری زیر پوسته یخی که از کاندیداهای اصلی حیات خارج از زمین است.",
            verifiedFactsEn = listOf(
                "Europa holds a vast subsurface global liquid water ocean containing more water than all of Earth's oceans combined.",
                "Its extremely smooth ice shell is crisscrossed by dark reddish fractures called lineae formed by tidal flexing and tectonic stresses.",
                "Europa is one of the highest-priority astrobiological targets in the search for habitable environments beyond Earth.",
                "Plumes of water vapor erupting scores of kilometers into space have been detected near Europa's southern polar region.",
                "Tidal flexing from Jupiter's gravitational field generates internal hydrothermal heat that keeps its deep ocean liquid."
            ),
            verifiedFactsFa = listOf(
                "اروپا دارای اقیانوسی جهانی از آب مایع زیر پوسته یخی خود است که حجم آب آن بیش از دو برابر تمام اقیانوس‌های زمین است.",
                "پوسته یخی اروپا بسیار صاف بوده و خطوط رگه‌مانند قهوه‌ای‌رنگی به نام «خطوارگی» (Lineae) سطح آن را پوشانده است.",
                "اقیانوس زیرسطحی اروپا به عنوان یکی از امیدوارکننده‌ترین جاها برای یافتن حیات فرازمینی توسط مأموریت‌های Clipper و JUICE بررسی می‌شود.",
                "آب‌فشان‌های عظیمی از بخار آب در قطب جنوب اروپا شناسایی شده‌اند که از فوران‌های اقیانوس زیرسطحی سرچشمه می‌گیرند.",
                "نیروی جزر و مدی مشتری باعث انقباض و انبساط مداوم هسته اروپا و تولید گرمای هیدروترمال برای مایع ماندن اقیانوس می‌شود."
            )
        )
    )

    val GANYMEDE = CanonicalAstroObject(
        canonicalId = "jup_ganymede",
        legacyIds = listOf("jupiter_ganymede", "ganymede", "galilean_moon_ganymede"),
        type = ObjectType.MOON,
        nameEn = "Ganymede (Galilean Moon)",
        nameFa = "گانیمد (قمر مشتری)",
        parentId = "planet_jupiter",
        physicalProperties = PhysicalProperties(
            magnitude = 4.6,
            diameterKm = 5268.2,
            diameterComparedToEarth = 0.4135,
            massKg = 1.482e23,
            massComparedToEarth = 0.02481,
            surfaceGravityMS2 = 1.428,
            surfaceGravityComparedToEarth = 0.1456,
            distanceKm = 1070400.0,
            distanceLightYears = null,
            temperatureK = 110,
            diameterDisplayEn = "5,268 km (0.414× Earth / Larger than Mercury)",
            diameterDisplayFa = "۵,۲۶۸ کیلومتر (۰.۴۱۴ برابر زمین / بزرگ‌تر از عطارد)",
            massDisplayEn = "1.482 × 10²³ kg (0.0248× Earth)",
            massDisplayFa = "۱.۴۸۲ × ۱۰²۳ کیلوگرم (۰.۰۲۴۸ برابر زمین)",
            gravityDisplayEn = "1.428 m/s² (0.146× Earth)",
            gravityDisplayFa = "۱.۴۲۸ متر بر مجذور ثانیه (۰.۱۴۶ برابر زمین)",
            distanceDisplayEn = "1,070,400 km from Jupiter (Earth distance dynamic via Jupiter)",
            distanceDisplayFa = "۱,۰۷۰,۴۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Galilean Moon (Largest Moon)",
            categoryFa = "بزرگ‌ترین قمر منظومه شمسی",
            descriptionEn = "Largest moon in the Solar System, larger than planet Mercury and possessing its own intrinsic magnetic field.",
            descriptionFa = "بزرگ‌ترین قمر منظومه شمسی، بزرگ‌تر از سیاره عطارد و دارای میدان مغناطیسی اختصاصی.",
            verifiedFactsEn = listOf(
                "Ganymede is the largest moon in the Solar System (5,268 km in diameter)—measuring larger than planet Mercury and dwarf planet Pluto.",
                "It is the only moon in the Solar System known to generate its own intrinsic magnetosphere, powered by a convecting liquid iron core.",
                "Magnetic interactions between Ganymede and Jupiter generate distinct ultraviolet auroral ovals around Ganymede's magnetic poles.",
                "A deep subsurface saltwater ocean containing more water than Earth lies stratified between high-pressure ice layers.",
                "Its surface features ancient, dark heavily cratered terrain alongside younger, grooved tectonic fractures."
            ),
            verifiedFactsFa = listOf(
                "گانی‌مید بزرگ‌ترین قمر در تمام منظومه شمسی است و ابعاد آن از سیاره عطارد و سیاره کوتوله پلوتو نیز بزرگ‌تر است.",
                "گانی‌مید تنها قمر شناخته‌شده در کیهان است که دارای میدان مغناطیسی اختصاصی (مگنوسفر) ناشی از هسته آهنی مذاب است.",
                "تعامل میدان مغناطیسی گانی‌مید با مشتری باعث ایجاد شفق‌های قطبی درخشان در قطب‌های این قمر می‌شود.",
                "در زیر پوسته ضخیم یخی گانی‌مید، اقیانوس عمیق چندلایه‌ای از آب مایع ساندویچ‌شده بین لایه‌های یخ وجود دارد.",
                "سطح گانی‌مید شامل دهانه‌های برخوردی ۴ میلیارد ساله تاریک و شیارهای روشن جوان‌تر ناشی از گسل‌های تکتونیکی است."
            )
        )
    )

    val CALLISTO = CanonicalAstroObject(
        canonicalId = "jup_callisto",
        legacyIds = listOf("jupiter_callisto", "callisto", "galilean_moon_callisto"),
        type = ObjectType.MOON,
        nameEn = "Callisto (Galilean Moon)",
        nameFa = "کالیستو (قمر مشتری)",
        parentId = "planet_jupiter",
        physicalProperties = PhysicalProperties(
            magnitude = 5.7,
            diameterKm = 4820.6,
            diameterComparedToEarth = 0.3783,
            massKg = 1.076e23,
            massComparedToEarth = 0.01802,
            surfaceGravityMS2 = 1.235,
            surfaceGravityComparedToEarth = 0.1259,
            distanceKm = 1882700.0,
            distanceLightYears = null,
            temperatureK = 134,
            diameterDisplayEn = "4,821 km (0.378× Earth)",
            diameterDisplayFa = "۴,۸۲۱ کیلومتر (۰.۳۷۸ برابر زمین)",
            massDisplayEn = "1.076 × 10²³ kg (0.0180× Earth)",
            massDisplayFa = "۱.۰۷۶ × ۱۰²۳ کیلوگرم (۰.۰۱۸۰ برابر زمین)",
            gravityDisplayEn = "1.235 m/s² (0.126× Earth)",
            gravityDisplayFa = "۱.۲۳۵ متر بر مجذور ثانیه (۰.۱۲۶ برابر زمین)",
            distanceDisplayEn = "1,882,700 km from Jupiter (Earth distance dynamic via Jupiter)",
            distanceDisplayFa = "۱,۸۸۲,۷۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Galilean Heavily Cratered Moon",
            categoryFa = "قمر کهنسال با دهانه‌های باستانی",
            descriptionEn = "Heavily cratered, ancient world in the Solar System with minimal surface geological activity.",
            descriptionFa = "یکی از قدیمی‌ترین سطوح دارنده دهانه‌های برخوردی باستان با کمترین فعالیت زمین‌شناختی.",
            verifiedFactsEn = listOf(
                "Callisto is the most heavily cratered object in the Solar System, preserving a 4-billion-year-old primordial icy surface.",
                "Its surface features colossal multi-ring impact structures, dominated by the ancient Valhalla impact basin spanning 3,800 km.",
                "Orbiting outside Jupiter's lethal main radiation belt makes Callisto the safest and most viable site for a future human exploration outpost.",
                "The total lack of internal volcanic or tectonic activity has left Callisto's geological cratering record pristine since the Solar System's birth.",
                "Callisto comprises an undifferentiated mixture of equal parts rock and ice and is suspected to harbor a deep, salty subsurface ocean."
            ),
            verifiedFactsFa = listOf(
                "کالیستو پردهانه‌ترین و دست‌نخورده‌ترین جرم منظومه شمسی است که سطح یخی آن بیش از ۴ میلیارد سال قدمت دارد.",
                "بزرگ‌ترین عارضه برخوردی کالیستو، دهانه چندحلقه‌ای «والهالا» با قطری بیش از ۳,۸۰۰ کیلومتر است.",
                "کالیستو خارج از کمربند تشعشعی خطرناک مشتری گردش می‌کند و بهترین گزینه برای پایگاه‌های انسانی آینده است.",
                "این قمر فاقد فعالیت‌های آتشفشانی یا تکتونیکی بوده و تاریخچه اولیه منظومه شمسی را به صورت بکر حفظ کرده است.",
                "کالیستو ترکیبی ۵۰/۵۰ از سنگ و یخ است و احتمالاً دارای اقیانوسی شور در عمق ۱۰۰ تا ۲۵۰ کیلومتری می‌باشد."
            )
        )
    )

    val ELARA = CanonicalAstroObject(
        canonicalId = "jup_elara",
        legacyIds = listOf("elara", "galilean_moon_elara"),
        type = ObjectType.MOON,
        nameEn = "Elara (Irregular Jovian Moon)",
        nameFa = "الارا (قمر نامنظم مشتری)",
        parentId = "planet_jupiter",
        physicalProperties = PhysicalProperties(
            magnitude = 16.3,
            diameterKm = 86.0,
            diameterComparedToEarth = 0.00675,
            massKg = 8.7e17,
            massComparedToEarth = 1.46e-7,
            surfaceGravityMS2 = 0.031,
            surfaceGravityComparedToEarth = 0.00316,
            distanceKm = 11740000.0,
            distanceLightYears = null,
            temperatureK = 124,
            diameterDisplayEn = "86 km diameter (Irregular Himalia Group)",
            diameterDisplayFa = "۸۶ کیلومتر قطر (قمر نامنظم هیپالیا)",
            massDisplayEn = "8.7 × 10¹⁷ kg (1.46 × 10⁻⁷× Earth)",
            massDisplayFa = "۸.۷ × ۱۰¹۷ کیلوگرم (۱.۴۶ × ۱۰⁻⁷ برابر زمین)",
            gravityDisplayEn = "0.031 m/s² (0.0032× Earth)",
            gravityDisplayFa = "۰.۰۳۱ متر بر مجذور ثانیه (۰.۰۰۳۲ برابر زمین)",
            distanceDisplayEn = "11,740,000 km from Jupiter",
            distanceDisplayFa = "۱۱,۷۴۰,۰۰۰ کیلومتر از مشتری"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Prograde Irregular Moon",
            categoryFa = "قمر نامنظم موافق‌گرد مشتری",
            descriptionEn = "Eighth-largest moon of Jupiter, discovered by Charles Dillon Perrine in 1905.",
            descriptionFa = "هشتمین قمر بزرگ مشتری که در سال ۱۹۰۵ کشف شد.",
            verifiedFactsEn = listOf(
                "Elara is an irregular Jovian satellite discovered in 1905 by astronomer Charles Dillon Perrine at Lick Observatory.",
                "Elara did not receive its present name until 1975; before then it was known simply as Jupiter VII, and between 1955 and 1975 it was sometimes informally called 'Hera'.",
                "It belongs to the Himalia group of prograde irregular moons orbiting nearly 11.7 million kilometers from Jupiter.",
                "Elara has an extremely dark C-type carbonaceous surface with an albedo of just 0.04, indicating it is an ancient captured asteroid.",
                "It takes Elara approximately 259.6 Earth days to complete a single eccentric orbit around Jupiter."
            ),
            verifiedFactsFa = listOf(
                "این قمر نامنظم مشتری در سال ۱۹۰۵ توسط چارلز دیلون پرین در رصدخانه لیک کشف شد.",
                "این قمر تا سال ۱۹۷۵ نام کنونی خود را نداشت و تنها با عنوان «مشتری ۷» (Jupiter VII) شناخته می‌شد؛ میان سال‌های ۱۹۵۵ تا ۱۹۷۵ گاهی به‌طور غیررسمی «هرا» نامیده می‌شد.",
                "الارا متعلق به گروه هیمالیا از قمرهای نامنظم مشتری است که مداری موافق و دوردست در فاصله ۱۱.۷ میلیون کیلومتری دارند.",
                "سطح الارا بسیار تاریک و خاکستری‌رنگ (از نوع کربنی C) است که نشان می‌دهد احتمالاً سیارکی بوده که توسط گرانش مشتری به دام افتاده است.",
                "یک دور گردش کامل الارا به دور مشتری حدود ۲۵۹.۶ روز زمین به طول می‌انجامد."
            )
        )
    )

    // --- 3. GALACTIC CENTER & BLACK HOLE (RESOLVED DUPLICATE) ---

    val SAGITTARIUS_A_STAR = CanonicalAstroObject(
        canonicalId = "sagittarius_a_star",
        legacyIds = listOf("galaxy_milky_way", "gal_center", "lab_sgra", "sgra"),
        type = ObjectType.BLACK_HOLE,
        nameEn = "Sagittarius A* (Milky Way Galactic Center)",
        nameFa = "سیاهچاله کمان آ* (مرکز کهکشان راه شیری)",
        searchAliasesEn = listOf("sagittarius a*", "sgr a*", "galactic center", "milky way center"),
        searchAliasesFa = listOf("کمان آ", "مرکز راه شیری", "سیاهچاله راه شیری", "مرکز کهکشان"),
        scientificIdentifiers = ScientificIdentifiers(
            constellationCode = "SGR"
        ),
        staticPosition = StaticPosition(
            raDeg = 266.416,
            decDeg = -29.007,
            distanceLightYears = 26000.0
        ),
        physicalProperties = PhysicalProperties(
            magnitude = -5.0,
            diameterKm = 5.18e7,
            massKg = 8.26e36,
            massComparedToEarth = 1.38e12,
            distanceKm = 2.46e17,
            distanceLightYears = 26000.0,
            relativisticGravitationalRatio = 0.99999,
            relativisticKinematicRatio = 0.98,
            diameterDisplayEn = "Event Horizon: ~25M to 52M km (0.16 to 0.35 AU)",
            diameterDisplayFa = "افق رویداد: ~۲۵ تا ۵۲ میلیون کیلومتر (۰.۱۶ تا ۰.۳۵ واحد نجومی)",
            massDisplayEn = "8.26 × 10³⁶ kg (4.15 Million Solar Masses)",
            massDisplayFa = "۸.۲۶ × ۱۰³۶ کیلوگرم (۴.۱۵ میلیون برابر جرم خورشید)",
            gravityDisplayEn = "Supermassive black hole with extreme relativistic gravity",
            gravityDisplayFa = "سیاهچاله کلان‌جرم با میدان گرانشی نسبیتی مفرط",
            distanceDisplayEn = "26,000 light-years (2.46 × 10¹⁷ km)",
            distanceDisplayFa = "۲۶,۰۰۰ سال نوری (۲.۴۶ × ۱۰¹۷ کیلومتر)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Supermassive Black Hole / Galactic Nucleus",
            categoryFa = "سیاهچاله کلان‌جرم / هسته کهکشانی",
            descriptionEn = "Supermassive black hole at the dynamical center of the Milky Way galaxy.",
            descriptionFa = "سیاهچاله کلان‌جرم گرانشی در مرکز دینامیکی کهکشان راه شیری با ۴.۱۵ میلیون برابر جرم خورشید.",
            observationTipEn = "Visible as a dense glowing arch of stars across dark summer skies in Sagittarius.",
            observationTipFa = "در شب‌های تابستان به صورت نوار پرنور قوس راه شیری در صورت فلکی قوس دیده می‌شود.",
            verifiedFactsEn = listOf(
                "Sagittarius A* (Sgr A*) is the supermassive black hole situated at the exact dynamical center of the Milky Way galaxy.",
                "It contains an accurately measured mass of 4.15 million times that of our Sun, concentrated within a radius smaller than Mercury's orbit.",
                "In May 2022, the Event Horizon Telescope (EHT) collaboration published the first direct radio image of the glowing accretion shadow around Sgr A*.",
                "Close-orbiting stars like S2 revolve around Sgr A* at speeds exceeding 7,000 km/s (nearly 3% the speed of light) at closest periastron.",
                "Located 26,000 light-years away in Sagittarius, Sgr A* is shrouded behind visual dust lanes and studied via radio, infrared, and X-ray observatories."
            ),
            verifiedFactsFa = listOf(
                "کمان آ (*Sagittarius A) سیاهچاله کلان‌جرم مرکز کهکشان راه شیری با جرمی معادل ۴.۱۵ میلیون برابر خورشید است.",
                "در سال ۲۰۲۲، تلسکوپ افق رویداد (EHT) نخستین تصویر مستقیم از سایه افق رویداد این سیاهچاله را منتشر کرد.",
                "ستارگان مرکزی نزدیک مانند S2 با سرعتی بالغ بر چند درصد سرعت نور در مدارهای بیضوی فشرده به دور آن می‌چرخند.",
                "شعاع افق رویداد (شعاع شوارتزشیلد) آن حدود ۱۲.۳ میلیون کیلومتر است که کمتر از فاصله عطارد تا خورشید می‌باشد.",
                "کمان آ یک سیاهچاله فشرده با تابش رادیویی غول‌پیکر است که گرانش مرکزی کهکشان ما را کنترل می‌کند."
            )
        )
    )

    // --- 4. SATELLITES (RESOLVED DUPLICATE ISS) ---

    val ISS = CanonicalAstroObject(
        canonicalId = "sat_25544",
        legacyIds = listOf("sat_iss", "iss"),
        type = ObjectType.SATELLITE,
        nameEn = "International Space Station (ISS)",
        nameFa = "ایستگاه فضایی بین‌المللی",
        searchAliasesEn = listOf("iss", "space station", "sat_iss", "zarya"),
        searchAliasesFa = listOf("ایستگاه فضایی", "ایستگاه بین المللی", "آی اس اس"),
        parentId = "planet_earth",
        scientificIdentifiers = ScientificIdentifiers(
            noradId = 25544,
            constellationCode = "LEO"
        ),
        physicalProperties = PhysicalProperties(
            magnitude = -3.2,
            diameterKm = 0.109,
            diameterComparedToEarth = 0.00000855,
            massKg = 450000.0,
            massComparedToEarth = 7.53e-20,
            surfaceGravityMS2 = 8.7,
            surfaceGravityComparedToEarth = 0.887,
            distanceKm = 420.0,
            distanceLightYears = 4.44e-11,
            orbitalPeriodDays = 0.064,
            diameterDisplayEn = "109 meters (Football field size)",
            diameterDisplayFa = "۱۰۹ متر (اندازه یک زمین فوتبال)",
            massDisplayEn = "450,000 kg (450 tonnes)",
            massDisplayFa = "۴۵۰,۰۰۰ کیلوگرم (۴۵۰ تن)",
            gravityDisplayEn = "8.7 m/s² orbital (Microgravity / free-fall environment)",
            gravityDisplayFa = "۸.۷ متر بر مجذور ثانیه در مدار (بی‌وزنی ظاهری / سقوط آزاد)",
            distanceDisplayEn = "420 km (Dynamic orbital altitude above Earth)",
            distanceDisplayFa = "۴۲۰ کیلومتر (ارتفاع مداری متغیر از سطح زمین)"
        ),
        observationalInfo = ObservationalInfo(
            categoryEn = "Habitable Space Station",
            categoryFa = "ایستگاه فضایی سرنشین‌دار",
            descriptionEn = "Habitable artificial satellite orbiting Earth every 90 minutes at 28,000 km/h.",
            descriptionFa = "ایستگاه فضایی سرنشین‌دار در حال چرخش به دور زمین هر ۹۰ دقیقه با سرعت ۲۸,۰۰۰ کیلومتر بر ساعت.",
            observationTipEn = "Appears as a fast-moving unblinking bright point across twilight skies.",
            observationTipFa = "مانند یک نقطه بسیار پرنور بدون چشمک‌زدن با سرعت بالای افق حرکت می‌کند.",
            verifiedFactsEn = listOf(
                "The ISS orbits Earth at an average velocity of 27,600 km/h (17,150 mph), completing one full orbit every 90 to 92 minutes.",
                "Astronauts aboard the ISS experience 16 sunrises and 16 sunsets every 24 hours as they traverse orbital day-night terminators.",
                "Spanning 109 meters from end to end (the size of an entire football field), its solar arrays cover over 2,400 square meters.",
                "The station provides an internal pressurized living volume of 916 cubic meters, equivalent to a large six-bedroom house.",
                "The ISS has been continuously inhabited by international astronaut crews without interruption since November 2, 2000."
            ),
            verifiedFactsFa = listOf(
                "ایستگاه فضایی بین‌المللی با سرعت ۲۷,۶۰۰ کیلومتر بر ساعت، هر ۹۰ تا ۹۲ دقیقه یک‌بار زمین را دور می‌زند.",
                "فضانوردان حاضر در ISS در هر ۲۴ ساعت، ۱۶ بار طلوع و ۱۶ بار غروب خورشید را تجربه می‌کنند.",
                "ابعاد ایستگاه فضایی معادل یک زمین فوتبال بزرگ است و فضایی قابل سکونت برابر یک خانه ۶ خوابه دارد.",
                "پنل‌های خورشیدی ISS مساحتی حدود ۲,۴۰۰ متر مربع را پوشش می‌دهند و برق کل ایستگاه را تامین می‌کنند.",
                "ایستگاه فضایی از زمان نوامبر سال ۲۰۰۰ میلادی به صورت پیوسته و بدون وقفه میزبان فضانوردان بوده است."
            )
        )
    )

    // --- LOOKUP & RESOLUTION ENGINE ---

    private val allCanonicalObjectsList: List<CanonicalAstroObject> = listOf(
        SUN, EARTH, MOON, MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE, PLUTO,
        IO, EUROPA, GANYMEDE, CALLISTO, ELARA, SAGITTARIUS_A_STAR, ISS
    )

    private val deepSkyEngineForMetadata by lazy { DeepSkyEngine() }
    private const val HAND_DOUBLE_CLUSTER_ID = "dso_double_cluster"
    private val splitDoubleClusterEngineIds = setOf("ngc869", "ngc884")
    private val skippedDoubleClusterAliasIds = setOf("c14")

    data class DeepSkyMergeReport(
        val engineCatalogCount: Int,
        val insertedCount: Int,
        val skippedCanonicalDuplicateCount: Int,
        val skippedEngineDuplicateCount: Int,
        val mergedCatalogDuplicateCount: Int,
        val originalHandAuthoredDsoCount: Int,
        val finalDeepSkyCount: Int,
        val finalCatalogCount: Int,
        val duplicateCanonicalIds: List<String>
    )

    private val monthNamesEn = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )
    private val monthNamesFa = listOf(
        "دی‌ماه", "بهمن‌ماه", "اسفندماه", "فروردین‌ماه", "اردیبهشت‌ماه", "خردادماه",
        "تیرماه", "مردادماه", "شهریورماه", "مهرماه", "آبان‌ماه", "آذرماه"
    )

    private val constellationCodeOverrides = mapOf(
        "andromeda" to "AND",
        "dorado / mensa" to "DOR/MEN",
        "tucana" to "TUC",
        "triangulum" to "TRI",
        "taurus" to "TAU",
        "cancer" to "CNC",
        "perseus" to "PER",
        "coma berenices" to "COM",
        "centaurus" to "CEN",
        "hercules" to "HER",
        "orion" to "ORI",
        "sagittarius" to "SGR",
        "carina" to "CAR"
    )

    private fun normalizedCatalogToken(value: String?): String? {
        val clean = value
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]+"), "")
            ?: return null
        return clean.takeIf { it.isNotBlank() }
    }

    private fun displayCatalogToken(token: String): String {
        return when {
            token.startsWith("ngc") -> "NGC ${token.drop(3)}"
            token.startsWith("ic") -> "IC ${token.drop(2)}"
            token.startsWith("melotte") -> "Melotte ${token.drop(7)}"
            token.startsWith("mel") -> "Melotte ${token.drop(3)}"
            token.startsWith("m") && token.drop(1).all(Char::isDigit) -> "M${token.drop(1)}"
            else -> token.uppercase()
        }
    }

    private fun catalogTokensFromText(value: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val upper = value.uppercase()
        Regex("\\bM\\s*\\d+[A-Z]?\\b").findAll(upper).forEach { match ->
            normalizedCatalogToken(match.value)?.let(tokens::add)
        }
        Regex("\\bNGC\\s*\\d+[A-Z]?\\b").findAll(upper).forEach { match ->
            normalizedCatalogToken(match.value)?.let(tokens::add)
        }
        Regex("\\bIC\\s*\\d+[A-Z]?\\b").findAll(upper).forEach { match ->
            normalizedCatalogToken(match.value)?.let(tokens::add)
        }
        Regex("\\bC\\s*\\d+[A-Z]?\\b").findAll(upper).forEach { match ->
            normalizedCatalogToken(match.value)?.let(tokens::add)
        }
        Regex("\\bMEL(?:OTTE)?\\s*\\d+[A-Z]?\\b").findAll(upper).forEach { match ->
            normalizedCatalogToken(match.value)?.let(tokens::add)
        }
        return tokens
    }

    private fun isCatalogIdentityToken(token: String): Boolean {
        return when {
            token.startsWith("ngc") -> token.drop(3).all(Char::isDigit)
            token.startsWith("ic") -> token.drop(2).all(Char::isDigit)
            token.startsWith("m") -> token.drop(1).all(Char::isDigit)
            token.startsWith("c") -> token.drop(1).all(Char::isDigit)
            token.startsWith("melotte") -> token.drop(7).all(Char::isDigit)
            token.startsWith("mel") -> token.drop(3).all(Char::isDigit)
            token.startsWith("norad") -> token.drop(5).all(Char::isDigit)
            else -> false
        }
    }

    private fun catalogIdentityTokens(obj: CanonicalAstroObject): Set<String> {
        val raw = buildList {
            addAll(obj.scientificIdentifiers.catalogDesignations)
            addAll(obj.legacyIds)
            obj.scientificIdentifiers.messierId?.let(::add)
            obj.scientificIdentifiers.ngcId?.let(::add)
            obj.scientificIdentifiers.caldwellId?.let(::add)
            obj.scientificIdentifiers.noradId?.let { add("NORAD $it") }
        }
        return raw.mapNotNull(::normalizedCatalogToken)
            .filter(::isCatalogIdentityToken)
            .toSet()
    }

    private fun CanonicalAstroObject.withMergedCatalogIdentity(candidate: CanonicalAstroObject): CanonicalAstroObject {
        val mergedDesignations = (scientificIdentifiers.catalogDesignations + candidate.scientificIdentifiers.catalogDesignations)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizedCatalogToken(it) ?: it.lowercase() }
        val mergedLegacyIds = (legacyIds + candidate.legacyIds + candidate.scientificIdentifiers.catalogDesignations)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        return copy(
            legacyIds = mergedLegacyIds,
            searchAliasesEn = (searchAliasesEn + candidate.searchAliasesEn + mergedDesignations).distinct(),
            searchAliasesFa = (searchAliasesFa + candidate.searchAliasesFa + mergedDesignations).distinct(),
            scientificIdentifiers = scientificIdentifiers.copy(
                messierId = scientificIdentifiers.messierId ?: candidate.scientificIdentifiers.messierId,
                ngcId = scientificIdentifiers.ngcId ?: candidate.scientificIdentifiers.ngcId,
                caldwellId = scientificIdentifiers.caldwellId ?: candidate.scientificIdentifiers.caldwellId,
                catalogDesignations = mergedDesignations
            ),
            observationalInfo = observationalInfo.copy(
                verifiedFactsEn = observationalInfo.verifiedFactsEn.ifEmpty { candidate.observationalInfo.verifiedFactsEn },
                verifiedFactsFa = observationalInfo.verifiedFactsFa.ifEmpty { candidate.observationalInfo.verifiedFactsFa },
                funFactsEn = observationalInfo.funFactsEn.ifEmpty { candidate.observationalInfo.funFactsEn },
                funFactsFa = observationalInfo.funFactsFa.ifEmpty { candidate.observationalInfo.funFactsFa }
            )
        )
    }

    private val coordinateDuplicateAllowlist = setOf(
        setOf("dso_m102", "dso_ngc_3115"),
        setOf("dso_ngc_6960", "dso_ngc_6992")
    )

    private fun angularSeparationDegrees(first: CanonicalAstroObject, second: CanonicalAstroObject): Double {
        val firstPos = first.staticPosition ?: return Double.POSITIVE_INFINITY
        val secondPos = second.staticPosition ?: return Double.POSITIVE_INFINITY
        val ra1 = Math.toRadians(firstPos.raDeg)
        val dec1 = Math.toRadians(firstPos.decDeg)
        val ra2 = Math.toRadians(secondPos.raDeg)
        val dec2 = Math.toRadians(secondPos.decDeg)
        val cosine = (sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2))
            .coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    private fun isCoordinateDuplicate(existing: CanonicalAstroObject, candidate: CanonicalAstroObject): Boolean {
        if (existing.type != candidate.type) return false
        if (setOf(existing.canonicalId, candidate.canonicalId) in coordinateDuplicateAllowlist) return false
        val separation = angularSeparationDegrees(existing, candidate)
        val magnitudeDelta = abs(existing.physicalProperties.magnitude - candidate.physicalProperties.magnitude)
        return separation <= 0.05 && magnitudeDelta <= 0.5
    }

    private fun MutableList<CanonicalAstroObject>.addOrMergeCatalogIdentity(candidate: CanonicalAstroObject) {
        val candidateTokens = catalogIdentityTokens(candidate)
        val catalogMatchingIndex = if (candidateTokens.isNotEmpty()) {
            indexOfFirst { existing -> catalogIdentityTokens(existing).any { it in candidateTokens } }
        } else {
            -1
        }
        val coordinateMatchingIndex = if (catalogMatchingIndex < 0) {
            indexOfFirst { existing -> isCoordinateDuplicate(existing, candidate) }
        } else {
            -1
        }
        val matchingIndex = if (catalogMatchingIndex >= 0) catalogMatchingIndex else coordinateMatchingIndex
        when {
            matchingIndex >= 0 -> this[matchingIndex] = this[matchingIndex].withMergedCatalogIdentity(candidate)
            none { it.canonicalId == candidate.canonicalId } -> add(candidate)
        }
    }

    private fun handAuthoredDsoTokens(obj: CelestialObject): Set<String> {
        val tokens = mutableSetOf<String>()
        tokens += catalogTokensFromText(obj.id)
        tokens += catalogTokensFromText(obj.nameEn)
        tokens += catalogTokensFromText(obj.nameFa)
        tokens += catalogTokensFromText(obj.category)
        obj.nameEn.substringBefore("(").trim().lowercase().takeIf { it.length > 2 }?.let { head ->
            tokens += head
            normalizedCatalogToken(head)?.let(tokens::add)
        }
        obj.nameFa.substringBefore("(").trim().lowercase().takeIf { it.length > 2 }?.let { head ->
            tokens += head
            normalizedCatalogToken(head)?.let(tokens::add)
        }
        return tokens
    }

    private fun engineDsoTokens(obj: EngineDeepSkyCatalog.DeepSkyObject): Set<String> {
        return listOfNotNull(
            obj.catalogId,
            obj.altCatalogId,
            obj.commonName
        ).mapNotNull { normalizedCatalogToken(it) ?: it.lowercase().takeIf { raw -> raw.length > 2 } }.toSet()
    }

    private fun isCanonicalDeepSkyType(type: ObjectType): Boolean {
        return type == ObjectType.DEEP_SKY ||
                type == ObjectType.GALAXY ||
                type == ObjectType.NEBULA ||
                type == ObjectType.STAR_CLUSTER ||
                type == ObjectType.GLOBULAR_CLUSTER ||
                type == ObjectType.BLACK_HOLE
    }

    private fun isSplitDoubleClusterComponent(obj: EngineDeepSkyCatalog.DeepSkyObject): Boolean {
        return normalizedCatalogToken(obj.catalogId) in splitDoubleClusterEngineIds
    }

    private fun isSkippedDoubleClusterAlias(obj: EngineDeepSkyCatalog.DeepSkyObject): Boolean {
        return normalizedCatalogToken(obj.catalogId) in skippedDoubleClusterAliasIds
    }

    private fun shouldSkipEngineDsoAsHandDuplicate(
        obj: EngineDeepSkyCatalog.DeepSkyObject,
        handTokens: Set<String>
    ): Boolean {
        if (isSplitDoubleClusterComponent(obj)) return false
        if (isSkippedDoubleClusterAlias(obj)) return false
        return engineDsoTokens(obj).any { it in handTokens }
    }

    private fun mapEngineDeepSkyType(type: EngineDeepSkyCatalog.ObjectType): ObjectType {
        return when (type) {
            EngineDeepSkyCatalog.ObjectType.GALAXY,
            EngineDeepSkyCatalog.ObjectType.SPIRAL_GALAXY,
            EngineDeepSkyCatalog.ObjectType.ELLIPTICAL_GALAXY,
            EngineDeepSkyCatalog.ObjectType.IRREGULAR_GALAXY,
            EngineDeepSkyCatalog.ObjectType.LENTICULAR_GALAXY -> ObjectType.GALAXY
            EngineDeepSkyCatalog.ObjectType.DIFFUSE_NEBULA,
            EngineDeepSkyCatalog.ObjectType.PLANETARY_NEBULA,
            EngineDeepSkyCatalog.ObjectType.SUPERNOVA_REMNANT -> ObjectType.NEBULA
            EngineDeepSkyCatalog.ObjectType.GLOBULAR_CLUSTER -> ObjectType.GLOBULAR_CLUSTER
            EngineDeepSkyCatalog.ObjectType.OPEN_CLUSTER,
            EngineDeepSkyCatalog.ObjectType.STAR_CLOUD,
            EngineDeepSkyCatalog.ObjectType.DOUBLE_STAR,
            EngineDeepSkyCatalog.ObjectType.STAR -> ObjectType.STAR_CLUSTER
        }
    }

    private fun cleanEngineTypeName(type: EngineDeepSkyCatalog.ObjectType): String {
        return type.name.lowercase().split("_").joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun constellationEntryFor(value: String) = ConstellationCatalog.getConstellations().firstOrNull {
        it.code.equals(value, ignoreCase = true) ||
                it.nameEn.equals(value, ignoreCase = true) ||
                it.nameFa.equals(value, ignoreCase = true)
    }

    private fun constellationNamesForCode(code: String): Pair<String, String> {
        val c = constellationEntryFor(code)
        return if (c != null) c.nameEn to c.nameFa else code to code
    }

    private fun constellationCodeForName(nameEn: String, nameFa: String = ""): String {
        return constellationEntryFor(nameEn)?.code
            ?: nameFa.takeIf { it.isNotBlank() }?.let { constellationEntryFor(it)?.code }
            ?: constellationCodeOverrides[nameEn.trim().lowercase()]
            ?: nameEn
    }

    private fun mapEngineDeepSkyObject(obj: EngineDeepSkyCatalog.DeepSkyObject): CanonicalAstroObject {
        val normalizedCatalog = obj.catalogId.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        val doubleClusterAliasDesignations = if (normalizedCatalogToken(obj.catalogId) == "ngc869") listOf("C14") else emptyList()
        val designations = (listOfNotNull(obj.catalogId, obj.altCatalogId) + doubleClusterAliasDesignations).distinct()
        val normalizedDesignations = designations.mapNotNull(::normalizedCatalogToken).distinct()
        val designationText = designations.joinToString(" / ")
        val commonOrCatalog = obj.commonName ?: designationText
        val nameEn = if (obj.commonName != null) "$commonOrCatalog ($designationText)" else commonOrCatalog
        val nameFa = DeepSkyContent.persianNameForEngineObject(obj, designationText)
        val (constEn, constFa) = constellationNamesForCode(obj.constellation)
        val bestMonth = deepSkyEngineForMetadata.bestViewingMonth(obj).coerceIn(1, 12)
        val type = mapEngineDeepSkyType(obj.type)
        val category = cleanEngineTypeName(obj.type)
        val categoryFa = DeepSkyContent.typeNameFa(obj.type)
        val curatedFacts = DeepSkyContent.factsForCanonicalId("dso_$normalizedCatalog")
        val distanceLy = obj.distanceLy ?: 0.0
        val distanceKm = if (distanceLy > 0.0) distanceLy * 9.4607304725808e12 else null
        val messier = obj.catalogId.takeIf { it.startsWith("M", ignoreCase = true) }
        val ngc = listOf(obj.catalogId, obj.altCatalogId).firstOrNull { it?.startsWith("NGC", ignoreCase = true) == true }
        val caldwell = obj.catalogId.takeIf { it.startsWith("C", ignoreCase = true) && it.drop(1).all(Char::isDigit) }
        val aliasesEn = (designations + listOfNotNull(obj.commonName, obj.commonName?.substringBefore("("), constEn, category))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.lowercase()) }
            .distinct()

        return CanonicalAstroObject(
            canonicalId = "dso_$normalizedCatalog",
            legacyIds = (designations + normalizedDesignations + listOf("deep_sky_$normalizedCatalog")).map { it.lowercase() }.distinct(),
            type = type,
            nameEn = nameEn,
            nameFa = nameFa,
            searchAliasesEn = (aliasesEn + normalizedDesignations).distinct(),
            searchAliasesFa = (designations + normalizedDesignations + listOf(nameFa, constFa, categoryFa)).distinct(),
            scientificIdentifiers = ScientificIdentifiers(
                messierId = messier,
                ngcId = ngc,
                caldwellId = caldwell,
                catalogDesignations = designations,
                constellationCode = obj.constellation.uppercase(),
                constellationNameEn = constEn,
                constellationNameFa = constFa
            ),
            staticPosition = StaticPosition(
                raDeg = obj.raHours * 15.0,
                decDeg = obj.decDeg,
                distanceLightYears = distanceLy,
                epoch = "J2000"
            ),
            physicalProperties = PhysicalProperties(
                magnitude = obj.magnitude,
                distanceKm = distanceKm,
                distanceLightYears = obj.distanceLy,
                angularSizeArcmin = obj.sizeArcmin,
                distanceDisplayEn = if (distanceLy > 0.0) String.format(Locale.US, "%,.0f light-years", distanceLy) else "Unknown distance",
                distanceDisplayFa = if (distanceLy > 0.0) TimeEngine.formatPersianNumbers(String.format(Locale.US, "%,.0f سال نوری", distanceLy)) else "فاصله نامشخص"
            ),
            observationalInfo = ObservationalInfo(
                categoryEn = category,
                categoryFa = categoryFa,
                descriptionEn = obj.description,
                descriptionFa = DeepSkyContent.persianDescriptionForEngineObject(obj, designationText, constFa),
                observationTipEn = "Best observed from a dark-sky site with binoculars or a telescope when high above the horizon.",
                observationTipFa = "برای رصد بهتر، این جرم را در آسمان تاریک و هنگام ارتفاع زیاد با دوربین دوچشمی یا تلسکوپ دنبال کنید.",
                bestViewingMonthEn = monthNamesEn[bestMonth - 1],
                bestViewingMonthFa = monthNamesFa[bestMonth - 1],
                verifiedFactsEn = curatedFacts?.en ?: emptyList(),
                verifiedFactsFa = curatedFacts?.fa ?: emptyList()
            )
        )
    }

    // Aggregates core canonical objects with mapped objects from stars, deep sky, satellites, constellations, meteor showers, and asterisms
    private val extendedCanonicalObjectsList: List<CanonicalAstroObject> by lazy {
        val list = mutableListOf<CanonicalAstroObject>()
        list.addAll(allCanonicalObjectsList)

        val coreIds = allCanonicalObjectsList.map { it.canonicalId }.toSet()

        // 1. Satellites from SatelliteCatalog
        for (sat in SatelliteCatalog.satellites) {
            val satCanonId = if (sat.noradId == 25544) "sat_25544" else "sat_${sat.noradId}"
            if (!coreIds.contains(satCanonId) && !list.any { it.canonicalId == satCanonId }) {
                list.addOrMergeCatalogIdentity(
                    CanonicalAstroObject(
                        canonicalId = satCanonId,
                        legacyIds = listOf(sat.id, "sat_${sat.id}", "norad_${sat.noradId}"),
                        type = ObjectType.SATELLITE,
                        nameEn = sat.nameEn,
                        nameFa = sat.nameFa,
                        searchAliasesEn = listOf(sat.designation, "norad ${sat.noradId}", sat.category.labelEn, sat.nameEn.lowercase()),
                        searchAliasesFa = listOf(sat.category.labelFa, sat.nameFa),
                        parentId = "planet_earth",
                        scientificIdentifiers = ScientificIdentifiers(noradId = sat.noradId, constellationCode = "LEO"),
                        physicalProperties = PhysicalProperties(magnitude = sat.standardMagnitude),
                        observationalInfo = ObservationalInfo(
                            categoryEn = sat.category.labelEn,
                            categoryFa = sat.category.labelFa,
                            descriptionEn = sat.descriptionEn,
                            descriptionFa = sat.descriptionFa,
                            verifiedFactsEn = sat.verifiedFactsEn,
                            verifiedFactsFa = sat.verifiedFactsFa
                        )
                    )
                )
            }
        }

        // 2. Stars, Deep Sky, Meteor Showers, Asterisms
        val extraObjects = StarCatalog.getStars() + BscNamedStarCatalog.getStars() + DeepSkyCatalog.getDeepSkyObjects() +
                MeteorShowerCatalog.getMeteorShowers() + AsterismCatalog.getAsterisms()

        for (obj in extraObjects) {
            if (obj.id == HAND_DOUBLE_CLUSTER_ID) continue // Split below into independent NGC 869 and NGC 884 objects.
            val canonId = obj.id
            if (!coreIds.contains(canonId) && !list.any { it.canonicalId == canonId }) {
                val faWords = obj.nameFa.replace("(", " ").replace(")", " ").replace("/", " ").split(" ").map { it.trim() }.filter { it.length > 1 }
                val enWords = obj.nameEn.replace("(", " ").replace(")", " ").replace("/", " ").split(" ").map { it.trim().lowercase() }.filter { it.length > 1 }
                val categoryFa = CatalogTextLocalizer.persianCategory(obj.category)
                val phys = PhysicalData.getPhysicalProperties(obj)
                val curatedFacts = DeepSkyContent.factsForCanonicalId(canonId)
                val factsFa = obj.funFactsFa.ifEmpty { curatedFacts?.fa ?: PhysicalData.getCoolFactsFa(obj) }
                val factsEn = obj.funFactsEn.ifEmpty { curatedFacts?.en ?: PhysicalData.getCoolFactsEn(obj) }
                val baseCatalogTokens = catalogTokensFromText(obj.nameEn) + catalogTokensFromText(obj.id)
                val handTokensForMatch = handAuthoredDsoTokens(obj)
                val matchedEngineDso = if (isCanonicalDeepSkyType(obj.type) && obj.id != HAND_DOUBLE_CLUSTER_ID) {
                    EngineDeepSkyCatalog.objects.firstOrNull { engineDsoTokens(it).any { token -> token in handTokensForMatch } }
                } else {
                    null
                }
                val engineCatalogTokens = matchedEngineDso?.let { catalogTokensFromText(it.catalogId) + (it.altCatalogId?.let(::catalogTokensFromText) ?: emptySet()) } ?: emptySet()
                val catalogTokens = baseCatalogTokens + engineCatalogTokens
                val displayDesignations = (catalogTokens.map { displayCatalogToken(it) } + listOfNotNull(matchedEngineDso?.catalogId, matchedEngineDso?.altCatalogId)).distinct()
                val messier = catalogTokens.firstOrNull { it.startsWith("m") && it.drop(1).all(Char::isDigit) }?.let { displayCatalogToken(it) }
                val ngc = catalogTokens.firstOrNull { it.startsWith("ngc") && it.drop(3).all(Char::isDigit) }?.let { displayCatalogToken(it) }
                val caldwell = matchedEngineDso?.catalogId?.takeIf { it.startsWith("C", ignoreCase = true) && it.drop(1).all(Char::isDigit) }
                val bestMonthFromEngine = matchedEngineDso?.let { deepSkyEngineForMetadata.bestViewingMonth(it).coerceIn(1, 12) }
                val angularSizeArcmin = obj.angularSizeArcmin ?: matchedEngineDso?.sizeArcmin

                list.addOrMergeCatalogIdentity(
                    CanonicalAstroObject(
                        canonicalId = canonId,
                        legacyIds = listOf(obj.id),
                        type = obj.type,
                        nameEn = obj.nameEn,
                        nameFa = obj.nameFa,
                        searchAliasesEn = (listOf(obj.nameEn.lowercase(), obj.constellationEn.lowercase(), obj.category.lowercase(), obj.bayerDesignation ?: "") + enWords + catalogTokens + displayDesignations).distinct(),
                        searchAliasesFa = (listOf(obj.nameFa, obj.constellationFa, categoryFa) + faWords + catalogTokens + displayDesignations).distinct(),
                        scientificIdentifiers = ScientificIdentifiers(
                            messierId = messier,
                            ngcId = ngc,
                            caldwellId = caldwell,
                            catalogDesignations = displayDesignations,
                            constellationCode = matchedEngineDso?.constellation?.uppercase() ?: constellationCodeForName(obj.constellationEn, obj.constellationFa),
                            constellationNameEn = obj.constellationEn,
                            constellationNameFa = obj.constellationFa,
                            spectralType = obj.spectralType,
                            hipId = obj.hipId,
                            hdId = obj.hdId,
                            bayerDesignation = obj.bayerDesignation,
                            flamsteedNumber = obj.flamsteedNumber
                        ),
                        staticPosition = StaticPosition(raDeg = obj.raDeg, decDeg = obj.decDeg, distanceLightYears = obj.distanceLightYears),
                        physicalProperties = PhysicalProperties(
                            magnitude = obj.magnitude,
                            temperatureK = obj.temperatureK,
                            diameterKm = phys.diameterKm,
                            diameterComparedToEarth = phys.diameterComparedToEarth,
                            massComparedToEarth = phys.massComparedToEarth,
                            surfaceGravityComparedToEarth = phys.gravityComparedToEarth,
                            distanceKm = phys.distanceKm,
                            distanceLightYears = phys.distanceLightYears,
                            angularSizeArcmin = angularSizeArcmin,
                            diameterDisplayEn = phys.diameterDisplayEn,
                            diameterDisplayFa = phys.diameterDisplayFa,
                            massDisplayEn = phys.massKgDisplayEn,
                            massDisplayFa = phys.massKgDisplayFa,
                            gravityDisplayEn = phys.gravityMssDisplayEn,
                            gravityDisplayFa = phys.gravityMssDisplayFa,
                            distanceDisplayEn = phys.distanceDisplayEn,
                            distanceDisplayFa = phys.distanceDisplayFa
                        ),
                        observationalInfo = ObservationalInfo(
                            categoryEn = obj.category,
                            categoryFa = categoryFa,
                            descriptionEn = obj.descriptionEn,
                            descriptionFa = obj.descriptionFa,
                            observationTipEn = obj.observationTipEn,
                            observationTipFa = obj.observationTipFa,
                            verifiedFactsEn = factsEn,
                            verifiedFactsFa = factsFa,
                            bestViewingMonthEn = obj.bestViewingMonthEn.ifBlank { bestMonthFromEngine?.let { monthNamesEn[it - 1] } ?: "" },
                            bestViewingMonthFa = obj.bestViewingMonthFa.ifBlank { bestMonthFromEngine?.let { monthNamesFa[it - 1] } ?: "" }
                        )
                    )
                )
            }
        }

        // 3. Expanded engine deep-sky catalog (Messier + NGC + Caldwell).
        // Hand-authored DSOs above win on duplicates so tuned names/descriptions are preserved.
        val handAuthoredDsoTokens = DeepSkyCatalog.getDeepSkyObjects().flatMap { handAuthoredDsoTokens(it) }.toSet()
        for (engineObj in EngineDeepSkyCatalog.objects) {
            if (isSkippedDoubleClusterAlias(engineObj)) continue
            if (shouldSkipEngineDsoAsHandDuplicate(engineObj, handAuthoredDsoTokens)) continue
            val mapped = mapEngineDeepSkyObject(engineObj)
            if (!coreIds.contains(mapped.canonicalId)) {
                list.addOrMergeCatalogIdentity(mapped)
            }
        }

        // 4. Constellations
        for (c in ConstellationCatalog.getConstellations()) {
            val constCanonId = "const_${c.code.lowercase()}"
            if (!coreIds.contains(constCanonId) && !list.any { it.canonicalId == constCanonId }) {
                val avgRa = if (c.mainStars.isNotEmpty()) c.mainStars.map { it.first }.average() else 0.0
                val avgDec = if (c.mainStars.isNotEmpty()) c.mainStars.map { it.second }.average() else 0.0
                val dummyObj = CelestialObject(
                    id = constCanonId,
                    type = ObjectType.CONSTELLATION,
                    nameEn = "${c.nameEn} Constellation",
                    nameFa = "صورت فلکی ${c.nameFa}",
                    category = "Constellation (${c.seasonEn})",
                    descriptionEn = c.historicalInfoEn,
                    descriptionFa = c.historicalInfoFa,
                    raDeg = avgRa,
                    decDeg = avgDec,
                    magnitude = 2.0,
                    constellationEn = c.nameEn,
                    constellationFa = c.nameFa,
                    distanceLightYears = 0.0,
                    observationTipEn = "",
                    observationTipFa = ""
                )
                val phys = PhysicalData.getPhysicalProperties(dummyObj)
                val factsFa = PhysicalData.getCoolFactsFa(dummyObj)
                val factsEn = PhysicalData.getCoolFactsEn(dummyObj)

                list.addOrMergeCatalogIdentity(
                    CanonicalAstroObject(
                        canonicalId = constCanonId,
                        legacyIds = listOf(c.code.lowercase(), "constellation_${c.code.lowercase()}"),
                        type = ObjectType.CONSTELLATION,
                        nameEn = "${c.nameEn} Constellation",
                        nameFa = "صورت فلکی ${c.nameFa}",
                        searchAliasesEn = listOf(c.nameEn.lowercase(), c.code.lowercase(), c.seasonEn.lowercase()),
                        searchAliasesFa = (listOf(c.nameFa, c.seasonFa, "صورت فلکی " + c.nameFa) +
                                c.nameFa.replace("(", " ").replace(")", " ").split(" ").map { it.trim() }
                                    .filter { it.length > 1 && (c.code == "AND" && it == "آندرومدا").not() }).distinct(),
                        scientificIdentifiers = ScientificIdentifiers(
                            constellationCode = c.code,
                            constellationNameEn = c.nameEn,
                            constellationNameFa = c.nameFa
                        ),
                        staticPosition = StaticPosition(raDeg = avgRa, decDeg = avgDec),
                        physicalProperties = PhysicalProperties(
                            magnitude = 2.0,
                            diameterDisplayEn = phys.diameterDisplayEn,
                            diameterDisplayFa = phys.diameterDisplayFa,
                            massDisplayEn = phys.massKgDisplayEn,
                            massDisplayFa = phys.massKgDisplayFa,
                            gravityDisplayEn = phys.gravityMssDisplayEn,
                            gravityDisplayFa = phys.gravityMssDisplayFa,
                            distanceDisplayEn = phys.distanceDisplayEn,
                            distanceDisplayFa = phys.distanceDisplayFa
                        ),
                        observationalInfo = ObservationalInfo(
                            categoryEn = "Constellation (${c.seasonEn})",
                            categoryFa = "صورت فلکی (${c.seasonFa})",
                            descriptionEn = "${c.historicalInfoEn} Area: ${c.areaSqDeg} sq deg.",
                            descriptionFa = "${c.historicalInfoFa} مساحت: ${c.areaSqDeg} درجه مربع.",
                            verifiedFactsEn = factsEn,
                            verifiedFactsFa = factsFa
                        )
                    )
                )
            }
        }

        list
    }

    // Map legacy IDs and aliases to canonical object IDs
    private val legacyToCanonicalMap: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        for (obj in extendedCanonicalObjectsList) {
            map[obj.canonicalId.lowercase()] = obj.canonicalId
            for (legacyId in obj.legacyIds) {
                map[legacyId.lowercase()] = obj.canonicalId
            }
            for (aliasEn in obj.searchAliasesEn) {
                if (aliasEn.isNotBlank()) map[aliasEn.lowercase()] = obj.canonicalId
            }
            for (aliasFa in obj.searchAliasesFa) {
                if (aliasFa.isNotBlank()) map[aliasFa.lowercase()] = obj.canonicalId
            }
        }
        map
    }

    /**
     * Resolves any input ID, legacy ID, or alias to its canonical ID.
     */
    fun resolveCanonicalId(idOrLegacyId: String): String {
        val clean = idOrLegacyId.trim().lowercase()
        val normalized = normalizedCatalogToken(idOrLegacyId)
        return legacyToCanonicalMap[clean]
            ?: normalized?.let { legacyToCanonicalMap[it] }
            ?: idOrLegacyId
    }

    /**
     * Gets a CanonicalAstroObject by canonical ID or legacy ID.
     */
    fun getCanonicalObject(idOrLegacyId: String): CanonicalAstroObject? {
        val canonicalId = resolveCanonicalId(idOrLegacyId)
        return extendedCanonicalObjectsList.find { it.canonicalId == canonicalId }
    }

    /**
     * Gets all canonical objects in the master catalog.
     */
    fun getAllCanonicalObjects(): List<CanonicalAstroObject> {
        return extendedCanonicalObjectsList
    }

    fun getDeepSkyMergeReport(): DeepSkyMergeReport {
        val originalDsoCount = DeepSkyCatalog.getDeepSkyObjects().size
        val handTokens = DeepSkyCatalog.getDeepSkyObjects().flatMap { handAuthoredDsoTokens(it) }.toSet()
        val skippedCanonical = EngineDeepSkyCatalog.objects.count { shouldSkipEngineDsoAsHandDuplicate(it, handTokens) }
        val skippedEngineDuplicate = EngineDeepSkyCatalog.objects.count { isSkippedDoubleClusterAlias(it) }
        val finalDsoCount = extendedCanonicalObjectsList.count { isCanonicalDeepSkyType(it.type) }
        val finalHandDsoCount = DeepSkyCatalog.getDeepSkyObjects().count { it.id != HAND_DOUBLE_CLUSTER_ID }
        val coreDeepSkyCount = allCanonicalObjectsList.count { isCanonicalDeepSkyType(it.type) }
        val inserted = finalDsoCount - finalHandDsoCount - coreDeepSkyCount
        val mergedCatalogDuplicate = EngineDeepSkyCatalog.objects.size - skippedCanonical - skippedEngineDuplicate - inserted
        val duplicateIds = extendedCanonicalObjectsList
            .map { it.canonicalId }
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .toList()
        return DeepSkyMergeReport(
            engineCatalogCount = EngineDeepSkyCatalog.objects.size,
            insertedCount = inserted,
            skippedCanonicalDuplicateCount = skippedCanonical,
            skippedEngineDuplicateCount = skippedEngineDuplicate,
            mergedCatalogDuplicateCount = mergedCatalogDuplicate,
            originalHandAuthoredDsoCount = originalDsoCount,
            finalDeepSkyCount = finalDsoCount,
            finalCatalogCount = extendedCanonicalObjectsList.size,
            duplicateCanonicalIds = duplicateIds
        )
    }

    /**
     * Retrieves child objects for a given parent canonical ID (e.g., moons of Jupiter).
     */
    fun getChildrenOf(parentIdOrLegacyId: String): List<CanonicalAstroObject> {
        val pId = resolveCanonicalId(parentIdOrLegacyId)
        return extendedCanonicalObjectsList.filter { it.parentId == pId }
    }

    /**
     * Converts a CanonicalAstroObject to legacy CelestialObject representation without data duplication.
     */
    fun toCelestialObject(
        canonicalObj: CanonicalAstroObject,
        dynamicRa: Double = canonicalObj.staticPosition?.raDeg ?: 0.0,
        dynamicDec: Double = canonicalObj.staticPosition?.decDeg ?: 0.0,
        dynamicMag: Double = canonicalObj.physicalProperties.magnitude,
        dynamicDistanceLy: Double? = null
    ): CelestialObject {
        val distanceLy = dynamicDistanceLy
            ?: canonicalObj.staticPosition?.distanceLightYears
            ?: canonicalObj.physicalProperties.distanceLightYears
            ?: (canonicalObj.physicalProperties.distanceKm?.let { it / 9.4607304725808e12 })
            ?: 0.0
        val fallbackConstellationNames = canonicalObj.scientificIdentifiers.constellationCode
            .takeIf { it.isNotBlank() }
            ?.let { constellationNamesForCode(it) }
            ?: ("" to "")
        val constellationEn = canonicalObj.scientificIdentifiers.constellationNameEn.ifBlank { fallbackConstellationNames.first }
        val constellationFa = canonicalObj.scientificIdentifiers.constellationNameFa.ifBlank { fallbackConstellationNames.second }

        return CelestialObject(
            id = canonicalObj.canonicalId,
            type = canonicalObj.type,
            nameEn = canonicalObj.nameEn,
            nameFa = canonicalObj.nameFa,
            raDeg = dynamicRa,
            decDeg = dynamicDec,
            magnitude = dynamicMag,
            constellationEn = constellationEn,
            constellationFa = constellationFa,
            distanceLightYears = distanceLy,
            category = canonicalObj.observationalInfo.categoryEn,
            descriptionEn = canonicalObj.observationalInfo.descriptionEn,
            descriptionFa = canonicalObj.observationalInfo.descriptionFa,
            observationTipEn = canonicalObj.observationalInfo.observationTipEn,
            observationTipFa = canonicalObj.observationalInfo.observationTipFa,
            spectralType = canonicalObj.scientificIdentifiers.spectralType,
            hipId = canonicalObj.scientificIdentifiers.hipId,
            hdId = canonicalObj.scientificIdentifiers.hdId,
            bayerDesignation = canonicalObj.scientificIdentifiers.bayerDesignation,
            flamsteedNumber = canonicalObj.scientificIdentifiers.flamsteedNumber,
            temperatureK = canonicalObj.physicalProperties.temperatureK,
            catalogDesignations = canonicalObj.scientificIdentifiers.catalogDesignations,
            angularSizeArcmin = canonicalObj.physicalProperties.angularSizeArcmin,
            constellationCode = canonicalObj.scientificIdentifiers.constellationCode,
            bestViewingMonthEn = canonicalObj.observationalInfo.bestViewingMonthEn,
            bestViewingMonthFa = canonicalObj.observationalInfo.bestViewingMonthFa,
            funFactsEn = if (canonicalObj.observationalInfo.funFactsEn.isNotEmpty()) canonicalObj.observationalInfo.funFactsEn else canonicalObj.observationalInfo.verifiedFactsEn,
            funFactsFa = if (canonicalObj.observationalInfo.funFactsFa.isNotEmpty()) canonicalObj.observationalInfo.funFactsFa else canonicalObj.observationalInfo.verifiedFactsFa
        )
    }

    /**
     * Integrity validation report data structure for Phase 2 verification.
     */
    data class IntegrityReport(
        val totalCanonicalObjects: Int,
        val duplicateCanonicalIds: List<String>,
        val resolvedLegacyAliasesCount: Int,
        val issResolvedCanonicalId: String,
        val sunResolvedCanonicalId: String,
        val moonResolvedCanonicalId: String,
        val sgrAResolvedCanonicalId: String,
        val ioResolvedCanonicalId: String,
        val isPassed: Boolean
    )

    /**
     * Validates that canonical IDs are 100% unique, legacy IDs resolve accurately, and no duplicate identities exist.
     */
    fun verifyIntegrity(): IntegrityReport {
        val canonicalIds = extendedCanonicalObjectsList.map { it.canonicalId }
        val duplicates = canonicalIds.groupBy { it }.filter { it.value.size > 1 }.keys.toList()

        val issRes = resolveCanonicalId("sat_iss")
        val sunRes = resolveCanonicalId("sun_sol")
        val moonRes = resolveCanonicalId("moon_main")
        val sgrARes = resolveCanonicalId("gal_center")
        val ioRes = resolveCanonicalId("jupiter_io")

        val passed = duplicates.isEmpty() &&
                issRes == "sat_25544" &&
                sunRes == "sun" &&
                moonRes == "moon" &&
                sgrARes == "sagittarius_a_star" &&
                ioRes == "jup_io"

        return IntegrityReport(
            totalCanonicalObjects = extendedCanonicalObjectsList.size,
            duplicateCanonicalIds = duplicates,
            resolvedLegacyAliasesCount = legacyToCanonicalMap.size,
            issResolvedCanonicalId = issRes,
            sunResolvedCanonicalId = sunRes,
            moonResolvedCanonicalId = moonRes,
            sgrAResolvedCanonicalId = sgrARes,
            ioResolvedCanonicalId = ioRes,
            isPassed = passed
        )
    }
}
