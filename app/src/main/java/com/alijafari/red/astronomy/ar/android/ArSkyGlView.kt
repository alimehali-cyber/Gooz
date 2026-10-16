package com.alijafari.red.astronomy.ar.android

import android.content.Context
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
import android.os.Looper
import android.util.Log
import android.view.TextureView
import com.alijafari.red.astronomy.ar.core.ArSkyLighting
import com.alijafari.red.astronomy.ar.core.SkyAtmosphere
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaConfig
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaQualityTier
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaTextureCache
import com.alijafari.red.astronomy.ui.skypanorama.SkyPanoramaTextureLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max

/** Per-frame camera state for the GL sky (all values for the virtual camera of the overlay). */
class ArSkyGlFrame(
    /** Camera → ENU, row-major (columns = camera right, camera down, boresight in ENU). */
    val camToEnu: FloatArray,
    /** ENU → GCRS (J2000) for the Milky Way lookup, row-major. */
    val enuToGcrs: FloatArray,
    val focalPx: Float,
    val viewWidth: Int,
    val viewHeight: Int,
    /** (P/1010 hPa)·(283 K/T) refraction scale. */
    val refractionScale: Float
)

/**
 * The AR sky shown when the camera is off: physically based atmosphere (spectral sky-view LUTs lit by
 * the Sun and the Moon), airglow and light pollution, the NASA SVS Milky Way at its true place of date
 * (refraction and extinction applied), a dark landscape below the horizon, and the same tone mapping
 * the Canvas glyphs are exposed with.
 *
 * GLES 3.0 on its own handler thread, drawn on demand; any GL failure reports `false` through
 * [onStatusChanged] and the screen falls back to its Canvas gradient.
 */
