package com.zig.gargantua.renderer

import java.util.Locale

/**
 * TEMPORARY one-tap physical-GPU forensic run (diagnostics only; never active while the DIAG view is OFF).
 *
 * A deterministic schedule driven exclusively by render-frame callbacks: every phase states the
 * diagnostic render configuration it needs, is gated on the *measured* renderer status of real frames
 * (stable frames without a geodesic retrace, animation actually presented), and then requests one GL
 * work item per frame from [GargantuaGpuDiagnostics]. Nothing here sleeps or blocks; wall-clock time is
 * only compared against frame timestamps. This class holds no GL state and is unit-testable on the JVM.
 */
internal class GargantuaForensicRun {

    enum class State { IDLE, RUNNING, COMPLETE, INTERRUPTED }

    /** One configuration of the matrix. Overrides apply only to the renderer's per-frame effective state. */
    class Phase(
        val id: String,
        val title: String,
        val enableAnimation: Boolean,
        val amplitudePercent: Int,
        val precision: GargantuaGpuDiagnostics.RecordPrecision,
        /** Rebuild the animation programs (and retrace the cache) when the phase starts. */
        val forceProgramRebuild: Boolean,
        /** Stable means: animation presented by the material pass (not only: no geodesic retrace). */
        val expectsAnimation: Boolean,
        val minStableFrames: Int,
        val minSettleMs: Long,
        val timeoutMs: Long,
        /** Sample times relative to t0 (first sample). One sample for static phases, t0/t1/t2 for animation. */
        val sampleOffsetsMs: List<Long>,
        /** Scripted camera motion after the phase settled (null: static camera). */
        val motion: Motion? = null,
        /** Measure the geodesic classification (LENS work) once the phase is settled. */
        val lens: Boolean = false
    )

    /**
     * A scripted camera move applied through the effective state only (the user's camera is never
     * written): [bursts] bursts of [framesPerBurst] frames, each frame adding (dAz, dIncl) degrees,
     * separated by [gapMs]. With [pop] the script starts with the D2 pop probe (POP0..POP2).
     */
    class Motion(
        val dAzDeg: Float,
        val dInclDeg: Float,
        val framesPerBurst: Int,
        val bursts: Int = 1,
        val gapMs: Long = 0L,
        val pop: Boolean = false,
        /** Maximum time from the release until the phase must present animation again. */
        val restartTimeoutMs: Long = 8_000L
    )

    /** One frame of a scripted motion phase (stage P pre-move work, S move, G gap, R after release). */
    class MotionFrame(
        val tMs: Float,
        val stage: Char,
        val offsetAz: Float,
        val offsetIncl: Float,
        val camAz: Float,
        val camIncl: Float,
        val gate: String,
        val geodesicPasses: Int,
        val materialRan: Boolean,
        val presentedModulated: Boolean,
        val signatureChanged: Boolean,
        val sceneDirty: Boolean,
        val cacheValid: Boolean,
        val cacheComplete: Boolean,
        val cpuMs: Float,
        val status: String
    )

    /** Restart measurement of one motion phase (times relative to the release frame). */
    class Restart(
        val releaseNanos: Long,
        var firstCompleteCacheMs: Float = -1f,
        var firstModulateMs: Float = -1f,
        var plainFrames: Int = 0,
        var rebuildFramesAfterRelease: Int = 0,
        var framesToModulate: Int = 0,
        var restarted: Boolean = false,
        var statusAtTimeout: String = ""
    )

    /** GL work the executor performs in one frame. SAMPLE carries its index into [Phase.sampleOffsetsMs]. */
    enum class WorkKind { NONE, SAMPLE, CACHE, RING, BLACK, GRAPH, POP, LENS }

    class Work(val kind: WorkKind, val index: Int = 0, val cachePass: Int = 0) {
        override fun toString() = when (kind) {
            WorkKind.SAMPLE -> "SAMPLE$index"
            WorkKind.CACHE -> "CACHE$cachePass"
            WorkKind.POP -> "POP$index"
            else -> kind.name
        }
    }

