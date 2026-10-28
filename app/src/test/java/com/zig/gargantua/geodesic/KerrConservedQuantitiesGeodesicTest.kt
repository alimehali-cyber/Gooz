package com.zig.gargantua.geodesic

import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/**
 * Validates Requirement 4: Kerr conserved quantities along photon geodesics.
 * Verifies that energy E, axial angular momentum L_z, and Carter constant Q remain constant
 * and their numerical drifts decrease when tightening the integration step size.
 */
class KerrConservedQuantitiesGeodesicTest {

    @Test
    fun conservedQuantitiesPreservedAndDriftDecreasesWithStepRefinement() {
        val M = 1.0
        val a = 0.7
        val spacetime = KerrSchildSpacetime(M, a)

        // Deflecting ray in off-equatorial plane
        val x0 = 15.0
        val y0 = 10.0
        val z0 = 8.0
        val dx = -0.7
        val dy = -0.5
        val dz = 0.4

        val initialPhoton = CameraModel.createNullStateFromDirection(
            spacetime, x0, y0, z0, dx, dy, dz
        )

        val q0 = initialPhoton.conservedCarterQ(spacetime.params)
        val lz0 = initialPhoton.conservedLz
        val e0 = initialPhoton.energy

        // Backward-traced (past-directed) photon: covector normalised to p_t = +1, so E = -p_t = -1
        assertEquals("Energy E = -p_t must equal -1.0 for the backward-traced ray", -1.0, e0, 1e-15)

        // Compare two step sizes: coarse (h = 0.05) vs fine (h = 0.01)
        val stepSizes = listOf(0.05, 0.01)
        val maxDriftsQ = mutableListOf<Double>()
        val maxDriftsLz = mutableListOf<Double>()

        for (h in stepSizes) {
            val integrator = KerrPhotonIntegrator(spacetime, escapeRadius = 50.0, maxSteps = 1000)
            val result = integrator.traceRay(initialPhoton, recordPath = true, fixedStepSize = h)

            var maxDriftQ = 0.0
            var maxDriftLz = 0.0
            val path = result.path ?: emptyList()

            assertTrue("Path must record at least 50 steps", path.size >= 50)

            for (st in path) {
                // 1. Energy E is strictly conserved
                assertEquals("Energy E = -p_t must remain constant", e0, st.energy, 1e-14)

                // 2. L_z drift
                val driftLz = abs(st.conservedLz - lz0)
                if (driftLz > maxDriftLz) maxDriftLz = driftLz

                // 3. Carter Q drift
                val driftQ = abs(st.conservedCarterQ(spacetime.params) - q0) / abs(q0)
                if (driftQ > maxDriftQ) maxDriftQ = driftQ
            }

            maxDriftsQ.add(maxDriftQ)
            maxDriftsLz.add(maxDriftLz)

            // Drift bounds
            assertTrue("L_z drift must be < 1e-8 for step $h, got $maxDriftLz", maxDriftLz < 1e-8)
            assertTrue("Carter Q drift must be < 1e-6 for step $h, got $maxDriftQ", maxDriftQ < 1e-6)
        }

        // Finer step size strictly reduces numerical drift
        assertTrue(
            "Finer step size must reduce Carter Q drift (coarse: ${maxDriftsQ[0]}, fine: ${maxDriftsQ[1]})",
            maxDriftsQ[1] < maxDriftsQ[0]
        )
        assertTrue(
            "Finer step size must reduce L_z drift (coarse: ${maxDriftsLz[0]}, fine: ${maxDriftsLz[1]})",
            maxDriftsLz[1] <= maxDriftsLz[0]
        )
    }
}
