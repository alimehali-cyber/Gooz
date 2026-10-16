package com.alijafari.red.astronomy.astro_engine

import kotlin.math.*

/**
 * Planet engine that computes geocentric apparent positions.
 * Delegates to VSOP87Engine for heliocentric coordinates,
 * then applies light-time correction and geometric transformation.
 */
object PlanetEngine {

    enum class PlanetType(
        val nameEn: String,
        val nameFa: String,
        val magnitudeBase: Double,
        val semiMajorAxisAU: Double,
        val eccentricity: Double,
        val inclinationDeg: Double,
        val meanLongitudeJ2000: Double,
        val longitudePerihelionDeg: Double,
        val longitudeNodeDeg: Double,
        val orbitalPeriodYears: Double,
        val equatorialRadiusKm: Double,
        val descriptionEn: String,
        val descriptionFa: String
    ) {
        MERCURY(
            "Mercury", "تیر (عطارد)", -0.42, 0.387098, 0.205630, 7.00487, 252.25084, 77.45645, 48.33167, 0.240846, 2439.7,
            "Smallest planet, closest to the Sun. Swiftly moves through twilight.",
            "کوچک‌ترین سیاره منظومه شمسی و نزدیک‌ترین سیاره به خورشید."
        ),
        VENUS(
            "Venus", "ناهید (زهره)", -4.40, 0.723332, 0.006773, 3.39471, 181.97973, 131.53298, 76.68069, 0.615197, 6051.8,
            "Brightest planet, often known as the Morning or Evening Star.",
            "درخشان‌ترین سیاره آسمان شب، معروف به ستاره صبحگاهی یا شامگاهی."
        ),
        MARS(
            "Mars", "بهرام (مریخ)", -1.52, 1.523679, 0.093405, 1.84973, 355.45332, 336.04084, 49.55740, 1.880848, 3396.2,
            "The Red Planet with iron-oxide surface dust and polar ice caps.",
            "سیاره سرخ با خاک حاوی اکسید آهن و کلاهک‌های یخی قطبی."
        ),
        JUPITER(
            "Jupiter", "هرمز (مشتری)", -2.70, 5.202603, 0.048498, 1.30300, 34.40438, 14.72848, 100.46444, 11.862615, 71492.0,
            "Gas giant, largest planet with Great Red Spot and 4 Galilean moons.",
            "غول گازی و بزرگ‌ترین سیاره منظومه شمسی همراه با ۴ قمر گالیله‌ای."
        ),
        SATURN(
            "Saturn", "کیوان (زحل)", 0.20, 9.554909, 0.055546, 2.48869, 49.94432, 92.43194, 113.66550, 29.447498, 60268.0,
            "Spectacular ring system visible through telescopes and Titan moon.",
            "سیاره شگفت‌انگیز با حلقه‌های یخی باشکوه و قمر عظیم تیتان."
        ),
        URANUS(
            "Uranus", "اورانوس", 5.52, 19.218446, 0.046296, 0.77319, 313.23218, 170.96424, 74.00595, 84.016846, 25559.0,
            "Ice giant with pale cyan hue, tilted on its orbital side.",
            "غول یخی متمایل به رنگ فیروزه‌ای زیبا با زاویه انحراف محوری شدید."
        ),
        NEPTUNE(
            "Neptune", "نپتون", 7.84, 30.110387, 0.009456, 1.76995, 304.88003, 44.97135, 131.78060, 164.79132, 24764.0,
            "Deep blue ice giant, outermost planet of solar system.",
            "دورترین سیاره شناخته‌شده منظومه شمسی به رنگ آبی نیلی عمیق."
        ),
        PLUTO(
            "Pluto", "پلوتو", 15.1, 39.481686, 0.248807, 17.16, 238.92881, 224.06676, 110.30347, 247.92065, 1188.3,
            "Dwarf planet in the Kuiper belt with large heart-shaped nitrogen glacier.",
            "سیاره کوتوله کمربند کایپر با یخچال نیتروژنی قلبی‌شکل معروف."
        )
    }

    data class PlanetPosition(
        val planet: PlanetType,
        val raDeg: Double,
        val decDeg: Double,
        val distanceAU: Double,
        val heliocentricDistanceAU: Double,
        val magnitude: Double,
        val phaseAngleDeg: Double,
        val illuminatedFraction: Double,
        val angularDiameterArcsec: Double
    ) {
        val distanceAu: Double get() = distanceAU
        val heliocentricDistanceAu: Double get() = heliocentricDistanceAU
    }

