package com.alijafari.red.astronomy.ui.skypanorama

import com.alijafari.red.astronomy.domain.SkyCanvasTheme

/**
 * Feature gate and renderer configuration for the Phase 1 photographic sky panorama.
 *
 * [INTEGRATION_ENABLED] is the permanent feature gate for the panorama. It is not a user-facing setting.
 * The panorama is shown only after its texture has been decoded, uploaded and presented. Until then, or
 * if it fails, the legacy Sky Canvas remains visible as the fallback.
 */
object SkyPanoramaFeature {
    // Enabled in every build. Set to false only to disable the panorama entirely.
    const val INTEGRATION_ENABLED = true

    /** The panorama replaces the Real Sky background only, and only when the feature is enabled. */
    fun isEnabledFor(theme: SkyCanvasTheme): Boolean =
        INTEGRATION_ENABLED && theme == SkyCanvasTheme.REAL_SKY

    /**
     * True when the panorama is actually on screen and the legacy procedural sky must be skipped.
     * Until [panoramaReady] is true (loading, or failed), the legacy sky stays visible as the fallback.
     */
    fun isPresented(theme: SkyCanvasTheme, panoramaReady: Boolean): Boolean =
        isEnabledFor(theme) && panoramaReady
}

/** Texture resolution tiers. Only the 4096 x 2048 runtime master is bundled in Phase 1. */
enum class SkyPanoramaQualityTier(val sampleSize: Int, val requiredMaxTextureSize: Int) {
    /** Full 4096 x 2048 master. */
    FULL(sampleSize = 1, requiredMaxTextureSize = 4096),

    /** 2048 x 1024 decode for GPUs whose maximum texture size is below 4096. */
    HALF(sampleSize = 2, requiredMaxTextureSize = 2048)
}

/**
 * Explicit renderer tuning values. Exposure, saturation, contrast and visibility are neutral
 * (1.0) until they can be tuned against the real texture on a device. They are applied in the
 * shader as uniforms, never baked into the image.
 */
data class SkyPanoramaConfig(
    /** Display-referred gain applied to texel values. 1.0 is neutral. */
    val exposure: Float = 1.0f,
    /** 0 = greyscale, 1 = unchanged. */
    val saturation: Float = 1.0f,
    /** Contrast around mid-grey. 1.0 is neutral. */
    val contrast: Float = 1.0f,
    /** Multiplier toward black. 1.0 shows the full panorama. */
    val visibility: Float = 1.0f,
    val qualityTier: SkyPanoramaQualityTier = SkyPanoramaQualityTier.FULL,
    /** Runtime texture inside the APK assets. The file is produced offline by tools/sky-panorama. */
    val assetPath: String = DEFAULT_ASSET_PATH
) {
    /** Values clamped to ranges the shader handles without clipping artefacts. */
    fun sanitized(): SkyPanoramaConfig = copy(
        exposure = exposure.coerceIn(0.05f, 4.0f),
        saturation = saturation.coerceIn(0f, 2f),
        contrast = contrast.coerceIn(0.2f, 2f),
        visibility = visibility.coerceIn(0f, 1f)
    )

    companion object {
        const val DEFAULT_ASSET_PATH = "sky/panorama/milkyway_2020_4k.jpg"
    }
}
