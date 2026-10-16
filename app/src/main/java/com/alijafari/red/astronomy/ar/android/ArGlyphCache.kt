package com.alijafari.red.astronomy.ar.android

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.alijafari.red.astronomy.ar.core.ArBodyKind
import com.alijafari.red.astronomy.ar.core.ArBodyState
import com.alijafari.red.astronomy.ar.core.BodyOrientation
import com.alijafari.red.astronomy.ar.core.GlyphRaster
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Photoreal sprites for the Sun, the Moon and the planets (rendered off the UI thread with
 * [GlyphRaster]) and the tinted point-spread sprites used for stars, satellites and Galilean moons.
 *
 * A sprite is re-rendered only when its size bucket or its geometry (phase, bright-limb direction,
 * pole and rotation, extinction colour) has changed noticeably; until then the last sprite is drawn,
 * scaled to the exact size.
 */
class ArGlyphCache(context: Context) {

    class Sprite(val bitmap: Bitmap, /** Body equatorial radius in sprite pixels. */ val bodyRadiusPx: Float)

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ArGlyphRaster").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }

    private class Entry {
        @Volatile var sprite: Sprite? = null
        @Volatile var key: String? = null
        @Volatile var pending = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    @Volatile private var released = false

    /** White PSF (alpha only) tinted into [STAR_TINT_BINS] colour classes by B−V. */
    val starSprites: Array<Bitmap> by lazy { buildStarSprites() }

    enum class DsoKind { GALAXY, GLOBULAR, OPEN_CLUSTER, NEBULA, PLANETARY, OTHER }

    private val dsoSprites = ConcurrentHashMap<DsoKind, Bitmap>()

    /** Procedural deep-sky glyph (surface-brightness profile of the object class), cached. */
    fun dsoSprite(kind: DsoKind): Bitmap = dsoSprites.getOrPut(kind) { buildDsoSprite(kind) }

    /**
     * Sprite for a Sun/Moon/planet at a drawn equatorial radius of [radiusPx]. Returns the best sprite
     * available now (maybe null on the very first frames) and schedules a refresh when needed.
     */
    fun bodySprite(state: ArBodyState, radiusPx: Float): Sprite? {
        if (released || !radiusPx.isFinite()) return null
        // Size buckets of ~12 % so zooming does not re-render every frame; minimum 6 px radius.
        val bucket = bucketRadius(radiusPx)
        val geo = state.glyph?.geometry
        val key = buildString {
            append(state.id).append('|').append(bucket)
            if (geo != null) {
                append('|').append(q(geo.sunDir, 0.01)).append('|').append(q(geo.pole, 0.01))
                append('|').append(q(geo.primeMeridian, if (state.kind == ArBodyKind.MOON) 0.004 else 0.03))
                append('|').append(keyRound(geo.earthshine * 1000.0))
            }
            append('|').append(q(displayTint(state.extinction), 0.03))
        }
        val entry = entries.getOrPut(state.id) { Entry() }
        if (entry.key != key && !entry.pending) {
            entry.pending = true
            val snapshotState = state
            executor.execute {
                try {
                    val sprite = render(snapshotState, bucket)
                    if (sprite != null) {
                        entry.sprite = sprite
                        entry.key = key
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Glyph render failed for ${snapshotState.id}", t)
                } finally {
                    entry.pending = false
                }
            }
        }
        return entry.sprite
    }

    fun release() {
        released = true
        executor.shutdownNow()
        entries.clear()
    }

    private fun render(state: ArBodyState, radius: Float): Sprite? {
        val tint = displayTint(state.extinction)
        if (state.kind == ArBodyKind.SUN) {
            val size = (radius * 6f).roundToInt().coerceIn(16, 1024)
            val px = IntArray(size * size)
            GlyphRaster.renderSun(size, radius.toDouble(), tint, px)
            return Sprite(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888), radius)
        }
        val body = state.body ?: return null
        val glyph = state.glyph ?: return null
        val spec = specFor(body) ?: return null
        val extent = if (spec.ring != null) spec.ring.outerRadius else 1.0
        val size = (radius * extent * 2.0 + 6.0).roundToInt().coerceIn(12, 1024)
        val px = IntArray(size * size)
        val geometry = GlyphRaster.Geometry(
            pole = glyph.geometry.pole,
            primeMeridian = glyph.geometry.primeMeridian,
            sunDir = glyph.geometry.sunDir,
            earthshine = glyph.geometry.earthshine,
            tint = tint
        )
        GlyphRaster.renderBody(size, radius.toDouble(), spec, geometry, px)
        return Sprite(Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888), radius)
    }

