package com.zig.gargantua.physics

import kotlin.math.*

/**
 * Deterministic factory for constructing physically valid, normalized 8D timelike states
 * in Kerr-Schild Cartesian spacetime for unit rest mass test particles (m = 1).
 *
 * GUARANTEES & ACCEPTANCE CRITERIA:
 * 1. Normalized: Contraction g_μν u^μ u^ν = -1 and g^μν p_μ p_ν = -1 to machine precision.
 * 2. Future-directed: Coordinate time rate u^0 = dT/dτ > 0.
 * 3. Subluminal: Rejects any spatial velocity or state where g_μν v^μ v^ν >= 0.
 * 4. Kerr-aligned: Uses the exact production KerrSchildSpacetime metric tensors.
 */
object TimelikeOrbitFactory {

    /**
     * Constructs a normalized timelike state from spatial coordinate position (X, Y, Z)
     * and coordinate 3-velocity v^i = dx^i / dT.
     *
     * @param spacetime Production Kerr-Schild metric.
     * @param T Coordinate time (typically 0.0).
     * @param X Cartesian X position.
     * @param Y Cartesian Y position.
     * @param Z Cartesian Z position.
     * @param vx Coordinate velocity dx/dT.
     * @param vy Coordinate velocity dy/dT.
     * @param vz Coordinate velocity dz/dT.
     * @param tau Initial proper time (default 0.0).
     * @return Normalized [TimelikeState].
     * @throws IllegalArgumentException if the trajectory is not timelike (superluminal or null).
     */
    fun createFromCoordinateVelocity(
        spacetime: KerrSchildSpacetime,
        T: Double = 0.0,
        X: Double,
        Y: Double,
        Z: Double,
        vx: Double,
        vy: Double,
        vz: Double,
        tau: Double = 0.0
    ): TimelikeState {
        val g = spacetime.metric(X, Y, Z)
        val v = doubleArrayOf(1.0, vx, vy, vz)

        // Contraction A = g_μν V^μ V^ν
        var contraction = 0.0
        for (mu in 0 until 4) {
            for (nu in 0 until 4) {
                contraction += g[mu, nu] * v[mu] * v[nu]
            }
        }

        require(contraction < -1e-15) {
            "Velocity is superluminal or null: g_μν V^μ V^ν = $contraction (must be strictly negative for timelike motion)"
        }

        // u^0 = 1 / √(-g_μν V^μ V^ν) > 0 (future-directed)
        val u0 = 1.0 / sqrt(-contraction)
        val u = doubleArrayOf(u0, u0 * vx, u0 * vy, u0 * vz)

        // Covariant momentum p_μ = g_μν u^ν (for m = 1)
        val p = DoubleArray(4)
        for (mu in 0 until 4) {
            var sum = 0.0
            for (nu in 0 until 4) {
                sum += g[mu, nu] * u[nu]
            }
            p[mu] = sum
        }

        val state = TimelikeState(
            tau = tau,
            T = T,
            X = X,
            Y = Y,
            Z = Z,
            pT = p[0],
            pX = p[1],
            pY = p[2],
            pZ = p[3]
        )

        // Validate mass-shell condition
        val residual = TimelikeConservedQuantities.evaluateMassShellResidual(spacetime, state)
        require(residual < 1e-8) {
            "Mass-shell condition violated upon construction: residual = $residual"
        }

        return state
    }

