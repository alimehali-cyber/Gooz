package com.alijafari.red.astronomy.astro_engine

import android.content.Context
import android.content.SharedPreferences
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObjectType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RED Guided 1-Point Reference Alignment Layer.
 *
 * Replaces the legacy manual 3-axis (yaw/pitch/roll) slider calibration with a streamlined
 * heading-only correction:
 *
 * - Phone accelerometers already provide pitch and roll with <0.1° accuracy, so the residual
 *   error is almost entirely in the horizontal plane (magnetometer yaw drift of ~1.5°–3.0°).
 * - The user centers ONE bright celestial target in a crosshair reticle and taps "Align".
 * - The yaw offset ΔAz = normalize180(Az_target − Az_current) is applied strictly around the
 *   Earth-fixed vertical axis (Zenith), leaving pitch and roll completely invariant:
 *
 *       R_final = M(ΔAz) · R_true
 *
 *   where M(ΔAz) is a pure world-Z rotation, so downstream AR projections receive the
 *   corrected orientation transparently via [OrientationProvider].
 *
 * Camera-based image analysis / optical tracking is out of scope: this layer performs pure
 * orientation-matrix math and runs no camera streams or frame observers.
 */
data class ARCalibrationOffsets(
    val yawOffsetDeg: Float = 0f,
    val lastCalibratedTimeMs: Long = 0L,
    val referenceStarName: String = "",
    val lastLocationLat: Double? = null,
    val lastLocationLon: Double? = null,
    /**
     * True once a 1-point alignment has been applied (and until it is reset). Kept separately from
     * the offset value: an alignment that confirms the compass exactly (offset 0.0°) is still an
     * alignment, and a non-zero offset is no longer used as a proxy for "aligned".
     */
    val alignmentActive: Boolean = false
) {
    val isCalibrated: Boolean
        get() = alignmentActive
}

/**
 * A bright celestial candidate for guided 1-point reference alignment.
 *
 * @param priority 1 = solar system body (Moon/planets, visible even in Bortle 8–9),
 *                 2 = 1st-magnitude navigational star.
 */
data class AlignmentTarget(
    val id: String,
    val nameEn: String,
    val nameFa: String,
    val type: ObjectType,
    val celestialObject: CelestialObject,
    val azimuthDeg: Double,
    val altitudeDeg: Double,
    val magnitude: Double,
    val priority: Int
)

/**
 * Directional guidance from the phone's current boresight toward an [AlignmentTarget].
 *
 * @param arrowAngleRad screen angle: 0 points UP, +PI/2 points RIGHT (matches Finder UI).
 * @param arrowsVisible whether guide arrows should currently be shown (with hysteresis
 *        between [ARCalibrationManager.GUIDANCE_SHOW_ABOVE_DEG] and
 *        [ARCalibrationManager.GUIDANCE_HIDE_BELOW_DEG]).
 */
data class AlignmentGuidance(
    val deltaAzimuthDeg: Float,
    val deltaAltitudeDeg: Float,
    val separationDeg: Float,
    val arrowAngleRad: Float,
    val arrowsVisible: Boolean
)

data class RecalibrationPromptDecision(
    val shouldPrompt: Boolean,
    val isTimeStale: Boolean,
    val isLocationStale: Boolean,
    val ageDays: Long,
    val distanceKm: Double?
)

object ARCalibrationManager {

    private const val PREFS_NAME = "red_ar_calibration_prefs"
    private const val KEY_YAW = "calib_yaw_offset_deg"
    private const val KEY_TIME = "calib_timestamp_ms"
    private const val KEY_STAR = "calib_reference_star"
    private const val KEY_AUTO_PROMPT = "calib_auto_prompt_enabled"
    private const val KEY_LAST_LOCATION_LAT = "calib_last_location_lat"
    private const val KEY_LAST_LOCATION_LON = "calib_last_location_lon"
    private const val KEY_ALIGNMENT_ACTIVE = "calib_alignment_active"

    /**
     * Largest total yaw correction a 1-point alignment may establish. Phone compasses that are off by
     * more than this are mis-calibrated or disturbed (a figure-8 calibration is needed), and accepting
     * such an offset almost always means the wrong object was centred.
     */
    const val MAX_TOTAL_YAW_OFFSET_DEG = 45.0

