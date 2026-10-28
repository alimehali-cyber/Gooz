package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Requirement 2: Schwarzschild rotational symmetry for a = 0.
 * Rotating initial photon conditions produces identical trajectories, minimum radii,
 * and deflection angles within tolerance.
 */
class SchwarzschildSymmetryTest {

    @Test
    fun rotatedInitialRaysProduceIdenticalTrajectoriesInSchwarzschild() {
        val M = 1.0
        val spacetime = KerrSchildSpacetime(M = M, a = 0.0)
        val integrator = KerrPhotonIntegrator(spacetime, escapeRadius = 40.0, maxSteps = 2000)

        val rotationAngles = listOf(0.0, Math.PI * 0.25, Math.PI * 0.5, Math.PI * 0.75, Math.PI, Math.PI * 1.5)
        val impactParameter = 8.0 // b = 8M

        var baselineMinRadius = 0.0
        var baselineDeflection = 0.0

        for ((idx, phiRot) in rotationAngles.withIndex()) {
            // Unrotated ray: camera at X = -30, Y = b = 8, Z = 0; moving in +X
            val unrotX = -30.0
            val unrotY = impactParameter
            val unrotZ = 0.0
            val unrotDirX = 1.0
            val unrotDirY = 0.0
            val unrotDirZ = 0.0

            // Rotated position and direction around Z-axis by angle phiRot
            val cosPhi = cos(phiRot)
            val sinPhi = sin(phiRot)

            val rotX = unrotX * cosPhi - unrotY * sinPhi
            val rotY = unrotX * sinPhi + unrotY * cosPhi
            val rotZ = unrotZ

            val rotDirX = unrotDirX * cosPhi - unrotDirY * sinPhi
            val rotDirY = unrotDirX * sinPhi + unrotDirY * cosPhi
            val rotDirZ = unrotDirZ

            val initialPhoton = CameraModel.createNullStateFromDirection(
                spacetime,
                rotX, rotY, rotZ,
                rotDirX, rotDirY, rotDirZ
            )

            val result = integrator.traceRay(initialPhoton, fixedStepSize = 0.05)
            assertTrue("Ray must escape", result.isEscaped)

            // Compute deflection angle between initial and final velocity
            val vInit = initialPhoton.velocity(spacetime)
            val vFinal = result.finalState.velocity(spacetime)
            val initNorm = sqrt(vInit[1] * vInit[1] + vInit[2] * vInit[2] + vInit[3] * vInit[3])
            val finalNorm = sqrt(vFinal[1] * vFinal[1] + vFinal[2] * vFinal[2] + vFinal[3] * vFinal[3])

            val cosDeflection = (vInit[1] * vFinal[1] + vInit[2] * vFinal[2] + vInit[3] * vFinal[3]) / (initNorm * finalNorm)
            val deflection = acos(cosDeflection.coerceIn(-1.0, 1.0))

            if (idx == 0) {
                baselineMinRadius = result.minRadiusReached
                baselineDeflection = deflection
            } else {
                assertEquals(
                    "Minimum approach radius must be identical under rotation by angle $phiRot",
                    baselineMinRadius,
                    result.minRadiusReached,
                    1e-9
                )
                assertEquals(
                    "Deflection angle must be identical under rotation by angle $phiRot",
                    baselineDeflection,
                    deflection,
                    1e-8
                )
            }
        }
    }
}
