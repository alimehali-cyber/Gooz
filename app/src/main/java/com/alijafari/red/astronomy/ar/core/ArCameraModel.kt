package com.alijafari.red.astronomy.ar.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Static description of the rear camera as reported by Camera2 (all pixel quantities in the
 * coordinate systems defined by the Android camera metadata).
 *
 * @param fx, fy, cx, cy, skew  LENS_INTRINSIC_CALIBRATION, in pixels of the
 *        SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE coordinate system (origin at its top-left),
 *        or an estimate from LENS_INFO_AVAILABLE_FOCAL_LENGTHS × pixel-array / physical size.
 * @param preCorrection, active  the two rectangles [left, top, width, height] in pixel-array
 *        coordinates (SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, SENSOR_INFO_ACTIVE_ARRAY_SIZE).
 * @param distortion  LENS_DISTORTION [κ1, κ2, κ3, κ4, κ5] — only set when the preview stream is NOT
 *        distortion-corrected by the HAL (DISTORTION_CORRECTION_MODE OFF / unsupported).
 * @param sensorOrientationDeg  SENSOR_ORIENTATION (clockwise rotation that makes the image upright
 *        in the device's natural orientation).
 */
data class ArCameraIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val skew: Double,
    val preCorrection: IntArray,
    val active: IntArray,
    val distortion: DoubleArray?,
    val sensorOrientationDeg: Int,
    val source: Source
) {
    enum class Source { FACTORY_CALIBRATION, FOCAL_LENGTH_AND_SENSOR_SIZE, DEFAULT_ASSUMPTION }

    val activeWidth: Int get() = active[2]
    val activeHeight: Int get() = active[3]

    /** Horizontal × vertical field of view (degrees) of the full active array at 1× zoom, sensor frame. */
    fun activeArrayFovDeg(): Pair<Double, Double> {
        val w = active[2].toDouble(); val h = active[3].toDouble()
        val fovX = Math.toDegrees(2.0 * atan(w / 2.0 / fx))
        val fovY = Math.toDegrees(2.0 * atan(h / 2.0 / fy))
        return fovX to fovY
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArCameraIntrinsics) return false
        return fx == other.fx && fy == other.fy && cx == other.cx && cy == other.cy && skew == other.skew &&
            preCorrection.contentEquals(other.preCorrection) && active.contentEquals(other.active) &&
            (distortion?.contentEquals(other.distortion ?: DoubleArray(0)) ?: (other.distortion == null)) &&
            sensorOrientationDeg == other.sensorOrientationDeg && source == other.source
    }

    override fun hashCode(): Int {
        var r = fx.hashCode()
        r = 31 * r + fy.hashCode(); r = 31 * r + cx.hashCode(); r = 31 * r + cy.hashCode()
        r = 31 * r + preCorrection.contentHashCode(); r = 31 * r + active.contentHashCode()
        r = 31 * r + sensorOrientationDeg
        return r
    }

    companion object {
        /** A typical phone main camera (≈ 66° × 51° on a 4:3 sensor) for when no metadata is available. */
        fun defaultAssumption(): ArCameraIntrinsics {
            val w = 4000; val h = 3000
            val f = (w / 2.0) / tan(Math.toRadians(66.0) / 2.0)
            return ArCameraIntrinsics(f, f, w / 2.0, h / 2.0, 0.0, intArrayOf(0, 0, w, h), intArrayOf(0, 0, w, h), null, 90, Source.DEFAULT_ASSUMPTION)
        }
    }
}

/**
 * Per-frame projection from local East-North-Up directions to view (Compose canvas) pixels.
 *
 * Two kinds:
 *  - **Camera**: the exact pipeline of a processed preview frame — pinhole intrinsics in
 *    pre-correction-array pixels, optional lens distortion, shift into active-array pixels,
 *    digital zoom about the active-array centre, then CameraX's `PreviewView.sensorToViewTransform`
 *    (active array → view, which already contains sensor/display rotation and the FILL_CENTER crop).
 *    If CameraX has not produced that matrix yet, an analytic FILL_CENTER mapping of the full active
 *    array is used.
 *  - **Virtual**: an ideal pinhole looking out of the back of the phone, used when the camera is
 *    off; its axes follow the display rotation.
 */
