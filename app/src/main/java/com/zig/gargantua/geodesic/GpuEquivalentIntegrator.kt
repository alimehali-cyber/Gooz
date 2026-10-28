package com.zig.gargantua.geodesic

import kotlin.math.*

/**
 * Deterministic software emulation of the GPU GLSL ES 3.0 shader pipeline [gargantua_geodesic.frag].
 *
 * Implements the exact same IEEE 754 single-precision (Float / highp float) mathematical
 * operations as executed on the GPU, enabling deterministic bit-level validation and
 * numerical correspondence checks against the CPU double-precision reference.
 *
 * EXACT CORRESPONDENCE WITH GLSL SHADER:
 * 1. Single-precision 32-bit float arithmetic matching GLSL highp float precision.
 * 2. Kerr-Schild Cartesian metric inversion g^μν = η^μν - 2H l^μ l^ν.
 * 3. Exact analytical spatial metric derivatives ∂_i g^μν.
 * 4. Pinhole camera ray null momentum solving: A_w w² + 2 B_w w + C_w = 0, past-directed root
 *    w = (-B_w + √D_w)/A_w (backward trace), covector normalised to p_0 = +1.
 * 5. 4th-order Runge-Kutta (RK4) integration with the shader step policy: Δλ = clamp(0.085·r, 0.035, 0.32),
 *    or clamp(0.085·r, 0.035, 0.55) when r > 6 and (moving outward or r > 18), limited to
 *    mix(0.032, 0.075, (r - r_capture)/0.95) inside the capture zone. The right-hand side is the shader's
 *    factored evaluate_rhs (not the matrix form), so the float operations follow the shader.
 * 6. Horizon capture threshold r ≤ r_+ + 0.05; escape when moving outward and r ≥ max(50, r_cam + 15)
 *    (or r ≥ diskOuterRadius with the disk enabled); ordinary budget clamp(maxSteps, 40, 180);
 *    near-critical rays (minR ≤ r_photonShellOuter + 0.5) continue up to 1500 steps; the post-loop
 *    safety rule classifies an unresolved ray moving outward beyond r_photonShellOuter as escaped.
 * 7. Disk crossing: rHit = sqrt(max(0, X² + Y² - a²)), tau clamped to [0, 1], g clamped to [0.05, 5].
 *    The disk is see-through, as in the shader: an accepted crossing (at most four) adds its emission
 *    ([com.zig.gargantua.disk.ShaderDiskShading]) and attenuates the transmittance, and the same ray keeps
 *    integrating; only a transmittance that falls to 0 ends the ray (rayState 3). [GpuRayResult.isDiskHit]
 *    means "at least one accepted crossing" (the shader's primaryHitRadius / baseHitR), and
 *    [GpuRayResult.rHit] / [GpuRayResult.frequencyShift] belong to that first accepted crossing.
 * 8. Loop order per iteration, identical to traceRaySample: budget check -> r, minR, movingOutward, prevR
 *    -> capture -> escape -> step size -> RK4 -> lastStepDir -> plane crossing / acceptance / emission /
 *    opacity; after the loop the safety rule applies only to rayState 0; the sky is sampled along
 *    normalize(lastStepDir) for a safety-rule escape and along normalize(p_spatial) otherwise.
 *    Not emulated: the relativistic test object (u_EnableObject) and the sky colour itself.
 */
object GpuEquivalentIntegrator {

