package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import kotlin.math.*

/**
 * Mathematically defined relativistic camera model for an observer in Kerr-Schild spacetime.
 *
 * Constructs the initial photon position and 4-momentum p_μ for every ray such that
 * the null Hamiltonian constraint H = 1/2 g^μν p_μ p_ν ≡ 0 is satisfied to machine precision.
 */
data class CameraModel(
    val posX: Double = 30.0,
    val posY: Double = 0.0,
    val posZ: Double = 4.0,
    val targetX: Double = 0.0,
    val targetY: Double = 0.0,
    val targetZ: Double = 0.0,
    val upX: Double = 0.0,
    val upY: Double = 0.0,
    val upZ: Double = 1.0,
    val fovDegrees: Double = 45.0
) {
    /** 3D Vector helpers. */
    data class Vector3(val x: Double, val y: Double, val z: Double) {
        val length: Double get() = sqrt(x * x + y * y + z * z)

        fun normalized(): Vector3 {
            val len = length
            return if (len > 1e-15) Vector3(x / len, y / len, z / len) else Vector3(0.0, 0.0, 1.0)
        }

        fun cross(o: Vector3): Vector3 = Vector3(
            y * o.z - z * o.y,
            z * o.x - x * o.z,
            x * o.y - y * o.x
        )

        operator fun plus(o: Vector3): Vector3 = Vector3(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vector3): Vector3 = Vector3(x - o.x, y - o.y, z - o.z)
        operator fun times(scalar: Double): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)
    }

    val eye = Vector3(posX, posY, posZ)
    val target = Vector3(targetX, targetY, targetZ)
    val forward = (target - eye).normalized()
    val right = forward.cross(Vector3(upX, upY, upZ)).normalized()
    val up = right.cross(forward).normalized()
    val fovScale = tan(Math.toRadians(fovDegrees * 0.5))

    /**
     * Constructs the unit spatial direction vector d pointing from the camera into the scene
     * for a given normalized device coordinate (ndcX, ndcY) in [-1, 1].
     */
    fun computeRayDirection(ndcX: Double, ndcY: Double, aspectRatio: Double = 1.0): Vector3 {
        val dir = forward + (right * (ndcX * fovScale * aspectRatio)) + (up * (ndcY * fovScale))
        return dir.normalized()
    }

    /**
     * Constructs an exact null photon state (H = 0) originating at the camera position
     * with spatial direction d pointing into the scene.
     */
    fun createInitialPhotonState(
        spacetime: KerrSchildSpacetime,
        ndcX: Double,
        ndcY: Double,
        aspectRatio: Double = 1.0
    ): PhotonState4D {
        val d = computeRayDirection(ndcX, ndcY, aspectRatio)
        return createNullStateFromDirection(spacetime, posX, posY, posZ, d.x, d.y, d.z)
    }

    companion object {
        /**
         * Computes canonical 4-momentum p_μ for a photon at position (X, Y, Z) with
         * spatial direction (dx, dy, dz) such that g_μν v^μ v^ν = 0 and p_0 = +1.0.
         * The ray is a backward trace of the received photon (past-directed tangent), identical to
         * the GPU shader ray initialisation in gargantua_geodesic.frag.
         */
        fun createNullStateFromDirection(
            spacetime: KerrSchildSpacetime,
            X: Double,
            Y: Double,
            Z: Double,
            dx: Double,
            dy: Double,
            dz: Double
        ): PhotonState4D {
            val dNorm = sqrt(dx * dx + dy * dy + dz * dz)
            require(dNorm > 1e-15) { "Ray direction cannot be zero" }
            val d = doubleArrayOf(dx / dNorm, dy / dNorm, dz / dNorm)

            val g = spacetime.metric(X, Y, Z)

            val Aw = g[0, 0]
            var Bw = 0.0
            for (i in 0 until 3) {
                Bw += g[0, i + 1] * d[i]
            }

            var Cw = 0.0
            for (i in 0 until 3) {
                for (j in 0 until 3) {
                    Cw += g[i + 1, j + 1] * d[i] * d[j]
                }
            }

            // Discriminant: Dw = Bw² - Aw Cw
            val Dw = Bw * Bw - Aw * Cw
            require(Dw >= 0.0) { "Camera must be outside horizon / ergosphere where D_w >= 0" }

            val sqrtDw = sqrt(max(0.0, Dw))
            // Backward trace: past-directed root with dT/dλ < 0 (Aw = g_00 < 0, so (-Bw + √Dw)/Aw < 0).
            // The future root (-Bw - √Dw)/Aw traces the time-reversed photon and mirrors frame dragging.
            val w = (-Bw + sqrtDw) / Aw
            val v = doubleArrayOf(w, d[0], d[1], d[2])

            // Lower indices v_μ = g_μν v^ν
            val vLower = DoubleArray(4)
            for (mu in 0 until 4) {
                var sum = 0.0
                for (nu in 0 until 4) {
                    sum += g[mu, nu] * v[nu]
                }
                vLower[mu] = sum
            }

            // Normalize all 4 covariant momentum components by the identical physical scale factor
            // s = 1 / vLower[0] (vLower[0] > 0 for the past-directed tangent), ensuring:
            // 1. p_0 = vLower[0] * s = +1.0 (conserved under stationarity)
            // 2. Hamiltonian H = 1/2 g^μν p_μ p_ν = s² * (1/2 g_μν v^μ v^ν) ≡ 0 (exact null preservation)
            // 3. dx^i/dλ = g^{iν} p_ν = s * v^i = s * d^i (exact spatial direction preservation)
            val scale = vLower[0]
            val pt = vLower[0] / scale // Exactly +1.0
            val px = vLower[1] / scale
            val py = vLower[2] / scale
            val pz = vLower[3] / scale

            return PhotonState4D(
                t = 0.0,
                x = X,
                y = Y,
                z = Z,
                p_t = pt,
                p_x = px,
                p_y = py,
                p_z = pz,
                affineLambda = 0.0
            )
        }
    }
}
