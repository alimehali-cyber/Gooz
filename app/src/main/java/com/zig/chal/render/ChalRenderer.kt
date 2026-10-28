package com.zig.chal.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.zig.chal.config.ChalFeatureToggles
import com.zig.chal.config.ChalRuntimeState
import com.zig.chal.config.ChalMotion
import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalFeatures
import com.zig.chal.config.ChalPresetName
import com.zig.chal.config.ChalPerformanceConfig
import com.zig.chal.config.ChalRayTracingQuality
import com.zig.chal.config.ChalRendererBackend
import com.zig.chal.config.ChalSimulationParams
import com.zig.chal.physics.ChalObserverReadouts
import com.zig.chal.physics.ChalKerrMetric
import com.zig.chal.shader.ChalGlShaders
import com.zig.chal.shader.ChalShaderManager
import com.zig.chal.shader.ChalShaderSource
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.roundToInt
import java.util.concurrent.atomic.AtomicBoolean

/** Longest the GL thread may sleep to pace one frame (never more than one 15 FPS frame). */
private const val MAX_PACING_SLEEP_MS = 40L

/**
 * Chal's GLES compatibility renderer.
 *
 * This shader-based renderer is retained only for devices that cannot load the XAPK's ARM64/Vulkan
 * renderer. It is an approximate visual fallback, not pixel-identical to the native backend. The
 * Lab screen prefers [XapkChalRenderer] whenever Vulkan 1.1 and arm64-v8a are available.
 */
class ChalRenderer(initialParams: ChalSimulationParams = ChalSimulationParams.MOBILE_PARAMS, initialRuntime: ChalRuntimeState? = null) : GLSurfaceView.Renderer, ChalRendererBackend {

    /** Live runtime snapshot consumed by the Compose HUD (all fields written on the GL thread). */
    data class ChalSnapshot(
        val params: ChalSimulationParams,
        val currentFps: Int,
        val frameTimeMs: Double,
        val quality: ChalRayTracingQuality,
        val budgetUsage: Double,
        val eventHorizonRadius: Double,
        val photonSphereRadius: Double,
        val iscoRadius: Double,
        val timeDilation: Double,
        val redshift: Double,
        val isCinematic: Boolean,
        val cinematicMode: ChalCamera.CinematicMode?,
        val targetFps: Int,
        val benchmarkState: ChalBenchmark.State = ChalBenchmark.State.IDLE,
        val benchmarkPreset: ChalPresetName? = null,
        val benchmarkProgress: Double = 0.0,
        val benchmarkResults: List<ChalBenchmark.BenchmarkResult> = emptyList(),
        val benchmarkRecommendation: ChalPresetName? = null,
        val actualRenderScale: Double = params.renderScale,
        val benchmarkRenderScale: Double? = null,
        val lastWorkingFeatures: ChalFeatureToggles? = null,
        val postProcessingAvailable: Boolean = true,
        val bloomThresholdMax: Double = 4.0,
        val isReady: Boolean = true
    )

    private val shaderManager = ChalShaderManager()
    private val benchmark = ChalBenchmark()
    private val camera = ChalCamera()
    private val frameClock = ChalFrameClock()
    private val paramsLock = Any()
    private val configurationDirty = AtomicBoolean(true)
    private val cadenceDirty = AtomicBoolean(true)
    private var appliedParams: ChalSimulationParams? = null
    private var actualRenderScale = 1.0

    /**
     * The last fully drawn frame, kept as a texture so a callback that is NOT due can show it again.
     * GLSurfaceView swaps buffers after every callback; swapping without drawing would present a
     * stale buffer from two or three frames ago, which shimmers on 90/120 Hz panels and while paused.
     */
    private var presentedTexture = 0
    private var presentedScale = 1.0
    private val monitor = ChalPerformanceMonitor()

    // Multi-threaded hand-off: the UI thread writes params, the GL thread renders them.
    @Volatile
    override var params: ChalSimulationParams = ChalRenderPolicy.normalize(initialParams, native = false)
        private set

    @Volatile
    private var surfaceWidth = 1
    @Volatile
    private var surfaceHeight = 1

    // GL resources (GL thread only)
    private var quadBuffer = 0
    private var noiseTexture = 0
    private var blueNoiseTexture = 0
    private var bloom: ChalBloom? = null
    private var reprojection: ChalReprojection? = null

