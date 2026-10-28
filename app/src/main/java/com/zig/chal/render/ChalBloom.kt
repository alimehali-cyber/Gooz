package com.zig.chal.render

import android.opengl.GLES30
import com.zig.chal.shader.ChalGlShaders
import com.zig.chal.shader.ChalShaderSource

/**
 * Bloom Post-Processing Manager.
 *
 * Manages multi-pass bloom rendering:
 *  1. Render scene to framebuffer
 *  2. Extract bright pixels
 *  3. Apply Gaussian blur (horizontal + vertical)
 *  4. Combine with original scene
 *
 * Verbatim port of the reference engine's `src/rendering/bloom.ts`.
 */
class ChalBloom(private val hdrCapable: Boolean) {

    /** Bloom configuration. */
    data class BloomConfig(
        val enabled: Boolean = true,
        /** 0.0 to 1.0. */
        val intensity: Double = 0.5,
        /** Brightness threshold for bloom. */
        val threshold: Double = 0.8,
        /** Number of blur iterations. */
        val blurPasses: Int = 2
    )

    var config: BloomConfig = BloomConfig()
        private set

    // Framebuffers
    private var sceneFramebuffer = 0
    private var brightFramebuffer = 0
    private var blurFramebuffer1 = 0
    private var blurFramebuffer2 = 0

    // Textures
    private var sceneTexture = 0
    private var brightTexture = 0
    private var blurTexture1 = 0
    private var blurTexture2 = 0

    // Shader programs
    private var brightPassProgram = 0
    private var blurProgram = 0
    private var combineProgram = 0

    private var quadBuffer = 0

    private var width = 0
    private var height = 0

    /** Driver-round-trip-free uniform/attribute lookups (see [ChalGlShaders.LocationCache]). */
    private val locations = ChalGlShaders.LocationCache()

    val sceneTextureId: Int get() = sceneTexture

    /** Internal format used for every intermediate target (RGBA16F when HDR is available). */
    private val internalFormat: Int
        get() = if (hdrCapable) GLES30.GL_RGBA16F else GLES30.GL_RGBA8

    private val textureType: Int
        get() = if (hdrCapable) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE

    /** Initialize bloom resources. @return true when the pipeline is complete. */
    fun initialize(width: Int, height: Int): Boolean {
        this.width = width
        this.height = height

        return try {
            quadBuffer = ChalGlShaders.createQuadBuffer()
            if (quadBuffer == 0) throw IllegalStateException("Failed to create quad buffer")

            brightPassProgram = createProgram(
                ChalShaderSource.BLOOM_VERTEX_SHADER,
                ChalShaderSource.BRIGHT_PASS_SHADER
            )
            blurProgram = createProgram(
                ChalShaderSource.BLOOM_VERTEX_SHADER,
                ChalShaderSource.BLUR_SHADER
            )
            combineProgram = createProgram(
                ChalShaderSource.BLOOM_VERTEX_SHADER,
                ChalShaderSource.COMBINE_SHADER
            )

            if (brightPassProgram == 0 || blurProgram == 0 || combineProgram == 0) {
                throw IllegalStateException("Failed to compile bloom shaders")
            }

            createFramebuffers()
            true
        } catch (t: Throwable) {
            android.util.Log.e(ChalGlShaders.TAG, "Failed to initialize bloom", t)
            cleanup()
            false
        }
    }

    private fun createFramebuffers() {
        // Scene framebuffer (full resolution)
        sceneTexture = createTexture(width, height)
        sceneFramebuffer = createFramebuffer(sceneTexture, "scene")

        // Bright pass framebuffer (half resolution for performance)
        val halfWidth = maxOf(1, width / 2)
        val halfHeight = maxOf(1, height / 2)
        brightTexture = createTexture(halfWidth, halfHeight)
        brightFramebuffer = createFramebuffer(brightTexture, "bright")

        // Blur framebuffers (quarter resolution for wider bloom and performance)
        val blurWidth = maxOf(1, width / 4)
        val blurHeight = maxOf(1, height / 4)
        blurTexture1 = createTexture(blurWidth, blurHeight)
        blurFramebuffer1 = createFramebuffer(blurTexture1, "blur1")
        blurTexture2 = createTexture(blurWidth, blurHeight)
        blurFramebuffer2 = createFramebuffer(blurTexture2, "blur2")
    }

