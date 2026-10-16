package com.alijafari.red.astronomy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alijafari.red.astronomy.astro_engine.ARCalibrationManager
import com.alijafari.red.astronomy.astro_engine.AlignmentTarget
import com.alijafari.red.astronomy.domain.CelestialObject
import com.alijafari.red.astronomy.domain.ObjectType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit tests for the Guided 1-Point Reference Alignment system:
 * - ΔAz wrap-around math (0°/360°, ±180°)
 * - Yaw-only application: azimuth shifts exactly, pitch/roll invariant
 * - Target visibility filtering (15°–80°) and brightness ranking
 * - Guidance arrow angle + auto show/hide hysteresis
 * - Apply/reset persistence lifecycle
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AROnePointAlignmentTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ARCalibrationManager.init(context)
        ARCalibrationManager.resetAlignment(context)
        ARCalibrationManager.setAutoPromptEnabled(true, context)
    }

    @After
    fun tearDown() {
        // Restore shared singleton state so other test classes are unaffected
        ARCalibrationManager.resetAlignment(context)
        ARCalibrationManager.setAutoPromptEnabled(true, context)
    }

    // ------------------------------------------------------------------
    // Angle normalization + ΔAz wrap-around
    // ------------------------------------------------------------------

    @Test
    fun testNormalizeAngle180() {
        assertEquals(0.0, ARCalibrationManager.normalizeAngle180(0.0), 1e-9)
        assertEquals(0.0, ARCalibrationManager.normalizeAngle180(360.0), 1e-9)
        assertEquals(0.0, ARCalibrationManager.normalizeAngle180(-360.0), 1e-9)
        assertEquals(45.0, ARCalibrationManager.normalizeAngle180(720.0 + 45.0), 1e-9)
        assertEquals(-170.0, ARCalibrationManager.normalizeAngle180(190.0), 1e-9)
        assertEquals(170.0, ARCalibrationManager.normalizeAngle180(-190.0), 1e-9)
        assertEquals(-180.0, ARCalibrationManager.normalizeAngle180(180.0), 1e-9)
        assertEquals(-180.0, ARCalibrationManager.normalizeAngle180(-180.0), 1e-9)
        assertEquals(-1.0, ARCalibrationManager.normalizeAngle180(359.0), 1e-9)
        assertEquals(1.0, ARCalibrationManager.normalizeAngle180(-359.0), 1e-9)
        assertEquals(45.5, ARCalibrationManager.normalizeAngle180(45.5), 1e-9)
    }

    @Test
    fun testComputeYawOffsetWraparound() {
        // Crossing 0°/360° boundary both directions
        assertEquals(10f, ARCalibrationManager.computeYawOffset(5.0, 355.0), 1e-4f)
        assertEquals(-10f, ARCalibrationManager.computeYawOffset(355.0, 5.0), 1e-4f)
        // ±180° boundary
        assertEquals(-180f, ARCalibrationManager.computeYawOffset(0.0, 180.0), 1e-4f)
        assertEquals(-180f, ARCalibrationManager.computeYawOffset(180.0, 0.0), 1e-4f)
        // Small typical magnetometer drift
        assertEquals(0.5f, ARCalibrationManager.computeYawOffset(90.5, 90.0), 1e-4f)
        assertEquals(-2.3f, ARCalibrationManager.computeYawOffset(200.0, 202.3), 1e-4f)
        // Identity
        assertEquals(0f, ARCalibrationManager.computeYawOffset(123.4, 123.4), 1e-4f)
    }

    // ------------------------------------------------------------------
    // Yaw-only application: azimuth shifts exactly, pitch/roll invariant
    // ------------------------------------------------------------------

    /**
     * Builds a True-North rotation matrix for a camera pointing at (az, alt) with roll,
     * using the same convention as OrientationProvider / ARProjectionEngine.
     */
    private fun createTrueRotationMatrix(azimuthDeg: Double, altitudeDeg: Double, rollDeg: Double): FloatArray {
        val azRad = Math.toRadians(azimuthDeg)
        val altRad = Math.toRadians(altitudeDeg)
        val rollRad = Math.toRadians(rollDeg)

        val px = cos(altRad) * sin(azRad)
        val py = cos(altRad) * cos(azRad)
        val pz = sin(altRad)

        val rx0 = cos(azRad)
        val ry0 = -sin(azRad)
        val rz0 = 0.0

        val ux0 = -sin(altRad) * sin(azRad)
        val uy0 = -sin(altRad) * cos(azRad)
        val uz0 = cos(altRad)

        val cosR = cos(rollRad)
        val sinR = sin(rollRad)

        val rx = (rx0 * cosR - ux0 * sinR).toFloat()
        val ry = (ry0 * cosR - uy0 * sinR).toFloat()
        val rz = (rz0 * cosR - uz0 * sinR).toFloat()

        val ux = (rx0 * sinR + ux0 * cosR).toFloat()
        val uy = (ry0 * sinR + uy0 * cosR).toFloat()
        val uz = (rz0 * sinR + uz0 * cosR).toFloat()

        return floatArrayOf(
            rx, ux, (-px).toFloat(),
            ry, uy, (-py).toFloat(),
            rz, uz, (-pz).toFloat()
        )
    }

    /** Extracts (azimuth, pitch, roll) exactly like OrientationProvider.updateQuaternion. */
    private fun extractAzPitchRoll(r: FloatArray): Triple<Float, Float, Float> {
        val px = -r[2]
        val py = -r[5]
        val pz = -r[8]

        val azimuthDeg = ((Math.toDegrees(atan2(px.toDouble(), py.toDouble())) + 360.0) % 360.0).toFloat()
        val pitchDeg = Math.toDegrees(asin(pz.toDouble().coerceIn(-1.0, 1.0))).toFloat()

        val rx = r[0]
        val ry = r[3]
        val rz = r[6]

        val horizLen = sqrt((px * px + py * py).toDouble()).toFloat()
        val rollDeg = if (horizLen > 1e-4f) {
            val rSkyX = py / horizLen
            val rSkyY = -px / horizLen
            val rSkyZ = 0f

            val uSkyX = -px * pz / horizLen
            val uSkyY = -py * pz / horizLen
            val uSkyZ = horizLen

            val dotRight = rx * rSkyX + ry * rSkyY + rz * rSkyZ
            val dotUp = rx * uSkyX + ry * uSkyY + rz * uSkyZ

            Math.toDegrees(atan2(dotUp.toDouble(), dotRight.toDouble())).toFloat()
        } else {
            Math.toDegrees(atan2(rz.toDouble(), r[7].toDouble())).toFloat()
        }

        return Triple(azimuthDeg, pitchDeg, rollDeg)
    }

    @Test
    fun testYawOnlyApplicationShiftsAzimuthExactly() {
        val azimuths = doubleArrayOf(0.0, 45.0, 120.0, 270.0, 359.0)
        val altitudes = doubleArrayOf(-10.0, 0.0, 30.0, 60.0, 80.0)
        val rolls = doubleArrayOf(-30.0, 0.0, 15.0)
        val offsets = floatArrayOf(-25f, -3.7f, 0f, 2.5f, 25f)

        for (az in azimuths) {
            for (alt in altitudes) {
                for (roll in rolls) {
                    val rTrue = createTrueRotationMatrix(az, alt, roll)
                    val (azBefore, _, _) = extractAzPitchRoll(rTrue)

                    for (offset in offsets) {
                        val mYaw = ARCalibrationManager.createYawOnlyRotationMatrix(offset)
                        val rFinal = ARCalibrationManager.multiplyMatrix3x3(mYaw, rTrue)
                        val (azAfter, _, _) = extractAzPitchRoll(rFinal)

                        val appliedShift = ARCalibrationManager.normalizeAngle180((azAfter - azBefore).toDouble())
                        assertEquals(
                            "Azimuth must shift by exactly +ΔAz (az=$az alt=$alt roll=$roll offset=$offset)",
                            offset.toDouble(),
                            appliedShift,
                            1e-3
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testYawOnlyApplicationKeepsPitchRollInvariant() {
        val azimuths = doubleArrayOf(0.0, 45.0, 120.0, 270.0, 359.0)
        val altitudes = doubleArrayOf(-10.0, 0.0, 30.0, 60.0, 80.0)
        val rolls = doubleArrayOf(-30.0, 0.0, 15.0)
        val offsets = floatArrayOf(-25f, -3.7f, 2.5f, 25f)

        for (az in azimuths) {
            for (alt in altitudes) {
                for (roll in rolls) {
                    val rTrue = createTrueRotationMatrix(az, alt, roll)
                    val (_, pitchBefore, rollBefore) = extractAzPitchRoll(rTrue)

                    for (offset in offsets) {
                        val mYaw = ARCalibrationManager.createYawOnlyRotationMatrix(offset)
                        val rFinal = ARCalibrationManager.multiplyMatrix3x3(mYaw, rTrue)
                        val (_, pitchAfter, rollAfter) = extractAzPitchRoll(rFinal)

                        assertEquals(
                            "Pitch must be invariant under yaw rotation (az=$az alt=$alt roll=$roll offset=$offset)",
                            pitchBefore.toDouble(),
                            pitchAfter.toDouble(),
                            1e-3
                        )
                        // Roll is circular; compare wrapped difference
                        val rollDrift = abs(ARCalibrationManager.normalizeAngle180((rollAfter - rollBefore).toDouble()))
                        assertTrue(
                            "Roll must be invariant under yaw rotation " +
                                "(az=$az alt=$alt roll=$roll offset=$offset drift=$rollDrift)",
                            rollDrift < 1e-3
                        )
                    }
                }
            }
        }
    }

    @Test
    fun testYawMatrixIsPureWorldZRotation() {
        // M(90°): x-axis maps to -y, y-axis maps to +x, z untouched
        val m = ARCalibrationManager.createYawOnlyRotationMatrix(90f)
        assertEquals(0f, m[0], 1e-5f)
        assertEquals(1f, m[1], 1e-5f)
        assertEquals(0f, m[2], 1e-5f)
        assertEquals(-1f, m[3], 1e-5f)
        assertEquals(0f, m[4], 1e-5f)
        assertEquals(0f, m[5], 1e-5f)
        assertEquals(0f, m[6], 1e-5f)
        assertEquals(0f, m[7], 1e-5f)
        assertEquals(1f, m[8], 1e-5f)

        // Orthogonality: M · Mᵀ = I for several angles
        for (angle in floatArrayOf(-25f, -3.7f, 0f, 2.5f, 90f, 180f)) {
            val mat = ARCalibrationManager.createYawOnlyRotationMatrix(angle)
            val transpose = floatArrayOf(
                mat[0], mat[3], mat[6],
                mat[1], mat[4], mat[7],
                mat[2], mat[5], mat[8]
            )
            val prod = ARCalibrationManager.multiplyMatrix3x3(mat, transpose)
            for (i in 0..2) {
                for (j in 0..2) {
                    val expected = if (i == j) 1f else 0f
                    assertEquals("M($angle°) must be orthogonal at [$i,$j]", expected, prod[i * 3 + j], 1e-5f)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Target visibility filtering + brightness ranking
    // ------------------------------------------------------------------

    private fun fakeObject(id: String, type: ObjectType, mag: Double): CelestialObject {
        return CelestialObject(
            id = id,
            type = type,
            nameEn = id,
            nameFa = id,
            raDeg = 0.0,
            decDeg = 0.0,
            magnitude = mag,
            constellationEn = "",
            constellationFa = "",
            distanceLightYears = 0.0,
            category = "",
            descriptionEn = "",
            descriptionFa = "",
            observationTipEn = "",
            observationTipFa = ""
        )
    }

    private fun fakeTarget(
        id: String,
        alt: Double,
        mag: Double,
        priority: Int,
        type: ObjectType = ObjectType.STAR
    ): AlignmentTarget {
        return AlignmentTarget(
            id = id,
            nameEn = id,
            nameFa = id,
            type = type,
            celestialObject = fakeObject(id, type, mag),
            azimuthDeg = 180.0,
            altitudeDeg = alt,
            magnitude = mag,
            priority = priority
        )
    }

    @Test
    fun testVisibilityFilterKeepsOnly15To80Degrees() {
        val candidates = listOf(
            fakeTarget("below_horizon", alt = -5.0, mag = -4.0, priority = 1),
            fakeTarget("too_low", alt = 14.9, mag = -4.0, priority = 1),
            fakeTarget("low_edge", alt = 15.0, mag = 1.0, priority = 2),
            fakeTarget("mid", alt = 45.0, mag = 1.0, priority = 2),
            fakeTarget("high_edge", alt = 80.0, mag = 1.0, priority = 2),
            fakeTarget("too_high", alt = 80.1, mag = -4.0, priority = 1),
            fakeTarget("zenith", alt = 89.0, mag = -12.0, priority = 1)
        )

        val result = ARCalibrationManager.filterAndRankTargets(candidates, maxTargets = 10)
        val ids = result.map { it.id }

        assertTrue(ids.contains("low_edge"))
        assertTrue(ids.contains("mid"))
        assertTrue(ids.contains("high_edge"))
        assertFalse(ids.contains("below_horizon"))
        assertFalse(ids.contains("too_low"))
        assertFalse(ids.contains("too_high"))
        assertFalse(ids.contains("zenith"))
    }

    @Test
    fun testRankingPrioritizesSolarSystemThenBrightness() {
        val candidates = listOf(
            fakeTarget("faint_star", alt = 45.0, mag = 1.5, priority = 2),
            fakeTarget("bright_star", alt = 45.0, mag = -1.4, priority = 2),
            fakeTarget("dim_planet", alt = 45.0, mag = 1.8, priority = 1, type = ObjectType.PLANET),
            fakeTarget("bright_planet", alt = 45.0, mag = -4.4, priority = 1, type = ObjectType.PLANET)
        )

        val result = ARCalibrationManager.filterAndRankTargets(candidates, maxTargets = 10)
        assertEquals(
            listOf("bright_planet", "dim_planet", "bright_star", "faint_star"),
            result.map { it.id }
        )
    }

    @Test
    fun testMaxThreeTargetsReturned() {
        val candidates = (1..8).map { i ->
            fakeTarget("star_$i", alt = 20.0 + i, mag = i.toDouble() - 2.0, priority = 2)
        }
        val result = ARCalibrationManager.filterAndRankTargets(candidates)
        assertEquals(3, result.size)
        // Brightest first
        assertEquals("star_1", result[0].id)
        assertEquals("star_2", result[1].id)
        assertEquals("star_3", result[2].id)
    }

    // ------------------------------------------------------------------
    // Guidance arrow angle + auto show/hide
    // ------------------------------------------------------------------

    @Test
    fun testGuidanceDeltasAndSeparation() {
        val g = ARCalibrationManager.computeGuidance(
            targetAzimuthDeg = 100.0,
            targetAltitudeDeg = 50.0,
            phoneAzimuthDeg = 100.0,
            phoneAltitudeDeg = 30.0,
            arrowsCurrentlyVisible = false
        )
        assertEquals(0f, g.deltaAzimuthDeg, 1e-4f)
        assertEquals(20f, g.deltaAltitudeDeg, 1e-4f)
        assertEquals(20f, g.separationDeg, 1e-3f)
    }

    @Test
    fun testGuidanceArrowPointsUpRightDownLeft() {
        // Target straight above boresight -> arrow UP (0 rad)
        val up = ARCalibrationManager.computeGuidance(100.0, 50.0, 100.0, 30.0, false)
        assertEquals(0f, up.arrowAngleRad, 1e-4f)

        // Target to the east (right) at same altitude -> arrow RIGHT (+PI/2)
        val right = ARCalibrationManager.computeGuidance(20.0, 45.0, 0.0, 45.0, false)
        assertEquals((PI / 2).toFloat(), right.arrowAngleRad, 1e-4f)

        // Target to the west (left) at same altitude -> arrow LEFT (-PI/2)
        val left = ARCalibrationManager.computeGuidance(340.0, 45.0, 0.0, 45.0, false)
        assertEquals((-PI / 2).toFloat(), left.arrowAngleRad, 1e-4f)

        // Target straight below boresight -> arrow DOWN (±PI)
        val down = ARCalibrationManager.computeGuidance(100.0, 30.0, 100.0, 50.0, false)
        assertEquals(PI.toFloat(), abs(down.arrowAngleRad), 1e-4f)
    }

    @Test
    fun testGuidanceDeltaAzWrapsAtNorth() {
        val g = ARCalibrationManager.computeGuidance(
            targetAzimuthDeg = 1.0,
            targetAltitudeDeg = 30.0,
            phoneAzimuthDeg = 359.0,
            phoneAltitudeDeg = 30.0,
            arrowsCurrentlyVisible = false
        )
        assertEquals(2f, g.deltaAzimuthDeg, 1e-3f)
    }

    @Test
    fun testGuidanceArrowsAutoHideNearCenter() {
        // Far from center (> 5°): arrows appear
        val far = ARCalibrationManager.computeGuidance(100.0, 50.0, 100.0, 30.0, false)
        assertTrue("Arrows must show when separation > 5°", far.arrowsVisible)

        // Close to center (< 4°): arrows auto-disappear for unobstructed centering
        val near = ARCalibrationManager.computeGuidance(101.0, 30.5, 100.0, 30.0, true)
        assertTrue("Fixture separation must be < 4°", near.separationDeg < 4f)
        assertFalse("Arrows must hide when separation < 4°", near.arrowsVisible)

        // Hysteresis band (4°–5°): visibility follows previous state, no flicker
        val bandShown = ARCalibrationManager.computeGuidance(0.0, 44.5, 0.0, 40.0, true)
        assertEquals(4.5f, bandShown.separationDeg, 1e-3f)
        assertTrue("Arrows must stay visible at 4.5° if previously shown", bandShown.arrowsVisible)

        val bandHidden = ARCalibrationManager.computeGuidance(0.0, 44.5, 0.0, 40.0, false)
        assertFalse("Arrows must stay hidden at 4.5° if previously hidden", bandHidden.arrowsVisible)
    }

    // ------------------------------------------------------------------
    // Apply / reset lifecycle
    // ------------------------------------------------------------------

    @Test
    fun testApplyOnePointAlignmentStoresAndPersistsOffset() {
        val applied = ARCalibrationManager.applyOnePointAlignment(
            targetAzimuthDeg = 100.0,
            currentAzimuthDeg = 90.0,
            referenceName = "TestStar",
            context = context
        )
        assertEquals(10f, applied, 1e-4f)

        val offsets = ARCalibrationManager.getOffsets()
        assertEquals(10f, offsets.yawOffsetDeg, 1e-4f)
        assertEquals("TestStar", offsets.referenceStarName)
        assertTrue(offsets.lastCalibratedTimeMs > 0L)
        assertTrue(offsets.isCalibrated)
        assertEquals(10f, ARCalibrationManager.calibrationFlow.value.yawOffsetDeg, 1e-4f)
    }

    @Test
    fun testApplyOnePointAlignmentWrapsAcrossNorth() {
        val applied = ARCalibrationManager.applyOnePointAlignment(
            targetAzimuthDeg = 2.0,
            currentAzimuthDeg = 358.0,
            referenceName = "Polaris",
            context = context
        )
        assertEquals(4f, applied, 1e-4f)
        assertTrue(ARCalibrationManager.getOffsets().isCalibrated)
    }

    @Test
    fun testResetAlignmentClearsOffset() {
        ARCalibrationManager.applyOnePointAlignment(100.0, 90.0, "TestStar", context)
        assertTrue(ARCalibrationManager.getOffsets().isCalibrated)

        ARCalibrationManager.resetAlignment(context)

        val offsets = ARCalibrationManager.getOffsets()
        assertEquals(0f, offsets.yawOffsetDeg, 0f)
        assertEquals("", offsets.referenceStarName)
        assertEquals(0L, offsets.lastCalibratedTimeMs)
        assertFalse(offsets.isCalibrated)
    }

    // ------------------------------------------------------------------
    // Re-alignment must accumulate, not toggle (audit finding #4)
    // ------------------------------------------------------------------

    @Test
    fun testRepeatedAlignmentOnSameTargetIsStableInsteadOfToggling() {
        // Raw compass heading 100°, true target azimuth 103°.
        val rawHeading = 100.0
        val target = 103.0
        var offset = 0.0
        repeat(4) { i ->
            val displayed = ARCalibrationManager.normalizeAngle180(rawHeading + offset)
            val applied = ARCalibrationManager.applyOnePointAlignment(target, (displayed + 360.0) % 360.0, "Star", context)
            offset = applied.toDouble()
            assertEquals("alignment #${i + 1} must keep the +3° correction", 3.0, offset, 1e-4)
        }
        assertTrue(ARCalibrationManager.getOffsets().isCalibrated)
    }

    @Test
    fun testSecondAlignmentRefinesTheExistingOffset() {
        ARCalibrationManager.applyOnePointAlignment(103.0, 100.0, "Vega", context) // +3
        // The view now shows 103°, but a better reference says the boresight is really at 104.5°.
        val total = ARCalibrationManager.applyOnePointAlignment(104.5, 103.0, "Altair", context)
        assertEquals(4.5f, total, 1e-4f)
        assertEquals(4.5f, ARCalibrationManager.getOffsets().yawOffsetDeg, 1e-4f)
    }

    @Test
    fun testZeroResidualAlignmentStillCountsAsAligned() {
        val total = ARCalibrationManager.applyOnePointAlignment(250.0, 250.0, "Moon", context)
        assertEquals(0f, total, 0f)
        assertTrue("an alignment confirming the compass exactly is still an alignment", ARCalibrationManager.getOffsets().isCalibrated)
    }

    @Test
    fun testImplausiblyLargeOffsetIsRejected() {
        val total = ARCalibrationManager.applyOnePointAlignment(200.0, 100.0, "Wrong object", context)
        assertTrue(total.isNaN())
        assertFalse(ARCalibrationManager.getOffsets().isCalibrated)
        assertEquals(0f, ARCalibrationManager.getOffsets().yawOffsetDeg, 0f)
    }
}
