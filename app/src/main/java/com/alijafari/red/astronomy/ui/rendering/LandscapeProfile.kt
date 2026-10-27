package com.alijafari.red.astronomy.ui.rendering

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure profile/growth math for the Home scene's photographic mountains and trees.
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
     *
     * Two geological refinements from the Zagros fold-and-thrust belt:
     *  - [benchAmp]: soft height terracing. Resistant limestone beds form benches and small
     *    cliffs while softer marl slopes wash smooth; blending the profile toward a
     *    soft-quantized version of itself (step = [benchStep] × maxHeight) gives that
     *    stepped rhythm without cartoon stairs.
     *  - [notches]: `(position, width, depth)` triples carving gaussian water-gaps — the
     *    V-valleys where a drainage cuts the ridge and an alluvial fan spills out below.
     */
    fun mountainHeights(
        samples: Int,
        maxHeightPx: Float,
        envelopePoints: FloatArray,
        seed: Int,
        sharpness: Float = 1.15f,
        benchAmp: Float = 0f,
        benchStep: Float = 0.14f,
        notches: FloatArray = FloatArray(0)
    ): FloatArray {
        val n = samples.coerceAtLeast(2)
        return FloatArray(n + 1) { i ->
            val p = i / n.toFloat()
            var h = maxHeightPx * envelope(p, envelopePoints) *
                (0.42f + 0.58f * ridged(p, seed).pow(sharpness))
            if (benchAmp > 0f && maxHeightPx > 0f) {
                val step = maxHeightPx * benchStep.coerceAtLeast(0.01f)
                val q = h / step
                val fl = floor(q)
                val fr = (q - fl - 0.35f) / 0.30f
                val s = fr.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
                val terraced = (fl + s) * step
                h += (terraced - h) * benchAmp.coerceIn(0f, 1f)
            }
            var j = 0
            while (j + 2 < notches.size) {
                val d = (p - notches[j]) / notches[j + 1].coerceAtLeast(1e-4f)
                h *= 1f - notches[j + 2].coerceIn(0f, 0.9f) * exp(-d * d)
                j += 3
            }
            h.coerceAtLeast(0f)
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

    /**
     * One woody segment of a [treeSkeleton], in unit space: the tree stands on (0, 0),
     * reaches y = 1 at its crown top, and spreads x over ±aspect/2. Radii are fractions of
     * total height. [terminal] segments end in a childless node (where foliage belongs).
     */
    data class BranchSegment(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val r0: Float, val r1: Float, val depth: Int, val terminal: Boolean
    )

    /**
     * A tree skeleton grown by 2D **space colonization** (Runions, Lane & Prusinkiewicz
     * 2007): attraction points fill a crown envelope (flattened-top ellipse, hollowed
     * heart — real crowns leaf at their surface), and branch nodes compete for them,
     * growing toward the average of the points they claim, biased by upward tropism.
     * Branching asymmetry, fork angles and the sparse woody interior all *emerge*; nothing
     * is mirrored or hand-placed. Radii then follow da Vinci's pipe model
     * `r_parent^2.05 = Σ r_child^2.05`, so trunks are stout exactly where they carry crown.
     *
     * Deterministic per [seed] and bounded (≤ [MAX_NODES] nodes), so callers cache a few
     * skeletons once and redraw them forever at any scale/flip.
     */
    fun treeSkeleton(
        seed: Int,
        aspect: Float = 1.25f,
        trunkFraction: Float = 0.34f,
        attractorCount: Int = 90,
        lean: Float = 0f
    ): List<BranchSegment> {
        var counter = 0
        fun rnd(): Float = hash01(seed, counter++ * 31 + 7)

        val crownBase = trunkFraction.coerceIn(0.05f, 0.8f)
        val crownH = 1f - crownBase
        val cy = crownBase + crownH / 2f
        val rx = aspect.coerceIn(0.4f, 2.2f) / 2f
        val ry = crownH / 2f

        // Attraction points inside the crown envelope (rejection-sampled, hollow heart).
        val attractors = ArrayList<FloatArray>()
        var guard = 0
        while (attractors.size < attractorCount && guard++ < attractorCount * 40) {
            val x = (rnd() * 2f - 1f) * rx
            var y = cy + (rnd() * 2f - 1f) * ry
            val e = (x / rx) * (x / rx) + ((y - cy) / ry) * ((y - cy) / ry)
            if (e > 1f) continue
            if (e < 0.14f && rnd() < 0.65f) continue // hollow heart: leaves live at the surface
            if (y > cy) y = cy + (y - cy) * 0.82f // flattened umbrella top (pistachio habit)
            attractors.add(floatArrayOf(x + lean * (y - crownBase), y))
        }

        // Nodes: parallel arrays (x, y, parent, depth). Trunk: two segments with the lean,
        // then three scaffold limbs so the crown forks like a real broadleaf.
        val nx = ArrayList<Float>()
        val ny = ArrayList<Float>()
        val parent = ArrayList<Int>()
        val depth = ArrayList<Int>()
        fun addNode(x: Float, y: Float, p: Int): Int {
            nx.add(x); ny.add(y); parent.add(p)
            depth.add(if (p < 0) 0 else depth[p] + 1)
            return nx.size - 1
        }
        val root = addNode(0f, 0f, -1)
        val mid = addNode(lean * 0.4f, crownBase * 0.55f, root)
        val fork = addNode(lean, crownBase, mid)
        val step = crownH / 13f
        for (angleDeg in listOf(-52f, -8f, 44f)) {
            val a = angleDeg * (Math.PI.toFloat() / 180f)
            addNode(
                nx[fork] + kotlin.math.sin(a) * step * 1.7f,
                ny[fork] + kotlin.math.cos(a) * step * 1.7f,
                fork
            )
        }

        val influence = step * 4.4f
        val kill = step * 1.15f
        val alive = BooleanArray(attractors.size) { true }
        var aliveCount = attractors.size
        var iterations = 0
        while (aliveCount > 0 && iterations++ < 110 && nx.size < MAX_NODES) {
            // Assign each live attractor to its nearest node; kill the reached ones.
            val claims = HashMap<Int, FloatArray>() // node -> [sumDirX, sumDirY, count]
            for (ai in attractors.indices) {
                if (!alive[ai]) continue
                val ax = attractors[ai][0]
                val ay = attractors[ai][1]
                var best = -1
                var bestD = Float.MAX_VALUE
                for (ni in nx.indices) {
                    if (ni == root || ni == mid) continue // trunk does not chase leaves
                    val dx = ax - nx[ni]
                    val dy = ay - ny[ni]
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d
                        best = ni
                    }
                }
                if (best < 0) continue
                val dist = sqrt(bestD)
                if (dist < kill) {
                    alive[ai] = false
                    aliveCount--
                } else if (dist < influence) {
                    val c = claims.getOrPut(best) { floatArrayOf(0f, 0f, 0f) }
                    c[0] += (ax - nx[best]) / dist
                    c[1] += (ay - ny[best]) / dist
                    c[2] += 1f
                }
            }
            if (claims.isEmpty()) break
            // Grow one child per claimed node, toward its points + tropism.
            for ((node, c) in claims) {
                if (nx.size >= MAX_NODES) break
                var dx = c[0] / c[2]
                var dy = c[1] / c[2] + 0.20f // phototropism
                dx += (nx[node] - lean) * 0.10f // gentle outward spread
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-5f)
                val s = step * (0.85f + 0.3f * rnd())
                addNode(nx[node] + dx / len * s, ny[node] + dy / len * s, node)
            }
        }

        // Pipe-model radii (da Vinci exponent 2.05), normalised so the trunk base reads
        // stout at landscape scale; a floor keeps twigs drawable.
        val children = Array(nx.size) { ArrayList<Int>() }
        for (i in nx.indices) if (parent[i] >= 0) children[parent[i]].add(i)
        val radius = FloatArray(nx.size)
        fun computeRadius(i: Int): Float {
            val r = if (children[i].isEmpty()) {
                1f
            } else {
                var sum = 0f
                for (ch in children[i]) sum += computeRadius(ch).pow(2.05f)
                sum.pow(1f / 2.05f) * 1.03f
            }
            radius[i] = r
            return r
        }
        computeRadius(root)
        val scaleR = 0.040f / radius[root].coerceAtLeast(1e-5f)
        for (i in radius.indices) radius[i] = (radius[i] * scaleR).coerceAtLeast(0.0035f)

        val out = ArrayList<BranchSegment>(nx.size - 1)
        for (i in nx.indices) {
            val p = parent[i]
            if (p < 0) continue
            out.add(
                BranchSegment(
                    x0 = nx[p], y0 = ny[p], x1 = nx[i], y1 = ny[i],
                    r0 = radius[p], r1 = radius[i],
                    depth = depth[i], terminal = children[i].isEmpty()
                )
            )
        }
        return out
    }

    private const val MAX_NODES = 420

    /**
     * Shared traits of one Voronoi grass clump (the Ghost-of-Tsushima lesson: fields are
     * not random blade-by-blade, they are *clumpy* — soil and moisture make whole patches
     * share height, lean, dryness and density). [edge01] is 0 at a clump border and 1 deep
     * inside, so callers can soften traits toward neutral at borders instead of showing
     * hard Voronoi seams.
     */
    data class GrassClump(
        val heightMul: Float,
        val lean: Float,
        val dryness01: Float,
        val density01: Float,
        val edge01: Float
    )

    /**
     * The clump at horizontal fraction [xFrac] (0..1 across the scene) and ground [depth]
     * (0 at the horizon line, 1 at the viewer's feet): nearest centre of a jittered-grid
     * Voronoi (3×3 search), traits hashed from that centre, edge factor from F2 − F1.
     * Deterministic per [seed]; nearby points in the same cell get identical traits.
     */
    fun grassClump(xFrac: Float, depth: Float, seed: Int): GrassClump {
        val cellW = 0.055f
        val cellD = 0.085f
        val gx = (xFrac / cellW).toInt()
        val gy = (depth / cellD).toInt()
        var f1 = Float.MAX_VALUE
        var f2 = Float.MAX_VALUE
        var bestIndex = 0
        for (cy in gy - 1..gy + 1) {
            for (cx in gx - 1..gx + 1) {
                val index = cx + cy * 64
                val jx = (cx + 0.5f + (hash01(seed, index * 3 + 1) - 0.5f) * 0.84f) * cellW
                val jy = (cy + 0.5f + (hash01(seed, index * 5 + 2) - 0.5f) * 0.84f) * cellD
                val dx = xFrac - jx
                val dy = depth - jy
                val d = dx * dx + dy * dy
                if (d < f1) {
                    f2 = f1
                    f1 = d
                    bestIndex = index
                } else if (d < f2) {
                    f2 = d
                }
            }
        }
        val edge = ((sqrt(f2) - sqrt(f1)) / (cellW * 0.9f)).coerceIn(0f, 1f)
        return GrassClump(
            heightMul = 0.70f + 0.65f * hash01(seed + 7, bestIndex * 11 + 3),
            lean = (hash01(seed + 13, bestIndex * 17 + 5) - 0.5f) * 1.1f,
            dryness01 = hash01(seed + 19, bestIndex * 23 + 7),
            density01 = hash01(seed + 29, bestIndex * 31 + 9),
            edge01 = edge
        )
    }

    /**
     * Lateral bend profile of one grass blade: fraction of the tip's total lean reached at
     * height fraction [t], as a cubic Bézier blend. [stiffness] 0 = supple (bend comes
     * late, mostly near the tip — dry wiry blades), 1 = stiff (bend distributed from the
     * base — lush blades). Monotonic in [t], 0 at the root, 1 at the tip.
     */
    fun bladeBendX(t: Float, stiffness: Float): Float {
        val tt = t.coerceIn(0f, 1f)
        val s = stiffness.coerceIn(0f, 1f)
        val c1 = 0.08f + 0.30f * s
        val c2 = 0.45f + 0.35f * s
        val u = 1f - tt
        return 3f * u * u * tt * c1 + 3f * u * tt * tt * c2 + tt * tt * tt
    }
}
