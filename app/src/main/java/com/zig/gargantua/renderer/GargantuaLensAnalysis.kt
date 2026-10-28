package com.zig.gargantua.renderer

import com.zig.gargantua.geodesic.GpuEquivalentIntegrator
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * TEMPORARY forensic analysis (pure JVM, no GL): the lensing / shadow / photon-ring measurements from
 * the lens words written by gargantua_diag_lens.frag, the CPU mirror of the same rays
 * ([GpuEquivalentIntegrator]), the Bardeen equatorial shadow reference and the camera-drag trace.
 *
 * Screen convention (gargantua_geodesic.frag main): st = (fragCoord * 2 - res) / min(res.x, res.y) with
 * fragCoord in ray pixels, y up (GL window coordinates; readPixels rows are bottom-up). The ray direction
 * is normalize(forward + right * st.x * fovScale + up * st.y * fovScale).
 */
internal object GargantuaLensAnalysis {

    const val CAPTURED = 0
    const val ESCAPED = 1
    const val MIXED = 2
    val ROW_FRACTIONS = floatArrayOf(-0.8f, -0.6f, -0.4f, -0.2f, 0f, 0.2f, 0.4f, 0.6f, 0.8f)
    val STAGES = arrayOf("D0", "D5", "D6")

    fun cls(x: Int) = x and 3
    fun crossings(x: Int) = (x ushr 2) and 3
    fun ho1(x: Int) = (x ushr 4) and 1
    fun ho2(x: Int) = (x ushr 5) and 1
    fun dark(x: Int, stage: Int) = (x ushr (6 + stage)) and 1
    fun noDiskAlpha(x: Int) = (x ushr 16) / 1000f

