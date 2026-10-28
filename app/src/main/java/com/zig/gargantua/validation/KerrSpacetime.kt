package com.zig.gargantua.validation

import kotlin.math.*

/**
 * Exact mathematical formulation of Kerr spacetime in Boyer-Lindquist coordinates (t, r, θ, φ).
 *
 * COORDINATE-ROLE SEPARATION ARCHITECTURE:
 * - M2 reference/validation mathematics: Boyer-Lindquist coordinates (t, r, θ, φ).
 * - M3/M4 production renderer: horizon-penetrating Kerr-Schild coordinates (T, X, Y, Z).
 * - This separation is intentional so the production implementation is not validated only against itself.
 *
 * Primary Reference:
 *   Kyleyhw "black_hole" repository (Kerr metric and null geodesic formulation).
 * Supporting References:
 *   - Bardeen, Press, & Teukolsky (1972), ApJ 178, 347 (Equatorial circular photon orbits).
 *   - Chandrasekhar (1983), "The Mathematical Theory of Black Holes", Oxford University Press.
 *   - Misner, Thorne, & Wheeler (1973), "Gravitation", W. H. Freeman.
 *
 * Units: Geometrized units where G = c = 1.
 * Mass M > 0, spin parameter a = J/M, with |a| <= M for non-singular black holes.
 * Metric signature: (-, +, +, +).
 */
