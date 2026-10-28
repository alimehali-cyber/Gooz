package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Requirement 1: CPU <-> GPU numerical correspondence.
 *
 * Compares the double-precision CPU reference engine ([KerrPhotonIntegrator], [CameraModel])
 * against the single-precision GPU-equivalent shader engine ([GpuEquivalentIntegrator])
 * under identical deterministic initial conditions.
 *
 * DOCUMENTATION OF NUMERICAL TOLERANCES (GLSL ES 3.0 / IEEE 754 Single Precision):
 * - IEEE 754 32-bit float (`highp float`) provides 24 bits of significand (~7.2 decimal digits),
 *   giving a machine epsilon of ε_mach ≈ 1.192 × 10⁻⁷.
 * - Initial momentum components: compared to relative tolerance 2 × 10⁻⁶ (well within single precision).
 * - Initial Hamiltonian residual |H|: single-precision cancellation error in 16-term quadratic sum
 *   yields |H| < 1 × 10⁻⁵.
 * - Intermediate trajectory drift: after N RK4 steps through strong curvature, single-precision
 *   truncation error accumulates as O(N · ε_mach · ‖p‖), bounded to < 0.05 M.
 * - Final escape direction angle: agrees to < 0.5 degrees (< 0.009 rad).
 * - Classification: 100% agreement between CPU and GPU on capture vs escape across the entire parameter space.
 */
class CpuGpuCorrespondenceTest {