    /** Measured renderer status of the frame that just finished (real renderer bookkeeping + GL results). */
    class FrameStatus(
        val nowNanos: Long,
        val animationActive: Boolean,
        val animationRequested: Boolean,
        val geodesicPasses: Int,
        val materialRan: Boolean,
        val bloomRan: Boolean,
        val compositeRan: Boolean,
        val rebuilds: Int,
        val gateAction: String,
        val animationStatus: String,
        val camAzDeg: Float = 0f,
        val camInclDeg: Float = 0f,
        val signatureChanged: Boolean = false,
        val sceneDirty: Boolean = false,
        val cacheValid: Boolean = false,
        val cacheComplete: Boolean = false,
        val presentedModulated: Boolean = false,
        val cpuMs: Float = 0f
    )

    class Perf {
        var frames = 0
        var idleIntervals = 0
        var idleNanos = 0L
        var diagIntervals = 0
        var diagNanos = 0L
        var geodesicPasses = 0L
        var framesWithGeodesic = 0
        var materialFrames = 0
        var bloomFrames = 0
        var compositeFrames = 0
        var rebuildsAtStart = -1
        var rebuildsAtEnd = 0
        var modulateFrames = 0
        var geodesicPassesInModulateFrames = 0L
        val gpuMs = LinkedHashMap<String, Float>()

        val idleFps: Float get() = if (idleNanos > 0) idleIntervals * 1.0e9f / idleNanos else 0f
        val diagFps: Float get() = if (idleNanos + diagNanos > 0) (idleIntervals + diagIntervals) * 1.0e9f / (idleNanos + diagNanos) else 0f
        val idleFrameMs: Float get() = if (idleIntervals > 0) idleNanos / 1.0e6f / idleIntervals else 0f
    }

    /** Everything recorded for one phase. GL measurements are filled in by the executor. */
    class PhaseRecord(val phase: Phase, val index: Int) {
        var startNanos = 0L
        var settledNanos = 0L
        var settleFrames = 0
        var t0Nanos = 0L
        val failures = ArrayList<String>()
        val samples = ArrayList<GargantuaForensicData.Sample>()
        val cache = ArrayList<GargantuaForensicData.RecordStat>()
        val cacheFloat = ArrayList<GargantuaForensicData.StageStat>()
        var ring: GargantuaForensicData.RingResult? = null
        var black: GargantuaForensicData.BlackResult? = null
        val passes = LinkedHashMap<String, GargantuaForensicData.PassRecord>()
        val fbos = ArrayList<GargantuaForensicData.FboRecord>()
        val textures = ArrayList<GargantuaForensicData.TextureRecord>()
        var programs: GargantuaForensicData.ProgramInfo? = null
        var animationStatusAtCapture = ""
        var gateActionAtCapture = ""
        var animationActiveAtCapture = false
        val perf = Perf()
        var finished = false
        /** Motion phases: 0 pre-settle, 1 script, 2 after release, 3 done. */
        var motionStage = 0
        var motionStartNanos = 0L
        val motionTrace = ArrayList<MotionFrame>()
        var restart: Restart? = null
        /** POP probe: D2 frame-to-frame change (baseline) and D2(prev) vs the rebuilt trace. */
        var popBaseline: GargantuaForensicData.DiffStat? = null
        var popStep: GargantuaForensicData.DiffStat? = null
        var popStepGate = ""
        /** Restart pop: first material frame after the release vs its static cache. */
        var restartPop: GargantuaForensicData.DiffStat? = null
        var lensText: String? = null
        var lensResult: GargantuaLensAnalysis.Result? = null
    }

    private sealed class Step {
        class DoWork(val work: Work) : Step()
        class Move(val dAz: Float, val dIncl: Float) : Step()
        class Wait(val ms: Long) : Step()
    }

    var state = State.IDLE
        private set
    var interruptReason = ""
        private set
    var rebuildGeneration = 0
        private set

    val records = ArrayList<PhaseRecord>()
    private var phaseIndex = -1
    private var runStartNanos = 0L
    private var lastFrameNanos = 0L
    private var lastFrameHadWork = false
    private var stableFrames = 0
    private val pending = ArrayList<Work>()
    private var timedSamples = ArrayList<Int>()
    private val script = ArrayList<Step>()
    private var waitStartNanos = 0L

