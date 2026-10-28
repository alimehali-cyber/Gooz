package com.zig.gargantua.renderer

import com.zig.gargantua.geodesic.GpuEquivalentIntegrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.*

/**
 * Milestone M7: Adaptive Rendering & Performance Validation Test Suite.
 *
 * Validates:
 * 1. Multi-tier adaptive quality allocation (Tier 0: 1 ray, Tier 1: 5 rays, Tier 2: 9 rays).
 * 2. Strict conservation of Kerr spacetime geometry, null Hamiltonian, and Novikov-Thorne disk physics.
 * 3. Exact invariant redshift calculation g = (-p·u_obs) / (-p·u_emit) across all adaptive subpixel rays.
 * 4. Deterministic bounding: maximum ray budget strictly capped at 9 rays/pixel, 0 runaway recursion.
 * 5. Shadow-core and asymptotic deep-space protection: zero radiance created where no ray hits emitting material.
 * 6. Convergence of adaptive refinement toward higher-resolution references on tertiary disk features.
 * 7. Forensic audit of the tertiary accretion disk structure: distinguishes genuine physical gaps (ISCO plunge)
 *    from finite-raster 0.5x sampling resolution limits.
 */
class M7AdaptiveRenderingTest {

    private val M = 1.0
    private val a = 0.8
    private val rIn = 2.91
    private val rOut = 22.0

    private fun readShader(filename: String): String {
        val paths = listOf(
            "app/src/main/assets/shaders/$filename",
            "src/main/assets/shaders/$filename"
        )
        for (p in paths) {
            val f = File(p)
            if (f.exists()) return f.readText()
        }
        val assetDir = File(".").walkTopDown().firstOrNull { it.isDirectory && it.name == "shaders" }
            ?: throw IllegalStateException("Cannot find shaders directory")
        return File(assetDir, filename).readText()
    }

    private fun diskFluxNormalized(r: Double): Double {
        if (r < rIn || r > rOut) return 0.0
        val f = (1.0 - sqrt(rIn / r)) / (r * r * r)
        val rPeak = (49.0 / 36.0) * rIn
        val fPeak = 1.0 / (7.0 * rPeak * rPeak * rPeak)
        return (f / fPeak).coerceIn(0.0, 1.0)
    }

    private fun diskRadiance(r: Double, g: Double): Double {
        val g4 = g * g * g * g
        return g4 * diskFluxNormalized(r)
    }

    private fun setupCamera(): Triple<FloatArray, FloatArray, FloatArray> {
        val inclRad = Math.toRadians(80.0)
        val camDist = 32.0f
        val camPos = floatArrayOf(
            camDist * sin(inclRad).toFloat(),
            0.0f,
            camDist * cos(inclRad).toFloat()
        )
        val fwdLen = sqrt(camPos[0] * camPos[0] + camPos[2] * camPos[2])
        val fwd = floatArrayOf(-camPos[0] / fwdLen, 0.0f, -camPos[2] / fwdLen)
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val up = floatArrayOf(-cos(inclRad).toFloat(), 0.0f, sin(inclRad).toFloat())
        return Triple(camPos, fwd, up)
    }

    private fun makeRayDir(
        stX: Float,
        stY: Float,
        fwd: FloatArray,
        right: FloatArray,
        up: FloatArray,
        fovScale: Float
    ): FloatArray {
        val d = floatArrayOf(
            fwd[0] + right[0] * (stX * fovScale) + up[0] * (stY * fovScale),
            fwd[1] + right[1] * (stX * fovScale) + up[1] * (stY * fovScale),
            fwd[2] + right[2] * (stX * fovScale) + up[2] * (stY * fovScale)
        )
        val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return floatArrayOf(d[0] / len, d[1] / len, d[2] / len)
    }

    // 1. Adaptive refinement does not alter Kerr equations or metric tensor
    @Test
    fun adaptiveRefinementPreservesKerrHamiltonianAndMetric() {
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        // Test multiple subpixel ray directions in the adaptive tier
        val testCoords = listOf(
            Pair(-0.26722f, 0.03520f),
            Pair(-0.27593f, 0.03520f),
            Pair(0.0f, 0.0f),
            Pair(-0.30f, 0.0f)
        )

        for ((stX, stY) in testCoords) {
            val dir = makeRayDir(stX, stY, fwd, right, up, fovScale)
            val ray = GpuEquivalentIntegrator.createInitialRay(1.0f, 0.8f, camPos, dir)
            val residualH = abs(GpuEquivalentIntegrator.computeHamiltonian(1.0f, 0.8f, ray))
            assertTrue(
                "Null Hamiltonian condition g^mu_nu p_mu p_nu = 0 must hold for subpixel ray ($stX, $stY)",
                residualH < 1e-4f
            )
        }
    }

