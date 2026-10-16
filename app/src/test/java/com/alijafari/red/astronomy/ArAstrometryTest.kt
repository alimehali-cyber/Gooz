package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.ar.core.AngleUtil
import com.alijafari.red.astronomy.ar.core.ArObserver
import com.alijafari.red.astronomy.ar.core.ArRefraction
import com.alijafari.red.astronomy.ar.core.ArSkyFrame
import com.alijafari.red.astronomy.ar.core.ArTimeScales
import com.alijafari.red.astronomy.ar.core.M3
import com.alijafari.red.astronomy.ar.core.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos

/**
 * Apparent places of the AR pipeline against ERFA/astropy 7 (IAU 2006/2000A, full SOFA apparent-place
 * chain, refraction off) for 2026-10-16 20:00:00 UTC at Frankfurt (50.1109 N, 8.6821 E, 112 m).
 * astropy used UT1−UTC = −0.036 s and polar motion (0.16″, 0.32″); the app assumes UT1 = UTC, which
 * accounts for a common ≈0.5″ rotation, hence the 1.5″ tolerance.
 */
class ArAstrometryTest {

    private val frame = ArSkyFrame(
        ArTimeScales.jdUtcFromEpochMillis(1792180800000L),
        ArObserver(50.1109, 8.6821, 112.0, 1013.25, 10.0)
    )

    private fun check(name: String, raDeg: Double, decDeg: Double, expAz: Double, expAlt: Double, pmRa: Double = 0.0, pmDec: Double = 0.0) {
        val v = frame.enuFromCatalog(raDeg, decDeg, pmRa, pmDec)
        val az = ArSkyFrame.azimuthDeg(v)
        val alt = ArSkyFrame.altitudeDeg(v)
        val dAzArcsec = AngleUtil.wrap180(az - expAz) * cos(Math.toRadians(alt)) * 3600.0
        val dAltArcsec = (alt - expAlt) * 3600.0
        assertTrue("$name azimuth off by $dAzArcsec arcsec", abs(dAzArcsec) < 1.5)
        assertTrue("$name altitude off by $dAltArcsec arcsec", abs(dAltArcsec) < 1.5)
    }

    @Test
    fun `stars match ERFA apparent places to arcsecond level`() {
        check("Vega", 279.234734787, 38.783688956, 274.9836486, 50.4040891)
        check("Polaris", 37.954560670, 89.264109028, 0.9360149, 50.2887469)
        check("Sirius", 101.287155333, -16.716115861, 70.8809999, -36.5370229)
        check("Antares", 247.351915458, -26.432002611, 249.6803522, -17.7539130)
        check("Altair", 297.695827, 8.868321, 228.5221837, 39.1735083)
        check("Capella", 79.172327, 45.997991, 47.8519227, 25.6025454)
    }

    @Test
    fun `proper motion is propagated from J2000`() {
        // Arcturus, BSC5 position and FK5 proper motion; astropy apply_space_motion() reference.
        check("Arcturus", (14 + 15 / 60.0 + 39.7 / 3600.0) * 15.0, 19 + 10 / 60.0 + 57 / 3600.0,
            304.7928469, -2.9254612, pmRa = -1.093, pmDec = -1.998)
    }

    @Test
    fun `sidereal time and precession-nutation matrix agree with ERFA`() {
        // erfa.gmst06 / gst06a with UT1 = UTC
        assertEquals(325.348657885, Math.toDegrees(frame.gmstRad), 2e-6)
        assertEquals(325.350730553, Math.toDegrees(frame.gastRad), 2e-6)
        // erfa.pnm06a element [0][1]; 2000B vs 2000A nutation differs by ~1 mas
        assertEquals(-0.006027216127, frame.npbMatrix[1], 2e-8)
        assertTrue(M3.orthonormalityError(frame.enuFromGcrs) < 1e-12)
    }

    @Test
    fun `J2000 catalogue positions are precessed - the old LAST-of-date shortcut was off by many arcminutes`() {
        // Using J2000 RA/Dec directly with the sidereal time of date misplaces Antares by ~22 arcmin in 2026.
        val v = frame.enuFromCatalog(247.351915458, -26.432002611)
        val naive = ArSkyFrame.enuFromAltAz(0.0, 0.0)
        val lst = frame.lastDeg
        val ha = Math.toRadians(lst - 247.351915458)
        val dec = Math.toRadians(-26.432002611)
        val lat = Math.toRadians(50.1109)
        val sinAlt = kotlin.math.sin(dec) * kotlin.math.sin(lat) + cos(dec) * cos(lat) * cos(ha)
        val alt = Math.toDegrees(kotlin.math.asin(sinAlt))
        val az = Math.toDegrees(kotlin.math.atan2(-cos(dec) * kotlin.math.sin(ha), kotlin.math.sin(dec) * cos(lat) - cos(dec) * kotlin.math.sin(lat) * cos(ha)))
        ArSkyFrame.enuFromAltAz(alt, AngleUtil.wrap360(az), naive)
        val sepArcmin = Math.toDegrees(V3.angleBetween(v, naive)) * 60.0
        assertTrue("expected ~22 arcmin difference, got $sepArcmin", sepArcmin in 18.0..26.0)
    }

    @Test
    fun `refraction formulas are mutually consistent and scale with pressure`() {
        for (h in listOf(0.0, 2.0, 10.0, 45.0)) {
            val r = ArRefraction.refractionFromTrueDeg(h, 1010.0, 10.0)
            val back = ArRefraction.refractionFromApparentDeg(h + r, 1010.0, 10.0)
            assertEquals("round trip at $h deg", r, back, 0.2 / 60.0)
        }
        val r0 = ArRefraction.refractionFromTrueDeg(0.0, 1010.0, 10.0) * 60.0
        assertTrue("horizon refraction ~29-34 arcmin, got $r0", r0 in 28.0..35.0)
        val high = ArRefraction.refractionFromTrueDeg(0.0, 800.0, 10.0)
        assertEquals(ArRefraction.refractionFromTrueDeg(0.0, 1010.0, 10.0) * 800.0 / 1010.0, high, 1e-9)
    }

    @Test
    fun `leap second table gives TT minus UTC of 69_184 s today`() {
        assertEquals(69.184, ArTimeScales.ttMinusUtcSeconds(2461330.0), 1e-9)
        assertEquals(32.184 + 32.0, ArTimeScales.ttMinusUtcSeconds(2451545.0), 1e-9)
    }
}
