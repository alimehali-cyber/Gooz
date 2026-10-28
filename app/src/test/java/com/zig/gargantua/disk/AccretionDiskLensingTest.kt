package com.zig.gargantua.disk

import com.zig.gargantua.geodesic.CameraModel
import com.zig.gargantua.geodesic.KerrPhotonIntegrator
import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test

/**
 * Validates Requirement 8 & 10: Gravitational lensing and classification of accretion disk rays.
 *
 * Constructs deterministic rays that produce all four relativistic physical outcomes:
 * 1. Direct disk hit: camera ray hitting the foreground disk.
 * 2. Strongly lensed disk hit: camera ray deflected over the black hole hitting the far back disk.
 * 3. Black-hole capture: camera ray captured into the event horizon (central shadow).
 * 4. Asymptotic background escape: camera ray escaping to the celestial sphere.
 */
class AccretionDiskLensingTest {

    @Test
    fun fourFoldRayClassificationCoversAllPhysicalRegimes() {
        val M = 1.0
        val a = 0.5
        val spacetime = KerrSchildSpacetime(M = M, a = a)
        val disk = AccretionDiskModel(M = M, a = a, outerRadius = 22.0)
        val integrator = KerrPhotonIntegrator(spacetime, disk = disk, escapeRadius = 45.0, maxSteps = 800)

        // Camera positioned at X = -25.0, Y = 0.0, Z = 4.0 (viewing slightly above the disk plane)
        val camX = -25.0
        val camY = 0.0
        val camZ = 4.0

        // 1. Direct foreground disk ray: aimed downwards at Z = 0 in front of the black hole
        val directRay = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = camX, Y = camY, Z = camZ,
            dx = 1.0, dy = 0.4, dz = -0.35 // Aiming directly at foreground disk
        )
        val directRes = integrator.traceRay(directRay)
        assertTrue("Direct foreground ray must hit disk", directRes.isDiskHit)
        assertNotNull("Direct ray must contain disk hit info", directRes.diskHit)
        // Hit must occur between camera and black hole (X_hit < 0)
        assertTrue("Direct foreground disk hit must be in front of black hole (X < 0)", directRes.diskHit!!.hitX < 0.0)

        // 2. Strongly lensed disk ray: aimed slightly above the black hole photon sphere
        // It deflects downwards over the horizon and hits the far side of the disk (X_hit > 0)
        val lensedRay = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = camX, Y = camY, Z = camZ,
            dx = 1.0, dy = 0.0, dz = 0.165 // Deflects over black hole
        )
        val lensedRes = integrator.traceRay(lensedRay)
        assertTrue("Strongly lensed ray must hit far disk", lensedRes.isDiskHit)
        assertNotNull("Lensed ray must contain disk hit info", lensedRes.diskHit)
        // Hit must occur on the far back side of the black hole (X_hit > 0)
        assertTrue("Lensed disk hit must be behind the black hole (X > 0)", lensedRes.diskHit!!.hitX > 0.0)

        // 3. Black hole capture ray: aimed directly into the black hole horizon (central shadow)
        val captureRay = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = camX, Y = camY, Z = camZ,
            dx = 1.0, dy = 0.0, dz = -0.16 // Directly into photon sphere
        )
        val captureRes = integrator.traceRay(captureRay)
        assertTrue("Head-on ray into shadow must be captured by horizon", captureRes.isCaptured)
        assertFalse("Captured ray must not be classified as disk hit", captureRes.isDiskHit)

        // 4. Background escape ray: aimed well above the black hole and disk
        val escapeRay = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = camX, Y = camY, Z = camZ,
            dx = 1.0, dy = 0.0, dz = 0.6 // Clear above the system
        )
        val escapeRes = integrator.traceRay(escapeRay)
        assertTrue("High angle ray must escape to background", escapeRes.isEscaped)
        assertFalse("Escaped ray must not hit disk", escapeRes.isDiskHit)
    }
}
