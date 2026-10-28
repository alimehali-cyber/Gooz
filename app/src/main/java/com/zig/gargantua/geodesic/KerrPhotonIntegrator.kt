package com.zig.gargantua.geodesic

import com.zig.gargantua.disk.AccretionDiskModel
import com.zig.gargantua.disk.DiskIntersection
import com.zig.gargantua.physics.KerrSchildCoordinates
import com.zig.gargantua.physics.KerrSchildDerivatives
import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.worldline.RelativisticObject
import com.zig.gargantua.worldline.RelativisticObjectIntersection
import kotlin.math.*

/**
 * High-precision RK4 null geodesic integrator for photon trajectories in Kerr-Schild spacetime.
 *
 * HAMILTONIAN GEODESIC EQUATIONS:
 *   dx^i / dλ = g^{iν} p_ν
 *   dp_i / dλ = -1/2 (∂_i g^{αβ}) p_α p_β
 * with p_0 constant due to stationarity (∂_T g^{αβ} = 0). traceRay() takes p_0 from the initial
 * state: camera rays built by [CameraModel] are past-directed backward traces with p_0 = +1.0,
 * matching the GPU shader; states constructed with p_0 = -1.0 keep that value.
 *
 * With its default parameters this is the double-precision reference implementation of the
 * gargantua_geodesic.frag integration loop: same step policy (0.085·r clamped to [0.035, 0.32], or
 * to [0.035, 0.55] when r > 6 and the ray is moving outward or r > 18), same capture-zone limiter,
 * same capture test r <= r+ + 0.05, same escape test (moving outward and r >= max(50, r_start + 15)
 * or, with a disk, r >= disk outer radius), same near-critical continuation (rays with
 * minR <= r_photonShellOuter + 0.5 continue past [maxSteps] up to 1500 steps) and the same
 * post-loop safety rule. The Hamiltonian residual is only evaluated on states that passed the
 * capture test (never inside the capture termination region).
 *
 * Loop order per iteration, identical to traceRaySample: budget check -> r, minR, movingOutward,
 * previousR -> capture -> escape -> step size -> RK4 -> object -> equatorial-plane crossing. The disk
 * is see-through exactly as in the shader: up to four accepted crossings add their emission
 * ([com.zig.gargantua.disk.ShaderDiskShading]) and attenuate the transmittance while the same ray keeps
 * integrating; only a transmittance of 0 ends the ray (DISK_HIT = shader rayState 3). The safety rule
 * is applied only to a ray that is still unresolved after the loop. The escape test includes the disk
 * outer edge only when a disk model is supplied (the shader always has u_DiskOuterRadius).
 */
