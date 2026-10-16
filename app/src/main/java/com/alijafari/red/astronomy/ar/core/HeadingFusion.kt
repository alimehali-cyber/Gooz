package com.alijafari.red.astronomy.ar.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * True-north attitude fusion for the AR sky.
 *
 * Android's ROTATION_VECTOR already fuses gyro, accelerometer and magnetometer, but it trusts the
 * magnetometer blindly: a nearby steel beam, a magnetic phone case or a car body bends its heading
 * with no warning. This filter instead takes the drift-stable, magnetometer-free
 * GAME_ROTATION_VECTOR (gyro + accelerometer) as the attitude, and estimates only the single
 * missing degree of freedom — the yaw offset δ between the game frame and true north — from the
 * calibrated magnetometer, gating every magnetometer sample against the World Magnetic Model:
 *
 *  - total intensity |B| must match the WMM2025 F at the observer (soft σ 6 %, hard limit 25 %),
 *  - the measured dip angle must match the WMM2025 inclination I (soft σ 4°, hard limit 15°),
 *  - samples taken while the phone spins fast are down-weighted (sensor time-skew),
 *  - large sudden innovations are treated as outliers unless they persist (then re-converge).
 *
 * The declination D comes from WMM2025, so the reference is true (geographic) north.
 * Fallbacks: ROTATION_VECTOR + D when no game vector exists; game vector alone (heading needs a star
 * alignment) when no magnetometer exists.
 *
 * Conventions: device frame (x right, y up, z out of the screen); world frames are East-North-Up.
 * A rotation matrix R maps device vectors into the world frame (R · v_device = v_world), exactly
 * like `SensorManager.getRotationMatrixFromVector`.
 */
class HeadingFusion {

    enum class Mode {
        /** Nothing usable yet. */
        NONE,
        /** GAME_ROTATION_VECTOR attitude + WMM-gated magnetometer yaw (best). */
        FUSED,
        /** Android ROTATION_VECTOR (+ WMM declination); used until/unless FUSED can run. */
        ROTATION_VECTOR,
        /** Gyro attitude only: heading reference is arbitrary until a star alignment is made. */
        GYRO_ONLY,
        /** Accelerometer + magnetometer only (no gyroscope). */
        ACCEL_MAG
    }

    data class Diagnostics(
        val mode: Mode = Mode.NONE,
        /** Measured total field |B| in µT (calibrated magnetometer), NaN if unknown. */
        val fieldMeasuredUt: Double = Double.NaN,
        /** WMM2025 total intensity at the observer in µT, NaN if no location yet. */
        val fieldExpectedUt: Double = Double.NaN,
        /** Measured magnetic dip (inclination) in degrees, NaN if unknown. */
        val dipMeasuredDeg: Double = Double.NaN,
        /** WMM2025 inclination in degrees, NaN if no location yet. */
        val dipExpectedDeg: Double = Double.NaN,
        /** WMM2025 declination applied (degrees, east positive). */
        val declinationDeg: Double = Double.NaN,
        /** WMM2025 1-σ declination uncertainty (degrees). */
        val declinationSigmaDeg: Double = Double.NaN,
        /** Android magnetometer accuracy status (−1 no contact, 0 unreliable, 1 low, 2 medium, 3 high). */
        val magAccuracyStatus: Int = -2,
        /** Combined weight (0..1) of the last magnetometer sample. */
        val lastSampleWeight: Double = 0.0,
        /** True while the magnetic environment disagrees with the WMM (interference). */
        val interference: Boolean = false,
        /** Estimated 1-σ heading uncertainty of the output, degrees (NaN when unknown). */
        val headingSigmaDeg: Double = Double.NaN,
        /** ROTATION_VECTOR's own heading accuracy (values[4]) in degrees, NaN if not reported. */
        val rotationVectorAccuracyDeg: Double = Double.NaN,
        /** Fraction (0..1) of field directions sampled in the device frame (figure-8 coverage). */
        val coverage: Double = 0.0,
        /** Whether the yaw filter has converged on magnetometer data. */
        val converged: Boolean = false,
        /** Angular speed of the device in degrees per second. */
        val angularSpeedDegPerSec: Double = 0.0
    )

    // ------------------------------------------------------------------------------------------
    // Configuration / external inputs
    // ------------------------------------------------------------------------------------------

    private var expectedField: Wmm2025.Field? = null

