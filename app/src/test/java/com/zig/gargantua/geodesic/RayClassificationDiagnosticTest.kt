package com.zig.gargantua.geodesic

import com.zig.gargantua.disk.AccretionDiskModel
import com.zig.gargantua.disk.KerrIsco
import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Diagnostic test quantifying ray classification across a representative viewport frame:
 * 1. Percentage of pixels captured (black hole shadow)
 * 2. Percentage of pixels escaped (asymptotic background)
 * 3. Percentage of pixels unresolved (must be 0.0%)
 * 4. Percentage of pixels with disk emission
 * 5. Percentage of background pixels
 * 6. Number / percentage of unresolved pixels near the visible ring (must be 0)
 */
class RayClassificationDiagnosticTest {

    enum class RayClassification {
        CAPTURED,
        ESCAPED,
        DISK_EMISSION,
        UNRESOLVED
    }

    data class DiagnosticReport(
        val totalRays: Int,
        val capturedCount: Int,
        val escapedCount: Int,
        val diskEmissionCount: Int,
        val unresolvedCount: Int,
        val ringUnresolvedCount: Int,
        val capturedPercent: Double,
        val escapedPercent: Double,
        val diskEmissionPercent: Double,
        val unresolvedPercent: Double,
        val backgroundPercent: Double,
        val ringUnresolvedPercent: Double
    )

    private fun evaluateRepresentativeFrame(
        samplesX: Int = 40,
        samplesY: Int = 40,
        maxSteps: Int = 180
    ): DiagnosticReport {
        val M = 1.0f
        val a = 0.8f
        val diskInnerRadius = KerrIsco.compute(M.toDouble(), (a * M).toDouble()).toFloat()
        val diskOuterRadius = 22.0f

        val dist = 24.0f
        val inclRad = Math.toRadians(82.0).toFloat()
        val azRad = Math.toRadians(0.0).toFloat()

        val camX = dist * sin(inclRad) * cos(azRad)
        val camY = dist * sin(inclRad) * sin(azRad)
        val camZ = dist * cos(inclRad)

        val fwdLen = sqrt(camX * camX + camY * camY + camZ * camZ)
        val fwdX = -camX / fwdLen
        val fwdY = -camY / fwdLen
        val fwdZ = -camZ / fwdLen

        val rightLen = sqrt(fwdY * fwdY + fwdX * fwdX)
        val rX = fwdY / rightLen
        val rY = -fwdX / rightLen
        val rZ = 0.0f

        val upX = rY * fwdZ - rZ * fwdY
        val upY = rZ * fwdX - rX * fwdZ
        val upZ = rX * fwdY - rY * fwdX

        val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()

        var captured = 0
        var escaped = 0
        var disk = 0
        var unresolved = 0
        var ringUnresolved = 0


        val classifications = Array(samplesY) { Array(samplesX) { RayClassification.UNRESOLVED } }

        for (row in 0 until samplesY) {
            val stY = 1.0f - row * (2.0f / (samplesY - 1))
            for (col in 0 until samplesX) {
                val stX = -1.0f + col * (2.0f / (samplesX - 1))

                // Initial ray direction
                val rdx = fwdX + rX * (stX * fovScale) + upX * (stY * fovScale)
                val rdy = fwdY + rY * (stX * fovScale) + upY * (stY * fovScale)
                val rdz = fwdZ + rZ * (stX * fovScale) + upZ * (stY * fovScale)
                val rdLen = sqrt(rdx * rdx + rdy * rdy + rdz * rdz)
                val rayDir = floatArrayOf(rdx / rdLen, rdy / rdLen, rdz / rdLen)

                // The shader algorithm (past-directed root, step policy, capture limiter, near-critical
                // continuation, KS disk radius, safety rule) is GpuEquivalentIntegrator.traceRay; this test
                // no longer keeps its own copy of the loop.
                val result = GpuEquivalentIntegrator.traceRay(
                    M, a, floatArrayOf(camX, camY, camZ), rayDir, maxSteps = maxSteps,
                    enableDisk = true, diskInnerRadius = diskInnerRadius, diskOuterRadius = diskOuterRadius
                )
                val outcome = when {
                    result.isDiskHit -> RayClassification.DISK_EMISSION
                    result.isCaptured -> RayClassification.CAPTURED
                    result.isEscaped -> RayClassification.ESCAPED
                    else -> RayClassification.UNRESOLVED
                }

                classifications[row][col] = outcome
                when (outcome) {
                    RayClassification.CAPTURED -> captured++
                    RayClassification.ESCAPED -> escaped++
                    RayClassification.DISK_EMISSION -> disk++
                    RayClassification.UNRESOLVED -> unresolved++
                }
            }
        }

        // Identify unresolved pixels neighboring the visible disk ring
        for (r in 0 until samplesY) {
            for (c in 0 until samplesX) {
                if (classifications[r][c] == RayClassification.UNRESOLVED) {
                    var nearDisk = false
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until samplesY && nc in 0 until samplesX) {
                                if (classifications[nr][nc] == RayClassification.DISK_EMISSION) {
                                    nearDisk = true
                                }
                            }
                        }
                    }
                    if (nearDisk) ringUnresolved++
                }
            }
        }

        val total = samplesX * samplesY
        return DiagnosticReport(
            totalRays = total,
            capturedCount = captured,
            escapedCount = escaped,
            diskEmissionCount = disk,
            unresolvedCount = unresolved,
            ringUnresolvedCount = ringUnresolved,
            capturedPercent = captured * 100.0 / total,
            escapedPercent = escaped * 100.0 / total,
            diskEmissionPercent = disk * 100.0 / total,
            unresolvedPercent = unresolved * 100.0 / total,
            backgroundPercent = escaped * 100.0 / total,
            ringUnresolvedPercent = ringUnresolved * 100.0 / total
        )
    }

    @Test
    fun representativeFrameClassificationMeetsDiagnosticGates() {
        val report = evaluateRepresentativeFrame(samplesX = 40, samplesY = 40, maxSteps = 180)

        println("=== GARGANTUA RAY CLASSIFICATION TELEMETRY REPORT ===")
        println("Total rays evaluated: ${report.totalRays}")
        println("Captured (Shadow):     ${report.capturedCount} (${String.format("%.2f", report.capturedPercent)}%)")
        println("Escaped (Background):  ${report.escapedCount} (${String.format("%.2f", report.escapedPercent)}%)")
        println("Disk Emission:         ${report.diskEmissionCount} (${String.format("%.2f", report.diskEmissionPercent)}%)")
        println("Unresolved:            ${report.unresolvedCount} (${String.format("%.2f", report.unresolvedPercent)}%)")
        println("Unresolved Near Ring:  ${report.ringUnresolvedCount} (${String.format("%.2f", report.ringUnresolvedPercent)}%)")
        println("=====================================================")

        // Verification Gates:
        // 1. Unresolved rays must be exactly 0 (no pixel should be unresolved or timeout into starfield)
        assertEquals("Percentage of unresolved pixels must be strictly 0.0%", 0.0, report.unresolvedPercent, 1e-6)
        assertEquals("Number of unresolved pixels near visible ring must be 0", 0, report.ringUnresolvedCount)

        // 2. Both shadow and disk emission must be prominently present in the default framing
        assertTrue("Black hole shadow must cover > 5% of default viewport", report.capturedPercent > 5.0)
        assertTrue("Accretion disk must cover > 30% of default viewport", report.diskEmissionPercent > 30.0)

        // 3. Background must be cleanly identified as escaped
        assertTrue("Escaped background rays must be present", report.escapedPercent > 0.0)
    }
}
