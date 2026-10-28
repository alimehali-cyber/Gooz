package com.zig.gargantua.renderer

import android.opengl.GLES30
import android.util.Log
import java.util.Locale

/**
 * Encapsulates an OpenGL ES shader program, managing shader compilation,
 * program linking, error logging, uniform location caching, and clean teardown.
 */
class ShaderProgram private constructor(val programId: Int) {

    data class CreationFailure(
        val label: String,
        val log: String
    )

    private val uniformLocations = mutableMapOf<String, Int>()

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun getUniformLocation(name: String): Int {
        return uniformLocations.getOrPut(name) {
            GLES30.glGetUniformLocation(programId, name)
        }
    }

    fun setUniform1f(name: String, value: Float) {
        val loc = getUniformLocation(name)
        if (loc >= 0) {
            GLES30.glUniform1f(loc, value)
        }
    }

    fun setUniform1i(name: String, value: Int) {
        val loc = getUniformLocation(name)
        if (loc >= 0) {
            GLES30.glUniform1i(loc, value)
        }
    }

    fun setUniform2f(name: String, x: Float, y: Float) {
        val loc = getUniformLocation(name)
        if (loc >= 0) {
            GLES30.glUniform2f(loc, x, y)
        }
    }

    fun setUniform3f(name: String, x: Float, y: Float, z: Float) {
        val loc = getUniformLocation(name)
        if (loc >= 0) {
            GLES30.glUniform3f(loc, x, y, z)
        }
    }

    fun setUniformMatrix4fv(name: String, matrix: FloatArray) {
        val loc = getUniformLocation(name)
        if (loc >= 0) {
            GLES30.glUniformMatrix4fv(loc, 1, false, matrix, 0)
        }
    }

    fun release() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            uniformLocations.clear()
        }
    }

    companion object {
        private const val TAG = "GargantuaShader"

        fun create(
            vertexSource: String,
            fragmentSource: String,
            onFailure: ((CreationFailure) -> Unit)? = null
        ): ShaderProgram? {
            val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource, onFailure)
            if (vertexShader == 0) return null

            val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource, onFailure)
            if (fragmentShader == 0) {
                GLES30.glDeleteShader(vertexShader)
                return null
            }

            val program = GLES30.glCreateProgram()
            if (program == 0) {
                reportFailure(
                    CreationFailure(
                        label = "CREATE0",
                        log = "glCreateProgram returned 0; glError=${glErrorHex()}"
                    ),
                    onFailure
                )
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                return null
            }

            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)

            val linkStatus = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)

            // Shaders can be flagged for deletion after attachment/linking
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)

            if (linkStatus[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(program)
                reportFailure(CreationFailure("LINK", log), onFailure)
                GLES30.glDeleteProgram(program)
                return null
            }

            return ShaderProgram(program)
        }

        private fun compileShader(
            type: Int,
            source: String,
            onFailure: ((CreationFailure) -> Unit)?
        ): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) {
                reportFailure(
                    CreationFailure(
                        label = "CREATE0",
                        log = "glCreateShader returned 0; glError=${glErrorHex()}"
                    ),
                    onFailure
                )
                return 0
            }

            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)

            val compiled = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                val label = if (type == GLES30.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
                reportFailure(CreationFailure(label, log), onFailure)
                GLES30.glDeleteShader(shader)
                return 0
            }

            return shader
        }

        private fun reportFailure(
            failure: CreationFailure,
            onFailure: ((CreationFailure) -> Unit)?
        ) {
            Log.e(TAG, "GL ${failure.label} failure: ${failure.log}")
            onFailure?.invoke(failure)
        }

        private fun glErrorHex(): String =
            String.format(Locale.US, "0x%04X", GLES30.glGetError())
    }
}
