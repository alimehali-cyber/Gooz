package com.alijafari.red.astronomy.data.catalog

import com.alijafari.red.astronomy.domain.CelestialObject
import kotlin.math.abs

data class PhysicalProperties(
    val diameterKm: Double? = null,
    val diameterDisplayFa: String,
    val diameterDisplayEn: String,
    val massKgDisplayFa: String,
    val massKgDisplayEn: String,
    val gravityMssDisplayFa: String,
    val gravityMssDisplayEn: String,
    val distanceDisplayFa: String,
    val distanceDisplayEn: String,
    val diameterComparedToEarth: Double? = null,
    val massComparedToEarth: Double? = null,
    val gravityComparedToEarth: Double? = null,
    val distanceKm: Double? = null,
    val distanceLightYears: Double? = null
)

object PhysicalData {

    const val EARTH_DIAMETER_KM = 12742.0
    const val EARTH_MASS_KG = 5.972e24
    const val EARTH_GRAVITY_MSS = 9.80665

    private val SUN_PROPS = PhysicalProperties(
        diameterKm = 1392700.0,
        diameterComparedToEarth = 109.3,
        diameterDisplayFa = "۱,۳۹۲,۷۰۰ کیلومتر (۱۰۹.۳ برابر زمین)",
        diameterDisplayEn = "1,392,700 km (109.3× Earth)",
        massKgDisplayFa = "۱.۹۸۹ × ۱۰³⁰ کیلوگرم (۳۳۳,۰۰۰ برابر زمین)",
        massKgDisplayEn = "1.989 × 10³⁰ kg (333,000× Earth)",
        massComparedToEarth = 333000.0,
        gravityMssDisplayFa = "۲۷۴.۰ متر بر مجذور ثانیه (۲۷.۹۴ برابر زمین)",
        gravityMssDisplayEn = "274.0 m/s² (27.94× Earth)",
        gravityComparedToEarth = 27.94,
        distanceKm = 149597870.7,
        distanceLightYears = 0.00001581,
        distanceDisplayFa = "۱۴۹,۶۰۰,۰۰۰ کیلومتر | ۰.۰۰۰۰۱۵۸ سال نوری (۱ واحد نجومی - متغیر مداری)",
        distanceDisplayEn = "149,600,000 km | 0.0000158 light-years (1.0 AU - dynamic orbital)"
    )

    private val MOON_PROPS = PhysicalProperties(
        diameterKm = 3474.8,
        diameterComparedToEarth = 0.2727,
        diameterDisplayFa = "۳,۴۷۵ کیلومتر (۰.۲۷۳ برابر زمین)",
        diameterDisplayEn = "3,475 km (0.273× Earth)",
        massKgDisplayFa = "۷.۳۴۲ × ۱۰²² کیلوگرم (۰.۰۱۲۳ برابر زمین)",
        massKgDisplayEn = "7.342 × 10²² kg (0.0123× Earth)",
        massComparedToEarth = 0.0123,
        gravityMssDisplayFa = "۱.۶۲۲ متر بر مجذور ثانیه (۰.۱۶۵ برابر زمین)",
        gravityMssDisplayEn = "1.622 m/s² (0.165× Earth)",
        gravityComparedToEarth = 0.1654,
        distanceKm = 384400.0,
        distanceLightYears = 0.0000000406,
        distanceDisplayFa = "۳۸۴,۴۰۰ کیلومتر | ۰.۰۰۰۰۰۰۰۴ سال نوری (متغیر در مدار بیضوی)",
        distanceDisplayEn = "384,400 km | 0.00000004 light-years (dynamic elliptical orbit)"
    )

    private val ISS_PROPS = PhysicalProperties(
        diameterKm = 0.109,
        diameterComparedToEarth = 0.00000855,
        diameterDisplayFa = "۱۰۹ متر (اندازه یک زمین فوتبال)",
        diameterDisplayEn = "109 meters (Football field size)",
        massKgDisplayFa = "۴۵۰,۰۰۰ کیلوگرم (۴۵۰ تن)",
        massKgDisplayEn = "450,000 kg (450 tonnes)",
        massComparedToEarth = 7.53e-20,
        gravityMssDisplayFa = "۸.۷ متر بر مجذور ثانیه در مدار (بی‌وزنی ظاهری / سقوط آزاد)",
        gravityMssDisplayEn = "8.7 m/s² orbital (Microgravity / free-fall environment)",
        gravityComparedToEarth = 0.887,
        distanceKm = 420.0,
        distanceLightYears = 4.44e-11,
        distanceDisplayFa = "۴۲۰ کیلومتر (ارتفاع مداری متغیر از سطح زمین)",
        distanceDisplayEn = "420 km (Dynamic orbital altitude above Earth)"
    )

    private val MERCURY_PROPS = PhysicalProperties(
        diameterKm = 4879.4,
        diameterComparedToEarth = 0.3829,
        diameterDisplayFa = "۴,۸۷۹ کیلومتر (۰.۳۸۳ برابر زمین)",
        diameterDisplayEn = "4,879 km (0.383× Earth)",
        massKgDisplayFa = "۳.۳۰۱ × ۱۰²³ کیلوگرم (۰.۰۵۵ برابر زمین)",
        massKgDisplayEn = "3.301 × 10²³ kg (0.055× Earth)",
        massComparedToEarth = 0.0553,
        gravityMssDisplayFa = "۳.۷۰ متر بر مجذور ثانیه (۰.۳۷۷ برابر زمین)",
        gravityMssDisplayEn = "3.70 m/s² (0.377× Earth)",
        gravityComparedToEarth = 0.377,
        distanceKm = null, // Dynamic planetary ephemeris
        distanceLightYears = null,
        distanceDisplayFa = "۷۷ تا ۲۲۲ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "77M to 222M km (Dynamic real-time ephemeris)"
    )

    private val VENUS_PROPS = PhysicalProperties(
        diameterKm = 12103.6,
        diameterComparedToEarth = 0.9499,
        diameterDisplayFa = "۱۲,۱۰۴ کیلومتر (۰.۹۵۰ برابر زمین)",
        diameterDisplayEn = "12,104 km (0.950× Earth)",
        massKgDisplayFa = "۴.۸۶۷ × ۱۰²⁴ کیلوگرم (۰.۸۱۵ برابر زمین)",
        massKgDisplayEn = "4.867 × 10²⁴ kg (0.815× Earth)",
        massComparedToEarth = 0.8150,
        gravityMssDisplayFa = "۸.۸۷ متر بر مجذور ثانیه (۰.۹۰۴ برابر زمین)",
        gravityMssDisplayEn = "8.87 m/s² (0.904× Earth)",
        gravityComparedToEarth = 0.9045,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۳۸ تا ۲۶۱ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "38M to 261M km (Dynamic real-time ephemeris)"
    )

    private val EARTH_PROPS = PhysicalProperties(
        diameterKm = 12742.0,
        diameterComparedToEarth = 1.00,
        diameterDisplayFa = "۱۲,۷۴۲ کیلومتر (۱.۰۰ برابر زمین)",
        diameterDisplayEn = "12,742 km (1.00× Earth)",
        massKgDisplayFa = "۵.۹۷۲ × ۱۰²⁴ کیلوگرم (۱.۰۰ برابر زمین)",
        massKgDisplayEn = "5.972 × 10²⁴ kg (1.00× Earth)",
        massComparedToEarth = 1.00,
        gravityMssDisplayFa = "۹.۸۱ متر بر مجذور ثانیه (۱.۰۰ برابر زمین)",
        gravityMssDisplayEn = "9.81 m/s² (1.00× Earth)",
        gravityComparedToEarth = 1.00,
        distanceKm = 0.0,
        distanceLightYears = 0.0,
        distanceDisplayFa = "۰ کیلومتر (مبدأ رصدی زیر پای شما)",
        distanceDisplayEn = "0 km (Observational origin beneath you)"
    )

    private val MARS_PROPS = PhysicalProperties(
        diameterKm = 6779.0,
        diameterComparedToEarth = 0.5320,
        diameterDisplayFa = "۶,۷۷۹ کیلومتر (۰.۵۳۲ برابر زمین)",
        diameterDisplayEn = "6,779 km (0.532× Earth)",
        massKgDisplayFa = "۶.۴۱۷ × ۱۰²³ کیلوگرم (۰.۱۰۷ برابر زمین)",
        massKgDisplayEn = "6.417 × 10²³ kg (0.107× Earth)",
        massComparedToEarth = 0.1074,
        gravityMssDisplayFa = "۳.۷۲ متر بر مجذور ثانیه (۰.۳۷۹ برابر زمین)",
        gravityMssDisplayEn = "3.72 m/s² (0.379× Earth)",
        gravityComparedToEarth = 0.3794,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۵۵ تا ۴۰۱ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "55M to 401M km (Dynamic real-time ephemeris)"
    )

    private val JUPITER_PROPS = PhysicalProperties(
        diameterKm = 139822.0,
        diameterComparedToEarth = 10.973,
        diameterDisplayFa = "۱۳۹,۸۲۲ کیلومتر (۱۰.۹۷ برابر زمین)",
        diameterDisplayEn = "139,822 km (10.97× Earth)",
        massKgDisplayFa = "۱.۸۹۸ × ۱۰²⁷ کیلوگرم (۳۱۷.۸ برابر زمین)",
        massKgDisplayEn = "1.898 × 10²⁷ kg (317.8× Earth)",
        massComparedToEarth = 317.83,
        gravityMssDisplayFa = "۲۴.۷۹ متر بر مجذور ثانیه (۲.۵۲۸ برابر زمین)",
        gravityMssDisplayEn = "24.79 m/s² (2.528× Earth)",
        gravityComparedToEarth = 2.528,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۵۸۸ تا ۹۶۸ میلیون کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "588M to 968M km (Dynamic real-time ephemeris)"
    )

    private val SATURN_PROPS = PhysicalProperties(
        diameterKm = 116460.0,
        diameterComparedToEarth = 9.140,
        diameterDisplayFa = "۱۱۶,۴۶۰ کیلومتر (۹.۱۴ برابر زمین)",
        diameterDisplayEn = "116,460 km (9.14× Earth)",
        massKgDisplayFa = "۵.۶۸۳ × ۱۰²۶ کیلوگرم (۹۵.۲ برابر زمین)",
        massKgDisplayEn = "5.683 × 10²⁶ kg (95.2× Earth)",
        massComparedToEarth = 95.16,
        gravityMssDisplayFa = "۱۰.۴۴ متر بر مجذور ثانیه (۱.۰۶۵ برابر زمین)",
        gravityMssDisplayEn = "10.44 m/s² (1.065× Earth)",
        gravityComparedToEarth = 1.065,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۱.۲ تا ۱.۶۶ میلیارد کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "1.2B to 1.66B km (Dynamic real-time ephemeris)"
    )

    private val URANUS_PROPS = PhysicalProperties(
        diameterKm = 50724.0,
        diameterComparedToEarth = 3.981,
        diameterDisplayFa = "۵۰,۷۲۴ کیلومتر (۳.۹۸ برابر زمین)",
        diameterDisplayEn = "50,724 km (3.98× Earth)",
        massKgDisplayFa = "۸.۶۸۱ × ۱۰²۵ کیلوگرم (۱۴.۵۴ برابر زمین)",
        massKgDisplayEn = "8.681 × 10²⁵ kg (14.54× Earth)",
        massComparedToEarth = 14.536,
        gravityMssDisplayFa = "۸.۶۹ متر بر مجذور ثانیه (۰.۸۸۶ برابر زمین)",
        gravityMssDisplayEn = "8.69 m/s² (0.886× Earth)",
        gravityComparedToEarth = 0.886,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۲.۶ تا ۳.۱۵ میلیارد کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "2.6B to 3.15B km (Dynamic real-time ephemeris)"
    )

