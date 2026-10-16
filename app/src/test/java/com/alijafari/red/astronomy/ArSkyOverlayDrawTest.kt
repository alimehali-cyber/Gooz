package com.alijafari.red.astronomy

import android.content.Context
import android.graphics.Bitmap
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
import com.alijafari.red.astronomy.ar.core.ArConstellationCatalog
import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArSkyLightingBuilder
import com.alijafari.red.astronomy.ar.core.ArSkySceneComputer
import com.alijafari.red.astronomy.ar.core.ArSkySnapshot
import com.alijafari.red.astronomy.ar.core.ArStarCatalog
import com.alijafari.red.astronomy.ui.screens.ar.ArOverlayOptions
import com.alijafari.red.astronomy.ui.screens.ar.ArOverlayState
import com.alijafari.red.astronomy.ui.screens.ar.ArScreenSupport
import com.alijafari.red.astronomy.ui.screens.ar.ArSkyOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Regression test for the AR-screen crash (`ArrayIndexOutOfBoundsException: length=0; index=0` in
 * `ArSkyOverlay.drawStars`): the overlay used to iterate over the star catalogue held by the screen
 * while indexing the per-star arrays of a sky snapshot that had been computed before that catalogue
 * finished loading. Snapshots now carry the catalogue they were computed with; this draws, with real
 * (native) Android graphics, both a snapshot computed without stars and one computed with them, for
 * several view directions, with the simulated sky and in camera mode.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ArSkyOverlayDrawTest {

    private fun asset(name: String): File {
        val candidates = listOf("src/main/assets/ar/$name", "app/src/main/assets/ar/$name")
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Asset not found: $name (searched ${candidates.joinToString()})")
    }

    private val stars: ArStarCatalog by lazy {
        asset("stars_bsc5.bin").inputStream().use { s ->
            asset("star_names.txt").inputStream().use { n -> ArStarCatalog.parse(s, n) }
        }
    }

    private val tehran = ArObserver(35.6892, 51.3890, 1190.0)

    @Test
    fun `snapshots carry the catalogue their star arrays are indexed by`() {
        val builder = ArSkyLightingBuilder()
        val figures = asset("constellations.txt").inputStream().use { ArConstellationCatalog.parse(it, stars.size) }
        val t = 1792175400000L
        val early = ArSkySceneComputer.compute(t, tehran, null, emptyList(), emptyList(), emptyMap(), builder.atmosphereModel)
        assertNull(early.stars)
        assertEquals(0, early.starCount)
        assertEquals(0, early.starEnu.size)
        assertEquals(0, early.constellationLabelEnu.size)

        val full = ArSkySceneComputer.compute(t, tehran, stars, figures, emptyList(), emptyMap(), builder.atmosphereModel)
        assertSame(stars, full.stars)
        assertSame(figures, full.constellations)
        assertEquals(stars.size * 3, full.starEnu.size)
        assertEquals(stars.size, full.starMag.size)
        assertEquals(figures.size * 3, full.constellationLabelEnu.size)

        // A snapshot whose arrays do not match its catalogue cannot be constructed.
        try {
            ArSkySnapshot(
                early.timeMs, early.frame, stars, early.starEnu, early.starMag, early.bodies, early.placements,
                early.constellations, early.constellationLabelEnu, early.sun, early.moon
            )
            fail("A snapshot with mismatched star arrays was accepted")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `overlay draws snapshots computed before and after the catalogue loaded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val figures = asset("constellations.txt").inputStream().use { ArConstellationCatalog.parse(it, stars.size) }
        val catalogue = ArScreenSupport.arCatalogue()
        val glyphs = ArGlyphCache(context)
        val density = Density(2.625f)
        val textMeasurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
        val w = 1080; val h = 2400
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = ComposeCanvas(android.graphics.Canvas(bitmap))
        val state = ArOverlayState()
        try {
            // Night (18:30 UTC = 22:00 Tehran) and an evening with the Moon up (16:00 UTC).
            for (t in listOf(1792175400000L, 1792512000000L)) {
                val builder = ArSkyLightingBuilder()
                val early = ArSkySceneComputer.compute(t, tehran, null, emptyList(), emptyList(), emptyMap(), builder.atmosphereModel)
                val full = ArSkySceneComputer.compute(t, tehran, stars, figures, emptyList(), emptyMap(), builder.atmosphereModel)
                val lighting = builder.build(
                    full.sun.placed.enu, full.moon.placed.enu, full.moon.glyph?.phaseAngleDeg ?: 90.0, tehran.heightM, 4
                )
                val views = listOf(
                    full.moon.placed.azDeg to full.moon.placed.altDeg,
                    0.0 to 89.0,
                    180.0 to 20.0,
                    full.sun.placed.azDeg to full.sun.placed.altDeg
                )
                for ((az, alt) in views) for (cameraMode in listOf(false, true)) for (snap in listOf(early, full)) {
                    val cam = ArCameraModel.virtual(w.toFloat(), h.toFloat(), 0, 60.0, 1.0)
                    cam.setAttitude(ArScreenSupport.manualAttitude(az, alt))
                    bitmap.eraseColor(android.graphics.Color.BLACK)
                    CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
                        ArSkyOverlay.draw(
                            scope = this,
                            state = state,
                            snapshot = snap,
                            lighting = lighting,
                            camera = cam,
                            catalogue = catalogue,
                            satellites = emptyList(),
                            glyphs = glyphs,
                            opts = ArOverlayOptions(
                                cameraMode = cameraMode, zoom = 1.0, density = density.density,
                                showStars = true, showConstellations = true, showPlanets = true, showMoon = true,
                                showSun = true, showDeepSky = true, showSatellites = true, showMeteors = true,
                                showNames = true, isFa = false, timeMs = t, selectedId = null, accent = Color(0xFF64B5F6)
                            ),
                            textMeasurer = textMeasurer,
                            fontFamily = FontFamily.Default,
                            aimRadiusPx = 24f * density.density
                        )
                    }
                    if (snap === full) assertTrue("nothing was drawn looking at az=$az alt=$alt", state.items.isNotEmpty())
                }
            }
        } finally {
            glyphs.release()
            bitmap.recycle()
        }
    }
}
