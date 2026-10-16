package com.alijafari.red.astronomy.ar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Sky light for one instant: the sky-view LUTs lit by the Sun and by the Moon, the dark-sky
 * background (airglow + light pollution) and the eye's adaptation luminance. Shared by the GL sky
 * (as textures + uniforms) and by the Canvas glyphs (star visibility), so both see the same sky.
 */
class ArSkyLighting(
    val sunLut: FloatArray,
    val moonLut: FloatArray,
    /** Multiplier applied to [moonLut] (lunar/solar illuminance ratio). */
    val moonScale: Double,
    val sunElevRad: Double,
    val sunAzRad: Double,
    val moonElevRad: Double,
    val moonAzRad: Double,
    val bortle: Int,
    /** Zenith transmittance (linear RGB) for extinction of the Milky Way in the shader. */
    val zenithTransmittance: DoubleArray,
    /** Log-average luminance of the visible sky (cd/m²): the eye's adaptation level. */
    val adaptLuminance: Double
) {
    /** Sky luminance (cd/m², photopic) towards an apparent ENU direction, Milky Way excluded. */
    fun luminanceAt(e: Double, n: Double, u: Double): Double {
        val elev = asin(u.coerceIn(-1.0, 1.0))
        val az = atan2(e, n)
        val rgb = DoubleArray(3)
        val acc = DoubleArray(3)
        lutLookup(sunLut, elev, az - sunAzRad, rgb)
        acc[0] = rgb[0]; acc[1] = rgb[1]; acc[2] = rgb[2]
        if (moonScale > 0.0) {
            lutLookup(moonLut, elev, az - moonAzRad, rgb)
            for (i in 0..2) acc[i] += rgb[i] * moonScale
        }
        var l = SkyRenderModel.luminance(acc[0], acc[1], acc[2])
        if (elev > 0.0) l += SkyRenderModel.nightBackgroundLuminance(Math.toDegrees(elev), bortle, zenithTransmittance[1])
        return l
    }

    /** Sky surface brightness (V mag/arcsec²) towards an apparent ENU direction. */
    fun magArcsec2At(e: Double, n: Double, u: Double): Double = SkyRenderModel.luminanceToMagArcsec2(luminanceAt(e, n, u))

    companion object {
        private fun lutLookup(lut: FloatArray, elev: Double, dAz: Double, out: DoubleArray) {
            val u = abs(AngleUtil.wrapPi(dAz)) / PI * SkyAtmosphere.SKY_LUT_W - 0.5
            val v = SkyAtmosphere.elevationToV(elev) * SkyAtmosphere.SKY_LUT_H - 0.5
            SkyAtmosphere.bilinear(lut, SkyAtmosphere.SKY_LUT_W, SkyAtmosphere.SKY_LUT_H, u, v, out)
        }

        /** Log-average luminance over the upper hemisphere (cos-weighted), including [extra] everywhere. */
        fun logAverage(sun: FloatArray, moon: FloatArray, moonScale: Double, extra: Double): Double {
            var sum = 0.0; var wsum = 0.0
            for (j in 0 until SkyAtmosphere.SKY_LUT_H) {
                val elev = SkyAtmosphere.vToElevation((j + 0.5) / SkyAtmosphere.SKY_LUT_H)
                if (elev <= 0.0) continue
                val w = cos(elev)
                for (i in 0 until SkyAtmosphere.SKY_LUT_W) {
                    val k = (j * SkyAtmosphere.SKY_LUT_W + i) * 3
                    var l = SkyRenderModel.luminance(sun[k].toDouble(), sun[k + 1].toDouble(), sun[k + 2].toDouble())
                    if (moonScale > 0.0) l += moonScale * SkyRenderModel.luminance(moon[k].toDouble(), moon[k + 1].toDouble(), moon[k + 2].toDouble())
                    sum += ln(max(l + extra, 1e-9)) * w
                    wsum += w
                }
            }
            return exp(sum / wsum)
        }
    }
}

/**
 * Builds [ArSkyLighting], re-using the expensive sky-view LUTs while the Sun and Moon have moved by
 * less than a small threshold (the sky changes imperceptibly within 0.1° of solar elevation).
 * Thread-confined: call from one background thread.
 */
class ArSkyLightingBuilder(private val atmosphere: SkyAtmosphere = SkyAtmosphere()) {

    private var lastSunElev = Double.NaN
    private var lastMoonElev = Double.NaN
    private var sunLut: FloatArray? = null
    private var moonLut: FloatArray? = null

    val atmosphereModel: SkyAtmosphere get() = atmosphere

    fun build(sunEnu: DoubleArray, moonEnu: DoubleArray, moonPhaseAngleDeg: Double, observerHeightM: Double, bortle: Int): ArSkyLighting {
        val sunElev = asin(sunEnu[2].coerceIn(-1.0, 1.0))
        val moonElev = asin(moonEnu[2].coerceIn(-1.0, 1.0))
        val hKm = (observerHeightM / 1000.0).coerceIn(0.002, 4.0)
        val sl = sunLut
        val sLut = if (sl == null || lastSunElev.isNaN() || abs(sunElev - lastSunElev) > Math.toRadians(0.1)) {
            atmosphere.buildSkyViewLut(sunElev, hKm).also { sunLut = it; lastSunElev = sunElev }
        } else sl
        val ml = moonLut
        val mLut = if (ml == null || lastMoonElev.isNaN() || abs(moonElev - lastMoonElev) > Math.toRadians(0.3)) {
            atmosphere.buildSkyViewLut(moonElev, hKm).also { moonLut = it; lastMoonElev = moonElev }
        } else ml
        val moonScale = SkyRenderModel.moonToSunIlluminanceRatio(moonPhaseAngleDeg)
        val tz = DoubleArray(3)
        atmosphere.extinctionRgb(PI / 2, hKm, tz)
        val night = SkyRenderModel.nightBackgroundLuminance(60.0, bortle, tz[1])
        val adapt = ArSkyLighting.logAverage(sLut, mLut, moonScale, night + SkyRenderModel.MILKY_WAY_PEAK_LUMINANCE * 0.2)
        return ArSkyLighting(
            sunLut = sLut,
            moonLut = mLut,
            moonScale = moonScale,
            sunElevRad = sunElev,
            sunAzRad = atan2(sunEnu[0], sunEnu[1]),
            moonElevRad = moonElev,
            moonAzRad = atan2(moonEnu[0], moonEnu[1]),
            bortle = bortle,
            zenithTransmittance = tz,
            adaptLuminance = adapt
        )
    }
}
