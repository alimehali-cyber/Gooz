package com.zig.gargantua.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Integer precision of the animation ray records (RGBA32UI, two crossings per texel).
 *
 * The records are 32-bit words. Their upper halves (phiHit and the tau that marks a crossing present) do
 * not survive a 16-bit integer, which is what the GLSL ES fragment default `mediump int` is on Mali
 * ([15,14]). Neither the JVM nor glslang models mediump, so these tests evaluate the canonical shader's
 * preprocessor for every real program variant and check precision scoping on the source that is
 * actually compiled. A bit-exact reference of the record contract (constants read from the shader)
 * demonstrates what a 32-bit versus a 16-bit integer path does to the records.
 */
class AnimationRecordPrecisionTest {

    private fun mainFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/$relative", "src/main/$relative").map { File(dir, it) }.firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("main source not found: $relative")
    }

    private val canonical by lazy { mainFile("assets/shaders/gargantua_geodesic.frag").readText() }

    private val animationBuild by lazy { ShaderSource.buildGeodesicVariant(canonical, listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE")) }
    private val animationMaterial by lazy {
        ShaderSource.buildGeodesicVariant(canonical, listOf("GARGANTUA_ANIMATION_SEMANTIC_CACHE", "GARGANTUA_ANIMATION_MATERIAL_PASS"))
    }
    private val telemetry by lazy {
        ShaderSource.buildGeodesicVariant(canonical, listOf("GARGANTUA_WORKLOAD_TELEMETRY", "GARGANTUA_WORKLOAD_SEMANTIC_CACHE"))
    }

    // ------------------------------------------------------------------ minimal GLSL conditional preprocessor

    /** Lines that survive #if/#ifdef/#elif/#else/#endif evaluation (directives themselves removed). */
    private fun activeLines(source: String): List<String> {
        val defined = HashSet<String>()
        class Frame(val parentActive: Boolean, var taken: Boolean, var active: Boolean)
        val stack = ArrayList<Frame>()
        fun active() = stack.isEmpty() || stack.last().active
        val out = ArrayList<String>()
        var continuation = false
        for (raw in source.lines()) {
            val line = raw.trim()
            if (continuation) { continuation = line.endsWith("\\"); continue }
            if (!line.startsWith("#")) { if (active()) out.add(raw); continue }
            val directive = line.removePrefix("#").trim()
            val word = directive.substringBefore(' ').substringBefore('(')
            val rest = directive.removePrefix(word).trim()
            when (word) {
                "if" -> { val v = active() && evaluate(rest, defined); stack.add(Frame(active(), v, v)) }
                "ifdef" -> { val v = active() && rest in defined; stack.add(Frame(active(), v, v)) }
                "ifndef" -> { val v = active() && rest !in defined; stack.add(Frame(active(), v, v)) }
                "elif" -> { val f = stack.last(); val v = f.parentActive && !f.taken && evaluate(rest, defined); f.active = v; f.taken = f.taken || v }
                "else" -> { val f = stack.last(); f.active = f.parentActive && !f.taken; f.taken = true }
                "endif" -> stack.removeAt(stack.size - 1)
                "define" -> { if (active()) defined.add(rest.split(Regex("[\\s(]"))[0]); continuation = line.endsWith("\\") }
                else -> if (active()) out.add(raw)
            }
        }
        assertTrue("unbalanced #if", stack.isEmpty())
        return out
    }

    /** defined(X), !, &&, || and parentheses: the only forms used by the canonical shader. */
    private fun evaluate(expr: String, defined: Set<String>): Boolean {
        val tokens = Regex("defined\\s*\\(\\s*\\w+\\s*\\)|&&|\\|\\||!|\\(|\\)").findAll(expr).map { it.value }.toList()
        assertEquals("unsupported #if expression: $expr", expr.replace(Regex("\\s"), ""), tokens.joinToString("").replace(Regex("\\s"), ""))
        var i = 0
        fun or(): Boolean {
            fun unary(): Boolean {
                val t = tokens[i++]
                return when {
                    t == "!" -> !unary()
                    t == "(" -> or().also { i++ }
                    else -> t.substringAfter('(').substringBefore(')').trim() in defined
                }
            }
            fun and(): Boolean { var v = unary(); while (i < tokens.size && tokens[i] == "&&") { i++; v = unary() && v }; return v }
            var v = and()
            while (i < tokens.size && tokens[i] == "||") { i++; v = and() || v }
            return v
        }
        return or()
    }

    private val integerType = Regex("\\b(uint|uvec[234]|int|ivec[234]|usampler2D|isampler2D)\\b")
    private val code = { l: String -> l.substringBefore("//").trim() }

    @Test
    fun animationProgramsDeclareHighpIntBeforeAnyIntegerDeclarationAndNeverLowerIt() {
        for ((name, source) in listOf("animation build" to animationBuild, "animation material" to animationMaterial)) {
            val lines = activeLines(source).map(code)
            val precisionIndex = lines.indexOfFirst { it == "precision highp int;" }
            assertTrue("$name: precision highp int; is not in the compiled source", precisionIndex >= 0)
            val firstInteger = lines.indexOfFirst { !it.startsWith("precision") && integerType.containsMatchIn(it) }
            assertTrue("$name: first integer use (line $firstInteger: ${lines.getOrNull(firstInteger)}) precedes precision highp int",
                precisionIndex < firstInteger)
            // No later default or per-declaration qualifier lowers any integer type of the record path.
            assertFalse(name, lines.any { Regex("precision\\s+(mediump|lowp)\\s+(int|usampler2D)\\s*;").matches(it) })
            assertFalse(name, lines.any { Regex("\\b(mediump|lowp)\\s+(uint|uvec[234]|int|ivec[234]|usampler2D)\\b").containsMatchIn(it) })
            // Float and sampler2D precision stay exactly as before (only float is declared at shader scope).
            assertEquals(name, listOf("precision highp float;", "precision highp int;"), lines.filter { it.startsWith("precision ") })
            // The describe() the forensic report shows reads the same statement.
            assertEquals(name, "highp", GargantuaForensicData.ProgramInfo.describe(source).first)
        }
        // The material pass fetches records through highp usamplers into (now highp) uvec4 values.
        val material = activeLines(animationMaterial).map(code)
        assertEquals(9, material.count { Regex("uniform highp usampler2D u_RayRecord[0-8];").matches(it) })
        // The build pass writes the records through uvec4 outputs that now default to highp.
        val build = activeLines(animationBuild).map(code)
        assertEquals(3, build.count { Regex("layout\\(location = [123]\\) out uvec4 animationRayRecord[ABC];").matches(it) })
    }

    @Test
    fun nonAnimationProgramsCompileExactlyAsBeforeWithoutIntegerRecords() {
        for ((name, source) in listOf("production" to canonical, "workload telemetry" to telemetry)) {
            val lines = activeLines(source).map(code)
            assertEquals(name, listOf("precision highp float;"), lines.filter { it.startsWith("precision ") })
            assertFalse("$name compiles unsigned integer code", lines.any { Regex("\\b(uint|uvec[234]|usampler2D)\\b").containsMatchIn(it) })
        }
    }

    // ------------------------------------------------------------------ record contract reference

    /** Integer constants of the record contract, read from the compiled material-pass source. */
    private class Masks(val tierStrip: Long, val validMask: Long, val tierFlag: Long)

    private fun masksFrom(lines: List<String>): Masks {
        fun hex(regex: String): Long {
            val m = lines.firstNotNullOfOrNull { Regex(regex).find(it) } ?: throw AssertionError("pattern not found: $regex")
            return m.groupValues[1].toLong(16)
        }
        val strip = hex("uint gTau = packedCrossing\\.y & 0x([0-9A-Fa-f]+)u;")
        val valid = hex("if \\(\\(gTau & 0x([0-9A-Fa-f]+)u\\) == 0u\\) return vec4\\(0\\.0\\);")
        val hasCrossing = hex("return \\(\\(record\\.y & 0x([0-9A-Fa-f]+)u\\) \\| \\(record\\.w & 0x[0-9A-Fa-f]+u\\)\\) != 0u;")
        assertEquals("gargantuaRayRecordHasCrossing and gargantuaUnpackCrossing disagree on validity", valid, hasCrossing)
        return Masks(strip, valid, hex("bool tier1 = \\(base\\.y & 0x([0-9A-Fa-f]+)u\\) != 0u;"))
    }

    private fun packUnorm16(v: Float): Long = (v.coerceIn(0f, 1f) * 65535f).toDouble().roundToLong()

    /** IEEE binary16, round to nearest even (GLSL packHalf2x16). */
    private fun half(f: Float): Long {
        val bits = java.lang.Float.floatToRawIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        var mant = bits and 0x7FFFFF
        if (exp <= 0) return sign.toLong()
        if (exp >= 31) return (sign or 0x7C00).toLong()
        var h = (exp shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h++
        return (sign or h).toLong()
    }

    private fun unhalf(h: Long): Float {
        val sign = if ((h and 0x8000) != 0L) -1f else 1f
        val exp = ((h ushr 10) and 0x1F).toInt()
        val mant = (h and 0x3FF).toInt()
        return sign * when (exp) { 0 -> mant / 1024f * (1f / 16384f); else -> (1f + mant / 1024f) * Math.scalb(1f, exp - 15) }
    }

    private val rIn = 6.0f
    private val rOut = 30.0f
    private val tau = (2.0 * PI).toFloat()

    /** gargantuaPackCrossing: (rHit, phiHit, g, +/-tau); w == 0 is an absent crossing. */
    private fun pack(c: FloatArray): LongArray {
        if (c[3] == 0f) return longArrayOf(0, 0)
        val rUnit = ((c[0] - rIn) / maxOf(1.0e-6f, rOut - rIn)).coerceIn(0f, 1f)
        val phiUnit = (c[1] / tau + 0.5f).coerceIn(0f, 1f)
        return longArrayOf(packUnorm16(rUnit) or (packUnorm16(phiUnit) shl 16), half(c[2]) or (half(c[3]) shl 16))
    }

    /** gargantuaUnpackCrossing with the shader's own masks; null = absent/invalid crossing. */
    private fun unpack(p: LongArray, m: Masks): FloatArray? {
        val gTau = p[1] and m.tierStrip
        if ((gTau and m.validMask) == 0L) return null
        return floatArrayOf(rIn + (p[0] and 0xFFFF) / 65535f * (rOut - rIn), ((p[0] ushr 16) / 65535f - 0.5f) * tau,
            unhalf(gTau and 0xFFFF), unhalf(gTau ushr 16))
    }

    private val crossings = listOf(
        floatArrayOf(6.4f, -2.9f, 1.62f, 0.37f),     // near ISCO, approaching side
        floatArrayOf(12.0f, 0.4f, 1.05f, 0.21f),
        floatArrayOf(29.5f, 3.1f, 0.71f, 0.02f),     // outer edge, receding
        floatArrayOf(8.8f, 1.7f, 0.93f, -0.44f),     // higher-order crossing (negative tau)
        floatArrayOf(17.0f, -0.8f, 1.21f, -0.013f)
    )

    @Test
    fun thirtyTwoBitRecordsRoundTripThroughTheShaderContractIncludingTierFlagsAndHigherOrder() {
        val m = masksFrom(activeLines(animationMaterial).map(code))
        val buildTier = activeLines(animationBuild).map(code).first { it.startsWith("if (animationRayCount >= 5) baseRayRecord.y |=") }
        assertEquals(m.tierFlag, Regex("0x([0-9A-Fa-f]+)u").find(buildTier)!!.groupValues[1].toLong(16))
        for (c in crossings) {
            val p = pack(c)
            for (flag in listOf(0L, m.tierFlag)) {
                val word = longArrayOf(p[0], p[1] or flag)
                assertTrue(word[1] <= 0xFFFFFFFFL)
                val d = unpack(word, m) ?: throw AssertionError("valid crossing ${c.toList()} decoded as absent (flag=$flag)")
                assertEquals(c[0], d[0], (rOut - rIn) / 65535f)
                assertEquals(c[1], d[1], tau / 65535f)
                assertEquals(c[2], d[2], c[2] / 1024f)
                assertEquals(c[3], d[3], abs(c[3]) / 1024f)
                assertEquals("higher-order sign lost", c[3] < 0f, d[3] < 0f)
            }
        }
        // Absent crossing and a tier flag on an absent crossing stay invalid (flags never create crossings).
        assertEquals(null, unpack(pack(floatArrayOf(0f, 0f, 0f, 0f)), m))
        assertEquals(null, unpack(longArrayOf(0, m.tierFlag), m))
    }

    @Test
    fun sixteenBitIntegerStorageReproducesTheMeasuredNonzeroButInvalidRecords() {
        val m = masksFrom(activeLines(animationMaterial).map(code))
        // Mali mediump int: 16 bits ([15,14]); the low half of each word survives, the upper half is lost.
        val truncated = crossings.map { c -> pack(c).map { it and 0xFFFF }.toLongArray() }
        assertTrue("every truncated real crossing stays nonzero", truncated.all { (it[0] or it[1]) != 0L })
        assertTrue("every truncated real crossing fails validity", truncated.all { unpack(it, m) == null })
        // Full 32-bit storage (highp int) keeps every one of them valid.
        assertTrue(crossings.all { unpack(pack(it), m) != null })
    }

    @Test
    fun diagnosticDecoderUsesTheSameValidityRuleAsTheMaterialPass() {
        val m = masksFrom(activeLines(animationMaterial).map(code))
        val diag = mainFile("assets/shaders/gargantua_diag_stats.frag").readLines().map(code)
        val rule = diag.first { it.startsWith("bool crossingValid(uvec2 c)") }
        val masks = Regex("0x([0-9A-Fa-f]+)u").findAll(rule).map { it.groupValues[1].toLong(16) }.toList()
        assertEquals(listOf(m.tierStrip, m.validMask), masks)
        assertTrue(diag.contains("precision highp int;"))
    }

    @Test
    fun legacyRenderersStayOnGles3AndOnlyChalUsesTheXapkVulkanBridge() {
        val root = mainFile("AndroidManifest.xml").parentFile
        val kotlin = root.resolve("java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        assertTrue(kotlin.isNotEmpty())

        val xapkBackendPaths = listOf(
            "/com/zig/chal/",
            "/com/orchestrsim/blackhole/"
        )
        for (file in kotlin) {
            val text = file.readText()
            for (legacyGl in listOf("GLES20", "GLES10", "GLES11")) {
                assertFalse("${file.name} uses unsupported legacy API $legacyGl", text.contains(legacyGl))
            }
            val path = file.absolutePath.replace('\\', '/')
            if (xapkBackendPaths.none { path.contains(it) }) {
                for (nativeBackend in listOf("vulkan", "Vulkan", "System.loadLibrary", "external fun")) {
                    assertFalse("${file.name} unexpectedly uses $nativeBackend", text.contains(nativeBackend))
                }
            }
        }

        assertFalse(root.resolve("cpp").exists() || root.resolve("jni").exists() || root.resolve("jniLibs").exists())
        val gradle = root.parentFile.parentFile.resolve("build.gradle.kts").readText()
        assertFalse(gradle.contains("externalNativeBuild") || gradle.contains("ndkVersion"))

        val manifest = mainFile("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:glEsVersion=\"0x00030000\""))
        assertTrue(manifest.contains("android:name=\"android.hardware.vulkan.version\""))
        assertTrue(manifest.contains("android:required=\"false\""))

        val bridge = mainFile("java/com/orchestrsim/blackhole/NativeBridge.kt").readText()
        assertTrue(bridge.contains("System.load(cxxLibrary.absolutePath)"))
        assertTrue(bridge.contains("System.load(rendererLibrary.absolutePath)"))
        assertTrue(bridge.contains("external fun nativeSetParams"))
        assertTrue(mainFile("assets/xapk-native/arm64-v8a/libblackhole.so").isFile)
        assertTrue(mainFile("assets/xapk-native/arm64-v8a/libc++_shared.so").isFile)
    }
}
