package com.zig.gargantua.renderer

import com.zig.gargantua.renderer.GargantuaForensicData.DiffStat
import com.zig.gargantua.renderer.GargantuaForensicData.PassRecord
import com.zig.gargantua.renderer.GargantuaForensicData.Sample
import java.util.Locale

/**
 * TEMPORARY: formats a finished [GargantuaForensicRun] into the compact COPY REPORT and derives the
 * first-divergence analysis purely from the recorded GPU measurements (no GL, JVM-testable).
 */
internal object GargantuaForensicReport {

    class Analysis(
        val animationStage: String,
        val ringStage: String,
        val blackStage: String,
        val cacheDivergence: Boolean?,
        val materialDivergence: Boolean?,
        val compositeDivergence: Boolean?,
        val feedback: Boolean,
        val feedbackPasses: List<String>,
        val boundOnlyPasses: List<String>,
        val evidence: List<String>
    )

    private val RIGHT_ANGLES = setOf(15, 45, 315, 345)
    private val LEFT_ANGLES = setOf(135, 165, 195, 225)
    private const val CONTRAST_VISIBLE = 1.05f

    private fun g(v: Float): String = if (v.isNaN()) "nan" else String.format(Locale.US, "%.4g", v)
    private fun pct(v: Float): String = String.format(Locale.US, "%.1f%%", v * 100f)

    fun record(run: GargantuaForensicRun, id: String) = run.records.firstOrNull { it.phase.id == id }

    fun totalValidCrossings(r: GargantuaForensicRun.PhaseRecord?): Long? =
        r?.cache?.takeIf { it.isNotEmpty() }?.sumOf { it.validCrossings }

    fun materialAnimated(r: GargantuaForensicRun.PhaseRecord?): Boolean? {
        if (r == null || r.samples.isEmpty()) return null
        val temporal = r.samples.mapNotNull { it.materialVsT0 }
        val vsStatic = r.samples.mapNotNull { it.materialVsStatic }
        if (temporal.isEmpty() && vsStatic.isEmpty()) return null
        return temporal.any { it.diskChanged > 0 } || vsStatic.any { it.diskChanged > 0 }
    }

    fun finalAnimated(r: GargantuaForensicRun.PhaseRecord?): Boolean? {
        val temporal = r?.samples?.mapNotNull { it.finalVsT0 } ?: return null
        if (temporal.isEmpty()) return null
        return temporal.any { it.changed > 0 }
    }

    private fun diverges(a: Long?, b: Long?): Boolean? {
        if (a == null || b == null) return null
        if ((a == 0L) != (b == 0L)) return true
        return kotlin.math.abs(a - b).toDouble() / maxOf(a, b, 1L) > 0.10
    }

