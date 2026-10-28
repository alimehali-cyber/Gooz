package com.alijafari.red.astronomy.ui.rendering

import com.alijafari.red.astronomy.astro_engine.TimeEngine

/**
 * The single definition of "the instant the sky is shown for". Pure Kotlin, no Compose.
 *
 * The Home Sky Canvas and the Live Sky backdrop both derive their instant here, so they cannot drift apart:
 *  - base instant: the Time Machine simulation instant in simulation mode, otherwise the live clock;
 *  - effective instant: the base instant shifted by the horizontal drag offset (±[MAX_DRAG_OFFSET_HOURS]),
 *    including the animated return to zero.
 */
object SkyTimeModel {

    /** Largest horizontal drag offset, in hours, on either side of the base instant. */
    const val MAX_DRAG_OFFSET_HOURS: Float = 12f

    /** Base instant before the drag offset is applied. */
    fun baseTimeMs(isSimulation: Boolean, simulationTimeMs: Long, liveTimeMs: Long): Long =
        if (isSimulation) simulationTimeMs else liveTimeMs

    /**
     * Julian Date of the effective instant. The arithmetic is the same one the Home hero has always used:
     * the base Julian Date plus the offset in days, as a double.
     */
    fun effectiveJd(baseTimeMs: Long, dragOffsetHours: Float): Double =
        TimeEngine.getJulianDate(baseTimeMs) + (dragOffsetHours / 24.0)
}
