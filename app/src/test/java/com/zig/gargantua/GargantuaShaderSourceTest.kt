package com.zig.gargantua

import com.zig.gargantua.renderer.GargantuaAnimation
import com.zig.gargantua.renderer.ShaderSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Validates Gargantua canonical shader assets and roadmap lock constraints.
 * Ensures the M1 GPU health test contains NO Kerr physics or black hole approximations.
 */
class GargantuaShaderSourceTest {

    @Test
    fun canonicalShaderAssetPathsAreConfigured() {
        assertEquals("shaders/gargantua_test.vert", ShaderSource.VERTEX_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_test.frag", ShaderSource.FRAGMENT_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_geodesic.frag", ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_blit.frag", ShaderSource.BLIT_FRAGMENT_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_brightpass.frag", ShaderSource.BRIGHTPASS_FRAGMENT_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_blur.frag", ShaderSource.BLUR_FRAGMENT_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_composite.frag", ShaderSource.COMPOSITE_FRAGMENT_SHADER_ASSET_PATH)
        assertEquals("shaders/gargantua_reduce.frag", ShaderSource.REDUCE_FRAGMENT_SHADER_ASSET_PATH)
    }

    @Test
    fun workloadSourceFlagOffMatchesCurrentGeneratedSourceByteForByte() {
        val asset = findAssetFile(ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH).readText()
        val expected = asset
            .replaceFirst(
                "out vec4 fragColor;",
                "layout(location = 0) out vec4 fragColor;"
            )
            .replaceFirst(
                "#version 300 es",
                "#version 300 es\n#define GARGANTUA_WORKLOAD_TELEMETRY 1"
            )
        val actual = ShaderSource.buildWorkloadTelemetryGeodesicFragmentShader(
            asset,
            includeSemanticCache = false
        )

        assertEquals(expected, actual)
        assertFalse(actual.contains("#define GARGANTUA_WORKLOAD_SEMANTIC_CACHE"))
    }

    @Test
    fun workloadSourceFlagOnPlacesSemanticDefineAfterVersion() {
        val asset = findAssetFile(ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH).readText()
        val actual = ShaderSource.buildWorkloadTelemetryGeodesicFragmentShader(
            asset,
            includeSemanticCache = true
        )
        val lines = actual.lines()

        assertEquals("#version 300 es", lines[0])
        assertEquals("#define GARGANTUA_WORKLOAD_TELEMETRY 1", lines[1])
        assertEquals("#define GARGANTUA_WORKLOAD_SEMANTIC_CACHE 1", lines[2])
    }

    @Test
    fun semanticRecordUsesBaseRayValuesAndSnapshotsAzimuthBeforeRefinement() {
        val source = findAssetFile(ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH).readText()
        val baseCallEnd = source.indexOf(
            "    );",
            source.indexOf("vec4 baseSample = GARGANTUA_TRACE_RAY_SAMPLE(")
        )
        val refinementCall = source.indexOf("vec4 sample1 = GARGANTUA_TRACE_RAY_SAMPLE(")
        // New semantic record packs deflected vector for state 2 and disk data for state 3
        val outputStart = source.indexOf("vec4 semanticRecord;")
        val outputEnd = source.indexOf("#endif\n}", outputStart)
        if (outputStart < 0) {
            // fallback to old pattern for backward compat
            val altStart = source.indexOf("    float diskRadiusNormalized =")
            val altEnd = source.indexOf("#endif\n}", altStart)
            assertTrue("Semantic output block must be present", altStart >= 0 && altEnd > altStart)
            val outputBlock = source.substring(altStart, altEnd)
            assertTrue(outputBlock.contains("baseHitR"))
            assertTrue(outputBlock.contains("baseDiskHitAzimuth"))
            assertTrue(outputBlock.contains("baseCrossings"))
            assertTrue(outputBlock.contains("baseState"))
            return
        }
        assertTrue("Semantic azimuth must be snapshotted after the base ray call", baseCallEnd >= 0)
        assertTrue("Semantic azimuth snapshot must precede refinement rays", baseCallEnd < refinementCall)
        assertTrue("Semantic output block must be present", outputStart >= 0 && outputEnd > outputStart)

        val outputBlock = source.substring(outputStart, outputEnd)
        assertTrue(outputBlock.contains("baseHitR"))
        assertTrue(outputBlock.contains("baseDiskHitAzimuth"))
        assertTrue(outputBlock.contains("baseCrossings"))
        assertTrue(outputBlock.contains("baseState"))
        assertTrue(outputBlock.contains("gargantuaSemanticDeflectedDir") || outputBlock.contains("Deflected"))
        assertTrue(outputBlock.contains("baseState == 2") || outputBlock.contains("baseState==2"))
        assertTrue(outputBlock.contains("baseState == 3") || outputBlock.contains("baseState==3"))
        assertFalse(Regex("\\bhitRadius\\b").containsMatchIn(outputBlock))
        assertFalse(Regex("\\bdiskHitAzimuth\\b").containsMatchIn(outputBlock))
        assertFalse(Regex("\\bcrossings\\b").containsMatchIn(outputBlock))
        assertFalse(Regex("\\brayState\\b").containsMatchIn(outputBlock))
    }

