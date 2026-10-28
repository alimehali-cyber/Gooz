package com.zig.gargantua.renderer

import com.zig.gargantua.renderer.GargantuaForensicData.DiffStat
import com.zig.gargantua.renderer.GargantuaForensicData.PassRecord
import com.zig.gargantua.renderer.GargantuaForensicData.RecordStat
import com.zig.gargantua.renderer.GargantuaForensicData.SamplerBinding
import com.zig.gargantua.renderer.GargantuaForensicData.Sample
import com.zig.gargantua.renderer.GargantuaForensicRun.WorkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The TEMPORARY one-tap forensic run: frame-driven schedule, overrides, termination, recovery and the
 * first-divergence analysis (pure logic; the GL measurements themselves only exist on a device).
 */
class GargantuaForensicRunTest {

    private val frameNanos = 33_000_000L

    /**
     * Minimal model of the renderer's reaction to the effective state: any change of the animation
     * configuration costs one retrace frame (2 geodesic passes); the material pass is presented after
     * [activationFrames] further frames when animation is requested (never when [animationWorks] is false).
     */
    private class FakeRenderer(
        val animationWorks: Boolean = true,
        val activationFrames: Int = 8,
        /** False models the reported bug: after a camera move the animation never presents again. */
        val resumesAfterMove: Boolean = true
    ) {
        private var lastKey: String? = null
        private var lastCamera: String? = null
        private var framesSinceChange = 0
        private var moved = false
        var rebuilds = 0

        fun frame(effective: GargantuaRenderState, now: Long): GargantuaForensicRun.FrameStatus {
            val requested = effective.enableAnimation && effective.animationAmplitudePercent > 0
            val key = "${effective.enableAnimation}/${effective.animationAmplitudePercent}/${effective.diagnosticRecordPrecision}/${effective.diagnosticRebuildGeneration}"
            val camera = "${effective.camAzimuthDeg}/${effective.camInclinationDeg}"
            val cameraChanged = lastCamera != null && camera != lastCamera
            if (cameraChanged) moved = true
            lastCamera = camera
            val changed = key != lastKey || cameraChanged
            if (changed) { lastKey = key; framesSinceChange = 0; if (requested) rebuilds++ } else framesSinceChange++
            val active = requested && animationWorks && framesSinceChange >= activationFrames && (resumesAfterMove || !moved)
            return GargantuaForensicRun.FrameStatus(
                nowNanos = now, animationActive = active, animationRequested = requested,
                geodesicPasses = if (changed) 2 else 0, materialRan = active, bloomRan = true, compositeRan = true,
                rebuilds = rebuilds, gateAction = if (active) "MODULATE" else if (changed && requested) "REBUILD" else "PLAIN",
                animationStatus = if (requested && !animationWorks) "ANIM FAILED: test" else "none",
                camAzDeg = effective.camAzimuthDeg, camInclDeg = effective.camInclinationDeg, signatureChanged = cameraChanged
            )
        }
    }

    private class Trace {
        val works = ArrayList<Pair<String, GargantuaForensicRun.Work>>()
        val configs = LinkedHashSet<String>()
        val workTimes = ArrayList<Long>()
    }

    private fun drive(run: GargantuaForensicRun, renderer: FakeRenderer, user: GargantuaRenderState, maxSeconds: Int, trace: Trace): Long {
        var now = 1_000_000_000L
        run.start(now)
        val end = now + maxSeconds * 1_000_000_000L
        while (now < end && run.state == GargantuaForensicRun.State.RUNNING) {
            run.snapshotOffsets()
            val effective = GargantuaForensicRun.effectiveState(user, run.activePhase(), run.rebuildGeneration, run.cameraOffsetAz, run.cameraOffsetIncl)
            run.activePhase()?.let { trace.configs.add("${it.id}:${effective.enableAnimation}/${effective.animationAmplitudePercent}/${effective.diagnosticRecordPrecision}/${effective.diagnosticRebuildGeneration}") }
            val phaseId = run.activePhase()?.id
            val work = run.onFrame(renderer.frame(effective, now))
            if (work.kind != WorkKind.NONE) {
                trace.works.add(phaseId!! to work)
                trace.workTimes.add(now)
                run.workDone(work, now)
            }
            now += frameNanos
        }
        return now
    }

