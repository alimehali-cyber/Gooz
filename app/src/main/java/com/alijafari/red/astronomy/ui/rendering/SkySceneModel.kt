package com.alijafari.red.astronomy.ui.rendering

import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.astro_engine.CoordinateEngine
import com.alijafari.red.astronomy.astro_engine.GalacticEngine
import com.alijafari.red.astronomy.astro_engine.MoonEngine
import com.alijafari.red.astronomy.astro_engine.PlanetEngine
import com.alijafari.red.astronomy.astro_engine.SunEngine
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.domain.CelestialObject
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything the Sky Canvas draws for one instant and one observer.
 *
 * Pure data with no Compose state and no drawing, so it can be computed off the main thread.
 * Shared by the Home hero card and the Live Sky app backdrop.
 */
data class SkySceneFrame(
    val jd: Double,
    val lastDeg: Double,
    val latitudeDeg: Double,
    val sunHoriz: CoordinateEngine.Horizontal,
    val moonData: MoonEngine.MoonData,
    val planetPositions: List<Triple<PlanetEngine.PlanetType, PlanetEngine.PlanetPosition, CoordinateEngine.Horizontal>>,
    val galacticPlanePoints: List<GalacticEngine.GalacticPlanePoint>,
    val catalogStars: List<Pair<CelestialObject, CoordinateEngine.Horizontal>>,
    val lightingState: LightingState,
    val isSolarEclipse: Boolean,
    val isLunarEclipse: Boolean,
    /**
     * The Sun and the Moon as the AR sky computes them (topocentric apparent places, extinction and the
     * Moon's glyph geometry: phase, bright limb, axis position angle, libration). The photographic Moon
     * is rendered from this, so the Sky Canvas and the AR screen show the same Moon. Null if unavailable.
     */
    val sunMoon: ArSkySceneComputer.SunMoon? = null
)

object SkySceneModel {

    /** Quantum used by [quantizeTimeMs]. The Live Sky backdrop does not quantise: it follows the effective instant. */
    const val TIME_QUANTUM_MS: Long = 60_000L

    /** Floors a timestamp to [TIME_QUANTUM_MS]. */
    fun quantizeTimeMs(timestampMs: Long): Long = timestampMs - Math.floorMod(timestampMs, TIME_QUANTUM_MS)

    // Catalog stars brighter than this are drawn on the sky. Deep-sky objects are intentionally excluded.
    private const val MAX_DRAWN_STAR_MAGNITUDE = 4.5

    // Immutable after first use. Lazy initialisation is synchronised, so this is safe from any thread.
    private val drawnCatalogStars: List<CelestialObject> by lazy {
        AstronomyCatalog.getStars().filter { it.magnitude <= MAX_DRAWN_STAR_MAGNITUDE }
    }

    /**
     * Pure computation of the sky for [jd] at the observer position. Has no side effects, and every
     * engine call it makes uses only local state, so it may run on any thread.
     */
    fun compute(jd: Double, latitudeDeg: Double, longitudeDeg: Double, elevationM: Double): SkySceneFrame {
        val lastDeg = TimeEngine.getLAST(jd, longitudeDeg)

        val sunPos = SunEngine.calculatePosition(jd)
        val sunHoriz = CoordinateEngine.equatorialToHorizontal(
            CoordinateEngine.Equatorial(sunPos.raDeg, sunPos.decDeg),
            lastDeg,
            latitudeDeg,
            elevationM
        )

        // Rise/set root-finding is skipped here because it is far too expensive for a per-frame or per-minute scene.
        val moonData = MoonEngine.calculateMoon(jd, latitudeDeg, longitudeDeg, elevationM, computeRiseSet = false)

        val planetPositions = PlanetEngine.PlanetType.values().mapNotNull { pType ->
            if (pType == PlanetEngine.PlanetType.PLUTO) null
            else {
                val pPos = PlanetEngine.calculatePlanet(pType, jd)
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(pPos.raDeg, pPos.decDeg),
                    lastDeg,
                    latitudeDeg,
                    elevationM
                )
                if (horiz.altitudeDeg > -2.0) Triple(pType, pPos, horiz) else null
            }
        }

