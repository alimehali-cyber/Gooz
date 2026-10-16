package com.alijafari.red.astronomy.ar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Photometry of the Moon and of earthshine.
 *
 * Sources
 *  - Krisciunas, K. & Schaefer, B. E. 1991, "A model of the brightness of moonlight", PASP 103, 1033:
 *    eq. 9 (after Allen 1976) V(α) = −12.73 + 0.026|α| + 4×10⁻⁹ α⁴ at the mean distance, and eq. 8,
 *    illuminance I* = 10^(−0.4(V + 16.57)) foot-candles.
 *  - Qiu, J. et al. 2003, JGR 108(D22) 4709; Pallé, E. et al. 2003, JGR 108(D22) 4710; Pallé, E. et
 *    al. 2016, GRL 43, 4531, eqs 1–2 (Big Bear earthshine programme): the earthshine/moonshine ratio of
 *    two lunar patches of equal albedo is (2/3) p*(β) f_L(β) (R_E/R_EM)² (R_MS/R_ES)² f(θ₀)/f(θ), with
 *    the Earth's Lambert phase function f_L(β) = ((π − |β|) cos β + sin |β|)/π, Earth phase angle
 *    β ≈ π − θ, and apparent albedo p* ≈ 0.30 for |θ| ≥ 2π/3, where the Earth is nearly Lambertian.
 *  - Lommel–Seeliger law I ∝ μ₀/(μ₀ + μ) for the lunar regolith and its disc-integrated phase function
 *    Φ(α) = 1 − sin(α/2) tan(α/2) ln cot(α/4) (e.g. Fairbairn 2005, JRASC 99, 92). The sunlit surface is
 *    shaded with this law times f(α) = Φ_KS(α)/Φ_LS(α), so the disc-integrated light follows the
 *    observed lunar phase curve of Krisciunas & Schaefer exactly.
 */
object LunarPhotometry {
    const val MEAN_DISTANCE_KM = 384_400.0
    const val MOON_RADIUS_KM = 1737.4
    const val EARTH_RADIUS_KM = 6371.0
    private const val LUX_PER_FOOT_CANDLE = 10.7639

    /** Apparent albedo p* of the Earth for near-crescent lunar phases (Pallé et al. 2016). */
    const val EARTH_APPARENT_ALBEDO = 0.30

    /** Above this phase angle the Krisciunas–Schaefer phase law is extrapolated, so it is held there. */
    private const val MAX_FITTED_PHASE_DEG = 165.0

    /** Magnitude drop with phase angle relative to full Moon (K&S 1991 eq. 9 without the constant). */
    fun phaseMagnitudeDrop(phaseAngleDeg: Double): Double {
        val a = abs(phaseAngleDeg).coerceAtMost(180.0)
        return 0.026 * a + 4.0e-9 * a.pow(4)
    }

    /** Visual magnitude of the Moon at phase angle [phaseAngleDeg], observer distance [distanceKm] and Sun distance [sunDistanceAu]. */
    fun magnitudeV(phaseAngleDeg: Double, distanceKm: Double, sunDistanceAu: Double = 1.0): Double =
        -12.73 + phaseMagnitudeDrop(phaseAngleDeg) + 5.0 * log10(distanceKm / MEAN_DISTANCE_KM * sunDistanceAu)

    /** Illuminance (lux) at the top of the atmosphere from an object of visual magnitude [v] (K&S 1991 eq. 8). */
    fun illuminanceLux(v: Double): Double = LUX_PER_FOOT_CANDLE * 10.0.pow(-0.4 * (v + 16.57))

    /** Angular radius (radians) of the Moon at [distanceKm]. */
    fun angularRadiusRad(distanceKm: Double): Double = asin((MOON_RADIUS_KM / distanceKm).coerceIn(0.0, 1.0))

    /** Solid angle (sr) of the lunar disc at [distanceKm]. */
    fun discSolidAngle(distanceKm: Double): Double = 2.0 * PI * (1.0 - cos(angularRadiusRad(distanceKm)))