    private val NEPTUNE_PROPS = PhysicalProperties(
        diameterKm = 49244.0,
        diameterComparedToEarth = 3.865,
        diameterDisplayFa = "۴۹,۲۴۴ کیلومتر (۳.۸۶ برابر زمین)",
        diameterDisplayEn = "49,244 km (3.86× Earth)",
        massKgDisplayFa = "۱.۰۲۴ × ۱۰²۶ کیلوگرم (۱۷.۱۵ برابر زمین)",
        massKgDisplayEn = "1.024 × 10²⁶ kg (17.15× Earth)",
        massComparedToEarth = 17.147,
        gravityMssDisplayFa = "۱۱.۱۵ متر بر مجذور ثانیه (۱.۱۳۷ برابر زمین)",
        gravityMssDisplayEn = "11.15 m/s² (1.137× Earth)",
        gravityComparedToEarth = 1.137,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۴.۳ تا ۴.۷ میلیارد کیلومتر (محاسبه دینامیکی لحظه‌ای)",
        distanceDisplayEn = "4.3B to 4.7B km (Dynamic real-time ephemeris)"
    )

    private val PLUTO_PROPS = PhysicalProperties(
        diameterKm = 2376.6,
        diameterComparedToEarth = 0.1865,
        diameterDisplayFa = "۲,۳۷۷ کیلومتر (۰.۱۸۶ برابر زمین)",
        diameterDisplayEn = "2,377 km (0.186× Earth)",
        massKgDisplayFa = "۱.۳۰۳ × ۱۰²۲ کیلوگرم (۰.۰۰۲۲ برابر زمین)",
        massKgDisplayEn = "1.303 × 10²² kg (0.0022× Earth)",
        massComparedToEarth = 0.00218,
        gravityMssDisplayFa = "۰.۶۲ متر بر مجذور ثانیه (۰.۰۶۳ برابر زمین)",
        gravityMssDisplayEn = "0.62 m/s² (0.063× Earth)",
        gravityComparedToEarth = 0.0632,
        distanceKm = null,
        distanceLightYears = null,
        distanceDisplayFa = "۴.۴ تا ۷.۵ میلیارد کیلومتر (مدار به شدت بیضوی)",
        distanceDisplayEn = "4.4B to 7.5B km (Highly eccentric orbit)"
    )

    private val IO_PROPS = PhysicalProperties(
        diameterKm = 3643.2,
        diameterComparedToEarth = 0.2859,
        diameterDisplayFa = "۳,۶۴۳ کیلومتر (۰.۲۸۶ برابر زمین)",
        diameterDisplayEn = "3,643 km (0.286× Earth)",
        massKgDisplayFa = "۸.۹۳۲ × ۱۰²² کیلوگرم (۰.۰۱۵۰ برابر زمین)",
        massKgDisplayEn = "8.932 × 10²² kg (0.0150× Earth)",
        massComparedToEarth = 0.01495,
        gravityMssDisplayFa = "۱.۷۹۶ متر بر مجذور ثانیه (۰.۱۸۳ برابر زمین)",
        gravityMssDisplayEn = "1.796 m/s² (0.183× Earth)",
        gravityComparedToEarth = 0.1831,
        distanceKm = 421700.0, // Orbital radius from Jupiter center
        distanceLightYears = null,
        distanceDisplayFa = "۴۲۱,۷۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)",
        distanceDisplayEn = "421,700 km from Jupiter (Earth distance dynamic via Jupiter)"
    )

    private val EUROPA_PROPS = PhysicalProperties(
        diameterKm = 3121.6,
        diameterComparedToEarth = 0.2450,
        diameterDisplayFa = "۳,۱۲۲ کیلومتر (۰.۲۴۵ برابر زمین)",
        diameterDisplayEn = "3,122 km (0.245× Earth)",
        massKgDisplayFa = "۴.۸۰۰ × ۱۰²² کیلوگرم (۰.۰۰۸۰ برابر زمین)",
        massKgDisplayEn = "4.800 × 10²² kg (0.0080× Earth)",
        massComparedToEarth = 0.008037,
        gravityMssDisplayFa = "۱.۳۱۵ متر بر مجذور ثانیه (۰.۱۳۴ برابر زمین)",
        gravityMssDisplayEn = "1.315 m/s² (0.134× Earth)",
        gravityComparedToEarth = 0.1341,
        distanceKm = 670900.0,
        distanceLightYears = null,
        distanceDisplayFa = "۶۷۰,۹۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)",
        distanceDisplayEn = "670,900 km from Jupiter (Earth distance dynamic via Jupiter)"
    )

    private val GANYMEDE_PROPS = PhysicalProperties(
        diameterKm = 5268.2,
        diameterComparedToEarth = 0.4135,
        diameterDisplayFa = "۵,۲۶۸ کیلومتر (۰.۴۱۴ برابر زمین / بزرگ‌تر از عطارد)",
        diameterDisplayEn = "5,268 km (0.414× Earth / Larger than Mercury)",
        massKgDisplayFa = "۱.۴۸۲ × ۱۰²۳ کیلوگرم (۰.۰۲۴۸ برابر زمین)",
        massKgDisplayEn = "1.482 × 10²³ kg (0.0248× Earth)",
        massComparedToEarth = 0.02481,
        gravityMssDisplayFa = "۱.۴۲۸ متر بر مجذور ثانیه (۰.۱۴۶ برابر زمین)",
        gravityMssDisplayEn = "1.428 m/s² (0.146× Earth)",
        gravityComparedToEarth = 0.1456,
        distanceKm = 1070400.0,
        distanceLightYears = null,
        distanceDisplayFa = "۱,۰۷۰,۴۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)",
        distanceDisplayEn = "1,070,400 km from Jupiter (Earth distance dynamic via Jupiter)"
    )

    private val CALLISTO_PROPS = PhysicalProperties(
        diameterKm = 4820.6,
        diameterComparedToEarth = 0.3783,
        diameterDisplayFa = "۴,۸۲۱ کیلومتر (۰.۳۷۸ برابر زمین)",
        diameterDisplayEn = "4,821 km (0.378× Earth)",
        massKgDisplayFa = "۱.۰۷۶ × ۱۰²۳ کیلوگرم (۰.۰۱۸۰ برابر زمین)",
        massKgDisplayEn = "1.076 × 10²³ kg (0.0180× Earth)",
        massComparedToEarth = 0.01802,
        gravityMssDisplayFa = "۱.۲۳۵ متر بر مجذور ثانیه (۰.۱۲۶ برابر زمین)",
        gravityMssDisplayEn = "1.235 m/s² (0.126× Earth)",
        gravityComparedToEarth = 0.1259,
        distanceKm = 1882700.0,
        distanceLightYears = null,
        distanceDisplayFa = "۱,۸۸۲,۷۰۰ کیلومتر از مشتری (فاصله از زمین وابسته به مشتری)",
        distanceDisplayEn = "1,882,700 km from Jupiter (Earth distance dynamic via Jupiter)"
    )

    private val ELARA_PROPS = PhysicalProperties(
        diameterKm = 86.0,
        diameterComparedToEarth = 0.00675,
        diameterDisplayFa = "۸۶ کیلومتر قطر (قمر نامنظم هیپالیا)",
        diameterDisplayEn = "86 km diameter (Irregular Himalia Group)",
        massKgDisplayFa = "۸.۷ × ۱۰¹۷ کیلوگرم (۱.۴۶ × ۱۰⁻⁷ برابر زمین)",
        massKgDisplayEn = "8.7 × 10¹⁷ kg (1.46 × 10⁻⁷× Earth)",
        massComparedToEarth = 1.46e-7,
        gravityMssDisplayFa = "۰.۰۳۱ متر بر مجذور ثانیه (۰.۰۰۳۲ برابر زمین)",
        gravityMssDisplayEn = "0.031 m/s² (0.0032× Earth)",
        gravityComparedToEarth = 0.00316,
        distanceKm = 11740000.0,
        distanceLightYears = null,
        distanceDisplayFa = "۱۱,۷۴۰,۰۰۰ کیلومتر از مشتری",
        distanceDisplayEn = "11,740,000 km from Jupiter"
    )

    private val SIRIUS_PROPS = PhysicalProperties(
        diameterKm = 2382000.0,
        diameterComparedToEarth = 186.9,
        diameterDisplayFa = "۲,۳۸۲,۰۰۰ کیلومتر (۱.۷۱ برابر خورشید / ۱۸۷ برابر زمین)",
        diameterDisplayEn = "2,382,000 km (1.71× Sun / 187× Earth)",
        massKgDisplayFa = "۴.۰۱۸ × ۱۰³⁰ کیلوگرم (۲.۰۶ برابر خورشید / ۶۷۳,۰۰۰ برابر زمین)",
        massKgDisplayEn = "4.018 × 10³⁰ kg (2.06× Sun / 673,000× Earth)",
        massComparedToEarth = 672800.0,
        gravityMssDisplayFa = "۲۴۰ متر بر مجذور ثانیه (۲۴.۵ برابر زمین)",
        gravityMssDisplayEn = "240 m/s² (24.5× Earth)",
        gravityComparedToEarth = 24.47,
        distanceKm = 8.146e13,
        distanceLightYears = 8.611,
        distanceDisplayFa = "۸.۶۱ سال نوری (۸۱.۵ تریلیون کیلومتر)",
        distanceDisplayEn = "8.61 light-years (81.5 trillion km)"
    )

    private val VEGA_PROPS = PhysicalProperties(
        diameterKm = 3286000.0,
        diameterComparedToEarth = 257.9,
        diameterDisplayFa = "۳,۲۸۶,۰۰۰ کیلومتر (۲.۳۶ برابر خورشید / ۲۵۸ برابر زمین)",
        diameterDisplayEn = "3,286,000 km (2.36× Sun / 258× Earth)",
        massKgDisplayFa = "۴.۲۵۰ × ۱۰³⁰ کیلوگرم (۲.۱۳ برابر خورشید / ۷۱۱,۰۰۰ برابر زمین)",
        massKgDisplayEn = "4.250 × 10³⁰ kg (2.13× Sun / 711,000× Earth)",
        massComparedToEarth = 710900.0,
        gravityMssDisplayFa = "۱۵۸ متر بر مجذور ثانیه (۱۶.۱ برابر زمین)",
        gravityMssDisplayEn = "158 m/s² (16.1× Earth)",
        gravityComparedToEarth = 16.11,
        distanceKm = 2.369e14,
        distanceLightYears = 25.04,
        distanceDisplayFa = "۲۵.۰۴ سال نوری (۲۳۶.۹ تریلیون کیلومتر)",
        distanceDisplayEn = "25.04 light-years (236.9 trillion km)"
    )

    private val BETELGEUSE_PROPS = PhysicalProperties(
        diameterKm = 1063000000.0,
        diameterComparedToEarth = 83400.0,
        diameterDisplayFa = "۱,۰۶۳,۰۰۰,۰۰۰ کیلومتر (۷۶۴ برابر خورشید / ۸۳,۴۰۰ برابر زمین)",
        diameterDisplayEn = "1,063,000,000 km (764× Sun / 83,400× Earth)",
        massKgDisplayFa = "۳.۲۸۰ × ۱۰³¹ کیلوگرم (۱۶.۵ برابر خورشید / ۵.۵ میلیون برابر زمین)",
        massKgDisplayEn = "3.280 × 10³¹ kg (16.5× Sun / 5.5M× Earth)",
        massComparedToEarth = 5490000.0,
        gravityMssDisplayFa = "۰.۰۰۳ متر بر مجذور ثانیه (ابرغول بسیار منبسط)",
        gravityMssDisplayEn = "0.003 m/s² (Ultra-low density red supergiant)",
        gravityComparedToEarth = 0.0003,
        distanceKm = 6.078e15,
        distanceLightYears = 642.5,
        distanceDisplayFa = "۶۴۲.۵ سال نوری (۶,۰۷۸ تریلیون کیلومتر)",
        distanceDisplayEn = "642.5 light-years (6,078 trillion km)"
    )

