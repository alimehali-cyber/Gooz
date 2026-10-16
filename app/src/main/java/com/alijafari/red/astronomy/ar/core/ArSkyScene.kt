package com.alijafari.red.astronomy.ar.core

import com.alijafari.red.astronomy.astro_engine.AstroTime
import com.alijafari.red.astronomy.astro_engine.JupiterMoonsEngine
import com.alijafari.red.astronomy.astro_engine.LunarSolarEngine
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class ArBodyKind { SUN, MOON, PLANET, JOVIAN_MOON }

/** Apparent topocentric placement (refracted) of any sky object. */
class ArPlaced(
    /** East-North-Up unit vector of the apparent (refracted) direction. */
    val enu: DoubleArray,
    val altDeg: Double,
    val azDeg: Double
)

/** A solar-system body with everything the glyph renderer and the labels need. */
class ArBodyState(
    val id: String,
    val kind: ArBodyKind,
    val body: BodyOrientation.Body?,
    val placed: ArPlaced,
    /** Visual magnitude outside the atmosphere. */
    val magnitude: Double,
    val angularRadiusDeg: Double,
    val distanceAu: Double,
    val glyph: GlyphGeometry.Result?,
    val illuminatedFraction: Double,
    /** Atmospheric extinction colour factor (linear RGB, 1 = none). */
    val extinction: DoubleArray
)

/** A J2000 catalogue position to be placed (deep-sky objects, named stars, radiants, …). */
class ArStaticTarget(val id: String, val raDeg: Double, val decDeg: Double)

/**
 * Everything computed for one instant; read-only, shared with the render thread.
 *
 * The snapshot carries the exact star catalogue and constellation figures it was computed from, and
 * the per-star / per-figure arrays are indexed by *those* lists only. Renderers must take the star
 * and figure lists from the snapshot, never from separately held state: the catalogue is loaded
 * asynchronously, so a snapshot computed before it arrived has no stars even though the catalogue may
 * already be in memory by the time the snapshot is drawn.
 */
class ArSkySnapshot(
    val timeMs: Long,
    val frame: ArSkyFrame,
    /** The star catalogue that [starEnu] and [starMag] are indexed by (null: not loaded yet, no stars). */
    val stars: ArStarCatalog?,
    /** 3 floats per catalogue star: apparent refracted ENU. */
    val starEnu: FloatArray,
    /** Observed magnitude per star (catalogue V + extinction relative to the zenith). */
    val starMag: FloatArray,
    val bodies: List<ArBodyState>,
    val placements: Map<String, ArPlaced>,
    /** The constellation figures that [constellationLabelEnu] is indexed by (their segments index [stars]). */
    val constellations: List<ArConstellation>,
    /** 3 floats per constellation: ENU of its label anchor (unrefracted; labels only). */
    val constellationLabelEnu: FloatArray,
    val sun: ArBodyState,
    val moon: ArBodyState
) {
    /** Number of stars with positions in this snapshot. */
    val starCount: Int get() = stars?.size ?: 0

    init {
        val n = stars?.size ?: 0
        require(starEnu.size == n * 3 && starMag.size == n) {
            "Star arrays (${starEnu.size}, ${starMag.size}) do not match the catalogue ($n stars)"
        }
        require(constellationLabelEnu.size == constellations.size * 3) {
            "Constellation labels (${constellationLabelEnu.size}) do not match the figures (${constellations.size})"
        }
    }

    fun placementOf(id: String): ArPlaced? = placements[id]
}

/**
 * Computes [ArSkySnapshot]s: apparent places of date (IAU 2006/2000B, aberration, proper motion,
 * parallax) and refraction with the observer's pressure and temperature, for the Yale Bright Star
 * Catalogue, the Sun (VSOP87-based solar theory), the Moon (Meeus ch. 47, topocentric), the planets
 * (VSOP87D with light time), the Galilean satellites and arbitrary J2000 targets.
 */
object ArSkySceneComputer {

    /** Mean V-band extinction coefficient (mag per airmass): Rayleigh + ozone + continental aerosol. */
    const val EXTINCTION_K_V = 0.20