    /** Sets (or clears) the WMM field expected at the observer. */
    fun setExpectedField(field: Wmm2025.Field?) {
        expectedField = field
    }

    val declinationDeg: Double get() = expectedField?.declinationDeg ?: 0.0

    // ------------------------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------------------------

    private val rGame = DoubleArray(9)
    private var hasGame = false
    private var lastGameTs = 0L
    private val prevQuat = DoubleArray(4)
    private var hasPrevQuat = false
    private var angularSpeedDegPerSec = 0.0

    private val rRv = DoubleArray(9)
    private var hasRv = false
    private var rvAccuracyDeg = Double.NaN

    private val rAccelMag = DoubleArray(9)
    private var hasAccelMag = false

    private var hasMagnetometerSensor = true
    private var magStatus = -2
    private var lastMagTs = 0L

    /** Yaw offset δ (radians): true azimuth = game azimuth − δ. */
    private var delta = 0.0
    private var deltaValid = false
    private var deltaSource = Mode.NONE
    private var convergedSamples = 0
    private var validTimeSec = 0.0
    private var outlierSinceNs = 0L
    private var lastWeight = 0.0
    private var innovationMs = 0.0 // EMA of innovation²
    private var interferenceSinceNs = 0L
    private var interference = false

    private var lastFieldUt = Double.NaN
    private var lastDipDeg = Double.NaN

    /** Weighted running mean of the field anomaly (deg of potential heading bias) of accepted samples. */
    private var acceptedAnomalyDeg = Double.NaN
    /** Seconds of magnetometer time since the yaw offset last received a well-weighted update. */
    private var secondsSinceGoodUpdate = 0.0

    // Figure-8 coverage: 26 direction bins (cube faces, edges and corners) of the device-frame field.
    private val coverageBins = BooleanArray(26)

    fun setHasMagnetometer(present: Boolean) {
        hasMagnetometerSensor = present
    }

    fun reset() {
        hasGame = false; hasRv = false; hasAccelMag = false; hasPrevQuat = false
        deltaValid = false; deltaSource = Mode.NONE; convergedSamples = 0; validTimeSec = 0.0
        outlierSinceNs = 0L; lastWeight = 0.0; innovationMs = 0.0; interference = false
        interferenceSinceNs = 0L; lastFieldUt = Double.NaN; lastDipDeg = Double.NaN
        lastMagTs = 0L; lastGameTs = 0L; angularSpeedDegPerSec = 0.0
        coverageBins.fill(false)
    }

    // ------------------------------------------------------------------------------------------
    // Sensor inputs
    // ------------------------------------------------------------------------------------------

    /** GAME_ROTATION_VECTOR sample, quaternion (x, y, z, w). */
    fun onGameRotation(qx: Double, qy: Double, qz: Double, qw: Double, timestampNs: Long) {
        quatToMatrix(qx, qy, qz, qw, rGame)
        if (hasPrevQuat && timestampNs > lastGameTs) {
            val dt = (timestampNs - lastGameTs) / 1e9
            val dot = abs(prevQuat[0] * qx + prevQuat[1] * qy + prevQuat[2] * qz + prevQuat[3] * qw)
            val ang = 2.0 * acos(dot.coerceIn(-1.0, 1.0))
            val speed = Math.toDegrees(ang) / max(dt, 1e-3)
            angularSpeedDegPerSec = angularSpeedDegPerSec * 0.8 + speed * 0.2
        }
        prevQuat[0] = qx; prevQuat[1] = qy; prevQuat[2] = qz; prevQuat[3] = qw
        hasPrevQuat = true
        lastGameTs = timestampNs
        hasGame = true
        // Seed the yaw offset from the OS rotation vector until magnetometer samples converge.
        if (!deltaValid && hasRv) seedDeltaFromRotationVector()
    }

    /** ROTATION_VECTOR sample, quaternion (x, y, z, w); heading accuracy in radians or NaN. */
    fun onRotationVector(qx: Double, qy: Double, qz: Double, qw: Double, headingAccuracyRad: Double) {
        quatToMatrix(qx, qy, qz, qw, rRv)
        hasRv = true
        rvAccuracyDeg = if (headingAccuracyRad.isFinite() && headingAccuracyRad > 0.0) Math.toDegrees(headingAccuracyRad) else Double.NaN
        if (hasGame && (!deltaValid || deltaSource == Mode.ROTATION_VECTOR)) seedDeltaFromRotationVector()
    }

