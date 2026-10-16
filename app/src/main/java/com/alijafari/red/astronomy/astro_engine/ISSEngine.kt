package com.alijafari.red.astronomy.astro_engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import kotlin.math.*

/**
 * ISS (International Space Station) tracking engine.
 * Uses standard SGP4 propagator with WGS-84 geodetic frame transformations
 * and astronomical solar twilight illumination modeling.
 */
class ISSEngine {

    enum class PassClassification(
        val labelEn: String,
        val labelFa: String,
        val colorHex: Long
    ) {
        OUTSTANDING("Outstanding Pass", "گذر فوق‌العاده استثنایی", 0xFF2DC653),
        EXCELLENT("Excellent Pass", "گذر عالی", 0xFF38B000),
        VERY_GOOD("Very Good Pass", "گذر بسیار خوب", 0xFF70E000),
        GOOD("Good Pass", "گذر خوب", 0xFF9EF01A),
        MARGINAL("Marginal Pass", "گذر حاشیه‌ای / کم‌نور", 0xFFFFB703),
        POOR("Poor Pass", "گذر ضعیف (ارتفاع پایین)", 0xFFFF8C00),
        NOT_VISIBLE("Not Visible", "قابل مشاهده نیست", 0xFFE63946),
        INVISIBLE_SHADOW("Invisible (In Shadow)", "پنهان در سایه زمین (تاریک)", 0xFF6C757D),
        DAYLIGHT_ONLY("Daylight Only", "گذر در روز (روشنایی خورشید)", 0xFF4A90E2)
    }

    data class TLEData(
        val name: String = "ISS (ZARYA)",
        val line1: String = "1 25544U 98067A   26288.82192346  .00005580  00000+0  10980-3 0  9999",
        val line2: String = "2 25544  51.6317  63.1136 0006755 265.8514  94.1703 15.48879905590452",
        val epochYear: Int = StarlinkTrainManager.extractEpochYear(line1),
        val epochDay: Double = StarlinkTrainManager.extractEpochDay(line1)
    )

    data class ISSPass(
        val startTimeMs: Long,
        val maxTimeMs: Long,
        val endTimeMs: Long,
        val maxElevationDeg: Double,
        val maxAltitudeKm: Double,
        val maxAzimuthDeg: Double,
        val startAzimuthDeg: Double,
        val endAzimuthDeg: Double,
        val estimatedMagnitude: Double,
        val passDurationSec: Long,
        val sunAltitudeDegAtMax: Double,
        val isObserverInDarkness: Boolean,
        val isIssSunlitAtMax: Boolean,
        val shadowEntryMs: Long? = null,
        val shadowExitMs: Long? = null,
        val classification: PassClassification,
        val visibilityScore: Int,
        val summaryReasonEn: String,
        val summaryReasonFa: String,
        val detailedReasonsEn: List<String>,
        val detailedReasonsFa: List<String>,
        val isVisible: Boolean = false
    )

    data class TopocentricPosition(
        val elevationDeg: Double,
        val azimuthDeg: Double,
        val rangeKm: Double,
        val subLatDeg: Double,
        val subLonDeg: Double,
        val satAltKm: Double,
        val velocityKmS: Double,
        val isSunlit: Boolean,
        val xEcef: Double,
        val yEcef: Double,
        val zEcef: Double
    )

    private val sgp4 = SGP4Propagator()
    var cachedTLE: TLEData = TLEData()

    /**
     * Fetches live TLE data from CelesTrak for NORAD ID 25544 (ISS).
     */
    suspend fun fetchLatestTLE(): TLEData = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE")
            val connection = url.openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val text = connection.getInputStream().bufferedReader().use { it.readText() }
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

