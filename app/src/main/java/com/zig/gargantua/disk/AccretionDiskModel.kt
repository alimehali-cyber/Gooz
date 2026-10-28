package com.zig.gargantua.disk

import com.zig.gargantua.geodesic.ProceduralSky
import com.zig.gargantua.physics.KerrSchildSpacetime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Physical model of a thin, relativistic equatorial accretion disk in Kerr spacetime.
 *
 * GOVERNING PHYSICS:
 * 1. Geometry: Thin equatorial disk at θ = π/2 (Z = 0) spanning from the physical
 *    Innermost Stable Circular Orbit (r_in = r_ISCO) out to configurable r_out.
 * 2. Emissivity: Standard Novikov-Thorne / Shakura-Sunyaev thin-disk-inspired radial profile:
 *      F(r) = (M / r³) * [ 1 - √(r_in / r) ]
 *    vanishing smoothly at the torque-free inner edge r_in and falling off as r⁻³ asymptotically.
 * 3. Relativistic Orbital Motion: Keplerian circular-orbit angular velocity:
 *      Ω(r) = √M / ( r^(3/2) + a √M )
 *    with contravariant 4-velocity u^μ = u^0 (1, -Ω Y, Ω X, 0) strictly normalized via g_μν u^μ u^ν = -1.
 * 4. Frequency Shift (Doppler & Gravitational):
 *      g = ν_obs / ν_emit = (-p_μ u_obs^μ) / (-p_μ u_emit^μ) = [ 1 / √(-g_00(x_obs)) ] / [ u^0 (1 - Ω L_z) ]
 * 5. Radiance & Spectral Appearance:
 *      I_obs = g⁴ F(r),  T_obs = g T_emit(r)
 */
