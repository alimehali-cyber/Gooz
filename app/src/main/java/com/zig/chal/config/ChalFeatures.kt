package com.zig.chal.config

import java.io.Serializable

/**
 * Feature toggle definitions for performance optimization.
 *
 * Adapted from the reference feature schema; UI capabilities are backend-specific.
 *
 * Lensing quality levels:
 * - off: Analytic Hologram (LOD 0, no ray marching)
 * - low: Analytic preview (same branch as legacy off)
 * - medium: Approximate ray marching (64 steps)
 * - high: Approximate Kerr ray marching (80-step mobile cap)
 * - ultra: Native-only UI tier; the GLES shader caps it identically to high
 */
enum class ChalRayTracingQuality(val id: String, val label: String, val shaderDefine: String) {
    OFF("off", "Off", "RAY_QUALITY_OFF"),
    LOW("low", "Low", "RAY_QUALITY_LOW"),
    MEDIUM("medium", "Med", "RAY_QUALITY_MEDIUM"),
    HIGH("high", "High", "RAY_QUALITY_HIGH"),
    ULTRA("ultra", "Ultra", "RAY_QUALITY_ULTRA");

    companion object {
        val VALID: List<String> = entries.map { it.id }

        fun fromId(id: String?): ChalRayTracingQuality? = entries.firstOrNull { it.id == id }
    }
}

/** Performance preset identity. */
enum class ChalPresetName(val id: String, val label: String) {
    MAXIMUM_PERFORMANCE("maximum-performance", "Max Perf"),
    BALANCED("balanced", "Balanced"),
    HIGH_QUALITY("high-quality", "High Qual"),
    ULTRA_QUALITY("ultra-quality", "Ultra"),
    CUSTOM("custom", "Custom")
}

/**
 * The complete feature matrix. `spacetimeVisualization` is carried for schema parity with the
 * reference engine (it drives a separate 3D analytics canvas there, not the ray-marcher) and has
 * no shader `#define`.
 */
data class ChalFeatureToggles(
    val gravitationalLensing: Boolean,
    val rayTracingQuality: ChalRayTracingQuality,
    val accretionDisk: Boolean,
    val dopplerBeaming: Boolean,
    val backgroundStars: Boolean,
    val photonSphereGlow: Boolean,
    val bloom: Boolean,
    val relativisticJets: Boolean,
    val gravitationalRedshift: Boolean,
    val kerrShadow: Boolean,
    val spacetimeVisualization: Boolean
) : Serializable

/** Global Performance Presets (`PERFORMANCE_PRESETS` in `simulation.config.ts`). */
object ChalPerformancePresets {

    val MAXIMUM_PERFORMANCE = ChalFeatureToggles(
        gravitationalLensing = false,
        rayTracingQuality = ChalRayTracingQuality.LOW,
        accretionDisk = true,
        dopplerBeaming = false,
        backgroundStars = true,
        photonSphereGlow = true,
        bloom = false,
        relativisticJets = false,
        gravitationalRedshift = false,
        kerrShadow = false,
        spacetimeVisualization = false
    )

    val BALANCED = ChalFeatureToggles(
        gravitationalLensing = true,
        rayTracingQuality = ChalRayTracingQuality.MEDIUM,
        accretionDisk = true,
        dopplerBeaming = false,
        backgroundStars = true,
        photonSphereGlow = false,
        bloom = false,
        relativisticJets = false,
        gravitationalRedshift = false,
        kerrShadow = false,
        spacetimeVisualization = false
    )

    val HIGH_QUALITY = ChalFeatureToggles(
        gravitationalLensing = true,
        rayTracingQuality = ChalRayTracingQuality.HIGH,
        accretionDisk = true,
        dopplerBeaming = true,
        backgroundStars = true,
        photonSphereGlow = true,
        bloom = true,
        relativisticJets = true,
        gravitationalRedshift = false,
        kerrShadow = false,
        spacetimeVisualization = false
    )

    val ULTRA_QUALITY = ChalFeatureToggles(
        gravitationalLensing = true,
        rayTracingQuality = ChalRayTracingQuality.ULTRA,
        accretionDisk = true,
        dopplerBeaming = true,
        backgroundStars = true,
        photonSphereGlow = true,
        bloom = true,
        relativisticJets = true,
        gravitationalRedshift = false,
        kerrShadow = false,
        spacetimeVisualization = false
    )

    /**
     * `PERFORMANCE_PRESETS: Record<PresetName, FeatureToggles>` including the `custom` alias.
     *
     * Lazily built so the `custom` alias can reference `ChalFeatures.DEFAULT_FEATURES` without
     * creating an initialization cycle with `ChalSimulationConfig.FEATURES`.
     */
    val all: Map<ChalPresetName, ChalFeatureToggles> by lazy {
        mapOf(
            ChalPresetName.MAXIMUM_PERFORMANCE to MAXIMUM_PERFORMANCE,
            ChalPresetName.BALANCED to BALANCED,
            ChalPresetName.HIGH_QUALITY to HIGH_QUALITY,
            ChalPresetName.ULTRA_QUALITY to ULTRA_QUALITY,
            ChalPresetName.CUSTOM to ChalFeatures.DEFAULT_FEATURES
        )
    }