    @Test
    fun withoutAnActivePhaseTheUserStateIsReturnedUnchanged() {
        val run = GargantuaForensicRun()
        val user = GargantuaRenderState(enableAnimation = true, animationAmplitudePercent = 25, diagnosticView = 7)
        assertNull(run.activePhase())
        assertTrue(user === GargantuaForensicRun.effectiveState(user, run.activePhase(), run.rebuildGeneration))
        assertEquals(GargantuaForensicRun.NO_WORK, run.onFrame(FakeRenderer().frame(user, 1L)))
        assertFalse(run.passCaptureArmed)
    }

    @Test
    fun oneRunExecutesTheWholeMatrixAndTerminates() {
        val run = GargantuaForensicRun()
        val user = GargantuaRenderState(enableAnimation = false, animationAmplitudePercent = 0, diagnosticView = 7)
        val trace = Trace()
        drive(run, FakeRenderer(), user, 300, trace)

        assertEquals(GargantuaForensicRun.State.COMPLETE, run.state)
        assertEquals(listOf("A", "C", "D", "E", "M0", "M1", "M2", "M3", "M4", "M5", "RA", "RB"), run.records.map { it.phase.id })
        assertTrue(run.records.filter { it.failures.isNotEmpty() }.joinToString { "${it.phase.id}: ${it.failures}" }, run.records.all { it.finished && it.failures.isEmpty() })
        // Every static-camera phase: t0 sample, three cache passes, ring, black pixels, pass graph.
        for (id in run.records.filter { it.phase.motion == null }.map { it.phase.id }) {
            val kinds = trace.works.filter { it.first == id }.map { it.second.toString() }
            for (expected in listOf("SAMPLE0", "CACHE0", "CACHE1", "CACHE2", "RING", "BLACK", "GRAPH")) {
                assertTrue("$id missing $expected: $kinds", expected in kinds)
            }
            val animated = run.records.first { it.phase.id == id }.phase.expectsAnimation
            assertEquals("$id t1/t2", animated, "SAMPLE1" in kinds && "SAMPLE2" in kinds)
        }
        // Amplitudes 0/15/40/80 and both REC record precisions were actually applied to the renderer state,
        // and each REC phase used its own rebuild generation.
        val amps = trace.configs.map { it.substringAfter(':').split('/')[1].toInt() }.toSet()
        assertEquals(setOf(0, 15, 40, 80), amps)
        val rec = trace.configs.filter { it.startsWith("R") }.map { it.substringAfter(':').split('/') }
        assertEquals(setOf(0, 1), rec.map { it[2].toInt() }.toSet())
        assertEquals(2, rec.map { it[3] }.toSet().size)
        // Production phases run before the optional REC comparisons.
        assertEquals(listOf("RA", "RB"), run.records.map { it.phase.id }.takeLast(2))
        // Wall-clock time series: t1 >= t0 + 1 s and t2 >= t0 + 2 s, measured from frame timestamps.
        for (id in listOf("C", "D", "E", "RA", "RB")) {
            val idx = trace.works.indices.filter { trace.works[it].first == id }
            fun t(name: String) = trace.workTimes[idx.first { trace.works[it].second.toString() == name }]
            assertTrue(t("SAMPLE1") - t("SAMPLE0") >= 1_000_000_000L)
            assertTrue(t("SAMPLE2") - t("SAMPLE0") >= 2_000_000_000L)
        }
        // Finished: overrides are gone and no more work is requested.
        assertNull(run.activePhase())
        val restored = GargantuaForensicRun.effectiveState(user, run.activePhase(), run.rebuildGeneration)
        assertTrue(restored === user)
        assertTrue(run.progressText(0L).startsWith("FORENSIC RUN COMPLETE\nPress COPY REPORT"))
    }