    // Legacy 3-axis keys (pitch/roll) from the retired manual slider system.
    // No longer read; removed on init so no stale offsets can linger.
    private const val KEY_PITCH_LEGACY = "calib_pitch_offset_deg"
    private const val KEY_ROLL_LEGACY = "calib_roll_offset_deg"

    /** Minimum altitude for alignment targets: above buildings and haze. */
    const val TARGET_MIN_ALTITUDE_DEG = 15.0

    /** Maximum altitude for alignment targets: avoids awkward zenith neck-strain. */
    const val TARGET_MAX_ALTITUDE_DEG = 80.0

    /** Number of recommended targets presented to the user. */
    const val MAX_ALIGNMENT_TARGETS = 3

    /** Show guide arrows when the target is farther than this from the reticle center. */
    const val GUIDANCE_SHOW_ABOVE_DEG = 5.0f

    /** Hide guide arrows once the target is brought closer than this to the center. */
    const val GUIDANCE_HIDE_BELOW_DEG = 4.0f

    private var sharedPreferences: SharedPreferences? = null

    private val _calibrationFlow = MutableStateFlow(ARCalibrationOffsets())
    val calibrationFlow: StateFlow<ARCalibrationOffsets> = _calibrationFlow.asStateFlow()

    private val _autoPromptEnabledFlow = MutableStateFlow(true)
    val autoPromptEnabledFlow: StateFlow<Boolean> = _autoPromptEnabledFlow.asStateFlow()

    fun init(context: Context) {
        if (sharedPreferences == null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPreferences = prefs
            val yaw = prefs.getFloat(KEY_YAW, 0f)
            val time = prefs.getLong(KEY_TIME, 0L)
            val star = prefs.getString(KEY_STAR, "") ?: ""
            val autoPrompt = prefs.getBoolean(KEY_AUTO_PROMPT, true)
            val lat = if (prefs.contains(KEY_LAST_LOCATION_LAT)) prefs.getFloat(KEY_LAST_LOCATION_LAT, Float.NaN).toDouble() else null
            val lon = if (prefs.contains(KEY_LAST_LOCATION_LON)) prefs.getFloat(KEY_LAST_LOCATION_LON, Float.NaN).toDouble() else null
            // Older builds had no explicit flag: a stored non-zero offset meant "aligned".
            val active = if (prefs.contains(KEY_ALIGNMENT_ACTIVE)) prefs.getBoolean(KEY_ALIGNMENT_ACTIVE, false) else yaw != 0f
            _calibrationFlow.value = ARCalibrationOffsets(
                yawOffsetDeg = if (active) yaw else 0f,
                lastCalibratedTimeMs = time,
                referenceStarName = star,
                lastLocationLat = lat?.takeIf { it.isFinite() },
                lastLocationLon = lon?.takeIf { it.isFinite() },
                alignmentActive = active
            )
            _autoPromptEnabledFlow.value = autoPrompt
            // One-way migration: drop retired pitch/roll keys if present.
            if (prefs.contains(KEY_PITCH_LEGACY) || prefs.contains(KEY_ROLL_LEGACY)) {
                prefs.edit().remove(KEY_PITCH_LEGACY).remove(KEY_ROLL_LEGACY).apply()
            }
        }
    }

    fun isAutoPromptEnabled(): Boolean = _autoPromptEnabledFlow.value

    fun setAutoPromptEnabled(enabled: Boolean, context: Context? = null) {
        _autoPromptEnabledFlow.value = enabled
        val prefs = sharedPreferences ?: context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putBoolean(KEY_AUTO_PROMPT, enabled)?.apply()
    }

    fun getOffsets(): ARCalibrationOffsets = _calibrationFlow.value