    /** Orientation from accelerometer + magnetometer (no gyro), R from SensorManager.getRotationMatrix. */
    fun onAccelMagMatrix(r: FloatArray) {
        for (i in 0 until 9) rAccelMag[i] = r[i].toDouble()
        hasAccelMag = true
    }

    fun onMagAccuracy(status: Int) {
        magStatus = status
    }

    /**
     * Calibrated magnetometer sample (TYPE_MAGNETIC_FIELD), µT in the device frame.
     */
    fun onMagnetometer(mx: Double, my: Double, mz: Double, timestampNs: Long) {
        val fieldUt = sqrt(mx * mx + my * my + mz * mz)
        lastFieldUt = fieldUt
        if (fieldUt > 1.0) updateCoverage(mx / fieldUt, my / fieldUt, mz / fieldUt)
        val dt = if (lastMagTs > 0L && timestampNs > lastMagTs) ((timestampNs - lastMagTs) / 1e9).coerceIn(0.0, 0.5) else 0.02
        lastMagTs = timestampNs
        if (!hasGame) return

        // Field in the game world frame.
        val bx = rGame[0] * mx + rGame[1] * my + rGame[2] * mz
        val by = rGame[3] * mx + rGame[4] * my + rGame[5] * mz
        val bz = rGame[6] * mx + rGame[7] * my + rGame[8] * mz
        val bh = hypot(bx, by)
        val dipDeg = Math.toDegrees(atan2(-bz, bh))
        lastDipDeg = dipDeg
        if (bh < 1.0) return // field almost vertical: heading undefined

        val alphaM = atan2(bx, by) // game azimuth of magnetic north
        val expected = expectedField
        val declRad = Math.toRadians(expected?.declinationDeg ?: 0.0)
        val deltaMeas = AngleUtil.wrapPi(alphaM - declRad)

        // ---- sample weight ----
        var w = 1.0
        var hardReject = false
        if (expected != null) {
            val fExp = expected.f / 1000.0
            val rel = (fieldUt - fExp) / fExp
            if (abs(rel) > 0.25) hardReject = true
            w *= exp(-0.5 * (rel / 0.06) * (rel / 0.06))
            val dDip = dipDeg - expected.inclinationDeg
            if (abs(dDip) > 15.0) hardReject = true
            w *= exp(-0.5 * (dDip / 4.0) * (dDip / 4.0))
        }
        when (magStatus) {
            0 -> w *= 0.05   // SENSOR_STATUS_UNRELIABLE
            1 -> w *= 0.4    // LOW
            2 -> w *= 0.85   // MEDIUM
            -1 -> w *= 0.05  // NO_CONTACT
        }
        val spin = angularSpeedDegPerSec / 90.0
        w *= exp(-spin * spin)
        if (hardReject) w = 0.0
        lastWeight = w

        // Interference flag: persistently low weight with a known expected field.
        val disturbedNow = expected != null && w < 0.08 && magStatus != 0
        if (disturbedNow) {
            if (interferenceSinceNs == 0L) interferenceSinceNs = timestampNs
            if (timestampNs - interferenceSinceNs > 800_000_000L) interference = true
        } else {
            interferenceSinceNs = 0L
            if (w > 0.25) interference = false
        }

        if (w < 0.3) secondsSinceGoodUpdate += dt else secondsSinceGoodUpdate = 0.0
        if (w <= 1e-4) return

        if (expected != null && w >= 0.3) {
            val fExp = expected.f / 1000.0
            val dF = fieldUt - fExp
            val dI = Math.toRadians(dipDeg - expected.inclinationDeg) * fExp
            val anomaly = Math.toDegrees(atan(sqrt(dF * dF + dI * dI) / max(expected.h / 1000.0, 1.0)))
            acceptedAnomalyDeg = if (acceptedAnomalyDeg.isNaN()) anomaly else acceptedAnomalyDeg + (1.0 - exp(-dt / 5.0)) * (anomaly - acceptedAnomalyDeg)
        }

        // ---- circular low-pass of the yaw offset ----
        if (!deltaValid || deltaSource != Mode.FUSED) {
            if (w > 0.3) {
                delta = deltaMeas
                deltaValid = true
                deltaSource = Mode.FUSED
                convergedSamples = 1
                validTimeSec = 0.0
                innovationMs = 0.0
            }
            return
        }
        val e = AngleUtil.wrapPi(deltaMeas - delta)
        val converged = validTimeSec > 1.5
        if (converged && abs(e) > Math.toRadians(12.0)) {
            // Possible transient disturbance: hold unless it persists for 2.5 s of good samples.
            if (outlierSinceNs == 0L) outlierSinceNs = timestampNs
            if (timestampNs - outlierSinceNs < 2_500_000_000L) return
        } else {
            outlierSinceNs = 0L
        }
        val reconverging = outlierSinceNs != 0L
        val tau = when {
            !converged || reconverging -> 0.35
            else -> 2.0
        }
        val gain = (1.0 - exp(-dt / tau)) * w
        delta = AngleUtil.wrapPi(delta + gain * e)
        validTimeSec += dt * w
        convergedSamples++
        innovationMs = innovationMs * 0.98 + 0.02 * e * e
        if (reconverging && abs(AngleUtil.wrapPi(deltaMeas - delta)) < Math.toRadians(3.0)) outlierSinceNs = 0L
    }

