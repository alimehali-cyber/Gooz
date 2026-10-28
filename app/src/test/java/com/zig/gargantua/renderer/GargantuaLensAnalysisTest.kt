package com.zig.gargantua.renderer

import com.zig.gargantua.renderer.GargantuaLensAnalysis as L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The lensing / shadow / drag-trace analysis of the forensic run, driven by lens words built from the
 * CPU mirror of the shader integrator (the same contract the GPU lens pass writes).
 */
class GargantuaLensAnalysisTest {

    /** IEEE half, round to nearest even (the GLSL packHalf2x16 contract for normal values). */
    private fun half(v: Float): Int {
        val b = java.lang.Float.floatToIntBits(v)
        val s = (b ushr 16) and 0x8000
        val e = ((b ushr 23) and 0xFF) - 127 + 15
        var m = b and 0x7FFFFF
        if (e <= 0) return s
        if (e >= 31) return s or 0x7C00
        var h = s or (e shl 10) or (m ushr 13)
        m = m and 0x1FFF
        if (m > 0x1000 || (m == 0x1000 && (h and 1) == 1)) h++
        return h
    }

    private fun unorm2(a: Float, b: Float) = Math.round(a.coerceIn(0f, 1f) * 65535f) or (Math.round(b.coerceIn(0f, 1f) * 65535f) shl 16)

    @Test
    fun recordContractEncodeDecodeIsBitExact() {
        val rin = 2.9066f; val rout = 22f
        for (r in listOf(rin, 5.5f, 13.25f, rout)) for (phi in listOf(-3.1f, -1f, 0f, 2.5f, 3.14159f)) for (g in listOf(0.05f, 0.6123f, 1f, 1.37f, 5f)) {
            for (tau in listOf(0.25f, -0.25f)) for (tierFlag in listOf(0, 0x8000)) {
                val x = unorm2((r - rin) / (rout - rin), phi / (2f * PI.toFloat()) + 0.5f)
                val y = (half(g) or tierFlag) or (half(tau) shl 16)
                // Decode exactly what the stored bits hold: 16-bit unorm steps and the half value of g.
                val rUnit = Math.round((r - rin) / (rout - rin) * 65535f) / 65535f
                assertEquals(rin + rUnit * (rout - rin), L.decodeR(x, rin, rout), 0f)
                val pUnit = Math.round((phi / (2f * PI.toFloat()) + 0.5f) * 65535f) / 65535f
                assertEquals((pUnit - 0.5f) * (2f * PI.toFloat()), L.decodePhi(x), 0f)
                assertEquals(L.halfToFloat(half(g)), L.decodeG(y), 0f)
                assertTrue("g within half precision", abs(L.decodeG(y) - g) <= g * 1e-3f)
                // Validity: the tau half is nonzero regardless of the tier flag; HO is the tau sign bit.
                assertTrue(((y and 0xFFFF7FFF.toInt()) and 0xFFFF0000.toInt()) != 0)
                assertEquals(tau < 0f, (y and 0x80000000.toInt()) != 0)
            }
        }
        // A tier-flag-only record (no crossing) is neither valid nor truncated.
        val flagOnly = 0x8000
        assertEquals(0, (flagOnly and 0xFFFF7FFF.toInt()) and 0xFFFF0000.toInt())
        assertEquals(0, flagOnly and 0x7FFF)
    }

    /** Lens words from the CPU mirror on a small ray grid (class from the no-disk ray, crossings from the disk ray). */
    private fun syntheticWords(cam: L.Camera): IntArray {
        val w = cam.rayW; val h = cam.rayH
        val words = IntArray(w * h * 4)
        for (py in 0 until h) for (px in 0 until w) {
            val sx = cam.stX(px + 0.5); val sy = cam.stY(py + 0.5)
            val nd = L.cpuRay(cam, sx, sy, false)
            val cls = if (nd.rayState == 1) L.CAPTURED else if (nd.rayState == 2) L.ESCAPED else L.MIXED
            val acc = L.cpuRay(cam, sx, sy, true).crossings.filter { it.accepted }
            val n = min(2, acc.size)
            val i = (py * w + px) * 4
            var x = cls or (n shl 2)
            for (k in 0 until n) {
                val c = acc[k]
                val ho = c.planeIndex >= 1 || k >= 1
                val xy = unorm2((c.rHit - cam.diskInner) / (cam.diskOuter - cam.diskInner), c.phiHit / (2f * PI.toFloat()) + 0.5f)
                if (k == 0) { words[i + 1] = xy; words[i + 2] = half(c.gShift) or (half(if (ho) -0.5f else 0.5f) shl 16) } else words[i + 3] = xy
                if (ho) x = x or (1 shl (4 + k))
            }
            if (cls == L.CAPTURED && n == 0) x = x or (7 shl 6)
            words[i] = x
        }
        return words
    }

    private fun camera(az: Float) = L.Camera(
        32f, 80f, az, floatArrayOf(0f, 0f, 0f), 1f, 0.8f, 220,
        com.zig.gargantua.disk.KerrIsco.compute(1.0, 0.8).toFloat(), 22f, false, 48, 96
    )