    private val POLARIS_PROPS = PhysicalProperties(
        diameterKm = 64000000.0,
        diameterComparedToEarth = 5020.0,
        diameterDisplayFa = "۶۴,۰۰۰,۰۰۰ کیلومتر (۴۶ برابر خورشید / ۵,۰۲۰ برابر زمین)",
        diameterDisplayEn = "64,000,000 km (46× Sun / 5,020× Earth)",
        massKgDisplayFa = "۱.۰۷۰ × ۱۰³¹ کیلوگرم (۵.۴ برابر خورشید / ۱.۸ میلیون برابر زمین)",
        massKgDisplayEn = "1.070 × 10³¹ kg (5.4× Sun / 1.8M× Earth)",
        massComparedToEarth = 1790000.0,
        gravityMssDisplayFa = "۷.۰ متر بر مجذور ثانیه (۰.۷۱ برابر زمین)",
        gravityMssDisplayEn = "7.0 m/s² (0.71× Earth)",
        gravityComparedToEarth = 0.71,
        distanceKm = 4.096e15,
        distanceLightYears = 433.0,
        distanceDisplayFa = "۴۳۳ سال نوری (۴,۰۹۶ تریلیون کیلومتر)",
        distanceDisplayEn = "433 light-years (4,096 trillion km)"
    )

    private val ANDROMEDA_PROPS = PhysicalProperties(
        diameterKm = 2.08e18,
        diameterComparedToEarth = null, // Scientifically not applicable (diffuse galaxy)
        diameterDisplayFa = "۲۲۰,۰۰۰ سال نوری قطر (۲.۲ برابر کهکشان راه شیری)",
        diameterDisplayEn = "220,000 light-years diameter (2.2× Milky Way)",
        massKgDisplayFa = "۲.۹۸ × ۱۰⁴۲ کیلوگرم (۱,۵۰۰ میلیارد برابر جرم خورشید)",
        massKgDisplayEn = "2.98 × 10⁴² kg (1.5 trillion Solar Masses)",
        massComparedToEarth = null,
        gravityMssDisplayFa = "پتانسیل گرانشی کهکشانی (دارای بیش از ۱ تریلیون ستاره)",
        gravityMssDisplayEn = "Galactic gravitational potential (Over 1 trillion stars)",
        gravityComparedToEarth = null,
        distanceKm = 2.365e19,
        distanceLightYears = 2500000.0,
        distanceDisplayFa = "۲,۵۰۰,۰۰۰ سال نوری (۲.۳۶ × ۱۰¹۹ کیلومتر)",
        distanceDisplayEn = "2,500,000 light-years (2.36 × 10¹⁹ km)"
    )

    private val ORION_NEBULA_PROPS = PhysicalProperties(
        diameterKm = 2.27e14,
        diameterComparedToEarth = null,
        diameterDisplayFa = "۲۴ سال نوری پهنای سحابی (زایشگاه فعال ستارگان)",
        diameterDisplayEn = "24 light-years diameter (Active stellar nursery)",
        massKgDisplayFa = "۳.۹۸ × ۱۰³۳ کیلوگرم (۲,۰۰۰ برابر جرم خورشید)",
        massKgDisplayEn = "3.98 × 10³³ kg (2,000 Solar Masses)",
        massComparedToEarth = null,
        gravityMssDisplayFa = "ابرهای گاز هیدروژن یونیزه در حال تراکم گرانشی",
        gravityMssDisplayEn = "Ionized hydrogen gas clouds collapsing gravitationally",
        gravityComparedToEarth = null,
        distanceKm = 1.271e16,
        distanceLightYears = 1344.0,
        distanceDisplayFa = "۱,۳۴۴ سال نوری (۱.۲۷ × ۱۰¹۶ کیلومتر)",
        distanceDisplayEn = "1,344 light-years (1.27 × 10¹⁶ km)"
    )

    private val PLEIADES_PROPS = PhysicalProperties(
        diameterKm = 1.65e14,
        diameterComparedToEarth = null,
        diameterDisplayFa = "۱۷.۵ سال نوری پهنای خوشه (شامل بیش از ۱,۰۰۰ ستاره)",
        diameterDisplayEn = "17.5 light-years cluster span (Over 1,000 stars)",
        massKgDisplayFa = "۱.۵۹ × ۱۰³۳ کیلوگرم (۸۰۰ برابر جرم خورشید)",
        massKgDisplayEn = "1.59 × 10³³ kg (800 Solar Masses)",
        massComparedToEarth = null,
        gravityMssDisplayFa = "پیوند گرانشی متقابل خوشه باز ستاره‌ای جوان",
        gravityMssDisplayEn = "Gravitationally bound young open star cluster",
        gravityComparedToEarth = null,
        distanceKm = 4.20e15,
        distanceLightYears = 444.0,
        distanceDisplayFa = "۴۴۴ سال نوری (۴.۲ × ۱۰¹۵ کیلومتر)",
        distanceDisplayEn = "444 light-years (4.2 × 10¹⁵ km)"
    )

    private val MILKY_WAY_PROPS = PhysicalProperties(
        diameterKm = 9.46e17,
        diameterComparedToEarth = null,
        diameterDisplayFa = "۱۰۰,۰۰۰ سال نوری قطر (کهکشان مارپیچی میله‌ای)",
        diameterDisplayEn = "100,000 light-years diameter (Barred spiral galaxy)",
        massKgDisplayFa = "۲.۹۸ × ۱۰⁴۲ کیلوگرم (۱,۵۰۰ میلیارد برابر جرم خورشید)",
        massKgDisplayEn = "2.98 × 10⁴² kg (1.5 trillion Solar Masses)",
        massComparedToEarth = null,
        gravityMssDisplayFa = "پتانسیل گرانشی کل کهکشان با ۴ میلیون خورشید در مرکز",
        gravityMssDisplayEn = "Total galactic potential with 4M Solar Masses at core",
        gravityComparedToEarth = null,
        distanceKm = 2.46e17,
        distanceLightYears = 26000.0,
        distanceDisplayFa = "۲۶,۰۰۰ سال نوری فاصله منظومه شمسی تا مرکز کهکشان",
        distanceDisplayEn = "26,000 light-years from Solar System to Galactic Core"
    )

    private val SGRA_PROPS = PhysicalProperties(
        diameterKm = 5.18e7, // Shadow diameter ~52M km, Schwarzschild diameter ~24.6M km
        diameterComparedToEarth = null,
        diameterDisplayFa = "افق رویداد: ~۲۵ تا ۵۲ میلیون کیلومتر (۰.۱۶ تا ۰.۳۵ واحد نجومی)",
        diameterDisplayEn = "Event Horizon: ~25M to 52M km (0.16 to 0.35 AU)",
        massKgDisplayFa = "۸.۲۶ × ۱۰³۶ کیلوگرم (۴.۱۵ میلیون برابر جرم خورشید)",
        massKgDisplayEn = "8.26 × 10³⁶ kg (4.15 Million Solar Masses)",
        massComparedToEarth = 1.38e12,
        gravityMssDisplayFa = "سیاهچاله کلان‌جرم با میدان گرانشی نسبیتی مفرط",
        gravityMssDisplayEn = "Supermassive black hole with extreme relativistic gravity",
        gravityComparedToEarth = null,
        distanceKm = 2.46e17,
        distanceLightYears = 26000.0,
        distanceDisplayFa = "۲۶,۰۰۰ سال نوری (۲.۴۶ × ۱۰¹۷ کیلومتر)",
        distanceDisplayEn = "26,000 light-years (2.46 × 10¹⁷ km)"
    )

    private val physicalMap: Map<String, PhysicalProperties> = mapOf(
        // Sun
        "sun" to SUN_PROPS,
        "sun_sol" to SUN_PROPS,
        "sun_main" to SUN_PROPS,

        // Earth
        "planet_earth" to EARTH_PROPS,
        "earth" to EARTH_PROPS,

        // Moon
        "moon" to MOON_PROPS,
        "moon_luna" to MOON_PROPS,
        "moon_main" to MOON_PROPS,

        // ISS
        "sat_25544" to ISS_PROPS,
        "sat_iss" to ISS_PROPS,
        "iss" to ISS_PROPS,
        "iss_zarya" to ISS_PROPS,

        // Planets
        "planet_mercury" to MERCURY_PROPS,
        "planet_venus" to VENUS_PROPS,
        "planet_mars" to MARS_PROPS,
        "planet_jupiter" to JUPITER_PROPS,
        "planet_saturn" to SATURN_PROPS,
        "planet_uranus" to URANUS_PROPS,
        "planet_neptune" to NEPTUNE_PROPS,
        "planet_pluto" to PLUTO_PROPS,

        // Moons
        "jup_io" to IO_PROPS,
        "galilean_moon_io" to IO_PROPS,
        "jupiter_io" to IO_PROPS,
        "io" to IO_PROPS,

        "jup_europa" to EUROPA_PROPS,
        "galilean_moon_europa" to EUROPA_PROPS,
        "jupiter_europa" to EUROPA_PROPS,
        "europa" to EUROPA_PROPS,

        "jup_ganymede" to GANYMEDE_PROPS,
        "galilean_moon_ganymede" to GANYMEDE_PROPS,
        "jupiter_ganymede" to GANYMEDE_PROPS,
        "ganymede" to GANYMEDE_PROPS,

        "jup_callisto" to CALLISTO_PROPS,
        "galilean_moon_callisto" to CALLISTO_PROPS,
        "jupiter_callisto" to CALLISTO_PROPS,
        "callisto" to CALLISTO_PROPS,

        "jup_elara" to ELARA_PROPS,
        "galilean_moon_elara" to ELARA_PROPS,
        "elara" to ELARA_PROPS,

        // Stars
        "star_cma_sirius" to SIRIUS_PROPS,
        "star_sirius" to SIRIUS_PROPS,
        "star_lyr_vega" to VEGA_PROPS,
        "star_vega" to VEGA_PROPS,
        "star_ori_betelgeuse" to BETELGEUSE_PROPS,
        "star_betelgeuse" to BETELGEUSE_PROPS,
        "star_umi_polaris" to POLARIS_PROPS,
        "star_polaris" to POLARIS_PROPS,

        // Deep Sky
        "dso_m31_andromeda" to ANDROMEDA_PROPS,
        "galaxy_andromeda_m31" to ANDROMEDA_PROPS,
        "dso_m42_orion_nebula" to ORION_NEBULA_PROPS,
        "nebula_orion_m42" to ORION_NEBULA_PROPS,
        "dso_m45_pleiades" to PLEIADES_PROPS,
        "cluster_pleiades_m45" to PLEIADES_PROPS,
        "galaxy_milky_way" to MILKY_WAY_PROPS,
        "sagittarius_a_star" to SGRA_PROPS,
        "gal_center" to SGRA_PROPS
    )