data class AccretionDiskModel(
    val M: Double = 1.0,
    val a: Double = 0.8,
    val innerRadius: Double = KerrIsco.compute(M, a),
    val outerRadius: Double = 22.0 * M,
    val fluxScale: Double = 50.0
) {
    init {
        require(innerRadius > 0.0) { "Inner disk radius must be positive" }
        require(outerRadius > innerRadius) { "Outer radius ($outerRadius) must exceed inner radius ($innerRadius)" }
    }

    /** ISCO radius alias for innerRadius. */
    val rIsco: Double get() = innerRadius

    /** Outer disk radius alias for outerRadius. */
    val rOut: Double get() = outerRadius

    /** Checks whether coordinate radius r lies within the active disk domain. */
    fun containsRadius(r: Double): Boolean = r in innerRadius..outerRadius

    /**
     * Radial flux profile inspired by the Novikov-Thorne thin-disk model with torque-free inner boundary:
     * F(r) = (M / r³) * [ 1 - √(r_in / r) ]
     */
    fun fluxProfile(r: Double): Double {
        if (r <= innerRadius || r > outerRadius) return 0.0
        val rRatio = innerRadius / r
        val r3 = r * r * r
        return (M / r3) * max(0.0, 1.0 - sqrt(rRatio))
    }

    /**
     * Normalized flux profile in [0, 1] relative to peak Novikov-Thorne emissivity.
     */
    fun normalizedFlux(r: Double): Double {
        val f = fluxProfile(r)
        val rPeak = 1.361111 * innerRadius
        val fPeak = M / (7.0 * rPeak * rPeak * rPeak)
        return if (fPeak > 1e-7) (f / fPeak).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * Analytical vertical scale height H(r) = h0 * (r / r_in)^beta (Thorne & DNeg 2015).
     */
    fun scaleHeight(r: Double, h0: Double = 0.045, beta: Double = 1.15): Double {
        require(r > 0.0) { "Radius must be positive" }
        val rIn = rIsco
        val normR = r / rIn
        return (h0 * M) * normR.pow(beta)
    }

    /**
     * Optical depth segment computation for a slab traversal.
     */
    fun computeSlabOpticalDepth(
        r: Double,
        cosIncidence: Double,
        h0: Double = 0.045,
        beta: Double = 1.15,
        baseOpacity: Double = 12.0
    ): Double {
        val h = scaleHeight(r, h0, beta)
        val clampedCos = max(abs(cosIncidence), 0.065)
        val pathLength = (2.0 * h) / clampedCos
        val fNorm = normalizedFlux(r)
        val radialWindow = ((r - rIsco) / 0.25).coerceIn(0.0, 1.0) * ((rOut - r) / 2.0).coerceIn(0.0, 1.0)
        val density = (0.55 + 0.45 * fNorm) * radialWindow
        return density * pathLength * baseOpacity
    }

    /** Emitted local rest-frame effective temperature T_emit(r) ∝ F(r)^(1/4). */
    fun emittedTemperature(r: Double): Double {
        val f = fluxProfile(r)
        return if (f > 0.0) f.pow(0.25) else 0.0
    }

    /**
     * Relativistic Keplerian circular-orbit angular velocity Ω = dφ/dt in Kerr spacetime:
     * Ω = √M / ( r^(3/2) + a √M )
     */
    fun keplerianAngularVelocity(r: Double): Double {
        require(r > 0.0) { "Radius must be positive" }
        val denom = r.pow(1.5) + a * sqrt(M)
        require(abs(denom) > 1e-15) { "Singular angular velocity denominator" }
        return sqrt(M) / denom
    }

    /**
     * Kerr-Schild radius r of an equatorial point (X, Y, 0): r = sqrt(max(0, X² + Y² - a²)), the same
     * expression as gargantua_geodesic.frag (rHit). For a != 0 this differs from sqrt(X² + Y²).
     */
    fun equatorialRadius(X: Double, Y: Double): Double = sqrt(max(0.0, X * X + Y * Y - a * a))

    /**
     * Constructs the physical 4-velocity u^μ of the disk material at Cartesian equatorial coordinates (X, Y, 0).
     * u^μ = u^0 (1, -Ω Y, Ω X, 0) normalized such that g_μν u^μ u^ν = -1.
     *
     * @return 4-vector [u^0, u^X, u^Y, u^Z].
     */
    fun emitterFourVelocity(spacetime: KerrSchildSpacetime, X: Double, Y: Double): DoubleArray {
        val r = equatorialRadius(X, Y)
        val omega = keplerianAngularVelocity(r)

        val vx = -omega * Y
        val vy = omega * X
        val vz = 0.0
        val v = doubleArrayOf(1.0, vx, vy, vz)

        val g = spacetime.metric(X, Y, 0.0)

        // Evaluate contraction g_μν v^μ v^ν
        var denom = 0.0
        for (mu in 0 until 4) {
            for (nu in 0 until 4) {
                denom += g[mu, nu] * v[mu] * v[nu]
            }
        }

        require(denom < 0.0) {
            "Circular orbit velocity exceeds speed of light or enters ergosphere instability at r=$r (g_μν v^μ v^ν = $denom)"
        }

        val u0 = 1.0 / sqrt(-denom)
        return doubleArrayOf(u0, u0 * vx, u0 * vy, u0 * vz)
    }

    /**
     * Invariant relativistic frequency shift g = ν_obs / ν_emit.
     *
     * g = (-p_μ u_obs^μ) / (-p_μ u_emit^μ)
     *
     * @param spacetime Kerr-Schild metric.
     * @param hitX Cartesian X coordinate of disk intersection.
     * @param hitY Cartesian Y coordinate of disk intersection.
     * @param rayPx Covariant momentum p_X of the ray.
     * @param rayPy Covariant momentum p_Y of the ray.
     * @param camX Observer position X.
     * @param camY Observer position Y.
     * @param camZ Observer position Z.
     */
    fun frequencyShift(
        spacetime: KerrSchildSpacetime,
        hitX: Double,
        hitY: Double,
        rayPx: Double,
        rayPy: Double,
        camX: Double,
        camY: Double,
        camZ: Double
    ): Double {
        val rHit = equatorialRadius(hitX, hitY)
        val omega = keplerianAngularVelocity(rHit)
        val uEmit = emitterFourVelocity(spacetime, hitX, hitY)
        val u0 = uEmit[0]

        // Stationarity energy p_0 = -1.0
        // Physical photon emitted from disk towards camera:
        // -p_μ u_emit^μ = u^0 * [ 1 - Ω * (hitY * rayPx - hitX * rayPy) ]
        val lz = hitX * rayPy - hitY * rayPx
        val denom = u0 * (1.0 + omega * lz)

        // Static observer at camera position
        val gCam = spacetime.metric(camX, camY, camZ)
        val uObs0 = 1.0 / sqrt(max(1e-12, -gCam[0, 0]))

        return if (abs(denom) > 1e-12) uObs0 / denom else 1.0
    }

    /**
     * Observed bolometric radiance according to Liouville's theorem:
     * I_obs = g⁴ * F(r) * fluxScale
     */
    fun observedRadiance(r: Double, g: Double): Double {
        val f = fluxProfile(r)
        val gClamped = g.coerceIn(0.01, 8.0)
        val g4 = gClamped * gClamped * gClamped * gClamped
        return g4 * f * fluxScale
    }

    /**
     * Scientifically interpretable thermal blackbody color mapping.
     * Converts effective observed temperature T_obs = g * T_emit to physical RGB.
     * Doppler-boosted regions (high T_obs) appear hot white-blue;
     * Doppler-dimmed and gravitationally redshifted regions appear deep orange-red.
     */
    fun spectralColor(tObs: Double, radiance: Double): ProceduralSky.ColorRGB {
        // Temperature response curve (normalized units)
        // tObs typically in range ~0.05 (cool/redshifted) to 0.50 (hot/blueshifted)
        val tNorm = (tObs * 4.0).coerceIn(0.0, 2.5)

        // Thermal blackbody color ramp
        val r = (1.0 + 0.3 * tNorm).coerceIn(0.0, 1.5)
        val g = (tNorm * tNorm * 0.45 + tNorm * 0.25).coerceIn(0.0, 1.2)
        val b = (tNorm * tNorm * tNorm * 0.35).coerceIn(0.0, 1.2)

        val intensity = radiance.coerceIn(0.0, 2.0)
        return ProceduralSky.ColorRGB(
            (r * intensity).coerceIn(0.0, 1.0),
            (g * intensity).coerceIn(0.0, 1.0),
            (b * intensity).coerceIn(0.0, 1.0)
        )
    }

    companion object {
        /**
         * Computes optical depth segment for a default accretion disk model.
         */
        fun computeSlabOpticalDepth(
            r: Double,
            cosIncidence: Double,
            M: Double = 1.0,
            a: Double = 0.8
        ): Double {
            val model = AccretionDiskModel(M = M, a = a)
            return model.computeSlabOpticalDepth(r, cosIncidence)
        }
    }
}
