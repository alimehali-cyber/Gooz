package com.zig.chal.config

import kotlin.math.round

/** Only controls with a rendering implementation belong here; the spacetime stub was retired. */
enum class ChalFeatureToggle {
    GRAVITATIONAL_LENSING, ACCRETION_DISK, DOPPLER_BEAMING, PHOTON_SPHERE, BACKGROUND_STARS,
    VOLUMETRIC_BLOOM, RELATIVISTIC_JETS, GRAVITATIONAL_REDSHIFT, KERR_SHADOW_GUIDE;

    fun label(isPersian: Boolean): String = when (this) {
        GRAVITATIONAL_LENSING -> if (isPersian) "همگرایی گرانشی" else "Gravitational lensing"
        ACCRETION_DISK -> if (isPersian) "قرص برافزایشی" else "Accretion disk"
        DOPPLER_BEAMING -> if (isPersian) "درخشش دوپلری" else "Doppler beaming"
        PHOTON_SPHERE -> if (isPersian) "درخشش حلقهٔ فوتونی" else "Photon-ring glow"
        BACKGROUND_STARS -> if (isPersian) "ستارگان پس‌زمینه" else "Background stars"
        VOLUMETRIC_BLOOM -> if (isPersian) "درخشش" else "Bloom"
        RELATIVISTIC_JETS -> if (isPersian) "فواره‌های نسبیتی" else "Relativistic jets"
        GRAVITATIONAL_REDSHIFT -> if (isPersian) "نمایش انتقال به سرخ" else "Redshift visualization"
        KERR_SHADOW_GUIDE -> if (isPersian) "مرجع سایهٔ شوارتزشیلد" else "Schwarzschild shadow reference"
    }

    fun read(f: ChalFeatureToggles): Boolean = when (this) {
        GRAVITATIONAL_LENSING -> f.gravitationalLensing
        ACCRETION_DISK -> f.accretionDisk
        DOPPLER_BEAMING -> f.dopplerBeaming
        PHOTON_SPHERE -> f.photonSphereGlow
        BACKGROUND_STARS -> f.backgroundStars
        VOLUMETRIC_BLOOM -> f.bloom
        RELATIVISTIC_JETS -> f.relativisticJets
        GRAVITATIONAL_REDSHIFT -> f.gravitationalRedshift
        KERR_SHADOW_GUIDE -> f.kerrShadow
    }

    fun write(f: ChalFeatureToggles, enabled: Boolean): ChalFeatureToggles = when (this) {
        GRAVITATIONAL_LENSING -> f.copy(gravitationalLensing = enabled)
        ACCRETION_DISK -> f.copy(accretionDisk = enabled)
        DOPPLER_BEAMING -> f.copy(dopplerBeaming = enabled)
        PHOTON_SPHERE -> f.copy(photonSphereGlow = enabled)
        BACKGROUND_STARS -> f.copy(backgroundStars = enabled)
        VOLUMETRIC_BLOOM -> f.copy(bloom = enabled)
        RELATIVISTIC_JETS -> f.copy(relativisticJets = enabled)
        GRAVITATIONAL_REDSHIFT -> f.copy(gravitationalRedshift = enabled)
        KERR_SHADOW_GUIDE -> f.copy(kerrShadow = enabled)
    }

    companion object {
        fun available(params: ChalSimulationParams, native: Boolean, bloomAvailable: Boolean): List<ChalFeatureToggle> {
            val rayMarching = native || ChalRenderPolicy.hasRayMarching(params.features.rayTracingQuality)
            return entries.filter { toggle ->
                when (toggle) {
                    VOLUMETRIC_BLOOM -> bloomAvailable
                    KERR_SHADOW_GUIDE -> !native && rayMarching
                    DOPPLER_BEAMING, RELATIVISTIC_JETS -> rayMarching && params.features.accretionDisk
                    GRAVITATIONAL_LENSING, GRAVITATIONAL_REDSHIFT -> rayMarching
                    else -> true
                }
            }
        }
    }
}

/** Slider and typed-entry validation share exactly the same step/range conversion. */
object ChalSliderMath {
    fun quantize(value: Double, config: ChalParameterConfig): Double {
        if (!value.isFinite()) return config.default.coerceIn(config.min, config.max)
        val bounded = value.coerceIn(config.min, config.max)
        if (config.step <= 0.0 || !config.step.isFinite()) return bounded
        return (config.min + round((bounded - config.min) / config.step) * config.step).coerceIn(config.min, config.max)
    }

    fun parseNumber(text: String): Double? {
        val normalized = buildString {
            for (c in text.trim()) when (c) {
                in '۰'..'۹' -> append('0' + (c - '۰'))
                in '٠'..'٩' -> append('0' + (c - '٠'))
                '٫' -> append('.')
                '−' -> append('-')
                '٬', ' ', '\u200e', '\u200f' -> Unit
                else -> append(c)
            }
        }
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
