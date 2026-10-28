package com.zig.chal.render

import android.opengl.GLES30
import com.zig.chal.shader.ChalGlShaders
import com.zig.chal.shader.ChalShaderSource

/**
 * Temporal Reprojection / TAA Manager using Variance Clipping in YCoCg space.
 *
 * Verbatim port of the reference engine's `src/rendering/reprojection.ts`: full-screen quad +
 * ping-pong history buffer, resolved through `REPROJECTION_FRAGMENT_SHADER`.
 */
class ChalReprojection(private val hdrCapable: Boolean) {

    private var currentFramebuffer = 0
    private var historyFramebuffer = 0

    private var currentTexture = 0
    private var historyTexture = 0

    private var program = 0
    private var quadBuffer = 0

    /** Driver-round-trip-free uniform/attribute lookups (see [ChalGlShaders.LocationCache]). */
    private val locations = ChalGlShaders.LocationCache()

    private var width = 0
    private var height = 0

    /** Index of the most recently resolved texture (the "current" scene after accumulation). */
    private var pingPong = 0

    val resolvedTextureId: Int get() = if (pingPong == 0) currentTexture else historyTexture

    private val internalFormat: Int
        get() = if (hdrCapable) GLES30.GL_RGBA16F else GLES30.GL_RGBA8

    private val textureType: Int
        get() = if (hdrCapable) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE

    fun initialize(width: Int, height: Int): Boolean {
        this.width = width
        this.height = height

        return try {
            quadBuffer = ChalGlShaders.createQuadBuffer()
            if (quadBuffer == 0) throw IllegalStateException("Failed to create quad buffer")

            val vertexShader = ChalGlShaders.createShader(
                GLES30.GL_VERTEX_SHADER,
                ChalShaderSource.REPROJECTION_VERTEX_SHADER
            ) ?: throw IllegalStateException("Reprojection vertex shader failed")
            val fragmentShader = ChalGlShaders.createShader(
                GLES30.GL_FRAGMENT_SHADER,
                ChalShaderSource.REPROJECTION_FRAGMENT_SHADER
            ) ?: run {
                GLES30.glDeleteShader(vertexShader)
                throw IllegalStateException("Reprojection fragment shader failed")
            }

            program = ChalGlShaders.createProgram(vertexShader, fragmentShader)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            if (program == 0) throw IllegalStateException("Reprojection program failed to link")
            locations.clear()

            createTargets()
            true
        } catch (t: Throwable) {
            android.util.Log.e(ChalGlShaders.TAG, "Failed to initialize reprojection", t)
            cleanup()
            false
        }
    }

    private fun createTargets() {
        currentTexture = createTexture(width, height)
        currentFramebuffer = createFramebuffer(currentTexture, "taa-current")
        historyTexture = createTexture(width, height)
        historyFramebuffer = createFramebuffer(historyTexture, "taa-history")

        // Clear both targets so the first resolve blends against a defined (black) history.
        clearFramebuffer(currentFramebuffer)
        clearFramebuffer(historyFramebuffer)
        pingPong = 1
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
            android.util.Log.e(ChalGlShaders.TAG, "Reprojection FBO incomplete ($label): $status")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            error("Incomplete post-processing framebuffer ($label): $status")
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return framebuffer
    }

    private fun clearFramebuffer(framebuffer: Int) {
        if (framebuffer == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Keep the render target in sync with the surface size.
     *
     * @return true when the targets were (re)created.
     */
    fun ensureSize(width: Int, height: Int): Boolean {
        if (this.width == width && this.height == height) return false
        this.width = width
        this.height = height
        cleanupTargets()
        createTargets()
        return true
    }

    /**
     * Resolve the current frame against the history buffer.
     *
     * @param sceneTexture texture holding this frame's ray-marched scene.
     * @param blendFactor base accumulation weight (0.0 to 1.0; 0.9 for static scenes). The renderer
     *   passes 0.75 explicitly, exactly like the reference does.
     * @param cameraMoving forces a full reset of accumulation when the camera moves.
     * @param renderScale virtual-viewport scale (0.5 = half resolution).
     * @param cameraVelocityMagnitude scalar camera velocity. When above 0.001 it overrides
     *   [blendFactor] with the velocity-aware formula
     *   `blend = clamp(0.9 - velocity * 6.0, 0.05, 0.9)`, which eliminates ghosting during fast pans
     *   while maximising noise suppression at rest.
     * @return the resolved texture id, or 0 when the pipeline is unavailable.
     */
    fun resolve(
        sceneTexture: Int,
        blendFactor: Double = 0.9,
        cameraMoving: Boolean = false,
        renderScale: Double = 1.0,
        cameraVelocityMagnitude: Double = 0.0
    ): Int {
        if (program == 0 || currentFramebuffer == 0 || historyFramebuffer == 0) return 0

        val targetFramebuffer = if (pingPong == 0) currentFramebuffer else historyFramebuffer
        val targetTexture = if (pingPong == 0) currentTexture else historyTexture
        val sourceHistoryTexture = if (pingPong == 0) historyTexture else currentTexture

        // Virtual viewport scaling: the resolve pass writes the scaled sub-region of the full-size
        // history target, so the viewport and u_resolution must both follow renderScale.
        val scaledWidth = maxOf(1, kotlin.math.floor(width * renderScale).toInt())
        val scaledHeight = maxOf(1, kotlin.math.floor(height * renderScale).toInt())

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebuffer)
        GLES30.glViewport(0, 0, scaledWidth, scaledHeight)

        GLES30.glUseProgram(program)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuffer)
        val position = locations.attribute(program, "position")
        if (position != -1) {
            GLES30.glEnableVertexAttribArray(position)
            GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, 0)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTexture)
        GLES30.glUniform1i(locations.uniform(program, "u_currentFrame"), 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceHistoryTexture)
        GLES30.glUniform1i(locations.uniform(program, "u_historyFrame"), 1)

        GLES30.glUniform2f(
            locations.uniform(program, "u_resolution"),
            scaledWidth.toFloat(),
            scaledHeight.toFloat()
        )

        // Phase 3.4: Velocity-aware blend factor.
        val effectiveBlend = if (cameraVelocityMagnitude > 0.001) {
            maxOf(0.05, minOf(0.9, 0.9 - cameraVelocityMagnitude * 6.0))
        } else {
            blendFactor
        }
        GLES30.glUniform1f(
            locations.uniform(program, "u_blendFactor"),
            effectiveBlend.toFloat()
        )
        GLES30.glUniform1i(
            locations.uniform(program, "u_cameraMoving"),
            if (cameraMoving) 1 else 0
        )
        GLES30.glUniform2f(
            locations.uniform(program, "u_textureScale"),
            renderScale.toFloat(),
            renderScale.toFloat()
        )

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        pingPong = 1 - pingPong
        return targetTexture
    }

    private fun cleanupTargets() {
        if (currentFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(currentFramebuffer), 0)
        if (historyFramebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(historyFramebuffer), 0)
        if (currentTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(currentTexture), 0)
        if (historyTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(historyTexture), 0)

        currentFramebuffer = 0
        historyFramebuffer = 0
        currentTexture = 0
        historyTexture = 0
    }

    fun cleanup() {
        cleanupTargets()
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
        if (quadBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBuffer), 0)
        quadBuffer = 0
    }
}