    private var program = 0
    private var fatalInitializationFailure = false
    private var graphicsCapabilitiesKnown = false
    private var compiledFeatures: ChalFeatureToggles? = null
    private var compiledHasPost = false
    private var failedFeatures: ChalFeatureToggles? = null
    private var failedHasPost = false
    private val uniformLocations = HashMap<String, Int>()

    private var time = initialRuntime?.shaderTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    private var lastMetricsUpdate = 0.0
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var cameraMoving = false
    private var cameraMoveTimeout = 0.0

    /**
     * Heavy EMA smoothing: 0.93/0.07, so a single spike needs ~15 consecutive bad frames to move
     * the average. This is what the PID resolution scaler reacts to (`smoothedDeltaTime`).
     */
    private var smoothedDeltaTime = 16.67

    /** `targetFrameTime`: the frame-budget gate value, lowered to the idle throttle when idle. */
    private var targetFrameTime = ChalPerformanceConfig.Scheduler.FRAME_BUDGET_MS

    /** `IdleDetector(PERFORMANCE_CONFIG.scheduler.idleTimeoutMs)`; stamped on every activity. */
    private var lastActivityTime = -1.0

    /** Set by UI-side parameter pushes so a paused-but-interactive frame still renders. */
    private val paramsDirty = AtomicBoolean(true)

    /**
     * Display refresh rate in Hz, supplied by the surface view. Drives both the adaptive target and
     * the frame gate so a 90/120 Hz panel is paced instead of being chased.
     */
    @Volatile
    private var displayRefreshRateHz = 60.0

    /** True when EXT_color_buffer_float is available (`hasFloatFramebuffer`). */
    var hdrCapable = false
        private set

    /** Set when the GL objects could not be built; the UI surfaces this instead of a blank screen. */
    @Volatile
    override var errorMessage: String? = null
        private set

    private val shadowCurve = FloatArray(128) // 64 points * 2
    private var shadowCurveMass = Double.NaN

    @Volatile
    private var lastBenchmarkReport: ChalBenchmark.BenchmarkReport? = initialRuntime?.benchmarkReport
    @Volatile private var runtimeSnapshot = ChalRuntimeState(params)
    @Volatile private var cinematicOriginalAutoSpin: Double? = null

    @Volatile
    private var lastSnapshot = ChalSnapshot(
        params = params,
        currentFps = 0,
        frameTimeMs = 0.0,
        quality = params.features.rayTracingQuality,
        budgetUsage = 0.0,
        eventHorizonRadius = 0.0,
        photonSphereRadius = 0.0,
        iscoRadius = 0.0,
        timeDilation = 0.0,
        redshift = 0.0,
        isCinematic = false,
        cinematicMode = null,
        targetFps = 60,
        isReady = false,
        postProcessingAvailable = false
    )

    init {
        camera.restoreSession(initialRuntime?.cameraSession, params)
        initialRuntime?.cameraSession?.cinematic?.let { saved ->
            if (params.reducedMotion && (saved.active || saved.recovering)) {
                params = params.copy(autoSpin = saved.startAutoSpin)
            }
        }
        if (camera.isCinematic) cinematicOriginalAutoSpin = initialRuntime?.cameraSession?.cinematic?.startAutoSpin
        initialRuntime?.benchmarkReport?.let { benchmark.restoreReport(it) }
        publishRuntimeState()
    }

    /** Snapshot for the UI thread. */
    override fun snapshot(): ChalSnapshot = lastSnapshot

    /** Called once by the surface view with the panel's refresh rate. */
    override fun setDisplayRefreshRate(refreshRateHz: Double) {
        displayRefreshRateHz = if (refreshRateHz.isFinite() && refreshRateHz > 0.0) refreshRateHz else 60.0
        configurationDirty.set(true)
        cadenceDirty.set(true)
    }

    // ---------------------------------------------------------------------------------------
    // UI-thread API
    // ---------------------------------------------------------------------------------------

    /** Replace the simulation parameters (feature changes force a shader recompilation). */
    override fun updateParams(newParams: ChalSimulationParams) {
        editParams { newParams }
    }

