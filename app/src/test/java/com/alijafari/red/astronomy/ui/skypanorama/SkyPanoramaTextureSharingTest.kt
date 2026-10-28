package com.alijafari.red.astronomy.ui.skypanorama

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks that Home and the Live Sky backdrop share one decoded panorama without sharing GL resources. The checks read
 * production source, so they run in the JVM unit-test job without a device.
 */
class SkyPanoramaTextureSharingTest {

    private fun source(relative: String): String {
        val candidates = listOf("src/main/java/$relative", "app/src/main/java/$relative")
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: error("Source not found: $relative")
        return file.readText()
    }

    private val base = "com/alijafari/red/astronomy/ui/skypanorama/"

    @Test
    fun theAssetIsDecodedOnlyThroughTheSharedCache() {
        val view = source("${base}SkyPanoramaSurfaceView.kt")
        assertTrue("hosts must obtain the decode from the shared cache", view.contains("SkyPanoramaTextureCache.obtain("))
        assertFalse("hosts must not decode the asset directly", view.contains("SkyPanoramaTextureLoader.decode("))
        val cache = source("${base}SkyPanoramaTextureCache.kt")
        assertTrue("the cache must call the existing decoder", cache.contains("SkyPanoramaTextureLoader.decode("))
    }

    @Test
    fun theCacheIsReadOnlyAndSerialisesDecodes() {
        val cache = source("${base}SkyPanoramaTextureCache.kt")
        assertTrue("decodes must be serialised so simultaneous hosts do not both decode", cache.contains("Mutex"))
        assertTrue("the cached bitmap must be held softly, not pinned", cache.contains("SoftReference"))
        assertFalse("the cache must never recycle the shared bitmap", cache.contains("recycle()"))
    }

    @Test
    fun theRendererNeverRecyclesTheSharedBitmap() {
        val renderer = source("${base}SkyPanoramaRenderer.kt")
        assertFalse(
            "uploadTexture borrows a shared bitmap; recycling it would break the other host",
            renderer.contains("bitmap.recycle()")
        )
    }

    @Test
    fun eachHostKeepsItsOwnGlContextAndTexture() {
        val renderer = source("${base}SkyPanoramaRenderer.kt")
        assertTrue("each renderer owns its own handler thread", renderer.contains("HandlerThread(\"SkyPanoramaGl\")"))
        assertTrue("each renderer creates its own texture", renderer.contains("glGenTextures("))
    }

    @Test
    fun theRendererDrawsOnDemandAndHasNoContinuousLoop() {
        val renderer = source("${base}SkyPanoramaRenderer.kt")
        assertFalse("no Choreographer loop", renderer.contains("Choreographer"))
        assertFalse("no sleeping render loop", renderer.contains("while (true)"))
    }
}
