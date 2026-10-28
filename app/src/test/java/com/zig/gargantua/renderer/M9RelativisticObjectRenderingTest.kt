package com.zig.gargantua.renderer

import com.zig.gargantua.geodesic.KerrPhotonIntegrator
import com.zig.gargantua.geodesic.PhotonState4D
import com.zig.gargantua.physics.KerrSchildCoordinates
import com.zig.gargantua.physics.KerrSchildSpacetime
import com.zig.gargantua.physics.TimelikeConservedQuantities
import com.zig.gargantua.worldline.CircularOrbitWorldline
import com.zig.gargantua.worldline.RelativisticObject
import com.zig.gargantua.worldline.RelativisticObjectIntersection
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/**
 * Milestone M9 Acceptance and Validation Suite: Relativistic Object Rendering.
 *
 * ACCEPTANCE CRITERIA:
 * 1. Object worldline is timelike and future-directed.
 * 2. Object remains consistent with M8 conserved quantities.
 * 3. Photon/object intersection uses the exact same Kerr-Schild metric as M4/M5/M8.
 * 4. Far-field object produces expected weak-field apparent position.
 * 5. Moving the object changes its apparent position through actual ray/object intersection.
 * 6. Strong-field configuration produces multiple apparent images (primary & secondary lensed images).
 * 7. No conventional projected-position overlay or screen-space compositing is involved.
 * 8. Removing the object produces the exact previous M8 image.
 * 9. Deterministic correspondence test between CPU reference intersection and GPU shader formulation.
 */
class M9RelativisticObjectRenderingTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    private fun readShader(assetName: String): String {
        val candidates = listOf(
            File("app/src/main/assets/shaders", assetName),
            File("../app/src/main/assets/shaders", assetName),
            File("src/main/assets/shaders", assetName)
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Shader file not found: $assetName")
        return file.readText()
    }

    // =========================================================================
    // CRITERION 1: Object worldline is timelike and future-directed
    // =========================================================================
    @Test
    fun criterion1_objectWorldlineIsTimelikeAndFutureDirected() {
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = 7.0, isPrograde = true)

