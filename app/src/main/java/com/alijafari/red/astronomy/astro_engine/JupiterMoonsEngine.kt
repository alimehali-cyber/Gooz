package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

object JupiterMoonsEngine {

    enum class GalileanMoon(
        val nameEn: String,
        val nameFa: String,
        val meanRadiusKm: Double,
        val orbitalPeriodDays: Double,
        val meanSemiMajorAxisRJ: Double
    ) {
        IO("Io", "آیو", 1821.6, 1.7691377, 5.90),
        EUROPA("Europa", "اروپا", 1560.8, 3.5511810, 9.40),
        GANYMEDE("Ganymede", "گانیمد", 2634.1, 7.1545530, 14.99),
        CALLISTO("Callisto", "کالیستو", 2410.3, 16.6890180, 26.37),
        ELARA("Elara", "الارا", 43.0, 259.65, 164.2)
    }

    enum class MoonPhenomenon {
        VISIBLE,
        IN_TRANSIT,       // Moon in front of Jupiter's disk
        OCCULTED,         // Moon behind Jupiter's disk
        IN_ECLIPSE,       // Moon inside Jupiter's shadow cone
        SHADOW_TRANSIT    // Shadow projected onto Jupiter
    }

    data class MoonPosition(
        val moon: GalileanMoon,
        val xRJ: Double,          // offset along Jupiter's equator in equatorial radii, +East on the sky
        val yRJ: Double,          // offset along Jupiter's rotation axis in equatorial radii, +North
        val zRJ: Double,          // depth in equatorial radii, + farther from the observer than Jupiter
        val offsetRaArcsec: Double,   // ΔRA (seconds of arc of RA, i.e. already divided by cos δ)
        val offsetDecArcsec: Double,  // ΔDec, seconds of arc
        val angularDistanceArcsec: Double,
        val phenomenon: MoonPhenomenon
    )

    data class JupiterSystem(
        val jupiterPos: PlanetEngine.PlanetPosition,
        val moons: List<MoonPosition>,
        val grsLongitudeDeg: Double,
        val isGrsVisible: Boolean,
        /** Position angle of Jupiter's north pole on the sky (degrees, from north through east). */
        val polePositionAngleDeg: Double = 0.0
    )

    private const val DEG2RAD = Math.PI / 180.0
    private const val RAD2DEG = 180.0 / Math.PI