    private val SUN_FACTS_FA = listOf(
        "هسته خورشید با دمای ۱۵ میلیون درجه سانتی‌گراد، در هر ثانیه ۶۰۰ میلیون تن هیدروژن را به هلیوم تبدیل می‌کند.",
        "فوتون‌های تولیدشده در هسته خورشید بین ۱۰ هزار تا ۱۷۰ هزار سال در حرکت انتشاری هستند تا به سطح برسند، اما از سطح تا زمین تنها ۸ دقیقه و ۲۰ ثانیه در راهند.",
        "خورشید بیش از ۹۹.۸۶ درصد از کل جرم کل منظومه شمسی را به خود اختصاص داده است.",
        "میدان مغناطیسی خورشید هر ۱۱ سال یک‌بار در چرخه فعالیت خورشیدی به طور کامل معکوس می‌شود و جای قطب‌ها عوض می‌شود.",
        "خورشید یک ستاره رشته اصلی با رده طیفی G2V (کوتوله زرد) با سن تقریبی ۴.۶ میلیارد سال است و در نیمه عمر رشته اصلی خود قرار دارد."
    )

    private val SUN_FACTS_EN = listOf(
        "The Sun's core temperature reaches 15 million °C (27 million °F), fusing 600 million tons of hydrogen into helium every second.",
        "Photons generated in the core take 10,000 to 170,000 years to diffuse to the surface, but then travel to Earth in just 8 minutes and 20 seconds.",
        "The Sun contains approximately 99.86% of the total mass of the entire Solar System.",
        "The Sun's magnetic field undergoes a complete polarity flip every 11 years as part of its solar activity cycle.",
        "Classified as a G2V main-sequence yellow dwarf star, the Sun is currently 4.6 billion years old and about halfway through its main-sequence lifetime."
    )

    private val EARTH_FACTS_FA = listOf(
        "زمین تنها جهان شناخته‌شده در کیهان است که دارای اقیانوس‌های آب مایع پایدار و حیات زیستی پویا است.",
        "هسته بیرونی مذاب آهن و نیکل زمین میدان مغناطیسی نیرومندی ایجاد می‌کند که سیاره را در برابر بادهای خورشیدی و پرتوهای کیهانی محافظت می‌نماید.",
        "جو زمین حاوی ۷۸٪ نیتروژن و ۲۱٪ اکسیژن است که تعادل دمایی و تنفسی مناسب برای حیات را فراهم می‌سازد.",
        "زمین تنها سیاره خاکی منظومه شمسی است که دارای صفحات تکتونیکی فعال برای بازیافت کربن پوسته است.",
        "زمین با سرعت میانگین ۲۹.۷۸ کیلومتر بر ثانیه (۱۰۷,۲۰۰ کیلومتر بر ساعت) به دور خورشید گردش می‌کند."
    )

    private val EARTH_FACTS_EN = listOf(
        "Earth is the only planetary body known to maintain stable liquid water oceans and harbor active biological life.",
        "Earth's molten iron-nickel outer core generates a powerful magnetosphere that deflects lethal solar wind and cosmic radiation.",
        "Earth's atmosphere comprises 78.08% nitrogen, 20.95% oxygen, 0.93% argon, and trace gases, balancing respiration and temperature.",
        "Earth is the only terrestrial planet in the Solar System with active plate tectonics that continuously recycle crustal carbon.",
        "Earth orbits the Sun at an average velocity of 29.78 km/s (107,200 km/h), completing one revolution every 365.256 days."
    )

    private val MOON_FACTS_FA = listOf(
        "ماه با سرعت ۳.۸ سانتی‌متر در سال به دلیل انتقال گشتاور جزر و مدی در حال دور شدن تدریجی از زمین است.",
        "به دلیل قفل همگام (جزر و مدی)، دوره چرخش ماه به دور خود با دوره گردش آن به دور زمین یکسان (۲۷.۳۲ روز) است و همواره یک سمت آن دیده می‌شود.",
        "چرخه کامل فازهای ماه (از ماه نو تا ماه نو بعدی) یا ماه هلالی برابر ۲۹.۵۳ روز به طول می‌انجامد.",
        "دمای سطح ماه نوسان شدیدی دارد: از ۱۲۰+ درجه سانتی‌گراد در نیمروز تا ۱۳۰- درجه در شب و ۲۴۶- درجه در گودال‌های تاریک قطبی.",
        "نیروی گرانش ماه (۱.۶۲۲ متر بر مجذور ثانیه) حدود یک‌ششم گرانش زمین است و عامل اصلی ایجاد جزر و مد در اقیانوس‌ها می‌باشد."
    )

    private val MOON_FACTS_EN = listOf(
        "Laser retroreflectors left by Apollo missions confirm the Moon is receding from Earth at a rate of 3.8 cm per year due to tidal friction.",
        "Tidal locking forces the Moon's rotation period to match its orbital period (27.32 days), keeping the same hemisphere permanently facing Earth.",
        "The complete lunar synodic month (New Moon to New Moon phase cycle) lasts exactly 29.53059 days.",
        "Surface temperatures swing dramatically from +120 °C at lunar noon to -130 °C at night, plummeting to -246 °C in permanently shadowed polar craters.",
        "Surface gravity on the Moon is 1.622 m/s² (about 1/6th of Earth's), driving significant ocean tides on Earth."
    )

    private val ISS_FACTS_FA = listOf(
        "ایستگاه فضایی بین‌المللی با سرعت ۲۷,۶۰۰ کیلومتر بر ساعت، هر ۹۰ تا ۹۲ دقیقه یک‌بار زمین را دور می‌زند.",
        "فضانوردان حاضر در ISS در هر ۲۴ ساعت، ۱۶ بار طلوع و ۱۶ بار غروب خورشید را تجربه می‌کنند.",
        "ابعاد ایستگاه فضایی معادل یک زمین فوتبال بزرگ است و فضایی قابل سکونت برابر یک خانه ۶ خوابه دارد.",
        "پنل‌های خورشیدی ISS مساحتی حدود ۲,۴۰۰ متر مربع را پوشش می‌دهند و برق کل ایستگاه را تامین می‌کنند.",
        "ایستگاه فضایی از زمان نوامبر سال ۲۰۰۰ میلادی به صورت پیوسته و بدون وقفه میزبان فضانوردان بوده است."
    )

    private val ISS_FACTS_EN = listOf(
        "The ISS orbits Earth at an average velocity of 27,600 km/h (17,150 mph), completing one full orbit every 90 to 92 minutes.",
        "Astronauts aboard the ISS experience 16 sunrises and 16 sunsets every 24 hours as they traverse orbital day-night terminators.",
        "Spanning 109 meters from end to end (the size of an entire football field), its solar arrays cover over 2,400 square meters.",
        "The station provides an internal pressurized living volume of 916 cubic meters, equivalent to a large six-bedroom house.",
        "The ISS has been continuously inhabited by international astronaut crews without interruption since November 2, 2000."
    )

    private val MERCURY_FACTS_FA = listOf(
        "عطارد سریع‌ترین سیاره منظومه شمسی است و یک سال آن تنها ۸۸ روز زمین طول می‌کشد.",
        "اختلاف دمای روز و شب در عطارد بیشترین میزان در منظومه شمسی است (از ۴۳۰ درجه بالای صفر تا ۱۸۰- زیر صفر).",
        "عطارد با وجود نزدیکی به خورشید، گرم‌ترین سیاره نیست؛ زیرا جوی برای به دام انداختن گرما ندارد.",
        "هسته فلزی آهن در عطارد حدود ۸۵ درصد از شعاع کل این سیاره را تشکیل می‌دهد.",
        "گودال‌های قطبی عطارد به دلیل زاویه میل صفر درجه محوری، هرگز نور خورشید را نمی‌بینند و دارای یخ آب هستند."
    )

    private val MERCURY_FACTS_EN = listOf(
        "Mercury is the fastest planet in the Solar System, orbiting the Sun at an average speed of 47.36 km/s and completing a year in 88 Earth days.",
        "Due to a 3:2 spin-orbit resonance, Mercury rotates three times on its axis for every two orbits around the Sun, making one solar day 176 Earth days.",
        "Mercury has the most extreme temperature swings in the Solar System, ranging from 430 °C (800 °F) by day to -180 °C (-290 °F) at night.",
        "Mercury's massive iron-rich metallic core constitutes approximately 85% of its entire planetary radius.",
        "Despite extreme daytime heat, permanently shadowed craters at Mercury's poles shelter significant deposits of pure water ice."
    )

    private val VENUS_FACTS_FA = listOf(
        "زهره داغ‌ترین سیاره منظومه شمسی است که دمای سطح آن به دلیل اثر گلخانه‌ای شدید به ۴۶۵ درجه سانتی‌گراد می‌رسد.",
        "جهت چرخش زهره به دور خود معکوس (ساعت‌گرد) است؛ بنابراین خورشید در زهره از غرب طلوع و در شرق غروب می‌کند.",
        "یک روز در زهره (۲۴۳ روز زمین) طولانی‌تر از یک سال آن (۲۲۵ روز زمین) به طول می‌انجامد.",
        "فشار جو در سطح زهره ۹۲ برابر فشار جو زمین است (معادل فشار آب در عمق ۹۰۰ متری اقیانوس).",
        "ابرهای غلیظ زهره از قطرات اسید سولفوریک تشکیل شده‌اند و بیش از ۷۵ درصد نور خورشید را بازمی‌تابانند."
    )

    private val VENUS_FACTS_EN = listOf(
        "Venus is the hottest planet in the Solar System with a runaway greenhouse surface temperature of 465 °C (870 °F), hot enough to melt lead.",
        "Venus rotates retrograde (clockwise) on its axis, meaning the Sun rises in the west and sets in the east.",
        "A single sidereal rotation of Venus (243 Earth days) is longer than its orbital year around the Sun (224.7 Earth days).",
        "Surface atmospheric pressure on Venus is 92 bars (9.2 MPa), equivalent to the crushing pressure 900 meters deep in Earth's oceans.",
        "Venus is perpetually veiled by reflective clouds of concentrated sulfuric acid that reflect over 75% of incoming sunlight."
    )

    private val MARS_FACTS_FA = listOf(
        "کوه المپوس در مریخ بزرگ‌ترین آتشفشان منظومه شمسی است که ارتفاعی ۳ برابر کوه اورست (۲۱.۹ کیلومتر) دارد.",
        "دره والز مارینریس در مریخ دره‌ای غول‌پیکر به طول ۴,۰۰۰ کیلومتر است که کل پهنای ایالات متحده را می‌پوشاند.",
        "رنگ سرخ مریخ ناشی از اکسید آهن (زنگ‌زدگی) موجود در خاک و غبار سطح آن است.",
        "مریخ دارای دو قمر کوچک و ناهموار به نام‌های فوبوس و دیموس است که احتمالاً سیارک‌های به دام افتاده هستند.",
        "یک روز در مریخ (سول) بسیار نزدیک به روز زمین است و ۲۴ ساعت و ۳۹ دقیقه طول می‌کشد."
    )

    private val MARS_FACTS_EN = listOf(
        "Olympus Mons on Mars is the largest volcano in the Solar System, rising 21.9 km (72,000 ft) high—nearly three times the height of Mount Everest.",
        "Valles Marineris is a colossal canyon system spanning over 4,000 km across Mars, four times deeper and ten times longer than the Grand Canyon.",
        "The characteristic reddish-orange hue of Mars is caused by abundant iron(III) oxide (ferric oxide / rust) in its surface regolith.",
        "Mars possesses two small, irregular moons, Phobos and Deimos, which are thought to be captured carbonaceous asteroids.",
        "A Martian solar day, known as a sol, lasts 24 hours, 39 minutes, and 35 seconds, remarkably close to an Earth day."
    )