    private val planetIds = listOf(
        Triple("planet_mercury", PlanetEngine.PlanetType.MERCURY, BodyOrientation.Body.MERCURY),
        Triple("planet_venus", PlanetEngine.PlanetType.VENUS, BodyOrientation.Body.VENUS),
        Triple("planet_mars", PlanetEngine.PlanetType.MARS, BodyOrientation.Body.MARS),
        Triple("planet_jupiter", PlanetEngine.PlanetType.JUPITER, BodyOrientation.Body.JUPITER),
        Triple("planet_saturn", PlanetEngine.PlanetType.SATURN, BodyOrientation.Body.SATURN),
        Triple("planet_uranus", PlanetEngine.PlanetType.URANUS, BodyOrientation.Body.URANUS),
        Triple("planet_neptune", PlanetEngine.PlanetType.NEPTUNE, BodyOrientation.Body.NEPTUNE)
    )

    private val jovianIds = mapOf(
        JupiterMoonsEngine.GalileanMoon.IO to ("jup_io" to 5.0),
        JupiterMoonsEngine.GalileanMoon.EUROPA to ("jup_europa" to 5.3),
        JupiterMoonsEngine.GalileanMoon.GANYMEDE to ("jup_ganymede" to 4.6),
        JupiterMoonsEngine.GalileanMoon.CALLISTO to ("jup_callisto" to 5.6)
    )

