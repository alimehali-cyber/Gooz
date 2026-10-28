package com.alijafari.red.astronomy.astro_engine

/**
 * Single value type representing astronomical time.
 * This is the ONLY way time should enter the astronomy calculation layer.
 *
 * All times are internally stored as UTC milliseconds since epoch.
 * Conversions to TT, UT1, and Julian centuries are computed on demand.
 */
class AstroTime(utcMs: Long) {

    // ============================================================
    // Public Properties
    // ============================================================

    /** UTC milliseconds since 1970-01-01 00:00:00 UTC */
    val utcMs: Long = utcMs

    /** Julian Date in UTC */
    val jdUtc: Double
        get() = (utcMs / 86400000.0) + 2440587.5

    /** Julian Date in Terrestrial Time (TT) = JD_UTC + ΔT/86400 */
    val jdTt: Double
        get() = jdUtc + (deltaT / 86400.0)

    /** ΔT = TT - UT1 in seconds. Uses Espenak-Meeus polynomials. */
    val deltaT: Double
        get() = calculateDeltaT(jdUtc)

    /** Julian centuries from J2000.0 in Terrestrial Time */
    val jcTt: Double
        get() = (jdTt - 2451545.0) / 36525.0

    /** Julian centuries from J2000.0 in UTC (for cases where TT is not needed) */
    val jcUtc: Double
        get() = (jdUtc - 2451545.0) / 36525.0

    // ============================================================
    // Companion Object
    // ============================================================