    /** Camera offsets (degrees) the effective state adds for the next frame; 0 outside motion phases. */
    var cameraOffsetAz = 0f
        private set
    var cameraOffsetIncl = 0f
        private set

    val phases: List<Phase> get() = PHASES
    val currentRecord: PhaseRecord? get() = records.getOrNull(phaseIndex)?.takeIf { state == State.RUNNING }

    /** Whether the executor should record the pass graph of this frame (phase settled and sampling). */
    val passCaptureArmed: Boolean get() = currentRecord?.let { it.settledNanos != 0L } ?: false

    fun start(nowNanos: Long) {
        records.clear()
        state = State.RUNNING
        interruptReason = ""
        runStartNanos = nowNanos
        lastFrameNanos = 0L
        lastFrameHadWork = false
        enterPhase(0, nowNanos)
    }

    fun cancel() {
        state = State.IDLE
        phaseIndex = -1
        cameraOffsetAz = 0f
        cameraOffsetIncl = 0f
        pending.clear()
        timedSamples.clear()
    }

    fun interrupt(reason: String) {
        if (state != State.RUNNING) return
        state = State.INTERRUPTED
        interruptReason = reason
        cameraOffsetAz = 0f
        cameraOffsetIncl = 0f
        pending.clear()
        timedSamples.clear()
    }

    /** The configuration the current phase needs, or null when no run is in progress. */
    fun activePhase(): Phase? = if (state == State.RUNNING) PHASES.getOrNull(phaseIndex) else null

    /**
     * Called once per rendered frame (after the frame's passes). Returns the single work item the
     * executor must perform now. The executor reports back via [workDone] when it performed work.
     */
    fun onFrame(status: FrameStatus): Work {
        if (state != State.RUNNING) return NO_WORK
        val record = records[phaseIndex]
        val phase = record.phase
        val now = status.nowNanos

        if (lastFrameNanos != 0L) {
            val gap = now - lastFrameNanos
            if (gap > INTERRUPT_GAP_NANOS) {
                interrupt(String.format(Locale.US, "no frame for %.1f s (app paused, backgrounded or screen off)", gap / 1.0e9))
                return NO_WORK
            }
            if (record.perf.frames > 0) {
                if (lastFrameHadWork) { record.perf.diagIntervals++; record.perf.diagNanos += gap }
                else { record.perf.idleIntervals++; record.perf.idleNanos += gap }
            }
        }
        lastFrameNanos = now
        lastFrameHadWork = false
        accountPerf(record.perf, status)

        if (phase.motion != null && record.motionStage < 3) {
            val w = motionStep(record, phase, phase.motion, status)
            if (w != null) return w
            if (record.motionStage < 3) return NO_WORK
        }

        if (record.settledNanos == 0L) {
            val stable = status.geodesicPasses == 0 && (!phase.expectsAnimation || (status.animationActive && status.materialRan))
            stableFrames = if (stable) stableFrames + 1 else 0
            val elapsedMs = (now - record.startNanos) / 1_000_000L
            val settled = stableFrames >= phase.minStableFrames && elapsedMs >= phase.minSettleMs
            val timedOut = elapsedMs >= phase.timeoutMs
            if (!settled && !timedOut) return NO_WORK
            if (!settled) {
                record.failures.add(
                    "PHASE NOT SETTLED after ${elapsedMs / 1000.0}s: stableFrames=$stableFrames animActive=${status.animationActive} " +
                        "materialRan=${status.materialRan} geodesicPasses=${status.geodesicPasses} gate=${status.gateAction} status=${status.animationStatus}"
                )
            }
            record.settledNanos = now
            record.settleFrames = record.perf.frames
            val animated = phase.expectsAnimation && settled
            timedSamples = ArrayList((1 until (if (animated) phase.sampleOffsetsMs.size else 1)).toList())
            pending.clear()
            pending.add(Work(WorkKind.SAMPLE, 0))
            for (p in 0 until AnimationGate.CACHE_PASS_COUNT) pending.add(Work(WorkKind.CACHE, cachePass = p))
            if (phase.lens) pending.add(Work(WorkKind.LENS))
            if (phase.motion == null) {
                pending.add(Work(WorkKind.RING))
                pending.add(Work(WorkKind.BLACK))
                pending.add(Work(WorkKind.GRAPH))
            }
        }

        // Timed samples (t1, t2) take priority once due; they are measured relative to t0.
        if (record.t0Nanos != 0L && timedSamples.isNotEmpty()) {
            val next = timedSamples.first()
            if (now - record.t0Nanos >= phase.sampleOffsetsMs[next] * 1_000_000L) {
                timedSamples.removeAt(0)
                return Work(WorkKind.SAMPLE, next)
            }
        }
        if (pending.isNotEmpty()) return pending.removeAt(0)
        if (timedSamples.isNotEmpty()) return NO_WORK
        finishPhase(record, now)
        return NO_WORK
    }

