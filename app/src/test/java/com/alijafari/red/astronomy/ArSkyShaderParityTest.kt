package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.android.ArSkyShaders
import com.alijafari.red.astronomy.ar.core.ArRefraction
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * The camera-off GL sky must expose and colour the sky exactly like the CPU model that the Canvas
 * glyphs (and the no-GL fallback) use. GLSL cannot run on the JVM, so this checks, formula by
 * formula, that the shader carries the same expressions and constants as the Kotlin reference, and
 * that the Kotlin reference evaluates those very expressions.
 */
class ArSkyShaderParityTest {

    private val frag = ArSkyShaders.FRAGMENT.replace(Regex("\\s+"), " ")

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "app/src/main/java/$relative")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Source not found: $relative (searched ${candidates.joinToString()})")
        return file.readText()
    }

    private fun assertInShader(snippet: String) {
        assertTrue("shader must contain: $snippet", frag.contains(snippet.replace(Regex("\\s+"), " ")))
    }

    @Test
    fun `tone curve, Purkinje shift and sRGB encoding are the same expressions`() {
        // ACES (Narkowicz 2015)
        assertInShader("(v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14)")
        for (x in listOf(0.0, 0.01, 0.18, 0.5, 1.0, 4.0)) {
            val v = max(0.0, x)
            assertEquals(((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14)).coerceIn(0.0, 1.0), SkyRenderModel.aces(x), 1e-12)
        }
        // Purkinje (scotopic) mix
        assertInShader("float scot = 0.36 * c.g + 0.62 * c.b + 0.02 * c.r;")
        assertInShader("scot * vec3(0.62, 0.80, 1.18) * uScotopic")
        // Luminance weights (Rec. 709)
        assertInShader("dot(c, vec3(0.2126, 0.7152, 0.0722))")
        assertEquals(0.2126 + 0.7152 + 0.0722, SkyRenderModel.luminance(1.0, 1.0, 1.0), 1e-12)
        // sRGB OETF
        assertInShader("vec3 lo = o * 12.92;")
        assertInShader("1.055 * pow(o, vec3(1.0 / 2.4)) - 0.055")
        assertInShader("step(vec3(0.0031308), o)")
        assertEquals(1.055 * 0.5.pow(1.0 / 2.4) - 0.055, SkyRenderModel.linearToSrgb(0.5), 1e-12)
        assertEquals(0.002 * 12.92, SkyRenderModel.linearToSrgb(0.002), 1e-12)
    }

    @Test
    fun `night background is Krisciunas-Schaefer in both`() {
        assertInShader("float x = 1.0 / sqrt(max(1e-3, 1.0 - 0.96 * sz * sz));")
        assertInShader("uNightNatural * x * pow(clamp(uZenithTv, 0.05, 1.0), x - 1.0)")
        assertInShader("uNightArtificial * (1.0 + 4.0 * exp(-alt / 9.0))")
        assertInShader("nightBackground(altDeg) * vec3(1.0, 1.0, 1.08)")
        val glView = source("com/alijafari/red/astronomy/ar/android/ArSkyGlView.kt")
        assertTrue(glView.contains("SkyRenderModel.NATURAL_ZENITH_MAG_ARCSEC2"))
        assertTrue(glView.contains("SkyRenderModel.bortleZenithMagArcsec2(l.bortle)"))
        assertTrue("landscape gain shared with the CPU model", glView.contains("ArSkyLighting.GROUND_GAIN"))
        assertTrue(glView.contains("SkyRenderModel.exposure(l.adaptLuminance)"))
        assertTrue(glView.contains("SkyRenderModel.scotopicWeight(l.adaptLuminance)"))
        val lighting = source("com/alijafari/red/astronomy/ar/core/ArSkyLighting.kt")
        assertTrue(lighting.contains("nb * 1.08"))
        assertInShader("clamp(-elev / radians(25.0), 0.0, 1.0)")
        assertInShader("uGroundGain * (1.0 - 0.75 * depth)")
        assertTrue(lighting.contains("Math.toRadians(25.0)") && lighting.contains("1.0 - 0.75 * depth"))
    }

    @Test
    fun `Milky Way uses the calibrated surface brightness, exact sRGB decoding and refraction`() {
        assertInShader("mix(mw / 12.92, pow((mw + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), mw))")
        assertEquals(((0.5 + 0.055) / 1.055).pow(2.4), SkyRenderModel.srgbToLinear(0.5), 1e-12)
        assertInShader("vec3 mwL = uMwPeak * pow(mw, vec3(uMwGamma));")
        val glView = source("com/alijafari/red/astronomy/ar/android/ArSkyGlView.kt")
        assertTrue(glView.contains("SkyRenderModel.MILKY_WAY_PEAK_LUMINANCE"))
        assertTrue(glView.contains("SkyRenderModel.MILKY_WAY_GAMMA"))
        // Bennett (1982) apparent → true altitude, scaled by P and T, identical to ArRefraction.
        assertInShader("(1.0 / tan(radians(altDeg + 7.31 / (altDeg + 4.4)))) * uRefrScale / 60.0")
        for (h in listOf(0.0, 2.0, 10.0, 45.0)) {
            val p = 1010.0; val t = 10.0
            val expected = (1.0 / tan(Math.toRadians(h + 7.31 / (h + 4.4)))) / 60.0
            assertEquals(expected, ArRefraction.refractionFromApparentDeg(h, p, t), 1e-9)
        }
        // Kasten & Young (1989) air mass for the extinction of the Milky Way.
        assertInShader("1.0 / (sin(radians(h)) + 0.50572 * pow(h + 6.07995, -1.6364))")
    }
}
