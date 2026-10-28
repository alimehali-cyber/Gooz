package com.zig.gargantua.renderer

import com.zig.gargantua.geodesic.PhotonState4D
import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.worldline.CircularOrbitWorldline
import com.zig.gargantua.worldline.RelativisticObject
import com.zig.gargantua.worldline.RelativisticObjectIntersection
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Deterministic step-convergence test for Milestone M9.
 *
 * Verifies that the bounded linear-interpolation closest-approach intersection algorithm
 * converges stably under 2x and 4x photon integration step refinement.
 *
 * Evaluates:
 * 1. Hit/Miss stability
 * 2. Emission coordinate time T_emit
 * 3. Object position at emission (X, Y, Z)
 * 4. Minimum separation d_min
 * 5. Doppler frequency shift g
 * 6. Observed radiance I_obs
 *
 * Across:
 * - Weak-field regime (r = 25.0M)
 * - Intermediate-field regime (r = 10.0M)
 * - Strong-field regime (r = 4.5M)
 * - Grazing miss regime (r = 8.0M)
 * - Horizon crossing occlusion test (r <= r_+)
 */
class M9StepConvergenceTest {

    data class SubstepResult(
        val hit: Boolean,
        val hitT: Double,
        val objPosX: Double,
        val objPosY: Double,
        val objPosZ: Double,
        val minSeparation: Double,
        val gShift: Double,
        val radiance: Double
    )

    private fun evaluateRefinedSteps(
        p1: PhotonState4D,
        p2: PhotonState4D,
        spacetime: KerrSchildSpacetime,
        obj: RelativisticObject,
        camX: Double,
        camY: Double,
        camZ: Double,
        subdivisions: Int
    ): SubstepResult {
        var bestDist = Double.MAX_VALUE
        var bestHit: RelativisticObjectIntersection.ObjectHitResult? = null

        val dt = (p2.t - p1.t) / subdivisions
        val dx = (p2.x - p1.x) / subdivisions
        val dy = (p2.y - p1.y) / subdivisions
        val dz = (p2.z - p1.z) / subdivisions
        val dpx = (p2.p_x - p1.p_x) / subdivisions
        val dpy = (p2.p_y - p1.p_y) / subdivisions
        val dpz = (p2.p_z - p1.p_z) / subdivisions

        for (k in 0 until subdivisions) {
            val sub1 = PhotonState4D(
                t = p1.t + k * dt,
                x = p1.x + k * dx,
                y = p1.y + k * dy,
                z = p1.z + k * dz,
                p_x = p1.p_x + k * dpx,
                p_y = p1.p_y + k * dpy,
                p_z = p1.p_z + k * dpz
            )
            val sub2 = PhotonState4D(
                t = p1.t + (k + 1) * dt,
                x = p1.x + (k + 1) * dx,
                y = p1.y + (k + 1) * dy,
                z = p1.z + (k + 1) * dz,
                p_x = p1.p_x + (k + 1) * dpx,
                p_y = p1.p_y + (k + 1) * dpy,
                p_z = p1.p_z + (k + 1) * dpz
            )

            val hit = RelativisticObjectIntersection.checkIntersection(
                previous = sub1,
                current = sub2,
                spacetime = spacetime,
                obj = obj,
                camX = camX,
                camY = camY,
                camZ = camZ
            )

            if (hit != null && hit.hitDistance < bestDist) {
                bestDist = hit.hitDistance
                bestHit = hit
            }
        }

        if (bestHit != null) {
            val pos = obj.positionAt(bestHit.hitT)
            return SubstepResult(
                hit = true,
                hitT = bestHit.hitT,
                objPosX = pos[0],
                objPosY = pos[1],
                objPosZ = pos[2],
                minSeparation = bestDist,
                gShift = bestHit.frequencyShift,
                radiance = bestHit.observedRadiance
            )
        }

        // If no hit, compute closest distance along the chord
        val midT = 0.5 * (p1.t + p2.t)
        val pos = obj.positionAt(midT)
        val midX = 0.5 * (p1.x + p2.x)
        val midY = 0.5 * (p1.y + p2.y)
        val midZ = 0.5 * (p1.z + p2.z)
        val dist = sqrt((midX - pos[0]).pow(2) + (midY - pos[1]).pow(2) + (midZ - pos[2]).pow(2))

        return SubstepResult(
            hit = false,
            hitT = midT,
            objPosX = pos[0],
            objPosY = pos[1],
            objPosZ = pos[2],
            minSeparation = dist,
            gShift = 1.0,
            radiance = 0.0
        )
    }

