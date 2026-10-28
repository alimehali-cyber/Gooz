package com.zig.chal.physics

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Kerr metric closed-form solutions.
 *
 * Verbatim port of the reference engine's `src/physics/kerr-metric.ts`. Used by the Chal
 * telemetry HUD and by the JVM test-suite gates; the GPU path evaluates the equivalent
 * expressions inside the ray-marching shader (`ChalShaderSource.CHAL_METRIC_CHUNK`).
 *
 * Geometric units: G = c = 1.
 */
object ChalKerrMetric {

    /**
     * Outer event horizon r+ = M + sqrt(M^2 - a^2).
     *
     * @param mass black hole mass M in solar masses (normalized).
     * @param spin dimensionless spin parameter a* = J / M^2, clamped to [-1, 1].
     */
    fun calculateEventHorizon(mass: Double, spin: Double): Double {
        val a = spin.coerceIn(-1.0, 1.0)
        val value = 1.0 - a * a
        return mass * (1.0 + sqrt(if (value < 0.0) 0.0 else value))
    }

    /**
     * Prograde photon sphere, r_ph = 2M * [1 + cos(2/3 * acos(-a*))].
     *
     * @param spin dimensionless spin parameter a* = J / M^2, clamped to [-1, 1].
     */
    fun calculatePhotonSphere(mass: Double, spin: Double): Double {
        val a = spin.coerceIn(-1.0, 1.0)
        val term = (2.0 / 3.0) * acos(-a)
        return 2.0 * mass * (1.0 + cos(term))
    }

    /**
     * Exact ISCO (Bardeen, Press & Teukolsky 1972).
     *
     * Prograde (co-rotating) orbits have the smaller ISCO, so the root term is subtracted.
     *
     * @param prograde true for the co-rotating disk used by this simulation.
     */
    fun calculateIsco(mass: Double, spin: Double, prograde: Boolean): Double {
        val a = spin.coerceIn(-1.0, 1.0)
        if (abs(a) < 1e-6) return mass * 6.0

        val a2 = a * a
        val t1 = cbrt(1.0 - a2)
        val t2 = cbrt(1.0 + a)
        val t3 = cbrt(1.0 - a)
        val z1 = 1.0 + t1 * (t2 + t3)

        val z2 = sqrt(3.0 * a2 + z1 * z1)

        val sign = if (prograde) -1.0 else 1.0

        val disc = (3.0 - z1) * (3.0 + z1 + 2.0 * z2)
        val root = if (disc < 0.0) 0.0 else sqrt(disc)

        return mass * (3.0 + z2 + sign * root)
    }

    /**
     * Schwarzschild time dilation at radius r: sqrt(1 - 2M/r).
     *
     * @return the 1/sqrt(-g_tt) time-dilation factor, or 0 inside the horizon.
     */
    fun calculateTimeDilation(radius: Double, mass: Double): Double {
        val value = 1.0 - (2.0 * mass) / radius
        return if (value < 0.0) 0.0 else sqrt(value)
    }
}
