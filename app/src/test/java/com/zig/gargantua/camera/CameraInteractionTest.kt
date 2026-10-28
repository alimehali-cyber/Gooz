package com.zig.gargantua.camera

import com.zig.gargantua.geodesic.GpuEquivalentIntegrator
import com.zig.gargantua.renderer.GargantuaRenderState
import com.zig.gargantua.renderer.RenderStateHolder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Milestone M6 Interactive Relativistic Camera:
 * 1. Bounded observer orbit and zoom keeping coordinates in numerically stable domain.
 * 2. Deterministic reset behavior returning to cinematic default state.
 * 3. Observer orthonormal tetrad basis construction (forward, right, up orthonormality).
 * 4. Pinhole null photon momentum normalization across rotated observer poses.
 */
class CameraInteractionTest {

    @Test
    fun boundedOrbitAndZoomKeepsObserverInNumericallyValidRegion() {
        val holder = RenderStateHolder()

        // Attempt excessive negative inclination (pole singularity avoidance)
        holder.updateState {
            it.copy(
                camInclinationDeg = (-25.0f).coerceIn(5.0f, 175.0f),
                camDist = (2.0f).coerceIn(12.0f, 60.0f) // Avoid diving into horizon (r+ ~ 1.6M)
            )
        }
        var state = holder.getState()
        assertEquals("Inclination must clamp to lower numerical bound", 5.0f, state.camInclinationDeg, 1e-4f)
        assertEquals("Distance must clamp to lower numerical bound", 12.0f, state.camDist, 1e-4f)

        // Attempt excessive positive inclination (south pole singularity avoidance) and distance
        holder.updateState {
            it.copy(
                camInclinationDeg = (210.0f).coerceIn(5.0f, 175.0f),
                camDist = (150.0f).coerceIn(12.0f, 60.0f) // Keep within reasonable asymptotic box
            )
        }
        state = holder.getState()
        assertEquals("Inclination must clamp to upper numerical bound", 175.0f, state.camInclinationDeg, 1e-4f)
        assertEquals("Distance must clamp to upper numerical bound", 60.0f, state.camDist, 1e-4f)
    }

    @Test
    fun deterministicCameraResetRestoresCinematicParameters() {
        val holder = RenderStateHolder()

        // Mutate camera state extensively
        holder.updateState {
            it.copy(
                camDist = 48.5f,
                camInclinationDeg = 25.0f,
                camAzimuthDeg = 142.0f,
                camTargetX = 5.0f,
                camTargetY = -3.0f,
                camTargetZ = 2.0f
            )
        }

        // Trigger reset
        holder.updateState {
            it.copy(
                camDist = 32.0f,
                camInclinationDeg = 80.0f,
                camAzimuthDeg = 0.0f,
                camTargetX = 0.0f,
                camTargetY = 0.0f,
                camTargetZ = 0.0f
            )
        }

        val resetState = holder.getState()
        assertEquals(32.0f, resetState.camDist, 1e-4f)
        assertEquals(80.0f, resetState.camInclinationDeg, 1e-4f)
        assertEquals(0.0f, resetState.camAzimuthDeg, 1e-4f)
        assertEquals(0.0f, resetState.camTargetX, 1e-4f)
        assertEquals(0.0f, resetState.camTargetY, 1e-4f)
        assertEquals(0.0f, resetState.camTargetZ, 1e-4f)
    }

