package com.zig.gargantua.physics

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [TimelikeIntegrator].
 */
class TimelikeIntegratorTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    @Test
    fun integratorHonorsExplicitStepLimits() {
        val integrator = TimelikeIntegrator(
            spacetime = spacetime,
            minStep = 0.005,
            maxStep = 0.1,
            stepFactor = 0.05
        )

        // Very close to horizon
        val nearHorizonStep = integrator.computeAdaptiveStepSize(spacetime.rPlus + 0.01)
        assertEquals(0.005, nearHorizonStep, 1e-12)

        // Far from horizon
        val farFieldStep = integrator.computeAdaptiveStepSize(100.0)
        assertEquals(0.1, farFieldStep, 1e-12)
    }

    @Test
    fun singleRk4StepAdvancesProperTimeAndCoordinatesDeterministically() {
        val integrator = TimelikeIntegrator(spacetime)
        val initial = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = 10.0,
            isPrograde = true
        )

        val dt = 0.05
        val next = integrator.rk4Step(initial, dt)

        assertEquals(dt, next.tau, 1e-12)
        assertTrue("Coordinate time T must advance", next.T > initial.T)
        assertTrue(next.isValid)

        val residual = TimelikeConservedQuantities.evaluateMassShellResidual(spacetime, next)
        assertTrue("Mass shell residual after RK4 step must be < 1e-8, got $residual", residual < 1e-8)
    }

    @Test
    fun terminationStatesAreDistinctAndMaxStepsNotConflated() {
        val shortIntegrator = TimelikeIntegrator(
            spacetime = spacetime,
            maxSteps = 5
        )

        val state = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = 10.0,
            isPrograde = true
        )

        val result = shortIntegrator.integrate(state, maxProperTime = 1000.0)
        assertEquals(TimelikeIntegrator.TerminationState.MAX_STEPS, result.terminationState)
        assertFalse("MAX_STEPS must not be flagged as captured", result.isCaptured)
        assertFalse("MAX_STEPS must not be flagged as escaped", result.isEscaped)
        assertTrue(result.isMaxSteps)
    }
}