    override fun editParams(edit: ChalSimulationParams.() -> ChalSimulationParams): ChalSimulationParams = synchronized(paramsLock) {
        val normalized = ChalRenderPolicy.normalize(params.edit(), native = false)
        params = if (graphicsCapabilitiesKnown && !hdrCapable) normalized.copy(bloomThreshold = normalized.bloomThreshold.coerceAtMost(0.9)) else normalized
        configurationDirty.set(true)
        paramsDirty.set(true)
        params
    }

    override fun paramsForPersistence(): ChalSimulationParams = benchmark.originalParams
        ?: params.copy(autoSpin = cinematicOriginalAutoSpin ?: params.autoSpin)

    override fun saveRuntimeState(): ChalRuntimeState = runtimeSnapshot.copy(
        params = benchmark.originalParams ?: params, benchmarkReport = lastBenchmarkReport
    )

    private fun publishRuntimeState() {
        runtimeSnapshot = ChalRuntimeState(params, camera.saveSession(), time, lastBenchmarkReport)
    }

    /** GL-thread animation edits merge into the latest UI state without resetting frame pacing. */
    private fun mutateParams(invalidate: Boolean = false, transform: ChalSimulationParams.() -> ChalSimulationParams) {
        synchronized(paramsLock) { params = params.transform() }
        if (invalidate) paramsDirty.set(true)
    }

    fun resetCamera() {
        cancelBenchmark()
        camera.reset { mutateParams(invalidate = true, transform = it) }
        configurationDirty.set(true)
    }

    fun resetScenarioPitch() {
        camera.resetScenarioPitch()
        syncCameraPose()
        paramsDirty.set(true)
    }

    fun startCinematic(mode: ChalCamera.CinematicMode, reducedMotion: Boolean) {
        if (params.reducedMotion || reducedMotion || isBenchmarkRunning()) return
        stopCinematic()
        cinematicOriginalAutoSpin = params.autoSpin
        camera.startCinematic(mode, params, reducedMotion)
        if (mode == ChalCamera.CinematicMode.DIVE) mutateParams(invalidate = true) { copy(autoSpin = 0.0) }
        paramsDirty.set(true)
    }

    fun stopCinematic() {
        camera.stopCinematic()?.let { speed -> mutateParams(invalidate = true) { copy(autoSpin = speed) } }
        cinematicOriginalAutoSpin = null
    }

    fun onHostResume() { cadenceDirty.set(true) }
    fun onHostPause() { cancelBenchmark(); syncCameraPose(); publishRuntimeState(); cadenceDirty.set(true) }

    fun onPointerDown(x: Double, y: Double) {
        lastActivityTime = now()
        camera.onPointerDown(x, y)
        paramsDirty.set(true)
    }
    fun onPointerMove(x: Double, y: Double) {
        lastActivityTime = now()
        camera.onPointerMove(x, y)
        paramsDirty.set(true)
    }
    fun onPointerUp() = camera.onPointerUp()
    fun onTouchCountChanged(count: Int) = camera.setTouchCount(count)
    fun onPan(dx: Double, dy: Double) {
        lastActivityTime = now()
        camera.onPan(dx, dy)
        paramsDirty.set(true)
    }
    fun onPinchStart(distance: Double) {
        lastActivityTime = now()
        camera.onPinchStart(distance)
    }
    fun onPinch(distance: Double) {
        lastActivityTime = now()
        mutateParams(invalidate = true) { copy(zoom = camera.onPinch(distance, zoom)) }
    }
    fun onScrollZoom(delta: Double) {
        lastActivityTime = now()
        mutateParams(invalidate = true) { copy(zoom = camera.onScrollZoom(delta, zoom)) }
    }
    fun nudge(dTheta: Double, dPhi: Double) {
        stopCinematic()
        lastActivityTime = now()
        camera.nudge(dTheta, dPhi)
        paramsDirty.set(true)
    }

    private fun syncCameraPose() {
        val pose = camera.mouseState()
        mutateParams { copy(cameraYaw = ChalRenderPolicy.normalizeYaw(pose.x), verticalAngle = pose.y * 180.0) }
    }

    /** True while a cinematic owns the camera (drives the ABORT SEQ affordance). */
    fun isCinematic(): Boolean = camera.isCinematic