    companion object {
        /**
         * Creates an AstroTime representing the current moment.
         */
        fun now(): AstroTime {
            return AstroTime(System.currentTimeMillis())
        }

        /**
         * Creates an AstroTime from a Julian Date in UTC.
         * @param jd Julian Date in UTC
         */
        fun fromJd(jd: Double): AstroTime {
            val ms = ((jd - 2440587.5) * 86400000.0).toLong()
            return AstroTime(ms)
        }

        /**
         * Creates an AstroTime from a specific UTC date and time.
         * @param year Gregorian year (e.g., 2026)
         * @param month Month (1-12)
         * @param day Day of month (1-31)
         * @param hour Hour (0-23)
         * @param minute Minute (0-59)
         * @param second Second (0-59)
         */
        fun fromUtcDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0): AstroTime {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                set(java.util.Calendar.YEAR, year)
                set(java.util.Calendar.MONTH, month - 1)
                set(java.util.Calendar.DAY_OF_MONTH, day)
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
                set(java.util.Calendar.SECOND, second)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            return AstroTime(cal.timeInMillis)
        }
    }

    // ============================================================
    // ΔT Calculation (Espenak-Meeus Polynomials)
    // ============================================================

    /**
     * Calculates ΔT = TT − UT1 in seconds using the Espenak–Meeus polynomial series.
     *
     * Reference: Fred Espenak & Jean Meeus, "Five Millennium Canon of Solar Eclipses:
     * −1999 to +3000", NASA/TP-2006-214141, §2.7 "Polynomial Expressions for ΔT".
     * Canonical online transcription (the coefficient source used for this port):
     *     https://eclipse.gsfc.nasa.gov/SEcat5/deltatpoly.html
     * Independently cross-checked against the published PDFs
     *     https://eclipse.gsfc.nasa.gov/5MCSE/5MCSE-Text11.pdf  (NASA/TP-2006-214141)
     *     https://eclipse.gsfc.nasa.gov/5MCLE/5MCLE-Text10.pdf  (NASA/TP-2009-214172)
     *
     * Every interval below is transcribed verbatim from that reference, including the
     * interval boundaries, the definition of the polynomial variable, and every
     * coefficient. The boundaries are half-open, [lo, hi), exactly as published.
     *
     * CONTINUITY. The published series is NOT continuous: it has genuine step
     * discontinuities at 6 of its 14 internal boundaries. They are a property of the
     * published polynomials, not transcription errors, and this implementation
     * reproduces them faithfully — the production jump equals the reference jump at
     * every boundary, to within float round-off. Measured jumps exceeding 0.05 s:
     *
     *     y =  500   −0.087121 s
     *     y = 1600   −0.251115 s   (largest)
     *     y = 1700   −0.162128 s
     *     y = 1860   +0.050180 s
     *     y = 1900   −0.088400 s
     *     y = 2005   −0.050071 s
     *
     * `DeltaTPolynomialTest` therefore does NOT assert continuity. It asserts a bound
     * on the change of ΔT between *sampled* instants — the worst step it measures is
     * 0.064614 s, against a 0.1 s threshold. That is a regression guard against a
     * mistyped interval or coefficient (it catches the historical +350.7 s / −188.4 s
     * errors by more than three orders of magnitude), not a continuity proof.
     *
     * DECIMAL YEAR. Espenak & Meeus define
     *     y = year + (month − 0.5)/12        (the middle of the month)
     * and state that month resolution "is accurate enough given the precision in the
     * known values of ΔT". This implementation instead derives the decimal year from
     * the Julian date on the Julian year:
     *     y = 2000 + (JD_UTC − 2451545.0) / 365.25
     * The two definitions agree at the middle of each month — that is exactly what
     * (month − 0.5)/12 encodes — and diverge toward month boundaries. Measured over
     * 1900–2100 the offset between them ranges from −17.1 to +13.3 days, and the
     * resulting difference in ΔT reaches 0.110 s (at 2100-03-01).
     *
     * That is small against the intrinsic scatter of the ΔT data, but it is NOT below
     * the 0.01 s resolution used by `DeltaTPolynomialTest`. All 21 expected values in
     * that test are computed under THIS implementation's Julian-date convention; 19 of
     * the 21 would fail a 0.01 s tolerance if regenerated from the Espenak & Meeus
     * month convention. Regenerate them from y = 2000 + (JD_UTC − 2451545.0)/365.25,
     * never from y = year + (month − 0.5)/12.
     *
     * TIME SCALE. ΔT is defined as TT − UT1. UT1 is approximated by UTC; by
     * construction |UT1 − UTC| < 0.9 s, which is under 0.001 s of lunar longitude,
     * and is neglected here as it is in Meeus.
     *
     * RANGE. The Canon constrains the series to −1999 … +3000. Inside that interval
     * the values are the published ones. Outside it the function continues with the
     * same long-term parabola that Espenak & Meeus themselves prescribe for
     * "before −500" and "after 2150"; those results are an EXTRAPOLATION whose
     * uncertainty grows quickly and are not observations.
     *
     * NOT APPLIED. The Canon also defines a lunar-secular-acceleration correction
     *     c = −0.000012932 · (y − 1955)²  seconds
     * needed only when pairing its ΔT with the ELP-2000/82 lunar ephemeris outside
     * 1955–2005. Over this app's supported range (1900–2100) |c| stays below 0.28 s,
     * i.e. a sub-arcsecond effect on the Moon, so it is deliberately omitted to keep
     * this function a faithful, reviewable transcription of the published polynomials.
     *
     * @param jd Julian Date in UTC
     * @return ΔT in seconds (never throws)
     */
    private fun calculateDeltaT(jd: Double): Double {
        val y = 2000.0 + (jd - 2451545.0) / 365.25

        return when {
            // ------------------------------------------------------------------
            // Outside the Canon interval. Long-term parabolic trend. EXTRAPOLATION.
            // ------------------------------------------------------------------
            y < -500.0 || y >= 2150.0 -> {
                val u = (y - 1820.0) / 100.0
                -20.0 + 32.0 * u * u
            }

            // ------------------------------------------------------------------
            // −500 … +500   (u = y/100)
            // ------------------------------------------------------------------
            y < 500.0 -> {
                val u = y / 100.0
                10583.6 - 1014.41 * u + 33.78311 * u * u - 5.952053 * u * u * u -
                    0.1798452 * u * u * u * u + 0.022174192 * u * u * u * u * u +
                    0.0090316521 * u * u * u * u * u * u
            }

            // ------------------------------------------------------------------
            // +500 … +1600   (u = (y−1000)/100)
            // ------------------------------------------------------------------
            y < 1600.0 -> {
                val u = (y - 1000.0) / 100.0
                1574.2 - 556.01 * u + 71.23472 * u * u + 0.319781 * u * u * u -
                    0.8503463 * u * u * u * u - 0.005050998 * u * u * u * u * u +
                    0.0083572073 * u * u * u * u * u * u
            }

            // ------------------------------------------------------------------
            // +1600 … +1700   (t = y − 1600)
            // ------------------------------------------------------------------
            y < 1700.0 -> {
                val t = y - 1600.0
                120.0 - 0.9808 * t - 0.01532 * t * t + t * t * t / 7129.0
            }

            // ------------------------------------------------------------------
            // +1700 … +1800   (t = y − 1700)
            // ------------------------------------------------------------------
            y < 1800.0 -> {
                val t = y - 1700.0
                8.83 + 0.1603 * t - 0.0059285 * t * t + 0.00013336 * t * t * t -
                    t * t * t * t / 1174000.0
            }

            // ------------------------------------------------------------------
            // +1800 … +1860   (t = y − 1800)
            // ------------------------------------------------------------------
            y < 1860.0 -> {
                val t = y - 1800.0
                13.72 - 0.332447 * t + 0.0068612 * t * t + 0.0041116 * t * t * t -
                    0.00037436 * t * t * t * t + 0.0000121272 * t * t * t * t * t -
                    0.0000001699 * t * t * t * t * t * t +
                    0.000000000875 * t * t * t * t * t * t * t
            }

            // ------------------------------------------------------------------
            // +1860 … +1900   (t = y − 1860)
            // ------------------------------------------------------------------
            y < 1900.0 -> {
                val t = y - 1860.0
                7.62 + 0.5737 * t - 0.251754 * t * t + 0.01680668 * t * t * t -
                    0.0004473624 * t * t * t * t + t * t * t * t * t / 233174.0
            }

            // ------------------------------------------------------------------
            // +1900 … +1920   (t = y − 1900)
            // ------------------------------------------------------------------
            y < 1920.0 -> {
                val t = y - 1900.0
                -2.79 + 1.494119 * t - 0.0598939 * t * t + 0.0061966 * t * t * t -
                    0.000197 * t * t * t * t
            }

            // ------------------------------------------------------------------
            // +1920 … +1941   (t = y − 1920)
            // ------------------------------------------------------------------
            y < 1941.0 -> {
                val t = y - 1920.0
                21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t
            }

            // ------------------------------------------------------------------
            // +1941 … +1961   (t = y − 1950)
            // ------------------------------------------------------------------
            y < 1961.0 -> {
                val t = y - 1950.0
                29.07 + 0.407 * t - t * t / 233.0 + t * t * t / 2547.0
            }

            // ------------------------------------------------------------------
            // +1961 … +1986   (t = y − 1975)
            // ------------------------------------------------------------------
            y < 1986.0 -> {
                val t = y - 1975.0
                45.45 + 1.067 * t - t * t / 260.0 - t * t * t / 718.0
            }

            // ------------------------------------------------------------------
            // +1986 … +2005   (t = y − 2000)
            // The quadratic coefficient here is 0.060374. An earlier revision of this
            // file used 0.006037 (a factor of ten small) and dropped the t⁴ and t⁵
            // terms; see DeltaTPolynomialTest.`rejects the historical 0.006037 coefficient`.
            // ------------------------------------------------------------------
            y < 2005.0 -> {
                val t = y - 2000.0
                63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t +
                    0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t
            }

            // ------------------------------------------------------------------
            // +2005 … +2050   (t = y − 2000)
            // ------------------------------------------------------------------
            y < 2050.0 -> {
                val t = y - 2000.0
                62.92 + 0.32217 * t + 0.005589 * t * t
            }

            // ------------------------------------------------------------------
            // +2050 … +2150
            // The −0.5628·(2150 − y) term exists solely to remove the discontinuity
            // at 2050 (it vanishes at y = 2150, matching the parabola below).
            // ------------------------------------------------------------------
            else -> {
                val u = (y - 1820.0) / 100.0
                -20.0 + 32.0 * u * u - 0.5628 * (2150.0 - y)
            }
        }
    }

    // ============================================================
    // Utility Methods
    // ============================================================

    /**
     * Returns the year (Gregorian) corresponding to this time.
     */
    fun getYear(): Int {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = utcMs
        }
        return cal.get(java.util.Calendar.YEAR)
    }

    override fun toString(): String {
        return "AstroTime(JD_UTC=${"%.5f".format(jdUtc)}, JD_TT=${"%.5f".format(jdTt)}, ΔT=${"%.2f".format(deltaT)}s)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AstroTime) return false
        return utcMs == other.utcMs
    }

    override fun hashCode(): Int {
        return utcMs.hashCode()
    }
}
