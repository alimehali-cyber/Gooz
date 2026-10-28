package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [TimelikeConservedQuantities].
 */
class TimelikeConservedQuantitiesTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    @Test
    fun equatorialPlanarMotionHasZeroCarterConstantQ() {
        val r = 10.0
        val state = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = r,
            isPrograde = true
        )

        val q = TimelikeConservedQuantities.evaluate(spacetime, state)
        assertEquals("Equatorial motion must have Carter Q = 0", 0.0, q.carterConstantQ, 1e-12)
        assertEquals(-0.5, q.hamiltonian, 1e-12)
        assertEquals(0.0, q.massShellResidual, 1e-12)

        // For planar motion, K = (L_z - a E)^2
        val expectedK = (q.angularMomentumZ - spacetime.a * q.energy).pow(2)
        assertEquals(expectedK, q.carterConstantK, 1e-10)
    }

    @Test
    fun nonEquatorialInclinedMotionHasPositiveCarterConstantQ() {
        val state = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            X = 8.0,
            Y = 0.0,
            Z = 4.0,
            vx = 0.0,
            vy = 0.15,
            vz = 0.05
        )

        val q = TimelikeConservedQuantities.evaluate(spacetime, state)
        assertTrue("Inclined non-equatorial motion must have Q > 0, got ${q.carterConstantQ}", q.carterConstantQ > 0.0)
        assertEquals(-0.5, q.hamiltonian, 1e-10)
        assertTrue(q.massShellResidual < 1e-10)
    }
}