    /**
     * Start the performance suite (`startBenchmark`).
     *
     * GL thread only: it mutates the simulation parameters as it walks the presets.
     */
    fun startBenchmark() {
        if (isBenchmarkRunning()) return
        stopCinematic()
        syncCameraPose()
        lastBenchmarkReport = null
        benchmark.start(params, actualRenderScale, waitForFirstPresentation = true)
        editParams {
            copy(features = benchmark.featuresFor(ChalBenchmark.PRESETS_TO_TEST.first()),
                paused = false, adaptiveResolution = false, automaticQuality = false,
                reducedMotion = true, batterySaver = false, renderScale = actualRenderScale)
        }
        monitor.reset()
    }

    fun cancelBenchmark() {
        benchmark.cancel()?.let { restoreBenchmarkParams(it) }
    }

    private fun restoreBenchmarkParams(original: ChalSimulationParams) {
        editParams { original }
        camera.restore(original)
        monitor.reset()
    }

    fun isBenchmarkRunning(): Boolean = benchmark.state == ChalBenchmark.State.RUNNING

    // ---------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ---------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // EGL may recreate the context; program IDs from the previous context are invalid.
        fatalInitializationFailure = false
        presentedTexture = 0
        shaderManager.invalidateContext()
        uniformLocations.clear()
        bloom = null
        reprojection = null
        shadowCurveMass = Double.NaN
        program = 0
        compiledFeatures = null
        compiledHasPost = false
        failedFeatures = null
        failedHasPost = false

        // FIX: Set explicit clear color so TAA history starts from a defined state
        // instead of GPU-dependent garbage.
        GLES30.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Enable float textures for HDR. Devices without the extension fall back to LDR
        // rather than shipping a broken framebuffer.
        val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        hdrCapable = extensions.contains("GL_EXT_color_buffer_float")
        if (!hdrCapable) {
            Log.w(ChalGlShaders.TAG, "EXT_color_buffer_float unavailable; HDR pipeline downgraded")
        }

        quadBuffer = ChalGlShaders.createQuadBuffer()
        noiseTexture = ChalGlShaders.createNoiseTexture(256)
        blueNoiseTexture = ChalGlShaders.createBlueNoiseTexture(256)

        // Post-processing chain (bloom owns the scene FBO; reprojection owns the TAA history)
        val bloomPipeline = ChalBloom(hdrCapable)
        val reprojectionPipeline = ChalReprojection(hdrCapable)

        val bloomOk = bloomPipeline.initialize(max(1, surfaceWidth), max(1, surfaceHeight))
        val reprojectionOk = reprojectionPipeline.initialize(max(1, surfaceWidth), max(1, surfaceHeight))

        if (bloomOk) bloom = bloomPipeline else {
            bloomPipeline.cleanup()
            bloom = null
        }
        if (reprojectionOk) reprojection = reprojectionPipeline else {
            reprojectionPipeline.cleanup()
            reprojection = null
        }
        if (!bloomOk || !reprojectionOk) {
            Log.w(ChalGlShaders.TAG, "Post-processing unavailable; rendering directly to the surface")
        }

        // Seed the adaptive controller with the phone start scale and the panel's refresh rate.
        monitor.setTargetFrameTime(ChalPerformanceConfig.Mobile.targetFrameTimeMs(displayRefreshRateHz))
        monitor.seedRenderScale(if (params.adaptiveResolution) min(params.renderScale, monitor.initialResolutionScale()) else params.renderScale)
        monitor.setQuality(params.features.rayTracingQuality)
        appliedParams = params
        monitor.requestFastRecalibration()

        compileProgram(force = true)

        if (quadBuffer == 0 || noiseTexture == 0 || blueNoiseTexture == 0 || program == 0) {
            fatalInitializationFailure = true
            errorMessage = "Chal renderer failed to initialise (shader compile or texture creation)."
        } else {
            errorMessage = null
            // Prevent stutters when new execution paths (branches) are first taken.
            ChalGlShaders.warmupShader(program, quadBuffer, surfaceWidth, surfaceHeight)
        }

