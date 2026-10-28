package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Requirement 3: Camera momentum normalization internal consistency.
 *
 * Confirms that the null momentum construction in [CameraModel] simultaneously satisfies:
 * 1. The exact null constraint: H = 1/2 g^μν p_μ p_ν ≡ 0.
 * 2. The exact intended observer-frame ray direction: dx^i/dλ ∥ d^i with positive proportionality.
 * 3. The stationarity normalization of the backward-traced (past-directed) ray: p_0 = +1.0.
 *
 * Crucially, proves that p_0 = +1.0 emerges directly from the uniform positive scalar scaling
 * p_μ = v_μ / v_0 of the past-directed null 4-vector v_μ, without any independent forcing
 * or artificial projection that could distort the physical ray direction or nullity.
 */
class CameraMomentumNormalizationTest {

    private val testSpacetimes = listOf(
        KerrSchildSpacetime(M = 1.0, a = 0.0),    // Schwarzschild
        KerrSchildSpacetime(M = 1.0, a = 0.5),    // Moderate prograde
        KerrSchildSpacetime(M = 1.0, a = -0.7),   // Retrograde
        KerrSchildSpacetime(M = 1.0, a = 0.95),   // Rapid Kerr
        KerrSchildSpacetime(M = 2.5, a = 1.8)     // Scaled mass
    )

    private val observerPositions = listOf(
        Triple(25.0, 0.0, 0.0),       // Equatorial on X axis
        Triple(0.0, 30.0, 0.0),       // Equatorial on Y axis
        Triple(0.0, 0.0, 35.0),       // Polar on Z axis
        Triple(20.0, 15.0, 10.0),     // General oblique position
        Triple(8.0, 6.0, 4.0)         // Closer strong-field observer
    )

    private val rayDirections = listOf(
        Triple(-1.0, 0.0, 0.0),       // Head-on towards origin
        Triple(-0.8, 0.5, 0.2),       // Oblique deflecting ray
        Triple(0.0, -1.0, 0.0),       // Tangential ray
        Triple(0.5, 0.3, -0.8),       // Angled polar ray
        Triple(-0.1, 0.9, -0.4)       // Strong off-axis ray
    )

    @Test
    fun nullConditionAndEnergyNormalizationSatisfiedSimultaneously() {
        for (spacetime in testSpacetimes) {
            for ((posX, posY, posZ) in observerPositions) {
                for ((dirX, dirY, dirZ) in rayDirections) {
                    val photon = CameraModel.createNullStateFromDirection(
                        spacetime = spacetime,
                        X = posX, Y = posY, Z = posZ,
                        dx = dirX, dy = dirY, dz = dirZ
                    )

                    // 1. Stationarity normalization of the backward trace: p_0 must be exactly +1.0
                    assertEquals(
                        "p_0 must be identically +1.0 (past-directed backward trace)",
                        1.0,
                        photon.p_t,
                        1e-15
                    )

                    // 2. Exact null constraint H = 1/2 g^μν p_μ p_ν ≡ 0
                    val h = abs(photon.hamiltonian(spacetime))
                    assertTrue(
                        "Super-Hamiltonian H must be 0 within double precision (got $h) for pos=($posX,$posY,$posZ) dir=($dirX,$dirY,$dirZ)",
                        h < 1e-14
                    )
                }
            }
        }
    }

    @Test
    fun spatialVelocityStrictlyMatchesIntendedRayDirection() {
        for (spacetime in testSpacetimes) {
            for ((posX, posY, posZ) in observerPositions) {
                for ((dirX, dirY, dirZ) in rayDirections) {
                    val normD = sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ)
                    val expectedDx = dirX / normD
                    val expectedDy = dirY / normD
                    val expectedDz = dirZ / normD

                    val photon = CameraModel.createNullStateFromDirection(
                        spacetime = spacetime,
                        X = posX, Y = posY, Z = posZ,
                        dx = dirX, dy = dirY, dz = dirZ
                    )

                    // Physical coordinate velocity: dx^i/dλ = g^{iν} p_ν
                    val v = photon.velocity(spacetime)
                    val vx = v[1]
                    val vy = v[2]
                    val vz = v[3]

                    val vNorm = sqrt(vx * vx + vy * vy + vz * vz)
                    assertTrue("Photon spatial velocity must be non-zero", vNorm > 1e-10)

                    val unitVx = vx / vNorm
                    val unitVy = vy / vNorm
                    val unitVz = vz / vNorm

                    // Cosine of angle between physical velocity and intended direction
                    val cosAngle = unitVx * expectedDx + unitVy * expectedDy + unitVz * expectedDz

                    // Must be strictly parallel: cos(θ) = 1.0 within 1e-14
                    val angleDeviation = abs(1.0 - cosAngle)
                    assertTrue(
                        "Physical ray velocity direction deviates from intended direction by $angleDeviation",
                        angleDeviation < 1e-14
                    )

                    // Backward trace of the received photon: dt/dλ must be strictly negative (past directed)
                    assertTrue("dt/dλ must be negative (past-directed backward trace)", v[0] < 0.0)
                }
            }
        }
    }

    @Test
    fun cameraModelNdcRayConstructionConsistency() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.8)
        val camera = CameraModel(
            posX = 20.0, posY = 10.0, posZ = 5.0,
            targetX = 0.0, targetY = 0.0, targetZ = 0.0,
            fovDegrees = 60.0
        )

        val ndcPoints = listOf(
            Pair(0.0, 0.0),    // Center pixel
            Pair(-1.0, -1.0),  // Bottom-left corner
            Pair(1.0, 1.0),    // Top-right corner
            Pair(-0.5, 0.7),   // Off-center
            Pair(0.8, -0.3)    // Off-center
        )

        for ((ndcX, ndcY) in ndcPoints) {
            val rayDir = camera.computeRayDirection(ndcX, ndcY)
            val photon = camera.createInitialPhotonState(spacetime, ndcX, ndcY)

            // Verify nullity
            val h = abs(photon.hamiltonian(spacetime))
            assertTrue("NDC ($ndcX, $ndcY) ray must be null, got H=$h", h < 1e-14)

            // Verify energy
            assertEquals("NDC ($ndcX, $ndcY) p_0 must be +1.0", 1.0, photon.p_t, 1e-15)

            // Verify direction alignment
            val v = photon.velocity(spacetime)
            val vNorm = sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
            val cosAngle = (v[1] * rayDir.x + v[2] * rayDir.y + v[3] * rayDir.z) / vNorm
            assertEquals("NDC ($ndcX, $ndcY) ray velocity must match rayDir", 1.0, cosAngle, 1e-14)
        }
    }
}
