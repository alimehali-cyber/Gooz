package com.alijafari.red.astronomy.ar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Equirectangular surface map (longitude 0 at the centre column, east to the right, north up), kept as
 * packed sRGB pixels (4 bytes per texel) and linearized on the fly through a 256-entry table.
 */
class EquirectTexture(val width: Int, val height: Int, private val argb: IntArray) {

    fun sample(lonRad: Double, latRad: Double, out: DoubleArray) {
        var u = 0.5 + lonRad / (2.0 * PI)
        u -= floor(u)
        val v = (0.5 - latRad / PI).coerceIn(0.0, 1.0)
        val x = u * width - 0.5
        val y = (v * height - 0.5).coerceIn(0.0, (height - 1).toDouble())
        val x0 = floor(x).toInt()
        val y0 = y.toInt().coerceIn(0, height - 1)
        val fx = x - x0
        val fy = y - y0
        val xa = ((x0 % width) + width) % width
        val xb = (xa + 1) % width
        val y1 = min(y0 + 1, height - 1)
        val pa = argb[y0 * width + xa]; val pb = argb[y0 * width + xb]
        val pd = argb[y1 * width + xa]; val pe = argb[y1 * width + xb]
        for (c in 0..2) {
            val shift = 16 - 8 * c
            val a = LIN[(pa shr shift) and 255]; val b = LIN[(pb shr shift) and 255]
            val d = LIN[(pd shr shift) and 255]; val e = LIN[(pe shr shift) and 255]
            out[c] = (a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + e * fx) * fy
        }
    }