    data class GpuRayResult(
        val isCaptured: Boolean,
        val isEscaped: Boolean,
        val isDiskHit: Boolean = false,
        val stepsTaken: Int,
        val finalPos: FloatArray,      // [X, Y, Z]
        val finalMomentum: FloatArray, // [pX, pY, pZ]
        val minRadiusReached: Float,
        val maxHamiltonianResidual: Float,
        val intermediateStates: List<FloatArray>, // List of 6D states [X, Y, Z, pX, pY, pZ]
        val frequencyShift: Float = 1.0f,
        val rHit: Float = 0.0f,
        val escapedBySafetyRule: Boolean = false,
        /** Shader rayState: 0 unresolved, 1 captured, 2 escaped, 3 opaque disk. */
        val rayState: Int = 0,
        /** Shader diskCrossings: accepted crossings (at most 4). */
        val diskCrossings: Int = 0,
        /** Every equatorial-plane crossing processed by the loop, in order. */
        val crossings: List<GpuCrossing> = emptyList(),
        /** Shader accumDiskRadiance. */
        val accumulatedRadiance: FloatArray = FloatArray(3),
        /** Shader diskTransmittance at the end of the ray. */
        val transmittance: Float = 1.0f,
        /** Direction the shader samples the sky with: normalize(lastStepDir) after the safety rule, else normalize(p_spatial). */
        val skyDirection: FloatArray = FloatArray(3),
        val finalRadius: Float = 0.0f,
        val primaryHitAzimuth: Float = 0.0f
    ) {
        /** Shader rayState == 3: the see-through disk became fully opaque and ended the ray. */
        val isOpaqueDiskHit: Boolean get() = rayState == 3
    }

    fun compute_r_KS(a: Float, X: Float, Y: Float, Z: Float): Float {
        val R2 = X * X + Y * Y + Z * Z
        val a2 = a * a
        val z2 = Z * Z
        val diff = R2 - a2
        val rad = sqrt(max(0.0f, diff * diff + 4.0f * a2 * z2))
        val r2 = 0.5f * (diff + rad)
        return sqrt(max(0.0f, r2))
    }