            if (lines.size >= 2) {
                val line1 = lines.find { it.startsWith("1 25544") }
                val line2 = lines.find { it.startsWith("2 25544") }
                if (line1 != null && line2 != null) {
                    val newTle = TLEData(
                        name = if (lines.size >= 3 && !lines[0].startsWith("1 ")) lines[0] else "ISS (ZARYA)",
                        line1 = line1,
                        line2 = line2
                    )
                    cachedTLE = newTle
                    return@withContext newTle
                }
            }
        } catch (e: Exception) {
            // Fallback to cached default TLE
        }
        return@withContext cachedTLE
    }

    /**
     * Calculates topocentric position using SGP4 propagator and WGS-84 frame transformations.
     */
    fun calculateTopocentricPos(
        timestampMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        userAltMeters: Double = 940.0,
        tle: TLEData = cachedTLE
    ): TopocentricPosition {
        val sgp4Tle = parseToSgp4(tle)
        val teme = sgp4.propagate(sgp4Tle, timestampMs)

        val currentJD = TimeEngine.getJulianDate(timestampMs)
        val gmstDeg = TimeEngine.getGMST(currentJD)
        val gmstRad = Math.toRadians(gmstDeg)

        // Rotate TEME (ECI) position to ECEF frame
        val cosGmst = cos(gmstRad)
        val sinGmst = sin(gmstRad)
        val xEcef = teme.xKm * cosGmst + teme.yKm * sinGmst
        val yEcef = -teme.xKm * sinGmst + teme.yKm * cosGmst
        val zEcef = teme.zKm

        // Sub-satellite point (Geodetic Latitude & Longitude using WGS-84 ellipsoid)
        val a = 6378.137 // WGS-84 semi-major axis (km)
        val f = 1.0 / 298.257223563
        val e2 = 2.0 * f - f * f
        val b = a * (1.0 - f)
        val ep2 = (a * a - b * b) / (b * b)

        val p = sqrt(xEcef * xEcef + yEcef * yEcef)
        val theta = atan2(zEcef * a, p * b)
        val subLatRad = atan2(
            zEcef + ep2 * b * sin(theta).pow(3),
            p - e2 * a * cos(theta).pow(3)
        )
        val subLonRad = atan2(yEcef, xEcef)

        val subLatDeg = Math.toDegrees(subLatRad)
        val subLonDeg = Math.toDegrees(subLonRad)

        val sinLat = sin(subLatRad)
        val nCur = a / sqrt(1.0 - e2 * sinLat * sinLat)
        val satAltKm = (p / cos(subLatRad)) - nCur
        val velKmS = sqrt(teme.vxKmS * teme.vxKmS + teme.vyKmS * teme.vyKmS + teme.vzKmS * teme.vzKmS)

        val isSunlit = checkIssSunlit(currentJD, gmstDeg, xEcef, yEcef, zEcef)

        // Observer WGS-84 geodetic position in ECEF
        val obsLatRad = Math.toRadians(userLatDeg)
        val obsLonRad = Math.toRadians(userLonDeg)
        val sinObsLat = sin(obsLatRad)
        val cosObsLat = cos(obsLatRad)
        val sinObsLon = sin(obsLonRad)
        val cosObsLon = cos(obsLonRad)

        val nObs = a / sqrt(1.0 - e2 * sinObsLat * sinObsLat)
        val obsAltKm = userAltMeters / 1000.0

        val obsXEcef = (nObs + obsAltKm) * cosObsLat * cosObsLon
        val obsYEcef = (nObs + obsAltKm) * cosObsLat * sinObsLon
        val obsZEcef = (nObs * (1.0 - e2) + obsAltKm) * sinObsLat

        val dX = xEcef - obsXEcef
        val dY = yEcef - obsYEcef
        val dZ = zEcef - obsZEcef

        // Topocentric coordinates (East, North, Up)
        val east = -sinObsLon * dX + cosObsLon * dY
        val north = -sinObsLat * cosObsLon * dX - sinObsLat * sinObsLon * dY + cosObsLat * dZ
        val up = cosObsLat * cosObsLon * dX + cosObsLat * sinObsLon * dY + sinObsLat * dZ

        val range = sqrt(east * east + north * north + up * up)
        val elevRad = asin((up / range).coerceIn(-1.0, 1.0))
        val azRad = (atan2(east, north) + 2.0 * PI) % (2.0 * PI)

        return TopocentricPosition(
            elevationDeg = Math.toDegrees(elevRad),
            azimuthDeg = Math.toDegrees(azRad),
            rangeKm = range,
            subLatDeg = subLatDeg,
            subLonDeg = subLonDeg,
            satAltKm = satAltKm,
            velocityKmS = velKmS,
            isSunlit = isSunlit,
            xEcef = xEcef,
            yEcef = yEcef,
            zEcef = zEcef
        )
    }

    /**
     * Exact Conical Earth Shadow (Umbra) calculation taking into account finite solar diameter
     * and atmospheric absorption layer (~20 km).
     */
    fun checkIssSunlit(jd: Double, gmstDeg: Double, xEcef: Double, yEcef: Double, zEcef: Double): Boolean {
        val sunPos = SunEngine.calculatePosition(jd)
        val raRad = Math.toRadians(sunPos.raDeg)
        val decRad = Math.toRadians(sunPos.decDeg)
        val gmstRad = Math.toRadians(gmstDeg)

        // Sun Unit Vector in ECEF frame
        val sunX = cos(decRad) * cos(raRad - gmstRad)
        val sunY = cos(decRad) * sin(raRad - gmstRad)
        val sunZ = sin(decRad)

        // Projection of satellite along Sun vector
        val s = xEcef * sunX + yEcef * sunY + zEcef * sunZ
        if (s > 0.0) {
            return true // On illuminated sunward hemisphere
        }

        // Perpendicular distance squared from Earth-Sun axis
        val rSatSq = xEcef * xEcef + yEcef * yEcef + zEcef * zEcef
        val dPerpSq = rSatSq - (s * s)
        if (dPerpSq <= 0.0) return false

        // Umbra cone radius at distance |s| behind Earth (accounting for 20 km atmospheric layer)
        val earthRadiusKm = 6378.137
        val atmoLayerKm = 20.0
        val rEff = earthRadiusKm + atmoLayerKm
        val sunRadiusKm = 696000.0
        val sunDistanceKm = 149597870.7
        val tanAlphaUmbra = (sunRadiusKm - rEff) / sunDistanceKm
        val rUmbra = rEff + s * tanAlphaUmbra // s is negative

        val rUmbraSq = if (rUmbra > 0.0) rUmbra * rUmbra else 0.0
        return dPerpSq > rUmbraSq
    }

    /**
     * Calculates Sun altitude for observer at a specific time.
     */
    fun getObserverSunAltitude(timestampMs: Long, userLatDeg: Double, userLonDeg: Double): Double {
        return SunEngine.getSunAltitude(timestampMs, userLatDeg, userLonDeg)
    }

    /**
     * Determines whether the satellite is sunlit in ECI coordinates.
     */
    fun isSatelliteSunlitEci(timestampMs: Long, tle: TLEData): Boolean {
        val currentJD = TimeEngine.getJulianDate(timestampMs)
        val gmstDeg = TimeEngine.getGMST(currentJD)
        val topo = calculateTopocentricPos(timestampMs, 0.0, 0.0, 0.0, tle)
        return checkIssSunlit(currentJD, gmstDeg, topo.xEcef, topo.yEcef, topo.zEcef)
    }

    /**
     * Checks if a sample satisfies the conditions for human naked-eye visibility:
     * - Satellite elevation >= 10.0°
     * - Observer sky is dark / twilight (Sun altitude <= -6.0°)
     * - Satellite is illuminated by the Sun (outside Earth's umbra)
     */
    fun checkSampleVisibility(
        timestampMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        tle: TLEData
    ): Boolean {
        val topo = calculateTopocentricPos(timestampMs, userLatDeg, userLonDeg, 940.0, tle)
        if (topo.elevationDeg < 10.0) return false
        val sunAlt = getObserverSunAltitude(timestampMs, userLatDeg, userLonDeg)
        if (sunAlt > -6.0) return false
        return topo.isSunlit
    }

    /**
     * Orbital pass prediction matching NASA Spot The Station and Heavens-Above standards:
     * 1. Scans forward in time with 12-second resolution to find geometric flyovers above 10° elevation.
     * 2. Refines geometric AOS, Peak (TCA), and LOS with 1-second accuracy.
     * 3. Evaluates visibility timeline (satellite sunlit & observer Sun altitude <= -6.0°).
     * 4. For dawn passes, accurately captures shadow exit (appearance from eclipse).
     * 5. For evening passes, accurately captures shadow entry (disappearance into eclipse).
     * 6. Strictly filters out daylight passes, deep shadow passes, and sub-threshold noise.
     */
    fun predictPasses(
        userLatDeg: Double,
        userLonDeg: Double,
        startTimestampMs: Long = System.currentTimeMillis(),
        tle: TLEData = cachedTLE,
        scanDays: Int = 7,
        visibleOnly: Boolean = true,
        standardMag: Double = -1.5
    ): List<ISSPass> {
        val noradId = try {
            val line1 = tle.line1.trim()
            if (line1.startsWith("1 ") && line1.length >= 7) {
                line1.substring(2, 7).trim().toInt()
            } else 25544
        } catch (e: Exception) {
            25544
        }
        val effectiveTle = SatelliteEngine.getEffectiveTle(noradId, tle)

        val passes = mutableListOf<ISSPass>()
        val scanDurationMs = scanDays * 24 * 3600 * 1000L
        val coarseStepMs = 12 * 1000L // 12-second coarse scan step
        val endTimeMs = startTimestampMs + scanDurationMs

        val horizonThreshold = 0.0 // Scan above geometric horizon

        var currentTime = startTimestampMs
        var prevTime = startTimestampMs
        var prevElev = calculateTopocentricPos(currentTime, userLatDeg, userLonDeg, 940.0, effectiveTle).elevationDeg
        var inPass = prevElev >= horizonThreshold
        var passAosMs = if (inPass) currentTime else 0L

        while (currentTime <= endTimeMs) {
            currentTime += coarseStepMs
            val pos = calculateTopocentricPos(currentTime, userLatDeg, userLonDeg, 940.0, effectiveTle)
            val elev = pos.elevationDeg

            if (!inPass && elev >= horizonThreshold) {
                inPass = true
                passAosMs = refineElevationThreshold(
                    t1 = prevTime,
                    t2 = currentTime,
                    targetElevationDeg = horizonThreshold,
                    isRising = true,
                    userLatDeg = userLatDeg,
                    userLonDeg = userLonDeg,
                    tle = effectiveTle
                )
            } else if (inPass && elev < horizonThreshold) {
                inPass = false
                val passLosMs = refineElevationThreshold(
                    t1 = prevTime,
                    t2 = currentTime,
                    targetElevationDeg = horizonThreshold,
                    isRising = false,
                    userLatDeg = userLatDeg,
                    userLonDeg = userLonDeg,
                    tle = effectiveTle
                )

                if (passLosMs > passAosMs + 25000L) { // Valid flyover must be at least 25s
                    val pass = analyzeAndBuildPass(
                        geomAosMs = passAosMs,
                        geomLosMs = passLosMs,
                        userLatDeg = userLatDeg,
                        userLonDeg = userLonDeg,
                        tle = effectiveTle,
                        standardMag = standardMag
                    )
                    if (pass != null) {
                        passes.add(pass)
                    }
                }
            }
            prevTime = currentTime
            prevElev = elev
        }

        val filteredPasses = if (visibleOnly) {
            passes.filter { it.isVisible }
        } else {
            passes
        }

        return filteredPasses.sortedWith(
            compareByDescending<ISSPass> { it.isVisible }
                .thenBy { it.startTimeMs }
        )
    }

    /**
     * Performs detailed analysis on a geometric flyover interval [geomAosMs, geomLosMs]
     * to evaluate visibility, shadow transitions, and twilight boundaries.
     */
    private fun analyzeAndBuildPass(
        geomAosMs: Long,
        geomLosMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        tle: TLEData,
        standardMag: Double
    ): ISSPass? {
        // 1. Refine geometric peak elevation and TCA
        val (geomPeakTimeMs, geomPeakPos) = refinePeakElevation(
            centerTimeMs = (geomAosMs + geomLosMs) / 2,
            searchRadiusMs = maxOf(10000L, (geomLosMs - geomAosMs) / 2 + 5000L),
            userLatDeg = userLatDeg,
            userLonDeg = userLonDeg,
            tle = tle
        )

        // Flyovers below 9.5° (e.g. unobservable low horizons) are filtered out
        if (geomPeakPos.elevationDeg < 9.5) {
            return null
        }

        // 2. High-resolution sampling (2.5-second steps) across flyover to inspect visibility
        val sampleStepMs = 2500L
        var firstVisibleSampleMs: Long? = null
        var lastVisibleSampleMs: Long? = null
        var maxVisibleElev = -90.0
        var maxVisibleTimeMs = geomPeakTimeMs

        var shadowEntryMs: Long? = null
        var shadowExitMs: Long? = null

        var prevSampleSunlit: Boolean? = null
        var prevSampleTime = geomAosMs

        var continuousVisSeconds = 0
        var maxContinuousVisSeconds = 0

        var t = geomAosMs
        while (t <= geomLosMs) {
            val topo = calculateTopocentricPos(t, userLatDeg, userLonDeg, 940.0, tle)
            val sunAlt = getObserverSunAltitude(t, userLatDeg, userLonDeg)

            val isSunlit = topo.isSunlit
            val isDark = sunAlt <= -6.0 // Civil twilight ended
            val isVisibleSample = topo.elevationDeg >= 5.0 && isSunlit && isDark

            if (prevSampleSunlit != null && isSunlit != prevSampleSunlit) {
                val transitionTime = refineShadowTransition(prevSampleTime, t, userLatDeg, userLonDeg, tle)
                if (!isSunlit && shadowEntryMs == null) shadowEntryMs = transitionTime
                if (isSunlit && shadowExitMs == null) shadowExitMs = transitionTime
            }
            prevSampleSunlit = isSunlit
            prevSampleTime = t

            if (isVisibleSample) {
                if (firstVisibleSampleMs == null) firstVisibleSampleMs = t
                lastVisibleSampleMs = t
                continuousVisSeconds += (sampleStepMs / 1000L).toInt()
                if (continuousVisSeconds > maxContinuousVisSeconds) {
                    maxContinuousVisSeconds = continuousVisSeconds
                }
                if (topo.elevationDeg > maxVisibleElev) {
                    maxVisibleElev = topo.elevationDeg
                    maxVisibleTimeMs = t
                }
            } else {
                continuousVisSeconds = 0
            }

            t += sampleStepMs
        }

        // 3. Determine if pass qualifies as visible (NASA Spot The Station & Heavens-Above criteria)
        val isPassVisible = maxContinuousVisSeconds >= 20 && maxVisibleElev >= 8.0

        if (isPassVisible && firstVisibleSampleMs != null && lastVisibleSampleMs != null) {
            // Refine visible start time (e.g. shadow exit, twilight dawn/dusk, or AOS above 5°)
            val visStartMs = when {
                shadowExitMs != null && shadowExitMs >= geomAosMs && shadowExitMs <= lastVisibleSampleMs -> {
                    // Dawn pass emerging from Earth shadow into sunlight!
                    shadowExitMs + 1000L
                }
                getObserverSunAltitude(geomAosMs, userLatDeg, userLonDeg) > -6.0 -> {
                    // Evening pass starting when Sun drops below -6.0°
                    refineSunAltitudeThreshold(geomAosMs, firstVisibleSampleMs, -6.0, isRising = false, userLatDeg, userLonDeg)
                }
                else -> {
                    // Refine time when satellite rises above 5° elevation
                    refineElevationThreshold(geomAosMs, firstVisibleSampleMs, 5.0, isRising = true, userLatDeg, userLonDeg, tle)
                }
            }

            // Refine visible end time (e.g. shadow entry, twilight dawn bright cutoff, or LOS below 5°)
            val visEndMs = when {
                shadowEntryMs != null && shadowEntryMs >= firstVisibleSampleMs && shadowEntryMs <= geomLosMs -> {
                    // Evening pass disappearing into Earth's shadow (eclipse)!
                    maxOf(visStartMs + 5000L, shadowEntryMs - 1000L)
                }
                getObserverSunAltitude(geomLosMs, userLatDeg, userLonDeg) > -6.0 -> {
                    // Dawn pass disappearing when Sun rises above -6.0°
                    refineSunAltitudeThreshold(lastVisibleSampleMs, geomLosMs, -6.0, isRising = true, userLatDeg, userLonDeg)
                }
                else -> {
                    // Refine time when satellite sets below 5° elevation
                    refineElevationThreshold(lastVisibleSampleMs, geomLosMs, 5.0, isRising = false, userLatDeg, userLonDeg, tle)
                }
            }

            // Refine visible maximum elevation within [visStartMs, visEndMs]
            val effectiveMaxTimeMs: Long
            val effectiveMaxElevDeg: Double
            val effectiveMaxAltKm: Double
            val effectiveMaxAzDeg: Double

            if (geomPeakTimeMs in visStartMs..visEndMs) {
                effectiveMaxTimeMs = geomPeakTimeMs
                effectiveMaxElevDeg = geomPeakPos.elevationDeg
                effectiveMaxAltKm = geomPeakPos.satAltKm
                effectiveMaxAzDeg = geomPeakPos.azimuthDeg
            } else {
                val startPos = calculateTopocentricPos(visStartMs, userLatDeg, userLonDeg, 940.0, tle)
                val endPos = calculateTopocentricPos(visEndMs, userLatDeg, userLonDeg, 940.0, tle)
                if (startPos.elevationDeg >= endPos.elevationDeg) {
                    effectiveMaxTimeMs = visStartMs
                    effectiveMaxElevDeg = startPos.elevationDeg
                    effectiveMaxAltKm = startPos.satAltKm
                    effectiveMaxAzDeg = startPos.azimuthDeg
                } else {
                    effectiveMaxTimeMs = visEndMs
                    effectiveMaxElevDeg = endPos.elevationDeg
                    effectiveMaxAltKm = endPos.satAltKm
                    effectiveMaxAzDeg = endPos.azimuthDeg
                }
            }

            val startPos = calculateTopocentricPos(visStartMs, userLatDeg, userLonDeg, 940.0, tle)
            val endPos = calculateTopocentricPos(visEndMs, userLatDeg, userLonDeg, 940.0, tle)

            return buildPass(
                startMs = visStartMs,
                maxMs = effectiveMaxTimeMs,
                endMs = visEndMs,
                maxElevDeg = effectiveMaxElevDeg,
                maxSatAltKm = effectiveMaxAltKm,
                startAzDeg = startPos.azimuthDeg,
                maxAzDeg = effectiveMaxAzDeg,
                endAzDeg = endPos.azimuthDeg,
                shadowEntryMs = shadowEntryMs,
                shadowExitMs = shadowExitMs,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                tle = tle,
                standardMag = standardMag,
                isVisible = true
            )
        } else {
            // Non-visible geometric pass
            val startPos = calculateTopocentricPos(geomAosMs, userLatDeg, userLonDeg, 940.0, tle)
            val endPos = calculateTopocentricPos(geomLosMs, userLatDeg, userLonDeg, 940.0, tle)

            return buildPass(
                startMs = geomAosMs,
                maxMs = geomPeakTimeMs,
                endMs = geomLosMs,
                maxElevDeg = geomPeakPos.elevationDeg,
                maxSatAltKm = geomPeakPos.satAltKm,
                startAzDeg = startPos.azimuthDeg,
                maxAzDeg = geomPeakPos.azimuthDeg,
                endAzDeg = endPos.azimuthDeg,
                shadowEntryMs = shadowEntryMs,
                shadowExitMs = shadowExitMs,
                userLatDeg = userLatDeg,
                userLonDeg = userLonDeg,
                tle = tle,
                standardMag = standardMag,
                isVisible = false
            )
        }
    }

    /**
     * Refines the timestamp where observer solar altitude crosses a threshold (e.g. -6.0°).
     */
    private fun refineSunAltitudeThreshold(
        t1: Long,
        t2: Long,
        targetSunAltDeg: Double,
        isRising: Boolean,
        userLatDeg: Double,
        userLonDeg: Double
    ): Long {
        var low = minOf(t1, t2)
        var high = maxOf(t1, t2)
        var bestTime = (low + high) / 2

        for (iter in 0..12) {
            val mid = (low + high) / 2
            val alt = getObserverSunAltitude(mid, userLatDeg, userLonDeg)
            bestTime = mid
            if (isRising) {
                if (alt < targetSunAltDeg) low = mid else high = mid
            } else {
                if (alt > targetSunAltDeg) low = mid else high = mid
            }
        }
        return bestTime
    }

    /**
     * Refines the timestamp where satellite elevation crosses a threshold (e.g. 10.0°) using bisection.
     */
    private fun refineElevationThreshold(
        t1: Long,
        t2: Long,
        targetElevationDeg: Double,
        isRising: Boolean,
        userLatDeg: Double,
        userLonDeg: Double,
        tle: TLEData
    ): Long {
        var low = minOf(t1, t2)
        var high = maxOf(t1, t2)
        var bestTime = (low + high) / 2

        for (iter in 0..14) {
            val mid = (low + high) / 2
            val elev = calculateTopocentricPos(mid, userLatDeg, userLonDeg, 940.0, tle).elevationDeg
            bestTime = mid
            if (isRising) {
                if (elev < targetElevationDeg) {
                    low = mid
                } else {
                    high = mid
                }
            } else {
                if (elev > targetElevationDeg) {
                    low = mid
                } else {
                    high = mid
                }
            }
        }
        return bestTime
    }

    /**
     * Refines peak elevation and its exact timestamp using golden-section search.
     */
    private fun refinePeakElevation(
        centerTimeMs: Long,
        searchRadiusMs: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        tle: TLEData
    ): Pair<Long, TopocentricPosition> {
        var a = centerTimeMs - searchRadiusMs
        var b = centerTimeMs + searchRadiusMs
        val invPhi = 0.618033988749895
        val invPhiSq = 0.381966011250105

        var c = a + (invPhiSq * (b - a)).toLong()
        var d = a + (invPhi * (b - a)).toLong()
        var posC = calculateTopocentricPos(c, userLatDeg, userLonDeg, 940.0, tle)
        var posD = calculateTopocentricPos(d, userLatDeg, userLonDeg, 940.0, tle)

        for (iter in 0..14) {
            if (posC.elevationDeg < posD.elevationDeg) {
                a = c
                c = d
                posC = posD
                d = a + (invPhi * (b - a)).toLong()
                posD = calculateTopocentricPos(d, userLatDeg, userLonDeg, 940.0, tle)
            } else {
                b = d
                d = c
                posD = posC
                c = a + (invPhiSq * (b - a)).toLong()
                posC = calculateTopocentricPos(c, userLatDeg, userLonDeg, 940.0, tle)
            }
        }

        val bestTime = (a + b) / 2
        val bestPos = calculateTopocentricPos(bestTime, userLatDeg, userLonDeg, 940.0, tle)
        return Pair(bestTime, bestPos)
    }

    /**
     * Refines shadow entry/exit boundary to 1-second accuracy.
     */
    private fun refineShadowTransition(
        t1: Long,
        t2: Long,
        userLatDeg: Double,
        userLonDeg: Double,
        tle: TLEData
    ): Long {
        var low = minOf(t1, t2)
        var high = maxOf(t1, t2)
        val initialSunlit = calculateTopocentricPos(low, userLatDeg, userLonDeg, 940.0, tle).isSunlit

        for (iter in 0..12) {
            val mid = (low + high) / 2
            val sunlit = calculateTopocentricPos(mid, userLatDeg, userLonDeg, 940.0, tle).isSunlit
            if (sunlit == initialSunlit) {
                low = mid
            } else {
                high = mid
            }
        }
        return (low + high) / 2
    }

    private fun buildPass(
        startMs: Long,
        maxMs: Long,
        endMs: Long,
        maxElevDeg: Double,
        maxSatAltKm: Double,
        startAzDeg: Double,
        maxAzDeg: Double,
        endAzDeg: Double,
        shadowEntryMs: Long?,
        shadowExitMs: Long?,
        userLatDeg: Double,
        userLonDeg: Double,
        tle: TLEData,
        standardMag: Double = -1.5,
        isVisible: Boolean = false
    ): ISSPass {
        val posAtMax = calculateTopocentricPos(maxMs, userLatDeg, userLonDeg, 940.0, tle)
        val sunAltAtMax = getObserverSunAltitude(maxMs, userLatDeg, userLonDeg)

        val rangeFactor = 5.0 * log10(maxOf(0.1, posAtMax.rangeKm / 400.0))
        val extFactor = if (maxElevDeg < 15.0) 0.5 else 0.0
        val estMag = standardMag + rangeFactor + extFactor

        val isDarkness = sunAltAtMax <= -6.0
        val isSunlit = posAtMax.isSunlit

        val durationSec = maxOf(25L, (endMs - startMs) / 1000L)

        val classification: PassClassification
        var score = 0
        val reasonsEn = mutableListOf<String>()
        val reasonsFa = mutableListOf<String>()

        if (!isVisible) {
            if (sunAltAtMax > -6.0) {
                classification = PassClassification.DAYLIGHT_ONLY
                score = 10
                reasonsEn.add("✕ Pass occurs during daylight (Sun altitude ${String.format("%.1f", sunAltAtMax)}° > -6°)")
                reasonsFa.add("✕ گذر در روشنایی روز (ارتفاع خورشید ${String.format("%.1f", sunAltAtMax)} درجه) رخ می‌دهد")
            } else if (!isSunlit) {
                classification = PassClassification.INVISIBLE_SHADOW
                score = 15
                reasonsEn.add("✕ Satellite is inside Earth's umbra shadow cone (Eclipsed)")
                reasonsFa.add("✕ ماهواره در مخروط سایه زمین قرار دارد (تاریک)")
            } else {
                classification = PassClassification.NOT_VISIBLE
                score = 20
                reasonsEn.add("✕ Pass is not observable to the naked eye")
                reasonsFa.add("✕ گذر برای چشم غیرمسلح قابل رویت نیست")
            }
        } else {
            // Visible pass evaluation
            if (sunAltAtMax <= -18.0) {
                score += 40
                reasonsEn.add("✓ Observer in true astronomical darkness (Sun ${String.format("%.1f", sunAltAtMax)}°)")
                reasonsFa.add("✓ ناظر در تاریکی کامل نجومی قرار دارد")
            } else if (sunAltAtMax <= -12.0) {
                score += 35
                reasonsEn.add("✓ Nautical twilight darkness (Sun ${String.format("%.1f", sunAltAtMax)}°)")
                reasonsFa.add("✓ گرگ و میش دریانوردی (شرایط عالی)")
            } else {
                score += 25
                reasonsEn.add("✓ Civil twilight ended (Sun ${String.format("%.1f", sunAltAtMax)}°)")
                reasonsFa.add("✓ پایان گرگ و میش شهری (آسمان تاریک)")
            }

            reasonsEn.add("✓ Satellite is brightly illuminated by solar radiation")
            reasonsFa.add("✓ ماهواره در معرض مستقیم نور خورشید است")
            score += 30

            // Mention shadow events if applicable
            if (shadowExitMs != null && shadowExitMs in (startMs - 5000L)..(startMs + 5000L)) {
                reasonsEn.add("✓ Dawn emergence: ISS leaves Earth shadow at ${String.format("%.0f", calculateTopocentricPos(startMs, userLatDeg, userLonDeg, 940.0, tle).elevationDeg)}° elevation")
                reasonsFa.add("✓ پدیده خروج از سایه: ایستگاه در ارتفاع ${String.format("%.0f", calculateTopocentricPos(startMs, userLatDeg, userLonDeg, 940.0, tle).elevationDeg)} درجه از سایه زمین خارج و نمایان می‌شود")
            }
            if (shadowEntryMs != null && shadowEntryMs in (endMs - 5000L)..(endMs + 5000L)) {
                reasonsEn.add("✓ Evening eclipse: ISS enters Earth shadow at ${String.format("%.0f", calculateTopocentricPos(endMs, userLatDeg, userLonDeg, 940.0, tle).elevationDeg)}° elevation")
                reasonsFa.add("✓ پدیده ورود به سایه: ایستگاه در ارتفاع ${String.format("%.0f", calculateTopocentricPos(endMs, userLatDeg, userLonDeg, 940.0, tle).elevationDeg)} درجه وارد سایه زمین شده و محو می‌شود")
            }

            when {
                maxElevDeg >= 50.0 -> {
                    score += 30
                    reasonsEn.add("✓ High peak elevation (${maxElevDeg.toInt()}°)")
                    reasonsFa.add("✓ زاویه اوج بسیار بالا (${maxElevDeg.toInt()} درجه)")
                }
                maxElevDeg >= 25.0 -> {
                    score += 20
                    reasonsEn.add("✓ Good peak elevation (${maxElevDeg.toInt()}°)")
                    reasonsFa.add("✓ زاویه اوج مناسب (${maxElevDeg.toInt()} درجه)")
                }
                maxElevDeg >= 15.0 -> {
                    score += 10
                    reasonsEn.add("⚠ Moderate peak elevation (${maxElevDeg.toInt()}°)")
                    reasonsFa.add("⚠ زاویه اوج متوسط (${maxElevDeg.toInt()} درجه)")
                }
                else -> {
                    score += 2
                    reasonsEn.add("⚠ Low horizon pass (${maxElevDeg.toInt()}°)")
                    reasonsFa.add("⚠ گذر نزدیک به افق (${maxElevDeg.toInt()} درجه)")
                }
            }

            classification = when {
                score >= 88 && maxElevDeg >= 45.0 -> PassClassification.OUTSTANDING
                score >= 75 && maxElevDeg >= 30.0 -> PassClassification.EXCELLENT
                score >= 60 && maxElevDeg >= 20.0 -> PassClassification.VERY_GOOD
                maxElevDeg >= 15.0 -> PassClassification.GOOD
                else -> PassClassification.MARGINAL
            }
        }

        val summaryEn = when (classification) {
            PassClassification.OUTSTANDING -> "Brilliant overhead pass illuminated in dark night sky! Mag ${String.format("%.1f", estMag)}"
            PassClassification.EXCELLENT -> "Easily visible naked-eye pass with high elevation (${maxElevDeg.toInt()}°)"
            PassClassification.VERY_GOOD -> "Bright pass visible across dusk/dawn sky"
            PassClassification.GOOD -> "Visible as a moving bright star across open skies"
            PassClassification.MARGINAL -> "Faint or low horizon pass (${maxElevDeg.toInt()}°)"
            PassClassification.POOR -> "Obscured by atmospheric extinction or low elevation"
            PassClassification.NOT_VISIBLE -> "Not observable by unaided eye"
            PassClassification.INVISIBLE_SHADOW -> "ISS enters Earth shadow during flyover"
            PassClassification.DAYLIGHT_ONLY -> "Sky too bright during daylight hours"
        }

        val summaryFa = when (classification) {
            PassClassification.OUTSTANDING -> "گذر درخشان بالای سر در تاریکی کامل! قدر ${String.format("%.1f", estMag)}"
            PassClassification.EXCELLENT -> "گذر کاملاً واضح با چشم غیرمسلح و ارتفاع بالا (${maxElevDeg.toInt()} درجه)"
            PassClassification.VERY_GOOD -> "گذر پرنور و جذاب در شفق شامگاهی یا سپیده‌دم"
            PassClassification.GOOD -> "مانند یک ستاره پرنور متحرک در آسمان دیده می‌شود"
            PassClassification.MARGINAL -> "گذر کم‌نور یا نزدیک افق (${maxElevDeg.toInt()} درجه)"
            PassClassification.POOR -> "تحت تاثیر غبار افق یا ارتفاع بسیار پایین"
            PassClassification.NOT_VISIBLE -> "غیرقابل رویت با چشم غیرمسلح"
            PassClassification.INVISIBLE_SHADOW -> "ایستگاه در طی مسیر وارد سایه زمین می‌شود"
            PassClassification.DAYLIGHT_ONLY -> "روشنایی خورشید مانع دیدن ایستگاه است"
        }

        return ISSPass(
            startTimeMs = startMs,
            maxTimeMs = maxMs,
            endTimeMs = endMs,
            maxElevationDeg = maxElevDeg,
            maxAltitudeKm = maxSatAltKm,
            maxAzimuthDeg = maxAzDeg,
            startAzimuthDeg = startAzDeg,
            endAzimuthDeg = endAzDeg,
            estimatedMagnitude = estMag,
            passDurationSec = durationSec,
            sunAltitudeDegAtMax = sunAltAtMax,
            isObserverInDarkness = isDarkness,
            isIssSunlitAtMax = isSunlit,
            shadowEntryMs = shadowEntryMs,
            shadowExitMs = shadowExitMs,
            classification = classification,
            visibilityScore = score.coerceIn(0, 100),
            summaryReasonEn = summaryEn,
            summaryReasonFa = summaryFa,
            detailedReasonsEn = reasonsEn,
            detailedReasonsFa = reasonsFa,
            isVisible = isVisible
        )
    }

    private fun parseToSgp4(tle: TLEData): SGP4Propagator.TLEData {
        val epochYear = parseEpochYear(tle.line1)
        val epochDay = parseDoubleSafe(tle.line1, 20, 32, 225.5)
        val bStar = parseBStar(tle.line1)

        val inc = parseDoubleSafe(tle.line2, 8, 16, 51.64)
        val raan = parseDoubleSafe(tle.line2, 17, 25, 200.0)
        val ecc = parseEccentricity(tle.line2, 26, 33, 0.0005)
        val argp = parseDoubleSafe(tle.line2, 34, 42, 90.0)
        val ma = parseDoubleSafe(tle.line2, 43, 51, 270.0)
        val mm = parseDoubleSafe(tle.line2, 52, 63, 15.49)

        return SGP4Propagator.TLEData(
            epochYear = epochYear,
            epochDay = epochDay,
            inclinationDeg = inc,
            raanDeg = raan,
            eccentricity = ecc,
            argPerigeeDeg = argp,
            meanAnomalyDeg = ma,
            meanMotion = mm,
            bStar = bStar
        )
    }

    private fun parseEpochYear(line1: String): Int {
        return try {
            if (line1.length >= 20) {
                line1.substring(18, 20).trim().toIntOrNull() ?: 26
            } else {
                26
            }
        } catch (e: Exception) {
            26
        }
    }

    fun parseBStar(line1: String): Double {
        try {
            if (line1.length >= 61) {
                val bStr = line1.substring(53, 61).trim()
                if (bStr.isEmpty() || bStr == "00000-0" || bStr == "00000+0" || bStr == "0") return 0.0

                var sign = 1.0
                var s = bStr
                if (s.startsWith("-")) {
                    sign = -1.0
                    s = s.substring(1)
                } else if (s.startsWith("+")) {
                    s = s.substring(1)
                }

                val expSignIdx = maxOf(s.lastIndexOf('+'), s.lastIndexOf('-'))
                if (expSignIdx > 0) {
                    val mantissaStr = s.substring(0, expSignIdx)
                    val expStr = s.substring(expSignIdx)
                    val mantissaVal = mantissaStr.toDoubleOrNull() ?: return 0.0
                    val expVal = expStr.toDoubleOrNull() ?: return 0.0
                    val mantissa = mantissaVal / 10.0.pow(mantissaStr.length)
                    return sign * mantissa * 10.0.pow(expVal)
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return 0.0001
    }

    private fun parseDoubleSafe(str: String, start: Int, end: Int, defaultVal: Double): Double {
        return try {
            if (end <= str.length) str.substring(start, end).trim().toDouble() else defaultVal
        } catch (e: Exception) {
            defaultVal
        }
    }

    private fun parseEccentricity(str: String, start: Int, end: Int, defaultVal: Double): Double {
        return try {
            if (end <= str.length) ("0." + str.substring(start, end).trim()).toDouble() else defaultVal
        } catch (e: Exception) {
            defaultVal
        }
    }

    companion object {
        val defaultEngine = ISSEngine()

        var cachedTLE: TLEData
            get() = defaultEngine.cachedTLE
            set(value) {
                defaultEngine.cachedTLE = value
            }

        suspend fun fetchLatestTLE(): TLEData = defaultEngine.fetchLatestTLE()

        fun calculateTopocentricPos(
            timestampMs: Long,
            userLatDeg: Double,
            userLonDeg: Double,
            userAltMeters: Double = 940.0,
            tle: TLEData = defaultEngine.cachedTLE
        ): TopocentricPosition = defaultEngine.calculateTopocentricPos(
            timestampMs, userLatDeg, userLonDeg, userAltMeters, tle
        )

        fun checkIssSunlit(jd: Double, gmstDeg: Double, xEcef: Double, yEcef: Double, zEcef: Double): Boolean =
            defaultEngine.checkIssSunlit(jd, gmstDeg, xEcef, yEcef, zEcef)

        fun getObserverSunAltitude(timestampMs: Long, userLatDeg: Double, userLonDeg: Double): Double =
            defaultEngine.getObserverSunAltitude(timestampMs, userLatDeg, userLonDeg)

        fun predictPasses(
            userLatDeg: Double,
            userLonDeg: Double,
            startTimestampMs: Long = System.currentTimeMillis(),
            tle: TLEData = defaultEngine.cachedTLE,
            scanDays: Int = 7,
            visibleOnly: Boolean = true,
            standardMag: Double = -1.5
        ): List<ISSPass> = defaultEngine.predictPasses(
            userLatDeg, userLonDeg, startTimestampMs, tle, scanDays, visibleOnly, standardMag
        )
    }
}