    fun analyse(run: GargantuaForensicRun): Analysis {
        val evidence = ArrayList<String>()
        val production = record(run, "D")?.takeIf { it.samples.isNotEmpty() } ?: record(run, "RA")
        val a = record(run, "RA")
        val b = record(run, "RB")

        // Animation: walk the production pipeline in order and stop at the first stage that fails.
        val animationStage = if (production == null) {
            "UNDETERMINED (no production animation phase recorded)"
        } else {
            val valid = totalValidCrossings(production)
            val material = materialAnimated(production)
            val final = finalAnimated(production)
            evidence.add("production ${production.phase.id}: animActive=${production.animationActiveAtCapture} validCrossings=$valid materialAnimated=$material finalAnimated=$final status=${production.animationStatusAtCapture}")
            when {
                !production.animationActiveAtCapture -> "ANIMATION PASS NOT PRESENTED (gate=${production.gateActionAtCapture}, status=${production.animationStatusAtCapture})"
                valid == null -> "UNDETERMINED (cache records not measured)"
                valid == 0L -> "CACHE GENERATION (animation build pass stored 0 valid crossings in all ${production.cache.size} record textures)"
                material == false -> "MATERIAL PASS (records hold valid crossings but the material output does not change)"
                final == false -> "DOWNSTREAM OF MATERIAL (material output changes, final framebuffer does not)"
                material == true && final == true -> "NONE (animation reaches the final framebuffer)"
                else -> "UNDETERMINED (material=$material final=$final)"
            }
        }
        for ((name, r) in listOf("RA production" to a, "RB +highp-int" to b)) {
            if (r == null) continue
            evidence.add("$name: programs=${r.programs?.let { "build=${it.buildOk} material=${it.materialOk}" } ?: "n/a"} validCrossings=${totalValidCrossings(r)} " +
                "materialAnimated=${materialAnimated(r)} finalAnimated=${finalAnimated(r)} animActive=${r.animationActiveAtCapture}")
        }
        val cacheDiv = diverges(totalValidCrossings(a), totalValidCrossings(b))
        val materialDiv = if (materialAnimated(a) == null || materialAnimated(b) == null) null else materialAnimated(a) != materialAnimated(b)
        val compositeDiv = if (finalAnimated(a) == null || finalAnimated(b) == null) null else finalAnimated(a) != finalAnimated(b)

        // Ring: baseline D0 right/left higher-order radiance, then the first stage losing right-side peak contrast.
        val ring = record(run, "A")?.ring
        val ringStage = if (ring == null) {
            "UNDETERMINED (ring scan not recorded)"
        } else {
            val rightRows = ring.rows.filter { it.angleDeg in RIGHT_ANGLES }
            evidence.add("ring D0: HO(a-1) max-on-line right=${g(ring.rightHo)} left=${g(ring.leftHo)}; statuses " +
                ring.rows.joinToString(" ") { "${it.angleDeg}:${it.status}" })
            if (ring.leftHo > 0f && ring.rightHo <= 0.1f * ring.leftHo) {
                "GEODESIC HDR D0 (right-side higher-order radiance absent: right/left=${g(ring.rightHo / ring.leftHo)}) - Case A"
            } else {
                val okRight = rightRows.filter { it.status == "OK" }.map { it.angleDeg }
                if (okRight.isEmpty()) {
                    "UNDETERMINED (no right-side sector with shadow boundary + peak: " + rightRows.joinToString(" ") { "${it.angleDeg}:${it.status}" } + ")"
                } else {
                    val contrast = LinkedHashMap<String, Float>()
                    for ((stage, pairs) in ring.perStage) {
                        if (pairs == null) continue
                        val ratios = ring.okAngles.indices.filter { ring.okAngles[it] in okRight }
                            .map { val p = pairs[it]; if (p[1] > 0f) p[0] / p[1] else Float.NaN }.filter { !it.isNaN() }
                        if (ratios.isNotEmpty()) contrast[stage] = ratios.average().toFloat()
                    }
                    evidence.add("ring right-side L(peak)/L(outer) per stage: " + contrast.entries.joinToString(" ") { "${it.key}=${g(it.value)}" })
                    val order = listOf("D0", "D2", "D5", "D6").filter { it in contrast }
                    val lost = order.firstOrNull { contrast.getValue(it) < CONTRAST_VISIBLE }
                    when {
                        order.isEmpty() -> "UNDETERMINED (no stage probes)"
                        lost == null -> "NONE (right-side peak contrast >= $CONTRAST_VISIBLE through ${order.last()})"
                        lost == "D0" -> "GEODESIC HDR D0 (right-side peak not above its outer neighbour) - Case A"
                        lost == "D2" -> "MATERIAL PASS D2 - Case B"
                        lost == "D5" -> "COMPOSITE BEFORE ACES D5 (sharpen/bloom/exposure) - Case B"
                        else -> "ACES/GAMMA D6 (contrast present pre-ACES, lost in final) - Case C"
                    }
                }
            }
        }

        val black = record(run, "A")?.black
        val blackStage = if (black == null) {
            "UNDETERMINED (black classification not recorded)"
        } else {
            val candidates = listOf(
                "GEODESIC (B unresolved)" to black.unresolved,
                "RAY SAMPLING (D sampling hole)" to black.samplingHole,
                "COMPOSITE POST-PROCESSING (E, zeroed before ACES=${black.zeroedBeforeAces})" to black.postProcessing
            )
            val worst = candidates.maxByOrNull { it.second }
            if (worst == null || worst.second == 0L) "NONE (all black final pixels are captured/empty-space in D0)" else "${worst.first}: ${worst.second} px"
        }

        val feedbackPasses = ArrayList<String>()
        val boundOnly = ArrayList<String>()
        for (r in run.records) for (p in r.passes.values) {
            if (p.feedbackSampled.isNotEmpty()) feedbackPasses.add("${r.phase.id}:${p.name} tex${p.feedbackSampled}")
            if (p.feedbackBoundOnly.isNotEmpty()) boundOnly.add("${r.phase.id}:${p.name} tex${p.feedbackBoundOnly}")
        }
        return Analysis(animationStage, ringStage, blackStage, cacheDiv, materialDiv, compositeDiv,
            feedbackPasses.isNotEmpty(), feedbackPasses.distinct(), boundOnly.distinct(), evidence)
    }

    private fun diffText(d: DiffStat?): String = if (d == null) "n/a" else
        "rms=${g(d.rms)} max=${g(d.maxAbs)} ch=${d.changed} disk=${d.diskChanged}/${d.diskPixels} shadow=${d.shadowChanged} other=${d.otherChanged}"