class ArCameraModel private constructor(
    val viewWidth: Float,
    val viewHeight: Float,
    val displayRotationDeg: Int,
    val isVirtual: Boolean,
    private val intr: ArCameraIntrinsics?,
    private val sensorToView: FloatArray?,
    val zoomRatio: Double,
    private val virtualFocalPx: Double
) {
    /** ENU → camera-frame matrix for the current attitude (rows: camera x, camera y (down), boresight). */
    private val m = DoubleArray(9)
    private var hasAttitude = false

    private val offX: Double
    private val offY: Double
    private val activeCx: Double
    private val activeCy: Double

    // Analytic FILL_CENTER fallback parameters.
    private val netRot: Int
    private val fillScale: Double
    private val rotW: Double
    private val rotH: Double

    init {
        if (intr != null) {
            offX = (intr.active[0] - intr.preCorrection[0]).toDouble()
            offY = (intr.active[1] - intr.preCorrection[1]).toDouble()
            activeCx = intr.active[2] / 2.0
            activeCy = intr.active[3] / 2.0
            netRot = ((intr.sensorOrientationDeg - displayRotationDeg) % 360 + 360) % 360
            val w = intr.active[2].toDouble(); val h = intr.active[3].toDouble()
            rotW = if (netRot == 90 || netRot == 270) h else w
            rotH = if (netRot == 90 || netRot == 270) w else h
            fillScale = max(viewWidth / rotW, viewHeight / rotH)
        } else {
            offX = 0.0; offY = 0.0; activeCx = 0.0; activeCy = 0.0
            netRot = 0; fillScale = 1.0; rotW = 1.0; rotH = 1.0
        }
    }

    /**
     * Sets the attitude: [rDeviceToEnu] maps device vectors to ENU (row-major, R · v_dev = v_enu).
     */
    fun setAttitude(rDeviceToEnu: DoubleArray) {
        // camera rows expressed in the device frame
        val c0x: Double; val c0y: Double; val c0z: Double
        val c1x: Double; val c1y: Double; val c1z: Double
        val c2x = 0.0; val c2y = 0.0; val c2z = -1.0
        if (isVirtual) {
            val r = Math.toRadians(displayRotationDeg.toDouble())
            // screen right = (cos r, −sin r, 0), screen down = −(sin r, cos r, 0) in device coordinates
            c0x = cos(r); c0y = -sin(r); c0z = 0.0
            c1x = -sin(r); c1y = -cos(r); c1z = 0.0
        } else {
            val th = Math.toRadians((intr?.sensorOrientationDeg ?: 90).toDouble())
            // sensor x = x cosθ − y sinθ ; sensor y = −x sinθ − y cosθ ; boresight = −z
            c0x = cos(th); c0y = -sin(th); c0z = 0.0
            c1x = -sin(th); c1y = -cos(th); c1z = 0.0
        }
        // M = C · Rᵀ  (C rows in device frame, Rᵀ maps ENU → device)
        val r = rDeviceToEnu
        m[0] = c0x * r[0] + c0y * r[1] + c0z * r[2]
        m[1] = c0x * r[3] + c0y * r[4] + c0z * r[5]
        m[2] = c0x * r[6] + c0y * r[7] + c0z * r[8]
        m[3] = c1x * r[0] + c1y * r[1] + c1z * r[2]
        m[4] = c1x * r[3] + c1y * r[4] + c1z * r[5]
        m[5] = c1x * r[6] + c1y * r[7] + c1z * r[8]
        m[6] = c2x * r[0] + c2y * r[1] + c2z * r[2]
        m[7] = c2x * r[3] + c2y * r[4] + c2z * r[5]
        m[8] = c2x * r[6] + c2y * r[7] + c2z * r[8]
        hasAttitude = true
    }

    /** Camera-frame coordinates of an ENU vector: out = (x, y down, depth). */
    fun toCamera(e: Double, n: Double, u: Double, out: DoubleArray) {
        out[0] = m[0] * e + m[1] * n + m[2] * u
        out[1] = m[3] * e + m[4] * n + m[5] * u
        out[2] = m[6] * e + m[7] * n + m[8] * u
    }

    /**
     * Projects an ENU direction. Returns false when the point is behind the image plane; on success
     * writes (x, y) view pixels into [out] at [offset].
     */
    fun project(e: Double, n: Double, u: Double, out: FloatArray, offset: Int = 0): Boolean {
        if (!hasAttitude) return false
        val cx = m[0] * e + m[1] * n + m[2] * u
        val cy = m[3] * e + m[4] * n + m[5] * u
        val cz = m[6] * e + m[7] * n + m[8] * u
        if (cz <= 1e-6) return false
        return projectCamera(cx / cz, cy / cz, out, offset)
    }

    /** Projects normalized camera coordinates (x/z, y/z) to view pixels. */
    fun projectCamera(xn0: Double, yn0: Double, out: FloatArray, offset: Int = 0): Boolean {
        if (isVirtual) {
            out[offset] = (viewWidth / 2.0 + virtualFocalPx * xn0).toFloat()
            out[offset + 1] = (viewHeight / 2.0 + virtualFocalPx * yn0).toFloat()
            return out[offset].isFinite() && out[offset + 1].isFinite()
        }
        val k = intr ?: return false
        var xn = xn0; var yn = yn0
        val d = k.distortion
        if (d != null && d.size >= 5) {
            val r2 = xn * xn + yn * yn
            val radial = 1.0 + d[0] * r2 + d[1] * r2 * r2 + d[2] * r2 * r2 * r2
            val xd = xn * radial + d[3] * (2.0 * xn * yn) + d[4] * (r2 + 2.0 * xn * xn)
            val yd = yn * radial + d[3] * (r2 + 2.0 * yn * yn) + d[4] * (2.0 * xn * yn)
            xn = xd; yn = yd
        }
        // pre-correction array pixels → active array pixels → digital zoom about the active centre
        var ua = k.fx * xn + k.skew * yn + k.cx - offX
        var va = k.fy * yn + k.cy - offY
        ua = activeCx + (ua - activeCx) * zoomRatio
        va = activeCy + (va - activeCy) * zoomRatio
        val s2v = sensorToView
        if (s2v != null) {
            val w = s2v[6] * ua + s2v[7] * va + s2v[8]
            if (abs(w) < 1e-12) return false
            out[offset] = ((s2v[0] * ua + s2v[1] * va + s2v[2]) / w).toFloat()
            out[offset + 1] = ((s2v[3] * ua + s2v[4] * va + s2v[5]) / w).toFloat()
        } else {
            val aw = k.active[2].toDouble(); val ah = k.active[3].toDouble()
            val ur: Double; val vr: Double
            when (netRot) {
                90 -> { ur = ah - va; vr = ua }
                180 -> { ur = aw - ua; vr = ah - va }
                270 -> { ur = va; vr = aw - ua }
                else -> { ur = ua; vr = va }
            }
            out[offset] = (viewWidth / 2.0 + (ur - rotW / 2.0) * fillScale).toFloat()
            out[offset + 1] = (viewHeight / 2.0 + (vr - rotH / 2.0) * fillScale).toFloat()
        }
        return out[offset].isFinite() && out[offset + 1].isFinite()
    }

    // Local linear part of the projection at the optical axis (view pixels per normalized unit).
    private var jacobianReady = false
    private val jac = DoubleArray(4)

    private fun ensureJacobian(): Boolean {
        if (jacobianReady) return true
        val a = FloatArray(2); val b = FloatArray(2); val c = FloatArray(2)
        val h = 1e-3
        if (!projectCamera(0.0, 0.0, a) || !projectCamera(h, 0.0, b) || !projectCamera(0.0, h, c)) return false
        jac[0] = (b[0] - a[0]) / h; jac[1] = (b[1] - a[1]) / h
        jac[2] = (c[0] - a[0]) / h; jac[3] = (c[1] - a[1]) / h
        jacobianReady = true
        return true
    }

    /**
     * Screen-space direction (view pixels; x right, y down; not normalised) towards an ENU direction as
     * seen from the optical axis. Valid for every pipeline (sensor rotation, display rotation, mirrored
     * transforms) and also for points behind the camera or far outside the view — use it for guidance
     * arrows. (The raw [toCamera] frame is the sensor frame for the live camera, not the screen.)
     */
    fun screenDirection(e: Double, n: Double, u: Double, out: DoubleArray): Boolean {
        if (!hasAttitude || !ensureJacobian()) return false
        val cx = m[0] * e + m[1] * n + m[2] * u
        val cy = m[3] * e + m[4] * n + m[5] * u
        out[0] = cx * jac[0] + cy * jac[2]
        out[1] = cx * jac[1] + cy * jac[3]
        return true
    }

    /** Boresight (view centre of the optical axis) as an ENU unit vector. */
    fun boresightEnu(out: DoubleArray = DoubleArray(3)): DoubleArray {
        out[0] = m[6]; out[1] = m[7]; out[2] = m[8]
        return out
    }

    /** ENU unit vector of the camera frame's y axis (sensor rows for the live camera; screen down when virtual). */
    fun imageDownEnu(out: DoubleArray = DoubleArray(3)): DoubleArray {
        out[0] = m[3]; out[1] = m[4]; out[2] = m[5]
        return out
    }

    /** ENU unit vector of the camera frame's x axis (sensor columns for the live camera; screen right when virtual). */
    fun imageRightEnu(out: DoubleArray = DoubleArray(3)): DoubleArray {
        out[0] = m[0]; out[1] = m[1]; out[2] = m[2]
        return out
    }

    /**
     * Effective focal length in view pixels at the image centre (pixels per radian for small angles),
     * measured numerically through the full pipeline so it is exact for any transform.
     */
    fun focalLengthViewPx(): Double {
        if (isVirtual) return virtualFocalPx
        val a = FloatArray(2); val b = FloatArray(2); val c = FloatArray(2)
        val h = 0.01
        if (!projectCamera(0.0, 0.0, a) || !projectCamera(h, 0.0, b) || !projectCamera(0.0, h, c)) return 1000.0
        val dx = sqrt(((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1])).toDouble()) / h
        val dy = sqrt(((c[0] - a[0]) * (c[0] - a[0]) + (c[1] - a[1]) * (c[1] - a[1])).toDouble()) / h
        return (dx + dy) / 2.0
    }

    /** View pixels per degree at the image centre. */
    fun pixelsPerDegree(): Double = focalLengthViewPx() * Math.PI / 180.0

    /** Approximate diagonal half field of view (radians) of the view, for culling. */
    fun halfDiagonalFovRad(): Double {
        val f = focalLengthViewPx()
        val halfDiag = sqrt((viewWidth * viewWidth + viewHeight * viewHeight).toDouble()) / 2.0
        return atan(halfDiag / f)
    }

    /** Horizontal and vertical fields of view (degrees) of the view. */
    fun viewFovDeg(): Pair<Double, Double> {
        val f = focalLengthViewPx()
        return Math.toDegrees(2.0 * atan(viewWidth / 2.0 / f)) to Math.toDegrees(2.0 * atan(viewHeight / 2.0 / f))
    }

    /** Camera → ENU matrix (transpose of the internal ENU → camera matrix), row-major. */
    fun cameraToEnuMatrix(out: DoubleArray = DoubleArray(9)): DoubleArray = M3.transpose(m, out)

    companion object {
        /** Model of the live camera preview. [sensorToViewValues] = android.graphics.Matrix values (9 floats) or null. */
        fun camera(
            viewWidth: Float,
            viewHeight: Float,
            displayRotationDeg: Int,
            intrinsics: ArCameraIntrinsics,
            sensorToViewValues: FloatArray?,
            zoomRatio: Double
        ): ArCameraModel = ArCameraModel(
            viewWidth, viewHeight, displayRotationDeg, false, intrinsics, sensorToViewValues?.copyOf(),
            zoomRatio.coerceIn(0.1, 100.0), 0.0
        )

        /**
         * Ideal pinhole looking out of the back of the phone. [verticalFovDeg] is the field of view
         * along the view's height at zoom 1.
         */
        fun virtual(
            viewWidth: Float,
            viewHeight: Float,
            displayRotationDeg: Int,
            verticalFovDeg: Double,
            zoom: Double
        ): ArCameraModel {
            val f = (viewHeight / 2.0) / tan(Math.toRadians(verticalFovDeg.coerceIn(5.0, 150.0)) / 2.0) * zoom
            return ArCameraModel(viewWidth, viewHeight, displayRotationDeg, true, null, null, zoom, f)
        }

        /** Vertical field of view (degrees) that the physical camera preview shows in a view of this size. */
        fun cameraVerticalFovDeg(viewWidth: Float, viewHeight: Float, displayRotationDeg: Int, intrinsics: ArCameraIntrinsics): Double {
            val model = camera(viewWidth, viewHeight, displayRotationDeg, intrinsics, null, 1.0)
            return model.viewFovDeg().second
        }
    }
}
