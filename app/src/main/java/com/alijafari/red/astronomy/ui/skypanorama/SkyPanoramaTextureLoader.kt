package com.alijafari.red.astronomy.ui.skypanorama

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes the bundled, offline-converted panorama from APK assets. No network access, no EXR
 * decoding at runtime. Decoding runs on [Dispatchers.IO]; the caller uploads the result on the GL
 * thread.
 */
object SkyPanoramaTextureLoader {

    /** Chooses the decode tier, respecting the GPU's maximum texture size. */
    fun chooseTier(requested: SkyPanoramaQualityTier, maxTextureSize: Int): SkyPanoramaQualityTier {
        return if (maxTextureSize < requested.requiredMaxTextureSize) SkyPanoramaQualityTier.HALF else requested
    }

    /**
     * Decodes [assetPath] as ARGB_8888 (16-bit RGB565 would band the dark gradients).
     * Throws [FileNotFoundException] when the runtime asset is not bundled, and [IOException] when
     * the asset does not decode to a 2:1 equirectangular image.
     */
    suspend fun decode(
        context: Context,
        assetPath: String,
        tier: SkyPanoramaQualityTier
    ): Bitmap = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = tier.sampleSize
            inScaled = false
            inMutable = false
        }
        val bitmap = context.assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: throw IOException("Panorama asset could not be decoded: $assetPath")
        if (bitmap.width != bitmap.height * 2) {
            bitmap.recycle()
            throw IOException("Panorama must be 2:1 equirectangular, got ${bitmap.width}x${bitmap.height}")
        }
        bitmap
    }
}