    fun build(run: GargantuaForensicRun, caps: String, config: String, nowNanos: Long): String {
        val sb = StringBuilder()
        fun line(s: String = "") { sb.append(s).append('\n') }
        fun section(s: String) { line(); line("== $s ==") }

        line("GARGANTUA FORENSIC REPORT (TEMPORARY DIAGNOSTICS) state=${run.state}${if (run.interruptReason.isNotEmpty()) " (${run.interruptReason})" else ""} elapsed=${run.elapsedMs(nowNanos) / 1000}s")
        val capLines = caps.lines()
        section("CACHE FIX ACCEPTANCE (production phases C/D/E)")
        acceptance(run).forEach { line(it) }
        section("CAMERA MOTION + ANIMATION RESTART (scripted, phases M0-M5)")
        motion(run).forEach { line(it) }
        section("LENSING / SHADOW / PHOTON RING (geodesic classification, phases D, M2, M5)")
        for (r in run.records) {
            val lens = r.lensResult ?: continue
            line(GargantuaLensAnalysis.text(lens, "${r.phase.id} ${r.phase.title}").trimEnd())
        }
        if (run.records.none { it.lensResult != null }) line("no LENS capture recorded")
        section("DEVICE")
        capLines.take(1).forEach { line(it) }
        section("GL LIMITS")
        capLines.drop(1).forEach { line(it) }
        section("TEST CONFIGURATION")
        line(config)
        line("phases: " + run.phases.joinToString(" | ") { "${it.id}=${it.title}" })
        line("gating: stable = no geodesic pass (+ material pass presented for ANIM phases); samples t0,+1s,+2s for ANIM phases")

        section("PHASE RESULTS")
        for (r in run.records) {
            val settleS = if (r.settledNanos != 0L) (r.settledNanos - r.startNanos) / 1.0e9 else Double.NaN
            line(String.format(Locale.US, "%s %s: %s settle=%.1fs frames=%d rebuilds+%d animActive=%s gate=%s status=%s",
                r.phase.id, r.phase.title, if (r.failures.isEmpty()) "OK" else "PHASE FAILED", settleS, r.perf.frames,
                maxOf(0, r.perf.rebuildsAtEnd - maxOf(0, r.perf.rebuildsAtStart)), r.animationActiveAtCapture, r.gateActionAtCapture, r.animationStatusAtCapture))
            r.failures.forEach { line("  ! $it") }
        }
        val stageNames = listOf("D0", "D1", "D2", "D3", "D4", "D5", "D6")
        line("stage (t0)  meanL / maxL / nonzero% per phase:")
        for (stage in stageNames) {
            line("  $stage " + run.records.joinToString(" | ") { r ->
                val s = r.samples.firstOrNull()?.stages?.firstOrNull { it.stage == stage }
                "${r.phase.id}:" + when {
                    s == null -> "-"
                    s.note != null -> s.note
                    else -> "${g(s.meanL)}/${g(s.maxL)}/${pct(s.nonzeroFraction)}"
                }
            })
        }
        line("  D7 HO/total mean/max/px(HO>50%) " + run.records.joinToString(" | ") { r ->
            val s = r.samples.firstOrNull()
            "${r.phase.id}:" + if (s == null) "-" else "${g(s.hoMean)}/${g(s.hoMax)}/${s.hoAboveHalf}"
        })

        section("ANIMATION A/B/C RESULTS")
        line("phase amp rec | t | D2-D0 | D2(t)-D2(t0) | D6(t)-D6(t0)")
        for (r in run.records.filter { it.phase.expectsAnimation || it.phase.enableAnimation }) {
            if (r.samples.isEmpty()) { line("${r.phase.id} ${r.phase.amplitudePercent}% ${r.phase.precision.label}: no samples"); continue }
            for (s in r.samples) {
                line("${r.phase.id} ${r.phase.amplitudePercent}% ${r.phase.precision.label} | t${s.index}=${s.tMs}ms | ${diffText(s.materialVsStatic)} | ${diffText(s.materialVsT0)} | ${diffText(s.finalVsT0)}")
            }
        }
        val ordering = listOf("C", "D", "E").map { id -> record(run, id)?.samples?.firstOrNull()?.materialVsStatic?.rms ?: 0f }
        line("RMS(D2-D0) t0 amp 15/40/80 = ${ordering.joinToString("/") { g(it) }} ordering 80>40>15: ${ordering[2] > ordering[1] && ordering[1] > ordering[0]}")
        for (id in listOf("C", "D", "E", "RB")) {
            val r = record(run, id) ?: continue
            if (r.samples.size < 3) continue
            line("probes ${r.phase.id} (${r.phase.amplitudePercent}% ${r.phase.precision.label}): name static | D2 t0 t1 t2 | |L1-L0| |L2-L1| | D6 t0 t1 t2")
            val n = r.samples[0].probeMaterial?.size ?: 0
            for (i in 0 until n) {
                val name = if (i < GargantuaGpuDiagnostics.DISK_PROBES) "P$i" else if (i == GargantuaGpuDiagnostics.DISK_PROBES) "S" else "K"
                fun m(k: Int) = r.samples[k].probeMaterial?.getOrNull(i) ?: Float.NaN
                fun f(k: Int) = r.samples[k].probeFinal?.getOrNull(i) ?: Float.NaN
                line(" $name ${g(r.samples[0].probeStatic?.getOrNull(i) ?: Float.NaN)} | ${g(m(0))} ${g(m(1))} ${g(m(2))} | ${g(kotlin.math.abs(m(1) - m(0)))} ${g(kotlin.math.abs(m(2) - m(1)))} | ${g(f(0))} ${g(f(1))} ${g(f(2))}")
            }
        }
        for (r in run.records.filter { it.phase.id.startsWith("R") }) {
            val p = r.programs
            if (p == null) { line("${r.phase.id} programs: not observed"); continue }
            line("${r.phase.id} precision=${p.precision} int=${p.intPrecision} sampler2D=${p.sampler2DPrecision} usampler2D(records)=${p.usamplerPrecision} " +
                "build=${p.buildVariant}(ok=${p.buildOk},id=${p.buildProgramId}) material=${p.materialVariant}(ok=${p.materialOk},id=${p.materialProgramId})" +
                (p.failure?.let { " FAILURE: $it" } ?: ""))
            line("   declarations: ${p.declarations}")
        }

        section("CACHE CONTENT")
        line("record textures (RGBA32UI, 2 crossings/texel): nonzeroPx zeroPx validPx truncatedPx | validX higherOrderX emissionInvalidX tier5 tier9")
        for (r in run.records.filter { it.cache.isNotEmpty() && it.phase.enableAnimation }) {
            line("${r.phase.id} (${r.phase.precision.label}) total validX=${r.cache.sumOf { it.validCrossings }} validPx=${r.cache.sumOf { it.validPixels }} truncatedPx=${r.cache.sumOf { it.truncatedPixels }}")
            line("  crossings: rawNonzero=${r.cache.sumOf { it.rawNonzeroCrossings }} validCrossing=${r.cache.sumOf { it.validCrossings }} " +
                "invalid/sentinel=${r.cache.sumOf { it.invalidNonzeroCrossings }} (of which tier-flag-only=${r.cache.sumOf { it.flagOnlyCrossings }}) zero=${r.cache.sumOf { it.zeroCrossings }} " +
                "decodedValid=${r.cache.sumOf { it.decodedValidCrossings }} decodedInvalid=${r.cache.sumOf { it.emissionInvalidCrossings }} " +
                "higherOrder=${r.cache.sumOf { it.higherOrderCrossings }}")
            r.cache.firstOrNull()?.let { c -> line("  ${c.label} sample words: nonzero ${words(c.sampleNonzero)} valid ${words(c.sampleValid)}") }
            for (c in r.cache) {
                line("  ${c.label} tex${c.texture} ${c.internalFormat} ${c.w}x${c.h}: ${c.nonzeroPixels} ${c.zeroPixels} ${c.validPixels} ${c.truncatedPixels} | ${c.validCrossings} ${c.higherOrderCrossings} ${c.emissionInvalidCrossings} ${c.tier5Flags} ${c.tier9Flags}")
            }
            r.cache.firstOrNull()?.let { c ->
                line("  ${c.label} decoded r[${g(c.rMin)},${g(c.rMax)}]M phi[${g(c.phiMin)},${g(c.phiMax)}]rad g[${g(c.gMin)},${g(c.gMax)}] raw x[${c.rawMin[0]},${c.rawMax[0]}] y[${c.rawMin[1]},${c.rawMax[1]}] z[${c.rawMin[2]},${c.rawMax[2]}] w[${c.rawMin[3]},${c.rawMax[3]}]")
            }
            r.cacheFloat.forEach { s -> line("  ${s.stage} tex${s.texture} ${s.w}x${s.h}: " + (s.note ?: "minL=${g(s.minL)} maxL=${g(s.maxL)} meanL=${g(s.meanL)} nonzero=${s.nonzero}")) }
        }

        section("FRAMEBUFFER PROVENANCE")
        val prov = run.records.lastOrNull { it.passes.isNotEmpty() && it.animationActiveAtCapture } ?: run.records.lastOrNull { it.passes.isNotEmpty() }
        if (prov != null) {
            line("from phase ${prov.phase.id}: role tex internalFormat WxH <- written by pass(fbo,Cn@drawbuffer) -> read by pass(uniform@unit)")
            for (t in prov.textures) {
                val writers = prov.passes.values.mapNotNull { p ->
                    val idx = p.attachments.indexOf(t.texture)
                    if (t.texture == 0 || idx < 0) null else {
                        val db = p.drawBuffers.indexOf(PassRecord.COLOR_ATTACHMENT0 + idx)
                        "${p.name}(fbo${p.fbo},C$idx@${if (db >= 0) "db$db" else "not-drawn"})"
                    }
                }
                val readers = prov.passes.values.flatMap { p -> p.samplers.filter { it.texture == t.texture && t.texture != 0 }.map { "${p.name}(${it.uniform}@u${it.unit})" } }
                line("  ${t.role} tex${t.texture} ${t.internalFormat} ${t.w}x${t.h} <- ${writers.ifEmpty { listOf("-") }.joinToString(",")} -> ${readers.ifEmpty { listOf("-") }.joinToString(",")}")
            }
            line("FBOs:")
            prov.fbos.forEach { f -> line("  ${f.name}(id${f.id}) ${f.status} [${f.attachments.joinToString(" ")}]") }
        } else {
            line("no pass graph recorded")
        }

        section("FEEDBACK HAZARDS")
        if (prov != null) {
            line("pass graph (phase ${prov.phase.id}, GL state read right after each draw):")
            for (p in prov.passes.values) {
                val verdict = when {
                    p.feedbackSampled.isNotEmpty() -> "FEEDBACK(sampled tex${p.feedbackSampled})"
                    p.feedbackBoundOnly.isNotEmpty() -> "SAFE-SAMPLERS / bound-only tex${p.feedbackBoundOnly}"
                    else -> "SAFE"
                }
                line("  ${p.name}: prog${p.program}${if (p.linked) "" else "(NOT LINKED)"} fbo${p.fbo} att[${p.attachments.joinToString(",")}] draw[${p.drawBuffers.joinToString(",") { drawName(it) }}] " +
                    "vp${p.viewport.joinToString(",")} samplers[${p.samplers.joinToString(" ") { "${it.uniform}@u${it.unit}=${it.texture}" }}] -> $verdict")
            }
        }
        val analysis = analyse(run)
        line("sampled feedback (all phases): ${analysis.feedbackPasses.ifEmpty { listOf("none") }.joinToString("; ")}")
        line("bound-only overlap (all phases): ${analysis.boundOnlyPasses.ifEmpty { listOf("none") }.joinToString("; ")}")

        section("RING RESULTS")
        record(run, "A")?.ring?.let { ring ->
            line("phase A, D0, 12 lines from st centre (${GargantuaGpuDiagnostics.RING_CENTER_ST[0]},${GargantuaGpuDiagnostics.RING_CENTER_ST[1]}), GL angles (y up):")
            for (row in ring.rows) {
                line(String.format(Locale.US, "  %3ddeg %-18s start=%s boundary=%s peak=%s dR=%.2fpx HO=%s total=%s HO/total=%s | HOmax-on-line=%s at %s",
                    row.angleDeg, row.status, row.startClass, row.boundary?.joinToString(",") ?: "-", row.peak?.joinToString(",") ?: "-",
                    row.dRpx, g(row.ho), g(row.total), g(if (row.total > 1e-4f) row.ho / row.total else 0f), g(row.hoMaxOnLine), row.hoMaxAt?.joinToString(",") ?: "-"))
            }
            line("  aggregate HOmax-on-line right(15,45,315,345)=${g(ring.rightHo)} left(135,165,195,225)=${g(ring.leftHo)} right/left=${g(if (ring.leftHo > 0) ring.rightHo / ring.leftHo else 0f)}")
            line("  per stage L(peak)/L(outer) for OK sectors ${ring.okAngles}:")
            for ((stage, pairs) in ring.perStage) {
                line("   $stage: " + (pairs?.joinToString(" ") { "${g(it[0])}/${g(it[1])}" } ?: "n/a"))
            }
        } ?: line("phase A ring not recorded")
        for (r in run.records.filter { it.phase.id != "A" && it.ring != null }) {
            val ring = r.ring!!
            line("${r.phase.id}: right=${g(ring.rightHo)} left=${g(ring.leftHo)} OK=${ring.okAngles}")
        }

        section("BLACK PIXEL RESULTS")
        line("final px max<3/255 by D0 provenance: A captured | B unresolved | C empty-space | D sampling-hole | E post-processing (zeroed before ACES)")
        for (r in run.records) {
            val b = r.black ?: continue
            line("${r.phase.id}: ${b.captured} | ${b.unresolved} | ${b.emptySpace} | ${b.samplingHole} | ${b.postProcessing} (${b.zeroedBeforeAces})")
        }
        record(run, "A")?.black?.examples?.forEach { line("  $it") }

        section("PERFORMANCE")
        line("NORMAL-PATH FPS = frames without diagnostic GPU work (timer queries + GL state queries still active); DIAGNOSTIC FPS includes readback frames")
        for (r in run.records) {
            val p = r.perf
            line(String.format(Locale.US, "%s: normalFPS=%.1f (%.1fms) diagFPS=%.1f frames=%d geodesicPasses=%d framesWithGeodesic=%d modulateFrames=%d geodesicPassesInModulate=%d material=%d bloom=%d composite=%d rebuilds+%d GPUms[%s]",
                r.phase.id, p.idleFps, p.idleFrameMs, p.diagFps, p.frames, p.geodesicPasses, p.framesWithGeodesic, p.modulateFrames, p.geodesicPassesInModulateFrames,
                p.materialFrames, p.bloomFrames, p.compositeFrames, maxOf(0, p.rebuildsAtEnd - maxOf(0, p.rebuildsAtStart)),
                p.gpuMs.entries.joinToString(" ") { "${it.key}=${g(it.value)}" }))
        }
        line("RK4 steps/frame: no GPU counter; a MODULATE frame with 0 geodesic passes integrates 0 RK4 steps (per-frame retrace = geodesicPassesInModulate)")

        section("FIRST-DIVERGENCE ANALYSIS")
        line("Animation: first divergent stage = ${analysis.animationStage}")
        line("Ring: first divergent stage = ${analysis.ringStage}")
        line("Black pixels: first divergent stage = ${analysis.blackStage}")
        line("Precision: cache divergence = ${yn(analysis.cacheDivergence)}, material divergence = ${yn(analysis.materialDivergence)}, composite divergence = ${yn(analysis.compositeDivergence)}")
        line("Feedback: physical feedback hazard (sampler of active program == enabled draw attachment) = ${if (analysis.feedback) "YES" else "NO"}")
        analysis.evidence.forEach { line("  - $it") }

        section("FINAL CONCLUSION")
        line("ROOT CAUSE NOT YET PROVEN")
        line("FIRST FAILING STAGE (animation) = ${analysis.animationStage}")
        line("FIRST FAILING STAGE (ring) = ${analysis.ringStage}")
        line("FIRST FAILING STAGE (black pixels) = ${analysis.blackStage}")
        return sb.toString()
    }

