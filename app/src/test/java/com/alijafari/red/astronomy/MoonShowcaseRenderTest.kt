package com.alijafari.red.astronomy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.alijafari.red.astronomy.ar.android.ArGlyphCache
import com.alijafari.red.astronomy.ar.core.ArCameraModel
import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArSkyLighting
import com.alijafari.red.astronomy.ar.core.ArSkyLightingBuilder
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.domain.SkyCanvasTheme
import com.alijafari.red.astronomy.ui.rendering.HeroSkyProjection
import com.alijafari.red.astronomy.ui.rendering.SkyMoonSprites
import com.alijafari.red.astronomy.ui.rendering.SkyRenderTarget
import com.alijafari.red.astronomy.ui.rendering.SkySceneModel
import com.alijafari.red.astronomy.ui.rendering.SkySceneRenderer
import com.alijafari.red.astronomy.ui.screens.ar.ArOverlayOptions
import com.alijafari.red.astronomy.ui.screens.ar.ArOverlayState
import com.alijafari.red.astronomy.ui.screens.ar.ArScreenSupport
import com.alijafari.red.astronomy.ui.screens.ar.ArSkyOverlay
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Draws the AR Moon (over the simulated sky, as the AR screen does with the camera off) and the Sky
 * Canvas Moon with real Android graphics for a set of real moments in Tehran — thin crescent with
 * earthshine, sunset, daytime, quarter at dusk, gibbous and full at night, gibbous by day — and writes
 * them to `build/moon-renders/` for review (published by the AR smoke workflow). Asserts that both
 * Moons are actually drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class MoonShowcaseRenderTest {

    private val tehran = ArObserver(35.6892, 51.3890, 1190.0)
    private val outDir = File("build/moon-renders").apply { mkdirs() }

    private val scenes = listOf(
        "1_crescent_earthshine" to 0L,             // found below: first dark evening after new Moon
        "2_crescent_sunset" to 1792159200000L,     // 2026-10-16 14:00 UTC (17:30 Tehran), Sun −0.6°
        "3_crescent_daytime" to 1792152000000L,    // 2026-10-16 12:00 UTC (15:30 Tehran), Sun +21.7°
        "4_quarter_dusk" to 1792335600000L,        // 2026-10-18 15:00 UTC, Sun −13.9° (nautical twilight)
        "5_gibbous_night" to 1792512000000L,       // 2026-10-20 16:00 UTC, Sun −26.5°
        "6_full_night" to 1792958400000L,          // 2026-10-25 20:00 UTC (23:30 Tehran)
        "7_gibbous_day" to 1793336400000L          // 2026-10-30 05:00 UTC (08:30 Tehran): waning gibbous 28° up, Sun +21.9°
    )

    @Test
    fun `render the AR Moon and the Sky Canvas Moon`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val glyphs = ArGlyphCache(context)
        val moonSprites = SkyMoonSprites(context)
        val density = Density(2.625f)
        val textMeasurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val catalogue = ArScreenSupport.arCatalogue()
        val report = StringBuilder()
        try {
            for ((name, t0) in scenes) {
                // The crescent scene: the first evening after new Moon with the Moon ≥ 6° up in a dark sky.
                val t = if (name.startsWith("1_")) findEarthshineEvening() else t0
                val sm = ArSkySceneComputer.sunAndMoon(t, tehran, null)
                report.append("$name t=$t sunAlt=%.1f moonAlt=%.1f phase=%.1f\n".format(
                    sm.sun.placed.altDeg, sm.moon.placed.altDeg, sm.moon.glyph!!.phaseAngleDeg))
                drawAr(context, name, t, glyphs, catalogue, textMeasurer, density)
                drawCanvas(name, t, moonSprites, density)
            }
        } finally {
            glyphs.release()
            moonSprites.release()
            File(outDir, "scenes.txt").writeText(report.toString())
        }
    }

    /** First instant after 2026-10-10 15:51 UTC (new Moon) with the Sun below −12° and the Moon above 6°. */
    private fun findEarthshineEvening(): Long {
        var t = 1791721200000L // 2026-10-11 12:20 UTC
        val end = t + 4L * 86_400_000L
        while (t < end) {
            val sm = ArSkySceneComputer.sunAndMoon(t, tehran, null)
            if (sm.sun.placed.altDeg < -12.0 && sm.moon.placed.altDeg > 6.0) return t
            t += 10L * 60_000L
        }
        error("no earthshine evening found")
    }

    private fun drawAr(
        context: Context, name: String, t: Long, glyphs: ArGlyphCache, catalogue: List<com.alijafari.red.astronomy.domain.CelestialObject>,
        textMeasurer: TextMeasurer, density: Density
    ) {
        val stars = null as com.alijafari.red.astronomy.ar.core.ArStarCatalog?
        val builder = ArSkyLightingBuilder()
        val snap = ArSkySceneComputer.compute(t, tehran, stars, emptyList(), emptyList(), emptyMap(), builder.atmosphereModel)
        val lighting = builder.build(snap.sun.placed.enu, snap.moon.placed.enu, snap.moon.glyph!!.phaseAngleDeg, tehran.heightM, 4)
        val w = 900; val h = 900
        val zoom = 10.0
        val cam = ArCameraModel.virtual(w.toFloat(), h.toFloat(), 0, 60.0, zoom)
        cam.setAttitude(ArScreenSupport.manualAttitude(snap.moon.placed.azDeg, snap.moon.placed.altDeg))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = ComposeCanvas(android.graphics.Canvas(bitmap))
        val state = ArOverlayState()
        val opts = ArOverlayOptions(
            cameraMode = false, zoom = zoom, density = density.density,
            showStars = false, showConstellations = false, showPlanets = true, showMoon = true, showSun = true,
            showDeepSky = false, showSatellites = false, showMeteors = false, showNames = false, isFa = false,
            timeMs = t, selectedId = null, accent = Color(0xFF64B5F6)
        )
        // The glyph is rendered off the UI thread: draw until it has had time to arrive, as the screen does.
        for (attempt in 0 until 10) {
            drawSimulatedSky(bitmap, lighting, cam)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                ArSkyOverlay.draw(this, state, snap, lighting, cam, catalogue, emptyList(), glyphs, opts, textMeasurer, FontFamily.Default, 24f * density.density)
            }
            Thread.sleep(250)
        }
        assertTrue("the Moon was drawn for $name", state.items.any { it.obj.id == "moon" })
        save(bitmap, "${name}_ar.jpg")
        bitmap.recycle()
    }

    /** The simulated sky exactly as SimulatedSkyFallback draws it (the GL sky's colours without the Milky Way). */
    private fun drawSimulatedSky(bitmap: Bitmap, lighting: ArSkyLighting, cam: ArCameraModel) {
        val w = bitmap.width; val h = bitmap.height
        val gw = 64; val gh = 64
        val px = IntArray(gw * gh)
        val c2e = cam.cameraToEnuMatrix()
        val f = cam.focalLengthViewPx()
        val out = DoubleArray(3); val tmp = DoubleArray(3)
        for (j in 0 until gh) for (i in 0 until gw) {
            val x = ((i + 0.5) / gw * w - w / 2.0) / f
            val y = ((j + 0.5) / gh * h - h / 2.0) / f
            var e = c2e[0] * x + c2e[1] * y + c2e[2]
            var n = c2e[3] * x + c2e[4] * y + c2e[5]
            var u = c2e[6] * x + c2e[7] * y + c2e[8]
            val norm = sqrt(e * e + n * n + u * u)
            e /= norm; n /= norm; u /= norm
            lighting.displayColorAt(e, n, u, out, tmp)
            val r = (out[0] * 255).roundToInt().coerceIn(0, 255)
            val g = (out[1] * 255).roundToInt().coerceIn(0, 255)
            val b = (out[2] * 255).roundToInt().coerceIn(0, 255)
            px[j * gw + i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val small = Bitmap.createBitmap(px, gw, gh, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawBitmap(small, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        small.recycle()
    }

    private fun drawCanvas(name: String, t: Long, moonSprites: SkyMoonSprites, density: Density) {
        val w = 1080; val h = 1080
        val jd = TimeEngine.getJulianDate(t)
        val frame = SkySceneModel.compute(jd, tehran.latitudeDeg, tehran.longitudeDeg, tehran.heightM)
        assertNotNull(frame.sunMoon)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = ComposeCanvas(android.graphics.Canvas(bitmap))
        var drawn = false
        for (attempt in 0 until 200) {
            bitmap.eraseColor(android.graphics.Color.BLACK)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                SkySceneRenderer.drawSky(this, frame, SkyCanvasTheme.REAL_SKY, t, panoramaActive = false,
                    target = SkyRenderTarget.HOME_HERO, moonSprites = moonSprites, bortle = 4)
            }
            val r = 26f * density.density * 0.48f
            if (moonSprites.sprite(frame, r, 4) != null && attempt > 0) {
                Thread.sleep(300)
                if (moonSprites.sprite(frame, r, 4) != null) {
                    bitmap.eraseColor(android.graphics.Color.BLACK)
                    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                        SkySceneRenderer.drawSky(this, frame, SkyCanvasTheme.REAL_SKY, t, panoramaActive = false,
                            target = SkyRenderTarget.HOME_HERO, moonSprites = moonSprites, bortle = 4)
                    }
                    drawn = true
                    break
                }
            }
            Thread.sleep(50)
        }
        if (frame.moonData.altitudeDeg > 0.0) assertTrue("Sky Canvas Moon for $name", drawn && !moonSprites.failed)
        save(bitmap, "${name}_canvas.jpg")
        // Enlarged view around the Moon (the canvas draws it at 12.5 dp).
        val p = HeroSkyProjection.project(frame.moonData.azimuthDeg, frame.moonData.altitudeDeg, w.toFloat(), h.toFloat(), tehran.latitudeDeg)
        val half = 90
        val cx = p.x.roundToInt().coerceIn(half, w - half); val cy = p.y.roundToInt().coerceIn(half, h - half)
        val crop = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(crop).drawBitmap(bitmap, Rect(cx - half, cy - half, cx + half, cy + half), Rect(0, 0, 360, 360), Paint(Paint.FILTER_BITMAP_FLAG))
        save(crop, "${name}_canvas_moon.jpg")
        crop.recycle()
        bitmap.recycle()
    }

    /** Saves a JPEG no larger than 600 px (the review images are published as base64 check-run text). */
    private fun save(bitmap: Bitmap, file: String) {
        val scale = minOf(1f, 600f / maxOf(bitmap.width, bitmap.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true) else bitmap
        FileOutputStream(File(outDir, file)).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (out !== bitmap) out.recycle()
    }
}
