package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.EquirectTexture
import com.alijafari.red.astronomy.ar.core.GlyphRaster
import com.alijafari.red.astronomy.ar.core.MoonLook
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Moon glyph ([GlyphRaster.renderMoon]): the sunlit part is where the Sun shines, the earthlit night
 * side appears only when the sky is dark enough, the Moon's light never darkens the sky in front of
 * it, and the daytime Moon is pale.
 */
class MoonRenderTest {

    private val size = 132
    private val radius = 60.0
    private val grey = doubleArrayOf(0.30, 0.29, 0.28)

    /** Geometry with lunar north up and the Sun at phase angle [phaseDeg], bright limb at position angle [paDeg]. */
    private fun geometry(phaseDeg: Double, paDeg: Double): GlyphRaster.Geometry {
        val i = Math.toRadians(phaseDeg); val pa = Math.toRadians(paDeg)
        // Sprite frame: x = west (image right), y = north (image up), z = towards the observer.
        val sun = doubleArrayOf(-sin(pa) * sin(i), cos(pa) * sin(i), cos(i))
        return GlyphRaster.Geometry(pole = doubleArrayOf(0.0, 1.0, 0.0), primeMeridian = doubleArrayOf(0.0, 0.0, 1.0), sunDir = sun)
    }

    /** A map of bright highlands and dark maria in 20° bands of longitude. */
    private val banded: EquirectTexture by lazy {
        val w = 360; val h = 180
        EquirectTexture.fromArgb(w, h, IntArray(w * h) { k ->
            val v = if ((k % w) / 20 % 2 == 0) 0xB8 else 0x60
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        })
    }

    private fun render(
        phaseDeg: Double, paDeg: Double, sky: Double, skyDisplay: DoubleArray?, minLit: Double = 0.0,
        texture: EquirectTexture? = null
    ): IntArray {
        val out = IntArray(size * size)
        GlyphRaster.renderMoon(
            size, radius, texture, grey, geometry(phaseDeg, paDeg),
            MoonLook(384_400.0, 1.0, sky, 0.85, minLit), skyDisplay, out
        )
        return out
    }

    /** μ₀ (cosine of the solar incidence) at a sprite pixel, or NaN off the disc. */
    private fun mu0(geo: GlyphRaster.Geometry, i: Int, j: Int): Double {
        val x = (i + 0.5 - size / 2.0) / radius; val y = -(j + 0.5 - size / 2.0) / radius
        val r2 = x * x + y * y
        if (r2 >= 0.92) return Double.NaN // keep away from the limb's anti-aliasing
        val z = sqrt(1 - r2)
        val s = geo.sunDir
        return (x * s[0] + y * s[1] + z * s[2]) / sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
    }

    /** Premultiplied light (max channel) of a straight-ARGB pixel. */
    private fun light(p: Int): Double {
        val a = ((p ushr 24) and 255) / 255.0
        val m = maxOf((p shr 16) and 255, (p shr 8) and 255, p and 255) / 255.0
        return a * m
    }

    @Test
    fun `sunlit where the Sun shines and dark elsewhere at night`() {
        for ((phase, pa) in listOf(30.0 to 0.0, 90.0 to 250.0, 120.0 to 70.0, 150.0 to 180.0)) {
            val geo = geometry(phase, pa)
            val px = render(phase, pa, 3e-4, null)
            var checked = 0
            for (j in 0 until size) for (i in 0 until size) {
                val m = mu0(geo, i, j)
                if (m.isNaN()) continue
                val l = light(px[j * size + i])
                if (m > 0.10) { assertTrue("phase $phase: lit pixel too dark ($l)", l > 0.25); checked++ }
                if (m < -0.10) { assertTrue("phase $phase: night pixel too bright ($l)", l < 0.15); checked++ }
            }
            assertTrue(checked > 1000)
        }
    }

