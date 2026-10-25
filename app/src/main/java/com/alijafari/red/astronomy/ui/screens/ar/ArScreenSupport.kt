package com.alijafari.red.astronomy.ui.screens.ar

import com.alijafari.red.astronomy.ar.core.AngleUtil
import com.alijafari.red.astronomy.ar.core.ArCameraModel
import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArPlaced
import com.alijafari.red.astronomy.ar.core.ArSkyFrame
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.ar.core.ArSkySnapshot
import com.alijafari.red.astronomy.ar.core.ArStarCatalog
import com.alijafari.red.astronomy.ar.core.ArStaticTarget
import com.alijafari.red.astronomy.ar.core.ArTimeScales
import com.alijafari.red.astronomy.ar.core.M3
import com.alijafari.red.astronomy.ar.core.V3
import com.alijafari.red.astronomy.astro_engine.AlignmentTarget
import com.alijafari.red.astronomy.astro_engine.ARCalibrationManager
import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.FinderData
import com.alijafari.red.astronomy.astro_engine.LunarSolarEngine
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.astro_engine.SatelliteCatalog
import com.alijafari.red.astronomy.astro_engine.SatelliteEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.astro_engine.TleMetadata
import com.alijafari.red.astronomy.data.catalog.CanonicalAstroCatalog
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObjectType
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Live state of a tracked satellite for the AR sky. */
class ArSatelliteInfo(
    val id: String,
    val noradId: Int,
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val rangeKm: Double,
    val sunlit: Boolean,
    val magnitude: Double,
    val tle: TleMetadata?,
    /** Geometric topocentric directions 10 s and 20 s earlier (az, alt), for the motion streak. */
    val trail: List<Pair<Double, Double>>
)

object ArScreenSupport {

    private val staticTypes = setOf(
        ObjectType.STAR, ObjectType.DEEP_SKY, ObjectType.GALAXY, ObjectType.NEBULA, ObjectType.STAR_CLUSTER,
        ObjectType.GLOBULAR_CLUSTER, ObjectType.BLACK_HOLE, ObjectType.ASTERISM, ObjectType.METEOR_SHOWER,
        ObjectType.CONSTELLATION, ObjectType.REFERENCE_POINT
    )

    /** J2000 targets for every catalogue object with a fixed position (stars, deep-sky, radiants, …). */
    fun staticTargets(objects: List<CelestialObject>): List<ArStaticTarget> =
        objects.filter { it.type in staticTypes }.map { ArStaticTarget(it.id, it.raDeg, it.decDeg) }

    /** The AR catalogue: every canonical object (static positions; live positions come from the snapshot). */
    fun arCatalogue(): List<CelestialObject> =
        com.alijafari.red.astronomy.astro_engine.ARSkyCatalog.arSkyObjects(
            CanonicalAstroCatalog.getAllCanonicalObjects().map { CanonicalAstroCatalog.toCelestialObject(it) }
        )