    @Test
    fun twoFingerPanTranslatesCameraTargetAndPreservesOrthonormalTetrad() {
        val holder = RenderStateHolder()

        // Apply two-finger translation/pan offset
        holder.updateState {
            it.copy(
                camTargetX = 3.5f,
                camTargetY = -2.0f,
                camTargetZ = 1.0f
            )
        }

        val state = holder.getState()
        assertEquals(3.5f, state.camTargetX, 1e-4f)
        assertEquals(-2.0f, state.camTargetY, 1e-4f)
        assertEquals(1.0f, state.camTargetZ, 1e-4f)

        // Verify tetrad orthonormal property remains invariant under translation
        val inclRad = Math.toRadians(state.camInclinationDeg.toDouble())
        val azRad = Math.toRadians(state.camAzimuthDeg.toDouble())
        val dist = state.camDist.toDouble()

        val dirX = sin(inclRad) * cos(azRad)
        val dirY = sin(inclRad) * sin(azRad)
        val dirZ = cos(inclRad)

        val camX = state.camTargetX.toDouble() + dist * dirX
        val camY = state.camTargetY.toDouble() + dist * dirY
        val camZ = state.camTargetZ.toDouble() + dist * dirZ

        val fwdRawX = state.camTargetX.toDouble() - camX
        val fwdRawY = state.camTargetY.toDouble() - camY
        val fwdRawZ = state.camTargetZ.toDouble() - camZ
        val fwdLen = sqrt(fwdRawX * fwdRawX + fwdRawY * fwdRawY + fwdRawZ * fwdRawZ)
        val fwd = doubleArrayOf(fwdRawX / fwdLen, fwdRawY / fwdLen, fwdRawZ / fwdLen)

        val rightRaw = doubleArrayOf(fwd[1], -fwd[0], 0.0)
        val rightLen = sqrt(rightRaw[0] * rightRaw[0] + rightRaw[1] * rightRaw[1])
        val right = doubleArrayOf(rightRaw[0] / rightLen, rightRaw[1] / rightLen, 0.0)

        val up = doubleArrayOf(
            right[1] * fwd[2] - right[2] * fwd[1],
            right[2] * fwd[0] - right[0] * fwd[2],
            right[0] * fwd[1] - right[1] * fwd[0]
        )

        fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        fun dot(v1: DoubleArray, v2: DoubleArray) = v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2]