    private fun isStable(phase: Phase, s: FrameStatus) =
        s.geodesicPasses == 0 && (!phase.enableAnimation || (s.animationActive && s.materialRan))

    private fun trace(record: PhaseRecord, stage: Char, s: FrameStatus) {
        if (record.motionTrace.size >= MAX_MOTION_FRAMES) return
        record.motionTrace.add(
            MotionFrame(
                (s.nowNanos - record.motionStartNanos) / 1e6f, stage, frameOffsetAz, frameOffsetIncl, s.camAzDeg, s.camInclDeg,
                s.gateAction, s.geodesicPasses, s.materialRan, s.presentedModulated, s.signatureChanged, s.sceneDirty,
                s.cacheValid, s.cacheComplete, s.cpuMs, s.animationStatus
            )
        )
    }

    /** Offsets the effective state of the frame now ending actually used (snapshotted at frame start). */
    private var frameOffsetAz = 0f
    private var frameOffsetIncl = 0f

    /** Called by the executor when it builds the effective state of a frame. */
    fun snapshotOffsets() {
        frameOffsetAz = cameraOffsetAz
        frameOffsetIncl = cameraOffsetIncl
    }

    /**
     * Motion phases: pre-settle (same criterion as sampling), the scripted camera move, the release and
     * the restart measurement. Returns work for this frame, or null. Sets motionStage 3 when done.
     */
    private fun motionStep(record: PhaseRecord, phase: Phase, m: Motion, s: FrameStatus): Work? {
        val now = s.nowNanos
        when (record.motionStage) {
            0 -> {
                stableFrames = if (isStable(phase, s)) stableFrames + 1 else 0
                val elapsedMs = (now - record.startNanos) / 1_000_000L
                val settled = stableFrames >= phase.minStableFrames && elapsedMs >= phase.minSettleMs
                if (!settled && elapsedMs < phase.timeoutMs) return null
                if (!settled) record.failures.add("MOTION PRE-SETTLE timed out: gate=${s.gateAction} status=${s.animationStatus}")
                record.motionStage = 1
                record.motionStartNanos = now
                script.clear()
                if (m.pop && phase.enableAnimation) {
                    script.add(Step.DoWork(Work(WorkKind.POP, 0)))
                    script.add(Step.DoWork(Work(WorkKind.POP, 1)))
                    script.add(Step.DoWork(Work(WorkKind.POP, 2)))
                }
                for (b in 0 until m.bursts) {
                    if (b > 0 && m.gapMs > 0) script.add(Step.Wait(m.gapMs))
                    repeat(m.framesPerBurst) { script.add(Step.Move(m.dAzDeg, m.dInclDeg)) }
                }
                trace(record, 'P', s)
                return advanceScript(record, s)
            }
            1 -> {
                trace(record, if (script.firstOrNull() is Step.Wait) 'G' else 'S', s)
                return advanceScript(record, s)
            }
            2 -> {
                val r = record.restart ?: return null
                trace(record, 'R', s)
                val tMs = (now - r.releaseNanos) / 1e6f
                if (r.firstModulateMs < 0f) {
                    r.framesToModulate++
                    if (s.gateAction == "PLAIN") r.plainFrames++
                    if (s.geodesicPasses > 0 && s.gateAction == "REBUILD") r.rebuildFramesAfterRelease++
                    if (s.gateAction == "COMPLETE_CACHE" && r.firstCompleteCacheMs < 0f) r.firstCompleteCacheMs = tMs
                    if (s.gateAction == "MODULATE" && s.materialRan) {
                        r.firstModulateMs = tMs
                        r.restarted = true
                        if (phase.enableAnimation) return Work(WorkKind.POP, 3)
                    }
                }
                val stable = isStable(phase, s)
                stableFrames = if (stable) stableFrames + 1 else 0
                val done = if (phase.enableAnimation) r.restarted && stableFrames >= phase.minStableFrames else stableFrames >= phase.minStableFrames
                if (done) { record.motionStage = 3; return null }
                if (tMs >= m.restartTimeoutMs) {
                    r.statusAtTimeout = "gate=${s.gateAction} active=${s.animationActive} material=${s.materialRan} geodesic=${s.geodesicPasses} status=${s.animationStatus}"
                    record.failures.add(
                        if (phase.enableAnimation && !r.restarted) "ANIMATION DID NOT RESTART within ${m.restartTimeoutMs} ms after release: ${r.statusAtTimeout}"
                        else "NOT STABLE ${m.restartTimeoutMs} ms after release: ${r.statusAtTimeout}"
                    )
                    record.motionStage = 3
                }
                return null
            }
        }
        return null
    }