    fun compute(
        timeMs: Long,
        observer: ArObserver,
        stars: ArStarCatalog?,
        constellations: List<ArConstellation>,
        targets: List<ArStaticTarget>,
        /** id → (azimuth, altitude) geometric topocentric positions from SGP4. */
        satellites: Map<String, Pair<Double, Double>>,
        atmosphere: SkyAtmosphere?
    ): ArSkySnapshot {
        val jd = ArTimeScales.jdUtcFromEpochMillis(timeMs)
        val frame = ArSkyFrame(jd, observer)
        val at = AstroTime(timeMs)
        val placements = HashMap<String, ArPlaced>(targets.size + 64)

        // ---- Stars -------------------------------------------------------------------------------
        val n = stars?.size ?: 0
        val starEnu = FloatArray(n * 3)
        val starMag = FloatArray(n)
        if (stars != null) {
            val v = DoubleArray(3)
            for (i in 0 until n) {
                frame.enuFromCatalog(stars.raDeg[i], stars.decDeg[i], stars.pmRaCosDec[i], stars.pmDec[i], v)
                val altTrue = ArSkyFrame.altitudeDeg(v)
                if (altTrue > -2.0) frame.refractInPlace(v)
                starEnu[i * 3] = v[0].toFloat(); starEnu[i * 3 + 1] = v[1].toFloat(); starEnu[i * 3 + 2] = v[2].toFloat()
                val alt = ArSkyFrame.altitudeDeg(v)
                starMag[i] = if (alt < -1.0) 99f else (stars.vmag[i] + EXTINCTION_K_V * (SkyAtmosphere.airmass(alt) - 1.0)).toFloat()
            }
        }

        // ---- Sun & Moon --------------------------------------------------------------------------
        val ls = LunarSolarEngine()
        val sunPos = ls.calculateSun(at)
        val sunGeo = V3.fromSpherical(Math.toRadians(sunPos.raDeg), Math.toRadians(sunPos.decDeg))
        val sunTod = topocentricTod(frame, sunGeo, sunPos.distanceAu)
        val sunEnu = M3.mulVec(frame.enuFromTod, sunTod)
        val sunPlaced = placeRefracted(frame, sunEnu)
        val sunState = ArBodyState(
            id = "sun", kind = ArBodyKind.SUN, body = null, placed = sunPlaced, magnitude = -26.74,
            angularRadiusDeg = Math.toDegrees(kotlin.math.asin(695700.0 / (sunPos.distanceAu * 149597870.7))),
            distanceAu = sunPos.distanceAu, glyph = null, illuminatedFraction = 1.0,
            extinction = extinction(atmosphere, sunPlaced.altDeg)
        )

        val moonPos = ls.calculateMoon(at)
        val moonDistAu = moonPos.distanceKm / 149597870.7
        val moonGeo = V3.fromSpherical(Math.toRadians(moonPos.raDeg), Math.toRadians(moonPos.decDeg))
        val moonTod = topocentricTod(frame, moonGeo, moonDistAu)
        val moonTopoDistAu = topocentricDistanceAu(frame, moonGeo, moonDistAu)
        val moonEnu = M3.mulVec(frame.enuFromTod, moonTod)
        val moonPlaced = placeRefracted(frame, moonEnu)
        val moonExt = extinction(atmosphere, moonPlaced.altDeg)
        val moonGlyph0 = GlyphGeometry.build(
            BodyOrientation.Body.MOON, frame.jdTt, moonTod, moonTopoDistAu, sunTod, sunPos.distanceAu, frame.npbMatrix
        )
        // Earthshine ∝ Earth's illuminated fraction seen from the Moon (≈ 1 − k), strongly non-linear.
        val earthshine = 0.022 * (1.0 - moonGlyph0.illuminatedFraction).pow(1.6)
        val moonGlyph = GlyphGeometry.build(
            BodyOrientation.Body.MOON, frame.jdTt, moonTod, moonTopoDistAu, sunTod, sunPos.distanceAu, frame.npbMatrix,
            earthshine = earthshine, tint = moonExt
        )
        val moonPhase = moonGlyph.phaseAngleDeg
        val moonMag = -12.73 + 0.026 * moonPhase + 4e-9 * moonPhase.pow(4)
        val moonState = ArBodyState(
            id = "moon", kind = ArBodyKind.MOON, body = BodyOrientation.Body.MOON, placed = moonPlaced,
            magnitude = moonMag,
            angularRadiusDeg = Math.toDegrees(kotlin.math.asin(1737.4 / (moonTopoDistAu * 149597870.7))),
            distanceAu = moonTopoDistAu, glyph = moonGlyph, illuminatedFraction = moonGlyph.illuminatedFraction,
            extinction = moonExt
        )

        val bodies = ArrayList<ArBodyState>(16)
        bodies.add(sunState)
        bodies.add(moonState)
        placements["sun"] = sunPlaced
        placements["moon"] = moonPlaced

        // ---- Planets -----------------------------------------------------------------------------
        var jupiterState: ArBodyState? = null
        for ((id, type, body) in planetIds) {
            val pos = PlanetEngine.calculatePlanet(type, at)
            val enu = frame.enuFromMeanOfDateGeometric(pos.raDeg, pos.decDeg, pos.distanceAU)
            val tod = frame.todFromEnu(enu)
            val placed = placeRefracted(frame, enu)
            val ext = extinction(atmosphere, placed.altDeg)
            val glyph = GlyphGeometry.build(body, frame.jdTt, tod, pos.distanceAU, sunTod, sunPos.distanceAu, frame.npbMatrix, tint = ext)
            val state = ArBodyState(
                id = id, kind = ArBodyKind.PLANET, body = body, placed = placed, magnitude = pos.magnitude,
                angularRadiusDeg = pos.angularDiameterArcsec / 7200.0, distanceAu = pos.distanceAU,
                glyph = glyph, illuminatedFraction = glyph.illuminatedFraction, extinction = ext
            )
            bodies.add(state)
            placements[id] = placed
            if (type == PlanetEngine.PlanetType.JUPITER) jupiterState = state
        }

        // ---- Galilean satellites -------------------------------------------------------------------
        if (jupiterState != null) {
            val sys = JupiterMoonsEngine.calculateJupiterMoons(jd)
            val jp = sys.jupiterPos
            for (m in sys.moons) {
                val ids = jovianIds[m.moon] ?: continue
                val ra = jp.raDeg + m.offsetRaArcsec / 3600.0
                val dec = jp.decDeg + m.offsetDecArcsec / 3600.0
                val enu = frame.enuFromMeanOfDateGeometric(ra, dec, jp.distanceAU)
                val placed = placeRefracted(frame, enu)
                val hidden = m.phenomenon == JupiterMoonsEngine.MoonPhenomenon.OCCULTED ||
                    m.phenomenon == JupiterMoonsEngine.MoonPhenomenon.IN_ECLIPSE
                bodies.add(
                    ArBodyState(
                        id = ids.first, kind = ArBodyKind.JOVIAN_MOON, body = null, placed = placed,
                        magnitude = if (hidden) 99.0 else ids.second,
                        angularRadiusDeg = 0.0, distanceAu = jp.distanceAU, glyph = null, illuminatedFraction = 1.0,
                        extinction = extinction(atmosphere, placed.altDeg)
                    )
                )
                placements[ids.first] = placed
                placements["galilean_moon_" + m.moon.name.lowercase()] = placed
            }
        }

        // ---- Static J2000 targets ------------------------------------------------------------------
        val tmp = DoubleArray(3)
        for (t in targets) {
            frame.enuFromCatalog(t.raDeg, t.decDeg, 0.0, 0.0, tmp)
            placements[t.id] = placeRefracted(frame, tmp)
        }

        // ---- Satellites (SGP4 topocentric, geometric) ---------------------------------------------
        for ((id, azAlt) in satellites) {
            val e = ArSkyFrame.enuFromAltAz(azAlt.second, azAlt.first)
            placements[id] = placeRefracted(frame, e)
        }

        // ---- Constellation labels ------------------------------------------------------------------
        val labels = FloatArray(constellations.size * 3)
        for ((i, c) in constellations.withIndex()) {
            frame.enuFromCatalog(c.labelRaDeg, c.labelDecDeg, 0.0, 0.0, tmp)
            labels[i * 3] = tmp[0].toFloat(); labels[i * 3 + 1] = tmp[1].toFloat(); labels[i * 3 + 2] = tmp[2].toFloat()
        }

        return ArSkySnapshot(
            timeMs = timeMs,
            frame = frame,
            stars = stars,
            starEnu = starEnu,
            starMag = starMag,
            bodies = bodies,
            placements = placements,
            constellations = constellations,
            constellationLabelEnu = labels,
            sun = sunState,
            moon = moonState
        )
    }

