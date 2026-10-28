package com.zig.gargantua.disk

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Single-precision port of the per-crossing disk shading of gargantua_geodesic.frag
 * (gargantuaHash3D, gargantuaNoise3D, gargantuaDomainWarp, evaluate3DVolumetricGasDensity,
 * evaluate4TierBlackbodySpectrum and the three-stratum slab integration of traceRaySample).
 *
 * Diagnostic reference only: the shader is the production source of truth. Everything is evaluated in
 * Float because the lattice hash is defined by float32 arithmetic (a double evaluation of the same
 * expression yields a different texture). GLSL built-ins are written out with their specified
 * definitions: fract(x) = x - floor(x), mix(x, y, a) = x (1 - a) + y a, smoothstep with clamped t.
 */
object ShaderDiskShading {

    /** Stop colours of evaluate4TierBlackbodySpectrum (frozen). */
    private val C_SMOKE = floatArrayOf(0.012f, 0.003f, 0.001f)
    private val C_RUST = floatArrayOf(0.48f, 0.065f, 0.003f)
    private val C_AMBER = floatArrayOf(2.60f, 0.65f, 0.015f)
    private val C_GOLD = floatArrayOf(7.50f, 4.20f, 0.35f)
    private val C_WHITE_HOT = floatArrayOf(16.5f, 15.2f, 13.5f)

    /** Result of shading one accepted disk crossing. */
    data class CrossingShade(
        /** Radiance added to accumDiskRadiance by this crossing (already weighted by the incoming transmittance). */
        val contribution: FloatArray,
        /** evaluate4TierBlackbodySpectrum(fNorm, gShift): the crossing's own emitted colour. */
        val crossingColor: FloatArray,
        val transmittanceIn: Float,
        val transmittanceOut: Float,
        val fNorm: Float,
        /** Palette coordinate tEff = clamp(gFactor * fNorm, 0, 1). */
        val tEff: Float,
        /** Palette interval index: 0 smoke-rust, 1 rust-amber, 2 amber-gold, 3 gold-white. */
        val paletteInterval: Int
    ) {
        /** Shader: diskTransmittance == 0.0 after the strata loop sets rayState = 3 and ends the ray. */
        val opaque: Boolean get() = transmittanceOut == 0.0f
    }