    /** Satellite states (SGP4 with the best available TLE) for the AR sky. */
    fun satellites(timeMs: Long, lat: Double, lon: Double, heightM: Double): List<ArSatelliteInfo> {
        val out = ArrayList<ArSatelliteInfo>()
        for (sat in SatelliteCatalog.getVisibleList(null)) {
            try {
                val now = SatelliteEngine.calculateSatelliteState(sat, timeMs, lat, lon, heightM)
                val trail = listOf(10_000L, 20_000L).map { dt ->
                    val p = SatelliteEngine.calculateSatelliteState(sat, timeMs - dt, lat, lon, heightM).topocentric
                    p.azimuthDeg to p.elevationDeg
                }
                out.add(
                    ArSatelliteInfo(
                        id = "sat_${sat.noradId}",
                        noradId = sat.noradId,
                        azimuthDeg = now.topocentric.azimuthDeg,
                        altitudeDeg = now.topocentric.elevationDeg,
                        rangeKm = now.topocentric.rangeKm,
                        sunlit = now.topocentric.isSunlit,
                        magnitude = now.apparentMagnitude,
                        tle = now.tleMetadata,
                        trail = trail
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return out
    }

    /** Ids under which a satellite may appear in the canonical catalogue. */
    fun satelliteIdsFor(info: ArSatelliteInfo): List<String> = buildList {
        add(info.id)
        add("norad_${info.noradId}")
        add(info.noradId.toString())
        when (info.noradId) {
            25544 -> { add("sat_iss"); add("iss"); add("iss_zarya") }
            48274 -> { add("sat_tiangong"); add("tiangong") }
            20580 -> { add("sat_hubble"); add("hubble") }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Meteor showers: a radiant is only meaningful while the shower is active.
    // ----------------------------------------------------------------------------------------------

    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val activeRegex = Regex("""Active:\s*([A-Za-z]+)\s+(\d{1,2})\s*-\s*([A-Za-z]+)\s+(\d{1,2})""")

    /** Day-of-year window (start, end) parsed from "Active: Jul 17 - Aug 24 …", or null. */
    fun activityWindow(obj: CelestialObject): Pair<Int, Int>? {
        val m = activeRegex.find(obj.activePeakDateWindowEn) ?: return null
        fun month(s: String): Int = monthNames.indexOfFirst { s.lowercase(Locale.US).startsWith(it) }
        val m1 = month(m.groupValues[1]); val m2 = month(m.groupValues[3])
        if (m1 < 0 || m2 < 0) return null
        val d1 = m.groupValues[2].toInt(); val d2 = m.groupValues[4].toInt()
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        return (cum[m1] + d1) to (cum[m2] + d2)
    }

    fun isShowerActive(obj: CelestialObject, timeMs: Long): Boolean {
        val window = activityWindow(obj) ?: return true
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = timeMs }
        val month = cal.get(Calendar.MONTH)
        val cum = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val doy = cum[month] + cal.get(Calendar.DAY_OF_MONTH)
        val (a, b) = window
        return if (a <= b) doy in a..b else doy >= a || doy <= b
    }

    // ----------------------------------------------------------------------------------------------
    // Finder: guidance computed in screen space through the real projection, so it is right for any
    // phone roll and always uses the target's live apparent position.
    // ----------------------------------------------------------------------------------------------

    fun finder(
        target: CelestialObject,
        placed: ArPlaced,
        camera: ArCameraModel,
        previouslyArrived: Boolean
    ): FinderData {
        val bore = camera.boresightEnu()
        val sep = Math.toDegrees(V3.angleBetween(bore, placed.enu)).toFloat()
        val c = DoubleArray(2)
        // Screen direction (x right, y down) through the real projection; 0 rad = up, +π/2 = right.
        val angle = if (camera.screenDirection(placed.enu[0], placed.enu[1], placed.enu[2], c)) atan2(c[0], -c[1]).toFloat() else 0f
        val arrived = if (previouslyArrived) sep <= 3.0f else sep <= 1.5f
        val below = placed.altDeg < 0.0
        val (fa, en) = when {
            below -> "هشدار: این جرم در حال حاضر زیر افق قرار دارد (${String.format(Locale.US, "%.1f", placed.altDeg)}°)" to
                "Warning: Target is currently below the horizon (${String.format(Locale.US, "%.1f", placed.altDeg)}°)"
            arrived -> "هدف دقیقاً در مرکز دید شماست!" to "Target acquired in the centre reticle!"
            else -> {
                val deg = Math.toDegrees(angle.toDouble())
                when {
                    deg > -22.5 && deg <= 22.5 -> "گوشی را بالاتر بگیرید" to "Tilt up"
                    deg > 22.5 && deg <= 67.5 -> "به راست و بالا بچرخید" to "Turn right & tilt up"
                    deg > 67.5 && deg <= 112.5 -> "به سمت راست بچرخید" to "Turn right"
                    deg > 112.5 && deg <= 157.5 -> "به راست و پایین بچرخید" to "Turn right & tilt down"
                    deg > 157.5 || deg <= -157.5 -> "گوشی را پایین‌تر بگیرید" to "Tilt down"
                    deg > -157.5 && deg <= -112.5 -> "به چپ و پایین بچرخید" to "Turn left & tilt down"
                    deg > -112.5 && deg <= -67.5 -> "به سمت چپ بچرخید" to "Turn left"
                    else -> "به چپ و بالا بچرخید" to "Turn left & tilt up"
                }
            }
        }
        val dAz = AngleUtil.wrap180(placed.azDeg - Math.toDegrees(atan2(bore[0], bore[1])))
        val dAlt = placed.altDeg - Math.toDegrees(kotlin.math.asin(bore[2].coerceIn(-1.0, 1.0)))
        return FinderData(
            targetObject = target,
            targetAzimuthDeg = placed.azDeg,
            targetAltitudeDeg = placed.altDeg,
            deltaAzimuthDeg = dAz.toFloat(),
            deltaAltitudeDeg = dAlt.toFloat(),
            totalAngularDistanceDeg = sep,
            arrowAngleRad = angle,
            proximityFraction = (1.0f - sep / 60.0f).coerceIn(0.0f, 1.0f),
            instructionFa = fa,
            instructionEn = en,
            isArrived = arrived,
            isBelowHorizon = below
        )
    }

    // ----------------------------------------------------------------------------------------------
    // Alignment targets from the live apparent places (stars precessed, Moon topocentric).
    // ----------------------------------------------------------------------------------------------

    private val alignmentCandidates = listOf(
        Triple("moon", "Moon", "ماه") to 1,
        Triple("planet_venus", "Venus", "زهره (Venus)") to 1,
        Triple("planet_jupiter", "Jupiter", "مشتری (Jupiter)") to 1,
        Triple("planet_saturn", "Saturn", "زحل (Saturn)") to 1,
        Triple("planet_mars", "Mars", "مریخ (Mars)") to 1,
        Triple("star_cma_sirius", "Sirius (α CMa)", "شباهنگ (Sirius)") to 2,
        Triple("star_lyr_vega", "Vega (α Lyr)", "نسر واقع (Vega)") to 2,
        Triple("star_boo_arcturus", "Arcturus (α Boo)", "نگهبان شمال (Arcturus)") to 2,
        Triple("star_aur_capella", "Capella (α Aur)", "بزبان (Capella)") to 2,
        Triple("star_ori_rigel", "Rigel (β Ori)", "پای شکارچی (Rigel)") to 2,
        Triple("star_ori_betelgeuse", "Betelgeuse (α Ori)", "ابط‌الجوزا (Betelgeuse)") to 2,
        Triple("star_aql_altair", "Altair (α Aql)", "نسر طایر (Altair)") to 2,
        Triple("star_tau_aldebaran", "Aldebaran (α Tau)", "دبران (Aldebaran)") to 2,
        Triple("star_vir_spica", "Spica (α Vir)", "بی‌سلاح (Spica)") to 2,
        Triple("star_sco_antares", "Antares (α Sco)", "قلب‌العقرب (Antares)") to 2
    )

    fun alignmentTargets(snapshot: ArSkySnapshot, catalogue: List<CelestialObject>): List<AlignmentTarget> {
        val byId = catalogue.associateBy { it.id }
        val candidates = alignmentCandidates.mapNotNull { (names, priority) ->
            val (id, en, fa) = names
            val placed = snapshot.placementOf(id) ?: return@mapNotNull null
            val obj = byId[id] ?: byId[CanonicalAstroCatalog.resolveCanonicalId(id)] ?: return@mapNotNull null
            val body = snapshot.bodies.firstOrNull { it.id == id }
            AlignmentTarget(
                id = id,
                nameEn = en,
                nameFa = fa,
                type = obj.type,
                celestialObject = obj,
                azimuthDeg = placed.azDeg,
                altitudeDeg = placed.altDeg,
                magnitude = body?.magnitude ?: obj.magnitude,
                priority = priority
            )
        }
        return ARCalibrationManager.filterAndRankTargets(candidates)
    }

    // ----------------------------------------------------------------------------------------------
    // Rise / transit / set by direct search of the apparent altitude (moving bodies included).
    // ----------------------------------------------------------------------------------------------

    class RiseSet(val riseMs: Long?, val setMs: Long?, val transitMs: Long?, val alwaysUp: Boolean, val neverUp: Boolean)

    /**
     * Searches the local day containing [timeMs] (in [timezoneId]) for the crossings of the standard
     * rise/set altitude by the body's true (moving) apparent place: the Sun from the solar theory, the
     * Moon topocentrically for this observer, planets from VSOP87D and fixed objects precessed to date.
     */
    fun riseSet(obj: CelestialObject, timeMs: Long, observer: ArObserver, timezoneId: String?): RiseSet? {
        if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) return null
        val tz = TimeEngine.resolveTimeZone(timezoneId)
        val cal = Calendar.getInstance(tz).apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val stepMs = 10 * 60_000L
        val n = (24 * 60 / 10)
        val ls = LunarSolarEngine()
        fun altitude(t: Long): Double {
            val jd = ArTimeScales.jdUtcFromEpochMillis(t)
            val frame = ArSkyFrame(jd, ArObserver(observer.latitudeDeg, observer.longitudeDeg, observer.heightM, observer.pressureHpa, observer.temperatureC))
            val at = AstroTime(t)
            val enu: DoubleArray = when {
                obj.type == ObjectType.SUN -> {
                    val s = ls.calculateSun(at)
                    frame.enuFromTrueOfDate(s.raDeg, s.decDeg)
                }
                obj.id == "moon" -> {
                    val m = ls.calculateMoon(at)
                    val geo = V3.fromSpherical(Math.toRadians(m.raDeg), Math.toRadians(m.decDeg))
                    val v = DoubleArray(3) { geo[it] * m.distanceKm - frame.observerTodKm[it] }
                    V3.normalizeInPlace(v)
                    M3.mulVec(frame.enuFromTod, v)
                }
                obj.type == ObjectType.PLANET || obj.type == ObjectType.DWARF_PLANET -> {
                    val type = planetType(obj.id) ?: return Double.NaN
                    val p = PlanetEngine.calculatePlanet(type, at)
                    frame.enuFromMeanOfDateGeometric(p.raDeg, p.decDeg, p.distanceAU)
                }
                else -> frame.enuFromCatalog(obj.raDeg, obj.decDeg)
            }
            return ArSkyFrame.altitudeDeg(enu) // geometric altitude; the threshold carries refraction
        }
        // Geometric altitude of the centre at the instant of rise/set:
        //  Sun: −(34′ refraction + 16′ semidiameter); topocentric Moon: −(34′ + 15.5′);
        //  points: −34′ refraction (standard −0.5667°).
        val threshold = when {
            obj.type == ObjectType.SUN -> -0.8333
            obj.id == "moon" -> -0.825
            else -> -0.5667
        }
        val alts = DoubleArray(n + 1) { altitude(start + it * stepMs) }
        if (alts.any { it.isNaN() }) return null
        var rise: Long? = null
        var set: Long? = null
        var transit: Long? = null
        var maxAlt = -100.0
        for (i in 0 until n) {
            val a = alts[i] - threshold; val b = alts[i + 1] - threshold
            if (rise == null && a < 0 && b >= 0) rise = refine(start + i * stepMs, stepMs, threshold) { altitude(it) }
            if (set == null && a >= 0 && b < 0) set = refine(start + i * stepMs, stepMs, threshold) { altitude(it) }
            if (alts[i] > maxAlt) { maxAlt = alts[i]; transit = start + i * stepMs }
        }
        val alwaysUp = alts.all { it > threshold }
        val neverUp = alts.all { it <= threshold }
        return RiseSet(rise, set, transit, alwaysUp, neverUp)
    }

    private fun refine(t0: Long, step: Long, threshold: Double, alt: (Long) -> Double): Long {
        var lo = t0; var hi = t0 + step
        val loUp = alt(lo) >= threshold
        repeat(12) {
            val mid = (lo + hi) / 2
            if ((alt(mid) >= threshold) == loUp) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    fun planetType(id: String): PlanetEngine.PlanetType? = when (id) {
        "planet_mercury" -> PlanetEngine.PlanetType.MERCURY
        "planet_venus" -> PlanetEngine.PlanetType.VENUS
        "planet_mars" -> PlanetEngine.PlanetType.MARS
        "planet_jupiter" -> PlanetEngine.PlanetType.JUPITER
        "planet_saturn" -> PlanetEngine.PlanetType.SATURN
        "planet_uranus" -> PlanetEngine.PlanetType.URANUS
        "planet_neptune" -> PlanetEngine.PlanetType.NEPTUNE
        "planet_pluto" -> PlanetEngine.PlanetType.PLUTO
        else -> null
    }

    // ----------------------------------------------------------------------------------------------
    // Paths (past solid, future dashed) of the selected object, apparent and refracted.
    // ----------------------------------------------------------------------------------------------

    fun path(
        obj: CelestialObject,
        timeMs: Long,
        observer: ArObserver,
        catalogue: ArStarCatalog?
    ): Pair<List<CoordinateEngine.Horizontal>, List<CoordinateEngine.Horizontal>> {
        if (obj.type == ObjectType.SATELLITE || obj.id.startsWith("sat_")) {
            val sat = SatelliteEngine.resolveSatelliteItem(obj.id) ?: return emptyList<CoordinateEngine.Horizontal>() to emptyList()
            return SatelliteEngine.generateOrbitTrajectory(
                satellite = sat, currentTimestampMs = timeMs, userLatDeg = observer.latitudeDeg,
                userLonDeg = observer.longitudeDeg, userAltMeters = observer.heightM,
                pastMinutes = 30, futureMinutes = 30, stepSeconds = 20
            )
        }
        if (obj.type == ObjectType.REFERENCE_POINT) return emptyList<CoordinateEngine.Horizontal>() to emptyList()
        val past = ArrayList<CoordinateEngine.Horizontal>()
        val future = ArrayList<CoordinateEngine.Horizontal>()
        val step = 15 * 60_000L
        for (k in -48..48) {
            val t = timeMs + k * step
            val snap = ArSkySceneComputer.compute(
                t, observer, null, emptyList(),
                if (obj.type == ObjectType.SUN || obj.type == ObjectType.MOON || obj.type == ObjectType.PLANET || obj.type == ObjectType.DWARF_PLANET) emptyList()
                else listOf(ArStaticTarget(obj.id, obj.raDeg, obj.decDeg)),
                emptyMap(), null
            )
            val p = snap.placementOf(obj.id) ?: snap.placementOf(CanonicalAstroCatalog.resolveCanonicalId(obj.id)) ?: continue
            val h = CoordinateEngine.Horizontal(p.azDeg, p.altDeg)
            if (k <= 0) past.add(h)
            if (k >= 0) future.add(h)
        }
        return past to future
    }

    // ----------------------------------------------------------------------------------------------
    // Manual (sensor-off) attitude from drag gestures: azimuth/altitude with no roll.
    // ----------------------------------------------------------------------------------------------

    fun manualAttitude(azDeg: Double, altDeg: Double): DoubleArray {
        val az = Math.toRadians(azDeg); val alt = Math.toRadians(altDeg)
        val f = doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
        val r = doubleArrayOf(cos(az), -sin(az), 0.0)
        val u = doubleArrayOf(-sin(alt) * sin(az), -sin(alt) * cos(az), cos(alt))
        return doubleArrayOf(r[0], u[0], -f[0], r[1], u[1], -f[1], r[2], u[2], -f[2])
    }

    /** Device → ENU (double) from the provider's float matrix. */
    fun toDouble(m: FloatArray): DoubleArray = DoubleArray(9) { m[it].toDouble() }

    /** True if the matrix is a usable rotation (non-zero, orthonormal within tolerance). */
    fun isValidRotation(m: FloatArray): Boolean {
        if (m.size != 9) return false
        val n0 = sqrt((m[0] * m[0] + m[3] * m[3] + m[6] * m[6]).toDouble())
        val n2 = sqrt((m[2] * m[2] + m[5] * m[5] + m[8] * m[8]).toDouble())
        return abs(n0 - 1.0) < 0.05 && abs(n2 - 1.0) < 0.05
    }

    /** Refraction scale factor (P/1010)·(283/T) used by the GL sky. */
    fun refractionScale(observer: ArObserver): Float =
        ((observer.pressureHpa / 1010.0) * (283.0 / (273.0 + observer.temperatureC))).toFloat()
}