    /** Illuminated fraction of the disc. */
    fun illuminatedFraction(phaseAngleDeg: Double): Double = (1.0 + cos(Math.toRadians(phaseAngleDeg))) / 2.0

    /** Mean luminance (cd/m², outside the atmosphere) of the whole disc at zero phase: the scale of the albedo map. */
    fun fullDiscLuminance(distanceKm: Double, sunDistanceAu: Double = 1.0): Double =
        illuminanceLux(magnitudeV(0.0, distanceKm, sunDistanceAu)) / discSolidAngle(distanceKm)

    /** Mean luminance (cd/m², outside the atmosphere) of the sunlit part of the disc. */
    fun litMeanLuminance(phaseAngleDeg: Double, distanceKm: Double, sunDistanceAu: Double = 1.0): Double {
        val a = abs(phaseAngleDeg).coerceAtMost(MAX_FITTED_PHASE_DEG)
        val k = max(illuminatedFraction(a), 1e-6)
        return illuminanceLux(magnitudeV(a, distanceKm, sunDistanceAu)) / (k * discSolidAngle(distanceKm))
    }

    /** Disc-integrated phase function of a Lommel–Seeliger sphere, 1 at α = 0. */
    fun lommelSeeligerPhase(phaseAngleRad: Double): Double {
        val a = abs(phaseAngleRad)
        if (a < 1e-6) return 1.0
        if (a >= PI) return 0.0
        val cot = 1.0 / tan(a / 4.0)
        return (1.0 - sin(a / 2.0) * tan(a / 2.0) * ln(cot)).coerceAtLeast(0.0)
    }

    /**
     * Multiplier of the Lommel–Seeliger radiance (normalised to 1 on the full disc at α = 0) that makes
     * the disc-integrated brightness follow the Krisciunas–Schaefer phase curve.
     */
    fun surfacePhaseCorrection(phaseAngleDeg: Double): Double {
        val a = abs(phaseAngleDeg).coerceAtMost(MAX_FITTED_PHASE_DEG)
        val ks = 10.0.pow(-0.4 * phaseMagnitudeDrop(a))
        return ks / max(lommelSeeligerPhase(Math.toRadians(a)), 1e-9)
    }

    /** Lambert phase function of the Earth seen from the Moon (1 at full Earth). */
    fun earthLambertPhase(earthPhaseAngleRad: Double): Double {
        val b = abs(earthPhaseAngleRad).coerceAtMost(PI)
        return ((PI - b) * cos(b) + sin(b)) / PI
    }

    /**
     * Earthshine luminance of a lunar patch divided by that patch's luminance at full Moon (same
     * distances), for lunar phase angle [phaseAngleDeg] (Earth phase angle β = 180° − θ).
     */
    fun earthshineRatio(phaseAngleDeg: Double, distanceKm: Double): Double {
        val beta = PI - Math.toRadians(abs(phaseAngleDeg).coerceAtMost(180.0))
        val r = EARTH_RADIUS_KM / distanceKm
        return 2.0 / 3.0 * EARTH_APPARENT_ALBEDO * earthLambertPhase(beta) * r * r
    }

    /** Mean earthshine luminance (cd/m², outside the atmosphere) of the night side. */
    fun earthshineLuminance(phaseAngleDeg: Double, distanceKm: Double, sunDistanceAu: Double = 1.0): Double =
        fullDiscLuminance(distanceKm, sunDistanceAu) * earthshineRatio(phaseAngleDeg, distanceKm)
}

/**
 * Visibility of extended targets to the human eye.
 *
 * Sources
 *  - Crumey, A. 2014, "Human contrast threshold and astronomical visibility", MNRAS 442, 2600: the
 *    threshold contrast of a uniform achromatic target of solid angle A against a background of
 *    luminance B (Blackwell 1946 data; Taylor 1960 for large targets), C = ((R/A)^q + C∞^q)^(1/q) with
 *    R from eqs 25/28, C∞ from eqs 39/40 and q from eqs 42–44.
 *  - CIE 146:2002, "CIE equations for disability glare" (Vos et al.): general disability glare
 *    equation L_veil/E_glare = 10/θ³ + (5/θ² + 0.1 p/θ)(1 + (A/62.5)⁴) + 0.0025 p, θ in degrees
 *    (valid 0.1°–100°), observer age A and eye pigmentation factor p.
 *  - Visibility level VL = C/C_threshold (Adrian 1989, Lighting Res. Technol. 21, 181; CIE 19/2).
 */