    private fun advanceScript(record: PhaseRecord, s: FrameStatus): Work? {
        while (script.isNotEmpty()) {
            when (val step = script.first()) {
                is Step.DoWork -> {
                    script.removeAt(0)
                    // POP1 measures the D2 baseline in this frame; the nudge makes the next frame a rebuild (POP2).
                    if (step.work.kind == WorkKind.POP && step.work.index == 1) cameraOffsetAz += POP_NUDGE_DEG
                    return step.work
                }
                is Step.Move -> {
                    script.removeAt(0)
                    cameraOffsetAz += step.dAz
                    cameraOffsetIncl += step.dIncl
                    return null
                }
                is Step.Wait -> {
                    if (waitStartNanos == 0L) waitStartNanos = s.nowNanos
                    if (s.nowNanos - waitStartNanos < step.ms * 1_000_000L) return null
                    waitStartNanos = 0L
                    script.removeAt(0)
                }
            }
        }
        // Script finished: this frame rendered the last offset; the release is now.
        record.motionStage = 2
        record.restart = Restart(s.nowNanos)
        stableFrames = 0
        return null
    }

    /** The executor performed [work] in this frame (its cost falls into the next frame interval). */
    fun workDone(work: Work, nowNanos: Long) {
        lastFrameHadWork = true
        val record = currentRecord ?: return
        if (work.kind == WorkKind.SAMPLE && work.index == 0) record.t0Nanos = nowNanos
    }

    private fun accountPerf(perf: Perf, s: FrameStatus) {
        perf.frames++
        if (perf.rebuildsAtStart < 0) perf.rebuildsAtStart = s.rebuilds
        perf.rebuildsAtEnd = s.rebuilds
        perf.geodesicPasses += s.geodesicPasses
        if (s.geodesicPasses > 0) perf.framesWithGeodesic++
        if (s.materialRan) perf.materialFrames++
        if (s.bloomRan) perf.bloomFrames++
        if (s.compositeRan) perf.compositeFrames++
        if (s.gateAction == "MODULATE") {
            perf.modulateFrames++
            perf.geodesicPassesInModulateFrames += s.geodesicPasses
        }
    }

    private fun enterPhase(index: Int, nowNanos: Long) {
        phaseIndex = index
        val phase = PHASES[index]
        if (phase.forceProgramRebuild) rebuildGeneration++
        records.add(PhaseRecord(phase, index).also { it.startNanos = nowNanos })
        stableFrames = 0
        pending.clear()
        timedSamples.clear()
        script.clear()
        waitStartNanos = 0L
        cameraOffsetAz = 0f
        cameraOffsetIncl = 0f
    }

    private fun finishPhase(record: PhaseRecord, nowNanos: Long) {
        record.finished = true
        if (phaseIndex + 1 < PHASES.size) {
            enterPhase(phaseIndex + 1, nowNanos)
        } else {
            state = State.COMPLETE
        }
    }

    fun elapsedMs(nowNanos: Long): Long = if (runStartNanos == 0L) 0L else (nowNanos - runStartNanos) / 1_000_000L