    /**
     * Per motion phase: the scripted camera, the state the renderer actually used, gates and passes per
     * frame, frame pacing, the release -> restart sequence and the POP measurements.
     */
    fun motion(run: GargantuaForensicRun): List<String> {
        val out = ArrayList<String>()
        fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
        val baseline = run.records.firstOrNull { it.popBaseline != null }?.popBaseline
        for (r in run.records.filter { it.phase.motion != null }) {
            val m = r.phase.motion!!
            val tr = r.motionTrace
            val script = tr.filter { it.stage == 'S' || it.stage == 'G' }
            val moving = tr.filter { it.stage == 'S' }
            val dts = moving.zipWithNext { a, b -> (b.tMs - a.tMs).toDouble() }
            val mean = dts.average().takeIf { !it.isNaN() } ?: 0.0
            val cv = if (dts.size > 1 && mean > 0) kotlin.math.sqrt(dts.map { (it - mean) * (it - mean) }.average()) / mean else 0.0
            val first = tr.firstOrNull()
            // State stage: the renderer's camera must equal the user camera (first frame) + the offset in effect.
            val baseAz = (first?.camAz ?: 0f) - (first?.offsetAz ?: 0f)
            val baseIncl = (first?.camIncl ?: 0f) - (first?.offsetIncl ?: 0f)
            var stateErr = 0f
            for (t in tr) {
                var d = t.camAz - (baseAz + t.offsetAz); while (d > 180f) d -= 360f; while (d < -180f) d += 360f
                stateErr = maxOf(stateErr, kotlin.math.abs(d), kotlin.math.abs(t.camIncl - (baseIncl + t.offsetIncl).coerceIn(5f, 175f)))
            }
            val steps = moving.zipWithNext { a, b -> (b.camAz - a.camAz) + (b.camIncl - a.camIncl) }
            val dir = if (m.dAzDeg + m.dInclDeg >= 0f) 1f else -1f
            val back = steps.count { it * dir < -1e-4f }
            val rebuildMs = moving.filter { it.geodesicPasses > 0 }.map { it.cpuMs.toDouble() }
            out.add("${r.phase.id} ${r.phase.title}: frames script=${script.size} (move ${moving.size}) gates=" +
                moving.groupingBy { it.gate }.eachCount() + " geodesicPasses=${moving.sumOf { it.geodesicPasses }} material=${moving.count { it.materialRan }}")
            out.add("  pacing during move: interval mean=${f1(mean)} max=${f1(dts.maxOrNull() ?: 0.0)} ms CV=${String.format(Locale.US, "%.2f", cv)}; " +
                "retrace frame CPU ms mean=${f1(rebuildMs.average().takeIf { !it.isNaN() } ?: 0.0)} max=${f1(rebuildMs.maxOrNull() ?: 0.0)}")
            out.add("  camera: max |rendered - (user + scripted offset)| = ${String.format(Locale.US, "%.5f", stateErr)} deg; rendered steps backwards=$back; " +
                "sigChanged frames=${moving.count { it.signatureChanged }}/${moving.size}")
            val rs = r.restart
            if (rs == null) out.add("  restart: release not reached") else out.add(
                "  release->restart: firstCOMPLETE_CACHE=${if (rs.firstCompleteCacheMs < 0) "never" else f1(rs.firstCompleteCacheMs.toDouble()) + "ms"} " +
                    "firstMODULATE=${if (rs.firstModulateMs < 0) "NEVER" else f1(rs.firstModulateMs.toDouble()) + "ms"} frames=${rs.framesToModulate} " +
                    "PLAIN=${rs.plainFrames} REBUILD-after-release=${rs.rebuildFramesAfterRelease} restarted=${if (r.phase.enableAnimation) rs.restarted else "n/a (ANIM off)"}" +
                    (if (rs.statusAtTimeout.isNotEmpty()) " timeout: ${rs.statusAtTimeout}" else ""))
            if (r.phase.enableAnimation) {
                val obs = r.samples.joinToString(" ") { "t${it.index}=${it.tMs}ms active=${it.materialVsStatic != null} D2(t)-D2(t0):${it.materialVsT0?.let { d -> "rms=${g(d.rms)} ch=${d.changed}" } ?: "-"}" }
                out.add("  observation after restart: $obs")
            }
            if ((m.pop && r.phase.enableAnimation) || r.popStep != null || r.popBaseline != null) {
                out.add("  POP: D2 frame-to-frame baseline ${diffText(r.popBaseline)}")
                out.add("  POP: last D2 -> retraced frame (gate ${r.popStepGate}) ${diffText(r.popStep)}")
            }
            if (r.restartPop != null) out.add("  POP at restart: first D2 vs its static cache ${diffText(r.restartPop)}" +
                (baseline?.let { b -> if (b.rms > 0f) " = ${String.format(Locale.US, "%.1f", r.restartPop!!.rms / b.rms)}x the frame-to-frame baseline" else "" } ?: ""))
            val tail = tr.take(MOTION_TRACE_LINES)
            if (r.phase.id == "M1" || r.phase.id == "M3") {
                out.add("  per-frame trace (t ms, stage, offAz, offIncl, camAz, camIncl, gate, geo, mat, presentedMod, sig, dirty, valid, complete, cpuMs):")
                for (t in tail) out.add(String.format(Locale.US, "   %7.1f %c %+.3f %+.3f %.3f %.3f %-14s %d %s %s %s %s %s %s %.1f",
                    t.tMs, t.stage, t.offsetAz, t.offsetIncl, t.camAz, t.camIncl, t.gate, t.geodesicPasses, b(t.materialRan), b(t.presentedModulated),
                    b(t.signatureChanged), b(t.sceneDirty), b(t.cacheValid), b(t.cacheComplete), t.cpuMs))
                if (tr.size > tail.size) out.add("   ... ${tr.size - tail.size} more frames in the raw log")
            }
        }
        val anim = run.records.filter { it.phase.motion != null && it.phase.enableAnimation }
        val stuck = anim.filter { it.restart?.restarted == false }
        out.add("RESTART INVARIANT (animation resumes without toggling): " + when {
            anim.isEmpty() -> "NOT MEASURED"
            stuck.isEmpty() && anim.all { it.restart != null } -> "PASS in ${anim.size} phases (" + anim.joinToString(" ") { "${it.phase.id}=${String.format(Locale.US, "%.0f", it.restart!!.firstModulateMs)}ms" } + ")"
            else -> "FAIL in " + (stuck.map { it.phase.id } + anim.filter { it.restart == null }.map { it.phase.id + "(no release)" }).joinToString(",")
        })
        return out
    }

