package com.zig.gargantua.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLES31
import android.util.Log
import com.zig.gargantua.renderer.GargantuaForensicData.BlackResult
import com.zig.gargantua.renderer.GargantuaForensicData.DiffStat
import com.zig.gargantua.renderer.GargantuaForensicData.FboRecord
import com.zig.gargantua.renderer.GargantuaForensicData.PassRecord
import com.zig.gargantua.renderer.GargantuaForensicData.ProgramInfo
import com.zig.gargantua.renderer.GargantuaForensicData.RecordStat
import com.zig.gargantua.renderer.GargantuaForensicData.RingResult
import com.zig.gargantua.renderer.GargantuaForensicData.RingRow
import com.zig.gargantua.renderer.GargantuaForensicData.Sample
import com.zig.gargantua.renderer.GargantuaForensicData.SamplerBinding
import com.zig.gargantua.renderer.GargantuaForensicData.StageStat
import com.zig.gargantua.renderer.GargantuaForensicData.TextureRecord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TEMPORARY physical-GPU pipeline diagnostics: executor of the one-tap [GargantuaForensicRun].
 *
 * Active only while [GargantuaRenderState.diagnosticView] is not [View.OFF]; with the view OFF the
 * renderer never calls into this class (no programs, textures, FBOs, queries or readbacks exist) and
 * normal rendering is unchanged. Every number is measured from the real GL objects of the running
 * renderer: GPU reductions/probes (texelFetch) write raw float bits or counts into an RGBA32UI target
 * that is read back with GL_RGBA_INTEGER / GL_UNSIGNED_INT (a small readback of the GPU result, not a
 * CPU re-computation of the image). Pass state (program, FBO, attachments, draw buffers, viewport,
 * sampler uniforms and their bound textures) is queried from GL right after each production draw.
 */
internal class GargantuaGpuDiagnostics(private val context: Context) {

    enum class View(val label: String) {
        OFF("OFF"),
        D0_STATIC_HDR("D0 static HDR cache"),
        D1_RAY_RECORDS("D1 ray records (R=r G=phi B=valid, magenta=truncated)"),
        D2_MATERIAL("D2 material-pass output"),
        D3_BRIGHTPASS("D3 brightpass"),
        D4_BLOOM("D4 bloom"),
        D5_PRE_TONEMAP("D5 composite before ACES"),
        D6_FINAL("D6 final (real composite)"),
        D7_HIGHER_ORDER("D7 higher-order / total"),
        L1_CAPTURE("L1 capture/escape (black captured, blue escaped, yellow mixed)"),
        L2_DISK_HIT("L2 disk hit (white 1 crossing, green 2)"),
        L3_R_HIT("L3 r_hit of crossing 1"),
        L4_PHI_HIT("L4 phi_hit of crossing 1"),
        L5_HO_COUNT("L5 HO crossings (orange 1, red 2)"),
        L6_BOUNDARY("L6 boundaries (white capture, green D0 dark, magenta D6 dark)");

        companion object {
            fun fromIndex(index: Int): View = values().getOrElse(index) { OFF }
        }
    }

    /** A/B switch of the integer/sampler precision of the animation build + material programs. */
    enum class RecordPrecision(val label: String) {
        AS_SHIPPED("as shipped"),
        HIGHP_INT("highp int"),
        HIGHP_INT_SAMPLER("highp int+sampler");

        companion object {
            fun fromIndex(index: Int): RecordPrecision = values().getOrElse(index) { AS_SHIPPED }
        }
    }

    /** Everything the diagnostics read from one renderer frame (texture/FBO names are the real ones). */
    class Frame(
        val nowNanos: Long,
        val view: View,
        val precision: RecordPrecision,
        val surfaceW: Int,
        val surfaceH: Int,
        val renderW: Int,
        val renderH: Int,
        val rayW: Int,
        val rayH: Int,
        val blockSize: Int,
        val hdrTexture: Int,
        val presentationTexture: Int,
        val modulatedTexture: Int,
        val recordTextures: IntArray,
        val rayCacheTexture: Int,
        val builtEmissionTexture: Int,
        val animatedRayTexture: Int,
        val diskInnerRadius: Float,
        val diskOuterRadius: Float,
        val bloomTexture: Int,
        val bloomW: Int,
        val bloomH: Int,
        val bloomEnabled: Boolean,
        val bloomThreshold: Float,
        val bloomIntensity: Float,
        val exposure: Float,
        val brightPass: ShaderProgram?,
        val composite: ShaderProgram?,
        val quad: QuadGeometry,
        val animationActive: Boolean,
        val animationRequested: Boolean,
        val amplitudePercent: Int,
        val gateAction: String,
        val geodesicPasses: Int,
        val rebuilds: Int,
        val materialRan: Boolean,
        val bloomRan: Boolean,
        val compositeRan: Boolean,
        val animationStatus: String,
        val config: String,
        val fbos: List<Pair<String, Int>>,
        val drawRealComposite: () -> Unit,
        /** Camera actually used: dist, incl, az, targetX, targetY, targetZ. */
        val camera: FloatArray = FloatArray(6),
        val mass: Float = 1f,
        val spinA: Float = 0f,
        val maxSteps: Int = 0,
        val enableDoppler: Boolean = false,
        val signatureChanged: Boolean = false,
        val sceneDirty: Boolean = false,
        val cacheValid: Boolean = false,
        val cacheComplete: Boolean = false,
        val presentedModulated: Boolean = false,
        /**
         * Redraws the program of the last geodesic trace with u_EnableDisk = 0 into (fbo, w, h) and restores
         * the uniform. Returns null on success or the reason it refused (scene changed since the trace, ...).
         */
        val redrawTraceWithoutDisk: (Int, Int, Int) -> String? = { _, _, _ -> "not provided" }
    )

    val run = GargantuaForensicRun()

    private var viewProgram: ShaderProgram? = null
    private var statsProgram: ShaderProgram? = null
    private var probeProgram: ShaderProgram? = null
    private var preToneProgram: ShaderProgram? = null
    private var programFailure: String? = null
    private var programsAttempted = false

    private var statsFbo = 0
    private var statsTex = 0
    private var probeFbo = 0
    private var probeTex = 0
    private var brightFbo = 0
    private var brightTex = 0
    private var brightW = 0
    private var brightH = 0
    private var preFbo = 0
    private var preTex = 0
    private var finalFbo = 0
    private var finalTex = 0
    private var refMaterialFbo = 0
    private var refMaterialTex = 0
    private var refFinalFbo = 0
    private var refFinalTex = 0
    private var copyReadFbo = 0
    private var refMaterialValid = false
    private var stageW = 0
    private var stageH = 0

    private var capsText: String? = null
    private var es31 = false
    private var hasTimer = false
    private val timerQueries = HashMap<String, Int>()
    private val timerPending = HashMap<String, Boolean>()
    private val timerMs = LinkedHashMap<String, Float>()
    private var timerOpen: String? = null
    private val samplerUniformCache = HashMap<Int, List<Pair<String, Int>>>()

    private var lensProgram: ShaderProgram? = null
    private var lensNoDiskFbo = 0
    private var lensNoDiskTex = 0
    private var lensFbo = 0
    private var lensTex = 0
    private var lensW = 0
    private var lensH = 0
    private var lensValid = false

    private var report = ""
    private var status = ""
    private var reportBuilt = false
    private var lastFrameQuad: QuadGeometry? = null
    private var lastRingPeaks: List<IntArray> = emptyList()

    private val statsBuffer: IntBuffer =
        ByteBuffer.allocateDirect(STATS_GRID * STATS_GRID * 16).order(ByteOrder.nativeOrder()).asIntBuffer()
    private val probeBuffer: IntBuffer =
        ByteBuffer.allocateDirect(SCAN_SAMPLES * PROBE_ROWS * 16).order(ByteOrder.nativeOrder()).asIntBuffer()

    /** Compact COPY REPORT text (complete once the run finished or was interrupted) + the manual drag trace. */
    val reportText: String get() = if (report.isEmpty()) report else report + manualTraceSection()
    /** Short progress / completion status for the overlay. */
    val statusText: String get() = status

    // ------------------------------------------------------------------ run control

    /**
     * The renderer's effective state for this frame: the user's state with the overrides of the current
     * forensic phase (animation, amplitude, record precision, rebuild generation). Starts a run on the
     * first frame with DIAG on. The user's own state in the holder is never modified.
     */
    fun forensicState(user: GargantuaRenderState, nowNanos: Long): GargantuaRenderState {
        if (run.state == GargantuaForensicRun.State.IDLE) {
            run.start(nowNanos)
            report = ""
            reportBuilt = false
        }
        run.snapshotOffsets()
        return GargantuaForensicRun.effectiveState(user, run.activePhase(), run.rebuildGeneration, run.cameraOffsetAz, run.cameraOffsetIncl)
    }