    fun progressText(nowNanos: Long): String = when (state) {
        State.IDLE -> "FORENSIC RUN: idle"
        State.RUNNING -> {
            val p = PHASES[phaseIndex]
            val e = elapsedMs(nowNanos) / 1000
            String.format(
                Locale.US, "FORENSIC RUN\nPhase %d/%d: %s %s\n%02d:%02d / ~%02d:%02d\nDO NOT TOUCH",
                phaseIndex + 1, PHASES.size, p.id, p.title, e / 60, e % 60, ESTIMATED_SECONDS / 60, ESTIMATED_SECONDS % 60
            )
        }
        State.COMPLETE -> "FORENSIC RUN COMPLETE\nPress COPY REPORT"
        State.INTERRUPTED -> "FORENSIC RUN INTERRUPTED\n$interruptReason\nTurn DIAG off and on to restart"
    }

    companion object {
        val NO_WORK = Work(WorkKind.NONE)

        /**
         * The renderer's effective per-frame state during a run: the user's state with the phase overrides.
         * Without an active phase (no run, run complete or interrupted) the user's state is returned as is.
         */
        fun effectiveState(
            user: GargantuaRenderState,
            phase: Phase?,
            rebuildGeneration: Int,
            offsetAzDeg: Float = 0f,
            offsetInclDeg: Float = 0f
        ): GargantuaRenderState {
            if (phase == null) return user
            var az = (user.camAzimuthDeg + offsetAzDeg) % 360f
            if (az < 0f) az += 360f
            return user.copy(
                enableAnimation = phase.enableAnimation,
                animationAmplitudePercent = phase.amplitudePercent,
                diagnosticRecordPrecision = phase.precision.ordinal,
                diagnosticRebuildGeneration = rebuildGeneration,
                camAzimuthDeg = if (offsetAzDeg == 0f) user.camAzimuthDeg else az,
                camInclinationDeg = if (offsetInclDeg == 0f) user.camInclinationDeg else (user.camInclinationDeg + offsetInclDeg).coerceIn(5f, 175f)
            )
        }
        const val ESTIMATED_SECONDS = 190L
        const val MAX_MOTION_FRAMES = 600
        /** Camera nudge of the POP probe: changes the ray signature (forces the rebuild) but not the image. */
        const val POP_NUDGE_DEG = 0.001f
        /**
         * A frame gap longer than this interrupts the run (app paused/backgrounded). It must exceed one
         * frame that compiles and links both animation programs and retraces the cache (REC phases force
         * that rebuild); the previous 5 s limit stopped a physical run during REC RB.
         */
        const val INTERRUPT_GAP_NANOS = 30_000_000_000L
        private val SERIES = listOf(0L, 1000L, 2000L)
        private val SINGLE = listOf(0L)
        private val P = GargantuaGpuDiagnostics.RecordPrecision.AS_SHIPPED

        private fun static(id: String, title: String, anim: Boolean, amp: Int) =
            Phase(id, title, anim, amp, P, false, false, 15, 1500L, 12_000L, SINGLE)

        private fun animated(id: String, title: String, amp: Int, precision: GargantuaGpuDiagnostics.RecordPrecision, rebuild: Boolean, lens: Boolean = false) =
            Phase(id, title, true, amp, precision, rebuild, true, 12, 2000L, 30_000L, SERIES, lens = lens)

        private fun moving(id: String, title: String, anim: Boolean, motion: Motion, lens: Boolean = false) =
            Phase(id, title, anim, if (anim) 40 else 0, P, false, anim, 12, 1500L, 30_000L, if (anim) listOf(0L, 1500L, 3000L) else SINGLE, motion, lens)

        /**
         * The matrix, in execution order: the production phases first, the optional REC comparisons last so
         * an interrupted REC phase cannot hide the production result. REC phases rebuild the animation
         * programs even when unchanged.
         */
        val PHASES: List<Phase> = listOf(
            static("A", "baseline, ANIM OFF", false, 0),
            animated("C", "ANIM ON 15%", 15, P, false),
            animated("D", "ANIM ON 40%", 40, P, false, lens = true),
            animated("E", "ANIM ON 80%", 80, P, false),
            moving("M0", "ANIM OFF az drag 40x0.6deg", false, Motion(0.6f, 0f, 40)),
            moving("M1", "ANIM ON az drag 40x0.6deg + pop", true, Motion(0.6f, 0f, 40, pop = true)),
            moving("M2", "ANIM ON incl drag 30x-0.4deg", true, Motion(0f, -0.4f, 30), lens = true),
            moving("M3", "ANIM ON small moves, 150ms gaps", true, Motion(0.15f, 0f, 3, bursts = 6, gapMs = 150L)),
            moving("M4", "ANIM ON small moves, 450ms gaps", true, Motion(0.15f, 0f, 3, bursts = 6, gapMs = 450L)),
            moving("M5", "ANIM ON large az move 30x3deg", true, Motion(3f, 0f, 30), lens = true),
            animated("RA", "REC production @40%", 40, GargantuaGpuDiagnostics.RecordPrecision.AS_SHIPPED, true),
            animated("RB", "REC +highp-int @40%", 40, GargantuaGpuDiagnostics.RecordPrecision.HIGHP_INT, true)
        )
    }
}

