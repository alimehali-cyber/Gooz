package com.zig.gargantua

import com.zig.gargantua.disk.ShaderDiskShading
import com.zig.gargantua.renderer.AnimationGate
import com.zig.gargantua.renderer.GargantuaAnimation
import com.zig.gargantua.renderer.ShaderSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationGateTest {
    private fun input(
        resourcesReady: Boolean,
        cacheValid: Boolean,
        sceneDirty: Boolean,
        signatureChanged: Boolean,
        now: Long,
        lastChange: Long,
        amplitude: Int = 15
    ) = AnimationGate.Input(
        animationRequested = true,
        resourcesReady = resourcesReady,
        cacheValid = cacheValid,
        sceneDirty = sceneDirty,
        signatureChangedThisFrame = signatureChanged,
        nowNanos = now,
        lastChangeNanos = lastChange,
        amplitudePercent = amplitude
    )

    private fun assertRebuildThenModulate(gate: AnimationGate) {
        assertEquals(
            AnimationGate.Action.REBUILD,
            gate.decide(input(true, false, true, true, 1_000_000_000L, 1_000_000_000L)).action
        )
        assertEquals(
            AnimationGate.Action.MODULATE,
            gate.decide(input(true, true, false, false, 1_400_000_000L, 1_000_000_000L)).action
        )
    }

    @Test
    fun animationSpeedPeriodsAreNonPersistedSlowNormalFastValues() {
        assertEquals(12.0, GargantuaAnimation.AnimationSpeed.SLOW.periodSeconds, 0.0)
        assertEquals(6.0, GargantuaAnimation.AnimationSpeed.NORMAL.periodSeconds, 0.0)
        assertEquals(3.5, GargantuaAnimation.AnimationSpeed.FAST.periodSeconds, 0.0)
    }

    @Test
    fun flowMapWeightsSumToOne() {
        listOf(0.0, 1.0, 6.0, 12.0, 23.999, 48.0).forEach { seconds ->
            val times = GargantuaAnimation.flowMapTimes(seconds)
            assertEquals(1.0, times.blendA + times.blendB, 1.0e-12)
        }
    }

    @Test
    fun flowMapWeightAIsZeroAtBothCycleEdges() {
        assertEquals(0.0, GargantuaAnimation.flowMapTimes(0.0).blendA, 1.0e-12)
        assertEquals(0.0, GargantuaAnimation.flowMapTimes(GargantuaAnimation.FLOW_MAP_PERIOD_SECONDS).blendA, 1.0e-12)
    }

    @Test
    fun flowMapWrapIsContinuous() {
        val period = GargantuaAnimation.FLOW_MAP_PERIOD_SECONDS
        val before = GargantuaAnimation.flowMapTimes(period - 1.0e-9)
        val after = GargantuaAnimation.flowMapTimes(period + 1.0e-9)
        assertEquals(period, before.timeA + after.timeA, 1.0e-6)
        assertTrue(kotlin.math.abs(before.timeB - after.timeB) < 1.0e-6)
        assertTrue(kotlin.math.abs(before.blendA - after.blendA) < 1.0e-6)
    }

    @Test
    fun flowMapBIsHalfPeriodFromA() {
        val period = GargantuaAnimation.FLOW_MAP_PERIOD_SECONDS
        listOf(0.0, 2.5, 12.0, 23.5, 49.0).forEach { seconds ->
            val times = GargantuaAnimation.flowMapTimes(seconds)
            val expectedB = ((times.timeA + period * 0.5) % period + period) % period
            assertEquals(expectedB, times.timeB, 1.0e-12)
        }
    }

    @Test
    fun dragThenReleaseEndsInModulateWithoutAnotherCameraChange() {
        assertRebuildThenModulate(AnimationGate())
    }

    @Test
    fun backgroundResumeEndsInModulateWithoutAnotherCameraChange() {
        val gate = AnimationGate()
        assertEquals(AnimationGate.Action.REBUILD, gate.decide(input(true, false, true, false, 2_000_000_000L, 2_000_000_000L)).action)
        assertEquals(AnimationGate.Action.MODULATE, gate.decide(input(true, true, false, false, 2_400_000_000L, 2_000_000_000L)).action)
    }

    @Test
    fun resumeCacheInvalidationRequiresRebuildThenSettlesToModulation() {
        val gate = AnimationGate()
        val afterResume = input(true, false, true, false, 5_000_000_000L, 5_000_000_000L)
        assertEquals(AnimationGate.Action.REBUILD, gate.decide(afterResume).action)
        assertEquals(
            AnimationGate.Action.MODULATE,
            gate.decide(afterResume.copy(cacheValid = true, sceneDirty = false, nowNanos = 5_300_000_000L)).action
        )
    }

    @Test
    fun fboDeletionCacheInvalidationRequiresRebuild() {
        val decision = AnimationGate().decide(
            input(true, cacheValid = false, sceneDirty = true, signatureChanged = false, now = 7_000_000_000L, lastChange = 6_600_000_000L)
        )
        assertEquals(AnimationGate.Action.REBUILD, decision.action)
    }

    @Test
    fun resizeEndsInModulateWithoutAnotherCameraChange() {
        assertRebuildThenModulate(AnimationGate())
    }

    @Test
    fun zeroAmplitudeIsAPlainStrictBypassEvenWhenSceneIsDirty() {
        val decision = AnimationGate().decide(
            input(
                resourcesReady = true,
                cacheValid = false,
                sceneDirty = true,
                signatureChanged = true,
                now = 1_400_000_000L,
                lastChange = 1_000_000_000L,
                amplitude = 0
            )
        )
        assertEquals(AnimationGate.Action.PLAIN, decision.action)
        assertEquals(AnimationGate.State.PLAIN, decision.state)
    }

    @Test
    fun transientNotReadyDoesNotConsumeRebuild() {
        val gate = AnimationGate()
        assertEquals(AnimationGate.Action.PLAIN, gate.decide(input(false, false, true, true, 1_000_000_000L, 1_000_000_000L)).action)
        assertEquals(AnimationGate.Action.REBUILD, gate.decide(input(true, false, true, true, 1_100_000_000L, 1_000_000_000L)).action)
        assertEquals(AnimationGate.Action.MODULATE, gate.decide(input(true, true, false, false, 1_500_000_000L, 1_000_000_000L)).action)
    }

    @Test
    fun signatureChangeInvalidatesOtherwiseValidCache() {
        val decision = AnimationGate().decide(
            input(
                resourcesReady = true,
                cacheValid = true,
                sceneDirty = false,
                signatureChanged = true,
                now = 8_000_000_000L,
                lastChange = 7_500_000_000L
            )
        )
        assertEquals(AnimationGate.Action.REBUILD, decision.action)
    }

    @Test
    fun coarseModeChangeEndsInModulateWithoutAnotherCameraChange() {
        assertRebuildThenModulate(AnimationGate())
    }

    @Test
    fun animationOffThenOnEndsInModulateWithoutAnotherCameraChange() {
        val gate = AnimationGate()
        assertEquals(
            AnimationGate.Action.PLAIN,
            gate.decide(AnimationGate.Input(false, true, true, true, true, 1_000_000_000L, 1_000_000_000L, 15)).action
        )
        assertRebuildThenModulate(gate)
    }

    private fun readShader(fileName: String): String {
        val candidates = listOf(
            java.io.File("app/src/main/assets/shaders/$fileName"),
            java.io.File("src/main/assets/shaders/$fileName"),
            java.io.File("assets/shaders/$fileName")
        )
        for (c in candidates) {
            if (c.exists()) return c.readText()
        }
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, "app/src/main/assets/shaders/$fileName")
            if (candidate.exists()) return candidate.readText()
            val candidate2 = java.io.File(dir, "src/main/assets/shaders/$fileName")
            if (candidate2.exists()) return candidate2.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("Shader $fileName not found")
    }

    private fun readSource(relative: String): String {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            val candidate = java.io.File(dir, relative)
            if (candidate.exists()) return candidate.readText()
            val candidate2 = java.io.File(dir, relative.removePrefix("app/"))
            if (candidate2.exists()) return candidate2.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("$relative not found")
    }

    private fun readRenderer() = readSource("app/src/main/java/com/zig/gargantua/renderer/GargantuaRenderer.kt")

    /** The production material-pass program (ShaderSource.loadAnimationMaterialFragmentShader) with its
     *  preprocessor applied: only the code the GPU compiles for the per-frame pass remains. */
    private fun materialPassSource(): String = activeCode(
        ShaderSource.buildGeodesicVariant(
            readShader("gargantua_geodesic.frag"),
            listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS")
        )
    )

    private fun animationBuildSource(): String = activeCode(
        ShaderSource.buildGeodesicVariant(readShader("gargantua_geodesic.frag"), listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE"))
    )

    /** Minimal #if/#ifdef/#else/#endif evaluator (defined(X), !, &&, ||) over the variant's #defines. */
    private fun activeCode(source: String): String {
        val defines = mutableSetOf<String>()
        val stack = mutableListOf<BooleanArray>() // [branchActive, anyTaken, parentActive]
        fun active() = stack.all { it[0] }
        fun eval(expr: String): Boolean {
            val e = expr.replace(Regex("defined\\s*\\(\\s*(\\w+)\\s*\\)")) { if (it.groupValues[1] in defines) "1" else "0" }
                .replace(Regex("defined\\s+(\\w+)")) { if (it.groupValues[1] in defines) "1" else "0" }.replace(" ", "")
            fun term(t: String): Boolean = if (t.startsWith("!")) !term(t.substring(1)) else t == "1"
            return e.split("||").any { part -> part.split("&&").all { term(it) } }
        }
        val out = StringBuilder()
        for (line in source.lines()) {
            val d = line.trim()
            when {
                d.startsWith("#ifdef ") -> { val parent = active(); val v = d.removePrefix("#ifdef ").trim() in defines; stack += booleanArrayOf(v, v, parent) }
                d.startsWith("#ifndef ") -> { val parent = active(); val v = d.removePrefix("#ifndef ").trim() !in defines; stack += booleanArrayOf(v, v, parent) }
                d.startsWith("#if ") -> { val parent = active(); val v = eval(d.removePrefix("#if ")); stack += booleanArrayOf(v, v, parent) }
                d.startsWith("#elif ") -> { val top = stack.last(); val v = !top[1] && eval(d.removePrefix("#elif ")); top[0] = v; top[1] = top[1] || v }
                d.startsWith("#else") -> { val top = stack.last(); top[0] = !top[1]; top[1] = true }
                d.startsWith("#endif") -> stack.removeAt(stack.size - 1)
                !active() -> {}
                d.startsWith("#define ") -> { defines += d.removePrefix("#define ").trim().substringBefore(' ').substringBefore('('); out.append(line).append('\n') }
                else -> out.append(line).append('\n')
            }
        }
        return out.toString()
    }

    /** Body of the function whose header starts with [signature], by brace matching. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature must exist", start >= 0)
        var i = source.indexOf('{', start)
        val open = i
        var depth = 0
        while (true) {
            if (source[i] == '{') depth++
            if (source[i] == '}') { depth--; if (depth == 0) break }
            i++
        }
        return source.substring(open + 1, i)
    }

    private fun codeOnly(text: String) = text.lines().map { it.substringBefore("//") }.joinToString("\n")

    // Production disk (GargantuaRenderState defaults: M = 1, a = 0.8 M, rOut = 22) for the CPU port of the
    // shader's crossing shading (ShaderDiskShading mirrors gargantuaAccumulateDiskCrossing).
    private val mass = 1.0f
    private val spinA = 0.8f
    private val rIn = com.zig.gargantua.disk.KerrIsco.compute(1.0, 0.8).toFloat()
    private val rOut = 22.0f

    private fun shade(rHit: Float, phiHit: Float, t: Float, amplitude: Float): Float {
        val c = ShaderDiskShading.shadeCrossing(
            mass, rHit, phiHit, stepDirZ = 0.35f, gShift = 1.0f, rIn = rIn, rOut = rOut, transmittanceIn = 1.0f,
            keplerPhase = ShaderDiskShading.keplerPhase(mass, spinA, rHit, t), amplitude = amplitude
        ).contribution
        return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
    }

    @Test
    fun animationSignMatchesKeplerianPositiveDirectionWithoutStandingWave() {
        val geodesic = readShader("gargantua_geodesic.frag")
        // Material advection at the real hit: phiMaterial = phiHit - OmegaK(rHit) t, in the geodesic
        // shading and in the per-frame material pass that re-shades the cached hits.
        assertTrue(geodesic.contains("float phiMaterial = phiHit - phaseAdvance;"))
        assertTrue(geodesic.contains("gargantuaAccumulateDiskCrossing(rHit, c.y - phaseAdvance,"))
        assertFalse(geodesic.contains("phiHit + phaseAdvance"))
        assertFalse(geodesic.contains("c.y + phaseAdvance"))
        // The material pass derives OmegaK from the cached hit radius c.x and the time digits only.
        val cached = codeOnly(functionBody(materialPassSource(), "vec4 gargantuaCachedCrossingRadiance("))
        assertTrue(cached.contains("float rHit = c.x;"))
        assertTrue(cached.contains("float omega = sqrt(u_Mass) / (pow(rHit, 1.5) + u_Spin * sqrt(u_Mass));"))
        assertTrue(cached.contains("float phaseAdvance = GARGANTUA_TAU * gargantuaPhaseAdvanceFromDigits(omega / GARGANTUA_TAU, d0, d1, d23, d45, d67);"))

        // Behaviour of the shaded material (CPU port): the pattern at (phiHit + OmegaK dt, t + dt) with the
        // Kepler phase held is the pattern at (phiHit, t): prograde co-rotation at the local Kepler rate.
        val r = 6.0f
        val dt = 0.8f
        val omega = ShaderDiskShading.keplerPhase(mass, spinA, r, 1.0f)
        val keplerHeld = ShaderDiskShading.keplerPhase(mass, spinA, r, 3.0f)
        for (i in 0 until 64) {
            val phi = -3.0f + i * 0.09f
            val before = ShaderDiskShading.gasDensity(r, phi - omega * 3.0f, 0.0f, 0.5f, rIn, rOut,
                ShaderDiskShading.materialPerturbationField(r, phi - omega * 3.0f, keplerHeld, rIn), 0.4f)
            val phiLater = phi + omega * dt
            val after = ShaderDiskShading.gasDensity(r, phiLater - omega * (3.0f + dt), 0.0f, 0.5f, rIn, rOut,
                ShaderDiskShading.materialPerturbationField(r, phiLater - omega * (3.0f + dt), keplerHeld, rIn), 0.4f)
            assertEquals(before, after, 1.0e-4f * kotlin.math.max(1.0f, before))
        }
    }

    // A2: the amplitude scales the perturbation of the density, never the orbital time or phase.
    @Test
    fun animationAmplitudeOrderingIsMonotonicWithoutArtificialFloor() {
        val material = materialPassSource()
        val density = codeOnly(functionBody(material, "float evaluate3DVolumetricGasDensity("))
        assertTrue(density.contains("if (u_AnimationAmplitude > 0.0) {"))
        assertTrue(density.contains("rawDensity *= 1.0 + u_AnimationAmplitude * gargantuaMaterialPerturbation;"))
        assertFalse(material.contains("max(amp * 1.8, 0.45)"))
        assertFalse(material.contains("ampFactor"))
        // The amplitude appears only in its declaration, the density factor and the perturbation guard.
        val uses = codeOnly(material).lines().filter { it.contains("u_AnimationAmplitude") }.map { it.trim() }
        assertEquals(
            listOf(
                "uniform float u_AnimationAmplitude;",
                "if (u_AnimationAmplitude > 0.0) {",
                "rawDensity *= 1.0 + u_AnimationAmplitude * gargantuaMaterialPerturbation;",
                "gargantuaMaterialPerturbation = (u_AnimationAmplitude > 0.0)"
            ),
            uses
        )

        // Perturbation magnitude of the shaded disk (RMS deviation from the unperturbed emission at the
        // same time): strictly increasing 15% < 40% < 80%, zero at 0%.
        fun perturbationRms(amplitude: Float): Double {
            var sum = 0.0
            var n = 0
            for (ri in 0 until 12) for (pi in 0 until 90) {
                val r = rIn * 1.05f + ri * 1.4f
                val phi = -3.1f + pi * 0.069f
                val d = (shade(r, phi, 2.5f, amplitude) - shade(r, phi, 2.5f, 0.0f)).toDouble()
                sum += d * d
                n++
            }
            return kotlin.math.sqrt(sum / n)
        }
        val rms0 = perturbationRms(0.0f)
        val rms15 = perturbationRms(0.15f)
        val rms40 = perturbationRms(0.40f)
        val rms80 = perturbationRms(0.80f)
        assertEquals(0.0, rms0, 0.0)
        assertTrue("Amplitude 15% < 40% ($rms15, $rms40)", rms15 < rms40)
        assertTrue("Amplitude 40% < 80% ($rms40, $rms80)", rms40 < rms80)
        assertTrue("15% perturbs the disk ($rms15)", rms15 > 0.0)
    }

    @Test
    fun keplerPhaseIsIdenticalAcrossAmplitudes() {
        // Shader: the phase passed to the material is computed before, and independently of, the amplitude.
        val accumulate = codeOnly(functionBody(materialPassSource(), "void gargantuaAccumulateDiskCrossing("))
        assertTrue(accumulate.contains("gargantuaMaterialPerturbationField(rHit, phiMaterial, keplerPhase)"))
        val cached = codeOnly(functionBody(materialPassSource(), "vec4 gargantuaCachedCrossingRadiance("))
        assertFalse(cached.contains("u_AnimationAmplitude"))
        val field = codeOnly(functionBody(materialPassSource(), "float gargantuaMaterialPerturbationField("))
        assertFalse(field.contains("u_AnimationAmplitude"))
        // Behaviour: at every amplitude the perturbation pattern is the same function of the same phase;
        // (shade(a) - shade(0)) / a is amplitude-independent to first order in a, i.e. the pattern does not
        // move faster or slower with amplitude.
        val r = 7.0f
        val t = 4.0f
        val pattern = { amplitude: Float ->
            DoubleArray(120) { i ->
                val phi = -3.1f + i * 0.052f
                ((shade(r, phi, t, amplitude) - shade(r, phi, t, 0.0f)) / amplitude).toDouble()
            }
        }
        val p15 = pattern(0.15f)
        val p40 = pattern(0.40f)
        val p80 = pattern(0.80f)
        fun corr(a: DoubleArray, b: DoubleArray): Double {
            val ma = a.average(); val mb = b.average()
            var sab = 0.0; var saa = 0.0; var sbb = 0.0
            for (i in a.indices) { sab += (a[i] - ma) * (b[i] - mb); saa += (a[i] - ma) * (a[i] - ma); sbb += (b[i] - mb) * (b[i] - mb) }
            return sab / kotlin.math.sqrt(saa * sbb)
        }
        assertTrue("15%/40% perturbation patterns share one phase (${corr(p15, p40)})", corr(p15, p40) > 0.95)
        assertTrue("15%/80% perturbation patterns share one phase (${corr(p15, p80)})", corr(p15, p80) > 0.90)
    }

    @Test
    fun animationSpeedOrderingPreservesPhaseProgressionRates() {
        val slow = GargantuaAnimation.AnimationSpeed.SLOW
        val normal = GargantuaAnimation.AnimationSpeed.NORMAL
        val fast = GargantuaAnimation.AnimationSpeed.FAST
        assertTrue("Slow period > Normal period", slow.periodSeconds > normal.periodSeconds)
        assertTrue("Normal period > Fast period", normal.periodSeconds > fast.periodSeconds)
        val rateSlow = 1.0 / slow.periodSeconds
        val rateNormal = 1.0 / normal.periodSeconds
        val rateFast = 1.0 / fast.periodSeconds
        assertTrue("Phase rate: slow < normal", rateSlow < rateNormal)
        assertTrue("Phase rate: normal < fast", rateNormal < rateFast)
    }

    @Test
    fun animationPhaseIsTimeBasedAndFrameRateIndependent() {
        // The renderer derives the animation time from System.nanoTime() since the animation origin; the
        // time digits handed to both the build and the material pass come from that time only.
        val renderer = codeOnly(readRenderer())
        assertTrue(renderer.contains("val now = System.nanoTime()"))
        assertTrue(renderer.contains("((now - animOriginNanos).coerceAtLeast(0L)).toDouble() / 1_000_000_000.0"))
        assertTrue(renderer.contains("GargantuaAnimation.fillTimeDigits(animTime, scratchTimeDigits)"))
        assertFalse(Regex("animTime\\s*=.*[Ff]rame(Count|Index|Number)").containsMatchIn(renderer))
        // Same elapsed time at 30 and 60 FPS -> same digits.
        var t30 = 0.0
        for (i in 0 until 90) t30 += 3.0 / 90.0
        var t60 = 0.0
        for (i in 0 until 180) t60 += 3.0 / 180.0
        val d30 = FloatArray(GargantuaAnimation.TIME_DIGIT_COUNT)
        val d60 = FloatArray(GargantuaAnimation.TIME_DIGIT_COUNT)
        GargantuaAnimation.fillTimeDigits(t30, d30)
        GargantuaAnimation.fillTimeDigits(t60, d60)
        for (k in d30.indices) assertEquals("digit $k", d30[k], d60[k], 1.0e-6f)
    }

    // D2: amplitude 0 is the identity: no cache, no material pass, no density change; the material pass
    // itself passes shadow/sky/star pixels (no cached disk crossing) through unchanged.
    @Test
    fun zeroAmplitudeStrictlyBypassesModulation() {
        // Amplitude 0 presents the plain geodesic frame: no cache build and no material pass.
        val decision = AnimationGate().decide(input(true, true, false, false, 5_000_000_000L, 0L, amplitude = 0))
        assertEquals(AnimationGate.Action.PLAIN, decision.action)
        assertEquals(AnimationGate.State.PLAIN, decision.state)
        assertEquals(0, AnimationGate.geodesicPassesFor(decision.action, sceneDirty = false))
        // Shader: the density factor and the perturbation are both skipped at amplitude 0.
        val material = materialPassSource()
        assertTrue(codeOnly(functionBody(material, "float evaluate3DVolumetricGasDensity(")).contains("if (u_AnimationAmplitude > 0.0) {"))
        assertTrue(material.contains("? gargantuaMaterialPerturbationField(rHit, phiMaterial, keplerPhase) : 0.0;"))
        // With amplitude zero the gate never runs the material pass; shadow stays unchanged.
        val main = codeOnly(material.substring(material.lastIndexOf("void main()")))
        assertTrue(main.contains("if (!hasCrossing) {\n        fragColor = vec4(max(hdr.rgb + skyDelta, vec3(0.0)), hdr.a);\n        return;\n    }"))
        assertTrue(main.contains("if (skyData.w > 0.0)"))
        // Behaviour (CPU port): amplitude 0 is bit-identical to the unanimated shading at every hit.
        for (ri in 0 until 10) for (pi in 0 until 40) {
            val r = rIn * 1.02f + ri * 1.9f
            val phi = -3.0f + pi * 0.15f
            val plain = ShaderDiskShading.shadeCrossing(mass, r, phi, 0.35f, 1.0f, rIn, rOut, 1.0f).contribution
            val zero = ShaderDiskShading.shadeCrossing(mass, r, phi, 0.35f, 1.0f, rIn, rOut, 1.0f, amplitude = 0.0f).contribution
            for (c in 0 until 3) assertEquals(plain[c].toRawBits(), zero[c].toRawBits())
        }
    }

    // D1: the animated signal is the physical material advected at phiHit - OmegaK t (not screen noise):
    // the frame-to-frame change of the shaded disk correlates with the prograde Keplerian advection of
    // the material and anti-correlates with the counter-propagating (retrograde) prediction.
    @Test
    fun behavioralDirectionAndNoStandingWaveTest() {
        listOf(0.15f, 0.40f, 0.80f).forEach { amplitude ->
            var sumProgradeDot = 0.0
            var sumChange = 0.0
            var sumPrediction = 0.0
            for (ri in 0 until 8) {
                val r = rIn * 1.1f + ri * 1.6f
                val omega = ShaderDiskShading.keplerPhase(mass, spinA, r, 1.0f)
                val dt = 0.02f / omega // 0.02 rad of orbital phase per step
                val t = 3.0f
                val h = 0.002f
                for (pi in 0 until 180) {
                    val phi = -3.1f + pi * 0.0345f
                    val change = (shade(r, phi, t + dt, amplitude) - shade(r, phi, t, amplitude)).toDouble()
                    // Advection of I(phi - OmegaK t): dI/dt = -OmegaK dI/dphi.
                    val gradient = ((shade(r, phi + h, t, amplitude) - shade(r, phi - h, t, amplitude)) / (2.0f * h)).toDouble()
                    val prograde = -omega * dt * gradient
                    sumProgradeDot += change * prograde
                    sumChange += change * change
                    sumPrediction += prograde * prograde
                }
            }
            val correlation = sumProgradeDot / kotlin.math.sqrt(sumChange * sumPrediction)
            assertTrue("amplitude $amplitude: change correlates with prograde advection ($correlation)", correlation > 0.8)
            // A retrograde (counter-propagating) pattern would have correlation -correlation < 0 with it.
            assertTrue(-correlation < 0.0)
        }
    }

    @Test
    fun behavioralFrameRateIndependenceAcrossCadences() {
        val elapsedTarget = 6.0 // seconds (exact multiple of 30, 60, 90, 120)
        val cadences = listOf(30, 60, 90, 120) // FPS
        val results = cadences.map { fps ->
            var t = 0.0
            val dt = 1.0 / fps.toDouble()
            val steps = kotlin.math.round(elapsedTarget * fps).toInt()
            for (step in 0 until steps) {
                t += dt
            }
            FloatArray(GargantuaAnimation.TIME_DIGIT_COUNT).also { GargantuaAnimation.fillTimeDigits(t, it) }
        }
        val ref = results[0]
        for (i in 1 until results.size) {
            for (k in ref.indices) assertEquals("digit $k at ${cadences[i]} FPS", ref[k], results[i][k], 1.0e-5f)
        }
        // The shaded material at a given time is a function of time only (CPU port), not of the cadence.
        assertEquals(shade(6.5f, 0.3f, 6.0f, 0.4f), shade(6.5f, 0.3f, (0 until 360).fold(0.0f) { acc, _ -> acc + 1.0f / 60.0f }, 0.4f), 1.0e-3f)
    }

    // D3: the old noise texture, the screen-space displacement and the synthetic motion are gone.
    @Test
    fun behavioralAmplitudeMonotonicScaling() {
        val material = materialPassSource()
        val build = animationBuildSource()
        val renderer = codeOnly(readRenderer())
        listOf(material, build).forEach { program ->
            val code = codeOnly(program)
            assertFalse(code.contains("u_NoiseTexture"))
            assertFalse(code.contains("u_FlowTime"))
            assertFalse(code.contains("sampleFluidFlow"))
            assertFalse(code.contains("keplerianNoise"))
            assertFalse("no screen-space displacement", Regex("gl_FragCoord\\.xy\\s*[+-]").containsMatchIn(code))
        }
        // The per-frame pass uses no screen-space derivatives (the build's dFdx only selects M7 tier 1).
        assertFalse(codeOnly(material).contains("dFdx") || codeOnly(material).contains("dFdy"))
        // The material pass fetches records at its own texel only (no offset, no filtered lookup).
        val mainCode = codeOnly(material.substring(material.lastIndexOf("void main()")))
        assertTrue(mainCode.contains("ivec2 coord = ivec2(gl_FragCoord.xy);"))
        assertFalse(Regex("\\btexture\\(").containsMatchIn(codeOnly(functionBody(material, "vec4 gargantuaCachedPixelEmission("))))
        assertFalse(Regex("\\btexture\\(").containsMatchIn(mainCode))
        assertFalse(renderer.contains("deterministicNoise"))
        assertFalse(renderer.contains("flowMapTimes"))
        assertFalse(renderer.contains("animation_modulation"))
        assertFalse(renderer.contains("animation_apply"))
        assertFalse(java.io.File("app/src/main/assets/shaders/gargantua_animation_modulation.frag").exists())
        assertFalse(java.io.File("src/main/assets/shaders/gargantua_animation_modulation.frag").exists())
        assertFalse(java.io.File("app/src/main/assets/shaders/gargantua_animation_apply.frag").exists())
        assertFalse(java.io.File("src/main/assets/shaders/gargantua_animation_apply.frag").exists())
    }

    // A1: every ray of a 1/5/9-ray pixel is re-shaded from its own cached hit and averaged with the M7
    // weights of the geodesic pass. Fails if a 5- or 9-ray path evaluates the material only once.
    @Test
    fun everyM7SubrayIsReShadedFromItsOwnCachedHit() {
        val build = codeOnly(animationBuildSource())
        // Geodesic pass: production weights, and one record captured right after each subray's trace.
        assertTrue(build.contains("fragColor = (baseSample + sample1 + sample2 + sample3 + sample4) / 5.0;"))
        assertTrue(build.contains("fragColor = (baseSample + sample1 + sample2 + sample3 + sample4 + sample5 + sample6 + sample7 + sample8) / 9.0;"))
        for (k in 1..8) {
            val trace = build.indexOf("vec4 sample$k = GARGANTUA_TRACE_RAY_SAMPLE(")
            val capture = build.indexOf("GARGANTUA_CAPTURE_RAY_RECORD($k);", trace)
            assertTrue("subray $k record captured", trace >= 0 && capture > trace)
            val nextTrace = build.indexOf("GARGANTUA_TRACE_RAY_SAMPLE(", trace + 30)
            assertTrue("subray $k record captured before the next trace", nextTrace < 0 || capture < nextTrace)
        }
        assertTrue(build.contains("animationRayCount = 5;"))
        assertTrue(build.contains("animationRayCount = 9;"))

        // Material pass: 1 + 4 + 4 evaluations, each of a distinct record, with weights 1, 1/5, 1/9.
        val pixel = codeOnly(functionBody(materialPassSource(), "vec4 gargantuaCachedPixelEmission("))
        val tier1 = pixel.indexOf("if (tier1) {")
        val tier2 = pixel.indexOf("if (tier2) {")
        assertTrue(tier1 > 0 && tier2 > tier1)
        fun evaluations(text: String) = Regex("gargantuaRayRecordEmission\\((\\w+)\\)").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf("base"), evaluations(pixel.substring(0, tier1)))
        assertEquals(listOf("r1", "r2", "r3", "r4"), evaluations(pixel.substring(tier1, tier2)))
        assertEquals(listOf("r5", "r6", "r7", "r8"), evaluations(pixel.substring(tier2)))
        for (k in 0..8) {
            assertEquals("record $k fetched once", 1, Regex("texelFetch\\(u_RayRecord$k, coord, 0\\)").findAll(pixel).count())
        }
        assertTrue(pixel.contains("float weight = 1.0;"))
        assertTrue(pixel.contains("weight = 1.0 / 5.0;"))
        assertTrue(pixel.contains("weight = 1.0 / 9.0;"))
        assertTrue(pixel.contains("return emission * weight;"))
        // Renderer: all nine record textures are allocated, written (3 cache passes x 3 targets) and bound.
        val renderer = codeOnly(readRenderer())
        assertTrue(renderer.contains("RAY_RECORD_UNIFORMS = Array(AnimationGate.CACHE_PASS_COUNT * AnimationGate.RECORDS_PER_CACHE_PASS) {"))
        assertTrue(renderer.contains("\"u_RayRecord\$it\""))
        assertEquals(9, AnimationGate.RECORDS_PER_CACHE_PASS * AnimationGate.CACHE_PASS_COUNT)
        assertTrue(renderer.contains("material.setUniform1i(RAY_RECORD_UNIFORMS[ray], 2 + ray)"))
    }

    // A3: coarse sampling modes re-shade every ray sample from its own record on the ray grid and then take
    // the same upscale path as the unanimated coarse frame (no nearest-center texel substitution).
    @Test
    fun coarseModesReShadeEachRaySampleOnTheRayGrid() {
        val renderer = codeOnly(readRenderer())
        val pass = functionBody(renderer, "private fun runAnimationPass(")
        assertTrue(pass.contains("GLES30.glViewport(0, 0, rayWidth, rayHeight)"))
        assertFalse(pass.contains("GLES30.glViewport(0, 0, renderWidth, renderHeight)"))
        assertTrue(pass.contains("upscaleCoarseRayTexture(animatedRayTextureId, renderWidth, renderHeight, quad, modulatedHdrFboId, animatedRayFboId)"))
        val main = codeOnly(materialPassSource().let { it.substring(it.lastIndexOf("void main()")) })
        assertFalse("no per-block center lookup", main.contains("u_BlockSize") || main.contains("blockSize"))
        assertTrue(main.contains("gargantuaCachedPixelEmission(coord, hasCrossing)"))
    }

    // F: animation frames do no RK4 integration; an invalidation rebuilds the cache exactly once.
    @Test
    fun animationFramesPerformZeroGeodesicPassesAndInvalidationRebuildsOnce() {
        val gate = AnimationGate()
        var passes = 0
        var rebuilds = 0
        var completions = 0
        var modulateFrames = 0
        var cacheValid = false
        var cacheComplete = false
        var sceneDirty = true
        val change = 1_000_000_000L
        for (frame in 0 until 120) {
            val now = change + frame * 16_666_667L
            val d = gate.decide(
                AnimationGate.Input(true, true, cacheValid, sceneDirty, frame == 0, now, change, 40, cacheComplete)
            )
            val framePasses = AnimationGate.geodesicPassesFor(d.action, sceneDirty)
            passes += framePasses
            when (d.action) {
                AnimationGate.Action.REBUILD -> { rebuilds++; cacheValid = true; cacheComplete = false; sceneDirty = false }
                AnimationGate.Action.COMPLETE_CACHE -> { completions++; cacheComplete = true }
                AnimationGate.Action.MODULATE -> { modulateFrames++; assertEquals("MODULATE frame $frame", 0, framePasses) }
                AnimationGate.Action.PLAIN -> assertEquals(0, framePasses)
            }
        }
        assertEquals(1, rebuilds)
        assertEquals(1, completions)
        assertTrue(modulateFrames > 90)
        // One build = 1 HDR pass + 2 record passes of the same rays (ES 3.0 guarantees only 4 draw buffers).
        assertEquals(1 + (AnimationGate.CACHE_PASS_COUNT - 1), passes)
        // Renderer instrumentation: every geodesic draw is counted, the material pass draws none, and a
        // MODULATE frame with a nonzero count latches ANIM FAILED.
        val renderer = codeOnly(readRenderer())
        assertEquals(2, Regex("geodesicPassesThisFrame\\+\\+").findAll(renderer).count())
        val pass = functionBody(renderer, "private fun runAnimationPass(")
        assertFalse(pass.contains("geodesicPassesThisFrame"))
        assertFalse(pass.contains("animationGeodesicProgram"))
        assertTrue(renderer.contains("if (gateDecision.action == AnimationGate.Action.MODULATE && geodesicPassesThisFrame != 0) {"))
        assertTrue(renderer.contains("failAnimation(\"ANIM FAILED: \$geodesicPassesThisFrame GEODESIC PASSES IN AN ANIMATION FRAME\")"))
        val material = materialPassSource()
        assertFalse("material pass must not integrate geodesics", material.substring(material.lastIndexOf("void main()")).contains("traceRaySample"))
        assertFalse(Regex("\\brk4|RK4\\w*\\(").containsMatchIn(codeOnly(functionBody(material, "vec4 gargantuaCachedPixelEmission("))))
    }
}
