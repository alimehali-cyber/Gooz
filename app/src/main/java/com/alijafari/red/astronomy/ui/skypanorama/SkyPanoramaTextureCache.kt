package com.alijafari.red.astronomy.ui.skypanorama

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.SoftReference

/**
 * Process-wide cache of the decoded panorama bitmap. Home and the Live Sky backdrop are separate GL hosts, but they
 * use the same bytes, so the asset is decoded once per tier while it stays reachable.
 *
 * - The bitmap is read-only and is never recycled here. Each GL context uploads it into its own texture, so GL
 *   resources stay separate per host.
 * - Only a soft reference is kept. A host holds the bitmap strongly until its upload has been queued. After that,
 *   Android may reclaim it under memory pressure, and the next host decodes again.
 * - A mutex serialises decodes, so two hosts that start together do not both decode the asset.
 */
object SkyPanoramaTextureCache {

    private class Entry(
        val assetPath: String,
        val tier: SkyPanoramaQualityTier,
        bitmap: Bitmap
    ) {
        private val ref = SoftReference(bitmap)

        fun matches(path: String, requestedTier: SkyPanoramaQualityTier): Boolean =
            assetPath == path && tier == requestedTier

        fun bitmapOrNull(): Bitmap? = ref.get()?.takeIf { !it.isRecycled }
    }

    private val mutex = Mutex()
    private var entry: Entry? = null

    /** Returns the shared decoded panorama, decoding it only when no live copy is cached. */
    suspend fun obtain(context: Context, assetPath: String, tier: SkyPanoramaQualityTier): Bitmap {
        mutex.withLock {
            val cached = entry
            if (cached != null && cached.matches(assetPath, tier)) {
                val live = cached.bitmapOrNull()
                if (live != null) return live
            }
            val decoded = SkyPanoramaTextureLoader.decode(context.applicationContext, assetPath, tier)
            entry = Entry(assetPath, tier, decoded)
            return decoded
        }
    }
}
