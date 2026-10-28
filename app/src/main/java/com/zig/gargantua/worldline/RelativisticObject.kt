package com.zig.gargantua.worldline

import com.zig.gargantua.geodesic.ProceduralSky

/**
 * Synthetic test marker with coordinate-defined finite radius moving along an M8 timelike Kerr worldline.
 *
 * M9 GEOMETRY DEFINITION (Option B):
 * The test marker is deliberately defined as a coordinate sphere in the Kerr-Schild Cartesian chart (X, Y, Z)
 * at emission coordinate time T:
 *
 *     || X_ray - X_obj(T) || <= R_coord
 *
 * where R_coord is the coordinate radius in geometrized units M. It is not an invariant proper-radius sphere
 * in the emitter's instantaneous rest frame, but a rigorously defined coordinate marker in the shared
 * Kerr-Schild spacetime chart.
 *
 * @param worldline M8 physical timelike worldline.
 * @param radius Coordinate radius of the synthetic marker in Kerr-Schild Cartesian units M.
 * @param baseColor Distinctive emissive base color (default vivid cyan/electric azure).
 * @param emissiveRadiance Base unshifted surface radiance.
 * @param enabled Whether the object is active in the scene.
 */
data class RelativisticObject(
    val worldline: RelativisticWorldline,
    val radius: Double = 0.45,
    val baseColor: ProceduralSky.ColorRGB = ProceduralSky.ColorRGB(0.15, 0.85, 1.0),
    val emissiveRadiance: Double = 35.0,
    val enabled: Boolean = true
) {
    init {
        require(radius > 0.0) { "Object coordinate radius must be positive, got $radius" }
        require(emissiveRadiance >= 0.0) { "Emissive radiance must be non-negative" }
    }

    /** Position at coordinate time T in Kerr-Schild Cartesian coordinates. */
    fun positionAt(T: Double): DoubleArray = worldline.positionAt(T)

    /** 4-velocity at coordinate time T in Kerr-Schild coordinates. */
    fun fourVelocityAt(T: Double): DoubleArray = worldline.fourVelocityAt(T)
}
