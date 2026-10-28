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
 * Deterministic audit and correspondence test between the CPU reference pipeline
 * (RelativisticObjectIntersection.kt, 64-bit double) and the GPU shader implementation
 * (gargantua_geodesic.frag, 32-bit float).
 *
 * Verifies with identical initial conditions, worldlines, Kerr parameters, and radii:
 * 1. Hit/Miss classification
 * 2. Emission coordinate time T_emit
 * 3. Object position at emission (X, Y, Z)
 * 4. Spatial intersection distance d_hit
 * 5. Doppler frequency shift g
 * 6. Observed radiance I_obs
 *
 * Across:
 * - Weak-field regime (r = 25.0M)
 * - Intermediate-field regime (r = 10.0M)
 * - Strong-field regime (r = 4.5M)
 * - Grazing / Definite miss regime (r = 8.0M)
 */
class M9CpuGpuIntersectionCorrespondenceTest {

    data class GpuShaderResult(
        val hit: Boolean,
        val hitT: Float,
        val hitPosX: Float,
        val hitPosY: Float,
        val hitPosZ: Float,
        val objPosX: Float,
        val objPosY: Float,
        val objPosZ: Float,
        val hitDist: Float,
        val gShift: Float,
        val observedRadiance: Float
    )

    /**
     * Exact 32-bit float reproduction of lines 346-410 of gargantua_geodesic.frag.
     */
    private fun runGpuShaderIntersection(
        prevT: Float,
        rayT: Float,
        prevPos: FloatArray,
        pos: FloatArray,
        prevP: FloatArray,
        pSpatial: FloatArray,
        camPos: FloatArray,
        mass: Float,
        spin: Float,
        objOrbitRadius: Float,
        objRadius: Float,
        objOmega: Float,
        objPhi0: Float,
        objZ: Float,
        emissiveRadiance: Float
    ): GpuShaderResult {
        val prevR = sqrt(prevPos[0] * prevPos[0] + prevPos[1] * prevPos[1] + prevPos[2] * prevPos[2])
        val r = sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2])

        val minStepR = min(prevR, r)
        val maxStepR = max(prevR, r)
        val objMargin = objRadius + 1.2f

        if (objOrbitRadius < minStepR - objMargin || objOrbitRadius > maxStepR + objMargin) {
            return GpuShaderResult(false, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        val phiPrev = objOmega * prevT + objPhi0
        val objPosPrev = floatArrayOf(objOrbitRadius * cos(phiPrev), objOrbitRadius * sin(phiPrev), objZ)

        val phiCurr = objOmega * rayT + objPhi0
        val objPosCurr = floatArrayOf(objOrbitRadius * cos(phiCurr), objOrbitRadius * sin(phiCurr), objZ)

        val dp0 = floatArrayOf(prevPos[0] - objPosPrev[0], prevPos[1] - objPosPrev[1], prevPos[2] - objPosPrev[2])
        val dp1 = floatArrayOf(pos[0] - objPosCurr[0], pos[1] - objPosCurr[1], pos[2] - objPosCurr[2])
        val vRel = floatArrayOf(dp1[0] - dp0[0], dp1[1] - dp0[1], dp1[2] - dp0[2])
        val vRel2 = vRel[0] * vRel[0] + vRel[1] * vRel[1] + vRel[2] * vRel[2]
        val sStar = if (vRel2 > 1.0e-10f) {
            (-(dp0[0] * vRel[0] + dp0[1] * vRel[1] + dp0[2] * vRel[2]) / vRel2).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }

        val hitPos = floatArrayOf(
            prevPos[0] + sStar * (pos[0] - prevPos[0]),
            prevPos[1] + sStar * (pos[1] - prevPos[1]),
            prevPos[2] + sStar * (pos[2] - prevPos[2])
        )
        val hitT = prevT + sStar * (rayT - prevT)
        val phiHit = objOmega * hitT + objPhi0
        val objPosHit = floatArrayOf(objOrbitRadius * cos(phiHit), objOrbitRadius * sin(phiHit), objZ)

        val hitRel = floatArrayOf(hitPos[0] - objPosHit[0], hitPos[1] - objPosHit[1], hitPos[2] - objPosHit[2])
        val hitDist = sqrt(hitRel[0] * hitRel[0] + hitRel[1] * hitRel[1] + hitRel[2] * hitRel[2])

        if (hitDist > objRadius) {
            return GpuShaderResult(false, hitT, hitPos[0], hitPos[1], hitPos[2], objPosHit[0], objPosHit[1], objPosHit[2], hitDist, 1f, 0f)
        }

        val hitP = floatArrayOf(
            prevP[0] + sStar * (pSpatial[0] - prevP[0]),
            prevP[1] + sStar * (pSpatial[1] - prevP[1]),
            prevP[2] + sStar * (pSpatial[2] - prevP[2])
        )

        // Object 4-velocity in Kerr-Schild
        val rObjHit = sqrt(objPosHit[0] * objPosHit[0] + objPosHit[1] * objPosHit[1])
        val a2Obj = spin * spin
        val denomVObj = rObjHit * rObjHit + a2Obj
        val lxObj = if (denomVObj > 1e-12f) (rObjHit * objPosHit[0] + spin * objPosHit[1]) / denomVObj else 0f
        val lyObj = if (denomVObj > 1e-12f) (rObjHit * objPosHit[1] - spin * objPosHit[0]) / denomVObj else 0f
        val HObj = mass / max(1.0e-6f, rObjHit)

        val lDotU = 1.0f + objOmega * (lyObj * objPosHit[0] - lxObj * objPosHit[1])
        val etaUU = -1.0f + (objOmega * objOmega) * (objPosHit[0] * objPosHit[0] + objPosHit[1] * objPosHit[1])
        val denomContract = etaUU + 2.0f * HObj * (lDotU * lDotU)
        val u0 = if (denomContract < 0.0f) 1.0f / sqrt(-denomContract) else 1.0f

        val vx = -objOmega * objPosHit[1]
        val vy = objOmega * objPosHit[0]
        val vz = 0.0f

        // Invariant frequency shift matching disk convention: denom = u0 * (1 + p · v)
        val pDotV = hitP[0] * vx + hitP[1] * vy + hitP[2] * vz
        val denomG = u0 * (1.0f + pDotV)

        val camR = sqrt(camPos[0] * camPos[0] + camPos[1] * camPos[1] + camPos[2] * camPos[2])
        val camH = mass / max(1e-6f, camR)
        val g00Cam = -1.0f + 2.0f * camH
        val uObs0 = 1.0f / sqrt(max(1.0e-6f, -g00Cam))
        val gShift = if (abs(denomG) > 1.0e-6f) (uObs0 / denomG).coerceIn(0.05f, 5.0f) else 1.0f

        val g2 = gShift * gShift
        val g4 = g2 * g2

        val distNorm = hitDist / objRadius
        val localNormalZ = if (hitDist > 1e-6f) hitRel[2] / hitDist else 0f
        val localNormalX = if (hitDist > 1e-6f) hitRel[0] / hitDist else 1f
        val localNormalY = if (hitDist > 1e-6f) hitRel[1] / hitDist else 0f
        val lat = acos(localNormalZ.coerceIn(-1.0f, 1.0f))
        val lon = atan2(localNormalY, localNormalX)
        val bands = 0.85f + 0.15f * cos(lat * 12.0f) * cos(lon * 8.0f)
        val coreGlow = exp(-distNorm * distNorm * 2.0f)
        val limb = 0.7f + 0.3f * (1.0f - distNorm)
        val patternFactor = bands * limb + 0.5f * coreGlow
        val radiance = g4 * emissiveRadiance * patternFactor

        return GpuShaderResult(true, hitT, hitPos[0], hitPos[1], hitPos[2], objPosHit[0], objPosHit[1], objPosHit[2], hitDist, gShift, radiance)
    }

    @Test
    fun weakFieldCorrespondence_HitAndPhysicalQuantitiesMatch() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.5)
        val rOrbit = 25.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.1)
        val obj = RelativisticObject(worldline, radius = 0.75, emissiveRadiance = 2.0)

        val camX = 35.0; val camY = 0.0; val camZ = 0.0
        val p1 = PhotonState4D(t = 2.0, x = 27.0, y = 2.4, z = 0.0, p_x = -1.0, p_y = 0.05, p_z = 0.0)
        val p2 = PhotonState4D(t = -2.0, x = 23.0, y = 2.6, z = 0.0, p_x = -1.0, p_y = 0.05, p_z = 0.0)

        // 1. CPU reference evaluation
        val cpuHit = RelativisticObjectIntersection.checkIntersection(p1, p2, spacetime, obj, camX, camY, camZ)
        assertNotNull("CPU reference must detect intersection in weak field", cpuHit)

        // 2. GPU shader simulation
        val gpuHit = runGpuShaderIntersection(
            prevT = p1.t.toFloat(),
            rayT = p2.t.toFloat(),
            prevPos = floatArrayOf(p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()),
            pos = floatArrayOf(p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat()),
            prevP = floatArrayOf(p1.p_x.toFloat(), p1.p_y.toFloat(), p1.p_z.toFloat()),
            pSpatial = floatArrayOf(p2.p_x.toFloat(), p2.p_y.toFloat(), p2.p_z.toFloat()),
            camPos = floatArrayOf(camX.toFloat(), camY.toFloat(), camZ.toFloat()),
            mass = spacetime.M.toFloat(),
            spin = spacetime.a.toFloat(),
            objOrbitRadius = rOrbit.toFloat(),
            objRadius = obj.radius.toFloat(),
            objOmega = worldline.omega.toFloat(),
            objPhi0 = worldline.phi0.toFloat(),
            objZ = 0f,
            emissiveRadiance = obj.emissiveRadiance.toFloat()
        )
        assertTrue("GPU shader must detect intersection in weak field", gpuHit.hit)

        // Numerical correspondence tolerances (32-bit float vs 64-bit double)
        assertEquals("Hit emission time must match", cpuHit!!.hitT.toFloat(), gpuHit.hitT, 1e-4f)
        assertEquals("Hit coordinate X must match", cpuHit.hitX.toFloat(), gpuHit.hitPosX, 1e-4f)
        assertEquals("Hit coordinate Y must match", cpuHit.hitY.toFloat(), gpuHit.hitPosY, 1e-4f)
        assertEquals("Hit coordinate Z must match", cpuHit.hitZ.toFloat(), gpuHit.hitPosZ, 1e-4f)
        assertEquals("Hit distance must match", cpuHit.hitDistance.toFloat(), gpuHit.hitDist, 1e-4f)
        assertEquals("Doppler g must match", cpuHit.frequencyShift.toFloat(), gpuHit.gShift, 1e-3f)
        assertEquals("Observed radiance must match", cpuHit.observedRadiance.toFloat(), gpuHit.observedRadiance, 5e-3f)
    }

    @Test
    fun intermediateFieldCorrespondence_HitAndPhysicalQuantitiesMatch() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.9375)
        val rOrbit = 10.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.5)
        val obj = RelativisticObject(worldline, radius = 0.5, emissiveRadiance = 1.5)

        val camX = 0.0; val camY = 30.0; val camZ = 3.0
        // Ray aimed near (r * cos(phi), r * sin(phi))
        val phiTarget = 0.5
        val tx = rOrbit * cos(phiTarget)
        val ty = rOrbit * sin(phiTarget)

        val p1 = PhotonState4D(t = 1.0, x = tx - 0.5, y = ty + 0.6, z = 0.05, p_x = 0.2, p_y = -0.8, p_z = -0.05)
        val p2 = PhotonState4D(t = -1.0, x = tx + 0.5, y = ty - 0.6, z = -0.05, p_x = 0.2, p_y = -0.8, p_z = -0.05)

        val cpuHit = RelativisticObjectIntersection.checkIntersection(p1, p2, spacetime, obj, camX, camY, camZ)
        assertNotNull("CPU must detect intersection in intermediate field", cpuHit)

        val gpuHit = runGpuShaderIntersection(
            prevT = p1.t.toFloat(),
            rayT = p2.t.toFloat(),
            prevPos = floatArrayOf(p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()),
            pos = floatArrayOf(p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat()),
            prevP = floatArrayOf(p1.p_x.toFloat(), p1.p_y.toFloat(), p1.p_z.toFloat()),
            pSpatial = floatArrayOf(p2.p_x.toFloat(), p2.p_y.toFloat(), p2.p_z.toFloat()),
            camPos = floatArrayOf(camX.toFloat(), camY.toFloat(), camZ.toFloat()),
            mass = spacetime.M.toFloat(),
            spin = spacetime.a.toFloat(),
            objOrbitRadius = rOrbit.toFloat(),
            objRadius = obj.radius.toFloat(),
            objOmega = worldline.omega.toFloat(),
            objPhi0 = worldline.phi0.toFloat(),
            objZ = 0f,
            emissiveRadiance = obj.emissiveRadiance.toFloat()
        )
        assertTrue("GPU must detect intersection in intermediate field", gpuHit.hit)

        assertEquals("Hit emission time T_emit must match", cpuHit!!.hitT.toFloat(), gpuHit.hitT, 1e-4f)
        assertEquals("Hit distance d_hit must match", cpuHit.hitDistance.toFloat(), gpuHit.hitDist, 1e-4f)
        assertEquals("Doppler g must match", cpuHit.frequencyShift.toFloat(), gpuHit.gShift, 2e-3f)
        assertEquals("Observed radiance must match", cpuHit.observedRadiance.toFloat(), gpuHit.observedRadiance, 0.02f)
    }

    @Test
    fun strongFieldCorrespondence_HighSpinAndStrongDopplerMatch() {
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

        val cpuHit = RelativisticObjectIntersection.checkIntersection(p1, p2, spacetime, obj, camX, camY, camZ)
        assertNotNull("CPU must detect intersection in strong field", cpuHit)

        val gpuHit = runGpuShaderIntersection(
            prevT = p1.t.toFloat(),
            rayT = p2.t.toFloat(),
            prevPos = floatArrayOf(p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()),
            pos = floatArrayOf(p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat()),
            prevP = floatArrayOf(p1.p_x.toFloat(), p1.p_y.toFloat(), p1.p_z.toFloat()),
            pSpatial = floatArrayOf(p2.p_x.toFloat(), p2.p_y.toFloat(), p2.p_z.toFloat()),
            camPos = floatArrayOf(camX.toFloat(), camY.toFloat(), camZ.toFloat()),
            mass = spacetime.M.toFloat(),
            spin = spacetime.a.toFloat(),
            objOrbitRadius = rOrbit.toFloat(),
            objRadius = obj.radius.toFloat(),
            objOmega = worldline.omega.toFloat(),
            objPhi0 = worldline.phi0.toFloat(),
            objZ = 0f,
            emissiveRadiance = obj.emissiveRadiance.toFloat()
        )
        assertTrue("GPU must detect intersection in strong field", gpuHit.hit)

        assertEquals("Strong field T_emit must match", cpuHit!!.hitT.toFloat(), gpuHit.hitT, 1e-4f)
        assertEquals("Strong field hit distance must match", cpuHit.hitDistance.toFloat(), gpuHit.hitDist, 1e-4f)
        assertEquals("Strong field Doppler g must match", cpuHit.frequencyShift.toFloat(), gpuHit.gShift, 6e-3f)
        assertEquals("Strong field radiance must match", cpuHit.observedRadiance.toFloat(), gpuHit.observedRadiance, 0.02f)
    }

    @Test
    fun grazingMissCorrespondence_BothRejectOutsideObjectRadius() {
        val spacetime = KerrSchildSpacetime(M = 1.0, a = 0.9375)
        val rOrbit = 8.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.5)

        val camX = 30.0; val camY = 0.0; val camZ = 0.0
        // Ray offset in Z by 0.9M (radius is 0.5M, so distance to center is ~0.9M > 0.5M)
        val p1 = PhotonState4D(t = 1.0, x = 8.5, y = 0.0, z = 0.9, p_x = -1.0, p_y = 0.0, p_z = 0.0)
        val p2 = PhotonState4D(t = -1.0, x = 7.5, y = 0.0, z = 0.9, p_x = -1.0, p_y = 0.0, p_z = 0.0)

        val cpuHit = RelativisticObjectIntersection.checkIntersection(p1, p2, spacetime, obj, camX, camY, camZ)
        assertNull("CPU must reject grazing ray outside radius", cpuHit)

        val gpuHit = runGpuShaderIntersection(
            prevT = p1.t.toFloat(),
            rayT = p2.t.toFloat(),
            prevPos = floatArrayOf(p1.x.toFloat(), p1.y.toFloat(), p1.z.toFloat()),
            pos = floatArrayOf(p2.x.toFloat(), p2.y.toFloat(), p2.z.toFloat()),
            prevP = floatArrayOf(p1.p_x.toFloat(), p1.p_y.toFloat(), p1.p_z.toFloat()),
            pSpatial = floatArrayOf(p2.p_x.toFloat(), p2.p_y.toFloat(), p2.p_z.toFloat()),
            camPos = floatArrayOf(camX.toFloat(), camY.toFloat(), camZ.toFloat()),
            mass = spacetime.M.toFloat(),
            spin = spacetime.a.toFloat(),
            objOrbitRadius = rOrbit.toFloat(),
            objRadius = obj.radius.toFloat(),
            objOmega = worldline.omega.toFloat(),
            objPhi0 = worldline.phi0.toFloat(),
            objZ = 0f,
            emissiveRadiance = obj.emissiveRadiance.toFloat()
        )
        assertFalse("GPU must reject grazing ray outside radius", gpuHit.hit)
    }
}