    private val JUPITER_FACTS_FA = listOf(
        "لکه سرخ بزرگ مشتری طوفانی عظیم و کهن است که ابعادی بزرگ‌تر از کل کره زمین دارد.",
        "مشتری دارای قوی‌ترین میدان مغناطیسی در میان سیارات است که ۲۰,۰۰۰ برابر قوی‌تر از میدان مغناطیسی زمین می‌باشد.",
        "مشتری سریع‌ترین سرعت دوران به دور خود را دارد و یک شبانه‌روز آن تنها ۹ ساعت و ۵۵ دقیقه طول می‌کشد.",
        "چهار قمر بزرگ مشتری (گالیله‌ای) شامل گانی‌مید (بزرگ‌ترین قمر کیهان)، اروپا (اقیانوس زیرسطحی)، یو (آتشفشانی) و کالیستو هستند.",
        "مشتری به عنوان سپر گرانشی منظومه شمسی عمل کرده و بسیاری از دنباله‌دارها و سیارک‌های سرگردان را به سوی خود می‌کشد."
    )

    private val JUPITER_FACTS_EN = listOf(
        "Jupiter is more than twice as massive as all other Solar System planets combined, containing 317.8 Earth masses.",
        "The Great Red Spot is a persistent anticyclonic storm larger than the diameter of Earth, continuously observed for over 350 years.",
        "Jupiter has the shortest day of all planets, completing one full axial rotation in just 9 hours and 55 minutes.",
        "Jupiter possesses a powerful magnetic field 20,000 times stronger than Earth's, driving intense auroral rings and radiation belts.",
        "Jupiter hosts 95 recognized moons, including the four massive Galilean moons: volcanic Io, oceanic Europa, giant Ganymede, and cratered Callisto."
    )

    private val SATURN_FACTS_FA = listOf(
        "حلقه‌های تماشایی زحل از میلیاردها قطعه یخ، غبار و سنگ با ضخامتی تنها حدود ۱۰ تا ۳۰ متر تشکیل شده‌اند.",
        "چگالی زحل از آب کمتر است (۰.۶۸۷ گرم بر سانتی‌متر مکعب)؛ اگر اقیانوسی به اندازه کافی بزرگ وجود داشت، زحل روی آب شناور می‌ماند!",
        "قمر تایتان زحل تنها قمر منظومه شمسی با جوی غلیظ و دریاچه‌های مایع متان و اتان است.",
        "در قطب شمال زحل، یک طوفان شش‌ضلعی (هگزاگون) شگفت‌انگیز و مداوم به عرض ۳۰,۰۰۰ کیلومتر وجود دارد.",
        "زحل تا کنون دارای ۱۴۶ قمر تاییدشده رسمی است که بیشترین تعداد در منظومه شمسی محسوب می‌شود."
    )

    private val SATURN_FACTS_EN = listOf(
        "Saturn's extensive ring system spans up to 282,000 km in width but is remarkably thin, averaging only about 10 to 30 meters in thickness.",
        "Saturn has the lowest mean density of any planet in the Solar System (0.687 g/cm³)—it is less dense than liquid water.",
        "Saturn's giant moon Titan is the only moon with a dense atmosphere and stable surface lakes and seas of liquid methane and ethane.",
        "A persistent hexagonal jet stream cloud pattern spanning 30,000 km across rotates around Saturn's north pole.",
        "Saturn is the planet with the most known moons in the Solar System, with 146 officially recognized moons."
    )

    private val URANUS_FACTS_FA = listOf(
        "اورانوس انحراف محوری عجیب ۹۸ درجه‌ای دارد و عملاً روی مدار خود به دور خورشید به پهلو می‌چرخد.",
        "اورانوس سردترین جو را در میان سیارات منظومه شمسی دارد که دمای آن به ۲۲۴- درجه سانتی‌گراد (۴۹ کلوین) می‌رسد.",
        "رنگ فیروزه‌ای-آبی اورانوس به دلیل وجود گاز متان در جو بالای آن است که نور سرخ را جذب می‌کند.",
        "اورانوس دارای ۱۳ حلقه باریک و تاریک است که پس از حلقه‌های زحل کشف شدند.",
        "به دلیل چرخش به پهلو، هر قطب اورانوس ۴۲ سال مداوم روشنایی خورشید و ۴۲ سال تاریکی مطلق پیاپی را تجربه می‌کند."
    )

    private val URANUS_FACTS_EN = listOf(
        "Uranus has an extreme axial tilt of 97.77 degrees, effectively rotating on its side as it orbits the Sun.",
        "Uranus possesses the coldest planetary atmosphere in the Solar System, with minimum temperatures dropping to -224 °C (49 K).",
        "The cyan/aquamarine color of Uranus is caused by atmospheric methane gas absorbing red light in its upper atmosphere.",
        "Because of its sideways rotation, each pole of Uranus experiences 42 years of continuous sunlight followed by 42 years of darkness.",
        "Uranus features a system of 13 faint, narrow planetary rings composed of dark boulders and dust grains."
    )

    private val NEPTUNE_FACTS_FA = listOf(
        "نپتون دارای شدیدترین بادهای منظومه شمسی است که سرعت آن‌ها به بیش از ۲,۱۰۰ کیلومتر بر ساعت می‌رسد.",
        "نپتون نخستین سیاره‌ای بود که وجود آن ابتدا از طریق محاسبات ریاضی گرانشی پیش‌بینی و سپس با تلسکوپ کشف شد.",
        "قمر بزرگ نپتون، تریتون، تنها قمر بزرگ منظومه شمسی است که مداری معکوس (مخالف جهت چرخش سیاره) دارد.",
        "فاصله نپتون از خورشید به قدری زیاد است که یک سال در نپتون معادل ۱۶۴.۸ سال زمین به طول می‌انجامد.",
        "با وجود فاصله دور از خورشید، نپتون ۲.۶ برابر انرژی گرمایی بیشتری نسبت به گرمای دریافتی از خورشید به فضا تابش می‌کند."
    )

    private val NEPTUNE_FACTS_EN = listOf(
        "Neptune harbors the fastest recorded winds in the Solar System, reaching supersonic speeds over 2,100 km/h (1,300 mph).",
        "Neptune was the first planet discovered through mathematical prediction rather than empirical observation, based on orbital perturbations of Uranus.",
        "Neptune's largest moon, Triton, is the only large moon in the Solar System with a retrograde orbit, indicating it was a captured Kuiper Belt object.",
        "Neptune completes one orbit around the Sun every 164.8 Earth years, having completed only one full orbit since its discovery in 1846.",
        "Despite being the most distant major planet from the Sun, Neptune emits 2.6 times more internal thermal energy than it absorbs from solar radiation."
    )

    private val PLUTO_FACTS_FA = listOf(
        "پلوتو دارای یک منطقه یخچالی نیتروژنی قلبی‌شکل معروف به نام «تومبا رجیو» است.",
        "قمر بزرگ پلوتو، شارون، ابعادی نصف پلوتو دارد و این دو جرم یک سامانه دوتایی قفل‌شده را تشکیل می‌دهند.",
        "مدار پلوتو کاملاً بیضی شکل است و در بخشی از مدار خود به خورشید نزدیک‌تر از نپتون می‌شود.",
        "در سال ۲۰۰۶، اتحادیه بین‌المللی اخترشناسی (IAU) تعریف سیاره را تغییر داد و پلوتو به عنوان سیاره کوتوله طبقه‌بندی شد.",
        "جو رقیق پلوتو هنگام نزدیک شدن به خورشید تبخیر شده و هنگام دور شدن منجمد و بر سطح می‌بارد."
    )

    private val PLUTO_FACTS_EN = listOf(
        "Pluto features a massive heart-shaped glacier named Tombaugh Regio, consisting primarily of bright frozen nitrogen, carbon monoxide, and methane.",
        "Pluto and its largest moon, Charon, form a mutually tidally locked binary system whose center of mass (barycenter) lies outside Pluto.",
        "Pluto's orbit is highly eccentric and inclined at 17 degrees; for 20 years of its 248-year orbit, it is closer to the Sun than Neptune.",
        "In 2006, the International Astronomical Union (IAU) redefined the term 'planet', reclassifying Pluto as a dwarf planet.",
        "Pluto has a tenuous nitrogen atmosphere that expands when closer to perihelion and freezes out onto the surface when farther away."
    )

    private val IO_FACTS_FA = listOf(
        "آیو فعال‌ترین جرم از نظر آتشفشانی در تمام منظومه شمسی است که فوران‌های گوگردی آن تا ارتفاع ۵۰۰ کیلومتری به فضا پرتاب می‌شوند.",
        "علت اصلی آتشفشان‌های شدید آیو، گرمایش جزر و مدی ناشی از رزونانس مداری ۴:۲:۱ با مشتری، اروپا و گانی‌مید است.",
        "سطح آیو دائماً با گدازه‌های تازه پوشانده می‌شود و ظاهر زرد، قرمز و سیاهی شبیه به پیتزا به آن می‌دهد.",
        "حرکت آیو در میدان مغناطیسی مشتری یک جریان الکتریکی عظیم با شدت ۱ میلیون آمپر ایجاد می‌کند.",
        "برخلاف بیشتر قمرهای یخ‌زده منظومه شمسی بیرونی، آیو عمدتاً از سنگ‌های سیلیکاتی و هسته آهنی مذاب تشکیل شده است."
    )

    private val IO_FACTS_EN = listOf(
        "Io is the most volcanically active body in the Solar System with over 400 active sulfur volcanoes erupting plumes up to 500 km high.",
        "Tidal heating from orbital resonance (4:2:1 Laplace resonance) with Europa and Ganymede powers Io's intense internal volcanism.",
        "Continuous lava flows constantly resurface Io, giving it a vibrant colorful yellow, black, and red 'pizza-like' appearance.",
        "Io's motion through Jupiter's powerful magnetic field generates an electric current ring of approximately 1 million amperes.",
        "Unlike outer icy moons, Io consists primarily of silicate rock surrounding a molten iron or iron-sulfide core."
    )

    private val EUROPA_FACTS_FA = listOf(
        "اروپا دارای اقیانوسی جهانی از آب مایع زیر پوسته یخی خود است که حجم آب آن بیش از دو برابر تمام اقیانوس‌های زمین است.",
        "پوسته یخی اروپا بسیار صاف بوده و خطوط رگه‌مانند قهوه‌ای‌رنگی به نام «خطوارگی» (Lineae) سطح آن را پوشانده است.",
        "اقیانوس زیرسطحی اروپا به عنوان یکی از امیدوارکننده‌ترین جاها برای یافتن حیات فرازمینی توسط مأموریت‌های Clipper و JUICE بررسی می‌شود.",
        "آب‌فشان‌های عظیمی از بخار آب در قطب جنوب اروپا شناسایی شده‌اند که از فوران‌های اقیانوس زیرسطحی سرچشمه می‌گیرند.",
        "نیروی جزر و مدی مشتری باعث انقباض و انبساط مداوم هسته اروپا و تولید گرمای هیدروترمال برای مایع ماندن اقیانوس می‌شود."
    )

    private val EUROPA_FACTS_EN = listOf(
        "Europa holds a vast subsurface global liquid water ocean containing more water than all of Earth's oceans combined.",
        "Its extremely smooth ice shell is crisscrossed by dark reddish fractures called lineae formed by tidal flexing and tectonic stresses.",
        "Europa is one of the highest-priority astrobiological targets in the search for habitable environments beyond Earth.",
        "Plumes of water vapor erupting scores of kilometers into space have been detected near Europa's southern polar region.",
        "Tidal flexing from Jupiter's gravitational field generates internal hydrothermal heat that keeps its deep ocean liquid."
    )

