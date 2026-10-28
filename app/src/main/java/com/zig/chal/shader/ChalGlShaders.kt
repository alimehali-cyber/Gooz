package com.zig.chal.shader

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random

/**
 * Low-level GLES 3.0 helpers for the Chal renderer.
 *
 * Verbatim port of the reference engine's `src/utils/webgl-utils.ts`: shader compilation, program
 * linking, the shared full-screen quad buffer, the procedural noise/blue-noise textures, and the
 * shader warm-up pass.
 *
 * This file is self-contained: it shares no code, state, or conventions with any other feature in
 * the application.
 */
object ChalGlShaders {

    /**
     * Uniform/attribute location cache.
     *
     * `glGetUniformLocation` / `glGetAttribLocation` are driver round-trips: on a tiler they can
     * cost microseconds each, and the post-processing chain asked for 25 of them per frame even
     * though the programs never change. Locations belong to a program, so the cache is keyed by
     * the program id and invalidated by [clear] whenever a program is (re)created.
     */
    class LocationCache {
        private val entries = HashMap<Long, Int>()

        fun uniform(program: Int, name: String): Int = lookup(program, name) {
            GLES30.glGetUniformLocation(program, name)
        }

        fun attribute(program: Int, name: String): Int = lookup(program, name) {
            GLES30.glGetAttribLocation(program, name)
        }

        fun clear() = entries.clear()

        private inline fun lookup(program: Int, name: String, query: () -> Int): Int {
            if (program == 0) return -1
            val key = (program.toLong() shl 32) or (name.hashCode().toLong() and 0xFFFFFFFFL)
            val cached = entries[key]
            if (cached != null) return cached
            val location = query()
            entries[key] = location
            return location
        }
    }

    const val TAG = "ChalGl"

    /** Full-screen quad, two triangles, matching `getSharedQuadBuffer`'s vertex data. */
    private val QUAD_VERTICES = floatArrayOf(
        -1.0f, -1.0f,
        1.0f, -1.0f,
        -1.0f, 1.0f,
        -1.0f, 1.0f,
        1.0f, -1.0f,
        1.0f, 1.0f
    )

    /**
     * Creates and compiles a GLSL ES shader from source code.
     *
     * @return the shader handle, or 0 if compilation failed (logged with a line-numbered dump of
     *   the source, exactly like the reference implementation's error path).
     */
    fun createShader(type: Int, source: String): Int? {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) return null

        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES30.glGetShaderInfoLog(shader)
            val typeStr = if (type == GLES30.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"

            // Format source with line numbers
            val numberedSource = source.split("\n")
                .mapIndexed { i, l -> "${(i + 1).toString().padStart(4, ' ')}: $l" }
                .joinToString("\n")

            Log.e(TAG, "Shader Compilation Error ($typeStr):\n$info\n\nSource:\n$numberedSource")

            GLES30.glDeleteShader(shader)
            return null
        }
        return shader
    }

    /**
     * Creates and links a program from a vertex and a fragment shader.
     *
     * @return the program handle, or 0 when linking failed.
     */
    fun createProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            Log.e(TAG, "Failed to create program")
            return 0
        }

        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES30.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link error: $info")
            GLES30.glDeleteProgram(program)
            return 0
        }

        return program
    }

    /** Allocate a direct FloatBuffer for vertex data. */
    fun floatBuffer(data: FloatArray): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data)
        buffer.position(0)
        return buffer
    }

    /** Creates a buffer with vertex data for a full-screen quad (`getSharedQuadBuffer`). */
    fun createQuadBuffer(): Int {
        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        val buffer = buffers[0]
        if (buffer == 0) return 0

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            QUAD_VERTICES.size * Float.SIZE_BYTES,
            floatBuffer(QUAD_VERTICES),
            GLES30.GL_STATIC_DRAW
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return buffer
    }

    /**
     * Sets up the vertex attribute pointer for the `position` attribute.
     */
    fun setupPositionAttribute(program: Int, attributeName: String, buffer: Int) {
        if (buffer == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        val positionLocation = GLES30.glGetAttribLocation(program, attributeName)
        if (positionLocation != -1) {
            GLES30.glEnableVertexAttribArray(positionLocation)
            GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 0, 0)
        }
    }

    /**
     * Generates a high-quality RGBA noise texture for shader lookups.
     * Replaces expensive runtime hash calls.
     *
     * WARNING: this texture is deliberately filled with i.i.d. `Random.nextFloat()` values exactly
     * like the reference; it is a hash source, not a blue-noise field. The distinction matters for
     * the shader's `hash(vec3)` lookups, which index it as a value table.
     */
    fun createNoiseTexture(size: Int = 256, random: Random = Random()): Int =
        createRandomTexture(size, random, minFilter = GLES30.GL_LINEAR, magFilter = GLES30.GL_LINEAR)

    /**
     * Generates a "blue noise" texture approximation (uniform distribution with high frequency),
     * used for dithering to break banding. NEAREST filtering preserves the precise pixel values
     * for the dither lookup.
     */
    fun createBlueNoiseTexture(size: Int = 256, random: Random = Random()): Int =
        createRandomTexture(size, random, minFilter = GLES30.GL_NEAREST, magFilter = GLES30.GL_NEAREST)

    private fun createRandomTexture(size: Int, random: Random, minFilter: Int, magFilter: Int): Int {
        val data = ByteArray(size * size * 4)
        for (i in data.indices) {
            data[i] = random.nextInt(256).toByte()
        }

        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texture = textures[0]
        if (texture == 0) return 0

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            size,
            size,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(data)
        )

        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return texture
    }

    /**
     * Performs a minimal 1x1 render pass to "warm up" the shader and driver.
     * Prevents stutters when new execution paths (branches) are first taken.
     */
    fun warmupShader(program: Int, quadBuffer: Int, viewportWidth: Int, viewportHeight: Int) {
        GLES30.glUseProgram(program)
        GLES30.glViewport(0, 0, 1, 1)
        setupPositionAttribute(program, "position", quadBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }
}