/** Plain measurement records produced by the GL executor and consumed by [GargantuaForensicReport]. */
internal object GargantuaForensicData {

    class StageStat(
        val stage: String,
        val texture: Int,
        val w: Int,
        val h: Int,
        val minL: Float,
        val maxL: Float,
        val meanL: Float,
        val nonzero: Long,
        val note: String? = null
    ) {
        val nonzeroFraction: Float get() = if (w > 0 && h > 0) nonzero.toFloat() / (w.toFloat() * h) else 0f
        companion object {
            fun unavailable(stage: String, note: String) = StageStat(stage, 0, 0, 0, 0f, 0f, 0f, 0L, note)
        }
    }

    /** a-vs-b per-pixel comparison, classified by b (shadow: a<=.5 and rgb=0; disk: a>=1 and L>.01). */
    class DiffStat(
        val rms: Float,
        val maxAbs: Float,
        val changed: Long,
        val diskPixels: Long,
        val diskChanged: Long,
        val shadowChanged: Long
    ) {
        val otherChanged: Long get() = changed - diskChanged - shadowChanged
    }

    class Sample(
        val index: Int,
        val tMs: Long,
        val stages: List<StageStat>,
        val recordsValidPixels: Long,
        val hoMean: Float,
        val hoMax: Float,
        val hoPixels: Long,
        val hoAboveHalf: Long,
        val materialVsStatic: DiffStat?,
        val materialVsT0: DiffStat?,
        val finalVsT0: DiffStat?,
        /** Luminance at the fixed probes: P0..P9 disk, S shadow, K sky. */
        val probeStatic: FloatArray?,
        val probeMaterial: FloatArray?,
        val probeFinal: FloatArray?
    )

    class RecordStat(
        val label: String,
        val texture: Int,
        val internalFormat: String,
        val w: Int,
        val h: Int,
        val nonzeroPixels: Long,
        val validPixels: Long,
        val truncatedPixels: Long,
        val tier5Flags: Long,
        val tier9Flags: Long,
        val validCrossings: Long,
        val higherOrderCrossings: Long,
        val emissionInvalidCrossings: Long,
        val rMin: Float, val rMax: Float,
        val phiMin: Float, val phiMax: Float,
        val gMin: Float, val gMax: Float,
        val rawMin: LongArray,
        val rawMax: LongArray,
        /** Crossings (2 per texel) whose words are nonzero but fail the tau-half validity test. */
        val invalidNonzeroCrossings: Long = 0,
        /** Crossings whose two words are both zero (no disk crossing recorded). */
        val zeroCrossings: Long = 0,
        /** Crossings holding only a tier flag (x = 0, y = bit 15): no crossing recorded, not truncated. */
        val flagOnlyCrossings: Long = 0,
        /** First nonzero crossing (x word, y word) and first valid crossing found by the GPU scan; 0 if none. */
        val sampleNonzero: LongArray = LongArray(2),
        val sampleValid: LongArray = LongArray(2)
    ) {
        val zeroPixels: Long get() = w.toLong() * h - nonzeroPixels
        val rawNonzeroCrossings: Long get() = validCrossings + invalidNonzeroCrossings
        val decodedValidCrossings: Long get() = validCrossings - emissionInvalidCrossings
    }

