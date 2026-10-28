package com.zig.gargantua.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

private fun effectiveCoarseSamplingBlockSize(state: GargantuaRenderState): Int =
    GargantuaCoarseSampling.sanitizeBlockSize(state.debugCoarseSamplingBlockSize)

/**
 * OpenGL ES 3.x Renderer for Gargantua.
 * Orchestrates full-screen relativistic photon geodesic tracing through Kerr-Schild spacetime,
 * offscreen HDR floating-point FBO rendering, restrained bloom extraction and separable blur,
 * ACES filmic tone mapping, stationary frame caching, and thread-safe telemetry emission.
 */
class GargantuaRenderer(
    private val context: Context,
    val stateHolder: RenderStateHolder = RenderStateHolder()
) : GLSurfaceView.Renderer {

    private enum class AnimationResourceResult { NOT_READY, FAILED, READY }

    private var geodesicProgram: ShaderProgram? = null
    private var workloadGeodesicProgram: ShaderProgram? = null
    private var animationGeodesicProgram: ShaderProgram? = null
    private var animationMaterialProgram: ShaderProgram? = null
    private var testProgram: ShaderProgram? = null
    private var blitProgram: ShaderProgram? = null
    private var semanticCacheDebugProgram: ShaderProgram? = null
    private var brightPassProgram: ShaderProgram? = null
    private var blurProgram: ShaderProgram? = null
    private var compositeProgram: ShaderProgram? = null
    private var reduceProgram: ShaderProgram? = null
    private var quadGeometry: QuadGeometry? = null

    // Primary HDR Framebuffer Object (FBO) resources (GL_RGBA16F with GL_RGBA8 fallback)
    private var hdrFboId = 0
    private var hdrTextureId = 0
    private var hdrWidth = 0
    private var hdrHeight = 0
    private var isHdrSupported = false

    // Downsampled Bloom Ping-Pong FBO resources
    private var bloomFboA = 0
    private var bloomTexA = 0
    private var bloomFboB = 0
    private var bloomTexB = 0
    private var bloomWidth = 0
    private var bloomHeight = 0

    // Temporary coarse ray target. The unchanged full internal HDR target is still used for
    // upsampling, bloom, and composite; only the expensive geodesic pass uses this smaller grid.
    private var coarseRayFboId = 0
    private var coarseRayTextureId = 0
    private var coarseRayWidth = 0
    private var coarseRayHeight = 0
    private var coarseRayTargetAvailable = false

    // Animation cache pass FBOs: [0] HDR + ray records 0-2, [1] records 3-5, [2] records 6-8.
    private val animationCacheFboIds = IntArray(AnimationGate.CACHE_PASS_COUNT)
    private val animationRayFboId get() = animationCacheFboIds[0]
    // Static hit cache written by the animation geodesic build: one packed record (emitting disk
    // crossings 1/2) per M7 ray, see gargantua_geodesic.frag GARGANTUA_ANIMATION_SEMANTIC_CACHE outputs.
    private val animationRayRecordTextureIds = IntArray(AnimationGate.CACHE_PASS_COUNT * AnimationGate.RECORDS_PER_CACHE_PASS)
    private var animationCacheComplete = false
    /** DIAG only (lens capture): the program and ray signature of the last geodesic trace. */
    private var lastTraceProgram: ShaderProgram? = null
    private var lastTraceRaySignature: RaySceneSignature? = null
    // Coarse sampling modes: the material pass writes the ray grid here and the result is upscaled.
    private var animatedRayFboId = 0
    private var animatedRayTextureId = 0
    // Geodesic (RK4) passes issued this frame; a MODULATE frame must issue none.
    private var geodesicPassesThisFrame = 0
    private var lastFrameGeodesicPasses = 0
    private var animationRayWidth = 0
    private var animationRayHeight = 0
    private var animationRayTextureId = 0
    // Emission of the cached hits at the build time, written once per build by the material pass.
    private var builtEmissionFboId = 0
    private var builtEmissionTextureId = 0
    private var animationSkyDirectionTextureId = 0
    private var animationSkyOriginalTextureId = 0
    private var builtEmissionValid = false
    private val animationBuildTimeDigits = FloatArray(GargantuaAnimation.TIME_DIGIT_COUNT)
    private var modulatedHdrFboId = 0
    private var modulatedHdrTextureId = 0
    private var modulatedHdrWidth = 0
    private var modulatedHdrHeight = 0
    private var animationResourcesReady = false
    private var animationProgramAttempted = false
    private var animationFailureStatus: String? = null
    private var animationFailureLatched = false
    private var animationCacheValid = false
    private var animationCacheSignature: RaySceneSignature? = null
    private var lastRaySignatureChangeNanos = 0L
    private var lastRaySignatureField = "none"
    private var animationLastNotReadyReason = "none"
    private var animationRebuildCount = 0
    private var animOriginNanos = 0L
    private var lastPresentedModulated = false
    private var lastAnimationRequested = false
    private var lastAnimationAmplitudePercent = -1
    private val animationGate = AnimationGate()
    // TEMPORARY physical-GPU diagnostics; only used while state.diagnosticView != 0.
    private val gpuDiagnostics = GargantuaGpuDiagnostics(context)
    private var diagActiveThisFrame = false
    private var lastDiagnosticView = 0
    private var animationRecordPrecision = GargantuaGpuDiagnostics.RecordPrecision.AS_SHIPPED
    private var animationRebuildGeneration = 0

    // Zero-allocation reusable scratch buffers
    private val scratchDrawBuffersSingle = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
    private val scratchDrawBuffersAnimation = intArrayOf(
        GLES30.GL_COLOR_ATTACHMENT0,
        GLES30.GL_COLOR_ATTACHMENT1,
        GLES30.GL_COLOR_ATTACHMENT2,
        GLES30.GL_COLOR_ATTACHMENT3,
        GLES30.GL_COLOR_ATTACHMENT4,
        GLES30.GL_COLOR_ATTACHMENT5
    )
    // Cache passes 1 and 2 only write ray records; the HDR output (location 0) is not attached.
    private val scratchDrawBuffersAnimationRecords = intArrayOf(
        GLES30.GL_NONE,
        GLES30.GL_COLOR_ATTACHMENT1,
        GLES30.GL_COLOR_ATTACHMENT2,
        GLES30.GL_COLOR_ATTACHMENT3
    )
    private val scratchZeroColor = floatArrayOf(0f, 0f, 0f, 0f)
    private val scratchTimeDigits = FloatArray(8)
    private var lastTelemetryDispatchUptimeMs = 0L

    // Debug-only workload instrumentation FBOs. These are never allocated or attached during
    // normal production rendering. A second and third color attachment carry per-pixel counters;
    // reduction passes collapse them to one texel before the debug-only readback.
    private var workloadFboId = 0
    private var workloadTierTextureId = 0
    private var workloadCostTextureId = 0
    private var workloadSemanticCacheTextureId = 0
    private var workloadReduceFboA = 0
    private var workloadReduceTextureA = 0
    private var workloadReduceFboB = 0
    private var workloadReduceTextureB = 0
    private var workloadWidth = 0
    private var workloadHeight = 0
    private var workloadRayTextureId = 0
    private var workloadTelemetrySupported = false
    private var workloadProgramAttempted = false
    private var workloadProgramFailureStatus: String? = null
    private var workloadDiagnosticStatus = "TEL OFF"
    private var workloadReadbackValid = true
    private var workloadReadbackFailureStatus: String? = null
    private var semanticCacheDiagnosticStatus: String? = null

    // Dirty/invalidation scheduling. Ray-scene changes, bloom extraction changes, and composite
    // changes are intentionally tracked separately so exposure/bloom-intensity changes do not
    // retrace photons.
    private var lastSceneSignature: SceneSignature? = null
    private var lastRaySceneSignature: RaySceneSignature? = null
    private var lastBloomSignature: BloomSignature? = null
    private var lastCompositeSignature: CompositeSignature? = null
    private var sceneDirty = true
    @Volatile private var presentationInvalidationPending = true
    @Volatile private var renderReadyListener: (() -> Unit)? = null
    private val renderReadyGate = GargantuaRenderReadyGate {
        renderReadyListener?.invoke()
    }

    private var lastWorkloadStats = GargantuaWorkloadStats.unavailable()
    private var lastPassTimings = GargantuaPassTimings()
    private var lastIscoRadius = 2.91f

    private var startTimeNanos: Long = 0L
    private var fpsAccumulatorTimeNanos: Long = 0L
    private var fpsLastSubmittedNanos: Long = 0L
    private var fpsFrames: Int = 0

    internal data class SceneSignature(
        val width: Int,
        val height: Int,
        val renderScale: Float,
        val mass: Float,
        val spin: Float,
        val camDist: Float,
        val camInclinationDeg: Float,
        val camAzimuthDeg: Float,
        val camTargetX: Float,
        val camTargetY: Float,
        val camTargetZ: Float,
        val maxSteps: Int,
        val enableDisk: Boolean,
        val diskOuterRadius: Float,
        val enableObject: Boolean,
        val objectRadius: Float,
        val objectOrbitRadius: Float,
        val objectPhi0: Float,
        val objectZ: Float,
        val enableDoppler: Boolean,
        val useGeodesicShader: Boolean,
        val exposure: Float,
        val enableBloom: Boolean,
        val bloomIntensity: Float,
        val bloomThreshold: Float
    ) {
        companion object {
            fun fromState(state: GargantuaRenderState, w: Int, h: Int): SceneSignature {
                return SceneSignature(
                    width = w,
                    height = h,
                    renderScale = state.renderScale,
                    mass = state.mass,
                    spin = state.spin,
                    camDist = state.camDist,
                    camInclinationDeg = state.camInclinationDeg,
                    camAzimuthDeg = state.camAzimuthDeg,
                    camTargetX = state.camTargetX,
                    camTargetY = state.camTargetY,
                    camTargetZ = state.camTargetZ,
                    maxSteps = state.maxSteps,
                    enableDisk = state.enableDisk,
                    diskOuterRadius = state.diskOuterRadius,
                    enableObject = state.enableObject,
                    objectRadius = state.objectRadius,
                    objectOrbitRadius = state.objectOrbitRadius,
                    objectPhi0 = state.objectPhi0,
                    objectZ = state.objectZ,
                    enableDoppler = state.enableDoppler,
                    useGeodesicShader = state.useGeodesicShader,
                    exposure = state.exposure,
                    enableBloom = state.enableBloom,
                    bloomIntensity = state.bloomIntensity,
                    bloomThreshold = state.bloomThreshold
                )
            }
        }
    }

    internal data class RaySceneSignature(
        val width: Int,
        val height: Int,
        val renderScale: Float,
        val mass: Float,
        val spin: Float,
        val camDist: Float,
        val camInclinationDeg: Float,
        val camAzimuthDeg: Float,
        val camTargetX: Float,
        val camTargetY: Float,
        val camTargetZ: Float,
        val maxSteps: Int,
        val enableDisk: Boolean,
        val diskOuterRadius: Float,
        val enableObject: Boolean,
        val objectRadius: Float,
        val objectOrbitRadius: Float,
        val objectPhi0: Float,
        val objectZ: Float,
        val enableDoppler: Boolean,
        val useGeodesicShader: Boolean,
        val debugCoarseSamplingBlockSize: Int,
        val enableWorkloadTelemetry: Boolean,
        val enableAnimation: Boolean
    ) {
        companion object {
            fun fromState(state: GargantuaRenderState, w: Int, h: Int): RaySceneSignature =
                RaySceneSignature(
                    width = w,
                    height = h,
                    renderScale = state.renderScale,
                    mass = state.mass,
                    spin = state.spin,
                    camDist = state.camDist,
                    camInclinationDeg = state.camInclinationDeg,
                    camAzimuthDeg = state.camAzimuthDeg,
                    camTargetX = state.camTargetX,
                    camTargetY = state.camTargetY,
                    camTargetZ = state.camTargetZ,
                    maxSteps = state.maxSteps,
                    enableDisk = state.enableDisk,
                    diskOuterRadius = state.diskOuterRadius,
                    enableObject = state.enableObject,
                    objectRadius = state.objectRadius,
                    objectOrbitRadius = state.objectOrbitRadius,
                    objectPhi0 = state.objectPhi0,
                    objectZ = state.objectZ,
                    enableDoppler = state.enableDoppler,
                    useGeodesicShader = state.useGeodesicShader,
                    debugCoarseSamplingBlockSize = effectiveCoarseSamplingBlockSize(state),
                    enableWorkloadTelemetry = state.enableWorkloadTelemetry,
                    enableAnimation = state.enableAnimation && state.useGeodesicShader && !state.enableWorkloadTelemetry
                )
        }
    }

    private data class BloomSignature(
        val enableBloom: Boolean,
        val bloomThreshold: Float
    )

    private data class CompositeSignature(
        val exposure: Float,
        val enableBloom: Boolean,
        val bloomIntensity: Float,
        val isPaused: Boolean
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "Unknown"
        val glRenderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown"
        val glVendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "Unknown"
        Log.i(TAG, "Gargantua GLES surface created: Version=$glVersion, Renderer=$glRenderer, Vendor=$glVendor")

        // EGL context recreation invalidates all prior object names; do not let stationary-cache
        // dimensions accidentally skip reallocation in the new context. The ready gate also
        // requires a fresh post-resource request for this context generation.
        renderReadyGate.reset()
        resetGpuResourceHandlesForNewContext()
        gpuDiagnostics.forgetContext()

        val vertSource = ShaderSource.loadVertexShader(context)

        // 1. Compile Phase M4/M5/M6 relativistic photon geodesic shader
        var isGeodesicReady = false
        geodesicProgram?.release()
        geodesicProgram = null
        try {
            val geodesicFragSource = ShaderSource.loadGeodesicFragmentShader(context)
            geodesicProgram = ShaderProgram.create(vertSource, geodesicFragSource)
            isGeodesicReady = (geodesicProgram != null)
            if (isGeodesicReady) {
                Log.i(TAG, "Gargantua relativistic photon geodesic shader compiled successfully.")
            } else {
                Log.w(TAG, "Gargantua geodesic shader failed to link; preparing fallback.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load geodesic shader asset; falling back to test shader", e)
        }

        // The workload variant of the canonical geodesic shader is compiled lazily only after
        // the temporary on-device diagnostic is explicitly enabled. The normal render path does
        // not pay the shader/FBO setup cost and continues to use the production program directly.
        workloadGeodesicProgram?.release()
        workloadGeodesicProgram = null
        reduceProgram?.release()
        reduceProgram = null
        workloadProgramAttempted = false
        workloadProgramFailureStatus = null
        workloadDiagnosticStatus = "TEL OFF"
        workloadReadbackValid = true
        workloadReadbackFailureStatus = null
        semanticCacheDiagnosticStatus = null
        animationGeodesicProgram?.release()
        animationGeodesicProgram = null
        animationMaterialProgram?.release()
        animationMaterialProgram = null
        animationProgramAttempted = false
        animationResourcesReady = false
        animationFailureStatus = null
        animationFailureLatched = false
        animationCacheValid = false
        animationCacheSignature = null
        lastRaySignatureField = "context"
        animationLastNotReadyReason = "none"
        animationRebuildCount = 0
        lastPresentedModulated = false
        lastAnimationRequested = false
        lastAnimationAmplitudePercent = -1

        // 2. Compile M1 baseline test shader as guaranteed fallback
        val testFragSource = ShaderSource.loadFragmentShader(context)
        testProgram?.release()
        testProgram = ShaderProgram.create(vertSource, testFragSource)

        // 3. Compile M6 cinematic presentation shaders (bright-pass, blur, ACES composite)
        try {
            val brightPassSource = ShaderSource.loadBrightPassFragmentShader(context)
            brightPassProgram?.release()
            brightPassProgram = ShaderProgram.create(vertSource, brightPassSource)

            val blurSource = ShaderSource.loadBlurFragmentShader(context)
            blurProgram?.release()
            blurProgram = ShaderProgram.create(vertSource, blurSource)

            val compositeSource = ShaderSource.loadCompositeFragmentShader(context)
            compositeProgram?.release()
            compositeProgram = ShaderProgram.create(vertSource, compositeSource)
        } catch (e: Exception) {
            Log.w(TAG, "Could not compile bloom/composite shaders; will use direct blit", e)
        }

        // 4. Compile fallback blit fragment shader
        try {
            val blitFragSource = ShaderSource.loadBlitFragmentShader(context)
            blitProgram?.release()
            blitProgram = ShaderProgram.create(vertSource, blitFragSource)
        } catch (e: Exception) {
            Log.w(TAG, "Could not load blit shader", e)
        }

        semanticCacheDebugProgram?.release()
        semanticCacheDebugProgram = if (ENABLE_SEMANTIC_CACHE_DEBUG) {
            try {
                ShaderProgram.create(
                    vertSource,
                    ShaderSource.loadSemanticCacheFalseColorFragmentShader(context)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not load semantic-cache false-color shader", e)
                null
            }
        } else {
            null
        }

        if (!isGeodesicReady && testProgram == null) {
            val errorMsg = "Failed to compile any Gargantua shader program."
            Log.e(TAG, errorMsg)
            stateHolder.updateTelemetry {
                it.copy(
                    glesVersion = glVersion,
                    glRenderer = glRenderer,
                    isInitialized = false,
                    errorMessage = errorMsg
                )
            }
            return
        }

        // Initialize fullscreen geometry
        quadGeometry?.release()
        quadGeometry = QuadGeometry().apply { init() }

        // Clear color (pure black for black hole horizon)
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Force one initial scene render. Subsequent frames are requested only by state changes.
        lastSceneSignature = null
        lastRaySceneSignature = null
        lastBloomSignature = null
        lastCompositeSignature = null
        sceneDirty = true
        presentationInvalidationPending = true
        lastWorkloadStats = GargantuaWorkloadStats.unavailable()
        lastPassTimings = GargantuaPassTimings()

        // Initialize baseline timers
        val now = System.nanoTime()
        startTimeNanos = now
        lastRaySignatureChangeNanos = now
        animOriginNanos = now
        fpsAccumulatorTimeNanos = now
        fpsLastSubmittedNanos = 0L
        fpsFrames = 0

        stateHolder.updateTelemetry {
            it.copy(
                fps = 0f,
                glesVersion = glVersion,
                glRenderer = glRenderer,
                isInitialized = true,
                errorMessage = null,
                isGeodesicActive = isGeodesicReady
            )
        }
        // This is the explicit post-resource lifecycle handshake. If the size was already known,
        // it requests the first presentation for this new EGL context; otherwise onSurfaceChanged
        // completes the gate after the non-empty size arrives.
        val currentViewport = stateHolder.getState()
        renderReadyGate.markSurfaceSize(currentViewport.viewportWidth, currentViewport.viewportHeight)
        renderReadyGate.markResourcesReady()
    }

    /**
     * Compiles the existing workload/reduction programs only after the temporary diagnostic is
     * enabled. This keeps the normal release render path identical until the temporary diagnostic
     * control is used on the physical device.
     */
    private fun ensureWorkloadPrograms(): Boolean {
        if (workloadGeodesicProgram != null && reduceProgram != null) {
            workloadDiagnosticStatus = "TEL ON · WORKLOAD READY"
            return true
        }
        if (workloadProgramAttempted) {
            workloadDiagnosticStatus = workloadProgramFailureStatus ?: "TEL ON · WORKLOAD NOT READY"
            return false
        }
        workloadProgramAttempted = true
        workloadProgramFailureStatus = null
        workloadDiagnosticStatus = "TEL ON · WORKLOAD COMPILING"

        var workloadSourceLength = 0
        val glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "UNKNOWN"
        val glslVersion = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: "UNKNOWN"

        return try {
            val vertexSource = ShaderSource.loadVertexShader(context)
            val workloadSource = ShaderSource.loadWorkloadTelemetryGeodesicFragmentShader(
                context,
                includeSemanticCache = ENABLE_SEMANTIC_CACHE_DEBUG
            )
            workloadSourceLength = workloadSource.length
            var workloadFailure: ShaderProgram.CreationFailure? = null
            val workload = ShaderProgram.create(
                vertexSource,
                workloadSource,
                onFailure = { workloadFailure = it }
            )
            var reduceFailure: ShaderProgram.CreationFailure? = null
            val reduce = ShaderProgram.create(
                vertexSource,
                ShaderSource.loadReduceFragmentShader(context),
                onFailure = { reduceFailure = it }
            )
            if (workload == null || reduce == null) {
                workload?.release()
                reduce?.release()
                workloadGeodesicProgram = null
                reduceProgram = null
                val failure = if (workload == null) workloadFailure else reduceFailure
                workloadProgramFailureStatus = workloadProgramFailureStatus(
                    failure = failure,
                    sourceLength = workloadSourceLength,
                    glVersion = glVersion,
                    glslVersion = glslVersion
                )
                workloadDiagnosticStatus = workloadProgramFailureStatus!!
                false
            } else {
                workloadGeodesicProgram = workload
                reduceProgram = reduce
                workloadProgramFailureStatus = null
                workloadDiagnosticStatus = "TEL ON · WORKLOAD READY"
                true
            }
        } catch (e: Exception) {
            workloadGeodesicProgram?.release()
            workloadGeodesicProgram = null
            reduceProgram?.release()
            reduceProgram = null
            workloadProgramFailureStatus =
                "TEL ON · WORKLOAD INIT EXCEPTION · ${compactWorkloadDetail(e.message ?: e.javaClass.simpleName)}" +
                    " · SRC_LEN=$workloadSourceLength · GL_VERSION=${compactWorkloadDetail(glVersion)}" +
                    " · GLSL_VERSION=${compactWorkloadDetail(glslVersion)}"
            workloadDiagnosticStatus = workloadProgramFailureStatus!!
            false
        }
    }

    private fun workloadProgramFailureStatus(
        failure: ShaderProgram.CreationFailure?,
        sourceLength: Int,
        glVersion: String,
        glslVersion: String
    ): String {
        val label = failure?.label ?: "UNKNOWN"
        val log = compactWorkloadDetail(failure?.log ?: "NO_INFO_LOG").take(200)
        return "TEL ON · WORKLOAD PROGRAM CREATE FAILED · $label · $log" +
            " · SRC_LEN=$sourceLength · GL_VERSION=${compactWorkloadDetail(glVersion)}" +
            " · GLSL_VERSION=${compactWorkloadDetail(glslVersion)}"
    }

    private fun compactWorkloadDetail(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().ifEmpty { "EMPTY" }

    private fun workloadStatsDiagnosticStatus(stats: GargantuaWorkloadStats): String {
        val sumOk = stats.totalPixels == stats.shadedBlocks
        val averageRays = if (stats.shadedBlocks > 0L) {
            stats.totalRaysFrame.toDouble() / stats.shadedBlocks.toDouble()
        } else {
            0.0
        }
        return "TEL ON · STATS READY · " +
            "N0=${stats.tier0Pixels} N1=${stats.tier1Pixels} N2=${stats.tier2Pixels} " +
            "EXP_PRIMARY=${stats.shadedBlocks} SUM_OK=$sumOk · " +
            "TOTAL_RAYS=${stats.totalRaysFrame} AVG_RAYS=${String.format(Locale.US, "%.3f", averageRays)} " +
            "TOTAL_STEPS=${stats.totalIntegrationSteps} MAX_STEPS=${stats.maximumIntegrationSteps} " +
            "DISK_HITS=${stats.diskIntersections}" + semanticCacheStatusSuffix()
    }

    private fun semanticCacheInternalFormat(): Int =
        if (SEMANTIC_CACHE_FORMAT == "32F") GLES30.GL_RGBA32F else GLES30.GL_RGBA16F

    private fun semanticCacheStatusSuffix(): String = when {
        semanticCacheDiagnosticStatus != null -> " · ${semanticCacheDiagnosticStatus}"
        ENABLE_SEMANTIC_CACHE_DEBUG && workloadSemanticCacheTextureId != 0 ->
            " · SEM OK $SEMANTIC_CACHE_FORMAT"
        else -> ""
    }

    private fun workloadStatusWithSemantic(baseStatus: String): String =
        baseStatus + semanticCacheStatusSuffix()

    /** Releases only the temporary diagnostic resources when it is toggled off. */
    private fun releaseWorkloadDiagnosticResources() {
        deleteWorkloadFbos()
        workloadGeodesicProgram?.release()
        workloadGeodesicProgram = null
        reduceProgram?.release()
        reduceProgram = null
        workloadProgramAttempted = false
        workloadProgramFailureStatus = null
        workloadDiagnosticStatus = "TEL OFF"
        workloadReadbackValid = true
        workloadReadbackFailureStatus = null
        semanticCacheDiagnosticStatus = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        stateHolder.updateState {
            it.copy(viewportWidth = width, viewportHeight = height)
        }
        lastSceneSignature = null
        lastRaySceneSignature = null
        lastBloomSignature = null
        lastCompositeSignature = null
        animationCacheValid = false
        animationCacheSignature = null
        lastRaySignatureField = "resize"
        sceneDirty = true
        presentationInvalidationPending = true
        // The state listener covers a changed size. The ready gate additionally covers an
        // unchanged-size callback and guarantees the request occurs after resources are ready.
        renderReadyGate.markSurfaceSize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStartNanos = System.nanoTime()
        val userState = stateHolder.getState()
        // TEMPORARY forensic run: phase overrides apply to this frame's effective state only (never to the holder).
        val state = if (userState.diagnosticView != 0) gpuDiagnostics.forensicState(userState, frameStartNanos) else userState
        if (state.isPaused) {
            if (userState.diagnosticView != 0) gpuDiagnostics.interruptRun("renderer paused")
            if (state.enableWorkloadTelemetry) {
                workloadDiagnosticStatus = "TEL ON · GL PAUSED"
                stateHolder.updateTelemetry { it.copy(adaptiveWorkload = workloadDiagnosticStatus) }
            }
            // Resume must re-present the cached HDR image even if the camera state is unchanged.
            lastCompositeSignature = null
            return
        }

        val surfaceW = state.viewportWidth
        val surfaceH = state.viewportHeight
        if (surfaceW <= 0 || surfaceH <= 0) {
            if (state.enableWorkloadTelemetry) {
                workloadDiagnosticStatus = "TEL ON · WAITING FOR SURFACE"
                stateHolder.updateTelemetry { it.copy(adaptiveWorkload = workloadDiagnosticStatus) }
            }
            return
        }

        val quad = quadGeometry ?: run {
            if (state.enableWorkloadTelemetry) {
                workloadDiagnosticStatus = "TEL ON · GL NOT READY"
                stateHolder.updateTelemetry { it.copy(adaptiveWorkload = workloadDiagnosticStatus) }
            }
            return
        }
        if (state.enableWorkloadTelemetry) {
            workloadDiagnosticStatus = "TEL ON · GL FRAME OBSERVED"
        }
        val activeProductionProgram = if (state.useGeodesicShader && geodesicProgram != null) {
            geodesicProgram
        } else {
            testProgram
        } ?: run {
            if (state.enableWorkloadTelemetry) {
                workloadDiagnosticStatus = "TEL ON · PRODUCTION PROGRAM NOT READY"
                stateHolder.updateTelemetry { it.copy(adaptiveWorkload = workloadDiagnosticStatus) }
            }
            return
        }

        diagActiveThisFrame = state.diagnosticView != 0
        if (state.diagnosticView != lastDiagnosticView) {
            lastDiagnosticView = state.diagnosticView
            presentationInvalidationPending = true
            if (state.diagnosticView == 0) gpuDiagnostics.release()
        }
        val requestedRecordPrecision = GargantuaGpuDiagnostics.RecordPrecision.fromIndex(state.diagnosticRecordPrecision)
        if (requestedRecordPrecision != animationRecordPrecision || state.diagnosticRebuildGeneration != animationRebuildGeneration) {
            // Diagnostics A/B: rebuild the animation programs with the requested precision and retrace.
            animationRecordPrecision = requestedRecordPrecision
            animationRebuildGeneration = state.diagnosticRebuildGeneration
            animationGeodesicProgram?.release()
            animationGeodesicProgram = null
            animationMaterialProgram?.release()
            animationMaterialProgram = null
            animationProgramAttempted = false
            animationFailureLatched = false
            animationFailureStatus = null
            animationCacheValid = false
            animationCacheSignature = null
            sceneDirty = true
            presentationInvalidationPending = true
        }
        val forcePresentation = presentationInvalidationPending
        presentationInvalidationPending = false

        val scale = state.renderScale.coerceIn(0.25f, 1.0f)
        val renderW = max(1, (surfaceW * scale).roundToInt())
        val renderH = max(1, (surfaceH * scale).roundToInt())

        // FBO allocation is dimension-dependent and therefore cheap on a stationary frame: helpers
        // return immediately when their existing attachments already match the target. The main
        // HDR target remains at the existing internal resolution for bloom/composite; only the
        // temporary coarse ray target may use a coarser uniform grid.
        updateHdrFbo(renderW, renderH)
        val requestedSamplingGrid = GargantuaCoarseSampling.grid(
            renderW,
            renderH,
            if (state.useGeodesicShader) effectiveCoarseSamplingBlockSize(state)
            else GargantuaCoarseSampling.BASELINE_BLOCK_SIZE
        )
        val rayGrid = if (
            requestedSamplingGrid.blockSize > GargantuaCoarseSampling.BASELINE_BLOCK_SIZE &&
            ensureCoarseRayFbo(requestedSamplingGrid.rayWidth, requestedSamplingGrid.rayHeight)
        ) {
            requestedSamplingGrid
        } else {
            // Returning to 1x1 does not need the diagnostic target; release it immediately so a
            // later mode change allocates a fresh target with the new ray-grid dimensions.
            if (coarseRayTargetAvailable || requestedSamplingGrid.blockSize > GargantuaCoarseSampling.BASELINE_BLOCK_SIZE) {
                deleteCoarseRayFbo()
            }
            GargantuaCoarseSampling.grid(renderW, renderH, GargantuaCoarseSampling.BASELINE_BLOCK_SIZE)
        }
        val rayTextureId = if (rayGrid.blockSize > 1) coarseRayTextureId else hdrTextureId
        val rayFboId = if (rayGrid.blockSize > 1) coarseRayFboId else hdrFboId

        if (state.enableBloom) {
            updateBloomFbos(max(1, renderW / 2), max(1, renderH / 2))
        }

        val now = System.nanoTime()
        val elapsedSecondsDouble = (now - startTimeNanos).toDouble() / 1_000_000_000.0
        val elapsedSeconds = elapsedSecondsDouble.toFloat()
        val animationRequested =
            state.enableAnimation &&
                state.animationAmplitudePercent.coerceIn(0, 80) > 0 &&
                state.useGeodesicShader &&
                !state.enableWorkloadTelemetry
        val currentAmplitudePercent = state.animationAmplitudePercent.coerceIn(0, 80)
        if (animationRequested != lastAnimationRequested || currentAmplitudePercent != lastAnimationAmplitudePercent) {
            if (animationRequested && !lastAnimationRequested) {
                // Enabling ANIM restarts both the clock and the settle gate.
                animOriginNanos = now
                lastRaySignatureChangeNanos = now
                lastRaySignatureField = "ANIM ENABLE"
                animationFailureLatched = false
                animationProgramAttempted = false
                animationFailureStatus = null
                animationCacheValid = false
                animationCacheSignature = null
                sceneDirty = true
            } else if (animationRequested && currentAmplitudePercent != lastAnimationAmplitudePercent) {
                // The amplitude enters the disk density the cache was traced with; the material pass
                // can only advance a cache built with the amplitude it presents.
                animationCacheValid = false
                animationCacheSignature = null
                sceneDirty = true
            }
            lastAnimationRequested = animationRequested
            lastAnimationAmplitudePercent = currentAmplitudePercent
            presentationInvalidationPending = true
        }
        val animationElapsedSecondsDouble =
            ((now - animOriginNanos).coerceAtLeast(0L)).toDouble() / 1_000_000_000.0
        val iscoRadius = com.zig.gargantua.disk.KerrIsco.compute(
            state.mass.toDouble(),
            state.spin.toDouble() * state.mass.toDouble()
        )
        val massDouble = max(state.mass.toDouble(), 1.0e-6)
        val spinDouble = (state.spin * state.mass).toDouble()
        val sqrtMass = sqrt(massDouble)
        val omegaIsco = sqrtMass / max(1.0e-6, iscoRadius.pow(1.5) + spinDouble * sqrtMass)
        val timeScale = (2.0 * Math.PI / state.animationSpeed.periodSeconds) / omegaIsco
        val animTime = if (animationRequested) animationElapsedSecondsDouble * timeScale else 0.0
        GargantuaAnimation.fillTimeDigits(animTime, scratchTimeDigits)
        animationLastNotReadyReason = "none"
        val animationReady = when {
            !animationRequested -> false
            animationFailureLatched -> {
                animationLastNotReadyReason = animationFailureStatus ?: "LATCHED_FAILURE"
                false
            }
            rayTextureId == 0 || renderW <= 0 || renderH <= 0 || rayGrid.rayWidth <= 0 || rayGrid.rayHeight <= 0 -> {
                animationLastNotReadyReason = "TARGET/DIMENSIONS"
                false
            }
            !isHdrSupported -> {
                failAnimation("ANIM FAILED: HDR16F REQUIRED")
                animationLastNotReadyReason = "HDR"
                false
            }
            !ensureAnimationPrograms() -> {
                animationLastNotReadyReason = animationFailureStatus ?: "PROGRAM"
                false
            }
            else -> when (ensureAnimationFbos(
                rayGrid.rayWidth, rayGrid.rayHeight, rayTextureId, renderW, renderH
            )) {
                AnimationResourceResult.READY -> true
                AnimationResourceResult.NOT_READY -> {
                    animationLastNotReadyReason = "RESIZE"
                    false
                }
                AnimationResourceResult.FAILED -> {
                    animationLastNotReadyReason = animationFailureStatus ?: "FBO"
                    false
                }
            }
        }

        // SceneSignature remains the complete public/test-visible equality record. The renderer
        // uses the narrower signatures below to avoid retracing for presentation-only changes.
        val currentSig = SceneSignature.fromState(state, surfaceW, surfaceH)
        if (currentSig != lastSceneSignature) {
            lastSceneSignature = currentSig
        }

        val raySig = RaySceneSignature.fromState(state, surfaceW, surfaceH)
        val previousRaySig = lastRaySceneSignature
        val signatureChangedThisFrame = raySig != previousRaySig
        if (signatureChangedThisFrame) {
            lastRaySceneSignature = raySig
            sceneDirty = true
            animationCacheValid = false
            animationCacheSignature = null
            lastRaySignatureChangeNanos = now
            lastRaySignatureField = changedRaySignatureField(previousRaySig, raySig)
        }

        val gateDecision = animationGate.decide(
            AnimationGate.Input(
                animationRequested = animationRequested,
                resourcesReady = animationReady,
                cacheValid = animationCacheValid,
                sceneDirty = sceneDirty,
                signatureChangedThisFrame = signatureChangedThisFrame,
                nowNanos = now,
                lastChangeNanos = lastRaySignatureChangeNanos,
                amplitudePercent = state.animationAmplitudePercent,
                cacheComplete = animationCacheComplete
            )
        )
        val cameraStable = gateDecision.cameraStable

        val bloomSig = BloomSignature(state.enableBloom, state.bloomThreshold)
        val bloomChanged = bloomSig != lastBloomSignature
        lastBloomSignature = bloomSig

        val compositeSig = CompositeSignature(
            exposure = state.exposure,
            enableBloom = state.enableBloom,
            bloomIntensity = state.bloomIntensity,
            isPaused = state.isPaused
        )
        val compositeChanged = compositeSig != lastCompositeSignature
        lastCompositeSignature = compositeSig

        if (state.enableWorkloadTelemetry) {
            ensureWorkloadPrograms()
        } else if (workloadGeodesicProgram != null || reduceProgram != null || workloadFboId != 0) {
            releaseWorkloadDiagnosticResources()
        }

        val workloadRequested =
            state.useGeodesicShader &&
                state.enableWorkloadTelemetry &&
                workloadGeodesicProgram != null &&
                reduceProgram != null &&
                ensureWorkloadFbo(rayGrid.rayWidth, rayGrid.rayHeight, rayTextureId)
        if (state.enableWorkloadTelemetry) {
            when {
                !state.useGeodesicShader ->
                    workloadDiagnosticStatus = "TEL ON · GEODESIC PATH OFF"
                workloadRequested ->
                    workloadDiagnosticStatus = workloadStatusWithSemantic("TEL ON · MRT/FBO READY")
                workloadGeodesicProgram == null || reduceProgram == null ->
                    workloadDiagnosticStatus = workloadProgramFailureStatus ?: "TEL ON · WORKLOAD NOT READY"
                else -> {
                    if (workloadDiagnosticStatus != "TEL ON · REDUCTION FBO NOT READY") {
                        workloadDiagnosticStatus = "TEL ON · MRT/FBO NOT READY"
                    }
                }
            }
        }

        var geodesicCpuSubmitMs = 0f
        var coarseUpscaleCpuSubmitMs = 0f
        var brightPassCpuSubmitMs = 0f
        var horizontalBlurCpuSubmitMs = 0f
        var verticalBlurCpuSubmitMs = 0f
        var compositeCpuSubmitMs = 0f

        geodesicPassesThisFrame = 0
        val gateRequestsRebuild = when (gateDecision.action) {
            AnimationGate.Action.REBUILD -> true
            AnimationGate.Action.COMPLETE_CACHE,
            AnimationGate.Action.MODULATE,
            AnimationGate.Action.PLAIN -> false
        }
        val renderedScene = sceneDirty || (animationRequested && animationReady && gateRequestsRebuild)
        if (gateRequestsRebuild && animationRequested && animationReady) {
            animationRebuildCount++
        }
        if (state.enableWorkloadTelemetry && workloadRequested && !renderedScene) {
            workloadDiagnosticStatus = workloadStatusWithSemantic(
                if (lastWorkloadStats.available && lastWorkloadStats.totalPixels > 0L) {
                    "TEL ON · STATS READY"
                } else {
                    "TEL ON · NO SCENE RENDER"
                }
            )
        }
        if (renderedScene) {
            if (animationReady) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, animationRayFboId)
                GLES30.glDrawBuffers(scratchDrawBuffersAnimation.size, scratchDrawBuffersAnimation, 0)
                // The material pass advances the cache from exactly the time digits it was traced with.
                scratchTimeDigits.copyInto(animationBuildTimeDigits)
                builtEmissionValid = false
                animationCacheComplete = false
            } else if (workloadRequested) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, workloadFboId)
                val workloadDrawBuffers = if (
                    ENABLE_SEMANTIC_CACHE_DEBUG && workloadSemanticCacheTextureId != 0
                ) {
                    intArrayOf(
                        GLES30.GL_COLOR_ATTACHMENT0,
                        GLES30.GL_COLOR_ATTACHMENT1,
                        GLES30.GL_COLOR_ATTACHMENT2,
                        GLES30.GL_COLOR_ATTACHMENT3
                    )
                } else {
                    intArrayOf(
                        GLES30.GL_COLOR_ATTACHMENT0,
                        GLES30.GL_COLOR_ATTACHMENT1,
                        GLES30.GL_COLOR_ATTACHMENT2
                    )
                }
                GLES30.glDrawBuffers(workloadDrawBuffers.size, workloadDrawBuffers, 0)
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, rayFboId)
                GLES30.glDrawBuffers(1, scratchDrawBuffersSingle, 0)
            }
            GLES30.glViewport(0, 0, rayGrid.rayWidth, rayGrid.rayHeight)
            if (animationReady) {
                // Only the float HDR target is cleared: glClear is undefined for the unsigned-integer
                // record targets, which the full-screen pass overwrites completely.
                GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, scratchZeroColor, 0)
            } else {
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }

            val activeProg = when {
                animationReady -> animationGeodesicProgram!!
                workloadRequested -> workloadGeodesicProgram!!
                else -> activeProductionProgram
            }
            val geodesicStart = System.nanoTime()
            activeProg.use()
            activeProg.setUniform2f("u_Resolution", rayGrid.rayWidth.toFloat(), rayGrid.rayHeight.toFloat())
            val animationGeodesicActive = activeProg == animationGeodesicProgram
            activeProg.setUniform1f("u_Time", if (animationGeodesicActive) scratchTimeDigits[0] else elapsedSeconds)
            if (animationGeodesicActive) {
                activeProg.setUniform1f("u_SkyRotation", (animationElapsedSecondsDouble * SKY_DRIFT_RADIANS_PER_SECOND).toFloat())
                activeProg.setUniform1f("u_TimeDigit1", scratchTimeDigits[1])
                activeProg.setUniform2f("u_TimeDigits23", scratchTimeDigits[2], scratchTimeDigits[3])
                activeProg.setUniform2f("u_TimeDigits45", scratchTimeDigits[4], scratchTimeDigits[5])
                activeProg.setUniform2f("u_TimeDigits67", scratchTimeDigits[6], scratchTimeDigits[7])
            }

            if (activeProg == geodesicProgram || activeProg == workloadGeodesicProgram || activeProg == animationGeodesicProgram) {
                // Interactive observer position in Kerr-Schild Cartesian coordinates.
                val inclRad = Math.toRadians(state.camInclinationDeg.toDouble())
                val azRad = Math.toRadians(state.camAzimuthDeg.toDouble())
                val dist = state.camDist.toDouble()

                val dirX = sin(inclRad) * cos(azRad)
                val dirY = sin(inclRad) * sin(azRad)
                val dirZ = cos(inclRad)

                val camX = state.camTargetX.toDouble() + dist * dirX
                val camY = state.camTargetY.toDouble() + dist * dirY
                val camZ = state.camTargetZ.toDouble() + dist * dirZ

                val fwdRawX = state.camTargetX.toDouble() - camX
                val fwdRawY = state.camTargetY.toDouble() - camY
                val fwdRawZ = state.camTargetZ.toDouble() - camZ
                val fwdLen = sqrt(fwdRawX * fwdRawX + fwdRawY * fwdRawY + fwdRawZ * fwdRawZ)
                val fwdX: Double
                val fwdY: Double
                val fwdZ: Double
                if (fwdLen > 1e-6) {
                    fwdX = fwdRawX / fwdLen
                    fwdY = fwdRawY / fwdLen
                    fwdZ = fwdRawZ / fwdLen
                } else {
                    fwdX = -dirX
                    fwdY = -dirY
                    fwdZ = -dirZ
                }

                // Stable orthonormal camera basis for full 360-degree orbit.
                val rX = -sin(azRad)
                val rY = cos(azRad)
                val rZ = 0.0
                val upX = rY * fwdZ - rZ * fwdY
                val upY = rZ * fwdX - rX * fwdZ
                val upZ = rX * fwdY - rY * fwdX
                val upLen = sqrt(upX * upX + upY * upY + upZ * upZ)
                val normUpX: Double
                val normUpY: Double
                val normUpZ: Double
                if (upLen > 1e-6) {
                    normUpX = upX / upLen
                    normUpY = upY / upLen
                    normUpZ = upZ / upLen
                } else {
                    normUpX = 0.0
                    normUpY = 0.0
                    normUpZ = 1.0
                }

                val fovScale = tan(Math.toRadians(45.0 * 0.5)).toFloat()
                val isco = com.zig.gargantua.disk.KerrIsco.compute(
                    state.mass.toDouble(),
                    state.spin.toDouble() * state.mass.toDouble()
                ).toFloat()
                lastIscoRadius = isco

                // M9 is not part of the production baseline. Avoid even calculating its angular
                // velocity unless an explicit experimental/instrumentation state enables it.
                val omegaObj = if (state.enableObject) {
                    val rObj = state.objectOrbitRadius.toDouble()
                    val mBH = state.mass.toDouble()
                    val aBH = state.spin.toDouble() * mBH
                    val denomOmega = rObj.pow(1.5) + aBH * sqrt(mBH)
                    if (abs(denomOmega) > 1e-12) (sqrt(mBH) / denomOmega).toFloat() else 0.0f
                } else {
                    0.0f
                }

                activeProg.setUniform1f("u_Mass", state.mass)
                activeProg.setUniform1f("u_Spin", state.spin * state.mass)
                activeProg.setUniform3f("u_CamPos", camX.toFloat(), camY.toFloat(), camZ.toFloat())
                activeProg.setUniform3f("u_CamForward", fwdX.toFloat(), fwdY.toFloat(), fwdZ.toFloat())
                activeProg.setUniform3f("u_CamRight", rX.toFloat(), rY.toFloat(), rZ.toFloat())
                activeProg.setUniform3f("u_CamUp", normUpX.toFloat(), normUpY.toFloat(), normUpZ.toFloat())
                activeProg.setUniform1f("u_FovScale", fovScale)
                activeProg.setUniform1i("u_MaxSteps", state.maxSteps)
                activeProg.setUniform1f("u_DiskInnerRadius", isco)
                activeProg.setUniform1f("u_DiskOuterRadius", state.diskOuterRadius)
                activeProg.setUniform1f("u_AnimationAmplitude", if (animationRequested) state.animationAmplitudePercent / 100.0f else 0.0f)
                activeProg.setUniform1i("u_EnableDisk", if (state.enableDisk) 1 else 0)
                activeProg.setUniform1i("u_EnableDoppler", if (state.enableDoppler) 1 else 0)

                activeProg.setUniform1i("u_EnableObject", if (state.enableObject) 1 else 0)
                activeProg.setUniform1f("u_ObjectRadius", state.objectRadius)
                activeProg.setUniform1f("u_ObjectOrbitRadius", state.objectOrbitRadius)
                activeProg.setUniform1f("u_ObjectOmega", omegaObj)
                activeProg.setUniform1f("u_ObjectPhi0", state.objectPhi0)
                activeProg.setUniform1f("u_ObjectZ", state.objectZ)
                activeProg.setUniform3f("u_ObjectBaseColor", 0.15f, 0.85f, 1.0f)
                activeProg.setUniform1f("u_ObjectRadiance", 35.0f)
            }
            if (animationGeodesicActive) {
                activeProg.setUniform1i("u_AnimationCachePass", 0)
            }

            if (diagActiveThisFrame) gpuDiagnostics.beginTimer("geodesic")
            quad.draw()
            lastTraceProgram = activeProg
            lastTraceRaySignature = raySig
            if (diagActiveThisFrame) {
                gpuDiagnostics.endTimer("geodesic")
                gpuDiagnostics.passSnapshot(if (animationReady) "anim build pass0" else "geodesic", intArrayOf())
            }
            geodesicPassesThisFrame++
            geodesicCpuSubmitMs = elapsedMilliseconds(geodesicStart)

            lastWorkloadStats = if (workloadRequested) {
                workloadDiagnosticStatus = workloadStatusWithSemantic("TEL ON · WORKLOAD PASS SUBMITTED")
                collectWorkloadStats(renderW, renderH, rayGrid).also { stats ->
                    workloadDiagnosticStatus = when {
                        !workloadReadbackValid -> workloadStatusWithSemantic(
                            "TEL ON · REDUCTION READBACK INVALID · " +
                                (workloadReadbackFailureStatus ?: "CHECK=UNKNOWN")
                        )
                        stats.available && stats.totalPixels > 0L -> workloadStatsDiagnosticStatus(stats)
                        stats.available -> workloadStatusWithSemantic("TEL ON · REDUCTION READBACK ZERO")
                        else -> workloadStatusWithSemantic("TEL ON · REDUCTION READBACK UNAVAILABLE")
                    }
                }
            } else {
                samplingSummary(rayGrid)
            }

            if (rayGrid.blockSize > GargantuaCoarseSampling.BASELINE_BLOCK_SIZE) {
                val upscaleStart = System.nanoTime()
                upscaleCoarseRayTexture(rayTextureId, renderW, renderH, quad)
                coarseUpscaleCpuSubmitMs = elapsedMilliseconds(upscaleStart)
            }
            if (animationReady) {
                animationCacheValid = true
                animationCacheSignature = raySig
            }
            if (!(animationRequested && !animationReady)) {
                sceneDirty = false
            }
        }

        val animationFrameActive = when (gateDecision.action) {
            AnimationGate.Action.REBUILD,
            AnimationGate.Action.PLAIN -> false
            AnimationGate.Action.COMPLETE_CACHE -> {
                // Presents the cached frame unchanged; the next frame starts the material animation.
                if (animationReady) completeAnimationCache(quad)
                false
            }
            AnimationGate.Action.MODULATE -> {
                if (diagActiveThisFrame && animationReady) gpuDiagnostics.beginTimer("material")
                val ran = animationReady &&
                    runAnimationPass(
                        state,
                        renderW,
                        renderH,
                        rayGrid.rayWidth,
                        rayGrid.rayHeight,
                        rayGrid.blockSize,
                        quad
                    )
                if (diagActiveThisFrame) gpuDiagnostics.endTimer("material")
                ran
            }
        }
        lastFrameGeodesicPasses = geodesicPassesThisFrame
        if (gateDecision.action == AnimationGate.Action.MODULATE && geodesicPassesThisFrame != 0) {
            // Instrumentation assertion: an animation frame re-shades cached hits only.
            failAnimation("ANIM FAILED: $geodesicPassesThisFrame GEODESIC PASSES IN AN ANIMATION FRAME")
        }
        val activePresentationHdrTextureId = if (animationFrameActive) modulatedHdrTextureId else hdrTextureId
        val presentationModulated = animationFrameActive
        val presentationChanged = presentationModulated != lastPresentedModulated
        val shouldRunBloom =
            state.enableBloom &&
                brightPassProgram != null &&
                blurProgram != null &&
                bloomFboA != 0 &&
                (renderedScene || bloomChanged || animationFrameActive || presentationChanged)

        if (shouldRunBloom) {
            // Bright-pass extraction and downsampling.
            val brightStart = System.nanoTime()
            if (diagActiveThisFrame) gpuDiagnostics.beginTimer("bloom")
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboA)
            GLES30.glViewport(0, 0, bloomWidth, bloomHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            brightPassProgram?.let { bp ->
                bp.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, activePresentationHdrTextureId)
                bp.setUniform1i("u_HdrTexture", 0)
                bp.setUniform1f("u_BloomThreshold", state.bloomThreshold)
                quad.draw()
                if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("brightpass", intArrayOf(0))
            }
            brightPassCpuSubmitMs = elapsedMilliseconds(brightStart)

            // Separable Gaussian blur, horizontal.
            val horizontalStart = System.nanoTime()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboB)
            GLES30.glViewport(0, 0, bloomWidth, bloomHeight)
            blurProgram?.let { blur ->
                blur.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
                blur.setUniform1i("u_Texture", 0)
                blur.setUniform2f("u_Direction", 1.0f / bloomWidth, 0.0f)
                quad.draw()
                if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("blur H", intArrayOf(0))
            }
            horizontalBlurCpuSubmitMs = elapsedMilliseconds(horizontalStart)

            // Separable Gaussian blur, vertical.
            val verticalStart = System.nanoTime()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboA)
            GLES30.glViewport(0, 0, bloomWidth, bloomHeight)
            blurProgram?.let { blur ->
                blur.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexB)
                blur.setUniform1i("u_Texture", 0)
                blur.setUniform2f("u_Direction", 0.0f, 1.0f / bloomHeight)
                quad.draw()
                if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("blur V", intArrayOf(0))
            }
            if (diagActiveThisFrame) gpuDiagnostics.endTimer("bloom")
            verticalBlurCpuSubmitMs = elapsedMilliseconds(verticalStart)
        }

        val shouldRunComposite = renderedScene || bloomChanged || compositeChanged || forcePresentation || animationFrameActive || presentationChanged
        if (shouldRunComposite) {
            val compositeStart = System.nanoTime()
            if (diagActiveThisFrame) gpuDiagnostics.beginTimer("composite")
            renderCompositeToDisplay(surfaceW, surfaceH, state, quad, activePresentationHdrTextureId, if (animationFrameActive) modulatedHdrFboId else hdrFboId)
            if (diagActiveThisFrame) gpuDiagnostics.endTimer("composite")
            compositeCpuSubmitMs = elapsedMilliseconds(compositeStart)
            lastPresentedModulated = presentationModulated
        }
        if (diagActiveThisFrame) {
            gpuDiagnostics.afterFrame(
                GargantuaGpuDiagnostics.Frame(
                    nowNanos = now,
                    view = GargantuaGpuDiagnostics.View.fromIndex(state.diagnosticView),
                    precision = animationRecordPrecision,
                    surfaceW = surfaceW,
                    surfaceH = surfaceH,
                    renderW = renderW,
                    renderH = renderH,
                    rayW = rayGrid.rayWidth,
                    rayH = rayGrid.rayHeight,
                    blockSize = rayGrid.blockSize,
                    hdrTexture = hdrTextureId,
                    presentationTexture = activePresentationHdrTextureId,
                    modulatedTexture = if (animationFrameActive) modulatedHdrTextureId else 0,
                    recordTextures = if (animationResourcesReady) animationRayRecordTextureIds.copyOf() else IntArray(0),
                    rayCacheTexture = if (animationResourcesReady) animationRayTextureId else 0,
                    builtEmissionTexture = if (animationResourcesReady) builtEmissionTextureId else 0,
                    animatedRayTexture = if (animationResourcesReady) animatedRayTextureId else 0,
                    diskInnerRadius = lastIscoRadius,
                    diskOuterRadius = state.diskOuterRadius,
                    bloomTexture = if (state.enableBloom) bloomTexA else 0,
                    bloomW = bloomWidth,
                    bloomH = bloomHeight,
                    bloomEnabled = state.enableBloom && bloomFboA != 0,
                    bloomThreshold = state.bloomThreshold,
                    bloomIntensity = state.bloomIntensity,
                    exposure = state.exposure,
                    brightPass = brightPassProgram,
                    composite = compositeProgram,
                    quad = quad,
                    animationActive = animationFrameActive,
                    animationRequested = animationRequested,
                    amplitudePercent = state.animationAmplitudePercent,
                    gateAction = gateDecision.action.name,
                    geodesicPasses = geodesicPassesThisFrame,
                    rebuilds = animationRebuildCount,
                    materialRan = animationFrameActive,
                    bloomRan = shouldRunBloom,
                    compositeRan = shouldRunComposite,
                    animationStatus = animationFailureStatus ?: animationLastNotReadyReason,
                    config = String.format(
                        Locale.US,
                        "camera dist=%.2f incl=%.2f az=%.2f target=%.2f,%.2f,%.2f | renderScale=%.2f render=%dx%d ray=%dx%d block=%d surface=%dx%d | " +
                            "mass=%.2f spin=%.2f rOut=%.1f maxSteps=%d | exposure=%.3f bloom=%s(th=%.2f int=%.2f) doppler=%s disk=%s tel=%s speed=%s | user ANIM=%s amp=%d%%",
                        state.camDist, state.camInclinationDeg, state.camAzimuthDeg, state.camTargetX, state.camTargetY, state.camTargetZ,
                        state.renderScale, renderW, renderH, rayGrid.rayWidth, rayGrid.rayHeight, rayGrid.blockSize, surfaceW, surfaceH,
                        state.mass, state.spin, state.diskOuterRadius, state.maxSteps, state.exposure, state.enableBloom,
                        state.bloomThreshold, state.bloomIntensity, state.enableDoppler, state.enableDisk, state.enableWorkloadTelemetry,
                        state.animationSpeed, userState.enableAnimation, userState.animationAmplitudePercent
                    ),
                    fbos = listOf(
                        "hdr" to hdrFboId, "bloomA" to bloomFboA, "bloomB" to bloomFboB, "coarseRay" to coarseRayFboId,
                        "animCache0" to animationCacheFboIds[0], "animCache1" to animationCacheFboIds[1],
                        "animCache2" to animationCacheFboIds[2], "builtEmission" to builtEmissionFboId,
                        "modulatedHdr" to modulatedHdrFboId, "animatedRay" to animatedRayFboId
                    ),
                    drawRealComposite = {
                        renderCompositeToDisplay(
                            surfaceW, surfaceH, state, quad, activePresentationHdrTextureId,
                            if (animationFrameActive) modulatedHdrFboId else hdrFboId
                        )
                    },
                    camera = floatArrayOf(
                        state.camDist, state.camInclinationDeg, state.camAzimuthDeg,
                        state.camTargetX, state.camTargetY, state.camTargetZ
                    ),
                    mass = state.mass,
                    spinA = state.spin * state.mass,
                    maxSteps = state.maxSteps,
                    enableDoppler = state.enableDoppler,
                    signatureChanged = signatureChangedThisFrame,
                    sceneDirty = sceneDirty,
                    cacheValid = animationCacheValid,
                    cacheComplete = animationCacheComplete,
                    presentedModulated = lastPresentedModulated,
                    redrawTraceWithoutDisk = { fbo, w, h ->
                        val program = lastTraceProgram
                        when {
                            program == null -> "no geodesic trace yet"
                            program !== geodesicProgram && program !== animationGeodesicProgram -> "trace program released or not a geodesic program"
                            lastTraceRaySignature != raySig -> "scene changed since the last trace"
                            w != rayGrid.rayWidth || h != rayGrid.rayHeight -> "ray size ${rayGrid.rayWidth}x${rayGrid.rayHeight} != ${w}x$h"
                            else -> {
                                // Same program, same uniforms and rays as the last trace; only the disk is disabled.
                                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                                GLES30.glDrawBuffers(1, scratchDrawBuffersSingle, 0)
                                GLES30.glViewport(0, 0, w, h)
                                GLES30.glClearBufferfv(GLES30.GL_COLOR, 0, scratchZeroColor, 0)
                                program.use()
                                program.setUniform1i("u_EnableDisk", 0)
                                quad.draw()
                                program.setUniform1i("u_EnableDisk", if (state.enableDisk) 1 else 0)
                                null
                            }
                        }
                    }
                )
            )
        }
        if (ENABLE_SEMANTIC_CACHE_DEBUG && workloadRequested && renderedScene) {
            renderSemanticCacheDebugToDisplay(surfaceW, surfaceH, quad)
        }
        lastPassTimings = GargantuaPassTimings(
            geodesicCpuSubmitMs = geodesicCpuSubmitMs,
            coarseUpscaleCpuSubmitMs = coarseUpscaleCpuSubmitMs,
            brightPassCpuSubmitMs = brightPassCpuSubmitMs,
            horizontalBlurCpuSubmitMs = horizontalBlurCpuSubmitMs,
            verticalBlurCpuSubmitMs = verticalBlurCpuSubmitMs,
            compositeCpuSubmitMs = compositeCpuSubmitMs,
            gpuTimerAvailable = false
        )

        // A WHEN_DIRTY callback is not a continuously ticking display clock. Count only frames
        // that actually submit a composite, and restart the measurement window after an idle gap;
        // otherwise the first frame after a stationary period would be diluted by idle time.
        val submittedAtNanos = System.nanoTime()
        val presentationSubmitted = shouldRunComposite
        var fpsWindowRestarted = false
        if (presentationSubmitted) {
            if (
                fpsLastSubmittedNanos == 0L ||
                submittedAtNanos - fpsLastSubmittedNanos > FPS_IDLE_RESET_NANOS
            ) {
                fpsFrames = 0
                fpsAccumulatorTimeNanos = submittedAtNanos
                fpsWindowRestarted = true
            }
            fpsFrames++
            fpsLastSubmittedNanos = submittedAtNanos
        }
        val fpsInterval = submittedAtNanos - fpsAccumulatorTimeNanos
        val shouldUpdateFps = presentationSubmitted && fpsInterval >= FPS_WINDOW_NANOS
        val measuredFps = when {
            shouldUpdateFps -> (fpsFrames * 1_000_000_000.0f) / fpsInterval
            fpsWindowRestarted -> 0f
            else -> stateHolder.getTelemetry().fps
        }
        val stats = lastWorkloadStats
        val workloadText = if (state.enableWorkloadTelemetry) {
            workloadDiagnosticStatus
        } else {
            when {
                stats.samplingBlockSize > 1 ->
                    "block ${stats.samplingBlockSize}x: unavailable (enable debug telemetry)"
                else ->
                    "Unavailable (debug instrumentation disabled)"
            }
        }
        val animationStatus = when {
            state.enableWorkloadTelemetry -> "ANIM IGNORED · TEL ON"
            animationFailureStatus != null -> animationFailureStatus ?: "ANIM FAILED"
            !state.enableAnimation -> "ANIM OFF"
            state.animationAmplitudePercent == 0 -> "ANIM ±0% · BYPASS"
            !animationReady -> "ANIM WAITING"
            !cameraStable -> "ANIM ±${state.animationAmplitudePercent}% · CAMERA SETTLING"
            animationFrameActive -> "ANIM ±${state.animationAmplitudePercent}% · ACTIVE"
            gateDecision.state == AnimationGate.State.BUILD -> "ANIM BUILD"
            else -> "ANIM ±${state.animationAmplitudePercent}% · READY"
        }
        val currentUptime = System.nanoTime() / 1_000_000L
        if (currentUptime - lastTelemetryDispatchUptimeMs >= TELEMETRY_DISPATCH_INTERVAL_MS || forcePresentation || signatureChangedThisFrame) {
            lastTelemetryDispatchUptimeMs = currentUptime
            val animationDiagnostics = String.format(
                Locale.US,
                "ANIM DIAGNOSTICS: state=%s action=%s geodesicPasses=%d cache=%s complete=%s since=%dms field=%s rebuilds=%d notReady=%s block=%d render=%dx%d",
                gateDecision.state.name,
                gateDecision.action.name,
                lastFrameGeodesicPasses,
                animationCacheValid,
                animationCacheComplete,
                gateDecision.elapsedSinceChangeNanos / 1_000_000L,
                lastRaySignatureField,
                animationRebuildCount,
                animationLastNotReadyReason,
                rayGrid.blockSize,
                renderW,
                renderH
            )

            stateHolder.updateTelemetry {
                it.copy(
                    fps = measuredFps,
                    animationFps = if (animationFrameActive) measuredFps else 0f,
                    animationFrameTimeMs = if (animationFrameActive) ((System.nanoTime() - frameStartNanos) / 1_000_000.0f) else 0f,
                    animationStatus = animationStatus,
                    animationDiagnostics = animationDiagnostics,
                    gpuDiagnosticsReport = if (diagActiveThisFrame) gpuDiagnostics.reportText else "",
                    gpuDiagnosticsStatus = if (diagActiveThisFrame) gpuDiagnostics.statusText else "",
                    frameTimeMs = ((System.nanoTime() - frameStartNanos) / 1_000_000.0f),
                    spin = state.spin,
                    isDiskActive = state.enableDisk,
                    isObjectActive = state.enableObject,
                    iscoRadius = lastIscoRadius,
                    renderScale = scale,
                    renderResolution = "${renderW}x${renderH}",
                    isHdrActive = isHdrSupported,
                    exposure = state.exposure,
                    camDist = state.camDist,
                    camInclinationDeg = state.camInclinationDeg,
                    camAzimuthDeg = state.camAzimuthDeg,
                    camTargetX = state.camTargetX,
                    camTargetY = state.camTargetY,
                    camTargetZ = state.camTargetZ,
                    adaptiveWorkload = workloadText,
                    avgRaysPerPixel = stats.averageRaysPerPixel,
                    maxRaysPerPixel = stats.maximumRaysPerPixel,
                    workloadStats = stats,
                    passTimings = lastPassTimings
                )
            }
        }
        if (shouldUpdateFps) {
            fpsFrames = 0
            fpsAccumulatorTimeNanos = submittedAtNanos
        }
    }

    private fun elapsedMilliseconds(startNanos: Long): Float =
        (System.nanoTime() - startNanos) / 1_000_000.0f

    private fun changedRaySignatureField(
        previous: RaySceneSignature?,
        current: RaySceneSignature
    ): String {
        if (previous == null) return "initial"
        return when {
            previous.width != current.width -> "width"
            previous.height != current.height -> "height"
            previous.renderScale != current.renderScale -> "renderScale"
            previous.mass != current.mass -> "mass"
            previous.spin != current.spin -> "spin"
            previous.camDist != current.camDist -> "camDist"
            previous.camInclinationDeg != current.camInclinationDeg -> "camInclinationDeg"
            previous.camAzimuthDeg != current.camAzimuthDeg -> "camAzimuthDeg"
            previous.camTargetX != current.camTargetX -> "camTargetX"
            previous.camTargetY != current.camTargetY -> "camTargetY"
            previous.camTargetZ != current.camTargetZ -> "camTargetZ"
            previous.maxSteps != current.maxSteps -> "maxSteps"
            previous.enableDisk != current.enableDisk -> "enableDisk"
            previous.diskOuterRadius != current.diskOuterRadius -> "diskOuterRadius"
            previous.enableObject != current.enableObject -> "enableObject"
            previous.objectRadius != current.objectRadius -> "objectRadius"
            previous.objectOrbitRadius != current.objectOrbitRadius -> "objectOrbitRadius"
            previous.objectPhi0 != current.objectPhi0 -> "objectPhi0"
            previous.objectZ != current.objectZ -> "objectZ"
            previous.enableDoppler != current.enableDoppler -> "enableDoppler"
            previous.useGeodesicShader != current.useGeodesicShader -> "useGeodesicShader"
            previous.debugCoarseSamplingBlockSize != current.debugCoarseSamplingBlockSize -> "blockSize"
            previous.enableWorkloadTelemetry != current.enableWorkloadTelemetry -> "telemetry"
            previous.enableAnimation != current.enableAnimation -> "animation"
            else -> "unknown"
        }
    }

    private fun animationProgramFailureStatus(failure: ShaderProgram.CreationFailure?): String {
        val label = failure?.label ?: "UNKNOWN"
        val log = (failure?.log ?: "NO_INFO_LOG").replace(Regex("\\s+"), " ").trim().take(180)
        return "ANIM FAILED: $label ${if (log.isEmpty()) "EMPTY" else log}".take(240)
    }

    /** UI thread, DIAG only: one processed touch event for the manual camera trace. */
    internal fun noteDiagnosticInput(event: GargantuaLensAnalysis.InputEvent) {
        val userState = stateHolder.getState()
        if (userState.diagnosticView != 0) gpuDiagnostics.noteInput(event)
    }

    private fun drainAnimationGlErrors() {
        var drained = 0
        while (drained < 8 && GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            drained++
        }
    }

    private fun failAnimation(label: String) {
        if (animationFailureLatched) return
        animationFailureLatched = true
        animationFailureStatus = if (label.startsWith("ANIM FAILED:")) label else "ANIM FAILED: $label"
        animationResourcesReady = false
        animationCacheValid = false
        val userState = stateHolder.getState()
        if (userState.diagnosticView != 0) gpuDiagnostics.noteAnimationFailure(animationFailureStatus ?: label, System.nanoTime())
        stateHolder.updateState { it.copy(enableAnimation = false, animationAmplitudePercent = 0) }
    }

    private fun ensureAnimationPrograms(): Boolean {
        if (animationFailureLatched) return false
        if (!isHdrSupported) {
            failAnimation("ANIM FAILED: HDR16F REQUIRED")
            return false
        }
        if (animationGeodesicProgram != null && animationMaterialProgram != null) {
            return true
        }
        if (animationProgramAttempted) return false
        animationProgramAttempted = true
        var failure: ShaderProgram.CreationFailure? = null
        return try {
            val vertex = ShaderSource.loadVertexShader(context)
            fun create(fragment: String): ShaderProgram? = ShaderProgram.create(vertex, fragment) { failure = it }
            drainAnimationGlErrors()
            val buildSource = GargantuaGpuDiagnostics.applyRecordPrecision(ShaderSource.loadAnimationGeodesicFragmentShader(context), animationRecordPrecision)
            val materialSource = GargantuaGpuDiagnostics.applyRecordPrecision(ShaderSource.loadAnimationMaterialFragmentShader(context), animationRecordPrecision)
            val geodesic = create(buildSource)
            val material = if (geodesic != null) {
                create(materialSource)
            } else {
                null
            }
            val programError = GLES30.glGetError()
            if (diagActiveThisFrame) {
                gpuDiagnostics.noteAnimationPrograms(
                    animationRecordPrecision, buildSource, materialSource, geodesic, material,
                    failure?.let { "${it.label}: ${it.log.replace(Regex("\\s+"), " ").take(300)}" }
                        ?: if (programError != GLES30.GL_NO_ERROR) "GLERR=0x${programError.toString(16)}" else null
                )
            }
            if (geodesic == null || material == null || programError != GLES30.GL_NO_ERROR) {
                geodesic?.release()
                material?.release()
                val status = if (programError != GLES30.GL_NO_ERROR) {
                    "ANIM FAILED: PROGRAM GLERR=0x${programError.toString(16)}"
                } else {
                    animationProgramFailureStatus(failure)
                }
                failAnimation(status)
                false
            } else {
                animationGeodesicProgram = geodesic
                animationMaterialProgram = material
                animationFailureStatus = null
                true
            }
        } catch (e: Exception) {
            failAnimation("ANIM FAILED: INIT ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private fun ensureAnimationFbos(
        rayWidth: Int,
        rayHeight: Int,
        rayTextureId: Int,
        renderWidth: Int,
        renderHeight: Int
    ): AnimationResourceResult {
        if (animationFailureLatched) return AnimationResourceResult.FAILED
        if (!isHdrSupported || rayTextureId == 0 || rayWidth <= 0 || rayHeight <= 0 || renderWidth <= 0 || renderHeight <= 0) {
            return AnimationResourceResult.NOT_READY
        }
        if (animationResourcesReady) {
            if (
                animationRayWidth != rayWidth || animationRayHeight != rayHeight ||
                animationRayTextureId != rayTextureId ||
                modulatedHdrWidth != renderWidth || modulatedHdrHeight != renderHeight
            ) {
                deleteAnimationFbos()
                return AnimationResourceResult.NOT_READY
            }
            return AnimationResourceResult.READY
        }
        deleteAnimationFbos()
        drainAnimationGlErrors()
        // Each cache pass writes the HDR (pass 0 only) plus three unsigned-integer ray records.
        val limits = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, limits, 0)
        GLES30.glGetIntegerv(GLES30.GL_MAX_COLOR_ATTACHMENTS, limits, 1)
        if (limits[0] < scratchDrawBuffersAnimation.size || limits[1] < scratchDrawBuffersAnimation.size) {
            failAnimation("ANIM FAILED: ${scratchDrawBuffersAnimation.size} MRT REQUIRED (draw=${limits[0]} attach=${limits[1]})")
            return AnimationResourceResult.FAILED
        }
        GLES30.glGenFramebuffers(animationCacheFboIds.size, animationCacheFboIds, 0)
        GLES30.glGenTextures(animationRayRecordTextureIds.size, animationRayRecordTextureIds, 0)
        val skyTextures = IntArray(2)
        GLES30.glGenTextures(2, skyTextures, 0)
        animationSkyDirectionTextureId = skyTextures[0]
        animationSkyOriginalTextureId = skyTextures[1]
        val fbos = IntArray(3)
        val textures = IntArray(3)
        GLES30.glGenFramebuffers(3, fbos, 0)
        GLES30.glGenTextures(3, textures, 0)
        builtEmissionFboId = fbos[0]
        modulatedHdrFboId = fbos[1]
        animatedRayFboId = fbos[2]
        builtEmissionTextureId = textures[0]
        modulatedHdrTextureId = textures[1]
        animatedRayTextureId = textures[2]
        animationRayWidth = rayWidth
        animationRayHeight = rayHeight
        animationRayTextureId = rayTextureId
        modulatedHdrWidth = renderWidth
        modulatedHdrHeight = renderHeight
        builtEmissionValid = false
        animationCacheComplete = false

        fun texture(id: Int, internal: Int, width: Int, height: Int, format: Int, type: Int, filter: Int) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, format, type, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        for (recordTexture in animationRayRecordTextureIds) {
            // Integer textures are only complete with NEAREST filtering.
            texture(recordTexture, GLES30.GL_RGBA32UI, rayWidth, rayHeight, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, GLES30.GL_NEAREST)
        }
        texture(animationSkyDirectionTextureId, GLES30.GL_RGBA16F, rayWidth, rayHeight, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST)
        texture(animationSkyOriginalTextureId, GLES30.GL_RGBA16F, rayWidth, rayHeight, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST)
        texture(builtEmissionTextureId, GLES30.GL_RGBA16F, rayWidth, rayHeight, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST)
        texture(animatedRayTextureId, GLES30.GL_RGBA16F, rayWidth, rayHeight, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_LINEAR)
        texture(modulatedHdrTextureId, GLES30.GL_RGBA16F, renderWidth, renderHeight, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_LINEAR)

        val statuses = IntArray(animationCacheFboIds.size + 3)
        for (pass in animationCacheFboIds.indices) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, animationCacheFboIds[pass])
            if (pass == 0) {
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, rayTextureId, 0)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT4, GLES30.GL_TEXTURE_2D, animationSkyDirectionTextureId, 0)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT5, GLES30.GL_TEXTURE_2D, animationSkyOriginalTextureId, 0)
            }
            for (slot in 0 until AnimationGate.RECORDS_PER_CACHE_PASS) {
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT1 + slot,
                    GLES30.GL_TEXTURE_2D,
                    animationRayRecordTextureIds[pass * AnimationGate.RECORDS_PER_CACHE_PASS + slot],
                    0
                )
            }
            val drawBuffers = if (pass == 0) scratchDrawBuffersAnimation else scratchDrawBuffersAnimationRecords
            GLES30.glDrawBuffers(drawBuffers.size, drawBuffers, 0)
            statuses[pass] = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        }
        val singleTargets = intArrayOf(builtEmissionFboId, modulatedHdrFboId, animatedRayFboId)
        val singleTextures = intArrayOf(builtEmissionTextureId, modulatedHdrTextureId, animatedRayTextureId,
                animationSkyDirectionTextureId, animationSkyOriginalTextureId)
        for (index in singleTargets.indices) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, singleTargets[index])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, singleTextures[index], 0)
            GLES30.glDrawBuffers(1, scratchDrawBuffersSingle, 0)
            statuses[animationCacheFboIds.size + index] = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        }
        val fboError = GLES30.glGetError()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (fboError != GLES30.GL_NO_ERROR || statuses.any { it != GLES30.GL_FRAMEBUFFER_COMPLETE }) {
            failAnimation(
                "ANIM FAILED: FBO gl=0x${fboError.toString(16)} status=" +
                    statuses.joinToString(",") { "0x" + it.toString(16) }
            )
            deleteAnimationFbos()
            return AnimationResourceResult.FAILED
        }
        animationResourcesReady = true
        return AnimationResourceResult.READY
    }

    private fun deleteAnimationFbos() {
        val framebuffers = animationCacheFboIds + intArrayOf(builtEmissionFboId, modulatedHdrFboId, animatedRayFboId)
        if (framebuffers.any { it != 0 }) {
            GLES30.glDeleteFramebuffers(framebuffers.size, framebuffers, 0)
        }
        val animationTextures = animationRayRecordTextureIds +
            intArrayOf(builtEmissionTextureId, modulatedHdrTextureId, animatedRayTextureId,
                animationSkyDirectionTextureId, animationSkyOriginalTextureId)
        if (animationTextures.any { it != 0 }) {
            GLES30.glDeleteTextures(animationTextures.size, animationTextures, 0)
        }
        animationCacheFboIds.fill(0)
        animationRayRecordTextureIds.fill(0)
        animationRayWidth = 0; animationRayHeight = 0; animationRayTextureId = 0
        builtEmissionFboId = 0; builtEmissionTextureId = 0; builtEmissionValid = false
        animationSkyDirectionTextureId = 0; animationSkyOriginalTextureId = 0
        animatedRayFboId = 0; animatedRayTextureId = 0; animationCacheComplete = false
        modulatedHdrFboId = 0; modulatedHdrTextureId = 0; modulatedHdrWidth = 0; modulatedHdrHeight = 0
        animationResourcesReady = false
        animationCacheValid = false
        animationCacheSignature = null
        sceneDirty = true
        presentationInvalidationPending = true
    }

    /**
     * COMPLETE_CACHE: writes ray records 3-8 with cache passes 1 and 2 of the animation build program.
     * The program still holds every uniform of the pass-0 build (program state), so each pass executes
     * the identical main() on the identical rays; only u_AnimationCachePass selects the records.
     */
    private fun completeAnimationCache(quad: QuadGeometry) {
        val geodesic = animationGeodesicProgram ?: return
        drainAnimationGlErrors()
        geodesic.use()
        GLES30.glViewport(0, 0, animationRayWidth, animationRayHeight)
        for (pass in 1 until AnimationGate.CACHE_PASS_COUNT) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, animationCacheFboIds[pass])
            GLES30.glDrawBuffers(scratchDrawBuffersAnimationRecords.size, scratchDrawBuffersAnimationRecords, 0)
            geodesic.setUniform1i("u_AnimationCachePass", pass)
            quad.draw()
            if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("anim cache pass$pass", intArrayOf())
            geodesicPassesThisFrame++
        }
        geodesic.setUniform1i("u_AnimationCachePass", 0)
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            failAnimation("ANIM FAILED: CACHE PASS GLERR=0x${error.toString(16)}")
            return
        }
        animationCacheComplete = true
    }

    private fun runAnimationPass(
        state: GargantuaRenderState,
        renderWidth: Int,
        renderHeight: Int,
        rayWidth: Int,
        rayHeight: Int,
        blockSize: Int,
        quad: QuadGeometry
    ): Boolean {
        val material = animationMaterialProgram ?: return false
        drainAnimationGlErrors()
        material.use()
        // The cached HDR of the build lives on the ray grid (the HDR texture itself when blockSize == 1).
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, animationRayTextureId)
        material.setUniform1i("u_HdrTexture", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE11)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, animationSkyDirectionTextureId)
        material.setUniform1i("u_SkyDirectionTexture", 11)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE12)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, animationSkyOriginalTextureId)
        material.setUniform1i("u_SkyOriginalTexture", 12)
        // A slowly drifting celestial environment makes lensing visible at a stationary camera.
        // The deflection map is fixed: no fictitious change in black-hole mass or spin.
        material.setUniform1f("u_SkyRotation", ((System.nanoTime() - animOriginNanos).coerceAtLeast(0L) * 1.0e-9 * SKY_DRIFT_RADIANS_PER_SECOND).toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, builtEmissionTextureId)
        material.setUniform1i("u_BuiltEmissionTexture", 1)
        for (ray in animationRayRecordTextureIds.indices) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2 + ray)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, animationRayRecordTextureIds[ray])
            material.setUniform1i(RAY_RECORD_UNIFORMS[ray], 2 + ray)
        }
        // Same disk/material uniforms as the animation geodesic build. Every one of them is part of the
        // ray-scene signature or invalidates the cache on change (amplitude), so they equal the build's.
        material.setUniform1f("u_Mass", state.mass)
        material.setUniform1f("u_Spin", state.spin * state.mass)
        material.setUniform1f("u_DiskInnerRadius", lastIscoRadius)
        material.setUniform1f("u_DiskOuterRadius", state.diskOuterRadius)
        material.setUniform1f("u_AnimationAmplitude", state.animationAmplitudePercent / 100.0f)
        material.setUniform1i("u_EnableDoppler", if (state.enableDoppler) 1 else 0)

        fun setTimeDigits(digits: FloatArray) {
            material.setUniform1f("u_Time", digits[0])
            material.setUniform1f("u_TimeDigit1", digits[1])
            material.setUniform2f("u_TimeDigits23", digits[2], digits[3])
            material.setUniform2f("u_TimeDigits45", digits[4], digits[5])
            material.setUniform2f("u_TimeDigits67", digits[6], digits[7])
        }

        if (!builtEmissionValid) {
            // Once per cache build (normally in the COMPLETE_CACHE frame's successor): emission of the
            // cached hits at the build time.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, builtEmissionFboId)
            GLES30.glViewport(0, 0, rayWidth, rayHeight)
            material.setUniform1i("u_MaterialPassMode", 1)
            setTimeDigits(animationBuildTimeDigits)
            quad.draw()
            if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("material built-emission", DIAG_MATERIAL_UNITS)
            builtEmissionValid = true
        }

        // The material pass runs on the ray grid, so every cached ray of every M7 pixel is re-shaded
        // exactly once; coarse sampling modes then take the same upscale path as the geodesic frame.
        val coarse = blockSize > GargantuaCoarseSampling.BASELINE_BLOCK_SIZE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, if (coarse) animatedRayFboId else modulatedHdrFboId)
        GLES30.glViewport(0, 0, rayWidth, rayHeight)
        material.setUniform1i("u_MaterialPassMode", 0)
        setTimeDigits(scratchTimeDigits)
        quad.draw()
        if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("material", DIAG_MATERIAL_UNITS)
        for (unit in 12 downTo 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        if (coarse) {
            upscaleCoarseRayTexture(animatedRayTextureId, renderWidth, renderHeight, quad, modulatedHdrFboId, animatedRayFboId)
            if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("material upscale", intArrayOf(0))
        }
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) {
            failAnimation("ANIM FAILED: PASS GLERR=0x${error.toString(16)}")
            return false
        }
        return true
    }

    private fun renderSemanticCacheDebugToDisplay(
        dstWidth: Int,
        dstHeight: Int,
        quad: QuadGeometry
    ) {
        val program = semanticCacheDebugProgram ?: return
        if (workloadSemanticCacheTextureId == 0) return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, dstWidth, dstHeight)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, workloadSemanticCacheTextureId)
        program.setUniform1i("u_Texture", 0)
        quad.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun renderCompositeToDisplay(
        dstWidth: Int,
        dstHeight: Int,
        state: GargantuaRenderState,
        quad: QuadGeometry,
        activeHdrTextureId: Int,
        activeHdrFboId: Int
    ) {
        val composite = compositeProgram
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, dstWidth, dstHeight)

        if (composite != null) {
            composite.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, activeHdrTextureId)
            composite.setUniform1i("u_HdrTexture", 0)

            val bloomReady =
                state.enableBloom &&
                    brightPassProgram != null &&
                    blurProgram != null &&
                    bloomFboA != 0 &&
                    bloomTexA != 0
            if (bloomReady) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTexA)
                composite.setUniform1i("u_BloomTexture", 1)
                composite.setUniform1i("u_EnableBloom", 1)
            } else {
                composite.setUniform1i("u_EnableBloom", 0)
            }

            composite.setUniform1f("u_Exposure", state.exposure)
            composite.setUniform1f("u_BloomIntensity", state.bloomIntensity)
            composite.setUniform2f(
                "u_TexelSize",
                1.0f / max(1, hdrWidth).toFloat(),
                1.0f / max(1, hdrHeight).toFloat()
            )
            quad.draw()
            if (diagActiveThisFrame) gpuDiagnostics.passSnapshot("composite", intArrayOf(0, 1))

            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        } else {
            // Direct blit fallback if composite shader unavailable
            val blit = blitProgram
            if (blit != null) {
                blit.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, activeHdrTextureId)
                blit.setUniform1i("u_Texture", 0)
                quad.draw()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, activeHdrFboId)
                GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
                GLES30.glBlitFramebuffer(
                    0, 0, hdrWidth, hdrHeight,
                    0, 0, dstWidth, dstHeight,
                    GLES30.GL_COLOR_BUFFER_BIT,
                    GLES30.GL_LINEAR
                )
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            }
        }
    }

    /**
     * Upscales the coarse ray texture into the unchanged internal HDR target. This is a diagnostic
     * texture-filtering pass only; it does not alter the ray shader or any physical calculation.
     */
    private fun upscaleCoarseRayTexture(
        rayTextureId: Int,
        targetWidth: Int,
        targetHeight: Int,
        quad: QuadGeometry,
        targetFboId: Int = hdrFboId,
        sourceFboId: Int = coarseRayFboId
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFboId)
        GLES30.glViewport(0, 0, targetWidth, targetHeight)
        val blit = blitProgram
        if (blit != null) {
            blit.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rayTextureId)
            blit.setUniform1i("u_Texture", 0)
            quad.draw()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        } else {
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sourceFboId)
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, targetFboId)
            GLES30.glBlitFramebuffer(
                0, 0, coarseRayWidth, coarseRayHeight,
                0, 0, targetWidth, targetHeight,
                GLES30.GL_COLOR_BUFFER_BIT,
                GLES30.GL_LINEAR
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFboId)
        }
    }

    /**
     * Allocates the temporary coarse ray target. The full internal HDR target is intentionally
     * retained so the existing bloom and composite pipeline receives the same-sized input.
     */
    private fun ensureCoarseRayFbo(targetWidth: Int, targetHeight: Int): Boolean {
        if (
            coarseRayTargetAvailable &&
            coarseRayWidth == targetWidth &&
            coarseRayHeight == targetHeight &&
            coarseRayFboId != 0 &&
            coarseRayTextureId != 0
        ) {
            return true
        }

        deleteCoarseRayFbo()
        val fboIds = IntArray(1)
        val textureIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        GLES30.glGenTextures(1, textureIds, 0)
        coarseRayFboId = fboIds[0]
        coarseRayTextureId = textureIds[0]
        coarseRayWidth = targetWidth
        coarseRayHeight = targetHeight

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coarseRayTextureId)
        val internalFormat = if (isHdrSupported) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
        val formatType = if (isHdrSupported) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            internalFormat,
            coarseRayWidth,
            coarseRayHeight,
            0,
            GLES30.GL_RGBA,
            formatType,
            null
        )
        // Linear filtering is the diagnostic upscale requested by the experiment.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coarseRayFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            coarseRayTextureId,
            0
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Coarse diagnostic framebuffer unavailable: status=$status")
            deleteCoarseRayFbo()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return false
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        coarseRayTargetAvailable = true
        return true
    }

    private fun deleteCoarseRayFbo() {
        if (coarseRayFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(coarseRayFboId), 0)
        }
        if (coarseRayTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(coarseRayTextureId), 0)
        }
        coarseRayFboId = 0
        coarseRayTextureId = 0
        coarseRayWidth = 0
        coarseRayHeight = 0
        coarseRayTargetAvailable = false
    }

    /**
     * Allocates the debug-only MRT and reduction attachments. RGBA32F is intentionally required
     * here: counters are summed over the internal frame and must not silently overflow/quantize
     * in a half-float debug buffer. If the device cannot render to RGBA32F, telemetry reports
     * unavailable instead of guessing.
     */
    private fun ensureWorkloadFbo(targetWidth: Int, targetHeight: Int, rayTextureId: Int): Boolean {
        if (workloadGeodesicProgram == null || reduceProgram == null) return false
        if (rayTextureId == 0) {
            workloadDiagnosticStatus = "TEL ON · MRT/FBO NOT READY"
            return false
        }
        if (
            workloadTelemetrySupported &&
            workloadWidth == targetWidth &&
            workloadHeight == targetHeight &&
            workloadRayTextureId == rayTextureId &&
            workloadFboId != 0
        ) {
            return true
        }

        deleteWorkloadFbos()
        semanticCacheDiagnosticStatus = null
        workloadDiagnosticStatus = "TEL ON · MRT/FBO ALLOCATING"

        val maxDrawBuffers = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, maxDrawBuffers, 0)
        val semanticCacheFboRequested =
            ENABLE_SEMANTIC_CACHE_DEBUG && maxDrawBuffers[0] >= 4
        if (ENABLE_SEMANTIC_CACHE_DEBUG && maxDrawBuffers[0] < 4) {
            semanticCacheDiagnosticStatus = "SEM MAXDRAW=${maxDrawBuffers[0]}"
        }

        val fboIds = IntArray(3)
        val textureIds = IntArray(if (semanticCacheFboRequested) 5 else 4)
        GLES30.glGenFramebuffers(3, fboIds, 0)
        GLES30.glGenTextures(textureIds.size, textureIds, 0)

        workloadFboId = fboIds[0]
        workloadReduceFboA = fboIds[1]
        workloadReduceFboB = fboIds[2]
        workloadTierTextureId = textureIds[0]
        workloadCostTextureId = textureIds[1]
        var textureIndex = 2
        workloadSemanticCacheTextureId =
            if (semanticCacheFboRequested) textureIds[textureIndex++] else 0
        workloadReduceTextureA = textureIds[textureIndex++]
        workloadReduceTextureB = textureIds[textureIndex]
        workloadWidth = targetWidth
        workloadHeight = targetHeight
        workloadRayTextureId = rayTextureId

        fun initFloatTexture(
            textureId: Int,
            internalFormat: Int = GLES30.GL_RGBA32F
        ) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                internalFormat,
                targetWidth,
                targetHeight,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_FLOAT,
                null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }

        initFloatTexture(workloadTierTextureId)
        initFloatTexture(workloadCostTextureId)
        if (semanticCacheFboRequested) {
            initFloatTexture(workloadSemanticCacheTextureId, semanticCacheInternalFormat())
        }
        initFloatTexture(workloadReduceTextureA)
        initFloatTexture(workloadReduceTextureB)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, workloadFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            rayTextureId,
            0
        )
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT1,
            GLES30.GL_TEXTURE_2D,
            workloadTierTextureId,
            0
        )
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT2,
            GLES30.GL_TEXTURE_2D,
            workloadCostTextureId,
            0
        )
        var semanticCacheAttached = false
        if (semanticCacheFboRequested) {
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT3,
                GLES30.GL_TEXTURE_2D,
                workloadSemanticCacheTextureId,
                0
            )
            val semanticStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (semanticStatus == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                semanticCacheAttached = true
            } else {
                semanticCacheDiagnosticStatus =
                    "SEM FBO INCOMPLETE 0x${semanticStatus.toString(16).uppercase()}"
                GLES30.glFramebufferTexture2D(
                    GLES30.GL_FRAMEBUFFER,
                    GLES30.GL_COLOR_ATTACHMENT3,
                    GLES30.GL_TEXTURE_2D,
                    0,
                    0
                )
                GLES30.glDeleteTextures(1, intArrayOf(workloadSemanticCacheTextureId), 0)
                workloadSemanticCacheTextureId = 0
            }
        }
        val workloadDrawBuffers = if (semanticCacheAttached) {
            intArrayOf(
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_COLOR_ATTACHMENT2,
                GLES30.GL_COLOR_ATTACHMENT3
            )
        } else {
            intArrayOf(
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_COLOR_ATTACHMENT2
            )
        }
        GLES30.glDrawBuffers(workloadDrawBuffers.size, workloadDrawBuffers, 0)
        var workloadStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (workloadStatus != GLES30.GL_FRAMEBUFFER_COMPLETE && semanticCacheAttached) {
            semanticCacheDiagnosticStatus =
                "SEM FBO INCOMPLETE 0x${workloadStatus.toString(16).uppercase()}"
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT3,
                GLES30.GL_TEXTURE_2D,
                0,
                0
            )
            GLES30.glDeleteTextures(1, intArrayOf(workloadSemanticCacheTextureId), 0)
            workloadSemanticCacheTextureId = 0
            semanticCacheAttached = false
            val telemetryDrawBuffers = intArrayOf(
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_COLOR_ATTACHMENT2
            )
            GLES30.glDrawBuffers(telemetryDrawBuffers.size, telemetryDrawBuffers, 0)
            workloadStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        }
        if (workloadStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "RGBA32F workload framebuffer unavailable: status=$workloadStatus")
            deleteWorkloadFbos()
            workloadDiagnosticStatus = "TEL ON · MRT/FBO NOT READY"
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return false
        }

        fun attachReductionFbo(fboId: Int, textureId: Int): Boolean {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                textureId,
                0
            )
            GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0), 0)
            return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        }

        val reductionAComplete = attachReductionFbo(workloadReduceFboA, workloadReduceTextureA)
        val reductionBComplete = attachReductionFbo(workloadReduceFboB, workloadReduceTextureB)
        if (!reductionAComplete || !reductionBComplete) {
            Log.w(TAG, "RGBA32F workload reduction framebuffer unavailable")
            deleteWorkloadFbos()
            workloadDiagnosticStatus = "TEL ON · REDUCTION FBO NOT READY"
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            return false
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        workloadTelemetrySupported = true
        Log.i(TAG, "Debug workload telemetry enabled for ${targetWidth}x${targetHeight} internal pixels")
        workloadDiagnosticStatus = workloadStatusWithSemantic("TEL ON · MRT/FBO READY")
        return true
    }

    private fun samplingSummary(grid: GargantuaCoarseSampling.Grid): GargantuaWorkloadStats =
        GargantuaWorkloadStats(
            available = false,
            internalWidth = grid.internalWidth,
            internalHeight = grid.internalHeight,
            frameWidth = grid.rayWidth,
            frameHeight = grid.rayHeight,
            samplingBlockSize = grid.blockSize,
            shadedBlocks = grid.shadedBlocks,
            primaryRayCalculations = grid.shadedBlocks,
            primaryRayReductionVsBaseline = grid.primaryRayReductionVsBaseline
        )

    private fun collectWorkloadStats(
        internalWidth: Int,
        internalHeight: Int,
        grid: GargantuaCoarseSampling.Grid
    ): GargantuaWorkloadStats {
        if (!workloadTelemetrySupported || reduceProgram == null || quadGeometry == null) {
            return samplingSummary(grid)
        }
        workloadReadbackValid = true
        workloadReadbackFailureStatus = null

        val width = grid.rayWidth
        val height = grid.rayHeight

        val tierTotals = reduceWorkloadTexture(
            workloadTierTextureId,
            width,
            height,
            maxChannel = -1,
            metricName = "TIER"
        )
        val costTotals = reduceWorkloadTexture(
            workloadCostTextureId,
            width,
            height,
            maxChannel = 2,
            metricName = "COST"
        )
        if (!workloadReadbackValid) {
            val tierSum = tierTotals[0] + tierTotals[1] + tierTotals[2]
            workloadReadbackFailureStatus =
                "${workloadReadbackFailureStatus ?: "CHECK=UNKNOWN"}" +
                    " SUM_T012=$tierSum EXP_PRIMARY=${grid.shadedBlocks}"
            return samplingSummary(grid)
        }

        val tier0 = tierTotals[0].roundToLong().coerceAtLeast(0L)
        val tier1 = tierTotals[1].roundToLong().coerceAtLeast(0L)
        val tier2 = tierTotals[2].roundToLong().coerceAtLeast(0L)
        val difficult = tierTotals[3].roundToLong().coerceAtLeast(0L)
        val totalPixels = tier0 + tier1 + tier2
        val totalRays = costTotals[0].roundToLong().coerceAtLeast(0L)
        val totalSteps = costTotals[1].roundToLong().coerceAtLeast(0L)
        val maximumSteps = costTotals[2].roundToInt().coerceAtLeast(0)
        val diskIntersections = costTotals[3].roundToLong().coerceAtLeast(0L)
        val averageRays = if (totalPixels > 0L) totalRays.toFloat() / totalPixels.toFloat() else 0f
        val averageSteps = if (totalRays > 0L) totalSteps.toFloat() / totalRays.toFloat() else 0f
        val maximumRays = when {
            tier2 > 0L -> 9
            tier1 > 0L -> 5
            tier0 > 0L -> 1
            else -> 0
        }

        return GargantuaWorkloadStats(
            available = true,
            internalWidth = internalWidth,
            internalHeight = internalHeight,
            frameWidth = width,
            frameHeight = height,
            samplingBlockSize = grid.blockSize,
            shadedBlocks = grid.shadedBlocks,
            primaryRayCalculations = grid.shadedBlocks,
            primaryRayReductionVsBaseline = grid.primaryRayReductionVsBaseline,
            tier0Pixels = tier0,
            tier1Pixels = tier1,
            tier2Pixels = tier2,
            difficultRayCount = difficult,
            totalRaysFrame = totalRays,
            averageRaysPerPixel = averageRays,
            maximumRaysPerPixel = maximumRays,
            totalIntegrationSteps = totalSteps,
            averageIntegrationSteps = averageSteps,
            maximumIntegrationSteps = maximumSteps,
            diskIntersections = diskIntersections
        )
    }

    /** One 1x1 float readback per metric group, only in the explicit debug instrumentation path. */
    private fun reduceWorkloadTexture(
        inputTextureId: Int,
        inputWidth: Int,
        inputHeight: Int,
        maxChannel: Int,
        metricName: String
    ): FloatArray {
        var sourceTexture = inputTextureId
        var sourceWidth = inputWidth
        var sourceHeight = inputHeight
        var ping = 0
        var lastOutputFbo = workloadReduceFboA

        if (sourceWidth == 1 && sourceHeight == 1) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, workloadReduceFboA)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                sourceTexture,
                0
            )
        } else {
            while (sourceWidth > 1 || sourceHeight > 1) {
                val targetWidth = max(1, (sourceWidth + 1) / 2)
                val targetHeight = max(1, (sourceHeight + 1) / 2)
                val targetFbo = if (ping == 0) workloadReduceFboA else workloadReduceFboB
                val targetTexture = if (ping == 0) workloadReduceTextureA else workloadReduceTextureB
                lastOutputFbo = targetFbo

                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
                GLES30.glViewport(0, 0, targetWidth, targetHeight)
                reduceProgram?.use()
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTexture)
                reduceProgram?.setUniform1i("u_Texture", 0)
                reduceProgram?.setUniform2f("u_SourceSize", sourceWidth.toFloat(), sourceHeight.toFloat())
                reduceProgram?.setUniform1i("u_MaxChannel", maxChannel)
                quadGeometry?.draw()

                sourceTexture = targetTexture
                sourceWidth = targetWidth
                sourceHeight = targetHeight
                ping = 1 - ping
            }
        }

        val values = ByteBuffer.allocateDirect(4 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lastOutputFbo)
        GLES30.glViewport(0, 0, 1, 1)
        GLES30.glReadPixels(0, 0, 1, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, values)
        val readError = GLES30.glGetError()
        values.position(0)
        return FloatArray(4).also { rawValues ->
            values.get(rawValues)
            val nonFinite = rawValues.any { value -> !value.isFinite() }
            if (readError != GLES30.GL_NO_ERROR || nonFinite) {
                workloadReadbackValid = false
                if (workloadReadbackFailureStatus == null) {
                    val check = if (readError != GLES30.GL_NO_ERROR) "GL_ERROR" else "NONFINITE"
                    val errorHex = "0x${readError.toString(16)}"
                    val raw = rawValues.joinToString(",") { value -> value.toString() }
                    workloadReadbackFailureStatus =
                        "$metricName CHECK=$check GLERR=$errorHex READ=RGBA/FLOAT/1x1 RAW=[$raw]"
                }
            }
        }
    }

    private fun resetGpuResourceHandlesForNewContext() {
        hdrFboId = 0
        hdrTextureId = 0
        hdrWidth = 0
        hdrHeight = 0
        isHdrSupported = false
        bloomFboA = 0
        bloomTexA = 0
        bloomFboB = 0
        bloomTexB = 0
        bloomWidth = 0
        bloomHeight = 0
        coarseRayFboId = 0
        coarseRayTextureId = 0
        coarseRayWidth = 0
        coarseRayHeight = 0
        coarseRayTargetAvailable = false
        animationCacheFboIds.fill(0)
        animationRayRecordTextureIds.fill(0)
        animationSkyDirectionTextureId = 0
        animationSkyOriginalTextureId = 0
        animationCacheComplete = false
        animatedRayFboId = 0
        animatedRayTextureId = 0
        animationRayWidth = 0
        animationRayHeight = 0
        animationRayTextureId = 0
        builtEmissionFboId = 0
        builtEmissionTextureId = 0
        builtEmissionValid = false
        modulatedHdrFboId = 0
        modulatedHdrTextureId = 0
        modulatedHdrWidth = 0
        modulatedHdrHeight = 0
        animationResourcesReady = false
        animationFailureLatched = false
        animationCacheValid = false
        animationCacheSignature = null
        lastRaySignatureChangeNanos = 0L
        lastPresentedModulated = false
        workloadFboId = 0
        workloadTierTextureId = 0
        workloadCostTextureId = 0
        workloadSemanticCacheTextureId = 0
        workloadReduceFboA = 0
        workloadReduceTextureA = 0
        workloadReduceFboB = 0
        workloadReduceTextureB = 0
        workloadWidth = 0
        workloadHeight = 0
        workloadRayTextureId = 0
        workloadTelemetrySupported = false
        workloadProgramAttempted = false
        workloadProgramFailureStatus = null
        workloadDiagnosticStatus = "TEL OFF"
        workloadReadbackValid = true
        workloadReadbackFailureStatus = null
        semanticCacheDiagnosticStatus = null
    }

    private fun deleteWorkloadFbos() {
        if (workloadFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(workloadFboId), 0)
        }
        if (workloadReduceFboA != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(workloadReduceFboA), 0)
        }
        if (workloadReduceFboB != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(workloadReduceFboB), 0)
        }
        if (workloadTierTextureId != 0 || workloadCostTextureId != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(workloadTierTextureId, workloadCostTextureId), 0)
        }
        if (workloadSemanticCacheTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(workloadSemanticCacheTextureId), 0)
        }
        if (workloadReduceTextureA != 0 || workloadReduceTextureB != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(workloadReduceTextureA, workloadReduceTextureB), 0)
        }
        workloadFboId = 0
        workloadTierTextureId = 0
        workloadCostTextureId = 0
        workloadSemanticCacheTextureId = 0
        workloadReduceFboA = 0
        workloadReduceTextureA = 0
        workloadReduceFboB = 0
        workloadReduceTextureB = 0
        workloadWidth = 0
        workloadHeight = 0
        workloadRayTextureId = 0
        workloadTelemetrySupported = false
    }

    private fun updateHdrFbo(targetWidth: Int, targetHeight: Int) {
        if (hdrWidth == targetWidth && hdrHeight == targetHeight && hdrFboId != 0) {
            return
        }
        // Workload and animation attachment 0 alias the HDR texture, so both temporary FBO families
        // must be released before the HDR attachment is resized or replaced.
        deleteWorkloadFbos()
        deleteAnimationFbos()
        deleteHdrFbo()

        val fbos = IntArray(1)
        val texs = IntArray(1)
        GLES30.glGenFramebuffers(1, fbos, 0)
        GLES30.glGenTextures(1, texs, 0)

        hdrFboId = fbos[0]
        hdrTextureId = texs[0]
        hdrWidth = targetWidth
        hdrHeight = targetHeight

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, hdrTextureId)
        // 1. Attempt GLES 3.0 floating-point HDR format (GL_RGBA16F)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F,
            hdrWidth, hdrHeight, 0,
            GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, hdrFboId)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, hdrTextureId, 0
        )

        var status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            isHdrSupported = true
            Log.i(TAG, "Gargantua HDR GL_RGBA16F framebuffer initialized successfully (${hdrWidth}x${hdrHeight})")
        } else {
            // Graceful fallback to standard RGBA8 if floating-point render targets unsupported
            Log.w(TAG, "GL_RGBA16F not complete (status=$status); falling back to standard GL_RGBA8")
            isHdrSupported = false
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8,
                hdrWidth, hdrHeight, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
            )
            status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "Fallback RGBA8 framebuffer incomplete: status=$status")
            }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun updateBloomFbos(targetWidth: Int, targetHeight: Int) {
        if (bloomWidth == targetWidth && bloomHeight == targetHeight && bloomFboA != 0) {
            return
        }
        deleteBloomFbos()

        bloomWidth = targetWidth
        bloomHeight = targetHeight

        val fbos = IntArray(2)
        val texs = IntArray(2)
        GLES30.glGenFramebuffers(2, fbos, 0)
        GLES30.glGenTextures(2, texs, 0)

        bloomFboA = fbos[0]
        bloomTexA = texs[0]
        bloomFboB = fbos[1]
        bloomTexB = texs[1]

        fun initBloomAttachment(fbo: Int, tex: Int) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            val internalFormat = if (isHdrSupported) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
            val formatType = if (isHdrSupported) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, internalFormat,
                bloomWidth, bloomHeight, 0,
                GLES30.GL_RGBA, formatType, null
            )
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, tex, 0
            )
        }

        initBloomAttachment(bloomFboA, bloomTexA)
        initBloomAttachment(bloomFboB, bloomTexB)

        val bloomAStatus = run {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboA)
            GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        }
        val bloomBStatus = run {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFboB)
            GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        }
        if (bloomAStatus != GLES30.GL_FRAMEBUFFER_COMPLETE || bloomBStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "Bloom framebuffer unavailable: A=$bloomAStatus, B=$bloomBStatus")
            deleteBloomFbos()
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun deleteHdrFbo() {
        if (hdrFboId != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(hdrFboId), 0)
            hdrFboId = 0
        }
        if (hdrTextureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(hdrTextureId), 0)
            hdrTextureId = 0
        }
        hdrWidth = 0
        hdrHeight = 0
    }

    private fun deleteBloomFbos() {
        if (bloomFboA != 0 || bloomFboB != 0) {
            GLES30.glDeleteFramebuffers(2, intArrayOf(bloomFboA, bloomFboB), 0)
            bloomFboA = 0
            bloomFboB = 0
        }
        if (bloomTexA != 0 || bloomTexB != 0) {
            GLES30.glDeleteTextures(2, intArrayOf(bloomTexA, bloomTexB), 0)
            bloomTexA = 0
            bloomTexB = 0
        }
        bloomWidth = 0
        bloomHeight = 0
    }

    /**
     * Installs the surface callback used to request a dirty frame after GLES resources and the
     * non-empty surface size are both ready. The listener is invoked from the GL thread; the view
     * queues the actual request behind the lifecycle callback before asking for a dirty frame.
     */
    fun setRenderReadyListener(listener: (() -> Unit)?) {
        renderReadyListener = listener
    }

    /** Marks the cached image for presentation; the owning GLSurfaceView schedules the dirty frame. */
    fun requestPresentation() {
        presentationInvalidationPending = true
    }

    fun onResume() {
        val now = System.nanoTime()
        lastRaySignatureChangeNanos = now
        lastRaySignatureField = "resume"
        animationCacheValid = false
        animationCacheSignature = null
        sceneDirty = true
        presentationInvalidationPending = true
    }

    fun release() {
        gpuDiagnostics.release()
        deleteWorkloadFbos()
        deleteAnimationFbos()
        deleteCoarseRayFbo()
        deleteHdrFbo()
        deleteBloomFbos()
        geodesicProgram?.release()
        geodesicProgram = null
        workloadGeodesicProgram?.release()
        workloadGeodesicProgram = null
        animationGeodesicProgram?.release()
        animationGeodesicProgram = null
        animationMaterialProgram?.release()
        animationMaterialProgram = null
        animationProgramAttempted = false
        animationResourcesReady = false
        animationFailureStatus = null
        animationFailureLatched = false
        animationCacheValid = false
        animationCacheSignature = null
        lastPresentedModulated = false
        lastAnimationRequested = false
        lastAnimationAmplitudePercent = -1
        testProgram?.release()
        testProgram = null
        blitProgram?.release()
        blitProgram = null
        semanticCacheDebugProgram?.release()
        semanticCacheDebugProgram = null
        brightPassProgram?.release()
        brightPassProgram = null
        blurProgram?.release()
        blurProgram = null
        compositeProgram?.release()
        compositeProgram = null
        reduceProgram?.release()
        reduceProgram = null
        workloadProgramAttempted = false
        workloadProgramFailureStatus = null
        workloadDiagnosticStatus = "TEL OFF"
        workloadReadbackValid = true
        workloadReadbackFailureStatus = null
        semanticCacheDiagnosticStatus = null
        quadGeometry?.release()
        quadGeometry = null
    }

    companion object {
        private const val ENABLE_SEMANTIC_CACHE_DEBUG = true
        private const val SEMANTIC_CACHE_FORMAT = "16F"
        private const val TAG = "GargantuaRenderer"
        private const val FPS_WINDOW_NANOS = 500_000_000L
        private const val FPS_IDLE_RESET_NANOS = 750_000_000L
        private const val TELEMETRY_DISPATCH_INTERVAL_MS = 250L // 4 Hz throttle
        // Material-pass samplers of the nine M7 ray records (0 base, 1-4 corners, 5-8 edges).
        private const val SKY_DRIFT_RADIANS_PER_SECOND = 0.012
        private val DIAG_MATERIAL_UNITS = IntArray(13) { it }
        private val RAY_RECORD_UNIFORMS = Array(AnimationGate.CACHE_PASS_COUNT * AnimationGate.RECORDS_PER_CACHE_PASS) {
            "u_RayRecord$it"
        }
    }
}