    /** Smoothed angular speed of the device (degrees per second) from the game rotation vector. */
    fun angularSpeedDegPerSec(): Double = angularSpeedDegPerSec

    /** The attitude source currently driving [trueNorthMatrix]. */
    fun currentMode(): Mode = when {
        hasGame && deltaValid && deltaSource == Mode.FUSED -> Mode.FUSED
        hasGame && deltaValid && deltaSource == Mode.ROTATION_VECTOR -> Mode.ROTATION_VECTOR
        hasRv && !hasGame -> Mode.ROTATION_VECTOR
        hasGame && !hasMagnetometerSensor && !hasRv -> Mode.GYRO_ONLY
        hasGame -> if (deltaValid) deltaSource else Mode.GYRO_ONLY
        hasAccelMag -> Mode.ACCEL_MAG
        else -> Mode.NONE
    }

    /**
     * Writes the device → true-north ENU rotation into [out]. Returns false if no attitude exists yet.
     */
    fun trueNorthMatrix(out: DoubleArray): Boolean {
        val decl = Math.toRadians(declinationDeg)
        when {
            hasGame -> {
                val d = if (deltaValid) delta else 0.0
                rotateYawVec(rGame, d, out)
                return true
            }
            hasRv -> {
                // Magnetic-north world frame → true north: azimuth += D, i.e. vector rotation by −D.
                rotateYawVec(rRv, -decl, out)
                return true
            }
            hasAccelMag -> {
                rotateYawVec(rAccelMag, -decl, out)
                return true
            }
        }
        return false
    }

    fun diagnostics(): Diagnostics {
        val field = expectedField
        val mode = currentMode()
        // 1-σ heading uncertainty budget.
        var sigma = Double.NaN
        if (mode == Mode.FUSED) {
            val gain = 1.0 - exp(-0.02 / 2.0)
            val noise = Math.toDegrees(sqrt(innovationMs)) * sqrt(gain / (2.0 - gain))
            val wmm = field?.declinationUncertaintyDeg ?: 3.0
            // Unknown local anomaly: the accepted samples' mean departure from the WMM bounds the bias.
            val anomaly = if (acceptedAnomalyDeg.isFinite()) acceptedAnomalyDeg else 2.0
            val status = when (magStatus) {
                3 -> 0.5
                2 -> 1.5
                1 -> 4.0
                else -> 10.0
            }
            // Gyro yaw drift while the magnetometer is rejected (~0.6°/min typical MEMS gyro, capped).
            val drift = (secondsSinceGoodUpdate * 0.01).coerceAtMost(10.0)
            sigma = sqrt(noise * noise + wmm * wmm + anomaly * anomaly + status * status + drift * drift)
        } else if (mode == Mode.ROTATION_VECTOR) {
            val wmm = field?.declinationUncertaintyDeg ?: 3.0
            val rv = if (rvAccuracyDeg.isFinite()) rvAccuracyDeg else when (magStatus) {
                3 -> 2.0
                2 -> 5.0
                1 -> 10.0
                else -> 20.0
            }
            sigma = sqrt(rv * rv + wmm * wmm)
        }
        return Diagnostics(
            mode = mode,
            fieldMeasuredUt = lastFieldUt,
            fieldExpectedUt = field?.let { it.f / 1000.0 } ?: Double.NaN,
            dipMeasuredDeg = lastDipDeg,
            dipExpectedDeg = field?.inclinationDeg ?: Double.NaN,
            declinationDeg = field?.declinationDeg ?: Double.NaN,
            declinationSigmaDeg = field?.declinationUncertaintyDeg ?: Double.NaN,
            magAccuracyStatus = magStatus,
            lastSampleWeight = lastWeight,
            interference = interference,
            headingSigmaDeg = sigma,
            rotationVectorAccuracyDeg = rvAccuracyDeg,
            coverage = coverageBins.count { it } / coverageBins.size.toDouble(),
            converged = deltaValid && deltaSource == Mode.FUSED && validTimeSec > 1.5,
            angularSpeedDegPerSec = angularSpeedDegPerSec
        )
    }

