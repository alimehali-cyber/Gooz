package com.zig.gargantua.disk

import com.zig.gargantua.geodesic.CameraModel
import com.zig.gargantua.geodesic.KerrPhotonIntegrator
import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates Requirement 10: Step refinement and stabilization of disk intersection properties.
 *
 * Demonstrates that refining the integration step size stabilizes:
 * 1. Disk intersection radius r_hit.
 * 2. Invariant frequency shift g.
 */
class DiskConvergenceTest {

    @Test
    fun diskIntersectionRadiusAndFrequencyShiftStabilizeWithStepRefinement() {
        val M = 1.0
        val a = 0.7
        val spacetime = KerrSchildSpacetime(M = M, a = a)
        val disk = AccretionDiskModel(M = M, a = a, outerRadius = 22.0)

        // Lensed ray aimed just above black hole crossing the disk
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -25.0, Y = 2.0, Z = 4.0,
            dx = 1.0, dy = 0.0, dz = 0.165
        )

        // Three integrators with successively halved step factors
        val stepSizes = listOf(0.1, 0.05, 0.025)
        val hitRadii = mutableListOf<Double>()
        val gShifts = mutableListOf<Double>()

        for (h in stepSizes) {
            val integrator = KerrPhotonIntegrator(
                spacetime = spacetime,
                disk = disk,
                maxSteps = 2000
            )
            val result = integrator.traceRay(initialPhoton, fixedStepSize = h)
            assertTrue("Ray must hit disk with step $h", result.isDiskHit)
            assertNotNull("Hit result must be present", result.diskHit)

            hitRadii.add(result.diskHit!!.rHit)
            gShifts.add(result.diskHit!!.frequencyShift)
        }

        // Difference between h=0.1 and h=0.05 vs difference between h=0.05 and h=0.025
        val diffR12 = abs(hitRadii[0] - hitRadii[1])
        val diffR23 = abs(hitRadii[1] - hitRadii[2])

        val diffG12 = abs(gShifts[0] - gShifts[1])
        val diffG23 = abs(gShifts[1] - gShifts[2])

        // Refinement must reduce deviation
        assertTrue(
            "Intersection radius must stabilize: diffR23 ($diffR23) <= diffR12 ($diffR12)",
            diffR23 <= diffR12 + 1e-12
        )
        assertTrue(
            "Frequency shift must stabilize: diffG23 ($diffG23) <= diffG12 ($diffG12)",
            diffG23 <= diffG12 + 1e-12
        )

        // Both values are within very tight absolute bounds
        assertTrue("Hit radius difference between finest meshes must be < 1e-3, got $diffR23", diffR23 < 1e-3)
        assertTrue("Frequency shift difference between finest meshes must be < 1e-3, got $diffG23", diffG23 < 1e-3)
    }

    @Test
    fun thinDiskIntersectionNearIscoConvergesAcrossThreeRefinementLevels() {
        val M = 1.0
        val a = 0.8
        val spacetime = KerrSchildSpacetime(M = M, a = a)
        val disk = AccretionDiskModel(M = M, a = a, outerRadius = 22.0)

        // Grazing near-ISCO ray crossing the equatorial plane in the strong relativistic field
        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime = spacetime,
            X = -20.0, Y = 1.5, Z = 2.5,
            dx = 1.0, dy = 0.05, dz = 0.12
        )

        // Three successive refinement levels (standard, fine, ultra-fine)
        val stepLevels = listOf(0.08, 0.04, 0.02)
        val hitRadii = mutableListOf<Double>()
        val hitPositionsX = mutableListOf<Double>()
        val hitPositionsY = mutableListOf<Double>()

        for (h in stepLevels) {
            val integrator = KerrPhotonIntegrator(
                spacetime = spacetime,
                disk = disk,
                maxSteps = 3000
            )
            val result = integrator.traceRay(initialPhoton, fixedStepSize = h)
            assertTrue("Ray must consistently detect thin-disk intersection at step size $h", result.isDiskHit)
            val hit = result.diskHit
            assertNotNull("Disk hit result must not be null at step size $h", hit)

            hitRadii.add(hit!!.rHit)
            hitPositionsX.add(hit.hitX)
            hitPositionsY.add(hit.hitY)
        }

        // Measure spatial distance between intersection coordinates across refinement levels
        val d12 = kotlin.math.hypot(hitPositionsX[0] - hitPositionsX[1], hitPositionsY[0] - hitPositionsY[1])
        val d23 = kotlin.math.hypot(hitPositionsX[1] - hitPositionsX[2], hitPositionsY[1] - hitPositionsY[2])

        // Step refinement must monotonically reduce intersection uncertainty
        assertTrue(
            "Spatial intersection must converge with refinement: d23 ($d23) <= d12 ($d12)",
            d23 <= d12 + 1e-10
        )

        // Ultra-fine agreement must be sub-millimeter (< 5e-4 in geometric M units)
        assertTrue(
            "Finest level intersection discrepancy must be < 5e-4 M, got $d23",
            d23 < 5e-4
        )
    }
}