object HumanVision {
    /**
     * Ratio of a practical observer's threshold to Blackwell's highly trained laboratory observers
     * (Crumey 2014 §1.5 discusses field factors of about 2 for naked-eye astronomy).
     */
    const val FIELD_FACTOR = 2.0
    const val OBSERVER_AGE_YEARS = 25.0
    const val EYE_PIGMENTATION = 0.5

    /** Visibility level at which a target is taken as fully conspicuous (one log unit above threshold). */
    const val FULL_VISIBILITY_LEVEL = 10.0

    /** Threshold contrast ΔB/B for a uniform target of [areaSr] steradians against [backgroundCdM2]. */
    fun thresholdContrast(backgroundCdM2: Double, areaSr: Double, fieldFactor: Double = FIELD_FACTOR): Double {
        val b = backgroundCdM2.coerceIn(3e-5, 1e5)
        val area = areaSr.coerceAtLeast(1e-10)
        val b4 = b.pow(-0.25)
        val b2 = b.pow(-0.5)
        // Point-source (Ricco) constant, Crumey eqs 25 and 28.
        val sr = sqrt(max(0.0, 5.949e-8 * b2 - 2.389e-7 * b4 + 2.459e-7)) + 4.120e-4 * b4 - 4.225e-4
        val r = sr * sr
        // Large-target threshold, eqs 39 and 40.
        val cInf = sqrt(max(0.0, 9.606e-6 * b2 - 4.112e-5 * b4 + 5.019e-5)) + 4.837e-3 * b4 - 4.884e-3
        val q = when {
            b >= 3.40 -> 1.146 - 0.0885 * log10(b)
            b >= 0.193 -> 0.8861 + 0.4 * log10(b)
            else -> 0.6
        }
        val c = ((r / area).pow(q) + max(cInf, 1e-9).pow(q)).pow(1.0 / q)
        return c * fieldFactor
    }

    private val DEFAULT_AGE_FACTOR = 1.0 + (OBSERVER_AGE_YEARS / 62.5).pow(4)

    /** CIE 146:2002 general disability glare: veiling luminance (cd/m²) per lux of glare illuminance at [thetaDeg]. */
    fun glareSpread(thetaDeg: Double, ageYears: Double = OBSERVER_AGE_YEARS, pigment: Double = EYE_PIGMENTATION): Double {
        val t = thetaDeg.coerceIn(0.1, 100.0)
        val age = if (ageYears == OBSERVER_AGE_YEARS) DEFAULT_AGE_FACTOR else 1.0 + (ageYears / 62.5).pow(4)
        return 10.0 / (t * t * t) + (5.0 / (t * t) + 0.1 * pigment / t) * age + 0.0025 * pigment
    }

