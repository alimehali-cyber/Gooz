package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.AngleUtil
import com.alijafari.red.astronomy.ar.core.HeadingFusion
import com.alijafari.red.astronomy.ar.core.Wmm2025
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * True-north heading fusion (GAME_ROTATION_VECTOR + WMM2025-gated magnetometer), driven with
 * synthetic but physically consistent sensor streams: the magnetometer sees the WMM2025 field of
 * Frankfurt rotated into the device frame, plus noise; the game frame has an arbitrary yaw.
 */
class HeadingFusionTest {

    private val field = Wmm2025.calculate(50.11, 8.68, 0.1, 2026.79)
    private val random = Random(20261016L)

    /** Device → ENU for the rear camera pointing at azimuth/altitude, phone upright (no roll). */
    private fun attitude(azDeg: Double, altDeg: Double): DoubleArray {
        val az = Math.toRadians(azDeg); val alt = Math.toRadians(altDeg)
        val f = doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
        val r = doubleArrayOf(cos(az), -sin(az), 0.0)
        val u = doubleArrayOf(-sin(alt) * sin(az), -sin(alt) * cos(az), cos(alt))
        return doubleArrayOf(r[0], u[0], -f[0], r[1], u[1], -f[1], r[2], u[2], -f[2])
    }

    /** Vector rotation about Up by [deg] (counter-clockwise seen from above) applied to a device → world matrix. */
    private fun yawed(r: DoubleArray, deg: Double): DoubleArray {
        val out = DoubleArray(9)
        HeadingFusion.rotateYawVec(r, Math.toRadians(deg), out)
        return out
    }

