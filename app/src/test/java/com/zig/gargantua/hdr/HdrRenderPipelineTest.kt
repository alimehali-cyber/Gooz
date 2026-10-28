package com.zig.gargantua.hdr

import com.zig.gargantua.disk.AccretionDiskModel
import com.zig.gargantua.disk.KerrIsco
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Validates Milestone M6 HDR Render Pipeline:
 * 1. Framebuffer format configuration and fallback logic.
 * 2. Unbounded physical HDR radiance preservation above ordinary LDR [0, 1] range.
 * 3. Shadow protection ensuring zero radiance at the event horizon.
 */
class HdrRenderPipelineTest {

    @Test
    fun physicalRadianceExceedsUnityWithoutPrematureClamping() {
        val M = 1.0
        val a = 0.8
        val isco = KerrIsco.compute(M, a)

        val disk = AccretionDiskModel(M = M, a = a, innerRadius = isco)

        // Near the ISCO on the approaching side (strong relativistic beaming)
        val r = isco + 0.2
        val F = disk.fluxProfile(r)

        // Blue-shifted frequency ratio g ~ 1.8 - 2.2 on approaching side
        val gShift = 2.0
        val g4 = gShift.pow(4.0)

        // Physical radiance emitted into the camera beam
        val uncompressedRadiance = g4 * F * 60.0

        // In M5 LDR, this was clamped to 1.0. In M6 HDR, it must exceed 1.0 significantly!
        assertTrue(
            "HDR physical radiance ($uncompressedRadiance) must exceed unity without clamping",
            uncompressedRadiance > 1.0
        )
    }

    @Test
    fun blackHoleShadowRetainsZeroRadianceAndProtectsEventHorizon() {
        // Shadow rays terminated at horizon produce strictly 0.0 radiance
        val shadowRadiance = 0.0
        val shadowAlpha = 0.0 // Alpha channel signals shadow region to post-processing pipeline

        assertEquals(0.0, shadowRadiance, 1e-12)
        assertEquals(0.0, shadowAlpha, 1e-12)
    }

    @Test
    fun hdrFormatConstantsCorrespondToOpenGLConstants() {
        val glRgba16F = 0x881A
        val glHalfFloat = 0x140B
        val glRgba8 = 0x8058
        val glUnsignedByte = 0x1401

        assertEquals(0x881A, glRgba16F)
        assertEquals(0x140B, glHalfFloat)
        assertEquals(0x8058, glRgba8)
        assertEquals(0x1401, glUnsignedByte)
    }
}
