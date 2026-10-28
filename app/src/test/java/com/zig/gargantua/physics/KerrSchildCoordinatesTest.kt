package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates the Kerr-Schild Cartesian radial coordinate r(X, Y, Z) and coordinate relations.
 */
class KerrSchildCoordinatesTest {

    @Test
    fun schwarzschildReductionYieldsEuclideanRadius() {
        val a = 0.0
        val testPoints = listOf(
            Triple(1.0, 0.0, 0.0),
            Triple(0.0, 5.0, 0.0),
            Triple(0.0, 0.0, 3.0),
            Triple(3.0, 4.0, 12.0),
            Triple(100.0, 200.0, 300.0),
            Triple(0.001, 0.002, 0.003)
        )

        for ((x, y, z) in testPoints) {
            val expectedR = sqrt(x * x + y * y + z * z)
            val actualR = KerrSchildCoordinates.computeR(a, x, y, z)
            assertEquals("At a=0, r must equal Euclidean norm", expectedR, actualR, 1e-13)
        }
    }

    @Test
    fun originEvaluatesToZeroRadius() {
        val rZero = KerrSchildCoordinates.computeR(a = 0.0, X = 0.0, Y = 0.0, Z = 0.0)
        assertEquals("Origin at a=0 must give r=0", 0.0, rZero, 1e-15)

        val rKerr = KerrSchildCoordinates.computeR(a = 0.9, X = 0.0, Y = 0.0, Z = 0.0)
        assertEquals("Origin at a=0.9 must give r=0", 0.0, rKerr, 1e-15)
    }

    @Test
    fun polarAxisEvaluatesToAbsoluteZ() {
        // On the symmetry axis (X=0, Y=0), (X²+Y²)/(r²+a²) + Z²/r² = 1 implies Z²/r² = 1, so r = |Z|.
        val spins = listOf(0.0, 0.3, 0.7, 0.99, 1.0)
        val zValues = listOf(-100.0, -5.0, -0.9, -0.1, 0.0, 0.1, 0.9, 5.0, 100.0)

        for (a in spins) {
            for (z in zValues) {
                val r = KerrSchildCoordinates.computeR(a, X = 0.0, Y = 0.0, Z = z)
                val expected = abs(z)
                assertEquals("On the polar axis, r must equal |Z| for spin a=$a", expected, r, 1e-13)
            }
        }
    }

    @Test
    fun equatorialPlaneDiskAndExteriorBranches() {
        val a = 2.0
        val z = 0.0

        // Inside equatorial disk (X² + Y² < a²): r = 0 (bounded by ring singularity at R = a)
        val rInside = KerrSchildCoordinates.computeR(a, X = 1.0, Y = 1.0, Z = z)
        assertEquals("Inside equatorial disc R < a, r must equal 0", 0.0, rInside, 1e-15)

        // Outside equatorial disk (X² + Y² >= a²): r = √(X² + Y² - a²)
        val rOutside = KerrSchildCoordinates.computeR(a, X = 3.0, Y = 4.0, Z = z)
        val expected = sqrt(3.0 * 3.0 + 4.0 * 4.0 - a * a) // √(25 - 4) = √21
        assertEquals("Outside equatorial disc R >= a, r must equal √(R² - a²)", expected, rOutside, 1e-13)
    }

    @Test
    fun oblateSpheroidalRelationIsSatisfiedEverywhere() {
        val spins = listOf(0.1, 0.5, 0.8, 0.99)
        val radii = listOf(1.5, 3.0, 10.0, 50.0)
        val thetas = listOf(0.1, 0.5, 1.0, 1.57, 2.5)
        val phis = listOf(0.0, 0.78, 1.57, 3.14, 4.5)

        for (a in spins) {
            for (rExpected in radii) {
                for (th in thetas) {
                    for (ph in phis) {
                        val pos = KerrSchildCoordinates.fromBoyerLindquist(a, rExpected, th, ph)
                        val rComputed = KerrSchildCoordinates.computeR(a, pos.X, pos.Y, pos.Z)

                        // 1. Recovered radius matches input radius to high precision
                        assertEquals("Recovered radius must match input radius", rExpected, rComputed, 1e-12)

                        // 2. Oblate spheroidal equation residual must be zero
                        val residual = KerrSchildCoordinates.checkCoordinateResidual(a, rComputed, pos.X, pos.Y, pos.Z)
                        assertEquals("Oblate spheroidal equation residual must be < 1e-12", 0.0, residual, 1e-12)

                        // 3. Branch convention: r is strictly non-negative
                        assertTrue("Radius branch must be non-negative", rComputed >= 0.0)
                    }
                }
            }
        }
    }

    @Test
    fun nearExtremalSpinRemainsAccurate() {
        val a = 0.999999
        val pos = KerrSchildCoordinates.fromBoyerLindquist(a, r = 2.0, theta = 0.8, phiKS = 1.2)
        val r = KerrSchildCoordinates.computeR(a, pos.X, pos.Y, pos.Z)
        assertEquals("Near-extremal radius calculation must be accurate", 2.0, r, 1e-12)
        assertFalse("Radius must be finite", r.isNaN() || r.isInfinite())
    }
}
