package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [TimelikeState].
 */
class TimelikeStateTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    @Test
    fun basicPropertiesAndArraysAreConsistent() {
        val state = TimelikeState(
            tau = 1.5,
            T = 2.0,
            X = 8.0,
            Y = 0.0,
            Z = 2.0,
            pT = -0.95,
            pX = 0.0,
            pY = 0.25,
            pZ = 0.05
        )

        assertEquals(1.5, state.tau, 1e-15)
        assertEquals(2.0, state.T, 1e-15)
        assertEquals(8.0, state.X, 1e-15)
        assertEquals(0.0, state.Y, 1e-15)
        assertEquals(2.0, state.Z, 1e-15)
        assertEquals(0.95, state.energy, 1e-15)
        assertEquals(8.0 * 0.25 - 0.0 * 0.0, state.angularMomentumZ, 1e-15)
        assertEquals(sqrt(8.0 * 8.0 + 2.0 * 2.0), state.sphericalRadius, 1e-12)

        assertArrayEquals(doubleArrayOf(2.0, 8.0, 0.0, 2.0), state.position4D, 1e-15)
        assertArrayEquals(doubleArrayOf(-0.95, 0.0, 0.25, 0.05), state.momentum4D, 1e-15)
        assertArrayEquals(doubleArrayOf(8.0, 0.0, 2.0), state.spatialPosition, 1e-15)
        assertArrayEquals(doubleArrayOf(0.0, 0.25, 0.05), state.spatialMomentum, 1e-15)

        assertTrue(state.isValid)
    }

    @Test
    fun invalidStateDetectedOnNanOrInf() {
        val nanState = TimelikeState(
            tau = 0.0,
            T = 0.0,
            X = Double.NaN,
            Y = 0.0,
            Z = 0.0,
            pT = -1.0,
            pX = 0.0,
            pY = 0.0,
            pZ = 0.0
        )
        assertFalse(nanState.isValid)

        val infState = TimelikeState(
            tau = 0.0,
            T = 0.0,
            X = 10.0,
            Y = 0.0,
            Z = 0.0,
            pT = Double.POSITIVE_INFINITY,
            pX = 0.0,
            pY = 0.0,
            pZ = 0.0
        )
        assertFalse(infState.isValid)
    }

    @Test
    fun fourVelocityAndCoordinateVelocityAreConsistent() {
        val state = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            X = 10.0,
            Y = 0.0,
            Z = 0.0,
            vx = 0.0,
            vy = 0.2,
            vz = 0.0
        )

        val u = state.fourVelocity(spacetime)
        assertTrue("u^0 must be future-directed (> 0)", u[0] > 0.0)

        val vCoord = state.coordinateVelocity(spacetime)
        assertEquals(0.0, vCoord[0], 1e-10)
        assertEquals(0.2, vCoord[1], 1e-10)
        assertEquals(0.0, vCoord[2], 1e-10)
    }
}
