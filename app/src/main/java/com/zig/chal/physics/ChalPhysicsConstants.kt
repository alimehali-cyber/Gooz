package com.zig.chal.physics

/**
 * Physics Constants & Formula Configuration.
 *
 * Centralized control for physical laws and mathematical constants used in shaders and
 * calculations. This object allows fine-tuning of the simulation's physical behavior, from the
 * speed of light to the specific coefficients used in blackbody radiation and gravitational
 * lensing.
 *
 * Verbatim port of the reference engine's `src/configs/physics.config.ts` and
 * `src/physics/constants.ts`. Every value is byte-identical to the source configuration; the
 * shader source interpolates these same numbers (see `ChalShaderSource`).
 *
 * Geometric units (G = c = 1) unless otherwise specified.
 */
object ChalPhysicsConstants {

    // ---------------------------------------------------------------------------------------
    // Fundamental Constants (Normalized for Simulation)
    // ---------------------------------------------------------------------------------------

    /** c = 1 in geometric units. Normalized speed of light for relativistic calculations. */
    const val SPEED_OF_LIGHT: Double = 1.0

    /** G = 1 in geometric units. Normalized gravitational constant. */
    const val GRAVITATIONAL_CONSTANT: Double = 1.0

    // ---------------------------------------------------------------------------------------
    // Color & Temperature Physics (Blackbody Radiation)
    // ---------------------------------------------------------------------------------------

    object Blackbody {
        const val TEMP_MIN: Double = 1000.0
        const val TEMP_MAX: Double = 1000000.0

        // Coefficients for converting temperature to RGB (Approximation of Planck's Law)
        object RedChannel {
            const val THRESHOLD: Double = 66.0
            const val EXPONENT: Double = -0.1332047592
            const val SCALE: Double = 1.292936186
            const val OFFSET: Double = 60.0
        }

        object GreenChannel {
            const val LOG_SCALE: Double = 0.39008157
            const val LOG_OFFSET: Double = -0.631841444
            const val POW_SCALE: Double = 1.129890861
            const val POW_EXPONENT: Double = -0.0755148492
        }

        object BlueChannel {
            const val THRESHOLD: Double = 19.0
            const val LOG_SCALE: Double = 0.543206789
            const val LOG_OFFSET: Double = -1.196254089
            const val OFFSET: Double = 10.0
        }
    }

    // ---------------------------------------------------------------------------------------
    // Accretion Disk Dynamics
    // ---------------------------------------------------------------------------------------

    object Accretion {
        /** Set higher to allow user-facing slider full range. */
        const val DISK_HEIGHT_MULTIPLIER: Double = 0.45

        /** Increased frequency to match larger radius. */
        const val TURBULENCE_SCALE: Double = 0.75

        /** Upped for finer nuances. */
        const val TURBULENCE_DETAIL: Double = 2.5

        /** Slightly slower for better stability. */
        const val TIME_SCALE: Double = 0.12

        /** Softened falloff to reduce 'vector lines' effect. */
        const val DENSITY_FALLOFF: Double = 0.25
    }

    // ---------------------------------------------------------------------------------------
    // Ray Marching Limits
    // ---------------------------------------------------------------------------------------

    object RayMarching {
        /** Maximum render distance. */
        const val MAX_DISTANCE: Double = 10000.0

        /** Minimum ray step size (precision). */
        const val MIN_STEP: Double = 0.01

        /** Maximum ray step size (speed). */
        const val MAX_STEP: Double = 1.2

        /** Multiplier for Event Horizon hit detection. */
        const val HORIZON_THRESHOLD: Double = 1.15
    }

    // ---------------------------------------------------------------------------------------
    // Lensing & Gravity
    // ---------------------------------------------------------------------------------------

    object Gravity {
        /** Multiplier for angular momentum barrier. */
        const val CENTRIFUGAL_SHIELD_STRENGTH: Double = 2.0

        /** Multiplier for space-time twist (ergosphere). */
        const val FRAME_DRAGGING_STRENGTH: Double = 2.0
    }

    // ---------------------------------------------------------------------------------------
    // Physical Constants for Black Hole Simulation (`src/physics/constants.ts`)
    // ---------------------------------------------------------------------------------------

    /** Schwarzschild radius for 1 Solar Mass (normalized units). */
    const val SCHWARZSCHILD_RADIUS_SOLAR: Double = 2.0

    /** Speed of light in m/s (approximate). */
    const val C: Double = 299792458.0

    /** Gravitational constant in m^3 kg^-1 s^-2. */
    const val G: Double = 6.6743e-11

    /** Solar Mass in kg. */
    const val SOLAR_MASS: Double = 1.989e30

    /** Reduced Planck constant. */
    const val H_BAR: Double = 1.0545718e-34

    /** Stefan-Boltzmann constant. */
    const val SIGMA: Double = 5.670374419e-8
}