class ArSkyGlView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    /** Main thread. true once a frame is on screen, false on failure. */
    var onStatusChanged: ((Boolean) -> Unit)? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderer: Renderer? = null
    private var decodeJob: Job? = null
    private var released = false

    private var pendingFrame: ArSkyGlFrame? = null
    private var pendingLighting: ArSkyLighting? = null

    init {
        isOpaque = false
        isClickable = false
        isFocusable = false
        surfaceTextureListener = this
    }

    fun updateFrame(frame: ArSkyGlFrame) {
        pendingFrame = frame
        renderer?.setFrame(frame)
    }

    fun updateLighting(lighting: ArSkyLighting) {
        if (pendingLighting === lighting) return
        pendingLighting = lighting
        renderer?.setLighting(lighting)
    }

    fun release() {
        released = true
        decodeJob?.cancel()
        renderer?.stop()
        renderer = null
        scope.cancel()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        lateinit var owner: Renderer
        owner = Renderer(object : Renderer.Listener {
            override fun onReady(maxTextureSize: Int) {
                mainHandler.post { if (!released && renderer === owner) startMilkyWayDecode(owner, maxTextureSize) }
            }

            override fun onFrameShown() {
                mainHandler.post {
                    if (!released && renderer === owner) {
                        isOpaque = true
                        onStatusChanged?.invoke(true)
                    }
                }
            }

            override fun onFailure(reason: String) {
                Log.w(TAG, "AR sky unavailable: $reason")
                mainHandler.post {
                    if (!released && renderer === owner) {
                        owner.stop()
                        renderer = null
                        onStatusChanged?.invoke(false)
                    }
                }
            }
        })
        renderer = owner
        owner.start(surface, width, height)
        pendingLighting?.let { owner.setLighting(it) }
        pendingFrame?.let { owner.setFrame(it) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.setViewport(width, height)
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        decodeJob?.cancel()
        renderer?.stop()
        renderer = null
        isOpaque = false
        return false // the renderer releases the SurfaceTexture on its own thread
    }

    private fun startMilkyWayDecode(owner: Renderer, maxTextureSize: Int) {
        decodeJob?.cancel()
        val tier = SkyPanoramaTextureLoader.chooseTier(SkyPanoramaQualityTier.FULL, maxTextureSize)
        decodeJob = scope.launch {
            try {
                // Shared, read-only decode (also used by Home); never recycled here.
                val bitmap = SkyPanoramaTextureCache.obtain(appContext, SkyPanoramaConfig.DEFAULT_ASSET_PATH, tier)
                if (renderer === owner) owner.uploadMilkyWay(bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The sky still renders without the Milky Way.
                Log.w(TAG, "Milky Way texture unavailable: ${e.message}")
            }
        }
    }

    /** GL-thread renderer. */
    private class Renderer(private val listener: Listener) {
        interface Listener {
            fun onReady(maxTextureSize: Int)
            fun onFrameShown()
            fun onFailure(reason: String)
        }

        private val thread = HandlerThread("ArSkyGl").apply { start() }
        private val handler = Handler(thread.looper)
        private val latestFrame = AtomicReference<ArSkyGlFrame?>(null)
        private val drawPending = AtomicBoolean(false)

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var surfaceTexture: SurfaceTexture? = null
        private var program = 0
        private var vao = 0
        private var sunTex = 0
        private var moonTex = 0
        private var mwTex = 0
        private var mwWidth = 0
        private var lighting: ArSkyLighting? = null
        private var lightingUploaded: ArSkyLighting? = null
        private var viewportW = 0
        private var viewportH = 0
        private var frameShown = false
        private var failed = false
        private val uniforms = HashMap<String, Int>()

        fun start(surface: SurfaceTexture, width: Int, height: Int) = handler.post {
            surfaceTexture = surface
            viewportW = width
            viewportH = height
            try {
                initEgl(surface)
                initProgram()
                val ids = IntArray(1)
                GLES30.glGenVertexArrays(1, ids, 0)
                vao = ids[0]
                val max = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
                listener.onReady(max[0])
            } catch (t: Throwable) {
                Log.w(TAG, "AR sky GL init failed", t)
                fail(t.message ?: "GL init failed")
            }
        }

        fun stop() = handler.post {
            releaseGl()
            thread.quitSafely()
        }

        fun setViewport(width: Int, height: Int) = handler.post {
            viewportW = width
            viewportH = height
            draw()
        }

        fun setLighting(l: ArSkyLighting) = handler.post {
            lighting = l
            requestDraw()
        }

        fun setFrame(frame: ArSkyGlFrame) {
            latestFrame.set(frame)
            requestDraw()
        }

        private fun requestDraw() {
            if (drawPending.compareAndSet(false, true)) {
                handler.post {
                    drawPending.set(false)
                    draw()
                }
            }
        }

        fun uploadMilkyWay(bitmap: Bitmap) = handler.post {
            if (failed || program == 0) return@post
            try {
                if (mwTex != 0) GLES30.glDeleteTextures(1, intArrayOf(mwTex), 0)
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                mwTex = ids[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mwTex)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
                checkGl("Milky Way upload")
                mwWidth = bitmap.width
                draw()
            } catch (t: Throwable) {
                // Without the Milky Way the sky is still correct; keep rendering.
                Log.w(TAG, "Milky Way upload failed", t)
                mwTex = 0
                mwWidth = 0
            }
        }

        private fun uploadLutsIfNeeded() {
            val l = lighting ?: return
            if (lightingUploaded === l) return
            sunTex = uploadLut(sunTex, l.sunLut)
            moonTex = uploadLut(moonTex, l.moonLut)
            lightingUploaded = l
        }

        private fun uploadLut(existing: Int, data: FloatArray): Int {
            var id = existing
            if (id == 0) {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                id = ids[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            } else {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            }
            val w = SkyAtmosphere.SKY_LUT_W
            val h = SkyAtmosphere.SKY_LUT_H
            val buf = ByteBuffer.allocateDirect(w * h * 4 * 2).order(ByteOrder.nativeOrder())
            val sb = buf.asShortBuffer()
            val inv = 1f / LUT_SCALE
            for (i in 0 until w * h) {
                sb.put(HalfFloat.fromFloat((data[i * 3] * inv).coerceIn(0f, 65000f)))
                sb.put(HalfFloat.fromFloat((data[i * 3 + 1] * inv).coerceIn(0f, 65000f)))
                sb.put(HalfFloat.fromFloat((data[i * 3 + 2] * inv).coerceIn(0f, 65000f)))
                sb.put(HalfFloat.fromFloat(1f))
            }
            sb.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, sb)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGl("LUT upload")
            return id
        }

        private fun draw() {
            if (failed || program == 0 || eglSurface == EGL14.EGL_NO_SURFACE) return
            val frame = latestFrame.get() ?: return
            val l = lighting ?: return
            if (viewportW <= 0 || viewportH <= 0) return
            try {
                uploadLutsIfNeeded()
                GLES30.glViewport(0, 0, viewportW, viewportH)
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glDisable(GLES30.GL_BLEND)
                GLES30.glUseProgram(program)
                GLES30.glBindVertexArray(vao)

                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mwTex)
                GLES30.glUniform1i(u("uMilkyWay"), 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sunTex)
                GLES30.glUniform1i(u("uSunLut"), 1)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, moonTex)
                GLES30.glUniform1i(u("uMoonLut"), 2)

                GLES30.glUniformMatrix3fv(u("uCamToEnu"), 1, true, frame.camToEnu, 0)
                GLES30.glUniformMatrix3fv(u("uEnuToGcrs"), 1, true, frame.enuToGcrs, 0)
                val f = max(frame.focalPx, 1f)
                GLES30.glUniform2f(u("uHalfTan"), viewportW / 2f / f, viewportH / 2f / f)
                GLES30.glUniform1f(u("uMoonScale"), l.moonScale.toFloat())
                GLES30.glUniform1f(u("uLutScale"), LUT_SCALE)
                GLES30.glUniform1f(u("uSunAz"), l.sunAzRad.toFloat())
                GLES30.glUniform1f(u("uMoonAz"), l.moonAzRad.toFloat())
                GLES30.glUniform1f(u("uMwReady"), if (mwTex != 0) 1f else 0f)
                val texelsPerPixel = (mwWidth / (2.0 * PI)) / f
                GLES30.glUniform1f(u("uMwLod"), if (mwWidth > 0) max(0.0, log2(max(texelsPerPixel, 1e-6))).toFloat() else 0f)
                GLES30.glUniform1f(u("uMwPeak"), SkyRenderModel.MILKY_WAY_PEAK_LUMINANCE.toFloat())
                GLES30.glUniform1f(u("uMwGamma"), SkyRenderModel.MILKY_WAY_GAMMA.toFloat())
                val tz = l.zenithTransmittance
                GLES30.glUniform3f(
                    u("uTau"),
                    (-ln(max(tz[0], 1e-6))).toFloat(),
                    (-ln(max(tz[1], 1e-6))).toFloat(),
                    (-ln(max(tz[2], 1e-6))).toFloat()
                )
                val natural = SkyRenderModel.magArcsec2ToLuminance(21.95)
                val total = SkyRenderModel.magArcsec2ToLuminance(SkyRenderModel.bortleZenithMagArcsec2(l.bortle))
                GLES30.glUniform1f(u("uNightNatural"), natural.toFloat())
                GLES30.glUniform1f(u("uNightArtificial"), max(0.0, total - natural).toFloat())
                GLES30.glUniform1f(u("uZenithTv"), tz[1].toFloat())
                GLES30.glUniform1f(u("uExposure"), SkyRenderModel.exposure(l.adaptLuminance).toFloat())
                GLES30.glUniform1f(u("uScotopic"), SkyRenderModel.scotopicWeight(l.adaptLuminance).toFloat())
                GLES30.glUniform1f(u("uRefrScale"), frame.refractionScale)
                GLES30.glUniform1f(u("uGroundGain"), 0.16f)
                GLES30.glUniform1f(u("uDitherSeed"), ((System.nanoTime() / 1_000_000L) % 997L).toFloat())
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                GLES30.glBindVertexArray(0)
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    Log.w(TAG, "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
                    return
                }
                if (!frameShown) {
                    frameShown = true
                    listener.onFrameShown()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AR sky draw failed", t)
                fail(t.message ?: "draw failed")
            }
        }

        private fun u(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

        private fun fail(reason: String) {
            if (failed) return
            failed = true
            releaseGl()
            listener.onFailure(reason)
        }

        private fun initEgl(surface: SurfaceTexture) {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
            eglDisplay = display
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0 && configs[0] != null) {
                "No GLES 3.0 EGL config"
            }
            val config = configs[0]!!
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            eglContext = ctx
            val win = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(win != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            eglSurface = win
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
        }

        private fun initProgram() {
            val vs = compile(GLES30.GL_VERTEX_SHADER, ArSkyShaders.VERTEX)
            val fs = compile(GLES30.GL_FRAGMENT_SHADER, ArSkyShaders.FRAGMENT)
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            val status = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            if (status[0] != GLES30.GL_TRUE) {
                val log = GLES30.glGetProgramInfoLog(prog)
                GLES30.glDeleteProgram(prog)
                throw IllegalStateException("AR sky program link failed: $log")
            }
            program = prog
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES30.GL_TRUE) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw IllegalStateException("AR sky shader compile failed: $log")
            }
            return shader
        }

        private fun checkGl(where: String) {
            val err = GLES30.glGetError()
            check(err == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(err)} during $where" }
        }

        private fun releaseGl() {
            val display = eglDisplay
            if (display != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                try {
                    val tex = intArrayOf(sunTex, moonTex, mwTex).filter { it != 0 }.toIntArray()
                    if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
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
                // No eglTerminate: the display connection is process-wide and other GL hosts may be alive.
            }
            surfaceTexture?.release()
            surfaceTexture = null
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglDisplay = EGL14.EGL_NO_DISPLAY
            program = 0
            vao = 0
            sunTex = 0
            moonTex = 0
            mwTex = 0
            lightingUploaded = null
            uniforms.clear()
        }

        companion object {
            /** Sky radiance spans ~1e-6 to ~1e6 cd/m²; stored ÷64 it fits binary16 (max 65504). */
            const val LUT_SCALE = 64f
            const val EGL_OPENGL_ES3_BIT = 0x0040
            const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        }
    }

    private companion object {
        const val TAG = "ArSkyGlView"
    }
}

/** IEEE 754 binary16 conversion (android.util.Half needs API 26; minSdk is 24). */
internal object HalfFloat {
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        var mant = bits and 0x7FFFFF
        if (((bits ushr 23) and 0xFF) == 0xFF) {
            // NaN or infinity
            return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
        }
        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - exp
            var half = mant ushr shift
            if (((mant ushr (shift - 1)) and 1) == 1) half += 1
            return (sign or half).toShort()
        }
        if (exp >= 31) return (sign or 0x7C00).toShort()
        var half = sign or (exp shl 10) or (mant ushr 13)
        if ((mant and 0x1000) != 0) half += 1 // round half up
        return half.toShort()
    }
}