    /** Refracts a copy of [enu] and wraps it with altitude/azimuth. */
    fun placeRefracted(frame: ArSkyFrame, enu: DoubleArray): ArPlaced {
        val v = enu.copyOf()
        V3.normalizeInPlace(v)
        frame.refractInPlace(v)
        return ArPlaced(v, ArSkyFrame.altitudeDeg(v), ArSkyFrame.azimuthDeg(v))
    }

    private fun topocentricTod(frame: ArSkyFrame, geoUnit: DoubleArray, distAu: Double): DoubleArray {
        val dKm = distAu * 149597870.7
        val v = doubleArrayOf(
            geoUnit[0] * dKm - frame.observerTodKm[0],
            geoUnit[1] * dKm - frame.observerTodKm[1],
            geoUnit[2] * dKm - frame.observerTodKm[2]
        )
        V3.normalizeInPlace(v)
        return v
    }

    private fun topocentricDistanceAu(frame: ArSkyFrame, geoUnit: DoubleArray, distAu: Double): Double {
        val dKm = distAu * 149597870.7
        val x = geoUnit[0] * dKm - frame.observerTodKm[0]
        val y = geoUnit[1] * dKm - frame.observerTodKm[1]
        val z = geoUnit[2] * dKm - frame.observerTodKm[2]
        return sqrt(x * x + y * y + z * z) / 149597870.7
    }

    private fun extinction(atmosphere: SkyAtmosphere?, altDeg: Double): DoubleArray {
        val out = DoubleArray(3)
        if (atmosphere == null) {
            // Analytic fallback: k = (0.13, 0.20, 0.33) mag/airmass in R, G, B.
            val x = SkyAtmosphere.airmass(max(altDeg, 0.3))
            out[0] = 10.0.pow(-0.4 * 0.13 * x); out[1] = 10.0.pow(-0.4 * 0.20 * x); out[2] = 10.0.pow(-0.4 * 0.33 * x)
        } else {
            atmosphere.extinctionRgb(Math.toRadians(max(altDeg, 0.3)), 0.1, out)
        }
        return out
    }
}
