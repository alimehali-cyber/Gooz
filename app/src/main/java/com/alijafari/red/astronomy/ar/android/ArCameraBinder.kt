package com.alijafari.red.astronomy.ar.android

import android.annotation.SuppressLint
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.alijafari.red.astronomy.ar.core.ArCameraIntrinsics
import kotlin.math.atan
import kotlin.math.max

/**
 * Binds the rear camera for the AR sky and reads its true optics.
 *
 *  - Electronic and optical image stabilization are switched off: both move the image relative to the
 *    phone body, which the sensor-driven overlay cannot follow.
 *  - The HAL's lens-distortion correction is requested (FAST) when supported, so the preview obeys the
 *    pinhole model of LENS_INTRINSIC_CALIBRATION; otherwise LENS_DISTORTION is applied in projection.
 *  - The intrinsics come from the camera actually bound (Camera2CameraInfo), not from the first camera
 *    id in the CameraManager list.
 */
// Camera2Interop / Camera2CameraInfo are marked @ExperimentalCamera2Interop (an androidx lint-level
// opt-in, not a Kotlin compiler requirement); the usage is deliberate and stable since CameraX 1.0.
@SuppressLint("UnsafeOptInUsageError", "UnsafeExperimentalUsageError")
object ArCameraBinder {

    private const val TAG = "ArCameraBinder"

    class Bound(val camera: Camera, val intrinsics: ArCameraIntrinsics)

    fun bind(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ): Bound {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val info: CameraInfo? = try {
            selector.filter(cameraProvider.availableCameraInfos).firstOrNull()
        } catch (e: Exception) {
            null
        }
        val chars = info?.let { readCharacteristics(it) }
        val correctionSupported = chars?.distortionCorrectionSupported == true

        val builder = Preview.Builder()
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        if (chars?.oisAvailable == true) {
            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        if (correctionSupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extender.setCaptureRequestOption(
                CaptureRequest.DISTORTION_CORRECTION_MODE,
                CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
            )
        }
        val preview = builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview)
        val intrinsics = (readCharacteristics(camera.cameraInfo) ?: chars)?.intrinsics
            ?: ArCameraIntrinsics.defaultAssumption()
        return Bound(camera, intrinsics)
    }

    private class Chars(
        val intrinsics: ArCameraIntrinsics,
        val distortionCorrectionSupported: Boolean,
        val oisAvailable: Boolean
    )

    private fun readCharacteristics(info: CameraInfo): Chars? {
        return try {
            val c2 = Camera2CameraInfo.from(info)
            val active: Rect = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
            val pre: Rect = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            } else null) ?: active
            val orientation = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val correctionModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                c2.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            } else null
            val correction = correctionModes?.any {
                it == CameraMetadata.DISTORTION_CORRECTION_MODE_FAST || it == CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            } == true
            val ois = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON } == true

            val preArray = intArrayOf(pre.left, pre.top, pre.width(), pre.height())
            val activeArray = intArrayOf(active.left, active.top, active.width(), active.height())

            // 1. Factory calibration, in pre-correction active-array pixels.
            val cal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                c2.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            } else null
            val distortion = if (!correction && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                c2.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)?.takeIf { it.size >= 5 }
                    ?.map { it.toDouble() }?.toDoubleArray()
            } else null
            val calibrated = if (cal != null && cal.size >= 5 && cal[0] > 0f && cal[1] > 0f) {
                ArCameraIntrinsics(
                    fx = cal[0].toDouble(), fy = cal[1].toDouble(), cx = cal[2].toDouble(), cy = cal[3].toDouble(),
                    skew = cal[4].toDouble(), preCorrection = preArray, active = activeArray,
                    distortion = distortion, sensorOrientationDeg = orientation,
                    source = ArCameraIntrinsics.Source.FACTORY_CALIBRATION
                ).takeIf { plausible(it) }
            } else null

            // 2. Focal length × pixel pitch (physical size covers the full pixel array).
            val estimated = calibrated ?: run {
                val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val phys = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pixels = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                if (focal != null && focal > 0f && phys != null && phys.width > 0f && phys.height > 0f && pixels != null) {
                    val fx = focal * pixels.width / phys.width
                    val fy = focal * pixels.height / phys.height
                    ArCameraIntrinsics(
                        fx = fx.toDouble(), fy = fy.toDouble(),
                        cx = pre.width() / 2.0, cy = pre.height() / 2.0, skew = 0.0,
                        preCorrection = preArray, active = activeArray, distortion = null,
                        sensorOrientationDeg = orientation,
                        source = ArCameraIntrinsics.Source.FOCAL_LENGTH_AND_SENSOR_SIZE
                    ).takeIf { plausible(it) }
                } else null
            }
            estimated?.let { Chars(it, correction, ois) }
        } catch (e: Exception) {
            Log.w(TAG, "Camera characteristics unavailable", e)
            null
        }
    }

    /** Rejects metadata that would imply an absurd field of view (≈ 15°–130° across the active array). */
    private fun plausible(i: ArCameraIntrinsics): Boolean {
        val w = max(i.active[2], 1).toDouble()
        val fov = Math.toDegrees(2.0 * atan(w / 2.0 / i.fx))
        return fov in 15.0..130.0 && i.fy > 0.0 && i.fx / i.fy in 0.8..1.25
    }
}
