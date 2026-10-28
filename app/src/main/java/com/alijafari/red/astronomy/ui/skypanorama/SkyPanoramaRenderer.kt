package com.alijafari.red.astronomy.ui.skypanorama

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.tan

/**
 * Owns the GLES 3.0 context, shader program and panorama texture for one [SkyPanoramaSurfaceView]
 * attachment. Every GL and EGL call runs on a dedicated handler thread.
 *
 * Lifecycle:
 *  - [attach] creates EGL, the program and an empty VAO on the GL thread, then reports the GL
 *    limits through [Listener.onGlReady] so the caller can choose a decode tier.
 *  - [uploadTexture] uploads the shared decoded bitmap into this context's own texture and generates mips.
 *    the bitmap. It does not repeat the upload per frame.
 *  - [requestDraw] draws one frame. There is no continuous loop: frames are produced only after a
 *    state, config, viewport or texture change.
 *  - [detachAndStop] releases every GL object, destroys the EGL surface and context, releases the
 *    SurfaceTexture, and quits the thread.
 */
class SkyPanoramaRenderer(private val listener: Listener) {

    interface Listener {
        /** GL thread. The context is current and GL_MAX_TEXTURE_SIZE is known. */
        fun onGlReady(maxTextureSize: Int)

        /** GL thread. The first panorama frame has been swapped to the surface. */
        fun onFrameShown()

        /** GL thread. Initialization or upload failed. The renderer has released its resources. */
        fun onGlFailure(reason: String)
    }

    private val thread = HandlerThread("SkyPanoramaGl").apply { start() }
    private val handler = Handler(thread.looper)

    // ---- GL-thread-only state -------------------------------------------------------------
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null

    private var program = 0
    private var vao = 0
    private var panoramaTexture = 0
    private var textureWidth = 0
    private var textureReady = false
    private var frameShownReported = false
    private var maxTextureSize = 0

    private var uPanorama = -1
    private var uForward = -1
    private var uRight = -1
    private var uUp = -1
    private var uTanHalf = -1
    private var uLod = -1
    private var uExposure = -1
    private var uSaturation = -1
    private var uContrast = -1
    private var uVisibility = -1
    private var uSkyZenith = -1
    private var uSkyMid = -1
    private var uSkyHorizon = -1
    private var uNightWeight = -1

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var state: SkyPanoramaState? = null
    private var config = SkyPanoramaConfig()

    // ---- Public API (any thread; work is posted to the GL thread) -------------------------

    fun attach(surface: SurfaceTexture, width: Int, height: Int) {
        handler.post {
            surfaceTexture = surface
            viewportWidth = width
            viewportHeight = height
            try {
                initEgl(surface)
                initProgram()
                initVao()
                maxTextureSize = queryMaxTextureSize()
                listener.onGlReady(maxTextureSize)
            } catch (t: Throwable) {
                Log.w(TAG, "Sky panorama GL init failed", t)
                releaseGl()
                listener.onGlFailure(t.message ?: "GL init failed")
            }
        }
    }

    fun setViewport(width: Int, height: Int) = handler.post {
        viewportWidth = width
        viewportHeight = height
        drawNow()
    }

    fun setState(newState: SkyPanoramaState) = handler.post {
        if (newState != state) {
            state = newState
            drawNow()
        }
    }

    fun setConfig(newConfig: SkyPanoramaConfig) = handler.post {
        val sanitized = newConfig.sanitized()
        if (sanitized != config) {
            config = sanitized
            drawNow()
        }
    }

