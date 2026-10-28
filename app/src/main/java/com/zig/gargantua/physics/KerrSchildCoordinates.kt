package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Kerr-Schild Cartesian coordinate transformations and robust radial coordinate solver.
 *
 * COORDINATE SYSTEM ARCHITECTURE:
 * Kerr-Schild Cartesian coordinates (T, X, Y, Z) are horizon-penetrating and non-singular
 * at the event horizon r = r_+ = M + √(M² - a²), unlike Boyer-Lindquist coordinates.
 *
 * Primary Reference:
 *   Kyleyhw "black_hole" repository (Kerr-Schild coordinate formulation).
 * Supporting References:
 *   Kerr, R. P. (1963) Gravitational field of a spinning mass as an example of algebraically special metrics.
 *   SpECTRE: gr::Solutions::KerrSchild coordinate specifications.
 *   Misner, Thorne, Wheeler (1973) §33.2.
 *
 * RADIAL COORDINATE DEFINITION:
 * The Kerr-Schild radial coordinate r is defined implicitly via the oblate spheroidal relation:
 *   (X² + Y²) / (r² + a²) + Z² / r² = 1
 *
 * Expanding into a polynomial in u = r²:
 *   r² (X² + Y²) + (r² + a²) Z² = r² (r² + a²)
 *   r² (X² + Y² + Z²) + a² Z² = r⁴ + a² r²
 * Let R² = X² + Y² + Z² (flat Euclidean squared distance) and S = R² - a².
 * Then:
 *   u² - S u - a² Z² = 0, where u = r².
 *
 * QUADRATIC DISCRIMINANT & BRANCH CONVENTION:
 *   D = S² + 4 a² Z² >= 0
 * Because a² Z² >= 0, √D >= |S|.
 * The positive root for u = r² is:
 *   u = (S + √D) / 2
 * Branch convention:
 *   r >= 0 (outer spacetime foliation).
 *   r = +√u.
 *
 * NUMERICAL STABILITY & CATASTROPHIC CANCELLATION AVOIDANCE:
 * When S = R² - a² < 0 (points inside the toroidal coordinate sphere R < |a|),
 * calculating (S + √D) directly would subtract two nearly equal positive quantities (|S| and √D).
 * We use the algebraically identical rationalized formulation:
 *   (S + √D) = (D - S²) / (√D - S) = 4 a² Z² / (√D + |S|)
 * Thus:
 *   u = (2 a² Z²) / (√D + |S|) = (2 a² Z²) / (√D - S)
 * which involves only addition of strictly positive terms and guarantees full Double-precision accuracy.
 *
 * EDGE CASES:
 * 1. a = 0 (Schwarzschild): S = R², D = R⁴, u = R², r = R = √(X² + Y² + Z²).
 * 2. Origin X=Y=Z=0: R² = 0, S = -a² < 0, Z = 0 -> u = 0, r = 0.
 * 3. Equatorial disc Z = 0, X² + Y² < a²: S < 0, Z = 0 -> u = 0, r = 0 (disk bounded by ring singularity).
 * 4. Equatorial plane Z = 0, X² + Y² >= a²: S >= 0, Z = 0 -> D = S², u = S = X² + Y² - a², r = √(X² + Y² - a²).
 * 5. Polar axis X = Y = 0: R² = Z², S = Z² - a², D = (Z² + a²)², √D = Z² + a², yielding u = Z², r = |Z| for all Z.
 */
object KerrSchildCoordinates {

    /**
     * 4-vector position in Kerr-Schild Cartesian coordinates (T, X, Y, Z).
     */
    data class Cartesian4D(
        val T: Double,
        val X: Double,
        val Y: Double,
        val Z: Double
    ) {
        val spatialNormSquared: Double get() = X * X + Y * Y + Z * Z
        val spatialNorm: Double get() = sqrt(spatialNormSquared)
    }