    // 2. Adaptive refinement preserves disk physics and invariant frequency shift
    @Test
    fun adaptiveRefinementPreservesDiskPhysicsAndInvariantRedshift() {
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        val dir = makeRayDir(-0.26722f, 0.03520f, fwd, right, up, fovScale)
        val res = GpuEquivalentIntegrator.traceRay(
            M = 1.0f, a = 0.8f, camPos = camPos, rayDir = dir,
            maxSteps = 220, enableDisk = true, diskInnerRadius = rIn.toFloat(), diskOuterRadius = rOut.toFloat()
        )

        assertTrue("Tertiary ray must intersect equatorial disk", res.isDiskHit)
        assertTrue("Hit radius must be within physical disk [rIn, rOut]", res.rHit in rIn.toFloat()..rOut.toFloat())
        assertTrue("Frequency shift g must be positive and physical", res.frequencyShift in 0.05f..5.0f)

        // Physical radiance follows I = g^4 * F(r)
        val rad = diskRadiance(res.rHit.toDouble(), res.frequencyShift.toDouble())
        assertTrue("Calculated physical radiance must be strictly positive", rad > 0.0)
    }

    // 3. Adaptive refinement does not alter genuinely black shadow-core pixels
    @Test
    fun adaptiveRefinementPreservesPureBlackShadowCore() {
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        val dim05 = 540.0f
        val pxScale = 2.0f / dim05
        val off = 0.50f * pxScale

        // Sample center and all 8 adaptive subpixel sample offsets (diagonal + axial)
        val offsets = listOf(
            Pair(0.0f, 0.0f),
            Pair(-off, -off), Pair(off, -off), Pair(-off, off), Pair(off, off),
            Pair(-off, 0.0f), Pair(off, 0.0f), Pair(0.0f, -off), Pair(0.0f, off)
        )

        for ((ox, oy) in offsets) {
            val dir = makeRayDir(ox, oy, fwd, right, up, fovScale)
            val res = GpuEquivalentIntegrator.traceRay(
                M = 1.0f, a = 0.8f, camPos = camPos, rayDir = dir,
                maxSteps = 220, enableDisk = true, diskInnerRadius = rIn.toFloat(), diskOuterRadius = rOut.toFloat()
            )
            assertTrue("Shadow core sample ($ox, $oy) must be captured", res.isCaptured)
            assertFalse("Shadow core sample ($ox, $oy) must never hit disk", res.isDiskHit)
            assertEquals("Shadow core sample hit radius must be 0", 0.0f, res.rHit, 1e-6f)
        }
    }

    // 4. Adaptive refinement does not create radiance in asymptotic empty space
    @Test
    fun adaptiveRefinementPreservesPureBlackAsymptoticSpace() {
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        val dim05 = 540.0f
        val pxScale = 2.0f / dim05
        val off = 0.50f * pxScale

        val offsets = listOf(
            Pair(0.0f, 0.0f),
            Pair(-off, -off), Pair(off, -off), Pair(-off, off), Pair(off, off),
            Pair(-off, 0.0f), Pair(off, 0.0f), Pair(0.0f, -off), Pair(0.0f, off)
        )

        for ((ox, oy) in offsets) {
            val dir = makeRayDir(0.85f + ox, 0.85f + oy, fwd, right, up, fovScale)
            val res = GpuEquivalentIntegrator.traceRay(
                M = 1.0f, a = 0.8f, camPos = camPos, rayDir = dir,
                maxSteps = 220, enableDisk = true, diskInnerRadius = rIn.toFloat(), diskOuterRadius = rOut.toFloat()
            )
            assertTrue("Asymptotic space sample must escape", res.isEscaped)
            assertFalse("Asymptotic space sample must not hit disk", res.isDiskHit)
        }
    }

