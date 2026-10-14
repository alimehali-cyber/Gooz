package com.alijafari.red.astronomy

import com.alijafari.red.astronomy.astro_engine.EclipseEngine
import com.alijafari.red.astronomy.domain.CalendarSystem
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*

/**
 * TEMPORARY DIAGNOSTIC — prints the next several solar and lunar eclipses for
 * a few observer locations so the visibility filter's output can be checked
 * against independent astronomy. Delete before merge.
 */
class ZzDiagnosticDumpTest {

    private val engine = EclipseEngine()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun dump(label: String, lat: Double, lon: Double, elev: Double, tz: String): String {
        val nowMs = 1_784_000_000_000L // 2026-07-14
        val (solar, lunar) = engine.getNextEclipseSeries(
            nowMs, lat, lon, elev, tz, CalendarSystem.SOLAR_HIJRI, 6
        )
        val sb = StringBuilder("\n### $label ###")
        for ((kind, list) in listOf("SOLAR" to solar, "LUNAR" to lunar)) {
            sb.append("\n-- $kind (${list.size}) --")
            list.forEach { r ->
                val e = r.event
                sb.append(
                    "\n$kind|" +
                        "date=${fmt.format(Date(e.maximumMs))}Z|" +
                        "type=${e.type}|" +
                        "mag=${e.magnitude}|" +
                        "gamma=${e.gamma}|" +
                        "up=${r.isLocallyVisible}|" +
                        "alt=${"%.1f".format(Locale.US, r.targetAltitudeDeg)}|" +
                        "obsc=${r.localObscurationPercent}|" +
                        "localMag=${"%.3f".format(Locale.US, r.localMagnitude)}|" +
                        "obs=${r.visibility.isObservable}|" +
                        "grade=${r.visibility.grade}"
                )
            }
        }
        return sb.toString()
    }

    @Test
    fun dumpTehran() {
        org.junit.Assert.fail(dump("Tehran", 35.6892, 51.3890, 1200.0, "Asia/Tehran"))
    }

    @Test
    fun dumpSydney() {
        org.junit.Assert.fail(dump("Sydney", -33.8688, 151.2093, 20.0, "Australia/Sydney"))
    }

    @Test
    fun dumpLuxor() {
        org.junit.Assert.fail(dump("Luxor", 25.6872, 32.6396, 89.0, "Africa/Cairo"))
    }
}