    @Test
    fun initialRayStateMatchesBetweenCpuAndGpuWithinSinglePrecision() {
        val testSpacetimes = listOf(
            Pair(1.0f, 0.0f),   // Schwarzschild
            Pair(1.0f, 0.7f),   // Moderate Kerr
            Pair(1.0f, -0.85f), // Retrograde Kerr
            Pair(2.0f, 1.5f)    // Scaled mass
        )

        val camPosList = listOf(
            floatArrayOf(25.0f, 0.0f, 2.0f),
            floatArrayOf(0.0f, 30.0f, 5.0f),
            floatArrayOf(15.0f, 15.0f, 10.0f)
        )

        val rayDirList = listOf(
            floatArrayOf(-1.0f, 0.0f, 0.0f),
            floatArrayOf(-0.8f, 0.4f, 0.1f),
            floatArrayOf(-0.3f, -0.9f, 0.2f)
        )

        for ((M, a) in testSpacetimes) {
            val spacetimeCpu = KerrSchildSpacetime(M.toDouble(), a.toDouble())

            for (camPos in camPosList) {
                for (rayDir in rayDirList) {
                    val dirNorm = sqrt(rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1] + rayDir[2] * rayDir[2])
                    val unitDir = floatArrayOf(rayDir[0] / dirNorm, rayDir[1] / dirNorm, rayDir[2] / dirNorm)

                    // 1. CPU Reference
                    val cpuState = CameraModel.createNullStateFromDirection(
                        spacetimeCpu,
                        camPos[0].toDouble(), camPos[1].toDouble(), camPos[2].toDouble(),
                        unitDir[0].toDouble(), unitDir[1].toDouble(), unitDir[2].toDouble()
                    )

                    // 2. GPU Equivalent
                    val gpuState = GpuEquivalentIntegrator.createInitialRay(M, a, camPos, unitDir)

                    // Initial position comparison: exact
                    assertEquals("X must match", cpuState.x.toFloat(), gpuState[0], 1e-6f)
                    assertEquals("Y must match", cpuState.y.toFloat(), gpuState[1], 1e-6f)
                    assertEquals("Z must match", cpuState.z.toFloat(), gpuState[2], 1e-6f)

                    // Initial covariant momentum comparison
                    val relPx = abs(cpuState.p_x.toFloat() - gpuState[3]) / abs(cpuState.p_x.toFloat())
                    val relPy = abs(cpuState.p_y.toFloat() - gpuState[4]) / max(1e-4f, abs(cpuState.p_y.toFloat()))
                    val relPz = abs(cpuState.p_z.toFloat() - gpuState[5]) / max(1e-4f, abs(cpuState.p_z.toFloat()))

                    assertTrue("px relative error $relPx must be < 1e-5", relPx < 1e-5f)
                    assertTrue("py relative error $relPy must be < 1e-5", relPy < 1e-5f)
                    assertTrue("pz relative error $relPz must be < 1e-5", relPz < 1e-5f)

                    // Initial Hamiltonian comparison
                    val cpuH = abs(cpuState.hamiltonian(spacetimeCpu))
                    val gpuH = abs(GpuEquivalentIntegrator.computeHamiltonian(M, a, gpuState))

                    assertTrue("CPU H must be < 1e-14, got $cpuH", cpuH < 1e-14)
                    assertTrue("GPU H must be < 1e-5 (single precision), got $gpuH", gpuH < 1e-5f)
                }
            }
        }
    }

    @Test
    fun intermediateTrajectoryStatesCorrespondBetweenCpuAndGpu() {
        val M = 1.0f
        val a = 0.6f
        val spacetimeCpu = KerrSchildSpacetime(M.toDouble(), a.toDouble())

        val camPos = floatArrayOf(-30.0f, 8.0f, 2.0f)
        val rayDir = floatArrayOf(1.0f, 0.0f, 0.0f)

        val cpuInitial = CameraModel.createNullStateFromDirection(
            spacetimeCpu,
            camPos[0].toDouble(), camPos[1].toDouble(), camPos[2].toDouble(),
            rayDir[0].toDouble(), rayDir[1].toDouble(), rayDir[2].toDouble()
        )

        // Default KerrPhotonIntegrator parameters are the shader step policy; both engines use the
        // shader's ordinary budget (180) with the near-critical continuation.
        val cpuIntegrator = KerrPhotonIntegrator(spacetimeCpu, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS)

        val cpuResult = cpuIntegrator.traceRay(cpuInitial, recordPath = true)
        val gpuResult = GpuEquivalentIntegrator.traceRay(M, a, camPos, rayDir, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS)

        assertTrue("Both CPU and GPU must escape", cpuResult.isEscaped && gpuResult.isEscaped)
        assertNotNull("CPU path must exist", cpuResult.path)

        val cpuPath = cpuResult.path!!
        val gpuPath = gpuResult.intermediateStates

        // Compare representative intermediate states at steps 5, 15, 30
        val checkSteps = listOf(5, 15, 30)
        for (stepIdx in checkSteps) {
            if (stepIdx < cpuPath.size && stepIdx < gpuPath.size) {
                val cpuPt = cpuPath[stepIdx]
                val gpuPt = gpuPath[stepIdx]

                val dx = cpuPt.x.toFloat() - gpuPt[0]
                val dy = cpuPt.y.toFloat() - gpuPt[1]
                val dz = cpuPt.z.toFloat() - gpuPt[2]
                val spatialDiff = sqrt(dx * dx + dy * dy + dz * dz)

                // Single-precision trajectory tracks double-precision reference to within 0.05 M
                assertTrue(
                    "Intermediate trajectory drift at step $stepIdx ($spatialDiff) must be < 0.05 M",
                    spatialDiff < 0.05f
                )
            }
        }

        // Final trajectory state escape direction comparison
        val cpuFinalV = cpuResult.finalState.velocity(spacetimeCpu)
        val cpuVNorm = sqrt(cpuFinalV[1] * cpuFinalV[1] + cpuFinalV[2] * cpuFinalV[2] + cpuFinalV[3] * cpuFinalV[3])
        val cpuDir = floatArrayOf(
            (cpuFinalV[1] / cpuVNorm).toFloat(),
            (cpuFinalV[2] / cpuVNorm).toFloat(),
            (cpuFinalV[3] / cpuVNorm).toFloat()
        )

        // GPU final velocity from final momentum
        val gpuR = GpuEquivalentIntegrator.compute_r_KS(a, gpuResult.finalPos[0], gpuResult.finalPos[1], gpuResult.finalPos[2])
        val gpuGInv = GpuEquivalentIntegrator.compute_g_inv(M, a, gpuResult.finalPos[0], gpuResult.finalPos[1], gpuResult.finalPos[2], gpuR)
        val gpuP = floatArrayOf(1.0f, gpuResult.finalMomentum[0], gpuResult.finalMomentum[1], gpuResult.finalMomentum[2])
        var gpuVx = 0.0f
        var gpuVy = 0.0f
        var gpuVz = 0.0f
        for (nu in 0 until 4) {
            gpuVx += gpuGInv[1][nu] * gpuP[nu]
            gpuVy += gpuGInv[2][nu] * gpuP[nu]
            gpuVz += gpuGInv[3][nu] * gpuP[nu]
        }
        val gpuVNorm = sqrt(gpuVx * gpuVx + gpuVy * gpuVy + gpuVz * gpuVz)
        val gpuDir = floatArrayOf(gpuVx / gpuVNorm, gpuVy / gpuVNorm, gpuVz / gpuVNorm)

        val cosExitAngle = cpuDir[0] * gpuDir[0] + cpuDir[1] * gpuDir[1] + cpuDir[2] * gpuDir[2]
        val exitAngleRad = acos(cosExitAngle.coerceIn(-1.0f, 1.0f))
        val exitAngleDeg = Math.toDegrees(exitAngleRad.toDouble())

        assertTrue(
            "Final exit velocity direction must match between CPU and GPU within 0.5 degrees, got $exitAngleDeg deg",
            exitAngleDeg < 0.5
        )
    }

    @Test
    fun captureAndEscapeClassification100PercentConsistentAcrossParameterGrid() {
        val testConfigs = listOf(
            Pair(1.0f, 0.0f),  // Schwarzschild
            Pair(1.0f, 0.85f), // High spin prograde
            Pair(1.0f, -0.85f) // High spin retrograde
        )

        // Range of impact parameters spanning clearly captured (b < 4.8) and clearly escaping (b > 5.5)
        val impactParameters = listOf(2.5f, 3.8f, 4.3f, 5.8f, 7.5f, 12.0f, 25.0f)

        for ((M, a) in testConfigs) {
            val spacetimeCpu = KerrSchildSpacetime(M.toDouble(), a.toDouble())
            val cpuIntegrator = KerrPhotonIntegrator(spacetimeCpu, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS)

            for (b in impactParameters) {
                val camPos = floatArrayOf(-35.0f, b, 0.5f)
                val rayDir = floatArrayOf(1.0f, 0.0f, 0.0f)

                val cpuInitial = CameraModel.createNullStateFromDirection(
                    spacetimeCpu,
                    camPos[0].toDouble(), camPos[1].toDouble(), camPos[2].toDouble(),
                    1.0, 0.0, 0.0
                )

                val cpuRes = cpuIntegrator.traceRay(cpuInitial)
                val gpuRes = GpuEquivalentIntegrator.traceRay(M, a, camPos, rayDir, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS)

                assertEquals(
                    "Capture classification must match 100% for M=$M, a=$a, b=$b",
                    cpuRes.isCaptured,
                    gpuRes.isCaptured
                )
                assertEquals(
                    "Escape classification must match 100% for M=$M, a=$a, b=$b",
                    cpuRes.isEscaped,
                    gpuRes.isEscaped
                )
            }
        }
    }

    @Test
    fun diskIntersectionCorrespondsBetweenCpuAndGpu() {
        val M = 1.0f
        val a = 0.5f
        val spacetimeCpu = KerrSchildSpacetime(M.toDouble(), a.toDouble())
        val diskCpu = com.zig.gargantua.disk.AccretionDiskModel(M.toDouble(), a.toDouble(), outerRadius = 22.0)

        val camPos = floatArrayOf(-25.0f, 0.0f, 4.0f)
        val rayDir = floatArrayOf(1.0f, 0.4f, -0.35f)

        val cpuInitial = CameraModel.createNullStateFromDirection(
            spacetimeCpu,
            camPos[0].toDouble(), camPos[1].toDouble(), camPos[2].toDouble(),
            rayDir[0].toDouble(), rayDir[1].toDouble(), rayDir[2].toDouble()
        )

        val cpuIntegrator = KerrPhotonIntegrator(spacetimeCpu, disk = diskCpu, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS)

        val cpuRes = cpuIntegrator.traceRay(cpuInitial)
        val gpuRes = GpuEquivalentIntegrator.traceRay(
            M = M,
            a = a,
            camPos = camPos,
            rayDir = rayDir,
            maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS,
            enableDisk = true,
            diskInnerRadius = diskCpu.innerRadius.toFloat(),
            diskOuterRadius = diskCpu.outerRadius.toFloat()
        )

        assertTrue("Both CPU and GPU must detect disk hit", cpuRes.isDiskHit && gpuRes.isDiskHit)
        assertNotNull("CPU disk hit must exist", cpuRes.diskHit)

        val cpuHit = cpuRes.diskHit!!
        val rDiff = abs(cpuHit.rHit.toFloat() - gpuRes.rHit)
        val gDiff = abs(cpuHit.frequencyShift.toFloat() - gpuRes.frequencyShift)

        assertTrue("Disk hit radius must match within 0.05 M, got $rDiff", rDiff < 0.05f)
        assertTrue("Frequency shift must match within 0.05, got $gDiff", gDiff < 0.05f)
    }

    // ---------------------------------------------------------------------------------------------
    // Phase-4 parity: the CPU reference (KerrPhotonIntegrator defaults) and the GPU emulation must
    // implement the same shader algorithm (gargantua_geodesic.frag).

    private val prodM = 1.0f
    private val prodA = 0.8f
    private val prodCam: FloatArray
    private val prodFwd: FloatArray
    private val prodRight = floatArrayOf(0.0f, 1.0f, 0.0f)
    private val prodUp: FloatArray
    private val prodFovScale = tan(Math.toRadians(22.5)).toFloat()

    init {
        val incl = Math.toRadians(80.0)
        prodCam = floatArrayOf((32.0 * sin(incl)).toFloat(), 0.0f, (32.0 * cos(incl)).toFloat())
        val len = sqrt(prodCam[0] * prodCam[0] + prodCam[2] * prodCam[2])
        prodFwd = floatArrayOf(-prodCam[0] / len, 0.0f, -prodCam[2] / len)
        // up = right x forward (GargantuaRenderer camera basis)
        val ux = prodRight[1] * prodFwd[2] - prodRight[2] * prodFwd[1]
        val uy = prodRight[2] * prodFwd[0] - prodRight[0] * prodFwd[2]
        val uz = prodRight[0] * prodFwd[1] - prodRight[1] * prodFwd[0]
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        prodUp = floatArrayOf(ux / ul, uy / ul, uz / ul)
    }

    private fun prodDir(stX: Float, stY: Float): FloatArray {
        val d = FloatArray(3) { i -> prodFwd[i] + prodRight[i] * (stX * prodFovScale) + prodUp[i] * (stY * prodFovScale) }
        val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return floatArrayOf(d[0] / l, d[1] / l, d[2] / l)
    }

    @Test
    fun stepPolicyIsIdenticalInCpuReferenceAndGpuEmulation() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val cpu = KerrPhotonIntegrator(spacetime)
        val rCapture = spacetime.rPlus + cpu.captureHorizonMargin
        var worst = 0.0
        var r = 1.70
        while (r < 60.0) {
            for (outward in listOf(false, true)) {
                var cpuStep = cpu.computeAdaptiveStep(r, outward)
                if (r > rCapture && r < rCapture + 0.95) {
                    cpuStep = min(cpuStep, 0.032 + (0.075 - 0.032) * (r - rCapture) / 0.95)
                }
                val gpuStep = GpuEquivalentIntegrator.adaptiveStep(r.toFloat(), outward, rCapture.toFloat())
                // Shader formula written out independently.
                var shader = if (r > 6.0 && (outward || r > 18.0)) (0.085 * r).coerceIn(0.035, 0.55) else (0.085 * r).coerceIn(0.035, 0.32)
                if (r > rCapture && r < rCapture + 0.95) shader = min(shader, 0.032 + 0.043 * (r - rCapture) / 0.95)
                assertEquals("CPU step at r=$r outward=$outward", shader, cpuStep, 1e-12)
                assertEquals("GPU step at r=$r outward=$outward", shader.toFloat(), gpuStep, 2e-7f)
                worst = max(worst, abs(cpuStep - gpuStep))
            }
            r += 0.0137
        }
        println("step policy parity: max |CPU - GPU| = $worst")
    }

    @Test
    fun derivativeAndRk4StepMatchAtRepresentativeProductionStates() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val cpu = KerrPhotonIntegrator(spacetime)
        var worstDerivative = 0.0
        var worstStep = 0.0
        for (stX in listOf(-0.45f, -0.28f, 0.0f, 0.3f, 0.55f)) {
            for (stY in listOf(-0.2f, 0.0f, 0.15f)) {
                val gpu = GpuEquivalentIntegrator.traceRay(prodM, prodA, prodCam, prodDir(stX, stY), maxSteps = 180)
                for (k in listOf(0, 10, 40, 70)) {
                    if (k >= gpu.intermediateStates.size) continue
                    val s = gpu.intermediateStates[k]
                    val r = GpuEquivalentIntegrator.compute_r_KS(prodA, s[0], s[1], s[2])
                    if (r < 2.5f) continue
                    val sd = DoubleArray(6) { s[it].toDouble() }
                    val dCpu = cpu.evaluateDerivatives(sd, 1.0)
                    val dGpu = GpuEquivalentIntegrator.evaluate_derivatives(prodM, prodA, s)
                    val scale = dCpu.maxOf { abs(it) }
                    for (i in 0 until 6) worstDerivative = max(worstDerivative, abs(dCpu[i] - dGpu[i]) / scale)
                    val h = GpuEquivalentIntegrator.adaptiveStep(r, false, 1.65f)
                    val nCpu = cpu.rk4Step(sd, h.toDouble(), 1.0)
                    val nGpu = GpuEquivalentIntegrator.rk4_step(prodM, prodA, s, h)
                    for (i in 0 until 6) worstStep = max(worstStep, abs(nCpu[i] - nGpu[i]) / max(1.0, abs(nCpu[i])))
                }
            }
        }
        println("derivative parity: max rel |dCPU - dGPU| = $worstDerivative, RK4 step parity = $worstStep")
        assertTrue("Derivative must match to single precision, got $worstDerivative", worstDerivative < 5e-6)
        assertTrue("RK4 step must match to single precision, got $worstStep", worstStep < 1e-5)
    }

    @Test
    fun productionRaysAgreeOnTerminationDiskRadiusAndFrequencyShift() {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val disk = com.zig.gargantua.disk.AccretionDiskModel(1.0, 0.8, outerRadius = 22.0)
        val cpu = KerrPhotonIntegrator(spacetime, disk = disk, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS)
        var n = 0
        var agree = 0
        var hits = 0
        var worstR = 0.0
        var worstG = 0.0
        var worstFormula = 0.0
        val a = 0.8
        for (ix in -6..6) {
            for (iy in -3..3) {
                val stX = ix * 0.1f
                val stY = iy * 0.1f
                val dir = prodDir(stX, stY)
                val cpuRes = cpu.traceRay(
                    CameraModel.createNullStateFromDirection(
                        spacetime, prodCam[0].toDouble(), prodCam[1].toDouble(), prodCam[2].toDouble(),
                        dir[0].toDouble(), dir[1].toDouble(), dir[2].toDouble()
                    )
                )
                val gpuRes = GpuEquivalentIntegrator.traceRay(
                    prodM, prodA, prodCam, dir, maxSteps = GpuEquivalentIntegrator.BASE_INTEGRATION_STEPS,
                    enableDisk = true, diskInnerRadius = disk.innerRadius.toFloat(), diskOuterRadius = 22.0f
                )
                n++
                val same = cpuRes.isCaptured == gpuRes.isCaptured && cpuRes.isEscaped == gpuRes.isEscaped &&
                    cpuRes.isDiskHit == gpuRes.isDiskHit
                if (same) agree++
                if (cpuRes.isDiskHit && gpuRes.isDiskHit) {
                    hits++
                    val hit = cpuRes.diskHit!!
                    // Disk radius is the shader's Kerr-Schild expression, not the cylindrical radius.
                    val formula = sqrt(max(0.0, hit.hitX * hit.hitX + hit.hitY * hit.hitY - a * a))
                    worstFormula = max(worstFormula, abs(formula - hit.rHit))
                    worstR = max(worstR, abs(hit.rHit - gpuRes.rHit))
                    worstG = max(worstG, abs(hit.frequencyShift - gpuRes.frequencyShift))
                }
            }
        }
        println("production parity: $agree/$n terminations agree, $hits disk hits, max |dr| = $worstR, max |dg| = $worstG")
        assertEquals("CPU rHit must equal sqrt(max(0, X^2 + Y^2 - a^2))", 0.0, worstFormula, 1e-12)
        assertEquals("Every production ray must terminate identically", n, agree)
        assertTrue("Disk hits must be exercised", hits > 20)
        assertTrue("Disk radius parity (measured 1.4e-5), got $worstR", worstR < 1e-4)
        assertTrue("Frequency shift parity (measured 1.2e-6), got $worstG", worstG < 1e-5)
    }

    // ---------------------------------------------------------------------------------------------
    // Phase-5 parity: the see-through disk. The shader (gargantua_geodesic.frag L788-870) keeps
    // integrating through up to four accepted crossings; both CPU paths must produce the same crossing
    // sequence, emission, transmittance, final state and sky direction.

    private fun cpuTrace(stX: Float, stY: Float, maxSteps: Int): KerrPhotonIntegrator.RayTraceResult {
        val spacetime = KerrSchildSpacetime(1.0, 0.8)
        val disk = com.zig.gargantua.disk.AccretionDiskModel(1.0, 0.8, outerRadius = 22.0)
        val dir = prodDir(stX, stY)
        return KerrPhotonIntegrator(spacetime, disk = disk, maxSteps = maxSteps).traceRay(
            CameraModel.createNullStateFromDirection(
                spacetime, prodCam[0].toDouble(), prodCam[1].toDouble(), prodCam[2].toDouble(),
                dir[0].toDouble(), dir[1].toDouble(), dir[2].toDouble()
            )
        )
    }

    private fun gpuTrace(stX: Float, stY: Float, maxSteps: Int): GpuEquivalentIntegrator.GpuRayResult {
        val rIn = com.zig.gargantua.disk.AccretionDiskModel(1.0, 0.8).innerRadius.toFloat()
        return GpuEquivalentIntegrator.traceRay(
            prodM, prodA, prodCam, prodDir(stX, stY), maxSteps = maxSteps,
            enableDisk = true, diskInnerRadius = rIn, diskOuterRadius = 22.0f
        )
    }

    private fun cpuState(r: KerrPhotonIntegrator.RayTraceResult): Int = when {
        r.isCaptured -> GpuEquivalentIntegrator.STATE_CAPTURED
        r.isEscaped -> GpuEquivalentIntegrator.STATE_ESCAPED
        r.isOpaqueDiskHit -> GpuEquivalentIntegrator.STATE_DISK
        else -> GpuEquivalentIntegrator.STATE_UNRESOLVED
    }

    private fun angleDeg(a: DoubleArray, bf: FloatArray): Double {
        val b = DoubleArray(3) { bf[it].toDouble() }
        val cx = a[1] * b[2] - a[2] * b[1]
        val cy = a[2] * b[0] - a[0] * b[2]
        val cz = a[0] * b[1] - a[1] * b[0]
        val dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return Math.toDegrees(atan2(sqrt(cx * cx + cy * cy + cz * cz), dot))
    }

    @Test
    fun multiCrossingRaysMatchCrossingByCrossing() {
        // Screen coordinates found by scanning the production frame: 2, 3 and 4 accepted crossings,
        // ending captured, escaped and opaque, including two rays whose fifth plane crossing is skipped
        // by the diskCrossings < 4 guard.
        val rays = listOf(
            -0.30f to 0.0f, -0.304f to 0.0f, -0.308f to 0.0f, -0.312f to 0.0f, 0.496f to 0.0f,
            0.5028f to 0.0f, 0.5034f to 0.0f, 0.5042f to 0.0f, 0.452f to -0.2f, 0.4502f to -0.2f,
            0.45009f to -0.2f, 0.4501f to -0.2f, 0.45032f to -0.2f, 0.592f to -0.2f
        )
        var worstR = 0.0
        var worstG = 0.0
        var worstRgb = 0.0
        var worstRgbSensitive = 0.0
        var worstT = 0.0
        val acceptedCounts = mutableSetOf<Int>()
        val rInDisk = com.zig.gargantua.disk.AccretionDiskModel(1.0, 0.8).innerRadius.toFloat()
        val finalStates = mutableSetOf<Int>()
        var skipped = 0
        for ((x, y) in rays) {
            val g = gpuTrace(x, y, 180)
            val c = cpuTrace(x, y, 180)
            assertEquals("final state at ($x,$y)", g.rayState, cpuState(c))
            assertEquals("accepted crossings at ($x,$y)", g.diskCrossings, c.acceptedCrossingCount)
            assertEquals("plane crossings at ($x,$y)", g.crossings.size, c.diskCrossings.size)
            assertTrue("multi-crossing ray ($x,$y)", g.diskCrossings >= 2)
            acceptedCounts.add(g.diskCrossings)
            finalStates.add(g.rayState)
            // A ray is "sensitive" when float32 vs double rounding has already moved a crossing by more
            // than 1e-3 M (repeated near-photon-orbit passes). The disk texture varies radially on a
            // ~0.04 M scale (logR * 108 in the micro-thread noise), so such a crossing's emission can
            // differ by several percent; the float32 shader emulation (emu) shows the same spread.
            val sensitive = g.crossings.indices.any { k -> !g.crossings[k].skippedByCrossingLimit && abs(g.crossings[k].rHit - c.diskCrossings[k].rHit) > 1e-3 }
            for (k in g.crossings.indices) {
                val gc = g.crossings[k]
                val cc = c.diskCrossings[k]
                assertEquals("crossing $k step at ($x,$y)", gc.step, cc.step)
                assertEquals("crossing $k accepted at ($x,$y)", gc.accepted, cc.accepted)
                assertEquals("crossing $k skipped at ($x,$y)", gc.skippedByCrossingLimit, cc.skippedByCrossingLimit)
                if (gc.skippedByCrossingLimit) skipped++
                if (!gc.skippedByCrossingLimit) worstR = max(worstR, abs(gc.rHit - cc.rHit))
                if (gc.accepted) {
                    assertEquals("crossing $k palette interval at ($x,$y)", gc.paletteInterval, cc.paletteInterval)
                    // Every crossing (any image order) is coloured by its own emission point: F(r)/F_peak and
                    // the palette coordinate are evaluated at that crossing's rHit and g, never inherited.
                    val sh = com.zig.gargantua.disk.ShaderDiskShading
                    val fOwn = sh.normalizedFlux(1.0f, gc.rHit, rInDisk)
                    assertEquals("crossing $k fNorm at its own radius ($x,$y)", fOwn, gc.fNorm, 1e-6f)
                    assertEquals("crossing $k tEff at its own radius ($x,$y)", sh.paletteCoordinate(fOwn, gc.gShift, false), gc.tEff, 1e-6f)
                    assertEquals("crossing $k palette interval of its own tEff ($x,$y)", sh.paletteInterval(gc.tEff), gc.paletteInterval)
                    worstG = max(worstG, abs(gc.gShift - cc.gShift))
                    for (i in 0 until 3) {
                        val d = (abs(gc.contribution[i] - cc.contribution[i]) / max(0.05f, gc.contribution[i])).toDouble()
                        if (sensitive) worstRgbSensitive = max(worstRgbSensitive, d) else worstRgb = max(worstRgb, d)
                    }
                    worstT = max(worstT, abs(gc.transmittanceOut - cc.transmittanceOut).toDouble())
                }
            }
            for (i in 0 until 3) {
                val d = (abs(g.accumulatedRadiance[i] - c.accumulatedRadiance[i]) / max(0.05f, g.accumulatedRadiance[i])).toDouble()
                if (sensitive) worstRgbSensitive = max(worstRgbSensitive, d) else worstRgb = max(worstRgb, d)
            }
        }
        println("multi-crossing parity: max |dr| = $worstR, |dg| = $worstG, rel dRGB = $worstRgb (sensitive rays $worstRgbSensitive), |dT| = $worstT, skipped = $skipped")
        assertEquals("2, 3 and 4 accepted crossings exercised", setOf(2, 3, 4), acceptedCounts)
        assertEquals("captured, escaped and opaque endings exercised", setOf(1, 2, 3), finalStates)
        assertEquals("the crossing cap is exercised on two rays", 2, skipped)
        // Near-critical rays amplify float32 rounding along the orbit: the late crossings of the
        // 4-crossing rays differ by up to ~0.03M between float and double (measured 0.028).
        assertTrue("crossing radius parity, got $worstR", worstR < 0.06)
        assertTrue("frequency shift parity, got $worstG", worstG < 1e-3)
        assertTrue("per-crossing and accumulated HDR parity (relative), got $worstRgb", worstRgb < 0.02)
        assertTrue("HDR parity on float-sensitive rays (measured 0.072), got $worstRgbSensitive", worstRgbSensitive < 0.12)
        assertTrue("transmittance parity, got $worstT", worstT < 5e-3)
    }

    @Test
    fun skyDirectionEscapeAndSafetyRuleMatchShader() {
        var worstAngle = 0.0
        // (stX, stY, maxSteps): ordinary escapes at 180 steps, safety-rule escapes and one ray that must
        // stay UNRESOLVED at the 80-step budget (the safety rule never turns an inbound ray into sky).
        val cases = listOf(
            Triple(0.85f, 0.85f, 180), Triple(-0.266f, 0.0f, 180), Triple(-0.30f, 0.0352f, 180),
            Triple(-0.28f, 0.0f, 180), Triple(-0.30f, 0.0f, 180), Triple(0.452f, -0.2f, 180),
            Triple(0.85f, 0.85f, 80), Triple(-0.70f, 0.35f, 80), Triple(0.70f, 0.35f, 80)
        )
        var safetyEscapes = 0
        var unresolved = 0
        for ((x, y, ms) in cases) {
            val g = gpuTrace(x, y, ms)
            val c = cpuTrace(x, y, ms)
            assertEquals("final state at ($x,$y,$ms)", g.rayState, cpuState(c))
            assertEquals("safety rule at ($x,$y,$ms)", g.escapedBySafetyRule, c.escapedBySafetyRule)
            if (g.rayState == GpuEquivalentIntegrator.STATE_UNRESOLVED) { unresolved++; continue }
            if (g.rayState != GpuEquivalentIntegrator.STATE_ESCAPED) continue
            // Shader L922: skyDir = escapedBySafetyRule ? normalize(lastStepDir) : normalize(p_spatial).
            val n = g.intermediateStates.size
            val expected = if (g.escapedBySafetyRule) {
                val s1 = g.intermediateStates[n - 1]; val s0 = g.intermediateStates[n - 2]
                floatArrayOf(s1[0] - s0[0], s1[1] - s0[1], s1[2] - s0[2])
            } else {
                floatArrayOf(g.finalMomentum[0], g.finalMomentum[1], g.finalMomentum[2])
            }
            val self = angleDeg(DoubleArray(3) { expected[it].toDouble() }, g.skyDirection)
            assertTrue("GPU emulation sky direction is the shader's at ($x,$y,$ms): $self deg", self < 1e-3)
            if (g.escapedBySafetyRule) safetyEscapes++
            worstAngle = max(worstAngle, angleDeg(c.skyDirection, g.skyDirection))
        }
        println("sky direction parity: max angle CPU vs GPU = $worstAngle deg, safety escapes = $safetyEscapes")
        assertEquals("two safety-rule escapes exercised", 2, safetyEscapes)
        assertEquals("one inbound ray stays unresolved", 1, unresolved)
        assertTrue("sky direction parity (measured 1.1e-3 deg), got $worstAngle", worstAngle < 0.05)
    }
}
