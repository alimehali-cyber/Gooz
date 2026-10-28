package com.zig.gargantua.disk

import com.zig.gargantua.geodesic.PhotonState4D
import com.zig.gargantua.geodesic.ProceduralSky
import com.zig.gargantua.physics.KerrSchildSpacetime
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects and evaluates relativistic photon intersections with the equatorial accretion disk.
 */
object DiskIntersection {

    data class DiskHitResult(
        val hitX: Double,
        val hitY: Double,
        val hitZ: Double = 0.0,
        val rHit: Double,
        val hitPx: Double,
        val hitPy: Double,
        val hitPz: Double,
        val frequencyShift: Double,
        val observedRadiance: Double,
        val observedColor: ProceduralSky.ColorRGB
    )

    /**
     * Checks whether the segment connecting previous and current photon states intersects
     * the thin equatorial disk (Z = 0) within the radial domain [r_in, r_out].
     *
     * @return [DiskHitResult] if an intersection occurred within the disk domain, or null otherwise.
     */
    fun checkIntersection(
        previous: PhotonState4D,
        current: PhotonState4D,
        spacetime: KerrSchildSpacetime,
        disk: AccretionDiskModel,
        camX: Double,
        camY: Double,
        camZ: Double
    ): DiskHitResult? {
        val z1 = previous.z
        val z2 = current.z

        // Test for crossing of equatorial plane Z = 0
        if (z1 * z2 > 0.0 || z1 == z2) return null

        // Linear interpolation parameter tau, clamped to [0, 1] as in the shader
        val tau = (-z1 / (z2 - z1)).coerceIn(0.0, 1.0)

        val hitX = previous.x + tau * (current.x - previous.x)
        val hitY = previous.y + tau * (current.y - previous.y)
        // Kerr-Schild radius of the equatorial point (Z = 0), exactly as gargantua_geodesic.frag:
        // rHit = sqrt(max(0, X^2 + Y^2 - a^2)). The cylindrical radius sqrt(X^2 + Y^2) is not r for a != 0.
        val a = spacetime.a
        val rHit = sqrt(max(0.0, hitX * hitX + hitY * hitY - a * a))

        // Check disk radial domain [r_in, r_out]
        if (!disk.containsRadius(rHit)) return null

        val hitPx = previous.p_x + tau * (current.p_x - previous.p_x)
        val hitPy = previous.p_y + tau * (current.p_y - previous.p_y)
        val hitPz = previous.p_z + tau * (current.p_z - previous.p_z)

        // Shader: gShift = clamp(uObs0 / denomG, 0.05, 5.0)
        val g = disk.frequencyShift(spacetime, hitX, hitY, hitPx, hitPy, camX, camY, camZ).coerceIn(0.05, 5.0)
        val tEmit = disk.emittedTemperature(rHit)
        val tObs = g * tEmit
        val radiance = disk.observedRadiance(rHit, g)
        val color = disk.spectralColor(tObs, radiance)

        return DiskHitResult(
            hitX = hitX,
            hitY = hitY,
            hitZ = 0.0,
            rHit = rHit,
            hitPx = hitPx,
            hitPy = hitPy,
            hitPz = hitPz,
            frequencyShift = g,
            observedRadiance = radiance,
            observedColor = color
        )
    }
}