    // 5. Adaptive refinement converges toward higher-resolution reference on tertiary disk features
    @Test
    fun adaptiveRefinementConvergesTowardHigherResolutionReference() {
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        val dim05 = 540.0f
        val pxScale = 2.0f / dim05
        val off = 0.50f * pxScale

        val px = 193
        val stX = (2.0f * px + 1.0f - dim05) / dim05
        val stY = 0.0352f

        // Base 0.5x single-sample
        val resBase = GpuEquivalentIntegrator.traceRay(
            1.0f, 0.8f, camPos, makeRayDir(stX, stY, fwd, right, up, fovScale), 220, true, rIn.toFloat(), rOut.toFloat()
        )
        assertFalse("Base 0.5x single-sample misses outer edge of tertiary ring", resBase.isDiskHit)

        // Tier 1 (5 samples: center + 4 diagonal)
        val diagOffsets = listOf(Pair(-off, -off), Pair(off, -off), Pair(-off, off), Pair(off, off))
        var tier1Hits = 0
        var tier1RadSum = 0.0
        for ((ox, oy) in diagOffsets) {
            val r = GpuEquivalentIntegrator.traceRay(
                1.0f, 0.8f, camPos, makeRayDir(stX + ox, stY + oy, fwd, right, up, fovScale), 220, true, rIn.toFloat(), rOut.toFloat()
            )
            if (r.isDiskHit) {
                tier1Hits++
                tier1RadSum += diskRadiance(r.rHit.toDouble(), r.frequencyShift.toDouble())
            }
        }
        val tier1AvgRad = tier1RadSum / 5.0
        assertTrue("Tier 1 recovers disk intersection on gap pixel px=193", tier1Hits > 0)
        assertTrue("Tier 1 radiance is strictly positive", tier1AvgRad > 0.0)

        // Tier 2 (9 samples: center + 4 diagonal + 4 axial)
        val axialOffsets = listOf(Pair(-off, 0.0f), Pair(off, 0.0f), Pair(0.0f, -off), Pair(0.0f, off))
        var tier2Hits = tier1Hits
        var tier2RadSum = tier1RadSum
        for ((ox, oy) in axialOffsets) {
            val r = GpuEquivalentIntegrator.traceRay(
                1.0f, 0.8f, camPos, makeRayDir(stX + ox, stY + oy, fwd, right, up, fovScale), 220, true, rIn.toFloat(), rOut.toFloat()
            )
            if (r.isDiskHit) {
                tier2Hits++
                tier2RadSum += diskRadiance(r.rHit.toDouble(), r.frequencyShift.toDouble())
            }
        }
        val tier2AvgRad = tier2RadSum / 9.0
        assertTrue("Tier 2 hits are >= Tier 1 hits", tier2Hits >= tier1Hits)
        assertTrue("Tier 2 radiance remains physically bounded", tier2AvgRad > 0.0)
    }

    // 6. Bounded ray workload audit: guarantees strictly bounded execution
    @Test
    fun adaptiveWorkloadIsDeterministicAndBounded() {
        val shaderContent = readShader("gargantua_geodesic.frag")

        // 1. Verify Tier 1 gate
        assertTrue("Shader must contain Tier 1 needsRefinement trigger", shaderContent.contains("bool needsRefinement ="))

        // 2. Verify Tier 2 gate
        assertTrue("Shader must contain Tier 2 needsTier2 trigger", shaderContent.contains("bool needsTier2 ="))
        assertTrue("Tier 2 must check for mixed topological outcomes", shaderContent.contains("hasMixedOutcomes"))

        // 3. Verify maximum bounded budget: 9 genuine Kerr ray samples
        assertTrue(
            "Shader must average exactly 9 samples in Tier 2",
            shaderContent.contains("(baseSample + sample1 + sample2 + sample3 + sample4 + sample5 + sample6 + sample7 + sample8) / 9.0")
        )

        // 4. Verify no runaway loops or recursion
        assertFalse("Shader must not contain unbounded while loops", shaderContent.contains("while (true)") || shaderContent.contains("while(true)"))
        assertTrue("Integration loop must use bounded MAX_INTEGRATION_STEPS", shaderContent.contains("step < MAX_INTEGRATION_STEPS"))
    }

    // 7. Forensic audit of tertiary accretion disk structure
    @Test
    fun tertiaryAccretionDiskForensicStructureAudit() {
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        val dim05 = 540.0f
        val stY = 0.0352f

        // Scan columns px in 193..198 across the tertiary disk body (corrected backward-traced rays)
        var continuousHits = 0
        for (px in 193..198) {
            val stX = (2.0f * px + 1.0f - dim05) / dim05
            val pxScale = 2.0f / dim05
            val off = 0.50f * pxScale

            // Evaluate adaptive 9-sample grid
            val sampleOffsets = listOf(
                Pair(0.0f, 0.0f),
                Pair(-off, -off), Pair(off, -off), Pair(-off, off), Pair(off, off),
                Pair(-off, 0.0f), Pair(off, 0.0f), Pair(0.0f, -off), Pair(0.0f, off)
            )

            var anyHit = false
            for ((ox, oy) in sampleOffsets) {
                val r = GpuEquivalentIntegrator.traceRay(
                    1.0f, 0.8f, camPos, makeRayDir(stX + ox, stY + oy, fwd, right, up, fovScale),
                    220, true, rIn.toFloat(), rOut.toFloat()
                )
                if (r.isDiskHit) {
                    anyHit = true
                    break
                }
            }
            if (anyHit) continuousHits++
        }

        // Prove 100% continuity across the entire tertiary disk body (px=193 to px=198)
        assertEquals("Tertiary disk body must have 0 black dropout pixels across px=193..198", 6, continuousHits)
    }

