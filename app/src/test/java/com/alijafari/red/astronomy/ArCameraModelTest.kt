package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.ArCameraIntrinsics
import com.alijafari.red.astronomy.ar.core.ArCameraModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * The AR projection: an ideal pinhole for the virtual sky, and the exact CameraX/Camera2 preview
 * pipeline (intrinsics in pre-correction pixels → distortion → active array → digital zoom →
 * PreviewView sensor-to-view transform, or its analytic FILL_CENTER equivalent) for the camera.
 */
class ArCameraModelTest {

    private val w = 1080f
    private val h = 2340f

    /** Device → ENU for the rear camera pointing at azimuth/altitude, phone upright (no roll). */
    private fun attitude(azDeg: Double, altDeg: Double): DoubleArray {
        val az = Math.toRadians(azDeg); val alt = Math.toRadians(altDeg)
        val f = doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
        val r = doubleArrayOf(cos(az), -sin(az), 0.0)
        val u = doubleArrayOf(-sin(alt) * sin(az), -sin(alt) * cos(az), cos(alt))
        return doubleArrayOf(r[0], u[0], -f[0], r[1], u[1], -f[1], r[2], u[2], -f[2])
    }

    private fun enu(azDeg: Double, altDeg: Double): DoubleArray {
        val az = Math.toRadians(azDeg); val alt = Math.toRadians(altDeg)
        return doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
    }

    private fun project(m: ArCameraModel, azDeg: Double, altDeg: Double): FloatArray? {
        val e = enu(azDeg, altDeg)
        val out = FloatArray(2)
        return if (m.project(e[0], e[1], e[2], out)) out else null
    }

    @Test
    fun `virtual camera is an exact pinhole with the requested field of view`() {
        val m = ArCameraModel.virtual(w, h, 0, 60.0, 1.0)
        m.setAttitude(attitude(100.0, 0.0))
        assertEquals(60.0, m.viewFovDeg().second, 1e-9)
        val c = project(m, 100.0, 0.0)!!
        assertEquals(w / 2, c[0], 1e-3f)
        assertEquals(h / 2, c[1], 1e-3f)
        val f = (h / 2.0) / tan(Math.toRadians(30.0))
        assertEquals(f, m.focalLengthViewPx(), 1e-9)
        // 10° to the right along the horizon: x = cx + f·tan 10°.
        val r = project(m, 110.0, 0.0)!!
        assertEquals(w / 2 + f * tan(Math.toRadians(10.0)), r[0].toDouble(), 1e-2)
        assertEquals(h / 2.0, r[1].toDouble(), 1e-2)
        // 5° up: above the centre.
        val u = project(m, 100.0, 5.0)!!
        assertEquals(h / 2 - f * tan(Math.toRadians(5.0)), u[1].toDouble(), 1e-2)
        // Behind the camera: not projectable.
        assertTrue(project(m, 280.0, 0.0) == null)
    }

    @Test
    fun `zoom scales the focal length exactly`() {
        val a = ArCameraModel.virtual(w, h, 0, 60.0, 1.0)
        val b = ArCameraModel.virtual(w, h, 0, 60.0, 2.5)
        assertEquals(2.5, b.focalLengthViewPx() / a.focalLengthViewPx(), 1e-12)
        val cam1 = ArCameraModel.camera(w, h, 0, ArCameraIntrinsics.defaultAssumption(), null, 1.0)
        val cam2 = ArCameraModel.camera(w, h, 0, ArCameraIntrinsics.defaultAssumption(), null, 2.0)
        // Measured numerically through the float pipeline: exact to float precision.
        assertEquals(2.0, cam2.focalLengthViewPx() / cam1.focalLengthViewPx(), 1e-4)
    }

    @Test
    fun `default intrinsics describe a typical 66 degree main camera`() {
        val (fx, fy) = ArCameraIntrinsics.defaultAssumption().activeArrayFovDeg()
        assertEquals(66.0, fx, 1e-9)
        assertEquals(Math.toDegrees(2 * atan(1500.0 / (2000.0 / tan(Math.toRadians(33.0))))), fy, 1e-9)
    }