    private fun createTexture(width: Int, height: Int): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texture = textures[0]
        check(texture != 0) { "Could not allocate a post-processing texture" }

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            textureType,
            null // allocate GPU storage without a full-size temporary Java heap buffer
        )

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return texture
    }

    private fun createFramebuffer(texture: Int, label: String): Int {
        val framebuffers = IntArray(1)
        GLES30.glGenFramebuffers(1, framebuffers, 0)
        val framebuffer = framebuffers[0]
        check(framebuffer != 0) { "Could not allocate a post-processing framebuffer ($label)" }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0
        )

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e(ChalGlShaders.TAG, "Framebuffer incomplete ($label): $status")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            error("Incomplete post-processing framebuffer ($label): $status")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return framebuffer
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        locations.clear()
        val vertexShader = ChalGlShaders.createShader(GLES30.GL_VERTEX_SHADER, vertexSource) ?: return 0
        val fragmentShader = ChalGlShaders.createShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource) ?: run {
            GLES30.glDeleteShader(vertexShader)
            return 0
        }
        val program = ChalGlShaders.createProgram(vertexShader, fragmentShader)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        return program
    }

    /** `beginScene`: bind the scene FBO for the main ray-march pass. */
    fun beginScene(): Int {
        if (sceneFramebuffer == 0) {
            android.util.Log.w(ChalGlShaders.TAG, "Bloom FBO missing")
            return 0
        }

        // Safety: Unbind potential feedback textures before binding the scene FBO
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        return sceneFramebuffer
    }

    /**
     * Apply bloom post-processing to the internally rendered scene (`applyBloom(renderScale)`).
     */
    fun applyBloom(inputTexture: Int = sceneTexture, renderScale: Double = 1.0) {
        if (!config.enabled || inputTexture == 0) return
        applyBloomToTexture(inputTexture, renderScale)
    }

    /**
     * Apply bloom post-processing to a specific input texture and render the result to the
     * default framebuffer.
     *
     * @param renderScale virtual-viewport scale used for the intermediate viewports.
     */
    fun applyBloomToTexture(inputTexture: Int, renderScale: Double = 1.0) {
        // Requirement 8.1: Skip bloom when disabled
        if (!config.enabled) {
            // If bloom is disabled, we still need to draw the input texture to screen
            // because the input might be an offscreen TAA buffer.
            drawTextureToScreen(inputTexture, renderScale)
            return
        }

        if (brightPassProgram == 0 || blurProgram == 0 || combineProgram == 0 ||
            brightFramebuffer == 0 || blurFramebuffer1 == 0 || blurFramebuffer2 == 0) {
            drawTextureToScreen(inputTexture, renderScale)
            return
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer)

        // === PASS 1: Extract bright pixels ===
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, brightFramebuffer)
        val halfWidth = maxOf(1, ((width * renderScale) / 2).toInt())
        val halfHeight = maxOf(1, ((height * renderScale) / 2).toInt())
        GLES30.glViewport(0, 0, halfWidth, halfHeight)

        GLES30.glUseProgram(brightPassProgram)
        val bpPosition = locations.attribute(brightPassProgram, "position")
        if (bpPosition != -1) {
            GLES30.glEnableVertexAttribArray(bpPosition)
            GLES30.glVertexAttribPointer(bpPosition, 2, GLES30.GL_FLOAT, false, 0, 0)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glUniform1i(locations.uniform(brightPassProgram, "u_texture"), 0)
        GLES30.glUniform1f(
            locations.uniform(brightPassProgram, "u_threshold"),
            config.threshold.toFloat()
        )
        setTextureScale(brightPassProgram, renderScale)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        // === PASS 2: Blur passes ===
        GLES30.glUseProgram(blurProgram)
        setTextureScale(blurProgram, renderScale)
        val blurPosition = locations.attribute(blurProgram, "position")
        if (blurPosition != -1) {
            GLES30.glEnableVertexAttribArray(blurPosition)
            GLES30.glVertexAttribPointer(blurPosition, 2, GLES30.GL_FLOAT, false, 0, 0)
        }

        val blurWidth = maxOf(1, ((width * renderScale) / 4).toInt())
        val blurHeight = maxOf(1, ((height * renderScale) / 4).toInt())
        GLES30.glViewport(0, 0, blurWidth, blurHeight)
        GLES30.glUniform2f(
            locations.uniform(blurProgram, "u_resolution"),
            blurWidth.toFloat(),
            blurHeight.toFloat()
        )

        var currentSourceTexture = brightTexture

        for (i in 0 until config.blurPasses) {
            // Horizontal blur: Source -> Blur1
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFramebuffer1)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSourceTexture)
            GLES30.glUniform1i(locations.uniform(blurProgram, "u_texture"), 0)
            GLES30.glUniform2f(locations.uniform(blurProgram, "u_direction"), 1.0f, 0.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

            // Vertical blur: Blur1 -> Blur2
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, blurFramebuffer2)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blurTexture1)
            GLES30.glUniform1i(locations.uniform(blurProgram, "u_texture"), 0)
            GLES30.glUniform2f(locations.uniform(blurProgram, "u_direction"), 0.0f, 1.0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

            // For next iteration, source is Blur2
            currentSourceTexture = blurTexture2
        }

        // === PASS 3: Combine with original scene ===
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)

        GLES30.glUseProgram(combineProgram)
        val combinePosition = locations.attribute(combineProgram, "position")
        if (combinePosition != -1) {
            GLES30.glEnableVertexAttribArray(combinePosition)
            GLES30.glVertexAttribPointer(combinePosition, 2, GLES30.GL_FLOAT, false, 0, 0)
        }

        // Bind scene texture (Original Input)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexture)
        GLES30.glUniform1i(locations.uniform(combineProgram, "u_sceneTexture"), 0)

        // Bind bloom texture (Blurred Result)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, currentSourceTexture)
        GLES30.glUniform1i(locations.uniform(combineProgram, "u_bloomTexture"), 1)

        GLES30.glUniform1f(
            locations.uniform(combineProgram, "u_bloomIntensity"),
            config.intensity.toFloat()
        )
        setTextureScale(combineProgram, renderScale)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    /** Simple passthrough of a texture to the screen (no bloom), reusing the combine shader. */
    fun drawTextureToScreen(texture: Int, renderScale: Double = 1.0) {
        if (combineProgram == 0) return

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glUseProgram(combineProgram)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer)
        val combinePosition = locations.attribute(combineProgram, "position")
        if (combinePosition != -1) {
            GLES30.glEnableVertexAttribArray(combinePosition)
            GLES30.glVertexAttribPointer(combinePosition, 2, GLES30.GL_FLOAT, false, 0, 0)
        }

        setTextureScale(combineProgram, renderScale)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(locations.uniform(combineProgram, "u_sceneTexture"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (brightTexture != 0) brightTexture else texture) // safe dummy
        GLES30.glUniform1i(locations.uniform(combineProgram, "u_bloomTexture"), 1)

        GLES30.glUniform1f(locations.uniform(combineProgram, "u_bloomIntensity"), 0.0f)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
    }

    private fun setTextureScale(program: Int, renderScale: Double) {
        val location = locations.uniform(program, "u_textureScale")
        if (location != -1) {
            GLES30.glUniform2f(location, renderScale.toFloat(), renderScale.toFloat())
        }
    }

    /** Update bloom configuration (feature toggle driven). */
    fun updateConfig(
        enabled: Boolean? = null,
        intensity: Double? = null,
        threshold: Double? = null,
        blurPasses: Int? = null
    ) {
        config = config.copy(
            enabled = enabled ?: config.enabled,
            intensity = intensity ?: config.intensity,
            threshold = threshold ?: config.threshold,
            blurPasses = blurPasses ?: config.blurPasses
        )
    }

    /** Resize framebuffers. */
    fun resize(width: Int, height: Int) {
        if (this.width == width && this.height == height) return
        this.width = width
        this.height = height
        cleanupFramebuffers()
        createFramebuffers()
    }

    private fun cleanupFramebuffers() {
        if (sceneFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFramebuffer), 0)
        if (brightFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(brightFramebuffer), 0)
        if (blurFramebuffer1 != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(blurFramebuffer1), 0)
        if (blurFramebuffer2 != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(blurFramebuffer2), 0)

        if (sceneTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(sceneTexture), 0)
        if (brightTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(brightTexture), 0)
        if (blurTexture1 != 0) GLES30.glDeleteTextures(1, intArrayOf(blurTexture1), 0)
        if (blurTexture2 != 0) GLES30.glDeleteTextures(1, intArrayOf(blurTexture2), 0)

        sceneFramebuffer = 0
        brightFramebuffer = 0
        blurFramebuffer1 = 0
        blurFramebuffer2 = 0

        sceneTexture = 0
        brightTexture = 0
        blurTexture1 = 0
        blurTexture2 = 0
    }

    /** Cleanup all resources. */
    fun cleanup() {
        cleanupFramebuffers()

        if (brightPassProgram != 0) GLES30.glDeleteProgram(brightPassProgram)
        if (blurProgram != 0) GLES30.glDeleteProgram(blurProgram)
        if (combineProgram != 0) GLES30.glDeleteProgram(combineProgram)
        // createQuadBuffer allocates a new buffer: this pipeline owns it.
        if (quadBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBuffer), 0)

        brightPassProgram = 0
        blurProgram = 0
        combineProgram = 0
        quadBuffer = 0
    }
}