    private fun fract(x: Float): Float = x - floor(x)
    private fun mix(x: Float, y: Float, a: Float): Float = x * (1.0f - a) + y * a
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0f, 1.0f)
        return t * t * (3.0f - 2.0f * t)
    }

    fun hash3D(px: Float, py: Float, pz: Float): Float {
        var x = fract(px * 0.3183099f + 0.1f)
        var y = fract(py * 0.3183099f + 0.1f)
        var z = fract(pz * 0.3183099f + 0.1f)
        x *= 17.0f
        y *= 17.0f
        z *= 17.0f
        return fract(x * y * z * (x + y + z))
    }

    fun noise3D(px: Float, py: Float, pz: Float): Float {
        val ix = floor(px)
        val iy = floor(py)
        val iz = floor(pz)
        val fx = fract(px)
        val fy = fract(py)
        val fz = fract(pz)
        val ux = fx * fx * (3.0f - 2.0f * fx)
        val uy = fy * fy * (3.0f - 2.0f * fy)
        val uz = fz * fz * (3.0f - 2.0f * fz)
        val n000 = hash3D(ix, iy, iz)
        val n100 = hash3D(ix + 1.0f, iy, iz)
        val n010 = hash3D(ix, iy + 1.0f, iz)
        val n110 = hash3D(ix + 1.0f, iy + 1.0f, iz)
        val n001 = hash3D(ix, iy, iz + 1.0f)
        val n101 = hash3D(ix + 1.0f, iy, iz + 1.0f)
        val n011 = hash3D(ix, iy + 1.0f, iz + 1.0f)
        val n111 = hash3D(ix + 1.0f, iy + 1.0f, iz + 1.0f)
        return mix(
            mix(mix(n000, n100, ux), mix(n010, n110, ux), uy),
            mix(mix(n001, n101, ux), mix(n011, n111, ux), uy),
            uz
        )
    }

    private fun domainWarp(px: Float, py: Float, pz: Float, strength: Float): FloatArray {
        val wx = noise3D(px * 0.5f + 11.3f, py * 0.5f + 2.1f, pz * 0.5f + 7.4f) - 0.5f
        val wy = noise3D(px * 0.5f + 41.7f, py * 0.5f + 91.3f, pz * 0.5f + 3.9f) - 0.5f
        val wz = noise3D(px * 0.5f + 5.5f, py * 0.5f + 63.2f, pz * 0.5f + 27.8f) - 0.5f
        return floatArrayOf(px + wx * strength, py + wy * strength, pz + wz * strength)
    }

    /**
     * gargantuaMaterialPerturbationField(rHit, phiMaterial, keplerPhase): co-moving perturbation in [-1, 1]
     * of the orbiting gas, evaluated at the material azimuth phiMaterial = phiHit - OmegaK(rHit) t.
     */
    fun materialPerturbationField(rHit: Float, phiMaterial: Float, keplerPhase: Float, rIn: Float): Float {
        val rNorm = max(1.0f, rHit / rIn)
        val logR = ln(rNorm)
        val phiSheared = phiMaterial - 26.0f * rNorm.pow(-1.5f) - 10.0f * logR
        val x = logR * 18.0f
        val y = cos(phiSheared) * 3.0f
        val z = sin(phiSheared) * 3.0f
        val fieldA = noise3D(x + 17.0f, y + 5.0f, z + 29.0f) * 2.0f - 1.0f
        val fieldB = noise3D(x + 43.0f, y + 71.0f, z + 13.0f) * 2.0f - 1.0f
        return (fieldA * cos(keplerPhase) + fieldB * sin(keplerPhase)).coerceIn(-1.0f, 1.0f)
    }

    /**
     * Kepler phase OmegaK(rHit) t of the shader (omega = sqrt(M) / (r^1.5 + a sqrt(M))). It depends on the
     * hit radius and the animation time only; the amplitude is not an argument.
     */
    fun keplerPhase(m: Float, a: Float, rHit: Float, t: Float): Float =
        sqrt(m) / (rHit.pow(1.5f) + a * sqrt(m)) * t

    /**
     * evaluate3DVolumetricGasDensity(r, phi, zeta, fNorm, rIn, rOut) with the animated material
     * perturbation of the crossing (gargantuaMaterialPerturbation) and u_AnimationAmplitude; amplitude 0
     * returns the unperturbed density.
     */
    fun gasDensity(
        r: Float,
        phi: Float,
        zeta: Float,
        fNorm: Float,
        rIn: Float,
        rOut: Float,
        perturbation: Float = 0.0f,
        amplitude: Float = 0.0f
    ): Float {
        val verticalFalloff = exp(-5.0f * zeta * zeta)
        val rNorm = max(1.0f, r / rIn)
        val logR = ln(rNorm)
        val keplerShear = 26.0f * rNorm.pow(-1.5f)
        val phiSheared = phi - keplerShear - 10.0f * logR
        val cx = cos(phiSheared)
        val cy = sin(phiSheared)

        val f1x = logR * 36.0f
        val f1y = cx * 4.5f + zeta * 2.2f
        val f1z = cy * 4.5f
        val w1 = domainWarp(f1x, f1y, f1z, 1.1f)
        val fineThreads = noise3D(w1[0], w1[1], w1[2])

        val f2x = logR * 72.0f
        val f2y = cx * 9.0f + zeta * 3.5f
        val f2z = cy * 9.0f
        val microThreads = noise3D(f2x * 1.5f + 2.3f, f2y * 1.5f + 7.1f, f2z * 1.5f + 0.9f)

        val spunFilaments = (fineThreads * 0.60f + microThreads * 0.40f).pow(1.8f) * 1.75f

        val dx = logR * 6.5f
        val dy = cx * 2.0f + zeta * 1.5f
        val dz = cy * 2.0f
        val wd = domainWarp(dx, dy, dz, 1.4f)
        val dustRift = smoothstep(0.28f, 0.72f, noise3D(wd[0], wd[1], wd[2]))
        val dustFiligree = smoothstep(0.32f, 0.65f, noise3D(f1x * 1.8f + 1.1f, f1y * 1.8f + 3.4f, f1z * 1.8f + 5.2f))
        val totalDust = dustRift * (0.4f + 0.6f * dustFiligree)

        val innerCutoff = smoothstep(rIn, rIn + 0.05f * rIn, r)

        val ox = cx * 3.0f
        val oy = logR * 8.0f
        val oz = 0.5f
        val outerTrailingWarp = noise3D(ox, oy, oz) * 0.7f + noise3D(ox * 2.5f, oy * 2.5f, oz * 2.5f) * 0.3f
        val frayedRadius = rOut - 4.2f * outerTrailingWarp
        val outerWisps = 1.0f - smoothstep(frayedRadius - 3.2f, frayedRadius + 1.2f, r)

        var rawDensity = (0.28f + 1.15f * spunFilaments) * (0.15f + 0.85f * totalDust)
        if (amplitude > 0.0f) {
            rawDensity *= 1.0f + amplitude * perturbation
        }
        return (rawDensity * verticalFalloff * (0.45f + 0.55f * fNorm) * innerCutoff * outerWisps).coerceIn(0.0f, 5.5f)
    }

    /** Shader fNorm = clamp(F / F_peak, 0, 1) with F = M/r³ (1 - sqrt(rIn/r)), r_peak = 1.361111 rIn. */
    fun normalizedFlux(m: Float, rHit: Float, rIn: Float): Float {
        val rRatio = rIn / rHit
        val f = (m / (rHit * rHit * rHit)) * max(0.0f, 1.0f - sqrt(rRatio))
        val rPeak = 1.361111f * rIn
        val fPeak = m / (7.0f * rPeak * rPeak * rPeak)
        return if (fPeak > 1.0e-7f) (f / fPeak).coerceIn(0.0f, 1.0f) else 0.0f
    }

    /** Palette coordinate of evaluate4TierBlackbodySpectrum. */
    fun paletteCoordinate(fNorm: Float, gShift: Float, enableDoppler: Boolean): Float {
        val gFactor = if (enableDoppler) gShift.coerceIn(0.10f, 4.2f) else mix(1.0f, gShift.coerceIn(0.65f, 1.65f), 0.12f)
        return (gFactor * fNorm).coerceIn(0.0f, 1.0f)
    }

    fun paletteInterval(tEff: Float): Int = when {
        tEff < 0.14f -> 0
        tEff < 0.38f -> 1
        tEff < 0.70f -> 2
        else -> 3
    }

    /** evaluate4TierBlackbodySpectrum(fNorm, gShift). */
    fun spectrum(fNorm: Float, gShift: Float, enableDoppler: Boolean): FloatArray {
        val iPhys: Float
        if (enableDoppler) {
            val gClamped = gShift.coerceIn(0.10f, 4.2f)
            iPhys = gClamped.pow(4.0f)
        } else {
            iPhys = 1.0f
        }
        val tEff = paletteCoordinate(fNorm, gShift, enableDoppler)
        val (c0, c1, t) = when {
            tEff < 0.14f -> Triple(C_SMOKE, C_RUST, smoothstep(0.0f, 0.14f, tEff))
            tEff < 0.38f -> Triple(C_RUST, C_AMBER, smoothstep(0.14f, 0.38f, tEff))
            tEff < 0.70f -> Triple(C_AMBER, C_GOLD, smoothstep(0.38f, 0.70f, tEff))
            else -> Triple(C_GOLD, C_WHITE_HOT, smoothstep(0.70f, 1.0f, tEff))
        }
        val color = FloatArray(3) { mix(c0[it], c1[it], t) }
        if (enableDoppler && gShift > 1.20f) {
            val whiteBoost = smoothstep(1.20f, 2.10f, gShift)
            val white = floatArrayOf(17.0f, 16.0f, 15.0f)
            for (i in 0 until 3) color[i] = mix(color[i], white[i], whiteBoost * 0.90f)
        }
        return FloatArray(3) { color[it] * iPhys * 0.85f }
    }

    /**
     * Shades one accepted crossing exactly like steps 1-6 of the disk block of traceRaySample:
     * flared scale height, slab path length through max(|stepDir.z|, 0.065), palette colour, and the
     * three strata (zeta -0.55/0/0.55, weights 0.28/0.44/0.28, optical-depth multiplier 2.2) with the
     * transmittance < 0.008 -> 0 cut-off.
     *
     * @param stepDirZ z component of normalize(pos - prevPos) of the RK4 step that crossed the plane.
     * @param keplerPhase OmegaK(rHit) t; the material azimuth is phiHit - keplerPhase (0: the static disk).
     * @param amplitude u_AnimationAmplitude in [0, 1] (0: no perturbation).
     */
    fun shadeCrossing(
        m: Float,
        rHit: Float,
        phiHit: Float,
        stepDirZ: Float,
        gShift: Float,
        rIn: Float,
        rOut: Float,
        transmittanceIn: Float,
        enableDoppler: Boolean = false,
        keplerPhase: Float = 0.0f,
        amplitude: Float = 0.0f
    ): CrossingShade {
        val h0 = 0.075f * m
        val rNormScale = rHit / max(1.0e-5f, rIn)
        val hR = h0 * rNormScale.pow(1.20f)
        val cosIncidence = max(abs(stepDirZ), 0.065f)
        val fullPathLength = (2.0f * hR) / cosIncidence
        val fNorm = normalizedFlux(m, rHit, rIn)
        val crossingColor = spectrum(fNorm, gShift, enableDoppler)
        val tEff = paletteCoordinate(fNorm, gShift, enableDoppler)

        val zetas = floatArrayOf(-0.55f, 0.0f, 0.55f)
        val weights = floatArrayOf(0.28f, 0.44f, 0.28f)
        val slabStepTau = fullPathLength * 2.20f
        // gargantuaAccumulateDiskCrossing(rHit, phiHit - phaseAdvance, phaseAdvance, ...)
        val phiMaterial = phiHit - keplerPhase
        val perturbation = if (amplitude > 0.0f) materialPerturbationField(rHit, phiMaterial, keplerPhase, rIn) else 0.0f
        val contribution = FloatArray(3)
        var transmittance = transmittanceIn
        for (s in 0 until 3) {
            val optDensity = gasDensity(rHit, phiMaterial, zetas[s], fNorm, rIn, rOut, perturbation, amplitude)
            val segAlpha = 1.0f - exp(-optDensity * slabStepTau * weights[s])
            for (i in 0 until 3) contribution[i] += transmittance * crossingColor[i] * segAlpha
            transmittance *= (1.0f - segAlpha)
            if (transmittance < 0.008f) {
                transmittance = 0.0f
                break
            }
        }
        return CrossingShade(contribution, crossingColor, transmittanceIn, transmittance, fNorm, tEff, paletteInterval(tEff))
    }
}