    private fun specFor(body: BodyOrientation.Body): GlyphRaster.BodySpec? {
        fun tex(name: String) = ArAssets.texture(appContext, name)
        return when (body) {
            BodyOrientation.Body.MOON -> GlyphRaster.BodySpec(
                tex("moon.jpg"), doubleArrayOf(0.45, 0.44, 0.42), GlyphRaster.Shading.LOMMEL_SEELIGER,
                brightness = 1.25, saturation = 0.55, opacityGain = 3.0
            )
            BodyOrientation.Body.MERCURY -> GlyphRaster.BodySpec(
                tex("mercury.jpg"), doubleArrayOf(0.45, 0.43, 0.41), GlyphRaster.Shading.LOMMEL_SEELIGER,
                brightness = 1.3, saturation = 0.55
            )
            BodyOrientation.Body.VENUS -> GlyphRaster.BodySpec(
                tex("venus.jpg"), doubleArrayOf(0.9, 0.85, 0.7), GlyphRaster.Shading.MINNAERT,
                minnaertK = 0.9, brightness = 1.35, saturation = 0.75
            )
            BodyOrientation.Body.MARS -> GlyphRaster.BodySpec(
                tex("mars.jpg"), doubleArrayOf(0.75, 0.4, 0.22), GlyphRaster.Shading.MINNAERT,
                minnaertK = 0.92, flattening = BodyOrientation.flattening(body), brightness = 1.3, saturation = 0.95
            )
            BodyOrientation.Body.JUPITER -> GlyphRaster.BodySpec(
                tex("jupiter.jpg"), doubleArrayOf(0.8, 0.72, 0.6), GlyphRaster.Shading.MINNAERT,
                minnaertK = 0.78, flattening = BodyOrientation.flattening(body), brightness = 1.25, saturation = 0.9
            )
            BodyOrientation.Body.SATURN -> {
                val rings = ArAssets.saturnRings(appContext)
                GlyphRaster.BodySpec(
                    tex("saturn.jpg"), doubleArrayOf(0.82, 0.74, 0.55), GlyphRaster.Shading.MINNAERT,
                    minnaertK = 0.78, flattening = BodyOrientation.flattening(body),
                    // Ring profile spans 72 600–140 800 km (fit to the Cassini division and the A-ring edge).
                    ring = rings?.let { GlyphRaster.RingSpec(72600.0 / 60268.0, 140800.0 / 60268.0, it) },
                    brightness = 1.3, saturation = 0.9
                )
            }
            BodyOrientation.Body.URANUS -> GlyphRaster.BodySpec(
                tex("uranus.jpg"), doubleArrayOf(0.6, 0.8, 0.85), GlyphRaster.Shading.MINNAERT,
                minnaertK = 0.8, flattening = BodyOrientation.flattening(body), brightness = 1.25, saturation = 0.85
            )
            BodyOrientation.Body.NEPTUNE -> GlyphRaster.BodySpec(
                tex("neptune.jpg"), doubleArrayOf(0.3, 0.45, 0.85), GlyphRaster.Shading.MINNAERT,
                minnaertK = 0.8, flattening = BodyOrientation.flattening(body), brightness = 1.3, saturation = 0.9
            )
        }
    }

