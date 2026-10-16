package com.alijafari.red.astronomy.ui.rendering

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.alijafari.red.astronomy.ar.android.ArAssets
import com.alijafari.red.astronomy.ar.android.ArGlyphCache
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.ar.core.GlyphRaster
import com.alijafari.red.astronomy.ar.core.HumanVision
import com.alijafari.red.astronomy.ar.core.LunarPhotometry
import com.alijafari.red.astronomy.ar.core.MoonLook
import com.alijafari.red.astronomy.ar.core.SkyRenderModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Sky Canvas Moon: the same photographic Moon as the Home Moon card and the AR screen — the card's
 * plate ray-cast by [GlyphRaster.renderMoon] with the exact phase, bright limb, axis position angle
 * and libration of [SkySceneFrame.sunMoon] — composited for the sky it is seen through: the sky
 * luminance in front of the Moon comes from the AR atmosphere model for the frame's Sun and Moon, so the
 * Moon is pale by day, its earthlit night side appears only when twilight has darkened enough, and so on.
 *
 * Sprites are rendered off the UI thread; [sprite] returns the latest one and schedules a new render
 * when the inputs have changed noticeably. Reading it inside a draw lambda re-draws when a new sprite
 * arrives.
 */
class SkyMoonSprites(context: Context) {

    class Sprite(
        /**
         * The Moon's light (sunlit and earthlit), premultiplied, to be drawn with SCREEN blending over the
         * canvas sky (celestial north up, west to the right).
         */
        val light: Bitmap,
        /** Moon radius in sprite pixels. */
        val bodyRadiusPx: Float,
        /**
         * Share of the eye's veiling glare around the Moon (CIE 146:2002, one lunar diameter from the limb)
         * against the sky there: 1 at night, ~0 in daylight. Scales the canvas' moonlight bloom.
         */
        val glareWeight: Double
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SkyMoonRaster").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }
    private val versionState = mutableIntStateOf(0)
    @Volatile private var current: Sprite? = null
    @Volatile private var currentKey: String? = null
    @Volatile private var pending = false
    @Volatile private var released = false

    /** True once rendering has failed (e.g. the plate texture is missing): callers fall back to their own Moon. */
    @Volatile var failed = false
        private set

    /**
     * The Moon sprite for [frame] at a drawn radius of [radiusPx] pixels, or null until the first one is
     * ready. [bortle] is the observer's sky class (light pollution of the night sky in front of the Moon).
     */
    fun sprite(frame: SkySceneFrame, radiusPx: Float, bortle: Int): Sprite? {
        versionState.intValue // read: a new sprite re-draws the caller
        if (released || failed) return null
        val sm = frame.sunMoon ?: return null
        val glyph = sm.moon.glyph ?: return null
        val bucket = ArGlyphCache.bucketRadius(radiusPx)
        val geo = glyph.geometry
        val sunEl = sm.sun.placed.altDeg
        val moonEl = sm.moon.placed.altDeg
        val dAz = AngleUtilDeg.wrap180(sm.sun.placed.azDeg - sm.moon.placed.azDeg)
        val key = buildString {
            append(bucket).append('|')
            append(q(geo.sunDir, 0.01)).append('|').append(q(geo.pole, 0.01)).append('|').append(q(geo.primeMeridian, 0.004))
            append('|').append(q(sm.moon.extinction, 0.03))
            // Inputs of the sky luminance in front of the Moon.
            append('|').append(qs(sunEl, if (sunEl in -20.0..12.0) 0.25 else 1.0))
            append('|').append(qs(moonEl, 0.5)).append('|').append(qs(dAz, 2.0)).append('|').append(bortle)
            append('|').append(qs(sm.moon.distanceAu * ArSkySceneComputer.AU_KM, 1500.0))
        }
        if (key != currentKey && !pending) {
            pending = true
            executor.execute {
                try {
                    val sprite = render(sm, bucket, bortle)
                    current = sprite
                    currentKey = key
                } catch (t: Throwable) {
                    Log.w(TAG, "Sky Canvas Moon render failed", t)
                    failed = true
                } finally {
                    pending = false
                    main.post { if (!released) versionState.intValue = versionState.intValue + 1 }
                }
            }
        }
        return current
    }

    private fun render(sm: ArSkySceneComputer.SunMoon, radius: Float, bortle: Int): Sprite {
        val moon = sm.moon
        val sun = sm.sun
        val glyph = moon.glyph ?: error("Moon without glyph geometry")
        val texture = ArAssets.texture(appContext, ArGlyphCache.MOON_TEXTURE) ?: error("Moon plate unavailable")
        // The process-wide atmosphere (shared with the AR sky worker, which also locks it).
        val builder = ArAssets.lightingBuilder()
        val light = synchronized(builder) {
            builder.build(sun.placed.enu, moon.placed.enu, glyph.phaseAngleDeg, sm.frame.observer.heightM, bortle)
        }
        val e = moon.placed.enu
        val sky = light.luminanceAt(e[0], e[1], e[2]).takeIf { it.isFinite() } ?: ArGlyphCache.NIGHT_SKY_LUMINANCE
        val ext = moon.extinction
        val transmittance = SkyRenderModel.luminance(ext[0], ext[1], ext[2])
        val distanceKm = moon.distanceAu * ArSkySceneComputer.AU_KM
        val look = MoonLook(distanceKm, sun.distanceAu, sky, transmittance)
        val size = (radius * 2.0 + 6.0).roundToInt().coerceIn(12, 1024)
        val px = IntArray(size * size)
        val geometry = GlyphRaster.Geometry(
            pole = glyph.geometry.pole,
            primeMeridian = glyph.geometry.primeMeridian,
            sunDir = glyph.geometry.sunDir,
            tint = ext
        )
        GlyphRaster.renderMoon(size, radius.toDouble(), texture, doubleArrayOf(0.30, 0.29, 0.28), geometry, look, null, px)
        val bitmap = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        // Veiling glare one lunar diameter outside the limb, against the sky there.
        val illuminance = LunarPhotometry.illuminanceLux(LunarPhotometry.magnitudeV(glyph.phaseAngleDeg, distanceKm, sun.distanceAu)) * transmittance
        val veil = illuminance * HumanVision.glareSpread(3.0 * Math.toDegrees(LunarPhotometry.angularRadiusRad(distanceKm)))
        return Sprite(bitmap, radius, HumanVision.luminanceShare(veil, sky))
    }

    fun release() {
        released = true
        executor.shutdownNow()
        current = null
    }

    private object AngleUtilDeg {
        fun wrap180(d: Double): Double = ((d % 360.0) + 540.0) % 360.0 - 180.0
    }

    private companion object {
        const val TAG = "SkyMoonSprites"

        fun q(v: DoubleArray, step: Double): String = v.joinToString(",") { qs(it, step).toString() }

        fun qs(v: Double, step: Double): Long = if (v.isFinite()) kotlin.math.round(v / step).toLong() else Long.MIN_VALUE
    }
}

/** A [SkyMoonSprites] for this composition, released when it leaves. */
@Composable
fun rememberSkyMoonSprites(): SkyMoonSprites {
    val context = LocalContext.current
    val sprites = remember { SkyMoonSprites(context.applicationContext) }
    DisposableEffect(sprites) { onDispose { sprites.release() } }
    return sprites
}
