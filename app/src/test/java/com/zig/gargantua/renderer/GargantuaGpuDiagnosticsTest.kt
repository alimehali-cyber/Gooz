package com.zig.gargantua.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards for the TEMPORARY physical-GPU diagnostics: with the DIAG view OFF the renderer must compile
 * and run exactly the shipped programs, and the diagnostics' own measurements must not be subject to
 * the precision behaviour they measure.
 */
class GargantuaGpuDiagnosticsTest {

    private fun mainFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/$relative", "src/main/$relative").map { File(dir, it) }.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("main source not found: $relative")
    }

    private fun shader(name: String) = mainFile("assets/shaders/$name").readText()

    private val animationBuild = ShaderSource.buildGeodesicVariant(shader("gargantua_geodesic.frag"), listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE"))
    private val animationMaterial = ShaderSource.buildGeodesicVariant(
        shader("gargantua_geodesic.frag"), listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS")
    )

    @Test
    fun asShippedPrecisionReturnsTheShippedAnimationProgramsUnchanged() {
        for (source in listOf(animationBuild, animationMaterial)) {
            assertEquals(source, GargantuaGpuDiagnostics.applyRecordPrecision(source, GargantuaGpuDiagnostics.RecordPrecision.AS_SHIPPED))
        }
        assertEquals(GargantuaGpuDiagnostics.RecordPrecision.AS_SHIPPED, GargantuaGpuDiagnostics.RecordPrecision.fromIndex(0))
        assertEquals(0, GargantuaRenderState().diagnosticRecordPrecision)
        assertEquals(0, GargantuaRenderState().diagnosticView)
        assertEquals(GargantuaGpuDiagnostics.View.OFF, GargantuaGpuDiagnostics.View.fromIndex(GargantuaRenderState().diagnosticView))
    }

    @Test
    fun precisionVariantsOnlyInsertThePrecisionStatementsAfterVersion() {
        for (source in listOf(animationBuild, animationMaterial)) {
            val highInt = GargantuaGpuDiagnostics.applyRecordPrecision(source, GargantuaGpuDiagnostics.RecordPrecision.HIGHP_INT)
            assertEquals(source.replaceFirst("#version 300 es", "#version 300 es\nprecision highp int;"), highInt)
            val both = GargantuaGpuDiagnostics.applyRecordPrecision(source, GargantuaGpuDiagnostics.RecordPrecision.HIGHP_INT_SAMPLER)
            assertEquals(source.replaceFirst("#version 300 es", "#version 300 es\nprecision highp int;\nprecision highp sampler2D;"), both)
            assertTrue(both.startsWith("#version 300 es\n"))
        }
    }

    @Test
    fun preToneMappingViewIsTheProductionCompositeCutImmediatelyBeforeAces() {
        val composite = shader("gargantua_composite.frag")
        val pre = GargantuaGpuDiagnostics.preToneMappingCompositeSource(composite)
        val anchor = "    float inputLuma = dot(exposed,"
        val cut = composite.indexOf(anchor)
        assertTrue(cut > 0)
        // Everything up to the ACES step (shadow guard, sharpening, bloom, exposure) is byte-identical.
        assertEquals(composite.substring(0, cut), pre.substring(0, cut))
        assertTrue(pre.substring(cut).startsWith("    fragColor = vec4(exposed, 1.0);\n    return;\n$anchor"))
        assertTrue(composite.substring(0, cut).contains("vec3 exposed = color * u_Exposure;"))
    }

    @Test
    fun diagnosticsShadersMeasureWithExplicitHighPrecision() {
        for (name in listOf("gargantua_diag_stats.frag", "gargantua_diag_probe.frag", "gargantua_diag_view.frag")) {
            val source = shader(name)
            assertTrue(name, source.startsWith("#version 300 es\n"))
            assertTrue(name, source.contains("precision highp int;"))
            assertTrue(name, source.contains("precision highp usampler2D;"))
            assertTrue(name, source.contains("precision highp sampler2D;"))
        }
        // Exact float bits and counts are returned through an integer target, not a normalized one.
        assertTrue(shader("gargantua_diag_probe.frag").contains("o = floatBitsToUint(texelFetch(u_A, p, 0));"))
        assertTrue(shader("gargantua_diag_stats.frag").contains("layout(location = 0) out uvec4 o;"))
    }

    @Test
    fun rendererCallsTheDiagnosticsOnlyWhileADiagViewIsActive() {
        val lines = mainFile("java/com/zig/gargantua/renderer/GargantuaRenderer.kt").readLines()
        val calls = lines.withIndex().filter { (_, line) ->
            line.contains("gpuDiagnostics.") &&
                !line.contains("gpuDiagnostics.forgetContext()") &&
                !line.contains("gpuDiagnostics.release()")
        }
        assertTrue(calls.size >= 15)
        for ((index, line) in calls) {
            val guarded = (maxOf(0, index - 3)..index).any {
                lines[it].contains("if (diagActiveThisFrame") || lines[it].contains("if (userState.diagnosticView != 0)")
            }
            assertTrue("unguarded diagnostics call at line ${index + 1}: $line", guarded)
        }
        assertTrue(lines.any { it.contains("diagActiveThisFrame = state.diagnosticView != 0") })
        assertTrue(lines.any { it.contains("gpuDiagnosticsReport = if (diagActiveThisFrame) gpuDiagnostics.reportText else \"\"") })
        // With DIAG OFF the frame renders the user's state object itself (no forensic overrides).
        assertTrue(lines.any {
            it.contains("val state = if (userState.diagnosticView != 0) gpuDiagnostics.forensicState(userState, frameStartNanos) else userState")
        })
        assertTrue(lines.none { it.contains("stateHolder.updateState") && it.contains("diagnostic") })
    }

    @Test
    fun probeCoordinatesInvertTheGeodesicStFormula() {
        val w = 540
        val h = 1200
        val m = minOf(w, h).toFloat()
        for (x in listOf(0, 17, 269, 270, 539)) {
            for (y in listOf(0, 311, 600, 1199)) {
                // gargantua_geodesic.frag: st = (gl_FragCoord.xy * 2.0 - u_Resolution.xy) / min(u_Resolution.x, u_Resolution.y)
                val stX = ((x + 0.5f) * 2f - w) / m
                val stY = ((y + 0.5f) * 2f - h) / m
                val texel = GargantuaGpuDiagnostics.stToTexel(stX, stY, w, h)
                assertEquals(x, texel[0])
                assertEquals(y, texel[1])
            }
        }
        assertEquals(12, GargantuaGpuDiagnostics.ANIMATION_PROBES_ST.size)
        assertEquals(10, GargantuaGpuDiagnostics.DISK_PROBES)
    }
}
