package com.zig.gargantua.validation

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Validates the Einstein weak-field gravitational light deflection:
 *   α ≈ 4M / b
 * with second-order post-Newtonian correction.
 */
class WeakFieldDeflectionTest {

    @Test
    fun parameterDefinitionsBetweenImpactParameterAndClosestApproachAreUnambiguous() {
        val M = 1.0

        for (b in listOf(100.0, 200.0, 500.0, 1000.0)) {
            val r0 = WeakFieldDeflection.findClosestApproachRadius(M, b)
            val exact = WeakFieldDeflection.exactSchwarzschildDeflection(M, b)

            // 1. Asymptotic impact parameter b is strictly greater than closest approach r_0
            // due to gravitational curvature bending the ray inwards: b = r_0 / √(1 - 2M/r_0)
            assertTrue("Impact parameter b=$b must strictly exceed closest approach r_0=$r0", b > r0)

            // 2. Both 2PN expansions evaluate with high accuracy:
            // Formula in terms of asymptotic impact parameter b:
            val alphaB = WeakFieldDeflection.secondOrderDeflectionImpactParameter(M, b)
            val errB = abs(exact - alphaB) / exact

            // Formula in terms of periastron / closest approach r_0 (Bodenner & Will 2003):
            val alphaR0 = WeakFieldDeflection.secondOrderDeflectionClosestApproach(M, r0)
            val errR0 = abs(exact - alphaR0) / exact

            // Both expansions should agree with exact numerical quadrature to better than 0.15% at b >= 100M
            assertTrue("2PN(b) relative error must be < 0.15% (observed $errB)", errB < 0.0015)
            assertTrue("2PN(r_0) relative error must be < 0.15% (observed $errR0)", errR0 < 0.0015)

            // At large b, 2PN expansions agree to sub-0.01%
            if (b >= 500.0) {
                assertTrue("2PN(b) at b=$b must be < 0.005% (observed $errB)", errB < 5e-5)
                assertTrue("2PN(r_0) at b=$b must be < 0.005% (observed $errR0)", errR0 < 5e-5)
            }
        }
    }

    @Test
    fun weakFieldApproximationImprovesInverselyWithImpactParameter() {
        val M = 1.0

        val comp100 = WeakFieldDeflection.compare(M, 100.0)
        val comp500 = WeakFieldDeflection.compare(M, 500.0)
        val comp1000 = WeakFieldDeflection.compare(M, 1000.0)

        // At b = 100M: relErr ~ 2.97% (< 3.5%)
        assertTrue(comp100.leadingOrderRelativeError < 0.035)
        // At b = 500M: relErr ~ 0.59% (< 0.8%)
        assertTrue(comp500.leadingOrderRelativeError < 0.008)
        // At b = 1000M: relErr ~ 0.30% (< 0.4%)
        assertTrue(comp1000.leadingOrderRelativeError < 0.004)

        // Monotonic improvement of weak-field approximation as b increases
        assertTrue(
            "Convergence: error at b=1000M (${comp1000.leadingOrderRelativeError}) must be strictly less than b=500M (${comp500.leadingOrderRelativeError})",
            comp1000.leadingOrderRelativeError < comp500.leadingOrderRelativeError
        )
        assertTrue(
            "Convergence: error at b=500M (${comp500.leadingOrderRelativeError}) must be strictly less than b=100M (${comp100.leadingOrderRelativeError})",
            comp500.leadingOrderRelativeError < comp100.leadingOrderRelativeError
        )
    }

    @Test
    fun secondOrderPostNewtonianCorrectionMatchesExactQuadrature() {
        val M = 1.0
        val comp200 = WeakFieldDeflection.compare(M, 200.0)

        // Second-order post-Newtonian correction in terms of impact parameter b has relative error < 0.05% at b = 200M (observed ~0.027%)
        assertTrue(
            "2PN relative error must be < 0.05%, got ${comp200.secondOrderRelativeError * 100}%",
            comp200.secondOrderRelativeError < 0.0005
        )
    }

    @Test
    fun referenceGatePassesDeterministically() {
        val result = GargantuaReferenceGates.verifyWeakFieldDeflection(1.0, 500.0)
        assertTrue("Gate 6 (Weak-field deflection) must pass: ${result.message}", result.passed)
        assertTrue(result.maxObservedError <= result.tolerance)
    }
}
