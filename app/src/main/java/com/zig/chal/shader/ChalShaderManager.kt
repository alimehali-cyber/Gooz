package com.zig.chal.shader

import android.opengl.GLES30
import com.zig.chal.config.ChalFeatureToggles

/**
 * Shader Manager with Conditional Compilation.
 *
 * Manages variants and caches compiled programs. Verbatim port of the reference engine's
 * `src/shaders/manager.ts`.
 *
 * IMPORTANT: Only emit `#define` when a feature is ENABLED. Do NOT emit `#define FEATURE 0` for
 * disabled features, because the GLSL `#ifdef` checks whether the symbol is DEFINED (any value),
 * not whether it is truthy. Omitting the define entirely makes `#ifdef` correctly evaluate false.
 */
class ChalShaderManager {

    /** A compiled shader variant: program plus the sources it was built from. */
    data class ShaderVariant(
        val features: ChalFeatureToggles,
        val hasPost: Boolean,
        val vertexSource: String,
        val fragmentSource: String,
        val program: Int,
        val compilationTimeMs: Long
    )

    private val variantCache = LinkedHashMap<String, ShaderVariant>()

    val cacheSize: Int get() = variantCache.size

    private fun generateCacheKey(features: ChalFeatureToggles, hasPost: Boolean): String = buildString {
        append("lensing=").append(features.gravitationalLensing)
        append(",quality=").append(features.rayTracingQuality.id)
        append(",disk=").append(features.accretionDisk)
        append(",doppler=").append(features.dopplerBeaming)
        append(",stars=").append(features.backgroundStars)
        append(",photon=").append(features.photonSphereGlow)
        append(",bloom=").append(features.bloom)
        append(",jets=").append(features.relativisticJets)
        append(",redshift=").append(features.gravitationalRedshift)
        append(",shadow=").append(features.kerrShadow)
        append(",hasPost=").append(hasPost)
    }

    fun getCachedVariant(features: ChalFeatureToggles, hasPost: Boolean = false): ShaderVariant? =
        variantCache[generateCacheKey(features, hasPost)]

    /**
     * Inject the feature `#define` block into a shader source.
     *
     * @param hasPost when true, `ENABLE_LINEAR_OUTPUT` is defined so the main pass emits linear HDR
     *   values instead of tone-mapped LDR ones (post-processing owns the tone map in that path).
     */
    fun generateShaderSource(
        baseSource: String,
        features: ChalFeatureToggles,
        hasPost: Boolean = false
    ): String {
        val defines = mutableListOf<String>()

        if (features.gravitationalLensing) defines += "#define ENABLE_LENSING 1"
        if (features.accretionDisk) defines += "#define ENABLE_DISK 1"
        if (features.dopplerBeaming) defines += "#define ENABLE_DOPPLER 1"
        if (features.backgroundStars) defines += "#define ENABLE_STARS 1"
        if (features.photonSphereGlow) defines += "#define ENABLE_PHOTON_GLOW 1"
        if (features.bloom) defines += "#define ENABLE_BLOOM 1"
        // Jets require an accretion disk to launch (magnetically driven from inner disk edge).
        // Rendering jets without a disk is physically incorrect -- suppress if disk is off.
        if (features.relativisticJets && features.accretionDisk) defines += "#define ENABLE_JETS 1"
        if (features.gravitationalRedshift) defines += "#define ENABLE_REDSHIFT 1"
        if (features.kerrShadow) defines += "#define ENABLE_SHADOW_GUIDE 1"

        // Quality LODs
        defines += "#define ${features.rayTracingQuality.shaderDefine} 1"

        if (hasPost) {
            defines += "#define ENABLE_LINEAR_OUTPUT 1"
        }

        val sanitized = baseSource.replace("\r", "").replace("\t", "  ")
        val lines = sanitized.split("\n").toMutableList()

        // Find insertion point
        // 1. If #version exists, we MUST insert after it.
        // 2. If precision exists, it's good practice to insert after it (but after version is strict req).
        var insertAt = 0

        val versionIndex = lines.indexOfFirst { it.trim().startsWith("#version") }
        val precisionIndex = lines.indexOfFirst { it.trim().startsWith("precision") }

        if (versionIndex != -1) {
            // Must be after version
            insertAt = versionIndex + 1
            // If precision is after version, put defines after precision too (cleaner)
            if (precisionIndex > versionIndex) {
                insertAt = precisionIndex + 1
            }
        } else if (precisionIndex != -1) {
            // No version, but precision exists
            insertAt = precisionIndex + 1
        }

        // Add a guard to ensure no empty lines or weird formatting in defines
        val cleanDefines = defines.map { it.trim() }
        lines.addAll(insertAt, cleanDefines)

        return lines.joinToString("\n")
    }

    /**
     * Compile (or fetch from cache) a shader variant.
     *
     * @return the variant, or null when compilation/linking failed.
     */
    fun compileShaderVariant(
        vertexSource: String,
        fragmentSource: String,
        features: ChalFeatureToggles,
        hasPost: Boolean = false
    ): ShaderVariant? {
        getCachedVariant(features, hasPost)?.let { return it }

        val start = android.os.SystemClock.elapsedRealtime()

        val vs = generateShaderSource(vertexSource, features, hasPost)
        val fs = generateShaderSource(fragmentSource, features, hasPost)

        val vertexShader = ChalGlShaders.createShader(GLES30.GL_VERTEX_SHADER, vs) ?: return null
        val fragmentShader = ChalGlShaders.createShader(GLES30.GL_FRAGMENT_SHADER, fs) ?: run {
            GLES30.glDeleteShader(vertexShader)
            return null
        }

        val program = ChalGlShaders.createProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        if (program == 0) return null

        val variant = ShaderVariant(
            features = features.copy(),
            hasPost = hasPost,
            vertexSource = vs,
            fragmentSource = fs,
            program = program,
            compilationTimeMs = android.os.SystemClock.elapsedRealtime() - start
        )

        variantCache[generateCacheKey(features, hasPost)] = variant
        return variant
    }

    /** Delete every cached program and clear the cache while its GL context is still current. */
    fun clearCache() {
        for (variant in variantCache.values) {
            GLES30.glDeleteProgram(variant.program)
        }
        variantCache.clear()
    }

    /**
     * Forget object names after EGL context loss without issuing deletes into the new context.
     * The old context already released its resources, and numeric GL names may have been reused.
     */
    fun invalidateContext() {
        variantCache.clear()
    }
}