    /**
     * Uploads [bitmap] into this context's texture. The bitmap is shared and read-only, so it is not recycled here;
     * [SkyPanoramaTextureCache] owns its lifetime.
     */
    fun uploadTexture(bitmap: Bitmap) = handler.post {
        try {
            if (eglSurface == EGL14.EGL_NO_SURFACE || program == 0) {
                return@post
            }
            if (bitmap.width > maxTextureSize || bitmap.height > maxTextureSize) {
                throw IllegalStateException(
                    "Panorama ${bitmap.width}x${bitmap.height} exceeds GL_MAX_TEXTURE_SIZE $maxTextureSize"
                )
            }
            if (panoramaTexture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(panoramaTexture), 0)
                panoramaTexture = 0
            }
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            panoramaTexture = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, panoramaTexture)
            // Horizontal wrap is REPEAT for the 0h/24h seam; vertical clamps at the poles.
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGlError("texture upload")
            textureWidth = bitmap.width
            textureReady = true
            frameShownReported = false
            drawNow()
        } catch (t: Throwable) {
            Log.w(TAG, "Sky panorama texture upload failed", t)
            releaseGl()
            listener.onGlFailure(t.message ?: "texture upload failed")
        }
    }

    /** Draws one frame if the renderer has everything it needs. Used after lifecycle changes. */
    fun requestDraw() = handler.post { drawNow() }

    /** Releases all GL resources and stops the thread. The renderer cannot be reused afterwards. */
    fun detachAndStop() {
        handler.post {
            releaseGl()
            thread.quitSafely()
        }
    }

    // ---- GL thread internals ---------------------------------------------------------------

    private fun initEgl(surface: SurfaceTexture) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        eglDisplay = display

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null
        ) { "No GLES 3.0 EGL config available" }
        val eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed (0x${Integer.toHexString(EGL14.eglGetError())})" }
        eglContext = context

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglWindow = EGL14.eglCreateWindowSurface(display, eglConfig, surface, surfaceAttribs, 0)
        check(eglWindow != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        eglSurface = eglWindow

        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun initProgram() {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, SkyPanoramaShaders.VERTEX_SHADER)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, SkyPanoramaShaders.FRAGMENT_SHADER)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        // Shader objects are no longer needed once the program is linked.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw IllegalStateException("Panorama program link failed: $log")
        }
        program = prog
        uPanorama = GLES30.glGetUniformLocation(prog, "uPanorama")
        uForward = GLES30.glGetUniformLocation(prog, "uForward")
        uRight = GLES30.glGetUniformLocation(prog, "uRight")
        uUp = GLES30.glGetUniformLocation(prog, "uUp")
        uTanHalf = GLES30.glGetUniformLocation(prog, "uTanHalf")
        uLod = GLES30.glGetUniformLocation(prog, "uLod")
        uExposure = GLES30.glGetUniformLocation(prog, "uExposure")
        uSaturation = GLES30.glGetUniformLocation(prog, "uSaturation")
        uContrast = GLES30.glGetUniformLocation(prog, "uContrast")
        uVisibility = GLES30.glGetUniformLocation(prog, "uVisibility")
        uSkyZenith = GLES30.glGetUniformLocation(prog, "uSkyZenith")
        uSkyMid = GLES30.glGetUniformLocation(prog, "uSkyMid")
        uSkyHorizon = GLES30.glGetUniformLocation(prog, "uSkyHorizon")
        uNightWeight = GLES30.glGetUniformLocation(prog, "uNightWeight")
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("Panorama shader compile failed: $log")
        }
        return shader
    }

    /** The draw uses gl_VertexID only, but GLES 3.0 still wants a bound VAO for draw calls. */
    private fun initVao() {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vao = ids[0]
    }

    private fun queryMaxTextureSize(): Int {
        val out = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, out, 0)
        return out[0]
    }

    private fun drawNow() {
        val current = state ?: return
        if (!textureReady || program == 0 || eglSurface == EGL14.EGL_NO_SURFACE) return
        if (viewportWidth <= 0 || viewportHeight <= 0) return

        val cfg = config
        val basis = current.basis
        // The field of view belongs to the framing, so Home and the Live Sky backdrop each use their own camera.
        val fovYRad = Math.toRadians(current.framing.fovYDeg.toDouble())
        val tanHalfY = tan(fovYRad / 2.0)
        val tanHalfX = tanHalfY * viewportWidth.toDouble() / viewportHeight.toDouble()
        val lod = SkyPanoramaMath.texelLod(textureWidth, fovYRad, viewportHeight)

        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, panoramaTexture)
        GLES30.glUniform1i(uPanorama, 0)
        GLES30.glUniform3f(uForward, basis.forward[0].toFloat(), basis.forward[1].toFloat(), basis.forward[2].toFloat())
        GLES30.glUniform3f(uRight, basis.right[0].toFloat(), basis.right[1].toFloat(), basis.right[2].toFloat())
        GLES30.glUniform3f(uUp, basis.up[0].toFloat(), basis.up[1].toFloat(), basis.up[2].toFloat())
        GLES30.glUniform2f(uTanHalf, tanHalfX.toFloat(), tanHalfY.toFloat())
        GLES30.glUniform1f(uLod, lod)
        GLES30.glUniform1f(uExposure, cfg.exposure)
        GLES30.glUniform1f(uSaturation, cfg.saturation)
        GLES30.glUniform1f(uContrast, cfg.contrast)
        GLES30.glUniform1f(uVisibility, cfg.visibility)
        val sky = current.sky
        GLES30.glUniform3f(uSkyZenith, sky.gradient.zenith.r, sky.gradient.zenith.g, sky.gradient.zenith.b)
        GLES30.glUniform3f(uSkyMid, sky.gradient.mid.r, sky.gradient.mid.g, sky.gradient.mid.b)
        GLES30.glUniform3f(uSkyHorizon, sky.gradient.horizon.r, sky.gradient.horizon.g, sky.gradient.horizon.b)
        GLES30.glUniform1f(uNightWeight, sky.nightWeight)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
            return
        }
        if (!frameShownReported) {
            frameShownReported = true
            listener.onFrameShown()
        }
    }

    private fun checkGlError(where: String) {
        val err = GLES30.glGetError()
        check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)} during $where" }
    }

    private fun releaseGl() {
        val display = eglDisplay
        if (display != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            // Delete GL objects while the context is still current.
            try {
                if (panoramaTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(panoramaTexture), 0)
                if (program != 0) GLES30.glDeleteProgram(program)
                if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            } catch (t: Throwable) {
                Log.w(TAG, "GL cleanup failed", t)
            }
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
        }
        surfaceTexture?.release()
        surfaceTexture = null
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        program = 0
        vao = 0
        panoramaTexture = 0
        textureWidth = 0
        textureReady = false
        frameShownReported = false
    }

    private companion object {
        const val TAG = "SkyPanoramaRenderer"
        /** EGL_OPENGL_ES3_BIT (0x0040), defined here because EGL14 does not expose it. */
        const val EGL_OPENGL_ES3_BIT = 0x0040
        /** EGL_CONTEXT_CLIENT_VERSION (0x3098). */
        const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
    }
}