    fun noteCalibrationCompleted(
        context: Context? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        referenceName: String = _calibrationFlow.value.referenceStarName
    ) {
        val current = _calibrationFlow.value
        val updated = current.copy(
            lastCalibratedTimeMs = System.currentTimeMillis(),
            referenceStarName = referenceName,
            lastLocationLat = latitude ?: current.lastLocationLat,
            lastLocationLon = longitude ?: current.lastLocationLon
        )
        _calibrationFlow.value = updated
        val prefs = sharedPreferences ?: context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.apply {
            putLong(KEY_TIME, updated.lastCalibratedTimeMs)
            putString(KEY_STAR, updated.referenceStarName)
            updated.lastLocationLat?.let { putFloat(KEY_LAST_LOCATION_LAT, it.toFloat()) }
            updated.lastLocationLon?.let { putFloat(KEY_LAST_LOCATION_LON, it.toFloat()) }
            apply()
        }
    }

    fun shouldSuggestRecalibration(
        currentTimeMs: Long = System.currentTimeMillis(),
        currentLatitude: Double? = null,
        currentLongitude: Double? = null,
        maxAgeDays: Long = 14L,
        maxDistanceKm: Double = 50.0
    ): RecalibrationPromptDecision {
        return shouldSuggestRecalibration(
            offsets = _calibrationFlow.value,
            currentTimeMs = currentTimeMs,
            currentLatitude = currentLatitude,
            currentLongitude = currentLongitude,
            maxAgeDays = maxAgeDays,
            maxDistanceKm = maxDistanceKm
        )
    }

    fun shouldSuggestRecalibration(
        offsets: ARCalibrationOffsets,
        currentTimeMs: Long = System.currentTimeMillis(),
        currentLatitude: Double? = null,
        currentLongitude: Double? = null,
        maxAgeDays: Long = 14L,
        maxDistanceKm: Double = 50.0
    ): RecalibrationPromptDecision {
        val hasCalibrationRecord = offsets.lastCalibratedTimeMs > 0L ||
                offsets.yawOffsetDeg != 0f ||
                offsets.lastLocationLat != null ||
                offsets.lastLocationLon != null
        if (!hasCalibrationRecord) {
            return RecalibrationPromptDecision(
                shouldPrompt = false,
                isTimeStale = false,
                isLocationStale = false,
                ageDays = 0L,
                distanceKm = null
            )
        }

        val ageMs = if (offsets.lastCalibratedTimeMs > 0L) currentTimeMs - offsets.lastCalibratedTimeMs else Long.MAX_VALUE
        val ageDays = if (ageMs == Long.MAX_VALUE) Long.MAX_VALUE else ageMs / 86_400_000L
        val isTimeStale = offsets.lastCalibratedTimeMs == 0L || ageMs > maxAgeDays * 86_400_000L
        val distanceKm = if (
            currentLatitude != null && currentLongitude != null &&
            offsets.lastLocationLat != null && offsets.lastLocationLon != null
        ) {
            haversineDistanceKm(offsets.lastLocationLat, offsets.lastLocationLon, currentLatitude, currentLongitude)
        } else {
            null
        }
        val isLocationStale = distanceKm != null && distanceKm > maxDistanceKm
        return RecalibrationPromptDecision(
            shouldPrompt = isTimeStale || isLocationStale,
            isTimeStale = isTimeStale,
            isLocationStale = isLocationStale,
            ageDays = ageDays,
            distanceKm = distanceKm
        )
    }

    fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0088
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return earthRadiusKm * c
    }

    // -------------------------------------------------------------------------
    // 1-Point Alignment math (heading-only / yaw correction)
    // -------------------------------------------------------------------------

    /**
     * Wraps an angle in degrees to the half-open range [-180°, +180°).
     */
    fun normalizeAngle180(angleDeg: Double): Double {
        var a = (angleDeg + 180.0) % 360.0
        if (a < 0) a += 360.0
        return a - 180.0
    }

    /**
     * Computes the yaw correction that snaps the current boresight heading onto the target:
     *   ΔAz = normalize180(Az_target − Az_current)
     */
    fun computeYawOffset(targetAzimuthDeg: Double, currentAzimuthDeg: Double): Float {
        return normalizeAngle180(targetAzimuthDeg - currentAzimuthDeg).toFloat()
    }

    /**
     * Applies a guided 1-point alignment.
     *
     * [currentAzimuthDeg] is the azimuth the AR view currently DISPLAYS for the boresight, i.e. the
     * fused compass heading with the existing alignment offset already applied. The residual
     * ΔAz = normalize180(Az_target − Az_current) is therefore ADDED to the stored offset:
     *
     *     offset_new = normalize180(offset_old + ΔAz)
     *
     * so re-aligning on an already aligned view leaves the offset unchanged (ΔAz ≈ 0) instead of
     * toggling it back to zero, which is what replacing the offset did.
     *
     * @return the new total yaw offset in degrees, or [Float.NaN] when the resulting total would exceed
     *         [MAX_TOTAL_YAW_OFFSET_DEG] (nothing is changed in that case).
     */
    fun applyOnePointAlignment(
        targetAzimuthDeg: Double,
        currentAzimuthDeg: Double,
        referenceName: String = "",
        context: Context? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): Float {
        val current = _calibrationFlow.value
        val residual = computeYawOffset(targetAzimuthDeg, currentAzimuthDeg)
        val previous = if (current.alignmentActive) current.yawOffsetDeg.toDouble() else 0.0
        val total = normalizeAngle180(previous + residual)
        if (kotlin.math.abs(total) > MAX_TOTAL_YAW_OFFSET_DEG) return Float.NaN
        val updated = ARCalibrationOffsets(
            yawOffsetDeg = total.toFloat(),
            lastCalibratedTimeMs = System.currentTimeMillis(),
            referenceStarName = referenceName,
            lastLocationLat = latitude,
            lastLocationLon = longitude,
            alignmentActive = true
        )
        _calibrationFlow.value = updated

        val prefs = sharedPreferences ?: context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.apply {
            putFloat(KEY_YAW, updated.yawOffsetDeg)
            putLong(KEY_TIME, updated.lastCalibratedTimeMs)
            putString(KEY_STAR, updated.referenceStarName)
            putBoolean(KEY_ALIGNMENT_ACTIVE, true)
            updated.lastLocationLat?.let { putFloat(KEY_LAST_LOCATION_LAT, it.toFloat()) }
            updated.lastLocationLon?.let { putFloat(KEY_LAST_LOCATION_LON, it.toFloat()) }
            apply()
        }
        return updated.yawOffsetDeg
    }

    fun resetAlignment(context: Context? = null) {
        _calibrationFlow.value = ARCalibrationOffsets(
            yawOffsetDeg = 0f,
            lastCalibratedTimeMs = 0L,
            referenceStarName = "",
            lastLocationLat = null,
            lastLocationLon = null,
            alignmentActive = false
        )
        val prefs = sharedPreferences ?: context?.applicationContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.apply {
            putFloat(KEY_YAW, 0f)
            putLong(KEY_TIME, 0L)
            putString(KEY_STAR, "")
            putBoolean(KEY_ALIGNMENT_ACTIVE, false)
            remove(KEY_LAST_LOCATION_LAT)
            remove(KEY_LAST_LOCATION_LON)
            apply()
        }
    }

    /**
     * Builds the pure world-Z rotation M(ΔAz) used for heading-only correction.
     *
     * Row-major 3x3 matrix (same convention as the declination correction in
     * [OrientationProvider]):
     *   M = [ cosD,  sinD, 0 ]
     *       [-sinD,  cosD, 0 ]
     *       [    0,     0, 1 ]
     *
     * Applied as R_final = M(ΔAz) · R_true, azimuth shifts by exactly +ΔAz while pitch
     * and roll remain invariant.
     */
    fun createYawOnlyRotationMatrix(
        yawDeg: Float,
        outMatrix: FloatArray = FloatArray(9)
    ): FloatArray {
        val rad = Math.toRadians(yawDeg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()

        outMatrix[0] = c
        outMatrix[1] = s
        outMatrix[2] = 0f

        outMatrix[3] = -s
        outMatrix[4] = c
        outMatrix[5] = 0f

        outMatrix[6] = 0f
        outMatrix[7] = 0f
        outMatrix[8] = 1f

        return outMatrix
    }

    /**
     * Multiplies two 3x3 matrices in row-major format: C = A * B
     */
    fun multiplyMatrix3x3(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(9)): FloatArray {
        for (i in 0..2) {
            val r = i * 3
            for (j in 0..2) {
                out[r + j] = a[r + 0] * b[0 * 3 + j] +
                             a[r + 1] * b[1 * 3 + j] +
                             a[r + 2] * b[2 * 3 + j]
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Dynamic 3-target recommendation (ultra-bright objects only)
    // -------------------------------------------------------------------------

    private data class TargetCandidate(
        val id: String,
        val nameEn: String,
        val nameFa: String,
        val priority: Int
    )

    private val solarSystemCandidates = listOf(
        TargetCandidate("moon", "Moon", "ماه", 1),
        TargetCandidate("planet_venus", "Venus", "زهره (Venus)", 1),
        TargetCandidate("planet_jupiter", "Jupiter", "مشتری (Jupiter)", 1),
        TargetCandidate("planet_saturn", "Saturn", "زحل (Saturn)", 1),
        TargetCandidate("planet_mars", "Mars", "مریخ (Mars)", 1)
    )

    private val navigationalStarCandidates = listOf(
        TargetCandidate("star_cma_sirius", "Sirius (α CMa)", "شباهنگ (Sirius)", 2),
        TargetCandidate("star_lyr_vega", "Vega (α Lyr)", "نسر واقع (Vega)", 2),
        TargetCandidate("star_boo_arcturus", "Arcturus (α Boo)", "نگهبان شمال (Arcturus)", 2),
        TargetCandidate("star_aur_capella", "Capella (α Aur)", "بزبان (Capella)", 2),
        TargetCandidate("star_ori_rigel", "Rigel (β Ori)", "پای شکارچی (Rigel)", 2),
        TargetCandidate("star_ori_betelgeuse", "Betelgeuse (α Ori)", "ابط‌الجوزا (Betelgeuse)", 2),
        TargetCandidate("star_aql_altair", "Altair (α Aql)", "نسر طایر (Altair)", 2),
        TargetCandidate("star_tau_aldebaran", "Aldebaran (α Tau)", "دبران (Aldebaran)", 2),
        TargetCandidate("star_vir_spica", "Spica (α Vir)", "بی‌سلاح (Spica)", 2),
        TargetCandidate("star_sco_antares", "Antares (α Sco)", "قلب‌العقرب (Antares)", 2)
    )

    /**
     * Pure visibility filter + brightness ranking over pre-computed candidates.
     * Keeps targets with altitude in [minAltitudeDeg, maxAltitudeDeg], sorts by
     * (priority, magnitude ascending = brighter first), and returns at most [maxTargets].
     */
    fun filterAndRankTargets(
        candidates: List<AlignmentTarget>,
        minAltitudeDeg: Double = TARGET_MIN_ALTITUDE_DEG,
        maxAltitudeDeg: Double = TARGET_MAX_ALTITUDE_DEG,
        maxTargets: Int = MAX_ALIGNMENT_TARGETS
    ): List<AlignmentTarget> {
        return candidates
            .filter { it.altitudeDeg >= minAltitudeDeg && it.altitudeDeg <= maxAltitudeDeg }
            .sortedWith(compareBy<AlignmentTarget> { it.priority }.thenBy { it.magnitude })
            .take(maxTargets)
    }

    /**
     * Computes the recommended alignment targets for the given time/location using the
     * existing (astropy-verified) astronomical engines. Positions are topocentric horizontal
     * coordinates; no engine math is modified here.
     */
    fun computeAlignmentTargets(
        jd: Double,
        latitude: Double,
        longitude: Double,
        elevationM: Double = 0.0
    ): List<AlignmentTarget> {
        val lastDeg = TimeEngine.getLAST(jd, longitude)
        val candidates = mutableListOf<AlignmentTarget>()

        // Priority 1: Moon (prominent visual reference, mag ≈ −12)
        try {
            val mData = MoonEngine.calculateMoon(
                jd = jd,
                latitude = latitude,
                longitude = longitude,
                elevationM = elevationM
            )
            candidates.add(
                AlignmentTarget(
                    id = "moon",
                    nameEn = "Moon",
                    nameFa = "ماه",
                    type = ObjectType.MOON,
                    celestialObject = AstronomyCatalog.MOON,
                    azimuthDeg = mData.azimuthDeg,
                    altitudeDeg = mData.altitudeDeg,
                    magnitude = -12.0,
                    priority = 1
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Priority 1: bright planets (Venus, Jupiter, Saturn, Mars)
        for (candidate in solarSystemCandidates) {
            if (candidate.id == "moon") continue
            try {
                val pObj = AstronomyCatalog.getById(candidate.id, jd) ?: continue
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(pObj.raDeg, pObj.decDeg),
                    lastDeg,
                    latitude,
                    elevationM
                )
                candidates.add(
                    AlignmentTarget(
                        id = candidate.id,
                        nameEn = candidate.nameEn,
                        nameFa = candidate.nameFa,
                        type = ObjectType.PLANET,
                        celestialObject = pObj,
                        azimuthDeg = horiz.azimuthDeg,
                        altitudeDeg = horiz.altitudeDeg,
                        magnitude = pObj.magnitude,
                        priority = candidate.priority
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Priority 2: 1st-magnitude navigational stars
        for (candidate in navigationalStarCandidates) {
            try {
                val sObj = AstronomyCatalog.getById(candidate.id, jd) ?: continue
                val horiz = CoordinateEngine.equatorialToHorizontal(
                    CoordinateEngine.Equatorial(sObj.raDeg, sObj.decDeg),
                    lastDeg,
                    latitude,
                    elevationM
                )
                candidates.add(
                    AlignmentTarget(
                        id = candidate.id,
                        nameEn = candidate.nameEn,
                        nameFa = candidate.nameFa,
                        type = ObjectType.STAR,
                        celestialObject = sObj,
                        azimuthDeg = horiz.azimuthDeg,
                        altitudeDeg = horiz.altitudeDeg,
                        magnitude = sObj.magnitude,
                        priority = candidate.priority
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return filterAndRankTargets(candidates)
    }

    // -------------------------------------------------------------------------
    // Directional guidance (pan/tilt arrows toward the selected target)
    // -------------------------------------------------------------------------

    /**
     * Computes guidance from the phone's current boresight (Az, Alt) toward the target's
     * topocentric (Az, Alt).
     *
     * Arrow angle convention matches the Finder UI: 0 rad points UP on screen,
     * +PI/2 points RIGHT. The horizontal delta is scaled by cos(targetAlt) to prevent
     * high-altitude yaw distortion.
     *
     * @param arrowsCurrentlyVisible previous visibility state; provides hysteresis so arrows
     *        appear above [GUIDANCE_SHOW_ABOVE_DEG] and disappear below
     *        [GUIDANCE_HIDE_BELOW_DEG] without flickering at the boundary.
     */
    fun computeGuidance(
        targetAzimuthDeg: Double,
        targetAltitudeDeg: Double,
        phoneAzimuthDeg: Double,
        phoneAltitudeDeg: Double,
        arrowsCurrentlyVisible: Boolean
    ): AlignmentGuidance {
        val dAz = normalizeAngle180(targetAzimuthDeg - phoneAzimuthDeg).toFloat()
        val dAlt = (targetAltitudeDeg - phoneAltitudeDeg).toFloat()

        // Spherical angular separation between boresight and target
        val targetAzRad = Math.toRadians(targetAzimuthDeg)
        val targetAltRad = Math.toRadians(targetAltitudeDeg)
        val phoneAzRad = Math.toRadians(phoneAzimuthDeg)
        val phoneAltRad = Math.toRadians(phoneAltitudeDeg)
        val cosSep = sin(targetAltRad) * sin(phoneAltRad) +
                cos(targetAltRad) * cos(phoneAltRad) * cos(targetAzRad - phoneAzRad)
        val separation = Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0))).toFloat()

        val effDAz = (dAz * cos(targetAltRad)).toFloat()
        val arrowAngleRad = atan2(effDAz, dAlt)

        val visible = if (arrowsCurrentlyVisible) {
            separation >= GUIDANCE_HIDE_BELOW_DEG
        } else {
            separation > GUIDANCE_SHOW_ABOVE_DEG
        }

        return AlignmentGuidance(
            deltaAzimuthDeg = dAz,
            deltaAltitudeDeg = dAlt,
            separationDeg = separation,
            arrowAngleRad = arrowAngleRad,
            arrowsVisible = visible
        )
    }
}