    /**
     * Solves for the Kerr-Schild radial coordinate r >= 0 in closed form from Cartesian coordinates (X, Y, Z).
     *
     * @param a Spin parameter of the Kerr black hole (|a| <= M).
     * @param X Cartesian X coordinate.
     * @param Y Cartesian Y coordinate.
     * @param Z Cartesian Z coordinate.
     * @return Kerr-Schild radial coordinate r >= 0.
     */
    fun computeR(a: Double, X: Double, Y: Double, Z: Double): Double {
        val a2 = a * a
        val z2 = Z * Z
        val r2D = X * X + Y * Y
        val R2 = r2D + z2

        // Fast path for Schwarzschild (a = 0)
        if (a2 == 0.0) {
            return sqrt(R2)
        }

        val S = R2 - a2
        val D = S * S + 4.0 * a2 * z2
        val sqrtD = sqrt(D)

        val u = if (S >= 0.0) {
            0.5 * (S + sqrtD)
        } else {
            // Catastrophic cancellation avoidance when S < 0
            val denom = sqrtD - S // Note: -S = +|S| > 0, so denom is strictly positive
            if (denom > 0.0) (2.0 * a2 * z2) / denom else 0.0
        }

        return sqrt(max(0.0, u))
    }

    /**
     * Evaluates the oblate spheroidal relation residual:
     *   (X² + Y²) / (r² + a²) + Z² / r² - 1
     * For any valid non-zero r, this residual must be within machine epsilon.
     */
    fun checkCoordinateResidual(a: Double, r: Double, X: Double, Y: Double, Z: Double): Double {
        if (r <= 0.0) return 0.0
        val r2 = r * r
        val a2 = a * a
        val termXY = (X * X + Y * Y) / (r2 + a2)
        val termZ = (Z * Z) / r2
        return (termXY + termZ) - 1.0
    }

    /**
     * Computes the equivalent colatitude θ in [0, π] from Kerr-Schild Cartesian coordinates:
     *   cos θ = Z / r
     */
    fun computeTheta(r: Double, Z: Double): Double {
        if (r <= 0.0) return if (Z >= 0.0) 0.0 else Math.PI
        val cosTheta = (Z / r).coerceIn(-1.0, 1.0)
        return acos(cosTheta)
    }

    /**
     * Computes the Kerr-Schild azimuthal angle φ_KS in (-π, π]:
     * In Kerr-Schild coordinates:
     *   X + i Y = (r + i a) sin θ exp(i φ_KS)
     * Expanding:
     *   (r X + a Y) + i (r Y - a X) = (r² + a²) sin θ exp(i φ_KS)
     * Therefore:
     *   φ_KS = atan2(r Y - a X, r X + a Y)
     */
    fun computePhiKS(a: Double, r: Double, X: Double, Y: Double): Double {
        val numY = r * Y - a * X
        val numX = r * X + a * Y
        if (abs(numX) < 1e-15 && abs(numY) < 1e-15) return 0.0
        return atan2(numY, numX)
    }

    /**
     * Converts Boyer-Lindquist-equivalent coordinates (r, θ, φ_KS) to Kerr-Schild Cartesian (X, Y, Z):
     *   X = (r cos φ - a sin φ) sin θ
     *   Y = (r sin φ + a cos φ) sin θ
     *   Z = r cos θ
     *
     * This mapping is exact and provides the independent cross-validation link to Phase M2.
     */
    fun fromBoyerLindquist(
        a: Double,
        r: Double,
        theta: Double,
        phiKS: Double,
        T: Double = 0.0
    ): Cartesian4D {
        val sinT = sin(theta)
        val cosT = cos(theta)
        val cosP = cos(phiKS)
        val sinP = sin(phiKS)

        val X = (r * cosP - a * sinP) * sinT
        val Y = (r * sinP + a * cosP) * sinT
        val Z = r * cosT

        return Cartesian4D(T = T, X = X, Y = Y, Z = Z)
    }

    /**
     * Kerr-Schild denominator scalar:
     *   Σ_KS = (r⁴ + a² Z²) / r² = r² + a² (Z/r)² = r² + a² cos²θ.
     */
    fun computeSigma(a: Double, r: Double, Z: Double): Double {
        if (r <= 0.0) return a * a
        val cos2Theta = (Z * Z) / (r * r)
        return r * r + a * a * cos2Theta.coerceIn(0.0, 1.0)
    }

    /**
     * Kerr horizon function:
     *   Δ(r) = r² - 2 M r + a².
     */
    fun computeDelta(M: Double, a: Double, r: Double): Double {
        return r * r - 2.0 * M * r + a * a
    }
}