    private fun b(v: Boolean) = if (v) "1" else "0"
    private const val MOTION_TRACE_LINES = 70

    /** Crossing words as hex plus the contract decode: x = unorm16 rUnit | phiUnit<<16, y = half g | half tau<<16. */
    fun words(w: LongArray): String {
        val x = w[0]; val y = w[1]
        if (x == 0L && y == 0L) return "none"
        return String.format(Locale.US, "x=0x%08X y=0x%08X (rUnit=%.4f phiUnit=%.4f gHalf=0x%04X tauHalf=0x%04X %s)",
            x, y, (x and 0xFFFF) / 65535.0, (x ushr 16) / 65535.0, y and 0xFFFF, y ushr 16,
            if (((y and 0xFFFF7FFFL) and 0xFFFF0000L) != 0L) "valid" else "INVALID: tau half is 0")
    }

    /**
     * Device acceptance of the cache fix, evaluated only from recorded GPU measurements. PASS/FAIL
     * lines per criterion; M6 (reddish feature) has no automated measure and needs visual confirmation.
     */
    fun acceptance(run: GargantuaForensicRun): List<String> {
        val out = ArrayList<String>()
        fun verdict(ok: Boolean?) = when (ok) { true -> "PASS"; false -> "FAIL"; null -> "NOT MEASURED" }
        val prod = listOf("C", "D", "E").mapNotNull { record(run, it) }
        val valid = prod.mapNotNull { totalValidCrossings(it) }
        out.add("A production validCrossing>0: ${verdict(if (valid.isEmpty()) null else valid.all { it > 0 })} " +
            prod.joinToString(" ") { "${it.phase.id}=${totalValidCrossings(it)}" })
        for ((label, id) in listOf("B" to "C", "C" to "D", "D" to "E")) {
            val s = record(run, id)?.samples?.firstOrNull()?.materialVsStatic
            out.add("$label $id D2-D0 at t0: ${verdict(s?.let { it.changed > 0 && it.diskChanged > 0 })} ${diffText(s)}")
        }
        for (r in prod) {
            val m = r.samples.map { it.probeMaterial }
            val ok: Boolean? = if (m.size < 3 || m.any { it == null }) null else {
                val d = (0 until GargantuaGpuDiagnostics.DISK_PROBES).map { i ->
                    maxOf(kotlin.math.abs(m[1]!![i] - m[0]!![i]), kotlin.math.abs(m[2]!![i] - m[1]!![i]))
                }
                d.count { it > 0f } > 0
            }
            val deltas = if (m.size < 3 || m.any { it == null }) "n/a" else (0 until GargantuaGpuDiagnostics.DISK_PROBES).joinToString(",") { i ->
                g(kotlin.math.abs(m[1]!![i] - m[0]!![i])) + "/" + g(kotlin.math.abs(m[2]!![i] - m[1]!![i]))
            }
            out.add("E ${r.phase.id} disk probes vary t0->t1->t2: ${verdict(ok)} |L1-L0|/|L2-L1| $deltas")
        }
        for (r in prod) {
            val t = r.samples.mapNotNull { it.materialVsT0 }
            out.add("F ${r.phase.id} material changes with time: ${verdict(if (t.isEmpty()) null else t.any { it.diskChanged > 0 })} " +
                r.samples.joinToString(" ") { "t${it.index}:${diffText(it.materialVsT0)}" })
        }
        val ho = prod.map { r -> r.cache.sumOf { it.higherOrderCrossings } }
        out.add("G higher-order records present: ${verdict(if (prod.all { it.cache.isEmpty() }) null else ho.any { it > 0 })} " +
            prod.joinToString(" ") { r -> "${r.phase.id}=${r.cache.sumOf { it.higherOrderCrossings }}" })
        out.add("H M6 reddish feature: VISUAL CONFIRMATION REQUIRED (no automated measure)")
        return out
    }