    /**
     * How strongly a target of luminance increment [targetCdM2] over [backgroundCdM2] is seen, 0..1:
     * 0 at or below threshold, rising with the logarithm of the visibility level to 1 at
     * [FULL_VISIBILITY_LEVEL].
     */
    fun visibilityStrength(targetCdM2: Double, backgroundCdM2: Double, areaSr: Double): Double {
        if (targetCdM2 <= 0.0) return 0.0
        val c = targetCdM2 / max(backgroundCdM2, 3e-5)
        val vl = c / thresholdContrast(backgroundCdM2, areaSr)
        if (vl <= 1.0) return 0.0
        val x = (log10(vl) / log10(FULL_VISIBILITY_LEVEL)).coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    /**
     * Fraction of the light reaching the eye from a patch that the target itself contributes when the
     * sky's own light ([backgroundCdM2]) lies in front of it: L/(L + B).
     */
    fun luminanceShare(targetCdM2: Double, backgroundCdM2: Double): Double {
        if (targetCdM2 <= 0.0) return 0.0
        return targetCdM2 / (targetCdM2 + max(backgroundCdM2, 0.0))
    }
}

/**
 * Everything the Moon glyph needs beyond geometry: the photometric scale, the sky in front of the Moon
 * and how the result is displayed.
 */
class MoonLook(
    /** Observer–Moon distance (km). */
    val distanceKm: Double,
    /** Sun–Moon distance (AU). */
    val sunDistanceAu: Double,
    /** Luminance of the sky in front of the Moon (cd/m²). */
    val skyLuminance: Double,
    /** Atmospheric transmittance of the Moon's light (photopic, 0..1). */
    val transmittance: Double,
    /** Lowest opacity of the sunlit part (AR camera mode keeps the glyph findable in daylight); 0 = physical. */
    val minLitOpacity: Double = 0.0
) {
    fun quantizedKey(): String {
        fun q(v: Double, step: Double): Long = if (v.isFinite()) kotlin.math.round(v / step).toLong() else Long.MIN_VALUE
        return buildString {
            append(q(log10(max(skyLuminance, 1e-6)), 0.04)).append(',')
            append(q(transmittance, 0.02)).append(',')
            append(q(distanceKm, 1500.0)).append(',')
            append(q(minLitOpacity, 0.05))
        }
    }

    companion object {
        /**
         * Brightness of the earthlit night side relative to the sunlit surface on the display, as a factor
         * on encoded (sRGB) values — the Home Moon card's night side (0.11 of the plate), which is the
         * reference rendition for a thin crescent in a dark sky.
         */
        const val NIGHT_SIDE_ENCODED_LEVEL = 0.11

        /**
         * Colour of earthshine relative to sunlight on the Moon (linear sRGB, luminance-neutral): the
         * earthlit dark side is 0.150 ± 0.005 mag bluer in B−V than the sunlit side (Thejll, Flynn,
         * Gleisner & Mattingly 2014, A&A 563, A38). Applied as a spectral tilt of −0.4·0.15 mag over
         * 550→440 nm evaluated at the sRGB primaries' dominant wavelengths (≈611, 549, 464 nm).
         */
        val EARTHSHINE_TINT: DoubleArray by lazy {
            val perNm = 0.4 * 0.150 / (550.0 - 440.0)
            val raw = doubleArrayOf(611.0, 549.0, 464.0).map { 10.0.pow(perNm * (550.0 - it)) }
            val lum = 0.2126 * raw[0] + 0.7152 * raw[1] + 0.0722 * raw[2]
            DoubleArray(3) { raw[it] / lum }
        }

        /** Earthshine/sunlit luminance ratio at which the night side is drawn at [NIGHT_SIDE_ENCODED_LEVEL]: a 150° crescent. */
        val REFERENCE_EARTHSHINE_CONTRAST: Double by lazy {
            LunarPhotometry.earthshineLuminance(150.0, LunarPhotometry.MEAN_DISTANCE_KM) /
                LunarPhotometry.litMeanLuminance(150.0, LunarPhotometry.MEAN_DISTANCE_KM)
        }

        /**
         * Encoded display level of the night side for phase angle [phaseAngleDeg]: the card's level scaled
         * by the cube root of the physical earthshine/sunlit ratio relative to the reference crescent —
         * perceived brightness grows as the cube root of luminance (Stevens 1957; CIE L*), and the sunlit
         * part is shown at the eye's local adaptation.
         */
        fun nightSideLevel(phaseAngleDeg: Double, distanceKm: Double, sunDistanceAu: Double): Double {
            val ratio = LunarPhotometry.earthshineLuminance(phaseAngleDeg, distanceKm, sunDistanceAu) /
                max(LunarPhotometry.litMeanLuminance(phaseAngleDeg, distanceKm, sunDistanceAu), 1e-12)
            val rel = (ratio / REFERENCE_EARTHSHINE_CONTRAST).coerceIn(0.0, 2.0)
            return NIGHT_SIDE_ENCODED_LEVEL * rel.pow(1.0 / 3.0)
        }
    }
}