    @Test
    fun aPhaseWhoseAnimationNeverPresentsIsRecordedAsFailedAndTheRunContinues() {
        val run = GargantuaForensicRun()
        val trace = Trace()
        drive(run, FakeRenderer(animationWorks = false), GargantuaRenderState(diagnosticView = 7), 600, trace)
        assertEquals(GargantuaForensicRun.State.COMPLETE, run.state)
        for (id in listOf("C", "D", "E", "RA", "RB")) {
            val record = run.records.first { it.phase.id == id }
            assertTrue(id, record.failures.single().startsWith("PHASE NOT SETTLED"))
            val kinds = trace.works.filter { it.first == id }.map { it.second.toString() }
            assertTrue("SAMPLE0" in kinds && "SAMPLE1" !in kinds)
        }
        assertTrue(run.records.first { it.phase.id == "A" }.failures.isEmpty())
    }

    @Test
    fun aFrameGapInterruptsTheRunWithoutFurtherOverrides() {
        val run = GargantuaForensicRun()
        val renderer = FakeRenderer()
        val user = GargantuaRenderState(diagnosticView = 7)
        run.start(0L)
        run.onFrame(renderer.frame(GargantuaForensicRun.effectiveState(user, run.activePhase(), 0), frameNanos))
        assertTrue(run.progressText(frameNanos).contains("Phase 1/12"))
        assertTrue(run.progressText(frameNanos).contains("DO NOT TOUCH"))
        // A single long frame (forced program rebuild + cache retrace) does not interrupt the run.
        run.onFrame(renderer.frame(GargantuaForensicRun.effectiveState(user, run.activePhase(), 0), frameNanos + 6_000_000_000L))
        assertEquals(GargantuaForensicRun.State.RUNNING, run.state)
        run.onFrame(renderer.frame(user, frameNanos + 37_000_000_000L))
        assertEquals(GargantuaForensicRun.State.INTERRUPTED, run.state)
        assertNull(run.activePhase())
        assertTrue(run.progressText(0L).startsWith("FORENSIC RUN INTERRUPTED"))
        run.cancel()
        assertEquals(GargantuaForensicRun.State.IDLE, run.state)
    }

