package com.zig.gargantua.worldline

import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.physics.TimelikeOrbitFactory
import com.zig.gargantua.physics.TimelikeState
import kotlin.math.*

/**
 * Interface representing a physical timelike worldline x^μ(T) in Kerr-Schild Cartesian spacetime.
 *
 * Provides the object's 8D phase-space state at any coordinate time T along its worldline,
 * strictly guaranteeing future-directed timelike motion (u^0 > 0, g_μν u^μ u^ν = -1).
 */
interface RelativisticWorldline {
    val spacetime: KerrSchildSpacetime

    /** Evaluates the state (position, 4-velocity, momentum) at coordinate time T. */
    fun evaluate(T: Double): TimelikeState

    /** Spatial position [X, Y, Z] at coordinate time T. */
    fun positionAt(T: Double): DoubleArray {
        val s = evaluate(T)
        return doubleArrayOf(s.X, s.Y, s.Z)
    }

    /** Contravariant 4-velocity u^μ = (u^0, u^X, u^Y, u^Z) at coordinate time T. */
    fun fourVelocityAt(T: Double): DoubleArray {
        val s = evaluate(T)
        return s.fourVelocity(spacetime)
    }
}

/**
 * Exact analytic relativistic Keplerian circular orbit worldline in Kerr-Schild spacetime.
 *
 * Angular velocity:
 *   Ω = √M / ( r^(3/2) ± a √M )
 * Coordinates:
 *   X(T) = r cos(Ω T + φ_0)
 *   Y(T) = r sin(Ω T + φ_0)
 *   Z(T) = Z_0
 */
class CircularOrbitWorldline(
    override val spacetime: KerrSchildSpacetime,
    val rOrbit: Double,
    val isPrograde: Boolean = true,
    val phi0: Double = 0.0,
    val z0: Double = 0.0
) : RelativisticWorldline {

    val omega: Double

    init {
        require(rOrbit > spacetime.rPlus) { "Orbit radius r=$rOrbit must be outside horizon r+=${spacetime.rPlus}" }
        val M = spacetime.M
        val a = spacetime.a
        val sign = if (isPrograde) 1.0 else -1.0
        val denom = rOrbit.pow(1.5) + sign * a * sqrt(M)
        require(abs(denom) > 1e-12) { "Singular circular orbit denominator" }
        omega = sign * (sqrt(M) / denom)
    }

    override fun evaluate(T: Double): TimelikeState {
        val phi = omega * T + phi0
        val X = rOrbit * cos(phi)
        val Y = rOrbit * sin(phi)
        val Z = z0

        val vx = -omega * Y
        val vy = omega * X
        val vz = 0.0

        return TimelikeOrbitFactory.createFromCoordinateVelocity(
            spacetime = spacetime,
            T = T,
            X = X,
            Y = Y,
            Z = Z,
            vx = vx,
            vy = vy,
            vz = vz,
            tau = 0.0
        )
    }
}

/**
 * Numerically integrated timelike worldline interpolated from discrete M8 states.
 */
class IntegratedTimelikeWorldline(
    override val spacetime: KerrSchildSpacetime,
    val trajectory: List<TimelikeState>
) : RelativisticWorldline {

    init {
        require(trajectory.size >= 2) { "Integrated worldline requires at least 2 state points" }
    }

    override fun evaluate(T: Double): TimelikeState {
        // Clamp to trajectory bounds if outside range
        if (T <= trajectory.first().T) return trajectory.first()
        if (T >= trajectory.last().T) return trajectory.last()

        // Binary search for bracketing interval [T_k, T_{k+1}]
        var low = 0
        var high = trajectory.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (trajectory[mid].T <= T) {
                if (mid == trajectory.size - 1 || trajectory[mid + 1].T > T) {
                    val s0 = trajectory[mid]
                    val s1 = trajectory[mid + 1]
                    val dt = s1.T - s0.T
                    val factor = if (dt > 1e-12) (T - s0.T) / dt else 0.0

                    return TimelikeState(
                        tau = s0.tau + factor * (s1.tau - s0.tau),
                        T = T,
                        X = s0.X + factor * (s1.X - s0.X),
                        Y = s0.Y + factor * (s1.Y - s0.Y),
                        Z = s0.Z + factor * (s1.Z - s0.Z),
                        pT = s0.pT + factor * (s1.pT - s0.pT),
                        pX = s0.pX + factor * (s1.pX - s0.pX),
                        pY = s0.pY + factor * (s1.pY - s0.pY),
                        pZ = s0.pZ + factor * (s1.pZ - s0.pZ)
                    )
                }
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return trajectory.first()
    }
}