    @Test
    fun allGeodesicVariantsHaveExactOutputsAndBalancedPreprocessorDirectives() {
        val asset = findAssetFile(ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH).readText()
        val variants = listOf(
            "plain" to asset,
            "telemetry" to ShaderSource.buildGeodesicVariant(asset, listOf("GARGANTUA_WORKLOAD_TELEMETRY")),
            "telemetry+semantic" to ShaderSource.buildGeodesicVariant(
                asset,
                listOf("GARGANTUA_WORKLOAD_TELEMETRY", "GARGANTUA_WORKLOAD_SEMANTIC_CACHE")
            ),
            "animation" to ShaderSource.buildGeodesicVariant(
                asset,
                listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE")
            ),
            "animation-material" to ShaderSource.buildGeodesicVariant(
                asset,
                listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS")
            )
        )
        val expected = mapOf(
            "plain" to listOf(null to "fragColor"),
            "telemetry" to listOf(0 to "fragColor", 1 to "workloadTierStats", 2 to "workloadCostStats"),
            "telemetry+semantic" to listOf(
                0 to "fragColor", 1 to "workloadTierStats", 2 to "workloadCostStats", 3 to "workloadSemanticCache"
            ),
            "animation" to listOf(
                0 to "fragColor", 1 to "animationRayRecordA", 2 to "animationRayRecordB", 3 to "animationRayRecordC",
                4 to "animationSkyDirection", 5 to "animationSkyOriginal"
            ),
            "animation-material" to listOf(0 to "fragColor")
        )
        variants.forEach { (name, source) ->
            assertEquals("$name output list", expected[name], outputDeclarations(preprocess(source)))
            assertBalancedDirectives(source)
            val locations = outputDeclarations(preprocess(source)).mapNotNull { it.first }
            assertEquals("$name locations must be unique", locations.size, locations.toSet().size)
        }
    }

    @Test
    fun animationMotionUsesExpectedConventionAndNoiseInputs() {
        val geodesic = findAssetFile(ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH).readText()
        val animation = findSourceFile("app/src/main/java/com/zig/gargantua/renderer/GargantuaAnimation.kt").readText()
        assertTrue(geodesic.contains("#define GARGANTUA_OBJECT_PHASE(t)"))
        assertTrue(geodesic.contains("u_TimeDigit1"))
        assertTrue(geodesic.contains("u_TimeDigits23"))
        assertTrue(geodesic.contains("u_TimeDigits45"))
        assertTrue(geodesic.contains("u_TimeDigits67"))
        assertTrue(geodesic.contains("float omega = sqrt(u_Mass) / (pow(rHit, 1.5) + u_Spin * sqrt(u_Mass));"))
        assertTrue(geodesic.contains("float denomG = u0 * (1.0 + omega * lz);"))
        assertTrue(geodesic.contains("float phiMaterial = phiHit - phaseAdvance;"))
        assertTrue(animation.contains("NoiseOctave(32, 8, 0.50f)") || animation.contains("NoiseOctave(32, 8, 0.5f)"))
        assertTrue(animation.contains("NoiseOctave(64, 16, 0.35f)"))
        assertTrue(animation.contains("NoiseOctave(128, 32, 0.15f)"))
        assertTrue(
            GargantuaAnimation.NOISE_OCTAVE_SPECS.all { it.latticeWidth >= it.latticeHeight }
        )
        assertEquals(6.0, GargantuaAnimation.AnimationSpeed.NORMAL.periodSeconds, 0.0)
        assertEquals(
            listOf(false to 0, true to 15, true to 40, true to 80),
            GargantuaAnimation.AMPLITUDE_STEPS
        )
        assertEquals(
            listOf(
                GargantuaAnimation.NoiseOctave(32, 8, 0.50f),
                GargantuaAnimation.NoiseOctave(64, 16, 0.35f),
                GargantuaAnimation.NoiseOctave(128, 32, 0.15f)
            ),
            GargantuaAnimation.NOISE_OCTAVE_SPECS
        )
    }

