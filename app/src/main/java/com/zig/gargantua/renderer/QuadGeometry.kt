package com.zig.gargantua.renderer

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Fullscreen quad geometry covering normalized device coordinates [-1, 1] x [-1, 1].
 * Provides vertex positions and texture coordinates with OpenGL ES 3.0 VAO support.
 */
class QuadGeometry {

    // 2 triangles forming a fullscreen quad (x, y, u, v)
    private val vertexData = floatArrayOf(
        //  x,     y,     u,    v
        -1.0f, -1.0f,  0.0f, 0.0f,
         1.0f, -1.0f,  1.0f, 0.0f,
        -1.0f,  1.0f,  0.0f, 1.0f,
        -1.0f,  1.0f,  0.0f, 1.0f,
         1.0f, -1.0f,  1.0f, 0.0f,
         1.0f,  1.0f,  1.0f, 1.0f
    )

    private val strideBytes = 4 * 4 // 4 floats * 4 bytes per float

    private var vaoId: Int = 0
    private var vboId: Int = 0
    private var isInitialized = false

    fun init() {
        if (isInitialized) return

        val floatBuffer: FloatBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
        floatBuffer.position(0)

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vaoId = vaos[0]

        val vbos = IntArray(1)
        GLES30.glGenBuffers(1, vbos, 0)
        vboId = vbos[0]

        GLES30.glBindVertexArray(vaoId)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexData.size * 4,
            floatBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Attribute 0: vec2 a_Position (offset 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, strideBytes, 0)

        // Attribute 1: vec2 a_TexCoord (offset 2 * 4 bytes)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, strideBytes, 2 * 4)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)

        isInitialized = true
    }

    fun draw() {
        if (!isInitialized) return
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        if (isInitialized) {
            if (vaoId != 0) {
                GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
                vaoId = 0
            }
            if (vboId != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(vboId), 0)
                vboId = 0
            }
            isInitialized = false
        }
    }
}
