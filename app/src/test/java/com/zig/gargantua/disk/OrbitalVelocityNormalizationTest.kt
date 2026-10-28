package com.zig.gargantua.disk

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Validates Requirement 5: Relativistic circular orbit 4-velocity normalization.
 *
 * Confirms that the disk material's 4-velocity u^μ = u^0 (1, -Ω Y, Ω X, 0) satisfies:
 *   g_μν u^μ u^ν ≡ -1.0
 * to high numerical precision across diverse radii outside ISCO and different black hole spins.
 */
class OrbitalVelocityNormalizationTest {

    @Test
    fun fourVelocityNormalizationIsStrictlyMinusOne() {
        val testSpacetimes = listOf(
            KerrSchildSpacetime(M = 1.0, a = 0.0),    // Schwarzschild
            KerrSchildSpacetime(M = 1.0, a = 0.5),    // Moderate prograde
            KerrSchildSpacetime(M = 1.0, a = -0.6),   // Retrograde
            KerrSchildSpacetime(M = 1.0, a = 0.92),   // High spin prograde
            KerrSchildSpacetime(M = 1.0, a = -0.92),  // High spin retrograde
            KerrSchildSpacetime(M = 2.5, a = 1.8)     // Scaled mass
        )

        val testAngles = listOf(0.0, Math.PI * 0.25, Math.PI * 0.5, Math.PI * 0.9, Math.PI * 1.4)

        for (spacetime in testSpacetimes) {
            val disk = AccretionDiskModel(M = spacetime.M, a = spacetime.a)
            val isco = disk.innerRadius

            // Radii outside ISCO
            val testRadii = listOf(
                isco + 0.1 * spacetime.M,
                isco + 1.0 * spacetime.M,
                8.0 * spacetime.M,
                15.0 * spacetime.M,
                25.0 * spacetime.M
            )

            for (r in testRadii) {
                for (phi in testAngles) {
                    val X = r * cos(phi)
                    val Y = r * sin(phi)

                    val u = disk.emitterFourVelocity(spacetime, X, Y)

                    // 1. Future-directed: u^0 must be positive
                    assertTrue("u^0 must be strictly positive", u[0] > 0.0)

                    // 2. Metric contraction g_μν u^μ u^ν
                    val g = spacetime.metric(X, Y, 0.0)
                    var norm = 0.0
                    for (mu in 0 until 4) {
                        for (nu in 0 until 4) {
                            norm += g[mu, nu] * u[mu] * u[nu]
                        }
                    }

                    // Must equal -1.0 to machine precision
                    assertEquals(
                        "g_μν u^μ u^ν must be -1.0 within 1e-11 for M=${spacetime.M}, a=${spacetime.a}, r=$r, phi=$phi",
                        -1.0,
                        norm,
                        1e-11
                    )
                }
            }
        }
    }

    @Test
    fun keplerianAngularVelocityMatchesSchwarzschildLimit() {
        val M = 1.0
        val disk = AccretionDiskModel(M = M, a = 0.0)

        for (r in listOf(6.0, 10.0, 20.0)) {
            val omega = disk.keplerianAngularVelocity(r)
            // Schwarzschild: Omega = sqrt(M / r³)
            val expectedOmega = kotlin.math.sqrt(M / (r * r * r))
            assertEquals("Angular velocity must match sqrt(M/r³)", expectedOmega, omega, 1e-14)
        }
    }
}
