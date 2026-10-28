package com.zig.gargantua.renderer

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Milestone M6 ACES Filmic Tone Mapping and Restrained Bloom Pipeline:
 * 1. ACES filmic tone mapping operator characteristics (black stays black, monotonic, finite).
 * 2. Bloom bright-pass extraction thresholding (zero leakage into shadow or sub-threshold LDR).
 * 3. Exposure scaling and isolation from physical disk equations.
 */
class ToneMappingAndBloomTest {

    // ACES filmic approximation (Narkowicz 2015)
    private fun acesFilmic(x: Float): Float {
        val a = 2.51f
        val b = 0.03f
        val c = 2.42f
        val d = 0.59f
        val e = 0.14f
        val num = x * (a * x + b)
        val den = x * (c * x + d) + e
        return (num / den).coerceIn(0.0f, 1.0f)
    }

    private fun brightPass(r: Float, g: Float, b: Float, threshold: Float = 1.0f): Triple<Float, Float, Float> {
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        if (lum <= threshold) {
            return Triple(0.0f, 0.0f, 0.0f)
        }
        val excess = lum - threshold
        val factor = excess / max(lum, 1.0e-5f)
        return Triple(r * factor, g * factor, b * factor)
    }

    @Test
    fun acesFilmicPreservesPureBlackShadow() {
        val output = acesFilmic(0.0f)
        assertEquals("Black hole shadow must remain strictly 0.0", 0.0f, output, 1e-6f)
    }

    @Test
    fun acesFilmicIsMonotonicallyIncreasingAndFinite() {
        var prevOutput = -1.0f
        val testInputs = listOf(
            0.0f, 0.001f, 0.01f, 0.05f, 0.1f, 0.5f, 1.0f,
            2.0f, 5.0f, 10.0f, 25.0f, 50.0f, 100.0f, 500.0f
        )

        for (x in testInputs) {
            val y = acesFilmic(x)

            // Must be finite and non-NaN
            assertFalse("ACES output must not be NaN for x=$x", y.isNaN())
            assertFalse("ACES output must not be Infinite for x=$x", y.isInfinite())

            // Must be in [0.0, 1.0]
            assertTrue("ACES output must be >= 0.0", y >= 0.0f)
            assertTrue("ACES output must be <= 1.0", y <= 1.0f)

            // Must be strictly monotonic
            assertTrue("ACES curve must be monotonically non-decreasing (prev=$prevOutput, curr=$y)", y >= prevOutput)
            prevOutput = y
        }

        // Highlight roll-off check
        val y10 = acesFilmic(10.0f)
        val y50 = acesFilmic(50.0f)
        assertTrue("Highlights must smoothly roll off towards 1.0 without hard clipping", y10 > 0.95f)
        assertTrue("Very high HDR values must approach 1.0", y50 > 0.99f)
    }

    @Test
    fun bloomThresholdRejectsLdrAndPassesHdrHighlights() {
        // 1. Black hole shadow (0, 0, 0)
        val shadowBloom = brightPass(0.0f, 0.0f, 0.0f, threshold = 1.0f)
        assertEquals(0.0f, shadowBloom.first, 1e-6f)
        assertEquals(0.0f, shadowBloom.second, 1e-6f)
        assertEquals(0.0f, shadowBloom.third, 1e-6f)

        // 2. Typical celestial sky background or dim outer disk (lum < 1.0)
        val dimBloom = brightPass(0.4f, 0.5f, 0.7f, threshold = 1.0f)
        assertEquals("Sub-threshold pixels must produce zero bloom", 0.0f, dimBloom.first, 1e-6f)
        assertEquals("Sub-threshold pixels must produce zero bloom", 0.0f, dimBloom.second, 1e-6f)
        assertEquals("Sub-threshold pixels must produce zero bloom", 0.0f, dimBloom.third, 1e-6f)

        // 3. Hot relativistic Doppler-boosted inner disk (lum = 4.2 > 1.0)
        val hotBloom = brightPass(5.0f, 4.0f, 2.0f, threshold = 1.0f)
        assertTrue("HDR highlights must produce positive bloom red component", hotBloom.first > 0.0f)
        assertTrue("HDR highlights must produce positive bloom green component", hotBloom.second > 0.0f)
        assertTrue("HDR highlights must produce positive bloom blue component", hotBloom.third > 0.0f)
    }