    private fun seedDeltaFromRotationVector() {
        // R_rv · R_gameᵀ must be a pure rotation about the vertical: extract its angle β.
        val m00 = rRv[0] * rGame[0] + rRv[1] * rGame[1] + rRv[2] * rGame[2]
        val m10 = rRv[3] * rGame[0] + rRv[4] * rGame[1] + rRv[5] * rGame[2]
        val beta = atan2(m10, m00)
        // True ENU = Rz(−D) · R_rv = Rz(−D) · Rz(β) · R_game  →  δ = β − D.
        delta = AngleUtil.wrapPi(beta - Math.toRadians(declinationDeg))
        deltaValid = true
        deltaSource = Mode.ROTATION_VECTOR
    }

    private fun updateCoverage(ux: Double, uy: Double, uz: Double) {
        // Quantize each axis to {−1, 0, +1} with a ±0.38 dead zone → 26 non-zero cells.
        fun q(v: Double): Int = if (v > 0.38) 1 else if (v < -0.38) -1 else 0
        val a = q(ux); val b = q(uy); val c = q(uz)
        if (a == 0 && b == 0 && c == 0) return
        var idx = (a + 1) * 9 + (b + 1) * 3 + (c + 1)
        if (idx > 13) idx -= 1 // skip the centre cell (13)
        coverageBins[idx] = true
    }

    companion object {
        /** Android rotation-vector quaternion (x, y, z, w) → row-major rotation matrix (device → world). */
        fun quatToMatrix(x: Double, y: Double, z: Double, w: Double, out: DoubleArray) {
            val n = sqrt(x * x + y * y + z * z + w * w)
            val qx = x / n; val qy = y / n; val qz = z / n; val qw = w / n
            out[0] = 1.0 - 2.0 * (qy * qy + qz * qz)
            out[1] = 2.0 * (qx * qy - qw * qz)
            out[2] = 2.0 * (qx * qz + qw * qy)
            out[3] = 2.0 * (qx * qy + qw * qz)
            out[4] = 1.0 - 2.0 * (qx * qx + qz * qz)
            out[5] = 2.0 * (qy * qz - qw * qx)
            out[6] = 2.0 * (qx * qz - qw * qy)
            out[7] = 2.0 * (qy * qz + qw * qx)
            out[8] = 1.0 - 2.0 * (qx * qx + qy * qy)
        }

        /**
         * out = Rz_vec(δ) · r, where Rz_vec(δ) = [[cos δ, −sin δ, 0], [sin δ, cos δ, 0], [0, 0, 1]] is a
         * counter-clockwise vector rotation about Up; it maps a direction of azimuth φ to φ − δ.
         */
        fun rotateYawVec(r: DoubleArray, delta: Double, out: DoubleArray) {
            val c = cos(delta); val s = sin(delta)
            val r0 = r[0]; val r1 = r[1]; val r2 = r[2]
            val r3 = r[3]; val r4 = r[4]; val r5 = r[5]
            out[0] = c * r0 - s * r3; out[1] = c * r1 - s * r4; out[2] = c * r2 - s * r5
            out[3] = s * r0 + c * r3; out[4] = s * r1 + c * r4; out[5] = s * r2 + c * r5
            out[6] = r[6]; out[7] = r[7]; out[8] = r[8]
        }

        /** Azimuth (deg) of the rear-camera boresight (−z_device) for a device → ENU matrix. */
        fun boresightAzimuthDeg(r: DoubleArray): Double {
            val e = -r[2]; val n = -r[5]
            return AngleUtil.wrap360(Math.toDegrees(atan2(e, n)))
        }

        /** Altitude (deg) of the rear-camera boresight. */
        fun boresightAltitudeDeg(r: DoubleArray): Double = Math.toDegrees(Math.asin((-r[8]).coerceIn(-1.0, 1.0)))
    }
}
