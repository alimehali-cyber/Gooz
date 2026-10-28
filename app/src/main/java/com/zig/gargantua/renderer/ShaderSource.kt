package com.zig.gargantua.renderer

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Loads canonical shader assets for the Gargantua GPU renderer.
 * The files in assets/shaders/ are the single canonical production source of truth.
 */
object ShaderSource {

    const val VERTEX_SHADER_ASSET_PATH = "shaders/gargantua_test.vert"
    const val FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_test.frag"
    const val GEODESIC_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_geodesic.frag"
    const val BLIT_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_blit.frag"
    const val BRIGHTPASS_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_brightpass.frag"
    const val BLUR_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_blur.frag"
    const val COMPOSITE_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_composite.frag"
    const val REDUCE_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_reduce.frag"
    const val SEMANTIC_CACHE_FALSE_COLOR_FRAGMENT_SHADER_ASSET_PATH = "shaders/gargantua_semantic_false_color.frag"

    fun loadVertexShader(context: Context): String {
        return readAsset(context, VERTEX_SHADER_ASSET_PATH)
    }

    fun loadFragmentShader(context: Context): String {
        return readAsset(context, FRAGMENT_SHADER_ASSET_PATH)
    }

    fun loadGeodesicFragmentShader(context: Context): String {
        return readAsset(context, GEODESIC_FRAGMENT_SHADER_ASSET_PATH)
    }

    /**
     * Loads the same canonical geodesic shader with optional MRT workload outputs enabled.
     * The define is inserted after #version because GLSL ES requires #version to be first.
     */
    fun loadWorkloadTelemetryGeodesicFragmentShader(
        context: Context,
        includeSemanticCache: Boolean = false
    ): String {
        val source = readAsset(context, GEODESIC_FRAGMENT_SHADER_ASSET_PATH)
        return buildWorkloadTelemetryGeodesicFragmentShader(source, includeSemanticCache)
    }

    fun loadAnimationGeodesicFragmentShader(context: Context): String {
        val source = readAsset(context, GEODESIC_FRAGMENT_SHADER_ASSET_PATH)
        return buildGeodesicVariant(source, listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE"))
    }

    /** Per-frame animation pass: re-shades the cached disk hits of the animation build (no ray tracing). */
    fun loadAnimationMaterialFragmentShader(context: Context): String {
        val source = readAsset(context, GEODESIC_FRAGMENT_SHADER_ASSET_PATH)
        return buildGeodesicVariant(
            source,
            listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS")
        )
    }

    internal fun buildWorkloadTelemetryGeodesicFragmentShader(
        source: String,
        includeSemanticCache: Boolean = false
    ): String {
        val versionLine = "#version 300 es"
        val semanticDefine = if (includeSemanticCache) {
            "\n#define GARGANTUA_WORKLOAD_SEMANTIC_CACHE 1"
        } else {
            ""
        }
        return if (source.startsWith(versionLine)) {
            // Keep the canonical production asset unchanged. The workload variant has three
            // outputs and therefore needs an explicit location for output 0 as well as the two
            // existing MRT outputs at locations 1 and 2.
            val workloadSource = source.replaceFirst(
                "out vec4 fragColor;",
                "layout(location = 0) out vec4 fragColor;"
            )
            workloadSource.replaceFirst(
                versionLine,
                "$versionLine\n#define GARGANTUA_WORKLOAD_TELEMETRY 1$semanticDefine"
            )
        } else {
            throw IllegalStateException("Canonical geodesic shader must begin with #version 300 es")
        }
    }

    internal fun buildGeodesicVariant(source: String, defines: List<String>): String {
        val versionLine = "#version 300 es"
        require(source.startsWith(versionLine)) {
            "Canonical geodesic shader must begin with #version 300 es"
        }
        val variantSource = source.replaceFirst(
            "out vec4 fragColor;",
            "layout(location = 0) out vec4 fragColor;"
        )
        val defineBlock = defines.joinToString("\n") { "#define $it 1" }
        return variantSource.replaceFirst(versionLine, "$versionLine\n$defineBlock")
    }

    fun loadReduceFragmentShader(context: Context): String {
        return readAsset(context, REDUCE_FRAGMENT_SHADER_ASSET_PATH)
    }

    fun loadSemanticCacheFalseColorFragmentShader(context: Context): String {
        return readAsset(context, SEMANTIC_CACHE_FALSE_COLOR_FRAGMENT_SHADER_ASSET_PATH)
    }

    fun loadBlitFragmentShader(context: Context): String {
        return readAsset(context, BLIT_FRAGMENT_SHADER_ASSET_PATH)
    }

    fun loadBrightPassFragmentShader(context: Context): String {
        return readAsset(context, BRIGHTPASS_FRAGMENT_SHADER_ASSET_PATH)
    }

    fun loadBlurFragmentShader(context: Context): String {
        return readAsset(context, BLUR_FRAGMENT_SHADER_ASSET_PATH)
    }

    fun loadCompositeFragmentShader(context: Context): String {
        return readAsset(context, COMPOSITE_FRAGMENT_SHADER_ASSET_PATH)
    }

    /** TEMPORARY GPU diagnostics shaders (gargantua_diag_*.frag); loaded only when a DIAG view is active. */
    fun loadAsset(context: Context, path: String): String = readAsset(context, path)

    private fun readAsset(context: Context, path: String): String {
        return try {
            context.assets.open(path).use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load canonical shader from assets/$path", e)
        }
    }
}