    @Test
    fun shadowBoundaryFromClassificationMatchesTheCpuMirrorAndTheKerrAsymmetry() {
        val cam = camera(0f)
        val words = syntheticWords(cam)
        val r = L.analyze(cam, words)
        L.cpuRows(r)
        assertTrue(r.notes.toString(), r.notes.isEmpty())
        val measured = r.rows.filter { !it.xL.isNaN() && !it.cpuL.isNaN() }
        assertTrue(measured.size >= 7)
        // Pixel-edge boundary vs the bisected CPU boundary: within one ray pixel on every row.
        for (row in measured) {
            assertTrue("row ${row.frac}: ${row.xL} vs ${row.cpuL}", abs(row.xL - row.cpuL) <= 1.0)
            assertTrue("row ${row.frac}: ${row.xR} vs ${row.cpuR}", abs(row.xR - row.cpuR) <= 1.0)
        }
        // Kerr a=0.8, i=80: the right (receding, g<1) edge is the far one; the shadow is vertically symmetric.
        val c = r.centreRow()!!
        val hole = cam.pxOfStX(r.holeSt[0])
        assertTrue(c.xR - hole > 1.6 * (hole - c.xL))
        assertTrue(r.right.meanG < 1.0 && r.left.meanG > 1.0)
        val text = L.text(r, "test")
        assertTrue(text, text.contains("far (retrograde) shadow edge on RIGHT; receding disk side (lower mean g) on RIGHT -> CONSISTENT"))
        // Above the shadow the direct image is the lensed far disk; below, the second crossing is the far disk.
        assertTrue(r.above.farFrac1 > 0.9)
        assertTrue(r.below.farFrac1 < 0.1 && r.below.farFrac2 > 0.9)
        // Higher-order (second plane crossing) pixels concentrate below the shadow at this inclination.
        val q = r.hoQuadrants
        assertTrue(q.contentToString(), q[2] + q[3] > 4 * (q[0] + q[1]))
        // The same rays traced by the CPU mirror agree with the recorded crossings.
        r.cpu = L.cpuSamples(r, words, L.samplePoints(r, words))
        assertTrue(r.cpu.all { it.gpuN == it.cpuN && it.gpuHo == it.cpuHo })
    }

    @Test
    fun azimuthRotationDoesNotChangeTheShadow() {
        val a = L.analyze(camera(0f), syntheticWords(camera(0f)))
        val b = L.analyze(camera(90f), syntheticWords(camera(90f)))
        for (k in a.rows.indices) {
            assertEquals(a.rows[k].xL, b.rows[k].xL, 1.0)
            assertEquals(a.rows[k].xR, b.rows[k].xR, 1.0)
        }
    }

    @Test
    fun bardeenReferenceReducesToSchwarzschildAndGrowsAsymmetricWithSpin() {
        val s = L.bardeenEquatorial(0.001, 80.0)
        assertEquals(3 * sqrt(3.0), s[0], 0.01)
        assertEquals(3 * sqrt(3.0), s[1], 0.01)
        val k = L.bardeenEquatorial(0.8, 80.0)
        assertTrue(k[0] < 3.4 && k[0] > 3.1)
        assertTrue(k[1] > 6.5 && k[1] < 6.8)
    }

    private fun drag(renderedBackStep: Boolean, resume: Boolean): String {
        val ms = 1_000_000L
        val inputs = ArrayList<L.InputEvent>()
        var az = 0f
        inputs.add(L.InputEvent(0, L.ACTION_DOWN, 0f, 0f, az, 80f))
        for (k in 1..30) { az -= -4f * L.TOUCH_DEG_PER_PX; inputs.add(L.InputEvent(k * 8 * ms, L.ACTION_MOVE, -4f, 0f, az, 80f)) }
        inputs.add(L.InputEvent(250 * ms, L.ACTION_UP, 0f, 0f, az, 80f))
        val frames = ArrayList<L.FrameEvent>()
        for (k in 0..7) {
            val t = (k * 32 + 4) * ms
            val last = inputs.last { it.tNanos <= t }
            val a = if (renderedBackStep && k == 5) last.azAfter - 8f else last.azAfter
            frames.add(L.FrameEvent(t, a, 80f, 32f, "REBUILD", 1, false, false, true, 60f, "none"))
        }
        for (k in 1..40) {
            val gate = if (!resume || k < 10) "PLAIN" else if (k == 10) "COMPLETE_CACHE" else "MODULATE"
            frames.add(L.FrameEvent((250 + k * 34) * ms, az, 80f, 32f, gate, if (gate == "COMPLETE_CACHE") 2 else 0, gate == "MODULATE", gate == "MODULATE", false, 5f, "none"))
        }
        return L.dragText(inputs, frames)
    }

    @Test
    fun dragTraceNamesTheFirstBadStageFromMeasuredDeltas() {
        val clean = drag(renderedBackStep = false, resume = true)
        assertTrue(clean, clean.contains("STATE: max |state delta - input delta| = 0.00000 deg"))
        assertTrue(clean, clean.contains("FIRST BAD STAGE (drag): none detected"))
        assertTrue(clean, clean.contains("first MODULATE at +374 ms"))
        val back = drag(renderedBackStep = true, resume = true)
        assertTrue(back, back.contains("FIRST BAD STAGE (drag): PRESENTATION"))
        val stuck = drag(renderedBackStep = false, resume = false)
        assertTrue(stuck, stuck.contains("first MODULATE at NEVER"))
        assertTrue(L.dragText(emptyList(), emptyList()).contains("no complete drag recorded"))
    }
}
