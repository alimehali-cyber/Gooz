package com.alijafari.red.astronomy.astro_engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.alijafari.red.astronomy.ar.core.ArRefraction
import com.alijafari.red.astronomy.ar.core.ArTimeScales
import com.alijafari.red.astronomy.ar.core.HeadingFusion
import com.alijafari.red.astronomy.ar.core.Wmm2025
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class SkyOrientation(
    val azimuth: Float = 0f,   // True north azimuth, 0-360°, clockwise
    val pitch: Float = 0f,     // Elevation, positive = up, degrees (-90° to +90°)
    val roll: Float = 0f,      // Roll around optical axis, degrees
    val rotationMatrix: FloatArray = FloatArray(9)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SkyOrientation
        return azimuth == other.azimuth &&
                pitch == other.pitch &&
                roll == other.roll &&
                rotationMatrix.contentEquals(other.rotationMatrix)
    }

    override fun hashCode(): Int {
        var result = azimuth.hashCode()
        result = 31 * result + pitch.hashCode()
        result = 31 * result + roll.hashCode()
        result = 31 * result + rotationMatrix.contentHashCode()
        return result
    }
}

enum class CalibrationState {
    EXCELLENT, GOOD, POOR, NEEDS_CALIBRATION, UNCALIBRATED
}

/** Local air pressure and temperature used to scale astronomical refraction. */
data class ArAtmosphereReading(
    val pressureHpa: Double,
    val temperatureC: Double,
    val pressureMeasured: Boolean,
    val temperatureMeasured: Boolean
)

/** Live sensor status for the calibration dialog and the telemetry panel. */
data class ArSensorStatus(
    val fusion: HeadingFusion.Diagnostics = HeadingFusion.Diagnostics(),
    val hasGameRotationVector: Boolean = false,
    val hasRotationVector: Boolean = false,
    val hasMagnetometer: Boolean = false,
    val hasGyroscope: Boolean = false,
    val hasAccelerometer: Boolean = false,
    val hasBarometer: Boolean = false,
    val hasThermometer: Boolean = false,
    /** Measured event rates (Hz) of the attitude and magnetometer streams. */
    val attitudeRateHz: Double = 0.0,
    val magnetometerRateHz: Double = 0.0,
    val atmosphere: ArAtmosphereReading? = null,
    /** Raw calibrated magnetometer vector in the device frame (µT), for the live field display. */
    val magneticVectorUt: FloatArray = FloatArray(3),
    /** Star-alignment yaw offset currently applied (degrees). */
    val alignmentOffsetDeg: Double = 0.0
)

/**
 * Sensor fusion for the AR sky (see [HeadingFusion] for the method):
 *  - attitude from GAME_ROTATION_VECTOR (gyro + accelerometer) when present, otherwise
 *    ROTATION_VECTOR, otherwise accelerometer + magnetometer;
 *  - true-north yaw from the calibrated magnetometer, gated against the World Magnetic Model 2025
 *    (total intensity and dip) and corrected by the WMM2025 declination;
 *  - optional user star alignment (yaw only) from [ARCalibrationManager];
 *  - barometer (and thermometer when fitted) for the refraction model.
 *
 * Location changes only update the geomagnetic expectation; the sensors are NOT restarted, so the
 * fusion state, the interference detector and the calibration history survive GPS fixes.
 */