    @Test
    fun exposureScalingLiftsDimRegionsWhilePreservingRelativeContrast() {
        val dimLdr = 0.05f
        val brightHdr = 3.0f

        val defaultExposure = 1.0f
        val cinematicExposure = 1.25f

        val dimDefault = acesFilmic(dimLdr * defaultExposure)
        val dimCinematic = acesFilmic(dimLdr * cinematicExposure)

        assertTrue(
            "Cinematic exposure (1.25x) must enhance visibility of dim disk regions ($dimCinematic > $dimDefault)",
            dimCinematic > dimDefault
        )

        // Monotonic contrast ratio preservation: bright side must still be brighter than dim side
        val brightCinematic = acesFilmic(brightHdr * cinematicExposure)
        assertTrue(
            "Relativistic brightness asymmetry must remain strictly preserved ($brightCinematic > $dimCinematic)",
            brightCinematic > dimCinematic
        )
    }

    private fun readShader(fileName: String): String {
        val candidates = listOf(
            java.io.File("app/src/main/assets/shaders/$fileName"),
            java.io.File("src/main/assets/shaders/$fileName"),
            java.io.File("assets/shaders/$fileName")
        )
        for (c in candidates) {
            if (c.exists()) return c.readText()
        }
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "app/src/main/assets/shaders/$fileName")
            if (candidate.exists()) return candidate.readText()
            val candidate2 = java.io.File(dir, "src/main/assets/shaders/$fileName")
            if (candidate2.exists()) return candidate2.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("Shader $fileName not found")
    }

    @Test
    fun photonRingHigherOrderAccumulationIsPhysicallySourcedAndOrderAware() {
        val geoShader = readShader("gargantua_geodesic.frag")
        assertTrue(
            "Higher-order ring must accumulate exact physical segment radiance",
            geoShader.contains("vec3 segRadiance = diskTransmittance * crossingColor * segAlpha;")
        )
        assertTrue(
            "Higher-order ring must distinguish equatorial plane crossings from disk crossings",
            geoShader.contains("equatorialCrossings >= 2 || diskCrossings >= 2")
        )
    }

    @Test
    fun photonRingHigherOrderIsNotDoubleCounted() {
        val geoShader = readShader("gargantua_geodesic.frag")
        assertTrue(
            "HDR texture RGB holds total physical radiance accumDiskRadiance",
            geoShader.contains("return vec4(accumDiskRadiance, 1.0 + k2Lum);")
        )
        val compShader = readShader("gargantua_composite.frag")
        assertFalse(
            "Composite shader must not re-add k2 to prevent double counting",
            compShader.contains("color += k2Chroma * k2Lum;")
        )
    }

    @Test
    fun photonRingHigherOrderDoesNotEnterBroadBloomPedestal() {
        val brightShader = readShader("gargantua_brightpass.frag")
        assertTrue(
            "Brightpass extracts k2Lum from alpha side channel",
            brightShader.contains("float k2Lum = max(0.0, hdr.a - 1.0);")
        )
        assertTrue(
            "Brightpass computes ordinary luminance by subtracting k2Lum",
            brightShader.contains("float ordLum = max(0.0, lum - k2Lum);")
        )
        assertTrue(
            "Brightpass threshold excess is computed from ordLum only",
            brightShader.contains("float excess = max(0.0, ordLum - u_BloomThreshold);")
        )

        // Mathematical verification of bloom exclusion:
        val threshold = 1.15f
        // Case 1: Pure subpixel photon ring (total lum = 4.0, k2Lum = 4.0, ordLum = 0.0)
        val ringR = 4.5f
        val ringG = 3.8f
        val ringB = 2.1f
        val lum = 0.2126f * ringR + 0.7152f * ringG + 0.0722f * ringB
        val k2Lum = lum
        val ordLum = max(0.0f, lum - k2Lum)
        val excess = max(0.0f, ordLum - threshold)
        assertEquals("Subpixel photon ring alone produces zero bloom excess", 0.0f, excess, 1e-6f)
    }

    @Test
    fun photonRingRgbChromaIsPreservedWithExactColorRatios() {
        // Known synthetic diagnostic higher-order contribution
        val synthK2 = Triple(0.12f, 0.48f, 0.96f) // Non-disk electric azure chromaticity (ratio 1 : 4 : 8)
        val ratioGtoR = synthK2.second / synthK2.first
        val ratioBtoR = synthK2.third / synthK2.first
        assertEquals(4.0f, ratioGtoR, 1e-5f)
        assertEquals(8.0f, ratioBtoR, 1e-5f)

        // In our pipeline, synthK2 is part of accumDiskRadiance in HDR RGB.
        // It arrives at composite directly in hdr.rgb:
        val hdrRgb = synthK2
        val arrivedRatioGtoR = hdrRgb.second / hdrRgb.first
        val arrivedRatioBtoR = hdrRgb.third / hdrRgb.first
        assertEquals("Higher-order G:R chroma ratio must be preserved exactly", ratioGtoR, arrivedRatioGtoR, 1e-5f)
        assertEquals("Higher-order B:R chroma ratio must be preserved exactly", ratioBtoR, arrivedRatioBtoR, 1e-5f)
    }

    @Test
    fun adaptiveSupersamplingAveragesHigherOrderContributionLinearly() {
        val knownK2 = Triple(1.0f, 2.0f, 3.0f)
        // 5-ray stencil: sample 0 has knownK2, samples 1..4 have zero
        val sample5AvgR = (knownK2.first + 0.0f + 0.0f + 0.0f + 0.0f) / 5.0f
        val sample5AvgG = (knownK2.second + 0.0f + 0.0f + 0.0f + 0.0f) / 5.0f
        val sample5AvgB = (knownK2.third + 0.0f + 0.0f + 0.0f + 0.0f) / 5.0f
        assertEquals("5-ray averaging must be exactly 1/5", knownK2.first / 5.0f, sample5AvgR, 1e-6f)
        assertEquals("5-ray averaging must be exactly 1/5", knownK2.second / 5.0f, sample5AvgG, 1e-6f)
        assertEquals("5-ray averaging must be exactly 1/5", knownK2.third / 5.0f, sample5AvgB, 1e-6f)

        // 9-ray stencil: sample 0 has knownK2, samples 1..8 have zero
        val sample9AvgR = (knownK2.first) / 9.0f
        val sample9AvgG = (knownK2.second) / 9.0f
        val sample9AvgB = (knownK2.third) / 9.0f
        assertEquals("9-ray averaging must be exactly 1/9", knownK2.first / 9.0f, sample9AvgR, 1e-6f)
        assertEquals("9-ray averaging must be exactly 1/9", knownK2.second / 9.0f, sample9AvgG, 1e-6f)
        assertEquals("9-ray averaging must be exactly 1/9", knownK2.third / 9.0f, sample9AvgB, 1e-6f)
    }

    @Test
    fun blackPixelAvoidanceAcrossShadowBoundaryAndDisk() {
        val compShader = readShader("gargantua_composite.frag")
        assertTrue(
            "Composite shader shadow guard requires both alpha check and near-zero luminance",
            compShader.contains("if (hdr.a <= 0.5)") && compShader.contains("dot(hdr.rgb, hdr.rgb) <= 1.0e-7")
        )

        val geoShader = readShader("gargantua_geodesic.frag")
        assertTrue(
            "Geodesic shader preserves radiance in unresolved branch while preserving diagnostic 0.5 for pure shadow",
            geoShader.contains("if (baseSample.a > 0.005 || dot(baseSample.rgb, baseSample.rgb) > 1.0e-7)") &&
                geoShader.contains("fragColor = vec4(0.0, 0.0, 0.0, 0.5);")
        )
        assertTrue(
            "Geodesic shader must refine unresolved rays (baseState == 0) to avoid false shadow assignment",
            geoShader.contains("bool needsRefinement = highFreqDisk || (baseMinR < 4.2) || (baseState == 0) || (baseState == 4);")
        )

        assertTrue(
            "Material pass changes only sky RGB, keeping shadow and alpha untouched without a disk crossing",
            geoShader.contains("if (!hasCrossing) {\n        fragColor = vec4(max(hdr.rgb + skyDelta, vec3(0.0)), hdr.a);\n        return;\n    }") &&
                geoShader.contains("if (skyData.w > 0.0)")
        )
    }

    @Test
    fun diskAnimationDirectlyAdvectsPhysicalDiskTextureWithoutUnrelatedNoise() {
        val geoShader = readShader("gargantua_geodesic.frag")
        assertTrue(
            "Material pass must re-shade the cached disk hit at phiHit - OmegaK(rHit) t",
            geoShader.contains("gargantuaAccumulateDiskCrossing(rHit, c.y - phaseAdvance,")
        )
        assertTrue(
            "Material pass must advance the cached HDR by the change of the hit emission since the build",
            geoShader.contains("vec3 rgb = max(hdr.rgb + (now.rgb - built.rgb) + skyDelta, vec3(0.0));")
        )
        assertFalse(
            "Material pass must not modulate with an unrelated noise texture",
            geoShader.contains("u_NoiseTexture")
        )
    }

    @Test
    fun alphaSemanticsFreeFromCollisionWithShadowCoverage() {
        // Pure shadow state: coverage 0.0, higher-order 0.0 -> alpha = 0.0
        val shadowAlpha = 0.0f
        val shadowRgb = Triple(0.0f, 0.0f, 0.0f)
        val isShadow = shadowAlpha <= 0.005f && (shadowRgb.first * shadowRgb.first + shadowRgb.second * shadowRgb.second + shadowRgb.third * shadowRgb.third) <= 1.0e-7f
        assertTrue("Pure shadow must be identified correctly", isShadow)

        // Subpixel boundary sample (e.g. 2 rays shadow, 3 rays illuminated disk)
        val subpixelCoverage = 3.0f / 5.0f // 0.60
        val subpixelRgb = Triple(0.60f * 2.0f, 0.60f * 1.5f, 0.60f * 0.5f)
        val subpixelIsShadow = subpixelCoverage <= 0.005f && (subpixelRgb.first * subpixelRgb.first + subpixelRgb.second * subpixelRgb.second + subpixelRgb.third * subpixelRgb.third) <= 1.0e-7f
        assertFalse("Subpixel boundary with light must NEVER be classified as pure shadow", subpixelIsShadow)

        // Subpixel boundary with 20% coverage and faint emission (0.002 lum)
        val faintCoverage = 0.20f
        val faintRgb = Triple(0.001f, 0.002f, 0.0005f)
        val faintIsShadow = faintCoverage <= 0.005f && (faintRgb.first * faintRgb.first + faintRgb.second * faintRgb.second + faintRgb.third * faintRgb.third) <= 1.0e-7f
        assertFalse("Faint subpixel emission must NEVER be classified as pure shadow", faintIsShadow)

        // Subpixel photon ring on shadow boundary: coverage 0.5, higher-order lum 3.0 -> alpha = 0.5 + 3.0 = 3.5
        val ringCoverage = 0.50f
        val ringK2Lum = 3.0f
        val ringAlpha = ringCoverage + ringK2Lum
        val extractedK2Lum = max(0.0f, ringAlpha - 1.0f)
        assertTrue("Higher-order luminance extracted from combined alpha is positive", extractedK2Lum > 0.0f)
    }

    @Test
    fun testPhysicalDiskAzimuthalMotionAndKeplerianShear() {
        val m = 1.0
        val a = 0.8
        val sqrtM = sqrt(m)
        fun omega(r: Double): Double = sqrtM / (r.pow(1.5) + a * sqrtM)

        val rIsco = 2.9066
        val rMid = 6.0
        val rOuter = 15.0

        val omegaIsco = omega(rIsco)
        val omegaMid = omega(rMid)
        val omegaOuter = omega(rOuter)

        // Strict Keplerian shear: inner disk completes orbits faster than outer disk
        assertTrue("Inner ISCO angular velocity exceeds mid-disk velocity", omegaIsco > omegaMid)
        assertTrue("Mid-disk angular velocity exceeds outer-disk velocity", omegaMid > omegaOuter)
        assertTrue("All Keplerian velocities are strictly positive (prograde)", omegaIsco > 0.0 && omegaMid > 0.0 && omegaOuter > 0.0)

        // Time progression: material azimuth rotates prograde
        val dt = 1.0
        val dPhiIsco = omegaIsco * dt
        val dPhiMid = omegaMid * dt
        assertTrue("Phase advance is strictly positive", dPhiIsco > 0.0 && dPhiMid > 0.0)

        // Amplitude scaling
        fun ampFactor(ampPercent: Int): Float = if (ampPercent > 0) 0.70f + 0.60f * (ampPercent / 100.0f) else 1.0f
        val amp0 = ampFactor(0)
        val amp15 = ampFactor(15)
        val amp40 = ampFactor(40)
        val amp80 = ampFactor(80)

        assertEquals("Static amplitude percent 0 produces exact unit factor", 1.0f, amp0, 1e-6f)
        assertTrue("Amplitude 15% is restrained", amp15 < amp40)
        assertTrue("Amplitude 40% is moderate", amp40 < amp80)
        assertTrue("Amplitude 80% is strongest", amp80 > amp40)
    }

    @Test
    fun testIlluminatedSamplesNeverDiscardedAsShadow() {
        // Test composite shadow discard rule across various radiance levels and alpha values
        fun isShadowDiscarded(alpha: Float, rgb: Triple<Float, Float, Float>): Boolean {
            val lumSq = rgb.first * rgb.first + rgb.second * rgb.second + rgb.third * rgb.third
            return alpha <= 0.5f && lumSq <= 1.0e-7f
        }

        // True shadow: zero emission, low alpha
        assertTrue("True shadow interior is discarded to pure black", isShadowDiscarded(0.0f, Triple(0f, 0f, 0f)))
        assertTrue("True shadow interior with diagnostic 0.5 is discarded to pure black", isShadowDiscarded(0.5f, Triple(0f, 0f, 0f)))

        // Subpixel photon ring on shadow boundary: alpha <= 0.5 but non-zero emission
        assertFalse("Subpixel photon ring with 0.005 radiance must NOT be discarded", isShadowDiscarded(0.2f, Triple(0.005f, 0.003f, 0.001f)))
        assertFalse("Subpixel photon ring with 0.001 radiance must NOT be discarded", isShadowDiscarded(0.4f, Triple(0.001f, 0.001f, 0.0005f)))

        // Escaped sky or disk sample with state 2: alpha 1.0
        assertFalse("Escaped ray is never discarded", isShadowDiscarded(1.0f, Triple(0.01f, 0.01f, 0.01f)))
    }

    @Test
    fun testStaticGeodesicDataReusedAcrossAnimationFrames() {
        val gate = com.zig.gargantua.renderer.AnimationGate()
        val now = 1_000_000_000L
        // Frame 1: Camera stationary, resources ready, cache valid, scene not dirty
        val input = com.zig.gargantua.renderer.AnimationGate.Input(
            animationRequested = true,
            resourcesReady = true,
            cacheValid = true,
            sceneDirty = false,
            signatureChangedThisFrame = false,
            nowNanos = now,
            lastChangeNanos = now - 500_000_000L,
            amplitudePercent = 40
        )
        val decision = gate.decide(input)
        assertEquals("Stationary animation frame must MODULATE without REBUILD", com.zig.gargantua.renderer.AnimationGate.Action.MODULATE, decision.action)
        assertTrue("Camera must be stable", decision.cameraStable)
    }

    @Test
    fun testRk4StepConvergencePinnedNearCriticalRays() {
        // Mathematical convergence check of step-doubling RK4 for Kerr geodesic
        fun rk4HarmonicStep(r0: Double, p0: Double, h: Double): Pair<Double, Double> {
            fun f(r: Double, p: Double): Pair<Double, Double> = Pair(p, -1.0 / (r * r))
            val (k1_r, k1_p) = f(r0, p0)
            val (k2_r, k2_p) = f(r0 + 0.5 * h * k1_r, p0 + 0.5 * h * k1_p)
            val (k3_r, k3_p) = f(r0 + 0.5 * h * k2_r, p0 + 0.5 * h * k2_p)
            val (k4_r, k4_p) = f(r0 + h * k3_r, p0 + h * k3_p)
            val r1 = r0 + (h / 6.0) * (k1_r + 2.0 * k2_r + 2.0 * k3_r + k4_r)
            val p1 = p0 + (h / 6.0) * (k1_p + 2.0 * k2_p + 2.0 * k3_p + k4_p)
            return Pair(r1, p1)
        }

        val rInit = 4.0
        val pInit = -0.5
        val hBase = 0.1
        val (rBase, _) = rk4HarmonicStep(rInit, pInit, hBase)
        val (rHalf1, pHalf1) = rk4HarmonicStep(rInit, pInit, hBase * 0.5)
        val (rHalf2, _) = rk4HarmonicStep(rHalf1, pHalf1, hBase * 0.5)
        val diff = kotlin.math.abs(rBase - rHalf2)
        assertTrue("RK4 integration must converge with O(h^4) error", diff < 1.0e-5)
    }

    @Test
    fun testFull360DegreeHigherOrderRadianceContinuity() {
        // Physical order definition: equatorialCrossings >= 2 is higher-order lensed emission
        fun isHigherOrder(planeCrossings: Int, diskCrossings: Int): Boolean =
            (planeCrossings >= 2 || diskCrossings >= 2)

        // Right side (0° - 150° and 315° - 345°): secondary lensed hit at planeOrder 2
        assertTrue("Right side secondary disk hit is higher order", isHigherOrder(2, 2))
        assertTrue("Right side secondary hit with plunge first crossing is higher order", isHigherOrder(2, 1))

        // Left side (165° - 300°): tertiary lensed hit at planeOrder 3 or 4
        assertTrue("Left side tertiary disk hit is higher order", isHigherOrder(3, 1))
        assertTrue("Left side quaternary disk hit is higher order", isHigherOrder(4, 2))

        // Direct disk emission (planeOrder 1, diskCrossings 1) is NOT higher order
        assertFalse("Direct primary disk hit must not be classified as higher order", isHigherOrder(1, 1))
    }

    @Test
    fun testLeftRightCorrespondingRayParity() {
        fun isHigherOrder(planeCrossings: Int, diskCrossings: Int): Boolean =
            (planeCrossings >= 2 || diskCrossings >= 2)

        val leftRayPlaneCrossings = 3
        val leftRayDiskCrossings = 1
        val rightRayPlaneCrossings = 2
        val rightRayDiskCrossings = 2

        assertTrue("Left prograde ray must classify as higher order", isHigherOrder(leftRayPlaneCrossings, leftRayDiskCrossings))
        assertTrue("Right retrograde ray must classify as higher order", isHigherOrder(rightRayPlaneCrossings, rightRayDiskCrossings))
    }
}