    fun interruptRun(reason: String) {
        run.interrupt(reason)
    }

    /** The renderer (re)built the animation programs while a run is active: record exact variant + status. */
    fun noteAnimationPrograms(
        precision: RecordPrecision,
        buildSource: String,
        materialSource: String,
        build: ShaderProgram?,
        material: ShaderProgram?,
        failure: String?
    ) {
        samplerUniformCache.clear()
        val record = run.currentRecord ?: return
        val (intP, samplerP, usamplerP) = ProgramInfo.describe(materialSource)
        record.programs = ProgramInfo(
            precision = precision.label,
            buildVariant = "GARGANTUA_ANIMATION_SEMANTIC_CACHE",
            materialVariant = "GARGANTUA_ANIMATION_SEMANTIC_CACHE+GARGANTUA_ANIMATION_MATERIAL_PASS",
            buildOk = build != null,
            materialOk = material != null,
            failure = failure,
            buildProgramId = build?.programId ?: 0,
            materialProgramId = material?.programId ?: 0,
            intPrecision = intP,
            sampler2DPrecision = samplerP,
            usamplerPrecision = usamplerP,
            declarations = "build: " + ProgramInfo.declarations(buildSource) + " | material: " + ProgramInfo.declarations(materialSource)
        )
    }

    // ------------------------------------------------------------------ renderer hooks

    /** Call right after a production draw while its state is still bound. Pure GL state queries. */
    fun passSnapshot(name: String, inputUnits: IntArray) {
        val record = run.currentRecord ?: return
        if (!run.passCaptureArmed && record.passes.containsKey(name)) return
        record.passes[name] = readPassState(name, inputUnits)
    }