class OrientationProvider(
    private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gameRotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val magnetometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val temperatureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)

    private val fusion = HeadingFusion().apply { setHasMagnetometer(magnetometerSensor != null) }

    // Raw accel + mag buffers for the no-gyro fallback
    private val gravityValues = FloatArray(3)
    private val geomagneticValues = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private val accelMagMatrix = FloatArray(9)

    // Output smoothing (quaternion SLERP with motion-adaptive gain)
    private var smoothW = 1.0
    private var smoothX = 0.0
    private var smoothY = 0.0
    private var smoothZ = 0.0
    private var smoothInit = false

    private val trueMatrix = DoubleArray(9)
    private val alignedMatrix = DoubleArray(9)
    private val yawMatrix = FloatArray(9)

    private var heightM = 0.0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN

    // Rates
    private var attitudeEvents = 0
    private var magEvents = 0
    private var rateWindowStartMs = 0L
    private var attitudeRate = 0.0
    private var magRate = 0.0
    private var lastStatusPublishMs = 0L

    // Barometer (smoothed) / thermometer
    private var pressureHpa = Double.NaN
    private var temperatureC = Double.NaN

    /** Magnetometer accuracy status as reported by Android. */
    var magneticAccuracy = -2 // unknown until the first event or accuracy callback
        private set

    private val _orientation = MutableStateFlow(SkyOrientation(0f, 45f, 0f))
    val orientation: StateFlow<SkyOrientation> = _orientation.asStateFlow()

    private val _calibrationState = MutableStateFlow(CalibrationState.UNCALIBRATED)
    val calibrationState: StateFlow<CalibrationState> = _calibrationState.asStateFlow()

    private val _magneticInterference = MutableStateFlow(false)
    val magneticInterference: StateFlow<Boolean> = _magneticInterference.asStateFlow()

    private val _status = MutableStateFlow(
        ArSensorStatus(
            hasGameRotationVector = gameRotationSensor != null,
            hasRotationVector = rotationVectorSensor != null,
            hasMagnetometer = magnetometerSensor != null,
            hasGyroscope = gyroSensor != null,
            hasAccelerometer = accelSensor != null,
            hasBarometer = pressureSensor != null,
            hasThermometer = temperatureSensor != null
        )
    )
    val status: StateFlow<ArSensorStatus> = _status.asStateFlow()

    private val _atmosphere = MutableStateFlow(standardAtmosphere())
    val atmosphere: StateFlow<ArAtmosphereReading> = _atmosphere.asStateFlow()

    private var isStarted = false

    fun start() {
        if (isStarted || sensorManager == null) return
        isStarted = true
        val rate = SensorManager.SENSOR_DELAY_GAME
        gameRotationSensor?.let { sensorManager.registerListener(this, it, rate) }
        rotationVectorSensor?.let { sensorManager.registerListener(this, it, rate) }
        magnetometerSensor?.let { sensorManager.registerListener(this, it, rate) }
        if (gameRotationSensor == null && rotationVectorSensor == null) {
            accelSensor?.let { sensorManager.registerListener(this, it, rate) }
        }
        pressureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        temperatureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        rateWindowStartMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        sensorManager?.unregisterListener(this)
        _magneticInterference.value = false
    }

    /**
     * Updates the observer position used for the geomagnetic model (declination, expected field
     * strength and dip). Cheap; never restarts the sensors. [altitudeMeters] is above mean sea level.
     */
    fun updateLocation(latitude: Double, longitude: Double, altitudeMeters: Double = 0.0) {
        heightM = altitudeMeters
        if (!lastLat.isNaN() && abs(latitude - lastLat) < 0.01 && abs(longitude - lastLon) < 0.01) {
            _atmosphere.value = currentAtmosphere()
            return
        }
        lastLat = latitude
        lastLon = longitude
        try {
            // Always the real current date: the compass lives in the present even in time-machine mode.
            val year = ArTimeScales.decimalYearFromEpochMillis(System.currentTimeMillis())
            fusion.setExpectedField(Wmm2025.calculate(latitude, longitude, altitudeMeters / 1000.0, year))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _atmosphere.value = currentAtmosphere()
        publishStatus(force = true)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val q = quaternionOf(event.values)
                fusion.onGameRotation(q[0], q[1], q[2], q[3], event.timestamp)
                attitudeEvents++
                publishOrientation()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = quaternionOf(event.values)
                val acc = if (event.values.size >= 5 && event.values[4] > 0f) event.values[4].toDouble() else Double.NaN
                fusion.onRotationVector(q[0], q[1], q[2], q[3], acc)
                if (gameRotationSensor == null) {
                    attitudeEvents++
                    publishOrientation()
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravityValues, 0, 3)
                hasGravity = true
                processAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagneticValues, 0, 3)
                hasGeomagnetic = true
                magEvents++
                if (event.accuracy != magneticAccuracy) {
                    magneticAccuracy = event.accuracy
                    fusion.onMagAccuracy(event.accuracy)
                }
                fusion.onMagnetometer(
                    event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
                    event.timestamp
                )
                if (gameRotationSensor == null && rotationVectorSensor == null) processAccelMag()
                publishStatus(force = false)
            }
            Sensor.TYPE_PRESSURE -> {
                val p = event.values[0].toDouble()
                if (p in 300.0..1100.0) {
                    pressureHpa = if (pressureHpa.isNaN()) p else pressureHpa + 0.1 * (p - pressureHpa)
                    _atmosphere.value = currentAtmosphere()
                }
            }
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                val t = event.values[0].toDouble()
                if (t in -60.0..60.0) {
                    temperatureC = if (temperatureC.isNaN()) t else temperatureC + 0.1 * (t - temperatureC)
                    _atmosphere.value = currentAtmosphere()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magneticAccuracy = accuracy
            fusion.onMagAccuracy(accuracy)
            publishStatus(force = true)
        }
    }

    private fun processAccelMag() {
        if (!hasGravity || !hasGeomagnetic) return
        if (!SensorManager.getRotationMatrix(accelMagMatrix, null, gravityValues, geomagneticValues)) return
        fusion.onAccelMagMatrix(accelMagMatrix)
        if (gameRotationSensor == null && rotationVectorSensor == null) {
            attitudeEvents++
            publishOrientation()
        }
    }

    private fun publishOrientation() {
        if (!fusion.trueNorthMatrix(trueMatrix)) return

        // Optional star alignment: pure rotation about the local vertical (azimuth += offset).
        val calibOffsets = ARCalibrationManager.getOffsets()
        if (calibOffsets.isCalibrated && calibOffsets.yawOffsetDeg != 0f) {
            ARCalibrationManager.createYawOnlyRotationMatrix(calibOffsets.yawOffsetDeg, yawMatrix)
            for (i in 0..2) for (j in 0..2) {
                var s = 0.0
                for (k in 0..2) s += yawMatrix[i * 3 + k] * trueMatrix[k * 3 + j]
                alignedMatrix[i * 3 + j] = s
            }
        } else {
            System.arraycopy(trueMatrix, 0, alignedMatrix, 0, 9)
        }

        val finalMatrix = smooth(alignedMatrix)

        // Boresight of the rear camera (−Z_device) in ENU.
        val px = -finalMatrix[2]
        val py = -finalMatrix[5]
        val pz = -finalMatrix[8]
        val azimuthDeg = ((Math.toDegrees(atan2(px.toDouble(), py.toDouble())) + 360.0) % 360.0).toFloat()
        val pitchDeg = Math.toDegrees(asin(pz.toDouble().coerceIn(-1.0, 1.0))).toFloat()

        val rx = finalMatrix[0]
        val ry = finalMatrix[3]
        val rz = finalMatrix[6]
        val horizLen = sqrt((px * px + py * py).toDouble()).toFloat()
        val rollDeg = if (horizLen > 1e-4f) {
            val rSkyX = py / horizLen
            val rSkyY = -px / horizLen
            val uSkyX = -px * pz / horizLen
            val uSkyY = -py * pz / horizLen
            val uSkyZ = horizLen
            val dotRight = rx * rSkyX + ry * rSkyY
            val dotUp = rx * uSkyX + ry * uSkyY + rz * uSkyZ
            Math.toDegrees(atan2(dotUp.toDouble(), dotRight.toDouble())).toFloat()
        } else {
            Math.toDegrees(atan2(rz.toDouble(), finalMatrix[7].toDouble())).toFloat()
        }

        _orientation.value = SkyOrientation(
            azimuth = azimuthDeg,
            pitch = pitchDeg,
            roll = rollDeg,
            rotationMatrix = finalMatrix
        )
        if (magnetometerSensor == null) publishStatus(force = false)
    }

    /** Motion-adaptive SLERP smoothing of the output attitude; light for gyro-based attitude. */
    private fun smooth(m: DoubleArray): FloatArray {
        val q = matrixToQuaternion(m)
        if (!smoothInit) {
            smoothW = q[0]; smoothX = q[1]; smoothY = q[2]; smoothZ = q[3]
            smoothInit = true
        } else {
            var tw = q[0]; var tx = q[1]; var ty = q[2]; var tz = q[3]
            var dot = smoothW * tw + smoothX * tx + smoothY * ty + smoothZ * tz
            if (dot < 0.0) { tw = -tw; tx = -tx; ty = -ty; tz = -tz; dot = -dot }
            val clamped = dot.coerceIn(0.0, 1.0)
            val angleDeg = Math.toDegrees(2.0 * acos(clamped))
            val mode = fusion.currentMode()
            val (aMin, aMax) = when (mode) {
                HeadingFusion.Mode.FUSED, HeadingFusion.Mode.GYRO_ONLY -> 0.55 to 1.0
                HeadingFusion.Mode.ROTATION_VECTOR -> 0.18 to 0.85
                else -> 0.06 to 0.5
            }
            val speed = fusion.angularSpeedDegPerSec()
            val motion = max(speed / 40.0, angleDeg / 3.0).coerceIn(0.0, 1.0)
            val alpha = aMin + (aMax - aMin) * motion
            if (clamped < 0.9995) {
                val theta = acos(clamped)
                val st = sin(theta)
                val w1 = sin((1.0 - alpha) * theta) / st
                val w2 = sin(alpha * theta) / st
                smoothW = w1 * smoothW + w2 * tw; smoothX = w1 * smoothX + w2 * tx
                smoothY = w1 * smoothY + w2 * ty; smoothZ = w1 * smoothZ + w2 * tz
            } else {
                smoothW += alpha * (tw - smoothW); smoothX += alpha * (tx - smoothX)
                smoothY += alpha * (ty - smoothY); smoothZ += alpha * (tz - smoothZ)
            }
            val n = sqrt(smoothW * smoothW + smoothX * smoothX + smoothY * smoothY + smoothZ * smoothZ)
            if (n > 1e-9) { smoothW /= n; smoothX /= n; smoothY /= n; smoothZ /= n }
        }
        val w = smoothW; val x = smoothX; val y = smoothY; val z = smoothZ
        return floatArrayOf(
            (1.0 - 2.0 * (y * y + z * z)).toFloat(), (2.0 * (x * y - w * z)).toFloat(), (2.0 * (x * z + w * y)).toFloat(),
            (2.0 * (x * y + w * z)).toFloat(), (1.0 - 2.0 * (x * x + z * z)).toFloat(), (2.0 * (y * z - w * x)).toFloat(),
            (2.0 * (x * z - w * y)).toFloat(), (2.0 * (y * z + w * x)).toFloat(), (1.0 - 2.0 * (x * x + y * y)).toFloat()
        )
    }

    private fun publishStatus(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatusPublishMs < 200L) return
        lastStatusPublishMs = now
        val window = now - rateWindowStartMs
        if (window >= 1000L) {
            attitudeRate = attitudeEvents * 1000.0 / window
            magRate = magEvents * 1000.0 / window
            attitudeEvents = 0
            magEvents = 0
            rateWindowStartMs = now
        }
        val d = fusion.diagnostics()
        _magneticInterference.value = d.interference
        _calibrationState.value = deriveCalibrationState(d)
        _status.value = _status.value.copy(
            fusion = d,
            attitudeRateHz = attitudeRate,
            magnetometerRateHz = magRate,
            atmosphere = currentAtmosphere(),
            magneticVectorUt = geomagneticValues.copyOf(),
            alignmentOffsetDeg = ARCalibrationManager.getOffsets().let { if (it.isCalibrated) it.yawOffsetDeg.toDouble() else 0.0 }
        )
    }

    /**
     * Calibration quality from Android's magnetometer accuracy flag AND the physics: the measured
     * field must have the strength and dip the WMM predicts here; a "HIGH" flag with a 30 % wrong field
     * is not a calibrated compass.
     */
    private fun deriveCalibrationState(d: HeadingFusion.Diagnostics): CalibrationState {
        if (magnetometerSensor == null) return CalibrationState.UNCALIBRATED
        val status = d.magAccuracyStatus
        if (status < 0 && status != SensorManager.SENSOR_STATUS_UNRELIABLE) {
            if (d.fieldMeasuredUt.isNaN()) return CalibrationState.UNCALIBRATED
        }
        val haveModel = d.fieldExpectedUt.isFinite() && d.fieldMeasuredUt.isFinite() && d.dipMeasuredDeg.isFinite()
        val relF = if (haveModel) abs(d.fieldMeasuredUt - d.fieldExpectedUt) / d.fieldExpectedUt else Double.NaN
        val dDip = if (haveModel) abs(d.dipMeasuredDeg - d.dipExpectedDeg) else Double.NaN
        return when {
            status == SensorManager.SENSOR_STATUS_UNRELIABLE -> CalibrationState.NEEDS_CALIBRATION
            haveModel && (relF > 0.25 || dDip > 15.0) -> CalibrationState.NEEDS_CALIBRATION
            status == SensorManager.SENSOR_STATUS_ACCURACY_HIGH && (!haveModel || (relF < 0.06 && dDip < 3.0)) -> CalibrationState.EXCELLENT
            status >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM && (!haveModel || (relF < 0.12 && dDip < 6.0)) -> CalibrationState.GOOD
            status == SensorManager.SENSOR_STATUS_ACCURACY_LOW || status >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CalibrationState.POOR
            else -> CalibrationState.UNCALIBRATED
        }
    }

    private fun currentAtmosphere(): ArAtmosphereReading {
        val pMeasured = !pressureHpa.isNaN()
        val tMeasured = !temperatureC.isNaN()
        return ArAtmosphereReading(
            pressureHpa = if (pMeasured) pressureHpa else ArRefraction.standardPressureHpa(heightM),
            temperatureC = if (tMeasured) temperatureC else ArRefraction.standardTemperatureC(heightM),
            pressureMeasured = pMeasured,
            temperatureMeasured = tMeasured
        )
    }

    private fun standardAtmosphere() = ArAtmosphereReading(
        ArRefraction.standardPressureHpa(0.0), ArRefraction.standardTemperatureC(0.0), false, false
    )

    private fun quaternionOf(values: FloatArray): DoubleArray {
        val qx = values[0].toDouble()
        val qy = values[1].toDouble()
        val qz = values[2].toDouble()
        val qw = if (values.size >= 4) {
            values[3].toDouble()
        } else {
            val s = qx * qx + qy * qy + qz * qz
            if (s <= 1.0) sqrt(1.0 - s) else 0.0
        }
        return doubleArrayOf(qx, qy, qz, qw)
    }

    private fun matrixToQuaternion(m: DoubleArray): DoubleArray {
        val trace = m[0] + m[4] + m[8]
        val w: Double; val x: Double; val y: Double; val z: Double
        if (trace > 0.0) {
            val s = 0.5 / sqrt(trace + 1.0)
            w = 0.25 / s; x = (m[7] - m[5]) * s; y = (m[2] - m[6]) * s; z = (m[3] - m[1]) * s
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = 2.0 * sqrt(1.0 + m[0] - m[4] - m[8])
            w = (m[7] - m[5]) / s; x = 0.25 * s; y = (m[1] + m[3]) / s; z = (m[2] + m[6]) / s
        } else if (m[4] > m[8]) {
            val s = 2.0 * sqrt(1.0 + m[4] - m[0] - m[8])
            w = (m[2] - m[6]) / s; x = (m[1] + m[3]) / s; y = 0.25 * s; z = (m[5] + m[7]) / s
        } else {
            val s = 2.0 * sqrt(1.0 + m[8] - m[0] - m[4])
            w = (m[3] - m[1]) / s; x = (m[2] + m[6]) / s; y = (m[5] + m[7]) / s; z = 0.25 * s
        }
        return doubleArrayOf(w, x, y, z)
    }
}