class KerrPhotonIntegrator(
    val spacetime: KerrSchildSpacetime,
    val escapeRadius: Double? = null,
    val captureHorizonMargin: Double = 0.05,
    val maxSteps: Int = 1500,
    val baseStepFactor: Double = 0.085,
    val minStepSize: Double = 0.035,
    val maxStepSize: Double = 0.32,
    val maxOuterStepSize: Double = 0.55,
    val disk: AccretionDiskModel? = null,
    val objectModel: RelativisticObject? = null,
    /** Shader u_EnableDoppler (default false: Movie Mode palette weighting). */
    val enableDoppler: Boolean = false
) {
    enum class TerminationReason {
        CAPTURED,
        ESCAPED,
        DISK_HIT,
        OBJECT_HIT,
        MAX_STEPS_EXCEEDED,
        NUMERICAL_ERROR
    }

    data class RayTraceResult(
        val finalState: PhotonState4D,
        val terminationReason: TerminationReason,
        val stepsTaken: Int,
        val minRadiusReached: Double,
        val maxHamiltonianResidual: Double,
        val path: List<PhotonState4D>? = null,
        val minStepSizeTaken: Double = 0.0,
        val maxStepSizeTaken: Double = 0.0,
        val stepSizes: List<Double>? = null,
        val diskHit: DiskIntersection.DiskHitResult? = null,
        val objectHit: RelativisticObjectIntersection.ObjectHitResult? = null,
        /** max |H| / (½ (p_0² + |p|²)) over the same pre-capture states as [maxHamiltonianResidual]. */
        val maxRelativeHamiltonianResidual: Double = 0.0,
        /** True when an unresolved ray was classified ESCAPED by the shader's post-loop safety rule. */
        val escapedBySafetyRule: Boolean = false,
        /** Every equatorial-plane crossing processed by the loop, in order (disk model only). */
        val diskCrossings: List<DiskCrossingRecord> = emptyList(),
        /** Shader accumDiskRadiance. */
        val accumulatedRadiance: FloatArray = FloatArray(3),
        /** Shader diskTransmittance at the end of the ray. */
        val transmittance: Float = 1.0f,
        /** Shader sky direction: normalize(lastStepDir) after the safety rule, otherwise normalize(p_spatial). */
        val skyDirection: DoubleArray = DoubleArray(3)
    ) {
        val isCaptured: Boolean get() = terminationReason == TerminationReason.CAPTURED
        val isEscaped: Boolean get() = terminationReason == TerminationReason.ESCAPED
        /**
         * At least one accepted disk crossing (the shader's primaryHitRadius / baseHitR). The disk is
         * see-through: such a ray may still end CAPTURED, ESCAPED or unresolved; [diskHit] is the first
         * accepted crossing.
         */
        val isDiskHit: Boolean get() = diskHit != null
        /** Shader rayState 3: the transmittance fell to 0 and ended the ray inside the disk. */
        val isOpaqueDiskHit: Boolean get() = terminationReason == TerminationReason.DISK_HIT
        val isObjectHit: Boolean get() = terminationReason == TerminationReason.OBJECT_HIT
        /** Number of accepted crossings (shader diskCrossings). */
        val acceptedCrossingCount: Int get() = diskCrossings.count { it.accepted }
    }

    /** One equatorial-plane crossing (see [GpuEquivalentIntegrator.GpuCrossing]). */
    data class DiskCrossingRecord(
        val step: Int,
        val planeIndex: Int,
        val accepted: Boolean,
        val skippedByCrossingLimit: Boolean,
        val rHit: Double,
        val phiHit: Double,
        val gShift: Double,
        val fNorm: Float,
        val tEff: Float,
        val paletteInterval: Int,
        val crossingColor: FloatArray,
        val contribution: FloatArray,
        val transmittanceIn: Float,
        val transmittanceOut: Float,
        val hit: DiskIntersection.DiskHitResult?
    )

    /**
     * Evaluates the right-hand side of Hamilton's equations for the 6D phase space:
     *   state = [X, Y, Z, p_X, p_Y, p_Z], with the conserved p_0 supplied separately.
     */
    fun evaluateDerivatives(state: DoubleArray, p0: Double = -1.0): DoubleArray {
        val x = state[0]
        val y = state[1]
        val z = state[2]
        val px = state[3]
        val py = state[4]
        val pz = state[5]

        val p = doubleArrayOf(p0, px, py, pz)

        // Contravariant metric g^μν
        val gInv = spacetime.inverseMetric(x, y, z)

        // Exact analytical spatial derivatives ∂_i g^μν from Phase M3
        val dG = KerrSchildDerivatives.compute(spacetime.params, x, y, z)
        val dg_dX = dG.d_dX
        val dg_dY = dG.d_dY
        val dg_dZ = dG.d_dZ

        // 1. dx^i / dλ = g^{iν} p_ν
        var dX = 0.0
        var dY = 0.0
        var dZ = 0.0
        for (nu in 0 until 4) {
            val pNu = p[nu]
            dX += gInv[1, nu] * pNu
            dY += gInv[2, nu] * pNu
            dZ += gInv[3, nu] * pNu
        }

        // 2. dp_i / dλ = -1/2 (∂_i g^αβ) p_α p_β
        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        for (alpha in 0 until 4) {
            val pA = p[alpha]
            for (beta in 0 until 4) {
                val pB = p[beta]
                val pTerm = pA * pB
                sumX += dg_dX[alpha, beta] * pTerm
                sumY += dg_dY[alpha, beta] * pTerm
                sumZ += dg_dZ[alpha, beta] * pTerm
            }
        }

        return doubleArrayOf(
            dX,
            dY,
            dZ,
            -0.5 * sumX,
            -0.5 * sumY,
            -0.5 * sumZ
        )
    }

    /**
     * Executes a single 4th-order Runge-Kutta step.
     */
    fun rk4Step(state: DoubleArray, dlambda: Double, p0: Double = -1.0): DoubleArray {
        val k1 = evaluateDerivatives(state, p0)

        val s2 = DoubleArray(6) { i -> state[i] + 0.5 * dlambda * k1[i] }
        val k2 = evaluateDerivatives(s2, p0)

        val s3 = DoubleArray(6) { i -> state[i] + 0.5 * dlambda * k2[i] }
        val k3 = evaluateDerivatives(s3, p0)

        val s4 = DoubleArray(6) { i -> state[i] + dlambda * k3[i] }
        val k4 = evaluateDerivatives(s4, p0)

        val next = DoubleArray(6)
        val sixth = dlambda / 6.0
        for (i in 0 until 6) {
            next[i] = state[i] + sixth * (k1[i] + 2.0 * k2[i] + 2.0 * k3[i] + k4[i])
        }
        return next
    }

    /**
     * Adaptive step size, identical to gargantua_geodesic.frag:
     *   baseStep = factor·r; clamp(baseStep, min, maxOuter) if r > 6 && (movingOutward || r > 18),
     *   otherwise clamp(baseStep, min, max). The capture-zone limiter is applied by [traceRay].
     */
    fun computeAdaptiveStep(r: Double, movingOutward: Boolean = false, stepFactorOverride: Double? = null): Double {
        val factor = stepFactorOverride ?: baseStepFactor
        val rawStep = factor * r
        return if (r > 6.0 && (movingOutward || r > 18.0)) {
            rawStep.coerceIn(minStepSize, maxOuterStepSize)
        } else {
            rawStep.coerceIn(minStepSize, maxStepSize)
        }
    }

    /**
     * Traces a photon ray from initial state until capture, escape, or timeout.
     */
    fun traceRay(
        initialState: PhotonState4D,
        recordPath: Boolean = false,
        fixedStepSize: Double? = null
    ): RayTraceResult {
        var state = doubleArrayOf(
            initialState.x,
            initialState.y,
            initialState.z,
            initialState.p_x,
            initialState.p_y,
            initialState.p_z
        )

        // Conserved covariant energy component of this ray (stationarity).
        val p0 = initialState.p_t

        val a = spacetime.a
        val rPlus = spacetime.rPlus
        val rCaptureThreshold = rPlus + captureHorizonMargin

        val initialR = KerrSchildCoordinates.computeR(a, state[0], state[1], state[2])
        val rEscape = escapeRadius ?: max(50.0, initialR + 15.0)
        val m = spacetime.M
        val rPhotonShellOuter = 2.0 * m * (1.0 + cos((2.0 / 3.0) * acos((abs(a) / m).coerceIn(0.0, 1.0))))
        var minR = initialR
        var maxHResidual = 0.0
        var maxRelHResidual = 0.0
        var currentLambda = initialState.affineLambda
        var currentT = initialState.t

        val pathList = if (recordPath) mutableListOf(initialState) else null
        val stepSizesList = if (recordPath) mutableListOf<Double>() else null
        var minStepTaken = Double.MAX_VALUE
        var maxStepTaken = 0.0

        var previousR = initialR
        var movingOutward = false

        // Shader see-through disk state (accumDiskRadiance, diskTransmittance, diskCrossings, lastStepDir).
        val accum = FloatArray(3)
        var transmittance = 1.0f
        var acceptedCrossings = 0
        var planeCrossings = 0
        var firstDiskHit: DiskIntersection.DiskHitResult? = null
        val crossingList = mutableListOf<DiskCrossingRecord>()
        val v0 = initialState.velocity(spacetime)
        var lastStepDir = doubleArrayOf(v0[1], v0[2], v0[3])
        fun skyDirection(escapedBySafety: Boolean, s: DoubleArray): DoubleArray {
            val d = if (escapedBySafety) lastStepDir else doubleArrayOf(s[3], s[4], s[5])
            val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            return doubleArrayOf(d[0] / l, d[1] / l, d[2] / l)
        }

        var stepsExecuted = 0
        for (step in 0 until max(maxSteps, NEAR_CRITICAL_MAX_STEPS)) {
            if (step >= maxSteps && minR > rPhotonShellOuter + 0.5) break
            stepsExecuted = step
            val r = KerrSchildCoordinates.computeR(a, state[0], state[1], state[2])
            if (r < minR) minR = r

            if (r > previousR) {
                movingOutward = true
            }
            previousR = r

            val currentState = PhotonState4D(
                t = currentT,
                x = state[0],
                y = state[1],
                z = state[2],
                p_t = p0,
                p_x = state[3],
                p_y = state[4],
                p_z = state[5],
                affineLambda = currentLambda
            )
            // 1. Termination condition: Capture by event horizon
            if (r <= rCaptureThreshold) {
                return RayTraceResult(
                    finalState = currentState,
                    terminationReason = TerminationReason.CAPTURED,
                    stepsTaken = step,
                    minRadiusReached = minR,
                    maxHamiltonianResidual = maxHResidual,
                    path = pathList,
                    minStepSizeTaken = if (minStepTaken == Double.MAX_VALUE) 0.0 else minStepTaken,
                    maxStepSizeTaken = maxStepTaken,
                    stepSizes = stepSizesList,
                    maxRelativeHamiltonianResidual = maxRelHResidual,
                    diskHit = firstDiskHit,
                    diskCrossings = crossingList,
                    accumulatedRadiance = accum,
                    transmittance = transmittance
                )
            }

            // Hamiltonian constraint, evaluated only outside the capture termination region.
            val h = abs(currentState.hamiltonian(spacetime))
            if (h > maxHResidual) maxHResidual = h
            val pScale = 0.5 * (p0 * p0 + state[3] * state[3] + state[4] * state[4] + state[5] * state[5])
            if (h / pScale > maxRelHResidual) maxRelHResidual = h / pScale

            // 2. Termination condition: Escape to asymptotic background (same test as the shader)
            if (movingOutward && (r >= rEscape || (disk != null && r >= disk.outerRadius))) {
                return RayTraceResult(
                    finalState = currentState,
                    terminationReason = TerminationReason.ESCAPED,
                    stepsTaken = step,
                    minRadiusReached = minR,
                    maxHamiltonianResidual = maxHResidual,
                    path = pathList,
                    minStepSizeTaken = if (minStepTaken == Double.MAX_VALUE) 0.0 else minStepTaken,
                    maxStepSizeTaken = maxStepTaken,
                    stepSizes = stepSizesList,
                    maxRelativeHamiltonianResidual = maxRelHResidual,
                    diskHit = firstDiskHit,
                    diskCrossings = crossingList,
                    accumulatedRadiance = accum,
                    transmittance = transmittance,
                    skyDirection = skyDirection(false, state)
                )
            }

            // 3. Numerical validity check (CPU only; the shader has no equivalent branch)
            if (state.any { it.isNaN() || it.isInfinite() }) {
                return RayTraceResult(
                    finalState = currentState,
                    terminationReason = TerminationReason.NUMERICAL_ERROR,
                    stepsTaken = step,
                    minRadiusReached = minR,
                    maxHamiltonianResidual = maxHResidual,
                    path = pathList,
                    minStepSizeTaken = if (minStepTaken == Double.MAX_VALUE) 0.0 else minStepTaken,
                    maxStepSizeTaken = maxStepTaken,
                    stepSizes = stepSizesList
                )
            }

            // Advance step
            var dlambda = fixedStepSize ?: computeAdaptiveStep(r, movingOutward)
            // Capture-zone limiter, identical to gargantua_geodesic.frag (causticStep): a backward-traced
            // (past-directed, p_t = +1) ray in ingoing Kerr-Schild coordinates approaches r+ with a
            // diverging covariant momentum, so without smaller steps it can bounce numerically out of the
            // shadow instead of reaching the capture threshold.
            if (fixedStepSize == null && r > rCaptureThreshold && r < rCaptureThreshold + 0.95) {
                val proximity = (r - rCaptureThreshold) / 0.95
                dlambda = min(dlambda, 0.032 + (0.075 - 0.032) * proximity)
            }
            if (dlambda < minStepTaken) minStepTaken = dlambda
            if (dlambda > maxStepTaken) maxStepTaken = dlambda
            stepSizesList?.add(dlambda)

            val nextState = rk4Step(state, dlambda, p0)

            // Approximate dT
            val v = currentState.velocity(spacetime)
            currentT += v[0] * dlambda
            currentLambda += dlambda

            val nextPhotonState = PhotonState4D(
                t = currentT,
                x = nextState[0],
                y = nextState[1],
                z = nextState[2],
                p_t = p0,
                p_x = nextState[3],
                p_y = nextState[4],
                p_z = nextState[5],
                affineLambda = currentLambda
            )

            // Check for relativistic test object intersection
            if (objectModel != null && objectModel.enabled) {
                val hit = RelativisticObjectIntersection.checkIntersection(
                    previous = currentState,
                    current = nextPhotonState,
                    spacetime = spacetime,
                    obj = objectModel,
                    camX = initialState.x,
                    camY = initialState.y,
                    camZ = initialState.z
                )
                if (hit != null) {
                    pathList?.add(nextPhotonState)
                    return RayTraceResult(
                        finalState = nextPhotonState,
                        terminationReason = TerminationReason.OBJECT_HIT,
                        stepsTaken = step + 1,
                        minRadiusReached = min(minR, hit.hitDistance),
                        maxHamiltonianResidual = maxHResidual,
                        path = pathList,
                        minStepSizeTaken = if (minStepTaken == Double.MAX_VALUE) 0.0 else minStepTaken,
                        maxStepSizeTaken = maxStepTaken,
                        stepSizes = stepSizesList,
                        objectHit = hit,
                        maxRelativeHamiltonianResidual = maxRelHResidual
                    )
                }
            }

            lastStepDir = doubleArrayOf(nextState[0] - state[0], nextState[1] - state[1], nextState[2] - state[2])

            // Equatorial-plane crossing, processed exactly like the shader's see-through disk block:
            // guard diskCrossings < 4, accept rHit in [r_in, r_out], add the crossing's emission, attenuate
            // the transmittance and keep integrating the same ray; only a transmittance of 0 ends it.
            if (disk != null && state[2] * nextState[2] <= 0.0 && state[2] != nextState[2]) {
                val planeIndex = planeCrossings++
                if (acceptedCrossings >= 4) {
                    crossingList.add(
                        DiskCrossingRecord(step, planeIndex, false, true, 0.0, 0.0, 0.0, 0.0f, 0.0f, -1,
                            FloatArray(3), FloatArray(3), transmittance, transmittance, null)
                    )
                } else {
                    val hit = DiskIntersection.checkIntersection(
                        previous = currentState,
                        current = nextPhotonState,
                        spacetime = spacetime,
                        disk = disk,
                        camX = initialState.x,
                        camY = initialState.y,
                        camZ = initialState.z
                    )
                    if (hit != null) {
                        acceptedCrossings++
                        if (firstDiskHit == null) firstDiskHit = hit
                        val phiHit = atan2(hit.hitY, hit.hitX)
                        val len = sqrt(lastStepDir[0] * lastStepDir[0] + lastStepDir[1] * lastStepDir[1] + lastStepDir[2] * lastStepDir[2])
                        val shade = com.zig.gargantua.disk.ShaderDiskShading.shadeCrossing(
                            spacetime.M.toFloat(), hit.rHit.toFloat(), phiHit.toFloat(), (lastStepDir[2] / len).toFloat(),
                            hit.frequencyShift.toFloat(), disk.innerRadius.toFloat(), disk.outerRadius.toFloat(),
                            transmittance, enableDoppler
                        )
                        for (i in 0 until 3) accum[i] += shade.contribution[i]
                        transmittance = shade.transmittanceOut
                        crossingList.add(
                            DiskCrossingRecord(step, planeIndex, true, false, hit.rHit, phiHit, hit.frequencyShift,
                                shade.fNorm, shade.tEff, shade.paletteInterval, shade.crossingColor, shade.contribution,
                                shade.transmittanceIn, shade.transmittanceOut, hit)
                        )
                        if (transmittance == 0.0f) {
                            pathList?.add(nextPhotonState)
                            return RayTraceResult(
                                finalState = nextPhotonState,
                                terminationReason = TerminationReason.DISK_HIT,
                                stepsTaken = step + 1,
                                minRadiusReached = minR,
                                maxHamiltonianResidual = maxHResidual,
                                path = pathList,
                                minStepSizeTaken = if (minStepTaken == Double.MAX_VALUE) 0.0 else minStepTaken,
                                maxStepSizeTaken = maxStepTaken,
                                stepSizes = stepSizesList,
                                diskHit = firstDiskHit,
                                maxRelativeHamiltonianResidual = maxRelHResidual,
                                diskCrossings = crossingList,
                                accumulatedRadiance = accum,
                                transmittance = transmittance
                            )
                        }
                    } else {
                        val tau = (-state[2] / (nextState[2] - state[2])).coerceIn(0.0, 1.0)
                        val hx = state[0] + tau * (nextState[0] - state[0])
                        val hy = state[1] + tau * (nextState[1] - state[1])
                        crossingList.add(
                            DiskCrossingRecord(step, planeIndex, false, false, disk.equatorialRadius(hx, hy), atan2(hy, hx),
                                0.0, 0.0f, 0.0f, -1, FloatArray(3), FloatArray(3), transmittance, transmittance, null)
                        )
                    }
                }
            }

            state = nextState
            stepsExecuted = step + 1
            pathList?.add(nextPhotonState)
        }

        // Post-loop safety rule, identical to the shader: an unresolved ray that is moving outward
        // outside the outermost spherical photon orbit has no turning point left and escapes.
        val rEnd = KerrSchildCoordinates.computeR(a, state[0], state[1], state[2])
        val escapedBySafety = rEnd > previousR && rEnd > rPhotonShellOuter
        val finalState = PhotonState4D(
            t = currentT,
            x = state[0],
            y = state[1],
            z = state[2],
            p_t = p0,
            p_x = state[3],
            p_y = state[4],
            p_z = state[5],
            affineLambda = currentLambda
        )
        return RayTraceResult(
            finalState = finalState,
            terminationReason = if (escapedBySafety) TerminationReason.ESCAPED else TerminationReason.MAX_STEPS_EXCEEDED,
            stepsTaken = stepsExecuted,
            minRadiusReached = minR,
            maxHamiltonianResidual = maxHResidual,
            path = pathList,
            minStepSizeTaken = if (minStepTaken == Double.MAX_VALUE) 0.0 else minStepTaken,
            maxStepSizeTaken = maxStepTaken,
            stepSizes = stepSizesList,
            maxRelativeHamiltonianResidual = maxRelHResidual,
            escapedBySafetyRule = escapedBySafety,
            diskHit = firstDiskHit,
            diskCrossings = crossingList,
            accumulatedRadiance = accum,
            transmittance = transmittance,
            skyDirection = skyDirection(escapedBySafety, state)
        )
    }

    companion object {
        /** Shader MAX_INTEGRATION_STEPS: hard limit of the near-critical continuation. */
        const val NEAR_CRITICAL_MAX_STEPS = 1500
    }
}