    /**
     * Constructs a normalized timelike state from spatial coordinate position (X, Y, Z)
     * and spatial covariant momentum components (p_X, p_Y, p_Z).
     * Solves analytically for the future-directed energy component p_0 = p_T such that
     * g^μν p_μ p_ν = -1.
     *
     * @param spacetime Production Kerr-Schild metric.
     * @param T Coordinate time.
     * @param X Cartesian X position.
     * @param Y Cartesian Y position.
     * @param Z Cartesian Z position.
     * @param px Covariant spatial momentum p_X.
     * @param py Covariant spatial momentum p_Y.
     * @param pz Covariant spatial momentum p_Z.
     * @param tau Initial proper time.
     * @return Normalized [TimelikeState].
     * @throws IllegalArgumentException if no real timelike solution exists for p_0.
     */
    fun createFromSpatialMomentum(
        spacetime: KerrSchildSpacetime,
        T: Double = 0.0,
        X: Double,
        Y: Double,
        Z: Double,
        px: Double,
        py: Double,
        pz: Double,
        tau: Double = 0.0
    ): TimelikeState {
        val gInv = spacetime.inverseMetric(X, Y, Z)
        val spatialP = doubleArrayOf(px, py, pz)

        // g^μν p_μ p_ν = g^00 p_0² + 2 (∑_i g^0i p_i) p_0 + ∑_{i,j} g^ij p_i p_j = -1
        // A p_0² + 2 B p_0 + C = 0 where:
        val A = gInv[0, 0]
        var B = 0.0
        for (i in 1..3) {
            B += gInv[0, i] * spatialP[i - 1]
        }
        var sumSpatial = 0.0
        for (i in 1..3) {
            for (j in 1..3) {
                sumSpatial += gInv[i, j] * spatialP[i - 1] * spatialP[j - 1]
            }
        }
        val C = sumSpatial + 1.0

        val discriminant = B * B - A * C
        require(discriminant >= 0.0) {
            "No real timelike solution for spatial momentum ($px, $py, $pz) at ($X, $Y, $Z): discriminant = $discriminant"
        }

        val sqrtD = sqrt(discriminant)
        // For Kerr-Schild metric, g^00 = -1 - 2H < 0, so A < 0.
        // We require future-directed motion: u^0 = g^0ν p_ν = A p_0 + B > 0.
        // Root 1: p_0 = (-B + √D) / A  =>  A p_0 + B = +√D > 0 (future-directed).
        val p0 = (-B + sqrtD) / A

        val state = TimelikeState(
            tau = tau,
            T = T,
            X = X,
            Y = Y,
            Z = Z,
            pT = p0,
            pX = px,
            pY = py,
            pZ = pz
        )

        val u = state.fourVelocity(spacetime)
        require(u[0] > 0.0) {
            "Constructed state is not future-directed: u^0 = ${u[0]}"
        }

        val residual = TimelikeConservedQuantities.evaluateMassShellResidual(spacetime, state)
        require(residual < 1e-8) {
            "Mass-shell condition violated upon construction: residual = $residual"
        }

        return state
    }

    /**
     * Constructs a stable or bound equatorial circular orbit state at coordinate radius r.
     * Uses the exact Keplerian orbital frequency in Kerr spacetime:
     *   Ω = √M / ( r^(3/2) ± a √M )
     * where + corresponds to prograde (co-rotating) and - to retrograde (counter-rotating).
     *
     * @param spacetime Production Kerr-Schild metric.
     * @param r Coordinate radius in the equatorial plane.
     * @param isPrograde True for prograde orbit (co-rotating with spin +Z), false for retrograde.
     * @param T Coordinate time.
     * @param phi0 Initial azimuthal angle φ_0.
     * @param tau Initial proper time.
     */
    fun createEquatorialCircularOrbit(
        spacetime: KerrSchildSpacetime,
        r: Double,
        isPrograde: Boolean = true,
        T: Double = 0.0,
        phi0: Double = 0.0,
        tau: Double = 0.0
    ): TimelikeState {
        require(r > 0.0) { "Orbital radius must be positive, got r=$r" }
        val M = spacetime.M
        val a = spacetime.a
        val sign = if (isPrograde) 1.0 else -1.0
        val denom = r.pow(1.5) + sign * a * sqrt(M)
        require(abs(denom) > 1e-12) { "Singular circular orbit denominator at r=$r" }

        val omega = sign * (sqrt(M) / denom)
        val X = r * cos(phi0)
        val Y = r * sin(phi0)
        val Z = 0.0

        // In equatorial circular motion: dx/dt = -Ω Y, dy/dt = Ω X, dz/dt = 0
        val vx = -omega * Y
        val vy = omega * X
        val vz = 0.0

        return createFromCoordinateVelocity(
            spacetime = spacetime,
            T = T,
            X = X,
            Y = Y,
            Z = Z,
            vx = vx,
            vy = vy,
            vz = vz,
            tau = tau
        )
    }

    /**
     * Constructs a radial infall state initialized from rest at coordinate radius r0.
     */
    fun createRadialInfallFromRest(
        spacetime: KerrSchildSpacetime,
        r0: Double,
        theta0: Double = Math.PI / 2.0,
        phi0: Double = 0.0,
        T: Double = 0.0,
        tau: Double = 0.0
    ): TimelikeState {
        require(r0 > spacetime.rPlus) { "Initial radius r0=$r0 must be outside event horizon r+=${spacetime.rPlus}" }
        val X = r0 * sin(theta0) * cos(phi0)
        val Y = r0 * sin(theta0) * sin(phi0)
        val Z = r0 * cos(theta0)

        // Initial 3-velocity is zero (released from rest)
        return createFromCoordinateVelocity(
            spacetime = spacetime,
            T = T,
            X = X,
            Y = Y,
            Z = Z,
            vx = 0.0,
            vy = 0.0,
            vz = 0.0,
            tau = tau
        )
    }
}