    private val vsop87 = VSOP87Engine()

    private const val DEG2RAD = Math.PI / 180.0
    private const val RAD2DEG = 180.0 / Math.PI

    private fun normalizeAngle(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    private fun meanObliquity(jcTt: Double): Double {
        val t = jcTt
        return 23.43929111 - 0.0130041667 * t - 0.00000016389 * t * t
    }

    /**
     * Calculates Geocentric Equatorial Position (RA, Dec) for a planet at given AstroTime.
     */
    fun calculatePlanet(planet: PlanetType, astroTime: AstroTime): PlanetPosition {
        if (planet == PlanetType.PLUTO) {
            return calculatePlutoFallback(astroTime)
        }

        val vsop87Planet = when (planet) {
            PlanetType.MERCURY -> VSOP87Engine.Planet.MERCURY
            PlanetType.VENUS -> VSOP87Engine.Planet.VENUS
            PlanetType.MARS -> VSOP87Engine.Planet.MARS
            PlanetType.JUPITER -> VSOP87Engine.Planet.JUPITER
            PlanetType.SATURN -> VSOP87Engine.Planet.SATURN
            PlanetType.URANUS -> VSOP87Engine.Planet.URANUS
            PlanetType.NEPTUNE -> VSOP87Engine.Planet.NEPTUNE
            PlanetType.PLUTO -> VSOP87Engine.Planet.NEPTUNE
        }

        // Earth heliocentric coordinates at observation time
        val earthHelio = vsop87.calculate(VSOP87Engine.Planet.EARTH, astroTime)
        val xEarth = earthHelio.distanceAu * cos(earthHelio.latitudeDeg * DEG2RAD) * cos(earthHelio.longitudeDeg * DEG2RAD)
        val yEarth = earthHelio.distanceAu * cos(earthHelio.latitudeDeg * DEG2RAD) * sin(earthHelio.longitudeDeg * DEG2RAD)
        val zEarth = earthHelio.distanceAu * sin(earthHelio.latitudeDeg * DEG2RAD)

        // Light-time iteration
        var targetAstroTime = astroTime
        var geoDistAu = 1.0
        var xGeo = 0.0
        var yGeo = 0.0
        var zGeo = 0.0
        var currentHelio = vsop87.calculate(vsop87Planet, astroTime)

        for (iter in 0..2) {
            currentHelio = vsop87.calculate(vsop87Planet, targetAstroTime)

            val xPlanet = currentHelio.distanceAu * cos(currentHelio.latitudeDeg * DEG2RAD) * cos(currentHelio.longitudeDeg * DEG2RAD)
            val yPlanet = currentHelio.distanceAu * cos(currentHelio.latitudeDeg * DEG2RAD) * sin(currentHelio.longitudeDeg * DEG2RAD)
            val zPlanet = currentHelio.distanceAu * sin(currentHelio.latitudeDeg * DEG2RAD)

            xGeo = xPlanet - xEarth
            yGeo = yPlanet - yEarth
            zGeo = zPlanet - zEarth

            geoDistAu = sqrt(xGeo * xGeo + yGeo * yGeo + zGeo * zGeo)
            val lightTimeDays = geoDistAu * 0.0057755183 // Light-time constant in days/AU
            val targetJd = astroTime.jdTt - lightTimeDays
            targetAstroTime = AstroTime.fromJd(targetJd)
        }

        val geoLonDeg = normalizeAngle(atan2(yGeo, xGeo) * RAD2DEG)
        val geoLatDeg = asin((zGeo / geoDistAu).coerceIn(-1.0, 1.0)) * RAD2DEG

        // Obliquity of date
        val epsDeg = meanObliquity(astroTime.jcTt)
        val epsRad = epsDeg * DEG2RAD

        val lRad = geoLonDeg * DEG2RAD
        val bRad = geoLatDeg * DEG2RAD

        // Convert Ecliptic to Equatorial coordinates
        val sinDec = sin(bRad) * cos(epsRad) + cos(bRad) * sin(epsRad) * sin(lRad)
        val decRad = asin(sinDec.coerceIn(-1.0, 1.0))

        val yRa = sin(lRad) * cos(epsRad) - tan(bRad) * sin(epsRad)
        val xRa = cos(lRad)
        val raRad = atan2(yRa, xRa)

        val raDeg = normalizeAngle(raRad * RAD2DEG)
        val decDeg = decRad * RAD2DEG

        // Phase angle
        val rAU = currentHelio.distanceAu
        val R_earth = earthHelio.distanceAu
        val cosPhase = (rAU * rAU + geoDistAu * geoDistAu - R_earth * R_earth) / (2.0 * rAU * geoDistAu)
        val phaseAngleDeg = acos(cosPhase.coerceIn(-1.0, 1.0)) * RAD2DEG
        val illuminatedFraction = (1.0 + cos(phaseAngleDeg * DEG2RAD)) / 2.0

        // Apparent V magnitude (Mallama & Hilton 2018, with the rings of Saturn and the sub-latitudes of Uranus).
        val helioVec = doubleArrayOf(
            currentHelio.distanceAu * cos(currentHelio.latitudeDeg * DEG2RAD) * cos(currentHelio.longitudeDeg * DEG2RAD),
            currentHelio.distanceAu * cos(currentHelio.latitudeDeg * DEG2RAD) * sin(currentHelio.longitudeDeg * DEG2RAD),
            currentHelio.distanceAu * sin(currentHelio.latitudeDeg * DEG2RAD)
        )
        val mag = apparentMagnitude(planet, helioVec, doubleArrayOf(xGeo, yGeo, zGeo), astroTime.jcTt)

        val diamArcsec = (2.0 * planet.equatorialRadiusKm / (geoDistAu * 149597870.7)) * 206264.8

        return PlanetPosition(
            planet = planet,
            raDeg = raDeg,
            decDeg = decDeg,
            distanceAU = geoDistAu,
            heliocentricDistanceAU = rAU,
            magnitude = mag,
            phaseAngleDeg = phaseAngleDeg,
            illuminatedFraction = illuminatedFraction,
            angularDiameterArcsec = diamArcsec
        )
    }

    /**
     * Compatibility overload accepting raw Julian Date.
     */
    fun calculatePlanet(planet: PlanetType, jd: Double): PlanetPosition {
        return calculatePlanet(planet, AstroTime.fromJd(jd))
    }

    /**
     * Apparent V magnitude from Mallama & Hilton (2018, Astronomy and Computing 25, 10–24), the formulas
     * used by The Astronomical Almanac, with the coefficients of the authors' reference code Ap_Mag_V3
     * (also used by Skyfield): Mercury 6th-order and Venus piecewise 4th-order phase curves, Mars,
     * Jupiter, Saturn with its rings (saturnicentric sub-Sun and sub-Earth latitudes), Uranus with its
     * sub-latitudes, and Neptune with its secular brightening.
     *
     * [helio] and [geo] are the planet's heliocentric and geocentric vectors (AU) in the ecliptic and
     * equinox of date; [jcTt] is Julian centuries TT from J2000.0.
     */
    internal fun apparentMagnitude(planet: PlanetType, helio: DoubleArray, geo: DoubleArray, jcTt: Double): Double {
        val r = sqrt(helio[0] * helio[0] + helio[1] * helio[1] + helio[2] * helio[2])
        val d = sqrt(geo[0] * geo[0] + geo[1] * geo[1] + geo[2] * geo[2])
        val a = acos(((helio[0] * geo[0] + helio[1] * geo[1] + helio[2] * geo[2]) / (r * d)).coerceIn(-1.0, 1.0)) * RAD2DEG
        val dist = 5.0 * log10(r * d)
        return when (planet) {
            PlanetType.MERCURY -> -0.613 + dist + a * (6.3280e-02 + a * (-1.6336e-03 + a * (3.3644e-05 + a * (-3.4265e-07 + a * (1.6893e-09 + a * -3.0334e-12)))))
            PlanetType.VENUS -> if (a < 163.7) {
                -4.384 + dist + a * (-1.044e-03 + a * (3.687e-04 + a * (-2.814e-06 + a * 8.938e-09)))
            } else {
                236.05828 + dist + a * (-2.81914 + a * 8.39034e-03)
            }
            PlanetType.MARS -> if (a <= 50.0) {
                -1.601 + dist + a * (2.267e-02 - 1.302e-04 * a)
            } else {
                -0.367 + dist + a * (-0.02573 + 0.0003445 * a)
            }
            PlanetType.JUPITER -> if (a <= 12.0) {
                -9.395 + dist + (6.16e-04 * a - 3.7e-04) * a
            } else {
                val x = a / 180.0
                -9.428 + dist - 2.5 * log10(((((-1.876 * x + 2.809) * x - 0.062) * x - 0.363) * x - 1.507) * x + 1.0)
            }
            PlanetType.SATURN -> {
                val pole = poleEclipticOfDate(40.589, 83.537, jcTt)
                val sunLat = subLatitudeDeg(pole, helio)
                val earthLat = subLatitudeDeg(pole, geo)
                val product = sunLat * earthLat
                val beta = if (product >= 0.0) sqrt(product) else 0.0
                if (a <= 6.5 && beta <= 27.0) {
                    val sb = sin(beta * DEG2RAD)
                    -8.914 - 1.825 * sb + 0.026 * a - 0.378 * sb * exp(-2.25 * a) + dist
                } else if (a <= 6.5) {
                    -8.95 - 3.7e-4 * a + 6.16e-4 * a * a + dist
                } else {
                    -8.94 + a * (2.446e-4 + a * (2.672e-4 + a * (-1.506e-6 + a * 4.767e-9))) + dist
                }
            }
            PlanetType.URANUS -> {
                val pole = poleEclipticOfDate(257.311, -15.175, jcTt)
                val f = 0.02293
                fun planetographic(latC: Double): Double = atan(tan(latC * DEG2RAD) / ((1.0 - f) * (1.0 - f))) * RAD2DEG
                val subLat = (abs(planetographic(subLatitudeDeg(pole, helio))) + abs(planetographic(subLatitudeDeg(pole, geo)))) / 2.0
                -7.110 + dist - 0.00084 * subLat + (if (a > 3.1) (1.045e-4 * a + 6.587e-3) * a else 0.0)
            }
            PlanetType.NEPTUNE -> {
                val year = 2000.0 + 100.0 * jcTt
                val v0 = (-6.89 - 0.0054 * (year - 1980.0)).coerceIn(-7.00, -6.89)
                v0 + dist + (if (a > 1.9 && year >= 2000.0) 7.944e-3 * a + 9.617e-5 * a * a else 0.0)
            }
            PlanetType.PLUTO -> -1.01 + dist + 0.041 * a
        }
    }

    /**
     * A planet's north pole (IAU, ICRF right ascension/declination) in the ecliptic and equinox of date:
     * rotated to the J2000 ecliptic (ε0 = 23°26′21.448″) and precessed in longitude (IAU 2006 p_A).
     */
    private fun poleEclipticOfDate(raDeg: Double, decDeg: Double, jcTt: Double): DoubleArray {
        val ra = raDeg * DEG2RAD; val dec = decDeg * DEG2RAD
        val x = cos(dec) * cos(ra); val y = cos(dec) * sin(ra); val z = sin(dec)
        val e = 23.4392911 * DEG2RAD
        val xe = x
        val ye = y * cos(e) + z * sin(e)
        val ze = -y * sin(e) + z * cos(e)
        val p = (5028.796195 * jcTt + 1.1054348 * jcTt * jcTt) / 3600.0 * DEG2RAD
        return doubleArrayOf(xe * cos(p) - ye * sin(p), xe * sin(p) + ye * cos(p), ze)
    }

    /** Planetocentric latitude (deg) of the point facing the origin of [v] (v = from that body to the planet). */
    private fun subLatitudeDeg(pole: DoubleArray, v: DoubleArray): Double {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return -asin(((pole[0] * v[0] + pole[1] * v[1] + pole[2] * v[2]) / n).coerceIn(-1.0, 1.0)) * RAD2DEG
    }

    private fun calculatePlutoFallback(astroTime: AstroTime): PlanetPosition {
        val pluto = PlanetType.PLUTO
        val d = astroTime.jdTt - 2451545.0

        // Keplerian orbital elements for Pluto
        val a = pluto.semiMajorAxisAU
        val e = pluto.eccentricity
        val incRad = pluto.inclinationDeg * DEG2RAD
        val nodeRad = pluto.longitudeNodeDeg * DEG2RAD
        val periRad = pluto.longitudePerihelionDeg * DEG2RAD
        val meanLongJ2000 = pluto.meanLongitudeJ2000

        // Mean daily motion (deg/day)
        val nDeg = 360.0 / (pluto.orbitalPeriodYears * 365.25)
        val Mdeg = normalizeAngle(meanLongJ2000 - pluto.longitudePerihelionDeg + nDeg * d)
        val Mrad = Mdeg * DEG2RAD

        // Solve Kepler's equation for Eccentric Anomaly E
        var E = Mrad
        for (i in 0..5) {
            E = E - (E - e * sin(E) - Mrad) / (1.0 - e * cos(E))
        }

        // True anomaly v
        val v = 2.0 * atan2(sqrt(1.0 + e) * sin(E / 2.0), sqrt(1.0 - e) * cos(E / 2.0))
        val rAU = a * (1.0 - e * cos(E))

        // Argument of latitude u = v + (longitudePerihelion - longitudeNode)
        val omegaRad = periRad - nodeRad
        val u = v + omegaRad

        // Heliocentric ecliptic rectangular coordinates for Pluto
        val xPluto = rAU * (cos(nodeRad) * cos(u) - sin(nodeRad) * sin(u) * cos(incRad))
        val yPluto = rAU * (sin(nodeRad) * cos(u) + cos(nodeRad) * sin(u) * cos(incRad))
        val zPluto = rAU * (sin(u) * sin(incRad))

        // Earth heliocentric position
        val earthHelio = vsop87.calculate(VSOP87Engine.Planet.EARTH, astroTime)
        val eLatRad = earthHelio.latitudeDeg * DEG2RAD
        val eLonRad = earthHelio.longitudeDeg * DEG2RAD
        val xEarth = earthHelio.distanceAu * cos(eLatRad) * cos(eLonRad)
        val yEarth = earthHelio.distanceAu * cos(eLatRad) * sin(eLonRad)
        val zEarth = earthHelio.distanceAu * sin(eLatRad)

        // Geocentric ecliptic coordinates
        val xGeo = xPluto - xEarth
        val yGeo = yPluto - yEarth
        val zGeo = zPluto - zEarth

        val geoDistAu = sqrt(xGeo * xGeo + yGeo * yGeo + zGeo * zGeo)
        val geoLonDeg = normalizeAngle(atan2(yGeo, xGeo) * RAD2DEG)
        val geoLatDeg = asin((zGeo / geoDistAu).coerceIn(-1.0, 1.0)) * RAD2DEG

        // Obliquity of date
        val epsRad = meanObliquity(astroTime.jcTt) * DEG2RAD
        val lRad = geoLonDeg * DEG2RAD
        val bRad = geoLatDeg * DEG2RAD

        // Ecliptic to Equatorial
        val sinDec = sin(bRad) * cos(epsRad) + cos(bRad) * sin(epsRad) * sin(lRad)
        val decRad = asin(sinDec.coerceIn(-1.0, 1.0))
        val yRa = sin(lRad) * cos(epsRad) - tan(bRad) * sin(epsRad)
        val xRa = cos(lRad)
        val raRad = atan2(yRa, xRa)

        val raDeg = normalizeAngle(raRad * RAD2DEG)
        val decDeg = decRad * RAD2DEG

        // Phase angle (Sun–Pluto–Earth) and V magnitude of Pluto + Charon: Explanatory Supplement to the
        // Astronomical Almanac (1992) Table 7.48.1, V(1,0) = −1.01 (Harris 1961), 0.041 mag/deg
        // (Binzel & Mulholland 1984); JPL Horizons fits −1.00 / 0.041 for 2020–2026.
        val dotHg = xPluto * xGeo + yPluto * yGeo + zPluto * zGeo
        val phaseAngle = acos((dotHg / (rAU * geoDistAu)).coerceIn(-1.0, 1.0)) * RAD2DEG
        val mag = -1.01 + 5.0 * log10(rAU * geoDistAu) + 0.041 * phaseAngle
        val diamArcsec = (2.0 * pluto.equatorialRadiusKm / (geoDistAu * 149597870.7)) * 206264.8

        return PlanetPosition(
            planet = PlanetType.PLUTO,
            raDeg = raDeg,
            decDeg = decDeg,
            distanceAU = geoDistAu,
            heliocentricDistanceAU = rAU,
            magnitude = mag,
            phaseAngleDeg = phaseAngle,
            illuminatedFraction = (1.0 + cos(phaseAngle * DEG2RAD)) / 2.0,
            angularDiameterArcsec = diamArcsec
        )
    }
}