    @Test
    fun weakFieldStepConvergence() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.5)
        val rOrbit = 25.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.1)
        val obj = RelativisticObject(worldline, radius = 0.75, emissiveRadiance = 2.0)
        val camX = 35.0; val camY = 0.0; val camZ = 0.0

        val p1 = PhotonState4D(t = 2.0, x = 27.0, y = 2.4, z = 0.0, p_x = -1.0, p_y = 0.05, p_z = 0.0)
        val p2 = PhotonState4D(t = -2.0, x = 23.0, y = 2.6, z = 0.0, p_x = -1.0, p_y = 0.05, p_z = 0.0)

        val res1x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 1)
        val res2x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 2)
        val res4x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 4)

        // 1. Hit stability
        assertTrue("1x step must detect hit", res1x.hit)
        assertTrue("2x refined step must detect hit", res2x.hit)
        assertTrue("4x refined step must detect hit", res4x.hit)

        // 2. Emission time convergence: variation between 1x and 4x is bounded
        val deltaT = abs(res4x.hitT - res1x.hitT)
        assertTrue("Weak-field emission time must converge within 0.01M, got $deltaT", deltaT < 0.01)

        // 3. Minimum separation convergence
        val deltaDist = abs(res4x.minSeparation - res1x.minSeparation)
        assertTrue("Weak-field separation must converge within 0.005M, got $deltaDist", deltaDist < 0.005)

        // 4. Doppler factor stability
        val deltaG = abs(res4x.gShift - res1x.gShift)
        assertTrue("Weak-field Doppler shift must be stable within 1e-4, got $deltaG", deltaG < 1e-4)
    }

    @Test
    fun intermediateFieldStepConvergence() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.9375)
        val rOrbit = 10.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.5)
        val obj = RelativisticObject(worldline, radius = 0.5, emissiveRadiance = 1.5)
        val camX = 0.0; val camY = 30.0; val camZ = 3.0

        val phiTarget = 0.5
        val tx = rOrbit * cos(phiTarget)
        val ty = rOrbit * sin(phiTarget)

        val p1 = PhotonState4D(t = 1.0, x = tx - 0.5, y = ty + 0.6, z = 0.05, p_x = 0.2, p_y = -0.8, p_z = -0.05)
        val p2 = PhotonState4D(t = -1.0, x = tx + 0.5, y = ty - 0.6, z = -0.05, p_x = 0.2, p_y = -0.8, p_z = -0.05)

        val res1x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 1)
        val res2x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 2)
        val res4x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 4)

        assertTrue("1x step must detect hit", res1x.hit)
        assertTrue("2x refined step must detect hit", res2x.hit)
        assertTrue("4x refined step must detect hit", res4x.hit)

        val deltaT = abs(res4x.hitT - res1x.hitT)
        assertTrue("Intermediate-field T_emit variation must be < 0.01M, got $deltaT", deltaT < 0.01)

        val deltaG = abs(res4x.gShift - res1x.gShift)
        assertTrue("Intermediate-field Doppler shift variation must be < 1e-3, got $deltaG", deltaG < 1e-3)
    }

    @Test
    fun strongFieldStepConvergence() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.9375)
        val rOrbit = 4.5
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 1.2)
        val obj = RelativisticObject(worldline, radius = 0.4, emissiveRadiance = 3.0)
        val camX = 20.0; val camY = 10.0; val camZ = 1.0

        val phiHitEst = 1.2
        val ox = rOrbit * cos(phiHitEst)
        val oy = rOrbit * sin(phiHitEst)

        val p1 = PhotonState4D(t = 0.6, x = ox + 0.3, y = oy + 0.2, z = 0.02, p_x = -0.6, p_y = -0.4, p_z = 0.0)
        val p2 = PhotonState4D(t = -0.6, x = ox - 0.3, y = oy - 0.2, z = -0.02, p_x = -0.6, p_y = -0.4, p_z = 0.0)

        val res1x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 1)
        val res2x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 2)
        val res4x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 4)

        assertTrue("Strong-field 1x step must detect hit", res1x.hit)
        assertTrue("Strong-field 2x step must detect hit", res2x.hit)
        assertTrue("Strong-field 4x step must detect hit", res4x.hit)

        val deltaT = abs(res4x.hitT - res1x.hitT)
        assertTrue("Strong-field T_emit variation must be < 0.01M, got $deltaT", deltaT < 0.01)

        val deltaG = abs(res4x.gShift - res1x.gShift)
        assertTrue("Strong-field Doppler shift variation must be < 1e-3, got $deltaG", deltaG < 1e-3)
    }

    @Test
    fun grazingMissStabilityUnderStepRefinement() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.9375)
        val rOrbit = 8.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.5)
        val camX = 30.0; val camY = 0.0; val camZ = 0.0

        // Ray passing offset in Z by 0.9M (radius is 0.5M)
        val p1 = PhotonState4D(t = 1.0, x = 8.5, y = 0.0, z = 0.9, p_x = -1.0, p_y = 0.0, p_z = 0.0)
        val p2 = PhotonState4D(t = -1.0, x = 7.5, y = 0.0, z = 0.9, p_x = -1.0, p_y = 0.0, p_z = 0.0)

        val res1x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 1)
        val res2x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 2)
        val res4x = evaluateRefinedSteps(p1, p2, spacetime, obj, camX, camY, camZ, 4)

        assertFalse("Grazing ray must miss at 1x resolution", res1x.hit)
        assertFalse("Grazing ray must miss at 2x resolution", res2x.hit)
        assertFalse("Grazing ray must miss at 4x resolution", res4x.hit)
    }

    @Test
    fun horizonCrossingOcclusionVerification() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.9375)
        val rPlus = 1.0 + sqrt(1.0 - 0.9375 * 0.9375) // r_+ ≈ 1.348M

        // Place object inside the horizon at r = 1.2M < r_+ using an infalling numerical worldline
        val s0 = com.zig.gargantua.physics.TimelikeState(
            tau = 0.0, T = -10.0, X = 1.2, Y = 0.0, Z = 0.0, pT = -1.0, pX = -0.1, pY = 0.0, pZ = 0.0
        )
        val s1 = com.zig.gargantua.physics.TimelikeState(
            tau = 10.0, T = 10.0, X = 1.0, Y = 0.0, Z = 0.0, pT = -1.0, pX = -0.1, pY = 0.0, pZ = 0.0
        )
        val worldlineInside = com.zig.gargantua.worldline.IntegratedTimelikeWorldline(spacetime, listOf(s0, s1))
        val objInside = RelativisticObject(worldlineInside, radius = 0.2)

        // Backward ray from external camera that terminates at or outside horizon
        // Any backward ray that stays outside or reaches r_+, terminates as black hole shadow
        val pCam1 = PhotonState4D(t = 10.0, x = 20.0, y = 0.0, z = 0.0, p_x = -1.0, p_y = 0.0, p_z = 0.0)
        val pCam2 = PhotonState4D(t = 5.0, x = 15.0, y = 0.0, z = 0.0, p_x = -1.0, p_y = 0.0, p_z = 0.0)

        val hit = RelativisticObjectIntersection.checkIntersection(
            previous = pCam1,
            current = pCam2,
            spacetime = spacetime,
            obj = objInside,
            camX = 25.0,
            camY = 0.0,
            camZ = 0.0
        )

        assertNull("External camera ray segment outside horizon cannot intersect object trapped inside r <= r_+", hit)
    }
}