    @Test
    fun feedbackIsDerivedFromSamplersAndEnabledDrawBuffersOnly() {
        val c0 = PassRecord.COLOR_ATTACHMENT0
        val sampled = PassRecord("p", 3, true, 9, intArrayOf(41, 0, 0, 0), intArrayOf(c0, 0, 0, 0), intArrayOf(0, 0, 8, 8),
            listOf(SamplerBinding("u_BuiltEmissionTexture", 1, 41)), listOf(intArrayOf(1, 41)))
        assertEquals(listOf(41), sampled.feedbackSampled)
        assertTrue(sampled.feedbackBoundOnly.isEmpty())
        val notDrawn = PassRecord("p", 3, true, 9, intArrayOf(41, 0, 0, 0), intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 8, 8),
            listOf(SamplerBinding("u_BuiltEmissionTexture", 1, 41)), listOf(intArrayOf(1, 41)))
        assertTrue(notDrawn.feedbackSampled.isEmpty())
        val boundOnly = PassRecord("p", 3, true, 9, intArrayOf(41, 0, 0, 0), intArrayOf(c0, 0, 0, 0), intArrayOf(0, 0, 8, 8),
            emptyList(), listOf(intArrayOf(5, 41)))
        assertEquals(listOf(41), boundOnly.feedbackBoundOnly)
    }

    private fun mainFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/$relative", "src/main/$relative").map { File(dir, it) }.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("main source not found: $relative")
    }

    @Test
    fun programDescriptionReportsTheDeclaredPrecisionsOfTheRealAnimationShader() {
        val material = ShaderSource.buildGeodesicVariant(
            mainFile("assets/shaders/gargantua_geodesic.frag").readText(),
            listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS")
        )
        val shipped = GargantuaForensicData.ProgramInfo.describe(material)
        assertEquals("highp", shipped.first)
        assertEquals("lowp (fragment default)", shipped.second)
        assertEquals("highp", shipped.third)
        val high = GargantuaForensicData.ProgramInfo.describe(
            GargantuaGpuDiagnostics.applyRecordPrecision(material, GargantuaGpuDiagnostics.RecordPrecision.HIGHP_INT_SAMPLER)
        )
        assertEquals("highp", high.first)
        assertEquals("highp", high.second)
    }

    private fun recordStat(valid: Long) = RecordStat(
        "r0", 11, "RGBA32UI", 10, 10, 50, if (valid > 0) 40 else 0, if (valid > 0) 10 else 50, 0, 0,
        valid, 0, 0, 3f, 20f, -3f, 3f, 0.5f, 1.5f, LongArray(4), LongArray(4)
    )

    private fun sample(index: Int, diskChangedVsStatic: Long, diskChangedTemporal: Long, finalChanged: Long) = Sample(
        index, index * 1000L, emptyList(), 0, 0f, 0f, 0, 0,
        DiffStat(0.1f, 1f, diskChangedVsStatic, 100, diskChangedVsStatic, 0),
        if (index == 0) null else DiffStat(0.1f, 1f, diskChangedTemporal, 100, diskChangedTemporal, 0),
        if (index == 0) null else DiffStat(0.1f, 1f, finalChanged, 100, finalChanged, 0),
        null, null, null
    )

    @Test
    fun analysisNamesTheFirstFailingStageFromMeasuredDataWithoutClaimingARootCause() {
        val run = GargantuaForensicRun()
        val phases = GargantuaForensicRun.PHASES
        fun add(id: String, valid: Long, animated: Boolean) {
            val record = GargantuaForensicRun.PhaseRecord(phases.first { it.id == id }, run.records.size)
            record.animationActiveAtCapture = true
            record.cache.add(recordStat(valid))
            val changed = if (animated) 50L else 0L
            (0..2).forEach { record.samples.add(sample(it, changed, changed, changed)) }
            run.records.add(record)
        }
        add("D", 0, false)
        add("RA", 0, false)
        add("RB", 250, true)
        val analysis = GargantuaForensicReport.analyse(run)
        assertTrue(analysis.animationStage, analysis.animationStage.startsWith("CACHE GENERATION"))
        assertEquals(true, analysis.cacheDivergence)
        assertEquals(true, analysis.materialDivergence)
        assertEquals(true, analysis.compositeDivergence)
        assertFalse(analysis.feedback)

        val report = GargantuaForensicReport.build(run, "GL test\nMAX_DRAW_BUFFERS=8", "config", 0L)
        for (section in listOf("DEVICE", "GL LIMITS", "TEST CONFIGURATION", "PHASE RESULTS", "ANIMATION A/B/C RESULTS", "CACHE CONTENT",
            "FRAMEBUFFER PROVENANCE", "FEEDBACK HAZARDS", "RING RESULTS", "BLACK PIXEL RESULTS", "PERFORMANCE", "FIRST-DIVERGENCE ANALYSIS", "FINAL CONCLUSION")) {
            assertTrue(section, report.contains("== $section =="))
        }
        assertTrue(report.contains("ROOT CAUSE NOT YET PROVEN"))
        assertTrue(report.contains("FIRST FAILING STAGE (animation) = CACHE GENERATION"))
        assertTrue(report.contains("== CACHE FIX ACCEPTANCE (production phases C/D/E) =="))
        assertTrue(report.contains("A production validCrossing>0: FAIL D=0"))
        assertTrue(report.contains("H M6 reddish feature: VISUAL CONFIRMATION REQUIRED"))
    }

    @Test
    fun acceptancePassesOnlyWithValidRecordsTemporalChangeVaryingProbesAndHigherOrderRecords() {
        val run = GargantuaForensicRun()
        for (id in listOf("C", "D", "E")) {
            val record = GargantuaForensicRun.PhaseRecord(GargantuaForensicRun.PHASES.first { it.id == id }, run.records.size)
            record.animationActiveAtCapture = true
            record.cache.add(RecordStat("r0", 11, "RGBA32UI", 10, 10, 50, 45, 5, 0, 0, 90, 12, 1, 3f, 20f, -3f, 3f, 0.5f, 1.5f,
                LongArray(4), LongArray(4), invalidNonzeroCrossings = 4, zeroCrossings = 106,
                sampleNonzero = longArrayOf(0x1234L, 0x3C00L), sampleValid = longArrayOf(0x80004000L, 0x36663E00L)))
            (0..2).forEach { k ->
                val probes = FloatArray(12) { 0.2f + 0.01f * k * (it % 3) }
                record.samples.add(Sample(k, k * 1000L, emptyList(), 0, 0f, 0f, 0, 0,
                    DiffStat(0.1f, 1f, 40, 100, 30, 0),
                    if (k == 0) null else DiffStat(0.1f, 1f, 20, 100, 20, 0),
                    if (k == 0) null else DiffStat(0.1f, 1f, 20, 100, 20, 0),
                    probes, probes, probes))
            }
            run.records.add(record)
        }
        val lines = GargantuaForensicReport.acceptance(run)
        assertTrue(lines.joinToString("\n"), lines.filter { !it.startsWith("H ") }.all { it.contains(": PASS") })
        val c = run.records[0].cache[0]
        assertEquals(94L, c.rawNonzeroCrossings)
        assertEquals(89L, c.decodedValidCrossings)
        assertTrue(GargantuaForensicReport.words(c.sampleNonzero).contains("INVALID: tau half is 0"))
        assertTrue(GargantuaForensicReport.words(c.sampleValid).endsWith("valid)"))
        // Static probes (zero delta) fail E even when everything else passes.
        run.records.forEach { r -> r.samples.replaceAll { s -> Sample(s.index, s.tMs, emptyList(), 0, 0f, 0f, 0, 0, s.materialVsStatic, s.materialVsT0,
            s.finalVsT0, FloatArray(12) { 0.2f }, FloatArray(12) { 0.2f }, FloatArray(12) { 0.2f }) } }
        assertTrue(GargantuaForensicReport.acceptance(run).filter { it.startsWith("E ") }.all { it.contains(": FAIL") })
    }

    @Test
    fun analysisSeparatesMaterialAndDownstreamFailures() {
        val run = GargantuaForensicRun()
        val record = GargantuaForensicRun.PhaseRecord(GargantuaForensicRun.PHASES.first { it.id == "D" }, 0)
        record.animationActiveAtCapture = true
        record.cache.add(recordStat(100))
        (0..2).forEach { record.samples.add(sample(it, 30, 30, 0)) }
        run.records.add(record)
        assertTrue(GargantuaForensicReport.analyse(run).animationStage.startsWith("DOWNSTREAM OF MATERIAL"))
        record.samples.clear()
        (0..2).forEach { record.samples.add(sample(it, 0, 0, 0)) }
        assertTrue(GargantuaForensicReport.analyse(run).animationStage.startsWith("MATERIAL PASS"))
    }

    @Test
    fun motionPhasesMoveOnlyTheEffectiveCameraAndMeasureTheRestartAfterRelease() {
        val run = GargantuaForensicRun()
        val user = GargantuaRenderState(camAzimuthDeg = 10f, camInclinationDeg = 80f, diagnosticView = 7)
        val trace = Trace()
        drive(run, FakeRenderer(), user, 400, trace)
        assertEquals(GargantuaForensicRun.State.COMPLETE, run.state)
        val m1 = run.records.first { it.phase.id == "M1" }
        // POP0, POP1 and POP2 in three consecutive frames (POP2 is the retrace caused by the nudge), POP3 at the restart.
        val idx = trace.works.indices.filter { trace.works[it].first == "M1" && trace.works[it].second.kind == WorkKind.POP }
        assertEquals(listOf("POP0", "POP1", "POP2", "POP3"), idx.map { trace.works[it].second.toString() })
        assertEquals(frameNanos, trace.workTimes[idx[1]] - trace.workTimes[idx[0]])
        assertEquals(frameNanos, trace.workTimes[idx[2]] - trace.workTimes[idx[1]])
        val popFrame = m1.motionTrace.first { it.offsetAz == GargantuaForensicRun.POP_NUDGE_DEG }
        assertEquals("REBUILD", popFrame.gate)
        // The renderer's camera equals user + scripted offset in every traced frame; the script ends at 40 x 0.6 deg.
        for (t in m1.motionTrace) assertEquals(10f + t.offsetAz, t.camAz, 1e-4f)
        assertEquals(40 * 0.6f + GargantuaForensicRun.POP_NUDGE_DEG, m1.motionTrace.last().offsetAz, 1e-3f)
        val restart = m1.restart!!
        assertTrue(restart.restarted)
        // FakeRenderer presents animation 8 frames after the last change.
        assertEquals(8 * frameNanos / 1e6f, restart.firstModulateMs, 0.5f)
        // Gaps: M3 waits >= 150 ms between bursts, M4 >= 450 ms.
        for ((id, gap) in listOf("M3" to 150f, "M4" to 450f)) {
            val r = run.records.first { it.phase.id == id }
            val g = r.motionTrace.filter { it.stage == 'G' }
            assertTrue(id, g.isNotEmpty())
            val moves = r.motionTrace.map { it.offsetAz }.distinct()
            assertEquals(id, 18 + 1, moves.size) // 6 bursts x 3 moves, plus the unmoved first frame
            val runs = r.motionTrace.zipWithNext().filter { (a, b) -> a.stage == 'G' && b.stage == 'S' }.size
            assertEquals(id, 5, runs)
            assertTrue(id, g.last().tMs - g.first().tMs >= gap)
        }
        // Inclination phase moved the inclination only; ANIM OFF phase has no restart expectation.
        val m2 = run.records.first { it.phase.id == "M2" }
        assertEquals(-12f, m2.motionTrace.last().offsetIncl, 1e-3f)
        assertEquals(0f, m2.motionTrace.last().offsetAz, 0f)
        // Offsets are reset between phases and after the run: the user's camera is never written.
        assertEquals(0f, run.cameraOffsetAz, 0f)
        val report = GargantuaForensicReport.motion(run)
        assertTrue(report.joinToString("\n"), report.last().startsWith("RESTART INVARIANT (animation resumes without toggling): PASS in 5 phases"))
        assertTrue(report.any { it.contains("POP: last D2 -> retraced frame") })
    }

    @Test
    fun animationThatDoesNotResumeAfterAMoveFailsTheRestartInvariant() {
        val run = GargantuaForensicRun()
        val trace = Trace()
        drive(run, FakeRenderer(resumesAfterMove = false), GargantuaRenderState(diagnosticView = 7), 900, trace)
        assertEquals(GargantuaForensicRun.State.COMPLETE, run.state)
        val m1 = run.records.first { it.phase.id == "M1" }
        assertEquals(false, m1.restart!!.restarted)
        assertTrue(m1.failures.toString(), m1.failures.any { it.startsWith("ANIMATION DID NOT RESTART within 8000 ms after release") })
        assertTrue(m1.restart!!.plainFrames > 0)
        val verdict = GargantuaForensicReport.motion(run).last()
        assertTrue(verdict, verdict.contains("FAIL in M1"))
        // The ANIM OFF motion phase has no restart failure.
        assertTrue(run.records.first { it.phase.id == "M0" }.failures.none { it.startsWith("ANIMATION DID NOT RESTART") })
    }

    @Test
    fun effectiveStateAppliesCameraOffsetsWithWrapAndClamp() {
        val phase = GargantuaForensicRun.PHASES.first { it.id == "M1" }
        val user = GargantuaRenderState(camAzimuthDeg = 350f, camInclinationDeg = 170f)
        val e = GargantuaForensicRun.effectiveState(user, phase, 0, 20f, 10f)
        assertEquals(10f, e.camAzimuthDeg, 1e-4f)
        assertEquals(175f, e.camInclinationDeg, 0f)
        assertEquals(350f, user.camAzimuthDeg, 0f)
        val same = GargantuaForensicRun.effectiveState(user, phase, 0)
        assertEquals(user.camAzimuthDeg, same.camAzimuthDeg, 0f)
    }
}
