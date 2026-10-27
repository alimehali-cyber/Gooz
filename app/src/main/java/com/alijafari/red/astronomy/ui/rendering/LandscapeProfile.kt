package com.alijafari.red.astronomy.ui.rendering

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Pure 1D terrain-profile math for the Home scene's photographic mountains.
 *
 * The old ridges were two summed sines — smooth, periodic and visibly synthetic. Real Zagros
 * skylines are folded, faulted limestone: sharp crests, clustered detail on the ridges and
 * smooth valleys. That is exactly what Musgrave's **ridged multifractal** produces, so the
 * mountain silhouettes here are built as:
 *
 *   value noise → fBm → domain-warped ridged multifractal → art-directed envelope
 *
 * The envelope (a smoothstep curve through control points) is what makes the result a
 * *composition* rather than noise soup: a low shoulder behind Persepolis and the readout,
 * the great Kuh-e Rahmat crest at the right, ranges that continue off-frame. Everything is
 * deterministic (fixed seeds, no `Random`), allocation-light and free of Compose types, so it
 * is unit-testable on the JVM and mirrors `tools/persepolis/render_framing_preview.py`,
 * where the composition was tuned visually.
 */
internal object LandscapeProfile {

    /** Deterministic 0..1 hash of (`seed`, `index`). Same mixing family as the ground speckle. */
    fun hash01(seed: Int, index: Int): Float {
        var x = seed * 1_103_515_245 + index * 97_531 + 12_345
        x = x xor (x ushr 13)
        x *= 0x5bd1e995
        x = x xor (x ushr 15)
        return (x and 0x7FFFFFFF) / 2_147_483_648f
    }

    /** Smooth 1D value noise in -1..1 (smoothstep-interpolated lattice hashes). */
    fun valueNoise(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = hash01(seed, i)
        val b = hash01(seed, i + 1)
        return (a + (b - a) * u) * 2f - 1f
    }

    /** Fractional Brownian motion in -1..1: octaves at lacunarity 2.13, gain 0.5. */
    fun fbm(x: Float, seed: Int, octaves: Int = 4): Float {
        var sum = 0f
        var amp = 0.55f
        var freq = 1f
        var norm = 0f
        for (o in 0 until octaves) {
            sum += amp * valueNoise(x * freq, seed + o * 17)
            norm += amp
            amp *= 0.5f
            freq *= 2.13f
        }
        return if (norm > 0f) sum / norm else 0f
    }

    /**
     * Ridged multifractal in 0..1 (Musgrave): each octave contributes `(1 - |noise|)²`,
     * weighted by the previous octave so detail accumulates *on* the ridges while valleys
     * stay smooth. The input is domain-warped by a low-frequency fBm first, which folds the
     * crest line instead of leaving it as a regular wave — the single biggest step from
     * "noise" to "mountain range".
     */
    fun ridged(x: Float, seed: Int, octaves: Int = 5): Float {
        val warped = x + 0.10f * fbm(x * 1.7f, seed + 51, octaves = 3)
        var sum = 0f
        var amp = 0.55f
        var freq = 2.6f
        var norm = 0f
        var weight = 1f
        for (o in 0 until octaves) {
            val n = valueNoise(warped * freq, seed + o * 23)
            val r = (1f - abs(n)).pow(2) * weight
            weight = (r * 1.7f).coerceIn(0f, 1f)
            sum += r * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.17f
        }
        return if (norm > 0f) (sum / norm).coerceIn(0f, 1f) else 0f
    }

    /**
     * Smoothstep interpolation through `(x, y)` control points packed as
     * `[x0, y0, x1, y1, …]` with ascending x in 0..1. Outside the ends, the end values hold.
     */
    fun envelope(p: Float, points: FloatArray): Float {
        require(points.size >= 4 && points.size % 2 == 0) { "envelope needs (x, y) pairs" }
        if (p <= points[0]) return points[1]
        var i = 0
        while (i + 3 < points.size) {
            val x0 = points[i]
            val y0 = points[i + 1]
            val x1 = points[i + 2]
            val y1 = points[i + 3]
            if (p <= x1) {
                val t = ((p - x0) / (x1 - x0).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
                val s = t * t * (3f - 2f * t)
                return y0 + (y1 - y0) * s
            }
            i += 2
        }
        return points[points.size - 1]
    }

    /**
     * A mountain skyline: `samples + 1` heights in pixels across the width.
     * Height = [maxHeightPx] × envelope × (0.42 + 0.58·ridged^sharpness) — the 0.42 floor
     * keeps the range a continuous wall (valleys saddle, they never vanish to the plain),
     * while the ridged term carves the crest.
     */
    fun mountainHeights(
        samples: Int,
        maxHeightPx: Float,
        envelopePoints: FloatArray,
        seed: Int,
        sharpness: Float = 1.15f
    ): FloatArray {
        val n = samples.coerceAtLeast(2)
        return FloatArray(n + 1) { i ->
            val p = i / n.toFloat()
            maxHeightPx * envelope(p, envelopePoints) *
                (0.42f + 0.58f * ridged(p, seed).pow(sharpness))
        }
    }

    /** A low, smooth foothill undulation (plain fBm — foothills are eroded, not crested). */
    fun foothillHeights(
        samples: Int,
        maxHeightPx: Float,
        envelopePoints: FloatArray,
        seed: Int,
        frequency: Float = 3.1f
    ): FloatArray {
        val n = samples.coerceAtLeast(2)
        return FloatArray(n + 1) { i ->
            val p = i / n.toFloat()
            maxHeightPx * envelope(p, envelopePoints) *
                (0.45f + 0.55f * (0.5f + 0.5f * fbm(p * frequency, seed)))
        }
    }

    /** Interpolated height of a [mountainHeights] array at horizontal fraction [p] in 0..1. */
    fun heightAt(heights: FloatArray, p: Float): Float {
        if (heights.size < 2) return heights.firstOrNull() ?: 0f
        val x = p.coerceIn(0f, 1f) * (heights.size - 1)
        val i = x.toInt().coerceIn(0, heights.size - 2)
        val f = x - i
        return heights[i] + (heights[i + 1] - heights[i]) * f
    }
}