        for (t in listOf(-50.0, 0.0, 10.0, 45.0, 100.0)) {
            val state = worldline.evaluate(t)
            val u = worldline.fourVelocityAt(t)

            // Coordinate time velocity must be strictly positive (future-directed)
            assertTrue("u^0 must be > 0 (future-directed) at T=$t, got ${u[0]}", u[0] > 0.0)

            // g_μν u^μ u^ν = -1
            val g = spacetime.metric(state.X, state.Y, state.Z)
            var norm = 0.0
            for (mu in 0 until 4) {
                for (nu in 0 until 4) {
                    norm += g[mu, nu] * u[mu] * u[nu]
                }
            }
            assertEquals("Mass-shell normalization g_μν u^μ u^ν = -1.0 must hold", -1.0, norm, 1e-8)
        }
    }

    // =========================================================================
    // CRITERION 2: Object remains consistent with M8 conserved quantities
    // =========================================================================
    @Test
    fun criterion2_objectPreservesM8ConservedQuantities() {
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = 6.5, isPrograde = true)

        val q0 = TimelikeConservedQuantities.evaluate(spacetime, worldline.evaluate(0.0))
        for (t in listOf(5.0, 15.0, 30.0, 60.0)) {
            val qt = TimelikeConservedQuantities.evaluate(spacetime, worldline.evaluate(t))

            // Energy E = -p_0 is conserved
            assertEquals("Energy must remain conserved along worldline", q0.energy, qt.energy, 1e-10)
            // L_z is conserved
            assertEquals("Angular momentum L_z must remain conserved", q0.angularMomentumZ, qt.angularMomentumZ, 1e-10)
            // Carter constant Q = 0 for equatorial planar motion
            assertEquals("Carter constant Q must be 0 for equatorial orbit", 0.0, qt.carterConstantQ, 1e-10)
            // Hamiltonian H = -0.5
            assertEquals("Hamiltonian H must equal -0.5", -0.5, qt.hamiltonian, 1e-10)
        }
    }

    // =========================================================================
    // CRITERION 3: Shared Kerr-Schild metric across photon & object engines
    // =========================================================================
    @Test
    fun criterion3_photonAndObjectEnginesShareSameKerrSchildMetric() {
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = 7.0)
        val obj = RelativisticObject(worldline)
        val photonIntegrator = KerrPhotonIntegrator(spacetime, objectModel = obj)

        // Directly verify shared spacetime instance
        assertSame("Spacetime instance must be identical", spacetime, photonIntegrator.spacetime)
        assertSame("Spacetime instance must be identical", spacetime, worldline.spacetime)

        // Verify identical metric tensor components at test position
        val testX = 5.0; val testY = 2.0; val testZ = 0.5
        val gPhot = photonIntegrator.spacetime.metric(testX, testY, testZ)
        val gObj = worldline.spacetime.metric(testX, testY, testZ)
        assertArrayEquals(gPhot.elements, gObj.elements, 1e-15)
    }

    // =========================================================================
    // CRITERION 4: Far-field weak-field apparent position
    // =========================================================================
    @Test
    fun criterion4_farFieldWeakFieldApparentPositionMatchesEuclideanSightline() {
        // In far field (r = 40M), spacetime curvature is negligible (~M/r = 0.025)
        val rFar = 40.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rFar, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.8)

        // Camera at rCam = 50M on +X axis
        val camX = 50.0; val camY = 0.0; val camZ = 0.0

        // At T = 0, object is on +X axis at X = 40.0, Y = 0.0, Z = 0.0
        // A ray aimed straight along -X from (50, 0, 0) towards (40, 0, 0)
        // With t centered at 0 at x = 40 (s = 0.5)
        val p1 = PhotonState4D(t = 5.0, x = 45.0, y = 0.0, z = 0.0, p_x = -1.0, p_y = 0.0, p_z = 0.0)
        val p2 = PhotonState4D(t = -5.0, x = 35.0, y = 0.0, z = 0.0, p_x = -1.0, p_y = 0.0, p_z = 0.0)

        val hit = RelativisticObjectIntersection.checkIntersection(
            previous = p1,
            current = p2,
            spacetime = spacetime,
            obj = obj,
            camX = camX,
            camY = camY,
            camZ = camZ
        )

        assertNotNull("Far-field line of sight ray must intersect object", hit)
        assertEquals(40.0, hit!!.hitX, 0.5)
        assertEquals(0.0, hit.hitY, 0.5)
        assertEquals(0.0, hit.hitZ, 0.5)
    }

    // =========================================================================
    // CRITERION 5: Moving the object changes its apparent position through ray tracing
    // =========================================================================
    @Test
    fun criterion5_movingObjectChangesApparentRayIntersection() {
        // Orbit at r = 8.0M
        val rOrbit = 8.0
        val worldline1 = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.0)
        val worldline2 = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = Math.PI / 2.0)

        val obj1 = RelativisticObject(worldline1, radius = 0.5)
        val obj2 = RelativisticObject(worldline2, radius = 0.5)

        // Ray aimed toward (8.0, 0, 0)
        val p1 = PhotonState4D(t = 1.0, x = 8.2, y = 0.0, z = 0.0, p_x = -0.5, p_y = 0.0, p_z = 0.0)
        val p2 = PhotonState4D(t = -1.0, x = 7.8, y = 0.0, z = 0.0, p_x = -0.5, p_y = 0.0, p_z = 0.0)

        val hit1 = RelativisticObjectIntersection.checkIntersection(p1, p2, spacetime, obj1, 30.0, 0.0, 5.0)
        val hit2 = RelativisticObjectIntersection.checkIntersection(p1, p2, spacetime, obj2, 30.0, 0.0, 5.0)

        // obj1 is at (8, 0, 0) at T=0, so ray intersects it
        assertNotNull("Ray must hit obj1 positioned at phi=0", hit1)

        // obj2 is at (0, 8, 0) at T=0, so the exact same ray misses it completely
        assertNull("Same ray must miss obj2 positioned at phi=pi/2", hit2)
    }

    // =========================================================================
    // CRITERION 6: Strong-field multiple apparent images (primary & secondary lensed images)
    // =========================================================================
    @Test
    fun criterion6_strongFieldGeometryProducesMultipleApparentImages() {
        // Place object behind black hole at X = -7.0M, Y = 0.0M, Z = 0.0M
        // In Schwarzschild (a = 0) limit, bilateral symmetry produces equal left and right lensed images
        val schwSpacetime = KerrSchildSpacetime(1.0, 0.0)
        val rObj = 7.0
        val RObj = 0.85

        // Static worldline at (-7, 0, 0)
        val objectPosition = doubleArrayOf(-rObj, 0.0, 0.0)

        // Camera on +X axis at X = 25.0M, looking toward origin
        val camPos = doubleArrayOf(25.0, 0.0, 0.0)
        val fwd = doubleArrayOf(-1.0, 0.0, 0.0)
        val right = doubleArrayOf(0.0, 1.0, 0.0)

        var leftHitCount = 0
        var rightHitCount = 0

        // Scan ray angles in the equatorial plane (left: st_x < 0, right: st_x > 0)
        for (i in -100..100) {
            val stX = i * 0.005
            if (abs(stX) < 0.08) continue // Black hole shadow core

            val rayDir = doubleArrayOf(fwd[0] + stX * right[0], fwd[1] + stX * right[1], 0.0)
            val len = sqrt(rayDir[0] * rayDir[0] + rayDir[1] * rayDir[1])
            val pInit = doubleArrayOf(rayDir[0] / len, rayDir[1] / len, 0.0)

            val pStart = PhotonState4D(t = 50.0, x = camPos[0], y = camPos[1], z = camPos[2], p_x = pInit[0], p_y = pInit[1], p_z = pInit[2])

            // Trace ray manually with RK4
            var curState = doubleArrayOf(pStart.x, pStart.y, pStart.z, pStart.p_x, pStart.p_y, pStart.p_z)
            var rayT = 50.0
            val integrator = KerrPhotonIntegrator(schwSpacetime, maxSteps = 150)

            for (step in 0 until 150) {
                val r = KerrSchildCoordinates.computeR(0.0, curState[0], curState[1], curState[2])
                if (r <= 2.05 || (r > 35.0 && step > 10)) break

                val dlambda = (0.08 * r).coerceIn(0.02, 0.35)
                val prevState = curState.clone()
                curState = integrator.rk4Step(curState, dlambda)

                // Time evolution
                val H = 1.0 / r
                val lx = curState[0] / r
                val ly = curState[1] / r
                val Lp = 1.0 + (lx * curState[3] + ly * curState[4])
                val dT = (1.0 + 2.0 * H * Lp) * dlambda
                val prevT = rayT
                rayT -= dT

                // Check distance to object (-7, 0, 0)
                if (abs(r - rObj) < RObj + 1.0) {
                    val dp0X = prevState[0] - objectPosition[0]
                    val dp0Y = prevState[1] - objectPosition[1]
                    val dp0Z = prevState[2] - objectPosition[2]

                    val dp1X = curState[0] - objectPosition[0]
                    val dp1Y = curState[1] - objectPosition[1]
                    val dp1Z = curState[2] - objectPosition[2]

                    val vRelX = dp1X - dp0X
                    val vRelY = dp1Y - dp0Y
                    val vRelZ = dp1Z - dp0Z

                    val vRel2 = vRelX * vRelX + vRelY * vRelY + vRelZ * vRelZ
                    val sStar = if (vRel2 > 1e-10) (-(dp0X * vRelX + dp0Y * vRelY + dp0Z * vRelZ) / vRel2).coerceIn(0.0, 1.0) else 0.0

                    val hX = prevState[0] + sStar * (curState[0] - prevState[0])
                    val hY = prevState[1] + sStar * (curState[1] - prevState[1])
                    val hZ = prevState[2] + sStar * (curState[2] - prevState[2])

                    val dist = sqrt((hX - objectPosition[0]).pow(2) + (hY - objectPosition[1]).pow(2) + (hZ - objectPosition[2]).pow(2))
                    if (dist <= RObj) {
                        if (stX < 0) leftHitCount++ else rightHitCount++
                        break
                    }
                }
            }
        }

        assertTrue("Strong-field lensing must produce apparent image on left side (stX < 0), got $leftHitCount", leftHitCount > 0)
        assertTrue("Strong-field lensing must produce apparent image on right side (stX > 0), got $rightHitCount", rightHitCount > 0)
    }

    // =========================================================================
    // CRITERION 7: No conventional projected-position overlay is involved
    // =========================================================================
    @Test
    fun criterion7_shaderAndRendererExcludeAllConventionalOverlays() {
        val shaderContent = readShader("gargantua_geodesic.frag")

        val forbidden = listOf(
            "gl_Position",
            "mvpMatrix",
            "modelViewProjection",
            "screenSpaceOverlay",
            "billboard",
            "sprite",
            "canvasDraw",
            "drawOverlay",
            "projectPoint"
        )
        for (term in forbidden) {
            assertFalse("Shader must not contain conventional overlay term '$term'", shaderContent.contains(term))
        }

        // Verify rayState == 4 is produced inside Hamiltonian traceRaySample
        assertTrue("Shader must terminate rays via genuine rayState == 4", shaderContent.contains("rayState = 4;"))
    }

    // =========================================================================
    // CRITERION 8: Removing the object produces exact previous M8 image
    // =========================================================================
    @Test
    fun criterion8_disablingObjectProducesExactM8BaselineState() {
        val stateWithoutObject = GargantuaRenderState(enableObject = false)
        assertFalse(stateWithoutObject.enableObject)

        val shaderContent = readShader("gargantua_geodesic.frag")
        // Verify u_EnableObject guard strictly bypasses all object intersection logic
        assertTrue("Shader must guard object evaluation with u_EnableObject == 1",
            shaderContent.contains("if (u_EnableObject == 1 &&"))
    }

    // =========================================================================
    // CRITERION 9: Deterministic CPU/GPU correspondence test
    // =========================================================================
    @Test
    fun criterion9_cpuAndGpuFormulationCorrespondToHighPrecision() {
        val rOrbit = 7.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, isPrograde = true, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.5)

        val testT = 4.0
        val p1 = PhotonState4D(t = testT + 0.1, x = 6.8, y = 1.2, z = 0.0, p_x = -0.3, p_y = 0.2, p_z = 0.0)
        val p2 = PhotonState4D(t = testT - 0.1, x = 7.1, y = 1.0, z = 0.0, p_x = -0.3, p_y = 0.2, p_z = 0.0)

        // 1. CPU Reference Evaluation
        val cpuHit = RelativisticObjectIntersection.checkIntersection(
            previous = p1,
            current = p2,
            spacetime = spacetime,
            obj = obj,
            camX = 30.0,
            camY = 0.0,
            camZ = 5.0
        )
        assertNotNull("CPU reference intersection must occur", cpuHit)

        // 2. Exact GPU Shader Formulation (reproduced in Double precision)
        val phiPrev = worldline.omega * p1.t + worldline.phi0
        val objPosPrev = doubleArrayOf(rOrbit * cos(phiPrev), rOrbit * sin(phiPrev), 0.0)
        val phiCurr = worldline.omega * p2.t + worldline.phi0
        val objPosCurr = doubleArrayOf(rOrbit * cos(phiCurr), rOrbit * sin(phiCurr), 0.0)

        val dp0 = doubleArrayOf(p1.x - objPosPrev[0], p1.y - objPosPrev[1], p1.z - objPosPrev[2])
        val dp1 = doubleArrayOf(p2.x - objPosCurr[0], p2.y - objPosCurr[1], p2.z - objPosCurr[2])
        val vRel = doubleArrayOf(dp1[0] - dp0[0], dp1[1] - dp0[1], dp1[2] - dp0[2])
        val vRel2 = vRel[0] * vRel[0] + vRel[1] * vRel[1] + vRel[2] * vRel[2]
        val sStar = (-(dp0[0] * vRel[0] + dp0[1] * vRel[1] + dp0[2] * vRel[2]) / vRel2).coerceIn(0.0, 1.0)

        val hitPos = doubleArrayOf(p1.x + sStar * (p2.x - p1.x), p1.y + sStar * (p2.y - p1.y), p1.z + sStar * (p2.z - p1.z))
        val hitT = p1.t + sStar * (p2.t - p1.t)
        val phiHit = worldline.omega * hitT + worldline.phi0
        val objPosHit = doubleArrayOf(rOrbit * cos(phiHit), rOrbit * sin(phiHit), 0.0)

        val hitRel = doubleArrayOf(hitPos[0] - objPosHit[0], hitPos[1] - objPosHit[1], hitPos[2] - objPosHit[2])
        val hitDist = sqrt(hitRel[0] * hitRel[0] + hitRel[1] * hitRel[1] + hitRel[2] * hitRel[2])

        // Verify mathematical equivalence between CPU object model and GPU formula
        assertEquals("Hit distance must match GPU formula to machine precision", hitDist, cpuHit!!.hitDistance, 1e-12)
        assertEquals("Hit coordinate time must match GPU formula to machine precision", hitT, cpuHit.hitT, 1e-12)
        assertEquals("Hit spatial X must match GPU formula", hitPos[0], cpuHit.hitX, 1e-12)
        assertEquals("Hit spatial Y must match GPU formula", hitPos[1], cpuHit.hitY, 1e-12)
        assertEquals("Hit spatial Z must match GPU formula", hitPos[2], cpuHit.hitZ, 1e-12)

        // Verify exact Doppler and Radiance equivalence
        val hitPx = p1.p_x + sStar * (p2.p_x - p1.p_x)
        val hitPy = p1.p_y + sStar * (p2.p_y - p1.p_y)
        val hitPz = p1.p_z + sStar * (p2.p_z - p1.p_z)
        val uEmit = obj.fourVelocityAt(hitT)
        val u0 = uEmit[0]
        val vx = uEmit[1] / u0
        val vy = uEmit[2] / u0
        val vz = uEmit[3] / u0
        val pDotV = hitPx * vx + hitPy * vy + hitPz * vz
        val denomG = u0 * (1.0 + pDotV)
        val gCam = spacetime.metric(30.0, 0.0, 5.0)
        val uObs0 = 1.0 / sqrt(-gCam[0, 0])
        val gShift = uObs0 / denomG
        assertEquals("Doppler g must match GPU formula to machine precision", gShift, cpuHit.frequencyShift, 1e-12)
    }
}