    private val GANYMEDE_FACTS_FA = listOf(
        "گانی‌مید بزرگ‌ترین قمر در تمام منظومه شمسی است و ابعاد آن از سیاره عطارد و سیاره کوتوله پلوتو نیز بزرگ‌تر است.",
        "گانی‌مید تنها قمر شناخته‌شده در کیهان است که دارای میدان مغناطیسی اختصاصی (مگنوسفر) ناشی از هسته آهنی مذاب است.",
        "تعامل میدان مغناطیسی گانی‌مید با مشتری باعث ایجاد شفق‌های قطبی درخشان در قطب‌های این قمر می‌شود.",
        "در زیر پوسته ضخیم یخی گانی‌مید، اقیانوس عمیق چندلایه‌ای از آب مایع ساندویچ‌شده بین لایه‌های یخ وجود دارد.",
        "سطح گانی‌مید شامل دهانه‌های برخوردی ۴ میلیارد ساله تاریک و شیارهای روشن جوان‌تر ناشی از گسل‌های تکتونیکی است."
    )

    private val GANYMEDE_FACTS_EN = listOf(
        "Ganymede is the largest moon in the Solar System (5,268 km in diameter)—measuring larger than planet Mercury and dwarf planet Pluto.",
        "It is the only moon in the Solar System known to generate its own intrinsic magnetosphere, powered by a convecting liquid iron core.",
        "Magnetic interactions between Ganymede and Jupiter generate distinct ultraviolet auroral ovals around Ganymede's magnetic poles.",
        "A deep subsurface saltwater ocean containing more water than Earth lies stratified between high-pressure ice layers.",
        "Its surface features ancient, dark heavily cratered terrain alongside younger, grooved tectonic fractures."
    )

    private val CALLISTO_FACTS_FA = listOf(
        "کالیستو پردهانه‌ترین و دست‌نخورده‌ترین جرم منظومه شمسی است که سطح یخی آن بیش از ۴ میلیارد سال قدمت دارد.",
        "بزرگ‌ترین عارضه برخوردی کالیستو، دهانه چندحلقه‌ای «والهالا» با قطری بیش از ۳,۸۰۰ کیلومتر است.",
        "کالیستو خارج از کمربند تشعشعی خطرناک مشتری گردش می‌کند و بهترین گزینه برای پایگاه‌های انسانی آینده است.",
        "این قمر فاقد فعالیت‌های آتشفشانی یا تکتونیکی بوده و تاریخچه اولیه منظومه شمسی را به صورت بکر حفظ کرده است.",
        "کالیستو ترکیبی ۵۰/۵۰ از سنگ و یخ است و احتمالاً دارای اقیانوسی شور در عمق ۱۰۰ تا ۲۵۰ کیلومتری می‌باشد."
    )

    private val CALLISTO_FACTS_EN = listOf(
        "Callisto is the most heavily cratered object in the Solar System, preserving a 4-billion-year-old primordial icy surface.",
        "Its surface features colossal multi-ring impact structures, dominated by the ancient Valhalla impact basin spanning 3,800 km.",
        "Orbiting outside Jupiter's lethal main radiation belt makes Callisto the safest and most viable site for a future human exploration outpost.",
        "The total lack of internal volcanic or tectonic activity has left Callisto's geological cratering record pristine since the Solar System's birth.",
        "Callisto comprises an undifferentiated mixture of equal parts rock and ice and is suspected to harbor a deep, salty subsurface ocean."
    )

    private val ELARA_FACTS_FA = listOf(
        "این قمر نامنظم مشتری در سال ۱۹۰۵ توسط چارلز دیلون پرین در رصدخانه لیک کشف شد.",
        "این قمر تا سال ۱۹۷۵ نام کنونی خود را نداشت و تنها با عنوان «مشتری ۷» (Jupiter VII) شناخته می‌شد؛ میان سال‌های ۱۹۵۵ تا ۱۹۷۵ گاهی به‌طور غیررسمی «هرا» نامیده می‌شد.",
        "الارا متعلق به گروه هیمالیا از قمرهای نامنظم مشتری است که مداری موافق و دوردست در فاصله ۱۱.۷ میلیون کیلومتری دارند.",
        "سطح الارا بسیار تاریک و خاکستری‌رنگ (از نوع کربنی C) است که نشان می‌دهد احتمالاً سیارکی بوده که توسط گرانش مشتری به دام افتاده است.",
        "یک دور گردش کامل الارا به دور مشتری حدود ۲۵۹.۶ روز زمین به طول می‌انجامد."
    )

    private val ELARA_FACTS_EN = listOf(
        "Elara is an irregular Jovian satellite discovered in 1905 by astronomer Charles Dillon Perrine at Lick Observatory.",
        "Elara did not receive its present name until 1975; before then it was known simply as Jupiter VII, and between 1955 and 1975 it was sometimes informally called 'Hera'.",
        "It belongs to the Himalia group of prograde irregular moons orbiting nearly 11.7 million kilometers from Jupiter.",
        "Elara has an extremely dark C-type carbonaceous surface with an albedo of just 0.04, indicating it is an ancient captured asteroid.",
        "It takes Elara approximately 259.6 Earth days to complete a single eccentric orbit around Jupiter."
    )

    private val SIRIUS_FACTS_FA = listOf(
        "شباهنگ (Sirius) درخشان‌ترین ستاره در آسمان شب زمین با قدر ظاهری ۱۴۶.- است.",
        "شباهنگ یک سامانه ستاره‌ای دوتایی است؛ ستاره همدم آن (Sirius B) یک کوتوله سفید بسیار متراکم است.",
        "در مصر باستان، طلوع شامگاهی شباهنگ نشان‌دهنده شروع طغیان سالانه رود نیل و آغاز سال نو بود.",
        "دمای سطح شباهنگ حدود ۹,۹۴۰ کلوین است که موجب درخشش سفید-آبی خیره‌کننده آن می‌شود.",
        "فاصله شباهنگ از زمین تنها ۸.۶ سال نوری است که آن را به یکی از نزدیک‌ترین همسایگان خورشید تبدیل می‌کند."
    )

    private val SIRIUS_FACTS_EN = listOf(
        "Sirius (Alpha Canis Majoris) is the brightest star in Earth's night sky with an apparent visual magnitude of -1.46.",
        "Sirius is a binary star system consisting of Sirius A (an A1V main-sequence star) and Sirius B (the first historically discovered white dwarf).",
        "In ancient Egypt, the heliacal rising of Sirius just before dawn marked the annual flooding of the Nile River and the start of the New Year.",
        "Sirius has a surface temperature of 9,940 K, giving it a brilliant diamond white-blue emission spectrum.",
        "Located only 8.611 light-years (81.5 trillion km) away, Sirius is the 7th closest known stellar system to our Sun."
    )

    private val VEGA_FACTS_FA = listOf(
        "ستاره نسر واقع (Vega) مبنای اولیه صفر قدر ظاهری در مقیاس درخشش ستارگان بوده است.",
        "حدود ۱۲,۰۰۰ سال پیش، نسر واقع ستاره قطبی زمین بوده است و ۱۴,۰۰۰ سال دیگر دوباره ستاره قطبی خواهد شد.",
        "نسر واقع نخسیتن ستاره‌ای بود که پس از خورشید از آن عکس‌برداری شد (سال ۱۸۵۰ میلادی).",
        "سرعت دوران نسر واقع به دور خود بسیار بالاست و شکل آن در استوا برآمده شده است.",
        "قرص غباری بزرگی در اطراف نسر واقع وجود دارد که نشان‌دهنده احتمال وجود سامانه‌های سیاره‌ای است."
    )

    private val VEGA_FACTS_EN = listOf(
        "Vega (Alpha Lyrae) was the historical baseline zero-point for the visual photometric magnitude scale (magnitude 0.00).",
        "Around 12,000 BCE, Vega served as Earth's northern pole star, and due to axial precession it will become the North Star again in ~13,727 CE.",
        "Vega was the very first star other than the Sun to be photographed (captured via daguerreotype at Harvard Observatory in 1850).",
        "Vega rotates at an extremely high equatorial velocity of 236 km/s, causing it to flatten into a pronounced oblate spheroid.",
        "Vega is surrounded by an extensive circumstellar debris disk of dust, indicating the presence of ongoing planetesimal collisions."
    )

    private val BETELGEUSE_FACTS_FA = listOf(
        "ابط‌الجوزا (Betelgeuse) یک ابرغول سرخ غول‌پیکر است که اگر جای خورشید بود، تا مدار مشتری را می‌بلعید!",
        "این ستاره در مراحل پایانی تکامل خود قرار دارد و به زودی (در مقیاس نجومی) دچار انفجار ابرنواختری خواهد شد.",
        "انفجار ابرنواختری ابط‌الجوزا به قدری درخشان خواهد بود که تا چند هفته در روز روشن نیز دیده خواهد شد.",
        "در سال ۲۰۱۹، افت نور شدید ابط‌الجوزا ناشی از خروج یک توده عظیم غبار و گاز از سطح آن بود.",
        "ابط‌الجوزا حدود ۱۰۰,۰۰۰ برابر خورشید روشنایی تابش می‌کند اما دمای سطح آن تنها ۳,۵۰۰ کلوین است."
    )

    private val BETELGEUSE_FACTS_EN = listOf(
        "Betelgeuse is a colossal red supergiant star; if placed at the center of our Solar System, its surface would extend beyond the orbit of Jupiter.",
        "Betelgeuse is in the late stages of stellar nucleosynthesis and will end its life in a catastrophic Type II core-collapse supernova.",
        "When Betelgeuse explodes, it will shine as brightly as the half-Moon and remain visible in broad daylight for several weeks.",
        "The Great Dimming of 2019-2020 was caused by a giant surface mass ejection that condensed into an obscuring dust cloud.",
        "Betelgeuse radiates over 100,000 times the luminosity of the Sun despite having a relatively cool surface temperature of ~3,500 K."
    )

    private val POLARIS_FACTS_FA = listOf(
        "ستاره قطبی (Polaris) دقیقاً در امتداد محور دوران زمین در قطب شمال آسمان قرار دارد.",
        "ارتفاع زاویه‌ای ستاره قطبی از افق، دقیقاً برابر با عرض جغرافیایی محل رصد شماست.",
        "ستاره قطبی یک ستاره تکی نیست، بلکه یک سامانه سه‌تایی از ستارگان تپنده متغیر قیفاووسی است.",
        "درخشش ستاره قطبی حدود ۲,۵۰۰ برابر خورشید است و در فاصله ۴۳۳ سال نوری قرار دارد.",
        "به دلیل حرکت تقدیمی محور زمین، ستاره قطبی برای همیشه در قطب شمال نخواهد ماند."
    )

    private val POLARIS_FACTS_EN = listOf(
        "Polaris currently aligns within 0.7 degrees of Earth's north celestial pole, serving as the premier navigational guide for northern observers.",
        "The angular altitude of Polaris above your northern horizon equals your exact geographic latitude on Earth.",
        "Polaris is a multiple star system consisting of the primary yellow supergiant Polaris Aa and companions Polaris Ab and Polaris B.",
        "Polaris is the closest and brightest Classical Cepheid variable star to Earth, pulsating in diameter and brightness over a 4-day cycle.",
        "Due to the 25,772-year axial precession of Earth, Polaris will gradually move away from the true celestial pole over future centuries."
    )

    private val ANDROMEDA_FACTS_FA = listOf(
        "کهکشان آندرومدا نزدیک‌ترین کهکشان بزرگ به راه شیری است که بیش از ۱,۰۰۰ میلیارد ستاره دارد.",
        "آندرومدا دورترین جرمی در کیهان است که می‌توان آن را با چشم غیرمسلح در تاریکی شب مشاهده کرد.",
        "کهکشان آندرومدا با سرعت ۱۱۰ کیلومتر بر ثانیه در حال نزدیک شدن به کهکشان راه شیری است.",
        "حدود ۴.۵ میلیارد سال دیگر، کهکشان آندرومدا و راه شیری با هم ادغام شده و یک کهکشان بیضوی غول‌پیکر می‌سازند.",
        "نوری که امشب از آندرومدا می‌بینید، ۲.۵ میلیون سال پیش (زمان انسان‌های اولیه) حرکت خود را آغاز کرده است."
    )