    @Test
    fun `earthlit night side appears in a dark sky and vanishes by day`() {
        val geo = geometry(150.0, 90.0)
        val night = render(150.0, 90.0, 3e-4, null)
        val day = render(150.0, 90.0, 5000.0, null)
        var sumNight = 0.0; var maxDay = 0.0; var n = 0
        for (j in 0 until size) for (i in 0 until size) {
            val m = mu0(geo, i, j)
            if (m.isNaN() || m > -0.2) continue
            sumNight += light(night[j * size + i]); maxDay = maxOf(maxDay, light(day[j * size + i])); n++
        }
        val meanNight = sumNight / n
        assertTrue("earthshine at night = $meanNight", meanNight in 0.02..0.15)
        assertEquals(0.0, maxDay, 0.0)
        // A gibbous Moon's night side shows no earthshine even at night (faint Earth, glare of the bright side).
        val gibbousGeo = geometry(40.0, 90.0)
        val gibbous = render(40.0, 90.0, 3e-4, null)
        for (j in 0 until size) for (i in 0 until size) {
            val m = mu0(gibbousGeo, i, j)
            if (!m.isNaN() && m < -0.2) assertTrue(light(gibbous[j * size + i]) < 0.01)
        }
    }

    @Test
    fun `the Moon never looks darker than the sky in front of it`() {
        val skies = listOf(
            3e-4 to doubleArrayOf(0.012, 0.014, 0.025),
            0.1 to doubleArrayOf(0.05, 0.08, 0.18),
            3.0 to doubleArrayOf(0.28, 0.38, 0.62),
            5000.0 to doubleArrayOf(0.45, 0.62, 0.90)
        )
        for ((b, sky) in skies) for (phase in listOf(0.0, 90.0, 150.0)) {
            val px = render(phase, 300.0, b, sky)
            val geo = geometry(phase, 300.0)
            for (j in 0 until size) for (i in 0 until size) {
                if (mu0(geo, i, j).isNaN()) continue
                val p = px[j * size + i]
                assertEquals("opaque inside the limb", 255, (p ushr 24) and 255)
                val c = intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
                for (q in 0..2) assertTrue("B=$b phase=$phase channel $q", c[q] >= (sky[q] * 255).toInt() - 1)
            }
        }
    }

    @Test
    fun `daytime Moon is pale`() {
        val sky = doubleArrayOf(0.45, 0.62, 0.90)
        fun contrastAndMean(b: Double, skyColour: DoubleArray): Pair<Double, Double> {
            val px = render(0.0, 0.0, b, skyColour, texture = banded)
            val geo = geometry(0.0, 0.0)
            val v = ArrayList<Double>()
            for (j in 0 until size) for (i in 0 until size) {
                if (mu0(geo, i, j).isNaN()) continue
                val p = px[j * size + i]
                v.add(SkyRenderModel.luminance(((p shr 16) and 255) / 255.0, ((p shr 8) and 255) / 255.0, (p and 255) / 255.0))
            }
            val mean = v.average()
            val sd = sqrt(v.sumOf { (it - mean) * (it - mean) } / v.size)
            return sd to mean
        }
        val (sdNight, _) = contrastAndMean(3e-4, doubleArrayOf(0.0, 0.0, 0.0))
        val (sdDay, meanDay) = contrastAndMean(5000.0, sky)
        assertTrue("daytime disc keeps less contrast ($sdDay vs $sdNight)", sdDay < 0.6 * sdNight)
        assertTrue("and is brighter than the sky", meanDay > SkyRenderModel.luminance(sky[0], sky[1], sky[2]))
    }

    @Test
    fun `over the camera the sunlit Moon stays findable in daylight`() {
        val geo = geometry(150.0, 90.0)
        val physical = render(150.0, 90.0, 8000.0, null)
        val camera = render(150.0, 90.0, 8000.0, null, minLit = 0.35)
        var maxPhysical = 0.0; var maxCamera = 0.0
        for (j in 0 until size) for (i in 0 until size) {
            val m = mu0(geo, i, j)
            if (m.isNaN() || m < 0.1) continue
            maxPhysical = maxOf(maxPhysical, light(physical[j * size + i]))
            maxCamera = maxOf(maxCamera, light(camera[j * size + i]))
        }
        assertTrue("a two-day crescent is all but invisible in daylight ($maxPhysical)", maxPhysical < 0.08)
        assertTrue("camera mode keeps it visible ($maxCamera)", maxCamera > 0.15)
    }

    @Test
    fun `albedo scale is the projected-area mean of the near side`() {
        val w = 64; val h = 32
        val uniform = IntArray(w * h) { (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80 }
        val t = EquirectTexture.fromArgb(w, h, uniform)
        assertEquals(SkyRenderModel.srgbToLinear(128 / 255.0), t.nearSideMeanLuminance, 1e-9)
    }
}
