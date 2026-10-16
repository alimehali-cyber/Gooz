package com.alijafari.red.astronomy.ar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Physically based, spectral clear-sky model of the Earth's atmosphere for the AR "camera off" sky.
 *
 * Method: precomputed atmospheric scattering after E. Bruneton (2017 reference implementation,
 * "Precomputed Atmospheric Scattering: a New Implementation") with S. Hillaire's multiple-scattering
 * approximation and sky-view LUT (2020, "A Scalable and Production Ready Sky and Atmosphere
 * Rendering Technique", EGSR):
 *
 *  - [N_LAMBDA] wavelengths between 400 and 700 nm (spectral, not RGB, so twilight hues are not
 *    distorted by three-wavelength sampling); results are converted to CIE XYZ with the CIE 1931
 *    colour-matching functions and then to linear sRGB, white-balanced to sunlight.
 *  - Rayleigh scattering ∝ λ⁻⁴ (5.802·10⁻⁶ m⁻¹ at 680 nm, H = 8 km); aerosol (Mie) scattering with
 *    an Ångström exponent of 1, H = 1.2 km, Cornette–Shanks phase function (g = 0.8); ozone absorption
 *    with the Bremen 233 K cross-sections used by Bruneton, 300 DU in a tent profile peaking at 25 km.
 *  - Solar spectral irradiance at the top of the atmosphere from the same reference tables.
 *  - Single scattering is ray-marched with the Earth's shadow (twilight, Belt of Venus, Earth shadow);
 *    multiple scattering uses Hillaire's isotropic infinite-order approximation.
 *
 * All radiances are absolute: cd/m² (luminance channel) in linear sRGB primaries for a light source of
 * unit relative strength (the Sun; the Moon is handled by scaling with its illuminance ratio).
 */
class SkyAtmosphere(val params: Params = Params()) {

    data class Params(
        val bottomRadiusKm: Double = 6360.0,
        val topRadiusKm: Double = 6460.0,
        val rayleighAt680PerKm: Double = 5.802e-3,
        val rayleighScaleHeightKm: Double = 8.0,
        /** Aerosol optical depth at 550 nm (clear continental sky ≈ 0.05–0.1). */
        val aerosolOpticalDepth550: Double = 0.06,
        val aerosolSingleScatteringAlbedo: Double = 0.90,
        val angstromExponent: Double = 1.0,
        val mieScaleHeightKm: Double = 1.2,
        val mieG: Double = 0.8,
        val ozoneDobsonUnits: Double = 300.0,
        val groundAlbedo: Double = 0.3
    )

    private val rb = params.bottomRadiusKm
    private val rt = params.topRadiusKm
    private val atmosphereHeight = rt - rb

    // Per-wavelength coefficients (km⁻¹)
    private val rayleigh = DoubleArray(N_LAMBDA)
    private val mieSca = DoubleArray(N_LAMBDA)
    private val mieExt = DoubleArray(N_LAMBDA)
    private val ozone = DoubleArray(N_LAMBDA)

    init {
        val mieExt550 = params.aerosolOpticalDepth550 / params.mieScaleHeightKm
        // 300 DU spread over the 30 km wide tent (mean density = half the peak over 30 km = 15 km equivalent)
        val dobson = 2.687e20 // molecules/m² per DU
        val peakNumberDensity = params.ozoneDobsonUnits * dobson / 15000.0 // m⁻³
        for (i in 0 until N_LAMBDA) {
            val l = LAMBDA[i]
            rayleigh[i] = params.rayleighAt680PerKm * (680.0 / l).pow(4.0)
            mieExt[i] = mieExt550 * (550.0 / l).pow(params.angstromExponent)
            mieSca[i] = mieExt[i] * params.aerosolSingleScatteringAlbedo
            ozone[i] = OZONE_CROSS_SECTION_M2[i] * peakNumberDensity * 1000.0 // m⁻¹ → km⁻¹
        }
    }

    // ---- LUTs ---------------------------------------------------------------------------------------
    private val tw = 128
    private val th = 48
    private val transmittance = FloatArray(tw * th * N_LAMBDA)
    private val mw = 32
    private val mh = 32
    private val multiScatter = FloatArray(mw * mh * N_LAMBDA)

    init {
        buildTransmittance()
        buildMultipleScattering()
    }

    // ------------------------------------------------------------------------------------------------
    // Media
    // ------------------------------------------------------------------------------------------------

    private fun rayleighDensity(h: Double) = exp(-max(h, -1.0) / params.rayleighScaleHeightKm)
    private fun mieDensity(h: Double) = exp(-max(h, -1.0) / params.mieScaleHeightKm)
    private fun ozoneDensity(h: Double) = max(0.0, 1.0 - abs(h - 25.0) / 15.0)

    private fun extinction(h: Double, out: DoubleArray) {
        val r = rayleighDensity(h); val m = mieDensity(h); val o = ozoneDensity(h)
        for (c in 0 until N_LAMBDA) out[c] = rayleigh[c] * r + mieExt[c] * m + ozone[c] * o
    }

    // ------------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------------

    private fun distanceToTop(r: Double, mu: Double): Double {
        val disc = r * r * (mu * mu - 1.0) + rt * rt
        return max(0.0, -r * mu + sqrt(max(disc, 0.0)))
    }

    private fun distanceToGround(r: Double, mu: Double): Double {
        val disc = r * r * (mu * mu - 1.0) + rb * rb
        if (disc < 0.0 || mu >= 0.0) return -1.0
        val d = -r * mu - sqrt(disc)
        return if (d >= 0.0) d else -1.0
    }

    // ------------------------------------------------------------------------------------------------
    // Transmittance LUT
    // ------------------------------------------------------------------------------------------------

    private fun hToV(h: Double) = sqrt((h / atmosphereHeight).coerceIn(0.0, 1.0))
    private fun vToH(v: Double) = v * v * atmosphereHeight

    private fun muToU(mu: Double): Double {
        val m = mu.coerceIn(-1.0, 1.0)
        return 0.5 + 0.5 * sign(m) * sqrt(abs(m))
    }

    private fun uToMu(u: Double): Double {
        val c = 2.0 * u - 1.0
        return sign(c) * c * c
    }

    private fun buildTransmittance() {
        val ext = DoubleArray(N_LAMBDA)
        val od = DoubleArray(N_LAMBDA)
        for (j in 0 until th) {
            val h = vToH((j + 0.5) / th)
            val r = rb + h
            for (i in 0 until tw) {
                val mu = uToMu((i + 0.5) / tw)
                val k = (j * tw + i) * N_LAMBDA
                if (distanceToGround(r, mu) > 0.0) {
                    for (c in 0 until N_LAMBDA) transmittance[k + c] = 0f
                    continue
                }
                val d = distanceToTop(r, mu)
                od.fill(0.0)
                val steps = 48
                for (s in 0 until steps) {
                    val t = d * (s + 0.5) / steps
                    val rr = sqrt(r * r + t * t + 2.0 * r * t * mu)
                    extinction(rr - rb, ext)
                    for (c in 0 until N_LAMBDA) od[c] += ext[c] * d / steps
                }
                for (c in 0 until N_LAMBDA) transmittance[k + c] = exp(-od[c]).toFloat()
            }
        }
    }

    private fun transmittanceToTop(r: Double, mu: Double, out: DoubleArray) {
        val v = hToV(r - rb) * th - 0.5
        val u = muToU(mu) * tw - 0.5
        bilinearN(transmittance, tw, th, N_LAMBDA, u, v, out)
    }

    /** Light-source visibility × transmittance (0 in the Earth's shadow), softened over the solar disc. */
    private fun lightTransmittance(r: Double, muS: Double, out: DoubleArray) {
        val sinHorizon = rb / r
        val cosHorizon = -sqrt(max(0.0, 1.0 - sinHorizon * sinHorizon))
        if (muS < cosHorizon - 0.0047) {
            out.fill(0.0)
            return
        }
        transmittanceToTop(r, max(muS, cosHorizon + 1e-6), out)
        val soft = ((muS - (cosHorizon - 0.0047)) / 0.0094).coerceIn(0.0, 1.0)
        for (c in 0 until N_LAMBDA) out[c] *= soft
    }

    // ------------------------------------------------------------------------------------------------
    // Phase functions
    // ------------------------------------------------------------------------------------------------

    private fun rayleighPhase(cosTheta: Double) = 3.0 / (16.0 * PI) * (1.0 + cosTheta * cosTheta)

    private fun miePhase(cosTheta: Double): Double {
        val g = params.mieG
        val k = 3.0 / (8.0 * PI) * (1.0 - g * g) / (2.0 + g * g)
        return k * (1.0 + cosTheta * cosTheta) / (1.0 + g * g - 2.0 * g * cosTheta).pow(1.5)
    }

    // ------------------------------------------------------------------------------------------------
    // Multiple scattering (Hillaire 2020 §5.5)
    // ------------------------------------------------------------------------------------------------

    private fun buildMultipleScattering() {
        val dirCount = 48
        val dirs = Array(dirCount) { i ->
            val z = 1.0 - 2.0 * (i + 0.5) / dirCount
            val rr = sqrt(max(0.0, 1.0 - z * z))
            val phi = i * PI * (3.0 - sqrt(5.0))
            doubleArrayOf(rr * cos(phi), rr * sin(phi), z)
        }
        val ext = DoubleArray(N_LAMBDA)
        val trSun = DoubleArray(N_LAMBDA)
        val throughput = DoubleArray(N_LAMBDA)
        val l2 = DoubleArray(N_LAMBDA)
        val fms = DoubleArray(N_LAMBDA)
        val pu = 1.0 / (4.0 * PI)
        val dirWeight = 4.0 * PI / dirCount
        for (j in 0 until mh) {
            val h = max(0.01, atmosphereHeight * (j + 0.5) / mh)
            val r = rb + h
            for (i in 0 until mw) {
                val muS = -1.0 + 2.0 * (i + 0.5) / mw
                val sx = sqrt(max(0.0, 1.0 - muS * muS)); val sz = muS
                l2.fill(0.0); fms.fill(0.0)
                for (dir in dirs) {
                    val mu = dir[2]
                    val dGround = distanceToGround(r, mu)
                    val dMax = if (dGround > 0.0) dGround else distanceToTop(r, mu)
                    val steps = 16
                    throughput.fill(1.0)
                    var tPrev = 0.0
                    for (s in 0 until steps) {
                        val f = (s + 1.0) / steps
                        val t = dMax * f * f
                        val dt = t - tPrev
                        val tm = 0.5 * (t + tPrev)
                        tPrev = t
                        val px = tm * dir[0]; val py = tm * dir[1]; val pz = r + tm * dir[2]
                        val pr = sqrt(px * px + py * py + pz * pz)
                        val ph = pr - rb
                        val muSp = (px * sx + pz * sz) / pr
                        extinction(ph, ext)
                        lightTransmittance(pr, muSp, trSun)
                        val dR = rayleighDensity(ph); val dM = mieDensity(ph)
                        for (c in 0 until N_LAMBDA) {
                            val scat = rayleigh[c] * dR + mieSca[c] * dM
                            val segT = exp(-ext[c] * dt)
                            val integ = (1.0 - segT) / max(ext[c], 1e-12)
                            l2[c] += throughput[c] * scat * trSun[c] * pu * integ * dirWeight * pu
                            fms[c] += throughput[c] * scat * integ * dirWeight * pu
                            throughput[c] *= segT
                        }
                    }
                    if (dGround > 0.0) {
                        val gx = dGround * dir[0]; val gy = dGround * dir[1]; val gz = r + dGround * dir[2]
                        val gr = sqrt(gx * gx + gy * gy + gz * gz)
                        val muG = (gx * sx + gz * sz) / gr
                        lightTransmittance(gr, muG, trSun)
                        val cosG = max(0.0, muG)
                        for (c in 0 until N_LAMBDA) {
                            l2[c] += throughput[c] * trSun[c] * cosG * params.groundAlbedo / PI * dirWeight * pu
                        }
                    }
                }
                val k = (j * mw + i) * N_LAMBDA
                for (c in 0 until N_LAMBDA) multiScatter[k + c] = (l2[c] / max(1e-6, 1.0 - fms[c])).toFloat()
            }
        }
    }

    private fun multipleScattering(h: Double, muS: Double, out: DoubleArray) {
        val u = (muS.coerceIn(-1.0, 1.0) + 1.0) / 2.0 * mw - 0.5
        val v = (h / atmosphereHeight).coerceIn(0.0, 1.0) * mh - 0.5
        bilinearN(multiScatter, mw, mh, N_LAMBDA, u, v, out)
    }

    // ------------------------------------------------------------------------------------------------
    // Radiance
    // ------------------------------------------------------------------------------------------------

    private val tlsExt = ThreadLocal.withInitial { DoubleArray(N_LAMBDA) }
    private val tlsTr = ThreadLocal.withInitial { DoubleArray(N_LAMBDA) }
    private val tlsMs = ThreadLocal.withInitial { DoubleArray(N_LAMBDA) }
    private val tlsTp = ThreadLocal.withInitial { DoubleArray(N_LAMBDA) }
    private val tlsAcc = ThreadLocal.withInitial { DoubleArray(N_LAMBDA) }

    /**
     * Spectral in-scattered radiance (W m⁻² sr⁻¹ nm⁻¹ for the solar spectrum) arriving at an observer at
     * [observerHeightKm] from elevation [viewElevRad] with azimuth difference [dAzRad] to a light at
     * elevation [lightElevRad]. Rays hitting the ground include the lit ground ([groundAlbedo]).
     */
    fun spectralRadiance(
        viewElevRad: Double,
        dAzRad: Double,
        lightElevRad: Double,
        observerHeightKm: Double,
        out: DoubleArray,
        steps: Int = 30,
        groundAlbedo: Double = GROUND_DISPLAY_ALBEDO
    ) {
        val ext = tlsExt.get(); val trL = tlsTr.get(); val ms = tlsMs.get(); val throughput = tlsTp.get(); val acc = tlsAcc.get()
        val r = rb + max(0.002, observerHeightKm)
        val mu = sin(viewElevRad)
        val vx = cos(viewElevRad); val vz = mu
        val lx = cos(lightElevRad) * cos(dAzRad); val ly = cos(lightElevRad) * sin(dAzRad); val lz = sin(lightElevRad)
        val cosTheta = vx * lx + vz * lz
        val pR = rayleighPhase(cosTheta)
        val pM = miePhase(cosTheta)
        val dGround = distanceToGround(r, mu)
        val dMax = if (dGround > 0.0) dGround else distanceToTop(r, mu)
        throughput.fill(1.0); acc.fill(0.0)
        var tPrev = 0.0
        for (s in 0 until steps) {
            val f = (s + 1.0) / steps
            val t = dMax * f * f
            val dt = t - tPrev
            val tm = 0.5 * (t + tPrev)
            tPrev = t
            val px = tm * vx; val pz = r + tm * vz
            val pr = sqrt(px * px + pz * pz)
            val ph = pr - rb
            val muL = (px * lx + pz * lz) / pr
            extinction(ph, ext)
            lightTransmittance(pr, muL, trL)
            multipleScattering(ph, muL, ms)
            val dR = rayleighDensity(ph); val dM = mieDensity(ph)
            for (c in 0 until N_LAMBDA) {
                val sR = rayleigh[c] * dR
                val sM = mieSca[c] * dM
                val single = trL[c] * (sR * pR + sM * pM)
                val multi = ms[c] * (sR + sM)
                val segT = exp(-ext[c] * dt)
                val integ = (1.0 - segT) / max(ext[c], 1e-12)
                acc[c] += throughput[c] * (single + multi) * integ
                throughput[c] *= segT
            }
        }
        if (dGround > 0.0) {
            val gx = dGround * vx; val gz = r + dGround * vz
            val gr = sqrt(gx * gx + gz * gz)
            val muG = (gx * lx + gz * lz) / gr
            lightTransmittance(gr, muG, trL)
            // Ground also receives skylight (≈ 20 % of the direct light at moderate Sun heights).
            val cosG = max(0.0, muG)
            for (c in 0 until N_LAMBDA) acc[c] += throughput[c] * trL[c] * cosG * 1.2 * groundAlbedo / PI
        }
        for (c in 0 until N_LAMBDA) out[c] = acc[c] * SOLAR_IRRADIANCE[c]
    }

    /** Converts a spectral radiance (per [spectralRadiance]) to linear sRGB in cd/m², white-balanced to sunlight. */
    fun spectrumToRgb(spec: DoubleArray, out: DoubleArray) {
        var x = 0.0; var y = 0.0; var z = 0.0
        for (c in 0 until N_LAMBDA) {
            x += spec[c] * CMF_X[c]; y += spec[c] * CMF_Y[c]; z += spec[c] * CMF_Z[c]
        }
        out[0] = (3.2404542 * x - 1.5371385 * y - 0.4985314 * z) * WB[0]
        out[1] = (-0.9692660 * x + 1.8760108 * y + 0.0415560 * z) * WB[1]
        out[2] = (0.0556434 * x - 0.2040259 * y + 1.0572252 * z) * WB[2]
    }

    /** Linear sRGB radiance (cd/m² luminance scale) for the Sun as light source. */
    fun radianceRgb(viewElevRad: Double, dAzRad: Double, lightElevRad: Double, observerHeightKm: Double, out: DoubleArray) {
        val spec = DoubleArray(N_LAMBDA)
        spectralRadiance(viewElevRad, dAzRad, lightElevRad, observerHeightKm, spec)
        spectrumToRgb(spec, out)
    }

    /**
     * Direct-light colour factor (linear sRGB, white = 1) after the atmosphere at an apparent elevation:
     * the reddening and dimming of the Sun, Moon, planets and stars near the horizon.
     */
    fun extinctionRgb(elevRad: Double, observerHeightKm: Double, out: DoubleArray) {
        val r = rb + max(0.002, observerHeightKm)
        val mu = sin(elevRad)
        if (distanceToGround(r, mu) > 0.0) {
            out[0] = 0.0; out[1] = 0.0; out[2] = 0.0
            return
        }
        val tr = DoubleArray(N_LAMBDA)
        transmittanceToTop(r, mu, tr)
        val spec = DoubleArray(N_LAMBDA)
        for (c in 0 until N_LAMBDA) spec[c] = tr[c] * SOLAR_IRRADIANCE[c]
        spectrumToRgb(spec, out)
        // Normalize by the unattenuated Sun so that no extinction gives (1, 1, 1).
        out[0] /= SUN_RGB[0]; out[1] /= SUN_RGB[1]; out[2] /= SUN_RGB[2]
    }

    /**
     * Sky-view LUT for an observer near the ground: [SKY_LUT_W] azimuths (0..π from the light's vertical;
     * the sky is mirror-symmetric) × [SKY_LUT_H] elevations with the nonlinear mapping [elevationToV].
     * Linear sRGB in cd/m² for the Sun as light source; row-major, 3 floats per texel.
     */
    fun buildSkyViewLut(lightElevRad: Double, observerHeightKm: Double): FloatArray {
        val lut = FloatArray(SKY_LUT_W * SKY_LUT_H * 3)
        val spec = DoubleArray(N_LAMBDA)
        val rgb = DoubleArray(3)
        for (j in 0 until SKY_LUT_H) {
            val elev = vToElevation((j + 0.5) / SKY_LUT_H)
            for (i in 0 until SKY_LUT_W) {
                val dAz = PI * (i + 0.5) / SKY_LUT_W
                spectralRadiance(elev, dAz, lightElevRad, observerHeightKm, spec)
                spectrumToRgb(spec, rgb)
                val k = (j * SKY_LUT_W + i) * 3
                lut[k] = max(0.0, rgb[0]).toFloat(); lut[k + 1] = max(0.0, rgb[1]).toFloat(); lut[k + 2] = max(0.0, rgb[2]).toFloat()
            }
        }
        return lut
    }

    companion object {
        const val SKY_LUT_W = 96
        const val SKY_LUT_H = 96
        const val N_LAMBDA = 8

        /** Display albedo of the dark landscape below the horizon. */
        const val GROUND_DISPLAY_ALBEDO = 0.10

        /** Band centres (nm), 40 nm wide bands covering 390–710 nm. */
        val LAMBDA = doubleArrayOf(410.0, 450.0, 490.0, 530.0, 570.0, 610.0, 650.0, 690.0)

        /**
         * Bruneton's tables (360–830 nm, 10 nm bins): solar spectral irradiance at the top of the atmosphere
         * (W m⁻² nm⁻¹) and the ozone absorption cross-section at 233 K (m², IUP Bremen 2011 reference spectra).
         */
        private val BRUNETON_SOLAR = doubleArrayOf(
            1.11776, 1.14259, 1.01249, 1.14716, 1.72765, 1.73054, 1.6887, 1.61253,
            1.91198, 2.03474, 2.02042, 2.02212, 1.93377, 1.95809, 1.91686, 1.8298,
            1.8685, 1.8931, 1.85149, 1.8504, 1.8341, 1.8345, 1.8147, 1.78158, 1.7533,
            1.6965, 1.68194, 1.64654, 1.6048, 1.52143, 1.55622, 1.5113, 1.474, 1.4482,
            1.41018, 1.36775, 1.34188, 1.31429, 1.28303, 1.26758, 1.2367, 1.2082,
            1.18737, 1.14683, 1.12362, 1.1058, 1.07124, 1.04992
        )
        private val BRUNETON_OZONE = doubleArrayOf(
            1.18e-27, 2.182e-28, 2.818e-28, 6.636e-28, 1.527e-27, 2.763e-27, 5.52e-27,
            8.451e-27, 1.582e-26, 2.316e-26, 3.669e-26, 4.924e-26, 7.752e-26, 9.016e-26,
            1.48e-25, 1.602e-25, 2.139e-25, 2.755e-25, 3.091e-25, 3.5e-25, 4.266e-25,
            4.672e-25, 4.398e-25, 4.701e-25, 5.019e-25, 4.305e-25, 3.74e-25, 3.215e-25,
            2.662e-25, 2.238e-25, 1.852e-25, 1.473e-25, 1.209e-25, 9.423e-26, 7.455e-26,
            6.566e-26, 5.105e-26, 4.15e-26, 4.228e-26, 3.237e-26, 2.451e-26, 2.801e-26,
            2.534e-26, 1.624e-26, 1.465e-26, 2.078e-26, 1.383e-26, 7.105e-27
        )

        /** Band average of a Bruneton table (bins start at 360 nm, 10 nm wide). */
        private fun bandAverage(table: DoubleArray, centre: Double): Double {
            var sum = 0.0; var n = 0
            var l = centre - 20.0
            while (l < centre + 20.0 - 1e-9) {
                val idx = ((l - 360.0) / 10.0).toInt().coerceIn(0, table.size - 1)
                sum += table[idx]; n++
                l += 10.0
            }
            return sum / n
        }

        val SOLAR_IRRADIANCE = DoubleArray(N_LAMBDA) { bandAverage(BRUNETON_SOLAR, LAMBDA[it]) }
        val OZONE_CROSS_SECTION_M2 = DoubleArray(N_LAMBDA) { bandAverage(BRUNETON_OZONE, LAMBDA[it]) }

        // CIE 1931 2° colour matching functions (Wyman, Sloan & Shirley 2013 fit) integrated over each band,
        // times 683 lm/W so that Y is in cd/m² for a radiance in W m⁻² sr⁻¹ nm⁻¹.
        private fun g(x: Double, mu: Double, s1: Double, s2: Double): Double {
            val s = if (x < mu) s1 else s2
            val t = (x - mu) / s
            return exp(-0.5 * t * t)
        }
        private fun xBar(l: Double) = 1.056 * g(l, 599.8, 37.9, 31.0) + 0.362 * g(l, 442.0, 16.0, 26.7) - 0.065 * g(l, 501.1, 20.4, 26.2)
        private fun yBar(l: Double) = 0.821 * g(l, 568.8, 46.9, 40.5) + 0.286 * g(l, 530.9, 16.3, 31.1)
        private fun zBar(l: Double) = 1.217 * g(l, 437.0, 11.8, 36.0) + 0.681 * g(l, 459.0, 26.0, 13.8)
        private fun bandIntegral(f: (Double) -> Double, centre: Double): Double {
            var s = 0.0
            var l = centre - 20.0 + 0.5
            while (l < centre + 20.0) { s += f(l); l += 1.0 }
            return s * 683.0
        }
        val CMF_X = DoubleArray(N_LAMBDA) { bandIntegral(::xBar, LAMBDA[it]) }
        val CMF_Y = DoubleArray(N_LAMBDA) { bandIntegral(::yBar, LAMBDA[it]) }
        val CMF_Z = DoubleArray(N_LAMBDA) { bandIntegral(::zBar, LAMBDA[it]) }

        /** Unattenuated sunlight in linear sRGB without white balance, and the white-balance gains. */
        private val SUN_RAW: DoubleArray
        val WB: DoubleArray
        init {
            var x = 0.0; var y = 0.0; var z = 0.0
            for (c in 0 until N_LAMBDA) {
                x += SOLAR_IRRADIANCE[c] * CMF_X[c]; y += SOLAR_IRRADIANCE[c] * CMF_Y[c]; z += SOLAR_IRRADIANCE[c] * CMF_Z[c]
            }
            SUN_RAW = doubleArrayOf(
                3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
                -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
                0.0556434 * x - 0.2040259 * y + 1.0572252 * z
            )
            // Gains so that sunlight becomes neutral with its photopic luminance unchanged.
            WB = doubleArrayOf(y / SUN_RAW[0], y / SUN_RAW[1], y / SUN_RAW[2])
        }

        /** Sunlight at the top of the atmosphere after white balance: neutral, = solar illuminance (lux). */
        val SUN_RGB: DoubleArray = doubleArrayOf(SUN_RAW[0] * WB[0], SUN_RAW[1] * WB[1], SUN_RAW[2] * WB[2])

        /** Solar illuminance outside the atmosphere for the photopic channel, lux. */
        val SOLAR_ILLUMINANCE_LUX: Double = SUN_RGB[1]

        /** Elevation (rad) ↔ LUT v: more resolution near the horizon. */
        fun elevationToV(elevRad: Double): Double {
            val l = elevRad.coerceIn(-PI / 2, PI / 2)
            return 0.5 + 0.5 * sign(l) * sqrt(abs(l) / (PI / 2))
        }

        fun vToElevation(v: Double): Double {
            val c = (v - 0.5) * 2.0
            return sign(c) * c * c * (PI / 2)
        }

        /** Bilinear lookup in a grid with [n] channels per texel and clamp-to-edge. */
        fun bilinearN(grid: FloatArray, w: Int, h: Int, n: Int, u: Double, v: Double, out: DoubleArray) {
            val uc = u.coerceIn(0.0, (w - 1).toDouble())
            val vc = v.coerceIn(0.0, (h - 1).toDouble())
            val i0 = uc.toInt().coerceIn(0, w - 2)
            val j0 = vc.toInt().coerceIn(0, h - 2)
            val fu = uc - i0; val fv = vc - j0
            val i1 = min(i0 + 1, w - 1); val j1 = min(j0 + 1, h - 1)
            val a0 = (j0 * w + i0) * n; val b0 = (j0 * w + i1) * n
            val d0 = (j1 * w + i0) * n; val e0 = (j1 * w + i1) * n
            for (c in 0 until n) {
                out[c] = (grid[a0 + c] * (1 - fu) + grid[b0 + c] * fu) * (1 - fv) +
                    (grid[d0 + c] * (1 - fu) + grid[e0 + c] * fu) * fv
            }
        }

        /** RGB (3-channel) bilinear lookup. */
        fun bilinear(grid: FloatArray, w: Int, h: Int, u: Double, v: Double, out: DoubleArray) = bilinearN(grid, w, h, 3, u, v, out)

        /** Relative airmass (Kasten & Young 1989) for an apparent altitude in degrees. */
        fun airmass(altDeg: Double): Double {
            val h = max(altDeg, -0.9)
            return 1.0 / (sin(Math.toRadians(h)) + 0.50572 * (h + 6.07995).pow(-1.6364))
        }
    }
}