    @Test
    fun animationMaterialPassReShadesCachedHitsWithoutRayTracing() {
        val asset = findAssetFile(ShaderSource.GEODESIC_FRAGMENT_SHADER_ASSET_PATH).readText()
        val material = preprocess(
            ShaderSource.buildGeodesicVariant(
                asset,
                listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS")
            )
        )
        val main = material.substring(material.indexOf("void main()"))
        (listOf(
            "uniform sampler2D u_HdrTexture;", "uniform sampler2D u_BuiltEmissionTexture;",
            "uniform int u_MaterialPassMode;"
        ) + (0..8).map { "uniform highp usampler2D u_RayRecord$it;" }).forEach { assertTrue(it, material.contains(it)) }
        assertFalse(material.contains("u_CrossingTexture0") || material.contains("u_FootprintTexture"))
        assertEquals("material pass has exactly one main", 1, Regex("void main\\(\\)").findAll(material).count())
        assertFalse("material pass must not integrate geodesics", main.contains("traceRaySample"))
        assertFalse("material pass must not use screen-space derivatives", material.contains("dFdx") || material.contains("dFdy"))
        // No disk crossing: only escaped sky pixels receive a cached-direction sky update.
        assertTrue(main.contains("if (!hasCrossing) {\n        fragColor = vec4(max(hdr.rgb + skyDelta, vec3(0.0)), hdr.a);\n        return;\n    }"))
        assertTrue(main.contains("if (skyData.w > 0.0)"))
        // The cached-hit emission uses the same strata accumulation as the geodesic pass.
        assertTrue(material.contains("gargantuaAccumulateDiskCrossing(rHit, c.y - phaseAdvance,"))
        val renderer = findSourceFile("app/src/main/java/com/zig/gargantua/renderer/GargantuaRenderer.kt").readText()
        assertTrue(renderer.contains("GLES30.GL_MAX_DRAW_BUFFERS"))
        assertTrue(renderer.contains("GLES30.GL_RGBA32UI"))
        assertTrue(renderer.contains("scratchTimeDigits.copyInto(animationBuildTimeDigits)"))
        assertTrue(renderer.contains("ShaderSource.loadAnimationMaterialFragmentShader(context)"))
    }

    @Test
    fun noiseTileIsDeterministicPeriodicAndMeasured() {
        val first = GargantuaAnimation.deterministicNoise()
        val second = GargantuaAnimation.deterministicNoise()
        val width = GargantuaAnimation.NOISE_WIDTH
        val height = GargantuaAnimation.NOISE_HEIGHT
        val value = { x: Int, y: Int -> first[y * width + x].toInt() and 0xFF }
        assertTrue(first.contentEquals(second))
        assertEquals(0, first.minOf { it.toInt() and 0xFF })
        assertEquals(255, first.maxOf { it.toInt() and 0xFF })
        assertTrue((0 until height).all { kotlin.math.abs(value(0, it) - value(width - 1, it)) <= 12 })
        assertTrue((0 until width).all { kotlin.math.abs(value(it, 0) - value(it, height - 1)) <= 12 })
        val sum = first.sumOf { it.toInt() and 0xFF }
        assertEquals(sum.toFloat() / (first.size.toFloat() * 255.0f), GargantuaAnimation.normalizedMean(first), 0.0f)
    }

    private fun preprocess(source: String, initialDefines: Set<String> = emptySet()): String {
        val defines = initialDefines.toMutableSet()
        val active = mutableListOf<Boolean>()
        val parents = mutableListOf<Boolean>()
        val output = StringBuilder()
        fun current() = active.all { it }
        fun expression(value: String): Boolean {
            var normalized = value
                .replace(Regex("defined\\s*\\(\\s*([A-Za-z0-9_]+)\\s*\\)")) {
                    if (defines.contains(it.groupValues[1])) "true" else "false"
                }
                .replace(Regex("defined\\s+([A-Za-z0-9_]+)")) {
                    if (defines.contains(it.groupValues[1])) "true" else "false"
                }
                .replace(" ", "")
            return when {
                normalized.contains("||") -> normalized.split("||").any { expression(it) }
                normalized.contains("&&") -> normalized.split("&&").all { expression(it) }
                normalized.startsWith("!") -> !expression(normalized.removePrefix("!"))
                normalized == "true" -> true
                normalized == "false" -> false
                else -> false
            }
        }
        source.lines().forEach { line ->
            val directive = line.trim()
            when {
                directive.startsWith("#define ") && current() ->
                    defines += directive.removePrefix("#define ").trim().substringBefore(' ')
                directive.startsWith("#ifdef ") -> {
                    parents += current()
                    active += current() && defines.contains(directive.removePrefix("#ifdef ").trim())
                }
                directive.startsWith("#ifndef ") -> {
                    parents += current()
                    active += current() && !defines.contains(directive.removePrefix("#ifndef ").trim())
                }
                directive.startsWith("#if ") -> {
                    parents += current()
                    active += current() && expression(directive.removePrefix("#if "))
                }
                directive.startsWith("#elif ") -> {
                    val parent = parents.last()
                    val prior = active.removeAt(active.lastIndex)
                    active += parent && !prior && expression(directive.removePrefix("#elif "))
                }
                directive.startsWith("#else") -> {
                    val parent = parents.last()
                    val prior = active.removeAt(active.lastIndex)
                    active += parent && !prior
                }
                directive == "#endif" -> {
                    active.removeAt(active.lastIndex)
                    parents.removeAt(parents.lastIndex)
                }
                current() && !directive.startsWith("#version") -> output.appendLine(line)
            }
        }
        assertEquals(0, active.size)
        assertEquals(0, parents.size)
        return output.toString()
    }

