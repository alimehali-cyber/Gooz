package com.zig.chal.config

/**
 * Centralized source of truth for Chal's numeric controls.
 *
 * The native-backed Lab screen uses the XAPK's control ranges. The GLES renderer remains available
 * as a compatibility fallback, but shares these UI values so a preset or saved state transfers
 * cleanly between backends.
 */
data class ChalParameterConfig(
    val default: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: String,
    val decimals: Int,
    val label: String
)

object ChalSimulationConfig {

    /** Ray tracing step budgets used by Chal's GLES fallback. */
    object RayTracingSteps {
        const val OFF: Int = 0
        const val LOW: Int = 32
        const val MEDIUM: Int = 64
        const val HIGH: Int = 128
        const val ULTRA: Int = 256
    }

    val DEFAULT_PRESET_MODE: ChalPresetName = ChalPresetName.HIGH_QUALITY

    val MASS = ChalParameterConfig(
        default = 1.0,
        min = 0.1,
        max = 20.0,
        step = 0.1,
        unit = "M☉",
        decimals = 1,
        label = "Black Hole Mass"
    )

    /** Dimensionless spin a* = J/M². The XAPK UI exposes prograde values in [0, 0.99]. */
    val SPIN = ChalParameterConfig(
        default = 0.5,
        min = 0.0,
        max = 0.99,
        step = 0.01,
        unit = "a*",
        decimals = 2,
        label = "Spin Parameter"
    )

    /** Legacy Chal camera value in degrees; 0.539 × π in the XAPK's normalized camera space. */
    val VERTICAL_ANGLE = ChalParameterConfig(
        default = 97.02,
        min = 9.0,
        max = 171.0,
        step = 1.0,
        unit = "deg",
        decimals = 1,
        label = "Initial Vertical Axis"
    )

    val ZOOM = ChalParameterConfig(
        default = 100.0,
        min = 1.5,
        max = 100.0,
        step = 0.5,
        unit = "rₛ☉",
        decimals = 1,
        label = "View Distance"
    )

    val AUTO_SPIN = ChalParameterConfig(
        default = 0.005,
        min = -0.05,
        max = 0.05,
        step = 0.001,
        unit = "rad/s",
        decimals = 3,
        label = "Cam Auto-Pan"
    )

    val DISK_SIZE = ChalParameterConfig(
        default = 50.0,
        min = 10.0,
        max = 120.0,
        step = 0.5,
        unit = "r_g",
        decimals = 1,
        label = "Accretion Max Radius"
    )

    val DISK_SCALE_HEIGHT = ChalParameterConfig(
        default = 0.2,
        min = 0.05,
        max = 0.45,
        step = 0.01,
        unit = "H/R",
        decimals = 2,
        label = "Disk Thickness"
    )

    val DISK_TEMP = ChalParameterConfig(
        default = 9500.0,
        min = 2000.0,
        max = 25_000.0,
        step = 500.0,
        unit = "K",
        decimals = 0,
        label = "Disk Temp"
    )

    val DISK_DENSITY = ChalParameterConfig(
        default = 4.0,
        min = 0.0,
        max = 10.0,
        step = 0.1,
        unit = "rel",
        decimals = 1,
        label = "Opt. Density"
    )

    val LENSING = ChalParameterConfig(
        default = 0.7,
        min = 0.0,
        max = 2.0,
        step = 0.1,
        unit = "η",
        decimals = 1,
        label = "Lensing Str"
    )

    val FRAME_DRAGGING = ChalParameterConfig(
        default = 2.0,
        min = 0.0,
        max = 4.0,
        step = 0.1,
        unit = "rel",
        decimals = 1,
        label = "Frame drag"
    )

    val BLOOM_THRESHOLD = ChalParameterConfig(
        default = 1.0,
        min = 0.0,
        max = 4.0,
        step = 0.1,
        unit = "rel",
        decimals = 1,
        label = "Bloom threshold"
    )

    val BLOOM_INTENSITY = ChalParameterConfig(
        default = 0.45,
        min = 0.0,
        max = 2.0,
        step = 0.05,
        unit = "rel",
        decimals = 2,
        label = "Bloom intensity"
    )

    /** GLES-only render scale. XAPK uses the corresponding scale selected by quality tier. */
    val RENDER_SCALE = ChalParameterConfig(
        default = 1.0,
        min = 0.5,
        max = 1.0,
        step = 0.05,
        unit = "x",
        decimals = 2,
        label = "Render Scale"
    )

    val FEATURES: ChalFeatureToggles = ChalPerformancePresets.byName(DEFAULT_PRESET_MODE)
}