    /** Camera and scene exactly as the renderer uploads them for the traced frame. */
    class Camera(
        val distance: Float, val inclinationDeg: Float, val azimuthDeg: Float,
        val target: FloatArray, val mass: Float, val spinA: Float, val maxSteps: Int,
        val diskInner: Float, val diskOuter: Float, val enableDoppler: Boolean,
        val rayW: Int, val rayH: Int
    ) {
        val fovScale: Double = tan(Math.toRadians(22.5))
        /** [camPos, forward, right, up] exactly as GargantuaRenderer computes them. */
        val basis: Array<DoubleArray> by lazy {
            val i = Math.toRadians(inclinationDeg.toDouble()); val a = Math.toRadians(azimuthDeg.toDouble())
            val d = doubleArrayOf(sin(i) * cos(a), sin(i) * sin(a), cos(i))
            val c = DoubleArray(3) { target[it] + distance * d[it] }
            val fr = DoubleArray(3) { target[it] - c[it] }
            val fl = sqrt(fr[0] * fr[0] + fr[1] * fr[1] + fr[2] * fr[2])
            val f = if (fl > 1e-6) DoubleArray(3) { fr[it] / fl } else DoubleArray(3) { -d[it] }
            val r = doubleArrayOf(-sin(a), cos(a), 0.0)
            val u = doubleArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
            val ul = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
            arrayOf(c, f, r, if (ul > 1e-6) DoubleArray(3) { u[it] / ul } else doubleArrayOf(0.0, 0.0, 1.0))
        }
        fun stX(px: Double) = (px * 2 - rayW) / min(rayW, rayH).toDouble()
        fun stY(py: Double) = (py * 2 - rayH) / min(rayW, rayH).toDouble()
        fun pxOfStX(st: Double) = (st * min(rayW, rayH) + rayW) / 2
        fun pxOfStY(st: Double) = (st * min(rayW, rayH) + rayH) / 2
        /** Screen st of the black hole (origin) for this camera. */
        fun holeSt(): DoubleArray {
            val b = basis
            val v = DoubleArray(3) { -b[0][it] }
            val z = dot(v, b[1])
            return doubleArrayOf(dot(v, b[2]) / z / fovScale, dot(v, b[3]) / z / fovScale)
        }
        /** Transverse size in M at the hole of a screen offset in st units. */
        fun stToM(st: Double) = st * fovScale * distance
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    // ------------------------------------------------------------------ record decode (same contract as the shader)

    fun halfToFloat(h: Int): Float {
        val s = (h ushr 15) and 1; val e = (h ushr 10) and 0x1F; val m = h and 0x3FF
        val v = when (e) {
            0 -> m / 1024f * 2f.pow(-14)
            31 -> if (m == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> (1f + m / 1024f) * 2f.pow(e - 15)
        }
        return if (s == 1) -v else v
    }
    private fun Float.pow(n: Int): Float = Math.pow(this.toDouble(), n.toDouble()).toFloat()

    fun decodeR(word: Int, rin: Float, rout: Float) = rin + (word and 0xFFFF) / 65535f * (rout - rin)
    fun decodePhi(word: Int) = (((word ushr 16) and 0xFFFF) / 65535f - 0.5f) * (2f * PI.toFloat())
    /** g of crossing 1 (low half of the y word, the tier flag bit 15 masked off; g > 0 always). */
    fun decodeG(word: Int) = halfToFloat(word and 0x7FFF)

    // ------------------------------------------------------------------ shadow geometry from classification

    class Row(
        val frac: Float, val py: Int, val sy: Double,
        /** Boundary positions in ray pixels (edge coordinates), NaN when not found. */
        val xL: Double, val xR: Double,
        /** Dark-region boundaries per stage D0/D5/D6 in ray pixels (NaN when the scan start is not dark). */
        val darkL: DoubleArray, val darkR: DoubleArray,
        var cpuL: Double = Double.NaN, var cpuR: Double = Double.NaN
    )

    class SideStat(val pixels: Long, val meanG: Double, val fracApproach: Double, val meanR: Double)

    class Band(val pixels: Long, val diskFrac: Double, val twoCrossFrac: Double, val farFrac1: Double, val farFrac2: Double,
               val hoFrac: Double, val medianR1: Double, val medianR2: Double)

    class CpuSample(val px: Int, val py: Int, val gpuCls: Int, val cpuState: Int, val gpuN: Int, val cpuN: Int,
                    val gpuR: Float, val cpuR: Float, val gpuPhi: Float, val cpuPhi: Float, val gpuG: Float, val cpuG: Float,
                    val gpuHo: Int, val cpuHo: Int)

    class Result(
        val camera: Camera,
        val captured: Long, val escaped: Long, val mixed: Long,
        val centreX: Double, val top: Double, val bottom: Double,
        val rows: List<Row>,
        val holeSt: DoubleArray,
        val left: SideStat, val right: SideStat,
        val above: Band, val below: Band,
        /** HO pixels per quadrant relative to the hole: UL, UR, LL, LR. */
        val hoQuadrants: LongArray,
        val notes: List<String>
    ) {
        @Volatile var cpu: List<CpuSample> = emptyList()
        @Volatile var cpuNote: String = "CPU mirror not run"
        fun centreRow(): Row? = rows.firstOrNull { it.frac == 0f }
    }

    fun analyze(cam: Camera, words: IntArray): Result {
        val w = cam.rayW; val h = cam.rayH
        val notes = ArrayList<String>()
        fun x(px: Int, py: Int) = words[(py * w + px) * 4]
        fun captured(px: Int, py: Int) = px in 0 until w && py in 0 until h && cls(x(px, py)) == CAPTURED
        var nc = 0L; var ne = 0L; var nm = 0L; var sx = 0.0; var sy = 0.0
        for (py in 0 until h) for (px in 0 until w) when (cls(x(px, py))) {
            CAPTURED -> { nc++; sx += px; sy += py }
            ESCAPED -> ne++
            else -> nm++
        }
        val hole = cam.holeSt()
        val hx = cam.pxOfStX(hole[0]); val hy = cam.pxOfStY(hole[1])
        val emptySide = SideStat(0, Double.NaN, Double.NaN, Double.NaN)
        val emptyBand = Band(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        if (nc == 0L) {
            notes.add("no captured ray pixel")
            return Result(cam, nc, ne, nm, Double.NaN, Double.NaN, Double.NaN, emptyList(), hole, emptySide, emptySide, emptyBand, emptyBand, LongArray(4), notes)
        }
        var cx = (sx / nc).toInt(); var cy = (sy / nc).toInt()
        if (!captured(cx, cy)) {
            notes.add("capture centroid ($cx,$cy) is not captured; using the hole projection")
            cx = hx.toInt(); cy = hy.toInt()
            if (!captured(cx, cy)) {
                notes.add("hole projection not captured either")
                return Result(cam, nc, ne, nm, Double.NaN, Double.NaN, Double.NaN, emptyList(), hole, emptySide, emptySide, emptyBand, emptyBand, LongArray(4), notes)
            }
        }
        fun runL(py: Int, from: Int, test: (Int) -> Boolean): Double { var p = from; if (!test(p)) return Double.NaN; while (p - 1 >= 0 && test(p - 1)) p--; return p.toDouble() }
        fun runR(py: Int, from: Int, test: (Int) -> Boolean): Double { var p = from; if (!test(p)) return Double.NaN; while (p + 1 < w && test(p + 1)) p++; return (p + 1).toDouble() }
        // Horizontal centre from the centroid row, vertical extent on that column (edge coordinates).
        val l0 = runL(cy, cx) { captured(it, cy) }; val r0 = runR(cy, cx) { captured(it, cy) }
        val col = ((l0 + r0) / 2).toInt()
        var t = cy; while (t + 1 < h && captured(col, t + 1)) t++
        var b = cy; while (b - 1 >= 0 && captured(col, b - 1)) b--
        val top = (t + 1).toDouble(); val bottom = b.toDouble()
        val midY = (top + bottom) / 2; val half = (top - bottom) / 2
        val rows = ROW_FRACTIONS.map { f ->
            val py = (midY + f * half - 0.5).toInt().coerceIn(0, h - 1)
            val seed = col
            val xl = runL(py, seed) { captured(it, py) }
            val xr = runR(py, seed) { captured(it, py) }
            val dl = DoubleArray(3); val dr = DoubleArray(3)
            for (s in 0..2) {
                dl[s] = runL(py, seed) { dark(x(it, py), s) == 1 }
                dr[s] = runR(py, seed) { dark(x(it, py), s) == 1 }
            }
            Row(f, py, cam.stY(py + 0.5), xl, xr, dl, dr)
        }
        // Disk crossing statistics.
        val rin = cam.diskInner; val rout = cam.diskOuter
        val az = Math.toRadians(cam.azimuthDeg.toDouble())
        fun far(phi: Float): Boolean { var d = phi - az; while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI; return abs(d) > PI / 2 }
        class Acc { var n = 0L; var g = 0.0; var ap = 0L; var r = 0.0 }
        val accL = Acc(); val accR = Acc()
        val quad = LongArray(4)
        for (py in 0 until h) for (px in 0 until w) {
            val i = (py * w + px) * 4
            val xw = words[i]
            val n = crossings(xw)
            if (n == 0) continue
            if (ho1(xw) == 1 || ho2(xw) == 1) quad[(if (py >= hy) 0 else 2) + (if (px >= hx) 1 else 0)]++
            if (ho1(xw) == 1) continue // side statistics use the first crossing when it is the direct image
            val g = decodeG(words[i + 2]).toDouble()
            val a = if (px < hx) accL else accR
            a.n++; a.g += g; if (g > 1.0) a.ap++; a.r += decodeR(words[i + 1], rin, rout)
        }
        fun side(a: Acc) = if (a.n == 0L) emptySide else SideStat(a.n, a.g / a.n, a.ap.toDouble() / a.n, a.r / a.n)
        val cRow = rows.firstOrNull { it.frac == 0f }
        val xL = cRow?.xL ?: l0; val xR = cRow?.xR ?: r0
        fun band(y0: Int, y1: Int): Band {
            var n = 0L; var disk = 0L; var two = 0L; var far1 = 0L; var far2 = 0L; var ho = 0L
            val r1 = ArrayList<Float>(); val r2 = ArrayList<Float>()
            if (xL.isNaN() || xR.isNaN()) return emptyBand
            for (py in max(0, min(y0, y1)) until min(h, max(y0, y1))) for (px in xL.toInt() until xR.toInt()) {
                val i = (py * w + px) * 4
                val xw = words[i]
                n++
                val c = crossings(xw)
                if (c == 0) continue
                disk++
                if (ho1(xw) == 1 || ho2(xw) == 1) ho++
                if (far(decodePhi(words[i + 1]))) far1++
                r1.add(decodeR(words[i + 1], rin, rout))
                if (c == 2) { two++; if (far(decodePhi(words[i + 3]))) far2++; r2.add(decodeR(words[i + 3], rin, rout)) }
            }
            fun med(l: ArrayList<Float>) = if (l.isEmpty()) Double.NaN else l.sorted()[l.size / 2].toDouble()
            return Band(n, if (n > 0) disk.toDouble() / n else Double.NaN, if (disk > 0) two.toDouble() / disk else Double.NaN,
                if (disk > 0) far1.toDouble() / disk else Double.NaN, if (two > 0) far2.toDouble() / two else Double.NaN,
                if (disk > 0) ho.toDouble() / disk else Double.NaN, med(r1), med(r2))
        }
        val bandH = max(4, half.toInt())
        return Result(cam, nc, ne, nm, (xL + xR) / 2, top, bottom, rows, hole, side(accL), side(accR),
            band(top.toInt(), top.toInt() + bandH), band(bottom.toInt() - bandH, bottom.toInt()), quad, notes)
    }

    // ------------------------------------------------------------------ CPU mirror of the same rays

    fun cpuRay(cam: Camera, stx: Double, sty: Double, disk: Boolean): GpuEquivalentIntegrator.GpuRayResult {
        val b = cam.basis
        val d = DoubleArray(3) { b[1][it] + b[2][it] * stx * cam.fovScale + b[3][it] * sty * cam.fovScale }
        val l = sqrt(dot(d, d))
        return GpuEquivalentIntegrator.traceRay(
            cam.mass, cam.spinA, floatArrayOf(b[0][0].toFloat(), b[0][1].toFloat(), b[0][2].toFloat()),
            floatArrayOf((d[0] / l).toFloat(), (d[1] / l).toFloat(), (d[2] / l).toFloat()),
            maxSteps = cam.maxSteps, enableDisk = disk, diskInnerRadius = cam.diskInner, diskOuterRadius = cam.diskOuter,
            enableDoppler = cam.enableDoppler
        )
    }

    /** CPU capture boundary (in ray pixels, edge coordinates) on each GPU row, bisecting from the GPU seed column. */
    fun cpuRows(result: Result) {
        val cam = result.camera
        for (row in result.rows) {
            val seed = cam.stX(result.centreX)
            if (cpuRay(cam, seed, row.sy, false).rayState != 1) continue
            fun edge(outside: Double): Double {
                var a = seed; var bb = outside
                repeat(24) { val m = 0.5 * (a + bb); if (cpuRay(cam, m, row.sy, false).rayState == 1) a = m else bb = m }
                return cam.pxOfStX(0.5 * (a + bb))
            }
            row.cpuL = edge(cam.stX(0.0) - 0.5)
            row.cpuR = edge(cam.stX(cam.rayW.toDouble()) + 0.5)
        }
    }

    /** First/second accepted crossings of the CPU mirror at GPU pixel centres (disk on), for [points]. */
    fun cpuSamples(result: Result, words: IntArray, points: List<IntArray>): List<CpuSample> {
        val cam = result.camera
        return points.map { (px, py) ->
            val i = (py * cam.rayW + px) * 4
            val xw = words[i]
            val r = cpuRay(cam, cam.stX(px + 0.5), cam.stY(py + 0.5), true)
            val acc = r.crossings.filter { it.accepted }
            val c1 = acc.getOrNull(0)
            val gpuN = crossings(xw)
            // Shader: higherOrderCrossing = equatorialCrossings >= 2 || diskCrossings >= 2 at the crossing.
            val cpuHo = if (c1 == null) 0 else if (c1.planeIndex >= 1) 1 else 0
            CpuSample(px, py, cls(xw), r.rayState, gpuN, min(2, acc.size),
                if (gpuN > 0) decodeR(words[i + 1], cam.diskInner, cam.diskOuter) else Float.NaN, c1?.rHit ?: Float.NaN,
                if (gpuN > 0) decodePhi(words[i + 1]) else Float.NaN, c1?.phiHit ?: Float.NaN,
                if (gpuN > 0) decodeG(words[i + 2]) else Float.NaN, c1?.gShift ?: Float.NaN,
                ho1(xw), cpuHo)
        }
    }

    /** A deterministic spread of sample pixels: disk pixels on a grid plus the centre-row shadow edges. */
    fun samplePoints(result: Result, words: IntArray, count: Int = 24): List<IntArray> {
        val cam = result.camera
        val out = ArrayList<IntArray>()
        val step = max(1, (sqrt(cam.rayW.toDouble() * cam.rayH / (count * 3.0))).toInt())
        var py = step / 2
        while (py < cam.rayH && out.size < count) {
            var px = step / 2
            while (px < cam.rayW && out.size < count) {
                if (crossings(words[(py * cam.rayW + px) * 4]) > 0) out.add(intArrayOf(px, py))
                px += step
            }
            py += step
        }
        result.centreRow()?.let { r ->
            if (!r.xL.isNaN()) { out.add(intArrayOf((r.xL - 1).toInt().coerceAtLeast(0), r.py)); out.add(intArrayOf(r.xL.toInt(), r.py)) }
            if (!r.xR.isNaN()) { out.add(intArrayOf((r.xR - 1).toInt(), r.py)); out.add(intArrayOf(r.xR.toInt().coerceAtMost(cam.rayW - 1), r.py)) }
        }
        return out
    }

    // ------------------------------------------------------------------ Bardeen reference (distant observer, M = 1)

    /**
     * Equatorial-row (beta = 0) edges of the Kerr shadow for spin a and inclination i, in units of M:
     * returns [alphaPrograde, alphaRetrograde] as magnitudes (the retrograde edge is the farther one).
     */
    fun bardeenEquatorial(a: Double, inclDeg: Double): DoubleArray {
        val i = Math.toRadians(inclDeg)
        val rPro = 2 * (1 + cos(2.0 / 3.0 * acos(-a)))
        val rRet = 2 * (1 + cos(2.0 / 3.0 * acos(a)))
        fun xi(r: Double) = (r * r * (3 - r) - a * a * (r + 1)) / (a * (r - 1))
        fun eta(r: Double) = r * r * r * (4 * a * a - r * (r - 3) * (r - 3)) / (a * a * (r - 1) * (r - 1))
        fun beta2(r: Double) = eta(r) + a * a * cos(i) * cos(i) - xi(r) * xi(r) / (tan(i) * tan(i))
        fun root(lo0: Double, hi0: Double): Double {
            var lo = lo0; var hi = hi0
            val slo = beta2(lo) >= 0
            repeat(80) { val m = 0.5 * (lo + hi); if ((beta2(m) >= 0) == slo) lo = m else hi = m }
            return 0.5 * (lo + hi)
        }
        // beta^2 >= 0 on an interval inside [rPro, rRet]; find where it turns positive from both ends.
        var peak = rPro; var best = -1e30
        for (k in 0..400) { val r = rPro + (rRet - rPro) * k / 400.0; val v = beta2(r); if (v > best) { best = v; peak = r } }
        val r1 = root(rPro, peak); val r2 = root(peak, rRet)
        return doubleArrayOf(abs(xi(r1) / sin(i)), abs(xi(r2) / sin(i)))
    }

    // ------------------------------------------------------------------ report text

    private fun f(v: Double, d: Int = 1) = if (v.isNaN()) "n/a" else String.format(Locale.US, "%.${d}f", v)

    fun text(result: Result, label: String): String {
        // Read the volatile note first: it orders the worker's row/sample writes before the reads below.
        val cpuNote = result.cpuNote
        val sb = StringBuilder()
        val cam = result.camera
        fun ln(s: String) { sb.append(s).append('\n') }
        ln("LENS [$label] camera dist=${f(cam.distance.toDouble(), 2)} incl=${f(cam.inclinationDeg.toDouble(), 2)} az=${f(cam.azimuthDeg.toDouble(), 2)} " +
            "a=${f(cam.spinA.toDouble(), 3)} rin=${f(cam.diskInner.toDouble(), 4)} rout=${f(cam.diskOuter.toDouble())} steps=${cam.maxSteps} ray=${cam.rayW}x${cam.rayH}")
        ln("  classification (disk OFF redraw alpha): captured=${result.captured} escaped=${result.escaped} mixed/unresolved=${result.mixed}")
        result.notes.forEach { ln("  NOTE $it") }
        val hx = cam.pxOfStX(result.holeSt[0]); val hy = cam.pxOfStY(result.holeSt[1])
        ln("  hole projection px=(${f(hx)},${f(hy)}) st=(${f(result.holeSt[0], 4)},${f(result.holeSt[1], 4)})  shadow top/bottom px=${f(result.top)}/${f(result.bottom)} centreX=${f(result.centreX)}")
        ln("  row   py    sy      GPU xL   xR    | CPU xL   xR    | dL    dR (px) | D0 dark L/R   D5 dark L/R   D6 dark L/R")
        var maxDev = 0.0; var devRows = 0
        for (r in result.rows) {
            val dl = r.xL - r.cpuL; val dr = r.xR - r.cpuR
            if (!dl.isNaN()) { maxDev = max(maxDev, abs(dl)); devRows++ }
            if (!dr.isNaN()) maxDev = max(maxDev, abs(dr))
            ln(String.format(Locale.US, "  %+.1f %5d %+.4f  %6s %6s | %6s %6s | %5s %5s | %s/%s  %s/%s  %s/%s",
                r.frac, r.py, r.sy, f(r.xL), f(r.xR), f(r.cpuL), f(r.cpuR), f(dl), f(dr),
                f(r.darkL[0]), f(r.darkR[0]), f(r.darkL[1]), f(r.darkR[1]), f(r.darkL[2]), f(r.darkR[2])))
        }
        ln("  GPU-vs-CPU capture boundary: max |d| = ${f(maxDev, 2)} px over $devRows rows ($cpuNote)")
        result.centreRow()?.let { c ->
            val lSt = abs(cam.stX(c.xL) - result.holeSt[0]); val rSt = abs(cam.stX(c.xR) - result.holeSt[0])
            val lm = cam.stToM(lSt); val rm = cam.stToM(rSt)
            val tSt = abs(cam.stY(result.top) - result.holeSt[1]); val bSt = abs(cam.stY(result.bottom) - result.holeSt[1])
            val bard = bardeenEquatorial((cam.spinA / cam.mass).toDouble(), cam.inclinationDeg.toDouble())
            ln("  centre row: width=${f(c.xR - c.xL)} px  left extent=${f(lm, 3)} M  right extent=${f(rm, 3)} M  " +
                "horizontal asym (R-L)/(R+L)=${f((rm - lm) / (rm + lm), 3)}  vertical asym (T-B)/(T+B)=${f((tSt - bSt) / (tSt + bSt), 3)}")
            ln("  Bardeen distant-observer equatorial edges: prograde |a|=${f(bard[0], 3)} M, retrograde |a|=${f(bard[1], 3)} M " +
                "(finite distance ${f(cam.distance.toDouble())} M, small-angle scaling; indicative)")
            val farSide = if (rm > lm) "RIGHT" else "LEFT"
            val recedingSide = if (result.right.meanG < result.left.meanG) "RIGHT" else "LEFT"
            ln("  far (retrograde) shadow edge on $farSide; receding disk side (lower mean g) on $recedingSide -> " +
                if (farSide == recedingSide) "CONSISTENT with Kerr frame dragging" else "INCONSISTENT")
        }
        ln("  disk first crossing by side (direct images): LEFT n=${result.left.pixels} meanG=${f(result.left.meanG, 3)} g>1=${f(result.left.fracApproach, 3)} meanR=${f(result.left.meanR, 2)} | " +
            "RIGHT n=${result.right.pixels} meanG=${f(result.right.meanG, 3)} g>1=${f(result.right.fracApproach, 3)} meanR=${f(result.right.meanR, 2)}")
        fun band(name: String, b: Band) = ln("  $name band: px=${b.pixels} diskFrac=${f(b.diskFrac, 3)} twoCrossings=${f(b.twoCrossFrac, 3)} " +
            "crossing1 farSide=${f(b.farFrac1, 3)} crossing2 farSide=${f(b.farFrac2, 3)} HO=${f(b.hoFrac, 3)} medianR1=${f(b.medianR1, 2)} medianR2=${f(b.medianR2, 2)}")
        band("ABOVE shadow", result.above)
        band("BELOW shadow", result.below)
        val q = result.hoQuadrants
        ln("  HO pixels by quadrant: UL=${q[0]} UR=${q[1]} LL=${q[2]} LR=${q[3]}  upper=${q[0] + q[1]} lower=${q[2] + q[3]}")
        if (result.cpu.isNotEmpty()) {
            var dr = 0.0; var dp = 0.0; var dg = 0.0; var nAgree = 0; var hoAgree = 0; var nBoth = 0
            for (s in result.cpu) {
                if (s.gpuN == s.cpuN) nAgree++
                if (s.gpuHo == s.cpuHo) hoAgree++
                if (s.gpuN > 0 && s.cpuN > 0) {
                    nBoth++
                    dr = max(dr, abs((s.gpuR - s.cpuR).toDouble()))
                    var d = (s.gpuPhi - s.cpuPhi).toDouble(); while (d > PI) d -= 2 * PI; while (d < -PI) d += 2 * PI
                    dp = max(dp, abs(d)); dg = max(dg, abs((s.gpuG - s.cpuG).toDouble()))
                }
            }
            ln("  CPU mirror samples=${result.cpu.size}: crossing-count agree=$nAgree HO-flag agree=$hoAgree; over $nBoth common hits max|dr|=${f(dr, 3)} max|dphi|=${f(dp, 4)} max|dg|=${f(dg, 4)}")
            for (s in result.cpu.filter { it.gpuN != it.cpuN || it.gpuHo != it.cpuHo }.take(6)) {
                ln("    mismatch px=(${s.px},${s.py}) gpu cls=${s.gpuCls} n=${s.gpuN} ho=${s.gpuHo} r=${f(s.gpuR.toDouble(), 2)} | cpu state=${s.cpuState} n=${s.cpuN} ho=${s.cpuHo} r=${f(s.cpuR.toDouble(), 2)}")
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ camera drag trace

    /** One touch event as the view processed it (azimuth/inclination after the state update). */
    class InputEvent(val tNanos: Long, val action: Int, val dx: Float, val dy: Float, val azAfter: Float, val inclAfter: Float)

    /** One rendered frame (renderer state actually used for the frame). */
    class FrameEvent(
        val tNanos: Long, val az: Float, val incl: Float, val dist: Float, val gate: String, val geodesicPasses: Int,
        val materialRan: Boolean, val presentedModulated: Boolean, val signatureChanged: Boolean, val cpuMs: Float, val status: String
    )

    const val ACTION_DOWN = 0
    const val ACTION_MOVE = 1
    const val ACTION_UP = 2
    const val TOUCH_DEG_PER_PX = 0.35f

    private fun wrap(d: Float): Float { var v = d; while (v > 180f) v -= 360f; while (v < -180f) v += 360f; return v }

    /**
     * Stage-by-stage trace of the last complete drag (DOWN..UP) and the 5 s after it:
     * raw input delta -> camera (state) delta -> rendered camera delta, frame pacing, and the restart.
     */
    fun dragText(inputs: List<InputEvent>, frames: List<FrameEvent>): String {
        val sb = StringBuilder()
        fun ln(s: String) { sb.append(s).append('\n') }
        val up = inputs.indexOfLast { it.action == ACTION_UP }
        val down = if (up < 0) -1 else inputs.subList(0, up).indexOfLast { it.action == ACTION_DOWN }
        if (up < 0 || down < 0) { ln("MANUAL DRAG TRACE: no complete drag recorded (drag once with one finger, release, wait 5 s, then COPY REPORT)"); return sb.toString() }
        val drag = inputs.subList(down, up + 1)
        val moves = drag.filter { it.action == ACTION_MOVE }
        val t0 = drag.first().tNanos; val tUp = drag.last().tNanos
        ln(String.format(Locale.US, "MANUAL DRAG TRACE: %d move events over %.0f ms", moves.size, (tUp - t0) / 1e6))
        // Input stage.
        val dts = moves.zipWithNext { a, b -> (b.tNanos - a.tNanos) / 1e6 }
        val inAz = moves.map { -it.dx * TOUCH_DEG_PER_PX }
        val inReversals = inAz.filter { abs(it) > 0.05f }.zipWithNext().count { (a, b) -> a * b < 0 }
        ln(String.format(Locale.US, "  INPUT: event interval mean=%.1f max=%.1f ms; az step mean=%.3f deg; direction reversals=%d",
            dts.average().takeIf { !it.isNaN() } ?: 0.0, dts.maxOrNull() ?: 0.0, inAz.map { abs(it) }.average().takeIf { !it.isNaN() } ?: 0.0, inReversals))
        // State stage: the stored azimuth must advance by exactly -dx * 0.35 per event.
        var stateErr = 0f
        for (k in 1 until moves.size) stateErr = max(stateErr, abs(wrap(moves[k].azAfter - moves[k - 1].azAfter) - inAz[k]))
        ln(String.format(Locale.US, "  STATE: max |state delta - input delta| = %.5f deg over %d events", stateErr, max(0, moves.size - 1)))
        // Render stage.
        val fr = frames.filter { it.tNanos in t0..(tUp + 5_000_000_000L) }
        val during = fr.filter { it.tNanos <= tUp }
        val fdt = during.zipWithNext { a, b -> (b.tNanos - a.tNanos) / 1e6 }
        val steps = during.zipWithNext { a, b -> wrap(b.az - a.az) }
        val dir = if (inAz.sum() >= 0) 1f else -1f
        val renderReversals = steps.count { it * dir < -1e-4f }
        val zeroSteps = steps.count { abs(it) <= 1e-4f }
        val fdtMean = fdt.average().takeIf { !it.isNaN() } ?: 0.0
        val fdtCv = if (fdt.size > 1 && fdtMean > 0) sqrt(fdt.map { (it - fdtMean) * (it - fdtMean) }.average()) / fdtMean else 0.0
        val rebuildMs = during.filter { it.geodesicPasses > 0 }.map { it.cpuMs.toDouble() }
        ln(String.format(Locale.US, "  RENDER: %d frames during drag, interval mean=%.1f max=%.1f ms CV=%.2f; rendered az steps: reversals=%d zero=%d max=%.3f deg",
            during.size, fdtMean, fdt.maxOrNull() ?: 0.0, fdtCv, renderReversals, zeroSteps, steps.maxOfOrNull { abs(it) } ?: 0f))
        ln("  RENDER: gates during drag " + during.groupingBy { it.gate }.eachCount() + String.format(Locale.US,
            "; frames with a geodesic pass=%d (CPU ms mean=%.1f max=%.1f); presented modulated=%d",
            rebuildMs.size, rebuildMs.average().takeIf { !it.isNaN() } ?: 0.0, rebuildMs.maxOrNull() ?: 0.0, during.count { it.presentedModulated }))
        // Lag: rendered azimuth vs the latest state before the frame.
        var maxLag = 0f
        for (f in during) {
            val last = moves.lastOrNull { it.tNanos <= f.tNanos } ?: continue
            maxLag = max(maxLag, abs(wrap(last.azAfter - f.az)))
        }
        ln(String.format(Locale.US, "  LAG: max |latest state az - rendered az| = %.3f deg", maxLag))
        // After release.
        val after = fr.filter { it.tNanos > tUp }
        val finalAz = moves.lastOrNull()?.azAfter ?: Float.NaN
        val moved = after.count { abs(wrap(it.az - finalAz)) > 1e-4f }
        val firstMod = after.firstOrNull { it.gate == "MODULATE" && it.materialRan }
        val gaps = (listOf(tUp) + after.map { it.tNanos }).zipWithNext { a, b -> (b - a) / 1e6 }
        ln(String.format(Locale.US, "  RELEASE: frames in 5 s=%d, camera changed after UP in %d frames, largest frame gap=%.0f ms, first MODULATE at %s",
            after.size, moved, gaps.maxOrNull() ?: 0.0, firstMod?.let { String.format(Locale.US, "+%.0f ms", (it.tNanos - tUp) / 1e6) } ?: "NEVER"))
        ln("  RELEASE: gate sequence " + after.take(24).joinToString(" ") { it.gate.take(4) + (if (it.geodesicPasses > 0) "*" else "") })
        if (firstMod == null && after.isNotEmpty()) ln("  RELEASE: last status=${after.last().status}")
        val stage = when {
            inReversals > 0 -> "INPUT (raw touch deltas reverse)"
            stateErr > 1e-3f -> "STATE (stored camera != input)"
            renderReversals > 0 -> "PRESENTATION (rendered camera steps backwards)"
            fdtCv > 0.35 || (fdt.maxOrNull() ?: 0.0) > 2.5 * fdtMean -> "FRAME PACING (uneven frame intervals; each drag frame retraces the scene)"
            else -> "none detected"
        }
        ln("  FIRST BAD STAGE (drag): $stage")
        return sb.toString()
    }
}