    /** Rotation matrix → Android quaternion (x, y, z, w) (Shepperd). */
    private fun quat(m: DoubleArray): DoubleArray {
        val tr = m[0] + m[4] + m[8]
        return if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2.0
            doubleArrayOf((m[7] - m[5]) / s, (m[2] - m[6]) / s, (m[3] - m[1]) / s, 0.25 * s)
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2.0
            doubleArrayOf(0.25 * s, (m[1] + m[3]) / s, (m[2] + m[6]) / s, (m[7] - m[5]) / s)
        } else if (m[4] > m[8]) {
            val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2.0
            doubleArrayOf((m[1] + m[3]) / s, 0.25 * s, (m[5] + m[7]) / s, (m[2] - m[6]) / s)
        } else {
            val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2.0
            doubleArrayOf((m[2] + m[6]) / s, (m[5] + m[7]) / s, 0.25 * s, (m[3] - m[1]) / s)
        }
    }

    /** WMM2025 field in ENU, µT. */
    private fun fieldEnu(): DoubleArray = doubleArrayOf(field.y / 1000.0, field.x / 1000.0, -field.z / 1000.0)

    /** Device-frame magnetometer reading for attitude [r] (device → ENU) of a world field [b]. */
    private fun reading(r: DoubleArray, b: DoubleArray, noiseUt: Double): DoubleArray = DoubleArray(3) { i ->
        r[i] * b[0] + r[3 + i] * b[1] + r[6 + i] * b[2] + random.nextGaussian() * noiseUt
    }

    private class Sim(val fusion: HeadingFusion) {
        var t = 1_000_000_000L
        fun step(game: DoubleArray, mag: DoubleArray) {
            val q = game
            fusion.onGameRotation(q[0], q[1], q[2], q[3], t)
            fusion.onMagnetometer(mag[0], mag[1], mag[2], t)
            t += 20_000_000L // 50 Hz
        }
    }

    private fun headingErrorDeg(fusion: HeadingFusion, truth: DoubleArray): Double {
        val out = DoubleArray(9)
        assertTrue(fusion.trueNorthMatrix(out))
        return abs(AngleUtil.wrap180(HeadingFusion.boresightAzimuthDeg(out) - HeadingFusion.boresightAzimuthDeg(truth)))
    }

    private fun newFusion(): HeadingFusion = HeadingFusion().apply {
        setExpectedField(field)
        onMagAccuracy(3)
    }

    @Test
    fun `converges to true north from an arbitrary game-frame yaw`() {
        for ((az, alt) in listOf(37.0 to 25.0, 200.0 to 60.0, 305.0 to -10.0)) {
            val fusion = newFusion()
            val sim = Sim(fusion)
            val truth = attitude(az, alt)
            val game = quat(yawed(truth, -73.0)) // game azimuth = true azimuth + 73°
            repeat(50 * 8) { sim.step(game, reading(truth, fieldEnu(), 0.3)) }
            val err = headingErrorDeg(fusion, truth)
            assertTrue("az=$az alt=$alt heading error $err°", err < 0.5)
            val d = fusion.diagnostics()
            assertEquals(HeadingFusion.Mode.FUSED, d.mode)
            assertFalse(d.interference)
            assertTrue("declination applied", abs(d.declinationDeg - field.declinationDeg) < 1e-9)
        }
    }

    @Test
    fun `rejects a magnetic disturbance that contradicts the WMM and flags interference`() {
        val fusion = newFusion()
        val sim = Sim(fusion)
        val truth = attitude(120.0, 30.0)
        val game = quat(yawed(truth, 41.0))
        repeat(50 * 8) { sim.step(game, reading(truth, fieldEnu(), 0.3)) }
        assertTrue(headingErrorDeg(fusion, truth) < 0.5)

        // A steel object adds 25 µT towards the east: |B| and dip leave the WMM tolerance.
        val disturbed = fieldEnu().also { it[0] += 25.0 }
        repeat(50 * 2) { sim.step(game, reading(truth, disturbed, 0.3)) }
        assertTrue("heading must hold during the disturbance", headingErrorDeg(fusion, truth) < 0.5)
        assertTrue("interference must be flagged", fusion.diagnostics().interference)

        repeat(50 * 2) { sim.step(game, reading(truth, fieldEnu(), 0.3)) }
        assertFalse("interference clears with a clean field", fusion.diagnostics().interference)
        assertTrue(headingErrorDeg(fusion, truth) < 0.5)
    }

    @Test
    fun `holds through a brief heading transient`() {
        val fusion = newFusion()
        val sim = Sim(fusion)
        val truth = attitude(250.0, 15.0)
        val game = quat(yawed(truth, -12.0))
        repeat(50 * 8) { sim.step(game, reading(truth, fieldEnu(), 0.3)) }
        // The horizontal field swings by 20° for one second with |B| and dip unchanged.
        val swung = DoubleArray(3).also {
            val b = fieldEnu(); val a = Math.toRadians(20.0)
            it[0] = cos(a) * b[0] - sin(a) * b[1]; it[1] = sin(a) * b[0] + cos(a) * b[1]; it[2] = b[2]
        }
        repeat(50) { sim.step(game, reading(truth, swung, 0.3)) }
        assertTrue("transient must not drag the heading", headingErrorDeg(fusion, truth) < 0.5)
        repeat(50 * 2) { sim.step(game, reading(truth, fieldEnu(), 0.3)) }
        assertTrue(headingErrorDeg(fusion, truth) < 0.5)
    }

    @Test
    fun `rotation vector fallback is referenced to true north through the WMM declination`() {
        val fusion = newFusion()
        val truth = attitude(80.0, 20.0)
        // ROTATION_VECTOR is referenced to magnetic north: its azimuths are true azimuth − D.
        val magnetic = yawed(truth, field.declinationDeg)
        val q = quat(magnetic)
        fusion.onRotationVector(q[0], q[1], q[2], q[3], Math.toRadians(3.0))
        assertEquals(HeadingFusion.Mode.ROTATION_VECTOR, fusion.currentMode())
        assertTrue(headingErrorDeg(fusion, truth) < 1e-6)
    }

    @Test
    fun `game vector without magnetometer reports gyro-only mode`() {
        val fusion = HeadingFusion().apply { setHasMagnetometer(false) }
        val q = quat(attitude(10.0, 10.0))
        fusion.onGameRotation(q[0], q[1], q[2], q[3], 1L)
        assertEquals(HeadingFusion.Mode.GYRO_ONLY, fusion.currentMode())
        // Without a magnetic reference the absolute heading is unknown until a star alignment.
        assertTrue(fusion.diagnostics().headingSigmaDeg.isNaN())
    }
}