    /**
     * Positions of the Galilean satellites (Meeus, *Astronomical Algorithms* 2nd ed., ch. 44, the
     * lower-accuracy method: ~1 arcsecond-class, ample for display), rotated onto the sky with the
     * position angle of Jupiter's north pole from the IAU rotation model.
     *
     * Earlier revisions mixed constants of the two Meeus methods (the angle J advanced at
     * 0.904701°/day instead of 0.9025179°/day, i.e. ~0.82°/day of drift), added the equatorial x
     * offset straight to RA without the cos δ factor or the pole rotation, and used x as +East where
     * Meeus' x is +West.
     *
     * @param jd Julian Date (UTC)
     */
    fun calculateJupiterMoons(jd: Double): JupiterSystem {
        val jupPos = PlanetEngine.calculatePlanet(PlanetEngine.PlanetType.JUPITER, jd)
        val jde = AstroTime.fromJd(jd).jdTt
        val d = jde - 2451545.0

        val v = 172.74 + 0.00111588 * d
        val m = 357.529 + 0.9856003 * d
        val n = 20.020 + 0.0830853 * d + 0.329 * sinD(v)
        val j = 66.115 + 0.9025179 * d - 0.329 * sinD(v)
        val a = 1.915 * sinD(m) + 0.020 * sinD(2 * m)
        val b = 5.555 * sinD(n) + 0.168 * sinD(2 * n)
        val k = j + a - b
        val rEarth = 1.00014 - 0.01671 * cosD(m) - 0.00014 * cosD(2 * m)
        val rJup = 5.20872 - 0.25208 * cosD(n) - 0.00611 * cosD(2 * n)
        val delta = sqrt(rJup * rJup + rEarth * rEarth - 2 * rJup * rEarth * cosD(k))
        val psi = asin((rEarth / delta * sinD(k)).coerceIn(-1.0, 1.0)) * RAD2DEG
        val dl = d - delta / 173.0

        val u1a = 163.8069 + 203.4058646 * dl + psi - b
        val u2a = 358.4140 + 101.2916335 * dl + psi - b
        val u3a = 5.7176 + 50.2345180 * dl + psi - b
        val u4a = 224.8092 + 21.4879800 * dl + psi - b
        val g = 331.18 + 50.310482 * dl
        val h = 87.45 + 21.569231 * dl
        val u1 = u1a + 0.473 * sinD(2 * (u1a - u2a))
        val u2 = u2a + 1.065 * sinD(2 * (u2a - u3a))
        val u3 = u3a + 0.165 * sinD(g)
        val u4 = u4a + 0.843 * sinD(h)
        val r1 = 5.9057 - 0.0244 * cosD(2 * (u1a - u2a))
        val r2 = 9.3966 - 0.0882 * cosD(2 * (u2a - u3a))
        val r3 = 14.9883 - 0.0216 * cosD(g)
        val r4 = 26.3627 - 0.1939 * cosD(h)
        val lambda = 34.35 + 0.083091 * d + 0.329 * sinD(v) + b
        val ds = 3.12 * sinD(lambda + 42.8)
        val de = ds - 2.22 * sinD(psi) * cosD(lambda + 22.0) - 1.30 * (rJup - delta) / delta * sinD(lambda - 100.5)

        // Position angle of Jupiter's north pole (IAU 2015 pole; Jupiter's apparent RA/Dec of date).
        val t = d / 36525.0
        val a0 = (268.056595 - 0.006499 * t) * DEG2RAD
        val d0 = (64.495303 + 0.002413 * t) * DEG2RAD
        val ra = jupPos.raDeg * DEG2RAD
        val dec = jupPos.decDeg * DEG2RAD
        val pa = atan2(cos(d0) * sin(a0 - ra), sin(d0) * cos(dec) - cos(d0) * sin(dec) * cos(a0 - ra))
        val sinP = sin(pa); val cosP = cos(pa)

        val jupAngularRadiusArcsec = jupPos.angularDiameterArcsec / 2.0
        val cosDec = max(cos(dec), 1e-6)

        val moonPositions = GalileanMoon.values().map { moon ->
            val (r, u) = when (moon) {
                GalileanMoon.IO -> r1 to u1
                GalileanMoon.EUROPA -> r2 to u2
                GalileanMoon.GANYMEDE -> r3 to u3
                GalileanMoon.CALLISTO -> r4 to u4
                // Elara (mag 16.3) is far beyond naked-eye and phone reach; a circular orbit is kept only
                // so that the catalogue entry has a nominal position.
                GalileanMoon.ELARA -> 164.2 to (110.0 + 1.3863 * dl)
            }
            // Meeus: x positive towards the WEST, y towards the north (along the projected axis);
            // u is counted from inferior conjunction, so the satellite is in front of Jupiter at u = 0°.
            val xWest = r * sinD(u)
            val yNorth = -r * cosD(u) * sinD(de)
            val zAway = -r * cosD(u)

            // Rotate from Jupiter's axis frame to the sky (north/east).
            val dNorth = xWest * sinP + yNorth * cosP
            val dEast = -xWest * cosP + yNorth * sinP
            val offsetDecArcsec = dNorth * jupAngularRadiusArcsec
            val offsetEastArcsec = dEast * jupAngularRadiusArcsec
            val offsetRaArcsec = offsetEastArcsec / cosDec
            val angDistArcsec = hypot(offsetEastArcsec, offsetDecArcsec)

            val onDisk = xWest * xWest + yNorth * yNorth / (0.9351 * 0.9351) <= 1.0
            val phenomenon = when {
                onDisk && zAway < 0.0 -> MoonPhenomenon.IN_TRANSIT
                onDisk && zAway > 0.0 -> MoonPhenomenon.OCCULTED
                // Shadow cylinder behind Jupiter as seen from the Sun (phase angle ≤ 12°).
                zAway > 0.0 && abs(xWest + zAway * sinD(psi)) <= 1.0 -> MoonPhenomenon.IN_ECLIPSE
                else -> MoonPhenomenon.VISIBLE
            }

            MoonPosition(
                moon = moon,
                xRJ = -xWest,
                yRJ = yNorth,
                zRJ = zAway,
                offsetRaArcsec = offsetRaArcsec,
                offsetDecArcsec = offsetDecArcsec,
                angularDistanceArcsec = angDistArcsec,
                phenomenon = phenomenon
            )
        }

        // Great Red Spot: System II longitude drifts by roughly +1.75°/month in recent years; an
        // approximate model only (the GRS drift is irregular).
        val yearCurrent = 2000.0 + d / 365.25
        val grsLonDeg = ((15.0 + 21.0 * (yearCurrent - 2024.0)) % 360.0 + 360.0) % 360.0
        val cmIIDeg = ((180.0 + 870.27 * d) % 360.0 + 360.0) % 360.0
        val grsDiff = abs(((grsLonDeg - cmIIDeg) % 360.0 + 360.0) % 360.0)
        val isGrsVisible = grsDiff < 85.0 || grsDiff > 275.0

        return JupiterSystem(
            jupiterPos = jupPos,
            moons = moonPositions,
            grsLongitudeDeg = grsLonDeg,
            isGrsVisible = isGrsVisible,
            polePositionAngleDeg = ((pa * RAD2DEG) % 360.0 + 360.0) % 360.0
        )
    }

    /** Raw Meeus (x: +West, y: +North) coordinates in Jupiter radii for validation. */
    internal fun meeusXY(jd: Double): List<Pair<Double, Double>> =
        calculateJupiterMoons(jd).moons.take(4).map { -it.xRJ to it.yRJ }

    private fun sinD(x: Double) = sin(x * DEG2RAD)
    private fun cosD(x: Double) = cos(x * DEG2RAD)
}
