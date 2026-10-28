package com.zig.gargantua.geodesic

import kotlin.math.*

/**
 * Deterministic procedural celestial starfield and coordinate grid for background gravitational lensing.
 *
 * Provides a structured visual reference on the celestial sphere so that:
 * 1. Gravitational light bending and deflection arcs are obvious.
 * 2. Einstein rings and secondary images can be clearly resolved.
 * 3. Kerr frame-dragging shear is visually distinctive.
 *
 * Implemented in pure Kotlin for CPU reference validation, exactly matching the GLSL shader logic.
 */
object ProceduralSky {

    data class ColorRGB(val r: Double, val g: Double, val b: Double) {
        operator fun plus(o: ColorRGB): ColorRGB = ColorRGB(r + o.r, g + o.g, b + o.b)
        operator fun times(scalar: Double): ColorRGB = ColorRGB(r * scalar, g * scalar, b * scalar)
    }

    /** Simple pseudo-random hash for deterministic star placement on the sphere. */
    private fun hash21(p1: Double, p2: Double): Double {
        val s = sin(p1 * 127.1 + p2 * 311.7) * 43758.5453123
        return s - floor(s)
    }

    /**
     * Evaluates procedural sky color for a given normalized asymptotic exit direction (kx, ky, kz).
     */
    fun sampleSky(kx: Double, ky: Double, kz: Double): ColorRGB {
        val norm = sqrt(kx * kx + ky * ky + kz * kz)
        val dirX = if (norm > 1e-15) kx / norm else 0.0
        val dirY = if (norm > 1e-15) ky / norm else 1.0
        val dirZ = if (norm > 1e-15) kz / norm else 0.0

        // Spherical celestial angles
        val theta = acos(dirZ.coerceIn(-1.0, 1.0)) // 0 to PI
        val phi = atan2(dirY, dirX) // -PI to PI

        // 1. Deep space background gradient
        val bgSlate = ColorRGB(0.02, 0.03, 0.06)
        val bgDeepBlue = ColorRGB(0.04, 0.06, 0.12)
        val bgGradientFactor = 0.5 + 0.5 * dirZ
        var color = bgSlate * (1.0 - bgGradientFactor) + bgDeepBlue * bgGradientFactor

        // 2. Galactic plane band (inclined plane)
        val galacticDist = abs(dirZ * 0.8 + dirY * 0.6)
        val milkyWay = exp(-galacticDist * galacticDist * 16.0) * 0.25
        color += ColorRGB(0.25, 0.22, 0.35) * milkyWay

        // 3. Structured celestial coordinate grid lines (every 30 degrees = π/6 rad)
        val gridSpacing = Math.PI / 6.0
        val latRemainder = abs((theta % gridSpacing) - gridSpacing * 0.5)
        val lonRemainder = abs(((phi + Math.PI) % gridSpacing) - gridSpacing * 0.5)

        val gridLineWidth = 0.015
        val latLine = 1.0 - smoothstep(0.0, gridLineWidth, abs(latRemainder - gridSpacing * 0.5))
        val lonLine = 1.0 - smoothstep(0.0, gridLineWidth, abs(lonRemainder - gridSpacing * 0.5))
        val gridIntensity = max(latLine, lonLine) * 0.20
        color += ColorRGB(0.18, 0.35, 0.55) * gridIntensity

        // 4. Procedural point stars using angular grid hashing
        val starGridScale = 40.0
        val cellX = floor(phi * starGridScale)
        val cellY = floor(theta * starGridScale)

        val starRand = hash21(cellX, cellY)
        if (starRand > 0.75) {
            val starCenterX = (cellX + hash21(cellX, cellY + 1.0)) / starGridScale
            val starCenterY = (cellY + hash21(cellX + 1.0, cellY)) / starGridScale

            val dist = hypot(phi - starCenterX, theta - starCenterY) * starGridScale
            val starBrightness = max(0.0, 1.0 - dist * 3.5)
            val starMag = (starRand - 0.75) * 4.0 * starBrightness

            val starColor = if (hash21(cellX * 2.0, cellY) > 0.5) {
                ColorRGB(0.9, 0.95, 1.0) // Blue-white
            } else {
                ColorRGB(1.0, 0.85, 0.65) // Warm amber
            }
            color += starColor * starMag
        }

        return ColorRGB(
            color.r.coerceIn(0.0, 1.0),
            color.g.coerceIn(0.0, 1.0),
            color.b.coerceIn(0.0, 1.0)
        )
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