    /** Named preset lookup that never touches the `custom` alias (cycle-free). */
    fun byName(name: ChalPresetName): ChalFeatureToggles = when (name) {
        ChalPresetName.MAXIMUM_PERFORMANCE -> MAXIMUM_PERFORMANCE
        ChalPresetName.BALANCED -> BALANCED
        ChalPresetName.HIGH_QUALITY -> HIGH_QUALITY
        ChalPresetName.ULTRA_QUALITY -> ULTRA_QUALITY
        ChalPresetName.CUSTOM -> ChalFeatures.DEFAULT_FEATURES
    }
}

/** Feature toggle helpers, mirroring the exported functions of `src/types/features.ts`. */
object ChalFeatures {

    /** `DEFAULT_FEATURES = SIMULATION_CONFIG.features.default`. */
    val DEFAULT_FEATURES: ChalFeatureToggles = ChalSimulationConfig.FEATURES

    /**
     * Ray-step budget for a quality LOD.
     *
     * @param isMobile caps the budget at [ChalPerformanceConfig.Compute.MAX_STEPS_MOBILE].
     */
    fun getMaxRaySteps(quality: ChalRayTracingQuality, isMobile: Boolean = false): Int {
        val steps = when (quality) {
            ChalRayTracingQuality.OFF -> ChalSimulationConfig.RayTracingSteps.OFF
            ChalRayTracingQuality.LOW -> ChalSimulationConfig.RayTracingSteps.LOW
            ChalRayTracingQuality.MEDIUM -> ChalSimulationConfig.RayTracingSteps.MEDIUM
            ChalRayTracingQuality.HIGH -> ChalSimulationConfig.RayTracingSteps.HIGH
            ChalRayTracingQuality.ULTRA -> ChalSimulationConfig.RayTracingSteps.ULTRA
        }
        return if (isMobile) minOf(steps, ChalPerformanceConfig.Compute.MAX_STEPS_MOBILE) else steps
    }

    /** Get preset by name (defensive copy, exactly like `getPreset`). */
    fun getPreset(name: ChalPresetName): ChalFeatureToggles =
        ChalPerformancePresets.all.getValue(name).copy()

    /** Identify which named preset (if any) the given feature matrix matches. */
    fun matchesPreset(features: ChalFeatureToggles): ChalPresetName {
        for (presetName in listOf(
            ChalPresetName.MAXIMUM_PERFORMANCE,
            ChalPresetName.BALANCED,
            ChalPresetName.HIGH_QUALITY,
            ChalPresetName.ULTRA_QUALITY
        )) {
            val p = ChalPerformancePresets.all.getValue(presetName)
            if (features.gravitationalLensing == p.gravitationalLensing &&
                features.rayTracingQuality == p.rayTracingQuality &&
                features.accretionDisk == p.accretionDisk &&
                features.dopplerBeaming == p.dopplerBeaming &&
                features.backgroundStars == p.backgroundStars &&
                features.photonSphereGlow == p.photonSphereGlow &&
                features.bloom == p.bloom &&
                features.relativisticJets == p.relativisticJets &&
                features.gravitationalRedshift == p.gravitationalRedshift &&
                features.kerrShadow == p.kerrShadow &&
                features.spacetimeVisualization == p.spacetimeVisualization
            ) {
                return presetName
            }
        }
        return ChalPresetName.CUSTOM
    }

    /** Balanced preset with post-processing forced off (mobile default). */
    fun getMobilePreset(): ChalFeatureToggles =
        getPreset(ChalPresetName.BALANCED).copy(bloom = false)

    /**
     * Runtime validation of an untyped feature map (storage / deep-link restore), mirroring
     * `validateFeatureToggles(features: unknown)`.
     */
    fun validateFeatureToggles(features: Map<String, Any?>?): Boolean {
        if (features == null) return false

        val requiredBooleans = listOf(
            "gravitationalLensing",
            "accretionDisk",
            "dopplerBeaming",
            "backgroundStars",
            "photonSphereGlow",
            "bloom",
            "relativisticJets",
            "gravitationalRedshift",
            "kerrShadow",
            "spacetimeVisualization"
        )

        for (key in requiredBooleans) {
            if (features[key] !is Boolean) return false
        }

        return ChalRayTracingQuality.VALID.contains(features["rayTracingQuality"] as? String)
    }

    /** Typed restore of a validated feature map. Returns null when the map is not valid. */
    fun fromMap(features: Map<String, Any?>?): ChalFeatureToggles? {
        if (!validateFeatureToggles(features)) return null
        val map = features!!
        return ChalFeatureToggles(
            gravitationalLensing = map["gravitationalLensing"] as Boolean,
            rayTracingQuality = ChalRayTracingQuality.fromId(map["rayTracingQuality"] as String)!!,
            accretionDisk = map["accretionDisk"] as Boolean,
            dopplerBeaming = map["dopplerBeaming"] as Boolean,
            backgroundStars = map["backgroundStars"] as Boolean,
            photonSphereGlow = map["photonSphereGlow"] as Boolean,
            bloom = map["bloom"] as Boolean,
            relativisticJets = map["relativisticJets"] as Boolean,
            gravitationalRedshift = map["gravitationalRedshift"] as Boolean,
            kerrShadow = map["kerrShadow"] as Boolean,
            spacetimeVisualization = map["spacetimeVisualization"] as Boolean
        )
    }
}