    /** 4x4 covariant metric g_μν in single precision. */
    fun compute_g_lower(M: Float, a: Float, X: Float, Y: Float, Z: Float, r: Float): Array<FloatArray> {
        val r2 = r * r
        val a2 = a * a
        val z2 = Z * Z
        val denom = r2 * r2 + a2 * z2
        val H = if (denom > 1e-12f) (M * r * r2) / denom else 0.0f
        val twoH = 2.0f * H

        val denomXY = r2 + a2
        val lx = if (denomXY > 1e-12f) (r * X + a * Y) / denomXY else 0.0f
        val ly = if (denomXY > 1e-12f) (r * Y - a * X) / denomXY else 0.0f
        val lz = if (r > 1e-12f) Z / r else 0.0f

        val l = floatArrayOf(1.0f, lx, ly, lz)
        val g = Array(4) { FloatArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var eta = 0.0f
                if (i == j) {
                    eta = if (i == 0) -1.0f else 1.0f
                }
                g[i][j] = eta + twoH * l[i] * l[j]
            }
        }
        return g
    }

    /** 4x4 contravariant metric g^μν in single precision. */
    fun compute_g_inv(M: Float, a: Float, X: Float, Y: Float, Z: Float, r: Float): Array<FloatArray> {
        val r2 = r * r
        val a2 = a * a
        val z2 = Z * Z
        val denom = r2 * r2 + a2 * z2
        val H = if (denom > 1e-12f) (M * r * r2) / denom else 0.0f
        val twoH = 2.0f * H

        val denomXY = r2 + a2
        val lx = if (denomXY > 1e-12f) (r * X + a * Y) / denomXY else 0.0f
        val ly = if (denomXY > 1e-12f) (r * Y - a * X) / denomXY else 0.0f
        val lz = if (r > 1e-12f) Z / r else 0.0f

        val lContra = floatArrayOf(-1.0f, lx, ly, lz)
        val gInv = Array(4) { FloatArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var eta = 0.0f
                if (i == j) {
                    eta = if (i == 0) -1.0f else 1.0f
                }
                gInv[i][j] = eta - twoH * lContra[i] * lContra[j]
            }
        }
        return gInv
    }

    /** Exact analytical spatial derivatives of g^μν with respect to X, Y, Z. */
    fun compute_dg_inv(
        M: Float,
        a: Float,
        X: Float,
        Y: Float,
        Z: Float,
        r: Float
    ): Triple<Array<FloatArray>, Array<FloatArray>, Array<FloatArray>> {
        val r2 = r * r
        val r3 = r2 * r
        val r4 = r2 * r2
        val a2 = a * a
        val z2 = Z * Z
        val denomSigma = r4 + a2 * z2
        val denomSigma2 = denomSigma * denomSigma
        val H = if (denomSigma > 1e-12f) (M * r3) / denomSigma else 0.0f

        val dr_dX = if (denomSigma > 1e-12f) (r3 * X) / denomSigma else 0.0f
        val dr_dY = if (denomSigma > 1e-12f) (r3 * Y) / denomSigma else 0.0f
        val dr_dZ = if (denomSigma > 1e-12f) (Z * r * (r2 + a2)) / denomSigma else 0.0f

        val dH_dr = if (denomSigma2 > 1e-12f) M * r2 * (3.0f * a2 * z2 - r4) / denomSigma2 else 0.0f
        val dH_dZ_expl = if (denomSigma2 > 1e-12f) -2.0f * M * a2 * r3 * Z / denomSigma2 else 0.0f

        val dH_dX = dH_dr * dr_dX
        val dH_dY = dH_dr * dr_dY
        val dH_dZ = dH_dr * dr_dZ + dH_dZ_expl

        val denomV = r2 + a2
        val denomV2 = denomV * denomV

        val lx = if (denomV > 1e-12f) (r * X + a * Y) / denomV else 0.0f
        val ly = if (denomV > 1e-12f) (r * Y - a * X) / denomV else 0.0f
        val lz = if (r > 1e-12f) Z / r else 0.0f
        val l = floatArrayOf(-1.0f, lx, ly, lz)

        fun diff_l(dr_di: Float, isX: Boolean, isY: Boolean, isZ: Boolean): FloatArray {
            val dv = 2.0f * r * dr_di
            val duX = dr_di * X + (if (isX) r else 0.0f) + (if (isY) a else 0.0f)
            val dlx = (duX * denomV - (r * X + a * Y) * dv) / denomV2

            val duY = dr_di * Y + (if (isY) r else 0.0f) - (if (isX) a else 0.0f)
            val dly = (duY * denomV - (r * Y - a * X) * dv) / denomV2

            val dlz = if (r > 1e-12f) ((if (isZ) 1.0f else 0.0f) * r - Z * dr_di) / r2 else 0.0f

            return floatArrayOf(0.0f, dlx, dly, dlz)
        }

        val dl_dX = diff_l(dr_dX, isX = true, isY = false, isZ = false)
        val dl_dY = diff_l(dr_dY, isX = false, isY = true, isZ = false)
        val dl_dZ = diff_l(dr_dZ, isX = false, isY = false, isZ = true)

        fun assemble_dg(dH_di: Float, dl_di: FloatArray): Array<FloatArray> {
            val res = Array(4) { FloatArray(4) }
            for (i in 0 until 4) {
                for (j in 0 until 4) {
                    res[i][j] = -2.0f * dH_di * l[i] * l[j] - 2.0f * H * (dl_di[i] * l[j] + l[i] * dl_di[j])
                }
            }
            return res
        }

        return Triple(assemble_dg(dH_dX, dl_dX), assemble_dg(dH_dY, dl_dY), assemble_dg(dH_dZ, dl_dZ))
    }

    /**
     * Right-hand side of Hamilton's equations for the 6D state, written exactly as the shader's
     * factored evaluate_rhs (p_0 = +1, Lp = -1 + l·p). [compute_dg_inv] keeps the matrix form for tests.
     */
    fun evaluate_derivatives(M: Float, a: Float, state: FloatArray): FloatArray {
        val x = state[0]
        val y = state[1]
        val z = state[2]
        val px = state[3]
        val py = state[4]
        val pz = state[5]
        val r = compute_r_KS(a, x, y, z)
        val a2 = a * a
        val z2 = z * z
        val r2 = r * r
        val r3 = r2 * r
        val r4 = r2 * r2
        var denomSigma = r4 + a2 * z2
        if (denomSigma < 1.0e-20f) denomSigma = 1.0e-20f

        val drdX = (r3 * x) / denomSigma
        val drdY = (r3 * y) / denomSigma
        val drdZ = (z * r * (r2 + a2)) / denomSigma

        val h = (M * r3) / denomSigma
        val denomSigma2 = denomSigma * denomSigma
        val dHdr = M * r2 * (3.0f * a2 * z2 - r4) / denomSigma2
        val dHdX = dHdr * drdX
        val dHdY = dHdr * drdY
        val dHdZ = dHdr * drdZ - (2.0f * M * a2 * r3 * z) / denomSigma2

        val denomV = r2 + a2
        val denomV2 = denomV * denomV
        val lx = if (denomV > 1.0e-12f) (r * x + a * y) / denomV else 0.0f
        val ly = if (denomV > 1.0e-12f) (r * y - a * x) / denomV else 0.0f
        val lz = if (r > 1.0e-7f) z / r else 0.0f

        val lp = -1.0f + (lx * px + ly * py + lz * pz)
        val twoH = 2.0f * h

        val dvX = 2.0f * r * drdX
        val duXX = drdX * x + r
        val dlXX = (duXX * denomV - (r * x + a * y) * dvX) / denomV2
        val duYX = drdX * y - a
        val dlYX = (duYX * denomV - (r * y - a * x) * dvX) / denomV2
        val dlZX = if (r > 1.0e-7f) (-z * drdX) / r2 else 0.0f
        val dlpX = dlXX * px + dlYX * py + dlZX * pz

        val dvY = 2.0f * r * drdY
        val duXY = drdY * x + a
        val dlXY = (duXY * denomV - (r * x + a * y) * dvY) / denomV2
        val duYY = drdY * y + r
        val dlYY = (duYY * denomV - (r * y - a * x) * dvY) / denomV2
        val dlZY = if (r > 1.0e-7f) (-z * drdY) / r2 else 0.0f
        val dlpY = dlXY * px + dlYY * py + dlZY * pz

        val dvZ = 2.0f * r * drdZ
        val duXZ = drdZ * x
        val dlXZ = (duXZ * denomV - (r * x + a * y) * dvZ) / denomV2
        val duYZ = drdZ * y
        val dlYZ = (duYZ * denomV - (r * y - a * x) * dvZ) / denomV2
        val dlZZ = if (r > 1.0e-7f) (r - z * drdZ) / r2 else 0.0f
        val dlpZ = dlXZ * px + dlYZ * py + dlZZ * pz

        return floatArrayOf(
            px - (twoH * lp) * lx,
            py - (twoH * lp) * ly,
            pz - (twoH * lp) * lz,
            lp * (dHdX * lp + twoH * dlpX),
            lp * (dHdY * lp + twoH * dlpY),
            lp * (dHdZ * lp + twoH * dlpZ)
        )
    }

    fun rk4_step(M: Float, a: Float, state: FloatArray, dlambda: Float): FloatArray {
        val k1 = evaluate_derivatives(M, a, state)

        val s2 = FloatArray(6) { i -> state[i] + 0.5f * dlambda * k1[i] }
        val k2 = evaluate_derivatives(M, a, s2)

        val s3 = FloatArray(6) { i -> state[i] + 0.5f * dlambda * k2[i] }
        val k3 = evaluate_derivatives(M, a, s3)

        val s4 = FloatArray(6) { i -> state[i] + dlambda * k3[i] }
        val k4 = evaluate_derivatives(M, a, s4)

        val next = FloatArray(6)
        val sixth = dlambda / 6.0f
        for (i in 0 until 6) {
            next[i] = state[i] + sixth * (k1[i] + 2.0f * k2[i] + 2.0f * k3[i] + k4[i])
        }
        return next
    }

    /** Constructs initial null momentum p_μ matching the GLSL main() ray initialization. */
    fun createInitialRay(M: Float, a: Float, camPos: FloatArray, rayDir: FloatArray): FloatArray {
        val rInit = compute_r_KS(a, camPos[0], camPos[1], camPos[2])
        val g = compute_g_lower(M, a, camPos[0], camPos[1], camPos[2], rInit)

        val Aw = g[0][0]
        var Bw = 0.0f
        for (i in 0 until 3) {
            Bw += g[0][i + 1] * rayDir[i]
        }

        var Cw = 0.0f
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                Cw += g[i + 1][j + 1] * rayDir[i] * rayDir[j]
            }
        }

        val Dw = max(0.0f, Bw * Bw - Aw * Cw)
        val w = (-Bw + sqrt(Dw)) / Aw
        val v = floatArrayOf(w, rayDir[0], rayDir[1], rayDir[2])

        val vLower = FloatArray(4)
        for (i in 0 until 4) {
            var sum = 0.0f
            for (j in 0 until 4) {
                sum += g[i][j] * v[j]
            }
            vLower[i] = sum
        }

        val scale = vLower[0]
        return floatArrayOf(
            camPos[0],
            camPos[1],
            camPos[2],
            vLower[1] / scale,
            vLower[2] / scale,
            vLower[3] / scale
        )
    }

    fun computeHamiltonian(M: Float, a: Float, state: FloatArray): Float {
        val r = compute_r_KS(a, state[0], state[1], state[2])
        val gInv = compute_g_inv(M, a, state[0], state[1], state[2], r)
        val p = floatArrayOf(1.0f, state[3], state[4], state[5])
        var H = 0.0f
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                H += gInv[i][j] * p[i] * p[j]
            }
        }
        return 0.5f * H
    }

    /**
     * One equatorial-plane crossing seen by the shader loop (accepted or rejected).
     * Rejected crossings (rHit outside [diskInnerRadius, diskOuterRadius], or skipped because the shader's
     * diskCrossings < 4 guard is false) do not increment the crossing count and emit nothing.
     */
    data class GpuCrossing(
        /** Loop index `step` of the RK4 step that crossed Z = 0. */
        val step: Int,
        /** Order of this plane crossing along the ray (0 = first time the ray crosses Z = 0). */
        val planeIndex: Int,
        val accepted: Boolean,
        /** True when the shader skipped the crossing because four crossings were already accumulated. */
        val skippedByCrossingLimit: Boolean,
        val rHit: Float,
        val phiHit: Float,
        val gShift: Float,
        val fNorm: Float,
        val tEff: Float,
        val paletteInterval: Int,
        val crossingColor: FloatArray,
        val contribution: FloatArray,
        val transmittanceIn: Float,
        val transmittanceOut: Float
    )

    fun traceRay(
        M: Float,
        a: Float,
        camPos: FloatArray,
        rayDir: FloatArray,
        maxSteps: Int = 150,
        enableDisk: Boolean = false,
        diskInnerRadius: Float = 6.0f,
        diskOuterRadius: Float = 22.0f,
        minStep: Float = 0.035f,
        maxStep: Float = 0.32f,
        maxOuterStep: Float = 0.55f,
        enableDoppler: Boolean = false
    ): GpuRayResult {
        // --- state initialisation (traceRaySample, before the loop) ---
        var state = createInitialRay(M, a, camPos, rayDir)
        val rInitCam = compute_r_KS(a, camPos[0], camPos[1], camPos[2])
        val rPlus = M + sqrt(max(0.0f, M * M - a * a))
        val rCapture = rPlus + 0.05f
        val rEscape = max(50.0f, rInitCam + 15.0f)
        val rPhotonShellOuter = 2.0f * M * (1.0f + cos((2.0f / 3.0f) * acos((abs(a) / M).coerceIn(0.0f, 1.0f))))
        // Shader: int maxSteps = clamp(u_MaxSteps, 40, BASE_INTEGRATION_STEPS).
        val baseSteps = maxSteps.coerceIn(40, BASE_INTEGRATION_STEPS)

        var rayState = STATE_UNRESOLVED
        val accum = FloatArray(3)
        var transmittance = 1.0f
        var diskCrossings = 0
        var planeCrossings = 0
        var primaryHitRadius = 0.0f
        var primaryHitAzimuth = 0.0f
        var primaryG = 1.0f
        val crossings = mutableListOf<GpuCrossing>()

        var minR = rInitCam
        var prevR = rInitCam
        var movingOutward = false
        var lastStepDir = rayDir.clone()
        var stepsTaken = 0

        var maxH = abs(computeHamiltonian(M, a, state))
        val intermediateList = mutableListOf(state.clone())
        val gCam = compute_g_lower(M, a, camPos[0], camPos[1], camPos[2], rInitCam)
        val uObs0 = 1.0f / sqrt(max(1.0e-6f, -gCam[0][0]))

        for (step in 0 until MAX_INTEGRATION_STEPS) {
            // (1) ordinary budget exhausted: only near-critical rays continue
            if (step >= baseSteps) {
                if (minR > rPhotonShellOuter + 0.5f) break
            }
            stepsTaken = step + 1

            // (2) radius bookkeeping of the current position
            val r = compute_r_KS(a, state[0], state[1], state[2])
            if (r < minR) minR = r
            if (r > prevR) movingOutward = true
            prevR = r
            val h = abs(computeHamiltonian(M, a, state))
            if (h > maxH) maxH = h

            // (3) capture
            if (r <= rCapture) {
                rayState = STATE_CAPTURED
                break
            }
            // (4) escape: u_DiskOuterRadius is part of the shader test whether or not the disk is enabled
            if (movingOutward && (r >= rEscape || r >= diskOuterRadius)) {
                rayState = STATE_ESCAPED
                break
            }

            // (5) step size, (6) RK4, (7) last-step direction
            val prevState = state.clone()
            val dlambda = adaptiveStep(r, movingOutward, rCapture, minStep, maxStep, maxOuterStep)
            state = rk4_step(M, a, state, dlambda)
            intermediateList.add(state.clone())
            lastStepDir = floatArrayOf(state[0] - prevState[0], state[1] - prevState[1], state[2] - prevState[2])

            // (8) disk-plane crossing on this step
            if (prevState[2] * state[2] <= 0.0f && prevState[2] != state[2]) {
                val planeIndex = planeCrossings++
                if (!enableDisk) continue
                if (diskCrossings >= 4) {
                    crossings.add(
                        GpuCrossing(step, planeIndex, false, true, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1,
                            FloatArray(3), FloatArray(3), transmittance, transmittance)
                    )
                    continue
                }
                val tau = (-prevState[2] / (state[2] - prevState[2])).coerceIn(0.0f, 1.0f)
                val hitX = prevState[0] + tau * (state[0] - prevState[0])
                val hitY = prevState[1] + tau * (state[1] - prevState[1])
                val rHit = sqrt(max(0.0f, hitX * hitX + hitY * hitY - a * a))
                val phiHit = atan2(hitY, hitX)

                if (rHit >= diskInnerRadius && rHit <= diskOuterRadius) {
                    // (9) accepted: increment count, record primary hit
                    diskCrossings++
                    if (diskCrossings == 1) {
                        primaryHitRadius = rHit
                        primaryHitAzimuth = phiHit
                    }
                    // (10) frequency shift
                    val hitPx = prevState[3] + tau * (state[3] - prevState[3])
                    val hitPy = prevState[4] + tau * (state[4] - prevState[4])
                    val omega = sqrt(M) / (rHit.pow(1.5f) + a * sqrt(M))
                    val denomV = rHit * rHit + a * a
                    val lxHit = (rHit * hitX + a * hitY) / denomV
                    val lyHit = (rHit * hitY - a * hitX) / denomV
                    val hMetric = M / max(1.0e-6f, rHit)
                    val lDotU = 1.0f + omega * (lyHit * hitX - lxHit * hitY)
                    val etaUU = -1.0f + (omega * omega) * (hitX * hitX + hitY * hitY)
                    val denomContract = etaUU + 2.0f * hMetric * (lDotU * lDotU)
                    val u0 = if (denomContract < 0.0f) 1.0f / sqrt(-denomContract) else 1.0f
                    val lz = hitX * hitPy - hitY * hitPx
                    val denomG = u0 * (1.0f + omega * lz)
                    val gShift = if (abs(denomG) > 1.0e-6f) (uObs0 / denomG).coerceIn(0.05f, 5.0f) else 1.0f
                    if (diskCrossings == 1) primaryG = gShift

                    // (11) emission and transmittance through the three strata
                    val len = sqrt(lastStepDir[0] * lastStepDir[0] + lastStepDir[1] * lastStepDir[1] + lastStepDir[2] * lastStepDir[2])
                    val shade = com.zig.gargantua.disk.ShaderDiskShading.shadeCrossing(
                        M, rHit, phiHit, lastStepDir[2] / len, gShift, diskInnerRadius, diskOuterRadius,
                        transmittance, enableDoppler
                    )
                    for (i in 0 until 3) accum[i] += shade.contribution[i]
                    transmittance = shade.transmittanceOut
                    crossings.add(
                        GpuCrossing(step, planeIndex, true, false, rHit, phiHit, gShift, shade.fNorm, shade.tEff,
                            shade.paletteInterval, shade.crossingColor, shade.contribution, shade.transmittanceIn,
                            shade.transmittanceOut)
                    )
                    // (12) fully opaque: DISK state, ray ends
                    if (transmittance == 0.0f) {
                        rayState = STATE_DISK
                        break
                    }
                } else {
                    crossings.add(
                        GpuCrossing(step, planeIndex, false, false, rHit, phiHit, 0.0f, 0.0f, 0.0f, -1,
                            FloatArray(3), FloatArray(3), transmittance, transmittance)
                    )
                }
            }
        }

        // (13) post-loop safety rule, only for a ray that is still unresolved
        var escapedBySafetyRule = false
        val rEnd = compute_r_KS(a, state[0], state[1], state[2])
        if (rayState == STATE_UNRESOLVED) {
            if (rEnd > prevR && rEnd > rPhotonShellOuter) {
                rayState = STATE_ESCAPED
                escapedBySafetyRule = true
            }
        }

        // (14) sky direction of the shader's escaped branch
        val skyDir = if (escapedBySafetyRule) lastStepDir else floatArrayOf(state[3], state[4], state[5])
        val skyLen = sqrt(skyDir[0] * skyDir[0] + skyDir[1] * skyDir[1] + skyDir[2] * skyDir[2])
        val skyDirection = floatArrayOf(skyDir[0] / skyLen, skyDir[1] / skyLen, skyDir[2] / skyLen)

        return GpuRayResult(
            isCaptured = rayState == STATE_CAPTURED,
            isEscaped = rayState == STATE_ESCAPED,
            isDiskHit = diskCrossings >= 1,
            stepsTaken = stepsTaken,
            finalPos = floatArrayOf(state[0], state[1], state[2]),
            finalMomentum = floatArrayOf(state[3], state[4], state[5]),
            minRadiusReached = minR,
            maxHamiltonianResidual = maxH,
            intermediateStates = intermediateList,
            frequencyShift = primaryG,
            rHit = primaryHitRadius,
            escapedBySafetyRule = escapedBySafetyRule,
            rayState = rayState,
            diskCrossings = diskCrossings,
            crossings = crossings,
            accumulatedRadiance = accum,
            transmittance = transmittance,
            skyDirection = skyDirection,
            finalRadius = rEnd,
            primaryHitAzimuth = primaryHitAzimuth
        )
    }

    /** traceRaySample rayState codes. */
    const val STATE_UNRESOLVED = 0
    const val STATE_CAPTURED = 1
    const val STATE_ESCAPED = 2
    const val STATE_DISK = 3

    /**
     * Step size of gargantua_geodesic.frag: baseStep = 0.085·r, clamped to [minStep, maxOuterStep] when
     * r > 6 && (movingOutward || r > 18) and to [minStep, maxStep] otherwise, then limited to
     * mix(0.032, 0.075, (r - rCapture)/0.95) inside the capture zone (causticStep). Backward-traced
     * (p0 = +1) rays approach r+ with diverging covariant momentum in ingoing Kerr-Schild coordinates.
     */
    fun adaptiveStep(
        r: Float,
        movingOutward: Boolean,
        rCapture: Float,
        minStep: Float = 0.035f,
        maxStep: Float = 0.32f,
        maxOuterStep: Float = 0.55f
    ): Float {
        val baseStep = 0.085f * r
        var dlambda = if (r > 6.0f && (movingOutward || r > 18.0f)) {
            baseStep.coerceIn(minStep, maxOuterStep)
        } else {
            baseStep.coerceIn(minStep, maxStep)
        }
        if (r > rCapture && r < rCapture + 0.95f) {
            val proximity = (r - rCapture) / 0.95f
            dlambda = min(dlambda, 0.032f + (0.075f - 0.032f) * proximity)
        }
        return dlambda
    }

    /** Shader BASE_INTEGRATION_STEPS (ordinary per-ray budget ceiling). */
    const val BASE_INTEGRATION_STEPS = 180

    /** Shader MAX_INTEGRATION_STEPS (near-critical continuation limit). */
    const val MAX_INTEGRATION_STEPS = 1500
}