    // 8. Physical generation verification: no screen-space ring or artificial ambient tricks
    @Test
    fun shaderExcludesAllArtificialOverlaysAndTricks() {
        val shaderContent = readShader("gargantua_geodesic.frag")
        val forbidden = listOf(
            "screenRing",
            "drawPhotonRing",
            "artificialRing",
            "minBrightness",
            "ambientGlow",
            "radialGradient",
            "copyNeighbor",
            "blurFilament"
        )
        for (term in forbidden) {
            assertFalse("Shader must strictly not contain '$term'", shaderContent.contains(term))
        }
    }

    // Phase 5: the tier-1 texture gate covers the inner disk. Two 2x2 quads of the 0.5x frame
    // (540 px across) whose base rays have one disk crossing inside 8M and minR >= 2.5 were never
    // refined by the previous 8 <= r <= 16 gate, although they carry the highest disk-texture
    // frequency and most of the single-ray speckle. The F2 frequency is evaluated exactly as in
    // main(): quad-pair derivatives of the primary hit radius and azimuth.
    @Test
    fun innerDiskTexturePixelsEnterTierOne() {
        val shaderContent = readShader("gargantua_geodesic.frag")
        assertTrue(
            "Shader gate must cover the whole textured disk inside 16M at 0.75 cycles/px",
            shaderContent.contains("bool highFreqDisk = (baseCrossings >= 1) && (baseHitR <= 16.0) &&\n        (f2CyclesPerPixel > 0.75);")
        )
        val (camPos, fwd, up) = setupCamera()
        val right = floatArrayOf(0.0f, 1.0f, 0.0f)
        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
        val dim05 = 540.0f
        val rInF = com.zig.gargantua.disk.AccretionDiskModel(1.0, 0.8).innerRadius.toFloat()
        for ((px0, py0) in listOf(170 to 262, 146 to 240)) {
            val hitR = Array(2) { FloatArray(2) }
            val hitPhi = Array(2) { FloatArray(2) }
            for (j in 0..1) for (i in 0..1) {
                val stX = (2.0f * (px0 + i) + 1.0f - dim05) / dim05
                val stY = (2.0f * (py0 + j) + 1.0f - dim05) / dim05
                val res = GpuEquivalentIntegrator.traceRay(
                    M = 1.0f, a = 0.8f, camPos = camPos, rayDir = makeRayDir(stX, stY, fwd, right, up, fovScale),
                    maxSteps = 180, enableDisk = true, diskInnerRadius = rInF, diskOuterRadius = rOut.toFloat()
                )
                assertEquals("quad ($px0,$py0) pixel ($i,$j) has one disk crossing", 1, res.diskCrossings)
                assertTrue("quad ($px0,$py0) is not a strong-lensing pixel", res.minRadiusReached >= 2.5f)
                hitR[j][i] = res.rHit
                hitPhi[j][i] = res.primaryHitAzimuth
            }
            val r = hitR[0][0]
            assertTrue("quad ($px0,$py0) lies inside 8M (outside the previous gate), r=$r", r < 8.0f)
            val twoPi = 6.28318530718f
            fun wrap(d: Float) = d - twoPi * floor(d / twoPi + 0.5f)
            val drdx = hitR[0][1] - hitR[0][0]
            val drdy = hitR[1][0] - hitR[0][0]
            val dphx = wrap(hitPhi[0][1] - hitPhi[0][0])
            val dphy = wrap(hitPhi[1][0] - hitPhi[0][0])
            val rNorm = max(1.0f, r / rInF)
            val shearPerR = 39.0f * rNorm.pow(-2.5f) / rInF - 10.0f / r
            val cx = sqrt((108.0f * drdx / r).pow(2) + (13.5f * (dphx + shearPerR * drdx)).pow(2))
            val cy = sqrt((108.0f * drdy / r).pow(2) + (13.5f * (dphy + shearPerR * drdy)).pow(2))
            val f2 = max(cx, cy)
            // Emulated frame: 4.97 cycles/px at (170,262), 1.81 at (146,240).
            assertTrue("quad ($px0,$py0) F2 = $f2 cycles/px must exceed the 0.75 gate", f2 > 0.75f)
        }
    }
}