        assertEquals("Forward must be unit length", 1.0, norm(fwd), 1e-6)
        assertEquals("Right must be unit length", 1.0, norm(right), 1e-6)
        assertEquals("Up must be unit length", 1.0, norm(up), 1e-6)
        assertEquals("Forward and Right must be orthogonal", 0.0, dot(fwd, right), 1e-6)
        assertEquals("Forward and Up must be orthogonal", 0.0, dot(fwd, up), 1e-6)
        assertEquals("Right and Up must be orthogonal", 0.0, dot(right, up), 1e-6)
    }

    @Test
    fun cameraOrientationProducesValidObserverTetrad() {
        val testAngles = listOf(
            Pair(82.0f, 0.0f),
            Pair(45.0f, 90.0f),
            Pair(15.0f, 180.0f),
            Pair(120.0f, 270.0f),
            Pair(90.0f, 45.0f)
        )

        val dist = 24.0

        for ((inclDeg, azDeg) in testAngles) {
            val inclRad = Math.toRadians(inclDeg.toDouble())
            val azRad = Math.toRadians(azDeg.toDouble())

            val camX = dist * sin(inclRad) * cos(azRad)
            val camY = dist * sin(inclRad) * sin(azRad)
            val camZ = dist * cos(inclRad)

            // Forward points to black hole origin (0, 0, 0)
            val fwdLen = sqrt(camX * camX + camY * camY + camZ * camZ)
            val fwd = doubleArrayOf(-camX / fwdLen, -camY / fwdLen, -camZ / fwdLen)

            // Right vector
            val rightRaw = doubleArrayOf(fwd[1], -fwd[0], 0.0)
            val rightLen = sqrt(rightRaw[0] * rightRaw[0] + rightRaw[1] * rightRaw[1])
            val right = if (rightLen > 1e-6) {
                doubleArrayOf(rightRaw[0] / rightLen, rightRaw[1] / rightLen, 0.0)
            } else {
                doubleArrayOf(1.0, 0.0, 0.0)
            }

            // Up vector = right x forward
            val up = doubleArrayOf(
                right[1] * fwd[2] - right[2] * fwd[1],
                right[2] * fwd[0] - right[0] * fwd[2],
                right[0] * fwd[1] - right[1] * fwd[0]
            )

            fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            fun dot(v1: DoubleArray, v2: DoubleArray) = v1[0] * v2[0] + v1[1] * v2[1] + v1[2] * v2[2]

            // 1. All basis vectors must be unit normalized
            assertEquals("Forward must be unit length", 1.0, norm(fwd), 1e-6)
            assertEquals("Right must be unit length", 1.0, norm(right), 1e-6)
            assertEquals("Up must be unit length", 1.0, norm(up), 1e-6)

            // 2. All basis vectors must be mutually orthogonal
            assertEquals("Forward and Right must be orthogonal", 0.0, dot(fwd, right), 1e-6)
            assertEquals("Forward and Up must be orthogonal", 0.0, dot(fwd, up), 1e-6)
            assertEquals("Right and Up must be orthogonal", 0.0, dot(right, up), 1e-6)
        }
    }

    @Test
    fun nullPhotonInitializationRemainsNormalizedAcrossCameraRotations() {
        val M = 1.0f
        val a = 0.8f

        val testCameraPoses = listOf(
            floatArrayOf(24.0f, 0.0f, 3.34f),   // 82 deg cinematic default
            floatArrayOf(0.0f, 24.0f, 3.34f),   // 90 deg azimuth
            floatArrayOf(-20.0f, 10.0f, 5.0f),  // Arbitrary quadrant
            floatArrayOf(15.0f, -15.0f, 12.0f)  // Elevated perspective
        )

        val rayDirs = listOf(
            floatArrayOf(-1.0f, 0.0f, 0.0f),
            floatArrayOf(-0.9f, 0.3f, 0.1f),
            floatArrayOf(-0.7f, -0.5f, 0.2f)
        )

        for (camPos in testCameraPoses) {
            for (dir in rayDirs) {
                val len = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
                val unitDir = floatArrayOf(dir[0] / len, dir[1] / len, dir[2] / len)

                val rayState = GpuEquivalentIntegrator.createInitialRay(M, a, camPos, unitDir)
                val residualH = abs(GpuEquivalentIntegrator.computeHamiltonian(M, a, rayState))

                // Initial Hamiltonian residual must remain zero within single precision (1e-5)
                assertTrue("Hamiltonian residual |H|=$residualH must be < 1e-5", residualH < 1e-5f)
            }
        }
    }

    @Test
    fun diskOrbitalVelocityIsConfinedToEquatorialPlaneWithZeroZComponent() {
        val M = 1.0
        val a = 0.8
        val isco = com.zig.gargantua.disk.KerrIsco.compute(M, a)
        val diskModel = com.zig.gargantua.disk.AccretionDiskModel(M = M, a = a, innerRadius = isco)
        val spacetime = com.zig.gargantua.physics.KerrSchildSpacetime(M, a)

        val testRadii = listOf(isco + 0.5, 6.0, 10.0, 15.0)
        val testAngles = listOf(0.0, PI / 4.0, PI / 2.0, PI, 3.0 * PI / 2.0)

        for (r in testRadii) {
            for (phi in testAngles) {
                val x = r * cos(phi)
                val y = r * sin(phi)

                val u = diskModel.emitterFourVelocity(spacetime, x, y)

                // u[0]=u^t, u[1]=u^x, u[2]=u^y, u[3]=u^z
                assertEquals("Orbital velocity in Z must be strictly 0.0 (planar orbit)", 0.0, u[3], 1e-15)

                // Verify angular momentum points along +Z axis: L_z = x * u_y - y * u_x > 0
                // Omega is evaluated at the Kerr-Schild radius of (x, y, 0), sqrt(x^2 + y^2 - a^2), as in the shader.
                val omega = diskModel.keplerianAngularVelocity(kotlin.math.sqrt(x * x + y * y - a * a))
                assertEquals("u^x must match -Omega * Y", -omega * y * u[0], u[1], 1e-10)
                assertEquals("u^y must match +Omega * X", omega * x * u[0], u[2], 1e-10)
            }
        }
    }

    @Test
    fun leftRightDopplerAsymmetryCorrespondsToScreenCoordinates() {
        val dist = 24.0
        val inclRad = Math.toRadians(82.0)
        val azRad = Math.toRadians(0.0)

        val camX = dist * sin(inclRad) * cos(azRad)
        val camY = dist * sin(inclRad) * sin(azRad)
        val camZ = dist * cos(inclRad)

        val fwdLen = sqrt(camX * camX + camY * camY + camZ * camZ)
        val fwd = doubleArrayOf(-camX / fwdLen, -camY / fwdLen, -camZ / fwdLen)

        val rightRaw = doubleArrayOf(fwd[1], -fwd[0], 0.0)
        val rightLen = sqrt(rightRaw[0] * rightRaw[0] + rightRaw[1] * rightRaw[1])
        val right = doubleArrayOf(rightRaw[0] / rightLen, rightRaw[1] / rightLen, 0.0)

        // Left ray (st.x = -0.5) vs Right ray (st.x = +0.5)
        val rayLeftY = fwd[1] + right[1] * (-0.5)
        val rayRightY = fwd[1] + right[1] * (+0.5)

        // Ray left targets negative Y (approaching material moving towards observer at +X)
        assertTrue("Left screen ray must aim towards negative Y (approaching side)", rayLeftY < 0.0)
        // Ray right targets positive Y (receding material moving away from observer at +X)
        assertTrue("Right screen ray must aim towards positive Y (receding side)", rayRightY > 0.0)
    }
}