    class RingRow(
        val angleDeg: Int,
        /** OK, NO_SHADOW_BOUNDARY, SHADOW_TO_SCAN_END (insufficient geometry), NO_RING_PEAK, ZERO_RADIANCE. */
        val status: String,
        val startClass: String,
        val boundary: IntArray?,
        val peak: IntArray?,
        val outer: IntArray?,
        val dRpx: Float,
        val ho: Float,
        val total: Float,
        val hoMaxOnLine: Float,
        val hoMaxAt: IntArray?
    )

    class RingResult(
        val rows: List<RingRow>,
        val rightHo: Float,
        val leftHo: Float,
        /** Stage name -> (L(peak), L(outer)) for every OK row, in row order. */
        val perStage: LinkedHashMap<String, List<FloatArray>?>,
        val okAngles: List<Int>
    )

    class BlackResult(
        val captured: Long,
        val unresolved: Long,
        val emptySpace: Long,
        val samplingHole: Long,
        val postProcessing: Long,
        val zeroedBeforeAces: Long,
        val examples: List<String>
    )

    class SamplerBinding(val uniform: String, val unit: Int, val texture: Int)

    /** Pass state read back from GL at the moment right after the draw (not Kotlin bookkeeping). */
    class PassRecord(
        val name: String,
        val program: Int,
        val linked: Boolean,
        val fbo: Int,
        val attachments: IntArray,
        val drawBuffers: IntArray,
        val viewport: IntArray,
        val samplers: List<SamplerBinding>,
        val boundUnits: List<IntArray>
    ) {
        /** Attachment textures behind enabled draw buffers. */
        val writtenTextures: List<Int>
            get() = drawBuffers.toList().mapNotNull { db ->
                val i = db - COLOR_ATTACHMENT0
                if (i in attachments.indices && attachments[i] != 0) attachments[i] else null
            }
        /** A texture sampled by a sampler uniform of the active program while written by this draw. */
        val feedbackSampled: List<Int> get() = samplers.map { it.texture }.filter { it != 0 && it in writtenTextures }.distinct()
        /** Bound to some unit (not referenced by the program's samplers) while written. */
        val feedbackBoundOnly: List<Int>
            get() = boundUnits.map { it[1] }.filter { it != 0 && it in writtenTextures && it !in feedbackSampled }.distinct()

        companion object { const val COLOR_ATTACHMENT0 = 0x8CE0 }
    }

    class FboRecord(val name: String, val id: Int, val status: String, val attachments: List<String>)

    class TextureRecord(val role: String, val texture: Int, val internalFormat: String, val w: Int, val h: Int)

    class ProgramInfo(
        val precision: String,
        val buildVariant: String,
        val materialVariant: String,
        val buildOk: Boolean,
        val materialOk: Boolean,
        val failure: String?,
        val buildProgramId: Int,
        val materialProgramId: Int,
        val intPrecision: String,
        val sampler2DPrecision: String,
        val usamplerPrecision: String,
        val declarations: String
    ) {
        companion object {
            /** Effective precisions as declared in [source] (GLSL ES 3.00 fragment defaults otherwise). */
            fun describe(source: String): Triple<String, String, String> {
                val lines = source.lineSequence().map { it.trim() }.toList()
                val intP = lines.firstOrNull { it.matches(Regex("precision\\s+\\w+\\s+int\\s*;")) }
                    ?.split(Regex("\\s+"))?.getOrNull(1) ?: "mediump (fragment default)"
                val samplerP = lines.firstOrNull { it.matches(Regex("precision\\s+\\w+\\s+sampler2D\\s*;")) }
                    ?.split(Regex("\\s+"))?.getOrNull(1) ?: "lowp (fragment default)"
                val usamplerLine = lines.firstOrNull { it.contains("usampler2D u_RayRecord0") }
                val usamplerP = usamplerLine?.split(Regex("\\s+"))?.getOrNull(1) ?: "n/a"
                return Triple(intP, samplerP, usamplerP)
            }

            fun declarations(source: String): String = source.lineSequence().map { it.trim() }
                .filter { it.startsWith("precision ") }.joinToString(" ")
        }
    }
}