    private fun readPassState(name: String, inputUnits: IntArray): PassRecord {
        val v = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, v, 0)
        val program = v[0]
        var linked = false
        if (program != 0) {
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, v, 0)
            linked = v[0] != 0
        }
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, v, 0)
        val fbo = v[0]
        val attachments = IntArray(4)
        if (fbo != 0) {
            for (i in 0 until 4) {
                GLES30.glGetFramebufferAttachmentParameteriv(
                    GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, v, 0
                )
                if (v[0] == GLES30.GL_TEXTURE) {
                    GLES30.glGetFramebufferAttachmentParameteriv(
                        GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, v, 0
                    )
                    attachments[i] = v[0]
                }
            }
        }
        val drawBuffers = IntArray(4)
        for (i in 0 until 4) {
            GLES30.glGetIntegerv(GLES30.GL_DRAW_BUFFER0 + i, v, 0)
            drawBuffers[i] = v[0]
        }
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, v, 0)
        val activeUnit = v[0]
        fun boundTexture(unit: Int): Int {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, v, 0)
            return v[0]
        }
        val samplers = ArrayList<SamplerBinding>()
        if (program != 0 && linked) {
            for ((uniform, location) in samplerUniforms(program)) {
                GLES30.glGetUniformiv(program, location, v, 0)
                val unit = v[0]
                samplers.add(SamplerBinding(uniform, unit, boundTexture(unit)))
            }
        }
        val bound = ArrayList<IntArray>()
        for (unit in (0 until BOUND_UNIT_SCAN).union(inputUnits.toList())) {
            val tex = boundTexture(unit)
            if (tex != 0) bound.add(intArrayOf(unit, tex))
        }
        GLES30.glActiveTexture(activeUnit)
        return PassRecord(name, program, linked, fbo, attachments, drawBuffers, viewport, samplers, bound)
    }

    /** Active sampler uniforms (name, location) of a linked program, enumerated from GL. */
    private fun samplerUniforms(program: Int): List<Pair<String, Int>> = samplerUniformCache.getOrPut(program) {
        val v = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_ACTIVE_UNIFORMS, v, 0)
        val out = ArrayList<Pair<String, Int>>()
        val size = IntArray(1)
        val type = IntArray(1)
        for (i in 0 until v[0]) {
            val name = GLES30.glGetActiveUniform(program, i, size, 0, type, 0) ?: continue
            if (type[0] != GLES30.GL_SAMPLER_2D && type[0] != GLES30.GL_UNSIGNED_INT_SAMPLER_2D && type[0] != GLES30.GL_INT_SAMPLER_2D) continue
            val location = GLES30.glGetUniformLocation(program, name)
            if (location >= 0) out.add(name to location)
        }
        out
    }

    fun beginTimer(name: String) {
        if (!hasTimer || timerOpen != null || timerPending[name] == true) return
        val id = timerQueries.getOrPut(name) {
            val q = IntArray(1)
            GLES30.glGenQueries(1, q, 0)
            q[0]
        }
        GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, id)
        timerOpen = name
    }

    fun endTimer(name: String) {
        if (timerOpen != name) return
        GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
        timerPending[name] = true
        timerOpen = null
    }

    private fun pollTimers() {
        if (!hasTimer) return
        val v = IntArray(1)
        GLES30.glGetIntegerv(GL_GPU_DISJOINT_EXT, v, 0)
        val disjoint = v[0] != 0
        for ((name, id) in timerQueries) {
            if (timerPending[name] != true) continue
            GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT_AVAILABLE, v, 0)
            if (v[0] == 0) continue
            GLES30.glGetQueryObjectuiv(id, GLES30.GL_QUERY_RESULT, v, 0)
            timerPending[name] = false
            if (!disjoint) timerMs[name] = (v[0].toLong() and 0xFFFFFFFFL) / 1.0e6f
        }
    }

    /** Runs after the normal frame: advances the forensic run by one work item and draws the view. */
    fun afterFrame(frame: Frame) {
        lastFrameQuad = frame.quad
        if (!ensurePrograms()) {
            run.interrupt("diagnostic programs failed: $programFailure")
            finishReport(frame)
            status = run.progressText(frame.nowNanos)
            return
        }
        if (capsText == null) capsText = collectCaps()
        pollTimers()
        ensureStageTargets(frame)
        val cpuMs = (System.nanoTime() - frame.nowNanos) / 1e6f
        if (run.state != GargantuaForensicRun.State.RUNNING) noteFrame(frame, cpuMs)

        val work = run.onFrame(
            GargantuaForensicRun.FrameStatus(
                nowNanos = frame.nowNanos,
                animationActive = frame.animationActive,
                animationRequested = frame.animationRequested,
                geodesicPasses = frame.geodesicPasses,
                materialRan = frame.materialRan,
                bloomRan = frame.bloomRan,
                compositeRan = frame.compositeRan,
                rebuilds = frame.rebuilds,
                gateAction = frame.gateAction,
                animationStatus = frame.animationStatus,
                camAzDeg = frame.camera[2],
                camInclDeg = frame.camera[1],
                signatureChanged = frame.signatureChanged,
                sceneDirty = frame.sceneDirty,
                cacheValid = frame.cacheValid,
                cacheComplete = frame.cacheComplete,
                presentedModulated = frame.presentedModulated,
                cpuMs = cpuMs
            )
        )
        val record = run.currentRecord
        if (record != null) {
            record.perf.gpuMs.putAll(timerMs)
            if (work.kind != GargantuaForensicRun.WorkKind.NONE) {
                try {
                    execute(work, record, frame)
                } catch (e: RuntimeException) {
                    record.failures.add("$work failed: ${e.javaClass.simpleName} ${e.message}")
                }
                val error = GLES30.glGetError()
                if (error != GLES30.GL_NO_ERROR) record.failures.add("$work GL error 0x${Integer.toHexString(error)}")
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                run.workDone(work, frame.nowNanos)
            }
        }
        finishReport(frame)
        status = run.progressText(frame.nowNanos)
        drawView(frame)
    }

    private fun finishReport(frame: Frame) {
        val done = run.state == GargantuaForensicRun.State.COMPLETE || run.state == GargantuaForensicRun.State.INTERRUPTED
        if (!done || reportBuilt) return
        reportBuilt = true
        report = GargantuaForensicReport.build(run, capsText ?: "caps unavailable", frame.config, frame.nowNanos)
        (report + "\n" + GargantuaForensicReport.raw(run)).lineSequence().forEach { Log.i(TAG, it) }
    }

    fun release() {
        listOf(viewProgram, statsProgram, probeProgram, preToneProgram, lensProgram).forEach { it?.release() }
        viewProgram = null; statsProgram = null; probeProgram = null; preToneProgram = null; lensProgram = null
        programsAttempted = false
        programFailure = null
        deleteTargets()
        if (timerQueries.isNotEmpty()) {
            val ids = timerQueries.values.toIntArray()
            GLES30.glDeleteQueries(ids.size, ids, 0)
        }
        timerQueries.clear(); timerPending.clear(); timerMs.clear(); timerOpen = null
        samplerUniformCache.clear()
        capsText = null
        run.cancel()
        report = ""
        status = ""
        reportBuilt = false
    }

    /** GL object names are invalid after an EGL context loss; forget them without deleting. */
    fun forgetContext() {
        run.interrupt("EGL context re-created (surface lost)")
        viewProgram = null; statsProgram = null; probeProgram = null; preToneProgram = null; lensProgram = null
        programsAttempted = false
        lensNoDiskFbo = 0; lensNoDiskTex = 0; lensFbo = 0; lensTex = 0; lensW = 0; lensH = 0; lensValid = false
        statsFbo = 0; statsTex = 0; probeFbo = 0; probeTex = 0
        brightFbo = 0; brightTex = 0; brightW = 0; brightH = 0
        preFbo = 0; preTex = 0; finalFbo = 0; finalTex = 0; stageW = 0; stageH = 0
        refMaterialFbo = 0; refMaterialTex = 0; refFinalFbo = 0; refFinalTex = 0; copyReadFbo = 0; refMaterialValid = false
        timerQueries.clear(); timerPending.clear(); timerOpen = null
        samplerUniformCache.clear()
        capsText = null
    }

    // ------------------------------------------------------------------ resources

    private fun ensurePrograms(): Boolean {
        if (viewProgram != null && statsProgram != null && probeProgram != null && preToneProgram != null && lensProgram != null) return true
        if (programsAttempted) return false
        programsAttempted = true
        val vertex = ShaderSource.loadVertexShader(context)
        fun create(label: String, fragment: String): ShaderProgram? =
            ShaderProgram.create(vertex, fragment) { programFailure = "$label ${it.label}: ${it.log.take(200)}" }
        viewProgram = create("view", ShaderSource.loadAsset(context, "shaders/gargantua_diag_view.frag"))
        statsProgram = create("stats", ShaderSource.loadAsset(context, "shaders/gargantua_diag_stats.frag"))
        probeProgram = create("probe", ShaderSource.loadAsset(context, "shaders/gargantua_diag_probe.frag"))
        preToneProgram = create("preTone", preToneMappingCompositeSource(ShaderSource.loadCompositeFragmentShader(context)))
        lensProgram = create("lens", ShaderSource.loadAsset(context, "shaders/gargantua_diag_lens.frag"))
        return viewProgram != null && statsProgram != null && probeProgram != null && preToneProgram != null && lensProgram != null
    }

    private fun texture(internal: Int, w: Int, h: Int, format: Int, type: Int, filter: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return t[0]
    }

    private fun fbo(tex: Int): Int {
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return f[0]
    }

    private fun ensureStageTargets(frame: Frame) {
        if (statsFbo == 0) {
            statsTex = texture(GLES30.GL_RGBA32UI, STATS_GRID, STATS_GRID, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, GLES30.GL_NEAREST)
            statsFbo = fbo(statsTex)
            probeTex = texture(GLES30.GL_RGBA32UI, SCAN_SAMPLES, PROBE_ROWS, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, GLES30.GL_NEAREST)
            probeFbo = fbo(probeTex)
            val f = IntArray(1)
            GLES30.glGenFramebuffers(1, f, 0)
            copyReadFbo = f[0]
        }
        val bw = max(1, frame.bloomW)
        val bh = max(1, frame.bloomH)
        if (brightW != bw || brightH != bh) {
            deleteFboTex(brightFbo, brightTex)
            brightTex = texture(GLES30.GL_RGBA16F, bw, bh, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_LINEAR)
            brightFbo = fbo(brightTex)
            brightW = bw; brightH = bh
        }
        if (stageW != frame.renderW || stageH != frame.renderH) {
            deleteFboTex(preFbo, preTex)
            deleteFboTex(finalFbo, finalTex)
            deleteFboTex(refMaterialFbo, refMaterialTex)
            deleteFboTex(refFinalFbo, refFinalTex)
            preTex = texture(GLES30.GL_RGBA16F, frame.renderW, frame.renderH, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_LINEAR)
            preFbo = fbo(preTex)
            finalTex = texture(GLES30.GL_RGBA8, frame.renderW, frame.renderH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR)
            finalFbo = fbo(finalTex)
            refMaterialTex = texture(GLES30.GL_RGBA16F, frame.renderW, frame.renderH, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST)
            refMaterialFbo = fbo(refMaterialTex)
            refFinalTex = texture(GLES30.GL_RGBA8, frame.renderW, frame.renderH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_NEAREST)
            refFinalFbo = fbo(refFinalTex)
            refMaterialValid = false
            stageW = frame.renderW; stageH = frame.renderH
        }
    }

    private fun deleteFboTex(f: Int, t: Int) {
        if (f != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(f), 0)
        if (t != 0) GLES30.glDeleteTextures(1, intArrayOf(t), 0)
    }

    private fun deleteTargets() {
        deleteFboTex(statsFbo, statsTex); statsFbo = 0; statsTex = 0
        deleteFboTex(probeFbo, probeTex); probeFbo = 0; probeTex = 0
        deleteFboTex(brightFbo, brightTex); brightFbo = 0; brightTex = 0; brightW = 0; brightH = 0
        deleteFboTex(preFbo, preTex); preFbo = 0; preTex = 0
        deleteFboTex(finalFbo, finalTex); finalFbo = 0; finalTex = 0
        deleteFboTex(refMaterialFbo, refMaterialTex); refMaterialFbo = 0; refMaterialTex = 0
        deleteFboTex(refFinalFbo, refFinalTex); refFinalFbo = 0; refFinalTex = 0
        deleteFboTex(copyReadFbo, 0); copyReadFbo = 0
        deleteFboTex(lensNoDiskFbo, lensNoDiskTex); lensNoDiskFbo = 0; lensNoDiskTex = 0
        deleteFboTex(lensFbo, lensTex); lensFbo = 0; lensTex = 0; lensW = 0; lensH = 0; lensValid = false
        refMaterialValid = false
        stageW = 0; stageH = 0
    }

    /** Exact copy (NEAREST blit, same format and size) of a renderer texture into a diagnostics reference. */
    private fun copyTexture(src: Int, dstFbo: Int, w: Int, h: Int): Boolean {
        if (src == 0 || dstFbo == 0) return false
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, copyReadFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, src, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, dstFbo)
        GLES30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glFramebufferTexture2D(GLES30.GL_READ_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        return true
    }

    // ------------------------------------------------------------------ stage capture

    /**
     * D3: the production brightpass program on the presented HDR texture (identical program, input and
     * threshold as the frame's bloom pass; its own output target because the bloom chain overwrites it).
     * D5: the production composite source truncated just before ACES. D6: the production composite
     * program into an RGBA8 target at the render resolution.
     */
    private fun renderStages(frame: Frame) {
        val quad = frame.quad
        frame.brightPass?.let { bp ->
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, brightFbo)
            GLES30.glViewport(0, 0, brightW, brightH)
            bp.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.presentationTexture)
            bp.setUniform1i("u_HdrTexture", 0)
            bp.setUniform1f("u_BloomThreshold", frame.bloomThreshold)
            quad.draw()
        }
        fun composite(program: ShaderProgram, target: Int) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target)
            GLES30.glViewport(0, 0, stageW, stageH)
            program.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, frame.presentationTexture)
            program.setUniform1i("u_HdrTexture", 0)
            val bloomReady = frame.bloomEnabled && frame.bloomTexture != 0
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (bloomReady) frame.bloomTexture else 0)
            program.setUniform1i("u_BloomTexture", 1)
            program.setUniform1i("u_EnableBloom", if (bloomReady) 1 else 0)
            program.setUniform1f("u_Exposure", frame.exposure)
            program.setUniform1f("u_BloomIntensity", frame.bloomIntensity)
            program.setUniform2f("u_TexelSize", 1.0f / max(1, frame.renderW), 1.0f / max(1, frame.renderH))
            quad.draw()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }
        preToneProgram?.let { composite(it, preFbo) }
        frame.composite?.let { composite(it, finalFbo) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun drawView(frame: Frame) {
        if (frame.view == View.D6_FINAL || frame.view == View.OFF) {
            // The normal presentation; only re-drawn when this frame's composite did not run.
            if (!frame.compositeRan) frame.drawRealComposite()
            return
        }
        val program = viewProgram ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, frame.surfaceW, frame.surfaceH)
        program.use()
        var mode = 0
        var tex = 0
        var size = intArrayOf(frame.renderW, frame.renderH)
        when (frame.view) {
            View.D0_STATIC_HDR -> tex = frame.hdrTexture
            View.D1_RAY_RECORDS -> { mode = 1; size = intArrayOf(frame.rayW, frame.rayH) }
            View.D2_MATERIAL -> tex = frame.modulatedTexture
            View.D3_BRIGHTPASS -> { renderStages(frame); tex = brightTex; size = intArrayOf(brightW, brightH) }
            View.D4_BLOOM -> { tex = frame.bloomTexture; size = intArrayOf(frame.bloomW, frame.bloomH) }
            View.D5_PRE_TONEMAP -> { renderStages(frame); tex = preTex }
            View.D7_HIGHER_ORDER -> { mode = 2; tex = frame.hdrTexture }
            View.L1_CAPTURE, View.L2_DISK_HIT, View.L3_R_HIT, View.L4_PHI_HIT, View.L5_HO_COUNT, View.L6_BOUNDARY -> {
                mode = 3; size = intArrayOf(lensW, lensH)
            }
            else -> Unit
        }
        if (mode == 3 && !lensValid) {
            // No LENS capture yet (it is taken in phases D, M2 and M5): show the normal presentation.
            if (!frame.compositeRan) frame.drawRealComposite()
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, frame.surfaceW, frame.surfaceH)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (mode == 1) 0 else tex)
        program.setUniform1i("u_A", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (mode == 1) frame.recordTextures.getOrElse(0) { 0 } else if (mode == 3) lensTex else 0)
        program.setUniform1i("u_LensMode", if (mode == 3) frame.view.ordinal - View.L1_CAPTURE.ordinal + 1 else 0)
        program.setUniform1i("u_R", 1)
        program.setUniform1i("u_Mode", mode)
        program.setUniform1f("u_Scale", 1.0f)
        GLES30.glUniform2i(program.getUniformLocation("u_Size"), size[0], size[1])
        program.setUniform2f("u_Screen", frame.surfaceW.toFloat(), frame.surfaceH.toFloat())
        val markers = markerPoints(frame)
        GLES30.glUniform2fv(program.getUniformLocation("u_Markers"), markers.size / 2, markers, 0)
        program.setUniform1i("u_MarkerCount", markers.size / 2)
        frame.quad.draw()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun markerPoints(frame: Frame): FloatArray {
        val out = ArrayList<Float>()
        for (p in ANIMATION_PROBES_ST) {
            val t = stToTexel(p[0], p[1], frame.renderW, frame.renderH)
            out.add((t[0] + 0.5f) / frame.renderW); out.add((t[1] + 0.5f) / frame.renderH)
        }
        for (p in lastRingPeaks.take(20)) {
            out.add((p[0] + 0.5f) / frame.renderW); out.add((p[1] + 0.5f) / frame.renderH)
        }
        return out.toFloatArray()
    }

    // ------------------------------------------------------------------ work items

    private fun execute(work: GargantuaForensicRun.Work, record: GargantuaForensicRun.PhaseRecord, frame: Frame) {
        when (work.kind) {
            GargantuaForensicRun.WorkKind.SAMPLE -> sample(work.index, record, frame)
            GargantuaForensicRun.WorkKind.CACHE -> cacheContent(work.cachePass, record, frame)
            GargantuaForensicRun.WorkKind.RING -> { renderStages(frame); record.ring = ringScan(frame) }
            GargantuaForensicRun.WorkKind.BLACK -> { renderStages(frame); record.black = blackPixels(frame) }
            GargantuaForensicRun.WorkKind.GRAPH -> graph(record, frame)
            GargantuaForensicRun.WorkKind.POP -> pop(work.index, record, frame)
            GargantuaForensicRun.WorkKind.LENS -> lens(record, frame)
            GargantuaForensicRun.WorkKind.NONE -> Unit
        }
    }

    /**
     * POP0: copy D2. POP1: D2 vs the copy (one frame of animation: the baseline), then copy D2 again (the
     * run nudges the camera so the next frame retraces). POP2: the retraced static frame vs the last
     * material frame. POP3: the first material frame after a release vs its static cache.
     */
    private fun pop(index: Int, record: GargantuaForensicRun.PhaseRecord, frame: Frame) {
        val w = frame.renderW; val h = frame.renderH
        val d2 = if (frame.animationActive) frame.modulatedTexture else 0
        when (index) {
            0 -> refMaterialValid = d2 != 0 && copyTexture(d2, refMaterialFbo, w, h)
            1 -> {
                if (d2 != 0 && refMaterialValid) record.popBaseline = diff(d2, refMaterialTex, w, h)
                else record.failures.add("POP1: D2 not presented (${frame.gateAction})")
                refMaterialValid = d2 != 0 && copyTexture(d2, refMaterialFbo, w, h)
            }
            2 -> {
                record.popStepGate = frame.gateAction
                if (refMaterialValid) record.popStep = diff(frame.presentationTexture, refMaterialTex, w, h)
                else record.failures.add("POP2: no previous D2")
                refMaterialValid = false
            }
            3 -> if (d2 != 0) record.restartPop = diff(d2, frame.hdrTexture, w, h)
        }
    }

    /**
     * LENS: the production trace redrawn without the disk (classification), packed per ray pixel with the
     * r0 records and the D0/D5/D6 dark masks, read back once; the CPU mirror runs on a worker thread.
     */
    private fun lens(record: GargantuaForensicRun.PhaseRecord, frame: Frame) {
        val program = lensProgram
        if (program == null) { record.failures.add("LENS: program unavailable ($programFailure)"); return }
        val w = frame.rayW; val h = frame.rayH
        if (lensW != w || lensH != h) {
            deleteFboTex(lensNoDiskFbo, lensNoDiskTex)
            deleteFboTex(lensFbo, lensTex)
            lensNoDiskTex = texture(GLES30.GL_RGBA16F, w, h, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST)
            lensNoDiskFbo = fbo(lensNoDiskTex)
            lensTex = texture(GLES30.GL_RGBA32UI, w, h, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, GLES30.GL_NEAREST)
            lensFbo = fbo(lensTex)
            lensW = w; lensH = h
        }
        lensValid = false
        val refused = frame.redrawTraceWithoutDisk(lensNoDiskFbo, w, h)
        if (refused != null) { record.failures.add("LENS: no-disk redraw refused: $refused"); return }
        renderStages(frame)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, lensFbo)
        GLES30.glViewport(0, 0, w, h)
        program.use()
        val samplers = arrayOf("u_NoDisk" to lensNoDiskTex, "u_Hdr" to frame.hdrTexture, "u_Pre" to preTex, "u_Final" to finalTex)
        for ((i, sp) in samplers.withIndex()) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sp.second)
            program.setUniform1i(sp.first, i)
        }
        val r0 = frame.recordTextures.getOrElse(0) { 0 }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, r0)
        program.setUniform1i("u_R0", 4)
        program.setUniform1i("u_HasRecords", if (r0 != 0) 1 else 0)
        GLES30.glUniform2i(program.getUniformLocation("u_Res"), w, h)
        frame.quad.draw()
        val buf = ByteBuffer.allocateDirect(w * h * 16).order(ByteOrder.nativeOrder()).asIntBuffer()
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, buf)
        for (i in 4 downTo 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        val words = IntArray(w * h * 4)
        buf.position(0)
        buf.get(words)
        lensValid = true
        val c = frame.camera
        val cam = GargantuaLensAnalysis.Camera(
            c[0], c[1], c[2], floatArrayOf(c[3], c[4], c[5]), frame.mass, frame.spinA, frame.maxSteps,
            frame.diskInnerRadius, frame.diskOuterRadius, frame.enableDoppler, w, h
        )
        val result = GargantuaLensAnalysis.analyze(cam, words)
        if (r0 == 0) record.failures.add("LENS: r0 records unavailable (ANIM off): crossing statistics empty")
        record.lensResult = result
        result.cpuNote = "CPU mirror running"
        // The CPU mirror (hundreds of full geodesics) must not run on the GL thread.
        Thread({
            val start = System.nanoTime()
            try {
                GargantuaLensAnalysis.cpuRows(result)
                result.cpu = GargantuaLensAnalysis.cpuSamples(result, words, GargantuaLensAnalysis.samplePoints(result, words))
                result.cpuNote = String.format(Locale.US, "CPU mirror done in %.0f ms", (System.nanoTime() - start) / 1e6)
            } catch (e: RuntimeException) {
                result.cpuNote = "CPU mirror failed: ${e.javaClass.simpleName} ${e.message}"
            }
        }, "gargantua-lens-cpu").start()
    }

    // ------------------------------------------------------------------ manual camera trace (after the run)

    private val inputRing = ArrayDeque<GargantuaLensAnalysis.InputEvent>()
    private val frameRing = ArrayDeque<GargantuaLensAnalysis.FrameEvent>()
    private val failureEvents = ArrayList<String>()
    private var manualCache = ""
    private var manualCacheKey = -1L

    /** UI thread: one processed touch event (only called while DIAG is on). */
    fun noteInput(event: GargantuaLensAnalysis.InputEvent) = synchronized(inputRing) {
        if (inputRing.size >= RING_SIZE) inputRing.removeFirst()
        inputRing.addLast(event)
    }

    /** GL thread: an animation failure latch (label includes the GL error when there was one). */
    fun noteAnimationFailure(label: String, nowNanos: Long) = synchronized(failureEvents) {
        if (failureEvents.size < 16) failureEvents.add(String.format(Locale.US, "t=%.3f s %s", nowNanos / 1e9, label))
    }

    private fun noteFrame(frame: Frame, cpuMs: Float) = synchronized(inputRing) {
        if (frameRing.size >= RING_SIZE) frameRing.removeFirst()
        frameRing.addLast(
            GargantuaLensAnalysis.FrameEvent(
                frame.nowNanos, frame.camera[2], frame.camera[1], frame.camera[0], frame.gateAction, frame.geodesicPasses,
                frame.materialRan, frame.presentedModulated, frame.signatureChanged, cpuMs, frame.animationStatus
            )
        )
    }

    private fun manualTraceSection(): String = synchronized(inputRing) {
        val key = (inputRing.lastOrNull()?.tNanos ?: 0L) xor (frameRing.size.toLong() shl 48) xor (frameRing.lastOrNull()?.tNanos ?: 0L) / 250_000_000L
        if (key != manualCacheKey) {
            manualCacheKey = key
            val failures = synchronized(failureEvents) { failureEvents.toList() }
            manualCache = "\n" + GargantuaLensAnalysis.dragText(inputRing.toList(), frameRing.toList()) +
                "ANIMATION FAILURE LATCH EVENTS: " + (if (failures.isEmpty()) "none" else failures.joinToString(" | ")) + "\n"
        }
        manualCache
    }

    private fun sample(index: Int, record: GargantuaForensicRun.PhaseRecord, frame: Frame) {
        renderStages(frame)
        val tMs = if (index == 0) 0L else (frame.nowNanos - record.t0Nanos) / 1_000_000L
        if (index == 0) {
            record.animationActiveAtCapture = frame.animationActive
            record.animationStatusAtCapture = frame.animationStatus
            record.gateActionAtCapture = frame.gateAction
        }
        val material = if (frame.animationActive) frame.modulatedTexture else 0
        val stages = ArrayList<StageStat>()
        stages.add(stageStat("D0", frame.hdrTexture, frame.renderW, frame.renderH))
        val r0 = frame.recordTextures.getOrElse(0) { 0 }
        var validPixels = -1L
        if (r0 == 0) {
            stages.add(StageStat.unavailable("D1", "records not allocated"))
        } else {
            val r = runStats(2, 0, 0, 0, r0, frame.rayW, frame.rayH)
            if (r == null) stages.add(StageStat.unavailable("D1", "readback failed")) else {
                var nz = 0L; var hi = 0L; var trunc = 0L
                for (i in 0 until STATS_GRID * STATS_GRID) { nz += u(r[i * 4]); hi += u(r[i * 4 + 1]); trunc += u(r[i * 4 + 2]) }
                validPixels = hi
                stages.add(StageStat("D1", r0, frame.rayW, frame.rayH, 0f, 0f, 0f, nz, "r0 nonzeroPx=$nz validPx=$hi truncatedPx=$trunc flagOnlyPx=${nz - hi - trunc}"))
            }
        }
        stages.add(if (material == 0) StageStat.unavailable("D2", "not presented (${frame.gateAction})") else stageStat("D2", material, frame.renderW, frame.renderH))
        stages.add(stageStat("D3", brightTex, brightW, brightH))
        stages.add(if (frame.bloomTexture == 0) StageStat.unavailable("D4", "bloom off") else stageStat("D4", frame.bloomTexture, frame.bloomW, frame.bloomH))
        stages.add(stageStat("D5", preTex, stageW, stageH))
        stages.add(stageStat("D6", finalTex, stageW, stageH))

        var hoMean = 0f; var hoMax = 0f; var hoPx = 0L; var hoHalf = 0L
        runStats(3, frame.hdrTexture, 0, 0, 0, frame.renderW, frame.renderH)?.let { s ->
            var sum = 0.0
            for (i in 0 until STATS_GRID * STATS_GRID) { sum += f(s[i * 4 + 2]); hoMax = max(hoMax, f(s[i * 4 + 1])); hoPx += s[i * 4 + 3] and 0xFFFF; hoHalf += s[i * 4 + 3] ushr 16 }
            hoMean = if (hoPx > 0) (sum / hoPx).toFloat() else 0f
        }

        val vsStatic = if (material != 0) diff(material, frame.hdrTexture, frame.renderW, frame.renderH) else null
        var vsT0: DiffStat? = null
        var finalVsT0: DiffStat? = null
        if (index == 0) {
            refMaterialValid = material != 0 && copyTexture(material, refMaterialFbo, frame.renderW, frame.renderH)
            copyTexture(finalTex, refFinalFbo, stageW, stageH)
        } else {
            if (material != 0 && refMaterialValid) vsT0 = diff(material, refMaterialTex, frame.renderW, frame.renderH)
            finalVsT0 = diff(finalTex, refFinalTex, stageW, stageH)
        }
        val pts = animationProbeTexels(frame)
        fun lums(tex: Int, w: Int, h: Int) = probeStage(tex, w, h, pts, frame)?.map { lum(it[0], it[1], it[2]) }?.toFloatArray()
        record.samples.add(
            Sample(
                index = index, tMs = tMs, stages = stages, recordsValidPixels = validPixels,
                hoMean = hoMean, hoMax = hoMax, hoPixels = hoPx, hoAboveHalf = hoHalf,
                materialVsStatic = vsStatic, materialVsT0 = vsT0, finalVsT0 = finalVsT0,
                probeStatic = lums(frame.hdrTexture, frame.renderW, frame.renderH),
                probeMaterial = if (material != 0) lums(material, frame.renderW, frame.renderH) else null,
                probeFinal = lums(finalTex, stageW, stageH)
            )
        )
    }

    private fun cacheContent(pass: Int, record: GargantuaForensicRun.PhaseRecord, frame: Frame) {
        for (slot in 0 until AnimationGate.RECORDS_PER_CACHE_PASS) {
            val index = pass * AnimationGate.RECORDS_PER_CACHE_PASS + slot
            val tex = frame.recordTextures.getOrElse(index) { 0 }
            if (tex == 0) continue
            recordStat("r$index(p$pass c${slot + 1})", tex, frame)?.let { record.cache.add(it) }
        }
        if (pass == 0) {
            fun float(name: String, tex: Int, w: Int, h: Int) {
                record.cacheFloat.add(if (tex == 0) StageStat.unavailable(name, "not allocated") else stageStat(name, tex, w, h))
            }
            float("rayHDR(p0 c0)", frame.rayCacheTexture, frame.rayW, frame.rayH)
            float("builtEmission", frame.builtEmissionTexture, frame.rayW, frame.rayH)
            if (frame.blockSize > 1) float("animatedRay", frame.animatedRayTexture, frame.rayW, frame.rayH)
        }
    }

    private fun recordStat(label: String, tex: Int, frame: Frame): RecordStat? {
        val w = frame.rayW
        val h = frame.rayH
        val m2 = runStats(2, 0, 0, 0, tex, w, h) ?: return null
        val m5 = runStats(5, 0, 0, 0, tex, w, h) ?: return null
        val m6 = runStats(6, 0, 0, 0, tex, w, h) ?: return null
        val m7 = runStats(7, 0, 0, 0, tex, w, h) ?: return null
        val m8 = runStats(8, 0, 0, 0, tex, w, h) ?: return null
        val m9 = runStats(9, 0, 0, 0, tex, w, h) ?: return null
        val m10 = runStats(10, 0, 0, 0, tex, w, h) ?: return null
        var invalidNz = 0L; var zeroX = 0L; var flagOnlyX = 0L
        val sampleNonzero = LongArray(2)
        val sampleValid = LongArray(2)
        var nz = 0L; var valid = 0L; var trunc = 0L; var tier5 = 0L
        var validX = 0L; var hoX = 0L; var invalidX = 0L; var tier9 = 0L
        var rMin = Float.MAX_VALUE; var rMax = -Float.MAX_VALUE; var pMin = Float.MAX_VALUE; var pMax = -Float.MAX_VALUE
        var gMin = Float.MAX_VALUE; var gMax = -Float.MAX_VALUE
        val rawMin = LongArray(4) { 0xFFFFFFFFL }
        val rawMax = LongArray(4)
        for (i in 0 until STATS_GRID * STATS_GRID) {
            val o = i * 4
            nz += u(m2[o]); valid += u(m2[o + 1]); trunc += u(m2[o + 2]); tier5 += u(m2[o + 3])
            val cells = (m6[o + 2] and 0xFFFF).toLong()
            validX += cells; hoX += (m6[o + 2] ushr 16); invalidX += (m6[o + 3] and 0xFFFF); tier9 += (m6[o + 3] ushr 16)
            if (cells > 0) {
                rMin = min(rMin, f(m5[o])); rMax = max(rMax, f(m5[o + 1])); pMin = min(pMin, f(m5[o + 2])); pMax = max(pMax, f(m5[o + 3]))
                gMin = min(gMin, f(m6[o])); gMax = max(gMax, f(m6[o + 1]))
            }
            rawMin[0] = min(rawMin[0], u(m7[o])); rawMax[0] = max(rawMax[0], u(m7[o + 1]))
            rawMin[1] = min(rawMin[1], u(m7[o + 2])); rawMax[1] = max(rawMax[1], u(m7[o + 3]))
            rawMin[2] = min(rawMin[2], u(m8[o])); rawMax[2] = max(rawMax[2], u(m8[o + 1]))
            rawMin[3] = min(rawMin[3], u(m8[o + 2])); rawMax[3] = max(rawMax[3], u(m8[o + 3]))
            invalidNz += u(m9[o]); zeroX += u(m9[o + 1]); flagOnlyX += u(m9[o + 2])
            if (sampleNonzero[0] == 0L && sampleNonzero[1] == 0L) { sampleNonzero[0] = u(m10[o]); sampleNonzero[1] = u(m10[o + 1]) }
            if (sampleValid[0] == 0L && sampleValid[1] == 0L) { sampleValid[0] = u(m10[o + 2]); sampleValid[1] = u(m10[o + 3]) }
        }
        val span = frame.diskOuterRadius - frame.diskInnerRadius
        val none = validX == 0L
        return RecordStat(
            label = label, texture = tex, internalFormat = textureInfo(tex).first, w = w, h = h,
            nonzeroPixels = nz, validPixels = valid, truncatedPixels = trunc, tier5Flags = tier5, tier9Flags = tier9,
            validCrossings = validX, higherOrderCrossings = hoX, emissionInvalidCrossings = invalidX,
            rMin = if (none) Float.NaN else frame.diskInnerRadius + rMin * span,
            rMax = if (none) Float.NaN else frame.diskInnerRadius + rMax * span,
            phiMin = if (none) Float.NaN else ((pMin - 0.5f) * TAU),
            phiMax = if (none) Float.NaN else ((pMax - 0.5f) * TAU),
            gMin = if (none) Float.NaN else gMin, gMax = if (none) Float.NaN else gMax,
            rawMin = rawMin, rawMax = rawMax,
            invalidNonzeroCrossings = invalidNz, zeroCrossings = zeroX, flagOnlyCrossings = flagOnlyX,
            sampleNonzero = sampleNonzero, sampleValid = sampleValid
        )
    }

    private fun graph(record: GargantuaForensicRun.PhaseRecord, frame: Frame) {
        record.fbos.clear()
        record.fbos.addAll(fboRecords(frame.fbos))
        record.textures.clear()
        fun add(role: String, tex: Int) {
            if (tex == 0) return
            val (format, size) = textureInfo(tex)
            record.textures.add(TextureRecord(role, tex, format, size[0], size[1]))
        }
        add("D0 static HDR", frame.hdrTexture)
        if (frame.presentationTexture != frame.hdrTexture) add("presented HDR", frame.presentationTexture)
        add("D2 material out", frame.modulatedTexture)
        add("ray HDR cache", frame.rayCacheTexture)
        add("builtEmission", frame.builtEmissionTexture)
        if (frame.blockSize > 1) add("animatedRay", frame.animatedRayTexture)
        frame.recordTextures.forEachIndexed { i, t -> add("record r$i", t) }
        add("D4 bloom", frame.bloomTexture)
        add("diag D3 bright", brightTex)
        add("diag D5 preACES", preTex)
        add("diag D6 final", finalTex)
    }

    // ------------------------------------------------------------------ GPU reductions / probes

    private fun runStats(mode: Int, a: Int, b: Int, c: Int, r: Int, w: Int, h: Int): IntArray? {
        val program = statsProgram ?: return null
        if (w <= 0 || h <= 0) return null
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, statsFbo)
        GLES30.glViewport(0, 0, STATS_GRID, STATS_GRID)
        program.use()
        val units = intArrayOf(a, b, c)
        val names = arrayOf("u_A", "u_B", "u_C")
        for (i in 0 until 3) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, units[i])
            program.setUniform1i(names[i], i)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, r)
        program.setUniform1i("u_R", 3)
        program.setUniform1i("u_Mode", mode)
        GLES30.glUniform2i(program.getUniformLocation("u_Size"), w, h)
        GLES30.glUniform2i(program.getUniformLocation("u_Grid"), STATS_GRID, STATS_GRID)
        lastFrameQuad?.draw()
        statsBuffer.clear()
        GLES30.glReadPixels(0, 0, STATS_GRID, STATS_GRID, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, statsBuffer)
        for (i in 3 downTo 0) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
        val out = IntArray(STATS_GRID * STATS_GRID * 4)
        statsBuffer.position(0)
        statsBuffer.get(out)
        return out
    }

    private fun stageStat(stage: String, tex: Int, w: Int, h: Int): StageStat {
        if (tex == 0) return StageStat.unavailable(stage, "no texture")
        val s = runStats(0, tex, 0, 0, 0, w, h) ?: return StageStat.unavailable(stage, "readback failed")
        var mn = Float.MAX_VALUE; var mx = 0f; var sum = 0.0; var nz = 0L
        for (i in 0 until STATS_GRID * STATS_GRID) {
            mn = min(mn, f(s[i * 4])); mx = max(mx, f(s[i * 4 + 1])); sum += f(s[i * 4 + 2]); nz += u(s[i * 4 + 3])
        }
        return StageStat(stage, tex, w, h, mn, mx, (sum / (w.toDouble() * h)).toFloat(), nz)
    }

    private fun diff(a: Int, b: Int, w: Int, h: Int): DiffStat? {
        val d = runStats(1, a, b, 0, 0, w, h) ?: return null
        var sum = 0.0; var mx = 0f; var ch = 0L; var sh = 0L; var disk = 0L; var dch = 0L
        for (i in 0 until STATS_GRID * STATS_GRID) {
            sum += f(d[i * 4]); mx = max(mx, f(d[i * 4 + 1]))
            ch += d[i * 4 + 2] and 0xFFFF; sh += d[i * 4 + 2] ushr 16; disk += d[i * 4 + 3] and 0xFFFF; dch += d[i * 4 + 3] ushr 16
        }
        return DiffStat(sqrt(sum / (w.toDouble() * h)).toFloat(), mx, ch, disk, dch, sh)
    }

    /** Point probes (row 0) and, when [scan] is set, the 12 fixed radial scan lines. Raw uint bits. */
    private fun runProbe(kindRecords: Boolean, tex: Int, w: Int, h: Int, pointsTexel: FloatArray, scan: Boolean, frame: Frame): IntArray? {
        val program = probeProgram ?: return null
        if (tex == 0 || w <= 0 || h <= 0) return null
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, probeFbo)
        val rows = if (scan) PROBE_ROWS else 1
        GLES30.glViewport(0, 0, SCAN_SAMPLES, rows)
        program.use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (kindRecords) 0 else tex)
        program.setUniform1i("u_A", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (kindRecords) tex else 0)
        program.setUniform1i("u_R", 1)
        program.setUniform1i("u_Kind", if (kindRecords) 1 else 0)
        GLES30.glUniform2i(program.getUniformLocation("u_Size"), w, h)
        val n = min(pointsTexel.size / 2, MAX_POINTS)
        program.setUniform1i("u_PointCount", n)
        if (n > 0) GLES30.glUniform2fv(program.getUniformLocation("u_Points"), n, pointsTexel, 0)
        program.setUniform2f("u_ScanCenterSt", RING_CENTER_ST[0], RING_CENTER_ST[1])
        program.setUniform1f("u_ScanR0", SCAN_R0)
        program.setUniform1f("u_ScanDr", SCAN_DR)
        program.setUniform2f("u_Res", frame.renderW.toFloat(), frame.renderH.toFloat())
        frame.quad.draw()
        probeBuffer.clear()
        GLES30.glReadPixels(0, 0, SCAN_SAMPLES, rows, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, probeBuffer)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val out = IntArray(SCAN_SAMPLES * rows * 4)
        probeBuffer.position(0)
        probeBuffer.get(out)
        return out
    }

    private fun f(bits: Int): Float = java.lang.Float.intBitsToFloat(bits)
    private fun u(v: Int): Long = v.toLong() and 0xFFFFFFFFL
    private fun lum(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Samples a float stage at render-resolution texel points (scaled to the stage size). */
    private fun probeStage(tex: Int, w: Int, h: Int, pointsRender: List<IntArray>, frame: Frame): List<FloatArray>? {
        if (tex == 0) return null
        val pts = FloatArray(pointsRender.size * 2)
        pointsRender.forEachIndexed { i, p ->
            pts[2 * i] = (p[0] + 0.5f) * w / frame.renderW
            pts[2 * i + 1] = (p[1] + 0.5f) * h / frame.renderH
        }
        val raw = runProbe(false, tex, w, h, pts, false, frame) ?: return null
        return pointsRender.indices.map { i -> FloatArray(4) { c -> f(raw[i * 4 + c]) } }
    }

    private fun probeRecords(tex: Int, frame: Frame, pointsRender: List<IntArray>): List<IntArray>? {
        if (tex == 0) return null
        val pts = FloatArray(pointsRender.size * 2)
        pointsRender.forEachIndexed { i, p ->
            pts[2 * i] = (p[0] + 0.5f) * frame.rayW / frame.renderW
            pts[2 * i + 1] = (p[1] + 0.5f) * frame.rayH / frame.renderH
        }
        val raw = runProbe(true, tex, frame.rayW, frame.rayH, pts, false, frame) ?: return null
        return pointsRender.indices.map { i -> IntArray(4) { c -> raw[i * 4 + c] } }
    }

    private fun animationProbeTexels(frame: Frame): List<IntArray> =
        ANIMATION_PROBES_ST.map { stToTexel(it[0], it[1], frame.renderW, frame.renderH) }

    // ------------------------------------------------------------------ ring / black pixels

    private fun ringScan(frame: Frame): RingResult? {
        val raw = runProbe(false, frame.hdrTexture, frame.renderW, frame.renderH, FloatArray(0), true, frame) ?: return null
        val px = SCAN_DR * min(frame.renderW, frame.renderH) * 0.5f
        val rows = ArrayList<RingRow>()
        val peaks = ArrayList<IntArray>()
        val outers = ArrayList<IntArray>()
        val okAngles = ArrayList<Int>()
        fun isShadow(v: FloatArray) = v[3] <= 0.5f && v[0] * v[0] + v[1] * v[1] + v[2] * v[2] <= 1e-7f
        for (d in 0 until SCAN_DIRECTIONS) {
            fun at(i: Int) = FloatArray(4) { c -> f(raw[((d + 1) * SCAN_SAMPLES + i) * 4 + c]) }
            val angle = 15 + 30 * d
            var boundary = -1
            var hoMax = 0f
            var hoMaxAt = -1
            for (i in 0 until SCAN_SAMPLES) {
                val v = at(i)
                if (isShadow(v)) boundary = i
                val ho = max(0f, v[3] - 1f)
                if (ho > hoMax) { hoMax = ho; hoMaxAt = i }
            }
            val first = at(0)
            val startClass = when { isShadow(first) -> "shadow"; first[3] >= 1f -> "disk-lit"; else -> "sky" }
            val hoAt = if (hoMaxAt >= 0) scanTexel(d, hoMaxAt, frame) else null
            if (boundary < 0) {
                rows.add(RingRow(angle, "NO_SHADOW_BOUNDARY", startClass, null, null, null, 0f, 0f, 0f, hoMax, hoAt)); continue
            }
            if (boundary >= SCAN_SAMPLES - 2) {
                rows.add(RingRow(angle, "SHADOW_TO_SCAN_END", startClass, scanTexel(d, boundary, frame), null, null, 0f, 0f, 0f, hoMax, hoAt)); continue
            }
            var best = boundary + 1
            var bestHo = 0f
            for (i in boundary + 1 until min(SCAN_SAMPLES, boundary + 1 + PEAK_WINDOW)) {
                val ho = max(0f, at(i)[3] - 1f)
                if (ho > bestHo) { bestHo = ho; best = i }
            }
            val pk = at(best)
            val total = lum(pk[0], pk[1], pk[2])
            val status = when { bestHo <= 0f -> "NO_RING_PEAK"; total <= 0f -> "ZERO_RADIANCE"; else -> "OK" }
            val outer = min(SCAN_SAMPLES - 1, best + OUTER_OFFSET)
            val peakTexel = scanTexel(d, best, frame)
            val outerTexel = scanTexel(d, outer, frame)
            rows.add(RingRow(angle, status, startClass, scanTexel(d, boundary, frame), peakTexel, outerTexel, (best - boundary) * px, bestHo, total, hoMax, hoAt))
            if (status == "OK") { peaks.add(peakTexel); outers.add(outerTexel); okAngles.add(angle) }
        }
        lastRingPeaks = peaks
        val right = rows.filter { it.angleDeg in setOf(15, 45, 315, 345) }.map { it.hoMaxOnLine }.average().toFloat()
        val left = rows.filter { it.angleDeg in setOf(135, 165, 195, 225) }.map { it.hoMaxOnLine }.average().toFloat()
        val perStage = LinkedHashMap<String, List<FloatArray>?>()
        if (peaks.isNotEmpty()) {
            val stages = listOf(
                "D0" to Triple(frame.hdrTexture, frame.renderW, frame.renderH),
                "D2" to Triple(if (frame.animationActive) frame.modulatedTexture else 0, frame.renderW, frame.renderH),
                "D3" to Triple(brightTex, brightW, brightH),
                "D4" to Triple(frame.bloomTexture, frame.bloomW, frame.bloomH),
                "D5" to Triple(preTex, stageW, stageH),
                "D6" to Triple(finalTex, stageW, stageH)
            )
            for ((name, t) in stages) {
                val pk = probeStage(t.first, t.second, t.third, peaks, frame)
                val ou = probeStage(t.first, t.second, t.third, outers, frame)
                perStage[name] = if (pk == null || ou == null) null else pk.indices.map { i ->
                    floatArrayOf(lum(pk[i][0], pk[i][1], pk[i][2]), lum(ou[i][0], ou[i][1], ou[i][2]))
                }
            }
        }
        return RingResult(rows, right, left, perStage, okAngles)
    }

    private fun blackPixels(frame: Frame): BlackResult? {
        val s = runStats(4, finalTex, frame.hdrTexture, preTex, 0, stageW, stageH) ?: return null
        var a = 0L; var b = 0L; var c = 0L; var d = 0L; var e = 0L; var ez = 0L
        val examples = ArrayList<IntArray>()
        for (i in 0 until STATS_GRID * STATS_GRID) {
            a += s[i * 4] and 0xFFFF; b += s[i * 4] ushr 16; c += s[i * 4 + 1] and 0xFFFF; d += s[i * 4 + 1] ushr 16
            e += s[i * 4 + 2] and 0xFFFF; ez += s[i * 4 + 2] ushr 16
            val pos = s[i * 4 + 3]
            if (pos != 0 && examples.size < 3) examples.add(intArrayOf(pos and 0xFFFF, pos ushr 16))
        }
        val lines = ArrayList<String>()
        if (examples.isNotEmpty()) {
            fun rgb(v: FloatArray?) = if (v == null) "n/a" else String.format(Locale.US, "(%.4g %.4g %.4g a%.4g)", v[0], v[1], v[2], v[3])
            val d0 = probeStage(frame.hdrTexture, frame.renderW, frame.renderH, examples, frame)
            val rec = probeRecords(frame.recordTextures.getOrElse(0) { 0 }, frame, examples)
            val d3 = probeStage(brightTex, brightW, brightH, examples, frame)
            val d4 = probeStage(frame.bloomTexture, frame.bloomW, frame.bloomH, examples, frame)
            val d5 = probeStage(preTex, stageW, stageH, examples, frame)
            val d6 = probeStage(finalTex, stageW, stageH, examples, frame)
            examples.forEachIndexed { i, p ->
                val r = rec?.get(i)
                val recText = if (r == null) "records n/a" else String.format(
                    Locale.US, "M7=%d crossing0=%s crossing1=%s",
                    if ((r[3] and 0x8000) != 0) 9 else if ((r[1] and 0x8000) != 0) 5 else 1,
                    if ((r[1] ushr 16) != 0) "yes" else "no", if ((r[3] ushr 16) != 0) "yes" else "no"
                )
                lines.add("px ${p[0]},${p[1]}: HDR${rgb(d0?.get(i))} $recText bright${rgb(d3?.get(i))} bloom${rgb(d4?.get(i))} preACES${rgb(d5?.get(i))} final${rgb(d6?.get(i))}")
            }
        }
        return BlackResult(a, b, c, d, e, ez, lines)
    }

    private fun scanTexel(direction: Int, sample: Int, frame: Frame): IntArray {
        val angle = Math.toRadians(15.0 + 30.0 * direction)
        val r = SCAN_R0 + SCAN_DR * sample
        return stToTexel(
            (RING_CENTER_ST[0] + kotlin.math.cos(angle) * r).toFloat(),
            (RING_CENTER_ST[1] + kotlin.math.sin(angle) * r).toFloat(),
            frame.renderW, frame.renderH
        )
    }

    // ------------------------------------------------------------------ GL object state

    /** Internal format and size of a texture from GL (ES 3.1 texture level queries), else "n/a". */
    private fun textureInfo(tex: Int): Pair<String, IntArray> {
        if (!es31 || tex == 0) return "n/a(ES3.0)" to intArrayOf(0, 0)
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, v, 0)
        val active = v[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + DIAG_QUERY_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        val w = IntArray(1)
        val h = IntArray(1)
        GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT, v, 0)
        GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, w, 0)
        GLES31.glGetTexLevelParameteriv(GLES30.GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, h, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glActiveTexture(active)
        return formatName(v[0]) to intArrayOf(w[0], h[0])
    }

    private fun formatName(format: Int): String = when (format) {
        GLES30.GL_RGBA16F -> "RGBA16F"
        GLES30.GL_RGBA32UI -> "RGBA32UI"
        GLES30.GL_RGBA8 -> "RGBA8"
        GLES30.GL_RGBA32F -> "RGBA32F"
        GLES30.GL_R11F_G11F_B10F -> "R11G11B10F"
        GLES30.GL_RGBA -> "RGBA"
        else -> "0x" + Integer.toHexString(format)
    }

    private fun fboRecords(fbos: List<Pair<String, Int>>): List<FboRecord> {
        val v = IntArray(1)
        val out = ArrayList<FboRecord>()
        for ((name, id) in fbos) {
            if (id == 0) { out.add(FboRecord(name, 0, "none", emptyList())); continue }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id)
            val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            val atts = ArrayList<String>()
            for (i in 0 until 4) {
                GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, v, 0)
                if (v[0] != GLES30.GL_TEXTURE) continue
                GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, v, 0)
                val tex = v[0]
                GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_FRAMEBUFFER_ATTACHMENT_COMPONENT_TYPE, v, 0)
                val type = when (v[0]) { GLES30.GL_FLOAT -> "F"; GLES30.GL_UNSIGNED_INT -> "UI"; GLES30.GL_INT -> "I"; GLES30.GL_UNSIGNED_NORMALIZED -> "UN"; else -> "0x" + Integer.toHexString(v[0]) }
                GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_FRAMEBUFFER_ATTACHMENT_RED_SIZE, v, 0)
                val bits = v[0]
                val (format, size) = textureInfo(tex)
                atts.add("C$i:tex$tex/$type$bits/$format/${size[0]}x${size[1]}")
            }
            out.add(FboRecord(name, id, if (status == GLES30.GL_FRAMEBUFFER_COMPLETE) "COMPLETE" else "INCOMPLETE 0x" + Integer.toHexString(status), atts))
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return out
    }

    private fun collectCaps(): String {
        val v = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAX_DRAW_BUFFERS, v, 0)
        GLES30.glGetIntegerv(GLES30.GL_MAX_COLOR_ATTACHMENTS, v, 1)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        val version = GLES30.glGetString(GLES30.GL_VERSION) ?: ""
        es31 = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(version)?.let { m ->
            val major = m.groupValues[1].toInt(); val minor = m.groupValues[2].toInt()
            major > 3 || (major == 3 && minor >= 1)
        } ?: false
        hasTimer = ext.contains("GL_EXT_disjoint_timer_query")
        fun precision(type: Int, name: String): String {
            val range = IntArray(2)
            val prec = IntArray(1)
            GLES30.glGetShaderPrecisionFormat(GLES30.GL_FRAGMENT_SHADER, type, range, 0, prec, 0)
            return "$name[${range[0]},${range[1]};p${prec[0]}]"
        }
        return "GL $version | ${GLES30.glGetString(GLES30.GL_RENDERER)} | ${GLES30.glGetString(GLES30.GL_VENDOR)} | " +
            "GLSL ${GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION)}\n" +
            "MAX_DRAW_BUFFERS=${v[0]} MAX_COLOR_ATTACHMENTS=${v[1]} (animation cache pass uses 4 draw buffers / 4 attachments)\n" +
            "ext color_buffer_float=${ext.contains("GL_EXT_color_buffer_float")} color_buffer_half_float=${ext.contains("GL_EXT_color_buffer_half_float")} " +
            "timer=$hasTimer es31TextureQueries=$es31\n" +
            "fragment precision: " + listOf(
                precision(GLES30.GL_LOW_INT, "lowInt"), precision(GLES30.GL_MEDIUM_INT, "mediumInt"), precision(GLES30.GL_HIGH_INT, "highInt"),
                precision(GLES30.GL_LOW_FLOAT, "lowFloat"), precision(GLES30.GL_MEDIUM_FLOAT, "mediumFloat"), precision(GLES30.GL_HIGH_FLOAT, "highFloat")
            ).joinToString(" ")
    }

    companion object {
        private const val TAG = "GargantuaDiag"
        private const val VERSION_LINE = "#version 300 es"
        private const val GL_TIME_ELAPSED_EXT = 0x88BF
        private const val GL_GPU_DISJOINT_EXT = 0x8FBB
        private const val GL_TEXTURE_WIDTH = 0x1000
        private const val GL_TEXTURE_HEIGHT = 0x1001
        private const val GL_TEXTURE_INTERNAL_FORMAT = 0x1003
        private const val DIAG_QUERY_UNIT = 15
        private const val BOUND_UNIT_SCAN = 12
        private const val TAU = 6.2831855f
        const val STATS_GRID = 32
        const val SCAN_SAMPLES = 256
        const val SCAN_DIRECTIONS = 12
        const val PROBE_ROWS = SCAN_DIRECTIONS + 1
        const val MAX_POINTS = 48
        /** Input events and post-run frames kept for the manual drag trace. */
        const val RING_SIZE = 1024
        const val DISK_PROBES = 10
        private const val PEAK_WINDOW = 32
        private const val OUTER_OFFSET = 6

        /** Photon-ring scan centre in shader st coordinates (Kerr shadow centre, default camera). */
        val RING_CENTER_ST = floatArrayOf(0.1215f, 0.0f)
        const val SCAN_R0 = 0.20f
        const val SCAN_DR = 0.00255f

        /**
         * Fixed animation probes in shader st coordinates, st = (2*fragCoord - res) / min(res), for the
         * default camera: P0..P9 disk, then the shadow probe S and the sky probe K.
         */
        val ANIMATION_PROBES_ST: List<FloatArray> = listOf(
            floatArrayOf(-0.90f, 0.02f), floatArrayOf(-0.70f, 0.02f), floatArrayOf(-0.50f, -0.06f),
            floatArrayOf(0.50f, -0.06f), floatArrayOf(0.70f, 0.02f), floatArrayOf(0.90f, 0.02f),
            floatArrayOf(-0.25f, -0.20f), floatArrayOf(0.00f, -0.22f), floatArrayOf(0.25f, -0.20f),
            floatArrayOf(0.12f, 0.52f),
            floatArrayOf(0.12f, 0.22f),
            floatArrayOf(-0.80f, 1.50f)
        )

        fun stToTexel(stX: Float, stY: Float, w: Int, h: Int): IntArray {
            val m = min(w, h).toFloat()
            val x = ((stX * m + w) * 0.5f).toInt().coerceIn(0, w - 1)
            val y = ((stY * m + h) * 0.5f).toInt().coerceIn(0, h - 1)
            return intArrayOf(x, y)
        }

        /** Record-precision variant of an animation program source (AS_SHIPPED returns it unchanged). */
        fun applyRecordPrecision(source: String, precision: RecordPrecision): String {
            val extra = when (precision) {
                RecordPrecision.AS_SHIPPED -> return source
                RecordPrecision.HIGHP_INT -> "precision highp int;"
                RecordPrecision.HIGHP_INT_SAMPLER -> "precision highp int;\nprecision highp sampler2D;"
            }
            require(source.startsWith(VERSION_LINE)) { "shader must begin with $VERSION_LINE" }
            return source.replaceFirst(VERSION_LINE, "$VERSION_LINE\n$extra")
        }

        /** The production composite source, returning the exposed colour just before the ACES curve. */
        fun preToneMappingCompositeSource(composite: String): String {
            val anchor = "    float inputLuma = dot(exposed,"
            require(composite.contains(anchor)) { "composite ACES anchor not found" }
            return composite.replaceFirst(anchor, "    fragColor = vec4(exposed, 1.0);\n    return;\n$anchor")
        }
    }
}