        val galacticPlanePoints = GalacticEngine.calculateGalacticPlanePointsWithLast(lastDeg, latitudeDeg, elevationM)
            .filter { it.altitudeDeg > -5.0 }

        val catalogStars = drawnCatalogStars.mapNotNull { celestialObj ->
            val horiz = CoordinateEngine.equatorialToHorizontal(
                CoordinateEngine.Equatorial(celestialObj.raDeg, celestialObj.decDeg),
                lastDeg,
                latitudeDeg,
                elevationM
            )
            if (horiz.altitudeDeg > 0.0) Pair(celestialObj, horiz) else null
        }

        val isSolarEclipse = run {
            val dAzRad = Math.toRadians(HeroSkyProjection.azimuthDistanceDeg(sunHoriz.azimuthDeg, moonData.azimuthDeg))
            val sunAltRad = Math.toRadians(sunHoriz.altitudeDeg)
            val moonAltRad = Math.toRadians(moonData.altitudeDeg)
            val cosSep = (sin(sunAltRad) * sin(moonAltRad) + cos(sunAltRad) * cos(moonAltRad) * cos(dAzRad)).coerceIn(-1.0, 1.0)
            val angDistDeg = Math.toDegrees(acos(cosSep))
            val moonSemiDiamDeg = (moonData.angularDiameterArcmin / 120.0).coerceIn(0.24, 0.29)
            val solarContactLimitDeg = 0.267 + moonSemiDiamDeg
            sunHoriz.altitudeDeg > -0.5 && angDistDeg <= solarContactLimitDeg
        }

        val isLunarEclipse = run {
            val dKm = moonData.distanceKm.coerceIn(340000.0, 420000.0)
            // Geocentric angular distance between the Moon and the anti-solar point (Earth's umbral axis)
            val antiSolarSepDeg = Math.toDegrees(moonData.phaseAngleRad) * (1.0 + dKm / 149597870.7)
            // Earth horizontal parallax + Moon semi-diameter + Danjon 2% atmospheric umbra enlargement
            val parallaxDeg = Math.toDegrees(asin(6378.14 / dKm))
            val moonSemiDiamDeg = Math.toDegrees(asin(1737.4 / dKm))
            val umbralContactLimitDeg = 1.02 * (parallaxDeg + 0.0024 - 0.2666) + moonSemiDiamDeg
            moonData.altitudeDeg > -12.0 && antiSolarSepDeg <= umbralContactLimitDeg
        }

        val sunMoon = try {
            ArSkySceneComputer.sunAndMoon(
                TimeEngine.getTimestampFromJulianDate(jd),
                ArObserver(latitudeDeg, longitudeDeg, elevationM),
                null
            )
        } catch (e: Exception) {
            // Pure model (also unit-tested off-device): no android.util.Log here.
            System.err.println("SkySceneModel: Moon geometry unavailable: $e")
            null
        }

        val lightingState = LightingEngine.computeLightingState(
            sunAltDeg = sunHoriz.altitudeDeg,
            moonAltDeg = moonData.altitudeDeg,
            moonIlluminationPercent = moonData.illuminationPercent
        )

        return SkySceneFrame(
            jd = jd,
            lastDeg = lastDeg,
            latitudeDeg = latitudeDeg,
            sunHoriz = sunHoriz,
            moonData = moonData,
            planetPositions = planetPositions,
            galacticPlanePoints = galacticPlanePoints,
            catalogStars = catalogStars,
            lightingState = lightingState,
            isSolarEclipse = isSolarEclipse,
            isLunarEclipse = isLunarEclipse,
            sunMoon = sunMoon
        )
    }
}