    private fun yn(v: Boolean?) = when (v) { true -> "YES"; false -> "NO"; null -> "UNKNOWN" }

    private fun drawName(v: Int): String = when {
        v == 0 -> "NONE"
        v == 0x0405 -> "BACK"
        v in PassRecord.COLOR_ATTACHMENT0..(PassRecord.COLOR_ATTACHMENT0 + 15) -> "C${v - PassRecord.COLOR_ATTACHMENT0}"
        else -> "0x" + Integer.toHexString(v)
    }

    /** Full raw dump of every phase for logcat (tag GargantuaDiag). */
    fun raw(run: GargantuaForensicRun): String {
        val sb = StringBuilder()
        for (r in run.records) {
            sb.append("RAW ${r.phase.id} ${r.phase.title} failures=${r.failures}\n")
            for (s in r.samples) {
                sb.append(" sample t${s.index} ${s.tMs}ms validPx=${s.recordsValidPixels} vsStatic=${diffText(s.materialVsStatic)} vsT0=${diffText(s.materialVsT0)} finalVsT0=${diffText(s.finalVsT0)}\n")
                s.stages.forEach { st -> sb.append("  ${st.stage} tex${st.texture} ${st.w}x${st.h} ${st.note ?: "min=${g(st.minL)} max=${g(st.maxL)} mean=${g(st.meanL)} nz=${st.nonzero}"}\n") }
                sb.append("  probes static=${s.probeStatic?.joinToString(",") { g(it) }} material=${s.probeMaterial?.joinToString(",") { g(it) }} final=${s.probeFinal?.joinToString(",") { g(it) }}\n")
            }
            for (t in r.motionTrace) {
                sb.append(String.format(Locale.US, " motion %.1f %c off=%+.3f,%+.3f cam=%.3f,%.3f %s geo=%d mat=%s mod=%s sig=%s dirty=%s valid=%s complete=%s cpu=%.1f %s\n",
                    t.tMs, t.stage, t.offsetAz, t.offsetIncl, t.camAz, t.camIncl, t.gate, t.geodesicPasses, b(t.materialRan), b(t.presentedModulated),
                    b(t.signatureChanged), b(t.sceneDirty), b(t.cacheValid), b(t.cacheComplete), t.cpuMs, t.status))
            }
            for (c in r.cache) {
                sb.append(" cache ${c.label} tex${c.texture} ${c.internalFormat} nz=${c.nonzeroPixels} valid=${c.validPixels} trunc=${c.truncatedPixels} validX=${c.validCrossings} hoX=${c.higherOrderCrossings} " +
                    "emisInvalidX=${c.emissionInvalidCrossings} invalidNzX=${c.invalidNonzeroCrossings} zeroX=${c.zeroCrossings} " +
                    "sampleNz=${words(c.sampleNonzero)} sampleValid=${words(c.sampleValid)} r[${g(c.rMin)},${g(c.rMax)}] phi[${g(c.phiMin)},${g(c.phiMax)}] g[${g(c.gMin)},${g(c.gMax)}] rawMin=${c.rawMin.toList()} rawMax=${c.rawMax.toList()}\n")
            }
            r.cacheFloat.forEach { sb.append(" cacheF ${it.stage} tex${it.texture} ${it.note ?: "min=${g(it.minL)} max=${g(it.maxL)} mean=${g(it.meanL)} nz=${it.nonzero}"}\n") }
            r.ring?.rows?.forEach { sb.append(" ring ${it.angleDeg} ${it.status} start=${it.startClass} b=${it.boundary?.toList()} p=${it.peak?.toList()} ho=${g(it.ho)} tot=${g(it.total)} hoMax=${g(it.hoMaxOnLine)}@${it.hoMaxAt?.toList()}\n") }
            r.black?.let { b -> sb.append(" black ${b.captured} ${b.unresolved} ${b.emptySpace} ${b.samplingHole} ${b.postProcessing} ${b.zeroedBeforeAces}\n"); b.examples.forEach { sb.append("  $it\n") } }
            r.passes.values.forEach { p -> sb.append(" pass ${p.name} prog${p.program} fbo${p.fbo} att${p.attachments.toList()} db${p.drawBuffers.map { drawName(it) }} vp${p.viewport.toList()} s${p.samplers.map { "${it.uniform}@${it.unit}=${it.texture}" }} bound${p.boundUnits.map { "${it[0]}=${it[1]}" }} fb=${p.feedbackSampled} boundOnly=${p.feedbackBoundOnly}\n") }
            r.fbos.forEach { f -> sb.append(" fbo ${f.name} ${f.id} ${f.status} ${f.attachments}\n") }
            r.textures.forEach { t -> sb.append(" tex ${t.role} ${t.texture} ${t.internalFormat} ${t.w}x${t.h}\n") }
        }
        return sb.toString()
    }
}
