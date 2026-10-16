package com.alijafari.red.astronomy.ar.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.alijafari.red.astronomy.ar.core.ArConstellation
import com.alijafari.red.astronomy.ar.core.ArConstellationCatalog
import com.alijafari.red.astronomy.ar.core.ArStarCatalog
import com.alijafari.red.astronomy.ar.core.EquirectTexture
import com.alijafari.red.astronomy.ar.core.RingProfile
import com.alijafari.red.astronomy.ar.core.ArSkyLightingBuilder

/**
 * Process-wide, lazily loaded AR sky resources (see `tools/ar-sky-assets` and THIRD_PARTY_LICENSES
 * for provenance): the Yale Bright Star Catalogue, the d3-celestial constellation figures, the
 * planetary surface maps and the precomputed atmosphere. All loaders are blocking — call them from a
 * background dispatcher.
 */
object ArAssets {
    private const val TAG = "ArAssets"
    private val lock = Any()

    @Volatile private var stars: ArStarCatalog? = null
    @Volatile private var constellations: List<ArConstellation>? = null
    @Volatile private var lighting: ArSkyLightingBuilder? = null
    private val textures = HashMap<String, EquirectTexture>()
    @Volatile private var rings: RingProfile? = null

    fun stars(context: Context): ArStarCatalog? {
        stars?.let { return it }
        synchronized(lock) {
            stars?.let { return it }
            return try {
                val app = context.applicationContext
                val catalog = app.assets.open("ar/stars_bsc5.bin").use { s ->
                    app.assets.open("ar/star_names.txt").use { n -> ArStarCatalog.parse(s, n) }
                }
                stars = catalog
                catalog
            } catch (e: Exception) {
                Log.w(TAG, "Star catalogue unavailable", e)
                null
            }
        }
    }

    fun constellations(context: Context): List<ArConstellation> {
        constellations?.let { return it }
        val catalog = stars(context) ?: return emptyList()
        synchronized(lock) {
            constellations?.let { return it }
            return try {
                val list = context.applicationContext.assets.open("ar/constellations.txt").use {
                    ArConstellationCatalog.parse(it, catalog.size)
                }
                constellations = list
                list
            } catch (e: Exception) {
                Log.w(TAG, "Constellation figures unavailable", e)
                emptyList()
            }
        }
    }

    /** The precomputed atmosphere (≈ 0.3 s to build the first time). */
    fun lightingBuilder(): ArSkyLightingBuilder {
        lighting?.let { return it }
        synchronized(lock) {
            lighting?.let { return it }
            val b = ArSkyLightingBuilder()
            lighting = b
            return b
        }
    }

    /** Planetary surface map by file name under `assets/ar/textures`, e.g. "moon.jpg". */
    fun texture(context: Context, name: String): EquirectTexture? {
        synchronized(textures) { textures[name]?.let { return it } }
        val tex = try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val bmp = context.applicationContext.assets.open("ar/textures/$name").use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val t = EquirectTexture.fromArgb(bmp.width, bmp.height, px)
            bmp.recycle()
            t
        } catch (e: Exception) {
            Log.w(TAG, "Texture $name unavailable", e)
            null
        }
        if (tex != null) synchronized(textures) { textures[name] = tex }
        return tex
    }

    fun saturnRings(context: Context): RingProfile? {
        rings?.let { return it }
        return try {
            val bmp = context.applicationContext.assets.open("ar/textures/saturn_rings.png").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                    inPremultiplied = false
                })
            } ?: return null
            val row = IntArray(bmp.width)
            bmp.getPixels(row, 0, bmp.width, 0, bmp.height / 2, bmp.width, 1)
            val profile = RingProfile.fromArgbRow(bmp.width, row)
            bmp.recycle()
            rings = profile
            profile
        } catch (e: Exception) {
            Log.w(TAG, "Saturn ring profile unavailable", e)
            null
        }
    }
}
