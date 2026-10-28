package com.zig.chal.config

import kotlin.math.abs

/** The four physical scenario presets exposed by BlackHole 1.0.0. */
enum class ChalXapkScenario(
    val label: String,
    val persianLabel: String,
    val mass: Double,
    val spin: Double,
    val lensing: Double,
    val diskSize: Double,
    val diskTemp: Double,
    val diskDensity: Double,
    val diskScaleHeight: Double,
    val cameraDistance: Double,
    val quality: ChalRayTracingQuality
) {
    STELLAR(
        label = "Stellar",
        persianLabel = "ستاره‌ای",
        mass = 10.0,
        spin = 0.5,
        lensing = 0.7,
        diskSize = 80.0,
        diskTemp = 15_000.0,
        diskDensity = 4.0,
        diskScaleHeight = 0.18,
        cameraDistance = 80.0,
        quality = ChalRayTracingQuality.HIGH
    ),
    SGR_A_PROXY(
        label = "Sgr A* proxy",
        persianLabel = "شبیه‌ساز کمان ای*",
        mass = 4.0,
        spin = 0.94,
        lensing = 0.9,
        diskSize = 60.0,
        diskTemp = 8_000.0,
        diskDensity = 3.5,
        diskScaleHeight = 0.2,
        cameraDistance = 60.0,
        quality = ChalRayTracingQuality.HIGH
    ),
    MAXIMAL_SPIN(
        label = "Maximal Spin",
        persianLabel = "بیشینهٔ چرخش",
        mass = 2.0,
        spin = 0.99,
        lensing = 1.2,
        diskSize = 40.0,
        diskTemp = 20_000.0,
        diskDensity = 4.5,
        diskScaleHeight = 0.15,
        cameraDistance = 30.0,
        quality = ChalRayTracingQuality.ULTRA
    ),
    SCHWARZSCHILD(
        label = "Schwarzschild",
        persianLabel = "شوارتزشیلد",
        mass = 1.0,
        spin = 0.0,
        lensing = 0.7,
        diskSize = 50.0,
        diskTemp = 9_500.0,
        diskDensity = 4.0,
        diskScaleHeight = 0.2,
        cameraDistance = 30.0,
        quality = ChalRayTracingQuality.HIGH
    );

    fun applyTo(params: ChalSimulationParams): ChalSimulationParams {
        val features = params.features.copy(rayTracingQuality = quality)
        return params.copy(
            mass = mass,
            spin = spin,
            lensing = lensing,
            diskSize = diskSize,
            diskTemp = diskTemp,
            diskDensity = diskDensity,
            diskScaleHeight = diskScaleHeight,
            zoom = cameraDistance,
            verticalAngle = XapkCameraState.DEFAULT_POLAR_ANGLE_DEGREES,
            renderScale = XapkRendererContract.qualityFor(quality).renderScale.toDouble(),
            features = features,
            performancePreset = ChalFeatures.matchesPreset(features)
        )
    }

    fun matches(params: ChalSimulationParams, native: Boolean = true): Boolean =
        abs(params.mass - mass) < 1e-4 &&
            abs(params.spin - spin) < 1e-4 &&
            abs(params.lensing - lensing) < 1e-4 &&
            abs(params.diskSize - diskSize) < 1e-4 &&
            abs(params.diskTemp - diskTemp) < 1e-2 &&
            abs(params.diskDensity - diskDensity) < 1e-4 &&
            abs(params.diskScaleHeight - diskScaleHeight) < 1e-4 &&
            abs(params.zoom - cameraDistance) < 1e-4 &&
            params.features.rayTracingQuality == ChalRenderPolicy.quality(quality, native)

    companion object {
        fun matching(params: ChalSimulationParams, native: Boolean = true): ChalXapkScenario? = entries.firstOrNull { it.matches(params, native) }
    }
}