    companion object {
        private val LIN = DoubleArray(256) { SkyRenderModel.srgbToLinear(it / 255.0) }

        /** From sRGB ARGB ints (e.g. Bitmap.getPixels); the array is used directly, not copied. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): EquirectTexture {
            require(argb.size >= width * height) { "Pixel array too small" }
            return EquirectTexture(width, height, argb)
        }
    }
}

/** Radial ring profile (inner → outer) with straight (non-premultiplied) colour and opacity. */
class RingProfile(val width: Int, private val rgba: FloatArray) {
    fun sample(t: Double, out: DoubleArray) {
        val x = (t.coerceIn(0.0, 1.0) * width - 0.5).coerceIn(0.0, (width - 1).toDouble())
        val i0 = x.toInt().coerceIn(0, width - 1)
        val i1 = min(i0 + 1, width - 1)
        val f = x - i0
        for (c in 0..3) out[c] = rgba[i0 * 4 + c] * (1 - f) + rgba[i1 * 4 + c] * f
    }

    companion object {
        /** From one row of sRGB ARGB ints. */
        fun fromArgbRow(width: Int, argb: IntArray): RingProfile {
            val v = FloatArray(width * 4)
            for (i in 0 until width) {
                val p = argb[i]
                v[i * 4] = SkyRenderModel.srgbToLinear(((p shr 16) and 255) / 255.0).toFloat()
                v[i * 4 + 1] = SkyRenderModel.srgbToLinear(((p shr 8) and 255) / 255.0).toFloat()
                v[i * 4 + 2] = SkyRenderModel.srgbToLinear((p and 255) / 255.0).toFloat()
                v[i * 4 + 3] = (((p ushr 24) and 255) / 255.0).toFloat()
            }
            return RingProfile(width, v)
        }
    }
}

/**
 * CPU ray-caster for the photoreal Sun/Moon/planet glyphs.
 *
 * The sprite frame is the observer's view of the sky around the body: x = celestial west (image
 * right when north is up), y = celestial north (image up), z = towards the observer. Each pixel casts
 * an orthographic ray at an oblate spheroid oriented by the IAU pole and prime meridian, samples the
 * surface map at the hit's planetographic latitude/longitude and shades it for the true Sun direction,
 * so the phase, the terminator, the bright-limb position angle, libration and the planet's tilt are all
 * physically right. Saturn's rings are intersected in the equatorial plane with mutual shadows.
 */
object GlyphRaster {

    enum class Shading {
        /** Ideal diffuse surface. */
        LAMBERT,
        /** Regolith (Moon, Mercury): uniform full disc, bright limb at crescent phases. */
        LOMMEL_SEELIGER,
        /** Limb-darkened cloud decks (giant planets, Venus): I ∝ μ0^k μ^(k−1). */
        MINNAERT
    }

    class RingSpec(val innerRadius: Double, val outerRadius: Double, val profile: RingProfile)

    class BodySpec(
        val texture: EquirectTexture?,
        val fallbackLinear: DoubleArray,
        val shading: Shading,
        val minnaertK: Double = 0.8,
        val flattening: Double = 0.0,
        val ring: RingSpec? = null,
        val brightness: Double = 1.0,
        val saturation: Double = 0.9,
        /** Opacity per unit of linear brightness (see [renderBody]). */
        val opacityGain: Double = 4.0,
        /** Opacity floor of the disc (0 = night side fully transparent). */
        val minOpacity: Double = 0.0
    )

    class Geometry(
        /** Body north pole in the sprite frame (unit). */
        val pole: DoubleArray,
        /** Body prime meridian on its equator in the sprite frame (unit, ⟂ pole). */
        val primeMeridian: DoubleArray,
        /** Direction from the body towards the Sun in the sprite frame (unit). */
        val sunDir: DoubleArray,
        /** Relative earthshine on the night side (Moon only), 0..1. */
        val earthshine: Double = 0.0,
        /** Atmospheric extinction colour applied to the whole glyph (linear RGB). */
        val tint: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)
    )

    /**
     * Renders into [out] (straight ARGB ints, row-major, y down) a [sizePx]² sprite with the body's
     * equatorial radius of [radiusPx] pixels centred in it.
     */
    fun renderBody(sizePx: Int, radiusPx: Double, spec: BodySpec, geo: Geometry, out: IntArray) {
        val zb = geo.pole.copyOf(); V3.normalizeInPlace(zb)
        val xb = geo.primeMeridian.copyOf()
        // Re-orthogonalize the prime meridian against the pole.
        val dp = V3.dot(xb, zb)
        for (i in 0..2) xb[i] -= dp * zb[i]
        V3.normalizeInPlace(xb)
        val yb = V3.cross(zb, xb)
        // Rows: sprite → body
        val m = doubleArrayOf(xb[0], xb[1], xb[2], yb[0], yb[1], yb[2], zb[0], zb[1], zb[2])
        val sB = M3.mulVec(m, geo.sunDir)
        V3.normalizeInPlace(sB)
        val vB = doubleArrayOf(m[2], m[5], m[8]) // sprite +z (towards observer) in body axes
        val ring = spec.ring
        val outerExtent = if (ring != null) ring.outerRadius else 1.0 + spec.flattening * 0.0
        val c = sizePx / 2.0
        val col = DoubleArray(4)
        val acc = DoubleArray(4)
        val tex = DoubleArray(3)
        val rgba = DoubleArray(4)
        for (j in 0 until sizePx) {
            for (i in 0 until sizePx) {
                val px = (i + 0.5 - c) / radiusPx
                val py = -(j + 0.5 - c) / radiusPx
                val rr = hypot(px, py)
                if (rr > outerExtent + 2.5 / radiusPx) {
                    out[j * sizePx + i] = 0
                    continue
                }
                // Supersample near edges (limb, ring edges) or everywhere for ringed bodies.
                val nearLimb = abs(rr - 1.0) < 1.6 / radiusPx || (abs(rr - (1.0 - spec.flattening)) < 1.6 / radiusPx)
                val ss = if (ring != null || nearLimb) 4 else 1
                acc.fill(0.0)
                for (sj in 0 until ss) for (si in 0 until ss) {
                    val sx = px + ((si + 0.5) / ss - 0.5) / radiusPx
                    val sy = py - ((sj + 0.5) / ss - 0.5) / radiusPx
                    shadeSample(sx, sy, m, sB, vB, spec, geo, col, tex, rgba)
                    // accumulate premultiplied
                    acc[0] += col[0] * col[3]; acc[1] += col[1] * col[3]; acc[2] += col[2] * col[3]; acc[3] += col[3]
                }
                val n = (ss * ss).toDouble()
                val coverage = acc[3] / n
                if (coverage <= 1e-4) {
                    out[j * sizePx + i] = 0
                    continue
                }
                // Premultiplied light, then an opacity that grows with brightness: the lit surface hides
                // what is behind it, while the unlit hemisphere lets the sky (whose glow lies in front of
                // the body) show through, exactly as seen in the real sky.
                val pr = acc[0] / n; val pg = acc[1] / n; val pb = acc[2] / n
                val peak = max(pr, max(pg, pb))
                val opacity = (coverage * min(1.0, peak * spec.opacityGain + spec.minOpacity)).coerceIn(0.0, 1.0)
                if (opacity <= 1e-4) {
                    out[j * sizePx + i] = 0
                    continue
                }
                val r = SkyRenderModel.linearToSrgb(pr / opacity)
                val g = SkyRenderModel.linearToSrgb(pg / opacity)
                val b = SkyRenderModel.linearToSrgb(pb / opacity)
                out[j * sizePx + i] = argb(opacity, r, g, b)
            }
        }
    }

    private fun shadeSample(
        sx: Double, sy: Double,
        m: DoubleArray, sB: DoubleArray, vB: DoubleArray,
        spec: BodySpec, geo: Geometry,
        out: DoubleArray, tex: DoubleArray, rgba: DoubleArray
    ) {
        // Ray in sprite frame: origin (sx, sy, 8), direction (0, 0, −1).
        val ox = m[0] * sx + m[1] * sy + m[2] * 8.0
        val oy = m[3] * sx + m[4] * sy + m[5] * 8.0
        val oz = m[6] * sx + m[7] * sy + m[8] * 8.0
        val dx = -m[2]; val dy = -m[5]; val dz = -m[8]
        val k = 1.0 - spec.flattening
        // Spheroid in scaled coordinates.
        val ozs = oz / k; val dzs = dz / k
        val a = dx * dx + dy * dy + dzs * dzs
        val b = 2.0 * (ox * dx + oy * dy + ozs * dzs)
        val cc = ox * ox + oy * oy + ozs * ozs - 1.0
        val disc = b * b - 4.0 * a * cc
        var tPlanet = Double.POSITIVE_INFINITY
        var pr = 0.0; var pg = 0.0; var pb = 0.0
        if (disc >= 0.0) {
            tPlanet = (-b - sqrt(disc)) / (2.0 * a)
            val hx = ox + tPlanet * dx; val hy = oy + tPlanet * dy; val hz = oz + tPlanet * dz
            var nx = hx; var ny = hy; var nz = hz / (k * k)
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= nl; ny /= nl; nz /= nl
            val lat = asin(nz.coerceIn(-1.0, 1.0))
            val lon = atan2(hy, hx)
            val t = spec.texture
            if (t != null) t.sample(lon, lat, tex) else { tex[0] = spec.fallbackLinear[0]; tex[1] = spec.fallbackLinear[1]; tex[2] = spec.fallbackLinear[2] }
            // Desaturate the artistic maps slightly towards their luminance.
            val tl = SkyRenderModel.luminance(tex[0], tex[1], tex[2])
            for (q in 0..2) tex[q] = tl + (tex[q] - tl) * spec.saturation
            val mu0 = nx * sB[0] + ny * sB[1] + nz * sB[2]
            val mu = max(1e-3, nx * vB[0] + ny * vB[1] + nz * vB[2])
            var f = when (spec.shading) {
                Shading.LAMBERT -> max(0.0, mu0)
                Shading.LOMMEL_SEELIGER -> if (mu0 > 0.0) 2.0 * mu0 / (mu0 + mu) else 0.0
                Shading.MINNAERT -> if (mu0 > 0.0) mu0.pow(spec.minnaertK) * mu.pow(spec.minnaertK - 1.0) else 0.0
            }
            // Soft terminator (penumbra of the solar disc + atmospheric blur).
            f *= SkyRenderModel.smoothstep(-0.015, 0.035, mu0)
            // Shadow of the rings on the planet.
            val ring = spec.ring
            if (ring != null && f > 0.0 && abs(sB[2]) > 1e-6) {
                val ts = -hz / sB[2]
                if (ts > 0.0) {
                    val rx = hx + ts * sB[0]; val ry = hy + ts * sB[1]
                    val rRing = hypot(rx, ry)
                    if (rRing in ring.innerRadius..ring.outerRadius) {
                        ring.profile.sample((rRing - ring.innerRadius) / (ring.outerRadius - ring.innerRadius), rgba)
                        f *= 1.0 - 0.85 * rgba[3]
                    }
                }
            }
            val es = geo.earthshine * mu.pow(0.5)
            pr = tex[0] * (f * spec.brightness + es * 0.80) * geo.tint[0]
            pg = tex[1] * (f * spec.brightness + es * 0.88) * geo.tint[1]
            pb = tex[2] * (f * spec.brightness + es * 1.00) * geo.tint[2]
        }
        val ring = spec.ring
        if (ring != null && abs(dz) > 1e-9) {
            val tr = -oz / dz
            val rx = ox + tr * dx; val ry = oy + tr * dy
            val rRing = hypot(rx, ry)
            if (rRing in ring.innerRadius..ring.outerRadius && tr < tPlanet) {
                ring.profile.sample((rRing - ring.innerRadius) / (ring.outerRadius - ring.innerRadius), rgba)
                val litSide = (sB[2] >= 0.0) == (vB[2] >= 0.0)
                var light = if (litSide) 1.05 * max(abs(sB[2]), 0.04).pow(0.18) else 0.22 * (1.0 - rgba[3] * 0.6)
                // Planet's shadow on the rings.
                if (shadowedByPlanet(rx, ry, 0.0, sB, k)) light *= 0.06
                val ra = rgba[3]
                val rr = rgba[0] * light * spec.brightness * geo.tint[0]
                val rg = rgba[1] * light * spec.brightness * geo.tint[1]
                val rb = rgba[2] * light * spec.brightness * geo.tint[2]
                if (tPlanet.isFinite()) {
                    // Ring in front of the planet.
                    out[0] = rr * ra + pr * (1 - ra); out[1] = rg * ra + pg * (1 - ra); out[2] = rb * ra + pb * (1 - ra); out[3] = 1.0
                } else {
                    out[0] = rr; out[1] = rg; out[2] = rb; out[3] = ra
                }
                return
            }
        }
        if (tPlanet.isFinite()) {
            out[0] = pr; out[1] = pg; out[2] = pb; out[3] = 1.0
        } else {
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0
        }
    }

    private fun shadowedByPlanet(x: Double, y: Double, z: Double, s: DoubleArray, k: Double): Boolean {
        val zs = z / k; val szs = s[2] / k
        val a = s[0] * s[0] + s[1] * s[1] + szs * szs
        val b = 2.0 * (x * s[0] + y * s[1] + zs * szs)
        val c = x * x + y * y + zs * zs - 1.0
        val disc = b * b - 4 * a * c
        if (disc < 0.0) return false
        val t1 = (-b - sqrt(disc)) / (2 * a)
        val t2 = (-b + sqrt(disc)) / (2 * a)
        return t1 > 0.0 || t2 > 0.0
    }

    /**
     * Limb-darkened photosphere (Neckel & Labs 1994 fit reduced to a quadratic law in V:
     * I(μ)/I(1) = 1 − 0.47(1 − μ) − 0.23(1 − μ)²) with a slightly warm white and a soft glare halo.
     */
    fun renderSun(sizePx: Int, radiusPx: Double, tint: DoubleArray, out: IntArray) {
        val c = sizePx / 2.0
        val maxR = (sizePx / 2.0) / radiusPx
        for (j in 0 until sizePx) for (i in 0 until sizePx) {
            val x0 = (i + 0.5 - c) / radiusPx; val y0 = (j + 0.5 - c) / radiusPx
            val r0 = hypot(x0, y0)
            val ss = if (abs(r0 - 1.0) < 1.5 / radiusPx) 4 else 1
            var pr = 0.0; var pg = 0.0; var pb = 0.0; var pa = 0.0
            for (sj in 0 until ss) for (si in 0 until ss) {
                val x = x0 + ((si + 0.5) / ss - 0.5) / radiusPx
                val y = y0 + ((sj + 0.5) / ss - 0.5) / radiusPx
                val r = hypot(x, y)
                if (r <= 1.0) {
                    val mu = sqrt(max(0.0, 1.0 - r * r))
                    val ld = 1.0 - 0.47 * (1 - mu) - 0.23 * (1 - mu) * (1 - mu)
                    // The limb is cooler, hence redder, than the disc centre.
                    pr += ld; pg += ld * (0.97 - 0.06 * (1 - mu)); pb += ld * (0.92 - 0.12 * (1 - mu)); pa += 1.0
                } else {
                    val d = r - 1.0
                    // Glare: scattering in the eye/optics, fading out before the sprite border.
                    val fade = SkyRenderModel.smoothstep(maxR, maxR * 0.7, r)
                    val glare = (0.42 * exp(-d * 6.0) + 0.14 * exp(-d * 1.6)) * fade
                    pr += glare; pg += glare * 0.93; pb += glare * 0.80; pa += glare
                }
            }
            val n = (ss * ss).toDouble()
            val a = (pa / n).coerceIn(0.0, 1.0)
            if (a <= 1e-3) { out[j * sizePx + i] = 0; continue }
            out[j * sizePx + i] = argb(
                a,
                SkyRenderModel.linearToSrgb(pr / n / a * tint[0]),
                SkyRenderModel.linearToSrgb(pg / n / a * tint[1]),
                SkyRenderModel.linearToSrgb(pb / n / a * tint[2])
            )
        }
    }

    /**
     * Star point-spread sprite (white; tinted when drawn): a near-diffraction-limited Gaussian core plus
     * a Moffat (β = 2.5) halo, as recorded by the eye/optics for a point source.
     */
    fun renderStarPsf(sizePx: Int, out: IntArray) {
        val c = sizePx / 2.0
        val sigma = sizePx * 0.085
        val alphaM = sizePx * 0.14
        for (j in 0 until sizePx) for (i in 0 until sizePx) {
            val x = i + 0.5 - c; val y = j + 0.5 - c
            val r2 = x * x + y * y
            val core = exp(-r2 / (2 * sigma * sigma))
            val halo = 0.10 * (1.0 + r2 / (alphaM * alphaM)).pow(-2.5)
            val edge = SkyRenderModel.smoothstep(c, c * 0.82, sqrt(r2)) // fade to zero at the sprite border
            val v = ((core + halo) * edge).coerceIn(0.0, 1.0)
            out[j * sizePx + i] = argb(v, 1.0, 1.0, 1.0)
        }
    }

    /** Straight (non-premultiplied) ARGB int from components in 0..1. */
    fun argb(a: Double, r: Double, g: Double, b: Double): Int {
        val ai = (a.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val ri = (r.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val gi = (g.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        val bi = (b.coerceIn(0.0, 1.0) * 255.0).roundToInt()
        return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