    private val ANDROMEDA_FACTS_EN = listOf(
        "The Andromeda Galaxy (M31) is the nearest major spiral galaxy to the Milky Way, containing over one trillion stars.",
        "At a distance of 2.5 million light-years, Andromeda is the most distant permanent celestial object visible to the naked human eye.",
        "Andromeda and the Milky Way are approaching each other at ~110 km/s (68 mi/s) and will merge into a giant elliptical galaxy in 4.5 billion years.",
        "The apparent angular span of Andromeda across dark skies covers more than six times the diameter of the full Moon.",
        "Andromeda contains an exceptionally massive double stellar nucleus harboring a central black hole of ~140 million solar masses."
    )

    private val ORION_NEBULA_FACTS_FA = listOf(
        "سحابی جبار درخشان‌ترین و نزدیک‌ترین زایشگاه عظیم ستاره‌ای به زمین است.",
        "در مرکز سحابی جبار، خوشه چهارتایی ستارگان جوان و داغ «تراپزیوم» گازهای هیدروژن را به درخشش واداشته‌اند.",
        "سحابی جبار حاوی ده‌ها قرص سیاره‌ساز اولیه (پروپلی) است که سیستم‌های سیاره‌ای جدید را شکل می‌دهند.",
        "رنگ سبز سحابی در عکس‌های نجومی ناشی از تابش اکسیژن یونیزه شده و رنگ سرخ ناشی از هیدروژن آلفا است.",
        "مساحت واقعی سحابی جبار در آسمان شب، حدود چهار برابر مساحت ماه کامل است."
    )

    private val ORION_NEBULA_FACTS_EN = listOf(
        "The Orion Nebula (M42) is the closest massive star-forming region and stellar nursery to Earth, located ~1,344 light-years away.",
        "The nebula is ionized and illuminated by the Trapezium Cluster, a compact group of massive young O- and B-type stars at its core.",
        "Hubble Space Telescope surveys discovered dozens of protoplanetary disks (proplyds) where nascent solar systems are currently forming.",
        "The distinct green emission in astro-photographs arises from doubly ionized oxygen [O III], while red hues come from hydrogen-alpha (Hα).",
        "The physical span of the Orion Nebula across the sky covers an angular area approximately four times larger than the full Moon."
    )

    private val PLEIADES_FACTS_FA = listOf(
        "خوشه پروین (ثریا) یک خوشه باز ستاره‌ای جوان شامل بیش از ۱,۰۰۰ ستاره داغ و آبی‌رنگ است.",
        "سن این خوشه ستاره‌ای تنها حدود ۱۰۰ میلیون سال است (در مقایسه با سن ۴.۶ میلیارد ساله خورشید).",
        "ابرهای غباری انعکاسی آبی‌رنگ اطراف ستارگان، ناشی از عبور خوشه از یک ابر غبار میان‌ستاره‌ای است.",
        "در فرهنگ‌ها و اساطیر کهن جهان (ایران، یونان، ژاپن و عرب)، این خوشه به نام هفت خواهران شناخته می‌شود.",
        "این خوشه با چشم غیرمسلح شبیه یک ملاقه کوچک یا آبپاش مینیاتوری در صورت فلکی گاو دیده می‌شود."
    )

    private val PLEIADES_FACTS_EN = listOf(
        "The Pleiades (M45), or Seven Sisters, is an open star cluster dominated by hot, luminous blue B-type stars.",
        "The cluster is extremely young in astronomical terms, with an estimated stellar age of approximately 100 million years.",
        "The striking blue reflection nebulae surrounding the cluster stars are caused by the cluster passing through an unrelated interstellar dust cloud.",
        "The Pleiades cluster appears prominently in the folklore of almost all ancient cultures, including Persian, Greek, Aboriginal, and Mayan mythologies.",
        "While 6 to 7 individual stars are easily resolved by the unaided eye, the cluster contains more than 1,000 confirmed stellar members."
    )

    private val MILKY_WAY_FACTS_FA = listOf(
        "کهکشان راه شیری حاوی بین ۱۰۰ تا ۴۰۰ میلیارد ستاره و حداقل ۱۰۰ میلیارد سیاره است.",
        "در مرکز کهکشان راه شیری، سیاهچاله کلان‌جرم کمان آ (*Sagittarius A) با جرمی ۴.۱۵ میلیون برابر خورشید قرار دارد.",
        "منظومه شمسی هر ۲۳۰ میلیون سال یک‌بار مدار کامل خود به دور مرکز کهکشان را طی می‌کند (یک سال کیهانی).",
        "نوار روشن راه شیری در آسمان شب، مقطع عرضی دیسک کهکشان خودمان است که از داخل آن را تماشا می‌کنیم.",
        "کهکشان راه شیری بخشی از ابرخوشه کهکشانی عظیم «لانیاکئا» شامل بیش از ۱۰ هزار کهکشان است."
    )

    private val MILKY_WAY_FACTS_EN = listOf(
        "The Milky Way is a barred spiral galaxy containing between 100 and 400 billion stars and at least 100 billion planets.",
        "At the dynamical core of the Milky Way lies the supermassive black hole Sagittarius A* with a mass of 4.15 million Suns.",
        "The Solar System orbits the Galactic Center at 230 km/s (514,000 mph), taking ~230 million years to complete one 'Galactic Year'.",
        "The luminous milky band in dark night skies is the edge-on disk of our own home galaxy viewed from within.",
        "The Milky Way is part of the Local Group of galaxies and is bound within the colossal Laniakea Supercluster of 100,000 galaxies."
    )

    private val SGRA_FACTS_FA = listOf(
        "کمان آ (*Sagittarius A) سیاهچاله کلان‌جرم مرکز کهکشان راه شیری با جرمی معادل ۴.۱۵ میلیون برابر خورشید است.",
        "در سال ۲۰۲۲، تلسکوپ افق رویداد (EHT) نخستین تصویر مستقیم از سایه افق رویداد این سیاهچاله را منتشر کرد.",
        "ستارگان مرکزی نزدیک مانند S2 با سرعتی بالغ بر چند درصد سرعت نور در مدارهای بیضوی فشرده به دور آن می‌چرخند.",
        "شعاع افق رویداد (شعاع شوارتزشیلد) آن حدود ۱۲.۳ میلیون کیلومتر است که کمتر از فاصله عطارد تا خورشید می‌باشد.",
        "کمان آ یک سیاهچاله فشرده با تابش رادیویی غول‌پیکر است که گرانش مرکزی کهکشان ما را کنترل می‌کند."
    )

    private val SGRA_FACTS_EN = listOf(
        "Sagittarius A* (Sgr A*) is the supermassive black hole situated at the exact dynamical center of the Milky Way galaxy.",
        "It contains an accurately measured mass of 4.15 million times that of our Sun, concentrated within a radius smaller than Mercury's orbit.",
        "In May 2022, the Event Horizon Telescope (EHT) collaboration published the first direct radio image of the glowing accretion shadow around Sgr A*.",
        "Close-orbiting stars like S2 revolve around Sgr A* at speeds exceeding 7,000 km/s (nearly 3% the speed of light) at closest periastron.",
        "Located 26,000 light-years away in Sagittarius, Sgr A* is shrouded behind visual dust lanes and studied via radio, infrared, and X-ray observatories."
    )

    private val coolFactsMap: Map<String, List<String>> = mapOf(
        "sun" to SUN_FACTS_FA,
        "sun_sol" to SUN_FACTS_FA,
        "sun_main" to SUN_FACTS_FA,

        "planet_earth" to EARTH_FACTS_FA,
        "earth" to EARTH_FACTS_FA,

        "moon" to MOON_FACTS_FA,
        "moon_luna" to MOON_FACTS_FA,
        "moon_main" to MOON_FACTS_FA,

        "sat_25544" to ISS_FACTS_FA,
        "sat_iss" to ISS_FACTS_FA,
        "iss" to ISS_FACTS_FA,
        "iss_zarya" to ISS_FACTS_FA,

        "planet_mercury" to MERCURY_FACTS_FA,
        "planet_venus" to VENUS_FACTS_FA,
        "planet_mars" to MARS_FACTS_FA,
        "planet_jupiter" to JUPITER_FACTS_FA,
        "planet_saturn" to SATURN_FACTS_FA,
        "planet_uranus" to URANUS_FACTS_FA,
        "planet_neptune" to NEPTUNE_FACTS_FA,
        "planet_pluto" to PLUTO_FACTS_FA,

        "jup_io" to IO_FACTS_FA,
        "galilean_moon_io" to IO_FACTS_FA,
        "jupiter_io" to IO_FACTS_FA,
        "io" to IO_FACTS_FA,

        "jup_europa" to EUROPA_FACTS_FA,
        "galilean_moon_europa" to EUROPA_FACTS_FA,
        "jupiter_europa" to EUROPA_FACTS_FA,
        "europa" to EUROPA_FACTS_FA,

        "jup_ganymede" to GANYMEDE_FACTS_FA,
        "galilean_moon_ganymede" to GANYMEDE_FACTS_FA,
        "jupiter_ganymede" to GANYMEDE_FACTS_FA,
        "ganymede" to GANYMEDE_FACTS_FA,

        "jup_callisto" to CALLISTO_FACTS_FA,
        "galilean_moon_callisto" to CALLISTO_FACTS_FA,
        "jupiter_callisto" to CALLISTO_FACTS_FA,
        "callisto" to CALLISTO_FACTS_FA,

        "jup_elara" to ELARA_FACTS_FA,
        "galilean_moon_elara" to ELARA_FACTS_FA,
        "elara" to ELARA_FACTS_FA,

        "star_cma_sirius" to SIRIUS_FACTS_FA,
        "star_sirius" to SIRIUS_FACTS_FA,
        "star_lyr_vega" to VEGA_FACTS_FA,
        "star_vega" to VEGA_FACTS_FA,
        "star_ori_betelgeuse" to BETELGEUSE_FACTS_FA,
        "star_betelgeuse" to BETELGEUSE_FACTS_FA,
        "star_umi_polaris" to POLARIS_FACTS_FA,
        "star_polaris" to POLARIS_FACTS_FA,

        "dso_m31_andromeda" to ANDROMEDA_FACTS_FA,
        "galaxy_andromeda_m31" to ANDROMEDA_FACTS_FA,
        "dso_m42_orion_nebula" to ORION_NEBULA_FACTS_FA,
        "nebula_orion_m42" to ORION_NEBULA_FACTS_FA,
        "dso_m45_pleiades" to PLEIADES_FACTS_FA,
        "cluster_pleiades_m45" to PLEIADES_FACTS_FA,
        "galaxy_milky_way" to MILKY_WAY_FACTS_FA,
        "sagittarius_a_star" to SGRA_FACTS_FA,
        "gal_center" to SGRA_FACTS_FA
    )