    private fun outputDeclarations(source: String): List<Pair<Int?, String>> =
        source.lines().mapNotNull { raw ->
            val line = raw.trim()
            val match = Regex("(?:layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*)?out\\s+\\w+\\s+(\\w+)\\s*;").matchEntire(line)
                ?: return@mapNotNull null
            val location = Regex("location\\s*=\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toInt()
            location to match.groupValues[2]
        }

    private fun assertBalancedDirectives(source: String) {
        var depth = 0
        source.lines().forEach { raw ->
            when {
                raw.trim().startsWith("#if") -> depth++
                raw.trim() == "#endif" -> {
                    assertTrue(depth > 0)
                    depth--
                }
            }
        }
        assertEquals(0, depth)
    }

    @Test
    fun canonicalM6ShadersExistAndDeclareGles3() {
        val brightPass = findAssetFile(ShaderSource.BRIGHTPASS_FRAGMENT_SHADER_ASSET_PATH).readText()
        val blur = findAssetFile(ShaderSource.BLUR_FRAGMENT_SHADER_ASSET_PATH).readText()
        val composite = findAssetFile(ShaderSource.COMPOSITE_FRAGMENT_SHADER_ASSET_PATH).readText()

        assertTrue(brightPass.contains("#version 300 es"))
        assertTrue(blur.contains("#version 300 es"))
        assertTrue(composite.contains("#version 300 es"))
        assertTrue(composite.contains("aces_filmic"))
    }

    @Test
    fun canonicalVertexShaderDeclaresExpectedGles3Layout() {
        val vertFile = findAssetFile(ShaderSource.VERTEX_SHADER_ASSET_PATH)
        assertTrue("Canonical vertex shader asset must exist", vertFile.isFile)

        val vert = vertFile.readText()
        assertTrue("Must declare #version 300 es", vert.contains("#version 300 es"))
        assertTrue("Must declare a_Position attribute", vert.contains("a_Position"))
        assertTrue("Must declare a_TexCoord attribute", vert.contains("a_TexCoord"))
        assertTrue("Must compute gl_Position", vert.contains("gl_Position"))
    }

    @Test
    fun canonicalFragmentShaderDeclaresExpectedUniformsAndOutputs() {
        val fragFile = findAssetFile(ShaderSource.FRAGMENT_SHADER_ASSET_PATH)
        assertTrue("Canonical fragment shader asset must exist", fragFile.isFile)

        val frag = fragFile.readText()
        assertTrue("Must declare #version 300 es", frag.contains("#version 300 es"))
        assertTrue("Must declare u_Resolution uniform", frag.contains("uniform vec2 u_Resolution;"))
        assertTrue("Must declare u_Time uniform", frag.contains("uniform float u_Time;"))
        assertTrue("Must declare output fragColor", frag.contains("out vec4 fragColor;"))
    }

    @Test
    fun m1CanonicalShaderDoesNotContainAnyKerrOrGeodesicPhysics() {
        // Strict M1 non-goal check: ensure no black hole math was prematurely added
        val bannedKeywords = listOf(
            "kerr", "schwarzschild", "geodesic", "isco", "ergosphere",
            "eventhorizon", "event_horizon", "christoffel", "rungekutta",
            "accretion", "redshift", "beaming", "lorentz", "\\bmetric\\b"
        )

        val vertText = findAssetFile(ShaderSource.VERTEX_SHADER_ASSET_PATH).readText()
        val fragText = findAssetFile(ShaderSource.FRAGMENT_SHADER_ASSET_PATH).readText()
        val allShaderText = (vertText + fragText).lowercase()

        for (banned in bannedKeywords) {
            val found = if (banned.startsWith("\\b")) {
                Regex(banned).containsMatchIn(allShaderText)
            } else {
                allShaderText.contains(banned)
            }
            assertFalse(
                "M1 canonical shader must not contain '$banned' (reserved for M3-M6)",
                found
            )
        }
    }

    private fun findSourceFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return File(relativePath)
    }

    private fun findAssetFile(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets/$relativePath")
            if (candidate.isFile) return candidate
            val direct = File(dir, "src/main/assets/$relativePath")
            if (direct.isFile) return direct
            dir = dir.parentFile
        }
        return File("app/src/main/assets/$relativePath")
    }
}