        frameClock.reset(now())
        cadenceDirty.set(true)
        paramsDirty.set(true)
        lastMetricsUpdate = 0.0
        publishSnapshot(monitor.getMetrics())
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = max(1, width)
        surfaceHeight = max(1, height)
        presentedTexture = 0 // the post-processing targets are about to be re-created
        GLES30.glViewport(0, 0, surfaceWidth, surfaceHeight)
        try {
            bloom?.resize(surfaceWidth, surfaceHeight)
        } catch (failure: RuntimeException) {
            Log.w(ChalGlShaders.TAG, "Scene target resize failed; using direct rendering", failure)
            bloom?.cleanup()
            reprojection?.cleanup()
            bloom = null
            reprojection = null
            compileProgram(force = true)
        }
        if (bloom != null) try {
            reprojection?.ensureSize(surfaceWidth, surfaceHeight)
        } catch (failure: RuntimeException) {
            // Keep the working scene/bloom pipeline if only optional temporal AA fails.
            Log.w(ChalGlShaders.TAG, "TAA resize failed; using the scene texture directly", failure)
            reprojection?.cleanup()
            reprojection = null
        }
        paramsDirty.set(true)
    }

    override fun onDrawFrame(gl: GL10?) {
        val frameStart = now()
        if (fatalInitializationFailure || program == 0) {
            // Nothing can be drawn; present clean black instead of whatever a stale buffer holds.
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            publishSnapshot(monitor.getMetrics())
            return
        }
        if (cadenceDirty.getAndSet(false)) {
            frameClock.reset(frameStart)
            monitor.reset()
            smoothedDeltaTime = 1000.0 / 60.0
        }
        if (configurationDirty.getAndSet(false)) {
            val previous = appliedParams
            val current = params
            if (current.renderScale != previous?.renderScale || current.adaptiveResolution != previous?.adaptiveResolution) {
                monitor.seedRenderScale(current.renderScale)
            }
            monitor.setQuality(current.features.rayTracingQuality)
            if (current.features.rayTracingQuality != previous?.features?.rayTracingQuality) monitor.requestFastRecalibration()
            if (current.paused != previous?.paused) {
                frameClock.reset(frameStart)
                monitor.reset()
            }
            if (current.reducedMotion && camera.isCinematic) stopCinematic()
            appliedParams = current
        }

        if (lastActivityTime < 0.0 || paramsDirty.get()) lastActivityTime = frameStart
        val idle = !isBenchmarkRunning() && frameStart - lastActivityTime > ChalPerformanceConfig.Scheduler.IDLE_TIMEOUT_MS
        val fpsLimit = if (params.batterySaver || idle) 30 else ChalPerformanceConfig.Scheduler.TARGET_FPS
        targetFrameTime = ChalPerformanceConfig.Mobile.targetFrameTimeMs(displayRefreshRateHz, fpsLimit)
        monitor.setTargetFrameTime(targetFrameTime)

        // Do not manufacture FPS samples or advance a cinematic while a paused scene is still.
        if (params.paused && !paramsDirty.get()) {
            if (frameStart - lastMetricsUpdate >= 200.0) {
                lastMetricsUpdate = frameStart
                publishSnapshot(monitor.getMetrics())
            }
            // Show the frozen frame again. Without a retained frame (direct rendering) fall through
            // and keep drawing at the paced rate: time and camera do not advance while paused.
            if (presentRetainedFrame()) return
        }
        val admittedDelta = frameClock.frameDelta(frameStart, targetFrameTime, force = params.paused && paramsDirty.get())
        val cappedDelta: Double = admittedDelta ?: run {
            if (presentRetainedFrame()) return
            // Nothing to re-present: wait until the next frame is due instead of swapping a stale buffer.
            sleepQuietly(frameClock.remainingMs(frameStart, targetFrameTime))
            frameClock.frameDelta(now(), targetFrameTime, force = true) ?: return
        }
        val interactionDirty = paramsDirty.getAndSet(false)
        val dtSeconds = min(cappedDelta, 100.0) * 0.001
        smoothedDeltaTime = smoothedDeltaTime * 0.93 + min(cappedDelta, 100.0) * 0.07

        camera.update(dtSeconds, params) { mutateParams(transform = it) }
        syncCameraPose()
        if (!camera.isCinematic) cinematicOriginalAutoSpin = null
        val mouse = camera.mouseState()
        if (interactionDirty || abs(mouse.x - lastMouseX) > 0.0001 || abs(mouse.y - lastMouseY) > 0.0001) {
            cameraMoving = true
            cameraMoveTimeout = frameStart + 300.0
            lastActivityTime = frameStart
        } else if (frameStart > cameraMoveTimeout) cameraMoving = false
        lastMouseX = mouse.x
        lastMouseY = mouse.y

        val metrics = monitor.updateMetrics(cappedDelta,
            adaptive = params.adaptiveResolution && !params.paused && !isBenchmarkRunning(),
            maximumScale = if (params.batterySaver) 0.75 else 1.0,
            controllerDelta = smoothedDeltaTime)

        if (isBenchmarkRunning()) {
            val next = benchmark.tick(metrics.rollingAverageFPS.toDouble()) { lastBenchmarkReport = it }
            if (next != null) {
                editParams { copy(features = benchmark.featuresFor(next)) }
                monitor.reset()
            } else if (benchmark.state == ChalBenchmark.State.COMPLETED) {
                benchmark.takeRestoreParams()?.let { restoreBenchmarkParams(it) }
            }
        }

        val frameParams = params
        val features = frameParams.features
        val scale = if (frameParams.adaptiveResolution) metrics.renderResolution else frameParams.renderScale
        // Without a scene FBO, a reduced viewport would paint only one corner of the screen.
        val renderScale = if (bloom != null) scale.coerceIn(0.5, if (frameParams.batterySaver) 0.75 else 1.0) else 1.0
        if (abs(renderScale - actualRenderScale) > 0.0001) {
            cameraMoving = true
            cameraMoveTimeout = frameStart + 300.0
        }
        actualRenderScale = renderScale
        if (frameStart - lastMetricsUpdate >= 200.0) {
            lastMetricsUpdate = frameStart
            publishSnapshot(metrics)
        }


        // Recompile shader if feature toggles changed (cached if same)
        compileProgram(force = false, features = features)
        if (program == 0) {
            publishSnapshot(metrics)
            return
        }

        if (!frameParams.paused) time += dtSeconds * ChalMotion.SHADER_TIME_PER_SECOND

        val bloomPipeline = bloom
        val reprojectionPipeline = reprojection

        // Update Bloom Config (feature-driven)
        bloomPipeline?.updateConfig(enabled = features.bloom, intensity = frameParams.bloomIntensity, threshold = frameParams.bloomThreshold)

        // 5. Offscreen for Bloom/TAA
        val targetFramebuffer = if (bloomPipeline != null) bloomPipeline.beginScene() else 0
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFramebuffer)

        GLES30.glViewport(
            0,
            0,
            max(1, (surfaceWidth * renderScale).toInt()),
            max(1, (surfaceHeight * renderScale).toInt())
        )
        GLES30.glUseProgram(program)

        // Bind Textures
        // NOTE: Sampler uniforms MUST be set with integer calls (uniform1i), not float calls.
        if (noiseTexture != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTexture)
            set1i("u_noiseTex", 2)
        }
        if (blueNoiseTexture != 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blueNoiseTexture)
            set1i("u_blueNoiseTex", 3)
        }

        // Shadow guide uniforms: Schwarzschild fallback (b_crit = 3*sqrt(3)*M), exactly the
        // reference's fallback path when the Rust telemetry stream is unavailable.
        // The 64-point circle only depends on the mass, so it is rebuilt when the mass changes
        // instead of 64 sin/cos + a sqrt every single frame.
        if (frameParams.mass != shadowCurveMass) {
            val bCrit = 3.0 * sqrt(3.0) * frameParams.mass
            for (i in 0 until 64) {
                val phi = (i / 64.0) * PI * 2.0
                shadowCurve[i * 2] = (kotlin.math.cos(phi) * bCrit).toFloat()
                shadowCurve[i * 2 + 1] = (kotlin.math.sin(phi) * bCrit).toFloat()
            }
            shadowCurveMass = frameParams.mass
        }
        set1f("u_shadowCount", 64.0)
        set2f("u_shadowShift", -(frameParams.mass * 2.0) * 2.6, frameParams.mass * 2.0 * 2.6)
        val curveLocation = loc("u_shadowCurve")
        if (curveLocation != -1) {
            GLES30.glUniform2fv(curveLocation, 64, shadowCurve, 0)
        }

        // CAMERA: u_camPos = (0,0,0) triggers the shader's spherical fallback camera, which uses
        // u_mouse + u_zoom so every interaction (drag, auto-spin, cinematic) is reflected.
        set3f("u_camPos", 0.0, 0.0, 0.0)
        set4f("u_camQuat", 0.0, 0.0, 0.0, 1.0)

        // Set Common Uniforms
        set2f("u_resolution", surfaceWidth * renderScale, surfaceHeight * renderScale)
        set1f("u_time", time)
        set1f("u_mass", frameParams.mass)
        // The shader converts dimensionless chi to a = M * chi exactly once.
        set1f("u_spin", frameParams.spin)
        set1f("u_zoom", frameParams.zoom * 2.0) // Decoupled from mass so it grows visibly
        set1f("u_disk_size", frameParams.diskSize)
        set1f("u_disk_scale_height", frameParams.diskScaleHeight)
        // `maxStepsMobile` (80) is Chal's legacy GLES mobile budget. Without it the ultra preset
        // asks for 256 steps per ray on a phone GPU, where a single step costs ~60 ALU ops plus a
        // divide-bound Kerr acceleration evaluation.
        set1i(
            "u_maxRaySteps",
            ChalFeatures.getMaxRaySteps(
                features.rayTracingQuality,
                isMobile = ChalPerformanceConfig.Mobile.IS_MOBILE_HARDWARE
            )
        )
        set1f("u_show_redshift", if (features.gravitationalRedshift) 1.0 else 0.0)
        set1f("u_show_kerr_shadow", if (features.kerrShadow) 1.0 else 0.0)
        set1f("u_lensing_strength", frameParams.lensing)
        set1f("u_frame_dragging_strength", frameParams.frameDraggingStrength)
        set2f("u_mouse", mouse.x, mouse.y)
        set1f("u_disk_density", frameParams.diskDensity)

        // THERMODYNAMICS: T ~ M^-1/4 (Shakura-Sunyaev)
        // Small BH = Hotter (Blue), Large BH = Cooler (Red)
        val massFactor = frameParams.mass.pow(-0.25)
        set1f("u_disk_temp", frameParams.diskTemp * massFactor)
        set1f("u_debug", 0.0)

        if (quadBuffer != 0) {
            ChalGlShaders.setupPositionAttribute(program, "position", quadBuffer)
        }

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        // A successful bloom/scene FBO is usable even when optional TAA failed to initialize.
        // Every offscreen scene is presented, including the bloom-disabled / TAA-missing case.
        if (bloomPipeline != null) {
            val scene = bloomPipeline.sceneTextureId
            if (scene != 0) {
                val resolved = reprojectionPipeline?.resolve(scene, 0.75, cameraMoving, renderScale)
                    ?.takeIf { it != 0 } ?: scene
                presentedTexture = resolved
                presentedScale = renderScale
                if (features.bloom) bloomPipeline.applyBloomToTexture(resolved, renderScale)
                else bloomPipeline.drawTextureToScreen(resolved, renderScale)
            }
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (compiledFeatures == features && benchmark.markPresetPresented()) {
            monitor.reset()
            frameClock.reset(now())
            smoothedDeltaTime = targetFrameTime
        }
        publishRuntimeState()
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /**
     * Re-issue the final presentation pass for the last finished frame: a few small full-screen passes,
     * with no ray-marching. The same calls end every normal frame, so GL state stays consistent.
     *
     * @return true when a frame was presented; false when there is nothing valid to show again.
     */
    private fun presentRetainedFrame(): Boolean {
        val pipeline = bloom ?: return false
        val texture = presentedTexture
        if (texture == 0) return false
        if (pipeline.config.enabled) pipeline.applyBloomToTexture(texture, presentedScale)
        else pipeline.drawTextureToScreen(texture, presentedScale)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return true
    }

    private fun sleepQuietly(milliseconds: Double) {
        if (!milliseconds.isFinite() || milliseconds <= 0.0) return
        try {
            Thread.sleep(milliseconds.toLong().coerceIn(1L, MAX_PACING_SLEEP_MS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun compileProgram(force: Boolean, features: ChalFeatureToggles = params.features) {
        val hasPost = bloom != null

        if (!force && compiledFeatures == features && compiledHasPost == hasPost) {
            errorMessage = null
            return
        }
        if (!force && failedFeatures == features && failedHasPost == hasPost) return

        val variant = shaderManager.compileShaderVariant(
            ChalShaderSource.VERTEX_SHADER,
            ChalShaderSource.FRAGMENT_SHADER,
            features,
            hasPost
        )

        if (variant != null) {
            program = variant.program
            compiledFeatures = features
            compiledHasPost = hasPost
            failedFeatures = null
            uniformLocations.clear()
            errorMessage = null
        } else {
            failedFeatures = features.copy()
            failedHasPost = hasPost
            errorMessage = if (force || program == 0) {
                "Chal shader compilation failed."
            } else {
                "Chal shader variant failed; the previous working variant remains active."
            }
            if (force) program = 0
        }
    }

    private fun publishSnapshot(metrics: ChalPerformanceMonitor.PerformanceMetrics) {
        // Physics state (the reference's `usePhysicsState`).
        val normalizedSpin = max(-1.0, min(1.0, params.spin))
        val eventHorizonRadius = ChalKerrMetric.calculateEventHorizon(params.mass, normalizedSpin)
        val photonSphereRadius = ChalKerrMetric.calculatePhotonSphere(params.mass, normalizedSpin)
        val iscoRadius = ChalKerrMetric.calculateIsco(params.mass, normalizedSpin, prograde = true)

        val timeDilation = ChalObserverReadouts.clockRate(params)
        // Standard gravitational redshift to infinity: z = 1/g - 1. Guard the horizon limit.
        val redshift = ChalObserverReadouts.redshift(timeDilation)

        lastSnapshot = ChalSnapshot(
            params = params,
            currentFps = if (params.paused) 0 else metrics.rollingAverageFPS,
            frameTimeMs = metrics.frameTimeMs,
            quality = compiledFeatures?.rayTracingQuality ?: params.features.rayTracingQuality,
            budgetUsage = monitor.getFrameTimeBudgetUsage(),
            eventHorizonRadius = eventHorizonRadius,
            photonSphereRadius = photonSphereRadius,
            iscoRadius = iscoRadius,
            timeDilation = timeDilation,
            redshift = redshift,
            isCinematic = camera.isCinematic,
            cinematicMode = camera.cinematicMode,
            targetFps = (1000.0 / targetFrameTime).roundToInt(),
            benchmarkState = benchmark.state,
            benchmarkPreset = benchmark.currentPreset(),
            benchmarkProgress = benchmark.currentProgress(),
            benchmarkResults = benchmark.results.toList(),
            benchmarkRecommendation = lastBenchmarkReport?.recommendedPreset,
            benchmarkRenderScale = lastBenchmarkReport?.renderScale,
            lastWorkingFeatures = compiledFeatures,
            actualRenderScale = actualRenderScale,
            postProcessingAvailable = bloom != null,
            bloomThresholdMax = if (hdrCapable) 4.0 else 0.9,
            isReady = program != 0 && !fatalInitializationFailure
        )
    }

    private fun now(): Double = System.nanoTime() / 1_000_000.0

    private fun loc(name: String): Int = uniformLocations.getOrPut(name) {
        if (program == 0) -1 else GLES30.glGetUniformLocation(program, name)
    }

    private fun set1f(name: String, value: Double) {
        val l = loc(name); if (l != -1) GLES30.glUniform1f(l, value.toFloat())
    }

    private fun set1i(name: String, value: Int) {
        val l = loc(name); if (l != -1) GLES30.glUniform1i(l, value)
    }

    private fun set2f(name: String, x: Double, y: Double) {
        val l = loc(name); if (l != -1) GLES30.glUniform2f(l, x.toFloat(), y.toFloat())
    }

    private fun set3f(name: String, x: Double, y: Double, z: Double) {
        val l = loc(name); if (l != -1) GLES30.glUniform3f(l, x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun set4f(name: String, x: Double, y: Double, z: Double, w: Double) {
        val l = loc(name)
        if (l != -1) GLES30.glUniform4f(l, x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat())
    }

    /** Release every GL object (called from the surface view's detach). */
    fun release() {
        shaderManager.clearCache()
        bloom?.cleanup()
        reprojection?.cleanup()
        bloom = null
        reprojection = null
        presentedTexture = 0
        if (noiseTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(noiseTexture), 0)
        if (blueNoiseTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(blueNoiseTexture), 0)
        if (quadBuffer != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadBuffer), 0)
        noiseTexture = 0
        blueNoiseTexture = 0
        quadBuffer = 0
        program = 0
        compiledFeatures = null
        shadowCurveMass = Double.NaN
        uniformLocations.clear()
    }
}
