package com.alijafari.red.astronomy.astro_engine

import kotlin.math.cos

/**
 * VSOP87D planetary theory (Bretagnon & Francou 1988, Bureau des Longitudes): heliocentric ecliptic
 * longitude L, latitude B and radius vector R of the eight major planets referred to the ecliptic and
 * equinox **of date**.
 *
 * The series coefficients live in [Vsop87dData] (generated from the official VSOP87D tables; see that
 * file for provenance and the truncation level). Over 1800–2200 the truncated series agree with the
 * full VSOP87D to ≤ 0.31″ in L and B, i.e. far below anything the app can display.
 *
 * Earlier revisions of this class contained hand-typed, partly invented "series" with the time argument
 * in centuries; they placed Mars, Jupiter and Saturn several degrees (Mars ~20°) away from their true
 * positions. This implementation is validated against the full VSOP87D and against an independent
 * ephemeris in `Vsop87dAccuracyTest`.
 */
class VSOP87Engine {

    companion object {
        private const val RAD2DEG = 180.0 / Math.PI

        private fun normalizeAngle(deg: Double): Double {
            var d = deg % 360.0
            if (d < 0) d += 360.0
            return d
        }

        /** Σ_α τ^α Σ_k A_k cos(B_k + C_k τ) for one coordinate. */
        private fun evaluate(series: Array<DoubleArray>, tau: Double): Double {
            var result = 0.0
            var tauPower = 1.0
            for (alpha in series.indices) {
                val terms = series[alpha]
                var sum = 0.0
                var i = 0
                while (i < terms.size) {
                    sum += terms[i] * cos(terms[i + 1] + terms[i + 2] * tau)
                    i += 3
                }
                result += sum * tauPower
                tauPower *= tau
            }
            return result
        }
    }

    enum class Planet {
        MERCURY, VENUS, EARTH, MARS, JUPITER, SATURN, URANUS, NEPTUNE
    }

    data class HeliocentricEcliptic(
        val longitudeDeg: Double,   // L (ecliptic longitude of date)
        val latitudeDeg: Double,    // B (ecliptic latitude of date)
        val distanceAu: Double      // R (distance in AU)
    )

    /**
     * Heliocentric ecliptic coordinates of date.
     * @param astroTime instant; the series argument is τ = (JDE − 2451545.0) / 365250 with JDE in TT.
     */
    fun calculate(planet: Planet, astroTime: AstroTime): HeliocentricEcliptic = calculateTt(planet, astroTime.jdTt)

    /** Same as [calculate] for a Julian Ephemeris Date (TT ≈ TDB). */
    fun calculateTt(planet: Planet, jdTt: Double): HeliocentricEcliptic {
        val tau = (jdTt - 2451545.0) / 365250.0
        val (l, b, r) = when (planet) {
            Planet.MERCURY -> Triple(Vsop87dMercury.L, Vsop87dMercury.B, Vsop87dMercury.R)
            Planet.VENUS -> Triple(Vsop87dVenus.L, Vsop87dVenus.B, Vsop87dVenus.R)
            Planet.EARTH -> Triple(Vsop87dEarth.L, Vsop87dEarth.B, Vsop87dEarth.R)
            Planet.MARS -> Triple(Vsop87dMars.L, Vsop87dMars.B, Vsop87dMars.R)
            Planet.JUPITER -> Triple(Vsop87dJupiter.L, Vsop87dJupiter.B, Vsop87dJupiter.R)
            Planet.SATURN -> Triple(Vsop87dSaturn.L, Vsop87dSaturn.B, Vsop87dSaturn.R)
            Planet.URANUS -> Triple(Vsop87dUranus.L, Vsop87dUranus.B, Vsop87dUranus.R)
            Planet.NEPTUNE -> Triple(Vsop87dNeptune.L, Vsop87dNeptune.B, Vsop87dNeptune.R)
        }
        return HeliocentricEcliptic(
            longitudeDeg = normalizeAngle(evaluate(l, tau) * RAD2DEG),
            latitudeDeg = evaluate(b, tau) * RAD2DEG,
            distanceAu = evaluate(r, tau)
        )
    }
}