data class KerrSpacetime(
    val M: Double = 1.0,
    val a: Double = 0.0
) {
    init {
        require(M > 0.0) { "Black hole mass M must be strictly positive, got M=$M" }
        require(abs(a) <= M) {
            "Kerr black hole condition |a| <= M violated: |$a| > $M (super-extremal naked singularity rejected)"
        }
    }

    /** Dimensionless spin parameter χ = a/M ∈ [-1, 1]. */
    val chi: Double get() = a / M

    /** Whether this spacetime reduces to spherically symmetric Schwarzschild (a = 0). */
    val isSchwarzschild: Boolean get() = abs(a) < 1e-15

    /**
     * Outer event horizon radius:
     * r_+ = M + √(M² - a²)
     */
    val rPlus: Double
        get() = M + sqrt(M * M - a * a)

    /**
     * Inner (Cauchy) horizon radius:
     * r_- = M - √(M² - a²)
     */
    val rMinus: Double
        get() = M - sqrt(M * M - a * a)

    /**
     * Outer ergosphere boundary (static limit) at colatitude θ:
     * r_ergo(θ) = M + √(M² - a² cos²θ)
     */
    fun rErgo(theta: Double): Double {
        val cosT = cos(theta)
        return M + sqrt(M * M - a * a * cosT * cosT)
    }

    /**
     * Schwarzschild photon sphere radius r_ph = 3M (for a = 0).
     */
    val rPhotonSchwarzschild: Double
        get() = 3.0 * M

    /**
     * Schwarzschild critical impact parameter b_crit = 3√3 M (for a = 0).
     * Photons with b < b_crit are captured into the event horizon.
     */
    val bCritSchwarzschild: Double
        get() = 3.0 * sqrt(3.0) * M

    /**
     * Equatorial circular photon orbit radius for co-rotating (prograde) rays (Bardeen, Press, Teukolsky 1972):
     * Rays orbiting in the same angular direction as the black hole spin (sgn(L_z) == sgn(a)):
     * r_ph = 2M [ 1 + cos( 2/3 arccos(-|a|/M) ) ]
     *
     * In Schwarzschild (a = 0): r_ph = 3M.
     * In extremal Kerr (|a| = M): r_ph = M.
     */
    fun rPhotonPrograde(): Double = rPhotonCorotating()
    fun rPhotonCorotating(): Double {
        val frac = (-abs(a) / M).coerceIn(-1.0, 1.0)
        return 2.0 * M * (1.0 + cos((2.0 / 3.0) * acos(frac)))
    }

    /**
     * Equatorial circular photon orbit radius for counter-rotating (retrograde) rays (Bardeen, Press, Teukolsky 1972):
     * Rays orbiting opposite to the black hole spin (sgn(L_z) != sgn(a)):
     * r_ph = 2M [ 1 + cos( 2/3 arccos(+|a|/M) ) ]
     *
     * In Schwarzschild (a = 0): r_ph = 3M.
     * In extremal Kerr (|a| = M): r_ph = 4M.
     */
    fun rPhotonRetrograde(): Double = rPhotonCounterrotating()
    fun rPhotonCounterrotating(): Double {
        val frac = (abs(a) / M).coerceIn(-1.0, 1.0)
        return 2.0 * M * (1.0 + cos((2.0 / 3.0) * acos(frac)))
    }

    /**
     * Equatorial circular photon orbit radius given the sign of axial angular momentum L_z:
     * When sgn(a * signLz) > 0, the ray is co-rotating (r < 3M).
     * When sgn(a * signLz) < 0, the ray is counter-rotating (r > 3M).
     */
    fun rPhotonForSignedLz(signLz: Double): Double {
        val prod = (a * signLz) / M
        val frac = (-prod.coerceIn(-1.0, 1.0))
        return 2.0 * M * (1.0 + cos((2.0 / 3.0) * acos(frac)))
    }

    /**
     * Boyer-Lindquist coordinate auxiliary functions:
     * Σ = r² + a² cos²θ
     * Δ = r² - 2Mr + a²
     * A = (r² + a²)² - Δ a² sin²θ
     */
    fun sigma(r: Double, theta: Double): Double {
        val cosT = cos(theta)
        return r * r + a * a * cosT * cosT
    }

    fun delta(r: Double): Double {
        return r * r - 2.0 * M * r + a * a
    }

    fun bigA(r: Double, theta: Double): Double {
        val r2a2 = r * r + a * a
        val sinT = sin(theta)
        return r2a2 * r2a2 - delta(r) * a * a * sinT * sinT
    }

    /**
     * Inverse metric tensor components g^μν in Boyer-Lindquist coordinates (t, r, θ, φ).
     *
     * g^tt = -A / (Σ Δ)
     * g^rr = Δ / Σ
     * g^θθ = 1 / Σ
     * g^φφ = (Δ - a² sin²θ) / (Σ Δ sin²θ)
     * g^tφ = -2 M a r / (Σ Δ)
     */
    data class InverseMetric(
        val g_tt: Double,
        val g_rr: Double,
        val g_thth: Double,
        val g_phph: Double,
        val g_tph: Double
    )

    fun inverseMetric(r: Double, theta: Double): InverseMetric {
        val sig = sigma(r, theta)
        val del = delta(r)
        val sinT = sin(theta).coerceAtLeast(1e-12)
        val sin2 = sinT * sinT
        val a2 = a * a
        val bigAVal = bigA(r, theta)

        val g_tt = -bigAVal / (sig * del)
        val g_rr = del / sig
        val g_thth = 1.0 / sig
        val g_phph = (del - a2 * sin2) / (sig * del * sin2)
        val g_tph = -2.0 * M * a * r / (sig * del)

        return InverseMetric(g_tt, g_rr, g_thth, g_phph, g_tph)
    }

    /**
     * Numerical partial derivatives of inverse metric tensor:
     * ∂_r g^μν and ∂_θ g^μν via central finite differences.
     */
    data class InverseMetricDerivatives(
        val dr_g_tt: Double,
        val dr_g_rr: Double,
        val dr_g_thth: Double,
        val dr_g_phph: Double,
        val dr_g_tph: Double,
        val dth_g_tt: Double,
        val dth_g_rr: Double,
        val dth_g_thth: Double,
        val dth_g_phph: Double,
        val dth_g_tph: Double
    )

    fun inverseMetricDerivatives(r: Double, theta: Double, h: Double = 1e-6): InverseMetricDerivatives {
        val g_r_plus = inverseMetric(r + h, theta)
        val g_r_minus = inverseMetric(r - h, theta)
        val dr_tt = (g_r_plus.g_tt - g_r_minus.g_tt) / (2.0 * h)
        val dr_rr = (g_r_plus.g_rr - g_r_minus.g_rr) / (2.0 * h)
        val dr_thth = (g_r_plus.g_thth - g_r_minus.g_thth) / (2.0 * h)
        val dr_phph = (g_r_plus.g_phph - g_r_minus.g_phph) / (2.0 * h)
        val dr_tph = (g_r_plus.g_tph - g_r_minus.g_tph) / (2.0 * h)

        val g_th_plus = inverseMetric(r, theta + h)
        val g_th_minus = inverseMetric(r, theta - h)
        val dth_tt = (g_th_plus.g_tt - g_th_minus.g_tt) / (2.0 * h)
        val dth_rr = (g_th_plus.g_rr - g_th_minus.g_rr) / (2.0 * h)
        val dth_thth = (g_th_plus.g_thth - g_th_minus.g_thth) / (2.0 * h)
        val dth_phph = (g_th_plus.g_phph - g_th_minus.g_phph) / (2.0 * h)
        val dth_tph = (g_th_plus.g_tph - g_th_minus.g_tph) / (2.0 * h)

        return InverseMetricDerivatives(
            dr_tt, dr_rr, dr_thth, dr_phph, dr_tph,
            dth_tt, dth_rr, dth_thth, dth_phph, dth_tph
        )
    }
}