    private fun buildStarSprites(): Array<Bitmap> {
        val size = 48
        val psf = IntArray(size * size)
        GlyphRaster.renderStarPsf(size, psf)
        val tint = DoubleArray(3)
        return Array(STAR_TINT_BINS) { bin ->
            val bv = binToBv(bin)
            SkyRenderModel.starTint(bv, 0.65, tint)
            val r = (SkyRenderModel.linearToSrgb(tint[0]) * 255).roundToInt()
            val g = (SkyRenderModel.linearToSrgb(tint[1]) * 255).roundToInt()
            val b = (SkyRenderModel.linearToSrgb(tint[2]) * 255).roundToInt()
            val px = IntArray(size * size) { i -> (psf[i] and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b }
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }

    private fun buildDsoSprite(kind: DsoKind): Bitmap {
        val size = 96
        val c = size / 2.0
        val px = IntArray(size * size)
        val rnd = java.util.Random(kind.ordinal * 7919L + 17L)
        // Deterministic speckles for clusters.
        val speckles = when (kind) {
            DsoKind.GLOBULAR -> Array(160) {
                val rr = kotlin.math.abs(rnd.nextGaussian()) * 0.32
                val a = rnd.nextDouble() * 2 * Math.PI
                doubleArrayOf(rr * kotlin.math.cos(a), rr * kotlin.math.sin(a), 0.25 + 0.75 * rnd.nextDouble())
            }
            DsoKind.OPEN_CLUSTER -> Array(22) {
                val rr = sqrt(rnd.nextDouble()) * 0.8
                val a = rnd.nextDouble() * 2 * Math.PI
                doubleArrayOf(rr * kotlin.math.cos(a), rr * kotlin.math.sin(a), 0.3 + 0.7 * rnd.nextDouble().pow(2.0))
            }
            DsoKind.NEBULA -> Array(9) {
                doubleArrayOf(rnd.nextGaussian() * 0.25, rnd.nextGaussian() * 0.25, 0.18 + 0.2 * rnd.nextDouble())
            }
            else -> emptyArray()
        }
        val (cr, cg, cb) = when (kind) {
            DsoKind.GALAXY -> Triple(1.0, 0.94, 0.86)
            DsoKind.GLOBULAR -> Triple(1.0, 0.95, 0.84)
            DsoKind.OPEN_CLUSTER -> Triple(0.86, 0.92, 1.0)
            DsoKind.NEBULA -> Triple(0.96, 0.80, 0.86)
            DsoKind.PLANETARY -> Triple(0.66, 0.96, 0.92)
            DsoKind.OTHER -> Triple(0.92, 0.92, 0.95)
        }
        for (j in 0 until size) for (i in 0 until size) {
            val x = (i + 0.5 - c) / c; val y = (j + 0.5 - c) / c
            val r = sqrt(x * x + y * y)
            val edge = SkyRenderModel.smoothstep(1.0, 0.75, r)
            var v = when (kind) {
                DsoKind.GALAXY, DsoKind.OTHER -> 0.85 * kotlin.math.exp(-r / 0.07) + 0.32 * kotlin.math.exp(-r / 0.30)
                DsoKind.GLOBULAR -> 0.55 / (1.0 + (r / 0.13).pow(2.0))
                DsoKind.OPEN_CLUSTER -> 0.06 * kotlin.math.exp(-r / 0.5)
                DsoKind.NEBULA -> speckles.sumOf { s -> s[2] * kotlin.math.exp(-((x - s[0]).pow(2.0) + (y - s[1]).pow(2.0)) / (2 * 0.16 * 0.16)) }
                DsoKind.PLANETARY -> 0.85 * SkyRenderModel.smoothstep(0.42, 0.30, r) * (0.75 + 0.25 * r / 0.42)
            }
            if (kind == DsoKind.GLOBULAR || kind == DsoKind.OPEN_CLUSTER) {
                val sigma = if (kind == DsoKind.GLOBULAR) 0.022 else 0.03
                for (s in speckles) {
                    val d2 = (x - s[0]).pow(2.0) + (y - s[1]).pow(2.0)
                    if (d2 < 0.02) v += s[2] * kotlin.math.exp(-d2 / (2 * sigma * sigma)) * (if (kind == DsoKind.GLOBULAR) 0.5 else 0.95)
                }
            }
            val a = (v * edge).coerceIn(0.0, 1.0)
            px[j * size + i] = if (a <= 0.002) 0 else GlyphRaster.argb(a, cr, cg, cb)
        }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val TAG = "ArGlyphCache"

        fun dsoKind(obj: com.alijafari.red.astronomy.domain.CelestialObject): DsoKind {
            val cat = obj.category
            return when {
                obj.type == com.alijafari.red.astronomy.domain.ObjectType.GALAXY || cat.contains("Galaxy", true) -> DsoKind.GALAXY
                obj.type == com.alijafari.red.astronomy.domain.ObjectType.GLOBULAR_CLUSTER || cat.contains("Globular", true) -> DsoKind.GLOBULAR
                cat.contains("Planetary", true) -> DsoKind.PLANETARY
                obj.type == com.alijafari.red.astronomy.domain.ObjectType.STAR_CLUSTER || cat.contains("Cluster", true) -> DsoKind.OPEN_CLUSTER
                obj.type == com.alijafari.red.astronomy.domain.ObjectType.NEBULA || cat.contains("Nebula", true) || cat.contains("Remnant", true) -> DsoKind.NEBULA
                else -> DsoKind.OTHER
            }
        }
        const val STAR_TINT_BINS = 14

        /** B−V of a tint bin (−0.35 … 2.05). */
        fun binToBv(bin: Int): Double = -0.35 + bin * (2.4 / (STAR_TINT_BINS - 1))

        fun bvToBin(bv: Double): Int {
            if (!bv.isFinite()) return bvToBin(0.6) // unknown colour: a solar-type star
            return ((bv + 0.35) / (2.4 / (STAR_TINT_BINS - 1))).roundToInt().coerceIn(0, STAR_TINT_BINS - 1)
        }

        /** PSF sprite pixels per core radius (the PSF core σ is 0.085 of the sprite). */
        const val PSF_SPRITE_PER_CORE_RADIUS = 3.2f

        fun bucketRadius(radiusPx: Float): Float {
            // Non-finite input must never reach roundToInt() (it throws on NaN).
            val r = if (radiusPx.isFinite()) radiusPx.coerceIn(6f, 4096f) else 6f
            val step = 1.12
            val n = (kotlin.math.ln(r / 6.0) / kotlin.math.ln(step)).roundToInt()
            return (6.0 * step.pow(n)).toFloat()
        }

        /**
         * Extinction colour for display: keeps the reddening hue but compresses the dimming, so a rising
         * Moon is orange yet still clearly visible.
         */
        fun displayTint(ext: DoubleArray): DoubleArray {
            val m = max(ext[0], max(ext[1], ext[2]))
            if (m <= 1e-9) return doubleArrayOf(0.45, 0.2, 0.08)
            val brightness = 0.45 + 0.55 * sqrt(m.coerceIn(0.0, 1.0))
            return doubleArrayOf(ext[0] / m * brightness, ext[1] / m * brightness, ext[2] / m * brightness)
        }

        private fun q(v: DoubleArray, step: Double): String =
            v.joinToString(",") { keyRound(it / step).toString() }

        /** Rounding for cache keys: NaN/∞ map to a sentinel instead of throwing. */
        private fun keyRound(v: Double): Int =
            if (v.isFinite()) v.coerceIn(-1e9, 1e9).roundToInt() else Int.MIN_VALUE
    }
}