    @Test
    fun `live camera pipeline handles the sensor orientation in portrait`() {
        val m = ArCameraModel.camera(w, h, 0, ArCameraIntrinsics.defaultAssumption(), null, 1.0)
        m.setAttitude(attitude(40.0, 30.0))
        val c = project(m, 40.0, 30.0)!!
        assertEquals(w / 2, c[0], 0.05f)
        assertEquals(h / 2, c[1], 0.05f)
        val right = project(m, 45.0, 30.0)!!
        assertTrue("east of the boresight must appear to the right", right[0] > c[0] + 50)
        val up = project(m, 40.0, 34.0)!!
        assertTrue("higher altitude must appear higher", up[1] < c[1] - 50)
        assertEquals(c[0], up[0], 1.0f)
        // Portrait FILL_CENTER of a 4:3 landscape sensor: the view height spans the sensor width.
        val fovV = m.viewFovDeg().second
        assertTrue("vertical FOV $fovV", fovV in 50.0..70.0)
    }

    @Test
    fun `sensor-to-view matrix path equals the analytic FILL_CENTER path`() {
        val k = ArCameraIntrinsics.defaultAssumption()
        val aw = k.active[2].toFloat(); val ah = k.active[3].toFloat()
        // Sensor orientation 90°, display rotation 0: rotated sensor is ah × aw, scaled to fill the view.
        val s = maxOf(w / ah, h / aw)
        val s2v = floatArrayOf(0f, -s, w / 2 + s * ah / 2, s, 0f, h / 2 - s * aw / 2, 0f, 0f, 1f)
        val analytic = ArCameraModel.camera(w, h, 0, k, null, 1.3)
        val matrix = ArCameraModel.camera(w, h, 0, k, s2v, 1.3)
        val att = attitude(210.0, 12.0)
        analytic.setAttitude(att); matrix.setAttitude(att)
        for ((az, alt) in listOf(210.0 to 12.0, 218.0 to 15.0, 203.0 to 4.0, 212.0 to 25.0)) {
            val a = project(analytic, az, alt)!!
            val b = project(matrix, az, alt)!!
            assertEquals("x at $az/$alt", a[0], b[0], 0.05f)
            assertEquals("y at $az/$alt", a[1], b[1], 0.05f)
        }
    }

    @Test
    fun `lens distortion follows the Brown-Conrady model`() {
        val base = ArCameraIntrinsics.defaultAssumption()
        val k1 = -0.05
        val distorted = base.copy(distortion = doubleArrayOf(k1, 0.0, 0.0, 0.0, 0.0))
        val ideal = ArCameraModel.camera(w, h, 0, base, null, 1.0)
        val real = ArCameraModel.camera(w, h, 0, distorted, null, 1.0)
        val p0 = FloatArray(2); val p1 = FloatArray(2)
        val xn = 0.3
        assertTrue(ideal.projectCamera(xn, 0.0, p0))
        assertTrue(real.projectCamera(xn, 0.0, p1))
        val scale = maxOf(w / base.active[3], h / base.active[2]).toDouble()
        val expectedShift = base.fx * (xn * k1 * xn * xn) * scale
        val shift = (p1[1] - p0[1]).toDouble() // sensor x runs down the portrait view
        assertEquals(expectedShift, shift, 0.05)
    }

    @Test
    fun `screen direction follows the projection in both pipelines, also behind the camera`() {
        val models = listOf(
            ArCameraModel.virtual(w, h, 0, 60.0, 1.0),
            ArCameraModel.camera(w, h, 0, ArCameraIntrinsics.defaultAssumption(), null, 1.0)
        )
        for (m in models) {
            m.setAttitude(attitude(0.0, 20.0))
            val d = DoubleArray(2)
            val e = enu(30.0, 20.0)
            assertTrue(m.screenDirection(e[0], e[1], e[2], d))
            assertTrue("right of view (virtual=${m.isVirtual})", d[0] > 0 && abs(d[1]) < d[0] * 0.3)
            val above = enu(0.0, 60.0)
            assertTrue(m.screenDirection(above[0], above[1], above[2], d))
            assertTrue("up (virtual=${m.isVirtual})", d[1] < 0 && abs(d[0]) < 1e-6 * abs(d[1]) + 1e-6)
            val behindRight = enu(150.0, 10.0)
            assertTrue(m.screenDirection(behindRight[0], behindRight[1], behindRight[2], d))
            assertTrue("behind-right points right (virtual=${m.isVirtual})", d[0] > 0)
            val behindLeft = enu(-150.0, 10.0)
            assertTrue(m.screenDirection(behindLeft[0], behindLeft[1], behindLeft[2], d))
            assertFalse("behind-left must not point right (virtual=${m.isVirtual})", d[0] > 0)
        }
    }
}