    private val coolFactsMapEn: Map<String, List<String>> = mapOf(
        "sun" to SUN_FACTS_EN,
        "sun_sol" to SUN_FACTS_EN,
        "sun_main" to SUN_FACTS_EN,

        "planet_earth" to EARTH_FACTS_EN,
        "earth" to EARTH_FACTS_EN,

        "moon" to MOON_FACTS_EN,
        "moon_luna" to MOON_FACTS_EN,
        "moon_main" to MOON_FACTS_EN,

        "sat_25544" to ISS_FACTS_EN,
        "sat_iss" to ISS_FACTS_EN,
        "iss" to ISS_FACTS_EN,
        "iss_zarya" to ISS_FACTS_EN,

        "planet_mercury" to MERCURY_FACTS_EN,
        "planet_venus" to VENUS_FACTS_EN,
        "planet_mars" to MARS_FACTS_EN,
        "planet_jupiter" to JUPITER_FACTS_EN,
        "planet_saturn" to SATURN_FACTS_EN,
        "planet_uranus" to URANUS_FACTS_EN,
        "planet_neptune" to NEPTUNE_FACTS_EN,
        "planet_pluto" to PLUTO_FACTS_EN,

        "jup_io" to IO_FACTS_EN,
        "galilean_moon_io" to IO_FACTS_EN,
        "jupiter_io" to IO_FACTS_EN,
        "io" to IO_FACTS_EN,

        "jup_europa" to EUROPA_FACTS_EN,
        "galilean_moon_europa" to EUROPA_FACTS_EN,
        "jupiter_europa" to EUROPA_FACTS_EN,
        "europa" to EUROPA_FACTS_EN,

        "jup_ganymede" to GANYMEDE_FACTS_EN,
        "galilean_moon_ganymede" to GANYMEDE_FACTS_EN,
        "jupiter_ganymede" to GANYMEDE_FACTS_EN,
        "ganymede" to GANYMEDE_FACTS_EN,

        "jup_callisto" to CALLISTO_FACTS_EN,
        "galilean_moon_callisto" to CALLISTO_FACTS_EN,
        "jupiter_callisto" to CALLISTO_FACTS_EN,
        "callisto" to CALLISTO_FACTS_EN,

        "jup_elara" to ELARA_FACTS_EN,
        "galilean_moon_elara" to ELARA_FACTS_EN,
        "elara" to ELARA_FACTS_EN,

        "star_cma_sirius" to SIRIUS_FACTS_EN,
        "star_sirius" to SIRIUS_FACTS_EN,
        "star_lyr_vega" to VEGA_FACTS_EN,
        "star_vega" to VEGA_FACTS_EN,
        "star_ori_betelgeuse" to BETELGEUSE_FACTS_EN,
        "star_betelgeuse" to BETELGEUSE_FACTS_EN,
        "star_umi_polaris" to POLARIS_FACTS_EN,
        "star_polaris" to POLARIS_FACTS_EN,

        "dso_m31_andromeda" to ANDROMEDA_FACTS_EN,
        "galaxy_andromeda_m31" to ANDROMEDA_FACTS_EN,
        "dso_m42_orion_nebula" to ORION_NEBULA_FACTS_EN,
        "nebula_orion_m42" to ORION_NEBULA_FACTS_EN,
        "dso_m45_pleiades" to PLEIADES_FACTS_EN,
        "cluster_pleiades_m45" to PLEIADES_FACTS_EN,
        "galaxy_milky_way" to MILKY_WAY_FACTS_EN,
        "sagittarius_a_star" to SGRA_FACTS_EN,
        "gal_center" to SGRA_FACTS_EN
    )

    fun getPhysicalProperties(obj: CelestialObject): PhysicalProperties {
        physicalMap[obj.id]?.let { return it }

        return when (obj.type) {
            com.alijafari.red.astronomy.domain.ObjectType.STAR -> {
                val solarMult = 1.0 + (abs(obj.magnitude) / 4.0)
                PhysicalProperties(
                    diameterKm = 1.3927e6 * solarMult,
                    diameterComparedToEarth = (1.3927e6 * solarMult) / EARTH_DIAMETER_KM,
                    diameterDisplayFa = "${String.format("%.1f", solarMult)} برابر قطر خورشید",
                    diameterDisplayEn = "${String.format("%.1f", solarMult)}× Solar Diameter",
                    massKgDisplayFa = "${String.format("%.1f", 1.2 + abs(obj.magnitude)/3.0)} برابر جرم خورشید",
                    massKgDisplayEn = "${String.format("%.1f", 1.2 + abs(obj.magnitude)/3.0)}× Solar Mass",
                    massComparedToEarth = (1.2 + abs(obj.magnitude)/3.0) * 333000.0,
                    gravityMssDisplayFa = "رده طیفی ${if (obj.spectralType.isNotEmpty()) obj.spectralType else "A/F/G"}",
                    gravityMssDisplayEn = "Spectral Class: ${if (obj.spectralType.isNotEmpty()) obj.spectralType else "A/F/G"}",
                    gravityComparedToEarth = null,
                    distanceKm = obj.distanceLightYears * 9.461e12,
                    distanceLightYears = obj.distanceLightYears,
                    distanceDisplayFa = String.format("%,.1f سال نوری", obj.distanceLightYears),
                    distanceDisplayEn = String.format("%,.1f light-years", obj.distanceLightYears)
                )
            }
            com.alijafari.red.astronomy.domain.ObjectType.GALAXY -> PhysicalProperties(
                diameterKm = 9.46e17,
                diameterComparedToEarth = null, // Not applicable
                diameterDisplayFa = "۵۰,۰۰۰ تا ۱۵۰,۰۰۰ سال نوری قطر",
                diameterDisplayEn = "50,000 to 150,000 light-years diameter",
                massKgDisplayFa = "۱۰۰ تا ۵۰۰ میلیارد برابر جرم خورشید",
                massKgDisplayEn = "100B to 500B Solar Masses",
                massComparedToEarth = null,
                gravityMssDisplayFa = "میدان گرانشی عظیم کهکشانی",
                gravityMssDisplayEn = "Massive galactic gravitational field",
                gravityComparedToEarth = null,
                distanceKm = obj.distanceLightYears * 9.461e12,
                distanceLightYears = obj.distanceLightYears,
                distanceDisplayFa = String.format("%,.0f سال نوری", obj.distanceLightYears),
                distanceDisplayEn = String.format("%,.0f light-years", obj.distanceLightYears)
            )
            com.alijafari.red.astronomy.domain.ObjectType.NEBULA -> PhysicalProperties(
                diameterKm = 1.0e14,
                diameterComparedToEarth = null, // Not applicable
                diameterDisplayFa = "۱۰ تا ۵۰ سال نوری پهنای سحابی",
                diameterDisplayEn = "10 to 50 light-years extent",
                massKgDisplayFa = "۵۰۰ تا ۳,۰۰۰ برابر جرم خورشید",
                massKgDisplayEn = "500 to 3,000 Solar Masses",
                massComparedToEarth = null,
                gravityMssDisplayFa = "ابرهای گاز هیدروژن و یون‌های باردار",
                gravityMssDisplayEn = "Ionized hydrogen and interstellar dust",
                gravityComparedToEarth = null,
                distanceKm = obj.distanceLightYears * 9.461e12,
                distanceLightYears = obj.distanceLightYears,
                distanceDisplayFa = String.format("%,.0f سال نوری", obj.distanceLightYears),
                distanceDisplayEn = String.format("%,.0f light-years", obj.distanceLightYears)
            )
            com.alijafari.red.astronomy.domain.ObjectType.STAR_CLUSTER, com.alijafari.red.astronomy.domain.ObjectType.GLOBULAR_CLUSTER -> PhysicalProperties(
                diameterKm = 1.5e14,
                diameterComparedToEarth = null, // Not applicable
                diameterDisplayFa = "۱۵ تا ۱۰۰ سال نوری قطر خوشه",
                diameterDisplayEn = "15 to 100 light-years cluster diameter",
                massKgDisplayFa = "۱,۰۰۰ تا ۵۰۰,۰۰۰ ستاره هم‌تکامل",
                massKgDisplayEn = "1,000 to 500,000 co-eval stars",
                massComparedToEarth = null,
                gravityMssDisplayFa = "پیوند گرانشی خود-تراکم ستاره‌ای",
                gravityMssDisplayEn = "Self-gravitating stellar cluster",
                gravityComparedToEarth = null,
                distanceKm = obj.distanceLightYears * 9.461e12,
                distanceLightYears = obj.distanceLightYears,
                distanceDisplayFa = String.format("%,.0f سال نوری", obj.distanceLightYears),
                distanceDisplayEn = String.format("%,.0f light-years", obj.distanceLightYears)
            )
            com.alijafari.red.astronomy.domain.ObjectType.CONSTELLATION -> PhysicalProperties(
                diameterKm = null,
                diameterComparedToEarth = null, // Not applicable
                diameterDisplayFa = "${obj.category} - محدوده رسمی IAU",
                diameterDisplayEn = "${obj.category} - Official IAU Boundary",
                massKgDisplayFa = "مجموعه‌ای از ده‌ها ستاره و اجرام اعماق آسمان",
                massKgDisplayEn = "Contains multiple bound stars and DSOs",
                massComparedToEarth = null,
                gravityMssDisplayFa = "آرایش بصری بر اساس خط دید رصدگر از زمین",
                gravityMssDisplayEn = "Apparent visual grouping from Earth's viewpoint",
                gravityComparedToEarth = null,
                distanceKm = null,
                distanceLightYears = null,
                distanceDisplayFa = "پوشش زاویه‌ای در کره آسمان",
                distanceDisplayEn = "Angular sky patch coverage"
            )
            com.alijafari.red.astronomy.domain.ObjectType.METEOR_SHOWER -> PhysicalProperties(
                diameterKm = null,
                diameterComparedToEarth = null, // Not applicable
                diameterDisplayFa = "نرخ سمت‌الراسی: ${obj.zhr} شهاب در ساعت (ZHR)",
                diameterDisplayEn = "Zenithal Hourly Rate: ${obj.zhr} meteors/hr",
                massKgDisplayFa = "ذرات میلی‌متری غبار دنباله‌دار در حال سوختن",
                massKgDisplayEn = "Millimeter cometary dust grains vaporizing in upper atmosphere",
                massComparedToEarth = null,
                gravityMssDisplayFa = "سرعت ورود به جو: ۴۰ تا ۷۰ کیلومتر بر ثانیه",
                gravityMssDisplayEn = "Atmospheric entry speed: 40 to 70 km/s",
                gravityComparedToEarth = null,
                distanceKm = 100.0, // Atmospheric altitude ~100 km
                distanceLightYears = null,
                distanceDisplayFa = "ارتفاع سوختن: ۸۰ تا ۱۲۰ کیلومتر در یونوسفر زمین",
                distanceDisplayEn = "Burn-up altitude: 80 to 120 km in Earth's ionosphere"
            )
            else -> PhysicalProperties(
                diameterKm = null,
                diameterComparedToEarth = null,
                diameterDisplayFa = "محاسبه‌شده بر اساس داده‌های کاتالوگ",
                diameterDisplayEn = "Calculated from catalog observation data",
                massKgDisplayFa = "جرم مشخصه رده ${obj.category}",
                massKgDisplayEn = "Characteristic mass for ${obj.category}",
                massComparedToEarth = null,
                gravityMssDisplayFa = "مشخصات گرانشی استاندارد",
                gravityMssDisplayEn = "Standard gravitational parameters",
                gravityComparedToEarth = null,
                distanceKm = if (obj.distanceLightYears > 0.001) obj.distanceLightYears * 9.461e12 else null,
                distanceLightYears = if (obj.distanceLightYears > 0.001) obj.distanceLightYears else null,
                distanceDisplayFa = if (obj.distanceLightYears < 0.001) "< 1 AU" else String.format("%,.0f سال نوری", obj.distanceLightYears),
                distanceDisplayEn = if (obj.distanceLightYears < 0.001) "< 1 AU" else String.format("%,.0f light-years", obj.distanceLightYears)
            )
        }
    }

    fun getCoolFactsFa(obj: CelestialObject): List<String> {
        coolFactsMap[obj.id]?.let { return it }
        return ExtendedFactCatalog.factsFor(obj.id)?.fa ?: emptyList()
    }

    fun getCoolFactsEn(obj: CelestialObject): List<String> {
        coolFactsMapEn[obj.id]?.let { return it }
        return ExtendedFactCatalog.factsFor(obj.id)?.en ?: emptyList()
    }
}
