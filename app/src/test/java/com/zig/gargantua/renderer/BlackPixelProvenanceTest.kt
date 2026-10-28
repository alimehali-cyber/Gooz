package com.zig.gargantua.renderer

import com.zig.gargantua.disk.KerrIsco
import com.zig.gargantua.geodesic.GpuEquivalentIntegrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Provenance of the dark pixels between the thin left-hand ring and the disk image of the default frame
 * (camera r = 32 M, inclination 80 deg, a = 0.8 M, 540x1200 ray grid). Traced with the shader mirror
 * GpuEquivalentIntegrator: every subray of such a pixel escapes after crossing the equatorial plane only
 * inside the ISCO (plunge region, no emitting gas in the disk model), so the geodesic pass already
 * writes sky radiance there; the darkness is not produced by a later stage. An emitting neighbour ray
 * crosses the plane inside [r_in, r_out] and is shaded.
 */
class BlackPixelProvenanceTest {
    private val mass = 1.0f
    private val spin = 0.8f
    private val rIn = KerrIsco.compute(1.0, 0.8).toFloat()

    // Camera basis of GargantuaRenderer.onDrawFrame for GargantuaRenderState defaults.
    private fun trace(stX: Float, stY: Float): GpuEquivalentIntegrator.GpuRayResult {
        val incl = Math.toRadians(80.0)
        val az = 0.0
        val dist = 32.0
        val camPos = floatArrayOf((dist * sin(incl) * cos(az)).toFloat(), (dist * sin(incl) * sin(az)).toFloat(), (dist * cos(incl)).toFloat())
        val fwd = doubleArrayOf(-sin(incl) * cos(az), -sin(incl) * sin(az), -cos(incl))
        val right = doubleArrayOf(-sin(az), cos(az), 0.0)
        var up = doubleArrayOf(right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
        val upLen = sqrt(up.sumOf { it * it })
        up = up.map { it / upLen }.toDoubleArray()
        val fov = tan(Math.toRadians(22.5))
        val dir = DoubleArray(3) { fwd[it] + right[it] * stX * fov + up[it] * stY * fov }
        val len = sqrt(dir.sumOf { it * it })
        val rayDir = FloatArray(3) { (dir[it] / len).toFloat() }
        return GpuEquivalentIntegrator.traceRay(
            mass, spin * mass, camPos, rayDir, maxSteps = 220, enableDisk = true,
            diskInnerRadius = rIn, diskOuterRadius = 22.0f
        )
    }

    @Test
    fun darkGapPixelIsAnEscapedRayWhosePlaneCrossingsAreAllInsideTheIsco() {
        // Base ray of ray-grid pixel (199, 606): st = (2 * 199.5 - 540, 2 * 606.5 - 1200) / 540.
        val ray = trace((2.0f * 199.5f - 540.0f) / 540.0f, (2.0f * 606.5f - 1200.0f) / 540.0f)
        assertEquals("escaped", 2, ray.rayState)
        assertEquals("two equatorial-plane crossings", 2, ray.crossings.size)
        ray.crossings.forEach { c ->
            assertTrue("plane crossing at r = ${c.rHit} lies in the plunge region", c.rHit < rIn)
            assertTrue("plunge-region crossing is not an emitting disk hit", !c.accepted)
            assertEquals(0.0f, c.contribution.sum(), 0.0f)
        }
        assertEquals("no emitting disk crossings", 0, ray.diskCrossings)
        assertEquals("no disk radiance: the pixel is sky from the geodesic pass on", 0.0f, ray.accumulatedRadiance.sum(), 0.0f)
        assertEquals(1.0f, ray.transmittance, 0.0f)
    }

    @Test
    fun emittingNeighbourRayIsShadedAndCountedSeparatelyFromItsPlaneCrossings() {
        // Corner subray (+x, +y) of ray-grid pixel (208, 643) (M7 offset 0.5 px).
        val px = 2.0f / 540.0f
        val ray = trace((2.0f * 208.5f - 540.0f) / 540.0f - 0.5f * px, (2.0f * 643.5f - 1200.0f) / 540.0f + 0.5f * px)
        val emitting = ray.crossings.filter { it.accepted }
        val plunge = ray.crossings.filter { !it.accepted && it.rHit < rIn }
        assertTrue("at least one emitting crossing (${ray.crossings.map { it.rHit }})", emitting.isNotEmpty())
        assertEquals(emitting.size, ray.diskCrossings)
        assertTrue(ray.crossings.size >= emitting.size + plunge.size)
        emitting.forEach { c ->
            assertTrue(c.rHit >= rIn && c.rHit <= 22.0f)
            assertTrue("emitting crossing contributes radiance", c.contribution.sum() > 0.0f)
        }
        assertTrue(ray.accumulatedRadiance.sum() > 0.0f)
    }

    @Test
    fun onlyCompositeStageThatCanZeroANonzeroSampleIsTheSharpeningClamp() {
        // Stage audit of the post-geodesic chain: bright-pass/blur only feed the additive bloom, the
        // exposure/ACES/gamma chain maps positive luminance to positive output, and the shadow guard
        // only fires for alpha <= 0.5 with |rgb|^2 <= 1e-7 (a pure captured pixel). The unsharp term
        // color += (color - neighborAvg) * 0.45 followed by max(color, 0) is the one operation that sends a
        // positive sample to exactly zero (when neighborAvg > color * (1 + 1 / 0.45)).
        val composite = java.io.File(
            listOf("app/src/main/assets/shaders/gargantua_composite.frag", "src/main/assets/shaders/gargantua_composite.frag")
                .first { java.io.File(it).exists() }
        ).readText()
        assertTrue(composite.contains("color += (color - neighborAvg) * 0.45;"))
        assertTrue(composite.contains("color = max(color, vec3(0.0));"))
        assertTrue(composite.contains("if (hdr.a <= 0.5) {"))
        assertTrue(composite.contains("if (dot(hdr.rgb, hdr.rgb) <= 1.0e-7) {"))
        // Known case (surface pixel 398, 1210 of the 1080x2400 composite, from a CPU execution of
        // gargantua_geodesic.frag and the composite chain): bilinear HDR sample rgb (0.1958, 0.1205, 0.0409); average of the four +/-1 texel samples
        // (0.7194, 0.5302, 0.3229), all with alpha > 0.5, so the sharpening branch runs and every channel
        // is clamped to exactly 0 although the input sample is nonzero.
        val color = floatArrayOf(0.1958106f, 0.12046815f, 0.04094476f)
        val neighbourAverage = floatArrayOf(0.71936166f, 0.530156f, 0.3229143f)
        for (c in 0 until 3) {
            assertTrue(color[c] > 0.0f)
            assertEquals(0.0f, maxOf(color[c] + (color[c] - neighbourAverage[c]) * 0.45f, 0.0f), 0.0f)
        }
    }
}
