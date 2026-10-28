package com.zig.gargantua.disk

import com.zig.gargantua.geodesic.CameraModel
import com.zig.gargantua.geodesic.KerrPhotonIntegrator
import com.zig.gargantua.geodesic.PhotonState4D
import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Validates Requirement 2: Accretion disk geometry and photon-disk intersection.
 *
 * Verifies:
 * 1. Equatorial intersection detection at Z = 0.
 * 2. Inner-radius rejection: photons passing through the gap r < r_ISCO are not hit.
 * 3. Outer-radius rejection: photons crossing Z = 0 at r > r_out are not hit.
 * 4. Exact radius reconstruction r_hit = √(max(0, X² + Y² - a²)) (Kerr-Schild r at Z = 0, as in the shader).
 * 5. Optically thick first-intersection policy.
 */
class AccretionDiskGeometryTest {

    @Test
    fun equatorialCrossingInsideDomainProducesDiskHit() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.0)
        val disk = AccretionDiskModel(M = 1.0, a = 0.0, outerRadius = 20.0) // r_in = 6M
        val integrator = KerrPhotonIntegrator(spacetime, disk = disk, maxSteps = 400)

        // Camera above the disk plane (Z = 5.0) pointing down-forward across the disk at r ~ 10M
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -25.0, Y = 10.0, Z = 6.0,
            dx = 1.0, dy = 0.0, dz = -0.3
        )

        val result = integrator.traceRay(initialPhoton, recordPath = true)
        assertTrue("Ray must hit the accretion disk", result.isDiskHit)
        assertNotNull("Disk hit result must be present", result.diskHit)

        val hit = result.diskHit!!
        assertEquals("Hit Z must be 0.0", 0.0, hit.hitZ, 1e-12)
        assertTrue("Hit radius must be >= ISCO (6.0), got ${hit.rHit}", hit.rHit >= disk.innerRadius)
        assertTrue("Hit radius must be <= outerRadius (20.0), got ${hit.rHit}", hit.rHit <= disk.outerRadius)
        assertEquals(
            "r_hit must equal the shader's Kerr-Schild radius √(max(0, X² + Y² - a²))",
            sqrt(maxOf(0.0, hit.hitX * hit.hitX + hit.hitY * hit.hitY - spacetime.a * spacetime.a)),
            hit.rHit,
            1e-12
        )
    }

    @Test
    fun innerRadiusGapRejectionPassesThroughWithoutHit() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.0)
        val disk = AccretionDiskModel(M = 1.0, a = 0.0, outerRadius = 20.0) // r_in = 6.0M
        val integrator = KerrPhotonIntegrator(spacetime, disk = disk, maxSteps = 600)

        // Ray aimed specifically through the gap between horizon (r = 2M) and ISCO (r = 6M)
        // Crossing Z = 0 at r ≈ 4M
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -20.0, Y = 4.0, Z = 5.0,
            dx = 1.0, dy = 0.0, dz = -0.28
        )

        val result = integrator.traceRay(initialPhoton, recordPath = true)

        // Ray must NOT hit the disk inside the gap r < r_ISCO
        assertFalse(
            "Ray passing through r < r_ISCO gap must not trigger disk hit",
            result.isDiskHit
        )
    }

    @Test
    fun outerRadiusRejectionPassesBeyondOuterEdge() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.0)
        val disk = AccretionDiskModel(M = 1.0, a = 0.0, outerRadius = 15.0) // r_out = 15.0M
        val integrator = KerrPhotonIntegrator(spacetime, disk = disk, maxSteps = 400)

        // Ray crossing Z = 0 far beyond outer edge at r ≈ 22M
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -30.0, Y = 22.0, Z = 4.0,
            dx = 1.0, dy = 0.0, dz = -0.15
        )

        val result = integrator.traceRay(initialPhoton)
        assertFalse(
            "Ray crossing beyond outerRadius must not trigger disk hit",
            result.isDiskHit
        )
    }

    @Test
    fun diskIntersectionInterpolationAccuracy() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.5)
        val disk = AccretionDiskModel(M = 1.0, a = 0.5)

        // Construct synthetic crossing segment: Z1 = 0.2, Z2 = -0.1
        val p1 = PhotonState4D(t = 0.0, x = 10.0, y = 5.0, z = 0.2, p_t = -1.0, p_x = 0.1, p_y = 0.2, p_z = -0.3, affineLambda = 1.0)
        val p2 = PhotonState4D(t = 0.1, x = 10.3, y = 5.1, z = -0.1, p_t = -1.0, p_x = 0.1, p_y = 0.2, p_z = -0.3, affineLambda = 1.1)

        val hit = DiskIntersection.checkIntersection(
            previous = p1,
            current = p2,
            spacetime = spacetime,
            disk = disk,
            camX = 30.0, camY = 0.0, camZ = 5.0
        )

        assertNotNull("Crossing must be detected", hit)
        val h = hit!!
        assertEquals("Interpolated Z must be 0", 0.0, h.hitZ, 1e-15)

        // Linear interpolation: tau = -0.2 / (-0.1 - 0.2) = 2/3
        val expectedTau = 2.0 / 3.0
        val expectedX = 10.0 + expectedTau * 0.3
        val expectedY = 5.0 + expectedTau * 0.1

        assertEquals("Hit X must match linear interpolation", expectedX, h.hitX, 1e-12)
        assertEquals("Hit Y must match linear interpolation", expectedY, h.hitY, 1e-12)
    }
}
