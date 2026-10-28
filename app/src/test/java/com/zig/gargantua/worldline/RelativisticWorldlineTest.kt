package com.zig.gargantua.worldline

import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.physics.TimelikeConservedQuantities
import com.zig.gargantua.physics.TimelikeIntegrator
import com.zig.gargantua.physics.TimelikeOrbitFactory
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [RelativisticWorldline], [CircularOrbitWorldline], and [IntegratedTimelikeWorldline].
 */
class RelativisticWorldlineTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    @Test
    fun circularOrbitWorldlineIsStrictlyTimelikeAndFutureDirected() {
        val rOrbit = 7.0
        val worldline = CircularOrbitWorldline(
            spacetime = spacetime,
            rOrbit = rOrbit,
            isPrograde = true,
            phi0 = 0.0
        )

        // Evaluate at diverse coordinate times
        val testTimes = listOf(0.0, 10.0, 50.0, 120.0, 500.0)
        for (t in testTimes) {
            val state = worldline.evaluate(t)
            val u = worldline.fourVelocityAt(t)

            // 1. Future-directed: u^0 > 0
            assertTrue("Coordinate time velocity u^0 must be positive at T=$t, got ${u[0]}", u[0] > 0.0)

            // 2. Timelike mass-shell normalization: g_μν u^μ u^ν = -1
            val g = spacetime.metric(state.X, state.Y, state.Z)
            var contraction = 0.0
            for (mu in 0 until 4) {
                for (nu in 0 until 4) {
                    contraction += g[mu, nu] * u[mu] * u[nu]
                }
            }
            assertEquals("Metric contraction g_μν u^μ u^ν must equal -1.0 at T=$t", -1.0, contraction, 1e-8)

            // 3. Conserved quantities consistency
            val q = TimelikeConservedQuantities.evaluate(spacetime, state)
            assertEquals("Mass-shell residual must be < 1e-8", 0.0, q.massShellResidual, 1e-8)
            assertEquals("Carter constant Q must be 0 for equatorial planar motion", 0.0, q.carterConstantQ, 1e-10)
            assertTrue("Angular momentum L_z must be positive for prograde orbit", q.angularMomentumZ > 0.0)
        }
    }

    @Test
    fun circularOrbitWorldlineFollowsExactKeplerianFrequency() {
        val rOrbit = 8.0
        val worldline = CircularOrbitWorldline(
            spacetime = spacetime,
            rOrbit = rOrbit,
            isPrograde = true,
            phi0 = 0.0
        )

        val expectedOmega = 1.0 / (rOrbit.pow(1.5) + spacetime.a)
        val period = 2.0 * Math.PI / expectedOmega

        // After one full period, the object returns to its starting coordinate position
        val state0 = worldline.evaluate(0.0)
        val stateT = worldline.evaluate(period)

        assertEquals(state0.X, stateT.X, 1e-8)
        assertEquals(state0.Y, stateT.Y, 1e-8)
        assertEquals(state0.Z, stateT.Z, 1e-8)
    }

    @Test
    fun integratedWorldlineInterpolatesCorrectlyBetweenStates() {
        val integrator = TimelikeIntegrator(spacetime)
        val initial = TimelikeOrbitFactory.createEquatorialCircularOrbit(
            spacetime = spacetime,
            r = 10.0,
            isPrograde = true
        )
        val simResult = integrator.integrate(initial, maxProperTime = 20.0, recordTrajectory = true)
        val trajectory = simResult.trajectory!!

        val worldline = IntegratedTimelikeWorldline(spacetime, trajectory)

        // Test at exact knot points
        for (st in trajectory) {
            val eval = worldline.evaluate(st.T)
            assertEquals(st.X, eval.X, 1e-10)
            assertEquals(st.Y, eval.Y, 1e-10)
            assertEquals(st.Z, eval.Z, 1e-10)
        }

        // Test midpoint interpolation between knot points
        val midT = 0.5 * (trajectory[0].T + trajectory[1].T)
        val midState = worldline.evaluate(midT)
        assertEquals(midT, midState.T, 1e-12)
        assertTrue("Midpoint X must lie between knot values", midState.X <= max(trajectory[0].X, trajectory[1].X) + 1e-5 &&
                midState.X >= min(trajectory[0].X, trajectory[1].X) - 1e-5)
    }
}
