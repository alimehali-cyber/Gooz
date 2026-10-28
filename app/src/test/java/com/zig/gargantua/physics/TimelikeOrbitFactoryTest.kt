package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [TimelikeOrbitFactory].
 */
class TimelikeOrbitFactoryTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    @Test
    fun createFromCoordinateVelocityValidatesMassShellAndFutureDirected() {
        val state = TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            X = 10.0,
            Y = 0.0,
            Z = 0.0,
            vx = 0.0,
            vy = 0.15,
            vz = 0.05
        )

        val u = state.fourVelocity(spacetime)
        assertTrue("Must be future-directed (u^0 > 0)", u[0] > 0.0)

        val residual = TimelikeConservedQuantities.evaluateMassShellResidual(spacetime, state)
        assertTrue("Mass shell residual must be < 1e-10, got $residual", residual < 1e-10)
    }

    @Test
    fun createFromCoordinateVelocityRejectsSuperluminalSpeeds() {
        // v = (0.9, 0.9, 0) clearly exceeds speed of light (v^2 = 1.62 > 1)
        assertThrows(IllegalArgumentException::class.java) {
            TimelikeOrbitFactory.createFromCoordinateVelocity(
                spacetime = spacetime,
                X = 10.0,
                Y = 0.0,
                Z = 0.0,
                vx = 0.9,
                vy = 0.9,
                vz = 0.0
            )
        }
    }

    @Test
    fun createFromSpatialMomentumYieldsFutureDirectedStateAndUnitMass() {
        val state = TimelikeOrbitFactory.createFromSpatialMomentum(
            spacetime = spacetime,
            X = 8.0,
            Y = 2.0,
            Z = 1.0,
            px = 0.0,
            py = 0.3,
            pz = -0.1
        )

        val u = state.fourVelocity(spacetime)
        assertTrue("Must be future-directed", u[0] > 0.0)

        val residual = TimelikeConservedQuantities.evaluateMassShellResidual(spacetime, state)
        assertTrue("Mass shell residual must be < 1e-10, got $residual", residual < 1e-10)
    }

    @Test
    fun createEquatorialCircularOrbitConstructsExactKeplerianMotion() {
        val r = 10.0
        val progradeState = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = r,
            isPrograde = true
        )
        val retrogradeState = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = r,
            isPrograde = false
        )

        // Prograde angular momentum must be positive, retrograde negative
        assertTrue("Prograde L_z must be positive", progradeState.angularMomentumZ > 0.0)
        assertTrue("Retrograde L_z must be negative", retrogradeState.angularMomentumZ < 0.0)

        // Frame dragging effect: prograde Keplerian angular velocity magnitude is smaller than retrograde
        // Ω_prog = √M / ( r^(3/2) + a √M ),  |Ω_retr| = √M / ( r^(3/2) - a √M )
        val omegaProg = 1.0 / (r.pow(1.5) + spacetime.a)
        val omegaRetrMag = 1.0 / (r.pow(1.5) - spacetime.a)
        assertTrue("Prograde frequency magnitude must be smaller than retrograde magnitude", omegaProg < omegaRetrMag)

        val vProg = progradeState.coordinateVelocity(spacetime)
        assertEquals(0.0, vProg[0], 1e-10)
        assertEquals(omegaProg * r, vProg[1], 1e-10)

        val vRetr = retrogradeState.coordinateVelocity(spacetime)
        assertEquals(0.0, vRetr[0], 1e-10)
        assertEquals(-omegaRetrMag * r, vRetr[1], 1e-10)
    }

    @Test
    fun radialInfallFromRestValidatesHorizonAndZeroSpatialVelocity() {
        val state = TimelikeOrbitFactory.createRadialInfallFromRest(
            spacetime = spacetime,
            r0 = 12.0
        )

        val vCoord = state.coordinateVelocity(spacetime)
        assertEquals(0.0, vCoord[0], 1e-10)
        assertEquals(0.0, vCoord[1], 1e-10)
        assertEquals(0.0, vCoord[2], 1e-10)

        assertThrows(IllegalArgumentException::class.java) {
            TimelikeOrbitFactory.createRadialInfallFromRest(
                spacetime = spacetime,
                r0 = spacetime.rPlus - 0.1
            )
        }
    }
}
